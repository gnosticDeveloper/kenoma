package common.security;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

public final class CorsConfigFactory {

    private CorsConfigFactory() {
    }

    public static CorsConfigurationSource build(String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            configuration.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .toList());
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
