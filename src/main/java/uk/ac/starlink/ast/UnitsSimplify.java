package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.UnitsTable.KnownUnit;
import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Simplification of units trees: folding of constants, inversion of unit coefficients,
 * substitution of the definitions of derived units, and the simplification that also puts a
 * tree into a standard form, so that equivalent trees can be compared.
 *
 * <p>Each function that C passes the address of a node pointer, so that it can replace the node,
 * here replaces the contents of the node it is given ({@link Node#assign}).
 *
 * <p>Ported from Rust {@code units/simplify.rs} and C {@code unit.c} ({@code RemakeTree}, {@code
 * FixConstants}, {@code InvertConstants}, {@code SimplifyTree}, {@code FindFactors} and {@code
 * CombineFactors}).
 */
final class UnitsSimplify {

    private UnitsSimplify() {}

    private static final double BAD = AstObject.AST__BAD;

    /**
     * Replaces each unit with a multiplier prefix by the product of the prefix's factor and the
     * unit, and each known derived unit by its definition, so that every unit in the tree is a
     * basic unit (C {@code RemakeTree}).
     */
    static void remakeTree(Node node) {
        Node newnode = null;
        if (node.opcode == Oper.LDVAR) {
            KnownUnit unit = node.unit;
            if (node.mult != null) {
                newnode = UnitsTree.newNode(Oper.MULT);
                newnode.arg[0] = UnitsTree.ldcon(1.0 / node.mult.scale);
                if (unit != null && unit.head != null) {
                    newnode.arg[1] = UnitsTree.copyTree(unit.head);
                } else {
                    newnode.arg[1] = UnitsTree.copyTree(node);
                    newnode.arg[1].mult = null;
                }
            } else if (unit != null && unit.head != null) {
                newnode = UnitsTree.copyTree(unit.head);
            }
        } else {
            for (Node a : node.arg) {
                remakeTree(a);
            }
        }
        if (newnode != null) {
            node.assign(newnode);
        }
    }

    /**
     * Replaces each sub-tree that combines only constants by a node loading the result (C {@code
     * FixConstants}). If {@code unity} is set, the constant of a multiplication of a constant and
     * a unit, square root or power is set to 1.
     *
     * @throws AstException with status {@code AST__BADUN} if a constant function has no value
     */
    static void fixConstants(Node node, boolean unity) {
        if (node == null || node.arg.length == 0) {
            return;
        }
        Oper op = node.opcode;
        boolean allcon = true;
        for (Node a : node.arg) {
            fixConstants(a, unity);
            if (a.con == BAD) {
                allcon = false;
            }
        }

        // In a simplified tree a constant argument of a multiplication is argument zero.
        if (unity
                && op == Oper.MULT
                && node.arg[0].con != BAD
                && (node.arg[1].opcode == Oper.LDVAR
                        || node.arg[1].opcode == Oper.SQRT
                        || node.arg[1].opcode == Oper.POW)) {
            node.arg[0].con = 1.0;
        }

        if (allcon) {
            double a0 = node.arg[0].con;
            double newcon = BAD;
            // MODERN-JAVA(21): a switch expression over op
            switch (op) {
                case LOG:
                    if (a0 > 0.0) {
                        newcon = Math.log10(a0);
                    } else {
                        throw Ast.err(
                                AstException.AST__BADUN,
                                "Illegal negative or zero constant value '" + CFormat.format("%g", a0)
                                        + "' encountered.");
                    }
                    break;
                case LN:
                    if (a0 > 0.0) {
                        newcon = Math.log(a0);
                    } else {
                        throw Ast.err(
                                AstException.AST__BADUN,
                                "Illegal negative or zero constant value '" + CFormat.format("%g", a0)
                                        + "' encountered.");
                    }
                    break;
                case EXP:
                    newcon = Math.exp(a0);
                    break;
                case SQRT:
                    if (a0 >= 0.0) {
                        newcon = Math.sqrt(a0);
                    } else {
                        throw Ast.err(
                                AstException.AST__BADUN,
                                "Illegal negative constant value '" + CFormat.format("%g", a0) + "' encountered.");
                    }
                    break;
                case POW: {
                    double a1 = node.arg[1].con;
                    if (a0 >= 0.0 || (int) a1 == a1) {
                        newcon = Math.pow(a0, a1);
                    } else {
                        throw Ast.err(
                                AstException.AST__BADUN,
                                "Illegal negative constant value '" + CFormat.format("%g", a0) + "' encountered.");
                    }
                    break;
                }
                case DIV:
                    if (node.arg[1].con != 0.0) {
                        newcon = a0 / node.arg[1].con;
                    } else {
                        throw Ast.err(AstException.AST__BADUN, "Illegal zero constant value encountered.");
                    }
                    break;
                case MULT:
                    newcon = a0 * node.arg[1].con;
                    break;
                default:
                    // C leaves the value bad for any other operation with arguments.
                    break;
            }
            node.assign(UnitsTree.ldcon(newcon));
        }
    }

    /**
     * Replaces the constant coefficients in a tree by their reciprocals (C {@code
     * InvertConstants}). A string such as "0.01 m" means "use units of 0.01 of a metre", so a
     * value in metres is converted to those units by dividing it by 0.01, not by multiplying.
     *
     * @throws AstException with status {@code AST__BADUN} for a zero coefficient or a variable
     *     exponent
     */
    static void invertConstants(Node node) {
        if (node == null || node.arg.length == 0) {
            return;
        }
        Oper op = node.opcode;
        boolean allcon = true;
        for (Node a : node.arg) {
            invertConstants(a);
            if (a.con == BAD) {
                allcon = false;
            }
        }
        if (allcon) {
            return;
        }
        if (op == Oper.MULT || op == Oper.DIV) {
            // A constant factor is a coefficient, as is a constant numerator ("2/m" is "2*m**-1");
            // a constant denominator is the reciprocal of one, and inverting it has the same
            // effect.
            for (int i = 0; i < 2; i++) {
                if (node.arg[i].con != BAD) {
                    if (node.arg[i].con != 0.0) {
                        node.arg[i].con = 1.0 / node.arg[i].con;
                    } else {
                        throw Ast.err(AstException.AST__BADUN, "Illegal zero constant encountered.");
                    }
                }
            }
        } else if (op == Oper.POW && node.arg[1].con == BAD) {
            // FITS WCS paper I requires a constant exponent.
            throw Ast.err(AstException.AST__BADUN, "Illegal variable exponent.");
        }
    }

    /**
     * Simplifies a tree, returning whether it changed (C {@code SimplifyTree}). If {@code std} is
     * set, the tree is also put into a standard form, so that equivalent trees compare equal,
     * which can make it larger.
     *
     * @throws AstException with status {@code AST__BADUN} if simplifying needs an undefined value
     */
    static boolean simplifyTree(Node node, boolean std) {
        boolean result = false;
        Node newnode = null;

        fixConstants(node, false);
        for (Node a : node.arg) {
            if (simplifyTree(a, std)) {
                result = true;
            }
        }

        Oper op = node.opcode;
        if (op == Oper.LN) {
            // ln(exp(x)) is x; in standard form, ln(x**k) is k*ln(x). A square root has already
            // become a power.
            if (node.arg[0].opcode == Oper.EXP) {
                newnode = UnitsTree.copyTree(node.arg[0].arg[0]);
            } else if (std && node.arg[0].opcode == Oper.POW) {
                newnode = UnitsTree.newNode(Oper.MULT);
                Node node2 = UnitsTree.newNode(Oper.LN);
                node2.arg[0] = UnitsTree.copyTree(node.arg[0].arg[0]);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[0].arg[1]);
                newnode.arg[1] = node2;
            }
        } else if (op == Oper.LOG) {
            // In standard form a common logarithm is a natural one divided by ln(10).
            if (std) {
                newnode = UnitsTree.newNode(Oper.DIV);
                Node node1 = UnitsTree.newNode(Oper.LN);
                node1.arg[0] = UnitsTree.copyTree(node.arg[0]);
                newnode.arg[0] = node1;
                newnode.arg[1] = UnitsTree.ldcon(Math.log(10.0));
            }
        } else if (op == Oper.EXP) {
            // exp(ln(x)) is x. A common logarithm has already become a natural one.
            if (node.arg[0].opcode == Oper.LN) {
                newnode = UnitsTree.copyTree(node.arg[0].arg[0]);
            }
        } else if (op == Oper.SQRT) {
            // In standard form a square root is a power of 0.5.
            if (std) {
                newnode = UnitsTree.newNode(Oper.POW);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[0]);
                newnode.arg[1] = UnitsTree.ldcon(0.5);
            }
        } else if (op == Oper.POW) {
            if (node.arg[0].opcode == Oper.EXP) {
                // (e**x)**k is e**(k*x).
                newnode = UnitsTree.newNode(Oper.EXP);
                Node node1 = UnitsTree.newNode(Oper.MULT);
                node1.arg[0] = UnitsTree.copyTree(node.arg[1]);
                node1.arg[1] = UnitsTree.copyTree(node.arg[0].arg[0]);
                newnode.arg[0] = node1;
            } else if (node.arg[1].con == 0.0) {
                newnode = UnitsTree.ldcon(1.0);
            } else if (MappingHelpers.astEqual(node.arg[1].con, 1.0)) {
                newnode = UnitsTree.copyTree(node.arg[0]);
            } else if (node.arg[0].opcode == Oper.POW) {
                // (x**k1)**k2 is x**(k1*k2).
                newnode = UnitsTree.newNode(Oper.POW);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[0].arg[0]);
                newnode.arg[1] = UnitsTree.ldcon(node.arg[0].arg[1].con * node.arg[1].con);
            } else if (std && node.arg[0].opcode == Oper.MULT) {
                // In standard form (x*y)**k is (x**k)*(y**k).
                newnode = UnitsTree.newNode(Oper.MULT);
                Node node1 = UnitsTree.newNode(Oper.POW);
                node1.arg[1] = UnitsTree.copyTree(node.arg[1]);
                Node node2 = UnitsTree.copyTree(node1);
                node1.arg[0] = UnitsTree.copyTree(node.arg[0].arg[0]);
                node2.arg[0] = UnitsTree.copyTree(node.arg[0].arg[1]);
                newnode.arg[0] = node1;
                newnode.arg[1] = node2;
            }
        } else if (op == Oper.DIV) {
            if (MappingHelpers.astEqual(node.arg[1].con, 1.0)) {
                newnode = UnitsTree.copyTree(node.arg[0]);
            } else if (node.arg[1].con != BAD) {
                // Division by a constant is multiplication by its reciprocal.
                if (node.arg[1].con != 0.0) {
                    newnode = UnitsTree.newNode(Oper.MULT);
                    newnode.arg[0] = UnitsTree.ldcon(1.0 / node.arg[1].con);
                    newnode.arg[1] = UnitsTree.copyTree(node.arg[0]);
                } else {
                    // The missing space is C's.
                    throw Ast.err(
                            AstException.AST__BADUN, "Simplifying a units expressionrequires a division by zero.");
                }
            } else if (std) {
                // In standard form x/y is x*(y**(-1)).
                newnode = UnitsTree.newNode(Oper.MULT);
                Node node1 = UnitsTree.newNode(Oper.POW);
                node1.arg[0] = UnitsTree.copyTree(node.arg[1]);
                node1.arg[1] = UnitsTree.ldcon(-1.0);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[0]);
                newnode.arg[1] = node1;
            }
        } else if (op == Oper.MULT) {
            if (node.arg[1].con != BAD) {
                // A constant goes first.
                newnode = UnitsTree.newNode(Oper.MULT);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[1]);
                newnode.arg[1] = UnitsTree.copyTree(node.arg[0]);
            } else if (node.arg[0].con == 0.0) {
                newnode = UnitsTree.ldcon(0.0);
            } else if (MappingHelpers.astEqual(node.arg[0].con, 1.0)) {
                newnode = UnitsTree.copyTree(node.arg[1]);
            } else if (std) {
                // In standard form a product is a coefficient followed by its factors, each
                // raised to its power, in order.
                Factors f = findFactors(node);
                newnode = combineFactors(f.list, f.powers, f.coeff);
            }
        }

        // A new node identical to the old one is no change.
        if (newnode != null) {
            if (UnitsCmp.cmpTree(newnode, node, true) == 0) {
                newnode = null;
            } else {
                result = true;
            }
        }
        if (newnode != null) {
            node.assign(newnode);
        }
        if (result) {
            simplifyTree(node, std);
        }
        return result;
    }

    /** The factors of a product, the power of each, and a constant coefficient. */
    static final class Factors {
        /** The factors, which are nodes of the tree analysed, not copies. */
        final List<Node> list = new ArrayList<>();

        /** The power of each factor. */
        final List<Double> powers = new ArrayList<>();

        /** The constant coefficient. */
        double coeff = 1.0;
    }

    /**
     * The factors of the tree headed by {@code node}, each with its power, and its constant
     * coefficient (C {@code FindFactors}).
     *
     * @throws AstException with status {@code AST__BADUN} if the coefficient has no real value
     */
    static Factors findFactors(Node node) {
        Factors f = new Factors();
        if (node.opcode == Oper.MULT || node.opcode == Oper.DIV) {
            boolean div = node.opcode == Oper.DIV;
            Factors f0 = findFactors(node.arg[0]);
            Factors f1 = findFactors(node.arg[1]);
            f.list.addAll(f0.list);
            f.powers.addAll(f0.powers);
            f.coeff = f0.coeff;
            // Combine the two lists, adding (or for a denominator subtracting) the power of a
            // factor already in the list.
            for (int i = 0; i < f1.list.size(); i++) {
                double p = f1.powers.get(i);
                int j = 0;
                boolean found = false;
                for (; j < f.list.size(); j++) {
                    if (UnitsCmp.cmpTree(f.list.get(j), f1.list.get(i), false) == 0) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    f.powers.set(j, div ? f.powers.get(j) - p : f.powers.get(j) + p);
                } else {
                    f.list.add(f1.list.get(i));
                    f.powers.add(div ? -p : p);
                }
            }
            if (!div) {
                f.coeff *= f1.coeff;
            } else if (f1.coeff != 0.0) {
                f.coeff /= f1.coeff;
            } else {
                // The missing space is C's.
                throw Ast.err(AstException.AST__BADUN, "Simplifying a units expressionrequires a division by zero.");
            }
        } else if (node.opcode == Oper.POW) {
            Factors f0 = findFactors(node.arg[0]);
            double con = node.arg[1].con;
            f.list.addAll(f0.list);
            for (double p : f0.powers) {
                f.powers.add(p * con);
            }
            if (f0.coeff >= 0.0 || (int) con == con) {
                f.coeff = Math.pow(f0.coeff, con);
            } else {
                throw Ast.err(
                        AstException.AST__BADUN,
                        // The spelling is C's.
                        "Simplifying a units expression requires a negative value to be raised to a "
                                + "non-intergal power."); // codespell:ignore intergal
            }
        } else if (node.opcode == Oper.SQRT) {
            Factors f0 = findFactors(node.arg[0]);
            f.list.addAll(f0.list);
            for (double p : f0.powers) {
                f.powers.add(p * 0.5);
            }
            if (f0.coeff >= 0.0) {
                f.coeff = Math.sqrt(f0.coeff);
            } else {
                throw Ast.err(
                        AstException.AST__BADUN,
                        "Simplifying a units expression requires the square root of a negative value to be taken.");
            }
        } else if (node.con != BAD) {
            f.coeff = node.con;
        } else {
            f.list.add(node);
            f.powers.add(1.0);
        }
        return f;
    }

    /**
     * A product of {@code coeff} and the factors raised to their powers, the factors sorted into
     * order (C {@code CombineFactors}). The lists are sorted in place.
     */
    static Node combineFactors(List<Node> factors, List<Double> powers, double coeff) {
        // A bubble sort, as there are only a few factors.
        int nfactor = factors.size();
        for (int i = nfactor - 1; i > 0; i--) {
            boolean done = true;
            for (int j = 0; j < i; j++) {
                if (UnitsCmp.cmpTree(factors.get(j), factors.get(j + 1), false) > 0) {
                    Node ftmp = factors.get(j);
                    factors.set(j, factors.get(j + 1));
                    factors.set(j + 1, ftmp);
                    Double ptmp = powers.get(j);
                    powers.set(j, powers.get(j + 1));
                    powers.set(j + 1, ptmp);
                    done = false;
                }
            }
            if (done) {
                break;
            }
        }

        // The first term is the coefficient, unless it is 1.
        Node node1 = coeff != 1.0 ? UnitsTree.ldcon(coeff) : null;
        Node result = null;
        for (int i = 0; i < nfactor; i++) {
            double p = powers.get(i);
            if (p == 0.0) {
                continue;
            }
            Node node2;
            if (MappingHelpers.astEqual(p, 1.0)) {
                node2 = UnitsTree.copyTree(factors.get(i));
            } else {
                node2 = UnitsTree.newNode(Oper.POW);
                node2.arg[0] = UnitsTree.copyTree(factors.get(i));
                node2.arg[1] = UnitsTree.ldcon(p);
            }
            if (node1 != null) {
                result = UnitsTree.newNode(Oper.MULT);
                result.arg[0] = node1;
                result.arg[1] = node2;
                node1 = result;
            } else {
                node1 = node2;
            }
        }
        if (result == null) {
            result = node1;
        }
        if (result == null) {
            result = UnitsTree.ldcon(1.0);
        }
        return result;
    }
}
