package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.PolyTerms.poly;
import static uk.ac.starlink.ast.PolyTerms.term;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_polytran.rs}: {@code polyTran}, C's {@code astPolyTran},
 * fits a replacement forward or inverse polynomial by least squares (polymap.c {@code
 * ReplaceTransformation}, {@code FitPoly1D}, {@code FitPoly2D}), sampling the other transformation
 * on a grid of {@code 2*order} points per axis and raising the order until the target accuracy is
 * met. Every expected value is a property of the fit: how closely the fitted transformation inverts
 * the one sampled.
 */
class PolyTranTest {

    /** y = 2x + 1, forward only. */
    private static PolyMap linear1d() {
        return poly(1, 1, term(2.0, 1, 1), term(1.0, 1, 0));
    }

    private static double[][] column(double... v) {
        return new double[][] {v};
    }

    @Test
    void polytranFitsLinearInverse() {
        PolyMap fitted = linear1d().polyTran(false, 1e-8, 1e-6, 5, new double[] {0.0}, new double[] {10.0});
        assertNotNull(fitted, "fit should succeed");
        double[][] out = fitted.transform(column(1.0, 11.0, 21.0), 3, false);
        double[] want = {0.0, 5.0, 10.0};
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(out[0][i] - want[i]) < 1e-6, "point " + i + ": got " + out[0][i]);
        }
    }

    @Test
    void polytranRoundTripNonlinear1d() {
        PolyMap pm = poly(1, 1, term(1.0, 1, 1), term(0.01, 1, 2));
        PolyMap fitted = pm.polyTran(false, 1e-7, 1e-5, 10, new double[] {0.0}, new double[] {10.0});
        assertNotNull(fitted, "fit should succeed");
        double[] xs = {0.5, 3.0, 7.25, 9.5};
        double[][] back = fitted.transform(fitted.transform(column(xs), 4, true), 4, false);
        for (int i = 0; i < xs.length; i++) {
            assertTrue(Math.abs(back[0][i] - xs[i]) < 1e-6, "round trip " + i + ": got " + back[0][i]);
        }
    }

    @Test
    void polytranReturnsNoneWhenAccuracyUnachievable() {
        // y = x^3 over [-1, 1] has inverse y^(1/3), which no quadratic approximates to 1e-10.
        PolyMap pm = poly(1, 1, term(1.0, 1, 3));
        assertNull(pm.polyTran(false, 1e-10, 1e-10, 3, new double[] {-1.0}, new double[] {1.0}));
    }

    @Test
    void polytranClearsIterInverse() {
        PolyMap pm = linear1d();
        pm.setIterInverse(true);
        PolyMap fitted = pm.polyTran(false, 1e-8, 1e-6, 5, new double[] {0.0}, new double[] {10.0});
        assertNotNull(fitted, "fit should succeed");
        assertFalse(fitted.getIterInverse());
    }

    @Test
    void polytranRejectsUnequalNinNout() {
        PolyMap pm = poly(2, 1, term(1.0, 1, 1, 0));
        AstException e = assertThrows(
                AstException.class,
                () -> pm.polyTran(false, 1e-8, 1e-6, 5, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}));
        assertEquals(AstException.AST__BADNI, e.getStatus(), e.getMessage());
    }

    @Test
    void polytranRejectsMissingSampledTransformation() {
        PolyMap pm = poly(1, 1, new double[0][], new double[][] {term(1.0, 1, 1)});
        AstException e = assertThrows(
                AstException.class, () -> pm.polyTran(false, 1e-8, 1e-6, 5, new double[] {0.0}, new double[] {1.0}));
        assertEquals(AstException.AST__NODEF, e.getStatus(), e.getMessage());
    }

    @Test
    void polytranFits2dSipLikeInverse() {
        // y1 = x1 + 1e-3 x1 x2, y2 = x2 - 5e-4 x2^2 over [0, 100]^2.
        PolyMap pm = poly(2, 2, term(1.0, 1, 1, 0), term(1e-3, 1, 1, 1), term(1.0, 2, 0, 1), term(-5e-4, 2, 0, 2));
        PolyMap fitted = pm.polyTran(false, 1e-6, 1e-4, 10, new double[] {0.0, 0.0}, new double[] {100.0, 100.0});
        assertNotNull(fitted, "fit should succeed");
        double[][] x = {{10.0, 50.0, 90.0}, {20.0, 60.0, 80.0}};
        double[][] back = fitted.transform(fitted.transform(x, 3, true), 3, false);
        for (int a = 0; a < 2; a++) {
            for (int i = 0; i < 3; i++) {
                assertTrue(
                        Math.abs(back[a][i] - x[a][i]) < 1e-4,
                        "axis " + (a + 1) + " round trip " + i + ": got " + back[a][i]);
            }
        }
    }

    @Test
    void polytranReplacesForwardFromInverse() {
        PolyMap pm = poly(1, 1, new double[0][], new double[][] {term(0.5, 1, 1)});
        PolyMap fitted = pm.polyTran(true, 1e-8, 1e-6, 5, new double[] {0.0}, new double[] {10.0});
        assertNotNull(fitted, "fit should succeed");
        double got = fitted.transform(column(4.0), 1, true)[0][0];
        assertTrue(Math.abs(got - 8.0) < 1e-6, "got " + got);
    }
}
