package uk.ac.starlink.ast;

/**
 * The program {@code CmpMapTest} runs in a child JVM whose heap holds the points given and their
 * result with little to spare: it transforms the points through eight ZoomMaps in nested series
 * CmpMaps in the way its argument names, checks the result, prints "passed" and exits with status
 * 0, or prints the failure and exits with status 1. A series CmpMap that held every point between
 * its two components would hold one full-size intermediate per level and run out of heap.
 */
final class CmpMapSeriesMemoryChild {

    /** The number of points: two axes of them take 32 MiB. */
    static final int NPOINT = 1 << 21;

    private CmpMapSeriesMemoryChild() {}

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException(what);
        }
    }

    /**
     * Runs one mode.
     *
     * @param args the mode: {@code transform}, {@code transformInto} or {@code transformIntoNan}
     */
    // The parent process reads the result from standard output, and any failure, running out of
    // heap among them, is reported there.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidCatchingGenericException"})
    public static void main(String[] args) {
        try {
            run(args[0]);
            System.out.println("passed");
        } catch (RuntimeException | OutOfMemoryError e) {
            System.out.println("failed: " + e);
            System.exit(1);
        }
    }

    /** The points (i, -i). */
    private static double[][] points() {
        double[][] pts = new double[2][NPOINT];
        for (int i = 0; i < NPOINT; i++) {
            pts[0][i] = i;
            pts[1][i] = -i;
        }
        return pts;
    }

    /** Eight ZoomMaps of factor 2 in series, nested as {@code z, (z, (z, ...))} or the other way. */
    static Mapping chain(boolean right) {
        Mapping map = new ZoomMap(2, 2.0);
        for (int i = 1; i < 8; i++) {
            map = right ? new CmpMap(new ZoomMap(2, 2.0), map, true) : new CmpMap(map, new ZoomMap(2, 2.0), true);
        }
        return map;
    }

    private static void run(String mode) {
        double[][] pts = points();
        if ("transform".equals(mode)) {
            for (boolean right : new boolean[] {true, false}) {
                Mapping map = chain(right);
                for (boolean forward : new boolean[] {true, false}) {
                    double[][] out = map.transform(pts, NPOINT, forward);
                    double want = forward ? 256.0 : 1.0 / 256.0;
                    check(out[0][1] == want, "right " + right + " forward " + forward + ": " + out[0][1]);
                }
            }
        } else if ("transformInto".equals(mode) || "transformIntoNan".equals(mode)) {
            Mapping map = chain(true);
            double[][] out = new double[2][NPOINT];
            if ("transformInto".equals(mode)) {
                map.transformInto(pts, NPOINT, true, out);
            } else {
                map.transformIntoNan(pts, NPOINT, true, out);
            }
            check(out[0][1] == 256.0, mode + ": " + out[0][1]);
        } else {
            throw new IllegalArgumentException("unknown mode " + mode);
        }
    }
}
