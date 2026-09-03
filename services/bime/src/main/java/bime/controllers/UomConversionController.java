package bime.controllers;

import bime.dto.UomConversionRequestDTO;
import bime.dto.UomConversionResponseDTO;
import bime.services.UomConversionService;
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
@RequestMapping("/variants/{variantId}/uom-conversions")
@RequiredArgsConstructor
@Tag(name = "Unit of Measure", description = "Configure alternate units a variant can be bought or sold in (e.g. sell a case of 24 as individual units)")
@SecurityRequirement(name = "bearerAuth")
public class UomConversionController {

    private final UomConversionService uomConversionService;

    @Operation(
            summary = "Set a unit-of-measure conversion",
            description = "Creates or updates (upsert, keyed by uomName) a conversion from an alternate unit to the variant's base unit. " +
                    "A stock movement recorded with this uom converts its quantity to base units using the given factor before it touches the ledger. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conversion set"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<UomConversionResponseDTO> setConversion(
            @PathVariable UUID variantId,
            @RequestBody UomConversionRequestDTO dto) {
        return uomConversionService.setConversion(variantId, dto);
    }

    @Operation(summary = "List a variant's unit-of-measure conversions", description = "Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of conversions (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<UomConversionResponseDTO> getConversions(@PathVariable UUID variantId) {
        return uomConversionService.getConversions(variantId);
    }

    @Operation(summary = "Remove a unit-of-measure conversion", description = "Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Conversion removed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Conversion not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{uomName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deleteConversion(@PathVariable UUID variantId, @PathVariable String uomName) {
        return uomConversionService.deleteConversion(variantId, uomName);
    }
}
