package bime.services;

import bime.clients.RaumClient;
import bime.db.BimeDbHandle;
import bime.db.BimeDbService;
import bime.openbao.OpenBaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockAlertSchedulerTest {

    @Mock
    private RaumClient raumClient;
    @Mock
    private BimeDbService bimeDbService;
    @Mock
    private OpenBaoService openBaoService;
    @Mock
    private StockAlertCheckService stockAlertCheckService;
    @Mock
    private BatchExpiryCheckService batchExpiryCheckService;
    @Mock
    private BimeDbHandle handle;

    private StockAlertScheduler scheduler;

    private static final String VAULT_TOKEN = "test-vault-token";
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new StockAlertScheduler(raumClient, bimeDbService, openBaoService, stockAlertCheckService, batchExpiryCheckService);
        when(openBaoService.getToken()).thenReturn(VAULT_TOKEN);
    }

    @Test
    void checkStockLevels_usesCurrentVaultTokenToListOrgsAndFetchHandles() {
        when(raumClient.getActiveOrgIds(VAULT_TOKEN)).thenReturn(Flux.just(ORG_A));
        when(bimeDbService.getHandleViaVaultToken(ORG_A, VAULT_TOKEN)).thenReturn(Mono.just(handle));
        when(stockAlertCheckService.checkOrg(ORG_A, handle)).thenReturn(Mono.empty());

        scheduler.checkStockLevels();

        verify(raumClient).getActiveOrgIds(VAULT_TOKEN);
        verify(bimeDbService).getHandleViaVaultToken(ORG_A, VAULT_TOKEN);
        verify(stockAlertCheckService).checkOrg(ORG_A, handle);
    }

    // Adversarial: one org's DB handle / check blowing up must not stop other orgs
    // from being processed in the same tick — mirrors InvoiceDeadlineScheduler's isolation test.
    @Test
    void checkStockLevels_isolatesFailureToOneOrg() {
        BimeDbHandle handleB = mock(BimeDbHandle.class);
        when(raumClient.getActiveOrgIds(VAULT_TOKEN)).thenReturn(Flux.fromIterable(List.of(ORG_A, ORG_B)));
        when(bimeDbService.getHandleViaVaultToken(ORG_A, VAULT_TOKEN))
                .thenReturn(Mono.error(new RuntimeException("credential fetch failed for org A")));
        when(bimeDbService.getHandleViaVaultToken(ORG_B, VAULT_TOKEN)).thenReturn(Mono.just(handleB));
        when(stockAlertCheckService.checkOrg(ORG_B, handleB)).thenReturn(Mono.empty());

        scheduler.checkStockLevels();

        verify(stockAlertCheckService).checkOrg(ORG_B, handleB);
        verify(stockAlertCheckService, never()).checkOrg(eq(ORG_A), any());
    }

    @Test
    void checkStockLevels_noActiveOrgs_neverFetchesAHandle() {
        when(raumClient.getActiveOrgIds(VAULT_TOKEN)).thenReturn(Flux.empty());

        scheduler.checkStockLevels();

        verifyNoInteractions(bimeDbService, stockAlertCheckService);
    }

    // Adversarial: getActiveOrgIds itself failing (e.g. Raum unreachable) must not throw out of
    // the @Scheduled method — a scheduler that throws stops future Spring @Scheduled invocations.
    // Incurs the real ~6s retry delay (3 attempts, 2s apart, see checkStockLevels) since every
    // invocation returns the same error - mirrors OpenBaoTokenRenewalSchedulerTest's style.
    @Test
    void checkStockLevels_orgListingFails_doesNotThrow() {
        when(raumClient.getActiveOrgIds(VAULT_TOKEN)).thenReturn(Flux.error(new RuntimeException("raum unreachable")));

        scheduler.checkStockLevels();

        verifyNoInteractions(bimeDbService, stockAlertCheckService);
    }

    // This only runs once a day - previously a single transient failure fetching the org list (no
    // retry at all) meant every org's stock alert check silently didn't run for a full 24h. Confirms
    // the retry actually recovers rather than just not throwing. retryWhen resubscribes to the same
    // publisher rather than re-invoking raumClient.getActiveOrgIds, so the per-attempt behavior has
    // to live inside a Flux.defer, not in separate Mockito thenReturn(...) stubs. checkStockLevels
    // subscribes fire-and-forget (doesn't block), so the 2s-per-attempt retry delay runs on a
    // background Reactor scheduler after the method call returns - a latch, not a bare assertion
    // right after checkStockLevels(), is needed to wait for that background work to finish.
    @Test
    void checkStockLevels_orgListingTransientlyFails_retriesAndRecovers() throws InterruptedException {
        AtomicInteger attempt = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        when(raumClient.getActiveOrgIds(VAULT_TOKEN)).thenReturn(Flux.defer(() ->
                attempt.incrementAndGet() < 3
                        ? Flux.error(new RuntimeException("raum unreachable"))
                        : Flux.just(ORG_A)));
        when(bimeDbService.getHandleViaVaultToken(ORG_A, VAULT_TOKEN)).thenReturn(Mono.just(handle));
        when(stockAlertCheckService.checkOrg(ORG_A, handle)).thenAnswer(inv -> {
            done.countDown();
            return Mono.empty();
        });

        scheduler.checkStockLevels();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(attempt.get()).isEqualTo(3);
        verify(stockAlertCheckService).checkOrg(ORG_A, handle);
    }
}
