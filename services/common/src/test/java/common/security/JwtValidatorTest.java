package common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        doReturn(Mono.just(response)).when(responseSpec).bodyToMono(Map.class);

        return mockClient;
    }

    private Map<String, Object> vassagoKeyResponse() {
        return Map.of("publicKey", publicKeyPem);
    }

    private void primeCache(JwtValidator validator, PublicKey key) throws Exception {
        primeCache(validator, key, Instant.now());
    }

    private void primeCache(JwtValidator validator, PublicKey key, Instant fetchedAt) throws Exception {
        Field f = JwtValidator.class.getDeclaredField("cache");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<JwtValidator.CachedKey> ref = (AtomicReference<JwtValidator.CachedKey>) f.get(validator);
        ref.set(new JwtValidator.CachedKey(key, fetchedAt));
    }

    @Test
    void getPublicKey_fetchesFromVassago_whenCacheIsEmpty() {
        JwtValidator validator = new JwtValidator(mockClientReturning(vassagoKeyResponse()));

        PublicKey result = validator.getPublicKey().block();

        assertThat(result).isNotNull().isEqualTo(keyPair.getPublic());
    }

    @Test
    void getPublicKey_returnsCachedKey_withoutCallingVassago() throws Exception {
        WebClient mockClient = mock(WebClient.class);
        JwtValidator validator = new JwtValidator(mockClient);
        primeCache(validator, keyPair.getPublic());

        PublicKey result = validator.getPublicKey().block();

        assertThat(result).isEqualTo(keyPair.getPublic());
        verifyNoInteractions(mockClient);
    }

    @Test
    void getPublicKey_callsVassagoOnlyOnce_onRepeatedRequests() {
        WebClient mockClient = mockClientReturning(vassagoKeyResponse());
        JwtValidator validator = new JwtValidator(mockClient);

        validator.getPublicKey().block();
        validator.getPublicKey().block();

        verify(mockClient, times(1)).get();
    }

    @Test
    void validateToken_returnsClaims_forValidToken() {
        JwtValidator validator = new JwtValidator(mockClientReturning(vassagoKeyResponse()));

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
        JwtValidator validator = new JwtValidator("http://fake-vassago:8081");
        assertThat(validator).isNotNull();
    }

    @Test
    void getPublicKey_propagatesError_onInvalidPem() {
        Map<String, Object> badResponse = Map.of("publicKey", "not-a-valid-pem");
        JwtValidator validator = new JwtValidator(mockClientReturning(badResponse));

        StepVerifier.create(validator.getPublicKey())
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().contains("Failed to parse public key"))
                .verify();
    }

    @Test
    void validateToken_fails_forTokenSignedWithDifferentKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair wrongKey = kpg.generateKeyPair();

        JwtValidator validator = new JwtValidator(mockClientReturning(vassagoKeyResponse()));

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

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void validateToken_retriesWithFreshKey_afterRotation() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair newKeyPair = kpg.generateKeyPair();
        String newPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(newKeyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        String token = Jwts.builder()
                .subject("user")
                .claim("orgId", UUID.randomUUID().toString())
                .claim("roles", "{}")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .id(UUID.randomUUID().toString())
                .signWith(newKeyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        WebClient mockClient = mock(WebClient.class);
        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        doReturn(uriSpec).when(mockClient).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        when(responseSpec.bodyToMono(Map.class))
                .thenReturn(Mono.just(Map.of("publicKey", publicKeyPem)))
                .thenReturn(Mono.just(Map.of("publicKey", newPem)));

        // Zero cooldown so a stale cache always triggers a re-fetch
        JwtValidator validator = new JwtValidator(mockClient, Duration.ZERO);

        StepVerifier.create(validator.validateToken(token))
                .assertNext(claims -> assertThat(claims.getSubject()).isEqualTo("user"))
                .verifyComplete();

        verify(mockClient, times(2)).get();
    }

    @Test
    void refreshCache_fetchesFromVassago_evenWhenCacheIsPopulated() throws Exception {
        WebClient mockClient = mockClientReturning(vassagoKeyResponse());
        JwtValidator validator = new JwtValidator(mockClient);
        primeCache(validator, keyPair.getPublic());

        PublicKey result = validator.refreshCache().block();

        assertThat(result).isEqualTo(keyPair.getPublic());
        verify(mockClient, times(1)).get();
    }

    @Test
    void validateToken_doesNotRetry_whenWithinCooldown() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair wrongKey = kpg.generateKeyPair();

        String token = Jwts.builder()
                .subject("attacker")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .id(UUID.randomUUID().toString())
                .signWith(wrongKey.getPrivate(), Jwts.SIG.ES256)
                .compact();

        WebClient mockClient = mock(WebClient.class);
        JwtValidator validator = new JwtValidator(mockClient, Duration.ofMinutes(5));
        primeCache(validator, keyPair.getPublic(), Instant.now());

        StepVerifier.create(validator.validateToken(token))
                .expectError()
                .verify();

        // Cache was fresh — Vassago must not be called
        verifyNoInteractions(mockClient);
    }
}
