package vassago.openbao;

import common.openbao.AppRoleProvisioningClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Bootstraps Vassago's own OpenBao AppRole at startup instead of relying on an external
 * init container to have already created it and handed Vassago a ready-made token.
 *
 * <p>Uses a narrow "provisioner" credential (permission only to write vassago's own
 * policy/role/secret-id) to create/ensure the real {@code vassago} AppRole, mint a
 * fresh secret-id, and log in as it. The resulting token feeds {@link OpenBaoService}.
 */
@Slf4j
@Component
public class OpenBaoProvisioner {

    private static final String POLICY_NAME = "vassago-policy";
    private static final String ROLE_NAME = "vassago";

    private final OpenBaoService openBaoService;
    private final AppRoleProvisioningClient provisioningClient = new AppRoleProvisioningClient();
    private final String host;
    private final String provisionerRoleId;
    private final String provisionerSecretId;
    private final Resource policyResource;

    public OpenBaoProvisioner(
            OpenBaoService openBaoService,
            @Value("${openbao.base-url}") String host,
            @Value("${vassago.openbao.provisioner.role-id}") String provisionerRoleId,
            @Value("${vassago.openbao.provisioner.secret-id}") String provisionerSecretId,
            @Value("classpath:openbao/vassago-policy.hcl") Resource policyResource) {
        this.openBaoService = openBaoService;
        this.host = host;
        this.provisionerRoleId = provisionerRoleId;
        this.provisionerSecretId = provisionerSecretId;
        this.policyResource = policyResource;
    }

    @PostConstruct
    void provision() {
        provisionOnce()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30)))
                .doOnSuccess(v -> log.info("Vassago AppRole provisioned and logged in successfully"))
                .block();
    }

    Mono<Void> provisionOnce() {
        WebClient anonymousClient = WebClient.builder().baseUrl(host).build();

        return provisioningClient.login(anonymousClient, provisionerRoleId, provisionerSecretId)
                .flatMap(provisionerToken -> {
                    WebClient provisionerClient = WebClient.builder()
                            .baseUrl(host)
                            .defaultHeader("X-Vault-Token", provisionerToken)
                            .build();
                    return provisioningClient.writePolicy(provisionerClient, POLICY_NAME, readPolicyHcl())
                            .then(provisioningClient.ensureAppRole(
                                    provisionerClient, ROLE_NAME, POLICY_NAME, Duration.ofHours(1), Duration.ofHours(24)))
                            .then(provisioningClient.readRoleId(provisionerClient, ROLE_NAME))
                            .flatMap(roleId -> provisioningClient.generateSecretId(provisionerClient, ROLE_NAME)
                                    .flatMap(secretId -> provisioningClient.login(anonymousClient, roleId, secretId)));
                })
                .doOnNext(openBaoService::setToken)
                .then();
    }

    private String readPolicyHcl() {
        try {
            return policyResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + POLICY_NAME + " HCL resource", e);
        }
    }
}
