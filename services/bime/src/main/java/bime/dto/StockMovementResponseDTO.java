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
@Schema(description = "Immutable record of a single stock movement")
public class StockMovementResponseDTO {
    private UUID id;
    private UUID orgId;
    private UUID productId;
    private UUID variantId;
    private UUID locationId;
    private MovementType movementType;
    @Schema(description = "Net quantity change applied, in the variant's base unit. Positive values increase stock, negative values decrease it", example = "10")
    private BigDecimal delta;
    @Schema(description = "Unit of measure the movement was originally recorded in, if different from the base unit. Null when delta was already in the base unit")
    private String uom;
    @Schema(description = "Quantity as originally entered, in uom. Null when uom is null - in that case delta already reflects what was entered")
    private BigDecimal uomQuantity;
    @Schema(description = "Optional external reference (e.g. purchase order ID or transfer ID) for traceability. This is currently not implemented and can safely be ignored")
    private UUID referenceId;
    @Schema(description = "Optional free-text note supplied at creation time")
    private String note;
    private LocalDateTime createdAt;
    @Schema(description = "ID of the user who recorded this movement. Derived from the JWT at creation time")
    private UUID createdBy;
}
