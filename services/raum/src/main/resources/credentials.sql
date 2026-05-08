CREATE TABLE credentials (
                             id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                             org_id             uuid NOT NULL,
                             service_id         uuid NOT NULL,
                             db_engine          varchar(50) NOT NULL,
                             db_host            varchar(255) NOT NULL,
                             db_port            integer NOT NULL,
                             db_name            varchar(255) NOT NULL,
                             user_name          bytea NOT NULL,
                             encrypted_password bytea NOT NULL,
                             modification_lock  bool DEFAULT false,
                             locked_at          timestamp,
                             created_at         timestamp DEFAULT current_timestamp,
                             modified_at        timestamp DEFAULT current_timestamp
);

CREATE UNIQUE INDEX idx_credentials_org_service ON credentials(org_id, service_id);