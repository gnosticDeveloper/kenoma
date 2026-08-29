package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Request body for creating or patching a product variant")
public class ProductVariantRequestDTO {
    @Schema(description = "Combination of metadata option IDs that uniquely defines this variant (e.g. [red-id, xl-id]). " +
            "Each option must belong to a metadata definition that is assigned to the parent product, " +
            "and at most one option per metadata definition is allowed")
    private List<UUID> optionIds;
    private Boolean isActive;
    private BigDecimal price;
    @Schema(description = "ISO 4217 currency code the price is set in. Required when price is set", example = "USD")
    private String priceCurrency;
    @Schema(description = "Purchase cost (COGS), for margin visibility. Independent of price")
    private BigDecimal cost;
    @Schema(description = "ISO 4217 currency code the cost is set in. Required when cost is set", example = "USD")
    private String costCurrency;
    @Schema(description = "The unit stock is tracked in for this variant (e.g. \"units\", \"kg\", \"m\"). " +
            "Defaults to \"units\" on create if omitted.", example = "units")
    private String baseUom;
    @Schema(description = "Optional alternate units to configure at creation time (e.g. a \"case\" of 24). " +
            "Equivalent to calling PUT /variants/{variantId}/uom-conversions once per entry right after creation; " +
            "only read on create, ignored on patch - manage conversions afterward via that endpoint")
    private List<UomConversionRequestDTO> uomConversions;
}
