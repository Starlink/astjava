package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_cmpmap.rs}, with tests of what C's sharing of components
 * gives, which Rust, copying its components, has no counterpart for. Rust's {@code then} and
 * {@code alongside} are the constructor with {@code series} true and false.
 *
 * <p>{@code a_frameset_component_becomes_its_mapping} is translated in Task 16. {@code
 * series_inverse_pair_of_mathmaps_cancels_to_unitmap} and {@code
 * parallel_with_inverted_cmpmap_child_canonicalises_for_serialisation} need a MathMap, and {@code
 * cmpmap_equality_compares_components_as_applied} tests {@code astEqual}: all are outside the first
 * phase. {@code cmpmap_simplified_two_unitmaps_series} and {@code
 * cmpmap_simplified_two_unitmaps_parallel} check only how the simplified Mapping transforms, which
 * holds of a Mapping that is not restructured.
 */
class CmpMapTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void cmpmapSeriesZoomThenShift() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        assertEquals(1, cm.getNin());
        assertEquals(1, cm.getNout());
        assertEquals(20.0, transformRows(cm, true, new double[] {5.0})[0][0], 1e-12);
    }

    @Test
    void cmpmapSeriesInverse() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        assertEquals(5.0, transformRows(cm, false, new double[] {20.0})[0][0], 1e-12);
    }

    @Test
    void cmpmapParallelZoomAlongsideShift() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 3.0), new ShiftMap(new double[] {100.0}), false);
        assertEquals(2, cm.getNin());
        assertEquals(2, cm.getNout());
        double[] out = point(transformRows(cm, true, new double[] {2.0, 5.0}), 0);
        assertEquals(6.0, out[0], 1e-12);
        assertEquals(105.0, out[1], 1e-12);
    }

    @Test
    void cmpmapSeriesDimensionMismatchError() {
        AstException e =
                assertThrows(AstException.class, () -> new CmpMap(new ZoomMap(2, 2.0), new ZoomMap(3, 3.0), true));
        assertEquals(AstException.AST__INNCO, e.getStatus());
    }

    @Test
    void cmpmapSeriesBadValuePropagation() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        assertEquals(BAD, transformRows(cm, true, new double[] {BAD})[0][0]);
    }

    @Test
    void cmpmapParallelBadValuePropagation() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), false);
        assertEquals(BAD, transformRows(cm, true, new double[] {BAD, 5.0})[0][0]);
    }

    @Test
    void cmpmapSimplifiedTwoUnitmapsSeries() {
        Mapping simp = new CmpMap(new UnitMap(2), new UnitMap(2), true).simplify();
        assertArrayEquals(new double[] {3.0, 7.0}, point(transformRows(simp, true, new double[] {3.0, 7.0}), 0));
    }

    @Test
    void cmpmapSimplifiedTwoUnitmapsParallel() {
        Mapping simp = new CmpMap(new UnitMap(1), new UnitMap(2), false).simplify();
        assertEquals(3, simp.getNin());
        assertArrayEquals(
                new double[] {1.0, 2.0, 3.0}, point(transformRows(simp, true, new double[] {1.0, 2.0, 3.0}), 0));
    }

    @Test
    void dynMappingThenMethod() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {5.0}), true);
        assertEquals(11.0, transformRows(cm, true, new double[] {3.0})[0][0], 1e-12);
    }

    @Test
    void dynMappingAlongsideMethod() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 4.0), new ShiftMap(new double[] {100.0}), false);
        assertEquals(2, cm.getNin());
        assertEquals(2, cm.getNout());
        double[] out = point(transformRows(cm, true, new double[] {2.0, 50.0}), 0);
        assertEquals(8.0, out[0], 1e-12);
        assertEquals(150.0, out[1], 1e-12);
    }

    // ------------------------------------------------------------------
    // Shared components (cmpmap.c astInitCmpMap, Transform, Copy and Dump)
    // ------------------------------------------------------------------

    @Test
    void theComponentsAreSharedAndACopyCopiesThem() {
        ZoomMap z = new ZoomMap(1, 2.0);
        ShiftMap s = new ShiftMap(new double[] {1.0});
        CmpMap cm = new CmpMap(z, s, true);
        assertSame(z, cm.getMap1());
        assertSame(s, cm.getMap2());
        assertTrue(cm.isSeries());
        CmpMap copy = (CmpMap) cm.copy();
        assertNotSame(z, copy.getMap1());
        assertNotSame(s, copy.getMap2());
        assertEquals(ChannelText.dump(cm), ChannelText.dump(copy));
    }

    /**
     * A CmpMap records each component's Invert value when it is made, and applies the component
     * in that orientation whatever is done later to the Mapping it shares (cmpmap.c Transform).
     */
    @Test
    void invertingASharedComponentLeavesTheCmpMapAlone() {
        ZoomMap z1 = new ZoomMap(1, 2.0);
        ZoomMap z2 = new ZoomMap(1, 4.0);
        z2.invert();
        CmpMap cm = new CmpMap(z1, z2, true);
        assertFalse(cm.getInvert1());
        assertTrue(cm.getInvert2());
        z1.invert();
        z2.invert();
        assertFalse(cm.getInvert1());
        assertTrue(cm.getInvert2());
        assertEquals(1.5, transformRows(cm, true, new double[] {3.0})[0][0]);
        assertEquals(3.0, transformRows(cm, false, new double[] {1.5})[0][0]);
    }

    /**
     * The dump writes the recorded Invert values as InvA and InvB and each component as it now
     * is, so the two can disagree, and reading the dump back gives a CmpMap that transforms as the
     * original (cmpmap.c Dump and astLoadCmpMap).
     */
    @Test
    void theDumpRecordsBothTheCmpMapsAndTheComponentsOrientation() {
        ZoomMap z1 = new ZoomMap(1, 2.0);
        ZoomMap z2 = new ZoomMap(1, 2.0);
        z2.invert();
        CmpMap cm = new CmpMap(z1, z2, true);
        z2.invert();
        String text = ChannelText.dump(cm);
        assertTrue(text.contains("InvB = 1"), text);
        assertFalse(text.contains("Invert = 1"), text);
        CmpMap back = assertInstanceOf(CmpMap.class, ChannelText.read(text));
        assertEquals(text, ChannelText.dump(back));
        assertEquals(3.0, transformRows(back, true, new double[] {3.0})[0][0]);
        assertEquals(3.0, transformRows(cm, true, new double[] {3.0})[0][0]);
    }

    @Test
    void anInvertedCmpMapAppliesItsComponentsInReverse() {
        CmpMap cm = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        cm.invert();
        assertEquals(5.0, transformRows(cm, true, new double[] {20.0})[0][0]);
        assertEquals(20.0, transformRows(cm, false, new double[] {5.0})[0][0]);
        String text = ChannelText.dump(cm);
        assertEquals(5.0, transformRows((Mapping) ChannelText.read(text), true, new double[] {20.0})[0][0]);
    }

    /** cmpmap.c astInitCmpMap: each direction is defined when both components define it. */
    @Test
    void eachDirectionNeedsBothComponents() {
        CmpMap cm = new CmpMap(new NoInverseMap(), new ZoomMap(1, 2.0), true);
        assertTrue(cm.getTranForward());
        assertFalse(cm.getTranInverse());
        cm.invert();
        assertFalse(cm.getTranForward());
        assertTrue(cm.getTranInverse());
    }

    /** cmpmap.c GetIsLinear: linear when both components are. */
    @Test
    void isLinearNeedsBothComponents() {
        assertTrue(new CmpMap(new ZoomMap(1, 2.0), new WinMap(new double[] {1.0}, new double[] {2.0}), true)
                .getB("IsLinear"));
        assertFalse(new CmpMap(new ZoomMap(1, 2.0), new PermMap(1, null, 1, null, null), true).getB("IsLinear"));
    }

    @Test
    void aDumpWithoutAComponentIsAnError() {
        String text = ChannelText.text(
                " Begin CmpMap",
                "    Nin = 1",
                " IsA Mapping",
                "    MapA =",
                "       Begin UnitMap",
                "          Nin = 1",
                "       IsA Mapping",
                "       End UnitMap",
                " End CmpMap");
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__LDERR, e.getStatus());
    }

    /**
     * cmpmap.c {@code Decompose}: an inverted series gives its components in reverse order with
     * their Invert values reversed; an inverted parallel CmpMap reverses only the Invert values.
     */
    @Test
    void decomposeGivesComponentsAndInvertValuesLikeC() {
        boolean[] series = new boolean[1];
        boolean[] inverts = new boolean[2];

        CmpMap inSeries = new CmpMap(new ZoomMap(1, 2.0), invertedShift(), true);
        Mapping[] d = inSeries.decompose(series, inverts);
        assertTrue(series[0]);
        assertInstanceOf(ZoomMap.class, d[0]);
        assertInstanceOf(ShiftMap.class, d[1]);
        assertArrayEquals(new boolean[] {false, true}, inverts);
        inSeries.invert();
        d = inSeries.decompose(series, inverts);
        assertTrue(series[0]);
        assertInstanceOf(ShiftMap.class, d[0]);
        assertInstanceOf(ZoomMap.class, d[1]);
        assertArrayEquals(new boolean[] {false, true}, inverts);

        CmpMap inParallel = new CmpMap(new ZoomMap(1, 2.0), invertedShift(), false);
        d = inParallel.decompose(series, inverts);
        assertFalse(series[0]);
        assertInstanceOf(ZoomMap.class, d[0]);
        assertInstanceOf(ShiftMap.class, d[1]);
        assertArrayEquals(new boolean[] {false, true}, inverts);
        inParallel.invert();
        d = inParallel.decompose(series, inverts);
        assertFalse(series[0]);
        assertInstanceOf(ZoomMap.class, d[0]);
        assertInstanceOf(ShiftMap.class, d[1]);
        assertArrayEquals(new boolean[] {true, false}, inverts);
    }

    private static ShiftMap invertedShift() {
        ShiftMap s = new ShiftMap(new double[] {1.0});
        s.invert();
        return s;
    }

    /**
     * The components {@code decompose} gives are the CmpMap's own, as C's are its cloned pointers
     * (cmpmap.c {@code Decompose}), and the arrays for the flags may be null.
     */
    @Test
    void decomposeSharesTheComponents() {
        ZoomMap zm = new ZoomMap(1, 2.0);
        UnitMap um = new UnitMap(1);
        CmpMap cmp = new CmpMap(zm, um, true);
        Mapping[] d = cmp.decompose(null, null);
        assertEquals(2, d.length);
        assertSame(zm, d[0]);
        assertSame(um, d[1]);
        CmpMap copy = (CmpMap) cmp.copy();
        Mapping[] dc = copy.decompose(null, null);
        assertNotSame(zm, dc[0]);
        assertThrows(AstException.class, () -> ((ZoomMap) dc[0]).setZoom(3.0));
        AstException e = assertThrows(AstException.class, () -> cmp.decompose(new boolean[0], null));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
    }
}
