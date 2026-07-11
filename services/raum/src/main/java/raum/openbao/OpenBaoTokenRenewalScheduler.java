package raum.openbao;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Component
public class OpenBaoTokenRenewalScheduler {

    private final OpenBaoService openBaoService;
    private final OpenBaoProvisioner openBaoProvisioner;

    public OpenBaoTokenRenewalScheduler(OpenBaoService openBaoService, OpenBaoProvisioner openBaoProvisioner) {
        this.openBaoService = openBaoService;
        this.openBaoProvisioner = openBaoProvisioner;
    }

    @Scheduled(fixedRateString = "${openbao.token-renewal-rate-ms:1200000}")
    public void renewToken() {
        openBaoService.renewSelf()
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))
                .doOnSuccess(v -> log.info("OpenBao token renewed successfully"))
                .doOnError(error -> log.error("OpenBao token renewal failed after retries: {}", error.getMessage()))
                .onErrorResume(error -> openBaoProvisioner.provisionOnce()
                        .doOnSuccess(v -> log.info("OpenBao re-authenticated via AppRole login after renewal failure"))
                        .doOnError(loginError -> log.error("OpenBao re-authentication also failed: {}", loginError.getMessage()))
                        .onErrorResume(loginError -> Mono.empty()))
                .block();
    }
}
