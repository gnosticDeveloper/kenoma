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
@Schema(description = "An effective-dated price entry for a module (service)")
public class ModulePricingResponseDTO {
    UUID id;
    UUID serviceId;
    String serviceName;
    BigDecimal price;
    String currency;
    boolean includedInBase;
    Instant effectiveFrom;
    Instant createdAt;
}
