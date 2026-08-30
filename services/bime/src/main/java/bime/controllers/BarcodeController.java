package bime.controllers;

import bime.dto.BarcodeLookupResponseDTO;
import bime.dto.OrgBarcodeSettingsRequestDTO;
import bime.dto.OrgBarcodeSettingsResponseDTO;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/barcodes")
@RequiredArgsConstructor
@Tag(name = "Barcodes", description = "Point-of-sale barcode resolution and the org's barcode issuance settings")
@SecurityRequirement(name = "bearerAuth")
public class BarcodeController {

    private final BarcodeService barcodeService;

    @Operation(
            summary = "Resolve a scanned barcode to a variant",
            description = "Looks up the variant a barcode identifies within the caller's organization, returning it along " +
                    "with its product, SKU, price and per-location stock. The barcode is passed as the ?code query " +
                    "parameter (so alphanumeric CODE128/CODE39 values with slashes or spaces work). A bare 12-digit " +
                    "UPC-A also matches its 13-digit EAN-13 form. A retired variant still resolves - check variant.isActive. Requires BIME_VIEW."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Variant found for the barcode"),
            @ApiResponse(responseCode = "400", description = "Blank barcode", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No variant linked to this barcode", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<BarcodeLookupResponseDTO> lookup(@RequestParam String code) {
        return barcodeService.lookup(code);
    }

    @Operation(summary = "Get the org's barcode issuance settings", description = "Returns defaults (no GS1 prefix, sequence 1) if never configured. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<OrgBarcodeSettingsResponseDTO> getSettings() {
        return barcodeService.getSettings();
    }

    @Operation(
            summary = "Update the org's barcode issuance settings",
            description = "Sets or clears the GS1 company prefix used when issuing internal barcodes. Send an empty/null " +
                    "gs1Prefix to clear it and fall back to the restricted-distribution range. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings updated"),
            @ApiResponse(responseCode = "400", description = "gs1Prefix is not 4-11 digits", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<OrgBarcodeSettingsResponseDTO> updateSettings(@RequestBody OrgBarcodeSettingsRequestDTO dto) {
        return barcodeService.updateSettings(dto);
    }
}
