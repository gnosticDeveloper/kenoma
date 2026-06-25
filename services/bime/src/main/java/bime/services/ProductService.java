package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.AssignedMetadataDTO;
import bime.dto.MetadataOptionResponseDTO;
import bime.dto.ProductRequestDTO;
import bime.dto.ProductResponseDTO;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;


@Service
@RequiredArgsConstructor
public class ProductService {

    private final BimeContextService ctx;
    private final ProductVariantService productVariantService;

    public Mono<ProductResponseDTO> createProduct(ProductRequestDTO dto) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                INSERT INTO products (org_id, sku, name, description)
                VALUES (:orgId, :sku, :name, :description)
                RETURNING id, org_id, sku, name, description, is_active, created_at, modified_at
                """)
                .bind("orgId", caller.getOrgId())
                .bind("sku", dto.getSku())
                .bind("name", dto.getName())
                .bind("description", dto.getDescription() != null ? dto.getDescription() : "")
                .fetch()
                .one()
                .map(this::toResponseDTO)
        );
    }

    public Mono<ProductResponseDTO> getProductById(UUID id) {
        return ctx.withHandle((caller, handle) ->
                fetchProductRow(handle, id, caller.getOrgId())
                        .flatMap(dto -> loadProductMetadata(handle, id)
                                .map(metadata -> {
                                    dto.setMetadata(metadata);
                                    return dto;
                                })
                        )
                        .flatMap(dto -> productVariantService
                                .loadVariantsForProduct(handle, id, caller.getOrgId())
                                .collectList()
                                .map(variants -> {
                                    dto.setVariants(variants);
                                    return dto;
                                })
                        )
        );
    }

    public Flux<ProductResponseDTO> getProducts() {
        return ctx.withHandleMany((caller, handle) -> handle.client().sql("""
                SELECT id, org_id, sku, name, description, is_active, created_at, modified_at
                FROM products
                WHERE org_id = :orgId
                ORDER BY name
                """)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .all()
                .map(this::toResponseDTO)
        );
    }

    public Mono<ProductResponseDTO> updateProduct(UUID id, ProductRequestDTO dto) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE products
                SET sku = :sku, name = :name, description = :description,
                    is_active = :isActive, modified_at = :modifiedAt
                WHERE id = :id AND org_id = :orgId
                RETURNING id, org_id, sku, name, description, is_active, created_at, modified_at
                """)
                .bind("sku", dto.getSku())
                .bind("name", dto.getName())
                .bind("description", dto.getDescription() != null ? dto.getDescription() : "")
                .bind("isActive", dto.getIsActive() != null ? dto.getIsActive() : Boolean.TRUE)
                .bind("modifiedAt", LocalDateTime.now())
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(this::toResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
        );
    }

    public Mono<Void> deactivateProduct(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                UPDATE products SET is_active = false, modified_at = :modifiedAt
                WHERE id = :id AND org_id = :orgId
                """)
                .bind("modifiedAt", LocalDateTime.now())
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Product not found"))
                        : Mono.empty())
        ).then();
    }

    private Mono<ProductResponseDTO> fetchProductRow(BimeDbHandle handle, UUID id, UUID orgId) {
        return handle.client().sql("""
                SELECT id, org_id, sku, name, description, is_active, created_at, modified_at
                FROM products
                WHERE id = :id AND org_id = :orgId
                """)
                .bind("id", id)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .map(this::toResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")));
    }

    private Mono<List<AssignedMetadataDTO>> loadProductMetadata(BimeDbHandle handle, UUID productId) {
        return handle.client().sql("""
                SELECT pm.id AS metadata_id, pm.name AS metadata_name,
                       pmo.id AS option_id, pmo.value AS option_value
                FROM product_metadata_assignments pma
                JOIN product_metadata pm ON pm.id = pma.metadata_id
                LEFT JOIN product_option_selections pos ON pos.assignment_id = pma.id
                LEFT JOIN product_metadata_option pmo ON pmo.id = pos.option_id
                WHERE pma.product_id = :productId
                ORDER BY pm.name, pmo.value
                """)
                .bind("productId", productId)
                .fetch()
                .all()
                .collectList()
                .map(this::aggregateAssignedMetadata);
    }

    private List<AssignedMetadataDTO> aggregateAssignedMetadata(List<Map<String, Object>> rows) {
        LinkedHashMap<UUID, AssignedMetadataDTO> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID metaId = (UUID) row.get("metadata_id");
            map.computeIfAbsent(metaId, k -> AssignedMetadataDTO.builder()
                    .metadataId(metaId)
                    .metadataName((String) row.get("metadata_name"))
                    .selectedOptions(new ArrayList<>())
                    .build()
            );
            UUID optionId = (UUID) row.get("option_id");
            if (optionId != null) {
                map.get(metaId).getSelectedOptions().add(MetadataOptionResponseDTO.builder()
                        .id(optionId)
                        .metadataId(metaId)
                        .value((String) row.get("option_value"))
                        .build()
                );
            }
        }
        return new ArrayList<>(map.values());
    }

    private ProductResponseDTO toResponseDTO(Map<String, Object> row) {
        return ProductResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .sku((String) row.get("sku"))
                .name((String) row.get("name"))
                .description((String) row.get("description"))
                .isActive((Boolean) row.get("is_active"))
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
