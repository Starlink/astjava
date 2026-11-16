package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the tests of Rust {@code fitschan/enc_dss.rs}: C's {@code DSSToStore}, which
 * makes the axis description of a DSS plate solution (a TPN projection whose polynomial is held as
 * projection parameters), and {@code DSSFromStore}, which writes one as DSS cards; with the reads
 * and writes of DSS headers through a FitsChan that C probe {@code p1.c} (beside the task report)
 * makes.
 *
 * <p>Rust's {@code read_from_cards} takes the sign of the declination from the first character of
 * PLTDECSN; C's {@code DSSToStore} takes it to be negative only when the first word of PLTDECSN is
 * {@code -}, so {@link #pltdecsnOtherThanAMinusIsPositive} expects C's result.
 */
class FitsEncDssTest {

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** The header of Rust's {@code dss_test_cards}, part of the wcsconv {@code dss.fits-dss} fixture. */
    private static final String DSS_HEADER = "PLTRAH  =                   10 / Plate centre RA hours\n"
            + "PLTRAM  =                   47 / Plate centre RA minutes\n"
            + "PLTRAS  =                 57.3 / Plate centre RA seconds\n"
            + "PLTDECD =                   60 / Plate centre DEC degrees\n"
            + "PLTDECM =                   15 / Plate centre DEC arcminutes\n"
            + "PLTDECS =                51.85 / Plate centre DEC arcseconds\n"
            + "PLTDECSN= '-'                  / Plate centre DEC sign\n"
            + "XPIXELSZ=                25.28 / Pixel size X (microns)\n"
            + "YPIXELSZ=                25.28 / Pixel size Y (microns)\n"
            + "PPO3    =            -18825.9  / Plate offset X (microns)\n"
            + "PPO6    =            -22216.5  / Plate offset Y (microns)\n"
            + "CNPIX1  =                  1.0 / Corner pixel X\n"
            + "CNPIX2  =                  1.0 / Corner pixel Y\n"
            + "AMDX1   =                67.23 / Plate solution x co-efficients\n"
            + "AMDX3   =                  1.36\n"
            + "AMDY1   =                67.23 / Plate solution y co-efficients\n"
            + "AMDY3   =                 0.74\n";

    /** {@link #DSS_HEADER} without the card of {@code skip}, and with {@code extra} added. */
    private static FitsChan dssChan(String skip, String extra, String attrs) {
        StringBuilder text = new StringBuilder();
        for (String line : CValues.lines(DSS_HEADER)) {
            if (skip == null || !line.startsWith(skip)) {
                text.append(line).append('\n');
            }
        }
        if (extra != null) {
            text.append(extra).append('\n');
        }
        FitsChan fc = new FitsChan();
        if (!attrs.isEmpty()) {
            fc.set(attrs);
        }
        for (String line : CValues.lines(text.toString())) {
            fc.putFits(line, false);
        }
        fc.clear("Card");
        fc.setEncoding("DSS");
        return fc;
    }

    /** The axis description C's {@code DSSToStore} makes of {@code header}. */
    private static FitsStore readStore(String header) {
        return FitsEncDss.dssToStore(FitsHeaders.chanFromLines(header), "astRead", "FitsChan");
    }

    /** A FitsPlaneStores whose primary description is Rust's {@code tpn_store}: a plate scale only. */
    private static FitsPlaneStores tpnStore() {
        FitsPlaneStores stores = new FitsPlaneStores(2);
        FitsStore store = stores.version(' ', 2);
        store.crval[0] = 180.0;
        store.crval[1] = 45.0;
        store.crpix[0] = 512.0;
        store.crpix[1] = 512.0;
        store.cdelt[0] = 1.0;
        store.cdelt[1] = 1.0;
        store.pc[0][0] = -0.001 * 25.0;
        store.pc[1][1] = 0.001 * 25.0;
        store.ctype[0] = "RA---TPN";
        store.ctype[1] = "DEC--TPN";
        store.radesys = "FK5";
        store.pv[0][1] = 67.0 / 3600.0;
        store.pv[1][1] = 67.0 / 3600.0;
        return stores;
    }

    /** The cards {@code DSSFromStore} writes for {@code stores} into a new FitsChan, which must be written. */
    private static FitsCardList write(FitsPlaneStores stores) {
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncDss.dssFromStore(fc, stores, "astWrite", "FitsChan"));
        return fc.cards();
    }

    @Test
    void readDssPlateCentre() {
        FitsStore store = readStore(DSS_HEADER);
        assertEquals(15.0 * (10.0 + 47.0 / 60.0 + 57.3 / 3600.0), store.crval[0]);
    }

    @Test
    void readDssNegativeDec() {
        FitsStore store = readStore(DSS_HEADER);
        assertEquals(-(60.0 + 15.0 / 60.0 + 51.85 / 3600.0), store.crval[1]);
    }

    @Test
    void readDssCrpix() {
        FitsStore store = readStore(DSS_HEADER);
        assertEquals((-18825.9 / 25.28) - 1.0 + 0.5, store.crpix[0]);
        assertEquals((-22216.5 / 25.28) - 1.0 + 0.5, store.crpix[1]);
    }

    @Test
    void readDssIsTpnWithUnitCdelt() {
        // C's DSSToStore makes a TPN projection with CDELT 1 and the pixel sizes in the PC matrix.
        FitsStore store = readStore(DSS_HEADER);
        assertEquals(2, store.naxis);
        assertEquals(2, (int) store.wcsaxes);
        assertEquals("RA---TPN", store.ctype[0]);
        assertEquals("DEC--TPN", store.ctype[1]);
        assertEquals("FK5", store.radesys);
        assertEquals(2000.0, store.equinox);
        assertEquals(180.0, store.lonpole);
        assertEquals(1.0, store.cdelt[0]);
        assertEquals(1.0, store.cdelt[1]);
        assertEquals(-0.001 * 25.28, store.pc[0][0]);
        assertEquals(0.001 * 25.28, store.pc[1][1]);
    }

    @Test
    void readDssPvFromAmd() {
        // AMDX1 gives PV1_1 and AMDX3 PV1_0, each divided by 3600; likewise for Y.
        FitsStore store = readStore(DSS_HEADER);
        assertEquals(67.23 / 3600.0, store.pv[0][1]);
        assertEquals(1.36 / 3600.0, store.pv[0][0]);
        assertEquals(67.23 / 3600.0, store.pv[1][1]);
        assertEquals(0.74 / 3600.0, store.pv[1][0]);
    }

    @Test
    void readDssDefaultCnpix() {
        String header = "PLTRAH  =                   12 / Plate centre RA hours\n"
                + "PLTRAM  =                    0 / Plate centre RA minutes\n"
                + "PLTRAS  =                  0.0 / Plate centre RA seconds\n"
                + "PLTDECD =                   45 / Plate centre DEC degrees\n"
                + "PLTDECM =                    0 / Plate centre DEC arcminutes\n"
                + "PLTDECS =                  0.0 / Plate centre DEC arcseconds\n"
                + "PLTDECSN= '+'\n"
                + "XPIXELSZ=                25.0  / Pixel size X (microns)\n"
                + "YPIXELSZ=                25.0  / Pixel size Y (microns)\n"
                + "PPO3    =              1000.0  / Plate offset X (microns)\n"
                + "PPO6    =              2000.0  / Plate offset Y (microns)\n"
                + "AMDX1   =                67.0  / Plate scale (arcsec/mm)\n";
        FitsStore store = readStore(header);
        assertEquals(40.5, store.crpix[0]);
        assertEquals(80.5, store.crpix[1]);
    }

    @Test
    void readDssMissingKeywordError() {
        // C probe p1 (d): the message C's GetValue reports.
        AstException e = assertThrows(AstException.class, () -> readStore(DSS_HEADER.replace("XPIXELSZ", "XPIXELSY")));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(
                e.getMessage().contains("astRead(FitsChan): Unable to find a value for FITS keyword \"XPIXELSZ\"."),
                e.getMessage());
    }

    @Test
    void writeDssRaDecomposition() {
        // RA 161.98875 degrees is 10h 47m 57.3s.
        FitsPlaneStores stores = tpnStore();
        FitsStore store = stores.get(' ');
        store.crval[0] = 161.98875;
        FitsCardList cards = write(stores);
        assertEquals(10.0, cards.getFloat("PLTRAH"));
        assertEquals(47.0, cards.getFloat("PLTRAM"));
        assertTrue(Math.abs(cards.getFloat("PLTRAS") - 57.3) < 0.01, "PLTRAS " + cards.getFloat("PLTRAS"));
    }

    @Test
    void writeDssNegativeDecSign() {
        FitsPlaneStores stores = tpnStore();
        stores.get(' ').crval[1] = -60.264;
        FitsCardList cards = write(stores);
        assertEquals("-", cards.getString("PLTDECSN"));
        assertEquals(60.0, cards.getFloat("PLTDECD"));
    }

    @Test
    void writeDssPositiveDecSign() {
        FitsPlaneStores stores = tpnStore();
        stores.get(' ').crval[1] = 45.5;
        assertEquals("+", write(stores).getString("PLTDECSN"));
    }

    @Test
    void writeDssEmitsFullAmdArrays() {
        FitsCardList cards = write(tpnStore());
        for (int i = 1; i <= 20; i++) {
            assertNotNull(cards.getFloat("AMDX" + i), "AMDX" + i);
            assertNotNull(cards.getFloat("AMDY" + i), "AMDY" + i);
        }
        for (int i = 1; i <= 6; i++) {
            assertNotNull(cards.getFloat("PPO" + i), "PPO" + i);
        }
    }

    @Test
    void writeDssRejectsRotation() {
        FitsPlaneStores stores = tpnStore();
        stores.get(' ').pc[0][1] = 0.1;
        FitsChan fc = new FitsChan();
        assertFalse(FitsEncDss.dssFromStore(fc, stores, "astWrite", "FitsChan"));
        assertEquals(0, fc.cards().len());
    }

    @Test
    void writeDssRequires2d() {
        FitsPlaneStores stores = tpnStore();
        FitsStore store = stores.version(' ', 3);
        store.crpix[2] = 1.0;
        assertFalse(FitsEncDss.dssFromStore(new FitsChan(), stores, "astWrite", "FitsChan"));
    }

    @Test
    void writeDssRequiresFk5TanOrTpn() {
        FitsPlaneStores stores = tpnStore();
        stores.get(' ').radesys = "GALACTIC";
        assertFalse(FitsEncDss.dssFromStore(new FitsChan(), stores, "astWrite", "FitsChan"));
    }

    @Test
    void roundTripPreservesCoordinates() {
        FitsPlaneStores stores = tpnStore();
        FitsStore original = stores.get(' ');
        original.crval[0] = 161.98875;
        original.crval[1] = -60.264;
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncDss.dssFromStore(fc, stores, "astWrite", "FitsChan"));
        fc.clear("Card");
        FitsStore recovered = FitsEncDss.dssToStore(fc, "astRead", "FitsChan");
        assertTrue(Math.abs(original.crval[0] - recovered.crval[0]) < 1.0 / 3600.0);
        assertTrue(Math.abs(original.crval[1] - recovered.crval[1]) < 1.0 / 3600.0);
    }

    // ------------------------------------------------------------------
    // Reads and writes through a FitsChan (C probe p1)
    // ------------------------------------------------------------------

    /** The pixels C probe p1 transforms, as tranP takes them. */
    private static final double[][] PIXELS = {{1.0, 265.0, 530.0, 100.5}, {1.0, 265.0, 530.0, 400.25}};

    /** {@code fs} applied to {@link #PIXELS}, as (x, y) pairs of outputs, in C probe p1's order. */
    private static double[] transformed(FrameSet fs) {
        double[][] out = fs.tranP(4, 2, PIXELS, true, 2);
        double[] flat = new double[8];
        for (int i = 0; i < 4; i++) {
            flat[2 * i] = out[0][i];
            flat[2 * i + 1] = out[1][i];
        }
        return flat;
    }

    /** The keyword of each card {@code fc} has not used, in order, as C probe p1 lists them. */
    private static List<String> unusedKeywords(FitsChan fc) {
        List<String> out = new ArrayList<>();
        for (String card : FitsHeaders.foundCards(fc)) {
            out.add(card.substring(0, 8).trim());
        }
        return out;
    }

    @Test
    void readUsesEveryDssCard() {
        // C probe p1 (a): the header's encoding is guessed to be DSS; the read uses every card
        // DSSToStore reads (PLTSCALE and PPO1, PPO2, PPO4 and PPO5 among them), but not EQUINOX or
        // EPOCH, and the FrameSet transforms as C's.
        FitsChan fc = FitsHeaders.fixture("wcsconv/inputs/dss.fits-dss");
        fc.setFitsDigits(8);
        assertEquals("DSS", fc.getEncoding());
        assertEquals(108, fc.getNcard());
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        assertEquals(50, fc.getNcard());
        List<String> blanks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            blanks.add("");
        }
        List<String> want = new ArrayList<>(Arrays.asList(
                "SIMPLE",
                "BITPIX",
                "NAXIS",
                "NAXIS1",
                "NAXIS2",
                "EXTEND",
                "DATE",
                "ORIGIN",
                "PLTLABEL",
                "PLATEID",
                "REGION",
                "DATE-OBS",
                "UT",
                "EPOCH",
                "EQUINOX",
                "EXPOSURE",
                "BANDPASS",
                "PLTGRADE",
                "SITELAT",
                "SITELONG",
                "TELESCOP",
                "DATATYPE",
                "SCANIMG",
                "SCANNUM",
                "DCHOPPED",
                "DSHEARED",
                "DSCNDNUM"));
        want.addAll(blanks);
        want.addAll(Arrays.asList("DATAMAX", "DATAMIN", "OBJECT", "OBJCTRA", "OBJCTDEC", "OBJCTX", "OBJCTY", "END"));
        assertEquals(want, unusedKeywords(fc));
        assertEquals(2, fs.getNframe());
        CValues.assertCValues(
                transformed(fs),
                new double[] {
                    2.8150213850747887, -1.044517052126356,
                    2.810755733626352, -1.042312576865633,
                    2.806506259777323, -1.0400918379780315,
                    2.8134723132726647, -1.0412170511937375
                },
                "the pixels of probe p1 (a)");
    }

    @Test
    void writtenAndReadBackAsC() {
        // C probe p1 (b): the FrameSet read, written as DSS into a new FitsChan, gives the cards
        // of wcsconv's dss.dss (which C writes from the FrameSet's native dump), and those cards
        // read back transform as C's.
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.fixture("wcsconv/inputs/dss.fits-dss"));
        FitsChan out = new FitsChan();
        out.set("Encoding=DSS,FitsDigits=8");
        assertEquals(1, out.write(fs));
        assertEquals(58, out.getNcard());
        List<String> want = new ArrayList<>();
        for (String line : CValues.lines(Fixtures.read("wcsconv/expected/dss.dss"))) {
            want.add(line);
        }
        List<String> got = FitsHeaders.foundCards(out);
        assertTrue(FitsHeaders.cardsMatchC(got, want), "--- Java\n" + String.join("\n", got));
        out.clear("Card");
        FrameSet back = FitsHeaders.readFrameSet(out);
        assertEquals(0, out.getNcard());
        CValues.assertCValues(
                transformed(back),
                new double[] {
                    2.815021385072929, -1.0445170521259821,
                    2.8107557336245015, -1.0423125768652555,
                    2.8065062597754826, -1.040091837977651,
                    2.8134723132708164, -1.0412170511933625
                },
                "the pixels of probe p1 (b)");
    }

    @Test
    void failedReadLeavesTheCards() {
        // C probe p1 (d): a read that fails reports C's error and, with Clean unset, leaves every
        // card unused; with Clean set, the cards read before the error (CNPIX1, CNPIX2, PPO3 and
        // PPO6) are used.
        FitsChan fc = dssChan("XPIXELSZ", null, "");
        AstException e = assertThrows(AstException.class, fc::read);
        assertTrue(e.getMessage().contains("Unable to find a value for FITS keyword \"XPIXELSZ\"."), e.getMessage());
        assertEquals(16, fc.getNcard());

        FitsChan clean = dssChan("XPIXELSZ", null, "Clean=1");
        assertThrows(AstException.class, clean::read);
        assertEquals(12, clean.getNcard());
        List<String> left = unusedKeywords(clean);
        for (String used : Arrays.asList("CNPIX1", "CNPIX2", "PPO3", "PPO6")) {
            assertFalse(left.contains(used), used + " is used");
        }

        FitsChan zero = dssChan("XPIXELSZ", "XPIXELSZ=                  0.0", "");
        e = assertThrows(AstException.class, zero::read);
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(
                e.getMessage().contains("astRead(FitsChan): FITS keyword XPIXELSZ has illegal value 0.0"),
                e.getMessage());
        assertEquals(17, zero.getNcard());

        FitsChan string = dssChan("PPO3", "PPO3    = 'abc'", "");
        e = assertThrows(AstException.class, string::read);
        assertEquals(AstException.AST__FTCNV, e.getStatus());
        assertTrue(
                e.getMessage().contains("astRead(FitsChan): Cannot convert FITS keyword 'PPO3' to floating point."),
                e.getMessage());
        assertEquals(17, string.getNcard());
    }

    @Test
    void pltdecsnOtherThanAMinusIsPositive() {
        // C probe p1 (e): the reference pixel transformed, in degrees, for three PLTDECSN values;
        // only a first word of '-' makes the declination negative.
        String[][] cases = {
            {"PLTDECSN= '-5'", "161.98951165622611", "60.264608331152985"},
            {"PLTDECSN= ' - '", "161.98951164665871", "-60.264197220041915"},
            {"PLTDECSN= '+'", "161.98951165622611", "60.264608331152985"}
        };
        for (String[] c : cases) {
            FrameSet fs = FitsHeaders.readFrameSet(dssChan("PLTDECSN", c[0], ""));
            double[] out = FitsHeaders.tran(
                    fs, new double[] {-18825.9 / 25.28 - 1.0 + 0.5, -22216.5 / 25.28 - 1.0 + 0.5}, true);
            CValues.assertCValues(
                    new double[] {out[0] * AST__DR2D, out[1] * AST__DR2D},
                    new double[] {Double.parseDouble(c[1]), Double.parseDouble(c[2])},
                    c[0]);
        }
    }

    @Test
    void rustHeaderTransformsAsC() {
        // C probe p1 (f): Rust's test header, read, uses every card and transforms as C's.
        FitsChan fc = dssChan(null, null, "");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        assertEquals(0, fc.getNcard());
        CValues.assertCValues(
                transformed(fs),
                new double[] {
                    2.8150101217105368, -1.0445227121800418,
                    2.810742362755056, -1.042320796878139,
                    2.8064910737385897, -1.0401027971023051,
                    2.813456552045181, -1.0412243189021584
                },
                "the pixels of probe p1 (f)");
    }

    @Test
    void readDssProducesFrameSet() {
        // Rust tests/test_fitschan_encodings.rs read_dss_produces_frameset: C's own DSS cards read
        // as a FrameSet of two two-dimensional Frames (C probe p1 (b) reads the same cards).
        FitsChan fc = FitsHeaders.fixture("wcsconv/expected/dss.dss");
        assertEquals("DSS", fc.getEncoding());
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }
}
