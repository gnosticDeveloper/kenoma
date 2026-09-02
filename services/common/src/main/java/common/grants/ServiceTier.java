package common.grants;

/**
 * The Postgres privilege level an ephemeral database lease is issued at. A tenant user's
 * roles for the target service resolve to exactly one tier (highest wins), and the lease
 * physically cannot exceed that tier's grants even from a raw database client.
 *
 * <p>Declared weakest to strongest — {@link #ordinal()} is the tie-breaker in
 * {@link ServiceGrantProfile#resolveTier}. Each value maps to an OpenBao database role
 * named {@code <connectionName>-<suffix>-role}.
 */
public enum ServiceTier {
    CATALOG("catalog"),
    READONLY("readonly"),
    SALES("sales"),
    OPERATIONS("operations"),
    FULL("full");

    private final String roleSuffix;

    ServiceTier(String roleSuffix) {
        this.roleSuffix = roleSuffix;
    }

    public String roleSuffix() {
        return roleSuffix;
    }

    public String roleName(String connectionName) {
        return connectionName + "-" + roleSuffix + "-role";
    }
}
