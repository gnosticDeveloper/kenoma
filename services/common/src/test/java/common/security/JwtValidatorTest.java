package common.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JwtValidatorTest {

    private KeyPair keyPair;
    private String publicKeyPem;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = kpg.generateKeyPair();
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebClient mockClientReturning(Map<String, Object> response) {
        WebClient mockClient = mock(WebClient.class);
        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        doReturn(uriSpec).when(mockClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString(), (Object[]) any());
        doReturn(responseSpec).when(headersSpec).retrieve();
        doReturn(reactor.core.publisher.Mono.just(response)).when(responseSpec).bodyToMono(Map.class);

        return mockClient;
    }

    private Map<String, Object> openBaoKeyResponse() {
        Map<String, Object> keyEntry = Map.of("public_key", publicKeyPem);
        Map<String, Object> keys = Map.of("1", keyEntry);
        Map<String, Object> data = Map.of("keys", keys);
        return Map.of("data", data);
    }

    private void primeCache(JwtValidator validator, PublicKey key) throws Exception {
        Field f = JwtValidator.class.getDeclaredField("cachedPublicKey");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<PublicKey> ref = (AtomicReference<PublicKey>) f.get(validator);
        ref.set(key);
    }

    @Test
    void getPublicKey_fetchesFromOpenBao_whenCacheIsEmpty() {
        JwtValidator validator = new JwtValidator(mockClientReturning(openBaoKeyResponse()), "vassago-jwt");

        PublicKey result = validator.getPublicKey().block();

        assertThat(result).isNotNull().isEqualTo(keyPair.getPublic());
    }

    @Test
    void getPublicKey_returnsCachedKey_withoutCallingOpenBao() throws Exception {
        WebClient mockClient = mock(WebClient.class);
        JwtValidator validator = new JwtValidator(mockClient, "vassago-jwt");
        primeCache(validator, keyPair.getPublic());

        PublicKey result = validator.getPublicKey().block();

        assertThat(result).isEqualTo(keyPair.getPublic());
        verifyNoInteractions(mockClient);
    }

    @Test
    void getPublicKey_callsOpenBaoOnlyOnce_onRepeatedRequests() {
        WebClient mockClient = mockClientReturning(openBaoKeyResponse());
        JwtValidator validator = new JwtValidator(mockClient, "vassago-jwt");

        validator.getPublicKey().block();
        validator.getPublicKey().block();

        verify(mockClient, times(1)).get();
    }

    @Test
    void validateToken_returnsClaims_forValidToken() {
        JwtValidator validator = new JwtValidator(mockClientReturning(openBaoKeyResponse()), "vassago-jwt");

        String token = Jwts.builder()
                .subject("testuser")
                .claim("orgId", UUID.randomUUID().toString())
                .claim("roles", "{}")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .id(UUID.randomUUID().toString())
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        StepVerifier.create(validator.validateToken(token))
                .assertNext(claims -> assertThat(claims.getSubject()).isEqualTo("testuser"))
                .verifyComplete();
    }

    @Test
    void publicConstructor_createsValidatorWithoutThrowing() {
        JwtValidator validator = new JwtValidator("http://fake-openbao:8200", "fake-token", "test-key");
        assertThat(validator).isNotNull();
    }

    @Test
    void getPublicKey_propagatesError_onInvalidPem() {
        Map<String, Object> badResponse = Map.of("data", Map.of("keys",
                Map.of("1", Map.of("public_key", "not-a-valid-pem"))));
        JwtValidator validator = new JwtValidator(mockClientReturning(badResponse), "vassago-jwt");

        StepVerifier.create(validator.getPublicKey())
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().contains("Failed to parse OpenBao public key"))
                .verify();
    }

    @Test
    void validateToken_fails_forTokenSignedWithDifferentKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair wrongKey = kpg.generateKeyPair();

        JwtValidator validator = new JwtValidator(mockClientReturning(openBaoKeyResponse()), "vassago-jwt");

        String token = Jwts.builder()
                .subject("attacker")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .id(UUID.randomUUID().toString())
                .signWith(wrongKey.getPrivate(), Jwts.SIG.ES256)
                .compact();

        StepVerifier.create(validator.validateToken(token))
                .expectError()
                .verify();
    }
}
