CREATE TABLE IF NOT EXISTS locations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL,
    name        varchar(255) NOT NULL,
    code        varchar(50)  NOT NULL,
    is_active   bool         NOT NULL DEFAULT true,
    created_at  timestamp    NOT NULL DEFAULT current_timestamp,
    modified_at timestamp    NOT NULL DEFAULT current_timestamp,
    UNIQUE (org_id, code)
);

CREATE TABLE IF NOT EXISTS products (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid NOT NULL,
    sku         varchar(255) NOT NULL,
    name        varchar(255) NOT NULL,
    description text,
    is_active   bool         NOT NULL DEFAULT true,
    created_at  timestamp    NOT NULL DEFAULT current_timestamp,
    modified_at timestamp    NOT NULL DEFAULT current_timestamp,
    UNIQUE (org_id, sku)
);

CREATE TABLE IF NOT EXISTS product_metadata (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL,
    name       varchar(100) NOT NULL,
    created_at timestamp NOT NULL DEFAULT current_timestamp,
    UNIQUE (org_id, name)
);

CREATE TABLE IF NOT EXISTS product_metadata_option (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    metadata_id uuid NOT NULL REFERENCES product_metadata(id) ON DELETE CASCADE,
    value       varchar(255) NOT NULL,
    code        varchar(50),
    created_at  timestamp NOT NULL DEFAULT current_timestamp,
    UNIQUE (metadata_id, value)
);

CREATE TABLE IF NOT EXISTS product_metadata_assignments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    metadata_id uuid NOT NULL REFERENCES product_metadata(id) ON DELETE CASCADE,
    UNIQUE (product_id, metadata_id)
);

CREATE TABLE IF NOT EXISTS product_option_selections (
    assignment_id uuid NOT NULL REFERENCES product_metadata_assignments(id) ON DELETE CASCADE,
    option_id     uuid NOT NULL REFERENCES product_metadata_option(id) ON DELETE CASCADE,
    PRIMARY KEY (assignment_id, option_id)
);

CREATE INDEX IF NOT EXISTS idx_product_option_selections_option_id
    ON product_option_selections(option_id);

CREATE TABLE IF NOT EXISTS product_variants (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    org_id     uuid NOT NULL,
    sku        varchar(255),
    is_active  bool NOT NULL DEFAULT true,
    created_at timestamp NOT NULL DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS product_variant_options (
    variant_id uuid NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    option_id  uuid NOT NULL REFERENCES product_metadata_option(id) ON DELETE CASCADE,
    PRIMARY KEY (variant_id, option_id)
);

CREATE INDEX IF NOT EXISTS idx_product_variant_options_option_id
    ON product_variant_options(option_id);

CREATE TABLE IF NOT EXISTS stock_movements (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid        NOT NULL,
    product_id    uuid        NOT NULL REFERENCES products(id),
    variant_id    uuid        NOT NULL REFERENCES product_variants(id),
    location_id   uuid        NOT NULL REFERENCES locations(id),
    movement_type varchar(30) NOT NULL,
    delta         integer     NOT NULL,
    reference_id  uuid,
    note          varchar(500),
    created_at    timestamp   NOT NULL DEFAULT current_timestamp,
    created_by    uuid
);
CREATE INDEX IF NOT EXISTS idx_movements_org_product_location
    ON stock_movements(org_id, product_id, location_id);
CREATE INDEX IF NOT EXISTS idx_movements_org_variant_location
    ON stock_movements(org_id, variant_id, location_id);

CREATE TABLE IF NOT EXISTS variant_stock_balances (
    org_id      uuid    NOT NULL,
    variant_id  uuid    NOT NULL REFERENCES product_variants(id),
    location_id uuid    NOT NULL REFERENCES locations(id),
    quantity    integer NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    modified_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (org_id, variant_id, location_id)
);
