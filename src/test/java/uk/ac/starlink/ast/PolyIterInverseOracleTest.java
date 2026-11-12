package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_poly_iter_inverse_oracle.rs}: the iterative inverse of
 * PolyMaps with no inverse coefficients, against C's bit for bit (polymap.c {@code Transform} with
 * IterInverse set), from {@code oracle/poly_iter_inverse.json}: an initial guess from the linear
 * truncation of the forward transformation ({@code LinearGuess}), Newton-Raphson steps solved by
 * {@code palDmat}, and convergence when |dx|^2 &lt;= tol^2 |x|^2. The iteration is arithmetic
 * only, so every value must equal C's.
 */
class PolyIterInverseOracleTest {

    private static double[] doubles(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = ProbeData.toDouble(n.get(i));
        }
        return v;
    }

    /**
     * The PolyMap a case builds: coefficients as {@code astPolyMap} takes them, IterInverse set, and
     * options NiterInverse and TolInverse given as {@code name=value} pairs.
     */
    private static PolyMap polymap(int n, double[] coeffs, String opts) {
        PolyMap pm = new PolyMap(n, n, coeffs.length / (2 + n), coeffs, 0, null);
        pm.setIterInverse(true);
        if (!opts.isEmpty()) {
            pm.set(opts);
        }
        return pm;
    }

    @Test
    void iterativeInverseMatchesTheCLibraryBitForBit() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("poly_iter_inverse").get("CASES")) {
            String name = c.get(0).asText();
            int n = c.get(1).asInt();
            PolyMap pm = polymap(n, doubles(c.get(2)), c.get(3).asText());
            int npoint = c.get(4).asInt();
            double[] out = doubles(c.get(5));
            double[] want = doubles(c.get(6));
            double[][] cols = new double[n][npoint];
            for (int a = 0; a < n; a++) {
                System.arraycopy(out, a * npoint, cols[a], 0, npoint);
            }
            double[][] got = pm.transform(cols, npoint, false);
            for (int ip = 0; ip < npoint; ip++) {
                for (int a = 0; a < n; a++) {
                    double g = got[a][ip];
                    double w = want[a * npoint + ip];
                    if (Double.doubleToRawLongBits(g) != Double.doubleToRawLongBits(w)) {
                        failures.add(name + " point " + ip + " axis " + a + ": " + g + ", C " + w);
                    }
                }
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(7, ran, "cases run");
    }
}
