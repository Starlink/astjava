package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.erfa.Cal2jd;
import uk.ac.starlink.ast.internal.erfa.Tf2d;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The Modified Julian Date of a FITS {@code DATE-OBS} string, as a FitsChan read uses it to supply
 * an MJD-OBS the header lacks.
 *
 * <p>Ported from the date helpers of Rust {@code fitschan/wcs_write/mod.rs} ({@code
 * date_str_to_mjd}, {@code parse_legacy_date}, {@code gregorian_to_mjd}); the strings accepted are
 * those of the {@code sscanf} patterns of C's {@code DateObs} (fitschan.c:8589).
 */
final class FitsDates {

    private FitsDates() {}

    /** The fields of a date C's patterns scanned: year, month, day, hour, minute, second. */
    private static final class Fields {
        int yy;
        int mm;
        int dd;
        int hr;
        int mn;
        int sc;

        /** The fractional seconds field, or zero if there is none. */
        int fsc;

        /** The number of characters the fractional seconds field occupies. */
        int fscWidth;
    }

    /**
     * The UTC Modified Julian Date of a FITS DATE-OBS string, or null if it is not one (C's {@code
     * DateObs}, fitschan.c:8589, returning AST__BAD).
     *
     * <p>Accepted, with optional white space before and after, are the old form {@code dd/mm/yy}
     * and the forms {@code ccyy-mm-dd} and {@code ccyy-mm-ddThh:mm:ss[.s...][Z]}, each field read as
     * C's {@code %2d}, {@code %4d} or {@code %d} reads it. The date and time must be valid for
     * {@code palCaldj} and {@code palDtf2d}.
     */
    static Double dateStrToMjd(String s) {
        Fields f = parseLegacyDate(s);
        if (f == null) {
            f = parseIsoDate(s);
        }
        return f == null ? null : gregorianToMjd(f);
    }

    /**
     * The fields of the old form {@code dd/mm/yy}, scanned with C's {@code " %2d/%2d/%d %n"}
     * consuming the whole string (fitschan.c:8662), or null; the time fields are zero.
     */
    private static Fields parseLegacyDate(String s) {
        Scanner sc = new Scanner(s);
        Integer dd = sc.decimal(2);
        Integer mm = dd == null || !sc.literal('/') ? null : sc.decimal(2);
        Integer yy = mm == null || !sc.literal('/') ? null : sc.decimal(0);
        if (yy == null || !sc.atEnd()) {
            return null;
        }
        Fields f = new Fields();
        f.dd = dd;
        f.mm = mm;
        f.yy = yy;
        return f;
    }

    /**
     * The fields of an ISO-8601 date, scanned with the five patterns C's {@code DateObs} tries after
     * the old form, in its order (fitschan.c:8672-8710), or null if none matches the whole string.
     */
    private static Fields parseIsoDate(String s) {
        // " %4d-%2d-%2d %n"
        Scanner sc = new Scanner(s);
        Fields f = scanDate(sc);
        if (f != null && sc.atEnd()) {
            return f;
        }
        // " %4d-%2d-%2dT%2d:%2d:%2d %n"
        sc = new Scanner(s);
        f = scanDateTime(sc);
        if (f != null && sc.atEnd()) {
            return f;
        }
        // " %4d-%2d-%2dT%2d:%2d:%2d.%n%d%n %n"
        sc = new Scanner(s);
        f = scanDateTime(sc);
        if (f != null && scanFraction(sc, f) && sc.atEnd()) {
            return f;
        }
        // " %4d-%2d-%2dT%2d:%2d:%2dZ %n"
        sc = new Scanner(s);
        f = scanDateTime(sc);
        if (f != null && sc.literal('Z') && sc.atEnd()) {
            return f;
        }
        // " %4d-%2d-%2dT%2d:%2d:%2d.%n%d%nZ %n"
        sc = new Scanner(s);
        f = scanDateTime(sc);
        if (f != null && scanFraction(sc, f) && sc.literal('Z') && sc.atEnd()) {
            return f;
        }
        return null;
    }

    /** C's {@code " %4d-%2d-%2d"}: the date fields, or null. */
    private static Fields scanDate(Scanner sc) {
        Integer yy = sc.decimal(4);
        Integer mm = yy == null || !sc.literal('-') ? null : sc.decimal(2);
        Integer dd = mm == null || !sc.literal('-') ? null : sc.decimal(2);
        if (dd == null) {
            return null;
        }
        Fields f = new Fields();
        f.yy = yy;
        f.mm = mm;
        f.dd = dd;
        return f;
    }

    /** C's {@code " %4d-%2d-%2dT%2d:%2d:%2d"}: the date and time fields, or null. */
    private static Fields scanDateTime(Scanner sc) {
        Fields f = scanDate(sc);
        Integer hr = f == null || !sc.literal('T') ? null : sc.decimal(2);
        Integer mn = hr == null || !sc.literal(':') ? null : sc.decimal(2);
        Integer sec = mn == null || !sc.literal(':') ? null : sc.decimal(2);
        if (sec == null) {
            return null;
        }
        f.hr = hr;
        f.mn = mn;
        f.sc = sec;
        return f;
    }

    /**
     * C's {@code ".%n%d%n"}: the fractional seconds field and the number of characters between the
     * two {@code %n} markers, which include any white space and sign {@code %d} reads; false if the
     * field is absent.
     */
    private static boolean scanFraction(Scanner sc, Fields f) {
        if (!sc.literal('.')) {
            return false;
        }
        int start = sc.pos;
        Integer fsc = sc.decimal(0);
        if (fsc == null) {
            return false;
        }
        f.fsc = fsc;
        f.fscWidth = sc.pos - start;
        return true;
    }

    /**
     * The MJD of the scanned fields, following the arithmetic of C's {@code DateObs}
     * (fitschan.c:8717-8745) exactly: {@code palCaldj} gives the MJD of the start of the day, the
     * seconds are {@code fsc / 10^width + sc}, and {@code palDtf2d} gives the fraction of the day
     * added to it. Null when either fails.
     */
    private static Double gregorianToMjd(Fields f) {
        // palCaldj: a year of 0 to 49 is 2000 to 2049, and 50 to 99 is 1950 to 1999; any other
        // year is taken as it is.
        int year = f.yy;
        if (year >= 0 && year <= 49) {
            year += 2000;
        } else if (year >= 50 && year <= 99) {
            year += 1900;
        }
        Cal2jd.Result day = Pal.cldj(year, f.mm, f.dd);
        if (day.error != null || day.warning != null) {
            return null;
        }
        double secs = 0.0;
        if (f.fsc > 0) {
            secs = f.fsc / Math.pow(10.0, f.fscWidth);
        }
        secs += f.sc;
        Tf2d.Result frac = Pal.dtf2d(f.hr, f.mn, secs);
        if (frac.warning != null) {
            return null;
        }
        return day.djm + frac.days;
    }

    /** A cursor over a string reading it as C's {@code sscanf} reads the directives used here. */
    private static final class Scanner {
        private final String s;
        private int pos;

        Scanner(String s) {
            this.s = s;
        }

        /** White space in a format: skips any white space. */
        private void skipSpace() {
            while (pos < s.length() && CScan.isCSpace(s.charAt(pos))) {
                pos++;
            }
        }

        /** A literal character in a format: whether the next character is {@code c}, which is passed. */
        boolean literal(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        /** A format ending {@code " %n"}: skips white space and says whether the string is used up. */
        boolean atEnd() {
            skipSpace();
            return pos >= s.length();
        }

        /**
         * {@code %<width>d}, or {@code %d} for a width of zero: skips white space, then reads an optional
         * sign and the digits after it, at most {@code width} characters in all; null if there is
         * no digit.
         *
         * <p>C leaves the value of a field too large for an {@code int} undefined; here it is what
         * the C libraries of 64-bit platforms give, the 64-bit value they convert (saturating at
         * its range, as {@code strtol} does) narrowed to an {@code int}.
         */
        Integer decimal(int width) {
            skipSpace();
            int limit = width > 0 ? Math.min(s.length(), pos + width) : s.length();
            int k = pos;
            boolean neg = false;
            if (k < limit && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                neg = s.charAt(k) == '-';
                k++;
            }
            int first = k;
            long v = 0;
            boolean saturated = false;
            while (k < limit && s.charAt(k) >= '0' && s.charAt(k) <= '9') {
                int d = s.charAt(k) - '0';
                if (saturated || v > (Long.MAX_VALUE - d) / 10) {
                    saturated = true;
                } else {
                    v = v * 10 + d;
                }
                k++;
            }
            if (k == first) {
                return null;
            }
            pos = k;
            long value;
            if (saturated) {
                value = neg ? Long.MIN_VALUE : Long.MAX_VALUE;
            } else {
                value = neg ? -v : v;
            }
            return (int) value;
        }
    }
}
