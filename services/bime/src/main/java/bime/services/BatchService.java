package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.BatchLocationBalanceDTO;
import bime.dto.BatchResponseDTO;
import bime.dto.BatchStatus;
import bime.dto.OrgBatchSettingsRequestDTO;
import bime.dto.OrgBatchSettingsResponseDTO;
import bime.dto.RecallReportDTO;
import bime.dto.StockMovementResponseDTO;
import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Production batches (lots) and the recall / near-expiry workflow around them. Batches are created
 * implicitly by INBOUND stock movements (see {@link StockLedgerService}); this service handles
 * reading them back, the quarantine actions, and the per-org expiry-alert window.
 */
@Service
@RequiredArgsConstructor
public class BatchService {

    private final BimeContextService ctx;

    static final String BATCH_COLUMNS = """
            id, variant_id, batch_code, expiry_date, status, recalled_at, recall_note, created_at
            """;

    /**
     * Finds or creates the batch identified by {@code (variantId, batchCode)} and returns its id.
     * A first sighting stores {@code expiryDate}; a later sighting fills the date in if it was
     * unknown, but a conflicting non-null date is rejected rather than silently overwritten. Runs
     * inside the caller's transaction.
     */
    Mono<UUID> upsertBatch(BimeDbHandle handle, UUID orgId, UUID variantId, String batchCode, LocalDate expiryDate) {
        String code = batchCode == null ? "" : batchCode.trim();
        if (code.isEmpty()) {
            return Mono.error(new BadRequestException("a batch code is required"));
        }
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO stock_batches (org_id, variant_id, batch_code, expiry_date)
                VALUES (:orgId, :variantId, :batchCode, :expiryDate)
                ON CONFLICT (org_id, variant_id, batch_code)
                    DO UPDATE SET expiry_date = COALESCE(stock_batches.expiry_date, EXCLUDED.expiry_date)
                RETURNING id, expiry_date
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("batchCode", code);
        spec = expiryDate != null
                ? spec.bind("expiryDate", expiryDate)
                : spec.bindNull("expiryDate", LocalDate.class);
        return spec.fetch().one()
                .flatMap(row -> {
                    LocalDate stored = (LocalDate) row.get("expiry_date");
                    if (expiryDate != null && stored != null && !stored.equals(expiryDate)) {
                        return Mono.error(new BadRequestException(
                                "batch \"" + code + "\" is already on file with expiry " + stored
                                        + ", which does not match the scanned " + expiryDate));
                    }
                    return Mono.just((UUID) row.get("id"));
                });
    }

    /** Loads a batch row for the org, or 404s. Used by the ledger to validate an explicit batchId. */
    Mono<Map<String, Object>> requireBatch(BimeDbHandle handle, UUID orgId, UUID batchId) {
        return handle.client().sql("SELECT %s FROM stock_batches WHERE id = :id AND org_id = :orgId".formatted(BATCH_COLUMNS))
                .bind("id", batchId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Batch not found")));
    }

    public Flux<BatchResponseDTO> listBatches(UUID variantId, UUID locationId, BatchStatus status, Integer expiringWithinDays) {
        return ctx.withHandleMany((caller, handle) -> {
            StringBuilder sql = new StringBuilder("SELECT " + BATCH_COLUMNS + " FROM stock_batches WHERE org_id = :orgId");
            if (variantId != null) sql.append(" AND variant_id = :variantId");
            if (status != null) sql.append(" AND status = :status");
            if (expiringWithinDays != null) sql.append(" AND expiry_date IS NOT NULL AND expiry_date <= current_date + :days");
            sql.append(" ORDER BY expiry_date NULLS LAST, batch_code");

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql(sql.toString()).bind("orgId", caller.getOrgId());
            if (variantId != null) spec = spec.bind("variantId", variantId);
            if (status != null) spec = spec.bind("status", status.name());
            if (expiringWithinDays != null) spec = spec.bind("days", expiringWithinDays);

            return spec.fetch().all().collectList().flatMapMany(rows -> {
                if (rows.isEmpty()) {
                    return Flux.empty();
                }
                List<UUID> ids = rows.stream().map(r -> (UUID) r.get("id")).toList();
                return loadBalances(handle, caller.getOrgId(), ids, locationId)
                        .collectList()
                        .flatMapMany(balances -> Flux.fromIterable(assemble(rows, balances)));
            });
        });
    }

    public Mono<BatchResponseDTO> getBatch(UUID id) {
        return ctx.withHandle((caller, handle) -> requireBatch(handle, caller.getOrgId(), id)
                .flatMap(row -> loadBalances(handle, caller.getOrgId(), List.of(id), null)
                        .collectList()
                        .map(balances -> assemble(List.of(row), balances).get(0))));
    }

    public Mono<BatchResponseDTO> recall(UUID batchId, String note) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE stock_batches
                SET status = 'RECALLED', recalled_at = :now, recalled_by = :uid, recall_note = :note
                WHERE id = :id AND org_id = :orgId AND status = 'ACTIVE'
                RETURNING id
                """)
                .bind("now", LocalDateTime.now())
                .bind("uid", caller.getId())
                .bind("note", note != null ? note : "")
                .bind("id", batchId)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0
                        ? Mono.empty()
                        : requireBatch(handle, caller.getOrgId(), batchId)
                                .flatMap(row -> Mono.error(new ConflictException("Batch is already under recall"))))
                .then(getBatch(batchId)));
    }

    public Mono<BatchResponseDTO> liftRecall(UUID batchId) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE stock_batches
                SET status = 'ACTIVE', recalled_at = NULL, recalled_by = NULL, recall_note = NULL
                WHERE id = :id AND org_id = :orgId AND status = 'RECALLED'
                RETURNING id
                """)
                .bind("id", batchId)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows > 0
                        ? Mono.empty()
                        : requireBatch(handle, caller.getOrgId(), batchId)
                                .flatMap(row -> Mono.error(new ConflictException("Batch is not under recall"))))
                .then(getBatch(batchId)));
    }

    public Mono<RecallReportDTO> recallReport(UUID batchId) {
        return ctx.withHandle((caller, handle) -> getBatch(batchId).flatMap(batch ->
                handle.client().sql("""
                        SELECT %s, batch_id
                        FROM stock_movements
                        WHERE batch_id = :batchId AND org_id = :orgId
                        ORDER BY created_at
                        """.formatted(StockLedgerService.MOVEMENT_COLUMNS))
                        .bind("batchId", batchId)
                        .bind("orgId", caller.getOrgId())
                        .fetch()
                        .all()
                        .map(StockLedgerService::toMovementResponseDTO)
                        .collectList()
                        .map(history -> {
                            List<BatchLocationBalanceDTO> affected = batch.getBalances() == null ? List.of()
                                    : batch.getBalances().stream()
                                            .filter(b -> b.getQuantity() != null && b.getQuantity().signum() > 0)
                                            .toList();
                            return RecallReportDTO.builder()
                                    .batch(batch)
                                    .affectedLocations(affected)
                                    .history(history)
                                    .build();
                        })));
    }

    public Mono<OrgBatchSettingsResponseDTO> getSettings() {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT org_id, near_expiry_days, created_at, modified_at
                FROM org_batch_settings WHERE org_id = :orgId
                """)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(BatchService::toSettingsDTO)
                .switchIfEmpty(Mono.just(OrgBatchSettingsResponseDTO.builder()
                        .orgId(caller.getOrgId())
                        .nearExpiryDays(30)
                        .build())));
    }

    public Mono<OrgBatchSettingsResponseDTO> updateSettings(OrgBatchSettingsRequestDTO dto) {
        if (dto == null || dto.getNearExpiryDays() == null || dto.getNearExpiryDays() < 1) {
            return Mono.error(new BadRequestException("nearExpiryDays must be a positive number"));
        }
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                INSERT INTO org_batch_settings (org_id, near_expiry_days) VALUES (:orgId, :days)
                ON CONFLICT (org_id) DO UPDATE SET near_expiry_days = :days, modified_at = current_timestamp
                RETURNING org_id, near_expiry_days, created_at, modified_at
                """)
                .bind("orgId", caller.getOrgId())
                .bind("days", dto.getNearExpiryDays())
                .fetch()
                .one()
                .map(BatchService::toSettingsDTO));
    }

    private Flux<Map<String, Object>> loadBalances(BimeDbHandle handle, UUID orgId, List<UUID> batchIds, UUID locationId) {
        StringBuilder sql = new StringBuilder("""
                SELECT bb.batch_id, bb.location_id, l.name AS location_name, bb.quantity
                FROM stock_batch_balances bb
                JOIN locations l ON l.id = bb.location_id
                WHERE bb.org_id = :orgId AND bb.batch_id = ANY(:batchIds)
                """);
        if (locationId != null) sql.append(" AND bb.location_id = :locationId");
        sql.append(" ORDER BY l.name");

        DatabaseClient.GenericExecuteSpec spec = handle.client().sql(sql.toString())
                .bind("orgId", orgId)
                .bind("batchIds", batchIds.toArray(new UUID[0]));
        if (locationId != null) spec = spec.bind("locationId", locationId);
        return spec.fetch().all();
    }

    private List<BatchResponseDTO> assemble(List<Map<String, Object>> batchRows, List<Map<String, Object>> balanceRows) {
        Map<UUID, List<BatchLocationBalanceDTO>> byBatch = new LinkedHashMap<>();
        Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> b : balanceRows) {
            UUID batchId = (UUID) b.get("batch_id");
            BigDecimal qty = (BigDecimal) b.get("quantity");
            byBatch.computeIfAbsent(batchId, k -> new ArrayList<>()).add(BatchLocationBalanceDTO.builder()
                    .locationId((UUID) b.get("location_id"))
                    .locationName((String) b.get("location_name"))
                    .quantity(qty)
                    .build());
            totals.merge(batchId, qty, BigDecimal::add);
        }
        List<BatchResponseDTO> out = new ArrayList<>();
        for (Map<String, Object> row : batchRows) {
            UUID id = (UUID) row.get("id");
            out.add(BatchResponseDTO.builder()
                    .id(id)
                    .variantId((UUID) row.get("variant_id"))
                    .batchCode((String) row.get("batch_code"))
                    .expiryDate((LocalDate) row.get("expiry_date"))
                    .status(BatchStatus.valueOf((String) row.get("status")))
                    .recalledAt((LocalDateTime) row.get("recalled_at"))
                    .recallNote((String) row.get("recall_note"))
                    .createdAt((LocalDateTime) row.get("created_at"))
                    .balances(byBatch.getOrDefault(id, List.of()))
                    .totalQuantity(totals.getOrDefault(id, BigDecimal.ZERO))
                    .build());
        }
        return out;
    }

    private static OrgBatchSettingsResponseDTO toSettingsDTO(Map<String, Object> row) {
        return OrgBatchSettingsResponseDTO.builder()
                .orgId((UUID) row.get("org_id"))
                .nearExpiryDays(((Number) row.get("near_expiry_days")).intValue())
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
