package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the {@code date_str_to_mjd_*} unit tests of Rust {@code fitschan/wcs_write/mod.rs}:
 * the MJD of a FITS DATE-OBS string (fitschan.c {@code DateObs}).
 *
 * <p>The two round-trip tests need {@code mjd_to_date_str}, the write side, and are deferred. The
 * tests after them check the strings C's {@code sscanf} patterns accept or reject
 * (fitschan.c:8662-8710), which the Rust parser treats differently.
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
}
