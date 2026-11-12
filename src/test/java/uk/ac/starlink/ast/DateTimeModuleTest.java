package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The unit tests of Rust {@code src/frames/date_time.rs}: reading a date and time as C's {@code
 * astReadDateTime} (frame.c) does. The expected Modified Julian Dates are the values C gives. A
 * string C does not accept is an {@code AST__DTERR} error, where Rust returns no value.
 */
class DateTimeModuleTest {

    private static void assertMjd(double a, double b) {
        assertTrue(Math.abs(a - b) < 1.0e-9, "expected " + b + ", got " + a);
    }

    private static void assertRejected(String text) {
        AstException e = assertThrows(AstException.class, () -> DateTime.readDateTime(text), text);
        assertEquals(AstException.AST__DTERR, e.getStatus());
    }

    @Test
    void besselianRoundTrip() {
        double mjd = DateTime.yearToMjd(1950.0);
        assertTrue(Math.abs(Pal.epb(mjd) - 1950.0) < 1.0e-9);
    }

    @Test
    void julianRoundTrip() {
        double mjd = DateTime.yearToMjd(2000.0);
        assertTrue(Math.abs(Pal.epj(mjd) - 2000.0) < 1.0e-9);
    }

    @Test
    void thresholdIsInclusiveForJulian() {
        assertEquals(Pal.epj2d(1984.0), DateTime.yearToMjd(1984.0));
        assertEquals(Pal.epb2d(1983.999), DateTime.yearToMjd(1983.999));
    }

    @Test
    void besselianEpoch() {
        assertMjd(DateTime.readDateTime("B1950.0"), 33_281.923_459_05);
    }

    @Test
    void julianEpoch() {
        assertMjd(DateTime.readDateTime("J2000.0"), 51544.5);
    }

    @Test
    void decimalYearBesselianAndJulian() {
        assertMjd(DateTime.readDateTime("1976.2"), Pal.epb2d(1976.2));
        assertMjd(DateTime.readDateTime("1984.0"), Pal.epj2d(1984.0));
    }

    @Test
    void mjdAndJd() {
        assertMjd(DateTime.readDateTime("MJD 54321.0"), 54321.0);
        assertMjd(DateTime.readDateTime("JD 2454321.5"), 54321.0);
    }

    @Test
    void gregorianNumericAndNamed() {
        assertMjd(DateTime.readDateTime("1996-10-2"), 50358.0);
        assertMjd(DateTime.readDateTime("1996-Oct-2"), 50358.0);
        assertMjd(DateTime.readDateTime("1996/10/2"), 50358.0);
        assertMjd(DateTime.readDateTime("1996 10 2"), 50358.0);
    }

    @Test
    void gregorianDecimalDay() {
        assertMjd(DateTime.readDateTime("1996-Oct-2.5001"), 50_358.500_1);
    }

    @Test
    void gregorianWithTime() {
        assertMjd(DateTime.readDateTime("1996-Oct-2 12:14:30.52"), 50_358.510_075_462_96);
        assertMjd(DateTime.readDateTime("2005-jun-1 12:30"), 53522.520833333336);
        assertMjd(DateTime.readDateTime("1977-01-01 00:00:00"), 43144.0);
        assertMjd(DateTime.readDateTime("2004-12-31 18:00:10.12"), 53_370.750_117_129_63);
    }

    @Test
    void iso8601TDelimiter() {
        assertMjd(DateTime.readDateTime("1996-10-2T12:14:30.52"), 50_358.510_075_462_96);
    }

    @Test
    void trailingTextRejected() {
        assertRejected("B2001.5 lunch time");
        assertRejected("2005-jun-1 12:30 lunch time");
    }

    @Test
    void trailingWhitespaceAllowed() {
        assertMjd(DateTime.readDateTime("B2001.5 "), 52091.896696271506);
    }

    @Test
    void separatorMismatchRejected() {
        assertRejected("1996-10/2");
    }

    @Test
    void invalidMonthNameRejected() {
        assertRejected("1996-Xyz-2");
    }

    @Test
    void garbageRejected() {
        assertRejected("not a date");
        assertRejected("");
    }
}
