package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * A Region with no boundary. A NullRegion that is not negated contains no points; a negated one
 * contains every point of its Frame. As a Mapping, a NullRegion that is not negated makes every
 * value bad, and a negated one leaves every value as it is.
 *
 * <p>A NullRegion has no defining points, so {@link #getRegionPoints} gives none, and its bounds
 * are a lower bound of 1 above an upper bound of -1 on each axis, marking a Region that contains
 * no points.
 *
 * <p>Ported from C {@code nullregion.c}, with Rust {@code regions/nullregion.rs}.
 */
public class NullRegion extends Region {

    /**
     * Creates a NullRegion (C's {@code astNullRegion}).
     *
     * @param frame the Frame the NullRegion is defined in; a copy is held. For a FrameSet or a
     *     Region, its current Frame
     * @param unc the uncertainty of positions in the Frame, such as a Box, Circle or Ellipse; a
     *     copy is held. Null for no uncertainty
     * @throws AstException with the error of {@link #setUnc} for the uncertainty
     * @throws NullPointerException if {@code frame} is null
     */
    public NullRegion(Frame frame, Region unc) {
        super(Objects.requireNonNull(frame, "frame"), null, unc);
    }

    /** Loads a NullRegion from its description, as C's {@code astLoadNullRegion} does: it has no items of its own. */
    NullRegion(NativeNode node) {
        super(node);
        node.section("NullRegion");
    }

    @Override
    String dumpComment() {
        return "Boundless region";
    }

    /** Writes the NullRegion class's part of a description (C's {@code Dump}, nullregion.c): it has no items. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /** No point is in a NullRegion that is not negated. */
    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        return new boolean[npoint];
    }

    /**
     * Copies the input to the output, and makes every value bad unless the NullRegion is negated
     * (C's {@code Transform}, nullregion.c). The FrameSet is not used: a negated NullRegion
     * contains every point as it is given, bad values and all.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean neg = getNegated();
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            for (int p = 0; p < npoint; p++) {
                if (neg) {
                    double v = a[p];
                    o[p] = Double.isNaN(v) ? AST__BAD : v;
                } else {
                    o[p] = AST__BAD;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Bounds and boundary
    // ------------------------------------------------------------------

    /**
     * A lower bound of 1 above an upper bound of -1 on each base Frame axis, which says that the
     * NullRegion contains no points (C's {@code RegBaseBox}, nullregion.c).
     */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        int nc = getRegFS().getNin();
        for (int i = 0; i < nc; i++) {
            lbnd[i] = 1.0;
            ubnd[i] = -1.0;
        }
    }

    /**
     * The bounds of {@link #regBaseBox} on each axis, whatever the Mapping to the current Frame
     * (C's {@code GetRegionBounds}, nullregion.c). C writes a value for each base Frame axis.
     *
     * @throws AstException with status {@code AST__INTER} if the base Frame has more axes than the
     *     current Frame, where C would write beyond the arrays
     */
    @Override
    void regionBounds(double[] lbnd, double[] ubnd) {
        int nc = getRegFS().getNin();
        if (nc > lbnd.length) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astGetRegionBounds(" + astClass() + "): The base Frame has more axes (" + nc
                            + ") than the current Frame (" + lbnd.length + ") (internal AST programming error).");
        }
        regBaseBox(lbnd, ubnd);
    }

    /**
     * A mesh of one point with every base Frame value bad (C's {@code RegBaseMesh}, nullregion.c),
     * kept until the NullRegion changes.
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        int nc = getRegFS().getNin();
        double[][] result = new double[nc][1];
        for (int i = 0; i < nc; i++) {
            result[i][0] = AST__BAD;
        }
        keepBaseMesh(result);
        return result;
    }

    /**
     * A NullRegion has no boundary to mesh (C's {@code RegMesh}, nullregion.c).
     *
     * @throws AstException with status {@code AST__INTER}
     */
    @Override
    double[][] regMesh() {
        throw Ast.err(
                AstException.AST__INTER,
                "astRegMesh(" + astClass() + "): The " + astClass() + " class does not implement the astRegMesh "
                        + "method inherited from the Region class (internal AST programming error).");
    }

    /**
     * The default uncertainty (C's {@code GetDefUnc}, nullregion.c): a Circle of zero radius at the
     * origin of the base Frame.
     *
     * @throws AstException with status {@code AST__INTER}: the Circle class is not yet ported
     */
    @Override
    Region getDefUnc() {
        throw Ast.err(
                AstException.AST__INTER,
                "astGetDefUnc(" + astClass() + "): The default uncertainty of a " + astClass()
                        + " is a Circle, which is not yet ported (internal AST programming error).");
    }

    /**
     * A NullRegion of the chosen base Frame axes (C's {@code RegBasePick}, nullregion.c), in a
     * Frame of those axes, with the uncertainty's own pick of them if that is a Region.
     */
    @Override
    Region regBasePick(int[] axes) {
        int naxes = axes.length;
        int[] axes1 = new int[naxes];
        for (int i = 0; i < naxes; i++) {
            axes1[i] = axes[i] + 1;
        }
        Frame frm = baseFrame().pickAxes(naxes, axes1, null);
        Region u = null;
        if (testUnc()) {
            Frame picked = getUncFrm(FrameSet.AST__BASE).pickAxes(naxes, axes1, null);
            if (picked instanceof Region) {
                u = (Region) picked;
            }
        }
        return new NullRegion(frm, u);
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * Simplifies the NullRegion (C's {@code Simplify}, nullregion.c): after the Region-generic
     * simplification, a NullRegion whose base and current Frames are not joined by a UnitMap is
     * made again in its current Frame, with its uncertainty, if one is set, mapped into that
     * Frame; a new NullRegion is given this one's Negated, Closed, MeshSize, FillFactor and
     * Adaptive, and no uncertainty if this one has none.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        Region nw = parentSimplify();
        Mapping map = nw.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Region result = isUnitMap(map) ? nw : new NullRegion(nw.regFrame(), nw.getUnc(false));
        if (result != this) {
            result.regOverlay(this, true);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Overlap
    // ------------------------------------------------------------------

    /** C's {@code Overlap} (nullregion.c): this class's {@code OverlapX} with the Regions swapped. */
    @Override
    int overlapWith(Region that) {
        return nullOverlap(that, this);
    }

    /** C's {@code OverlapX} (nullregion.c), with this NullRegion as {@code that}. */
    @Override
    int overlapX(Region thisRegion) {
        return nullOverlap(this, thisRegion);
    }

    /**
     * C's {@code OverlapX( that, thisRegion )} (nullregion.c), one of the two being a NullRegion:
     * how {@code thisRegion} and {@code that} overlap. Regions whose Frames cannot be converted
     * give 0; two NullRegions are the same if both or neither are negated, and each other's
     * negation otherwise; a NullRegion that is not negated overlaps nothing; and a negated one
     * contains the other Region.
     */
    private static int nullOverlap(Region that, Region thisRegion) {
        FrameSet fs = thisRegion.convert(that, "");
        if (fs == null) {
            return OVERLAP_UNKNOWN;
        }
        boolean thisNull = thisRegion instanceof NullRegion;
        boolean thatNull = that instanceof NullRegion;
        if (thisNull && thatNull) {
            return thisRegion.getNegated() == that.getNegated() ? OVERLAP_SAME : OVERLAP_NEGATE;
        } else if ((thisNull && !thisRegion.getNegated()) || (thatNull && !that.getNegated())) {
            return OVERLAP_NONE;
        } else if (thatNull && that.getNegated()) {
            return OVERLAP_INSIDE;
        } else if (thisNull && thisRegion.getNegated()) {
            return OVERLAP_OUTSIDE;
        } else {
            return OVERLAP_PARTIAL;
        }
    }
}
