package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for adding an option to a metadata definition")
public class MetadataOptionRequestDTO {
    @Schema(description = "The option value — must be unique within the parent metadata definition", example = "Red")
    private String value;
    @Schema(description = "Short unique code for this option within its metadata definition, used as a SKU fragment. " +
            "If omitted, derived automatically from value (uppercased, non-alphanumeric characters stripped). " +
            "Required if value contains characters that cannot be represented in ASCII once uppercased " +
            "(e.g. accented letters, Cyrillic, CJK).", example = "RED")
    private String code;
}
