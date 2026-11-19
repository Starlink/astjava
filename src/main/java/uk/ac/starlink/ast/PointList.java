package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * A Region of a set of points in a Frame. A position is in the PointList if it is within the
 * uncertainty of one of the points: the uncertainty Region, centred on that point, holds it
 * strictly inside its boundary. A PointList is all boundary, so its Closed attribute is always
 * the opposite of its Negated attribute, whatever value is set.
 *
 * <p>The mesh of its boundary is the points themselves, and {@link #getRegionPoints} gives them.
 *
 * <p>Ported from C {@code pointlist.c}, with Rust {@code regions/pointlist.rs}.
 */
public class PointList extends Region {

    /** The lower bound on each base Frame axis, kept once found (C's {@code lbnd}), or null. */
    private double[] lbnd;

    /** The upper bound on each base Frame axis, kept once found (C's {@code ubnd}), or null. */
    private double[] ubnd;

    /**
     * Creates a PointList (C's {@code astPointList}).
     *
     * @param frame the Frame the PointList is defined in; a copy is held. For a FrameSet or a
     *     Region, its current Frame
     * @param npnt the number of points
     * @param points the coordinates of the points, {@code points[axis][point]}: an array for each
     *     axis of {@code frame}, each holding a value for each point. A value that is not finite
     *     is bad
     * @param unc the uncertainty of each point, such as a Box, Circle or Ellipse; a copy is held.
     *     Null for the default, a Box one millionth of the size of the bounding box of the points
     * @throws AstException with status {@code AST__NPTIN} if {@code npnt} is less than one, {@code
     *     AST__DIMIN} if there are fewer arrays than the Frame has axes or an array has fewer than
     *     {@code npnt} values, or the error of {@link #setUnc} for the uncertainty
     * @throws NullPointerException if {@code frame}, {@code points} or one of its arrays is null
     */
    public PointList(Frame frame, int npnt, double[][] points, Region unc) {
        super(frame, listPoints(frame, npnt, points), unc);
    }

    /** The defining points of a PointList: those given, a value that is not finite being bad. */
    private static double[][] listPoints(Frame frame, int npnt, double[][] points) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(points, "points");
        int naxes = frame.getNaxes();
        if (npnt < 1) {
            throw Ast.err(
                    AstException.AST__NPTIN,
                    "astInitPointSet(PointSet): Number of points (" + npnt + ") is not valid.");
        }
        if (naxes < 1) {
            throw Ast.err(
                    AstException.AST__NCOIN,
                    "astInitPointSet(PointSet): Number of coordinates per point (" + naxes + ") is not valid.");
        }
        if (points.length < naxes) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astPointList(PointList): The points array holds values for " + points.length
                            + " axes.\nThis should not be less than " + naxes + ".");
        }
        double[][] pts = new double[naxes][npnt];
        for (int i = 0; i < naxes; i++) {
            double[] axis = Objects.requireNonNull(points[i], "points[" + i + "]");
            if (axis.length < npnt) {
                throw Ast.err(
                        AstException.AST__DIMIN,
                        "astPointList(PointList): The points[" + i + "] array holds " + axis.length
                                + " values.\nThis should not be less than " + npnt + ".");
            }
            for (int j = 0; j < npnt; j++) {
                pts[i][j] = isBad(axis[j]) ? AST__BAD : axis[j];
            }
        }
        return pts;
    }

    /**
     * A PointList of the points {@code points}, {@code [ncoord][npoint]}, in the Frame of {@code
     * frame}, as C's protected {@code astPointList} makes one from a PointSet.
     */
    private PointList(Frame frame, double[][] points, Region unc) {
        super(frame, points, unc);
    }

    /** Loads a PointList from its description, as C's {@code astLoadPointList} does: it has no items of its own. */
    PointList(NativeNode node) {
        super(node);
        node.section("PointList");
    }

    @Override
    String dumpComment() {
        return "Collection of points";
    }

    /** Writes the PointList class's part of a description (C's {@code Dump}, pointlist.c): it has no items. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
    }

    /** Completes a copy as C's {@code Copy} (pointlist.c) does: the bounds kept are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (lbnd != null && ubnd != null) {
            lbnd = lbnd.clone();
            ubnd = ubnd.clone();
        } else {
            lbnd = null;
            ubnd = null;
        }
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the number of points in the PointList, the read-only attribute ListSize.
     *
     * @return the number of points
     */
    public int getListSize() {
        return getPoints()[0].length;
    }

    /**
     * Gets the Closed attribute, which for a PointList is always the opposite of Negated (C's
     * {@code GetClosed}, pointlist.c): its points are all boundary, in it unless it is negated.
     *
     * @return whether the PointList is not negated
     */
    @Override
    public boolean getClosed() {
        return !getNegated();
    }

    /** C's {@code GetAttrib} (pointlist.c): ListSize, then the Region's. */
    @Override
    String getAttrib(String attrib) {
        if ("listsize".equals(attrib)) {
            return Integer.toString(getListSize());
        }
        return super.getAttrib(attrib);
    }

    /**
     * C's {@code SetAttrib} (pointlist.c): ListSize is read-only.
     *
     * @throws AstException with status {@code AST__NOWRT} for ListSize
     */
    @Override
    void setAttrib(String attrib, String value) {
        if ("listsize".equals(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        }
        super.setAttrib(attrib, value);
    }

    /** C's {@code TestAttrib} (pointlist.c): ListSize is never set. */
    @Override
    boolean testAttrib(String attrib) {
        return !"listsize".equals(attrib) && super.testAttrib(attrib);
    }

    /**
     * C's {@code ClearAttrib} (pointlist.c): ListSize is read-only.
     *
     * @throws AstException with status {@code AST__NOWRT} for ListSize
     */
    @Override
    void clearAttrib(String attrib) {
        if ("listsize".equals(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        }
        super.clearAttrib(attrib);
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /** Which base Frame points are in the un-negated PointList, as {@link #regBaseMask} decides. */
    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        boolean[] mask = regBaseMask(base, npoint);
        if (getNegated()) {
            for (int i = 0; i < npoint; i++) {
                mask[i] = !mask[i];
            }
        }
        return mask;
    }

    /**
     * Which base Frame points are in the PointList (C's {@code Transform}, pointlist.c). The
     * PointList's own uncertainty is negated and centred on each of its points in turn, and the
     * points given are passed through it each time, so that a point inside any of them is made
     * bad; then the uncertainty is put back where it was and its Negated attribute cleared. A
     * point is in the PointList if its first value was made bad (or was bad), and in a negated
     * PointList otherwise: the first value alone decides.
     *
     * @throws AstException with status {@code AST__INTER} if the points given do not have a value
     *     for each base Frame axis
     */
    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        double[][] preg = getPoints();
        int nrp = preg[0].length;
        if (preg.length != base.length) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astTransform(PointList): Illegal number of coords (" + preg.length + ") in the Region - should be "
                            + base.length + " (internal AST programming error).");
        }
        Region unc = getUncFrm(FrameSet.AST__BASE);
        unc.setNegatedValue(true);
        double[][] pbase = unc.regTransform(preg, false);
        double[] cenOrig = unc.regCentre(null, null, 0, FrameSet.AST__BASE);
        double[][] ps1 = base;
        for (int point = 0; point < nrp; point++) {
            unc.regCentre(null, pbase, point, FrameSet.AST__BASE);
            ps1 = unc.transform(ps1, npoint, false);
        }
        unc.regCentre(cenOrig, null, 0, FrameSet.AST__BASE);
        boolean neg = getNegated();
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            boolean bad = ps1[0][point] == AST__BAD;
            mask[point] = neg != bad;
        }
        unc.clearNegated();
        return mask;
    }

    // ------------------------------------------------------------------
    // Bounds and boundary
    // ------------------------------------------------------------------

    /**
     * The bounds of the points on each base Frame axis (C's {@code RegBaseBox}, pointlist.c), kept
     * once found: the largest offsets below and above the first point, measured along the axis as
     * the base Frame measures them, so that on a sky axis the bounds may wrap through zero.
     */
    @Override
    void regBaseBox(double[] lb, double[] ub) {
        if (lbnd == null || ubnd == null) {
            double[][] ptr = getPoints();
            int nc = ptr.length;
            int np = ptr[0].length;
            Frame frm = baseFrame();
            double[] l = new double[nc];
            double[] u = new double[nc];
            for (int ic = 0; ic < nc; ic++) {
                double p0 = ptr[ic][0];
                l[ic] = 0.0;
                u[ic] = 0.0;
                for (int ip = 1; ip < np; ip++) {
                    double d = frm.axDistance(ic + 1, p0, ptr[ic][ip]);
                    if (d < l[ic]) {
                        l[ic] = d;
                    }
                    if (d > u[ic]) {
                        u[ic] = d;
                    }
                }
                l[ic] = frm.axOffset(ic + 1, p0, l[ic]);
                u[ic] = frm.axOffset(ic + 1, p0, u[ic]);
            }
            lbnd = l;
            ubnd = u;
        }
        System.arraycopy(lbnd, 0, lb, 0, lbnd.length);
        System.arraycopy(ubnd, 0, ub, 0, ubnd.length);
    }

    /** The boundary mesh: a copy of the points (C's {@code RegBaseMesh}, pointlist.c), kept once made. */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        double[][] result = copyPoints(getPoints());
        keepBaseMesh(result);
        return result;
    }

    /**
     * Whether every point of {@code pset} is on the boundary of the PointList (C's {@code RegPins},
     * pointlist.c): whether each is in the PointList, not negated and with its base Frame as its
     * current Frame; and, if so, whether each of this PointList's points is in a PointList made of
     * the points given, with {@code pointUnc} as its Frame and uncertainty. That second test looks
     * at as many of this PointList's points as there are points given, as C does. The Negated
     * attribute is set to its value afterwards.
     *
     * @throws AstException with status {@code AST__INTER} if the second test is reached with more
     *     points given than the PointList has, where C reads beyond its points, or with no {@code
     *     pointUnc}, for which C has no Frame
     */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        FrameSet fs = getRegFS();
        int icurr = fs.getCurrent();
        fs.setCurrent(FrameSet.AST__BASE);
        boolean negOld = getNegated();
        setNegatedValue(false);
        int np = pset[0].length;
        int nc = pset.length;
        double[][] pset2 = transform(pset, np, true);
        boolean result = true;
        if (mask != null) {
            for (int ip = 0; ip < np; ip++) {
                mask[ip] = pset2[0][ip] != AST__BAD;
                if (!mask[ip]) {
                    result = false;
                }
            }
            for (int ic = 1; ic < nc; ic++) {
                for (int ip = 0; ip < np; ip++) {
                    if (pset2[ic][ip] == AST__BAD) {
                        result = false;
                        mask[ip] = false;
                    }
                }
            }
        } else {
            for (int ic = 0; ic < nc && result; ic++) {
                for (int ip = 0; ip < np; ip++) {
                    if (pset2[ic][ip] == AST__BAD) {
                        result = false;
                        break;
                    }
                }
            }
        }
        if (result) {
            double[][] own = getPoints();
            if (pointUnc == null || np > own[0].length) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astRegPins(" + astClass() + "): Cannot compare the " + own[0].length + " points of the "
                                + astClass() + " with " + np + " points"
                                + (pointUnc == null ? " that have no uncertainty" : "")
                                + " (internal AST programming error).");
            }
            PointList pl = new PointList(pointUnc, copyPoints(pset), pointUnc);
            double[][] pset3 = pl.transform(own, own[0].length, true);
            for (int ic = 0; ic < nc && result; ic++) {
                for (int ip = 0; ip < np; ip++) {
                    if (pset3[ic][ip] == AST__BAD) {
                        result = false;
                        break;
                    }
                }
            }
        }
        fs.setCurrent(icurr);
        setNegatedValue(negOld);
        return result;
    }

    /**
     * A PointList of the chosen base Frame axes (C's {@code RegBasePick}, pointlist.c): the points'
     * values on those axes, in a Frame of those axes, with the uncertainty's own pick of them if
     * that is a Region.
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
        double[][] ptr = getPoints();
        double[][] pts = new double[naxes][];
        for (int i = 0; i < naxes; i++) {
            pts[i] = ptr[axes[i]].clone();
        }
        return new PointList(frm, pts, u);
    }

    // ------------------------------------------------------------------
    // Masking
    // ------------------------------------------------------------------

    /** C's {@code Mask<X>} (pointlist.c): {@link RegionMask#pointListMask}. */
    @Override
    int maskData(Mapping map, boolean inside, int ndim, int[] glbnd, int[] gubnd, PixelArray in, String method) {
        return RegionMask.pointListMask(this, map, inside, ndim, glbnd, gubnd, in, method);
    }
}
