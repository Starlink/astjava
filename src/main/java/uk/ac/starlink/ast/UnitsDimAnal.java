package uk.ac.starlink.ast;

import java.util.List;
import uk.ac.starlink.ast.UnitsTree.Node;
import uk.ac.starlink.ast.UnitsTree.Oper;

/**
 * Dimensional analysis of units trees, and the location and fixing of the units in a tree.
 *
 * <p>Ported from Rust {@code units/dim_anal.rs} and C {@code unit.c} ({@code DimAnal}, {@code
 * LocateUnits} and {@code FixUnits}).
 */
final class UnitsDimAnal {

    private UnitsDimAnal() {}

    /** The dimensions of a tree: the power of each basic quantity, and a scale factor. */
    static final class Result {
        /** The power of each of {@link UnitsTable#quantUnits}. */
        final double[] powers;

        /** The scale factor. */
        final double scale;

        Result(double[] powers, double scale) {
            this.powers = powers;
            this.scale = scale;
        }
    }

    /**
     * The dimensions of the tree headed by {@code node}, or null if they cannot be found, as for
     * a logarithm or exponential, or a unit that is not one of the basic quantities (C {@code
     * DimAnal}). A gram is counted as 0.001 of the quantity, which is the kilogram.
     */
    static Result dimAnal(Node node) {
        List<UnitsTable.KnownUnit> quant = UnitsTable.quantUnits();
        int nquant = quant.size();
        double[] powers = new double[nquant];
        double scale = 1.0;
        Oper oper = node.opcode;
        // MODERN-JAVA(21): a switch expression over oper
        switch (oper) {
            case LDCON:
                scale = 1.0 / node.con;
                break;
            case LDVAR: {
                int i = quant.indexOf(node.unit);
                if (node.unit == null || i < 0) {
                    return null;
                }
                powers[i] = 1.0;
                scale = node.mult != null ? 1.0 / node.mult.scale : 1.0;
                if ("g".equals(node.unit.sym)) {
                    scale *= 0.001;
                }
                break;
            }
            case LOG:
            case LN:
            case EXP:
                return null;
            case SQRT: {
                Result r = dimAnal(node.arg[0]);
                if (r == null) {
                    return null;
                }
                for (int i = 0; i < nquant; i++) {
                    powers[i] = r.powers[i] * 0.5;
                }
                scale = Math.sqrt(r.scale);
                break;
            }
            case POW: {
                Result r = dimAnal(node.arg[0]);
                if (r == null) {
                    return null;
                }
                double power = node.arg[1].con;
                for (int i = 0; i < nquant; i++) {
                    powers[i] = r.powers[i] * power;
                }
                scale = Math.pow(r.scale, power);
                break;
            }
            case DIV:
            case MULT: {
                Result r0 = dimAnal(node.arg[0]);
                Result r1 = r0 != null ? dimAnal(node.arg[1]) : null;
                if (r1 == null) {
                    return null;
                }
                for (int i = 0; i < nquant; i++) {
                    powers[i] = oper == Oper.DIV ? r0.powers[i] - r1.powers[i] : r0.powers[i] + r1.powers[i];
                }
                scale = oper == Oper.DIV ? r0.scale / r1.scale : r0.scale * r1.scale;
                break;
            }
            case LDPI:
                scale = 1.0 / UnitsTree.PI;
                break;
            case LDE:
                scale = 1.0 / UnitsTree.E;
                break;
            case NULL:
                // C leaves a null operation dimensionless.
                break;
            default:
                throw Ast.err(AstException.AST__INTER, "UnitsDimAnal: unexpected operation " + oper);
        }
        return new Result(powers, scale);
    }

    /**
     * Adds to {@code units} each unit node of the tree headed by {@code node} whose name is not
     * already in the list (C {@code LocateUnits}).
     */
    static void locateUnits(Node node, List<Node> units) {
        if (node.opcode == Oper.LDVAR) {
            for (Node u : units) {
                if (u.name.equals(node.name)) {
                    return;
                }
            }
            units.add(node);
        } else {
            for (Node a : node.arg) {
                locateUnits(a, units);
            }
        }
    }

    /**
     * A copy of the tree headed by {@code node} in which every unit other than the one named by
     * {@code test} is replaced by the constant 1 (C {@code FixUnits}).
     */
    static Node fixUnits(Node node, Node test) {
        Node result;
        if (node.opcode == Oper.LDVAR) {
            result = test.name.equals(node.name) ? UnitsTree.copyTree(node) : UnitsTree.ldcon(1.0);
        } else {
            result = UnitsTree.copyTree(node);
            for (int i = 0; i < node.arg.length; i++) {
                result.arg[i] = fixUnits(node.arg[i], test);
            }
        }
        return result;
    }
}
