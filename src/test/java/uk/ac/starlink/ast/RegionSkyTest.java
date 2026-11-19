package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.RegionProbeText.check;
import static uk.ac.starlink.ast.RegionProbeText.checkClose;
import static uk.ac.starlink.ast.RegionProbeText.overlap;
import static uk.ac.starlink.ast.RegionProbeText.tran;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Boxes and Circles on a SkyFrame that straddle RA 0 or hold a celestial pole give C's results,
 * from {@code probes/region_sky_probe.c}: which test points they hold, their bounds ({@code
 * astGetRegionBounds}), the masks of the inside and the outside of each over an all-sky grid of
 * 24 by 12 pixels, and the overlaps of such Regions with each other.
 *
 * <p>The bounds of a Box are its centre plus and minus axis distances, normalised by whole turns,
 * and are those of C to the last bit. Those of a Circle come from its boundary mesh, each point
 * placed by {@code astOffset2} through {@code sin}, {@code cos}, {@code asin} and {@code atan2},
 * each within 1 ulp of the true value in {@code java.lang.Math} and in the C library, so that the
 * two may differ by 2 ulp in each: about {@code 1e-15} in a unit vector, a few times that after
 * the products that rotate it. The longitude of a point is the {@code atan2} of components whose
 * modulus is the cosine of its latitude, at least {@code 0.01} for these meshes (the point of a
 * mesh nearest a pole is 0.01 radians from it), which amplifies the error by at most 100; the
 * pole's own bounds are set exactly by {@code astNormBox}. That bounds the difference by about
 * {@code 100 * 10 * 1e-15 = 1e-12}, so {@link #SKY_TOL} is {@code 1e-11}.
 *
 * <p>Membership, the masks and the overlaps are compared exactly. {@link
 * #pointsAndPixelsAreClearOfTheBoundaries} shows that every test point and pixel centre is at
 * least {@code 1e-6} radians from each boundary, far beyond the few ulps by which the two
 * libraries may place a point.
 */
class RegionSkyTest {

    private static final Map<String, String> PROBE = ProbeOutput.load("region_sky_probe");

    /** The tolerance for the bounds of a Circle (see the class description). */
    private static final double SKY_TOL = 1e-11;

    private static final double PIBY2 = 1.5707963267948966;

    private static final double[] RA0 = {
        0.05, 0.3, 6.25, 0.3, 6.1, 0.35, 0.15, 0.2, 3.1, 0.3, 0.05, -0.3, 6.25, 0.6, 0.4, 0.3
    };

    private static final double[] NORTH = {
        0.0, PIBY2, 1.0, 1.5, 4.1, 1.5, 2.5, 1.3, 5.5, 1.3, 1.0, 1.0, 4.1, 1.2, 1.0, -1.5
    };

    private static final double[] NEARPOLE = {
        0.0, 1.5707963, 3.14159, 1.5707963, 1.0, 1.5, 4.1, 1.5, 2.5, 1.3, 5.5, 1.3, 0.2, 1.3, 0.0, 1.2
    };

    private static final double[] SOUTH = {0.0, -PIBY2, 2.0, -1.5, 5.1, -1.5, 5.1, -1.2, 2.0, -1.2, 2.0, 1.5};

    private static SkyFrame icrs() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        return sf;
    }

    private static Box box(double cx, double cy, double px, double py) {
        return new Box(icrs(), 0, new double[] {cx, cy}, new double[] {px, py}, null);
    }

    private static Circle circle(double cx, double cy, double r) {
        return new Circle(icrs(), new double[] {cx, cy}, r, null);
    }

    private static Box boxRa0() {
        return box(6.2, 0.3, 0.1, 0.45);
    }

    private static Box boxPole() {
        return box(0.0, PIBY2, 3.0, PIBY2 - 0.2);
    }

    private static Circle circleRa0() {
        return circle(6.2, 0.3, 0.25);
    }

    private static Circle circlePole() {
        return circle(1.0, 1.4, 0.3);
    }

    private static Circle circleSouth() {
        return circle(2.0, -1.45, 0.2);
    }

    private static Circle circleNearPole() {
        return circle(0.0, PIBY2 - 0.3, 0.29);
    }

    /** The probe's mask line: the count, then the array as integers. */
    private static String mask(Region r0, boolean inside) {
        Region r = (Region) r0.copy();
        Mapping map = new WinMap(
                2,
                new double[] {0.0, -PIBY2},
                new double[] {2 * Math.PI, PIBY2},
                new double[] {0.5, 0.5},
                new double[] {24.5, 12.5});
        double[] d = new double[24 * 12];
        for (int i = 0; i < d.length; i++) {
            d[i] = i % 3;
        }
        int count = r.maskD(map, inside, 2, new int[] {1, 1}, new int[] {24, 12}, d, 9.0);
        StringBuilder sb = new StringBuilder("mask ").append(count);
        for (double v : d) {
            sb.append(' ').append((int) v);
        }
        return sb.append('\n').toString();
    }

    private static void checkRegion(String name, Region r, double[] pts) {
        String bounds = RegionProbeText.bounds(r);
        String text = tran(r, 2, pts) + bounds + mask(r, true) + mask(r, false);
        if (r instanceof Box) {
            check(PROBE, name, text);
        } else {
            checkClose(PROBE, name, text, SKY_TOL);
        }
    }

    @Test
    void boxesMatchC() {
        checkRegion("box_ra0", boxRa0(), RA0);
        checkRegion("box_pole", boxPole(), NORTH);
    }

    @Test
    void circlesMatchC() {
        checkRegion("circle_ra0", circleRa0(), RA0);
        checkRegion("circle_pole", circlePole(), NORTH);
        checkRegion("circle_south", circleSouth(), SOUTH);
        checkRegion("circle_near_pole", circleNearPole(), NEARPOLE);
    }

    @Test
    void overlapsMatchC() {
        Box boxRa0 = boxRa0();
        Box boxPole = boxPole();
        Circle circleRa0 = circleRa0();
        Circle circlePole = circlePole();
        Box boxPole2 = box(2.0, PIBY2, 1.0, PIBY2 - 0.1);
        check(PROBE, "overlap_box_circle_ra0", overlap(boxRa0, circleRa0));
        check(PROBE, "overlap_circles_ra0", overlap(circleRa0, circle(0.1, 0.35, 0.2)));
        check(PROBE, "overlap_circle_far_ra0", overlap(circleRa0, circle(3.0, 0.3, 0.2)));
        check(PROBE, "overlap_circle_inside_ra0", overlap(circleRa0, circle(6.25, 0.3, 0.05)));
        check(PROBE, "overlap_circle_self_ra0", overlap(circleRa0, circle(6.2, 0.3, 0.25)));
        Region neg = (Region) circleRa0.copy();
        neg.negate();
        check(PROBE, "overlap_circle_negation_ra0", overlap(circleRa0, neg));
        check(PROBE, "overlap_box_circle_pole", overlap(boxPole, circlePole));
        check(PROBE, "overlap_boxes_pole", overlap(boxPole, boxPole2));
        check(PROBE, "overlap_circles_pole", overlap(circlePole, circleNearPole()));
        check(PROBE, "overlap_circle_inside_pole", overlap(circlePole, circle(3.0, 1.55, 0.01)));
        check(PROBE, "overlap_circle_box_pole_inside", overlap(circlePole, boxPole2));
        check(PROBE, "overlap_poles", overlap(circlePole, circleSouth()));
    }

    /** The distance of a point from the boundary of a sky Circle. */
    private static double circleMargin(double[] c, double r, double[] p) {
        return Math.abs(icrs().distance(c, p) - r);
    }

    /** The least distance of a point from the limits of a sky Box, on either axis. */
    private static double boxMargin(double[] c, double[] corner, double[] p) {
        SkyFrame sf = icrs();
        double margin = Double.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            double half = Math.abs(sf.axDistance(i + 1, corner[i], c[i]));
            double off = Math.abs(sf.axDistance(i + 1, c[i], p[i]));
            margin = Math.min(margin, Math.abs(off - half));
        }
        return margin;
    }

    private static void assertClear(double[] pts, double[][] circles, double[][] boxes) {
        for (int k = 0; k < pts.length; k += 2) {
            double[] p = {pts[k], pts[k + 1]};
            for (double[] c : circles) {
                double m = circleMargin(new double[] {c[0], c[1]}, c[2], p);
                assertTrue(m > 1e-6, "point " + p[0] + " " + p[1] + " is " + m + " from a circle");
            }
            for (double[] b : boxes) {
                double m = boxMargin(new double[] {b[0], b[1]}, new double[] {b[2], b[3]}, p);
                assertTrue(m > 1e-6, "point " + p[0] + " " + p[1] + " is " + m + " from a box");
            }
        }
    }

    @Test
    void pointsAndPixelsAreClearOfTheBoundaries() {
        double[][] circles = {
            {6.2, 0.3, 0.25}, {1.0, 1.4, 0.3}, {2.0, -1.45, 0.2}, {0.0, PIBY2 - 0.3, 0.29},
        };
        double[][] boxes = {{6.2, 0.3, 0.1, 0.45}, {0.0, PIBY2, 3.0, PIBY2 - 0.2}};
        assertClear(RA0, new double[][] {circles[0]}, new double[][] {boxes[0]});
        assertClear(NORTH, new double[][] {circles[1]}, new double[][] {boxes[1]});
        assertClear(SOUTH, new double[][] {circles[2]}, new double[0][]);
        assertClear(NEARPOLE, new double[][] {circles[3]}, new double[0][]);
        double[] pixels = new double[2 * 24 * 12];
        int k = 0;
        for (int j = 1; j <= 12; j++) {
            for (int i = 1; i <= 24; i++) {
                pixels[k++] = (i - 0.5) * Math.PI / 12;
                pixels[k++] = -PIBY2 + (j - 0.5) * Math.PI / 12;
            }
        }
        assertClear(pixels, circles, boxes);
        assertEquals(2 * 24 * 12, k);
    }
}
