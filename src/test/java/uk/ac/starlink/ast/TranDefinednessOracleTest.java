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
 * Translation of Rust {@code tests/test_tran_definedness_oracle.rs}: which directions a Mapping
 * defines, and whether transforming in each is an error, match C, from {@code
 * oracle/tran_definedness.json}, for Mappings whose definedness depends on more than their
 * coefficients, here a MatrixMap holding a bad element (matrixmap.c {@code InvertMatrix}) and its
 * control, and an NCP WcsMap, which defines both directions but cannot transform in either
 * (wcsmap.c {@code Map}), and its SIN control, and a PolyMap with unequal numbers of inputs and
 * outputs (polymap.c {@code GetIterInverse}, {@code SetIterInverse}), with whether setting
 * IterInverse fails. The MatrixMaps are also built from the matrices C was given.
 *
 * <p>The SwitchMap cases need a class outside the first phase.
 */
class TranDefinednessOracleTest {

    /** Whether transforming one point in the given direction fails. */
    private static boolean tranFails(Mapping map, boolean forward) {
        int n = forward ? map.getNin() : map.getNout();
        double[][] pt = new double[n][1];
        for (int i = 0; i < n; i++) {
            pt[i][0] = 0.1 * (i + 1);
        }
        try {
            map.transform(pt, 1, forward);
            return false;
        } catch (AstException e) {
            return true;
        }
    }

    /** TranForward, TranInverse, forward fails, inverse fails. */
    private static List<Boolean> definedness(Mapping map) {
        return Arrays.asList(map.getTranForward(), map.getTranInverse(), tranFails(map, true), tranFails(map, false));
    }

    private static List<Boolean> want(JsonNode c) {
        return Arrays.asList(
                c.get(2).asBoolean(),
                c.get(3).asBoolean(),
                c.get(4).asBoolean(),
                c.get(5).asBoolean());
    }

    private static boolean inScope(String name) {
        return name.startsWith("matrixmap_") || name.startsWith("wcsmap_") || name.startsWith("polymap_");
    }

    @Test
    void definednessMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("tran_definedness").get("CASES")) {
            String name = c.get(0).asText();
            if (!inScope(name)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode l : c.get(1)) {
                lines.add(l.asText());
            }
            Mapping map = (Mapping) ChannelText.read(ChannelText.text(lines.toArray(new String[0])));
            List<Boolean> got = definedness(map);
            if (!got.equals(want(c))) {
                failures.add(name + ": (TranForward, TranInverse, forward fails, inverse fails) " + got + ", C gives "
                        + want(c));
            }
            if (map instanceof PolyMap) {
                boolean iterFails;
                try {
                    ((PolyMap) map).setIterInverse(true);
                    iterFails = false;
                } catch (AstException e) {
                    iterFails = true;
                }
                if (iterFails != c.get(6).asBoolean()) {
                    failures.add(name + ": setting IterInverse fails " + iterFails + ", C "
                            + c.get(6).asBoolean());
                }
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(7, ran, "cases run");
    }

    @Test
    void builtMatrixmapsMatchTheCLibrary() {
        Object[][] built = {
            {"matrixmap_full_bad", new double[][] {{1.0, AstObject.AST__BAD}, {0.0, 1.0}}},
            {"matrixmap_full", new double[][] {{1.0, 2.0}, {0.0, 1.0}}},
        };
        for (Object[] b : built) {
            String name = (String) b[0];
            JsonNode found = null;
            for (JsonNode c : ProbeData.load("tran_definedness").get("CASES")) {
                if (c.get(0).asText().equals(name)) {
                    found = c;
                }
            }
            MatrixMap map = new MatrixMap(2, 2, (double[][]) b[1]);
            assertEquals(want(found), definedness(map), name);
        }
    }
}
