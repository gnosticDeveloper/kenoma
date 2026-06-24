package bime.security;

import java.util.Set;

import static bime.security.BimePermission.*;

public enum BimeRole {
    BIME_ADMIN {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_MANAGE, BIME_VIEW, BIME_VIEW_CATALOG);
        }
    },
    BIME_MANAGER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_MANAGE, BIME_VIEW, BIME_VIEW_CATALOG);
        }
    },
    BIME_VIEWER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_VIEW, BIME_VIEW_CATALOG);
        }
    },
    BIME_USER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_VIEW_CATALOG);
        }
    };

    public abstract Set<BimePermission> getPermissions();
}
