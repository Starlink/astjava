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
 * Position/velocity from Cartesian to spherical coordinates: {@code eraPv2s}.
 *
 * <p>Ported from Rust {@code erfa/pv2s.rs}, itself ported from ERFA's {@code eraPv2s} ({@code
 * erfa/pv2s.c}, ERFA 2.0.1).
 */
public final class Pv2s {

    private Pv2s() {}

    /**
     * Converts position/velocity from Cartesian to spherical coordinates, returning {@code {theta,
     * phi, r, td, pd, rd}}: longitude, latitude, radial distance and their rates of change.
     */
    public static double[] pv2s(double[][] pv) {
        // Components of position/velocity vector.
        double x = pv[0][0];
        double y = pv[0][1];
        double z = pv[0][2];
        double xd = pv[1][0];
        double yd = pv[1][1];
        double zd = pv[1][2];

        // Component of r in XY plane squared.
        double rxy2 = x * x + y * y;

        // Modulus squared.
        double r2 = rxy2 + z * z;

        // Modulus.
        double rtrue = Math.sqrt(r2);

        // If null vector, move the origin along the direction of movement.
        double rw = rtrue;
        if (rtrue == 0.0) {
            x = xd;
            y = yd;
            z = zd;
            rxy2 = x * x + y * y;
            r2 = rxy2 + z * z;
            rw = Math.sqrt(r2);
        }

        // Position and velocity in spherical coordinates.
        double rxy = Math.sqrt(rxy2);
        double xyp = x * xd + y * yd;
        double theta;
        double phi;
        double td;
        double pd;
        if (rxy2 != 0.0) {
            theta = Math.atan2(y, x);
            phi = Math.atan2(z, rxy);
            td = (x * yd - y * xd) / rxy2;
            pd = (zd * rxy2 - z * xyp) / (r2 * rxy);
        } else {
            theta = 0.0;
            phi = z != 0.0 ? Math.atan2(z, rxy) : 0.0;
            td = 0.0;
            pd = 0.0;
        }
        double rd = rw != 0.0 ? (xyp + z * zd) / rw : 0.0;
        return new double[] {theta, phi, rtrue, td, pd, rd};
    }
}
