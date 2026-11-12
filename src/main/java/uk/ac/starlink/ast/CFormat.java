package uk.ac.starlink.ast;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * C's {@code sprintf(buf, fmt, value)} for a format with at most one conversion, given an
 * {@code int} or a {@code double}: the part of C's {@code printf} that AST's attribute values, axis
 * formatting and FITS cards use.
 *
 * <p>Ported from Rust {@code frames/axis_format.rs}. Floating conversions round the exact binary
 * value of the argument to the nearest decimal, ties to even, as C's {@code printf} does in the
 * default rounding mode; {@code String.format} does not, as it rounds the shortest decimal that
 * reads back to the value.
 *
 * <p>The argument must be consumed by exactly one conversion of the right type, as C requires; a
 * second conversion, a {@code *} width or precision, a length modifier that changes the argument
 * type, a conversion of the wrong type or a trailing {@code %} is undefined in C and raises
 * {@code AST__FMTER} here. {@code %a}, {@code %A} and the {@code '} flag are defined but not
 * ported, and raise it too.
 */
final class CFormat {

    private CFormat() {}

    /** A parsed conversion specification (C99 7.19.6.1). */
    private static final class Spec {
        boolean minus;
        boolean plus;
        boolean space;
        boolean zero;
        boolean alt;
        int width;
        /** The precision, or -1 if none was given. */
        int prec = -1;
    }

    /**
     * C's {@code sprintf} of a {@code double} with {@code fmt}.
     *
     * @throws AstException with status {@code AST__FMTER} if the format is not valid for one
     *     {@code double}
     */
    static String format(String fmt, double value) {
        return sprintf(fmt, false, 0, value);
    }

    /**
     * C's {@code sprintf} of an {@code int} with {@code fmt}.
     *
     * @throws AstException with status {@code AST__FMTER} if the format is not valid for one
     *     {@code int}
     */
    static String format(String fmt, int value) {
        return sprintf(fmt, true, value, 0.0);
    }

    private static AstException bad(String fmt, String reason) {
        return Ast.err(AstException.AST__FMTER, "Invalid C format \"" + fmt + "\": " + reason + ".");
    }

    private static char at(String s, int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int saturatingDigit(int n, char d) {
        long v = (long) n * 10 + (d - '0');
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    private static String sprintf(String fmt, boolean isInt, int iv, double dv) {
        StringBuilder out = new StringBuilder();
        boolean used = false;
        int i = 0;
        int n = fmt.length();
        while (i < n) {
            char c = fmt.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (at(fmt, i) == '%' && i < n) {
                out.append('%');
                i++;
                continue;
            }
            Spec spec = new Spec();
            boolean flags = true;
            while (flags && i < n) {
                switch (fmt.charAt(i)) {
                    case '-':
                        spec.minus = true;
                        break;
                    case '+':
                        spec.plus = true;
                        break;
                    case ' ':
                        spec.space = true;
                        break;
                    case '0':
                        spec.zero = true;
                        break;
                    case '#':
                        spec.alt = true;
                        break;
                    case '\'':
                        throw bad(fmt, "the ' flag is not supported");
                    default:
                        flags = false;
                        continue;
                }
                i++;
            }
            if (at(fmt, i) == '*' && i < n) {
                throw bad(fmt, "a * field width needs a second argument");
            }
            while (i < n && isDigit(fmt.charAt(i))) {
                spec.width = saturatingDigit(spec.width, fmt.charAt(i));
                i++;
            }
            if (i < n && fmt.charAt(i) == '.') {
                i++;
                if (i < n && fmt.charAt(i) == '*') {
                    throw bad(fmt, "a * precision needs a second argument");
                }
                int p = 0;
                while (i < n && isDigit(fmt.charAt(i))) {
                    p = saturatingDigit(p, fmt.charAt(i));
                    i++;
                }
                spec.prec = p;
            }
            // "l" has no effect on a floating conversion; any other length modifier changes the
            // argument type.
            boolean isLong = i < n && fmt.charAt(i) == 'l';
            if (isLong) {
                i++;
            } else if (i < n && "hLqjzt".indexOf(fmt.charAt(i)) >= 0) {
                throw bad(fmt, "a length modifier changes the argument type");
            }
            if (i >= n) {
                throw bad(fmt, "the format ends inside a conversion");
            }
            char conv = fmt.charAt(i);
            i++;
            if (used) {
                throw bad(fmt, "a second conversion has no argument");
            }
            used = true;
            if (isInt && !isLong && "diuoxX".indexOf(conv) >= 0) {
                out.append(formatInt(spec, conv, iv));
            } else if (isInt && !isLong && conv == 'c') {
                if (spec.zero) {
                    throw bad(fmt, "the 0 flag is undefined for %c");
                }
                // The int is converted to unsigned char.
                out.append(pad(spec, "", String.valueOf((char) (iv & 0xFF)), false));
            } else if (!isInt && "eEfFgG".indexOf(conv) >= 0) {
                out.append(formatDouble(spec, conv, dv));
            } else if (!isInt && (conv == 'a' || conv == 'A')) {
                throw bad(fmt, "%a is not supported");
            } else {
                throw bad(fmt, "%" + conv + " does not take the " + (isInt ? "int" : "double") + " argument");
            }
        }
        return out.toString();
    }

    /**
     * Pads {@code prefix} (sign and radix prefix) and {@code body} (digits) to the field width:
     * spaces on the right with '-', zeros after the prefix when {@code zeroPad}, else spaces on the
     * left.
     */
    private static String pad(Spec spec, String prefix, String body, boolean zeroPad) {
        int len = prefix.length() + body.length();
        int fill = Math.max(spec.width - len, 0);
        StringBuilder out = new StringBuilder(len + fill);
        if (spec.minus) {
            out.append(prefix).append(body);
            repeat(out, ' ', fill);
        } else if (zeroPad) {
            out.append(prefix);
            repeat(out, '0', fill);
            out.append(body);
        } else {
            repeat(out, ' ', fill);
            out.append(prefix).append(body);
        }
        return out.toString();
    }

    private static void repeat(StringBuilder sb, char c, int count) {
        for (int k = 0; k < count; k++) {
            sb.append(c);
        }
    }

    /** The sign C writes in front of a signed conversion. */
    private static String signPrefix(Spec spec, boolean negative) {
        if (negative) {
            return "-";
        }
        if (spec.plus) {
            return "+";
        }
        return spec.space ? " " : "";
    }

    /** {@code %d}, {@code %i}, {@code %u}, {@code %o}, {@code %x} and {@code %X} of an int. */
    private static String formatInt(Spec spec, char conv, int v) {
        boolean negative = false;
        String digits;
        switch (conv) {
            case 'd':
            case 'i':
                negative = v < 0;
                digits = Long.toString(Math.abs((long) v));
                break;
            case 'u':
                digits = Integer.toUnsignedString(v);
                break;
            case 'o':
                digits = Integer.toOctalString(v);
                break;
            case 'x':
                digits = Integer.toHexString(v);
                break;
            case 'X':
                digits = Integer.toHexString(v).toUpperCase(java.util.Locale.ROOT);
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "CFormat: unexpected integer conversion %" + conv + ".");
        }
        // A precision is the minimum number of digits; zero with precision 0 gives no digits.
        StringBuilder body = new StringBuilder(spec.prec == 0 && v == 0 ? "" : digits);
        while (spec.prec > 0 && body.length() < spec.prec) {
            body.insert(0, '0');
        }
        // '#' makes the first octal digit zero, and puts 0x or 0X before a non-zero hexadecimal
        // value.
        String prefix = "";
        if (conv == 'd' || conv == 'i') {
            prefix = signPrefix(spec, negative);
        } else if (conv == 'o' && spec.alt && (body.length() == 0 || body.charAt(0) != '0')) {
            body.insert(0, '0');
        } else if (conv == 'x' && spec.alt && v != 0) {
            prefix = "0x";
        } else if (conv == 'X' && spec.alt && v != 0) {
            prefix = "0X";
        }
        // '0' is ignored with a precision.
        return pad(spec, prefix, body.toString(), spec.zero && spec.prec < 0);
    }

    /** {@code %e}, {@code %E}, {@code %f}, {@code %F}, {@code %g} and {@code %G} of a double. */
    private static String formatDouble(Spec spec, char conv, double v) {
        boolean upper = Character.isUpperCase(conv);
        String prefix = signPrefix(spec, Double.doubleToRawLongBits(v) < 0);
        double a = Math.abs(v);
        if (Double.isNaN(a) || Double.isInfinite(a)) {
            String text = Double.isNaN(a) ? "nan" : "inf";
            return pad(spec, prefix, upper ? text.toUpperCase(java.util.Locale.ROOT) : text, false);
        }
        int prec = spec.prec < 0 ? 6 : spec.prec;
        String body;
        switch (Character.toLowerCase(conv)) {
            case 'e':
                body = expForm(a, prec, spec.alt, upper);
                break;
            case 'f':
                body = fixedForm(a, prec, spec.alt);
                break;
            case 'g': {
                // P significant digits, in %e style when the exponent X of the value rounded to P
                // digits is below -4 or at least P, else in %f style with P - 1 - X decimals;
                // trailing zeros and a bare decimal point removed unless '#'.
                int p = Math.max(prec, 1);
                int x = decimalExponent(a, p - 1);
                String s = x < -4 || x >= p ? expForm(a, p - 1, spec.alt, upper) : fixedForm(a, p - 1 - x, spec.alt);
                body = spec.alt ? s : stripZeros(s);
                break;
            }
            default:
                throw Ast.err(AstException.AST__INTER, "CFormat: unexpected floating conversion %" + conv + ".");
        }
        return pad(spec, prefix, body, spec.zero);
    }

    /**
     * The significant digits of {@code a} (finite, non-negative) rounded to {@code ndigits}, and the
     * decimal exponent of the first: {@code a} is about {@code d.ddd * 10^exp}.
     */
    private static final class Digits {
        final String text;
        final int exp;

        Digits(double a, int ndigits) {
            if (a == 0.0) {
                StringBuilder z = new StringBuilder();
                repeat(z, '0', ndigits);
                text = z.toString();
                exp = 0;
                return;
            }
            BigDecimal bd = exact(a).round(new MathContext(ndigits, RoundingMode.HALF_EVEN));
            String u = bd.unscaledValue().toString();
            exp = u.length() - 1 - bd.scale();
            StringBuilder d = new StringBuilder(u);
            if (d.length() > ndigits) {
                d.setLength(ndigits);
            }
            repeat(d, '0', ndigits - d.length());
            text = d.toString();
        }
    }

    /** The exact value of {@code a}, which C's {@code printf} rounds. */
    // The rule is about decimal literals, whose binary value is not the decimal written; here the
    // binary value is what is wanted.
    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static BigDecimal exact(double a) {
        return new BigDecimal(a);
    }

    /** The decimal exponent of {@code a} written in {@code %e} style with {@code decimals} decimals. */
    private static int decimalExponent(double a, int decimals) {
        return new Digits(a, decimals + 1).exp;
    }

    /**
     * {@code a} in {@code %e} style: one digit, {@code prec} decimals, then the exponent with a
     * sign and at least two digits. '#' keeps the point when {@code prec} is 0.
     */
    private static String expForm(double a, int prec, boolean alt, boolean upper) {
        Digits d = new Digits(a, prec + 1);
        StringBuilder sb = new StringBuilder(prec + 8).append(d.text.charAt(0));
        if (prec > 0 || alt) {
            sb.append('.');
        }
        sb.append(d.text, 1, d.text.length()).append(upper ? 'E' : 'e').append(d.exp < 0 ? '-' : '+');
        int e = Math.abs(d.exp);
        if (e < 10) {
            sb.append('0');
        }
        return sb.append(e).toString();
    }

    /** {@code a} in {@code %f} style with {@code prec} decimals. '#' keeps the point when {@code prec} is 0. */
    private static String fixedForm(double a, int prec, boolean alt) {
        String s = exact(a).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
        return alt && prec == 0 ? s + "." : s;
    }

    /** Removes trailing zeros from the fraction of a {@code %g} result, and the point if no fraction is left. */
    private static String stripZeros(String s) {
        int e = s.indexOf('e');
        if (e < 0) {
            e = s.indexOf('E');
        }
        String num = e < 0 ? s : s.substring(0, e);
        String exp = e < 0 ? "" : s.substring(e);
        if (num.indexOf('.') >= 0) {
            int end = num.length();
            while (end > 0 && num.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && num.charAt(end - 1) == '.') {
                end--;
            }
            num = num.substring(0, end);
        }
        return num + exp;
    }
}
