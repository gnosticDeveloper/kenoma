package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An effective-dated exchange rate entry")
public class ExchangeRateResponseDTO {
    UUID id;
    String fromCurrency;
    String toCurrency;
    BigDecimal rate;
    Instant effectiveFrom;
    Instant createdAt;
}
