package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "Request body for recording a stock movement")
public class StockMovementRequestDTO {
    private UUID variantId;
    private UUID locationId;
    private MovementType movementType;
    @Schema(description = "Quantity change to apply, in uom if given, otherwise in the variant's base unit. Must be positive for INBOUND, " +
            "negative for OUTBOUND, and may be positive or negative for ADJUSTMENT. Supports up to 3 decimal places for stock sold by weight/length/volume",
            example = "10")
    private BigDecimal delta;
    @Schema(description = "Optional unit of measure this delta is expressed in (e.g. \"case\"), if different from the variant's base unit. " +
            "Must be a unit configured via /variants/{variantId}/uom-conversions. When omitted, delta is interpreted directly in the base unit")
    private String uom;
    @Schema(description = "Optional lifecycle state. POSTED (the default) applies the delta to on-hand stock immediately. " +
            "PENDING records the movement without affecting stock; it can later be posted or cancelled. CANCELLED is not accepted here")
    private MovementStatus status;
    @Schema(description = "Optional external reference (e.g. a purchase order ID) for traceability. Transfer-order movements set this automatically to the transfer's ID")
    private UUID referenceId;
    @Schema(description = "Optional free-text note for human-readable context", example = "Received from supplier PO-2026-042")
    private String note;
}
