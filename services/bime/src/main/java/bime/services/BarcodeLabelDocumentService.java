package bime.services;

import common.exception.BadRequestException;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.Barcode;
import org.openpdf.text.pdf.Barcode128;
import org.openpdf.text.pdf.Barcode39;
import org.openpdf.text.pdf.BarcodeEAN;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Renders a printable PDF sheet of barcode labels laid out in a grid. Barcodes are drawn as real
  * scannable symbols by OpenPDF's barcode classes, not as text. There is no printer integration -
  * the caller prints the returned PDF through their own operating system. */
@Service
public class BarcodeLabelDocumentService {

    /** One label to print. {@code copies} of it are emitted consecutively. {@code unitLabel} is a
      * short pack caption like "CASE x24" (empty for a plain base-unit barcode). */
    public record LabelItem(String productName, String variantSku, String optionSummary,
                            String barcode, String symbology, String priceLabel, String unitLabel) {}

    public record LabelOptions(int columns, int copies, String pageSize) {}

    private static final float MARGIN = 28f;

    public byte[] generate(List<LabelItem> items, LabelOptions rawOpts) {
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("Nothing to print: no barcode labels were selected.");
        }
        LabelOptions opts = normalize(rawOpts);
        Rectangle page = "LETTER".equalsIgnoreCase(opts.pageSize()) ? PageSize.LETTER : PageSize.A4;

        // Everything a cell draws is sized against the real column width so nothing overflows into
        // the next cell, however many columns were asked for.
        int columns = opts.columns();
        float pad = columns >= 4 ? 4f : 7f;
        float columnWidth = (page.getWidth() - 2 * MARGIN) / columns;
        float contentWidth = Math.max(40f, columnWidth - 2 * pad - 3f);
        float nameSize = columns >= 4 ? 6.5f : 8f;
        float subSize = columns >= 4 ? 5.5f : 6.5f;
        Font nameFont = new Font(Font.HELVETICA, nameSize, Font.BOLD);
        Font subFont = new Font(Font.HELVETICA, subSize);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(page, MARGIN, MARGIN, MARGIN, MARGIN);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();
            PdfContentByte cb = writer.getDirectContent();

            PdfPTable table = new PdfPTable(columns);
            table.setWidthPercentage(100);

            int cells = 0;
            for (LabelItem item : items) {
                for (int c = 0; c < opts.copies(); c++) {
                    table.addCell(labelCell(cb, item, contentWidth, pad, columns, nameFont, subFont));
                    cells++;
                }
            }
            int remainder = cells % opts.columns();
            if (remainder != 0) {
                for (int i = 0; i < opts.columns() - remainder; i++) {
                    PdfPCell blank = new PdfPCell();
                    blank.setBorder(Rectangle.NO_BORDER);
                    table.addCell(blank);
                }
            }

            document.add(table);
            document.close();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Failed to generate barcode labels: " + e.getMessage());
        }
        return out.toByteArray();
    }

    private static LabelOptions normalize(LabelOptions o) {
        int columns = o == null ? 3 : clamp(o.columns(), 1, 5, 3);
        int copies = o == null ? 1 : clamp(o.copies(), 1, 100, 1);
        String pageSize = o == null ? "A4" : o.pageSize();
        return new LabelOptions(columns, copies, pageSize);
    }

    private static int clamp(int v, int min, int max, int fallback) {
        if (v == 0) return fallback;
        return Math.max(min, Math.min(max, v));
    }

    private PdfPCell labelCell(PdfContentByte cb, LabelItem item, float contentWidth, float pad,
                              int columns, Font nameFont, Font subFont) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(pad);
        cell.setBorderColor(Color.LIGHT_GRAY);

        Paragraph name = centered(item.productName(), nameFont);
        name.setSpacingAfter(1);
        cell.addElement(name);

        if (notBlank(item.optionSummary())) {
            cell.addElement(centered(item.optionSummary(), subFont));
        }
        if (notBlank(item.variantSku())) {
            cell.addElement(centered(item.variantSku(), subFont));
        }
        if (notBlank(item.unitLabel())) {
            cell.addElement(centered(item.unitLabel(), nameFont));
        }

        Paragraph gap = new Paragraph(" ", subFont);
        gap.setLeading(4);
        cell.addElement(gap);

        Image barcode = barcodeImage(cb, item.barcode(), item.symbology(), columns);
        barcode.setAlignment(Image.ALIGN_CENTER);
        // Never wider than the column; scale down (not up) so small grids stay crisp.
        float maxHeight = columns >= 4 ? 42f : 55f;
        if (barcode.getScaledWidth() > contentWidth || barcode.getScaledHeight() > maxHeight) {
            barcode.scaleToFit(contentWidth, maxHeight);
        }
        cell.addElement(barcode);

        if (notBlank(item.priceLabel())) {
            Paragraph price = centered(item.priceLabel(), nameFont);
            price.setSpacingBefore(2);
            cell.addElement(price);
        }
        return cell;
    }

    private static Paragraph centered(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Maps our stored symbology to the matching OpenPDF barcode. Stored values are already
      * canonical (UPC-A is kept as a 13-digit EAN-13), but the 12-digit case is handled too. */
    private Image barcodeImage(PdfContentByte cb, String value, String symbology, int columns) {
        boolean dense = columns >= 4;
        switch (symbology) {
            case "EAN13": {
                BarcodeEAN b = new BarcodeEAN();
                b.setCodeType(Barcode.EAN13);
                b.setCode(value);
                tuneEan(b, dense);
                return b.createImageWithBarcode(cb, null, null);
            }
            case "UPC_A": {
                BarcodeEAN b = new BarcodeEAN();
                b.setCodeType(value.length() == 12 ? Barcode.UPCA : Barcode.EAN13);
                b.setCode(value);
                tuneEan(b, dense);
                return b.createImageWithBarcode(cb, null, null);
            }
            case "EAN8": {
                BarcodeEAN b = new BarcodeEAN();
                b.setCodeType(Barcode.EAN8);
                b.setCode(value);
                tuneEan(b, dense);
                return b.createImageWithBarcode(cb, null, null);
            }
            case "CODE128": {
                Barcode128 b = new Barcode128();
                b.setCode(value);
                if (dense) { b.setSize(5f); b.setBaseline(5f); b.setBarHeight(18f); }
                return b.createImageWithBarcode(cb, null, null);
            }
            case "CODE39": {
                Barcode39 b = new Barcode39();
                b.setCode(value);
                b.setStartStopText(false);
                if (dense) { b.setSize(5f); b.setBaseline(5f); b.setBarHeight(18f); }
                return b.createImageWithBarcode(cb, null, null);
            }
            default:
                throw new BadRequestException("Cannot render barcode of type " + symbology);
        }
    }

    private static void tuneEan(BarcodeEAN b, boolean dense) {
        if (dense) {
            b.setSize(5f);
            b.setBaseline(5f);
            b.setBarHeight(16f);
        }
    }
}
