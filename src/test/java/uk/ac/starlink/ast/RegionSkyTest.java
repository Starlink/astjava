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
 * Boxes, Circles and Polygons on a SkyFrame that straddle RA 0 or hold a celestial pole give C's
 * results, from {@code probes/region_sky_probe.c}: which test points they hold, their bounds
 * ({@code astGetRegionBounds}), the masks of the inside and the outside of each over an all-sky
 * grid of 24 by 12 pixels, and the overlaps of such Regions with each other; and for a Polygon,
 * whether its vertices were put in the other order (Negated and the vertices it holds).
 *
 * <p>The bounds of a Box are its centre plus and minus axis distances, normalised by whole turns,
 * and are those of C to the last bit; so are those of a Polygon, which are the axis distances of
 * its vertices from the first, widened to whole turns or a pole by {@code astNormBox}. Those of a Circle come from its boundary mesh, each point
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
 * libraries may place a point, and beyond the {@code 1e-7} of an edge's length within which a
 * point counts as on the edge of a Polygon; and that none of them, nor a pole, lies within that
 * distance of the great circle from a Polygon's interior point through a vertex, where the count
 * of edges crossed on the way to it changes.
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

    /** A Polygon of the vertices given as longitude, latitude pairs. */
    private static Polygon polygon(double... pts) {
        int n = pts.length / 2;
        double[] lon = new double[n];
        double[] lat = new double[n];
        for (int i = 0; i < n; i++) {
            lon[i] = pts[2 * i];
            lat[i] = pts[2 * i + 1];
        }
        return new Polygon(icrs(), n, lon, lat, null);
    }

    /** A quadrilateral straddling RA 0. */
    private static final double[] V_RA0 = {6.0, 0.5, 0.25, 0.52, 0.27, 0.1, 6.0, 0.12};

    /** The quadrilateral's vertices in the other order, which make the Polygon the rest of the sky. */
    private static final double[] V_RA0_REVERSED = {6.0, 0.12, 0.27, 0.1, 0.25, 0.52, 6.0, 0.5};

    /** A triangle holding the north pole. */
    private static final double[] V_POLE = {4.7, 1.2, 2.6, 1.25, 0.5, 1.15};

    /** A triangle holding the south pole. */
    private static final double[] V_SOUTH = {1.0, -1.25, 3.0, -1.2, 5.0, -1.3};

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
        if (r instanceof Box || r instanceof Polygon) {
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

    /** The probe's vertices line: Negated, then the vertices the Polygon holds. */
    private static String vertices(Polygon p) {
        return "negated " + (p.getNegated() ? 1 : 0) + "\n" + RegionProbeText.points(p);
    }

    @Test
    void polygonsMatchC() {
        Polygon ra0 = polygon(V_RA0);
        check(PROBE, "polygon_ra0_vertices", vertices(ra0));
        checkRegion("polygon_ra0", ra0, RA0);
        Polygon reversed = polygon(V_RA0_REVERSED);
        check(PROBE, "polygon_ra0_reversed_vertices", vertices(reversed));
        checkRegion("polygon_ra0_reversed", reversed, RA0);
        Polygon pole = polygon(V_POLE);
        check(PROBE, "polygon_pole_vertices", vertices(pole));
        checkRegion("polygon_pole", pole, NORTH);
        Polygon south = polygon(V_SOUTH);
        check(PROBE, "polygon_south_vertices", vertices(south));
        checkRegion("polygon_south", south, SOUTH);
    }

    @Test
    void polygonOverlapsMatchC() {
        Polygon ra0 = polygon(V_RA0);
        Polygon pole = polygon(V_POLE);
        check(PROBE, "overlap_polygon_box_ra0", overlap(ra0, boxRa0()));
        check(PROBE, "overlap_polygon_circle_ra0", overlap(ra0, circleRa0()));
        check(PROBE, "overlap_polygon_circle_inside_ra0", overlap(ra0, circle(0.1, 0.35, 0.05)));
        check(PROBE, "overlap_polygon_reversed_ra0", overlap(ra0, polygon(V_RA0_REVERSED)));
        check(PROBE, "overlap_polygon_reversed_box_ra0", overlap(polygon(V_RA0_REVERSED), boxRa0()));
        Region neg = (Region) ra0.copy();
        neg.negate();
        check(PROBE, "overlap_polygon_negation_ra0", overlap(ra0, neg));
        check(PROBE, "overlap_polygon_circle_pole", overlap(pole, circlePole()));
        check(PROBE, "overlap_polygon_box_pole_inside", overlap(pole, box(2.0, PIBY2, 1.0, PIBY2 - 0.1)));
        check(PROBE, "overlap_polygon_circle_south", overlap(pole, circleSouth()));
        check(PROBE, "overlap_polygon_poles", overlap(pole, polygon(V_SOUTH)));
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

    /**
     * The least distance of a point from an edge of a sky Polygon, and from the great circle from
     * the Polygon's interior point through a vertex wherever that reaches the point.
     */
    private static double polygonMargin(Polygon poly, double[] p) {
        return RegionMaskingTest.clearance(poly, new double[][] {{p[0]}, {p[1]}}, 1, true);
    }

    private static void assertClear(double[] pts, double[][] circles, double[][] boxes, Polygon... polys) {
        for (int k = 0; k < pts.length; k += 2) {
            double[] p = {pts[k], pts[k + 1]};
            for (Polygon poly : polys) {
                double m = polygonMargin(poly, p);
                assertTrue(m > 1e-6, "point " + p[0] + " " + p[1] + " is " + m + " from a polygon");
            }
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
        Polygon[] polys = {polygon(V_RA0), polygon(V_RA0_REVERSED), polygon(V_POLE), polygon(V_SOUTH)};
        assertClear(RA0, new double[][] {circles[0]}, new double[][] {boxes[0]}, polys[0], polys[1]);
        assertClear(NORTH, new double[][] {circles[1]}, new double[][] {boxes[1]}, polys[2]);
        assertClear(SOUTH, new double[][] {circles[2]}, new double[0][], polys[3]);
        assertClear(new double[] {0.0, PIBY2, 0.0, -PIBY2}, new double[0][], new double[0][], polys);
        assertClear(NEARPOLE, new double[][] {circles[3]}, new double[0][]);
        double[] pixels = new double[2 * 24 * 12];
        int k = 0;
        for (int j = 1; j <= 12; j++) {
            for (int i = 1; i <= 24; i++) {
                pixels[k++] = (i - 0.5) * Math.PI / 12;
                pixels[k++] = -PIBY2 + (j - 0.5) * Math.PI / 12;
            }
        }
        assertClear(pixels, circles, boxes, polys);
        assertEquals(2 * 24 * 12, k);
    }
}
