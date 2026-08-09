package bime.services;

import bime.db.BimeDbHandle;
import common.mail.MailgunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockAlertCheckServiceTest {

    @Mock
    private MailgunService mailgunService;
    @Mock
    private DatabaseClient client;

    private StockAlertCheckService service;

    private static final UUID ORG_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StockAlertCheckService(mailgunService);
    }

    @Test
    void checkOrg_sendsEmailWithVariantSkuLabelWhenPresent() {
        Map<String, Object> row = triggeredRow("Widget", "PROD-SKU", "VAR-SKU", "Main WH", "alerts@example.com", true, 10, 3);
        stubClient(0L, List.of(row));
        when(mailgunService.sendStockAlertEmail(anyString(), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.checkOrg(ORG_ID, mockHandle())).verifyComplete();

        verify(mailgunService).sendStockAlertEmail(
                eq("alerts@example.com"), eq("Widget (VAR-SKU)"), eq("Main WH"), eq(3), eq(10), any());
    }

    @Test
    void checkOrg_fallsBackToProductSku_whenVariantHasNoOwnSku() {
        Map<String, Object> row = triggeredRow("Widget", "PROD-SKU", null, "Main WH", "alerts@example.com", true, 10, 3);
        stubClient(0L, List.of(row));
        when(mailgunService.sendStockAlertEmail(anyString(), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.checkOrg(ORG_ID, mockHandle())).verifyComplete();

        verify(mailgunService).sendStockAlertEmail(
                anyString(), eq("Widget (PROD-SKU)"), anyString(), anyInt(), anyInt(), any());
    }

    // Adversarial: a location with no notification_email must not crash the whole check,
    // and must not attempt to email a null/blank address.
    @Test
    void checkOrg_skipsEmail_whenLocationHasNoNotificationEmail() {
        Map<String, Object> row = triggeredRow("Widget", "PROD-SKU", "VAR-SKU", "Main WH", null, true, 10, 3);
        stubClient(0L, List.of(row));

        StepVerifier.create(service.checkOrg(ORG_ID, mockHandle())).verifyComplete();

        verify(mailgunService, never()).sendStockAlertEmail(any(), any(), any(), anyInt(), anyInt(), any());
    }

    // Adversarial: an unverified notification_email must not receive stock alert emails, even if
    // it's otherwise present - the location owner never confirmed they control that address.
    @Test
    void checkOrg_skipsEmail_whenNotificationEmailNotVerified() {
        Map<String, Object> row = triggeredRow("Widget", "PROD-SKU", "VAR-SKU", "Main WH", "unverified@example.com", false, 10, 3);
        stubClient(0L, List.of(row));

        StepVerifier.create(service.checkOrg(ORG_ID, mockHandle())).verifyComplete();

        verify(mailgunService, never()).sendStockAlertEmail(any(), any(), any(), anyInt(), anyInt(), any());
    }

    // Adversarial: one row's email send failing must not prevent the next row's email from
    // being attempted — a single Mailgun outage/rejection shouldn't blank out the whole tick.
    @Test
    void checkOrg_isolatesEmailFailureToOneRow() {
        Map<String, Object> failingRow = triggeredRow("Failing", "F-SKU", null, "WH-1", "fails@example.com", true, 10, 1);
        Map<String, Object> healthyRow = triggeredRow("Healthy", "H-SKU", null, "WH-2", "ok@example.com", true, 10, 1);
        stubClient(0L, List.of(failingRow, healthyRow));
        when(mailgunService.sendStockAlertEmail(eq("fails@example.com"), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Mono.error(new RuntimeException("mailgun down")));
        when(mailgunService.sendStockAlertEmail(eq("ok@example.com"), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.checkOrg(ORG_ID, mockHandle())).verifyComplete();

        verify(mailgunService).sendStockAlertEmail(eq("ok@example.com"), anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void checkOrg_noTriggeredRows_neverCallsMailgun() {
        stubClient(2L, List.of());

        StepVerifier.create(service.checkOrg(ORG_ID, mockHandle())).verifyComplete();

        verifyNoInteractions(mailgunService);
    }

    private Map<String, Object> triggeredRow(String productName, String productSku, String variantSku,
                                              String locationName, String notificationEmail, boolean notificationEmailVerified,
                                              int threshold, int quantity) {
        Map<String, Object> row = new HashMap<>();
        row.put("product_name", productName);
        row.put("product_sku", productSku);
        row.put("variant_sku", variantSku);
        row.put("location_name", locationName);
        row.put("notification_email", notificationEmail);
        row.put("notification_email_verified", notificationEmailVerified);
        row.put("threshold", threshold);
        row.put("quantity", quantity);
        return row;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubClient(long recoveredRows, List<Map<String, Object>> triggeredRows) {
        DatabaseClient.GenericExecuteSpec deleteSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec deleteFetch = mock(FetchSpec.class);
        when(deleteSpec.bind(anyString(), any())).thenReturn(deleteSpec);
        when(deleteSpec.fetch()).thenReturn(deleteFetch);
        when(deleteFetch.rowsUpdated()).thenReturn(Mono.just(recoveredRows));

        DatabaseClient.GenericExecuteSpec triggerSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec triggerFetch = mock(FetchSpec.class);
        when(triggerSpec.bind(anyString(), any())).thenReturn(triggerSpec);
        when(triggerSpec.fetch()).thenReturn(triggerFetch);
        when(triggerFetch.all()).thenReturn(Flux.fromIterable(triggeredRows));

        when(client.sql(anyString())).thenReturn(deleteSpec).thenReturn(triggerSpec);
    }

    private BimeDbHandle mockHandle() {
        TransactionalOperator tx = mock(TransactionalOperator.class);
        return new BimeDbHandle(client, tx);
    }
}
