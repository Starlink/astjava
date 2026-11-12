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
 * The CIO locator s, IAU 2006/2000A: {@code eraS06}.
 *
 * <p>Ported from Rust {@code erfa/s06.rs}, itself ported from ERFA's {@code eraS06} ({@code
 * erfa/s06.c}, ERFA 2.0.1).
 */
public final class S06 {

    private S06() {}

    /**
     * One term of a series for s + XY/2: the multipliers of the fundamental arguments l, l', F, D,
     * Omega, LVe, LE and pA, then the sine and cosine amplitudes in arcseconds.
     */
    // MODERN-JAVA(16): record
    private static final class Term {
        final int[] nfa;
        final double s;
        final double c;

        Term(int n0, int n1, int n2, int n3, int n4, int n5, int n6, int n7, double s, double c) {
            this.nfa = new int[] {n0, n1, n2, n3, n4, n5, n6, n7};
            this.s = s;
            this.c = c;
        }
    }

    /** Polynomial coefficients. */
    private static final double[] SP = {
        // 1-6
        94.00e-6, 3808.65e-6, -122.68e-6, -72574.11e-6, 27.98e-6, 15.62e-6
    };

    /** Terms of order t^0. */
    private static final Term[] S0 = {
        // 1-10
        new Term(0, 0, 0, 0, 1, 0, 0, 0, -2640.73e-6, 0.39e-6),
        new Term(0, 0, 0, 0, 2, 0, 0, 0, -63.53e-6, 0.02e-6),
        new Term(0, 0, 2, -2, 3, 0, 0, 0, -11.75e-6, -0.01e-6),
        new Term(0, 0, 2, -2, 1, 0, 0, 0, -11.21e-6, -0.01e-6),
        new Term(0, 0, 2, -2, 2, 0, 0, 0, 4.57e-6, 0.00e-6),
        new Term(0, 0, 2, 0, 3, 0, 0, 0, -2.02e-6, 0.00e-6),
        new Term(0, 0, 2, 0, 1, 0, 0, 0, -1.98e-6, 0.00e-6),
        new Term(0, 0, 0, 0, 3, 0, 0, 0, 1.72e-6, 0.00e-6),
        new Term(0, 1, 0, 0, 1, 0, 0, 0, 1.41e-6, 0.01e-6),
        new Term(0, 1, 0, 0, -1, 0, 0, 0, 1.26e-6, 0.01e-6),
        // 11-20
        new Term(1, 0, 0, 0, -1, 0, 0, 0, 0.63e-6, 0.00e-6),
        new Term(1, 0, 0, 0, 1, 0, 0, 0, 0.63e-6, 0.00e-6),
        new Term(0, 1, 2, -2, 3, 0, 0, 0, -0.46e-6, 0.00e-6),
        new Term(0, 1, 2, -2, 1, 0, 0, 0, -0.45e-6, 0.00e-6),
        new Term(0, 0, 4, -4, 4, 0, 0, 0, -0.36e-6, 0.00e-6),
        new Term(0, 0, 1, -1, 1, -8, 12, 0, 0.24e-6, 0.12e-6),
        new Term(0, 0, 2, 0, 0, 0, 0, 0, -0.32e-6, 0.00e-6),
        new Term(0, 0, 2, 0, 2, 0, 0, 0, -0.28e-6, 0.00e-6),
        new Term(1, 0, 2, 0, 3, 0, 0, 0, -0.27e-6, 0.00e-6),
        new Term(1, 0, 2, 0, 1, 0, 0, 0, -0.26e-6, 0.00e-6),
        // 21-30
        new Term(0, 0, 2, -2, 0, 0, 0, 0, 0.21e-6, 0.00e-6),
        new Term(0, 1, -2, 2, -3, 0, 0, 0, -0.19e-6, 0.00e-6),
        new Term(0, 1, -2, 2, -1, 0, 0, 0, -0.18e-6, 0.00e-6),
        new Term(0, 0, 0, 0, 0, 8, -13, -1, 0.10e-6, -0.05e-6),
        new Term(0, 0, 0, 2, 0, 0, 0, 0, -0.15e-6, 0.00e-6),
        new Term(2, 0, -2, 0, -1, 0, 0, 0, 0.14e-6, 0.00e-6),
        new Term(0, 1, 2, -2, 2, 0, 0, 0, 0.14e-6, 0.00e-6),
        new Term(1, 0, 0, -2, 1, 0, 0, 0, -0.14e-6, 0.00e-6),
        new Term(1, 0, 0, -2, -1, 0, 0, 0, -0.14e-6, 0.00e-6),
        new Term(0, 0, 4, -2, 4, 0, 0, 0, -0.13e-6, 0.00e-6),
        // 31-33
        new Term(0, 0, 2, -2, 4, 0, 0, 0, 0.11e-6, 0.00e-6),
        new Term(1, 0, -2, 0, -3, 0, 0, 0, -0.11e-6, 0.00e-6),
        new Term(1, 0, -2, 0, -1, 0, 0, 0, -0.11e-6, 0.00e-6)
    };

    /** Terms of order t^1. */
    private static final Term[] S1 = {
        // 1 - 3
        new Term(0, 0, 0, 0, 2, 0, 0, 0, -0.07e-6, 3.57e-6),
        new Term(0, 0, 0, 0, 1, 0, 0, 0, 1.73e-6, -0.03e-6),
        new Term(0, 0, 2, -2, 3, 0, 0, 0, 0.00e-6, 0.48e-6)
    };

    /** Terms of order t^2. */
    private static final Term[] S2 = {
        // 1-10
        new Term(0, 0, 0, 0, 1, 0, 0, 0, 743.52e-6, -0.17e-6),
        new Term(0, 0, 2, -2, 2, 0, 0, 0, 56.91e-6, 0.06e-6),
        new Term(0, 0, 2, 0, 2, 0, 0, 0, 9.84e-6, -0.01e-6),
        new Term(0, 0, 0, 0, 2, 0, 0, 0, -8.85e-6, 0.01e-6),
        new Term(0, 1, 0, 0, 0, 0, 0, 0, -6.38e-6, -0.05e-6),
        new Term(1, 0, 0, 0, 0, 0, 0, 0, -3.07e-6, 0.00e-6),
        new Term(0, 1, 2, -2, 2, 0, 0, 0, 2.23e-6, 0.00e-6),
        new Term(0, 0, 2, 0, 1, 0, 0, 0, 1.67e-6, 0.00e-6),
        new Term(1, 0, 2, 0, 2, 0, 0, 0, 1.30e-6, 0.00e-6),
        new Term(0, 1, -2, 2, -2, 0, 0, 0, 0.93e-6, 0.00e-6),
        // 11-20
        new Term(1, 0, 0, -2, 0, 0, 0, 0, 0.68e-6, 0.00e-6),
        new Term(0, 0, 2, -2, 1, 0, 0, 0, -0.55e-6, 0.00e-6),
        new Term(1, 0, -2, 0, -2, 0, 0, 0, 0.53e-6, 0.00e-6),
        new Term(0, 0, 0, 2, 0, 0, 0, 0, -0.27e-6, 0.00e-6),
        new Term(1, 0, 0, 0, 1, 0, 0, 0, -0.27e-6, 0.00e-6),
        new Term(1, 0, -2, -2, -2, 0, 0, 0, -0.26e-6, 0.00e-6),
        new Term(1, 0, 0, 0, -1, 0, 0, 0, -0.25e-6, 0.00e-6),
        new Term(1, 0, 2, 0, 1, 0, 0, 0, 0.22e-6, 0.00e-6),
        new Term(2, 0, 0, -2, 0, 0, 0, 0, -0.21e-6, 0.00e-6),
        new Term(2, 0, -2, 0, -1, 0, 0, 0, 0.20e-6, 0.00e-6),
        // 21-25
        new Term(0, 0, 2, 2, 2, 0, 0, 0, 0.17e-6, 0.00e-6),
        new Term(2, 0, 2, 0, 2, 0, 0, 0, 0.13e-6, 0.00e-6),
        new Term(2, 0, 0, 0, 0, 0, 0, 0, -0.13e-6, 0.00e-6),
        new Term(1, 0, 2, -2, 2, 0, 0, 0, -0.12e-6, 0.00e-6),
        new Term(0, 0, 2, 0, 0, 0, 0, 0, -0.11e-6, 0.00e-6)
    };

    /** Terms of order t^3. */
    private static final Term[] S3 = {
        // 1-4
        new Term(0, 0, 0, 0, 1, 0, 0, 0, 0.30e-6, -23.42e-6),
        new Term(0, 0, 2, -2, 2, 0, 0, 0, -0.03e-6, -1.46e-6),
        new Term(0, 0, 2, 0, 2, 0, 0, 0, -0.01e-6, -0.25e-6),
        new Term(0, 0, 0, 0, 2, 0, 0, 0, 0.00e-6, 0.23e-6)
    };

    /** Terms of order t^4. */
    private static final Term[] S4 = {
        // 1-1
        new Term(0, 0, 0, 0, 1, 0, 0, 0, -0.26e-6, -0.01e-6)
    };

    /** The sum of one series at the fundamental arguments {@code fa}, added to {@code w} from the last term to the first. */
    private static double series(double w, Term[] terms, double[] fa) {
        for (int i = terms.length - 1; i >= 0; i--) {
            Term term = terms[i];
            double a = 0.0;
            for (int j = 0; j < 8; j++) {
                a += term.nfa[j] * fa[j];
            }
            w += term.s * Math.sin(a) + term.c * Math.cos(a);
        }
        return w;
    }

    /**
     * The CIO locator s, in radians, for the TT date {@code date1 + date2} (a two-part Julian
     * Date), given the CIP coordinates {@code x} and {@code y}, consistent with the IAU 2006
     * precession and IAU 2000A nutation.
     */
    public static double s06(double date1, double date2, double x, double y) {
        // Interval between fundamental epoch J2000.0 and current date (JC).
        double t = ((date1 - Erfa.ERFA_DJ00) + date2) / Erfa.ERFA_DJC;

        // Fundamental Arguments (from IERS Conventions 2003): mean anomaly of the Moon, mean
        // anomaly of the Sun, mean longitude of the Moon minus that of the ascending node, mean
        // elongation of the Moon from the Sun, mean longitude of the ascending node of the Moon,
        // mean longitude of Venus, mean longitude of Earth, general precession in longitude.
        double[] fa = {
            Fal03.fal03(t),
            Falp03.falp03(t),
            Faf03.faf03(t),
            Fad03.fad03(t),
            Faom03.faom03(t),
            Fave03.fave03(t),
            Fae03.fae03(t),
            Fapa03.fapa03(t)
        };

        // Evaluate s.
        double w0 = series(SP[0], S0, fa);
        double w1 = series(SP[1], S1, fa);
        double w2 = series(SP[2], S2, fa);
        double w3 = series(SP[3], S3, fa);
        double w4 = series(SP[4], S4, fa);
        double w5 = SP[5];

        return (w0 + (w1 + (w2 + (w3 + (w4 + w5 * t) * t) * t) * t) * t) * Erfa.ERFA_DAS2R - x * y / 2.0;
    }
}
