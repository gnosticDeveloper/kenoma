package raum.onboarding;

import common.dto.CredentialsDTO;
import raum.dto.OnboardingRequestDTO;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public interface OnboardingStrategy {
    UUID getServiceId();
    Mono<Void> execute(UUID orgId, CredentialsDTO credentials, OnboardingRequestDTO request, OnboardingContext context);

    default Map<String, String> extractConfig(OnboardingRequestDTO request) {
        return Map.of();
    }

    default OnboardingRequestDTO buildRequestFromConfig(Map<String, String> config) {
        return new OnboardingRequestDTO();
    }
}
