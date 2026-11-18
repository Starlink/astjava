package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Eight threads resampling at once through one Mapping each get the result a single thread gets,
 * and the Mapping is unchanged. This test has no Rust counterpart.
 *
 * <p>The Mapping is a FrameSet's, from its base Frame to its current one, through a ZoomMap, a
 * ShiftMap and a non-linear MathMap in series, simplified. A 200 by 200 {@code double} image has
 * more than C's 1024 output pixels, so resampling simplifies the Mapping, which, being simplified
 * already, gives the Mapping itself: every thread works on the one object. The MathMap makes the
 * linear fits divide the output, so the inverse is fitted many times. Each thread resamples the
 * image ten times, linearly and with variances, and every result must equal, bit for bit, the
 * result of resampling in the test's own thread first. Inverting the Mapping in place around each
 * fit, as C does, makes this test fail.
 */
class ResampleThreadsTest {

    private static final int NTHREAD = 8;
    private static final int REPEATS = 10;
    private static final int NX = 200;
    private static final int[] LBND = {1, 1};
    private static final int[] UBND = {NX, NX};

    private static Mapping mapping() {
        FrameSet fs = new FrameSet(new Frame(2));
        MathMap curve = new MathMap(
                2, 2, new String[] {"p=a", "q=b"}, new String[] {"a=p+0.0002*p*q-0.3", "b=q-0.00015*p*p+0.2"});
        Mapping series = new CmpMap(
                new CmpMap(new ZoomMap(2, 1.05), new ShiftMap(new double[] {-3.5, 2.25}), true), curve, true);
        fs.addFrame(FrameSet.AST__BASE, series, new Frame(2));
        return fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
    }

    private static double[][] resample(Mapping map, double[] in, double[] var) {
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

    // ExecutorService is not AutoCloseable before Java 19; the pool is shut down in the finally block.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void threadsGetTheSingleThreadResult() throws InterruptedException, ExecutionException {
        Mapping map = mapping();
        String dump = map.toString();
        double[] in = new double[NX * NX];
        double[] var = new double[NX * NX];
        for (int k = 0; k < in.length; k++) {
            in[k] = Math.sin(k * 0.001) * 100.0 + k % 37;
            var[k] = 1.0 + k % 11;
        }
        double[][] want = resample(map, in, var);

        // MODERN-JAVA(19): try-with-resources on the ExecutorService
        ExecutorService pool = Executors.newFixedThreadPool(NTHREAD);
        try {
            List<Future<List<double[][]>>> futures = new ArrayList<>();
            for (int t = 0; t < NTHREAD; t++) {
                Callable<List<double[][]>> task = () -> {
                    List<double[][]> results = new ArrayList<>();
                    for (int r = 0; r < REPEATS; r++) {
                        results.add(resample(map, in, var));
                    }
                    return results;
                };
                futures.add(pool.submit(task));
            }
            for (Future<List<double[][]>> f : futures) {
                for (double[][] got : f.get()) {
                    assertArrayEquals(want[2], got[2], "bad pixels");
                    assertArrayEquals(want[0], got[0], "output values");
                    assertArrayEquals(want[1], got[1], "output variances");
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }
        assertEquals(dump, map.toString(), "the Mapping's dump");
    }
}
