package bime.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MetadataOptionPatchDTO {
    private List<UUID> add;
    private List<UUID> remove;
}
