package common.pool;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;

import java.time.Instant;

/**
 * Immutable value object representing one live pooled database connection.
 *
 * <p>Lives in {@code common} so that all services share the same entry type.
 *
 * <p>The {@code connectionFactory} field is typed as {@link ConnectionFactory} (r2dbc-spi)
 * rather than {@link ConnectionPool} (r2dbc-pool) so that {@code common} only needs a
 * dependency on {@code r2dbc-spi}. Vassago passes a {@code ConnectionPool} instance,
 * which implements {@code ConnectionFactory}. Disposal casts internally when needed.
 *
 * <p><strong>Security:</strong> raw credential strings are intentionally absent.
 * Only the already-constructed {@link ConnectionFactory} is stored, so credentials
 * cannot be read back from the in-memory pool map.
 */
public record ConnectionPoolEntry(
        ConnectionPoolKey key,
        ConnectionFactory connectionFactory,
        Instant expiresAt,
        String leaseId
) {

    /**
     * Returns {@code true} when this entry has passed its expiry instant.
     *
     * <p>Called on every {@code getClient()} invocation as a redundant guard
     * against eviction-scheduler failures (e.g. long GC pauses, missed ticks).
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Cleanly disposes of the underlying connection pool, closing all physical
     * connections and releasing any credential state held internally by the
     * R2DBC driver. Must be called whenever this entry is evicted.
     *
     * <p>Casts to {@link ConnectionPool} if available, which provides proper
     * lifecycle management. Falls back to a no-op if the factory is not a pool.
     */
    public void dispose() {
        if (connectionFactory instanceof ConnectionPool pool) {
            pool.dispose();
        }
    }
}