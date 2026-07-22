package raum.onboarding;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

class PresetLocaleResourcesTest {

    private static final String[] KEYS = {
            "clothing.location.mainStore", "clothing.location.branch", "clothing.location.stockroom",
            "clothing.meta.size", "clothing.meta.colour",
            "clothing.option.size.s", "clothing.option.size.m", "clothing.option.size.l", "clothing.option.size.xl",
            "clothing.option.colour.black", "clothing.option.colour.white", "clothing.option.colour.red",
            "clothing.product.tshirt.name", "clothing.product.tshirt.desc",
            "clothing.product.jeans.name", "clothing.product.jeans.desc",
            "book.location.store", "book.meta.format", "book.meta.genre",
            "book.option.format.hardcover", "book.option.format.paperback", "book.option.format.ebook",
            "book.option.genre.fiction", "book.option.genre.nonfiction", "book.option.genre.science",
            "book.product.novel.name", "book.product.novel.desc",
            "book.product.guide.name", "book.product.guide.desc",
            "repair.location.workshop", "repair.location.partsStorage", "repair.meta.condition",
            "repair.option.condition.new", "repair.option.condition.used", "repair.option.condition.refurbished",
            "repair.product.screen.name", "repair.product.screen.desc",
            "repair.product.battery.name", "repair.product.battery.desc",
            "warehouse.location.receiving", "warehouse.location.zoneA", "warehouse.location.zoneB",
            "warehouse.location.dispatch", "warehouse.meta.category",
            "warehouse.option.category.electronics", "warehouse.option.category.furniture",
            "warehouse.option.category.supplies",
            "warehouse.product.component.name", "warehouse.product.component.desc",
            "warehouse.product.box.name", "warehouse.product.box.desc",
    };

    @Test
    void englishBundle_hasEveryPresetKey() {
        ResourceBundle bundle = ResourceBundle.getBundle("presets", Locale.ENGLISH);
        for (String key : KEYS) {
            assertThat(bundle.getString(key)).as("key %s", key).isNotBlank();
        }
    }

    @Test
    void spanishBundle_hasEveryPresetKeyTranslated() {
        ResourceBundle en = ResourceBundle.getBundle("presets", Locale.ENGLISH);
        ResourceBundle es = ResourceBundle.getBundle("presets", Locale.forLanguageTag("es"));
        for (String key : KEYS) {
            assertThat(es.getString(key)).as("key %s", key).isNotBlank();
        }
        // Spot-check a handful of values actually differ between locales, not just present.
        assertThat(es.getString("clothing.product.tshirt.name")).isNotEqualTo(en.getString("clothing.product.tshirt.name"));
        assertThat(es.getString("book.product.novel.name")).isNotEqualTo(en.getString("book.product.novel.name"));
        assertThat(es.getString("warehouse.meta.category")).isNotEqualTo(en.getString("warehouse.meta.category"));
    }
}
