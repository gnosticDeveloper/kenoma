package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for updating the org's barcode issuance settings")
public class OrgBarcodeSettingsRequestDTO {
    @Schema(description = "The org's GS1 company prefix (digits only, 4-11 long). When set, issued barcodes are real, " +
            "externally valid GTINs built from this prefix. Send null or empty to clear it and fall back to the " +
            "restricted-distribution range (in-store use only)", example = "5012345")
    private String gs1Prefix;
}
