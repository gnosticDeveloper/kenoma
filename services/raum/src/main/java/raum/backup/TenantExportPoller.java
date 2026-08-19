package raum.backup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import common.dto.CredentialsDTO;
import raum.models.Credentials;
import raum.models.ExportFormat;
import raum.models.ExportJob;
import raum.models.ExportJobStatus;
import raum.models.ExportLayout;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.ExportJobRepository;
import raum.repository.ServiceRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tenant export (issue #125 phase 2, formats/layout added for issue #140): given an org, produces a
 * dump of the org's data and uploads it to the same bucket DR backups use.
 *
 * <p><b>Every service's contribution is row-filtered by org_id (or a join chain reaching it) —
 * none of the three service databases (raum, vassago, bime) are actually database-per-tenant.</b>
 * An earlier version of this class assumed vassago/bime were, and did a full {@code pg_dump}/
 * full-table dump of those services for whichever org requested an export — since those databases
 * are in fact shared across every org (row-scoped by {@code org_id}, same as raum), that leaked
 * every other org's data into any org's export. Fixed by giving vassago and bime their own
 * {@link #SERVICE_TABLES} entry, exactly like raum's own {@link #RAUM_TABLES} — each table listed
 * with the column set to export and a WHERE-clause template (direct {@code org_id = '%s'}, or a
 * join/subquery reaching org_id through a parent table, e.g. bime's `location_id`/`product_id`/
 * `metadata_id`-scoped tables) that every extraction (SQL/JSON/CSV) applies. A service with no
 * entry in {@link #SERVICE_TABLES} fails the export loudly rather than silently falling back to an
 * unfiltered dump — see {@link #tablesFor}.
 *
 * <p>Three output formats are supported per job ({@link ExportFormat}): SQL (restorable
 * {@code COPY ... FROM stdin} blocks per table, gzipped) is disaster-recovery-shaped and not meant
 * for a human to open, and always produces one file per service regardless of layout (a merged SQL
 * script wouldn't be replayable against any single database). JSON and CSV exist for offboarding
 * tenants who just want their own data in a readable form — for those, every table is extracted
 * independently (via {@code \copy ... TO STDOUT}) rather than dumped as a restorable script, and
 * {@link ExportLayout} controls whether each service gets its own file (SEPARATE, the default) or
 * everything lands in one combined file (MERGED), namespaced by service to avoid table-name
 * collisions. CSV writes one {@code {table}.csv} (or {@code {service}_{table}.csv} when merged) per
 * table into a zip — tables don't share a column shape, so they can't share one flat file. JSON
 * writes one {@code {"table": [...], ...}} object (SEPARATE, one per service) or
 * {@code {"service": {"table": [...], ...}, ...}} (MERGED, one overall), gzipped.
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
    private static final String MERGED_KEY_SEGMENT = "export";

    /**
     * Org-scoped raum tables actually eligible for tenant export, with their export column list and
     * a WHERE-clause template (a single {@code %s} placeholder for the org id). Deliberately
     * excludes `credentials` (db host/port/name — operational plumbing describing where the org's
     * data physically lives, not the org's own data) and `pending_org_verifications` (verification
     * token bookkeeping) — both are platform-internal and sensitive, not something a tenant's
     * export should ever contain.
     */
    private static final List<ExportTable> RAUM_TABLES = List.of(
            new ExportTable("organizations",
                    "id, name, contact_name, contact_email, tax_id, fiscal_name, fiscal_address, " +
                            "billing_email, billing_email_verified, billing_cycle, next_invoice_due_at, currency, " +
                            "currency_refresh_mode, currency_refresh_cadence, currency_refresh_interval_days, " +
                            "product_pricing_currency, modification_lock, locked_at, created_at, modified_at, stopped_at",
                    "id = '%s'"),
            new ExportTable("billing_history",
                    "id, org_id, billing_cycle, due_at, created_at, amount, currency, line_items, " +
                            "payment_status, paid_at, payment_reference",
                    "org_id = '%s'")
    );

    /** vassago's tables - `users` carries org_id directly, `pending_verifications` only reaches it
     * through `user_id`. */
    private static final List<ExportTable> VASSAGO_TABLES = List.of(
            new ExportTable("users",
                    "id, name, last_name, email, username, password, roles, modification_lock, " +
                            "locked_at, created_at, modified_at, stopped_at, is_ready, locale, org_id",
                    "org_id = '%s'"),
            new ExportTable("pending_verifications",
                    "id, user_id, token_hash, expires_at, used, type",
                    "user_id IN (SELECT id FROM users WHERE org_id = '%s')")
    );

    /** bime's tables. Most carry org_id directly; the rest reach it through a parent
     * (`locations`/`products`/`product_metadata`/`product_variants`/`product_metadata_option`/
     * `product_metadata_assignments`). Ordered so a SQL restore never violates a foreign key -
     * every parent table appears before the children that reference it. */
    private static final List<ExportTable> BIME_TABLES = List.of(
            new ExportTable("locations",
                    "id, org_id, name, code, is_active, created_at, modified_at, notification_email, notification_email_verified",
                    "org_id = '%s'"),
            new ExportTable("products",
                    "id, org_id, sku, name, description, is_active, created_at, modified_at",
                    "org_id = '%s'"),
            new ExportTable("product_metadata",
                    "id, org_id, name, created_at",
                    "org_id = '%s'"),
            new ExportTable("product_variants",
                    "id, product_id, org_id, sku, is_active, created_at, price, price_currency",
                    "org_id = '%s'"),
            new ExportTable("product_metadata_option",
                    "id, metadata_id, value, code, created_at",
                    "metadata_id IN (SELECT id FROM product_metadata WHERE org_id = '%s')"),
            new ExportTable("product_metadata_assignments",
                    "id, product_id, metadata_id",
                    "product_id IN (SELECT id FROM products WHERE org_id = '%s')"),
            new ExportTable("product_variant_options",
                    "variant_id, option_id",
                    "variant_id IN (SELECT id FROM product_variants WHERE org_id = '%s')"),
            new ExportTable("product_option_selections",
                    "assignment_id, option_id",
                    "assignment_id IN (SELECT id FROM product_metadata_assignments WHERE product_id IN " +
                            "(SELECT id FROM products WHERE org_id = '%s'))"),
            new ExportTable("pending_location_verifications",
                    "id, location_id, email, token_hash, expires_at, used, created_at",
                    "location_id IN (SELECT id FROM locations WHERE org_id = '%s')"),
            new ExportTable("stock_movements",
                    "id, org_id, product_id, variant_id, location_id, movement_type, delta, reference_id, note, created_at, created_by",
                    "org_id = '%s'"),
            new ExportTable("variant_stock_alert_thresholds",
                    "org_id, variant_id, location_id, threshold, created_at, modified_at",
                    "org_id = '%s'"),
            new ExportTable("variant_stock_alerts",
                    "org_id, variant_id, location_id, threshold, quantity, triggered_at",
                    "org_id = '%s'"),
            new ExportTable("variant_stock_balances",
                    "org_id, variant_id, location_id, quantity, modified_at",
                    "org_id = '%s'")
    );

    private static final Map<String, List<ExportTable>> SERVICE_TABLES = Map.of(
            "vassago", VASSAGO_TABLES,
            "bime", BIME_TABLES
    );

    /** Fails loudly for a service with no entry above, instead of silently falling back to an
     * unfiltered full-DB dump - that fallback is exactly the cross-tenant leak this class used to
     * have (see class javadoc). Every service a tenant can have credentials for must define its
     * org-scoped table list here before it can be exported safely. */
    private static List<ExportTable> tablesFor(String serviceName) {
        List<ExportTable> tables = SERVICE_TABLES.get(serviceName);
        if (tables == null) {
            throw new IllegalStateException("No org-scoped export table list defined for service '" + serviceName +
                    "' - refusing to export it to avoid an unfiltered cross-tenant dump");
        }
        return tables;
    }

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
                        .flatMap(uploadedKeys -> Mono.defer(() -> complete(job, ExportJobStatus.DONE, null, uploadedKeys)))
                        .onErrorResume(e -> {
                            log.error("Tenant export failed for org {}", job.getOrgId(), e);
                            return complete(job, ExportJobStatus.FAILED, e.getMessage(), List.of());
                        }))
                .subscribe(null, e -> log.error("Tenant export poll failed", e));
    }

    private Mono<ExportJob> claim(ExportJob job) {
        job.setStatus(ExportJobStatus.RUNNING.name());
        job.setStartedAt(Instant.now());
        return exportJobRepository.save(job);
    }

    private Mono<ExportJob> complete(ExportJob job, ExportJobStatus status, String errorMessage, List<String> uploadedKeys) {
        job.setStatus(status.name());
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(errorMessage);
        job.setObjectKeys(uploadedKeys.isEmpty() ? null : String.join(",", uploadedKeys));
        return exportJobRepository.save(job);
    }

    /** Package-private so IT tests can drive an export directly, without going through the shared
     * PENDING queue (which every test class extending BaseIT shares one Postgres testcontainer for).
     * Emits the object keys actually uploaded to the bucket on success. */
    Mono<List<String>> runExport(ExportJob job) {
        UUID orgId = job.getOrgId();
        ExportFormat format = resolveFormat(job);
        ExportLayout layout = resolveLayout(job);
        // Tracks which parts (raum, vassago, bime...) finished before a failure, if any - for
        // SEPARATE layout this means "already uploaded to the bucket"; for MERGED it means "already
        // folded into the in-progress combined file" (nothing is uploaded until the whole thing is
        // built). Either way, the job's error message says what's actually done instead of leaving
        // that ambiguous.
        List<String> completedParts = new CopyOnWriteArrayList<>();
        List<String> uploadedKeys = new CopyOnWriteArrayList<>();
        Mono<Void> exportMono = (format != ExportFormat.SQL && layout == ExportLayout.MERGED)
                ? runMergedExport(orgId, format, completedParts, uploadedKeys)
                : runSeparateExport(orgId, format, completedParts, uploadedKeys);
        return exportMono
                .doOnSuccess(v -> log.info("Tenant export completed for org {} (format {}, layout {})", orgId, format, layout))
                .onErrorMap(e -> !(e instanceof PartialExportFailureException)
                        ? new PartialExportFailureException(completedParts, e) : e)
                .thenReturn(uploadedKeys);
    }

    private ExportFormat resolveFormat(ExportJob job) {
        return job.getFormat() == null ? ExportFormat.SQL : ExportFormat.valueOf(job.getFormat());
    }

    private ExportLayout resolveLayout(ExportJob job) {
        return job.getLayout() == null ? ExportLayout.SEPARATE : ExportLayout.valueOf(job.getLayout());
    }

    // ---------------------------------------------------------------------------------------------
    // SEPARATE layout: one file per service (raum, and each service the org has credentials for)
    // ---------------------------------------------------------------------------------------------

    private Mono<Void> runSeparateExport(UUID orgId, ExportFormat format, List<String> completedParts, List<String> uploadedKeys) {
        Mono<Void> raum = dumpAndUploadRaumTables(orgId, format, uploadedKeys).doOnSuccess(v -> completedParts.add(RAUM_SERVICE_NAME));
        Mono<Void> perServiceDbs = credentialsRepository.findAllByOrgId(orgId)
                .concatMap(creds -> dumpAndUploadServiceDb(orgId, creds, format, completedParts, uploadedKeys))
                .then();
        return raum.then(perServiceDbs);
    }

    private Mono<Void> dumpAndUploadServiceDb(UUID orgId, Credentials creds, ExportFormat format, List<String> completedParts, List<String> uploadedKeys) {
        return resolveServiceName(creds)
                .flatMap(serviceName -> openBaoService.registerBackupRole(creds.getId(), creds.getDbHost(), creds.getDbPort(), creds.getDbName())
                        .then(openBaoService.issueBackupCredentials(creds.getId()))
                        .map(dbCreds -> new PgConn(creds.getDbHost(), creds.getDbPort(), creds.getDbName(), dbCreds.getUserName(), dbCreds.getPassword()))
                        .flatMap(conn -> buildExportFile(conn, format, tablesFor(serviceName), orgId, "tenant-export-" + serviceName + "-"))
                        .flatMap(dumpFile -> {
                            String key = objectKey(orgId, serviceName, format);
                            return artifactStore.upload(key, dumpFile)
                                    .doFinally(signal -> deleteQuietly(dumpFile))
                                    .doOnSuccess(v -> uploadedKeys.add(key));
                        })
                        .doOnSuccess(v -> completedParts.add(serviceName)));
    }

    private Mono<String> resolveServiceName(Credentials creds) {
        return serviceRepository.findById(creds.getServiceId())
                .map(service -> service.getName().toLowerCase(Locale.ROOT))
                .switchIfEmpty(Mono.just(creds.getServiceId().toString()));
    }

    private Mono<Void> dumpAndUploadRaumTables(UUID orgId, ExportFormat format, List<String> uploadedKeys) {
        return buildExportFile(raumConn(), format, RAUM_TABLES, orgId, "tenant-export-raum-")
                .flatMap(dumpFile -> {
                    String key = objectKey(orgId, RAUM_SERVICE_NAME, format);
                    return artifactStore.upload(key, dumpFile)
                            .doFinally(signal -> deleteQuietly(dumpFile))
                            .doOnSuccess(v -> uploadedKeys.add(key));
                });
    }

    /** Builds one service's export file, org-scoped via `tables`' WHERE-clause templates - shared by
     * raum's own dump and every other service's, so there's exactly one code path that can produce
     * an export file, and it's always table-list-driven (never an unfiltered full-DB dump). */
    private Mono<Path> buildExportFile(PgConn conn, ExportFormat format, List<ExportTable> tables, UUID orgId, String tempFilePrefix) {
        return Mono.fromCallable(() -> switch (format) {
            case SQL -> buildExportSql(conn, tables, orgId, tempFilePrefix);
            case JSON -> writeGzippedJson("{" + jsonObjectBody(conn, tables, orgId) + "}", tempFilePrefix);
            case CSV -> buildExportCsv(conn, tables, orgId, tempFilePrefix);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Path buildExportSql(PgConn conn, List<ExportTable> tables, UUID orgId, String tempFilePrefix) throws IOException, InterruptedException {
        Path plainFile = Files.createTempFile(tempFilePrefix, ".sql");
        Path gzFile = Files.createTempFile(tempFilePrefix, ".sql.gz");
        try {
            for (ExportTable table : tables) {
                appendTableCopy(plainFile, conn, table, orgId);
            }
            gzip(plainFile, gzFile);
            return gzFile;
        } finally {
            Files.deleteIfExists(plainFile);
        }
    }

    private Path buildExportCsv(PgConn conn, List<ExportTable> tables, UUID orgId, String tempFilePrefix) throws IOException, InterruptedException {
        Path zipFile = Files.createTempFile(tempFilePrefix, ".csv.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            writeCsvEntries(zos, conn, tables, orgId, "");
        }
        return zipFile;
    }

    private void writeCsvEntries(ZipOutputStream zos, PgConn conn, List<ExportTable> tables, UUID orgId, String entryPrefix) throws IOException, InterruptedException {
        for (ExportTable table : tables) {
            writeTableCsvEntry(zos, conn, filteredSelect(table, orgId), entryPrefix + table.name());
        }
    }

    private String jsonObjectBody(PgConn conn, List<ExportTable> tables, UUID orgId) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                body.append(",");
            }
            ExportTable table = tables.get(i);
            body.append(jsonKey(table.name())).append(":").append(tableJsonArray(conn, filteredSelect(table, orgId)));
        }
        return body.toString();
    }

    private String filteredSelect(ExportTable table, UUID orgId) {
        return String.format("SELECT %s FROM %s WHERE %s", table.columns(), table.name(),
                String.format(table.whereClauseTemplate(), orgId));
    }

    private PgConn raumConn() {
        return new PgConn(raumDbHost, raumDbPort, raumDbName, raumDbUser, raumDbPassword);
    }

    private void appendTableCopy(Path plainFile, PgConn conn, ExportTable table, UUID orgId) throws IOException, InterruptedException {
        byte[] rows = runPsql(conn, "\\copy (" + filteredSelect(table, orgId) + ") TO STDOUT");

        String header = "COPY " + table.name() + " (" + table.columns() + ") FROM stdin;\n";
        Files.writeString(plainFile, header, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.write(plainFile, rows, StandardOpenOption.APPEND);
        Files.writeString(plainFile, "\\.\n\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    // ---------------------------------------------------------------------------------------------
    // MERGED layout (JSON/CSV only): every service's data combined into a single uploaded file,
    // namespaced by service name so two services' same-named tables don't collide. Building the whole
    // thing happens before any upload - a mid-build failure leaves nothing new in the bucket, unlike
    // SEPARATE where earlier parts may already be uploaded.
    // ---------------------------------------------------------------------------------------------

    private Mono<Void> runMergedExport(UUID orgId, ExportFormat format, List<String> completedParts, List<String> uploadedKeys) {
        return resolveServiceConns(orgId)
                .flatMap(serviceConns -> Mono.fromCallable(() -> format == ExportFormat.JSON
                                ? buildMergedJson(orgId, serviceConns, completedParts)
                                : buildMergedCsv(orgId, serviceConns, completedParts))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(mergedFile -> {
                    String key = mergedObjectKey(orgId, format);
                    return artifactStore.upload(key, mergedFile)
                            .doFinally(signal -> deleteQuietly(mergedFile))
                            .doOnSuccess(v -> uploadedKeys.add(key));
                });
    }

    private Mono<List<ServicePgConn>> resolveServiceConns(UUID orgId) {
        return credentialsRepository.findAllByOrgId(orgId)
                .concatMap(creds -> resolveServiceName(creds)
                        .flatMap(name -> openBaoService.registerBackupRole(creds.getId(), creds.getDbHost(), creds.getDbPort(), creds.getDbName())
                                .then(openBaoService.issueBackupCredentials(creds.getId()))
                                .map(dbCreds -> new ServicePgConn(name,
                                        new PgConn(creds.getDbHost(), creds.getDbPort(), creds.getDbName(), dbCreds.getUserName(), dbCreds.getPassword())))))
                .collectList();
    }

    private Path buildMergedJson(UUID orgId, List<ServicePgConn> serviceConns, List<String> completedParts) throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder("{");
        json.append(jsonKey(RAUM_SERVICE_NAME)).append(":{").append(jsonObjectBody(raumConn(), RAUM_TABLES, orgId)).append("}");
        completedParts.add(RAUM_SERVICE_NAME);
        for (ServicePgConn sc : serviceConns) {
            json.append(",").append(jsonKey(sc.name())).append(":{").append(jsonObjectBody(sc.conn(), tablesFor(sc.name()), orgId)).append("}");
            completedParts.add(sc.name());
        }
        json.append("}");
        return writeGzippedJson(json.toString(), "tenant-export-merged-");
    }

    private Path buildMergedCsv(UUID orgId, List<ServicePgConn> serviceConns, List<String> completedParts) throws IOException, InterruptedException {
        Path zipFile = Files.createTempFile("tenant-export-merged-", ".csv.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            writeCsvEntries(zos, raumConn(), RAUM_TABLES, orgId, RAUM_SERVICE_NAME + "_");
            completedParts.add(RAUM_SERVICE_NAME);
            for (ServicePgConn sc : serviceConns) {
                writeCsvEntries(zos, sc.conn(), tablesFor(sc.name()), orgId, sc.name() + "_");
                completedParts.add(sc.name());
            }
        }
        return zipFile;
    }

    private String mergedObjectKey(UUID orgId, ExportFormat format) {
        String extension = format == ExportFormat.JSON ? "json.gz" : "csv.zip";
        return "tenant-exports/" + orgId + "/" + MERGED_KEY_SEGMENT + "/" + KEY_TIMESTAMP.format(Instant.now()) + "." + extension;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared CSV/JSON/psql plumbing
    // ---------------------------------------------------------------------------------------------

    /** Writes one table's rows as a CSV file into an open zip - JSON/CSV exports don't restore into a
     * schema, they're one independent file per table (tables don't share a column shape, unlike the
     * SQL format's single concatenated COPY-block file). */
    private void writeTableCsvEntry(ZipOutputStream zos, PgConn conn, String selectExpr, String entryBaseName) throws IOException, InterruptedException {
        byte[] csv = runPsql(conn, "\\copy (" + selectExpr + ") TO STDOUT WITH CSV HEADER");
        zos.putNextEntry(new ZipEntry(entryBaseName + ".csv"));
        zos.write(csv);
        zos.closeEntry();
    }

    /** Returns a JSON array (as raw text, via `json_agg`/`row_to_json`) of every row `selectExpr`
     * matches - `COALESCE(..., '[]')` so an empty table exports as `[]` rather than a literal `null`.
     * Deliberately a plain `SELECT`, not `\copy ... TO STDOUT`: COPY's TEXT format backslash-escapes
     * its output (`\` becomes `\\`, etc), which corrupts JSON text containing its own `\"` escapes
     * (e.g. any text column whose value itself contains a `"`, like a JSON-in-text column) - a plain
     * query has no such escaping, psql just prints the value's text representation as-is. */
    private String tableJsonArray(PgConn conn, String selectExpr) throws IOException, InterruptedException {
        byte[] out = runPsql(conn, "SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (" + selectExpr + ") t");
        String text = new String(out, StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? "[]" : text;
    }

    private Path writeGzippedJson(String json, String tempFilePrefix) throws IOException {
        Path gzFile = Files.createTempFile(tempFilePrefix, ".json.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzFile))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return gzFile;
    }

    /** Table/service names come from a hardcoded whitelist (raum), `information_schema.tables`
     * (services), or the `services` table's own `name` column - none is attacker-controlled, but this
     * still guards against a bare `"` breaking the object. */
    private String jsonKey(String key) {
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private byte[] runPsql(PgConn conn, String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("psql", "-h", conn.host(), "-p", String.valueOf(conn.port()),
                "-U", conn.user(), "-d", conn.db(), "-t", "-A", "-c", command);
        pb.environment().put("PGPASSWORD", conn.password());

        Process process = pb.start();
        byte[] out = process.getInputStream().readAllBytes();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("psql exited " + exitCode + ": " + stderr);
        }
        return out;
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

    private String objectKey(UUID orgId, String serviceName, ExportFormat format) {
        String extension = switch (format) {
            case SQL -> "sql.gz";
            case JSON -> "json.gz";
            case CSV -> "csv.zip";
        };
        return "tenant-exports/" + orgId + "/" + serviceName + "/" + KEY_TIMESTAMP.format(Instant.now()) + "." + extension;
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temp tenant export file {}", file, e);
        }
    }

    /** One exportable table: its column list, and a WHERE-clause template with a single `%s`
     * placeholder for the org id - either a direct `org_id = '%s'` or a join/subquery reaching
     * org_id through a parent table. */
    private record ExportTable(String name, String columns, String whereClauseTemplate) {}

    private record PgConn(String host, int port, String db, String user, String password) {}

    private record ServicePgConn(String name, PgConn conn) {}

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
