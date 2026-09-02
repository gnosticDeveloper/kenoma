package common.grants;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Fallback grant model for any service without an explicit profile — preserves the
 * pre-tiering behaviour: {@code FULL} is blanket CRUD on every table in schema public,
 * {@code READONLY} is blanket SELECT. No role names map to a tier, so the JWT-role path
 * (which needs {@link ServiceGrantProfile#resolveTier}) is refused for an unknown service;
 * only the {@code CREDENTIAL_MANAGE} and service-AppRole paths — which always request
 * {@code FULL} — reach this profile.
 *
 * <p>Adding a real service is then: give it a {@code ServiceGrantProfile}, register it in
 * {@link ServiceGrantProfiles}, and add its policy → identity mapping to raum config. No
 * change to this class.
 */
public final class GenericGrantProfile {

    private GenericGrantProfile() {}

    public static ServiceGrantProfile forServiceName(String serviceName) {
        Map<ServiceTier, Set<GrantSpec>> grantsByTier = new EnumMap<>(ServiceTier.class);
        grantsByTier.put(ServiceTier.READONLY, Set.of(GrantSpec.allTables(Op.SELECT)));
        grantsByTier.put(ServiceTier.FULL,
                Set.of(GrantSpec.allTables(Op.SELECT, Op.INSERT, Op.UPDATE, Op.DELETE)));
        return new ServiceGrantProfile(serviceName, grantsByTier, Map.of());
    }
}
