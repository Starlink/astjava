package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * SelectorMap behaviour against C, from {@code probes/selectormap_probe.c}: the index, or the bad
 * value, a SelectorMap gives for points in overlapping, negated, unbounded and open Regions, on a
 * boundary, and bad on one axis, the first Region holding a point winning; the dump of a
 * SelectorMap after it has transformed points, in which its Regions have Closed set, as C's
 * {@code Transform} leaves them (selectormap.c negates and re-closes its own Regions in place); its
 * copy and comparisons; simplification, which keeps a SelectorMap whose Regions do not simplify
 * and rebuilds one whose Region does; SelectorMaps in series, including two equal 1-D SelectorMaps
 * both applied forward, which C's {@code MapMerge} replaces with a UnitMap; the load of a dump whose
 * first Region's FrameSet holds a MathMap; the load of a dump without Regions; testregions.c's
 * {@code checkSelectorMapEqual}; and the constructor's errors.
 *
 * <p>Each case writes the lines the probe writes and compares them with C's exactly.
 */
class SelectorMapProbeTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("selectormap_probe");

    private static final double[] PX = {
        2.0, 7.0, 12.0, 10.0, 15.0, 60.0, -100.0, -200.0, 50.0, -105.0, BAD, 5.0, BAD, 20.0, 25.0
    };
    private static final double[] PY = {
        2.0, 7.0, 12.0, 5.0, 15.0, -1000.0, -100.0, 0.0, 0.0, -100.0, 5.0, BAD, BAD, 25.0, 25.0
    };
    private static final double[] PTS1 = {-1.0, 0.0, 0.5, 1.0, 2.0, BAD};

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    /** The probe case without the lines naming the C source of an error. */
    private static String probeWithoutSource(String name) {
        StringBuilder sb = new StringBuilder();
        for (String line : probe(name).split("\n", -1)) {
            if (!line.startsWith("!! ") && !line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    /** The dump of {@code obj}, as the probe's {@code astShow} writes it. */
    private static String show(AstObject obj) {
        StringBuilder sb = new StringBuilder();
        for (String l : ChannelText.dump(obj).split("\n", -1)) {
            if (!l.isEmpty()) {
                sb.append(ProbeOutput.trimLines(l)).append('\n');
            }
        }
        return sb.toString();
    }

    /** The forward transformation of the 2-D points, one line each. */
    private static String tran2(Mapping m) {
        double[] out = m.tranP(PX.length, 2, new double[][] {PX, PY}, true, 1)[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PX.length; i++) {
            sb.append("fwd ")
                    .append(g(PX[i]))
                    .append(' ')
                    .append(g(PY[i]))
                    .append(" -> ")
                    .append(g(out[i]))
                    .append('\n');
        }
        return sb.toString();
    }

    /** The forward transformation of 1-D points, one line each. */
    private static String tran1(Mapping m, double[] in) {
        double[] out = m.tran1(in.length, in, true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i++) {
            sb.append("fwd ").append(g(in[i])).append(" -> ").append(g(out[i])).append('\n');
        }
        return sb.toString();
    }

    /** The class of the simplified Mapping, whether it is the Mapping itself, and its dump. */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // astSame compares the Objects.
    private static String simp(Mapping m) {
        Mapping s = m.simplify();
        return "-- simplified " + s.astClass() + " same " + (s == m ? 1 : 0) + "\n" + show(s);
    }

    private static int flag(boolean b) {
        return b ? 1 : 0;
    }

    private static Box box2(double xlo, double ylo, double xhi, double yhi) {
        return new Box(new Frame(2), 1, new double[] {xlo, ylo}, new double[] {xhi, yhi}, null);
    }

    private static Box box1(double lo, double hi) {
        return new Box(new Frame(1), 1, new double[] {lo}, new double[] {hi}, null);
    }

    /** Review Focus 1: Regions that overlap, an unbounded Interval and a negated Circle. */
    private static SelectorMap focus(double badval) {
        Circle circle = new Circle(new Frame(2), new double[] {-100.0, -100.0}, 5.0, null);
        circle.negate();
        Region[] regs = {
            box2(0.0, 0.0, 10.0, 10.0),
            box2(5.0, 5.0, 15.0, 15.0),
            new Interval(new Frame(2), new double[] {50.0, BAD}, new double[] {BAD, BAD}, null),
            circle
        };
        return new SelectorMap(regs, badval);
    }

    /** A SelectorMap of two copies of one Box, the first open. */
    private static SelectorMap openClosed() {
        Box open = box2(20.0, 20.0, 30.0, 30.0);
        open.setClosed(false);
        return new SelectorMap(new Region[] {open, box2(20.0, 20.0, 30.0, 30.0)}, -1.0);
    }

    private static SelectorMap twoBoxes() {
        return new SelectorMap(new Region[] {box2(0.0, 0.0, 10.0, 10.0), box2(20.0, 20.0, 30.0, 30.0)}, -1.0);
    }

    @Test
    void indicesMatchC() {
        assertEquals(probe("focus_points"), tran2(focus(-1.0)));
        assertEquals(probe("focus_points_bad"), tran2(focus(BAD)));
        assertEquals(probe("open_closed_points"), tran2(openClosed()));
    }

    /**
     * Review Focus 2: C's {@code Transform} leaves Closed set on each of the SelectorMap's own
     * Regions, so a dump made after a transformation has {@code Closed} lines; comparisons, which
     * read the values, are unchanged.
     */
    @Test
    void aTransformationLeavesClosedSetAsC() {
        SelectorMap before = focus(-1.0);
        assertEquals(probe("focus_dump_before"), show(before));

        SelectorMap sm = focus(-1.0);
        assertEquals(probe("focus_dump_after"), tran2(sm) + show(sm));
        assertEquals(probe("focus_copy_after"), show(sm.copy()));
        SelectorMap fresh = focus(-1.0);
        String equal = "equal fresh " + flag(sm.astEqual(fresh)) + "\nequal fresh reversed " + flag(fresh.astEqual(sm))
                + "\nequal copy " + flag(sm.astEqual(sm.copy())) + "\n";
        assertEquals(probe("focus_equal_after"), equal);

        SelectorMap oc = openClosed();
        assertEquals(probe("open_closed_dump_after"), tran2(oc) + show(oc));
    }

    /** Item 3: a SelectorMap whose Regions do not simplify is itself; one whose Region does is rebuilt. */
    @Test
    void simplificationMatchesC() {
        SelectorMap sm = twoBoxes();
        assertEquals(probe("simplify_unchanged"), simp(sm) + "-- original after\n" + show(sm));

        Region interval = new Interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {10.0, 10.0}, null);
        SelectorMap sm2 = new SelectorMap(new Region[] {interval, box2(20.0, 20.0, 30.0, 30.0)}, -1.0);
        assertEquals(probe("simplify_changed"), show(sm2) + simp(sm2) + tran2(sm2.simplify()));
    }

    /**
     * Item 4: C's {@code MapMerge} inverts the neighbouring SelectorMap and compares it with {@code
     * Equal}, which ignores Invert, so two equal 1-D SelectorMaps both applied forward merge into a
     * UnitMap, although the pair gives 1 for every point in the Region.
     */
    @Test
    void selectorMapsInSeriesSimplifyAsC() {
        SelectorMap sm = new SelectorMap(new Region[] {box1(0.0, 1.0)}, BAD);
        SelectorMap sm2 = (SelectorMap) sm.copy();
        CmpMap cm = new CmpMap(sm, sm2, true);
        assertEquals(
                probe("series_1d_both_forward"),
                "equal " + flag(sm.astEqual(sm2)) + "\n" + tran1(cm, PTS1) + simp(cm) + tran1(cm.simplify(), PTS1));

        SelectorMap inv = (SelectorMap) sm.copy();
        inv.invert();
        assertEquals(probe("series_1d_second_inverted"), simp(new CmpMap(sm, inv, true)));

        SelectorMap other = new SelectorMap(new Region[] {box1(0.0, 2.0)}, BAD);
        assertEquals(probe("series_1d_different"), simp(new CmpMap(sm, other, true)));

        SelectorMap two = twoBoxes();
        SelectorMap twoInv = (SelectorMap) two.copy();
        twoInv.invert();
        assertEquals(probe("series_2d_second_inverted"), simp(new CmpMap(two, twoInv, true)));
        assertEquals(probe("series_2d_first_inverted"), simp(new CmpMap(twoInv, two, true)));
        assertEquals(probe("parallel_2d"), simp(new CmpMap(two, two, false)));
    }

    /**
     * The second Region is written without its Frame and, read back, given the first Region's
     * FrameSet, whose Mapping is a MathMap (selectormap.c {@code astLoadSelectorMap}): {@code
     * astSetRegFS} joins the base Frame to that FrameSet's current Frame with a UnitMap, so the
     * Region is as it was.
     */
    @Test
    void aLaterRegionIsGivenTheFirstRegionsFrameSetAsC() {
        Circle circle = new Circle(new Frame(2), new double[] {0.0, 0.0}, 1.0, null);
        MathMap cube =
                new MathMap(2, 2, new String[] {"x=a*a*a", "y=b"}, new String[] {"a=sign(abs(x)**(1/3.),x)", "b=y"});
        Region[] regs = {circle.mapRegion(cube, new Frame(2)), box2(3.0, 3.0, 4.0, 4.0)};
        SelectorMap sm = new SelectorMap(regs, -1.0);
        Mapping back = (Mapping) ChannelText.read(ChannelText.dump(sm));
        StringBuilder sb = new StringBuilder()
                .append("-- written\n")
                .append(show(sm))
                .append("-- read back\n")
                .append(show(back))
                .append("-- equal ")
                .append(flag(sm.astEqual(back)))
                .append('\n');
        double[][] q = {{0.5, 3.5, 40.0, 7.0}, {0.5, 3.5, 3.5, 7.0}};
        String[] labels = {"written", "read back"};
        Mapping[] maps = {sm, back};
        for (int k = 0; k < 2; k++) {
            double[] out = maps[k].tranP(4, 2, q, true, 1)[0];
            for (int i = 0; i < 4; i++) {
                sb.append(labels[k])
                        .append(' ')
                        .append(g(q[0][i]))
                        .append(' ')
                        .append(g(q[1][i]))
                        .append(" -> ")
                        .append(g(out[i]))
                        .append('\n');
            }
        }
        assertEquals(probe("load_first_region_mapped"), sb.toString());
    }

    /**
     * C cannot load a SelectorMap without Regions: {@code astLoadSelectorMap} annuls the first
     * Region's FrameSet pointer, which is null, and fails with AST__OBJIN. Here the load fails with
     * AST__LDERR.
     */
    @Test
    void aDumpWithoutRegionsDoesNotLoad() {
        assertTrue(probe("load_no_regions").contains("status " + AstException.AST__OBJIN + "\n-- read nothing\n"));
        String text = " Begin SelectorMap\n    Nin = 2\n    Nout = 1\n    Inv = 0\n IsA Mapping\n"
                + "    BadVal = 7\n End SelectorMap\n";
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__LDERR, e.getStatus());
    }

    /** testregions.c {@code checkSelectorMapEqual}. */
    @Test
    void checkSelectorMapEqual() {
        Frame frm = new Frame(2);
        Region[] regs = {
            new Circle(frm, new double[] {0.0, 0.0}, 1.0, null), new Circle(frm, new double[] {5.0, 5.0}, 1.0, null)
        };
        SelectorMap one = new SelectorMap(new Region[] {regs[0]}, BAD);
        SelectorMap two = new SelectorMap(regs, BAD);
        String got = "test 1 " + flag(one.astEqual(two)) + "\ntest 2 " + flag(two.astEqual(one)) + "\ntest 3 "
                + flag(two.astEqual(two.copy())) + "\n";
        assertEquals(probe("check_selectormap_equal"), got);
    }

    @Test
    void constructorErrorsMatchC() {
        assertError("error_no_regions", () -> new SelectorMap(new Region[0], BAD));
        assertError("error_naxes", () -> new SelectorMap(new Region[] {box2(0.0, 0.0, 1.0, 1.0), box1(0.0, 1.0)}, BAD));
        Circle sky = new Circle(new SkyFrame(), new double[] {0.0, 0.0}, 0.1, null);
        assertError(
                "error_skyframe",
                () -> new SelectorMap(new Region[] {box2(0.0, 0.0, 1.0, 1.0), box2(2.0, 2.0, 3.0, 3.0), sky}, BAD));
    }

    private static void assertError(String name, org.junit.jupiter.api.function.Executable make) {
        AstException e = assertThrows(AstException.class, make, name);
        StringBuilder sb = new StringBuilder();
        for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        assertEquals(probeWithoutSource(name), sb + "status " + e.getStatus() + "\n", name);
    }
}
