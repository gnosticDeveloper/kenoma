package bime.controllers;

import bime.dto.*;
import bime.services.ProductMetadataService;
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
@RequestMapping("/metadata")
@RequiredArgsConstructor
@Tag(name = "Product Metadata", description = "Manage reusable metadata definitions (e.g. Colour, Size) and their selectable options")
@SecurityRequirement(name = "bearerAuth")
public class ProductMetadataController {

    private final ProductMetadataService productMetadataService;

    @Operation(summary = "Create a metadata definition", description = "Creates a new metadata attribute definition for the authenticated organisation. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metadata definition created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A metadata definition with the same name already exists", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductMetadataResponseDTO> createMetadata(@RequestBody ProductMetadataRequestDTO dto) {
        return productMetadataService.createMetadata(dto);
    }

    @Operation(summary = "List all metadata definitions", description = "Returns all metadata definitions for the organisation, each including their options. Requires BIME_VIEW_CATALOG.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of metadata definitions (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW_CATALOG')")
    public Flux<ProductMetadataResponseDTO> getAllMetadata() {
        return productMetadataService.getAllMetadata();
    }

    @Operation(summary = "Get a metadata definition by ID", description = "Returns a single metadata definition including its options. Requires BIME_VIEW_CATALOG.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metadata definition found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Metadata definition not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW_CATALOG')")
    public Mono<ProductMetadataResponseDTO> getMetadataById(@PathVariable UUID id) {
        return productMetadataService.getMetadataById(id);
    }

    @Operation(summary = "Delete a metadata definition", description = "Permanently deletes the metadata definition and all its options. Only possible if the definition is not currently assigned to any product. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Metadata definition deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Metadata definition not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deleteMetadata(@PathVariable UUID id) {
        return productMetadataService.deleteMetadata(id);
    }

    @Operation(summary = "Add an option to a metadata definition", description = "Appends a new selectable option to an existing metadata definition. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Option added"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Metadata definition not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "An option with the same value already exists in this definition", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/options")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<MetadataOptionResponseDTO> addOption(@PathVariable UUID id, @RequestBody MetadataOptionRequestDTO dto) {
        return productMetadataService.addOption(id, dto);
    }

    @Operation(summary = "Remove an option from a metadata definition", description = "Permanently removes an option. Options that are currently selected on any product or used by any variant cannot be removed. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Option removed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Metadata definition or option not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> removeOption(@PathVariable UUID id, @PathVariable UUID optionId) {
        return productMetadataService.removeOption(id, optionId);
    }
}
