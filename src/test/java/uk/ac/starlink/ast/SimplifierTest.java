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
}
