package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/other_axes.rs} and {@code
 * log_axis.rs} (C {@code OtherAxes}, {@code LogAxis}, {@code AddUnitMaps} and {@code SetAlgCode},
 * fitschan.c) that need no class this library lacks, and tests of the branches that reach one.
 * Expectations not from a Rust test are worked from C's code.
 *
 * <p>Not translated: {@code log_axis.rs} {@code linear_axis_is_not_log} and {@code
 * detects_log_axis_and_returns_inverse_mapping}, which build C's MathMap (tested here as the throw
 * {@code needs MathMap}), and {@code out_of_range_axis_returns_none}, a check C does not make.
 */
class FitsOtherAxesTest {

    private static final double BAD = AstObject.AST__BAD;

    /** A FrameSet from a GRID Frame to {@code current} through {@code map}. */
    private static FrameSet frameSet(Mapping map, Frame current) {
        Frame pixel = new Frame(map.getNin());
        pixel.setDomain("GRID");
        FrameSet fs = new FrameSet(pixel);
        fs.addFrame(FrameSet.AST__BASE, map, current);
        return fs;
    }

    /** Rust's {@code build_2axis_basic_frameset}. */
    private static FrameSet twoAxisFrameSet() {
        Frame wcs = new Frame(2);
        wcs.setLabel(1, "world x");
        wcs.setLabel(2, "world y");
        wcs.setSymbol(1, "WX");
        wcs.setSymbol(2, "WY");
        wcs.setUnit(1, "m");
        wcs.setUnit(2, "m");
        return frameSet(new UnitMap(2), wcs);
    }

    private static Mapping otherAxes(
            FitsChan fc, FrameSet fs, double[] dim, FitsStore store, double[] crvals, boolean[] axisDone) {
        int nwcs = fs.getNout();
        int[] wperm = new int[nwcs];
        for (int i = 0; i < nwcs; i++) {
            wperm[i] = i;
        }
        return FitsOtherAxes.otherAxes(fc, fs, dim, wperm, ' ', store, crvals, axisDone, "FitsChan", "astWrite");
    }

    @Test
    void twoAxesBasicFrameUnitPixelToWcs() {
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        Mapping map = otherAxes(new FitsChan(), twoAxisFrameSet(), new double[] {100, 200}, store, null, axisDone);

        assertTrue(axisDone[0] && axisDone[1]);
        assertEquals("WX", store.ctype[0]);
        assertEquals("WY", store.ctype[1]);
        assertEquals("m", store.cunit[0]);
        assertEquals("m", store.cunit[1]);
        assertEquals("world x", store.ctypeCom[0]);
        assertEquals("world x", store.cname[0]);
        // The centre pixel (floor(50), floor(100)) under the identity.
        assertEquals(50.0, store.crval[0]);
        assertEquals(100.0, store.crval[1]);

        assertEquals(2, map.getNin());
        assertEquals(2, map.getNout());
        double[] out = map.tranN(1, 2, new double[] {50, 100}, true, 2);
        assertArrayEquals(new double[] {0, 0}, out, 1e-12);
    }

    @Test
    void noUnclaimedAxesReturnsUnitMap() {
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = {true, true};
        Mapping map = otherAxes(new FitsChan(), twoAxisFrameSet(), new double[] {100, 100}, store, null, axisDone);
        assertNull(store.ctype[0]);
        assertNull(store.ctype[1]);
        assertEquals(2, map.getNin());
        assertEquals(2, map.getNout());
        assertInstanceOf(UnitMap.class, map);
    }

    @Test
    void overrideCrvalTakesPrecedence() {
        FitsStore store = new FitsStore(2);
        otherAxes(
                new FitsChan(),
                twoAxisFrameSet(),
                new double[] {100, 100},
                store,
                new double[] {7.5, BAD},
                new boolean[2]);
        assertEquals(7.5, store.crval[0]);
        assertEquals(50.0, store.crval[1]);
    }

    @Test
    void partialPreClaimedAxesProducesOneAxisMapping() {
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = {true, false};
        Mapping map = otherAxes(new FitsChan(), twoAxisFrameSet(), new double[] {100, 100}, store, null, axisDone);
        assertTrue(axisDone[0] && axisDone[1]);
        assertNull(store.ctype[0]);
        assertEquals("WY", store.ctype[1]);
        assertEquals(50.0, store.crval[1]);
        double[] out = map.tranN(1, 2, new double[] {10, 50}, true, 2);
        assertArrayEquals(new double[] {10, 0}, out, 1e-12);
    }

    @Test
    void unknownDimUsesPixelOne() {
        FitsStore store = new FitsStore(2);
        otherAxes(new FitsChan(), twoAxisFrameSet(), new double[] {BAD, 200}, store, null, new boolean[2]);
        assertEquals(1.0, store.crval[0]);
        assertEquals(100.0, store.crval[1]);
    }

    @Test
    void fallbackAxisLabelWhenNoSymbol() {
        // A Frame's default Symbol (frame.c GetDefaultSymbol) is "x" and the axis number.
        FitsStore store = new FitsStore(1);
        otherAxes(
                new FitsChan(), frameSet(new UnitMap(1), new Frame(1)), new double[] {50}, store, null, new boolean[1]);
        assertEquals("x1", store.ctype[0]);
        assertEquals("Type of co-ordinate on axis 1", store.ctypeCom[0]); // codespell:ignore
        assertNull(store.cunit[0]);
        assertNull(store.cname[0]);
    }

    @Test
    void blankSymbolGivesAxisN() {
        Frame wcs = new Frame(2);
        wcs.setSymbol(2, "");
        FitsStore store = new FitsStore(2);
        otherAxes(new FitsChan(), frameSet(new UnitMap(2), wcs), new double[] {50, 50}, store, null, new boolean[2]);
        assertEquals("x1", store.ctype[0]);
        assertEquals("AXIS2", store.ctype[1]);
    }

    @Test
    void labelEqualToSymbolIsNotACname() {
        Frame wcs = new Frame(1);
        wcs.setSymbol(1, "WX");
        wcs.setLabel(1, "WX");
        FitsStore store = new FitsStore(1);
        otherAxes(new FitsChan(), frameSet(new UnitMap(1), wcs), new double[] {50}, store, null, new boolean[1]);
        assertEquals("Type of co-ordinate on axis 1", store.ctypeCom[0]); // codespell:ignore
        assertNull(store.cname[0]);
    }

    @Test
    void badCentreValueStopsTheAxisLoop() {
        // C breaks out of its axis loop at an axis with no CRVAL, leaving it and later axes
        // undescribed.
        PermMap pm = new PermMap(2, new int[] {1, -1}, 2, new int[] {1, -1}, new double[] {BAD});
        Frame wcs = new Frame(2);
        FitsStore store = new FitsStore(2);
        boolean[] axisDone = new boolean[2];
        otherAxes(new FitsChan(), frameSet(pm, wcs), new double[] {100, 100}, store, null, axisDone);
        assertTrue(axisDone[0]);
        assertEquals(50.0, store.crval[0]);
        assertFalse(axisDone[1]);
        assertNull(store.crval[1]);
        assertNull(store.ctype[1]);
    }

    /** A 1-D FrameSet whose world axis is {@code c0 + c1 p + c2 p^2} of the pixel axis. */
    private static FrameSet quadratic(double c0, double c1, double c2) {
        PolyMap pm = new PolyMap(1, 1, 3, new double[] {c0, 1, 0, c1, 1, 1, c2, 1, 2}, 0, new double[0]);
        pm.set("IterInverse=1");
        Frame wcs = new Frame(1);
        wcs.setDomain("WORLD");
        return frameSet(pm, wcs);
    }

    @Test
    void nonLinearAxisWithNonZeroCrvalNeedsMathMap() {
        // C's LogAxis makes a MathMap for any CRVAL other than zero, before testing whether the
        // axis is logarithmic.
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> otherAxes(
                        new FitsChan(),
                        quadratic(10, 1, 0.01),
                        new double[] {100},
                        new FitsStore(1),
                        null,
                        new boolean[1]));
        assertTrue(e.getMessage().endsWith("needs MathMap"), e.getMessage());
    }

    @Test
    void nonLinearAxisWithZeroCrvalIsDescribedAsLinear() {
        // C probe: a quadratic axis that is zero at the centre pixel is written by nothing (the
        // write returns 0); OtherAxes gives it the linear description that MakeIntWorld rejects.
        FitsStore store = new FitsStore(1);
        boolean[] axisDone = new boolean[1];
        Mapping map = otherAxes(new FitsChan(), quadratic(-25, 0, 0.01), new double[] {100}, store, null, axisDone);
        assertTrue(axisDone[0]);
        assertEquals(0.0, store.crval[0]);
        assertEquals("WORLD1", store.ctype[0]);
        assertArrayEquals(new double[] {3}, map.tran1(1, new double[] {3}, true), 0);
    }

    @Test
    void forceTabWithTabOkNeedsFitsTable() {
        // C probe: TabOK=1 and ForceTab=1 write a linear axis as WORL-TAB.
        FitsChan fc = new FitsChan();
        fc.set("TabOK=1,ForceTab=1");
        FrameSet fs = frameSet(new ShiftMap(new double[] {5}), new Frame(1));
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> otherAxes(fc, fs, new double[] {100}, new FitsStore(1), null, new boolean[1]));
        assertTrue(e.getMessage().endsWith("needs FitsTable"), e.getMessage());
    }

    @Test
    void zeroCrvalNonLinearAxisWithTabOkNeedsFitsTable() {
        // C probe: with TabOK=1 the quadratic axis that is zero at the centre is written as WORL-TAB.
        FitsChan fc = new FitsChan();
        fc.set("TabOK=1");
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> otherAxes(
                        fc, quadratic(-25, 0, 0.01), new double[] {100}, new FitsStore(1), null, new boolean[1]));
        assertTrue(e.getMessage().endsWith("needs FitsTable"), e.getMessage());
    }

    @Test
    void forceTabWithoutTabOkIsDescribedAsLinear() {
        FitsChan fc = new FitsChan();
        fc.set("ForceTab=1");
        FitsStore store = new FitsStore(1);
        otherAxes(
                fc,
                frameSet(new ShiftMap(new double[] {5}), new Frame(1)),
                new double[] {100},
                store,
                null,
                new boolean[1]);
        assertEquals(55.0, store.crval[0]);
        assertEquals("x1", store.ctype[0]);
    }

    @Test
    void logAxisZeroCrvalReturnsNull() {
        assertNull(FitsOtherAxes.logAxis(new UnitMap(1), 0, 1, new double[] {1}, new double[] {100}, 0.0));
    }

    @Test
    void addUnitMapsPlacesTheMapping() {
        Mapping m = FitsOtherAxes.addUnitMaps(new ShiftMap(new double[] {-5}), 1, 3);
        assertEquals(3, m.getNin());
        assertArrayEquals(new double[] {1, 2, 3}, m.tranN(1, 3, new double[] {1, 7, 3}, true, 3), 0);
        assertInstanceOf(ShiftMap.class, FitsOtherAxes.addUnitMaps(new ShiftMap(new double[] {-5}), 0, 1));
    }

    @Test
    void setAlgCodePadsAndTruncates() {
        assertEquals("WORL-LOG", FitsOtherAxes.setAlgCode("WORLD1", "-LOG"));
        assertEquals("X----TAB", FitsOtherAxes.setAlgCode("X", "-TAB"));
        assertEquals("FREQ-LOG", FitsOtherAxes.setAlgCode("FREQ", "-LOG"));
    }
}
