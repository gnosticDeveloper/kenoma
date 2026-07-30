package bime.services;

import bime.db.BimeDbHandle;
import common.mail.MailgunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Runs the low-stock comparison for a single org's database handle. Takes a plain
 * {@link BimeDbHandle} rather than going through {@link bime.db.BimeContextService}, since the
 * scheduler that calls this has no authenticated caller/reactive-security-context to pull one from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlertCheckService {

    private final MailgunService mailgunService;

    public Mono<Void> checkOrg(UUID orgId, BimeDbHandle handle) {
        return clearRecoveredAlerts(orgId, handle)
                .then(triggerNewAlerts(orgId, handle));
    }

    // Clears an active alert once either: (a) stock has recovered above the *current* threshold, or
    // (b) the threshold itself was removed since the alert was raised — no reason to keep flagging it.
    private Mono<Long> clearRecoveredAlerts(UUID orgId, BimeDbHandle handle) {
        return handle.client().sql("""
                DELETE FROM variant_stock_alerts a
                WHERE a.org_id = :orgId
                  AND (
                    NOT EXISTS (
                        SELECT 1 FROM variant_stock_alert_thresholds t
                        WHERE t.org_id = a.org_id AND t.variant_id = a.variant_id AND t.location_id = a.location_id
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM variant_stock_balances b
                        JOIN variant_stock_alert_thresholds t
                            ON t.org_id = b.org_id AND t.variant_id = b.variant_id AND t.location_id = b.location_id
                        WHERE b.org_id = a.org_id AND b.variant_id = a.variant_id AND b.location_id = a.location_id
                          AND b.quantity > t.threshold
                    )
                  )
                """)
                .bind("orgId", orgId)
                .fetch()
                .rowsUpdated();
    }

    // Inserts a new active-alert row for every threshold breach that doesn't already have one, then
    // (in the same round trip) joins in everything needed to compose the email. NOT EXISTS against
    // variant_stock_alerts is what makes this "once per dip" rather than "every scheduled tick".
    private Mono<Void> triggerNewAlerts(UUID orgId, BimeDbHandle handle) {
        return handle.client().sql("""
                WITH triggered AS (
                    INSERT INTO variant_stock_alerts (org_id, variant_id, location_id, threshold, quantity)
                    SELECT t.org_id, t.variant_id, t.location_id, t.threshold, b.quantity
                    FROM variant_stock_alert_thresholds t
                    JOIN variant_stock_balances b
                        ON b.org_id = t.org_id AND b.variant_id = t.variant_id AND b.location_id = t.location_id
                    WHERE t.org_id = :orgId
                      AND b.quantity <= t.threshold
                      AND NOT EXISTS (
                          SELECT 1 FROM variant_stock_alerts a
                          WHERE a.org_id = t.org_id AND a.variant_id = t.variant_id AND a.location_id = t.location_id
                      )
                    RETURNING org_id, variant_id, location_id, threshold, quantity
                )
                SELECT tr.threshold, tr.quantity,
                       p.name AS product_name, p.sku AS product_sku, pv.sku AS variant_sku,
                       l.name AS location_name, l.notification_email
                FROM triggered tr
                JOIN product_variants pv ON pv.id = tr.variant_id
                JOIN products p ON p.id = pv.product_id
                JOIN locations l ON l.id = tr.location_id
                """)
                .bind("orgId", orgId)
                .fetch()
                .all()
                .concatMap(row -> sendAlertEmail(orgId, row)
                        .onErrorResume(e -> {
                            log.error("Failed to send stock alert email for org {}", orgId, e);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sendAlertEmail(UUID orgId, Map<String, Object> row) {
        String notificationEmail = (String) row.get("notification_email");
        if (notificationEmail == null) {
            log.warn("Stock alert triggered for org {} but location {} has no notification_email set",
                    orgId, row.get("location_name"));
            return Mono.empty();
        }
        String variantSku = (String) row.get("variant_sku");
        String sku = variantSku != null ? variantSku : (String) row.get("product_sku");
        String productLabel = "%s (%s)".formatted(row.get("product_name"), sku);

        return mailgunService.sendStockAlertEmail(
                notificationEmail,
                productLabel,
                (String) row.get("location_name"),
                (Integer) row.get("quantity"),
                (Integer) row.get("threshold"),
                null
        );
    }
}
