package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import common.dto.CredentialsDTO;
import raum.models.Credentials;
import raum.models.DrBackup;
import raum.models.DrBackupScope;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.DrBackupRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Disaster-recovery backup: one pg_dump per distinct physical (host, port, db_name) discovered
 * across raum's credentials table, uploaded to object storage. This is instance-level protection,
 * not tenant export — a shared instance's dump covers every org on it, filtered by nothing.
 *
 * <p>Raum's own database is backed up too ({@link #backupRaumInstance()}), but can't go through the
 * same discovery path as every other instance: raum never has a {@code credentials} row pointing at
 * itself (it uses its own {@code RAUM_DB_*} connection directly, not a Vault-registered one - see
 * {@code TenantExportPoller#raumConn}), so there's no representative {@link Credentials} row and no
 * Vault connection to issue a backup role against. The resulting catalog entry has
 * {@code representativeCredentialsId = null}, which {@code DrRestoreService} treats as a signal to
 * refuse an API-triggered restore for it - the artifact is still uploaded and cataloged (so it's
 * available for a DBA to replay by hand), but raum is the platform's own control-plane database and
 * self-service restoring it out from under itself is not something to expose as a one-click
 * operation.
 */
@Slf4j
@Component
public class DrBackupScheduler {

    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;
    private final ArtifactStore artifactStore;
    private final DrBackupRepository drBackupRepository;

    private final String raumDbHost;
    private final int raumDbPort;
    private final String raumDbName;
    private final String raumDbUser;
    private final String raumDbPassword;

    public DrBackupScheduler(CredentialsRepository credentialsRepository,
                              OpenBaoService openBaoService,
                              @Qualifier("encryptingArtifactStore") ArtifactStore artifactStore,
                              DrBackupRepository drBackupRepository,
                              @Value("${RAUM_DB_HOST:localhost}") String raumDbHost,
                              @Value("${RAUM_DB_PORT:5432}") int raumDbPort,
                              @Value("${RAUM_DB_NAME:raum}") String raumDbName,
                              @Value("${RAUM_DB_USER:postgres}") String raumDbUser,
                              @Value("${RAUM_DB_PASSWORD:postgres}") String raumDbPassword) {
        this.credentialsRepository = credentialsRepository;
        this.openBaoService = openBaoService;
        this.artifactStore = artifactStore;
        this.drBackupRepository = drBackupRepository;
        this.raumDbHost = raumDbHost;
        this.raumDbPort = raumDbPort;
        this.raumDbName = raumDbName;
        this.raumDbUser = raumDbUser;
        this.raumDbPassword = raumDbPassword;
    }

    @Scheduled(cron = "${raum.backup.dr-cron:0 0 2 * * *}")
    public void runDrBackup() {
        Mono<Void> discoveredInstances = credentialsRepository.findAll()
                .collectList()
                .map(InstanceDiscovery::discoverInstances)
                .flatMapMany(Flux::fromIterable)
                .concatMap(instance -> backupInstance(instance)
                        .onErrorResume(e -> {
                            log.error("DR backup failed for instance {}", instance.key(), e);
                            return Mono.empty();
                        }))
                .then();
        Mono<Void> raumSelf = backupRaumInstance()
                .onErrorResume(e -> {
                    log.error("DR backup failed for raum's own instance", e);
                    return Mono.empty();
                });
        discoveredInstances.then(raumSelf)
                .subscribe(null, e -> log.error("DR backup run failed", e));
    }

    private Mono<Void> backupInstance(InstanceDiscovery.Instance instance) {
        Credentials rep = instance.representative();
        return openBaoService.registerBackupRole(rep.getId(), rep.getDbHost(), rep.getDbPort(), rep.getDbName())
                .then(openBaoService.issueBackupCredentials(rep.getId()))
                .flatMap(creds -> dumpAndUpload(instance, rep, creds));
    }

    /** Raum's own DB, using its own admin connection directly - no Vault role needed (unlike every
     * other instance) since raum already holds full admin credentials to its own database. The
     * synthetic {@link Credentials} below is never persisted, so its {@code id} is {@code null};
     * that null flows into {@link #recordCatalogEntry} as {@code representativeCredentialsId},
     * which is exactly the signal {@code DrRestoreService} uses to refuse restoring this entry via
     * the API. */
    private Mono<Void> backupRaumInstance() {
        Credentials self = Credentials.builder()
                .dbHost(raumDbHost)
                .dbPort(raumDbPort)
                .dbName(raumDbName)
                .build();
        CredentialsDTO creds = new CredentialsDTO();
        creds.setUserName(raumDbUser);
        creds.setPassword(raumDbPassword);
        InstanceDiscovery.Instance instance =
                new InstanceDiscovery.Instance(raumDbHost + ":" + raumDbPort + "/" + raumDbName, self);
        return dumpAndUpload(instance, self, creds);
    }

    private Mono<Void> dumpAndUpload(InstanceDiscovery.Instance instance, Credentials rep, CredentialsDTO creds) {
        String key = objectKey(instance.key());
        return Mono.fromCallable(() -> runPgDump(rep, creds))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(dumpFile -> artifactStore.upload(key, dumpFile)
                        .doFinally(signal -> deleteQuietly(dumpFile)))
                .then(recordCatalogEntry(rep, key))
                .doOnSuccess(v -> log.info("DR backup completed for instance {}", instance.key()));
    }

    private Mono<Void> recordCatalogEntry(Credentials rep, String objectKey) {
        return drBackupRepository.save(DrBackup.builder()
                .scope(DrBackupScope.INSTANCE.name())
                .instanceHost(rep.getDbHost())
                .instancePort(rep.getDbPort())
                .instanceDb(rep.getDbName())
                .representativeCredentialsId(rep.getId())
                .objectKey(objectKey)
                .createdAt(Instant.now())
                .build()).then();
    }

    private Path runPgDump(Credentials rep, CredentialsDTO creds) throws IOException, InterruptedException {
        Path dumpFile = Files.createTempFile("dr-backup-", ".sql.gz");
        // --no-privileges is deliberately omitted: GRANTs must survive the dump, since --clean drops
        // and recreates every table on restore, which would otherwise strip the write privileges
        // Vault's dynamic per-connection roles were granted at credential-issuance time and silently
        // break the live app's DB access until someone manually re-runs GRANTs (confirmed by live-fire
        // restore testing 2026-08-19 - see memory).
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "pg_dump --no-owner --clean --if-exists | gzip > " + dumpFile);
        pb.environment().put("PGHOST", rep.getDbHost());
        pb.environment().put("PGPORT", String.valueOf(rep.getDbPort()));
        pb.environment().put("PGDATABASE", rep.getDbName());
        pb.environment().put("PGUSER", creds.getUserName());
        pb.environment().put("PGPASSWORD", creds.getPassword());

        Process process = pb.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            Files.deleteIfExists(dumpFile);
            throw new IllegalStateException("pg_dump exited " + exitCode + ": " + stderr);
        }
        return dumpFile;
    }

    private String objectKey(String instanceKey) {
        String sanitized = instanceKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "dr-backups/" + sanitized + "/" + KEY_TIMESTAMP.format(Instant.now()) + ".sql.gz.enc";
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp DR dump file {}", file, e);
        }
    }
}
