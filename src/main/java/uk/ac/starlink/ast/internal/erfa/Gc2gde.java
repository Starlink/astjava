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

import java.util.Objects;

/**
 * Geocentric to geodetic coordinates on a general ellipsoid: {@code eraGc2gde}.
 *
 * <p>Ported from Rust {@code erfa/gc2gde.rs}, itself ported from ERFA's {@code eraGc2gde} ({@code
 * erfa/gc2gde.c}, ERFA 2.0.1).
 */
public final class Gc2gde {

    private Gc2gde() {}

    /** Why {@link #gc2gde} gave no result. */
    public enum Gc2gdeError {
        /** The flattening is outside 0 to 1 (ERFA status -1). */
        BAD_FLATTENING,
        /** The equatorial radius is not positive (ERFA status -2). */
        BAD_RADIUS
    }

    /** The result of {@link #gc2gde}: an error, or the geodetic longitude, latitude and height. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** Why there is no result, or null. */
        public final Gc2gdeError error;

        /** Longitude (radians, east positive); zero with an error. */
        public final double elong;

        /** Geodetic latitude (radians); zero with an error. */
        public final double phi;

        /** Height above the ellipsoid, in the units of the radius; zero with an error. */
        public final double height;

        /** A result holding the given values. */
        public Result(Gc2gdeError error, double elong, double phi, double height) {
            this.error = error;
            this.elong = elong;
            this.phi = phi;
            this.height = height;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return error == r.error
                    && Double.compare(elong, r.elong) == 0
                    && Double.compare(phi, r.phi) == 0
                    && Double.compare(height, r.height) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(error, elong, phi, height);
        }

        @Override
        public String toString() {
            return "Gc2gde.Result(" + error + ", " + elong + ", " + phi + ", " + height + ")";
        }
    }

    /**
     * Geocentric Cartesian coordinates to geodetic longitude, latitude and height, on an ellipsoid
     * with the equatorial radius {@code a} and flattening {@code f}; the height is in the units of
     * {@code a}.
     */
    public static Result gc2gde(double a, double f, double[] xyz) {
        // Validate ellipsoid parameters.
        if (f < 0.0 || f >= 1.0) {
            return new Result(Gc2gdeError.BAD_FLATTENING, 0.0, 0.0, 0.0);
        }
        if (a <= 0.0) {
            return new Result(Gc2gdeError.BAD_RADIUS, 0.0, 0.0, 0.0);
        }

        // Functions of ellipsoid parameters (with further validation of f).
        double aeps2 = a * a * 1e-32;
        double e2 = (2.0 - f) * f;
        double e4t = e2 * e2 * 1.5;
        double ec2 = 1.0 - e2;
        if (ec2 <= 0.0) {
            return new Result(Gc2gdeError.BAD_FLATTENING, 0.0, 0.0, 0.0);
        }
        double ec = Math.sqrt(ec2);
        double b = a * ec;

        // Cartesian components.
        double x = xyz[0];
        double y = xyz[1];
        double z = xyz[2];

        // Distance from polar axis squared.
        double p2 = x * x + y * y;

        // Longitude.
        double elong = p2 > 0.0 ? Math.atan2(y, x) : 0.0;

        // Unsigned z-coordinate.
        double absz = Math.abs(z);

        double phi;
        double height;

        // Proceed unless polar case.
        if (p2 > aeps2) {
            // Distance from polar axis.
            double p = Math.sqrt(p2);

            // Normalisation.
            double s0 = absz / a;
            double pn = p / a;
            double zc = ec * s0;

            // Prepare Newton correction factors.
            double c0 = ec * pn;
            double c02 = c0 * c0;
            double c03 = c02 * c0;
            double s02 = s0 * s0;
            double s03 = s02 * s0;
            double a02 = c02 + s02;
            double a0 = Math.sqrt(a02);
            double a03 = a02 * a0;
            double d0 = zc * a03 + e2 * s03;
            double f0 = pn * a03 - e2 * c03;

            // Prepare Halley correction factor.
            double b0 = e4t * s02 * c02 * pn * (a0 - ec);
            double s1 = d0 * f0 - b0 * s0;
            double cc = ec * (f0 * f0 - b0 * c0);

            // Evaluate latitude and height.
            phi = Math.atan(s1 / cc);
            double s12 = s1 * s1;
            double cc2 = cc * cc;
            height = (p * cc + absz * s1 - a * Math.sqrt(ec2 * s12 + cc2)) / Math.sqrt(s12 + cc2);
        } else {
            // Exception: pole.
            phi = Erfa.ERFA_DPI / 2.0;
            height = absz - b;
        }

        // Restore sign of latitude.
        if (z < 0.0) {
            phi = -phi;
        }
        return new Result(null, elong, phi, height);
    }
}
