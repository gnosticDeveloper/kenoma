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

/**
 * Adversarial coverage for the billing endpoints: malformed input, cross-org token
 * reuse, injection-style payloads and the email-change race guard.
 */
class OrganizationBillingAdversarialIT extends BaseIT {

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
        when(mailgunService.sendBillingEmailVerification(anyString(), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());
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

    private String requestVerificationAndCaptureToken(UUID orgId, String email) {
        client.post().uri("/orgs/{id}/billing-email", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO(email, "en"))
                .exchange()
                .expectStatus().isNoContent();

        var tokenCaptor = forClass(String.class);
        org.mockito.Mockito.verify(mailgunService, org.mockito.Mockito.atLeastOnce())
                .sendBillingEmailVerification(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.eq(orgId),
                        tokenCaptor.capture(), anyString());
        return tokenCaptor.getValue();
    }

    // --- null/blank input that previously NPE'd into a 500 ---

    @Test
    void updateBillingInfo_nullBillingCycle_returns400NotServerError() {
        UUID id = createOrg("Null Cycle Org", "null-cycle@example.com");
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("tax", "name", "address", null, Instant.now());

        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_blankBillingCycle_returns400() {
        UUID id = createOrg("Blank Cycle Org", "blank-cycle@example.com");
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("tax", "name", "address", "   ", Instant.now());

        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_lowercaseCycle_rejectedAsUnknown() {
        UUID id = createOrg("Lowercase Cycle Org", "lowercase-cycle@example.com");
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("tax", "name", "address", "monthly", Instant.now());

        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void confirmBillingEmail_nullToken_returns400NotServerError() {
        UUID id = createOrg("Null Token Org", "null-token@example.com");

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void confirmBillingEmail_blankToken_returns400() {
        UUID id = createOrg("Blank Token Org", "blank-token@example.com");

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO("   "))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void requestBillingEmailVerification_nullEmail_returns400() {
        UUID id = createOrg("Null Email Org", "null-email@example.com");

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO(null, "en"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void requestBillingEmailVerification_missingBody_returns400() {
        UUID id = createOrg("Missing Body Org", "missing-body@example.com");

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // --- cross-org / cross-tenant token confusion ---

    @Test
    void confirmBillingEmail_tokenFromOtherOrg_returns404() {
        UUID orgA = createOrg("Org A", "org-a@example.com");
        UUID orgB = createOrg("Org B", "org-b@example.com");
        String tokenForA = requestVerificationAndCaptureToken(orgA, "billing-a@example.com");

        client.post().uri("/orgs/{id}/billing-email/confirm", orgB)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(tokenForA))
                .exchange()
                .expectStatus().isNotFound();

        // token must still be usable against the correct org afterwards (org B's
        // failed attempt must not have consumed org A's token)
        client.post().uri("/orgs/{id}/billing-email/confirm", orgA)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(tokenForA))
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void confirmBillingEmail_unknownOrgIdInPath_returns404() {
        client.post().uri("/orgs/{id}/billing-email/confirm", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO("whatever-token"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void confirmBillingEmail_malformedOrgIdInPath_returns400() {
        client.post().uri("/orgs/{id}/billing-email/confirm", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO("whatever-token"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // --- email-change race guard: a stale token for an old email must not verify a new one ---

    @Test
    void confirmBillingEmail_staleTokenAfterEmailChanged_returns404() {
        UUID id = createOrg("Race Org", "race-org@example.com");
        String staleToken = requestVerificationAndCaptureToken(id, "old-billing@example.com");

        // admin changes their mind and re-requests with a different email before confirming
        requestVerificationAndCaptureToken(id, "new-billing@example.com");

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(staleToken))
                .exchange()
                .expectStatus().isNotFound();

        OrgResponseDTO org = client.get().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(org).isNotNull();
        assertThat(org.isBillingEmailVerified()).isFalse();
        assertThat(org.getBillingEmail()).isEqualTo("new-billing@example.com");
    }

    // --- injection-style payloads must round-trip inert, never execute ---

    @Test
    void updateBillingInfo_sqlLikePayload_isStoredVerbatimNotExecuted() {
        UUID id = createOrg("Injection Org", "injection@example.com");
        String payload = "'; DROP TABLE organizations; --";
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(payload, payload, payload, "MONTHLY", Instant.now());

        OrgResponseDTO response = client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getTaxId()).isEqualTo(payload);
        assertThat(response.getFiscalName()).isEqualTo(payload);

        // the table must still exist and be queryable — proves the payload was bound, not executed
        client.get().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void requestBillingEmailVerification_scriptTagInEmailField_isStoredVerbatim() {
        UUID id = createOrg("XSS Org", "xss-org@example.com");
        String payload = "<script>alert(1)</script>@example.com";

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO(payload, "en"))
                .exchange()
                .expectStatus().isNoContent();

        OrgResponseDTO org = client.get().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(org).isNotNull();
        assertThat(org.getBillingEmail()).isEqualTo(payload);
    }

    // --- oversized fields hitting varchar column limits ---

    @Test
    void updateBillingInfo_taxIdExceedsColumnLimit_failsCleanly() {
        UUID id = createOrg("Oversized Org", "oversized@example.com");
        String tooLong = "9".repeat(200); // column is varchar(64)
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(tooLong, "name", "address", "MONTHLY", Instant.now());

        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    // --- unknown org id on billing-info still 404s, not 500 ---

    @Test
    void updateBillingInfo_unknownOrgId_returns404() {
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("tax", "name", "address", "MONTHLY", Instant.now());

        client.put().uri("/orgs/{id}/billing-info", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }
}
