package raum.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import raum.dto.ExportJobResponseDTO;
import raum.models.ExportJob;
import raum.models.ExportJobStatus;
import raum.models.Organization;
import raum.repository.ExportJobRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportJobServiceTest {

    @Mock
    private ExportJobRepository exportJobRepository;
    @Mock
    private OrganizationRepository organizationRepository;

    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private ExportJobService service() {
        return new ExportJobService(exportJobRepository, organizationRepository);
    }

    // --- requestExport ---

    @Test
    void requestExport_orgExists_savesPendingJob() {
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(Organization.builder().id(orgId).build()));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(eq(orgId), anyCollection())).thenReturn(Mono.empty());
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(jobId);
            return Mono.just(job);
        });

        StepVerifier.create(service().requestExport(orgId, null, null))
                .assertNext(dto -> {
                    assertThat(dto.getId()).isEqualTo(jobId);
                    assertThat(dto.getOrgId()).isEqualTo(orgId);
                    assertThat(dto.getStatus()).isEqualTo(ExportJobStatus.PENDING.name());
                    assertThat(dto.getRequestedAt()).isNotNull();
                    assertThat(dto.getStartedAt()).isNull();
                    assertThat(dto.getCompletedAt()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void requestExport_stoppedOrg_stillAllowed() {
        // Tenant export exists specifically for offboarding (issue #125) - a soft-deleted org
        // (stopped_at set) must still be exportable, unlike e.g. getAllOrgs()/getActiveOrgIds()
        // which deliberately filter stopped orgs out. Locks in that requestExport uses findById
        // (unfiltered), not an active-only lookup.
        Organization stopped = Organization.builder().id(orgId).stoppedAt(Instant.now()).build();
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(stopped));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(eq(orgId), anyCollection())).thenReturn(Mono.empty());
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(jobId);
            return Mono.just(job);
        });

        StepVerifier.create(service().requestExport(orgId, null, null))
                .assertNext(dto -> assertThat(dto.getStatus()).isEqualTo(ExportJobStatus.PENDING.name()))
                .verifyComplete();
    }

    @Test
    void requestExport_jsonFormat_savesJobWithFormat() {
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(Organization.builder().id(orgId).build()));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(eq(orgId), anyCollection())).thenReturn(Mono.empty());
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(jobId);
            return Mono.just(job);
        });

        StepVerifier.create(service().requestExport(orgId, "json", null))
                .assertNext(dto -> assertThat(dto.getFormat()).isEqualTo("JSON"))
                .verifyComplete();
    }

    @Test
    void requestExport_unknownFormat_throwsBadRequestWithoutSavingJob() {
        StepVerifier.create(service().requestExport(orgId, "yaml", null))
                .verifyError(BadRequestException.class);
        verify(exportJobRepository, never()).save(any());
        verify(organizationRepository, never()).findById(any(UUID.class));
    }

    @Test
    void requestExport_mergedLayout_savesJobWithLayout() {
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(Organization.builder().id(orgId).build()));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(eq(orgId), anyCollection())).thenReturn(Mono.empty());
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(jobId);
            return Mono.just(job);
        });

        StepVerifier.create(service().requestExport(orgId, "json", "merged"))
                .assertNext(dto -> {
                    assertThat(dto.getFormat()).isEqualTo("JSON");
                    assertThat(dto.getLayout()).isEqualTo("MERGED");
                })
                .verifyComplete();
    }

    @Test
    void requestExport_nullLayout_defaultsToSeparate() {
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(Organization.builder().id(orgId).build()));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(eq(orgId), anyCollection())).thenReturn(Mono.empty());
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(jobId);
            return Mono.just(job);
        });

        StepVerifier.create(service().requestExport(orgId, "csv", null))
                .assertNext(dto -> assertThat(dto.getLayout()).isEqualTo("SEPARATE"))
                .verifyComplete();
    }

    @Test
    void requestExport_unknownLayout_throwsBadRequestWithoutSavingJob() {
        StepVerifier.create(service().requestExport(orgId, "json", "combined"))
                .verifyError(BadRequestException.class);
        verify(exportJobRepository, never()).save(any());
        verify(organizationRepository, never()).findById(any(UUID.class));
    }

    @Test
    void requestExport_mergedLayoutWithSqlFormat_throwsBadRequestWithoutSavingJob() {
        StepVerifier.create(service().requestExport(orgId, "sql", "merged"))
                .verifyErrorSatisfies(e -> assertThat(e).isInstanceOf(BadRequestException.class)
                        .hasMessageContaining("SQL"));
        verify(exportJobRepository, never()).save(any());
        verify(organizationRepository, never()).findById(any(UUID.class));
    }

    @Test
    void requestExport_mergedLayoutWithDefaultSqlFormat_throwsBadRequest() {
        StepVerifier.create(service().requestExport(orgId, null, "merged"))
                .verifyError(BadRequestException.class);
        verify(exportJobRepository, never()).save(any());
    }

    @Test
    void requestExport_nullFormat_defaultsToSql() {
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(Organization.builder().id(orgId).build()));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(eq(orgId), anyCollection())).thenReturn(Mono.empty());
        when(exportJobRepository.save(any())).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(jobId);
            return Mono.just(job);
        });

        StepVerifier.create(service().requestExport(orgId, null, null))
                .assertNext(dto -> assertThat(dto.getFormat()).isEqualTo("SQL"))
                .verifyComplete();
    }

    @Test
    void requestExport_orgNotFound_throwsNotFoundWithoutSavingJob() {
        when(organizationRepository.findById(orgId)).thenReturn(Mono.empty());

        StepVerifier.create(service().requestExport(orgId, null, null))
                .verifyError(NotFoundException.class);
        verify(exportJobRepository, never()).save(any());
    }

    @Test
    void requestExport_activeJobAlreadyExists_returnsItWithoutSavingNew() {
        ExportJob existing = ExportJob.builder()
                .id(jobId).orgId(orgId).status(ExportJobStatus.RUNNING.name())
                .requestedAt(Instant.now().minusSeconds(30)).startedAt(Instant.now().minusSeconds(10))
                .build();
        when(organizationRepository.findById(orgId)).thenReturn(Mono.just(Organization.builder().id(orgId).build()));
        when(exportJobRepository.findFirstByOrgIdAndStatusIn(
                eq(orgId), eq(List.of(ExportJobStatus.PENDING.name(), ExportJobStatus.RUNNING.name()))))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service().requestExport(orgId, null, null))
                .assertNext(dto -> {
                    assertThat(dto.getId()).isEqualTo(jobId);
                    assertThat(dto.getStatus()).isEqualTo(ExportJobStatus.RUNNING.name());
                })
                .verifyComplete();
        verify(exportJobRepository, never()).save(any());
    }

    // --- getJob ---

    @Test
    void getJob_found_returnsDTO() {
        ExportJob job = ExportJob.builder()
                .id(jobId).orgId(orgId).status(ExportJobStatus.DONE.name())
                .requestedAt(Instant.now().minusSeconds(60))
                .startedAt(Instant.now().minusSeconds(50))
                .completedAt(Instant.now())
                .build();
        when(exportJobRepository.findByIdAndOrgId(jobId, orgId)).thenReturn(Mono.just(job));

        StepVerifier.create(service().getJob(orgId, jobId))
                .assertNext(dto -> {
                    assertThat(dto.getId()).isEqualTo(jobId);
                    assertThat(dto.getStatus()).isEqualTo(ExportJobStatus.DONE.name());
                    assertThat(dto.getCompletedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void getJob_notFound_throwsNotFound() {
        when(exportJobRepository.findByIdAndOrgId(jobId, orgId)).thenReturn(Mono.empty());

        StepVerifier.create(service().getJob(orgId, jobId))
                .verifyError(NotFoundException.class);
    }

    @Test
    void getJob_failedJob_surfacesErrorMessage() {
        ExportJob job = ExportJob.builder()
                .id(jobId).orgId(orgId).status(ExportJobStatus.FAILED.name())
                .requestedAt(Instant.now()).errorMessage("pg_dump exited 1: connection refused")
                .build();
        when(exportJobRepository.findByIdAndOrgId(jobId, orgId)).thenReturn(Mono.just(job));

        StepVerifier.create(service().getJob(orgId, jobId))
                .assertNext((ExportJobResponseDTO dto) ->
                        assertThat(dto.getErrorMessage()).contains("connection refused"))
                .verifyComplete();
    }
}
