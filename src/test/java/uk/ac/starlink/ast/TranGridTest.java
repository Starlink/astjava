package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_trangrid.rs}, after C's {@code ast_tester/testtrangrid.c}:
 * each Mapping transforms a grid with {@code tranGrid}, and the results, transformed back, must
 * land on the grid positions. This is done forward over a fixed grid, and inverse over the integer
 * box {@code mapBox} gives for the forward grid, each with and without a linear-fit tolerance and
 * with initial scale sizes of 100 and 4. The results are transformed back with {@code tranP}
 * rather than C's {@code astTranN}, and, as in C, the unit MatrixMap is made in its unit form.
 *
 * <p>{@code tranmap} needs a TranMap and {@code nonlinear_mathmap_1d} a MathMap, which are outside
 * the first phase. {@code fits_wcs_pixel_to_sky} reads a header with a {@code CROTA1} card, which
 * C's FitsChan guesses to be FITS-AIPS (fitschan.c:12880), an encoding whose read is out of scope.
 */
class TranGridTest {

    /**
     * {@code testgrid}: transform the grid {@code [lbnd, ubnd]} in the given direction, then
     * transform the results the other way and check they give the grid back.
     */
    private static void testgrid(
            Mapping map, int[] lbnd, int[] ubnd, double tol, int maxpix, boolean forward, int itest) {
        int nin = lbnd.length;
        int nout = forward ? map.getNout() : map.getNin();
        double[][] out = map.tranGrid(nin, lbnd, ubnd, tol, maxpix, forward, nout);
        int npoint = out[0].length;
        double[][] back = map.tranP(npoint, nout, out, !forward, nin);
        int[] pos = lbnd.clone();
        for (int j = 0; j < npoint; j++) {
            for (int i = 0; i < nin; i++) {
                double val = back[i][j];
                double want = pos[i];
                boolean ok = pos[i] != 0
                        ? Math.abs(val - want) <= 1.0e-6 * Math.abs(0.5 * (val + want))
                        : Math.abs(val) <= 1.0e-5;
                assertTrue(
                        ok,
                        "Test " + itest + " failed at point " + j + " axis " + i + ": " + val + " should be " + pos[i]);
            }
            for (int i = 0; i < nin; i++) {
                pos[i]++;
                if (pos[i] <= ubnd[i]) {
                    break;
                }
                pos[i] = lbnd[i];
            }
        }
    }

    /** {@code testgridpair}: the forward grid, then the inverse over the integer bounds of its outputs. */
    private static void testgridpair(Mapping map, double tol, int maxpix, int itest) {
        int[] lbndAll = {-6, 0, 10};
        int[] ubndAll = {15, 30, 40};
        int nin = map.getNin();
        int[] lbnd = java.util.Arrays.copyOf(lbndAll, nin);
        int[] ubnd = java.util.Arrays.copyOf(ubndAll, nin);
        testgrid(map, lbnd, ubnd, tol, maxpix, true, itest);

        double[] dlbnd = new double[nin];
        double[] dubnd = new double[nin];
        for (int i = 0; i < nin; i++) {
            dlbnd[i] = lbnd[i];
            dubnd[i] = ubnd[i];
        }
        int nout = map.getNout();
        int[] lbndi = new int[nout];
        int[] ubndi = new int[nout];
        for (int i = 0; i < nout; i++) {
            double[] b = map.mapBox(dlbnd, dubnd, true, i + 1, null, null);
            // C assigns the bounds to ints, truncating toward zero.
            lbndi[i] = (int) b[0];
            ubndi[i] = (int) b[1];
        }
        testgrid(map, lbndi, ubndi, tol, maxpix, false, itest + 1);
    }

    /** {@code testmap}: both scale sizes, with and without a tolerance. */
    private static void assertGridsRoundTrip(Mapping map, int itest, double tol) {
        testgridpair(map, tol, 100, itest * 10);
        testgridpair(map, 0.0, 100, itest * 10 + 2);
        testgridpair(map, tol, 4, itest * 10 + 4);
        testgridpair(map, 0.0, 4, itest * 10 + 6);
    }

    @Test
    void unitmap() {
        assertGridsRoundTrip(new UnitMap(2), 1, 0.5);
    }

    @Test
    void zoommap() {
        assertGridsRoundTrip(new ZoomMap(2, 2.0), 2, 0.5);
    }

    @Test
    void unitMatrixmap() {
        assertGridsRoundTrip(new MatrixMap(2, 2), 3, 0.5);
    }

    @Test
    void permmap() {
        assertGridsRoundTrip(new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, new double[] {0.0}), 4, 0.5);
    }

    @Test
    void cmpmap3d() {
        CmpMap c3 = new CmpMap(
                new ShiftMap(new double[] {-1.0}),
                new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {-2.0, 3.0}}),
                false);
        int[] perm = {3, 2, 1};
        Mapping c1 = new PermMap(3, perm, 3, perm, new double[] {0.0});
        ZoomMap c2 = new ZoomMap(3, 0.25);
        c2.invert();
        CmpMap c4 = new CmpMap(c1, c2, true);
        c4.invert();
        assertGridsRoundTrip(new CmpMap(c3, c4, true), 6, 0.5);
    }

    /** The results hold one value per grid position for each output, the first axis varying fastest. */
    @Test
    void gridRoundTripUsesPointset() {
        double[][] out = new ZoomMap(2, 2.0).tranGrid(2, new int[] {1, 1}, new int[] {2, 3}, 0.0, 100, true, 2);
        assertArrayEquals(new double[] {2.0, 4.0, 2.0, 4.0, 2.0, 4.0}, out[0]);
        assertArrayEquals(new double[] {2.0, 2.0, 4.0, 4.0, 6.0, 6.0}, out[1]);
    }
}
