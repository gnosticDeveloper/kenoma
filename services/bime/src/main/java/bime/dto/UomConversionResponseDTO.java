package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A variant's configured conversion from an alternate unit of measure to its base unit")
public class UomConversionResponseDTO {
    private UUID id;
    private UUID orgId;
    private UUID variantId;
    private String uomName;
    @Schema(description = "Number of base units that make up one of uomName")
    private BigDecimal factor;
    @Schema(description = "Flat price for one of this unit if explicitly set (in the variant's priceCurrency), otherwise null")
    private BigDecimal price;
    @Schema(description = "Price for one of this unit: the explicit price above if set, otherwise factor * the variant's price. " +
            "Null if the variant has no price set")
    private BigDecimal effectivePrice;
    @Schema(description = "Purchase cost for one of this unit, always derived as factor * the variant's cost (no per-unit " +
            "override - a bulk purchase's actual cost belongs to batch/lot tracking, not this). Null if the variant has no cost set")
    private BigDecimal effectiveCost;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
}
