package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the card-handling tests of Rust {@code tests/test_fitschan.rs}: parsing a header,
 * the typed getters and setters, finding, deleting and emptying cards, the Encoding, FitsDigits
 * and FitsRounding attributes, defaults chosen from a source not yet read, and {@code
 * FITSCHAN_OPTIONS}.
 *
 * <p>Left to later tasks: {@code cd_matrix_from_a_source_leaves_end_of_file_as_c_does} reads the
 * FitsChan (Task 22). The tests that write to the FitsChan ({@code native_round_trip_zoommap},
 * {@code native_round_trip_skyframe}, {@code native_rewind_and_reread} and {@code
 * translate_projp_retains_source_cards}) are listed in {@code deferred/rust-tests.txt}.
 */
class FitsChanTest {

    private static FitsChan sipHeader() {
        return FitsChan.fromHeader(Fixtures.read("wcsconv/inputs/sip.head"));
    }

    @Test
    void parseSipHeader() {
        FitsChan fc = sipHeader();
        assertTrue(fc.getNcard() > 50, "cards: " + fc.getNcard());
        assertEquals(2, (int) fc.getFitsI("NAXIS"));
        assertEquals(256, (int) fc.getFitsI("NAXIS1"));
        assertEquals(256, (int) fc.getFitsI("NAXIS2"));
        assertTrue(fc.getFitsL("SIMPLE"));
        String ctype1 = fc.getFitsS("CTYPE1");
        assertTrue(ctype1.contains("RA"), ctype1);
        assertTrue(ctype1.contains("TAN-SIP"), ctype1);
        assertTrue(Math.abs(fc.getFitsF("CRPIX1") - 128.0) < 1e-10);
    }

    @Test
    void getFitsTypedValues() {
        FitsChan fc = sipHeader();
        assertEquals(2, (int) fc.getFitsI("NAXIS"));
        assertEquals(-32, (int) fc.getFitsI("BITPIX"));
        assertTrue(Math.abs(fc.getFitsF("CRVAL1") - 202.482322805429) < 1e-6);
        assertTrue(Math.abs(fc.getFitsF("CRPIX2") - 128.0) < 1e-10);
        assertTrue(fc.getFitsS("CTYPE1").contains("RA"));
        assertTrue(fc.getFitsS("TELESCOP").contains("Spitzer"));
        assertTrue(fc.getFitsL("SIMPLE"));
        assertFalse(fc.getFitsL("HDRMODE"));
        assertNull(fc.getFitsI("NONEXIST"));
        assertNull(fc.getFitsF("NONEXIST"));
        assertNull(fc.getFitsS("NONEXIST"));
        assertNull(fc.getFitsL("NONEXIST"));
    }

    @Test
    void putFitsFindFitsRoundTrip() {
        FitsChan fc = new FitsChan();
        fc.putFits("TESTKEY =                   42 / A test integer keyword                          ", false);
        assertEquals(1, fc.getNcard());
        fc.clear("Card");
        String found = fc.findFits("TESTKEY", false);
        assertNotNull(found);
        assertEquals(80, found.length());
        assertTrue(found.startsWith("TESTKEY "));
        assertTrue(found.contains("42"));
    }

    @Test
    void setGetRoundTripInteger() {
        FitsChan fc = new FitsChan();
        fc.setFits("NAXIS", 4, "Number of axes", false);
        assertEquals(4, (int) fc.getFitsI("NAXIS"));
    }

    @Test
    void setGetRoundTripFloat() {
        FitsChan fc = new FitsChan();
        fc.setFits("CRVAL1", 123.456789012345, "Reference value", false);
        assertTrue(Math.abs(fc.getFitsF("CRVAL1") - 123.456789012345) < 1e-12);
    }

    @Test
    void setGetRoundTripString() {
        FitsChan fc = new FitsChan();
        fc.setFits("CTYPE1", "RA---TAN-SIP", "Axis type", false);
        assertEquals("RA---TAN-SIP", fc.getFitsS("CTYPE1"));
    }

    @Test
    void setGetRoundTripLogical() {
        FitsChan fc = new FitsChan();
        fc.setFits("SIMPLE", true, "Standard FITS", false);
        assertTrue(fc.getFitsL("SIMPLE"));
        fc.setFits("EXTEND", false, "No extensions", false);
        assertFalse(fc.getFitsL("EXTEND"));
    }

    @Test
    void delFitsRemovesCard() {
        FitsChan fc = new FitsChan();
        fc.setFits("ALPHA", 1, "First", false);
        fc.setFits("BETA", 2, "Second", false);
        fc.setFits("GAMMA", 3, "Third", false);
        assertEquals(3, fc.getNcard());
        fc.clear("Card");
        assertNotNull(fc.findFits("BETA", false));
        fc.delFits();
        assertEquals(2, fc.getNcard());
        assertNull(fc.getFitsI("BETA"));
        assertEquals(1, (int) fc.getFitsI("ALPHA"));
        assertEquals(3, (int) fc.getFitsI("GAMMA"));
    }

    @Test
    void emptyFitsClearsHeader() {
        FitsChan fc = sipHeader();
        assertTrue(fc.getNcard() > 0);
        fc.emptyFits();
        assertEquals(0, fc.getNcard());
        assertNull(fc.getFitsI("NAXIS"));
        assertNull(fc.getFitsS("CTYPE1"));
    }

    @Test
    void findFitsIterationWithInc() {
        FitsChan fc = new FitsChan();
        fc.setFits("KEY1", 10, "", false);
        fc.setFits("KEY2", 20, "", false);
        fc.setFits("KEY3", 30, "", false);
        fc.setFits("KEY4", 40, "", false);
        fc.clear("Card");
        assertTrue(fc.findFits("KEY1", true).contains("10"));
        assertTrue(fc.findFits("KEY2", true).contains("20"));
        assertTrue(fc.findFits("KEY3", true).contains("30"));
        assertTrue(fc.findFits("KEY4", true).contains("40"));
    }

    @Test
    void findFitsSearchesForwardOnly() {
        FitsChan fc = new FitsChan();
        fc.setFits("FIRST", 1, "", false);
        fc.setFits("SECOND", 2, "", false);
        fc.setFits("THIRD", 3, "", false);
        fc.clear("Card");
        fc.findFits("SECOND", true);
        // C's astFindFits searches forward from the current card only, leaving end-of-file.
        assertNull(fc.findFits("FIRST", true));
        assertEquals(fc.getNcard() + 1, fc.getCard());
    }

    @Test
    void encodingDefaultForEmptyChanIsNative() {
        // C's astGetEncoding (fitschan.c:12603): NATIVE when no WCS keyword is present.
        assertEquals("NATIVE", new FitsChan().getEncoding());
    }

    @Test
    void encodingSetAndGet() {
        FitsChan fc = new FitsChan();
        for (String e : new String[] {"NATIVE", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "DSS"}) {
            fc.setEncoding(e);
            assertEquals(e, fc.getEncoding());
        }
    }

    @Test
    void encodingFromHeaderAutoDetects() {
        // CDi_j without RADECSYS, PROJP or CmVAL: FITS-WCS (fitschan.c:12596).
        assertEquals("FITS-WCS", sipHeader().getEncoding());
    }

    @Test
    void fitsDigitsDefaultAndSet() {
        FitsChan fc = new FitsChan();
        // AST__DBL_DIG (fitschan.c:43079).
        assertEquals(17, fc.getFitsDigits());
        fc.setFitsDigits(15);
        assertEquals(15, fc.getFitsDigits());
        fc.setFitsDigits(0);
        assertEquals(0, fc.getFitsDigits());
        fc.setFitsDigits(-5);
        assertEquals(-5, fc.getFitsDigits());
        fc.setFitsDigits(25);
        assertEquals(25, fc.getFitsDigits());
        assertTrue(fc.test("FitsDigits"));
        fc.clear("FitsDigits");
        assertEquals(17, fc.getFitsDigits());
        assertFalse(fc.test("FitsDigits"));
        // C tests the value, not whether one was set.
        fc.setFitsDigits(17);
        assertFalse(fc.test("FitsDigits"));
    }

    @Test
    void overwriteTrueReplacesCard() {
        FitsChan fc = new FitsChan();
        fc.setFits("NAXIS", 2, "Original", false);
        assertEquals(1, fc.getNcard());
        fc.clear("Card");
        fc.setFits("NAXIS", 3, "Updated", true);
        assertEquals(1, fc.getNcard());
        assertEquals(3, (int) fc.getFitsI("NAXIS"));
    }

    @Test
    void overwriteFalseAppendsDuplicate() {
        FitsChan fc = new FitsChan();
        fc.setFits("NAXIS", 2, "First", false);
        fc.setFits("NAXIS", 3, "Second", false);
        assertEquals(2, fc.getNcard());
    }

    /** The steps of testfitschan.c {@code test_fitsrounding}: card to put, FitsRounding, C's text. */
    private static final Object[][] ROUNDING_STEPS = {
        {"DUMMY   =  1.0000010001", null, "DUMMY   =         1.0000010001"},
        {"DUMMY   =  1.00000100001", null, "DUMMY   =             1.000001"},
        {"DUMMY   =  1.0000019991", null, "DUMMY   =         1.0000019991"},
        {"DUMMY   =  1.00000199991", null, "DUMMY   =             1.000002"},
        {"DUMMY   =  -1.0000010000001", null, "DUMMY   =            -1.000001"},
        {null, 5, "DUMMY   =                 -1.0"},
        {"DUMMY   =  1.9999969999993E-02", null, "DUMMY   =           0.01999997"},
        {null, 5, "DUMMY   =                 0.02"},
        {"DUMMY   =  1.9999969999993E-08", 10, "DUMMY   =          1.999997E-8"},
        {null, 5, "DUMMY   =               2.0E-8"},
        {"DUMMY   =  9.999999E-6", 5, "DUMMY   =              10.0E-6"},
        {"DUMMY   =  -9.999999", 5, "DUMMY   =                -10.0"},
        {null, null, "DUMMY   =            -9.999999"},
        {null, 6, "DUMMY   =                -10.0"},
        {null, 7, "DUMMY   =            -9.999999"},
        {"DUMMY   =  -0.0019999912", 6, "DUMMY   =        -0.0019999912"},
        {null, 5, "DUMMY   =               -0.002"},
    };

    @Test
    void fitsRoundingFormatsFoundCards() {
        FitsChan fc = new FitsChan();
        assertEquals(10, fc.getI("FitsRounding"));
        for (int i = 0; i < ROUNDING_STEPS.length; i++) {
            Object[] step = ROUNDING_STEPS[i];
            if (step[1] == null) {
                fc.clear("FitsRounding");
            } else {
                fc.setI("FitsRounding", (Integer) step[1]);
            }
            if (step[0] != null) {
                fc.putFits((String) step[0], false);
            }
            fc.clear("Card");
            String found = fc.findFits("DUMMY", false);
            assertNotNull(found, "step " + (i + 1));
            assertEquals(step[2], found.replaceAll(" +$", ""), "step " + (i + 1));
        }
        fc.clear("FitsRounding");
        assertEquals(10, fc.getI("FitsRounding"));
    }

    /**
     * {@code FitsCardList.put} stores a card as {@code putFits} does. C probe: cards A, B, C with
     * the second current, then astPutFits of X=9 (insert), Y=8 (overwrite) and A=7 (overwrite) gives
     * A / first, X, Y / second, A / third, with Card 5.
     */
    @Test
    void cardListPutOverwritesTheCurrentCard() {
        FitsCardList list = FitsCardList.fromHeader("A       =                    1 / first\n"
                + "B       =                    2 / second\n"
                + "C       =                    3 / third");
        list.setPosition(1);
        list.put(FitsCard.parse("X       =                    9"), false);
        list.put(FitsCard.parse("Y       =                    8"), true);
        list.put(FitsCard.parse("A       =                    7"), true);
        List<String> got = new ArrayList<>();
        for (FitsCard c : list.cards()) {
            got.add(c.keyword + " " + c.value.i + " " + c.comment);
        }
        assertEquals(Arrays.asList("A 1 first", "X 9 null", "Y 8 second", "A 7 third"), got);
        assertEquals(4, list.position());
    }

    /** A FITS-WCS header with a CD matrix, given through a source. */
    private static final String[] CD_WCS_SOURCE = {
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
        "CRPIX1  =                   50",
        "CRPIX2  =                   50",
        "CRVAL1  =                   10",
        "CRVAL2  =                   20",
        "CD1_1   =               -0.001",
        "CD2_2   =                0.001",
    };

    private static FitsChan withCdSource() {
        return new FitsChan(Arrays.asList(CD_WCS_SOURCE).iterator());
    }

    /**
     * Encoding, CDMatrix and DefB1950 read a source not yet read before choosing a default. C
     * probe: on a fresh FitsChan with this source, Encoding is FITS-WCS, CDMatrix 1 and DefB1950 0.
     */
    @Test
    void defaultsReadASourceNotYetRead() {
        assertEquals("FITS-WCS", withCdSource().getEncoding());
        assertTrue(withCdSource().getCDMatrix());
        assertFalse(withCdSource().getDefB1950());
    }

    /** A set Encoding is given without reading a source (C probe). */
    @Test
    void setEncodingReadsNoSource() {
        final int[] calls = {0};
        Iterator<String> counting = new Iterator<String>() {
            @Override
            public boolean hasNext() {
                calls[0]++;
                return false;
            }

            @Override
            public String next() {
                throw new NoSuchElementException();
            }
        };
        FitsChan fc = new FitsChan(counting);
        fc.setEncoding("FITS-WCS");
        assertEquals("FITS-WCS", fc.getEncoding());
        assertEquals(0, calls[0]);
    }

    /**
     * Every new FitsChan takes the settings in {@code FITSCHAN_OPTIONS}. The variable is set only for
     * a child JVM running {@link FitsChanOptionsChild}, so no other test sees it. C: astFitsChan
     * applies the variable through astEnvSet, and a bad setting makes the constructor fail; a
     * variable set to "None" is ignored.
     */
    @Test
    void fitschanOptionsAppliesToEveryNewFitschan() throws IOException, InterruptedException {
        String env = System.getenv("FITSCHAN_OPTIONS");
        assertTrue(env == null || env.isEmpty(), "FITSCHAN_OPTIONS is set for the test process; unset it");
        String[][] modes = {
            {"good", "Encoding=fits_wcs, FitsDigits = 8 , Warnings=\"badval\""},
            {"bad", "FitsDigits=8, Bogus=1"},
            {"none", "None"},
        };
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        for (String[] mode : modes) {
            ProcessBuilder pb = new ProcessBuilder(
                    java, "-cp", System.getProperty("java.class.path"), FitsChanOptionsChild.class.getName(), mode[0]);
            pb.environment().put("FITSCHAN_OPTIONS", mode[1]);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p.getInputStream());
            assertEquals(0, p.waitFor(), mode[0] + ": " + out);
            assertTrue(out.contains("passed"), mode[0] + ": " + out);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
