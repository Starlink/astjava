package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.UnitsTable.KnownUnit;
import uk.ac.starlink.ast.UnitsTable.Multiplier;
import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Parsing of units expressions into trees.
 *
 * <p>The parser scans an expression for the operator of lowest precedence outside parentheses,
 * splits the expression there, and parses each side in turn. As in C, a part of an expression is
 * given by the whole string, its start and its length, and the parser may read characters beyond
 * the part's end where C does, up to the end of the whole string.
 *
 * <p>Ported from Rust {@code units/parser.rs} and C {@code unit.c} ({@code CreateTree}, {@code
 * CleanExp}, {@code MakeTree}, {@code SplitUnit}, {@code ConStart}, {@code EndsWith}, {@code
 * Ustrcmp} and {@code Ustrncmp}). Where the Rust port departs from C (the case of function names,
 * the reading of numbers, the opcodes of pi and e), this follows C.
 */
final class UnitsParser {

    private UnitsParser() {}

    /** A unit name split into a multiplier prefix and a unit (C {@code SplitUnit}). */
    static final class Split {
        /** The multiplier prefix, or null if there is none. */
        final Multiplier mult;

        /** The length of the unit symbol or label matched. */
        final int l;

        Split(Multiplier mult, int l) {
            this.mult = mult;
            this.l = l;
        }
    }

    /** The character at {@code i}, or C's terminating null beyond the end of {@code s}. */
    static char ch(String s, int i) {
        return i >= 0 && i < s.length() ? s.charAt(i) : '\0';
    }

    /** C's {@code isdigit} in the C locale. */
    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** C's {@code isalpha} in the C locale. */
    static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** C's {@code ISWORD} macro: alphanumeric, '+' or '-'. */
    private static boolean isWord(char c) {
        return isDigit(c) || isAlpha(c) || c == '+' || c == '-';
    }

    /** C's {@code toupper} in the C locale. */
    private static char toUpper(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
    }

    /**
     * Whether the first {@code n} characters from {@code a[ai]} and {@code b[bi]} match without
     * regard to case, as C's {@code Ustrncmp} returning zero: the comparison stops at the end of
     * either string, and matches only if both end there.
     */
    static boolean ustrnEq(String a, int ai, String b, int bi, int n) {
        for (int i = 0; i < n; i++) {
            char ca = ch(a, ai + i);
            char cb = ch(b, bi + i);
            if (ca == '\0' || cb == '\0') {
                return ca == cb;
            }
            if (toUpper(ca) != toUpper(cb)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code a} and {@code b} match without regard to case (C {@code Ustrcmp} returning zero). */
    static boolean ustrEq(String a, String b) {
        return a.length() == b.length() && ustrnEq(a, 0, b, 0, a.length());
    }

    /**
     * The tree of the units expression {@code exp}, in which, if {@code basic} is set, every
     * known derived unit is replaced by its definition in basic units; null for a blank
     * expression (C {@code CreateTree}).
     *
     * @throws AstException with status {@code AST__BADUN} if the expression cannot be parsed
     */
    static Node createTree(String exp, boolean basic) {
        return createTree(exp, basic, UnitsTable.knownUnits());
    }

    /** {@link #createTree(String, boolean)} with the known units given, as they stand while the table is built. */
    static Node createTree(String exp, boolean basic, List<KnownUnit> units) {
        String cleanex = cleanExp(exp);
        if (cleanex.isEmpty()) {
            return null;
        }
        Node result = makeTree(cleanex, 0, cleanex.length(), units);
        UnitsSimplify.fixConstants(result, false);
        UnitsSimplify.invertConstants(result);
        if (basic) {
            UnitsSimplify.remakeTree(result);
        }
        return result;
    }

    /**
     * A clean copy of a units expression (C {@code CleanExp}): common non-standard forms are
     * replaced by standard ones, leading and trailing white space is removed, runs of white space
     * become one space, and spaces next to an operator or parenthesis are removed, since the
     * parser reads a space as multiplication.
     */
    static String cleanExp(String exp) {
        // Split the string into tokens: runs of alphanumeric characters (counting + and - as
        // alphanumeric) and runs of other characters.
        List<String> tok = new ArrayList<>();
        int start = 0;
        boolean word = isWord(ch(exp, 0));
        for (int p = 0; p < exp.length(); p++) {
            char c = exp.charAt(p);
            if (word) {
                if (!isWord(c)) {
                    tok.add(exp.substring(start, p));
                    start = p;
                    word = false;
                }
            } else if (isWord(c)) {
                tok.add(exp.substring(start, p));
                start = p;
                word = true;
            }
        }
        tok.add(exp.substring(start));

        // Replace known non-standard unit syntax.
        StringBuilder joined = new StringBuilder();
        for (String t : tok) {
            int l = t.length();
            if (l > 1 && t.charAt(0) != '-' && t.charAt(0) != '+' && firstDigit(t) == l - 1) {
                // A word followed by a digit is <word>^<digit>, and a word followed by a sign and a
                // digit is <word>^<sign><digit>.
                char w = t.charAt(l - 2);
                if (w != '+' && w != '-') {
                    joined.append(t, 0, l - 1).append('^').append(t, l - 1, l);
                } else {
                    joined.append(t, 0, l - 2).append('^').append(t, l - 2, l);
                }
            } else if (t.contains("micron")) {
                // "micron" becomes "(<start>m*1.0E-6)", which suits things like "Kmicron".
                joined.append('(').append(t, 0, t.indexOf("micron")).append("m*1.0E-6)");
            } else if (ustrEq(t, "STER")) {
                joined.append("sr");
            } else if (l == 3 && "JY".equals(t.substring(1))) {
                // "<c>JY" becomes "<c>Jy", as in "MJY".
                joined.append(t.charAt(0)).append("Jy");
            } else if (ustrnEq(t, 0, "nano", 0, 4)) {
                // "nano" becomes "n" here because scanf reads "nan" as a NaN.
                joined.append('n').append(t, 4, l);
            } else {
                joined.append(t);
            }
        }

        // Remove leading white space and replace runs of white space by one character.
        StringBuilder r = new StringBuilder();
        boolean ps = true;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (CScan.isCSpace(c)) {
                if (!ps) {
                    r.append(c);
                    ps = true;
                }
            } else {
                r.append(c);
                ps = false;
            }
        }
        if (ps && r.length() > 0) {
            r.setLength(r.length() - 1);
        }

        // Remove spaces next to an operator or parenthesis.
        StringBuilder w = new StringBuilder();
        ps = false;
        boolean po = false;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (CScan.isCSpace(c)) {
                if (!po) {
                    w.append(c);
                    po = true;
                    ps = true;
                }
            } else if (c == '*' || c == '/' || c == '^' || c == '.' || c == ')' || c == '(') {
                if (ps) {
                    w.setLength(w.length() - 1);
                }
                w.append(c);
                po = true;
                ps = false;
            } else {
                w.append(c);
                po = false;
                ps = false;
            }
        }
        if (ps) {
            w.setLength(w.length() - 1);
        }
        return w.toString();
    }

    /** The index of the first decimal digit in {@code t}, or its length (C {@code strcspn}). */
    private static int firstDigit(String t) {
        int i = 0;
        while (i < t.length() && !isDigit(t.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * The numerical constant at the start of {@code text[from..]}, as C's {@code ConStart} reads
     * it with {@code sscanf("%lf%n")}, or null if there is none. The result's {@code end} is the
     * index just after the characters read. A leading "n" or "N" is never read as a number.
     */
    static CScan.Num conStart(String text, int from) {
        int i = from;
        while (i < text.length() && CScan.isCSpace(text.charAt(i))) {
            i++;
        }
        CScan.Num n = CScan.strtod(text, i);
        if (n == null || ch(text, i) == 'n' || ch(text, i) == 'N') {
            return null;
        }
        return n;
    }

    /**
     * Whether the {@code nc} characters ending at {@code s[c]} end with {@code test}, without
     * regard to case (C {@code EndsWith}).
     */
    private static boolean endsWith(String s, int c, int nc, String test) {
        int tlen = test.length();
        if (tlen > nc) {
            return false;
        }
        int start = c - tlen + 1;
        for (int i = 0; i < tlen; i++) {
            if (CScan.toLower(ch(s, start + i)) != CScan.toLower(test.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the {@code ls} characters of {@code str} from {@code start} are the unit symbol or
     * label {@code u}, preceded by nothing or by a multiplier symbol or name (C {@code
     * SplitUnit}). {@code u} is matched with regard to case if {@code cs} is set; a multiplier
     * symbol is matched first with regard to case, then without, and a multiplier name without.
     *
     * @return the multiplier found and the length of {@code u}, or null if there is no match
     */
    static Split splitUnit(String str, int start, int ls, String u, boolean cs) {
        int lu = u.length();
        int lm = ls - lu;
        if (lm < 0) {
            return null;
        }
        boolean tail = cs ? str.regionMatches(start + lm, u, 0, lu) : ustrnEq(str, start + lm, u, 0, lu);
        if (!tail) {
            return null;
        }
        if (lm == 0) {
            return new Split(null, lu);
        }
        List<Multiplier> mults = UnitsTable.multipliers();
        for (Multiplier m : mults) {
            if (m.sym.length() == lm && str.regionMatches(start, m.sym, 0, lm)) {
                return new Split(m, lu);
            }
        }
        for (Multiplier m : mults) {
            if (m.sym.length() == lm && ustrnEq(str, start, m.sym, 0, lm)) {
                return new Split(m, lu);
            }
        }
        for (Multiplier m : mults) {
            if (m.label.length() == lm && ustrnEq(str, start, m.label, 0, lm)) {
                return new Split(m, lu);
            }
        }
        return null;
    }

    /** The text C copies into its error messages: up to {@code n} characters from {@code s[c]}. */
    static String opText(String s, int c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n && ch(s, c + i) != '\0'; i++) {
            b.append(ch(s, c + i));
        }
        return b.toString();
    }

    /**
     * The tree of the {@code nc} characters of the cleaned expression {@code s} from {@code off},
     * whose units refer to the symbols in the expression rather than to their definitions; null
     * if {@code nc} is not positive (C {@code MakeTree}).
     *
     * @throws AstException with status {@code AST__BADUN} if the expression cannot be parsed
     */
    static Node makeTree(String s, int off, int nc, List<KnownUnit> units) {
        if (nc <= 0) {
            return null;
        }

        // Scan from the end for the last multiplication or division at depth zero, so that
        // "a/b/c" is read as "(a/b)/c".
        Oper op = Oper.NULL;
        int oplen = 1;
        int depth = 0;
        int c = off + nc - 1;
        int i = nc - 1;
        while (i >= 0) {
            char cc = ch(s, c);
            if (cc == '(') {
                depth--;
                if (depth < 0) {
                    throw Ast.err(AstException.AST__BADUN, "Missing closing parenthesis.");
                }
                // An opening parenthesis at depth zero that is not first, and follows no function
                // name and no operator, has an implicit multiplication before it.
                if (depth == 0 && i > 0) {
                    char d = ch(s, c - 1);
                    if (d != '*'
                            && d != '/'
                            && d != '^'
                            && d != '.'
                            && d != ' '
                            && !endsWith(s, c, i + 1, "sqrt(")
                            && !endsWith(s, c, i + 1, "exp(")
                            && !endsWith(s, c, i + 1, "ln(")
                            && !endsWith(s, c, i + 1, "log(")) {
                        op = Oper.MULT;
                        oplen = 0;
                        break;
                    }
                }
            } else if (cc == ')') {
                depth++;
                // A closing parenthesis at depth zero that is not last, and is followed by no
                // operator, has an implicit multiplication after it.
                if (depth == 1 && i < nc - 1) {
                    char d = ch(s, c + 1);
                    if (d != '*' && d != '/' && d != '^' && d != '.' && d != ' ') {
                        op = Oper.MULT;
                        oplen = 0;
                        // The left operand ends, and the right one starts, after the parenthesis.
                        i++;
                        c++;
                        break;
                    }
                }
            } else if (depth == 0) {
                if (cc == '/') {
                    op = Oper.DIV;
                } else if (cc == ' ') {
                    op = Oper.MULT;
                } else if (cc == '*') {
                    // An asterisk next to another asterisk is part of "**".
                    if (c == off) {
                        if (ch(s, c + 1) != '*') {
                            op = Oper.MULT;
                        }
                    } else if (i == nc - 1) {
                        if (ch(s, c - 1) != '*') {
                            op = Oper.MULT;
                        }
                    } else if (ch(s, c + 1) != '*' && ch(s, c - 1) != '*') {
                        op = Oper.MULT;
                    }
                } else if (cc == '.'
                        && (c == off || !isDigit(ch(s, c - 1)))
                        && (i == nc - 1 || !isDigit(ch(s, c + 1)))) {
                    // A dot is a multiplication unless it is between two digits.
                    op = Oper.MULT;
                }
            }
            if (op != Oper.NULL) {
                break;
            }
            i--;
            c--;
        }

        if (op != Oper.NULL) {
            Node result = UnitsTree.newNode(op);
            result.arg[0] = makeTree(s, off, i, units);
            if (result.arg[0] == null) {
                throw Ast.err(AstException.AST__BADUN, "Missing operand before '" + opText(s, c, oplen) + "'.");
            }
            result.arg[1] = makeTree(s, c + oplen, nc - i - oplen, units);
            if (result.arg[1] == null) {
                throw Ast.err(AstException.AST__BADUN, "Missing operand after '" + opText(s, c, oplen) + "'.");
            }
            return result;
        }
        if (depth > 0) {
            throw Ast.err(AstException.AST__BADUN, "Missing opening parenthesis.");
        }

        // Scan from the start for the first exponentiation at depth zero.
        depth = 0;
        c = off;
        i = 0;
        while (i < nc && ch(s, c) != '\0') {
            char cc = ch(s, c);
            if (cc == '(') {
                depth++;
            } else if (cc == ')') {
                depth--;
                if (depth < 0) {
                    throw Ast.err(AstException.AST__BADUN, "Missing opening parenthesis.");
                }
            } else if (depth == 0) {
                if (s.startsWith("**", c)) {
                    op = Oper.POW;
                    oplen = 2;
                } else if (cc == '^') {
                    op = Oper.POW;
                    oplen = 1;
                }
                if (op != Oper.NULL) {
                    break;
                }
            }
            i++;
            c++;
        }
        if (op != Oper.NULL) {
            Node result = UnitsTree.newNode(op);
            result.arg[0] = makeTree(s, off, i, units);
            if (result.arg[0] == null) {
                throw Ast.err(AstException.AST__BADUN, "Missing operand before '" + opText(s, c, oplen) + "'.");
            }
            result.arg[1] = makeTree(s, c + oplen, nc - i - oplen, units);
            if (result.arg[1] == null) {
                throw Ast.err(AstException.AST__BADUN, "Missing operand after '" + opText(s, c, oplen) + "'.");
            }
            return result;
        }

        // With no binary operator at depth zero, the expression may start with a function name.
        if (s.startsWith("sqrt(", off) || s.startsWith("SQRT(", off)) {
            op = Oper.SQRT;
            oplen = 4;
        } else if (s.startsWith("exp(", off) || s.startsWith("EXP(", off)) {
            op = Oper.EXP;
            oplen = 3;
        } else if (s.startsWith("ln(", off) || s.startsWith("LN(", off)) {
            op = Oper.LN;
            oplen = 2;
        } else if (s.startsWith("log(", off) || s.startsWith("LOG(", off)) {
            op = Oper.LOG;
            oplen = 3;
        }
        if (op != Oper.NULL) {
            Node result = UnitsTree.newNode(op);
            result.arg[0] = makeTree(s, off + oplen, nc - oplen, units);
            if (result.arg[0] == null) {
                throw Ast.err(AstException.AST__BADUN, "Missing argument for '" + opText(s, c, oplen) + "'.");
            }
            return result;
        }

        // An expression enclosed in parentheses.
        if (ch(s, off) == '(' && ch(s, off + nc - 1) == ')') {
            return makeTree(s, off + 1, nc - 2, units);
        }

        // A numerical constant, which must be the whole expression.
        CScan.Num num = conStart(s, off);
        if (num != null) {
            int n = num.end - off;
            if (n == nc) {
                return UnitsTree.ldcon(num.value);
            }
            throw Ast.err(
                    AstException.AST__BADUN, "Missing operator after numerical string '" + opText(s, off, n) + "'.");
        }

        // The named constants.
        if (nc == 2 && (s.startsWith("pi", off) || s.startsWith("PI", off))) {
            return UnitsTree.newNode(Oper.LDPI);
        }
        if (nc == 1 && (ch(s, off) == 'e' || ch(s, off) == 'E')) {
            return UnitsTree.newNode(Oper.LDE);
        }

        // The only possibility left is a unit, possibly with a multiplier prefix. Find the
        // longest known unit symbol the string ends with, then the longest symbol without regard
        // to case, then the longest name without regard to case. Failing those, try again
        // without a final "s", which may be a plural as in "Angstroms".
        KnownUnit munit;
        Multiplier mmult = null;
        boolean plural = false;
        while (true) {
            munit = null;
            int maxlen = -1;
            for (KnownUnit unit : units) {
                Split sp = splitUnit(s, off, nc, unit.sym, true);
                if (sp != null && sp.l > maxlen) {
                    maxlen = sp.l;
                    munit = unit;
                    mmult = sp.mult;
                }
            }
            if (munit == null) {
                for (KnownUnit unit : units) {
                    Split sp = splitUnit(s, off, nc, unit.sym, false);
                    if (sp != null && sp.l > maxlen) {
                        maxlen = sp.l;
                        munit = unit;
                        mmult = sp.mult;
                    }
                }
            }
            if (munit == null) {
                for (KnownUnit unit : units) {
                    Split sp = splitUnit(s, off, nc, unit.label, false);
                    if (sp != null && sp.l > maxlen) {
                        maxlen = sp.l;
                        munit = unit;
                        mmult = sp.mult;
                    }
                }
            }
            char last = ch(s, off + nc - 1);
            if (munit == null && nc > 1 && !plural && (last == 's' || last == 'S')) {
                plural = true;
                nc--;
            } else {
                break;
            }
        }
        if (plural) {
            nc++;
        }

        if (munit != null) {
            // An alias is replaced by the unit it stands for.
            Node result = UnitsTree.newNode(Oper.LDVAR);
            result.unit = munit.use != null ? munit.use : munit;
            result.mult = mmult;
            result.name = result.unit.sym;
            return result;
        }

        // Otherwise the string is a new basic unit, possibly with a multiplier prefix that is not
        // the whole string.
        Multiplier mult = null;
        int start = off;
        int n = nc;
        for (Multiplier m : UnitsTable.multipliers()) {
            int rest = nc - m.sym.length();
            if (rest > 0 && s.startsWith(m.sym, off)) {
                mult = m;
                start = off + m.sym.length();
                n = rest;
                break;
            }
        }
        for (int k = 0; k < n; k++) {
            char x = ch(s, start + k);
            if (!isAlpha(x)) {
                throw Ast.err(AstException.AST__BADUN, "Illegal character '" + x + "' found.");
            }
        }
        Node result = UnitsTree.newNode(Oper.LDVAR);
        result.mult = mult;
        result.name = s.substring(start, start + n);
        return result;
    }
}
