package bime.controllers;

import bime.dto.InTransitStockDTO;
import bime.dto.StockTransferReceiveRequestDTO;
import bime.dto.StockTransferRequestDTO;
import bime.dto.StockTransferResponseDTO;
import bime.dto.TransferStatus;
import bime.services.StockTransferService;
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
@RequestMapping("/stock/transfers")
@RequiredArgsConstructor
@Tag(name = "Stock transfers", description = "Move stock between two locations as one tracked operation with an approval and in-transit lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class StockTransferController {

    private final StockTransferService stockTransferService;

    @Operation(summary = "Create a draft transfer order",
            description = "Creates a transfer order in DRAFT with one or more lines. Every line moves stock from the same source " +
                    "location to the same destination location. Nothing moves until the transfer is submitted, approved and dispatched. " +
                    "Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Draft created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "A variant or location was not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockTransferResponseDTO> create(@RequestBody StockTransferRequestDTO dto) {
        return stockTransferService.create(dto);
    }

    @Operation(summary = "List transfer orders",
            description = "Returns transfer orders for the organization, newest first, optionally filtered by status, source location, " +
                    "destination location and/or variant. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transfers (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockTransferResponseDTO> list(
            @RequestParam(required = false) TransferStatus status,
            @Parameter(description = "Filter to transfers with a line leaving this location") @RequestParam(required = false) UUID sourceLocationId,
            @Parameter(description = "Filter to transfers with a line arriving at this location") @RequestParam(required = false) UUID destLocationId,
            @Parameter(description = "Filter to transfers with a line for this variant") @RequestParam(required = false) UUID variantId) {
        return stockTransferService.list(status, sourceLocationId, destLocationId, variantId);
    }

    @Operation(summary = "List stock currently in transit",
            description = "Returns, per variant and destination location, the quantity that has been dispatched from a transfer but not " +
                    "yet received. This is the stock that is neither at its source nor at its destination. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "In-transit quantities (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/in-transit")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<InTransitStockDTO> inTransit() {
        return stockTransferService.inTransit();
    }

    @Operation(summary = "Get a transfer order by ID", description = "Returns one transfer order with its lines. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<StockTransferResponseDTO> getById(@PathVariable UUID id) {
        return stockTransferService.getById(id);
    }

    @Operation(summary = "Edit a draft transfer order",
            description = "Replaces the reference, note and lines of a transfer that is still in DRAFT. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Draft updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer, variant or location not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is no longer a draft", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockTransferResponseDTO> update(@PathVariable UUID id, @RequestBody StockTransferRequestDTO dto) {
        return stockTransferService.update(id, dto);
    }

    @Operation(summary = "Delete a draft transfer order",
            description = "Deletes a transfer that is still in DRAFT. Once submitted, cancel it instead. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Draft deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is no longer a draft", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> delete(@PathVariable UUID id) {
        return stockTransferService.delete(id);
    }

    @Operation(summary = "Submit a transfer for approval",
            description = "Moves a DRAFT transfer to PENDING_APPROVAL, or straight to APPROVED when the caller holds BIME_TRANSFER_APPROVE. " +
                    "Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer submitted"),
            @ApiResponse(responseCode = "400", description = "Transfer has no lines", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is not a draft", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockTransferResponseDTO> submit(@PathVariable UUID id) {
        return stockTransferService.submit(id);
    }

    @Operation(summary = "Approve a transfer", description = "Moves a PENDING_APPROVAL transfer to APPROVED. Requires BIME_TRANSFER_APPROVE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer approved"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is not awaiting approval", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('BIME_TRANSFER_APPROVE')")
    public Mono<StockTransferResponseDTO> approve(@PathVariable UUID id) {
        return stockTransferService.approve(id);
    }

    @Operation(summary = "Reject a transfer", description = "Moves a PENDING_APPROVAL transfer to CANCELLED. Requires BIME_TRANSFER_APPROVE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer rejected"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is not awaiting approval", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('BIME_TRANSFER_APPROVE')")
    public Mono<StockTransferResponseDTO> reject(@PathVariable UUID id) {
        return stockTransferService.reject(id);
    }

    @Operation(summary = "Dispatch a transfer",
            description = "Moves an APPROVED transfer to IN_TRANSIT. For every line, the requested quantity leaves the source location " +
                    "immediately (a posted TRANSFER_OUT movement) and an equal quantity is recorded as pending arrival at the destination. " +
                    "Fails if any line would drive the source balance negative. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer dispatched"),
            @ApiResponse(responseCode = "400", description = "A line would drive the source balance negative", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is not approved", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockTransferResponseDTO> dispatch(@PathVariable UUID id) {
        return stockTransferService.dispatch(id);
    }

    @Operation(summary = "Receive a transfer",
            description = "Records goods arriving at the destination for an IN_TRANSIT or PARTIALLY_RECEIVED transfer. Each line's actual " +
                    "received quantity is applied to the destination balance; a quantity below what was dispatched leaves the rest in transit. " +
                    "Set closeShort to write off whatever is still in transit and complete the transfer. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Receipt recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid body, or a received quantity exceeds what is in transit", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer or line not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer is not in transit", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockTransferResponseDTO> receive(@PathVariable UUID id, @RequestBody StockTransferReceiveRequestDTO dto) {
        return stockTransferService.receive(id, dto);
    }

    @Operation(summary = "Cancel a transfer",
            description = "Moves a transfer that has not been dispatched (DRAFT, PENDING_APPROVAL or APPROVED) to CANCELLED. " +
                    "Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer cancelled"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Transfer has already been dispatched", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockTransferResponseDTO> cancel(@PathVariable UUID id) {
        return stockTransferService.cancel(id);
    }
}
