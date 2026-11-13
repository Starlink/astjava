package uk.ac.starlink.ast;

import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Inversion of units trees.
 *
 * <p>Ported from Rust {@code units/invert.rs} and C {@code unit.c} ({@code InvertTree}).
 */
final class UnitsInvert {

    private UnitsInvert() {}

    /**
     * The inverse of the tree headed by {@code fwdnode}, with {@code src} as its one unit, or null
     * if the tree cannot be inverted (C {@code InvertTree}). Only a chain of exponentials,
     * natural logarithms, constant powers and multiplications by constants ending in one unit can
     * be inverted, which is the form a simplified tree in one unit takes. {@code src} becomes
     * part of the result.
     */
    static Node invertTree(Node fwdnode, Node src) {
        Node newnode = null;
        Node nextnode = null;
        Oper fop = fwdnode.opcode;
        if (fop == Oper.EXP) {
            // The inverse of exp(x) is ln(x).
            newnode = UnitsTree.newNode(Oper.LN);
            newnode.arg[0] = src;
            nextnode = fwdnode.arg[0];
        } else if (fop == Oper.LN) {
            // The inverse of ln(x) is exp(x).
            newnode = UnitsTree.newNode(Oper.EXP);
            newnode.arg[0] = src;
            nextnode = fwdnode.arg[0];
        } else if (fop == Oper.POW) {
            // The inverse of x**k is x**(1/k).
            newnode = UnitsTree.newNode(Oper.POW);
            newnode.arg[0] = src;
            newnode.arg[1] = UnitsTree.ldcon(1.0 / fwdnode.arg[1].con);
            nextnode = fwdnode.arg[0];
        } else if (fop == Oper.MULT) {
            // Only a product of a constant and a non-constant can be inverted. The inverse of k*x
            // is (1/k)*x, kept as a multiplication to keep the standard form.
            int varg;
            if (fwdnode.arg[0].con != AstObject.AST__BAD && fwdnode.arg[1].con == AstObject.AST__BAD) {
                varg = 1;
            } else if (fwdnode.arg[0].con == AstObject.AST__BAD && fwdnode.arg[1].con != AstObject.AST__BAD) {
                varg = 0;
            } else {
                varg = -1;
            }
            if (varg != -1) {
                newnode = UnitsTree.newNode(Oper.MULT);
                newnode.arg[0] = UnitsTree.ldcon(1.0 / fwdnode.arg[1 - varg].con);
                newnode.arg[1] = src;
                nextnode = fwdnode.arg[varg];
            }
        } else if (fop == Oper.LDVAR) {
            // Nothing is left to invert.
            return src;
        }
        if (nextnode != null && newnode != null) {
            return invertTree(nextnode, newnode);
        }
        return null;
    }
}
