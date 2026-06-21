package bime.config;

import common.db.DatabaseConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BimeConfig {

    @Bean
    public DatabaseConnectionService databaseConnectionService(
            @Value("${bime.pool.max-size:10}") int maxSize,
            @Value("${bime.pool.initial-size:2}") int initialSize) {
        return new DatabaseConnectionService(maxSize, initialSize);
    }
}
