package common.grants;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composition helpers for building a tier's {@link GrantSpec} set out of small, reusable
 * pieces. A tier is expressed as {@code union(catalogSelect, salesWrites, ...)} so adding
 * a permission later is a one-line edit to the fragment it belongs to rather than a
 * rewrite of every tier that inherits it.
 *
 * <p>{@link #union} merges specs for the same table: operations are OR-ed together, and a
 * table-wide spec always wins over a column-scoped one for the same table (the broader
 * grant subsumes the narrower). Column-scoped specs for the same table union their column
 * lists, preserving first-seen order.
 */
public final class GrantFragment {

    private GrantFragment() {}

    public static Set<GrantSpec> select(String... tables) {
        return ops(EnumSet.of(Op.SELECT), tables);
    }

    /** {@code INSERT, UPDATE, DELETE} — a full write grant, no {@code SELECT}. */
    public static Set<GrantSpec> writes(String... tables) {
        return ops(EnumSet.of(Op.INSERT, Op.UPDATE, Op.DELETE), tables);
    }

    public static Set<GrantSpec> insert(String... tables) {
        return ops(EnumSet.of(Op.INSERT), tables);
    }

    public static Set<GrantSpec> insertUpdate(String... tables) {
        return ops(EnumSet.of(Op.INSERT, Op.UPDATE), tables);
    }

    public static Set<GrantSpec> crud(String... tables) {
        return ops(EnumSet.of(Op.SELECT, Op.INSERT, Op.UPDATE, Op.DELETE), tables);
    }

    private static Set<GrantSpec> ops(Set<Op> ops, String... tables) {
        Set<GrantSpec> out = new java.util.LinkedHashSet<>();
        for (String table : tables) {
            out.add(new GrantSpec(table, ops, List.of()));
        }
        return out;
    }

    public static Set<GrantSpec> selectColumns(String table, List<String> columns) {
        return Set.of(GrantSpec.columns(table, columns, Op.SELECT));
    }

    @SafeVarargs
    public static Set<GrantSpec> union(Set<GrantSpec>... fragments) {
        Map<String, GrantSpec> byTable = new LinkedHashMap<>();
        for (Set<GrantSpec> fragment : fragments) {
            for (GrantSpec spec : fragment) {
                byTable.merge(spec.table(), spec, GrantFragment::mergeSpecs);
            }
        }
        return new java.util.LinkedHashSet<>(byTable.values());
    }

    private static GrantSpec mergeSpecs(GrantSpec a, GrantSpec b) {
        EnumSet<Op> ops = EnumSet.copyOf(a.ops());
        ops.addAll(b.ops());

        List<String> columns;
        if (a.columns().isEmpty() || b.columns().isEmpty()) {
            columns = List.of();
        } else {
            List<String> merged = new ArrayList<>(a.columns());
            for (String col : b.columns()) {
                if (!merged.contains(col)) {
                    merged.add(col);
                }
            }
            columns = merged;
        }
        return new GrantSpec(a.table(), ops, columns);
    }
}
