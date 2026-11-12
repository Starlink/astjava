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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * TAI minus UTC, and the leap-second table: {@code eraDat}.
 *
 * <p>The table is the final one: it ends at the 2017 January 1 leap second, after which none has
 * been added, and a leap-second table in a later ERFA release is a new port, not a run-time
 * setting. The C library can have its table replaced at run time and carries a release year past
 * which it warns of a possibly stale table; neither is reproduced, since no caller changes the
 * table and the warning is ignored.
 *
 * <p>Ported from Rust {@code erfa/dat.rs}, itself ported from ERFA's {@code eraDat} ({@code
 * erfa/dat.c}, ERFA 2.0.1).
 */
public final class Dat {

    private Dat() {}

    /** Why {@link #dat} gave no result. */
    public enum DatError {
        /** Year earlier than -4799 (ERFA status -1). */
        BAD_YEAR,
        /** Month outside 1 to 12 (ERFA status -2). */
        BAD_MONTH,
        /** The day is not a day of the month (ERFA status -3). */
        BAD_DAY,
        /** The fraction of the day is outside 0 to 1 (ERFA status -4). */
        BAD_FRACTION,
        /**
         * No table entry precedes the date (ERFA status -5); only a year so large that C's {@code
         * int} arithmetic on it overflows gets here.
         */
        UNDERFLOW
    }

    /** The warning that comes with {@link #dat}'s result. */
    public enum DatWarning {
        /** The date is before the first table entry, 1960 (ERFA status 1). The value is 0. */
        BEFORE_TABLE
    }

    /** The result of {@link #dat}: an error, or TAI minus UTC and a warning. */
    // MODERN-JAVA(16): record
    public static final class Result {
        /** Why there is no value, or null. */
        public final DatError error;

        /** TAI minus UTC in seconds; zero with an error. */
        public final double delat;

        /** The warning, or null. */
        public final DatWarning warning;

        /** A result holding the given values. */
        public Result(DatError error, double delat, DatWarning warning) {
            this.error = error;
            this.delat = delat;
            this.warning = warning;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result r = (Result) o;
            return error == r.error && Double.compare(delat, r.delat) == 0 && warning == r.warning;
        }

        @Override
        public int hashCode() {
            return Objects.hash(error, delat, warning);
        }

        @Override
        public String toString() {
            return "Dat.Result(" + error + ", " + delat + ", " + warning + ")";
        }
    }

    /** One change of TAI minus UTC: the year and month it takes effect and the new value. */
    // MODERN-JAVA(16): record
    static final class Change {
        final int iy;
        final int im;
        final double delat;

        Change(int iy, int im, double delat) {
            this.iy = iy;
            this.im = im;
            this.delat = delat;
        }
    }

    /** Number of Delta(AT) expressions before leap seconds were introduced. */
    public static final int NERA1 = 14;

    /**
     * Reference dates (MJD) and drift rates (s/day), pre leap seconds: one {@code {date, rate}} for
     * each of the first {@link #NERA1} changes.
     */
    public static final List<double[]> DRIFT = Collections.unmodifiableList(Arrays.asList(
            new double[] {37300.0, 0.0012960},
            new double[] {37300.0, 0.0012960},
            new double[] {37300.0, 0.0012960},
            new double[] {37665.0, 0.0011232},
            new double[] {37665.0, 0.0011232},
            new double[] {38761.0, 0.0012960},
            new double[] {38761.0, 0.0012960},
            new double[] {38761.0, 0.0012960},
            new double[] {38761.0, 0.0012960},
            new double[] {38761.0, 0.0012960},
            new double[] {38761.0, 0.0012960},
            new double[] {38761.0, 0.0012960},
            new double[] {39126.0, 0.0025920},
            new double[] {39126.0, 0.0025920}));

    /** Year, month and Delta(AT) of each change. */
    static final Change[] CHANGES = {
        new Change(1960, 1, 1.4178180),
        new Change(1961, 1, 1.4228180),
        new Change(1961, 8, 1.3728180),
        new Change(1962, 1, 1.8458580),
        new Change(1963, 11, 1.9458580),
        new Change(1964, 1, 3.2401300),
        new Change(1964, 4, 3.3401300),
        new Change(1964, 9, 3.4401300),
        new Change(1965, 1, 3.5401300),
        new Change(1965, 3, 3.6401300),
        new Change(1965, 7, 3.7401300),
        new Change(1965, 9, 3.8401300),
        new Change(1966, 1, 4.3131700),
        new Change(1968, 2, 4.2131700),
        new Change(1972, 1, 10.0),
        new Change(1972, 7, 11.0),
        new Change(1973, 1, 12.0),
        new Change(1974, 1, 13.0),
        new Change(1975, 1, 14.0),
        new Change(1976, 1, 15.0),
        new Change(1977, 1, 16.0),
        new Change(1978, 1, 17.0),
        new Change(1979, 1, 18.0),
        new Change(1980, 1, 19.0),
        new Change(1981, 7, 20.0),
        new Change(1982, 7, 21.0),
        new Change(1983, 7, 22.0),
        new Change(1985, 7, 23.0),
        new Change(1988, 1, 24.0),
        new Change(1990, 1, 25.0),
        new Change(1991, 1, 26.0),
        new Change(1992, 7, 27.0),
        new Change(1993, 7, 28.0),
        new Change(1994, 7, 29.0),
        new Change(1996, 1, 30.0),
        new Change(1997, 7, 31.0),
        new Change(1999, 1, 32.0),
        new Change(2006, 1, 33.0),
        new Change(2009, 1, 34.0),
        new Change(2012, 7, 35.0),
        new Change(2015, 7, 36.0),
        new Change(2017, 1, 37.0)
    };

    /**
     * The leap-second table: for each change, {@code {mjd, delat}}, the UTC MJD of its first day
     * and the TAI minus UTC that applies from it (before 1972, the value at that instant, to which
     * {@link #DRIFT} adds a rate).
     */
    public static final List<double[]> LEAP_SECONDS = leapSeconds();

    private static List<double[]> leapSeconds() {
        List<double[]> t = new ArrayList<>(CHANGES.length);
        for (Change c : CHANGES) {
            t.add(new double[] {firstOfMonthMjd(c.iy, c.im), c.delat});
        }
        return Collections.unmodifiableList(t);
    }

    /** The MJD of the first day of a month, by {@code eraCal2jd}'s integer formula. */
    private static double firstOfMonthMjd(int iy, int im) {
        long my = (im - 14L) / 12;
        long iypmy = iy + my;
        return (1461 * (iypmy + 4800)) / 4
                + (367 * (im - 2 - 12 * my)) / 12
                - (3 * ((iypmy + 4900) / 100)) / 4
                + 1
                - 2432076;
    }

    /**
     * TAI minus UTC, in seconds, at a Gregorian date and fraction of a day. A date before 1960
     * gives 0 with {@link DatWarning#BEFORE_TABLE}. C also warns of a year later than 2028, which
     * is not reproduced.
     */
    public static Result dat(int iy, int im, int id, double fd) {
        // If invalid fraction of a day, give up.
        if (fd < 0.0 || fd > 1.0) {
            return new Result(DatError.BAD_FRACTION, 0.0, null);
        }

        // Convert the date into an MJD; an invalid year, month, or day gives up.
        Cal2jd.Result cal = Cal2jd.cal2jd(iy, im, id);
        if (cal.error == Cal2jd.Cal2jdError.BAD_YEAR) {
            return new Result(DatError.BAD_YEAR, 0.0, null);
        } else if (cal.error == Cal2jd.Cal2jdError.BAD_MONTH) {
            return new Result(DatError.BAD_MONTH, 0.0, null);
        } else if (cal.warning == Cal2jd.Cal2jdWarning.BAD_DAY) {
            return new Result(DatError.BAD_DAY, 0.0, null);
        }
        double djm = cal.djm;

        // If pre-UTC year, give up.
        if (iy < CHANGES[0].iy) {
            return new Result(null, 0.0, DatWarning.BEFORE_TABLE);
        }

        // Combine year and month to form a date-ordered integer (C's int arithmetic, which a year
        // this large overflows)...
        int m = 12 * iy + im;

        // ...and use it to find the preceding table entry. A year of 1960 or later reaches the
        // first entry unless m overflowed.
        int i = CHANGES.length;
        while (true) {
            if (i == 0) {
                return new Result(DatError.UNDERFLOW, 0.0, null);
            }
            i--;
            if (m >= 12 * CHANGES[i].iy + CHANGES[i].im) {
                break;
            }
        }

        // Get the Delta(AT).
        double da = CHANGES[i].delat;

        // If pre-1972, adjust for drift.
        if (i < NERA1) {
            double[] drift = DRIFT.get(i);
            da += (djm + fd - drift[0]) * drift[1];
        }
        return new Result(null, da, null);
    }
}
