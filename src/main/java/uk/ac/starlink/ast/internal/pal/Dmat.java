package uk.ac.starlink.ast.internal.pal;

/**
 * Matrix inversion and the solution of simultaneous equations.
 *
 * <p>Ported from Rust {@code pal/dmat.rs}, itself from PAL's {@code palDmat} (PAL v0.9.10-9-g75017db
 * as vendored with AST), derived from SLALIB with permission: Copyright (C) 2001 Rutherford
 * Appleton Laboratory, Copyright (C) 2012 Science and Technology Facilities Council; GNU Lesser
 * General Public License version 3 or later.
 */
public final class Dmat {

    /** The smallest pivot or determinant magnitude taken to be non-zero. */
    private static final double SFA = 1e-20;

    private Dmat() {}

    /**
     * For the set of {@code n} simultaneous equations in {@code n} unknowns {@code A.Y = X},
     * calculates the inverse of A, its determinant and the vector of unknowns, by Gaussian
     * elimination with partial pivoting, optimised for speed rather than accuracy (errors 1 to 4
     * times those of routines optimised for accuracy), as {@code palDmat} does.
     *
     * @param n the number of equations and unknowns
     * @param a the {@code n} by {@code n} matrix in row order, replaced by its inverse; if the
     *     matrix is singular it is left as far as the elimination got
     * @param y the {@code n} knowns, replaced by the solutions
     * @return the determinant of A, or zero if A is singular (palDmat's {@code jf = -1})
     */
    public static double dmat(int n, double[] a, double[] y) {
        int[] iw = new int[n];
        boolean singular = false;
        double d = 1.0;
        for (int k = 0; k < n; k++) {
            int ko = k * n;
            double amx = Math.abs(a[ko + k]);
            int imx = k;
            for (int i = k + 1; i < n; i++) {
                double t = Math.abs(a[i * n + k]);
                if (t > amx) {
                    amx = t;
                    imx = i;
                }
            }
            if (amx < SFA) {
                singular = true;
            } else {
                if (imx != k) {
                    for (int j = 0; j < n; j++) {
                        swap(a, ko + j, imx * n + j);
                    }
                    swap(y, k, imx);
                    d = -d;
                }
                iw[k] = imx;
                d *= a[ko + k];
                if (Math.abs(d) < SFA) {
                    singular = true;
                } else {
                    a[ko + k] = 1.0 / a[ko + k];
                    for (int j = 0; j < n; j++) {
                        if (j != k) {
                            a[ko + j] *= a[ko + k];
                        }
                    }
                    double yk = y[k] * a[ko + k];
                    y[k] = yk;
                    for (int i = 0; i < n; i++) {
                        if (i != k) {
                            int io = i * n;
                            for (int j = 0; j < n; j++) {
                                if (j != k) {
                                    a[io + j] -= a[io + k] * a[ko + j];
                                }
                            }
                            y[i] -= a[io + k] * yk;
                        }
                    }
                    for (int i = 0; i < n; i++) {
                        if (i != k) {
                            a[i * n + k] *= -a[ko + k];
                        }
                    }
                }
            }
        }
        if (singular) {
            return 0.0;
        }
        for (int k = n - 1; k >= 0; k--) {
            int ki = iw[k];
            if (k != ki) {
                for (int i = 0; i < n; i++) {
                    swap(a, i * n + k, i * n + ki);
                }
            }
        }
        return d;
    }

    private static void swap(double[] v, int i, int j) {
        double t = v[i];
        v[i] = v[j];
        v[j] = t;
    }
}
