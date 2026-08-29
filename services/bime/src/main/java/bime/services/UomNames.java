package bime.services;

/** Unit-of-measure names (base_uom, uom_name) are matched case-insensitively - "Case", "case" and
  * "CASE" are the same unit. Canonicalized to trimmed lowercase everywhere a unit name is written
  * or looked up, so storage and comparisons never need a case-insensitive collation/index. */
final class UomNames {

    private UomNames() {}

    static String normalize(String name) {
        return name == null ? null : name.trim().toLowerCase();
    }
}
