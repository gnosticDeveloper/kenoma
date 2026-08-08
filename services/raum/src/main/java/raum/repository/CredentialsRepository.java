package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.Credentials;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface CredentialsRepository extends ReactiveCrudRepository<Credentials, UUID> {
    Mono<Credentials> findByOrgIdAndServiceId(UUID orgId, UUID serviceId);
    Flux<Credentials> findAllByInitializedFalse();
    Flux<Credentials> findAllByOrgId(UUID orgId);
}
