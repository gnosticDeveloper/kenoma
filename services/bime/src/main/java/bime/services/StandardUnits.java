package bime.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Physical-constant unit conversions - not org data, the same for every org, so they live in code
  * rather than a table. Each recognized standard unit name maps to a dimension and its factor
  * relative to that dimension's base unit (kg for mass, m for length, l for volume). Two standard
  * units convert to each other only within the same dimension; "units" (count) has no conversions -
  * it's just the default base unit for non-metric products. Org-defined custom units (case,
  * six-pack, dozen...) are never in this map; those always need an explicit variant_uom_conversions row. */
final class StandardUnits {

    private StandardUnits() {}

    enum Dimension { MASS, LENGTH, VOLUME }

    private record Unit(Dimension dimension, BigDecimal factorToDimensionBase) {}

    private static final Map<String, Unit> UNITS = Map.of(
            "kg", new Unit(Dimension.MASS, BigDecimal.ONE),
            "g", new Unit(Dimension.MASS, new BigDecimal("0.001")),
            "m", new Unit(Dimension.LENGTH, BigDecimal.ONE),
            "cm", new Unit(Dimension.LENGTH, new BigDecimal("0.01")),
            "l", new Unit(Dimension.VOLUME, BigDecimal.ONE),
            "ml", new Unit(Dimension.VOLUME, new BigDecimal("0.001"))
    );

    /** Every org's catalog is seeded with these on first use (see UnitsService) so a fresh org
      * always has the common metric units + the generic count unit available without setup. */
    static final List<String> SEED_NAMES = List.of("units", "kg", "g", "m", "cm", "l", "ml");

    /** True for any of SEED_NAMES, not just the ones with dimension/factor data - "units" is
      * recognized (auto-registrable, shown as standard) even though it has no metric conversions. */
    static boolean isStandard(String normalizedName) {
        return SEED_NAMES.contains(normalizedName);
    }

    /** Number of base-unit-many units make up one of toUnit, e.g. factor("kg", "g") = 0.001 (1 g =
      * 0.001 kg) - same semantics as variant_uom_conversions.factor. Returns null if either name
      * isn't a recognized standard unit, or they're in different dimensions (e.g. kg to m). */
    static BigDecimal factor(String normalizedBaseName, String normalizedToName) {
        Unit base = UNITS.get(normalizedBaseName);
        Unit to = UNITS.get(normalizedToName);
        if (base == null || to == null || base.dimension() != to.dimension()) {
            return null;
        }
        return to.factorToDimensionBase().divide(base.factorToDimensionBase());
    }
}
