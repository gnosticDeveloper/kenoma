package vassago.services;

import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
import common.mail.MailgunService;
import common.security.VerificationTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import vassago.clients.RaumClient;
import vassago.db.VassagoDbService;
import vassago.dto.LoginRequestDTO;
import vassago.security.JwtService;
import vassago.security.RedisTokenService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the org-deactivation login gap: previously AuthService.login/refresh
 * only checked the user's own stopped_at, never the org's - a deactivated org's user could still
 * log in (and even refresh a session for up to 30 days) as long as Vassago's per-org DB connection
 * pool happened to still be warm, since the only place that incidentally caught this was the
 * ephemeral-credential fetch on a cold pool. See project_org_deactivation_cascade_gap memory.
 *
 * <p>Deliberately a unit test, not an IT: exercising this against the real shared IT org (see
 * vassago.BaseIT) would mean deactivating state shared across every IT class in the module, which
 * is both risky (test-order/parallelism corruption) and unnecessary - raum's own org-active
 * mechanism is already covered by raum.OrganizationIT and the SecurityConfig fix is proven by the
 * whole vassago IT suite passing against the real endpoint. What's specific to Vassago, and what
 * this test actually covers, is that AuthService calls and respects that check at all.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private VassagoDbService vassagoDbService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private MailgunService mailgunService;
    @Mock
    private RedisTokenService redisTokenService;
    @Mock
    private VerificationTokenService verificationTokenService;
    @Mock
    private RaumClient raumClient;

    private AuthService authService;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authService = new AuthService(vassagoDbService, passwordEncoder, jwtService, mailgunService,
                redisTokenService, verificationTokenService, raumClient);
    }

    private LoginRequestDTO loginRequest() {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setOrgId(orgId);
        dto.setUsername("alice");
        dto.setPassword("whatever-not-checked-in-this-test");
        return dto;
    }

    @Test
    void login_rejectsDeactivatedOrg_withoutTouchingDb() {
        when(raumClient.isOrgActive(orgId)).thenReturn(Mono.just(false));
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        StepVerifier.create(authService.login(loginRequest(), response))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(UnauthorizedException.class);
                    assertThat(e.getMessage()).isEqualTo("Invalid credentials");
                })
                .verify();

        // The whole point: a deactivated org must be rejected before ever touching the DB, not
        // incidentally via a connection-pool failure that only sometimes fires.
        verifyNoInteractions(vassagoDbService);
    }

    @Test
    void login_proceedsPastOrgCheck_whenOrgActive() {
        when(raumClient.isOrgActive(orgId)).thenReturn(Mono.just(true));
        when(vassagoDbService.getClient(orgId)).thenReturn(Mono.error(new NotFoundException("no client")));
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        StepVerifier.create(authService.login(loginRequest(), response))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(UnauthorizedException.class))
                .verify();

        // Existing behavior preserved: an active org still reaches the DB lookup.
        verify(vassagoDbService).getClient(orgId);
    }

    @Test
    void refresh_rejectsDeactivatedOrg_withoutTouchingDb() {
        UUID userId = UUID.randomUUID();
        String rtRaw = "raw-refresh-token";
        String fpRaw = "raw-fingerprint";
        String fpHash = "hashed-fingerprint";

        lenient().when(verificationTokenService.hashToken(rtRaw)).thenReturn("hashed-rt");
        lenient().when(verificationTokenService.hashToken(fpRaw)).thenReturn(fpHash);
        when(redisTokenService.consumeRefreshToken("hashed-rt"))
                .thenReturn(Mono.just(new RedisTokenService.RefreshTokenData(orgId, userId, "alice", fpHash)));
        when(raumClient.isOrgActive(orgId)).thenReturn(Mono.just(false));

        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/refresh")
                .cookie(new HttpCookie("session-rt", rtRaw), new HttpCookie("session-fp", fpRaw))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(authService.refresh(exchange))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(UnauthorizedException.class);
                    assertThat(e.getMessage()).isEqualTo("Invalid or expired session");
                })
                .verify();

        verifyNoInteractions(vassagoDbService);
    }
}
