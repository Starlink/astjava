package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;

/**
 * The tests of Rust {@code tests/test_sphmap.rs}: SphMap's transformations both ways, bad
 * values, attributes, inversion and native round trips. Its {@code try_merge} tests, of
 * SphMap's merging when simplified, are deferred ({@code needs simplification}).
 */
class SphMapTest {

    private static final double TOL = 1.0e-10;
    private static final double BAD = AstObject.AST__BAD;
    private static final double HALF_PI = Math.PI / 2;

    private static void assertClose(double a, double b) {
        assertTrue(Math.abs(a - b) < TOL, "expected " + a + " ~= " + b);
    }

    @Test
    void dimensionsDefault() {
        SphMap m = new SphMap();
        assertEquals(3, m.getNin());
        assertEquals(2, m.getNout());
        assertFalse(m.getInvert());
    }

    @Test
    void dimensionsInverted() {
        SphMap m = new SphMap();
        m.invert();
        assertEquals(2, m.getNin());
        assertEquals(3, m.getNout());
        assertTrue(m.getInvert());
    }

    @Test
    void attributes() {
        SphMap m = new SphMap();
        assertFalse(m.getUnitRadius());
        assertEquals(0.0, m.getPolarLong());
        m.setUnitRadius(true);
        m.setPolarLong(1.23);
        assertTrue(m.getUnitRadius());
        assertClose(m.getPolarLong(), 1.23);
    }

    @Test
    void forwardAxes() {
        double[][] out = transformRows(
                new SphMap(),
                true,
                new double[] {1, 0, 0},
                new double[] {0, 1, 0},
                new double[] {-1, 0, 0},
                new double[] {0, -1, 0});
        assertClose(point(out, 0)[0], 0.0);
        assertClose(point(out, 0)[1], 0.0);
        assertClose(point(out, 1)[0], HALF_PI);
        assertClose(point(out, 1)[1], 0.0);
        assertClose(point(out, 2)[0], Math.PI);
        assertClose(point(out, 2)[1], 0.0);
        assertClose(point(out, 3)[0], -HALF_PI);
        assertClose(point(out, 3)[1], 0.0);
    }

    @Test
    void forwardPoles() {
        double[][] out = transformRows(new SphMap(), true, new double[] {0, 0, 1}, new double[] {0, 0, -1});
        assertClose(point(out, 0)[0], 0.0);
        assertClose(point(out, 0)[1], HALF_PI);
        assertClose(point(out, 1)[0], 0.0);
        assertClose(point(out, 1)[1], -HALF_PI);
    }

    @Test
    void forwardPoleCustomLongitude() {
        SphMap m = new SphMap();
        m.setPolarLong(2.5);
        double[][] out = transformRows(m, true, new double[] {0, 0, 1});
        assertClose(point(out, 0)[0], 2.5);
        assertClose(point(out, 0)[1], HALF_PI);
    }

    @Test
    void forwardNonUnitVector() {
        double[][] out = transformRows(new SphMap(), true, new double[] {2, 0, 0});
        assertClose(point(out, 0)[0], 0.0);
        assertClose(point(out, 0)[1], 0.0);
    }

    @Test
    void inverseUnitVectors() {
        double[][] out = transformRows(
                new SphMap(),
                false,
                new double[] {0, 0},
                new double[] {HALF_PI, 0},
                new double[] {0, HALF_PI},
                new double[] {0, -HALF_PI});
        double[][] want = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int p = 0; p < 4; p++) {
            for (int c = 0; c < 3; c++) {
                assertClose(point(out, p)[c], want[p][c]);
            }
        }
    }

    @Test
    void roundTripForwardThenInverse() {
        SphMap m = new SphMap();
        double[] v = {0.6, 0.8, 0.0};
        double[][] sph = transformRows(m, true, v);
        double[][] cart = m.transform(sph, 1, false);
        for (int c = 0; c < 3; c++) {
            assertClose(cart[c][0], v[c]);
        }
    }

    @Test
    void roundTripInverseThenForward() {
        SphMap m = new SphMap();
        double[][] cart = transformRows(m, false, new double[] {1.234, 0.567});
        double[][] sph = m.transform(cart, 1, true);
        assertClose(sph[0][0], 1.234);
        assertClose(sph[1][0], 0.567);
    }

    @Test
    void badForward() {
        double[][] out = transformRows(
                new SphMap(), true, new double[] {BAD, 1, 0}, new double[] {1, BAD, 0}, new double[] {1, 0, BAD});
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(new double[] {BAD, BAD}, point(out, i));
        }
    }

    @Test
    void badInverse() {
        double[][] out = transformRows(new SphMap(), false, new double[] {BAD, 0.5}, new double[] {0.5, BAD});
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(new double[] {BAD, BAD, BAD}, point(out, i));
        }
    }

    @Test
    void invertedForwardIsSphericalToCartesian() {
        SphMap m = new SphMap();
        m.invert();
        double[][] out = transformRows(m, true, new double[] {0, 0});
        assertClose(point(out, 0)[0], 1.0);
        assertClose(point(out, 0)[1], 0.0);
        assertClose(point(out, 0)[2], 0.0);
    }

    @Test
    void invertedInverseIsCartesianToSpherical() {
        SphMap m = new SphMap();
        m.invert();
        double[][] out = transformRows(m, false, new double[] {1, 0, 0});
        assertClose(point(out, 0)[0], 0.0);
        assertClose(point(out, 0)[1], 0.0);
    }

    @Test
    void serialiseDefault() {
        String s = ChannelText.dump(new SphMap());
        assertTrue(s.contains("SphMap"));
        Mapping m2 = (Mapping) ChannelText.read(s);
        assertEquals(3, m2.getNin());
        assertEquals(2, m2.getNout());
        assertFalse(m2.getInvert());
    }

    @Test
    void serialiseWithAttributes() {
        SphMap m = new SphMap();
        m.setUnitRadius(true);
        m.setPolarLong(1.5);
        String s = ChannelText.dump(m);
        assertTrue(s.contains("UntRd"));
        assertTrue(s.contains("PlrLg"));
        SphMap m2 = (SphMap) ChannelText.read(s);
        assertTrue(m2.getUnitRadius());
        assertClose(m2.getPolarLong(), 1.5);
    }

    @Test
    void serialiseInverted() {
        SphMap m = new SphMap();
        m.invert();
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        assertTrue(m2.getInvert());
        assertEquals(2, m2.getNin());
        assertEquals(3, m2.getNout());
    }

    @Test
    void serialiseTransformEquivalence() {
        SphMap m = new SphMap();
        m.setPolarLong(0.7);
        double[] v = {0.3, 0.5, 0.8};
        double[][] out1 = transformRows(m, true, v);
        Mapping m2 = (Mapping) ChannelText.read(ChannelText.dump(m));
        double[][] out2 = transformRows(m2, true, v);
        assertClose(out1[0][0], out2[0][0]);
        assertClose(out1[1][0], out2[1][0]);
    }

    /** {@code n} deterministic points with {@code ncoord} coordinates in roughly [-2, 2]. */
    private static double[][] points(int n, int ncoord) {
        long s = 0x2545f4914f6cdd1dL;
        double[][] rows = new double[n][ncoord];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < ncoord; c++) {
                s = s * 6364136223846793005L + 1;
                rows[i][c] = ((s >>> 11) * 0x1.0p-53) * 4.0 - 2.0;
            }
        }
        return rows;
    }

    private static long[] bits(double[] v) {
        long[] b = new long[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = Double.doubleToRawLongBits(v[i]);
        }
        return b;
    }

    @Test
    void bulkAndSinglePointGiveIdenticalBits() {
        SphMap m = new SphMap();
        for (int[] fc : new int[][] {{1, 3}, {0, 2}}) {
            boolean forward = fc[0] == 1;
            for (int n : new int[] {1, 2, 3, 7, 1001}) {
                double[][] rows = points(n, fc[1]);
                double[][] all = transformRows(m, forward, rows);
                for (int i = 0; i < n; i++) {
                    double[][] alone = transformRows(m, forward, rows[i]);
                    assertArrayEquals(
                            bits(point(alone, 0)), bits(point(all, i)), "forward=" + forward + " n=" + n + " i=" + i);
                }
            }
        }
    }

    @Test
    void minusXMeridianWithNegativeZeroYGivesPlusPi() {
        double[][] rows = points(7, 3);
        rows[3] = new double[] {-1.0, -0.0, 0.0};
        double[][] out = transformRows(new SphMap(), true, rows);
        assertEquals(Double.doubleToRawLongBits(Math.PI), Double.doubleToRawLongBits(out[0][3]));
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(out[1][3]));
    }

    @Test
    void oneBadCoordinateMakesOnlyThatPointBad() {
        SphMap m = new SphMap();
        double[][] rows = points(7, 3);
        rows[3][1] = BAD;
        double[][] out = transformRows(m, true, rows);
        assertArrayEquals(new double[] {BAD, BAD}, point(out, 3));
        for (int i = 0; i < 7; i++) {
            if (i != 3) {
                assertArrayEquals(bits(point(transformRows(m, true, rows[i]), 0)), bits(point(out, i)), "i=" + i);
            }
        }
    }

    @Test
    void polesInABatchUsePolarLong() {
        SphMap m = new SphMap();
        m.setPolarLong(0.5);
        double[][] rows = points(5, 3);
        rows[1] = new double[] {0.0, 0.0, 1.0};
        rows[2] = new double[] {0.0, 0.0, -1.0};
        rows[3] = new double[] {1e-20, 1e-20, 1.0};
        double[][] out = transformRows(m, true, rows);
        assertArrayEquals(new double[] {0.5, HALF_PI}, point(out, 1));
        assertArrayEquals(new double[] {0.5, -HALF_PI}, point(out, 2));
        assertArrayEquals(new double[] {0.5, HALF_PI}, point(out, 3));
    }

    @Test
    void nanProtocolGivesBadForNanAndZeroVectorGivesZeros() {
        SphMap m = new SphMap();
        double[][] in = MappingTestData.fromRows(new double[] {Double.NaN, 0.0, 1.0}, new double[] {1.0, 0.0, 0.0});
        double[][] out = m.transformNanProtocol(in, 2, true);
        assertArrayEquals(new double[] {BAD, BAD}, point(out, 0));
        assertArrayEquals(new double[] {0.0, 0.0}, point(out, 1));
        double[][] zero = transformRows(m, true, new double[] {0, 0, 0}, new double[] {1, 0, 0});
        assertArrayEquals(bits(new double[] {0.0, 0.0}), bits(point(zero, 0)));
    }
}
