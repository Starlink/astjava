package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_tran1_tran2.rs}: {@code tran1} and {@code tran2}, with the
 * checks of C's {@code ValidateMapping} (mapping.c) they make.
 *
 * <p>{@code tran_rejects_an_undefined_direction} uses a RateMap, which is outside the first phase; here a
 * Mapping whose inverse is undefined, as a RateMap's is, stands in for it. The Java methods take
 * the number of points as jniast's do, so the arrays may be longer than it; one shorter than it is
 * C's {@code AST__DIMIN} (mapping.c {@code TranN}), where the Rust test of arrays of different
 * lengths expects its own {@code AST__NPTIN}.
 */
class Tran1Tran2Test {

    private static final double BAD = AstObject.AST__BAD;

    // ------------------------------------------------------------------
    // tran1
    // ------------------------------------------------------------------

    @Test
    void tran1AppliesTheForwardTransform() {
        ZoomMap zoom = new ZoomMap(1, 2.5);
        assertArrayEquals(new double[] {2.5, 5.0, -10.0}, zoom.tran1(3, new double[] {1.0, 2.0, -4.0}, true));
    }

    @Test
    void tran1AppliesTheInverseTransform() {
        ZoomMap zoom = new ZoomMap(1, 2.5);
        assertArrayEquals(new double[] {1.0, 2.0}, zoom.tran1(2, new double[] {2.5, 5.0}, false));
    }

    @Test
    void tran1AgreesWithThePointsetPath() {
        ShiftMap shift = new ShiftMap(new double[] {3.25});
        double[] xin = {0.0, 1.5, -2.75, 1e9};
        double[] viaTran1 = shift.tran1(xin.length, xin, true);
        double[] viaTransform = shift.transform(new double[][] {xin}, xin.length, true)[0];
        assertArrayEquals(viaTransform, viaTran1);
    }

    @Test
    void tran1PropagatesBadValues() {
        ZoomMap zoom = new ZoomMap(1, 3.0);
        double[] out = zoom.tran1(3, new double[] {1.0, BAD, 2.0}, true);
        assertEquals(3.0, out[0]);
        assertEquals(BAD, out[1], "BAD in must stay BAD out");
        assertEquals(6.0, out[2]);
    }

    @Test
    void tran1RejectsAnEmptyInput() {
        // C's astTran1 reports AST__NPTIN for npoint < 1 when astInitPointSet builds its
        // PointSet (pointset.c astInitPointSet).
        ZoomMap zoom = new ZoomMap(1, 2.0);
        AstException e = assertThrows(AstException.class, () -> zoom.tran1(0, new double[0], true));
        assertEquals(AstException.AST__NPTIN, e.getStatus());
    }

    @Test
    void tran1RejectsAMappingOfTheWrongArity() {
        // C: ValidateMapping(this, forward, npoint, 1, 1, "astTran1") reports AST__NCPIN when the
        // Mapping does not take one coordinate and give one.
        ZoomMap zoom2d = new ZoomMap(2, 2.0);
        AstException e = assertThrows(AstException.class, () -> zoom2d.tran1(1, new double[] {1.0}, true));
        assertEquals(AstException.AST__NCPIN, e.getStatus());
        assertTrue(e.getMessage().contains("requires 2 coordinate values"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // tran2
    // ------------------------------------------------------------------

    @Test
    void tran2AppliesBothAxes() {
        ShiftMap shift = new ShiftMap(new double[] {1.0, -2.0});
        double[][] out = shift.tran2(2, new double[] {0.0, 10.0}, new double[] {0.0, 10.0}, true);
        assertArrayEquals(new double[] {1.0, 11.0}, out[0]);
        assertArrayEquals(new double[] {-2.0, 8.0}, out[1]);
    }

    @Test
    void tran2AgreesWithThePointsetPath() {
        ShiftMap shift = new ShiftMap(new double[] {0.5, 7.0});
        double[] xin = {1.0, 2.0, 3.0};
        double[] yin = {-1.0, 0.0, 1.0};
        double[][] out = shift.tran2(3, xin, yin, true);
        double[][] want = shift.transform(new double[][] {xin, yin}, 3, true);
        assertArrayEquals(want[0], out[0]);
        assertArrayEquals(want[1], out[1]);
    }

    @Test
    void tran2RespectsAxisOrder() {
        // A swap PermMap proves the two output vectors are not transposed: the x output must come
        // from the y input.
        PermMap swap = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        double[][] out = swap.tran2(2, new double[] {1.0, 2.0}, new double[] {30.0, 40.0}, true);
        assertArrayEquals(new double[] {30.0, 40.0}, out[0]);
        assertArrayEquals(new double[] {1.0, 2.0}, out[1]);
    }

    @Test
    void tran2RejectsMismatchedInputLengths() {
        UnitMap unit = new UnitMap(2);
        AstException e = assertThrows(
                AstException.class, () -> unit.tran2(2, new double[] {1.0, 2.0}, new double[] {1.0}, true));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertTrue(e.getMessage().contains("yin array holds 1 values"), e.getMessage());
    }

    @Test
    void tran2RejectsAMappingOfTheWrongArity() {
        UnitMap unit1d = new UnitMap(1);
        AstException e =
                assertThrows(AstException.class, () -> unit1d.tran2(1, new double[] {1.0}, new double[] {1.0}, true));
        assertEquals(AstException.AST__NCPIN, e.getStatus());
        assertTrue(e.getMessage().contains("requires 1 coordinate value "), e.getMessage());
    }

    // ------------------------------------------------------------------
    // Definedness
    // ------------------------------------------------------------------

    @Test
    void tranRejectsAnUndefinedDirection() {
        Mapping rate = new NoInverseMap();
        assertTrue(rate.getTranForward());
        assertFalse(rate.getTranInverse(), "the stand-in has no inverse transform");

        AstException e = assertThrows(AstException.class, () -> rate.tran1(1, new double[] {1.0}, false));
        assertEquals(AstException.AST__TRNND, e.getStatus());
        assertTrue(e.getMessage().contains("inverse"), e.getMessage());

        // The forward direction still works, so the check is for the direction asked for.
        assertArrayEquals(new double[] {2.0}, rate.tran1(1, new double[] {1.0}, true));
    }

    @Test
    void tran1AndTran2AreAvailableThroughATraitObject() {
        Mapping boxed = new ZoomMap(1, 4.0);
        assertArrayEquals(new double[] {8.0}, boxed.tran1(1, new double[] {2.0}, true));

        Mapping boxed2 = new ShiftMap(new double[] {1.0, 1.0});
        double[][] out = boxed2.tran2(1, new double[] {0.0}, new double[] {0.0}, true);
        assertArrayEquals(new double[] {1.0}, out[0]);
        assertArrayEquals(new double[] {1.0}, out[1]);
    }
}
