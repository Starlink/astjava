package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_time_scale_set.rs}: which TimeScale, System and Unit
 * settings a TimeFrame rejects, and the TimeOrigin that results otherwise, checked against C
 * ({@code oracle/time_scale_set.json}, from {@code tools/oracle_fixtures/time_scale_set_probe.c}).
 *
 * <p>C's {@code SetTimeScale} rejects a time scale other than TT on a BEPOCH TimeFrame with {@code
 * AST__ATTIN}, and re-expresses the TimeOrigin in the new time scale; setting the System to BEPOCH
 * first sets the Unit to "yr" and the TimeScale to TT; {@code SetUnit} on a BEPOCH TimeFrame
 * rejects any Unit but "yr" (timeframe.c {@code SetTimeScale}, {@code ClearTimeScale}, {@code
 * OriginScale}, {@code SetSystem}, {@code SetUnit}).
 */
class TimeScaleSetTest {

    /** The steps of an action, separated by semicolons. */
    private static List<String> steps(String action) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (true) {
            int end = action.indexOf(';', start);
            if (end < 0) {
                out.add(action.substring(start));
                return out;
            }
            out.add(action.substring(start, end));
            start = end + 1;
        }
    }

    /** What the probe prints for {@code from} and {@code action}, less the TimeOrigin. */
    private static String run(String from, String action, double[] origin) {
        TimeFrame f = new TimeFrame();
        f.set("System=MJD");
        try {
            f.set("TimeScale=" + from);
            f.set("TimeOrigin=55000.5");
            for (String step : steps(action)) {
                if ("clear".equals(step)) {
                    f.clear("TimeScale");
                } else {
                    f.set(step);
                }
            }
        } catch (AstException e) {
            if (e.getStatus() == AstException.AST__ATTIN) {
                return "Err:Attin";
            }
            return e.getStatus() == AstException.AST__INCTS ? "Err:Incts" : "Err:other";
        }
        origin[0] = f.getD("TimeOrigin");
        return "Ok:" + f.getC("System") + "|" + f.getC("TimeScale") + "|" + f.getC("Unit(1)");
    }

    @Test
    void timescaleSettingsMatchTheCLibrary() {
        JsonNode cases = ProbeData.load("time_scale_set").get("CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String from = c.get(0).asText();
            String action = c.get(1).asText();
            JsonNode w = c.get(2);
            String want;
            double wantOrigin = 0.0;
            if ("Err".equals(w.get("variant").asText())) {
                want = "Err:" + w.get("value").asText();
            } else {
                JsonNode v = w.get("value");
                want = "Ok:" + v.get(0).asText() + "|" + v.get(1).asText() + "|"
                        + v.get(2).asText();
                wantOrigin = ProbeData.toDouble(v.get(3));
            }
            double[] origin = {0.0};
            String got = run(from, action, origin);
            if (!got.equals(want) || !CValues.cValueEq(origin[0], wantOrigin)) {
                failures.add(from + " [" + action + "]: got " + got + " " + origin[0] + ", C gives " + want + " "
                        + wantOrigin);
            }
        }
        assertEquals(150, cases.size());
        assertTrue(
                failures.isEmpty(),
                failures.size() + " of " + cases.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
