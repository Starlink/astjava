package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Matching keyword names against templates, as C's {@code Match}, {@code MatchFront} and {@code
 * MatchChar} (fitschan.c) do.
 *
 * <p>A template is a keyword name in which {@code %} introduces a field specifier {@code
 * %<width><type>}. A width of 0 matches zero or more characters, 1 to 9 exactly that many, and an
 * omitted width one or more. Type {@code d} matches decimal digits, {@code c} upper case letters,
 * and {@code f} any character legal in a FITS keyword (letters of either case, digits, {@code _}
 * and {@code -}). Any other character must be matched by the same character, ignoring case.
 *
 * <p>Ported from Rust {@code fitschan/fits_match.rs}.
 */
final class FitsMatch {

    /** The first template fault met during the current match in this thread, or null. */
    private static final ThreadLocal<String[]> BAD_TEMPLATE = ThreadLocal.withInitial(() -> new String[1]);

    /** The fault of an incomplete field specifier, as recorded in {@link #BAD_TEMPLATE}. */
    private static final String INCOMPLETE = "";

    private FitsMatch() {}

    /** A legal FITS keyword character, C's {@code isFits}, which accepts lower case letters. */
    private static boolean isFits(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    /** Records the first template fault of the current match: an illegal type, or {@link #INCOMPLETE}. */
    private static void noteBadTemplate(String fault) {
        String[] b = BAD_TEMPLATE.get();
        if (b[0] == null) {
            b[0] = fault;
        }
    }

    /** Whether {@code test} belongs to the character set named by {@code type} (C's {@code MatchChar}). */
    private static boolean matchChar(char test, char type) {
        switch (type) {
            case 'd':
                return test >= '0' && test <= '9';
            case 'c':
                return test >= 'A' && test <= 'Z';
            case 'f':
                return isFits(test);
            default:
                noteBadTemplate(String.valueOf(type));
                return false;
        }
    }

    /** The outcome of matching the leading field of a template. */
    private static final class Front {
        /** The field type, or 0 when the template began with a literal. */
        final char type;

        /** Whether the field can take further test characters (an unspecified or zero width). */
        final boolean extend;

        /** The characters used from the test string, the field's minimum. */
        final int ntest;

        /** The characters used from the template. */
        final int ntemp;

        Front(char type, boolean extend, int ntest, int ntemp) {
            this.type = type;
            this.extend = extend;
            this.ntest = ntest;
            this.ntemp = ntemp;
        }
    }

    private static char at(String s, int i) {
        return i < s.length() ? s.charAt(i) : 0;
    }

    /**
     * Matches the first field of {@code temp} (from {@code tp}) against the start of {@code test}
     * (from {@code tt}), or null when fewer than the minimum number of characters match (C's
     * {@code MatchFront}).
     */
    private static Front matchFront(String test, int tt, String temp, int tp) {
        char tb = at(temp, tp);
        if (tb != '%') {
            char ta = at(test, tt);
            return CScan.toLower(tb) == CScan.toLower(ta) ? new Front((char) 0, false, 1, 1) : null;
        }
        int ntemp = 3;
        int b = 1;
        int ntest;
        boolean extend;
        char w = at(temp, tp + b);
        if (tp + b < temp.length() && w >= '0' && w <= '9') {
            ntest = w - '0';
            extend = ntest == 0;
        } else {
            // No width: one or more characters match, and the current character is the type.
            b--;
            ntemp--;
            ntest = 1;
            extend = true;
        }
        b++;
        if (tp + b >= temp.length()) {
            noteBadTemplate(INCOMPLETE);
            return null;
        }
        char type = temp.charAt(tp + b);
        // C tests the string's terminating null too, so an illegal type is found even when the
        // test string has run out.
        for (int i = 0; i < ntest; i++) {
            if (!matchChar(at(test, tt + i), type)) {
                return null;
            }
        }
        return new Front(type, extend, ntest, ntemp);
    }

    /** The decimal digits {@code s} holds as an {@code int}, or null if it is out of range. */
    private static Integer parseDigits(String s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            v = v * 10 + (s.charAt(i) - '0');
            if (v > Integer.MAX_VALUE) {
                return null;
            }
        }
        return (int) v;
    }

    /**
     * The recursive body of {@link #matchKeyword}. Integer fields are added to {@code fields}
     * innermost first, so in reverse template order, while {@code nfld[0]} counts every integer
     * field matched.
     */
    private static boolean matchInner(
            String test, int tt, String temp, int tp, int maxfld, List<Integer> fields, int[] nfld) {
        Front front = matchFront(test, tt, temp, tp);
        if (front == null) {
            return false;
        }
        int a = tt + front.ntest;
        int b = tp + front.ntemp;
        boolean matched = false;
        if (a >= test.length() && b >= temp.length()) {
            matched = true;
        } else if (b >= temp.length() && front.extend) {
            // The template has run out, but the field can take the rest of the test string if it
            // is all of the field's type.
            while (a < test.length() && matchChar(test.charAt(a), front.type)) {
                a++;
            }
            matched = a >= test.length();
        } else if (matchInner(test, a, temp, b, maxfld, fields, nfld)) {
            matched = true;
        } else if (front.extend) {
            // Widen the leading field one character at a time and try again.
            while (a < test.length() && matchChar(test.charAt(a), front.type)) {
                a++;
                if (matchInner(test, a, temp, b, maxfld, fields, nfld)) {
                    matched = true;
                    break;
                }
            }
        }
        if (matched && front.type == 'd' && a > tt) {
            if (nfld[0] < maxfld) {
                Integer v = parseDigits(test.substring(tt, a));
                if (v != null) {
                    fields.add(v);
                }
            }
            nfld[0]++;
        }
        return matched;
    }

    /**
     * Whether keyword name {@code test} matches {@code template}.
     *
     * @return null if it does not match, and otherwise the values of up to {@code maxfld} integer
     *     ({@code %d}) fields in the order they occur in the template
     */
    static List<Integer> matchKeyword(String test, String template, int maxfld) {
        List<Integer> fields = new ArrayList<>();
        if (!matchInner(test, 0, template, 0, maxfld, fields, new int[1])) {
            return null;
        }
        Collections.reverse(fields);
        return fields;
    }

    /**
     * {@link #matchKeyword} for a template a caller supplied, whose faults are errors.
     *
     * @param method the method named in an error
     * @return whether the keyword matches
     * @throws AstException with status {@code AST__BDFMT} if the template holds an illegal field
     *     type or ends in an incomplete field specifier, as C's {@code MatchChar} and {@code
     *     MatchFront} report
     */
    static boolean matchKeywordChecked(String test, String template, String method) {
        String[] b = BAD_TEMPLATE.get();
        b[0] = null;
        boolean matched = matchKeyword(test, template, 0) != null;
        String fault = b[0];
        b[0] = null;
        if (fault == null) {
            return matched;
        }
        // The fault is either the empty INCOMPLETE marker or the illegal type character.
        if (fault.isEmpty()) {
            throw Ast.err(
                    AstException.AST__BDFMT,
                    method + "(FitsChan): Incomplete field specifier found at end of filter template '" + template
                            + "'.");
        }
        throw Ast.err(
                AstException.AST__BDFMT,
                method + "(FitsChan): Illegal field type or width specifier '" + fault + "' found in filter template '"
                        + template + "'.");
    }
}
