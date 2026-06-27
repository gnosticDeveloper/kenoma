package raum.security;

import common.security.JwtValidator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
public class JwtPublicKeyRefreshScheduler {

    private final JwtValidator jwtValidator;

    public JwtPublicKeyRefreshScheduler(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Scheduled(cron = "${vassago.jwt.public-key-refresh-cron:15 0 0 * * *}")
    public void refreshPublicKey() {
        jwtValidator.refreshCache()
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))
                .block();
    }
}
