package vassago.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import vassago.openbao.OpenBaoService;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
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
    private final AtomicReference<PublicKey> cachedPublicKey = new AtomicReference<>();

    public JwtService(
            OpenBaoService openBaoService,
            @Value("${vassago.jwt.transit-key-name}") String transitKeyName,
            @Value("${vassago.jwt.ttl-seconds:300}") long ttlSeconds) {
        this.openBaoClient = openBaoService.client();
        this.transitKeyName = transitKeyName;
        this.ttlSeconds = ttlSeconds;
    }

    public Mono<String> issueToken(UUID orgId, UUID userId, Map<String, List<String>> roles) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);

        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

        String claimsJson = buildClaimsJson(orgId, userId, roles, now, exp);
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
        return fetchPublicKey()
                .flatMap(publicKey -> {
                    try {
                        return Mono.just(Jwts.parser()
                                .verifyWith(publicKey)
                                .build()
                                .parseSignedClaims(token)
                                .getPayload());
                    } catch (SignatureException e) {
                        cachedPublicKey.set(null);
                        return fetchPublicKey().map(freshKey ->
                                Jwts.parser()
                                        .verifyWith(freshKey)
                                        .build()
                                        .parseSignedClaims(token)
                                        .getPayload());
                    }
                });
    }

    public Mono<PublicKey> getPublicKey() {
        return fetchPublicKey();
    }

    public Mono<String> getPublicKeyPem() {
        return fetchPublicKey().map(key ->
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
    }

    @Scheduled(cron = "${vassago.jwt.key-rotation-cron:0 0 0 * * *}")
    public void scheduledKeyRotation() {
        openBaoClient.post()
                .uri("/v1/transit/keys/{key}/rotate", transitKeyName)
                .retrieve()
                .bodyToMono(Void.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(5)))
                .then(Mono.delay(Duration.ofSeconds(1)))
                .then(Mono.fromRunnable(() -> cachedPublicKey.set(null)))
                .then(fetchPublicKey().retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2))))
                .block();
    }

    public long remainingSeconds(Instant expiry) {
        return Math.max(0, expiry.getEpochSecond() - Instant.now().getEpochSecond());
    }

    private Mono<PublicKey> fetchPublicKey() {
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
                    int latestVersion = ((Number) data.get("latest_version")).intValue();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> keys = (Map<String, Object>) data.get("keys");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> latestKey = (Map<String, Object>) keys.get(String.valueOf(latestVersion));
                    return parsePublicKey((String) latestKey.get("public_key"));
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

    private String buildClaimsJson(UUID orgId, UUID userId, Map<String, List<String>> roles,
                                   Instant now, Instant exp) {
        try {
            String rolesJson = OBJECT_MAPPER.writeValueAsString(roles);
            Map<String, Object> claims = Map.of(
                    "sub", userId.toString(),
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
