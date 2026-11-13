package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * The Mapping between two systems of units.
 *
 * <p>Both units strings are parsed into trees of basic units. For each basic unit in turn, every
 * other unit is fixed at 1, and the tree that converts the input units to the output units through
 * that unit is formed; the conversion is possible only if every unit gives the same tree. Where
 * the simple simplification fails to find a conversion one way, the other way is tried and
 * inverted. The tree found becomes a UnitMap, a ZoomMap or a MathMap.
 *
 * <p>Ported from Rust {@code units/mapper.rs} and C {@code unit.c} ({@code astUnitMapper_} and
 * {@code MakeMapping}). Where the Rust port differs from C, this follows C: an expression that
 * cannot be parsed is an error rather than no conversion, and only identical strings, not strings
 * that are the same once cleaned, take the shortcut to a UnitMap.
 */
final class UnitsMapper {

    private UnitsMapper() {}

    /**
     * The Mapping {@code tree} describes, whose input is the input units and whose output is the
     * head of the tree (C {@code MakeMapping}): a UnitMap for the input units alone or a product
     * of them and 1, a ZoomMap for a product of them and another constant, and otherwise a
     * MathMap.
     *
     * @throws UnsupportedOperationException where a MathMap is needed, since MathMap is not
     *     available
     */
    static Mapping makeMapping(Node tree) {
        if (tree.opcode == Oper.LDVAR) {
            return new UnitMap(1);
        }
        // The standard form puts the constant of a product first, and "x/k" becomes "(1/k)*x".
        if (tree.opcode == Oper.MULT && tree.arg[0].con != AstObject.AST__BAD && tree.arg[1].opcode == Oper.LDVAR) {
            if (tree.arg[0].con == 1.0) {
                return new UnitMap(1);
            }
            return new ZoomMap(1, tree.arg[0].con);
        }
        String fwdexp = UnitsToExpr.makeExp(tree, UnitsToExpr.MATHMAP, true);
        Node inv = UnitsInvert.invertTree(tree, UnitsTree.ldvar("output_units"));
        if (inv == null) {
            throw Ast.err(
                    AstException.AST__BADUN,
                    "MakeMapping(Unit): Failed to invert supplied tree '" + fwdexp
                            + "' (internal AST programming error).");
        }
        String invexp = UnitsToExpr.makeExp(inv, UnitsToExpr.MATHMAP, true);
        throw new UnsupportedOperationException("This conversion of units needs a MathMap, which is not available"
                + " (forward \"output_units=" + fwdexp + "\", inverse \"input_units=" + invexp + "\").");
    }

    /**
     * A copy of {@code e} with the further message {@code context}, with C's status for it, as
     * C's {@code astError} adds a message when an error has already been reported.
     */
    private static AstException context(AstException e, String context) {
        AstException out = new AstException(e.getMsgText() + "\n" + context, AstException.AST__BADUN);
        out.initCause(e);
        return out;
    }

    /**
     * The Mapping from the units {@code in} to the units {@code out}, and, if {@code inLab} is not
     * null, the label {@code inLab} rewritten for the new units (C {@code astUnitMapper_}).
     *
     * @return the Mapping and label, or null if the units cannot be converted; the label is null
     *     if {@code inLab} is null, or where C gives none
     * @throws AstException with status {@code AST__BADUN} if a units string or the label cannot
     *     be parsed
     * @throws UnsupportedOperationException if the conversion needs a MathMap
     */
    static Units.LabelledMapping unitMapper(String in, String out, String inLab) {
        return unitMapper(in, out, inLab, true);
    }

    /**
     * {@link #unitMapper(String, String, String)}, making the Mapping from the conversion found
     * only if {@code makeMap}: otherwise the result's Mapping is null, so that a conversion C makes with a MathMap is found
     * and its label rewritten, as C's {@code NewUnit} (frame.c) uses them.
     */
    static Units.LabelledMapping unitMapper(String in, String out, String inLab, boolean makeMap) {
        if (in.equals(out)) {
            return new Units.LabelledMapping(new UnitMap(1), inLab);
        }

        Node inTree;
        try {
            inTree = UnitsParser.createTree(in, true);
        } catch (AstException e) {
            throw context(e, "astUnitMapper: Error parsing input units string '" + in + "'.");
        }
        Node outTree;
        try {
            outTree = UnitsParser.createTree(out, true);
        } catch (AstException e) {
            throw context(e, "astUnitMapper: Error parsing output units string '" + out + "'.");
        }

        // Two blank strings convert with a UnitMap; one blank string does not convert.
        if (inTree == null && outTree == null) {
            return new Units.LabelledMapping(new UnitMap(1), inLab);
        }
        if (inTree == null || outTree == null) {
            return null;
        }

        // Each basic unit used by either tree, once.
        List<Node> units = new ArrayList<>();
        UnitsDimAnal.locateUnits(inTree, units);
        UnitsDimAnal.locateUnits(outTree, units);

        // The simple simplification sometimes finds a conversion from the output units to the
        // input units but not the other way, so a second pass swaps them and inverts the result.
        Node testtree = null;
        int ipass;
        for (ipass = 0; ipass < 2; ipass++) {
            if (ipass == 1) {
                Node tmp = inTree;
                inTree = outTree;
                outTree = tmp;
            }
            testtree = null;
            for (Node unit : units) {
                // The trees with every other unit fixed at 1.
                Node intemp = UnitsDimAnal.fixUnits(inTree, unit);
                Node outtemp = UnitsDimAnal.fixUnits(outTree, unit);
                UnitsSimplify.simplifyTree(intemp, true);
                UnitsSimplify.simplifyTree(outtemp, true);
                boolean inConst = intemp.con != AstObject.AST__BAD;
                boolean outConst = outtemp.con != AstObject.AST__BAD;
                if (inConst && outConst) {
                    // The unit cancels out of both expressions.
                    continue;
                }
                if (inConst || outConst) {
                    // Only one expression depends on the unit.
                    testtree = null;
                    break;
                }
                // The tree from the input units to this unit, joined to the tree from this unit
                // to the output units, must be the same for every unit.
                Node inv = UnitsInvert.invertTree(intemp, UnitsTree.ldvar("input_units"));
                if (inv == null) {
                    testtree = null;
                    break;
                }
                Node totaltree = UnitsConcat.concatTree(inv, outtemp);
                UnitsSimplify.simplifyTree(totaltree, true);
                if (testtree == null) {
                    testtree = totaltree;
                } else if (UnitsCmp.cmpTree(totaltree, testtree, false) != 0) {
                    testtree = null;
                    break;
                }
            }
            if (testtree != null) {
                break;
            }
        }
        // A conversion found with the units swapped is inverted.
        if (ipass > 0 && testtree != null) {
            testtree = UnitsInvert.invertTree(testtree, UnitsTree.ldvar("input_units"));
        }
        if (testtree == null) {
            return null;
        }

        Mapping result = makeMap ? makeMapping(testtree) : null;
        if (inLab == null) {
            return new Units.LabelledMapping(result, null);
        }

        // The label tree, from the basic label to the input label, joined to the units tree,
        // from the input units to the output units, gives the output label.
        int exp = 0;
        while (CScan.isCSpace(UnitsParser.ch(inLab, exp))) {
            exp++;
        }
        int c = inLab.length() - 1;
        while (c >= exp && CScan.isCSpace(inLab.charAt(c))) {
            c--;
        }
        Node labtree;
        try {
            labtree = UnitsLabel.makeLabelTree(inLab, exp, c - exp + 1);
        } catch (AstException e) {
            throw context(e, "astUnitMapper: Error parsing axis label '" + inLab + "'.");
        }
        if (labtree == null) {
            // C goes on to dereference the missing tree of a blank label; no label results.
            return new Units.LabelledMapping(result, null);
        }
        Node totlabtree = UnitsConcat.concatTree(labtree, testtree);
        UnitsSimplify.simplifyTree(totlabtree, true);
        return new Units.LabelledMapping(result, UnitsLabel.makeLabelExp(totlabtree));
    }
}
