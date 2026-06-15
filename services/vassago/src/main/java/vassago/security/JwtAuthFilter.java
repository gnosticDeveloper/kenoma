package vassago.security;

import common.utils.RolesUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements WebFilter {
    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;
    private final UUID serviceId;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }
        String token = authHeader.substring(BEARER_PREFIX.length());
        return jwtService.validateToken(token)
                .flatMap(claims -> {
                    String username = claims.getSubject();
                    UUID orgId = UUID.fromString(claims.get("orgId", String.class));
                    @SuppressWarnings("unchecked")
                    Map<String, List<String>> roles = RolesUtils.deserialize(
                            claims.get("roles", String.class));
                    VassagoAuthentication auth = new VassagoAuthentication(orgId, username, roles, serviceId);
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                })
                .onErrorResume(e -> {
                    if (e instanceof org.springframework.web.server.ResponseStatusException) {
                        return Mono.error(e);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }
}