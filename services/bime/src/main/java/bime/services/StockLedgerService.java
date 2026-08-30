package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.db.OptionFilterSql;
import bime.dto.MovementStatus;
import bime.dto.MovementType;
import bime.dto.StockBalanceResponseDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockLedgerService {

    private final BimeContextService ctx;

    static final String MOVEMENT_COLUMNS = """
            id, org_id, product_id, variant_id, location_id, movement_type, status,
            delta, uom, uom_quantity, reference_id, note, created_at, created_by
            """;

    public Mono<StockMovementResponseDTO> recordMovement(StockMovementRequestDTO dto) {
        if (dto.getMovementType() == MovementType.TRANSFER_OUT || dto.getMovementType() == MovementType.TRANSFER_IN) {
            return Mono.error(new BadRequestException(
                    "TRANSFER_OUT and TRANSFER_IN movements are created by the transfer-order flow, not directly"));
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
                resolveBaseDelta(handle, caller.getOrgId(), dto.getVariantId(), dto.getUom(), dto.getDelta())
                        .flatMap(baseDelta -> appendMovement(handle, caller.getOrgId(), caller.getId(),
                                dto.getVariantId(), dto.getLocationId(), dto.getMovementType(), status, baseDelta,
                                dto.getUom(), dto.getUom() != null ? dto.getDelta() : null,
                                dto.getReferenceId(), dto.getNote()))
        )));
    }

    /**
     * Flip a PENDING movement to POSTED, applying its delta to the running balance. Used both by
     * the manual endpoint and, indirectly, by the transfer receive flow.
     */
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
                                    .thenReturn(movement);
                        })
        )));
    }

    /** Void a PENDING movement without ever applying its delta. */
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
                .map(this::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Pending movement not found")))
        );
    }

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
                .map(this::toMovementResponseDTO)
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
            return spec.fetch().all().map(this::toMovementResponseDTO);
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
     * Insert one movement row (deriving product_id from the variant, and joining locations so a
     * location from another org can't be smuggled in with a valid same-org variant). When the
     * movement is POSTED its delta is immediately applied to the running balance; a PENDING
     * movement is only recorded. Runs inside the caller's transaction.
     */
    Mono<StockMovementResponseDTO> appendMovement(BimeDbHandle handle, UUID orgId, UUID userId,
                                                  UUID variantId, UUID locationId, MovementType type,
                                                  MovementStatus status, BigDecimal baseDelta,
                                                  String uom, BigDecimal uomQuantity, UUID referenceId, String note) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO stock_movements
                    (org_id, product_id, variant_id, location_id, movement_type, status, delta, uom, uom_quantity, reference_id, note, created_by)
                SELECT :orgId, pv.product_id, pv.id, l.id, :movementType, :status, :delta, :uom, :uomQuantity, :referenceId, :note, :createdBy
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
        if (referenceId != null) {
            spec = spec.bind("referenceId", referenceId);
        } else {
            spec = spec.bindNull("referenceId", UUID.class);
        }

        Mono<StockMovementResponseDTO> inserted = spec.fetch().one()
                .map(this::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Variant or location not found")));

        if (status != MovementStatus.POSTED) {
            return inserted;
        }
        return inserted.flatMap(movement ->
                upsertBalance(handle, orgId, variantId, locationId, baseDelta).thenReturn(movement));
    }

    Mono<Long> upsertBalance(BimeDbHandle handle, UUID orgId, UUID variantId, UUID locationId, BigDecimal baseDelta) {
        LocalDateTime now = LocalDateTime.now();
        // Same two-step pattern as before: guarantee the row exists at 0 first so the CHECK
        // constraint (quantity >= 0) is never evaluated on the initial insert, then apply the delta.
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

    private StockMovementResponseDTO toMovementResponseDTO(Map<String, Object> row) {
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
