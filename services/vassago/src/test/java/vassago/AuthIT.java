package vassago;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.dto.PasswordChangeRequestDTO;
import vassago.dto.PublicKeyResponseDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.dto.VerifyTokenRequestDTO;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        reset(mailgunService);
    }

    private EntityExchangeResult<LoginResponseDTO> login(String username, String password) {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setOrgId(orgId);
        dto.setUsername(username);
        dto.setPassword(password);
        return webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponseDTO.class)
                .returnResult();
    }

    @Test
    void logout_blacklistsJwt_andDeletesRefreshToken() {
        EntityExchangeResult<LoginResponseDTO> loginResult = login(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String jwt = loginResult.getResponseBody().token();
        ResponseCookie rtCookie = loginResult.getResponseCookies().getFirst("session-rt");
        ResponseCookie fpCookie = loginResult.getResponseCookies().getFirst("session-fp");

        assertThat(rtCookie).isNotNull();
        assertThat(fpCookie).isNotNull();
        assertThat(rtCookie.getDomain()).isEqualTo(".test.local");
        assertThat(fpCookie.getDomain()).isEqualTo(".test.local");

        webTestClient.post()
                .uri("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .cookie("session-rt", rtCookie.getValue())
                .cookie("session-fp", fpCookie.getValue())
                .exchange()
                .expectStatus().isNoContent();

        // blacklisted JWT is rejected on protected endpoints
        webTestClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isUnauthorized();

        // refresh token was deleted — refresh attempt fails
        webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", rtCookie.getValue())
                .cookie("session-fp", fpCookie.getValue())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refresh_issuesNewJwt_andRotatesRefreshToken() {
        EntityExchangeResult<LoginResponseDTO> loginResult = login(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String originalJwt = loginResult.getResponseBody().token();
        String originalRt = loginResult.getResponseCookies().getFirst("session-rt").getValue();
        String originalFp = loginResult.getResponseCookies().getFirst("session-fp").getValue();

        EntityExchangeResult<LoginResponseDTO> refreshResult = webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", originalRt)
                .cookie("session-fp", originalFp)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponseDTO.class)
                .returnResult();

        String newJwt = refreshResult.getResponseBody().token();
        assertThat(newJwt).isNotBlank().isNotEqualTo(originalJwt);

        // new JWT works on protected endpoints
        webTestClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + newJwt)
                .exchange()
                .expectStatus().isOk();

        // old refresh token is gone after rotation
        webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", originalRt)
                .cookie("session-fp", originalFp)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refresh_replayOfRotatedToken_revokesTheSessionItSpawned() throws Exception {
        // A refresh token being presented again after it was already rotated out is the
        // signature of token theft (a stolen copy racing — or trailing — the legitimate
        // client's own refresh). The session born from that legitimate rotation must not be
        // left usable just because the replay itself gets a 401.
        EntityExchangeResult<LoginResponseDTO> loginResult = login(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String originalRt = loginResult.getResponseCookies().getFirst("session-rt").getValue();
        String originalFp = loginResult.getResponseCookies().getFirst("session-fp").getValue();

        EntityExchangeResult<LoginResponseDTO> refreshResult = webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", originalRt)
                .cookie("session-fp", originalFp)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponseDTO.class)
                .returnResult();
        String rotatedJwt = refreshResult.getResponseBody().token();

        // Confirm the token born from the legitimate rotation works before the replay.
        webTestClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedJwt)
                .exchange()
                .expectStatus().isOk();

        // Revocation only catches tokens issued strictly before the revocation instant (JWT
        // `iat` is second-granularity) — push the replay into a later wall-clock second so the
        // assertion isn't timing-flaky.
        Thread.sleep(1100);

        // Replay the original (already-rotated) refresh token.
        webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", originalRt)
                .cookie("session-fp", originalFp)
                .exchange()
                .expectStatus().isUnauthorized();

        // The session that resulted from the legitimate rotation is now revoked too.
        webTestClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedJwt)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refresh_concurrentCallsOnSameToken_onlyOneSucceeds() throws Exception {
        // lookupRefreshToken-then-rotate wasn't atomic, so two requests racing on the same
        // still-valid token could both pass the check before either rotated it out, both minting
        // a new session from the same original token. Only one should ever win.
        EntityExchangeResult<LoginResponseDTO> loginResult = login(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String rt = loginResult.getResponseCookies().getFirst("session-rt").getValue();
        String fp = loginResult.getResponseCookies().getFirst("session-fp").getValue();
        String cookieHeader = "session-rt=" + rt + "; session-fp=" + fp;

        int concurrency = 8;
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + port).build();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReferenceArray<String> results = new AtomicReferenceArray<>(concurrency);

        try {
            List<Runnable> tasks = IntStream.range(0, concurrency)
                    .<Runnable>mapToObj(i -> () -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            LoginResponseDTO body = webClient.post()
                                    .uri("/auth/refresh")
                                    .header(HttpHeaders.COOKIE, cookieHeader)
                                    .retrieve()
                                    .bodyToMono(LoginResponseDTO.class)
                                    .block();
                            results.set(i, body != null ? "OK:" + body.token() : "OK:null");
                        } catch (Exception e) {
                            results.set(i, "FAILED");
                        }
                    })
                    .collect(Collectors.toList());
            tasks.forEach(pool::execute);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long successCount = IntStream.range(0, concurrency)
                .mapToObj(results::get)
                .filter(r -> r.startsWith("OK:"))
                .count();
        Set<String> distinctTokens = IntStream.range(0, concurrency)
                .mapToObj(results::get)
                .filter(r -> r.startsWith("OK:"))
                .collect(Collectors.toSet());

        assertThat(successCount).as("exactly one concurrent refresh should win the race").isEqualTo(1);
        assertThat(distinctTokens).hasSize(1);
    }

    @Test
    void refresh_returns401_onFingerprintMismatch_andDeletesRefreshToken() {
        EntityExchangeResult<LoginResponseDTO> loginResult = login(BOOTSTRAP_USERNAME, BOOTSTRAP_PASSWORD);
        String rt = loginResult.getResponseCookies().getFirst("session-rt").getValue();

        // send a tampered fingerprint
        webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", rt)
                .cookie("session-fp", "tampered-fingerprint-value")
                .exchange()
                .expectStatus().isUnauthorized();

        // refresh token was deleted due to binding mismatch
        webTestClient.post()
                .uri("/auth/refresh")
                .cookie("session-rt", rt)
                .cookie("session-fp", "tampered-fingerprint-value")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void publicKey_returnsValidPem() {
        webTestClient.get()
                .uri("/auth/public-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(PublicKeyResponseDTO.class)
                .value(dto -> {
                    assertThat(dto.publicKey()).contains("-----BEGIN PUBLIC KEY-----");
                    assertThat(dto.publicKey()).contains("-----END PUBLIC KEY-----");
                });
    }

    @Test
    void login_returns401_forWrongPassword() {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setOrgId(orgId);
        dto.setUsername(BOOTSTRAP_USERNAME);
        dto.setPassword("wrong-password-that-does-not-match");

        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void login_missingUsername_returns400NotUnhandled500() {
        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("orgId", orgId.toString(), "password", "irrelevant"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void refresh_returns401_whenNoCookiePresent() {
        webTestClient.post()
                .uri("/auth/refresh")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void recoverAccount_sendsEmail_forExistingUser() {
        when(mailgunService.sendPasswordResetEmail(anyString(), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());

        RecoverRequestDTO dto = new RecoverRequestDTO(orgId, BOOTSTRAP_USERNAME);
        webTestClient.post()
                .uri("/auth/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNoContent();

        verify(mailgunService).sendPasswordResetEmail(anyString(), eq(orgId), anyString(), anyString());
    }

    @Test
    void passwordChange_fullFlow() {
        when(mailgunService.sendPasswordResetEmail(anyString(), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());

        EntityExchangeResult<LoginResponseDTO> loginResult = login(CHANGEPW_USERNAME, CHANGEPW_PASSWORD);
        String jwt = loginResult.getResponseBody().token();

        PasswordChangeRequestDTO changeRequest = new PasswordChangeRequestDTO();
        changeRequest.setOldPassword(CHANGEPW_PASSWORD);

        webTestClient.patch()
                .uri("/user/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(changeRequest)
                .exchange()
                .expectStatus().isNoContent();

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailgunService).sendPasswordResetEmail(anyString(), eq(orgId), tokenCaptor.capture(), anyString());
        String verificationToken = tokenCaptor.getValue();

        String newPassword = "N3wP@ssw0rd!99";
        VerifyTokenRequestDTO verifyRequest = new VerifyTokenRequestDTO();
        verifyRequest.setOrgId(orgId);
        verifyRequest.setToken(verificationToken);
        verifyRequest.setNewPassword(newPassword);

        webTestClient.post()
                .uri("/user/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(verifyRequest)
                .exchange()
                .expectStatus().isNoContent();

        // old password no longer works
        LoginRequestDTO oldLogin = new LoginRequestDTO();
        oldLogin.setOrgId(orgId);
        oldLogin.setUsername(CHANGEPW_USERNAME);
        oldLogin.setPassword(CHANGEPW_PASSWORD);
        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(oldLogin)
                .exchange()
                .expectStatus().isUnauthorized();

        // new password works
        LoginRequestDTO newLogin = new LoginRequestDTO();
        newLogin.setOrgId(orgId);
        newLogin.setUsername(CHANGEPW_USERNAME);
        newLogin.setPassword(newPassword);
        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newLogin)
                .exchange()
                .expectStatus().isOk();

        // The verification token was consumed by the first /user/verify call above - presenting
        // it again must not succeed a second time.
        webTestClient.post()
                .uri("/user/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(verifyRequest)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void protectedEndpoint_malformedJwt_returns401NotUnhandled500() {
        webTestClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt-at-all")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedEndpoint_wellFormedButUnsignedJwt_returns401NotUnhandled500() {
        // Well-formed (three base64 segments) but not actually signed by this vassago instance's
        // key - must be rejected as invalid, not crash trying to read claims off it.
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"nobody\"}".getBytes());
        String forged = header + "." + payload + ".bogus-signature";

        webTestClient.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
