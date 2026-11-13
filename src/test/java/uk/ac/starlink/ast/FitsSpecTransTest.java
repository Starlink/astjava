package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the unit tests of Rust {@code fitschan/spec_trans.rs}: the standard equivalents of
 * non-standard FITS-WCS keywords (fitschan.c {@code SpecTrans}).
 *
 * <p>Many Rust tests run under FITS-PC although the translation they test is not gated on the
 * encoding; those run here under FITS-WCS, which C translates the same way. The tests of the
 * translations only FITS-PC, FITS-IRAF and FITS-CLASS make are deferred, since those encodings are
 * not read. C marks the cards a translation reads as used rather than deleting them, so where Rust
 * asserts a card has gone, these tests assert it was consumed. The tests after the translated ones
 * check behaviour of C that the Rust port does not share, each citing the C it follows.
 */
class FitsSpecTransTest {

    private static FitsCardList run(String header, FitsSpecTrans.TransAttrs attrs, FitsSpecTrans.TransWarnings w) {
        FitsCardList cards = FitsCardList.fromHeader(header);
        FitsSpecTrans.specTransInplace(cards, FitsChan.Encoding.FITS_WCS, attrs, w);
        return cards;
    }

    private static FitsCardList wcs(String header) {
        return run(header, new FitsSpecTrans.TransAttrs(), new FitsSpecTrans.TransWarnings());
    }

    private static boolean consumed(FitsCardList cards, String keyword) {
        return cards.takeConsumedNames().contains(keyword);
    }

    @Test
    void specTransIsANoOpWhenAllKeywordsAreAlreadyStandard() {
        FitsCardList cards = FitsCardList.fromHeader("CRVAL1  =                180.0\n"
                + "CRVAL2  =                 45.0\n"
                + "CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "RADESYS = 'FK5     '\n"
                + "EQUINOX =               2000.0\n"
                + "END");
        int before = cards.len();
        FitsSpecTrans.specTransInplace(
                cards, FitsChan.Encoding.FITS_WCS, new FitsSpecTrans.TransAttrs(), new FitsSpecTrans.TransWarnings());
        assertEquals(before, cards.len());
        assertEquals("FK5     ", cards.getString("RADESYS"));
    }

    @Test
    void zeroCdeltIsReplacedWithOne() {
        FitsCardList cards = wcs("CTYPE1  = 'WAVE    '\n"
                + "CTYPE2  = 'RA---TAN'\n"
                + "CTYPE3  = 'DEC--TAN'\n"
                + "CDELT1  =            1.000E-10\n"
                + "CDELT2  =                  0.0\n"
                + "CDELT3  =                  0.0\n"
                + "END");
        // A zero CDELT becomes 1.0, since 1.0E-6 times the zero CDELT is still zero
        // (fitschan.c:31700-31706).
        assertEquals(1.0e-10, cards.getFloat("CDELT1"));
        assertEquals(1.0, cards.getFloat("CDELT2"));
        assertEquals(1.0, cards.getFloat("CDELT3"));
    }

    @Test
    void zeroCdeltHandlesAlternateVersions() {
        FitsCardList cards = wcs("CDELT1A =                  0.0\nCDELT2A =                  2.5\nEND");
        assertEquals(1.0, cards.getFloat("CDELT1A"));
        assertEquals(2.5, cards.getFloat("CDELT2A"));
    }

    @Test
    void cdjjjiiiSurvivesAnEncodingThatDoesNotTranslateIt() {
        // Only FITS-IRAF turns CDjjjiii into a PC matrix (fitschan.c:31735), so under FITS-WCS the
        // cards are neither read nor consumed.
        FitsCardList cards = wcs("CD001001=                  1.0\nCD002002=                  1.0\nEND");
        assertNotNull(cards.find("CD001001"));
        assertNotNull(cards.find("CD002002"));
        assertNull(cards.find("PC1_1"));
        assertFalse(consumed(cards, "CD001001"));
    }

    @Test
    void translatesCrotaToPcMatrix() {
        // CROTA2 = 30 degrees, CDELT2/CDELT1 = -1, so lambda = -1.
        FitsCardList cards = wcs("CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "CRPIX1  =                  0.0\n"
                + "CRPIX2  =                  0.0\n"
                + "CRVAL1  =                  0.0\n"
                + "CRVAL2  =                  0.0\n"
                + "CDELT1  =                 -1.0\n"
                + "CDELT2  =                  1.0\n"
                + "CROTA2  =                 30.0\n"
                + "END");
        assertTrue(consumed(cards, "CROTA2"));
        double cos30 = Math.cos(Math.toRadians(30.0));
        assertTrue(Math.abs(cards.getFloat("PC1_1") - cos30) < 1e-10);
        assertTrue(Math.abs(cards.getFloat("PC2_2") - cos30) < 1e-10);
        assertEquals(-1.0, cards.getFloat("CDELT1"));
        assertEquals(1.0, cards.getFloat("CDELT2"));
    }

    @Test
    void crotaSurvivesWhenPcAlreadyPresent() {
        // The CROTA reads are inside the !gotpcij gate (fitschan.c:31723-31724).
        FitsCardList cards = wcs("CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "CDELT1  =                  1.0\n"
                + "CDELT2  =                  1.0\n"
                + "PC1_1   =                  1.0\n"
                + "PC2_2   =                  1.0\n"
                + "CROTA2  =                 30.0\n"
                + "END");
        assertEquals(30.0, cards.getFloat("CROTA2"));
        assertFalse(consumed(cards, "CROTA2"));
        assertEquals(1.0, cards.getFloat("PC1_1"));
    }

    @Test
    void crotaSuppressedByPcOnANonCelestialAxis() {
        // The gate is a template sweep over every card, so a PC keyword naming only the spectral
        // axis still suppresses the CROTA translation.
        FitsCardList cards = wcs("CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "CTYPE3  = 'FREQ'\n"
                + "CDELT1  =                 -1.0\n"
                + "CDELT2  =                  1.0\n"
                + "CDELT3  =                  1.0\n"
                + "PC3_3   =                  1.0\n"
                + "CROTA2  =                 30.0\n"
                + "END");
        assertEquals(30.0, cards.getFloat("CROTA2"));
        assertNull(cards.find("PC1_1"));
        assertNull(cards.find("PC1_2"));
        assertNull(cards.find("PC2_1"));
        assertNull(cards.find("PC2_2"));
        assertEquals(1.0, cards.getFloat("PC3_3"));
    }

    @Test
    void projpSkippedWhenConsistentPvSetPresent() {
        // PV1_0, PV1_1 and PV1_2 on the longitude axis: C trusts the PV values and does not read
        // PROJP (fitschan.c:31910-31947).
        FitsCardList cards = wcs("CTYPE1  = 'RA---ZPN'\n"
                + "CTYPE2  = 'DEC--ZPN'\n"
                + "PROJP0  =                999.0\n"
                + "PV1_0   =                  0.0\n"
                + "PV1_1   =                  1.0\n"
                + "PV1_2   =                 -0.4\n"
                + "END");
        assertNotNull(cards.find("PROJP0"));
        assertNull(cards.find("PV2_0"));
        assertFalse(consumed(cards, "PROJP0"));
    }

    @Test
    void cnxxxTranslationSkippedForFitsWcs() {
        // C reads CmYPEi under every encoding but writes CTYPEis only for FITS-PC and FITS-IRAF
        // (fitschan.c:32002-32020).
        FitsCardList cards = wcs("C1YPE1  = 'GLON-TAN'\nEND");
        assertNotNull(cards.find("C1YPE1"));
        assertNull(cards.find("CTYPE1A"));
        assertTrue(consumed(cards, "C1YPE1"));
    }

    @Test
    void translatesEpochToEquinox() {
        FitsCardList cards = wcs("EPOCH   =               1995.0\nEND");
        assertTrue(consumed(cards, "EPOCH"));
        assertEquals(1995.0, cards.getFloat("EQUINOX"));
    }

    @Test
    void epochZeroBecomesB1950Equinox() {
        FitsCardList cards = wcs("EPOCH   =                  0.0\nEND");
        assertEquals(1950.0, cards.getFloat("EQUINOX"));
    }

    @Test
    void epochConsumedWhenEquinoxPresent() {
        FitsCardList cards = wcs("EPOCH   =               1995.0\nEQUINOX =               2000.0\nEND");
        assertTrue(consumed(cards, "EPOCH"));
        assertEquals(2000.0, cards.getFloat("EQUINOX"));
    }

    @Test
    void equinoxZeroBecomesB1950() {
        FitsCardList cards = wcs("EQUINOX =                  0.0\nEND");
        assertEquals(1950.0, cards.getFloat("EQUINOX"));
    }

    @Test
    void translatesEquinoxB1995StringToJulianNumber() {
        FitsCardList cards = wcs("EQUINOX = 'B1995.0'\nEND");
        // C: dval = palEpj( palEpb2d( dval ) ) (fitschan.c:32136).
        assertEquals(Pal.epj(Pal.epb2d(1995.0)), cards.getFloat("EQUINOX"));
    }

    @Test
    void translatesEquinoxJ2000StringToPlainNumber() {
        FitsCardList cards = wcs("EQUINOX = 'J2000.0'\nEND");
        assertEquals(2000.0, cards.getFloat("EQUINOX"));
    }

    @Test
    void translatesDateObsToMjdObs() {
        FitsCardList cards = wcs("DATE-OBS= '2000-01-15T12:00:00'\nEND");
        assertTrue(Math.abs(cards.getFloat("MJD-OBS") - 51558.5) < 1e-6);
        assertNotNull(cards.find("DATE-OBS"));
    }

    @Test
    void dateObsSkippedWhenMjdObsPresent() {
        FitsCardList cards = wcs("DATE-OBS= '2000-01-15T12:00:00'\nMJD-OBS =              50000.0\nEND");
        assertEquals(50000.0, cards.getFloat("MJD-OBS"));
        // MJD-OBS is read only as a test for its presence (fitschan.c:32170-32175).
        List<String> names = cards.takeConsumedNames();
        assertTrue(names.contains("DATE-OBS"));
        assertFalse(names.contains("MJD-OBS"));
    }

    @Test
    void translatesNcpToSinWithPv22() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---NCP'\nCTYPE2  = 'DEC--NCP'\nCRVAL2  =                 30.0\nEND");
        assertEquals("RA---SIN", cards.getString("CTYPE1"));
        assertEquals("DEC--SIN", cards.getString("CTYPE2"));
        double cot30 = 1.0 / Math.tan(Math.toRadians(30.0));
        assertTrue(Math.abs(cards.getFloat("PV2_2") - cot30) < 1e-10);
        assertEquals(0.0, cards.getFloat("PV2_1"));
    }

    @Test
    void translatesGlsToSfl() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---GLS'\nCTYPE2  = 'DEC--GLS'\nEND");
        assertEquals("RA---SFL", cards.getString("CTYPE1"));
        assertEquals("DEC--SFL", cards.getString("CTYPE2"));
    }

    @Test
    void ncpAtEquatorLeftAlone() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---NCP'\nCTYPE2  = 'DEC--NCP'\nCRVAL2  =                  0.0\nEND");
        assertEquals("DEC--NCP", cards.getString("CTYPE2"));
    }

    @Test
    void earlyPaperIiiWaveFrqBecomesWaveF2w() {
        assertEquals("WAVE-F2W", wcs("CTYPE3  = 'WAVE-FRQ'\nEND").getString("CTYPE3"));
    }

    @Test
    void earlyPaperIiiVeloVelRedundantBlanks() {
        assertEquals("VELO    ", wcs("CTYPE3  = 'VELO-VEL'\nEND").getString("CTYPE3"));
    }

    @Test
    void translatesSaoCoToPvAndTpv() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "CO1_1   =                  0.0\n"
                + "CO1_2   =                  1.0\n"
                + "CO1_3   =                  0.0\n"
                + "CO1_5   =                  0.5\n"
                + "CO2_1   =                  0.0\n"
                + "CO2_2   =                  1.0\n"
                + "CO2_3   =                  0.0\n"
                + "CO2_5   =                  0.7\n"
                + "END");
        assertEquals("RA---TPN", cards.getString("CTYPE1"));
        assertEquals("DEC--TPN", cards.getString("CTYPE2"));
        assertEquals(1.0, cards.getFloat("PV1_1"));
        assertEquals(1.0, cards.getFloat("PV2_2"));
        assertEquals(1.0, cards.getFloat("PC1_1"));
        assertEquals(1.0, cards.getFloat("CDELT1"));
    }

    @Test
    void translatesAtfToAit() {
        // The ATF translation has no encoding test (fitschan.c:32245-32257).
        FitsCardList cards = wcs("CTYPE1  = 'RA---ATF'\nCTYPE2  = 'DEC--ATF'\nEND");
        assertEquals("RA---AIT", cards.getString("CTYPE1"));
        assertEquals("DEC--AIT", cards.getString("CTYPE2"));
    }

    @Test
    void translatesQvToPvAndTanToTpn() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---TAN'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "QV2_1   =                  0.5\n"
                + "QV2_2   =                  0.7\n"
                + "END");
        assertEquals("RA---TPN", cards.getString("CTYPE1"));
        assertEquals("DEC--TPN", cards.getString("CTYPE2"));
        assertEquals(0.5, cards.getFloat("PV2_1"));
        assertEquals(0.7, cards.getFloat("PV2_2"));
        assertTrue(consumed(cards, "QV2_1"));
    }

    @Test
    void translatesTpvToTpn() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---TPV'\nCTYPE2  = 'DEC--TPV'\nEND");
        assertEquals("RA---TPN", cards.getString("CTYPE1"));
        assertEquals("DEC--TPN", cards.getString("CTYPE2"));
    }

    @Test
    void translatesZpxToZpnAndExtractsProjp() {
        FitsCardList cards = wcs(
                "CTYPE1  = 'RA---ZPX'\n" + "CTYPE2  = 'DEC--ZPX'\n" + "WAT2_001= 'projp1=1.0 projp2=-0.4'\n" + "END");
        assertEquals("RA---ZPN-ZPX", cards.getString("CTYPE1"));
        assertEquals(1.0, cards.getFloat("PV2_1"));
        assertEquals(-0.4, cards.getFloat("PV2_2"));
    }

    @Test
    void translatesTnxToTpn() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---TNX'\nCTYPE2  = 'DEC--TNX'\nEND");
        assertEquals("RA---TPN", cards.getString("CTYPE1"));
        assertEquals("DEC--TPN", cards.getString("CTYPE2"));
    }

    @Test
    void translatesMsxCarWrapsCrpix() {
        FitsCardList cards = wcs("CTYPE1  = 'RA---CAR'\n"
                + "CTYPE2  = 'DEC--CAR'\n"
                + "NAXIS1  =                  100\n"
                + "CRPIX1  =               1000.0\n"
                + "CDELT1  =                 -1.0\n"
                + "END");
        double crpix1 = cards.getFloat("CRPIX1");
        assertTrue(Math.abs(crpix1) < 250.0, "CRPIX1 not wrapped: " + crpix1);
    }

    @Test
    void carlinSuppressesTheMsxCarWrap() {
        // car4.head has CRPIX1 = 211076 and a CD matrix; C takes the longitude CDELT from the
        // translation of the CD matrix and gates the wrap on !CarLin (fitschan.c:32473).
        String text = Fixtures.read("wcsconv/inputs/car4.head");
        FitsCardList wrapped = run(text, new FitsSpecTrans.TransAttrs(), new FitsSpecTrans.TransWarnings());
        assertNotEquals(211076.0, wrapped.getFloat("CRPIX1"));
        FitsSpecTrans.TransAttrs carLin = new FitsSpecTrans.TransAttrs();
        carLin.carLin = true;
        FitsCardList kept = run(text, carLin, new FitsSpecTrans.TransWarnings());
        assertEquals(211076.0, kept.getFloat("CRPIX1"));
    }

    @Test
    void translatesRestfreqToRestfrqWithGhzScale() {
        FitsCardList cards = wcs("RESTFREQ=                1.42 / Rest frequency in GHz\nEND");
        assertTrue(consumed(cards, "RESTFREQ"));
        assertTrue(Math.abs(cards.getFloat("RESTFRQ") - 1.42e9) < 1.0);
    }

    @Test
    void translatesVeloLsrToVradPlusSpecsys() {
        FitsCardList cards = wcs("CTYPE3  = 'VELO-LSR'\nEND");
        assertEquals("VRAD    ", cards.getString("CTYPE3"));
        assertEquals("LSRK", cards.getString("SPECSYS"));
    }

    @Test
    void translatesLambdaToWave() {
        assertEquals("WAVE", wcs("CTYPE3  = 'LAMBDA  '\nEND").getString("CTYPE3"));
    }

    @Test
    void translatesCunitCaseInsensitively() {
        // C compares the whole value without regard to case but with its trailing spaces
        // (Ustrcmp, fitschan.c:32597-32604), so only an unpadded value is translated.
        FitsCardList cards =
                wcs("CUNIT1  = 'HZ'\n" + "CUNIT2  = 'M/S'\n" + "CUNIT3  = 'KM/S'\n" + "CUNIT4  = 'HZ      '\n" + "END");
        assertEquals("Hz", cards.getString("CUNIT1"));
        assertEquals("m/s", cards.getString("CUNIT2"));
        assertEquals("km/s", cards.getString("CUNIT3"));
        assertEquals("HZ      ", cards.getString("CUNIT4"));
    }

    @Test
    void irafMiniWcsKeywordsStripped() {
        // C marks WAT%d_%d, LTM%d_%d, LTV%d, WSV%d_LEN and WSV%d_%d (fitschan.c:32618-32637), not MWCS.
        FitsCardList cards = wcs("WAT1_001= 'foo'\n"
                + "WAT2_001= 'bar'\n"
                + "MWCS1   =                   1\n"
                + "LTM1_1  =                  1.0\n"
                + "LTV1    =                  0.0\n"
                + "WSV1_LEN=                   3\n"
                + "END");
        List<String> names = cards.takeConsumedNames();
        for (String k : new String[] {"WAT1_001", "WAT2_001", "LTM1_1", "LTV1", "WSV1_LEN"}) {
            assertTrue(names.contains(k), k);
        }
        assertFalse(names.contains("MWCS1"));
    }

    // ------------------------------------------------------------------
    // C behaviour beyond the Rust tests
    // ------------------------------------------------------------------

    @Test
    void encodingsThatAreNotReadThrow() {
        for (FitsChan.Encoding e : new FitsChan.Encoding[] {
            FitsChan.Encoding.FITS_PC, FitsChan.Encoding.FITS_IRAF, FitsChan.Encoding.FITS_CLASS
        }) {
            UnsupportedOperationException ex = assertThrows(
                    UnsupportedOperationException.class,
                    () -> FitsSpecTrans.specTransInplace(
                            FitsCardList.fromHeader("END"),
                            e,
                            new FitsSpecTrans.TransAttrs(),
                            new FitsSpecTrans.TransWarnings()));
            assertEquals("FitsChan: reading the " + e.cName() + " encoding is not available", ex.getMessage());
        }
    }

    @Test
    void radecsysAndLongpoleAreConsumedUnderFitsWcs() {
        // C reads both under every encoding, and writes RADESYS and LONPOLE only for FITS-PC and
        // FITS-IRAF (fitschan.c:31659-31675).
        FitsCardList cards = wcs("RADECSYS= 'FK5'\nLONGPOLE=                180.0\nEND");
        List<String> names = cards.takeConsumedNames();
        assertTrue(names.contains("RADECSYS"));
        assertTrue(names.contains("LONGPOLE"));
        assertNull(cards.find("RADESYS"));
        assertNull(cards.find("LONPOLE"));
    }

    @Test
    void cdMatrixSizeIsTheHighestColumnIndex() {
        // astKeyFields( this, "CD%d_%d", 1, &naxis, ... ) (fitschan.c:31767) returns one field, and
        // C's Match stores the fields from the last (fitschan.c:22790), so naxis is the highest
        // column index: a matrix with one column is a 1x1 matrix.
        FitsCardList cards = wcs("CD1_1   =                  2.0\nCD2_1   =                  1.0\nEND");
        assertEquals(2.0, cards.getFloat("CDELT1"));
        assertEquals(1.0, cards.getFloat("PC1_1"));
        assertNull(cards.find("CDELT2"));
        assertNull(cards.find("PC2_1"));
        assertFalse(consumed(cards, "CD2_1"));
    }

    @Test
    void crotaIsTranslatedOnlyForThePrimaryDescription() {
        // fitschan.c:31862 requires s == ' '; the alternate CROTA is still read.
        FitsCardList cards = wcs("CTYPE1A = 'RA---TAN'\n"
                + "CTYPE2A = 'DEC--TAN'\n"
                + "CDELT1A =                 -1.0\n"
                + "CDELT2A =                  1.0\n"
                + "CROTA2A =                 30.0\n"
                + "END");
        assertNull(cards.find("PC1_1A"));
        assertTrue(consumed(cards, "CROTA2A"));
    }

    @Test
    void crotaNeedsCdeltOnBothCelestialAxes() {
        // The CDELT values are read with report set (fitschan.c:31870-31875).
        AstException e = assertThrows(
                AstException.class,
                () -> wcs("CTYPE1  = 'RA---TAN'\n"
                        + "CTYPE2  = 'DEC--TAN'\n"
                        + "CDELT1  =                 -1.0\n"
                        + "CROTA2  =                 30.0\n"
                        + "END"));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(e.getMessage().startsWith("astRead(FitsChan): Unable to find a value for FITS keyword \"CDELT2\"."));
    }

    @Test
    void cmeltIsTranslatedUnderEveryEncoding() {
        // The CmELTi block writes CDELTis and PCj_is with no encoding test (fitschan.c:32042-32098),
        // while CmVALi is only read (fitschan.c:31962-31980).
        FitsCardList cards = wcs("C1VAL1  =                 10.0\n"
                + "C1ELT1  =                  2.0\n"
                + "C1ELT2  =                  3.0\n"
                + "END");
        assertEquals(2.0, cards.getFloat("CDELT1A"));
        assertEquals(3.0, cards.getFloat("CDELT2A"));
        assertEquals(1.0, cards.getFloat("PC1_1A"));
        assertEquals(0.0, cards.getFloat("PC1_2A"));
        assertNull(cards.find("CRVAL1A"));
        assertTrue(consumed(cards, "C1VAL1"));
    }

    @Test
    void zeroPolyTanCoefficientsWarnAndAreDeleted() {
        // GetUsedPolyTan (fitschan.c:14104): every latitude PVi_m is zero, so a warning is raised,
        // the PV cards are deleted and the projection stays TAN.
        FitsSpecTrans.TransWarnings w = new FitsSpecTrans.TransWarnings();
        FitsCardList cards = run(
                "CTYPE1  = 'RA---TAN'\n"
                        + "CTYPE2  = 'DEC--TAN'\n"
                        + "PV2_1   =                  0.0\n"
                        + "PV2_2   =                  0.0\n"
                        + "END",
                new FitsSpecTrans.TransAttrs(),
                w);
        assertEquals("RA---TAN", cards.getString("CTYPE1"));
        assertNull(cards.find("PV2_1"));
        assertNull(cards.find("PV2_2"));
        assertTrue(w.entry.isEmpty());
        assertEquals(1, w.end.size());
        assertEquals("badpv", w.end.get(0).condition);
    }

    @Test
    void strictTurnsATranslationWarningIntoAnError() {
        FitsCardList cards = FitsCardList.fromHeader(
                "CTYPE1  = 'RA---TAN'\n" + "CTYPE2  = 'DEC--TAN'\n" + "PV2_1   =                  0.0\n" + "END");
        AstException e = assertThrows(
                AstException.class,
                () -> FitsSpecTrans.specTrans(
                        cards,
                        FitsChan.Encoding.FITS_WCS,
                        new FitsSpecTrans.TransAttrs(),
                        new FitsSpecTrans.TransWarnings(),
                        FitsWarnings.strict("BadPV", "astRead")));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    @Test
    void specTransLeavesTheSuppliedCardsAndRecordsWhatItWrites() {
        FitsCardList cards = FitsCardList.fromHeader("CTYPE1  = 'RA---TPV'\nCTYPE2  = 'DEC--TPV'\nEND");
        FitsCardList trans = FitsSpecTrans.specTrans(
                cards,
                FitsChan.Encoding.FITS_WCS,
                new FitsSpecTrans.TransAttrs(),
                new FitsSpecTrans.TransWarnings(),
                new FitsWarnings());
        assertEquals("RA---TPV", cards.getString("CTYPE1"));
        assertEquals("RA---TPN", trans.getString("CTYPE1"));
        List<String> written = trans.takeWrittenNames();
        assertTrue(written.contains("CTYPE1"));
        assertTrue(written.contains("CTYPE2"));
    }

    @Test
    void tnxSurfaceThatIsNotATpnIsWarnedOf() {
        // A legendre surface (type 2) cannot be a TPN (fitschan.c WATCoeffs), so the tnx warning
        // is raised at end-of-file (fitschan.c:32460).
        FitsSpecTrans.TransWarnings w = new FitsSpecTrans.TransWarnings();
        run(
                "CTYPE1  = 'RA---TNX'\n"
                        + "CTYPE2  = 'DEC--TNX'\n"
                        + "WAT1_001= 'wtype=tnx axtype=ra lngcor = \"2 2 2 2 0 1 0 1 0 0 0\"'\n"
                        + "END",
                new FitsSpecTrans.TransAttrs(),
                w);
        assertEquals(1, w.end.size());
        assertEquals("tnx", w.end.get(0).condition);
    }

    @Test
    void watCoeffsOfASimplePolynomial() {
        // Surface type 3, order 2, half cross-terms: the coefficients of 1, xi and eta, with the
        // linear term on the longitude axis increased by one (fitschan.c WATCoeffs).
        FitsSpecTrans.WatCoeffs c = FitsSpecTrans.watCoeffs("lngcor = \"3 2 2 2 0 0 0 0 0.1 0.2 0.3\"", 0);
        assertTrue(c.ok);
        assertArrayEquals(new int[] {0, 1, 2}, c.m);
        assertArrayEquals(new double[] {0.1, 1.2, 0.3}, c.value);
        FitsSpecTrans.WatCoeffs none = FitsSpecTrans.watCoeffs("wtype=tnx", 0);
        assertTrue(none.ok);
        assertEquals(0, none.m.length);
    }

    @Test
    void watCoeffsWithAWordThatIsNotANumberIsAnError() {
        AstException e = assertThrows(AstException.class, () -> FitsSpecTrans.watCoeffs("lngcor = \"3 2 2 abc\"", 0));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
    }

    @Test
    void concatWatJoinsTheCardsOfAnAxisInOrder() {
        FitsCardList cards = FitsCardList.fromHeader("WAT1_001= 'abc '\nWAT1_002= 'def'\nWAT2_001= 'xyz'\nEND");
        assertEquals("abc def", FitsSpecTrans.concatWat(cards, 1));
        assertEquals("xyz", FitsSpecTrans.concatWat(cards, 2));
        assertEquals("", FitsSpecTrans.concatWat(cards, 3));
    }
}
