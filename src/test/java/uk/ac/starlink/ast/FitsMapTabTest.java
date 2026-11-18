package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests of C's {@code IsMapTab1D}, {@code IsMapTab2D}, {@code AdaptLut} and {@code
 * ChangePermSplit} (fitschan.c), as far as this library can run them without FitsTable; the
 * expectations are read from C's code.
 */
class FitsMapTabTest {

    private static final double BAD = AstObject.AST__BAD;

    private static Mapping isMapTab1D(Mapping map, double[] dim, int iax) {
        return FitsMapTab.isMapTab1D(
                map, 1.0, null, new Frame(map.getNout()), dim, iax, iax, new int[1], new int[1], new int[1]);
    }

    /** A WinMap's outputs split off its inverse: with the grid dimension known C samples it into a FitsTable. */
    @Test
    void aSeparableAxisWithAKnownDimensionNeedsFitsTable() {
        WinMap win = new WinMap(new double[] {-1.0, 2.0}, new double[] {0.5, 3.0});
        UnsupportedOperationException e =
                assertThrows(UnsupportedOperationException.class, () -> isMapTab1D(win, new double[] {100, 100}, 1));
        assertTrue(e.getMessage().endsWith("needs FitsTable"), e.getMessage());
        assertFalse(win.getInvert());
    }

    /** Without the grid dimension C cannot sample the axis, and returns NULL. */
    @Test
    void aSeparableAxisWithAnUnknownDimensionIsNotTabulated() {
        WinMap win = new WinMap(new double[] {-1.0, 2.0}, new double[] {0.5, 3.0});
        int[] icolmain = {5};
        int[] icolindex = {5};
        int[] interp = {5};
        assertNull(FitsMapTab.isMapTab1D(
                win, 1.0, "deg", new Frame(2), new double[] {BAD, 100}, 0, 0, icolmain, icolindex, interp));
        assertEquals(-1, icolmain[0]);
        assertEquals(-1, icolindex[0]);
        assertEquals(0, interp[0]);
        // Axis 2's dimension is known.
        assertThrows(UnsupportedOperationException.class, () -> isMapTab1D(win, new double[] {BAD, 100}, 1));
        assertFalse(win.getInvert());
    }

    /**
     * A grid to WCS Mapping holding a LutMap applied forward: C takes the table from the LutMap,
     * and its interpolation method, whether or not the grid dimension is known, and makes a
     * FitsTable for it, which needs FitsTable. An inverted LutMap is not used, so with the
     * dimension unknown the axis is not tabulated.
     */
    @Test
    void aLutMapIsTabulatedWithoutTheGridDimension() {
        LutMap lut = new LutMap(new double[] {0.0, 1.0, 4.0, 9.0}, 1.0, 1.0);
        lut.setI("LutInterp", 1);
        CmpMap map = new CmpMap(new ZoomMap(1, 2.0), lut, true);
        int[] interp = {5};
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> FitsMapTab.isMapTab1D(
                        map, 1.0, null, new Frame(1), new double[] {BAD}, 0, 0, new int[1], new int[1], interp));
        assertTrue(e.getMessage().endsWith("needs FitsTable"), e.getMessage());
        assertEquals(1, interp[0]);
        assertFalse(map.getInvert());

        LutMap inv = new LutMap(new double[] {0.0, 1.0, 4.0, 9.0}, 1.0, 1.0);
        inv.invert();
        assertNull(isMapTab1D(new CmpMap(new ZoomMap(1, 2.0), inv, true), new double[] {BAD}, 0));
    }

    /** An output that depends on two grid axes cannot be split off, so is not tabulated. */
    @Test
    void aNonSeparableAxisIsNotTabulated() {
        MatrixMap rot = new MatrixMap(2, 2, new double[][] {{1.0, 0.5}, {-0.5, 1.0}});
        assertNull(isMapTab1D(rot, new double[] {100, 100}, 0));
        assertNull(isMapTab1D(rot, new double[] {100, 100}, 1));
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        assertNull(isMapTab1D(wm, new double[] {100, 100}, 0));
    }

    /**
     * IsMapTab2D needs both outputs separable; with one the columns of the other would be removed
     * from a FitsTable, reached only when it was made, which needs FitsTable first.
     */
    @Test
    void isMapTab2DNeedsBothOutputs() {
        MatrixMap rot = new MatrixMap(2, 2, new double[][] {{1.0, 0.5}, {-0.5, 1.0}});
        int[][] outs = new int[8][1];
        assertNull(FitsMapTab.isMapTab2D(
                rot,
                1.0,
                "deg",
                new Frame(2),
                new double[] {100, 100},
                0,
                1,
                0,
                1,
                outs[0],
                outs[1],
                outs[2],
                outs[3],
                outs[4],
                outs[5],
                outs[6],
                outs[7]));
        // A separable Mapping with unknown dimensions: both NULL.
        WinMap win = new WinMap(new double[] {-1.0, 2.0}, new double[] {0.5, 3.0});
        assertNull(FitsMapTab.isMapTab2D(
                win,
                1.0,
                "deg",
                new Frame(2),
                new double[] {BAD, BAD},
                0,
                1,
                0,
                1,
                outs[0],
                outs[1],
                outs[2],
                outs[3],
                outs[4],
                outs[5],
                outs[6],
                outs[7]));
    }

    /**
     * A linear Mapping is sampled once: the three interior samples and the end, appended after the
     * first sample.
     */
    @Test
    void adaptLutOfALinearMapping() {
        ZoomMap zoom = new ZoomMap(1, 2.0);
        double[][] work = {{1.0}, {2.0}};
        int[] nsamp = {1};
        FitsMapTab.adaptLut(zoom, 3, 0.25, 1.0, 9.0, 2.0, 18.0, work, nsamp);
        assertEquals(5, nsamp[0]);
        assertArrayEquals(new double[] {1, 3, 5, 7, 9}, Arrays.copyOf(work[0], 5));
        assertArrayEquals(new double[] {2, 6, 10, 14, 18}, Arrays.copyOf(work[1], 5));
    }

    /**
     * A quadratic departs from the chord by more than 0.25 in input, so each interval is
     * subdivided until the chords fit; the samples stay in increasing order and end at the end.
     */
    @Test
    void adaptLutSubdividesACurve() {
        PolyMap sq = new PolyMap(1, 1, 1, new double[] {1.0, 1, 2}, 0, new double[0]);
        double[] v = sq.tran1(2, new double[] {1.0, 100.0}, true);
        double[][] work = {{1.0}, {v[0]}};
        int[] nsamp = {1};
        FitsMapTab.adaptLut(sq, 3, 0.25, 1.0, 100.0, v[0], v[1], work, nsamp);
        assertTrue(nsamp[0] > 5, "subdivided: " + nsamp[0]);
        assertEquals(100.0, work[0][nsamp[0] - 1]);
        for (int i = 1; i < nsamp[0]; i++) {
            assertTrue(work[0][i] > work[0][i - 1]);
            assertEquals(work[0][i] * work[0][i], work[1][i], 1e-9 * work[1][i]);
        }
    }

    /** ChangePermSplit sets PermSplit on the PermMaps inside a compound Mapping. */
    @Test
    void changePermSplitReachesPermMapsInCmpMaps() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        CmpMap cmp = new CmpMap(new ZoomMap(2, 2.0), new CmpMap(pm, new UnitMap(2), true), true);
        FitsMapTab.changePermSplit(cmp);
        assertTrue(pm.getPermSplit());
        PermMap alone = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        FitsMapTab.changePermSplit(alone);
        assertTrue(alone.getPermSplit());
    }
}
