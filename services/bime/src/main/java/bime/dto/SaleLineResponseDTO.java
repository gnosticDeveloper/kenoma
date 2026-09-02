package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "One line of a completed sale. The batch(es) the stock was drawn from, when the " +
        "variant is batch-tracked, are on the SALE stock_movements linked to this sale, not here")
public class SaleLineResponseDTO {
    private UUID id;
    private UUID variantId;
    @Schema(description = "The barcode that was scanned for this line, if any")
    private String barcode;
    @Schema(description = "Quantity sold, normalized to the variant's base unit - what left stock", example = "24")
    private BigDecimal qtyBase;
    @Schema(description = "Unit of measure the line was sold in, if not the base unit (e.g. \"case\")")
    private String uom;
    @Schema(description = "Quantity as sold, in uom. Null when uom is null", example = "1")
    private BigDecimal uomQuantity;
    @Schema(description = "Price charged for one unit sold, at the time of sale", example = "18.00")
    private BigDecimal unitPrice;
    @Schema(description = "unitPrice * uomQuantity (or qtyBase when no uom)", example = "18.00")
    private BigDecimal lineTotal;
    @Schema(description = "What the variant's effective price for this unit was at the time of sale; " +
            "null when no price was on file", example = "20.00")
    private BigDecimal catalogueUnitPrice;
    @Schema(description = "True when unitPrice was a till-side override that differs from catalogueUnitPrice")
    private boolean priceOverridden;
}
