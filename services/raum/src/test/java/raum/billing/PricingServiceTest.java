package raum.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import raum.exception.PricingConfigurationException;
import raum.models.BasePricing;
import raum.models.Credentials;
import raum.models.ExchangeRate;
import raum.models.ModulePricing;
import raum.models.Service;
import raum.repository.BasePricingRepository;
import raum.repository.CredentialsRepository;
import raum.repository.ExchangeRateRepository;
import raum.repository.ModulePricingRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private BasePricingRepository basePricingRepository;
    @Mock
    private ModulePricingRepository modulePricingRepository;
    @Mock
    private ExchangeRateRepository exchangeRateRepository;
    @Mock
    private CredentialsRepository credentialsRepository;
    @Mock
    private ServiceRepository serviceRepository;

    private PricingService pricingService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private void init() {
        pricingService = new PricingService(basePricingRepository, modulePricingRepository,
                exchangeRateRepository, credentialsRepository, serviceRepository);
    }

    private BasePricing base(BigDecimal price, String currency) {
        return BasePricing.builder().price(price).currency(currency).effectiveFrom(now.minusSeconds(60)).build();
    }

    @Test
    void calculateInvoice_baseOnly_noModulesProvisioned() {
        init();
        when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.just(base(new BigDecimal("100.00"), "USD")));
        when(credentialsRepository.findAllByOrgId(orgId)).thenReturn(Flux.empty());

        StepVerifier.create(pricingService.calculateInvoice(orgId, "USD", now))
                .assertNext(invoice -> {
                    assertThat(invoice.getAmount()).isEqualByComparingTo("100.00");
                    assertThat(invoice.getCurrency()).isEqualTo("USD");
                    assertThat(invoice.getLineItems()).hasSize(1);
                })
                .verifyComplete();
    }

    @Test
    void calculateInvoice_bundledModule_excludedFromTotal() {
        init();
        when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.just(base(new BigDecimal("100.00"), "USD")));
        when(credentialsRepository.findAllByOrgId(orgId))
                .thenReturn(Flux.just(Credentials.builder().orgId(orgId).serviceId(serviceId).build()));
        when(serviceRepository.findById(serviceId))
                .thenReturn(Mono.just(Service.builder().id(serviceId).name("Bime").build()));
        when(modulePricingRepository.findFirstByServiceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(eq(serviceId), any()))
                .thenReturn(Mono.just(ModulePricing.builder()
                        .serviceId(serviceId).price(new BigDecimal("50.00")).currency("USD")
                        .includedInBase(true).effectiveFrom(now.minusSeconds(60)).build()));

        StepVerifier.create(pricingService.calculateInvoice(orgId, "USD", now))
                .assertNext(invoice -> {
                    assertThat(invoice.getAmount()).isEqualByComparingTo("100.00");
                    assertThat(invoice.getLineItems()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void calculateInvoice_nonBundledModule_addedToTotal() {
        init();
        when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.just(base(new BigDecimal("100.00"), "USD")));
        when(credentialsRepository.findAllByOrgId(orgId))
                .thenReturn(Flux.just(Credentials.builder().orgId(orgId).serviceId(serviceId).build()));
        when(serviceRepository.findById(serviceId))
                .thenReturn(Mono.just(Service.builder().id(serviceId).name("Bime").build()));
        when(modulePricingRepository.findFirstByServiceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(eq(serviceId), any()))
                .thenReturn(Mono.just(ModulePricing.builder()
                        .serviceId(serviceId).price(new BigDecimal("25.00")).currency("USD")
                        .includedInBase(false).effectiveFrom(now.minusSeconds(60)).build()));

        StepVerifier.create(pricingService.calculateInvoice(orgId, "USD", now))
                .assertNext(invoice -> assertThat(invoice.getAmount()).isEqualByComparingTo("125.00"))
                .verifyComplete();
    }

    @Test
    void calculateInvoice_convertsToOrgCurrency() {
        init();
        when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.just(base(new BigDecimal("100.00"), "USD")));
        when(credentialsRepository.findAllByOrgId(orgId)).thenReturn(Flux.empty());
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                eq("USD"), eq("ARS"), any()))
                .thenReturn(Mono.just(ExchangeRate.builder()
                        .fromCurrency("USD").toCurrency("ARS").rate(new BigDecimal("1000"))
                        .effectiveFrom(now.minusSeconds(60)).build()));

        StepVerifier.create(pricingService.calculateInvoice(orgId, "ARS", now))
                .assertNext(invoice -> {
                    assertThat(invoice.getAmount()).isEqualByComparingTo("100000.00");
                    assertThat(invoice.getCurrency()).isEqualTo("ARS");
                })
                .verifyComplete();
    }

    @Test
    void calculateInvoice_missingExchangeRate_errors() {
        init();
        when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.just(base(new BigDecimal("100.00"), "USD")));
        when(credentialsRepository.findAllByOrgId(orgId)).thenReturn(Flux.empty());
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                eq("USD"), eq("EUR"), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(pricingService.calculateInvoice(orgId, "EUR", now))
                .expectError(PricingConfigurationException.class)
                .verify();
    }

    @Test
    void calculateInvoice_noBasePricingConfigured_errors() {
        init();
        when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(pricingService.calculateInvoice(orgId, "USD", now))
                .expectError(PricingConfigurationException.class)
                .verify();
    }

    @Test
    void getRate_sameCurrency_returnsOneWithoutLookup() {
        init();

        StepVerifier.create(pricingService.getRate("USD", "usd", now))
                .assertNext(rate -> assertThat(rate).isEqualByComparingTo(BigDecimal.ONE))
                .verifyComplete();
    }

    @Test
    void getRate_readsStoredExchangeRate() {
        init();
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                eq("USD"), eq("ARS"), any()))
                .thenReturn(Mono.just(ExchangeRate.builder()
                        .fromCurrency("USD").toCurrency("ARS").rate(new BigDecimal("1000"))
                        .effectiveFrom(now.minusSeconds(60)).build()));

        StepVerifier.create(pricingService.getRate("USD", "ARS", now))
                .assertNext(rate -> assertThat(rate).isEqualByComparingTo("1000"))
                .verifyComplete();
    }

    @Test
    void getRate_missingStoredRate_errors() {
        init();
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                eq("USD"), eq("EUR"), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(pricingService.getRate("USD", "EUR", now))
                .expectError(PricingConfigurationException.class)
                .verify();
    }
}
