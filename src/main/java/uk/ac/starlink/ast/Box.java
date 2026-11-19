package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * A Region of a box with sides parallel to the axes of a Frame: on each axis, an interval centred
 * on the box's centre. In a Frame whose axes are not flat, such as a SkyFrame, the box is centred
 * on its centre, and its width on each axis is measured along the axis through the centre.
 *
 * <p>A Box is described either by its centre and a corner or by two opposite corners. It holds
 * its centre and a corner, which {@link #getRegionPoints} gives.
 *
 * <p>Ported from C {@code box.c}, with Rust {@code regions/box_region.rs}.
 */
public class Box extends Region {

    /** The half-width on each base Frame axis, kept until the Box changes. */
    private double[] extent;

    /** The centre on each base Frame axis, kept until the Box changes. */
    private double[] centre;

    /** The lower limit on each base Frame axis, kept until the Box changes. */
    private double[] lo;

    /** The upper limit on each base Frame axis, kept until the Box changes. */
    private double[] hi;

    /** The geodesic half-length of each side, kept until the Box changes. */
    private double[] geolen;

    /** Whether the values kept above must be worked out again (C's {@code stale}). */
    private boolean stale = true;

    /**
     * Creates a Box (C's {@code astBox}).
     *
     * @param frame the Frame the Box is defined in; a copy is held. For a FrameSet or a Region, its
     *     current Frame
     * @param form 1 if {@code point1} and {@code point2} are opposite corners of the Box; any other
     *     value if {@code point1} is its centre and {@code point2} a corner
     * @param point1 the centre or a corner, a value for each axis
     * @param point2 a corner, a value for each axis
     * @param unc the uncertainty of positions in the Box, such as a Box, Circle or Ellipse; a copy
     *     is held. Null for the default, a Box one millionth of the size of this one
     * @throws AstException with status {@code AST__BADIN} if a coordinate is bad, {@code
     *     AST__DIMIN} if an array has fewer values than the Frame has axes, or the error of {@link
     *     #setUnc} for the uncertainty
     */
    public Box(Frame frame, int form, double[] point1, double[] point2, Region unc) {
        super(frame, boxPoints(frame, form, point1, point2), unc);
    }

    /**
     * The defining points of a Box, as C's {@code astInitBox} makes them: the centre, then the
     * corner, the centre being the mean of the two points given when they are corners.
     */
    private static double[][] boxPoints(Frame frame, int form, double[] point1, double[] point2) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(point1, "point1");
        Objects.requireNonNull(point2, "point2");
        int nc = frame.getNaxes();
        if (nc < 1) {
            throw Ast.err(
                    AstException.AST__NCOIN,
                    "astInitPointSet(PointSet): Number of coordinates per point (" + nc + ") is not valid.");
        }
        checkLength("point1", point1, nc);
        checkLength("point2", point2, nc);
        double[][] pts = new double[nc][2];
        for (int i = 0; i < nc; i++) {
            if (isBad(point1[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitBox(Box): The value of axis " + (i + 1) + " is undefined at point 1 of the box.");
            }
            if (isBad(point2[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitBox(Box): The value of axis " + (i + 1) + " is undefined at point 2 of the box.");
            }
            pts[i][0] = point1[i];
            pts[i][1] = point2[i];
        }
        if (form == 1) {
            for (int i = 0; i < nc; i++) {
                pts[i][0] = 0.5 * (point1[i] + point2[i]);
            }
        }
        return pts;
    }

    /** An {@code AST__DIMIN} error if {@code values} has fewer than {@code nc} elements. */
    private static void checkLength(String what, double[] values, int nc) {
        if (values.length < nc) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astBox(Box): The " + what + " array holds " + values.length
                            + " values.\nThis should not be less than " + nc + ".");
        }
    }

    /** Loads a Box from its description, as C's {@code astLoadBox} does: a Box has no items of its own. */
    Box(NativeNode node) {
        super(node);
        node.section("Box");
    }

    @Override
    String dumpComment() {
        return "Axis intervals";
    }

    /** Writes the Box class's part of a description, as C's {@code Dump} (box.c) does: it has no items. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
    }

    /** Completes a copy as C's {@code Copy} (box.c) does: the kept values are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        extent = extent == null ? null : extent.clone();
        centre = centre == null ? null : centre.clone();
        lo = lo == null ? null : lo.clone();
        hi = hi == null ? null : hi.clone();
        geolen = geolen == null ? null : geolen.clone();
    }

    /** Discards the kept values (C's {@code ResetCache}, box.c), then what the Region keeps. */
    @Override
    void resetCache() {
        stale = true;
        super.resetCache();
    }

    /** Gives the Box a new FrameSet (C's {@code SetRegFS}, box.c), discarding the kept values. */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        resetCache();
    }

    /**
     * Works out the centre, half-width, limits and geodesic half-lengths on each base Frame axis,
     * unless they are kept (C's {@code Cache}, box.c). The half-width is the axis distance from the
     * centre to the corner.
     */
    private void cache() {
        if (!stale) {
            return;
        }
        double[][] pts = getPoints();
        Frame frm = baseFrame();
        int nc = getRegFS().getNin();
        double[] c = new double[nc];
        double[] h = new double[nc];
        for (int i = 0; i < nc; i++) {
            c[i] = pts[i][0];
            h[i] = pts[i][1];
        }
        double[] g = geoLengths(frm, nc, c, h);
        double[] e = new double[nc];
        double[] l = new double[nc];
        for (int i = 0; i < nc; i++) {
            e[i] = Math.abs(frm.axDistance(i + 1, pts[i][1], c[i]));
            l[i] = c[i] - e[i];
            h[i] = c[i] + e[i];
        }
        extent = e;
        centre = c;
        lo = l;
        hi = h;
        geolen = g;
        stale = false;
    }

    /**
     * The geodesic half-length of each side of a box (C's {@code GeoLengths}): the distance along
     * the path from the centre that changes one axis at a time to the corner's value.
     */
    private static double[] geoLengths(Frame frm, int nc, double[] centre, double[] corner) {
        double[] result = new double[nc];
        double[] p1 = centre.clone();
        double[] p2 = centre.clone();
        for (int i = 0; i < nc; i++) {
            p2[i] = corner[i];
            result[i] = frm.distance(p1, p2);
            p1[i] = corner[i];
        }
        return result;
    }

    /**
     * The corner of a box with the given centre and geodesic half-lengths (C's {@code GeoCorner}):
     * from the centre, an offset of each half-length in turn, along the direction of increasing
     * value on that axis.
     */
    private static double[] geoCorner(Frame frm, int nc, double[] centre, double[] geolen) {
        double[] p1 = centre.clone();
        for (int i = 0; i < nc; i++) {
            double[] p2 = p1.clone();
            double off = geolen[i] != 0.0 ? 0.0001 * Math.abs(geolen[i]) : 1.0E-6;
            double off0 = Math.abs(1.0E-10 * centre[i]);
            if (off < off0) {
                off = off0;
            }
            p2[i] += off;
            p1 = frm.offset(p1, p2, geolen[i]);
        }
        return p1;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Which base Frame points are in the Box for the Negated and Closed values given (C's {@code
     * Transform}, box.c). The limits are the kept ones, except that where Negated and Closed
     * differ, an axis on which the Box is narrower than its uncertainty takes the half-width of
     * the uncertainty's bounding box. Each value is tested against its axis's limits as the axis
     * tests it ({@code astAxIn}), so that on a sky axis a value is moved by whole turns into
     * range. An un-negated Box holds a point if every value is within its limits; a negated Box,
     * if a value is outside them before any value is bad.
     */
    private boolean[] inside(double[][] base, int npoint, boolean neg, boolean closed) {
        cache();
        int nc = base.length;
        double[] l = lo.clone();
        double[] h = hi.clone();
        if (neg != closed) {
            Region unc = getUncFrm(FrameSet.AST__BASE);
            double[] lbndUnc = new double[nc];
            double[] ubndUnc = new double[nc];
            unc.regionBounds(lbndUnc, ubndUnc);
            for (int coord = 0; coord < nc; coord++) {
                double wid = 0.5 * (ubndUnc[coord] - lbndUnc[coord]);
                if (extent[coord] < wid) {
                    l[coord] = centre[coord] - wid;
                    h[coord] = centre[coord] + wid;
                }
            }
        }
        Frame frm = baseFrame();
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            boolean ok;
            if (neg) {
                ok = false;
                for (int coord = 0; coord < nc; coord++) {
                    double axval = base[coord][point];
                    if (isBad(axval)) {
                        break;
                    } else if (!frm.axIn(coord + 1, l[coord], h[coord], axval, !closed)) {
                        ok = true;
                        break;
                    }
                }
            } else {
                ok = true;
                for (int coord = 0; coord < nc; coord++) {
                    double axval = base[coord][point];
                    if (isBad(axval) || !frm.axIn(coord + 1, l[coord], h[coord], axval, closed)) {
                        ok = false;
                        break;
                    }
                }
            }
            mask[point] = ok;
        }
        return mask;
    }

    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        return inside(base, npoint, false, closedForBaseInside());
    }

    /**
     * Which base Frame points are in the Box, allowing for Negated (C's {@code Transform}, box.c),
     * which, for a negated Box, keeps a point with a value outside the Box even when a later
     * value is bad.
     */
    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        return inside(base, npoint, getNegated(), getClosed());
    }

    // ------------------------------------------------------------------
    // Bounds, boundary and grid
    // ------------------------------------------------------------------

    /** The centre plus and minus the half-width on each base Frame axis (C's {@code RegBaseBox}, box.c). */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        cache();
        int nc = getRegFS().getNin();
        for (int i = 0; i < nc; i++) {
            lbnd[i] = centre[i] - extent[i];
            ubnd[i] = centre[i] + extent[i];
        }
    }

    /**
     * A grid over the Box in the base Frame, the base Frame's own grid over the bounding box with
     * MeshSize points (C's {@code RegBaseGrid}, box.c), kept until the Box changes.
     *
     * @return the grid, or null if the Box is not bounded
     */
    @Override
    double[][] regBaseGrid() {
        double[][] kept = keptBaseGrid();
        if (kept != null) {
            return kept;
        }
        if (!getBounded()) {
            return null;
        }
        Frame frm = baseFrame();
        int naxes = frm.getNaxes();
        double[] lbnd = new double[naxes];
        double[] ubnd = new double[naxes];
        regBaseBox(lbnd, ubnd);
        double[][] result = frm.frameGrid(getMeshSize(), lbnd, ubnd);
        keepBaseGrid(result);
        return result;
    }

    /**
     * A mesh over the boundary of the Box in the base Frame (C's {@code RegBaseMesh}, box.c), kept
     * until the Box changes: the centre alone for a Box that is a single point; the two ends of a
     * one-dimensional Box; MeshSize points round the four edges of a two-dimensional Box, shared in
     * proportion to the edges' geodesic lengths when the axes share a metric, and equally
     * otherwise; and a grid on each face of a Box of more dimensions.
     *
     * @throws AstException with status {@code AST__INTER} if a two-dimensional Box with a metric
     *     has no length round its edges
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        final int npEdge = 50;
        Frame frm = baseFrame();
        int naxes = frm.getNaxes();
        double[] lbnd = new double[naxes];
        double[] ubnd = new double[naxes];
        regBaseBox(lbnd, ubnd);
        int np = getMeshSize();
        boolean single = true;
        for (int i = 0; i < naxes; i++) {
            if (ubnd[i] > lbnd[i]) {
                single = false;
                break;
            }
        }
        double[][] result;
        if (single) {
            result = new double[naxes][1];
            for (int i = 0; i < naxes; i++) {
                result[i][0] = 0.5 * (lbnd[i] + ubnd[i]);
            }
        } else if (naxes == 1) {
            result = new double[][] {{lbnd[0], ubnd[0]}};
        } else if (naxes == 2) {
            double[][] c = {
                {lbnd[0], lbnd[1]}, {lbnd[0], ubnd[1]}, {ubnd[0], ubnd[1]}, {ubnd[0], lbnd[1]}, {lbnd[0], lbnd[1]}
            };
            int[] pax = new int[1];
            Frame pfrm0 = frm.primaryFrame(1, pax);
            Frame pfrm1 = frm.primaryFrame(2, pax);
            boolean flat = ("Frame".equals(pfrm0.astClass()) || pfrm0.getNaxes() == 1)
                    && ("Frame".equals(pfrm1.astClass()) || pfrm1.getNaxes() == 1);
            boolean metric = frm.getUnit(1).equals(frm.getUnit(2)) || isSame(pfrm0, pfrm1);
            int[] npEdges = new int[4];
            if (metric) {
                double totalLen = 0.0;
                double[] edgeLen = new double[4];
                for (int iedge = 0; iedge < 4; iedge++) {
                    double dx = (c[iedge + 1][0] - c[iedge][0]) / (npEdge - 1);
                    double dy = (c[iedge + 1][1] - c[iedge][1]) / (npEdge - 1);
                    double[] p0 = {c[iedge][0], c[iedge][1]};
                    double[] p1 = new double[2];
                    double len = 0.0;
                    for (int i = 1; i < npEdge; i++) {
                        p1[0] = p0[0];
                        p1[1] = p0[1];
                        p0[0] = p1[0] + dx;
                        p0[1] = p1[1] + dy;
                        if (flat) {
                            len += Math.abs(dx + dy);
                        } else {
                            len += frm.distance(p0, p1);
                        }
                    }
                    edgeLen[iedge] = len;
                    totalLen += len;
                }
                if (totalLen > 0.0) {
                    double ppd = np / totalLen;
                    np = 0;
                    for (int iedge = 0; iedge < 4; iedge++) {
                        npEdges[iedge] = (int) (edgeLen[iedge] * ppd);
                        if (npEdges[iedge] == 0) {
                            npEdges[iedge] = 1;
                        }
                        np += npEdges[iedge];
                    }
                } else {
                    throw Ast.err(
                            AstException.AST__INTER,
                            "astRegBaseMesh(" + astClass() + "): Distance around box perimeter is zero (internal AST "
                                    + "programming error).");
                }
            } else {
                int np0 = (int) (0.25 * np);
                np = 0;
                for (int iedge = 0; iedge < 4; iedge++) {
                    npEdges[iedge] = np0;
                    np += np0;
                }
            }
            result = newPoints(2, np);
            int k = 0;
            for (int iedge = 0; iedge < 4; iedge++) {
                double dx = (c[iedge + 1][0] - c[iedge][0]) / npEdges[iedge];
                double dy = (c[iedge + 1][1] - c[iedge][1]) / npEdges[iedge];
                double lax = c[iedge][0];
                double lay = c[iedge][1];
                result[0][k] = lax;
                result[1][k] = lay;
                k++;
                for (int i = 1; i < npEdges[iedge]; i++, k++) {
                    lax += dx;
                    lay += dy;
                    result[0][k] = lax;
                    result[1][k] = lay;
                }
            }
        } else {
            // C divides the integers before taking the root.
            int perFace = np / (2 * naxes);
            int npAxis = 1 + (int) Math.pow(perFace, 1.0 / (naxes - 1));
            if (npAxis < 2) {
                npAxis = 2;
            }
            np = 2 * naxes;
            for (int iaxis = 1; iaxis < naxes; iaxis++) {
                np *= npAxis;
            }
            result = newPoints(naxes, np);
            int ip = 0;
            for (int iaxis = 0; iaxis < naxes; iaxis++) {
                ip += makeGrid(naxes, result, ip, lbnd, ubnd, npAxis, iaxis, ubnd[iaxis]);
                ip += makeGrid(naxes, result, ip, lbnd, ubnd, npAxis, iaxis, lbnd[iaxis]);
            }
            if (ip < np) {
                double[][] trimmed = new double[naxes][];
                for (int i = 0; i < naxes; i++) {
                    trimmed[i] = Arrays.copyOf(result[i], ip);
                }
                result = trimmed;
            }
        }
        keepBaseMesh(result);
        return result;
    }

    /** Whether two Frames are the same object, as C compares the pointers. */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    private static boolean isSame(Frame a, Frame b) {
        return a == b;
    }

    /**
     * Stores, from point {@code ip}, a regular grid of {@code npAxis} points along each axis
     * between the bounds, with axis {@code iaxis} fixed at {@code axval} (C's {@code MakeGrid},
     * box.c).
     *
     * @return the number of points stored
     */
    private static int makeGrid(
            int naxes, double[][] ptr, int ip, double[] lbnd, double[] ubnd, int npAxis, int iaxis, double axval) {
        int[] maxi = new int[naxes];
        int[] pi = new int[naxes];
        double[] step = new double[naxes];
        for (int i = 0; i < naxes; i++) {
            step[i] = (ubnd[i] - lbnd[i]) / (npAxis - 1);
            pi[i] = 0;
            maxi[i] = npAxis - 1;
        }
        if (iaxis >= 0) {
            maxi[iaxis] = 0;
            step[iaxis] = 0.0;
            pi[iaxis] = 0;
        }
        int ipp = ip;
        int i = 0;
        while (i < naxes) {
            for (i = 0; i < naxes; i++) {
                ptr[i][ipp] = i == iaxis ? axval : lbnd[i] + pi[i] * step[i];
            }
            ipp++;
            i = 0;
            while (i < naxes) {
                pi[i]++;
                if (pi[i] > maxi[i]) {
                    pi[i] = 0;
                    i++;
                } else {
                    break;
                }
            }
        }
        return ipp - ip;
    }

    // ------------------------------------------------------------------
    // Centre, boundary points and axes
    // ------------------------------------------------------------------

    /**
     * The centre of the Box, or a move of it (C's {@code RegCentre}, box.c). A move keeps the
     * geodesic half-lengths of the sides, placing the corner by offsetting from the new centre; a
     * bad value in the new centre leaves that axis where it was.
     */
    @Override
    // The loop replacing bad centre values copies only some elements.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    double[] regCentre(double[] cen, double[][] ptr, int index, int ifrm) {
        cache();
        FrameSet fs = getRegFS();
        int ncb = fs.getNin();
        int ncc = fs.getNout();
        if (ptr == null && cen == null) {
            if (ifrm == FrameSet.AST__CURRENT) {
                return regTranPoint(centre, true);
            }
            return centre.clone();
        }
        double[][] rptr = getPoints();
        double[] bc;
        if (ifrm == FrameSet.AST__CURRENT) {
            if (cen != null) {
                bc = regTranPoint(cen, false);
            } else {
                double[] tmp = new double[ncc];
                for (int ic = 0; ic < ncc; ic++) {
                    tmp[ic] = ptr[ic][index];
                }
                bc = regTranPoint(tmp, false);
            }
            for (int ic = 0; ic < ncb; ic++) {
                if (bc[ic] == AST__BAD) {
                    bc[ic] = centre[ic];
                }
            }
        } else {
            bc = centre;
            for (int ic = 0; ic < ncb; ic++) {
                double axval = cen != null ? cen[ic] : ptr[ic][index];
                if (axval != AST__BAD) {
                    bc[ic] = axval;
                }
            }
        }
        double[] corner = geoCorner(baseFrame(), ncb, bc, geolen);
        for (int ic = 0; ic < ncb; ic++) {
            rptr[ic][0] = bc[ic];
            rptr[ic][1] = corner[ic];
        }
        resetCache();
        return null;
    }

    /**
     * Whether every point is on the boundary of the Box (C's {@code RegPins}, box.c): inside a Box
     * larger than this one, and outside one smaller, by half the combined widths of the bounding
     * boxes of this Box's uncertainty and the points' uncertainty, each re-centred on the centre
     * of this Box.
     *
     * @throws AstException with status {@code AST__INTER} if the points or the uncertainty do not
     *     have the base Frame's number of axes
     */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        cache();
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
        tunc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
        double[] lbndTunc = new double[nc];
        double[] ubndTunc = new double[nc];
        tunc.regionBounds(lbndTunc, ubndTunc);
        double[] lbndUnc = null;
        double[] ubndUnc = null;
        if (pointUnc != null) {
            pointUnc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
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
                wid[i] = Math.abs(0.5 * frm.axDistance(i + 1, lbndTunc[i], ubndTunc[i]));
            }
        }
        double[] large = new double[nc];
        double[] small = new double[nc];
        for (int i = 0; i < nc; i++) {
            large[i] = centre[i] + extent[i] + wid[i];
            small[i] = extent[i] - wid[i];
            if (small[i] < 0.0) {
                small[i] = 0.0;
            }
            small[i] += centre[i];
        }
        Box largeBox = new Box(frm, 0, centre, large, null);
        Box smallBox = new Box(frm, 0, centre, small, null);
        smallBox.negate();
        int np = pset[0].length;
        double[][] ps1 = largeBox.transform(pset, np, true);
        double[][] ps2 = smallBox.transform(ps1, np, true);
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
     * A Box of the chosen base Frame axes (C's {@code RegBasePick}, box.c): the centre and corner
     * on those axes, in a Frame of those axes, with the uncertainty's own pick of them if that is a
     * Region.
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
        double[][] pts = getPoints();
        double[] cen = new double[naxes];
        double[] cor = new double[naxes];
        for (int i = 0; i < naxes; i++) {
            cen[i] = pts[axes[i]][0];
            cor[i] = pts[axes[i]][1];
        }
        return new Box(frm, 0, cen, cor, u);
    }
}
