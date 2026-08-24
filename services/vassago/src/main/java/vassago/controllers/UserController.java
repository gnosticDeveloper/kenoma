package vassago.controllers;

import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vassago.dto.PasswordChangeRequestDTO;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;
import vassago.dto.VerifyTokenRequestDTO;
import vassago.services.UserService;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management within an organization")
@SecurityRequirement(name = "bearerAuth")
public class UserController {
    private final UserService userService;

    @Operation(
            summary = "Create a user",
            description = "Creates a new user in the caller's organization and sends a verification email. " +
                    "The user cannot log in until they complete verification via POST /user/verify. " +
                    "Callers cannot assign roles they do not hold themselves. Requires VASSAGO_CREATE_USER."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User created — verification email sent"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or unknown role name", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions or attempted role elevation", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('VASSAGO_CREATE_USER')")
    public Mono<UserResponseDTO> createUser(@RequestBody UserRequestDTO dto) {
        return userService.createUser(dto);
    }

    @Operation(summary = "Get a user by ID", description = "Returns a user in the caller's organization. Requires VASSAGO_VIEW_USER.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VASSAGO_VIEW_USER')")
    public Mono<UserResponseDTO> getUserById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @Operation(summary = "List all users", description = "Returns all active users in the caller's organization. Requires VASSAGO_VIEW_USER.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of users (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('VASSAGO_VIEW_USER')")
    public Flux<UserResponseDTO> getUsersByOrgId() {
        return userService.getUsersByOrgId();
    }

    @Operation(
            summary = "Update a user",
            description = "Replaces all fields of the user. Non-admin users can only update themselves. Requires VASSAGO_EDIT_USER."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or unknown role name", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions or non-admin attempting to edit another user", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('VASSAGO_EDIT_USER')")
    public Mono<UserResponseDTO> updateUser(@PathVariable UUID id, @RequestBody UserRequestDTO dto) {
        return userService.updateUser(id, dto);
    }

    @Operation(summary = "Offboard a user", description = "Soft-deletes the user by setting stopped_at. The user can no longer log in and is excluded from all queries. Requires VASSAGO_OFFBOARD_USER.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User offboarded"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('VASSAGO_OFFBOARD_USER')")
    public Mono<Void> deleteUser(@PathVariable UUID id) {
        return userService.deleteUser(id);
    }

    @Operation(
            summary = "Verify token and set password",
            description = "Completes account creation or account recovery. Validates the one-time token received by email and sets the new password. " +
                    "Token validity: 24 h for account creation, 1 h for account recovery. Public endpoint — no JWT required."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password set — user may now log in"),
            @ApiResponse(responseCode = "400", description = "Password does not meet complexity requirements", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Token not found, already used, or expired", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements({})
    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> verifyToken(@RequestBody VerifyTokenRequestDTO dto) {
        return userService.verifyToken(dto);
    }

    @Operation(
            summary = "Initiate password change",
            description = "Step 1 of a self-service password change. Verifies the current password and sends a reset link to the user's email. " +
                    "The new password is set in step 2 by submitting the emailed token to POST /user/verify."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Reset email sent"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT, or incorrect current password", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> changePassword(@RequestBody PasswordChangeRequestDTO dto) {
        return userService.changePassword(dto);
    }
}
