package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.BillingHistory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface BillingHistoryRepository extends ReactiveCrudRepository<BillingHistory, UUID> {
    Flux<BillingHistory> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);
    Mono<BillingHistory> findByIdAndOrgId(UUID id, UUID orgId);
}
