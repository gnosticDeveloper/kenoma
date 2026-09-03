package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A single selectable option within a metadata definition")
public class MetadataOptionResponseDTO {
    private UUID id;
    @Schema(description = "Identifier of the parent metadata definition this option belongs to")
    private UUID metadataId;
    private String value;
    @Schema(description = "Short unique code for this option within its metadata definition, used as a SKU fragment", example = "RED")
    private String code;
    private LocalDateTime createdAt;
}
