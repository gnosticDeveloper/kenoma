package bime.db;

import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Arrays;

/**
 * Shared SQL fragment for order-independent, segment-style SKU search: every whitespace-separated
 * token in the search string must appear as a substring somewhere in the SKU, regardless of which
 * order the tokens are given in (so "l black" matches "HOOD-001-...-BLACK-...-L" just as well as
 * "black l" would). Implemented with Postgres's {@code ILIKE ALL(array)} rather than a dynamic
 * number of bind params, since the token count varies per request.
 */
public final class SkuSearchSql {

    private SkuSearchSql() {}

    public static String fragment(String skuColumn) {
        return "(:hasSkuFilter = false OR " + skuColumn + " ILIKE ALL(:skuPatterns))";
    }

    public static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, String sku) {
        boolean hasFilter = sku != null && !sku.isBlank();
        String[] patterns = hasFilter
                ? Arrays.stream(sku.trim().split("\\s+")).map(t -> "%" + t + "%").toArray(String[]::new)
                : new String[0];
        return spec
                .bind("hasSkuFilter", hasFilter)
                .bind("skuPatterns", patterns);
    }
}
