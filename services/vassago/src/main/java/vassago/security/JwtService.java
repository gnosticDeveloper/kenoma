package vassago.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class JwtService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient openBaoClient;
    private final String transitKeyName;
    private final long ttlSeconds;

    // TODO: refresh cache on key rotation (e.g. via scheduled re-fetch or version check)
    private final AtomicReference<PublicKey> cachedPublicKey = new AtomicReference<>();

    public JwtService(
            @Value("${openbao.base-url}") String openBaoBaseUrl,
            @Value("${vassago.openbao.token}") String openBaoToken,
            @Value("${vassago.jwt.transit-key-name}") String transitKeyName,
            @Value("${vassago.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.openBaoClient = WebClient.builder()
                .baseUrl(openBaoBaseUrl)
                .defaultHeader("X-Vault-Token", openBaoToken)
                .build();
        this.transitKeyName = transitKeyName;
        this.ttlSeconds = ttlSeconds;
    }

    public Mono<String> issueToken(UUID orgId, String username, Map<String, List<String>> roles) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);

        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

        String claimsJson = buildClaimsJson(orgId, username, roles, now, exp);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + payload;
        String encodedInput = Base64.getEncoder()
                .encodeToString(signingInput.getBytes(StandardCharsets.UTF_8));

        return openBaoClient.post()
                .uri("/v1/transit/sign/{key}", transitKeyName)
                .bodyValue(Map.of(
                        "input", encodedInput,
                        "prehashed", false,
                        "marshaling_algorithm", "jws"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    String vaultSig = (String) data.get("signature");
                    String rawSig = vaultSig.substring(vaultSig.lastIndexOf(":") + 1);
                    return signingInput + "." + rawSig;
                });
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

    private String buildClaimsJson(UUID orgId, String username, Map<String, List<String>> roles,
                                   Instant now, Instant exp) {
        try {
            // Serialize roles map to JSON string so it roundtrips cleanly as a
            // single claim value rather than a nested object that jjwt may mangle.
            String rolesJson = OBJECT_MAPPER.writeValueAsString(roles);
            Map<String, Object> claims = Map.of(
                    "sub", username,
                    "orgId", orgId.toString(),
                    "roles", rolesJson,
                    "iat", now.getEpochSecond(),
                    "exp", exp.getEpochSecond()
            );
            return OBJECT_MAPPER.writeValueAsString(claims);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JWT claims", e);
        }
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