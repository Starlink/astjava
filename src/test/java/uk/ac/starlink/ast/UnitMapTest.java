package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_unitmap.rs}.
 *
 * <p>{@code unit_map_rate} and {@code unit_rate_analytical} test {@code rate}, and are translated
 * in Task 8.
 */
class UnitMapTest {

    @Test
    void unitMapDimensions() {
        UnitMap m = new UnitMap(3);
        assertEquals(3, m.getNin());
        assertEquals(3, m.getNout());
        assertFalse(m.getInvert());
    }

    @Test
    void unitMapForwardTransform() {
        UnitMap m = new UnitMap(2);
        double[][] out = transformRows(m, true, new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        assertArrayEquals(new double[] {1.0, 2.0}, point(out, 0));
        assertArrayEquals(new double[] {3.0, 4.0}, point(out, 1));
    }

    @Test
    void unitMapInverseTransform() {
        UnitMap m = new UnitMap(2);
        double[][] out = transformRows(m, false, new double[] {5.0, 6.0});
        assertArrayEquals(new double[] {5.0, 6.0}, point(out, 0));
    }

    @Test
    void unitMapBadValuePassthrough() {
        UnitMap m = new UnitMap(2);
        double[][] out = transformRows(m, true, new double[] {AstObject.AST__BAD, 2.0});
        assertArrayEquals(new double[] {AstObject.AST__BAD, 2.0}, point(out, 0));
    }

    @Test
    void unitMapInvertIsNoop() {
        UnitMap m = new UnitMap(2);
        m.invert();
        assertTrue(m.getInvert());
        double[][] out = transformRows(m, true, new double[] {1.0, 2.0});
        assertArrayEquals(new double[] {1.0, 2.0}, point(out, 0));
    }

    @Test
    void unitMapSimplifiedReturnsSelf() {
        UnitMap m = new UnitMap(2);
        Mapping s = m.simplify();
        assertEquals(2, s.getNin());
        assertEquals(2, s.getNout());
    }
}
