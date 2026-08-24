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
    @Schema(description = "SQL, JSON or CSV")
    String format;
    @Schema(description = "SEPARATE (one file per service) or MERGED (one combined file) - only meaningful for JSON/CSV")
    String layout;
    Instant requestedAt;
    Instant startedAt;
    Instant completedAt;
    String errorMessage;
}
