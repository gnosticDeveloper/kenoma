package bime.services;

import bime.dto.BarcodeSymbology;
import common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarcodesTest {

    @Test
    void checkDigit_matchesKnownGtinExamples() {
        // ISBN-13 9780201379624, UPC-A 036000291452, EAN-8 96385074
        assertThat(Barcodes.checkDigit("978020137962")).isEqualTo(4);
        assertThat(Barcodes.checkDigit("03600029145")).isEqualTo(2);
        assertThat(Barcodes.checkDigit("9638507")).isEqualTo(4);
    }

    @Test
    void validate_acceptsWellFormedGtins() {
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.EAN13, "9780201379624")).doesNotThrowAnyException();
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.UPC_A, "036000291452")).doesNotThrowAnyException();
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.EAN8, "96385074")).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsBadCheckDigit() {
        assertThatThrownBy(() -> Barcodes.validate(BarcodeSymbology.EAN13, "9780201379625"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("check digit");
    }

    @Test
    void validate_rejectsWrongLengthAndNonDigits() {
        assertThatThrownBy(() -> Barcodes.validate(BarcodeSymbology.EAN13, "97802013796"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Barcodes.validate(BarcodeSymbology.EAN13, "97802013796X4"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void validate_freeformSymbologies() {
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.CODE128, "ABC-123/45")).doesNotThrowAnyException();
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.CODE39, "HELLO 123")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Barcodes.validate(BarcodeSymbology.CODE39, "lower"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalize_trimsAndUppercasesFreeform() {
        assertThat(Barcodes.normalize(BarcodeSymbology.EAN13, "  9780201379624 ")).isEqualTo("9780201379624");
        assertThat(Barcodes.normalize(BarcodeSymbology.CODE39, " abc-1 ")).isEqualTo("ABC-1");
        assertThatThrownBy(() -> Barcodes.normalize(BarcodeSymbology.EAN13, "   "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void issueEan13_buildsCheckDigitValidValueInRestrictedRange() {
        String issued = Barcodes.issueEan13(Barcodes.RESTRICTED_PREFIX, 1);
        assertThat(issued).isEqualTo("2000000000015").hasSize(13);
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.EAN13, issued)).doesNotThrowAnyException();
    }

    @Test
    void issueEan13_withGs1Prefix_startsWithIt() {
        String issued = Barcodes.issueEan13("5012345", 7);
        assertThat(issued).startsWith("5012345").hasSize(13);
        assertThatCode(() -> Barcodes.validate(BarcodeSymbology.EAN13, issued)).doesNotThrowAnyException();
    }

    @Test
    void issueEan13_rejectsPrefixLeavingNoRoom() {
        assertThatThrownBy(() -> Barcodes.issueEan13("123456789012", 1))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void issueEan13_rejectsSequenceBeyondCapacity() {
        assertThatThrownBy(() -> Barcodes.issueEan13("20", 10_000_000_000L))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void validateGs1Prefix_boundsAndCharset() {
        assertThatCode(() -> Barcodes.validateGs1Prefix("1234")).doesNotThrowAnyException();
        assertThatCode(() -> Barcodes.validateGs1Prefix("12345678901")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Barcodes.validateGs1Prefix("123")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Barcodes.validateGs1Prefix("123456789012")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Barcodes.validateGs1Prefix("12a4")).isInstanceOf(BadRequestException.class);
    }
}
