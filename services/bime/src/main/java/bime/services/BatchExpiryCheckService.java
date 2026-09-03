package bime.services;

import bime.db.BimeDbHandle;
import common.mail.MailgunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Near-expiry alerting for batch-tracked stock, for a single org's database handle. Same
 * "row exists only while breached, email once on first detection" contract as
 * {@link StockAlertCheckService}: an active alert row is raised the first time a batch with stock
 * on hand falls inside the org's near-expiry window, and cleared once it is recalled, consumed to
 * zero, or the window no longer covers it - so a later dip triggers a fresh email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchExpiryCheckService {

    private static final int DEFAULT_NEAR_EXPIRY_DAYS = 30;

    private final MailgunService mailgunService;

    public Mono<Void> checkOrg(UUID orgId, BimeDbHandle handle) {
        return clearRecoveredAlerts(orgId, handle)
                .then(triggerNewAlerts(orgId, handle));
    }

    private Mono<Long> clearRecoveredAlerts(UUID orgId, BimeDbHandle handle) {
        return handle.client().sql("""
                DELETE FROM batch_expiry_alerts a
                WHERE a.org_id = :orgId
                  AND NOT EXISTS (
                    SELECT 1
                    FROM stock_batch_balances b
                    JOIN stock_batches sb ON sb.id = b.batch_id
                    LEFT JOIN org_batch_settings s ON s.org_id = b.org_id
                    WHERE b.org_id = a.org_id AND b.batch_id = a.batch_id AND b.location_id = a.location_id
                      AND b.quantity > 0
                      AND sb.status = 'ACTIVE'
                      AND sb.expiry_date IS NOT NULL
                      AND sb.expiry_date <= current_date + COALESCE(s.near_expiry_days, :defaultDays)
                  )
                """)
                .bind("orgId", orgId)
                .bind("defaultDays", DEFAULT_NEAR_EXPIRY_DAYS)
                .fetch()
                .rowsUpdated();
    }

    private Mono<Void> triggerNewAlerts(UUID orgId, BimeDbHandle handle) {
        return handle.client().sql("""
                WITH triggered AS (
                    INSERT INTO batch_expiry_alerts (org_id, batch_id, location_id, expiry_date, quantity)
                    SELECT b.org_id, b.batch_id, b.location_id, sb.expiry_date, b.quantity
                    FROM stock_batch_balances b
                    JOIN stock_batches sb ON sb.id = b.batch_id
                    LEFT JOIN org_batch_settings s ON s.org_id = b.org_id
                    WHERE b.org_id = :orgId
                      AND b.quantity > 0
                      AND sb.status = 'ACTIVE'
                      AND sb.expiry_date IS NOT NULL
                      AND sb.expiry_date <= current_date + COALESCE(s.near_expiry_days, :defaultDays)
                      AND NOT EXISTS (
                          SELECT 1 FROM batch_expiry_alerts a
                          WHERE a.org_id = b.org_id AND a.batch_id = b.batch_id AND a.location_id = b.location_id
                      )
                    RETURNING org_id, batch_id, location_id, expiry_date, quantity
                )
                SELECT tr.expiry_date, tr.quantity, sb.batch_code,
                       p.name AS product_name, p.sku AS product_sku, pv.sku AS variant_sku,
                       l.name AS location_name, l.notification_email, l.notification_email_verified
                FROM triggered tr
                JOIN stock_batches sb ON sb.id = tr.batch_id
                JOIN product_variants pv ON pv.id = sb.variant_id
                JOIN products p ON p.id = pv.product_id
                JOIN locations l ON l.id = tr.location_id
                """)
                .bind("orgId", orgId)
                .bind("defaultDays", DEFAULT_NEAR_EXPIRY_DAYS)
                .fetch()
                .all()
                .concatMap(row -> sendExpiryEmail(orgId, row)
                        .onErrorResume(e -> {
                            log.error("Failed to send batch expiry email for org {}", orgId, e);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sendExpiryEmail(UUID orgId, Map<String, Object> row) {
        String notificationEmail = (String) row.get("notification_email");
        if (notificationEmail == null) {
            log.warn("Batch expiry alert triggered for org {} but location {} has no notification_email set",
                    orgId, row.get("location_name"));
            return Mono.empty();
        }
        if (!Boolean.TRUE.equals(row.get("notification_email_verified"))) {
            log.warn("Batch expiry alert triggered for org {} but location {}'s notification_email is not verified",
                    orgId, row.get("location_name"));
            return Mono.empty();
        }
        String variantSku = (String) row.get("variant_sku");
        String sku = variantSku != null ? variantSku : (String) row.get("product_sku");
        String productLabel = "%s (%s)".formatted(row.get("product_name"), sku);

        return mailgunService.sendBatchExpiryEmail(
                notificationEmail,
                productLabel,
                (String) row.get("location_name"),
                (String) row.get("batch_code"),
                String.valueOf((LocalDate) row.get("expiry_date")),
                (BigDecimal) row.get("quantity"),
                null
        );
    }
}
