package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Invoicing data for an organization. Fields are unvalidated beyond format (e.g. tax ID scheme varies by country)")
public class BillingInfoRequestDTO {
    String taxId;
    String fiscalName;
    String fiscalAddress;
    @Schema(description = "One of MONTHLY, QUARTERLY, ANNUAL")
    String billingCycle;
    Instant nextInvoiceDueAt;
}
