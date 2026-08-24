package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.ExportJob;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface ExportJobRepository extends ReactiveCrudRepository<ExportJob, UUID> {
    Mono<ExportJob> findByIdAndOrgId(UUID id, UUID orgId);
    Flux<ExportJob> findAllByStatusOrderByRequestedAtAsc(String status);
    Mono<ExportJob> findFirstByOrgIdAndStatusIn(UUID orgId, Collection<String> statuses);
    Flux<ExportJob> findAllByOrderByRequestedAtDesc();
}
