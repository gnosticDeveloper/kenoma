package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.DrBackup;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public interface DrBackupRepository extends ReactiveCrudRepository<DrBackup, UUID> {
    Flux<DrBackup> findAllByOrderByCreatedAtDesc();
    Flux<DrBackup> findAllByScopeOrderByCreatedAtDesc(String scope);
    Flux<DrBackup> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
