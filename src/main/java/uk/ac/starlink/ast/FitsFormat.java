package uk.ac.starlink.ast;

/**
 * The formatting of floating point values in FITS header cards: a value is written to the requested
 * number of significant digits, a zero loses any minus sign, and long runs of 0s and 9s are rounded
 * out, as a FitsChan writes it in a card.
 *
 * <p>Ported from Rust {@code fitschan/format.rs}, which ports {@code fitschan.c}'s
 * {@code EncodeFloat} with the {@code CheckZero} and {@code RoundFString} clean-up it applies.
 */
final class FitsFormat {

    /** The width of a value right-justified to column 30, C's {@code FITSRLCOL - FITSNAMLEN - 2}. */
    static final int VALUE_WIDTH = 20;

    /** The columns available to a value starting in column 11, C's {@code AST__FITSCHAN_FITSCARDLEN - col + 1}. */
    static final int VALUE_MAXWIDTH = 70;

    /** The shortest run of adjacent 0s or 9s that {@code RoundFString} rounds out, C's {@code NSEQ}. */
    private static final int NSEQ = 4;

    private FitsFormat() {}

    /**
     * A floating point value formatted for the value of a header card, right-justified to column
     * 30.
     *
     * @param value the value
     * @param digits the number of significant digits, as the FitsDigits attribute gives it
     * @param fitsrnd the number of leading digits protected from rounding, as the FitsRounding
     *     attribute gives it
     * @return the text, or null if the value is too wide for the card; as in C, an infinity or a NaN
     *     is written as zero, because {@code RoundFString} finds no non-zero digit in it
     */
    static String encodeFloat(double value, int digits, int fitsrnd) {
        return encodeFloatIn(value, digits, VALUE_WIDTH, VALUE_MAXWIDTH, fitsrnd);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * C's {@code EncodeFloat}: {@code value} right-justified in {@code width} columns, using no
     * more than {@code maxwidth}, or null if it cannot be.
     *
     * <p>The value is written with {@code %*.*G} to {@code |digits|} significant digits
     * ({@code %*.*E} for one between -0.001 and -0.0001, which is one character shorter), then
     * {@code CheckZero} cleans it, and a leading zero in the exponent is removed. If that is wider
     * than {@code width} and {@code digits} is negative, the digits are reduced by the excess and
     * the value written again. Finally a decimal point is added if the value has none.
     */
    static String encodeFloatIn(double value, int digits, int width, int maxwidth, int fitsrnd) {
        int ldigits = Math.abs(digits);
        StringBuilder buf;
        while (true) {
            char conv = 'G';
            int prec = ldigits;
            if (value > -0.001 && value < -0.0001) {
                conv = 'E';
                prec = ldigits - 1;
            }
            // A negative precision is taken by C's printf as if it were omitted.
            String fmt = prec < 0 ? "%" + width + conv : "%" + width + "." + prec + conv;
            buf = new StringBuilder(checkZero(CFormat.format(fmt, value), value, width, fitsrnd));

            // Remove a leading zero from the exponent. A leading space absorbs the change and keeps
            // the field width; otherwise the text shortens.
            String text = buf.toString();
            int w = -1;
            if (text.indexOf("E-0") >= 0) {
                w = text.indexOf("E-0") + 2;
            } else if (text.indexOf("E+0") >= 0) {
                w = text.indexOf("E+0") + 2;
            } else if (text.indexOf("E0") >= 0) {
                w = text.indexOf("E0") + 1;
            }
            if (w >= 0) {
                buf.deleteCharAt(w);
                if (buf.length() > 0 && buf.charAt(0) == ' ') {
                    buf.insert(0, ' ');
                }
            }

            int ret = buf.length();
            if (ret > width && digits < 0) {
                ldigits -= ret - width;
            } else if (ret > maxwidth) {
                return null;
            } else {
                break;
            }
        }

        // Ensure there is a decimal point after the leading digits.
        int first = -1;
        for (int k = 0; k < buf.length(); k++) {
            if (isDigit(buf.charAt(k))) {
                first = k;
                break;
            }
        }
        if (first < 0) {
            return null;
        }
        int n = 0;
        while (first + n < buf.length() && isDigit(buf.charAt(first + n))) {
            n++;
        }
        if (first + n >= buf.length() || buf.charAt(first + n) != '.') {
            if (buf.length() >= 2 && buf.charAt(0) == ' ' && buf.charAt(1) == ' ') {
                // Shift the leading characters two places left and append ".0" to the digits.
                buf.delete(0, 2);
                buf.insert(first - 2 + n, ".0");
            } else if (buf.length() > 0 && buf.charAt(0) == ' ') {
                // Shift the leading characters one place left and append ".".
                buf.deleteCharAt(0);
                buf.insert(first - 1 + n, '.');
            } else {
                // With no leading blank, the value grows by the ".".
                if (buf.length() + 1 > maxwidth) {
                    return null;
                }
                buf.insert(first + n, '.');
            }
        }
        return buf.toString();
    }

    private static int leadingBlanks(String text) {
        int lead = 0;
        while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) {
            lead++;
        }
        return lead;
    }

    /**
     * C's {@code CheckZero}: no minus sign on a zero value, and otherwise {@code RoundFString} on
     * the text, which {@code width} is the field width of.
     */
    private static String checkZero(String text, double value, int width, int fitsrnd) {
        if (value == 0.0) {
            int lead = leadingBlanks(text);
            if (lead < text.length() && text.charAt(lead) == '-') {
                return text.substring(0, lead) + " " + text.substring(lead + 1);
            }
            return text;
        }
        return roundFStringIn(text, width, fitsrnd);
    }

    /**
     * C's {@code RoundFString} on {@code text}, a value right-justified in a field of {@code width}
     * columns: the value with long runs of 0s and 9s rounded out (see {@link #roundFString}),
     * right-justified in {@code width} columns, or with no leading blanks if it is wider.
     *
     * <p>C keeps the value unrounded if rounding would carry a digit into a value with no leading
     * blank to take it and make it longer.
     */
    private static String roundFStringIn(String text, int width, int fitsrnd) {
        int lead = leadingBlanks(text);
        String rounded = roundFString(text.substring(lead), fitsrnd);
        String content = lead > 0 || rounded.length() <= text.length() ? rounded : text;
        StringBuilder sb = new StringBuilder();
        for (int k = content.length(); k < width; k++) {
            sb.append(' ');
        }
        return sb.append(content).toString();
    }

    /**
     * Rounds out long sequences of adjacent 0s or 9s in a formatted floating-point string, as
     * {@code fitschan.c}'s {@code RoundFString} does.
     *
     * <p>{@code fitsrnd} is the number of leading significant digits protected from rounding
     * (treated as 1 if 0). A run of {@code NSEQ} (4) or more adjacent 0s or 9s that ends beyond the
     * protected zone is collapsed: a run of 9s rounds up, carrying into earlier digits and
     * inserting a leading 1 if the carry reaches the front, and a run of 0s is left in place with
     * any digits after it set to zero. Trailing zeros after a decimal point are then removed,
     * keeping at least one.
     */
    static String roundFString(String text, int fitsrnd) {
        fitsrnd = Math.max(fitsrnd, 1);

        int e = text.indexOf('E');
        if (e < 0) {
            e = text.indexOf('e');
        }
        String mantissa = e < 0 ? text : text.substring(0, e);
        String exponent = e < 0 ? "" : text.substring(e);

        // The mantissa with a leading sentinel space, so that a final carry has a place to land.
        char[] buf = (" " + mantissa).toCharArray();
        int n = buf.length;

        // The decimal point, and the first digit at which rounding may occur: the fitsrnd-th
        // significant digit from the left.
        int dot = -1;
        int prnd = -1;
        boolean started = false;
        int nsig = 0;
        for (int i = 0; i < n; i++) {
            char b = buf[i];
            if (b == '.') {
                dot = i;
            } else if (isDigit(b)) {
                if (b != '0') {
                    started = true;
                }
                if (started) {
                    nsig++;
                    if (nsig == fitsrnd && prnd < 0) {
                        prnd = i;
                    }
                }
            }
        }

        // With no non-zero digit, C gives the simplest zero.
        if (!started) {
            return dot >= 0 ? "0.0" : "0";
        }
        // prnd is unset when fitsrnd covers every significant digit, and nothing can be rounded.
        if (prnd >= 0) {
            // Walk backward over the digits, tracking runs of 9s and 0s.
            int seq9 = -1;
            int seq0 = -1;
            int seqlen = 0;
            int i = n;
            while (i > 0) {
                i--;
                char b = buf[i];
                if (seq9 >= 0) {
                    if (b == '9') {
                        seqlen++;
                    } else if (isDigit(b)) {
                        if (seq9 > prnd && seqlen >= NSEQ) {
                            zeroDigits(buf, i + 1, n);
                            buf[i]++;
                        }
                        if (buf[i] == '9') {
                            seq9 = i;
                            seq0 = -1;
                            seqlen = 1;
                        } else if (buf[i] == '0') {
                            seq0 = i;
                            seq9 = -1;
                            seqlen = 1;
                        } else {
                            seq9 = -1;
                            seqlen = 0;
                        }
                    }
                } else if (seq0 >= 0) {
                    if (b == '0') {
                        seqlen++;
                    } else if (isDigit(b)) {
                        if (seq0 > prnd && seqlen >= NSEQ) {
                            zeroDigits(buf, i + 1, n);
                        }
                        if (buf[i] == '9') {
                            seq9 = i;
                            seq0 = -1;
                            seqlen = 1;
                        } else {
                            seq0 = -1;
                            seqlen = 0;
                        }
                    }
                } else if (i < prnd) {
                    // Walked past the protection cutoff with no active run.
                    break;
                } else if (b == '9') {
                    seq9 = i;
                    seqlen = 1;
                } else if (b == '0') {
                    seq0 = i;
                    seqlen = 1;
                }
            }

            // A run of 9s reaching the front of the number collapses and gains a leading 1, which
            // the sentinel space makes room for.
            if (seq9 >= 0 && seq9 > prnd && seqlen >= NSEQ) {
                boolean first = true;
                for (int k = 0; k < n; k++) {
                    if (isDigit(buf[k])) {
                        buf[k] = '0';
                        if (first) {
                            // Shift any sign characters one place left to put the 1 before this
                            // zero.
                            System.arraycopy(buf, 1, buf, 0, k - 1);
                            buf[k - 1] = '1';
                            first = false;
                        }
                    }
                }
            }
        }

        // Strip trailing zeros after a decimal point, keeping one, when there was a point.
        int end = n;
        if (dot >= 0) {
            while (end > dot + 1 && (buf[end - 1] == '0' || buf[end - 1] == ' ')) {
                end--;
            }
            if (end == dot + 1 && end < n) {
                buf[end] = '0';
                end++;
            }
        }

        // Drop the leading sentinel space if it survived.
        int start = buf[0] == ' ' ? 1 : 0;
        return new String(buf, start, Math.max(end - start, 0)) + exponent;
    }

    private static void zeroDigits(char[] buf, int from, int to) {
        for (int k = from; k < to; k++) {
            if (isDigit(buf[k])) {
                buf[k] = '0';
            }
        }
    }
}
