package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_specmap_3d.rs}: a 3-dimensional SpecMap, whose second and
 * third axes give the RA and Dec of each point's source, matches C's (specmap.c {@code Transform}
 * and {@code FrameChange}). Each case of {@code oracle/specmap_3d.json} (C probe {@code
 * specmap_3d_probe.c}) loads with three axes and is written again as C writes it, and its forward
 * and inverse transformations, of points that include bad values, equal C's bit for bit. A run of
 * 3-dimensional SpecMaps simplifies as C's does.
 */
class SpecMap3dOracleTest {

    private static JsonNode consts() {
        return ProbeData.load("specmap_3d");
    }

    private static String text(JsonNode lines) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode l : lines) {
            sb.append(l.asText()).append('\n');
        }
        return sb.toString();
    }

    /** The points of {@code rows}, one row per point, as {@code [axis][point]}. */
    private static double[][] columns(JsonNode rows) {
        double[][] cols = new double[3][rows.size()];
        for (int p = 0; p < rows.size(); p++) {
            for (int c = 0; c < 3; c++) {
                cols[c][p] = ProbeData.toDouble(rows.get(p).get(c));
            }
        }
        return cols;
    }

    private static boolean differs(double[][] got, double[][] want) {
        for (int c = 0; c < 3; c++) {
            for (int p = 0; p < want[c].length; p++) {
                if (Double.doubleToRawLongBits(got[c][p]) != Double.doubleToRawLongBits(want[c][p])) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void transformationsMatchTheCLibrary() {
        JsonNode cases = consts().get("CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            String dump = text(c.get(1));
            AstObject obj = ChannelText.read(dump);
            String got = ChannelText.dump(obj);
            if (!got.equals(dump)) {
                failures.add(name + " re-dump:\n" + got + "\nC gives:\n" + dump);
            }
            Mapping map = (Mapping) obj;
            assertEquals(3, map.getNin(), name);
            double[][] in = columns(c.get(2));
            for (int d = 0; d < 2; d++) {
                boolean forward = d == 0;
                double[][] want = columns(c.get(3 + d));
                double[][] out = map.transform(in, in[0].length, forward);
                if (differs(out, want)) {
                    failures.add(name + " forward=" + forward + ": " + Arrays.deepToString(out) + ", C gives "
                            + Arrays.deepToString(want));
                }
            }
        }
        assertEquals(4, cases.size());
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void aBuiltSpecmapDumpsAsCDoes() {
        SpecMap map = new SpecMap(3, 0);
        map.specAdd("LKF2HL", new double[] {0.1, 0.2});
        String want = null;
        for (JsonNode c : consts().get("CASES")) {
            if ("lsrk".equals(c.get(0).asText())) {
                want = text(c.get(1));
            }
        }
        assertEquals(want, ChannelText.dump(map));
    }

    @Test
    void onlyOneOrThreeAxes() {
        for (int nin : new int[] {0, 2, 4}) {
            AstException e = assertThrows(AstException.class, () -> new SpecMap(nin, 0), "nin " + nin);
            assertEquals(AstException.AST__BADNI, e.getStatus(), "nin " + nin);
        }
    }

    @Test
    void simplificationMatchesTheCLibrary() {
        JsonNode cases = consts().get("SIMPLIFY_CASES");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            Mapping map = (Mapping) ChannelText.read(text(c.get(1)));
            String got = ChannelText.dump(map.simplify());
            String want = text(c.get(2));
            if (!got.equals(want)) {
                failures.add(name + ":\n" + got + "\nC gives:\n" + want);
            }
        }
        assertEquals(2, cases.size());
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
