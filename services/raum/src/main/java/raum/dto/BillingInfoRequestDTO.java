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
    @Schema(description = "ISO 4217 currency code the organization is invoiced in")
    String currency;
    @Schema(description = "One of MANUAL, PERIODIC - controls whether exchange rates used for this org's pricing conversions are curated by hand or refreshed automatically")
    String currencyRefreshMode;
    @Schema(description = "One of DAILY, WEEKLY, EVERY_N_DAYS, MONTHLY - only meaningful when currencyRefreshMode is PERIODIC. MONTHLY refreshes on the 1st of each month")
    String currencyRefreshCadence;
    @Schema(description = "Required and must be a positive integer when currencyRefreshCadence is EVERY_N_DAYS; ignored otherwise")
    Integer currencyRefreshIntervalDays;
    @Schema(description = "ISO 4217 currency code the org prices its own catalog/inventory in (Bime). " +
            "Independent of `currency` above, which is what Kenoma invoices the org's own subscription in - " +
            "an org can supply-cost and price its catalog in USD while being billed by Kenoma in ARS, or vice versa.")
    String productPricingCurrency;
}
