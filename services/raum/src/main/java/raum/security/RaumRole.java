package raum.security;

import java.util.Set;

public enum RaumRole {
    RAUM_ADMIN {
        @Override
        public Set<RaumPermission> getPermissions() {
            return Set.of(RaumPermission.ORG_MANAGE, RaumPermission.SERVICE_MANAGE,
                    RaumPermission.CREDENTIAL_MANAGE, RaumPermission.PRICING_MANAGE);
        }

        @Override
        public String getDisplayName() {
            return "Platform Administrator";
        }

        @Override
        public String getDescription() {
            return "Operates the platform itself: manages every organization, registered services, "
                    + "database credentials, and pricing configuration.";
        }
    },
    RAUM_OWNER {
        @Override
        public Set<RaumPermission> getPermissions() {
            return Set.of(RaumPermission.ORG_EXPORT_SELF);
        }

        @Override
        public String getDisplayName() {
            return "Organization Owner";
        }

        @Override
        public String getDescription() {
            return "Can request and download an export of this organization's own data, for backup or offboarding.";
        }
    },
    RAUM_ONBOARDING {
        @Override
        public Set<RaumPermission> getPermissions() {
            return Set.of(RaumPermission.INITIATE_ONBOARDING);
        }

        @Override
        public String getDisplayName() {
            return "Onboarding Operator";
        }

        @Override
        public String getDescription() {
            return "Can initiate onboarding of new organizations.";
        }
    };

    public abstract Set<RaumPermission> getPermissions();

    public abstract String getDisplayName();

    public abstract String getDescription();
}
