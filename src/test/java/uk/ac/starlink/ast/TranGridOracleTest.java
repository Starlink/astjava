package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_tran_grid_oracle.rs}: {@code tranGrid} against C's {@code
 * astTranGrid} (mapping.c), from {@code oracle/tran_grid.json}. Each grid is compared bit for bit
 * through C's FNV-1a hash of every output, and through a sample of positions so that a mismatch
 * shows where it is. The grids cover the linear-fit paths C keeps separate (1-D, 2-D and general),
 * sections divided by the initial scale size, grids large enough to be transformed in blocks, and
 * the inverse transformation. The Mappings here do only arithmetic, which Java and C carry out
 * alike, so every value must equal C's.
 *
 * <p>{@code grids_match_c} runs the grids of the Mappings in the first phase; those of the FITS-WCS
 * FrameSet ({@code tan}) are added in Task 23, and the MathMap grids ({@code cubic}) are outside the
 * first phase. In {@code grid_errors_match_c}, a
 * Mapping with no inverse stands in for the MathMap whose inverse is undefined. {@code
 * quadratic_fits_match_c} and {@code quadratic_fit_needs_two_inputs} test {@code astQuadApprox},
 * which jniast does not have.
 */
class TranGridOracleTest {

    /** The probe's Mapping for a case's Mapping name, or null for one outside the first phase. */
    private static Mapping mapping(String name) {
        switch (name) {
            case "zoom2":
                return new ZoomMap(2, 2.0);
            case "cmp3":
                return cmp3();
            case "matrix23":
                return new MatrixMap(2, 3, new double[][] {{1.0, 2.0}, {3.0, -4.0}, {0.5, 6.0}});
            case "poly":
                return new PolyMap(
                        2,
                        1,
                        6,
                        new double[] {
                            2.0, 1.0, 0.0, 0.0, 3.0, 1.0, 1.0, 0.0, 4.0, 1.0, 0.0, 1.0, 5.0, 1.0, 1.0, 1.0, 6.0, 1.0,
                            2.0, 0.0, 7.0, 1.0, 0.0, 2.0
                        },
                        0,
                        null);
            case "tan":
            case "cubic":
            case "sqrt_part":
            case "second_bad":
                return null;
            default:
                throw new IllegalArgumentException("no Mapping " + name);
        }
    }

    /** The 3-D CmpMap of {@code testtrangrid.c}. */
    private static Mapping cmp3() {
        CmpMap c3 = new CmpMap(
                new ShiftMap(new double[] {-1.0}),
                new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {-2.0, 3.0}}),
                false);
        int[] perm = {3, 2, 1};
        Mapping c1 = new PermMap(3, perm, 3, perm, null);
        ZoomMap c2 = new ZoomMap(3, 0.25);
        c2.invert();
        CmpMap c4 = new CmpMap(c1, c2, true);
        c4.invert();
        return new CmpMap(c3, c4, true);
    }

    /** FNV-1a over the bit patterns of every output, position by position. */
    private static long hash(double[][] out) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < out[0].length; i++) {
            for (double[] axis : out) {
                h = (h ^ Double.doubleToRawLongBits(axis[i])) * 0x100000001b3L;
            }
        }
        return h;
    }

    private static int[] ints(JsonNode n) {
        int[] v = new int[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = n.get(i).asInt();
        }
        return v;
    }

    @Test
    void gridsMatchC() {
        int ran = 0;
        for (JsonNode c : ProbeData.load("tran_grid").get("GRIDS")) {
            String name = c.get(0).asText();
            Mapping m = mapping(c.get(1).asText());
            if (m == null) {
                continue;
            }
            int[] lbnd = ints(c.get(2));
            int[] ubnd = ints(c.get(3));
            double tol = ProbeData.toDouble(c.get(4));
            int maxpix = c.get(5).asInt();
            boolean forward = c.get(6).asBoolean();
            long wantHash = c.get(7).bigIntegerValue().longValue();
            JsonNode sample = c.get(8);
            int nout = forward ? m.getNout() : m.getNin();
            double[][] out = m.tranGrid(lbnd.length, lbnd, ubnd, tol, maxpix, forward, nout);
            List<Double> got = new ArrayList<>();
            for (int i = 0; i < out[0].length; i += 97) {
                for (double[] axis : out) {
                    got.add(axis[i]);
                }
            }
            double[] gotArr = new double[got.size()];
            for (int i = 0; i < gotArr.length; i++) {
                gotArr[i] = got.get(i);
            }
            double[] want = new double[sample.size()];
            for (int i = 0; i < want.length; i++) {
                want[i] = ProbeData.toDouble(sample.get(i));
            }
            CValues.assertCValues(gotArr, want, name + ": sampled positions");
            assertEquals(wantHash, hash(out), name + ": every position");
            ran++;
        }
        assertEquals(14, ran, "grids run");
    }

    @Test
    void gridErrorsMatchC() {
        JsonNode want = ProbeData.load("tran_grid").get("GRID_ERRORS");
        Mapping zoom = mapping("zoom2");
        NoInverseMap noInverse = new NoInverseMap();
        int[] codes = {
            code(() -> zoom.tranGrid(2, new int[] {5, 0}, new int[] {3, 4}, 0.5, 100, true, 2)),
            code(() -> zoom.tranGrid(2, new int[] {3, 4}, new int[] {3, 4}, -1.0, 100, true, 2)),
            code(() -> noInverse.tranGrid(1, new int[] {0}, new int[] {4}, 0.5, 100, false, 1)),
            code(() -> zoom.tranGrid(1, new int[] {0}, new int[] {4}, 0.5, 100, true, 2))
        };
        assertArrayEquals(ints(want), codes);
    }

    private static int code(org.junit.jupiter.api.function.Executable e) {
        return org.junit.jupiter.api.Assertions.assertThrows(AstException.class, e)
                .getStatus();
    }
}
