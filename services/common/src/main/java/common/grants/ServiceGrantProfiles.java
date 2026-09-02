package common.grants;

/**
 * Single lookup from a {@code services.name} to its {@link ServiceGrantProfile}. Used by
 * raum at credential-issuance time and by the build-time statement generator, so both
 * emit identical grants.
 */
public final class ServiceGrantProfiles {

    private ServiceGrantProfiles() {}

    public static ServiceGrantProfile forServiceName(String serviceName) {
        if (serviceName == null) {
            return GenericGrantProfile.forServiceName("unknown");
        }
        if (serviceName.equalsIgnoreCase(BimeGrantProfile.SERVICE_NAME)) {
            return BimeGrantProfile.PROFILE;
        }
        if (serviceName.equalsIgnoreCase(VassagoGrantProfile.SERVICE_NAME)) {
            return VassagoGrantProfile.PROFILE;
        }
        return GenericGrantProfile.forServiceName(serviceName);
    }
}
