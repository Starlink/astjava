package uk.ac.starlink.ast;

/**
 * Converting a card's value to the type a caller asks for, C's {@code CnvType} (fitschan.c) as
 * the {@code astGetFits<X>} getters use it, and the {@code sscanf} readings of a card's value.
 *
 * <p>Ported from Rust {@code fitschan/card_value.rs}.
 */
final class FitsCardValue {

    /** The text C stores for a bad floating value (fitschan.c {@code BAD_STRING}). */
    private static final String BAD_STRING = "<bad>";

    private FitsCardValue() {}

    /** The type a getter asks for. */
    enum Want {
        INT("integer"),
        KINT("64 bit integer"),
        FLOAT("floating point"),
        STRING("string"),
        CONTINUE("continuation string"),
        LOGICAL("logical"),
        COMPLEXF("complex floating point"),
        COMPLEXI("complex integer");

        private final String text;

        Want(String text) {
            this.text = text;
        }

        /** C's name for the type in error messages ({@code type_names}). */
        String cName() {
            return text;
        }
    }

    /**
     * A converted value: an integer ({@link Want#INT} narrowed to {@code int}, or {@link
     * Want#KINT}) in {@code i}, a floating value in {@code d}, a string in {@code s}, a logical in
     * {@code b}, and the parts of a complex value in {@code d}, {@code dIm} or {@code i}, {@code
     * iIm}.
     */
    static final class Got {
        final long i;
        final long iIm;
        final double d;
        final double dIm;
        final String s;
        final boolean b;

        private Got(long i, long iIm, double d, double dIm, String s, boolean b) {
            this.i = i;
            this.iIm = iIm;
            this.d = d;
            this.dIm = dIm;
            this.s = s;
            this.b = b;
        }

        static Got ofInt(long v) {
            return new Got(v, 0, 0, 0, null, false);
        }

        static Got ofFloat(double v) {
            return new Got(0, 0, v, 0, null, false);
        }

        static Got ofString(String v) {
            return new Got(0, 0, 0, 0, v, false);
        }

        static Got ofLogical(boolean v) {
            return new Got(0, 0, 0, 0, null, v);
        }

        static Got ofComplexF(double re, double im) {
            return new Got(0, 0, re, im, null, false);
        }

        static Got ofComplexI(long re, long im) {
            return new Got(re, im, 0, 0, null, false);
        }
    }

    /**
     * C's {@code CnvType} for a getter: the card's value converted to {@code want}.
     *
     * @param value the value, or null when there is no current card
     * @param want the type wanted
     * @param fitsrnd the FitsRounding attribute, used when a floating value becomes a string
     * @param name the card's keyword, for the error message
     * @return the value, or null when C's conversion fails (which the caller reports as {@code
     *     AST__FTCNV}): no current card, a commentary card, or a value that cannot be converted
     * @throws AstException with status {@code AST__FUNDEF} if the card has no value
     */
    // MODERN-JAVA(21): switch expressions over a sealed Value hierarchy.
    static Got cnvType(FitsCard.Value value, Want want, int fitsrnd, String name) {
        if (value == null) {
            return null;
        }
        switch (value.kind) {
            case UNDEF:
                throw Ast.err(AstException.AST__FUNDEF, "The FITS keyword '" + name + "' has an undefined value.");
            case COMMENT:
                return null;
            case FLOAT:
                return fromFloat(value.d, want, fitsrnd);
            case STRING:
            case CONTINUE:
                return fromString(value.s, want);
            case INT:
                return fromInt(narrow(value.i), want);
            case KINT:
                return fromInt(value.i, want);
            case LOGICAL:
                return fromLogical(value.b, want);
            case COMPLEX_FLOAT:
                return fromComplexFloat(value.d, value.dIm, want, fitsrnd);
            case COMPLEX_INT:
                return fromComplexInt((int) value.i, (int) value.iIm, want);
            default:
                throw Ast.err(AstException.AST__INTER, "CnvType: unexpected value kind " + value.kind);
        }
    }

    private static AstException unexpected(Want want) {
        return Ast.err(AstException.AST__INTER, "CnvType: unexpected type " + want);
    }

    private static Got fromLogical(boolean b, Want want) {
        switch (want) {
            case FLOAT:
                return Got.ofFloat(b ? 1.0 : 0.0);
            case STRING:
            case CONTINUE:
                return Got.ofString(b ? "Y" : "N");
            case INT:
            case KINT:
                return Got.ofInt(b ? 1 : 0);
            case LOGICAL:
                return Got.ofLogical(b);
            case COMPLEXF:
                return Got.ofComplexF(b ? 1.0 : 0.0, 0.0);
            case COMPLEXI:
                return Got.ofComplexI(b ? 1 : 0, 0);
            default:
                throw unexpected(want);
        }
    }

    private static Got fromComplexFloat(double re, double im, Want want, int fitsrnd) {
        switch (want) {
            case FLOAT:
                return Got.ofFloat(re);
            case STRING:
            case CONTINUE:
                return Got.ofString(floatText(re, fitsrnd) + " " + floatText(im, fitsrnd));
            case INT:
                return Got.ofInt((int) re);
            case KINT:
                return Got.ofInt((long) re);
            case LOGICAL:
                return Got.ofLogical(re != 0.0);
            case COMPLEXF:
                return Got.ofComplexF(re, im);
            case COMPLEXI:
                return Got.ofComplexI((int) re, (int) im);
            default:
                throw unexpected(want);
        }
    }

    private static Got fromComplexInt(int re, int im, Want want) {
        switch (want) {
            case FLOAT:
                return Got.ofFloat(re);
            case STRING:
            case CONTINUE:
                return Got.ofString(re + " " + im);
            case INT:
            case KINT:
                return Got.ofInt(re);
            case LOGICAL:
                return Got.ofLogical(re != 0);
            case COMPLEXF:
                return Got.ofComplexF(re, im);
            case COMPLEXI:
                return Got.ofComplexI(re, im);
            default:
                throw unexpected(want);
        }
    }

    /**
     * A floating value as C writes it for a string: {@code %.17g}, then {@code CheckZero} (no sign
     * on zero, and long runs of 0s and 9s rounded out).
     */
    private static String floatText(double v, int fitsrnd) {
        if (v == AstObject.AST__BAD) {
            return BAD_STRING;
        }
        String text = CFormat.format("%.17g", v);
        if (v == 0.0) {
            int minus = text.indexOf('-');
            return minus < 0 ? text : text.substring(0, minus) + " " + text.substring(minus + 1);
        }
        return FitsFormat.roundFString(text, fitsrnd);
    }

    private static Got fromFloat(double v, Want want, int fitsrnd) {
        switch (want) {
            case FLOAT:
                return Got.ofFloat(v);
            case STRING:
            case CONTINUE:
                return Got.ofString(floatText(v, fitsrnd));
            case INT:
                return Got.ofInt((int) v);
            case KINT:
                return Got.ofInt((long) v);
            case LOGICAL:
                return Got.ofLogical(v != 0.0);
            case COMPLEXF:
                return Got.ofComplexF(v, 0.0);
            case COMPLEXI:
                return Got.ofComplexI((int) v, 0);
            default:
                throw unexpected(want);
        }
    }

    /** An integer held as C's {@code int} or {@code int64_t}. */
    private static Got fromInt(long v, Want want) {
        switch (want) {
            case FLOAT:
                return Got.ofFloat((double) v);
            case STRING:
            case CONTINUE:
                return Got.ofString(Long.toString(v));
            case INT:
                return Got.ofInt(narrow(v));
            case KINT:
                return Got.ofInt(v);
            case LOGICAL:
                return Got.ofLogical(v != 0);
            case COMPLEXF:
                return Got.ofComplexF((double) v, 0.0);
            case COMPLEXI:
                return Got.ofComplexI(narrow(v), 0);
            default:
                throw unexpected(want);
        }
    }

    private static Got fromString(String s, Want want) {
        switch (want) {
            case FLOAT: {
                if (BAD_STRING.equals(FitsCard.trim(s))) {
                    return Got.ofFloat(AstObject.AST__BAD);
                }
                double[] v = scanAllDoubles(s, 1);
                return v == null ? null : Got.ofFloat(v[0]);
            }
            case STRING:
            case CONTINUE:
                return Got.ofString(s.length() > 80 ? s.substring(0, 80) : s);
            case INT: {
                long[] v = scanAllInts(s, 1);
                return v == null ? null : Got.ofInt(narrow(v[0]));
            }
            case KINT: {
                long[] v = scanAllInts(s, 1);
                return v == null ? null : Got.ofInt(v[0]);
            }
            case LOGICAL: {
                long[] v = scanAllInts(s, 1);
                if (v != null) {
                    return Got.ofLogical((int) v[0] != 0);
                }
                int k = skipSpace(s, 0);
                char first = k < s.length() ? s.charAt(k) : 0;
                char second = k + 1 < s.length() ? s.charAt(k + 1) : 0;
                if (first == 'y'
                        || first == 'Y'
                        || first == 't'
                        || first == 'T'
                        || (first == '.' && (second == 't' || second == 'T'))) {
                    return Got.ofLogical(true);
                } else if (first == 'n'
                        || first == 'N'
                        || first == 'f'
                        || first == 'F'
                        || (first == '.' && (second == 'f' || second == 'F'))) {
                    return Got.ofLogical(false);
                }
                return null;
            }
            case COMPLEXF: {
                double[] v = scanAllDoubles(s, 2);
                if (v != null) {
                    return Got.ofComplexF(v[0], v[1]);
                }
                v = scanAllDoubles(s, 1);
                return v == null ? null : Got.ofComplexF(v[0], 0.0);
            }
            case COMPLEXI: {
                long[] v = scanAllInts(s, 2);
                if (v != null) {
                    return Got.ofComplexI(narrow(v[0]), narrow(v[1]));
                }
                v = scanAllInts(s, 1);
                return v == null ? null : Got.ofComplexI(narrow(v[0]), 0);
            }
            default:
                throw unexpected(want);
        }
    }

    /** {@code v} narrowed to an {@code int}, as C's assignment of a wider integer narrows it. */
    private static long narrow(long v) {
        return (int) v;
    }

    private static int skipSpace(String s, int from) {
        int k = from;
        while (k < s.length() && CScan.isCSpace(s.charAt(k))) {
            k++;
        }
        return k;
    }

    /**
     * C's {@code sscanf( s, "%lf %lf ... %n" )} with {@code n} conversions, followed by the test
     * that every character was used: the values, or null.
     */
    static double[] scanAllDoubles(String s, int n) {
        double[] out = new double[n];
        int k = 0;
        for (int j = 0; j < n; j++) {
            CScan.Num num = CScan.strtod(s, skipSpace(s, k));
            if (num == null) {
                return null;
            }
            out[j] = num.value;
            k = skipSpace(s, num.end);
        }
        return k == s.length() ? out : null;
    }

    /**
     * C's {@code sscanf( s, "%d %d ... %n" )} (or {@code %PRId64}) with {@code n} conversions,
     * followed by the test that every character was used: the values, or null. Like {@code
     * strtoimax}, a value beyond the 64-bit range saturates; a {@code %d} caller then narrows it to
     * an {@code int} as C's assignment does.
     */
    static long[] scanAllInts(String s, int n) {
        long[] out = new long[n];
        int k = 0;
        for (int j = 0; j < n; j++) {
            int i = skipSpace(s, k);
            boolean neg = false;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                neg = s.charAt(i) == '-';
                i++;
            }
            int start = i;
            long v = 0;
            boolean overflow = false;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                int d = s.charAt(i) - '0';
                if (!overflow) {
                    // Accumulate negatively so that Long.MIN_VALUE can be reached.
                    if (v < (Long.MIN_VALUE + d) / 10) {
                        overflow = true;
                    } else {
                        v = v * 10 - d;
                    }
                }
                i++;
            }
            if (i == start) {
                return null;
            }
            if (overflow) {
                out[j] = neg ? Long.MIN_VALUE : Long.MAX_VALUE;
            } else if (neg) {
                out[j] = v;
            } else {
                out[j] = v == Long.MIN_VALUE ? Long.MAX_VALUE : -v;
            }
            k = skipSpace(s, i);
        }
        return k == s.length() ? out : null;
    }
}
