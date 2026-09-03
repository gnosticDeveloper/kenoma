package bime.services;

import bime.db.BimeContextService;
import bime.dto.UomConversionRequestDTO;
import bime.dto.UomConversionResponseDTO;
import common.exception.BadRequestException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UomConversionService {

    private final BimeContextService ctx;

    public Mono<UomConversionResponseDTO> setConversion(UUID variantId, UomConversionRequestDTO dto) {
        if (dto.getUomName() == null || dto.getUomName().isBlank()) {
            return Mono.error(new BadRequestException("uomName is required"));
        }
        if (dto.getFactor() == null || dto.getFactor().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new BadRequestException("factor must be a positive number"));
        }
        if (dto.getPrice() != null && dto.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new BadRequestException("price must be a positive number"));
        }
        String normalizedName = UomNames.normalize(dto.getUomName());
        return ctx.withHandle((caller, handle) ->
                UnitCatalog.resolveUnitId(handle, caller.getOrgId(), normalizedName)
                        .flatMap(uomId -> {
                            var spec = handle.client().sql("""
                                    INSERT INTO variant_uom_conversions (org_id, variant_id, uom_id, factor, price)
                                    SELECT :orgId, pv.id, :uomId, :factor, :price
                                    FROM product_variants pv
                                    WHERE pv.id = :variantId AND pv.org_id = :orgId
                                    ON CONFLICT (variant_id, uom_id)
                                        DO UPDATE SET factor = EXCLUDED.factor, price = EXCLUDED.price, modified_at = current_timestamp
                                    RETURNING id, org_id, variant_id, factor, price, created_at, modified_at,
                                        :uomName AS uom_name,
                                        (SELECT pv2.price FROM product_variants pv2 WHERE pv2.id = variant_id) AS variant_price,
                                        (SELECT pv2.cost FROM product_variants pv2 WHERE pv2.id = variant_id) AS variant_cost
                                    """)
                                    .bind("orgId", caller.getOrgId())
                                    .bind("variantId", variantId)
                                    .bind("uomId", uomId)
                                    .bind("factor", dto.getFactor())
                                    .bind("uomName", normalizedName);
                            spec = dto.getPrice() != null ? spec.bind("price", dto.getPrice()) : spec.bindNull("price", BigDecimal.class);
                            return spec.fetch()
                                    .one()
                                    .map(this::toResponseDTO)
                                    .switchIfEmpty(Mono.error(new NotFoundException("Variant not found")));
                        })
        );
    }

    public Flux<UomConversionResponseDTO> getConversions(UUID variantId) {
        return ctx.withHandleMany((caller, handle) -> handle.client().sql("""
                SELECT vuc.id, vuc.org_id, vuc.variant_id, ou.name AS uom_name, vuc.factor, vuc.price,
                       vuc.created_at, vuc.modified_at, pv.price AS variant_price, pv.cost AS variant_cost
                FROM variant_uom_conversions vuc
                JOIN product_variants pv ON pv.id = vuc.variant_id
                JOIN org_units ou ON ou.id = vuc.uom_id
                WHERE vuc.org_id = :orgId AND vuc.variant_id = :variantId
                ORDER BY ou.name
                """)
                .bind("orgId", caller.getOrgId())
                .bind("variantId", variantId)
                .fetch()
                .all()
                .map(this::toResponseDTO)
        );
    }

    public Mono<Void> deleteConversion(UUID variantId, String uomName) {
        String normalizedName = UomNames.normalize(uomName);
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                DELETE FROM variant_uom_conversions vuc
                USING org_units ou
                WHERE ou.id = vuc.uom_id
                  AND vuc.org_id = :orgId AND vuc.variant_id = :variantId AND ou.name = :uomName
                """)
                .bind("orgId", caller.getOrgId())
                .bind("variantId", variantId)
                .bind("uomName", normalizedName)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Unit-of-measure conversion not found"))
                        : Mono.empty())
        ).then();
    }

    /** price can have an explicit per-unit override (a case sold as a bulk discount); cost cannot -
      * there's no purchase-batch tracking yet to back a real "this case actually cost X" number, so
      * effectiveCost is always purely derived (factor * the variant's cost). Revisit once batches
      * exist and cost can be tied to an actual purchase record instead of a typed-in override. */
    static BigDecimal effectivePrice(BigDecimal explicitPrice, BigDecimal factor, BigDecimal variantPrice) {
        if (explicitPrice != null) return explicitPrice;
        if (variantPrice == null) return null;
        return factor.multiply(variantPrice);
    }

    static BigDecimal effectiveCost(BigDecimal factor, BigDecimal variantCost) {
        if (variantCost == null) return null;
        return factor.multiply(variantCost);
    }

    private UomConversionResponseDTO toResponseDTO(Map<String, Object> row) {
        BigDecimal factor = (BigDecimal) row.get("factor");
        BigDecimal price = (BigDecimal) row.get("price");
        BigDecimal variantPrice = (BigDecimal) row.get("variant_price");
        BigDecimal variantCost = (BigDecimal) row.get("variant_cost");
        return UomConversionResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .variantId((UUID) row.get("variant_id"))
                .uomName((String) row.get("uom_name"))
                .factor(factor)
                .price(price)
                .effectivePrice(effectivePrice(price, factor, variantPrice))
                .effectiveCost(effectiveCost(factor, variantCost))
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
