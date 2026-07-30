package bime.controllers;

import bime.dto.VariantBatchPriceRequestDTO;
import bime.services.ProductVariantService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/variants/pricing")
@RequiredArgsConstructor
@Tag(name = "Variant Pricing", description = "Org-wide, cross-product batch pricing updates for variants.")
@SecurityRequirement(name = "bearerAuth")
public class VariantPricingController {

    private final ProductVariantService productVariantService;

    @Operation(
            summary = "Batch-update variant prices",
            description = "Reprices many variants (possibly spanning multiple products) in one call. " +
                    "All prices are stored in the organization's current base currency, regardless of " +
                    "the caller's own currency preferences. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "IDs of the variants that were updated"),
            @ApiResponse(responseCode = "400", description = "Empty batch, missing variantId/price, or org has no base currency configured", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "One or more variant IDs do not exist in this org", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<List<UUID>> batchUpdatePrices(@RequestBody VariantBatchPriceRequestDTO dto) {
        return productVariantService.batchUpdatePrices(dto);
    }
}
