package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_zoommap.rs}.
 *
 * <p>A ZoomMap's rate is C's {@code Rate} (zoommap.c): the zoom factor, or its reciprocal when
 * inverted, where the output is the input, and zero elsewhere. {@code
 * zoom_parallel_merge_different_factors}, {@code zoom_parallel_merge_same_factor} and {@code
 * a_merged_zoom_that_underflows_to_zero_is_an_error} test simplification, and are deferred.
 */
class ZoomMapTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void zoomMapDimensions() {
        ZoomMap m = new ZoomMap(3, 2.5);
        assertEquals(3, m.getNin());
        assertEquals(3, m.getNout());
    }

    @Test
    void zoomMapZeroFactorRejected() {
        AstException e = assertThrows(AstException.class, () -> new ZoomMap(2, 0.0));
        assertEquals(AstException.AST__ZOOMI, e.getStatus());
    }

    @Test
    void zoomMapForward() {
        ZoomMap m = new ZoomMap(2, 3.0);
        double[][] out = transformRows(m, true, new double[] {1.0, 2.0}, new double[] {4.0, 5.0});
        assertArrayEquals(new double[] {3.0, 6.0}, point(out, 0));
        assertArrayEquals(new double[] {12.0, 15.0}, point(out, 1));
    }

    @Test
    void zoomMapInverse() {
        ZoomMap m = new ZoomMap(2, 4.0);
        double[][] out = transformRows(m, false, new double[] {8.0, 12.0});
        assertArrayEquals(new double[] {2.0, 3.0}, point(out, 0));
    }

    @Test
    void zoomMapBadValue() {
        ZoomMap m = new ZoomMap(2, 2.0);
        // Only the bad axis is blanked; the other is computed.
        double[][] out = transformRows(m, true, new double[] {BAD, 3.0});
        assertEquals(BAD, out[0][0]);
        assertEquals(6.0, out[1][0]);
    }

    @Test
    void zoomMapInvertedFlag() {
        ZoomMap m = new ZoomMap(2, 2.0);
        m.invert();
        double[][] out = transformRows(m, true, new double[] {4.0, 6.0});
        assertArrayEquals(new double[] {2.0, 3.0}, point(out, 0));
    }

    @Test
    void zoomMapSimplifiedIdentity() {
        ZoomMap m = new ZoomMap(2, 1.0);
        Mapping s = m.simplify();
        double[][] out = transformRows(s, true, new double[] {7.0, 8.0});
        assertArrayEquals(new double[] {7.0, 8.0}, point(out, 0));
    }

    @Test
    void zoomBadPerAxis() {
        ZoomMap zm = new ZoomMap(3, 2.0);
        // Only axis 2 is bad; axes 1 and 3 are still computed.
        double[][] out = transformRows(zm, true, new double[] {1.0, BAD, 3.0});
        assertArrayEquals(new double[] {2.0, BAD, 6.0}, point(out, 0));

        double[][] inv = transformRows(zm, false, new double[] {BAD, 4.0, 6.0});
        assertArrayEquals(new double[] {BAD, 2.0, 3.0}, point(inv, 0));
    }

    /**
     * {@code testzoommap.c}: Zoom is set by construction, reverts to 1 when cleared, and reads back
     * a new value; putting the ZoomMap in a CmpMap leaves it as it was (Errors 1-8) and makes Zoom
     * immutable (Errors 9 and 11).
     */
    @Test
    void zoomAttributeSetClearAndGet() {
        ZoomMap zm = new ZoomMap(1, -1.0);
        assertTrue(zm.test("Zoom"), "Error 1");
        assertEquals(-1.0, zm.getZoom(), "Error 2");

        zm.clear("Zoom");
        assertFalse(zm.test("Zoom"), "Error 3");
        assertEquals(1.0, zm.getZoom(), "Error 4");

        zm.setZoom(2.5);
        assertTrue(zm.test("Zoom"), "Error 5");
        assertEquals(2.5, zm.getZoom(), "Error 6");

        CmpMap cmp = new CmpMap(zm, new UnitMap(1), true);
        assertTrue(zm.test("Zoom"), "Error 7");
        assertEquals(2.5, zm.getZoom(), "Error 8");
        assertEquals(5.0, cmp.tran1(1, new double[] {2.0}, true)[0]);

        AstException e = assertThrows(AstException.class, () -> zm.setD("Zoom", 1.5), "Error 9");
        assertEquals(AstException.AST__IMMUT, e.getStatus(), "Error 9");
        e = assertThrows(AstException.class, () -> zm.clear("Zoom"), "Error 11");
        assertEquals(AstException.AST__IMMUT, e.getStatus(), "Error 11");
        assertEquals(2.5, zm.getZoom());
        assertEquals(5.0, cmp.tran1(1, new double[] {2.0}, true)[0]);
    }

    /**
     * The messages of C's {@code astMAKE_SET1} and {@code astMAKE_CLEAR1} (object.h.in:870-900,
     * 1210-1235), which join the attribute name to "attribute" without a space; a zero factor on a
     * shared ZoomMap is refused as immutable, since that check comes first.
     */
    @Test
    void aSharedZoomIsImmutableWithTheMessagesOfC() {
        ZoomMap zm = new ZoomMap(1, 2.0);
        new CmpMap(new UnitMap(1), zm, true);
        AstException e = assertThrows(AstException.class, () -> zm.setZoom(3.0));
        assertEquals(
                "astSet(ZoomMap): The Zoomattribute of the supplied ZoomMap cannot be changed because the ZoomMap "
                        + "has been cloned (programming error). (AST__IMMUT)",
                e.getMessage());
        e = assertThrows(AstException.class, () -> zm.clear("Zoom"));
        assertEquals(
                "astClear(ZoomMap): The Zoomattribute of the supplied ZoomMap cannot be cleared because the "
                        + "ZoomMap has been cloned (programming error). (AST__IMMUT)",
                e.getMessage());
        e = assertThrows(AstException.class, () -> zm.setZoom(0.0));
        assertEquals(AstException.AST__IMMUT, e.getStatus());
    }

    /** A copy of a shared ZoomMap, as C's {@code astCopy} gives, is not shared and can be changed. */
    @Test
    void aCopyOfASharedZoomMapCanBeChanged() {
        ZoomMap zm = new ZoomMap(1, 2.0);
        CmpMap cmp = new CmpMap(zm, new UnitMap(1), true);
        ZoomMap copy = (ZoomMap) zm.copy();
        copy.setZoom(3.0);
        assertEquals(3.0, copy.getZoom());
        CmpMap cmpCopy = (CmpMap) cmp.copy();
        ZoomMap component = (ZoomMap) cmpCopy.getMap1();
        component.setZoom(4.0);
        assertEquals(4.0, cmpCopy.tran1(1, new double[] {1.0}, true)[0]);
        assertEquals(2.0, cmp.tran1(1, new double[] {1.0}, true)[0]);
    }

    /**
     * A KeyMap clones the Objects it stores and those it returns (keymap.c:5002, 5251, 5348 and
     * 2353), so a ZoomMap stored in one, or read back from a copy of one, is shared.
     */
    @Test
    void aZoomMapInAKeyMapIsImmutable() {
        ZoomMap zm = new ZoomMap(1, 2.0);
        KeyMap km = new KeyMap();
        km.mapPut0A("zoom", zm, null);
        assertThrows(AstException.class, () -> zm.setZoom(3.0));

        ZoomMap inVector = new ZoomMap(1, 2.0);
        km.mapPut1A("vec", new AstObject[] {inVector}, null);
        assertThrows(AstException.class, () -> inVector.setZoom(3.0));

        KeyMap fresh = (KeyMap) km.copy();
        ZoomMap held = (ZoomMap) fresh.mapGet0A("zoom");
        assertThrows(AstException.class, () -> held.setZoom(3.0));
        ZoomMap heldInVector = (ZoomMap) fresh.mapGet1A("vec")[0];
        assertThrows(AstException.class, () -> heldInVector.setZoom(3.0));
    }

    @Test
    void zoomMapRate() {
        ZoomMap m = new ZoomMap(2, 3.0);
        assertEquals(3.0, m.rate(new double[] {1.0, 1.0}, 1, 1), 1e-4);
    }

    @Test
    void zoomRateAnalytical() {
        double[] at = {1.0, 2.0};
        ZoomMap zm = new ZoomMap(2, 3.0);
        assertEquals(3.0, zm.rate(at, 1, 1));
        assertEquals(0.0, zm.rate(at, 1, 2));
        assertEquals(3.0, zm.rate(at, 2, 2));

        ZoomMap zmInv = new ZoomMap(2, 3.0);
        zmInv.invert();
        assertEquals(1.0 / 3.0, zmInv.rate(at, 1, 1), 1e-15);
        assertEquals(0.0, zmInv.rate(at, 1, 2));
    }

    /** {@code decompose} of a Mapping that is not compound gives it, which C clones (mapping.c Decompose). */
    @Test
    void aDecomposedZoomMapIsShared() {
        ZoomMap zm = new ZoomMap(1, 2.0);
        zm.invert();
        boolean[] series = new boolean[1];
        boolean[] inverts = new boolean[2];
        Mapping[] parts = zm.decompose(series, inverts);
        assertEquals(1, parts.length);
        assertSame(zm, parts[0]);
        assertTrue(series[0]);
        assertTrue(inverts[0]);
        assertFalse(inverts[1]);
        assertThrows(AstException.class, () -> zm.setZoom(3.0));
    }
}
