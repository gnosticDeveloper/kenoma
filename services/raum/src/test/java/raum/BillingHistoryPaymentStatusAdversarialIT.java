package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.BillingHistoryResponseDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.dto.PaymentStatusUpdateRequestDTO;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Adversarial coverage for the payment-status/resend endpoints added in #124:
 * malformed status values, oversized references, cross-org history-ID reuse, and
 * the no-billing-email guard on resend.
 */
class BillingHistoryPaymentStatusAdversarialIT extends BaseIT {

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

    private UUID insertBillingHistory(UUID org) throws Exception {
        return UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "INSERT INTO billing_history (org_id, billing_cycle, due_at, amount, currency, line_items) VALUES " +
                                "('%s', 'MONTHLY', current_timestamp, 100.00, 'USD', '[]'::jsonb) RETURNING id;".formatted(org))
                .getStdout().strip().lines().findFirst().orElseThrow());
    }

    // --- malformed / invalid status ---

    @Test
    void updatePaymentStatus_unknownStatusValue_returns400NotServerError() throws Exception {
        UUID org = createOrg("Bad Status Org", "bad-status@example.com");
        UUID historyId = insertBillingHistory(org);

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("CANCELLED", null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updatePaymentStatus_lowercaseStatus_rejectedAsUnknown() throws Exception {
        UUID org = createOrg("Lowercase Status Org", "lowercase-status@example.com");
        UUID historyId = insertBillingHistory(org);

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("paid", null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updatePaymentStatus_nullStatus_returns400() throws Exception {
        UUID org = createOrg("Null Status Org", "null-status@example.com");
        UUID historyId = insertBillingHistory(org);

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO(null, null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updatePaymentStatus_missingBody_returns4xx() throws Exception {
        UUID org = createOrg("Missing Body Status Org", "missing-body-status@example.com");
        UUID historyId = insertBillingHistory(org);

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // --- oversized reference ---

    @Test
    void updatePaymentStatus_referenceExceedsColumnLimit_returns400NotServerError() throws Exception {
        UUID org = createOrg("Oversized Reference Org", "oversized-reference@example.com");
        UUID historyId = insertBillingHistory(org);
        String tooLong = "9".repeat(300); // column is varchar(255)

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", tooLong))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // --- injection-style payload in reference must round-trip inert ---

    @Test
    void updatePaymentStatus_sqlLikeReference_isStoredVerbatimNotExecuted() throws Exception {
        UUID org = createOrg("Injection Reference Org", "injection-reference@example.com");
        UUID historyId = insertBillingHistory(org);
        String payload = "'; DROP TABLE billing_history; --";

        BillingHistoryResponseDTO response = client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", payload))
                .exchange()
                .expectStatus().isOk()
                .expectBody(BillingHistoryResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getPaymentReference()).isEqualTo(payload);

        // table must still exist and be queryable — proves the payload was bound, not executed
        client.get().uri("/orgs/{orgId}/billing-history", org)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk();
    }

    // --- cross-org / cross-tenant history-ID confusion ---

    @Test
    void updatePaymentStatus_crossOrgHistoryId_returns404NotForeignInvoice() throws Exception {
        UUID orgA = createOrg("Payment Status Org A", "status-org-a@example.com");
        UUID orgB = createOrg("Payment Status Org B", "status-org-b@example.com");
        UUID historyId = insertBillingHistory(orgA);

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", orgB, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", "ref-cross-org"))
                .exchange()
                .expectStatus().isNotFound();

        // must not have been mutated via the foreign-org path
        client.get().uri("/orgs/{orgId}/billing-history", orgA)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(BillingHistoryResponseDTO.class)
                .value(list -> assertThat(list).extracting(BillingHistoryResponseDTO::getPaymentStatus)
                        .containsExactly("PENDING"));
    }

    @Test
    void resendInvoice_crossOrgHistoryId_returns404() throws Exception {
        UUID orgA = createOrg("Resend Org A", "resend-org-a@example.com");
        UUID orgB = createOrg("Resend Org B", "resend-org-b@example.com");
        UUID historyId = insertBillingHistory(orgA);

        client.post().uri("/orgs/{orgId}/billing-history/{historyId}/resend", orgB, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();

        verify(mailgunService, never()).sendInvoiceEmail(anyString(), any(), anyString(), any());
    }

    @Test
    void updatePaymentStatus_unknownHistoryId_returns404() throws Exception {
        UUID org = createOrg("Unknown History Org", "unknown-history@example.com");

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", "ref-unknown-id"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- resend without a billing email must fail cleanly, not throw a raw NPE/500 ---

    @Test
    void resendInvoice_orgHasNoBillingEmail_returns400NotServerError() throws Exception {
        UUID org = createOrg("No Billing Email Org", "no-billing-email@example.com");
        UUID historyId = insertBillingHistory(org);

        client.post().uri("/orgs/{orgId}/billing-history/{historyId}/resend", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();

        verify(mailgunService, never()).sendInvoiceEmail(anyString(), any(), anyString(), any());
    }

    @Test
    void resendInvoice_unknownHistoryId_returns404() throws Exception {
        UUID org = createOrg("Resend Unknown History Org", "resend-unknown-history@example.com");

        client.post().uri("/orgs/{orgId}/billing-history/{historyId}/resend", org, UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- auth is required, not just permissive default-deny ---

    @Test
    void updatePaymentStatus_missingAuth_returns401() throws Exception {
        UUID org = createOrg("No Auth Status Org", "no-auth-status@example.com");
        UUID historyId = insertBillingHistory(org);

        client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", null))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void resendInvoice_missingAuth_returns401() throws Exception {
        UUID org = createOrg("No Auth Resend Org", "no-auth-resend@example.com");
        UUID historyId = insertBillingHistory(org);

        client.post().uri("/orgs/{orgId}/billing-history/{historyId}/resend", org, historyId)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
