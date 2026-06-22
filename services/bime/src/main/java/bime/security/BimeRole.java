package bime.security;

import java.util.Set;

import static bime.security.BimePermission.*;

public enum BimeRole {
    BIME_ADMIN {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_MANAGE, BIME_VIEW);
        }
    },
    BIME_MANAGER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_MANAGE, BIME_VIEW);
        }
    },
    BIME_VIEWER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_VIEW);
        }
    };

    public abstract Set<BimePermission> getPermissions();
}
