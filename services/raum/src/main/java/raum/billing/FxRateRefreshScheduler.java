package raum.billing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import raum.dto.ExchangeRateRequestDTO;
import raum.models.CurrencyRefreshCadence;
import raum.models.CurrencyRefreshMode;
import raum.models.Organization;
import raum.repository.BasePricingRepository;
import raum.repository.ExchangeRateRepository;
import raum.repository.OrganizationRepository;
import raum.services.PricingAdminService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps exchange_rates fresh so reads (via {@link PricingService#getRate}) never need a live
 * external call. Only considers PERIODIC-mode orgs (MANUAL orgs are curated by hand via
 * POST /pricing/exchange-rates and are never touched here). Runs once a day; for each
 * reference-currency -> target-currency pair (target being an org's billing currency and/or its
 * product pricing currency - Bime's price conversions rely on the latter, invoicing on the
 * former), refreshes it only if at least one org using that currency is "due" per its own
 * cadence (DAILY/WEEKLY/EVERY_N_DAYS/MONTHLY) - a pair shared by orgs with different cadences
 * gets refreshed as often as the most demanding one needs, which only ever makes the others'
 * rates fresher than they asked for, never staler.
 */
@Slf4j
@Component
public class FxRateRefreshScheduler {

    private final OrganizationRepository organizationRepository;
    private final BasePricingRepository basePricingRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ExternalFxRateProvider externalFxRateProvider;
    private final PricingAdminService pricingAdminService;

    public FxRateRefreshScheduler(OrganizationRepository organizationRepository,
                                   BasePricingRepository basePricingRepository,
                                   ExchangeRateRepository exchangeRateRepository,
                                   ExternalFxRateProvider externalFxRateProvider,
                                   PricingAdminService pricingAdminService) {
        this.organizationRepository = organizationRepository;
        this.basePricingRepository = basePricingRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.externalFxRateProvider = externalFxRateProvider;
        this.pricingAdminService = pricingAdminService;
    }

    @Scheduled(cron = "${raum.billing.fx-refresh-cron:0 0 5 * * *}")
    public void refreshRates() {
        Instant now = Instant.now();
        basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(now)
                .map(base -> base.getCurrency().toUpperCase())
                .flatMapMany(reference -> periodicOrgsByCurrency(reference)
                        .flatMap(entry -> refreshIfDue(reference, entry.getKey(), entry.getValue(), now)
                                .onErrorResume(e -> {
                                    log.error("Failed to refresh FX rate {} -> {}", reference, entry.getKey(), e);
                                    return Mono.empty();
                                })))
                .then()
                .subscribe(null, e -> log.error("FX rate refresh failed", e));
    }

    private Flux<Map.Entry<String, List<Organization>>> periodicOrgsByCurrency(String reference) {
        return organizationRepository.findAllByStoppedAtIsNull()
                .filter(org -> CurrencyRefreshMode.PERIODIC.name().equalsIgnoreCase(org.getCurrencyRefreshMode()))
                .flatMap(org -> Flux.fromIterable(targetCurrencies(org, reference))
                        .map(currency -> new CurrencyOrgPair(currency, org)))
                .collectMultimap(CurrencyOrgPair::currency, CurrencyOrgPair::org)
                .flatMapMany(byCurrency -> Flux.fromIterable(byCurrency.entrySet())
                        .map(e -> Map.entry(e.getKey(), List.copyOf(e.getValue()))));
    }

    private static Set<String> targetCurrencies(Organization org, String reference) {
        Set<String> currencies = new LinkedHashSet<>();
        if (org.getCurrency() != null && !org.getCurrency().equalsIgnoreCase(reference)) {
            currencies.add(org.getCurrency().toUpperCase());
        }
        if (org.getProductPricingCurrency() != null && !org.getProductPricingCurrency().equalsIgnoreCase(reference)) {
            currencies.add(org.getProductPricingCurrency().toUpperCase());
        }
        return currencies;
    }

    private record CurrencyOrgPair(String currency, Organization org) {}

    private Mono<Void> refreshIfDue(String reference, String target, List<Organization> orgs, Instant now) {
        return exchangeRateRepository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        reference, target, now)
                .map(rate -> orgs.stream().anyMatch(org -> isDue(org, rate.getEffectiveFrom(), now)))
                .defaultIfEmpty(true) // no rate stored yet at all - always due
                .flatMap(due -> due ? refreshPair(reference, target) : Mono.empty());
    }

    private boolean isDue(Organization org, Instant lastEffectiveFrom, Instant now) {
        CurrencyRefreshCadence cadence = org.getCurrencyRefreshCadence() == null
                ? CurrencyRefreshCadence.WEEKLY
                : CurrencyRefreshCadence.valueOf(org.getCurrencyRefreshCadence().toUpperCase());

        if (cadence == CurrencyRefreshCadence.MONTHLY) {
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            LocalDate lastRefresh = LocalDate.ofInstant(lastEffectiveFrom, ZoneOffset.UTC);
            return today.getDayOfMonth() == 1
                    && (today.getYear() != lastRefresh.getYear() || today.getMonthValue() != lastRefresh.getMonthValue());
        }

        int intervalDays = switch (cadence) {
            case DAILY -> 1;
            case WEEKLY -> 7;
            case EVERY_N_DAYS -> org.getCurrencyRefreshIntervalDays() == null ? 1 : org.getCurrencyRefreshIntervalDays();
            case MONTHLY -> throw new IllegalStateException("handled above");
        };
        return ChronoUnit.DAYS.between(lastEffectiveFrom, now) >= intervalDays;
    }

    private Mono<Void> refreshPair(String from, String to) {
        return externalFxRateProvider.fetchRate(from, to)
                .flatMap(rate -> pricingAdminService.addExchangeRate(
                        new ExchangeRateRequestDTO(from, to, rate, Instant.now())))
                .then();
    }
}
