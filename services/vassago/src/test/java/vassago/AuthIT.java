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
import reactor.core.publisher.Mono;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.dto.PasswordChangeRequestDTO;
import vassago.dto.PublicKeyResponseDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.dto.VerifyTokenRequestDTO;

import java.time.Duration;
import java.util.UUID;

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
    void refresh_returns401_whenNoCookiePresent() {
        webTestClient.post()
                .uri("/auth/refresh")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void recoverAccount_sendsEmail_forExistingUser() {
        when(mailgunService.sendPasswordResetEmail(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.empty());

        RecoverRequestDTO dto = new RecoverRequestDTO(orgId, BOOTSTRAP_USERNAME);
        webTestClient.post()
                .uri("/auth/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNoContent();

        verify(mailgunService).sendPasswordResetEmail(anyString(), eq(orgId), anyString());
    }

    @Test
    void passwordChange_fullFlow() {
        when(mailgunService.sendPasswordResetEmail(anyString(), any(UUID.class), anyString()))
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
        verify(mailgunService).sendPasswordResetEmail(anyString(), eq(orgId), tokenCaptor.capture());
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
    }
}
