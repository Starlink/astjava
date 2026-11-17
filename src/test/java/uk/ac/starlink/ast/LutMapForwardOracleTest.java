package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_lutmap_forward_oracle.rs}: a LutMap's forward
 * transformation against C's, bit for bit, between nodes with an increment that is not a power of
 * two, beyond either end, on the last node beside a bad entry, and at half increments for
 * nearest-neighbour interpolation (lutmap.c {@code Transform}), from {@code
 * oracle/lutmap_forward.json} (C probe {@code tools/oracle_fixtures/lutmap_forward_probe.c}).
 */
class LutMapForwardOracleTest {

    private static double[] doubles(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = ProbeData.toDouble(n.get(i));
        }
        return v;
    }

    @Test
    void forwardMatchesTheCLibraryBitForBit() {
        JsonNode cases = ProbeData.load("lutmap_forward").get("CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            LutMap lm =
                    new LutMap(doubles(c.get(1)), c.get(2).asDouble(), c.get(3).asDouble());
            lm.setI("LutInterp", c.get(4).asBoolean() ? 1 : 0);
            double[] input = doubles(c.get(5));
            double[] want = doubles(c.get(6));
            double[] got = lm.tran1(input.length, input, true);
            for (int i = 0; i < input.length; i++) {
                if (Double.doubleToRawLongBits(got[i]) != Double.doubleToRawLongBits(want[i])) {
                    failures.add(name + " at " + input[i] + ": " + got[i] + ", C " + want[i]);
                }
            }
        }
        assertEquals(6, cases.size());
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
