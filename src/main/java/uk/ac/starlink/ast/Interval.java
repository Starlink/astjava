package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * A Region of limits on the axes of a Frame: a point is in it if its value on every axis passes
 * that axis's limits. An axis may have a lower limit, an upper limit, both or neither; a lower
 * limit above the upper one excludes the range between them rather than including it. The
 * Interval makes no allowance for an axis that wraps round, such as a sky longitude; a {@link
 * Box} is usually better there.
 *
 * <p>An Interval with both limits on every axis, all included, is the same as a Box, and behaves
 * as that Box; the Box is kept until the Interval changes. Any other Interval is not bounded.
 * {@link #getRegionPoints} gives the lower limits, then the upper limits, with a missing limit
 * bad.
 *
 * <p>Ported from C {@code interval.c}, with Rust {@code regions/interval.rs}.
 */
public class Interval extends Region {

    /** The epsilon of a double, C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    /** The equivalent Box, in the current Frame, or null if there is none (C's {@code box}). */
    private Box box;

    /**
     * The lower limit on each base Frame axis, the most negative double where there is none,
     * kept until the Interval changes.
     */
    private double[] lbnd;

    /**
     * The upper limit on each base Frame axis, the largest double where there is none, kept until
     * the Interval changes.
     */
    private double[] ubnd;

    /** Whether the values kept above must be worked out again (C's {@code stale}). */
    private boolean stale = true;

    /**
     * Creates an Interval (C's {@code astInterval}).
     *
     * @param frame the Frame the Interval is defined in; a copy is held. For a FrameSet or a
     *     Region, its current Frame
     * @param lbnd the lower limit on each axis; a bad value (or one that is not finite) means no
     *     lower limit
     * @param ubnd the upper limit on each axis; a bad value (or one that is not finite) means no
     *     upper limit. A lower limit above its upper limit excludes the range between them
     * @param unc the uncertainty of positions on the boundary, such as a Box, Circle or Ellipse; a
     *     copy is held. Null for the default, a Box one millionth of the size of the Interval on
     *     each axis with both limits
     * @throws AstException with status {@code AST__DIMIN} if an array has fewer values than the
     *     Frame has axes, or the error of {@link #setUnc} for the uncertainty
     * @throws NullPointerException if {@code frame}, {@code lbnd} or {@code ubnd} is null
     */
    public Interval(Frame frame, double[] lbnd, double[] ubnd, Region unc) {
        super(frame, intervalPoints(frame, lbnd, ubnd), unc);
    }

    /**
     * The defining points of an Interval, as C's {@code astInitInterval} makes them: the lower
     * limits, then the upper limits, a value that is not finite being bad.
     */
    private static double[][] intervalPoints(Frame frame, double[] lbnd, double[] ubnd) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(lbnd, "lbnd");
        Objects.requireNonNull(ubnd, "ubnd");
        int nc = frame.getNaxes();
        if (nc < 1) {
            throw Ast.err(
                    AstException.AST__NCOIN,
                    "astInitPointSet(PointSet): Number of coordinates per point (" + nc + ") is not valid.");
        }
        checkLength("lbnd", lbnd, nc);
        checkLength("ubnd", ubnd, nc);
        double[][] pts = new double[nc][2];
        for (int i = 0; i < nc; i++) {
            pts[i][0] = isBad(lbnd[i]) ? AST__BAD : lbnd[i];
            pts[i][1] = isBad(ubnd[i]) ? AST__BAD : ubnd[i];
        }
        return pts;
    }

    /** An {@code AST__DIMIN} error if {@code values} has fewer than {@code nc} elements. */
    private static void checkLength(String what, double[] values, int nc) {
        if (values.length < nc) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astInterval(Interval): The " + what + " array holds " + values.length
                            + " values.\nThis should not be less than " + nc + ".");
        }
    }

    /** Loads an Interval from its description, as C's {@code astLoadInterval} does: it has no items of its own. */
    Interval(NativeNode node) {
        super(node);
        node.section("Interval");
    }

    @Override
    String dumpComment() {
        return "Axis intervals";
    }

    /** Writes the Interval class's part of a description (C's {@code Dump}, interval.c): it has no items. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
    }

    /** Completes a copy as C's {@code Copy} (interval.c) does: the Box and limits kept are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        box = box == null ? null : (Box) box.copy();
        lbnd = lbnd == null ? null : lbnd.clone();
        ubnd = ubnd == null ? null : ubnd.clone();
    }

    /** Discards the kept values (C's {@code ResetCache}, interval.c), then what the Region keeps. */
    @Override
    void resetCache() {
        stale = true;
        super.resetCache();
    }

    /** Gives the Interval a new FrameSet (C's {@code SetRegFS}, interval.c), discarding the kept values. */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        resetCache();
    }

    /**
     * Works out the limits on each base Frame axis and the equivalent Box, unless they are kept
     * (C's {@code Cache}, interval.c). The Interval is a Box if every axis has both limits, the
     * same way round as on the first axis, and the first axis includes its range; the Box is made
     * in the base Frame with the Interval's uncertainty, mapped into the current Frame, and given
     * the Interval's Negated and Closed.
     *
     * @return the equivalent Box, or null if there is none
     */
    private Box cache() {
        if (!stale) {
            return box;
        }
        box = null;
        double[][] ptr = getPoints();
        int nc = getRegFS().getNin();
        double[] l = new double[nc];
        double[] u = new double[nc];
        boolean isBox = true;
        boolean neg = false;
        for (int i = 0; i < nc; i++) {
            l[i] = ptr[i][0];
            u[i] = ptr[i][1];
            if (l[i] == AST__BAD) {
                l[i] = -Double.MAX_VALUE;
            }
            if (Math.abs(l[i]) == Double.MAX_VALUE) {
                isBox = false;
            }
            if (u[i] == AST__BAD) {
                u[i] = Double.MAX_VALUE;
            }
            if (Math.abs(u[i]) == Double.MAX_VALUE) {
                isBox = false;
            }
            if (i == 0) {
                neg = l[i] > u[i];
            } else if (neg) {
                if (l[i] < u[i]) {
                    isBox = false;
                }
            } else if (l[i] > u[i]) {
                isBox = false;
            }
        }
        if (isBox && !neg) {
            FrameSet fs = getRegFS();
            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Region unc = testUnc() ? getUncFrm(FrameSet.AST__BASE) : null;
            Box bbox = new Box(baseFrame(), 1, l, u, unc);
            if (isUnitMap(map)) {
                box = bbox;
            } else {
                box = (Box) bbox.mapRegionInto(map, regFrame());
            }
            if (getNegated()) {
                box.negate();
            }
            box.setClosedValue(getClosed());
        }
        lbnd = l;
        ubnd = u;
        stale = false;
        return box;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Which points are in the Interval (C's {@code Transform}, interval.c): those the equivalent
     * Box keeps, if there is one, and otherwise those {@link #regBaseMask} keeps.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        Box b = cache();
        if (b != null) {
            b.transformInto(in, npoint, forward, out);
            return;
        }
        super.transformInto(in, npoint, forward, out);
    }

    /** Which base Frame points are in the un-negated Interval, as {@link #regBaseMask} decides. */
    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        return inside(base, npoint, false, closedForBaseInside());
    }

    /**
     * Which base Frame points are in an Interval that is not a Box (C's {@code Transform},
     * interval.c), allowing for Negated.
     */
    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        return inside(base, npoint, getNegated(), getClosed());
    }

    /**
     * C's test of each axis (interval.c {@code Transform}). An Interval that is not negated holds
     * a point if every value passes its axis's limits; a negated one, if a value fails them before
     * any value is bad. On an axis whose limits are equal, they are moved apart by the width of
     * the uncertainty's bounding box on that axis where Negated and Closed differ. A bad value
     * makes the point outside.
     */
    private boolean[] inside(double[][] base, int npoint, boolean neg, boolean closed) {
        cache();
        int nc = base.length;
        double[] lbndUnc = null;
        double[] ubndUnc = null;
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            boolean setbad = neg;
            for (int coord = 0; coord < nc; coord++) {
                double p = base[coord][point];
                double lb = lbnd[coord];
                double ub = ubnd[coord];
                if (lb == ub && closed != neg) {
                    if (lbndUnc == null) {
                        Region unc = getUncFrm(FrameSet.AST__BASE);
                        lbndUnc = new double[nc];
                        ubndUnc = new double[nc];
                        unc.regionBounds(lbndUnc, ubndUnc);
                    }
                    double wid = 0.5 * (ubndUnc[coord] - lbndUnc[coord]);
                    lb -= wid;
                    ub += wid;
                }
                if (p == AST__BAD) {
                    setbad = true;
                    break;
                }
                boolean pass;
                if (!neg) {
                    if (closed) {
                        pass = lb <= ub ? lb <= p && p <= ub : p <= ub || lb <= p;
                    } else {
                        pass = lb <= ub ? lb < p && p < ub : p < ub || lb < p;
                    }
                    if (!pass) {
                        setbad = true;
                        break;
                    }
                } else {
                    if (closed) {
                        pass = lb <= ub ? p <= lb || ub <= p : ub <= p && p <= lb;
                    } else {
                        pass = lb <= ub ? p < lb || ub < p : ub < p && p < lb;
                    }
                    if (pass) {
                        setbad = false;
                        break;
                    }
                }
            }
            mask[point] = !setbad;
        }
        return mask;
    }

    /** Whether the Interval is bounded (C's {@code GetBounded}, interval.c): whether it is a Box. */
    @Override
    boolean isBoundedInner() {
        return cache() != null;
    }

    // ------------------------------------------------------------------
    // Bounds, boundary and uncertainty
    // ------------------------------------------------------------------

    /**
     * The limits on each base Frame axis (C's {@code RegBaseBox}, interval.c): the most negative
     * or the largest double where there is no limit, and a lower limit above its upper limit on
     * an axis that excludes its range.
     */
    @Override
    void regBaseBox(double[] lb, double[] ub) {
        cache();
        int naxb = getRegFS().getNin();
        for (int i = 0; i < naxb; i++) {
            lb[i] = lbnd[i];
            ub[i] = ubnd[i];
        }
    }

    /**
     * The boundary mesh of the equivalent Box (C's {@code RegBaseMesh}, interval.c).
     *
     * @throws AstException with status {@code AST__INTER} if the Interval is not a Box
     */
    @Override
    double[][] regBaseMesh() {
        Box b = cache();
        if (b == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegBaseMesh(" + astClass() + "): The " + astClass() + " given is unbounded and therefore no "
                            + "boundary mesh can be produced (internal AST programming error).");
        }
        return b.regBaseMesh();
    }

    /**
     * The boundary mesh, in the base Frame, of the part of the Interval inside the box from {@code
     * lbndc} to {@code ubndc} in the current Frame (C's {@code BndBaseMesh}, interval.c): that of the
     * equivalent Box if there is one; otherwise that of a Box made of the Interval's limits, with
     * each missing limit taken from the box's bounds in the base Frame, or one point with every
     * value bad if that Box is not closed.
     */
    @Override
    double[][] bndBaseMesh(double[] lbndc, double[] ubndc) {
        Box b = cache();
        if (b != null) {
            return b.bndBaseMesh(lbndc, ubndc);
        }
        Mapping map = getRegFS().getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
        int nbase = map.getNout();
        double[] lbndb = new double[nbase];
        double[] ubndb = new double[nbase];
        for (int i = 0; i < nbase; i++) {
            double[] r = MapBox.mapBox(map, lbndc, ubndc, true, i, null, null);
            lbndb[i] = r[0];
            ubndb[i] = r[1];
        }
        boolean closed = true;
        for (int i = 0; i < nbase; i++) {
            if (ubnd[i] != Double.MAX_VALUE) {
                ubndb[i] = ubnd[i];
            }
            if (lbnd[i] != -Double.MAX_VALUE) {
                lbndb[i] = lbnd[i];
            }
            if (lbndb[i] > ubndb[i]) {
                closed = false;
            }
        }
        if (closed) {
            return new Box(baseFrame(), 1, lbndb, ubndb, null).regBaseMesh();
        }
        double[][] result = new double[nbase][1];
        for (int i = 0; i < nbase; i++) {
            result[i][0] = AST__BAD;
        }
        return result;
    }

    /**
     * The default uncertainty, in the base Frame (C's {@code GetDefUnc}, interval.c): that of the
     * equivalent Box if there is one; otherwise a Box centred between the limits, one millionth of
     * their separation on each axis with both limits (one millionth of the value if they are
     * equal, and 1.0E-12 if both are zero), and of no width, at zero, on any other axis.
     */
    @Override
    Region getDefUnc() {
        Box b = cache();
        if (b != null) {
            return b.getDefUnc();
        }
        Frame bfrm = baseFrame();
        int naxb = bfrm.getNaxes();
        double[] lb = new double[naxb];
        double[] ub = new double[naxb];
        for (int i = 0; i < naxb; i++) {
            if (lbnd[i] != -Double.MAX_VALUE && ubnd[i] != Double.MAX_VALUE) {
                double hw = Math.abs(0.5E-6 * (ubnd[i] - lbnd[i]));
                double c = 0.5 * (ubnd[i] + lbnd[i]);
                if (hw == 0.0) {
                    hw = c * 0.5E-6;
                }
                if (hw == 0.0) {
                    hw = 0.5E-12;
                }
                ub[i] = c + hw;
                lb[i] = c - hw;
            } else {
                ub[i] = 0.0;
                lb[i] = 0.0;
            }
        }
        return new Box(bfrm, 1, lb, ub, null);
    }

    // ------------------------------------------------------------------
    // Centre, boundary points and axes
    // ------------------------------------------------------------------

    /**
     * The centre of the equivalent Box, or a move of the Interval (C's {@code RegCentre},
     * interval.c). A move moves the Box and takes the new limits from it. As in C, the limits
     * kept for {@link #regBaseBox} are not worked out again, so the bounds of the moved Interval
     * in its base Frame are those it had before the move.
     *
     * @return the centre, or null if the Interval is not a Box or has been moved
     * @throws AstException with status {@code AST__REGCN} if asked to move an Interval that is
     *     not a Box
     */
    @Override
    double[] regCentre(double[] cen, double[][] ptr, int index, int ifrm) {
        Box b = cache();
        if (b != null) {
            if (cen != null || ptr != null) {
                b.regCentre(cen, ptr, index, ifrm);
                int naxb = getRegFS().getNin();
                double[] lb = new double[naxb];
                double[] ub = new double[naxb];
                b.regBaseBox(lb, ub);
                double[][] bptr = getPoints();
                for (int i = 0; i < naxb; i++) {
                    bptr[i][0] = lb[i];
                    bptr[i][1] = ub[i];
                }
                return null;
            }
            return b.regCentre(null, null, 0, ifrm);
        } else if (cen != null || ptr != null) {
            throw Ast.err(
                    AstException.AST__REGCN,
                    "astRegCentre(" + astClass() + "): The supplied " + astClass()
                            + " is not a closed Interval and so cannot be re-centred.");
        }
        return null;
    }

    /**
     * Whether every point is on the boundary of the Interval (C's {@code RegPins}, interval.c):
     * as the equivalent Box decides if there is one; otherwise whether every point is inside an
     * Interval larger than this one and outside one smaller, by half the combined widths of the
     * bounding boxes of this Interval's uncertainty and the points' uncertainty, each re-centred
     * on the centre of this Interval where it has one. A missing limit stays missing.
     *
     * @throws AstException with status {@code AST__INTER} if the points or the uncertainty do not
     *     have the base Frame's number of axes
     */
    @Override
    // The loop making the larger and smaller Intervals' limits computes them; it copies nothing.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        Box b = cache();
        if (b != null) {
            return b.regPins(pset, pointUnc, mask);
        }
        Frame frm = baseFrame();
        int nc = frm.getNaxes();
        if (pset.length != nc) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegPins(" + astClass() + "): Illegal number of axis values per point (" + pset.length
                            + ") in the supplied PointSet - should be " + nc + " (internal AST programming error).");
        }
        if (pointUnc != null && pointUnc.getNaxes() != nc) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegPins(" + astClass() + "): Illegal number of axes (" + pointUnc.getNaxes()
                            + ") in the supplied uncertainty Region - should be " + nc
                            + " (internal AST programming error).");
        }
        double[] safe = regCentre(null, null, 0, FrameSet.AST__BASE);
        Region tunc = getUncFrm(FrameSet.AST__BASE);
        if (safe != null) {
            tunc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
        }
        double[] lbndTunc = new double[nc];
        double[] ubndTunc = new double[nc];
        tunc.regionBounds(lbndTunc, ubndTunc);
        double[] lbndUnc = null;
        double[] ubndUnc = null;
        if (pointUnc != null) {
            if (safe != null) {
                pointUnc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
            }
            lbndUnc = new double[nc];
            ubndUnc = new double[nc];
            pointUnc.regionBounds(lbndUnc, ubndUnc);
        }
        double[] wid = new double[nc];
        for (int i = 0; i < nc; i++) {
            if (pointUnc != null) {
                wid[i] = 0.5
                        * (Math.abs(frm.axDistance(i + 1, lbndTunc[i], ubndTunc[i]))
                                + Math.abs(frm.axDistance(i + 1, lbndUnc[i], ubndUnc[i])));
            } else {
                wid[i] = 0.5 * Math.abs(frm.axDistance(i + 1, lbndTunc[i], ubndTunc[i]));
            }
        }
        double[] largeLbnd = new double[nc];
        double[] largeUbnd = new double[nc];
        double[] smallLbnd = new double[nc];
        double[] smallUbnd = new double[nc];
        for (int i = 0; i < nc; i++) {
            double lb = lbnd[i];
            double ub = ubnd[i];
            if (lb > ub) {
                double t = ub;
                ub = lb;
                lb = t;
            }
            double w = Math.abs(wid[i]);
            if (lb != -Double.MAX_VALUE) {
                largeLbnd[i] = lb - w;
                smallLbnd[i] = lb + w;
            } else {
                largeLbnd[i] = AST__BAD;
                smallLbnd[i] = AST__BAD;
            }
            if (ub != Double.MAX_VALUE) {
                largeUbnd[i] = ub + w;
                smallUbnd[i] = ub - w;
            } else {
                largeUbnd[i] = AST__BAD;
                smallUbnd[i] = AST__BAD;
            }
            if (smallLbnd[i] > smallUbnd[i]) {
                smallLbnd[i] = smallUbnd[i];
            }
        }
        Interval largeInt = new Interval(frm, largeLbnd, largeUbnd, null);
        Interval smallInt = new Interval(frm, smallLbnd, smallUbnd, null);
        smallInt.negate();
        int np = pset[0].length;
        double[][] ps1 = largeInt.transform(pset, np, true);
        double[][] ps2 = smallInt.transform(ps1, np, true);
        boolean result = true;
        if (mask != null) {
            for (int j = 0; j < np; j++) {
                mask[j] = ps2[0][j] != AST__BAD;
                if (!mask[j]) {
                    result = false;
                }
            }
            for (int i = 1; i < nc; i++) {
                for (int j = 0; j < np; j++) {
                    if (ps2[i][j] == AST__BAD) {
                        result = false;
                        mask[j] = false;
                    }
                }
            }
        } else {
            for (int i = 0; i < nc && result; i++) {
                for (int j = 0; j < np; j++) {
                    if (ps2[i][j] == AST__BAD) {
                        result = false;
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * An Interval of the chosen base Frame axes (C's {@code RegBasePick}, interval.c): the limits
     * on those axes, in a Frame of those axes, with the uncertainty's own pick of them if that is
     * a Region.
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
        double[] lb = new double[naxes];
        double[] ub = new double[naxes];
        for (int i = 0; i < naxes; i++) {
            lb[i] = ptr[axes[i]][0];
            ub[i] = ptr[axes[i]][1];
        }
        return new Interval(frm, lb, ub, u);
    }

    // ------------------------------------------------------------------
    // Overlap
    // ------------------------------------------------------------------

    /** The overlap codes of two un-negated Intervals, by the code of the axes so far and that of the next axis. */
    private static final int[][] NEW_RESULT = {
        {1, 1, 1, 1, 1}, {1, 2, 4, 4, 2}, {1, 4, 3, 4, 3}, {1, 4, 4, 4, 4}, {1, 2, 3, 4, 5}
    };

    /**
     * How this Interval and {@code that} overlap (C's {@code Overlap}, interval.c). When both are
     * Intervals and each base Frame axis of {@code that} maps to one base Frame axis of this one,
     * the limits are compared axis by axis, two limits being equal if they differ by no more than
     * the widths of the two uncertainties added in quadrature; otherwise, or if that gives no
     * answer, the overlap is region.c's.
     */
    @Override
    int overlapWith(Region that) {
        int result = 0;
        if (that instanceof Interval) {
            FrameSet fs = that.convert(this, "");
            if (fs != null) {
                Mapping map1 = that.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                Mapping map2 = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                Mapping map3 = getRegFS().getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
                Mapping map = new CmpMap(new CmpMap(map1, map2, true), map3, true);
                Mapping smap = map.simplify();
                if (oneToOne(smap) != null) {
                    result = intervalOverlap((Interval) that, smap);
                }
            }
        }
        if (result == 0) {
            result = super.overlapWith(that);
        }
        return result;
    }

    /**
     * The axis-by-axis comparison of C's {@code Overlap} (interval.c), {@code smap} taking the base
     * Frame of {@code that} to the base Frame of this Interval.
     */
    private int intervalOverlap(Interval that, Mapping smap) {
        Region uncThis = getUncFrm(FrameSet.AST__BASE);
        Region uncTemp = that.getUncFrm(FrameSet.AST__BASE);
        Frame frm = baseFrame();
        Region uncThat = uncTemp.mapRegionInto(smap, frm);
        int nc = frm.getNaxes();
        double[] lbnduThis = new double[nc];
        double[] ubnduThis = new double[nc];
        uncThis.regionBounds(lbnduThis, ubnduThis);
        double[] lbnduThat = new double[nc];
        double[] ubnduThat = new double[nc];
        uncThat.regionBounds(lbnduThat, ubnduThat);
        double[][] ptrThato = that.getPoints();
        double[][] ptrThat = smap.transform(ptrThato, 2, true);
        double[][] ptrThis = getPoints();
        int result = 0;
        for (int ic = 0; ic < nc; ic++) {
            double errThis = ubnduThis[ic] - lbnduThis[ic];
            double errThat = ubnduThat[ic] - lbnduThat[ic];
            double err = Math.sqrt(errThat * errThat + errThis * errThis);
            double lbThis = ptrThis[ic][0];
            double ubThis = ptrThis[ic][1];
            double lbThat = ptrThat[ic][0];
            double ubThat = ptrThat[ic][1];
            // The mapped limits of "that" may have been swapped; swap them back so that an
            // included range is not taken as an excluded one.
            if (lbThat != AST__BAD && ubThat != AST__BAD) {
                if (ptrThato[ic][0] < ptrThato[ic][1]) {
                    if (lbThat > ubThat) {
                        double tmp = lbThat;
                        lbThat = ubThat;
                        ubThat = tmp;
                    }
                } else if (lbThat < ubThat) {
                    double tmp = lbThat;
                    lbThat = ubThat;
                    ubThat = tmp;
                }
            }
            if (!that.getClosed()) {
                if (lbThat != AST__BAD && lbThat < Double.MAX_VALUE) {
                    lbThat += DBL_EPSILON * Math.abs(lbThat);
                }
                if (ubThat != AST__BAD && ubThat > -Double.MAX_VALUE) {
                    ubThat -= DBL_EPSILON * Math.abs(ubThat);
                }
            }
            if (!getClosed()) {
                if (lbThis != AST__BAD && lbThis < Double.MAX_VALUE) {
                    lbThis += DBL_EPSILON * Math.abs(lbThis);
                }
                if (ubThis != AST__BAD && ubThis > -Double.MAX_VALUE) {
                    ubThis -= DBL_EPSILON * Math.abs(ubThis);
                }
            }
            if (lbThis == AST__BAD) {
                lbThis = -Double.MAX_VALUE;
            }
            if (ubThis == AST__BAD) {
                ubThis = Double.MAX_VALUE;
            }
            if (lbThat == AST__BAD) {
                lbThat = -Double.MAX_VALUE;
            }
            if (ubThat == AST__BAD) {
                ubThat = Double.MAX_VALUE;
            }
            boolean incThis = lbThis <= ubThis;
            if (!incThis) {
                double tmp = lbThis;
                lbThis = ubThis;
                ubThis = tmp;
            }
            boolean incThat = lbThat <= ubThat;
            if (!incThat) {
                double tmp = lbThat;
                lbThat = ubThat;
                ubThat = tmp;
            }
            boolean lbEqual = MappingHelpers.astEquals(lbThis, lbThat, 1.0E9);
            if (!lbEqual && Math.abs(lbThis) != Double.MAX_VALUE && Math.abs(lbThat) != Double.MAX_VALUE) {
                lbEqual = Math.abs(lbThis - lbThat) <= err;
            }
            boolean ubEqual = MappingHelpers.astEquals(ubThis, ubThat, 1.0E9);
            if (!ubEqual && Math.abs(ubThis) != Double.MAX_VALUE && Math.abs(ubThat) != Double.MAX_VALUE) {
                ubEqual = Math.abs(ubThis - ubThat) <= err;
            }
            int ov;
            if (lbEqual && ubEqual) {
                ov = incThis == incThat ? 5 : 6;
            } else if (incThis) {
                if (incThat) {
                    if (lbThat <= lbThis && ubThat >= ubThis) {
                        ov = 2;
                    } else if (lbThat >= lbThis && ubThat <= ubThis) {
                        ov = 3;
                    } else if (ubThat >= lbThis && lbThat <= ubThis) {
                        ov = 4;
                    } else {
                        ov = 1;
                    }
                } else if (lbThat <= lbThis && ubThat >= ubThis) {
                    ov = 1;
                } else if (lbThat >= ubThis || ubThat <= lbThis) {
                    ov = 2;
                } else if (lbThis == -Double.MAX_VALUE && ubThis == Double.MAX_VALUE) {
                    ov = 3;
                } else {
                    ov = 4;
                }
            } else if (incThat) {
                if (lbThis <= lbThat && ubThis >= ubThat) {
                    ov = 1;
                } else if (lbThis >= ubThat || ubThis <= lbThat) {
                    ov = 3;
                } else if (lbThat == -Double.MAX_VALUE && ubThat == Double.MAX_VALUE) {
                    ov = 2;
                } else {
                    ov = 4;
                }
            } else {
                ov = 4;
            }
            if (ic == 0) {
                result = ov;
            } else if (result > NEW_RESULT.length || ov > NEW_RESULT.length) {
                // C indexes its table beyond its end when an axis's limits are each other's
                // negation (6) and there is more than one axis.
                throw Ast.err(
                        AstException.AST__INTER,
                        "astOverlap(" + astClass() + "): Cannot combine the overlap codes " + result + " and " + ov
                                + " of two axes (internal AST programming error).");
            } else {
                result = NEW_RESULT[result - 1][ov - 1];
            }
            if (result == 1) {
                break;
            }
        }
        boolean negThis = getNegated();
        boolean negThat = that.getNegated();
        switch (result) {
            case 1:
                if (negThis) {
                    result = negThat ? 4 : 3;
                } else if (negThat) {
                    result = 2;
                }
                break;
            case 2:
                if (negThis) {
                    result = negThat ? 3 : 4;
                } else if (negThat) {
                    result = 1;
                }
                break;
            case 3:
                if (negThis) {
                    result = negThat ? 2 : 1;
                } else if (negThat) {
                    result = 4;
                }
                break;
            case 4:
            case 6:
                break;
            case 5:
                if (negThis) {
                    result = negThat ? 5 : 6;
                } else if (negThat) {
                    result = 6;
                }
                break;
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "astOverlap(" + astClass() + "): Unexpected overlap code " + result
                                + " (internal AST programming error).");
        }
        return result;
    }

    /**
     * The input feeding each output of {@code map}, if each output is fed by one input and the
     * Mapping has as many inputs as outputs, or null (C's {@code OneToOne}, interval.c).
     */
    static int[] oneToOne(Mapping map) {
        int nout = map.getNout();
        if (map.getNin() != nout) {
            return null;
        }
        int[] result = new int[nout];
        String cls = map.astClass();
        if ("WinMap".equals(cls) || "ZoomMap".equals(cls) || "UnitMap".equals(cls) || "ShiftMap".equals(cls)) {
            for (int i = 0; i < nout; i++) {
                result[i] = i;
            }
            return result;
        }
        for (int i = 0; i < nout; i++) {
            MapSplit split = map.mapSplit(new int[] {i});
            if (split == null || split.outputs.length != 1) {
                return null;
            }
            result[split.outputs[0]] = i;
        }
        return result;
    }
}
