package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for linking an existing (provider) barcode to a variant")
public class VariantBarcodeRequestDTO {
    @Schema(description = "The barcode value exactly as printed/scanned. For EAN13/UPC_A/EAN8 this is the digit string " +
            "including its check digit", example = "5012345678900")
    private String barcode;
    @Schema(description = "How the barcode is encoded. EAN13/UPC_A/EAN8 are check-digit validated; CODE128/CODE39 are stored as-is")
    private BarcodeSymbology symbology;
    @Schema(description = "The unit of measure this barcode identifies - the variant's base unit, or one of its configured " +
            "pack sizes (e.g. \"case\"). Omit for the base unit. A pack-size scan resolves to that pack's quantity multiplier at point of sale",
            example = "case")
    private String uom;
    @Schema(description = "Make this the primary barcode for its unit, replacing any current primary for that unit. Defaults to false")
    private Boolean isPrimary;
}
