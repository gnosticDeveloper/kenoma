package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.BasePricing;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface BasePricingRepository extends ReactiveCrudRepository<BasePricing, UUID> {
    Mono<BasePricing> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(Instant now);
    Mono<BasePricing> findFirstByOrderByEffectiveFromDesc();
    Flux<BasePricing> findAllByOrderByEffectiveFromDesc();
}
