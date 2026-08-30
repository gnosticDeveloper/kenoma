package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for changing which of a variant's barcodes is primary")
public class VariantBarcodePrimaryRequestDTO {
    @Schema(description = "true makes this barcode the variant's primary (clearing any other); false just clears it")
    private Boolean isPrimary;
}
