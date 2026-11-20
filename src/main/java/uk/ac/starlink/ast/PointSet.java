package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * A set of points, each with the same number of coordinates: the defining points of a Region in
 * its description ({@code Begin PointSet}), with C's {@code pointset.c} {@code Dump}, loader and
 * {@code Equal}. The values are held as {@code [ncoord][npoint]}, as a Region holds them.
 *
 * <p>Each axis may have an absolute accuracy (C's PointAccuracy, an {@code AccN} item of a
 * description), which {@link #equalTo} uses in place of its default relative accuracy. A
 * description may also mark a PointSet as empty, holding no values.
 *
 * <p>Ported from C {@code pointset.c}, with Rust {@code serial/mod.rs} ({@code
 * write_pointset_node}, {@code read_pointset_node}).
 */
final class PointSet extends AstObject {

    /** The number of points. */
    private final int npoint;

    /** The number of coordinates of each point. */
    private final int ncoord;

    /** The values, {@code [ncoord][npoint]}, or null if the PointSet is empty. */
    private double[][] values;

    /** The absolute accuracy of each axis, bad where none is set; or null if none is set. */
    private double[] acc;

    /**
     * Creates a PointSet holding copies of {@code points}.
     *
     * @param points the values, {@code [ncoord][npoint]}
     * @throws AstException with status {@code AST__NPTIN} if there are no points, or {@code
     *     AST__NCOIN} if there are no coordinates (C's {@code astInitPointSet}), or {@code
     *     AST__INTER} if the axes do not all have the same number of values
     */
    PointSet(double[][] points) {
        int nc = points.length;
        int np = nc > 0 ? points[0].length : 0;
        if (nc > 0 && np < 1) {
            throw Ast.err(
                    AstException.AST__NPTIN, "astInitPointSet(PointSet): Number of points (" + np + ") is not valid.");
        }
        if (nc < 1) {
            throw Ast.err(
                    AstException.AST__NCOIN,
                    "astInitPointSet(PointSet): Number of coordinates per point (" + nc + ") is not valid.");
        }
        ncoord = nc;
        npoint = np;
        values = new double[nc][];
        for (int ic = 0; ic < nc; ic++) {
            if (points[ic].length != np) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astPointSet(PointSet): Axis " + (ic + 1) + " has " + points[ic].length
                                + " values but axis 1 has " + np + " (AST internal programming error).");
            }
            values[ic] = points[ic].clone();
        }
    }

    /**
     * Creates a PointSet from its description, as C's {@code astLoadPointSet} does: Npoint and
     * Ncoord, each at least 1; the accuracy {@code AccN} of each axis; and, unless {@code Empty} is
     * set, the values {@code X1}, {@code X2} and so on, point by point, each missing one bad.
     *
     * <p>C sets the accuracies of the axes before the first one given only up to the axis before
     * last, leaving that axis's accuracy uninitialised; here every axis without one has none.
     */
    PointSet(NativeNode node) {
        super(node);
        NativeNode part = node.section("PointSet");
        npoint = Math.max(1, part.readInt("Npoint", 1));
        ncoord = Math.max(1, part.readInt("Ncoord", 1));
        for (int coord = 0; coord < ncoord; coord++) {
            double a = part.readDouble("Acc" + (coord + 1), AST__BAD);
            if (acc == null && a != AST__BAD) {
                acc = new double[ncoord];
                Arrays.fill(acc, AST__BAD);
            }
            if (acc != null) {
                acc[coord] = a;
            }
        }
        boolean empty = part.readInt("Empty", 0) != 0;
        if (!empty) {
            values = new double[ncoord][npoint];
            int i = 0;
            for (int point = 0; point < npoint; point++) {
                for (int coord = 0; coord < ncoord; coord++) {
                    i++;
                    values[coord][point] = part.readDouble("X" + i, AST__BAD);
                }
            }
        }
    }

    @Override
    String dumpComment() {
        return "Container for a set of points";
    }

    /**
     * Writes the PointSet as C's {@code Dump} (pointset.c) does: Npoint, Ncoord, the accuracy of
     * each axis that has one, Empty (set only when the PointSet is empty), and each value that is
     * not bad, numbered point by point from 1, the first with a comment.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Object", "AST Object");
        node.writeInt("Npoint", true, true, npoint, "Number of points");
        node.writeInt("Ncoord", true, true, ncoord, "Number of coordinates per point");
        for (int coord = 0; coord < ncoord; coord++) {
            if (testPointAccuracy(coord)) {
                node.writeDouble("Acc" + (coord + 1), true, true, acc[coord], coord == 0 ? "Axis accuracies..." : "");
            }
        }
        boolean empty = values == null;
        node.writeInt("Empty", empty, false, empty ? 1 : 0, empty ? "PointSet is empty" : "PointSet contains data");
        if (!empty) {
            boolean makeComment = true;
            int i = 0;
            for (int point = 0; point < npoint; point++) {
                for (int coord = 0; coord < ncoord; coord++) {
                    i++;
                    double v = values[coord][point];
                    if (v != AST__BAD) {
                        node.writeDouble("X" + i, true, true, v, makeComment ? "Coordinate values..." : "");
                        makeComment = false;
                    }
                }
            }
        }
    }

    /** Gives the copy its own values and accuracies, as C's {@code Copy} (pointset.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (values != null) {
            double[][] v = new double[ncoord][];
            for (int ic = 0; ic < ncoord; ic++) {
                v[ic] = values[ic].clone();
            }
            values = v;
        }
        if (acc != null) {
            acc = acc.clone();
        }
    }

    /** The number of points. */
    int getNpoint() {
        return npoint;
    }

    /** The number of coordinates of each point. */
    int getNcoord() {
        return ncoord;
    }

    /**
     * A copy of the values, {@code [ncoord][npoint]}.
     *
     * @throws AstException with status {@code AST__INTER} if the PointSet is empty; C's {@code
     *     astGetPoints} then gives values it has not initialised
     */
    double[][] points() {
        if (values == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astGetPoints(PointSet): The PointSet is empty, so it has no coordinate values "
                            + "(AST internal programming error).");
        }
        double[][] v = new double[ncoord][];
        for (int ic = 0; ic < ncoord; ic++) {
            v[ic] = values[ic].clone();
        }
        return v;
    }

    /** Whether axis {@code coord}, from 0, has an accuracy (C's {@code TestPointAccuracy}). */
    private boolean testPointAccuracy(int coord) {
        return acc != null && acc[coord] != AST__BAD;
    }

    /** The accuracy of axis {@code coord}, from 0, or bad if it has none (C's {@code GetPointAccuracy}). */
    private double getPointAccuracy(int coord) {
        return acc != null ? acc[coord] : AST__BAD;
    }

    /**
     * Whether {@code that} is a PointSet with the same number of points and coordinates whose
     * values agree with these (C's {@code Equal}, pointset.c). Two values agree if both are bad,
     * or if both are good and differ by no more than their accuracies combined in quadrature. An
     * axis's accuracy is its own, if set; otherwise a value's is 1000 times the larger of its
     * magnitude times the machine epsilon and the square root of the smallest normal double.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!super.equalTo(that)) {
            return false;
        }
        PointSet other = (PointSet) that;
        if (npoint != other.npoint || ncoord != other.ncoord) {
            return false;
        }
        double[][] pThis = points();
        double[][] pThat = other.points();
        double small = Math.sqrt(Double.MIN_NORMAL);
        for (int ic = 0; ic < ncoord; ic++) {
            double accThis = getPointAccuracy(ic);
            double accThat = other.getPointAccuracy(ic);
            boolean bothSet = accThis != AST__BAD && accThat != AST__BAD;
            double accBoth = bothSet ? Math.sqrt(accThis * accThis + accThat * accThat) : 0.0;
            for (int ip = 0; ip < npoint; ip++) {
                double a = pThis[ic][ip];
                double b = pThat[ic][ip];
                if (a == AST__BAD || b == AST__BAD) {
                    if (a != AST__BAD || b != AST__BAD) {
                        return false;
                    }
                    continue;
                }
                double tol;
                if (bothSet) {
                    tol = accBoth;
                } else {
                    double acc1 = accThis;
                    if (acc1 == AST__BAD) {
                        acc1 = Math.abs(a) * Math.ulp(1.0);
                        if (acc1 < small) {
                            acc1 = small;
                        }
                        acc1 *= 1.0E3;
                    }
                    double acc2 = accThat;
                    if (acc2 == AST__BAD) {
                        acc2 = Math.abs(b) * Math.ulp(1.0);
                        if (acc2 < small) {
                            acc2 = small;
                        }
                        acc2 *= 1.0E3;
                    }
                    tol = Math.sqrt(acc1 * acc1 + acc2 * acc2);
                }
                if (Math.abs(a - b) > tol) {
                    return false;
                }
            }
        }
        return true;
    }
}
