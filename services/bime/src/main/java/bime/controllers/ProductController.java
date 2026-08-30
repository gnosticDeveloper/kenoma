package bime.controllers;

import bime.dto.*;
import bime.services.BarcodeService;
import bime.services.ProductMetadataService;
import bime.services.ProductService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ProductVariantService productVariantService;
    private final BarcodeService barcodeService;

    @Operation(summary = "Create a product", description = "Creates a new product for the authenticated organization. Requires BIME_MANAGE.")
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

    @Operation(summary = "List all products", description = "Returns all products for the authenticated organization with their variant count. " +
            "If optionIds is passed, only products with a metadata option selection matching the given IDs are returned - " +
            "matching at least one of them by default, or all of them when matchAll=true. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of products (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<ProductResponseDTO> getProducts(
            @RequestParam(required = false) List<UUID> optionIds,
            @RequestParam(required = false, defaultValue = "false") boolean matchAll) {
        return productService.getProducts(optionIds, matchAll);
    }

    @Operation(summary = "Search variants across all products by shared option values and/or SKU",
            description = "Returns variants from any product in the org matching the given metadata option IDs - " +
                    "matching at least one of them by default, or all of them when matchAll=true. " +
                    "Useful for finding every variant sharing a characteristic (e.g. all Red variants across every product). " +
                    "If sku is passed, only variants whose SKU contains every whitespace-separated token (in any order) " +
                    "are returned - can be combined with optionIds, or used on its own. " +
                    "If currency is passed, each variant's price is converted from its stored priceCurrency to it. Requires BIME_VIEW.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of matching variants (may be empty)"),
            @ApiResponse(responseCode = "400", description = "Neither optionIds nor sku provided", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/variants/search")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<ProductVariantResponseDTO> searchVariants(
            @RequestParam(required = false) List<UUID> optionIds,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false, defaultValue = "false") boolean matchAll,
            @RequestParam(required = false) String sku) {
        return productVariantService.searchVariantsByOptions(optionIds, currency, matchAll, sku);
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

    @Operation(
            summary = "Download a printable barcode label sheet (PDF)",
            description = "Renders a grid of scannable barcode labels for the product's variants, one label per active " +
                    "variant using its primary barcode by default. Set which=all for every barcode, or variantId to " +
                    "restrict to one variant, or uom to restrict to barcodes of one unit (e.g. \"case\"). " +
                    "columns (1-5, default 3) and copies (1-100, default 1) control the grid; " +
                    "pageSize is A4 or LETTER. There is no printer integration - print the returned PDF yourself. Requires BIME_VIEW."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF document"),
            @ApiResponse(responseCode = "400", description = "The product has no barcodes to print", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/{id}/barcode-labels", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<ResponseEntity<byte[]>> barcodeLabels(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "primary") String which,
            @RequestParam(defaultValue = "3") int columns,
            @RequestParam(defaultValue = "1") int copies,
            @RequestParam(defaultValue = "A4") String pageSize,
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) String uom) {
        return barcodeService.generateLabels(id, which, columns, copies, pageSize, variantId, uom)
                .map(pdf -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=barcode-labels-" + id + ".pdf")
                        .body(pdf));
    }
}
