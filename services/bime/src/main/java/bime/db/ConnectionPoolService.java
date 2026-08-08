package bime.db;

import bime.clients.RaumClient;
import common.db.DatabaseConnectionService;
import common.dto.BasicCredentialDTO;
import common.dto.CredentialsDTO;
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
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class ConnectionPoolService {

    private final RaumClient raumClient;
    private final DatabaseConnectionService dbConnectionService;
    private final MeterRegistry meterRegistry;

    @Value("${bime.service-id}")
    private String serviceId;

    @Value("${bime.pool.expiry-buffer-percent:10}")
    private int expiryBufferPercent;

    @Value("${bime.pool.eviction-interval-seconds:60}")
    private long evictionIntervalSeconds;

    private final ConcurrentHashMap<ConnectionPoolKey, Mono<ConnectionPoolEntry>> pool =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConnectionPoolKey, List<Meter>> poolMetrics =
            new ConcurrentHashMap<>();

    @PostConstruct
    void startEvictionScheduler() {
        if (expiryBufferPercent < 1 || expiryBufferPercent > 50) {
            throw new IllegalStateException(
                    "bime.pool.expiry-buffer-percent must be between 1 and 50, got: "
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
                entryMono.subscribe(this::disposeQuietly, err -> {}));
        pool.clear();
    }

    public Mono<BimeDbHandle> getHandle(UUID orgId) {
        ConnectionPoolKey key = new ConnectionPoolKey(orgId, UUID.fromString(serviceId));
        return getHandleForKey(key, this::fetchCredentialsViaUserJwt);
    }

    /**
     * Same pooled-handle resolution as {@link #getHandle(UUID)}, but fetches fresh credentials
     * (on a cache miss) using Bime's own OpenBao AppRole token instead of a user's JWT. Used by
     * scheduled jobs, which have no live user session to pull a JWT from.
     */
    public Mono<BimeDbHandle> getHandleViaVaultToken(UUID orgId, String vaultToken) {
        ConnectionPoolKey key = new ConnectionPoolKey(orgId, UUID.fromString(serviceId));
        return getHandleForKey(key, k -> fetchCredentialsViaVaultToken(k, vaultToken));
    }

    private Mono<BimeDbHandle> getHandleForKey(ConnectionPoolKey key, Function<ConnectionPoolKey, Mono<CredentialsDTO>> credentialsFetcher) {
        return pool
                .computeIfAbsent(key, k -> buildEntryMono(k, credentialsFetcher))
                .flatMap(entry -> {
                    if (entry.isExpired()) {
                        pool.remove(key);
                        disposeQuietly(entry);
                        return getHandleForKey(key, credentialsFetcher);
                    }
                    DatabaseClient client = DatabaseClient.create(entry.connectionFactory());
                    TransactionalOperator tx = TransactionalOperator.create(
                            new R2dbcTransactionManager(entry.connectionFactory()));
                    return Mono.just(new BimeDbHandle(client, tx));
                });
    }

    private Mono<CredentialsDTO> fetchCredentialsViaUserJwt(ConnectionPoolKey key) {
        BasicCredentialDTO request = new BasicCredentialDTO();
        request.setOrgId(key.orgId());
        request.setServiceId(key.serviceId());
        return raumClient.getEphemeralCredentials(request);
    }

    private Mono<CredentialsDTO> fetchCredentialsViaVaultToken(ConnectionPoolKey key, String vaultToken) {
        BasicCredentialDTO request = new BasicCredentialDTO();
        request.setOrgId(key.orgId());
        request.setServiceId(key.serviceId());
        return raumClient.getEphemeralCredentials(request, vaultToken);
    }

    private Mono<ConnectionPoolEntry> buildEntryMono(ConnectionPoolKey key, Function<ConnectionPoolKey, Mono<CredentialsDTO>> credentialsFetcher) {
        return credentialsFetcher.apply(key)
                .<ConnectionPoolEntry>handle((credentials, sink) -> {
                    long bufferSeconds = (credentials.getLeaseDuration() * expiryBufferPercent) / 100;
                    long effectiveTtl  = credentials.getLeaseDuration() - bufferSeconds;

                    if (effectiveTtl <= 0) {
                        sink.error(new IllegalStateException(String.format(
                                "Effective TTL is %ds after applying %d%% buffer to lease_duration=%ds. " +
                                        "Reduce bime.pool.expiry-buffer-percent or increase the OpenBao role TTL.",
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

                    poolMetrics.put(key, ConnectionPoolMetrics.register(meterRegistry, connectionPool, "bime", key.orgId()));
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
