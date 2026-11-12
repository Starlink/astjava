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
 * The least-squares solution of a system augmented by a diagonal matrix, given its QR
 * factorisation.
 *
 * <p>Ported from cminpack's {@code qrsolv.c} as vendored with AST, without the LAPACK and CBLAS
 * variants, which AST does not build; see {@link Lmder} for its origin; the MINPACK copyright
 * notice is at the head of this file's source.
 */
final class Qrsolv {

    private static final double P5 = 0.5;
    private static final double P25 = 0.25;

    private Qrsolv() {}

    /**
     * Given the QR factorisation with pivoting {@code A P = Q R} of an {@code m} by {@code n}
     * matrix A, an {@code n} by {@code n} diagonal matrix D and an {@code m}-vector b, solves {@code
     * A x = b, D x = 0} in the least-squares sense, from R, P and the first {@code n} components of
     * {@code Q^T b}. The system is equivalent to {@code R z = Q^T b, P^T D P z = 0} with {@code x =
     * P z}; Givens rotations reduce it to {@code S z = c}, S upper triangular, and if S is singular a
     * least-squares solution is found by setting the components of z beyond the first zero pivot
     * to zero.
     *
     * @param n the order of R
     * @param r R in its full upper triangle, by columns with leading dimension {@code ldr}; its
     *     strict lower triangle receives the strict upper triangle of S transposed
     * @param ldr the leading dimension of {@code r}
     * @param ipvt the permutation: column j of P is column {@code ipvt[j]} (1-based) of the identity
     * @param diag the diagonal elements of D
     * @param qtb the first {@code n} elements of {@code Q^T b}
     * @param x receives the least-squares solution
     * @param sdiag receives the diagonal elements of S
     * @param wa work space of {@code n} values
     */
    // The loops are cminpack's, which copy as part of other work in the same pass.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    static void qrsolv(
            int n,
            double[] r,
            int ldr,
            int[] ipvt,
            double[] diag,
            double[] qtb,
            double[] x,
            double[] sdiag,
            double[] wa) {
        // Copy R and Q^T b to preserve input and initialise S. Save the diagonal elements of R in x.
        for (int j = 0; j < n; ++j) {
            for (int i = j; i < n; ++i) {
                r[i + j * ldr] = r[j + i * ldr];
            }
            x[j] = r[j + j * ldr];
            wa[j] = qtb[j];
        }

        // Eliminate the diagonal matrix D using Givens rotations.
        for (int j = 0; j < n; ++j) {
            // Prepare the row of D to be eliminated, locating the diagonal element using P from
            // the QR factorisation.
            int l = ipvt[j] - 1;
            if (diag[l] != 0.0) {
                for (int k = j; k < n; ++k) {
                    sdiag[k] = 0.0;
                }
                sdiag[j] = diag[l];

                // The transformations to eliminate the row of D modify only a single element of
                // Q^T b beyond the first n, which is initially zero.
                double qtbpj = 0.0;
                for (int k = j; k < n; ++k) {
                    // Determine a Givens rotation which eliminates the appropriate element in the
                    // current row of D.
                    if (sdiag[k] != 0.0) {
                        double cos;
                        double sin;
                        if (Math.abs(r[k + k * ldr]) < Math.abs(sdiag[k])) {
                            double cotan = r[k + k * ldr] / sdiag[k];
                            sin = P5 / Math.sqrt(P25 + P25 * (cotan * cotan));
                            cos = sin * cotan;
                        } else {
                            double tan = sdiag[k] / r[k + k * ldr];
                            cos = P5 / Math.sqrt(P25 + P25 * (tan * tan));
                            sin = cos * tan;
                        }

                        // Compute the modified diagonal element of R and the modified element of
                        // (Q^T b, 0).
                        double temp = cos * wa[k] + sin * qtbpj;
                        qtbpj = -sin * wa[k] + cos * qtbpj;
                        wa[k] = temp;

                        // Accumulate the transformation in the row of S.
                        r[k + k * ldr] = cos * r[k + k * ldr] + sin * sdiag[k];
                        if (n > k + 1) {
                            for (int i = k + 1; i < n; ++i) {
                                temp = cos * r[i + k * ldr] + sin * sdiag[i];
                                sdiag[i] = -sin * r[i + k * ldr] + cos * sdiag[i];
                                r[i + k * ldr] = temp;
                            }
                        }
                    }
                }
            }

            // Store the diagonal element of S and restore the corresponding diagonal element of R.
            sdiag[j] = r[j + j * ldr];
            r[j + j * ldr] = x[j];
        }

        // Solve the triangular system for z. If the system is singular, obtain a least-squares
        // solution.
        int nsing = n;
        for (int j = 0; j < n; ++j) {
            if (sdiag[j] == 0.0 && nsing == n) {
                nsing = j;
            }
            if (nsing < n) {
                wa[j] = 0.0;
            }
        }
        if (nsing >= 1) {
            for (int k = 1; k <= nsing; ++k) {
                int j = nsing - k;
                double sum = 0.0;
                if (nsing > j + 1) {
                    for (int i = j + 1; i < nsing; ++i) {
                        sum += r[i + j * ldr] * wa[i];
                    }
                }
                wa[j] = (wa[j] - sum) / sdiag[j];
            }
        }

        // Permute the components of z back to components of x.
        for (int j = 0; j < n; ++j) {
            int l = ipvt[j] - 1;
            x[l] = wa[j];
        }
    }
}
