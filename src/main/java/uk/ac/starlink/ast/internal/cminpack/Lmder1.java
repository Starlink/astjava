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
 * The simple driver for {@link Lmder}, which chooses its tolerances and limits.
 *
 * <p>Ported from cminpack's {@code lmder1.c} as vendored with AST; see {@link Lmder} for its
 * origin; the MINPACK copyright notice is at the head of this file's source.
 */
public final class Lmder1 {

    /** The initial step bound factor lmder1 gives lmder. */
    private static final double FACTOR = 100.0;

    private Lmder1() {}

    /**
     * Minimises the sum of the squares of {@code m} nonlinear functions in {@code n} variables by a
     * modification of the Levenberg-Marquardt algorithm, using {@link Lmder#lmder} with both its
     * tolerances on the relative reduction of the sum of squares and on the relative change of the
     * solution set to {@code tol}, its gradient tolerance zero, at most {@code 100 (n + 1)}
     * function evaluations, internal scaling and step bound factor 100. C's {@code lmder1} carves
     * lmder's work arrays from one array it is given; here they are allocated separately.
     *
     * <p>The result, {@code info}, is the value {@code fcn} returned if it was negative, or: 0,
     * improper input parameters; 1, the relative error in the sum of squares is estimated to be at
     * most {@code tol}; 2, the relative error between {@code x} and the solution is estimated to be
     * at most {@code tol}; 3, both of those; 4, {@code fvec} is orthogonal to the columns of the
     * Jacobian to machine precision; 5, {@code fcn} has been called with {@code iflag} 1 {@code 100
     * (n + 1)} times; 6, {@code tol} is too small to reduce the sum of squares further; 7, {@code
     * tol} is too small to improve {@code x} further.
     *
     * @param fcn the functions and their Jacobian
     * @param m the number of functions
     * @param n the number of variables, at most {@code m}
     * @param x the initial estimate of the solution, replaced by the final one
     * @param fvec receives the functions at the final {@code x}
     * @param fjac receives, in its upper {@code n} by {@code n} part, the upper triangular matrix R
     *     of {@link Lmder#lmder}
     * @param ldfjac the leading dimension of {@code fjac}, at least {@code m}
     * @param tol the tolerance, not negative
     * @param ipvt receives the permutation: column j of P is column {@code ipvt[j]} (1-based) of the
     *     identity matrix
     * @return {@code info}
     */
    public static int lmder1(
            Lmder.Function fcn,
            int m,
            int n,
            double[] x,
            double[] fvec,
            double[] fjac,
            int ldfjac,
            double tol,
            int[] ipvt) {
        // Check the input parameters for errors.
        if (n <= 0 || m < n || ldfjac < m || tol < 0.0) {
            return 0;
        }
        int maxfev = (n + 1) * 100;
        int info = Lmder.lmder(
                fcn,
                m,
                n,
                x,
                fvec,
                fjac,
                ldfjac,
                tol,
                tol,
                0.0,
                maxfev,
                new double[n],
                1,
                FACTOR,
                0,
                new int[2],
                ipvt,
                new double[n],
                new double[n],
                new double[n],
                new double[n],
                new double[m]);
        if (info == 8) {
            info = 4;
        }
        return info;
    }
}
