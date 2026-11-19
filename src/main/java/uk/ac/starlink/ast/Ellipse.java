package uk.ac.starlink.ast;

import java.util.Objects;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A Region of an ellipse within a two-dimensional Frame. Its axes are geodesics: in a SkyFrame,
 * arcs of great circles, the secondary axis at right angles to the primary one at the centre, and
 * a point is inside if its components along and across the primary axis, measured as the Frame
 * measures distances, satisfy the equation of the ellipse.
 *
 * <p>An Ellipse is described either by its centre and two points on its circumference, the
 * first the end of the primary axis, or by its centre, the half-lengths of its axes and the
 * angle of its primary axis. It holds its centre and the two points, which {@link
 * #getRegionPoints} gives; given the half-lengths, the points are the ends of the two axes.
 *
 * <p>Ported from C {@code ellipse.c}, with Rust {@code regions/ellipse.rs}.
 */
public class Ellipse extends Region {

    private static final double PIBY2 = Math.PI / 2;

    /** The centre in the base Frame, kept until the Ellipse changes. */
    private double[] centre;

    /** The end of the primary axis in the base Frame, kept until the Ellipse changes. */
    private double[] point1;

    /** The half-length of the primary axis, kept until the Ellipse changes. */
    private double a;

    /** The half-length of the secondary axis, kept until the Ellipse changes. */
    private double b;

    /**
     * The angle of the primary axis from the second axis towards the first, in radians, kept until
     * the Ellipse changes.
     */
    private double angle;

    /** The bounds of the boundary mesh on the first base Frame axis, set when the mesh is made. */
    private double lbx;

    private double ubx;

    /** The bounds of the boundary mesh on the second base Frame axis, set when the mesh is made. */
    private double lby;

    private double uby;

    /** Whether the values kept above must be worked out again (C's {@code stale}). */
    private boolean stale = true;

    /**
     * Creates an Ellipse (C's {@code astEllipse}). Points that do not determine an ellipse, such as
     * an axis of zero length, are not rejected until the Ellipse is used.
     *
     * @param frame the two-dimensional Frame the Ellipse is defined in; a copy is held. For a
     *     FrameSet or a Region, its current Frame
     * @param form 0 if {@code point1} and {@code point2} are points on the circumference, the
     *     first the end of the primary axis; 1 if {@code point1} holds the half-lengths of the
     *     primary and secondary axes and {@code point2[0]} the angle of the primary axis, in
     *     radians from the second axis towards the first
     * @param centre the centre, a value for each axis
     * @param point1 the end of the primary axis, or the half-lengths of the axes
     * @param point2 another point on the circumference, or the angle of the primary axis
     * @param unc the uncertainty of positions on the boundary, such as a Box, Circle or Ellipse; a
     *     copy is held. Null for the default, a Box one millionth of the size of the Ellipse
     * @throws AstException with status {@code AST__BADIN} if {@code form} is neither 0 nor 1, the
     *     Frame does not have two axes or a coordinate is bad, {@code AST__DIMIN} if an array has
     *     fewer values than needed, or the error of {@link #setUnc} for the uncertainty
     */
    public Ellipse(Frame frame, int form, double[] centre, double[] point1, double[] point2, Region unc) {
        super(frame, ellipsePoints(frame, form, centre, point1, point2), unc);
    }

    /**
     * The defining points of an Ellipse, as C's {@code astInitEllipse} makes them: the centre, the
     * end of the primary axis and another point on the circumference, found from the half-lengths
     * and angle as the ends of the two axes ({@code astOffset2}).
     */
    private static double[][] ellipsePoints(Frame frame, int form, double[] centre, double[] point1, double[] point2) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(centre, "centre");
        Objects.requireNonNull(point1, "point1");
        Objects.requireNonNull(point2, "point2");
        if (form != 0 && form != 1) {
            // C goes on to ask the Frame its number of axes, which gives 0 once an error is
            // reported, and reports that too.
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astInitEllipse(Ellipse): The value supplied for parameter \"form\" (" + form
                            + ") is illegal - it should be 0 or 1 (programming error).\n"
                            + naxesMessage(frame, 0));
        }
        int nc = frame.getNaxes();
        if (nc != 2) {
            throw Ast.err(AstException.AST__BADIN, naxesMessage(frame, nc));
        }
        checkLength("centre", centre, 2);
        checkLength("point1", point1, 2);
        checkLength("point2", point2, form == 1 ? 1 : 2);
        double[] p1;
        double[] p2;
        if (form == 1) {
            p1 = new double[2];
            p2 = new double[2];
            frame.offset2(centre, point2[0], point1[0], p1);
            frame.offset2(centre, point2[0] + PIBY2, point1[1], p2);
        } else {
            p1 = point1;
            p2 = point2;
        }
        double[][] pts = new double[2][3];
        for (int i = 0; i < 2; i++) {
            if (isBad(centre[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitEllipse(Ellipse): The value of axis " + (i + 1)
                                + " is undefined at the ellipse centre.");
            }
            if (isBad(p1[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitEllipse(Ellipse): The value of axis " + (i + 1)
                                + " is undefined at point 1 on the circumference of the ellipse.");
            }
            if (isBad(p2[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitEllipse(Ellipse): The value of axis " + (i + 1)
                                + " is undefined at point 2 on the circumference of the ellipse.");
            }
            pts[i][0] = centre[i];
            pts[i][1] = p1[i];
            pts[i][2] = p2[i];
        }
        return pts;
    }

    /** C's message for a Frame that does not have two axes. */
    private static String naxesMessage(Frame frame, int nc) {
        return "astInitEllipse(Ellipse): The supplied " + frame.astClass() + " has " + nc
                + " axes - ellipses must have exactly 2 axes.";
    }

    /** An {@code AST__DIMIN} error if {@code values} has fewer than {@code n} elements. */
    private static void checkLength(String what, double[] values, int n) {
        if (values.length < n) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astEllipse(Ellipse): The " + what + " array holds " + values.length
                            + " values.\nThis should not be less than " + n + ".");
        }
    }

    /**
     * Loads an Ellipse from its description, as C's {@code astLoadEllipse} does: an Ellipse has no
     * items of its own.
     */
    Ellipse(NativeNode node) {
        super(node);
        node.section("Ellipse");
    }

    @Override
    String dumpComment() {
        return "Elliptical region";
    }

    /** Writes the Ellipse class's part of a description, as C's {@code Dump} (ellipse.c) does: it has no items. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
    }

    /** Completes a copy as C's {@code Copy} (ellipse.c) does: the kept values are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        centre = centre == null ? null : centre.clone();
        point1 = point1 == null ? null : point1.clone();
    }

    /** Discards the kept values (C's {@code ResetCache}, ellipse.c), then what the Region keeps. */
    @Override
    void resetCache() {
        stale = true;
        super.resetCache();
    }

    /** Gives the Ellipse a new FrameSet (C's {@code SetRegFS}, ellipse.c), discarding the kept values. */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        resetCache();
    }

    /**
     * Works out the centre, the end of the primary axis, the half-lengths of the axes and the
     * angle of the primary axis in the base Frame, unless they are kept (C's {@code Cache},
     * ellipse.c). The mesh bounds are made unbounded until the mesh is made. C marks its values
     * up to date even when the points do not determine an ellipse; here they stay out of date, so
     * that each use reports the error.
     *
     * @throws AstException with status {@code AST__BADIN} if the points do not determine an
     *     ellipse
     */
    private void cache() {
        if (!stale) {
            return;
        }
        Frame frm = baseFrame();
        double[][] pts = getPoints();
        double[] c = {pts[0][0], pts[1][0]};
        double[] p1 = {pts[0][1], pts[1][1]};
        double[] p2 = {pts[0][2], pts[1][2]};
        double[] pars = calcPars(frm, c, p1, p2);
        if (pars[0] <= 0.0 || pars[0] == AST__BAD || pars[1] <= 0.0 || pars[1] == AST__BAD) {
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astInitEllipse(" + astClass() + "): The supplied points do not determine an ellipse.");
        }
        centre = c;
        point1 = p1;
        a = pars[0];
        b = pars[1];
        angle = pars[2];
        lbx = -Double.MAX_VALUE;
        ubx = Double.MAX_VALUE;
        lby = -Double.MAX_VALUE;
        uby = Double.MAX_VALUE;
        stale = false;
    }

    /**
     * The half-lengths of the primary and secondary axes and the angle of the primary axis, from
     * the second axis towards the first (C's {@code CalcPars}, ellipse.c): the primary axis runs
     * from the centre to {@code point1}; the secondary half-length is found from the components of
     * {@code point2} along and across the primary axis ({@code astResolve}). When the primary axis
     * has no length the other two values are 0. C also finds the end of the secondary axis, which
     * nothing here uses.
     */
    private static double[] calcPars(Frame frm, double[] centre, double[] point1, double[] point2) {
        double pa = frm.distance(centre, point1);
        double pb = 0.0;
        double ang = 0.0;
        if (pa > 0.0) {
            double[] point3 = new double[2];
            double[] d = frm.resolve(centre, point1, point2, point3);
            double x = d[0];
            double y = d[1];
            pb = pa * pa - x * x;
            if (pb > 0.0) {
                pb = pa * y / Math.sqrt(pb);
            }
            point3[0] = centre[0];
            point3[1] = centre[1] + Math.abs(0.1 * pa);
            ang = frm.angle(point3, centre, point1);
        }
        return new double[] {pa, pb, ang};
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Which base Frame points are in the Ellipse for the Negated and Closed values given (C's
     * {@code Transform}, ellipse.c): each point is resolved into components along and across the
     * primary axis from the centre ({@code astResolvePoints}), and its elliptical distance, 1 on
     * the boundary, compared with 1 as {@link Circle} compares a distance with its radius. A point
     * with a bad component is in neither.
     */
    private boolean[] inside(double[][] base, int npoint, boolean neg, boolean closed) {
        cache();
        Frame frm = baseFrame();
        double[][] res = frm.resolvePoints(centre, point1, base, npoint);
        double c1 = 1.0 / (a * a);
        double c2 = 1.0 / (b * b);
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            double px = res[0][point];
            double py = res[1][point];
            boolean in;
            if (px == AST__BAD || py == AST__BAD) {
                in = false;
            } else {
                double d = c1 * px * px + c2 * py * py;
                if (d != AST__BAD) {
                    if (neg) {
                        in = closed ? d >= 1.0 : d > 1.0;
                    } else {
                        in = closed ? d <= 1.0 : d < 1.0;
                    }
                } else {
                    in = false;
                }
            }
            mask[point] = in;
        }
        return mask;
    }

    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        return inside(base, npoint, false, closedForBaseInside());
    }

    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        return inside(base, npoint, getNegated(), getClosed());
    }

    // ------------------------------------------------------------------
    // Bounds and boundary
    // ------------------------------------------------------------------

    /**
     * The bounding box in the base Frame (C's {@code RegBaseBox}, ellipse.c): the bounds of the
     * boundary mesh, made if the Region has not kept one.
     */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        if (keptBaseMesh() == null) {
            regBaseMesh();
        }
        lbnd[0] = lbx;
        ubnd[0] = ubx;
        lbnd[1] = lby;
        ubnd[1] = uby;
    }

    /**
     * A mesh over the boundary in the base Frame (C's {@code RegBaseMesh}, ellipse.c), kept until
     * the Ellipse changes: MeshSize points at equal steps of the parametric angle {@code phi},
     * each found by moving {@code a*cos(phi)} from the centre along the primary axis and then
     * {@code b*sin(phi)} at right angles to it ({@code astOffset2}). Its bounds are taken as axis
     * distances from the centre, each axis by its own rule, so that an Ellipse across RA 0 is
     * bounded correctly, and widened to take in any singularity inside the Ellipse ({@code
     * astNormBox}).
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        double lx = Double.MAX_VALUE;
        double ux = -Double.MAX_VALUE;
        double ly = Double.MAX_VALUE;
        double uy = -Double.MAX_VALUE;
        cache();
        Frame frm = baseFrame();
        int np = getMeshSize();
        double delta = 2 * Math.PI / np;
        double[][] result = newPoints(2, np);
        double phi = 0.0;
        double[] p = new double[2];
        double[] p2 = new double[2];
        for (int i = 0; i < np; i++) {
            double dx = a * Math.cos(phi);
            double dy = b * Math.sin(phi);
            double ang = frm.offset2(centre, angle, dx, p);
            frm.offset2(p, ang + PIBY2, dy, p2);
            result[0][i] = p2[0];
            result[1][i] = p2[1];
            if (p2[0] != AST__BAD && p2[1] != AST__BAD) {
                double dist = frm.axDistance(1, centre[0], p2[0]);
                if (dist < lx) {
                    lx = dist;
                } else if (dist > ux) {
                    ux = dist;
                }
                dist = frm.axDistance(2, centre[1], p2[1]);
                if (dist < ly) {
                    ly = dist;
                } else if (dist > uy) {
                    uy = dist;
                }
            }
            phi += delta;
        }
        keepBaseMesh(result);
        Ellipse reg = (Ellipse) copy();
        reg.setRegFS(frm);
        reg.setNegatedValue(false);
        double[] lbnd = {centre[0] + lx, centre[1] + ly};
        double[] ubnd = {centre[0] + ux, centre[1] + uy};
        frm.normBox(lbnd, ubnd, reg);
        lbx = lbnd[0];
        ubx = ubnd[0];
        lby = lbnd[1];
        uby = ubnd[1];
        return result;
    }

    // ------------------------------------------------------------------
    // Centre and boundary points
    // ------------------------------------------------------------------

    /**
     * The centre of the Ellipse, or a move of it (C's {@code RegCentre}, ellipse.c): each defining
     * point is moved by the distance from the old centre to the new, along a geodesic leaving it at
     * the angle the geodesic from the old centre to the new leaves the old centre ({@code
     * astAxAngle} from the second axis). A bad value in the new centre leaves that axis where it
     * was. C's move takes the axis and point indices of its points the wrong way round, reading
     * beyond its points; here each point is moved, as C's comment describes.
     */
    @Override
    // The loops replacing bad centre values copy only some elements.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    double[] regCentre(double[] cen, double[][] ptr, int index, int ifrm) {
        cache();
        int ncc = getRegFS().getNout();
        int ncb = 2;
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
            bc = new double[ncb];
            for (int ic = 0; ic < ncb; ic++) {
                double axval = cen != null ? cen[ic] : ptr[ic][index];
                bc[ic] = axval != AST__BAD ? axval : centre[ic];
            }
        }
        Frame frm = baseFrame();
        double ang = frm.axAngle(centre, bc, 2);
        double dist = frm.distance(centre, bc);
        double[] pa = new double[2];
        double[] pb = new double[2];
        for (int ip = 0; ip < 3; ip++) {
            pa[0] = rptr[0][ip];
            pa[1] = rptr[1][ip];
            frm.offset2(pa, ang, dist, pb);
            rptr[0][ip] = pb[0];
            rptr[1][ip] = pb[1];
        }
        resetCache();
        return null;
    }

    /**
     * Whether every point is on the boundary of the Ellipse (C's {@code RegPins}, ellipse.c):
     * inside an Ellipse whose half-lengths are larger than this one's, and outside one whose
     * half-lengths are smaller (but at least {@code 1e-6} of the border), by half of a border
     * width that is half the sum of the diagonals of the bounding boxes of this Ellipse's
     * uncertainty and the points' uncertainty, each re-centred on the centre of this Ellipse.
     *
     * @throws AstException with status {@code AST__INTER} if the points or the uncertainty do not
     *     have two axes
     */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        if (pset.length != 2) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegPins(" + astClass() + "): Illegal number of axis values per point (" + pset.length
                            + ") in the supplied PointSet - should be 2 (internal AST programming error).");
        }
        if (pointUnc != null && pointUnc.getNaxes() != 2) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegPins(" + astClass() + "): Illegal number of axes (" + pointUnc.getNaxes()
                            + ") in the supplied uncertainty Region - should be 2 (internal AST programming error).");
        }
        double[] safe = regCentre(null, null, 0, FrameSet.AST__BASE);
        Region tunc = getUncFrm(FrameSet.AST__BASE);
        tunc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
        double[] lbndTunc = new double[2];
        double[] ubndTunc = new double[2];
        tunc.regionBounds(lbndTunc, ubndTunc);
        Frame frm = baseFrame();
        double l1 = frm.distance(lbndTunc, ubndTunc);
        double l2;
        if (pointUnc != null) {
            pointUnc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
            double[] lbndUnc = new double[2];
            double[] ubndUnc = new double[2];
            pointUnc.regionBounds(lbndUnc, ubndUnc);
            l2 = frm.distance(lbndUnc, ubndUnc);
        } else {
            l2 = 0.0;
        }
        cache();
        double drad = 0.5 * (l1 + l2);
        double[] ang = {angle};
        Ellipse large = new Ellipse(frm, 1, centre, new double[] {a + 0.5 * drad, b + 0.5 * drad}, ang, null);
        double[] p1 = {a - 0.5 * drad, b - 0.5 * drad};
        double lim = 1.0E-6 * drad;
        if (p1[0] < lim) {
            p1[0] = lim;
        }
        if (p1[1] < lim) {
            p1[1] = lim;
        }
        Ellipse small = new Ellipse(frm, 1, centre, p1, ang, null);
        small.negate();
        int np = pset[0].length;
        double[][] ps1 = large.transform(pset, np, true);
        double[][] ps2 = small.transform(ps1, np, true);
        return Circle.onBoundary(ps2, np, mask);
    }

    // ------------------------------------------------------------------
    // The best fitting Ellipse
    // ------------------------------------------------------------------

    /**
     * The best fitting Ellipse through a mesh of points (C's {@code astBestEllipse}), centred on
     * {@code cen}, in the Frame {@code unc} represents and with {@code unc} as its uncertainty.
     * The farthest point of the mesh from the centre and its good neighbours on either side give
     * three distances against angle, through which a quadratic is fitted; its turning point is the
     * end of the primary axis. The secondary half-length is the mean of those each other point of
     * the mesh would give, weighted by its component across the primary axis. As C notes, the
     * method assumes the mesh covers the whole ellipse evenly.
     *
     * @param mesh the points, {@code [2][npoint]}, in the Frame {@code unc} represents
     * @param cen the centre
     * @param unc the uncertainty of each point of the mesh
     * @return the Ellipse, or null if the quadratic or the secondary axis cannot be found
     * @throws AstException with status {@code AST__INTER} if every point of the mesh is bad or at
     *     the centre, where C reads beyond its arrays
     */
    static Ellipse bestEllipse(double[][] mesh, double[] cen, Region unc) {
        int np = mesh[0].length;
        double[] dist = new double[np];
        double[] ang = new double[np];
        Frame frm = unc.regFrame();
        double[] p = new double[2];
        int ip;
        for (ip = 0; ip < np; ip++) {
            p[0] = mesh[0][ip];
            p[1] = mesh[1][ip];
            dist[ip] = frm.distance(cen, p);
            if (dist[ip] != AST__BAD && dist[ip] != 0.0) {
                break;
            }
            ang[ip] = AST__BAD;
            dist[ip] = AST__BAD;
        }
        if (ip == np) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astBestEllipse(Ellipse): No point of the mesh is good and away from the centre "
                            + "(internal AST programming error).");
        }
        double[] aa = new double[2];
        frm.offset2(cen, 0.0, dist[ip], aa);
        ang[ip] = frm.angle(aa, cen, p);
        int maxat = ip;
        double r2 = dist[maxat];
        for (ip++; ip < np; ip++) {
            p[0] = mesh[0][ip];
            p[1] = mesh[1][ip];
            dist[ip] = frm.distance(cen, p);
            ang[ip] = frm.angle(aa, cen, p);
            if (dist[ip] != AST__BAD && dist[ip] > r2) {
                r2 = dist[ip];
                maxat = ip;
            }
        }
        double t2 = 0.0;
        double r3 = AST__BAD;
        double t3 = AST__BAD;
        double a0 = ang[maxat];
        for (ip = maxat + 1; ip < np; ip++) {
            if (dist[ip] != AST__BAD) {
                r3 = dist[ip];
                t3 = Pal.drange(ang[ip] - a0);
                break;
            }
        }
        if (r3 == AST__BAD) {
            for (ip = 0; ip < maxat; ip++) {
                if (dist[ip] != AST__BAD) {
                    r3 = dist[ip];
                    t3 = Pal.drange(ang[ip] - a0);
                    break;
                }
            }
        }
        double r1 = AST__BAD;
        double t1 = AST__BAD;
        for (ip = maxat - 1; ip > -1; ip--) {
            if (dist[ip] != AST__BAD) {
                r1 = dist[ip];
                t1 = Pal.drange(ang[ip] - a0);
                break;
            }
        }
        if (r1 == AST__BAD) {
            for (ip = np - 1; ip > maxat; ip--) {
                if (dist[ip] != AST__BAD) {
                    r1 = dist[ip];
                    t1 = Pal.drange(ang[ip] - a0);
                    break;
                }
            }
        }
        double qa = r2 - r1;
        double qb = t2 - t1;
        double qc = t2 * t2 - t1 * t1;
        double qd = r3 - r2;
        double qe = t3 - t2;
        double qf = t3 * t3 - t2 * t2;
        double den = qc * qe - qb * qf;
        if (den == 0.0) {
            return null;
        }
        double c1 = (qd * qc - qa * qf) / den;
        double c2 = (qa * qe - qd * qb) / den;
        double c0 = r1 - c1 * t1 - c2 * t1 * t1;
        double mx;
        double at;
        if (c2 < 0.0) {
            mx = (4 * c0 * c2 - c1 * c1) / (4 * c2);
            at = a0 - c1 / (2 * c2);
        } else {
            mx = r2;
            at = a0 - t2;
        }
        double[] pa = new double[2];
        frm.offset2(cen, at, mx, pa);
        double[][] ps2 = frm.resolvePoints(cen, pa, mesh, np);
        double smn = 0.0;
        double sw = 0.0;
        for (ip = 0; ip < np; ip++) {
            double px = ps2[0][ip];
            double py = ps2[1][ip];
            if (px != AST__BAD && py != AST__BAD) {
                double dd = mx * mx - px * px;
                if (dd > 0.0) {
                    smn += Math.abs(mx * py * py) / Math.sqrt(dd);
                    sw += Math.abs(py);
                }
            }
        }
        if (sw > 0) {
            double mn = smn / sw;
            double[] pb = new double[2];
            frm.offset2(cen, at + PIBY2, mn, pb);
            return new Ellipse(frm, 0, cen, pa, pb, unc);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * Simplifies the Ellipse (C's {@code Simplify}, ellipse.c): after the Region-generic
     * simplification, the Ellipse is fitted in its current Frame, about its centre transformed
     * there, with a Circle ({@link Circle#bestCircle}) and, failing that, an Ellipse ({@link
     * #bestEllipse}); the first fit whose boundary holds every point of the Ellipse's boundary mesh
     * in that Frame, within the uncertainty, is the result. The fit is made whatever the Mapping
     * from the base to the current Frame, a UnitMap included, as C makes it. A new Region takes
     * this Ellipse's Negated, Closed, MeshSize, FillFactor and Adaptive values, and has no
     * uncertainty if this one has none set.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        Region nw = parentSimplify();
        boolean simpler = nw != this;
        Region fit = Circle.fittedCircleOrEllipse(this, nw);
        if (fit != null) {
            nw = fit;
            simpler = true;
        }
        if (simpler) {
            nw.regOverlay(this, true);
            return nw;
        }
        return this;
    }
}
