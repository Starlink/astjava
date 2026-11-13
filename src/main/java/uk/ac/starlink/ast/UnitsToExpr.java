package uk.ac.starlink.ast;

import uk.ac.starlink.ast.UnitsTable.Multiplier;
import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Formatting of units trees as expressions: as an axis label, as an expression for a MathMap, or
 * as a units string.
 *
 * <p>Ported from Rust {@code units/to_expr.rs} and {@code units/label.rs} and C {@code unit.c}
 * ({@code MakeExp} and {@code ModifyPrefix}). As in C, every level of the recursion complicates
 * its own copy of its tree before formatting it.
 */
final class UnitsToExpr {

    /** C's {@code mathmap} argument for an axis label. */
    static final int LABEL = 0;

    /** C's {@code mathmap} argument for an expression for the MathMap parser. */
    static final int MATHMAP = 1;

    /** C's {@code mathmap} argument for a units string. */
    static final int UNITS = 2;

    /** {@code AST__DBL_DIG}, the significant digits with which constants are formatted. */
    private static final int DBL_DIG = 17;

    private UnitsToExpr() {}

    /**
     * The tree headed by {@code tree} as an expression (C {@code MakeExp}), in one of three
     * styles: {@link #LABEL}, an axis label, which omits simple scale factors, since a scaled
     * wavelength is still a wavelength; {@link #MATHMAP}, an expression for the MathMap parser,
     * in which "log10" is the common and "log" the natural logarithm; and {@link #UNITS}, a units
     * string, in which "log" is the common and "ln" the natural logarithm, each unit keeps its
     * multiplier prefix, and a constant multiplying or dividing a unit is folded into the prefix
     * where that is closer. {@code top} is set for the head of the whole tree.
     *
     * @return the expression, or null where C returns none, as for a label that is only a
     *     product of constants
     */
    static String makeExp(Node tree, int mathmap, boolean top) {
        Node newtree = UnitsTree.copyTree(tree);
        UnitsComplicate.complicateTree(newtree);

        if (mathmap == LABEL) {
            // Set to 1 each constant multiplying a unit, square root or power, and simplify away
            // the factors of 1 (without restoring the standard form), until nothing changes.
            UnitsSimplify.fixConstants(newtree, true);
            while (UnitsSimplify.simplifyTree(newtree, false)) {
                UnitsSimplify.fixConstants(newtree, true);
            }
        }

        if (newtree.con != AstObject.AST__BAD) {
            return CFormat.format("%." + DBL_DIG + "g", newtree.con);
        }
        // MODERN-JAVA(21): a switch expression over the operation
        switch (newtree.opcode) {
            case LDVAR:
                // A unit within a label has a space before and after it.
                if (mathmap == LABEL && !top) {
                    return " " + newtree.name + " ";
                } else if (mathmap == UNITS) {
                    return (newtree.mult != null ? newtree.mult.sym : "") + newtree.name;
                } else {
                    return newtree.name;
                }
            case LOG:
                return (mathmap == MATHMAP ? "log10(" : "log(") + arg(newtree, 0, mathmap) + ")";
            case LN:
                return (mathmap == MATHMAP ? "log(" : "ln(") + arg(newtree, 0, mathmap) + ")";
            case EXP:
                return "exp(" + arg(newtree, 0, mathmap) + ")";
            case SQRT:
                return "sqrt(" + arg(newtree, 0, mathmap) + ")";
            case POW: {
                // The exponent is a constant. The base is put in parentheses if it has two
                // arguments, or if it is a unit within a label, which has spaces around it.
                String arg0 = arg(newtree, 0, mathmap);
                String arg1 = arg(newtree, 1, mathmap);
                if (newtree.arg[0].arg.length == 2 || (newtree.arg[0].opcode == Oper.LDVAR && mathmap == LABEL)) {
                    return "(" + arg0 + ")**" + arg1;
                }
                return arg0 + "**" + arg1;
            }
            case DIV: {
                Node sunit = mathmap == UNITS ? modifyPrefix(newtree) : null;
                if (sunit != null) {
                    return makeExp(sunit, mathmap, false);
                }
                // The denominator is put in parentheses if it is a product.
                String arg0 = arg(newtree, 0, mathmap);
                String arg1 = arg(newtree, 1, mathmap);
                if (newtree.arg[1].opcode == Oper.MULT && arg1.indexOf('*') >= 0) {
                    return arg0 + "/(" + arg1 + ")";
                }
                return arg0 + "/" + arg1;
            }
            case MULT: {
                Node sunit = mathmap == UNITS ? modifyPrefix(newtree) : null;
                if (sunit != null) {
                    return makeExp(sunit, mathmap, false);
                }
                String arg0 = makeExp(newtree.arg[0], mathmap, false);
                String arg1 = makeExp(newtree.arg[1], mathmap, false);
                // A label carries no constant factors.
                if (top && mathmap == LABEL) {
                    if (newtree.arg[0].con != AstObject.AST__BAD) {
                        arg0 = null;
                    }
                    if (newtree.arg[1].con != AstObject.AST__BAD) {
                        arg1 = null;
                    }
                }
                if (arg0 != null && arg1 != null) {
                    // The first argument is put in parentheses if it is a quotient or power.
                    if ((newtree.arg[0].opcode == Oper.DIV && arg0.indexOf('/') >= 0)
                            || (newtree.arg[0].opcode == Oper.POW && arg0.contains("**"))) {
                        return "(" + arg0 + ")*" + arg1;
                    }
                    return arg0 + "*" + arg1;
                }
                return arg0 != null ? arg0 : arg1;
            }
            case LDCON:
            case LDPI:
            case LDE:
            case NULL:
                // C formats nothing for a node with no constant and no operation it knows.
                return null;
            default:
                throw Ast.err(AstException.AST__INTER, "MakeExp(Unit): unexpected operation " + newtree.opcode);
        }
    }

    /** Argument {@code i} of {@code node} formatted, or "" where C formats nothing. */
    private static String arg(Node node, int i, int mathmap) {
        String s = makeExp(node.arg[i], mathmap, false);
        return s != null ? s : "";
    }

    /**
     * The node {@code old}, a product or quotient of a constant and a unit, with the constant
     * folded into the unit's multiplier prefix, choosing the prefix closest to the combined
     * factor; null if {@code old} has no such form or would not change (C {@code ModifyPrefix}).
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // identity, as C compares pointers
    static Node modifyPrefix(Node old) {
        if (old.opcode != Oper.DIV && old.opcode != Oper.MULT) {
            return null;
        }
        Node newtree = UnitsTree.copyTree(old);
        Node ldvar;
        if (newtree.arg[0].opcode == Oper.LDVAR) {
            ldvar = newtree.arg[0];
        } else if (newtree.arg[1].opcode == Oper.LDVAR) {
            ldvar = newtree.arg[1];
        } else {
            ldvar = null;
        }
        Node ldcon;
        if (newtree.arg[0].opcode == Oper.LDCON) {
            ldcon = newtree.arg[0];
        } else if (newtree.arg[1].opcode == Oper.LDCON) {
            ldcon = newtree.arg[1];
        } else {
            ldcon = null;
        }
        if (ldvar == null || ldcon == null) {
            return null;
        }

        // The factor by which the unit without its prefix is multiplied. A unit in the
        // denominator of a quotient makes the node its reciprocal.
        double cmult = ldvar.mult != null ? ldvar.mult.scale : 1.0;
        double con = ldcon.con;
        boolean recip;
        if (newtree.opcode == Oper.MULT) {
            con = con * cmult;
            recip = false;
        } else {
            con = cmult / con;
            recip = ldvar == newtree.arg[1];
        }

        // The closest known multiplier; none if unity is closest.
        double rmin = con > 1 ? con : 1.0 / con;
        Multiplier mmult = null;
        for (Multiplier mult : UnitsTable.multipliers()) {
            double r = con > mult.scale ? con / mult.scale : mult.scale / con;
            if (r < rmin) {
                mmult = mult;
                rmin = r;
            }
        }
        if (mmult != null) {
            con = con / mmult.scale;
        }

        boolean changed = false;
        if (ldvar.mult != mmult) {
            ldvar.mult = mmult;
            changed = true;
        }
        // For a reciprocal the constant node holds the numerator, whose change is judged once it
        // is recomputed below.
        if (ldcon.con != con) {
            ldcon.con = con;
            if (!recip) {
                changed = true;
            }
        }

        Node result;
        if (!recip) {
            if (newtree.opcode != Oper.MULT) {
                newtree.opcode = Oper.MULT;
                changed = true;
            }
            // A factor of 1 leaves the unit alone.
            if (Math.abs(con - 1.0) < 1.0E-6) {
                result = UnitsTree.copyTree(ldvar);
                changed = true;
            } else {
                result = newtree;
            }
        } else {
            // The numerator is the reciprocal of the factor found above.
            ldcon.con = 1.0 / ldcon.con;
            if (!MappingHelpers.astEqual(ldcon.con, old.arg[0].con)) {
                changed = true;
            }
            result = newtree;
        }
        return changed ? result : null;
    }
}
