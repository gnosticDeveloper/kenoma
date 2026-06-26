package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Patch body for adding or removing selected metadata options from a product-metadata assignment")
public class MetadataOptionPatchDTO {
    @Schema(description = "Option IDs to add to the current selection for this product-metadata pair")
    private List<UUID> add;
    @Schema(description = "Option IDs to remove from the current selection for this product-metadata pair")
    private List<UUID> remove;
}
