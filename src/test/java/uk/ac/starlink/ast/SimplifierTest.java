package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the unit tests of Rust {@code mappings/simplify.rs} for the simplification engine
 * ({@link Simplifier}, {@link MergeList}), with tests of what simplifying leaves behind.
 */
class SimplifierTest {

    /** The list a Mapping decomposes into in series, applied with the Invert value {@code invert}. */
    private static MergeList list(Mapping m, boolean series, boolean invert) {
        List<Mapping> maps = new ArrayList<>();
        List<Boolean> inverts = new ArrayList<>();
        m.mapList(series, invert, maps, inverts);
        return new MergeList(maps, inverts);
    }

    private static double tran1(Mapping m, double x, boolean forward) {
        return m.tran1(1, new double[] {x}, forward)[0];
    }

    // ------------------------------------------------------------------
    // patternCheck
    // ------------------------------------------------------------------

    @Test
    void patternCheckShortHistoryNeverFires() {
        List<Long> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            assertEquals(0, Simplifier.patternCheck(i % 2, 1, history));
        }
    }

    @Test
    void patternCheckCheckDisabledNeverFires() {
        List<Long> history = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            assertEquals(0, Simplifier.patternCheck(7, 0, history));
        }
    }

    @Test
    void patternCheckDetectsWavelengthTwo() {
        List<Long> history = new ArrayList<>();
        long detected = 0;
        for (int i = 0; i < 40; i++) {
            long w = Simplifier.patternCheck(i % 2, 1, history);
            if (w != 0) {
                detected = w;
                break;
            }
        }
        assertEquals(2, detected);
    }

    @Test
    void patternCheckWavelengthOneNeedsThirtyRepeats() {
        List<Long> history = new ArrayList<>();
        int firstHit = -1;
        for (int i = 0; i < 60; i++) {
            if (Simplifier.patternCheck(5, 1, history) != 0) {
                firstHit = i;
                break;
            }
        }
        assertTrue(firstHit >= 29, "fired too early: " + firstHit);
    }

    // ------------------------------------------------------------------
    // MergeList
    // ------------------------------------------------------------------

    @Test
    void mergeCtxFreezesTheMappingItself() {
        ZoomMap a = new ZoomMap(1, 2.0);
        ZoomMap b = new ZoomMap(1, 3.0);
        MergeList list = new MergeList(Arrays.asList(a, b), Arrays.asList(false, false));
        assertEquals(2, list.size());
        assertTrue(list.mayNominate(0));
        assertTrue(list.mayNominate(1));
        list.map(1).setFrozen();
        assertTrue(list.mayNominate(0));
        assertFalse(list.mayNominate(1));
        assertTrue(b.getFrozen());
    }

    // ------------------------------------------------------------------
    // flatten (C's MapList)
    // ------------------------------------------------------------------

    @Test
    void flattenNonCmpmapReturnsSingleElement() {
        ZoomMap z = new ZoomMap(2, 3.0);
        MergeList l = Simplifier.flatten(z, true, null);
        assertEquals(1, l.size());
        assertFalse(l.invert(0));
        assertSame(z, l.map(0));
        assertEquals(2, l.map(0).getNin());
    }

    @Test
    void flattenNonCmpmapWithInvFlag() {
        ZoomMap z = new ZoomMap(2, 3.0);
        MergeList l = list(z, true, true);
        assertEquals(1, l.size());
        assertTrue(l.invert(0));
        assertFalse(l.map(0).getInvert());
    }

    @Test
    void flattenNonCmpmapWithSelfInverted() {
        ZoomMap z = new ZoomMap(1, 5.0);
        z.invert();
        MergeList l = Simplifier.flatten(z, true, null);
        assertEquals(1, l.size());
        assertTrue(l.invert(0));
        assertTrue(l.map(0).getInvert());
    }

    @Test
    void flattenNonCmpmapDoubleInversionCancels() {
        ZoomMap z = new ZoomMap(1, 5.0);
        z.invert();
        // The list's value is the Invert value to apply the Mapping with: the inverse of an
        // inverted ZoomMap is the ZoomMap applied with Invert unset.
        MergeList l = list(z, true, false);
        assertEquals(1, l.size());
        assertFalse(l.invert(0));
        assertTrue(l.map(0).getInvert());
    }

    @Test
    void flattenSeriesCmpmapTwoElements() {
        CmpMap cmp = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), true);
        MergeList l = Simplifier.flatten(cmp, true, null);
        assertEquals(2, l.size());
        assertFalse(l.invert(0));
        assertFalse(l.invert(1));
    }

    @Test
    void flattenNestedSeriesThreeElements() {
        CmpMap inner = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        CmpMap outer = new CmpMap(inner, new ZoomMap(1, 5.0), true);
        MergeList l = Simplifier.flatten(outer, true, null);
        assertEquals(3, l.size());
        for (int i = 0; i < 3; i++) {
            assertFalse(l.invert(i));
        }
    }

    @Test
    void flattenParallelCmpmapNotAbsorbedBySeries() {
        CmpMap par = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), false);
        assertEquals(1, Simplifier.flatten(par, true, null).size());
    }

    @Test
    void flattenInvertedSeriesReversesOrder() {
        CmpMap cmp = new CmpMap(new ZoomMap(2, 2.0), new ShiftMap(new double[] {10.0, 20.0}), true);
        cmp.invert();
        boolean[] simpler = new boolean[1];
        MergeList l = Simplifier.flatten(cmp, true, simpler);
        assertEquals(2, l.size());
        assertTrue(l.invert(0));
        assertTrue(l.invert(1));
        assertInstanceOf(ShiftMap.class, l.map(0));
        assertInstanceOf(ZoomMap.class, l.map(1));
        assertTrue(simpler[0], "an inverted CmpMap decomposed counts as a simplification");
    }

    @Test
    void flattenParallelCmpmapForParallel() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), false);
        assertEquals(2, Simplifier.flatten(cmp, false, null).size());
    }

    // ------------------------------------------------------------------
    // The nominate loop
    // ------------------------------------------------------------------

    @Test
    void mergeLoopNoMergesWithIncompatibleTypes() {
        CmpMap cmp = new CmpMap(new SphMap(), new ShiftMap(new double[] {10.0, 20.0}), true);
        Mapping out = cmp.simplify();
        assertSame(cmp, out);
    }

    @Test
    void mergeLoopAllUnitmapsPreservesOne() {
        CmpMap cmp = new CmpMap(new UnitMap(2), new UnitMap(2), true);
        Mapping out = cmp.simplify();
        assertInstanceOf(UnitMap.class, out);
        assertEquals(2, out.getNin());
    }

    @Test
    void mergeLoopStripsUnitmapsFromSeries() {
        CmpMap cmp = new CmpMap(
                new CmpMap(new ZoomMap(1, 3.0), new UnitMap(1), true), new ShiftMap(new double[] {5.0}), true);
        Mapping out = cmp.simplify();
        // The ZoomMap absorbs the UnitMap, and the result is absorbed into the ShiftMap.
        assertFalse(out instanceof CmpMap);
        assertFalse(out instanceof UnitMap);
        assertEquals(20.0, tran1(out, 5.0, true), 1e-12);
    }

    @Test
    void mergeLoopMergesLinearParallel() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 3.0), new UnitMap(1), false);
        Mapping out = cmp.simplify();
        assertFalse(out instanceof CmpMap);
        assertEquals(2, out.getNin());
    }

    // ------------------------------------------------------------------
    // reconstruct
    // ------------------------------------------------------------------

    @Test
    void reconstructSingleElement() {
        MergeList l = new MergeList(Arrays.asList(new ZoomMap(1, 4.0)), Arrays.asList(false));
        Mapping result = Simplifier.reconstruct(l, true);
        assertEquals(1, result.getNin());
        assertFalse(result.getInvert());
    }

    @Test
    void reconstructSingleElementInverted() {
        MergeList l = new MergeList(Arrays.asList(new ZoomMap(1, 4.0)), Arrays.asList(true));
        assertTrue(Simplifier.reconstruct(l, true).getInvert());
    }

    @Test
    void reconstructTwoElementsSeries() {
        MergeList l = new MergeList(
                Arrays.asList(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0})), Arrays.asList(false, false));
        assertEquals(20.0, tran1(Simplifier.reconstruct(l, true), 5.0, true), 1e-12);
    }

    @Test
    void reconstructThreeElementsSeries() {
        MergeList l = new MergeList(
                Arrays.asList(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), new ZoomMap(1, 3.0)),
                Arrays.asList(false, false, false));
        assertEquals(60.0, tran1(Simplifier.reconstruct(l, true), 5.0, true), 1e-12);
    }

    @Test
    void reconstructTwoElementsParallel() {
        MergeList l = new MergeList(
                Arrays.asList(new ZoomMap(1, 3.0), new ShiftMap(new double[] {100.0})), Arrays.asList(false, false));
        Mapping result = Simplifier.reconstruct(l, false);
        assertEquals(2, result.getNin());
        double[][] out = result.tranP(1, 2, new double[][] {{2.0}, {5.0}}, true, 2);
        assertEquals(6.0, out[0][0], 1e-12);
        assertEquals(105.0, out[1][0], 1e-12);
    }

    @Test
    void reconstructLeavesTheComponentsInvertAsItWas() {
        ZoomMap z = new ZoomMap(1, 2.0);
        ShiftMap s = new ShiftMap(new double[] {10.0});
        MergeList l = new MergeList(Arrays.asList(z, s), Arrays.asList(true, false));
        Mapping result = Simplifier.reconstruct(l, true);
        assertFalse(z.getInvert(), "the component keeps its own Invert attribute");
        // (5 / 2) + 10
        assertEquals(12.5, tran1(result, 5.0, true), 1e-12);
    }

    // ------------------------------------------------------------------
    // simplify
    // ------------------------------------------------------------------

    @Test
    void simplifyMappingCmpmapRoundtrip() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        assertEquals(20.0, tran1(cmp.simplify(), 5.0, true), 1e-12);
    }

    @Test
    void simplifyMappingInvertedCmpmap() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        cmp.invert();
        assertEquals(5.0, tran1(cmp.simplify(), 20.0, true), 1e-12);
    }

    @Test
    void baseSimplifyReturnsAClonewhenNothingReduces() {
        ZoomMap z = new ZoomMap(1, 3.0);
        Mapping out = Simplifier.baseSimplify(z);
        assertEquals(3.0, assertInstanceOf(ZoomMap.class, out).getZoom());
    }

    @Test
    void anInvertedUnitMapAloneSimplifiesToAForwardCopy() {
        UnitMap u = new UnitMap(2);
        u.setInvert(true);
        Mapping out = u.simplify();
        assertInstanceOf(UnitMap.class, out);
        assertFalse(out.getInvert());
        assertTrue(u.getInvert(), "the original keeps its Invert attribute");
    }

    /**
     * A Mapping with Ident set is not offered the list (mapping.c {@code astMapMerge_} returns at
     * once): a ZoomMap of factor one, which alone becomes a UnitMap, is left as it is.
     */
    @Test
    void anIdentMappingIsNotMerged() {
        ZoomMap named = new ZoomMap(1, 1.0);
        named.setIdent("keep");
        MergeList list = new MergeList(Arrays.asList(named), Arrays.asList(false));
        assertEquals(-1, Simplifier.dispatchMapMerge(named, list, 0, true));
        assertSame(named, list.map(0));
        ZoomMap plain = new ZoomMap(1, 1.0);
        MergeList other = new MergeList(Arrays.asList(plain), Arrays.asList(false));
        assertEquals(0, Simplifier.dispatchMapMerge(plain, other, 0, true));
        assertInstanceOf(UnitMap.class, other.map(0));
    }

    /**
     * Only the nominated Mapping is guarded: zoommap.c {@code MapMerge} takes a neighbouring UnitMap
     * into its run by its class alone, so a UnitMap with Ident set beside a ZoomMap is merged away.
     */
    @Test
    void aNeighbourWithIdentIsMergedAsCDoes() {
        UnitMap named = new UnitMap(1);
        named.setIdent("keep");
        Mapping out = new CmpMap(new ZoomMap(1, 2.0), named, true).simplify();
        assertEquals(2.0, assertInstanceOf(ZoomMap.class, out).getZoom());
        assertFalse(Channel.text(out, false, 0).contains("keep"));
    }

    @Test
    void simplifyLeavesTheInputAsCDoes() {
        AstObject read = ChannelText.read(ChannelText.dump(
                new CmpMap(new CmpMap(new ZoomMap(1, 2.0), new UnitMap(1), true), new UnitMap(1), true)));
        CmpMap input = assertInstanceOf(CmpMap.class, read);
        String before = CValues.stripIsSimp(ChannelText.dump(input));
        double[] xs = {-3.0, 0.0, 1.5, 7.0, 1e6};
        double[] tranBefore = input.tran1(xs.length, xs, true);
        Mapping out = input.simplify();
        assertEquals(before, CValues.stripIsSimp(ChannelText.dump(input)));
        assertArrayEquals(tranBefore, input.tran1(xs.length, xs, true), 0.0);
        assertArrayEquals(tranBefore, out.tran1(xs.length, xs, true), 0.0);
    }

    @Test
    void aDeepAlternatingSeriesBecomesAUnitMap() {
        Mapping m = new UnitMap(1);
        for (int i = 0; i < 2000; i++) {
            ZoomMap z = new ZoomMap(1, 3.0);
            ZoomMap zi = new ZoomMap(1, 3.0);
            zi.invert();
            m = new CmpMap(m, new CmpMap(z, zi, true), true);
        }
        assertInstanceOf(UnitMap.class, m.simplify());
    }

    // ExecutorService is not AutoCloseable before Java 19; the pool is shut down in the finally block.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void threadsSimplifyTheirOwnCopiesIdentically() throws Exception {
        FrameSet fs = assertInstanceOf(FrameSet.class, ChannelText.read(Fixtures.read("wcsconv/inputs/lsst.ast")));
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        String single = ChannelText.dump(((Mapping) map.copy()).simplify());
        // MODERN-JAVA(19): try-with-resources on the ExecutorService
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Mapping own = (Mapping) map.copy();
                results.add(pool.submit(() -> ChannelText.dump(own.simplify())));
            }
            for (Future<String> f : results) {
                assertEquals(single, f.get());
            }
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // The merges of ZoomMap, ShiftMap and WinMap
    // ------------------------------------------------------------------

    /** The points of {@code rows}, one per row, transformed by {@code m}, one per row. */
    private static double[][] transformPoints(Mapping m, boolean forward, double[]... rows) {
        int ncoord = rows[0].length;
        double[][] in = new double[ncoord][rows.length];
        for (int p = 0; p < rows.length; p++) {
            for (int c = 0; c < ncoord; c++) {
                in[c][p] = rows[p][c];
            }
        }
        int nout = forward ? m.getNout() : m.getNin();
        double[][] out = m.tranP(rows.length, ncoord, in, forward, nout);
        double[][] points = new double[rows.length][nout];
        for (int p = 0; p < rows.length; p++) {
            for (int c = 0; c < nout; c++) {
                points[p][c] = out[c][p];
            }
        }
        return points;
    }

    /** Checks that {@code a} and {@code b} transform {@code rows} alike, to within {@code tol}. */
    private static void assertSameTransform(Mapping a, Mapping b, boolean forward, double tol, double[]... rows) {
        double[][] pa = transformPoints(a, forward, rows);
        double[][] pb = transformPoints(b, forward, rows);
        for (int p = 0; p < rows.length; p++) {
            assertArrayEquals(pa[p], pb[p], tol, "point " + p);
        }
    }

    @Test
    void simplifyMappingNonCmpmapDelegates() {
        assertInstanceOf(UnitMap.class, new ZoomMap(1, 1.0).simplify());
    }

    @Test
    void baseSimplifyReachesAClassesOwnMapMerge() {
        assertInstanceOf(UnitMap.class, Simplifier.baseSimplify(new ZoomMap(2, 1.0)));
    }

    /**
     * A unit-scale WinMap becomes a ShiftMap wherever it sits, because the narrowing is the last
     * step of winmap.c {@code MapMerge}, which the nominate loop reaches for a parallel component
     * too. The PermMap beside it cannot merge with it, so the WinMap survives into the rebuilt
     * CmpMap.
     */
    @Test
    void unitScaleWinmapInAParallelBranchNarrowsToAShiftmap() {
        WinMap win = new WinMap(new double[] {3.0}, new double[] {1.0});
        PermMap perm = new PermMap(2, new int[] {1, 2}, 2, new int[] {2, 1}, null);
        CmpMap par = new CmpMap(win, perm, false);
        CmpMap cm = assertInstanceOf(CmpMap.class, par.simplify());
        ShiftMap shift = assertInstanceOf(ShiftMap.class, cm.getMap1());
        assertArrayEquals(new double[] {3.0}, shift.getShifts(), 0.0);
    }

    @Test
    void simplifyUnitmapSeriesZoomRemovesUnit() {
        Mapping result = new CmpMap(new UnitMap(2), new ZoomMap(2, 3.0), true).simplify();
        assertInstanceOf(ZoomMap.class, result);
        assertEquals(2, result.getNin());
        assertEquals(2, result.getNout());
    }

    @Test
    void simplifyZoomSeriesMergesFactors() {
        Mapping result = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), true).simplify();
        assertInstanceOf(ZoomMap.class, result);
        assertEquals(2, result.getNin());
        assertArrayEquals(new double[] {6.0, 12.0}, transformPoints(result, true, new double[] {1.0, 2.0})[0], 1e-12);
    }

    @Test
    void simplifyZoomSeriesInverseBecomesUnit() {
        Mapping result = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 0.5), true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyShiftSeriesMergesOffsets() {
        Mapping result = new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {3.0, 4.0}), true)
                .simplify();
        assertInstanceOf(ShiftMap.class, result);
        assertArrayEquals(new double[] {4.0, 6.0}, transformPoints(result, true, new double[] {0.0, 0.0})[0], 1e-12);
    }

    @Test
    void simplifyShiftSeriesCancelsToUnit() {
        Mapping result = new CmpMap(
                        new ShiftMap(new double[] {1.0, 2.0}), new ShiftMap(new double[] {-1.0, -2.0}), true)
                .simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyPreservesTransformZoomSeries() {
        CmpMap cmp = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), true);
        assertSameTransform(cmp, cmp.simplify(), true, 1e-12, new double[] {1.0, -2.0}, new double[] {0.5, 100.0});
    }

    @Test
    void simplifyPreservesTransformShiftSeries() {
        CmpMap cmp = new CmpMap(new ShiftMap(new double[] {10.0, 20.0}), new ShiftMap(new double[] {30.0, 40.0}), true);
        assertSameTransform(cmp, cmp.simplify(), true, 1e-12, new double[] {1.0, 2.0}, new double[] {5.0, -3.0});
    }

    @Test
    void simplifyPreservesTransformInverse() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 4.0), new ZoomMap(1, 5.0), true);
        assertSameTransform(cmp, cmp.simplify(), false, 1e-12, new double[] {100.0});
    }

    @Test
    void simplifyUnitmapParallelMerges() {
        Mapping result = new CmpMap(new UnitMap(2), new UnitMap(3), false).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(5, result.getNin());
    }

    @Test
    void simplifyWinmapSeriesComposes() {
        // 10 + 3 * (1 + 2 * x) = 13 + 6 * x
        Mapping result = new CmpMap(
                        new WinMap(new double[] {1.0}, new double[] {2.0}),
                        new WinMap(new double[] {10.0}, new double[] {3.0}),
                        true)
                .simplify();
        assertInstanceOf(WinMap.class, result);
        assertEquals(43.0, tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyWinmapInverseCancelsToUnit() {
        WinMap b = new WinMap(new double[] {3.0}, new double[] {2.0});
        b.invert();
        Mapping result = new CmpMap(new WinMap(new double[] {3.0}, new double[] {2.0}), b, true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(1, result.getNin());
    }

    @Test
    void simplifyWinmapPreservesTransform() {
        CmpMap cmp = new CmpMap(
                new WinMap(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}),
                new WinMap(new double[] {5.0, 6.0}, new double[] {7.0, 8.0}),
                true);
        assertSameTransform(cmp, cmp.simplify(), true, 1e-10, new double[] {1.0, -2.0}, new double[] {0.5, 100.0});
    }

    @Test
    void simplifyWinmapAbsorbsZoommap() {
        // 3 * (2 * x + 1) = 6 * x + 3
        Mapping result =
                new CmpMap(new WinMap(new double[] {1.0}, new double[] {2.0}), new ZoomMap(1, 3.0), true).simplify();
        assertInstanceOf(WinMap.class, result);
        assertEquals(33.0, tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyZoommapThenWinmap() {
        // 2 * (3 * x) + 1 = 6 * x + 1
        CmpMap cmp = new CmpMap(new ZoomMap(1, 3.0), new WinMap(new double[] {1.0}, new double[] {2.0}), true);
        Mapping result = cmp.simplify();
        assertEquals(31.0, tran1(result, 5.0, true), 1e-12);
        assertEquals(tran1(cmp, 5.0, true), tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyWinmapAbsorbsShiftmap() {
        // (2 * x + 1) + 10 = 2 * x + 11
        Mapping result = new CmpMap(
                        new WinMap(new double[] {1.0}, new double[] {2.0}), new ShiftMap(new double[] {10.0}), true)
                .simplify();
        assertInstanceOf(WinMap.class, result);
        assertEquals(21.0, tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyShiftmapThenWinmap() {
        // 2 * (x + 10) + 1 = 2 * x + 21
        CmpMap cmp =
                new CmpMap(new ShiftMap(new double[] {10.0}), new WinMap(new double[] {1.0}, new double[] {2.0}), true);
        Mapping result = cmp.simplify();
        assertEquals(31.0, tran1(result, 5.0, true), 1e-12);
        assertEquals(tran1(cmp, 5.0, true), tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyZoommapThenShiftmap() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 3.0), new ShiftMap(new double[] {10.0}), true);
        Mapping result = cmp.simplify();
        assertInstanceOf(WinMap.class, result);
        assertEquals(25.0, tran1(result, 5.0, true), 1e-12);
        assertEquals(tran1(cmp, 5.0, true), tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyShiftmapThenZoommap() {
        CmpMap cmp = new CmpMap(new ShiftMap(new double[] {10.0}), new ZoomMap(1, 3.0), true);
        Mapping result = cmp.simplify();
        assertInstanceOf(WinMap.class, result);
        assertEquals(45.0, tran1(result, 5.0, true), 1e-12);
        assertEquals(tran1(cmp, 5.0, true), tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyWinmapThenInvZoommap() {
        // (3 * x + 6) / 3 = x + 2
        ZoomMap z = new ZoomMap(1, 3.0);
        z.invert();
        CmpMap cmp = new CmpMap(new WinMap(new double[] {6.0}, new double[] {3.0}), z, true);
        Mapping result = cmp.simplify();
        assertEquals(7.0, tran1(result, 5.0, true), 1e-12);
        assertEquals(tran1(cmp, 5.0, true), tran1(result, 5.0, true), 1e-12);
    }

    @Test
    void simplifyInvShiftmapThenZoommap() {
        // 2 * (x - 10)
        ShiftMap s = new ShiftMap(new double[] {10.0});
        s.invert();
        CmpMap cmp = new CmpMap(s, new ZoomMap(1, 2.0), true);
        Mapping result = cmp.simplify();
        assertEquals(10.0, tran1(result, 15.0, true), 1e-12);
        assertEquals(tran1(cmp, 15.0, true), tran1(result, 15.0, true), 1e-12);
    }

    @Test
    void simplifyCrossTypeMultiAxis() {
        // 3 * (x + shift)
        CmpMap cmp = new CmpMap(new ShiftMap(new double[] {1.0, 2.0}), new ZoomMap(2, 3.0), true);
        Mapping result = cmp.simplify();
        assertInstanceOf(WinMap.class, result);
        assertArrayEquals(
                new double[] {33.0, 66.0}, transformPoints(result, true, new double[] {10.0, 20.0})[0], 1e-12);
        assertSameTransform(cmp, result, true, 1e-12, new double[] {10.0, 20.0});
    }

    @Test
    void simplifyZoommapInverseViaWinmap() {
        CmpMap cmp = new CmpMap(new ZoomMap(1, 3.0), new WinMap(new double[] {0.0}, new double[] {1.0 / 3.0}), true);
        assertInstanceOf(UnitMap.class, cmp.simplify());
    }

    @Test
    void simplifyMixedLinearChain() {
        // 3 * (x + 1) + 10 = 3 * x + 13
        CmpMap inner = new CmpMap(new ShiftMap(new double[] {1.0}), new ZoomMap(1, 3.0), true);
        CmpMap outer = new CmpMap(inner, new ShiftMap(new double[] {10.0}), true);
        Mapping result = outer.simplify();
        assertEquals(28.0, tran1(result, 5.0, true), 1e-12);
        assertFalse(result instanceof CmpMap);
    }

    // ------------------------------------------------------------------
    // MatrixMap merges (matrixmap.c MapMerge)
    // ------------------------------------------------------------------

    /** A MatrixMap of the full matrix given as rows. */
    private static MatrixMap matrix(double[]... rows) {
        return new MatrixMap(rows[0].length, rows.length, rows);
    }

    @Test
    void simplifyMatrixRot90InverseCancels() {
        CmpMap cmp = new CmpMap(
                matrix(new double[] {0.0, -1.0}, new double[] {1.0, 0.0}),
                matrix(new double[] {0.0, 1.0}, new double[] {-1.0, 0.0}),
                true);
        Mapping result = cmp.simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyMatrixSeriesMultiplies() {
        CmpMap cmp = new CmpMap(
                matrix(new double[] {1.0, 1.0}, new double[] {0.0, 1.0}),
                matrix(new double[] {2.0, 0.0}, new double[] {0.0, 1.0}),
                true);
        Mapping simplified = cmp.simplify();
        assertInstanceOf(MatrixMap.class, simplified);
        assertArrayEquals(
                new double[] {6.0, 2.0}, transformPoints(simplified, true, new double[] {1.0, 2.0})[0], 1e-12);
    }

    @Test
    void simplifyMatrixPreservesTransform() {
        CmpMap cmp = new CmpMap(
                matrix(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}),
                matrix(new double[] {5.0, 6.0}, new double[] {7.0, 8.0}),
                true);
        Mapping simplified = cmp.simplify();
        assertSameTransform(cmp, simplified, true, 1e-10, new double[] {1.0, -1.0}, new double[] {3.0, 7.0});
    }

    @Test
    void simplifyMatrixWithInversionFlag() {
        MatrixMap b = matrix(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        b.invert();
        CmpMap cmp = new CmpMap(matrix(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}), b, true);
        Mapping result = cmp.simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyMatrixIdentityReducesToUnitmap() {
        Mapping result =
                matrix(new double[] {1.0, 0.0}, new double[] {0.0, 1.0}).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyMatrixUniformDiagonalReducesToZoommap() {
        Mapping result =
                matrix(new double[] {3.0, 0.0}, new double[] {0.0, 3.0}).simplify();
        assertInstanceOf(ZoomMap.class, result);
        assertEquals(2, result.getNin());
        assertArrayEquals(new double[] {6.0, 15.0}, transformPoints(result, true, new double[] {2.0, 5.0})[0], 1e-12);
    }

    @Test
    void simplifyMatrixNonuniformDiagonalStaysMatrix() {
        assertInstanceOf(
                MatrixMap.class,
                matrix(new double[] {2.0, 0.0}, new double[] {0.0, 5.0}).simplify());
    }

    @Test
    void simplifyMatrixNonSquareStaysMatrix() {
        assertInstanceOf(
                MatrixMap.class,
                matrix(new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0})
                        .simplify());
    }

    /**
     * An inverted uniform diagonal MatrixMap becomes a forward ZoomMap whose factor is read from the
     * inverse matrix (matrixmap.c:1885-1892, the replacement applied forward at 1925).
     */
    @Test
    void simplifyMatrixInvertedDiagonalReducesToZoommap() {
        MatrixMap mm = matrix(new double[] {4.0, 0.0}, new double[] {0.0, 4.0});
        mm.invert();
        ZoomMap result = assertInstanceOf(ZoomMap.class, mm.simplify());
        assertFalse(result.getInvert());
        assertEquals(0.25, result.getZoom(), 1e-12);
        assertArrayEquals(new double[] {2.0, 3.0}, transformPoints(result, true, new double[] {8.0, 12.0})[0], 1e-12);
    }

    @Test
    void simplifyZoommapThenMatrixmap() {
        CmpMap cmp = new CmpMap(new ZoomMap(2, 3.0), matrix(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}), true);
        Mapping simplified = cmp.simplify();
        assertInstanceOf(MatrixMap.class, simplified);
        assertSameTransform(cmp, simplified, true, 1e-12, new double[] {1.0, 2.0});
    }

    @Test
    void simplifyMatrixmapThenZoommap() {
        CmpMap cmp = new CmpMap(matrix(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}), new ZoomMap(2, 3.0), true);
        Mapping simplified = cmp.simplify();
        assertInstanceOf(MatrixMap.class, simplified);
        assertSameTransform(cmp, simplified, true, 1e-12, new double[] {1.0, 2.0});
    }

    @Test
    void simplifyMatrixmapThenInvZoommap() {
        ZoomMap z = new ZoomMap(2, 3.0);
        z.invert();
        CmpMap cmp = new CmpMap(matrix(new double[] {3.0, 0.0}, new double[] {0.0, 3.0}), z, true);
        Mapping result = cmp.simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyDiagonalMatrixSeriesReduces() {
        CmpMap cmp = new CmpMap(
                matrix(new double[] {2.0, 0.0}, new double[] {0.0, 2.0}),
                matrix(new double[] {3.0, 0.0}, new double[] {0.0, 3.0}),
                true);
        Mapping simplified = cmp.simplify();
        assertInstanceOf(ZoomMap.class, simplified);
        assertArrayEquals(
                new double[] {6.0, 12.0}, transformPoints(simplified, true, new double[] {1.0, 2.0})[0], 1e-12);
    }

    /**
     * A ZoomMap of two axes and a ShiftMap of three cannot be joined in series (cmpmap.c {@code
     * astInitCmpMap}), so no merge list can hold them side by side and no merge is offered.
     */
    @Test
    void simplifyCrossTypeDimensionMismatchNoMerge() {
        ZoomMap z = new ZoomMap(2, 3.0);
        ShiftMap s = new ShiftMap(new double[] {1.0, 2.0, 3.0});
        AstException e = assertThrows(AstException.class, () -> new CmpMap(z, s, true));
        assertEquals(AstException.AST__INNCO, e.getStatus());
    }

    // ------------------------------------------------------------------
    // PermMap merges (permmap.c MapMerge)
    // ------------------------------------------------------------------

    /** A swap of two axes followed by its own inverse composes to the null permutation, a UnitMap. */
    @Test
    void simplifyPermmapInverseCancels() {
        PermMap fwd = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        PermMap inv = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        inv.invert();
        Mapping result = new CmpMap(fwd, inv, true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    /**
     * A swap of two axes followed by the inverse of the null permutation composes to the swap, a
     * PermMap that replaces the two (permmap.c {@code MapMerge}: {@code ngone} is 1).
     */
    @Test
    void simplifyPermmapDifferentPermsNoMerge() {
        PermMap fwd = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        PermMap inv = new PermMap(2, new int[] {1, 2}, 2, new int[] {1, 2}, null);
        inv.invert();
        Mapping result = new CmpMap(fwd, inv, true).simplify();
        PermMap pm = assertInstanceOf(PermMap.class, result);
        assertArrayEquals(new double[] {2.0, 1.0}, transformPoints(pm, true, new double[] {1.0, 2.0})[0]);
    }

    // ------------------------------------------------------------------
    // CmpMap merges (cmpmap.c MapMerge)
    // ------------------------------------------------------------------

    /** A one-coordinate ZoomMap of factor {@code f}. */
    private static ZoomMap zoom1(double f) {
        return new ZoomMap(1, f);
    }

    /** A one-coordinate ShiftMap of {@code s}. */
    private static ShiftMap shift1(double s) {
        return new ShiftMap(new double[] {s});
    }

    /** Checks that {@code m} is a diagonal MatrixMap with the forward diagonal {@code diagonal}. */
    private static void assertDiagonal(Mapping m, double... diagonal) {
        MatrixMap mm = assertInstanceOf(MatrixMap.class, m);
        assertEquals(MatrixMap.DIAGONAL, mm.storageForm());
        assertArrayEquals(diagonal, mm.storedForward(), 0.0);
    }

    /**
     * Parallel ZoomMaps in series are regrouped into series pairs, each of which merges; C gives a
     * diagonal MatrixMap of 10 and 21.
     */
    @Test
    void parallelInSeriesBasicZoomMerge() {
        CmpMap series =
                new CmpMap(new CmpMap(zoom1(2.0), zoom1(3.0), false), new CmpMap(zoom1(5.0), zoom1(7.0), false), true);
        Mapping result = series.simplify();
        assertDiagonal(result, 10.0, 21.0);
        assertArrayEquals(new double[] {10.0, 21.0}, transformPoints(result, true, new double[] {1.0, 1.0})[0], 1e-12);
    }

    /** Parallel ShiftMaps in series; C gives one ShiftMap of 7 and 10. */
    @Test
    void parallelInSeriesShiftMerge() {
        CmpMap series = new CmpMap(
                new CmpMap(shift1(2.0), shift1(3.0), false), new CmpMap(shift1(5.0), shift1(7.0), false), true);
        Mapping result = series.simplify();
        ShiftMap shift = assertInstanceOf(ShiftMap.class, result);
        assertArrayEquals(new double[] {7.0, 10.0}, shift.getShifts(), 0.0);
        assertArrayEquals(new double[] {8.0, 11.0}, transformPoints(result, true, new double[] {1.0, 1.0})[0], 1e-12);
    }

    /**
     * Parallel CmpMaps in series whose components split the coordinates differently, 2 and 1 against
     * 1, 1 and 1: the runs are matched by number of coordinates; C gives a diagonal MatrixMap of 10,
     * 14 and 27. (Rust only checks that the simplification ends.)
     */
    @Test
    void parallelInSeriesNoImprovementNoChange() {
        CmpMap par1 = new CmpMap(new ZoomMap(2, 2.0), zoom1(3.0), false);
        CmpMap par2 = new CmpMap(new CmpMap(zoom1(5.0), zoom1(7.0), false), zoom1(9.0), false);
        Mapping result = new CmpMap(par1, par2, true).simplify();
        assertDiagonal(result, 10.0, 14.0, 27.0);
    }

    /** Two-coordinate ZoomMaps in parallel, in series; C gives a diagonal MatrixMap of 10, 10, 21, 21. */
    @Test
    void parallelInSeriesDimensionMatchingMulticomponent() {
        CmpMap series = new CmpMap(
                new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), false),
                new CmpMap(new ZoomMap(2, 5.0), new ZoomMap(2, 7.0), false),
                true);
        Mapping result = series.simplify();
        assertDiagonal(result, 10.0, 10.0, 21.0, 21.0);
        assertArrayEquals(
                new double[] {10.0, 10.0, 21.0, 21.0},
                transformPoints(result, true, new double[] {1.0, 1.0, 1.0, 1.0})[0],
                1e-12);
    }

    /** Series pairs that cancel, side by side; C gives a UnitMap of 2 coordinates. */
    @Test
    void seriesInParallelBasicZoomMerge() {
        CmpMap parallel = new CmpMap(
                new CmpMap(zoom1(2.0), zoom1(0.5), true), new CmpMap(zoom1(3.0), zoom1(1.0 / 3.0), true), false);
        Mapping result = parallel.simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    /**
     * Series of different lengths side by side: each series merges on its own, and the two
     * ZoomMaps then merge into a diagonal MatrixMap; C gives 24 and 30.
     */
    @Test
    void seriesInParallelLengthMismatchNoChange() {
        CmpMap ser1 = new CmpMap(new CmpMap(zoom1(2.0), zoom1(3.0), true), zoom1(4.0), true);
        CmpMap ser2 = new CmpMap(zoom1(5.0), zoom1(6.0), true);
        Mapping result = new CmpMap(ser1, ser2, false).simplify();
        assertDiagonal(result, 24.0, 30.0);
    }

    /**
     * A PermMap swapping two axes before parallel ShiftMaps: the CmpMap is swapped in front of the
     * PermMap; C gives the swap PermMap followed by one ShiftMap of 10 and 20.
     */
    @Test
    void permmapSwapBasicBlockSwap() {
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        CmpMap series = new CmpMap(pm, new CmpMap(shift1(10.0), shift1(20.0), false), true);
        Mapping result = series.simplify();
        CmpMap cm = assertInstanceOf(CmpMap.class, result);
        assertTrue(cm.isSeries());
        assertInstanceOf(PermMap.class, cm.getMap1());
        ShiftMap shift = assertInstanceOf(ShiftMap.class, cm.getMap2());
        assertArrayEquals(new double[] {10.0, 20.0}, shift.getShifts(), 0.0);
        assertSameTransform(series, result, true, 1e-12, new double[] {1.0, 2.0});
    }

    /** The null permutation before parallel ShiftMaps; C gives one ShiftMap of 10 and 20. */
    @Test
    void permmapSwapNonBlockNoChange() {
        PermMap pm = new PermMap(2, new int[] {1, 2}, 2, new int[] {1, 2}, null);
        CmpMap series = new CmpMap(pm, new CmpMap(shift1(10.0), shift1(20.0), false), true);
        Mapping result = series.simplify();
        ShiftMap shift = assertInstanceOf(ShiftMap.class, result);
        assertArrayEquals(new double[] {10.0, 20.0}, shift.getShifts(), 0.0);
        assertSameTransform(series, result, true, 1e-12, new double[] {1.0, 2.0});
    }

    /** A PermMap of 3 coordinates whose outputs are constants 4 to 6 of {@code 0.1, 0.2, 5, 0.3, 0.4, 7}. */
    private static PermMap constantOutputs(int[] inperm) {
        return new PermMap(3, inperm, 3, new int[] {-4, -5, -6}, new double[] {0.1, 0.2, 5.0, 0.3, 0.4, 7.0});
    }

    /** A WcsMap (TAN) in parallel with a ZoomMap of 3. */
    private static CmpMap wcsBesideZoom() {
        return new CmpMap(new WcsMap(2, WcsMap.AST__TAN, 1, 2), zoom1(3.0), false);
    }

    /**
     * A PermMap whose outputs are all constant, and whose inputs are all bad, before a parallel
     * CmpMap: both blocks are constant, so the CmpMap is swapped in front of the PermMap as the
     * second component beside a UnitMap, and the new PermMap holds what the components make of the
     * constants. C gives a diagonal MatrixMap of 3, 1 and 1, then a PermMap of the constants.
     */
    @Test
    void aSwapWithBothBlocksConstantHoldsTheTransformedConstants() {
        CmpMap series = new CmpMap(constantOutputs(new int[] {0, 0, 0}), wcsBesideZoom(), true);
        CmpMap cm = assertInstanceOf(CmpMap.class, series.simplify());
        assertDiagonal(cm.getMap1(), 3.0, 1.0, 1.0);
        PermMap pm = assertInstanceOf(PermMap.class, cm.getMap2());
        assertArrayEquals(
                new double[] {0.6989710183699915, -2.259583282761333, 21.0},
                transformPoints(pm, true, new double[] {1.0, 2.0, 3.0})[0],
                0.0);
    }

    /**
     * A PermMap whose outputs and inputs are all constant before a parallel CmpMap of two WcsMaps:
     * both blocks are constant, and C's swap passes the PermMap's inverse constants through the
     * second WcsMap's inverse (cmpmap.c:2255-2259), so the forward transformation is kept and the
     * inverse one changes. The values are C's.
     */
    @Test
    void aSwapWithBothBlocksConstantChangesTheInverseAsCDoes() {
        CmpMap par = new CmpMap(new WcsMap(2, WcsMap.AST__TAN, 1, 2), new WcsMap(2, WcsMap.AST__SIN, 1, 2), false);
        PermMap pm = new PermMap(4, new int[] {-1, -2, -3, -4}, 4, new int[] {-5, -6, -7, -8}, new double[] {
            0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8
        });
        CmpMap series = new CmpMap(pm, par, true);
        Mapping result = series.simplify();
        double[] in = {0.01, 0.02, 0.03, 0.04};
        assertSameTransform(series, result, true, 0.0, in);
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4}, transformPoints(series, false, in)[0], 0.0);
        assertArrayEquals(
                new double[] {2.677945044588987, 1.3452829208967654, 0.3, 0.4},
                transformPoints(result, false, in)[0],
                0.0);
    }

    /**
     * As {@link #aSwapWithBothBlocksConstantHoldsTheTransformedConstants}, but with the PermMap's
     * inputs constant too, so that the two blocks store more constants than C allocates room for
     * (cmpmap.c:2176, written past at 2243): every constant is kept, and the forward transformation
     * is unchanged.
     */
    @Test
    void aSwapWithMoreConstantsThanCAllocatesKeepsThemAll() {
        CmpMap series = new CmpMap(constantOutputs(new int[] {-1, -2, -3}), wcsBesideZoom(), true);
        Mapping result = series.simplify();
        assertSameTransform(series, result, true, 0.0, new double[] {1.0, 2.0, 3.0});
    }

    /**
     * Three parallel CmpMaps in series end in one diagonal MatrixMap (C: 110, 110, 273, 273). The
     * Rust test of this name checks an iteration cap, which neither C nor this library has.
     */
    @Test
    void anOscillatingListStopsAtThePatternCheck() {
        CmpMap par = new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(2, 3.0), false);
        CmpMap par2 = new CmpMap(new ZoomMap(2, 5.0), new ZoomMap(2, 7.0), false);
        CmpMap par3 = new CmpMap(new ZoomMap(2, 11.0), new ZoomMap(2, 13.0), false);
        CmpMap outer = new CmpMap(new CmpMap(par, par2, true), par3, true);
        Mapping result = outer.simplify();
        assertDiagonal(result, 110.0, 110.0, 273.0, 273.0);
    }

    /**
     * A ShiftMap after a WcsMap and a UnitMap in parallel shifts only the UnitMap's coordinate, and
     * is absorbed into that branch; C gives the WcsMap in parallel with a ShiftMap of 7.5.
     */
    @Test
    void seriesParallelAbsorbTrailingShiftIntoBranch() {
        WcsMap wcs = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        CmpMap par = new CmpMap(wcs, new UnitMap(1), false);
        CmpMap series = new CmpMap(par, new ShiftMap(new double[] {0.0, 0.0, 7.5}), true);
        Mapping result = series.simplify();
        CmpMap cm = assertInstanceOf(CmpMap.class, result);
        assertFalse(cm.isSeries());
        assertEquals(3, result.getNin());
        assertInstanceOf(WcsMap.class, cm.getMap1());
        ShiftMap shift = assertInstanceOf(ShiftMap.class, cm.getMap2());
        assertArrayEquals(new double[] {7.5}, shift.getShifts(), 0.0);
        assertSameTransform(series, result, true, 1e-12, new double[] {0.1, 0.2, 4.0});
        assertEquals(11.5, transformPoints(result, true, new double[] {0.1, 0.2, 4.0})[0][2], 1e-12);
    }

    @Test
    void simplifySphmapFwdInvCancels() {
        SphMap fwd = new SphMap();
        fwd.setUnitRadius(true);
        SphMap inv = new SphMap();
        inv.setUnitRadius(true);
        inv.invert();
        Mapping result = new CmpMap(fwd, inv, true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(3, result.getNin());
    }

    @Test
    void simplifySphmapInvFwdCancels() {
        SphMap inv = new SphMap();
        inv.invert();
        Mapping result = new CmpMap(inv, new SphMap(), true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifySphmapFwdInvCancelsWithUnitRadiusOnFirst() {
        SphMap fwd = new SphMap();
        fwd.setUnitRadius(true);
        SphMap inv = new SphMap();
        inv.invert();
        Mapping result = new CmpMap(fwd, inv, true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(3, result.getNin());
    }

    @Test
    void simplifySphmapFwdInvNoCancelWithoutUnitRadius() {
        SphMap inv = new SphMap();
        inv.invert();
        assertFalse(new CmpMap(new SphMap(), inv, true).simplify() instanceof UnitMap);
    }

    @Test
    void simplifyWcsmapInverseCancels() {
        WcsMap inv = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        inv.invert();
        Mapping result = new CmpMap(new WcsMap(2, WcsMap.AST__TAN, 1, 2), inv, true).simplify();
        assertInstanceOf(UnitMap.class, result);
        assertEquals(2, result.getNin());
    }

    @Test
    void simplifyWcsmapDifferentProjNoMerge() {
        WcsMap inv = new WcsMap(2, WcsMap.AST__SIN, 1, 2);
        inv.invert();
        Mapping result = new CmpMap(new WcsMap(2, WcsMap.AST__TAN, 1, 2), inv, true).simplify();
        assertFalse(result instanceof UnitMap);
    }

    /**
     * An FK5 conversion and its inverse in parallel CmpMaps, with a ShiftMap on the other axis in a
     * parallel CmpMap between them: C swaps the CmpMaps so that the SlaMaps cancel, and gives a
     * ShiftMap of 0.001 on the third axis. (Rust only logs the result.)
     */
    @Test
    @org.junit.jupiter.api.Disabled("needs SlaMap's MapMerge")
    void parallelInSeriesWithBlockingDelta() {
        SlaMap sla1 = new SlaMap();
        sla1.add("HFK5Z", new double[] {2000.0});
        SlaMap sla2 = new SlaMap();
        sla2.add("FK5HZ", new double[] {2000.0});
        CmpMap pair1 = new CmpMap(sla1, new UnitMap(1), false);
        CmpMap pair2 = new CmpMap(new UnitMap(2), shift1(0.001), false);
        CmpMap pair3 = new CmpMap(sla2, new UnitMap(1), false);
        CmpMap full = new CmpMap(new CmpMap(pair1, pair2, true), pair3, true);
        Mapping result = full.simplify();
        ShiftMap shift = assertInstanceOf(ShiftMap.class, result);
        assertArrayEquals(new double[] {0.0, 0.0, 0.001}, shift.getShifts(), 0.0);
    }
}
