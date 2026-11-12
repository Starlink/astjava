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
 * Gregorian calendar date to Julian Date: {@code eraCal2jd}.
 *
 * <p>Ported from Rust {@code erfa/cal2jd.rs}, itself ported from ERFA's {@code eraCal2jd} ({@code
 * erfa/cal2jd.c}, ERFA 2.0.1).
 */
public final class Cal2jd {

    private Cal2jd() {}

    /** Why {@link #cal2jd} gave no result. */
    public enum Cal2jdError {
        /** Year earlier than -4799 (ERFA status -1). */
        BAD_YEAR,
        /** Month outside 1 to 12 (ERFA status -2). */
        BAD_MONTH
    }

    /** A warning that comes with {@link #cal2jd}'s result. */
    public enum Cal2jdWarning {
        /**
         * The day is not a day of the month (ERFA status -3). The result is the date the
         * arithmetic gives, as for a valid day.
         */
        BAD_DAY
    }

    /** The result of {@link #cal2jd}: an error, or a two-part Julian Date and a warning. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** Why there is no date, or null. */
        public final Cal2jdError error;

        /** The first part of the date, 2400000.5; zero with an error. */
        public final double djm0;

        /** The second part of the date, the Modified Julian Date; zero with an error. */
        public final double djm;

        /** The warning, or null. */
        public final Cal2jdWarning warning;

        /** A result holding the given values. */
        public Result(Cal2jdError error, double djm0, double djm, Cal2jdWarning warning) {
            this.error = error;
            this.djm0 = djm0;
            this.djm = djm;
            this.warning = warning;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return error == r.error
                    && Double.compare(djm0, r.djm0) == 0
                    && Double.compare(djm, r.djm) == 0
                    && warning == r.warning;
        }

        @Override
        public int hashCode() {
            return Objects.hash(error, djm0, djm, warning);
        }

        @Override
        public String toString() {
            return "Cal2jd.Result(" + error + ", " + djm0 + ", " + djm + ", " + warning + ")";
        }
    }

    /** Gregorian calendar date to a two-part Julian Date {@code (2400000.5, MJD)}. */
    public static Result cal2jd(int iy, int im, int id) {
        // Earliest year allowed (4800BC).
        final int iymin = -4799;

        // Month lengths in days.
        final int[] mtab = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

        // Validate year and month.
        if (iy < iymin) {
            return new Result(Cal2jdError.BAD_YEAR, 0.0, 0.0, null);
        }
        if (im < 1 || im > 12) {
            return new Result(Cal2jdError.BAD_MONTH, 0.0, 0.0, null);
        }

        // If February in a leap year, 1, otherwise 0.
        int ly = im == 2 && iy % 4 == 0 && (iy % 100 != 0 || iy % 400 == 0) ? 1 : 0;

        // Validate day, taking into account leap years.
        Cal2jdWarning warning = id < 1 || id > mtab[im - 1] + ly ? Cal2jdWarning.BAD_DAY : null;

        // Return result. iy is at least IYMIN and im is 1 to 12, so none of the int arithmetic
        // overflows.
        int my = (im - 14) / 12;
        long iypmy = (long) iy + my;
        double djm = (1461 * (iypmy + 4800)) / 4
                + (367 * (long) (im - 2 - 12 * my)) / 12
                - (3 * ((iypmy + 4900) / 100)) / 4
                + id
                - 2432076;
        return new Result(null, Erfa.ERFA_DJM0, djm, warning);
    }
}
