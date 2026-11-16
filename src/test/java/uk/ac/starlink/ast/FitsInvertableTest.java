package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/invertable.rs} (C {@code
 * MakeInvertable}). C's matrix is indexed {@code fullmat[input][output]}, the transpose of the
 * Rust tests' {@code cd[[output, input]]}; the matrices here are the Rust ones transposed.
 */
class FitsInvertableTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void alreadyInvertableUnchanged() {
        double[][] m = {{1.0, 0.0}, {0.0, 1.0}};
        FitsInvertable.makeInvertable(m, 2, new double[] {100.0, 100.0});
        assertArrayEquals(new double[] {1.0, 0.0}, m[0]);
        assertArrayEquals(new double[] {0.0, 1.0}, m[1]);
    }

    /** Input 1 affects no output and spans one pixel: its diagonal element becomes 1. */
    @Test
    void degenerateInputZeroedColumnPatched() {
        double[][] m = {{2.0, 3.0}, {0.0, 0.0}};
        FitsInvertable.makeInvertable(m, 2, new double[] {100.0, 1.0});
        assertEquals(2.0, m[0][0]);
        assertEquals(3.0, m[0][1]);
        assertEquals(0.0, m[1][0]);
        assertEquals(1.0, m[1][1]);
    }

    @Test
    void nonDegenerateZeroedColumnLeftAlone() {
        double[][] m = {{2.0, 3.0}, {0.0, 0.0}};
        FitsInvertable.makeInvertable(m, 2, new double[] {100.0, 50.0});
        assertEquals(0.0, m[1][1]);
    }

    @Test
    void unknownDimTreatedAsNonDegenerate() {
        double[][] m = {{2.0, 3.0}, {0.0, 0.0}};
        FitsInvertable.makeInvertable(m, 2, new double[] {100.0, BAD});
        assertEquals(0.0, m[1][1]);
    }

    @Test
    void inputWithPartialZeroColumnUnchanged() {
        double[][] m = {{2.0, 3.0}, {0.5, 0.0}};
        FitsInvertable.makeInvertable(m, 2, new double[] {100.0, 1.0});
        assertEquals(0.5, m[1][0]);
        assertEquals(0.0, m[1][1]);
    }

    @Test
    void threeAxisOneDegenerate() {
        double[][] m = {{1.0, 0.0, 0.0}, {0.0, 0.0, 0.0}, {0.0, 1.0, 0.0}};
        FitsInvertable.makeInvertable(m, 3, new double[] {100.0, 1.0, 50.0});
        assertEquals(1.0, m[0][0]);
        assertEquals(1.0, m[1][1]);
        assertEquals(0.0, m[2][2]);
    }

    /**
     * C reads one dimension per matrix input, though its caller holds one per pixel axis, fewer when
     * the matrix has degenerate axes added; an input with no dimension is not patched.
     */
    @Test
    void inputsBeyondTheDimensionsAreNotPatched() {
        double[][] m = {{0.0, 0.0}, {0.0, 0.0}};
        FitsInvertable.makeInvertable(m, 2, new double[0]);
        assertEquals(0.0, m[0][0]);
        assertEquals(0.0, m[1][1]);
        FitsInvertable.makeInvertable(m, 2, new double[] {1.0});
        assertEquals(1.0, m[0][0]);
        assertEquals(0.0, m[1][1]);
    }
}
