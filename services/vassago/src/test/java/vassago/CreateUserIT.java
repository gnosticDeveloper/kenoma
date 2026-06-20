package vassago;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class CreateUserIT extends BaseIT {

    @LocalServerPort
    int port;

    @Test
    void createUser_persistsToOrgDatabase() {
        when(mailgunService.sendVerificationEmail(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.empty());

        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();
        String token = obtainToken(client);

        UserRequestDTO request = new UserRequestDTO();
        request.setName("Jane");
        request.setLastName("Doe");
        request.setEmail("jane.doe@example.com");
        request.setUsername("janedoe");
        request.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));

        UserResponseDTO response = client.post()
                .uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(UserResponseDTO.class)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Jane");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(response.getUsername()).isEqualTo("janedoe");
        assertThat(response.getRoles()).isEqualTo(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));
    }

    @Test
    void createUser_poolReusesConnectionOnSecondRequest() {
        when(mailgunService.sendVerificationEmail(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.empty());

        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:%d".formatted(port))
                .build();
        String token = obtainToken(client);

        UserRequestDTO first = new UserRequestDTO();
        first.setName("Alice");
        first.setLastName("Smith");
        first.setEmail("alice@example.com");
        first.setUsername("alicesmith");
        first.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));

        UserRequestDTO second = new UserRequestDTO();
        second.setName("Bob");
        second.setLastName("Jones");
        second.setEmail("bob@example.com");
        second.setUsername("bobjones");
        second.setRoles(Map.of(vassagoServiceId.toString(), List.of("VASSAGO_USER")));

        UserResponseDTO r1 = client.post().uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(first)
                .retrieve().bodyToMono(UserResponseDTO.class).block();

        UserResponseDTO r2 = client.post().uri("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(second)
                .retrieve().bodyToMono(UserResponseDTO.class).block();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }
}
