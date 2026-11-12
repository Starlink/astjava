package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static uk.ac.starlink.ast.MappingTestData.fromRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_nan_protocol.rs}: the NaN protocol ({@link
 * Mapping#transformNanProtocol}) gives the results of {@link Mapping#transform}.
 *
 * <p>The tests of other classes are translated in the tasks that port them: {@code
 * nan_protocol_matrixmap}, {@code nan_protocol_matrixmap_zero_coefficient_masks_bad} and {@code
 * nan_protocol_permmap} in Task 7; {@code nan_protocol_slamap_matches_transform} and {@code
 * nan_protocol_timemap} in Task 13; {@code nan_protocol_forward_matches_transform}, {@code
 * nan_protocol_inverse_matches_transform} and {@code nan_protocol_propagates_bad_and_nan_inputs},
 * which read {@code lsst.ast}, in Task 20. The others need classes outside the first phase and are
 * deferred.
 */
class NanProtocolTest {

    private static final double BAD = AstObject.AST__BAD;

    private static boolean isInvalid(double v) {
        return v == BAD || Double.isNaN(v);
    }

    /**
     * Transforms {@code pts} both ways and asserts that each output is invalid (bad or NaN) in
     * both or within {@code tol} in both.
     */
    private static void assertNanProtocolMatchesTransform(
            Mapping map, double[][] pts, int npoint, boolean forward, double tol) {
        double[][] regular = map.transform(pts, npoint, forward);
        double[][] viaNan = map.transformNanProtocol(pts, npoint, forward);
        int nout = forward ? map.getNout() : map.getNin();
        for (int i = 0; i < npoint; i++) {
            for (int axis = 0; axis < nout; axis++) {
                double r = regular[axis][i];
                double n = viaNan[axis][i];
                if (isInvalid(r) || isInvalid(n)) {
                    assertTrue(isInvalid(r) && isInvalid(n), "pt " + i + " ax " + axis + ": r=" + r + " n=" + n);
                } else if (Math.abs(r - n) > tol) {
                    fail("pt " + i + " ax " + axis + ": regular=" + r + " nan=" + n);
                }
            }
        }
    }

    private static double[][] rows64() {
        double[][] rows = new double[64][];
        for (int i = 0; i < 64; i++) {
            rows[i] = new double[] {i * 0.1, -i * 0.2};
        }
        return rows;
    }

    @Test
    void nanProtocolZoommap() {
        ZoomMap map = new ZoomMap(2, 3.5);
        double[][] rows = rows64();
        rows[0][0] = BAD;
        rows[10][1] = Double.NaN;
        double[][] pts = fromRows(rows);
        assertNanProtocolMatchesTransform(map, pts, 64, true, 0.0);
        assertNanProtocolMatchesTransform(map, pts, 64, false, 0.0);
    }

    @Test
    void nanProtocolShiftmap() {
        ShiftMap map = new ShiftMap(new double[] {1.0, -2.0});
        double[][] rows = rows64();
        rows[5][0] = Double.NaN;
        rows[7][1] = BAD;
        double[][] pts = fromRows(rows);
        assertNanProtocolMatchesTransform(map, pts, 64, true, 0.0);
        assertNanProtocolMatchesTransform(map, pts, 64, false, 0.0);
    }
}
