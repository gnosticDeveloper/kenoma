package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Product metadata definition with its available options")
public class ProductMetadataResponseDTO {
    private UUID id;
    private UUID orgId;
    private String name;
    @Schema(description = "All selectable options for this metadata attribute (e.g. Red, Blue, Green for 'Color')")
    private List<MetadataOptionResponseDTO> options;
    private LocalDateTime createdAt;
}
