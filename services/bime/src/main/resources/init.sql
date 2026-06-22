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

CREATE TABLE IF NOT EXISTS stock_movements (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid        NOT NULL,
    product_id    uuid        NOT NULL REFERENCES products(id),
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

CREATE TABLE IF NOT EXISTS stock_balances (
    org_id      uuid    NOT NULL,
    product_id  uuid    NOT NULL REFERENCES products(id),
    location_id uuid    NOT NULL REFERENCES locations(id),
    quantity    integer NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    modified_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (org_id, product_id, location_id)
);
