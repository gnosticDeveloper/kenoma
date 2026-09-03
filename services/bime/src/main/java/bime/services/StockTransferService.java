package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.InTransitStockDTO;
import bime.dto.MovementStatus;
import bime.dto.MovementType;
import bime.dto.StockTransferLineBatchDTO;
import bime.dto.StockTransferLineRequestDTO;
import bime.dto.StockTransferLineResponseDTO;
import bime.dto.StockTransferReceiveLineDTO;
import bime.dto.StockTransferReceiveRequestDTO;
import bime.dto.StockTransferRequestDTO;
import bime.dto.StockTransferResponseDTO;
import bime.dto.TransferStatus;
import bime.security.BimeAuthentication;
import bime.security.BimePermission;
import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockTransferService {

    private final BimeContextService ctx;
    private final StockLedgerService stockLedgerService;

    private static final String TRANSFER_COLUMNS = """
            id, org_id, reference, status, note,
            created_at, created_by, submitted_at, submitted_by, approved_at, approved_by,
            dispatched_at, dispatched_by, completed_at, completed_by, cancelled_at, cancelled_by
            """;
    private static final String LINE_COLUMNS = """
            id, transfer_id, variant_id, source_location_id, dest_location_id,
            qty_requested, qty_dispatched, qty_received, uom, uom_quantity
            """;

    public Mono<StockTransferResponseDTO> create(StockTransferRequestDTO dto) {
        String error = validate(dto);
        if (error != null) {
            return Mono.error(new BadRequestException(error));
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                insertTransfer(handle, caller.getOrgId(), caller.getId(), dto)
                        .flatMap(transferId -> insertLines(handle, caller.getOrgId(), transferId, dto)
                                .then(loadTransfer(handle, caller.getOrgId(), transferId)))
        )));
    }

    public Mono<StockTransferResponseDTO> update(UUID id, StockTransferRequestDTO dto) {
        String error = validate(dto);
        if (error != null) {
            return Mono.error(new BadRequestException(error));
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                currentStatus(handle, caller.getOrgId(), id).flatMap(status -> {
                    if (!"DRAFT".equals(status)) {
                        return Mono.error(new ConflictException("Only a draft transfer can be edited"));
                    }
                    DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                            UPDATE stock_transfers SET reference = :reference, note = :note
                            WHERE id = :id AND org_id = :orgId AND status = 'DRAFT'
                            """)
                            .bind("id", id)
                            .bind("orgId", caller.getOrgId());
                    spec = bindNullableString(spec, "reference", nullable(dto.getReference()));
                    spec = bindNullableString(spec, "note", nullable(dto.getNote()));
                    return spec.fetch().rowsUpdated()
                            .then(handle.client().sql("DELETE FROM stock_transfer_lines WHERE transfer_id = :id AND org_id = :orgId")
                                    .bind("id", id).bind("orgId", caller.getOrgId())
                                    .fetch().rowsUpdated())
                            .then(insertLines(handle, caller.getOrgId(), id, dto))
                            .then(loadTransfer(handle, caller.getOrgId(), id));
                })
        )));
    }

    public Mono<Void> delete(UUID id) {
        return ctx.withHandle((caller, handle) ->
                currentStatus(handle, caller.getOrgId(), id).flatMap(status -> {
                    if (!"DRAFT".equals(status)) {
                        return Mono.error(new ConflictException("Only a draft transfer can be deleted; cancel it instead"));
                    }
                    return handle.client().sql("DELETE FROM stock_transfers WHERE id = :id AND org_id = :orgId AND status = 'DRAFT'")
                            .bind("id", id).bind("orgId", caller.getOrgId())
                            .fetch().rowsUpdated().then();
                })
        );
    }

    public Mono<StockTransferResponseDTO> getById(UUID id) {
        return ctx.withHandle((caller, handle) -> loadTransfer(handle, caller.getOrgId(), id));
    }

    public Flux<StockTransferResponseDTO> list(TransferStatus status, UUID sourceLocationId,
                                               UUID destLocationId, UUID variantId) {
        return ctx.withHandleMany((caller, handle) -> {
            List<String> conditions = new ArrayList<>();
            conditions.add("t.org_id = :orgId");
            if (status != null) {
                conditions.add("t.status = :status");
            }
            if (sourceLocationId != null || destLocationId != null || variantId != null) {
                List<String> lineConds = new ArrayList<>();
                lineConds.add("l.transfer_id = t.id");
                if (sourceLocationId != null) lineConds.add("l.source_location_id = :sourceLocationId");
                if (destLocationId != null) lineConds.add("l.dest_location_id = :destLocationId");
                if (variantId != null) lineConds.add("l.variant_id = :variantId");
                conditions.add("EXISTS (SELECT 1 FROM stock_transfer_lines l WHERE " + String.join(" AND ", lineConds) + ")");
            }
            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT %s FROM stock_transfers t
                    WHERE %s
                    ORDER BY t.created_at DESC
                    """.formatted(TRANSFER_COLUMNS.strip(), String.join(" AND ", conditions)))
                    .bind("orgId", caller.getOrgId());
            if (status != null) spec = spec.bind("status", status.name());
            if (sourceLocationId != null) spec = spec.bind("sourceLocationId", sourceLocationId);
            if (destLocationId != null) spec = spec.bind("destLocationId", destLocationId);
            if (variantId != null) spec = spec.bind("variantId", variantId);

            return spec.fetch().all()
                    .concatMap(row -> loadLines(handle, caller.getOrgId(), (UUID) row.get("id"))
                            .map(lines -> toTransferDTO(row, lines)));
        });
    }

    public Flux<InTransitStockDTO> inTransit() {
        return ctx.withHandleMany((caller, handle) -> handle.client().sql("""
                SELECT variant_id, location_id AS dest_location_id, SUM(delta) AS quantity
                FROM stock_movements
                WHERE org_id = :orgId AND movement_type = 'TRANSFER_IN' AND status = 'PENDING'
                GROUP BY variant_id, location_id
                HAVING SUM(delta) <> 0
                ORDER BY variant_id
                """)
                .bind("orgId", caller.getOrgId())
                .fetch().all()
                .map(row -> InTransitStockDTO.builder()
                        .variantId((UUID) row.get("variant_id"))
                        .destLocationId((UUID) row.get("dest_location_id"))
                        .quantity((BigDecimal) row.get("quantity"))
                        .build()));
    }


    public Mono<StockTransferResponseDTO> submit(UUID id) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                currentStatus(handle, caller.getOrgId(), id).flatMap(status -> {
                    if (!"DRAFT".equals(status)) {
                        return Mono.error(new ConflictException("Only a draft transfer can be submitted"));
                    }
                    boolean autoApprove = hasAuthority(caller, BimePermission.BIME_TRANSFER_APPROVE);
                    String target = autoApprove ? "APPROVED" : "PENDING_APPROVAL";
                    LocalDateTime now = LocalDateTime.now();
                    return countLines(handle, caller.getOrgId(), id).flatMap(lineCount -> {
                        if (lineCount == 0) {
                            return Mono.error(new BadRequestException("A transfer with no lines cannot be submitted"));
                        }
                        return handle.client().sql("""
                                UPDATE stock_transfers
                                SET status = :target,
                                    submitted_at = :now, submitted_by = :uid,
                                    approved_at = CASE WHEN :autoApprove THEN :now ELSE approved_at END,
                                    approved_by = CASE WHEN :autoApprove THEN :uid ELSE approved_by END
                                WHERE id = :id AND org_id = :orgId AND status = 'DRAFT'
                                """)
                                .bind("target", target)
                                .bind("now", now)
                                .bind("uid", caller.getId())
                                .bind("autoApprove", autoApprove)
                                .bind("id", id)
                                .bind("orgId", caller.getOrgId())
                                .fetch().rowsUpdated()
                                .then(loadTransfer(handle, caller.getOrgId(), id));
                    });
                })
        )));
    }

    public Mono<StockTransferResponseDTO> approve(UUID id) {
        return simpleTransition(id, Set.of("PENDING_APPROVAL"), "APPROVED",
                "Only a transfer awaiting approval can be approved", "approved_at", "approved_by");
    }

    public Mono<StockTransferResponseDTO> reject(UUID id) {
        return simpleTransition(id, Set.of("PENDING_APPROVAL"), "CANCELLED",
                "Only a transfer awaiting approval can be rejected", "cancelled_at", "cancelled_by");
    }

    public Mono<StockTransferResponseDTO> cancel(UUID id) {
        return simpleTransition(id, Set.of("DRAFT", "PENDING_APPROVAL", "APPROVED"), "CANCELLED",
                "A transfer can only be cancelled before it is dispatched", "cancelled_at", "cancelled_by");
    }

    private Mono<StockTransferResponseDTO> simpleTransition(UUID id, Set<String> allowed, String target,
                                                           String conflictMessage, String tsColumn, String byColumn) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                currentStatus(handle, caller.getOrgId(), id).flatMap(status -> {
                    if (!allowed.contains(status)) {
                        return Mono.error(new ConflictException(conflictMessage));
                    }
                    return handle.client().sql("""
                            UPDATE stock_transfers
                            SET status = :target, %s = :now, %s = :uid
                            WHERE id = :id AND org_id = :orgId
                            """.formatted(tsColumn, byColumn))
                            .bind("target", target)
                            .bind("now", LocalDateTime.now())
                            .bind("uid", caller.getId())
                            .bind("id", id)
                            .bind("orgId", caller.getOrgId())
                            .fetch().rowsUpdated()
                            .then(loadTransfer(handle, caller.getOrgId(), id));
                })
        )));
    }

    public Mono<StockTransferResponseDTO> dispatch(UUID id) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                currentStatus(handle, caller.getOrgId(), id).flatMap(status -> {
                    if (!"APPROVED".equals(status)) {
                        return Mono.error(new ConflictException("Only an approved transfer can be dispatched"));
                    }
                    UUID orgId = caller.getOrgId();
                    return handle.client().sql("""
                            UPDATE stock_transfers
                            SET status = 'IN_TRANSIT', dispatched_at = :now, dispatched_by = :uid
                            WHERE id = :id AND org_id = :orgId AND status = 'APPROVED'
                            """)
                            .bind("now", LocalDateTime.now())
                            .bind("uid", caller.getId())
                            .bind("id", id)
                            .bind("orgId", orgId)
                            .fetch().rowsUpdated()
                            .flatMap(updated -> updated == 0
                                    ? Mono.error(new ConflictException("Only an approved transfer can be dispatched"))
                                    : Mono.just(updated))
                            .then(handle.client().sql("UPDATE stock_transfer_lines SET qty_dispatched = qty_requested WHERE transfer_id = :id AND org_id = :orgId")
                                    .bind("id", id).bind("orgId", orgId)
                                    .fetch().rowsUpdated())
                            .then(loadLineRows(handle, orgId, id).collectList())
                            .flatMapMany(Flux::fromIterable)
                            .concatMap(line -> dispatchLine(handle, orgId, caller.getId(), id, line))
                            .then(loadTransfer(handle, orgId, id));
                })
        )));
    }

    private Mono<Void> dispatchLine(BimeDbHandle handle, UUID orgId, UUID userId, UUID transferId, Map<String, Object> line) {
        UUID variantId = (UUID) line.get("variant_id");
        UUID sourceLocationId = (UUID) line.get("source_location_id");
        UUID destLocationId = (UUID) line.get("dest_location_id");
        BigDecimal qty = (BigDecimal) line.get("qty_requested");
        return isBatchTracked(handle, orgId, variantId).flatMap(tracked -> {
            if (!tracked) {
                return stockLedgerService.appendMovement(handle, orgId, userId, variantId, sourceLocationId,
                                MovementType.TRANSFER_OUT, MovementStatus.POSTED, qty.negate(), null, null, transferId, "", null)
                        .then(stockLedgerService.appendMovement(handle, orgId, userId, variantId, destLocationId,
                                MovementType.TRANSFER_IN, MovementStatus.PENDING, qty, null, null, transferId, "", null))
                        .then();
            }
            return stockLedgerService.allocateFefo(handle, orgId, variantId, sourceLocationId, qty)
                    .flatMapMany(Flux::fromIterable)
                    .concatMap(entry -> {
                        UUID batchId = (UUID) entry[0];
                        BigDecimal take = (BigDecimal) entry[1];
                        return stockLedgerService.appendMovement(handle, orgId, userId, variantId, sourceLocationId,
                                        MovementType.TRANSFER_OUT, MovementStatus.POSTED, take.negate(), null, null, transferId, "", batchId)
                                .then(stockLedgerService.upsertBatchBalance(handle, orgId, batchId, variantId, sourceLocationId, take.negate()))
                                .then(stockLedgerService.appendMovement(handle, orgId, userId, variantId, destLocationId,
                                        MovementType.TRANSFER_IN, MovementStatus.PENDING, take, null, null, transferId, "", batchId));
                    })
                    .then();
        });
    }

    public Mono<StockTransferResponseDTO> receive(UUID id, StockTransferReceiveRequestDTO dto) {
        List<StockTransferReceiveLineDTO> receiveLines = dto.getLines() != null ? dto.getLines() : List.of();
        for (StockTransferReceiveLineDTO rl : receiveLines) {
            if (rl.getLineId() == null) {
                return Mono.error(new BadRequestException("Each receive line needs a lineId"));
            }
            if (rl.getQtyReceived() == null || rl.getQtyReceived().compareTo(BigDecimal.ZERO) < 0) {
                return Mono.error(new BadRequestException("qtyReceived must be zero or a positive number"));
            }
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                currentStatus(handle, caller.getOrgId(), id).flatMap(status -> {
                    if (!"IN_TRANSIT".equals(status) && !"PARTIALLY_RECEIVED".equals(status)) {
                        return Mono.error(new ConflictException("Only a transfer that is in transit can be received"));
                    }
                    UUID orgId = caller.getOrgId();
                    return Flux.fromIterable(receiveLines)
                            .concatMap(rl -> receiveLine(handle, orgId, caller.getId(), id, rl))
                            .then(Mono.defer(() -> dto.isCloseShort()
                                    ? handle.client().sql("""
                                        UPDATE stock_movements SET status = 'CANCELLED'
                                        WHERE reference_id = :id AND org_id = :orgId
                                          AND movement_type = 'TRANSFER_IN' AND status = 'PENDING'
                                        """)
                                        .bind("id", id).bind("orgId", orgId)
                                        .fetch().rowsUpdated().then()
                                    : Mono.<Void>empty()))
                            .then(finalizeReceiveStatus(handle, orgId, caller.getId(), id))
                            .then(loadTransfer(handle, orgId, id));
                })
        )));
    }

    private Mono<Void> receiveLine(BimeDbHandle handle, UUID orgId, UUID userId, UUID transferId, StockTransferReceiveLineDTO rl) {
        return handle.client().sql("""
                SELECT variant_id, dest_location_id FROM stock_transfer_lines
                WHERE id = :lineId AND transfer_id = :transferId AND org_id = :orgId
                """)
                .bind("lineId", rl.getLineId())
                .bind("transferId", transferId)
                .bind("orgId", orgId)
                .fetch().one()
                .switchIfEmpty(Mono.error(new NotFoundException("Transfer line not found")))
                .flatMap(lineRow -> {
                    UUID variantId = (UUID) lineRow.get("variant_id");
                    UUID destLocationId = (UUID) lineRow.get("dest_location_id");
                    return stockLedgerService.resolveBaseDelta(handle, orgId, variantId, rl.getUom(), rl.getQtyReceived())
                            .flatMap(baseQty -> {
                                if (baseQty.compareTo(BigDecimal.ZERO) == 0) {
                                    return Mono.empty();
                                }
                                return handle.client().sql("""
                                        SELECT m.id, m.delta, m.batch_id
                                        FROM stock_movements m
                                        LEFT JOIN stock_batches sb ON sb.id = m.batch_id
                                        WHERE m.reference_id = :transferId AND m.org_id = :orgId AND m.variant_id = :variantId
                                          AND m.location_id = :destLocationId AND m.movement_type = 'TRANSFER_IN' AND m.status = 'PENDING'
                                        ORDER BY sb.expiry_date ASC NULLS LAST, m.created_at ASC
                                        FOR UPDATE OF m
                                        """)
                                        .bind("transferId", transferId)
                                        .bind("orgId", orgId)
                                        .bind("variantId", variantId)
                                        .bind("destLocationId", destLocationId)
                                        .fetch().all().collectList()
                                        .flatMap(pendingRows -> {
                                            if (pendingRows.isEmpty()) {
                                                return Mono.error(new BadRequestException("This line has already been fully received"));
                                            }
                                            BigDecimal outstanding = pendingRows.stream()
                                                    .map(r -> (BigDecimal) r.get("delta"))
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                                            if (baseQty.compareTo(outstanding) > 0) {
                                                return Mono.error(new BadRequestException(
                                                        "Received quantity " + baseQty.stripTrailingZeros().toPlainString()
                                                                + " exceeds the " + outstanding.stripTrailingZeros().toPlainString()
                                                                + " still in transit for this line"));
                                            }
                                            List<Object[]> plan = new ArrayList<>();
                                            BigDecimal remaining = baseQty;
                                            for (Map<String, Object> r : pendingRows) {
                                                if (remaining.signum() <= 0) break;
                                                BigDecimal rowDelta = (BigDecimal) r.get("delta");
                                                BigDecimal take = rowDelta.min(remaining);
                                                plan.add(new Object[]{(UUID) r.get("id"), rowDelta, take, (UUID) r.get("batch_id")});
                                                remaining = remaining.subtract(take);
                                            }
                                            return Flux.fromIterable(plan)
                                                    .concatMap(p -> settlePendingLeg(handle, orgId, userId, transferId, variantId, destLocationId, p))
                                                    .then(handle.client().sql(
                                                                    "UPDATE stock_transfer_lines SET qty_received = qty_received + :recv WHERE id = :lineId")
                                                            .bind("recv", baseQty).bind("lineId", rl.getLineId())
                                                            .fetch().rowsUpdated());
                                        });
                            });
                })
                .then();
    }

    /** Settle one PENDING TRANSFER_IN leg by {@code take}: post it whole, or split off a POSTED
      * portion and shrink the leg. Applies the running balance and, when the leg carries a lot,
      * the per-batch balance at the destination. */
    private Mono<Void> settlePendingLeg(BimeDbHandle handle, UUID orgId, UUID userId, UUID transferId,
                                        UUID variantId, UUID destLocationId, Object[] p) {
        UUID legId = (UUID) p[0];
        BigDecimal legDelta = (BigDecimal) p[1];
        BigDecimal take = (BigDecimal) p[2];
        UUID batchId = (UUID) p[3];
        Mono<Void> batchBalance = batchId != null
                ? stockLedgerService.upsertBatchBalance(handle, orgId, batchId, variantId, destLocationId, take).then()
                : Mono.empty();
        if (take.compareTo(legDelta) == 0) {
            return handle.client().sql("UPDATE stock_movements SET status = 'POSTED' WHERE id = :mid")
                    .bind("mid", legId).fetch().rowsUpdated()
                    .then(stockLedgerService.upsertBalance(handle, orgId, variantId, destLocationId, take))
                    .then(batchBalance);
        }
        return handle.client().sql("UPDATE stock_movements SET delta = delta - :recv WHERE id = :mid")
                .bind("recv", take).bind("mid", legId).fetch().rowsUpdated()
                .then(stockLedgerService.appendMovement(handle, orgId, userId, variantId, destLocationId,
                        MovementType.TRANSFER_IN, MovementStatus.POSTED, take, null, null, transferId, "", batchId))
                .then(batchBalance);
    }

    private Mono<Void> finalizeReceiveStatus(BimeDbHandle handle, UUID orgId, UUID userId, UUID transferId) {
        return handle.client().sql("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'POSTED')  AS posted
                FROM stock_movements
                WHERE reference_id = :id AND org_id = :orgId AND movement_type = 'TRANSFER_IN'
                """)
                .bind("id", transferId)
                .bind("orgId", orgId)
                .fetch().one()
                .flatMap(row -> {
                    long pending = ((Number) row.get("pending")).longValue();
                    long posted = ((Number) row.get("posted")).longValue();
                    String target;
                    if (pending == 0) {
                        target = "COMPLETED";
                    } else if (posted > 0) {
                        target = "PARTIALLY_RECEIVED";
                    } else {
                        target = "IN_TRANSIT";
                    }
                    return handle.client().sql("""
                            UPDATE stock_transfers
                            SET status = :target,
                                completed_at = CASE WHEN :target = 'COMPLETED' THEN :now ELSE completed_at END,
                                completed_by = CASE WHEN :target = 'COMPLETED' THEN :uid ELSE completed_by END
                            WHERE id = :id AND org_id = :orgId AND status IN ('IN_TRANSIT', 'PARTIALLY_RECEIVED')
                            """)
                            .bind("target", target)
                            .bind("now", LocalDateTime.now())
                            .bind("uid", userId)
                            .bind("id", transferId)
                            .bind("orgId", orgId)
                            .fetch().rowsUpdated();
                })
                .then();
    }


    private String validate(StockTransferRequestDTO dto) {
        if (dto.getSourceLocationId() == null || dto.getDestLocationId() == null) {
            return "sourceLocationId and destLocationId are required";
        }
        if (dto.getSourceLocationId().equals(dto.getDestLocationId())) {
            return "source and destination locations must be different";
        }
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            return "at least one line is required";
        }
        Set<UUID> seen = new HashSet<>();
        for (StockTransferLineRequestDTO line : dto.getLines()) {
            if (line.getVariantId() == null) {
                return "each line needs a variantId";
            }
            if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                return "each line quantity must be a positive number";
            }
            if (!seen.add(line.getVariantId())) {
                return "each variant may appear at most once in a transfer";
            }
        }
        return null;
    }

    /** Whether the variant's product opts into batch/expiry tracking. Batch-tracked lines
      * FEFO-allocate across source batches on dispatch and carry each lot through to receipt. */
    private Mono<Boolean> isBatchTracked(BimeDbHandle handle, UUID orgId, UUID variantId) {
        return handle.client().sql("""
                SELECT p.tracks_batches
                FROM product_variants pv
                JOIN products p ON p.id = pv.product_id
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                """)
                .bind("variantId", variantId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")))
                .map(row -> Boolean.TRUE.equals(row.get("tracks_batches")));
    }

    private Mono<UUID> insertTransfer(BimeDbHandle handle, UUID orgId, UUID userId, StockTransferRequestDTO dto) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO stock_transfers (org_id, reference, note, status, created_by)
                VALUES (:orgId, :reference, :note, 'DRAFT', :createdBy)
                RETURNING id
                """)
                .bind("orgId", orgId)
                .bind("createdBy", userId);
        spec = bindNullableString(spec, "reference", nullable(dto.getReference()));
        spec = bindNullableString(spec, "note", nullable(dto.getNote()));
        return spec.fetch().one()
                .map(row -> (UUID) row.get("id"));
    }

    private Mono<Void> insertLines(BimeDbHandle handle, UUID orgId, UUID transferId, StockTransferRequestDTO dto) {
        return Flux.fromIterable(dto.getLines())
                .concatMap(line -> stockLedgerService.resolveBaseDelta(handle, orgId, line.getVariantId(), line.getUom(), line.getQuantity())
                        .flatMap(baseQty -> {
                            if (baseQty.setScale(3, RoundingMode.HALF_UP).signum() <= 0) {
                                return Mono.error(new BadRequestException(
                                        "a line quantity is too small to record - it rounds to zero in the variant's base unit"));
                            }
                            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                                    INSERT INTO stock_transfer_lines
                                        (transfer_id, org_id, source_location_id, dest_location_id, variant_id, qty_requested, uom, uom_quantity)
                                    SELECT :transferId, :orgId, src.id, dst.id, pv.id, :qtyRequested, :uom, :uomQuantity
                                    FROM product_variants pv
                                    JOIN locations src ON src.id = :sourceLocationId AND src.org_id = :orgId
                                    JOIN locations dst ON dst.id = :destLocationId AND dst.org_id = :orgId
                                    WHERE pv.id = :variantId AND pv.org_id = :orgId
                                    RETURNING id
                                    """)
                                    .bind("transferId", transferId)
                                    .bind("orgId", orgId)
                                    .bind("sourceLocationId", dto.getSourceLocationId())
                                    .bind("destLocationId", dto.getDestLocationId())
                                    .bind("variantId", line.getVariantId())
                                    .bind("qtyRequested", baseQty);
                            if (line.getUom() != null) {
                                spec = spec.bind("uom", line.getUom()).bind("uomQuantity", line.getQuantity());
                            } else {
                                spec = spec.bindNull("uom", String.class).bindNull("uomQuantity", BigDecimal.class);
                            }
                            return spec.fetch().one()
                                    .switchIfEmpty(Mono.error(new NotFoundException("Variant or location not found")))
                                    .onErrorMap(DataIntegrityViolationException.class,
                                            e -> new BadRequestException("each variant may appear at most once in a transfer"));
                        }))
                .then();
    }

    private Mono<String> currentStatus(BimeDbHandle handle, UUID orgId, UUID id) {
        return handle.client().sql("SELECT status FROM stock_transfers WHERE id = :id AND org_id = :orgId")
                .bind("id", id).bind("orgId", orgId)
                .fetch().one()
                .map(row -> (String) row.get("status"))
                .switchIfEmpty(Mono.error(new NotFoundException("Transfer order not found")));
    }

    private Mono<Long> countLines(BimeDbHandle handle, UUID orgId, UUID id) {
        return handle.client().sql("SELECT count(*) AS c FROM stock_transfer_lines WHERE transfer_id = :id AND org_id = :orgId")
                .bind("id", id).bind("orgId", orgId)
                .fetch().one()
                .map(row -> ((Number) row.get("c")).longValue());
    }

    private Flux<Map<String, Object>> loadLineRows(BimeDbHandle handle, UUID orgId, UUID transferId) {
        return handle.client().sql("""
                SELECT %s FROM stock_transfer_lines
                WHERE transfer_id = :transferId AND org_id = :orgId
                ORDER BY variant_id
                """.formatted(LINE_COLUMNS))
                .bind("transferId", transferId)
                .bind("orgId", orgId)
                .fetch().all();
    }

    private Mono<List<StockTransferLineResponseDTO>> loadLines(BimeDbHandle handle, UUID orgId, UUID transferId) {
        return loadLineRows(handle, orgId, transferId).map(StockTransferService::toLineDTO).collectList()
                .flatMap(lines -> {
                    if (lines.isEmpty()) {
                        return Mono.just(lines);
                    }
                    return loadLineBatches(handle, orgId, transferId).map(byVariant -> {
                        for (StockTransferLineResponseDTO line : lines) {
                            List<StockTransferLineBatchDTO> b = byVariant.get(line.getVariantId());
                            line.setBatches(b != null ? b : List.of());
                        }
                        return lines;
                    });
                });
    }

    /** Per-lot dispatch/receipt progress for every batch-tracked line of a transfer, keyed by variant.
      * Derived from the TRANSFER_OUT / TRANSFER_IN movement rows that carry a batch_id. */
    private Mono<Map<UUID, List<StockTransferLineBatchDTO>>> loadLineBatches(BimeDbHandle handle, UUID orgId, UUID transferId) {
        return handle.client().sql("""
                SELECT m.variant_id, m.batch_id, sb.batch_code, sb.expiry_date, sb.status,
                       SUM(CASE WHEN m.movement_type = 'TRANSFER_OUT' THEN -m.delta ELSE 0::numeric END) AS dispatched,
                       SUM(CASE WHEN m.movement_type = 'TRANSFER_IN' AND m.status = 'POSTED'  THEN m.delta ELSE 0::numeric END) AS received,
                       SUM(CASE WHEN m.movement_type = 'TRANSFER_IN' AND m.status = 'PENDING' THEN m.delta ELSE 0::numeric END) AS in_transit
                FROM stock_movements m
                JOIN stock_batches sb ON sb.id = m.batch_id
                WHERE m.reference_id = :transferId AND m.org_id = :orgId AND m.batch_id IS NOT NULL
                GROUP BY m.variant_id, m.batch_id, sb.batch_code, sb.expiry_date, sb.status
                ORDER BY sb.expiry_date ASC NULLS LAST, sb.batch_code ASC
                """)
                .bind("transferId", transferId)
                .bind("orgId", orgId)
                .fetch().all()
                .collectList()
                .map(rows -> {
                    Map<UUID, List<StockTransferLineBatchDTO>> byVariant = new LinkedHashMap<>();
                    for (Map<String, Object> r : rows) {
                        byVariant.computeIfAbsent((UUID) r.get("variant_id"), k -> new ArrayList<>())
                                .add(StockTransferLineBatchDTO.builder()
                                        .batchId((UUID) r.get("batch_id"))
                                        .batchCode((String) r.get("batch_code"))
                                        .expiryDate((LocalDate) r.get("expiry_date"))
                                        .status((String) r.get("status"))
                                        .qtyDispatched((BigDecimal) r.get("dispatched"))
                                        .qtyReceived((BigDecimal) r.get("received"))
                                        .qtyInTransit((BigDecimal) r.get("in_transit"))
                                        .build());
                    }
                    return byVariant;
                });
    }

    private Mono<StockTransferResponseDTO> loadTransfer(BimeDbHandle handle, UUID orgId, UUID id) {
        return handle.client().sql("SELECT %s FROM stock_transfers WHERE id = :id AND org_id = :orgId".formatted(TRANSFER_COLUMNS))
                .bind("id", id).bind("orgId", orgId)
                .fetch().one()
                .switchIfEmpty(Mono.error(new NotFoundException("Transfer order not found")))
                .flatMap(row -> loadLines(handle, orgId, id).map(lines -> toTransferDTO(row, lines)));
    }

    private static StockTransferResponseDTO toTransferDTO(Map<String, Object> row, List<StockTransferLineResponseDTO> lines) {
        TransferStatus status = TransferStatus.valueOf((String) row.get("status"));
        boolean inTransit = status == TransferStatus.IN_TRANSIT || status == TransferStatus.PARTIALLY_RECEIVED;
        for (StockTransferLineResponseDTO line : lines) {
            BigDecimal outstanding = line.getQtyDispatched().subtract(line.getQtyReceived());
            line.setQtyInTransit(inTransit && outstanding.compareTo(BigDecimal.ZERO) > 0 ? outstanding : BigDecimal.ZERO);
        }
        return StockTransferResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .reference((String) row.get("reference"))
                .status(status)
                .note((String) row.get("note"))
                .sourceLocationId(lines.isEmpty() ? null : lines.get(0).getSourceLocationId())
                .destLocationId(lines.isEmpty() ? null : lines.get(0).getDestLocationId())
                .lines(lines)
                .createdAt((LocalDateTime) row.get("created_at"))
                .createdBy((UUID) row.get("created_by"))
                .submittedAt((LocalDateTime) row.get("submitted_at"))
                .submittedBy((UUID) row.get("submitted_by"))
                .approvedAt((LocalDateTime) row.get("approved_at"))
                .approvedBy((UUID) row.get("approved_by"))
                .dispatchedAt((LocalDateTime) row.get("dispatched_at"))
                .dispatchedBy((UUID) row.get("dispatched_by"))
                .completedAt((LocalDateTime) row.get("completed_at"))
                .completedBy((UUID) row.get("completed_by"))
                .cancelledAt((LocalDateTime) row.get("cancelled_at"))
                .cancelledBy((UUID) row.get("cancelled_by"))
                .build();
    }

    private static StockTransferLineResponseDTO toLineDTO(Map<String, Object> row) {
        return StockTransferLineResponseDTO.builder()
                .id((UUID) row.get("id"))
                .variantId((UUID) row.get("variant_id"))
                .sourceLocationId((UUID) row.get("source_location_id"))
                .destLocationId((UUID) row.get("dest_location_id"))
                .qtyRequested((BigDecimal) row.get("qty_requested"))
                .qtyDispatched((BigDecimal) row.get("qty_dispatched"))
                .qtyReceived((BigDecimal) row.get("qty_received"))
                .qtyInTransit(BigDecimal.ZERO)
                .uom((String) row.get("uom"))
                .uomQuantity((BigDecimal) row.get("uom_quantity"))
                .build();
    }

    private static boolean hasAuthority(BimeAuthentication caller, BimePermission permission) {
        return caller.getAuthorities().stream().anyMatch(a -> permission.name().equals(a.getAuthority()));
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableString(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value != null ? spec.bind(name, value) : spec.bindNull(name, String.class);
    }
}
