package bime.services;

import bime.clients.RaumClient;
import bime.db.BimeDbService;
import bime.openbao.OpenBaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockAlertScheduler {

    private final RaumClient raumClient;
    private final BimeDbService bimeDbService;
    private final OpenBaoService openBaoService;
    private final StockAlertCheckService stockAlertCheckService;
    private final BatchExpiryCheckService batchExpiryCheckService;

    @Scheduled(cron = "${bime.stock-alerts.check-cron:0 0 4 * * *}")
    public void checkStockLevels() {
        String vaultToken = openBaoService.getToken();
        // This only runs once a day - a transient failure fetching the org list (e.g. raum momentarily
        // unreachable) with no retry would silently skip every org's stock alert check for a full 24h,
        // not just this one call. The per-org onErrorResume below already isolates one org's failure
        // from the rest; this retry does the same for the org-list fetch itself.
        raumClient.getActiveOrgIds(vaultToken)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))
                .concatMap(orgId -> processOrg(orgId, vaultToken)
                        .onErrorResume(e -> {
                            log.error("Stock alert check failed for org {}", orgId, e);
                            return Mono.empty();
                        }))
                .then()
                .subscribe(null, e -> log.error("Stock alert check failed", e));
    }

    @Scheduled(cron = "${bime.batch-expiry.check-cron:0 30 4 * * *}")
    public void checkBatchExpiry() {
        String vaultToken = openBaoService.getToken();
        raumClient.getActiveOrgIds(vaultToken)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))
                .concatMap(orgId -> bimeDbService.getHandleViaVaultToken(orgId, vaultToken)
                        .flatMap(handle -> batchExpiryCheckService.checkOrg(orgId, handle))
                        .onErrorResume(e -> {
                            log.error("Batch expiry check failed for org {}", orgId, e);
                            return Mono.empty();
                        }))
                .then()
                .subscribe(null, e -> log.error("Batch expiry check failed", e));
    }

    private Mono<Void> processOrg(UUID orgId, String vaultToken) {
        return bimeDbService.getHandleViaVaultToken(orgId, vaultToken)
                .flatMap(handle -> stockAlertCheckService.checkOrg(orgId, handle));
    }
}
