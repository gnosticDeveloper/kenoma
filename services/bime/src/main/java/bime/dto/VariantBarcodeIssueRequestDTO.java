package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for issuing a new internal barcode for a variant. The value is generated " +
        "server-side as a 13-digit EAN from the org's barcode settings (its GS1 company prefix, or the " +
        "restricted-distribution range when none is configured)")
public class VariantBarcodeIssueRequestDTO {
    @Schema(description = "The unit of measure to issue the barcode for - the variant's base unit, or one of its configured " +
            "pack sizes (e.g. \"case\"). Omit for the base unit.", example = "case")
    private String uom;
    @Schema(description = "Make the newly issued barcode the primary for its unit, replacing any current primary for that unit. Defaults to false")
    private Boolean isPrimary;
}
