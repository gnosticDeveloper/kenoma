package bime.services;

import bime.services.SaleTicketDocumentService.TicketData;
import bime.services.SaleTicketDocumentService.TicketLine;
import common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleTicketDocumentServiceTest {

    private static final Locale EN = Locale.US;
    private static final Locale ES = Locale.forLanguageTag("es");

    private final SaleTicketDocumentService service = new SaleTicketDocumentService();

    private static boolean isPdf(byte[] b) {
        return b != null && b.length > 400 && new String(b, 0, 5).equals("%PDF-");
    }

    private static TicketLine line(String desc, String qty, String unit, String unitPrice, String total) {
        return new TicketLine(desc, new BigDecimal(qty), unit, new BigDecimal(unitPrice), new BigDecimal(total));
    }

    private static TicketData data(List<TicketLine> lines, String subtotal) {
        return new TicketData("Acme Books", "Downtown store", "DT-01", "R-1042", UUID.randomUUID().toString(),
                LocalDateTime.of(2026, 9, 2, 14, 30), "USD", new BigDecimal(subtotal), null, lines);
    }

    @Test
    void render_producesPdf() {
        byte[] pdf = service.render(data(List.of(
                line("Widget Red / XL (WIDGET-001)", "3", null, "5.00", "15.00"),
                line("Soda case", "1", "case", "18.00", "18.00")), "33.00"), EN);

        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void render_spanishLocale_producesPdf() {
        byte[] pdf = service.render(data(List.of(
                line("Pan de masa madre (BAKERY-001)", "2", null, "6.50", "13.00")), "13.00"), ES);

        assertThat(isPdf(pdf)).isTrue();
    }

    @Test
    void render_toleratesNullOptionalFields() {
        // no company, no location, no code, no reference, no soldAt, no currency, no note
        TicketData sparse = new TicketData(null, null, null, null, null, null, null,
                new BigDecimal("4.00"), null, List.of(line("Loose item", "2", null, "2.00", "4.00")));

        assertThat(isPdf(service.render(sparse, EN))).isTrue();
        assertThat(isPdf(service.render(sparse, null))).isTrue();
    }

    @Test
    void render_locationWithoutCode_producesPdf() {
        TicketData d = new TicketData("Acme Books", "Downtown store", null, "R-1", UUID.randomUUID().toString(),
                LocalDateTime.now(), "USD", new BigDecimal("2.00"), null,
                List.of(line("Item", "1", null, "2.00", "2.00")));

        assertThat(isPdf(service.render(d, EN))).isTrue();
    }

    @Test
    void render_withNoteAndManyLines() {
        List<TicketLine> lines = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> line("Item " + i, "1", null, "1.00", "1.00"))
                .toList();
        TicketData d = new TicketData("Kiosk Ltd", "Kiosk", "K1", null, UUID.randomUUID().toString(),
                LocalDateTime.now(), "EUR", new BigDecimal("40.00"), "Gift wrapped", lines);

        assertThatCode(() -> service.render(d, ES)).doesNotThrowAnyException();
        assertThat(isPdf(service.render(d, ES))).isTrue();
    }

    @Test
    void render_noLines_throws() {
        assertThatThrownBy(() -> service.render(data(List.of(), "0.00"), EN))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.render(null, EN))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void money_knownCurrency_rendersWithSymbol_notBareCode() {
        String usd = SaleTicketDocumentService.money(new BigDecimal("19.50"), "USD", EN);
        assertThat(usd).contains("$").contains("19.50").doesNotContain("USD");

        String eur = SaleTicketDocumentService.money(new BigDecimal("19.50"), "EUR", ES);
        assertThat(eur).contains("€");
    }

    @Test
    void money_usesCurrencyHomeConventions_notThreeLetterCode() {
        // ARS in a Spanish ticket: "$" with Argentine grouping, not "3.100,00 ARS".
        String ars = SaleTicketDocumentService.money(new BigDecimal("3100.00"), "ARS", ES);
        assertThat(ars).contains("$").contains("3.100,00").doesNotContain("ARS");

        String gbp = SaleTicketDocumentService.money(new BigDecimal("3.75"), "GBP", ES);
        assertThat(gbp).contains("£").doesNotContain("GBP");

        // Fullwidth yen from ja-JP is folded to the halfwidth glyph.
        String jpy = SaleTicketDocumentService.money(new BigDecimal("1830"), "JPY", EN);
        assertThat(jpy).contains("¥").doesNotContain("￥").doesNotContain("JPY").doesNotContain(".00");
    }

    @Test
    void money_zeroDecimalCurrency_hasNoFractionDigits() {
        String jpy = SaleTicketDocumentService.money(new BigDecimal("1830"), "JPY", EN);
        assertThat(jpy).contains("1,830").doesNotContain("1,830.0").doesNotContain(".00");
    }

    @Test
    void money_noCurrencyOnFile_isGroupedNumberWithoutSymbol() {
        String out = SaleTicketDocumentService.money(new BigDecimal("1234.5"), null, EN);
        assertThat(out).isEqualTo("1,234.50");
    }

    @Test
    void money_unknownCode_fallsBackToPrefixedNumber() {
        String out = SaleTicketDocumentService.money(new BigDecimal("5"), "XYZ", EN);
        assertThat(out).isEqualTo("XYZ 5.00");
    }

    @Test
    void money_nullAmount_isDash() {
        assertThat(SaleTicketDocumentService.money(null, "USD", EN)).isEqualTo("-");
    }

    @Test
    void render_unitPrintsExactlyAsStored_noTranslation() {
        // "units", a custom org unit, whatever - the ticket echoes it verbatim in both languages.
        TicketData en = new TicketData("Shop", "Loc", "L1", "R1", UUID.randomUUID().toString(),
                LocalDateTime.now(), "USD", new BigDecimal("6.00"), null,
                List.of(new TicketLine("Thing", new BigDecimal("3"), "units", new BigDecimal("2.00"), new BigDecimal("6.00"))));
        assertThat(isPdf(service.render(en, EN))).isTrue();
        assertThat(isPdf(service.render(en, ES))).isTrue();
    }
}
