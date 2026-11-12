package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_bulk_kernels.rs}: the whole-axis kernels take every
 * non-finite input as bad, and write only the points they are given into outputs longer than the
 * input.
 *
 * <p>The kernels of later classes are translated in their tasks: {@code winmap_kernel}, {@code
 * permmap_kernel}, {@code diagonal_matrixmap_kernel}, {@code
 * full_matrixmap_kernel_ignores_a_bad_input_with_a_zero_coefficient}, {@code
 * full_matrixmap_kernel_crosses_its_block_boundary}, {@code
 * full_matrixmap_kernel_matches_c_for_each_input_count} and the four CmpMap tests in Task 7. The
 * UnitNormMap and NullRegion tests need classes outside the first phase and are deferred.
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
}
