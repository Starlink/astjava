package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_matrixmap.rs}. A Rust matrix given as rows is jniast's
 * {@code double[nout][nin]}.
 *
 * <p>A MatrixMap's rate is C's {@code Rate} (matrixmap.c): the element of the matrix applied
 * forward. The Rust tests of the pairwise merge ({@code try_merge}) offer the two Mappings here, as
 * a list, to the MatrixMap's {@code MapMerge} (matrixmap.c), which is where C merges a MatrixMap
 * with a PermMap or a WinMap, whichever comes first. {@code
 * an_inverted_non_square_matrixmap_equals_its_copy} tests {@code astEqual}, which is outside the
 * first phase.
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

    /** The list of {@code a} and {@code b} in series, applied with the Invert values given. */
    private static MergeList pair(Mapping a, Mapping b, boolean inva, boolean invb) {
        return new MergeList(Arrays.asList(a, b), Arrays.asList(inva, invb));
    }

    @Test
    void matrixMergeWithPermmapAfter() {
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        MergeList list = pair(mm, pm, false, false);
        assertEquals(0, mm.mapMerge(list, 0, true), "MatrixMap + PermMap should merge");
        assertEquals(1, list.size());
        assertFalse(list.invert(0));
        double[] in = {1.0, 2.0};
        double[] expected = point(transformRows(new CmpMap(mm, pm, true), true, in), 0);
        double[] actual = point(transformRows(list.map(0), true, in), 0);
        assertArrayEquals(expected, actual);
        assertArrayEquals(new double[] {11.0, 5.0}, actual);
    }

    @Test
    void matrixMergeWithPermmapBefore() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        Mapping simplified = new CmpMap(pm, mm, true).simplify();
        assertInstanceOf(MatrixMap.class, simplified, "PermMap + MatrixMap should simplify to MatrixMap");
        assertArrayEquals(new double[] {4.0, 10.0}, point(transformRows(simplified, true, new double[] {1.0, 2.0}), 0));
    }

    /** The PermMap first: C merges the pair through the MatrixMap's {@code MapMerge}. */
    @Test
    void permmapTryMergeWithMatrixmap() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        MergeList list = pair(pm, mm, false, false);
        assertEquals(0, mm.mapMerge(list, 1, true), "PermMap + MatrixMap should merge");
        assertEquals(1, list.size());
        assertArrayEquals(
                new double[] {4.0, 10.0}, point(transformRows(list.map(0), true, new double[] {1.0, 2.0}), 0));
    }

    @Test
    void matrixMergeWithInvertedPermmap() {
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        MergeList list = pair(mm, pm, false, true);
        assertEquals(0, mm.mapMerge(list, 0, true), "MatrixMap + inverted PermMap should merge");
        assertEquals(1, list.size());
        assertArrayEquals(
                new double[] {11.0, 5.0}, point(transformRows(list.map(0), true, new double[] {1.0, 2.0}), 0));
        assertFalse(pm.getInvert(), "the PermMap's own Invert attribute is left as it was");
    }

    /**
     * A PermMap giving an output a constant has directions that disagree (matrixmap.c {@code
     * PermOK}), and a link that works in one direction only, so it neither merges nor swaps.
     */
    @Test
    void matrixNoMergeWithConstantPermmap() {
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {-1, 1}, new double[] {99.0});
        MergeList list = pair(mm, pm, false, false);
        assertEquals(-1, mm.mapMerge(list, 0, true), "PermMap with constants should not merge into MatrixMap");
        assertEquals(2, list.size());
        assertSame(mm, list.map(0));
        assertSame(pm, list.map(1));
    }

    @Test
    void diagonalMatrixMergeWithWinmap() {
        MatrixMap mm = full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0});
        WinMap wm = new WinMap(new double[] {1.0, 1.0}, new double[] {1.0, 1.0});
        MergeList list = pair(mm, wm, false, false);
        assertEquals(0, mm.mapMerge(list, 0, true), "diagonal MatrixMap + WinMap should merge");
        assertEquals(1, list.size());
        double[] in = {5.0, 7.0};
        double[] expected = point(transformRows(new CmpMap(mm, wm, true), true, in), 0);
        assertArrayEquals(expected, point(transformRows(list.map(0), true, in), 0), 1e-10);
    }

    /**
     * A full MatrixMap does not merge with a WinMap. It can be swapped with one, but with nothing
     * beyond the WinMap to move towards, the swap is only tried on copies, and kept only if a
     * swapped Mapping then simplifies to another class, which the swap's own simplification of each
     * has already done.
     */
    @Test
    void nonDiagonalMatrixNoMergeWithWinmap() {
        MatrixMap mm = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        WinMap wm = new WinMap(new double[] {1.0, 1.0}, new double[] {1.0, 1.0});
        MergeList list = pair(mm, wm, false, false);
        assertEquals(-1, mm.mapMerge(list, 0, true), "non-diagonal MatrixMap should not merge with WinMap");
        assertSame(mm, list.map(0));
        assertSame(wm, list.map(1));
    }

    @Test
    void diagonalMatrixMergeWithWinmapScales() {
        MatrixMap mm = full(new double[] {3.0, 0.0}, new double[] {0.0, 2.0});
        WinMap wm = new WinMap(new double[] {10.0, 20.0}, new double[] {0.5, 4.0});
        MergeList list = pair(mm, wm, false, false);
        assertEquals(0, mm.mapMerge(list, 0, true));
        double[] in = {2.0, 3.0};
        double[] expected = point(transformRows(new CmpMap(mm, wm, true), true, in), 0);
        assertArrayEquals(new double[] {13.0, 44.0}, expected);
        assertArrayEquals(expected, point(transformRows(list.map(0), true, in), 0), 1e-10);
    }

    /** A full matrix read from a dump, its off-diagonal elements zero, simplifies to diagonal form. */
    @Test
    void simplifiedSwitchesFullToDiagonalWhenOffdiagonalsAreZero() {
        String src = ChannelText.text(
                " Begin MatrixMap",
                "    Nin = 2",
                " IsA Mapping",
                "    M0 = 2",
                "    M1 = 0",
                "    M2 = 0",
                "    M3 = 3",
                "    IM0 = 0.5",
                "    IM1 = 0",
                "    IM2 = 0",
                "    IM3 = 0.33333333333333331",
                "    Form = \"Full\"",
                " End MatrixMap");
        Mapping map = (Mapping) ChannelText.read(src);
        MatrixMap mm = assertInstanceOf(MatrixMap.class, map.simplify(), "still a MatrixMap");
        String s = ChannelText.dump(mm);
        assertTrue(s.contains("Form = \"Diagonal\""), "expected Diagonal form after simplify, got:\n" + s);
        assertFalse(s.contains("M2 ="), "diagonal form should not emit M2");
    }

    /** An inverted singular MatrixMap, which has no forward transformation. */
    private static MatrixMap invertedSingular() {
        MatrixMap m = full(new double[] {1.0, 1.0}, new double[] {1.0, 1.0});
        m.invert();
        return m;
    }

    /**
     * Merging a MatrixMap with a neighbour multiplies the matrices, which an inverted singular
     * MatrixMap cannot take part in, and C passes the error up from the simplification.
     */
    @Test
    void mergingWithAnInvertedSingularMatrixIsAnError() {
        Mapping[][] pairs = {
            {full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}), invertedSingular()},
            {invertedSingular(), new ZoomMap(2, 3.0)}
        };
        for (Mapping[] p : pairs) {
            CmpMap map = new CmpMap(p[0], p[1], true);
            AstException e = assertThrows(AstException.class, map::simplify);
            assertEquals(AstException.AST__MTRML, e.getStatus());
        }
    }
}
