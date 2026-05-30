package raum.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import raum.models.Organization;

import java.util.UUID;

@Repository
public interface OrganizationRepository extends ReactiveCrudRepository<Organization, UUID> {

}
