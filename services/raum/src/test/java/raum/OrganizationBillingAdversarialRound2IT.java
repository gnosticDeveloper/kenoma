package raum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.BillingEmailRequestDTO;
import raum.dto.BillingEmailVerifyRequestDTO;
import raum.dto.BillingInfoRequestDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Second round of adversarial coverage: expiry, concurrency races, oversized columns,
 * transport-level malformity, method-scoping of the permitAll rule, unicode payloads,
 * and soft-deleted org behavior.
 */
class OrganizationBillingAdversarialRound2IT extends BaseIT {

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

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- expired token, inserted directly so we don't need to wait 24h ---

    @Test
    void confirmBillingEmail_expiredToken_returns404() throws Exception {
        UUID id = createOrg("Expired Token Org", "expired@example.com");
        String rawToken = "expired-raw-token-value-1234567890";
        String tokenHash = sha256Hex(rawToken);

        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                ("INSERT INTO pending_org_verifications (org_id, field_name, email, token_hash, expires_at, used) " +
                        "VALUES ('%s', 'BILLING_EMAIL', 'expired@example.com', '%s', current_timestamp - interval '1 hour', false);")
                        .formatted(id, tokenHash));

        // org.billing_email must match for any other check to even be reached; set it directly too
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE organizations SET billing_email = 'expired@example.com' WHERE id = '%s';".formatted(id));

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(rawToken))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void confirmBillingEmail_alreadyUsedToken_directlyMarkedUsed_returns404() throws Exception {
        UUID id = createOrg("Preused Token Org", "preused@example.com");
        String rawToken = "preused-raw-token-value-1234567890";
        String tokenHash = sha256Hex(rawToken);

        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                ("INSERT INTO pending_org_verifications (org_id, field_name, email, token_hash, expires_at, used) " +
                        "VALUES ('%s', 'BILLING_EMAIL', 'preused@example.com', '%s', current_timestamp + interval '1 hour', true);")
                        .formatted(id, tokenHash));
        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-c",
                "UPDATE organizations SET billing_email = 'preused@example.com' WHERE id = '%s';".formatted(id));

        client.post().uri("/orgs/{id}/billing-email/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailVerifyRequestDTO(rawToken))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- concurrent double-confirm race on the same valid token ---

    @Test
    void confirmBillingEmail_concurrentDoubleConfirm_atMostOneEffectivelyMattersButBothMayReturn204() throws Exception {
        UUID id = createOrg("Race Confirm Org", "race-confirm@example.com");
        String token = requestVerificationAndCaptureToken(id, "race-confirm-billing@example.com");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger noContentCount = new AtomicInteger();
        AtomicInteger notFoundCount = new AtomicInteger();

        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            HttpStatusCode status = client.post().uri("/orgs/{id}/billing-email/confirm", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new BillingEmailVerifyRequestDTO(token))
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus();
            if (status.value() == 204) noContentCount.incrementAndGet();
            else if (status.value() == 404) notFoundCount.incrementAndGet();
        };

        pool.submit(attempt);
        pool.submit(attempt);
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // Whatever interleaving occurs, the end state must be verified=true and the
        // requests seen must be exactly 2 (no silent drops/duplication at the HTTP layer).
        assertThat(noContentCount.get() + notFoundCount.get()).isEqualTo(2);
        assertThat(noContentCount.get()).isGreaterThanOrEqualTo(1);

        OrgResponseDTO org = client.get().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(org).isNotNull();
        assertThat(org.isBillingEmailVerified()).isTrue();
    }

    // --- oversized billing email (column is varchar(255)) ---

    @Test
    void requestBillingEmailVerification_emailExceedsColumnLimit_failsCleanly() {
        UUID id = createOrg("Oversized Email Org", "oversized-email@example.com");
        String tooLong = "a".repeat(300) + "@example.com";

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO(tooLong, "en"))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    // --- transport-level malformity ---

    @Test
    void updateBillingInfo_malformedJson_returns400() {
        UUID id = createOrg("Malformed JSON Org", "malformed-json@example.com");

        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ this is not valid json ")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void requestBillingEmailVerification_wrongContentType_isRejected() {
        UUID id = createOrg("Wrong Content Type Org", "wrong-ct@example.com");

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("billingEmail=nope@example.com")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // --- method-scoping: the permitAll rule is POST-only on that exact path ---

    @Test
    void getOnConfirmPath_withoutAuth_isNotSilentlyPermitted() {
        // No GET handler exists at this path, but security evaluation happens before
        // routing — confirms the permitAll() rule is scoped to POST, not the whole path.
        client.get().uri("/orgs/{id}/billing-email/confirm", orgId)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void billingEmailRequestPath_isNotAccidentallyPermitted() {
        client.post().uri("/orgs/{id}/billing-email", orgId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO("nope@example.com", "en"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- unicode / non-ASCII fiscal data (Argentine-locale names, accents, ñ) ---

    @Test
    void updateBillingInfo_unicodeFiscalName_roundTripsIntact() {
        UUID id = createOrg("Unicode Org", "unicode@example.com");
        String unicodeName = "Ñandú & Cía. S.A. — 日本語 — Straße";
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("20-99999999-9", unicodeName,
                "Ávda. Corrientes 1234, Piso 3°", "MONTHLY", Instant.now());

        OrgResponseDTO response = client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getFiscalName()).isEqualTo(unicodeName);
        assertThat(response.getFiscalAddress()).isEqualTo("Ávda. Corrientes 1234, Piso 3°");
    }

    // --- soft-deleted org: is billing still reachable? (documents existing behavior) ---

    @Test
    void updateBillingInfo_onSoftDeletedOrg_isStillReachable() {
        UUID id = createOrg("Soon Deleted Org", "soon-deleted@example.com");

        client.delete().uri("/orgs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        BillingInfoRequestDTO dto = new BillingInfoRequestDTO("tax", "name", "address", "MONTHLY", Instant.now());
        client.put().uri("/orgs/{id}/billing-info", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk();
    }

    // --- garbage locale must not crash email sending path ---

    @Test
    void requestBillingEmailVerification_garbageLocale_stillSucceeds() {
        UUID id = createOrg("Garbage Locale Org", "garbage-locale@example.com");

        client.post().uri("/orgs/{id}/billing-email", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BillingEmailRequestDTO("billing@garbage-locale.com", "not-a-real-locale-!!##"))
                .exchange()
                .expectStatus().isNoContent();
    }
}
