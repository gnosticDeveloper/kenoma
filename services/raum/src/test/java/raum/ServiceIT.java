package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.ServiceRequestDTO;
import raum.dto.ServiceResponseDTO;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceIT extends BaseIT {

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
    void getAll_returnsSeededServices() {
        List<ServiceResponseDTO> services = client.get().uri("/services")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ServiceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(services).isNotNull();
        assertThat(services).anyMatch(s -> "Raum".equals(s.getName()));
        assertThat(services).anyMatch(s -> "Vassago".equals(s.getName()));
    }

    @Test
    void register_persistsService() {
        ServiceRequestDTO dto = new ServiceRequestDTO("TestService", "A test service");

        ServiceResponseDTO response = client.post().uri("/services")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("TestService");
        assertThat(response.getDescription()).isEqualTo("A test service");
    }

    @Test
    void getById_returnsService() {
        ServiceResponseDTO created = client.post().uri("/services")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ServiceRequestDTO("GetByIdSvc", "desc"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();

        client.get().uri("/services/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponseDTO.class)
                .value(s -> assertThat(s.getId()).isEqualTo(created.getId()));
    }

    @Test
    void getById_returns404_forUnknownId() {
        client.get().uri("/services/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void update_changesDescription() {
        ServiceResponseDTO created = client.post().uri("/services")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ServiceRequestDTO("UpdateSvc", "original desc"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();

        ServiceResponseDTO updated = client.put().uri("/services/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ServiceRequestDTO("UpdateSvc", "updated desc"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getDescription()).isEqualTo("updated desc");
    }

    @Test
    void delete_returns204() {
        ServiceResponseDTO created = client.post().uri("/services")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ServiceRequestDTO("DeleteSvc", "to be deleted"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();

        client.delete().uri("/services/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();
    }
}
