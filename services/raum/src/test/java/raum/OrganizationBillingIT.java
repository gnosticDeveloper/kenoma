package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.BillingEmailRequestDTO;
import raum.dto.BillingEmailVerifyRequestDTO;
import raum.dto.BillingInfoRequestDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class OrganizationBillingIT extends BaseIT {

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

    private UUID createOrg(String name, String email) {
        OrgRequestDTO create = new OrgRequestDTO(name, email, "Admin");
        OrgResponseDTO created = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(create)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();
        return created.getId();
    }

    @Test
    void updateBillingInfo_persistsFields() {
        UUID id = createOrg("Billing Org", "billing-org@example.com");
        Instant dueAt = Instant.now().plusSeconds(3600);
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("20-12345678-9", "Billing Org S.A.",
                "123 Fiscal St", "MONTHLY", dueAt, null, null, null, null, null);

        OrgResponseDTO response = client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getTaxId()).isEqualTo("20-12345678-9");
        assertThat(response.getFiscalName()).isEqualTo("Billing Org S.A.");
        assertThat(response.getFiscalAddress()).isEqualTo("123 Fiscal St");
        assertThat(response.getBillingCycle()).isEqualTo("MONTHLY");
    }

    @Test
    void updateBillingInfo_rejectsUnknownCycle() {
        UUID id = createOrg("Bad Cycle Org", "bad-cycle@example.com");
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("id", "name", "address", "WEEKLY", Instant.now(), null, null, null, null, null);

        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void billingEmailVerificationFlow_confirmsSuccessfully() {
        when(mailgunService.sendBillingEmailVerification(anyString(), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());

        UUID id = createOrg("Verify Org", "verify-org@example.com");
        BillingEmailRequestDTO requestDto = new BillingEmailRequestDTO("billing@verify-org.com", "en");

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isNoContent();

        var tokenCaptor = forClass(String.class);
        org.mockito.Mockito.verify(mailgunService)
                .sendBillingEmailVerification(anyString(), any(UUID.class), tokenCaptor.capture(), anyString());
        String token = tokenCaptor.getValue();

        OrgResponseDTO afterRequest = client.get().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(afterRequest).isNotNull();
        assertThat(afterRequest.isBillingEmailVerified()).isFalse();

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(token))
                .exchange()
                .expectStatus().isNoContent();

        OrgResponseDTO afterConfirm = client.get().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(afterConfirm).isNotNull();
        assertThat(afterConfirm.isBillingEmailVerified()).isTrue();

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(token))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void confirmBillingEmail_returns404_forUnknownToken() {
        UUID id = createOrg("Unknown Token Org", "unknown-token@example.com");

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO("not-a-real-token"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void requestBillingEmailVerification_requiresAuth() {
        client.post().uri("/orgs/{id}/billing-email", orgId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO("nope@example.com", "en"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
