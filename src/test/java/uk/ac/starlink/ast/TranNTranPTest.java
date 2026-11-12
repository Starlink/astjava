package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@code tranN} and {@code tranP}, which have no Rust counterpart: jniast's layouts (all the values
 * of one coordinate, then the next, in {@code tranN}; one array per coordinate in {@code tranP})
 * and the checks of C's {@code ValidateMapping} and {@code TranN} (mapping.c).
 */
class TranNTranPTest {

    private static final double BAD = AstObject.AST__BAD;

    @Test
    void tranNHoldsEachCoordinateInTurn() {
        ShiftMap shift = new ShiftMap(new double[] {1.0, 10.0, 100.0});
        // Two points: (0, 1, 2) and (3, 4, 5).
        double[] in = {0.0, 3.0, 1.0, 4.0, 2.0, 5.0};
        assertArrayEquals(new double[] {1.0, 4.0, 11.0, 14.0, 102.0, 105.0}, shift.tranN(2, 3, in, true, 3));
        assertArrayEquals(new double[] {-1.0, 2.0, -9.0, -6.0, -98.0, -95.0}, shift.tranN(2, 3, in, false, 3));
    }

    @Test
    void tranPHoldsOneArrayPerCoordinate() {
        ZoomMap zoom = new ZoomMap(2, 2.0);
        double[][] out = zoom.tranP(2, 2, new double[][] {{1.0, BAD}, {3.0, 4.0}}, true, 2);
        assertArrayEquals(new double[] {2.0, BAD}, out[0]);
        assertArrayEquals(new double[] {6.0, 8.0}, out[1]);
    }

    @Test
    void theCoordinateCountsMustBeTheMappings() {
        ZoomMap zoom = new ZoomMap(2, 2.0);
        assertEquals(
                AstException.AST__NCPIN,
                assertThrows(AstException.class, () -> zoom.tranN(1, 3, new double[3], true, 2))
                        .getStatus());
        assertEquals(
                AstException.AST__NCPIN,
                assertThrows(AstException.class, () -> zoom.tranP(1, 2, new double[2][1], true, 1))
                        .getStatus());
    }

    @Test
    void shortArraysAreDimin() {
        ZoomMap zoom = new ZoomMap(2, 2.0);
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> zoom.tranN(2, 2, new double[3], true, 2))
                        .getStatus());
        assertEquals(
                AstException.AST__DIMIN,
                assertThrows(AstException.class, () -> zoom.tranP(2, 2, new double[][] {{1, 2}, {3}}, true, 2))
                        .getStatus());
        assertEquals(
                AstException.AST__NCPIN,
                assertThrows(AstException.class, () -> zoom.tranP(1, 2, new double[][] {{1}}, true, 2))
                        .getStatus());
    }
}
