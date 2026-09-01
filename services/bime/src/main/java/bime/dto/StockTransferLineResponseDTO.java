package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "One line of a transfer order, with its progress through dispatch and receipt")
public class StockTransferLineResponseDTO {
    private UUID id;
    private UUID variantId;
    private UUID sourceLocationId;
    private UUID destLocationId;
    @Schema(description = "Quantity the transfer asks to move, in the variant's base unit", example = "12")
    private BigDecimal qtyRequested;
    @Schema(description = "Quantity that actually left the source on dispatch, in the base unit. Zero until dispatched", example = "12")
    private BigDecimal qtyDispatched;
    @Schema(description = "Total quantity accepted at the destination so far, in the base unit", example = "10")
    private BigDecimal qtyReceived;
    @Schema(description = "Quantity dispatched but not yet received, in the base unit. Zero unless the transfer is in transit", example = "2")
    private BigDecimal qtyInTransit;
    @Schema(description = "Unit of measure the line was entered in, if not the base unit")
    private String uom;
    @Schema(description = "Quantity as entered, in uom. Null when uom is null")
    private BigDecimal uomQuantity;
    @Schema(description = "Per-lot breakdown for a batch-tracked line. Empty for lines whose product does not track batches")
    private List<StockTransferLineBatchDTO> batches;
}
