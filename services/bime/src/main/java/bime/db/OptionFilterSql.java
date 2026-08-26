package bime.db;

import org.springframework.r2dbc.core.DatabaseClient;

import java.util.List;
import java.util.UUID;

/**
 * Shared SQL fragment for narrowing any per-variant query (stock movements/balances/alert
 * thresholds/active alerts) down to rows whose variant carries the given metadata options -
 * at least one of them (OR, default) or all of them (AND, when matchAll is true). Mirrors the
 * same optionIds/matchAll semantics used by the product and variant listing endpoints.
 */
public final class OptionFilterSql {

    private OptionFilterSql() {}

    public static String fragment(String variantIdColumn) {
        return "(:hasOptionFilter = false OR " + variantIdColumn + " IN (" +
                "SELECT variant_id FROM product_variant_options WHERE option_id = ANY(:optionIds) " +
                "GROUP BY variant_id HAVING COUNT(DISTINCT option_id) >= CASE WHEN :matchAll THEN cardinality(:optionIds) ELSE 1 END" +
                "))";
    }

    public static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, List<UUID> optionIds, boolean matchAll) {
        boolean hasFilter = optionIds != null && !optionIds.isEmpty();
        return spec
                .bind("hasOptionFilter", hasFilter)
                .bind("matchAll", matchAll)
                .bind("optionIds", (hasFilter ? optionIds : List.<UUID>of()).toArray(new UUID[0]));
    }
}
