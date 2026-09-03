package bime.services;

import java.time.LocalDate;

/**
 * Minimal reader for GS1 element strings (the payload of a GS1-128, GS1 DataMatrix or GS1 QR
 * barcode). Perishable goods routinely carry one barcode encoding the GTIN (AI 01), the batch/lot
 * (AI 10) and the expiry date (AI 17) together, so a single scan at receiving can both identify the
 * product and record its batch.
 *
 * <p>Only the AIs Bime acts on are interpreted - 01, 10, 17. A handful of other common fixed and
 * variable AIs (11, 15, 21, 20, 30, 310n) are recognised only well enough to skip past them so the
 * fields after them still parse. Anything genuinely unparseable, or a plain GTIN with no AI
 * structure at all, yields {@code null} and the caller falls back to treating the scan as an
 * ordinary barcode value.
 */
final class Gs1Parser {

    private Gs1Parser() {}

    /** FNC1 in a transmitted GS1 element string: ASCII 29 (Group Separator). Terminates a
      * variable-length field that is not the last field in the string. */
    private static final char GS = 0x1D;

    record Gs1Scan(String gtin, String lot, LocalDate expiry) {
        boolean isEmpty() {
            return gtin == null && lot == null && expiry == null;
        }
    }

    /**
     * Parses {@code raw} as a GS1 element string. Returns {@code null} when it is not GS1-shaped
     * (e.g. a bare EAN-13) or when nothing usable could be extracted.
     */
    static Gs1Scan parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = stripSymbologyIdentifier(raw.trim());
        if (s.isEmpty() || !looksLikeElementString(s)) {
            return null;
        }

        String gtin = null;
        String lot = null;
        LocalDate expiry = null;

        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == GS) {
                i++;
                continue;
            }
            if (i + 2 > s.length()) {
                break;
            }
            String ai = s.substring(i, i + 2);
            i += 2;
            switch (ai) {
                case "01" -> {
                    if (i + 14 > s.length() || !isDigits(s, i, i + 14)) {
                        return null;
                    }
                    gtin = s.substring(i, i + 14);
                    i += 14;
                }
                case "17", "11", "15", "13", "16" -> {
                    if (i + 6 > s.length() || !isDigits(s, i, i + 6)) {
                        return null;
                    }
                    LocalDate date = parseYymmdd(s.substring(i, i + 6));
                    if (ai.equals("17")) {
                        expiry = date;
                    }
                    i += 6;
                }
                case "20" -> i = Math.min(s.length(), i + 2);
                case "10", "21" -> {
                    int end = s.indexOf(GS, i);
                    if (end < 0) {
                        end = s.length();
                    }
                    String value = s.substring(i, end);
                    if (ai.equals("10")) {
                        lot = value.isEmpty() ? null : value;
                    }
                    i = end;
                }
                default -> {
                    // An AI we do not model. Without knowing its length we cannot safely resume,
                    // so bail out and let whatever we already have stand.
                    i = s.length();
                }
            }
        }

        Gs1Scan scan = new Gs1Scan(gtin, lot, expiry);
        return scan.isEmpty() ? null : scan;
    }

    /** Drops a leading symbology identifier such as {@code ]C1} (GS1-128), {@code ]d2}
      * (GS1 DataMatrix), {@code ]Q3} (GS1 QR) or {@code ]e0} (GS1 DataBar). */
    private static String stripSymbologyIdentifier(String s) {
        if (s.length() >= 3 && s.charAt(0) == ']') {
            return s.substring(3);
        }
        return s;
    }

    /** True when the string carries AI structure rather than being a bare numeric GTIN/EAN. */
    private static boolean looksLikeElementString(String s) {
        if (s.indexOf(GS) >= 0) {
            return true;
        }
        if (s.startsWith("01") && s.length() > 16 && isDigits(s, 2, 16)) {
            return true;
        }
        return s.startsWith("10") || s.startsWith("17");
    }

    private static boolean isDigits(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** YYMMDD to a date. Per GS1, a day of {@code 00} on AI 17 means the last day of that month.
      * The two-digit year is taken as 2000-2099, which covers every date this system will see. */
    private static LocalDate parseYymmdd(String yymmdd) {
        int year = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day = Integer.parseInt(yymmdd.substring(4, 6));
        if (month < 1 || month > 12) {
            return null;
        }
        if (day == 0) {
            return LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        }
        return LocalDate.of(year, month, day);
    }
}
