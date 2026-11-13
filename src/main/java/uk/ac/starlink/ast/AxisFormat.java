package uk.ac.starlink.ast;

/**
 * Formatting an axis value with an axis Format string, as C's {@code AxisFormat} (axis.c) does:
 * the Format is rewritten by {@code ParseAxisFormat} and handed to {@code sprintf} with the value,
 * cast to {@code int} for an integer conversion.
 *
 * <p>Besides the conversions of C's {@code printf}, a Format may hold the AST flag {@code &},
 * which writes the value as {@code 10^x} with the exponent {@code x} formatted by the rest of the
 * Format, and a {@code .*} precision, which takes the axis Digits.
 *
 * <p>Ported from Rust {@code frames/axis_format.rs} ({@code parse_axis_format} and {@code
 * format_value}); the {@code printf} part is {@link CFormat}.
 */
final class AxisFormat {

    /**
     * The text written between the sign and the exponent of a log Format when graphical escapes
     * are not kept, which is the default (axis.c {@code log_txt}).
     */
    private static final String LOG_TXT = "10^";

    /** The escape sequence written after a log Format, which resets the graphical attributes. */
    private static final String LOG_END = "%+";

    /** The longest result C's buffer holds ({@code AST__AXIS_AXISFORMAT_BUFF_LEN}, axis.h). */
    private static final int BUFF_LEN = 127;

    private AxisFormat() {}

    /** A Format as C's {@code ParseAxisFormat} returns it, with the flags it reports. */
    // MODERN-JAVA(16): record
    static final class Parsed {
        /**
         * The Format to hand to {@code sprintf}: with {@code &}, the {@code &}, {@code +} and
         * space flags removed; a {@code .*} precision replaced by the Digits.
         */
        final String fmt;

        /** Whether the value is written as {@code 10^x}. */
        final boolean log;

        /** With {@code log}, whether a positive value has a {@code +} in front. */
        final boolean sign;

        /** With {@code log} and no {@code sign}, whether a positive value has a space in front. */
        final boolean space;

        /** Whether the conversion is an integer one, so that the value is cast to {@code int}. */
        final boolean integ;

        Parsed(String fmt, boolean log, boolean sign, boolean space, boolean integ) {
            this.fmt = fmt;
            this.log = log;
            this.sign = sign;
            this.space = space;
            this.integ = integ;
        }
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * C's {@code ParseAxisFormat} (axis.c): the Format {@code fmt0} without trailing white space;
     * with the {@code &} flag after its first '%', the {@code &}, {@code +} and space flags
     * removed and noted; the conversion code noted as integer or not; and a {@code .*} precision
     * replaced by {@code digs}.
     */
    static Parsed parseAxisFormat(String fmt0, int digs) {
        boolean log = false;
        boolean sign = false;
        boolean lspace = false;
        boolean integ = false;

        int len = fmt0.length();
        while (len > 0 && CScan.isCSpace(fmt0.charAt(len - 1))) {
            len--;
        }
        String result = fmt0.substring(0, len);

        int perc = result.indexOf('%');
        if (perc >= 0) {
            boolean minus = false;
            boolean plus = false;
            boolean space = false;
            boolean hash = false;
            int a = perc + 1;
            boolean flag = true;
            while (flag && a < result.length()) {
                switch (result.charAt(a)) {
                    case '-':
                        minus = true;
                        break;
                    case '+':
                        plus = true;
                        break;
                    case ' ':
                        space = true;
                        break;
                    case '#':
                        hash = true;
                        break;
                    case '&':
                        log = true;
                        break;
                    default:
                        flag = false;
                        continue;
                }
                a++;
            }
            if (log) {
                sign = plus;
                lspace = space;
                StringBuilder out = new StringBuilder(result.substring(0, perc + 1));
                if (minus) {
                    out.append('-');
                }
                if (hash) {
                    out.append('#');
                }
                out.append(result, a, result.length());
                result = out.toString();
            }
        }

        // Skip the flags and field width after the first '%'. The next letter is the conversion
        // code, and a ".*" precision takes the Digits. C's search for the code does not stop at
        // the end of the string; this one does.
        perc = result.indexOf('%');
        if (perc >= 0) {
            int a = perc + 1;
            while (a < result.length() && "-+ #".indexOf(result.charAt(a)) >= 0) {
                a++;
            }
            while (a < result.length() && result.charAt(a) >= '0' && result.charAt(a) <= '9') {
                a++;
            }
            for (int c = a; c < result.length(); c++) {
                if (isAlpha(result.charAt(c))) {
                    integ = "diuoxXc".indexOf(result.charAt(c)) >= 0;
                    break;
                }
            }
            if (a + 1 < result.length() && result.charAt(a) == '.' && result.charAt(a + 1) == '*') {
                result = result.substring(0, a) + "." + digs + result.substring(a + 2);
            }
        }
        return new Parsed(result, log, sign, lspace, integ);
    }

    /** C's {@code (int) x}, which saturates and gives zero for NaN, as Java's cast does. */
    private static int cInt(double x) {
        return (int) x;
    }

    /**
     * C's {@code AxisFormat} (axis.c) for a value that is not bad: {@code value} formatted with
     * the Format {@code fmt} and the axis Digits {@code digits}.
     *
     * @param cls the class of the Axis, for error messages
     * @throws AstException with status {@code AST__FMTER} if the Format cannot format one value,
     *     or the result is longer than C's buffer
     */
    static String formatValue(String cls, String fmt, int digits, double value) {
        Parsed p = parseAxisFormat(fmt, digits);

        // Zero is formatted normally. Otherwise a log Format gives "10^x" after a sign, with x
        // formatted by the Format.
        boolean log = p.log && value != 0.0;
        StringBuilder buf = new StringBuilder();
        double x = value;
        if (log) {
            if (p.sign) {
                buf.append('+');
            } else if (p.space) {
                buf.append(' ');
            }
            if (value > 0) {
                x = Math.log10(p.integ ? cInt(value) : value);
            } else {
                x = Math.log10(p.integ ? cInt(-value) : -value);
                buf.setLength(0);
                buf.append('-');
            }
            buf.append(LOG_TXT);
            if (Math.abs(x) < 1.0E-10) {
                x = 0.0;
            }
        }

        String text;
        try {
            text = p.integ ? CFormat.format(p.fmt, cInt(x)) : CFormat.format(p.fmt, x);
        } catch (AstException e) {
            AstException err = Ast.err(
                    AstException.AST__FMTER,
                    "astAxisFormat(" + cls + "): Error formatting a coordinate value of "
                            + CFormat.format("%1.17G", value) + " - " + e.getMsgText() + "\nThe format string was \""
                            + p.fmt + "\".");
            err.initCause(e);
            throw err;
        }
        buf.append(text);
        if (log) {
            buf.append(LOG_END);
        }
        if (buf.length() > BUFF_LEN) {
            throw Ast.err(
                    AstException.AST__FMTER,
                    "astAxisFormat(" + cls + "): Internal buffer overflow while formatting a coordinate value of "
                            + CFormat.format("%1.17G", value) + " - result exceeds " + BUFF_LEN
                            + " characters.\nThe format string was \"" + p.fmt + "\".");
        }

        // The result is a C string, so it ends at the first zero character, which %c writes for
        // a zero value.
        int nul = buf.indexOf("\0");
        if (nul >= 0) {
            buf.setLength(nul);
        }
        return buf.toString();
    }
}
