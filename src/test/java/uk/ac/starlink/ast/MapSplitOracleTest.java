package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_map_split_oracle.rs}: {@code mapSplit} against C's {@code
 * astMapSplit} for each class that overrides it, from {@code oracle/map_split.json}: a partial
 * selection, a full permuted one, an index out of range, an index of 0, a repeated index and an
 * empty selection. The base MapSplit and the classes that call it first refuse an index out of
 * range with {@code AST__AXIIN}; the others give no split. A split is compared by its outputs and
 * its dump, byte for byte.
 *
 * <p>{@code matrixmap_inverted_full_permuted}, where C simplifies the PermMap and MatrixMap the base
 * class splits a full permuted selection into into one MatrixMap, is in {@link #EXCLUDED}, and
 * {@code excludedCasesStillDiffer} fails when it matches. The WcsMap cases are added in Task 11;
 * the NormMap, RateMap and TranMap cases, and with them {@code
 * issimp_component_exclusions_still_differ}, need classes outside the first phase.
 */
class MapSplitOracleTest {

    /** Cases whose split differs from C's, with the reason. */
    private static final String[][] EXCLUDED = {
        {
            "matrixmap_inverted_full_permuted",
            "needs simplification: C merges the PermMap and the MatrixMap into one MatrixMap"
        },
    };

    private static Mapping inverted(Mapping m) {
        m.invert();
        return m;
    }

    /** The probe's Mapping for a case, by the case name's prefix, or null for one not yet here. */
    private static Mapping mapping(String name) {
        if (name.startsWith("unitmap_")) {
            return new UnitMap(3);
        } else if (name.startsWith("zoommap_inverted_")) {
            return inverted(new ZoomMap(3, 2.0));
        } else if (name.startsWith("shiftmap_inverted_")) {
            return inverted(new ShiftMap(new double[] {1.0, 2.0, 3.0}));
        } else if (name.startsWith("winmap_inverted_")) {
            return inverted(new WinMap(
                    2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {1.0, 2.0}, new double[] {3.0, 6.0
                    }));
        } else if (name.startsWith("permmap_")) {
            int[] p = {2, 1, 3};
            return new PermMap(3, p, 3, p, null);
        } else if (name.startsWith("matrixmap_inverted_")) {
            return inverted(new MatrixMap(2, 2, new double[][] {{2.0, 1.0}, {0.0, 3.0}}));
        } else if (name.startsWith("cmpmap_parallel_")) {
            return new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {1.0, 2.0}), false);
        } else if (name.startsWith("normmap_")
                || name.startsWith("ratemap_")
                || name.startsWith("tranmap_")
                || name.startsWith("wcsmap_")) {
            return null;
        }
        throw new IllegalArgumentException("no Java construction for case " + name);
    }

    private static int[] ints(JsonNode n) {
        int[] v = new int[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = n.get(i).asInt();
        }
        return v;
    }

    private static boolean isExcluded(String name) {
        for (String[] e : EXCLUDED) {
            if (e[0].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** What Java gives for a case: the AST status name, "None", or the outputs and the dump. */
    private static String outcome(Mapping map, int[] inputs) {
        int[] outs = new int[map.getNout()];
        Mapping sub;
        try {
            sub = map.mapSplit(inputs, outs);
        } catch (AstException e) {
            return "Err " + e.getStatusName();
        }
        if (sub == null) {
            return "None";
        }
        return "Split " + Arrays.toString(Arrays.copyOf(outs, sub.getNout())) + "\n" + ChannelText.dump(sub);
    }

    /** C's outcome for a case, in the form of {@link #outcome}. */
    private static String want(JsonNode outcome) {
        String variant = outcome.get("variant").asText();
        switch (variant) {
            case "Err":
                return "Err AST__" + outcome.get("value").asText();
            case "None":
                return "None";
            case "Split":
                return "Split " + Arrays.toString(ints(outcome.get("value").get(0))) + "\n"
                        + outcome.get("value").get(1).asText();
            default:
                throw new IllegalArgumentException("unexpected outcome " + variant);
        }
    }

    @Test
    void mapSplitMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("map_split").get("CASES")) {
            String name = c.get(0).asText();
            Mapping map = mapping(name);
            if (map == null || isExcluded(name)) {
                continue;
            }
            int[] inputs = ints(c.get(1));
            String got = outcome(map, inputs);
            String want = want(c.get(2));
            if (!got.equals(want)) {
                failures.add(name + " " + Arrays.toString(inputs) + ":\n" + got + "\nC:\n" + want);
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(41, ran, "cases run");
    }

    @Test
    void excludedCasesStillDiffer() {
        for (String[] e : EXCLUDED) {
            JsonNode found = null;
            for (JsonNode c : ProbeData.load("map_split").get("CASES")) {
                if (c.get(0).asText().equals(e[0])) {
                    found = c;
                }
            }
            assertNotNull(found, e[0]);
            assertNotEquals(
                    want(found.get(2)),
                    outcome(mapping(e[0]), ints(found.get(1))),
                    e[0] + " now matches C; remove it from EXCLUDED (" + e[1] + ")");
        }
    }
}
