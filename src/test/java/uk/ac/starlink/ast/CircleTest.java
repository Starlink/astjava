package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.RegionProbeText.bounded;
import static uk.ac.starlink.ast.RegionProbeText.bounds;
import static uk.ac.starlink.ast.RegionProbeText.check;
import static uk.ac.starlink.ast.RegionProbeText.error;
import static uk.ac.starlink.ast.RegionProbeText.overlap;
import static uk.ac.starlink.ast.RegionProbeText.points;
import static uk.ac.starlink.ast.RegionProbeText.tran;
import static uk.ac.starlink.ast.RegionProbeText.unc;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_circle.rs}, and the Circle cases of {@code
 * probes/region_circle_ellipse_probe.c}: the constructors and their errors, membership (closed,
 * open and negated), Bounded, bounds in one, two and three dimensions, the defining points, the
 * uncertainty, the boundary test ({@code astRegPins}), overlap, the mesh and the dump, against C.
 *
 * <p>A Circle in a plain Frame finds its circumference point, its radius and its bounds with
 * arithmetic and a square root, which give the same bits on every platform, so its values are
 * compared exactly. Its boundary mesh goes through {@code sin} and {@code cos}, which may differ
 * in the last bit between platforms, and is compared only as {@code astShowMesh} formats it, to
 * six significant figures, none of which lies within a few ulps of a rounding boundary.
 *
 * <p>Rust's {@code with_integrity} with a closure that changes nothing is translated as a setting
 * of the Title, and with a closure that fails as a setting the Frame rejects. {@code
 * construct_requires_matching_axes} gives a centre longer than the Frame has axes, which C
 * accepts; here it is shorter, which is {@code AST__DIMIN}. {@code
 * construct_requires_edge_or_radius} is jniast's choice of constructor, translated as the
 * unknown {@code form} C rejects.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code construct_with_radius}, {@code
 * construct_with_edge_point}, {@code radius_round_trips_exactly_on_a_sky_frame} and {@code
 * with_integrity_callable_multiple_times} ({@code astCirclePars}, not in jniast), and {@code
 * with_integrity_identity_change_preserves_points} and {@code
 * with_integrity_non_adaptive_preserves_points} ({@code astGetRegionDisc}, not in jniast). {@link
 * RegionSkyTest} has the membership of points by a pole that the round trip of the radius
 * decides.
 */
class CircleTest {

    private static final double BAD = AstObject.AST__BAD;

    /** The cases of {@code probes/region_circle_ellipse_probe.c}. */
    static final Map<String, String> PROBE = ProbeOutput.load("region_circle_ellipse_probe");

    private static Circle circle(Frame f, double[] centre, double radius) {
        return new Circle(f, centre, radius, null);
    }

    private static Circle circle2(double cx, double cy, double r) {
        return circle(new Frame(2), new double[] {cx, cy}, r);
    }

    private static Box box2(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static Frame frame(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
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
        check(PROBE, name, actual);
    }

    // ---- Translation of test_circle.rs ----

    @Test
    void constructRequiresMatchingAxes() {
        AstException e = assertThrows(AstException.class, () -> circle(new Frame(3), new double[] {1.0, 2.0}, 1.0));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
    }

    @Test
    void constructRequiresEdgeOrRadius() {
        AstException e = assertThrows(
                AstException.class,
                () -> new Circle(new Frame(2), 2, new double[] {1.0, 2.0}, new double[] {4.0, 6.0}, null));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    private static Circle five() {
        return circle2(0.0, 0.0, 5.0);
    }

    @Test
    void pointInside() {
        Circle c = five();
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {3.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {0.0, 4.0}));
        assertTrue(c.pointInRegion(new double[] {4.9, 0.0}));
    }

    @Test
    void pointOutside() {
        Circle c = five();
        assertFalse(c.pointInRegion(new double[] {6.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {0.0, -6.0}));
        assertFalse(c.pointInRegion(new double[] {5.0, 5.0}));
    }

    @Test
    void pointOnBoundaryClosed() {
        Circle c = five();
        assertTrue(c.getClosed());
        assertTrue(c.pointInRegion(new double[] {5.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {0.0, 5.0}));
        assertTrue(c.pointInRegion(new double[] {3.0, 4.0}));
    }

    @Test
    void pointOnBoundaryOpen() {
        Circle c = five();
        c.setClosed(false);
        assertFalse(c.getClosed());
        assertFalse(c.pointInRegion(new double[] {5.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {3.0, 4.0}));
        assertTrue(c.pointInRegion(new double[] {4.99, 0.0}));
    }

    @Test
    void pointWithBadValue() {
        Circle c = five();
        assertFalse(c.pointInRegion(new double[] {BAD, 0.0}));
        assertFalse(c.pointInRegion(new double[] {0.0, BAD}));
        assertFalse(c.pointInRegion(new double[] {BAD, BAD}));
    }

    @Test
    void negationFlipsMembership() {
        Circle c = five();
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(c.pointInRegion(new double[] {10.0, 0.0}));
        c.negate();
        assertTrue(c.getNegated());
        assertFalse(c.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(c.pointInRegion(new double[] {10.0, 0.0}));
    }

    @Test
    void negatedCopy() {
        Circle c = five();
        Region neg = c.getNegation();
        assertFalse(c.getNegated());
        assertTrue(neg.getNegated());
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(neg.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void transformPassesInterior() {
        double[][] in = rows(new double[] {1.0, 1.0}, new double[] {3.0, 4.0});
        assertArrayEquals(in, five().transform(in, 2, true));
    }

    @Test
    void transformMasksExterior() {
        double[][] out = five().transform(rows(new double[] {10.0, 0.0}, new double[] {0.0, -10.0}), 2, true);
        for (double[] axis : out) {
            assertArrayEquals(new double[] {BAD, BAD}, axis);
        }
    }

    @Test
    void transformMixed() {
        double[][] out = five().transform(
                        rows(new double[] {1.0, 1.0}, new double[] {10.0, 10.0}, new double[] {0.0, 0.0}), 3, true);
        assertArrayEquals(new double[] {1.0, BAD, 0.0}, out[0]);
        assertArrayEquals(new double[] {1.0, BAD, 0.0}, out[1]);
    }

    @Test
    void transformInverseMasksAsForward() {
        Circle c = five();
        double[][] in = rows(new double[] {10.0, 10.0}, new double[] {0.0, 0.0});
        double[][] inverse = c.transform(in, 2, false);
        assertArrayEquals(c.transform(in, 2, true), inverse);
        assertEquals(BAD, inverse[0][0]);
        assertEquals(BAD, inverse[1][0]);
    }

    @Test
    void frameDelegation() {
        Frame f = new Frame(3);
        f.setTitle("Test Frame");
        f.setDomain("TEST");
        f.setLabel(1, "X");
        f.setLabel(2, "Y");
        f.setLabel(3, "Z");
        Circle c = circle(f, new double[] {0.0, 0.0, 0.0}, 2.0);
        assertEquals(3, c.getNaxes());
        assertEquals("TEST", c.getDomain());
        assertEquals("Y", c.getLabel(2));
    }

    @Test
    void frameDistanceDelegation() {
        assertEquals(5.0, five().distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}));
    }

    @Test
    void boundingBox2d() {
        double[][] b = circle2(1.0, 2.0, 3.0).getRegionBounds();
        assertArrayEquals(new double[] {-2.0, -1.0}, b[0]);
        assertArrayEquals(new double[] {4.0, 5.0}, b[1]);
    }

    @Test
    void boundingBox3d() {
        double[][] b = circle(new Frame(3), new double[] {5.0, 10.0, -3.0}, 2.0).getRegionBounds();
        assertArrayEquals(new double[] {3.0, 8.0, -5.0}, b[0]);
        assertArrayEquals(new double[] {7.0, 12.0, -1.0}, b[1]);
    }

    @Test
    void mesh2dPointsOnBoundary() {
        Circle c = five();
        c.setMeshSize(100);
        double[][] mesh = c.baseMesh();
        assertEquals(2, mesh.length);
        assertEquals(100, mesh[0].length);
        // Each point is (5 sin t, 5 cos t), whose distance from the centre is 5 to within a few
        // ulps of 5.
        for (int i = 0; i < 100; i++) {
            assertEquals(5.0, Math.hypot(mesh[0][i], mesh[1][i]), 1e-13, "point " + i);
        }
    }

    @Test
    void mesh1d() {
        double[][] mesh = circle(new Frame(1), new double[] {10.0}, 3.0).baseMesh();
        assertEquals(1, mesh.length);
        assertArrayEquals(new double[] {7.0, 13.0}, mesh[0]);
    }

    @Test
    void offCentrePointInRegion() {
        Circle c = circle2(10.0, 20.0, 3.0);
        assertTrue(c.pointInRegion(new double[] {10.0, 20.0}));
        assertTrue(c.pointInRegion(new double[] {12.0, 20.0}));
        assertFalse(c.pointInRegion(new double[] {14.0, 20.0}));
    }

    @Test
    void mappingNinNout() {
        assertEquals(2, five().getNin());
        assertEquals(2, five().getNout());
    }

    @Test
    void regionAttributes() {
        Circle c = five();
        assertTrue(c.getClosed());
        assertFalse(c.getNegated());
        assertTrue(c.getBounded());
        assertTrue(c.getAdaptive());
        assertEquals(200, c.getMeshSize());
        assertEquals(1.0, c.getFillFactor());
        c.setClosed(false);
        assertFalse(c.getClosed());
        c.setMeshSize(500);
        assertEquals(500, c.getMeshSize());
        c.setFillFactor(0.5);
        assertEquals(0.5, c.getFillFactor());
        c.setAdaptive(false);
        assertFalse(c.getAdaptive());
    }

    @Test
    void cloneIsIndependent() {
        Circle c = five();
        Circle copy = (Circle) c.copy();
        copy.negate();
        assertFalse(c.getNegated());
        assertTrue(copy.getNegated());
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(copy.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void batchTransform10kPoints() {
        Circle c = circle2(0.0, 0.0, 1.0);
        int n = 100;
        double[][] in = new double[2][n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                in[0][i * n + j] = -2.0 + 4.0 * i / (n - 1);
                in[1][i * n + j] = -2.0 + 4.0 * j / (n - 1);
            }
        }
        double[][] out = c.transform(in, n * n, true);
        int inside = 0;
        for (int k = 0; k < n * n; k++) {
            // The plain Frame's distance is the square root of the sum of squares, as here.
            double dist = Math.sqrt(in[0][k] * in[0][k] + in[1][k] * in[1][k]);
            if (dist <= 1.0) {
                assertEquals(in[0][k], out[0][k], "point " + k);
                assertEquals(in[1][k], out[1][k]);
                inside++;
            } else {
                assertEquals(BAD, out[0][k], "point " + k);
                assertEquals(BAD, out[1][k]);
            }
        }
        assertTrue(inside > 700, "inside " + inside);
        assertTrue(n * n - inside > 7000, "outside " + (n * n - inside));
    }

    @Test
    void withIntegrityInvalidatesCache() {
        Circle c = five();
        c.baseMesh();
        c.setTitle("Same place");
        double[][] mesh = c.baseMesh();
        assertEquals(200, mesh[0].length);
        for (int i = 0; i < mesh[0].length; i++) {
            assertEquals(5.0, Math.hypot(mesh[0][i], mesh[1][i]), 1e-13, "point " + i);
        }
    }

    @Test
    void withIntegrityErrorPropagates() {
        Circle c = five();
        String before = ChannelText.dump(c);
        AstException e = assertThrows(AstException.class, () -> c.set("Digits=many"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
        assertEquals(before, ChannelText.dump(c));
        assertTrue(c.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void regPinsCircleEdgeOnBoundary() {
        assertTrue(five().regPins(rows(new double[] {5.0, 0.0}), null, null));
    }

    @Test
    void regPinsCircleInteriorAndExteriorOffBoundary() {
        assertFalse(five().regPins(rows(new double[] {0.0, 0.0}), null, null));
        assertFalse(five().regPins(rows(new double[] {20.0, 0.0}), null, null));
    }

    @Test
    void regPinsCircleFullMeshOnBoundary() {
        Circle c = five();
        assertTrue(c.regPins(c.baseMesh(), null, null));
    }

    @Test
    void loadedFramelessUncertaintyTakesTheBaseFrame() {
        Circle unc = circle(frame(2, "FOO"), new double[] {1.0, 0.5}, 0.001);
        Circle c = circle(frame(2, "FOO"), new double[] {1.0, 0.5}, 0.1);
        Region mapped = c.mapRegionInto(new ZoomMap(2, 2.0), frame(2, "BAR"));
        mapped.setUnc(unc);
        Region restored = (Region) ChannelText.read(ChannelText.dump(mapped));
        assertEquals(
                "FOO", restored.getUncFrm(FrameSet.AST__BASE).getRegionFrame().getDomain());
    }

    @Test
    void copyKeepsIdentAndOwnUseDefsAndClearsReport() {
        Circle c = circle2(1.0, 2.0, 0.5);
        c.setIdent("C1");
        String text = ChannelText.dump(c)
                .replaceFirst(
                        "    Ident = \"C1\" \t# Permanent Object identification string\n",
                        "    Ident = \"C1\" \t# Permanent Object identification string\n"
                                + "    UseDfs = 0 \t# Use default values\n");
        Region read = (Region) ChannelText.read(text);
        assertFalse(read.getUseDefs());
        read.setReport(true);
        Region copy = (Region) read.copy();
        assertEquals("C1", copy.getIdent());
        assertFalse(copy.getReport());
        assertFalse(copy.getUseDefs());
    }

    @Test
    void getUncGivesTheUncertaintyInTheCurrentFrame() {
        Circle c = circle(frame(2, "FOO"), new double[] {1.0, 0.5}, 0.1);
        assertNull(c.getUnc(false));
        assertInstanceOf(Region.class, c.getUnc(true));
        Circle unc = circle(frame(2, "FOO"), new double[] {1.0, 0.5}, 0.001);
        Region mapped = c.mapRegionInto(new ZoomMap(2, 2.0), frame(2, "BAR"));
        mapped.setUnc(unc);
        Region got = mapped.getUnc(false);
        double[][] b = got.getRegionBounds();
        // The Circle of radius 0.001 doubled by the ZoomMap: its bounds are arithmetic on the
        // values given, within a few ulps of those written.
        assertArrayEquals(new double[] {1.998, 0.998}, b[0], 1e-9);
        assertArrayEquals(new double[] {2.002, 1.002}, b[1], 1e-9);
        assertEquals("BAR", got.getRegionFrame().getDomain());
        assertFalse(got.testRegionFS());
    }

    @Test
    void aFramesetGivenAsTheFrameContributesItsCurrentFrame() {
        FrameSet fs = new FrameSet(frame(2, "X"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), frame(2, "Y"));
        Circle onFs = circle(fs, new double[] {0.0, 0.0}, 1.0);
        assertEquals("Y", onFs.getDomain());
        assertEquals(ChannelText.dump(circle(frame(2, "Y"), new double[] {0.0, 0.0}, 1.0)), ChannelText.dump(onFs));
        fs.invert();
        Circle onInverted = circle(fs, new double[] {0.0, 0.0}, 1.0);
        assertEquals("X", onInverted.getDomain());
        assertEquals("X", circle(onInverted, new double[] {0.0, 0.0}, 2.0).getDomain());
    }

    // ---- C's values (probes/region_circle_ellipse_probe.c) ----

    private static final double[] TEST = {
        1.0, 2.0, 4.0, 2.0, 1.0, 5.0, 3.9, 2.0, 4.1, 2.0, BAD, 2.0, 1.0, BAD, 100.0, 100.0, -1.0, 2.0, 1.0, -1.0
    };

    private static Circle flat() {
        return circle(new Frame(2), new double[] {1.0, 2.0}, 3.0);
    }

    @Test
    void flatCircleMatchesC() {
        Circle cf = flat();
        checkCase(
                "circle_flat",
                tran(cf, 2, TEST) + bounded(cf) + bounds(cf) + points(cf) + unc(cf, true) + unc(cf, false));
        Circle neg = (Circle) cf.copy();
        neg.negate();
        checkCase("circle_flat_negated", tran(neg, 2, TEST) + bounded(neg) + bounds(neg));
        Circle open = (Circle) cf.copy();
        open.setClosed(false);
        String openTran = tran(open, 2, TEST);
        open.negate();
        checkCase("circle_flat_open", openTran);
        checkCase("circle_flat_open_negated", tran(open, 2, TEST));
        Circle form0 = new Circle(new Frame(2), new double[] {1.0, 2.0}, new double[] {4.0, 6.0}, null);
        checkCase("circle_form0", points(form0) + bounds(form0) + tran(form0, 2, TEST));
    }

    @Test
    void oneAndThreeDimensionalCirclesMatchC() {
        Circle c1 = circle(new Frame(1), new double[] {10.0}, 3.0);
        checkCase(
                "circle_1d",
                tran(c1, 1, 7.0, 13.0, 6.9, 13.1, 10.0) + bounds(c1) + points(c1) + mesh(c1, false, "mesh"));
        Circle c3 = circle(new Frame(3), new double[] {5.0, 10.0, -3.0}, 2.0);
        checkCase(
                "circle_3d",
                tran(c3, 3, 5.0, 10.0, -3.0, 7.0, 10.0, -3.0, 6.0, 11.0, -2.0, 5.0, 10.0, -5.1, 6.5, 11.5, -1.5)
                        + bounds(c3)
                        + points(c3));
    }

    /** The table {@link Region#showMesh} writes, as the probe's output keeps it: lines trimmed, none empty. */
    static String mesh(Region r, boolean format, String ttl) {
        StringBuilder sb = new StringBuilder();
        for (String line : r.meshText(format, ttl).split("\n", -1)) {
            String t = line.replaceAll("\\s+$", "");
            if (!t.isEmpty()) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private static String domain(Region r) {
        return "domain " + r.getDomain() + "\n";
    }

    @Test
    void framesAndRegionsGivenAsTheFrameMatchC() {
        FrameSet fs = new FrameSet(frame(2, "X"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), frame(2, "Y"));
        Circle u = circle(fs, new double[] {0.0, 0.0}, 1.0);
        checkCase("circle_frameset", domain(u) + points(u));
        fs.invert();
        u = circle(fs, new double[] {0.0, 0.0}, 1.0);
        checkCase("circle_frameset_inverted", domain(u));
        u = circle(u, new double[] {0.0, 0.0}, 2.0);
        checkCase("circle_region_frame", domain(u) + points(u));
    }

    @Test
    void constructorErrorsMatchC() {
        double[] cen = {1.0, 2.0};
        double[] pt = {4.0, 6.0};
        double[] cbad = {BAD, 2.0};
        double[] pbad = {4.0, BAD};
        checkCase("circle_form_2", error(() -> new Circle(new Frame(2), 2, cen, pt, null)));
        checkCase("circle_bad_centre_form1", error(() -> circle(new Frame(2), cbad, 1.0)));
        checkCase("circle_bad_radius", error(() -> circle(new Frame(2), cen, BAD)));
        checkCase("circle_bad_centre_form0", error(() -> new Circle(new Frame(2), 0, cbad, pt, null)));
        checkCase("circle_bad_point", error(() -> new Circle(new Frame(2), 0, cen, pbad, null)));
        Box box3 = new Box(new Frame(3), 1, new double[] {-0.1, -0.1, -0.1}, new double[] {0.1, 0.1, 0.1}, null);
        checkCase("circle_unc_dims", error(() -> new Circle(new Frame(2), cen, 1.0, box3)));
        Interval half = new Interval(new Frame(2), new double[] {0.0, BAD}, new double[] {BAD, BAD}, null);
        checkCase("circle_unc_shape", error(() -> new Circle(new Frame(2), cen, 1.0, half)));
    }

    @Test
    void nonFiniteCoordinatesAreBad() {
        assertEquals(
                AstException.AST__BADIN,
                assertThrows(AstException.class, () -> circle2(Double.NaN, 0.0, 1.0))
                        .getStatus());
        assertEquals(
                AstException.AST__BADIN,
                assertThrows(AstException.class, () -> circle2(0.0, 0.0, Double.POSITIVE_INFINITY))
                        .getStatus());
    }

    @Test
    void nullAndShortArgumentsAreRejected() {
        double[] cen = {1.0, 2.0};
        assertThrows(NullPointerException.class, () -> circle(null, cen, 1.0));
        assertThrows(NullPointerException.class, () -> circle(new Frame(2), null, 1.0));
        assertThrows(NullPointerException.class, () -> new Circle(new Frame(2), cen, null, null));
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> new Circle(new Frame(2), cen, new double[] {1.0}, null))
                        .getStatus());
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> new Circle(new Frame(2), 1, cen, new double[0], null))
                        .getStatus());
        // Longer arrays are accepted.
        Circle c = new Circle(new Frame(2), 1, new double[] {1.0, 2.0, 99.0}, new double[] {3.0, 99.0}, null);
        assertTrue(c.pointInRegion(new double[] {3.0, 2.0}));
    }

    @Test
    void uncertaintiesMatchC() {
        double[] cen = {1.0, 2.0};
        Box negBox = box2(-0.1, -0.1, 0.1, 0.2);
        negBox.negate();
        checkCase("circle_unc_negated", unc(new Circle(new Frame(2), cen, 1.0, negBox), false));
        Circle u = new Circle(new Frame(2), cen, 1.0, circle2(-5.0, 7.0, 0.01));
        checkCase("circle_unc_circle", unc(u, false) + bounds(u));
    }

    @Test
    void negativeAndZeroRadiiMatchC() {
        Circle neg = circle(new Frame(2), new double[] {1.0, 2.0}, -2.0);
        checkCase("circle_negative_radius", points(neg) + bounds(neg) + tran(neg, 2, TEST));
        Circle zero = circle(new Frame(2), new double[] {1.0, 2.0}, 0.0);
        checkCase("circle_zero_radius", points(zero) + bounds(zero) + tran(zero, 2, TEST) + unc(zero, true));
    }

    @Test
    void mappedCircleMatchesC() {
        Region m = flat().mapRegionInto(new ZoomMap(2, 2.0), frame(2, "Z"));
        assertInstanceOf(Circle.class, m);
        checkCase("circle_mapped", points(m) + bounds(m) + tran(m, 2, TEST));
    }

    @Test
    void dumpsMatchC() {
        checkCase("circle_show", ChannelText.dump(flat()));
        Circle withUnc = new Circle(new Frame(2), new double[] {1.0, 2.0}, 1.0, box2(-0.1, -0.1, 0.1, 0.2));
        checkCase("circle_show_unc", ChannelText.dump(withUnc));
        Circle back = assertInstanceOf(Circle.class, ChannelText.read(ChannelText.dump(withUnc)));
        assertEquals(ChannelText.dump(withUnc), ChannelText.dump(back));
        assertTrue(back.pointInRegion(new double[] {1.5, 2.5}));
        Circle cf = flat();
        cf.setMeshSize(8);
        checkCase("circle_showmesh", mesh(cf, false, null));
    }

    /** The probe's {@code pins} line: the result of regPins, then its mask. */
    static String pins(Region r, double[][] pts, Region pointUnc) {
        int np = pts[0].length;
        boolean[] mask = new boolean[np];
        boolean result = r.regPins(pts, pointUnc, mask);
        StringBuilder sb = new StringBuilder("pins ").append(result ? 1 : 0);
        for (boolean m : mask) {
            sb.append(m ? " 1" : " 0");
        }
        return sb.append('\n').toString();
    }

    @Test
    void boundaryTestsMatchC() {
        Circle cf = flat();
        double[][] near = rows(new double[] {4.0 + 1.0e-7, 2.0}, new double[] {4.0 + 1.0e-5, 2.0});
        checkCase(
                "pins_circle",
                pins(cf, rows(new double[] {4.0, 2.0}), null)
                        + pins(cf, rows(new double[] {1.0, 2.0}), null)
                        + pins(cf, rows(new double[] {20.0, 2.0}), null)
                        + pins(
                                cf,
                                rows(new double[] {4.0, 2.0}, new double[] {1.0, 2.0}, new double[] {1.0, 5.0}),
                                null)
                        + pins(cf, near, null)
                        + pins(cf, cf.regMesh(), null)
                        + pins(cf, near, box2(-1.0e-4, -1.0e-4, 1.0e-4, 1.0e-4)));
    }

    @Test
    void overlapsMatchC() {
        Circle c0 = circle2(0.0, 0.0, 2.0);
        checkCase("overlap_circle_same", overlap(c0, circle2(0.0, 0.0, 2.0)));
        checkCase("overlap_circle_inside", overlap(c0, circle2(0.5, 0.0, 0.5)));
        checkCase("overlap_circle_partial", overlap(c0, circle2(3.0, 0.0, 2.0)));
        checkCase("overlap_circle_none", overlap(c0, circle2(10.0, 0.0, 2.0)));
        Region neg = (Region) c0.copy();
        neg.negate();
        checkCase("overlap_circle_negation", overlap(c0, neg));
        checkCase("overlap_circle_box", overlap(c0, box2(-1.0, -1.0, 1.0, 1.0)));
    }

    @Test
    void regionFrameIsACopyAndBoundsAreKept() {
        Circle cf = flat();
        Frame f = cf.getRegionFrame();
        f.setDomain("CHANGED");
        assertEquals("", cf.getDomain());
        // RegBaseBox of a Circle in a plain Frame is the centre plus and minus the radius.
        double[] lb = new double[2];
        double[] ub = new double[2];
        cf.regBaseBox(lb, ub);
        assertArrayEquals(new double[] {-2.0, -1.0}, lb);
        assertArrayEquals(new double[] {4.0, 5.0}, ub);
    }
}
