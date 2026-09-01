package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "One production lot moving on a batch-tracked transfer line, with its progress through dispatch and receipt")
public class StockTransferLineBatchDTO {
    private UUID batchId;
    private String batchCode;
    private LocalDate expiryDate;
    @Schema(description = "Lot state at the source: ACTIVE or RECALLED")
    private String status;
    @Schema(description = "Quantity of this lot that left the source, in the variant's base unit", example = "48")
    private BigDecimal qtyDispatched;
    @Schema(description = "Quantity of this lot accepted at the destination so far, in the base unit", example = "48")
    private BigDecimal qtyReceived;
    @Schema(description = "Quantity of this lot dispatched but not yet received, in the base unit", example = "0")
    private BigDecimal qtyInTransit;
}
