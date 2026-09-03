package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Immutable record of a single stock movement")
public class StockMovementResponseDTO {
    private UUID id;
    private UUID orgId;
    private UUID productId;
    private UUID variantId;
    private UUID locationId;
    private MovementType movementType;
    @Schema(description = "Lifecycle state. Only POSTED movements are reflected in on-hand balances; PENDING movements are recorded but not yet applied; CANCELLED movements were never applied")
    private MovementStatus status;
    @Schema(description = "Net quantity change applied, in the variant's base unit. Positive values increase stock, negative values decrease it", example = "10")
    private BigDecimal delta;
    @Schema(description = "Unit of measure the movement was originally recorded in, if different from the base unit. Null when delta was already in the base unit")
    private String uom;
    @Schema(description = "Quantity as originally entered, in uom. Null when uom is null - in that case delta already reflects what was entered")
    private BigDecimal uomQuantity;
    @Schema(description = "Optional external reference for traceability. On TRANSFER_OUT / TRANSFER_IN movements this is the originating transfer order's ID")
    private UUID referenceId;
    @Schema(description = "Optional free-text note supplied at creation time")
    private String note;
    private LocalDateTime createdAt;
    @Schema(description = "ID of the user who recorded this movement. Derived from the JWT at creation time")
    private UUID createdBy;
    @Schema(description = "The batch this movement drew from or added to. Null for movements of non-batch-tracked variants")
    private UUID batchId;
    @Schema(description = "Set only on the aggregate result of a FEFO outbound that was split across several batches: the individual " +
            "per-batch movement rows that were written. Null for a single-batch or non-batch movement")
    private List<StockMovementResponseDTO> allocations;
}
