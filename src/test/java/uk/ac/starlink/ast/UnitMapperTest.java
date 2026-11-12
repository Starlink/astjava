package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
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
 * leaves its scale factor as it is. The cases are checked both on {@link Units#unitMapper} and
 * through {@code convert}, whose Mapping this version does not simplify: it is the units Mapping
 * between UnitMaps, which leave the values unchanged.
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

    /** A one-axis Frame with the given Unit and ActiveUnit set. */
    private static Frame activeFrame(String unit) {
        Frame f = new Frame(1);
        f.setUnit(1, unit);
        f.setActiveUnit(true);
        return f;
    }

    /** The units Mapping from {@link Units#unitMapper}. */
    private static Mapping byUnitMapper(String from, String to) {
        return Units.unitMapper(from, to);
    }

    /** The base-to-current Mapping of the conversion between two Frames differing only in Unit. */
    private static Mapping byConvert(String from, String to) {
        FrameSet fs = activeFrame(from).convert(activeFrame(to), "");
        return fs == null ? null : fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
    }

    @Test
    void unitMappingsMatchTheCLibrary() {
        checkUnitCases(UnitMapperTest::byUnitMapper);
    }

    @Test
    void unitConversionsMatchTheCLibrary() {
        checkUnitCases(UnitMapperTest::byConvert);
    }

    private static void checkUnitCases(BiFunction<String, String, Mapping> route) {
        JsonNode data = ProbeData.load("unit_mapper");
        double[] values = doubles(data.get("VALUES"));
        JsonNode cases = data.get("UNIT_CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String from = c.get(0).asText();
            String to = c.get(1).asText();
            double[] wantFwd = doubles(c.get(2).get(0));
            double[] wantInv = doubles(c.get(2).get(1));
            Mapping map = route.apply(from, to);
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
