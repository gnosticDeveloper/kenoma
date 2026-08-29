package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "Request body for setting a variant's stock alert threshold at a location")
public class StockAlertThresholdRequestDTO {
    private UUID variantId;
    private UUID locationId;
    @Schema(description = "Alert fires the moment on-hand quantity at this location drops to or below this value", example = "10")
    private BigDecimal threshold;
}
