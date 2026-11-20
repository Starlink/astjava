package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_regions.rs}, the port of C's {@code
 * ast_tester/testregions.c} that tests Regions of several classes together: the bounds of
 * CmpRegions and Prisms ({@code generalChecks}), spheres, Intervals, Boxes, CmpRegions and Prisms
 * in plain Frames, Circles, Boxes, Ellipses, Polygons and CmpRegions on a SkyFrame, a Region whose
 * System changes, Circles on the SkyFrame of a FITS header, permuted and picked axes, the
 * uncertainty set on a Region or given by default, the simplification of Regions, and a Region as
 * the target of {@code findFrame}.
 *
 * <p>Expected values are C's: those of {@code testregions.c} where the Rust test takes them from
 * it; C's bounds, from the probe {@code probes/regions_cross_probe.c}, where the Rust test checks
 * bounds of its own; and otherwise those the Rust test records, which the probe finds C gives for
 * each overlap and for several of the membership tests on the sky. The one the probe finds C does
 * not give is replaced by C's ({@link #cmpRegionNegatedReversal}). The other membership tests on
 * the sky, which the probe does not make, test points well clear of every boundary. The dumps of
 * Regions with Negated, Closed, MeshSize and FillFactor set are C's, from {@code
 * probes/region_dump_probe.c}.
 *
 * <p>{@code general_circle_bounds_and_disc} and {@code fitschan_sky_circle_bounds} are translated
 * without their checks of the bounding disc ({@code astGetRegionDisc}, not in jniast), as {@link
 * #generalCircleBounds} and {@link #fitsChanSkyCircleBounds}. Listed in {@code
 * deferred/rust-tests.txt}: {@code sky_circle_disc} and {@code sky_polygon_disc} ({@code
 * astGetRegionDisc}), and {@code convex_hull_basic} ({@code astConvex}, not in jniast).
 */
class RegionsTest {

    private static final double BAD = AstObject.AST__BAD;

    /** C's results for the checks {@code testregions.c} does not make, from {@code probes/regions_cross_probe.c}. */
    private static final Map<String, String> PROBE = ProbeOutput.load("regions_cross_probe");

    /** C's dumps of Regions with attributes set, from {@code probes/region_dump_probe.c}. */
    private static final Map<String, String> DUMP_PROBE = ProbeOutput.load("region_dump_probe");

    /**
     * The tolerance on bounds compared with C's. The bounds of a Region on a SkyFrame come from
     * its boundary mesh, each point of which is found by a few calls of {@code sin}, {@code cos},
     * {@code asin} and {@code atan2} (each within 1 ulp of the true value in C's library and in {@code
     * java.lang.Math}, except {@code atan2}, within 2 ulp there) and a few roundings, then normalised;
     * a bound is the extreme of those points, so it differs from C's by a few ulps of values below
     * 2*pi, which {@code asin} and {@code atan2} amplify near a pole, by up to about 10 for the meshes
     * here: under 1e-13. 1e-12 leaves a margin and is still far below any change in the mesh or in
     * the normalisation, which moves a bound by more than 1e-6.
     */
    private static final double BOUNDS_TOL = 1e-12;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The numbers of the line of probe case {@code name} that starts with {@code key}. */
    private static double[] probeValues(String name, String key) {
        String text = PROBE.get(name);
        assertNotNull(text, "probe case " + name);
        for (String line : CValues.lines(text)) {
            String[] f = line.trim().split(" ", -1);
            if (f[0].equals(key)) {
                double[] v = new double[f.length - 1];
                for (int i = 1; i < f.length; i++) {
                    v[i - 1] = Double.parseDouble(f[i]);
                }
                return v;
            }
        }
        throw new AssertionError("probe case " + name + " has no " + key + " line");
    }

    /** Checks the bounds of {@code r} against C's, the probe's lower then upper bound on each axis. */
    private static void assertBoundsAsC(String name, Region r) {
        double[][] b = r.getRegionBounds();
        double[] c = probeValues(name, "bounds");
        int n = b[0].length;
        assertEquals(2 * n, c.length, name + ": axes");
        for (int i = 0; i < n; i++) {
            assertEquals(c[i], b[0][i], BOUNDS_TOL, name + ": lower bound on axis " + (i + 1));
            assertEquals(c[n + i], b[1][i], BOUNDS_TOL, name + ": upper bound on axis " + (i + 1));
        }
    }

    /** Whether a lower bound is unbounded, as {@code testregions.c} tests it. */
    private static boolean isUnboundedLower(double v) {
        return v < 0.99 * -1.7e308;
    }

    /** Whether an upper bound is unbounded, as {@code testregions.c} tests it. */
    private static boolean isUnboundedUpper(double v) {
        return v > 0.99 * 1.7e308;
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    private static Interval interval(Frame f, double[] lbnd, double[] ubnd) {
        return new Interval(f, lbnd, ubnd, null);
    }

    /** A Box given by its bounds. */
    private static Box boxBounds(Frame f, double[] lbnd, double[] ubnd) {
        return new Box(f, 1, lbnd, ubnd, null);
    }

    /** A Box given by its centre and a corner. */
    private static Box boxCorner(Frame f, double[] centre, double[] corner) {
        return new Box(f, 0, centre, corner, null);
    }

    /** A Circle given by its centre and a point on its circumference. */
    private static Circle circleEdge(Frame f, double[] centre, double[] edge) {
        return new Circle(f, 0, centre, edge, null);
    }

    /** A Circle given by its centre and radius. */
    private static Circle circleRadius(Frame f, double[] centre, double radius) {
        return new Circle(f, centre, radius, null);
    }

    /** An Ellipse given by its centre, the half-lengths of its axes and the angle of its primary. */
    private static Ellipse ellipse(Frame f, double[] centre, double[] semiAxes, double angle) {
        return new Ellipse(f, 1, centre, semiAxes, new double[] {angle}, null);
    }

    /** A Polygon of vertices each {x, y}. */
    private static Polygon polygon(Frame f, double[][] vertices) {
        int n = vertices.length;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = vertices[i][0];
            y[i] = vertices[i][1];
        }
        return new Polygon(f, n, x, y, null);
    }

    /** The points {@code rows}, one coordinate array per point, transformed by {@code m}. */
    private static double[][] tranRows(Mapping m, boolean forward, double[]... rows) {
        int n = rows.length;
        int ncoord = rows[0].length;
        double[][] in = new double[ncoord][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < ncoord; j++) {
                in[j][i] = rows[i][j];
            }
        }
        double[][] out = m.transform(in, n, forward);
        double[][] result = new double[n][out.length];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < out.length; j++) {
                result[i][j] = out[j][i];
            }
        }
        return result;
    }

    /** {@code points} ({@code [axis][point]}) converted from ICRS to Galactic. */
    private static double[][] toGalactic(double[][] points) {
        FrameSet conversion = sky("ICRS").convert(sky("GALACTIC"), "");
        return conversion.transform(points, points[0].length, true);
    }

    /** The centre (1.0, 0.5) in ICRS converted to Galactic. */
    private static double[] galacticCentre() {
        double[][] gal = toGalactic(new double[][] {{1.0}, {0.5}});
        return new double[] {gal[0][0], gal[1][0]};
    }

    // ------------------------------------------------------------------
    // generalChecks: bounds of compound Regions in a plain Frame
    // ------------------------------------------------------------------

    /** The Interval axis 1 >= 0, axis 2 <= 0. */
    private static Interval generalInterval() {
        return interval(new Frame(2), new double[] {0.0, BAD}, new double[] {BAD, 0.0});
    }

    /** The unit Circle at the origin, given by an edge point. */
    private static Circle generalCircle() {
        return circleEdge(new Frame(2), new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
    }

    /** The 1-D Interval [-1, 1]. */
    private static Interval generalInterval1() {
        return interval(new Frame(1), new double[] {-1.0}, new double[] {1.0});
    }

    /** {@code generalChecks}, General 1 to 4. */
    @Test
    void generalIntervalBounds() {
        double[][] b = generalInterval().getRegionBounds();
        assertEquals(0.0, b[0][0], "General 1");
        assertTrue(isUnboundedLower(b[0][1]), "General 2");
        assertTrue(isUnboundedUpper(b[1][0]), "General 3");
        assertEquals(0.0, b[1][1], "General 4");
    }

    /**
     * {@code generalChecks}, General 5 to 8; General 8a to 8c, the disc, need {@code
     * astGetRegionDisc}, which jniast lacks.
     */
    @Test
    void generalCircleBounds() {
        double[][] b = generalCircle().getRegionBounds();
        assertEquals(-1.0, b[0][0], "General 5");
        assertEquals(-1.0, b[0][1], "General 6");
        assertEquals(1.0, b[1][0], "General 7");
        assertEquals(1.0, b[1][1], "General 8");
    }

    /** {@code generalChecks}, General 9 to 12. */
    @Test
    void generalCmpRegionOrBounds() {
        CmpRegion cmp = new CmpRegion(generalInterval(), generalCircle(), CmpRegion.AST__OR);
        double[][] b = cmp.getRegionBounds();
        assertEquals(-1.0, b[0][0], "General 9");
        assertTrue(isUnboundedLower(b[0][1]), "General 10");
        assertTrue(isUnboundedUpper(b[1][0]), "General 11");
        assertEquals(1.0, b[1][1], "General 12");
    }

    /** {@code generalChecks}, General 13 to 20. */
    @Test
    void generalPrismBounds() {
        CmpRegion cmp = new CmpRegion(generalInterval(), generalCircle(), CmpRegion.AST__OR);
        Interval i1 = generalInterval1();
        double[][] b1 = i1.getRegionBounds();
        assertEquals(-1.0, b1[0][0], "General 13");
        assertEquals(1.0, b1[1][0], "General 14");
        double[][] b = new Prism(cmp, i1).getRegionBounds();
        assertEquals(-1.0, b[0][0], "General 15");
        assertTrue(isUnboundedLower(b[0][1]), "General 16");
        assertTrue(isUnboundedUpper(b[1][0]), "General 17");
        assertEquals(1.0, b[1][1], "General 18");
        assertEquals(-1.0, b[0][2], "General 19");
        assertEquals(1.0, b[1][2], "General 20");
    }

    /** {@code generalChecks}, General 21 to 24: OR with a negated Circle is unbounded. */
    @Test
    void generalNegatedCircleOrBounds() {
        Circle circle = generalCircle();
        circle.negate();
        double[][] b = new CmpRegion(generalInterval(), circle, CmpRegion.AST__OR).getRegionBounds();
        assertTrue(isUnboundedLower(b[0][0]), "General 21");
        assertTrue(isUnboundedLower(b[0][1]), "General 22");
        assertTrue(isUnboundedUpper(b[1][0]), "General 23");
        assertTrue(isUnboundedUpper(b[1][1]), "General 24");
    }

    /** {@code generalChecks}, General 25 to 30. */
    @Test
    void generalNegatedCirclePrismBounds() {
        Circle circle = generalCircle();
        circle.negate();
        CmpRegion cmp = new CmpRegion(generalInterval(), circle, CmpRegion.AST__OR);
        double[][] b = new Prism(cmp, generalInterval1()).getRegionBounds();
        assertTrue(isUnboundedLower(b[0][0]), "General 25");
        assertTrue(isUnboundedLower(b[0][1]), "General 26");
        assertTrue(isUnboundedUpper(b[1][0]), "General 27");
        assertTrue(isUnboundedUpper(b[1][1]), "General 28");
        assertEquals(-1.0, b[0][2], "General 29");
        assertEquals(1.0, b[1][2], "General 30");
    }

    /** {@code generalChecks}, General 31 to 34: AND with a negated Circle has the Interval's bounds. */
    @Test
    void generalNegatedCircleAndBounds() {
        Circle circle = generalCircle();
        circle.negate();
        double[][] b = new CmpRegion(generalInterval(), circle, CmpRegion.AST__AND).getRegionBounds();
        assertEquals(0.0, b[0][0], "General 31");
        assertTrue(isUnboundedLower(b[0][1]), "General 32");
        assertTrue(isUnboundedUpper(b[1][0]), "General 33");
        assertEquals(0.0, b[1][1], "General 34");
    }

    /** {@code generalChecks}, General 35 to 40. */
    @Test
    void generalNegatedCircleAndPrismBounds() {
        Circle circle = generalCircle();
        circle.negate();
        CmpRegion cmp = new CmpRegion(generalInterval(), circle, CmpRegion.AST__AND);
        double[][] b = new Prism(cmp, generalInterval1()).getRegionBounds();
        assertEquals(0.0, b[0][0], "General 35");
        assertTrue(isUnboundedLower(b[0][1]), "General 36");
        assertTrue(isUnboundedUpper(b[1][0]), "General 37");
        assertEquals(0.0, b[1][1], "General 38");
        assertEquals(-1.0, b[0][2], "General 39");
        assertEquals(1.0, b[1][2], "General 40");
    }

    // ------------------------------------------------------------------
    // checkCircle: spheres in a 3-D Frame
    // ------------------------------------------------------------------

    /** The sphere about (1, 2, 3) through (0, -1, -2), of radius sqrt(35). */
    private static Circle sphere() {
        return circleEdge(new Frame(3), new double[] {1.0, 2.0, 3.0}, new double[] {0.0, -1.0, -2.0});
    }

    /** The points at {@code frac} of the radius of {@link #sphere} from its centre along {@code dir}. */
    private static double[] alongSphere(double[] dir, double frac) {
        double rad = Math.sqrt(1.0 + 9.0 + 25.0);
        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        double s = rad * frac / len;
        return new double[] {1.0 + dir[0] * s, 2.0 + dir[1] * s, 3.0 + dir[2] * s};
    }

    /** Sphere Errors 1 to 4: a point at 99.9% of the radius is inside, one at 100.1% outside. */
    @Test
    void sphere3dTransform() {
        double[] dir = {-1.0, -3.0, -5.0};
        double[] in = alongSphere(dir, 0.999);
        double[] out = alongSphere(dir, 1.001);
        double[][] r = tranRows(sphere(), true, in, out);
        assertArrayEquals(in, r[0], "Sphere: Error 1-2");
        assertArrayEquals(new double[] {BAD, BAD, BAD}, r[1], "Sphere: Error 3-4");
    }

    /** Sphere Errors 5 to 8, along the direction towards the origin. */
    @Test
    void sphere3dTransformOppositeDirection() {
        double[] dir = {-1.0, -2.0, -3.0};
        double[] in = alongSphere(dir, 0.999);
        double[] out = alongSphere(dir, 1.001);
        double[][] r = tranRows(sphere(), true, in, out);
        assertArrayEquals(in, r[0], "Sphere: Error 5-6");
        assertArrayEquals(new double[] {BAD, BAD, BAD}, r[1], "Sphere: Error 7-8");
    }

    /** Sphere Error 10: a sphere overlaps itself as the same Region. */
    @Test
    void sphere3dOverlapSame() {
        Circle c = sphere();
        assertEquals(Region.OVERLAP_SAME, c.overlap(c), "Sphere: Error 10");
    }

    /** Sphere Errors 11 and 12: a smaller sphere about the same centre is inside. */
    @Test
    void sphere3dOverlapFirstInside() {
        Circle big = sphere();
        Circle small = circleEdge(new Frame(3), new double[] {1.0, 2.0, 3.0}, new double[] {0.5, 0.0, -1.0});
        assertEquals(Region.OVERLAP_INSIDE, small.overlap(big), "Sphere: Error 11");
        assertEquals(Region.OVERLAP_OUTSIDE, big.overlap(small), "Sphere: Error 12");
    }

    /** Sphere Error 13: spheres offset on axis 2 overlap partly. */
    @Test
    void sphere3dOverlapPartial() {
        Circle c2 = circleEdge(new Frame(3), new double[] {1.0, 0.0, 3.0}, new double[] {0.0, -1.0, -2.0});
        assertEquals(Region.OVERLAP_PARTIAL, sphere().overlap(c2), "Sphere: Error 13");
    }

    /** Sphere Error 14: distant spheres do not overlap. */
    @Test
    void sphere3dOverlapNone() {
        Circle c2 = circleEdge(new Frame(3), new double[] {1.0, 102.0, 3.0}, new double[] {0.0, 99.0, -2.0});
        assertEquals(Region.OVERLAP_NONE, sphere().overlap(c2), "Sphere: Error 14");
    }

    /** Two unit spheres whose centres are {@code sep} apart on axis 1. */
    private static Circle[] touchingSpheres(double sep) {
        Circle c1 = circleEdge(new Frame(3), new double[] {0.0, 0.0, 0.0}, new double[] {0.0, 0.0, 1.0});
        Circle c2 = circleEdge(new Frame(3), new double[] {sep, 0.0, 0.0}, new double[] {sep, 1.0, 0.0});
        return new Circle[] {c1, c2};
    }

    /** Sphere Error 15: closed spheres touching to within their uncertainty overlap partly. */
    @Test
    void sphere3dOverlapTouchingClosed() {
        Circle[] c = touchingSpheres(2.000001);
        assertEquals(Region.OVERLAP_PARTIAL, c[0].overlap(c[1]), "Sphere: Error 15");
    }

    /** Sphere Error 17: open spheres touching to within their uncertainty do not overlap. */
    @Test
    void sphere3dOverlapTouchingOpen() {
        Circle[] c = touchingSpheres(2.000001);
        c[0].setClosed(false);
        c[1].setClosed(false);
        assertEquals(Region.OVERLAP_NONE, c[0].overlap(c[1]), "Sphere: Error 17");
    }

    /** Sphere Error 18: spheres farther apart than their radii do not overlap. */
    @Test
    void sphere3dOverlapClearlySeparated() {
        Circle[] c = touchingSpheres(2.000004);
        assertEquals(Region.OVERLAP_NONE, c[0].overlap(c[1]), "Sphere: Error 18");
    }

    // ------------------------------------------------------------------
    // Intervals in a plain Frame
    // ------------------------------------------------------------------

    /** Points inside, outside and on the boundary of a closed 2-D Interval, before and after negation. */
    @Test
    void interval2dBasicMembershipAndNegation() {
        Interval iv = interval(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        assertTrue(iv.pointInRegion(new double[] {3.0, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {0.0, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {3.0, 9.0}));
        assertTrue(iv.pointInRegion(new double[] {1.0, 2.0}));
        assertTrue(iv.pointInRegion(new double[] {5.0, 8.0}));
        iv.negate();
        assertFalse(iv.pointInRegion(new double[] {3.0, 5.0}));
        assertTrue(iv.pointInRegion(new double[] {0.0, 5.0}));
        assertTrue(iv.pointInRegion(new double[] {3.0, 9.0}));
    }

    /** An Interval with no upper limit on axis 1 and no lower limit on axis 2. */
    @Test
    void interval2dUnboundedMembership() {
        Interval iv = interval(new Frame(2), new double[] {2.0, BAD}, new double[] {BAD, 5.0});
        assertTrue(iv.pointInRegion(new double[] {3.0, 4.0}));
        assertTrue(iv.pointInRegion(new double[] {100.0, -100.0}));
        assertTrue(iv.pointInRegion(new double[] {2.0, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {1.0, 4.0}));
        assertFalse(iv.pointInRegion(new double[] {3.0, 6.0}));
    }

    @Test
    void interval2dOverlapBasic() {
        Interval a = interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {4.0, 4.0});
        Interval b = interval(new Frame(2), new double[] {2.0, 2.0}, new double[] {6.0, 6.0});
        assertEquals(Region.OVERLAP_PARTIAL, a.overlap(b));
    }

    @Test
    void interval2dOverlapFirstInside() {
        Interval a = interval(new Frame(2), new double[] {2.0, 2.0}, new double[] {3.0, 3.0});
        Interval b = interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {5.0, 5.0});
        assertEquals(Region.OVERLAP_INSIDE, a.overlap(b));
        assertEquals(Region.OVERLAP_OUTSIDE, b.overlap(a));
    }

    @Test
    void interval2dOverlapNone() {
        Interval a = interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        Interval b = interval(new Frame(2), new double[] {5.0, 5.0}, new double[] {6.0, 6.0});
        assertEquals(Region.OVERLAP_NONE, a.overlap(b));
    }

    @Test
    void interval2dOverlapSame() {
        Interval a = interval(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        Interval b = interval(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        assertEquals(Region.OVERLAP_SAME, a.overlap(b));
    }

    // ------------------------------------------------------------------
    // Boxes in a plain Frame
    // ------------------------------------------------------------------

    /** A closed Box passes its centre and corners and makes points outside it bad. */
    @Test
    void boxTransformMembership() {
        Box bx = boxBounds(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        double[][] r = tranRows(
                bx,
                true,
                new double[] {3.0, 5.0},
                new double[] {1.0, 2.0},
                new double[] {5.0, 8.0},
                new double[] {0.5, 5.0},
                new double[] {3.0, 8.5},
                new double[] {0.0, 0.0});
        assertArrayEquals(new double[] {3.0, 5.0}, r[0]);
        assertArrayEquals(new double[] {1.0, 2.0}, r[1]);
        assertArrayEquals(new double[] {5.0, 8.0}, r[2]);
        for (int i = 3; i < 6; i++) {
            assertArrayEquals(new double[] {BAD, BAD}, r[i], "point " + i);
        }
    }

    @Test
    void boxOverlapBasic() {
        Box b1 = boxBounds(new Frame(2), new double[] {0.0, 0.0}, new double[] {4.0, 4.0});
        Box b2 = boxBounds(new Frame(2), new double[] {2.0, 2.0}, new double[] {6.0, 6.0});
        assertEquals(Region.OVERLAP_PARTIAL, b1.overlap(b2));
        Box b3 = boxBounds(new Frame(2), new double[] {10.0, 10.0}, new double[] {12.0, 12.0});
        assertEquals(Region.OVERLAP_NONE, b1.overlap(b3));
        Box b4 = boxBounds(new Frame(2), new double[] {-1.0, -1.0}, new double[] {5.0, 5.0});
        assertEquals(Region.OVERLAP_INSIDE, b1.overlap(b4));
        assertEquals(Region.OVERLAP_OUTSIDE, b4.overlap(b1));
    }

    @Test
    void boxOverlapSame() {
        Box b1 = boxBounds(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        Box b2 = boxBounds(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        assertEquals(Region.OVERLAP_SAME, b1.overlap(b2));
    }

    // ------------------------------------------------------------------
    // checkCmpRegion in a plain Frame
    // ------------------------------------------------------------------

    /**
     * {@code checkCmpRegion}'s CmpRegion: the unit square ANDed with the negation of the OR of the
     * unit Circles about (-1, 0) and (1, 0).
     */
    private static CmpRegion squareWithoutCircles() {
        Frame f = new Frame(2);
        Box r1 = boxBounds(f, new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        Circle r2 = circleEdge(f, new double[] {-1.0, 0.0}, new double[] {0.0, 0.0});
        Circle r3 = circleEdge(f, new double[] {1.0, 0.0}, new double[] {0.0, 0.0});
        CmpRegion cr = new CmpRegion(r2, r3, CmpRegion.AST__OR);
        cr.negate();
        return new CmpRegion(cr, r1, CmpRegion.AST__AND);
    }

    private static final double[][] SQUARE_POINTS = {{0.0, 0.5}, {0.2, 1.5}, {0.5, 0.5}, {-0.5, 0.5}};

    /** CmpRegion Errors 11 to 18. */
    @Test
    void cmpRegionBasicOrNegatedAndBox() {
        double[][] r = tranRows(squareWithoutCircles(), true, SQUARE_POINTS);
        assertArrayEquals(new double[] {0.0, 0.5}, r[0], "CmpRegion:Error 11-12");
        assertArrayEquals(new double[] {BAD, BAD}, r[1], "CmpRegion:Error 13-14");
        assertArrayEquals(new double[] {BAD, BAD}, r[2], "CmpRegion:Error 15-16");
        assertArrayEquals(new double[] {BAD, BAD}, r[3], "CmpRegion:Error 17-18");
    }

    /**
     * CmpRegion Errors 19 to 26: the same, negated. The Rust test gives the square by its bounds
     * where {@code testregions.c} gives it by its centre and a corner (the square [-1, 1] x [-1,
     * 1]), so (0, 0.5) lies on its boundary; a closed Region holds its boundary whether or not it
     * is negated, so C keeps the point (probe case {@code square_without_circles_negated}), where
     * the Rust test expects it to be bad.
     */
    @Test
    void cmpRegionNegatedReversal() {
        CmpRegion cr2 = squareWithoutCircles();
        cr2.negate();
        double[][] r = tranRows(cr2, true, SQUARE_POINTS);
        assertArrayEquals(new double[] {1.0, 1.0, 1.0, 1.0}, probeValues("square_without_circles_negated", "tran"));
        assertArrayEquals(new double[] {0.0, 0.5}, r[0], "CmpRegion:Error 19-20, C's value");
        assertArrayEquals(new double[] {0.2, 1.5}, r[1], "CmpRegion:Error 21-22");
        assertArrayEquals(new double[] {0.5, 0.5}, r[2], "CmpRegion:Error 23-24");
        assertArrayEquals(new double[] {-0.5, 0.5}, r[3], "CmpRegion:Error 25-26");
    }

    // ------------------------------------------------------------------
    // Prisms in a plain Frame
    // ------------------------------------------------------------------

    /** The unit square as a Box. */
    private static Box unitSquare() {
        return boxBounds(new Frame(2), new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
    }

    private static Interval interval1(double lo, double hi) {
        return interval(new Frame(1), new double[] {lo}, new double[] {hi});
    }

    @Test
    void prismBoundsBasic() {
        Box bx = boxBounds(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        Prism prism = new Prism(bx, interval1(10.0, 20.0));
        assertEquals(3, prism.getNaxes());
        double[][] b = prism.getRegionBounds();
        assertArrayEquals(new double[] {1.0, 2.0, 10.0}, b[0]);
        assertArrayEquals(new double[] {5.0, 8.0, 20.0}, b[1]);
    }

    @Test
    void prismOverlapSame() {
        Prism prism = new Prism(unitSquare(), interval1(5.0, 6.0));
        assertEquals(Region.OVERLAP_SAME, prism.overlap(prism), "Prism self-overlap");
    }

    @Test
    void prismOverlapSecondInside() {
        Prism big = new Prism(unitSquare(), interval1(5.0, 10.0));
        Prism small = new Prism(unitSquare(), interval1(6.0, 8.0));
        assertEquals(Region.OVERLAP_OUTSIDE, big.overlap(small), "Prism containment");
        assertEquals(Region.OVERLAP_INSIDE, small.overlap(big), "Prism containment reverse");
    }

    @Test
    void prismOverlapPartial() {
        Prism p1 = new Prism(unitSquare(), interval1(5.0, 10.0));
        Prism p2 = new Prism(unitSquare(), interval1(8.0, 12.0));
        assertEquals(Region.OVERLAP_PARTIAL, p1.overlap(p2), "Prism partial");
        assertEquals(Region.OVERLAP_PARTIAL, p2.overlap(p1), "Prism partial reverse");
    }

    @Test
    void prismOverlapNone() {
        Prism p1 = new Prism(unitSquare(), interval1(5.0, 6.0));
        Prism p2 = new Prism(unitSquare(), interval1(7.0, 8.0));
        assertEquals(Region.OVERLAP_NONE, p1.overlap(p2), "Prism no overlap");
        assertEquals(Region.OVERLAP_NONE, p2.overlap(p1), "Prism no overlap reverse");
    }

    /** A cylinder: a Circle of radius 5 extruded over [2, 8]. */
    @Test
    void prismMembership3d() {
        Circle circle = circleRadius(new Frame(2), new double[] {0.0, 0.0}, 5.0);
        Prism prism = new Prism(circle, interval1(2.0, 8.0));
        assertTrue(prism.pointInRegion(new double[] {0.0, 0.0, 5.0}));
        assertTrue(prism.pointInRegion(new double[] {3.0, 4.0, 2.0}));
        assertTrue(prism.pointInRegion(new double[] {4.0, 0.0, 8.0}));
        assertFalse(prism.pointInRegion(new double[] {6.0, 0.0, 5.0}));
        assertFalse(prism.pointInRegion(new double[] {0.0, 0.0, 1.0}));
        assertFalse(prism.pointInRegion(new double[] {0.0, 0.0, 9.0}));
        assertFalse(prism.pointInRegion(new double[] {6.0, 0.0, 9.0}));
    }

    // ------------------------------------------------------------------
    // CmpRegions of Circles and Boxes in a plain Frame
    // ------------------------------------------------------------------

    /** The AND of the Circle of radius 5 at the origin and the Box [-3, 3] x [-3, 3]. */
    private static CmpRegion circleAndBox() {
        Frame f = new Frame(2);
        Circle circle = circleRadius(f, new double[] {0.0, 0.0}, 5.0);
        Box bx = boxBounds(f, new double[] {-3.0, -3.0}, new double[] {3.0, 3.0});
        return new CmpRegion(circle, bx, CmpRegion.AST__AND);
    }

    @Test
    void cmpRegionAndOverlapSame() {
        assertEquals(Region.OVERLAP_SAME, circleAndBox().overlap(circleAndBox()));
    }

    @Test
    void cmpRegionOrMembership() {
        Frame f = new Frame(2);
        Circle c1 = circleRadius(f, new double[] {-3.0, 0.0}, 2.0);
        Circle c2 = circleRadius(f, new double[] {3.0, 0.0}, 2.0);
        CmpRegion cmp = new CmpRegion(c1, c2, CmpRegion.AST__OR);
        assertTrue(cmp.pointInRegion(new double[] {-3.0, 0.0}));
        assertTrue(cmp.pointInRegion(new double[] {-2.0, 0.0}));
        assertTrue(cmp.pointInRegion(new double[] {3.0, 0.0}));
        assertTrue(cmp.pointInRegion(new double[] {4.0, 0.0}));
        assertFalse(cmp.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(cmp.pointInRegion(new double[] {0.0, 5.0}));
    }

    @Test
    void cmpRegionAndMembership() {
        CmpRegion cmp = circleAndBox();
        assertTrue(cmp.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(cmp.pointInRegion(new double[] {2.0, 2.0}));
        assertTrue(cmp.pointInRegion(new double[] {-3.0, 0.0}));
        assertFalse(cmp.pointInRegion(new double[] {4.0, 0.0}));
        assertFalse(cmp.pointInRegion(new double[] {0.0, 4.0}));
        assertTrue(cmp.pointInRegion(new double[] {3.0, 3.0}));
        assertFalse(cmp.pointInRegion(new double[] {6.0, 0.0}));
    }

    /** The Box lies inside the Circle, so the AND is the Box. */
    @Test
    void cmpRegionAndBoxInsideCircle() {
        CmpRegion cmp = circleAndBox();
        assertTrue(cmp.pointInRegion(new double[] {3.0, 3.0}));
        assertTrue(cmp.pointInRegion(new double[] {-3.0, -3.0}));
        assertFalse(cmp.pointInRegion(new double[] {4.0, 0.0}));
        assertFalse(cmp.pointInRegion(new double[] {0.0, -4.0}));
    }

    /** The bounds of a Prism of {@link #circleAndBox} and [100, 200] are the Box's and the Interval's. */
    @Test
    void prismWithCmpRegionBounds() {
        Prism prism = new Prism(circleAndBox(), interval1(100.0, 200.0));
        assertEquals(3, prism.getNaxes());
        assertBoundsAsC("prism_circle_and_box_bounds", prism);
    }

    // ------------------------------------------------------------------
    // Circles on a SkyFrame
    // ------------------------------------------------------------------

    private static final double[] SKY_CENTRE = {1.2217305, 1.3962634};
    private static final double[] SKY_EDGE = {0.8, 0.8};

    /** The Circle about {@link #SKY_CENTRE} through {@link #SKY_EDGE} in {@code frame}. */
    private static Circle skyCircle(Frame frame) {
        return circleEdge(frame, SKY_CENTRE, SKY_EDGE);
    }

    /**
     * Points at 99.9% and 100.1% of the radius of {@link #skyCircle} towards {@code toward} are
     * inside and outside.
     */
    private static void checkSkyCircleRadius(Frame frame, double[] toward, double fin, double fout, String what) {
        Circle circle = skyCircle(frame);
        double rad = frame.distance(SKY_CENTRE, SKY_EDGE);
        assertTrue(rad > 0.0, "radius must be positive");
        double[] in = frame.offset(SKY_CENTRE, toward, rad * fin);
        double[] out = frame.offset(SKY_CENTRE, toward, rad * fout);
        double[][] r = tranRows(circle, true, in, out);
        assertArrayEquals(in, r[0], what + ": inside point should pass");
        assertArrayEquals(new double[] {BAD, BAD}, r[1], what + ": outside point should be bad");
    }

    /** {@code checkCircle}: points just inside and outside along the line to the edge point. */
    @Test
    void skyCircleBasicMembership() {
        checkSkyCircleRadius(sky("ICRS"), SKY_EDGE, 0.999, 1.001, "SkyCircle");
    }

    @Test
    void skyCircleMembershipOppositeDirection() {
        double[] other = {SKY_CENTRE[0] + 0.5, SKY_CENTRE[1] - 0.5};
        checkSkyCircleRadius(sky("ICRS"), other, 0.9, 1.1, "SkyCircle opposite");
    }

    @Test
    void skyCircleCentreIsInside() {
        assertTrue(skyCircle(sky("ICRS")).pointInRegion(SKY_CENTRE), "Centre must be inside circle");
    }

    @Test
    void skyCircleEdgeIsInsideClosed() {
        assertTrue(skyCircle(sky("ICRS")).pointInRegion(SKY_EDGE), "Edge point must be inside closed circle");
    }

    @Test
    void skyCircleFromRadius() {
        SkyFrame sky = sky("ICRS");
        double[] centre = {0.0, 0.0};
        Circle circle = circleRadius(sky, centre, 0.1);
        double[] in = sky.offset(centre, new double[] {0.1, 0.0}, 0.05);
        assertTrue(circle.pointInRegion(in), "Point at half radius should be inside");
        double[] out = sky.offset(centre, new double[] {0.1, 0.0}, 0.15);
        assertFalse(circle.pointInRegion(out), "Point at 1.5x radius should be outside");
    }

    @Test
    void skyCircleOverlapSame() {
        SkyFrame sky = sky("ICRS");
        Circle c1 = circleRadius(sky, new double[] {1.0, 0.5}, 0.2);
        Circle c2 = circleRadius(sky, new double[] {1.0, 0.5}, 0.2);
        assertEquals(Region.OVERLAP_SAME, c1.overlap(c2));
    }

    @Test
    void skyCircleOverlapFirstInside() {
        SkyFrame sky = sky("ICRS");
        Circle big = circleRadius(sky, new double[] {1.0, 0.5}, 0.5);
        Circle small = circleRadius(sky, new double[] {1.0, 0.5}, 0.1);
        assertEquals(Region.OVERLAP_INSIDE, small.overlap(big));
        assertEquals(Region.OVERLAP_OUTSIDE, big.overlap(small));
    }

    @Test
    void skyCircleOverlapPartial() {
        SkyFrame sky = sky("ICRS");
        Circle c1 = circleRadius(sky, new double[] {1.0, 0.5}, 0.3);
        Circle c2 = circleRadius(sky, new double[] {1.4, 0.5}, 0.3);
        assertEquals(Region.OVERLAP_PARTIAL, c1.overlap(c2));
    }

    @Test
    void skyCircleOverlapNone() {
        SkyFrame sky = sky("ICRS");
        Circle c1 = circleRadius(sky, new double[] {0.0, 0.0}, 0.1);
        Circle c2 = circleRadius(sky, new double[] {Math.PI, 0.0}, 0.1);
        assertEquals(Region.OVERLAP_NONE, c1.overlap(c2));
    }

    /**
     * A Circle of radius 0.1 on the equator: its bounds come from its boundary mesh, so the upper
     * latitude is a little short of 0.1.
     */
    @Test
    void skyCircleBoundsEquator() {
        assertBoundsAsC("sky_circle_bounds_equator", circleRadius(sky("ICRS"), new double[] {1.0, 0.0}, 0.1));
    }

    /** A Circle about the north pole spans every longitude. */
    @Test
    void skyCircleBoundsNearPole() {
        assertBoundsAsC("sky_circle_bounds_near_pole", circleRadius(sky("ICRS"), new double[] {0.8, Math.PI / 2}, 0.1));
    }

    @Test
    void skyCircleNegatedMembership() {
        SkyFrame sky = sky("ICRS");
        double[] centre = {1.0, 0.5};
        Circle circle = circleRadius(sky, centre, 0.2);
        assertTrue(circle.pointInRegion(centre));
        circle.negate();
        assertFalse(circle.pointInRegion(centre));
        double[] far = sky.offset(centre, new double[] {2.0, 0.0}, 0.2 * 2.0);
        assertTrue(circle.pointInRegion(far));
    }

    @Test
    void skyCircleOpenBoundary() {
        double[] centre = {1.0, 0.5};
        double[] edge = {1.1, 0.5};
        Circle circle = circleEdge(sky("ICRS"), centre, edge);
        assertTrue(circle.pointInRegion(edge), "Closed circle includes edge");
        circle.setClosed(false);
        assertFalse(circle.pointInRegion(edge), "Open circle excludes edge");
        assertTrue(circle.pointInRegion(centre), "Open circle includes centre");
    }

    @Test
    void skyCircleFk5Membership() {
        SkyFrame sky = sky("FK5");
        double[] centre = {0.5, 0.3};
        double radius = 0.05;
        Circle circle = circleRadius(sky, centre, radius);
        double[] in = sky.offset(centre, new double[] {0.6, 0.3}, radius * 0.8);
        assertTrue(circle.pointInRegion(in), "FK5 circle: inside point");
        double[] out = sky.offset(centre, new double[] {0.6, 0.3}, radius * 1.2);
        assertFalse(circle.pointInRegion(out), "FK5 circle: outside point");
    }

    // ------------------------------------------------------------------
    // Boxes on a SkyFrame
    // ------------------------------------------------------------------

    /** The Box about (1.0, 0.5) with corner (1.1, 0.6). */
    private static Box skyBox() {
        return boxCorner(sky("ICRS"), new double[] {1.0, 0.5}, new double[] {1.1, 0.6});
    }

    @Test
    void skyBoxBasicMembership() {
        Box bx = skyBox();
        assertTrue(bx.pointInRegion(new double[] {1.0, 0.5}), "SkyBox: centre");
        assertTrue(bx.pointInRegion(new double[] {1.1, 0.6}), "SkyBox: corner");
        assertTrue(bx.pointInRegion(new double[] {0.9, 0.4}), "SkyBox: opposite corner");
        assertFalse(bx.pointInRegion(new double[] {1.2, 0.5}), "SkyBox: outside RA");
        assertFalse(bx.pointInRegion(new double[] {1.0, 0.7}), "SkyBox: outside Dec");
    }

    @Test
    void skyBoxBounds() {
        assertBoundsAsC("sky_box_bounds", skyBox());
    }

    @Test
    void skyBoxOverlapSame() {
        assertEquals(Region.OVERLAP_SAME, skyBox().overlap(skyBox()));
    }

    @Test
    void skyBoxOverlapNone() {
        Box b1 = boxCorner(sky("ICRS"), new double[] {0.5, 0.3}, new double[] {0.6, 0.4});
        Box b2 = boxCorner(sky("ICRS"), new double[] {3.0, -0.5}, new double[] {3.1, -0.4});
        assertEquals(Region.OVERLAP_NONE, b1.overlap(b2));
    }

    @Test
    void skyBoxOverlapPartial() {
        Box b2 = boxCorner(sky("ICRS"), new double[] {1.05, 0.55}, new double[] {1.15, 0.65});
        assertEquals(Region.OVERLAP_PARTIAL, skyBox().overlap(b2));
    }

    // ------------------------------------------------------------------
    // Ellipses and Polygons on a SkyFrame
    // ------------------------------------------------------------------

    /** An Ellipse with its primary axis along RA. */
    @Test
    void skyEllipseBasicMembership() {
        double[] centre = {1.0, 0.5};
        Ellipse ell = ellipse(sky("ICRS"), centre, new double[] {0.1, 0.05}, Math.PI / 2);
        assertTrue(ell.pointInRegion(centre), "centre inside");
        assertTrue(ell.pointInRegion(new double[] {1.05, 0.5}), "halfway along major axis");
        assertTrue(ell.pointInRegion(new double[] {1.0, 0.52}), "near centre along minor axis");
        assertFalse(ell.pointInRegion(new double[] {1.15, 0.5}), "beyond major axis");
        assertFalse(ell.pointInRegion(new double[] {1.0, 0.6}), "beyond minor axis");
    }

    /** An Ellipse with its primary axis at 45 degrees. */
    @Test
    void skyEllipseRotatedMembership() {
        double[] centre = {1.0, 0.0};
        Ellipse ell = ellipse(sky("ICRS"), centre, new double[] {0.1, 0.02}, Math.PI / 4);
        assertTrue(ell.pointInRegion(centre), "centre inside");
        double diag = 0.05 / Math.sqrt(2.0);
        assertTrue(ell.pointInRegion(new double[] {centre[0] + diag, centre[1] + diag}), "along major diagonal");
    }

    /** A sky Polygon straddling RA = 0 has bounds 0.1 wide in longitude, not nearly 2*pi. */
    @Test
    void polygonSkyBoundsUseFirstVertexOrigin() {
        Polygon poly = polygon(sky("ICRS"), new double[][] {
            {2.0 * Math.PI - 0.05, 0.1}, {0.05, 0.1}, {0.05, -0.1}, {2.0 * Math.PI - 0.05, -0.1}
        });
        assertBoundsAsC("sky_polygon_ra0_bounds", poly);
    }

    /** An Ellipse enclosing the north pole spans every longitude and reaches the pole. */
    @Test
    void ellipseSkyPoleBoundsSpanAllLongitudes() {
        Ellipse ell = ellipse(sky("ICRS"), new double[] {0.0, Math.PI / 2 - 0.2}, new double[] {0.5, 0.3}, Math.PI);
        assertBoundsAsC("sky_ellipse_pole_bounds", ell);
    }

    @Test
    void skyPolygonBasicMembership() {
        Polygon poly = polygon(sky("ICRS"), new double[][] {{1.0, 0.5}, {1.0, 0.6}, {1.1, 0.6}, {1.1, 0.5}});
        assertTrue(poly.pointInRegion(new double[] {1.05, 0.55}), "centre inside");
        assertTrue(poly.pointInRegion(new double[] {1.0, 0.5}), "corner inside (closed)");
        assertFalse(poly.pointInRegion(new double[] {2.0, 0.55}), "outside in RA");
        assertFalse(poly.pointInRegion(new double[] {1.05, 0.8}), "outside in Dec");
    }

    @Test
    void skyPolygonTriangleMembership() {
        Polygon poly = polygon(sky("ICRS"), new double[][] {{0.5, 0.2}, {0.6, 0.4}, {0.7, 0.2}});
        assertTrue(poly.pointInRegion(new double[] {0.6, 0.267}), "centroid");
        assertFalse(poly.pointInRegion(new double[] {0.5, 0.39}), "outside near apex");
    }

    @Test
    void skyPolygonBounds() {
        Polygon poly = polygon(sky("ICRS"), new double[][] {{1.0, 0.5}, {1.1, 0.5}, {1.1, 0.6}, {1.0, 0.6}});
        assertBoundsAsC("sky_polygon_bounds", poly);
    }

    // ------------------------------------------------------------------
    // CmpRegions, large Circles, Prisms on a SkyFrame
    // ------------------------------------------------------------------

    /** The AND of a Circle of radius 0.2 and a Box of half-widths 0.15 about (1.0, 0.5). */
    @Test
    void skyCmpRegionAndCircleBox() {
        Circle circle = circleRadius(sky("ICRS"), new double[] {1.0, 0.5}, 0.2);
        Box bx = boxCorner(sky("ICRS"), new double[] {1.0, 0.5}, new double[] {1.15, 0.65});
        CmpRegion cmp = new CmpRegion(circle, bx, CmpRegion.AST__AND);
        assertTrue(cmp.pointInRegion(new double[] {1.0, 0.5}), "centre");
        // (1.14, 0.64) is inside the Box and, at about 0.18 from the centre, inside the Circle
        // (probe case sky_circle_and_box_membership); the Rust test checks it only if it is
        // farther than 0.2 from the centre, which it is not.
        assertTrue(cmp.pointInRegion(new double[] {1.14, 0.64}), "box corner inside circle");
        assertFalse(cmp.pointInRegion(new double[] {0.84, 0.5}), "inside circle but outside box");
    }

    @Test
    void skyCmpRegionOrTwoCircles() {
        Circle c1 = circleRadius(sky("ICRS"), new double[] {1.0, 0.5}, 0.1);
        Circle c2 = circleRadius(sky("ICRS"), new double[] {1.5, 0.5}, 0.1);
        CmpRegion cmp = new CmpRegion(c1, c2, CmpRegion.AST__OR);
        assertTrue(cmp.pointInRegion(new double[] {1.0, 0.5}), "centre of c1");
        assertTrue(cmp.pointInRegion(new double[] {1.5, 0.5}), "centre of c2");
        assertFalse(cmp.pointInRegion(new double[] {1.25, 0.5}), "midpoint outside both");
    }

    /** A Circle of radius 2, more than a hemisphere. */
    @Test
    void skyCircleLargeRadius() {
        SkyFrame sky = sky("ICRS");
        double[] centre = {0.0, 0.0};
        Circle circle = circleRadius(sky, centre, 2.0);
        assertTrue(circle.pointInRegion(centre), "centre");
        double[] p90 = {Math.PI / 2, 0.0};
        assertTrue(sky.distance(centre, p90) < 2.0, "Distance to 90 deg point should be < radius");
        assertTrue(circle.pointInRegion(p90), "point at 90 degrees is inside");
        double[] anti = {Math.PI, 0.0};
        assertTrue(sky.distance(centre, anti) > 2.0);
        assertFalse(circle.pointInRegion(anti), "antipodal point is outside");
    }

    @Test
    void skyCircleBatchTransform() {
        SkyFrame sky = sky("ICRS");
        double[] centre = {2.0, -0.3};
        double radius = 0.15;
        Circle circle = circleRadius(sky, centre, radius);
        double[] fracs = {0.0, 0.5, 0.99, 1.01, 1.5, 2.0};
        double[][] pts = new double[fracs.length][];
        for (int i = 0; i < fracs.length; i++) {
            pts[i] = sky.offset(centre, new double[] {2.2, -0.2}, radius * fracs[i]);
        }
        double[][] r = tranRows(circle, true, pts);
        for (int i = 0; i < 3; i++) {
            assertNotEquals(BAD, r[i][0], "Batch point " + i + " (frac " + fracs[i] + ") should be inside");
        }
        for (int i = 3; i < fracs.length; i++) {
            assertEquals(BAD, r[i][0], "Batch point " + i + " (frac " + fracs[i] + ") should be outside");
        }
    }

    @Test
    void skyBoxNegated() {
        Box bx = skyBox();
        assertTrue(bx.pointInRegion(new double[] {1.0, 0.5}));
        assertFalse(bx.pointInRegion(new double[] {3.0, 0.0}));
        bx.negate();
        assertFalse(bx.pointInRegion(new double[] {1.0, 0.5}));
        assertTrue(bx.pointInRegion(new double[] {3.0, 0.0}));
    }

    /** A sky Circle extruded over [100, 200]. */
    @Test
    void skyCirclePrismWithInterval() {
        SkyFrame sky = sky("ICRS");
        Circle circle = circleRadius(sky, new double[] {1.0, 0.5}, 0.1);
        Prism prism = new Prism(circle, interval1(100.0, 200.0));
        assertEquals(3, prism.getNaxes());
        assertTrue(prism.pointInRegion(new double[] {1.0, 0.5, 150.0}), "inside");
        assertFalse(prism.pointInRegion(new double[] {1.0, 0.5, 250.0}), "outside interval");
        double[] out = sky.offset(new double[] {1.0, 0.5}, new double[] {1.5, 0.5}, 0.15);
        assertFalse(prism.pointInRegion(new double[] {out[0], out[1], 150.0}), "outside circle");
    }

    /**
     * At latitude 80 degrees a longitude difference of 0.5 is about 0.087 of arc, and a Circle
     * through such a point does not reach a point at the same longitude on the equator.
     */
    @Test
    void skyCircleGreatCircleDistanceCorrectness() {
        SkyFrame sky = sky("ICRS");
        double[] p1 = {1.0, 1.396};
        double[] p2 = {1.5, 1.396};
        double gc = sky.distance(p1, p2);
        assertEquals(0.5 * Math.cos(1.396), gc, 0.01, "Great-circle distance at Dec=80");
        Circle circle = circleEdge(sky, p1, p2);
        assertTrue(circle.pointInRegion(p2), "Edge point is inside closed circle");
        assertFalse(circle.pointInRegion(new double[] {1.5, 0.0}), "Point at equator with same delta-RA is outside");
    }

    // ------------------------------------------------------------------
    // A change of System
    // ------------------------------------------------------------------

    /** A Circle made in ICRS whose System is set to Galactic holds the converted centre. */
    @Test
    void systemChangeCircleMembership() {
        Circle circle = circleRadius(sky("ICRS"), new double[] {1.0, 0.5}, 0.01);
        circle.setSystem("GALACTIC");
        double[] gal = galacticCentre();
        assertTrue(circle.pointInRegion(gal), "converted centre should be inside");
        assertFalse(circle.pointInRegion(new double[] {gal[0] + 1.0, gal[1]}), "far point should be outside");
    }

    /**
     * A Circle made in Galactic coordinates overlaps an identical Circle as the same Region, and a
     * smaller one about the same centre lies inside it.
     */
    @Test
    void systemChangeCircleOverlapNativeGalactic() {
        SkyFrame gal = sky("GALACTIC");
        Circle c1 = circleRadius(gal, new double[] {1.0, 0.5}, 0.01);
        Circle c2 = circleRadius(gal, new double[] {1.0, 0.5}, 0.01);
        assertEquals(Region.OVERLAP_SAME, c1.overlap(c2), "Identical circles in Galactic should overlap as Same");
        Circle small = circleRadius(gal, new double[] {1.0, 0.5}, 0.005);
        assertEquals(Region.OVERLAP_INSIDE, small.overlap(c1), "Smaller circle should be inside larger one");
    }

    /** A Box made in ICRS whose System is set to Galactic holds the converted centre. */
    @Test
    void systemChangeBoxMembership() {
        Box bx = boxCorner(sky("ICRS"), new double[] {1.0, 0.5}, new double[] {1.005, 0.505});
        assertTrue(bx.pointInRegion(new double[] {1.0, 0.5}), "centre inside (ICRS)");
        bx.setSystem("GALACTIC");
        double[] gal = galacticCentre();
        assertTrue(bx.pointInRegion(gal), "converted centre should be inside");
        assertFalse(bx.pointInRegion(new double[] {gal[0] + 1.0, gal[1]}), "far point should be outside");
    }

    /** A Circle whose System has changed, written and read back, still holds the converted centre. */
    @Test
    void systemChangeCircleSerialisationRoundtrip() {
        Circle circle = circleRadius(sky("ICRS"), new double[] {1.0, 0.5}, 0.01);
        circle.setSystem("GALACTIC");
        Circle restored = assertInstanceOf(Circle.class, ChannelText.read(ChannelText.dump(circle)));
        double[] gal = galacticCentre();
        assertTrue(restored.pointInRegion(gal), "restored circle should hold the converted centre");
        assertFalse(restored.pointInRegion(new double[] {gal[0] + 1.0, gal[1]}), "restored circle rejects far point");
    }

    // ------------------------------------------------------------------
    // Circles on the SkyFrame of a FITS header
    // ------------------------------------------------------------------

    /** The FrameSet of a TAN header, whose current Frame is a SkyFrame. */
    private static FrameSet tanFrameSet() {
        FitsChan fc = new FitsChan();
        for (String card : new String[] {
            "NAXIS   =                    2",
            "NAXIS1  =                  200",
            "NAXIS2  =                  200",
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
            "CRPIX1  =                  100",
            "CRPIX2  =                  100",
            "CRVAL1  =          71.61972400",
            "CRVAL2  =         -35.22878700",
            "CDELT1  = -5.5555555555555E-04",
            "CDELT2  =  5.5555555555555E-04",
            "CROTA2  =              0.00000"
        }) {
            fc.putFits(card, false);
        }
        fc.setCard(1);
        return (FrameSet) fc.read();
    }

    /**
     * {@code checkCircle}, Circle Errors AA1 to AA4: C's bounds of a Circle of radius 0.01 at
     * latitude 1.0, with C's tolerance. Errors AA5 to AA7, the disc, need {@code
     * astGetRegionDisc}, which jniast lacks. C's values come from {@code testregions.c}, whose
     * header has another reference point and scale than this one (the Rust test's), but the Circle
     * is defined in the header's ICRS SkyFrame, and its bounds depend on that alone.
     */
    @Test
    void fitsChanSkyCircleBounds() {
        Circle circle = circleRadius(tanFrameSet(), new double[] {0.0, 1.0}, 0.01);
        double[][] b = circle.getRegionBounds();
        assertEquals(-0.01850666061475259, b[0][0], 1.0e-6, "Circle: Error AA1");
        assertEquals(0.9900000002235173, b[0][1], 1.0e-6, "Circle: Error AA2");
        assertEquals(0.01850666061475276, b[1][0], 1.0e-6, "Circle: Error AA3");
        assertEquals(1.009994987166073, b[1][1], 1.0e-6, "Circle: Error AA4");
    }

    /** {@code checkCircle}: points just inside and outside a Circle in a FrameSet's SkyFrame. */
    @Test
    void fitsChanSkyCircleMembership() {
        checkSkyCircleRadius(tanFrameSet(), SKY_EDGE, 0.999, 1.001, "fitschan membership");
    }

    /**
     * {@code checkCircle}, the second set of Circle Errors AA5 to AA8: C's bounds of a Circle of
     * radius 0.01 near the pole, with C's tolerance. As for {@link #fitsChanSkyCircleBounds}, C's
     * header differs from this one, but the bounds depend only on its ICRS SkyFrame.
     */
    @Test
    void fitsChanSkyCircleNearPoleBounds() {
        Circle circle = circleRadius(tanFrameSet(), new double[] {0.0, 1.57}, 0.01);
        double[][] b = circle.getRegionBounds();
        assertEquals(0.0, b[0][0], 1.0e-6, "Circle: Error AA5");
        assertEquals(1.560000052675599, b[0][1], 1.0e-6, "Circle: Error AA6");
        assertEquals(6.283185307179586, b[1][0], 1.0e-6, "Circle: Error AA7");
        assertEquals(1.5707963267948966, b[1][1], 1.0e-6, "Circle: Error AA8");
    }

    // ------------------------------------------------------------------
    // Permuted and picked axes
    // ------------------------------------------------------------------

    /** A Box on a Galactic SkyFrame whose axes are swapped holds the point given in the new order. */
    @Test
    void permAxesBoxSwapAndTransform() {
        Box box1 = boxCorner(sky("GALACTIC"), new double[] {1.25, 0.75}, new double[] {1.5, 0.5});
        assertNotEquals(BAD, tranRows(box1, true, new double[] {1.3, 0.7})[0][0], "inside before perm");
        box1.permAxes(new int[] {2, 1});
        assertNotEquals(BAD, tranRows(box1, true, new double[] {0.7, 1.3})[0][0], "inside after perm");
        assertEquals(BAD, tranRows(box1, true, new double[] {1.3, 0.7})[0][0], "old order outside after perm");
    }

    @Test
    void permAxesDoubleSwapIsIdentity() {
        Box box1 = boxCorner(sky("ICRS"), new double[] {1.0, 0.5}, new double[] {1.2, 0.7});
        String before = box1.format(1, 0.25);
        box1.permAxes(new int[] {2, 1});
        box1.permAxes(new int[] {2, 1});
        assertEquals(before, box1.format(1, 0.25), "double permutation should be identity");
        assertNotEquals(BAD, tranRows(box1, true, new double[] {1.1, 0.6})[0][0], "still inside");
    }

    /** After the axes of an FK5 Box are swapped, each axis is formatted as the other was. */
    @Test
    void permAxesFormatChangesAxisType() {
        Box box1 = boxCorner(sky("FK5"), new double[] {1.0, 0.5}, new double[] {1.2, 0.7});
        String fmt1 = box1.format(1, 1.0);
        String fmt2 = box1.format(2, 1.0);
        box1.permAxes(new int[] {2, 1});
        assertEquals(fmt2, box1.format(1, 1.0), "axis 1 formats as axis 2 did");
        assertEquals(fmt1, box1.format(2, 1.0), "axis 2 formats as axis 1 did");
    }

    @Test
    void permAxesBasicFrameBox() {
        Frame frame = new Frame(3);
        frame.setLabel(1, "X");
        frame.setLabel(2, "Y");
        frame.setLabel(3, "Z");
        Box box1 = boxCorner(frame, new double[] {5.0, 10.0, 15.0}, new double[] {7.0, 12.0, 17.0});
        double[] p = {6.0, 11.0, 16.0};
        assertNotEquals(BAD, tranRows(box1, true, p)[0][0], "should be inside");
        box1.permAxes(new int[] {2, 3, 1});
        assertNotEquals(BAD, tranRows(box1, true, new double[] {11.0, 16.0, 6.0})[0][0], "permuted inside");
        assertEquals(BAD, tranRows(box1, true, p)[0][0], "old order outside after perm");
    }

    @Test
    void permAxesCirclePreservesMembership() {
        Circle circle = circleRadius(sky("GALACTIC"), new double[] {0.5, 0.3}, 0.1);
        assertTrue(circle.pointInRegion(new double[] {0.52, 0.31}));
        circle.permAxes(new int[] {2, 1});
        assertTrue(circle.pointInRegion(new double[] {0.31, 0.52}));
        assertFalse(circle.pointInRegion(new double[] {0.52, 0.31}));
    }

    @Test
    void pickAxesSkyFrameOneAxis() {
        Mapping[] map = new Mapping[1];
        Frame picked = sky("FK4").pickAxes(1, new int[] {1}, map);
        assertEquals(1, picked.getNaxes());
        assertEquals(2, map[0].getNin());
        assertEquals(1, map[0].getNout());
        assertEquals(1.5, tranRows(map[0], true, new double[] {1.5, 0.7})[0][0], 1e-10);
    }

    @Test
    void pickAxesBasicFrameSubset() {
        Mapping[] map = new Mapping[1];
        Frame picked = new Frame(3).pickAxes(2, new int[] {1, 3}, map);
        assertEquals(2, picked.getNaxes());
        assertEquals(3, map[0].getNin());
        assertEquals(2, map[0].getNout());
        double[] fwd = tranRows(map[0], true, new double[] {10.0, 20.0, 30.0})[0];
        assertEquals(10.0, fwd[0], 1e-10);
        assertEquals(30.0, fwd[1], 1e-10);
        double[] inv = tranRows(map[0], false, new double[] {10.0, 30.0})[0];
        assertEquals(10.0, inv[0], 1e-10);
        assertEquals(BAD, inv[1]);
        assertEquals(30.0, inv[2], 1e-10);
    }

    /** Both axes of a Circle picked in swapped order give a Circle (region.c {@code RegBasePick}). */
    @Test
    void pickAxesAllAxesPermutedPreservesRegionClass() {
        Circle circle = circleRadius(new Frame(2), new double[] {3.0, 5.0}, 2.0);
        Frame picked = circle.pickAxes(2, new int[] {2, 1}, null);
        assertInstanceOf(Circle.class, picked, "permuted all-axes pick must stay a Circle");
        assertEquals(2, picked.getNaxes());
    }

    /** All three axes of an AND of 3-D Boxes picked in reverse order give a CmpRegion (cmpregion.c). */
    @Test
    void pickAxesCmpRegionSubsetKeepsCmpRegion() {
        Frame frm = new Frame(3);
        Box a = boxCorner(frm, new double[] {0.0, 0.0, 0.0}, new double[] {10.0, 10.0, 10.0});
        Box b = boxCorner(frm, new double[] {2.0, 2.0, 2.0}, new double[] {8.0, 8.0, 8.0});
        CmpRegion cmp = new CmpRegion(a, b, CmpRegion.AST__AND);
        Frame picked = cmp.pickAxes(3, new int[] {3, 2, 1}, null);
        assertInstanceOf(CmpRegion.class, picked, "permuted all-axes pick of a CmpRegion must stay a CmpRegion");
        assertEquals(3, picked.getNaxes());
    }

    // ------------------------------------------------------------------
    // Uncertainty: set (astSetUnc) and default (astGetDefUnc)
    // ------------------------------------------------------------------

    /** The Box about the origin with corner (10, 10), as the fixture generator makes it. */
    private static Box fixtureBox() {
        return boxCorner(new Frame(2), new double[] {0.0, 0.0}, new double[] {10.0, 10.0});
    }

    /**
     * An uncertainty in the Region's own kind of Frame is stored without its FrameSet and
     * recentred: the dump is C's {@code setunc_unitmap_regionfs_omitted.map}.
     */
    @Test
    void setUncertaintyUnitmapClearsRegionFs() {
        Box region = fixtureBox();
        region.setUnc(boxCorner(new Frame(2), new double[] {-0.5, -0.5}, new double[] {0.5, 0.5}));
        assertEquals(Fixtures.read("simplify/setunc_unitmap_regionfs_omitted.map"), ChannelText.dump(region));
    }

    /** A negated uncertainty is negated again before it is stored: C's {@code setunc_unbounded_negated.map}. */
    @Test
    void setUncertaintyNegatesUnbounded() {
        Box region = fixtureBox();
        Box unc = boxCorner(new Frame(2), new double[] {-0.5, -0.5}, new double[] {0.5, 0.5});
        unc.negate();
        region.setUnc(unc);
        assertEquals(Fixtures.read("simplify/setunc_unbounded_negated.map"), ChannelText.dump(region));
    }

    /** The default uncertainty of {@code region} as {@code astGetUnc} returns it, written. */
    private static String defUnc(Region region) {
        return ChannelText.dump(region.getUnc(true));
    }

    /** interval.c {@code GetDefUnc} for an Interval half-open on axis 2. */
    @Test
    void getDefUncIntervalUnbounded() {
        Interval iv = interval(new Frame(2), new double[] {5.0, 0.0}, new double[] {8.0, BAD});
        assertEquals(Fixtures.read("simplify/defunc_interval_unbounded.map"), defUnc(iv));
    }

    /** nullregion.c {@code GetDefUnc}: a Circle of zero radius at the origin. */
    @Test
    void getDefUncNullRegionIsZeroCircle() {
        assertEquals(Fixtures.read("simplify/defunc_nullregion_2d.map"), defUnc(new NullRegion(new Frame(2), null)));
    }

    /** prism.c {@code GetDefUnc}: a Prism of the components' default uncertainties. */
    @Test
    void getDefUncPrismCombinesComponents() {
        Box bx = boxCorner(new Frame(2), new double[] {0.0, 0.0}, new double[] {4.0, 6.0});
        Prism prism = new Prism(bx, interval1(1.0, 9.0));
        assertEquals(Fixtures.read("simplify/defunc_prism_box_interval.map"), defUnc(prism));
    }

    /** cmpregion.c {@code GetDefUnc}: the uncertainty set on the first component. */
    @Test
    void getDefUncCmpRegionUsesComponentUncertainty() {
        Box reg1 = fixtureBox();
        reg1.setUnc(boxCorner(new Frame(2), new double[] {-0.25, -0.25}, new double[] {0.25, 0.25}));
        Box reg2 = boxCorner(new Frame(2), new double[] {5.0, 5.0}, new double[] {15.0, 15.0});
        CmpRegion cmp = new CmpRegion(reg1, reg2, CmpRegion.AST__AND);
        assertEquals(Fixtures.read("simplify/defunc_cmpregion_component_unc.map"), defUnc(cmp));
    }

    /**
     * An axis unbounded above gets a default uncertainty of no width at zero, both in interval.c's
     * {@code GetDefUnc} and in region.c's, which a CmpRegion whose components have no uncertainty
     * of their own uses: the upper bound is +DBL_MAX, not the bad value.
     */
    @Test
    void anAxisUnboundedAboveGetsAZeroWidthDefaultUncertainty() {
        Interval iv = interval(new Frame(1), new double[] {0.0}, new double[] {BAD});
        double[] lbnd = new double[1];
        double[] ubnd = new double[1];
        iv.regBaseBox2(lbnd, ubnd);
        assertEquals(0.0, lbnd[0]);
        assertEquals(Double.MAX_VALUE, ubnd[0]);
        Region[] uncs = {
            iv.getDefUnc(),
            new CmpRegion(iv, interval(new Frame(1), new double[] {0.0}, new double[] {BAD}), CmpRegion.AST__OR)
                    .getDefUnc()
        };
        for (Region unc : uncs) {
            double[] ul = new double[1];
            double[] uu = new double[1];
            unc.regBaseBox2(ul, uu);
            assertArrayEquals(new double[] {0.0, 0.0}, new double[] {ul[0], uu[0]}, "zero-width uncertainty");
        }
    }

    /**
     * {@code checkDefaultUncAtOrigin}: on an axis where a PointList's bounding box is a point at
     * zero, and an Interval's limits are both zero, the default uncertainty keeps a width of
     * 1.0E-12, so the PointList holds its own points.
     */
    @Test
    void theDefaultUncertaintyKeepsANonZeroWidthAtTheOrigin() {
        PointList pl = new PointList(new Frame(3), 2, new double[][] {{5.0, 5.0}, {0.0, 0.0}, {3.0, 4.0}}, null);
        assertTrue(pl.pointInRegion(new double[] {5.0, 0.0, 3.0}), "a PointList must contain its own point");
        double[][] b = pl.getUncFrm(FrameSet.AST__BASE).getRegionBounds();
        assertTrue(b[1][1] > b[0][1], "non-zero width on an axis constant at zero");
        Interval iv = interval(new Frame(2), new double[] {0.0, 1.0}, new double[] {0.0, BAD});
        double[][] ib = iv.getUncFrm(FrameSet.AST__BASE).getRegionBounds();
        assertTrue(ib[1][0] > ib[0][0], "an Interval's default uncertainty keeps a non-zero width at the origin");
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * A Prism of two bounded 1-D Intervals simplifies to one 2-D Region (the Intervals merge, and
     * the Interval, being bounded, is a Box), and the uncertainty set on the Prism is set on it
     * (prism.c {@code Simplify}).
     */
    @Test
    void prismSimplifyCarriesExplicitUncertainty() {
        Interval a = new Interval(new Frame(1), new double[] {0.0}, new double[] {10.0}, null);
        Interval b = new Interval(new Frame(1), new double[] {0.0}, new double[] {20.0}, null);
        Box unc = new Box(new Frame(2), 0, new double[] {0.0, 0.0}, new double[] {0.1, 0.2}, null);
        Prism prism = new Prism(a, b);
        prism.setUnc(unc);
        Region simplified = (Region) prism.simplify();
        assertTrue(simplified instanceof Box || simplified instanceof Interval, "a single 2-D Interval or Box");
        assertEquals(2, simplified.getNaxes(), "collapsed Region should be 2-D");
        Region carried = simplified.getUnc(false);
        assertNotNull(carried, "explicit uncertainty must be carried forward");
        double[][] bounds = carried.getRegionBounds();
        assertEquals(2, bounds[0].length, "carried uncertainty should be 2-D");
        assertEquals(0.2, bounds[1][0] - bounds[0][0], 1e-9, "axis-0 uncertainty width");
        assertEquals(0.4, bounds[1][1] - bounds[0][1], 1e-9, "axis-1 uncertainty width");
    }

    /**
     * The fixture {@code simplify/<name>.map} simplified and written matches C's {@code
     * <name>.simp}, numbers to within 4 units in the last place, the comparison {@code
     * SimplifyRegressionTest} makes of the same rows of {@code cases.txt}: the fits that Circle and
     * Ellipse simplification make pass through {@code java.lang.Math}.
     */
    private static void assertSimplifiesAsC(String name) {
        String want = SerialisationCorpusTest.stripLeadingComments(Fixtures.read("simplify/" + name + ".simp"));
        Mapping input = (Mapping) ChannelText.read(Fixtures.read("simplify/" + name + ".map"));
        Mapping simplified = input.simplify();
        String got = ChannelText.dump(simplified);
        assertTrue(CValues.nativeStringsEquivalent(got, want, 4), () -> name + ": got\n" + got + "want\n" + want);
    }

    /** region.c {@code Simplify}: a linearly mapped uncertainty is stable and is kept. */
    @Test
    void simplifyStabilityBoxLinearUnc() {
        assertSimplifiesAsC("simpstab_box_linear_unc");
    }

    /** region.c {@code Simplify}: an uncertainty mapped through a squaring MathMap is kept. */
    @Test
    void simplifyStabilityBoxNonlinearUnc() {
        assertSimplifiesAsC("simpstab_box_nonlinear_unc");
    }

    /** region.c {@code Simplify}: an uncertainty of a Region mapped through a PermMap dropping an axis. */
    @Test
    void simplifyStabilityPermmapSliceUnc() {
        assertSimplifiesAsC("simpstab_permmap_slice_unc");
    }

    /** circle.c {@code Simplify}: a Circle mapped through an isotropic ZoomMap stays as it is. */
    @Test
    void simplifyCircleConformalKeepsCircle() {
        assertSimplifiesAsC("cefit_circle_conformal");
    }

    /** circle.c {@code Simplify}: a Circle mapped through an anisotropic MatrixMap becomes an Ellipse. */
    @Test
    void simplifyCircleToEllipse() {
        assertSimplifiesAsC("cefit_circle_to_ellipse");
    }

    /** ellipse.c {@code Simplify}: an Ellipse mapped to a circular boundary becomes a Circle. */
    @Test
    void simplifyEllipseToCircle() {
        assertSimplifiesAsC("cefit_ellipse_to_circle");
    }

    /** circle.c {@code Simplify}: a Circle mapped through a non-linear MathMap stays as it is. */
    @Test
    void simplifyCircleNonconformalKeepsCircle() {
        assertSimplifiesAsC("cefit_circle_nonconformal_keep");
    }

    /** A Circle mapped through a PermMap swapping its axes stays as it is. */
    @Test
    void simplifyRegbasepickCirclePermaxes() {
        assertSimplifiesAsC("regbasepick_circle_permaxes");
    }

    /**
     * Two 1-D Boxes of MeshSize 30 and 50 merged in parallel give a 2-D Box whose MeshSize is the
     * 2-D default, 200, scaled by 30/2 and 50/2.
     */
    @Test
    void parallelMergeScalesMeshSize() {
        Box b1 = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {10.0}, null);
        b1.setMeshSize(30);
        Box b2 = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {10.0}, null);
        b2.setMeshSize(50);
        Box region = assertInstanceOf(Box.class, new CmpMap(b1, b2, false).simplify());
        assertTrue(region.testMeshSize(), "merged MeshSize should be set");
        assertEquals(75000, region.getMeshSize());
    }

    /** Two 1-D Boxes of FillFactor 0.5 and 0.8 merged in parallel give a 2-D Box of FillFactor 0.4. */
    @Test
    void parallelMergeScalesFillFactor() {
        Box b1 = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {10.0}, null);
        b1.setFillFactor(0.5);
        Box b2 = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {10.0}, null);
        b2.setFillFactor(0.8);
        Box region = assertInstanceOf(Box.class, new CmpMap(b1, b2, false).simplify());
        assertTrue(region.testFillFactor(), "merged FillFactor should be set");
        assertEquals(0.4, region.getFillFactor(), 1e-12);
    }

    /**
     * A Circle in ICRS whose System is changed to Galactic, simplified, still holds its centre,
     * unchanged, and not a point far from it.
     */
    @Test
    void systemChangeCircleSimplify() {
        Circle circle = new Circle(sky("ICRS"), new double[] {1.0, 0.5}, 0.01, null);
        circle.setSystem("GALACTIC");
        double[][] gal = toGalactic(new double[][] {{1.0}, {0.5}});
        Mapping simplified = circle.simplify();
        double[][] result = simplified.transform(gal, 1, true);
        assertNotEquals(BAD, result[0][0], "Simplified region should pass centre through (inside)");
        assertEquals(gal[0][0], result[0][0], 1.0e-10, "Simplified region should preserve the interior point");
        double[][] far = simplified.transform(new double[][] {{gal[0][0] + 1.0}, {gal[1][0]}}, 1, true);
        assertEquals(BAD, far[0][0], "Simplified region should mask exterior points");
    }

    /**
     * A Circle in ICRS whose System is changed to Galactic, simplified, agrees with the Circle on
     * which of four points are inside it, and gives the same values for those that are.
     */
    @Test
    void systemChangeSimplifiedTransformConsistent() {
        Circle circle = new Circle(sky("ICRS"), new double[] {1.0, 0.5}, 0.02, null);
        circle.setSystem("GALACTIC");
        double[][] gal = toGalactic(new double[][] {{1.0, 1.005, 1.05, 2.0}, {0.5, 0.5, 0.5, 1.0}});
        double[][] orig = circle.transform(gal, 4, true);
        double[][] simp = circle.simplify().transform(gal, 4, true);
        for (int i = 0; i < 4; i++) {
            boolean origBad = orig[0][i] == BAD;
            assertEquals(
                    origBad, simp[0][i] == BAD, "Point " + i + ": original and simplified should agree on membership");
            if (!origBad) {
                assertEquals(
                        orig[0][i], simp[0][i], 1.0e-10, "Point " + i + ": interior coordinate values should match");
            }
        }
    }

    // ------------------------------------------------------------------
    // Removing Regions (testregions.c checkRemoveRegions)
    // ------------------------------------------------------------------

    /**
     * A Circle clips a point outside it, and the Mapping of the ICRS to Galactic FrameSet, with its
     * Regions removed, passes that point. This translates Rust's {@code remove_regions_from_frameset},
     * whose FrameSet from {@code convert} holds no Region, so nothing is removed. The C-valued cases
     * that remove a Region a FrameSet holds are {@code RegionsCTest.checkRemoveRegions} (a Circle as
     * the current Frame) and the {@code RemoveRegionsTest} cases {@code frameset_region_frame} (a Box
     * as a Frame), {@code frameset_region_in_cmpmap} (a Circle in a CmpMap) and {@code
     * frameset_region_map} (a Box as a Mapping).
     */
    @Test
    void removeRegionsFromFrameSet() {
        SkyFrame sf1 = sky("ICRS");
        Circle circle = circleRadius(sf1, new double[] {0.0, 0.0}, 0.001);
        assertNotEquals(BAD, tranRows(circle, true, new double[] {0.0, 0.0})[0][0], "centre should be inside");
        assertEquals(BAD, tranRows(circle, true, new double[] {0.01, 0.01})[0][0], "distant point should be clipped");

        FrameSet fs = sf1.convert(sky("GALACTIC"), "");
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        assertNotEquals(
                BAD, tranRows(map, true, new double[] {0.0, 0.0})[0][0], "origin should transform through FrameSet");

        Mapping clean = map.removeRegions();
        assertNotEquals(
                BAD,
                tranRows(clean, true, new double[] {0.01, 0.01})[0][0],
                "remove_regions should eliminate clipping");
    }

    /** Removing Regions from a Mapping that holds none gives an equivalent Mapping. */
    @Test
    void removeRegionsUnitPassthrough() {
        FrameSet fs = new FrameSet(new Frame(2));
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping clean = map.removeRegions();
        assertEquals(2, clean.getNin());
        assertEquals(2, clean.getNout());
        double[] out = tranRows(clean, true, new double[] {3.0, 4.0})[0];
        assertEquals(3.0, out[0], 1e-10);
        assertEquals(4.0, out[1], 1e-10);
    }

    // ------------------------------------------------------------------
    // A Region as the target of findFrame
    // ------------------------------------------------------------------

    /** A Region target is searched as its Frame: neither Frame of the result is a Circle. */
    @Test
    void findFrameSearchesARegionTargetAsItsFrame() {
        Circle circle = circleRadius(new Frame(2), new double[] {0.0, 0.0}, 1.0);
        FrameSet found = circle.findFrame(new Frame(2), "");
        assertNotNull(found, "a 2-dimensional system");
        assertFalse(found.getFrame(FrameSet.AST__BASE) instanceof Circle);
        assertFalse(found.getFrame(FrameSet.AST__CURRENT) instanceof Circle);
    }

    // ------------------------------------------------------------------
    // Attribute values in a dump
    // ------------------------------------------------------------------

    /** The dump of {@code obj}, as the probe's {@code astShow} writes it. */
    private static String show(AstObject obj) {
        StringBuilder sb = new StringBuilder();
        for (String l : ChannelText.dump(obj).split("\n", -1)) {
            if (!l.isEmpty()) {
                sb.append(ProbeOutput.trimLines(l)).append('\n');
            }
        }
        return sb.toString();
    }

    /** A Box with Closed, MeshSize and FillFactor set writes the values set, as C's does. */
    @Test
    void boxDumpsTheValuesSet() {
        Box box = new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {1.0, 2.0}, null);
        box.set("Closed=0,MeshSize=50,FillFactor=0.5");
        assertEquals(DUMP_PROBE.get("box"), show(box));
    }

    /**
     * A CmpRegion with Closed and MeshSize set writes them, and so do its components, which its
     * setters give them, as C's does.
     */
    @Test
    void cmpRegionDumpsTheValuesSet() {
        Box a = new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {2.0, 2.0}, null);
        Box b = new Box(new Frame(2), 1, new double[] {1.0, 1.0}, new double[] {3.0, 3.0}, null);
        CmpRegion cmp = new CmpRegion(a, b, CmpRegion.AST__AND);
        cmp.set("Closed=0,MeshSize=20");
        assertEquals(DUMP_PROBE.get("cmpregion"), show(cmp));
    }

    /**
     * A PointList's Closed attribute is always the opposite of Negated, but its dump writes the
     * Closed value set on it, as C's region.c {@code Dump} reads it through its own {@code
     * TestClosed} and {@code GetClosed}.
     */
    @Test
    void pointListDumpsTheClosedValueSet() {
        PointList pl = new PointList(new Frame(2), 2, new double[][] {{0.0, 1.0}, {0.0, 2.0}}, null);
        pl.set("Closed=0");
        assertEquals(DUMP_PROBE.get("pointlist"), "Closed " + pl.getI("Closed") + "\n" + show(pl));
        pl.set("Negated=1,Closed=1");
        assertEquals(DUMP_PROBE.get("pointlist_negated"), "Closed " + pl.getI("Closed") + "\n" + show(pl));
    }
}
