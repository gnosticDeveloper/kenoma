package raum.openbao;

/**
 * Which Kenoma service an OpenBao AppRole token belongs to, resolved from the token's
 * attached policy (see {@code raum.credentials.service-tokens} config). {@code RAUM} is
 * raum acting as the platform orchestrator (any org, any service, full tier); {@code BIME}
 * and {@code VASSAGO} tokens may only request credentials for their own service id.
 */
public enum ServiceIdentity {
    RAUM,
    BIME,
    VASSAGO
}
