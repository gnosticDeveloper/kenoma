package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Explicit confirmation required to trigger a destructive DR restore")
public class RestoreRequestDTO {
    @Schema(description = "Must be true - a safety guard against triggering a destructive restore by accident")
    boolean confirm;
}
