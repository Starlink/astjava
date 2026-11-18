package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_resample.rs}, after C's {@code
 * ast_tester/testresample.c}: {@code resample} on known data. Here are test 1 for the
 * nearest-neighbour and linear schemes, and tests 2, 3 and 5 to 9; every call uses a {@code tol}
 * of zero and a {@code maxpix} of 100, and resamples into the whole output grid.
 */
class ResampleTest {

    private static final double BAD = AstObject.AST__BAD;

    private static ResampleFlags flags(boolean useBad, boolean noBad) {
        ResampleFlags f = new ResampleFlags();
        f.setUseBad(useBad);
        f.setNoBad(noBad);
        return f;
    }

    private static double[] range(int from, int to) {
        double[] v = new double[to - from + 1];
        for (int i = 0; i < v.length; i++) {
            v[i] = from + i;
        }
        return v;
    }

    /** The data of tests 2 and 3: pixel (x, y) of a 20 by 20 grid holds x + y. */
    private static double[] sumGrid() {
        double[] in = new double[400];
        for (int k = 0; k < 400; k++) {
            in[k] = k % 20 + 1 + k / 20 + 1;
        }
        return in;
    }

    @Test
    void test1UnitMapIsTheIdentity() {
        double[] in = range(1, 30);
        for (Mapping.Interpolator interp :
                new Mapping.Interpolator[] {Mapping.NEAREST_INTERPOLATOR, Mapping.LINEAR_INTERPOLATOR}) {
            double[] out = new double[30];
            new UnitMap(1)
                    .resampleD(
                            1,
                            new int[] {1},
                            new int[] {30},
                            in,
                            null,
                            interp,
                            flags(false, false),
                            0.0,
                            100,
                            BAD,
                            1,
                            new int[] {1},
                            new int[] {30},
                            new int[] {1},
                            new int[] {30},
                            out,
                            null);
            for (int i = 3; i < 27; i++) {
                assertTrue(Math.abs(out[i] - in[i]) <= 1.0e-10, "scheme " + interp.scheme + " pixel " + i);
            }
        }
    }

    @Test
    void test2IntegerShiftNearest() {
        double[] out = new double[19 * 19];
        new ShiftMap(new double[] {1.0, 1.0})
                .resampleD(
                        2,
                        new int[] {1, 1},
                        new int[] {20, 20},
                        sumGrid(),
                        null,
                        Mapping.NEAREST_INTERPOLATOR,
                        flags(false, false),
                        0.0,
                        100,
                        BAD,
                        2,
                        new int[] {2, 2},
                        new int[] {20, 20},
                        new int[] {2, 2},
                        new int[] {20, 20},
                        out,
                        null);
        for (int iy = 0; iy < 19; iy++) {
            for (int ix = 0; ix < 19; ix++) {
                assertEquals(ix + 1 + iy + 1, out[iy * 19 + ix], "pixel " + ix + ", " + iy);
            }
        }
    }

    @Test
    void test3HalfPixelShiftLinear() {
        double[] out = new double[18 * 18];
        new ShiftMap(new double[] {0.5, 0.5})
                .resampleD(
                        2,
                        new int[] {1, 1},
                        new int[] {20, 20},
                        sumGrid(),
                        null,
                        Mapping.LINEAR_INTERPOLATOR,
                        flags(false, false),
                        0.0,
                        100,
                        BAD,
                        2,
                        new int[] {2, 2},
                        new int[] {19, 19},
                        new int[] {2, 2},
                        new int[] {19, 19},
                        out,
                        null);
        for (int iy = 0; iy < 18; iy++) {
            for (int ix = 0; ix < 18; ix++) {
                double expected = ix + 2 + iy + 2 - 1;
                assertTrue(Math.abs(out[iy * 18 + ix] - expected) <= 1.0e-10, "pixel " + ix + ", " + iy);
            }
        }
    }

    @Test
    void test5IntegerData() {
        int[] in = new int[20];
        for (int i = 0; i < 20; i++) {
            in[i] = (i + 1) * 100;
        }
        for (Mapping.Interpolator interp :
                new Mapping.Interpolator[] {Mapping.NEAREST_INTERPOLATOR, Mapping.LINEAR_INTERPOLATOR}) {
            int[] out = new int[20];
            new UnitMap(1)
                    .resampleI(
                            1,
                            new int[] {1},
                            new int[] {20},
                            in,
                            null,
                            interp,
                            flags(false, false),
                            0.0,
                            100,
                            Integer.MIN_VALUE,
                            1,
                            new int[] {1},
                            new int[] {20},
                            new int[] {1},
                            new int[] {20},
                            out,
                            null);
            assertArrayEquals(in, out, "scheme " + interp.scheme);
        }
        int[] out = new int[19];
        new ShiftMap(new double[] {1.0})
                .resampleI(
                        1,
                        new int[] {1},
                        new int[] {20},
                        in,
                        null,
                        Mapping.NEAREST_INTERPOLATOR,
                        flags(false, false),
                        0.0,
                        100,
                        Integer.MIN_VALUE,
                        1,
                        new int[] {2},
                        new int[] {20},
                        new int[] {2},
                        new int[] {20},
                        out,
                        null);
        assertArrayEquals(Arrays.copyOf(in, 19), out);
    }

    @Test
    void test6FloatData() {
        float[] in = new float[20];
        for (int i = 0; i < 20; i++) {
            in[i] = i + 1;
        }
        for (Mapping.Interpolator interp :
                new Mapping.Interpolator[] {Mapping.NEAREST_INTERPOLATOR, Mapping.LINEAR_INTERPOLATOR}) {
            float[] out = new float[20];
            new UnitMap(1)
                    .resampleF(
                            1,
                            new int[] {1},
                            new int[] {20},
                            in,
                            null,
                            interp,
                            flags(false, false),
                            0.0,
                            100,
                            -Float.MAX_VALUE,
                            1,
                            new int[] {1},
                            new int[] {20},
                            new int[] {1},
                            new int[] {20},
                            out,
                            null);
            assertArrayEquals(in, out, "scheme " + interp.scheme);
        }
    }

    @Test
    void test7BadPixels() {
        double[] in = range(1, 20);
        in[9] = BAD;
        double[] out = new double[20];
        int nbad = new UnitMap(1)
                .resampleD(
                        1,
                        new int[] {1},
                        new int[] {20},
                        in,
                        null,
                        Mapping.NEAREST_INTERPOLATOR,
                        flags(true, false),
                        0.0,
                        100,
                        BAD,
                        1,
                        new int[] {1},
                        new int[] {20},
                        new int[] {1},
                        new int[] {20},
                        out,
                        null);
        assertEquals(1, nbad);
        assertArrayEquals(in, out);

        out = new double[18];
        nbad = new ShiftMap(new double[] {0.5})
                .resampleD(
                        1,
                        new int[] {1},
                        new int[] {20},
                        in,
                        null,
                        Mapping.LINEAR_INTERPOLATOR,
                        flags(true, false),
                        0.0,
                        100,
                        BAD,
                        1,
                        new int[] {2},
                        new int[] {19},
                        new int[] {2},
                        new int[] {19},
                        out,
                        null);
        assertTrue(nbad >= 1, "bad pixels " + nbad);
    }

    @Test
    void test8Variances() {
        double[] in = range(1, 20);
        double[] var = new double[20];
        for (int i = 0; i < 20; i++) {
            var[i] = (i + 1) * 0.1;
        }
        double[] out = new double[20];
        double[] outVar = new double[20];
        new UnitMap(1)
                .resampleD(
                        1,
                        new int[] {1},
                        new int[] {20},
                        in,
                        var,
                        Mapping.NEAREST_INTERPOLATOR,
                        flags(false, false),
                        0.0,
                        100,
                        BAD,
                        1,
                        new int[] {1},
                        new int[] {20},
                        new int[] {1},
                        new int[] {20},
                        out,
                        outVar);
        assertArrayEquals(in, out);
        assertArrayEquals(var, outVar);
    }

    @Test
    void test9NoBadLeavesBadOutputAlone() {
        double[] in = range(1, 20);
        in[9] = BAD;
        double[] out = new double[20];
        Arrays.fill(out, -999.0);
        int nbad = new UnitMap(1)
                .resampleD(
                        1,
                        new int[] {1},
                        new int[] {20},
                        in,
                        null,
                        Mapping.NEAREST_INTERPOLATOR,
                        flags(true, true),
                        0.0,
                        100,
                        BAD,
                        1,
                        new int[] {1},
                        new int[] {20},
                        new int[] {1},
                        new int[] {20},
                        out,
                        null);
        assertEquals(1, nbad);
        assertEquals(-999.0, out[9]);
        for (int i = 0; i < 20; i++) {
            if (i != 9) {
                assertEquals(in[i], out[i], "pixel " + i);
            }
        }
    }
}
