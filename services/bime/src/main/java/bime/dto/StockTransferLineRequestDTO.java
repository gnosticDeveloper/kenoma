package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "One variant and quantity to move within a transfer order")
public class StockTransferLineRequestDTO {
    private UUID variantId;
    @Schema(description = "Quantity to move, in uom if given, otherwise in the variant's base unit. Must be positive", example = "12")
    private BigDecimal quantity;
    @Schema(description = "Optional unit of measure this quantity is expressed in (e.g. \"case\"). Must be a unit configured for the variant. When omitted, quantity is in the base unit")
    private String uom;
}
