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
 * The IAU 2006 precession quantities: {@code eraP06e}.
 *
 * <p>Ported from Rust {@code erfa/p06e.rs}, itself ported from ERFA's {@code eraP06e} ({@code
 * erfa/p06e.c}, ERFA 2.0.1).
 */
public final class P06e {

    private P06e() {}

    /**
     * The IAU 2006 precession quantities for the TT date {@code date1 + date2} (a two-part Julian
     * Date), all in radians.
     *
     * <p>The sixteen values are, in order: {@code eps0} (obliquity at J2000.0), {@code psia}
     * (luni-solar precession), {@code oma} (inclination of the mean equator on the J2000.0
     * ecliptic), {@code bpa} (ecliptic pole x, J2000.0 ecliptic triad), {@code bqa} (ecliptic pole
     * -y), {@code pia} (angle between the moving and J2000.0 ecliptics), {@code bpia} (longitude of
     * the ascending node of the moving ecliptic), {@code epsa} (mean obliquity), {@code chia}
     * (planetary precession), {@code za} and {@code zetaa} (the third and first equatorial
     * precession angles, negated), {@code thetaa} (the second), {@code pa} (general precession),
     * and the Fukushima-Williams angles {@code gam}, {@code phi} and {@code psi}.
     */
    public static double[] p06e(double date1, double date2) {
        // Interval between fundamental date J2000.0 and given date (JC).
        double t = ((date1 - Erfa.ERFA_DJ00) + date2) / Erfa.ERFA_DJC;

        // Obliquity at J2000.0.
        double eps0 = 84381.406 * Erfa.ERFA_DAS2R;

        // Luni-solar precession.
        double psia = (5038.481507 + (-1.0790069 + (-0.00114045 + (0.000132851 + -0.0000000951 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // Inclination of mean equator with respect to the J2000.0 ecliptic.
        double oma = eps0
                + (-0.025754 + (0.0512623 + (-0.00772503 + (-0.000000467 + 0.0000003337 * t) * t) * t) * t)
                        * t
                        * Erfa.ERFA_DAS2R;

        // Ecliptic pole x, J2000.0 ecliptic triad.
        double bpa = (4.199094 + (0.1939873 + (-0.00022466 + (-0.000000912 + 0.0000000120 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // Ecliptic pole -y, J2000.0 ecliptic triad.
        double bqa = (-46.811015 + (0.0510283 + (0.00052413 + (-0.000000646 + -0.0000000172 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // Angle between moving and J2000.0 ecliptics.
        double pia = (46.998973 + (-0.0334926 + (-0.00012559 + (0.000000113 + -0.0000000022 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // Longitude of ascending node of the moving ecliptic.
        double bpia = (629546.7936
                        + (-867.95758 + (0.157992 + (-0.0005371 + (-0.00004797 + 0.000000072 * t) * t) * t) * t) * t)
                * Erfa.ERFA_DAS2R;

        // Mean obliquity of the ecliptic.
        double epsa = Obl06.obl06(date1, date2);

        // Planetary precession.
        double chia = (10.556403 + (-2.3814292 + (-0.00121197 + (0.000170663 + -0.0000000560 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // Equatorial precession: minus the third of the 323 Euler angles.
        double za = (-2.650545
                        + (2306.077181 + (1.0927348 + (0.01826837 + (-0.000028596 + -0.0000002904 * t) * t) * t) * t)
                                * t)
                * Erfa.ERFA_DAS2R;

        // Equatorial precession: minus the first of the 323 Euler angles.
        double zetaa = (2.650545
                        + (2306.083227 + (0.2988499 + (0.01801828 + (-0.000005971 + -0.0000003173 * t) * t) * t) * t)
                                * t)
                * Erfa.ERFA_DAS2R;

        // Equatorial precession: second of the 323 Euler angles.
        double thetaa = (2004.191903 + (-0.4294934 + (-0.04182264 + (-0.000007089 + -0.0000001274 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // General precession.
        double pa = (5028.796195 + (1.1054348 + (0.00007964 + (-0.000023857 + -0.0000000383 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        // Fukushima-Williams angles for precession.
        double gam = (10.556403 + (0.4932044 + (-0.00031238 + (-0.000002788 + 0.0000000260 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        double phi = eps0
                + (-46.811015 + (0.0511269 + (0.00053289 + (-0.000000440 + -0.0000000176 * t) * t) * t) * t)
                        * t
                        * Erfa.ERFA_DAS2R;

        double psi = (5038.481507 + (1.5584176 + (-0.00018522 + (-0.000026452 + -0.0000000148 * t) * t) * t) * t)
                * t
                * Erfa.ERFA_DAS2R;

        return new double[] {eps0, psia, oma, bpa, bqa, pia, bpia, epsa, chia, za, zetaa, thetaa, pa, gam, phi, psi};
    }
}
