package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the simplification tests of Rust {@code tests/test_regions.rs}: an uncertainty set
 * on a Prism carried to the Region it simplifies to (prism.c {@code Simplify}); the MeshSize and
 * FillFactor of two Boxes merged in parallel (box.c {@code MergeBox}); and a sky Circle whose
 * System has changed, simplified, transforming points as the Circle does.
 */
class RegionsTest {

    private static final double BAD = AstObject.AST__BAD;

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

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    /** {@code points} ({@code [axis][point]}) converted from ICRS to Galactic. */
    private static double[][] toGalactic(double[][] points) {
        FrameSet conversion = sky("ICRS").convert(sky("GALACTIC"), "");
        return conversion.transform(points, points[0].length, true);
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
}
