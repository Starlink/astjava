package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_fitschan_c_inline.rs}: checks made inline in {@code main()}
 * of C's {@code ast_tester/testfitschan.c}, each named by C's error numbers and made with C's
 * values.
 *
 * <p>The write tests use {@link #roundtrip}, C's helper of the same name, and expect the values a
 * C probe of the pinned library gives ({@code c1.c} beside the task report): where C's test accepts
 * either of two outcomes, the one C gives.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: those that need a SpecFrame ({@code
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

    /** C's {@code roundtrip} result: the write or read failed. */
    private static final int RT_FAILED = 0;

    /** C's {@code roundtrip} result: the keyword did not have the expected value. */
    private static final int RT_WRONG_KEY = -2;

    /** C's {@code roundtrip} result: the coordinates disagreed. */
    private static final int RT_DISAGREE = -1;

    /** C's {@code roundtrip} result: all is well. */
    private static final int RT_OK = 1;

    /** The pixel value on every axis of each point {@link #roundtrip} compares. */
    private static final double[] OFFSETS = {50.0, 50.5, 51.0, 55.0, 45.0};

    /**
     * C's {@code roundtrip} (testfitschan.c): writes {@code fs} with {@code encoding} into a new
     * FitsChan, checks that {@code key} (any {@code CTYPEn}, n from 1 to 9, for {@code "CTYPE"})
     * has the value {@code val}, reads it back, and, unless {@code tol} is negative, compares the
     * world coordinates of the two FrameSets at {@link #OFFSETS} on every axis, after converting
     * the first FrameSet's to the current Frame of the second, to {@code tol} absolute or relative.
     * When the current Frames cannot be converted, the pixel coordinates are compared after a
     * round trip through the first FrameSet and back through the second. An error clears to
     * {@link #RT_FAILED}, as C clears the status.
     */
    private static int roundtrip(FrameSet fs, String encoding, double tol, String key, String val) {
        try {
            FitsChan fc = new FitsChan();
            fc.setEncoding(encoding);
            if (fc.write(fs) != 1) {
                return RT_FAILED;
            }
            boolean found = false;
            List<String> keys = new ArrayList<>();
            if ("CTYPE".equals(key)) {
                for (int iax = 1; iax <= 9; iax++) {
                    keys.add("CTYPE" + iax);
                }
            } else {
                keys.add(key);
            }
            for (String k : keys) {
                fc.clear("Card");
                if (val.equals(fc.getFitsS(k))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return RT_WRONG_KEY;
            }
            fc.clear("Card");
            AstObject obj = fc.read();
            if (obj == null) {
                return RT_FAILED;
            }
            FrameSet fs2 = (FrameSet) obj;
            if (tol < 0.0) {
                return RT_OK;
            }
            Mapping map1 = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Mapping map2 = fs2.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Frame curfrm2 = fs2.getFrame(FrameSet.AST__CURRENT);
            int nin = fs.getNin();
            int nout = fs.getNout();
            FrameSet cvt = fs.getFrame(FrameSet.AST__CURRENT).convert(curfrm2, " ");
            if (cvt != null) {
                for (double offset : OFFSETS) {
                    double[] pix = new double[nin];
                    Arrays.fill(pix, offset);
                    double[] wcs1 = map1.tranN(1, nin, pix, true, nout);
                    double[] wcs2 = map2.tranN(1, nin, pix, true, nout);
                    double[] wcs1c = cvt.tranN(1, nout, wcs1, true, nout);
                    for (int iax = 0; iax < nout; iax++) {
                        double v1 = wcs1c[iax];
                        double v2 = wcs2[iax];
                        if (v1 == AstObject.AST__BAD || v2 == AstObject.AST__BAD) {
                            continue;
                        }
                        double diff = Math.abs(curfrm2.axDistance(iax + 1, v1, v2));
                        if (diff > tol) {
                            double scale = Math.max(Math.abs(v1), Math.abs(v2));
                            if (scale > 0.0 && diff / scale > tol) {
                                return RT_DISAGREE;
                            }
                        }
                    }
                }
            } else {
                Mapping roundmap = new CmpMap(map1, fs2.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE), true);
                for (double offset : OFFSETS) {
                    double[] pixIn = new double[nin];
                    Arrays.fill(pixIn, offset);
                    double[] pixOut = roundmap.tranN(1, nin, pixIn, true, nin);
                    for (int iax = 0; iax < nin; iax++) {
                        if (pixIn[iax] == AstObject.AST__BAD || pixOut[iax] == AstObject.AST__BAD) {
                            continue;
                        }
                        double diff = Math.abs(pixOut[iax] - pixIn[iax]);
                        if (diff > tol) {
                            double scale = Math.abs(pixIn[iax]);
                            if (scale > 0.0 && diff / scale > tol) {
                                return RT_DISAGREE;
                            }
                        }
                    }
                }
            }
            return RT_OK;
        } catch (AstException e) {
            return RT_FAILED;
        }
    }

    /**
     * The keywords whose values {@link #azelWritesAsFitsWcs} and {@link #supergalacticWritesSlon}
     * compare within libm rounding ({@link FitsHeaders#cardsMatchC(List, List, java.util.Set)}).
     * The Mappings fitted are C's, but java.lang.Math differs from C's libm by 1 ULP at the same
     * arguments: in the sine MtrRot takes of the rotation angles (AZEL), in an arctangent of
     * palEqgal and palGalsup giving the fiducial latitude, and so the angle, of the rotation
     * (Supergalactic), and in the SphMap, SlaMap and TAN WcsMap transforms of the points FitLine
     * fits (Galactic); IWC computed near the native pole amplify those differences (probes g1,
     * a2, rep, sla and cs beside the task report).
     */
    private static final Set<String> LIBM_KEYWORDS =
            new HashSet<>(Arrays.asList("CDELT1", "CDELT2", "PC1_1", "PC1_2", "PC2_1", "PC2_2"));

    /** The FrameSet {@code fc} reads from {@code cards}. */
    private static FrameSet readCards(String... cards) {
        return FitsHeaders.readFrameSet(FitsHeaders.chan(cards));
    }

    /** 12-13: with SipOK off, a SIP header reads but is too non-linear to write as FITS-WCS. */
    @Test
    void sipHeaderDoesNotWriteAsFitsWcs() {
        FitsChan fc = new FitsChan();
        fc.set("SipOK=0");
        fc.setC("SourceFile", Fixtures.root().resolve("wcsconv/inputs/sip.head").toString());
        fc.clear("Card");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        fc.setEncoding("FITS-WCS");
        assertEquals(0, fc.write(fs), "Error 13");
        // C leaves the 244 cards the read left.
        assertEquals(244, fc.getNcard());
    }

    /** The header of the AZEL test (630-633), without its MJD-OBS. */
    private static final String[] AZEL_SOURCE = {
        "SIMPLE  =                    T",
        "BITPIX  =                  -32",
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
        "CRVAL1  =              180.000",
        "CRVAL2  =               45.000",
        "CRPIX1  =               50.500",
        "CRPIX2  =               50.500",
        "CDELT1  =              -0.0100",
        "CDELT2  =               0.0100",
        "RADESYS = 'FK5'",
        "EQUINOX =               2000.0",
        "DATE-OBS= '2017-07-21T08:38:39.087'",
        "OBSGEO-X=  -5464586.5949660344",
        "OBSGEO-Y=  -2492996.5580556658",
        "OBSGEO-Z=   2150654.3760909005",
        "END",
    };

    /**
     * 630-633: an FK5 header with an observation date and place, changed to AZEL, writes {@code
     * AZ---TAN} as FITS-WCS (C puts the elevation axis first) and reads back.
     */
    @Test
    void azelWritesAsFitsWcs() {
        FrameSet fs = readCards(AZEL_SOURCE);
        fs.setC("System", "AZEL");
        assertEquals(RT_OK, roundtrip(fs, "FITS-WCS", 1e-3, "CTYPE", "AZ---TAN"), "Error 630-633");

        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        assertEquals(1, fc.write(fs));
        List<String> want = FitsHeaders.padded(
                "WCSAXES =                    2 / Number of WCS axes",
                "CRPIX1  =            50.499968 / Reference pixel on axis 1",
                "CRPIX2  =            50.499981 / Reference pixel on axis 2",
                "CRVAL1  =   11.615311196755748 / Value at ref. pixel on axis 1",
                "CRVAL2  =   313.79068013639778 / Value at ref. pixel on axis 2",
                "CTYPE1  = 'EL---TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
                "CTYPE2  = 'AZ---TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
                "CDELT1  = -0.010000493460993179 / Pixel size on axis 1",
                "CDELT2  = 0.010000494181343293 / Pixel size on axis 2",
                "PC1_1   =  0.95887433689593371 / Transformation matrix element",
                "PC1_2   = -0.28383094623804417 / Transformation matrix element",
                "PC2_1   =  0.28383092720225839 / Transformation matrix element",
                "PC2_2   =  0.95887434253060821 / Transformation matrix element",
                "MJD-OBS =   57955.360174618057 / Modified Julian Date of observation",
                "DATE-OBS= '2017-07-21T08:38:39.087' / Date of observation",
                "OBSGEO-X=  -5464586.5949660344 / [m] Observatory geocentric X",
                "OBSGEO-Y=  -2492996.5580556653 / [m] Observatory geocentric Y",
                "OBSGEO-Z=    2150654.376090901 / [m] Observatory geocentric Z");
        List<String> got = FitsHeaders.foundCards(fc);
        assertTrue(FitsHeaders.cardsMatchC(got, want, LIBM_KEYWORDS), String.join("\n", got));
    }

    /**
     * 835-836: an FK5 header remapped to Galactic and to Supergalactic writes {@code GLON} and
     * {@code SLON} as FITS-WCS; C puts the Galactic latitude axis first.
     */
    @Test
    void supergalacticWritesSlon() {
        String[][] want = {
            {
                "WCSAXES =                    2 / Number of WCS axes",
                "CRPIX1  =                 50.0 / Reference pixel on axis 1",
                "CRPIX2  =                 50.0 / Reference pixel on axis 2",
                "CRVAL1  =   40.885263413197158 / Value at ref. pixel on axis 1",
                "CRVAL2  =   141.58712489261552 / Value at ref. pixel on axis 2",
                "CTYPE1  = 'GLAT-TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
                "CTYPE2  = 'GLON-TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
                "CDELT1  =                 0.01 / Pixel size on axis 1",
                "CDELT2  =                 0.01 / Pixel size on axis 2",
                "PC1_1   =  0.99949812396986037 / Transformation matrix element",
                "PC1_2   = 0.031678070975510009 / Transformation matrix element",
                "PC2_1   = -0.031678070968866552 / Transformation matrix element",
                "PC2_2   =  0.99949812397007098 / Transformation matrix element",
                "MJD-OBS =   51544.499257130781 / Modified Julian Date of observation",
                "DATE-OBS= '2000-01-01T11:58:55.816' / Date of observation",
            },
            {
                "WCSAXES =                    2 / Number of WCS axes",
                "CRPIX1  =                 50.0 / Reference pixel on axis 1",
                "CRPIX2  =                 50.0 / Reference pixel on axis 2",
                "CRVAL1  =   320.87048231548192 / Value at ref. pixel on axis 1",
                "CRVAL2  =  -52.245457827546453 / Value at ref. pixel on axis 2",
                "CTYPE1  = 'SLON-TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
                "CTYPE2  = 'SLAT-TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
                "CDELT1  = -0.0099999999998425602 / Pixel size on axis 1",
                "CDELT2  =                 0.01 / Pixel size on axis 2",
                "PC1_1   =  0.99019598050830937 / Transformation matrix element",
                "PC1_2   =  0.13968507502660274 / Transformation matrix element",
                "PC2_1   = -0.13968507500640948 / Transformation matrix element",
                "PC2_2   =  0.99019598051115809 / Transformation matrix element",
                "MJD-OBS =   51544.499257130781 / Modified Julian Date of observation",
                "DATE-OBS= '2000-01-01T11:58:55.816' / Date of observation",
            },
        };
        String[] systems = {"Galactic", "Supergalactic"};
        for (int it = 0; it < systems.length; it++) {
            FrameSet fs = readCards(
                    "NAXIS1  =                  100",
                    "NAXIS2  =                  100",
                    "CTYPE1  = 'RA---TAN'",
                    "CTYPE2  = 'DEC--TAN'",
                    "CRVAL1  =              180.000",
                    "CRVAL2  =               45.000",
                    "CRPIX1  =               50.000",
                    "CRPIX2  =               50.000",
                    "CDELT1  =              -0.0100",
                    "CDELT2  =               0.0100",
                    "RADESYS = 'FK5'",
                    "EQUINOX =               2000.0",
                    "END");
            SkyFrame tgt = new SkyFrame();
            tgt.setC("System", systems[it]);
            FrameSet cvt = fs.getFrame(FrameSet.AST__CURRENT).convert(tgt, "");
            fs.remapFrame(FrameSet.AST__CURRENT, cvt.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT));
            fs.setC("System", systems[it]);

            FitsChan wfc = FitsHeaders.chan("NAXIS1  =                  100", "NAXIS2  =                  100");
            wfc.setEncoding("FITS-WCS");
            assertEquals(1, wfc.write(fs), systems[it]);
            List<String> w = FitsHeaders.padded("NAXIS1  =                  100", "NAXIS2  =                  100");
            w.addAll(FitsHeaders.padded(want[it]));
            List<String> got = FitsHeaders.foundCards(wfc);
            assertTrue(
                    FitsHeaders.cardsMatchC(got, w, LIBM_KEYWORDS),
                    "Error " + (835 + it) + ": SkySys " + systems[it] + ":\n" + String.join("\n", got));
        }
    }

    /**
     * 500-534: a Galactic TAN header changed to each sky System and written in each of five
     * encodings. C's test accepts a refused write; C refuses FK4-NO-E and ICRS with FITS-AIPS and
     * FITS-AIPS++, and Supergalactic with FITS-IRAF, FITS-AIPS and FITS-AIPS++. Every write made
     * has C's longitude CTYPE and reads back to the same sky positions.
     */
    @Test
    void skySystemsThroughFiveEncodings() {
        String[] systems = {"FK5", "FK4", "FK4-NO-E", "ICRS", "GALACTIC", "SUPERGALACTIC", "ECLIPTIC"};
        String[] ctypes = {"RA---TAN", "RA---TAN", "RA---TAN", "RA---TAN", "GLON-TAN", "SLON-TAN", "ELON-TAN"};
        String[] encodings = {"FITS-WCS", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++"};
        int[][] want = {
            {1, 1, 1, 1, 1},
            {1, 1, 1, 1, 1},
            {1, 1, 1, 0, 0},
            {1, 1, 1, 0, 0},
            {1, 1, 1, 1, 1},
            {1, 1, 0, 0, 0},
            {1, 1, 1, 1, 1},
        };
        for (int is = 0; is < systems.length; is++) {
            for (int ie = 0; ie < encodings.length; ie++) {
                FrameSet fs = readCards(
                        "SIMPLE  =                    T",
                        "BITPIX  =                  -32",
                        "NAXIS   =                    2",
                        "NAXIS1  =                  100",
                        "NAXIS2  =                  100",
                        "CTYPE1  = 'GLON-TAN'",
                        "CTYPE2  = 'GLAT-TAN'",
                        "CRVAL1  =              180.000",
                        "CRVAL2  =                0.000",
                        "CRPIX1  =               50.500",
                        "CRPIX2  =               50.500",
                        "CDELT1  =              -0.0100",
                        "CDELT2  =               0.0100",
                        "END");
                fs.setC("System", systems[is]);
                assertEquals(
                        want[is][ie],
                        roundtrip(fs, encodings[ie], 1e-3, "CTYPE", ctypes[is]),
                        "Error " + (500 + is * encodings.length + ie) + ": " + systems[is] + " with " + encodings[ie]);
            }
        }
    }

    /**
     * 650-659 (DATE-OBS block): an FK5 header with MJD-OBS and OBSGEO writes them back as FITS-WCS
     * within 1e-3 days and 1 m, as the cards C writes.
     */
    @Test
    void dateAndObservatoryWriteBack() {
        String[] source = Arrays.copyOf(AZEL_SOURCE, AZEL_SOURCE.length + 1);
        System.arraycopy(AZEL_SOURCE, 15, source, 16, AZEL_SOURCE.length - 15);
        source[15] = "MJD-OBS =   57955.360174621412";
        FrameSet fs = readCards(source);
        FitsChan wfc = new FitsChan();
        wfc.setEncoding("FITS-WCS");
        assertEquals(1, wfc.write(fs), "Error 651");
        String[] keys = {"MJD-OBS", "OBSGEO-X", "OBSGEO-Y", "OBSGEO-Z"};
        // C's values, to the digits of its header cards.
        String[] values = {"57955.360174621412", "-5464586.5949660344", "-2492996.5580556658", "2150654.3760909005"};
        double[] tols = {1e-3, 1.0, 1.0, 1.0};
        for (int i = 0; i < keys.length; i++) {
            wfc.clear("Card");
            Double got = wfc.getFitsF(keys[i]);
            assertNotNull(got, "Error " + (652 + 2 * i) + ": " + keys[i] + " not written");
            assertTrue(
                    Math.abs(got - Double.parseDouble(values[i])) <= tols[i],
                    "Error " + (653 + 2 * i) + ": " + keys[i] + " = " + got);
        }
        List<String> want = FitsHeaders.padded(
                "WCSAXES =                    2 / Number of WCS axes",
                "CRPIX1  =                 50.5 / Reference pixel on axis 1",
                "CRPIX2  =                 50.5 / Reference pixel on axis 2",
                "CRVAL1  =                180.0 / Value at ref. pixel on axis 1",
                "CRVAL2  =                 45.0 / Value at ref. pixel on axis 2",
                "CTYPE1  = 'RA---TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
                "CTYPE2  = 'DEC--TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
                "CDELT1  =                -0.01 / Pixel size on axis 1",
                "CDELT2  =                 0.01 / Pixel size on axis 2",
                "MJD-OBS =   57955.360174621412 / Modified Julian Date of observation",
                "DATE-OBS= '2017-07-21T08:38:39.087' / Date of observation",
                "RADESYS = 'FK5     '           / Reference frame for RA/DEC values",
                "EQUINOX =               2000.0 / [yr] Epoch of reference equinox",
                "OBSGEO-X=  -5464586.5949660344 / [m] Observatory geocentric X",
                "OBSGEO-Y=  -2492996.5580556653 / [m] Observatory geocentric Y",
                "OBSGEO-Z=    2150654.376090901 / [m] Observatory geocentric Z");
        List<String> got = FitsHeaders.foundCards(wfc);
        assertTrue(FitsHeaders.cardsMatchC(got, want), String.join("\n", got));
    }
}
