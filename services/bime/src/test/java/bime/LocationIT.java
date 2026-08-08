package bime;

import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocationIT extends BaseIT {

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
    void createLocation_persistsToDatabase() {
        LocationResponseDTO response = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("Main Warehouse", "WH-001"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Main Warehouse");
        assertThat(response.getCode()).isEqualTo("WH-001");
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getOrgId()).isEqualTo(ORG_ID);
    }

    @Test
    void createLocation_withoutCode_returns400NotServerError() {
        client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("Unnamed Code Location", null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getLocationById_returnsLocation() {
        LocationResponseDTO created = createLocation("Shelf A", "SH-A");

        LocationResponseDTO response = client.get().uri("/locations/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(created.getId());
        assertThat(response.getName()).isEqualTo("Shelf A");
    }

    @Test
    void getLocations_returnsAll() {
        createLocation("Loc One", "L-001");
        createLocation("Loc Two", "L-002");

        client.get().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(LocationResponseDTO.class)
                .hasSize(2);
    }

    @Test
    void updateLocation_changesFields() {
        LocationResponseDTO created = createLocation("Old Name", "OLD-1");

        LocationResponseDTO updated = client.put().uri("/locations/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("New Name", "NEW-1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getCode()).isEqualTo("NEW-1");
    }

    @Test
    void deactivateLocation_returns204() {
        LocationResponseDTO created = createLocation("To Deactivate", "DEA-1");

        client.delete().uri("/locations/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void getLocation_returns404_forUnknownId() {
        client.get().uri("/locations/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createLocation_returns403_withViewerRole() {
        mockViewerJwt();

        client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("Blocked", "BLK-1"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void getLocations_returns401_withNoToken() {
        client.get().uri("/locations")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void updateLocation_returns404_forUnknownId() {
        client.put().uri("/locations/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("Ghost", "GH-001"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deactivateLocation_returns404_forUnknownId() {
        client.delete().uri("/locations/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createLocation_returns409_forDuplicateCode() {
        createLocation("First", "DUP-CODE");

        client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest("Second", "DUP-CODE"))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    LocationResponseDTO createLocation(String name, String code) {
        LocationResponseDTO response = client.post().uri("/locations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(locationRequest(name, code))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LocationResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private static LocationRequestDTO locationRequest(String name, String code) {
        LocationRequestDTO dto = new LocationRequestDTO();
        dto.setName(name);
        dto.setCode(code);
        dto.setIsActive(true);
        return dto;
    }
}
