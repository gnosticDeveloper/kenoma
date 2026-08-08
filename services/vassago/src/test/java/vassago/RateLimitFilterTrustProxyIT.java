package vassago;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.UUID;

@TestPropertySource(properties = {
        "vassago.rate-limit.window-seconds=60",
        "vassago.rate-limit.max-requests=3",
        "vassago.rate-limit.trust-x-real-ip=false"
})
class RateLimitFilterTrustProxyIT extends BaseIT {

    @LocalServerPort
    int port;

    @Autowired
    ReactiveStringRedisTemplate redis;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        // With trust disabled, every request in this class shares one bucket keyed by the real
        // loopback socket address - other IT classes in this shared-Redis test run can also hit
        // /auth/public-key from that same address (e.g. without ever setting X-Real-IP), so start
        // from a known-empty counter rather than whatever they left behind.
        redis.delete("ratelimit:/auth/public-key:127.0.0.1").block();
        redis.delete("ratelimit:/auth/public-key:0:0:0:0:0:0:0:1").block();
    }

    @Test
    void withTrustDisabled_spoofedXRealIpDoesNotDodgeTheLimiter() {
        // With trust-x-real-ip=false, every request here shares the same bucket (the test
        // client's real socket address) regardless of what X-Real-IP claims - a caller can't
        // reset their own budget just by sending a different value each time.
        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/auth/public-key")
                    .header("X-Real-IP", "203.0.113." + (10 + i))
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.get()
                .uri("/auth/public-key")
                .header("X-Real-IP", "203.0.113." + UUID.randomUUID().hashCode())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
