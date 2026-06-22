package bime.db;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Entry point for all database access in Bime.
 *
 * <p>Delegates to {@link ConnectionPoolService}, which manages per-org ephemeral
 * credential pools. Credentials are fetched from Raum using the authenticated
 * user's JWT — no service-level OpenBao token is required.
 */
@Service
@RequiredArgsConstructor
public class BimeDbService {

    private final ConnectionPoolService connectionPoolService;

    public Mono<BimeDbHandle> getHandle(UUID orgId) {
        return connectionPoolService.getHandle(orgId);
    }
}
