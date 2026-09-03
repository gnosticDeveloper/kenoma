package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Quantity of a variant currently dispatched but not yet received, heading to a destination location")
public class InTransitStockDTO {
    private UUID variantId;
    @Schema(description = "Destination location the stock is heading to")
    private UUID destLocationId;
    @Schema(description = "Total quantity in transit toward this location, in the variant's base unit", example = "6")
    private BigDecimal quantity;
}
