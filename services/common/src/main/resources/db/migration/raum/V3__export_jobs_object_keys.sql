-- Same gap as V2: object_keys was also added to the export_jobs definition in
-- V1__baseline.sql after that table already existed on some databases, and
-- CREATE TABLE IF NOT EXISTS never re-applies to add it.
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS object_keys text;
