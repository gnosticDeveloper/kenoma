package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.db.OptionFilterSql;
import bime.dto.MovementStatus;
import bime.dto.MovementType;
import bime.dto.StockBalanceResponseDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
import bime.services.Gs1Parser.Gs1Scan;
import common.db.WhereClause;
import common.exception.BadRequestException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockLedgerService {

    private final BimeContextService ctx;
    private final BatchService batchService;

    static final String MOVEMENT_COLUMNS = """
            id, org_id, product_id, variant_id, location_id, movement_type, status,
            delta, uom, uom_quantity, reference_id, note, created_at, created_by, batch_id
            """;

    public Mono<StockMovementResponseDTO> recordMovement(StockMovementRequestDTO dto) {
        if (dto.getMovementType() == MovementType.TRANSFER_OUT || dto.getMovementType() == MovementType.TRANSFER_IN) {
            return Mono.error(new BadRequestException(
                    "TRANSFER_OUT and TRANSFER_IN movements are created by the transfer-order flow, not directly"));
        }
        if (dto.getMovementType() == MovementType.SALE) {
            return Mono.error(new BadRequestException(
                    "SALE movements are created by the sales flow (POST /sales), not directly"));
        }
        if (dto.getMovementType() == MovementType.INBOUND && dto.getDelta().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new BadRequestException("INBOUND movement must have a positive delta"));
        }
        if (dto.getMovementType() == MovementType.OUTBOUND && dto.getDelta().compareTo(BigDecimal.ZERO) >= 0) {
            return Mono.error(new BadRequestException("OUTBOUND movement must have a negative delta"));
        }
        MovementStatus status = dto.getStatus() != null ? dto.getStatus() : MovementStatus.POSTED;
        if (status == MovementStatus.CANCELLED) {
            return Mono.error(new BadRequestException("A movement cannot be created already CANCELLED"));
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                tracksBatches(handle, caller.getOrgId(), dto.getVariantId()).flatMap(tracked -> {
                    if (tracked) {
                        return recordBatchTracked(handle, caller.getOrgId(), caller.getId(), dto, status);
                    }
                    return resolveBaseDelta(handle, caller.getOrgId(), dto.getVariantId(), dto.getUom(), dto.getDelta())
                            .flatMap(baseDelta -> appendMovement(handle, caller.getOrgId(), caller.getId(),
                                    dto.getVariantId(), dto.getLocationId(), dto.getMovementType(), status, baseDelta,
                                    dto.getUom(), dto.getUom() != null ? dto.getDelta() : null,
                                    dto.getReferenceId(), dto.getNote(), null));
                })
        )));
    }


    private Mono<Boolean> tracksBatches(BimeDbHandle handle, UUID orgId, UUID variantId) {
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
                .map(row -> (Boolean) row.get("tracks_batches"))
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")));
    }

    private Mono<StockMovementResponseDTO> recordBatchTracked(BimeDbHandle handle, UUID orgId, UUID userId,
                                                             StockMovementRequestDTO dto, MovementStatus status) {
        return resolveBaseDelta(handle, orgId, dto.getVariantId(), dto.getUom(), dto.getDelta())
                .flatMap(baseDelta -> {
                    if (baseDelta.signum() == 0) {
                        return Mono.error(new BadRequestException("delta must be non-zero"));
                    }
                    return baseDelta.signum() > 0
                            ? recordBatchInbound(handle, orgId, userId, dto, status, baseDelta)
                            : recordBatchOutbound(handle, orgId, userId, dto, status, baseDelta);
                });
    }

    /** INBOUND / positive ADJUSTMENT into a named batch. */
    private Mono<StockMovementResponseDTO> recordBatchInbound(BimeDbHandle handle, UUID orgId, UUID userId,
                                                             StockMovementRequestDTO dto, MovementStatus status,
                                                             BigDecimal baseDelta) {
        Mono<UUID> batchId;
        if (dto.getBatchId() != null) {
            batchId = batchService.requireBatch(handle, orgId, dto.getBatchId())
                    .flatMap(row -> {
                        if (!dto.getVariantId().equals(row.get("variant_id"))) {
                            return Mono.error(new BadRequestException("batch does not belong to this variant"));
                        }
                        if ("RECALLED".equals(row.get("status"))) {
                            return Mono.error(new BadRequestException("cannot add stock to a recalled batch"));
                        }
                        return Mono.just(dto.getBatchId());
                    });
        } else {
            String batchCode = dto.getBatchCode();
            LocalDate expiry = dto.getExpiryDate();
            if (dto.getGs1() != null && !dto.getGs1().isBlank()) {
                Gs1Scan scan = Gs1Parser.parse(dto.getGs1());
                if (scan == null || scan.lot() == null) {
                    return Mono.error(new BadRequestException(
                            "the GS1 scan did not contain a batch/lot (AI 10); enter the batch code manually"));
                }
                batchCode = scan.lot();
                expiry = scan.expiry() != null ? scan.expiry() : expiry;
            }
            if (batchCode == null || batchCode.isBlank()) {
                return Mono.error(new BadRequestException(
                        "a batch is required for inbound movements of a batch-tracked product (batchId, batchCode, or gs1)"));
            }
            batchId = batchService.upsertBatch(handle, orgId, dto.getVariantId(), batchCode, expiry);
        }

        return batchId.flatMap(id -> insertMovementRow(handle, orgId, userId, dto.getVariantId(), dto.getLocationId(),
                        dto.getMovementType(), status, baseDelta, dto.getUom(),
                        dto.getUom() != null ? dto.getDelta() : null, dto.getReferenceId(), dto.getNote(), id)
                .flatMap(movement -> {
                    if (status != MovementStatus.POSTED) {
                        return Mono.just(movement);
                    }
                    return upsertBalance(handle, orgId, dto.getVariantId(), dto.getLocationId(), baseDelta)
                            .then(upsertBatchBalance(handle, orgId, id, dto.getVariantId(), dto.getLocationId(), baseDelta))
                            .thenReturn(movement);
                }));
    }

    /** OUTBOUND / negative ADJUSTMENT: an explicit batch, or first-expired-first-out across the
      * variant's active batches at that location. Always POSTED - a pending batch outbound is not
      * supported. Writes one movement row per batch drawn from. */
    private Mono<StockMovementResponseDTO> recordBatchOutbound(BimeDbHandle handle, UUID orgId, UUID userId,
                                                              StockMovementRequestDTO dto, MovementStatus status,
                                                              BigDecimal baseDelta) {
        if (status != MovementStatus.POSTED) {
            return Mono.error(new BadRequestException(
                    "PENDING is not supported for batch-tracked outbound movements"));
        }
        BigDecimal needed = baseDelta.abs();

        if (dto.getBatchId() != null) {
            return batchService.requireBatch(handle, orgId, dto.getBatchId()).flatMap(row -> {
                if (!dto.getVariantId().equals(row.get("variant_id"))) {
                    return Mono.error(new BadRequestException("batch does not belong to this variant"));
                }
                if ("RECALLED".equals(row.get("status")) && dto.getMovementType() == MovementType.OUTBOUND) {
                    return Mono.error(new BadRequestException("batch \"" + row.get("batch_code")
                            + "\" is under recall; record a disposal ADJUSTMENT instead of an OUTBOUND"));
                }
                return consumeFromBatch(handle, orgId, userId, dto, dto.getBatchId(), baseDelta,
                        dto.getUom(), dto.getUom() != null ? dto.getDelta() : null);
            });
        }

        return allocateFefo(handle, orgId, dto.getVariantId(), dto.getLocationId(), needed)
                .flatMap(plan -> applyPlan(handle, orgId, userId, dto, plan, baseDelta));
    }

    /**
     * First-expired-first-out plan: allocate {@code needed} (positive, in base units) across the
     * variant's ACTIVE batch balances at {@code locationId}, earliest expiry first. Each entry is
     * {@code [UUID batchId, BigDecimal quantity]} with a positive quantity. Errors with the standard
     * insufficient-stock message when the active batches at that location cannot cover the amount.
     * Shared by direct OUTBOUND movements and by transfer-order dispatch.
     */
    Mono<List<Object[]>> allocateFefo(BimeDbHandle handle, UUID orgId, UUID variantId,
                                      UUID locationId, BigDecimal needed) {
        return activeBatchBalances(handle, orgId, variantId, locationId)
                .collectList()
                .flatMap(rows -> {
                    BigDecimal available = rows.stream()
                            .map(r -> (BigDecimal) r.get("quantity"))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (available.compareTo(needed) < 0) {
                        return Mono.error(new BadRequestException(
                                "Insufficient stock: only " + available.stripTrailingZeros().toPlainString()
                                        + " available across active batches at this location"));
                    }
                    List<Object[]> plan = new ArrayList<>();
                    BigDecimal remaining = needed;
                    for (Map<String, Object> r : rows) {
                        if (remaining.signum() <= 0) break;
                        BigDecimal take = ((BigDecimal) r.get("quantity")).min(remaining);
                        plan.add(new Object[]{(UUID) r.get("batch_id"), take});
                        remaining = remaining.subtract(take);
                    }
                    return Mono.just(plan);
                });
    }

    private Mono<StockMovementResponseDTO> applyPlan(BimeDbHandle handle, UUID orgId, UUID userId,
                                                    StockMovementRequestDTO dto, List<Object[]> plan, BigDecimal baseDelta) {
        return Flux.fromIterable(plan)
                .concatMap(entry -> consumeFromBatch(handle, orgId, userId, dto, (UUID) entry[0],
                        ((BigDecimal) entry[1]).negate(), null, null))
                .collectList()
                .flatMap(movements -> upsertBalance(handle, orgId, dto.getVariantId(), dto.getLocationId(), baseDelta)
                        .thenReturn(movements))
                .map(movements -> {
                    if (movements.size() == 1) {
                        return movements.get(0);
                    }
                    StockMovementResponseDTO first = movements.get(0);
                    return StockMovementResponseDTO.builder()
                            .id(null)
                            .orgId(first.getOrgId())
                            .productId(first.getProductId())
                            .variantId(first.getVariantId())
                            .locationId(first.getLocationId())
                            .movementType(first.getMovementType())
                            .status(first.getStatus())
                            .delta(baseDelta)
                            .uom(dto.getUom())
                            .uomQuantity(dto.getUom() != null ? dto.getDelta() : null)
                            .referenceId(first.getReferenceId())
                            .note(first.getNote())
                            .createdAt(first.getCreatedAt())
                            .createdBy(first.getCreatedBy())
                            .batchId(null)
                            .allocations(movements)
                            .build();
                });
    }

    /** Insert one negative movement row against {@code batchId} and decrement its batch balance.
      * The batch-balance CHECK (quantity >= 0) enforces "can't take more than the batch holds". */
    private Mono<StockMovementResponseDTO> consumeFromBatch(BimeDbHandle handle, UUID orgId, UUID userId,
                                                           StockMovementRequestDTO dto, UUID batchId, BigDecimal signedDelta,
                                                           String uom, BigDecimal uomQuantity) {
        return insertMovementRow(handle, orgId, userId, dto.getVariantId(), dto.getLocationId(),
                        dto.getMovementType(), MovementStatus.POSTED, signedDelta, uom, uomQuantity,
                        dto.getReferenceId(), dto.getNote(), batchId)
                .flatMap(movement -> upsertBatchBalance(handle, orgId, batchId, dto.getVariantId(),
                        dto.getLocationId(), signedDelta).thenReturn(movement));
    }

    private Flux<Map<String, Object>> activeBatchBalances(BimeDbHandle handle, UUID orgId, UUID variantId, UUID locationId) {
        return handle.client().sql("""
                SELECT bb.batch_id, bb.quantity
                FROM stock_batch_balances bb
                JOIN stock_batches sb ON sb.id = bb.batch_id
                WHERE bb.org_id = :orgId AND bb.variant_id = :variantId AND bb.location_id = :locationId
                  AND bb.quantity > 0 AND sb.status = 'ACTIVE'
                ORDER BY sb.expiry_date ASC NULLS LAST, sb.created_at ASC
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("locationId", locationId)
                .fetch()
                .all();
    }

    Mono<StockMovementResponseDTO> upsertBatchBalanceForPost(BimeDbHandle handle, StockMovementResponseDTO movement) {
        if (movement.getBatchId() == null) {
            return Mono.just(movement);
        }
        return upsertBatchBalance(handle, movement.getOrgId(), movement.getBatchId(), movement.getVariantId(),
                movement.getLocationId(), movement.getDelta()).thenReturn(movement);
    }

    // ---------------------------------------------------------------------------------------------
    // Pending lifecycle
    // ---------------------------------------------------------------------------------------------

    public Mono<StockMovementResponseDTO> postPendingMovement(UUID id) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                handle.client().sql("""
                        UPDATE stock_movements SET status = 'POSTED'
                        WHERE id = :id AND org_id = :orgId AND status = 'PENDING'
                        RETURNING %s
                        """.formatted(MOVEMENT_COLUMNS))
                        .bind("id", id)
                        .bind("orgId", caller.getOrgId())
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(new NotFoundException("Pending movement not found")))
                        .flatMap(row -> {
                            StockMovementResponseDTO movement = toMovementResponseDTO(row);
                            return upsertBalance(handle, movement.getOrgId(), movement.getVariantId(),
                                    movement.getLocationId(), movement.getDelta())
                                    .then(upsertBatchBalanceForPost(handle, movement));
                        })
        )));
    }

    public Mono<StockMovementResponseDTO> cancelPendingMovement(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE stock_movements SET status = 'CANCELLED'
                WHERE id = :id AND org_id = :orgId AND status = 'PENDING'
                RETURNING %s
                """.formatted(MOVEMENT_COLUMNS))
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(StockLedgerService::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Pending movement not found")))
        );
    }

    // ---------------------------------------------------------------------------------------------
    // Unit-of-measure resolution
    // ---------------------------------------------------------------------------------------------

    /**
     * Convert an entered quantity to the variant's base unit. When {@code uom} is null the quantity
     * is already in the base unit. Otherwise it is multiplied by the explicit per-variant
     * conversion factor, or a standard metric factor, else the call fails.
     */
    Mono<BigDecimal> resolveBaseDelta(BimeDbHandle handle, UUID orgId, UUID variantId, String uom, BigDecimal quantity) {
        if (uom == null) {
            return Mono.just(quantity);
        }
        String normalizedUom = UomNames.normalize(uom);
        return handle.client().sql("""
                SELECT ou_base.name AS base_uom_name, vuc.factor AS explicit_factor
                FROM product_variants pv
                JOIN org_units ou_base ON ou_base.id = pv.base_uom_id
                LEFT JOIN org_units ou_target ON ou_target.org_id = pv.org_id AND ou_target.name = :uomName
                LEFT JOIN variant_uom_conversions vuc ON vuc.variant_id = pv.id AND vuc.uom_id = ou_target.id
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("uomName", normalizedUom)
                .fetch()
                .one()
                .flatMap(row -> {
                    BigDecimal explicitFactor = (BigDecimal) row.get("explicit_factor");
                    if (explicitFactor != null) {
                        return Mono.just(quantity.multiply(explicitFactor));
                    }
                    String baseUomName = (String) row.get("base_uom_name");
                    if (baseUomName.equals(normalizedUom)) {
                        return Mono.just(quantity);
                    }
                    BigDecimal standardFactor = StandardUnits.factor(baseUomName, normalizedUom);
                    if (standardFactor != null) {
                        return Mono.just(quantity.multiply(standardFactor));
                    }
                    return Mono.error(new BadRequestException(
                            "No conversion configured from \"" + normalizedUom + "\" to this variant's base unit \"" + baseUomName + "\""));
                })
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")));
    }


    public Mono<StockMovementResponseDTO> getMovementById(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT %s
                FROM stock_movements
                WHERE id = :id AND org_id = :orgId
                """.formatted(MOVEMENT_COLUMNS))
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(StockLedgerService::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Stock movement not found")))
        );
    }

    public Flux<StockMovementResponseDTO> getMovements(UUID variantId, UUID locationId, MovementStatus status,
                                                      List<UUID> optionIds, boolean matchAll) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("variant_id", "variantId", variantId)
                    .eqIfPresent("location_id", "locationId", locationId)
                    .eqIfPresent("status", "status", status != null ? status.name() : null)
                    .raw(OptionFilterSql.fragment("variant_id"));

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT %s
                    FROM stock_movements
                    %s
                    ORDER BY created_at DESC
                    """.formatted(MOVEMENT_COLUMNS, where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            spec = OptionFilterSql.bind(spec, optionIds, matchAll);
            return spec.fetch().all().map(StockLedgerService::toMovementResponseDTO);
        });
    }

    public Flux<StockBalanceResponseDTO> getBalances(UUID variantId, UUID locationId, List<UUID> optionIds, boolean matchAll) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("variant_id", "variantId", variantId)
                    .eqIfPresent("location_id", "locationId", locationId)
                    .raw(OptionFilterSql.fragment("variant_id"));

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT org_id, variant_id, location_id, quantity, modified_at
                    FROM variant_stock_balances
                    %s
                    """.formatted(where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            spec = OptionFilterSql.bind(spec, optionIds, matchAll);
            return spec.fetch().all().map(this::toBalanceResponseDTO);
        });
    }


    /**
     * Non-batch path: insert one movement row and, when POSTED, apply its delta to the running
     * balance. Runs inside the caller's transaction.
     */
    Mono<StockMovementResponseDTO> appendMovement(BimeDbHandle handle, UUID orgId, UUID userId,
                                                  UUID variantId, UUID locationId, MovementType type,
                                                  MovementStatus status, BigDecimal baseDelta,
                                                  String uom, BigDecimal uomQuantity, UUID referenceId, String note,
                                                  UUID batchId) {
        Mono<StockMovementResponseDTO> inserted = insertMovementRow(handle, orgId, userId, variantId, locationId,
                type, status, baseDelta, uom, uomQuantity, referenceId, note, batchId);
        if (status != MovementStatus.POSTED) {
            return inserted;
        }
        return inserted.flatMap(movement ->
                upsertBalance(handle, orgId, variantId, locationId, baseDelta).thenReturn(movement));
    }

    /** The bare INSERT (deriving product_id from the variant, joining locations so a foreign
      * location can't be smuggled in). No balance side effects. */
    Mono<StockMovementResponseDTO> insertMovementRow(BimeDbHandle handle, UUID orgId, UUID userId,
                                                     UUID variantId, UUID locationId, MovementType type,
                                                     MovementStatus status, BigDecimal baseDelta,
                                                     String uom, BigDecimal uomQuantity, UUID referenceId, String note,
                                                     UUID batchId) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO stock_movements
                    (org_id, product_id, variant_id, location_id, movement_type, status, delta, uom, uom_quantity, reference_id, note, created_by, batch_id)
                SELECT :orgId, pv.product_id, pv.id, l.id, :movementType, :status, :delta, :uom, :uomQuantity, :referenceId, :note, :createdBy, :batchId
                FROM product_variants pv
                JOIN locations l ON l.id = :locationId AND l.org_id = :orgId
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                RETURNING %s
                """.formatted(MOVEMENT_COLUMNS))
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("locationId", locationId)
                .bind("movementType", type.name())
                .bind("status", status.name())
                .bind("delta", baseDelta)
                .bind("note", note != null ? note : "")
                .bind("createdBy", userId);

        if (uom != null) {
            spec = spec.bind("uom", uom).bind("uomQuantity", uomQuantity);
        } else {
            spec = spec.bindNull("uom", String.class).bindNull("uomQuantity", BigDecimal.class);
        }
        spec = referenceId != null ? spec.bind("referenceId", referenceId) : spec.bindNull("referenceId", UUID.class);
        spec = batchId != null ? spec.bind("batchId", batchId) : spec.bindNull("batchId", UUID.class);

        return spec.fetch().one()
                .map(StockLedgerService::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Variant or location not found")));
    }

    Mono<Long> upsertBalance(BimeDbHandle handle, UUID orgId, UUID variantId, UUID locationId, BigDecimal baseDelta) {
        LocalDateTime now = LocalDateTime.now();
        return handle.client().sql("""
                INSERT INTO variant_stock_balances (org_id, variant_id, location_id, quantity, modified_at)
                VALUES (:orgId, :variantId, :locationId, 0, :modifiedAt)
                ON CONFLICT (org_id, variant_id, location_id) DO NOTHING
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("locationId", locationId)
                .bind("modifiedAt", now)
                .fetch()
                .rowsUpdated()
                .then(handle.client().sql("""
                        UPDATE variant_stock_balances
                        SET quantity    = quantity + :delta,
                            modified_at = :modifiedAt
                        WHERE org_id = :orgId AND variant_id = :variantId AND location_id = :locationId
                        """)
                        .bind("orgId", orgId)
                        .bind("variantId", variantId)
                        .bind("locationId", locationId)
                        .bind("delta", baseDelta)
                        .bind("modifiedAt", now)
                        .fetch()
                        .rowsUpdated())
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        e -> new BadRequestException("Insufficient stock: movement would result in negative balance")
                );
    }

    /** Same guarantee-row-then-apply-delta shape as {@link #upsertBalance}, against the per-batch
      * breakdown. The CHECK (quantity >= 0) rejects consuming more than a batch holds at a location. */
    Mono<Long> upsertBatchBalance(BimeDbHandle handle, UUID orgId, UUID batchId, UUID variantId, UUID locationId, BigDecimal baseDelta) {
        LocalDateTime now = LocalDateTime.now();
        return handle.client().sql("""
                INSERT INTO stock_batch_balances (org_id, variant_id, location_id, batch_id, quantity, modified_at)
                VALUES (:orgId, :variantId, :locationId, :batchId, 0, :modifiedAt)
                ON CONFLICT (org_id, batch_id, location_id) DO NOTHING
                """)
                .bind("orgId", orgId)
                .bind("variantId", variantId)
                .bind("locationId", locationId)
                .bind("batchId", batchId)
                .bind("modifiedAt", now)
                .fetch()
                .rowsUpdated()
                .then(handle.client().sql("""
                        UPDATE stock_batch_balances
                        SET quantity = quantity + :delta, modified_at = :modifiedAt
                        WHERE org_id = :orgId AND batch_id = :batchId AND location_id = :locationId
                        """)
                        .bind("orgId", orgId)
                        .bind("batchId", batchId)
                        .bind("locationId", locationId)
                        .bind("delta", baseDelta)
                        .bind("modifiedAt", now)
                        .fetch()
                        .rowsUpdated())
                .onErrorMap(
                        DataIntegrityViolationException.class,
                        e -> new BadRequestException("Insufficient stock: the selected batch does not hold that much at this location")
                );
    }

    static StockMovementResponseDTO toMovementResponseDTO(Map<String, Object> row) {
        return StockMovementResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .productId((UUID) row.get("product_id"))
                .variantId((UUID) row.get("variant_id"))
                .locationId((UUID) row.get("location_id"))
                .movementType(MovementType.valueOf((String) row.get("movement_type")))
                .status(MovementStatus.valueOf((String) row.get("status")))
                .delta((BigDecimal) row.get("delta"))
                .uom((String) row.get("uom"))
                .uomQuantity((BigDecimal) row.get("uom_quantity"))
                .referenceId((UUID) row.get("reference_id"))
                .note((String) row.get("note"))
                .createdAt((LocalDateTime) row.get("created_at"))
                .createdBy((UUID) row.get("created_by"))
                .batchId((UUID) row.get("batch_id"))
                .build();
    }

    private StockBalanceResponseDTO toBalanceResponseDTO(Map<String, Object> row) {
        return StockBalanceResponseDTO.builder()
                .orgId((UUID) row.get("org_id"))
                .variantId((UUID) row.get("variant_id"))
                .locationId((UUID) row.get("location_id"))
                .quantity((BigDecimal) row.get("quantity"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
