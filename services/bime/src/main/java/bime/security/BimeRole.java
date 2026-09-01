package bime.security;

import java.util.Set;

import static bime.security.BimePermission.*;

public enum BimeRole {
    BIME_ADMIN {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_MANAGE, BIME_VIEW, BIME_VIEW_CATALOG, BIME_TRANSFER_APPROVE, BIME_RECALL_MANAGE);
        }

        @Override
        public String getDisplayName() {
            return "Inventory Administrator";
        }

        @Override
        public String getDescription() {
            return "Full control over products, stock, and locations, including approving transfers and recalling batches.";
        }
    },
    BIME_STOCK_OPERATOR {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_MANAGE, BIME_VIEW, BIME_VIEW_CATALOG);
        }

        @Override
        public String getDisplayName() {
            return "Stock Operator";
        }

        @Override
        public String getDescription() {
            return "Manages stock movements and transfers day to day. Transfers this user raises need a separate approval before dispatch.";
        }
    },
    BIME_TRANSFER_APPROVER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_VIEW, BIME_VIEW_CATALOG, BIME_TRANSFER_APPROVE);
        }

        @Override
        public String getDisplayName() {
            return "Transfer Approver";
        }

        @Override
        public String getDescription() {
            return "Approves or rejects stock transfer orders. Cannot make other stock changes.";
        }
    },
    BIME_VIEWER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_VIEW, BIME_VIEW_CATALOG);
        }

        @Override
        public String getDisplayName() {
            return "Inventory Viewer";
        }

        @Override
        public String getDescription() {
            return "Can view products, stock, and locations without making changes.";
        }
    },
    BIME_CATALOG_VIEWER {
        @Override
        public Set<BimePermission> getPermissions() {
            return Set.of(BIME_VIEW_CATALOG);
        }

        @Override
        public String getDisplayName() {
            return "Catalog Browser";
        }

        @Override
        public String getDescription() {
            return "Can only browse the product catalog, without visibility into stock levels or locations.";
        }
    };

    public abstract Set<BimePermission> getPermissions();

    public abstract String getDisplayName();

    public abstract String getDescription();
}
