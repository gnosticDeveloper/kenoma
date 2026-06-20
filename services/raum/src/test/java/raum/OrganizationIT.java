package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationIT extends BaseIT {

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
    void registerOrg_persistsOrg() {
        OrgRequestDTO dto = new OrgRequestDTO("Acme Corp", "admin@acme.com", "Acme Admin");

        OrgResponseDTO response = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Acme Corp");
        assertThat(response.getContactEmail()).isEqualTo("admin@acme.com");
    }

    @Test
    void getOrgById_returnsSeededPlatformOrg() {
        OrgResponseDTO response = client.get().uri("/orgs/{id}", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(orgId);
        assertThat(response.getName()).isEqualTo("Platform");
    }

    @Test
    void updateOrg_changesFields() {
        OrgRequestDTO create = new OrgRequestDTO("Org To Update", "orig@update.com", "Orig Admin");
        OrgResponseDTO created = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(create)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();

        OrgRequestDTO update = new OrgRequestDTO("Updated Org", "updated@update.com", "Updated Admin");
        OrgResponseDTO updated = client.put().uri("/orgs/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(update)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("Updated Org");
        assertThat(updated.getContactEmail()).isEqualTo("updated@update.com");
    }

    @Test
    void updateOrg_returns404_forUnknownId() {
        OrgRequestDTO dto = new OrgRequestDTO("Ghost Org", "ghost@org.com", "Ghost Admin");

        client.put().uri("/orgs/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteOrg_returns204() {
        OrgRequestDTO create = new OrgRequestDTO("Org To Delete", "del@org.com", "Del Admin");
        OrgResponseDTO created = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(create)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();

        client.delete().uri("/orgs/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void getOrg_returns401_withoutToken() {
        client.get().uri("/orgs/{id}", orgId)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
