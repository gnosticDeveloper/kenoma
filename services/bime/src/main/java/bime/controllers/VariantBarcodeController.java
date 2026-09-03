package bime.controllers;

import bime.dto.VariantBarcodeIssueRequestDTO;
import bime.dto.VariantBarcodePrimaryRequestDTO;
import bime.dto.VariantBarcodeRequestDTO;
import bime.dto.VariantBarcodeResponseDTO;
import bime.services.BarcodeService;
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
@RequestMapping("/products/{productId}/variants/{variantId}/barcodes")
@RequiredArgsConstructor
@Tag(name = "Variant Barcodes", description = "Link manufacturer barcodes to a variant or issue internal ones. Used to resolve a scan at point of sale to a variant.")
@SecurityRequirement(name = "bearerAuth")
public class VariantBarcodeController {

    private final BarcodeService barcodeService;

    @Operation(summary = "List a variant's barcodes", description = "Primary barcode first, then by creation order. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of barcodes (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<VariantBarcodeResponseDTO> list(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return barcodeService.list(productId, variantId);
    }

    @Operation(
            summary = "Link an existing barcode",
            description = "Attaches a manufacturer-supplied (provider) barcode to the variant. For EAN13/UPC_A/EAN8 the " +
                    "value's length and check digit are validated; UPC_A is stored as its equivalent 13-digit EAN13. " +
                    "CODE128/CODE39 values are stored as given. The value must be unique within the organization. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Barcode linked"),
            @ApiResponse(responseCode = "400", description = "Invalid barcode value or symbology", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Barcode already linked to a variant in this organization", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<VariantBarcodeResponseDTO> link(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestBody VariantBarcodeRequestDTO dto) {
        return barcodeService.link(productId, variantId, dto);
    }

    @Operation(
            summary = "Issue an internal barcode",
            description = "Generates a new 13-digit EAN barcode for the variant from the org's barcode settings - its GS1 " +
                    "company prefix if configured, otherwise the restricted-distribution range (in-store use only). " +
                    "Consumes one number from the org's issuance sequence. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Barcode issued"),
            @ApiResponse(responseCode = "400", description = "Issuance space exhausted for the configured prefix", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/issue")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<VariantBarcodeResponseDTO> issue(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestBody(required = false) VariantBarcodeIssueRequestDTO dto) {
        return barcodeService.issue(productId, variantId, dto);
    }

    @Operation(summary = "Set or clear a barcode as primary", description = "Promotes the barcode (passed as the ?barcode query parameter) to the variant's primary, demoting any other, or clears the flag. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Primary flag updated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant or barcode not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<VariantBarcodeResponseDTO> setPrimary(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestParam String barcode,
            @RequestBody VariantBarcodePrimaryRequestDTO dto) {
        return barcodeService.setPrimary(productId, variantId, barcode, Boolean.TRUE.equals(dto.getIsPrimary()));
    }

    @Operation(summary = "Unlink a barcode", description = "Removes the barcode (passed as the ?barcode query parameter) from the variant. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Barcode removed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant or barcode not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> remove(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestParam String barcode) {
        return barcodeService.remove(productId, variantId, barcode);
    }
}
