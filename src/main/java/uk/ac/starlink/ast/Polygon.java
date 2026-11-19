package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * A Region of a polygonal area within a two-dimensional Frame, defined by its vertices. The
 * vertices are joined by geodesic curves in the Frame: straight lines in a plain Frame, great
 * circles in a SkyFrame, where neighbouring vertices are joined by the shorter path. The inside
 * of the Polygon is to the left of the boundary as the vertices are traversed in the order given
 * (on the sky, as seen from the inside of the celestial sphere); vertices given in the other
 * order make the Polygon the area outside them, the negation of the first.
 *
 * <p>The Polygon holds its vertices in the order that makes its un-negated form the bounded area
 * (on the sky, the smaller of the two areas the boundary divides it into), negating itself if it
 * reversed them, so the vertices {@link #getRegionPoints} gives may be in the other order.
 *
 * <p>Ported from C {@code polygon.c}, with Rust {@code regions/polygon.rs}. C's {@code astConvex},
 * {@code astOutline} and {@code astDownsize} are not part of jniast and are not ported.
 */
public class Polygon extends Region {

    /** The edges in the base Frame, edge {@code i} ending at vertex {@code i}, kept until the Polygon changes. */
    private Frame.LineDef[] edges;

    /** A point well inside the Polygon in the base Frame, kept with the edges (C's {@code in}). */
    private double[] in;

    /** Whether the vertices go anticlockwise round the inside, kept with the edges (C's {@code acw}). */
    private boolean acw = true;

    /** Whether the edges and the point inside must be worked out again (C's {@code stale}). */
    private boolean stale = true;

    /** The lower bounds of the base Frame bounding box, kept once found (C's {@code lbnd}), or null. */
    private double[] boxLbnd;

    /** The upper bounds of the base Frame bounding box, kept once found (C's {@code ubnd}). */
    private double[] boxUbnd;

    /** The SimpVertices attribute: 0, 1 or {@link #UNSET}. */
    private int simpVertices = UNSET;

    /**
     * Creates a Polygon (C's {@code astPolygon}).
     *
     * @param frame the two-dimensional Frame the Polygon is defined in; a copy is held. For a
     *     FrameSet or a Region, its current Frame
     * @param npnt the number of vertices; one or two are accepted
     * @param xcoords the value on the first axis of each vertex, in order. A value that is not
     *     finite is bad
     * @param ycoords the value on the second axis of each vertex
     * @param unc the uncertainty of positions on the boundary, such as a Box, Circle or Ellipse; a
     *     copy is held, centred on the first vertex as given. Null for the default, a Box one
     *     millionth of the size of the Polygon
     * @throws AstException with status {@code AST__BADIN} if {@code frame} does not have two axes
     *     or a vertex has a bad value, {@code AST__NPTIN} if {@code npnt} is less than one, {@code
     *     AST__DIMIN} if an array has fewer than {@code npnt} values, or the error of {@link
     *     #setUnc} for the uncertainty
     * @throws NullPointerException if {@code frame}, {@code xcoords} or {@code ycoords} is null
     */
    // C's astInitPolygon puts the vertices in order once the Region is made; ensureInside reads
    // only the Region's own FrameSet and points.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    public Polygon(Frame frame, int npnt, double[] xcoords, double[] ycoords, Region unc) {
        super(frame, vertices(frame, npnt, xcoords, ycoords), unc);
        ensureInside();
    }

    /**
     * The defining points of a Polygon, its vertices, checked as C's {@code astInitPolygon} checks
     * them: the Frame's number of axes, then the number of vertices (C's {@code astPointSet}), then
     * each first-axis value before any second-axis value.
     */
    private static double[][] vertices(Frame frame, int npnt, double[] xcoords, double[] ycoords) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(xcoords, "xcoords");
        Objects.requireNonNull(ycoords, "ycoords");
        int nin = frame.getNaxes();
        if (nin != 2) {
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astInitPolygon(Polygon): The supplied " + frame.astClass() + " has " + nin
                            + " axes - polygons must have exactly 2 axes.");
        }
        if (npnt < 1) {
            throw Ast.err(
                    AstException.AST__NPTIN,
                    "astInitPointSet(PointSet): Number of points (" + npnt + ") is not valid.");
        }
        checkLength("xcoords", xcoords, npnt);
        checkLength("ycoords", ycoords, npnt);
        double[][] pts = new double[2][npnt];
        double[][] given = {xcoords, ycoords};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < npnt; j++) {
                double v = given[i][j];
                if (isBad(v)) {
                    throw Ast.err(
                            AstException.AST__BADIN,
                            "astInitPolygon(Polygon): One or more bad axis values supplied for the vertex number "
                                    + (j + 1) + ".");
                }
                pts[i][j] = v;
            }
        }
        return pts;
    }

    /** An {@code AST__DIMIN} error if {@code values} has fewer than {@code n} elements. */
    private static void checkLength(String what, double[] values, int n) {
        if (values.length < n) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astPolygon(Polygon): The " + what + " array holds " + values.length
                            + " values.\nThis should not be less than " + n + ".");
        }
    }

    /**
     * Loads a Polygon from its description, as C's {@code astLoadPolygon} does: SimpVertices
     * ({@code SimpVT}) and the convention of the vertex order ({@code Order}). A description
     * without {@code Order}, written before AST took the inside to be to the left of the boundary
     * as seen from inside the celestial sphere, is negated. The vertices are then put in order.
     */
    // C's astLoadPolygon puts the vertices in order once the Region is read.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    Polygon(NativeNode node) {
        super(node);
        NativeNode part = node.section("Polygon");
        int sv = part.readInt("SimpVT", UNSET);
        if (sv != UNSET) {
            simpVertices = sv != 0 ? 1 : 0;
        }
        int order = part.readInt("Order", 0);
        if (order == 0) {
            negate();
        }
        ensureInside();
    }

    @Override
    String dumpComment() {
        return "Polygonal region";
    }

    /**
     * Writes the Polygon class's part of a description, as C's {@code Dump} (polygon.c) does:
     * SimpVertices if set, and the convention of the vertex order.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
        boolean set = testSimpVertices();
        node.writeInt("SimpVT", set, false, getSimpVertices() ? 1 : 0, "Simplify by transforming vertices?");
        node.writeInt("Order", true, false, 1, "Polygon uses STC vertex order convention");
    }

    /**
     * Completes a copy as C's {@code Copy} (polygon.c) does: nothing the Polygon or the Region has
     * worked out is kept.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        in = null;
        resetCache();
    }

    /**
     * Discards the edges, the point inside and the bounding box (C's {@code ResetCache},
     * polygon.c), then what the Region keeps.
     */
    @Override
    void resetCache() {
        stale = true;
        boxLbnd = null;
        boxUbnd = null;
        edges = null;
        super.resetCache();
    }

    /** Gives the Polygon a new FrameSet (C's {@code SetRegFS}, polygon.c), discarding what it keeps. */
    @Override
    void setRegFS(Frame frm) {
        resetCache();
        super.setRegFS(frm);
    }

    // ------------------------------------------------------------------
    // SimpVertices
    // ------------------------------------------------------------------

    /**
     * The SimpVertices attribute: whether simplifying the Polygon in a changed coordinate system
     * just transforms its vertices into it, rather than also checking that the new edges keep to
     * the old boundary within the uncertainty. The default is true.
     */
    boolean getSimpVertices() {
        return simpVertices == UNSET || simpVertices != 0;
    }

    /** Sets SimpVertices. */
    void setSimpVertices(boolean value) {
        simpVertices = value ? 1 : 0;
    }

    /** Whether SimpVertices is set. */
    boolean testSimpVertices() {
        return simpVertices != UNSET;
    }

    /** Clears SimpVertices. */
    void clearSimpVertices() {
        simpVertices = UNSET;
    }

    /** C's {@code GetAttrib} (polygon.c): SimpVertices, then the Region's attributes. */
    @Override
    String getAttrib(String attrib) {
        if ("simpvertices".equals(attrib)) {
            return getSimpVertices() ? "1" : "0";
        }
        return super.getAttrib(attrib);
    }

    /** C's {@code SetAttrib} (polygon.c): SimpVertices, then the Region's attributes. */
    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        if ("simpvertices".equals(attrib) && ival != null) {
            setSimpVertices(ival != 0);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    /** C's {@code TestAttrib} (polygon.c): SimpVertices, then the Region's attributes. */
    @Override
    boolean testAttrib(String attrib) {
        if ("simpvertices".equals(attrib)) {
            return testSimpVertices();
        }
        return super.testAttrib(attrib);
    }

    /** C's {@code ClearAttrib} (polygon.c): SimpVertices, then the Region's attributes. */
    @Override
    void clearAttrib(String attrib) {
        if ("simpvertices".equals(attrib)) {
            clearSimpVertices();
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // The edges and the point inside
    // ------------------------------------------------------------------

    /**
     * Works out the edges and a point well inside the Polygon in the base Frame, unless they are
     * kept (C's {@code Cache}, polygon.c). Edge {@code i} runs from vertex {@code i - 1} to vertex
     * {@code i}, edge 0 from the last vertex. The point inside is half way across the Polygon at
     * right angles to the edge across which it is widest ({@link #polywidth}). If no width is
     * found, the vertices go clockwise and the Polygon is the area outside them: the point is then
     * the far end of the last line drawn across, outside the polygonal hole.
     *
     * @throws AstException with status {@code AST__INTER} if a vertex is bad, which C's {@code
     *     astLineDef} cannot describe and C then reads through a null pointer
     */
    private void cache() {
        if (!stale) {
            return;
        }
        Frame frm = baseFrame();
        double[][] ptr = getPoints();
        int nv = ptr[0].length;
        Frame.LineDef[] e = new Frame.LineDef[nv];
        double[] start = {ptr[0][nv - 1], ptr[1][nv - 1]};
        for (int i = 0; i < nv; i++) {
            double[] end = {ptr[0][i], ptr[1][i]};
            e[i] = frm.lineDef(start, end);
            if (e[i] == null) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astPolygon(" + astClass() + "): Vertex " + (i + 1) + " or the one before it has a bad"
                                + " value, so the edge between them cannot be described (internal AST programming"
                                + " error).");
            }
            start = end;
        }
        double maxwid = -1.0;
        double[] inside = {AST__BAD, AST__BAD};
        double[] polcen = {AST__BAD, AST__BAD};
        for (int i = 0; i < nv; i++) {
            if (e[i].length > 0.0) {
                double polwid = polywidth(frm, e, i, nv, polcen);
                if (polwid > maxwid && polwid != AST__BAD) {
                    maxwid = polwid;
                    inside[0] = polcen[0];
                    inside[1] = polcen[1];
                }
            }
        }
        if (maxwid < 0.0) {
            inside[0] = polcen[0];
            inside[1] = polcen[1];
            acw = false;
        } else {
            acw = true;
        }
        edges = e;
        in = inside;
        stale = false;
    }

    /**
     * The width of the Polygon at right angles to edge {@code i}, or {@link #AST__BAD} if none is
     * found (C's {@code Polywidth}): the distance from the middle of the edge, going across it
     * towards the inside, to the nearest other edge. The point half way across is put in {@code
     * cen}, or, if no width is found, the far end of the line drawn across.
     *
     * <p>The line across starts ten times the half-length of the edge long, and is halved until
     * its end is that distance from its start, as a line that goes round a great circle and back
     * is not.
     *
     * @throws AstException with status {@code AST__INTER} if no line across the edge has its end
     *     at the distance asked for, however short, where C does not stop
     */
    private double polywidth(Frame frm, Frame.LineDef[] edges, int i, int nv, double[] cen) {
        double result = AST__BAD;
        double l1 = 0.5 * edges[i].length;
        double[] start = new double[2];
        frm.lineOffset(edges[i], l1, 0.0, start);
        double l2 = 10.0 * l1;
        double[] end = new double[2];
        while (true) {
            frm.lineOffset(edges[i], l1, l2, end);
            double d = frm.distance(start, end);
            if (d != AST__BAD && Math.abs(d - l2) < 1.0E-6 * l2) {
                break;
            }
            if (l2 == 0.0) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astPolygon(" + astClass() + "): No line across edge " + (i + 1)
                                + " reaches the distance asked for" + " (internal AST programming error).");
            }
            l2 *= 0.5;
        }
        Frame.LineDef line = frm.lineDef(start, end);
        if (line == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astPolygon(" + astClass() + "): The line across edge " + (i + 1) + " has a bad end (internal AST"
                            + " programming error).");
        }
        line.infinite = true;
        double[] cross = new double[5];
        double[] d = new double[1];
        for (int j = 0; j < nv; j++) {
            if (j != i
                    && frm.lineCrossing(line, edges[j], cross, d)
                    && d[0] != AST__BAD
                    && (d[0] < result || result == AST__BAD)) {
                result = d[0];
            }
        }
        if (result != AST__BAD) {
            double[] half = frm.offset(start, end, 0.5 * result);
            cen[0] = half[0];
            cen[1] = half[1];
        } else {
            cen[0] = end[0];
            cen[1] = end[1];
        }
        return result;
    }

    /** The point inside the Polygon that membership is tested from, in the base Frame (C's {@code in}). */
    double[] insidePoint() {
        cache();
        return in.clone();
    }

    /**
     * The area of a Polygon in a SkyFrame, in steradians (C's {@code SkyArea}): the sum of the
     * angles at the vertices, each taken from 0 to 2 pi, less {@code (n - 2) pi}. The angles are
     * measured by this Region as a Frame, in the Frame it represents.
     */
    private double skyArea() {
        double[][] ptr = getPoints();
        int nvert = ptr[0].length;
        double result = 0.0;
        for (int ivert = 0; ivert < nvert; ivert++) {
            double[] b = {ptr[0][ivert], ptr[1][ivert]};
            int ia = ivert < nvert - 1 ? ivert + 1 : 0;
            int ic = ivert > 0 ? ivert - 1 : nvert - 1;
            double[] a = {ptr[0][ia], ptr[1][ia]};
            double[] c = {ptr[0][ic], ptr[1][ic]};
            double angle = angle(a, b, c);
            if (angle < 0.0) {
                angle += 2 * Math.PI;
            }
            result += angle;
        }
        result -= (nvert - 2) * Math.PI;
        return result;
    }

    /**
     * Puts the vertices in the order that makes the un-negated Polygon the inside of the polygon
     * (C's {@code EnsureInside}): if the un-negated Polygon is not bounded, the vertices are
     * reversed and the Polygon negated, which leaves the area it covers as it was. On the sky,
     * where both areas are bounded, the inside is the area of less than 2 pi steradians.
     */
    private void ensureInside() {
        boolean issky = regFrame() instanceof SkyFrame;
        boolean bounded = issky ? skyArea() < 2 * Math.PI : getBounded();
        boolean negated = getNegated();
        if (bounded == negated) {
            double[][] ptr = getPoints();
            int np = ptr[0].length;
            int jmid = np / 2;
            for (int i = 0; i < 2; i++) {
                double[] p = ptr[i];
                for (int j = 0; j < jmid; j++) {
                    double tmp = p[j];
                    p[j] = p[np - 1 - j];
                    p[np - 1 - j] = tmp;
                }
            }
            negate();
            stale = true;
        }
    }

    /**
     * Whether the Polygon is bounded (C's {@code GetBounded}, polygon.c): always in a SkyFrame,
     * where a finite area has a finite negation; otherwise when its vertices go anticlockwise and
     * it is not negated, or go clockwise and it is negated.
     */
    @Override
    boolean isBoundedInner() {
        if (baseFrame() instanceof SkyFrame) {
            return true;
        }
        cache();
        boolean neg = getNegated();
        return acw ? !neg : neg;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /** Where a point lies with respect to the boundary. */
    private enum Pos {
        IN,
        OUT,
        ON
    }

    /**
     * Which base Frame points are in the Polygon for the Negated and Closed values given (C's
     * {@code Transform}, polygon.c). A point is inside the polygon if the line from the point
     * inside to it crosses the edges an even number of times, and on the boundary if an edge
     * contains it; it is in the un-negated Polygon if inside, and in the negated one if outside;
     * a point on the boundary is in either if Closed. A point with a bad value is in neither.
     *
     * @throws AstException with status {@code AST__BADIN} if the vertices are all the same point,
     *     so that no edge has any length and there is no point inside, where C reads an unset value
     */
    private boolean[] inside(double[][] base, int npoint, boolean neg, boolean closed) {
        Frame frm = baseFrame();
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            double px = base[0][point];
            double py = base[1][point];
            boolean inRegion;
            if (isBad(px) || isBad(py)) {
                inRegion = false;
            } else {
                cache();
                double[] p = {px, py};
                Frame.LineDef a = frm.lineDef(in, p);
                if (a == null) {
                    throw Ast.err(
                            AstException.AST__BADIN,
                            "astTransform(" + astClass() + "): The " + astClass() + "'s vertices are all the same"
                                    + " point, so it has no inside.");
                }
                int ncross = 0;
                Pos pos = null;
                for (Frame.LineDef b : edges) {
                    if (frm.lineContains(b, 0, p)) {
                        pos = Pos.ON;
                        break;
                    } else if (frm.lineCrossing(b, a, null)) {
                        ncross++;
                    }
                }
                if (pos == null) {
                    pos = ncross % 2 == 0 ? Pos.IN : Pos.OUT;
                }
                switch (pos) {
                    case IN:
                        inRegion = !neg;
                        break;
                    case OUT:
                        inRegion = neg;
                        break;
                    case ON:
                        inRegion = closed;
                        break;
                    default:
                        throw Ast.err(
                                AstException.AST__INTER, "astTransform(Polygon): Unexpected position " + pos + ".");
                }
            }
            mask[point] = inRegion;
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
     * The bounding box in the base Frame, kept once found (C's {@code RegBaseBox}, polygon.c): the
     * least and greatest axis distances of the vertices from the first, added to the first, so
     * that a box across RA 0 is bounded correctly, then widened to take in any singularity inside
     * the un-negated Polygon ({@code astNormBox}). As in C, a vertex that would widen both ends of
     * an axis widens only the lower.
     */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        if (boxLbnd != null) {
            lbnd[0] = boxLbnd[0];
            lbnd[1] = boxLbnd[1];
            ubnd[0] = boxUbnd[0];
            ubnd[1] = boxUbnd[1];
            return;
        }
        double[][] ptr = getPoints();
        int np = ptr[0].length;
        Frame frm = baseFrame();
        lbnd[0] = 0.0;
        lbnd[1] = 0.0;
        ubnd[0] = 0.0;
        ubnd[1] = 0.0;
        double x0 = ptr[0][0];
        double y0 = ptr[1][0];
        for (int ip = 0; ip < np; ip++) {
            double dist = frm.axDistance(1, x0, ptr[0][ip]);
            if (dist < lbnd[0]) {
                lbnd[0] = dist;
            } else if (dist > ubnd[0]) {
                ubnd[0] = dist;
            }
            dist = frm.axDistance(2, y0, ptr[1][ip]);
            if (dist < lbnd[1]) {
                lbnd[1] = dist;
            } else if (dist > ubnd[1]) {
                ubnd[1] = dist;
            }
        }
        lbnd[0] += x0;
        lbnd[1] += y0;
        ubnd[0] += x0;
        ubnd[1] += y0;
        Polygon reg = (Polygon) copy();
        reg.setRegFS(frm);
        reg.setNegatedValue(false);
        frm.normBox(lbnd, ubnd, reg);
        boxLbnd = new double[] {lbnd[0], lbnd[1]};
        boxUbnd = new double[] {ubnd[0], ubnd[1]};
    }

    /**
     * A mesh over the boundary in the base Frame (C's {@code RegBaseMesh}, polygon.c): MeshSize
     * points shared between the edges by their lengths as the base Frame measures distance, {@code
     * 1 + (int)(length * MeshSize / perimeter)} to an edge; each edge starts at its first vertex,
     * normalised, and its other points are placed along it with the Frame's {@code offset}, so on
     * the sky they follow the great circle. The edges ending at vertices 1 onwards come first, then
     * the edge ending at vertex 0. An edge of undefined length is left out.
     *
     * @throws AstException with status {@code AST__BADIN} if the boundary has no length
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        Frame frm = baseFrame();
        double[][] vptr = getPoints();
        int nv = vptr[0].length;
        double[] lens = new double[nv];
        double total = 0.0;
        double[] start = {vptr[0][0], vptr[1][0]};
        for (int iv = 1; iv < nv; iv++) {
            double[] end = {vptr[0][iv], vptr[1][iv]};
            double d = frm.distance(start, end);
            if (d != AST__BAD) {
                total += Math.abs(d);
            }
            lens[iv] = d;
            start = end;
        }
        double d = frm.distance(start, new double[] {vptr[0][0], vptr[1][0]});
        if (d != AST__BAD) {
            total += Math.abs(d);
        }
        lens[0] = d;
        // As C tests it, so that a NaN length is undefined too.
        boolean defined = total > 0.0;
        if (!defined) {
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astRegBaseMesh(" + astClass() + "): The boundary of the supplied " + astClass()
                            + " has an undefined length.");
        }
        double mp = getMeshSize() / total;
        int np = 0;
        for (int iv = 0; iv < nv; iv++) {
            if (lens[iv] != AST__BAD) {
                np += 1 + (int) (lens[iv] * mp);
            }
        }
        double[][] result = newPoints(2, np);
        int next = 0;
        start = new double[] {vptr[0][0], vptr[1][0]};
        for (int iv = 1; iv < nv; iv++) {
            double[] end = {vptr[0][iv], vptr[1][iv]};
            if (lens[iv] != AST__BAD) {
                next = meshEdge(frm, start, end, lens[iv], mp, result, next);
            }
            start = end;
        }
        if (lens[0] != AST__BAD) {
            meshEdge(frm, start, new double[] {vptr[0][0], vptr[1][0]}, lens[0], mp, result, next);
        }
        keepBaseMesh(result);
        return result;
    }

    /**
     * Adds the mesh points of the edge from {@code start} to {@code end}, of length {@code len},
     * to {@code result} from index {@code next}: {@code start}, normalised in place, then the
     * points spaced evenly along the edge before {@code end}.
     *
     * @return the index of the next point to add
     */
    private static int meshEdge(
            Frame frm, double[] start, double[] end, double len, double mp, double[][] result, int next) {
        int k = next;
        frm.norm(start);
        result[0][k] = start[0];
        result[1][k] = start[1];
        k++;
        int n = 1 + (int) (len * mp);
        if (n > 1) {
            double delta = len / n;
            for (int ip = 1; ip < n; ip++) {
                double[] p = frm.offset(start, end, delta * ip);
                result[0][k] = p[0];
                result[1][k] = p[1];
                k++;
            }
        }
        return k;
    }

    /**
     * Whether every point is on the boundary of the Polygon (C's {@code RegPins}, polygon.c):
     * within a margin of some edge, the margin being half the sum of the diagonals of the bounding
     * boxes of this Polygon's uncertainty and the points' uncertainty. A point is within the
     * margin of an edge if, resolved into parts along the edge and across it, the part along is
     * more than minus the margin and less than the edge's length plus the margin, and the part
     * across is within the margin either side. As in C, a point with a bad value counts as on the
     * boundary.
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
        double[][] vptr = getPoints();
        int nv = vptr[0].length;
        double[][] ptr1 = copyPoints(pset);
        int np = ptr1[0].length;
        double[] safe = regCentre(null, null, 0, FrameSet.AST__BASE);
        Region tunc = getUncFrm(FrameSet.AST__BASE);
        if (safe != null) {
            tunc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
        }
        double[] lbndTunc = new double[2];
        double[] ubndTunc = new double[2];
        tunc.regionBounds(lbndTunc, ubndTunc);
        Frame frm = baseFrame();
        double l1 = frm.distance(lbndTunc, ubndTunc);
        double l2;
        if (pointUnc != null) {
            if (safe != null) {
                pointUnc.regCentre(safe, null, 0, FrameSet.AST__CURRENT);
            }
            double[] lbndUnc = new double[2];
            double[] ubndUnc = new double[2];
            pointUnc.regionBounds(lbndUnc, ubndUnc);
            l2 = frm.distance(lbndUnc, ubndUnc);
        } else {
            l2 = 0.0;
        }
        double wid = 0.5 * (l1 + l2);
        double[] start = {vptr[0][nv - 1], vptr[1][nv - 1]};
        for (int i = 0; i < nv; i++) {
            double[] end = {vptr[0][i], vptr[1][i]};
            double edgeLen = frm.distance(start, end);
            double[][] ptr2 = frm.resolvePoints(start, end, ptr1, np);
            double parmax = edgeLen + wid;
            for (int ip = 0; ip < np; ip++) {
                double par = ptr2[0][ip];
                double prp = ptr2[1][ip];
                if (par != AST__BAD && prp != AST__BAD && par > -wid && par < parmax && prp > -wid && prp < wid) {
                    ptr1[0][ip] = AST__BAD;
                    ptr1[1][ip] = AST__BAD;
                }
            }
            start = end;
        }
        boolean result = true;
        if (mask != null) {
            for (int ip = 0; ip < np; ip++) {
                if (ptr1[0][ip] != AST__BAD && ptr1[1][ip] != AST__BAD) {
                    mask[ip] = false;
                    result = false;
                } else {
                    mask[ip] = true;
                }
            }
        } else {
            for (int ip = 0; ip < np; ip++) {
                if (ptr1[0][ip] != AST__BAD && ptr1[1][ip] != AST__BAD) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * C's {@code Simplify} (polygon.c): region.c's simplification, then, if the Mapping from the
     * base to the current Frame is not a UnitMap and has two outputs, a new Polygon made from the
     * vertices transformed into the current Frame, with the uncertainty in that Frame, unless a
     * vertex becomes bad. With SimpVertices false the new Polygon is kept only if every point of
     * the boundary mesh of the Region, in the current Frame, is on its boundary ({@link #regPins}).
     * The result takes this Polygon's Negated, Closed, MeshSize, FillFactor and Adaptive values,
     * and has no uncertainty if this one has none set.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        Region nw = parentSimplify();
        boolean simpler = nw != this;
        Mapping map = nw.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        if (!isUnitMap(map) && map.getNout() == 2) {
            Frame frm = nw.regFrame();
            Region unc = nw.getUncFrm(FrameSet.AST__CURRENT);
            double[][] ps2 = regTransform(null, true);
            int nv = ps2[0].length;
            boolean ok = true;
            for (int iv = 0; iv < nv; iv++) {
                if (ps2[0][iv] == AST__BAD || ps2[1][iv] == AST__BAD) {
                    ok = false;
                }
            }
            if (ok) {
                Polygon newpol = new Polygon(frm, nv, ps2[0], ps2[1], unc);
                if (!getSimpVertices()) {
                    double[][] mesh = nw.regMesh();
                    if (!newpol.regPins(mesh, null, null)) {
                        newpol = null;
                    }
                }
                if (newpol != null) {
                    nw = newpol;
                    simpler = true;
                }
            }
        }
        if (simpler) {
            nw.regOverlay(this, true);
            return nw;
        }
        return this;
    }
}
