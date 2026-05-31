package raum.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import raum.dto.ServiceRequestDTO;
import raum.dto.ServiceResponseDTO;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceService {
    private final ServiceRepository repository;

    public Mono<ServiceResponseDTO> register(ServiceRequestDTO dto) {
        return repository.save(
                raum.models.Service.builder()
                        .name(dto.getName())
                        .description(dto.getDescription())
                        .createdAt(Instant.now())
                        .modifiedAt(Instant.now())
                        .build()
        ).map(s -> ServiceResponseDTO.builder()
                .id(s.getId())
                .name(s.getName())
                .description(s.getDescription())
                .build()
        );
    }

    public Mono<ServiceResponseDTO> getById(UUID id) {
        return repository.findById(id)
                .map(s -> ServiceResponseDTO.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .description(s.getDescription())
                        .build()
                )
                .switchIfEmpty(Mono.error(new RuntimeException("Service not found")));
    }

    public Flux<ServiceResponseDTO> getAll() {
        return repository.findAll()
                .map(s -> ServiceResponseDTO.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .description(s.getDescription())
                        .build()
                );
    }

    public Mono<ServiceResponseDTO> update(UUID id, ServiceRequestDTO dto) {
        return repository.findById(id)
                .flatMap(s -> {
                    s.setName(dto.getName());
                    s.setDescription(dto.getDescription());
                    s.setModifiedAt(Instant.now());
                    return repository.save(s);
                })
                .map(s -> ServiceResponseDTO.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .description(s.getDescription())
                        .build()
                )
                .switchIfEmpty(Mono.error(new RuntimeException("Service not found")));
    }

    public Mono<Void> delete(UUID id) {
        return repository.findById(id)
                .flatMap(s -> {
                    s.setStoppedAt(Instant.now());
                    return repository.save(s);
                })
                .switchIfEmpty(Mono.error(new RuntimeException("Service not found")))
                .then();
    }
}