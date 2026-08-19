package raum.backup;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Builds an org-scoped, restorable SQL dump: one {@code COPY ... FROM stdin} block per table
 * (gzipped), org-filtered via each {@link ExportTable}'s WHERE-clause template. Shared by
 * {@link TenantExportPoller}'s SQL export format and DR's per-org backup ({@code OrgBackupScheduler})
 * - the only difference between the two callers is which table lists they pass in (tenant export's
 * trimmed lists vs. DR's full lists), not how the dump itself is built.
 */
@Component
public class SqlCopyDumpWriter {

    public Path buildInsertOnlySql(PgConn conn, List<ExportTable> tables, UUID orgId, String tempFilePrefix)
            throws IOException, InterruptedException {
        Path plainFile = Files.createTempFile(tempFilePrefix, ".sql");
        Path gzFile = Files.createTempFile(tempFilePrefix, ".sql.gz");
        try {
            for (ExportTable table : tables) {
                appendTableCopy(plainFile, conn, table, orgId);
            }
            gzip(plainFile, gzFile);
            return gzFile;
        } finally {
            Files.deleteIfExists(plainFile);
        }
    }

    public String filteredSelect(ExportTable table, UUID orgId) {
        return String.format("SELECT %s FROM %s WHERE %s", table.columns(), table.name(),
                String.format(table.whereClauseTemplate(), orgId));
    }

    private void appendTableCopy(Path plainFile, PgConn conn, ExportTable table, UUID orgId) throws IOException, InterruptedException {
        byte[] rows = runPsql(conn, "\\copy (" + filteredSelect(table, orgId) + ") TO STDOUT");

        String header = "COPY " + table.name() + " (" + table.columns() + ") FROM stdin;\n";
        Files.writeString(plainFile, header, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.write(plainFile, rows, StandardOpenOption.APPEND);
        Files.writeString(plainFile, "\\.\n\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    public byte[] runPsql(PgConn conn, String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("psql", "-h", conn.host(), "-p", String.valueOf(conn.port()),
                "-U", conn.user(), "-d", conn.db(), "-t", "-A", "-c", command);
        pb.environment().put("PGPASSWORD", conn.password());

        Process process = pb.start();
        byte[] out = process.getInputStream().readAllBytes();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("psql exited " + exitCode + ": " + stderr);
        }
        return out;
    }

    /** Runs a {@code psql} script file against a target database via {@code -f}, aborting on the
     * first error ({@code ON_ERROR_STOP=1}) - used by restore, where a mid-script failure must not
     * silently continue applying a partial script. */
    public void runPsqlFile(PgConn conn, Path scriptFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("psql", "-h", conn.host(), "-p", String.valueOf(conn.port()),
                "-U", conn.user(), "-d", conn.db(), "-v", "ON_ERROR_STOP=1", "-f", scriptFile.toString());
        pb.environment().put("PGPASSWORD", conn.password());

        Process process = pb.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("psql restore exited " + exitCode + ": " + stderr);
        }
    }

    public void gzip(Path plainFile, Path gzFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "gzip -c " + plainFile + " > " + gzFile);
        Process process = pb.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("gzip exited " + exitCode + ": " + stderr);
        }
    }

    public void gunzip(Path gzFile, Path plainFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "gunzip -c " + gzFile + " > " + plainFile);
        Process process = pb.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("gunzip exited " + exitCode + ": " + stderr);
        }
    }
}
