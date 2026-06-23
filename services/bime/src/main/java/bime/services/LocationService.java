package bime.services;

import bime.db.BimeDbService;
import bime.dto.LocationRequestDTO;
import bime.dto.LocationResponseDTO;
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
public class LocationService {

    private final BimeDbService bimeDbService;

    public Mono<LocationResponseDTO> createLocation(LocationRequestDTO dto) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
                                INSERT INTO locations (org_id, name, code)
                                VALUES (:orgId, :name, :code)
                                RETURNING id, org_id, name, code, is_active, created_at, modified_at
                                """)
                                .bind("orgId", caller.getOrgId())
                                .bind("name", dto.getName())
                                .bind("code", dto.getCode())
                                .fetch()
                                .one()
                                .map(this::toResponseDTO)
                        )
                );
    }

    public Mono<LocationResponseDTO> getLocationById(UUID id) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
                                SELECT id, org_id, name, code, is_active, created_at, modified_at
                                FROM locations
                                WHERE id = :id AND org_id = :orgId
                                """)
                                .bind("id", id)
                                .bind("orgId", caller.getOrgId())
                                .fetch()
                                .one()
                                .map(this::toResponseDTO)
                                .switchIfEmpty(Mono.error(new NotFoundException("Location not found")))
                        )
                );
    }

    public Flux<LocationResponseDTO> getLocations() {
        return getCaller()
                .flatMapMany(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMapMany(handle -> handle.client().sql("""
                                SELECT id, org_id, name, code, is_active, created_at, modified_at
                                FROM locations
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

    public Mono<LocationResponseDTO> updateLocation(UUID id, LocationRequestDTO dto) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
                                UPDATE locations
                                SET name = :name, code = :code, is_active = :isActive, modified_at = :modifiedAt
                                WHERE id = :id AND org_id = :orgId
                                RETURNING id, org_id, name, code, is_active, created_at, modified_at
                                """)
                                .bind("name", dto.getName())
                                .bind("code", dto.getCode())
                                .bind("isActive", dto.getIsActive() != null ? dto.getIsActive() : Boolean.TRUE)
                                .bind("modifiedAt", LocalDateTime.now())
                                .bind("id", id)
                                .bind("orgId", caller.getOrgId())
                                .fetch()
                                .one()
                                .map(this::toResponseDTO)
                                .switchIfEmpty(Mono.error(new NotFoundException("Location not found")))
                        )
                );
    }

    public Mono<Void> deactivateLocation(UUID id) {
        return getCaller()
                .flatMap(caller -> bimeDbService.getHandle(caller.getOrgId())
                        .flatMap(handle -> handle.client().sql("""
                                UPDATE locations SET is_active = false, modified_at = :modifiedAt
                                WHERE id = :id AND org_id = :orgId
                                """)
                                .bind("modifiedAt", LocalDateTime.now())
                                .bind("id", id)
                                .bind("orgId", caller.getOrgId())
                                .fetch()
                                .rowsUpdated()
                                .flatMap(rows -> rows == 0
                                        ? Mono.error(new NotFoundException("Location not found"))
                                        : Mono.empty())
                        )
                ).then();
    }

    private Mono<BimeAuthentication> getCaller() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (BimeAuthentication) ctx.getAuthentication());
    }

    private LocationResponseDTO toResponseDTO(Map<String, Object> row) {
        return LocationResponseDTO.builder()
                .id((UUID) row.get("id"))
                .orgId((UUID) row.get("org_id"))
                .name((String) row.get("name"))
                .code((String) row.get("code"))
                .isActive((Boolean) row.get("is_active"))
                .createdAt((LocalDateTime) row.get("created_at"))
                .modifiedAt((LocalDateTime) row.get("modified_at"))
                .build();
    }
}
