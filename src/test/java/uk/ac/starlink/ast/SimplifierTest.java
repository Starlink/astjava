package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Disabled;
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

    @Test
    void anIdentMappingIsNotMerged() {
        UnitMap named = new UnitMap(1);
        named.setIdent("keep");
        CmpMap cmp = new CmpMap(new ZoomMap(1, 2.0), named, true);
        Mapping out = cmp.simplify();
        assertTrue(Channel.text(out, false, 0).contains("keep"), "the Mapping with Ident set is still present");
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
    @Disabled("needs ZoomMap merge: Task 3")
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
}
