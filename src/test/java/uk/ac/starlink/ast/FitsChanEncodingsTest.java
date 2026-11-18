package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_fitschan_encodings.rs}: the encoding a FitsChan guesses for
 * a header of each encoding, and the FrameSets the FITS-IRAF, FITS-PC and DSS reads give. Each
 * fixture line is given to {@code putFits}, and the values expected are those C gives (probe
 * {@code c2.c} beside the task report).
 *
 * <p>{@code read_dss_produces_frameset} is translated in {@link FitsEncDssTest}. Listed in {@code
 * deferred/rust-tests.txt}: {@code read_aips_spectral} (a spectral axis, SpecFrame), {@code
 * read_class_produces_frameset} (the FITS-CLASS read), {@code freq_log_builds_log_mapping}
 * (MathMap and SpecFrame) and {@code grism_builds_grism_mapping} (SpecFrame and GrismMap).
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
}
