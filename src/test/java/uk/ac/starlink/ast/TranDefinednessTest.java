package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_tran_definedness.rs}: which directions a Mapping defines,
 * its TranForward and TranInverse attributes, in its current orientation, a CmpMap combining its
 * components'.
 *
 * <p>The PolyMap tests ({@code polymap_*} and the {@code cmpmap_*} tests built on a PolyMap) are
 * translated in Task 12; {@code chebymap_forward_only_has_no_inverse} and {@code
 * tranmap_needs_both_directions} need classes outside the first phase.
 */
class TranDefinednessTest {

    /**
     * A 2-out, 3-in matrix that stays in full form, so that, not being square, it has no inverse
     * (matrixmap.c {@code InvertMatrix}).
     */
    private static MatrixMap nonSquareFullMatrixmap() {
        return new MatrixMap(3, 2, new double[][] {{1.0, 2.0, 3.0}, {4.0, 5.0, 6.0}});
    }

    @Test
    void matrixmapNonSquareFullHasNoInverse() {
        MatrixMap mm = nonSquareFullMatrixmap();
        assertTrue(mm.getTranForward());
        assertFalse(mm.getTranInverse());
    }

    @Test
    void matrixmapInvertSwapsDefinedness() {
        MatrixMap mm = nonSquareFullMatrixmap();
        mm.invert();
        assertFalse(mm.getTranForward());
        assertTrue(mm.getTranInverse());
    }

    @Test
    void matrixmapSingularSquareHasNoInverse() {
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {2.0, 4.0}});
        assertTrue(mm.getTranForward());
        assertFalse(mm.getTranInverse());
    }

    /**
     * A 2-out, 3-in selection matrix compresses to unit form, which C defines in both directions
     * whatever its shape (matrixmap.c {@code GetTranForward} and {@code GetTranInverse}).
     */
    @Test
    void matrixmapNonSquareUnitHasBothDirections() {
        MatrixMap mm = new MatrixMap(3, 2, new double[][] {{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}});
        assertTrue(mm.getTranForward());
        assertTrue(mm.getTranInverse());
        mm.invert();
        assertTrue(mm.getTranForward());
        assertTrue(mm.getTranInverse());
    }

    @Test
    void cmpmapOfTwoWayComponentsHasBothDirections() {
        CmpMap cmp = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 0.5), true);
        assertTrue(cmp.getTranForward());
        assertTrue(cmp.getTranInverse());
    }
}
