package common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class JwtValidator {

    record CachedKey(PublicKey key, Instant fetchedAt) {}

    private static final Duration DEFAULT_REFETCH_COOLDOWN = Duration.ofSeconds(30);

    private final WebClient vassagoClient;
    private final Duration refetchCooldown;
    private final AtomicReference<CachedKey> cache = new AtomicReference<>();

    public JwtValidator(String vassagoBaseUrl) {
        this(WebClient.builder().baseUrl(vassagoBaseUrl).build(), DEFAULT_REFETCH_COOLDOWN);
    }

    JwtValidator(WebClient vassagoClient) {
        this(vassagoClient, DEFAULT_REFETCH_COOLDOWN);
    }

    JwtValidator(WebClient vassagoClient, Duration refetchCooldown) {
        this.vassagoClient = vassagoClient;
        this.refetchCooldown = refetchCooldown;
    }

    public Mono<Claims> validateToken(String token) {
        return getPublicKey()
                .flatMap(publicKey -> {
                    try {
                        return Mono.just(Jwts.parser()
                                .verifyWith(publicKey)
                                .build()
                                .parseSignedClaims(token)
                                .getPayload());
                    } catch (SignatureException e) {
                        CachedKey current = cache.get();
                        if (current != null && Duration.between(current.fetchedAt(), Instant.now()).compareTo(refetchCooldown) < 0) {
                            throw e;
                        }
                        return fetchFromVassago().map(freshKey ->
                                Jwts.parser()
                                        .verifyWith(freshKey)
                                        .build()
                                        .parseSignedClaims(token)
                                        .getPayload());
                    }
                });
    }

    public Mono<PublicKey> getPublicKey() {
        CachedKey cached = cache.get();
        if (cached != null) {
            return Mono.just(cached.key());
        }
        return fetchFromVassago();
    }

    private Mono<PublicKey> fetchFromVassago() {
        return vassagoClient.get()
                .uri("/auth/public-key")
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    String pem = (String) ((Map<String, Object>) response).get("publicKey");
                    return parsePublicKey(pem);
                })
                .doOnNext(key -> cache.set(new CachedKey(key, Instant.now())));
    }

    public Mono<PublicKey> refreshCache() {
        return fetchFromVassago();
    }

    void invalidateCache() {
        cache.set(null);
    }

    private PublicKey parsePublicKey(String pem) {
        try {
            String stripped = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse public key", e);
        }
    }
}
