package bime.db;

import bime.clients.RaumClient;
import common.db.DatabaseConnectionService;
import common.dto.BasicCredentialDTO;
import common.pool.ConnectionPoolEntry;
import common.pool.ConnectionPoolKey;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ConnectionPoolService {

    private final RaumClient raumClient;
    private final DatabaseConnectionService dbConnectionService;

    @Value("${bime.service-id}")
    private String serviceId;

    @Value("${bime.pool.expiry-buffer-percent:10}")
    private int expiryBufferPercent;

    @Value("${bime.pool.eviction-interval-seconds:60}")
    private long evictionIntervalSeconds;

    private final ConcurrentHashMap<ConnectionPoolKey, Mono<ConnectionPoolEntry>> pool =
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
        return getHandleForKey(key);
    }

    private Mono<BimeDbHandle> getHandleForKey(ConnectionPoolKey key) {
        return pool
                .computeIfAbsent(key, this::buildEntryMono)
                .flatMap(entry -> {
                    if (entry.isExpired()) {
                        pool.remove(key);
                        disposeQuietly(entry);
                        return getHandleForKey(key);
                    }
                    DatabaseClient client = DatabaseClient.create(entry.connectionFactory());
                    TransactionalOperator tx = TransactionalOperator.create(
                            new R2dbcTransactionManager(entry.connectionFactory()));
                    return Mono.just(new BimeDbHandle(client, tx));
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
                                        "Reduce bime.pool.expiry-buffer-percent or increase the OpenBao role TTL.",
                                effectiveTtl, expiryBufferPercent, credentials.getLeaseDuration())));
                        return;
                    }

                    Instant expiresAt = Instant.now().plusSeconds(effectiveTtl);
                    ConnectionPoolEntry entry = new ConnectionPoolEntry(
                            key,
                            dbConnectionService.createConnectionPool(credentials),
                            expiresAt,
                            credentials.getLeaseId()
                    );

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
        try {
            entry.dispose();
        } catch (Exception ignored) {}
    }
}
