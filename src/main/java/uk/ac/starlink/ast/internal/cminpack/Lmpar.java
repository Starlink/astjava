/*
 * Minpack Copyright Notice (1999) University of Chicago.  All rights reserved
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. The end-user documentation included with the
 * redistribution, if any, must include the following
 * acknowledgment:
 *
 *    "This product includes software developed by the
 *    University of Chicago, as Operator of Argonne National
 *    Laboratory.
 *
 * Alternately, this acknowledgment may appear in the software
 * itself, if and wherever such third-party acknowledgments
 * normally appear.
 *
 * 4. WARRANTY DISCLAIMER. THE SOFTWARE IS SUPPLIED "AS IS"
 * WITHOUT WARRANTY OF ANY KIND. THE COPYRIGHT HOLDER, THE
 * UNITED STATES, THE UNITED STATES DEPARTMENT OF ENERGY, AND
 * THEIR EMPLOYEES: (1) DISCLAIM ANY WARRANTIES, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO ANY IMPLIED WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE
 * OR NON-INFRINGEMENT, (2) DO NOT ASSUME ANY LEGAL LIABILITY
 * OR RESPONSIBILITY FOR THE ACCURACY, COMPLETENESS, OR
 * USEFULNESS OF THE SOFTWARE, (3) DO NOT REPRESENT THAT USE OF
 * THE SOFTWARE WOULD NOT INFRINGE PRIVATELY OWNED RIGHTS, (4)
 * DO NOT WARRANT THAT THE SOFTWARE WILL FUNCTION
 * UNINTERRUPTED, THAT IT IS ERROR-FREE OR THAT ANY ERRORS WILL
 * BE CORRECTED.
 *
 * 5. LIMITATION OF LIABILITY. IN NO EVENT WILL THE COPYRIGHT
 * HOLDER, THE UNITED STATES, THE UNITED STATES DEPARTMENT OF
 * ENERGY, OR THEIR EMPLOYEES: BE LIABLE FOR ANY INDIRECT,
 * INCIDENTAL, CONSEQUENTIAL, SPECIAL OR PUNITIVE DAMAGES OF
 * ANY KIND OR NATURE, INCLUDING BUT NOT LIMITED TO LOSS OF
 * PROFITS OR LOSS OF DATA, FOR ANY REASON WHATSOEVER, WHETHER
 * SUCH LIABILITY IS ASSERTED ON THE BASIS OF CONTRACT, TORT
 * (INCLUDING NEGLIGENCE OR STRICT LIABILITY), OR OTHERWISE,
 * EVEN IF ANY OF SAID PARTIES HAS BEEN WARNED OF THE
 * POSSIBILITY OF SUCH LOSS OR DAMAGES.
 */
package uk.ac.starlink.ast.internal.cminpack;

/**
 * The Levenberg-Marquardt parameter.
 *
 * <p>Ported from cminpack's {@code lmpar.c} as vendored with AST, without the CBLAS variant, which
 * AST does not build; see {@link Lmder} for its origin; the MINPACK copyright notice is at the head
 * of this file's source.
 */
final class Lmpar {

    private static final double P1 = 0.1;
    private static final double P001 = 0.001;

    private Lmpar() {}

    /**
     * Given an {@code m} by {@code n} matrix A, an {@code n} by {@code n} non-singular diagonal
     * matrix D, an {@code m}-vector b and a positive number delta, finds a value par such that if x
     * solves {@code A x = b, sqrt(par) D x = 0} in the least-squares sense, either par is zero and
     * {@code |D x| - delta <= 0.1 delta}, or par is positive and {@code ||D x| - delta| <= 0.1
     * delta}. It takes the QR factorisation with column pivoting of A, {@code A P = Q R}, and the
     * first {@code n} components of {@code Q^T b}, and also gives the upper triangular matrix S of
     * {@code P^T (A^T A + par D D) P = S^T S}.
     *
     * @param n the order of R
     * @param r R in its full upper triangle, by columns with leading dimension {@code ldr}; its
     *     strict lower triangle receives the strict upper triangle of S
     * @param ldr the leading dimension of {@code r}
     * @param ipvt the permutation: column j of P is column {@code ipvt[j]} (1-based) of the identity
     * @param diag the diagonal elements of D
     * @param qtb the first {@code n} elements of {@code Q^T b}
     * @param delta an upper bound on the Euclidean norm of {@code D x}
     * @param par {@code par[0]} holds an initial estimate of the parameter and receives its final
     *     value
     * @param x receives the least-squares solution for that value
     * @param sdiag receives the diagonal elements of S
     * @param wa1 work space of {@code n} values
     * @param wa2 work space of {@code n} values
     */
    // The loops are cminpack's, which copy as part of other work in the same pass.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    static void lmpar(
            int n,
            double[] r,
            int ldr,
            int[] ipvt,
            double[] diag,
            double[] qtb,
            double delta,
            double[] par,
            double[] x,
            double[] sdiag,
            double[] wa1,
            double[] wa2) {
        double dwarf = Dpmpar.dpmpar(2);

        // Compute and store in x the Gauss-Newton direction. If the Jacobian is rank-deficient,
        // obtain a least-squares solution.
        int nsing = n;
        for (int j = 0; j < n; ++j) {
            wa1[j] = qtb[j];
            if (r[j + j * ldr] == 0.0 && nsing == n) {
                nsing = j;
            }
            if (nsing < n) {
                wa1[j] = 0.0;
            }
        }
        if (nsing >= 1) {
            for (int k = 1; k <= nsing; ++k) {
                int j = nsing - k;
                wa1[j] /= r[j + j * ldr];
                double temp = wa1[j];
                if (j >= 1) {
                    for (int i = 0; i < j; ++i) {
                        wa1[i] -= r[i + j * ldr] * temp;
                    }
                }
            }
        }
        for (int j = 0; j < n; ++j) {
            int l = ipvt[j] - 1;
            x[l] = wa1[j];
        }

        // Evaluate the function at the origin, and test for acceptance of the Gauss-Newton
        // direction.
        int iter = 0;
        for (int j = 0; j < n; ++j) {
            wa2[j] = diag[j] * x[j];
        }
        double dxnorm = Enorm.enorm(n, wa2, 0);
        double fp = dxnorm - delta;
        if (fp > P1 * delta) {
            // If the Jacobian is not rank deficient, the Newton step provides a lower bound, parl,
            // for the zero of the function. Otherwise set this bound to zero.
            double parl = 0.0;
            if (nsing >= n) {
                for (int j = 0; j < n; ++j) {
                    int l = ipvt[j] - 1;
                    wa1[j] = diag[l] * (wa2[l] / dxnorm);
                }
                for (int j = 0; j < n; ++j) {
                    double sum = 0.0;
                    if (j >= 1) {
                        for (int i = 0; i < j; ++i) {
                            sum += r[i + j * ldr] * wa1[i];
                        }
                    }
                    wa1[j] = (wa1[j] - sum) / r[j + j * ldr];
                }
                double temp = Enorm.enorm(n, wa1, 0);
                parl = fp / delta / temp / temp;
            }

            // Calculate an upper bound, paru, for the zero of the function.
            for (int j = 0; j < n; ++j) {
                double sum = 0.0;
                for (int i = 0; i <= j; ++i) {
                    sum += r[i + j * ldr] * qtb[i];
                }
                int l = ipvt[j] - 1;
                wa1[j] = sum / diag[l];
            }
            double gnorm = Enorm.enorm(n, wa1, 0);
            double paru = gnorm / delta;
            if (paru == 0.0) {
                paru = dwarf / Lmder.min(delta, P1);
            }

            // If the input par lies outside of the interval (parl, paru), set par to the closer
            // end point.
            par[0] = Lmder.max(par[0], parl);
            par[0] = Lmder.min(par[0], paru);
            if (par[0] == 0.0) {
                par[0] = gnorm / dxnorm;
            }

            // Beginning of an iteration.
            for (; ; ) {
                ++iter;

                // Evaluate the function at the current value of par.
                if (par[0] == 0.0) {
                    par[0] = Lmder.max(dwarf, P001 * paru);
                }
                double temp = Math.sqrt(par[0]);
                for (int j = 0; j < n; ++j) {
                    wa1[j] = temp * diag[j];
                }
                Qrsolv.qrsolv(n, r, ldr, ipvt, wa1, qtb, x, sdiag, wa2);
                for (int j = 0; j < n; ++j) {
                    wa2[j] = diag[j] * x[j];
                }
                dxnorm = Enorm.enorm(n, wa2, 0);
                temp = fp;
                fp = dxnorm - delta;

                // If the function is small enough, accept the current value of par. Also test for
                // the exceptional cases where parl is zero or the number of iterations has reached
                // 10.
                if (Math.abs(fp) <= P1 * delta || (parl == 0.0 && fp <= temp && temp < 0.0) || iter == 10) {
                    break;
                }

                // Compute the Newton correction.
                for (int j = 0; j < n; ++j) {
                    int l = ipvt[j] - 1;
                    wa1[j] = diag[l] * (wa2[l] / dxnorm);
                }
                for (int j = 0; j < n; ++j) {
                    wa1[j] /= sdiag[j];
                    temp = wa1[j];
                    if (n > j + 1) {
                        for (int i = j + 1; i < n; ++i) {
                            wa1[i] -= r[i + j * ldr] * temp;
                        }
                    }
                }
                temp = Enorm.enorm(n, wa1, 0);
                double parc = fp / delta / temp / temp;

                // Depending on the sign of the function, update parl or paru.
                if (fp > 0.0) {
                    parl = Lmder.max(parl, par[0]);
                }
                if (fp < 0.0) {
                    paru = Lmder.min(paru, par[0]);
                }

                // Compute an improved estimate for par.
                par[0] = Lmder.max(parl, par[0] + parc);
            }
        }

        // Termination.
        if (iter == 0) {
            par[0] = 0.0;
        }
    }
}
