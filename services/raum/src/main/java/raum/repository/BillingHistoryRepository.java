package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.BillingHistory;

import java.util.UUID;

@Repository
public interface BillingHistoryRepository extends ReactiveCrudRepository<BillingHistory, UUID> {

}
