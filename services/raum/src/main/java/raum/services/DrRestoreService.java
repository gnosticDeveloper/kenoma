package raum.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import raum.backup.ArtifactStore;
import raum.backup.ExportTable;
import raum.backup.PgConn;
import raum.backup.SqlCopyDumpWriter;
import raum.backup.TenantExportPoller;
import raum.models.DrBackup;
import raum.models.DrBackupScope;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.DrBackupRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Replays a {@link DrBackup} back into a live database - the missing leg issue #141 asked for.
 * Dispatches on the catalog row's scope:
 *
 * <ul>
 *   <li>{@link DrBackupScope#INSTANCE}: the dump was taken with {@code --clean --if-exists}
 *       (see {@code DrBackupScheduler.runPgDump}), so replaying it needs admin/owner privileges -
 *       fetched via {@link OpenBaoService#getStaticCredentials}, not the read-only backup role or
 *       the ephemeral CRUD role used elsewhere. Safe to run against a live/dirty target.</li>
 *   <li>{@link DrBackupScope#ORG}: the dump is insert-only (COPY blocks, no DDL) and org-scoped -
 *       restoring it means deleting just that org's rows first (reverse table order, satisfying
 *       FKs) then replaying the COPY blocks (forward/parent-first order), wrapped in one
 *       transaction so a mid-restore failure leaves the target untouched rather than half-rewound.
 *       Uses the ephemeral read/write role ({@link OpenBaoService#issueEphemeralCredentials}) -
 *       no DDL needed for an insert-only replay.</li>
 * </ul>
 */
@Slf4j
@Service
public class DrRestoreService {

    private static final String RAUM_SERVICE_NAME = "raum";

    private final DrBackupRepository drBackupRepository;
    private final CredentialsRepository credentialsRepository;
    private final ServiceRepository serviceRepository;
    private final OpenBaoService openBaoService;
    private final ArtifactStore artifactStore;
    private final SqlCopyDumpWriter sqlCopyDumpWriter;

    private final String raumDbHost;
    private final int raumDbPort;
    private final String raumDbName;
    private final String raumDbUser;
    private final String raumDbPassword;

    public DrRestoreService(DrBackupRepository drBackupRepository,
                             CredentialsRepository credentialsRepository,
                             ServiceRepository serviceRepository,
                             OpenBaoService openBaoService,
                             @Qualifier("encryptingArtifactStore") ArtifactStore artifactStore,
                             SqlCopyDumpWriter sqlCopyDumpWriter,
                             @Value("${RAUM_DB_HOST:localhost}") String raumDbHost,
                             @Value("${RAUM_DB_PORT:5432}") int raumDbPort,
                             @Value("${RAUM_DB_NAME:raum}") String raumDbName,
                             @Value("${RAUM_DB_USER:postgres}") String raumDbUser,
                             @Value("${RAUM_DB_PASSWORD:postgres}") String raumDbPassword) {
        this.drBackupRepository = drBackupRepository;
        this.credentialsRepository = credentialsRepository;
        this.serviceRepository = serviceRepository;
        this.openBaoService = openBaoService;
        this.artifactStore = artifactStore;
        this.sqlCopyDumpWriter = sqlCopyDumpWriter;
        this.raumDbHost = raumDbHost;
        this.raumDbPort = raumDbPort;
        this.raumDbName = raumDbName;
        this.raumDbUser = raumDbUser;
        this.raumDbPassword = raumDbPassword;
    }

    public Mono<Void> restore(UUID backupId) {
        return drBackupRepository.findById(backupId)
                .switchIfEmpty(Mono.error(new NotFoundException("DR backup not found")))
                .flatMap(backup -> DrBackupScope.valueOf(backup.getScope()) == DrBackupScope.INSTANCE
                        ? restoreInstance(backup)
                        : restoreOrg(backup));
    }

    private Mono<Void> restoreInstance(DrBackup backup) {
        if (backup.getRepresentativeCredentialsId() == null) {
            return Mono.error(new BadRequestException(
                    "This backup is raum's own platform database - restore is not exposed through this API. " +
                    "Recover manually: download+decrypt the object and replay it with psql against raum's DB."));
        }
        return openBaoService.getStaticCredentials(backup.getRepresentativeCredentialsId())
                .map(adminCreds -> new PgConn(backup.getInstanceHost(), backup.getInstancePort(), backup.getInstanceDb(),
                        adminCreds.getUserName(), adminCreds.getPassword()))
                .flatMap(conn -> downloadAndDecompress(backup.getObjectKey())
                        .flatMap(sqlFile -> runRestore(conn, sqlFile))
                        .then(regrantTablePrivileges(conn)))
                .doOnSuccess(v -> log.warn("INSTANCE DR restore completed for {}:{}/{}",
                        backup.getInstanceHost(), backup.getInstancePort(), backup.getInstanceDb()));
    }

    /** {@code --clean} drops and recreates every table, wiping whatever grants existed on the old
     * ones. Vault's dynamic per-connection roles are rotated continuously, so re-granting to PUBLIC -
     * which every role, including ones issued after this restore runs, is automatically a member of -
     * is the only way to reliably restore live write access rather than chasing specific role names.
     * This matches the blanket SELECT/INSERT/UPDATE/DELETE every dynamic role already gets at
     * issuance time (see {@code scripts/kenoma-pre-init.sh}'s role creation_statements), so it's not
     * a broader grant than the existing security model already hands out uniformly. */
    private Mono<Void> regrantTablePrivileges(PgConn conn) {
        return Mono.<Void>fromRunnable(() -> {
                    try {
                        sqlCopyDumpWriter.runInlineSql(conn,
                                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO PUBLIC;");
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> restoreOrg(DrBackup backup) {
        UUID orgId = backup.getOrgId();
        String serviceName = backup.getServiceName();
        List<ExportTable> tables = RAUM_SERVICE_NAME.equals(serviceName)
                ? TenantExportPoller.DR_RAUM_TABLES
                : TenantExportPoller.tablesFor(serviceName);
        return resolveTargetConn(orgId, serviceName)
                .flatMap(conn -> downloadAndDecompress(backup.getObjectKey())
                        .flatMap(dumpFile -> Mono.fromCallable(() -> buildOrgRestoreScript(tables, orgId, dumpFile))
                                .subscribeOn(Schedulers.boundedElastic())
                                .doFinally(signal -> deleteQuietly(dumpFile)))
                        .flatMap(scriptFile -> runRestore(conn, scriptFile)))
                .doOnSuccess(v -> log.warn("ORG DR restore (rewind) completed for org {} service {}", orgId, serviceName));
    }

    private Mono<PgConn> resolveTargetConn(UUID orgId, String serviceName) {
        if (RAUM_SERVICE_NAME.equals(serviceName)) {
            return Mono.just(raumConn());
        }
        return credentialsRepository.findAllByOrgId(orgId)
                .filterWhen(creds -> serviceRepository.findById(creds.getServiceId())
                        .map(service -> service.getName().equalsIgnoreCase(serviceName))
                        .defaultIfEmpty(false))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No credentials found for org " + orgId + " service " + serviceName)))
                .flatMap(creds -> openBaoService.issueEphemeralCredentials(creds.getId())
                        .map(dbCreds -> new PgConn(creds.getDbHost(), creds.getDbPort(), creds.getDbName(),
                                dbCreds.getUserName(), dbCreds.getPassword())));
    }

    private PgConn raumConn() {
        return new PgConn(raumDbHost, raumDbPort, raumDbName, raumDbUser, raumDbPassword);
    }

    private Mono<Path> downloadAndDecompress(String objectKey) {
        return artifactStore.download(objectKey)
                .flatMap(gzFile -> Mono.fromCallable(() -> {
                            Path sqlFile = Files.createTempFile("dr-restore-", ".sql");
                            sqlCopyDumpWriter.gunzip(gzFile, sqlFile);
                            return sqlFile;
                        }).subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signal -> deleteQuietly(gzFile)));
    }

    private Mono<Void> runRestore(PgConn conn, Path sqlFile) {
        return Mono.<Void>fromRunnable(() -> {
                    try {
                        sqlCopyDumpWriter.runPsqlFile(conn, sqlFile);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> deleteQuietly(sqlFile));
    }

    /** {@code BEGIN;} + one {@code DELETE FROM table WHERE <org-scoped clause>} per table, in
     * reverse table-list order (children before parents, satisfying FKs) + the dump's COPY blocks
     * verbatim (already forward/parent-first order) + {@code COMMIT;}. A mid-script failure leaves
     * the transaction uncommitted; Postgres rolls it back when the psql connection closes. */
    private Path buildOrgRestoreScript(List<ExportTable> tables, UUID orgId, Path dumpFile) throws IOException {
        Path scriptFile = Files.createTempFile("dr-org-restore-", ".sql");
        List<ExportTable> reversed = new ArrayList<>(tables);
        Collections.reverse(reversed);

        StringBuilder deletes = new StringBuilder("BEGIN;\nSET CONSTRAINTS ALL DEFERRED;\n");
        for (ExportTable table : reversed) {
            String whereClause = String.format(table.whereClauseTemplate(), orgId);
            deletes.append("DELETE FROM ").append(table.name()).append(" WHERE ").append(whereClause).append(";\n");
        }
        Files.writeString(scriptFile, deletes.toString(), StandardCharsets.UTF_8);

        try (var in = Files.newInputStream(dumpFile); var out = Files.newOutputStream(scriptFile, StandardOpenOption.APPEND)) {
            in.transferTo(out);
        }
        Files.writeString(scriptFile, "\nCOMMIT;\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return scriptFile;
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp DR restore file {}", file, e);
        }
    }
}
