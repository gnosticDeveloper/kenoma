package bime.controllers;

import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
import bime.dto.NotificationEmailVerifyRequestDTO;
import bime.services.LocationService;
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

import java.util.UUID;

@RestController
@RequestMapping("/locations")
@RequiredArgsConstructor
@Tag(name = "Locations", description = "Manage physical or logical stock-holding locations (e.g. warehouses, retail stores)")
@SecurityRequirement(name = "bearerAuth")
public class LocationController {

    private final LocationService locationService;

    @Operation(summary = "Create a location", description = "Creates a new stock location for the authenticated organisation. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A location with the same code already exists", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<LocationResponseDTO> createLocation(@RequestBody LocationRequestDTO dto) {
        return locationService.createLocation(dto);
    }

    @Operation(summary = "List all locations", description = "Returns all active and inactive locations for the authenticated organisation. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of locations (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<LocationResponseDTO> getLocations() {
        return locationService.getLocations();
    }

    @Operation(summary = "Get a location by ID", description = "Returns a single location by its unique identifier. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Location not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<LocationResponseDTO> getLocationById(@PathVariable UUID id) {
        return locationService.getLocationById(id);
    }

    @Operation(summary = "Update a location", description = "Replaces all fields of the location. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Location not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<LocationResponseDTO> updateLocation(@PathVariable UUID id, @RequestBody LocationRequestDTO dto) {
        return locationService.updateLocation(id, dto);
    }

    @Operation(summary = "Deactivate a location", description = "Soft-deletes the location by setting isActive to false. Historical stock records are preserved. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Location deactivated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Location not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deactivateLocation(@PathVariable UUID id) {
        return locationService.deactivateLocation(id);
    }

    @Operation(summary = "Confirm a location's notification email", description = "Confirms a notification email verification token. No authentication required — the token itself is the credential.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Notification email confirmed"),
            @ApiResponse(responseCode = "400", description = "Missing token", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invalid or expired token", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/notification-email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> confirmNotificationEmail(@RequestBody NotificationEmailVerifyRequestDTO dto) {
        return locationService.confirmNotificationEmail(dto.getOrgId(), dto.getToken());
    }
}
