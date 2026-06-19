package vassago.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.security.JwtValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient openBaoClient;
    private final String transitKeyName;
    private final long ttlSeconds;
    private final JwtValidator jwtValidator;

    public JwtService(
            @Value("${openbao.base-url}") String openBaoBaseUrl,
            @Value("${vassago.openbao.token}") String openBaoToken,
            @Value("${vassago.jwt.transit-key-name}") String transitKeyName,
            @Value("${vassago.jwt.ttl-seconds:300}") long ttlSeconds) {
        this.openBaoClient = WebClient.builder()
                .baseUrl(openBaoBaseUrl)
                .defaultHeader("X-Vault-Token", openBaoToken)
                .build();
        this.transitKeyName = transitKeyName;
        this.ttlSeconds = ttlSeconds;
        this.jwtValidator = new JwtValidator(openBaoBaseUrl, openBaoToken, transitKeyName);
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
        return jwtValidator.validateToken(token);
    }

    // TODO: refresh cache on key rotation (e.g. via scheduled re-fetch or version check)
    public Mono<PublicKey> getPublicKey() {
        return jwtValidator.getPublicKey();
    }

    public long remainingSeconds(Instant expiry) {
        return Math.max(0, expiry.getEpochSecond() - Instant.now().getEpochSecond());
    }

    private String buildClaimsJson(UUID orgId, String username, Map<String, List<String>> roles,
                                   Instant now, Instant exp) {
        try {
            String rolesJson = OBJECT_MAPPER.writeValueAsString(roles);
            Map<String, Object> claims = Map.of(
                    "sub", username,
                    "orgId", orgId.toString(),
                    "roles", rolesJson,
                    "iat", now.getEpochSecond(),
                    "exp", exp.getEpochSecond(),
                    "jti", UUID.randomUUID().toString()
            );
            return OBJECT_MAPPER.writeValueAsString(claims);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JWT claims", e);
        }
    }
}