package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Request body for creating or patching a product variant")
public class ProductVariantRequestDTO {
    @Schema(description = "Combination of metadata option IDs that uniquely defines this variant (e.g. [red-id, xl-id]). " +
            "Each option must belong to a metadata definition that is assigned to the parent product, " +
            "and at most one option per metadata definition is allowed")
    private List<UUID> optionIds;
    @Schema(description = "Optional variant-specific SKU. When set, overrides the parent product SKU for this variant", example = "WIDGET-001-RED-XL")
    private String sku;
    private Boolean isActive;
}
