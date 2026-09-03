package common.grants;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The full grant model for one service: which {@link ServiceTier}s it supports, the
 * {@link GrantSpec}s each tier receives, and how that service's role names map to tiers.
 *
 * <p>Role keys are plain strings ({@code "BIME_CASHIER"}, {@code "VASSAGO_ADMIN"}, ...)
 * because {@code common} cannot depend on {@code bime.security} / {@code vassago.security}.
 * Raum and the build-time statement generator both consume the same profile instances via
 * {@link ServiceGrantProfiles}.
 *
 * @param serviceName    the {@code services.name} this profile is for (case-insensitive match)
 * @param grantsByTier   grants per supported tier; iteration order is weakest-to-strongest
 * @param roleNameToTier service role name to the tier it grants
 */
public record ServiceGrantProfile(
        String serviceName,
        Map<ServiceTier, Set<GrantSpec>> grantsByTier,
        Map<String, ServiceTier> roleNameToTier
) {

    public ServiceGrantProfile {
        grantsByTier = Map.copyOf(grantsByTier);
        roleNameToTier = Map.copyOf(roleNameToTier);
    }

    /** Supported tiers, weakest first. */
    public Set<ServiceTier> supportedTiers() {
        return new java.util.TreeSet<>(grantsByTier.keySet());
    }

    public Set<GrantSpec> grantsFor(ServiceTier tier) {
        Set<GrantSpec> grants = grantsByTier.get(tier);
        if (grants == null) {
            throw new IllegalArgumentException(
                    serviceName + " profile does not support tier " + tier);
        }
        return grants;
    }

    /**
     * The strongest tier any of {@code roleNames} grants for this service.
     *
     * @throws NoTierForRolesException if none map — the caller holds no role for this service
     */
    public ServiceTier resolveTier(Collection<String> roleNames) {
        ServiceTier best = null;
        for (String roleName : roleNames) {
            ServiceTier tier = roleNameToTier.get(roleName);
            if (tier != null && (best == null || tier.ordinal() > best.ordinal())) {
                best = tier;
            }
        }
        if (best == null) {
            throw new NoTierForRolesException(
                    "No " + serviceName + " tier for roles " + roleNames);
        }
        return best;
    }
}
