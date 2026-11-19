package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static uk.ac.starlink.ast.RegionProbeText.checkClose;
import static uk.ac.starlink.ast.RegionProbeText.points;

import org.junit.jupiter.api.Test;

/**
 * The best fitting Circle and Ellipse through the boundary mesh of a Region ({@link
 * Circle#bestCircle}, C's {@code astBestCircle}; {@link Ellipse#bestEllipse}, C's {@code
 * astBestEllipse}), which the simplification of a Circle or an Ellipse tries, against {@code
 * probes/region_circle_ellipse_probe.c}: for Circles and Ellipses in a plain Frame, stretched by
 * a WinMap or sheared by a MatrixMap, and in a SkyFrame, the class of the Region made, its shape,
 * and which mesh points are on its boundary ({@code astRegPins}).
 *
 * <p>The mesh is placed with {@code sin} and {@code cos}, which may differ by 2 ulp between
 * {@code java.lang.Math} and the C library. The Circle's radius is the root mean square distance
 * of the mesh from the centre, which keeps that error, and its points are compared. The Ellipse
 * fits a quadratic to the distances of the farthest mesh point and its two neighbours, against
 * their angles: the differences of those distances, about {@code 1e-4} of the distances for these
 * meshes of 200 points, divide errors of a few ulps, so the angle and length of the primary axis
 * may differ by {@code 1e4 * 1e-15 = 1e-11}, within {@link #TOL} of {@code 1e-9}. Which end of the
 * primary axis is farthest from the centre is decided by rounding, the two ends of the mesh of a
 * symmetric shape being equally far, so for an Ellipse the half-lengths of its axes and the
 * direction of its primary axis at the centre, modulo a half turn, are compared rather than its
 * points; for a Circle's mesh every distance is equal and the direction too is decided by
 * rounding, so only the half-lengths are compared.
 */
class RegionBestFitTest {

    /** The tolerance for the points of a fitted Region (see the class description). */
    private static final double TOL = 1e-9;

    private static String fit(Region r, boolean circle) {
        double[][] mesh = r.regMesh();
        Region u = r.getUncFrm(FrameSet.AST__CURRENT);
        double[][] pts = r.getRegionPoints();
        double[] cen = {pts[0][0], pts[1][0]};
        Region result = circle ? Circle.bestCircle(mesh, cen, u) : Ellipse.bestEllipse(mesh, cen, u);
        if (result == null) {
            return "fit none\n";
        }
        return "fit " + result.astClass() + "\n" + points(result) + CircleTest.pins(result, mesh, null);
    }

    private static void checkNear(String name, Region r, boolean circle) {
        if (circle) {
            checkClose(CircleTest.PROBE, name, fit(r, true), TOL);
        } else {
            checkEllipseFit(name, r, true);
        }
    }

    /**
     * Checks an Ellipse fit: the class and the boundary test exactly, and the half-lengths of the
     * axes and, if {@code direction}, the direction of the primary axis modulo a half turn, within
     * {@link #TOL}, each found in the Frame of {@code r}.
     */
    private static void checkEllipseFit(String name, Region r, boolean direction) {
        String[] want = CircleTest.PROBE.get(name).split("\n", -1);
        String[] got = fit(r, false).split("\n", -1);
        assertEquals(want[0], got[0], name);
        assertEquals(want[2], got[2], name + ": pins");
        Frame f = r.getRegionFrame();
        double[] w = shape(f, numbers(want[1]));
        double[] g = shape(f, numbers(got[1]));
        assertEquals(w[0], g[0], TOL, name + ": primary half-length");
        assertEquals(w[1], g[1], TOL, name + ": secondary half-length");
        if (direction) {
            assertEquals(0.0, Math.IEEEremainder(g[2] - w[2], Math.PI), TOL, name + ": direction");
        }
    }

    /**
     * The half-lengths of the axes of an Ellipse with the given {@code points} line values, and
     * the direction of its primary axis at the centre from the second axis.
     */
    private static double[] shape(Frame f, double[] v) {
        double[] cen = {v[1], v[4]};
        double[] pa = {v[2], v[5]};
        double[] pb = {v[3], v[6]};
        return new double[] {f.distance(cen, pa), f.distance(cen, pb), f.axAngle(cen, pa, 2)};
    }

    private static Circle circle() {
        return new Circle(new Frame(2), new double[] {1.0, 2.0}, 3.0, null);
    }

    private static Ellipse ellipse(Frame f, double cx, double cy, double a, double b, double angle) {
        return new Ellipse(f, 1, new double[] {cx, cy}, new double[] {a, b}, new double[] {angle}, null);
    }

    private static SkyFrame icrs() {
        SkyFrame s = new SkyFrame();
        s.setSystem("ICRS");
        return s;
    }

    @Test
    void fitsInAPlainFrameMatchC() {
        Ellipse e = ellipse(new Frame(2), 1.0, 2.0, 2.0, 1.0, 0.3);
        checkNear("fit_circle_of_circle", circle(), true);
        checkNear("fit_circle_of_ellipse", e, true);
        checkNear("fit_ellipse_of_ellipse", e, false);
    }

    @Test
    void anEllipseFittedToACircleHasItsRadius() {
        checkEllipseFit("fit_ellipse_of_circle", circle(), false);
    }

    private static double[] numbers(String line) {
        String[] words = line.split(" ", -1);
        double[] out = new double[words.length - 1];
        for (int i = 1; i < words.length; i++) {
            out[i - 1] = Double.parseDouble(words[i]);
        }
        return out;
    }

    @Test
    void fitsToMappedRegionsMatchC() {
        Region stretched = circle().mapRegionInto(
                        new WinMap(
                                2,
                                new double[] {0.0, 0.0},
                                new double[] {1.0, 1.0},
                                new double[] {0.0, 0.0},
                                new double[] {1.0, 1.3}),
                        new Frame(2));
        checkNear("fit_circle_of_stretched_circle", stretched, true);
        checkNear("fit_ellipse_of_stretched_circle", stretched, false);
        Region sheared = ellipse(new Frame(2), 1.0, 2.0, 2.0, 1.0, 0.3)
                .mapRegionInto(new MatrixMap(2, 2, new double[][] {{1.0, 0.3}, {0.0, 1.0}}), new Frame(2));
        checkNear("fit_ellipse_of_sheared_ellipse", sheared, false);
    }

    @Test
    void fitsOnTheSkyMatchC() {
        Circle cs = new Circle(icrs(), new double[] {1.0, 1.3}, 0.1, null);
        Ellipse es = ellipse(icrs(), 1.0, 0.8, 0.2, 0.1, 0.3);
        checkNear("fit_circle_of_sky_circle", cs, true);
        checkNear("fit_ellipse_of_sky_ellipse", es, false);
        checkNear("fit_circle_of_sky_ellipse", es, true);
    }

    @Test
    void aMeshWithNoGoodValueFitsNoCircle() {
        // astBestCircle returns NULL when no axis value of the mesh is good.
        double bad = AstObject.AST__BAD;
        double[][] mesh = {{bad, bad}, {bad, bad}};
        Region u = circle().getUncFrm(FrameSet.AST__CURRENT);
        assertNull(Circle.bestCircle(mesh, new double[] {1.0, 2.0}, u));
    }

    @Test
    void aMeshWithNoPointAwayFromTheCentreIsAnError() {
        // C reads beyond its arrays when every mesh point is bad or at the centre.
        double[][] mesh = {{1.0, 1.0}, {2.0, 2.0}};
        Region u = circle().getUncFrm(FrameSet.AST__CURRENT);
        AstException e = org.junit.jupiter.api.Assertions.assertThrows(
                AstException.class, () -> Ellipse.bestEllipse(mesh, new double[] {1.0, 2.0}, u));
        assertEquals(AstException.AST__INTER, e.getStatus());
    }

    @Test
    void theFittedRegionTakesTheFrameAndUncertaintyGiven() {
        Ellipse e = ellipse(icrs(), 1.0, 0.8, 0.2, 0.1, 0.3);
        Region u = e.getUncFrm(FrameSet.AST__CURRENT);
        double[][] pts = e.getRegionPoints();
        Region fitted = Ellipse.bestEllipse(e.regMesh(), new double[] {pts[0][0], pts[1][0]}, u);
        assertInstanceOf(SkyFrame.class, fitted.getRegionFrame());
        assertEquals("ICRS", fitted.getSystem());
        assertEquals(u.astClass(), fitted.getUnc(false).astClass());
    }
}
