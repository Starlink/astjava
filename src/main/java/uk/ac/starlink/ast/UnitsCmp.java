package uk.ac.starlink.ast;

import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Comparison of units trees.
 *
 * <p>Ported from Rust {@code units/cmp.rs} and C {@code unit.c} ({@code CmpTree}). Constants are
 * compared with C's {@code astEQUAL}, and the result of comparing a multiplication with its
 * arguments swapped is the result C returns, where the Rust port differs.
 */
final class UnitsCmp {

    private UnitsCmp() {}

    /**
     * Compares two trees (C {@code CmpTree}): zero if they are equivalent, otherwise a positive
     * or negative value ordering them. Nodes of different kinds are ordered by their operation,
     * units by name and constants by value. Unless {@code exact} is set, two multiplications
     * whose arguments are the same but swapped are equivalent.
     */
    static int cmpTree(Node tree1, Node tree2, boolean exact) {
        int result = 0;
        Oper op = tree1.opcode;
        if (op != tree2.opcode) {
            result = op.compareTo(tree2.opcode) > 0 ? 1 : -1;
        } else if (op == Oper.LDVAR) {
            result = tree1.name.compareTo(tree2.name);
        } else if (tree1.con != AstObject.AST__BAD) {
            if (!MappingHelpers.astEqual(tree1.con, tree2.con)) {
                result = tree1.con > tree2.con ? 1 : -1;
            }
        } else {
            for (int i = 0; i < tree1.arg.length; i++) {
                result = cmpTree(tree1.arg[i], tree2.arg[i], exact);
                if (result != 0) {
                    break;
                }
            }
            // Multiplications that differ may have their arguments swapped; C returns the
            // comparison with the arguments of tree2 swapped.
            if (result != 0 && op == Oper.MULT && !exact) {
                for (int i = 0; i < tree1.arg.length; i++) {
                    result = cmpTree(tree1.arg[i], tree2.arg[1 - i], false);
                    if (result != 0) {
                        break;
                    }
                }
            }
        }
        return result;
    }
}
