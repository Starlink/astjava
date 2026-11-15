package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_matrixmap.rs}. A Rust matrix given as rows is jniast's
 * {@code double[nout][nin]}.
 *
 * <p>A MatrixMap's rate is C's {@code Rate} (matrixmap.c): the element of the matrix applied
 * forward. The merge tests and {@code
 * simplified_switches_full_to_diagonal_when_offdiagonals_are_zero} and {@code
 * merging_with_an_inverted_singular_matrix_is_an_error} test simplification, and are deferred;
 * {@code an_inverted_non_square_matrixmap_equals_its_copy} tests {@code astEqual}, which is outside
 * the first phase. {@code matrix_map_simplified_identity} and {@code
 * matrix_map_simplified_non_identity_preserved} check only that the simplified Mapping transforms
 * as the original does, which holds of a Mapping that is not restructured.
 */
class MatrixMapTest {

    private static final double BAD = AstObject.AST__BAD;

    /** A MatrixMap of the full matrix given as rows. */
    private static MatrixMap full(double[]... rows) {
        return new MatrixMap(rows[0].length, rows.length, rows);
    }

    @Test
    void matrixMapNonSquareDimensions() {
        MatrixMap mm = full(new double[] {1.0, 2.0, 3.0}, new double[] {4.0, 5.0, 6.0});
        assertEquals(3, mm.getNin());
        assertEquals(2, mm.getNout());
    }

    @Test
    void matrixMapIdentityPassthrough() {
        MatrixMap mm = full(new double[] {1.0, 0.0}, new double[] {0.0, 1.0});
        double[][] out = transformRows(mm, true, new double[] {3.0, 7.0}, new double[] {-1.0, 4.0});
        assertArrayEquals(new double[] {3.0, 7.0}, point(out, 0));
        assertArrayEquals(new double[] {-1.0, 4.0}, point(out, 1));
    }

    @Test
    void matrixMapRotation90() {
        MatrixMap mm = full(new double[] {0.0, -1.0}, new double[] {1.0, 0.0});
        double[] out = point(transformRows(mm, true, new double[] {1.0, 0.0}), 0);
        assertEquals(0.0, out[0], 1e-12);
        assertEquals(1.0, out[1], 1e-12);
    }

    @Test
    void matrixMapInverseScale() {
        MatrixMap mm = full(new double[] {2.0, 0.0}, new double[] {0.0, 2.0});
        double[] out = point(transformRows(mm, false, new double[] {4.0, 6.0}), 0);
        assertEquals(2.0, out[0], 1e-12);
        assertEquals(3.0, out[1], 1e-12);
    }

    @Test
    void matrixMapNonSquareInverseError() {
        MatrixMap mm = full(new double[] {1.0, 2.0, 3.0}, new double[] {4.0, 5.0, 6.0});
        AstException e = assertThrows(AstException.class, () -> transformRows(mm, false, new double[] {1.0, 2.0}));
        assertEquals(AstException.AST__TRNND, e.getStatus());
    }

    @Test
    void matrixMapBadValuePropagation() {
        MatrixMap mm = full(new double[] {1.0, 0.0}, new double[] {0.0, 1.0});
        double[] out = point(transformRows(mm, true, new double[] {BAD, 3.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(3.0, out[1]);
    }

    @Test
    void matrixBadPerAxisZeroCoeff() {
        // Row 0: [1, 0, 2] (the second input is masked); row 1: [0, 1, 1].
        MatrixMap mm = full(new double[] {1.0, 0.0, 2.0}, new double[] {0.0, 1.0, 1.0});
        double[] out = point(transformRows(mm, true, new double[] {3.0, BAD, 5.0}), 0);
        assertEquals(1.0 * 3.0 + 0.0 + 2.0 * 5.0, out[0]);
        assertEquals(BAD, out[1]);
    }

    @Test
    void matrixBadAllInputsBad() {
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        double[] out = point(transformRows(mm, true, new double[] {BAD, BAD}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(BAD, out[1]);
    }

    @Test
    void matrixMapSimplifiedIdentity() {
        Mapping simplified =
                full(new double[] {1.0, 0.0}, new double[] {0.0, 1.0}).simplify();
        assertArrayEquals(new double[] {5.0, 9.0}, point(transformRows(simplified, true, new double[] {5.0, 9.0}), 0));
    }

    @Test
    void matrixMapSimplifiedNonIdentityPreserved() {
        Mapping simplified =
                full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0}).simplify();
        double[] out = point(transformRows(simplified, true, new double[] {1.0, 1.0}), 0);
        assertEquals(2.0, out[0], 1e-12);
        assertEquals(3.0, out[1], 1e-12);
    }

    @Test
    void matrixDiagonalFastPath() {
        MatrixMap mm = full(new double[] {2.0, 0.0, 0.0}, new double[] {0.0, 3.0, 0.0}, new double[] {0.0, 0.0, 4.0});
        assertArrayEquals(
                new double[] {2.0, 6.0, 12.0}, point(transformRows(mm, true, new double[] {1.0, 2.0, 3.0}), 0));
        double[] inv = point(transformRows(mm, false, new double[] {1.0, 2.0, 3.0}), 0);
        assertEquals(0.5, inv[0], 1e-12);
        assertEquals(2.0 / 3.0, inv[1], 1e-12);
        assertEquals(0.75, inv[2], 1e-12);
    }

    @Test
    void matrixDiagonalFastPathLarge() {
        int n = 100;
        double[][] big = new double[n][n];
        double[] ones = new double[n];
        for (int i = 0; i < n; i++) {
            big[i][i] = i + 1;
            ones[i] = 1.0;
        }
        double[] out = point(transformRows(new MatrixMap(n, n, big), true, ones), 0);
        for (int i = 0; i < n; i++) {
            assertEquals(i + 1, out[i]);
        }
    }

    @Test
    void matrixDiagonalBadHandling() {
        // The off-diagonal zeros compress the matrix to the diagonal form (matrixmap.c
        // CompressMatrix), whose Transform multiplies a zero diagonal through rather than
        // masking a bad input; only a bad diagonal element makes its axis bad.
        MatrixMap mm = full(new double[] {0.0, 0.0}, new double[] {0.0, 5.0});
        double[] out = point(transformRows(mm, true, new double[] {BAD, 3.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(15.0, out[1]);

        double[] out2 = point(transformRows(mm, true, new double[] {2.0, BAD}), 0);
        assertEquals(0.0, out2[0]);
        assertEquals(BAD, out2[1]);
    }

    @Test
    void matrixFullBadCoefficientAndMasking() {
        MatrixMap mm = full(new double[] {2.0, BAD}, new double[] {3.0, 4.0});
        double[] out = point(transformRows(mm, true, new double[] {1.0, 1.0}), 0);
        assertEquals(BAD, out[0]);
        assertEquals(7.0, out[1]);

        // The off-diagonal [1][0] is not zero, so the matrix stays full.
        MatrixMap mm2 = full(new double[] {1.0, 0.0}, new double[] {5.0, 1.0});
        double[] out2 = point(transformRows(mm2, true, new double[] {3.0, BAD}), 0);
        assertEquals(3.0, out2[0]);
        assertEquals(BAD, out2[1]);
    }

    // ------------------------------------------------------------------
    // astMtrRot and astMtrMult (matrixmap.c), checked against the documented rotation and product
    // formulae: these C functions are protected, so no oracle covers them.
    // ------------------------------------------------------------------

    private static double[] fwd(MatrixMap m, double... input) {
        return point(transformRows(m, true, input), 0);
    }

    @Test
    void mtrRot2dClockwise() {
        MatrixMap r = full(new double[] {1.0, 0.0}, new double[] {0.0, 1.0}).mtrRot(0.3, new double[0]);
        double c = Math.cos(0.3);
        double s = Math.sin(0.3);
        double[] o0 = fwd(r, 1.0, 0.0);
        double[] o1 = fwd(r, 0.0, 1.0);
        assertEquals(c, o0[0], 1e-12);
        assertEquals(-s, o0[1], 1e-12);
        assertEquals(s, o1[0], 1e-12);
        assertEquals(c, o1[1], 1e-12);
    }

    @Test
    void mtrRotFullTurnIsUnchanged() {
        MatrixMap m = full(new double[] {2.0, 1.0}, new double[] {0.0, 3.0});
        MatrixMap r = m.mtrRot(0.0, new double[0]);
        assertArrayEquals(fwd(m, 1.0, 0.0), fwd(r, 1.0, 0.0));
        assertArrayEquals(fwd(m, 0.0, 1.0), fwd(r, 0.0, 1.0));
    }

    @Test
    void mtrRot3dAboutZLeavesZFixed() {
        // C's 3-D form (matrixmap.c astMtrRot) about (0,0,1) sends the x basis vector to (cos, sin,
        // 0): the opposite off-diagonal sign from the 2-D form, as in C.
        MatrixMap m = full(new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0}, new double[] {0.0, 0.0, 1.0});
        MatrixMap r = m.mtrRot(0.5, new double[] {0.0, 0.0, 1.0});
        double[] ox = fwd(r, 1.0, 0.0, 0.0);
        assertEquals(Math.cos(0.5), ox[0], 1e-12);
        assertEquals(Math.sin(0.5), ox[1], 1e-12);
        assertEquals(0.0, ox[2], 1e-12);
        double[] oz = fwd(r, 0.0, 0.0, 1.0);
        assertEquals(0.0, oz[0], 1e-12);
        assertEquals(0.0, oz[1], 1e-12);
        assertEquals(1.0, oz[2], 1e-12);
    }

    @Test
    void mtrRot3dNullAxisErrors() {
        MatrixMap m = full(new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0}, new double[] {0.0, 0.0, 1.0});
        assertEquals(
                AstException.AST__MTRAX,
                assertThrows(AstException.class, () -> m.mtrRot(0.5, new double[] {0.0, 0.0, 0.0}))
                        .getStatus());
        assertEquals(
                AstException.AST__MTRAX,
                assertThrows(AstException.class, () -> m.mtrRot(0.5, null)).getStatus());
    }

    @Test
    void mtrRotWrongDimensionErrors() {
        MatrixMap m1 = full(new double[] {2.0});
        assertEquals(
                AstException.AST__MTR23,
                assertThrows(AstException.class, () -> m1.mtrRot(0.5, new double[0]))
                        .getStatus());
    }

    @Test
    void mtrMultReturnsATimesThis() {
        MatrixMap self = full(new double[] {1.0, 1.0}, new double[] {0.0, 1.0});
        MatrixMap a = full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0});
        MatrixMap prod = self.mtrMult(a);
        assertArrayEquals(new double[] {2.0, 0.0}, fwd(prod, 1.0, 0.0));
        assertArrayEquals(new double[] {2.0, 3.0}, fwd(prod, 0.0, 1.0));
    }

    @Test
    void mtrMultShapeMismatchErrors() {
        MatrixMap self = full(new double[] {1.0, 0.0}, new double[] {0.0, 1.0}, new double[] {1.0, 1.0});
        MatrixMap a = full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0});
        assertEquals(
                AstException.AST__MTRML,
                assertThrows(AstException.class, () -> self.mtrMult(a)).getStatus());
    }

    /** A MatrixMap without a forward transformation cannot be multiplied, whichever factor it is. */
    @Test
    void mtrMultWithoutAForwardTransformationErrors() {
        MatrixMap singular = full(new double[] {1.0, 1.0}, new double[] {1.0, 1.0});
        singular.invert();
        MatrixMap other = full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0});
        assertEquals(
                AstException.AST__MTRML,
                assertThrows(AstException.class, () -> singular.mtrMult(other)).getStatus());
        assertEquals(
                AstException.AST__MTRML,
                assertThrows(AstException.class, () -> other.mtrMult(singular)).getStatus());
    }

    @Test
    void mtrMultNonSquareProduct() {
        MatrixMap self = full(new double[] {1.0, 0.0}, new double[] {0.0, 1.0}, new double[] {1.0, 1.0});
        MatrixMap a = full(new double[] {1.0, 0.0, 1.0}, new double[] {0.0, 1.0, 0.0});
        MatrixMap prod = self.mtrMult(a);
        assertArrayEquals(new double[] {2.0, 0.0}, fwd(prod, 1.0, 0.0));
        assertArrayEquals(new double[] {1.0, 1.0}, fwd(prod, 0.0, 1.0));
    }

    /**
     * A diagonal MatrixMap over a long array: a bad, NaN or infinite input gives a bad output, and
     * a finite input whose product overflows gives the infinite product, wherever they fall.
     */
    @Test
    void matrixDiagonalLongArrayWithNonFiniteInputs() {
        double[] diag = {2.0, 0.0, 1e300};
        MatrixMap mm = new MatrixMap(3, 3, diag);
        int n = 2051;
        double[] specials = {BAD, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, 1e10
        };
        double[][] in = new double[3][n];
        for (int a = 0; a < 3; a++) {
            for (int i = 0; i < n; i++) {
                in[a][i] = (i % 97 == a * 13 || i == n - 1 - a)
                        ? specials[(i / 97 + a) % specials.length]
                        : (i - 1000.0) * 0.37;
            }
        }
        double[][] out = mm.transform(in, n, true);
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < 3; a++) {
                double v = in[a][i];
                double want = v == BAD || !Double.isFinite(v) ? BAD : diag[a] * v;
                assertEquals(
                        Double.doubleToRawLongBits(want),
                        Double.doubleToRawLongBits(out[a][i]),
                        "point " + i + " axis " + a + ", input " + v);
            }
        }
        boolean overflow = false;
        boolean nan = false;
        for (int i = 0; i < n; i++) {
            overflow |= in[2][i] == 1e10;
            nan |= Double.isNaN(in[1][i]);
        }
        assertTrue(overflow && nan, "every kind of special value, and an overflow, occurs");
    }

    // ------------------------------------------------------------------
    // jniast's constructors
    // ------------------------------------------------------------------

    @Test
    void theUnitConstructorCopiesTheInputs() {
        MatrixMap mm = new MatrixMap(2, 3);
        assertTrue(mm.getTranForward() && mm.getTranInverse());
        assertArrayEquals(new double[] {4.0, 5.0, 0.0}, fwd(mm, 4.0, 5.0));
        assertArrayEquals(new double[] {4.0, 5.0}, point(transformRows(mm, false, new double[] {4.0, 5.0, 6.0}), 0));
    }

    @Test
    void theDiagonalConstructorDropsSurplusInputs() {
        MatrixMap mm = new MatrixMap(3, 2, new double[] {2.0, 4.0});
        assertTrue(mm.getTranInverse());
        assertArrayEquals(new double[] {2.0, 8.0}, fwd(mm, 1.0, 2.0, 3.0));
        // The inverse sets the input that cannot be recovered to zero (matrixmap.c Transform).
        assertArrayEquals(new double[] {1.0, 2.0, 0.0}, point(transformRows(mm, false, new double[] {2.0, 8.0}), 0));
    }

    @Test
    void aMatrixOfTheWrongShapeIsRefused() {
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> new MatrixMap(2, 2, new double[][] {{1.0, 2.0}}))
                        .getStatus());
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {3.0}}))
                        .getStatus());
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> new MatrixMap(2, 3, new double[] {1.0}))
                        .getStatus());
        // matrixmap.c astInitMatrixMap: no matrix at all, or one holding only bad values.
        assertEquals(
                AstException.AST__MTRMT,
                assertThrows(AstException.class, () -> new MatrixMap(2, 2, (double[]) null))
                        .getStatus());
        assertEquals(
                AstException.AST__MTRMT,
                assertThrows(AstException.class, () -> new MatrixMap(1, 1, new double[][] {{Double.NaN}}))
                        .getStatus());
    }

    @Test
    void aCopyDumpsAsTheOriginal() {
        for (MatrixMap mm : new MatrixMap[] {
            full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}),
            new MatrixMap(2, 2, new double[] {2.0, 3.0}),
            new MatrixMap(2, 2)
        }) {
            assertEquals(ChannelText.dump(mm), ChannelText.dump(mm.copy()));
        }
    }

    @Test
    void matrixRateAnalytical() {
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {3.0, 4.0}});
        double[] at = {0.0, 0.0};
        assertEquals(1.0, mm.rate(at, 1, 1));
        assertEquals(2.0, mm.rate(at, 1, 2));
        assertEquals(3.0, mm.rate(at, 2, 1));
        assertEquals(4.0, mm.rate(at, 2, 2));
    }

    @Test
    void matrixRateAnalyticalInverted() {
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{2.0, 0.0}, {0.0, 4.0}});
        mm.invert();
        double[] at = {0.0, 0.0};
        assertEquals(0.5, mm.rate(at, 1, 1), 1e-15);
        assertEquals(0.0, mm.rate(at, 1, 2));
        assertEquals(0.0, mm.rate(at, 2, 1));
        assertEquals(0.25, mm.rate(at, 2, 2), 1e-15);
    }

    /** The rates of a unit and of a non-square full MatrixMap (matrixmap.c {@code Rate}). */
    @Test
    void unitAndNonSquareRates() {
        MatrixMap unit = new MatrixMap(3, 2);
        double[] at3 = {1.0, 2.0, 3.0};
        assertEquals(1.0, unit.rate(at3, 2, 2));
        assertEquals(0.0, unit.rate(at3, 1, 3));
        MatrixMap full = new MatrixMap(3, 2, new double[][] {{1.0, 2.0, 3.0}, {4.0, 5.0, 6.0}});
        assertEquals(6.0, full.rate(at3, 2, 3));
        assertEquals(2.0, full.rate(at3, 1, 2));
    }
}
