package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import raum.dto.ExportJobResponseDTO;
import raum.models.ExportJobStatus;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TenantExportIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        mockAdminJwt();
    }

    @Test
    void requestExport_queuesPendingJob() {
        ExportJobResponseDTO job = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(job).isNotNull();
        assertThat(job.getId()).isNotNull();
        assertThat(job.getOrgId()).isEqualTo(orgId);
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.PENDING.name());
        assertThat(job.getRequestedAt()).isNotNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getCompletedAt()).isNull();
    }

    @Test
    void requestExport_calledTwice_isIdempotent_returnsSameJob() {
        ExportJobResponseDTO first = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();
        ExportJobResponseDTO second = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // While a PENDING/RUNNING job already exists for the org, a repeat request returns that
        // same job instead of queuing a duplicate.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(ExportJobStatus.PENDING.name());
    }

    @Test
    void requestExport_concurrentCalls_onlyOneJobCreated() throws Exception {
        // The idempotency check-then-insert in requestExport isn't atomic, so this fires many
        // genuinely concurrent requests to exercise the database-level unique constraint that
        // catches the race the application-level check alone can miss.
        int concurrency = 8;
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + port).build();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReferenceArray<UUID> results = new AtomicReferenceArray<>(concurrency);

        try {
            List<Runnable> tasks = IntStream.range(0, concurrency)
                    .<Runnable>mapToObj(i -> () -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ExportJobResponseDTO job = webClient.post()
                                .uri("/orgs/{id}/export", orgId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                                .retrieve()
                                .bodyToMono(ExportJobResponseDTO.class)
                                .block();
                        results.set(i, job != null ? job.getId() : null);
                    })
                    .collect(Collectors.toList());
            tasks.forEach(pool::execute);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Set<UUID> distinctJobIds = IntStream.range(0, concurrency)
                .mapToObj(results::get)
                .collect(Collectors.toSet());
        assertThat(distinctJobIds)
                .as("every concurrent request must converge on the same single export job")
                .hasSize(1)
                .doesNotContainNull();
    }

    private UUID insertOrg(String uniqueName) throws Exception {
        String stdout = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                        "INSERT INTO organizations (name, contact_name, contact_email) VALUES " +
                                "('%s', 'Admin', 'admin@example.com') RETURNING id;".formatted(uniqueName))
                .getStdout();
        return UUID.fromString(stdout.strip().lines().findFirst().orElseThrow());
    }

    @Test
    void requestExport_jsonFormat_persistsFormatOnJob() throws Exception {
        // A fresh org, not the shared class-level `orgId` - reusing it would risk colliding with the
        // active-export idempotency guard exercised by other tests in this class, which would return
        // an existing SQL job instead of creating a new JSON one.
        UUID freshOrgId = insertOrg("Tenant Export IT JSON Format " + UUID.randomUUID());
        ExportJobResponseDTO job = client.post().uri("/orgs/{id}/export?format=json", freshOrgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(job).isNotNull();
        assertThat(job.getFormat()).isEqualTo("JSON");
    }

    @Test
    void requestExport_noFormat_defaultsToSql() throws Exception {
        UUID freshOrgId = insertOrg("Tenant Export IT Default Format " + UUID.randomUUID());
        ExportJobResponseDTO job = client.post().uri("/orgs/{id}/export", freshOrgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(job).isNotNull();
        assertThat(job.getFormat()).isEqualTo("SQL");
    }

    @Test
    void requestExport_unknownFormat_returns400() {
        client.post().uri("/orgs/{id}/export?format=yaml", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void requestExport_mergedLayout_persistsLayoutOnJob() throws Exception {
        UUID freshOrgId = insertOrg("Tenant Export IT Merged Layout " + UUID.randomUUID());
        ExportJobResponseDTO job = client.post().uri("/orgs/{id}/export?format=json&layout=merged", freshOrgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(job).isNotNull();
        assertThat(job.getFormat()).isEqualTo("JSON");
        assertThat(job.getLayout()).isEqualTo("MERGED");
    }

    @Test
    void requestExport_unknownLayout_returns400() {
        client.post().uri("/orgs/{id}/export?format=json&layout=combined", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void requestExport_mergedLayoutWithSqlFormat_returns400() {
        client.post().uri("/orgs/{id}/export?layout=merged", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void requestExport_returns404_forUnknownOrg() {
        client.post().uri("/orgs/{id}/export", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void requestExport_returns401_withoutToken() {
        client.post().uri("/orgs/{id}/export", orgId)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getExportJob_returnsQueuedJob() {
        ExportJobResponseDTO created = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();

        ExportJobResponseDTO fetched = client.get().uri("/orgs/{id}/export/{jobId}", orgId, created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(fetched).isNotNull();
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getStatus()).isEqualTo(ExportJobStatus.PENDING.name());
    }

    @Test
    void getExportJob_returns404_forUnknownJob() {
        client.get().uri("/orgs/{id}/export/{jobId}", orgId, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getExportJob_returns404_whenJobBelongsToDifferentOrg() {
        ExportJobResponseDTO created = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();

        client.get().uri("/orgs/{id}/export/{jobId}", UUID.randomUUID(), created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getExportJob_returns401_withoutToken() {
        client.get().uri("/orgs/{id}/export/{jobId}", orgId, UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void requestExport_ownerRole_succeedsForOwnOrg() {
        mockOwnerJwt(orgId);

        client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202);
    }

    @Test
    void requestExport_ownerRole_forbiddenForAnotherOrg() {
        mockOwnerJwt(orgId);

        client.post().uri("/orgs/{id}/export", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void getExportJob_ownerRole_forbiddenForAnotherOrg() {
        ExportJobResponseDTO created = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();

        mockOwnerJwt(UUID.randomUUID());

        client.get().uri("/orgs/{id}/export/{jobId}", orgId, created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }
}
