package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for updating the org's batch expiry-alert settings")
public class OrgBatchSettingsRequestDTO {
    @Schema(description = "How many days before a batch's expiry date the daily sweep starts sending near-expiry alerts. Must be positive", example = "30")
    private Integer nearExpiryDays;
}
