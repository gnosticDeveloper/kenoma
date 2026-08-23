package raum.openbao;

/**
 * Which Postgres grant tier an ephemeral lease is issued under, for a given {@code credentials}
 * row. Every registered database connection gets one Vault role per tier (see
 * {@link OpenBaoService#registerDatabaseConnection}) so a caller who only holds a non-admin role
 * for the target service can never write through the ephemeral connection, regardless of
 * app-layer authorization checks. {@link raum.controllers.CredentialsController} resolves which
 * tier a caller gets from their actual role names for the requested serviceId.
 */
public enum CredentialTier {
    ADMIN("admin"),
    MEMBER("member");

    private final String roleSuffix;

    CredentialTier(String roleSuffix) {
        this.roleSuffix = roleSuffix;
    }

    public String roleName(String connectionName) {
        return connectionName + "-" + roleSuffix + "-role";
    }
}
