package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "An organisation's billing currency, product pricing currency, and how its " +
        "exchange rates are kept fresh")
public class OrgCurrencyResponseDTO {
    String currency;
    String currencyRefreshMode;
    String productPricingCurrency;
}
