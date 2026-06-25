package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.*;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductMetadataService {

    private final BimeContextService ctx;

    public Mono<ProductMetadataResponseDTO> createMetadata(ProductMetadataRequestDTO dto) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                INSERT INTO product_metadata (org_id, name)
                VALUES (:orgId, :name)
                RETURNING id, org_id, name, created_at
                """)
                .bind("orgId", caller.getOrgId())
                .bind("name", dto.getName())
                .fetch()
                .one()
                .map(row -> ProductMetadataResponseDTO.builder()
                        .id((UUID) row.get("id"))
                        .orgId((UUID) row.get("org_id"))
                        .name((String) row.get("name"))
                        .options(List.of())
                        .createdAt((LocalDateTime) row.get("created_at"))
                        .build()
                )
        );
    }

    public Flux<ProductMetadataResponseDTO> getAllMetadata() {
        return ctx.withHandleMany((caller, handle) -> handle.client().sql("""
                SELECT pm.id, pm.org_id, pm.name, pm.created_at,
                       pmo.id AS option_id, pmo.value AS option_value,
                       pmo.created_at AS option_created_at
                FROM product_metadata pm
                LEFT JOIN product_metadata_option pmo ON pmo.metadata_id = pm.id
                WHERE pm.org_id = :orgId
                ORDER BY pm.name, pmo.value
                """)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .all()
                .collectList()
                .flatMapMany(rows -> Flux.fromIterable(aggregateMetadataList(rows)))
        );
    }

    public Mono<ProductMetadataResponseDTO> getMetadataById(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                SELECT pm.id, pm.org_id, pm.name, pm.created_at,
                       pmo.id AS option_id, pmo.value AS option_value,
                       pmo.created_at AS option_created_at
                FROM product_metadata pm
                LEFT JOIN product_metadata_option pmo ON pmo.metadata_id = pm.id
                WHERE pm.id = :id AND pm.org_id = :orgId
                ORDER BY pmo.value
                """)
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .all()
                .collectList()
                .flatMap(rows -> {
                    List<ProductMetadataResponseDTO> result = aggregateMetadataList(rows);
                    return result.isEmpty()
                            ? Mono.error(new NotFoundException("Metadata not found"))
                            : Mono.just(result.get(0));
                })
        );
    }

    public Mono<Void> deleteMetadata(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                DELETE FROM product_metadata WHERE id = :id AND org_id = :orgId
                """)
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Metadata not found"))
                        : Mono.empty())
        ).then();
    }

    public Mono<MetadataOptionResponseDTO> addOption(UUID metadataId, MetadataOptionRequestDTO dto) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                INSERT INTO product_metadata_option (metadata_id, value)
                SELECT id, :value FROM product_metadata
                WHERE id = :metadataId AND org_id = :orgId
                RETURNING id, metadata_id, value, created_at
                """)
                .bind("value", dto.getValue())
                .bind("metadataId", metadataId)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .one()
                .map(this::toOptionResponseDTO)
                .switchIfEmpty(Mono.error(new NotFoundException("Metadata not found")))
        );
    }

    public Mono<Void> removeOption(UUID metadataId, UUID optionId) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                DELETE FROM product_metadata_option pmo
                USING product_metadata pm
                WHERE pmo.id = :optionId
                  AND pmo.metadata_id = :metadataId
                  AND pm.id = pmo.metadata_id
                  AND pm.org_id = :orgId
                """)
                .bind("optionId", optionId)
                .bind("metadataId", metadataId)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Option not found"))
                        : Mono.empty())
        ).then();
    }

    public Mono<Void> assignMetadata(UUID productId, List<ProductMetadataAssignmentItemDTO> items) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                deleteAssignments(handle, productId, caller.getOrgId())
                        .thenMany(Flux.fromIterable(items))
                        .concatMap(item ->
                                insertAssignment(handle, productId, item.getMetadataId(), caller.getOrgId())
                                        .flatMap(assignmentId ->
                                                insertOptionSelections(handle, assignmentId, item.getOptionIds())
                                        )
                        )
                        .then()
        )));
    }

    public Mono<Void> patchOptions(UUID productId, UUID metadataId, MetadataOptionPatchDTO dto) {
        return ctx.withHandle((caller, handle) -> Mono.from(handle.tx().transactional(
                getOrCreateAssignment(handle, productId, metadataId, caller.getOrgId())
                        .flatMap(assignmentId ->
                                applyOptionRemovals(handle, assignmentId, dto.getRemove())
                                        .then(applyOptionAdditions(handle, assignmentId, metadataId, dto.getAdd()))
                        )
        )));
    }

    private Mono<Long> deleteAssignments(BimeDbHandle handle, UUID productId, UUID orgId) {
        return handle.client().sql("""
                DELETE FROM product_metadata_assignments
                WHERE product_id = :productId
                  AND metadata_id IN (SELECT id FROM product_metadata WHERE org_id = :orgId)
                """)
                .bind("productId", productId)
                .bind("orgId", orgId)
                .fetch()
                .rowsUpdated();
    }

    private Mono<UUID> insertAssignment(BimeDbHandle handle, UUID productId, UUID metadataId, UUID orgId) {
        return handle.client().sql("""
                INSERT INTO product_metadata_assignments (product_id, metadata_id)
                SELECT :productId, id FROM product_metadata WHERE id = :metadataId AND org_id = :orgId
                RETURNING id
                """)
                .bind("productId", productId)
                .bind("metadataId", metadataId)
                .bind("orgId", orgId)
                .fetch()
                .one()
                .map(row -> (UUID) row.get("id"))
                .switchIfEmpty(Mono.error(new NotFoundException("Metadata not found: " + metadataId)));
    }

    private Mono<Void> insertOptionSelections(BimeDbHandle handle, UUID assignmentId, List<UUID> optionIds) {
        if (optionIds == null || optionIds.isEmpty()) return Mono.empty();
        return Flux.fromIterable(optionIds)
                .concatMap(optionId -> handle.client().sql("""
                        INSERT INTO product_option_selections (assignment_id, option_id)
                        VALUES (:assignmentId, :optionId)
                        ON CONFLICT DO NOTHING
                        """)
                        .bind("assignmentId", assignmentId)
                        .bind("optionId", optionId)
                        .fetch()
                        .rowsUpdated()
                )
                .then();
    }

    private Mono<UUID> getOrCreateAssignment(BimeDbHandle handle, UUID productId, UUID metadataId, UUID orgId) {
        return handle.client().sql("""
                SELECT id FROM product_metadata_assignments
                WHERE product_id = :productId AND metadata_id = :metadataId
                """)
                .bind("productId", productId)
                .bind("metadataId", metadataId)
                .fetch()
                .one()
                .map(row -> (UUID) row.get("id"))
                .switchIfEmpty(
                        handle.client().sql("""
                                INSERT INTO product_metadata_assignments (product_id, metadata_id)
                                SELECT :productId, id FROM product_metadata
                                WHERE id = :metadataId AND org_id = :orgId
                                RETURNING id
                                """)
                                .bind("productId", productId)
                                .bind("metadataId", metadataId)
                                .bind("orgId", orgId)
                                .fetch()
                                .one()
                                .map(row -> (UUID) row.get("id"))
                                .switchIfEmpty(Mono.error(new NotFoundException("Metadata not found")))
                );
    }

    private Mono<Void> applyOptionRemovals(BimeDbHandle handle, UUID assignmentId, List<UUID> removeIds) {
        if (removeIds == null || removeIds.isEmpty()) return Mono.empty();
        return Flux.fromIterable(removeIds)
                .concatMap(optionId -> handle.client().sql("""
                        DELETE FROM product_option_selections
                        WHERE assignment_id = :assignmentId AND option_id = :optionId
                        """)
                        .bind("assignmentId", assignmentId)
                        .bind("optionId", optionId)
                        .fetch()
                        .rowsUpdated()
                )
                .then();
    }

    private Mono<Void> applyOptionAdditions(BimeDbHandle handle, UUID assignmentId, UUID metadataId, List<UUID> addIds) {
        if (addIds == null || addIds.isEmpty()) return Mono.empty();
        return Flux.fromIterable(addIds)
                .concatMap(optionId -> handle.client().sql("""
                        INSERT INTO product_option_selections (assignment_id, option_id)
                        SELECT :assignmentId, id FROM product_metadata_option
                        WHERE id = :optionId AND metadata_id = :metadataId
                        ON CONFLICT DO NOTHING
                        """)
                        .bind("assignmentId", assignmentId)
                        .bind("optionId", optionId)
                        .bind("metadataId", metadataId)
                        .fetch()
                        .rowsUpdated()
                )
                .then();
    }

    private List<ProductMetadataResponseDTO> aggregateMetadataList(List<Map<String, Object>> rows) {
        LinkedHashMap<UUID, ProductMetadataResponseDTO> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID metaId = (UUID) row.get("id");
            map.computeIfAbsent(metaId, k -> ProductMetadataResponseDTO.builder()
                    .id(metaId)
                    .orgId((UUID) row.get("org_id"))
                    .name((String) row.get("name"))
                    .createdAt((LocalDateTime) row.get("created_at"))
                    .options(new ArrayList<>())
                    .build()
            );
            UUID optionId = (UUID) row.get("option_id");
            if (optionId != null) {
                map.get(metaId).getOptions().add(MetadataOptionResponseDTO.builder()
                        .id(optionId)
                        .metadataId(metaId)
                        .value((String) row.get("option_value"))
                        .createdAt((LocalDateTime) row.get("option_created_at"))
                        .build()
                );
            }
        }
        return new ArrayList<>(map.values());
    }

    private MetadataOptionResponseDTO toOptionResponseDTO(Map<String, Object> row) {
        return MetadataOptionResponseDTO.builder()
                .id((UUID) row.get("id"))
                .metadataId((UUID) row.get("metadata_id"))
                .value((String) row.get("value"))
                .createdAt((LocalDateTime) row.get("created_at"))
                .build();
    }
}
