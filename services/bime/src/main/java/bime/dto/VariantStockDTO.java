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
@Schema(description = "Current stock balance for a variant at a single location")
public class VariantStockDTO {
    private UUID locationId;
    @Schema(description = "Net on-hand quantity. The sum of all recorded movements (inbound minus outbound) at this location")
    private int quantity;
    @Schema(description = "Timestamp of the last stock movement that affected this balance")
    private LocalDateTime modifiedAt;
}
