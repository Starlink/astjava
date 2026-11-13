package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of Rust {@code tests/test_fitschan_c_inline.rs}: checks made inline in {@code main()}
 * of C's {@code ast_tester/testfitschan.c}, each named by C's error numbers and made with C's
 * values.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: the tests that write a FrameSet to a FitsChan
 * ({@code sip_header_does_not_write_as_fits_wcs}, {@code azel_writes_as_fits_wcs}, {@code
 * supergalactic_writes_slon}, {@code sky_systems_through_five_encodings} and {@code
 * date_and_observatory_write_back}), those that need a SpecFrame ({@code
 * alternate_axes_with_a_bad_set}, whose header has a VRAD axis, {@code non_linear_spectral_writes},
 * {@code spectral_systems_through_fits_wcs} and {@code log_spectral_axis_writes_freq_log}), and
 * {@code show_fits_succeeds} (jniast has no {@code showFits}).
 */
class FitsChanCInlineTest {

    /** The FITS-WCS header C puts into a FitsChan before its GetFits tests. */
    private static final String[] TAN_CARDS = {
        "CRPIX1  =                   45",
        "CRPIX2  =                   45",
        "CRVAL1  =                   45",
        "CRVAL2  =                 89.9",
        "MYNAME  =                     ",
        "CDELT1  =                -0.01",
        "CDELT2  =                 0.01",
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
    };

    private static void put(FitsChan fc, String... cards) {
        for (String card : cards) {
            fc.putFits(card, false);
        }
    }

    /** 7777-7778: GetFitsK with no keyword reads the card at the cursor. */
    @Test
    void getFitsKAtTheCursor() {
        FitsChan fc = new FitsChan();
        put(fc, TAN_CARDS);
        fc.setCard(2);
        assertEquals(45L, fc.getFitsK(null), "Error 7777-7778");
    }

    /** 115-117, 133-135: ForceTab and TabOK are set, read back and cleared. */
    @Test
    void forceTabAndTabOkSetAndClear() {
        FitsChan fc = new FitsChan();
        fc.set("ForceTab=1");
        assertTrue(fc.test("ForceTab"), "Error 115");
        assertEquals(1, fc.getI("ForceTab"), "Error 116");
        fc.clear("ForceTab");
        assertFalse(fc.test("ForceTab"), "Error 117");
        fc.set("TabOK=1");
        assertTrue(fc.test("TabOK"), "Error 133");
        assertEquals(1, fc.getI("TabOK"), "Error 134");
        fc.clear("TabOK");
        assertFalse(fc.test("TabOK"), "Error 135");
    }

    /** 804-805: a bad floating-point value reads back as the string {@code <bad>}. */
    @Test
    void badFloatReadsAsTheBadString() {
        FitsChan fc = new FitsChan();
        fc.setFits("BADFLT", CValues.BAD, "bad float", false);
        fc.clear("Card");
        assertEquals("<bad>", fc.getFitsS("BADFLT"), "Error 804-805");
    }

    /** 876, 880, 882: each Encoding reads back by name once set, and a set Encoding and Clean test as set. */
    @Test
    void encodingAndCleanSetAndTest() {
        FitsChan fc = new FitsChan();
        for (String name : new String[] {
            "FITS-WCS", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++", "FITS-CLASS", "DSS", "NATIVE"
        }) {
            fc.setEncoding(name);
            assertEquals(name, fc.getEncoding(), "Error 876");
        }
        assertTrue(fc.test("Encoding"), "Error 880");
        fc.set("Clean=1");
        assertTrue(fc.test("Clean"), "Error 882");
        fc.clear("Clean");
        fc.clear("Encoding");
        assertFalse(fc.test("Clean") || fc.test("Encoding"));
    }

    /**
     * 4-11: a read fails on a CRPIX2 that is a string, leaving the cards in place, except that with
     * Clean set it removes every card it used but the one RetainFits marked.
     */
    @Test
    void failedReadRetainsCards() {
        String[] cards = {
            "CRPIX1  = 45",
            "CRPIX2  = 'fred'",
            "CRVAL1  = 45",
            "CRVAL2  = 89.9",
            "CDELT1  = -0.01",
            "CDELT2  = 0.01",
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
        };
        boolean[] cleans = {false, true};
        int[][] errs = {{4, 6, 7}, {8, 10, 11}};
        for (int k = 0; k < 2; k++) {
            boolean clean = cleans[k];
            FitsChan fc = new FitsChan();
            fc.set("Clean=" + (clean ? 1 : 0));
            put(fc, cards);
            fc.clear("Card");
            if (fc.findFits("CTYPE1", false) != null) {
                fc.retainFits();
            }
            fc.clear("Card");
            assertThrows(AstException.class, fc::read, "Error " + errs[k][0] + "-" + (errs[k][0] + 1));
            fc.clear("Card");
            assertNotNull(fc.findFits("CTYPE1", false), "Error " + errs[k][1] + ": CTYPE1 has not been retained");
            fc.clear("Card");
            assertEquals(!clean, fc.findFits("CTYPE2", false) != null, "Error " + errs[k][2] + ": CTYPE2");
        }
    }

    /** 740-747: string values read as logicals. */
    @Test
    void stringsReadAsLogicals() {
        String[][] cases = {
            {"STRT", "T", "true"},
            {"STRY", "Y", "true"},
            {"STRF", "F", "false"},
            {"STRN", "N", "false"},
            {"STRDOTT", ".TRUE.", "true"},
            {"STRDOTF", ".FALSE.", "false"},
            {"STR1", "1", "true"},
            {"STR0", "0", "false"},
        };
        FitsChan fc = new FitsChan();
        for (String[] c : cases) {
            fc.setFits(c[0], c[1], "", false);
        }
        for (int i = 0; i < cases.length; i++) {
            fc.clear("Card");
            assertEquals(
                    Boolean.valueOf(cases[i][2]),
                    fc.getFitsL(cases[i][0]),
                    "Error " + (740 + i) + ": STRING '" + cases[i][1] + "' -> LOGICAL");
        }
    }

    /**
     * 808-809: PutFits overwriting a card of each value type with the same value, then an integer
     * too large for 32 bits with a different one. An overwrite replaces the card at the cursor,
     * which each Get leaves on the card it found.
     */
    @Test
    void putFitsOverwritesEachType() {
        FitsChan fc = new FitsChan();
        fc.putFits("KTEST   = 9999999999 / original", true);
        fc.clear("Card");
        fc.putFits("KTEST   = 9999999999 / same value", true);
        fc.clear("Card");
        assertEquals(9999999999L, fc.getFitsK("KTEST"), "Error 808: KINT");
        fc.putFits("CITEST  = 10 20 / original ci", true);
        fc.clear("Card");
        fc.putFits("CITEST  = 10 20 / same ci", true);
        fc.clear("Card");
        assertArrayEquals(new int[] {10, 20}, fc.getFitsCI("CITEST"), "Error 808: COMPLEXI");
        fc.putFits("CFTEST  = 1.5 2.5 / original cf", true);
        fc.clear("Card");
        fc.putFits("CFTEST  = 1.5 2.5 / same cf", true);
        fc.clear("Card");
        assertArrayEquals(new double[] {1.5, 2.5}, fc.getFitsCF("CFTEST"), "Error 808: COMPLEXF");
        fc.putFits("LTEST   = T / original logical", true);
        fc.clear("Card");
        fc.putFits("LTEST   = T / same logical", true);
        fc.clear("Card");
        assertEquals(true, fc.getFitsL("LTEST"), "Error 808: LOGICAL");
        fc.putFits("UTEST   =   / original undef", true);
        fc.clear("Card");
        fc.putFits("UTEST   =   / same undef", true);
        fc.putFits("CONTINUE  'a long continuation value'", true);
        fc.clear("Card");
        fc.putFits("CONTINUE  'a long continuation value'", true);
        fc.clear("Card");
        assertNotNull(fc.getFitsCN("CONTINUE"), "Error 808: CONTINUE");
        fc.putFits("COMMENT   This is a comment", true);
        fc.clear("Card");
        fc.putFits("COMMENT   This is a comment", true);
        fc.clear("Card");
        fc.putFits("KTEST   = 8888888888 / changed", true);
        fc.clear("Card");
        assertEquals(8888888888L, fc.getFitsK("KTEST"), "Error 809");
    }

    /** 810-821: every TIMESYS value reads, and TDB and TCB give an Epoch different from UTC's. */
    @Test
    void everyTimesysReads() {
        double epochUtc = 0.0;
        String[] systems = {"UTC", "UT", "IAT", "ET", "TT", "TDT", "TDB", "TCG", "TCB"};
        for (int its = 0; its < systems.length; its++) {
            String ts = systems[its];
            StringBuilder padded = new StringBuilder(ts);
            while (padded.length() < 8) {
                padded.append(' ');
            }
            FitsChan fc = FitsHeaders.chan(
                    "NAXIS1  = 100",
                    "NAXIS2  = 100",
                    "CTYPE1  = 'RA---TAN'",
                    "CTYPE2  = 'DEC--TAN'",
                    "CRVAL1  = 180.0",
                    "CRVAL2  = 45.0",
                    "CRPIX1  = 50.5",
                    "CRPIX2  = 50.5",
                    "CDELT1  = -0.001",
                    "CDELT2  = 0.001",
                    "RADESYS = 'FK5'",
                    "EQUINOX = 2000.0",
                    "MJD-OBS = 55000.0",
                    "TIMESYS = '" + padded + "'");
            AstObject obj = fc.read();
            assertInstanceOf(FrameSet.class, obj, "Error " + (810 + its) + ": TIMESYS='" + ts + "' read failed");
            double epoch = ((FrameSet) obj).getEpochMjd();
            if ("UTC".equals(ts)) {
                epochUtc = epoch;
            } else if ("TDB".equals(ts)) {
                assertTrue(Math.abs(epoch - epochUtc) >= 1.0e-8, "Error 820");
            } else if ("TCB".equals(ts)) {
                assertTrue(Math.abs(epoch - epochUtc) >= 1.0e-8, "Error 821");
            }
        }
    }
}
