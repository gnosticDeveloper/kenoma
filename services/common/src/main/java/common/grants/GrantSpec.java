package common.grants;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One {@code GRANT} a tenant database role receives: a set of {@link Op}s on a single
 * table, optionally narrowed to specific columns.
 *
 * <p>An empty {@link #columns()} list means the grant is table-wide. A non-empty list
 * renders as {@code GRANT SELECT (col_a, col_b) ON <table>} — Postgres only supports
 * column lists for {@code SELECT}, {@code INSERT} and {@code UPDATE}, which is enough for
 * the one case that needs it (Vassago's read-only tier must see every {@code users}
 * column except {@code password}).
 *
 * <p>The sentinel table name {@value #ALL_TABLES} means "every table in schema public";
 * {@link GrantStatementRenderer} turns it into {@code ON ALL TABLES IN SCHEMA public}.
 * Used only by the generic fallback profile for services that have no explicit grant
 * model yet.
 */
public record GrantSpec(String table, Set<Op> ops, List<String> columns) {

    public static final String ALL_TABLES = "*";

    public GrantSpec {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("GrantSpec table must not be blank");
        }
        if (ops == null || ops.isEmpty()) {
            throw new IllegalArgumentException("GrantSpec ops must not be empty for table " + table);
        }
        ops = Collections.unmodifiableSet(EnumSet.copyOf(ops));
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public static GrantSpec of(String table, Op... ops) {
        return new GrantSpec(table, EnumSet.copyOf(List.of(ops)), List.of());
    }

    public static GrantSpec columns(String table, List<String> columns, Op... ops) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("column-scoped GrantSpec needs at least one column: " + table);
        }
        return new GrantSpec(table, EnumSet.copyOf(List.of(ops)), columns);
    }

    public static GrantSpec allTables(Op... ops) {
        return new GrantSpec(ALL_TABLES, EnumSet.copyOf(List.of(ops)), List.of());
    }

    public boolean isAllTables() {
        return ALL_TABLES.equals(table);
    }

    public boolean isColumnScoped() {
        return !columns.isEmpty();
    }
}
