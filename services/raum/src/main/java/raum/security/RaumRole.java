package raum.security;

import java.util.Set;

public enum RaumRole {
    RAUM_ADMIN {
        @Override
        public Set<RaumPermission> getPermissions() {
            return Set.of(RaumPermission.RAUM_MANAGE);
        }
    },
    RAUM_ONBOARDING {
        @Override
        public Set<RaumPermission> getPermissions() {
            return Set.of(RaumPermission.INITIATE_ONBOARDING);
        }
    };

    public abstract Set<RaumPermission> getPermissions();
}