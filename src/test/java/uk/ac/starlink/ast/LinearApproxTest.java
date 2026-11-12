package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests in Rust {@code src/mappings/linear_approx.rs}, whose expected fits
 * were captured from C's {@code astLinearApprox} ({@code tools/oracle_fixtures/linear_approx.c});
 * the tolerance of 1e-12 is the Rust tests' own. A fit is {@code Nout} constant terms followed by
 * the gradients, row by row.
 *
 * <p>{@code identity_polymap_2x2} is translated in Task 12, and {@code tan_tiny_box_fits} and
 * {@code tan_too_large_refuses} in Task 11. {@code returns_correct_type} tests a Rust type. In
 * {@code transform_error_is_reported}, a Mapping with no inverse, inverted, stands in for the
 * inverted RateMap, which is outside the first phase.
 */
class LinearApproxTest {

    /** C's fit of a 3-D ShiftMap over [0, 100] on each axis. */
    private static final double[] LINEAR_APPROX_SHIFT_3D = {
        10.0, 20.0, 30.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0
    };

    /** C's fit of a 1-D ZoomMap of 7.5 over [-2, 2]. */
    private static final double[] LINEAR_APPROX_ZOOM_1D = {0.0, 7.5};

    /** C's fit of a 2-in, 3-out MatrixMap over [-5, 5]. */
    private static final double[] LINEAR_APPROX_MATRIX_2X3 = {0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0};

    private static void assertClose(double[] actual, double[] expected, String label) {
        assertNotNull(actual, label + ": no fit");
        assertEquals(expected.length, actual.length, label + " length mismatch");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-12, label + ": index " + i);
        }
    }

    @Test
    void shift3d() {
        ShiftMap sm = new ShiftMap(new double[] {10.0, 20.0, 30.0});
        double[] fit = sm.linearApprox(new double[] {0.0, 0.0, 0.0}, new double[] {100.0, 100.0, 100.0}, 0.001);
        assertClose(fit, LINEAR_APPROX_SHIFT_3D, "shift 3d");
    }

    @Test
    void zoom1d() {
        ZoomMap zm = new ZoomMap(1, 7.5);
        assertClose(zm.linearApprox(new double[] {-2.0}, new double[] {2.0}, 0.001), LINEAR_APPROX_ZOOM_1D, "zoom 1d");
    }

    @Test
    void matrix2x3() {
        MatrixMap mm = new MatrixMap(2, 3, new double[][] {{1.0, 2.0}, {3.0, 4.0}, {5.0, 6.0}});
        double[] fit = mm.linearApprox(new double[] {-5.0, -5.0}, new double[] {5.0, 5.0}, 0.001);
        assertClose(fit, LINEAR_APPROX_MATRIX_2X3, "matrix 2x3");
    }

    @Test
    void dimensionMismatchIsAnError() {
        ZoomMap zm = new ZoomMap(2, 1.0);
        AstException e = assertThrows(
                AstException.class, () -> zm.linearApprox(new double[] {0.0}, new double[] {1.0, 2.0}, 1e-3));
        assertEquals(AstException.AST__NCPIN, e.getStatus());
    }

    @Test
    void transformErrorIsReported() {
        NoInverseMap rm = new NoInverseMap();
        rm.invert();
        AstException e =
                assertThrows(AstException.class, () -> rm.linearApprox(new double[] {0.0}, new double[] {1.0}, 1e-3));
        assertEquals(AstException.AST__TRNND, e.getStatus());
    }

    /**
     * An output that is bad everywhere has bad coefficients but does not stop the others being
     * fitted; when every output is bad there is no fit (mapping.c {@code LinearApprox}).
     */
    @Test
    void badOutputsHaveBadCoefficients() {
        PermMap pm = new PermMap(1, new int[] {1}, 2, new int[] {1, 0}, null);
        double[] fit = pm.linearApprox(new double[] {0.0}, new double[] {1.0}, 1e-3);
        assertNotNull(fit);
        assertEquals(0.0, fit[0]);
        assertEquals(AstObject.AST__BAD, fit[1]);
        assertEquals(1.0, fit[2]);
        assertEquals(AstObject.AST__BAD, fit[3]);
        PermMap allBad = new PermMap(1, new int[] {1}, 1, new int[] {0}, null);
        assertNull(allBad.linearApprox(new double[] {0.0}, new double[] {1.0}, 1e-3));
    }
}
