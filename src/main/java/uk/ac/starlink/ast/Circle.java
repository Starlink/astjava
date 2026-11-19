package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * A Region of a circle or sphere within a Frame: the points whose distance from a centre, as the
 * Frame measures it, is no more than a radius. In a SkyFrame the distance is the arc of a great
 * circle, so a Circle is a spherical cap.
 *
 * <p>A Circle is described by its centre and either its radius or a point on its circumference.
 * It holds its centre and a point on its circumference, which {@link #getRegionPoints} gives;
 * given a radius, the point is where the circle crosses the first axis.
 *
 * <p>Ported from C {@code circle.c}, with Rust {@code regions/circle.rs}.
 */
public class Circle extends Region {

    /** The centre in the base Frame, kept until the Circle changes. */
    private double[] centre;

    /** The radius in the base Frame, kept until the Circle changes. */
    private double radius;

    /** The lower bound of the boundary mesh on each base Frame axis, set when the mesh is made. */
    private double[] lb;

    /** The upper bound of the boundary mesh on each base Frame axis, set when the mesh is made. */
    private double[] ub;

    /** Whether the values kept above must be worked out again (C's {@code stale}). */
    private boolean stale = true;

    /**
     * Creates a Circle (C's {@code astCircle}).
     *
     * @param frame the Frame the Circle is defined in; a copy is held. For a FrameSet or a Region,
     *     its current Frame
     * @param form 0 if {@code point} is a point on the circumference, 1 if {@code point[0]} is the
     *     radius
     * @param centre the centre, a value for each axis
     * @param point a point on the circumference, a value for each axis, or the radius, a distance
     *     as the Frame measures it
     * @param unc the uncertainty of positions on the boundary, such as a Box, Circle or Ellipse; a
     *     copy is held. Null for the default, a Box one millionth of the size of the Circle
     * @throws AstException with status {@code AST__BADIN} if {@code form} is neither 0 nor 1 or a
     *     coordinate is bad, {@code AST__DIMIN} if an array has fewer values than needed, or the
     *     error of {@link #setUnc} for the uncertainty
     */
    // C's astInitCircle works out the kept values once the Region is made; cache() reads only the
    // Region's own FrameSet and points.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    public Circle(Frame frame, int form, double[] centre, double[] point, Region unc) {
        super(frame, circlePoints(frame, form, centre, point), unc);
        cache();
    }

    /**
     * Creates a Circle given its centre and radius.
     *
     * @param frame the Frame the Circle is defined in; a copy is held
     * @param centre the centre, a value for each axis
     * @param radius the radius, a distance as the Frame measures it
     * @param unc the uncertainty of positions on the boundary, or null for the default
     * @throws AstException as for {@link #Circle(Frame, int, double[], double[], Region)}
     */
    public Circle(Frame frame, double[] centre, double radius, Region unc) {
        this(frame, 1, centre, new double[] {radius}, unc);
    }

    /**
     * Creates a Circle given its centre and a point on its circumference.
     *
     * @param frame the Frame the Circle is defined in; a copy is held
     * @param centre the centre, a value for each axis
     * @param point a point on the circumference, a value for each axis
     * @param unc the uncertainty of positions on the boundary, or null for the default
     * @throws AstException as for {@link #Circle(Frame, int, double[], double[], Region)}
     */
    public Circle(Frame frame, double[] centre, double[] point, Region unc) {
        this(frame, 0, centre, point, unc);
    }

    /**
     * The defining points of a Circle, as C's {@code astInitCircle} makes them: the centre, then a
     * point on the circumference, found from a radius where the circle crosses the first axis.
     */
    private static double[][] circlePoints(Frame frame, int form, double[] centre, double[] point) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(centre, "centre");
        Objects.requireNonNull(point, "point");
        if (form != 0 && form != 1) {
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astInitCircle(Circle): The value supplied for parameter \"form\" (" + form
                            + ") is illegal - it should be 0 or 1 (programming error).");
        }
        int nc = frame.getNaxes();
        if (nc < 1) {
            throw Ast.err(
                    AstException.AST__NCOIN,
                    "astInitPointSet(PointSet): Number of coordinates per point (" + nc + ") is not valid.");
        }
        checkLength("centre", centre, nc);
        checkLength("point", point, form == 1 ? 1 : nc);
        double[] cen = Arrays.copyOf(centre, nc);
        double[] circum = form == 1 ? circumPoint(frame, nc, cen, point[0]) : Arrays.copyOf(point, nc);
        double[][] pts = new double[nc][2];
        for (int i = 0; i < nc; i++) {
            if (isBad(cen[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitCircle(Circle): The value of axis " + (i + 1) + " is undefined at the circle centre.");
            }
            if (isBad(circum[i])) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astInitCircle(Circle): The value of axis " + (i + 1)
                                + " is undefined on the circumference of the circle.");
            }
            pts[i][0] = cen[i];
            pts[i][1] = circum[i];
        }
        return pts;
    }

    /** An {@code AST__DIMIN} error if {@code values} has fewer than {@code n} elements. */
    private static void checkLength(String what, double[] values, int n) {
        if (values.length < n) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astCircle(Circle): The " + what + " array holds " + values.length
                            + " values.\nThis should not be less than " + n + ".");
        }
    }

    /**
     * The point where a circle of the given centre and radius crosses the first axis (C's {@code
     * CircumPoint}): from the centre towards a point offset along the first axis by the radius,
     * the radius away.
     */
    private static double[] circumPoint(Frame frm, int naxes, double[] centre, double radius) {
        double[] work = Arrays.copyOf(centre, naxes);
        work[0] = frm.axOffset(1, work[0], radius);
        return frm.offset(Arrays.copyOf(centre, naxes), work, radius);
    }

    /** Loads a Circle from its description, as C's {@code astLoadCircle} does: a Circle has no items of its own. */
    // C's astLoadCircle works out the kept values once the Region is read.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    Circle(NativeNode node) {
        super(node);
        node.section("Circle");
        cache();
    }

    @Override
    String dumpComment() {
        return "Circular or spherical region";
    }

    /** Writes the Circle class's part of a description, as C's {@code Dump} (circle.c) does: it has no items. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
    }

    /** Completes a copy as C's {@code Copy} (circle.c) does: the kept values are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        centre = centre == null ? null : centre.clone();
        lb = lb == null ? null : lb.clone();
        ub = ub == null ? null : ub.clone();
    }

    /** Discards the kept values (C's {@code ResetCache}, circle.c), then what the Region keeps. */
    @Override
    void resetCache() {
        stale = true;
        super.resetCache();
    }

    /** Gives the Circle a new FrameSet (C's {@code SetRegFS}, circle.c), discarding the kept values. */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        resetCache();
    }

    /**
     * Works out the centre and radius in the base Frame, unless they are kept (C's {@code Cache},
     * circle.c): the radius is the distance from the centre to the point on the circumference.
     * The mesh bounds are made unbounded until the mesh is made.
     */
    private void cache() {
        if (!stale) {
            return;
        }
        Frame frm = baseFrame();
        int nc = frm.getNaxes();
        double[][] pts = getPoints();
        double[] c = new double[nc];
        double[] circum = new double[nc];
        for (int i = 0; i < nc; i++) {
            c[i] = pts[i][0];
            circum[i] = pts[i][1];
        }
        double r = frm.distance(c, circum);
        double[] l = new double[nc];
        double[] u = new double[nc];
        Arrays.fill(l, -Double.MAX_VALUE);
        Arrays.fill(u, Double.MAX_VALUE);
        radius = r;
        centre = c;
        lb = l;
        ub = u;
        stale = false;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Which base Frame points are in the Circle for the Negated and Closed values given (C's
     * {@code Transform}, circle.c): a point is in an un-negated Circle if its distance from the
     * centre is less than the radius, or equal to it if Closed; in a negated one if greater, or
     * equal if Closed. A point whose distance cannot be found is in neither.
     */
    private boolean[] inside(double[][] base, int npoint, boolean neg, boolean closed) {
        cache();
        Frame frm = baseFrame();
        int nc = base.length;
        double[] work = new double[nc];
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            for (int coord = 0; coord < nc; coord++) {
                work[coord] = base[coord][point];
            }
            double d = frm.distance(centre, work);
            boolean in;
            if (d != AST__BAD) {
                if (neg) {
                    in = closed ? d >= radius : d > radius;
                } else {
                    in = closed ? d <= radius : d < radius;
                }
            } else {
                in = false;
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
     * The bounding box in the base Frame (C's {@code RegBaseBox}, circle.c): in a plain Frame, the
     * centre plus and minus the radius on each axis; in any other, which may not be flat, the
     * bounds of the boundary mesh, made if the Region has not kept one.
     */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        cache();
        Frame frm = baseFrame();
        int nb = frm.getNaxes();
        if ("Frame".equals(frm.astClass())) {
            for (int i = 0; i < nb; i++) {
                lbnd[i] = centre[i] - radius;
                ubnd[i] = centre[i] + radius;
            }
        } else {
            if (keptBaseMesh() == null) {
                regBaseMesh();
            }
            for (int i = 0; i < nb; i++) {
                lbnd[i] = lb[i];
                ubnd[i] = ub[i];
            }
        }
    }

    /**
     * A mesh over the boundary in the base Frame, and the bounds of that mesh (C's {@code
     * RegBaseMesh}, circle.c): the two ends of a one-dimensional Circle; MeshSize points at equal
     * angles round a two-dimensional Circle, each the radius from the centre along a geodesic
     * ({@code astOffset2}), whose bounds are taken as axis distances from the centre so that a
     * Circle across RA 0 is bounded correctly; and for more dimensions, the mesh of the Box just
     * enclosing the sphere with each point moved along the geodesic from the centre to the
     * sphere. The bounds are then widened to take in any singularity inside the Circle ({@code
     * astNormBox}). As C does, only the mesh of more than two dimensions is kept.
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        Frame frm = baseFrame();
        int naxes = frm.getNaxes();
        int np = getMeshSize();
        cache();
        double[][] result;
        if (naxes == 1) {
            result = newPoints(1, 2);
            result[0][0] = centre[0] - radius;
            result[0][1] = centre[0] + radius;
            lb[0] = result[0][0];
            ub[0] = result[0][1];
        } else if (naxes == 2) {
            double delta = 2 * Math.PI / np;
            result = newPoints(2, np);
            double lbx = Double.MAX_VALUE;
            double ubx = -Double.MAX_VALUE;
            double lby = Double.MAX_VALUE;
            double uby = -Double.MAX_VALUE;
            double angle = 0.0;
            double[] p = new double[2];
            for (int i = 0; i < np; i++) {
                frm.offset2(centre, angle, radius, p);
                result[0][i] = p[0];
                result[1][i] = p[1];
                if (p[0] != AST__BAD && p[1] != AST__BAD) {
                    double dist = frm.axDistance(1, centre[0], p[0]);
                    if (dist < lbx) {
                        lbx = dist;
                    } else if (dist > ubx) {
                        ubx = dist;
                    }
                    dist = frm.axDistance(2, centre[1], p[1]);
                    if (dist < lby) {
                        lby = dist;
                    } else if (dist > uby) {
                        uby = dist;
                    }
                }
                angle += delta;
            }
            lb[0] = centre[0] + lbx;
            lb[1] = centre[1] + lby;
            ub[0] = centre[0] + ubx;
            ub[1] = centre[1] + uby;
        } else {
            double[] p1 = centre.clone();
            for (int j = 0; j < naxes; j++) {
                p1[j] += radius;
                double[] p2 = frm.offset(centre, p1, radius);
                p1[j] = centre[j];
                ub[j] = p2[j];
            }
            Box box = new Box(frm, 0, centre, ub, null);
            box.setMeshSizeValue(np);
            double[][] boxMesh = box.regBaseMesh();
            int nmesh = boxMesh[0].length;
            result = newPoints(naxes, nmesh);
            Arrays.fill(lb, Double.MAX_VALUE);
            Arrays.fill(ub, -Double.MAX_VALUE);
            for (int i = 0; i < nmesh; i++) {
                for (int j = 0; j < naxes; j++) {
                    p1[j] = boxMesh[j][i];
                }
                double[] p2 = frm.offset(centre, p1, radius);
                for (int j = 0; j < naxes; j++) {
                    result[j][i] = p2[j];
                    if (p2[j] != AST__BAD) {
                        if (p2[j] < lb[j]) {
                            lb[j] = p2[j];
                        } else if (p2[j] > ub[j]) {
                            ub[j] = p2[j];
                        }
                    }
                }
            }
            keepBaseMesh(result);
        }
        Circle reg = (Circle) copy();
        reg.setRegFS(frm);
        reg.setNegatedValue(false);
        frm.normBox(lb, ub, reg);
        return result;
    }

    // ------------------------------------------------------------------
    // Centre and boundary points
    // ------------------------------------------------------------------

    /**
     * The centre of the Circle, or a move of it (C's {@code RegCentre}, circle.c). A move keeps the
     * radius, placing the point on the circumference where the moved circle crosses the first
     * axis; a bad value in the new centre leaves that axis where it was. As C does, a move keeps
     * the radius and the boundary the Region has worked out.
     */
    @Override
    // The loops replacing bad centre values copy only some elements.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    double[] regCentre(double[] cen, double[][] ptr, int index, int ifrm) {
        FrameSet fs = getRegFS();
        int ncb = fs.getNin();
        int ncc = fs.getNout();
        cache();
        if (ptr == null && cen == null) {
            if (ifrm == FrameSet.AST__CURRENT) {
                return regTranPoint(centre, true);
            }
            return Arrays.copyOf(centre, ncb);
        }
        Frame frm = baseFrame();
        double[][] rptr = getPoints();
        if (ifrm == FrameSet.AST__CURRENT) {
            double[] bc;
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
            double[] circum = circumPoint(frm, ncb, bc, radius);
            for (int ic = 0; ic < ncb; ic++) {
                rptr[ic][0] = bc[ic];
                rptr[ic][1] = circum[ic];
                centre[ic] = bc[ic];
            }
        } else {
            for (int ic = 0; ic < ncb; ic++) {
                double axval = cen != null ? cen[ic] : ptr[ic][index];
                if (axval != AST__BAD) {
                    centre[ic] = axval;
                }
            }
            double[] circum = circumPoint(frm, ncb, centre, radius);
            for (int ic = 0; ic < ncb; ic++) {
                rptr[ic][0] = centre[ic];
                rptr[ic][1] = circum[ic];
            }
        }
        return null;
    }

    /**
     * Whether every point is on the boundary of the Circle (C's {@code RegPins}, circle.c): inside
     * a Circle larger than this one and outside one smaller, by half of a border width that is
     * half the sum of the diagonals of the bounding boxes of this Circle's uncertainty and the
     * points' uncertainty, each re-centred on the centre of this Circle. The smaller radius is not
     * kept from going below zero, as C does not: a Circle made with a negative radius has the
     * radius's magnitude.
     *
     * @throws AstException with status {@code AST__INTER} if the points or the uncertainty do not
     *     have the base Frame's number of axes
     */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
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
        double l1 = frm.distance(lbndTunc, ubndTunc);
        double l2;
        if (pointUnc != null) {
            pointUnc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
            double[] lbndUnc = new double[nc];
            double[] ubndUnc = new double[nc];
            pointUnc.regionBounds(lbndUnc, ubndUnc);
            l2 = frm.distance(lbndUnc, ubndUnc);
        } else {
            l2 = 0.0;
        }
        cache();
        double drad = 0.5 * (l1 + l2);
        Circle largeCircle = new Circle(frm, 1, centre, new double[] {radius + 0.5 * drad}, null);
        Circle smallCircle = new Circle(frm, 1, centre, new double[] {radius - 0.5 * drad}, null);
        smallCircle.negate();
        int np = pset[0].length;
        double[][] ps1 = largeCircle.transform(pset, np, true);
        double[][] ps2 = smallCircle.transform(ps1, np, true);
        return onBoundary(ps2, np, mask);
    }

    /**
     * Whether no value of the points is bad, and in {@code mask}, if given, whether each point has
     * no bad value (the tail of C's {@code RegPins} in circle.c and ellipse.c).
     */
    static boolean onBoundary(double[][] ps2, int np, boolean[] mask) {
        int nc = ps2.length;
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

    // ------------------------------------------------------------------
    // The best fitting Circle
    // ------------------------------------------------------------------

    /**
     * The best fitting Circle through a mesh of points (C's {@code astBestCircle}), centred on
     * {@code cen}: its radius is the root mean square distance of the mesh from the centre, taken
     * as the square root of the number of axes times the mean squared axis increment over the good
     * values. The Circle is in the Frame {@code unc} represents, with {@code unc} as its
     * uncertainty.
     *
     * @param mesh the points, {@code [ncoord][npoint]}, in the Frame {@code unc} represents
     * @param cen the centre
     * @param unc the uncertainty of each point of the mesh
     * @return the Circle, or null if the mesh has no good value
     */
    static Circle bestCircle(double[][] mesh, double[] cen, Region unc) {
        int nc = mesh.length;
        double s2r = 0.0;
        int n = 0;
        for (int ic = 0; ic < nc; ic++) {
            double[] p = mesh[ic];
            double p0 = cen[ic];
            for (double v : p) {
                if (v != AST__BAD) {
                    double d = v - p0;
                    s2r += d * d;
                    n++;
                }
            }
        }
        if (n > 0) {
            double rad = Math.sqrt(nc * s2r / n);
            return new Circle(unc, 1, cen, new double[] {rad}, unc);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * Simplifies the Circle (C's {@code Simplify}, circle.c): after the Region-generic
     * simplification, a Circle whose base and current Frames are not joined by a UnitMap is fitted
     * in the current Frame, about its centre transformed there, with a Circle ({@link
     * #bestCircle}) and, for two axes, failing that, an Ellipse ({@link Ellipse#bestEllipse}); the
     * first fit whose boundary holds every point of the Circle's boundary mesh in that Frame,
     * within the uncertainty, is the result. As in C, a fitted Circle is returned only when the
     * Region-generic simplification changed the Circle, since C does not count the fit itself as a
     * simplification; a fitted Ellipse always is. A new Region takes this Circle's Negated,
     * Closed, MeshSize, FillFactor and Adaptive values, and has no uncertainty if this one has
     * none set.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        Region nw = parentSimplify();
        boolean simpler = nw != this;
        Mapping map = nw.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        if (!isUnitMap(map)) {
            Region fit = fitted(nw);
            if (fit != null) {
                nw = fit;
                simpler = simpler || fit instanceof Ellipse;
            }
        }
        if (simpler) {
            nw.regOverlay(this, true);
            return nw;
        }
        return this;
    }

    /**
     * The Circle, or for two axes the Ellipse, that fits the boundary mesh of {@code nw} in its
     * current Frame about this Region's centre transformed into that Frame, as C's {@code
     * Simplify} tries them; null if the centre has a bad value or neither fits.
     */
    private Region fitted(Region nw) {
        double[][] mesh = nw.regMesh();
        Region unc = nw.getUncFrm(FrameSet.AST__CURRENT);
        double[][] ps2 = regTransform(null, true);
        int nc = ps2.length;
        double[] cen = new double[nc];
        boolean ok = true;
        for (int ic = 0; ic < nc; ic++) {
            cen[ic] = ps2[ic][0];
            if (cen[ic] == AST__BAD) {
                ok = false;
            }
        }
        Region newreg = ok ? bestCircle(mesh, cen, unc) : null;
        if (newreg != null && newreg.regPins(mesh, null, null)) {
            return newreg;
        } else if (ok && nc == 2) {
            newreg = Ellipse.bestEllipse(mesh, cen, unc);
            if (newreg != null && newreg.regPins(mesh, null, null)) {
                return newreg;
            }
        }
        return null;
    }
}
