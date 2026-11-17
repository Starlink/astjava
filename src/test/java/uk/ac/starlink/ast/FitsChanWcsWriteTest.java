package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of Rust {@code tests/test_fitschan_wcs_write.rs}: a FrameSet read from a FITS-WCS
 * header written FITS-WCS into a new FitsChan, and read back. The cards and coordinates expected
 * are those C gives (probe {@code c1.c} beside the task report).
 *
 * <p>Grism axes are also checked against the C probe {@code probes/grismmap_probe.c}: headers
 * with {@code -GRI} and {@code -GRA} axes read with each encoding and written back, and FrameSets
 * whose Mapping ends in a GrismMap written FITS-WCS.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: the four tests of the {@code lsst2.ast} FrameSet
 * (SplineMap).
 */
class FitsChanWcsWriteTest {

    /** A two-dimensional TAN header whose reference pixel is (1, 1). */
    private static final String[] MINIMAL_TAN_HEADER = {
        "NAXIS   =                    2 / Number of axes",
        "CRPIX1  =                1.000 / Reference pixel",
        "CRPIX2  =                1.000 / Reference pixel",
        "CRVAL1  =          202.4820000 / Reference RA",
        "CRVAL2  =           47.1750000 / Reference Dec",
        "CDELT1  =       -0.00027778000 / Degrees per pixel",
        "CDELT2  =        0.00027778000 / Degrees per pixel",
        "CTYPE1  = 'RA---TAN'           / RA with TAN projection",
        "CTYPE2  = 'DEC--TAN'           / Dec with TAN projection",
        "RADESYS = 'ICRS    '           / Reference frame",
        "EQUINOX =               2000.0 / Equinox",
    };

    /** The FrameSet read FITS-WCS from {@link #MINIMAL_TAN_HEADER}. */
    private static FrameSet readMinimalTan() {
        FitsChan fc = FitsHeaders.chan(MINIMAL_TAN_HEADER);
        fc.setEncoding("FITS-WCS");
        return FitsHeaders.readFrameSet(fc);
    }

    /** A new FITS-WCS FitsChan holding {@code fs}, which it must write. */
    private static FitsChan written(FrameSet fs) {
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        assertEquals(1, fc.write(fs));
        return fc;
    }

    /**
     * A SIP-shaped PolyMap followed by a MathMap that is not linear over the image is not written
     * as a SIP description; the plain linear approximation is good enough, so the write succeeds.
     */
    @Test
    void aSipDescriptionIsRejectedWhenItIsNotLinearOverTheImage() {
        FrameSet tan = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "CRPIX1  =                  0.0",
                "CRPIX2  =                  0.0",
                "CRVAL1  =                180.0",
                "CRVAL2  =                  0.0",
                "CD1_1   =             -5.5E-05",
                "CD1_2   =                  0.0",
                "CD2_1   =                  0.0",
                "CD2_2   =              5.5E-05",
                "RADESYS = 'ICRS'"));
        // A unit linear term per axis plus one quadratic term, as the SIP conventions require.
        PolyMap poly = new PolyMap(
                2, 2, 4, new double[] {1.0, 1, 1, 0, 1.0e-9, 1, 2, 0, 1.0, 2, 0, 1, 1.0e-9, 2, 0, 2}, 4, new double[] {
                    1.0, 1, 1, 0, -1.0e-9, 1, 2, 0, 1.0, 2, 0, 1, -1.0e-9, 2, 0, 2
                });
        // The cubic term sits between the PolyMap and the CD matrix, so it is part of the Mapping
        // whose linearity the SIP test judges.
        MathMap cubic = new MathMap(
                2, 2, new String[] {"u = x + 1.0E-15*x*x*x", "v = y"}, new String[] {"x = u - 1.0E-15*u*u*u", "y = v"});
        Mapping total = new CmpMap(
                new CmpMap(new CmpMap(new ShiftMap(new double[] {20000.0, 20000.0}), poly, true), cubic, true),
                tan.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT),
                true);
        Frame grid = new Frame(2);
        grid.setDomain("GRID");
        FrameSet fs = new FrameSet(grid);
        fs.addFrame(FrameSet.AST__BASE, total, (Frame)
                tan.getFrame(FrameSet.AST__CURRENT).copy());
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        fc.setCDMatrix(true);
        fc.putFits("NAXIS1  =                 4000", false);
        fc.putFits("NAXIS2  =                 4000", false);
        assertEquals(1, fc.write(fs), "the plain linear approximation is good enough");
        assertFalse(
                FitsHeaders.keywords(fc).contains("A_ORDER"),
                "a SIP description must not be accepted when the Mapping after the SIP polynomial is not linear");
    }

    @Test
    void writeProducesFitsCards() {
        FitsChan fc = written(readMinimalTan());
        List<String> want = FitsHeaders.padded(
                "WCSAXES =                    2 / Number of WCS axes",
                "CRPIX1  =                  1.0 / Reference pixel on axis 1",
                "CRPIX2  =                  1.0 / Reference pixel on axis 2",
                "CRVAL1  =              202.482 / Value at ref. pixel on axis 1",
                "CRVAL2  =               47.175 / Value at ref. pixel on axis 2",
                "CTYPE1  = 'RA---TAN'           / Type of co-ordinate on axis 1", // codespell:ignore
                "CTYPE2  = 'DEC--TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
                "CDELT1  =           -2.7778E-4 / Pixel size on axis 1",
                "CDELT2  =           0.00027778 / Pixel size on axis 2",
                "MJD-OBS =   51544.499257130781 / Modified Julian Date of observation",
                "DATE-OBS= '2000-01-01T11:58:55.816' / Date of observation",
                "RADESYS = 'ICRS    '           / Reference frame for RA/DEC values");
        assertEquals(12, fc.getNcard());
        List<String> got = FitsHeaders.foundCards(fc);
        assertTrue(FitsHeaders.cardsMatchC(got, want), got.toString());
    }

    @Test
    void writeCelestialHasCorrectCtype() {
        FitsChan fc = written(readMinimalTan());
        assertEquals("RA---TAN", fc.getFitsS("CTYPE1"));
        fc.clear("Card");
        assertEquals("DEC--TAN", fc.getFitsS("CTYPE2"));
    }

    @Test
    void writeCelestialHasRadesys() {
        FitsChan fc = written(readMinimalTan());
        assertEquals("ICRS", fc.getFitsS("RADESYS"));
    }

    @Test
    void roundTripReadWriteRead() {
        FrameSet fs1 = readMinimalTan();
        FitsChan fc = written(fs1);
        fc.clear("Card");
        FrameSet fs2 = FitsHeaders.readFrameSet(fc);
        double[] want = {-2.7492077311564285, 0.8233590746283249};
        CValues.assertCValues(FitsHeaders.tran(fs1, new double[] {1, 1}, true), want, "the original at (1, 1)");
        CValues.assertCValues(FitsHeaders.tran(fs2, new double[] {1, 1}, true), want, "read back, at (1, 1)");
        CValues.assertCValues(
                FitsHeaders.tran(fs2, new double[] {2, 2}, true),
                new double[] {-2.7492148633663933, 0.8233639227912407},
                "read back, at (2, 2)");
    }

    /**
     * A FrameSet whose Mapping has no inverse cannot be described: C derives CRPIX by
     * transforming the IWC origin back to grid coordinates. The write gives 0 and leaves the cards
     * it was given.
     */
    @Test
    void aFrameSetWhoseMappingHasNoInverseWritesNothing() {
        // Forward only, and IterInverse off, so there is no numerical inverse either.
        PolyMap poly = new PolyMap(2, 2, 2, new double[] {1.0, 1, 1, 0, 1.0, 2, 0, 1}, 0, null);
        poly.set("IterInverse=0");
        assertFalse(poly.getTranInverse());
        Frame pixel = new Frame(2);
        pixel.setDomain("PIXEL");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, poly, new Frame(2));

        FitsChan fc = FitsHeaders.chan("NAXIS1  =                  100", "NAXIS2  =                  100");
        fc.setEncoding("FITS-WCS");
        assertEquals(0, fc.write(fs));
        assertEquals(
                FitsHeaders.padded("NAXIS1  =                  100", "NAXIS2  =                  100"),
                FitsHeaders.foundCards(fc));
    }

    /** Rust's {@code SPECTRAL_HEADER}: a frequency axis in Hz. */
    private static final String[] SPECTRAL_HEADER = {
        "NAXIS   =                    1 / Number of axes",
        "CRPIX1  =                  1.0 / Reference pixel",
        "CRVAL1  =          1.42040575E9 / Reference frequency (Hz)",
        "CDELT1  =           1000000.00 / Frequency step (Hz)",
        "CTYPE1  = 'FREQ    '           / Frequency axis",
        "CUNIT1  = 'Hz      '           / Units",
        "SPECSYS = 'BARYCENT'           / Reference frame",
        "RESTFRQ =          1.42040575E9 / Rest frequency (Hz)",
    };

    /** The FrameSet read FITS-WCS from {@link #SPECTRAL_HEADER}. */
    private static FrameSet readSpectral() {
        FitsChan fc = FitsHeaders.chan(SPECTRAL_HEADER);
        fc.setEncoding("FITS-WCS");
        return FitsHeaders.readFrameSet(fc);
    }

    @Test
    void writeSpectralFrameset() {
        // C probe roundtrip_probe of Task 8, on Rust's SPECTRAL_HEADER.
        FitsChan fc = written(readSpectral());
        List<String> want = FitsHeaders.padded(
                "WCSAXES =                    1 / Number of WCS axes",
                "CRPIX1  =                  1.0 / Reference pixel on axis 1",
                "CRVAL1  =         1420405750.0 / Value at ref. pixel on axis 1",
                "CTYPE1  = 'FREQ    '           / Type of co-ordinate on axis 1", // codespell:ignore
                "CDELT1  =            1000000.0 / Pixel size on axis 1",
                "CUNIT1  = 'Hz      '           / Units for axis 1",
                "SPECSYS = 'BARYCENT'           / Standard of rest for spectral axis",
                "VELOSYS =   29327.576999844281 / [m/s] Topo. apparent velocity of rest frame",
                "RESTFRQ =         1420405750.0 / [Hz] Rest frequency");
        List<String> got = FitsHeaders.foundCards(fc);
        assertTrue(FitsHeaders.cardsMatchC(got, want), got.toString());
        fc.clear("Card");
        assertEquals("FREQ", fc.getFitsS("CTYPE1"));
    }

    @Test
    void writeSpectralRoundTrip() {
        // C probe roundtrip_probe of Task 8: both FrameSets give these frequencies.
        FrameSet fs1 = readSpectral();
        FitsChan fc2 = written(fs1);
        fc2.clear("Card");
        FrameSet fs2 = FitsHeaders.readFrameSet(fc2);
        double[] pix = {1, 2, 100};
        double[] want = {1420405750.0, 1421405750.0, 1519405750.0};
        assertArrayEquals(want, fs1.tran1(3, pix, true), 0.0);
        assertArrayEquals(want, fs2.tran1(3, pix, true), 0.0);
    }

    // ------------------------------------------------------------------
    // tests/test_fitschan_wcs_write.rs: grism axes
    // ------------------------------------------------------------------

    /**
     * A FITS grism header keeps its interference order, which is commonly negative, through a
     * read and write, with its reference pixel and pixel size (C's {@code testgrismorder},
     * testfitschan.c).
     */
    @Test
    void grismInterferenceOrderSurvivesARoundTrip() {
        for (double morder : new double[] {1.0, -1.0, -2.0}) {
            FitsChan in = FitsHeaders.chan(
                    "CTYPE1  = 'WAVE-GRI'",
                    "CRVAL1  =            5.0E-7",
                    "CRPIX1  =                1.0",
                    "CDELT1  =            1.0E-9",
                    "CUNIT1  = 'm       '",
                    "PV1_0   =            9.0E5",
                    String.format(java.util.Locale.ROOT, "PV1_1   = %20.1f", morder),
                    "PV1_2   =                0.0",
                    "PV1_3   =                1.0",
                    "PV1_4   =                0.0",
                    "PV1_5   =                0.0",
                    "PV1_6   =                0.0");
            FitsChan out = written(FitsHeaders.readFrameSet(in));
            out.clear("Card");
            assertEquals(morder, out.getFitsF("PV1_1"), "grism interference order not preserved");
            out.clear("Card");
            assertEquals(1.0, out.getFitsF("CRPIX1"), "grism output must carry a reference pixel");
            out.clear("Card");
            assertEquals(1.0e-9, out.getFitsF("CDELT1"), 1.0e-12, "grism output CDELT1");
        }
    }

    /**
     * A grism axis whose non-linearity over 10000 pixels is within the linearity tolerance is
     * written as a linear {@code WAVE} axis, with the cards C writes (an oracle probe), and the
     * header read back transforms as the original.
     */
    @Test
    void aNonLinearGrismAxisWritesAHeaderThatReadsBack() {
        FrameSet obj = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "NAXIS   =                    1",
                "NAXIS1  =                10000",
                "CTYPE1  = 'WAVE-GRI'",
                "CRVAL1  =            5.000E-07",
                "CRPIX1  =               5000.0",
                "CDELT1  =            1.000E-10",
                "CUNIT1  = 'm'",
                "RESTFRQ =           5.996E+14",
                "SPECSYS = 'BARYCENT'",
                "PV1_0   =            3.000E-06",
                "PV1_1   =                    1",
                "PV1_2   =                  0.0"));
        FitsChan fc = new FitsChan();
        fc.setEncoding("FITS-WCS");
        fc.putFits("NAXIS   =                    1", false);
        fc.putFits("NAXIS1  =                10000", false);
        assertEquals(1, fc.write(obj), "a grism FrameSet should write one description");
        List<String> want = FitsHeaders.padded(
                "NAXIS   =                    1",
                "NAXIS1  =                10000",
                "CRPIX1  =               5001.0 / Reference pixel on axis 1",
                "CRVAL1  =             5.001E-7 / Value at ref. pixel on axis 1",
                "CTYPE1  = 'WAVE    '           / Type of co-ordinate on axis 1", // codespell:ignore
                "CDELT1  =              1.0E-10 / Pixel size on axis 1",
                "CUNIT1  = 'm       '           / Units for axis 1",
                "SPECSYS = 'BARYCENT'           / Standard of rest for spectral axis",
                "VELOSYS =   29327.576999871173 / [m/s] Topo. apparent velocity of rest frame",
                "RESTWAV = 4.9998742161440962E-7 / [m] Rest wavelength");
        List<String> got = FitsHeaders.foundCards(fc);
        assertTrue(FitsHeaders.cardsMatchC(got, want), got.toString());
        fc.clear("Card");
        FrameSet obj2 = FitsHeaders.readFrameSet(fc);
        double x1 = obj.tran1(1, new double[] {5000.0}, true)[0];
        double x2 = obj2.tran1(1, new double[] {5000.0}, true)[0];
        assertTrue(Math.abs(x1 - x2) <= 1.0e-6 * Math.abs(x1), x1 + " vs " + x2);
    }

    // ------------------------------------------------------------------
    // Grism axes against the C probe probes/grismmap_probe.c
    // ------------------------------------------------------------------

    private static final java.util.Map<String, String> GRISM_PROBE =
            uk.ac.starlink.ast.testsupport.ProbeOutput.load("grismmap_probe");

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    /** The probe's {@code pixels}: pixels 1, 50 and 100 transformed forward, and back. */
    private static void pixels(List<String> out, FrameSet fs) {
        double[] pix = {1.0, 50.0, 100.0};
        double[] w = fs.tran1(3, pix, true);
        double[] back = fs.tran1(3, w, false);
        for (int i = 0; i < 3; i++) {
            out.add("pixel " + g(pix[i]) + " -> " + g(w[i]) + " -> " + g(back[i]));
        }
    }

    /** The probe's report of an error, without the line naming the C source. */
    private static void error(List<String> out, AstException e) {
        for (String line : uk.ac.starlink.ast.testsupport.ProbeOutput.trimLines(e.getMsgText())
                .split("\n", -1)) {
            out.add("!  " + line);
        }
        out.add("status " + e.getStatus());
    }

    /** {@code text}'s lines, trailing white space removed, without the last newline. */
    private static void addText(List<String> out, String text) {
        String t = uk.ac.starlink.ast.testsupport.ProbeOutput.trimLines(text);
        out.add(t.endsWith("\n") ? t.substring(0, t.length() - 1) : t);
    }

    /** The cards of {@code fc}, as the probe lists them. */
    private static void cards(List<String> out, FitsChan fc) {
        for (String card : FitsHeaders.foundCards(fc)) {
            out.add(uk.ac.starlink.ast.testsupport.ProbeOutput.trimLines(card));
        }
    }

    /** The probe's {@code fitsrw}: the header read with the encoding, written with it and read back. */
    private static String fitsrw(String encoding, String... header) {
        List<String> out = new java.util.ArrayList<>();
        FitsChan fc = FitsHeaders.chan(header);
        fc.setEncoding(encoding);
        fc.clear("Card");
        AstObject obj;
        try {
            obj = fc.read();
        } catch (AstException e) {
            error(out, e);
            return String.join("\n", out) + "\n";
        }
        if (obj == null) {
            return "read nothing\n";
        }
        FrameSet fs = (FrameSet) obj;
        out.add("read " + fs.astClass());
        pixels(out, fs);
        addText(
                out,
                ChannelText.dump(
                        fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify()));
        FitsChan wfc = new FitsChan();
        wfc.setEncoding(encoding);
        int n = wfc.write(fs);
        out.add("-- written " + n);
        cards(out, wfc);
        if (n != 0) {
            wfc.clear("Card");
            try {
                AstObject back = wfc.read();
                if (back == null) {
                    out.add("-- read back nothing");
                } else {
                    out.add("-- read back " + back.astClass());
                    pixels(out, (FrameSet) back);
                }
            } catch (AstException e) {
                error(out, e);
            }
        }
        return String.join("\n", out) + "\n";
    }

    /** The probe's {@code fitswrite}: a FrameSet from a GRID Frame to the SpecFrame written FITS-WCS. */
    private static String fitswrite(Mapping map, String specattrs) {
        List<String> out = new java.util.ArrayList<>();
        Frame grid = new Frame(1);
        grid.setDomain("GRID");
        FrameSet fs = new FrameSet(grid);
        SpecFrame sf = new SpecFrame();
        sf.set(specattrs);
        fs.addFrame(FrameSet.AST__BASE, map, sf);
        FitsChan wfc = new FitsChan();
        wfc.setEncoding("FITS-WCS");
        int n = wfc.write(fs);
        out.add("written " + n);
        cards(out, wfc);
        pixels(out, fs);
        if (n != 0) {
            wfc.clear("Card");
            out.add("-- read back");
            pixels(out, (FrameSet) wfc.read());
        }
        return String.join("\n", out) + "\n";
    }

    /**
     * The relative difference from C's allowed a number in the grism cases. Reading a grism axis
     * divides by the rate of change of the grism parameter at CRVAL, which C's {@code astRate}
     * estimates numerically, choosing the interval whose sub-interval gradients spread least. The
     * grism parameter is {@code tan(asin(s) - k3)}, zero at CRVAL; java.lang.Math's {@code asin}
     * and {@code tan} differ from C's libm by an ULP at some points, and near CRVAL the
     * subtraction makes that a large part of the parameter, which changes the interval chosen. The
     * rate, and with it the read Mapping's scale, the world coordinates and the CDELT written
     * back, then differ from C's by up to 8e-10 of their values (the {@code AWAV-GRA} and nm
     * cases).
     */
    private static final double GRISM_RTOL = 1.0e-8;

    /**
     * Whether {@code got} is C's {@code want} without the lines naming the C source of an error:
     * each line the same, but for numbers within {@link #GRISM_RTOL} of C's, which on a card may
     * be written to a width the white space beside them absorbs.
     */
    static boolean grismMatchesC(String got, String want) {
        List<String> g = CValues.lines(got);
        List<String> w = new java.util.ArrayList<>();
        for (String line : CValues.lines(want)) {
            if (!line.contains("AST: Error in routine")) {
                w.add(line);
            }
        }
        if (g.size() != w.size()) {
            return false;
        }
        for (int i = 0; i < g.size(); i++) {
            if (!CValues.nativeStringsWithinRelative(g.get(i) + "\n", w.get(i) + "\n", GRISM_RTOL)) {
                return false;
            }
        }
        return true;
    }

    private static void assertGrismProbe(String name, String got) {
        String want = GRISM_PROBE.get(name);
        if (want == null || !grismMatchesC(got, want)) {
            assertEquals(want, got, name);
        }
    }

    private static final String[] GRI_HEADER = {
        "CTYPE1  = 'WAVE-GRI'",
        "CRVAL1  =            5.0E-7",
        "CRPIX1  =                1.0",
        "CDELT1  =            1.0E-9",
        "CUNIT1  = 'm       '",
        "PV1_0   =            9.0E5",
        "PV1_1   =                1.0",
        "PV1_2   =                0.0",
        "PV1_3   =                1.0",
        "PV1_4   =                0.0",
        "PV1_5   =                0.0",
        "PV1_6   =                0.0"
    };

    private static final String[] GRA_HEADER = {
        "CTYPE1  = 'AWAV-GRA'",
        "CRVAL1  =            5.0E-7",
        "CRPIX1  =               10.0",
        "CDELT1  =            1.0E-9",
        "CUNIT1  = 'm       '",
        "PV1_0   =            6.0E5",
        "PV1_1   =               -2.0",
        "PV1_2   =               10.0",
        "PV1_3   =                1.5",
        "PV1_4   =             -100.0",
        "PV1_5   =                2.0",
        "PV1_6   =                3.0"
    };

    /**
     * {@code -GRI} and {@code -GRA} headers read with each encoding, and written back with it, as
     * C reads and writes them: FITS-WCS writes the grism axis with each parameter that differs
     * from its default; FITS-PC and FITS-IRAF write the CTYPE without the parameters, so the
     * header does not read back; FITS-AIPS and FITS-AIPS++ write nothing; FITS-CLASS and DSS
     * refuse the header, and NATIVE finds nothing in it.
     */
    @Test
    void grismHeadersReadAndWriteAsC() {
        for (String enc : new String[] {
            "FITS-WCS", "FITS-PC", "FITS-IRAF", "FITS-AIPS", "FITS-AIPS++", "FITS-CLASS", "NATIVE", "DSS"
        }) {
            assertGrismProbe("fits_gri_" + enc, fitsrw(enc, GRI_HEADER));
            assertGrismProbe("fits_gra_" + enc, fitsrw(enc, GRA_HEADER));
        }
    }

    /**
     * Grism axes in vacuum wavelength dispersed in air, in frequency, and in nm read and written
     * as C does; a fractional order is rounded, and a radio velocity axis, which does not convert
     * to wavelength, and an order whose rounding puts the reference ray outside the grism, are
     * refused.
     */
    @Test
    void otherGrismHeadersReadAndWriteAsC() {
        assertGrismProbe(
                "fits_wave_gra",
                fitsrw(
                        "FITS-WCS",
                        "CTYPE1  = 'WAVE-GRA'",
                        "CRVAL1  =            5.0E-7",
                        "CRPIX1  =                1.0",
                        "CDELT1  =            1.0E-9",
                        "CUNIT1  = 'm       '",
                        "PV1_0   =            9.0E5",
                        "PV1_1   =                1.0"));
        assertGrismProbe(
                "fits_freq_gri",
                fitsrw(
                        "FITS-WCS",
                        "CTYPE1  = 'FREQ-GRI'",
                        "CRVAL1  =           6.0E14",
                        "CRPIX1  =                1.0",
                        "CDELT1  =           1.0E12",
                        "CUNIT1  = 'Hz      '",
                        "PV1_0   =            9.0E5",
                        "PV1_1   =                1.0"));
        assertGrismProbe(
                "fits_gri_nm",
                fitsrw(
                        "FITS-WCS",
                        "CTYPE1  = 'WAVE-GRI'",
                        "CRVAL1  =              500.0",
                        "CRPIX1  =                1.0",
                        "CDELT1  =                1.0",
                        "CUNIT1  = 'nm      '",
                        "PV1_0   =            9.0E5",
                        "PV1_1   =                1.0"));
        assertGrismProbe(
                "fits_vrad_gri",
                fitsrw(
                        "FITS-WCS",
                        "CTYPE1  = 'VRAD-GRI'",
                        "CRVAL1  =              100.0",
                        "CRPIX1  =                1.0",
                        "CDELT1  =                1.0",
                        "PV1_0   =            9.0E5",
                        "PV1_1   =                1.0"));
        assertGrismProbe(
                "fits_gri_half_order",
                fitsrw(
                        "FITS-WCS",
                        "CTYPE1  = 'WAVE-GRI'",
                        "CRVAL1  =            5.0E-7",
                        "CRPIX1  =                1.0",
                        "CDELT1  =            1.0E-9",
                        "CUNIT1  = 'm       '",
                        "PV1_0   =            9.0E5",
                        "PV1_1   =                1.5"));
        assertGrismProbe(
                "fits_gri_negative_half_order",
                fitsrw(
                        "FITS-WCS",
                        "CTYPE1  = 'WAVE-GRI'",
                        "CRVAL1  =            5.0E-7",
                        "CRPIX1  =                1.0",
                        "CDELT1  =            1.0E-9",
                        "CUNIT1  = 'm       '",
                        "PV1_0   =            9.0E5",
                        "PV1_1   =               -2.5"));
    }

    private static Mapping pixelToGrism() {
        return new CmpMap(new ShiftMap(new double[] {-50.0}), new ZoomMap(1, 0.002), true);
    }

    private static GrismMap grism() {
        GrismMap g = new GrismMap();
        g.set("GrismG=9.0E5,GrismM=1,GrismWaveR=5.0E-7");
        return g;
    }

    /**
     * FrameSets whose Mapping ends in a GrismMap are written as {@code -GRI} axes, as C writes
     * them: in wavelength, and in frequency, whose Mapping ends in a GrismMap once the SpecFrame
     * is set to wavelength; a GrismMap inverted twice is still not inverted. A GrismMap followed
     * by a ShiftMap is not at the end of the Mapping, and one followed by a ZoomMap to nm is not
     * written either.
     */
    @Test
    void grismFrameSetsWriteAsC() {
        assertGrismProbe(
                "write_shift_zoom_grism", fitswrite(new CmpMap(pixelToGrism(), grism(), true), "System=WAVE,Unit=m"));
        assertGrismProbe(
                "write_grism_then_zoom_nm",
                fitswrite(
                        new CmpMap(pixelToGrism(), new CmpMap(grism(), new ZoomMap(1, 1.0e9), true), true),
                        "System=WAVE,Unit=nm"));
        SpecMap w2f = new SpecMap(1, 0);
        w2f.specAdd("WVTOFR", new double[0]);
        assertGrismProbe(
                "write_grism_freq",
                fitswrite(new CmpMap(pixelToGrism(), new CmpMap(grism(), w2f, true), true), "System=FREQ,Unit=Hz"));
        assertGrismProbe(
                "write_grism_then_shift",
                fitswrite(
                        new CmpMap(
                                new CmpMap(pixelToGrism(), grism(), true), new ShiftMap(new double[] {1.0e-9}), true),
                        "System=WAVE,Unit=m"));
        GrismMap twice = grism();
        twice.invert();
        twice.invert();
        assertGrismProbe(
                "write_grism_inverted", fitswrite(new CmpMap(pixelToGrism(), twice, true), "System=WAVE,Unit=m"));
    }
}
