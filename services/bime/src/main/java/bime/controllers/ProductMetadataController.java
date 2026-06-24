package bime.controllers;

import bime.dto.*;
import bime.services.ProductMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/metadata")
@RequiredArgsConstructor
public class ProductMetadataController {

    private final ProductMetadataService productMetadataService;

    @PostMapping
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<ProductMetadataResponseDTO> createMetadata(@RequestBody ProductMetadataRequestDTO dto) {
        return productMetadataService.createMetadata(dto);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BIME_VIEW_CATALOG')")
    public Flux<ProductMetadataResponseDTO> getAllMetadata() {
        return productMetadataService.getAllMetadata();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW_CATALOG')")
    public Mono<ProductMetadataResponseDTO> getMetadataById(@PathVariable UUID id) {
        return productMetadataService.getMetadataById(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> deleteMetadata(@PathVariable UUID id) {
        return productMetadataService.deleteMetadata(id);
    }

    @PostMapping("/{id}/options")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<MetadataOptionResponseDTO> addOption(@PathVariable UUID id, @RequestBody MetadataOptionRequestDTO dto) {
        return productMetadataService.addOption(id, dto);
    }

    @DeleteMapping("/{id}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<Void> removeOption(@PathVariable UUID id, @PathVariable UUID optionId) {
        return productMetadataService.removeOption(id, optionId);
    }
}
