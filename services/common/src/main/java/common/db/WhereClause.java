package common.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WhereClause {

    public record Binding(String name, Object value) {}

    private final List<String> fragments = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();

    private WhereClause() {}

    public static WhereClause of() {
        return new WhereClause();
    }

    public WhereClause eq(String column, String param, Object value) {
        fragments.add(column + " = :" + param);
        bindings.add(new Binding(param, value));
        return this;
    }

    public WhereClause eqIfPresent(String column, String param, Object value) {
        if (value != null) {
            eq(column, param, value);
        }
        return this;
    }

    public WhereClause raw(String fragment) {
        fragments.add(fragment);
        return this;
    }

    public String toSql() {
        if (fragments.isEmpty()) return "";
        return "WHERE " + String.join(" AND ", fragments);
    }

    public List<Binding> bindings() {
        return Collections.unmodifiableList(bindings);
    }
}
