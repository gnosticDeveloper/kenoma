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
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the payment-status/resend endpoints added for #124: manual PAID/PENDING
 * toggle with paid_at/payment_reference bookkeeping, and the derived (not stored)
 * overdue flag.
 */
class BillingHistoryPaymentStatusIT extends BaseIT {

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

    private UUID insertBillingHistory(UUID org, String dueAtExpr) throws Exception {
        return UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        ("INSERT INTO billing_history (org_id, billing_cycle, due_at, amount, currency, line_items) VALUES " +
                                "('%s', 'MONTHLY', %s, 100.00, 'USD', '[]'::jsonb) RETURNING id;").formatted(org, dueAtExpr))
                .getStdout().strip().lines().findFirst().orElseThrow());
    }

    private BillingHistoryResponseDTO findInList(UUID org, UUID historyId) {
        List<BillingHistoryResponseDTO> history = client.get().uri("/orgs/{orgId}/billing-history", org)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(BillingHistoryResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(history).isNotNull();
        return history.stream().filter(h -> h.getId().equals(historyId)).findFirst().orElseThrow();
    }

    @Test
    void newBillingHistoryEntry_defaultsToPending() throws Exception {
        UUID org = createOrg("Payment Status Defaults Org", "defaults@example.com");
        UUID historyId = insertBillingHistory(org, "current_timestamp + interval '10 days'");

        BillingHistoryResponseDTO entry = findInList(org, historyId);
        assertThat(entry.getPaymentStatus()).isEqualTo("PENDING");
        assertThat(entry.isOverdue()).isFalse();
        assertThat(entry.getPaidAt()).isNull();
        assertThat(entry.getPaymentReference()).isNull();
    }

    @Test
    void pendingEntry_pastDueDate_isReportedOverdue() throws Exception {
        UUID org = createOrg("Overdue Org", "overdue@example.com");
        UUID historyId = insertBillingHistory(org, "current_timestamp - interval '1 day'");

        assertThat(findInList(org, historyId).isOverdue()).isTrue();
    }

    @Test
    void markPaid_thenMarkUnpaid_roundTripsCleanly() throws Exception {
        UUID org = createOrg("Toggle Payment Status Org", "toggle@example.com");
        UUID historyId = insertBillingHistory(org, "current_timestamp - interval '1 day'");

        BillingHistoryResponseDTO paid = client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", "wire-transfer-42"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(BillingHistoryResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(paid).isNotNull();
        assertThat(paid.getPaymentStatus()).isEqualTo("PAID");
        assertThat(paid.getPaymentReference()).isEqualTo("wire-transfer-42");
        assertThat(paid.getPaidAt()).isNotNull();
        // a PAID entry is never "overdue" regardless of due date
        assertThat(paid.isOverdue()).isFalse();

        BillingHistoryResponseDTO unpaid = client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PENDING", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(BillingHistoryResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(unpaid).isNotNull();
        assertThat(unpaid.getPaymentStatus()).isEqualTo("PENDING");
        assertThat(unpaid.getPaymentReference()).isNull();
        assertThat(unpaid.getPaidAt()).isNull();
        assertThat(unpaid.isOverdue()).isTrue();
    }

    @Test
    void markPaid_withoutReference_leavesReferenceNull() throws Exception {
        UUID org = createOrg("Mark Paid No Reference Org", "no-reference@example.com");
        UUID historyId = insertBillingHistory(org, "current_timestamp + interval '5 days'");

        BillingHistoryResponseDTO paid = client.put().uri("/orgs/{orgId}/billing-history/{historyId}/payment-status", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PaymentStatusUpdateRequestDTO("PAID", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(BillingHistoryResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(paid).isNotNull();
        assertThat(paid.getPaymentReference()).isNull();
        assertThat(paid.getPaidAt()).isNotNull();
    }

    @Test
    void resendInvoice_regeneratesPdfAndEmailsBillingAddress() throws Exception {
        when(mailgunService.sendInvoiceEmail(anyString(), any(), anyString(), any())).thenReturn(Mono.empty());

        UUID org = createOrg("Resend Invoice Org", "resend-org@example.com");
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                "UPDATE organizations SET billing_email = 'resend-target@example.com' WHERE id = '%s';".formatted(org));
        UUID historyId = insertBillingHistory(org, "current_timestamp + interval '5 days'");

        client.post().uri("/orgs/{orgId}/billing-history/{historyId}/resend", org, historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        verify(mailgunService).sendInvoiceEmail(eq("resend-target@example.com"), any(), anyString(), any());
    }
}
