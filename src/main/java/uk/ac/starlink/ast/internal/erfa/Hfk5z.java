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
 * Hipparcos to FK5, assuming zero Hipparcos proper motion: {@code eraHfk5z}.
 *
 * <p>Ported from Rust {@code erfa/hfk5z.rs}, itself ported from ERFA's {@code eraHfk5z} ({@code
 * erfa/hfk5z.c}, ERFA 2.0.1).
 */
public final class Hfk5z {

    private Hfk5z() {}

    /**
     * Transforms a Hipparcos star position into FK5 J2000.0, assuming zero Hipparcos proper motion,
     * returning {@code {r5, d5, dr5, dd5}}: the (R.A., Dec) at the given date and the FK5 proper
     * motions (radians per year). The date is the two-part Julian Date of the Hipparcos position.
     */
    public static double[] hfk5z(double rh, double dh, double date1, double date2) {
        // Time interval from fundamental epoch J2000.0 to given date (JY).
        double t = ((date1 - Erfa.ERFA_DJ00) + date2) / Erfa.ERFA_DJY;

        // Hipparcos barycentric position vector (normalised).
        double[] ph = S2c.s2c(rh, dh);

        // FK5 to Hipparcos orientation matrix and spin vector.
        Fk5hip.Result hip = Fk5hip.fk5hip();

        // Rotate the spin into the Hipparcos system.
        double[] sh = Rxp.rxp(hip.r5h, hip.s5h);

        // Accumulated Hipparcos wrt FK5 spin over that interval.
        double[] vst = Sxp.sxp(t, hip.s5h);

        // Express the accumulated spin as a rotation matrix.
        double[][] rst = Rv2m.rv2m(vst);

        // Rotation matrix: accumulated spin, then FK5 to Hipparcos.
        double[][] r5ht = Rxr.rxr(hip.r5h, rst);

        // De-orient and de-spin the Hipparcos position into FK5 J2000.0.
        double[][] pv5e = new double[2][];
        pv5e[0] = Trxp.trxp(r5ht, ph);

        // Apply spin to the position giving a space motion.
        double[] vv = Pxp.pxp(sh, ph);

        // De-orient and de-spin the Hipparcos space motion into FK5 J2000.0.
        pv5e[1] = Trxp.trxp(r5ht, vv);

        // FK5 position/velocity pv-vector to spherical.
        double[] s = Pv2s.pv2s(pv5e);
        return new double[] {Anp.anp(s[0]), s[1], s[3], s[4]};
    }
}
