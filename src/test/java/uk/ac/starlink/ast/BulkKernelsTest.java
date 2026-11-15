package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_bulk_kernels.rs}: the whole-axis kernels take every
 * non-finite input as bad, and write only the points they are given into outputs longer than the
 * input.
 *
 * <p>The UnitNormMap and NullRegion tests need classes outside the first phase and are deferred.
 * Rust's NaN-path entry point is {@link Mapping#transformIntoNan}.
 */
class BulkKernelsTest {

    private static final double BAD = AstObject.AST__BAD;

    /** Every non-finite input is bad: BAD, NaN, and both infinities. */
    private static final double[] ODD = {BAD, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.0};

    /** Transforms {@link #ODD} on both axes into outputs one point longer, which must keep their last value. */
    private static double[][] run(Mapping map, boolean forward) {
        double[][] out = new double[2][ODD.length + 1];
        Arrays.fill(out[0], 7.0);
        Arrays.fill(out[1], 7.0);
        map.transformInto(new double[][] {ODD, ODD}, ODD.length, forward, out);
        return out;
    }

    private static void assertBadThen(double[] axis, double good) {
        for (int i = 0; i < 4; i++) {
            assertEquals(BAD, axis[i], Arrays.toString(axis));
        }
        assertEquals(good, axis[4]);
        assertEquals(7.0, axis[5], "a point past the input was written");
    }

    @Test
    void zoommapKernel() {
        ZoomMap m = new ZoomMap(2, 4.0);
        for (double[] axis : run(m, true)) {
            assertBadThen(axis, 8.0);
        }
        for (double[] axis : run(m, false)) {
            assertBadThen(axis, 0.5);
        }
    }

    @Test
    void shiftmapKernel() {
        ShiftMap m = new ShiftMap(new double[] {1.0, -1.0});
        double[][] out = run(m, true);
        assertBadThen(out[0], 3.0);
        assertBadThen(out[1], 1.0);
    }

    @Test
    void unitmapKernel() {
        UnitMap m = new UnitMap(2);
        double[] a = run(m, true)[0];
        // UnitMap replaces only NaN; it passes infinities through.
        assertArrayEquals(
                new double[] {BAD, BAD, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.0}, Arrays.copyOf(a, 5));
        assertEquals(7.0, a[5]);
    }

    @Test
    void winmapKernel() {
        // Axis 1 is scaled by 2; axis 2 is shifted by 1.
        WinMap m = new WinMap(new double[] {0.0, 1.0}, new double[] {2.0, 1.0});
        double[][] out = run(m, true);
        assertBadThen(out[0], 4.0);
        assertBadThen(out[1], 3.0);
        out = run(m, false);
        assertBadThen(out[0], 1.0);
        assertBadThen(out[1], 1.0);
    }

    @Test
    void permmapKernel() {
        // Output 1 is input 2, output 2 is the constant 5.
        PermMap m = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, -1}, new double[] {5.0});
        double[][] out = run(m, true);
        assertBadThen(out[0], 2.0);
        for (int i = 0; i < 5; i++) {
            assertEquals(5.0, out[1][i], Arrays.toString(out[1]));
        }
        assertEquals(7.0, out[1][5]);
    }

    @Test
    void diagonalMatrixmapKernel() {
        MatrixMap m = new MatrixMap(2, 2, new double[] {2.0, -1.0});
        double[][] out = run(m, true);
        assertBadThen(out[0], 4.0);
        assertBadThen(out[1], -2.0);
    }

    @Test
    void fullMatrixmapKernelIgnoresABadInputWithAZeroCoefficient() {
        // Output 1 = 1*x + 0*y ; output 2 = 3*x + 2*y.
        MatrixMap m = new MatrixMap(2, 2, new double[][] {{1.0, 0.0}, {3.0, 2.0}});
        double[] x = {1.0, 2.0, BAD};
        double[] y = {BAD, 1.0, 1.0};
        double[][] out = new double[2][3];
        m.transformInto(new double[][] {x, y}, 3, true, out);
        assertArrayEquals(new double[] {1.0, 2.0, BAD}, out[0]);
        assertArrayEquals(new double[] {BAD, 8.0, BAD}, out[1]);
    }

    @Test
    void fullMatrixmapKernelCrossesItsBlockBoundary() {
        MatrixMap m = new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {3.0, 4.0}});
        int n = 1000;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
            y[i] = i * 0.5;
        }
        double[][] out = new double[2][n];
        m.transformInto(new double[][] {x, y}, n, true, out);
        for (int i = 0; i < n; i++) {
            assertEquals(0.0 + 1.0 * x[i] + 2.0 * y[i], out[0][i]);
            assertEquals(0.0 + 3.0 * x[i] + 4.0 * y[i], out[1][i]);
        }
    }

    /**
     * Transforms {@code inp} with {@code map} into outputs one point longer, which must keep their
     * last value, and checks every point bit for bit against {@code expect}.
     */
    private static void assertPoints(
            Mapping map, boolean forward, double[][] inp, int nout, Function<double[], double[]> expect) {
        int n = inp[0].length;
        double[][] out = new double[nout][n + 1];
        for (double[] axis : out) {
            Arrays.fill(axis, 7.0);
        }
        map.transformInto(inp, n, forward, out);
        for (int i = 0; i < n; i++) {
            double[] point = new double[inp.length];
            for (int k = 0; k < inp.length; k++) {
                point[k] = inp[k][i];
            }
            double[] want = expect.apply(point);
            for (int k = 0; k < nout; k++) {
                assertEquals(
                        Double.doubleToRawLongBits(want[k]),
                        Double.doubleToRawLongBits(out[k][i]),
                        "point " + i + " " + Arrays.toString(point) + " axis " + k + ": got " + out[k][i] + ", want "
                                + want[k]);
            }
        }
        for (double[] axis : out) {
            assertEquals(7.0, axis[n], "a point past the input was written");
        }
    }

    /**
     * One output of a full MatrixMap (matrixmap.c Transform): bad if any input with a non-zero
     * coefficient is bad, otherwise the sum, in input order, of coefficient times value over the
     * good inputs.
     */
    private static double matrixRow(double[] coeffs, double[] p) {
        for (int k = 0; k < coeffs.length; k++) {
            if (coeffs[k] != 0.0 && (!Double.isFinite(p[k]) || p[k] == BAD)) {
                return BAD;
            }
        }
        double sum = 0.0;
        for (int k = 0; k < coeffs.length; k++) {
            if (Double.isFinite(p[k]) && p[k] != BAD) {
                sum += coeffs[k] * p[k];
            }
        }
        return sum;
    }

    @Test
    void fullMatrixmapKernelMatchesCForEachInputCount() {
        double[] odd = {BAD, Double.NaN, Double.POSITIVE_INFINITY, -0.0};
        for (int nin = 1; nin <= 6; nin++) {
            // Row 0 has a zero coefficient on its last input, and a negative one so that -0.0
            // terms arise.
            double[][] rows = new double[2][nin];
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < nin; c++) {
                    rows[r][c] = r == 0 && c == nin - 1 ? 0.0 : (r + 1.0) * 0.5 - c * 0.75;
                }
            }
            MatrixMap m = new MatrixMap(nin, 2, rows);
            double[][] inp = new double[nin][150];
            for (int j = 0; j < nin; j++) {
                for (int i = 0; i < 150; i++) {
                    inp[j][i] = i % (5 + j) == 2 ? odd[(i + j) % odd.length] : i * 0.37 - 20.0 + j;
                }
            }
            assertPoints(m, true, inp, 2, p -> new double[] {matrixRow(rows[0], p), matrixRow(rows[1], p)});
        }
    }

    @Test
    void aParallelCmpmapSplitsItsOutputAtTheFirstChildsOutputCount() {
        // First child: 1 input -> 2 outputs (the input and the constant 9).
        PermMap first = new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {9.0});
        CmpMap m = new CmpMap(first, new ZoomMap(1, 2.0), false);
        double[][] out = new double[3][2];
        m.transformInto(new double[][] {{1.0, 2.0}, {3.0, 4.0}}, 2, true, out);
        assertArrayEquals(new double[] {1.0, 2.0}, out[0]);
        assertArrayEquals(new double[] {9.0, 9.0}, out[1]);
        assertArrayEquals(new double[] {6.0, 8.0}, out[2]);
    }

    @Test
    void aSeriesCmpmapHandlesAPartialLastBatch() {
        CmpMap m = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {1.0}), true);
        int n = 8192 + 5;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
        }
        double[][] o = new double[1][n];
        m.transformInto(new double[][] {x}, n, true, o);
        for (int i = 0; i < n; i++) {
            assertEquals(2.0 * x[i] + 1.0, o[0][i]);
        }
    }

    @Test
    void aSeriesCmpmapOfNoPointsStillChecksItsDirection() {
        // A non-square MatrixMap has no inverse, so neither has the series.
        MatrixMap first = new MatrixMap(1, 2, new double[][] {{1.0}, {2.0}});
        CmpMap m = new CmpMap(first, new UnitMap(2), true);
        assertThrows(AstException.class, () -> m.transformInto(new double[][] {{}, {}}, 0, false, new double[][] {{}}));
    }

    /**
     * The same series through the NaN protocol's entry point, which has no top-level check of its
     * own: the error comes from the series asking each component for its stage, even with no
     * points to transform.
     */
    @Test
    void aSeriesCmpmapOfNoPointsChecksEachComponentOnTheNanPath() {
        MatrixMap first = new MatrixMap(1, 2, new double[][] {{1.0}, {2.0}});
        CmpMap m = new CmpMap(first, new UnitMap(2), true);
        assertThrows(
                AstException.class, () -> m.transformIntoNan(new double[][] {{}, {}}, 0, false, new double[][] {{}}));
        assertTrue(m.getTranForward());
    }
}
