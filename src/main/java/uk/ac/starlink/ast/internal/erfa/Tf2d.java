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
 * Hours, minutes and seconds to days: {@code eraTf2d}.
 *
 * <p>Ported from Rust {@code erfa/tf2d.rs}, itself ported from ERFA's {@code eraTf2d} ({@code
 * erfa/tf2d.c}, ERFA 2.0.1).
 */
public final class Tf2d {

    private Tf2d() {}

    /** A warning that comes with {@link #tf2d}'s result. */
    public enum Tf2dWarning {
        /** The hour is outside 0 to 23 (ERFA status 1). */
        HOUR,
        /** The minute is outside 0 to 59 (ERFA status 2). */
        MINUTE,
        /** The seconds are outside 0 up to but excluding 60 (ERFA status 3). */
        SECOND
    }

    /** The result of {@link #tf2d}: the interval in days and a warning. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** The interval in days. */
        public final double days;

        /** The first field out of range, or null. */
        public final Tf2dWarning warning;

        /** A result holding the given values. */
        public Result(double days, Tf2dWarning warning) {
            this.days = days;
            this.warning = warning;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return Double.compare(days, r.days) == 0 && warning == r.warning;
        }

        @Override
        public int hashCode() {
            return Objects.hash(days, warning);
        }

        @Override
        public String toString() {
            return "Tf2d.Result(" + days + ", " + warning + ")";
        }
    }

    /**
     * Sign, hours, minutes and seconds to an interval in days, with a warning for a field out of
     * its range; the first field out of range is the one reported. C's {@code abs} of the most
     * negative {@code int} is undefined; here its magnitude is 2^31.
     */
    public static Result tf2d(char s, int ihour, int imin, double sec) {
        // Compute the interval.
        double days = (s == '-' ? -1.0 : 1.0)
                * (60.0 * (60.0 * Math.abs((double) ihour) + Math.abs((double) imin)) + Math.abs(sec))
                / Erfa.ERFA_DAYSEC;

        // Validate arguments.
        Tf2dWarning warning;
        if (ihour < 0 || ihour > 23) {
            warning = Tf2dWarning.HOUR;
        } else if (imin < 0 || imin > 59) {
            warning = Tf2dWarning.MINUTE;
        } else if (sec < 0.0 || sec >= 60.0) {
            warning = Tf2dWarning.SECOND;
        } else {
            warning = null;
        }
        return new Result(days, warning);
    }
}
