package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code mappings/helpers/scale_poly_inputs.rs} (C {@code
 * ScalePolyInputs}, fitschan.c). The expected coefficients were captured from C by the Rust
 * repository's {@code tools/oracle_fixtures/scale_poly_inputs.c}; C gives them in the order
 * {@code astPolyCoeffs} returns them, which is the order compared here.
 */
class ScalePolyInputsTest {

    /** {@code y0 = x0 + 0.001*x0*x1}, {@code y1 = x1 + 0.002*x0^2}, with no inverse. */
    private static PolyMap sipShape() {
        return new PolyMap(2, 2, 4, new double[] {1.0, 1, 1, 0, 0.001, 1, 1, 1, 1.0, 2, 0, 1, 0.002, 2, 2, 0}, 0, null);
    }

    /** C's SCALE_POLY_INPUTS_SIP_UNIFORM fixture: scales (2, 2). */
    @Test
    void sipUniformScaling() {
        PolyMap result = ScalePolyInputs.scalePolyInputs(sipShape(), new double[] {2.0, 2.0});
        assertArrayEquals(
                new double[] {2.0, 1, 1, 0, 0.004, 1, 1, 1, 2.0, 2, 0, 1, 0.008, 2, 2, 0}, result.polyCoeffs(true));
    }

    /** C's SCALE_POLY_INPUTS_SIP_NONUNIFORM fixture: scales (3, 5). */
    @Test
    void sipNonuniformScaling() {
        PolyMap result = ScalePolyInputs.scalePolyInputs(sipShape(), new double[] {3.0, 5.0});
        assertArrayEquals(
                new double[] {3.0, 1, 1, 0, 0.015, 1, 1, 1, 5.0, 2, 0, 1, 0.018000000000000002, 2, 2, 0},
                result.polyCoeffs(true));
    }

    /**
     * C's SCALE_POLY_INPUTS_3D_MIXED fixture: scales (2, 3, 5) on a 3-input PolyMap. C's fixture
     * records only the first two powers of each term; the third is the one supplied.
     */
    @Test
    void threeDMixed() {
        PolyMap pm = new PolyMap(3, 1, 3, new double[] {2.0, 1, 1, 0, 0, 3.0, 1, 0, 2, 1, 7.0, 1, 1, 1, 1}, 0, null);
        PolyMap result = ScalePolyInputs.scalePolyInputs(pm, new double[] {2.0, 3.0, 5.0});
        assertArrayEquals(
                new double[] {4.0, 1, 1, 0, 0, 135.0, 1, 0, 2, 1, 210.0, 1, 1, 1, 1}, result.polyCoeffs(true));
    }

    /** A zero scale factor is an internal programming error in C, which reports AST__INTER. */
    @Test
    void zeroScaleIsAnInternalError() {
        PolyMap pm = new PolyMap(2, 2, 2, new double[] {1.0, 1, 1, 0, 1.0, 2, 0, 1}, 0, null);
        AstException e =
                assertThrows(AstException.class, () -> ScalePolyInputs.scalePolyInputs(pm, new double[] {0.0, 1.0}));
        assertEquals(AstException.AST__INTER, e.getStatus());
        assertTrue(e.getMessage().contains("Scale factor for input 0 is zero or bad"), e.getMessage());
    }

    /** The IterInverse, NiterInverse and TolInverse values set on the PolyMap are copied. */
    @Test
    void iterInverseAttributesArePropagated() {
        PolyMap pm = sipShape();
        pm.setIterInverse(true);
        pm.setNiterInverse(7);
        pm.setTolInverse(1.0e-9);
        PolyMap result = ScalePolyInputs.scalePolyInputs(pm, new double[] {2.0, 2.0});
        assertTrue(result.testIterInverse());
        assertTrue(result.getIterInverse());
        assertEquals(7, result.getNiterInverse());
        assertEquals(1.0e-9, result.getTolInverse());
    }

    /**
     * An inverted PolyMap is rebuilt uninverted and then inverted, and the coefficients of an
     * explicit inverse are divided by the scale of the input they give, as C's {@code
     * ScalePolyInputs} does.
     */
    @Test
    void invertedPolyMapKeepsItsOrientation() {
        PolyMap pm = new PolyMap(
                2, 2, 2, new double[] {2.0, 1, 1, 0, 3.0, 2, 0, 1}, 2, new double[] {0.5, 1, 1, 0, 1.0 / 3.0, 2, 0, 1});
        pm.invert();
        PolyMap result = ScalePolyInputs.scalePolyInputs(pm, new double[] {4.0, 8.0});
        assertTrue(result.getInvert());
        // The forward transformation of the inverted PolyMap: its original inverse, scaled by the
        // powers of the scales.
        assertArrayEquals(new double[] {0.5 * 4.0, 1, 1, 0, 1.0 / 3.0 * 8.0, 2, 0, 1}, result.polyCoeffs(true));
        // Its inverse: the original forward coefficients, times the reciprocal of the scale of the
        // output each gives.
        assertArrayEquals(
                new double[] {2.0 * (1.0 / 4.0), 1, 1, 0, 3.0 * (1.0 / 8.0), 2, 0, 1}, result.polyCoeffs(false));
    }
}
