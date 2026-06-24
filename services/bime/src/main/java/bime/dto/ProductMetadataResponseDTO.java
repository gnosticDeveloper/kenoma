package bime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductMetadataResponseDTO {
    private UUID id;
    private UUID orgId;
    private String name;
    private List<MetadataOptionResponseDTO> options;
    private LocalDateTime createdAt;
}
