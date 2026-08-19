package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import raum.models.Credentials;
import raum.models.DrBackup;
import raum.models.DrBackupScope;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.DrBackupRepository;
import raum.repository.OrganizationRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * DR backup, per-org and per-service: unlike {@link DrBackupScheduler} (one dump per shared
 * physical instance, covering every org on it) and unlike {@link TenantExportPoller} (org-scoped,
 * but trims platform-internal tables and is meant for download), this produces an insert-only,
 * org-scoped dump of <b>everything</b> an org owns - including the platform-internal tables tenant
 * export omits (see {@link TenantExportPoller#DR_RAUM_TABLES}) - so a single org's data can be
 * "rewound" after user error without touching any other org sharing the same instance, and without
 * exposing platform-internal data through a tenant-facing export.
 *
 * <p>Reuses tenant export's table lists ({@link TenantExportPoller#tablesFor}) and dump-building
 * ({@link SqlCopyDumpWriter}) - the only difference from tenant export's SQL format is which table
 * lists are passed in and that the artifact is uploaded via the encrypting, non-downloadable
 * {@link EncryptingArtifactStore} rather than the plain one.
 */
@Slf4j
@Component
public class OrgBackupScheduler {

    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final String RAUM_SERVICE_NAME = "raum";

    private final OrganizationRepository organizationRepository;
    private final CredentialsRepository credentialsRepository;
    private final ServiceRepository serviceRepository;
    private final OpenBaoService openBaoService;
    private final ArtifactStore artifactStore;
    private final DrBackupRepository drBackupRepository;
    private final SqlCopyDumpWriter sqlCopyDumpWriter;

    private final String raumDbHost;
    private final int raumDbPort;
    private final String raumDbName;
    private final String raumDbUser;
    private final String raumDbPassword;

    public OrgBackupScheduler(OrganizationRepository organizationRepository,
                               CredentialsRepository credentialsRepository,
                               ServiceRepository serviceRepository,
                               OpenBaoService openBaoService,
                               @Qualifier("encryptingArtifactStore") ArtifactStore artifactStore,
                               DrBackupRepository drBackupRepository,
                               SqlCopyDumpWriter sqlCopyDumpWriter,
                               @Value("${RAUM_DB_HOST:localhost}") String raumDbHost,
                               @Value("${RAUM_DB_PORT:5432}") int raumDbPort,
                               @Value("${RAUM_DB_NAME:raum}") String raumDbName,
                               @Value("${RAUM_DB_USER:postgres}") String raumDbUser,
                               @Value("${RAUM_DB_PASSWORD:postgres}") String raumDbPassword) {
        this.organizationRepository = organizationRepository;
        this.credentialsRepository = credentialsRepository;
        this.serviceRepository = serviceRepository;
        this.openBaoService = openBaoService;
        this.artifactStore = artifactStore;
        this.drBackupRepository = drBackupRepository;
        this.sqlCopyDumpWriter = sqlCopyDumpWriter;
        this.raumDbHost = raumDbHost;
        this.raumDbPort = raumDbPort;
        this.raumDbName = raumDbName;
        this.raumDbUser = raumDbUser;
        this.raumDbPassword = raumDbPassword;
    }

    @Scheduled(cron = "${raum.backup.org-backup-cron:0 30 2 * * *}")
    public void runOrgBackup() {
        organizationRepository.findAllByStoppedAtIsNull()
                .concatMap(org -> backupOrg(org.getId())
                        .onErrorResume(e -> {
                            log.error("Org DR backup failed for org {}", org.getId(), e);
                            return Mono.empty();
                        }))
                .then()
                .subscribe(null, e -> log.error("Org DR backup run failed", e));
    }

    private Mono<Void> backupOrg(UUID orgId) {
        Mono<Void> raum = dumpAndUploadRaum(orgId);
        Mono<Void> perService = credentialsRepository.findAllByOrgId(orgId)
                .concatMap(creds -> dumpAndUploadServiceDb(orgId, creds)
                        .onErrorResume(e -> {
                            log.error("Org DR backup failed for org {} credentials {}", orgId, creds.getId(), e);
                            return Mono.empty();
                        }))
                .then();
        return raum.then(perService);
    }

    private Mono<Void> dumpAndUploadRaum(UUID orgId) {
        return buildDump(raumConn(), TenantExportPoller.DR_RAUM_TABLES, orgId, "dr-org-backup-raum-")
                .flatMap(dumpFile -> uploadAndCatalog(dumpFile, orgId, RAUM_SERVICE_NAME));
    }

    private Mono<Void> dumpAndUploadServiceDb(UUID orgId, Credentials creds) {
        return resolveServiceName(creds)
                .flatMap(serviceName -> openBaoService.registerBackupRole(creds.getId(), creds.getDbHost(), creds.getDbPort(), creds.getDbName())
                        .then(openBaoService.issueBackupCredentials(creds.getId()))
                        .map(dbCreds -> new PgConn(creds.getDbHost(), creds.getDbPort(), creds.getDbName(), dbCreds.getUserName(), dbCreds.getPassword()))
                        .flatMap(conn -> buildDump(conn, TenantExportPoller.tablesFor(serviceName), orgId, "dr-org-backup-" + serviceName + "-"))
                        .flatMap(dumpFile -> uploadAndCatalog(dumpFile, orgId, serviceName)));
    }

    private Mono<Path> buildDump(PgConn conn, List<ExportTable> tables, UUID orgId, String tempFilePrefix) {
        return Mono.fromCallable(() -> sqlCopyDumpWriter.buildInsertOnlySql(conn, tables, orgId, tempFilePrefix))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> uploadAndCatalog(Path dumpFile, UUID orgId, String serviceName) {
        String key = objectKey(orgId, serviceName);
        return artifactStore.upload(key, dumpFile)
                .doFinally(signal -> deleteQuietly(dumpFile))
                .then(recordCatalogEntry(orgId, serviceName, key))
                .doOnSuccess(v -> log.info("Org DR backup completed for org {} service {}", orgId, serviceName));
    }

    private Mono<Void> recordCatalogEntry(UUID orgId, String serviceName, String objectKey) {
        return drBackupRepository.save(DrBackup.builder()
                .scope(DrBackupScope.ORG.name())
                .orgId(orgId)
                .serviceName(serviceName)
                .objectKey(objectKey)
                .createdAt(Instant.now())
                .build()).then();
    }

    private Mono<String> resolveServiceName(Credentials creds) {
        return serviceRepository.findById(creds.getServiceId())
                .map(service -> service.getName().toLowerCase(Locale.ROOT))
                .switchIfEmpty(Mono.just(creds.getServiceId().toString()));
    }

    private PgConn raumConn() {
        return new PgConn(raumDbHost, raumDbPort, raumDbName, raumDbUser, raumDbPassword);
    }

    private String objectKey(UUID orgId, String serviceName) {
        return "dr-org-backups/" + orgId + "/" + serviceName + "/" + KEY_TIMESTAMP.format(Instant.now()) + ".sql.gz.enc";
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp org DR backup file {}", file, e);
        }
    }
}
