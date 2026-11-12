package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_is_linear.rs}: the read-only IsLinear attribute is what
 * C's {@code GetIsLinear} overrides report, with C's answers from {@code astGetI(map,
 * "IsLinear")}.
 *
 * <p>The test's FrameSet case is added in Task 16; its Circle case needs a class outside the first phase.
 */
class IsLinearTest {

    @Test
    void isLinearMatchesTheCLibrary() {
        Map<String, Mapping> cases = new LinkedHashMap<>();
        Map<String, Boolean> want = new LinkedHashMap<>();
        cases.put("unitmap", new UnitMap(2));
        want.put("unitmap", true);
        cases.put("zoommap", new ZoomMap(2, 2.0));
        want.put("zoommap", true);
        cases.put("shiftmap", new ShiftMap(new double[] {1.0, 2.0}));
        want.put("shiftmap", true);
        cases.put("winmap", new WinMap(new double[] {1.0, 2.0}, new double[] {2.0, 3.0}));
        want.put("winmap", true);
        cases.put("matrixmap", new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {3.0, 4.0}}));
        want.put("matrixmap", true);
        cases.put("permmap", perm());
        want.put("permmap", false);
        cases.put("cmpmap_linear", new CmpMap(new ZoomMap(2, 2.0), new ShiftMap(new double[] {1.0, 2.0}), true));
        want.put("cmpmap_linear", true);
        cases.put("cmpmap_with_permmap", new CmpMap(new ZoomMap(2, 2.0), perm(), true));
        want.put("cmpmap_with_permmap", false);
        cases.put("sphmap", new SphMap());
        want.put("sphmap", false);
        cases.put("polymap_linear", new PolyMap(1, 1, 1, new double[] {1.0, 1.0, 1.0}, 0, null));
        want.put("polymap_linear", false);
        for (Map.Entry<String, Mapping> c : cases.entrySet()) {
            assertEquals(want.get(c.getKey()), c.getValue().getB("IsLinear"), c.getKey());
            assertEquals(want.get(c.getKey()), c.getValue().isLinear(), c.getKey());
        }
    }

    private static PermMap perm() {
        return new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
    }
}
