package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.ModulePricing;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ModulePricingRepository extends ReactiveCrudRepository<ModulePricing, UUID> {
    Mono<ModulePricing> findFirstByServiceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID serviceId, Instant now);
    Flux<ModulePricing> findAllByServiceIdOrderByEffectiveFromDesc(UUID serviceId);
    Flux<ModulePricing> findAllByOrderByEffectiveFromDesc();
}
