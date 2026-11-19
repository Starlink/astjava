package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.CompoundProbe.BAD;
import static uk.ac.starlink.ast.CompoundProbe.bounded;
import static uk.ac.starlink.ast.CompoundProbe.bounds;
import static uk.ac.starlink.ast.CompoundProbe.box2;
import static uk.ac.starlink.ast.CompoundProbe.boxc;
import static uk.ac.starlink.ast.CompoundProbe.check;
import static uk.ac.starlink.ast.CompoundProbe.checkClose;
import static uk.ac.starlink.ast.CompoundProbe.circle2;
import static uk.ac.starlink.ast.CompoundProbe.cmp;
import static uk.ac.starlink.ast.CompoundProbe.decompose;
import static uk.ac.starlink.ast.CompoundProbe.fill;
import static uk.ac.starlink.ast.CompoundProbe.maplist;
import static uk.ac.starlink.ast.CompoundProbe.mask;
import static uk.ac.starlink.ast.CompoundProbe.mesh;
import static uk.ac.starlink.ast.CompoundProbe.naxes;
import static uk.ac.starlink.ast.CompoundProbe.negated;
import static uk.ac.starlink.ast.CompoundProbe.negatedCopy;
import static uk.ac.starlink.ast.CompoundProbe.pick;
import static uk.ac.starlink.ast.CompoundProbe.pins;
import static uk.ac.starlink.ast.CompoundProbe.points;
import static uk.ac.starlink.ast.CompoundProbe.tran;
import static uk.ac.starlink.ast.CompoundProbe.tranValues;
import static uk.ac.starlink.ast.CompoundProbe.unc;
import static uk.ac.starlink.ast.RegionProbeText.error;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_cmpregion.rs}, and the CmpRegion cases of {@code
 * probes/region_compound_probe.c}: the constructor and its errors, membership (AND, OR, negated,
 * nested, and with bad values), Bounded (including C's report that an AND of two nested unbounded
 * Regions is bounded), bounds, the boundary mesh and grid, the uncertainty, masks, the boundary
 * test with a PointList component, decomposition, picking axes and the dump, against C.
 *
 * <p>Values from Boxes, Intervals, PointLists and NullRegions in a plain Frame come from arithmetic
 * alone and are compared exactly. The boundary meshes of Circles are {@code c + r cos(a)} and
 * {@code c + r sin(a)}: {@code java.lang.Math} may give {@code cos} and {@code sin} one unit in the
 * last place away from C's libm (1.1e-16 relative), so for radii up to 5 about centres up to 5 a
 * mesh value may differ by up to {@code 5 * 2.2e-16 + ulp(10) = 2.9e-15}; they are compared to
 * {@code 1e-13}. The membership of those mesh points in the other component, which decides the
 * points kept, is clear of rounding: {@link #circleMeshesAreClearOfTheOtherCircle} shows each
 * point at least {@code 1e-6} from the other boundary.
 *
 * <p>The XOR, the protected {@code astCmpRegionList}, the compound attributes and the sky AND are
 * {@code RegionCmpRegionOracleTest}, {@code RegionCmpListOracleTest} and {@code
 * RegionCompoundAttrOracleTest}.
 */
class CmpRegionTest {

    /** The bound on the difference of a Circle-derived mesh value from C's (see the class comment). */
    private static final double MESH_TOL = 1.0e-13;

    private static final double[] ZERO2 = {0.0, 0.0};

    private static final double[] TWO2 = {2.0, 2.0};

    private static final double[] THREE2 = {3.0, 3.0};

    // ---- Translation of test_cmpregion.rs ----

    @Test
    void constructAndRegion() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND);
        assertEquals(2, c.getNaxes());
        assertEquals(CmpRegion.AST__AND, c.getOperator());
    }

    @Test
    void constructOrRegion() {
        CmpRegion c = cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR);
        assertEquals(2, c.getNaxes());
        assertEquals(CmpRegion.AST__OR, c.getOperator());
    }

    @Test
    void constructMismatchedNaxesFails() {
        Circle c3 = new Circle(new Frame(3), 1, new double[3], new double[] {1.0}, null);
        AstException e = assertThrows(AstException.class, () -> cmp(circle2(0.0, 0.0, 1.0), c3, CmpRegion.AST__AND));
        assertEquals(AstException.AST__INTRD, e.getStatus());
    }

    @Test
    void andCircleAndBox() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND);
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {2.0, 2.0}));
        assertFalse(c.pointInRegion(new double[] {4.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {-4.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {6.0, 0.0}));
    }

    @Test
    void orTwoSeparatedCircles() {
        CmpRegion c = cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR);
        assertTrue(c.pointInRegion(new double[] {-5.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {-4.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {5.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {6.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {0.0, 10.0}));
    }

    @Test
    void negatedCmpRegion() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND);
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {4.0, 0.0}));
        c.negate();
        assertTrue(c.getNegated());
        assertFalse(c.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {4.0, 0.0}));
    }

    @Test
    void negatedCopyOfOr() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 3.0), circle2(0.0, 0.0, 1.0), CmpRegion.AST__OR);
        CmpRegion neg = negatedCopy(c);
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(neg.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {10.0, 10.0}));
        assertTrue(neg.pointInRegion(new double[] {10.0, 10.0}));
    }

    @Test
    void transformForwardMasksOutside() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        double[][] out = c.transform(new double[][] {{1.0, 2.5, 5.0}, {1.0, 0.0, 5.0}}, 3, true);
        assertEquals(1.0, out[0][0]);
        assertEquals(1.0, out[1][0]);
        for (int i = 1; i < 3; i++) {
            assertEquals(BAD, out[0][i]);
            assertEquals(BAD, out[1][i]);
        }
    }

    @Test
    void transformInverseMasksAsForward() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        double[][] in = {{10.0, 0.0}, {10.0, 0.0}};
        double[][] inv = c.transform(in, 2, false);
        double[][] fwd = c.transform(in, 2, true);
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                assertEquals(fwd[j][i], inv[j][i]);
            }
        }
        assertEquals(BAD, inv[0][0]);
        assertEquals(BAD, inv[1][0]);
    }

    @Test
    void andBoundedIfEitherBounded() {
        Circle unbounded = negatedCopy(circle2(10.0, 10.0, 1.0));
        assertFalse(unbounded.getBounded());
        assertTrue(cmp(circle2(0.0, 0.0, 3.0), unbounded, CmpRegion.AST__AND).getBounded());
    }

    @Test
    void nullRegionIsBounded() {
        NullRegion n = new NullRegion(new Frame(2), null);
        assertTrue(n.getBounded());
        n.negate();
        assertFalse(n.getBounded());
    }

    @Test
    void andUnboundedIfBothUnbounded() {
        Circle a = negatedCopy(circle2(0.0, 0.0, 1.0));
        Circle b = negatedCopy(circle2(10.0, 10.0, 1.0));
        assertFalse(cmp(a, b, CmpRegion.AST__AND).getBounded());
    }

    @Test
    void orBoundedOnlyIfBothBounded() {
        Circle circle = circle2(0.0, 0.0, 3.0);
        Circle unbounded = negatedCopy(circle2(10.0, 10.0, 1.0));
        assertFalse(cmp(circle, unbounded, CmpRegion.AST__OR).getBounded());
        assertTrue(cmp(circle, circle2(1.0, 1.0, 2.0), CmpRegion.AST__OR).getBounded());
    }

    @Test
    void andBoundingBoxIsIntersection() {
        double[][] b = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND)
                .getRegionBounds();
        assertEquals(-3.0, b[0][0], 1e-10);
        assertEquals(-3.0, b[0][1], 1e-10);
        assertEquals(3.0, b[1][0], 1e-10);
        assertEquals(3.0, b[1][1], 1e-10);
    }

    @Test
    void orBoundingBoxIsUnion() {
        double[][] b = cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR)
                .getRegionBounds();
        assertEquals(-7.0, b[0][0], 1e-10);
        assertEquals(7.0, b[1][0], 1e-10);
        assertEquals(-2.0, b[0][1], 1e-10);
        assertEquals(2.0, b[1][1], 1e-10);
    }

    @Test
    void badInputPointIsOutside() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND);
        assertFalse(c.pointInRegion(new double[] {BAD, 0.0}));
        assertFalse(c.pointInRegion(new double[] {0.0, BAD}));
    }

    @Test
    void nestedCmpRegion() {
        CmpRegion inner = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        CmpRegion outer = cmp(inner, circle2(10.0, 10.0, 1.0), CmpRegion.AST__OR);
        assertTrue(outer.pointInRegion(new double[] {1.0, 1.0}));
        assertFalse(outer.pointInRegion(new double[] {2.5, 0.0}));
        assertTrue(outer.pointInRegion(new double[] {10.0, 10.0}));
        assertFalse(outer.pointInRegion(new double[] {20.0, 20.0}));
    }

    @Test
    void fromDynBuilder() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 3.0), circle2(0.0, 0.0, 1.0), CmpRegion.AST__AND);
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {2.0, 0.0}));
    }

    @Test
    void andMeshIsNonemptyAndOnBoundary() {
        double[][] mesh = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND)
                .regBaseMesh();
        int n = mesh[0].length;
        assertTrue(n > 0);
        for (int i = 0; i < n; i++) {
            double x = mesh[0][i];
            double y = mesh[1][i];
            assertNotEquals(BAD, x);
            assertNotEquals(BAD, y);
            assertTrue(x * x + y * y <= 25.0 + 1e-6 && Math.abs(x) <= 3.0 + 1e-6 && Math.abs(y) <= 3.0 + 1e-6);
        }
    }

    @Test
    void orMeshIsNonemptyAndOnBoundary() {
        double[][] mesh = cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR)
                .regBaseMesh();
        int n = mesh[0].length;
        assertTrue(n > 0);
        boolean left = false;
        boolean right = false;
        for (int i = 0; i < n; i++) {
            assertNotEquals(BAD, mesh[0][i]);
            assertNotEquals(BAD, mesh[1][i]);
            left |= mesh[0][i] < 0.0;
            right |= mesh[0][i] > 0.0;
        }
        assertTrue(left);
        assertTrue(right);
    }

    @Test
    void andMeshOverlappingCircles() {
        double[][] mesh = cmp(circle2(0.0, -1.0, 3.0), circle2(0.0, 1.0, 3.0), CmpRegion.AST__AND)
                .regBaseMesh();
        int n = mesh[0].length;
        assertTrue(n > 0);
        for (int i = 0; i < n; i++) {
            double x = mesh[0][i];
            double y = mesh[1][i];
            assertTrue(x * x + (y + 1.0) * (y + 1.0) <= 9.0 + 1e-6 && x * x + (y - 1.0) * (y - 1.0) <= 9.0 + 1e-6);
        }
    }

    @Test
    void accessorMethods() {
        CmpRegion c = cmp(circle2(0.0, 0.0, 3.0), circle2(1.0, 1.0, 2.0), CmpRegion.AST__OR);
        assertEquals(2, c.getRegion1().getNaxes());
        assertEquals(2, c.getRegion2().getNaxes());
        assertEquals(2, c.getNaxes());
        assertEquals(2, c.getNin());
        assertEquals(2, c.getNout());
    }

    @Test
    void andMeshFollowsClosedAfterFirstUse() {
        double[] p0 = {1.0, 1.0};
        double[] c1 = {1.0, 0.0};
        double[] p1 = {2.0, 1.0};
        CmpRegion open = cmp(boxc(ZERO2, p0), boxc(c1, p1), CmpRegion.AST__AND);
        open.setClosed(false);
        int openCount = open.regBaseMesh()[0].length;
        CmpRegion warmed = cmp(boxc(ZERO2, p0), boxc(c1, p1), CmpRegion.AST__AND);
        warmed.setClosed(true);
        int closedCount = warmed.regBaseMesh()[0].length;
        assertNotEquals(openCount, closedCount);
        warmed.setClosed(false);
        assertEquals(openCount, warmed.regBaseMesh()[0].length);
    }

    @Test
    void andOfNestedUnboundedRegionsReportsBoundedAsCDoes() {
        Interval a = new Interval(new Frame(2), new double[] {0.0, BAD}, new double[] {BAD, BAD}, null);
        Interval b = new Interval(new Frame(2), new double[] {1.0, BAD}, new double[] {BAD, BAD}, null);
        assertFalse(a.getBounded());
        assertFalse(b.getBounded());
        assertEquals(Region.OVERLAP_OUTSIDE, a.overlap(b));
        CmpRegion and = cmp(a, b, CmpRegion.AST__AND);
        assertTrue(and.getBounded());
        assertTrue(and.pointInRegion(new double[] {1.0e30, 1.0e30}));
    }

    // ---- Against C (probes/region_compound_probe.c) ----

    @Test
    void constructorErrorsMatchC() {
        check("cmp_oper_0", error(() -> cmp(circle2(0.0, 0.0, 5.0), box2(-3.0, -3.0, 3.0, 3.0), 0)));
        check("cmp_oper_4", error(() -> cmp(circle2(0.0, 0.0, 5.0), box2(-3.0, -3.0, 3.0, 3.0), 4)));
        Circle c3 = new Circle(new Frame(3), 1, new double[3], new double[] {1.0}, null);
        check("cmp_naxes", error(() -> cmp(circle2(0.0, 0.0, 1.0), c3, CmpRegion.AST__AND)));
        Frame fa = new Frame(2);
        fa.setDomain("AAA");
        Frame fb = new Frame(2);
        fb.setDomain("BBB");
        Box a = new Box(fa, 1, ZERO2, TWO2, null);
        Box b = new Box(fb, 1, ZERO2, TWO2, null);
        check("cmp_domain", error(() -> cmp(a, b, CmpRegion.AST__AND)));
        check("cmp_xor_naxes", naxes(cmp(a, a, 3)));
    }

    @Test
    void nullArgumentsAreRejected() {
        Box b = box2(0.0, 0.0, 1.0, 1.0);
        NullPointerException e1 =
                assertThrows(NullPointerException.class, () -> new CmpRegion(null, b, CmpRegion.AST__AND));
        assertEquals("region1", e1.getMessage());
        NullPointerException e2 =
                assertThrows(NullPointerException.class, () -> new CmpRegion(b, null, CmpRegion.AST__AND));
        assertEquals("region2", e2.getMessage());
    }

    @Test
    void andOfCircleAndBoxMatchesC() {
        double[] pts = {0.0, 0.0, 2.0, 2.0, 4.0, 0.0, -4.0, 0.0, 6.0, 0.0, BAD, 0.0, 0.0, BAD};
        CmpRegion r = cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND);
        check("and_cb", tran(r, 2, pts) + bounded(r) + bounds(r) + points(r) + unc(r, true) + mesh(r, true) + fill(r));
        r.negate();
        check("and_cb_negated", tran(r, 2, pts) + bounded(r) + bounds(r) + negated(r));
    }

    @Test
    void orOfCirclesMatchesC() {
        CmpRegion r = cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR);
        String text = tran(r, 2, -5.0, 0.0, -4.0, 0.0, 5.0, 0.0, 6.0, 0.0, 0.0, 0.0, 0.0, 10.0)
                + bounded(r)
                + bounds(r)
                + mesh(r, true);
        checkClose("or_cc", text, MESH_TOL);
        CmpRegion n = cmp(circle2(0.0, 0.0, 3.0), circle2(0.0, 0.0, 1.0), CmpRegion.AST__OR);
        String t2 = tran(n, 2, 0.0, 0.0, 10.0, 10.0);
        n.negate();
        check("or_nested_circles_negated", t2 + tran(n, 2, 0.0, 0.0, 10.0, 10.0) + bounded(n));
    }

    @Test
    void transformValuesMatchC() {
        CmpRegion r = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        check(
                "and_transform",
                tranValues(r, 2, true, 1.0, 1.0, 2.5, 0.0, 5.0, 5.0)
                        + tranValues(r, 2, true, 10.0, 10.0, 0.0, 0.0)
                        + tranValues(r, 2, false, 10.0, 10.0, 0.0, 0.0));
    }

    @Test
    void nestedAndConcentricMatchC() {
        CmpRegion a = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        CmpRegion r = cmp(a, circle2(10.0, 10.0, 1.0), CmpRegion.AST__OR);
        check("nested", tran(r, 2, 1.0, 1.0, 2.5, 0.0, 10.0, 10.0, 20.0, 20.0) + bounded(r) + bounds(r));
        CmpRegion c = cmp(circle2(0.0, 0.0, 3.0), circle2(0.0, 0.0, 1.0), CmpRegion.AST__AND);
        check("and_concentric", tran(c, 2, 0.0, 0.0, 2.0, 0.0));
    }

    @Test
    void boundedAndBoundsMatchC() {
        CmpRegion r = cmp(circle2(0.0, 0.0, 3.0), negatedCopy(circle2(10.0, 10.0, 1.0)), CmpRegion.AST__AND);
        check("bounded_and_negated_circle", bounded(r) + bounds(r));
        NullRegion n = new NullRegion(new Frame(2), null);
        String t = bounded(n);
        n.negate();
        check("bounded_null", t + bounded(n));
        r = cmp(negatedCopy(circle2(0.0, 0.0, 1.0)), negatedCopy(circle2(10.0, 10.0, 1.0)), CmpRegion.AST__AND);
        check("bounded_and_two_negated", bounded(r) + bounds(r));
        r = cmp(circle2(0.0, 0.0, 3.0), negatedCopy(circle2(10.0, 10.0, 1.0)), CmpRegion.AST__OR);
        check("bounded_or_negated", bounded(r) + bounds(r));
        r = cmp(circle2(0.0, 0.0, 3.0), circle2(1.0, 1.0, 2.0), CmpRegion.AST__OR);
        check("bounded_or_two", bounded(r) + bounds(r));
        r = cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR);
        check("bounds_or_apart", bounds(r));
    }

    @Test
    void meshOfOverlappingCirclesMatchesC() {
        CmpRegion r = cmp(circle2(0.0, -1.0, 3.0), circle2(0.0, 1.0, 3.0), CmpRegion.AST__AND);
        checkClose("and_overlapping_circles", mesh(r, true) + bounds(r), MESH_TOL);
    }

    /**
     * The mesh points of each Circle of {@link #meshOfOverlappingCirclesMatchesC} and {@link
     * #orOfCirclesMatchesC} are at least {@code 1e-6} from the other Circle's boundary, so which
     * are kept does not depend on rounding.
     */
    @Test
    void circleMeshesAreClearOfTheOtherCircle() {
        assertClear(circle2(0.0, -1.0, 3.0), 0.0, 1.0, 3.0);
        assertClear(circle2(0.0, 1.0, 3.0), 0.0, -1.0, 3.0);
        assertClear(circle2(-5.0, 0.0, 2.0), 5.0, 0.0, 2.0);
        assertClear(circle2(5.0, 0.0, 2.0), -5.0, 0.0, 2.0);
    }

    private static void assertClear(Circle c, double ox, double oy, double r) {
        double[][] m = c.regMesh();
        for (int i = 0; i < m[0].length; i++) {
            double d = Math.hypot(m[0][i] - ox, m[1][i] - oy);
            assertTrue(Math.abs(d - r) > 1.0e-6, "mesh point " + i + " at " + d);
        }
    }

    @Test
    void closedChangesTheMeshAsInC() {
        double[] p0 = {1.0, 1.0};
        double[] c1 = {1.0, 0.0};
        double[] p1 = {2.0, 1.0};
        CmpRegion r = cmp(boxc(ZERO2, p0), boxc(c1, p1), CmpRegion.AST__AND);
        String t = mesh(r, true);
        r.setClosed(false);
        t += mesh(r, true);
        r.setClosed(true);
        check("and_closed", t + mesh(r, true));
    }

    @Test
    void nestedUnboundedRegionsMatchC() {
        Interval a = new Interval(new Frame(2), new double[] {0.0, BAD}, new double[] {BAD, BAD}, null);
        Interval b = new Interval(new Frame(2), new double[] {1.0, BAD}, new double[] {BAD, BAD}, null);
        String t = bounded(a) + bounded(b) + "overlap " + a.overlap(b) + "\n";
        CmpRegion r = cmp(a, b, CmpRegion.AST__AND);
        t += bounded(r) + tran(r, 2, 1.0e30, 1.0e30) + error(r::getRegionBounds);
        check("and_nested_unbounded", t);
    }

    @Test
    void valuesWithBadCoordinatesMatchC() {
        double[] pts = {BAD, 5.0, 1.0, BAD, 1.0, 1.0};
        NullRegion all = new NullRegion(new Frame(2), null);
        all.negate();
        check(
                "or_negated_null_values",
                tranValues(cmp(all, box2(0.0, 0.0, 2.0, 2.0), CmpRegion.AST__OR), 2, true, pts));
        NullRegion all2 = new NullRegion(new Frame(2), null);
        all2.negate();
        check("and_negated_nulls_values", tranValues(cmp(all, all2, CmpRegion.AST__AND), 2, true, pts));
        check("and_null_box_values", tranValues(cmp(all, box2(0.0, 0.0, 2.0, 2.0), CmpRegion.AST__AND), 2, true, pts));
    }

    @Test
    void disjointAndMatchesC() {
        CmpRegion r = cmp(box2(0.0, 0.0, 1.0, 1.0), box2(3.0, 3.0, 4.0, 4.0), CmpRegion.AST__AND);
        check("disjoint_and", bounded(r) + mesh(r, true) + bounds(r) + error(() -> mesh(r, false)));
    }

    @Test
    void uncertaintyMatchesC() {
        CmpRegion r = cmp(box2(0.0, 0.0, 2.0, 2.0), box2(1.0, 1.0, 3.0, 3.0), CmpRegion.AST__OR);
        check("unc_default", unc(r, true) + unc(r, false));
        Circle u = new Circle(new Frame(2), 1, ZERO2, new double[] {0.01}, null);
        Box b = box2(1.0, 1.0, 3.0, 3.0);
        b.setUnc(u);
        r = cmp(box2(0.0, 0.0, 2.0, 2.0), b, CmpRegion.AST__OR);
        check("unc_second", unc(r, true) + unc(r, false));
        Box target = box2(0.0, 0.0, 2.0, 2.0);
        CmpRegion shape = cmp(box2(0.0, 0.0, 0.1, 0.1), box2(0.0, 0.0, 0.2, 0.2), CmpRegion.AST__AND);
        check("unc_cmpregion_shape", error(() -> target.setUnc(shape)));
    }

    @Test
    void masksMatchC() {
        CmpRegion r = cmp(box2(0.5, 0.5, 2.5, 1.5), box2(3.5, 1.5, 4.5, 3.5), CmpRegion.AST__OR);
        check("mask_or_boxes", mask(r, true) + mask(r, false));
        r = cmp(box2(0.5, 0.5, 4.5, 3.5), negatedCopy(box2(1.5, 1.5, 2.5, 2.5)), CmpRegion.AST__AND);
        check("mask_and_negated", mask(r, true) + mask(r, false));
    }

    /**
     * A CmpRegion with a PointList component asks the PointList whether points on the boundary of
     * another Region are on its own boundary (C's {@code astRegPins}); C's values come from the
     * probe linked with pointlist.c's mask allocation corrected.
     */
    @Test
    void pointListComponentMatchesC() {
        PointList pl = new PointList(new Frame(2), 2, new double[][] {{1.0, 2.0}, {1.0, 2.0}}, null);
        CmpRegion r = cmp(pl, box2(0.0, 0.0, 3.0, 3.0), CmpRegion.AST__AND);
        check("ov_pl_and_box_big", CompoundProbe.overlap(r, box2(0.0, 0.0, 5.0, 5.0)));
        check("ov_pl_and_box_small", CompoundProbe.overlap(r, box2(1.5, 1.5, 5.0, 5.0)));
        check("ov_pl_and_box_apart", CompoundProbe.overlap(r, box2(10.0, 10.0, 15.0, 15.0)));
        double[] pinpts = {1.0, 1.0, 2.0, 2.0, 0.5, 0.5};
        double[][] ps = CompoundProbe.columns(2, pinpts);
        check("pl_and_box", tran(r, 2, pinpts) + bounded(r) + bounds(r) + mesh(r, true) + pins(r, ps, null));
        r = cmp(pl, box2(0.0, 0.0, 3.0, 3.0), CmpRegion.AST__OR);
        check("ov_pl_or_box", CompoundProbe.overlap(r, box2(0.0, 0.0, 5.0, 5.0)));
        check("pl_or_box", tran(r, 2, pinpts) + bounded(r) + pins(r, ps, null));
    }

    /**
     * When every point of another Region's mesh is near a point of a PointList component but there
     * are more of them than the PointList has points, pointlist.c {@code RegPins} reads beyond the
     * PointList's points in checking that each of them is near a point given; Java checks every
     * PointList point, which is C's stated intent and gives C's answer (see {@code
     * docs/rust-c-differences.md}). The mesh of the second CmpRegion is the PointList's two points
     * and the corner of the Box that coincides with one of them.
     */
    @Test
    void pointListComponentGivenMorePointsThanItHasMatchesC() {
        PointList pl = new PointList(new Frame(2), 2, new double[][] {{0.0, 1.0}, {0.0, 1.0}}, null);
        CmpRegion a = cmp(pl, box2(-1.0, -1.0, 3.0, 3.0), CmpRegion.AST__AND);
        CmpRegion b = cmp(pl, box2(0.0, 0.0, 2.0, 2.0), CmpRegion.AST__AND);
        check("pl_more_points", mesh(b, true) + "overlap " + a.overlap(b) + "\n");
    }

    @Test
    void overlapsMatchC() {
        check(
                "ov_cmp_box_inside",
                CompoundProbe.overlap(
                        cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND),
                        box2(-10.0, -10.0, 10.0, 10.0)));
        check(
                "ov_cmp_box_partial",
                CompoundProbe.overlap(
                        cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND),
                        box2(0.0, 0.0, 10.0, 10.0)));
        check(
                "ov_cmp_box_none",
                CompoundProbe.overlap(
                        cmp(circle2(0.0, 0.0, 5.0), boxc(ZERO2, THREE2), CmpRegion.AST__AND),
                        box2(20.0, 20.0, 30.0, 30.0)));
        check(
                "ov_or_circle",
                CompoundProbe.overlap(
                        cmp(circle2(-5.0, 0.0, 2.0), circle2(5.0, 0.0, 2.0), CmpRegion.AST__OR),
                        circle2(-5.0, 0.0, 1.0)));
        CmpRegion a = cmp(box2(0.0, 0.0, 2.0, 2.0), box2(1.0, 1.0, 3.0, 3.0), CmpRegion.AST__OR);
        check("ov_cmp_same", CompoundProbe.overlap(a, (Region) a.copy()));
        check("ov_cmp_negation", CompoundProbe.overlap(a, negatedCopy(a)));
    }

    @Test
    void pickAndDecomposeMatchC() {
        CmpRegion r = cmp(box2(0.0, 0.0, 2.0, 2.0), box2(1.0, 1.0, 3.0, 3.0), CmpRegion.AST__AND);
        check("cmp_pick", pick(r, 1));
        check("cmp_decompose", decompose(r) + maplist(r));
    }

    @Test
    void decomposeGivesTheComponentsThemselves() {
        CmpRegion r = cmp(box2(0.0, 0.0, 2.0, 2.0), box2(1.0, 1.0, 3.0, 3.0), CmpRegion.AST__AND);
        Mapping[] parts = r.decompose(null, null);
        assertSame(r.getRegion1(), parts[0]);
        assertSame(r.getRegion2(), parts[1]);
    }

    @Test
    void componentsAreCopies() {
        Box b1 = box2(0.0, 0.0, 2.0, 2.0);
        Box b2 = box2(1.0, 1.0, 3.0, 3.0);
        CmpRegion r = cmp(b1, b2, CmpRegion.AST__AND);
        b1.negate();
        assertFalse(r.getRegion1().getNegated());
        assertTrue(r.pointInRegion(new double[] {1.5, 1.5}));
        CmpRegion copy = (CmpRegion) r.copy();
        copy.getRegion1().negate();
        assertFalse(r.getRegion1().getNegated());
    }

    @Test
    void dumpsMatchC() {
        SkyFrame fk5 = new SkyFrame();
        fk5.setSystem("FK5");
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        Circle a = new Circle(fk5, 1, new double[] {1.0, 0.5}, new double[] {0.1}, null);
        Box b = new Box(gal, 1, new double[] {2.857, -0.346}, new double[] {3.057, -0.146}, null);
        CmpRegion sky = cmp(a, b, CmpRegion.AST__AND);
        // The Circle's point on its circumference comes from SkyFrame.offset2 (sin, cos, atan2,
        // asin), whose error RegionEllipseOracleTest bounds by 4e-14; 1e-12 is used as there.
        checkClose("show_sky_and", ChannelText.dump(sky), 1.0e-12);
        CmpRegion inner = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        CmpRegion nested = cmp(inner, circle2(10.0, 10.0, 1.0), CmpRegion.AST__OR);
        check("show_nested", ChannelText.dump(nested));
    }

    /** A CmpRegion read back from its description is written as C writes the one it reads back. */
    @Test
    void dumpsReadBackAsC() {
        CmpRegion inner = cmp(circle2(0.0, 0.0, 3.0), boxc(ZERO2, TWO2), CmpRegion.AST__AND);
        CmpRegion nested = cmp(inner, circle2(10.0, 10.0, 1.0), CmpRegion.AST__OR);
        CmpRegion back = (CmpRegion) ChannelText.read(ChannelText.dump(nested));
        check("reload_nested", ChannelText.dump(back));
        check("nested", tran(back, 2, 1.0, 1.0, 2.5, 0.0, 10.0, 10.0, 20.0, 20.0) + bounded(back) + bounds(back));
        SkyFrame fk5 = new SkyFrame();
        fk5.setSystem("FK5");
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        Circle a = new Circle(fk5, 1, new double[] {1.0, 0.5}, new double[] {0.1}, null);
        Box b = new Box(gal, 1, new double[] {2.857, -0.346}, new double[] {3.057, -0.146}, null);
        // As dumpsMatchC.
        checkClose(
                "reload_sky_and",
                ChannelText.dump(ChannelText.read(ChannelText.dump(cmp(a, b, CmpRegion.AST__AND)))),
                1.0e-12);
    }
}
