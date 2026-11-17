package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_fitschan_encodings.rs}: the encoding a FitsChan guesses for
 * a header of each encoding, and the FrameSets the FITS-IRAF, FITS-PC, FITS-CLASS and DSS reads give. Each
 * fixture line is given to {@code putFits}, and the values expected are those C gives (probe
 * {@code c2.c} beside the task report).
 *
 * <p>{@code read_dss_produces_frameset} is translated in {@link FitsEncDssTest}.
 */
class FitsChanEncodingsTest {

    private static void assertGuess(String rel, String encoding) {
        assertEquals(encoding, FitsHeaders.fixture(rel).getEncoding(), rel);
    }

    /** The FrameSet read from {@code rel} with {@code encoding} set. */
    private static FrameSet read(String rel, String encoding) {
        FitsChan fc = FitsHeaders.fixture(rel);
        fc.setEncoding(encoding);
        return FitsHeaders.readFrameSet(fc);
    }

    /** A FrameSet of two Frames whose current Frame has two axes and the System {@code system}. */
    private static void assertSky(FrameSet fs, String system) {
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
        assertEquals(system, fs.getC("System"));
    }

    @Test
    void guessEncodingIraf() {
        // CDi_j keywords and RADECSYS.
        assertGuess("wcsconv/inputs/serpens.head", "FITS-IRAF");
    }

    @Test
    void guessEncodingPc() {
        // PC001001.
        assertGuess("wcsconv/inputs/pc-sparse.head", "FITS-PC");
    }

    @Test
    void guessEncodingAips() {
        // An AIPS spectral CTYPE (VELO-LSR).
        assertGuess("wcsconv/inputs/velo-aips.head", "FITS-AIPS");
    }

    @Test
    void guessEncodingClass() {
        // DELTAV and VELO-LSR.
        assertGuess("wcsconv/inputs/class-write.head", "FITS-CLASS");
    }

    @Test
    void guessEncodingDss() {
        // PLTRAH.
        assertGuess("wcsconv/expected/dss.dss", "DSS");
    }

    @Test
    void readIrafProducesFrameSet() {
        FrameSet fs = read("wcsconv/inputs/serpens.head", "FITS-IRAF");
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void readClassProducesFrameSet() {
        // A spectral axis and two celestial axes.
        FrameSet fs = read("wcsconv/inputs/class-write.head", "FITS-CLASS");
        assertEquals(2, fs.getNframe());
        assertEquals(3, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void readPcProducesFrameSet() {
        FrameSet fs = read("wcsconv/inputs/pc-sparse.head", "FITS-PC");
        assertEquals(2, fs.getNframe());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
    }

    @Test
    void irafFrameSetHasSky() {
        assertSky(read("wcsconv/inputs/serpens.head", "FITS-IRAF"), "FK5");
    }

    @Test
    void pcFrameSetHasSky() {
        assertSky(read("wcsconv/inputs/pc-sparse.head", "FITS-PC"), "FK5");
    }

    @Test
    void dssFrameSetHasSky() {
        assertSky(read("wcsconv/expected/dss.dss", "DSS"), "FK5");
    }

    @Test
    void readAipsSpectral() {
        // A one-axis spectral header read with the FITS-AIPS encoding: the pixel Frame and the
        // SpecFrame.
        FrameSet fs = read("wcsconv/inputs/velo-aips.head", "FITS-AIPS");
        assertEquals(2, fs.getNframe());
        assertEquals(1, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
        assertInstanceOf(SpecFrame.class, fs.getFrame(FrameSet.AST__CURRENT));
    }

    /**
     * A {@code FREQ-LOG} header read with FITS-WCS maps pixel to frequency through C's {@code
     * LogWcs} MathMap: the inverse transformation gives the pixel coordinates C gives for the same
     * frequencies (the oracle {@code headers.oracle}, section {@code freq-log.head}).
     */
    @Test
    void freqLogBuildsLogMapping() {
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.fixture("wcsconv/inputs/freq-log.head"));
        double[] sInputs = {
            1420399999.4884999,
            1420400000.5115,
            1420399999.4884999,
            1420400000.0,
            1420400000.0,
            1420399999.74425,
            1420400000.25575,
            1420399999.616375,
            1420400000.127875,
            1420399999.8721251,
            1420400000.383625,
            1420399999.5524375,
            1420400000.0639377,
            1420399999.8081875,
            1420400000.3196874,
            1420399999.6803126,
            1420400000.1918123,
            1420399999.9360623,
            1420400000.4475627,
            1420399999.5204687,
            1420400000.0319688,
            1420399999.7762187,
            1420400000.2877188,
            1420399999.6483438,
        };
        double[] expectedPixels = {
            0.9998384814760453,
            1023.999845942171,
            0.9998384814760453,
            512.5,
            512.5,
            256.75007695984095,
            768.24992299411,
            128.87495772641466,
            640.3751191988895,
            384.62519618175503,
            896.125042169975,
            64.9375558014628,
            576.4375596008838,
            320.6874788761586,
            832.1874825834816,
            192.8126750406453,
            704.3123634018605,
            448.5622827001597,
            960.0626017535905,
            32.968775989868405,
            544.4687798008017,
            288.7186990703203,
            800.2187027891555,
            160.84373753585044,
        };
        double[] got = fs.tran1(sInputs.length, sInputs, false);
        for (int i = 0; i < sInputs.length; i++) {
            double tol = 1e-9 + 1e-9 * Math.abs(expectedPixels[i]);
            assertEquals(expectedPixels[i], got[i], tol, "point " + i + ": S=" + sInputs[i]);
        }
    }

    /**
     * The {@code -GRI} axis of {@code wcsconv/inputs/grism.head} is read with a GrismMap, and the
     * FrameSet's inverse gives C's pixel coordinates for C's wavelengths, every row of the section
     * {@code [grism.head nin=1 nout=1 dir=inverse]} of {@code headers.oracle}.
     */
    @Test
    void grismBuildsGrismMapping() {
        String rel = "wcsconv/inputs/grism.head";
        FrameSet fs = FitsHeaders.readFrameSet(FitsHeaders.fixture(rel));
        uk.ac.starlink.ast.testsupport.OracleSection inv = FitsHeaders.headersOracle(rel, false);
        for (int row = 1; row <= inv.npoint(); row++) {
            double s = FitsHeaders.oracleIn(inv, row)[0];
            double expected = FitsHeaders.oracleOut(inv, row)[0];
            double got = fs.tran1(1, new double[] {s}, false)[0];
            double tol = 1e-9 + 1e-9 * Math.abs(expected);
            assertEquals(expected, got, tol, "row " + row + ": S=" + s);
        }
    }
}
