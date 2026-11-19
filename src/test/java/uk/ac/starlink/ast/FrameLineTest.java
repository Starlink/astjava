package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The line geometry the Region classes use ({@code lineDef}, {@code lineOffset}, {@code
 * lineCrossing}, {@code lineContains}) gives C's results, from {@code
 * probes/frame_line_probe.c}: straight lines in a 2-D Frame, great circles on a SkyFrame (across
 * RA 0, over and near a pole, of zero length and of half a turn), on a SkyFrame whose axes are
 * swapped and through a FrameSet, and C's errors.
 *
 * <p>A plain Frame's lines use only arithmetic and a square root, which give the same bits on
 * every platform, so its values are compared exactly. A SkyFrame's go through {@code sin}, {@code
 * cos}, {@code acos} and {@code atan2}, each within 1 ulp of the true value in {@code
 * java.lang.Math} and in the C library, so the two may differ by 2 ulp in each: about {@code
 * 5e-16} in a unit-vector component. The cross and dot products of such vectors add a few
 * roundings, and the pole of a line is normalised from a cross product whose modulus is the sine
 * of the angle between its two vectors, at least 0.01 in these cases (the zero-length and
 * half-turn lines take the pole from a point 0.01 radians away), which amplifies an error by at
 * most 100; {@code acos} amplifies by {@code 1/sin(length)}, at most 20 for these lines, and
 * {@code atan2} by the inverse of the distance from the pole, at most about 60 here. That bounds
 * the difference by about {@code 100 * 10 * 5e-16 = 5e-13}, so {@link #SKY_TOL} is {@code 1e-12}.
 * No case puts a crossing or offset point on the 180 degree meridian or at a pole, where the
 * longitude C gives is not a continuous function of the input.
 */
class FrameLineTest {

    /** The tolerance for a SkyFrame's values (see the class description). */
    private static final double SKY_TOL = 1e-12;

    private static SkyFrame swappedSky() {
        SkyFrame s = new SkyFrame();
        s.permAxes(new int[] {2, 1});
        return s;
    }

    /** A FrameSet with a 2-D base Frame and current Frame {@code cur}, as the probe makes it. */
    static FrameSet frameset(Frame cur) {
        int naxes = cur.getNaxes();
        FrameSet fs = new FrameSet(new Frame(naxes));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(naxes), cur);
        return fs;
    }

    /** Runs the line case {@code name} in {@code frame}, checking each result against C's. */
    private static void assertCaseMatchesC(String name, Frame frame, boolean sky) {
        assertCaseMatchesC(name, frame, sky, new ArrayList<>());
    }

    /**
     * Runs the line case {@code name} in {@code frame}, checking each result against C's. The
     * lines of {@code extra}, made elsewhere as the probe makes them, follow the lines the case
     * defines.
     */
    private static void assertCaseMatchesC(String name, Frame frame, boolean sky, List<Frame.LineDef> extra) {
        double tol = sky ? SKY_TOL : 0.0;
        int nvec = sky ? 3 : 2;
        double[] lastCross = null;
        List<Frame.LineDef> lines = new ArrayList<>();
        for (FrameProbe.Op op : FrameProbe.ops(name)) {
            if (!"def".equals(op.name) && !extra.isEmpty()) {
                lines.addAll(extra);
                extra.clear();
            }
            switch (op.name) {
                case "def": {
                    double[] start = op.argRange(0, 2);
                    double[] end = op.argRange(2, 4);
                    if (op.failed()) {
                        op.assertFails(() -> frame.lineDef(start, end));
                        lines.add(null);
                        break;
                    }
                    Frame.LineDef l = frame.lineDef(start, end);
                    lines.add(l);
                    if ("null".equals(op.results.get(0))) {
                        assertNull(l, op.line);
                        break;
                    }
                    assertNotNull(l, op.line);
                    FrameProbe.assertClose(
                            new double[] {op.result(0)}, new double[] {l.length}, tol, op.line + " length");
                    assertEquals(op.result(1) != 0.0, l.infinite, op.line);
                    int i = 2;
                    FrameProbe.assertClose(op.resultRange(i, i + nvec), l.start, tol, op.line + " start");
                    i += nvec;
                    FrameProbe.assertClose(op.resultRange(i, i + nvec), l.end, tol, op.line + " end");
                    i += nvec;
                    FrameProbe.assertClose(op.resultRange(i, i + nvec), l.dir, tol, op.line + " dir");
                    i += nvec;
                    FrameProbe.assertClose(op.resultRange(i, i + nvec), l.q, tol, op.line + " q");
                    break;
                }
                case "off": {
                    Frame.LineDef l = lines.get((int) op.arg(0));
                    double par = op.arg(1);
                    double prp = op.arg(2);
                    double[] p = {AstObject.AST__BAD, AstObject.AST__BAD};
                    if (op.failed()) {
                        op.assertFails(() -> frame.lineOffset(l, par, prp, p));
                        break;
                    }
                    frame.lineOffset(l, par, prp, p);
                    FrameProbe.assertClose(op.resultRange(0, 2), p, tol, op.line);
                    break;
                }
                case "cross": {
                    Frame.LineDef l1 = lines.get((int) op.arg(0));
                    Frame.LineDef l2 = lines.get((int) op.arg(1));
                    double bad = AstObject.AST__BAD;
                    double[] c = {bad, bad, bad, bad, bad};
                    if (op.failed()) {
                        op.assertFails(() -> frame.lineCrossing(l1, l2, c));
                        break;
                    }
                    boolean r = frame.lineCrossing(l1, l2, c);
                    assertEquals(op.result(0) != 0.0, r, op.line);
                    int nc = sky ? 5 : 2;
                    double[] got = new double[nc];
                    System.arraycopy(c, 0, got, 0, nc);
                    FrameProbe.assertClose(op.resultRange(1, 1 + nc), got, tol, op.line);
                    lastCross = c;
                    break;
                }
                case "contains": {
                    Frame.LineDef l = lines.get((int) op.arg(0));
                    int def = (int) op.arg(1);
                    int np = def != 0 && sky ? 5 : 2;
                    double[] point = new double[5];
                    Arrays.fill(point, AstObject.AST__BAD);
                    System.arraycopy(op.argRange(2, 2 + np), 0, point, 0, np);
                    if (def != 0 && lastCross != null) {
                        FrameProbe.assertClose(point, lastCross, tol, op.line + " input");
                    }
                    if (op.failed()) {
                        op.assertFails(() -> frame.lineContains(l, def, point));
                        break;
                    }
                    assertEquals(op.result(0) != 0.0, frame.lineContains(l, def, point), op.line);
                    break;
                }
                default:
                    fail("Unknown probe operation " + op.line);
            }
        }
    }

    @Test
    void linesInAFrameAreThoseOfC() {
        assertCaseMatchesC("line_frame", new Frame(2), false);
    }

    @Test
    void greatCirclesOnASkyFrameAreThoseOfC() {
        assertCaseMatchesC("line_sky", new SkyFrame(), true);
    }

    @Test
    void greatCirclesOnASwappedSkyFrameAreThoseOfC() {
        assertCaseMatchesC("line_sky_swapped", swappedSky(), true);
    }

    @Test
    void aFrameSetUsesItsCurrentFramesLines() {
        assertCaseMatchesC("line_frameset", frameset(new SkyFrame()), true);
    }

    @Test
    void aFrameThatIsNotTwoDimensionalHasNoLines() {
        assertCaseMatchesC("line_error_naxes", new Frame(3), false);
    }

    /** A line made in another Frame is an internal error, as in C. */
    @Test
    void aLineFromAnotherFrameIsRejected() {
        List<Frame.LineDef> extra = new ArrayList<>();
        extra.add(new Frame(2).lineDef(new double[] {0, 0}, new double[] {1, 1}));
        assertCaseMatchesC("line_error_frame", new Frame(2), false, extra);

        extra.add(new SkyFrame().lineDef(new double[] {0, 0}, new double[] {1, 1}));
        assertCaseMatchesC("line_error_sky_frame", new SkyFrame(), true, extra);

        extra.add(frameset(new SkyFrame()).lineDef(new double[] {0, 0}, new double[] {1, 1}));
        assertCaseMatchesC("line_error_frameset", new SkyFrame(), true, extra);
    }
}
