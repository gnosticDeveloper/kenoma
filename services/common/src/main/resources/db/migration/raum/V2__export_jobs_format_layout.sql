-- V1__baseline.sql uses CREATE TABLE IF NOT EXISTS for export_jobs, so on any database where
-- that table already existed before format/layout were added to the baseline, Flyway marks V1
-- as applied without ever creating those columns. This backfills them for that case.
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS format varchar(8) NOT NULL DEFAULT 'SQL';
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS layout varchar(8) NOT NULL DEFAULT 'SEPARATE';
