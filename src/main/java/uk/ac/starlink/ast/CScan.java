package uk.ac.starlink.ast;

/**
 * The parts of C's {@code strtod} and {@code sscanf} that AST uses to read numbers from strings.
 *
 * <p>{@link #strtod} is ported from Rust {@code frames/axis_format.rs} ({@code c_strtod}). It
 * reads as C's {@code strtod} does, and as {@code sscanf("%lf")} does on macOS: the longest prefix
 * that is a decimal or hexadecimal floating constant, an infinity or a NaN. The C standard instead
 * makes a {@code %lf} input item the longest prefix of a possible number, so a library following
 * it fails on "1e+x" or "0x", where this reads "1" or "0".
 */
final class CScan {

    private CScan() {}

    /** A number read from a string, and the index just after it. */
    static final class Num {
        final double value;
        final int end;

        Num(double value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    /** Whether {@code c} is a C {@code isspace} character in the C locale. */
    static boolean isCSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r';
    }

    /** C's {@code tolower} in the C locale: ASCII letters only. */
    static char toLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    /** C's {@code toupper} in the C locale: ASCII letters only. */
    static char toUpper(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiAlnum(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean startsWithIgnoreCase(String s, int at, String word) {
        return s.regionMatches(true, at, word, 0, word.length());
    }

    private static int digits(String s, int from) {
        int k = from;
        while (k < s.length() && isDigit(s.charAt(k))) {
            k++;
        }
        return k - from;
    }

    /**
     * C's {@code strtod} on {@code s} from index {@code from}, without skipping leading white
     * space.
     *
     * @return the value and the index after it, or null if no prefix is a number
     */
    static Num strtod(String s, int from) {
        int i = from;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        if (startsWithIgnoreCase(s, i, "infinity")) {
            return new Num(neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY, i + 8);
        }
        if (startsWithIgnoreCase(s, i, "inf")) {
            return new Num(neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY, i + 3);
        }
        if (startsWithIgnoreCase(s, i, "nan")) {
            // "nan" may be followed by a parenthesised sequence of letters, digits and
            // underscores, taken only when it is closed.
            int end = i + 3;
            if (end < s.length() && s.charAt(end) == '(') {
                int k = end + 1;
                while (k < s.length() && (isAsciiAlnum(s.charAt(k)) || s.charAt(k) == '_')) {
                    k++;
                }
                if (k < s.length() && s.charAt(k) == ')') {
                    end = k + 1;
                }
            }
            return new Num(Double.NaN, end);
        }
        if (i + 1 < s.length() && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            Num hex = hexFloat(s, i + 2);
            if (hex != null) {
                return new Num(neg ? -hex.value : hex.value, hex.end);
            }
            // "0x" with no hexadecimal digits is the number 0.
            return new Num(neg ? -0.0 : 0.0, i + 1);
        }

        // Decimal: digits with an optional point, at least one digit, then an optional exponent
        // with at least one digit.
        int j = i + digits(s, i);
        int ndig = j - i;
        if (j < s.length() && s.charAt(j) == '.') {
            int frac = digits(s, j + 1);
            ndig += frac;
            j += 1 + frac;
        }
        if (ndig == 0) {
            return null;
        }
        if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                k++;
            }
            int exp = digits(s, k);
            if (exp > 0) {
                j = k + exp;
            }
        }
        // Java's parse is correctly rounded, as strtod is.
        double v = Double.parseDouble(s.substring(i, j));
        return new Num(neg ? -v : v, j);
    }

    /**
     * The hexadecimal floating constant starting at {@code from}, just after "0x": hexadecimal
     * digits with an optional point, at least one digit, then an optional binary exponent with at
     * least one decimal digit. Rounded to nearest, ties to even, as strtod rounds; Java's parsing of
     * a hexadecimal floating literal rounds the same way.
     */
    private static Num hexFloat(String s, int from) {
        int k = from;
        while (k < s.length() && isHexDigit(s.charAt(k))) {
            k++;
        }
        String intPart = s.substring(from, k);
        String fracPart = "";
        int j = k;
        if (j < s.length() && s.charAt(j) == '.') {
            int f = j + 1;
            while (f < s.length() && isHexDigit(s.charAt(f))) {
                f++;
            }
            fracPart = s.substring(j + 1, f);
            j = f;
        }
        if (intPart.isEmpty() && fracPart.isEmpty()) {
            return null;
        }
        long exp2 = 0;
        if (j < s.length() && (s.charAt(j) == 'p' || s.charAt(j) == 'P')) {
            int e = j + 1;
            boolean negExp = false;
            if (e < s.length() && (s.charAt(e) == '+' || s.charAt(e) == '-')) {
                negExp = s.charAt(e) == '-';
                e++;
            }
            int n = digits(s, e);
            if (n > 0) {
                for (int d = e; d < e + n; d++) {
                    // Any exponent this large already overflows or underflows.
                    exp2 = Math.min(exp2 * 10 + (s.charAt(d) - '0'), 1L << 40);
                }
                exp2 = negExp ? -exp2 : exp2;
                j = e + n;
            }
        }
        String lit = "0x" + (intPart.isEmpty() ? "0" : intPart) + "." + fracPart + "p" + exp2;
        return new Num(Double.parseDouble(lit), j);
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && isCSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * {@code sscanf(s, " %lf %n")} consuming the whole string: the value, or null if the string is
     * not one number with optional surrounding white space.
     */
    static Double scanDouble(String s) {
        Num n = strtod(s, skipSpace(s, 0));
        return n != null && skipSpace(s, n.end) == s.length() ? n.value : null;
    }

    /** {@code sscanf(s, " %f %n")} consuming the whole string, or null. */
    static Float scanFloat(String s) {
        int start = skipSpace(s, 0);
        Num n = strtod(s, start);
        if (n == null || skipSpace(s, n.end) != s.length()) {
            return null;
        }
        // strtof rounds the decimal directly to float; rounding through double could differ in
        // the last bit.
        String tok = s.substring(start, n.end);
        boolean decimal = Double.isFinite(n.value)
                && !tok.toLowerCase(java.util.Locale.ROOT).contains("x");
        return decimal ? Float.parseFloat(tok) : (float) n.value;
    }

    /**
     * {@code sscanf(s, " %ld %n")} consuming the whole string, or null. Out of range values
     * saturate, as {@code strtol} does.
     */
    static Long scanLong(String s) {
        int i = skipSpace(s, 0);
        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            i++;
        }
        int n = digits(s, i);
        if (n == 0 || skipSpace(s, i + n) != s.length()) {
            return null;
        }
        java.math.BigInteger v =
                new java.math.BigInteger(s.substring(start, i + n).replace("+", ""));
        if (v.bitLength() > 63) {
            return v.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return v.longValue();
    }

    /**
     * {@code sscanf(s, " %d %n")} consuming the whole string, or null. A value outside the
     * {@code int} range is read as {@code strtol} reads it and then narrowed to {@code int}.
     */
    static Integer scanInt(String s) {
        Long v = scanLong(s);
        return v == null ? null : (int) (long) v;
    }
}
