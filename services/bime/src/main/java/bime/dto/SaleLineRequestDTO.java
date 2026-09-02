package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "One item scanned into a sale. Identify the variant either by barcode (the usual " +
        "point-of-sale path) or by variantId. When a barcode is given it also fixes the unit sold - " +
        "its base unit or the pack size it was issued for - and uom is ignored")
public class SaleLineRequestDTO {
    @Schema(description = "The scanned barcode. Resolves the variant and the unit sold. Either this or variantId is required")
    private String barcode;
    @Schema(description = "The variant sold, when not scanning a barcode. Either this or barcode is required")
    private UUID variantId;
    @Schema(description = "Quantity sold, in uom (or the barcode's unit) if given, otherwise in the variant's base unit. Must be positive", example = "2")
    private BigDecimal quantity;
    @Schema(description = "Unit of measure the quantity is in (e.g. \"case\"). Must be a unit configured for the variant. " +
            "Ignored when a barcode is given. When omitted, quantity is in the base unit")
    private String uom;
    @Schema(description = "Till-side price override for one unit sold. When omitted, the variant's effective price for that unit is used")
    private BigDecimal unitPrice;
}
