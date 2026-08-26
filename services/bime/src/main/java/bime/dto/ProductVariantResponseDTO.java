package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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
    @Schema(description = "Server-generated SKU: the product's SKU followed by a code fragment for each defining option", example = "WIDGET-001-RED-XL")
    private String sku;
    private Boolean isActive;
    private LocalDateTime createdAt;
    @Schema(description = "Canonical price as stored (in priceCurrency), or already converted to the requested ?currency if one was passed")
    private BigDecimal price;
    @Schema(description = "Currency of the price field above - the stored currency, or the requested conversion target if ?currency was passed", example = "USD")
    private String priceCurrency;
    @Schema(description = "The metadata options that define this variant (e.g. Color=Red, Size=XL)")
    private List<MetadataOptionResponseDTO> options;
    @Schema(description = "Current stock balances for this variant across all locations")
    private List<VariantStockDTO> stock;
}
