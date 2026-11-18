package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_dsbspecframe_read.rs}: a FITS-CLASS header with an IMAGFREQ
 * keyword describes its spectral axis with a DSBSpecFrame, whose IF, DSBCentre and SideBand C's
 * {@code DSBSetUp} (fitschan.c) sets. The values are those C gives reading the same fixtures and
 * picking the spectral axis ({@code astGetC} of DSBCentre, IF and SideBand).
 */
class DSBSpecFrameReadTest {

    /**
     * Checks the DSBSpecFrame read from the wcsconv fixture {@code name} with the FITS-CLASS
     * encoding, on world axis {@code axis}, against C's values.
     */
    private static void assertDsb(String name, int axis, String sideBand, double ifGhz, double centre, double tol) {
        FitsChan fc = FitsHeaders.fixture("wcsconv/inputs/" + name);
        fc.setEncoding("FITS-CLASS");
        FrameSet fs = FitsHeaders.readFrameSet(fc);
        Frame dsb = fs.pickAxes(1, new int[] {axis}, null);
        assertEquals("DSBSpecFrame", dsb.astClass());
        assertEquals(sideBand, dsb.getC("SideBand"), "SideBand");
        assertEquals(ifGhz, dsb.getD("IF"), 1e-6, "IF (GHz)");
        assertEquals(centre, dsb.getD("DSBCentre"), tol, "DSBCentre (Hz, source rest frame)");
    }

    @Test
    void classWriteBuildsDsbSpecFrame() {
        // class-write.head: CTYPE1 = FREQ, with IMAGFREQ.
        assertDsb("class-write.head", 1, "USB", -6.459953212758027, 110201354300.00003, 1.0);
    }

    @Test
    void janBuildsDsbSpecFrame() {
        // jan.fits-class: CTYPE3 = FREQ, with IMAGFREQ.
        assertDsb("jan.fits-class", 3, "USB", -3.9999795728960574, 220398648224.8721, 10.0);
    }
}
