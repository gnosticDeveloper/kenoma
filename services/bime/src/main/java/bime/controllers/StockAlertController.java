package bime.controllers;

import bime.dto.StockAlertResponseDTO;
import bime.dto.StockAlertThresholdRequestDTO;
import bime.dto.StockAlertThresholdResponseDTO;
import bime.services.StockAlertThresholdService;
import common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/stock/alerts")
@RequiredArgsConstructor
@Tag(name = "Stock Alerts", description = "Configure low-stock thresholds per variant/location and view currently active alerts")
@SecurityRequirement(name = "bearerAuth")
public class StockAlertController {

    private final StockAlertThresholdService stockAlertThresholdService;

    @Operation(
            summary = "Set a stock alert threshold",
            description = "Creates or replaces the alert threshold for a variant at a location. " +
                    "A scheduled job emails the location's notification address the first time on-hand quantity drops to or below this value, " +
                    "and again after it recovers above threshold and dips a second time. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Threshold set"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant or location not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/thresholds")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockAlertThresholdResponseDTO> setThreshold(@RequestBody StockAlertThresholdRequestDTO dto) {
        return stockAlertThresholdService.setThreshold(dto);
    }

    @Operation(summary = "List stock alert thresholds", description = "Returns configured thresholds for the organization, optionally filtered by variant and/or location. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of thresholds (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/thresholds")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockAlertThresholdResponseDTO> getThresholds(
            @Parameter(description = "Filter to the threshold for this variant") @RequestParam(required = false) UUID variantId,
            @Parameter(description = "Filter to thresholds at this location") @RequestParam(required = false) UUID locationId) {
        return stockAlertThresholdService.getThresholds(variantId, locationId);
    }

    @Operation(summary = "Remove a stock alert threshold", description = "Deletes the threshold for a variant at a location; stops future alerts for that pair. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Threshold removed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Threshold not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/thresholds")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deleteThreshold(@RequestParam UUID variantId, @RequestParam UUID locationId) {
        return stockAlertThresholdService.deleteThreshold(variantId, locationId);
    }

    @Operation(
            summary = "List active low-stock alerts",
            description = "Returns variants currently at or below their configured threshold at a location, for the organization. " +
                    "A row disappears once stock recovers above threshold. Requires BIME_VIEW."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of active alerts (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/active")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockAlertResponseDTO> getActiveAlerts(
            @Parameter(description = "Filter to alerts for this variant") @RequestParam(required = false) UUID variantId,
            @Parameter(description = "Filter to alerts at this location") @RequestParam(required = false) UUID locationId) {
        return stockAlertThresholdService.getActiveAlerts(variantId, locationId);
    }
}
