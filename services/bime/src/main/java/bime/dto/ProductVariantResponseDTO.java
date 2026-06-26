package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Product variant details including its defining options and per-location stock")
public class ProductVariantResponseDTO {
    private UUID id;
    private UUID productId;
    private UUID orgId;
    @Schema(description = "Variant-specific SKU, or null if the parent product SKU applies", example = "WIDGET-001-RED-XL")
    private String sku;
    private Boolean isActive;
    private LocalDateTime createdAt;
    @Schema(description = "The metadata options that define this variant (e.g. Colour=Red, Size=XL)")
    private List<MetadataOptionResponseDTO> options;
    @Schema(description = "Current stock balances for this variant across all locations")
    private List<VariantStockDTO> stock;
}
