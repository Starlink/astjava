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
 * Minimisation of a sum of squares of nonlinear functions by the Levenberg-Marquardt algorithm,
 * with a Jacobian the caller computes.
 *
 * <p>Ported from cminpack's {@code lmder.c} as vendored with AST (in {@code cminpack/} of the AST
 * source), itself from the MINPACK FORTRAN of the Argonne National Laboratory MINPACK project,
 * March 1980, by Burton S. Garbow, Kenneth E. Hillstrom and Jorge J. More, translated to C by
 * Manolis Lourakis and repackaged by Frederic Devernay. Only the plain C paths are ported: AST
 * builds cminpack without LAPACK or CBLAS.
 *
 * <p>Apache Commons Math's {@code LevenbergMarquardtOptimizer} is a Java translation of the same
 * MINPACK routine and could replace this port, at the cost of a runtime dependency.
 *
 * <p>This product includes software developed by the University of Chicago, as Operator of Argonne
 * National Laboratory. The MINPACK copyright notice, conditions and disclaimer are at the head of
 * this file's source.
 */
public final class Lmder {

    private static final double P1 = 0.1;
    private static final double P5 = 0.5;
    private static final double P25 = 0.25;
    private static final double P75 = 0.75;
    private static final double P0001 = 1e-4;

    /** The functions to minimise and their Jacobian: cminpack's {@code cminpack_funcder_mn}. */
    @FunctionalInterface
    public interface Function {

        /**
         * Calculates the {@code m} functions of the {@code n} variables at {@code x} into {@code
         * fvec} if {@code iflag} is 1, leaving {@code fjac} alone, or their Jacobian into {@code
         * fjac} if {@code iflag} is 2, leaving {@code fvec} alone. The Jacobian is held by columns
         * with leading dimension {@code ldfjac}: the derivative of function i with respect to
         * variable j is {@code fjac[i + j * ldfjac]}.
         *
         * @return a negative value to end the minimisation, which then returns it; otherwise zero
         */
        int apply(int m, int n, double[] x, double[] fvec, double[] fjac, int ldfjac, int iflag);
    }

    private Lmder() {}

    /** C's {@code max} macro from cminpack's {@code cminpackP.h}: {@code a >= b ? a : b}. */
    static double max(double a, double b) {
        return a >= b ? a : b;
    }

    /** C's {@code min} macro from cminpack's {@code cminpackP.h}: {@code a <= b ? a : b}. */
    static double min(double a, double b) {
        return a <= b ? a : b;
    }

    /** C's {@code min} macro for integers. */
    static int min(int a, int b) {
        return a <= b ? a : b;
    }

    /**
     * Minimises the sum of the squares of {@code m} nonlinear functions in {@code n} variables by a
     * modification of the Levenberg-Marquardt algorithm.
     *
     * <p>The result, {@code info}, is the value {@code fcn} returned if it was negative, or: 0,
     * improper input parameters; 1, both the actual and predicted relative reductions in the sum
     * of squares are at most {@code ftol}; 2, the relative error between two consecutive iterates
     * is at most {@code xtol}; 3, both of those; 4, the cosine of the angle between {@code fvec}
     * and any column of the Jacobian is at most {@code gtol} in absolute value; 5, {@code fcn} has
     * been called with {@code iflag} 1 {@code maxfev} times; 6, {@code ftol} is too small to reduce
     * the sum of squares further; 7, {@code xtol} is too small to improve the solution further; 8,
     * {@code gtol} is too small to make {@code fvec} more orthogonal to the columns of the
     * Jacobian.
     *
     * @param fcn the functions and their Jacobian
     * @param m the number of functions
     * @param n the number of variables, at most {@code m}
     * @param x the initial estimate of the solution, replaced by the final one
     * @param fvec receives the functions at the final {@code x}
     * @param fjac receives, in its upper {@code n} by {@code n} part, an upper triangular matrix R
     *     with diagonal elements of non-increasing magnitude such that {@code P^T (J^T J) P = R^T
     *     R}, P being the permutation {@code ipvt} gives and J the final Jacobian
     * @param ldfjac the leading dimension of {@code fjac}, at least {@code m}
     * @param ftol the tolerance on the relative reduction of the sum of squares
     * @param xtol the tolerance on the relative error between two iterates
     * @param gtol the tolerance on the orthogonality of the functions and the Jacobian's columns
     * @param maxfev the most calls of {@code fcn} with {@code iflag} 1
     * @param diag the scale factors of the variables: given if {@code mode} is 2, otherwise set
     *     internally
     * @param mode 1 to scale the variables internally, 2 to use {@code diag}
     * @param factor the initial step bound is {@code factor} times the scaled length of {@code x},
     *     or {@code factor} itself if that is zero
     * @param nprint if positive, {@code fcn} is called with {@code iflag} 0 at the start of every
     *     {@code nprint}-th iteration and on return
     * @param counts receives the numbers of calls of {@code fcn} with {@code iflag} 1 and 2
     * @param ipvt receives the permutation: column j of P is column {@code ipvt[j]} (1-based) of the
     *     identity matrix
     * @param qtf receives the first {@code n} elements of {@code Q^T fvec}
     * @param wa1 work space of {@code n} values
     * @param wa2 work space of {@code n} values
     * @param wa3 work space of {@code n} values
     * @param wa4 work space of {@code m} values
     * @return {@code info}
     */
    public static int lmder(
            Function fcn,
            int m,
            int n,
            double[] x,
            double[] fvec,
            double[] fjac,
            int ldfjac,
            double ftol,
            double xtol,
            double gtol,
            int maxfev,
            double[] diag,
            int mode,
            double factor,
            int nprint,
            int[] counts,
            int[] ipvt,
            double[] qtf,
            double[] wa1,
            double[] wa2,
            double[] wa3,
            double[] wa4) {
        counts[0] = 0;
        counts[1] = 0;
        int info = minimise(
                fcn, m, n, x, fvec, fjac, ldfjac, ftol, xtol, gtol, maxfev, diag, mode, factor, nprint, counts, ipvt,
                qtf, wa1, wa2, wa3, wa4);

        // Termination, either normal or imposed by fcn, whose negative value minimise returns.
        if (nprint > 0) {
            fcn.apply(m, n, x, fvec, fjac, ldfjac, 0);
        }
        return info;
    }

    /**
     * The body of {@link #lmder} up to its termination: {@code info}, or the negative value {@code
     * fcn} returned, which ended it.
     */
    // The loops are cminpack's, which copy as part of other work in the same pass.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static int minimise(
            Function fcn,
            int m,
            int n,
            double[] x,
            double[] fvec,
            double[] fjac,
            int ldfjac,
            double ftol,
            double xtol,
            double gtol,
            int maxfev,
            double[] diag,
            int mode,
            double factor,
            int nprint,
            int[] counts,
            int[] ipvt,
            double[] qtf,
            double[] wa1,
            double[] wa2,
            double[] wa3,
            double[] wa4) {
        double epsmch = Dpmpar.dpmpar(1);
        int info = 0;
        double delta = 0.0;
        double xnorm = 0.0;
        double[] par = {0.0};

        // Check the input parameters for errors.
        if (n <= 0 || m < n || ldfjac < m || ftol < 0.0 || xtol < 0.0 || gtol < 0.0 || maxfev <= 0 || factor <= 0.0) {
            return 0;
        }
        if (mode == 2) {
            for (int j = 0; j < n; ++j) {
                if (diag[j] <= 0.0) {
                    return 0;
                }
            }
        }

        // Evaluate the function at the starting point and calculate its norm.
        int iflag = fcn.apply(m, n, x, fvec, fjac, ldfjac, 1);
        counts[0] = 1;
        if (iflag < 0) {
            return iflag;
        }
        double fnorm = Enorm.enorm(m, fvec, 0);

        // Initialise Levenberg-Marquardt parameter and iteration counter.
        int iter = 1;

        // Beginning of the outer loop.
        for (; ; ) {
            // Calculate the Jacobian matrix.
            iflag = fcn.apply(m, n, x, fvec, fjac, ldfjac, 2);
            ++counts[1];
            if (iflag < 0) {
                return iflag;
            }

            // If requested, call fcn to enable printing of iterates.
            if (nprint > 0) {
                iflag = 0;
                if ((iter - 1) % nprint == 0) {
                    iflag = fcn.apply(m, n, x, fvec, fjac, ldfjac, 0);
                }
                if (iflag < 0) {
                    return iflag;
                }
            }

            // Compute the QR factorisation of the Jacobian.
            Qrfac.qrfac(m, n, fjac, ldfjac, true, ipvt, wa1, wa2, wa3);

            // On the first iteration and if mode is 1, scale according to the norms of the
            // columns of the initial Jacobian.
            if (iter == 1) {
                if (mode != 2) {
                    for (int j = 0; j < n; ++j) {
                        diag[j] = wa2[j];
                        if (wa2[j] == 0.0) {
                            diag[j] = 1.0;
                        }
                    }
                }

                // On the first iteration, calculate the norm of the scaled x and initialise the
                // step bound delta.
                for (int j = 0; j < n; ++j) {
                    wa3[j] = diag[j] * x[j];
                }
                xnorm = Enorm.enorm(n, wa3, 0);
                delta = factor * xnorm;
                if (delta == 0.0) {
                    delta = factor;
                }
            }

            // Form Q^T fvec and store the first n components in qtf.
            System.arraycopy(fvec, 0, wa4, 0, m);
            for (int j = 0; j < n; ++j) {
                if (fjac[j + j * ldfjac] != 0.0) {
                    double sum = 0.0;
                    for (int i = j; i < m; ++i) {
                        sum += fjac[i + j * ldfjac] * wa4[i];
                    }
                    double temp = -sum / fjac[j + j * ldfjac];
                    for (int i = j; i < m; ++i) {
                        wa4[i] += fjac[i + j * ldfjac] * temp;
                    }
                }
                fjac[j + j * ldfjac] = wa1[j];
                qtf[j] = wa4[j];
            }

            // Compute the norm of the scaled gradient.
            double gnorm = 0.0;
            if (fnorm != 0.0) {
                for (int j = 0; j < n; ++j) {
                    int l = ipvt[j] - 1;
                    if (wa2[l] != 0.0) {
                        double sum = 0.0;
                        for (int i = 0; i <= j; ++i) {
                            sum += fjac[i + j * ldfjac] * (qtf[i] / fnorm);
                        }
                        gnorm = max(gnorm, Math.abs(sum / wa2[l]));
                    }
                }
            }

            // Test for convergence of the gradient norm.
            if (gnorm <= gtol) {
                info = 4;
            }
            if (info != 0) {
                return info;
            }

            // Rescale if necessary.
            if (mode != 2) {
                for (int j = 0; j < n; ++j) {
                    diag[j] = max(diag[j], wa2[j]);
                }
            }

            // Beginning of the inner loop.
            double ratio;
            do {
                // Determine the Levenberg-Marquardt parameter.
                Lmpar.lmpar(n, fjac, ldfjac, ipvt, diag, qtf, delta, par, wa1, wa2, wa3, wa4);

                // Store the direction p and x + p. Calculate the norm of p.
                for (int j = 0; j < n; ++j) {
                    wa1[j] = -wa1[j];
                    wa2[j] = x[j] + wa1[j];
                    wa3[j] = diag[j] * wa1[j];
                }
                double pnorm = Enorm.enorm(n, wa3, 0);

                // On the first iteration, adjust the initial step bound.
                if (iter == 1) {
                    delta = min(delta, pnorm);
                }

                // Evaluate the function at x + p and calculate its norm.
                iflag = fcn.apply(m, n, wa2, wa4, fjac, ldfjac, 1);
                ++counts[0];
                if (iflag < 0) {
                    return iflag;
                }
                double fnorm1 = Enorm.enorm(m, wa4, 0);

                // Compute the scaled actual reduction.
                double actred = -1.0;
                if (P1 * fnorm1 < fnorm) {
                    double d1 = fnorm1 / fnorm;
                    actred = 1.0 - d1 * d1;
                }

                // Compute the scaled predicted reduction and the scaled directional derivative.
                for (int j = 0; j < n; ++j) {
                    wa3[j] = 0.0;
                    int l = ipvt[j] - 1;
                    double temp = wa1[l];
                    for (int i = 0; i <= j; ++i) {
                        wa3[i] += fjac[i + j * ldfjac] * temp;
                    }
                }
                double temp1 = Enorm.enorm(n, wa3, 0) / fnorm;
                double temp2 = (Math.sqrt(par[0]) * pnorm) / fnorm;
                double prered = temp1 * temp1 + temp2 * temp2 / P5;
                double dirder = -(temp1 * temp1 + temp2 * temp2);

                // Compute the ratio of the actual to the predicted reduction.
                ratio = 0.0;
                if (prered != 0.0) {
                    ratio = actred / prered;
                }

                // Update the step bound.
                if (ratio <= P25) {
                    double temp;
                    if (actred >= 0.0) {
                        temp = P5;
                    } else {
                        temp = P5 * dirder / (dirder + P5 * actred);
                    }
                    if (P1 * fnorm1 >= fnorm || temp < P1) {
                        temp = P1;
                    }
                    delta = temp * min(delta, pnorm / P1);
                    par[0] /= temp;
                } else if (par[0] == 0.0 || ratio >= P75) {
                    delta = pnorm / P5;
                    par[0] = P5 * par[0];
                }

                // Test for successful iteration.
                if (ratio >= P0001) {
                    // Successful iteration. Update x, fvec, and their norms.
                    for (int j = 0; j < n; ++j) {
                        x[j] = wa2[j];
                        wa2[j] = diag[j] * x[j];
                    }
                    System.arraycopy(wa4, 0, fvec, 0, m);
                    xnorm = Enorm.enorm(n, wa2, 0);
                    fnorm = fnorm1;
                    ++iter;
                }

                // Tests for convergence.
                if (Math.abs(actred) <= ftol && prered <= ftol && P5 * ratio <= 1.0) {
                    info = 1;
                }
                if (delta <= xtol * xnorm) {
                    info = 2;
                }
                if (Math.abs(actred) <= ftol && prered <= ftol && P5 * ratio <= 1.0 && info == 2) {
                    info = 3;
                }
                if (info != 0) {
                    return info;
                }

                // Tests for termination and stringent tolerances.
                if (counts[0] >= maxfev) {
                    info = 5;
                }
                if (Math.abs(actred) <= epsmch && prered <= epsmch && P5 * ratio <= 1.0) {
                    info = 6;
                }
                if (delta <= epsmch * xnorm) {
                    info = 7;
                }
                if (gnorm <= epsmch) {
                    info = 8;
                }
                if (info != 0) {
                    return info;
                }
            } while (ratio < P0001);
        }
    }
}
