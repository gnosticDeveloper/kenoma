package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A metadata attribute that has been assigned to a product, along with the options selected for that product")
public class AssignedMetadataDTO {
    private UUID metadataId;
    private String metadataName;
    private List<MetadataOptionResponseDTO> selectedOptions;
}
