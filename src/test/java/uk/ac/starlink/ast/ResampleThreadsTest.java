package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Eight threads, each resampling and rebinning through the Mapping of its own {@code copy()} of a
 * FrameSet, get exactly the result of a single thread: resampling and rebinning share no hidden
 * state between copies. This test has no Rust counterpart.
 *
 * <p>The FrameSet's Mapping, from its base Frame to its current one, is a ZoomMap, a ShiftMap and
 * a non-linear MathMap in series. The test's own thread resamples a 200 by 200 {@code double}
 * image through the original's Mapping, then makes eight copies of the FrameSet and hands one to
 * each of eight threads, so that no object is used by two threads at once, as the design
 * requires. Each thread fetches the unsimplified Mapping of its copy and resamples the image
 * through it twenty times, linearly and with variances, then rebins it through it twenty times,
 * with a sinc kernel, variances and a weight limit; the image has more than C's 1024 pixels, so
 * each call simplifies the Mapping, and the MathMap makes the linear fits divide the image. Every
 * result must equal the single-thread result bit for bit, which each thread checks as it goes so
 * that no results are kept, and the original FrameSet's dump must be unchanged.
 */
class ResampleThreadsTest {

    private static final int NTHREAD = 8;
    private static final int REPEATS = 20;
    private static final int NX = 200;
    private static final int[] LBND = {1, 1};
    private static final int[] UBND = {NX, NX};

    private static FrameSet frameSet() {
        FrameSet fs = new FrameSet(new Frame(2));
        MathMap curve = new MathMap(
                2, 2, new String[] {"p=a", "q=b"}, new String[] {"a=p+0.0002*p*q-0.3", "b=q-0.00015*p*p+0.2"});
        Mapping series = new CmpMap(
                new CmpMap(new ZoomMap(2, 1.05), new ShiftMap(new double[] {-3.5, 2.25}), true), curve, true);
        fs.addFrame(FrameSet.AST__BASE, series, new Frame(2));
        return fs;
    }

    private static double[][] resample(FrameSet fs, double[] in, double[] var) {
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        ResampleFlags flags = new ResampleFlags();
        flags.setUseBad(true);
        double[] out = new double[NX * NX];
        double[] outVar = new double[NX * NX];
        int nbad = map.resampleD(
                2,
                LBND,
                UBND,
                in,
                var,
                Mapping.LINEAR_INTERPOLATOR,
                flags,
                0.01,
                100,
                AstObject.AST__BAD,
                2,
                LBND,
                UBND,
                LBND,
                UBND,
                out,
                outVar);
        return new double[][] {out, outVar, {nbad}};
    }

    private static double[][] rebin(FrameSet fs, double[] in, double[] var) {
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        double[] out = new double[NX * NX];
        double[] outVar = new double[NX * NX];
        map.rebinD(
                0.5,
                2,
                LBND,
                UBND,
                in,
                var,
                Mapping.Spreader.sinc(2),
                true,
                0.01,
                100,
                AstObject.AST__BAD,
                2,
                LBND,
                UBND,
                LBND,
                UBND,
                out,
                outVar);
        return new double[][] {out, outVar};
    }

    /**
     * How {@code got} differs from {@code want}, compared bit for bit as each array's {@code
     * what}, or null if it does not.
     */
    private static String difference(double[][] want, double[][] got, String[] what) {
        for (int i = 0; i < what.length; i++) {
            if (!Arrays.equals(want[i], got[i])) {
                return what[i] + " differ";
            }
        }
        return null;
    }

    // ExecutorService is not AutoCloseable before Java 19; the pool is shut down in the finally block.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void copiesGetTheSingleThreadResult() throws InterruptedException, ExecutionException {
        FrameSet fs = frameSet();
        String dump = fs.toString();
        double[] in = new double[NX * NX];
        double[] var = new double[NX * NX];
        for (int k = 0; k < in.length; k++) {
            in[k] = Math.sin(k * 0.001) * 100.0 + k % 37;
            var[k] = 1.0 + k % 11;
        }
        double[][] want = resample(fs, in, var);
        double[][] wantRebin = rebin(fs, in, var);

        // MODERN-JAVA(19): try-with-resources on the ExecutorService
        ExecutorService pool = Executors.newFixedThreadPool(NTHREAD);
        try {
            String[] resampled = {"output values", "output variances", "bad pixel counts"};
            String[] rebinned = {"rebinned values", "rebinned variances"};
            List<Future<String>> futures = new ArrayList<>();
            for (int t = 0; t < NTHREAD; t++) {
                FrameSet mine = (FrameSet) fs.copy();
                Callable<String> task = () -> {
                    for (int r = 0; r < REPEATS; r++) {
                        String diff = difference(want, resample(mine, in, var), resampled);
                        if (diff != null) {
                            return "resampling " + r + ": " + diff;
                        }
                    }
                    for (int r = 0; r < REPEATS; r++) {
                        String diff = difference(wantRebin, rebin(mine, in, var), rebinned);
                        if (diff != null) {
                            return "rebinning " + r + ": " + diff;
                        }
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            for (int t = 0; t < NTHREAD; t++) {
                assertNull(futures.get(t).get(), "thread " + t);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }
        assertEquals(dump, fs.toString(), "the original FrameSet's dump");
    }
}
