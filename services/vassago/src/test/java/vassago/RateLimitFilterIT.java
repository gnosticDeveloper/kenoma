package vassago;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.UUID;

@TestPropertySource(properties = {
        "vassago.rate-limit.window-seconds=60",
        "vassago.rate-limit.max-requests=5"
})
class RateLimitFilterIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    // A synthetic per-test X-Real-IP keeps each test's rate-limit counter isolated
    // from every other IT class hitting these same endpoints from the shared loopback
    // address (the filter trusts this header unconditionally, same as it would coming
    // from nginx in production, which always sets its own value regardless of the client).
    private String syntheticClientIp() {
        return "203.0.113." + (1 + Math.abs(UUID.randomUUID().hashCode() % 250));
    }

    @Test
    void publicKey_getsThrottled_afterExceedingLimit() {
        String clientIp = syntheticClientIp();
        for (int i = 0; i < 5; i++) {
            webTestClient.get()
                    .uri("/auth/public-key")
                    .header("X-Real-IP", clientIp)
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.get()
                .uri("/auth/public-key")
                .header("X-Real-IP", clientIp)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429);
    }

    @Test
    void protectedEndpoint_isNotAffectedByAuthRateLimit() {
        String clientIp = syntheticClientIp();
        for (int i = 0; i < 5; i++) {
            webTestClient.get()
                    .uri("/user")
                    .header("X-Real-IP", clientIp)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        // a 6th request past the /auth/* limit still gets a normal 401, not a 429 —
        // /user (aside from /user/verify) isn't in the rate-limited path set
        webTestClient.get()
                .uri("/user")
                .header("X-Real-IP", clientIp)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
