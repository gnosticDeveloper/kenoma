package bime.controllers;

import bime.dto.*;
import bime.services.ProductMetadataService;
import bime.services.ProductService;
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
@RequestMapping("/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Manage products and their metadata assignments")
@SecurityRequirement(name = "bearerAuth")
public class ProductController {

    private final ProductService productService;
    private final ProductMetadataService productMetadataService;

    @Operation(summary = "Create a product", description = "Creates a new product for the authenticated organisation. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product created"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A product with the same SKU already exists", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductResponseDTO> createProduct(@RequestBody ProductRequestDTO dto) {
        return productService.createProduct(dto);
    }

    @Operation(summary = "List all products", description = "Returns all products for the authenticated organisation with their variant count. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of products (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<ProductResponseDTO> getProducts() {
        return productService.getProducts();
    }

    @Operation(summary = "Get a product by ID", description = "Returns a single product with its metadata and variants. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<ProductResponseDTO> getProductById(@PathVariable UUID id) {
        return productService.getProductById(id);
    }

    @Operation(summary = "Update a product", description = "Replaces all fields of the product. Does not affect metadata assignments or variants. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A product with the same SKU already exists", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductResponseDTO> updateProduct(@PathVariable UUID id, @RequestBody ProductRequestDTO dto) {
        return productService.updateProduct(id, dto);
    }

    @Operation(summary = "Deactivate a product", description = "Soft-deletes the product by setting isActive to false. Variants and stock records are preserved. Requires BIME_MANAGE.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Product deactivated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deactivateProduct(@PathVariable UUID id) {
        return productService.deactivateProduct(id);
    }

    @Operation(
            summary = "Assign metadata to a product",
            description = "Replaces the full set of metadata assignments for the product. " +
                    "Each item links a metadata definition to the subset of its options that apply to this product. " +
                    "Sending an empty list removes all metadata from the product. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Metadata assignments updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or unknown metadata/option IDs", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}/metadata")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> assignMetadata(@PathVariable UUID id, @RequestBody List<ProductMetadataAssignmentItemDTO> assignments) {
        return productMetadataService.assignMetadata(id, assignments);
    }

    @Operation(
            summary = "Patch selected options for one metadata assignment",
            description = "Incrementally adds or removes selected options for a single metadata definition that is already assigned to the product. " +
                    "Useful for toggling individual options without resending the full assignment. Requires BIME_MANAGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Options patched"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or unknown option IDs", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product or metadata assignment not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{id}/metadata/{metadataId}/options")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> patchMetadataOptions(@PathVariable UUID id, @PathVariable UUID metadataId, @RequestBody MetadataOptionPatchDTO dto) {
        return productMetadataService.patchOptions(id, metadataId, dto);
    }
}
