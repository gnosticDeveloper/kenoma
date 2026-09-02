package common.grants;

/**
 * Thrown by {@link ServiceGrantProfile#resolveTier} when none of the caller's role names
 * map to a tier for the target service — i.e. the caller holds no role scoped to that
 * service. Raum's {@code CredentialsController} translates this to HTTP 403.
 */
public class NoTierForRolesException extends RuntimeException {

    public NoTierForRolesException(String message) {
        super(message);
    }
}
