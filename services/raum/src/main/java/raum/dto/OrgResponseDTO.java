package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Organization details")
public class OrgResponseDTO {
    UUID id;
    String name;
    String contactEmail;
    String taxId;
    String fiscalName;
    String fiscalAddress;
    String billingEmail;
    boolean billingEmailVerified;
    String billingCycle;
    Instant nextInvoiceDueAt;
    String currency;
    String currencyRefreshMode;
    String currencyRefreshCadence;
    Integer currencyRefreshIntervalDays;
    String productPricingCurrency;
}
