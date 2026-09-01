package bime.services;

import bime.services.Gs1Parser.Gs1Scan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class Gs1ParserTest {

    /** FNC1 / Group Separator, as a scanner transmits it between variable-length fields. */
    private static final String GS = "\u001d";

    @Test
    void parsesGtinLotAndExpiry_concatenated() {
        Gs1Scan scan = Gs1Parser.parse("0109506000134352" + "17261231" + "10LOT-A");
        assertThat(scan).isNotNull();
        assertThat(scan.gtin()).isEqualTo("09506000134352");
        assertThat(scan.expiry()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(scan.lot()).isEqualTo("LOT-A");
    }

    @Test
    void stripsSymbologyIdentifier() {
        Gs1Scan scan = Gs1Parser.parse("]C1" + "0109506000134352" + "10LOT-A");
        assertThat(scan).isNotNull();
        assertThat(scan.gtin()).isEqualTo("09506000134352");
        assertThat(scan.lot()).isEqualTo("LOT-A");
    }

    @Test
    void variableLotTerminatedByGroupSeparator() {
        Gs1Scan scan = Gs1Parser.parse("10LOT-17" + GS + "17260630");
        assertThat(scan).isNotNull();
        assertThat(scan.lot()).isEqualTo("LOT-17");
        assertThat(scan.expiry()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void dayZeroMeansEndOfMonth() {
        Gs1Scan scan = Gs1Parser.parse("17260200" + "10L1");
        assertThat(scan).isNotNull();
        assertThat(scan.expiry()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(scan.lot()).isEqualTo("L1");
    }

    @Test
    void plainEan13IsNotGs1() {
        assertThat(Gs1Parser.parse("7501234567890")).isNull();
    }

    @Test
    void blankOrNullIsNull() {
        assertThat(Gs1Parser.parse(null)).isNull();
        assertThat(Gs1Parser.parse("   ")).isNull();
    }

    @Test
    void lotOnlyScanParses() {
        Gs1Scan scan = Gs1Parser.parse("10BATCH99");
        assertThat(scan).isNotNull();
        assertThat(scan.lot()).isEqualTo("BATCH99");
        assertThat(scan.gtin()).isNull();
    }
}
