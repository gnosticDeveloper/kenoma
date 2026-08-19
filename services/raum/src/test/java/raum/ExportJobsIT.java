package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.ExportJobResponseDTO;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportJobsIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Test
    void listAll_adminRole_returnsQueuedJobAcrossOrgs() {
        mockAdminJwt();
        ExportJobResponseDTO created = client.post().uri("/orgs/{id}/export", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody(ExportJobResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();

        ExportJobResponseDTO[] jobs = client.get().uri("/export-jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ExportJobResponseDTO[].class)
                .returnResult().getResponseBody();

        assertThat(jobs).isNotNull();
        assertThat(jobs).anyMatch(j -> j.getId().equals(created.getId()));
    }

    @Test
    void listAll_ownerRole_forbidden() {
        mockOwnerJwt(orgId);

        client.get().uri("/export-jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void listAll_returns401_withoutToken() {
        client.get().uri("/export-jobs")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
