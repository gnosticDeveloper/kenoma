package vassago.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Successful login or refresh response. The response also sets two HttpOnly cookies: session-rt (refresh token) and session-fp (fingerprint token), which are required by POST /auth/refresh")
public record LoginResponseDTO(
        @Schema(description = "Short-lived JWT to include as Authorization: Bearer <token> on subsequent requests")
        String token
) {}
