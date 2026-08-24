package raum.onboarding;

import common.dto.CredentialsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import raum.dto.OnboardingRequestDTO;
import raum.models.Credentials;
import raum.models.Organization;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import raum.repository.OrganizationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OnboardingRetryScheduler {

    private final CredentialsRepository credentialsRepository;
    private final OrganizationRepository organizationRepository;
    private final OpenBaoService openBaoService;
    private final OnboardingConfigStore configStore;
    private final VassagoOnboardingStrategy vassagoStrategy;
    private final List<OnboardingStrategy> strategies;

    @Value("${vassago.service-id}")
    private UUID vassagoServiceId;

    public OnboardingRetryScheduler(CredentialsRepository credentialsRepository,
                                    OrganizationRepository organizationRepository,
                                    OpenBaoService openBaoService,
                                    OnboardingConfigStore configStore,
                                    VassagoOnboardingStrategy vassagoStrategy,
                                    List<OnboardingStrategy> strategies) {
        this.credentialsRepository = credentialsRepository;
        this.organizationRepository = organizationRepository;
        this.openBaoService = openBaoService;
        this.configStore = configStore;
        this.vassagoStrategy = vassagoStrategy;
        this.strategies = strategies;
    }

    @Scheduled(cron = "${raum.onboarding.retry-cron:0 */10 * * * *}")
    public void retryUninitialized() {
        credentialsRepository.findAllByInitializedFalse()
                .collectList()
                .flatMap(uninitList -> {
                    if (uninitList.isEmpty()) return Mono.empty();

                    return organizationRepository.findAllByStoppedAtIsNull()
                            .map(Organization::getId)
                            .collect(Collectors.toSet())
                            .flatMap(activeOrgIds -> {
                                Map<UUID, List<Credentials>> byOrg = uninitList.stream()
                                        .filter(c -> activeOrgIds.contains(c.getOrgId()))
                                        .collect(Collectors.groupingBy(Credentials::getOrgId));

                                return Flux.fromIterable(byOrg.entrySet())
                                        .concatMap(entry -> retryForOrg(entry.getKey(), entry.getValue()))
                                        .then();
                            });
                })
                .subscribe(
                        null,
                        e -> log.error("Onboarding retry cycle failed", e)
                );
    }

    private Mono<Void> retryForOrg(UUID orgId, List<Credentials> uninitCreds) {
        boolean vassagoUninitialized = uninitCreds.stream()
                .anyMatch(c -> c.getServiceId().equals(vassagoServiceId));

        if (vassagoUninitialized) {
            log.warn("Skipping retry for org {} — Vassago credential not initialized", orgId);
            return Mono.empty();
        }

        List<Credentials> toRetry = uninitCreds.stream()
                .filter(c -> !c.getServiceId().equals(vassagoServiceId))
                .toList();

        if (toRetry.isEmpty()) return Mono.empty();

        return credentialsRepository.findByOrgIdAndServiceId(orgId, vassagoServiceId)
                .flatMap(vassagoCreds -> openBaoService.issueEphemeralCredentials(vassagoCreds.getId())
                        .flatMap(vassagoEphemeral -> {
                            CredentialsDTO vassagoDto = buildDto(vassagoCreds, vassagoEphemeral);
                            return vassagoStrategy.createTempSession(orgId, vassagoDto)
                                    .flatMap(session -> {
                                        OnboardingContext context = new OnboardingContext();
                                        context.setJwt(session.jwt());

                                        return Flux.fromIterable(toRetry)
                                                .concatMap(cred -> retryCredential(orgId, cred, context))
                                                .then(session.cleanup());
                                    });
                        })
                )
                .doOnSuccess(v -> log.info("Retry completed for org {}", orgId))
                .doOnError(e -> log.error("Retry failed for org {}", orgId, e))
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> retryCredential(UUID orgId, Credentials cred, OnboardingContext context) {
        return configStore.get(cred.getId())
                .flatMap(config -> {
                    OnboardingStrategy strategy = strategies.stream()
                            .filter(s -> s.getServiceId().equals(cred.getServiceId()))
                            .findFirst()
                            .orElse(null);

                    if (strategy == null) {
                        log.warn("No strategy found for service {} — skipping", cred.getServiceId());
                        return Mono.empty();
                    }

                    return openBaoService.issueEphemeralCredentials(cred.getId())
                            .flatMap(ephemeral -> {
                                CredentialsDTO dto = buildDto(cred, ephemeral);
                                OnboardingRequestDTO request = strategy.buildRequestFromConfig(config);
                                return strategy.execute(orgId, dto, request, context)
                                        .then(Mono.defer(() -> {
                                            cred.setInitialized(true);
                                            return credentialsRepository.save(cred).then();
                                        }));
                            });
                });
    }

    private CredentialsDTO buildDto(Credentials creds, CredentialsDTO ephemeral) {
        CredentialsDTO dto = new CredentialsDTO();
        dto.setOrgId(creds.getOrgId());
        dto.setServiceId(creds.getServiceId());
        dto.setUserName(ephemeral.getUserName());
        dto.setPassword(ephemeral.getPassword());
        dto.setDbHost(creds.getDbHost());
        dto.setDbPort(creds.getDbPort());
        dto.setDbName(creds.getDbName());
        dto.setDbEngine(creds.getDbEngine());
        dto.setLeaseId(ephemeral.getLeaseId());
        dto.setLeaseDuration(ephemeral.getLeaseDuration());
        return dto;
    }
}
