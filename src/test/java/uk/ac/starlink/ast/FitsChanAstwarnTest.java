package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of Rust {@code tests/test_fitschan_astwarn.rs}: where the {@code ASTWARN} cards of a
 * FITS-WCS read go, the KeyMap {@link Channel#warnings} gives, and the Strict attribute.
 *
 * <p>C's {@code Warn} (fitschan.c:35453) stores its cards in front of the current card (from
 * fitschan.c:35590), and during a read the current card is the last {@code NAXIS<i>} that C's
 * {@code DistortMaps} looked up with {@code astGetFitsF} (fitschan.c:9019).
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code
 * badctype_warning_names_the_legal_ctype_and_precedes_naxis1} (a spectral axis, SpecFrame).
 */
class FitsChanAstwarnTest {

    /** A 2-axis header with an unknown celestial system, which C warns of under {@code badcel}. */
    private static final String TWO_AXIS_BADCEL = String.join(
            "\n",
            "NAXIS1  =                  100 / length of data axis 1",
            "NAXIS2  =                  100 / length of data axis 2",
            "CTYPE1  = 'XLON-TAN'           / Unknown longitude axis",
            "CTYPE2  = 'XLAT-TAN'           / Unknown latitude axis",
            "CRVAL1  =                180.0",
            "CRVAL2  =                 45.0",
            "CRPIX1  =                 50.0",
            "CRPIX2  =                 50.0",
            "CDELT1  =                 -0.1",
            "CDELT2  =                  0.1",
            "");

    private static String tanZeropvHeader() {
        return Fixtures.read("wcsconv/inputs/tan-zeropv.head");
    }

    /** A FitsChan with Warnings {@code conditions} and Strict set. */
    private static FitsChan strictChan(String conditions) {
        FitsChan fc = new FitsChan();
        fc.setWarnings(conditions);
        fc.setStrict(true);
        return fc;
    }

    private static void assertMessage(String want, AstException e) {
        assertTrue(e.getMessage().startsWith(want + " ("), "message: " + e.getMessage());
    }

    @Test
    void astwarnBlockPrecedesTheLastNaxisCardForTwoAxes() {
        FitsChan fc = FitsChan.fromHeader(TWO_AXIS_BADCEL);
        fc.read();
        List<String> kws = FitsHeaders.keywords(fc);
        int firstWarn = kws.indexOf("ASTWARN");
        int naxis1 = kws.indexOf("NAXIS1");
        int naxis2 = kws.indexOf("NAXIS2");
        assertTrue(firstWarn >= 0, "a badcel ASTWARN block is expected: " + kws);
        assertTrue(naxis1 >= 0 && naxis2 >= 0, "NAXIS1 and NAXIS2 are kept: " + kws);
        assertTrue(naxis1 < firstWarn && firstWarn < naxis2, "the block lies between NAXIS1 and NAXIS2: " + kws);
    }

    @Test
    void allZeroLongitudePvWarnsAndDemotesToPlainTan() {
        // C's GetUsedPolyTan: the latitude axis has a non-zero coefficient (PV2_1 = 1.0), and the
        // longitude check then finds every coefficient zero and warns (fitschan.c:14266-14290).
        FitsChan fc = FitsChan.fromHeader(tanZeropvHeader());
        assertNotNull(fc.read(), "tan-zeropv.head gives an Object");
        List<String> warns = FitsHeaders.astwarnValues(fc);
        assertTrue(anyContains(warns, "longitude axis are zero"), "longitude badpv warning expected: " + warns);
        assertFalse(anyContains(warns, "latitude axis are zero"), "no latitude badpv warning: " + warns);
    }

    @Test
    void allZeroLatitudePvWarnsAboutTheLatitudeAxis() {
        // With every latitude coefficient zero C warns of the latitude axis and does not reach the
        // longitude check (fitschan.c:14219-14250).
        String text = tanZeropvHeader()
                .replace("PV2_1   =                  1.0", "PV2_1   =                  0.0")
                .replace("PV2_2   =            0.0001000", "PV2_2   =            0.0000000");
        FitsChan fc = FitsChan.fromHeader(text);
        assertNotNull(fc.read(), "the edited header gives an Object");
        List<String> warns = FitsHeaders.astwarnValues(fc);
        assertTrue(anyContains(warns, "latitude axis are zero"), "latitude badpv warning expected: " + warns);
    }

    private static boolean anyContains(List<String> values, String text) {
        for (String v : values) {
            if (v.contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void warningsKeymapMirrorsCAstwarnings() {
        // C's astWarnings (channel.c:1582) makes a KeyMap with keys Warning_1, Warning_2, ... in
        // the order the warnings were issued (channel.c:1703), one string each, or NULL if none.
        FitsChan fc = FitsChan.fromHeader(tanZeropvHeader());
        assertNotNull(fc.read(), "tan-zeropv.head gives an Object");
        List<String> texts = fc.warningTexts();
        assertFalse(texts.isEmpty(), "tan-zeropv.head raises at least one warning");
        KeyMap map = fc.warnings();
        assertNotNull(map, "warnings were issued");
        assertEquals(texts.size(), map.mapSize(), "one entry per warning");
        for (int i = 0; i < texts.size(); i++) {
            String key = "Warning_" + (i + 1);
            assertEquals(KeyMap.AST__STRINGTYPE, map.mapType(key), key + " is a string entry");
            assertEquals(texts.get(i), map.mapGet0C(key), key + " holds the warning text");
        }
        assertFalse(map.mapHasKey("Warning_0"));
        assertFalse(map.mapHasKey("Warning_" + (texts.size() + 1)));
    }

    /** {Warnings KeyMap size or -1 for none, ASTWARN card count} after one bad card. */
    private static int[] badKeyCounts(Integer reportLevel) {
        FitsChan fc = new FitsChan();
        if (reportLevel != null) {
            fc.setReportLevel(reportLevel);
        }
        fc.setWarnings("BadKeyName");
        fc.putFits("BAD!KEY = 1", false);
        KeyMap map = fc.warnings();
        return new int[] {
            map == null ? -1 : map.mapSize(), FitsHeaders.astwarnValues(fc).size()
        };
    }

    @Test
    void reportLevelZeroKeepsAstwarnCardsButRecordsNoWarnings() {
        // A C probe: astPutFits of "BAD!KEY = 1" with Warnings BadKeyName gives two warnings and
        // four ASTWARN cards by default and with ReportLevel 2, and with ReportLevel 0 or -1 no
        // warnings (astWarnings returns NULL) but the same four cards.
        assertEquals("[2, 4]", Arrays.toString(badKeyCounts(null)));
        assertEquals("[-1, 4]", Arrays.toString(badKeyCounts(0)));
        assertEquals("[2, 4]", Arrays.toString(badKeyCounts(2)));
        assertEquals("[-1, 4]", Arrays.toString(badKeyCounts(-1)));
    }

    @Test
    void strictPutFitsWarningIsAnError() {
        // A C probe: astPutFits of this card with Warnings BadKeyName and Strict set fails with
        // AST__BADIN and stores no card; with ReportLevel 0 it stores the ASTWARN block and the
        // card, and with Clean set the card alone.
        String card = "BAD!KEY =                    1";
        FitsChan fc = strictChan("BadKeyName");
        AstException e = assertThrows(AstException.class, () -> fc.putFits(card, false));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertMessage(
                "astPutFits(FitsChan): The FITS keyword name ('BAD!KEY') contains an illegal character ('!').", e);
        assertEquals(0, fc.getNcard());
        assertTrue(fc.warningTexts().isEmpty());

        FitsChan fc0 = strictChan("BadKeyName");
        fc0.setReportLevel(0);
        fc0.putFits(card, false);
        assertEquals(5, fc0.getNcard());

        FitsChan clean = strictChan("BadKeyName");
        clean.set("Clean=1");
        clean.putFits(card, false);
        assertEquals(1, clean.getNcard());
    }

    /** The cards of Rust's {@code NO_EQUINOX} before its {@code RADECSYS}. */
    private static final String[] TAN_CARDS = {
        "NAXIS   =                    2",
        "NAXIS1  =                  100",
        "NAXIS2  =                  100",
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
        "CRPIX1  =                   50",
        "CRPIX2  =                   50",
        "CRVAL1  =                   10",
        "CRVAL2  =                   20",
        "CDELT1  =               -0.001",
        "CDELT2  =                0.001",
    };

    @Test
    void strictWriteWarningIsAnError() {
        // A C probe: a FrameSet with an MJD-OBS written to a FitsChan holding TIMESYS = 'ET', with
        // Warnings badval and Strict set, fails naming the method "FitsChan", since C's Write
        // passes FsetToStore its method and class names in the wrong order.
        FitsChan fc = new FitsChan();
        for (String card : TAN_CARDS) {
            fc.putFits(card, false);
        }
        fc.putFits("EQUINOX =                 2000", false);
        fc.putFits("MJD-OBS =                55000", false);
        fc.setCard(1);
        AstObject obj = fc.read();
        assertNotNull(obj);

        FitsChan out = strictChan("badval");
        out.setEncoding("FITS-WCS");
        out.putFits("TIMESYS = 'ET'", false);
        out.setCard(1);
        String before = out.toHeader();
        AstException e = assertThrows(AstException.class, () -> out.write(obj));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertMessage(
                "FitsChan(FitsChan): The original FITS header contained a value of ET for keyword TIMESYS. TT will be"
                        + " used instead.",
                e);
        assertEquals(before, out.toHeader());
    }

    /**
     * A 2-axis TAN header with {@code PROJPi} and {@code RADECSYS}, which C reads with the
     * FITS-IRAF encoding: the PROJP values become PV2_m, which TAN does not use.
     */
    private static final String TWO_AXIS_BADPV = String.join(
            "\n",
            "NAXIS1  =                  512 / length of data axis 1",
            "NAXIS2  =                  512 / length of data axis 2",
            "CTYPE1  = 'RA---TAN'           / Gnomonic projection",
            "CTYPE2  = 'DEC--TAN'           / Gnomonic projection",
            "CRVAL1  =              180.000 / Reference RA (deg)",
            "CRVAL2  =               45.000 / Reference Dec (deg)",
            "CRPIX1  =              256.500 / Reference pixel on axis 1",
            "CRPIX2  =              256.500 / Reference pixel on axis 2",
            "CD1_1   =        -8.3332205E-4 / CD matrix",
            "CD1_2   =                  0.0 / CD matrix",
            "CD2_1   =                  0.0 / CD matrix",
            "CD2_2   =        0.00083332205 / CD matrix",
            "RADECSYS= 'FK5     '           / Old-style reference frame keyword",
            "EQUINOX =               2000.0 / Equinox",
            "PROJP0  =                  0.0 / Legacy projection parameter 0",
            "PROJP1  =                  0.0 / Legacy projection parameter 1",
            "PROJP2  =                  0.0 / Legacy projection parameter 2",
            "LONGPOLE=              180.000 / Native longitude of celestial pole",
            "");

    @Test
    void badpvWarningNamesEachUnusableParameterInAscendingOrder() {
        FitsChan fc = FitsChan.fromHeader(TWO_AXIS_BADPV);
        fc.read();
        // Text and wrapping from wcsconv/expected/projp.native, with the trailing space where C's
        // 60-character wrap breaks the line.
        assertEquals(
                Arrays.asList(
                        "        ",
                        "Projection parameter PV2_1 found, but is not used by -TAN ",
                        "projections.",
                        "        ",
                        "        ",
                        "Projection parameter PV2_2 found, but is not used by -TAN ",
                        "projections.",
                        "        "),
                FitsHeaders.astwarnValues(fc));
    }

    /** Rust's {@code NO_EQUINOX}: a TAN header with {@code RADECSYS} and no EQUINOX, read as FITS-PC. */
    private static String[] noEquinox() {
        String[] cards = Arrays.copyOf(TAN_CARDS, TAN_CARDS.length + 1);
        cards[TAN_CARDS.length] = "RADECSYS= 'FK5'";
        return cards;
    }

    @Test
    void strictReadWarningIsAnError() {
        // A C probe: the read of NO_EQUINOX with Warnings noequinox gives an Object and an ASTWARN
        // block; with Strict set too it fails with AST__BADIN, leaving the 12 cards, Card 1 and no
        // warnings; with ReportLevel 0 as well it succeeds as without Strict.
        FitsChan fc = strictChan("noequinox");
        for (String card : noEquinox()) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        String before = fc.toHeader();
        AstException e = assertThrows(AstException.class, fc::read);
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertMessage(
                "astRead(FitsChan): The original FITS header did not specify the reference equinox. A default value"
                        + " of J2000 was assumed.",
                e);
        assertEquals(before, fc.toHeader());
        assertEquals(1, fc.getCard());
        assertTrue(fc.warningTexts().isEmpty());

        FitsChan fc0 = strictChan("noequinox");
        fc0.setReportLevel(0);
        for (String card : noEquinox()) {
            fc0.putFits(card, false);
        }
        fc0.clear("Card");
        assertNotNull(fc0.read());
        assertEquals(7, fc0.getNcard());
    }

    @Test
    void strictSweepWarningIsAnError() {
        // A C probe: NO_EQUINOX with CRVAL1 = 'abc', read with Warnings badval, gives an Object
        // and a badval ASTWARN block in front of CRVAL1; with Strict set the read fails with
        // AST__BADIN, leaving the 12 cards and Card 1.
        FitsChan fc = strictChan("badval");
        for (String card : noEquinox()) {
            fc.putFits(card.startsWith("CRVAL1") ? "CRVAL1  = 'abc'" : card, false);
        }
        fc.clear("Card");
        String before = fc.toHeader();
        AstException e = assertThrows(AstException.class, fc::read);
        assertEquals(AstException.AST__BADIN, e.getStatus());
        assertMessage(
                "astRead(FitsChan): The original FITS header contained a value for keyword CRVAL1 which could not be"
                        + " converted to a floating point number.",
                e);
        assertEquals(before, fc.toHeader());
        assertEquals(1, fc.getCard());
    }
}
