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
import static uk.ac.starlink.ast.RegionProbeText.checkClose;
import static uk.ac.starlink.ast.RegionProbeText.error;
import static uk.ac.starlink.ast.RegionProbeText.overlap;
import static uk.ac.starlink.ast.RegionProbeText.points;
import static uk.ac.starlink.ast.RegionProbeText.tran;
import static uk.ac.starlink.ast.RegionProbeText.unc;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_ellipse.rs}, and the Ellipse cases of {@code
 * probes/region_circle_ellipse_probe.c}: the constructors and their errors (including the points
 * that do not determine an ellipse, which C rejects only when the Ellipse is used), membership
 * (closed, open and negated), Bounded, bounds, the defining points, the uncertainty (including
 * an Ellipse given as the uncertainty, which is moved to the Region's first point), the boundary
 * test ({@code astRegPins}), overlap, the mesh and the dump, against C.
 *
 * <p>An Ellipse places the ends of its axes, and its boundary mesh, with {@code sin} and {@code
 * cos} ({@code astOffset2}), and finds its axes with {@code atan2} and {@code acos} ({@code
 * astAngle}); each is within 1 ulp of the true value in {@code java.lang.Math} and in the C
 * library, so the two may differ by 2 ulp, about {@code 4.4e-16} relative. The values here are
 * at most 10 in size; each passes through at most three such functions, and the arithmetic
 * between them (the resolution of a point along and across the primary axis, and the half-length
 * of the secondary axis, {@code a*y/sqrt(a*a - x*x)}, whose {@code x} is at most 0.6 of {@code a}
 * in these cases) amplifies an error by less than 10. That bounds the difference by about {@code
 * 10 * 10 * 3 * 4.4e-16 = 1.3e-13}, so {@link #TOL} is {@code 1e-12}. Every test point lies at
 * least 0.01 from the boundary in the elliptical distance C compares with 1, far beyond such a
 * difference, so membership is compared exactly.
 *
 * <p>{@code construct_rejects_wrong_centre_length} gives a centre longer than the Frame has
 * axes, which C accepts; here it is shorter, which is {@code AST__DIMIN}. {@code negated_copy}
 * uses the negation the Region keeps ({@code astGetNegation}).
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code point_on_boundary_rotated}, whose points
 * lie on the boundary of a rotated Ellipse to within rounding, so that whether they are inside is
 * decided by the last bit of {@code sin} and {@code cos} on each platform.
 */
class EllipseTest {

    private static final double BAD = AstObject.AST__BAD;

    /** The tolerance for values that pass through {@code sin}, {@code cos} and the like (see the class description). */
    private static final double TOL = 1e-12;

    private static final double PIBY2 = Math.PI / 2;

    private static Ellipse ellipse(Frame f, double[] centre, double a, double b, double angle, Region unc) {
        return new Ellipse(f, 1, centre, new double[] {a, b}, new double[] {angle}, unc);
    }

    private static Ellipse ellipse2(double cx, double cy, double a, double b, double angle) {
        return ellipse(new Frame(2), new double[] {cx, cy}, a, b, angle, null);
    }

    private static Box box2(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static double[][] rows(double[]... pts) {
        int naxes = pts[0].length;
        double[][] out = new double[naxes][pts.length];
        for (int i = 0; i < pts.length; i++) {
            for (int j = 0; j < naxes; j++) {
                out[j][i] = pts[i][j];
            }
        }
        return out;
    }

    private static void checkCase(String name, String actual) {
        check(CircleTest.PROBE, name, actual);
    }

    private static void checkNear(String name, String actual) {
        checkClose(CircleTest.PROBE, name, actual, TOL);
    }

    // ---- Translation of test_ellipse.rs ----

    private static Ellipse fiveThree() {
        return ellipse2(0.0, 0.0, 5.0, 3.0, PIBY2);
    }

    private static Ellipse fourTwoRotated() {
        return ellipse2(0.0, 0.0, 4.0, 2.0, Math.PI / 4);
    }

    @Test
    void constructAxisAligned() {
        Ellipse e = fiveThree();
        assertEquals(2, e.getNaxes());
        assertTrue(e.pointInRegion(new double[] {4.9, 0.0}));
        assertFalse(e.pointInRegion(new double[] {5.1, 0.0}));
        assertTrue(e.pointInRegion(new double[] {0.0, 2.9}));
        assertFalse(e.pointInRegion(new double[] {0.0, 3.1}));
    }

    @Test
    void constructRotated() {
        assertEquals(2, ellipse2(1.0, 2.0, 4.0, 2.0, Math.PI / 4).getNaxes());
    }

    @Test
    void constructRejectsNon2dFrame() {
        AstException e = assertThrows(
                AstException.class, () -> ellipse(new Frame(3), new double[] {0.0, 0.0, 0.0}, 5.0, 3.0, PIBY2, null));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    @Test
    void constructRejectsWrongCentreLength() {
        AstException e = assertThrows(
                AstException.class, () -> ellipse(new Frame(2), new double[] {0.0}, 5.0, 3.0, PIBY2, null));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
    }

    @Test
    void constructAcceptsNonPositiveAxes() {
        Ellipse zero = ellipse2(0.0, 0.0, 0.0, 3.0, PIBY2);
        assertThrows(AstException.class, () -> zero.pointInRegion(new double[] {0.0, 0.0}));
        Ellipse negative = ellipse2(0.0, 0.0, 5.0, -1.0, PIBY2);
        assertTrue(negative.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void pointInsideAxisAligned() {
        Ellipse e = fiveThree();
        assertTrue(e.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(e.pointInRegion(new double[] {4.0, 0.0}));
        assertTrue(e.pointInRegion(new double[] {0.0, 2.0}));
        assertTrue(e.pointInRegion(new double[] {2.0, 1.0}));
    }

    @Test
    void pointOutsideAxisAligned() {
        Ellipse e = fiveThree();
        assertFalse(e.pointInRegion(new double[] {6.0, 0.0}));
        assertFalse(e.pointInRegion(new double[] {0.0, 4.0}));
        assertFalse(e.pointInRegion(new double[] {5.0, 3.0}));
    }

    @Test
    void pointOnBoundaryAxisAligned() {
        // The axis ends are placed with sin(pi/2) = 1 and cos(pi/2) = 6.123233995736766e-17, the
        // correctly rounded values every library gives, so these points are on the boundary to
        // within the rounding of 1.0 itself.
        Ellipse e = fiveThree();
        assertTrue(e.pointInRegion(new double[] {5.0, 0.0}));
        assertTrue(e.pointInRegion(new double[] {-5.0, 0.0}));
        assertTrue(e.pointInRegion(new double[] {0.0, 3.0}));
        assertTrue(e.pointInRegion(new double[] {0.0, -3.0}));
    }

    @Test
    void pointOnBoundaryOpen() {
        Ellipse e = fiveThree();
        e.setClosed(false);
        assertFalse(e.pointInRegion(new double[] {5.0, 0.0}));
        assertTrue(e.pointInRegion(new double[] {4.99, 0.0}));
    }

    @Test
    void pointInsideRotated() {
        Ellipse e = fourTwoRotated();
        assertTrue(e.pointInRegion(new double[] {0.0, 0.0}));
        double q = Math.PI / 4;
        assertTrue(e.pointInRegion(new double[] {3.0 * Math.cos(q), 3.0 * Math.sin(q)}));
        assertTrue(e.pointInRegion(new double[] {1.5 * Math.cos(q + PIBY2), 1.5 * Math.sin(q + PIBY2)}));
    }

    @Test
    void pointOutsideRotated() {
        Ellipse e = fourTwoRotated();
        double q = Math.PI / 4;
        assertFalse(e.pointInRegion(new double[] {5.0 * Math.cos(q), 5.0 * Math.sin(q)}));
        assertFalse(e.pointInRegion(new double[] {3.0 * Math.cos(q + PIBY2), 3.0 * Math.sin(q + PIBY2)}));
    }

    @Test
    void negation() {
        Ellipse e = fiveThree();
        assertTrue(e.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(e.pointInRegion(new double[] {6.0, 0.0}));
        e.negate();
        assertTrue(e.getNegated());
        assertFalse(e.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(e.pointInRegion(new double[] {6.0, 0.0}));
    }

    @Test
    void negatedCopy() {
        Ellipse e = fiveThree();
        Region neg = e.getNegation();
        assertFalse(e.getNegated());
        assertTrue(neg.getNegated());
    }

    @Test
    void boundingBoxAxisAligned() {
        // C's values, from astGetRegionBounds at MeshSize 200: the primary axis end, at
        // parametric angle zero, takes the lower bound on each axis, so the upper bound on the
        // first axis is that of the next mesh point, 1 + 5*cos(2*pi/200).
        double[][] b = ellipse2(1.0, 2.0, 5.0, 3.0, PIBY2).getRegionBounds();
        assertEquals(-4.0, b[0][0], 1e-10);
        assertEquals(-1.0, b[0][1], 1e-10);
        assertEquals(5.997532801828654, b[1][0], 1e-10);
        assertEquals(5.0, b[1][1], 1e-10);
    }

    @Test
    void boundingBoxRotated() {
        double[][] b = ellipse2(0.0, 0.0, 4.0, 2.0, 0.0).getRegionBounds();
        assertEquals(-2.0, b[0][0], 1e-10);
        assertEquals(-4.0, b[0][1], 1e-10);
        assertEquals(2.0, b[1][0], 1e-10);
        assertEquals(3.9980262414629233, b[1][1], 1e-10);
    }

    @Test
    void transformForwardPassesInterior() {
        double[][] out = fiveThree()
                .transform(
                        rows(new double[] {2.0, 1.0}, new double[] {6.0, 0.0}, new double[] {0.0, 0.0}, new double[] {
                            0.0, 4.0
                        }),
                        4,
                        true);
        assertArrayEquals(new double[] {2.0, BAD, 0.0, BAD}, out[0]);
        assertArrayEquals(new double[] {1.0, BAD, 0.0, BAD}, out[1]);
    }

    @Test
    void transformInverseMasksAsForward() {
        Ellipse e = fiveThree();
        double[][] in = rows(new double[] {6.0, 0.0}, new double[] {100.0, 200.0});
        double[][] inverse = e.transform(in, 2, false);
        assertArrayEquals(e.transform(in, 2, true), inverse);
        assertEquals(BAD, inverse[0][0]);
        assertEquals(BAD, inverse[1][0]);
    }

    @Test
    void meshPointsLieOnBoundary() {
        Ellipse e = ellipse2(1.0, 2.0, 5.0, 3.0, PIBY2 - 0.3);
        double[][] mesh = e.baseMesh();
        assertTrue(mesh[0].length >= 100);
        double cosA = Math.cos(0.3);
        double sinA = Math.sin(0.3);
        for (int i = 0; i < mesh[0].length; i++) {
            double dx = mesh[0][i] - 1.0;
            double dy = mesh[1][i] - 2.0;
            double u = dx * cosA + dy * sinA;
            double v = -dx * sinA + dy * cosA;
            double d = u * u / 25.0 + v * v / 9.0;
            assertEquals(1.0, d, 1e-10, "point " + i);
        }
    }

    @Test
    void regPinsEllipseEdgeOnBoundary() {
        Ellipse e = fiveThree();
        assertTrue(e.regPins(rows(new double[] {5.0, 0.0}), null, null));
        assertTrue(e.regPins(rows(new double[] {0.0, 3.0}), null, null));
    }

    @Test
    void regPinsEllipseInteriorAndExteriorOffBoundary() {
        Ellipse e = fiveThree();
        assertFalse(e.regPins(rows(new double[] {0.0, 0.0}), null, null));
        assertFalse(e.regPins(rows(new double[] {20.0, 20.0}), null, null));
    }

    @Test
    void regPinsEllipseFullMeshOnBoundary() {
        Ellipse e = fiveThree();
        assertTrue(e.regPins(e.baseMesh(), null, null));
    }

    @Test
    void ellipseBoundsMeasureEachAxisByItsOwnRule() {
        double raw = Math.PI + 1.0e-8;
        for (boolean skyFirst : new boolean[] {true, false}) {
            SkyFrame sky = new SkyFrame();
            sky.setSystem("FK5");
            Frame t = new Frame(1);
            t.setDomain("T");
            CmpFrame cf = new CmpFrame(sky, t);
            int[] pick = skyFirst ? new int[] {1, 3} : new int[] {3, 1};
            Frame mixed = cf.pickAxes(2, pick, null);
            Ellipse el = ellipse(mixed, new double[] {0.0, 0.0}, 4.0, 3.5, PIBY2, null);
            double[][] b = el.getRegionBounds();
            boolean wrapped = b[1][1] <= raw && b[0][1] >= -raw;
            assertEquals(!skyFirst, wrapped, "sky first " + skyFirst + ": " + b[0][1] + " .. " + b[1][1]);
            checkNear(skyFirst ? "ellipse_axis_rule_sky_first" : "ellipse_axis_rule_sky_second", bounds(el));
        }
    }

    // ---- C's values (probes/region_circle_ellipse_probe.c) ----

    private static final double[] TEST = {
        1.0, 2.0, 2.9, 2.6, 2.95, 2.6, 1.0, 3.0, 0.7, 2.9, BAD, 2.0, 1.0, BAD, 100.0, 100.0, 1.5, 2.1, -1.0, 2.0
    };

    private static Ellipse form1() {
        return ellipse2(1.0, 2.0, 2.0, 1.0, 0.3);
    }

    /**
     * The elliptical distance {@code d} of each test point from the Ellipse, which C compares
     * with 1, here at least 0.01 from 1, so that the platform's last bits cannot decide.
     */
    @Test
    void testPointsAreClearOfTheBoundary() {
        double a = 2.0;
        double b = 1.0;
        for (int i = 0; i < TEST.length; i += 2) {
            if (TEST[i] == BAD || TEST[i + 1] == BAD) {
                continue;
            }
            double dx = TEST[i] - 1.0;
            double dy = TEST[i + 1] - 2.0;
            // The primary axis is 0.3 radians from the second axis towards the first.
            double u = dx * Math.sin(0.3) + dy * Math.cos(0.3);
            double v = dx * Math.cos(0.3) - dy * Math.sin(0.3);
            double d = u * u / (a * a) + v * v / (b * b);
            assertTrue(Math.abs(d - 1.0) > 0.01, "point " + i / 2 + ": " + d);
        }
    }

    @Test
    void form1EllipseMatchesC() {
        Ellipse e = form1();
        checkNear("ellipse_form1", tran(e, 2, TEST) + bounded(e) + bounds(e) + points(e) + unc(e, true));
        Ellipse neg = (Ellipse) e.copy();
        neg.negate();
        checkCase("ellipse_form1_negated", tran(neg, 2, TEST) + bounded(neg));
        Ellipse open = (Ellipse) e.copy();
        open.setClosed(false);
        checkCase("ellipse_form1_open", tran(open, 2, TEST));
    }

    @Test
    void form0EllipseMatchesC() {
        Ellipse e = new Ellipse(
                new Frame(2), 0, new double[] {0.0, 0.0}, new double[] {2.0, 0.0}, new double[] {1.0, 0.866}, null);
        checkNear(
                "ellipse_form0", tran(e, 2, 0.0, 0.0, 2.0, 0.0, 1.9, 0.1, 0.0, 1.2, 0.0, 0.95) + bounds(e) + points(e));
    }

    @Test
    void constructorErrorsMatchC() {
        double[] c0 = {0.0, 0.0};
        double[] p1 = {2.0, 0.0};
        double[] p2 = {1.0, 0.866};
        double[] bad = {BAD, 0.0};
        double[] ax = {2.0, 1.0};
        double[] ang = {0.3};
        checkCase("ellipse_form_2", error(() -> new Ellipse(new Frame(2), 2, c0, p1, p2, null)));
        checkCase("ellipse_naxes", error(() -> new Ellipse(new Frame(3), 1, new double[3], ax, ang, null)));
        checkCase("ellipse_bad_centre", error(() -> new Ellipse(new Frame(2), 1, bad, ax, ang, null)));
        checkCase(
                "ellipse_bad_axis", error(() -> new Ellipse(new Frame(2), 1, c0, new double[] {2.0, BAD}, ang, null)));
        checkCase("ellipse_bad_angle", error(() -> new Ellipse(new Frame(2), 1, c0, ax, new double[] {BAD}, null)));
        checkCase("ellipse_bad_point1", error(() -> new Ellipse(new Frame(2), 0, c0, bad, p2, null)));
        checkCase("ellipse_bad_point2", error(() -> new Ellipse(new Frame(2), 0, c0, p1, bad, null)));
        Box box3 = new Box(new Frame(3), 1, new double[] {-0.1, -0.1, -0.1}, new double[] {0.1, 0.1, 0.1}, null);
        checkCase("ellipse_unc_dims", error(() -> ellipse(new Frame(2), new double[] {1.0, 2.0}, 2.0, 1.0, 0.3, box3)));
    }

    @Test
    void pointsThatDoNotDetermineAnEllipseFailWhenUsed() {
        Ellipse zero = new Ellipse(
                new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {0.0, 1.0}, new double[] {0.3}, null);
        double[] test0 = {0.0, 0.0, 2.0, 0.0, 1.9, 0.1, 0.0, 1.2, 0.0, 0.95};
        checkNear("ellipse_zero_axis", points(zero) + error(() -> tran(zero, 2, test0)));
        // C marks its cache up to date even when it fails; here each use fails again.
        assertThrows(AstException.class, () -> tran(zero, 2, test0));
        Ellipse onAxis = new Ellipse(
                new Frame(2), 0, new double[] {0.0, 0.0}, new double[] {2.0, 0.0}, new double[] {0.0, 0.0}, null);
        checkCase("ellipse_on_its_axis", error(() -> bounds(onAxis)));
    }

    @Test
    void negativeAxisMatchesC() {
        Ellipse e = ellipse2(1.0, 2.0, -2.0, 1.0, 0.3);
        checkNear("ellipse_negative_axis", points(e) + tran(e, 2, TEST));
    }

    @Test
    void nonFiniteAndShortArgumentsAreRejected() {
        double[] c0 = {0.0, 0.0};
        assertEquals(
                AstException.AST__BADIN,
                assertThrows(
                                AstException.class,
                                () -> ellipse(new Frame(2), new double[] {Double.NaN, 0.0}, 2.0, 1.0, 0.3, null))
                        .getStatus());
        assertThrows(NullPointerException.class, () -> ellipse(null, c0, 2.0, 1.0, 0.3, null));
        assertThrows(
                NullPointerException.class, () -> new Ellipse(new Frame(2), 1, c0, null, new double[] {0.3}, null));
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(
                                AstException.class,
                                () -> new Ellipse(new Frame(2), 1, c0, new double[] {2.0}, new double[] {0.3}, null))
                        .getStatus());
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(
                                AstException.class,
                                () -> new Ellipse(new Frame(2), 1, c0, new double[] {2.0, 1.0}, new double[0], null))
                        .getStatus());
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(
                                AstException.class,
                                () -> new Ellipse(
                                        new Frame(2), 0, c0, new double[] {2.0, 0.0}, new double[] {1.0}, null))
                        .getStatus());
    }

    @Test
    void uncertaintiesMatchC() {
        Ellipse e = ellipse(new Frame(2), new double[] {1.0, 2.0}, 2.0, 1.0, 0.3, box2(-0.1, -0.1, 0.1, 0.2));
        checkCase("ellipse_unc_box", unc(e, false));
        Ellipse eu = ellipse(new Frame(2), new double[] {1.0, 2.0}, 2.0, 1.0, 0.3, ellipse2(5.0, 5.0, 0.02, 0.01, 0.5));
        checkNear("ellipse_unc_ellipse", unc(eu, false) + points(eu.getUnc(false)));
    }

    @Test
    void anEllipseUncertaintyIsMovedToTheRegionMatchesC() {
        Circle c = new Circle(new Frame(2), new double[] {1.0, 2.0}, 1.0, ellipse2(-5.0, 7.0, 0.02, 0.01, 0.3));
        checkNear("circle_unc_ellipse", unc(c, false) + bounds(c));
        Box b = box2(10.0, 10.0, 20.0, 20.0);
        b.setUnc(ellipse2(0.0, 0.0, 0.2, 0.1, 0.3));
        checkNear("box_unc_ellipse", unc(b, false) + points(b.getUnc(false)));
    }

    @Test
    void mappedEllipseMatchesC() {
        Frame z = new Frame(2);
        z.setDomain("Z");
        Region m = form1().mapRegionInto(new ZoomMap(2, 2.0), z);
        assertInstanceOf(Ellipse.class, m);
        checkNear("ellipse_mapped", points(m) + bounds(m));
    }

    @Test
    void dumpsMatchC() {
        Ellipse e = form1();
        checkNear("ellipse_show", ChannelText.dump(e));
        Ellipse back = assertInstanceOf(Ellipse.class, ChannelText.read(ChannelText.dump(e)));
        assertEquals(ChannelText.dump(e), ChannelText.dump(back));
        assertTrue(back.pointInRegion(new double[] {1.0, 2.0}));
        e.setMeshSize(8);
        checkNear("ellipse_showmesh", CircleTest.mesh(e, false, null));
    }

    @Test
    void boundaryTestsMatchC() {
        Ellipse e = fiveThree();
        checkNear(
                "pins_ellipse",
                CircleTest.pins(
                                e,
                                rows(new double[] {0.0, 5.0}, new double[] {0.0, 0.0}, new double[] {3.0, 0.0}),
                                null)
                        + CircleTest.pins(e, e.regMesh(), null)
                        + CircleTest.pins(e, rows(new double[] {0.0, 0.0}), null));
    }

    @Test
    void overlapsMatchC() {
        Ellipse e = ellipse2(0.0, 0.0, 2.0, 1.0, 0.3);
        checkCase("overlap_circle_ellipse", overlap(new Circle(new Frame(2), new double[] {0.0, 0.0}, 2.0, null), e));
        checkCase("overlap_ellipse_same", overlap(e, ellipse2(0.0, 0.0, 2.0, 1.0, 0.3)));
        checkCase("overlap_ellipse_inside", overlap(e, ellipse2(0.1, 0.0, 0.5, 0.2, 1.0)));
    }
}
