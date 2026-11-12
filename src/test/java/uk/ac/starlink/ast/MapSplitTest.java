package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_map_split.rs}. Rust's {@code map_split_zero_based} is the
 * package-private {@code mapSplit(int[])}, C's protected {@code astMapSplit} with zero-based
 * indices, and Rust's public {@code map_split} is jniast's {@code mapSplit(int[], int[])}.
 *
 * <p>{@code frameset_splits_its_base_to_current_mapping} is translated in Task 16. The {@code normmap_*}, {@code tranmap_*} and {@code ratemap_*} tests need classes
 * outside the first phase, as does {@code testcmpmap_split_of_splittest1}, whose Mapping holds
 * LutMaps.
 */
class MapSplitTest {

    /** The Mapping's forward transformation of one point. */
    private static double[] fwdPoint(Mapping m, double... input) {
        return point(transformRows(m, true, input), 0);
    }

    private static MapSplit split(Mapping m, int... in) {
        return m.mapSplit(in);
    }

    // ------------------------------------------------------------------
    // UnitMap
    // ------------------------------------------------------------------

    @Test
    void unitSplitPicksChosenAxes() {
        MapSplit s = split(new UnitMap(3), 0, 2);
        assertNotNull(s);
        assertArrayEquals(new int[] {0, 2}, s.outputs);
        assertEquals(2, s.map.getNin());
        assertEquals(2, s.map.getNout());
        assertArrayEquals(new double[] {7.0, 9.0}, fwdPoint(s.map, 7.0, 9.0));
    }

    @Test
    void unitSplitFullInputs() {
        MapSplit s = split(new UnitMap(3), 0, 1, 2);
        assertArrayEquals(new int[] {0, 1, 2}, s.outputs);
        assertEquals(3, s.map.getNin());
        assertEquals(3, s.map.getNout());
    }

    @Test
    void unitSplitReorders() {
        MapSplit s = split(new UnitMap(3), 2, 0, 1);
        assertArrayEquals(new int[] {2, 0, 1}, s.outputs);
        assertEquals(3, s.map.getNin());
        assertEquals(3, s.map.getNout());
    }

    @Test
    void unitSplitAcceptsARepeatedInput() {
        MapSplit s = split(new UnitMap(3), 1, 1);
        assertArrayEquals(new int[] {1, 1}, s.outputs);
        assertEquals(2, s.map.getNin());
    }

    @Test
    void unitSplitRejectsOutOfRange() {
        assertNull(split(new UnitMap(3), 0, 3));
    }

    // ------------------------------------------------------------------
    // ZoomMap
    // ------------------------------------------------------------------

    @Test
    void zoomSplitCarriesFactor() {
        MapSplit s = split(new ZoomMap(3, 5.0), 0);
        assertArrayEquals(new int[] {0}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertArrayEquals(new double[] {10.0}, fwdPoint(s.map, 2.0));
    }

    @Test
    void zoomSplitSubsetTwoAxes() {
        MapSplit s = split(new ZoomMap(4, 3.0), 1, 3);
        assertArrayEquals(new int[] {1, 3}, s.outputs);
        assertEquals(2, s.map.getNin());
        assertEquals(2, s.map.getNout());
        assertArrayEquals(new double[] {6.0, 15.0}, fwdPoint(s.map, 2.0, 5.0));
    }

    @Test
    void zoomSplitPreservesInvert() {
        ZoomMap z = new ZoomMap(3, 2.0);
        z.invert();
        MapSplit s = split(z, 0, 2);
        assertArrayEquals(new int[] {0, 2}, s.outputs);
        assertArrayEquals(new double[] {5.0, 3.0}, fwdPoint(s.map, 10.0, 6.0));
    }

    @Test
    void zoomSplitOutOfRangeGivesNoneAndAcceptsARepeat() {
        ZoomMap z = new ZoomMap(3, 5.0);
        assertNull(split(z, 3));
        assertArrayEquals(new int[] {1, 1}, split(z, 1, 1).outputs);
    }

    // ------------------------------------------------------------------
    // ShiftMap
    // ------------------------------------------------------------------

    @Test
    void shiftSplitPicksAxisOffset() {
        MapSplit s = split(new ShiftMap(new double[] {10.0, 20.0, 30.0}), 2, 0);
        assertArrayEquals(new int[] {2, 0}, s.outputs);
        assertEquals(2, s.map.getNin());
        assertEquals(2, s.map.getNout());
        double[] out = fwdPoint(s.map, 5.0, 1.0);
        assertEquals(35.0, out[0]);
        assertEquals(11.0, out[1]);
    }

    @Test
    void shiftSplitFullInputsInOrder() {
        MapSplit s = split(new ShiftMap(new double[] {1.0, 2.0, 3.0}), 0, 1, 2);
        assertArrayEquals(new int[] {0, 1, 2}, s.outputs);
        assertArrayEquals(new double[] {11.0, 22.0, 33.0}, fwdPoint(s.map, 10.0, 20.0, 30.0));
    }

    @Test
    void shiftSplitPreservesInvert() {
        ShiftMap sm = new ShiftMap(new double[] {1.0, 2.0, 3.0});
        sm.invert();
        MapSplit s = split(sm, 0, 1);
        assertArrayEquals(new int[] {0, 1}, s.outputs);
        assertArrayEquals(new double[] {9.0, 18.0}, fwdPoint(s.map, 10.0, 20.0));
    }

    @Test
    void shiftSplitOutOfRangeGivesNoneAndAcceptsARepeat() {
        ShiftMap sm = new ShiftMap(new double[] {1.0, 2.0});
        assertNull(split(sm, 2));
        MapSplit s = split(sm, 0, 0);
        assertArrayEquals(new int[] {0, 0}, s.outputs);
        double[][] out = s.map.tran2(1, new double[] {0.0}, new double[] {0.0}, true);
        assertArrayEquals(new double[] {1.0}, out[0]);
        assertArrayEquals(new double[] {1.0}, out[1]);
    }

    // ------------------------------------------------------------------
    // WinMap
    // ------------------------------------------------------------------

    /** A 3-axis WinMap: {@code y_i = shifts[i] + scales[i] * x_i}. */
    private static WinMap winmap3axis() {
        return new WinMap(new double[] {1.0, 2.0, 3.0}, new double[] {10.0, 20.0, 30.0});
    }

    @Test
    void winmapSplitFullInputsIdentityPerm() {
        WinMap w = winmap3axis();
        MapSplit s = split(w, 0, 1, 2);
        assertArrayEquals(new int[] {0, 1, 2}, s.outputs);
        assertEquals(3, s.map.getNin());
        assertEquals(3, s.map.getNout());
        double[] expected = fwdPoint(w, 4.0, 5.0, 6.0);
        assertArrayEquals(expected, fwdPoint(s.map, 4.0, 5.0, 6.0));
        assertArrayEquals(new double[] {41.0, 102.0, 183.0}, expected);
    }

    @Test
    void winmapSplitFullInputsPermuted() {
        MapSplit s = split(winmap3axis(), 2, 0, 1);
        assertArrayEquals(new int[] {2, 0, 1}, s.outputs);
        assertEquals(3, s.map.getNin());
        assertEquals(3, s.map.getNout());
        assertArrayEquals(new double[] {153.0, 61.0, 142.0}, fwdPoint(s.map, 5.0, 6.0, 7.0));
    }

    @Test
    void winmapSplitOutOfRangeGivesNoneAndAcceptsARepeat() {
        WinMap w = winmap3axis();
        assertArrayEquals(new int[] {0, 1, 1}, split(w, 0, 1, 1).outputs);
        assertNull(split(w, 0, 1, 3));
    }

    @Test
    void winmapSplitOfNothingGivesNone() {
        assertNull(split(winmap3axis()));
    }

    @Test
    void winmapSplitPicksPerAxisCoeffs() {
        MapSplit s = split(winmap3axis(), 2, 0);
        assertArrayEquals(new int[] {2, 0}, s.outputs);
        assertEquals(2, s.map.getNin());
        assertEquals(2, s.map.getNout());
        double[] out = fwdPoint(s.map, 5.0, 1.0);
        assertEquals(153.0, out[0], 1e-12);
        assertEquals(11.0, out[1], 1e-12);
    }

    @Test
    void winmapSplitPreservesInvert() {
        WinMap w = winmap3axis();
        w.invert();
        MapSplit s = split(w, 2, 0);
        assertArrayEquals(new int[] {2, 0}, s.outputs);
        double[] out = fwdPoint(s.map, 153.0, 11.0);
        assertEquals(5.0, out[0], 1e-12);
        assertEquals(1.0, out[1], 1e-12);
    }

    // ------------------------------------------------------------------
    // MatrixMap
    // ------------------------------------------------------------------

    private static MatrixMap matrix(double[][] rows) {
        return new MatrixMap(rows[0].length, rows.length, rows);
    }

    @Test
    void matrixSplitDiagonalPicksAxis() {
        MatrixMap mm = matrix(new double[][] {{2.0, 0.0, 0.0}, {0.0, 3.0, 0.0}, {0.0, 0.0, 5.0}});
        MapSplit s = split(mm, 1);
        assertArrayEquals(new int[] {1}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(12.0, fwdPoint(s.map, 4.0)[0], 1e-12);
    }

    @Test
    void matrixSplitBlockDiagonal() {
        MatrixMap mm = matrix(new double[][] {{1.0, 2.0, 0.0}, {3.0, 4.0, 0.0}, {0.0, 0.0, 5.0}});
        MapSplit s = split(mm, 2);
        assertArrayEquals(new int[] {2}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(35.0, fwdPoint(s.map, 7.0)[0], 1e-12);
        MapSplit s2 = split(mm, 0, 1);
        assertArrayEquals(new int[] {0, 1}, s2.outputs);
        assertEquals(2, s2.map.getNin());
        assertEquals(2, s2.map.getNout());
        double[] out2 = fwdPoint(s2.map, 1.0, 1.0);
        assertEquals(3.0, out2[0], 1e-12);
        assertEquals(7.0, out2[1], 1e-12);
    }

    @Test
    void matrixSplitCoupledReturnsNone() {
        MatrixMap mm = matrix(new double[][] {{1.0, 1.0}, {1.0, 1.0}});
        assertNull(split(mm, 0));
        assertNull(split(mm, 1));
        MapSplit s = split(mm, 0, 1);
        assertArrayEquals(new int[] {0, 1}, s.outputs);
        assertEquals(2, s.map.getNin());
    }

    @Test
    void matrixSplitInvertedUsesEffectiveForward() {
        MatrixMap mm = matrix(new double[][] {{2.0, 0.0}, {0.0, 4.0}});
        mm.invert();
        MapSplit s = split(mm, 0);
        assertArrayEquals(new int[] {0}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(4.0, fwdPoint(s.map, 8.0)[0], 1e-12);
    }

    @Test
    void matrixSplitEmptyKeptOutsReturnsNone() {
        MatrixMap mm = matrix(new double[][] {{0.0, 1.0}, {0.0, 2.0}, {0.0, 3.0}});
        assertNull(split(mm, 0));
        MapSplit s = split(mm, 1);
        assertArrayEquals(new int[] {0, 1, 2}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(3, s.map.getNout());
    }

    // ------------------------------------------------------------------
    // PermMap
    // ------------------------------------------------------------------

    /** Rust's {@code PermMap::new(inperm, outperm, constants)}. */
    private static PermMap perm(int[] inperm, int[] outperm, double... constants) {
        return new PermMap(inperm.length, inperm, outperm.length, outperm, constants.length == 0 ? null : constants);
    }

    @Test
    void permSplitSimpleSwap() {
        MapSplit s = split(perm(new int[] {2, 1}, new int[] {2, 1}), 1);
        assertArrayEquals(new int[] {0}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(5.0, fwdPoint(s.map, 5.0)[0], 1e-12);
    }

    @Test
    void permSplitIdentity() {
        MapSplit s = split(perm(new int[] {1, 2, 3}, new int[] {1, 2, 3}), 0, 1, 2);
        assertArrayEquals(new int[] {0, 1, 2}, s.outputs);
        assertEquals(3, s.map.getNin());
        assertEquals(3, s.map.getNout());
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, fwdPoint(s.map, 1.0, 2.0, 3.0));
    }

    /** permmap.c {@code MapSplit} drops an output fed by a constant, rather than refusing the split. */
    @Test
    void permSplitConstantOutpermSilentlyDropped() {
        MapSplit s = split(perm(new int[] {1}, new int[] {1, -1}, 42.0), 0);
        assertArrayEquals(new int[] {0}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(7.0, fwdPoint(s.map, 7.0)[0], 1e-12);
    }

    @Test
    void permSplitConstantInpermPassedThrough() {
        MapSplit s = split(perm(new int[] {-1}, new int[] {1}, 42.0), 0);
        assertArrayEquals(new int[] {0}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(7.0, fwdPoint(s.map, 7.0)[0], 1e-12);
        assertEquals(42.0, point(transformRows(s.map, false, new double[] {7.0}), 0)[0], 1e-12);
    }

    @Test
    void permSplitUnselectedOutputFeedsKeptInputReturnsNone() {
        assertNull(split(perm(new int[] {2, 1}, new int[] {1, 2}), 0));
    }

    @Test
    void permSplitInvertedUsesEffectiveForward() {
        PermMap pm = perm(new int[] {-1, 1}, new int[] {2}, 99.0);
        pm.invert();
        MapSplit s = split(pm, 0);
        assertArrayEquals(new int[] {1}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        double[] out = fwdPoint(s.map, 42.0);
        assertEquals(42.0, out[0], 1e-12);
        assertEquals(fwdPoint(pm, 42.0)[1], out[0], 1e-12);
    }

    @Test
    void permSplitOutOfRangeGivesNoneAndKeepsARepeatOnce() {
        PermMap pm = perm(new int[] {1, 2, 3}, new int[] {1, 2, 3});
        MapSplit s = split(pm, 0, 0);
        assertArrayEquals(new int[] {0}, s.outputs);
        assertEquals(2, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertNull(split(pm, 0, 3));
    }

    @Test
    void permSplitUnmatchedInputYieldsNone() {
        assertNull(split(perm(new int[] {1}, new int[] {-1}, 5.0), 0));
    }

    // ------------------------------------------------------------------
    // CmpMap
    // ------------------------------------------------------------------

    @Test
    void cmpmapParallelSplitPerChild() {
        CmpMap cm = new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {10.0, 20.0}), false);
        assertArrayEquals(new int[] {2}, split(cm, 2).outputs);
        assertArrayEquals(new int[] {0, 3}, split(cm, 0, 3).outputs);
    }

    @Test
    void cmpmapSeriesSplitThreadsThrough() {
        CmpMap cm = new CmpMap(new ShiftMap(new double[] {1.0, 2.0, 3.0}), new ZoomMap(3, 5.0), true);
        MapSplit s = split(cm, 1);
        assertArrayEquals(new int[] {1}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(25.0, fwdPoint(s.map, 3.0)[0], 1e-12);
    }

    @Test
    void cmpmapParallelOneChildRejectsReturnsNone() {
        CmpMap cm =
                new CmpMap(matrix(new double[][] {{1.0, 1.0}, {1.0, 1.0}}), new ShiftMap(new double[] {10.0}), false);
        assertNull(split(cm, 0));
        assertNull(split(cm, 1));
        MapSplit s = split(cm, 2);
        assertArrayEquals(new int[] {2}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
    }

    @Test
    void cmpmapNestedSeriesOfParallel() {
        CmpMap parallel =
                new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {10.0, 20.0}), false);
        CmpMap cm = new CmpMap(parallel, new ZoomMap(4, 3.0), true);
        MapSplit s = split(cm, 2);
        assertArrayEquals(new int[] {2}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(42.0, fwdPoint(s.map, 4.0)[0], 1e-12);
    }

    @Test
    void cmpmapInvertedSeriesSplit() {
        CmpMap cm = new CmpMap(new ShiftMap(new double[] {1.0, 2.0, 3.0}), new ZoomMap(3, 5.0), true);
        cm.invert();
        MapSplit s = split(cm, 1);
        assertArrayEquals(new int[] {1}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(5.0, fwdPoint(s.map, 35.0)[0], 1e-12);
    }

    @Test
    void cmpmapInvertedParallelSplit() {
        CmpMap cm = new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {10.0, 20.0}), false);
        cm.invert();
        MapSplit s = split(cm, 3);
        assertArrayEquals(new int[] {3}, s.outputs);
        assertEquals(1, s.map.getNin());
        assertEquals(1, s.map.getNout());
        assertEquals(80.0, fwdPoint(s.map, 100.0)[0], 1e-12);
    }

    @Test
    void cmpmapSeriesSecondChildCouplesReturnsNone() {
        CmpMap cm = new CmpMap(
                new ShiftMap(new double[] {1.0, 2.0}), matrix(new double[][] {{1.0, 1.0}, {1.0, 1.0}}), true);
        assertNull(split(cm, 0));
        assertNull(split(cm, 1));
        MapSplit s = split(cm, 0, 1);
        assertArrayEquals(new int[] {0, 1}, s.outputs);
        assertEquals(2, s.map.getNin());
        assertEquals(2, s.map.getNout());
    }

    @Test
    void cmpmapParallelInputReorderViaPermmap() {
        CmpMap cm = new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {10.0, 20.0}), false);
        MapSplit s = split(cm, 3, 1, 2, 0);
        assertArrayEquals(new int[] {0, 1, 2, 3}, s.outputs);
        assertEquals(4, s.map.getNin());
        assertEquals(4, s.map.getNout());
        double[] row = fwdPoint(s.map, 100.0, 50.0, 7.0, 3.0);
        assertEquals(4.0, row[0], 1e-12);
        assertEquals(52.0, row[1], 1e-12);
        assertEquals(17.0, row[2], 1e-12);
        assertEquals(120.0, row[3], 1e-12);
    }

    @Test
    void cmpmapSplitOutOfRangeGivesNoneAndAcceptsARepeat() {
        CmpMap cm = new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {10.0, 20.0}), false);
        assertNull(split(cm, 4));
        assertArrayEquals(new int[] {0, 0}, split(cm, 0, 0).outputs);
    }

    @Test
    void cmpmapMapsplit2NoFalsePositiveWhenGenuinelyCoupled() {
        CmpMap cm = new CmpMap(
                new ShiftMap(new double[] {1.0, 2.0}), matrix(new double[][] {{1.0, 1.0}, {1.0, -1.0}}), true);
        assertNull(split(cm, 0));
        assertNull(split(cm, 1));
        assertNotNull(split(cm, 0, 1));
    }

    @Test
    void cmpmapMapsplit2TerminatesOnSmallNout() {
        CmpMap cm = new CmpMap(matrix(new double[][] {{1.0, 1.0}}), matrix(new double[][] {{2.0}}), true);
        assertNull(split(cm, 0));
        assertNull(split(cm, 1));
    }

    /**
     * The split Mapping is a copy, independent of the original (mapping.c {@code astMapSplit_}),
     * even where the class's split gives the Mapping itself, as the base class's does for a
     * selection of every input in order.
     */
    @Test
    void theSplitMappingIsACopy() {
        MatrixMap mm = matrix(new double[][] {{1.0, 2.0}, {3.0, 4.0}});
        MapSplit whole = split(mm, 0, 1);
        assertInstanceOf(MatrixMap.class, whole.map);
        org.junit.jupiter.api.Assertions.assertNotSame(mm, whole.map);
        CmpMap cm = new CmpMap(mm, new UnitMap(1), false);
        MapSplit s = split(cm, 0, 1, 2);
        assertNotNull(s);
        Mapping[] parts = s.map.decompose(null, null);
        assertInstanceOf(MatrixMap.class, parts[0]);
        org.junit.jupiter.api.Assertions.assertNotSame(mm, parts[0]);
    }

    // ------------------------------------------------------------------
    // The public 1-based mapSplit
    // ------------------------------------------------------------------

    @Test
    void publicMapSplitIsOneBasedInAndOut() {
        ZoomMap z = new ZoomMap(4, 3.0);
        int[] outs = new int[4];
        Mapping sub = z.mapSplit(new int[] {2, 4}, outs);
        assertNotNull(sub, "1-based split");
        assertArrayEquals(new int[] {2, 4, 0, 0}, outs);
        assertEquals(2, sub.getNin());
        assertEquals(2, sub.getNout());
        assertArrayEquals(new double[] {6.0, 15.0}, fwdPoint(sub, 2.0, 5.0));
        assertArrayEquals(new int[] {1, 3}, split(z, 1, 3).outputs);
    }

    @Test
    void publicMapSplitRejectsZeroAxis() {
        UnitMap u = new UnitMap(3);
        assertNull(u.mapSplit(new int[] {0}, new int[3]));
        assertNull(u.mapSplit(new int[] {1, 0}, new int[3]));
    }

    @Test
    void publicMapSplitRejectsOutOfRange() {
        UnitMap u = new UnitMap(3);
        assertNull(u.mapSplit(new int[] {4}, new int[3]));
        int[] outs = new int[3];
        Mapping sub = u.mapSplit(new int[] {1, 2, 3}, outs);
        assertArrayEquals(new int[] {1, 2, 3}, outs);
        assertEquals(3, sub.getNin());
    }

    @Test
    void publicMapSplitPermmapConstantOutputDropped() {
        PermMap pm = perm(new int[] {1}, new int[] {1, -1}, 42.0);
        int[] outs = new int[2];
        Mapping sub = pm.mapSplit(new int[] {1}, outs);
        assertEquals(1, outs[0]);
        assertEquals(1, sub.getNin());
        assertEquals(1, sub.getNout());
    }

    /**
     * testcmpmap.c Errors 1-6: inputs 3, 6 and 4 of a parallel CmpMap of a UnitMap, a 2-axis
     * ZoomMap and a 3-axis diagonal MatrixMap split off a 3-in 3-out Mapping feeding outputs 3, 4
     * and 6, in ascending order.
     */
    @Test
    void testcmpmapSplitOfNestedParallelCmpmap() {
        CmpMap inner = new CmpMap(new UnitMap(1), new ZoomMap(2, 2.0), false);
        CmpMap m4 = new CmpMap(inner, new MatrixMap(3, 3, new double[] {-1.0, 1.0, 2.0}), false);
        int[] outs = new int[6];
        Mapping m5 = m4.mapSplit(new int[] {3, 6, 4}, outs);
        assertNotNull(m5, "Error 1");
        assertEquals(3, m5.getNin(), "Error 2");
        assertEquals(3, m5.getNout(), "Error 3");
        assertArrayEquals(new int[] {3, 4, 6}, java.util.Arrays.copyOf(outs, 3), "Errors 4-6");
    }

    @Test
    void wcsSplitFullInputsSucceeds() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        MapSplit split = wm.mapSplit(new int[] {0, 1});
        assertArrayEquals(new int[] {0, 1}, split.outputs);
        assertEquals(2, split.map.getNin());
        assertEquals(2, split.map.getNout());
        double[] parent = fwdPoint(wm, 0.1, 1.4);
        double[] child = fwdPoint(split.map, 0.1, 1.4);
        assertTrue(Math.abs(parent[0] - child[0]) < 1e-12);
        assertTrue(Math.abs(parent[1] - child[1]) < 1e-12);
    }

    /** A 2-axis WcsMap split to one celestial axis: C's nin > 1 test fails and there is no split. */
    @Test
    void wcsSplitDropsCelestialAxisReturnsNone2axis() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        assertNull(wm.mapSplit(new int[] {0}));
        assertNull(wm.mapSplit(new int[] {1}));
    }

    @Test
    void wcsSplit3axisKeepsBothCelestialYieldsWcsmap() {
        WcsMap wm = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        MapSplit split = wm.mapSplit(new int[] {0, 1});
        assertArrayEquals(new int[] {0, 1}, split.outputs);
        assertEquals(2, split.map.getNin());
        assertEquals(2, split.map.getNout());
        double[] fwd = fwdPoint(split.map, 0.1, 1.4);
        double[] back = point(transformRows(split.map, false, fwd), 0);
        assertTrue(Math.abs(back[0] - 0.1) < 1e-10);
        assertTrue(Math.abs(back[1] - 1.4) < 1e-10);
    }

    @Test
    void wcsSplit3axisPassthroughOnlyYieldsUnitmap() {
        WcsMap wm = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        MapSplit split = wm.mapSplit(new int[] {2});
        assertArrayEquals(new int[] {2}, split.outputs);
        assertEquals(1, split.map.getNin());
        assertEquals(1, split.map.getNout());
        assertArrayEquals(new double[] {42.5}, fwdPoint(split.map, 42.5));
    }

    /** Only the longitude kept: C builds a PermMap dropping it (wcsmap.c MapSplit), whose inverse gives it bad. */
    @Test
    void wcsSplit3axisOnlyLonKeptDropsViaPermmap() {
        WcsMap wm = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        MapSplit split = wm.mapSplit(new int[] {2, 0});
        assertArrayEquals(new int[] {2}, split.outputs);
        assertEquals(2, split.map.getNin());
        assertEquals(1, split.map.getNout());
        assertEquals(123.0, fwdPoint(split.map, 123.0, 0.5)[0], 1e-12);
        double[] inv = point(transformRows(split.map, false, new double[] {7.0}), 0);
        assertEquals(7.0, inv[0], 1e-12);
        assertEquals(AstObject.AST__BAD, inv[1]);
    }

    @Test
    void wcsSplitPreservesInvertFullSet() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        assertTrue(wm.mapSplit(new int[] {0, 1}).map.getInvert());
    }

    @Test
    void wcsSplitPreservesInvertSubset() {
        WcsMap wm = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        MapSplit split = wm.mapSplit(new int[] {0, 1});
        assertArrayEquals(new int[] {0, 1}, split.outputs);
        assertTrue(split.map.getInvert());
    }

    /** wcsmap.c MapSplit calls the parent's first, which reports an index out of range as AST__AXIIN. */
    @Test
    void wcsSplitOutOfRangeIsAnError() {
        WcsMap wm = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        assertEquals(
                AstException.AST__AXIIN,
                assertThrows(AstException.class, () -> wm.mapSplit(new int[] {0, 1, 3}))
                        .getStatus());
    }

    @Test
    void wcsSplitFullSetPermutedSimplifiesThrough() {
        WcsMap wm = new WcsMap(3, WcsMap.AST__TAN, 1, 2);
        MapSplit split = wm.mapSplit(new int[] {2, 0, 1});
        assertArrayEquals(new int[] {0, 1, 2}, split.outputs);
        assertEquals(3, split.map.getNin());
        assertEquals(3, split.map.getNout());
        double[] sub = fwdPoint(split.map, 10.0, 0.1, 1.4);
        double[] parent = fwdPoint(wm, 0.1, 1.4, 10.0);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(sub[i] - parent[i]) < 1e-12, "axis " + i);
        }
    }

    // ------------------------------------------------------------------
    // PolyMap
    // ------------------------------------------------------------------

    /**
     * PolyMap has no MapSplit of its own: the base class's handles all its inputs and no other
     * selection.
     */
    @Test
    void polymapSplitFullInputsOnly() {
        PolyMap pm = new PolyMap(2, 2, 2, new double[] {1.0, 1.0, 1.0, 0.0, 1.0, 2.0, 0.0, 1.0}, 0, null);
        assertNotNull(split(pm, 0, 1));
        assertNull(split(pm, 0));
        assertNull(split(pm, 1));
    }
}
