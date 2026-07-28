package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "A new effective-dated price for a module (service). Inserted as a new row, never overwrites history")
public class ModulePricingRequestDTO {
    UUID serviceId;
    BigDecimal price;
    String currency;
    @Schema(description = "True while this module's cost is bundled into the base price rather than billed separately")
    boolean includedInBase;
    Instant effectiveFrom;
}
