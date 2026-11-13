package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests in Rust {@code src/mappings/matrix.rs}, which invert a full
 * matrix as C's {@code InvertMatrix} (matrixmap.c) does, through {@code palDmat}.
 */
class MatrixMapModuleTest {

    @Test
    void gaussJordan2x2() {
        double[] inv = MatrixMap.invertMatrix(MatrixMap.FULL, 2, 2, new double[] {2.0, 0.0, 0.0, 4.0});
        assertNotNull(inv);
        assertEquals(0.5, inv[0], 1e-12);
        assertEquals(0.0, inv[1], 1e-12);
        assertEquals(0.0, inv[2], 1e-12);
        assertEquals(0.25, inv[3], 1e-12);
    }

    @Test
    void gaussJordanSingular() {
        assertNull(MatrixMap.invertMatrix(MatrixMap.FULL, 2, 2, new double[] {1.0, 2.0, 2.0, 4.0}));
    }
}
