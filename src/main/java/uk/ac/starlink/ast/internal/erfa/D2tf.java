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
 * Days to hours, minutes and seconds: {@code eraD2tf}.
 *
 * <p>Ported from Rust {@code erfa/d2tf.rs}, itself ported from ERFA's {@code eraD2tf} ({@code
 * erfa/d2tf.c}, ERFA 2.0.1).
 */
public final class D2tf {

    private D2tf() {}

    /** The result of {@link #d2tf}: a sign and the hours, minutes, seconds and fraction. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** {@code '+'} or {@code '-'}. */
        public final char sign;

        /** Hours, minutes, seconds and the fraction of a second in units of the resolution. */
        public final int[] ihmsf;

        /** A result holding the given values. */
        public Result(char sign, int[] ihmsf) {
            this.sign = sign;
            this.ihmsf = ihmsf;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return sign == r.sign && Arrays.equals(ihmsf, r.ihmsf);
        }

        @Override
        public int hashCode() {
            return 31 * sign + Arrays.hashCode(ihmsf);
        }

        @Override
        public String toString() {
            return "D2tf.Result(" + sign + ", " + Arrays.toString(ihmsf) + ")";
        }
    }

    /**
     * Interval in days to a sign and hours, minutes, seconds and fraction of a second, with {@code
     * ndp} decimal places of seconds.
     *
     * <p>The fields are converted to {@code int} by saturating, with NaN as 0, and a resolution of
     * more than 9 places overflows ERFA's {@code int} unit count, which wraps. These are the port's
     * defined results; C's are undefined there.
     */
    public static Result d2tf(int ndp, double days) {
        // Handle sign.
        char sign = days >= 0.0 ? '+' : '-';

        // Interval in seconds.
        double a = Erfa.ERFA_DAYSEC * Math.abs(days);

        // Pre-round if resolution coarser than 1s (then pretend ndp=1).
        if (ndp < 0) {
            int nrs = 1;
            for (int n = 1; n <= -ndp; n++) {
                nrs *= n == 2 || n == 4 ? 6 : 10;
            }
            double rs = nrs;
            double w = a / rs;
            a = rs * Erfa.dnint(w);
        }

        // Express the unit of each field in resolution units.
        int nrs = 1;
        for (int n = 1; n <= ndp; n++) {
            nrs *= 10;
        }
        double rs = nrs;
        double rm = rs * 60.0;
        double rh = rm * 60.0;

        // Round the interval and express in resolution units.
        a = Erfa.dnint(rs * a);

        // Break into fields.
        double ah = a / rh;
        ah = Erfa.dint(ah);
        a -= ah * rh;
        double am = a / rm;
        am = Erfa.dint(am);
        a -= am * rm;
        double as = a / rs;
        as = Erfa.dint(as);
        double af = a - as * rs;

        return new Result(sign, new int[] {(int) ah, (int) am, (int) as, (int) af});
    }
}
