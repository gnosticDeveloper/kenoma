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
@Schema(description = "Aggregated on-hand stock balance for a variant at a location")
public class StockBalanceResponseDTO {
    private UUID orgId;
    private UUID variantId;
    private UUID locationId;
    @Schema(description = "Net on-hand quantity — the running sum of all recorded movement deltas for this variant and location", example = "42")
    private BigDecimal quantity;
    @Schema(description = "Timestamp of the last movement that affected this balance")
    private LocalDateTime modifiedAt;
}
