package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_grid.rs}: the grid over the inside of a Region (C's
 * {@code astRegGrid}, which {@code astGetRegionMesh} gives when not asked for the boundary) of
 * Boxes in a Frame, a CmpFrame and a CmpFrame holding a SkyFrame (box.c {@code RegBaseGrid}),
 * Circles and a Polygon in a Frame and a SkyFrame (region.c {@code RegBaseGrid}), and a Prism,
 * whose boundary mesh is made from its components' meshes and grids (prism.c {@code RegBaseMesh}),
 * against {@code oracle/region_grid.json} (from {@code tools/oracle_fixtures/region_grid_probe.c}).
 *
 * <p>The points are normalised as {@code astGetRegionMesh} gives them. The grids of Boxes and of
 * Regions in a plain Frame are sums of bounds and multiples of steps, and which grid points a
 * Circle keeps is decided by squares and a square root, all exact or correctly rounded; they are
 * compared exactly. On the sky the grid's points are spaced along
 * parallels by {@code sin}, {@code cos} and {@code acos}, which may differ in the last place from
 * C's libm; a grid value is at most a few multiples of 0.2 radians, so an error of a few units in
 * the last place is below 1e-15, and the values are compared to {@code 1e-12}. Which points a sky
 * Region keeps could change with such an error only for a point within it of the boundary; {@link
 * #skyGridPointsAreClearOfTheBoundaries} shows every point of the covering grids at least 1e-6 from
 * the sky Circle's boundary. The Prism's mesh holds the plain Circle's mesh, {@code r cos(a)} and
 * {@code r sin(a)} with r = 1, within 2.2e-16 of C's; compared to {@code 1e-13}.
 */
class RegionGridTest {

    private static final JsonNode DATA = ProbeData.load("region_grid");

    private static final double D2R = Math.PI / 180.0;

    private static final double SKY_TOL = 1.0e-12;

    private static final double MESH_TOL = 1.0e-13;

    /** Checks the points of {@code r}, normalised as C's {@code astGetRegionMesh} gives them. */
    private static void check(String name, Region r, double[][] pts, double tol) {
        check(name, r.normalPoints(pts, pts[0].length, true), tol);
    }

    private static void check(String name, double[][] got, double tol) {
        JsonNode want = DATA.get(name);
        assertEquals(want.size(), got[0].length, name + ": number of points");
        for (int i = 0; i < want.size(); i++) {
            JsonNode row = want.get(i);
            assertEquals(row.size(), got.length, name + ": number of axes");
            for (int j = 0; j < row.size(); j++) {
                double w = ProbeData.toDouble(row.get(j));
                if (tol == 0.0) {
                    assertEquals(w, got[j][i], name + ": point " + i + " axis " + j);
                } else {
                    assertEquals(w, got[j][i], tol, name + ": point " + i + " axis " + j);
                }
            }
        }
    }

    private static Box box(Frame f, double[] lo, double[] hi) {
        return new Box(f, 1, lo, hi, null);
    }

    private static Circle skyCircle() {
        Circle c = new Circle(new SkyFrame(), 1, new double[] {0.0, 0.0}, new double[] {5.0 * D2R}, null);
        c.setMeshSize(50);
        return c;
    }

    @Test
    void regionGridsFollowTheCLibrary() {
        double[] lo = {0.0, 0.0};
        double[] hi = {1.0, 2.0};
        Box b = box(new Frame(2), lo, hi);
        b.setMeshSize(12);
        check("BOX_PLAIN", b, b.regGrid(), 0.0);

        Box bc = box(new CmpFrame(new Frame(1), new Frame(1)), lo, hi);
        bc.setMeshSize(12);
        check("BOX_CMP", bc, bc.regGrid(), 0.0);

        Box bs = box(
                new CmpFrame(new SkyFrame(), new Frame(1)), new double[] {-0.2, -0.1, 0.0}, new double[] {0.2, 0.1, 3.0
                });
        bs.setMeshSize(60);
        check("BOX_SKY_CMP", bs, bs.regGrid(), SKY_TOL);

        Circle circle = new Circle(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {1.0}, null);
        circle.setMeshSize(40);
        check("CIRCLE_PLAIN", circle, circle.regGrid(), 0.0);

        Circle sc = skyCircle();
        check("CIRCLE_SKY", sc, sc.regGrid(), SKY_TOL);

        double[] lon = {25.0 * D2R, 25.0 * D2R, 35.0 * D2R, 35.0 * D2R};
        double[] lat = {-5.0 * D2R, 5.0 * D2R, 5.0 * D2R, -5.0 * D2R};
        Polygon poly = new Polygon(new SkyFrame(), 4, lon, lat, null);
        poly.setMeshSize(40);
        check("POLYGON_SKY", poly, poly.regGrid(), SKY_TOL);

        Interval interval = new Interval(new Frame(1), new double[] {0.0}, new double[] {3.0}, null);
        Prism prism = new Prism(circle, interval);
        prism.setMeshSize(60);
        check("PRISM_MESH", prism, prism.regMesh(), MESH_TOL);
        check("PRISM_GRID", prism, prism.regGrid(), 0.0);
    }

    /**
     * Every point of the grids region.c {@code RegBaseGrid} tries over the sky Circle's bounding
     * box, at the sizes it tries them, is at least 1e-6 radians from the Circle's boundary. The
     * sizes are those C's loop uses: MeshSize, then scaled by MeshSize over the count kept.
     */
    @Test
    void skyGridPointsAreClearOfTheBoundaries() {
        Circle c = skyCircle();
        double r = 5.0 * D2R;
        SkyFrame sky = new SkyFrame();
        double[] lbnd = new double[2];
        double[] ubnd = new double[2];
        c.regBaseBox(lbnd, ubnd);
        Box box = new Box(sky, 1, lbnd, ubnd, null);
        int meshsize = c.getMeshSize();
        int np = meshsize;
        for (int ntry = 0; ntry < 3; ntry++) {
            box.setMeshSize(np);
            double[][] g = box.regBaseGrid();
            int kept = 0;
            for (int i = 0; i < g[0].length; i++) {
                double d = sky.distance(new double[] {0.0, 0.0}, new double[] {g[0][i], g[1][i]});
                assertTrue(Math.abs(d - r) > 1.0e-6, "grid point " + i + " at " + d);
                if (d <= r) {
                    kept++;
                }
            }
            if (Math.abs(kept - meshsize) / (double) meshsize < 0.05) {
                break;
            }
            np = kept == 0 ? np * 10 : (int) (np * ((double) meshsize / kept));
        }
    }
}
