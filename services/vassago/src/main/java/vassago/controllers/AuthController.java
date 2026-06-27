package vassago.controllers;

import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import vassago.dto.LoginRequestDTO;
import vassago.dto.LoginResponseDTO;
import vassago.dto.PublicKeyResponseDTO;
import vassago.dto.RecoverRequestDTO;
import vassago.security.JwtService;
import vassago.services.AuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, session refresh, logout, and account recovery")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    @Operation(
            summary = "Login",
            description = "Authenticates the user and issues a short-lived JWT. " +
                    "Also sets two HttpOnly cookies (session-rt, session-fp) that are required by POST /auth/refresh. " +
                    "Cookies are scoped to the /auth path and will not be sent to other endpoints."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful — JWT in body, refresh cookies set"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    public Mono<LoginResponseDTO> login(@RequestBody LoginRequestDTO dto, ServerWebExchange exchange) {
        return authService.login(dto, exchange.getResponse()).map(LoginResponseDTO::new);
    }

    @Operation(
            summary = "Refresh session",
            description = "Issues a new JWT using the session-rt and session-fp cookies set at login. " +
                    "Rotates the refresh token — old cookies are invalidated and new ones are set. " +
                    "No request body required."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New JWT in body, rotated refresh cookies set"),
            @ApiResponse(responseCode = "401", description = "Missing, expired, or mismatched session cookies", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logged out"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerWebExchange exchange) {
        return authService.logout(exchange);
    }

    @Operation(summary = "Get current JWT signing public key",
            description = "Returns the current EC public key used to sign JWTs. " +
                    "Consumer services use this to validate JWTs without a direct OpenBao connection.")
    @ApiResponse(responseCode = "200", description = "Public key in PEM format")
    @GetMapping("/public-key")
    public Mono<PublicKeyResponseDTO> publicKey() {
        return jwtService.getPublicKeyPem().map(PublicKeyResponseDTO::new);
    }

    @Operation(
            summary = "Initiate account recovery",
            description = "Sends a password reset link to the email address associated with the given account. " +
                    "Always returns 204 regardless of whether the account exists to prevent user enumeration. " +
                    "The reset token expires after 1 hour and must be submitted to POST /user/verify."
    )
    @ApiResponse(responseCode = "204", description = "Recovery email sent (or silently suppressed if account not found)")
    @PostMapping("/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> recover(@RequestBody RecoverRequestDTO dto) {
        return authService.recoverAccount(dto);
    }
}
