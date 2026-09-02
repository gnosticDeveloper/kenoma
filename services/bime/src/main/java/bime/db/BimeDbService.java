package bime.db;

import bime.security.BimeAuthentication;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Entry point for all database access in Bime.
 *
 * <p>Delegates to {@link ConnectionPoolService}, which manages per-org, per-tier ephemeral
 * credential pools. On the request path the credentials are fetched from Raum using the
 * authenticated user's JWT (plus Bime's own service AppRole token); the caller's Bime roles
 * pick the Postgres privilege tier.
 */
@Service
@RequiredArgsConstructor
public class BimeDbService {

    private final ConnectionPoolService connectionPoolService;

    public Mono<BimeDbHandle> getHandle(BimeAuthentication caller) {
        return connectionPoolService.getHandle(caller);
    }

    /** For scheduled jobs with no live user session — see {@link ConnectionPoolService#getHandleViaVaultToken}. */
    public Mono<BimeDbHandle> getHandleViaVaultToken(UUID orgId, String vaultToken) {
        return connectionPoolService.getHandleViaVaultToken(orgId, vaultToken);
    }
}
