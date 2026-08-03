package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import common.dto.CredentialsDTO;
import raum.models.Credentials;
import raum.models.ExportJob;
import raum.models.ExportJobStatus;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.ExportJobRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tenant export (issue #125 phase 2): given an org, produces one dump per service the org has
 * data in and uploads them to the same bucket DR backups use. Unlike DR backup, bime/vassago don't
 * need row filtering — each org already has its own dedicated database there, so a full pg_dump of
 * that database is the export. Only raum is genuinely shared/row-scoped, so raum's contribution is
 * a handful of `COPY ... WHERE org_id = ?` extracts instead of a full instance dump.
 *
 * <p>Job table + poller is an interim mechanism — the intent is to eventually move this (and the
 * rest of the platform's scheduled jobs, DR backup's cron included) onto Kafka. Until then this
 * mirrors {@link DrBackupScheduler}'s single-node polling shape.
 */
@Slf4j
@Component
public class TenantExportPoller {

    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final String RAUM_SERVICE_NAME = "raum";

    /**
     * Org-scoped raum tables actually eligible for tenant export, with their export column list and
     * filter column. Deliberately excludes `credentials` (db host/port/name — operational plumbing
     * describing where the org's data physically lives, not the org's own data) and
     * `pending_org_verifications` (verification token bookkeeping) — both are platform-internal and
     * sensitive, not something a tenant's export should ever contain.
     */
    private static final List<RaumTable> RAUM_TABLES = List.of(
            new RaumTable("organizations",
                    "id, name, contact_name, contact_email, tax_id, fiscal_name, fiscal_address, " +
                            "billing_email, billing_email_verified, billing_cycle, next_invoice_due_at, currency, " +
                            "currency_refresh_mode, currency_refresh_cadence, currency_refresh_interval_days, " +
                            "product_pricing_currency, modification_lock, locked_at, created_at, modified_at, stopped_at",
                    "id"),
            new RaumTable("billing_history",
                    "id, org_id, billing_cycle, due_at, created_at, amount, currency, line_items, " +
                            "payment_status, paid_at, payment_reference",
                    "org_id")
    );

    private final ExportJobRepository exportJobRepository;
    private final CredentialsRepository credentialsRepository;
    private final ServiceRepository serviceRepository;
    private final OpenBaoService openBaoService;
    private final ArtifactStore artifactStore;

    private final String raumDbHost;
    private final int raumDbPort;
    private final String raumDbName;
    private final String raumDbUser;
    private final String raumDbPassword;

    public TenantExportPoller(ExportJobRepository exportJobRepository,
                               CredentialsRepository credentialsRepository,
                               ServiceRepository serviceRepository,
                               OpenBaoService openBaoService,
                               ArtifactStore artifactStore,
                               @Value("${RAUM_DB_HOST:localhost}") String raumDbHost,
                               @Value("${RAUM_DB_PORT:5432}") int raumDbPort,
                               @Value("${RAUM_DB_NAME:raum}") String raumDbName,
                               @Value("${RAUM_DB_USER:postgres}") String raumDbUser,
                               @Value("${RAUM_DB_PASSWORD:postgres}") String raumDbPassword) {
        this.exportJobRepository = exportJobRepository;
        this.credentialsRepository = credentialsRepository;
        this.serviceRepository = serviceRepository;
        this.openBaoService = openBaoService;
        this.artifactStore = artifactStore;
        this.raumDbHost = raumDbHost;
        this.raumDbPort = raumDbPort;
        this.raumDbName = raumDbName;
        this.raumDbUser = raumDbUser;
        this.raumDbPassword = raumDbPassword;
    }

    @Scheduled(fixedDelayString = "${raum.backup.export-poll-interval-ms:30000}")
    public void poll() {
        exportJobRepository.findAllByStatusOrderByRequestedAtAsc(ExportJobStatus.PENDING.name())
                .next()
                .flatMap(this::claim)
                .flatMap(job -> runExport(job)
                        // Mono.defer is required here: complete(...) mutates `job` and calls
                        // save() as soon as it's invoked, and passing it directly to .then(...)
                        // would evaluate that call eagerly while assembling the chain - i.e.
                        // immediately after claim(), before runExport actually finishes - marking
                        // the job DONE regardless of whether the export succeeded.
                        .then(Mono.defer(() -> complete(job, ExportJobStatus.DONE, null)))
                        .onErrorResume(e -> {
                            log.error("Tenant export failed for org {}", job.getOrgId(), e);
                            return complete(job, ExportJobStatus.FAILED, e.getMessage());
                        }))
                .subscribe(null, e -> log.error("Tenant export poll failed", e));
    }

    private Mono<ExportJob> claim(ExportJob job) {
        job.setStatus(ExportJobStatus.RUNNING.name());
        job.setStartedAt(Instant.now());
        return exportJobRepository.save(job);
    }

    private Mono<ExportJob> complete(ExportJob job, ExportJobStatus status, String errorMessage) {
        job.setStatus(status.name());
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(errorMessage);
        return exportJobRepository.save(job);
    }

    /** Package-private so IT tests can drive an export directly, without going through the shared
     * PENDING queue (which every test class extending BaseIT shares one Postgres testcontainer for). */
    Mono<Void> runExport(ExportJob job) {
        UUID orgId = job.getOrgId();
        // Tracks which parts (raum, vassago, bime...) finished uploading before a failure, if any -
        // a mid-export failure still leaves earlier uploads sitting in the bucket, so the job's error
        // message should say what's actually there rather than leaving that ambiguous.
        List<String> completedParts = new CopyOnWriteArrayList<>();
        Mono<Void> raum = dumpAndUploadRaumTables(orgId).doOnSuccess(v -> completedParts.add(RAUM_SERVICE_NAME));
        Mono<Void> perServiceDbs = credentialsRepository.findAllByOrgId(orgId)
                .concatMap(creds -> dumpAndUploadServiceDb(orgId, creds, completedParts))
                .then();
        return raum.then(perServiceDbs)
                .doOnSuccess(v -> log.info("Tenant export completed for org {}", orgId))
                .onErrorMap(e -> !(e instanceof PartialExportFailureException)
                        ? new PartialExportFailureException(completedParts, e) : e);
    }

    private Mono<Void> dumpAndUploadServiceDb(UUID orgId, Credentials creds, List<String> completedParts) {
        return resolveServiceName(creds)
                .flatMap(serviceName -> openBaoService.registerBackupRole(creds.getId(), creds.getDbHost(), creds.getDbPort(), creds.getDbName())
                        .then(openBaoService.issueBackupCredentials(creds.getId()))
                        .flatMap(dbCreds -> dumpServiceDb(creds, dbCreds))
                        .flatMap(dumpFile -> artifactStore.upload(objectKey(orgId, serviceName), dumpFile)
                                .doFinally(signal -> deleteQuietly(dumpFile)))
                        .doOnSuccess(v -> completedParts.add(serviceName)));
    }

    private Mono<String> resolveServiceName(Credentials creds) {
        return serviceRepository.findById(creds.getServiceId())
                .map(service -> service.getName().toLowerCase(Locale.ROOT))
                .switchIfEmpty(Mono.just(creds.getServiceId().toString()));
    }

    private Mono<Path> dumpServiceDb(Credentials creds, CredentialsDTO dbCreds) {
        return Mono.fromCallable(() -> runPgDump(creds, dbCreds))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Path runPgDump(Credentials creds, CredentialsDTO dbCreds) throws IOException, InterruptedException {
        Path dumpFile = Files.createTempFile("tenant-export-", ".sql.gz");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "pg_dump --no-owner --no-privileges --clean --if-exists | gzip > " + dumpFile);
        pb.environment().put("PGHOST", creds.getDbHost());
        pb.environment().put("PGPORT", String.valueOf(creds.getDbPort()));
        pb.environment().put("PGDATABASE", creds.getDbName());
        pb.environment().put("PGUSER", dbCreds.getUserName());
        pb.environment().put("PGPASSWORD", dbCreds.getPassword());

        Process process = pb.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            Files.deleteIfExists(dumpFile);
            throw new IllegalStateException("pg_dump exited " + exitCode + ": " + stderr);
        }
        return dumpFile;
    }

    private Mono<Void> dumpAndUploadRaumTables(UUID orgId) {
        return Mono.fromCallable(() -> buildRaumExportFile(orgId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(dumpFile -> artifactStore.upload(objectKey(orgId, RAUM_SERVICE_NAME), dumpFile)
                        .doFinally(signal -> deleteQuietly(dumpFile)));
    }

    private Path buildRaumExportFile(UUID orgId) throws IOException, InterruptedException {
        Path plainFile = Files.createTempFile("tenant-export-raum-", ".sql");
        Path gzFile = Files.createTempFile("tenant-export-raum-", ".sql.gz");
        try {
            for (RaumTable table : RAUM_TABLES) {
                appendTableCopy(plainFile, table, orgId);
            }
            gzip(plainFile, gzFile);
            return gzFile;
        } finally {
            Files.deleteIfExists(plainFile);
        }
    }

    private void appendTableCopy(Path plainFile, RaumTable table, UUID orgId) throws IOException, InterruptedException {
        String query = String.format("\\copy (SELECT %s FROM %s WHERE %s = '%s') TO STDOUT",
                table.columns(), table.name(), table.filterColumn(), orgId);
        ProcessBuilder pb = new ProcessBuilder("psql", "-h", raumDbHost, "-p", String.valueOf(raumDbPort),
                "-U", raumDbUser, "-d", raumDbName, "-c", query);
        pb.environment().put("PGPASSWORD", raumDbPassword);

        Process process = pb.start();
        byte[] rows = process.getInputStream().readAllBytes();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("psql export of " + table.name() + " exited " + exitCode + ": " + stderr);
        }

        String header = "COPY " + table.name() + " (" + table.columns() + ") FROM stdin;\n";
        Files.writeString(plainFile, header, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.write(plainFile, rows, StandardOpenOption.APPEND);
        Files.writeString(plainFile, "\\.\n\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private void gzip(Path plainFile, Path gzFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "gzip -c " + plainFile + " > " + gzFile);
        Process process = pb.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("gzip exited " + exitCode + ": " + stderr);
        }
    }

    private String objectKey(UUID orgId, String serviceName) {
        return "tenant-exports/" + orgId + "/" + serviceName + "/" + KEY_TIMESTAMP.format(Instant.now()) + ".sql.gz";
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp tenant export file {}", file, e);
        }
    }

    private record RaumTable(String name, String columns, String filterColumn) {}

    /** Wraps an export failure with which parts (raum/vassago/bime) already finished uploading
     * before it failed, so the job's stored error message doesn't leave that ambiguous - a retry
     * still redoes everything (there's no per-part resume), but at least the failure is legible. */
    static final class PartialExportFailureException extends RuntimeException {
        PartialExportFailureException(List<String> completedParts, Throwable cause) {
            super(buildMessage(completedParts, cause), cause);
        }

        private static String buildMessage(List<String> completedParts, Throwable cause) {
            String completed = completedParts.isEmpty() ? "none" : String.join(", ", completedParts);
            return "Export failed after completing: " + completed + ". Cause: " + cause.getMessage();
        }
    }
}
