package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of the tests of Rust {@code fitschan/enc_aips.rs}: C's {@code AIPSFromStore} and
 * {@code AIPSPPFromStore}, which write the primary axis description as FITS-AIPS and FITS-AIPS++
 * cards, and the reads of AIPS headers, whose translations to FITS-WCS C's {@code SpecTrans} makes
 * whatever the encoding.
 *
 * <p>{@code read_aips_spectral_felo_geo} is not translated: its header reads as a SpecFrame, which
 * this library does not have. Rust's {@code write_aipspp_with_lonpole_latpole} expects no LONPOLE
 * card for a LONPOLE of 180 degrees; C's {@code AIPSPPFromStore} writes any LONPOLE stored, so the
 * translation expects C's card, as C probe {@code p2.c} shows for a FrameSet. The values of the
 * reads through a FitsChan come from C probe {@code p1.c} (probes beside the task report).
 */
class FitsEncAipsTest {

    /** The primary axis description C's read makes of {@code header}: {@code SpecTrans}, then {@code WcsToStore}. */
    private static FitsStore readStore(String header) {
        FitsCardList cards = FitsCardList.fromHeader(header);
        FitsSpecTrans.specTransInplace(
                cards, FitsChan.Encoding.FITS_AIPS, new FitsSpecTrans.TransAttrs(), new FitsSpecTrans.TransWarnings());
        FitsStore store = FitsWcsKeywords.extractWcsKeywordsWithAlts(cards).primary;
        assertNotNull(store, "a primary axis description");
        return store;
    }

    /** The stores of an image of {@code n} pixel axes, with a primary description of {@code n} axes. */
    private static FitsPlaneStores axes(int n) {
        FitsPlaneStores stores = new FitsPlaneStores(n);
        stores.version(' ', n);
        return stores;
    }

    /** The cards {@code AIPSFromStore} writes for {@code stores}, which must be written. */
    private static FitsCardList aips(FitsPlaneStores stores) {
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncAips.aipsFromStore(fc, stores, "astWrite", "FitsChan"));
        return fc.cards();
    }

    /** The cards {@code AIPSPPFromStore} writes for {@code stores}, which must be written. */
    private static FitsCardList aipspp(FitsPlaneStores stores) {
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncAips.aipsppFromStore(fc, stores, "astWrite", "FitsChan"));
        return fc.cards();
    }

    /** A FitsChan holding the lines of {@code header}, rewound, with Encoding {@code enc}. */
    private static FitsChan chan(String header, String enc) {
        FitsChan fc = FitsHeaders.chanFromLines(header);
        fc.setEncoding(enc);
        return fc;
    }

    private static final String CROTA_HEADER = "NAXIS   =                    2 / Number of axes\n"
            + "CRPIX1  =              512.0   / Reference pixel\n"
            + "CRPIX2  =              512.0   / Reference pixel\n"
            + "CRVAL1  =          180.000000  / Reference value\n"
            + "CRVAL2  =          -45.000000  / Reference value\n"
            + "CDELT1  =        -0.0002778    / Pixel scale\n"
            + "CDELT2  =         0.0002778    / Pixel scale\n"
            + "CTYPE1  = 'RA---TAN'           / Axis type\n"
            + "CTYPE2  = 'DEC--TAN'           / Axis type\n";

    @Test
    void readAipsCrota() {
        // SpecTrans turns CROTA2 into a PC matrix on the celestial axes, with lambda = CDELT2 /
        // CDELT1 = -1: PC2_1 = sin / lambda and PC1_2 = -sin * lambda.
        FitsStore store = readStore(CROTA_HEADER
                + "CROTA2  =               30.0   / Rotation angle\n"
                + "EPOCH   =             2000.0   / Epoch\n"
                + "END");
        assertEquals(2, store.naxis);
        double cos30 = Math.cos(Math.toRadians(30.0));
        double sin30 = Math.sin(Math.toRadians(30.0));
        double lambda = 0.0002778 / -0.0002778;
        assertTrue(Math.abs(store.pc[0][0] - cos30) < 1e-10, "PC1_1 = cos(30)");
        assertTrue(Math.abs(store.pc[0][1] - -sin30 * lambda) < 1e-10, "PC1_2 = -sin(30) * lambda");
        assertTrue(Math.abs(store.pc[1][0] - sin30 / lambda) < 1e-10, "PC2_1 = sin(30) / lambda");
        assertTrue(Math.abs(store.pc[1][1] - cos30) < 1e-10, "PC2_2 = cos(30)");
    }

    @Test
    void readAipsEpoch() {
        // C probe p1 (a): EPOCH is the equinox, 0 meaning 1950, FK4 before 1984 and FK5 after.
        String[][] cases = {{"2000.0", "FK5", "2000.0"}, {"1950.0", "FK4", "1950.0"}, {"0.0", "FK4", "1950.0"}};
        for (String[] c : cases) {
            String header = "NAXIS   =                    2\n"
                    + "CRPIX1  =              512.0\n"
                    + "CRPIX2  =              512.0\n"
                    + "CRVAL1  =          180.000000\n"
                    + "CRVAL2  =          -45.000000\n"
                    + "CDELT1  =        -0.0002778\n"
                    + "CDELT2  =         0.0002778\n"
                    + "CTYPE1  = 'RA---TAN'\n"
                    + "CTYPE2  = 'DEC--TAN'\n"
                    + String.format("EPOCH   = %20s", c[0]);
            FrameSet fs = FitsHeaders.readFrameSet(chan(header, "FITS-AIPS"));
            assertEquals(c[1], fs.getC("System"), "EPOCH " + c[0]);
            assertEquals(Double.parseDouble(c[2]), fs.getD("Equinox"), "EPOCH " + c[0]);
        }
    }

    @Test
    void readAipsppLatitudeWithoutLongitudeFails() {
        // C probe p1 (b): the read reports an error.
        String header = "NAXIS   =                    2\n"
                + "CRPIX1  =              256.0\n"
                + "CRPIX2  =              256.0\n"
                + "CRVAL1  =          180.000000\n"
                + "CRVAL2  =          -30.000000\n"
                + "CTYPE1  = 'VELO-LSR'\n"
                + "CTYPE2  = 'DEC--TAN'\n"
                + "CD1_1   =           1000.0\n"
                + "CD2_2   =            0.001\n"
                + "EQUINOX =             2000.0\n"
                + "LONPOLE =              180.0";
        AstException e = assertThrows(
                AstException.class, () -> chan(header, "FITS-AIPS++").read());
        assertTrue(
                e.getMessage()
                        .contains("A latitude axis ('DEC--TAN') was found without a corresponding longitude axis."),
                e.getMessage());
    }

    @Test
    void readAipsSpectralVeloLsr() {
        FitsStore store = readStore("NAXIS   =                    1 / Number of axes\n"
                + "CRPIX1  =                1.0   / Reference pixel\n"
                + "CRVAL1  =            10000.0   / Reference value\n"
                + "CDELT1  =             1000.0   / Channel width\n"
                + "CTYPE1  = 'VELO-LSR'           / Axis type\n"
                + "END");
        assertEquals("VRAD", store.ctype[0]);
        assertEquals("LSRK", store.specsys);
    }

    @Test
    void readAipsSpectralFreqHel() {
        FitsStore store = readStore("NAXIS   =                    1 / Number of axes\n"
                + "CRPIX1  =                1.0   / Reference pixel\n"
                + "CRVAL1  =         1.4e9        / Reference value\n"
                + "CDELT1  =         1.0e6        / Channel width\n"
                + "CTYPE1  = 'FREQ-HEL'           / Axis type\n"
                + "END");
        assertEquals("FREQ", store.ctype[0]);
        assertEquals("BARYCENT", store.specsys);
    }

    @Test
    void readAipsExplicitRadesysOverrides() {
        FitsStore store = readStore(CROTA_HEADER
                + "EPOCH   =             2000.0   / Epoch\n"
                + "RADESYS = 'ICRS'               / Reference frame\n"
                + "END");
        assertEquals(2000.0, store.equinox);
        assertEquals("ICRS", store.radesys);
    }

    @Test
    void writeAipsCrota() {
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 512.0;
        store.crpix[1] = 512.0;
        store.crval[0] = 180.0;
        store.crval[1] = -45.0;
        store.cdelt[0] = 0.0002778;
        store.cdelt[1] = 0.0002778;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        double angle = Math.toRadians(30.0);
        store.pc[0][0] = Math.cos(angle);
        store.pc[0][1] = -Math.sin(angle);
        store.pc[1][0] = Math.sin(angle);
        store.pc[1][1] = Math.cos(angle);
        store.equinox = 2000.0;

        FitsCardList cards = aips(stores);
        double crota2 = cards.getFloat("CROTA2");
        assertTrue(Math.abs(crota2 - 30.0) < 1e-10, "CROTA2 should be ~30, got " + crota2);
        assertNull(cards.getFloat("PC1_1"));
        assertNull(cards.getFloat("CD1_1"));
    }

    @Test
    void writeAipsEpoch() {
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 100.0;
        store.crpix[1] = 100.0;
        store.crval[0] = 45.0;
        store.crval[1] = 60.0;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.equinox = 2000.0;

        FitsCardList cards = aips(stores);
        assertEquals(2000.0, cards.getFloat("EPOCH"));
        assertNull(cards.getFloat("EQUINOX"));
    }

    @Test
    void writeAipsSpectral() {
        FitsPlaneStores stores = axes(1);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 1.0;
        store.crval[0] = 10000.0;
        store.cdelt[0] = 1000.0;
        store.ctype[0] = "VRAD";
        store.cunit[0] = "m/s";
        store.specsys = "LSRK";

        assertEquals("VELO-LSR", aips(stores).getString("CTYPE1"));
    }

    @Test
    void writeAipsSpectralFreqBarycent() {
        FitsPlaneStores stores = axes(1);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 1.0;
        store.crval[0] = 1.4e9;
        store.cdelt[0] = 1.0e6;
        store.ctype[0] = "FREQ";
        store.cunit[0] = "Hz";
        store.specsys = "BARYCENT";

        assertEquals("FREQ-HEL", aips(stores).getString("CTYPE1"));
    }

    @Test
    void writeAipsppCdeltForDiagonalCd() {
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 256.0;
        store.crpix[1] = 256.0;
        store.crval[0] = 180.0;
        store.crval[1] = -30.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.cdelt[0] = -0.0005;
        store.cdelt[1] = 0.0005;
        store.equinox = 2000.0;
        store.radesys = "FK5";

        FitsCardList cards = aipspp(stores);
        // No rotation: no CROTA, and CDELT from the diagonal of the CD matrix.
        assertEquals(-0.0005, cards.getFloat("CDELT1"));
        assertEquals(0.0005, cards.getFloat("CDELT2"));
        assertNull(cards.getFloat("CROTA2"));
        assertNull(cards.getFloat("CD1_1"));
        assertEquals(2000.0, cards.getFloat("EPOCH"));
        assertNull(cards.getFloat("EQUINOX"));
        assertNull(cards.getString("RADESYS"));
    }

    @Test
    void writeAipsppEmitsCrotaWhenRotationConsistent() {
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 100.0;
        store.crpix[1] = 100.0;
        store.crval[0] = 45.0;
        store.crval[1] = 60.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        double angle = Math.toRadians(30.0);
        store.pc[0][0] = Math.cos(angle);
        store.pc[0][1] = -Math.sin(angle);
        store.pc[1][0] = Math.sin(angle);
        store.pc[1][1] = Math.cos(angle);
        store.cdelt[0] = 0.001;
        store.cdelt[1] = 0.001;
        store.equinox = 2000.0;

        FitsCardList cards = aipspp(stores);
        double crota2 = cards.getFloat("CROTA2");
        assertTrue(Math.abs(crota2 - 30.0) < 1e-10, "CROTA2 should be ~30, got " + crota2);
        assertNull(cards.getFloat("CD1_1"));
        assertEquals(2000.0, cards.getFloat("EPOCH"));
        assertNull(cards.getFloat("EQUINOX"));
    }

    @Test
    void writeAipsppWithLonpoleLatpole() {
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 256.0;
        store.crpix[1] = 256.0;
        store.crval[0] = 180.0;
        store.crval[1] = -30.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.lonpole = 180.0;
        store.latpole = -30.0;

        FitsCardList cards = aipspp(stores);
        // C's AIPSPPFromStore writes any LONPOLE stored, the default included.
        assertEquals(180.0, cards.getFloat("LONPOLE"));
        assertEquals(-30.0, cards.getFloat("LATPOLE"));
    }

    @Test
    void writeAipsppNonDefaultLonpoleEmitted() {
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 256.0;
        store.crpix[1] = 256.0;
        store.crval[0] = 180.0;
        store.crval[1] = -30.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.lonpole = 45.0;

        assertEquals(45.0, aipspp(stores).getFloat("LONPOLE"));
    }

    @Test
    void writeAipsppSpectralWithSpecsys() {
        FitsPlaneStores stores = axes(3);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 512.0;
        store.crpix[1] = 512.0;
        store.crpix[2] = 1.0;
        store.crval[0] = 180.0;
        store.crval[1] = -30.0;
        store.crval[2] = 10000.0;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.ctype[2] = "VRAD";
        store.cunit[2] = "m/s";
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.cdelt[2] = 1000.0;
        store.specsys = "LSRK";

        FitsCardList cards = aipspp(stores);
        // The standard of rest goes into the CTYPE; there is no SPECSYS card.
        assertEquals("VELO-LSR", cards.getString("CTYPE3"));
        assertNull(cards.getString("SPECSYS"));
    }

    @Test
    void readAipsCrota1Fallback() {
        // C reads CROTA on the latitude axis first, then on the longitude axis, so CROTA1 is used
        // when there is no CROTA2.
        FitsStore store = readStore(CROTA_HEADER
                + "CROTA1  =               45.0   / Rotation angle\n"
                + "EPOCH   =             2000.0   / Epoch\n"
                + "END");
        double cos45 = Math.cos(Math.toRadians(45.0));
        double sin45 = Math.sin(Math.toRadians(45.0));
        double lambda = 0.0002778 / -0.0002778;
        assertTrue(Math.abs(store.pc[0][0] - cos45) < 1e-10);
        assertTrue(Math.abs(store.pc[0][1] - -sin45 * lambda) < 1e-10);
        assertTrue(Math.abs(store.pc[1][0] - sin45 / lambda) < 1e-10);
        assertTrue(Math.abs(store.pc[1][1] - cos45) < 1e-10);
    }

    @Test
    void crota1FallbackTransformsAsC() {
        // C probe p1 (c): the header is guessed to be FITS-AIPS, and its FrameSet transforms two
        // pixels to these sky positions.
        FitsChan fc = FitsHeaders.chanFromLines(CROTA_HEADER
                + "CROTA1  =               45.0   / Rotation angle\n"
                + "EPOCH   =             2000.0   / Epoch");
        assertEquals("FITS-AIPS", fc.getEncoding());
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        double[][] out = fs.tranP(2, 2, new double[][] {{512.0, 600.0}, {512.0, 400.0}}, true, 2);
        CValues.assertCValues(
                new double[] {out[0][0], out[1][0], out[0][1], out[1][1]},
                new double[] {3.141592653589793, -0.7853981633974482, -3.141476209154235, -0.7860838448336191},
                "pixels (512, 512) and (600, 400)");
    }

    @Test
    void aipsWriteRefusesIcrs() {
        // C's AIPSFromStore: ICRS is not supported.
        FitsPlaneStores stores = axes(2);
        FitsStore store = stores.get(' ');
        store.crpix[0] = 100.0;
        store.crpix[1] = 100.0;
        store.crval[0] = 45.0;
        store.crval[1] = 60.0;
        store.cdelt[0] = -0.001;
        store.cdelt[1] = 0.001;
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.radesys = "ICRS";
        assertFalse(FitsEncAips.aipsFromStore(new FitsChan(), stores, "astWrite", "FitsChan"));
        assertFalse(FitsEncAips.aipsppFromStore(new FitsChan(), stores, "astWrite", "FitsChan"));
    }

    @Test
    void aipsppWritesALonpoleOf180() {
        // C probe p2: a TAN header at the north pole, where the default LONPOLE is 0, with LONPOLE
        // 180, written as FITS-AIPS++ gives these cards.
        FitsChan in = FitsHeaders.chan(
                "NAXIS   = 2",
                "NAXIS1  = 100",
                "NAXIS2  = 100",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "CRPIX1  = 50",
                "CRPIX2  = 50",
                "CRVAL1  = 10",
                "CRVAL2  = 90",
                "CDELT1  = -0.01",
                "CDELT2  = 0.01",
                "LONPOLE = 180.0",
                "RADESYS = 'FK5'",
                "EQUINOX = 2000.0");
        FrameSet fs = FitsHeaders.readFrameSet(in);
        FitsChan out = new FitsChan();
        out.setEncoding("FITS-AIPS++");
        assertEquals(1, out.write(fs));
        out.clear("Card");
        List<String> got = new ArrayList<>();
        String card;
        while ((card = out.findFits("%f", true)) != null) {
            got.add(card);
        }
        List<String> want = Arrays.asList(
                "CRPIX1  =                 50.0 / Reference pixel on axis 1                      ",
                "CRPIX2  =                 50.0 / Reference pixel on axis 2                      ",
                "CRVAL1  =                 10.0 / Value at ref. pixel on axis 1                  ",
                "CRVAL2  =                 90.0 / Value at ref. pixel on axis 2                  ",
                "CTYPE1  = 'RA---TAN'           / Type of co-ordinate on axis 1                  ", // codespell:ignore
                "CTYPE2  = 'DEC--TAN'           / Type of co-ordinate on axis 2                  ", // codespell:ignore
                "CDELT1  =                -0.01 / Pixel size                                     ",
                "CDELT2  =                 0.01 / Pixel size                                     ",
                "EPOCH   =               2000.0 / Epoch of reference equinox                     ",
                "LONPOLE =                180.0 / Longitude of native north pole                 ",
                "DATE-OBS= '2000-01-01T11:58:55.816' / Date of observation                       ");
        assertEquals(want, got);
    }
}
