package bime.services;

import bime.clients.RaumClient;
import bime.dto.OrgSummaryDTO;
import bime.openbao.OpenBaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an organization's display name from raum, cached for the lifetime of the process.
 * Org names change rarely and a stale name on a printed ticket is harmless, so there is no
 * eviction; a failed lookup is not cached and simply yields an empty result the caller falls
 * back from (e.g. to a generic receipt heading).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgDirectoryService {

    private final RaumClient raumClient;
    private final OpenBaoService openBaoService;

    private final ConcurrentHashMap<UUID, Mono<String>> names = new ConcurrentHashMap<>();

    /** The org's display name, or an empty Mono when raum can't be reached or has no such org. */
    public Mono<String> nameOf(UUID orgId) {
        return names.computeIfAbsent(orgId, id -> raumClient.getOrgSummary(id, openBaoService.getToken())
                .mapNotNull(OrgSummaryDTO::getName)
                .doOnError(e -> {
                    log.warn("Could not resolve org name for {}: {}", id, e.toString());
                    names.remove(id);
                })
                .onErrorResume(e -> Mono.empty())
                .cache());
    }
}
