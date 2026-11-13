package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the unit tests of Rust {@code fitschan/wcs_keywords.rs}: the extraction of the
 * FITS-WCS keywords of one axis description into a {@link FitsStore}, as C's {@code WcsFcRead}
 * (fitschan.c:36696) stores them. Further tests cover where the extraction follows C rather than
 * the Rust port: the observation details C reads for the primary description only, the SIP
 * distortion code, the order in which repeated SIP coefficients are stored, and which of two cards
 * for one keyword gives its value.
 */
class FitsWcsKeywordsTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void keyForPrimaryNoSuffix() {
        assertEquals("CRVAL1", FitsWcsKeywords.keyFor("CRVAL", 1, ' '));
        assertEquals("RADESYS", FitsWcsKeywords.keyFor("RADESYS", 0, ' '));
    }

    @Test
    void keyForAltAppendsLetter() {
        assertEquals("CRVAL1A", FitsWcsKeywords.keyFor("CRVAL", 1, 'A'));
        assertEquals("RADESYSA", FitsWcsKeywords.keyFor("RADESYS", 0, 'A'));
    }

    @Test
    void keyFor2HandlesPcCd() {
        assertEquals("PC1_2", FitsWcsKeywords.keyFor2("PC", 1, 2, ' '));
        assertEquals("CD1_1B", FitsWcsKeywords.keyFor2("CD", 1, 1, 'B'));
    }

    @Test
    void wcsStoreStringStripIsUnconditionalUnlikeGenericAccessor() {
        // WcsFcRead strips trailing spaces from every string it stores (fitschan.c:37369), whatever
        // its length, unlike the generic accessor.
        String header = "NAXIS   =                    2\n"
                + "CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "SPECSYS = 'BARYCENTRIC  '\n"
                + "END";
        FitsCardList cards = FitsCardList.fromHeader(header);
        assertEquals("BARYCENTRIC  ", cards.getString("SPECSYS"));
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(cards, ' ', 2);
        assertEquals("BARYCENTRIC", store.specsys);
    }

    @Test
    void versionsFollowCrpixAndCrval() {
        // The pixel axes come from the highest CRPIX index, NAXIS aside; an alternate description
        // is read up to the highest letter with a CRVAL (C's GetMaxS), so B, with only a CRPIX, is
        // not.
        String header = "NAXIS   =                    2\n"
                + "CRPIX1  =                  1.0\n"
                + "CRPIX3  =                  1.0\n"
                + "CRVAL1  =                  1.0\n"
                + "CTYPE1A = 'GLON-TAN'\n"
                + "CRVAL2A =                  1.0\n"
                + "CRPIX1B =                100.0\n"
                + "END";
        FitsWcsMark.WcsVersions v = FitsWcsMark.wcsVersions(FitsCardList.fromHeader(header));
        assertEquals(3, v.npix);
        assertTrue(v.primary);
        assertEquals(Collections.singletonList('A'), v.alternates);
        // Without a CRPIX there are no pixel axes and nothing is read.
        header = "NAXIS   =                    2\nCTYPE1  = 'RA---TAN'\nCRVAL1  =                  1.0\nEND";
        v = FitsWcsMark.wcsVersions(FitsCardList.fromHeader(header));
        assertEquals(0, v.npix);
        assertFalse(v.primary);
        assertTrue(v.alternates.isEmpty());
    }

    @Test
    void extractWithAltsNoPrimaryFallback() {
        // C's GetItem has no primary fallback: an alternate description that omits CRVAL1A has no
        // CRVAL for axis 1 though CRVAL1 is set.
        String header = "NAXIS   =                    2\n"
                + "CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "CRVAL1  =                180.0\n"
                + "CRVAL2  =                 45.0\n"
                + "CRPIX1  =                 50.5\n"
                + "CRPIX2  =                 50.5\n"
                + "CDELT1  =                -0.01\n"
                + "CDELT2  =                 0.01\n"
                + "CTYPE1A = 'GLON-TAN'\n"
                + "CTYPE2A = 'GLAT-TAN'\n"
                + "CRVAL2A =                  1.0\n"
                + "END";
        FitsWcsKeywords.WcsStores stores = FitsWcsKeywords.extractWcsKeywordsWithAlts(FitsCardList.fromHeader(header));
        assertEquals("RA---TAN", stores.primary.ctype[0]);
        assertEquals(1, stores.alts.size());
        assertEquals('A', (char) stores.altLetters.get(0));
        FitsStore alt = stores.alts.get(0);
        assertEquals("GLON-TAN", alt.ctype[0]);
        assertNull(alt.crval[0]);
        assertNull(alt.crpix[0]);
        assertNull(alt.cdelt[0]);
    }

    @Test
    void minimalHeader() {
        String header = "NAXIS   =                    2 / Number of axes\n"
                + "CRPIX1  =                128.0 / Reference pixel axis 1\n"
                + "CRPIX2  =                256.0 / Reference pixel axis 2\n"
                + "CRVAL1  =              180.000 / Reference value axis 1\n"
                + "CRVAL2  =              -45.000 / Reference value axis 2\n"
                + "CDELT1  =            -0.001000 / Pixel scale axis 1\n"
                + "CDELT2  =             0.001000 / Pixel scale axis 2\n"
                + "CTYPE1  = 'RA---TAN'           / Axis type\n"
                + "CTYPE2  = 'DEC--TAN'           / Axis type\n"
                + "END";
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(header), ' ', 2);
        assertEquals(2, store.naxis);
        assertEquals(128.0, store.crpix[0]);
        assertEquals(256.0, store.crpix[1]);
        assertEquals(180.0, store.crval[0]);
        assertEquals(-45.0, store.crval[1]);
        assertEquals(-0.001, store.cdelt[0]);
        assertEquals(0.001, store.cdelt[1]);
        assertEquals("RA---TAN", store.ctype[0]);
        assertEquals("DEC--TAN", store.ctype[1]);
        assertEquals("Axis type", store.ctypeCom[0]);
        for (Double[] row : store.cd) {
            for (Double v : row) {
                assertNull(v);
            }
        }
        assertFalse(store.hasPcMatrix());
        assertFalse(store.hasSip());
    }

    @Test
    void cdMatrixHeaderArrivesAsPcAndCdelt() {
        // SpecTrans normalises each row of a CD matrix to unit length, the row length, signed by
        // the diagonal element, becoming that row's CDELT (fitschan.c:31775-31804).
        String header = "NAXIS   =                    2 / Number of axes\n"
                + "CRPIX1  =                128.0 / Reference pixel\n"
                + "CRPIX2  =                128.0 / Reference pixel\n"
                + "CRVAL1  =              202.000 / Reference value\n"
                + "CRVAL2  =               47.000 / Reference value\n"
                + "CD1_1   =          0.000250000 / CD matrix element\n"
                + "CD1_2   =          0.000230000 / CD matrix element\n"
                + "CD2_1   =          0.000230000 / CD matrix element\n"
                + "CD2_2   =         -0.000250000 / CD matrix element\n"
                + "CTYPE1  = 'RA---TAN'           / Axis type\n"
                + "CTYPE2  = 'DEC--TAN'           / Axis type\n"
                + "END";
        FitsCardList cards = FitsCardList.fromHeader(header);
        FitsSpecTrans.specTransInplace(
                cards, FitsChan.Encoding.FITS_WCS, new FitsSpecTrans.TransAttrs(), new FitsSpecTrans.TransWarnings());
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(cards, ' ', 2);
        assertTrue(store.hasPcMatrix());
        double cdelt = Math.sqrt(0.00025 * 0.00025 + 0.00023 * 0.00023);
        assertEquals(cdelt, store.cdelt[0], 1e-17);
        assertEquals(-cdelt, store.cdelt[1], 1e-17);
        double[][] cd = {{0.000250, 0.000230}, {0.000230, -0.000250}};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                double got = store.pc[i][j] * store.cdelt[i];
                assertEquals(cd[i][j], got, 1e-15, "PC*CDELT[" + i + "][" + j + "]");
            }
        }
    }

    @Test
    void wcsaxesSetsTheNumberOfWcsAxes() {
        String header = "NAXIS   =                    4 / Physical axes\n"
                + "WCSAXES =                    2 / WCS axes\n"
                + "CRPIX1  =                  1.0\n"
                + "CRPIX2  =                  1.0\n"
                + "END";
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(header), ' ', 4);
        assertEquals(2, store.naxis);
        assertEquals(2, (int) store.wcsaxes);
    }

    @Test
    void sipHeaderExtraction() {
        FitsCardList cards = FitsCardList.fromHeader(Fixtures.read("wcsconv/inputs/sip.head"));
        FitsSpecTrans.specTransInplace(
                cards, FitsChan.Encoding.FITS_WCS, new FitsSpecTrans.TransAttrs(), new FitsSpecTrans.TransWarnings());
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(cards, ' ', 2);
        assertEquals(2, store.naxis);
        assertEquals(128.0, store.crpix[0]);
        assertEquals(128.0, store.crpix[1]);
        assertEquals(202.482322805429, store.crval[0]);
        assertEquals(47.1751189300101, store.crval[1]);
        // The SIP code is removed from the CTYPE values.
        assertEquals("RA---TAN", store.ctype[0]);
        assertEquals("DEC--TAN", store.ctype[1]);
        // The read takes the powers from the coefficients, so the ORDER keywords are not read.
        assertTrue(store.hasSip());
        assertNull(store.sipAOrder);
        assertNull(store.sipBOrder);
        assertNull(store.sipApOrder);
        assertNull(store.sipBpOrder);
        assertEquals(2.9656e-06, store.sipA[0][2]);
        assertEquals(-2.3863e-05, store.sipA[2][0]);
        assertEquals(2.31e-05, store.sipB[0][2]);
        // The CD matrix reaches the store as PC and CDELT.
        assertTrue(store.hasPcMatrix());
        assertEquals(0.000249756880272355, store.pc[0][0] * store.cdelt[0], 1e-15);
        assertEquals("ICRS", store.radesys);
        assertEquals(2000.0, store.equinox);
        // The header has MJD_OBS and DATE_OBS, which C does not recognise.
        assertNull(store.mjdobs);
    }

    @Test
    void pvParameters() {
        String header = "NAXIS   =                    2\n"
                + "CRPIX1  =                  1.0\n"
                + "CRPIX2  =                  1.0\n"
                + "PV2_1   =                  0.0\n"
                + "PV2_2   =                  0.0\n"
                + "END";
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(header), ' ', 2);
        assertEquals(0.0, store.pv[1][1]);
        assertEquals(0.0, store.pv[1][2]);
        assertNull(store.pv[0][0]);
    }

    /** C reads DTAI only when TELESCOP starts with JCMT or UKIRT (fitschan.c:37034-37046). */
    @Test
    void dtaiIsReadOnlyForJcmtOrUkirt() {
        String body = "CRPIX1  = 1.0\nDTAI    = 37.0\nDUT1    = 0.1\nEND";
        FitsStore store =
                FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader("TELESCOP= 'JCMT    '\n" + body), ' ', 1);
        assertEquals(37.0, store.dtai);
        assertEquals(0.1, store.dut1);
        store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader("TELESCOP= 'UKIRT'\n" + body), ' ', 1);
        assertEquals(37.0, store.dtai);
        store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader("TELESCOP= 'HST'\n" + body), ' ', 1);
        assertNull(store.dtai);
        store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(body), ' ', 1);
        assertNull(store.dtai);
    }

    /**
     * C has no secondary DUT1, DTAI or IMAGFREQ branch (fitschan.c:37025-37046, 37171-37178), and
     * reads the store for the alternate description's own letter (fitschan.c:38360-38365), so an
     * alternate description has none; SSYSSRC and ZSOURCE have secondary branches.
     */
    @Test
    void observationDetailsOfAnAlternateDescription() {
        String header = "TELESCOP= 'JCMT'\n"
                + "CRPIX1  = 1.0\n"
                + "DTAI    = 37.0\n"
                + "DUT1    = 0.1\n"
                + "DUT1A   = 0.2\n"
                + "IMAGFREQ= 1.0E9\n"
                + "SSYSSRC = 'BARYCENT'\n"
                + "SSYSSRCA= 'LSRK'\n"
                + "ZSOURCE = 0.5\n"
                + "MJD-OBS = 50000.0\n"
                + "OBSGEO-X= 1000.0\n"
                + "END";
        FitsCardList cards = FitsCardList.fromHeader(header);
        FitsStore primary = FitsWcsKeywords.extractWcsKeywords(cards, ' ', 1);
        assertEquals(0.1, primary.dut1);
        assertEquals(1.0e9, primary.imagfreq);
        assertEquals("BARYCENT", primary.ssyssrc);
        assertEquals(0.5, primary.zsource);
        FitsStore alt = FitsWcsKeywords.extractWcsKeywords(cards, 'A', 1);
        assertNull(alt.dut1);
        assertNull(alt.dtai);
        assertNull(alt.imagfreq);
        assertEquals("LSRK", alt.ssyssrc);
        assertNull(alt.zsource);
        // ChooseEpoch falls back to the primary MJD-OBS, and OBSGEO is read for the primary
        // description (fitschan.c:6355-6369, 39868-39870).
        assertEquals(50000.0, alt.mjdobs);
        assertEquals(1000.0, alt.obsgeox);
    }

    /** ChooseEpoch prefers MJD-AVG to MJD-OBS (fitschan.c:6355-6369); WcsFcRead does not read DATE-OBS. */
    @Test
    void epochPrefersMjdAvgAndIgnoresDateObs() {
        FitsStore store = new FitsStore(1);
        FitsWcsKeywords.readEpochIntoStore(
                FitsCardList.fromHeader("MJD-OBS = 50000.0\nMJD-AVG = 50000.5\nTIMESYS = 'TAI     '\nEND"), store, 10);
        assertEquals(50000.5, store.mjdobs);
        assertEquals("TAI", store.timesys);
        store = new FitsStore(1);
        FitsWcsKeywords.readEpochIntoStore(FitsCardList.fromHeader("MJD-OBS = 50000.0\nEND"), store, 10);
        assertEquals(50000.0, store.mjdobs);
        store = new FitsStore(1);
        FitsWcsKeywords.readEpochIntoStore(FitsCardList.fromHeader("DATE-OBS= '2000-01-01'\nEND"), store, 10);
        assertNull(store.mjdobs);
    }

    /** DistortMaps compares the end of each CTYPE with {@code -SIP} using strcmp (fitschan.c:9036-9041). */
    @Test
    void theSipCodeIsCaseSensitive() {
        String header = "CRPIX1  = 1.0\nCRPIX2  = 1.0\nCTYPE1  = 'RA---TAN-sip'\nCTYPE2  = 'DEC--TAN-sip'\n"
                + "A_2_0   = 1.0E-5\nEND";
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(header), ' ', 2);
        assertFalse(store.hasSip());
        assertEquals("RA---TAN-sip", store.ctype[0]);
        // A lone code on axis 1 is removed but gives no distortion.
        header = "CRPIX1  = 1.0\nCRPIX2  = 1.0\nCTYPE1  = 'RA---TAN-SIP'\nCTYPE2  = 'DEC--TAN'\nEND";
        store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(header), ' ', 2);
        assertFalse(store.hasSip());
        assertEquals("RA---TAN", store.ctype[0]);
    }

    /**
     * WcsFcRead stores each SIP coefficient card it meets with SetItem, which overwrites, so the
     * last of two cards for one coefficient is kept (fitschan.c:37362); an absent
     * coefficient is AST__BAD and a row with no coefficients is empty, as C's GetMaxI and GetMaxJM
     * see them.
     */
    @Test
    void sipCoefficientsKeepTheLastCard() {
        String header = "CRPIX1  = 1.0\nCRPIX2  = 1.0\nCTYPE1  = 'RA---TAN-SIP'\nCTYPE2  = 'DEC--TAN-SIP'\n"
                + "A_2_0   = 1.0E-5\nA_2_0   = 2.0E-5\nA_0_1   = '3.0E-5'\nB_1_1A  = 1.0\nEND";
        FitsStore store = FitsWcsKeywords.extractWcsKeywords(FitsCardList.fromHeader(header), ' ', 2);
        assertTrue(store.hasSip());
        assertEquals(3, store.sipA.length);
        assertEquals(0, store.sipA[1].length);
        assertTrue(Arrays.equals(new double[] {BAD, 3.0e-5}, store.sipA[0]));
        assertTrue(Arrays.equals(new double[] {2.0e-5}, store.sipA[2]));
        assertEquals(0, store.sipB.length);
    }

    /** A celestial header with {@code extra} cards after it. */
    private static FrameSet readWithDuplicates(String... extra) {
        String[] base = {
            "NAXIS1  =                  100",
            "NAXIS2  =                  100",
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
            "CRVAL2  =               45.000",
            "CRPIX1  =               50.000",
            "CRPIX2  =               50.000",
            "CDELT1  =              -0.0100",
            "CDELT2  =               0.0100",
            "RADESYS = 'FK5'",
            "EQUINOX =               2000.0"
        };
        String[] cards = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, cards, base.length, extra.length);
        return FitsHeaders.readFrameSet(FitsHeaders.chan(cards));
    }

    private static void assertReferencePoint(FrameSet fs, double lon, double lat) {
        double[] world = FitsHeaders.tran(fs, new double[] {50.0, 50.0}, true);
        assertTrue(CValues.cValueEq(world[0], lon), "longitude " + world[0] + ", C " + lon);
        assertTrue(CValues.cValueEq(world[1], lat), "latitude " + world[1] + ", C " + lat);
    }

    // C's WcsFcRead stores every card it can convert, in order, so a later card for a keyword
    // replaces the value of an earlier one, and a card it cannot convert stores nothing
    // (fitschan.c:37357-37373). The expected values are the pinned C library's.

    @Test
    void aLaterCardForAKeywordGivesItsValue() {
        assertReferencePoint(
                readWithDuplicates("CRVAL1  =              180.000", "CRVAL1  =              190.000"),
                -2.967059728390361,
                0.7853981633974484);
    }

    @Test
    void aLaterCardThatDoesNotConvertIsPassedOver() {
        assertReferencePoint(
                readWithDuplicates("CRVAL1  =              180.000", "CRVAL1  = 'abc'"),
                3.141592653589793,
                0.7853981633974484);
    }

    @Test
    void aLaterStringCardGivesItsValue() {
        FrameSet fs = readWithDuplicates("RADESYS = 'FK4'", "CRVAL1  =              180.000");
        assertEquals("FK4", fs.getC("System"));
    }
}
