package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A currently active low-stock alert for a variant at a location")
public class StockAlertResponseDTO {
    private UUID orgId;
    private UUID variantId;
    private UUID locationId;
    private int threshold;
    @Schema(description = "On-hand quantity at the moment this alert was triggered")
    private int quantity;
    private LocalDateTime triggeredAt;
}
