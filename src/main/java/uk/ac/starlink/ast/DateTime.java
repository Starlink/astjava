package uk.ac.starlink.ast;

import java.util.Locale;
import uk.ac.starlink.ast.internal.erfa.Cal2jd;
import uk.ac.starlink.ast.internal.erfa.Tf2d;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * Reading and writing dates for the Epoch attribute: C's {@code astReadDateTime} and {@code
 * astFmtDecimalYr} (frame.c).
 *
 * <p>Ported from C {@code frame.c}, with Rust {@code frames/date_time.rs}. The patterns are C's
 * {@code sscanf} formats, matched as C matches them: a space in a format skips any white space, a
 * numeric conversion skips white space before the number, a {@code %[...]} conversion does not,
 * and a field width counts the sign.
 */
final class DateTime {

    private DateTime() {}

    /**
     * The Modified Julian Date of a year given as a number: Besselian before 1984 and Julian from
     * then on, as C's {@code astReadDateTime} and {@code astLoadFrame} read a bare year.
     */
    static double yearToMjd(double year) {
        return year < 1984.0 ? Pal.epb2d(year) : Pal.epj2d(year);
    }

    /**
     * C's {@code astFmtDecimalYr} (frame.c): a year written with {@code digits} significant
     * digits (at most 17), trailing zeros removed but at least one decimal place kept.
     */
    static String fmtDecimalYr(double year, int digits) {
        int d = Math.min(digits, 17);
        StringBuilder s = new StringBuilder(CFormat.format("%#." + d + "g", year));
        int nc = s.length();
        while (nc > 0 && s.charAt(nc - 1) == '0') {
            nc--;
        }
        s.setLength(nc);
        if (nc > 0 && s.charAt(nc - 1) == '.') {
            s.append('0');
        }
        return s.toString();
    }

    /**
     * Reads a date and time and returns the Modified Julian Date, as C's {@code astReadDateTime}
     * (frame.c) does. The forms accepted, the whole string being used, are: a Besselian epoch
     * ({@code B1950}); a Julian epoch ({@code J2000}); a year as a number, Besselian before 1984 and
     * Julian from then on; a Modified Julian Date ({@code MJD 54321.5}); a Julian Date ({@code JD
     * 2454321.5}); and a Gregorian calendar date with an optional time ({@code 1996-Oct-2
     * 12:13:56.985}), with the month a number or a three-letter name, the fields separated by
     * {@code -}, {@code /}, {@code :} or a space, an optional {@code T} before the time, and the
     * fraction of the day as a decimal day or as hours, minutes and seconds. A year of 0 to 49 is
     * taken as 2000 to 2049, and 50 to 99 as 1950 to 1999 ({@code palCaldj}). The date is not tied
     * to a time scale: a day has 86400 seconds.
     *
     * @throws AstException with status {@code AST__DTERR} if the string is not a date in one of
     *     these forms, or a field is out of range
     */
    static double readDateTime(String value) {
        Double v = epoch(value, "Bb");
        if (v != null) {
            return Pal.epb2d(v);
        }
        v = epoch(value, "Jj");
        if (v != null) {
            return Pal.epj2d(v);
        }
        v = CScan.scanDouble(value);
        if (v != null) {
            return yearToMjd(v);
        }
        v = epoch(value, "Mm", "Jj", "Dd");
        if (v != null) {
            return v;
        }
        v = epoch(value, "Jj", "Dd");
        if (v != null) {
            return v - 2400000.5;
        }
        return gregorian(value);
    }

    /**
     * {@code sscanf(value, " %*1[<set>] ... %lf %n")} using the whole string: one character from
     * each set in turn, with white space allowed before each, then a number. Null if it does not
     * match.
     */
    private static Double epoch(String value, String... sets) {
        Scan s = new Scan(value);
        for (String set : sets) {
            s.ws();
            if (s.set(set, 1) == null) {
                return null;
            }
        }
        s.ws();
        Double d = s.dbl();
        if (d == null) {
            return null;
        }
        s.ws();
        return s.atEnd() ? d : null;
    }

    private static AstException dterr(String message) {
        return Ast.err(AstException.AST__DTERR, message);
    }

    /** The Gregorian calendar form of {@link #readDateTime}. */
    private static double gregorian(String value) {
        // The year and month, by the first of four patterns that reads all four fields.
        int[] year = new int[1];
        int[] month = new int[1];
        String[] cmonth = {""};
        String[] seps = new String[2];
        int nc = yearMonth(value, year, month, cmonth, seps);
        boolean match = nc >= 0 && seps[0].equals(seps[1]);
        double day = 0.0;
        int hour = 0;
        int minute = 0;
        double sec = 0.0;
        if (match) {
            String v = value.substring(nc);
            // ISO 8601 puts a "T" between the date and the time.
            int t = v.indexOf('T');
            if (t >= 0) {
                v = v.substring(0, t) + " " + v.substring(t + 1);
            }
            int l = v.length();
            if (isNumber(v)) {
                Scan s = new Scan(v);
                s.ws();
                Double d = s.dbl();
                s.ws();
                match = d != null && s.pos == l;
                if (match) {
                    day = d;
                }
            } else {
                int[] fields = new int[3];
                String[] tseps = new String[2];
                int hnc = dayHourMinute(v, fields, tseps, true);
                match = hnc >= 0 && tseps[0].equals(tseps[1]);
                if (match) {
                    day = fields[0];
                    hour = fields[1];
                    minute = fields[2];
                    String rest = v.substring(hnc);
                    match = isNumber(rest);
                    if (match) {
                        Scan s = new Scan(rest);
                        s.ws();
                        Double d = s.dbl();
                        s.ws();
                        match = d != null && s.pos == rest.length();
                        if (match) {
                            sec = d;
                        }
                    }
                } else {
                    match = dayHourMinute(v, fields, tseps, false) == l;
                    if (match) {
                        day = fields[0];
                        hour = fields[1];
                        minute = fields[2];
                    }
                }
            }
        }
        if (!match) {
            throw dterr("Date/time does not have the correct form.");
        }

        if (!cmonth[0].isEmpty()) {
            String table = "jan feb mar apr may jun jul aug sep oct nov dec";
            int at = table.indexOf(cmonth[0].toLowerCase(Locale.ROOT));
            if (at < 0) {
                throw dterr("Month value \"" + cmonth[0] + "\" is invalid.");
            }
            month[0] = 1 + at / 4;
        }

        int iday = (int) day;
        double mjd = caldj(year[0], month[0], iday);
        mjd += day - iday;
        Tf2d.Result hms = Pal.dtf2d(hour, minute, sec);
        if (hms.warning != null) {
            switch (hms.warning) {
                case HOUR:
                    throw dterr("Hour value (" + hour + ") is invalid.");
                case MINUTE:
                    throw dterr("Minute value (" + minute + ") is invalid.");
                case SECOND:
                    throw dterr("Seconds value (" + CFormat.format("%.17g", sec) + ") is invalid.");
                default:
                    throw Ast.err(AstException.AST__INTER, "astReadDateTime: unexpected warning " + hms.warning + ".");
            }
        }
        return mjd + hms.days;
    }

    /**
     * {@code palCaldj}: the Modified Julian Date of a Gregorian calendar date, with a year of 0
     * to 49 taken as 2000 to 2049 and 50 to 99 as 1950 to 1999.
     *
     * <p>{@code palCaldj} returns ERFA's negative status codes, which C's {@code astReadDateTime}
     * tests for 1, 2 and 3 and so never sees: a day that is not in its month gives the date the
     * arithmetic gives, as here. A year before 4800 BC or a month outside 1 to 12 leaves C's date
     * unset; here it is the error C's message intends.
     */
    private static double caldj(int iy, int im, int id) {
        int adj = 0;
        if (iy >= 0 && iy <= 49) {
            adj = 2000;
        } else if (iy >= 50 && iy <= 99) {
            adj = 1900;
        }
        int y = iy + adj;
        Cal2jd.Result r = Pal.cldj(y, im, id);
        if (r.error != null) {
            switch (r.error) {
                case BAD_YEAR:
                    throw dterr("Year value (" + y + ") is invalid.");
                case BAD_MONTH:
                    throw dterr("Month value (" + im + ") is invalid.");
                default:
                    throw Ast.err(AstException.AST__INTER, "astReadDateTime: unexpected error " + r.error + ".");
            }
        }
        return r.djm;
    }

    /**
     * The year and month at the start of {@code v}, by the first of C's four patterns that reads
     * all four of its fields: {@code " %d %1[:/-] %2d %1[:/-]%n"}, {@code " %d%1[ ] %2d%1[ ]%n"},
     * and the two with a three-letter month name, {@code %3[A-Za-z]}, for {@code %2d}.
     *
     * @return the number of characters read, or -1 if no pattern matches
     */
    private static int yearMonth(String v, int[] year, int[] month, String[] cmonth, String[] seps) {
        for (int pattern = 0; pattern < 4; pattern++) {
            int nc = yearMonth(v, pattern >= 2, pattern % 2 == 1, year, month, cmonth, seps);
            if (nc >= 0) {
                return nc;
            }
        }
        return -1;
    }

    /**
     * One of the patterns of {@link #yearMonth(String, int[], int[], String[], String[])}: with
     * the month a name if {@code named}, and the separators single spaces if {@code spaces}.
     */
    private static int yearMonth(
            String v, boolean named, boolean spaces, int[] year, int[] month, String[] cmonth, String[] seps) {
        Scan s = new Scan(v);
        s.ws();
        Long y = s.integer(Integer.MAX_VALUE);
        if (y == null) {
            return -1;
        }
        if (!spaces) {
            s.ws();
        }
        String sep1 = s.set(spaces ? " " : ":/-", 1);
        if (sep1 == null) {
            return -1;
        }
        s.ws();
        Long mon = null;
        String name = null;
        if (named) {
            name = s.letters(3);
            if (name == null) {
                return -1;
            }
        } else {
            mon = s.integer(2);
            if (mon == null) {
                return -1;
            }
        }
        if (!spaces) {
            s.ws();
        }
        String sep2 = s.set(spaces ? " " : ":/-", 1);
        if (sep2 == null) {
            return -1;
        }
        year[0] = (int) (long) y;
        if (named) {
            cmonth[0] = name;
        } else {
            month[0] = (int) (long) mon;
        }
        seps[0] = sep1;
        seps[1] = sep2;
        return s.pos;
    }

    /**
     * The day, hour and minute at the start of {@code v}, by the first of two patterns that reads
     * all its fields: with {@code withSeconds}, {@code " %2d%*1[ ] %2d %1[:/-] %2d %1[:/-]%n"} or
     * {@code " %2d%*1[ ] %2d%1[ ] %2d%1[ ]%n"}, which end before a seconds field; otherwise
     * {@code " %2d%*1[ ] %2d %1[:/-] %2d %n"} or {@code " %2d%*1[ ] %2d%1[ ] %2d %n"}, which must
     * read all of {@code v}.
     *
     * @return the number of characters read, or -1 if neither pattern matches
     */
    private static int dayHourMinute(String v, int[] fields, String[] seps, boolean withSeconds) {
        for (int pattern = 0; pattern < 2; pattern++) {
            int nc = dayHourMinute(v, pattern == 1, fields, seps, withSeconds);
            if (nc >= 0) {
                return nc;
            }
        }
        return -1;
    }

    /**
     * One of the patterns of {@link #dayHourMinute(String, int[], String[], boolean)}: with the
     * separators single spaces if {@code spaces}.
     */
    private static int dayHourMinute(String v, boolean spaces, int[] fields, String[] seps, boolean withSeconds) {
        String sepSet = spaces ? " " : ":/-";
        Scan s = new Scan(v);
        s.ws();
        Long day = s.integer(2);
        if (day == null || s.set(" ", 1) == null) {
            return -1;
        }
        s.ws();
        Long hour = s.integer(2);
        if (hour == null) {
            return -1;
        }
        if (!spaces) {
            s.ws();
        }
        String sep3 = s.set(sepSet, 1);
        if (sep3 == null) {
            return -1;
        }
        s.ws();
        Long minute = s.integer(2);
        if (minute == null) {
            return -1;
        }
        String sep4 = "";
        if (withSeconds) {
            if (!spaces) {
                s.ws();
            }
            sep4 = s.set(sepSet, 1);
            if (sep4 == null) {
                return -1;
            }
        } else {
            s.ws();
            if (!s.atEnd()) {
                return -1;
            }
        }
        fields[0] = (int) (long) day;
        fields[1] = (int) (long) hour;
        fields[2] = (int) (long) minute;
        seps[0] = sep3;
        seps[1] = sep4;
        return s.pos;
    }

    /**
     * Whether all of {@code v} matches one of C's three patterns for a day or seconds field,
     * {@code " %*2[0-9].%*[0-9] %n"}, {@code " %*2[0-9]. %n"} or {@code " %*2[0-9] %n"}: one or
     * two digits, then optionally a point and any further digits, with white space around.
     */
    private static boolean isNumber(String v) {
        Scan s = new Scan(v);
        s.ws();
        if (s.set("0123456789", 2) == null) {
            return false;
        }
        int afterDigits = s.pos;
        // " %*2[0-9].%*[0-9] %n"
        if (s.set(".", 1) != null && s.set("0123456789", Integer.MAX_VALUE) != null) {
            s.ws();
            if (s.atEnd()) {
                return true;
            }
        }
        // " %*2[0-9]. %n"
        s.pos = afterDigits;
        if (s.set(".", 1) != null) {
            s.ws();
            if (s.atEnd()) {
                return true;
            }
        }
        // " %*2[0-9] %n"
        s.pos = afterDigits;
        s.ws();
        return s.atEnd();
    }

    /** A position in a string being read as C's {@code sscanf} reads it. */
    private static final class Scan {
        final String s;
        int pos;

        Scan(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        /** A space in a format: skips any white space. */
        void ws() {
            while (pos < s.length() && CScan.isCSpace(s.charAt(pos))) {
                pos++;
            }
        }

        /** {@code %<width>[set]}: one or more, at most {@code width}, characters from {@code set}. */
        String set(String set, int width) {
            int start = pos;
            while (pos < s.length() && pos - start < width && set.indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            return pos > start ? s.substring(start, pos) : null;
        }

        /** {@code %<width>[A-Za-z]}. */
        String letters(int width) {
            int start = pos;
            while (pos < s.length() && pos - start < width && isLetter(s.charAt(pos))) {
                pos++;
            }
            return pos > start ? s.substring(start, pos) : null;
        }

        private static boolean isLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        /**
         * {@code %<width>d}: white space skipped, then a sign and digits, the width counting the
         * sign; null with the position unchanged if there are no digits.
         */
        Long integer(int width) {
            int save = pos;
            ws();
            int start = pos;
            int end = pos;
            int limit = width == Integer.MAX_VALUE ? Integer.MAX_VALUE : start + width;
            if (end < s.length() && end < limit && (s.charAt(end) == '+' || s.charAt(end) == '-')) {
                end++;
            }
            int digits = end;
            while (end < s.length() && end < limit && s.charAt(end) >= '0' && s.charAt(end) <= '9') {
                end++;
            }
            if (end == digits) {
                pos = save;
                return null;
            }
            pos = end;
            return CScan.scanLong(s.substring(start, end));
        }

        /** {@code %lf}: white space skipped, then a number as {@code strtod} reads it. */
        Double dbl() {
            ws();
            CScan.Num n = CScan.strtod(s, pos);
            if (n == null) {
                return null;
            }
            pos = n.end;
            return n.value;
        }
    }
}
