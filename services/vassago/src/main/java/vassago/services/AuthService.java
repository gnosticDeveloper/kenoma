package vassago.services;

import common.utils.RolesUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import vassago.db.VassagoDbService;
import vassago.dto.LoginRequestDTO;
import vassago.security.JwtService;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final VassagoDbService vassagoDbService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public Mono<String> login(LoginRequestDTO dto) {
        return vassagoDbService.getClient(dto.getOrgId())
                .flatMap(client -> client.sql("""
                        SELECT username, password, roles
                        FROM users
                        WHERE username = :username AND stopped_at IS NULL AND is_ready
                        """)
                        .bind("username", dto.getUsername())
                        .fetch()
                        .one()
                        .switchIfEmpty(Mono.error(
                                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                )
                .flatMap(row -> {
                    String storedHash = (String) row.get("password");
                    if (!passwordEncoder.matches(dto.getPassword(), storedHash)) {
                        return Mono.error(
                                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
                    }
                    Map<String, List<String>> roles = RolesUtils.deserialize((String) row.get("roles"));
                    return jwtService.issueToken(dto.getOrgId(), dto.getUsername(), roles);
                });
    }
}