package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_nan_outputs.rs}: no transformation gives a NaN output; a
 * NaN input is taken to be bad. Each Mapping is given points with a NaN on one input axis at a
 * time, and transformed in each direction it defines.
 *
 * <p>The Mappings of the other first-phase classes are added to {@link #mappings} by the tasks that
 * port them: SphMap and WcsMap in Task 11, PolyMap in Task 12, SlaMap and TimeMap in Task 13.
 * Those of the Rust test's LutMap, MathMap, UnitNormMap, SpecMap, PcdMap and TranMap are outside
 * the first phase.
 */
class NanOutputsTest {

    private static Map<String, Mapping> mappings() {
        Map<String, Mapping> m = new LinkedHashMap<>();
        m.put("UnitMap", new UnitMap(2));
        m.put("ZoomMap", new ZoomMap(2, 2.0));
        m.put("ShiftMap", new ShiftMap(new double[] {1.0, 2.0}));
        m.put("WinMap", new WinMap(new double[] {1.0, -2.0}, new double[] {2.0, 3.0}));
        m.put("PermMap", new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null));
        m.put("MatrixMap diagonal", new MatrixMap(2, 2, new double[][] {{2.0, 0.0}, {0.0, 3.0}}));
        m.put("MatrixMap full", new MatrixMap(2, 2, new double[][] {{1.0, 0.5}, {0.25, 2.0}}));
        m.put("CmpMap", new CmpMap(new ZoomMap(1, 2.0), new UnitMap(1), false));
        return m;
    }

    @Test
    void noTransformationGivesNan() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Mapping> e : mappings().entrySet()) {
            Mapping map = e.getValue();
            for (boolean forward : new boolean[] {true, false}) {
                if (!(forward ? map.getTranForward() : map.getTranInverse())) {
                    continue;
                }
                int n = forward ? map.getNin() : map.getNout();
                for (int axis = 0; axis < n; axis++) {
                    double[][] in = new double[n][1];
                    for (int i = 0; i < n; i++) {
                        in[i][0] = 0.1 + 0.2 * i;
                    }
                    in[axis][0] = Double.NaN;
                    double[][] out = map.transform(in, 1, forward);
                    for (double[] o : out) {
                        if (Double.isNaN(o[0])) {
                            failures.add(e.getKey() + " forward=" + forward + " NaN on axis " + (axis + 1) + ": "
                                    + Arrays.deepToString(out));
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
