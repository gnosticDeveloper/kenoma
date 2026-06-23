package bime.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class StockMovementResponseDTO {
    private UUID id;
    private UUID orgId;
    private UUID productId;
    private UUID locationId;
    private String movementType;
    private int delta;
    private UUID referenceId;
    private String note;
    private LocalDateTime createdAt;
    private UUID createdBy;
}
