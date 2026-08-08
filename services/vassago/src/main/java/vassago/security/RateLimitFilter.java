package vassago.security;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Per-IP request throttling for vassago's unauthenticated endpoints, which otherwise
 * have no protection against credential-stuffing or verification-token brute forcing.
 * Uses a fixed-window counter in Redis (shared with {@link RedisTokenService}) rather
 * than in-memory state, since it's the storage already wired in for this purpose.
 *
 * <p>Deliberately not a {@code @Component}: Spring WebFlux auto-registers every
 * {@link WebFilter} bean into the global filter chain, which would run this filter a
 * second time in addition to its explicit registration in {@code SecurityConfig}'s
 * {@code SecurityWebFilterChain}, double-counting every request. Instead it's
 * constructed directly by {@code SecurityConfig} and wired only into that chain.
 */
public class RateLimitFilter implements WebFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            "/auth/login", "/auth/recover", "/auth/refresh", "/auth/public-key", "/user/verify");

    private final ReactiveStringRedisTemplate redis;
    private final long windowSeconds;
    private final long maxRequests;
    private final boolean trustXRealIp;

    public RateLimitFilter(ReactiveStringRedisTemplate redis, long windowSeconds, long maxRequests) {
        this(redis, windowSeconds, maxRequests, true);
    }

    /**
     * @param trustXRealIp Whether to key the limiter off the caller-supplied X-Real-IP header.
     *                      Safe to leave true only because vassago's HTTP port is never exposed
     *                      to the host (only nginx is — see docker-compose.yml), so in this
     *                      deployment the header is always set by nginx, never by the actual
     *                      client. If that assumption ever changes (vassago reachable directly,
     *                      or fronted by something that doesn't set/strip this header itself), a
     *                      caller could set an arbitrary value to dodge or frame another IP's
     *                      bucket — set this to false in that case to fall back to the socket's
     *                      remote address.
     */
    public RateLimitFilter(ReactiveStringRedisTemplate redis, long windowSeconds, long maxRequests, boolean trustXRealIp) {
        this.redis = redis;
        this.windowSeconds = windowSeconds;
        this.maxRequests = maxRequests;
        this.trustXRealIp = trustXRealIp;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!LIMITED_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String clientIp = clientIp(exchange.getRequest());
        String key = "ratelimit:" + path + ":" + clientIp;

        return redis.opsForValue().increment(key)
                .flatMap(count -> {
                    Mono<Void> expireIfFirst = count == 1L
                            ? redis.expire(key, Duration.ofSeconds(windowSeconds)).then()
                            : Mono.empty();
                    return expireIfFirst.then(count > maxRequests
                            ? tooManyRequests(exchange)
                            : chain.filter(exchange));
                });
    }

    private String clientIp(ServerHttpRequest request) {
        if (trustXRealIp) {
            String forwardedFor = request.getHeaders().getFirst("X-Real-IP");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor;
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":429,\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
