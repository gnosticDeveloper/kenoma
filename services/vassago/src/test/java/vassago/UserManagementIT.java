package vassago;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

class UserManagementIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;
    private String adminToken;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        reset(mailgunService);
        when(mailgunService.sendVerificationEmail(anyString(), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());
        adminToken = obtainToken(WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .build());
    }

    private UserRequestDTO newUserRequest() {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UserRequestDTO dto = new UserRequestDTO();
        dto.setName("Test");
        dto.setLastName("User");
        dto.setEmail("test_" + unique + "@example.com");
        dto.setUsername("test_" + unique);
        dto.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));
        return dto;
    }

    @Test
    void getUsersByOrgId_returnsAllOrgUsers() {
        List<UserResponseDTO> users = client.get().uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(users).isNotEmpty();
        assertThat(users).anyMatch(u -> BOOTSTRAP_USERNAME.equals(u.getUsername()));
    }

    @Test
    void getUserById_returnsCorrectUser() {
        List<UserResponseDTO> users = client.get().uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(users).isNotNull();
        UUID bootstrapId = users.stream()
                .filter(u -> BOOTSTRAP_USERNAME.equals(u.getUsername()))
                .findFirst()
                .map(UserResponseDTO::getId)
                .orElseThrow(() -> new AssertionError("bootstrap_admin not in list"));

        client.get().uri("/user/{id}", bootstrapId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDTO.class)
                .value(u -> {
                    assertThat(u.getId()).isEqualTo(bootstrapId);
                    assertThat(u.getUsername()).isEqualTo(BOOTSTRAP_USERNAME);
                });
    }

    @Test
    void updateUser_changesNameAndLastName() {
        UserRequestDTO createDto = newUserRequest();
        UserResponseDTO created = client.post().uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();

        UserRequestDTO updateDto = new UserRequestDTO();
        updateDto.setName("Updated");
        updateDto.setLastName("Name");
        updateDto.setEmail(createDto.getEmail());
        updateDto.setUsername(createDto.getUsername());
        updateDto.setRoles(createDto.getRoles());

        client.put().uri("/user/{id}", created.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDTO.class)
                .value(u -> {
                    assertThat(u.getName()).isEqualTo("Updated");
                    assertThat(u.getLastName()).isEqualTo("Name");
                    assertThat(u.getUsername()).isEqualTo(createDto.getUsername());
                });
    }

    @Test
    void deleteUser_softDeletesMakesUserUnreachable() {
        UserRequestDTO dto = newUserRequest();
        UserResponseDTO created = client.post().uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        UUID id = created.getId();

        client.delete().uri("/user/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isNoContent();

        // is_ready=false on the created user, but stopped_at is now set — either way get returns 404
        client.get().uri("/user/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isNotFound();
    }
}
