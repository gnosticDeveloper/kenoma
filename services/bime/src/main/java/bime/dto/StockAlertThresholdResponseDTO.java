package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A variant's configured stock alert threshold at a location")
public class StockAlertThresholdResponseDTO {
    private UUID orgId;
    private UUID variantId;
    private UUID locationId;
    private BigDecimal threshold;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;
}
