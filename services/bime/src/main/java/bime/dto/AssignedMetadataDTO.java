package bime.dto;

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
public class AssignedMetadataDTO {
    private UUID metadataId;
    private String metadataName;
    private List<MetadataOptionResponseDTO> selectedOptions;
}
