package raum.migration;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import common.dto.CredentialsDTO;
import raum.backup.InstanceDiscovery;
import raum.models.Credentials;
import raum.models.Service;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Applies Flyway migrations to every distinct physical database discovered across raum's
 * {@code credentials} table (see {@link InstanceDiscovery} - one representative row per
 * (db_host, db_port, db_name), whether it's shared by many orgs or dedicated to one), plus
 * raum's own database.
 *
 * <p>Raum's own database is migrated by Spring Boot's Flyway autoconfiguration
 * (see {@code spring.flyway.*} in application.yaml) as part of context startup, so by the time
 * {@link #onReady()} fires on {@link ApplicationReadyEvent}, the {@code credentials} table
 * already reflects any schema this deploy introduced for raum itself (including newly seeded
 * rows from {@code R__seed_platform_data.sql}).
 *
 * <p>DDL requires object-ownership rights, which ephemeral OpenBao roles don't have (a fresh role
 * is issued per request) - so, like {@link raum.onboarding.SchemaProvisioner}, this always runs
 * under the static, long-lived credentials for each instance.
 */
@Slf4j
@Component
public class MigrationRunner {

    private final CredentialsRepository credentialsRepository;
    private final ServiceRepository serviceRepository;
    private final OpenBaoService openBaoService;

    public MigrationRunner(CredentialsRepository credentialsRepository,
                            ServiceRepository serviceRepository,
                            OpenBaoService openBaoService) {
        this.credentialsRepository = credentialsRepository;
        this.serviceRepository = serviceRepository;
        this.openBaoService = openBaoService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        migrateAll().subscribe(null, e -> log.error("Startup migration sweep failed", e));
    }

    /** Sweeps every distinct instance discoverable via the credentials table. One failing
     * instance doesn't block the others - each is isolated and logged. */
    public Mono<Void> migrateAll() {
        return credentialsRepository.findAll()
                .collectList()
                .map(InstanceDiscovery::discoverInstances)
                .flatMapMany(Flux::fromIterable)
                .concatMap(instance -> migrateInstance(instance.representative())
                        .onErrorResume(e -> {
                            log.error("Migration failed for instance {}", instance.key(), e);
                            return Mono.empty();
                        }))
                .then();
    }

    /** Migrates the single physical instance behind the given credentials row. Safe to call
     * repeatedly (including against an already up-to-date instance, e.g. from onboarding a new
     * org onto a colocated/shared database) - Flyway no-ops once there's nothing pending. */
    public Mono<Void> migrateInstance(Credentials credentials) {
        return serviceRepository.findById(credentials.getServiceId())
                .flatMap(service -> openBaoService.getStaticCredentials(credentials.getId())
                        .flatMap(staticCreds -> runFlyway(service, credentials, staticCreds)));
    }

    private Mono<Void> runFlyway(Service service, Credentials credentials, CredentialsDTO staticCreds) {
        String jdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(
                credentials.getDbHost(), credentials.getDbPort(), credentials.getDbName());
        String location = "classpath:db/migration/" + service.getName().toLowerCase();
        return Mono.fromRunnable(() -> Flyway.configure()
                        .dataSource(jdbcUrl, staticCreds.getUserName(), staticCreds.getPassword())
                        .locations(location)
                        .baselineOnMigrate(true)
                        .baselineVersion("1")
                        .load()
                        .migrate())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> log.info("Migrated {} instance {}:{}/{}", service.getName(),
                        credentials.getDbHost(), credentials.getDbPort(), credentials.getDbName()))
                .then();
    }
}
