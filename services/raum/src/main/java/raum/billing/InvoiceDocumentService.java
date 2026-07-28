package raum.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import raum.exception.PricingConfigurationException;
import raum.models.BillingHistory;
import raum.models.Organization;

import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InvoiceDocumentService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    // Not injected: this app runs on Spring Boot 4's Jackson 3 (tools.jackson) autoconfiguration,
    // which doesn't provide a com.fasterxml.jackson.databind.ObjectMapper bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] generate(Organization org, BillingHistory history) {
        try {
            List<InvoiceLineItem> lineItems = objectMapper.readValue(
                    history.getLineItems().asString(), new TypeReference<List<InvoiceLineItem>>() {});

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            document.add(new Paragraph("Invoice", titleFont));
            document.add(new Paragraph(" "));
            document.add(new Paragraph(org.getFiscalName() != null ? org.getFiscalName() : org.getName()));
            if (org.getFiscalAddress() != null) {
                document.add(new Paragraph(org.getFiscalAddress()));
            }
            if (org.getTaxId() != null) {
                document.add(new Paragraph("Tax ID: " + org.getTaxId()));
            }
            document.add(new Paragraph("Due: " + DATE_FORMAT.format(history.getDueAt())));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(3);
            table.setWidthPercentage(100);
            table.addCell(headerCell("Item"));
            table.addCell(headerCell("Amount"));
            table.addCell(headerCell("Status"));
            for (InvoiceLineItem item : lineItems) {
                table.addCell(item.getLabel());
                table.addCell("%s %s".formatted(item.getCurrency(), item.getPrice()));
                table.addCell(item.isIncludedInBase() ? "Included in base" : "Billed");
            }
            document.add(table);

            document.add(new Paragraph(" "));
            Font totalFont = new Font(Font.HELVETICA, 14, Font.BOLD);
            document.add(new Paragraph("Total: %s %s".formatted(history.getCurrency(), history.getAmount()), totalFont));

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new PricingConfigurationException("Failed to generate invoice document: " + e.getMessage());
        }
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, new Font(Font.HELVETICA, 12, Font.BOLD)));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        return cell;
    }
}
