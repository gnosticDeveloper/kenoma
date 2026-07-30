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
    private BimeDbHandle handle;

    private StockAlertScheduler scheduler;

    private static final String VAULT_TOKEN = "test-vault-token";
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new StockAlertScheduler(raumClient, bimeDbService, openBaoService, stockAlertCheckService);
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
    @Test
    void checkStockLevels_orgListingFails_doesNotThrow() {
        when(raumClient.getActiveOrgIds(VAULT_TOKEN)).thenReturn(Flux.error(new RuntimeException("raum unreachable")));

        scheduler.checkStockLevels();

        verifyNoInteractions(bimeDbService, stockAlertCheckService);
    }
}
