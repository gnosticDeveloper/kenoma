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
    @Schema(description = "Purchase cost (COGS), for margin visibility. Independent of price - not affected by ?currency conversion")
    private BigDecimal cost;
    @Schema(description = "Currency of the cost field above", example = "USD")
    private String costCurrency;
    @Schema(description = "The unit stock is tracked in for this variant. Movements/balances are always in this unit; " +
            "see GET /variants/{variantId}/uom-conversions for alternate units this variant can be bought/sold in", example = "each")
    private String baseUom;
    @Schema(description = "The metadata options that define this variant (e.g. Color=Red, Size=XL)")
    private List<MetadataOptionResponseDTO> options;
    @Schema(description = "Current stock balances for this variant across all locations")
    private List<VariantStockDTO> stock;
    @Schema(description = "Alternate units this variant can be bought/sold in, and their conversion factor to baseUom")
    private List<UomConversionResponseDTO> uomConversions;
}
