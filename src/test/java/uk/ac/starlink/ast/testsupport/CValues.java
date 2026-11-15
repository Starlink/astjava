package uk.ac.starlink.ast.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Comparisons of values and texts produced by the port against those produced by the C library.
 *
 * <p>A port of the Rust repository's {@code tests/common/mod.rs}. The C values were captured with
 * the C library's maths library, which is not Java's {@code Math}, so a C value is matched exactly
 * or to within {@link #withinLibmRounding libm rounding}, the rule the Rust tests apply off the
 * platform the values were captured on.
 */
public final class CValues {

    /** C's {@code AST__BAD}. */
    public static final double BAD = -Double.MAX_VALUE;

    /** Relative tolerance for libm rounding differences. */
    public static final double LIBM_RTOL = 1.0e-13;

    /** Absolute floor for {@link #LIBM_RTOL}, for values near zero. */
    public static final double LIBM_ATOL = 1.0e-14;

    /**
     * The numbers Rust's {@code str::parse::<f64>} accepts: decimal forms with an optional sign and
     * exponent, and infinities and NaN in any case.
     */
    private static final Pattern RUST_FLOAT =
            Pattern.compile("[+-]?(?:(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?|(?i:inf|infinity|nan))");

    private CValues() {}

    /**
     * How far a value that went through SphMap may move from C: {@code ulps} for any value and
     * {@code atol} for a value near zero.
     */
    // MODERN-JAVA(16): record
    public static final class DriftTol {
        /** Largest difference in units in the last place. */
        public final long ulps;

        /** Largest absolute difference. */
        public final double atol;

        public DriftTol(long ulps, double atol) {
            this.ulps = ulps;
            this.atol = atol;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DriftTol)) {
                return false;
            }
            DriftTol t = (DriftTol) o;
            return ulps == t.ulps && Double.compare(atol, t.atol) == 0;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(ulps) * 31 + Double.hashCode(atol);
        }

        @Override
        public String toString() {
            return "DriftTol(ulps=" + ulps + ", atol=" + atol + ")";
        }
    }

    /**
     * C's {@code astEQUAL}: {@code |a-b| <= max(1e5*(|a|+|b|)*DBL_EPSILON, 1e-12)}.
     */
    public static boolean astEqualClose(double a, double b) {
        if (a == b) {
            return true;
        }
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return false;
        }
        double tol = Math.max(1.0e5 * (Math.abs(a) + Math.abs(b)) * Math.ulp(1.0), 1.0e-12);
        return Math.abs(a - b) <= tol;
    }

    /**
     * Whether {@code a} and {@code b} are within {@code maxUlps} units in the last place; differing
     * signs, including zeros of opposite sign, are never close.
     */
    public static boolean ulpClose(double a, double b, long maxUlps) {
        if (a == b) {
            return true;
        }
        if (Double.isNaN(a) || Double.isNaN(b) || isSignNegative(a) != isSignNegative(b)) {
            return false;
        }
        return Math.abs(Double.doubleToRawLongBits(a) - Double.doubleToRawLongBits(b)) <= maxUlps;
    }

    private static boolean isSignNegative(double v) {
        return Double.doubleToRawLongBits(v) < 0;
    }

    /**
     * Whether {@code got} is within libm rounding of the C value {@code want}; a BAD value or a
     * non-finite value matches only an equal value, so NaN matches nothing.
     */
    public static boolean withinLibmRounding(double got, double want) {
        if (Double.isInfinite(got)
                || Double.isNaN(got)
                || Double.isInfinite(want)
                || Double.isNaN(want)
                || got == BAD
                || want == BAD) {
            return got == want;
        }
        return Math.abs(got - want) <= LIBM_ATOL + LIBM_RTOL * Math.abs(want);
    }

    /** Whether {@code got} matches the C value {@code want}: equal, or within libm rounding. */
    public static boolean cValueEq(double got, double want) {
        return got == want || withinLibmRounding(got, want);
    }

    /** {@link #cValueEq} element by element, with equal lengths. */
    public static boolean cValuesEq(double[] got, double[] want) {
        if (got.length != want.length) {
            return false;
        }
        for (int i = 0; i < got.length; i++) {
            if (!cValueEq(got[i], want[i])) {
                return false;
            }
        }
        return true;
    }

    /** Asserts {@link #cValuesEq}, reporting both sides and {@code what} on failure. */
    public static void assertCValues(double[] got, double[] want, String what) {
        if (!cValuesEq(got, want)) {
            fail(what + ": got " + Arrays.toString(got) + ", C gives " + Arrays.toString(want));
        }
    }

    /**
     * Whether {@code got} is within {@code tol} of the C value {@code want}, or within libm
     * rounding of it; a BAD value or NaN matches only itself.
     */
    public static boolean driftEq(double got, double want, DriftTol tol) {
        return got == want
                || ulpClose(got, want, tol.ulps)
                || Math.abs(got - want) <= tol.atol
                || withinLibmRounding(got, want);
    }

    /**
     * Compares two native dumps: byte-equal, except that a number may differ in value by up to
     * {@code maxUlps} units in the last place. Every other byte, white space included, must match.
     */
    public static boolean nativeStringsEquivalent(String got, String want, long maxUlps) {
        return linesMatch(got, want, (a, b) -> ulpClose(a, b, maxUlps));
    }

    /** {@link #nativeStringsEquivalent} with C's {@code astEQUAL} tolerance on numbers. */
    public static boolean nativeStringsAstEqual(String got, String want) {
        return linesMatch(got, want, CValues::astEqualClose);
    }

    /** Drops the {@code IsSimp} attribute lines from a native dump, joining the rest with newlines. */
    public static String stripIsSimp(String s) {
        List<String> kept = new ArrayList<>();
        for (String l : lines(s)) {
            if (!stripLeading(l).startsWith("IsSimp =")) {
                kept.add(l);
            }
        }
        return String.join("\n", kept);
    }

    /** A test of two numbers that differ. */
    @FunctionalInterface
    interface Close {
        boolean test(double a, double b);
    }

    private static boolean linesMatch(String got, String want, Close close) {
        if (got.equals(want)) {
            return true;
        }
        if (got.endsWith("\n") != want.endsWith("\n")) {
            return false;
        }
        List<String> g = lines(got);
        List<String> w = lines(want);
        if (g.size() != w.size()) {
            return false;
        }
        for (int i = 0; i < g.size(); i++) {
            if (!lineMatches(g.get(i), w.get(i), close)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lines of {@code s} as Rust's {@code str::lines} gives them: split at each newline, a
     * final newline ending the last line rather than starting an empty one, and a carriage return
     * before a newline removed.
     */
    public static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < s.length()) {
            int nl = s.indexOf('\n', start);
            int end = nl < 0 ? s.length() : nl;
            String line = s.substring(start, end);
            if (nl >= 0 && line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            out.add(line);
            start = nl < 0 ? s.length() : nl + 1;
        }
        return out;
    }

    /** The white-space-separated tokens of {@code s}, as Rust's {@code str::split_whitespace} gives them. */
    public static String[] splitWhitespace(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                out.add(s.substring(start, i));
            }
        }
        return out.toArray(new String[0]);
    }

    /** Whether {@code s} is empty or all white space. */
    public static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** A line as alternating runs of white space and of everything else, in order. */
    private static List<String> runs(String line) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i)) != Character.isWhitespace(line.charAt(i - 1))) {
                out.add(line.substring(start, i));
                start = i;
            }
        }
        if (start < line.length()) {
            out.add(line.substring(start));
        }
        return out;
    }

    private static int byteLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Whether two lines match under {@code close}: the same runs of white space and of other text,
     * except that a token both sides read as a number may be written differently when its value
     * differs and {@code close} accepts the difference. White space may differ only to absorb such
     * a number's change of width in a fixed-width line: some number must differ, and the white
     * space must change in total by exactly the opposite of the numbers' width change.
     */
    private static boolean lineMatches(String got, String want, Close close) {
        if (got.equals(want)) {
            return true;
        }
        List<String> g = runs(got);
        List<String> w = runs(want);
        if (g.size() != w.size()) {
            return false;
        }
        boolean numbersDiffer = false;
        boolean wsDiffer = false;
        int numWidth = 0;
        int wsWidth = 0;
        for (int i = 0; i < g.size(); i++) {
            String gr = g.get(i);
            String wr = w.get(i);
            if (gr.equals(wr)) {
                continue;
            }
            int width = byteLength(gr) - byteLength(wr);
            if (isBlank(gr) && isBlank(wr)) {
                wsDiffer = true;
                wsWidth += width;
                continue;
            }
            Double gv = parseRustFloat(gr);
            Double wv = parseRustFloat(wr);
            if (gv == null
                    || wv == null
                    || Double.doubleToRawLongBits(gv) == Double.doubleToRawLongBits(wv)
                    || !close.test(gv, wv)) {
                return false;
            }
            numbersDiffer = true;
            numWidth += width;
        }
        return !wsDiffer || (numbersDiffer && wsWidth == -numWidth);
    }

    /** Parses a token as Rust's {@code str::parse::<f64>} does, or returns null. */
    public static Double parseRustFloat(String tok) {
        if (!RUST_FLOAT.matcher(tok).matches()) {
            return null;
        }
        String t = tok.toLowerCase(Locale.ROOT);
        boolean neg = t.startsWith("-");
        String body = t.startsWith("-") || t.startsWith("+") ? t.substring(1) : t;
        if ("nan".equals(body)) {
            return Double.NaN;
        }
        if ("inf".equals(body) || "infinity".equals(body)) {
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return Double.parseDouble(tok);
    }
}
