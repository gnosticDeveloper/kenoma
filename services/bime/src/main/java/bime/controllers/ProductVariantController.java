package bime.controllers;

import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.services.ProductVariantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/products/{productId}/variants")
@RequiredArgsConstructor
public class ProductVariantController {

    private final ProductVariantService productVariantService;

    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductVariantResponseDTO> createVariant(
            @PathVariable UUID productId,
            @RequestBody ProductVariantRequestDTO dto) {
        return productVariantService.createVariant(productId, dto);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<ProductVariantResponseDTO> getVariants(@PathVariable UUID productId) {
        return productVariantService.getVariantsForProduct(productId);
    }

    @GetMapping("/{variantId}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<ProductVariantResponseDTO> getVariantById(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return productVariantService.getVariantById(productId, variantId);
    }

    @PatchMapping("/{variantId}")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductVariantResponseDTO> patchVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @RequestBody ProductVariantRequestDTO dto) {
        return productVariantService.patchVariant(productId, variantId, dto);
    }

    @DeleteMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deactivateVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return productVariantService.deactivateVariant(productId, variantId);
    }
}
