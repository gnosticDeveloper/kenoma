package vassago.services;

import common.utils.RolesUtils;
import lombok.RequiredArgsConstructor;
import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import vassago.db.VassagoDbService;
import vassago.dto.LoginRequestDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.security.JwtService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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

    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailgunService mailgunService;

    public Mono<String> login(LoginRequestDTO dto) {
        return vassagoDbService.getClient(dto.getOrgId())
                .onErrorMap(NotFoundException.class, ex -> new UnauthorizedException("Invalid credentials"))
                .flatMap(client -> client.sql("""
                        SELECT username, password, roles
                        FROM users
                        WHERE username = :username AND stopped_at IS NULL AND is_ready
                        """)
                        .bind("username", dto.getUsername())
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(
                                new UnauthorizedException("Invalid credentials")))
                )
                .flatMap(row -> {
                    String storedHash = (String) row.get("password");
                    if (!passwordEncoder.matches(dto.getPassword(), storedHash)) {
                        return Mono.error(new UnauthorizedException("Invalid credentials"));
                    }
                    Map<String, List<String>> roles = RolesUtils.deserialize((String) row.get("roles"));
                    return jwtService.issueToken(dto.getOrgId(), dto.getUsername(), roles);
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
