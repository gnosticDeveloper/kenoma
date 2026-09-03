package raum.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@code services.id} to its {@code name} so grant issuance can pick the right
 * {@link common.grants.ServiceGrantProfile}. Results are cached per id for the process
 * lifetime — the service catalogue is tiny and effectively static, and a stale name only
 * means a lease is issued against the wrong grant profile, which the reconcile job would
 * surface. Restart to refresh.
 */
@Service
@RequiredArgsConstructor
public class ServiceNameResolver {

    private final ServiceRepository serviceRepository;
    private final Map<UUID, Mono<String>> cache = new ConcurrentHashMap<>();

    public Mono<String> nameFor(UUID serviceId) {
        if (serviceId == null) {
            return Mono.just("unknown");
        }
        return cache.computeIfAbsent(serviceId, id ->
                serviceRepository.findById(id)
                        .map(service -> service.getName())
                        .defaultIfEmpty("unknown")
                        .cache());
    }
}
