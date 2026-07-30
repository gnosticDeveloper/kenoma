package raum.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import raum.dto.ExchangeRateRequestDTO;
import raum.dto.ExchangeRateResponseDTO;
import raum.models.BasePricing;
import raum.models.CurrencyRefreshCadence;
import raum.models.CurrencyRefreshMode;
import raum.models.ExchangeRate;
import raum.models.Organization;
import raum.repository.BasePricingRepository;
import raum.repository.ExchangeRateRepository;
import raum.repository.OrganizationRepository;
import raum.services.PricingAdminService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxRateRefreshSchedulerTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private BasePricingRepository basePricingRepository;
    @Mock
    private ExchangeRateRepository exchangeRateRepository;
    @Mock
    private ExternalFxRateProvider externalFxRateProvider;
    @Mock
    private PricingAdminService pricingAdminService;

    private FxRateRefreshScheduler scheduler;

    private void init() {
        scheduler = new FxRateRefreshScheduler(organizationRepository, basePricingRepository,
                exchangeRateRepository, externalFxRateProvider, pricingAdminService);
        lenient().when(basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(any()))
                .thenReturn(Mono.just(BasePricing.builder().price(BigDecimal.TEN).currency("USD")
                        .effectiveFrom(Instant.now().minusSeconds(60)).build()));
        lenient().when(externalFxRateProvider.fetchRate(any(), any())).thenReturn(Mono.just(new BigDecimal("1.5")));
        lenient().when(pricingAdminService.addExchangeRate(any()))
                .thenReturn(Mono.just(new ExchangeRateResponseDTO(UUID.randomUUID(), "USD", "ARS",
                        new BigDecimal("1.5"), Instant.now(), Instant.now())));
    }

    private Organization org(String currency, String mode, CurrencyRefreshCadence cadence, Integer intervalDays) {
        return org(currency, null, mode, cadence, intervalDays);
    }

    private Organization org(String currency, String productPricingCurrency, String mode,
                              CurrencyRefreshCadence cadence, Integer intervalDays) {
        return Organization.builder()
                .id(UUID.randomUUID())
                .currency(currency)
                .productPricingCurrency(productPricingCurrency)
                .currencyRefreshMode(mode)
                .currencyRefreshCadence(cadence == null ? null : cadence.name())
                .currencyRefreshIntervalDays(intervalDays)
                .build();
    }

    private void stubNoStoredRate() {
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                any(), any(), any())).thenReturn(Mono.empty());
    }

    private void stubStoredRateAge(int daysAgo) {
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                eq("USD"), eq("ARS"), any()))
                .thenReturn(Mono.just(ExchangeRate.builder()
                        .fromCurrency("USD").toCurrency("ARS").rate(new BigDecimal("1.0"))
                        .effectiveFrom(Instant.now().minus(daysAgo, ChronoUnit.DAYS)).build()));
    }

    @Test
    void noStoredRate_alwaysDueRegardlessOfCadence() {
        init();
        stubNoStoredRate();
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("ARS", "PERIODIC", CurrencyRefreshCadence.MONTHLY, null)));

        scheduler.refreshRates();

        verify(pricingAdminService).addExchangeRate(any());
    }

    @Test
    void manualModeOrg_neverRefreshed() {
        init();
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("ARS", "MANUAL", CurrencyRefreshCadence.DAILY, null)));

        scheduler.refreshRates();

        verify(pricingAdminService, never()).addExchangeRate(any());
    }

    @Test
    void daily_dueOnlyWhenLastRateIsAtLeastOneDayOld() {
        init();
        stubStoredRateAge(0);
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("ARS", "PERIODIC", CurrencyRefreshCadence.DAILY, null)));

        scheduler.refreshRates();

        verify(pricingAdminService, never()).addExchangeRate(any());
    }

    @Test
    void weekly_notDueAtThreeDays_dueAtEightDays() {
        init();
        stubStoredRateAge(3);
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("ARS", "PERIODIC", CurrencyRefreshCadence.WEEKLY, null)));
        scheduler.refreshRates();
        verify(pricingAdminService, never()).addExchangeRate(any());

        stubStoredRateAge(8);
        scheduler.refreshRates();
        verify(pricingAdminService, times(1)).addExchangeRate(any());
    }

    @Test
    void everyNDays_respectsCustomInterval() {
        init();
        stubStoredRateAge(2);
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("ARS", "PERIODIC", CurrencyRefreshCadence.EVERY_N_DAYS, 3)));
        scheduler.refreshRates();
        verify(pricingAdminService, never()).addExchangeRate(any());

        stubStoredRateAge(4);
        scheduler.refreshRates();
        verify(pricingAdminService, times(1)).addExchangeRate(any());
    }

    @Test
    void monthly_dueOnlyOnFirstOfMonthAndNotAlreadyRefreshedThisMonth() {
        init();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean isFirstOfMonth = today.getDayOfMonth() == 1;

        // Last refresh was in a different month, e.g. 45 days ago, well outside this month.
        stubStoredRateAge(45);
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("ARS", "PERIODIC", CurrencyRefreshCadence.MONTHLY, null)));

        scheduler.refreshRates();

        if (isFirstOfMonth) {
            verify(pricingAdminService).addExchangeRate(any());
        } else {
            verify(pricingAdminService, never()).addExchangeRate(any());
        }
    }

    @Test
    void sharedCurrency_refreshedIfAnyOrgIsDueEvenIfOthersArent() {
        init();
        stubStoredRateAge(8); // due for WEEKLY, not due for a hypothetical 30-day interval
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.fromIterable(List.of(
                        org("ARS", "PERIODIC", CurrencyRefreshCadence.EVERY_N_DAYS, 30),
                        org("ARS", "PERIODIC", CurrencyRefreshCadence.WEEKLY, null))));

        scheduler.refreshRates();

        verify(pricingAdminService, times(1)).addExchangeRate(any());
    }

    @Test
    void productPricingCurrency_refreshedEvenWhenBillingCurrencyMatchesReference() {
        init();
        stubNoStoredRate();
        // Billing currency (USD) equals the reference currency, so that pair is a no-op -
        // only productPricingCurrency (ARS) should trigger a refresh.
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("USD", "ARS", "PERIODIC", CurrencyRefreshCadence.DAILY, null)));

        scheduler.refreshRates();

        ArgumentCaptor<ExchangeRateRequestDTO> captor = ArgumentCaptor.forClass(ExchangeRateRequestDTO.class);
        verify(pricingAdminService, times(1)).addExchangeRate(captor.capture());
        assertThat(captor.getValue().getFromCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().getToCurrency()).isEqualTo("ARS");
    }

    @Test
    void billingAndProductPricingCurrencies_bothRefreshedWhenDistinct() {
        init();
        stubNoStoredRate();
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.just(org("EUR", "ARS", "PERIODIC", CurrencyRefreshCadence.DAILY, null)));

        scheduler.refreshRates();

        ArgumentCaptor<ExchangeRateRequestDTO> captor = ArgumentCaptor.forClass(ExchangeRateRequestDTO.class);
        verify(pricingAdminService, times(2)).addExchangeRate(captor.capture());
        List<String> targets = captor.getAllValues().stream().map(ExchangeRateRequestDTO::getToCurrency).toList();
        assertThat(targets).containsExactlyInAnyOrder("EUR", "ARS");
    }

    @Test
    void failureForOneCurrencyPair_doesNotBlockAnother() {
        init();
        stubNoStoredRate();
        when(organizationRepository.findAllByStoppedAtIsNull())
                .thenReturn(Flux.fromIterable(List.of(
                        org("ARS", "PERIODIC", CurrencyRefreshCadence.DAILY, null),
                        org("EUR", "PERIODIC", CurrencyRefreshCadence.DAILY, null))));
        when(externalFxRateProvider.fetchRate("USD", "ARS")).thenReturn(Mono.error(new RuntimeException("provider down")));
        when(externalFxRateProvider.fetchRate("USD", "EUR")).thenReturn(Mono.just(new BigDecimal("0.9")));

        scheduler.refreshRates();

        ArgumentCaptor<ExchangeRateRequestDTO> captor = ArgumentCaptor.forClass(ExchangeRateRequestDTO.class);
        verify(pricingAdminService, times(1)).addExchangeRate(captor.capture());
        assertThat(captor.getValue().getFromCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().getToCurrency()).isEqualTo("EUR");
    }
}
