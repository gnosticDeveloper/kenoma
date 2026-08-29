package bime.services;

import bime.db.BimeContextService;
import bime.db.BimeDbHandle;
import bime.dto.OrgUnitRequestDTO;
import bime.dto.OrgUnitResponseDTO;
import common.exception.BadRequestException;
import common.exception.ConflictException;
import common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnitsService {

    private final BimeContextService ctx;

    /** Lists the org's unit catalog, seeding the standard units first if this org has none yet -
      * every org ends up with kg/g/m/cm/l/ml/units available without any explicit setup step. */
    public Flux<OrgUnitResponseDTO> getUnits() {
        return ctx.withHandleMany((caller, handle) ->
                seedStandardUnits(handle, caller.getOrgId())
                        .thenMany(listUnits(handle, caller.getOrgId()))
        );
    }

    private Mono<Void> seedStandardUnits(BimeDbHandle handle, UUID orgId) {
        return Flux.fromIterable(StandardUnits.SEED_NAMES)
                .concatMap(name -> handle.client().sql("""
                        INSERT INTO org_units (org_id, name)
                        VALUES (:orgId, :name)
                        ON CONFLICT (org_id, name) DO NOTHING
                        """)
                        .bind("orgId", orgId)
                        .bind("name", name)
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private Flux<OrgUnitResponseDTO> listUnits(BimeDbHandle handle, UUID orgId) {
        return handle.client().sql("""
                SELECT id, org_id, name, created_at FROM org_units
                WHERE org_id = :orgId
                ORDER BY name
                """)
                .bind("orgId", orgId)
                .fetch()
                .all()
                .map(this::toResponseDTO);
    }

    public Mono<OrgUnitResponseDTO> createUnit(OrgUnitRequestDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            return Mono.error(new BadRequestException("name is required"));
        }
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                INSERT INTO org_units (org_id, name)
                VALUES (:orgId, :name)
                RETURNING id, org_id, name, created_at
                """)
                .bind("orgId", caller.getOrgId())
                .bind("name", UomNames.normalize(dto.getName()))
                .fetch()
                .one()
                .map(this::toResponseDTO)
                .onErrorMap(DataIntegrityViolationException.class, e ->
                        new ConflictException("A unit named \"" + UomNames.normalize(dto.getName()) + "\" already exists"))
        );
    }

    public Mono<Void> deleteUnit(UUID id) {
        return ctx.withHandle((caller, handle) -> handle.client().sql("""
                DELETE FROM org_units WHERE id = :id AND org_id = :orgId
                """)
                .bind("id", id)
                .bind("orgId", caller.getOrgId())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == 0
                        ? Mono.error(new NotFoundException("Unit not found"))
                        : Mono.empty())
                .onErrorMap(DataIntegrityViolationException.class, e ->
                        new ConflictException("Unit is in use by one or more variants and cannot be deleted"))
        ).then();
    }

    private OrgUnitResponseDTO toResponseDTO(Map<String, Object> row) {
        String name = (String) row.get("name");
        return OrgUnitResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .name(name)
                .standard(StandardUnits.isStandard(name))
                .createdAt((LocalDateTime) row.get("created_at"))
                .build();
    }
}
