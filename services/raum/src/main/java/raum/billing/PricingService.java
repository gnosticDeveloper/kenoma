package raum.billing;

import raum.exception.PricingConfigurationException;
import raum.models.BasePricing;
import raum.models.ModulePricing;
import raum.repository.CredentialsRepository;
import raum.repository.BasePricingRepository;
import raum.repository.ExchangeRateRepository;
import raum.repository.ModulePricingRepository;
import raum.repository.ServiceRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PricingService {

    private final BasePricingRepository basePricingRepository;
    private final ModulePricingRepository modulePricingRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final CredentialsRepository credentialsRepository;
    private final ServiceRepository serviceRepository;

    public PricingService(BasePricingRepository basePricingRepository,
                           ModulePricingRepository modulePricingRepository,
                           ExchangeRateRepository exchangeRateRepository,
                           CredentialsRepository credentialsRepository,
                           ServiceRepository serviceRepository) {
        this.basePricingRepository = basePricingRepository;
        this.modulePricingRepository = modulePricingRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.credentialsRepository = credentialsRepository;
        this.serviceRepository = serviceRepository;
    }

    public Mono<InvoiceCalculation> calculateInvoice(UUID orgId, String orgCurrency, Instant now) {
        return basePricingRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(now)
                .switchIfEmpty(Mono.error(new PricingConfigurationException("No base pricing configured")))
                .flatMap(base -> moduleLineItems(orgId, now)
                        .collectList()
                        .flatMap(moduleLineItems -> buildInvoice(base, moduleLineItems, orgCurrency, now)));
    }

    private Flux<InvoiceLineItem> moduleLineItems(UUID orgId, Instant now) {
        return credentialsRepository.findAllByOrgId(orgId)
                .flatMap(credentials -> Mono.zip(
                        serviceRepository.findById(credentials.getServiceId()),
                        modulePricingRepository.findFirstByServiceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                credentials.getServiceId(), now)
                                .switchIfEmpty(Mono.error(new PricingConfigurationException(
                                        "No module pricing configured for service " + credentials.getServiceId())))
                ))
                .map(tuple -> InvoiceLineItem.builder()
                        .label(tuple.getT1().getName())
                        .price(tuple.getT2().getPrice())
                        .currency(tuple.getT2().getCurrency())
                        .includedInBase(tuple.getT2().isIncludedInBase())
                        .build());
    }

    private Mono<InvoiceCalculation> buildInvoice(BasePricing base, List<InvoiceLineItem> moduleLineItems,
                                                   String orgCurrency, Instant now) {
        String referenceCurrency = base.getCurrency().toUpperCase();
        String targetCurrency = orgCurrency == null || orgCurrency.isBlank()
                ? referenceCurrency : orgCurrency.toUpperCase();

        InvoiceLineItem baseLineItem = InvoiceLineItem.builder()
                .label("Base")
                .price(base.getPrice())
                .currency(base.getCurrency())
                .includedInBase(false)
                .build();

        List<InvoiceLineItem> allLineItems = new ArrayList<>(moduleLineItems.size() + 1);
        allLineItems.add(baseLineItem);
        allLineItems.addAll(moduleLineItems);

        Mono<BigDecimal> billableTotal = Flux.fromIterable(allLineItems)
                .filter(item -> !item.isIncludedInBase())
                .concatMap(item -> convert(item.getPrice(), item.getCurrency(), referenceCurrency, now))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return billableTotal
                .flatMap(total -> convert(total, referenceCurrency, targetCurrency, now))
                .map(amount -> InvoiceCalculation.builder()
                        .amount(amount)
                        .currency(targetCurrency)
                        .lineItems(allLineItems)
                        .build());
    }

    private Mono<BigDecimal> convert(BigDecimal amount, String fromCurrency, String toCurrency, Instant now) {
        String from = fromCurrency.toUpperCase();
        String to = toCurrency.toUpperCase();
        if (from.equals(to)) {
            return Mono.just(amount);
        }
        return exchangeRateRepository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        from, to, now)
                .switchIfEmpty(Mono.error(new PricingConfigurationException(
                        "No exchange rate configured for " + fromCurrency + " -> " + toCurrency)))
                .map(rate -> amount.multiply(rate.getRate()));
    }
}
