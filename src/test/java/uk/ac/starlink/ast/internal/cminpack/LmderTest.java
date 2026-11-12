package uk.ac.starlink.ast.internal.cminpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of the cminpack port against problems whose solutions are known exactly: machine
 * constants, Euclidean norms that would overflow or underflow if formed naively, a QR
 * factorisation checked through its defining identity, and least-squares problems with known
 * minima. No Rust test covers cminpack, which the Rust port replaced with another solver.
 */
class LmderTest {

    @Test
    void dpmparGivesTheMachineConstants() {
        assertEquals(Math.ulp(1.0), Dpmpar.dpmpar(1));
        assertEquals(Double.MIN_NORMAL, Dpmpar.dpmpar(2));
        assertEquals(Double.MAX_VALUE, Dpmpar.dpmpar(3));
    }

    @Test
    void enormAvoidsOverflowAndUnderflow() {
        assertEquals(5.0, Enorm.enorm(2, new double[] {3.0, 4.0}, 0));
        assertEquals(5.0, Enorm.enorm(2, new double[] {9.0, 3.0, -4.0}, 1));
        assertEquals(0.0, Enorm.enorm(3, new double[3], 0));
        double big = 3.0e200;
        assertEquals(5.0e200, Enorm.enorm(2, new double[] {big, 4.0e200}, 0), 1e186);
        assertEquals(5.0e-200, Enorm.enorm(2, new double[] {3.0e-200, 4.0e-200}, 0), 1e-214);
        assertEquals(Math.sqrt(2.0), Enorm.enorm(3, new double[] {1.0, 1.0e-300, -1.0}, 0), 1e-15);
    }

    /** R^T R equals P^T A^T A P, where column j of P is column ipvt[j] of the identity. */
    @Test
    void qrfacFactorisesWithPivoting() {
        int m = 4;
        int n = 3;
        double[] a = {1.0, 2.0, 3.0, 4.0, 2.0, -1.0, 0.5, 3.0, 10.0, 0.0, 1.0, -2.0};
        double[] orig = a.clone();
        int[] ipvt = new int[n];
        double[] rdiag = new double[n];
        double[] acnorm = new double[n];
        Qrfac.qrfac(m, n, a, m, true, ipvt, rdiag, acnorm, new double[n]);
        for (int j = 0; j < n; j++) {
            assertEquals(Enorm.enorm(m, orig, j * m), acnorm[j], 1e-15);
        }
        // Column norms are taken in decreasing order: column 3 is largest.
        assertEquals(3, ipvt[0]);
        for (int j = 0; j < n; j++) {
            for (int k = 0; k < n; k++) {
                double rtr = 0.0;
                for (int i = 0; i <= Math.min(j, k); i++) {
                    double rij = i == j ? rdiag[j] : a[i + j * m];
                    double rik = i == k ? rdiag[k] : a[i + k * m];
                    rtr += rij * rik;
                }
                double ata = 0.0;
                for (int i = 0; i < m; i++) {
                    ata += orig[i + (ipvt[j] - 1) * m] * orig[i + (ipvt[k] - 1) * m];
                }
                assertEquals(ata, rtr, 1e-12, "(" + j + ", " + k + ")");
            }
        }
    }

    /** The line through points that lie on one: y = 1.5 + 0.25 t. */
    @Test
    void lmder1FitsALineExactly() {
        double[] t = {0.0, 1.0, 2.0, 3.0, 4.0};
        int m = t.length;
        Lmder.Function fcn = (mm, nn, x, fvec, fjac, ldfjac, iflag) -> {
            for (int i = 0; i < mm; i++) {
                if (iflag == 1) {
                    fvec[i] = x[0] + x[1] * t[i] - (1.5 + 0.25 * t[i]);
                } else {
                    fjac[i] = 1.0;
                    fjac[i + ldfjac] = t[i];
                }
            }
            return 0;
        };
        double[] x = {0.0, 1.0};
        double[] fvec = new double[m];
        int info = Lmder1.lmder1(fcn, m, 2, x, fvec, new double[m * 2], m, Math.sqrt(Math.ulp(1.0)), new int[2]);
        assertTrue(info >= 1 && info <= 4, "info " + info);
        assertEquals(1.5, x[0], 1e-12);
        assertEquals(0.25, x[1], 1e-12);
        assertEquals(0.0, Enorm.enorm(m, fvec, 0), 1e-12);
    }

    /** Rosenbrock's function as residuals 10 (x2 - x1^2) and 1 - x1, with its minimum at (1, 1). */
    @Test
    void lmder1FindsTheRosenbrockMinimum() {
        Lmder.Function fcn = (mm, nn, x, fvec, fjac, ldfjac, iflag) -> {
            if (iflag == 1) {
                fvec[0] = 10.0 * (x[1] - x[0] * x[0]);
                fvec[1] = 1.0 - x[0];
            } else {
                fjac[0] = -20.0 * x[0];
                fjac[1] = -1.0;
                fjac[ldfjac] = 10.0;
                fjac[1 + ldfjac] = 0.0;
            }
            return 0;
        };
        double[] x = {-1.2, 1.0};
        int info = Lmder1.lmder1(fcn, 2, 2, x, new double[2], new double[4], 2, 1e-10, new int[2]);
        assertTrue(info >= 1 && info <= 4, "info " + info);
        assertEquals(1.0, x[0], 1e-9);
        assertEquals(1.0, x[1], 1e-9);
    }

    /** Improper input, such as more variables than functions, gives info 0. */
    @Test
    void lmder1RefusesImproperInput() {
        Lmder.Function fcn = (mm, nn, x, fvec, fjac, ldfjac, iflag) -> 0;
        assertEquals(0, Lmder1.lmder1(fcn, 1, 2, new double[2], new double[1], new double[2], 1, 1e-8, new int[2]));
        assertEquals(0, Lmder1.lmder1(fcn, 2, 2, new double[2], new double[2], new double[4], 2, -1.0, new int[2]));
    }

    /** A negative value from the function stops the minimisation, and is returned as info. */
    @Test
    void aNegativeFlagStopsTheMinimisation() {
        Lmder.Function fcn = (mm, nn, x, fvec, fjac, ldfjac, iflag) -> iflag == 2 ? -3 : 0;
        assertEquals(-3, Lmder1.lmder1(fcn, 2, 2, new double[2], new double[2], new double[4], 2, 1e-8, new int[2]));
    }
}
