package raum.openbao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import raum.config.ServiceTokenProperties;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code X-Vault-Token} vetting logic that gates
 * {@code POST /credentials/ephemeral}. Exercises {@link OpenBaoService#classifyServiceToken}
 * directly with hand-built {@code auth/token/lookup-self} response bodies — no OpenBao needed.
 */
class OpenBaoServiceResolveTokenTest {

    private OpenBaoService service;

    @BeforeEach
    void setUp() {
        ServiceTokenProperties props = new ServiceTokenProperties();
        props.setServiceTokens(Map.of(
                "bime-policy", ServiceIdentity.BIME,
                "vassago-policy", ServiceIdentity.VASSAGO,
                "raum-service-policy", ServiceIdentity.RAUM));
        service = new OpenBaoService("http://localhost:8200", "secret", props);
    }

    private static Map<String, Object> lookup(Object policies, Object path, Object meta) {
        return Map.of("data", Map.of(
                "policies", policies,
                "path", path,
                "meta", meta));
    }

    @Test
    void recognisesAKnownServiceApproleToken() {
        Map<String, Object> body = lookup(
                List.of("default", "bime-policy"), "auth/approle/login", Map.of("role_name", "bime"));

        StepVerifier.create(service.classifyServiceToken(body))
                .expectNext(ServiceIdentity.BIME)
                .verifyComplete();
    }

    @Test
    void rejectsRootToken() {
        Map<String, Object> body = lookup(
                List.of("root"), "auth/token/root", Map.of());
        StepVerifier.create(service.classifyServiceToken(body)).verifyError();
    }

    @Test
    void rejectsNonApproleToken() {
        Map<String, Object> body = lookup(
                List.of("default", "vassago-policy"), "auth/userpass/login", Map.of("role_name", "x"));
        StepVerifier.create(service.classifyServiceToken(body)).verifyError();
    }

    @Test
    void rejectsApproleTokenWithNoKnownServicePolicy() {
        Map<String, Object> body = lookup(
                List.of("default", "some-other-policy"), "auth/approle/login", Map.of("role_name", "x"));
        StepVerifier.create(service.classifyServiceToken(body)).verifyError();
    }

    @Test
    void rejectsTokenMissingRoleNameMeta() {
        Map<String, Object> body = lookup(
                List.of("default", "vassago-policy"), "auth/approle/login", Map.of());
        StepVerifier.create(service.classifyServiceToken(body)).verifyError();
    }
}
