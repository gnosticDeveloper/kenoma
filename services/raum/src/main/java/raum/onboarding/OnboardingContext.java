package raum.onboarding;

import reactor.core.publisher.Mono;

import java.util.UUID;

public class OnboardingContext {
    private volatile String jwt;
    private volatile UUID tempAdminId;
    private volatile Mono<Void> cleanup;

    public String getJwt() { return jwt; }
    public void setJwt(String jwt) { this.jwt = jwt; }

    public UUID getTempAdminId() { return tempAdminId; }
    public void setTempAdminId(UUID tempAdminId) { this.tempAdminId = tempAdminId; }

    public Mono<Void> getCleanup() { return cleanup; }
    public void setCleanup(Mono<Void> cleanup) { this.cleanup = cleanup; }
}
