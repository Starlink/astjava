package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of {@link FitsWcsDecompose}: the unit tests of Rust {@code fitschan/wcs_decompose.rs}
 * ({@code split_cd}, C's {@code SplitMat}), and tests of C's {@code FindBasisVectors}, {@code
 * FitLine} and {@code FitOK} whose expectations follow from those functions in fitschan.c.
 */
class FitsWcsDecomposeTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void splitCdDiagonal() {
        double[] m = {-0.001, 0.0, 0.0, 0.001};
        double[] cdelt = new double[2];
        assertTrue(FitsWcsDecompose.splitMat(2, m, cdelt));
        assertEquals(-0.001, cdelt[0], 1e-15);
        assertEquals(0.001, cdelt[1], 1e-15);
        assertEquals(1.0, m[0], 1e-15);
        assertEquals(1.0, m[3], 1e-15);
    }

    @Test
    void splitCdRotated() {
        double s = 0.001;
        double cos45 = Math.sqrt(0.5);
        double[] m = {s * cos45, -s * cos45, s * cos45, s * cos45};
        double[] cdelt = new double[2];
        assertTrue(FitsWcsDecompose.splitMat(2, m, cdelt));
        assertEquals(s, Math.abs(cdelt[0]), 1e-12);
        assertEquals(s, Math.abs(cdelt[1]), 1e-12);
        assertEquals(1.0, Math.sqrt(m[0] * m[0] + m[1] * m[1]), 1e-12);
        assertEquals(1.0, Math.sqrt(m[2] * m[2] + m[3] * m[3]), 1e-12);
    }

    /** SplitMat (fitschan.c) stops at the first bad element and returns zero. */
    @Test
    void splitMatStopsAtABadElement() {
        double[] m = {BAD, 0.0, 0.0, 1.0};
        double[] cdelt = {7.0, 7.0};
        assertFalse(FitsWcsDecompose.splitMat(2, m, cdelt));
        assertArrayEquals(new double[] {BAD, 0.0, 0.0, 1.0}, m);
        assertArrayEquals(new double[] {7.0, 7.0}, cdelt);
    }

    /** FitOK accepts a mean squared residual strictly below the squared tolerance. */
    @Test
    void fitOkIsStrict() {
        assertFalse(FitsWcsDecompose.fitOK(2, new double[] {0.5, 0.5}, new double[] {0.0, 0.0}, 0.5));
        assertTrue(FitsWcsDecompose.fitOK(2, new double[] {0.5, 0.0}, new double[] {0.0, 0.0}, 0.5));
    }

    /** FitOK skips pairs with a bad value, and fails when none is left. */
    @Test
    void fitOkSkipsBadValues() {
        assertTrue(FitsWcsDecompose.fitOK(2, new double[] {BAD, 1.0}, new double[] {0.0, 1.0}, 1e-10));
        assertFalse(FitsWcsDecompose.fitOK(2, new double[] {BAD, 1.0}, new double[] {0.0, BAD}, 1.0));
    }

    /** With every dimension known, the first root position tried is the grid centre. */
    @Test
    void basisVectorsStartAtTheGridCentre() {
        ShiftMap map = new ShiftMap(new double[] {10.0, -20.0});
        double[][] ptrg = new double[2][3];
        double[][] ptrw = new double[2][3];
        assertTrue(FitsWcsDecompose.findBasisVectors(map, 2, 2, new double[] {100.0, 50.0}, ptrg, ptrw));
        assertArrayEquals(new double[] {50.5, 51.5, 50.5}, ptrg[0]);
        assertArrayEquals(new double[] {25.5, 25.5, 26.5}, ptrg[1]);
        assertArrayEquals(new double[] {60.5, 61.5, 60.5}, ptrw[0]);
        assertArrayEquals(new double[] {5.5, 5.5, 6.5}, ptrw[1]);
    }

    /** With a dimension unknown, the first root position tried is (1, 1, ...). */
    @Test
    void basisVectorsStartAtOneWithoutDimensions() {
        ShiftMap map = new ShiftMap(new double[] {10.0, -20.0});
        double[][] ptrg = new double[2][3];
        double[][] ptrw = new double[2][3];
        assertTrue(FitsWcsDecompose.findBasisVectors(map, 2, 2, new double[] {100.0, BAD}, ptrg, ptrw));
        assertArrayEquals(new double[] {1.0, 2.0, 1.0}, ptrg[0]);
        assertArrayEquals(new double[] {1.0, 1.0, 2.0}, ptrg[1]);
        assertTrue(FitsWcsDecompose.findBasisVectors(map, 2, 2, null, ptrg, ptrw));
        assertArrayEquals(new double[] {1.0, 2.0, 1.0}, ptrg[0]);
    }

    /** FitLine gives the vector in the output space of a unit step along the input line. */
    @Test
    void fitLineOfALinearMapping() {
        MatrixMap map = new MatrixMap(2, 2, new double[][] {{3.0, 1.0}, {-2.0, 0.5}});
        double[] g0 = {50.5, 50.5};
        double[] w0 = {3.0 * 50.5 + 50.5, -2.0 * 50.5 + 0.5 * 50.5};
        double[] tol = {3.0e-3, 2.0e-3};
        double[] col = FitsWcsDecompose.fitLine(map, new double[] {1.0, 0.0}, g0, w0, 100.0, tol);
        assertNotNull(col);
        assertEquals(3.0, col[0], 1e-12);
        assertEquals(-2.0, col[1], 1e-12);
        col = FitsWcsDecompose.fitLine(map, new double[] {0.0, 1.0}, g0, w0, BAD, tol);
        assertNotNull(col);
        assertEquals(1.0, col[0], 1e-12);
        assertEquals(0.5, col[1], 1e-12);
    }

    /** A constant output gives a zero component. */
    @Test
    void fitLineOfAConstantOutputIsZero() {
        PermMap map = new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {7.0});
        double[] col = FitsWcsDecompose.fitLine(
                map, new double[] {1.0}, new double[] {5.0}, new double[] {5.0, 7.0}, 10.0, new double[] {1e-3, 1e-3});
        assertNotNull(col);
        assertEquals(1.0, col[0], 1e-12);
        assertEquals(0.0, col[1]);
    }

    /** FitLine returns no vector for a zero dimension, a bad root value, or a non-linear Mapping. */
    @Test
    void fitLineFailures() {
        ZoomMap zoom = new ZoomMap(1, 2.0);
        double[] g = {1.0};
        double[] tol = {1e-3};
        assertNull(FitsWcsDecompose.fitLine(zoom, g, new double[] {5.0}, new double[] {10.0}, 0.0, tol));
        assertNull(FitsWcsDecompose.fitLine(zoom, g, new double[] {5.0}, new double[] {BAD}, 10.0, tol));
        WcsMap tan = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        tan.invert();
        double[] g0 = {0.0, 0.0};
        double[][] w0 = tan.transform(new double[][] {{0.0}, {0.0}}, 1, true);
        assertNull(FitsWcsDecompose.fitLine(
                tan, new double[] {1.0, 0.0}, g0, new double[] {w0[0][0], w0[1][0]}, BAD, new double[] {1e-3, 1e-3}));
    }
}
