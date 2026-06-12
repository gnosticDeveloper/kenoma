package vassago.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.UUID;

@Configuration
public class VassagoConfig {
    @Bean
    public UUID serviceId(@Value("${vassago.service-id}") String serviceId) {
        return UUID.fromString(serviceId);
    }
}