package vassago.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RedisTokenService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String USER_REVOKED_PREFIX = "revoked-user:";
    private static final String ROTATED_MARKER = "ROTATED:";
    // Window in which a rotated-out refresh token is still recognized as a replay rather than
    // just "unknown/expired". Only needs to cover realistic attacker/legitimate-client race
    // windows, not the full refresh-token lifetime.
    private static final long ROTATED_TOMBSTONE_TTL_SECONDS = 120;

    private final ReactiveStringRedisTemplate redis;
    private final long refreshTtlSeconds;

    public RedisTokenService(ReactiveStringRedisTemplate redis,
                             @Value("${vassago.refresh-token.ttl-seconds:2592000}") long refreshTtlSeconds) {
        this.redis = redis;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public Mono<Void> storeRefreshToken(String tokenHash, UUID orgId, UUID userId, String username, String fpHash) {
        String value = serialize(orgId, userId, username, fpHash);
        return redis.opsForValue()
                .set(REFRESH_PREFIX + tokenHash, value, Duration.ofSeconds(refreshTtlSeconds))
                .then();
    }

    /**
     * Atomically reads and removes the refresh token entry (Redis GETDEL) so that when two
     * requests race on the exact same still-valid token, only one of them can ever observe live
     * data and proceed to rotate it — the other sees nothing (having lost the race, not because
     * the token was already known-replayed) and simply fails with a clean 401, instead of both
     * successfully minting a new session from the same original token.
     */
    public Mono<RefreshTokenData> consumeRefreshToken(String tokenHash) {
        return redis.opsForValue()
                .getAndDelete(REFRESH_PREFIX + tokenHash)
                .flatMap(value -> {
                    if (value.startsWith(ROTATED_MARKER)) {
                        // GETDEL unconditionally removes the key, but a rotated tombstone needs to
                        // survive to the end of its detection window for later replay attempts to
                        // still be caught — put it straight back (this also slides the window
                        // forward, which is fine: it only extends while replay attempts continue).
                        return redis.opsForValue()
                                .set(REFRESH_PREFIX + tokenHash, value, Duration.ofSeconds(ROTATED_TOMBSTONE_TTL_SECONDS))
                                .then(Mono.<RefreshTokenData>empty());
                    }
                    return Mono.justOrEmpty(deserialize(value));
                });
    }

    /**
     * True if this token hash was already rotated out (i.e. legitimately used once already) and
     * is now being presented again — the signature of a stolen refresh token racing the real
     * client, or a stolen token used after the real client already rotated past it.
     */
    public Mono<UUID> checkReplayedToken(String tokenHash) {
        return redis.opsForValue()
                .get(REFRESH_PREFIX + tokenHash)
                .filter(value -> value.startsWith(ROTATED_MARKER))
                .map(value -> UUID.fromString(value.substring(ROTATED_MARKER.length())));
    }

    public Mono<Void> deleteRefreshToken(String tokenHash) {
        return redis.delete(REFRESH_PREFIX + tokenHash).then();
    }

    /**
     * Replaces a successfully-rotated refresh token with a short-lived tombstone (rather than
     * deleting it outright) so a subsequent replay of the same token hash can be recognized and
     * distinguished from a token that's simply unknown or naturally expired.
     */
    public Mono<Void> markRotated(String tokenHash, UUID userId) {
        return redis.opsForValue()
                .set(REFRESH_PREFIX + tokenHash, ROTATED_MARKER + userId, Duration.ofSeconds(ROTATED_TOMBSTONE_TTL_SECONDS))
                .then();
    }

    public Mono<Void> blacklistJwt(String jti, long remainingSeconds) {
        return redis.opsForValue()
                .set(BLACKLIST_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds))
                .then();
    }

    public Mono<Boolean> isBlacklisted(String jti) {
        return redis.hasKey(BLACKLIST_PREFIX + jti);
    }

    /**
     * Marks every token issued to this user before now as revoked. The entry only needs to
     * outlive the longest-lived token that could still be outstanding, so it's stored with a TTL
     * equal to the JWT lifetime — once that elapses, every pre-existing token has expired
     * naturally anyway and the marker becomes redundant.
     */
    public Mono<Void> revokeUserTokens(UUID userId, long jwtTtlSeconds) {
        return redis.opsForValue()
                .set(USER_REVOKED_PREFIX + userId, String.valueOf(Instant.now().getEpochSecond()), Duration.ofSeconds(jwtTtlSeconds))
                .then();
    }

    public Mono<Boolean> isRevokedForUser(UUID userId, Instant issuedAt) {
        // Strictly-before, not at-or-before: JWT `iat` is second-granularity (per the JWT spec's
        // NumericDate), so a fresh token minted in the same wall-clock second as an unrelated
        // revocation event for the same user must not be caught by it. This narrows the
        // protection to "tokens that already existed when the revocation happened," which is
        // the actual goal — a token that comes into existence at essentially the same instant
        // wasn't the one being revoked.
        return redis.opsForValue()
                .get(USER_REVOKED_PREFIX + userId)
                .map(revokedAt -> issuedAt.getEpochSecond() < Long.parseLong(revokedAt))
                .defaultIfEmpty(false);
    }

    private String serialize(UUID orgId, UUID userId, String username, String fpHash) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "orgId", orgId.toString(),
                    "userId", userId.toString(),
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
                    UUID.fromString(node.get("userId").asText()),
                    node.get("username").asText(),
                    node.get("fpHash").asText()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize refresh token entry", e);
        }
    }

    public record RefreshTokenData(UUID orgId, UUID userId, String username, String fpHash) {}
}
