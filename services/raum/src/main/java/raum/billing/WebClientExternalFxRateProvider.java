package raum.billing;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import raum.exception.PricingConfigurationException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * exchangerate-api.com's "pair conversion" endpoint: GET {base-url}/{api-key}/pair/{from}/{to}.
 * https://www.exchangerate-api.com/docs/pair-conversion-requests
 */
@Service
public class WebClientExternalFxRateProvider implements ExternalFxRateProvider {

    private final WebClient webClient;
    private final String apiKey;

    public WebClientExternalFxRateProvider(
            @Value("${raum.fx-provider.base-url:https://v6.exchangerate-api.com/v6}") String baseUrl,
            @Value("${raum.fx-provider.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public Mono<BigDecimal> fetchRate(String fromCurrency, String toCurrency) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new PricingConfigurationException(
                    "No external FX provider configured (raum.fx-provider.api-key is unset)"));
        }
        return webClient.get()
                .uri("/{apiKey}/pair/{from}/{to}", apiKey, fromCurrency, toCurrency)
                .retrieve()
                .bodyToMono(ExchangeRateApiResponse.class)
                .flatMap(response -> "success".equals(response.result())
                        ? Mono.just(response.conversionRate())
                        : Mono.error(new PricingConfigurationException(
                                "exchangerate-api.com returned an error converting %s -> %s: %s"
                                        .formatted(fromCurrency, toCurrency, response.result()))));
    }

    private record ExchangeRateApiResponse(
            String result,
            @JsonProperty("conversion_rate") BigDecimal conversionRate) {
    }
}
