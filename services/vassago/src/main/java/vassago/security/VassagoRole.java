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

        @Override
        public String getDisplayName() {
            return "Account Administrator";
        }

        @Override
        public String getDescription() {
            return "Can create, view, edit, and offboard any user in the organization.";
        }
    },
    VASSAGO_MEMBER {
        @Override
        public Set<VassagoPermission> getPermissions() {
            return Set.of(
                    VASSAGO_EDIT_USER,
                    VASSAGO_VIEW_USER
            );
        }

        @Override
        public String getDisplayName() {
            return "Account Member";
        }

        @Override
        public String getDescription() {
            return "Can view every user in the organization, and edit their own profile.";
        }
    };

    public abstract Set<VassagoPermission> getPermissions();

    public abstract String getDisplayName();

    public abstract String getDescription();
}
