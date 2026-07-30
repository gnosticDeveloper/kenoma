package bime.services;

import bime.clients.RaumClient;
import bime.db.BimeDbService;
import bime.openbao.OpenBaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockAlertScheduler {

    private final RaumClient raumClient;
    private final BimeDbService bimeDbService;
    private final OpenBaoService openBaoService;
    private final StockAlertCheckService stockAlertCheckService;

    @Scheduled(cron = "${bime.stock-alerts.check-cron:0 0 4 * * *}")
    public void checkStockLevels() {
        String vaultToken = openBaoService.getToken();
        raumClient.getActiveOrgIds(vaultToken)
                .concatMap(orgId -> processOrg(orgId, vaultToken)
                        .onErrorResume(e -> {
                            log.error("Stock alert check failed for org {}", orgId, e);
                            return Mono.empty();
                        }))
                .then()
                .subscribe(null, e -> log.error("Stock alert check failed", e));
    }

    private Mono<Void> processOrg(UUID orgId, String vaultToken) {
        return bimeDbService.getHandleViaVaultToken(orgId, vaultToken)
                .flatMap(handle -> stockAlertCheckService.checkOrg(orgId, handle));
    }
}
