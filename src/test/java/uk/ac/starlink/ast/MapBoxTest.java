package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests in Rust {@code src/mappings/map_box.rs}: the bounds of each output
 * of linear Mappings, whose exact values the search finds to within the Rust tests' tolerance.
 */
class MapBoxTest {

    private static void assertBounds(Mapping m, double[] lbnd, double[] ubnd, int coord, double lo, double hi) {
        double[] r = m.mapBox(lbnd, ubnd, true, coord, null, null);
        assertEquals(lo, r[0], 1e-9, "lower bound of output " + coord);
        assertEquals(hi, r[1], 1e-9, "upper bound of output " + coord);
    }

    @Test
    void unitMapBox() {
        UnitMap m = new UnitMap(2);
        assertBounds(m, new double[] {1.0, 2.0}, new double[] {10.0, 20.0}, 1, 1.0, 10.0);
        assertBounds(m, new double[] {1.0, 2.0}, new double[] {10.0, 20.0}, 2, 2.0, 20.0);
    }

    @Test
    void zoomMapBox() {
        ZoomMap m = new ZoomMap(2, 3.0);
        assertBounds(m, new double[] {1.0, -2.0}, new double[] {4.0, 5.0}, 1, 3.0, 12.0);
        assertBounds(m, new double[] {1.0, -2.0}, new double[] {4.0, 5.0}, 2, -6.0, 15.0);
    }

    @Test
    void shiftMapBox() {
        ShiftMap m = new ShiftMap(new double[] {10.0, -5.0});
        assertBounds(m, new double[] {0.0, 0.0}, new double[] {1.0, 2.0}, 1, 10.0, 11.0);
        assertBounds(m, new double[] {0.0, 0.0}, new double[] {1.0, 2.0}, 2, -5.0, -3.0);
    }

    @Test
    void winMapBox() {
        WinMap m = new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {10.0, 10.0}, new double[] {100.0, 200.0}, new double[] {
                    110.0, 300.0
                });
        assertBounds(m, new double[] {0.0, 0.0}, new double[] {10.0, 10.0}, 1, 100.0, 110.0);
        assertBounds(m, new double[] {0.0, 0.0}, new double[] {10.0, 10.0}, 2, 200.0, 300.0);
    }

    @Test
    void swappedBounds() {
        ZoomMap m = new ZoomMap(1, 2.0);
        assertBounds(m, new double[] {5.0}, new double[] {1.0}, 1, 2.0, 10.0);
    }

    /**
     * The positions where the bounds are reached, the inverse transformation, and the error when
     * every position is bad (mapping.c {@code MapBox}, {@code SpecialBounds} and {@code
     * GlobalBounds}).
     */
    @Test
    void positionsInverseAndNoBounds() {
        ZoomMap m = new ZoomMap(2, -2.0);
        double[] xl = new double[2];
        double[] xu = new double[2];
        double[] r = m.mapBox(new double[] {1.0, 1.0}, new double[] {3.0, 4.0}, true, 2, xl, xu);
        assertEquals(-8.0, r[0], 1e-9);
        assertEquals(-2.0, r[1], 1e-9);
        assertEquals(4.0, xl[1], 1e-9);
        assertEquals(1.0, xu[1], 1e-9);
        r = m.mapBox(new double[] {2.0, 2.0}, new double[] {4.0, 4.0}, false, 1, null, null);
        assertEquals(-2.0, r[0], 1e-9);
        assertEquals(-1.0, r[1], 1e-9);
        PermMap bad = new PermMap(1, new int[] {1}, 1, new int[] {0}, null);
        AstException e = assertThrows(
                AstException.class, () -> bad.mapBox(new double[] {0.0}, new double[] {1.0}, true, 1, null, null));
        assertEquals(AstException.AST__MBBNF, e.getStatus());
    }
}
