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
@Schema(description = "An effective-dated base price entry")
public class BasePricingResponseDTO {
    UUID id;
    BigDecimal price;
    String currency;
    Instant effectiveFrom;
    Instant createdAt;
}
