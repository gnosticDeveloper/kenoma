package raum.backup;

/** One exportable/backupable table: its column list, and a WHERE-clause template with a single
 * {@code %s} placeholder for the org id - either a direct {@code org_id = '%s'} or a join/subquery
 * reaching org_id through a parent table. Shared by tenant export (trimmed table lists) and DR
 * org-level backup (full table lists, including platform-internal tables tenant export omits). */
public record ExportTable(String name, String columns, String whereClauseTemplate) {}
