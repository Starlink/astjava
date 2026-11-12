package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_shiftmap.rs}.
 *
 * <p>A ShiftMap's rate is C's {@code Rate} (shiftmap.c): one where the output is the input, zero
 * elsewhere.
 */
class ShiftMapTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void shiftMapDimensions() {
        ShiftMap m = new ShiftMap(new double[] {1.0, 2.0, 3.0});
        assertEquals(3, m.getNin());
        assertEquals(3, m.getNout());
    }

    @Test
    void shiftMapForward() {
        ShiftMap m = new ShiftMap(new double[] {10.0, 20.0});
        double[][] out = transformRows(m, true, new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        assertArrayEquals(new double[] {11.0, 22.0}, point(out, 0));
        assertArrayEquals(new double[] {13.0, 24.0}, point(out, 1));
    }

    @Test
    void shiftMapInverse() {
        ShiftMap m = new ShiftMap(new double[] {10.0, 20.0});
        double[][] out = transformRows(m, false, new double[] {11.0, 22.0});
        assertArrayEquals(new double[] {1.0, 2.0}, point(out, 0));
    }

    @Test
    void shiftMapBadValue() {
        ShiftMap m = new ShiftMap(new double[] {1.0, 2.0});
        double[][] out = transformRows(m, true, new double[] {Double.NaN, 5.0});
        // Only the bad axis is blanked.
        assertEquals(BAD, out[0][0]);
        assertEquals(7.0, out[1][0]);
    }

    @Test
    void shiftMapSimplifiedZero() {
        ShiftMap m = new ShiftMap(new double[] {0.0, 0.0});
        Mapping s = m.simplify();
        double[][] out = transformRows(s, true, new double[] {5.0, 6.0});
        assertArrayEquals(new double[] {5.0, 6.0}, point(out, 0));
    }

    @Test
    void shiftBadPerAxis() {
        ShiftMap sm = new ShiftMap(new double[] {10.0, 20.0, 30.0});
        double[][] out = transformRows(sm, true, new double[] {1.0, BAD, 3.0});
        assertArrayEquals(new double[] {11.0, BAD, 33.0}, point(out, 0));

        double[][] inv = transformRows(sm, false, new double[] {BAD, 25.0, 33.0});
        assertArrayEquals(new double[] {BAD, 5.0, 3.0}, point(inv, 0));
    }

    @Test
    void shiftMapRate() {
        ShiftMap m = new ShiftMap(new double[] {10.0, 20.0});
        assertEquals(1.0, m.rate(new double[] {1.0, 1.0}, 1, 1), 1e-6);
    }

    @Test
    void shiftRateAnalytical() {
        ShiftMap sm = new ShiftMap(new double[] {10.0, 20.0});
        double[] at = {1.0, 2.0};
        assertEquals(1.0, sm.rate(at, 1, 1));
        assertEquals(0.0, sm.rate(at, 1, 2));
        assertEquals(1.0, sm.rate(at, 2, 2));
        assertEquals(0.0, sm.rate(at, 2, 1));
    }
}
