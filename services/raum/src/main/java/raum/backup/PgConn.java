package raum.backup;

/** Connection coordinates + credentials for a target Postgres database - shared by tenant export
 * and DR backup/restore, all of which shell out to {@code psql}/{@code pg_dump} rather than using
 * a JDBC/R2DBC client for these one-off operational commands. */
public record PgConn(String host, int port, String db, String user, String password) {}
