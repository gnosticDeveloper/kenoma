package common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationTokenServiceTest {

    private final VerificationTokenService service = new VerificationTokenService();

    @Test
    void generateToken_isUrlSafeBase64OfCorrectLength() {
        String token = service.generateToken();
        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    void generateToken_producesDistinctValues() {
        assertThat(service.generateToken()).isNotEqualTo(service.generateToken());
    }

    @Test
    void hashToken_isDeterministic() {
        String token = service.generateToken();
        assertThat(service.hashToken(token)).isEqualTo(service.hashToken(token));
    }

    @Test
    void hashToken_differsForDifferentInputs() {
        assertThat(service.hashToken("token-a")).isNotEqualTo(service.hashToken("token-b"));
    }

    @Test
    void hashToken_isHexSha256() {
        String hash = service.hashToken("token");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }
}
