package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Request body for recording a stock movement")
public class StockMovementRequestDTO {
    private UUID variantId;
    private UUID locationId;
    private MovementType movementType;
    @Schema(description = "Quantity change to apply. Must be positive for INBOUND, negative for OUTBOUND, and may be positive or negative for ADJUSTMENT",
            example = "10")
    private int delta;
    @Schema(description = "Optional external reference (e.g. purchase order ID or transfer ID) for traceability. This is currently not implemented and can safely be ignored")
    private UUID referenceId;
    @Schema(description = "Optional free-text note for human-readable context", example = "Received from supplier PO-2026-042")
    private String note;
}
