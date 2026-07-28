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
@Schema(description = "A new effective-dated exchange rate. Inserted as a new row, never overwrites history")
public class ExchangeRateRequestDTO {
    String fromCurrency;
    String toCurrency;
    BigDecimal rate;
    Instant effectiveFrom;
}
