package vassago.security;

import java.util.Set;

import static vassago.security.VassagoPermission.*;

public enum VassagoRole {
    VASSAGO_ADMIN {
        @Override
        public Set<VassagoPermission> getPermissions() {
            return Set.of(
                    VASSAGO_CREATE_USER,
                    VASSAGO_EDIT_USER,
                    VASSAGO_VIEW_USER,
                    VASSAGO_OFFBOARD_USER
            );
        }
    },
    VASSAGO_USER {
        @Override
        public Set<VassagoPermission> getPermissions() {
            return Set.of(
                    VASSAGO_EDIT_USER,
                    VASSAGO_VIEW_USER
            );
        }
    };

    public abstract Set<VassagoPermission> getPermissions();
}