package uk.ac.starlink.ast;

import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Axis labels rewritten for a change of units. A label such as "Frequency" is parsed into a tree
 * whose one unit is the basic label; joined to the tree that converts the old units to the new
 * ones and simplified, it is printed in the label style of {@link UnitsToExpr#makeExp}, so that
 * the logarithm of a wavelength is labelled "log( wavelength )".
 *
 * <p>Ported from Rust {@code units/label.rs} and C {@code unit.c} ({@code MakeLabelTree}).
 */
final class UnitsLabel {

    private UnitsLabel() {}

    /**
     * The tree of the {@code nc} characters of the label {@code lab} from {@code off}, without
     * surrounding white space, whose one unit is the basic label; null if {@code nc} is zero (C
     * {@code MakeLabelTree}). Exponentiation outside parentheses splits the label, then a leading
     * function name, then enclosing parentheses, then a numerical constant; anything else is the
     * basic label.
     *
     * @throws AstException with status {@code AST__BADUN} if the label cannot be parsed
     */
    static Node makeLabelTree(String lab, int off, int nc) {
        if (nc == 0) {
            return null;
        }
        int exp = off;
        while (CScan.isCSpace(UnitsParser.ch(lab, exp))) {
            exp++;
        }
        int c = off + nc - 1;
        while (c >= exp && CScan.isCSpace(UnitsParser.ch(lab, c))) {
            c--;
        }
        nc = c - exp + 1;

        // The first exponentiation at depth zero, ignoring spaces.
        int depth = 0;
        c = exp;
        int i = 0;
        Oper op = Oper.NULL;
        int oplen = 0;
        while (i < nc && UnitsParser.ch(lab, c) != '\0') {
            char cc = UnitsParser.ch(lab, c);
            if (cc == '(') {
                depth++;
            } else if (cc == ')') {
                depth--;
                if (depth < 0) {
                    throw Ast.err(AstException.AST__BADUN, "Missing opening parenthesis.");
                }
            } else if (depth == 0 && !CScan.isCSpace(cc)) {
                if (lab.startsWith("**", c)) {
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
            result.arg[0] = makeLabelTree(lab, exp, i);
            if (result.arg[0] == null) {
                throw Ast.err(
                        AstException.AST__BADUN, "Missing operand before '" + UnitsParser.opText(lab, c, oplen) + "'.");
            }
            result.arg[1] = makeLabelTree(lab, c + oplen, nc - i - oplen);
            if (result.arg[1] == null) {
                throw Ast.err(
                        AstException.AST__BADUN, "Missing operand after '" + UnitsParser.opText(lab, c, oplen) + "'.");
            }
            return result;
        }

        // A function of one argument.
        if (lab.startsWith("sqrt(", exp) || lab.startsWith("SQRT(", exp)) {
            op = Oper.SQRT;
            oplen = 4;
        } else if (lab.startsWith("exp(", exp) || lab.startsWith("EXP(", exp)) {
            op = Oper.EXP;
            oplen = 3;
        } else if (lab.startsWith("ln(", exp) || lab.startsWith("LN(", exp)) {
            op = Oper.LN;
            oplen = 2;
        } else if (lab.startsWith("log(", exp) || lab.startsWith("LOG(", exp)) {
            op = Oper.LOG;
            oplen = 3;
        }
        if (op != Oper.NULL) {
            Node result = UnitsTree.newNode(op);
            result.arg[0] = makeLabelTree(lab, exp + oplen, nc - oplen);
            if (result.arg[0] == null) {
                throw Ast.err(
                        AstException.AST__BADUN, "Missing argument for '" + UnitsParser.opText(lab, c, oplen) + "'.");
            }
            return result;
        }

        // A label enclosed in parentheses.
        if (nc > 0 && UnitsParser.ch(lab, exp) == '(' && UnitsParser.ch(lab, exp + nc - 1) == ')') {
            return makeLabelTree(lab, exp + 1, nc - 2);
        }

        // A numerical constant, which must be the whole label.
        CScan.Num num = UnitsParser.conStart(lab, exp);
        if (num != null) {
            int n = num.end - exp;
            if (n == nc) {
                return UnitsTree.ldcon(num.value);
            }
            throw Ast.err(
                    AstException.AST__BADUN,
                    "Missing operator after numerical string '" + UnitsParser.opText(lab, exp, n) + "'.");
        }

        // The basic label.
        return UnitsTree.ldvar(lab.substring(exp, exp + nc));
    }

    /** The tree as an axis label (C {@code MakeExp(tree, 0, 1)}), or null where C gives none. */
    static String makeLabelExp(Node tree) {
        return UnitsToExpr.makeExp(tree, UnitsToExpr.LABEL, true);
    }
}
