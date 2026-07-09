package vassago.db;

import common.dto.BasicCredentialDTO;
import common.metrics.ConnectionPoolMetrics;
import common.pool.ConnectionPoolEntry;
import common.pool.ConnectionPoolKey;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.r2dbc.pool.ConnectionPool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import common.db.DatabaseConnectionService;
import vassago.clients.RaumClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-org R2DBC connection pool for Vassago.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>A single {@link ConcurrentHashMap} stores either a pending {@code Mono<ConnectionPoolEntry>}
 *       (while credentials are being fetched) or a resolved {@code Mono.just(entry)} (once live).
 *       This eliminates the race window between "removed from inflight" and "visible in pool".</li>
 *   <li>Concurrent requests for the same key share the single in-flight Mono via {@code .cache()},
 *       so exactly one Raum call is made regardless of concurrency.</li>
 *   <li>The expiry buffer is a configurable percentage of the OpenBao lease duration.
 *       An exception is thrown at entry-creation time if the effective TTL would be zero or
 *       negative, surfacing misconfiguration immediately.</li>
 *   <li>The background eviction sweep catches exceptions per entry so a single disposal
 *       failure does not abort the rest of the tick.</li>
 *   <li>{@code orgId} is passed in by the caller for now. Once the JWT security layer is
 *       implemented it will be extracted from the reactive security context instead, and
 *       this parameter will be removed.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>Raw credentials are never stored in the map — only the constructed
 *       {@link io.r2dbc.pool.ConnectionPool} is kept.</li>
 *   <li>On eviction, {@link ConnectionPoolEntry#dispose()} closes all physical connections
 *       and releases internal credential state held by the R2DBC driver.</li>
 *   <li>The redundant {@link ConnectionPoolEntry#isExpired()} check on every {@link #getClient}
 *       call defends against scheduler failures.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConnectionPoolService {

    private final RaumClient raumClient;
    private final DatabaseConnectionService dbConnectionService;
    private final MeterRegistry meterRegistry;

    @Value("${vassago.service-id}")
    private String serviceId;

    @Value("${vassago.pool.expiry-buffer-percent:10}")
    private int expiryBufferPercent;

    @Value("${vassago.pool.eviction-interval-seconds:60}")
    private long evictionIntervalSeconds;

    private final ConcurrentHashMap<ConnectionPoolKey, Mono<ConnectionPoolEntry>> pool =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConnectionPoolKey, List<Meter>> poolMetrics =
            new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @PostConstruct
    void startEvictionScheduler() {
        if (expiryBufferPercent < 1 || expiryBufferPercent > 50) {
            throw new IllegalStateException(
                    "vassago.pool.expiry-buffer-percent must be between 1 and 50, got: "
                            + expiryBufferPercent);
        }
        Schedulers.boundedElastic().schedulePeriodically(
                this::evictExpiredEntries,
                evictionIntervalSeconds,
                evictionIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    void shutdown() {
        pool.forEach((key, entryMono) ->
                entryMono.subscribe(
                        this::disposeQuietly,
                        err -> {}
                )
        );
        pool.clear();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link DatabaseClient} for the given {@code orgId}.
     *
     * <p>Temporarily accepts {@code orgId} as a parameter until the JWT security
     * layer is in place, at which point this will be extracted from the reactive
     * security context and this argument removed.
     */
    public Mono<DatabaseClient> getClient(UUID orgId) {
        ConnectionPoolKey key = new ConnectionPoolKey(orgId, UUID.fromString(serviceId));
        return getClientForKey(key);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private Mono<DatabaseClient> getClientForKey(ConnectionPoolKey key) {
        return pool
                .computeIfAbsent(key, this::buildEntryMono)
                .flatMap(entry -> {
                    // Redundant expiry guard — catches scheduler lag.
                    if (entry.isExpired()) {
                        pool.remove(key);
                        disposeQuietly(entry);
                        return getClientForKey(key);
                    }
                    return Mono.just(DatabaseClient.create(entry.connectionFactory()));
                });
    }

    private Mono<ConnectionPoolEntry> buildEntryMono(ConnectionPoolKey key) {
        BasicCredentialDTO request = new BasicCredentialDTO();
        request.setOrgId(key.orgId());
        request.setServiceId(key.serviceId());

        return raumClient.getEphemeralCredentials(request)
                .<ConnectionPoolEntry>handle((credentials, sink) -> {
                    long bufferSeconds = (credentials.getLeaseDuration() * expiryBufferPercent) / 100;
                    long effectiveTtl  = credentials.getLeaseDuration() - bufferSeconds;

                    if (effectiveTtl <= 0) {
                        sink.error(new IllegalStateException(String.format(
                                "Effective TTL is %ds after applying %d%% buffer to lease_duration=%ds. " +
                                        "Reduce vassago.pool.expiry-buffer-percent or increase the OpenBao role TTL.",
                                effectiveTtl, expiryBufferPercent, credentials.getLeaseDuration())));
                        return;
                    }

                    Instant expiresAt = Instant.now().plusSeconds(effectiveTtl);
                    ConnectionPool connectionPool = dbConnectionService.createConnectionPool(credentials);
                    ConnectionPoolEntry entry = new ConnectionPoolEntry(
                            key,
                            connectionPool,
                            expiresAt,
                            credentials.getLeaseId()
                    );

                    poolMetrics.put(key, ConnectionPoolMetrics.register(meterRegistry, connectionPool, "vassago", key.orgId()));
                    pool.put(key, Mono.just(entry));
                    sink.next(entry);
                })
                .doOnError(err -> pool.remove(key))
                .cache();
    }

    private void evictExpiredEntries() {
        pool.forEach((key, entryMono) ->
                entryMono.subscribe(
                        entry -> {
                            if (entry.isExpired()) {
                                if (pool.remove(key, entryMono)) {
                                    disposeQuietly(entry);
                                }
                            }
                        },
                        err -> {}
                )
        );
    }

    private void disposeQuietly(ConnectionPoolEntry entry) {
        List<Meter> meters = poolMetrics.remove(entry.key());
        if (meters != null) {
            ConnectionPoolMetrics.unregister(meterRegistry, meters);
        }
        try {
            entry.dispose();
        } catch (Exception ignored) {}
    }
}