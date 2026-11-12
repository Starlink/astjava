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
 * Julian Date to Gregorian calendar date: {@code eraJd2cal}.
 *
 * <p>Ported from Rust {@code erfa/jd2cal.rs}, itself ported from ERFA's {@code eraJd2cal} ({@code
 * erfa/jd2cal.c}, ERFA 2.0.1).
 */
public final class Jd2cal {

    private Jd2cal() {}

    /** A Gregorian calendar date and fraction of a day, the result of {@link #jd2cal}. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** Year. */
        public final int iy;

        /** Month. */
        public final int im;

        /** Day. */
        public final int id;

        /** Fraction of the day. */
        public final double fd;

        /** A result holding the given values. */
        public Result(int iy, int im, int id, double fd) {
            this.iy = iy;
            this.im = im;
            this.id = id;
            this.fd = fd;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return iy == r.iy && im == r.im && id == r.id && Double.compare(fd, r.fd) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(iy, im, id, fd);
        }

        @Override
        public String toString() {
            return "Jd2cal.Result(" + iy + ", " + im + ", " + id + ", " + fd + ")";
        }
    }

    /**
     * Two-part Julian Date to Gregorian year, month, day and fraction of a day; null when the date
     * is outside -68569.5 to 1e9 (ERFA status -1).
     *
     * <p>A NaN date passes the range check, as in ERFA, and gives the calendar date of day number 0
     * and a NaN fraction. That is this port's defined result: C casts the NaN to {@code long}, so
     * its result there depends on the platform. ERFA does its integer arithmetic in {@code long}. A
     * two-part date whose parts are too large for a {@code long} and cancel is likewise
     * platform-dependent in C; here the casts saturate and the arithmetic wraps.
     */
    public static Result jd2cal(double dj1, double dj2) {
        // Minimum and maximum allowed JD.
        final double djmin = -68569.5;
        final double djmax = 1e9;

        // C's DBL_EPSILON.
        final double dblEpsilon = Math.ulp(1.0);

        // Verify date is acceptable.
        double dj = dj1 + dj2;
        if (dj < djmin || dj > djmax) {
            return null;
        }

        // Separate day and fraction (where -0.5 <= fraction < 0.5). The casts from double to long
        // saturate, with NaN as 0; C's casts of a NaN or out-of-range value are undefined.
        double d = Erfa.dnint(dj1);
        double f1 = dj1 - d;
        long jd = (long) d;
        d = Erfa.dnint(dj2);
        double f2 = dj2 - d;
        jd += (long) d;

        // Compute f1+f2+0.5 using compensated summation (Klein 2006).
        double s = 0.5;
        double cs = 0.0;
        double[] v = {f1, f2};
        for (double x : v) {
            double t = s + x;
            cs += Math.abs(s) >= Math.abs(x) ? (s - t) + x : (x - t) + s;
            s = t;
            if (s >= 1.0) {
                jd++;
                s -= 1.0;
            }
        }
        double f = s + cs;
        cs = f - s;

        // Deal with negative f.
        if (f < 0.0) {
            // Compensated summation: assume that |s| <= 1.0.
            f = s + 1.0;
            cs += (1.0 - f) + s;
            s = f;
            f = s + cs;
            cs = f - s;
            jd--;
        }

        // Deal with f that is 1.0 or more (when rounded to double).
        if ((f - 1.0) >= -dblEpsilon / 4.0) {
            // Compensated summation: assume that |s| <= 1.0.
            double t = s - 1.0;
            cs += (s - t) - 1.0;
            s = t;
            f = s + cs;
            if (-dblEpsilon / 2.0 < f) {
                jd++;
                // ERFA_GMAX(f, 0.0).
                f = f > 0.0 ? f : 0.0;
            }
        }

        // Express day in Gregorian calendar.
        long l = jd + 68569;
        long n = (4 * l) / 146097;
        l -= (146097 * n + 3) / 4;
        long i = (4000 * (l + 1)) / 1461001;
        l -= (1461 * i) / 4 - 31;
        long k = (80 * l) / 2447;
        int id = (int) (l - (2447 * k) / 80);
        l = k / 11;
        int im = (int) (k + 2 - 12 * l);
        int iy = (int) (100 * (n - 49) + i + l);
        return new Result(iy, im, id, f);
    }
}
