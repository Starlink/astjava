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
 * Translation of Rust {@code tests/test_sky_norm.rs}: SkyFrame Norm and the SkyTol and Equinox
 * setters, checked against C ({@code oracle/sky_norm.json}, from {@code
 * tools/oracle_fixtures/sky_norm_probe.c}).
 */
class SkyNormTest {

    private static double[] pair(JsonNode n) {
        return new double[] {ProbeData.toDouble(n.get(0)), ProbeData.toDouble(n.get(1))};
    }

    @Test
    void normMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : ProbeData.load("sky_norm").get("NORM_CASES")) {
            boolean permuted = c.get(0).asBoolean();
            boolean neglon = c.get(1).asBoolean();
            double[] input = pair(c.get(2));
            double[] want = pair(c.get(3));
            SkyFrame f = new SkyFrame();
            if (permuted) {
                f.permAxes(new int[] {2, 1});
            }
            if (neglon) {
                f.setNegLon(true);
            }
            double[] v = input.clone();
            f.norm(v);
            if (!CValues.cValuesEq(v, want)) {
                failures.add("permuted " + permuted + " neglon " + neglon + " " + Arrays.toString(input) + ": got "
                        + Arrays.toString(v) + ", C gives " + Arrays.toString(want));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void settersMatchTheCLibrary() {
        JsonNode data = ProbeData.load("sky_norm");
        SkyFrame helio = new SkyFrame();
        helio.setSystem("HELIOECLIPTIC");
        helio.setEquinox(1975.0);
        assertEquals(data.get("HELIO_EQUINOX_SET").asBoolean(), helio.testEquinox());

        SkyFrame f = new SkyFrame();
        f.setSkyTol(-0.5);
        assertEquals(ProbeData.toDouble(data.get("SKYTOL")), f.getSkyTol());
    }
}
