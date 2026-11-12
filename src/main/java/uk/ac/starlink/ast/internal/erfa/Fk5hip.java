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

import java.util.Arrays;

/**
 * The FK5 to Hipparcos rotation and spin: {@code eraFk5hip}.
 *
 * <p>Ported from Rust {@code erfa/fk5hip.rs}, itself ported from ERFA's {@code eraFk5hip} ({@code
 * erfa/fk5hip.c}, ERFA 2.0.1).
 */
public final class Fk5hip {

    private Fk5hip() {}

    /** The result of {@link #fk5hip}: the FK5 to Hipparcos rotation matrix and spin vector. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** The orientation of the Hipparcos system relative to FK5, as a rotation matrix. */
        public final double[][] r5h;

        /** The spin of Hipparcos relative to FK5, in radians per year. */
        public final double[] s5h;

        /** A result holding the given values. */
        public Result(double[][] r5h, double[] s5h) {
            this.r5h = r5h;
            this.s5h = s5h;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return Arrays.deepEquals(r5h, r.r5h) && Arrays.equals(s5h, r.s5h);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.deepHashCode(r5h) + Arrays.hashCode(s5h);
        }

        @Override
        public String toString() {
            return "Fk5hip.Result(" + Arrays.deepToString(r5h) + ", " + Arrays.toString(s5h) + ")";
        }
    }

    /**
     * The orientation of the Hipparcos system relative to FK5 as a rotation matrix, and the spin
     * of Hipparcos relative to FK5 in radians per year.
     */
    public static Result fk5hip() {
        // FK5 wrt Hipparcos orientation and spin (radians, radians/year).
        double epx = -19.9e-3 * Erfa.ERFA_DAS2R;
        double epy = -9.1e-3 * Erfa.ERFA_DAS2R;
        double epz = 22.9e-3 * Erfa.ERFA_DAS2R;

        double omx = -0.30e-3 * Erfa.ERFA_DAS2R;
        double omy = 0.60e-3 * Erfa.ERFA_DAS2R;
        double omz = 0.70e-3 * Erfa.ERFA_DAS2R;

        // FK5 to Hipparcos orientation expressed as an r-vector, re-expressed as an r-matrix.
        double[][] r5h = Rv2m.rv2m(new double[] {epx, epy, epz});

        // Hipparcos wrt FK5 spin expressed as an r-vector.
        return new Result(r5h, new double[] {omx, omy, omz});
    }
}
