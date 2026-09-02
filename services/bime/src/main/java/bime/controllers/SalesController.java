package bime.controllers;

import bime.dto.SaleRequestDTO;
import bime.dto.SaleResponseDTO;
import bime.services.SalesService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/sales")
@RequiredArgsConstructor
@Tag(name = "Sales", description = "Point-of-sale sales: scan items at a location and deplete stock through the ledger")
@SecurityRequirement(name = "bearerAuth")
public class SalesController {

    private final SalesService salesService;

    @Operation(summary = "Ring up a sale",
            description = "Records a completed sale of one or more scanned items at a single location and depletes stock " +
                    "immediately with SALE movements. Batch-tracked items are consumed first-expired-first-out; recalled " +
                    "batches are never sold. Each line identifies its variant by barcode or by variantId, and may carry a " +
                    "till-side unitPrice override. Requires BIME_SALE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sale recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid body, no price on file, or insufficient stock", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Location, variant or barcode not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_SALE')")
    public Mono<SaleResponseDTO> create(@RequestBody SaleRequestDTO dto) {
        return salesService.create(dto);
    }

    @Operation(summary = "List sales",
            description = "Returns sales for the organization, newest first, optionally filtered by location and sold-at date " +
                    "range (from is inclusive, to is inclusive of the whole day). Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of sales (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<SaleResponseDTO> list(
            @Parameter(description = "Filter to sales rung up at this location") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Earliest sold-at date, inclusive") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Latest sold-at date, inclusive") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return salesService.list(locationId, from, to);
    }

    @Operation(summary = "Get a sale by ID", description = "Returns one sale with its lines. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sale found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Sale not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<SaleResponseDTO> getById(@PathVariable UUID id) {
        return salesService.getById(id);
    }
}
