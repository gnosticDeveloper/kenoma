package bime.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class StockMovementRequestDTO {
    private UUID variantId;
    private UUID locationId;
    private String movementType;
    private int delta;
    private UUID referenceId;
    private String note;
}
