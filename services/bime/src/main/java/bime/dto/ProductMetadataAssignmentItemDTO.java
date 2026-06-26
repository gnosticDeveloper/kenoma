package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "One metadata assignment item — links a metadata definition to a set of selected options for a product")
public class ProductMetadataAssignmentItemDTO {
    @Schema(description = "Identifier of the metadata definition to assign (e.g. the 'Colour' definition)")
    private UUID metadataId;
    @Schema(description = "Option IDs to select for this metadata on the product. Replaces any previously selected options for this metadata")
    private List<UUID> optionIds;
}
