CREATE TABLE IF NOT EXISTS organizations (
                                             id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                             name               varchar(255) NOT NULL UNIQUE,
                                             contact_name       varchar(255) NOT NULL,
                                             contact_email      varchar(255) NOT NULL,
                                             modification_lock  bool DEFAULT false,
                                             locked_at          timestamp,
                                             created_at         timestamp DEFAULT current_timestamp,
                                             modified_at        timestamp DEFAULT current_timestamp,
                                             stopped_at         timestamp DEFAULT null
);

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS contact_email_verified bool NOT NULL DEFAULT false;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS tax_id                 varchar(64);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS fiscal_name            varchar(255);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS fiscal_address         varchar(500);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS billing_email          varchar(255);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS billing_email_verified bool NOT NULL DEFAULT false;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS billing_cycle          varchar(16);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS next_invoice_due_at    timestamptz;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS currency               varchar(3);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS currency_refresh_mode  varchar(16) NOT NULL DEFAULT 'manual';
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS currency_refresh_cadence varchar(16) NOT NULL DEFAULT 'weekly';
-- Only meaningful when currency_refresh_cadence = 'every_n_days'
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS currency_refresh_interval_days integer;
-- Currency the org prices its own catalog/inventory in - independent of `currency`, which is
-- the currency Kenoma invoices the org's own subscription in.
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS product_pricing_currency varchar(3) NOT NULL DEFAULT 'ARS';

CREATE TABLE IF NOT EXISTS services (
                                        id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                        name         varchar(255) NOT NULL UNIQUE,
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
                                           is_initialized    bool NOT NULL DEFAULT false,
                                           created_at        timestamp DEFAULT current_timestamp,
                                           modified_at       timestamp DEFAULT current_timestamp,
                                           FOREIGN KEY (org_id) REFERENCES organizations(id),
                                           FOREIGN KEY (service_id) REFERENCES services(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_credentials_org_service ON credentials(org_id, service_id);

CREATE TABLE IF NOT EXISTS billing_history (
                                               id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                               org_id          uuid NOT NULL,
                                               billing_cycle   varchar(16) NOT NULL,
                                               due_at          timestamptz NOT NULL,
                                               created_at      timestamptz DEFAULT current_timestamp,
                                               FOREIGN KEY (org_id) REFERENCES organizations(id)
);

ALTER TABLE billing_history ADD COLUMN IF NOT EXISTS amount         numeric(12,2);
ALTER TABLE billing_history ADD COLUMN IF NOT EXISTS currency       varchar(3);
ALTER TABLE billing_history ADD COLUMN IF NOT EXISTS line_items     jsonb;
ALTER TABLE billing_history ADD COLUMN IF NOT EXISTS payment_status    varchar(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE billing_history ADD COLUMN IF NOT EXISTS paid_at            timestamptz;
ALTER TABLE billing_history ADD COLUMN IF NOT EXISTS payment_reference  varchar(255);

CREATE INDEX IF NOT EXISTS idx_billing_history_org_id ON billing_history(org_id);

CREATE TABLE IF NOT EXISTS base_pricing (
                                            id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                            price           numeric(12,2) NOT NULL,
                                            currency        varchar(3) NOT NULL,
                                            effective_from  timestamptz NOT NULL,
                                            created_at      timestamptz DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS idx_base_pricing_effective_from ON base_pricing(effective_from);

CREATE TABLE IF NOT EXISTS module_pricing (
                                              id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                              service_id         uuid NOT NULL REFERENCES services(id),
                                              price              numeric(12,2) NOT NULL,
                                              currency           varchar(3) NOT NULL,
                                              included_in_base   bool NOT NULL DEFAULT false,
                                              effective_from     timestamptz NOT NULL,
                                              created_at         timestamptz DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS idx_module_pricing_service_effective ON module_pricing(service_id, effective_from);

CREATE TABLE IF NOT EXISTS exchange_rates (
                                              id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                              from_currency   varchar(3) NOT NULL,
                                              to_currency     varchar(3) NOT NULL,
                                              rate            numeric(18,8) NOT NULL,
                                              effective_from  timestamptz NOT NULL,
                                              created_at      timestamptz DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS idx_exchange_rates_pair_effective ON exchange_rates(from_currency, to_currency, effective_from);

CREATE TABLE IF NOT EXISTS pending_org_verifications (
                                                          id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                                          org_id      uuid NOT NULL REFERENCES organizations(id),
                                                          field_name  varchar(32) NOT NULL DEFAULT 'BILLING_EMAIL',
                                                          email       varchar(255) NOT NULL,
                                                          token_hash  varchar(64) NOT NULL,
                                                          expires_at  timestamptz NOT NULL,
                                                          used        boolean NOT NULL DEFAULT false,
                                                          created_at  timestamptz DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS idx_pending_org_verifications_org_id ON pending_org_verifications(org_id);

CREATE TABLE IF NOT EXISTS export_jobs (
                                           id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                           org_id          uuid NOT NULL REFERENCES organizations(id),
                                           status          varchar(16) NOT NULL DEFAULT 'PENDING',
                                           format          varchar(8) NOT NULL DEFAULT 'SQL',
                                           layout          varchar(8) NOT NULL DEFAULT 'SEPARATE',
                                           requested_at    timestamptz DEFAULT current_timestamp,
                                           started_at      timestamptz,
                                           completed_at    timestamptz,
                                           error_message   text,
                                           object_keys     text
);

CREATE INDEX IF NOT EXISTS idx_export_jobs_status ON export_jobs(status);
CREATE INDEX IF NOT EXISTS idx_export_jobs_org_id ON export_jobs(org_id);

-- Catalog of DR backups (instance-level pg_dumps and per-org/per-service rewind dumps) actually
-- uploaded to the bucket - restore looks these up rather than re-deriving target coordinates or
-- listing S3 directly.
CREATE TABLE IF NOT EXISTS dr_backups (
                                          id                              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                          scope                           varchar(16) NOT NULL, -- INSTANCE | ORG
                                          instance_host                   varchar(255),
                                          instance_port                   integer,
                                          instance_db                     varchar(255),
                                          representative_credentials_id   uuid,
                                          org_id                          uuid REFERENCES organizations(id),
                                          service_name                    varchar(64),
                                          object_key                      text NOT NULL,
                                          created_at                      timestamptz DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS idx_dr_backups_instance ON dr_backups(instance_host, instance_port, instance_db, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dr_backups_org ON dr_backups(org_id, service_name, created_at DESC);

-- DrRestoreService's org-scoped restore runs inside one transaction that DELETEs then re-inserts
-- `organizations` (among other DR_RAUM_TABLES) - export_jobs and dr_backups are platform-internal
-- bookkeeping tables that FK to organizations but are deliberately NOT part of the org-scoped
-- restore set, so their rows for that org still exist when `organizations` gets deleted. A
-- non-deferrable FK would reject that DELETE immediately even though the same transaction
-- re-inserts the row a few statements later; making these deferrable and having the restore script
-- issue `SET CONSTRAINTS ALL DEFERRED` lets Postgres check them at COMMIT instead, once the
-- re-insert has already happened.
ALTER TABLE export_jobs ALTER CONSTRAINT export_jobs_org_id_fkey DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE dr_backups ALTER CONSTRAINT dr_backups_org_id_fkey DEFERRABLE INITIALLY DEFERRED;
-- Enforces "at most one active export per org" at the database level, closing the race where two
-- concurrent requests both see no active job (application-level check-then-insert) and both
-- insert one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_export_jobs_active_per_org ON export_jobs(org_id)
    WHERE status IN ('PENDING', 'RUNNING');
