package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_zoommap.rs}.
 *
 * <p>{@code zoom_map_rate} and {@code zoom_rate_analytical} test {@code rate}, and are translated
 * in Task 8; Errors 7 and 8 of {@code zoom_attribute_set_clear_and_get}, which put the ZoomMap in
 * a CmpMap, are added in Task 7. {@code zoom_parallel_merge_different_factors}, {@code
 * zoom_parallel_merge_same_factor} and {@code a_merged_zoom_that_underflows_to_zero_is_an_error}
 * test simplification, and are deferred.
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
     * {@code testzoommap.c} Errors 1-6: Zoom is set by construction, reverts to 1 when cleared,
     * and reads back a new value.
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
    }
}
