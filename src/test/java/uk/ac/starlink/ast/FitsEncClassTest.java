package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C's {@code CLASSFromStore}, which writes FITS-CLASS cards, as far as this library reaches it:
 * the checks it makes before it writes, which refuse every FrameSet without a SpecFrame, since
 * only a SpecFrame gives the description a SPECSYS; and the tests of Rust {@code
 * fitschan/enc_class.rs} that need no SpecFrame.
 *
 * <p>Rust's {@code write_class_subtracts_restfreq} and {@code write_class_sfl_to_gls} write a
 * description holding SPECSYS and a rest frequency; C's {@code CLASSFromStore} then makes a
 * SpecFrame for the VELO-LSR card, so they are translated to that ({@link
 * #aDescriptionClassCanHoldNeedsSpecFrame}). The other reads of FITS-CLASS headers and {@code
 * round_trip_class} need C's {@code ClassTrans}, part of the FITS-CLASS read, which is not
 * available. The FrameSets with a FREQ axis but no SpecFrame are C probe {@code p2.c}'s (beside
 * the task report).
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

    @Test
    void aDescriptionClassCanHoldNeedsSpecFrame() {
        // The descriptions of Rust's write_class_subtracts_restfreq (RA/DEC TAN) and
        // write_class_sfl_to_gls (galactic SFL at the origin): every check of CLASSFromStore
        // passes, and C then makes a SpecFrame to find the VELO-LSR value.
        String[][] cases = {{"RA---TAN", "DEC--TAN", "40.738", "62.933"}, {"GLON-SFL", "GLAT-SFL", "0.0", "0.0"}};
        for (String[] c : cases) {
            FitsPlaneStores stores = new FitsPlaneStores(3);
            FitsStore store = stores.version(' ', 3);
            store.ctype[0] = "FREQ";
            store.crval[0] = 0.110201354300e12;
            store.crpix[0] = 512.5;
            store.cdelt[0] = 39062.5;
            store.ctype[1] = c[0];
            store.crval[1] = Double.parseDouble(c[2]);
            store.crpix[1] = 0.5;
            store.cdelt[1] = -0.01;
            store.ctype[2] = c[1];
            store.crval[2] = Double.parseDouble(c[3]);
            store.crpix[2] = 0.5;
            store.cdelt[2] = 0.01;
            store.restfrq = 0.110201354300e12;
            store.radesys = "FK5";
            store.equinox = 2000.0;
            store.specsys = "SOURCE";
            store.cunit[0] = "Hz";
            FrameSet fs = new FrameSet(new Frame(3));
            double[] dim = {AstObject.AST__BAD, AstObject.AST__BAD, AstObject.AST__BAD};
            UnsupportedOperationException e = assertThrows(
                    UnsupportedOperationException.class,
                    () -> FitsEncClass.classFromStore(new FitsChan(), stores, fs, dim, "astWrite", "FitsChan"));
            assertTrue(e.getMessage().endsWith("needs SpecFrame"), e.getMessage());
            store.specsys = null;
            assertFalse(FitsEncClass.classFromStore(new FitsChan(), stores, fs, dim, "astWrite", "FitsChan"), c[0]);
        }
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
}
