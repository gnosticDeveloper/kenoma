package raum.onboarding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class OnboardingConfigStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PREFIX = "onboarding:config:";
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final ReactiveStringRedisTemplate redis;
    private final long ttlSeconds;

    public OnboardingConfigStore(ReactiveStringRedisTemplate redis,
                                 @Value("${raum.onboarding.config-ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    public Mono<Void> save(UUID credentialId, Map<String, String> config) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(config);
            return redis.opsForValue()
                    .set(PREFIX + credentialId, json, Duration.ofSeconds(ttlSeconds))
                    .then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    public Mono<Map<String, String>> get(UUID credentialId) {
        return redis.opsForValue()
                .get(PREFIX + credentialId)
                .mapNotNull(json -> {
                    try {
                        return OBJECT_MAPPER.readValue(json, MAP_TYPE);
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    public Mono<Void> delete(UUID credentialId) {
        return redis.delete(PREFIX + credentialId).then();
    }
}
