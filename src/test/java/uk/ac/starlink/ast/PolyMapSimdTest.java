package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_polymap_simd.rs} where it checks that PolyMap's bulk
 * evaluation of many points gives each point what it gets alone, and that bad values in a bulk
 * transformation come back bad. Java's bulk evaluation is Rust's scalar blocked loop ({@code
 * poly_transform_blocked}), so the results must be identical, where Rust's SIMD kernel, which
 * fuses multiplies and adds, is allowed a last-bit difference.
 *
 * <p>Rust takes the PolyMap from the simplified LSST FrameSet. Here it is the distortion PolyMap of
 * {@code simplify/lsst.map}, the same FrameSet's Mapping, and the test points are passed first
 * through the linear PolyMap before it, so that they lie in the distortion's domain as Rust's
 * merged PolyMap's do. {@code simd_falls_back_below_threshold} and {@code
 * simd_inverse_falls_back_below_threshold} test when Rust's hardware-SIMD kernel runs, and are
 * deferred ({@code hardware SIMD}).
 */
class PolyMapSimdTest {

    private static final double BAD = AstObject.AST__BAD;

    /** The PolyMaps of a Mapping, in order, each a copy that can be changed. */
    private static void polyMaps(Mapping m, List<PolyMap> found) {
        if (m instanceof PolyMap) {
            found.add((PolyMap) m.copy());
        } else if (m instanceof CmpMap) {
            for (Mapping part : m.decompose(null, null)) {
                polyMaps(part, found);
            }
        }
    }

    /** The two PolyMaps of the LSST Mapping: the linear normalisation, then the distortion. */
    static List<PolyMap> lsstPolyMaps() {
        Mapping map = (Mapping) ChannelText.read(Fixtures.read("simplify/lsst.map"));
        List<PolyMap> found = new ArrayList<>();
        polyMaps(map, found);
        assertEquals(2, found.size(), "PolyMaps in lsst.map");
        return found;
    }

    /** The distortion PolyMap of the LSST Mapping. */
    static PolyMap loadLsstPolymap() {
        return lsstPolyMaps().get(1);
    }

    /**
     * {@code n} pixel positions (100 + 0.01 i, 200 + 0.005 i), as Rust's test makes them, passed
     * through the LSST Mapping's linear PolyMap into the distortion's domain.
     */
    static double[][] makePoints(int n) {
        double[][] pix = new double[2][n];
        for (int i = 0; i < n; i++) {
            pix[0][i] = 100.0 + i * 0.01;
            pix[1][i] = 200.0 + i * 0.005;
        }
        return lsstPolyMaps().get(0).transform(pix, n, true);
    }

    /** The points transformed one at a time. */
    private static double[][] onePerCall(Mapping m, double[][] pts, boolean forward) {
        int n = pts[0].length;
        double[][] out = new double[2][n];
        for (int i = 0; i < n; i++) {
            double[][] one = m.transform(new double[][] {{pts[0][i]}, {pts[1][i]}}, 1, forward);
            out[0][i] = one[0][0];
            out[1][i] = one[1][0];
        }
        return out;
    }

    private static void assertSameBits(double[][] want, double[][] got, int i, String what) {
        for (int axis = 0; axis < 2; axis++) {
            assertEquals(
                    Double.doubleToRawLongBits(want[axis][i]),
                    Double.doubleToRawLongBits(got[axis][i]),
                    what + " point " + i + " axis " + axis + ": " + want[axis][i] + " vs " + got[axis][i]);
        }
    }

    private static double[][] copyOf(double[][] pts) {
        return new double[][] {pts[0].clone(), pts[1].clone()};
    }

    @Test
    void simdMatchesScalarBulk() {
        PolyMap map = loadLsstPolymap();
        double[][] pts = makePoints(1024);
        double[][] bulk = map.transform(pts, 1024, true);
        double[][] single = onePerCall(map, pts, true);
        for (int i = 0; i < 1024; i++) {
            assertSameBits(single, bulk, i, "forward");
        }
    }

    @Test
    void simdInverseMatchesScalarBulk() {
        PolyMap map = loadLsstPolymap();
        assertTrue(map.getIterInverse());
        double[][] sky = map.transform(makePoints(1024), 1024, true);
        double[][] bulk = map.transform(sky, 1024, false);
        double[][] single = onePerCall(map, sky, false);
        for (int i = 0; i < 1024; i++) {
            assertSameBits(single, bulk, i, "inverse");
        }
    }

    @Test
    void simdInversePropagatesBadValue() {
        PolyMap map = loadLsstPolymap();
        double[][] target = copyOf(map.transform(makePoints(200), 200, true));
        target[0][0] = BAD;
        target[1][3] = BAD;
        target[0][100] = BAD;
        target[1][100] = BAD;
        double[][] bulk = map.transform(target, 200, false);
        for (int i : new int[] {0, 3, 100}) {
            assertEquals(BAD, bulk[0][i], "point " + i + " axis 0");
            assertEquals(BAD, bulk[1][i], "point " + i + " axis 1");
        }
        double[][] single = onePerCall(map, target, false);
        for (int i : new int[] {1, 2, 99, 101}) {
            assertSameBits(single, bulk, i, "neighbour");
        }
    }

    @Test
    void simdPropagatesBadValue() {
        PolyMap map = loadLsstPolymap();
        double[][] pts = copyOf(makePoints(200));
        pts[0][0] = BAD;
        pts[1][3] = BAD;
        pts[0][100] = BAD;
        pts[1][100] = BAD;
        double[][] out = map.transform(pts, 200, true);
        for (int i : new int[] {0, 3, 100}) {
            assertEquals(BAD, out[0][i], "point " + i + " axis 0");
            assertEquals(BAD, out[1][i], "point " + i + " axis 1");
        }
        assertTrue(Double.isFinite(out[0][1]) && Double.isFinite(out[1][1]), "neighbour point became non-finite");
        assertTrue(out[0][1] != BAD && out[1][1] != BAD, "neighbour point became bad");
    }
}
