package bime.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ProductMetadataAssignmentItemDTO {
    private UUID metadataId;
    private List<UUID> optionIds;
}
