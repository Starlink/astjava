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
 * The constants of ERFA's {@code erfam.h} and its rounding macros, shared by the ERFA ports in this
 * package.
 *
 * <p>The package holds one class per ERFA function AST uses. Each keeps ERFA's operations in ERFA's
 * order, so callers get ERFA's results to within the rounding of the elementary functions, which
 * come from {@link Math}. Vectors are {@code double[3]}, matrices {@code double[3][3]} by rows, and
 * pv-vectors {@code double[2][3]} (position, then velocity); no function changes its arguments, and
 * each returns new arrays.
 *
 * <p>Ported from Rust {@code erfa/mod.rs}.
 */
public final class Erfa {

    private Erfa() {}

    // MODERN-JAVA(9): the Rust port's vectorisable kernels (KernelTrig, VmathTrig and the kernels
    // of c2s, s2c, fk45z, fk524 and fk54z) need fused multiply-add (Math.fma) and, to pay, the
    // Vector API; this port computes every point with the per-value functions.

    /** Pi. */
    public static final double ERFA_DPI = 3.141592653589793;

    /** 2 pi. */
    public static final double ERFA_D2PI = 6.283185307179586;

    /** Radians to degrees. */
    public static final double ERFA_DR2D = 57.29577951308232;

    /** Degrees to radians. */
    public static final double ERFA_DD2R = 0.017453292519943295;

    /** Radians to arcseconds. */
    public static final double ERFA_DR2AS = 206264.80624709636;

    /** Arcseconds to radians. */
    public static final double ERFA_DAS2R = 4.84813681109536E-6;

    /** Seconds of time to radians. */
    public static final double ERFA_DS2R = 7.27220521664304E-5;

    /** Arcseconds in a full circle. */
    public static final double ERFA_TURNAS = 1296000.0;

    /** Milliarcseconds to radians. */
    public static final double ERFA_DMAS2R = ERFA_DAS2R / 1e3;

    /** Length of tropical year B1900 (days). */
    public static final double ERFA_DTY = 365.242198781;

    /** Seconds per day. */
    public static final double ERFA_DAYSEC = 86400.0;

    /** Days per Julian year. */
    public static final double ERFA_DJY = 365.25;

    /** Days per Julian century. */
    public static final double ERFA_DJC = 36525.0;

    /** Days per Julian millennium. */
    public static final double ERFA_DJM = 365250.0;

    /** Reference epoch (J2000.0), Julian Date. */
    public static final double ERFA_DJ00 = 2451545.0;

    /** Julian Date of Modified Julian Date zero. */
    public static final double ERFA_DJM0 = 2400000.5;

    /** Reference epoch (J2000.0), Modified Julian Date. */
    public static final double ERFA_DJM00 = 51544.5;

    /** 1977 Jan 1.0 as MJD. */
    public static final double ERFA_DJM77 = 43144.0;

    /** TT minus TAI (s). */
    public static final double ERFA_TTMTAI = 32.184;

    /** Astronomical unit (m, IAU 2012). */
    public static final double ERFA_DAU = 149597870.7e3;

    /** Speed of light (m/s). */
    public static final double ERFA_CMPS = 299792458.0;

    /** Light time for 1 au (s). */
    public static final double ERFA_AULT = ERFA_DAU / ERFA_CMPS;

    /** Speed of light (au per day). */
    public static final double ERFA_DC = ERFA_DAYSEC / ERFA_AULT;

    /** L_G = 1 - d(TT)/d(TCG). */
    public static final double ERFA_ELG = 6.969290134e-10;

    /** L_B = 1 - d(TDB)/d(TCB). */
    public static final double ERFA_ELB = 1.550519768e-8;

    /** TDB (s) at TAI 1977/1/1.0. */
    public static final double ERFA_TDB0 = -6.55e-5;

    /** Schwarzschild radius of the Sun (au). */
    public static final double ERFA_SRS = 1.97412574336e-8;

    /** Truncates towards zero: {@code ERFA_DINT}. */
    public static double dint(double a) {
        return a < 0.0 ? Math.ceil(a) : Math.floor(a);
    }

    /** Rounds to the nearest whole number, halves away from zero: {@code ERFA_DNINT}. */
    public static double dnint(double a) {
        if (Math.abs(a) < 0.5) {
            return 0.0;
        } else if (a < 0.0) {
            return Math.ceil(a - 0.5);
        } else {
            return Math.floor(a + 0.5);
        }
    }

    /**
     * The magnitude of {@code a} with the sign of {@code b}: {@code ERFA_DSIGN}; a zero {@code b} of
     * either sign gives a positive result.
     */
    public static double dsign(double a, double b) {
        return b < 0.0 ? -Math.abs(a) : Math.abs(a);
    }
}
