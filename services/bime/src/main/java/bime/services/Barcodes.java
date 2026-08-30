package bime.services;

import bime.dto.BarcodeSymbology;
import common.exception.BadRequestException;

/** Barcode value handling: normalization, symbology-specific validation, GTIN check-digit maths, and
  * issuance of internal EAN-13 codes.
  *
  * <p>EAN-13, UPC-A and EAN-8 all carry a GTIN whose last digit is a mod-10 checksum of the rest;
  * that lets a typo or misread be rejected instead of resolving to the wrong item. UPC-A is a
  * 12-digit GTIN - the same number as an EAN-13 with a leading zero - so it is normalized to 13
  * digits and thereafter treated exactly like EAN-13. CODE128 and CODE39 carry an arbitrary string
  * with no GTIN semantics and no universal checksum, so they are only sanity-checked for charset and
  * length and stored opaquely. */
final class Barcodes {

    private Barcodes() {}

    /** GS1 restricted-distribution range: EAN-13 numbers starting "20" are reserved for in-store use
      * and are never centrally registered, so they are always safe to mint without a GS1 membership. */
    static final String RESTRICTED_PREFIX = "20";

    private static final int GS1_PREFIX_MIN = 4;
    private static final int GS1_PREFIX_MAX = 11;

    /** Trims surrounding whitespace and, for the alphanumeric symbologies, upper-cases the value
      * (CODE39's alphabet is upper-case only). Rejects a blank value. */
    static String normalize(BarcodeSymbology symbology, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("barcode is required");
        }
        String trimmed = raw.trim();
        return (symbology == BarcodeSymbology.CODE39 || symbology == BarcodeSymbology.CODE128)
                ? trimmed.toUpperCase()
                : trimmed;
    }

    /** Validates an already-normalized barcode against its declared symbology, throwing
      * {@link BadRequestException} with a specific message on any mismatch. */
    static void validate(BarcodeSymbology symbology, String value) {
        if (symbology == null) {
            throw new BadRequestException("symbology is required");
        }
        switch (symbology) {
            case EAN13 -> validateGtin(value, 13);
            case UPC_A -> validateGtin(value, 12);
            case EAN8 -> validateGtin(value, 8);
            case CODE128 -> validateFreeform(value, "CODE128", (char) 32, (char) 126);
            case CODE39 -> validateCode39(value);
        }
    }

    private static void validateGtin(String value, int length) {
        if (!value.chars().allMatch(Character::isDigit)) {
            throw new BadRequestException("a " + describeGtin(length) + " barcode must be digits only");
        }
        if (value.length() != length) {
            throw new BadRequestException("a " + describeGtin(length) + " barcode must be exactly " + length + " digits");
        }
        int expected = checkDigit(value.substring(0, length - 1));
        int actual = value.charAt(length - 1) - '0';
        if (expected != actual) {
            throw new BadRequestException("barcode check digit is invalid (expected " + expected + ", got " + actual
                    + ") - the value was mistyped or misread");
        }
    }

    private static String describeGtin(int length) {
        return switch (length) {
            case 13 -> "EAN-13";
            case 12 -> "UPC-A";
            case 8 -> "EAN-8";
            default -> length + "-digit";
        };
    }

    private static void validateFreeform(String value, String label, char min, char max) {
        if (value.length() > 64) {
            throw new BadRequestException("a " + label + " barcode must be at most 64 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < min || c > max) {
                throw new BadRequestException("a " + label + " barcode contains an unsupported character");
            }
        }
    }

    private static void validateCode39(String value) {
        if (value.length() > 64) {
            throw new BadRequestException("a CODE39 barcode must be at most 64 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == ' ' || c == '$' || c == '/' || c == '+' || c == '%';
            if (!ok) {
                throw new BadRequestException("a CODE39 barcode may only contain A-Z, 0-9 and - . space $ / + %");
            }
        }
    }

    /** Mod-10 GTIN check digit for the given leading digits (everything before the check position).
      * From the rightmost digit leftward the weights alternate 3, 1, 3, 1 ...; the check digit is
      * whatever makes the weighted sum a multiple of 10. */
    static int checkDigit(String leadingDigits) {
        int sum = 0;
        for (int i = 0; i < leadingDigits.length(); i++) {
            int digit = leadingDigits.charAt(leadingDigits.length() - 1 - i) - '0';
            sum += (i % 2 == 0) ? digit * 3 : digit;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** Validates a GS1 company prefix supplied by an org: digits only, {@value #GS1_PREFIX_MIN} to
      * {@value #GS1_PREFIX_MAX} long (leaving room for at least one item-reference digit within the
      * 12-digit EAN-13 body). */
    static void validateGs1Prefix(String prefix) {
        if (!prefix.chars().allMatch(Character::isDigit)) {
            throw new BadRequestException("gs1Prefix must be digits only");
        }
        if (prefix.length() < GS1_PREFIX_MIN || prefix.length() > GS1_PREFIX_MAX) {
            throw new BadRequestException("gs1Prefix must be between " + GS1_PREFIX_MIN + " and " + GS1_PREFIX_MAX + " digits");
        }
    }

    /** Builds a 13-digit EAN-13 from a body prefix (the GS1 company prefix, or {@link #RESTRICTED_PREFIX})
      * and a sequential item reference: prefix + zero-padded sequence filling the 12-digit body, then
      * the computed check digit. Throws if the sequence no longer fits the space the prefix leaves. */
    static String issueEan13(String bodyPrefix, long sequence) {
        int refWidth = 12 - bodyPrefix.length();
        if (refWidth < 1) {
            throw new BadRequestException("gs1Prefix leaves no room for an item reference");
        }
        long capacity = (long) Math.pow(10, refWidth);
        if (sequence < 0 || sequence >= capacity) {
            throw new BadRequestException("barcode issuance space is exhausted for the configured prefix");
        }
        String body = bodyPrefix + String.format("%0" + refWidth + "d", sequence);
        return body + checkDigit(body);
    }
}
