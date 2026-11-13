package uk.ac.starlink.ast;

import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Undoing the standard form of a units tree, so that the expressions made from it read more
 * naturally.
 *
 * <p>Ported from Rust {@code units/complicate.rs} and C {@code unit.c} ({@code ComplicateTree}).
 */
final class UnitsComplicate {

    private UnitsComplicate() {}

    /** C's {@code NINT} macro: the nearest integer, halves rounded away from zero. */
    private static int nint(double x) {
        return (int) (x + (x > 0.0 ? 0.5 : -0.5));
    }

    /**
     * Undoes some of the standardisation {@link UnitsSimplify#simplifyTree} applies, returning
     * whether the tree changed (C {@code ComplicateTree}).
     */
    static boolean complicateTree(Node node) {
        boolean result = false;
        Node newnode = null;
        for (Node a : node.arg) {
            if (complicateTree(a)) {
                result = true;
            }
        }

        if (node.opcode == Oper.MULT) {
            double con = node.arg[0].con;
            double kcon = node.arg[0].con;
            if (con != AstObject.AST__BAD && node.arg[1].opcode == Oper.LN) {
                // k*ln(x) becomes ln(x**k), or the common logarithm log(x**(k*ln(10))) when k*ln(10) is
                // a multiple of 0.1.
                double fk = 10.0 * con * Math.log(10.0);
                int k = nint(fk);
                if (MappingHelpers.astEqual(fk, k)) {
                    newnode = UnitsTree.newNode(Oper.LOG);
                    con = k / 10.0;
                } else {
                    newnode = UnitsTree.newNode(Oper.LN);
                }
                Node node2 = UnitsTree.copyTree(node.arg[1].arg[0]);
                if (!MappingHelpers.astEqual(con, 1.0)) {
                    Node node1 = UnitsTree.copyTree(node.arg[0]);
                    Node node3 = UnitsTree.newNode(Oper.POW);
                    node1.con = con;
                    node3.arg[0] = node2;
                    node3.arg[1] = node1;
                    newnode.arg[0] = node3;
                } else {
                    newnode.arg[0] = node2;
                }
            } else if (node.arg[0].opcode == Oper.POW && MappingHelpers.astEqual(node.arg[0].arg[1].con, -1.0)) {
                // (A**-1)*B becomes B/A.
                newnode = UnitsTree.newNode(Oper.DIV);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[1]);
                newnode.arg[1] = UnitsTree.copyTree(node.arg[0].arg[0]);
            } else if (node.arg[1].opcode == Oper.POW && MappingHelpers.astEqual(node.arg[1].arg[1].con, -1.0)) {
                // B*(A**-1) becomes B/A.
                newnode = UnitsTree.newNode(Oper.DIV);
                newnode.arg[0] = UnitsTree.copyTree(node.arg[0]);
                newnode.arg[1] = UnitsTree.copyTree(node.arg[1].arg[0]);
            } else if (node.arg[0].opcode == Oper.POW
                    && node.arg[1].opcode == Oper.POW
                    && MappingHelpers.astEqual(node.arg[0].arg[1].con, node.arg[1].arg[1].con)) {
                // (x**k)*(y**k) becomes (x*y)**k.
                newnode = UnitsTree.newNode(Oper.POW);
                Node node1 = UnitsTree.newNode(Oper.MULT);
                node1.arg[0] = UnitsTree.copyTree(node.arg[0].arg[0]);
                node1.arg[1] = UnitsTree.copyTree(node.arg[1].arg[0]);
                newnode.arg[0] = node1;
                newnode.arg[1] = UnitsTree.copyTree(node.arg[0].arg[1]);
            } else if (kcon != AstObject.AST__BAD && kcon > 0.0 && node.arg[1].opcode == Oper.SQRT) {
                // c*sqrt(x) becomes sqrt((c**2)*x) for positive c.
                newnode = UnitsTree.newNode(Oper.SQRT);
                Node node1 = UnitsTree.newNode(Oper.MULT);
                node1.arg[0] = UnitsTree.ldcon(kcon * kcon);
                node1.arg[1] = UnitsTree.copyTree(node.arg[1].arg[0]);
                newnode.arg[0] = node1;
            }
        } else if (node.opcode == Oper.POW && MappingHelpers.astEqual(node.arg[1].con, 0.5)) {
            // x**0.5 becomes sqrt(x).
            newnode = UnitsTree.newNode(Oper.SQRT);
            newnode.arg[0] = UnitsTree.copyTree(node.arg[0]);
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

        // After a change, simplify without putting the tree back into standard form, then
        // complicate again.
        if (result) {
            UnitsSimplify.simplifyTree(node, false);
            complicateTree(node);
        }
        return result;
    }
}
