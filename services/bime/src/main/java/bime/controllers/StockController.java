package bime.controllers;

import bime.dto.StockBalanceResponseDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
import bime.services.StockLedgerService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
@Tag(name = "Stock", description = "Record and query stock movements and current on-hand balances")
@SecurityRequirement(name = "bearerAuth")
public class StockController {

    private final StockLedgerService stockLedgerService;

    @Operation(
            summary = "Record a stock movement",
            description = "Appends an immutable movement record to the ledger and updates the running balance for the variant at the given location. " +
                    "Movements cannot be edited or deleted — corrections must be recorded as ADJUSTMENT movements. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Variant or location not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/movements")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockMovementResponseDTO> recordMovement(@RequestBody StockMovementRequestDTO dto) {
        return stockLedgerService.recordMovement(dto);
    }

    @Operation(summary = "Get a movement by ID", description = "Returns a single immutable stock movement record. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Movement not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/movements/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<StockMovementResponseDTO> getMovementById(@PathVariable UUID id) {
        return stockLedgerService.getMovementById(id);
    }

    @Operation(summary = "List stock movements", description = "Returns stock movement records for the organisation, optionally filtered by variant and/or location. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of movements (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockMovementResponseDTO> getMovements(
            @Parameter(description = "Filter to movements for this variant") @RequestParam(required = false) UUID variantId,
            @Parameter(description = "Filter to movements at this location") @RequestParam(required = false) UUID locationId) {
        return stockLedgerService.getMovements(variantId, locationId);
    }

    @Operation(summary = "List stock balances", description = "Returns current on-hand stock balances for the organisation, optionally filtered by variant and/or location. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of balances (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/balances")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockBalanceResponseDTO> getBalances(
            @Parameter(description = "Filter to balances for this variant") @RequestParam(required = false) UUID variantId,
            @Parameter(description = "Filter to balances at this location") @RequestParam(required = false) UUID locationId) {
        return stockLedgerService.getBalances(variantId, locationId);
    }
}
