-- Same root cause as V2/V3: this database was Flyway-baselined against an already-existing
-- schema, so V1__baseline.sql was marked applied without ever running. Tables/statements that
-- only existed in the baseline file itself (not in the pre-existing schema) never got created —
-- dr_backups is one of them. Recreated here verbatim from V1, plus the two statements V1 runs
-- right after it that dr_backups' restore flow depends on.
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

-- DrRestoreService's org-scoped restore needs both FKs deferrable (see V1 for the full
-- explanation); reapplying export_jobs' is harmless if it already ran.
ALTER TABLE export_jobs ALTER CONSTRAINT export_jobs_org_id_fkey DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE dr_backups ALTER CONSTRAINT dr_backups_org_id_fkey DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX IF NOT EXISTS uq_export_jobs_active_per_org ON export_jobs(org_id)
    WHERE status IN ('PENDING', 'RUNNING');
