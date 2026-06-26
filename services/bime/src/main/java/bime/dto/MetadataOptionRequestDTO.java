package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for adding an option to a metadata definition")
public class MetadataOptionRequestDTO {
    @Schema(description = "The option value — must be unique within the parent metadata definition", example = "Red")
    private String value;
}
