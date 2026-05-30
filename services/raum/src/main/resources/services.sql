CREATE TABLE IF NOT EXISTS services (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name               varchar(255) NOT NULL,
    description        varchar(255) NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    stopped_at   TIMESTAMP DEFAULT NULL
    );