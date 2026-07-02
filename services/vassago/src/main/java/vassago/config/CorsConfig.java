package vassago.config;

import common.security.CorsConfigFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Value("${vassago.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return CorsConfigFactory.build(allowedOrigins);
    }
}
