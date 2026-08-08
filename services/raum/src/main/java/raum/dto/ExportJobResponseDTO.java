package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A tenant data export job for an organization's offboarding")
public class ExportJobResponseDTO {
    UUID id;
    UUID orgId;
    @Schema(description = "PENDING, RUNNING, DONE or FAILED")
    String status;
    Instant requestedAt;
    Instant startedAt;
    Instant completedAt;
    String errorMessage;
}
