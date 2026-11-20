package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_region_bounded_oracle.rs}: whether Regions are bounded,
 * and their bounding boxes, against C where a class's own {@code GetBounded} or {@code
 * RegBaseBox} decides them: a PointList, negated in a Cartesian Frame and in a SkyFrame (region.c
 * {@code GetBounded}); a Prism, negated (prism.c {@code GetBounded}); an Interval excluding a
 * range on each axis (interval.c {@code RegBaseBox}); and a sky PointList either side of RA 0,
 * with its default uncertainty (pointlist.c {@code RegBaseBox}) ({@code
 * oracle/region_bounded.json}, from {@code tools/oracle_fixtures/region_bounded_probe.c}).
 *
 * <p>Every value is compared exactly. The sky values come from SkyAxis distances and offsets,
 * which normalise by a remainder and the addition or subtraction of a whole turn, and the
 * uncertainty's from sums and products of those with 0.5 and 0.5e-6: correctly rounded operations
 * only, with no call of an elementary function, so they are the same on every platform.
 */
class RegionBoundedOracleTest {

    private static final JsonNode DATA = ProbeData.load("region_bounded");

    private static final double[][] POINTS = {{0.0, 0.0}, {1.0, 2.0}, {3.0, 1.0}};

    private static PointList pointList(Frame frm, double[][] pts) {
        double[][] axes = new double[2][pts.length];
        for (int i = 0; i < pts.length; i++) {
            axes[0][i] = pts[i][0];
            axes[1][i] = pts[i][1];
        }
        return new PointList(frm, pts.length, axes, null);
    }

    private static SkyFrame icrs() {
        SkyFrame sf = new SkyFrame();
        sf.setSystem("ICRS");
        return sf;
    }

    private static void assertBounds(JsonNode want, double[][] got, double tol) {
        for (int k = 0; k < 2; k++) {
            double[] w = {
                ProbeData.toDouble(want.get(k).get(0)),
                ProbeData.toDouble(want.get(k).get(1))
            };
            assertArrayEquals(w, got[k], tol, k == 0 ? "lower" : "upper");
        }
    }

    @Test
    void negatedPointlistIsUnboundedUnlessOnTheSky() {
        PointList pl = pointList(new Frame(2), POINTS);
        assertEquals(DATA.get("POINTLIST").booleanValue(), pl.getBounded());
        pl.negate();
        assertEquals(DATA.get("POINTLIST_NEGATED").booleanValue(), pl.getBounded());
        PointList sky = pointList(icrs(), POINTS);
        sky.negate();
        assertEquals(DATA.get("SKY_POINTLIST_NEGATED").booleanValue(), sky.getBounded());
    }

    @Test
    void negatedPrismTestsNegatedComponents() {
        Box bx = new Box(new Frame(2), 1, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
        Interval iv = new Interval(new Frame(1), new double[] {0.0}, new double[] {1.0}, null);
        Prism prism = new Prism(bx, iv);
        assertEquals(DATA.get("PRISM").booleanValue(), prism.getBounded());
        prism.negate();
        assertEquals(DATA.get("PRISM_NEGATED").booleanValue(), prism.getBounded());
    }

    @Test
    void excludedIntervalKeepsItsLimits() {
        Interval iv = new Interval(new Frame(2), new double[] {3.0, 4.0}, new double[] {1.0, 2.0}, null);
        assertBounds(DATA.get("EXCLUDED_BOUNDS"), iv.getRegionBounds(), 0.0);
    }

    @Test
    void skyPointlistBoxWrapsThroughRaZero() {
        PointList pl = pointList(icrs(), new double[][] {{0.01, 0.1}, {6.27, 0.2}, {6.2, 0.15}});
        assertBounds(DATA.get("SKY_BOUNDS"), pl.getRegionBounds(), 0.0);
        assertBounds(DATA.get("SKY_UNC_BOUNDS"), pl.getDefUnc().getRegionBounds(), 0.0);
    }
}
