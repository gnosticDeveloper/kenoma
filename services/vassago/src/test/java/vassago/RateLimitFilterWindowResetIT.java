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
        "vassago.rate-limit.window-seconds=2",
        "vassago.rate-limit.max-requests=3"
})
class RateLimitFilterWindowResetIT extends BaseIT {

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

    private String syntheticClientIp() {
        return "203.0.113." + (1 + Math.abs(UUID.randomUUID().hashCode() % 250));
    }

    @Test
    void counter_resetsAfterWindowElapses() throws InterruptedException {
        String clientIp = syntheticClientIp();
        for (int i = 0; i < 3; i++) {
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
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Past the window's TTL, the counter key has expired in Redis and this IP gets a fresh budget.
        Thread.sleep(2500);

        webTestClient.get()
                .uri("/auth/public-key")
                .header("X-Real-IP", clientIp)
                .exchange()
                .expectStatus().isOk();
    }
}
