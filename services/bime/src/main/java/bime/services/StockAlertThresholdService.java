package bime.services;

import bime.db.BimeContextService;
import bime.db.OptionFilterSql;
import bime.dto.StockAlertResponseDTO;
import bime.dto.StockAlertThresholdRequestDTO;
import bime.dto.StockAlertThresholdResponseDTO;
import common.db.WhereClause;
import common.exception.BadRequestException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockAlertThresholdService {

    private final BimeContextService ctx;

    public Mono<StockAlertThresholdResponseDTO> setThreshold(StockAlertThresholdRequestDTO dto) {
        if (dto.getThreshold() < 0) {
            return Mono.error(new BadRequestException("threshold must be zero or a positive integer"));
        }
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                INSERT INTO variant_stock_alert_thresholds (org_id, variant_id, location_id, threshold)
                SELECT :orgId, pv.id, l.id, :threshold
                FROM product_variants pv
                JOIN locations l ON l.id = :locationId AND l.org_id = :orgId
                WHERE pv.id = :variantId AND pv.org_id = :orgId
                ON CONFLICT (org_id, variant_id, location_id)
                    DO UPDATE SET threshold = EXCLUDED.threshold, modified_at = current_timestamp
                RETURNING org_id, variant_id, location_id, threshold, created_at, modified_at
                """)
                .bind("orgId", caller.getOrgId())
                .bind("variantId", dto.getVariantId())
                .bind("locationId", dto.getLocationId())
                .bind("threshold", dto.getThreshold())
                .fetch()
                .one()
                .map(this::toThresholdResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Variant or location not found")))
        );
    }

    public Flux<StockAlertThresholdResponseDTO> getThresholds(UUID variantId, UUID locationId, List<UUID> optionIds, boolean matchAll) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("variant_id", "variantId", variantId)
                    .eqIfPresent("location_id", "locationId", locationId)
                    .raw(OptionFilterSql.fragment("variant_id"));

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT org_id, variant_id, location_id, threshold, created_at, modified_at
                    FROM variant_stock_alert_thresholds
                    %s
                    """.formatted(where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            spec = OptionFilterSql.bind(spec, optionIds, matchAll);
            return spec.fetch().all().map(this::toThresholdResponseDTO);
        });
    }

    public Mono<Void> deleteThreshold(UUID variantId, UUID locationId) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                DELETE FROM variant_stock_alert_thresholds
                WHERE org_id = :orgId AND variant_id = :variantId AND location_id = :locationId
                """)
                .bind("orgId", caller.getOrgId())
                .bind("variantId", variantId)
                .bind("locationId", locationId)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Threshold not found"))
                        : Mono.empty())
        ).then();
    }

    public Flux<StockAlertResponseDTO> getActiveAlerts(UUID variantId, UUID locationId, List<UUID> optionIds, boolean matchAll) {
        return ctx.withHandleMany((caller, handle) -> {
            WhereClause where = WhereClause.of()
                    .eq("org_id", "orgId", caller.getOrgId())
                    .eqIfPresent("variant_id", "variantId", variantId)
                    .eqIfPresent("location_id", "locationId", locationId)
                    .raw(OptionFilterSql.fragment("variant_id"));

            DatabaseClient.GenericExecuteSpec spec = handle.client().sql("""
                    SELECT org_id, variant_id, location_id, threshold, quantity, triggered_at
                    FROM variant_stock_alerts
                    %s
                    ORDER BY triggered_at DESC
                    """.formatted(where.toSql()));
            for (WhereClause.Binding b : where.bindings()) {
                spec = spec.bind(b.name(), b.value());
            }
            spec = OptionFilterSql.bind(spec, optionIds, matchAll);
            return spec.fetch().all().map(this::toAlertResponseDTO);
        });
    }

    private StockAlertThresholdResponseDTO toThresholdResponseDTO(Map<String, Object> row) {
        return StockAlertThresholdResponseDTO.builder()
                .orgId((UUID) row.get("org_id"))
                .variantId((UUID) row.get("variant_id"))
                .locationId((UUID) row.get("location_id"))
                .threshold((Integer) row.get("threshold"))
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }

    private StockAlertResponseDTO toAlertResponseDTO(Map<String, Object> row) {
        return StockAlertResponseDTO.builder()
                .orgId((UUID) row.get("org_id"))
                .variantId((UUID) row.get("variant_id"))
                .locationId((UUID) row.get("location_id"))
                .threshold((Integer) row.get("threshold"))
                .quantity((Integer) row.get("quantity"))
                .triggeredAt((LocalDateTime) row.get("triggered_at"))
                .build();
    }
}
