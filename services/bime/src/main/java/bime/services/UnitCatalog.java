package bime.services;

import bime.db.BimeDbHandle;
import common.exception.BadRequestException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Resolves a unit name to its org_units row, scoped to the caller's org. Recognized standard units
  * (kg, g, m, cm, l, ml, units) are lazily auto-registered the first time they're referenced, since
  * they're always legitimate regardless of catalog state - a fresh org shouldn't have to call
  * POST /units before creating its first variant. Custom unit names must already exist in the
  * catalog (added via POST /units first) - that's the whole point of the catalog being a strict
  * foreign key: a variant can't silently reference a unit nobody registered. */
final class UnitCatalog {

    private UnitCatalog() {}

    static Mono<UUID> resolveUnitId(BimeDbHandle handle, UUID orgId, String rawName) {
        String normalized = UomNames.normalize(rawName);
        return selectId(handle, orgId, normalized)
                .switchIfEmpty(StandardUnits.isStandard(normalized)
                        ? autoRegister(handle, orgId, normalized)
                        : Mono.error(new BadRequestException(
                                "Unknown unit \"" + normalized + "\" - add it to the unit catalog first via POST /units")));
    }

    private static Mono<UUID> selectId(BimeDbHandle handle, UUID orgId, String normalized) {
        return handle.client().sql("SELECT id FROM org_units WHERE org_id = :orgId AND name = :name")
                .bind("orgId", orgId)
                .bind("name", normalized)
                .fetch()
                .one()
                .map(row -> (UUID) row.get("id"));
    }

    private static Mono<UUID> autoRegister(BimeDbHandle handle, UUID orgId, String normalized) {
        return handle.client().sql("""
                INSERT INTO org_units (org_id, name)
                VALUES (:orgId, :name)
                ON CONFLICT (org_id, name) DO NOTHING
                RETURNING id
                """)
                .bind("orgId", orgId)
                .bind("name", normalized)
                .fetch()
                .one()
                .map(row -> (UUID) row.get("id"))
                .switchIfEmpty(selectId(handle, orgId, normalized));
    }
}
