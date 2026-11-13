package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Several threads, each transforming through its own {@code copy()} of a FrameSet, get exactly the
 * results of a single thread. This test has no Rust counterpart.
 *
 * <p>Eight threads each copy the FrameSet read from {@code wcsconv/inputs/lsst.ast} and an
 * FK5-to-Galactic conversion FrameSet with Epoch 1990, whose SlaMap and SkyFrame keep caches, and
 * transform the same 10,000 points through each copy 20 times. Every result must equal, bit for
 * bit, the result of transforming the points through the original in the test's own thread.
 */
class ThreadCopyTest {

    private static final int NTHREAD = 8;
    private static final int NPOINT = 10_000;
    private static final int REPEATS = 20;

    /** Pixel positions spread over the LSST detector, axis by axis. */
    private static double[][] pixels() {
        double[][] in = new double[2][NPOINT];
        for (int i = 0; i < NPOINT; i++) {
            int row = i / 100;
            in[0][i] = 1.0 + (i % 100) * 40.7;
            in[1][i] = 1.0 + row * 40.3;
        }
        return in;
    }

    /** Sky positions in radians, longitude in [0, 2 pi) and latitude in (-pi/2, pi/2), axis by axis. */
    private static double[][] skyPositions() {
        double[][] in = new double[2][NPOINT];
        for (int i = 0; i < NPOINT; i++) {
            int row = i / 100;
            in[0][i] = 2.0 * Math.PI * (i % 100) / 100.0;
            in[1][i] = Math.PI * (row + 0.5) / 100.0 - Math.PI / 2.0;
        }
        return in;
    }

    /** The FrameSet converting FK5 to Galactic coordinates at Epoch 1990. */
    private static FrameSet fk5ToGalactic() {
        SkyFrame fk5 = new SkyFrame();
        fk5.setSystem("FK5");
        fk5.setEpoch(1990.0);
        SkyFrame gal = new SkyFrame();
        gal.setSystem("Galactic");
        gal.setEpoch(1990.0);
        FrameSet cvt = fk5.convert(gal, "");
        assertNotNull(cvt, "FK5 to Galactic conversion");
        return cvt;
    }

    private static double[][] transform(Mapping map, double[][] in) {
        return map.tranP(NPOINT, 2, in, true, 2);
    }

    @Test
    // ExecutorService is not AutoCloseable before Java 19; the pool is shut down in the finally block.
    @SuppressWarnings("PMD.CloseResource")
    void copiesTransformAsOneThreadDoes() throws InterruptedException, ExecutionException {
        FrameSet lsst = LsstOracleTest.loadLsst();
        FrameSet sky = fk5ToGalactic();
        double[][] pix = pixels();
        double[][] pos = skyPositions();
        double[][] lsstWant = transform(lsst, pix);
        double[][] skyWant = transform(sky, pos);

        // MODERN-JAVA(19): try-with-resources on the ExecutorService
        ExecutorService pool = Executors.newFixedThreadPool(NTHREAD);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < NTHREAD; t++) {
                futures.add(pool.submit(() -> {
                    FrameSet myLsst = (FrameSet) lsst.copy();
                    FrameSet mySky = (FrameSet) sky.copy();
                    for (int r = 0; r < REPEATS; r++) {
                        double[][] a = transform(myLsst, pix);
                        double[][] b = transform(mySky, pos);
                        for (int axis = 0; axis < 2; axis++) {
                            assertArrayEquals(lsstWant[axis], a[axis], "lsst repeat " + r + " axis " + axis);
                            assertArrayEquals(skyWant[axis], b[axis], "FK5 repeat " + r + " axis " + axis);
                        }
                    }
                    return REPEATS;
                }));
            }
            for (Future<Integer> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES), "threads finished");
        }
    }
}
