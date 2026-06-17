package raum.config;

import common.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import raum.security.JwtAuthFilter;

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
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}