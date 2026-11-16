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
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code grism_interference_order_survives_a_round_trip}
 * and {@code a_non_linear_grism_axis_writes_a_header_that_reads_back} (GrismMap, which the read of
 * their {@code WAVE-GRI} headers builds),
 * the four tests of the {@code lsst2.ast} FrameSet (SplineMap), and {@code
 * a_sip_description_is_rejected_when_it_is_not_linear_over_the_image} (MathMap).
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
}
