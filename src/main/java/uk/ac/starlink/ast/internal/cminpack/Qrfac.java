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
 * QR factorisation with optional column pivoting by Householder transformations.
 *
 * <p>Ported from cminpack's {@code qrfac.c} as vendored with AST, without the LAPACK variant, which
 * AST does not build; see {@link Lmder} for its origin; the MINPACK copyright notice is at the head
 * of this file's source.
 */
public final class Qrfac {

    private static final double P05 = 0.05;

    private Qrfac() {}

    /**
     * Computes the QR factorisation of the {@code m} by {@code n} matrix A, held by columns in
     * {@code a} with leading dimension {@code lda}: with column pivoting, {@code A P = Q R}, where P
     * is a permutation matrix, Q has orthogonal columns and R is upper trapezoidal with diagonal
     * elements of non-increasing magnitude. Q is held in factored form: the Householder
     * transformation for column k is {@code I - (1/u(k)) u u^T}, where u has zeros in its first
     * k-1 positions.
     *
     * @param m the number of rows of A
     * @param n the number of columns of A
     * @param a A, replaced by the strict upper trapezoidal part of R in its upper trapezoid and by
     *     the factored form of Q in its lower trapezoid
     * @param lda the leading dimension of {@code a}, at least {@code m}
     * @param pivot whether to pivot columns
     * @param ipvt receives the permutation if {@code pivot}: column j of P is column {@code
     *     ipvt[j]} (1-based) of the identity matrix
     * @param rdiag receives the diagonal elements of R
     * @param acnorm receives the norms of the columns of A
     * @param wa work space of {@code n} values
     */
    // The loops are cminpack's, which copy as part of other work in the same pass.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    public static void qrfac(
            int m,
            int n,
            double[] a,
            int lda,
            boolean pivot,
            int[] ipvt,
            double[] rdiag,
            double[] acnorm,
            double[] wa) {
        double epsmch = Dpmpar.dpmpar(1);

        // Compute the initial column norms and initialise several arrays.
        for (int j = 0; j < n; ++j) {
            acnorm[j] = Enorm.enorm(m, a, j * lda);
            rdiag[j] = acnorm[j];
            wa[j] = rdiag[j];
            if (pivot) {
                ipvt[j] = j + 1;
            }
        }

        // Reduce A to R with Householder transformations.
        int minmn = Lmder.min(m, n);
        for (int j = 0; j < minmn; ++j) {
            if (pivot) {
                // Bring the column of largest norm into the pivot position.
                int kmax = j;
                for (int k = j; k < n; ++k) {
                    if (rdiag[k] > rdiag[kmax]) {
                        kmax = k;
                    }
                }
                if (kmax != j) {
                    for (int i = 0; i < m; ++i) {
                        double temp = a[i + j * lda];
                        a[i + j * lda] = a[i + kmax * lda];
                        a[i + kmax * lda] = temp;
                    }
                    rdiag[kmax] = rdiag[j];
                    wa[kmax] = wa[j];
                    int k = ipvt[j];
                    ipvt[j] = ipvt[kmax];
                    ipvt[kmax] = k;
                }
            }

            // Compute the Householder transformation to reduce the j-th column of A to a multiple
            // of the j-th unit vector.
            double ajnorm = Enorm.enorm(m - (j + 1) + 1, a, j + j * lda);
            if (ajnorm != 0.0) {
                if (a[j + j * lda] < 0.0) {
                    ajnorm = -ajnorm;
                }
                for (int i = j; i < m; ++i) {
                    a[i + j * lda] /= ajnorm;
                }
                a[j + j * lda] += 1.0;

                // Apply the transformation to the remaining columns and update the norms.
                int jp1 = j + 1;
                if (n > jp1) {
                    for (int k = jp1; k < n; ++k) {
                        double sum = 0.0;
                        for (int i = j; i < m; ++i) {
                            sum += a[i + j * lda] * a[i + k * lda];
                        }
                        double temp = sum / a[j + j * lda];
                        for (int i = j; i < m; ++i) {
                            a[i + k * lda] -= temp * a[i + j * lda];
                        }
                        if (pivot && rdiag[k] != 0.0) {
                            temp = a[j + k * lda] / rdiag[k];
                            double d1 = 1.0 - temp * temp;
                            rdiag[k] *= Math.sqrt(Lmder.max(0.0, d1));
                            d1 = rdiag[k] / wa[k];
                            if (P05 * (d1 * d1) <= epsmch) {
                                rdiag[k] = Enorm.enorm(m - (j + 1), a, jp1 + k * lda);
                                wa[k] = rdiag[k];
                            }
                        }
                    }
                }
            }
            rdiag[j] = -ajnorm;
        }
    }
}
