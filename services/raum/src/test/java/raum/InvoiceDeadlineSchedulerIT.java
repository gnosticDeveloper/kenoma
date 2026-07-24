package raum;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import raum.billing.InvoiceDeadlineScheduler;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the scheduler against a real Postgres instance rather than mocked
 * repositories — the mocked unit test (InvoiceDeadlineSchedulerTest) can't catch
 * timezone/column-type binding bugs since it never touches a real DB or R2DBC driver.
 */
class InvoiceDeadlineSchedulerIT extends BaseIT {

    @Autowired
    private InvoiceDeadlineScheduler scheduler;

    @Test
    void checkInvoiceDeadlines_pastDueOrgInUtc_getsBillingHistoryEntry() throws Exception {
        UUID id = UUID.fromString(firstLine(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                "-t", "-A", "-c",
                "INSERT INTO organizations (name, contact_name, contact_email, billing_email, " +
                        "billing_email_verified, billing_cycle, next_invoice_due_at) VALUES " +
                        "('Scheduler IT Org', 'Admin', 'sched-it@example.com', 'sched-it-billing@example.com', " +
                        "true, 'MONTHLY', current_timestamp - interval '1 hour') RETURNING id;")
                .getStdout()));

        scheduler.checkInvoiceDeadlines();

        int count = 0;
        for (int i = 0; i < 20; i++) {
            String result = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                            "SELECT count(*) FROM billing_history WHERE org_id = '%s';".formatted(id))
                    .getStdout().trim();
            count = Integer.parseInt(result);
            if (count > 0) break;
            Thread.sleep(250);
        }
        assertThat(count).isEqualTo(1);

        String nextDueAt = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                        "SELECT next_invoice_due_at > current_timestamp FROM organizations WHERE id = '%s';".formatted(id))
                .getStdout().trim();
        assertThat(nextDueAt).isEqualTo("t");
    }

    @Test
    void checkInvoiceDeadlines_notYetDueOrg_isIgnored() throws Exception {
        UUID id = UUID.fromString(firstLine(raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum",
                        "-t", "-A", "-c",
                        "INSERT INTO organizations (name, contact_name, contact_email, billing_email, " +
                                "billing_email_verified, billing_cycle, next_invoice_due_at) VALUES " +
                                "('Not Due IT Org', 'Admin', 'notdue-it@example.com', 'notdue-it-billing@example.com', " +
                                "true, 'MONTHLY', current_timestamp + interval '10 days') RETURNING id;")
                .getStdout()));

        scheduler.checkInvoiceDeadlines();

        Thread.sleep(1000);
        String count = raumDb.execInContainer("psql", "-U", "postgres", "-d", "raum", "-t", "-A", "-c",
                        "SELECT count(*) FROM billing_history WHERE org_id = '%s';".formatted(id))
                .getStdout().trim();
        assertThat(Integer.parseInt(count)).isEqualTo(0);
    }

    private static String firstLine(String output) {
        return output.strip().lines().findFirst().orElseThrow();
    }
}
