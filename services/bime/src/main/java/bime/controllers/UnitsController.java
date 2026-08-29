package bime.controllers;

import bime.dto.OrgUnitRequestDTO;
import bime.dto.OrgUnitResponseDTO;
import bime.services.UnitsService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/units")
@RequiredArgsConstructor
@Tag(name = "Units", description = "The org's catalog of units of measure. Variants can only reference units that exist here")
@SecurityRequirement(name = "bearerAuth")
public class UnitsController {

    private final UnitsService unitsService;

    @Operation(
            summary = "List the org's unit catalog",
            description = "Seeds the standard units (kg, g, m, cm, l, ml, units) on first call if the org has none yet, " +
                    "then returns the full catalog including any custom units the org has added. Requires BIME_VIEW."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of units"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<OrgUnitResponseDTO> getUnits() {
        return unitsService.getUnits();
    }

    @Operation(summary = "Add a custom unit", description = "Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unit created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A unit with this name already exists", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<OrgUnitResponseDTO> createUnit(@RequestBody OrgUnitRequestDTO dto) {
        return unitsService.createUnit(dto);
    }

    @Operation(summary = "Remove a custom unit", description = "Fails if any variant still references this unit. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Unit removed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unit not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Unit is in use and cannot be deleted", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deleteUnit(@PathVariable UUID id) {
        return unitsService.deleteUnit(id);
    }
}
