package vassago.services;

import common.utils.RolesUtils;
import common.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.db.VassagoDbService;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder encoder;

    public Mono<UserResponseDTO> createUser(UserRequestDTO dto) {
        if (!StringUtils.isValidPassword(dto.getPassword())) {
            return Mono.error(new IllegalArgumentException("Password does not meet requirements"));
        }
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
                        .bind("password", encoder.encode(dto.getPassword()))
                        .bind("roles", RolesUtils.serialize(dto.getRoles()))
                        .fetch()
                        .one()
                        .map(this::toResponseDTO)
                );
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
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            if (!StringUtils.isValidPassword(dto.getPassword())) {
                return Mono.error(new IllegalArgumentException("Password does not meet requirements"));
            }
        }
        return vassagoDbService.getClient(orgId)
                .flatMap(client -> {
                    String sql = dto.getPassword() != null && !dto.getPassword().isBlank()
                            ? """
                            UPDATE users SET name = :name, last_name = :lastName, email = :email,
                            username = :username, roles = :roles, password = :password, modified_at = :modifiedAt
                            WHERE id = :id AND stopped_at IS NULL
                            RETURNING id, name, last_name, email, username, roles
                            """
                            : """
                            UPDATE users SET name = :name, last_name = :lastName, email = :email,
                            username = :username, roles = :roles, modified_at = :modifiedAt
                            WHERE id = :id AND stopped_at IS NULL
                            RETURNING id, name, last_name, email, username, roles
                            """;
                    var spec = client.sql(sql)
                            .bind("name", dto.getName())
                            .bind("lastName", dto.getLastName())
                            .bind("email", dto.getEmail())
                            .bind("username", dto.getUsername())
                            .bind("roles", RolesUtils.serialize(dto.getRoles()))
                            .bind("modifiedAt", Instant.now())
                            .bind("id", id);
                    if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
                        spec = spec.bind("password", encoder.encode(dto.getPassword()));
                    }
                    return spec.fetch().one()
                            .map(this::toResponseDTO)
                            .switchIfEmpty(Mono.error(new RuntimeException("User not found")));
                });
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
}