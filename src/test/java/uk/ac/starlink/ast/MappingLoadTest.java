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
 * Translation of Rust {@code tests/test_mapping_load.rs}: Mappings whose dumps leave values out
 * read back as C's do. A PermMap constant or a diagonal MatrixMap element that is bad is written
 * unset and reads back as bad (permmap.c, matrixmap.c Dump and Load). Each case's dump read back
 * and written again must equal C's, and its forward transformation must equal C's. The PermMaps C
 * dumped are also built here, and their dumps must equal C's: C writes only the constants the
 * permutations refer to. The cases come from {@code oracle/mapping_load.json} (from {@code
 * tools/oracle_fixtures/mapping_load_probe.c}).
 *
 * <p>{@code loads_match_the_c_library} runs here the PermMap and MatrixMap cases; the WcsMap cases
 * ({@code wcsmap_*}) are added in Task 11. {@code a_dssmap_dump_needs_its_plate_solution} needs a
 * DssMap, which is outside the first phase.
 */
class MappingLoadTest {

    private static String[] lines(JsonNode arr) {
        String[] out = new String[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).asText();
        }
        return out;
    }

    private static double[] values(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProbeData.toDouble(arr.get(i));
        }
        return out;
    }

    private static JsonNode cases() {
        return ProbeData.load("mapping_load").get("CASES");
    }

    @Test
    void loadsMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : cases()) {
            String name = c.get(0).asText();
            if (!name.startsWith("permmap") && !name.startsWith("matrixmap")) {
                continue;
            }
            run++;
            String dump = ChannelText.text(lines(c.get(1)));
            String redump = ChannelText.text(lines(c.get(2)));
            AstObject obj = ChannelText.read(dump);
            String got = ChannelText.dump(obj);
            if (!got.equals(redump)) {
                failures.add(name + " re-dump:\n" + got + "\nC gives:\n" + redump);
            }

            Mapping map = (Mapping) obj;
            double[] input = values(c.get(3));
            double[] want = values(c.get(4));
            int nin = map.getNin();
            int nout = map.getNout();
            int npoint = input.length / nin;
            double[][] in = new double[nin][npoint];
            for (int p = 0; p < npoint; p++) {
                for (int k = 0; k < nin; k++) {
                    in[k][p] = input[p * nin + k];
                }
            }
            double[][] out = map.transform(in, npoint, true);
            double[] flat = new double[npoint * nout];
            for (int p = 0; p < npoint; p++) {
                for (int k = 0; k < nout; k++) {
                    flat[p * nout + k] = out[k][p];
                }
            }
            if (!CValues.cValuesEq(flat, want)) {
                failures.add(name + " forward: " + Arrays.toString(flat) + ", C gives " + Arrays.toString(want));
            }
        }
        assertEquals(5, run, "PermMap and MatrixMap cases in mapping_load.json");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void permmapDumpsMatchTheCLibrary() {
        double bad = AstObject.AST__BAD;
        Object[][] built = {
            {"permmap_bad_constant", new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {bad})},
            {
                "permmap_bad_constant_among_others",
                new PermMap(1, new int[] {1}, 2, new int[] {1, -2}, new double[] {5.0, bad, 7.0})
            },
            {
                "permmap_unreferenced_constant",
                new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {5.0, 6.0})
            },
        };
        List<String> failures = new ArrayList<>();
        for (Object[] b : built) {
            String name = (String) b[0];
            String want = null;
            for (JsonNode c : cases()) {
                if (c.get(0).asText().equals(name)) {
                    want = ChannelText.text(lines(c.get(1)));
                }
            }
            String got = ChannelText.dump((PermMap) b[1]);
            if (!got.equals(want)) {
                failures.add(name + ":\n" + got + "\nC gives:\n" + want);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
