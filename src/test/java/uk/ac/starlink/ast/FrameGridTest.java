package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * {@code frameGrid}, the grid of points a Box spreads through its volume, gives C's points
 * ({@code astFrameGrid}, from {@code probes/frame_line_probe.c}) and C's errors: a Frame's grid
 * with ticks on each axis in proportion to its length among the axes of the same unit, reversed
 * and zero-width bounds; a SkyFrame's rows of points along parallels, across RA 0, around a pole,
 * with reversed latitude bounds, all the way round, of zero width and too small; a CmpFrame's
 * product of its components' grids, permuted or not; and a FrameSet's, which is its current
 * Frame's.
 *
 * <p>The points are sums of bounds and multiples of steps, so they are compared exactly. The
 * number of ticks or of points on a parallel is a truncation; for a SkyFrame it depends on {@code
 * sin}, {@code cos} and {@code sqrt}, which may differ in the last bit between platforms, but in
 * every case here the truncated value is at least 0.01 from an integer, far beyond that.
 */
class FrameGridTest {

    private static void assertCaseMatchesC(String name, Frame frame) {
        int naxes = frame.getNaxes();
        for (FrameProbe.Op op : FrameProbe.ops(name)) {
            if (!"grid".equals(op.name)) {
                fail("Unknown probe operation " + op.line);
            }
            int size = (int) op.arg(0);
            double[] lbnd = op.argRange(1, 1 + naxes);
            double[] ubnd = op.argRange(1 + naxes, 1 + 2 * naxes);
            if (op.failed()) {
                op.assertFails(() -> frame.frameGrid(size, lbnd, ubnd));
                continue;
            }
            double[][] grid = frame.frameGrid(size, lbnd, ubnd);
            int npoint = (int) op.result(0);
            assertEquals(naxes, grid.length, op.line);
            assertEquals(npoint, op.rows.size(), op.line);
            for (int ip = 0; ip < npoint; ip++) {
                double[] got = new double[naxes];
                for (int ic = 0; ic < naxes; ic++) {
                    assertEquals(npoint, grid[ic].length, op.line);
                    got[ic] = grid[ic][ip];
                }
                FrameProbe.assertClose(op.rows.get(ip), got, 0.0, op.line + " point " + ip);
            }
        }
    }

    @Test
    void aFramesGridIsCs() {
        assertCaseMatchesC("grid_frame", new Frame(2));
        Frame units = new Frame(2);
        units.setUnit(1, "m");
        units.setUnit(2, "s");
        assertCaseMatchesC("grid_frame_units", units);
        Frame hz = new Frame(3);
        hz.setUnit(3, "Hz");
        assertCaseMatchesC("grid_frame3_units", hz);
        assertCaseMatchesC("grid_frame3", new Frame(3));
        assertCaseMatchesC("grid_frame1", new Frame(1));
    }

    @Test
    void aSkyFramesGridIsCs() {
        assertCaseMatchesC("grid_sky", new SkyFrame());
        SkyFrame s = new SkyFrame();
        s.permAxes(new int[] {2, 1});
        assertCaseMatchesC("grid_sky_swapped", s);
    }

    @Test
    void aCmpFramesGridIsCs() {
        assertCaseMatchesC("grid_cmpframe_skytime", new CmpFrame(new SkyFrame(), NormBoxTest.timeFrame()));
        assertCaseMatchesC("grid_cmpframe_timesky", new CmpFrame(NormBoxTest.timeFrame(), new SkyFrame()));
        CmpFrame c = new CmpFrame(new SkyFrame(), NormBoxTest.timeFrame());
        c.permAxes(new int[] {3, 2, 1});
        assertCaseMatchesC("grid_cmpframe_permuted", c);
    }

    @Test
    void aFrameSetsGridIsItsCurrentFrames() {
        assertCaseMatchesC("grid_frameset", FrameLineTest.frameset(new SkyFrame()));
    }
}
