package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * The program {@code ResampleMemoryTest} runs in a child JVM whose heap holds a 2048 by 2048
 * {@code float} image and its resampled copy with little to spare: it resamples the image through
 * a non-linear MathMap with sinc interpolation and a tolerance of zero, so that every output pixel
 * is transformed through the Mapping, checks the result, prints "passed" and exits with status 0,
 * or prints the failure and exits with status 1. Resampling holds the positions of one block of
 * output pixels at a time, as C does; holding them for the whole image would need 128 MiB.
 */
final class ResampleMemoryChild {

    /** The number of pixels along each axis: the image and its copy take 32 MiB. */
    static final int SIZE = 2048;

    private ResampleMemoryChild() {}

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException(what);
        }
    }

    /**
     * Runs the resampling.
     *
     * @param args not used
     */
    // The parent process reads the result from standard output, and any failure, running out of
    // heap among them, is reported there.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidCatchingGenericException"})
    public static void main(String[] args) {
        try {
            run();
            System.out.println("passed");
        } catch (RuntimeException | OutOfMemoryError e) {
            System.out.println("failed: " + e);
            System.exit(1);
        }
    }

    private static void run() {
        // x = a + 1e-4 a^2 and y = b, with its inverse.
        Mapping map = new MathMap(
                2, 2, new String[] {"x=a+0.0001*a*a", "y=b"}, new String[] {"a=(sqrt(1+0.0004*x)-1)/0.0002", "b=y"});
        int[] lbnd = {1, 1};
        int[] ubnd = {SIZE, SIZE};
        float[] in = new float[SIZE * SIZE];
        Arrays.fill(in, 3.0f);
        float[] out = new float[SIZE * SIZE];
        int nbad = map.resampleF(
                2,
                lbnd,
                ubnd,
                in,
                null,
                Mapping.Interpolator.sinc(2),
                new ResampleFlags(),
                0.0,
                50,
                -1.0f,
                2,
                lbnd,
                ubnd,
                lbnd,
                ubnd,
                out,
                null);
        // The inverse takes every output pixel into the input grid, and the kernel keeps constant
        // data constant: the centre's value is sum(w 3) / sum(w), exactly 3 whatever the weights
        // w, but for the float roundings of the 16 terms and partial sums of each sum, at most
        // 16 * 2 * 2^-24 of sum(|w|) * 3 each, and of the quotient; with sum(|w|) / sum(w) at most
        // 2 * 2 for sinc with two pixels either side (at most 2 along each axis), that is below
        // 3 * 4 * (2 * 32 + 1) * 2^-24 = 4.7e-5.
        check(nbad == 0, "bad pixels " + nbad);
        int centre = SIZE / 2 + SIZE * (SIZE / 2);
        check(Math.abs(out[centre] - 3.0f) < 1.0e-4f, "centre " + out[centre]);
    }
}
