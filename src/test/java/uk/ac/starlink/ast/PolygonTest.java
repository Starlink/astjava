package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.RegionProbeText.bounded;
import static uk.ac.starlink.ast.RegionProbeText.bounds;
import static uk.ac.starlink.ast.RegionProbeText.check;
import static uk.ac.starlink.ast.RegionProbeText.error;
import static uk.ac.starlink.ast.RegionProbeText.overlap;
import static uk.ac.starlink.ast.RegionProbeText.points;
import static uk.ac.starlink.ast.RegionProbeText.unc;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_polygon.rs}, and the cases of {@code
 * probes/region_polygon_probe.c}: the constructor and its errors, membership (closed, open, negated,
 * and with the vertices given clockwise), Bounded, bounds, the defining points, the uncertainty, the
 * boundary mesh, the boundary test ({@code astRegPins}), masks, overlaps, SimpVertices, the dump,
 * and simplification after a mapping, against C.
 *
 * <p>Every value here comes from arithmetic and square roots on the vertices of Polygons in a plain
 * Frame, which give the same bits on every platform, so all are compared exactly.
 *
 * <p>Rust's {@code construct_rejects_wrong_vertex_length} gives a vertex with too few values; with
 * jniast's arrays of x and y values it is a y array shorter than the number of vertices, which is
 * {@code AST__DIMIN}. Listed in {@code deferred/rust-tests.txt}: {@code centroid_of_square}
 * ({@code astGetRegionDisc}, not in jniast).
 */
class PolygonTest {

    private static final double BAD = AstObject.AST__BAD;

    /** The cases of {@code probes/region_polygon_probe.c}. */
    private static final Map<String, String> PROBE = ProbeOutput.load("region_polygon_probe");

    private static void checkCase(String name, String actual) {
        check(PROBE, name, actual);
    }

    /** A Polygon of the vertices given as x, y pairs. */
    static Polygon poly(Frame f, Region unc, double... xy) {
        int n = xy.length / 2;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = xy[2 * i];
            y[i] = xy[2 * i + 1];
        }
        return new Polygon(f, n, x, y, unc);
    }

    private static Polygon poly(double... xy) {
        return poly(new Frame(2), null, xy);
    }

    private static Frame frame(int naxes, String domain) {
        Frame f = new Frame(naxes);
        if (domain != null) {
            f.setDomain(domain);
        }
        return f;
    }

    private static Box box2(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static Circle circle2(double cx, double cy, double r) {
        return new Circle(new Frame(2), new double[] {cx, cy}, r, null);
    }

    private static double[][] rows(double... xy) {
        int n = xy.length / 2;
        double[][] out = new double[2][n];
        for (int i = 0; i < n; i++) {
            out[0][i] = xy[2 * i];
            out[1][i] = xy[2 * i + 1];
        }
        return out;
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    /** The probe's {@code tran} line for 2-D points given as x, y pairs. */
    private static String tran(Region r, double... pts) {
        return RegionProbeText.tran(r, 2, pts);
    }

    /** The probe's {@code negated} line. */
    private static String negated(Region r) {
        return "negated " + (r.getNegated() ? 1 : 0) + "\n";
    }

    /** The probe's {@code mesh} line: the boundary mesh in the current Frame. */
    private static String mesh(Region r) {
        double[][] m = r.regMesh();
        int n = m[0].length;
        StringBuilder sb = new StringBuilder("mesh ").append(n);
        for (double[] axis : m) {
            for (int i = 0; i < n; i++) {
                sb.append(' ').append(g(axis[i]));
            }
        }
        return sb.append('\n').toString();
    }

    /** The probe's {@code pins} line: the result of regPins, then its mask. */
    private static String pins(Region r, double[][] pts, Region pointUnc) {
        return CircleTest.pins(r, pts, pointUnc);
    }

    /** The probe's {@code mask} line, over pixels 0 to 5 by 0 to 4 with no Mapping. */
    private static String mask(Region r, boolean inside) {
        return RegionProbeText.mask(r, null, inside, new int[] {0, 0}, new int[] {5, 4});
    }

    private static String simpVertices(Region r) {
        return "simpvertices " + r.getI("SimpVertices") + " " + (r.test("SimpVertices") ? 1 : 0) + "\n";
    }

    // ---- Translation of test_polygon.rs ----

    private static Polygon triangle() {
        return poly(0.0, 0.0, 4.0, 0.0, 0.0, 3.0);
    }

    private static Polygon square() {
        return poly(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0);
    }

    private static Polygon lShape() {
        return poly(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0);
    }

    private static Polygon arrow() {
        return poly(0.0, 0.0, 4.0, 0.0, 4.0, 3.0, 2.0, 1.0, 0.0, 3.0);
    }

    @Test
    void constructTriangle() {
        assertEquals(2, triangle().getNaxes());
    }

    @Test
    void constructAcceptsFewerThan3Vertices() {
        poly(0.0, 0.0, 1.0, 1.0);
        AstException e = assertThrows(
                AstException.class, () -> new Polygon(new Frame(2), 0, new double[0], new double[0], null));
        assertEquals(AstException.AST__NPTIN, e.getStatus());
    }

    @Test
    void constructRejectsNon2dFrame() {
        AstException e = assertThrows(
                AstException.class, () -> new Polygon(new Frame(3), 3, new double[3], new double[3], null));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    @Test
    void constructRejects1dFrame() {
        AstException e = assertThrows(
                AstException.class, () -> new Polygon(new Frame(1), 3, new double[3], new double[3], null));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    @Test
    void constructRejectsWrongVertexLength() {
        AstException e = assertThrows(
                AstException.class,
                () -> new Polygon(new Frame(2), 3, new double[] {0.0, 1.0, 0.5}, new double[] {0.0, 0.0}, null));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
    }

    @Test
    void trianglePointInside() {
        Polygon p = triangle();
        assertTrue(p.pointInRegion(new double[] {1.0, 0.5}));
        assertTrue(p.pointInRegion(new double[] {0.5, 0.5}));
    }

    @Test
    void trianglePointOutside() {
        Polygon p = triangle();
        assertFalse(p.pointInRegion(new double[] {5.0, 5.0}));
        assertFalse(p.pointInRegion(new double[] {-1.0, 0.0}));
        assertFalse(p.pointInRegion(new double[] {3.0, 3.0}));
    }

    @Test
    void trianglePointOnVertex() {
        Polygon p = triangle();
        assertTrue(p.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(p.pointInRegion(new double[] {4.0, 0.0}));
        assertTrue(p.pointInRegion(new double[] {0.0, 3.0}));
    }

    @Test
    void trianglePointOnEdge() {
        Polygon p = triangle();
        assertTrue(p.pointInRegion(new double[] {2.0, 0.0}));
        assertTrue(p.pointInRegion(new double[] {0.0, 1.5}));
    }

    @Test
    void squareInterior() {
        Polygon p = square();
        assertTrue(p.pointInRegion(new double[] {0.5, 0.5}));
        assertTrue(p.pointInRegion(new double[] {0.1, 0.9}));
        assertTrue(p.pointInRegion(new double[] {0.99, 0.01}));
    }

    @Test
    void squareCorners() {
        Polygon p = square();
        assertTrue(p.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(p.pointInRegion(new double[] {1.0, 0.0}));
        assertTrue(p.pointInRegion(new double[] {1.0, 1.0}));
        assertTrue(p.pointInRegion(new double[] {0.0, 1.0}));
    }

    @Test
    void squareOutside() {
        Polygon p = square();
        assertFalse(p.pointInRegion(new double[] {1.5, 0.5}));
        assertFalse(p.pointInRegion(new double[] {-0.1, 0.5}));
        assertFalse(p.pointInRegion(new double[] {0.5, -0.1}));
        assertFalse(p.pointInRegion(new double[] {0.5, 1.1}));
    }

    @Test
    void concaveInsideBody() {
        assertTrue(arrow().pointInRegion(new double[] {2.0, 0.5}));
    }

    @Test
    void concaveInsideCorner() {
        assertTrue(arrow().pointInRegion(new double[] {0.5, 0.5}));
    }

    @Test
    void concaveInNotch() {
        assertFalse(arrow().pointInRegion(new double[] {2.0, 2.5}));
    }

    @Test
    void concaveOutside() {
        Polygon p = arrow();
        assertFalse(p.pointInRegion(new double[] {5.0, 0.5}));
        assertFalse(p.pointInRegion(new double[] {-0.5, 1.0}));
    }

    @Test
    void boundingBoxTriangle() {
        double[][] b = triangle().getRegionBounds();
        assertArrayEquals(new double[] {0.0, 0.0}, b[0]);
        assertArrayEquals(new double[] {4.0, 3.0}, b[1]);
    }

    @Test
    void boundingBoxLShape() {
        double[][] b = lShape().getRegionBounds();
        assertArrayEquals(new double[] {0.0, 0.0}, b[0]);
        assertArrayEquals(new double[] {2.0, 2.0}, b[1]);
    }

    @Test
    void negationInvertsMembership() {
        Polygon p = triangle();
        assertTrue(p.pointInRegion(new double[] {1.0, 0.5}));
        assertFalse(p.pointInRegion(new double[] {5.0, 5.0}));
        p.negate();
        assertTrue(p.getNegated());
        assertFalse(p.pointInRegion(new double[] {1.0, 0.5}));
        assertTrue(p.pointInRegion(new double[] {5.0, 5.0}));
    }

    @Test
    void negatedCopyPreservesOriginal() {
        Polygon p = triangle();
        Region neg = (Region) p.copy();
        neg.negate();
        assertFalse(p.getNegated());
        assertTrue(neg.getNegated());
        assertTrue(p.pointInRegion(new double[] {1.0, 0.5}));
        assertFalse(neg.pointInRegion(new double[] {1.0, 0.5}));
    }

    @Test
    void batchTransformInsideOutside() {
        double[][] out = square().transform(rows(0.5, 0.5, 2.0, 2.0, 0.1, 0.1, 5.0, 5.0), 4, true);
        assertArrayEquals(new double[] {0.5, BAD, 0.1, BAD}, out[0]);
        assertArrayEquals(new double[] {0.5, BAD, 0.1, BAD}, out[1]);
    }

    @Test
    void batchTransformBadInput() {
        double[][] out = square().transform(rows(BAD, 0.5, 0.5, BAD), 2, true);
        assertEquals(BAD, out[0][0]);
        assertEquals(BAD, out[0][1]);
    }

    @Test
    void inverseTransformMasksAsForward() {
        Polygon p = square();
        double[][] in = rows(5.0, 5.0, 0.5, 0.5);
        double[][] inverse = p.transform(in, 2, false);
        double[][] forward = p.transform(in, 2, true);
        assertArrayEquals(forward[0], inverse[0]);
        assertArrayEquals(forward[1], inverse[1]);
        assertEquals(BAD, inverse[0][0]);
        assertEquals(BAD, inverse[1][0]);
    }

    @Test
    void meshHasPointsOnBoundary() {
        double[][] mesh = square().baseMesh();
        int n = mesh[0].length;
        assertTrue(n > 0);
        for (int i = 0; i < n; i++) {
            double x = mesh[0][i];
            double y = mesh[1][i];
            boolean onBoundary = x == 0.0 || x == 1.0 || y == 0.0 || y == 1.0;
            assertTrue(onBoundary, "mesh point (" + x + ", " + y + ") is not on the boundary");
        }
    }

    @Test
    void openPolygonExcludesBoundary() {
        Polygon p = square();
        p.setClosed(false);
        assertTrue(p.pointInRegion(new double[] {0.5, 0.5}));
        assertFalse(p.pointInRegion(new double[] {0.0, 0.5}));
        assertFalse(p.pointInRegion(new double[] {1.0, 0.5}));
        assertFalse(p.pointInRegion(new double[] {0.5, 0.0}));
        assertFalse(p.pointInRegion(new double[] {0.5, 1.0}));
        assertFalse(p.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void isBounded() {
        assertTrue(triangle().getBounded());
    }

    @Test
    void regionPointsReturnsVertices() {
        double[][] pts = triangle().getRegionPoints();
        assertArrayEquals(new double[] {0.0, 4.0, 0.0}, pts[0]);
        assertArrayEquals(new double[] {0.0, 0.0, 3.0}, pts[1]);
    }

    private static Polygon big() {
        return poly(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0);
    }

    @Test
    void regPinsPolygonEdgeAndVertexOnBoundary() {
        Polygon p = big();
        assertTrue(p.regPins(rows(2.0, 0.0), null, null));
        assertTrue(p.regPins(rows(4.0, 4.0), null, null));
    }

    @Test
    void regPinsPolygonInteriorAndExteriorOffBoundary() {
        Polygon p = big();
        assertFalse(p.regPins(rows(2.0, 2.0), null, null));
        assertFalse(p.regPins(rows(10.0, 10.0), null, null));
    }

    @Test
    void regPinsPolygonMixedSetReportsFalse() {
        assertFalse(big().regPins(rows(2.0, 0.0, 2.0, 2.0), null, null));
    }

    // ---- The cases of region_polygon_probe.c ----

    private static final double[] TRI = {0.0, 0.0, 4.0, 0.0, 0.0, 3.0};

    private static final double[] TRI_CW = {0.0, 0.0, 0.0, 3.0, 4.0, 0.0};

    private static final double[] TRI_TEST = {
        1.0, 0.5, 0.5, 0.5, 5.0, 5.0, -1.0, 0.0, 3.0, 3.0, 0.0, 0.0, 4.0, 0.0, 0.0, 3.0, 2.0, 0.0, 0.0, 1.5
    };

    private static final double[] SQ_TEST = {
        0.5, 0.5, 0.1, 0.9, 0.99, 0.01, 1.5, 0.5, -0.1, 0.5, 0.5, -0.1, 0.5, 1.1, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0,
        1.0, 0.0, 0.5, 1.0, 0.5, 0.5, 0.0, 0.5, 1.0, 2.0, 2.0, BAD, 0.5, 0.5, BAD
    };

    private static final double[] ELL_TEST = {0.5, 0.5, 1.5, 0.5, 0.5, 1.5, 1.5, 1.5, 2.5, 0.5, 1.0, 1.5, 1.5, 1.0};

    private static final double[] ARROW_TEST = {2.0, 0.5, 0.5, 0.5, 2.0, 2.5, 5.0, 0.5, -0.5, 1.0, 3.5, 2.5, 2.0, 1.0};

    private static final double[] TWO_TEST = {0.5, 0.0, 0.5, 0.5, 5.0, 5.0, 0.5, -0.5, 1.0, 0.0};

    @Test
    void membershipBoundsAndPointsMatchC() {
        Polygon t = poly(TRI);
        checkCase(
                "tri",
                tran(t, TRI_TEST)
                        + bounded(t)
                        + negated(t)
                        + bounds(t)
                        + points(t)
                        + unc(t, true)
                        + unc(t, false)
                        + mesh(t));
        Region u = (Region) t.copy();
        u.negate();
        checkCase("tri_negated", tran(u, TRI_TEST) + bounded(u) + bounds(u));
        u = (Region) t.copy();
        u.setClosed(false);
        checkCase("tri_open", tran(u, TRI_TEST));
        u.negate();
        checkCase("tri_open_negated", tran(u, TRI_TEST));
        u = poly(TRI_CW);
        checkCase("tri_clockwise", tran(u, TRI_TEST) + bounded(u) + negated(u) + bounds(u) + points(u));

        Polygon sq = square();
        checkCase("square", tran(sq, SQ_TEST) + bounds(sq));
        u = (Region) sq.copy();
        u.setClosed(false);
        checkCase("square_open", tran(u, SQ_TEST));
        u = lShape();
        checkCase("ell", tran(u, ELL_TEST) + bounds(u) + mesh(u));
        u = arrow();
        checkCase("arrow", tran(u, ARROW_TEST) + bounds(u));
    }

    @Test
    void fewerThanThreeVerticesMatchC() {
        Polygon u = poly(0.0, 0.0, 1.0, 0.0);
        checkCase("two", bounded(u) + negated(u) + points(u) + tran(u, TWO_TEST) + bounds(u) + mesh(u));
        Polygon one = poly(1.0, 2.0);
        checkCase("one", bounded(one) + negated(one) + points(one) + bounds(one) + error(() -> mesh(one)));
        u = poly(0.0, 0.0, 1.0, 0.0, 2.0, 0.0);
        checkCase("line3", bounded(u) + negated(u) + points(u) + tran(u, TWO_TEST) + bounds(u));
    }

    @Test
    void constructorErrorsMatchC() {
        double[] tri3x = {0.0, 4.0, 0.0};
        double[] tri3y = {0.0, 0.0, 3.0};
        checkCase("ctor_naxes3", error(() -> new Polygon(new Frame(3), 3, tri3x, tri3y, null)));
        checkCase("ctor_naxes1", error(() -> new Polygon(new Frame(1), 3, tri3x, tri3y, null)));
        checkCase("ctor_empty", error(() -> new Polygon(new Frame(2), 0, tri3x, tri3y, null)));
        checkCase("ctor_bad_x", error(() -> poly(0.0, 0.0, BAD, 0.0, 0.0, 3.0)));
        checkCase("ctor_bad_xy", error(() -> poly(0.0, 0.0, 4.0, BAD, BAD, 3.0)));
        Box box3 = new Box(new Frame(3), 1, new double[] {-0.1, -0.1, -0.1}, new double[] {0.1, 0.1, 0.1}, null);
        checkCase("ctor_unc_dims", error(() -> poly(new Frame(2), box3, TRI)));
        Polygon t = poly(TRI);
        checkCase("ctor_unc_shape", error(() -> poly(new Frame(2), t, TRI)));
        Box nb = box2(-0.1, -0.1, 0.1, 0.2);
        nb.negate();
        checkCase("ctor_unc_negated", unc(poly(new Frame(2), nb, TRI), false));
        checkCase("ctor_unc_circle", unc(poly(new Frame(2), circle2(-5.0, 7.0, 0.01), TRI), false));
        Polygon cw = poly(new Frame(2), circle2(-5.0, 7.0, 0.01), 4.0, 0.0, 0.0, 0.0, 0.0, 3.0);
        checkCase("ctor_unc_circle_clockwise", unc(cw, false) + points(cw));
    }

    @Test
    void nonFiniteVertexIsBad() {
        AstException e = assertThrows(AstException.class, () -> poly(0.0, 0.0, Double.NaN, 0.0, 0.0, 3.0));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertEquals(
                "astInitPolygon(Polygon): One or more bad axis values supplied for the vertex number 2.",
                e.getMsgText());
    }

    @Test
    void nullArgumentsAreNamed() {
        double[] x = {0.0, 4.0, 0.0};
        double[] y = {0.0, 0.0, 3.0};
        NullPointerException e = assertThrows(NullPointerException.class, () -> new Polygon(null, 3, x, y, null));
        assertEquals("frame", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> new Polygon(new Frame(2), 3, null, y, null));
        assertEquals("xcoords", e.getMessage());
        e = assertThrows(NullPointerException.class, () -> new Polygon(new Frame(2), 3, x, null, null));
        assertEquals("ycoords", e.getMessage());
    }

    @Test
    void longerArraysAreAccepted() {
        Polygon p = new Polygon(
                new Frame(2), 3, new double[] {0.0, 4.0, 0.0, 9.0}, new double[] {0.0, 0.0, 3.0, 9.0}, null);
        checkCase(
                "tri",
                tran(p, TRI_TEST)
                        + bounded(p)
                        + negated(p)
                        + bounds(p)
                        + points(p)
                        + unc(p, true)
                        + unc(p, false)
                        + mesh(p));
    }

    @Test
    void frameSetAndRegionAsTheFrameMatchC() {
        FrameSet fs = new FrameSet(frame(2, "X"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), frame(2, "Y"));
        Polygon u = poly(fs, null, TRI);
        checkCase("frameset", "domain " + u.getDomain() + "\n" + points(u));
        Polygon v = poly(u, null, TRI);
        checkCase("region_frame", "domain " + v.getDomain() + "\n" + points(v));
    }

    @Test
    void boundaryTestsMatchC() {
        Polygon u = big();
        double[] pinpts = {
            2.0, 0.0, 4.0, 4.0, 2.0, 2.0, 10.0, 10.0, 2.0, 1.0e-7, 2.0, 1.0e-5, -1.0e-7, 2.0, 4.0, 4.0 + 1.0e-5
        };
        double[][] unc = rows(2.0, 0.04, 2.0, 0.06);
        checkCase(
                "pins",
                pins(u, rows(2.0, 0.0), null)
                        + pins(u, rows(4.0, 4.0), null)
                        + pins(u, rows(2.0, 2.0), null)
                        + pins(u, rows(10.0, 10.0), null)
                        + pins(u, rows(4.0, 4.0, 2.0, 2.0), null)
                        + pins(u, rows(pinpts), null)
                        + pins(u, rows(BAD, BAD, 2.0, 0.0, BAD, 1.0), null)
                        + pins(u, unc, null)
                        + pins(u, unc, box2(-0.05, -0.05, 0.05, 0.05))
                        + pins(u, u.regMesh(), null));
    }

    @Test
    void masksMatchC() {
        Polygon t = poly(TRI);
        checkCase("mask_tri", mask(t, true) + mask(t, false));
        Region u = (Region) t.copy();
        u.negate();
        checkCase("mask_tri_negated", mask(u, true));
    }

    @Test
    void overlapsMatchC() {
        Polygon t = poly(TRI);
        checkCase("overlap_tri_box_inside", overlap(t, box2(0.5, 0.5, 1.0, 1.0)));
        checkCase("overlap_tri_box_partial", overlap(t, box2(1.0, 1.0, 5.0, 5.0)));
        checkCase("overlap_tri_box_far", overlap(t, box2(10.0, 10.0, 11.0, 11.0)));
        checkCase("overlap_tri_box_around", overlap(t, box2(-1.0, -1.0, 5.0, 4.0)));
        checkCase("overlap_tri_self", overlap(t, poly(TRI)));
        checkCase("overlap_tri_clockwise", overlap(t, poly(TRI_CW)));
        Region u = (Region) t.copy();
        u.negate();
        checkCase("overlap_tri_negation", overlap(t, u));
        checkCase("overlap_tri_circle", overlap(t, circle2(1.0, 0.8, 0.3)));
        checkCase("overlap_tri_square", overlap(t, square()));
    }

    @Test
    void simpVerticesMatchesC() {
        Region u = (Region) poly(TRI).copy();
        StringBuilder sb = new StringBuilder(simpVertices(u));
        u.setI("SimpVertices", 0);
        sb.append(simpVertices(u));
        u.clear("SimpVertices");
        sb.append(simpVertices(u));
        u.set("SimpVertices=5");
        sb.append(simpVertices(u));
        checkCase("simpvertices", sb.toString());
    }

    @Test
    void dumpsMatchC() {
        Polygon t = poly(TRI);
        checkCase("show_tri", ChannelText.dump(t));
        Region u = (Region) t.copy();
        u.setI("SimpVertices", 0);
        checkCase("show_tri_sv0", ChannelText.dump(u));
        Region back = (Region) ChannelText.read(ChannelText.dump(u));
        assertInstanceOf(Polygon.class, back);
        assertEquals(ChannelText.dump(u), ChannelText.dump(back));
    }

    @Test
    void mappedAndSimplifiedMatchC() {
        Polygon t = poly(TRI);
        Region u = t.mapRegionInto(new ZoomMap(2, 2.0), new Frame(2));
        Region v = (Region) u.simplify();
        checkCase(
                "mapped_zoom",
                tran(u, TRI_TEST) + bounds(u) + points(u) + "class " + v.astClass() + "\n" + negated(v) + points(v)
                        + tran(v, TRI_TEST));
        int[] perm = {2, 1};
        PermMap mirror = new PermMap(2, perm, 2, perm, new double[] {0.0});
        u = t.mapRegionInto(mirror, new Frame(2));
        v = (Region) u.simplify();
        checkCase(
                "mapped_mirror",
                tran(u, TRI_TEST) + bounds(u) + "class " + v.astClass() + "\n" + negated(v) + bounded(v) + points(v)
                        + tran(v, TRI_TEST));
        Region n = (Region) t.copy();
        n.negate();
        v = (Region) n.mapRegionInto(mirror, new Frame(2)).simplify();
        checkCase("mapped_mirror_negated", "class " + v.astClass() + "\n" + negated(v) + points(v) + tran(v, TRI_TEST));
    }
}
