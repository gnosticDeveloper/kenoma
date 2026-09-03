package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "On-hand quantity of a batch at one location, in the variant's base unit")
public class BatchLocationBalanceDTO {
    private UUID locationId;
    private String locationName;
    private BigDecimal quantity;
}
