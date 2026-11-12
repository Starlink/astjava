package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * A linear Mapping which transforms a rectangular window in one coordinate system into a similar
 * window in another coordinate system by scaling and shifting each axis, the window edges being
 * parallel to the coordinate axes. The forward transformation of axis {@code i} is {@code
 * output = shift[i] + scale[i] * input}.
 *
 * <p>A WinMap is specified by the coordinates of two opposite corners, A and B, of the window in
 * both the input and output coordinate systems. An axis on which the two input corners coincide,
 * or on which any corner is bad, has no transformation: it gives bad values.
 *
 * <p>Ported from Rust {@code mappings/winmap.rs} and C {@code winmap.c}.
 */
public class WinMap extends Mapping {

    /** The shift of each axis (C's {@code a}), bad where the axis is undefined. */
    private double[] shifts;

    /** The scale factor of each axis (C's {@code b}), bad where the axis is undefined. */
    private double[] scales;

    /**
     * Creates a WinMap.
     *
     * @param ncoord the number of coordinate values for each point to be transformed: the number
     *     of dimensions of the space the points lie in, the same for input and output points
     * @param ina the coordinates of corner A of the window in the input coordinate system
     * @param inb the coordinates of corner B of the window in the input coordinate system
     * @param outa the coordinates of corner A of the window in the output coordinate system
     * @param outb the coordinates of corner B of the window in the output coordinate system
     * @throws AstException with status {@code AST__DIMIN} if an array holds fewer than {@code
     *     ncoord} values, or {@code AST__BADNI} if {@code ncoord} is negative
     */
    public WinMap(int ncoord, double[] ina, double[] inb, double[] outa, double[] outb) {
        super(checkedNcoord(ncoord, ina, inb, outa, outb), ncoord, true, true);
        shifts = new double[ncoord];
        scales = new double[ncoord];
        for (int axis = 0; axis < ncoord; axis++) {
            if (ina == null || outa == null || inb == null || outb == null) {
                scales[axis] = AST__BAD;
                shifts[axis] = AST__BAD;
            } else if (isGood(inb[axis]) && isGood(ina[axis]) && isGood(outb[axis]) && isGood(outa[axis])) {
                double denom = inb[axis] - ina[axis];
                if (denom != 0.0) {
                    scales[axis] = (outb[axis] - outa[axis]) / denom;
                    shifts[axis] = outa[axis] - scales[axis] * ina[axis];
                } else {
                    scales[axis] = AST__BAD;
                    shifts[axis] = AST__BAD;
                }
            } else {
                scales[axis] = AST__BAD;
                shifts[axis] = AST__BAD;
            }
        }
    }

    /**
     * Creates a WinMap from the shift and scale factor of each axis.
     *
     * @param shifts the shift of each axis
     * @param scales the scale factor of each axis, as many as {@code shifts}
     */
    WinMap(double[] shifts, double[] scales) {
        super(shifts.length, shifts.length, true, true);
        if (scales.length != shifts.length) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "WinMap: " + shifts.length + " shifts but " + scales.length + " scale factors.");
        }
        this.shifts = shifts.clone();
        this.scales = scales.clone();
    }

    /** C's {@code astISGOOD}: neither bad nor infinite nor NaN. */
    private static boolean isGood(double v) {
        return v != AST__BAD && Double.isFinite(v);
    }

    /** {@code ncoord}, after checking that each corner array holds a value for every axis. */
    private static int checkedNcoord(int ncoord, double[]... corners) {
        String[] names = {"ina", "inb", "outa", "outb"};
        for (int i = 0; i < corners.length; i++) {
            if (corners[i] != null && corners[i].length < ncoord) {
                throw Ast.err(
                        AstException.AST__DIMIN,
                        "astWinMap(WinMap): The " + names[i] + " array holds " + corners[i].length
                                + " values.\nThis should not be less than " + ncoord + ".");
            }
        }
        return ncoord;
    }

    /**
     * Loads a WinMap from its description, as C's {@code astLoadWinMap} does: the shift and scale
     * of axis {@code i} are the items {@code Sft<i>} and {@code Scl<i>}, zero and one if absent.
     */
    WinMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("WinMap");
        // C reads astGetNin, which is the same here, since a WinMap has as many outputs as inputs.
        int ncoord = createdNin();
        shifts = new double[ncoord];
        scales = new double[ncoord];
        for (int axis = 0; axis < ncoord; axis++) {
            shifts[axis] = part.readDouble("Sft" + (axis + 1), 0.0);
            scales[axis] = part.readDouble("Scl" + (axis + 1), 1.0);
        }
    }

    @Override
    String dumpComment() {
        return "Map one window on to another";
    }

    /**
     * Writes the shift and scale of each axis, each set when it is not the default (zero and one),
     * as C's {@code Dump} (winmap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        for (int axis = 0; axis < shifts.length; axis++) {
            node.writeDouble(
                    "Sft" + (axis + 1), shifts[axis] != 0.0, false, shifts[axis], "Shift for axis " + (axis + 1));
            node.writeDouble(
                    "Scl" + (axis + 1),
                    scales[axis] != 1.0,
                    false,
                    scales[axis],
                    "Scale factor for axis " + (axis + 1));
        }
    }

    /** Gives the copy its own shifts and scales, as C's {@code Copy} (winmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        shifts = shifts.clone();
        scales = scales.clone();
    }

    @Override
    boolean isLinear() {
        return true;
    }

    /**
     * The error winmap.c {@code Transform} reports for a WinMap with no axes, which has no window
     * information.
     */
    private AstException noWindow() {
        return Ast.err(
                AstException.AST__BADWM,
                "astTransform(" + astClass() + "): The supplied " + astClass()
                        + " does not contain any window information.");
    }

    /**
     * Applies the shift and scale of each axis, as C's {@code Transform} (winmap.c) does. The
     * inverse transformation inverts the coefficients first, {@code -shift/scale + input/scale},
     * so that its rounding is C's. An axis with a bad shift or scale, or a zero scale in the
     * inverse direction, gives bad values, as does a bad input.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        if (shifts.length == 0) {
            throw noWindow();
        }
        boolean fwd = effectiveForward(forward);
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            double aa = shifts[c];
            double bb = scales[c];
            boolean def;
            if (isBad(aa) || isBad(bb)) {
                def = false;
            } else if (fwd) {
                def = true;
            } else if (bb != 0.0) {
                bb = 1.0 / bb;
                aa = -aa * bb;
                def = true;
            } else {
                def = false;
            }
            if (!def) {
                Arrays.fill(o, 0, npoint, AST__BAD);
                continue;
            }
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = isBad(v) ? AST__BAD : aa + bb * v;
            }
        }
    }

    /**
     * NaN protocol: the arithmetic of {@link #transformInto}, through which NaN propagates; an
     * axis with no transformation gives NaN.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        if (shifts.length == 0) {
            throw noWindow();
        }
        boolean fwd = effectiveForward(forward);
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            double aa = shifts[c];
            double bb = scales[c];
            if (isBad(aa) || isBad(bb) || (!fwd && bb == 0.0)) {
                Arrays.fill(o, 0, npoint, Double.NaN);
                continue;
            }
            if (!fwd) {
                bb = 1.0 / bb;
                aa = -aa * bb;
            }
            for (int p = 0; p < npoint; p++) {
                o[p] = aa + bb * a[p];
            }
        }
    }
}
