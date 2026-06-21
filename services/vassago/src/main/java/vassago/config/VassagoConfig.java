package vassago.config;

import common.db.DatabaseConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import java.util.UUID;

@Configuration
public class VassagoConfig {

    @Bean
    public UUID serviceId(@Value("${vassago.service-id}") String serviceId) {
        return UUID.fromString(serviceId);
    }

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    @Bean
    public DatabaseConnectionService databaseConnectionService(
            @Value("${vassago.pool.max-size:10}") int maxSize,
            @Value("${vassago.pool.initial-size:2}") int initialSize) {
        return new DatabaseConnectionService(maxSize, initialSize);
    }
}