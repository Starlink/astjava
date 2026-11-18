package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A FrameSet whose description in FITS-WCS needs a class this library lacks makes {@link
 * FitsChan#write} throw an {@link UnsupportedOperationException} naming the class, at the point C
 * reaches it, and leaves the cards as they were. Each FrameSet is one a C-oracle probe linked
 * against the pinned C library wrote, with the outcome noted beside it.
 */
class FitsWriteUnsupportedTest {

    /** A 1-D FrameSet whose world axis is {@code c0 + c1 p + c2 p^2} of the pixel axis. */
    private static FrameSet quadratic(double c0, double c1, double c2) {
        PolyMap pm = new PolyMap(1, 1, 3, new double[] {c0, 1, 0, c1, 1, 1, c2, 1, 2}, 0, new double[0]);
        pm.set("IterInverse=1");
        Frame pixel = new Frame(1);
        pixel.setDomain("GRID");
        Frame wcs = new Frame(1);
        wcs.setDomain("WORLD");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, pm, wcs);
        return fs;
    }

    private static FitsChan chan(String attrs) {
        FitsChan fc = FitsChan.fromHeader(String.join("\n", "NAXIS   = 1", "NAXIS1  = 100"));
        fc.setEncoding("FITS-WCS");
        if (!attrs.isEmpty()) {
            fc.set(attrs);
        }
        return fc;
    }

    private static void assertNeeds(String cls, FitsChan fc, FrameSet fs) {
        String before = fc.toHeader();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> fc.write(fs));
        assertTrue(e.getMessage().endsWith("needs " + cls), e.getMessage());
        assertEquals(before, fc.toHeader());
    }

    @Test
    void aNonLinearAxisThatIsNotLogarithmicIsNotWritten() {
        // C's LogAxis finds the logarithm of this quadratic not linear, and the axis described as
        // linear is too far from linear for MakeIntWorld (C probe loglin.c: the write returns 0).
        FitsChan fc = chan("");
        String before = fc.toHeader();
        assertEquals(0, fc.write(quadratic(10, 1, 0.01)));
        assertEquals(before, fc.toHeader());
        // With TabOK set, -TAB comes after LogAxis (C probe loglin.c: written as WORL-TAB).
        assertNeeds("FitsTable", chan("TabOK=1"), quadratic(10, 1, 0.01));
    }

    @Test
    void aNonLinearAxisWithZeroCrvalIsNotWritten() {
        // LogAxis returns at once for a zero CRVAL, and with TabOK zero the axis is described as
        // linear, which MakeIntWorld rejects (C probe: the write returns 0).
        FitsChan fc = chan("");
        String before = fc.toHeader();
        assertEquals(0, fc.write(quadratic(-25, 0, 0.01)));
        assertEquals(before, fc.toHeader());
    }

    @Test
    void aTableWithTabOkIsUnsupported() {
        // C probe tab_zero_crval: TabOK=1 and a non-linear axis whose CRVAL is zero, written as
        // WORL-TAB; IsMapTab1D samples the axis and stores it in a FitsTable, reached before any
        // LutMap.
        assertNeeds("FitsTable", chan("TabOK=1"), quadratic(-25, 0, 0.01));
        // C probe tab_force: ForceTab describes even a linear axis with -TAB.
        Frame pixel = new Frame(1);
        Frame wcs = new Frame(1);
        wcs.setDomain("WORLD");
        FrameSet linear = new FrameSet(pixel);
        linear.addFrame(FrameSet.AST__BASE, new ShiftMap(new double[] {5}), wcs);
        assertNeeds("FitsTable", chan("TabOK=1,ForceTab=1"), linear);
    }
}
