package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.fromRows;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;
import static uk.ac.starlink.ast.PolyTerms.cPoly;
import static uk.ac.starlink.ast.PolyTerms.flat;
import static uk.ac.starlink.ast.PolyTerms.poly;
import static uk.ac.starlink.ast.PolyTerms.term;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_polymap.rs}, which ports C's {@code testpolymap.c}:
 * PolyMap's construction, transformations, iterative inverse, attributes, coefficients and native
 * round trips, and the PolyTran fits of {@code testpolymap.c}.
 *
 * <p>Rust's {@code PolyTerm} becomes a group of C's coefficient array ({@link PolyTerms}), and
 * Rust's {@code coeffs} is the package-private {@link PolyMap#polyCoeffs}, C's {@code
 * astPolyCoeffs}. The tests that check what C's simplification makes of a PolyMap ({@code
 * polymap_linear_decomposes_to_matrixmap}, {@code polymap_affine_decomposes}, {@code
 * polymap_affine_diagonal_decomposes_to_winmap}, {@code
 * polymap_whose_terms_all_cancel_becomes_a_matrixmap}, {@code
 * polymap_does_not_cancel_against_a_different_inverse}, {@code
 * polymap_fitted_inverse_cancels_soundly}, {@code ident_set_polymap_nominated_in_a_list_is_left_alone},
 * {@code c_simplify} and {@code polymap_affine_general_merges_with_a_neighbour}) are deferred
 * ({@code needs simplification}), those of {@code astEqual} ({@code
 * polymap_equal_compares_the_inverse_coefficients}, {@code
 * polymap_with_more_outputs_than_inputs_equals_its_copy}) are deferred with it, and {@code
 * polymap_bad_powers_len_rejected} tests the length of a Rust term's power vector, which C's
 * coefficient array cannot express. The tests that simplify only to compare transformed values
 * are kept, since simplification returns a Mapping that transforms identically.
 */
class PolyMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static void assertClose(double want, double got, double tol, String what) {
        assertTrue(Math.abs(got - want) < tol, what + ": got " + got + ", want " + want);
    }

    // ------------------------------------------------------------------
    // Construction and basic properties
    // ------------------------------------------------------------------

    @Test
    void polymapDimensions() {
        PolyMap m = poly(1, 1, term(1.0, 1, 0), term(3.0, 1, 1), term(2.0, 1, 2));
        assertEquals(1, m.getNin());
        assertEquals(1, m.getNout());
        assertFalse(m.getInvert());
    }

    /**
     * C's {@code astInitPolyMap} passes the counts to {@code astInitMapping}, which accepts zero
     * (mapping.c), so a PolyMap with no inputs is made; the Rust constructor refuses it.
     */
    @Test
    void polymapNinZeroRejected() {
        PolyMap m = new PolyMap(0, 1, 0, null, 0, null);
        assertEquals(0, m.getNin());
        assertFalse(m.getTranForward());
    }

    /** As {@link #polymapNinZeroRejected}, for the outputs. */
    @Test
    void polymapNoutZeroRejected() {
        PolyMap m = new PolyMap(1, 0, 0, null, 0, null);
        assertEquals(0, m.getNout());
        assertFalse(m.getTranForward());
    }

    @Test
    void polymapBadOutAxisRejected() {
        AstException e = assertThrows(AstException.class, () -> poly(1, 1, term(1.0, 2, 1)));
        assertEquals(AstException.AST__BADCI, e.getStatus());
    }

    @Test
    void polymapDefaults() {
        PolyMap m = poly(1, 1, term(1.0, 1, 1));
        // IterInverse defaults to "no inverse coefficients supplied" (polymap.c GetIterInverse).
        assertTrue(m.getIterInverse());
        assertEquals(4, m.getNiterInverse());
        assertEquals(1e-6, m.getTolInverse());
    }

    @Test
    void polymapIterInverseDefaultFollowsInverseCoefficients() {
        double[] fwd = term(2.0, 1, 1);
        PolyMap m = poly(1, 1, fwd);
        assertTrue(m.getIterInverse(), "no inverse coefficients: default on");
        double[][] out = transformRows(m, false, new double[] {8.0});
        assertClose(4.0, out[0][0], 1e-6, "x = y/2");

        PolyMap m2 = poly(1, 1, new double[][] {fwd}, new double[][] {term(0.5, 1, 1)});
        assertFalse(m2.getIterInverse(), "inverse coefficients present: default off");

        PolyMap m3 = poly(1, 1, fwd);
        m3.setIterInverse(false);
        assertFalse(m3.getIterInverse());
        assertFalse(m3.getTranInverse());
    }

    // ------------------------------------------------------------------
    // Transformations
    // ------------------------------------------------------------------

    @Test
    void polymap1dPolynomial() {
        PolyMap m = poly(1, 1, term(1.0, 1, 0), term(3.0, 1, 1), term(2.0, 1, 2));
        double[][] out = transformRows(m, true, new double[] {0.0}, new double[] {1.0}, new double[] {2.0});
        assertArrayEquals(new double[] {1.0, 6.0, 15.0}, out[0]);
    }

    @Test
    void polymap2dTo2d() {
        PolyMap m = poly(2, 2, term(1.0, 1, 1, 0), term(1.0, 1, 0, 1), term(1.0, 2, 1, 1));
        double[][] out = transformRows(m, true, new double[] {2.0, 3.0});
        assertClose(5.0, out[0][0], 1e-12, "x + y");
        assertClose(6.0, out[1][0], 1e-12, "x y");
        assertArrayEquals(new double[] {0.0, 0.0}, point(transformRows(m, true, new double[] {0.0, 0.0}), 0));
        out = transformRows(m, true, new double[] {-1.0, 4.0});
        assertClose(3.0, out[0][0], 1e-12, "x + y");
        assertClose(-4.0, out[1][0], 1e-12, "x y");
    }

    @Test
    void polymapExplicitInverseRoundtrip() {
        PolyMap m = poly(1, 1, new double[][] {term(2.0, 1, 1)}, new double[][] {term(0.5, 1, 1)});
        double[][] out = transformRows(m, true, new double[] {5.0});
        assertClose(10.0, out[0][0], 1e-12, "forward");
        double[][] back = m.transform(out, 1, false);
        assertClose(5.0, back[0][0], 1e-12, "inverse");
    }

    @Test
    void polymapIterativeInverse() {
        PolyMap m = poly(1, 1, term(1.0, 1, 0), term(1.0, 1, 2));
        m.setIterInverse(true);
        m.setNiterInverse(100);
        m.setTolInverse(1e-10);
        assertClose(1.0, transformRows(m, false, new double[] {2.0})[0][0], 1e-4, "y = 2");
        assertClose(2.0, transformRows(m, false, new double[] {5.0})[0][0], 1e-4, "y = 5");
    }

    @Test
    void polymapNoInverseIsAnError() {
        PolyMap m = poly(1, 1, term(1.0, 1, 2));
        m.setIterInverse(false);
        AstException e = assertThrows(AstException.class, () -> transformRows(m, false, new double[] {3.0}));
        assertEquals(AstException.AST__TRNND, e.getStatus());
    }

    @Test
    void polymapBadValuePropagation() {
        PolyMap m = poly(2, 2, term(1.0, 1, 1, 0), term(1.0, 2, 0, 1));
        double[][] out = transformRows(m, true, new double[] {BAD, 3.0});
        assertEquals(BAD, out[0][0]);
        assertEquals(3.0, out[1][0]);
        out = transformRows(m, true, new double[] {Double.NaN, 3.0});
        assertEquals(BAD, out[0][0]);
        assertEquals(3.0, out[1][0]);
    }

    @Test
    void polymapMultiTermSameAxis() {
        PolyMap m = poly(1, 1, term(7.0, 1, 0), term(1.0, 1, 1), term(3.0, 1, 2), term(5.0, 1, 3));
        double[][] out = transformRows(m, true, new double[] {0.0}, new double[] {1.0}, new double[] {2.0});
        assertClose(7.0, out[0][0], 1e-12, "f(0)");
        assertClose(16.0, out[0][1], 1e-12, "f(1)");
        assertClose(61.0, out[0][2], 1e-12, "f(2)");
    }

    @Test
    void polymapInvert() {
        PolyMap m = poly(1, 1, new double[][] {term(2.0, 1, 1)}, new double[][] {term(0.5, 1, 1)});
        assertClose(10.0, transformRows(m, true, new double[] {5.0})[0][0], 1e-12, "forward");
        m.invert();
        assertTrue(m.getInvert());
        assertEquals(1, m.getNin());
        assertEquals(1, m.getNout());
        assertClose(2.5, transformRows(m, true, new double[] {5.0})[0][0], 1e-12, "inverted forward");
    }

    @Test
    void polymapSetters() {
        PolyMap m = poly(1, 1, term(1.0, 1, 1));
        m.setIterInverse(true);
        assertTrue(m.getIterInverse());
        m.setNiterInverse(10);
        assertEquals(10, m.getNiterInverse());
        m.setTolInverse(1e-8);
        assertEquals(1e-8, m.getTolInverse());
    }

    // ------------------------------------------------------------------
    // Native round trips
    // ------------------------------------------------------------------

    private static PolyMap roundTrip(PolyMap m) {
        AstObject obj = ChannelText.read(ChannelText.dump(m));
        return assertInstanceOf(PolyMap.class, obj);
    }

    @Test
    void polymapSerialisationRoundtrip() {
        PolyMap m = poly(
                2,
                2,
                new double[][] {term(2.0, 1, 1, 0), term(3.0, 1, 0, 1), term(1.0, 2, 1, 1), term(5.0, 2, 0, 0)},
                new double[][] {term(0.5, 1, 1, 0), term(0.25, 2, 0, 1)});
        PolyMap m2 = roundTrip(m);
        double[] p = {2.0, 3.0};
        double[] a = point(transformRows(m, true, p), 0);
        double[] b = point(transformRows(m2, true, p), 0);
        for (int j = 0; j < 2; j++) {
            assertClose(a[j], b[j], 1e-12, "axis " + j);
        }
        a = point(transformRows(m, false, p), 0);
        b = point(transformRows(m2, false, p), 0);
        for (int j = 0; j < 2; j++) {
            assertClose(a[j], b[j], 1e-12, "inverse axis " + j);
        }
    }

    @Test
    void polymapSerialisationForwardOnly() {
        PolyMap m = poly(1, 1, term(2.0, 1, 2), term(3.0, 1, 1), term(1.0, 1, 0));
        PolyMap m2 = roundTrip(m);
        double[][] pts = fromRows(new double[] {0.0}, new double[] {1.0}, new double[] {2.0});
        double[][] a = m.transform(pts, 3, true);
        double[][] b = m2.transform(pts, 3, true);
        for (int i = 0; i < 3; i++) {
            assertClose(a[0][i], b[0][i], 1e-12, "point " + i);
        }
    }

    @Test
    void polymapSerialisationIterInverseAttributes() {
        PolyMap m = poly(1, 1, term(1.0, 1, 1));
        m.setIterInverse(true);
        m.setNiterInverse(10);
        m.setTolInverse(1e-8);
        PolyMap m2 = roundTrip(m);
        assertTrue(m2.getIterInverse());
        assertEquals(10, m2.getNiterInverse());
        assertEquals(1e-8, m2.getTolInverse());
    }

    // ------------------------------------------------------------------
    // Shapes and degenerate polynomials
    // ------------------------------------------------------------------

    @Test
    void polymap2dTo1d() {
        PolyMap m = poly(2, 1, term(1.0, 1, 2, 0), term(1.0, 1, 0, 2));
        assertEquals(2, m.getNin());
        assertEquals(1, m.getNout());
        assertClose(25.0, transformRows(m, true, new double[] {3.0, 4.0})[0][0], 1e-12, "x^2 + y^2");
    }

    @Test
    void polymapConstant() {
        PolyMap m = poly(1, 1, term(42.0, 1, 0));
        double[][] out = transformRows(m, true, new double[] {0.0}, new double[] {100.0}, new double[] {-50.0});
        for (int i = 0; i < 3; i++) {
            assertClose(42.0, out[0][i], 1e-12, "point " + i);
        }
    }

    @Test
    void polymapEmptyForward() {
        PolyMap m = new PolyMap(1, 1, 0, null, 0, null);
        assertFalse(m.getTranForward());
        AstException e = assertThrows(AstException.class, () -> transformRows(m, true, new double[] {5.0}));
        assertEquals(AstException.AST__TRNND, e.getStatus());
    }

    /**
     * Coefficients that are all zero still define the transformation, which gives zero: C's
     * {@code StoreArrays} makes the coefficient arrays whenever coefficients are given, dropping
     * only the zero values ({@code AddCoeff}), and {@code GetTranForward} and {@code
     * GetIterInverse} test whether the arrays exist (polymap.c). The Rust port, which keeps only
     * the terms, treats such a transformation as undefined.
     */
    @Test
    void allZeroCoefficientsStillDefineTheTransformation() {
        PolyMap m = poly(1, 1, new double[][] {term(0.0, 1, 1)}, new double[][] {term(0.0, 1, 1)});
        assertTrue(m.getTranForward());
        assertTrue(m.getTranInverse());
        assertFalse(m.getIterInverse(), "inverse coefficients were given");
        assertEquals(0.0, transformRows(m, true, new double[] {3.0})[0][0]);
        assertEquals(0.0, transformRows(m, false, new double[] {3.0})[0][0]);
        assertEquals(0, m.polyCoeffs(true).length);
    }

    // ------------------------------------------------------------------
    // The iterative inverse
    // ------------------------------------------------------------------

    /** u = x + 1, v = y + 2. */
    private static PolyMap shift2d() {
        return poly(2, 2, term(1.0, 1, 1, 0), term(1.0, 1, 0, 0), term(1.0, 2, 0, 1), term(2.0, 2, 0, 0));
    }

    @Test
    void polymapNrInverse2d() {
        PolyMap m = shift2d();
        m.setIterInverse(true);
        m.setNiterInverse(100);
        m.setTolInverse(1e-10);
        double[][] out = transformRows(m, true, new double[] {3.0, 4.0});
        assertClose(4.0, out[0][0], 1e-12, "u");
        assertClose(6.0, out[1][0], 1e-12, "v");
        double[][] inv = m.transform(out, 1, false);
        assertClose(3.0, inv[0][0], 1e-3, "x");
        assertClose(4.0, inv[1][0], 1e-3, "y");
    }

    @Test
    void polymapBulkNrInverse1d() {
        PolyMap m = poly(1, 1, term(2.0, 1, 1), term(3.0, 1, 0));
        m.setIterInverse(true);
        m.setNiterInverse(20);
        m.setTolInverse(1e-10);
        int n = 1000;
        double[][] in = new double[1][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = -50.0 + 100.0 * i / (n - 1.0);
        }
        double[][] back = m.transform(m.transform(in, n, true), n, false);
        for (int i = 0; i < n; i++) {
            assertClose(in[0][i], back[0][i], 1e-6, "point " + i);
        }
    }

    @Test
    void polymapBulkNrInverse2d() {
        PolyMap m = poly(2, 2, term(1.0, 1, 1, 0), term(0.01, 1, 1, 1), term(1.0, 2, 0, 1), term(0.01, 2, 2, 0));
        m.setIterInverse(true);
        m.setNiterInverse(20);
        m.setTolInverse(1e-10);
        int side = 32;
        int n = side * side;
        double[][] in = new double[2][n];
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                in[0][i * side + j] = -5.0 + 10.0 * i / (side - 1.0);
                in[1][i * side + j] = -5.0 + 10.0 * j / (side - 1.0);
            }
        }
        double[][] back = m.transform(m.transform(in, n, true), n, false);
        for (int i = 0; i < n; i++) {
            assertTrue(
                    Math.abs(back[0][i] - in[0][i]) < 1e-6 && Math.abs(back[1][i] - in[1][i]) < 1e-6,
                    "point " + i + ": (" + back[0][i] + ", " + back[1][i] + ")");
        }
    }

    @Test
    void polymapBulkNrInverseBadValues() {
        PolyMap m = shift2d();
        m.setIterInverse(true);
        m.setNiterInverse(20);
        m.setTolInverse(1e-10);
        double[][] inv = transformRows(
                m, false, new double[] {4.0, 6.0}, new double[] {BAD, 6.0}, new double[] {5.0, 7.0}, new double[] {
                    4.0, BAD
                });
        assertClose(3.0, inv[0][0], 1e-3, "point 0 x");
        assertClose(4.0, inv[1][0], 1e-3, "point 0 y");
        // C's iteration carries a bad value through its arithmetic without spoiling the other axis.
        assertEquals(BAD, inv[0][1]);
        assertEquals(4.0, inv[1][1]);
        assertClose(4.0, inv[0][2], 1e-3, "point 2 x");
        assertClose(5.0, inv[1][2], 1e-3, "point 2 y");
        assertEquals(3.0, inv[0][3]);
        assertEquals(BAD, inv[1][3]);
    }

    /**
     * C uses the iterative inverse in preference to any inverse coefficients whenever IterInverse
     * is set (polymap.c {@code Transform}).
     */
    @Test
    void iterInverseTakesPriorityOverExplicitInverseCoefficients() {
        PolyMap m = poly(1, 1, new double[][] {term(2.0, 1, 1)}, new double[][] {term(1.0, 1, 1)});
        assertClose(8.0, transformRows(m, false, new double[] {8.0})[0][0], 1e-12, "coefficients");
        m.setIterInverse(true);
        m.setNiterInverse(20);
        m.setTolInverse(1e-10);
        assertClose(4.0, transformRows(m, false, new double[] {8.0})[0][0], 1e-6, "iterative inverse");
    }

    // ------------------------------------------------------------------
    // Simplification keeps the transformation
    // ------------------------------------------------------------------

    @Test
    void polymapRemovesZeroTerms() {
        Mapping s = poly(1, 1, term(2.0, 1, 1), term(-2.0, 1, 1)).simplify();
        assertEquals(0.0, transformRows(s, true, new double[] {7.0})[0][0]);
    }

    @Test
    void polymapConsolidatesDuplicateTerms() {
        Mapping s = poly(1, 1, term(2.0, 1, 2), term(3.0, 1, 2)).simplify();
        assertEquals(45.0, transformRows(s, true, new double[] {3.0})[0][0]);
    }

    @Test
    void polymapSimplifiedNoChangeWhenNoDuplicates() {
        Mapping s =
                poly(1, 1, term(1.0, 1, 0), term(3.0, 1, 1), term(2.0, 1, 2)).simplify();
        assertEquals(15.0, transformRows(s, true, new double[] {2.0})[0][0]);
    }

    @Test
    void polymapSimplifiedConsolidatesMultiOutput() {
        Mapping s = poly(2, 2, term(2.0, 1, 1, 0), term(3.0, 1, 1, 0), term(4.0, 2, 0, 1))
                .simplify();
        double[][] out = transformRows(s, true, new double[] {3.0, 2.0});
        assertClose(15.0, out[0][0], 1e-12, "out1");
        assertClose(8.0, out[1][0], 1e-12, "out2");
    }

    /** out1 = 10 + 2x + 3y, out2 = 20 + 4x + 5y. */
    private static PolyMap generalAffine() {
        return poly(
                2,
                2,
                term(10.0, 1, 0, 0),
                term(2.0, 1, 1, 0),
                term(3.0, 1, 0, 1),
                term(20.0, 2, 0, 0),
                term(4.0, 2, 1, 0),
                term(5.0, 2, 0, 1));
    }

    @Test
    void polymapAffineGeneralSimplifiesToAnEquivalentMapping() {
        Mapping[] maps = {generalAffine(), new CmpMap(generalAffine(), new ZoomMap(2, 2.0), true)};
        for (Mapping m : maps) {
            Mapping s = m.simplify();
            double[] want = point(transformRows(m, true, new double[] {1.0, 2.0}), 0);
            double[] got = point(transformRows(s, true, new double[] {1.0, 2.0}), 0);
            assertClose(want[0], got[0], 1e-10, "axis 1");
            assertClose(want[1], got[1], 1e-10, "axis 2");
        }
    }

    @Test
    void polymapNonlinearStaysPolymap() {
        Mapping s = poly(1, 1, term(1.0, 1, 2)).simplify();
        assertInstanceOf(PolyMap.class, s);
    }

    // ------------------------------------------------------------------
    // Coefficients
    // ------------------------------------------------------------------

    /**
     * A zero coefficient contributes nothing, and C's {@code AddCoeff} drops it, so a PolyMap given
     * one is the PolyMap without it: the two dump identically.
     */
    @Test
    void aZeroCoefficientIsDropped() {
        PolyMap withZero = poly(1, 1, term(0.0, 1, 0), term(2.0, 1, 1));
        PolyMap without = poly(1, 1, term(2.0, 1, 1));
        assertEquals(ChannelText.dump(without), ChannelText.dump(withZero));
        assertEquals(3, withZero.polyCoeffs(true).length);
    }

    /**
     * {@code astPolyCoeffs} is relative to the Invert attribute, so an inverted PolyMap's forward
     * coefficients are the original inverse ones, grouped by output (testmapping.c {@code
     * testpolycoeffsinverted}).
     */
    @Test
    void coeffsAreRelativeToInvertAndGroupedByOutput() {
        double[][] fwd = {term(2.0, 1, 1, 0), term(3.0, 1, 0, 1)};
        double[][] inv = {term(0.25, 2, 1), term(0.5, 1, 1)};
        PolyMap pm = poly(2, 1, fwd, inv);
        double[] grouped = flat(term(0.5, 1, 1), term(0.25, 2, 1));
        assertArrayEquals(flat(fwd), pm.polyCoeffs(true));
        assertArrayEquals(grouped, pm.polyCoeffs(false));
        pm.invert();
        assertArrayEquals(grouped, pm.polyCoeffs(true));
        assertArrayEquals(flat(fwd), pm.polyCoeffs(false));
    }

    // ------------------------------------------------------------------
    // testpolymap.c, in its order. Assertion messages carry C's error numbers.
    // ------------------------------------------------------------------

    private static final double[] C_COEFF = {
        1.0, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0, 0.0, 1.0, 2.0, 0.0, 0.0, 3.0, 2.0, 0.0, 1.0
    };
    private static final double[] C_COEFF2 = {
        1.0, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, 1.0, 2.0, 0.0, 0.0, 1.0, 2.0, 1.0, 0.0, 2.0, 2.0,
        0.0, 1.0
    };
    private static final double[] C_COEFF3 = {
        -0.1, 1.0, 0.0, 0.0, 0.99, 1.0, 1.0, 0.0, 1.0e-4, 1.0, 1.0, 1.0, -0.1, 2.0, 0.0, 0.0, 0.99, 2.0, 0.0, 1.0,
        1.0e-4, 2.0, 1.0, 1.0
    };
    private static final double C_ACC = 1.0e-7;
    private static final double C_MAXACC = 1.0e-3;

    /**
     * C's PolyTran check: fit the inverse over [-1000, 1000] on each axis, transform three points
     * forward and back through the fit, then back again through the iterative inverse, each within
     * 1000 times the accuracy. Gives the failures, each with C's error number.
     */
    private static List<String> cPolyTranRoundTrips(PolyMap pm, int err1, int err2) {
        double errlim = 1000.0 * C_ACC;
        int n = pm.getNin();
        double[] lbnd = new double[n];
        double[] ubnd = new double[n];
        Arrays.fill(lbnd, -1000.0);
        Arrays.fill(ubnd, 1000.0);
        PolyMap pm2 = pm.polyTran(false, C_ACC, C_MAXACC, 10, lbnd, ubnd);
        List<String> failures = new ArrayList<>();
        if (pm2 == null) {
            failures.add("Error " + err1 + ": no fit");
            return failures;
        }
        double[][] xin = new double[n][3];
        for (int c = 0; c < n; c++) {
            xin[c] = new double[] {1.0, 100.0, -50.0};
        }
        double[][] xout = pm2.transform(xin, 3, true);
        checkWithin(pm2.transform(xout, 3, false), xin, errlim, err1, failures);
        pm2.setIterInverse(true);
        checkWithin(pm2.transform(xout, 3, false), xin, errlim, err2, failures);
        return failures;
    }

    private static void checkWithin(double[][] got, double[][] want, double lim, int err, List<String> failures) {
        for (int c = 0; c < want.length; c++) {
            for (int p = 0; p < want[c].length; p++) {
                if (Math.abs(got[c][p] - want[c][p]) > lim || Double.isNaN(got[c][p])) {
                    failures.add(
                            "Error " + err + ": axis " + c + " point " + p + ": " + got[c][p] + " vs " + want[c][p]);
                }
            }
        }
    }

    private static void assertNoFailures(List<String> failures) {
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void cCoefficients() {
        PolyMap pm = cPoly(2, C_COEFF);
        assertEquals(4, pm.polyCoeffs(true).length / 4, "Error -1");
        assertEquals(0, pm.polyCoeffs(false).length, "Error -2");
        assertArrayEquals(C_COEFF, pm.polyCoeffs(true), "Error -3, -4");
    }

    @Test
    void cPolyTran2dLinear() {
        assertNoFailures(cPolyTranRoundTrips(cPoly(2, C_COEFF), 1, 1001));
    }

    @Test
    void cPolyTran1d() {
        assertNoFailures(cPolyTranRoundTrips(cPoly(1, new double[] {1.0, 1.0, 0.0, 2.0, 1.0, 1.0}), 3, 3001));
    }

    @Test
    void cPolyTran2dNonlinear() {
        assertNoFailures(cPolyTranRoundTrips(cPoly(2, C_COEFF2), 4, 4001));
    }

    @Test
    void cPolyTran2dNearUnit() {
        assertNoFailures(cPolyTranRoundTrips(cPoly(2, C_COEFF3), 6, 6001));
    }

    /** TranForward, IterInverse and TranInverse. */
    private static boolean[] state(PolyMap pm) {
        return new boolean[] {pm.getTranForward(), pm.getIterInverse(), pm.getTranInverse()};
    }

    /**
     * TranForward, IterInverse and TranInverse as IterInverse is set and the PolyMap inverted, and
     * for a forward-only PolyMap with two inputs and one output, which cannot have an iterative
     * inverse.
     */
    @Test
    void cIterInverseAttributes() {
        PolyMap pm = cPoly(2, C_COEFF3);
        assertArrayEquals(new boolean[] {true, true, true}, state(pm), "Error 8001-8003");
        pm.setIterInverse(false);
        assertArrayEquals(new boolean[] {true, false, false}, state(pm), "Error 8004-8006");
        pm.invert();
        assertArrayEquals(new boolean[] {false, false, true}, state(pm), "Error 8007-8009");
        pm.setIterInverse(true);
        assertArrayEquals(new boolean[] {true, true, true}, state(pm), "Error 8010-8012");
        pm.invert();
        assertArrayEquals(new boolean[] {true, true, true}, state(pm), "Error 8013-8015");

        PolyMap pm2 = new PolyMap(2, 1, 2, new double[] {2.0, 1.0, 2.0, 0.0, 3.0, 1.0, 0.0, 1.0}, 0, null);
        assertArrayEquals(new boolean[] {true, false, false}, state(pm2), "Error 8016-8018");
    }

    // ------------------------------------------------------------------
    // Long arrays and the NaN path
    // ------------------------------------------------------------------

    private static double longX(int i) {
        switch (i % 53) {
            case 0:
                return BAD;
            case 7:
                return Double.NaN;
            default:
                return (i - 500.0) * 0.731;
        }
    }

    private static double longY(int i) {
        return i % 61 == 0 ? BAD : Math.sin(i * 0.0137) * 40.0;
    }

    /**
     * Over a long array, each point gets the result it gets alone, and a bad input makes bad only
     * the outputs whose terms use a power of it above zero, as in C: the second output here does
     * not depend on the first input.
     */
    @Test
    void polymapLongArrayMatchesEachPointAndKeepsIndependentOutputs() {
        PolyMap m = poly(
                2,
                2,
                term(0.5, 1, 0, 0),
                term(1.25, 1, 1, 0),
                term(-0.75, 1, 2, 1),
                term(1e-3, 1, 0, 3),
                term(2.0, 2, 0, 1),
                term(-0.5, 2, 0, 2),
                term(0.0, 2, 0, 0));
        int n = 1000;
        double[][] in = new double[2][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = longX(i);
            in[1][i] = longY(i);
        }
        double[][] all = m.transform(in, n, true);
        for (int i = 0; i < n; i++) {
            double[] one = point(transformRows(m, true, new double[] {longX(i), longY(i)}), 0);
            for (int a = 0; a < 2; a++) {
                assertEquals(
                        Double.doubleToRawLongBits(one[a]),
                        Double.doubleToRawLongBits(all[a][i]),
                        "point " + i + " output " + a);
            }
            if (longX(i) == BAD && longY(i) != BAD) {
                assertEquals(BAD, all[0][i], "point " + i);
                assertNotEquals(BAD, all[1][i], "point " + i);
            }
        }
    }

    /**
     * A PolyMap with no inverse refuses an inverse transformation on either path: the NaN
     * protocol's entry point gives the same error as {@code transformInto} rather than outputs.
     */
    @Test
    void polymapWithoutAnInverseRefusesItOnTheNanPathToo() {
        PolyMap m = poly(2, 1, term(2.0, 1, 1, 1));
        assertFalse(m.getTranInverse());
        double[][] in = {{1.0, 2.0}};
        AstException plain = assertThrows(AstException.class, () -> m.transformInto(in, 2, false, new double[2][2]));
        double[][] nanOut = new double[2][2];
        AstException nan = assertThrows(AstException.class, () -> m.transformIntoNan(in, 2, false, nanOut));
        assertEquals(plain.getStatus(), nan.getStatus());
        assertArrayEquals(new double[2], nanOut[0], "no outputs written");
    }
}
