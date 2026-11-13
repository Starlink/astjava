package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Bad and NaN input coordinates through every Mapping class of the first phase and through the
 * FrameSet read from {@code wcsconv/inputs/lsst.ast}. This test has no Rust counterpart.
 *
 * <p>For a Mapping with two inputs and two outputs, {@code tran2} transforms the three points
 * {@code (AST__BAD, 1)}, {@code (NaN, 1)} and a valid point {@code (x, 1)}, with no exception. An
 * output that depends on the first input is {@code AST__BAD} for the first two points; an output
 * that does not is, for them, exactly what the valid point gives, as all three share the second
 * input. The valid point gives exactly the result of transforming it alone. Where an instance can
 * be chosen whose outputs each depend on both inputs, it is; otherwise the bad pattern asserted is
 * the one the class's C {@code Transform} gives, cited beside the case.
 *
 * <p>C copies a NaN through a UnitMap, PermMap or Frame unchanged and lets it propagate through
 * arithmetic elsewhere; here, as the Mapping class documents, a NaN input is bad, so every output
 * C would make bad from {@code AST__BAD} is {@code AST__BAD} for a NaN too.
 *
 * <p>The Mappings with one input (TimeMap, and TimeFrame as a Mapping) are given the three values
 * {@code AST__BAD}, NaN and a valid value through {@code tran1}. SphMap has three inputs and two
 * outputs, so its inverse, with two inputs and three outputs, is given the three points through
 * {@code tranP}.
 */
class BadInputTest {

    private static final double BAD = AstObject.AST__BAD;

    /** The second input of every point. */
    private static final double Y = 1.0;

    /**
     * Transforms {@code (BAD, Y)}, {@code (NaN, Y)} and {@code (x, Y)} through {@code map} and checks
     * the outputs: those flagged in {@code dependsOnX} are bad for the first two points, the others
     * are exactly the valid point's, and the valid point's are exactly those of transforming it
     * alone.
     */
    private static void check(Mapping map, boolean forward, double x, boolean... dependsOnX) {
        int nout = forward ? map.getNout() : map.getNin();
        assertEquals(dependsOnX.length, nout, "outputs");
        double[][] in = {{BAD, Double.NaN, x}, {Y, Y, Y}};
        double[][] out = map.tranP(3, 2, in, forward, nout);
        double[][] alone = map.tranP(1, 2, new double[][] {{x}, {Y}}, forward, nout);
        String name = map.getClass().getSimpleName();
        for (int k = 0; k < nout; k++) {
            assertEquals(alone[k][0], out[k][2], name + " valid point, output " + (k + 1));
            for (int p = 0; p < 2; p++) {
                double want = dependsOnX[k] ? BAD : alone[k][0];
                assertEquals(want, out[k][p], name + " point " + (p + 1) + ", output " + (k + 1));
            }
        }
        if (nout == 2) {
            // The same through tran2.
            double[][] out2 = map.tran2(3, in[0], in[1], forward);
            for (int k = 0; k < 2; k++) {
                for (int p = 0; p < 3; p++) {
                    assertEquals(out[k][p], out2[k][p], name + " tran2 point " + (p + 1) + ", output " + (k + 1));
                }
            }
        }
    }

    /** Transforms {@code BAD}, NaN and {@code t} through the one-input {@code map} with {@code tran1}. */
    private static void check1(Mapping map, double t) {
        double[] out = map.tran1(3, new double[] {BAD, Double.NaN, t}, true);
        double[] alone = map.tran1(1, new double[] {t}, true);
        String name = map.getClass().getSimpleName();
        assertEquals(BAD, out[0], name + " bad");
        assertEquals(BAD, out[1], name + " NaN");
        assertEquals(alone[0], out[2], name + " valid value");
    }

    /** A 2x2 MatrixMap with no zero element, so each output depends on both inputs. */
    private static MatrixMap fullMatrix() {
        return new MatrixMap(2, 2, new double[][] {{1.5, 0.2}, {-0.3, 1.1}});
    }

    /** unitmap.c:892-904 copies each coordinate, so only the first output sees the first input. */
    @Test
    void unitMap() {
        check(new UnitMap(2), true, 0.5, true, false);
    }

    /** shiftmap.c:1042-1061 shifts each axis on its own. */
    @Test
    void shiftMap() {
        check(new ShiftMap(new double[] {1.5, -2.0}), true, 0.5, true, false);
    }

    /** zoommap.c:1624-1627 scales each axis on its own. */
    @Test
    void zoomMap() {
        check(new ZoomMap(2, 2.5), true, 0.5, true, false);
    }

    /** winmap.c:2557-2567 scales and shifts each axis on its own. */
    @Test
    void winMap() {
        WinMap map = new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {3.0, -1.0}, new double[] {5.0, 4.0});
        check(map, true, 0.5, true, false);
    }

    /** matrixmap.c:5205-5208: an output is bad if an input with a non-zero element is bad. */
    @Test
    void matrixMap() {
        check(fullMatrix(), true, 0.5, true, true);
    }

    /**
     * permmap.c:2004 copies the input the permutation names to each output, so with the axes
     * swapped only the second output sees the first input.
     */
    @Test
    void permMap() {
        check(new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null), true, 0.5, false, true);
    }

    /** cmpmap.c:4110-4111 applies the components in turn; the MatrixMap mixes the inputs. */
    @Test
    void cmpMap() {
        check(new CmpMap(fullMatrix(), new ShiftMap(new double[] {1.0, -1.0}), true), true, 0.5, true, true);
    }

    /** sphmap.c:1292-1300: the inverse makes all three outputs bad if either input is bad. */
    @Test
    void sphMap() {
        check(new SphMap(), false, 0.5, true, true, true);
    }

    /** wcsmap.c:2834-2837 makes both outputs bad if either input is bad. */
    @Test
    void wcsMap() {
        check(new WcsMap(2, WcsMap.AST__TAN, 1, 2), true, 0.5, true, true);
    }

    /** polymap.c:6285-6286: an output is bad if a term using a bad input is bad. */
    @Test
    void polyMap() {
        // Output 1 = x + 0.1 y, output 2 = y + 0.2 x.
        double[] coeffs = {1.0, 1, 1, 0, 0.1, 1, 0, 1, 1.0, 2, 0, 1, 0.2, 2, 1, 0};
        check(new PolyMap(2, 2, 4, coeffs, 0, null), true, 0.5, true, true);
    }

    /** slamap.c:3660-3665 makes both outputs bad if either input is bad. */
    @Test
    void slaMap() {
        SlaMap map = new SlaMap();
        map.add("EQGAL", new double[0]);
        check(map, true, 0.5, true, true);
    }

    /** timemap.c:4107-4113 leaves a bad time bad. */
    @Test
    void timeMap() {
        TimeMap map = new TimeMap();
        map.timeAdd("TAITOTT", new double[] {0.0});
        check1(map, 55000.25);
    }

    /** frame.c:11818-11821 transforms through a UnitMap, which copies each coordinate. */
    @Test
    void frame() {
        check(new Frame(2), true, 0.5, true, false);
    }

    /** skyframe.c has no Transform of its own: a SkyFrame transforms as a Frame (frame.c:11818). */
    @Test
    void skyFrame() {
        check(new SkyFrame(), true, 0.5, true, false);
    }

    /** timeframe.c has no Transform of its own: a TimeFrame transforms as a Frame (frame.c:11818). */
    @Test
    void timeFrame() {
        check1(new TimeFrame(), 55000.25);
    }

    /**
     * cmpframe.c:10015-10064 transforms through a parallel CmpMap of its two Frames, each of which
     * copies its own coordinate.
     */
    @Test
    void cmpFrame() {
        check(new CmpFrame(new Frame(1), new Frame(1)), true, 0.5, true, false);
    }

    /** frameset.c:11357 transforms through the base-to-current Mapping, here one mixing the inputs. */
    @Test
    void frameSet() {
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__BASE, fullMatrix(), new Frame(2));
        check(fs, true, 0.5, true, true);
    }

    /** The LSST FrameSet's distortion PolyMap gives each output terms in both pixel coordinates. */
    @Test
    void lsstFrameSet() {
        check(LsstOracleTest.loadLsst(), true, 100.0, true, true);
    }
}
