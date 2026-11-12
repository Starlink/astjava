package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_unit_mapper.rs}: scale factors derived from unit strings,
 * against the C library, from {@code oracle/unit_mapper.json} (C probe {@code
 * unit_mapper_probe.c}).
 *
 * <p>C's {@code UNIT_CASES} are the Mappings {@code astConvert} finds between two one-axis
 * Frames with ActiveUnit set, which differ only in Unit; frame.c {@code SubFrame} takes the
 * Mapping between them from {@code astUnitMapper}, and simplifying a lone UnitMap or ZoomMap
 * leaves its scale factor as it is. The cases are checked here on {@link Units#unitMapper}; Task
 * 17 (Frame matching and conversion) adds the route through {@code convert}.
 *
 * <p>Left to later work: {@code spec_frame_unit_changes_match_the_c_library} (SpecFrame) and
 * {@code zoom_map_chains_simplify_as_the_c_library_does} (needs simplification).
 */
class UnitMapperTest {

    private static double[] doubles(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = ProbeData.toDouble(n.get(i));
        }
        return v;
    }

    @Test
    void unitMappingsMatchTheCLibrary() {
        JsonNode data = ProbeData.load("unit_mapper");
        double[] values = doubles(data.get("VALUES"));
        JsonNode cases = data.get("UNIT_CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String from = c.get(0).asText();
            String to = c.get(1).asText();
            double[] wantFwd = doubles(c.get(2).get(0));
            double[] wantInv = doubles(c.get(2).get(1));
            Mapping map = Units.unitMapper(from, to);
            if (map == null) {
                failures.add(from + " -> " + to + ": no Mapping");
                continue;
            }
            double[] fwd = map.tran1(values.length, values, true);
            double[] inv = map.tran1(values.length, values, false);
            if (!Arrays.equals(fwd, wantFwd) || !Arrays.equals(inv, wantInv)) {
                failures.add(from + " -> " + to + ": forward " + Arrays.toString(fwd) + " inverse "
                        + Arrays.toString(inv) + ", C gives forward " + Arrays.toString(wantFwd) + " inverse "
                        + Arrays.toString(wantInv));
            }
        }
        assertEquals(70, cases.size());
        assertTrue(failures.isEmpty(), failures.size() + " cases differ from C:\n" + String.join("\n", failures));
    }
}
