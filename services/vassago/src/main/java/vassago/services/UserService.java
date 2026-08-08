package vassago.services;

import common.mail.MailgunService;
import common.security.VerificationTokenService;
import common.utils.RolesUtils;
import common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.ForbiddenException;
import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.db.VassagoDbService;
import vassago.dto.PasswordChangeRequestDTO;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;
import vassago.dto.VerifyTokenRequestDTO;
import vassago.security.RedisTokenService;
import vassago.security.VassagoAuthentication;
import vassago.security.VassagoRole;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UserService {
    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder encoder;
    private final UUID serviceId;
    private final MailgunService mailgunService;
    private final VerificationTokenService verificationTokenService;
    private final RedisTokenService redisTokenService;

    @Value("${vassago.jwt.ttl-seconds:300}")
    private long jwtTtlSeconds;

    public Mono<UserResponseDTO> createUser(UserRequestDTO dto) {
        if (isBlank(dto.getEmail()) || isBlank(dto.getName()) || isBlank(dto.getLastName()) || isBlank(dto.getUsername())) {
            return Mono.error(new BadRequestException("email, name, lastName, and username are required"));
        }
        return getCaller()
                .flatMap(caller -> {
                    Map<String, List<String>> callerRoles = caller.getRoles();
                    Map<String, List<String>> requestedRoles = dto.getRoles() == null ? Map.of() : dto.getRoles();

                    for (Map.Entry<String, List<String>> entry : requestedRoles.entrySet()) {
                        String service = entry.getKey();
                        List<String> requested = entry.getValue();
                        List<String> callerServiceRoles = callerRoles.getOrDefault(service, List.of());
                        if (!new HashSet<>(callerServiceRoles).containsAll(requested)) {
                            return Mono.error(new ForbiddenException(
                                    "Cannot assign roles not held by the calling user for service: " + service));
                        }
                    }

                    for (String role : requestedRoles.getOrDefault(serviceId.toString(), List.of())) {
                        try {
                            VassagoRole.valueOf(role);
                        } catch (IllegalArgumentException e) {
                            return Mono.error(new BadRequestException("Unknown Vassago role: " + role));
                        }
                    }

                    String placeholderPassword = encoder.encode(verificationTokenService.generateToken());
                    String verificationToken = verificationTokenService.generateToken();
                    String tokenHash = verificationTokenService.hashToken(verificationToken);
                    Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
                    String locale = dto.getLocale() == null || dto.getLocale().isBlank() ? "en" : dto.getLocale();

                    return vassagoDbService.getClient(caller.getOrgId())
                            .flatMap(client -> client.sql("""
                                    INSERT INTO users (name, last_name, email, username, password, roles, locale)
                                    VALUES (:name, :lastName, :email, :username, :password, :roles, :locale)
                                    RETURNING id, name, last_name, email, username, roles
                                    """)
                                    .bind("name", dto.getName())
                                    .bind("lastName", dto.getLastName())
                                    .bind("email", dto.getEmail())
                                    .bind("username", dto.getUsername())
                                    .bind("password", placeholderPassword)
                                    .bind("roles", RolesUtils.serialize(requestedRoles))
                                    .bind("locale", locale)
                                    .fetch()
                                    .one()
                                    .flatMap(row -> {
                                        UUID userId = (UUID) row.get("id");
                                        return client.sql("""
                                                INSERT INTO pending_verifications (user_id, token_hash, expires_at)
                                                VALUES (:userId, :tokenHash, :expiresAt)
                                                """)
                                                .bind("userId", userId)
                                                .bind("tokenHash", tokenHash)
                                                .bind("expiresAt", expiresAt)
                                                .fetch()
                                                .rowsUpdated()
                                                .then(mailgunService.sendVerificationEmail(
                                                        dto.getEmail(), caller.getOrgId(), verificationToken, locale))
                                                .thenReturn(toCreateResponseDTO(row));
                                    })
                                    // username and email are both UNIQUE at the DB level (each org has
                                    // its own database); without this, a violation surfaces as a raw
                                    // unhandled 500 instead of a clean 409.
                                    .onErrorMap(DataIntegrityViolationException.class, e ->
                                            new ConflictException("A user with this username or email already exists"))
                            );
                });
    }

    public Mono<Void> verifyToken(VerifyTokenRequestDTO dto) {
        String tokenHash = verificationTokenService.hashToken(dto.getToken());
        return vassagoDbService.getClient(dto.getOrgId())
                .flatMap(client -> client.sql("""
                        SELECT id, user_id FROM pending_verifications
                        WHERE token_hash = :tokenHash AND used = false AND expires_at > current_timestamp
                        """)
                        .bind("tokenHash", tokenHash)
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(new NotFoundException("Invalid or expired verification token")))
                        .flatMap(row -> {
                            UUID verificationId = (UUID) row.get("id");
                            UUID userId = (UUID) row.get("user_id");
                            if (!StringUtils.isValidPassword(dto.getNewPassword())) {
                                return Mono.error(new BadRequestException("Password does not meet complexity requirements"));
                            }
                            String newPasswordHash = encoder.encode(dto.getNewPassword());
                            return client.sql("""
                                    UPDATE users SET password = :password, is_ready = true
                                    WHERE id = :userId AND stopped_at IS NULL
                                    """)
                                    .bind("password", newPasswordHash)
                                    .bind("userId", userId)
                                    .fetch()
                                    .rowsUpdated()
                                    .then(client.sql("""
                                            UPDATE pending_verifications SET used = true WHERE id = :id
                                            """)
                                            .bind("id", verificationId)
                                            .fetch()
                                            .rowsUpdated())
                                    .then();
                        })
                );
    }

    public Mono<UserResponseDTO> getUserById(UUID id) {
        return getCaller()
                .flatMap(caller -> vassagoDbService.getClient(caller.getOrgId())
                        .flatMap(client -> client.sql("""
                                SELECT id, name, last_name, email, username, roles
                                FROM users WHERE id = :id AND stopped_at IS NULL AND is_ready
                                """)
                                .bind("id", id)
                                .fetch()
                                .one()
                                .map(this::toResponseDTO)
                                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                        )
                );
    }

    public Flux<UserResponseDTO> getUsersByOrgId() {
        return getCaller()
                .flatMapMany(caller -> vassagoDbService.getClient(caller.getOrgId())
                        .flatMapMany(client -> client.sql("""
                                SELECT id, name, last_name, email, username, roles
                                FROM users WHERE stopped_at IS NULL
                                """)
                                .fetch()
                                .all()
                                .map(this::toResponseDTO)
                        )
                );
    }

    public Mono<UserResponseDTO> updateUser(UUID id, UserRequestDTO dto) {
        if (isBlank(dto.getEmail()) || isBlank(dto.getName()) || isBlank(dto.getLastName()) || isBlank(dto.getUsername())) {
            return Mono.error(new BadRequestException("email, name, lastName, and username are required"));
        }
        return getCaller()
                .flatMap(caller -> {
                    boolean isAdmin = caller.getRoles()
                            .values().stream()
                            .flatMap(List::stream)
                            .anyMatch(r -> r.equals(VassagoRole.VASSAGO_ADMIN.name()));

                    Map<String, List<String>> requestedRoles = dto.getRoles() == null ? Map.of() : dto.getRoles();

                    for (String role : requestedRoles.getOrDefault(serviceId.toString(), List.of())) {
                        try {
                            VassagoRole.valueOf(role);
                        } catch (IllegalArgumentException e) {
                            return Mono.error(new BadRequestException("Unknown Vassago role: " + role));
                        }
                    }

                    // Same invariant as createUser: a caller can only grant roles they already hold
                    // themselves. Without this, a non-admin editing their own account (allowed below)
                    // could self-elevate to VASSAGO_ADMIN by simply naming it in the request.
                    Map<String, List<String>> callerRoles = caller.getRoles();
                    for (Map.Entry<String, List<String>> entry : requestedRoles.entrySet()) {
                        String service = entry.getKey();
                        List<String> requested = entry.getValue();
                        List<String> callerServiceRoles = callerRoles.getOrDefault(service, List.of());
                        if (!new HashSet<>(callerServiceRoles).containsAll(requested)) {
                            return Mono.error(new ForbiddenException(
                                    "Cannot assign roles not held by the calling user for service: " + service));
                        }
                    }

                    return vassagoDbService.getClient(caller.getOrgId())
                            .flatMap(client -> client.sql("""
                                    SELECT id FROM users
                                    WHERE id = :id AND stopped_at IS NULL
                                    """)
                                    .bind("id", id)
                                    .fetch()
                                    .one()
                                    .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                                    .flatMap(row -> {
                                        UUID targetId = (UUID) row.get("id");
                                        if (!isAdmin && !caller.getId().equals(targetId)) {
                                            return Mono.error(new ForbiddenException("Cannot edit another user"));
                                        }
                                        return client.sql("""
                                                UPDATE users SET name = :name, last_name = :lastName,
                                                email = :email, username = :username,
                                                roles = :roles, modified_at = :modifiedAt
                                                WHERE id = :id AND stopped_at IS NULL
                                                RETURNING id, name, last_name, email, username, roles
                                                """)
                                                .bind("name", dto.getName())
                                                .bind("lastName", dto.getLastName())
                                                .bind("email", dto.getEmail())
                                                .bind("username", dto.getUsername())
                                                .bind("roles", RolesUtils.serialize(requestedRoles))
                                                .bind("modifiedAt", Instant.now())
                                                .bind("id", id)
                                                .fetch()
                                                .one()
                                                .map(this::toResponseDTO);
                                    })
                            );
                });
    }

    public Mono<Void> changePassword(PasswordChangeRequestDTO dto) {
        return getCaller()
                .flatMap(caller -> vassagoDbService.getClient(caller.getOrgId())
                        .flatMap(client -> client.sql("""
                                SELECT id, password, email, locale FROM users
                                WHERE id = :userId AND stopped_at IS NULL
                                """)
                                .bind("userId", caller.getId())
                                .fetch()
                                .one()
                                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                                .flatMap(row -> {
                                    if (!encoder.matches(dto.getOldPassword(), (String) row.get("password"))) {
                                        return Mono.error(new UnauthorizedException("Invalid credentials"));
                                    }
                                    UUID userId = (UUID) row.get("id");
                                    String email = (String) row.get("email");
                                    String locale = (String) row.get("locale");
                                    String verificationToken = verificationTokenService.generateToken();
                                    String tokenHash = verificationTokenService.hashToken(verificationToken);
                                    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
                                    return client.sql("""
                                            INSERT INTO pending_verifications (user_id, token_hash, expires_at, type)
                                            VALUES (:userId, :tokenHash, :expiresAt, 'PASSWORD_CHANGE')
                                            """)
                                            .bind("userId", userId)
                                            .bind("tokenHash", tokenHash)
                                            .bind("expiresAt", expiresAt)
                                            .fetch()
                                            .rowsUpdated()
                                            .then(mailgunService.sendPasswordResetEmail(
                                                    email, caller.getOrgId(), verificationToken, locale));
                                })
                        )
                );
    }

    public Mono<Void> deleteUser(UUID id) {
        return getCaller()
                .flatMap(caller -> vassagoDbService.getClient(caller.getOrgId())
                        .flatMap(client -> client.sql("""
                                UPDATE users SET stopped_at = :stoppedAt
                                WHERE id = :id AND stopped_at IS NULL
                                """)
                                .bind("stoppedAt", Instant.now())
                                .bind("id", id)
                                .fetch()
                                .rowsUpdated()
                                .flatMap(rows -> rows == 0
                                        ? Mono.error(new NotFoundException("User not found"))
                                        // Any access token already issued to this user is a bearer secret
                                        // that's otherwise valid until its own (short) expiry regardless of
                                        // stopped_at — revoke it immediately rather than waiting it out.
                                        : redisTokenService.revokeUserTokens(id, jwtTtlSeconds))
                        )
                ).then();
    }

    private Mono<VassagoAuthentication> getCaller() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (VassagoAuthentication) ctx.getAuthentication());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private UserResponseDTO toResponseDTO(Map<String, Object> row) {
        return UserResponseDTO.builder()
                .id((UUID) row.get("id"))
                .name((String) row.get("name"))
                .lastName((String) row.get("last_name"))
                .email((String) row.get("email"))
                .username((String) row.get("username"))
                .roles(RolesUtils.deserialize((String) row.get("roles")))
                .build();
    }

    private UserResponseDTO toCreateResponseDTO(Map<String, Object> row) {
        return UserResponseDTO.builder()
                .id((UUID) row.get("id"))
                .name((String) row.get("name"))
                .lastName((String) row.get("last_name"))
                .email((String) row.get("email"))
                .username((String) row.get("username"))
                .roles(RolesUtils.deserialize((String) row.get("roles")))
                .build();
    }
}
