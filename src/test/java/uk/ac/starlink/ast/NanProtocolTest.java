package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static uk.ac.starlink.ast.MappingTestData.fromRows;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_nan_protocol.rs}: the NaN protocol ({@link
 * Mapping#transformNanProtocol}) gives the results of {@link Mapping#transform}, except for a value
 * that is not finite made from finite inputs.
 *
 * <p>{@code nan_protocol_forward_matches_transform}, {@code
 * nan_protocol_inverse_matches_transform} and {@code nan_protocol_propagates_bad_and_nan_inputs}
 * run through the simplified FrameSet read from {@code wcsconv/inputs/lsst.ast}. The tests of
 * classes outside the first phase are deferred.
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

    @Test
    void nanProtocolMatrixmap() {
        // Both rows have non-zero coefficients in both columns.
        MatrixMap map = new MatrixMap(2, 2, new double[][] {{1.5, 0.2}, {-0.3, 1.1}});
        double[][] rows = new double[64][];
        for (int i = 0; i < 64; i++) {
            rows[i] = new double[] {i * 0.1, -i * 0.2};
        }
        rows[3][0] = BAD;
        rows[8][1] = Double.NaN;
        assertNanProtocolMatchesTransform(map, fromRows(rows), 64, true, 1e-14);
    }

    @Test
    void nanProtocolMatrixmapZeroCoefficientMasksBad() {
        // Row 0 has a zero coefficient in column 1, so output 0 must not see a bad input 1.
        MatrixMap map = new MatrixMap(2, 2, new double[][] {{2.0, 0.0}, {1.0, 3.0}});
        double[][] rows = new double[16][];
        for (int i = 0; i < 16; i++) {
            rows[i] = new double[] {i, 10.0 + i};
        }
        rows[5][1] = BAD;
        rows[7][1] = Double.NaN;
        double[][] pts = fromRows(rows);
        double[][] regular = map.transform(pts, 16, true);
        double[][] viaNan = map.transformNanProtocol(pts, 16, true);
        for (int badIx : new int[] {5, 7}) {
            assertEquals(BAD, regular[1][badIx]);
            assertEquals(BAD, viaNan[1][badIx]);
            assertTrue(Double.isFinite(regular[0][badIx]) && regular[0][badIx] != BAD);
            assertTrue(Double.isFinite(viaNan[0][badIx]) && viaNan[0][badIx] != BAD);
            assertEquals(regular[0][badIx], viaNan[0][badIx], 1e-14);
        }
    }

    @Test
    void nanProtocolPermmap() {
        PermMap map = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        double[][] rows = new double[32][];
        for (int i = 0; i < 32; i++) {
            rows[i] = new double[] {i, -i};
        }
        rows[2][0] = BAD;
        rows[6][1] = Double.NaN;
        double[][] pts = fromRows(rows);
        assertNanProtocolMatchesTransform(map, pts, 32, true, 0.0);
        assertNanProtocolMatchesTransform(map, pts, 32, false, 0.0);
    }

    /**
     * An SlaMap from equatorial to galactic coordinates gives the same results through the NaN
     * protocol, bad and NaN inputs included.
     */
    @Test
    void nanProtocolSlamapMatchesTransform() {
        SlaMap map = new SlaMap();
        map.add("EQGAL", new double[0]);
        double[][] rows = new double[256][];
        for (int i = 0; i < 256; i++) {
            rows[i] = new double[] {i * 0.01, -0.5 + i * 0.001};
        }
        rows[10][0] = BAD;
        rows[42][1] = Double.NaN;
        rows[100][0] = Double.NaN;
        rows[100][1] = BAD;
        double[][] pts = fromRows(rows);
        double[][] regular = map.transform(pts, 256, true);
        double[][] viaNan = map.transformNanProtocol(pts, 256, true);
        for (int i = 0; i < 256; i++) {
            for (int axis = 0; axis < 2; axis++) {
                double r = regular[axis][i];
                double n = viaNan[axis][i];
                if (r == BAD || n == BAD) {
                    assertEquals(r, n, "pt " + i + " ax " + axis + ": BAD mismatch");
                } else {
                    assertTrue(Math.abs(r - n) < 1e-14, "pt " + i + " ax " + axis + ": regular=" + r + " nan=" + n);
                }
            }
        }
    }

    @Test
    void nanProtocolTimemap() {
        TimeMap map = new TimeMap();
        map.timeAdd("TAITOUTC", new double[] {50000.0, BAD});
        double[][] rows = new double[32][];
        for (int i = 0; i < 32; i++) {
            rows[i] = new double[] {50000.0 + i * 0.1};
        }
        rows[7][0] = BAD;
        rows[11][0] = Double.NaN;
        assertNanProtocolMatchesTransform(map, fromRows(rows), 32, true, 1e-14);
    }

    /** The simplified FrameSet read from {@code lsst.ast}. */
    private static Mapping loadLsst() {
        return LsstOracleTest.loadLsst().simplify();
    }

    /** {@code n} pixel positions (100 + i*0.01, 200 + i*0.005), one row per point. */
    private static double[][] lsstRows(int n) {
        double[][] rows = new double[n][];
        for (int i = 0; i < n; i++) {
            rows[i] = new double[] {100.0 + i * 0.01, 200.0 + i * 0.005};
        }
        return rows;
    }

    /** Asserts that each output of {@code a} is within {@code tol} of that of {@code b}. */
    private static void assertWithin(double[][] a, double[][] b, int npoint, double tol) {
        for (int i = 0; i < npoint; i++) {
            for (int axis = 0; axis < 2; axis++) {
                double diff = Math.abs(a[axis][i] - b[axis][i]);
                assertTrue(
                        diff < tol,
                        "pt " + i + " axis " + axis + ": regular=" + a[axis][i] + " nan_protocol=" + b[axis][i]
                                + " diff=" + diff);
            }
        }
    }

    @Test
    void nanProtocolForwardMatchesTransform() {
        Mapping map = loadLsst();
        double[][] pts = fromRows(lsstRows(1024));
        double[][] regular = map.transform(pts, 1024, true);
        double[][] viaNan = map.transformNanProtocol(pts, 1024, true);
        assertWithin(regular, viaNan, 1024, 1e-14);
    }

    @Test
    void nanProtocolInverseMatchesTransform() {
        Mapping map = loadLsst();
        double[][] sky = map.transform(fromRows(lsstRows(1024)), 1024, true);
        double[][] regular = map.transform(sky, 1024, false);
        double[][] viaNan = map.transformNanProtocol(sky, 1024, false);
        assertWithin(regular, viaNan, 1024, 1e-10);
    }

    @Test
    void nanProtocolPropagatesBadAndNanInputs() {
        Mapping map = loadLsst();
        double[][] rows = lsstRows(200);
        rows[0][0] = BAD;
        rows[5][1] = Double.NaN;
        rows[100][0] = Double.NaN;
        rows[100][1] = BAD;
        double[][] out = map.transformNanProtocol(fromRows(rows), 200, true);
        for (int i : new int[] {0, 5, 100}) {
            assertEquals(BAD, out[0][i], "pt " + i + " axis 0");
            assertEquals(BAD, out[1][i], "pt " + i + " axis 1");
        }
        // The neighbouring valid point stays finite.
        assertTrue(Double.isFinite(out[0][1]) && out[0][1] != BAD);
        assertTrue(Double.isFinite(out[1][1]) && out[1][1] != BAD);
    }

    @Test
    void anOverflowIsBadOnlyUnderTheNanProtocol() {
        // The pinned C library gives Infinity for this by default, and AST__BAD with
        // AST_REPLACE_NAN=1.
        ZoomMap zoom = new ZoomMap(1, 1.0e300);
        double[][] in = {{1.0e300}};
        assertEquals(Double.POSITIVE_INFINITY, zoom.transform(in, 1, true)[0][0]);
        assertEquals(Double.POSITIVE_INFINITY, zoom.tran1(1, new double[] {1.0e300}, true)[0]);
        assertEquals(BAD, zoom.transformNanProtocol(in, 1, true)[0][0]);
    }
}
