package vassago.services;

import common.exception.BadRequestException;
import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
import common.mail.MailgunService;
import common.security.VerificationTokenService;
import common.utils.RolesUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import vassago.clients.RaumClient;
import vassago.db.VassagoDbService;
import vassago.dto.LoginRequestDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.security.JwtService;
import vassago.security.RedisTokenService;
import vassago.security.VassagoAuthentication;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String REFRESH_COOKIE = "session-rt";
    private static final String FP_COOKIE = "session-fp";

    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailgunService mailgunService;
    private final RedisTokenService redisTokenService;
    private final VerificationTokenService verificationTokenService;
    private final RaumClient raumClient;

    @Value("${vassago.refresh-token.ttl-seconds:2592000}")
    private long refreshTtlSeconds;

    @Value("${vassago.jwt.ttl-seconds:300}")
    private long jwtTtlSeconds;

    @Value("${vassago.cookie.domain:}")
    private String cookieDomain;

    public Mono<String> login(LoginRequestDTO dto, ServerHttpResponse response) {
        if (dto.getOrgId() == null || dto.getUsername() == null || dto.getUsername().isBlank()
                || dto.getPassword() == null || dto.getPassword().isBlank()) {
            return Mono.error(new BadRequestException("orgId, username, and password are required"));
        }
        return raumClient.isOrgActive(dto.getOrgId())
                .flatMap(active -> active ? loginInternal(dto, response) : Mono.error(new UnauthorizedException("Invalid credentials")));
    }

    private Mono<String> loginInternal(LoginRequestDTO dto, ServerHttpResponse response) {
        return vassagoDbService.getClient(dto.getOrgId())
                .onErrorMap(NotFoundException.class, ex -> new UnauthorizedException("Invalid credentials"))
                .flatMap(client -> client.sql("""
                        SELECT id, username, password, roles
                        FROM users
                        WHERE username = :username AND org_id = :orgId AND stopped_at IS NULL AND is_ready
                        """)
                        .bind("username", dto.getUsername())
                        .bind("orgId", dto.getOrgId())
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(new UnauthorizedException("Invalid credentials")))
                )
                .flatMap(row -> {
                    String storedHash = (String) row.get("password");
                    if (!passwordEncoder.matches(dto.getPassword(), storedHash)) {
                        return Mono.error(new UnauthorizedException("Invalid credentials"));
                    }
                    UUID userId = (UUID) row.get("id");
                    Map<String, List<String>> roles = RolesUtils.deserialize((String) row.get("roles"));
                    return jwtService.issueToken(dto.getOrgId(), userId, roles)
                            .flatMap(token -> {
                                String rtRaw = verificationTokenService.generateToken();
                                String rtHash = verificationTokenService.hashToken(rtRaw);
                                String fpRaw = verificationTokenService.generateToken();
                                String fpHash = verificationTokenService.hashToken(fpRaw);
                                return redisTokenService.storeRefreshToken(rtHash, dto.getOrgId(), userId, dto.getUsername(), fpHash)
                                        .then(Mono.fromRunnable(() -> {
                                            setCookie(response, REFRESH_COOKIE, rtRaw);
                                            setCookie(response, FP_COOKIE, fpRaw);
                                        }))
                                        .thenReturn(token);
                            });
                });
    }

    public Mono<String> refresh(ServerWebExchange exchange) {
        HttpCookie rtCookie = exchange.getRequest().getCookies().getFirst(REFRESH_COOKIE);
        if (rtCookie == null) {
            return Mono.error(new UnauthorizedException("No session"));
        }
        String rtHash = verificationTokenService.hashToken(rtCookie.getValue());
        // consumeRefreshToken atomically reads-and-removes the entry (Redis GETDEL), so two
        // requests racing on the same still-valid token can't both observe live data — only one
        // proceeds past this point, the other falls straight through to handlePossibleReplay
        // having simply lost the race (not because the token was already known-replayed).
        return redisTokenService.consumeRefreshToken(rtHash)
                .switchIfEmpty(Mono.defer(() -> handlePossibleReplay(rtHash)))
                .flatMap(data -> {
                    HttpCookie fpCookie = exchange.getRequest().getCookies().getFirst(FP_COOKIE);
                    if (fpCookie == null || !verificationTokenService.hashToken(fpCookie.getValue()).equals(data.fpHash())) {
                        return Mono.error(new UnauthorizedException("Session binding mismatch"));
                    }
                    // Same explicit check as login() - a refresh token can live up to 30 days, so
                    // without this a deactivated org's user could keep extending their session
                    // indefinitely as long as their pooled DB connection stayed warm.
                    return raumClient.isOrgActive(data.orgId())
                            .flatMap(active -> active
                                    ? refreshInternal(data, rtHash, exchange)
                                    : Mono.error(new UnauthorizedException("Invalid or expired session")));
                });
    }

    private Mono<String> refreshInternal(RedisTokenService.RefreshTokenData data, String rtHash, ServerWebExchange exchange) {
        return vassagoDbService.getClient(data.orgId())
                .flatMap(client -> client.sql("""
                        SELECT id, roles FROM users
                        WHERE username = :username AND org_id = :orgId AND stopped_at IS NULL AND is_ready
                        """)
                        .bind("username", data.username())
                        .bind("orgId", data.orgId())
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(new UnauthorizedException("Invalid or expired session")))
                )
                .flatMap(row -> {
                    UUID userId = (UUID) row.get("id");
                    Map<String, List<String>> roles = RolesUtils.deserialize((String) row.get("roles"));
                    return jwtService.issueToken(data.orgId(), userId, roles);
                })
                .flatMap(token -> {
                    String newRtRaw = verificationTokenService.generateToken();
                    String newRtHash = verificationTokenService.hashToken(newRtRaw);
                    String newFpRaw = verificationTokenService.generateToken();
                    String newFpHash = verificationTokenService.hashToken(newFpRaw);
                    return redisTokenService.markRotated(rtHash, data.userId())
                            .then(redisTokenService.storeRefreshToken(newRtHash, data.orgId(), data.userId(), data.username(), newFpHash))
                            .then(Mono.fromRunnable(() -> {
                                setCookie(exchange.getResponse(), REFRESH_COOKIE, newRtRaw);
                                setCookie(exchange.getResponse(), FP_COOKIE, newFpRaw);
                            }))
                            .thenReturn(token);
                });
    }

    /**
     * A refresh-token hash with no live entry is either genuinely unknown/expired, or it's a
     * replay of a token that was already rotated out — the signature of a stolen token racing (or
     * following) the legitimate client's own refresh. In the replay case, the session that resulted
     * from the legitimate rotation is revoked immediately rather than left to run its course,
     * since presenting the old token again means whoever holds it wasn't the one who rotated it.
     */
    private Mono<RedisTokenService.RefreshTokenData> handlePossibleReplay(String rtHash) {
        return redisTokenService.checkReplayedToken(rtHash)
                .flatMap(userId -> redisTokenService.revokeUserTokens(userId, jwtTtlSeconds))
                .then(Mono.error(new UnauthorizedException("Invalid or expired session")));
    }

    public Mono<Void> logout(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (VassagoAuthentication) ctx.getAuthentication())
                .flatMap(auth -> {
                    long remainingSeconds = jwtService.remainingSeconds(auth.getExpiry());
                    HttpCookie rtCookie = exchange.getRequest().getCookies().getFirst(REFRESH_COOKIE);
                    Mono<Void> blacklistMono = remainingSeconds > 0
                            ? redisTokenService.blacklistJwt(auth.getJti(), remainingSeconds)
                            : Mono.empty();
                    Mono<Void> deleteMono = rtCookie != null
                            ? redisTokenService.deleteRefreshToken(verificationTokenService.hashToken(rtCookie.getValue()))
                            : Mono.empty();
                    clearCookies(exchange.getResponse());
                    return blacklistMono.then(deleteMono);
                });
    }

    public Mono<Void> recoverAccount(RecoverRequestDTO dto) {
        return vassagoDbService.getClient(dto.getOrgId())
                .onErrorResume(NotFoundException.class, ex -> Mono.empty())
                .flatMap(client -> client.sql("""
                        SELECT id, email, locale FROM users
                        WHERE username = :username AND org_id = :orgId AND stopped_at IS NULL
                        """)
                        .bind("username", dto.getUsername())
                        .bind("orgId", dto.getOrgId())
                        .fetch()
                        .one()
                        .flatMap(row -> {
                            UUID userId = (UUID) row.get("id");
                            String email = (String) row.get("email");
                            String locale = (String) row.get("locale");
                            String verificationToken = verificationTokenService.generateToken();
                            String tokenHash = verificationTokenService.hashToken(verificationToken);
                            Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
                            return client.sql("""
                                    INSERT INTO pending_verifications (user_id, token_hash, expires_at, type)
                                    VALUES (:userId, :tokenHash, :expiresAt, 'ACCOUNT_RECOVERY')
                                    """)
                                    .bind("userId", userId)
                                    .bind("tokenHash", tokenHash)
                                    .bind("expiresAt", expiresAt)
                                    .fetch()
                                    .rowsUpdated()
                                    .then(mailgunService.sendPasswordResetEmail(email, dto.getOrgId(), verificationToken, locale));
                        })
                )
                .then();
    }

    private void setCookie(ServerHttpResponse response, String name, String value) {
        response.addCookie(cookieBuilder(name, value, Duration.ofSeconds(refreshTtlSeconds)).build());
    }

    private void clearCookies(ServerHttpResponse response) {
        for (String name : new String[]{REFRESH_COOKIE, FP_COOKIE}) {
            response.addCookie(cookieBuilder(name, "", Duration.ZERO).build());
        }
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(String name, String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .maxAge(maxAge)
                .path("/auth");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        return builder;
    }
}
