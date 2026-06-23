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
public class StockBalanceResponseDTO {
    private UUID orgId;
    private UUID productId;
    private UUID locationId;
    private int quantity;
    private LocalDateTime modifiedAt;
}
