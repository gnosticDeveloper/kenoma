package bime.controllers;

import bime.dto.*;
import bime.services.ProductMetadataService;
import bime.services.ProductService;
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
public class ProductController {

    private final ProductService productService;
    private final ProductMetadataService productMetadataService;

    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductResponseDTO> createProduct(@RequestBody ProductRequestDTO dto) {
        return productService.createProduct(dto);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<ProductResponseDTO> getProducts() {
        return productService.getProducts();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<ProductResponseDTO> getProductById(@PathVariable UUID id) {
        return productService.getProductById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductResponseDTO> updateProduct(@PathVariable UUID id, @RequestBody ProductRequestDTO dto) {
        return productService.updateProduct(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deactivateProduct(@PathVariable UUID id) {
        return productService.deactivateProduct(id);
    }

    @PutMapping("/{id}/metadata")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> assignMetadata(@PathVariable UUID id, @RequestBody List<ProductMetadataAssignmentItemDTO> assignments) {
        return productMetadataService.assignMetadata(id, assignments);
    }

    @PatchMapping("/{id}/metadata/{metadataId}/options")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> patchMetadataOptions(@PathVariable UUID id, @PathVariable UUID metadataId, @RequestBody MetadataOptionPatchDTO dto) {
        return productMetadataService.patchOptions(id, metadataId, dto);
    }
}
