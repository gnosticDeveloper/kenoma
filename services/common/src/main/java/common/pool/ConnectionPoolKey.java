package common.pool;

import common.grants.ServiceTier;

import java.util.UUID;

/**
 * Composite key identifying a pooled connection by the organization, service, and
 * privilege {@link ServiceTier} it belongs to.
 *
 * <p>Using a record gives correct {@code equals} and {@code hashCode} for free,
 * making it safe to use directly as a {@link java.util.concurrent.ConcurrentHashMap} key.
 *
 * <p>The {@code tier} component matters because an ephemeral lease's Postgres privileges
 * are fixed for its whole ~1h life: a lease warmed by a read-only caller must not be
 * reused to serve an admin write (it would fail {@code 42501}), and vice versa a
 * lower-privilege caller must not ride on a read/write lease. Keying on tier keeps one
 * pool entry (and one lease) per (org, service, tier) actually in use — a handful per
 * active org.
 *
 * <p>Lives in {@code common} so that any service implementing a connection pool
 * can reuse the same key type without duplicating it.
 */
public record ConnectionPoolKey(UUID orgId, UUID serviceId, ServiceTier tier) {}
