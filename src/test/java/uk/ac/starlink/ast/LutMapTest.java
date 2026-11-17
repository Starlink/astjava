package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_lutmap.rs} and {@code tests/test_lutmap_inverse.rs}, and of
 * C's {@code testlutmap.c}.
 *
 * <p>The Rust tests read the monotonicity of the table, which C keeps to itself; here they read
 * what C makes of it, whether the inverse transformation is defined. Rust's {@code
 * lutmap_interp_change_applies_to_a_repeated_value} expects the result of the new interpolation
 * method, where C returns the result it kept for the same input; the test here expects C's value,
 * from the probe {@code probes/lutmap_probe.c}.
 *
 * <p>The cases of {@code probes/lutmap_probe.out} pin what no fixture or oracle holds: the
 * simplification of a LutMap beside a WinMap, a ZoomMap and its own inverse, its dumps through a
 * Channel and a FitsChan with LutInterp and LutEpsilon set and unset, its attribute strings, and
 * the input and output it keeps from its last transformation.
 */
class LutMapTest {

    private static final double BAD = AstObject.AST__BAD;

    /** A table that is not linear, so the WinMap replacement cannot hide the cancellation. */
    private static final double[] CURVED = {0.0, 1.0, 4.0, 9.0, 16.0};

    private static final double[] LINEAR = {1.0, 3.0, 5.0, 7.0, 9.0};

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("lutmap_probe");

    private static double at(Mapping m, double x, boolean forward) {
        return m.tran1(1, new double[] {x}, forward)[0];
    }

    private static LutMap inverted(LutMap m) {
        LutMap c = (LutMap) m.copy();
        c.invert();
        return c;
    }

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    void lutmapDimensions() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(1, m.getNin());
        assertEquals(1, m.getNout());
    }

    @Test
    void lutmapEmptyTableRejected() {
        AstException e = assertThrows(AstException.class, () -> new LutMap(new double[0], 0.0, 1.0));
        assertEquals(AstException.AST__LUTIN, e.getStatus());
    }

    @Test
    void lutmapZeroIncRejected() {
        AstException e = assertThrows(AstException.class, () -> new LutMap(new double[] {1.0, 2.0}, 0.0, 0.0));
        assertEquals(AstException.AST__LUTII, e.getStatus());
    }

    /** The table is stored as C's {@code astGetLutMapInfo} gives it, an entry that is not finite being bad. */
    @Test
    void theTableIsStoredWithBadValues() {
        LutMap m = new LutMap(new double[] {1.0, Double.NaN, Double.POSITIVE_INFINITY, 4.0}, 2.5, -0.5);
        assertArrayEquals(new double[] {1.0, BAD, BAD, 4.0}, m.lutTable());
        assertEquals(2.5, m.lutStart());
        assertEquals(-0.5, m.lutInc());
    }

    @Test
    void lutmapDefaultInterp() {
        LutMap m = new LutMap(new double[] {0.0, 10.0}, 0.0, 1.0);
        assertEquals(0, m.getI("LutInterp"));
    }

    // ------------------------------------------------------------------
    // Monotonicity, which decides whether the inverse is defined
    // ------------------------------------------------------------------

    @Test
    void lutmapMonotonicIncreasing() {
        assertTrue(new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0).getTranInverse());
    }

    @Test
    void lutmapMonotonicDecreasing() {
        assertTrue(new LutMap(new double[] {30.0, 20.0, 10.0, 0.0}, 0.0, 1.0).getTranInverse());
    }

    @Test
    void lutmapNonMonotonic() {
        assertFalse(new LutMap(new double[] {0.0, 10.0, 5.0, 30.0}, 0.0, 1.0).getTranInverse());
    }

    @Test
    void lutmapFlatSectionsMonotonic() {
        assertTrue(new LutMap(new double[] {0.0, 10.0, 10.0, 20.0}, 0.0, 1.0).getTranInverse());
    }

    @Test
    void lutmapAllEqualUndetermined() {
        assertFalse(new LutMap(new double[] {5.0, 5.0, 5.0}, 0.0, 1.0).getTranInverse());
    }

    @Test
    void lutmapBadEntriesIgnoredForMonotonicity() {
        assertTrue(new LutMap(new double[] {0.0, BAD, 20.0, 30.0}, 0.0, 1.0).getTranInverse());
    }

    // ------------------------------------------------------------------
    // Linear forward transform
    // ------------------------------------------------------------------

    @Test
    void lutmapLinearInterpolation() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(15.0, at(m, 1.5, true), 1e-10);
    }

    @Test
    void lutmapLinearAtExactEntry() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(20.0, at(m, 2.0, true), 1e-10);
    }

    @Test
    void lutmapLinearAtFirstEntry() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(0.0, at(m, 0.0, true), 1e-10);
    }

    @Test
    void lutmapLinearAtLastEntry() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(30.0, at(m, 3.0, true), 1e-10);
    }

    @Test
    void lutmapLinearExtrapolationBelow() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(-5.0, at(m, -0.5, true), 1e-10);
    }

    @Test
    void lutmapLinearExtrapolationAbove() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(35.0, at(m, 3.5, true), 1e-10);
    }

    @Test
    void lutmapLinearWithNonzeroStartAndInc() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 10.0, 5.0);
        assertEquals(5.0, at(m, 12.5, true), 1e-10);
    }

    // ------------------------------------------------------------------
    // Nearest-neighbour forward transform
    // ------------------------------------------------------------------

    @Test
    void lutmapNearestInBounds() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        m.setI("LutInterp", 1);
        assertEquals(10.0, at(m, 1.3, true), 1e-10);
        assertEquals(20.0, at(m, 1.7, true), 1e-10);
    }

    @Test
    void lutmapNearestOutsideBounds() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        m.setI("LutInterp", 1);
        assertEquals(BAD, at(m, -1.0, true));
        assertEquals(BAD, at(m, 4.0, true));
    }

    // ------------------------------------------------------------------
    // Bad values
    // ------------------------------------------------------------------

    @Test
    void lutmapBadInputPropagation() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(BAD, at(m, BAD, true));
        assertEquals(BAD, at(m, Double.NaN, true));
    }

    @Test
    void lutmapBadInTableBrackets() {
        LutMap m = new LutMap(new double[] {0.0, BAD, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(BAD, at(m, 0.5, true));
    }

    @Test
    void lutmapBadAtBoundaryExtrapolation() {
        LutMap m = new LutMap(new double[] {BAD, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(BAD, at(m, -0.5, true));
    }

    // ------------------------------------------------------------------
    // Inverse transform
    // ------------------------------------------------------------------

    @Test
    void lutmapInverseMonotonicIncreasing() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(1.5, at(m, 15.0, false), 1e-10);
    }

    @Test
    void lutmapInverseMonotonicDecreasing() {
        LutMap m = new LutMap(new double[] {30.0, 20.0, 10.0, 0.0}, 0.0, 1.0);
        assertEquals(15.0, at(m, 1.5, true), 1e-10);
        assertEquals(1.5, at(m, 15.0, false), 1e-10);
    }

    @Test
    void lutmapInverseNonMonotonicIsAnError() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 5.0, 30.0}, 0.0, 1.0);
        assertFalse(m.getTranInverse());
        AstException e = assertThrows(AstException.class, () -> at(m, 15.0, false));
        assertEquals(AstException.AST__TRNND, e.getStatus());
    }

    @Test
    void lutmapFlatSectionInverseReturnsBad() {
        LutMap m = new LutMap(new double[] {0.0, 1.0, 1.0, 1.0, 2.0}, 0.0, 1.0);
        assertEquals(BAD, at(m, 1.0, false));
    }

    @Test
    void lutmapInverseRoundTrip() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        for (double x : new double[] {0.5, 1.0, 1.5, 2.0, 2.5}) {
            double y = at(m, x, true);
            assertEquals(x, at(m, y, false), 1e-10, "round trip of " + x);
        }
    }

    @Test
    void lutmapInverseExtrapolation() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(-0.5, at(m, -5.0, false), 1e-10);
        assertEquals(3.5, at(m, 35.0, false), 1e-10);
    }

    @Test
    void lutmapInverseBadInput() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertEquals(BAD, at(m, BAD, false));
    }

    @Test
    void lutmapSingleElementIsAnError() {
        AstException e = assertThrows(AstException.class, () -> new LutMap(new double[] {42.0}, 0.0, 1.0));
        assertEquals(AstException.AST__LUTIN, e.getStatus());
        assertEquals(
                "astInitLutMap(LutMap): Invalid number of lookup table elements (1).\n"
                        + "This value should be at least 2.",
                e.getMsgText());
        e = assertThrows(AstException.class, () -> new LutMap(new double[] {1.0, 2.0}, 0.0, 0.0));
        assertEquals(AstException.AST__LUTII, e.getStatus());
        assertEquals(
                "astInitLutMap(LutMap): An input value increment of zero between lookup table elements is"
                        + " not allowed.",
                e.getMsgText());
    }

    @Test
    void lutmapInvertFlag() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        assertFalse(m.getInvert());
        m.invert();
        assertTrue(m.getInvert());
        assertEquals(1.5, at(m, 15.0, true), 1e-10);
    }

    @Test
    void lutmapMultiplePoints() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        double[] out = m.tran1(4, new double[] {0.0, 1.0, 1.5, 3.0}, true);
        assertArrayEquals(new double[] {0.0, 10.0, 15.0, 30.0}, out, 1e-10);
    }

    @Test
    void lutmapRate() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        // The rate of a linear table is about 10 everywhere (the Rust test's tolerance).
        assertEquals(10.0, m.rate(new double[] {1.5}, 1, 1), 1e-3);
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    @Test
    void lutmapSerialisationRoundTrip() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 5.0, 2.0);
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertEquals(at(m, 6.0, true), at(m2, 6.0, true), 1e-10);
    }

    @Test
    void lutmapSerialisationTextContent() {
        String text = ChannelText.dump(new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 5.0, 2.0));
        for (String s : new String[] {"Begin LutMap", "Nlut", "Start", "Incr", "L1", "L4", "End LutMap"}) {
            assertTrue(text.contains(s), "missing " + s);
        }
    }

    @Test
    void lutmapSerialisationDefaultCommented() {
        String text = ChannelText.dump(new LutMap(new double[] {0.0, 10.0, 20.0}, 0.0, 1.0));
        assertTrue(text.contains("#   Start = 0"), text);
        assertTrue(text.contains("#   Incr = 1"), text);
    }

    @Test
    void lutmapSerialisationNearestInterp() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0}, 0.0, 1.0);
        m.setI("LutInterp", 1);
        String text = ChannelText.dump(m);
        assertTrue(text.contains("LutInt"), text);
        LutMap m2 = assertInstanceOf(LutMap.class, ChannelText.read(text));
        assertEquals(1, m2.getI("LutInterp"));
    }

    @Test
    void lutmapSerialisationBadEntries() {
        LutMap m = new LutMap(new double[] {0.0, BAD, 20.0}, 0.0, 1.0);
        String text = ChannelText.dump(m);
        assertTrue(text.contains("L1"), text);
        assertFalse(text.contains("L2"), text);
        assertTrue(text.contains("L3"), text);
        Mapping m2 = (Mapping) ChannelText.read(text);
        assertEquals(BAD, at(m2, 0.5, true));
    }

    @Test
    void lutmapSerialisationInverted() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0}, 0.0, 1.0);
        m.invert();
        String text = ChannelText.dump(m);
        assertTrue(text.contains("Invert"), text);
        assertTrue(((Mapping) ChannelText.read(text)).getInvert());
    }

    // ------------------------------------------------------------------
    // Cancellation against an adjacent inverse LutMap
    // ------------------------------------------------------------------

    @Test
    void lutmapCancelsWithInverse() {
        LutMap lm = new LutMap(CURVED, 0.0, 1.0);
        assertInstanceOf(UnitMap.class, new CmpMap(lm, inverted(lm), true).simplify());
    }

    @Test
    void lutmapNoCancelDifferentLut() {
        LutMap lm1 = new LutMap(CURVED, 0.0, 1.0);
        LutMap lm2 = new LutMap(new double[] {0.0, 2.0, 3.0, 9.0, 8.0}, 0.0, 1.0);
        lm2.invert();
        assertFalse(new CmpMap(lm1, lm2, true).simplify() instanceof UnitMap);
    }

    @Test
    void lutmapNoCancelSameDirection() {
        LutMap lm = new LutMap(CURVED, 0.0, 1.0);
        assertFalse(new CmpMap(lm, (LutMap) lm.copy(), true).simplify() instanceof UnitMap);
    }

    @Test
    void lutmapNoCancelParallel() {
        LutMap lm = new LutMap(CURVED, 0.0, 1.0);
        assertFalse(new CmpMap(lm, inverted(lm), false).simplify() instanceof UnitMap);
    }

    // ------------------------------------------------------------------
    // Simplification: a linear table becomes a WinMap
    // ------------------------------------------------------------------

    @Test
    void lutmapLinearSimplifiesToWinmap() {
        LutMap lm = new LutMap(LINEAR, 0.0, 1.0);
        Mapping s = lm.simplify();
        assertInstanceOf(WinMap.class, s);
        assertEquals(1, s.getNin());
        assertEquals(1, s.getNout());
        assertEquals(at(lm, 2.5, true), at(s, 2.5, true), 1e-10);
    }

    @Test
    void lutmapNearestLinearDoesNotSimplify() {
        LutMap lm = new LutMap(LINEAR, 0.0, 1.0);
        lm.setI("LutInterp", 1);
        assertFalse(lm.simplify() instanceof WinMap);
    }

    @Test
    void lutmapLinearWithStartAndIncSimplifies() {
        LutMap lm = new LutMap(new double[] {100.0, 200.0, 300.0}, 10.0, 5.0);
        Mapping s = lm.simplify();
        assertInstanceOf(WinMap.class, s);
        for (double x : new double[] {10.0, 12.5, 15.0, 17.5, 20.0}) {
            assertEquals(at(lm, x, true), at(s, x, true), 1e-10, "x = " + x);
        }
    }

    @Test
    void lutmapLinearInvertedSimplifies() {
        LutMap lm = new LutMap(LINEAR, 0.0, 1.0);
        lm.invert();
        Mapping s = lm.simplify();
        assertInstanceOf(WinMap.class, s);
        // C builds the WinMap from the corners swapped, so it is applied forward.
        assertFalse(s.getInvert());
        assertEquals(at(lm, 5.0, true), at(s, 5.0, true), 1e-10);
    }

    @Test
    void lutmapNonlinearStaysLutmap() {
        assertInstanceOf(LutMap.class, new LutMap(CURVED, 0.0, 1.0).simplify());
    }

    @Test
    void lutmapHasBeenSimplifiedRespected() {
        LutMap lm = new LutMap(LINEAR, 0.0, 1.0);
        lm.setIsSimple();
        assertInstanceOf(LutMap.class, lm.simplify());
    }

    // ------------------------------------------------------------------
    // Values that are not finite
    // ------------------------------------------------------------------

    @Test
    void lutmapTreatsInfAsBad() {
        LutMap lm = new LutMap(new double[] {0.0, 1.0, 2.0, 3.0}, 0.0, 1.0);
        assertEquals(BAD, at(lm, Double.POSITIVE_INFINITY, true));
        assertEquals(BAD, at(lm, Double.NEGATIVE_INFINITY, true));
    }

    /**
     * Infinite and NaN entries are stored as bad values, so a table holding one is not linear and
     * stays a LutMap; a linear table of the same length simplifies to a UnitMap. The classes are
     * those C gives for the same four tables.
     */
    @Test
    void lutmapNonFiniteEntriesNotLinear() {
        double inf = Double.NEGATIVE_INFINITY;
        Object[][] tables = {
            {"interior -inf", new double[] {1.0, 2.0, 3.0, inf, inf}, true},
            {"last -inf", new double[] {1.0, 2.0, 3.0, 4.0, inf}, true},
            {"interior nan", new double[] {1.0, 2.0, Double.NaN, 4.0, 5.0}, true},
            {"linear", new double[] {1.0, 2.0, 3.0, 4.0, 5.0}, false},
        };
        for (Object[] t : tables) {
            Mapping s = new LutMap((double[]) t[1], 1.0, 1.0).simplify();
            if ((Boolean) t[2]) {
                assertInstanceOf(LutMap.class, s, (String) t[0]);
            } else {
                assertInstanceOf(UnitMap.class, s, (String) t[0]);
            }
        }
    }

    // ------------------------------------------------------------------
    // testlutmap.c
    // ------------------------------------------------------------------

    /** testlutmap.c Errors 1 and 2: a table equal to its input round-trips. */
    @Test
    void identityTableRoundTrips() {
        double[] lut = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8};
        double[] x = {-2.0, -1.0, -0.5, 3.0, 7.5, 8.0, 8.5};
        LutMap lm = new LutMap(lut, -1.0, 1.0);
        double[] y = lm.tran1(7, x, true);
        assertArrayEquals(x, y, "Error 1");
        assertArrayEquals(y, lm.tran1(7, y, false), "Error 2");
    }

    /**
     * testlutmap.c Errors 3-12. The table starts with two equal values, so anything at or below the
     * start maps to that value and its inverse is bad, while values beyond the flat run round-trip;
     * a bad table value makes its node and the value it would have held bad in both directions.
     */
    @Test
    void flatStartAndBadValueTables() {
        double[] lut = {0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0};
        double[] x = {-2.0, -1.0, -0.5, 0.5, 3.0, 8.0, 8.5};

        LutMap lm = new LutMap(lut, -1.0, 1.0);
        double[] y = lm.tran1(7, x, true);
        assertArrayEquals(new double[3], Arrays.copyOfRange(y, 0, 3), "Error 3");
        assertArrayEquals(Arrays.copyOfRange(x, 3, 7), Arrays.copyOfRange(y, 3, 7), "Error 4");
        double[] back = lm.tran1(7, y, false);
        assertArrayEquals(new double[] {BAD, BAD, BAD}, Arrays.copyOfRange(back, 0, 3), "Error 5");
        assertArrayEquals(Arrays.copyOfRange(y, 3, 7), Arrays.copyOfRange(back, 3, 7), "Error 6");

        lut[4] = BAD;
        lm = new LutMap(lut, -1.0, 1.0);
        y = lm.tran1(7, x, true);
        assertArrayEquals(new double[3], Arrays.copyOfRange(y, 0, 3), "Error 7");
        assertEquals(BAD, y[4], "Error 8");
        y[4] = x[4];
        assertArrayEquals(Arrays.copyOfRange(x, 3, 7), Arrays.copyOfRange(y, 3, 7), "Error 9");
        back = lm.tran1(7, y, false);
        assertArrayEquals(new double[] {BAD, BAD, BAD}, Arrays.copyOfRange(back, 0, 3), "Error 10");
        assertEquals(BAD, back[4], "Error 11");
        back[4] = y[4];
        assertArrayEquals(Arrays.copyOfRange(y, 3, 7), Arrays.copyOfRange(back, 3, 7), "Error 12");
    }

    /**
     * testlutmap.c Errors 13-16: the inverse of values beyond the first table value, which has no
     * lower table value to compare with.
     */
    @Test
    void inverseBeyondTheFirstValue() {
        LutMap lm = new LutMap(new double[] {1.0, BAD, 3.0}, 1.0, 1.0);
        assertEquals(0.25, at(lm, -0.5, false), "Error 13");
        lm = new LutMap(new double[] {1.0, 2.0, 4.0, 8.0}, 1.0, 1.0);
        lm.set("LutInterp=1");
        assertEquals(BAD, at(lm, -0.5, false), "Error 14");
        lm = new LutMap(new double[] {8.0, 4.0, 2.0, 1.0}, 1.0, 1.0);
        lm.set("LutInterp=1");
        double[] out = lm.tran1(2, new double[] {8.0, 10.0}, false);
        assertEquals(1.0, out[0], "Error 15");
        assertEquals(BAD, out[1], "Error 16");
    }

    /**
     * A long inverse array, with bad values, flat runs and values beyond both ends of a table with a
     * bad entry, gives each point the result it gets alone.
     */
    @Test
    void lutmapInverseOfALongArrayMatchesEachPointAlone() {
        double[] lut = {-3.0, 0.0, 0.0, 2.0, BAD, 5.0, 7.5, 7.5, 7.5, 9.0, 12.0, 20.0};
        for (int interp = 0; interp < 2; interp++) {
            LutMap m = new LutMap(lut, 1.0, 0.5);
            m.setI("LutInterp", interp);
            double[] ys = new double[1000];
            for (int i = 0; i < ys.length; i++) {
                ys[i] = i % 37 == 0 ? BAD : -5.0 + (i % 101) * 0.27;
            }
            double[] all = m.tran1(ys.length, ys, false);
            for (int i = 0; i < ys.length; i++) {
                assertEquals(
                        Double.doubleToRawLongBits(all[i]),
                        Double.doubleToRawLongBits(at(m, ys[i], false)),
                        "LutInterp " + interp + " point " + i + ", y = " + ys[i]);
            }
        }
    }

    /**
     * Changing LutInterp between transforms leaves the result for the last value transformed in
     * each direction as it was: C keeps the last input and output of each direction and returns
     * the kept output for the same input, whatever LutInterp now says (lutmap.c {@code
     * Transform}). The probe's {@code cache} case gives C's values; Rust expects those of the new
     * method.
     */
    @Test
    void lutmapInterpChangeKeepsTheLastResult() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0}, 1.0, 1.0);
        StringBuilder sb = new StringBuilder();
        tranLine(sb, m, 1.4, true);
        m.setI("LutInterp", 1);
        tranLine(sb, m, 1.4, true);
        tranLine(sb, m, 1.6, true);
        tranLine(sb, m, 1.4, true);
        tranLine(sb, m, 4.0, false);
        m.clear("LutInterp");
        tranLine(sb, m, 4.0, false);
        tranLine(sb, m, 5.0, false);
        tranLine(sb, m, 4.0, false);
        assertEquals(probe("cache"), sb.toString());
    }

    /** A copy keeps the last input and output of the LutMap it copies, as C's copy does. */
    @Test
    void aCopyKeepsTheLastResult() {
        LutMap c = new LutMap(new double[] {0.0, 10.0, 20.0}, 1.0, 1.0);
        StringBuilder sb = new StringBuilder();
        tranLine(sb, c, 1.4, true);
        LutMap cc = (LutMap) c.copy();
        cc.setI("LutInterp", 1);
        tranLine(sb, cc, 1.4, true);
        cc.invert();
        tranLine(sb, cc, 1.4, false);
        tranLine(sb, cc, 1.6, false);
        assertEquals(probe("cache_copy"), sb.toString());
    }

    private static void tranLine(StringBuilder sb, Mapping m, double x, boolean forward) {
        double y = at(m, x, forward);
        sb.append(forward ? "fwd " : "inv ")
                .append(CFormat.format("%.17g", x))
                .append(" -> ")
                .append(CFormat.format("%.17g", y))
                .append('\n');
    }

    // ------------------------------------------------------------------
    // test_lutmap_inverse.rs: the inverse against C
    // ------------------------------------------------------------------

    private static double[] doubles(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = ProbeData.toDouble(n.get(i));
        }
        return v;
    }

    /**
     * The inverse transformation matches C's (lutmap.c {@code Transform}) bit for bit, for tables
     * with bad entries, flat runs, either direction and both interpolation methods, at the table
     * values, between them and beyond both ends ({@code oracle/lutmap_inverse.json}, from {@code
     * tools/oracle_fixtures/lutmap_inverse_probe.c}).
     */
    @Test
    void inverseMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        JsonNode cases = ProbeData.load("lutmap_inverse").get("CASES");
        for (JsonNode c : cases) {
            String name = c.get(0).asText();
            LutMap map =
                    new LutMap(doubles(c.get(1)), c.get(2).asDouble(), c.get(3).asDouble());
            if (c.get(4).asBoolean()) {
                map.setI("LutInterp", 1);
            }
            JsonNode pairs = c.get(5);
            double[] ys = new double[pairs.size()];
            for (int i = 0; i < ys.length; i++) {
                ys[i] = ProbeData.toDouble(pairs.get(i).get(0));
            }
            double[] out = map.tran1(ys.length, ys, false);
            for (int i = 0; i < ys.length; i++) {
                double want = ProbeData.toDouble(pairs.get(i).get(1));
                if (Double.doubleToRawLongBits(out[i]) != Double.doubleToRawLongBits(want)) {
                    failures.add(name + ": inverse of " + ys[i] + " is " + out[i] + ", C gives " + want);
                }
            }
        }
        assertTrue(cases.size() > 0);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ------------------------------------------------------------------
    // The probe: simplification
    // ------------------------------------------------------------------

    private static void assertSimplifies(String name, Mapping map) {
        Mapping s = map.simplify();
        String got = "class " + s.astClass() + "\n" + ChannelText.dump(s);
        assertEquals(probe(name), got, name);
    }

    /** A LutMap that is not linear stays beside a WinMap or a ZoomMap, either side. */
    @Test
    void besideAWinMapOrZoomMap() {
        LutMap lc = new LutMap(CURVED, 0.0, 1.0);
        WinMap wm = new WinMap(1, new double[] {0.0}, new double[] {1.0}, new double[] {1.0}, new double[] {3.0});
        ZoomMap zm = new ZoomMap(1, 2.0);
        assertSimplifies("win_lut", new CmpMap(wm, lc, true));
        assertSimplifies("lut_win", new CmpMap(lc, wm, true));
        assertSimplifies("zoom_lut", new CmpMap(zm, lc, true));
        assertSimplifies("lut_zoom", new CmpMap(lc, zm, true));
    }

    /** A linear LutMap becomes a WinMap, which merges with a WinMap or a ZoomMap beside it. */
    @Test
    void aLinearLutMapMergesWithItsNeighbour() {
        LutMap ll = new LutMap(LINEAR, 0.0, 1.0);
        WinMap wm = new WinMap(1, new double[] {0.0}, new double[] {1.0}, new double[] {1.0}, new double[] {3.0});
        assertSimplifies("zoom_linlut", new CmpMap(new ZoomMap(1, 2.0), ll, true));
        assertSimplifies("linlut_win", new CmpMap(ll, wm, true));
        assertSimplifies("linlut_alone", ll);
        assertSimplifies("linlut_inverted", inverted(ll));
    }

    /**
     * A LutMap and its inverse in series cancel when C's comparison finds them equal: C compares
     * the nominated LutMap with its own Invert attribute, not the one it is applied with, so in an
     * inverted CmpMap a LutMap followed by its inverse is kept, and a LutMap followed by itself
     * cancels (lutmap.c {@code MapMerge}).
     */
    @Test
    void besideItsOwnInverse() {
        LutMap lc = new LutMap(CURVED, 0.0, 1.0);
        assertSimplifies("lut_lutinv", new CmpMap(lc, inverted(lc), true));
        assertSimplifies("lutinv_lut", new CmpMap(inverted(lc), lc, true));
        CmpMap cm = new CmpMap(lc, inverted(lc), true);
        cm.invert();
        assertSimplifies("inverted_cmp_lut_lutinv", cm);
        CmpMap same = new CmpMap(lc, (LutMap) lc.copy(), true);
        same.invert();
        assertSimplifies("inverted_cmp_lut_lut", same);
        CmpMap inner = new CmpMap(new ZoomMap(1, 2.0), lc, true);
        inner.invert();
        assertSimplifies("inverted_inner_then_lut", new CmpMap(inner, lc, true));
        assertSimplifies("lut_lut", new CmpMap(lc, (LutMap) lc.copy(), true));
        assertSimplifies("lut_lutinv_parallel", new CmpMap(lc, inverted(lc), false));
        LutMap ln = (LutMap) lc.copy();
        ln.setI("LutInterp", 1);
        assertSimplifies("lut_nearestinv", new CmpMap(lc, inverted(ln), true));
    }

    /**
     * A table C takes to be linear but whose ends are equal is neither replaced nor cancelled
     * against its inverse: C's {@code MapMerge} tries the cancellation only for a table that is not
     * linear.
     */
    @Test
    void aLinearTableWithEqualEndsIsKept() {
        LutMap lf = new LutMap(new double[] {1.0, 1.0 + Math.ulp(1.0), 1.0}, 0.0, 1.0);
        assertSimplifies("flat_linear_lutinv", new CmpMap(lf, inverted(lf), true));
        LutMap lk = new LutMap(new double[] {5.0, 5.0, 5.0}, 0.0, 1.0);
        assertSimplifies("constant_lutinv", new CmpMap(lk, inverted(lk), true));
    }

    /** LutEpsilon decides whether a nearly linear table becomes a WinMap. */
    @Test
    void lutEpsilonDecidesLinearity() {
        double[] near = {1.0, 3.0 + 1.0E-9, 5.0, 7.0, 9.0};
        assertSimplifies("near_linear_default_eps", new LutMap(near, 0.0, 1.0));
        LutMap le = new LutMap(near, 0.0, 1.0);
        le.set("LutEpsilon=1.0E-7");
        assertSimplifies("near_linear_eps", le);
    }

    // ------------------------------------------------------------------
    // The probe: attributes and dumps
    // ------------------------------------------------------------------

    private static String attrs(LutMap m) {
        return "LutInterp " + m.getC("LutInterp") + " test " + (m.test("LutInterp") ? 1 : 0) + "\n" + "LutEpsilon "
                + m.getC("LutEpsilon") + " test " + (m.test("LutEpsilon") ? 1 : 0) + "\n";
    }

    /**
     * The dump through a Channel, the cards of a native FitsChan write, and the dump of the Object
     * read back from them, as the probe's {@code dumps} gives them.
     */
    private static void assertDumps(String name, AstObject obj) {
        StringBuilder sb = new StringBuilder();
        String dump = ChannelText.dump(obj);
        sb.append(dump);
        FitsChan fc = new FitsChan();
        fc.setC("Encoding", "NATIVE");
        fc.write(obj);
        sb.append("-- fits\n");
        fc.clear("Card");
        String card;
        while ((card = fc.findFits("%f", true)) != null) {
            assertEquals(80, card.length(), card);
            sb.append(ProbeOutput.trimLines(card)).append('\n');
        }
        fc.clear("Card");
        AstObject back = fc.read();
        sb.append("-- read back from fits\n").append(ChannelText.dump(back));
        assertEquals(probe(name), sb.toString(), name);
    }

    @Test
    void attributesAndDumps() {
        LutMap m = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 5.0, 2.0);
        assertEquals(probe("attrs_default"), attrs(m));
        assertDumps("dump_default", m);
        m.set("LutInterp=1");
        assertEquals(probe("attrs_interp1"), attrs(m));
        assertDumps("dump_interp1", m);
        m.set("LutEpsilon=1.0E-7");
        assertEquals(probe("attrs_both"), attrs(m));
        assertDumps("dump_both", m);
        m.clear("LutInterp");
        m.clear("LutEpsilon");
        assertEquals(probe("attrs_cleared"), attrs(m));
        assertDumps("dump_cleared", m);
        m.set("LutInterp=5");
        assertEquals(probe("attrs_interp5"), attrs(m));
        m.set("LutInterp=0");
        m.set("LutEpsilon=0.25");
        assertEquals(probe("attrs_interp0_eps"), attrs(m));
        assertDumps("dump_interp0_eps", m);
    }

    @Test
    void dumpsOfBadEntriesInversionAndACmpMap() {
        assertDumps("dump_bad_first", new LutMap(new double[] {BAD, 1.0, 2.0, 4.0}, 0.0, 1.0));
        LutMap n = new LutMap(new double[] {0.0, 1.0, 0.5, 4.0}, 0.0, 1.0);
        n.invert();
        assertDumps("dump_nonmonotonic_inverted", n);
        LutMap k = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        k.set("LutInterp=1,LutEpsilon=1.0E-7");
        assertDumps("dump_in_cmpmap", new CmpMap(new ZoomMap(1, 2.0), k, true));
    }

    /** LutInterp and LutEpsilon cannot be changed on a LutMap a CmpMap holds (astMAKE_SET1). */
    @Test
    void aSharedLutMapRefusesItsAttributes() {
        LutMap k = new LutMap(new double[] {0.0, 10.0, 20.0, 30.0}, 0.0, 1.0);
        new CmpMap(new ZoomMap(1, 2.0), k, true);
        for (String setting : new String[] {"LutInterp=1", "LutEpsilon=1.0E-7"}) {
            AstException e = assertThrows(AstException.class, () -> k.set(setting));
            assertEquals(AstException.AST__IMMUT, e.getStatus(), setting);
        }
        AstException e = assertThrows(AstException.class, () -> k.clear("LutInterp"));
        assertEquals(AstException.AST__IMMUT, e.getStatus());
    }
}
