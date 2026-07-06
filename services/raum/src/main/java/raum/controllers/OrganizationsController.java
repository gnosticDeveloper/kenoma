package raum.controllers;

import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import raum.dto.OrgRequestDTO;
import raum.dto.OrgResponseDTO;
import raum.services.OrganizationService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/orgs")
@Tag(name = "Organizations", description = "Tenant registration and management")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationsController {
    final OrganizationService service;

    public OrganizationsController(OrganizationService service) {
        this.service = service;
    }

    @Operation(summary = "Register an organisation", description = "Creates a new tenant organisation. Requires RAUM_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisation registered"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    Mono<OrgResponseDTO> registerOrg(@RequestBody OrgRequestDTO dto) {
        return service.registerOrg(dto);
    }

    @Operation(summary = "List organisations", description = "Returns all active (non-deleted) organisations. Requires RAUM_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisations listed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    Flux<OrgResponseDTO> getAllOrgs() {
        return service.getAllOrgs();
    }

    @Operation(summary = "Get an organisation by ID", description = "Requires RAUM_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisation found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    Mono<OrgResponseDTO> getOrgDataById(@PathVariable("id") UUID id) {
        return service.getOrgDataById(id);
    }

    @Operation(summary = "Update an organisation", description = "Replaces all fields of the organisation. Requires RAUM_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organisation updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    Mono<OrgResponseDTO> updateOrg(@PathVariable("id") UUID id, @RequestBody OrgRequestDTO dto) {
        return service.updateOrg(id, dto);
    }

    @Operation(summary = "Delete an organisation", description = "Soft-deletes the organisation by setting stopped_at. Requires RAUM_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Organisation deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organisation not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> deleteOrg(@PathVariable("id") UUID id) {
        return service.deleteOrg(id);
    }
}
