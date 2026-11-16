package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/enc_class.rs}: C's {@code ClassTrans},
 * which translates a FITS-CLASS header for a read, and {@code CLASSFromStore}, which writes one.
 *
 * <p>The reads translate the header as a FITS-CLASS read does ({@link FitsSpecTrans#specTrans})
 * and make the description C's {@code WcsToStore} makes of it; Rust's {@code
 * read_class_spatial_axes_unchanged}, {@code read_class_equinox_and_radesys} and {@code
 * read_class_mjdobs} make it from the untranslated header, as Rust does. The writes give {@code
 * CLASSFromStore} the FrameSet it takes the VELO-LSR and AZIMUTH values from: for Rust's writes, a
 * FrameSet with a plain Frame, from which C finds neither. The values are those of C's arithmetic
 * in {@code ClassTrans} and {@code CLASSFromStore}; the wcsconv FITS-CLASS rows compare whole
 * headers with C's. The FrameSets with a FREQ axis but no SpecFrame are C probe {@code p2.c}'s
 * (phase 3).
 */
class FitsEncClassTest {

    /** The header of Rust's {@code CLASS_HEADER}. */
    private static final String CLASS_HEADER = "NAXIS   =                    3\n"
            + "CTYPE1  = 'FREQ    '\n"
            + "CRVAL1  =  0.000000000000E+000\n"
            + "CRPIX1  =              512.500\n"
            + "CDELT1  =  0.390625000000E+005\n"
            + "CTYPE2  = 'RA---TAN'\n"
            + "CRVAL2  =               40.738\n"
            + "CRPIX2  =                  0.5\n"
            + "CDELT2  =              -0.0100\n"
            + "CTYPE3  = 'DEC--TAN'\n"
            + "CRVAL3  =               62.933\n"
            + "CRPIX3  =                  0.5\n"
            + "CDELT3  =               0.0100\n"
            + "RADESYS = 'FK5     '\n"
            + "EQUINOX =               2000.0\n"
            + "SPECSYS = 'SOURCE  '\n"
            + "RESTFREQ=  0.110201354300E+012\n"
            + "VELO-LSR=  0.250000000000E+004\n"
            + "DELTAV  = -0.106265872717E+003\n"
            + "MJD-OBS =            52413.59\n";

    /** The primary description C's {@code WcsToStore} makes of {@link #CLASS_HEADER}, untranslated. */
    private static FitsStore classStore() {
        return FitsWcsKeywords.extractWcsKeywordsWithAlts(FitsCardList.fromHeader(CLASS_HEADER)).primary;
    }

    @Test
    void readClassSpatialAxesUnchanged() {
        FitsStore store = classStore();
        assertEquals(40.738, store.crval[1]);
        assertEquals(62.933, store.crval[2]);
    }

    @Test
    void readClassEquinoxAndRadesys() {
        FitsStore store = classStore();
        assertEquals(2000.0, store.equinox);
        assertEquals("FK5", store.radesys);
    }

    @Test
    void readClassMjdobs() {
        assertEquals(52413.59, classStore().mjdobs);
    }

    /**
     * The primary description C's {@code WcsToStore} makes of {@code header} translated as a
     * FITS-CLASS read translates it.
     */
    private static FitsStore translated(String header) {
        FitsCardList trans = FitsSpecTrans.specTrans(
                FitsCardList.fromHeader(header),
                FitsChan.Encoding.FITS_CLASS,
                new FitsSpecTrans.TransAttrs(),
                new FitsSpecTrans.TransWarnings(),
                new FitsWarnings());
        return FitsWcsKeywords.extractWcsKeywordsWithAlts(trans).primary;
    }

    /** {@link #CLASS_HEADER} with its spatial and velocity cards replaced as {@code edits} gives. */
    private static String classHeader(String... edits) {
        String h = CLASS_HEADER;
        for (int i = 0; i < edits.length; i += 2) {
            h = h.replace(edits[i], edits[i + 1]);
        }
        return h;
    }

    @Test
    void readClassOffsetFrequency() {
        // CRVAL1 is 0, an offset from RESTFREQ, so the frequency is RESTFREQ.
        assertEquals(0.110201354300e12, translated(CLASS_HEADER).crval[0], 1.0);
    }

    @Test
    void readClassWithNonzeroOffset() {
        FitsStore store = translated(classHeader(
                "CRVAL1  =  0.000000000000E+000",
                "CRVAL1  =  1.000000000000E+006",
                "RADESYS = 'FK5     '\nEQUINOX =               2000.0\nSPECSYS = 'SOURCE  '\n",
                "",
                "MJD-OBS =            52413.59\n",
                ""));
        assertEquals(0.110201354300e12 + 1.0e6, store.crval[0], 1.0);
    }

    @Test
    void readClassGlsToSfl() {
        FitsStore store = translated(classHeader("RA---TAN", "GLON-GLS", "DEC--TAN", "GLAT-GLS"));
        assertEquals("GLON-SFL", store.ctype[1]);
        assertEquals("GLAT-SFL", store.ctype[2]);
    }

    @Test
    void readClassSsyssrcFromVeloLsr() {
        FitsStore store = translated(CLASS_HEADER);
        assertEquals("SOURCE", store.specsys);
        assertEquals("LSRK", store.ssyssrc);
    }

    @Test
    void readClassSsyssrcFromVeloHel() {
        FitsStore store = translated(classHeader("VELO-LSR", "VELO-HEL", "SPECSYS = 'SOURCE  '\n", ""));
        assertEquals("SOURCE", store.specsys);
        assertEquals("BARYCENT", store.ssyssrc);
    }

    /**
     * The description of Rust's {@code write_class_subtracts_restfreq} and {@code
     * write_class_sfl_to_gls}: a FREQ axis at the rest frequency and celestial axes {@code lontype}
     * and {@code lattype} with reference point ({@code lon}, {@code lat}).
     */
    private static FitsPlaneStores writeStores(String lontype, String lattype, double lon, double lat) {
        FitsPlaneStores stores = new FitsPlaneStores(3);
        FitsStore store = stores.version(' ', 3);
        store.ctype[0] = "FREQ";
        store.crval[0] = 0.110201354300e12;
        store.crpix[0] = 512.5;
        store.cdelt[0] = 39062.5;
        store.ctype[1] = lontype;
        store.crval[1] = lon;
        store.crpix[1] = 0.5;
        store.cdelt[1] = -0.01;
        store.ctype[2] = lattype;
        store.crval[2] = lat;
        store.crpix[2] = 0.5;
        store.cdelt[2] = 0.01;
        store.restfrq = 0.110201354300e12;
        store.specsys = "SOURCE";
        store.cunit[0] = "Hz";
        return stores;
    }

    /** The image dimensions of a FitsChan without NAXISi cards. */
    private static double[] unknownDims() {
        return new double[] {AstObject.AST__BAD, AstObject.AST__BAD, AstObject.AST__BAD};
    }

    @Test
    void writeClassSubtractsRestfreq() {
        FitsPlaneStores stores = writeStores("RA---TAN", "DEC--TAN", 40.738, 62.933);
        stores.get(' ').radesys = "FK5";
        stores.get(' ').equinox = 2000.0;
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncClass.classFromStore(
                fc, stores, new FrameSet(new Frame(3)), unknownDims(), "astWrite", "FitsChan"));
        assertEquals(0.0, fc.getFitsF("CRVAL1"), 1.0);
        assertEquals(40.738, fc.getFitsF("CRVAL2"), 1e-10);
    }

    @Test
    void writeClassSflToGls() {
        // An SFL projection at the origin, the only one FITS-CLASS can hold, is written with the
        // older code GLS.
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncClass.classFromStore(
                fc,
                writeStores("GLON-SFL", "GLAT-SFL", 0.0, 0.0),
                new FrameSet(new Frame(3)),
                unknownDims(),
                "astWrite",
                "FitsChan"));
        assertEquals("GLON-GLS", fc.getFitsS("CTYPE2"));
        assertEquals("GLAT-GLS", fc.getFitsS("CTYPE3"));
    }

    @Test
    void aDescriptionWithoutSpecsysIsRefused() {
        FitsPlaneStores stores = writeStores("RA---TAN", "DEC--TAN", 40.738, 62.933);
        stores.get(' ').specsys = null;
        assertFalse(FitsEncClass.classFromStore(
                new FitsChan(), stores, new FrameSet(new Frame(3)), unknownDims(), "astWrite", "FitsChan"));
    }

    /** A FitsPlaneStores whose primary description has the values of {@code st} CLASSFromStore uses. */
    private static FitsPlaneStores storesOf(FitsStore st) {
        FitsPlaneStores stores = new FitsPlaneStores(3);
        FitsStore out = stores.version(' ', 3);
        for (int i = 0; i < 3; i++) {
            out.ctype[i] = st.ctype[i];
            out.crval[i] = st.crval[i];
            out.crpix[i] = st.crpix[i];
            out.cdelt[i] = st.cdelt[i];
            out.cunit[i] = st.cunit[i];
        }
        out.specsys = st.specsys;
        out.restfrq = st.restfrq;
        out.radesys = st.radesys;
        out.equinox = st.equinox;
        out.mjdobs = st.mjdobs;
        return stores;
    }

    @Test
    void roundTripClass() {
        // Read, written and read again, both reads translated as a FITS-CLASS read is. The header
        // gives no CUNIT1; a description made from a FrameSet has the SpecFrame's unit, which
        // FITS-CLASS needs.
        FitsStore store = translated(CLASS_HEADER);
        store.cunit[0] = "Hz";
        FitsChan fc = new FitsChan();
        assertTrue(FitsEncClass.classFromStore(
                fc, storesOf(store), new FrameSet(new Frame(3)), unknownDims(), "astWrite", "FitsChan"));
        FitsStore store2 = translated(String.join("\n", FitsHeaders.foundCards(fc)) + "\n");
        assertEquals(store.crval[0], store2.crval[0], 1.0);
        assertEquals(store.crval[1], store2.crval[1], 1e-10);
    }

    @Test
    void spectralCdeltIsScaledToHertzOnce() {
        // CLASSFromStore converts the spectral CRVAL and CDELT of a description in kHz to Hz once,
        // for the cards and for the VELO-LSR and DELTAV values, so a description in kHz is written
        // as the same description in Hz is (the fix the pinned C has as 17416d33). The FrameSet,
        // read from CLASS_HEADER, gives the SpecFrame the velocities are found with.
        FitsChan in = FitsChan.fromHeader(CLASS_HEADER);
        in.setEncoding("FITS-CLASS");
        FrameSet fs = FitsHeaders.readFrameSet(in);
        List<List<String>> written = new java.util.ArrayList<>();
        for (String unit : new String[] {"Hz", "kHz"}) {
            double f = "Hz".equals(unit) ? 1.0 : 1.0e3;
            FitsPlaneStores stores = writeStores("RA---TAN", "DEC--TAN", 40.738, 62.933);
            FitsStore st = stores.get(' ');
            st.crval[0] = 1.1e11 / f;
            st.cdelt[0] = 39062.5 / f;
            st.cunit[0] = unit;
            FitsChan fc = new FitsChan();
            assertTrue(FitsEncClass.classFromStore(fc, stores, fs, unknownDims(), "astWrite", "FitsChan"), unit);
            written.add(FitsHeaders.foundCards(fc));
        }
        assertEquals(written.get(0), written.get(1));
        assertTrue(written.get(0).stream().anyMatch(c -> c.startsWith("DELTAV")), String.join("\n", written.get(0)));
    }

    @Test
    void aSpectrumWithAReferencePositionIsWrittenAsACube() {
        // The one-axis FITS-CLASS header class-spec.head, read, given RefRA and RefDec, and
        // written back as FITS-CLASS: MakeFitsFrameSet adds celestial axes at the reference
        // position, and CLASSFromStore writes the description as an image of three axes, setting
        // NAXIS to 3 and putting NAXIS2 and NAXIS3 of 1 after NAXIS1. The cards are C's (C probe
        // write_set_probe of the task report: astRead, astSet, astWrite to the same FitsChan).
        FitsChan fc = FitsHeaders.fixture("wcsconv/inputs/class-spec.head");
        fc.setEncoding("FITS-CLASS");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        fs.set("RefRA=10:00:00,RefDec=20:00:00");
        assertEquals(1, fc.write(fs));
        List<String> want = FitsHeaders.padded(
                "SIMPLE  =                    T / Fits standard",
                "BITPIX  =                  -32 / Bits per pixel",
                "NAXIS   =                    3 / Number of dimensions",
                "NAXIS1  =                 1024 / Spectral axis",
                "CTYPE1  = 'FREQ    '           / spectral axis is frequency",
                "CRVAL1  =  -110201350647.34827 / Value at ref. pixel on axis 1",
                "CDELT1  =   19531.672938506857 / Pixel size",
                "CRPIX1  =                579.0 / Reference pixel on axis 1",
                "NAXIS2  =                    1",
                "NAXIS3  =                    1",
                "LINE    = '13co10  '           / Line name",
                "RESTFREQ=       110201354300.0 / Rest frequency",
                "VELO-LSR=   299792438.17278373 / [m/s] Reference velocity",
                "CRPIX2  =                  1.0 / Reference pixel on axis 2",
                "CRPIX3  =                  1.0 / Reference pixel on axis 3",
                "CRVAL2  =                150.0 / Value at ref. pixel on axis 2",
                "CRVAL3  =                 20.0 / Value at ref. pixel on axis 3",
                "CTYPE2  = 'RA---TAN'           / Type of co-ordinate on axis 2", // codespell:ignore
                "CTYPE3  = 'DEC--TAN'           / Type of co-ordinate on axis 3", // codespell:ignore
                "CDELT2  = -0.017453292519943295 / Pixel size",
                "CDELT3  = 0.017453292519943295 / Pixel size",
                "EQUINOX =               2000.0 / Epoch of reference equinox",
                "DATE-OBS= '2000-01-01T11:58:55.816' / Date of observation",
                "OBJECT  = '        '",
                "VLSR    =   299792438.17278373 / [m/s] Reference velocity",
                "DELTAV  =  -106.31700652837753 / [m/s] Velocity resolution",
                "IMAGFREQ=    97250382992.51059 / [Hz] Image frequency",
                "END");
        List<String> got = FitsHeaders.foundCards(fc);
        assertTrue(FitsHeaders.cardsMatchC(got, want), "--- Java\n" + String.join("\n", got));
    }

    /**
     * C probe p2's FrameSet: {@code npix} pixel axes; a world Frame, a CmpFrame of a plain Frame
     * with Symbol FREQ and Unit Hz and the FK5 SkyFrame of a TAN header, and with four pixel axes a
     * plain Frame with Symbol STOKES; the frequency axis linear.
     */
    private static FrameSet freqFrameSet(int npix) {
        FrameSet sky2 = FitsHeaders.readFrameSet(FitsHeaders.chan(
                "NAXIS   = 2",
                "CRPIX1  = 5",
                "CRPIX2  = 5",
                "CRVAL1  = 10",
                "CRVAL2  = 20",
                "CDELT1  = -0.001",
                "CDELT2  = 0.001",
                "CTYPE1  = 'RA---TAN'",
                "CTYPE2  = 'DEC--TAN'",
                "RADESYS = 'FK5'",
                "EQUINOX = 2000"));
        Mapping skymap = sky2.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Frame skyfrm = sky2.getFrame(FrameSet.AST__CURRENT);
        Frame freq = new Frame(1);
        freq.set("Symbol(1)=FREQ,Unit(1)=Hz,Label(1)=Frequency");
        Frame wcs = new CmpFrame(freq, skyfrm);
        WinMap fmap = new WinMap(
                1, new double[] {0.0}, new double[] {1.0}, new double[] {1.0e11}, new double[] {1.0e11 + 1.0e6});
        Mapping map = new CmpMap(fmap, skymap, false);
        Frame pix = new Frame(npix);
        pix.setDomain("GRID");
        if (npix == 4) {
            Frame stokes = new Frame(1);
            stokes.set("Symbol(1)=STOKES");
            wcs = new CmpFrame(wcs, stokes);
            map = new CmpMap(map, new UnitMap(1), false);
        }
        FrameSet fs = new FrameSet(pix);
        fs.addFrame(FrameSet.AST__BASE, map, wcs);
        return fs;
    }

    /** A FitsChan holding the NAXIS cards of C probe p2, with Encoding {@code enc}. */
    private static FitsChan naxisChan(int npix, int naxis2, String enc) {
        FitsChan fc = npix == 4
                ? FitsHeaders.chan("NAXIS   = 4", "NAXIS1  = 10", "NAXIS2  = " + naxis2, "NAXIS3  = 1", "NAXIS4  = 1")
                : FitsHeaders.chan("NAXIS   = 3", "NAXIS1  = 10", "NAXIS2  = " + naxis2, "NAXIS3  = 1");
        fc.setEncoding(enc);
        return fc;
    }

    @Test
    void aFrequencyAxisWithoutSpecFrameIsRefused() {
        // C probe p2 (a) and (b): the description has a spectral axis (the FITS-WCS write gives
        // CTYPE1 FREQ) but no SPECSYS, so CLASSFromStore refuses it, with the spatial axes
        // spanning one pixel (when it first transforms pixel (1, 1, 1)) or not.
        FrameSet fs = freqFrameSet(3);
        FitsChan wcs = naxisChan(3, 1, "FITS-WCS");
        assertEquals(1, wcs.write(fs));
        List<String> want = Arrays.asList(
                "NAXIS   =                    3                                                  ",
                "NAXIS1  =                   10                                                  ",
                "NAXIS2  =                    1                                                  ",
                "NAXIS3  =                    1                                                  ",
                "CRPIX1  =                  5.0 / Reference pixel on axis 1                      ",
                "CRPIX2  =                  5.0 / Reference pixel on axis 2                      ",
                "CRPIX3  =                  5.0 / Reference pixel on axis 3                      ",
                "CRVAL1  =       100005000000.0 / Value at ref. pixel on axis 1                  ",
                "CRVAL2  =                 10.0 / Value at ref. pixel on axis 2                  ",
                "CRVAL3  =                 20.0 / Value at ref. pixel on axis 3                  ",
                "CTYPE1  = 'FREQ    '           / Frequency                                      ",
                "CTYPE2  = 'RA---TAN'           / Type of co-ordinate on axis 2                  ", // codespell:ignore
                "CTYPE3  = 'DEC--TAN'           / Type of co-ordinate on axis 3                  ", // codespell:ignore
                "CDELT1  =            1000000.0 / Pixel size on axis 1                           ",
                "CDELT2  =               -0.001 / Pixel size on axis 2                           ",
                "CDELT3  =                0.001 / Pixel size on axis 3                           ",
                "CUNIT1  = 'Hz      '           / Units for axis 1                               ",
                "MJD-OBS =   51544.499257130781 / Modified Julian Date of observation            ",
                "DATE-OBS= '2000-01-01T11:58:55.816' / Date of observation                       ",
                "RADESYS = 'FK5     '           / Reference frame for RA/DEC values              ",
                "EQUINOX =               2000.0 / [yr] Epoch of reference equinox                ");
        List<String> got = FitsHeaders.foundCards(wcs);
        assertTrue(FitsHeaders.cardsMatchC(got, want), "--- Java\n" + String.join("\n", got));

        for (int naxis2 : new int[] {1, 5}) {
            FitsChan fc = naxisChan(3, naxis2, "FITS-CLASS");
            assertEquals(0, fc.write(fs), "NAXIS2 = " + naxis2);
            assertEquals(4, FitsHeaders.foundCards(fc).size(), "NAXIS2 = " + naxis2);
        }
    }

    @Test
    void oneSpatialPixelOfAFourAxisImageFailsAsC() {
        // C probe p2 (c): with four pixel axes and the spatial axes spanning one pixel,
        // CLASSFromStore transforms pixel (1, 1, 1) with three coordinates, which the FrameSet's
        // four inputs refuse.
        FrameSet fs = freqFrameSet(4);
        FitsChan wcs = naxisChan(4, 1, "FITS-WCS");
        assertEquals(1, wcs.write(fs));
        FitsChan fc = naxisChan(4, 1, "FITS-CLASS");
        AstException e = assertThrows(AstException.class, () -> fc.write(fs));
        assertEquals(AstException.AST__NCPIN, e.getStatus());
        assertTrue(
                e.getMessage()
                        .contains("astTranN(FrameSet): Bad number of input coordinate values (3).\nThe FrameSet given"
                                + " requires 4 coordinate values for each input point."),
                e.getMessage());
        assertEquals(5, FitsHeaders.foundCards(fc).size());
    }

    @Test
    void aSpectralAxisBeyondTheWrittenAxesIsAnInternalError() {
        // CRPIX is set for axis 1 only, so one axis is written but the spectral axis is the third;
        // C then writes past its CDELT array. A FrameSet always gives a CRPIX for every axis, so
        // this is unreachable by a write.
        FitsPlaneStores stores = writeStores("RA---TAN", "DEC--TAN", 40.738, 62.933);
        FitsStore st = stores.get(' ');
        st.ctype[0] = "RA---TAN";
        st.ctype[1] = "DEC--TAN";
        st.ctype[2] = "FREQ";
        st.cunit[0] = null;
        st.cunit[2] = "Hz";
        st.crpix[1] = null;
        st.crpix[2] = null;
        st.radesys = "FK5";
        st.equinox = 2000.0;
        AstException e = assertThrows(
                AstException.class,
                () -> FitsEncClass.classFromStore(
                        new FitsChan(), stores, new FrameSet(new Frame(3)), unknownDims(), "astWrite", "FitsChan"));
        assertEquals(AstException.AST__INTER, e.getStatus());
        assertTrue(e.getMessage().contains("CLASSFromStore"), e.getMessage());
    }
}
