package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import common.dto.CredentialsDTO;
import raum.models.Credentials;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disaster-recovery backup: one pg_dump per distinct physical (host, port, db_name) discovered
 * across raum's credentials table, uploaded to object storage. This is instance-level protection,
 * not tenant export — a shared instance's dump covers every org on it, filtered by nothing.
 */
@Slf4j
@Component
public class DrBackupScheduler {

    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;
    private final ArtifactStore artifactStore;

    public DrBackupScheduler(CredentialsRepository credentialsRepository,
                              OpenBaoService openBaoService,
                              ArtifactStore artifactStore) {
        this.credentialsRepository = credentialsRepository;
        this.openBaoService = openBaoService;
        this.artifactStore = artifactStore;
    }

    @Scheduled(cron = "${raum.backup.dr-cron:0 0 2 * * *}")
    public void runDrBackup() {
        credentialsRepository.findAll()
                .collectList()
                .map(this::discoverInstances)
                .flatMapMany(Flux::fromIterable)
                .concatMap(instance -> backupInstance(instance)
                        .onErrorResume(e -> {
                            log.error("DR backup failed for instance {}", instance.key(), e);
                            return Mono.empty();
                        }))
                .then()
                .subscribe(null, e -> log.error("DR backup run failed", e));
    }

    /** One representative credentials row per distinct (db_host, db_port, db_name) — that connection
     * (already registered in Vault by {@link raum.services.CredentialsService}) stands in for the
     * whole physical instance, since every org on it shares the same host/port/db_name. */
    private List<Instance> discoverInstances(List<Credentials> all) {
        Map<String, Instance> byKey = new LinkedHashMap<>();
        for (Credentials c : all) {
            String key = c.getDbHost() + ":" + c.getDbPort() + "/" + c.getDbName();
            byKey.putIfAbsent(key, new Instance(key, c));
        }
        return List.copyOf(byKey.values());
    }

    private Mono<Void> backupInstance(Instance instance) {
        Credentials rep = instance.representative();
        return openBaoService.registerBackupRole(rep.getId(), rep.getDbHost(), rep.getDbPort(), rep.getDbName())
                .then(openBaoService.issueBackupCredentials(rep.getId()))
                .flatMap(creds -> dumpAndUpload(instance, rep, creds));
    }

    private Mono<Void> dumpAndUpload(Instance instance, Credentials rep, CredentialsDTO creds) {
        return Mono.fromCallable(() -> runPgDump(rep, creds))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(dumpFile -> artifactStore.upload(objectKey(instance.key()), dumpFile)
                        .doFinally(signal -> deleteQuietly(dumpFile)))
                .doOnSuccess(v -> log.info("DR backup completed for instance {}", instance.key()));
    }

    private Path runPgDump(Credentials rep, CredentialsDTO creds) throws IOException, InterruptedException {
        Path dumpFile = Files.createTempFile("dr-backup-", ".sql.gz");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "pg_dump --no-owner --no-privileges --clean --if-exists | gzip > " + dumpFile);
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
        return "dr-backups/" + sanitized + "/" + KEY_TIMESTAMP.format(Instant.now()) + ".sql.gz";
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp DR dump file {}", file, e);
        }
    }

    private record Instance(String key, Credentials representative) {}
}
