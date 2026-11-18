package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Translation of the unit tests of Rust {@code fitschan/fset_to_store/world_axes.rs} (C {@code WorldAxes}). */
class FitsWorldAxesTest {

    private static int[] worldAxes(Mapping map, double[] dim) {
        int[] perm = new int[map.getNout()];
        assertTrue(FitsWorldAxes.worldAxes(new FitsChan(), map, dim, perm));
        return perm;
    }

    @Test
    void unitMap2dReturnsIdentityPerm() {
        assertArrayEquals(new int[] {0, 1}, worldAxes(new UnitMap(2), new double[] {100, 100}));
    }

    /** A PermMap swapping the axes associates each output with the other input. */
    @Test
    void permMapSwapsAxes() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        assertArrayEquals(new int[] {1, 0}, worldAxes(pm, new double[] {100, 100}));
    }

    @Test
    void shiftMapKeepsIdentity() {
        ShiftMap s = new ShiftMap(new double[] {10.0, 20.0});
        assertArrayEquals(new int[] {0, 1}, worldAxes(s, new double[] {50, 50}));
    }

    @Test
    void matrixSwapViaOffDiagonal() {
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{0.0, 1.0}, {1.0, 0.0}});
        assertArrayEquals(new int[] {1, 0}, worldAxes(mm, new double[] {100, 100}));
    }

    /** Output 0 follows pixel axis 1, output 1 pixel axis 2 and output 2 pixel axis 0. */
    @Test
    void threeAxisPartialSwap() {
        PermMap pm = new PermMap(3, new int[] {3, 1, 2}, 3, new int[] {2, 3, 1}, null);
        assertArrayEquals(new int[] {1, 2, 0}, worldAxes(pm, new double[] {100, 100, 100}));
    }

    @Test
    void seriesChainOfShiftAndSwap() {
        ShiftMap shift = new ShiftMap(new double[] {3.0, 7.0});
        MatrixMap swap = new MatrixMap(2, 2, new double[][] {{0.0, 1.0}, {1.0, 0.0}});
        CmpMap chain = new CmpMap(shift, swap, true);
        assertArrayEquals(new int[] {1, 0}, worldAxes(chain, new double[] {100, 100}));
    }

    /**
     * A Mapping with only a forward transformation is analysed only when TabOK is positive, each
     * output taking the input that alone feeds it.
     */
    @Test
    void aForwardOnlyMappingNeedsTabOk() {
        PolyMap square = new PolyMap(1, 1, 1, new double[] {1.0, 1, 2}, 0, null);
        square.set("IterInverse=0");
        CmpMap map = new CmpMap(new UnitMap(1), square, false);
        int[] perm = new int[2];
        FitsChan fc = new FitsChan();
        assertFalse(FitsWorldAxes.worldAxes(fc, map, new double[] {10, 10}, perm));
        fc.set("TabOK=1");
        assertTrue(FitsWorldAxes.worldAxes(fc, map, new double[] {10, 10}, perm));
        assertArrayEquals(new int[] {0, 1}, perm);
    }

    /**
     * A WCS axis no pixel axis is aligned with takes the lowest unused pixel axis, numbering past
     * the pixel axes when they run out (fitschan.c {@code WorldAxes}).
     */
    @Test
    void aDegenerateWcsAxisIsNumberedPastThePixelAxes() {
        PermMap pm = new PermMap(1, new int[] {2}, 2, new int[] {-1, 1}, new double[] {4.0});
        assertArrayEquals(new int[] {1, 0}, worldAxes(pm, new double[] {100}));
    }
}
