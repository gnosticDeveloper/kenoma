package common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.PoolMetrics;

import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Registers per-tenant R2DBC connection pool gauges with Micrometer.
 *
 * <p>Cardinality is bounded by the number of active tenants (one pool per org per
 * service), not by request volume, so tagging by org_id here is safe unlike tagging
 * HTTP request metrics by tenant would be.
 */
public class ConnectionPoolMetrics {

    public static List<Meter> register(MeterRegistry registry, ConnectionPool pool, String service, UUID orgId) {
        return List.of(
                gauge(registry, pool, service, orgId, "acquired", PoolMetrics::acquiredSize),
                gauge(registry, pool, service, orgId, "idle", PoolMetrics::idleSize),
                gauge(registry, pool, service, orgId, "pending", PoolMetrics::pendingAcquireSize)
        );
    }

    public static void unregister(MeterRegistry registry, List<Meter> meters) {
        meters.forEach(registry::remove);
    }

    private static Meter gauge(MeterRegistry registry, ConnectionPool pool, String service, UUID orgId,
                                String state, ToDoubleFunction<PoolMetrics> valueFn) {
        return Gauge.builder("db.pool.connections", pool, p -> p.getMetrics().map(valueFn::applyAsDouble).orElse(0.0))
                .tag("service", service)
                .tag("org_id", orgId.toString())
                .tag("state", state)
                .register(registry);
    }
}
