package raum.services;

import common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.models.Organization;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrganizationService {
    final OrganizationRepository repository;

    public OrganizationService(OrganizationRepository repository) {
        this.repository = repository;
    }

    public Mono<OrgResponseDTO> registerOrg(OrgRequestDTO dto) {
        Organization org = Organization.builder()
                .contactName(dto.getContactName())
                .contactEmail(dto.getContactEmail())
                .name(dto.getName())
                .build();

        return repository.save(org).map(savedOrg ->
                OrgResponseDTO.builder()
                        .id(savedOrg.getId())
                        .name(savedOrg.getName())
                        .contactEmail(savedOrg.getContactEmail())
                        .build()
        );
    }

    public Flux<OrgResponseDTO> getAllOrgs() {
        return repository.findAll()
                .filter(organization -> organization.getStoppedAt() == null)
                .map(organization -> OrgResponseDTO.builder()
                        .id(organization.getId())
                        .name(organization.getName())
                        .contactEmail(organization.getContactEmail())
                        .build()
                );
    }

    public Mono<OrgResponseDTO> getOrgDataById(UUID id){
        return repository.findById(id).map( organization ->
                OrgResponseDTO.builder()
                        .id(organization.getId())
                        .name(organization.getName())
                        .contactEmail(organization.getContactEmail())
                        .build()
        );
    }

    public Mono<OrgResponseDTO> updateOrg(UUID id, OrgRequestDTO dto) {
        return repository.findById(id)
                .flatMap(org -> {
                    org.setName(dto.getName());
                    org.setContactName(dto.getContactName());
                    org.setContactEmail(dto.getContactEmail());
                    org.setModifiedAt(Instant.now());
                    return repository.save(org);
                })
                .map(savedOrg -> OrgResponseDTO.builder()
                        .id(savedOrg.getId())
                        .name(savedOrg.getName())
                        .contactEmail(savedOrg.getContactEmail())
                        .build()
                )
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")));
    }

    public Mono<Void> deleteOrg(UUID id) {
        return repository.findById(id)
                .flatMap(org -> {
                    org.setStoppedAt(Instant.now());
                    return repository.save(org);
                })
                .switchIfEmpty(Mono.error(new NotFoundException("Organization not found")))
                .then();
    }
}
