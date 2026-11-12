package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_new_unit.rs}: what setting or clearing an axis Unit does
 * to that axis's Label, Symbol and Format, checked against C ({@code oracle/new_unit.json}, from
 * {@code tools/oracle_fixtures/new_unit_probe.c}). With ActiveUnit set, C rewrites a set Label and
 * Symbol for the new Unit and clears a set Format when the Unit changes (frame.c {@code SetUnit},
 * {@code ClearUnit} and {@code NewUnit}); trailing spaces are dropped from the Unit.
 *
 * <p>The plain Frame cases run here; {@code time_s} is added in Task 19, and {@code spec_log}
 * and {@code spec_mhz} need a SpecFrame, outside the first phase.
 */
class NewUnitTest {

    private static Frame frame(boolean active) {
        Frame f = new Frame(1);
        f.setLabel(1, "Dist");
        f.setSymbol(1, "d");
        f.setFormat(1, "%.2f");
        f.setUnit(1, "m");
        f.setActiveUnit(active);
        return f;
    }

    /** The Frame of case {@code name}, or null if it is not run here. */
    private static Frame run(String name) {
        Frame f;
        switch (name) {
            case "active_log":
                f = frame(true);
                f.setUnit(1, "log(m)");
                return f;
            case "active_km":
                f = frame(true);
                f.setUnit(1, "km");
                return f;
            case "active_same":
                f = frame(true);
                f.setUnit(1, "m");
                return f;
            case "inactive_log":
                f = frame(false);
                f.setUnit(1, "log(m)");
                return f;
            case "active_trailing_spaces":
                f = frame(true);
                f.setUnit(1, "km   ");
                return f;
            case "active_clear":
                f = frame(true);
                f.clearUnit(1);
                return f;
            default:
                return null;
        }
    }

    @Test
    void unitChangesMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("new_unit").get("CASES")) {
            String name = c.get(0).asText();
            Frame f = run(name);
            if (f == null) {
                continue;
            }
            ran++;
            String got = f.getLabel(1) + "|" + f.getSymbol(1) + "|" + f.testFormat(1) + "|" + f.getUnit(1);
            String want = c.get(1).asText() + "|" + c.get(2).asText() + "|"
                    + c.get(3).asBoolean() + "|" + c.get(4).asText();
            if (!got.equals(want)) {
                failures.add(name + ": got " + got + ", C gives " + want);
            }
        }
        assertEquals(6, ran, "the plain Frame cases");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
