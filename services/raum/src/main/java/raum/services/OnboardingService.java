package raum.services;

import common.dto.CredentialsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import raum.dto.OnboardingRequestDTO;
import raum.onboarding.OnboardingConfigStore;
import raum.onboarding.OnboardingContext;
import raum.onboarding.OnboardingStrategy;
import raum.openbao.OpenBaoService;
import raum.repository.CredentialsRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final CredentialsRepository credentialsRepository;
    private final OpenBaoService openBaoService;
    private final OnboardingConfigStore configStore;
    private final List<OnboardingStrategy> strategies;

    public Mono<Void> initiateOnboarding(UUID orgId, OnboardingRequestDTO request) {
        OnboardingContext context = new OnboardingContext();
        return Flux.fromIterable(strategies)
                .concatMap(strategy -> credentialsRepository.findByOrgIdAndServiceId(orgId, strategy.getServiceId())
                        .flatMap(credentials -> openBaoService.issueEphemeralCredentials(credentials.getId())
                                .flatMap(ephemeral -> {
                                    CredentialsDTO dto = new CredentialsDTO();
                                    dto.setOrgId(credentials.getOrgId());
                                    dto.setServiceId(credentials.getServiceId());
                                    dto.setUserName(ephemeral.getUserName());
                                    dto.setPassword(ephemeral.getPassword());
                                    dto.setDbHost(credentials.getDbHost());
                                    dto.setDbPort(credentials.getDbPort());
                                    dto.setDbName(credentials.getDbName());
                                    dto.setDbEngine(credentials.getDbEngine());
                                    dto.setLeaseId(ephemeral.getLeaseId());
                                    dto.setLeaseDuration(ephemeral.getLeaseDuration());
                                    Map<String, String> config = strategy.extractConfig(request);
                                    Mono<Void> saveConfig = config.isEmpty()
                                            ? Mono.empty()
                                            : configStore.save(credentials.getId(), config);
                                    return saveConfig
                                            .then(strategy.execute(orgId, dto, request, context))
                                            .then(Mono.defer(() -> {
                                                credentials.setInitialized(true);
                                                return credentialsRepository.save(credentials).then();
                                            }));
                                }))
                )
                .then(Mono.defer(() -> context.getCleanup() != null ? context.getCleanup() : Mono.empty()));
    }
}
