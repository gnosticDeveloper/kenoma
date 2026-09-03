package bime.services;

import common.exception.BadRequestException;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.draw.LineSeparator;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a narrow, thermal-receipt-style PDF ticket for one completed sale: the organization name
 * as the heading, then the location, reference, date and time, and the priced lines. A sale longer
 * than one page flows onto further pages; the subtotal and the "not a tax receipt" disclaimer come
 * once, pinned to the bottom of the last page. Labels are English or Spanish (the two languages the
 * app supports); money is written the way that currency normally is; units print exactly as the
 * organization named them in its unit catalogue. It is deliberately minimal and is NOT a tax
 * document - tax compliance is out of scope (issue #172) and the footer says so. There is no
 * printer integration; the caller prints the returned PDF through their own operating system.
 */
@Service
public class SaleTicketDocumentService {

    /** One priced line on the ticket. {@code quantity} is shown with its {@code unit} - the pack
      * unit when sold as a pack ("2 case"), otherwise the variant's base unit ("0.5 kg"). */
    public record TicketLine(String description, BigDecimal quantity, String unit,
                             BigDecimal unitPrice, BigDecimal lineTotal) {}

    public record TicketData(String companyName, String locationName, String locationCode, String reference,
                             String saleId, LocalDateTime soldAt, String currency, BigDecimal subtotal,
                             String note, List<TicketLine> lines) {}

    /** 80 mm roll minus a hair, in PostScript points. */
    private static final float WIDTH = 226f;
    /** Fixed cut length; content longer than one page flows onto further pages. */
    private static final float HEIGHT = 340f;
    private static final float MARGIN = 14f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final Font TITLE = new Font(Font.HELVETICA, 11f, Font.BOLD);
    private static final Font META = new Font(Font.HELVETICA, 7.5f);
    private static final Font ITEM = new Font(Font.HELVETICA, 8f);
    private static final Font ITEM_BOLD = new Font(Font.HELVETICA, 8f, Font.BOLD);
    private static final Font FOOT = new Font(Font.HELVETICA, 6.5f, Font.ITALIC);

    /** currency code -> a locale that uses it, discovered from the JDK's locale data (see
      * {@link #currencyLocale}) so amounts print the local way ($, €, £, ¥) instead of the
      * 3-letter code. {@link Locale#ROOT} means "no such locale found". */
    private static final ConcurrentHashMap<String, Locale> CURRENCY_LOCALES = new ConcurrentHashMap<>();

    /** The handful of fixed strings on the ticket, per supported language. */
    private record Labels(String receipt, String location, String reference, String date, String time,
                          String subtotal, String disclaimer) {}

    private static Labels labels(Locale locale) {
        if (isSpanish(locale)) {
            return new Labels("Recibo de venta", "Ubicación", "Referencia", "Fecha", "Hora", "Subtotal",
                    "No es un comprobante fiscal. Impreso el %s.");
        }
        return new Labels("Sale receipt", "Location", "Reference", "Date", "Time", "Subtotal",
                "This is not a tax receipt. Printed %s.");
    }

    private static boolean isSpanish(Locale locale) {
        return locale != null && "es".equals(locale.getLanguage());
    }

    public byte[] render(TicketData ticket, Locale rawLocale) {
        if (ticket == null || ticket.lines() == null || ticket.lines().isEmpty()) {
            throw new BadRequestException("Nothing to print: this sale has no lines.");
        }
        Locale locale = rawLocale == null ? Locale.ENGLISH : rawLocale;
        Labels text = labels(locale);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(new Rectangle(WIDTH, HEIGHT), MARGIN, MARGIN, MARGIN, MARGIN);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            String heading = ticket.companyName() == null || ticket.companyName().isBlank()
                    ? text.receipt().toUpperCase(locale) : ticket.companyName();
            Paragraph title = new Paragraph(heading, TITLE);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            Paragraph caption = new Paragraph(text.receipt(), META);
            caption.setAlignment(Element.ALIGN_CENTER);
            caption.setSpacingAfter(6);
            document.add(caption);

            String location = locationLine(ticket);
            if (location != null) {
                document.add(meta(text.location(), location));
            }
            document.add(meta(text.reference(), reference(ticket.reference(), ticket.saleId())));
            document.add(meta(text.date(), ticket.soldAt() == null ? "-" : DATE.format(ticket.soldAt())));
            document.add(meta(text.time(), ticket.soldAt() == null ? "-" : TIME.format(ticket.soldAt())));
            document.add(rule());

            PdfPTable table = new PdfPTable(new float[]{62f, 38f});
            table.setWidthPercentage(100);
            table.setSpacingBefore(2);
            for (TicketLine line : ticket.lines()) {
                table.addCell(cell(line.description(), ITEM, Element.ALIGN_LEFT, 2));
                table.addCell(cell(qtyLabel(line, ticket.currency(), locale), META, Element.ALIGN_LEFT, 0));
                table.addCell(cell(money(line.lineTotal(), ticket.currency(), locale), ITEM, Element.ALIGN_RIGHT, 0));
            }
            document.add(table);

            // Closing block: the subtotal and disclaimer, once, pinned to the bottom of the last page.
            PdfPTable closing = closingBlock(text, money(ticket.subtotal(), ticket.currency(), locale),
                    ticket.note());
            float blockHeight = closing.getTotalHeight();
            float pageBottom = document.bottom();
            if (writer.getVerticalPosition(false) - pageBottom < blockHeight + 6f) {
                document.newPage();
                writer.setPageEmpty(false);
            }
            closing.writeSelectedRows(0, -1, MARGIN, pageBottom + blockHeight, writer.getDirectContent());

            document.close();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Failed to generate the sale ticket: " + e.getMessage());
        }
        return out.toByteArray();
    }

    /** The bottom-of-ticket block as one measurable table: a rule, the bold subtotal row, an
      * optional note, and the disclaimer. Width is locked so {@code getTotalHeight()} is exact and
      * the caller can position it with {@code writeSelectedRows}. */
    private static PdfPTable closingBlock(Labels text, String subtotalAmount, String note) {
        PdfPTable t = new PdfPTable(new float[]{55f, 45f});
        t.setTotalWidth(WIDTH - 2 * MARGIN);
        t.setLockedWidth(true);

        PdfPCell sep = new PdfPCell();
        sep.setColspan(2);
        sep.setBorder(Rectangle.TOP);
        sep.setBorderColorTop(Color.GRAY);
        sep.setBorderWidthTop(0.5f);
        sep.setFixedHeight(5f);
        t.addCell(sep);

        t.addCell(closingCell(text.subtotal(), ITEM_BOLD, Element.ALIGN_LEFT, 1, 3f));
        t.addCell(closingCell(subtotalAmount, ITEM_BOLD, Element.ALIGN_RIGHT, 1, 3f));

        if (note != null && !note.isBlank()) {
            t.addCell(closingCell(note, META, Element.ALIGN_LEFT, 2, 8f));
        }
        t.addCell(closingCell(text.disclaimer().formatted(DATE.format(LocalDateTime.now())),
                FOOT, Element.ALIGN_CENTER, 2, 10f));
        return t;
    }

    private static PdfPCell closingCell(String value, Font font, int align, int colspan, float padTop) {
        PdfPCell c = new PdfPCell(new Phrase(value, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setColspan(colspan);
        c.setPaddingTop(padTop);
        c.setPaddingBottom(1.5f);
        return c;
    }

    private static Paragraph meta(String label, String value) {
        Paragraph p = new Paragraph(label + ": " + value, META);
        p.setSpacingAfter(1);
        return p;
    }

    private static Paragraph rule() {
        Paragraph p = new Paragraph();
        p.add(new Chunk(new LineSeparator(0.5f, 100f, Color.GRAY, Element.ALIGN_CENTER, -2)));
        p.setSpacingBefore(3);
        p.setSpacingAfter(3);
        return p;
    }

    private static PdfPCell cell(String text, Font font, int align, int colspan) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        cell.setPadding(1.5f);
        if (colspan > 0) {
            cell.setColspan(colspan);
        }
        return cell;
    }

    private static String qtyLabel(TicketLine line, String currency, Locale locale) {
        // The unit prints exactly as the organization named it in its unit catalogue - no translation.
        String u = line.unit() == null || line.unit().isBlank() ? "" : " " + line.unit().trim();
        return trim(line.quantity()) + u + " × " + money(line.unitPrice(), currency, locale);
    }

    /** A locale that uses {@code currency}, found once from {@link Locale#getAvailableLocales()} and
      * cached, so we can format the amount the way that currency is normally written. */
    private static Locale currencyLocale(Currency currency) {
        Locale found = CURRENCY_LOCALES.computeIfAbsent(currency.getCurrencyCode(), code -> {
            for (Locale l : Locale.getAvailableLocales()) {
                if (l.getCountry().isEmpty() || l.getLanguage().isEmpty()) {
                    continue;
                }
                try {
                    if (currency.equals(Currency.getInstance(l))) {
                        return l;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return Locale.ROOT;
        });
        return Locale.ROOT.equals(found) ? null : found;
    }

    /** Money the way that currency is normally written - {@code $3.100,00} for ARS, {@code $19.50}
      * for USD, {@code 19,50 €}, {@code £3.75}, {@code ¥1,830} - by formatting with a locale that
      * uses that currency ({@link #currencyLocale}). A currency the JDK knows no locale for formats
      * in the ticket's own language (which shows the 3-letter code); no currency at all gives a bare
      * grouped number; a non-ISO code is prefixed as-is. Zero-decimal currencies (JPY, CLP, ...)
      * print no fraction digits. */
    static String money(BigDecimal amount, String currency, Locale rawLocale) {
        if (amount == null) {
            return "-";
        }
        Locale locale = rawLocale == null ? Locale.ENGLISH : rawLocale;
        if (currency == null || currency.isBlank()) {
            return plain(locale).format(amount);
        }
        try {
            Currency iso = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
            Locale forCurrency = currencyLocale(iso);
            NumberFormat nf = NumberFormat.getCurrencyInstance(forCurrency != null ? forCurrency : locale);
            nf.setCurrency(iso);
            int digits = iso.getDefaultFractionDigits();
            if (digits >= 0) {
                nf.setMinimumFractionDigits(digits);
                nf.setMaximumFractionDigits(digits);
            }
            return nf.format(amount)
                    .replace('￥', '¥')
                    .replaceAll("(\\p{Sc})[\\s\\u00a0\\u202f]+(\\d)", "$1$2");
        } catch (IllegalArgumentException unknownCode) {
            return currency + " " + plain(locale).format(amount);
        }
    }

    private static NumberFormat plain(Locale locale) {
        NumberFormat nf = NumberFormat.getNumberInstance(locale);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf;
    }

    private static String trim(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        String s = value.stripTrailingZeros().toPlainString();
        return s.isEmpty() ? "0" : s;
    }

    /** "Name (CODE)", just the name when there is no code, or null when there is no location at all. */
    private static String locationLine(TicketData ticket) {
        String name = ticket.locationName();
        if (name == null || name.isBlank()) {
            return null;
        }
        String code = ticket.locationCode();
        return code == null || code.isBlank() ? name : name + " (" + code + ")";
    }

    private static String reference(String reference, String saleId) {
        if (reference != null && !reference.isBlank()) {
            return reference;
        }
        return saleId == null ? "-" : saleId.substring(0, Math.min(8, saleId.length()));
    }
}
