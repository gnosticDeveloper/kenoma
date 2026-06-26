package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockLedgerService {

    private final BimeContextService ctx;

    public Mono<StockMovementResponseDTO> recordMovement(StockMovementRequestDTO dto) {
        if (dto.getMovementType() == MovementType.INBOUND && dto.getDelta() <= 0) {
            return Mono.error(new BadRequestException("INBOUND movement must have a positive delta"));
        }
        if (dto.getMovementType() == MovementType.OUTBOUND && dto.getDelta() >= 0) {
            return Mono.error(new BadRequestException("OUTBOUND movement must have a negative delta"));
        }
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                insertMovement(handle, caller.getOrgId(), caller.getId(), dto)
                        .flatMap(movement -> upsertBalance(handle, caller.getOrgId(), dto)
                                .thenReturn(movement))
        )));
    }

    public Mono<StockMovementResponseDTO> getMovementById(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT id, org_id, product_id, variant_id, location_id, movement_type,
                       delta, reference_id, note, created_at, created_by
                FROM stock_movements
                WHERE id = :id AND org_id = :orgId
                """)
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(this::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Stock movement not found")))
        );
    }

    public Flux<StockMovementResponseDTO> getMovements(UUID variantId, UUID locationId) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("variant_id", "variantId", variantId)
                    .eqIfPresent("location_id", "locationId", locationId);

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT id, org_id, product_id, variant_id, location_id, movement_type,
                           delta, reference_id, note, created_at, created_by
                    FROM stock_movements
                    %s
                    ORDER BY created_at DESC
                    """.formatted(where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            return spec.fetch().all().map(this::toMovementResponseDTO);
        });
    }

    public Flux<StockBalanceResponseDTO> getBalances(UUID variantId, UUID locationId) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("variant_id", "variantId", variantId)
                    .eqIfPresent("location_id", "locationId", locationId);

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT org_id, variant_id, location_id, quantity, modified_at
                    FROM variant_stock_balances
                    %s
                    """.formatted(where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            return spec.fetch().all().map(this::toBalanceResponseDTO);
        });
    }

    // Derives product_id from the variant in the same INSERT to avoid an extra round-trip.
    // Returns empty if the variant doesn't exist or doesn't belong to the org.
    private Mono<StockMovementResponseDTO> insertMovement(BimeDbHandle handle, UUID orgId, UUID userId, StockMovementRequestDTO dto) {
        DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                INSERT INTO stock_movements
                    (org_id, product_id, variant_id, location_id, movement_type, delta, reference_id, note, created_by)
                SELECT :orgId, pv.product_id, pv.id, :locationId, :movementType, :delta, :referenceId, :note, :createdBy
                FROM product_variants pv
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                RETURNING id, org_id, product_id, variant_id, location_id, movement_type,
                          delta, reference_id, note, created_at, created_by
                """)
                .bind("orgId", orgId)
                .bind("variantId", dto.getVariantId())
                .bind("locationId", dto.getLocationId())
                .bind("movementType", dto.getMovementType().name())
                .bind("delta", dto.getDelta())
                .bind("note", dto.getNote() != null ? dto.getNote() : "")
                .bind("createdBy", userId);

        if (dto.getReferenceId() != null) {
            spec = spec.bind("referenceId", dto.getReferenceId());
        } else {
            spec = spec.bindNull("referenceId", UUID.class);
        }

        return spec.fetch().one()
                .map(this::toMovementResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")));
    }

    private Mono<Long> upsertBalance(BimeDbHandle handle, UUID orgId, StockMovementRequestDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        // Same two-step pattern as before: guarantee the row exists at 0 first so the CHECK
        // constraint (quantity >= 0) is never evaluated on the initial insert, then apply the delta.
        return handle.client().sql("""
                INSERT INTO variant_stock_balances (org_id, variant_id, location_id, quantity, modified_at)
                VALUES (:orgId, :variantId, :locationId, 0, :modifiedAt)
                ON CONFLICT (org_id, variant_id, location_id) DO NOTHING
                """)
                .bind("orgId", orgId)
                .bind("variantId", dto.getVariantId())
                .bind("locationId", dto.getLocationId())
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
                        .bind("variantId", dto.getVariantId())
                        .bind("locationId", dto.getLocationId())
                        .bind("delta", dto.getDelta())
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
                .delta((Integer) row.get("delta"))
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
                .quantity((Integer) row.get("quantity"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
