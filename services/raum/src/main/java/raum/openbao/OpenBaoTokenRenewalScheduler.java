package raum.openbao;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
public class OpenBaoTokenRenewalScheduler {

    private final OpenBaoService openBaoService;

    public OpenBaoTokenRenewalScheduler(OpenBaoService openBaoService) {
        this.openBaoService = openBaoService;
    }

    @Scheduled(fixedRateString = "${openbao.token-renewal-rate-ms:1200000}")
    public void renewToken() {
        openBaoService.renewSelf()
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))
                .doOnSuccess(v -> System.out.println("OpenBao token renewed successfully"))
                .doOnError(error -> System.err.println("OpenBao token renewal failed after retries: " + error.getMessage()))
                .onErrorResume(error -> Mono.empty())
                .block();
    }
}
