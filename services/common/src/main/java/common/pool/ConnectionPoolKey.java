package common.pool;

import java.util.UUID;

/**
 * Composite key identifying a pooled connection by the organisation and service
 * it belongs to.
 *
 * <p>Using a record gives correct {@code equals} and {@code hashCode} for free,
 * making it safe to use directly as a {@link java.util.concurrent.ConcurrentHashMap} key.
 *
 * <p>Lives in {@code common} so that any service implementing a connection pool
 * can reuse the same key type without duplicating it.
 */
public record ConnectionPoolKey(UUID orgId, UUID serviceId) {}