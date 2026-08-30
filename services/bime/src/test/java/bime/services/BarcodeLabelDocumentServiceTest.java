package bime.services;

import bime.services.BarcodeLabelDocumentService.LabelItem;
import bime.services.BarcodeLabelDocumentService.LabelOptions;
import common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarcodeLabelDocumentServiceTest {

    private final BarcodeLabelDocumentService service = new BarcodeLabelDocumentService();

    private static LabelItem item(String barcode, String symbology) {
        return new LabelItem("Widget", "WIDGET-001-RED-XL", "Red / XL", barcode, symbology, "USD 9.99", "EACH");
    }

    private static boolean isPdf(byte[] b) {
        return b != null && b.length > 400 && new String(b, 0, 5).equals("%PDF-");
    }

    @Test
    void generate_rendersEverySymbology() {
        List<LabelItem> items = List.of(
                item("9780201379624", "EAN13"),
                item("036000291452", "UPC_A"),
                item("96385074", "EAN8"),
                item("ABC-123/45", "CODE128"),
                item("HELLO 123", "CODE39"));

        byte[] pdf = service.generate(items, new LabelOptions(3, 1, "A4"));

        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void generate_clampsAbsurdColumnsAndCopies() {
        List<LabelItem> one = List.of(item("9780201379624", "EAN13"));
        assertThatCode(() -> service.generate(one, new LabelOptions(99, 9999, "A4"))).doesNotThrowAnyException();
        assertThatCode(() -> service.generate(one, new LabelOptions(0, 0, "A4"))).doesNotThrowAnyException();
        assertThatCode(() -> service.generate(one, new LabelOptions(-5, -1, "A4"))).doesNotThrowAnyException();
    }

    @Test
    void generate_unknownPageSizeFallsBackWithoutError() {
        byte[] pdf = service.generate(List.of(item("9780201379624", "EAN13")), new LabelOptions(2, 1, "nonsense"));
        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void generate_letterPageSize() {
        byte[] pdf = service.generate(List.of(item("9780201379624", "EAN13")), new LabelOptions(2, 2, "LETTER"));
        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void generate_manyCopiesSpanMultiplePages() {
        byte[] pdf = service.generate(List.of(item("9780201379624", "EAN13")), new LabelOptions(3, 100, "A4"));
        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void generate_emptyItems_throws() {
        assertThatThrownBy(() -> service.generate(List.of(), new LabelOptions(3, 1, "A4")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.generate(null, new LabelOptions(3, 1, "A4")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void generate_unsupportedSymbology_throws() {
        assertThatThrownBy(() -> service.generate(List.of(item("123", "QR")), new LabelOptions(3, 1, "A4")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void generate_toleratesNullOptionalFields() {
        LabelItem sparse = new LabelItem("Widget", null, null, "9780201379624", "EAN13", null, null);
        byte[] pdf = service.generate(List.of(sparse), new LabelOptions(3, 1, "A4"));
        assertThat(isPdf(pdf)).isTrue();
    }
}
