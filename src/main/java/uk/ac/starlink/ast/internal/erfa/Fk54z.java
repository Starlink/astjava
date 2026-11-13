/*
 * Copyright (C) 2013-2016, NumFOCUS Foundation.
 * All rights reserved.
 *
 * This library is derived, with permission, from the International
 * Astronomical Union's "Standards of Fundamental Astronomy" library,
 * available from http://www.iausofa.org.
 *
 * The ERFA version is intended to retain identical functionality to
 * the SOFA library, but made distinct through different function and
 * file names, as set out in the SOFA license conditions.  The SOFA
 * original has a role as a reference standard for the IAU and IERS,
 * and consequently redistribution is permitted only in its unaltered
 * state.  The ERFA version is not subject to this restriction and
 * therefore can be included in distributions which do not support the
 * concept of "read only" software.
 *
 * Although the intent is to replicate the SOFA API (other than
 * replacement of prefix names) and results (with the exception of
 * bugs;  any that are discovered will be fixed), SOFA is not
 * responsible for any errors found in this version of the library.
 *
 * If you wish to acknowledge the SOFA heritage, please acknowledge
 * that you are using a library derived from SOFA, rather than SOFA
 * itself.
 *
 *
 * TERMS AND CONDITIONS
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1 Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * 2 Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in
 *   the documentation and/or other materials provided with the
 *   distribution.
 *
 * 3 Neither the name of the Standards Of Fundamental Astronomy Board,
 *   the International Astronomical Union nor the names of its
 *   contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package uk.ac.starlink.ast.internal.erfa;

/**
 * FK5 to FK4, assuming zero proper motion in FK5: {@code eraFk54z}.
 *
 * <p>Ported from Rust {@code erfa/fk54z.rs}, itself ported from ERFA's {@code eraFk54z} ({@code
 * erfa/fk54z.c}, ERFA 2.0.1).
 */
public final class Fk54z {

    private Fk54z() {}

    // MODERN-JAVA(9): Rust's fk54z_kernel, the branchless form a loop over many positions
    // vectorises, takes its sines, cosines and arctangents from kernels needing Math.fma and the
    // Vector API; every position here goes through fk54z.

    /**
     * Converts J2000.0 FK5 star data to B1950.0 FK4 assuming zero proper motion and parallax,
     * returning {@code {r1950, d1950, dr1950, dd1950}}: the (R.A., Dec) at the Besselian epoch
     * {@code bepoch} and the fictitious proper motions in radians per year.
     */
    public static double[] fk54z(double r2000, double d2000, double bepoch) {
        // FK5 equinox J2000.0 to FK4 equinox B1950.0.
        double[] fk4 = Fk524.fk524(r2000, d2000, 0.0, 0.0, 0.0, 0.0);
        double r = fk4[0];
        double d = fk4[1];
        double pr = fk4[2];
        double pd = fk4[3];

        // Spherical to Cartesian.
        double[] p = S2c.s2c(r, d);

        // Fictitious proper motion (radians per year).
        double sr = Math.sin(r);
        double cr = Math.cos(r);
        double sd = Math.sin(d);
        double cd = Math.cos(d);
        double[] v = {-pr * p[1] - pd * cr * sd, pr * p[0] - pd * sr * sd, pd * cd};

        // Apply the motion.
        double w = bepoch - 1950.0;
        for (int i = 0; i < 3; i++) {
            p[i] += w * v[i];
        }

        // Cartesian to spherical.
        double[] wd = C2s.c2s(p);
        return new double[] {Anp.anp(wd[0]), wd[1], pr, pd};
    }
}
