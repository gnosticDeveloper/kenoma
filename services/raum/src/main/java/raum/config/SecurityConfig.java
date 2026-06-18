package raum.config;

import common.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import raum.security.JwtAuthFilter;

import java.nio.charset.StandardCharsets;

import java.util.UUID;

@Configuration
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtValidator jwtValidator(
            @Value("${openbao.host}") String openBaoBaseUrl,
            @Value("${openbao.token}") String openBaoToken,
            @Value("${raum.jwt.transit-key-name}") String transitKeyName) {
        return new JwtValidator(openBaoBaseUrl, openBaoToken, transitKeyName);
    }

    @Bean
    public UUID serviceId(@Value("${raum.service-id}") String serviceId) {
        return UUID.fromString(serviceId);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         JwtAuthFilter jwtAuthFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/credentials/ephemeral").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .anyExchange().hasAuthority("RAUM_MANAGE")
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((exchange, ex) ->
                                writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((exchange, ex) ->
                                writeErrorResponse(exchange, HttpStatus.FORBIDDEN, "Access denied"))
                )
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }

    private static Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}".formatted(
                status.value(), status.name(), message);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}