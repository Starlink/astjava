package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_time_map_oracle.rs}: single TimeMap conversion steps
 * against the C library, from {@code oracle/time_map.json} (C probe {@code time_map_probe.c}).
 * Each case is a TimeMap holding one step, applied to two points in both directions, which pins
 * the step arithmetic of timemap.c {@code Transform} and the arguments {@code AddArgs} derives.
 * Every step is run with points that are small offsets from an MJD offset of 55000.5, and with
 * absolute points and zero offsets.
 *
 * <p>The steps that call no elementary function are arithmetic only, so their values must equal
 * C's; TT to TDB (whose {@code Rcc} sums sines) and LMST to LAST (whose {@code palEqeqx} calls
 * trigonometric functions) are compared within libm rounding.
 */
class TimeMapOracleTest {

    /** The steps whose arithmetic calls elementary functions. */
    private static final List<String> LIBM_STEPS = Arrays.asList("TTTOTDB", "TDBTOTT", "LMSTTOLAST", "LASTTOLMST");

    private static double[] doubles(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = ProbeData.toDouble(n.get(i));
        }
        return v;
    }

    private static boolean matches(double[] got, double[] want, boolean libm) {
        for (int i = 0; i < want.length; i++) {
            boolean ok = libm ? CValues.cValueEq(got[i], want[i]) : got[i] == want[i];
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Test
    void stepsMatchTheCLibrary() {
        JsonNode cases = ProbeData.load("time_map").get("CASES");
        List<String> failures = new ArrayList<>();
        int exact = 0;
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            String code = c.get(1).asText();
            TimeMap map = new TimeMap();
            map.timeAdd(code, doubles(c.get(2)));
            double[] points = doubles(c.get(3));
            boolean libm = LIBM_STEPS.contains(code);
            if (!libm) {
                exact++;
            }
            for (int d = 0; d < 2; d++) {
                boolean forward = d == 0;
                double[] want = doubles(c.get(4 + d));
                double[] got = map.tran1(2, points, forward);
                if (!matches(got, want, libm)) {
                    failures.add(name + (forward ? " forward" : " inverse") + ": got " + Arrays.toString(got)
                            + ", C gives " + Arrays.toString(want));
                }
            }
        }
        assertEquals(62, cases.size());
        assertEquals(50, exact);
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + 2 * cases.size() + " directions differ from C:\n"
                        + String.join("\n", failures));
    }
}
