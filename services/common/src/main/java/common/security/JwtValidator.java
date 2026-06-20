package common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class JwtValidator {

    private final WebClient openBaoClient;
    private final String transitKeyName;
    private final AtomicReference<PublicKey> cachedPublicKey = new AtomicReference<>();

    public JwtValidator(String openBaoBaseUrl, String openBaoToken, String transitKeyName) {
        this.openBaoClient = WebClient.builder()
                .baseUrl(openBaoBaseUrl)
                .defaultHeader("X-Vault-Token", openBaoToken)
                .build();
        this.transitKeyName = transitKeyName;
    }

    JwtValidator(WebClient openBaoClient, String transitKeyName) {
        this.openBaoClient = openBaoClient;
        this.transitKeyName = transitKeyName;
    }

    public Mono<Claims> validateToken(String token) {
        return getPublicKey()
                .map(publicKey -> Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload());
    }

    public Mono<PublicKey> getPublicKey() {
        PublicKey cached = cachedPublicKey.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return openBaoClient.get()
                .uri("/v1/transit/keys/{key}", transitKeyName)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> keys = (Map<String, Object>) data.get("keys");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> latestKey = (Map<String, Object>) keys.get("1");
                    String pemPublicKey = (String) latestKey.get("public_key");
                    return parsePublicKey(pemPublicKey);
                })
                .doOnNext(cachedPublicKey::set);
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
            throw new RuntimeException("Failed to parse OpenBao public key", e);
        }
    }
}