package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_nan_input_parity.rs}: a NaN input to a PolyMap is taken to
 * be bad, as {@code AST__BAD} is, so each gives bad outputs, through the forward polynomial and
 * through the iterative inverse. Rust also checks that its hardware-SIMD kernel agrees; Java has
 * only the scalar path, whose results are checked here. The PolyMap and points are those of {@link
 * PolyMapSimdTest}.
 */
class NanInputParityTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final int[] FLAGGED = {0, 5, 100, 199};

    /** Sets a mix of bad and NaN values in the points. */
    private static double[][] sprinkle(double[][] pts) {
        double[][] out = {pts[0].clone(), pts[1].clone()};
        out[0][0] = BAD;
        out[1][5] = Double.NaN;
        out[0][100] = Double.NaN;
        out[1][100] = BAD;
        out[1][199] = Double.NaN;
        return out;
    }

    @Test
    void scalarAndSimdHandleNanInputsIdenticallyForward() {
        PolyMap map = PolyMapSimdTest.loadLsstPolymap();
        double[][] out = map.transform(sprinkle(PolyMapSimdTest.makePoints(200)), 200, true);
        for (int i : FLAGGED) {
            assertEquals(BAD, out[0][i], "pt " + i + " ax 0");
            assertEquals(BAD, out[1][i], "pt " + i + " ax 1");
        }
    }

    @Test
    void scalarAndSimdHandleNanInputsIdenticallyInverse() {
        PolyMap map = PolyMapSimdTest.loadLsstPolymap();
        double[][] sky = map.transform(PolyMapSimdTest.makePoints(200), 200, true);
        double[][] out = map.transform(sprinkle(sky), 200, false);
        for (int i : FLAGGED) {
            assertEquals(BAD, out[0][i], "pt " + i + " ax 0");
            assertEquals(BAD, out[1][i], "pt " + i + " ax 1");
        }
    }
}
