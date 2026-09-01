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
@Schema(description = "The org's batch expiry-alert settings")
public class OrgBatchSettingsResponseDTO {
    private UUID orgId;
    @Schema(description = "Days before expiry that near-expiry alerts start being sent")
    private int nearExpiryDays;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
}
