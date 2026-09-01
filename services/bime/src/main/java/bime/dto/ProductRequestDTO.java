package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for creating or updating a product")
public class ProductRequestDTO {
    @Schema(description = "Stock Keeping Unit. A unique identifier for this product within the organization", example = "WIDGET-001")
    private String sku;
    private String name;
    @Schema(description = "Optional description of the product")
    private String description;
    private Boolean isActive;
    @Schema(description = "When true, this product's stock is tracked by production batch (lot) and expiry date: movements must " +
            "name a batch, outbound consumption defaults to first-expired-first-out, and near-expiry / recall handling applies. " +
            "Defaults to false. Turning it off again is only sensible before any batch stock has been recorded")
    private Boolean tracksBatches;
}
