package bime.services;

import bime.db.BimeDbService;
import bime.dto.ProductRequestDTO;
import bime.dto.ProductResponseDTO;
import bime.security.BimeAuthentication;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final BimeDbService bimeDbService;

    public Mono<ProductResponseDTO> createProduct(ProductRequestDTO dto) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
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
                        )
                );
    }

    public Mono<ProductResponseDTO> getProductById(UUID id) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
                                SELECT id, org_id, sku, name, description, is_active, created_at, modified_at
                                FROM products
                                WHERE id = :id AND org_id = :orgId
                                """)
                                .bind("id", id)
                                .bind("orgId", caller.getOrgId())
                                .fetch()
                                .one()
                                .map(this::toResponseDTO)
                                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                        )
                );
    }

    public Flux<ProductResponseDTO> getProducts() {
        return getCaller()
                .flatMapMany(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMapMany(handle -> handle.client().sql("""
                                SELECT id, org_id, sku, name, description, is_active, created_at, modified_at
                                FROM products
                                WHERE org_id = :orgId
                                ORDER BY name
                                """)
                                .bind("orgId", caller.getOrgId())
                                .fetch()
                                .all()
                                .map(this::toResponseDTO)
                        )
                );
    }

    public Mono<ProductResponseDTO> updateProduct(UUID id, ProductRequestDTO dto) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
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
                        )
                );
    }

    public Mono<Void> deactivateProduct(UUID id) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
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
                        )
                ).then();
    }

    private Mono<BimeAuthentication> getCaller() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (BimeAuthentication) ctx.getAuthentication());
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
