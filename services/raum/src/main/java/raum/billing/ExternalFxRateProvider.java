package raum.billing;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public interface ExternalFxRateProvider {
    Mono<BigDecimal> fetchRate(String fromCurrency, String toCurrency);
}
