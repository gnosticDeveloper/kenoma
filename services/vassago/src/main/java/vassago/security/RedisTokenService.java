package vassago.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class RedisTokenService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final ReactiveStringRedisTemplate redis;
    private final long refreshTtlSeconds;

    public RedisTokenService(ReactiveStringRedisTemplate redis,
                             @Value("${vassago.refresh-token.ttl-seconds:2592000}") long refreshTtlSeconds) {
        this.redis = redis;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public Mono<Void> storeRefreshToken(String tokenHash, UUID orgId, String username, String fpHash) {
        String value = serialize(orgId, username, fpHash);
        return redis.opsForValue()
                .set(REFRESH_PREFIX + tokenHash, value, Duration.ofSeconds(refreshTtlSeconds))
                .then();
    }

    public Mono<RefreshTokenData> lookupRefreshToken(String tokenHash) {
        return redis.opsForValue()
                .get(REFRESH_PREFIX + tokenHash)
                .mapNotNull(this::deserialize);
    }

    public Mono<Void> deleteRefreshToken(String tokenHash) {
        return redis.delete(REFRESH_PREFIX + tokenHash).then();
    }

    public Mono<Void> blacklistJwt(String jti, long remainingSeconds) {
        return redis.opsForValue()
                .set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds))
                .then();
    }

    public Mono<Boolean> isBlacklisted(String jti) {
        return redis.hasKey(BLACKLIST_PREFIX + jti);
    }

    private String serialize(UUID orgId, String username, String fpHash) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "orgId", orgId.toString(),
                    "username", username,
                    "fpHash", fpHash
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize refresh token entry", e);
        }
    }

    private RefreshTokenData deserialize(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            return new RefreshTokenData(
                    UUID.fromString(node.get("orgId").asText()),
                    node.get("username").asText(),
                    node.get("fpHash").asText()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize refresh token entry", e);
        }
    }

    public record RefreshTokenData(UUID orgId, String username, String fpHash) {}
}
