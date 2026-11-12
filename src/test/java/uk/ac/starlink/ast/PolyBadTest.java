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
 * Translation of Rust {@code tests/test_poly_bad.rs}: PolyMaps treat bad values as C does
 * (polymap.c {@code Transform} and {@code PolyPowers}): a bad or NaN input spoils only the terms
 * that use a power of it above zero, and a bad coefficient spoils its output, so outputs that use
 * none of these stay good. Each case's forward and inverse transformations equal C's bit for bit,
 * from {@code oracle/poly_bad.json}, through both the bad-value path and the NaN protocol.
 *
 * <p>The ChebyMap cases ({@code chebymap_*}) need a class outside the first phase.
 */
class PolyBadTest {

    private static double[] doubles(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = ProbeData.toDouble(n.get(i));
        }
        return v;
    }

    /** Points given point by point, {@code n} values each, as {@code [n][npoint]}. */
    private static double[][] points(double[] values, int n) {
        int np = values.length / n;
        double[][] out = new double[n][np];
        for (int p = 0; p < np; p++) {
            for (int c = 0; c < n; c++) {
                out[c][p] = values[p * n + c];
            }
        }
        return out;
    }

    /** Points as {@code [ncoord][npoint]}, flattened point by point. */
    private static double[] flat(double[][] pts) {
        int np = pts[0].length;
        double[] out = new double[np * pts.length];
        for (int p = 0; p < np; p++) {
            for (int c = 0; c < pts.length; c++) {
                out[p * pts.length + c] = pts[c][p];
            }
        }
        return out;
    }

    private static boolean same(double[] got, double[] want) {
        if (got.length != want.length) {
            return false;
        }
        for (int i = 0; i < got.length; i++) {
            if (Double.doubleToRawLongBits(got[i]) != Double.doubleToRawLongBits(want[i])) {
                return false;
            }
        }
        return true;
    }

    @Test
    void badValuesMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("poly_bad").get("CASES")) {
            String name = c.get(0).asText();
            if (!name.startsWith("polymap_")) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode l : c.get(1)) {
                lines.add(l.asText());
            }
            Mapping map = (Mapping) ChannelText.read(ChannelText.text(lines.toArray(new String[0])));
            double[] input = doubles(c.get(2));
            List<Object[]> dirs = new ArrayList<>();
            dirs.add(new Object[] {true, map.getNin(), doubles(c.get(3))});
            if (!c.get(4).isNull()) {
                dirs.add(new Object[] {false, map.getNout(), doubles(c.get(4))});
            }
            for (Object[] d : dirs) {
                boolean forward = (Boolean) d[0];
                int n = (Integer) d[1];
                double[] want = (double[]) d[2];
                double[][] pts = points(input, n);
                int np = pts[0].length;
                double[] got = flat(map.transform(pts, np, forward));
                if (!same(got, want)) {
                    failures.add(name + " forward=" + forward + ": " + Arrays.toString(got) + "\n  C gives "
                            + Arrays.toString(want));
                }
                got = flat(map.transformNanProtocol(pts, np, forward));
                if (!same(got, want)) {
                    failures.add(name + " NaN protocol forward=" + forward + ": " + Arrays.toString(got)
                            + "\n  C gives " + Arrays.toString(want));
                }
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(4, ran, "cases run");
    }
}
