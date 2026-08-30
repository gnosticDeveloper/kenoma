package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "Actual quantity accepted at the destination for one transfer line")
public class StockTransferReceiveLineDTO {
    private UUID lineId;
    @Schema(description = "Quantity actually received, in uom if given, otherwise in the variant's base unit. " +
            "Zero is allowed (nothing accepted for this line yet). May not exceed the line's outstanding in-transit quantity", example = "10")
    private BigDecimal qtyReceived;
    @Schema(description = "Optional unit of measure qtyReceived is expressed in. When omitted, it is in the base unit")
    private String uom;
}
