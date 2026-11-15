package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.UnitsTree.Node;

/**
 * Concatenation of units trees.
 *
 * <p>Ported from Rust {@code units/concat.rs} and C {@code unit.c} ({@code ConcatTree} and {@code
 * ReplaceNode}). The node to replace is found by identity, as C finds it by address.
 */
final class UnitsConcat {

    private UnitsConcat() {}

    /**
     * A tree that feeds the output of {@code tree1} into the one unit of {@code tree2}: a copy of
     * {@code tree2} in which that unit is replaced by a copy of {@code tree1}, or null if {@code
     * tree2} has no unit (C {@code ConcatTree}).
     *
     * @throws AstException with status {@code AST__INTER} if {@code tree2} has more than one unit
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"
    }) // found by identity, as C finds it by address
    static Node concatTree(Node tree1, Node tree2) {
        Node result = UnitsTree.copyTree(tree2);
        List<Node> units = new ArrayList<>();
        UnitsDimAnal.locateUnits(result, units);
        if (units.isEmpty()) {
            return null;
        }
        if (units.size() > 1) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "ConcatTree(unit): tree2 uses " + units.size()
                            + " units - should be 1 (internal AST programming error).");
        }
        // The node to be replaced may be the head of the copy.
        if (units.get(0) == result) {
            return UnitsTree.copyTree(tree1);
        }
        replaceNode(result, units.get(0), UnitsTree.copyTree(tree1));
        return result;
    }

    /**
     * Replaces the node {@code old} within the tree headed by {@code target} with {@code
     * replacement}, returning whether it was found (C {@code ReplaceNode}).
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"
    }) // found by identity, as C finds it by address
    static boolean replaceNode(Node target, Node old, Node replacement) {
        for (int i = 0; i < target.arg.length; i++) {
            if (target.arg[i] == old) {
                target.arg[i] = replacement;
                return true;
            }
            if (replaceNode(target.arg[i], old, replacement)) {
                return true;
            }
        }
        return false;
    }
}
