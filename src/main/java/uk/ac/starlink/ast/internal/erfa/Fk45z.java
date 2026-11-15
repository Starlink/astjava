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
 * FK4 to FK5, assuming zero proper motion in FK5: {@code eraFk45z}.
 *
 * <p>Ported from Rust {@code erfa/fk45z.rs}, itself ported from ERFA's {@code eraFk45z} ({@code
 * erfa/fk45z.c}, ERFA 2.0.1).
 */
public final class Fk45z {

    private Fk45z() {}

    // MODERN-JAVA(9): Rust's Fk45zEpoch and fk45z_kernel, the branchless form a loop over many
    // positions vectorises, take their sines, cosines and arctangents from kernels needing Math.fma
    // and the Vector API; every position here goes through fk45z.

    /** Vector A (Seidelmann 3.591-2). */
    private static final double[] A = {-1.62557e-6, -0.31919e-6, -0.13843e-6};

    /** Vector Adot (Seidelmann 3.591-2). */
    private static final double[] AD = {1.245e-3, -1.580e-3, -0.659e-3};

    /** 3x2 matrix of p-vectors (cf. Seidelmann 3.591-4, matrix M). */
    private static final double[][][] EM = {
        {
            {0.9999256782, -0.0111820611, -0.0048579477},
            {0.0111820610, 0.9999374784, -0.0000271765},
            {0.0048579479, -0.0000271474, 0.9999881997}
        },
        {
            {-0.000551, -0.238565, 0.435739},
            {0.238514, -0.002667, -0.008541},
            {-0.435623, 0.012254, 0.002117}
        }
    };

    /**
     * Converts B1950.0 FK4 star data to J2000.0 FK5 assuming zero proper motion in the FK5 system,
     * returning {@code {ra, dec}} at J2000.0 in radians. {@code bepoch} is the Besselian epoch of
     * the FK4 position.
     */
    public static double[] fk45z(double r1950, double d1950, double bepoch) {
        // Radians per year to arcsec per century.
        double pmf = 100.0 * Erfa.ERFA_DR2AS;

        // Spherical coordinates to p-vector.
        double[] r0 = S2c.s2c(r1950, d1950);

        // Adjust p-vector A to give zero proper motion in FK5.
        double w = (bepoch - 1950.0) / pmf;
        double[] p = Ppsp.ppsp(A, w, AD);

        // Remove E-terms.
        p = Ppsp.ppsp(p, -Pdp.pdp(r0, p), r0);
        p = Pmp.pmp(r0, p);

        // Convert to Fricke system pv-vector (cf. Seidelmann 3.591-3).
        double[][] pv = new double[2][3];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 3; j++) {
                double wij = 0.0;
                for (int k = 0; k < 3; k++) {
                    wij += EM[i][j][k] * p[k];
                }
                pv[i][j] = wij;
            }
        }

        // Allow for fictitious proper motion.
        double[] jd = Epb2jd.epb2jd(bepoch);
        w = (Epj.epj(jd[0], jd[1]) - 2000.0) / pmf;
        pv = Pvu.pvu(w, pv);

        // Revert to spherical coordinates.
        double[] wd = C2s.c2s(pv[0]);
        return new double[] {Anp.anp(wd[0]), wd[1]};
    }
}
