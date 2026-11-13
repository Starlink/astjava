package uk.ac.starlink.ast.internal.erfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The unit tests of Rust {@code src/erfa/}: {@code mod.rs}, {@code eform.rs}, {@code gc2gd.rs},
 * {@code tf2d.rs}, {@code d2tf.rs}, {@code jd2cal.rs} and {@code dat.rs}. The tests of the
 * vectorised kernels in {@code c2s.rs}, {@code s2c.rs}, {@code fk45z.rs} and {@code fk54z.rs} are
 * deferred as hardware SIMD.
 */
class ErfaTest {

    private static long bits(double v) {
        return Double.doubleToRawLongBits(v);
    }

    /** {@code dint}, {@code dnint} and {@code dsign} against {@code erfam.h}'s macros. */
    @Test
    void roundingMacrosMatchErfamH() {
        double[] values = {
            0.0,
            -0.0,
            0.5,
            -0.5,
            0.49999999999999994,
            -0.49999999999999994,
            1.5,
            -1.5,
            2.5,
            -2.5,
            1e15 + 0.5,
            // 4503599627370497.5 rounded to a double.
            4.503599627370498E15,
            1e300,
            -1e300,
            Double.MAX_VALUE,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        };
        for (double a : values) {
            // ERFA_DINT: ((A)<0.0?ceil(A):floor(A))
            double dintC = a < 0.0 ? Math.ceil(a) : Math.floor(a);
            // ERFA_DNINT: (fabs(A)<0.5?0.0:(A)<0.0?ceil((A)-0.5):floor((A)+0.5))
            double dnintC;
            if (Math.abs(a) < 0.5) {
                dnintC = 0.0;
            } else if (a < 0.0) {
                dnintC = Math.ceil(a - 0.5);
            } else {
                dnintC = Math.floor(a + 0.5);
            }
            assertEquals(bits(dintC), bits(Erfa.dint(a)), "dint " + a);
            assertEquals(bits(dnintC), bits(Erfa.dnint(a)), "dnint " + a);
            for (double b : values) {
                // ERFA_DSIGN: ((B)<0.0?-fabs(A):fabs(A))
                double c = b < 0.0 ? -Math.abs(a) : Math.abs(a);
                assertEquals(bits(c), bits(Erfa.dsign(a, b)), "dsign " + a + " " + b);
            }
        }
        assertEquals(-2.0, Erfa.dint(-2.7));
        assertEquals(2.0, Erfa.dint(2.7));
        assertEquals(3.0, Erfa.dnint(2.5));
        assertEquals(-3.0, Erfa.dnint(-2.5));
        assertEquals(bits(0.0), bits(Erfa.dnint(-0.4)));
        // The sum a + 0.5 rounds up for the largest double below 0.5, so a value just under a
        // half-integer boundary shows the macro's form.
        assertEquals(0.0, Erfa.dnint(0.49999999999999994));
        assertEquals(1.0, Erfa.dnint(1.4999999999999998));
        assertEquals(3.0, Erfa.dsign(3.0, -0.0));
        assertEquals(-3.0, Erfa.dsign(-3.0, -1.0));
    }

    @Test
    void eformGivesThePublishedParameters() {
        assertArrayEquals(new double[] {6378137.0, 1.0 / 298.257223563}, Eform.eform(Eform.Ellipsoid.WGS84));
        assertArrayEquals(new double[] {6378137.0, 1.0 / 298.257222101}, Eform.eform(Eform.Ellipsoid.GRS80));
        assertArrayEquals(new double[] {6378135.0, 1.0 / 298.26}, Eform.eform(Eform.Ellipsoid.WGS72));
    }

    @Test
    void gc2gdInvertsGd2gcToRounding() {
        Eform.Ellipsoid e = Eform.Ellipsoid.WGS84;
        double[] xyz = Gd2gc.gd2gc(e, 0.3, 0.7, 4205.0);
        assertNotNull(xyz);
        Gc2gde.Result r = Gc2gd.gc2gd(e, xyz);
        assertNull(r.error);
        assertTrue(
                Math.abs(r.elong - 0.3) < 1e-12 && Math.abs(r.phi - 0.7) < 1e-12 && Math.abs(r.height - 4205.0) < 1e-6);
    }

    @Test
    void tf2dTakesTheMagnitudeOfTheMostNegativeIntAs2Pow31() {
        Tf2d.Result r = Tf2d.tf2d(' ', Integer.MIN_VALUE, 0, 0.0);
        assertEquals(60.0 * 60.0 * 2147483648.0 / 86400.0, r.days);
        assertEquals(Tf2d.Tf2dWarning.HOUR, r.warning);
        r = Tf2d.tf2d('-', 0, Integer.MIN_VALUE, 0.0);
        assertEquals(-(60.0 * 2147483648.0) / 86400.0, r.days);
        assertEquals(Tf2d.Tf2dWarning.MINUTE, r.warning);
    }

    @Test
    void d2tfDefinedResultsWhereCIsUndefined() {
        assertEquals(new D2tf.Result('-', new int[] {0, 0, 0, 0}), D2tf.d2tf(3, Double.NaN));
        assertEquals('+', D2tf.d2tf(0, Double.POSITIVE_INFINITY).sign);
        assertEquals(Integer.MAX_VALUE, D2tf.d2tf(0, 1e15).ihmsf[0]);
    }

    @Test
    void d2tfKnownValues() {
        assertEquals(new D2tf.Result('+', new int[] {12, 0, 0, 0}), D2tf.d2tf(3, 0.5));
        assertEquals(new D2tf.Result('-', new int[] {6, 0, 0, 0}), D2tf.d2tf(0, -0.25));
        assertEquals(new D2tf.Result('+', new int[] {0, 1, 1, 2}), D2tf.d2tf(1, 1.0 / 86400.0 * 61.25));
    }

    /**
     * Dates on which C's result depends on the platform: a NaN sum reaches its {@code (long)}
     * casts, and so does a part too large for a {@code long} whose sum is in range. The port
     * returns, with a NaN fraction for a NaN date.
     */
    @Test
    void jd2calReturnsWhereCIsUndefined() {
        double[][] dates = {
            {Double.NaN, 0.0},
            {0.0, Double.NaN},
            {2400000.5, Double.NaN},
            {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}
        };
        for (double[] d : dates) {
            Jd2cal.Result r = Jd2cal.jd2cal(d[0], d[1]);
            assertNotNull(r);
            assertTrue(Double.isNaN(r.fd), d[0] + " " + d[1]);
        }
        Jd2cal.jd2cal(1e19, -1e19);
    }

    @Test
    void jd2calKnownDates() {
        assertEquals(2000, Jd2cal.jd2cal(2400000.5, 51544.0).iy);
        assertEquals(new Jd2cal.Result(2000, 1, 1, 0.5), Jd2cal.jd2cal(2400000.5, 51544.5));
        assertNull(Jd2cal.jd2cal(2400000.5, -2400000.5 - 68570.0));
        assertNull(Jd2cal.jd2cal(0.0, 1.0e9 + 1.0));
        // A NaN date is accepted, with a NaN fraction, on the date of day number 0.
        Jd2cal.Result r = Jd2cal.jd2cal(Double.NaN, 0.0);
        assertEquals(-4713, r.iy);
        assertEquals(11, r.im);
        assertEquals(24, r.id);
        assertTrue(Double.isNaN(r.fd));
    }

    /**
     * Years for which C's {@code 12 * iy + im} overflows a signed {@code int}, which is undefined:
     * only that the port returns, with the wrapped month number below every table entry giving
     * UNDERFLOW.
     */
    @Test
    void datDefinedResultsWhereCOverflows() {
        // 12 * 178_956_970 is 2_147_483_640, so only months from 8 overflow.
        for (int im : new int[] {8, 12}) {
            assertEquals(Dat.DatError.UNDERFLOW, Dat.dat(178_956_970, im, 1, 0.5).error);
        }
        assertNull(Dat.dat(178_956_970, 7, 1, 0.5).error);
        for (int iy : new int[] {178_956_971, 357_913_941, Integer.MAX_VALUE}) {
            for (int im : new int[] {1, 6, 12}) {
                assertEquals(Dat.DatError.UNDERFLOW, Dat.dat(iy, im, 1, 0.5).error, String.valueOf(iy));
            }
        }
    }

    @Test
    void leapSecondsAreTheFirstDaysOfTheTableMonths() {
        assertEquals(Dat.CHANGES.length, Dat.LEAP_SECONDS.size());
        for (int i = 0; i < Dat.CHANGES.length; i++) {
            Dat.Change c = Dat.CHANGES[i];
            double mjd = Cal2jd.cal2jd(c.iy, c.im, 1).djm;
            assertArrayEquals(new double[] {mjd, c.delat}, Dat.LEAP_SECONDS.get(i));
        }
    }

    @Test
    void datKnownValues() {
        assertEquals(new Dat.Result(null, 37.0, null), Dat.dat(2017, 1, 1, 0.0));
        assertEquals(new Dat.Result(null, 36.0, null), Dat.dat(2016, 12, 31, 0.5));
        assertEquals(new Dat.Result(null, 0.0, Dat.DatWarning.BEFORE_TABLE), Dat.dat(1959, 12, 31, 0.0));
        assertEquals(Dat.DatError.BAD_FRACTION, Dat.dat(2000, 1, 1, 1.5).error);
        assertEquals(Dat.DatError.BAD_DAY, Dat.dat(2000, 2, 30, 0.0).error);
    }
}
