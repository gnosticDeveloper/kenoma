package raum.config;

import common.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import raum.security.JwtAuthFilter;

import java.nio.charset.StandardCharsets;

import java.util.UUID;

@Configuration
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtValidator jwtValidator(@Value("${vassago.base-url}") String vassagoBaseUrl) {
        return new JwtValidator(vassagoBaseUrl);
    }

    @Bean
    public UUID serviceId(@Value("${raum.service-id}") String serviceId) {
        return UUID.fromString(serviceId);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         JwtAuthFilter jwtAuthFilter,
                                                         CorsConfigurationSource corsConfigurationSource) {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/credentials/ephemeral").permitAll()
                        .pathMatchers("/orgs/active-ids").permitAll()
                        .pathMatchers(HttpMethod.POST, "/orgs/*/billing-email/confirm").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/webjars/**").permitAll()
                        .pathMatchers("/onboarding/**").hasAuthority("INITIATE_ONBOARDING")
                        .pathMatchers("/orgs/**").hasAuthority("ORG_MANAGE")
                        .pathMatchers("/services/**").hasAuthority("SERVICE_MANAGE")
                        .pathMatchers("/credentials/**").hasAuthority("CREDENTIAL_MANAGE")
                        .pathMatchers("/pricing/**").hasAuthority("PRICING_MANAGE")
                        .anyExchange().denyAll()
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