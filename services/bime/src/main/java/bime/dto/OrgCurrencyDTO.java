package bime.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrgCurrencyDTO {
    String currency;
    String currencyRefreshMode;
    String productPricingCurrency;
}
