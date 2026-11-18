package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_resample.rs}, after C's {@code
 * ast_tester/testresample.c}: {@code resample} on known data, tests 1 to 9; every call uses a
 * {@code tol} of zero and a {@code maxpix} of 100, and resamples into the whole output grid. The
 * kernel schemes are given Rust's parameters, 2 and 2: two pixels either side, and a width or full
 * width at half maximum of two pixels. {@code user_flags_reach_the_callers_interpolator_and_kernel}
 * is listed in {@code deferred/rust-tests.txt}: jniast's ResampleFlags has no user flags.
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
            // Rust's 1e-10 bounds an exact result: no java.lang.Math function is called, and at pixel
            // centres nearest copies the pixel and linear weighs it by exactly 1 and its neighbour by 0.
            for (int i = 3; i < 27; i++) {
                assertTrue(Math.abs(out[i] - in[i]) <= 1.0e-10, "scheme " + interp.scheme + " pixel " + i);
            }
        }
    }

    /**
     * The kernel schemes with Rust's parameters, 2 and 2. jniast has no factory for {@code
     * AST__GAUSS}, so its Interpolator is made directly.
     */
    private static final Mapping.Interpolator SINC = Mapping.Interpolator.sinc(2);

    private static final Mapping.Interpolator SINCSINC = Mapping.Interpolator.sincSinc(2, 2.0);
    private static final Mapping.Interpolator SINCCOS = Mapping.Interpolator.sincCos(2, 2.0);
    private static final Mapping.Interpolator SINCGAUSS = Mapping.Interpolator.sincGauss(2, 2.0);
    private static final Mapping.Interpolator GAUSS =
            new Mapping.Interpolator(Mapping.AST__GAUSS, null, null, 2.0, 2.0);
    private static final Mapping.Interpolator SOMB = Mapping.Interpolator.somb(2);
    private static final Mapping.Interpolator SOMBCOS = Mapping.Interpolator.sombCos(2, 2.0);
    private static final Mapping.Interpolator BLOCKAVE = Mapping.Interpolator.blockAve(2);

    /** Resamples {@code in}, 30 pixels, through a 1-D UnitMap with {@code interp}. */
    private static double[] unit30(double[] in, Mapping.Interpolator interp) {
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
        return out;
    }

    @Test
    void test1KernelsAreTheIdentityOnAUnitMap() {
        double[] in = range(1, 30);
        for (Mapping.Interpolator interp : new Mapping.Interpolator[] {SINC, SINCSINC, SINCCOS, SINCGAUSS, SOMBCOS}) {
            double[] out = unit30(in, interp);
            // Rust's 1e-10: out is sum(w v) / sum(w) over offsets -1 to 2, where offset 0 weighs 1,
            // SOMBCOS weighs 0.064 at both -1 and 1 (from |offset|) and the others under 4e-17, so
            // 1 <= sum(w) <= 1.13; with |v - out| <= 2 on these linear data, a last-bit change in each
            // kernel value (2^-51 relative, at most two Math calls) moves out by at most 4 * 2^-51 * 2,
            // about 4e-15, and rounding by at most (2 * 4 + 1) * 2^-53 * 1.13 * 30, about 4e-14.
            for (int i = 3; i < 27; i++) {
                assertTrue(Math.abs(out[i] - in[i]) <= 1.0e-10, "scheme " + interp.scheme + " pixel " + i);
            }
        }
    }

    /** The smoothing schemes reproduce constant data. */
    @Test
    void test1SmoothingSchemesKeepConstantData() {
        double[] in = new double[30];
        Arrays.fill(in, 42.0);
        for (Mapping.Interpolator interp : new Mapping.Interpolator[] {GAUSS, SOMB, BLOCKAVE}) {
            double[] out = unit30(in, interp);
            // Rust's 1e-10: out is sum(w c) / sum(w), in which the kernel values cancel, so a last-bit
            // change in them changes nothing beyond rounding; over 4 pixels with sum|w| / |sum w| at
            // most 1.11 (SOMB), rounding leaves out within (2 * 4 + 1) * 2^-53 * 42 * 1.11, about 5e-14.
            for (int i = 3; i < 27; i++) {
                assertTrue(Math.abs(out[i] - 42.0) <= 1.0e-10, "scheme " + interp.scheme + " pixel " + i);
            }
        }
    }

    @Test
    void test4KernelsKeepConstantData() {
        double[] in = new double[400];
        Arrays.fill(in, 42.0);
        for (Mapping.Interpolator interp :
                new Mapping.Interpolator[] {SINC, SINCSINC, SINCCOS, SINCGAUSS, GAUSS, SOMB, SOMBCOS, BLOCKAVE}) {
            double[] out = new double[16 * 16];
            new ShiftMap(new double[] {0.5, 0.5})
                    .resampleD(
                            2,
                            new int[] {1, 1},
                            new int[] {20, 20},
                            in,
                            null,
                            interp,
                            flags(false, false),
                            0.0,
                            100,
                            BAD,
                            2,
                            new int[] {3, 3},
                            new int[] {18, 18},
                            new int[] {3, 3},
                            new int[] {18, 18},
                            out,
                            null);
            // Rust's 1e-8: out is sum(w c) / sum(w), in which the kernel values cancel, so a last-bit
            // change in them changes nothing beyond rounding; over 16 pixels with sum|w| / |sum w| at
            // most 4 (SINC at offsets of 0.5 and 1.5 on both axes), rounding leaves out within
            // (2 * 16 + 1) * 2^-53 * 42 * 4, about 6e-13.
            for (int i = 0; i < out.length; i++) {
                assertTrue(Math.abs(out[i] - 42.0) <= 1.0e-8, "scheme " + interp.scheme + " pixel " + i);
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
                // Rust's 1e-10 bounds an exact result: no java.lang.Math function is called, and each
                // output is a mean of four small integers with weights of exactly 0.5 along each axis.
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
