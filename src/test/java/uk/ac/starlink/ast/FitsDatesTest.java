package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the {@code date_str_to_mjd_*} and {@code mjd_to_date_str_*} unit tests of Rust
 * {@code fitschan/wcs_write/mod.rs}: the MJD of a FITS DATE-OBS string (fitschan.c {@code
 * DateObs}), and the DATE-OBS string C's {@code WcsFromStore} writes for an MJD.
 *
 * <p>The tests after them check the strings C's {@code sscanf} patterns accept or reject
 * (fitschan.c:8662-8710), which the Rust parser treats differently, and the DATE-OBS C writes
 * where its calendar routines round.
 */
class FitsDatesTest {

    @Test
    void dateStrToMjdIsoModern() {
        double mjd = FitsDates.dateStrToMjd("2000-01-01T12:00:00.000");
        assertTrue(Math.abs(mjd - 51544.5) < 1e-6);
    }

    /**
     * The MJD must be bit-identical to C's {@code DateObs}, which composes it as {@code palCaldj}
     * (whole days) + {@code palDtf2d} (day fraction) and builds the seconds as {@code fsc / 10^width
     * + sc}. These are the DATE-OBS strings from the {@code azel}, {@code date-frac} and {@code
     * serpens} wcsconv fixtures; the expected values were taken from the C library on the same
     * inputs.
     */
    @Test
    void dateStrToMjdMatchesCBitForBit() {
        String[] text = {"2017-07-21T08:38:39.087", "2020-06-15T10:30:00.123Z", "2007-04-30T15:13:53.717"};
        double[] want = {57955.36017461806, 59015.437501423614, 54220.63464950232};
        for (int k = 0; k < text.length; k++) {
            Double got = FitsDates.dateStrToMjd(text[k]);
            assertEquals(
                    Double.doubleToLongBits(want[k]),
                    Double.doubleToLongBits(got),
                    text[k] + ": got " + got + ", want " + want[k]);
        }
    }

    /** A fractional-seconds field keeps its leading zeros: ".087" is 87 milliseconds, not 870. */
    @Test
    void dateStrToMjdFractionWidthIsSignificant() {
        // Differencing against the day number quantises the residue to a few times 1e-7 s, so this
        // checks the scale, not the last bits.
        double a = (FitsDates.dateStrToMjd("2000-01-01T00:00:00.087") - 51544.0) * 86400.0;
        double b = (FitsDates.dateStrToMjd("2000-01-01T00:00:00.87") - 51544.0) * 86400.0;
        assertTrue(Math.abs(a - 0.087) < 1e-6, Double.toString(a));
        assertTrue(Math.abs(b - 0.87) < 1e-6, Double.toString(b));
    }

    @Test
    void dateStrToMjdIsoNoTime() {
        double mjd = FitsDates.dateStrToMjd("2000-01-01");
        assertTrue(Math.abs(mjd - 51544.0) < 1e-6);
    }

    @Test
    void dateStrToMjdIsoWithTrailingZ() {
        double mjd = FitsDates.dateStrToMjd("2000-01-01T12:00:00.000Z");
        assertTrue(Math.abs(mjd - 51544.5) < 1e-6);
    }

    @Test
    void dateStrToMjdLegacy() {
        // 24/05/68 is 1968-05-24, MJD 40000.
        double mjd = FitsDates.dateStrToMjd("24/05/68");
        assertTrue(Math.abs(mjd - 40000.0) < 1e-6);
    }

    /**
     * The year of the old form is scanned with an unbounded {@code %d} (fitschan.c:8662), and
     * {@code palCaldj} takes a year outside 0 to 99 as it is, so a four-digit year is accepted.
     */
    @Test
    void legacyFormAcceptsAFullYear() {
        assertEquals(FitsDates.dateStrToMjd("24/05/68"), FitsDates.dateStrToMjd("24/05/1968"));
    }

    /**
     * {@code %2d} and {@code %4d} skip white space and read at most two or four characters, so
     * single-digit fields and surrounding blanks are accepted (fitschan.c:8672) but a five-digit
     * year is not.
     */
    @Test
    void fieldWidthsFollowTheScanPatterns() {
        assertEquals(FitsDates.dateStrToMjd("2000-01-05"), FitsDates.dateStrToMjd(" 2000-1-5 "));
        assertNull(FitsDates.dateStrToMjd("20000-01-01"));
        assertNull(FitsDates.dateStrToMjd("2000-01-01T"));
    }

    /**
     * A decimal point must be followed by the {@code %d} of the fractional seconds
     * (fitschan.c:8691); a trailing point matches none of the patterns, and only one {@code Z} may
     * follow (fitschan.c:8699, 8707).
     */
    @Test
    void aBareDecimalPointOrDoubledZIsRejected() {
        assertNull(FitsDates.dateStrToMjd("2000-01-01T12:00:00."));
        assertNull(FitsDates.dateStrToMjd("2000-01-01T12:00:00ZZ"));
    }

    /** {@code DateObs} returns AST__BAD when {@code palCaldj} or {@code palDtf2d} fails (fitschan.c:8717-8745). */
    @Test
    void anInvalidDateOrTimeGivesNull() {
        assertNull(FitsDates.dateStrToMjd("2000-02-30"));
        assertNull(FitsDates.dateStrToMjd("2000-13-01"));
        assertNull(FitsDates.dateStrToMjd("2000-01-01T24:00:00"));
        assertNull(FitsDates.dateStrToMjd("not a date"));
    }

    @Test
    void mjdToDateStrModernIso() {
        assertEquals("2000-01-01T12:00:00.000", FitsDates.mjdToDateStr(51544.5));
    }

    @Test
    void mjdToDateStrModernWithMsPrecision() {
        String s = FitsDates.mjdToDateStr(52414.59);
        assertTrue(s.startsWith("2002-05-20T"), s);
        assertEquals("14:09", s.substring(11, 16));
    }

    @Test
    void mjdToDateStrLegacyFormat() {
        // Before 1999 the old form dd/mm/yy: MJD 40000 is 1968-05-24.
        assertEquals("24/05/68", FitsDates.mjdToDateStr(40000.0));
    }

    @Test
    void mjdToDateStrCutoffBoundary() {
        // MJD 51179 is 1999-01-01, palCaldj( 99, 1, 1 ).
        assertTrue(FitsDates.mjdToDateStr(51179.0).startsWith("1999-01-01T"));
        assertEquals("31/12/98", FitsDates.mjdToDateStr(51178.0));
    }

    @Test
    void dateStrToMjdRoundtripModern() {
        double original = 52414.59;
        double parsed = FitsDates.dateStrToMjd(FitsDates.mjdToDateStr(original));
        assertTrue(Math.abs(parsed - original) < 1e-3);
    }

    @Test
    void dateStrToMjdRoundtripLegacy() {
        double original = 40000.0;
        double parsed = FitsDates.dateStrToMjd(FitsDates.mjdToDateStr(original));
        assertTrue(Math.abs(parsed - original) < 1e-6);
    }

    /**
     * Values from a C probe of {@code WcsFromStore}'s formatting. The old form is not rounded: {@code palDjcal} with no decimal places gives the day the MJD
     * falls in. The new form rounds the time of day to milliseconds with {@code palDd2tf}, which
     * can give 24 hours on the day the MJD falls in (fitschan.c {@code WcsFromStore}).
     */
    @Test
    void mjdToDateStrRoundsOnlyTheTimeOfDay() {
        assertEquals("31/12/98", FitsDates.mjdToDateStr(51178.9999999));
        assertEquals("2009-06-18T24:00:00.000", FitsDates.mjdToDateStr(55000.99999999999));
        assertEquals("2009-06-18T23:59:59.999", FitsDates.mjdToDateStr(55000.0 + 86399.999 / 86400.0));
    }

    /** Before 1900 the year less 1900 is negative, written with C's {@code %2.2d}. */
    @Test
    void mjdToDateStrBefore1900() {
        // MJD 0 is 1858-11-17.
        assertEquals("17/11/-42", FitsDates.mjdToDateStr(0.0));
        // MJD 15020 is 1900-01-01; the day before is 1899-12-31.
        assertEquals("31/12/-01", FitsDates.mjdToDateStr(15019.0));
    }
}
