package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.Service;
import java.util.UUID;

@Repository
public interface ServiceRepository extends ReactiveCrudRepository<Service, UUID> {
}