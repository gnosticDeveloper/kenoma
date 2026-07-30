package raum;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import raum.dto.BasePricingRequestDTO;
import raum.dto.BillingInfoRequestDTO;
import raum.dto.ExchangeRateRequestDTO;
import raum.dto.ModulePricingRequestDTO;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adversarial coverage for the pricing/permission redesign in #117: proves the new
 * ORG_MANAGE/SERVICE_MANAGE/CREDENTIAL_MANAGE/PRICING_MANAGE split actually denies
 * access (not just that it compiles), that billing history is scoped per-org, and
 * that pricing endpoints reject malformed input instead of leaking a 500.
 */
class PricingAdversarialIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    private void mockOnboardingOnlyJwt() {
        Claims claims = mock(Claims.class);
        String rolesJson = "{\"" + raumServiceId + "\":[\"RAUM_ONBOARDING\"]}";
        when(claims.getSubject()).thenReturn("test-onboarding");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn(rolesJson);
        when(jwtValidator.validateToken(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.just(claims));
    }

    private void mockNoRolesJwt() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("test-noroles");
        when(claims.get(eq("orgId"), eq(String.class))).thenReturn(orgId.toString());
        when(claims.get(eq("roles"), eq(String.class))).thenReturn("{}");
        when(jwtValidator.validateToken(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.just(claims));
    }

    // --- permission segregation: an onboarding-only token must not reach any of the
    // four newly-split management permissions ---

    @Test
    void onboardingRole_cannotListPricing() {
        mockOnboardingOnlyJwt();
        client.get().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void onboardingRole_cannotListOrgs() {
        mockOnboardingOnlyJwt();
        client.get().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void onboardingRole_cannotListServices() {
        mockOnboardingOnlyJwt();
        client.get().uri("/services")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void onboardingRole_cannotRegisterCredentials() {
        mockOnboardingOnlyJwt();
        client.post().uri("/credentials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void noRoles_cannotListPricing() {
        mockNoRolesJwt();
        client.get().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminRole_canListPricing() {
        mockAdminJwt();
        client.get().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk();
    }

    // --- billing history is scoped per-org, not globally addressable by history ID ---

    @Test
    void billingHistory_crossOrgIdWithForeignHistoryId_returns404NotForeignInvoice() throws Exception {
        mockAdminJwt();

        UUID orgAId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "INSERT INTO organizations (name, contact_name, contact_email) VALUES " +
                                "('Pricing Adversarial Org A', 'Admin', 'org-a@example.com') RETURNING id;")
                .getStdout().strip().lines().findFirst().orElseThrow());

        UUID historyId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "INSERT INTO billing_history (org_id, billing_cycle, due_at, amount, currency, line_items) VALUES " +
                                "('%s', 'MONTHLY', current_timestamp, 100.00, 'USD', '[]'::jsonb) RETURNING id;"
                                        .formatted(orgAId))
                .getStdout().strip().lines().findFirst().orElseThrow());

        OrgResponseDTO orgB = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrgRequestDTO("Pricing Adversarial Org B", "org-b@example.com", "Admin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        client.get().uri("/orgs/{orgId}/billing-history/{historyId}/invoice", orgB.getId(), historyId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void billingHistory_listing_doesNotLeakOtherOrgsEntries() throws Exception {
        mockAdminJwt();

        UUID orgAId = UUID.fromString(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "INSERT INTO organizations (name, contact_name, contact_email) VALUES " +
                                "('Pricing Listing Org A', 'Admin', 'listing-a@example.com') RETURNING id;")
                .getStdout().strip().lines().findFirst().orElseThrow());

        raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                "INSERT INTO billing_history (org_id, billing_cycle, due_at, amount, currency, line_items) VALUES " +
                        "('%s', 'MONTHLY', current_timestamp, 100.00, 'USD', '[]'::jsonb);".formatted(orgAId));

        OrgResponseDTO orgB = client.post().uri("/orgs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrgRequestDTO("Pricing Listing Org B", "listing-b@example.com", "Admin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgResponseDTO.class)
                .returnResult().getResponseBody();

        client.get().uri("/orgs/{orgId}/billing-history", orgB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Object.class)
                .hasSize(0);
    }

    // --- pricing endpoints reject malformed input with 400, not a raw DB/500 error ---

    @Test
    void addBasePricing_nullPrice_returns400NotServerError() {
        mockAdminJwt();
        BasePricingRequestDTO dto = new BasePricingRequestDTO(null, "USD", null);
        client.post().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void addBasePricing_negativePrice_returns400() {
        mockAdminJwt();
        BasePricingRequestDTO dto = new BasePricingRequestDTO(new BigDecimal("-10.00"), "USD", null);
        client.post().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void addBasePricing_blankCurrency_returns400() {
        mockAdminJwt();
        BasePricingRequestDTO dto = new BasePricingRequestDTO(new BigDecimal("10.00"), "   ", null);
        client.post().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void addBasePricing_currencyMismatchWithExistingHistory_returns400() {
        // The seeded base_pricing row (from init.sql) is USD — adding a EUR row without
        // first establishing EUR as the base currency must be rejected, not silently
        // accepted and left to flip what "the" reference currency is for every future invoice.
        mockAdminJwt();
        BasePricingRequestDTO dto = new BasePricingRequestDTO(new BigDecimal("10.00"), "EUR", null);
        client.post().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void addModulePricing_unknownServiceId_returns404NotServerError() {
        mockAdminJwt();
        ModulePricingRequestDTO dto = new ModulePricingRequestDTO(
                UUID.randomUUID(), new BigDecimal("10.00"), "USD", false, null);
        client.post().uri("/pricing/modules")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void addExchangeRate_zeroRate_returns400() {
        mockAdminJwt();
        ExchangeRateRequestDTO dto = new ExchangeRateRequestDTO("USD", "ARS", BigDecimal.ZERO, null);
        client.post().uri("/pricing/exchange-rates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void addExchangeRate_negativeRate_returns400() {
        mockAdminJwt();
        ExchangeRateRequestDTO dto = new ExchangeRateRequestDTO("USD", "ARS", new BigDecimal("-1"), null);
        client.post().uri("/pricing/exchange-rates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // --- currency case sensitivity: lowercase input must still match uppercase-stored rates ---

    @Test
    void addBasePricing_lowercaseCurrency_isNormalizedToUppercase() {
        mockAdminJwt();
        BasePricingRequestDTO dto = new BasePricingRequestDTO(new BigDecimal("10.00"), "usd", null);
        client.post().uri("/pricing/base")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.currency").isEqualTo("USD");
    }

    // --- GET /pricing/rate is vault-token-only: a user JWT alone must never satisfy it,
    // and a missing/invalid vault token must not leak a 500 ---

    @Test
    void getRate_noVaultTokenNoJwt_returns401() {
        client.get().uri("/pricing/rate?from=USD&to=ARS")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getRate_validAdminJwtButNoVaultToken_returns401NotBypassable() {
        mockAdminJwt();
        client.get().uri("/pricing/rate?from=USD&to=ARS")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getRate_garbageVaultToken_returns401NotServerError() {
        client.get().uri("/pricing/rate?from=USD&to=ARS")
                .header("X-Vault-Token", "not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- currencyRefreshMode / currencyRefreshCadence validation on PUT /orgs/{id}/billing-info ---

    @Test
    void updateBillingInfo_unknownCurrencyRefreshMode_returns400() {
        mockAdminJwt();
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(
                "tax", "name", "address", "MONTHLY", null, "USD", "sometimes", null, null, null);
        client.put().uri("/orgs/{id}/billing-info", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_unknownCurrencyRefreshCadence_returns400() {
        mockAdminJwt();
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(
                "tax", "name", "address", "MONTHLY", null, "USD", "PERIODIC", "fortnightly", null, null);
        client.put().uri("/orgs/{id}/billing-info", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_everyNDaysWithoutInterval_returns400() {
        mockAdminJwt();
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(
                "tax", "name", "address", "MONTHLY", null, "USD", "PERIODIC", "EVERY_N_DAYS", null, null);
        client.put().uri("/orgs/{id}/billing-info", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_everyNDaysWithZeroInterval_returns400() {
        mockAdminJwt();
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(
                "tax", "name", "address", "MONTHLY", null, "USD", "PERIODIC", "EVERY_N_DAYS", 0, null);
        client.put().uri("/orgs/{id}/billing-info", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_everyNDaysWithNegativeInterval_returns400() {
        mockAdminJwt();
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(
                "tax", "name", "address", "MONTHLY", null, "USD", "PERIODIC", "EVERY_N_DAYS", -3, null);
        client.put().uri("/orgs/{id}/billing-info", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateBillingInfo_validEveryNDays_persistsIntervalDays() {
        mockAdminJwt();
        BillingInfoRequestDTO dto = new BillingInfoRequestDTO(
                "tax", "name", "address", "MONTHLY", null, "USD", "PERIODIC", "EVERY_N_DAYS", 5, null);
        client.put().uri("/orgs/{id}/billing-info", orgId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.currencyRefreshCadence").isEqualTo("EVERY_N_DAYS")
                .jsonPath("$.currencyRefreshIntervalDays").isEqualTo(5);
    }
}
