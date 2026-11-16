package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_flux_badun.rs}: where a FluxFrame Unit that suits no flux
 * System is reported, checked against C ({@code oracle/flux_badun.json}, from {@code
 * tools/oracle_fixtures/flux_badun_probe.c}). C's {@code GetSystem} takes an unset System from a
 * set Unit and reports AST__BADUN when no flux System's default units convert to it; {@code
 * SetUnit} stores the Unit and then reads the System, clearing a set System the Unit does not
 * suit; and the Title, Label and Symbol defaults read the System (fluxframe.c).
 */
class FluxBadunOracleTest {

    /** {@code Ok:<value>} or {@code Err:<code>}, as the probe records an outcome. */
    private static String outcome(Supplier<String> f) {
        try {
            return "Ok:" + f.get();
        } catch (AstException e) {
            return "Err:" + (e.getStatus() == AstException.AST__BADUN ? "Badun" : "other");
        }
    }

    private static String want(JsonNode r) {
        return r.get("variant").asText() + ":" + r.get("value").asText();
    }

    @Test
    void badUnitsAreReportedAsInTheCLibrary() {
        JsonNode data = ProbeData.load("flux_badun");
        JsonNode attrs = data.get("ATTRS");
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : data.get("CASES")) {
            String system = c.get(0).asText();
            String unit = c.get(1).asText();
            FluxFrame f = new FluxFrame(1.4e9, null);
            if (!system.isEmpty()) {
                f.set(system);
            }
            List<String> got = new ArrayList<>();
            List<String> exp = new ArrayList<>();
            got.add(outcome(() -> {
                f.setUnit(1, unit);
                return "";
            }));
            exp.add(want(c.get(2)));
            for (int i = 0; i < attrs.size(); i++) {
                String attr = attrs.get(i).asText();
                got.add(outcome(() -> f.getC(attr)));
                exp.add(want(c.get(3).get(i)));
            }
            if (!got.equals(exp)) {
                failures.add("[" + system + "] Unit=" + unit + ": got " + got + ", C gives " + exp);
            }
            run++;
        }
        assertEquals(6, run);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
