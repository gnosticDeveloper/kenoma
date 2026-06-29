CREATE TABLE IF NOT EXISTS organizations (
                                             id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                             name               varchar(255) NOT NULL,
                                             contact_name       varchar(255) NOT NULL,
                                             contact_email      varchar(255) NOT NULL,
                                             modification_lock  bool DEFAULT false,
                                             locked_at          timestamp,
                                             created_at         timestamp DEFAULT current_timestamp,
                                             modified_at        timestamp DEFAULT current_timestamp,
                                             stopped_at         timestamp DEFAULT null
);

CREATE TABLE IF NOT EXISTS services (
                                        id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                        name         varchar(255) NOT NULL,
                                        description  varchar(255) NOT NULL,
                                        created_at   timestamp DEFAULT current_timestamp,
                                        modified_at  timestamp DEFAULT current_timestamp,
                                        stopped_at   timestamp DEFAULT null
);

CREATE TABLE IF NOT EXISTS credentials (
                                           id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                           org_id            uuid NOT NULL,
                                           service_id        uuid NOT NULL,
                                           db_engine         varchar(50) NOT NULL,
                                           db_host           varchar(255) NOT NULL,
                                           db_port           integer NOT NULL,
                                           db_name           varchar(255) NOT NULL,
                                           modification_lock bool DEFAULT false,
                                           locked_at         timestamp,
                                           created_at        timestamp DEFAULT current_timestamp,
                                           modified_at       timestamp DEFAULT current_timestamp,
                                           FOREIGN KEY (org_id) REFERENCES organizations(id),
                                           FOREIGN KEY (service_id) REFERENCES services(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_credentials_org_service ON credentials(org_id, service_id);

INSERT INTO services (name, description)
VALUES
    ('Raum', 'Credential and organisation registry'),
    ('Vassago', 'Authentication and identity service')
ON CONFLICT DO NOTHING;

INSERT INTO organizations (name, contact_name, contact_email)
VALUES ('Platform', 'Platform Operator', 'platform@internal')
ON CONFLICT DO NOTHING;

INSERT INTO credentials (org_id, service_id, db_engine, db_host, db_port, db_name)
SELECT o.id, s.id, 'postgres', 'vassago-postgres', 5432, 'vassago'
FROM organizations o, services s
WHERE o.name = 'Platform' AND s.name = 'Vassago'
ON CONFLICT DO NOTHING;