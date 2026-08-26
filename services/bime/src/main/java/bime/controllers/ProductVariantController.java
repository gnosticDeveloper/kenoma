package bime.controllers;

import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/products/{productId}/variants")
@RequiredArgsConstructor
@Tag(name = "Product Variants", description = "Manage variants of a product (e.g. specific color/size combinations). Stock is tracked at the variant level.")
@SecurityRequirement(name = "bearerAuth")
public class ProductVariantController {

    private final ProductVariantService productVariantService;

    @Operation(
            summary = "Create a variant",
            description = "Creates a new variant for the given product, defined by a combination of metadata option IDs. " +
                    "The combination must be unique within the product. The variant's SKU is generated automatically " +
                    "from the product SKU and each selected option's code and cannot be set directly. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Variant created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or option IDs that do not belong to metadata assigned to this product", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A variant with the same option combination or SKU already exists", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductVariantResponseDTO> createVariant(
            @PathVariable UUID productId,
            @RequestBody ProductVariantRequestDTO dto) {
        return productVariantService.createVariant(productId, dto);
    }

    @Operation(summary = "List variants for a product", description = "Returns all variants for the given product, each including its defining options and per-location stock balances. " +
            "If currency is passed, each variant's price is converted from its stored priceCurrency to it. " +
            "If optionIds is passed, only variants matching the given option IDs are returned - matching at least " +
            "one of them by default, or all of them when matchAll=true. If sku is passed, only variants whose SKU " +
            "contains every whitespace-separated token (in any order) are returned. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of variants (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<ProductVariantResponseDTO> getVariants(
            @PathVariable UUID productId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) List<UUID> optionIds,
            @RequestParam(required = false, defaultValue = "false") boolean matchAll,
            @RequestParam(required = false) String sku) {
        return productVariantService.getVariantsForProduct(productId, currency, optionIds, matchAll, sku);
    }

    @Operation(summary = "Get a variant by ID", description = "Returns a single variant with its options and stock balances. " +
            "If currency is passed, the price is converted from its stored priceCurrency to it. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Variant found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product or variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{variantId}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<ProductVariantResponseDTO> getVariantById(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestParam(required = false) String currency) {
        return productVariantService.getVariantById(productId, variantId, currency);
    }

    @Operation(
            summary = "Patch a variant",
            description = "Partially updates a variant. Only fields included in the request body are changed; null fields are ignored. " +
                    "Changing optionIds re-evaluates uniqueness against existing variants. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Variant updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or conflicting option IDs", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product or variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{variantId}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductVariantResponseDTO> patchVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestBody ProductVariantRequestDTO dto) {
        return productVariantService.patchVariant(productId, variantId, dto);
    }

    @Operation(summary = "Deactivate a variant", description = "Soft-deletes the variant by setting isActive to false. Stock records are preserved. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Variant deactivated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product or variant not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deactivateVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return productVariantService.deactivateVariant(productId, variantId);
    }
}
