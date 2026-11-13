package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_system_change.rs}: what setting or clearing the System does
 * to the other attributes of a TimeFrame, checked against C ({@code oracle/system_change.json},
 * from {@code tools/oracle_fixtures/system_change_probe.c}). When the System changes, the Label,
 * Symbol and Title, whose defaults depend on it, are cleared, and the Unit last used with the new
 * System is restored, or cleared (timeframe.c {@code SetSystem} and {@code ClearSystem}). {@link
 * #run} makes each Frame as the probe does.
 *
 * <p>The SpecFrame cases ({@code spec_*}) and the FluxFrame case ({@code flux_set_sfcbr}) are
 * skipped (out of scope).
 */
class SystemChangeTest {

    /** The probe's {@code mine} settings. */
    private static final String MINE = "Label(1)=Mine,Symbol(1)=M,Title=Mine";

    private static TimeFrame time(String system) {
        TimeFrame f = new TimeFrame();
        f.set("System=" + system);
        f.set(MINE);
        return f;
    }

    /** The Frame a case leaves, or null for a case whose class is out of scope. */
    private static Frame run(String name) {
        switch (name) {
            case "time_set_jd": {
                TimeFrame f = time("MJD");
                f.set("System=JD");
                return f;
            }
            case "time_clear": {
                TimeFrame f = time("JD");
                f.clear("System");
                return f;
            }
            case "time_clear_bepoch": {
                TimeFrame f = time("BEPOCH");
                f.clear("System");
                return f;
            }
            case "spec_set_vrad":
            case "spec_set_back_to_freq":
            case "spec_clear":
            case "spec_clear_unchanged":
            case "flux_set_sfcbr":
                return null;
            default:
                throw new IllegalArgumentException("no Java set-up for case " + name);
        }
    }

    @Test
    void systemChangesMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("system_change").get("CASES")) {
            String name = c.get(0).asText();
            Frame f = run(name);
            if (f == null) {
                continue;
            }
            run++;
            String got = f.getC("System") + " | " + f.getC("Label(1)") + " | " + f.getC("Symbol(1)") + " | "
                    + f.getC("Title") + " | " + f.getC("Unit(1)");
            String want = c.get(1).asText() + " | " + c.get(2).asText() + " | "
                    + c.get(3).asText() + " | " + c.get(4).asText() + " | "
                    + c.get(5).asText();
            if (!got.equals(want)) {
                failures.add(name + ": got " + got + ", C gives " + want);
            }
        }
        assertEquals(3, run, "the in-scope cases the C probe recorded");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
