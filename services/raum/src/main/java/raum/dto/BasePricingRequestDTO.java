package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A new effective-dated base price. Inserted as a new row, never overwrites history")
public class BasePricingRequestDTO {
    BigDecimal price;
    String currency;
    @Schema(description = "When this price takes effect; defaults to now if omitted")
    Instant effectiveFrom;
}
