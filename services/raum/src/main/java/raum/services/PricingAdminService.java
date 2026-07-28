package raum.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import raum.dto.BasePricingRequestDTO;
import raum.dto.BasePricingResponseDTO;
import raum.dto.ExchangeRateRequestDTO;
import raum.dto.ExchangeRateResponseDTO;
import raum.dto.ModulePricingRequestDTO;
import raum.dto.ModulePricingResponseDTO;
import raum.models.BasePricing;
import raum.models.ExchangeRate;
import raum.models.ModulePricing;
import raum.repository.BasePricingRepository;
import raum.repository.ExchangeRateRepository;
import raum.repository.ModulePricingRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class PricingAdminService {

    private final BasePricingRepository basePricingRepository;
    private final ModulePricingRepository modulePricingRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ServiceRepository serviceRepository;

    public PricingAdminService(BasePricingRepository basePricingRepository,
                                ModulePricingRepository modulePricingRepository,
                                ExchangeRateRepository exchangeRateRepository,
                                ServiceRepository serviceRepository) {
        this.basePricingRepository = basePricingRepository;
        this.modulePricingRepository = modulePricingRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.serviceRepository = serviceRepository;
    }

    public Flux<BasePricingResponseDTO> listBasePricing() {
        return basePricingRepository.findAllByOrderByEffectiveFromDesc().map(this::toDTO);
    }

    public Mono<BasePricingResponseDTO> addBasePricing(BasePricingRequestDTO dto) {
        Mono<BasePricingResponseDTO> invalid = this.<BasePricingResponseDTO>validatePriceAndCurrency(dto.getPrice(), dto.getCurrency());
        if (invalid != null) {
            return invalid;
        }
        String newCurrency = dto.getCurrency().toUpperCase();
        return basePricingRepository.findFirstByOrderByEffectiveFromDesc()
                .flatMap(latest -> {
                    if (!latest.getCurrency().equals(newCurrency)) {
                        return Mono.<BasePricingResponseDTO>error(new BadRequestException(
                                "Base price currency is locked to " + latest.getCurrency()
                                        + " (set by the first base price entry); cannot add a price in " + newCurrency));
                    }
                    return saveBasePricing(dto, newCurrency);
                })
                .switchIfEmpty(saveBasePricing(dto, newCurrency));
    }

    private Mono<BasePricingResponseDTO> saveBasePricing(BasePricingRequestDTO dto, String currency) {
        BasePricing entry = BasePricing.builder()
                .price(dto.getPrice())
                .currency(currency)
                .effectiveFrom(dto.getEffectiveFrom() != null ? dto.getEffectiveFrom() : Instant.now())
                .createdAt(Instant.now())
                .build();
        return basePricingRepository.save(entry).map(this::toDTO);
    }

    public Flux<ModulePricingResponseDTO> listModulePricing() {
        return modulePricingRepository.findAllByOrderByEffectiveFromDesc()
                .flatMap(this::toDTO);
    }

    public Mono<ModulePricingResponseDTO> addModulePricing(ModulePricingRequestDTO dto) {
        Mono<ModulePricingResponseDTO> invalid = this.<ModulePricingResponseDTO>validatePriceAndCurrency(dto.getPrice(), dto.getCurrency());
        if (invalid != null) {
            return invalid;
        }
        if (dto.getServiceId() == null) {
            return Mono.error(new BadRequestException("serviceId is required"));
        }
        return serviceRepository.findById(dto.getServiceId())
                .switchIfEmpty(Mono.error(new NotFoundException("Service not found")))
                .flatMap(service -> {
                    ModulePricing entry = ModulePricing.builder()
                            .serviceId(dto.getServiceId())
                            .price(dto.getPrice())
                            .currency(dto.getCurrency().toUpperCase())
                            .includedInBase(dto.isIncludedInBase())
                            .effectiveFrom(dto.getEffectiveFrom() != null ? dto.getEffectiveFrom() : Instant.now())
                            .createdAt(Instant.now())
                            .build();
                    return modulePricingRepository.save(entry)
                            .map(saved -> toDTO(saved, service.getName()));
                });
    }

    public Flux<ExchangeRateResponseDTO> listExchangeRates() {
        return exchangeRateRepository.findAllByOrderByEffectiveFromDesc().map(this::toDTO);
    }

    public Mono<ExchangeRateResponseDTO> addExchangeRate(ExchangeRateRequestDTO dto) {
        if (dto.getFromCurrency() == null || dto.getFromCurrency().isBlank()
                || dto.getToCurrency() == null || dto.getToCurrency().isBlank()) {
            return Mono.error(new BadRequestException("fromCurrency and toCurrency are required"));
        }
        if (dto.getRate() == null || dto.getRate().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new BadRequestException("rate must be a positive number"));
        }
        ExchangeRate entry = ExchangeRate.builder()
                .fromCurrency(dto.getFromCurrency().toUpperCase())
                .toCurrency(dto.getToCurrency().toUpperCase())
                .rate(dto.getRate())
                .effectiveFrom(dto.getEffectiveFrom() != null ? dto.getEffectiveFrom() : Instant.now())
                .createdAt(Instant.now())
                .build();
        return exchangeRateRepository.save(entry).map(this::toDTO);
    }

    private <T> Mono<T> validatePriceAndCurrency(BigDecimal price, String currency) {
        if (currency == null || currency.isBlank()) {
            return Mono.error(new BadRequestException("currency is required"));
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            return Mono.error(new BadRequestException("price must be zero or a positive number"));
        }
        return null;
    }

    private BasePricingResponseDTO toDTO(BasePricing entity) {
        return BasePricingResponseDTO.builder()
                .id(entity.getId())
                .price(entity.getPrice())
                .currency(entity.getCurrency())
                .effectiveFrom(entity.getEffectiveFrom())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private Mono<ModulePricingResponseDTO> toDTO(ModulePricing entity) {
        return serviceRepository.findById(entity.getServiceId())
                .map(service -> toDTO(entity, service.getName()))
                .switchIfEmpty(Mono.just(toDTO(entity, null)));
    }

    private ModulePricingResponseDTO toDTO(ModulePricing entity, String serviceName) {
        return ModulePricingResponseDTO.builder()
                .id(entity.getId())
                .serviceId(entity.getServiceId())
                .serviceName(serviceName)
                .price(entity.getPrice())
                .currency(entity.getCurrency())
                .includedInBase(entity.isIncludedInBase())
                .effectiveFrom(entity.getEffectiveFrom())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private ExchangeRateResponseDTO toDTO(ExchangeRate entity) {
        return ExchangeRateResponseDTO.builder()
                .id(entity.getId())
                .fromCurrency(entity.getFromCurrency())
                .toCurrency(entity.getToCurrency())
                .rate(entity.getRate())
                .effectiveFrom(entity.getEffectiveFrom())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
