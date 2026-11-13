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
 * The Euclidean norm of a vector.
 *
 * <p>Ported from cminpack's {@code enorm.c} as vendored with AST; see {@link Lmder} for its origin;
 * the MINPACK copyright notice is at the head of this file's source.
 */
public final class Enorm {

    /**
     * The magnitude below which components are summed scaled by the largest small one, cminpack's
     * {@code sqrt(dpmpar(2))*0.9}, which replaces MINPACK's original 3.834e-20 because that one's
     * square underflows.
     */
    private static final double RDWARF = 1.4916681462400413e-154 * 0.9;

    /** The magnitude above which components are summed scaled by the largest, {@code sqrt(dpmpar(3))*0.9}. */
    private static final double RGIANT = 1.3407807929942596e+154 * 0.9;

    private Enorm() {}

    /**
     * The Euclidean norm of the {@code n} values of {@code x} from {@code off}. The sum of squares
     * is accumulated in three parts, of small, intermediate and large components, the small and
     * large ones scaled so that the result neither overflows nor underflows when the norm itself
     * would not.
     *
     * @param n the number of values
     * @param x the vector
     * @param off the index of its first value
     * @return the norm
     */
    public static double enorm(int n, double[] x, int off) {
        double s1 = 0.0;
        double s2 = 0.0;
        double s3 = 0.0;
        double x1max = 0.0;
        double x3max = 0.0;
        double agiant = RGIANT / n;
        for (int i = 0; i < n; i++) {
            double xabs = Math.abs(x[off + i]);
            if (xabs <= RDWARF || xabs >= agiant) {
                if (xabs > RDWARF) {
                    // Sum for large components.
                    if (xabs > x1max) {
                        double d1 = x1max / xabs;
                        s1 = 1.0 + s1 * (d1 * d1);
                        x1max = xabs;
                    } else {
                        double d1 = xabs / x1max;
                        s1 += d1 * d1;
                    }
                } else {
                    // Sum for small components.
                    if (xabs > x3max) {
                        double d1 = x3max / xabs;
                        s3 = 1.0 + s3 * (d1 * d1);
                        x3max = xabs;
                    } else if (xabs != 0.0) {
                        double d1 = xabs / x3max;
                        s3 += d1 * d1;
                    }
                }
            } else {
                // Sum for intermediate components.
                s2 += xabs * xabs;
            }
        }
        if (s1 != 0.0) {
            return x1max * Math.sqrt(s1 + (s2 / x1max) / x1max);
        } else if (s2 != 0.0) {
            if (s2 >= x3max) {
                return Math.sqrt(s2 * (1.0 + (x3max / s2) * (x3max * s3)));
            }
            return Math.sqrt(x3max * ((s2 / x3max) + (x3max * s3)));
        }
        return x3max * Math.sqrt(s3);
    }
}
