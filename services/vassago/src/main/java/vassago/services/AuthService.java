package vassago.services;

import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
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
import vassago.db.VassagoDbService;
import vassago.dto.LoginRequestDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.security.JwtService;
import vassago.security.RedisTokenService;
import vassago.security.VassagoAuthentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String REFRESH_COOKIE = "session-rt";
    private static final String FP_COOKIE = "session-fp";

    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailgunService mailgunService;
    private final RedisTokenService redisTokenService;

    @Value("${vassago.refresh-token.ttl-seconds:2592000}")
    private long refreshTtlSeconds;

    @Value("${vassago.cookie.domain:}")
    private String cookieDomain;

    public Mono<String> login(LoginRequestDTO dto, ServerHttpResponse response) {
        return vassagoDbService.getClient(dto.getOrgId())
                .onErrorMap(NotFoundException.class, ex -> new UnauthorizedException("Invalid credentials"))
                .flatMap(client -> client.sql("""
                        SELECT id, username, password, roles
                        FROM users
                        WHERE username = :username AND stopped_at IS NULL AND is_ready
                        """)
                        .bind("username", dto.getUsername())
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
                                String rtRaw = generateToken();
                                String rtHash = hashToken(rtRaw);
                                String fpRaw = generateToken();
                                String fpHash = hashToken(fpRaw);
                                return redisTokenService.storeRefreshToken(rtHash, dto.getOrgId(), dto.getUsername(), fpHash)
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
        String rtHash = hashToken(rtCookie.getValue());
        return redisTokenService.lookupRefreshToken(rtHash)
                .switchIfEmpty(Mono.error(new UnauthorizedException("Invalid or expired session")))
                .flatMap(data -> {
                    HttpCookie fpCookie = exchange.getRequest().getCookies().getFirst(FP_COOKIE);
                    if (fpCookie == null || !hashToken(fpCookie.getValue()).equals(data.fpHash())) {
                        return redisTokenService.deleteRefreshToken(rtHash)
                                .then(Mono.error(new UnauthorizedException("Session binding mismatch")));
                    }
                    return vassagoDbService.getClient(data.orgId())
                            .flatMap(client -> client.sql("""
                                    SELECT id, roles FROM users
                                    WHERE username = :username AND stopped_at IS NULL AND is_ready
                                    """)
                                    .bind("username", data.username())
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
                                String newRtRaw = generateToken();
                                String newRtHash = hashToken(newRtRaw);
                                String newFpRaw = generateToken();
                                String newFpHash = hashToken(newFpRaw);
                                return redisTokenService.deleteRefreshToken(rtHash)
                                        .then(redisTokenService.storeRefreshToken(newRtHash, data.orgId(), data.username(), newFpHash))
                                        .then(Mono.fromRunnable(() -> {
                                            setCookie(exchange.getResponse(), REFRESH_COOKIE, newRtRaw);
                                            setCookie(exchange.getResponse(), FP_COOKIE, newFpRaw);
                                        }))
                                        .thenReturn(token);
                            });
                });
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
                            ? redisTokenService.deleteRefreshToken(hashToken(rtCookie.getValue()))
                            : Mono.empty();
                    clearCookies(exchange.getResponse());
                    return blacklistMono.then(deleteMono);
                });
    }

    public Mono<Void> recoverAccount(RecoverRequestDTO dto) {
        return vassagoDbService.getClient(dto.getOrgId())
                .onErrorResume(NotFoundException.class, ex -> Mono.empty())
                .flatMap(client -> client.sql("""
                        SELECT id, email FROM users
                        WHERE username = :username AND stopped_at IS NULL
                        """)
                        .bind("username", dto.getUsername())
                        .fetch()
                        .one()
                        .flatMap(row -> {
                            UUID userId = (UUID) row.get("id");
                            String email = (String) row.get("email");
                            String verificationToken = generateToken();
                            String tokenHash = hashToken(verificationToken);
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
                                    .then(mailgunService.sendPasswordResetEmail(email, dto.getOrgId(), verificationToken));
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
                .sameSite("Strict")
                .maxAge(maxAge)
                .path("/auth");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        return builder;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
