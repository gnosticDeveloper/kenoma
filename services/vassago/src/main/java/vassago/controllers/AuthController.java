package vassago.controllers;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.services.AuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Mono<LoginResponseDTO> login(@RequestBody LoginRequestDTO dto, ServerWebExchange exchange) {
        return authService.login(dto, exchange.getResponse()).map(LoginResponseDTO::new);
    }

    @PostMapping("/refresh")
    public Mono<LoginResponseDTO> refresh(ServerWebExchange exchange) {
        return authService.refresh(exchange).map(LoginResponseDTO::new);
    }

    @Operation(
            summary = "Logout",
            description = "Invalidates the current refresh token and blacklists the JWT used in this request. " +
                    "Any other JWT previously issued for the same session remains valid until it naturally expires " +
                    "(up to vassago.jwt.ttl-seconds, default 300 s)."
    )
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerWebExchange exchange) {
        return authService.logout(exchange);
    }

    @PostMapping("/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> recover(@RequestBody RecoverRequestDTO dto) {
        return authService.recoverAccount(dto);
    }
}
