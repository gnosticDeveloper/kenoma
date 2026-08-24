package raum.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import raum.models.ExportJob;
import raum.models.ExportJobStatus;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.ExportJobRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises poll()'s claim/complete/error-handling orchestration in isolation from the actual
 * pg_dump/psql/gzip mechanics (covered separately by TenantExportPollerIT against a real Postgres) —
 * runExport is stubbed out via a spy so this stays a fast, deterministic unit test.
 */
@ExtendWith(MockitoExtension.class)
class TenantExportPollerTest {

    @Mock
    private ExportJobRepository exportJobRepository;
    @Mock
    private CredentialsRepository credentialsRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private OpenBaoService openBaoService;
    @Mock
    private ArtifactStore artifactStore;
    @Mock
    private SqlCopyDumpWriter sqlCopyDumpWriter;

    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private TenantExportPoller spyPoller() {
        TenantExportPoller poller = new TenantExportPoller(
                exportJobRepository, credentialsRepository, serviceRepository, openBaoService, artifactStore, sqlCopyDumpWriter,
                "localhost", 5432, "raum", "postgres", "postgres");
        return spy(poller);
    }

    private ExportJob pendingJob() {
        return ExportJob.builder().id(jobId).orgId(orgId)
                .status(ExportJobStatus.PENDING.name()).requestedAt(Instant.now()).build();
    }

    @Test
    void poll_pendingJob_success_marksRunningThenDone() {
        TenantExportPoller poller = spyPoller();
        doReturn(Mono.just(List.of())).when(poller).runExport(any());
        when(exportJobRepository.findAllByStatusOrderByRequestedAtAsc(ExportJobStatus.PENDING.name()))
                .thenReturn(Flux.just(pendingJob()));
        // claim() and complete() mutate the same ExportJob instance in place, so an ArgumentCaptor
        // capturing references would show every call as the object's *final* state - snapshot the
        // status string (immutable) at invocation time instead, to actually verify the ordering.
        List<String> savedStatuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            savedStatuses.add(job.getStatus());
            return Mono.just(job);
        });

        poller.poll();

        verify(exportJobRepository, timeout(2000).times(2)).save(any());
        assertThat(savedStatuses).containsExactly(ExportJobStatus.RUNNING.name(), ExportJobStatus.DONE.name());

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(exportJobRepository, times(2)).save(saved.capture());
        ExportJob afterComplete = saved.getAllValues().get(1);
        assertThat(afterComplete.getStatus()).isEqualTo(ExportJobStatus.DONE.name());
        assertThat(afterComplete.getCompletedAt()).isNotNull();
        assertThat(afterComplete.getErrorMessage()).isNull();
    }

    @Test
    void poll_runExportFails_marksFailedWithErrorMessage() {
        TenantExportPoller poller = spyPoller();
        doReturn(Mono.error(new IllegalStateException("pg_dump exited 1: connection refused")))
                .when(poller).runExport(any());
        when(exportJobRepository.findAllByStatusOrderByRequestedAtAsc(ExportJobStatus.PENDING.name()))
                .thenReturn(Flux.just(pendingJob()));
        when(exportJobRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        poller.poll();

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(exportJobRepository, timeout(2000).times(2)).save(saved.capture());

        ExportJob afterComplete = saved.getAllValues().get(1);
        assertThat(afterComplete.getStatus()).isEqualTo(ExportJobStatus.FAILED.name());
        assertThat(afterComplete.getCompletedAt()).isNotNull();
        assertThat(afterComplete.getErrorMessage()).contains("connection refused");
    }

    @Test
    void poll_noPendingJobs_doesNothing() {
        TenantExportPoller poller = spyPoller();
        when(exportJobRepository.findAllByStatusOrderByRequestedAtAsc(ExportJobStatus.PENDING.name()))
                .thenReturn(Flux.empty());

        poller.poll();

        verify(exportJobRepository, never()).save(any());
        verifyNoMoreInteractions(artifactStore, openBaoService, credentialsRepository);
    }

    @Test
    void poll_onlyClaimsOldestPendingJob_ignoresRest() {
        TenantExportPoller poller = spyPoller();
        doReturn(Mono.just(List.of())).when(poller).runExport(any());
        ExportJob older = pendingJob();
        ExportJob newer = ExportJob.builder().id(UUID.randomUUID()).orgId(UUID.randomUUID())
                .status(ExportJobStatus.PENDING.name()).requestedAt(Instant.now().plusSeconds(60)).build();
        // findAllByStatusOrderByRequestedAtAsc already orders oldest-first at the query level; poll()
        // takes only the first element via .next(), so only "older" should ever be touched.
        when(exportJobRepository.findAllByStatusOrderByRequestedAtAsc(ExportJobStatus.PENDING.name()))
                .thenReturn(Flux.just(older, newer));
        when(exportJobRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        poller.poll();

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(exportJobRepository, timeout(2000).times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allMatch(job -> job.getId().equals(older.getId()));
    }
}
