package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.PendingOrgVerification;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface PendingOrgVerificationRepository extends ReactiveCrudRepository<PendingOrgVerification, UUID> {

    Mono<PendingOrgVerification> findByTokenHashAndUsedFalseAndExpiresAtAfter(String tokenHash, Instant now);
}
