package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.PolyTerms.poly;
import static uk.ac.starlink.ast.PolyTerms.term;

import org.junit.jupiter.api.Test;

/**
 * Review Focus 2: a pixel so far off a SIP-like PolyMap's domain that its iterative inverse cannot
 * converge gives exactly what C gives, without an exception.
 *
 * <p>C's iterative inverse (polymap.c {@code IterInverse}, from line 2481) runs at most
 * NiterInverse Newton-Raphson iterations (line 2643). In each, a point not yet converged takes the
 * step {@code palDmat} solves for (line 2699) and is marked converged when the step is within
 * TolInverse of the new position (line 2723); a singular Jacobian makes it bad (line 2703). Nothing
 * else marks a point: one that has not converged when the iterations run out keeps its last
 * position (line 2716), which is what C returns. So the inverse after NiterInverse iterations is
 * one Newton step on from the inverse after one fewer, and is neither bad nor the true inverse.
 */
class PolyIterInverseDomainTest {

    private static final double BAD = AstObject.AST__BAD;

    /** SIP-like distortion coefficients for a 4000-pixel image: quadratic and cubic terms. */
    private static final double A20 = 2.0e-5;

    private static final double A02 = -1.5e-5;
    private static final double A11 = 1.0e-5;
    private static final double A30 = 3.0e-9;
    private static final double B20 = -1.0e-5;
    private static final double B02 = 2.5e-5;
    private static final double B03 = -2.0e-9;

    /** u = x + A20 x^2 + A11 x y + A02 y^2 + A30 x^3, v = y + B20 x^2 + B02 y^2 + B03 y^3. */
    private static PolyMap sip(int niter) {
        PolyMap pm = poly(
                2,
                2,
                term(1.0, 1, 1, 0),
                term(A20, 1, 2, 0),
                term(A11, 1, 1, 1),
                term(A02, 1, 0, 2),
                term(A30, 1, 3, 0),
                term(1.0, 2, 0, 1),
                term(B20, 2, 2, 0),
                term(B02, 2, 0, 2),
                term(B03, 2, 0, 3));
        pm.set("IterInverse=1");
        pm.setNiterInverse(niter);
        return pm;
    }

    /** The point to invert: 1e6 pixels beyond the image on each axis. */
    private static final double[][] FAR = {{1.0e6}, {1.0e6}};

    private static double[] inverse(int niter) {
        double[][] out = sip(niter).transform(FAR, 1, false);
        return new double[] {out[0][0], out[1][0]};
    }

    /** One Newton-Raphson step of the forward transformation from {@code x} towards {@link #FAR}. */
    private static double[] newtonStep(double[] x) {
        double[][] f = sip(4).transform(new double[][] {{x[0]}, {x[1]}}, 1, true);
        double du = FAR[0][0] - f[0][0];
        double dv = FAR[1][0] - f[1][0];
        double j11 = 1.0 + 2 * A20 * x[0] + A11 * x[1] + 3 * A30 * x[0] * x[0];
        double j12 = A11 * x[0] + 2 * A02 * x[1];
        double j21 = 2 * B20 * x[0];
        double j22 = 1.0 + 2 * B02 * x[1] + 3 * B03 * x[1] * x[1];
        double det = j11 * j22 - j12 * j21;
        return new double[] {x[0] + (j22 * du - j12 * dv) / det, x[1] + (j11 * dv - j21 * du) / det};
    }

    private static void assertRelClose(double want, double got, String what) {
        assertTrue(Math.abs(got - want) <= 1e-10 * Math.abs(want), what + ": got " + got + ", want " + want);
    }

    @Test
    void unconvergedPointKeepsItsLastIterate() {
        double[] x3 = inverse(3);
        double[] x4 = inverse(4);
        for (double v : x4) {
            assertTrue(Double.isFinite(v), "finite: " + v);
            assertNotEquals(BAD, v);
        }
        double[] step = newtonStep(x3);
        assertRelClose(step[0], x4[0], "axis 1");
        assertRelClose(step[1], x4[1], "axis 2");

        // The last step was larger than TolInverse allows, so the point did not converge.
        double tol = sip(4).getTolInverse();
        double dx = x4[0] - x3[0];
        double dy = x4[1] - x3[1];
        assertTrue(dx * dx + dy * dy > tol * tol * (x4[0] * x4[0] + x4[1] * x4[1]), "the point converged");

        // And so the forward transformation does not take it back to the point given.
        double[][] back = sip(4).transform(new double[][] {{x4[0]}, {x4[1]}}, 1, true);
        assertTrue(
                Math.abs(back[0][0] - FAR[0][0]) > 1.0 || Math.abs(back[1][0] - FAR[1][0]) > 1.0,
                "forward of the inverse: (" + back[0][0] + ", " + back[1][0] + ")");
    }

    /** With no iterations the result is the first guess, the inverse of the linear terms. */
    @Test
    void noIterationsGiveTheLinearGuess() {
        double[] x0 = inverse(0);
        assertEquals(FAR[0][0], x0[0]);
        assertEquals(FAR[1][0], x0[1]);
    }
}
