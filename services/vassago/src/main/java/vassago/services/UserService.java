package vassago.services;

import common.utils.RolesUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.db.VassagoDbService;
import vassago.dto.CreateUserResponseDTO;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;
import vassago.security.VassagoAuthentication;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder encoder;

    public Mono<CreateUserResponseDTO> createUser(UserRequestDTO dto) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (VassagoAuthentication) ctx.getAuthentication())
                .flatMap(caller -> {
                    Map<String, List<String>> callerRoles = caller.getRoles();
                    Map<String, List<String>> requestedRoles = dto.getRoles();

                    for (Map.Entry<String, List<String>> entry : requestedRoles.entrySet()) {
                        String service = entry.getKey();
                        List<String> requested = entry.getValue();
                        List<String> callerServiceRoles = callerRoles.getOrDefault(service, List.of());
                        if (!new HashSet<>(callerServiceRoles).containsAll(requested)) {
                            return Mono.error(new ResponseStatusException(
                                    HttpStatus.FORBIDDEN,
                                    "Cannot assign roles not held by the calling user for service: " + service));
                        }
                    }

                    String temporaryPassword = generateTemporaryPassword();

                    return vassagoDbService.getClient(dto.getOrgId())
                            .flatMap(client -> client.sql("""
                                    INSERT INTO users (name, last_name, email, username, password, roles)
                                    VALUES (:name, :lastName, :email, :username, :password, :roles)
                                    RETURNING id, name, last_name, email, username, roles
                                    """)
                                    .bind("name", dto.getName())
                                    .bind("lastName", dto.getLastName())
                                    .bind("email", dto.getEmail())
                                    .bind("username", dto.getUsername())
                                    .bind("password", Objects.requireNonNull(encoder.encode(temporaryPassword)))
                                    .bind("roles", RolesUtils.serialize(requestedRoles))
                                    .fetch()
                                    .one()
                                    .map(row -> toCreateResponseDTO(row, temporaryPassword))
                            );
                });
    }

    public Mono<UserResponseDTO> getUserById(UUID orgId, UUID id) {
        return vassagoDbService.getClient(orgId)
                .flatMap(client -> client.sql("""
                        SELECT id, name, last_name, email, username, roles
                        FROM users WHERE id = :id AND stopped_at IS NULL
                        """)
                        .bind("id", id)
                        .fetch()
                        .one()
                        .map(this::toResponseDTO)
                        .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
                );
    }

    public Flux<UserResponseDTO> getUsersByOrgId(UUID orgId) {
        return vassagoDbService.getClient(orgId)
                .flatMapMany(client -> client.sql("""
                        SELECT id, name, last_name, email, username, roles
                        FROM users WHERE stopped_at IS NULL
                        """)
                        .fetch()
                        .all()
                        .map(this::toResponseDTO)
                );
    }

    public Mono<UserResponseDTO> updateUser(UUID orgId, UUID id, UserRequestDTO dto) {
        final String updateQuery="""
                        UPDATE users SET name = :name, last_name = :lastName, email = :email,
                        username = :username, roles = :roles, modified_at = :modifiedAt
                        WHERE id = :id AND stopped_at IS NULL
                        RETURNING id, name, last_name, email, username, roles
                        """;
        return vassagoDbService.getClient(orgId)
                .flatMap(client -> client.sql(updateQuery)
                        .bind("name", dto.getName())
                        .bind("lastName", dto.getLastName())
                        .bind("email", dto.getEmail())
                        .bind("username", dto.getUsername())
                        .bind("roles", RolesUtils.serialize(dto.getRoles()))
                        .bind("modifiedAt", Instant.now())
                        .bind("id", id)
                        .fetch()
                        .one()
                        .map(this::toResponseDTO)
                        .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
                );
    }

    public Mono<Void> deleteUser(UUID orgId, UUID id) {
        return vassagoDbService.getClient(orgId)
                .flatMap(client -> client.sql("""
                        UPDATE users SET stopped_at = :stoppedAt
                        WHERE id = :id AND stopped_at IS NULL
                        """)
                        .bind("stoppedAt", Instant.now())
                        .bind("id", id)
                        .fetch()
                        .rowsUpdated()
                        .flatMap(rows -> rows == 0
                                ? Mono.error(new RuntimeException("User not found"))
                                : Mono.empty())
                ).then();
    }

    private static String generateTemporaryPassword() {
        byte[] bytes = new byte[18];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    private CreateUserResponseDTO toCreateResponseDTO(Map<String, Object> row, String temporaryPassword) {
        return CreateUserResponseDTO.builder()
                .id((UUID) row.get("id"))
                .name((String) row.get("name"))
                .lastName((String) row.get("last_name"))
                .email((String) row.get("email"))
                .username((String) row.get("username"))
                .roles(RolesUtils.deserialize((String) row.get("roles")))
                .temporaryPassword(temporaryPassword)
                .build();
    }
}