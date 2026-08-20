package raum.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import raum.BaseIT;
import raum.models.ExportFormat;
import raum.models.ExportJob;
import raum.models.ExportLayout;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Drives TenantExportPoller.runExport directly against the real testcontainer Postgres (real psql/
 * pg_dump/gzip subprocesses, not mocked) to catch things a pure-Mockito unit test can't: schema drift
 * between init.sql and the hardcoded RAUM_TABLES column lists, and whether the WHERE org_id/id filter
 * actually excludes other orgs' rows. OpenBao/S3 stay out of scope — ArtifactStore is mocked, and
 * these test orgs are given no `credentials` rows, so the vassago/bime dump path never engages.
 */
class TenantExportPollerIT extends BaseIT {

    @Autowired
    private TenantExportPoller poller;


    @MockitoBean(name = "s3ArtifactStore")
    private S3ArtifactStore artifactStore;

    @BeforeEach
    void resetCapture() {
        capturedKey = null;
        capturedBytes = null;
    }

    private String capturedKey;
    private byte[] capturedBytes;

    private void stubUploadCapturingFile() {
        doAnswer(invocation -> {
            capturedKey = invocation.getArgument(0);
            Path path = invocation.getArgument(1);
            capturedBytes = Files.readAllBytes(path);
            return Mono.empty();
        }).when(artifactStore).upload(anyString(), any(Path.class));
    }

    private UUID insertOrg(String uniqueName) throws Exception {
        return insertOrg(uniqueName, "Admin");
    }

    private UUID insertOrg(String uniqueName, String contactName) throws Exception {
        // Postgres SQL-escaping (doubled single quotes) for the literal, not JSON escaping - this is
        // building a SQL statement, independent of the JSON-corruption bug under test.
        String stdout = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                        "INSERT INTO organizations (name, contact_name, contact_email) VALUES " +
                                "('%s', '%s', 'admin@example.com') RETURNING id;"
                                        .formatted(uniqueName, contactName.replace("'", "''")))
                .getStdout();
        return UUID.fromString(stdout.strip().lines().findFirst().orElseThrow());
    }

    private String gunzip(byte[] gz) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            in.transferTo(out);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void runExport_orgWithNoCredentials_uploadsOnlyRaumFile_containingItsOwnRow() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        // No credentials rows for this org -> only the raum upload should have happened.
        verify(artifactStore, times(1)).upload(anyString(), any(Path.class));
        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/raum/").endsWith(".sql.gz");
        assertThat(gunzip(capturedBytes)).contains("COPY organizations (").contains(orgAId.toString());
    }

    @Test
    void runExport_rowFiltering_excludesOtherOrgsData() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());
        UUID orgBId = insertOrg("Tenant Export IT Org B " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/raum/");
        String content = gunzip(capturedBytes);
        assertThat(content).contains("COPY organizations (");
        assertThat(content).contains(orgAId.toString());
        assertThat(content).doesNotContain(orgBId.toString());
    }

    @Test
    void runExport_includesOnlyOrgProfileAndBillingHistory() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        // credentials (db host/port/name - operational plumbing) and pending_org_verifications
        // (verification tokens) are platform-internal and must never appear in a tenant export.
        String content = gunzip(capturedBytes);
        assertThat(content)
                .contains("COPY organizations (")
                .contains("COPY billing_history (")
                .doesNotContain("COPY credentials (")
                .doesNotContain("COPY pending_org_verifications (");
    }

    @Test
    void runExport_uploadFailure_propagatesError() throws Exception {
        doAnswer(inv -> Mono.error(new RuntimeException("s3 down")))
                .when(artifactStore).upload(anyString(), any(Path.class));
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).build();
        StepVerifier.create(poller.runExport(job))
                .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("s3 down"));
    }

    @Test
    void runExport_partialFailure_errorMessageListsWhatCompleted() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        // A credentials row pointing at a service the org doesn't really have working access to -
        // openBaoService.registerBackupRole hits the fake OpenBao host configured for this whole IT
        // suite and fails fast, after raum's own dump has already succeeded and uploaded.
        String vassagoServiceId = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                        "SELECT id FROM services WHERE name = 'Vassago' LIMIT 1;")
                .getStdout().strip();
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "INSERT INTO credentials (org_id, service_id, db_engine, db_host, db_port, db_name) VALUES " +
                        "('%s', '%s', 'postgres', 'nonexistent-host', 5432, 'ghost');"
                                .formatted(orgAId, vassagoServiceId));

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).build();
        StepVerifier.create(poller.runExport(job))
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(TenantExportPoller.PartialExportFailureException.class);
                    assertThat(e.getMessage()).contains("completing: raum");
                });

        // raum's file still made it out despite the later failure.
        verify(artifactStore, times(1)).upload(anyString(), any(Path.class));
        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/raum/");
    }

    @Test
    void runExport_jsonFormat_producesJsonObjectKeyedByTable() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).format(ExportFormat.JSON.name()).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/raum/").endsWith(".json.gz");
        String content = gunzip(capturedBytes);
        assertThat(content).startsWith("{\"organizations\":[").contains("\"billing_history\":[").endsWith("}");
        assertThat(content).contains(orgAId.toString());
    }

    @Test
    void runExport_csvFormat_producesZipWithOneCsvPerTable() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).format(ExportFormat.CSV.name()).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/raum/").endsWith(".csv.zip");
        List<String> entryNames = new ArrayList<>();
        String organizationsCsv = null;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(capturedBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                if (entry.getName().equals("organizations.csv")) {
                    organizationsCsv = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(entryNames).containsExactlyInAnyOrder("organizations.csv", "billing_history.csv");
        assertThat(organizationsCsv).contains("id,name,").contains(orgAId.toString());
    }

    @Test
    void runExport_jsonFormat_embeddedQuotesAndBackslashesInTextColumns_produceValidJson() throws Exception {
        // Regression test for a real bug: tableJsonArray() used to run `\copy (...) TO STDOUT`, which
        // is COPY's TEXT format and backslash-escapes its output. Any text column whose value itself
        // contains a `"` (row_to_json escapes that to `\"`) got that backslash escaped *again* by
        // COPY, corrupting the JSON (`\"` became `\\"` in the file). Caught live via a real export
        // whose vassago `roles` column stores JSON-as-text (full of `"` characters) - reproduced here
        // with a raum column instead, since this IT suite only has real raum data to work with.
        stubUploadCapturingFile();
        String contactNameWithQuotesAndBackslash = "Admin \"The Boss\" \\ Ops";
        UUID orgAId = insertOrg("Tenant Export IT Quote Regression " + UUID.randomUUID(), contactNameWithQuotesAndBackslash);

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId).format(ExportFormat.JSON.name()).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        String content = gunzip(capturedBytes);
        JsonNode root = new ObjectMapper().readTree(content);
        String actualContactName = root.get("organizations").get(0).get("contact_name").asText();
        assertThat(actualContactName).isEqualTo(contactNameWithQuotesAndBackslash);
    }

    @Test
    void runExport_jsonMergedLayout_producesOneFileNamespacedByService() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId)
                .format(ExportFormat.JSON.name()).layout(ExportLayout.MERGED.name()).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        // No credentials rows for this org, so only raum contributes - but the key/shape must still
        // reflect the merged (single-file, service-namespaced) layout rather than SEPARATE's per-service key.
        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/export/").endsWith(".json.gz");
        String content = gunzip(capturedBytes);
        assertThat(content).startsWith("{\"raum\":{\"organizations\":[").contains(orgAId.toString());
    }

    @Test
    void runExport_csvMergedLayout_producesOneZipWithServicePrefixedEntries() throws Exception {
        stubUploadCapturingFile();
        UUID orgAId = insertOrg("Tenant Export IT Org A " + UUID.randomUUID());

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(orgAId)
                .format(ExportFormat.CSV.name()).layout(ExportLayout.MERGED.name()).build();
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        assertThat(capturedKey).startsWith("tenant-exports/" + orgAId + "/export/").endsWith(".csv.zip");
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(capturedBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        assertThat(entryNames).containsExactlyInAnyOrder("raum_organizations.csv", "raum_billing_history.csv");
    }

    @Test
    void runExport_unknownOrg_producesEmptyRaumFile_noError() throws Exception {
        stubUploadCapturingFile();
        UUID ghostOrgId = UUID.randomUUID();

        ExportJob job = ExportJob.builder().id(UUID.randomUUID()).orgId(ghostOrgId).build();
        // No org row at all (org was never created, or FK-orphaned job) - the extract queries just
        // return zero rows per table rather than erroring; the export still "succeeds" with empty
        // COPY blocks. Documents current behavior rather than asserting it's necessarily desirable.
        StepVerifier.create(poller.runExport(job)).expectNextCount(1).verifyComplete();

        String content = gunzip(capturedBytes);
        assertThat(content).contains("COPY organizations (");
        assertThat(content).doesNotContain(ghostOrgId.toString());
    }
}
