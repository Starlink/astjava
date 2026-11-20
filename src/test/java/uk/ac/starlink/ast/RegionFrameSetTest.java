package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * A Box used as a Frame, and a Box changed after it has been used, against C ({@code
 * probes/region_frameset_probe.c}).
 *
 * <p>A Box added to a FrameSet as a Frame is a Mapping on the path between the Frames on either
 * side of it (frameset.c {@code Span}), so a point outside it becomes bad, and the FrameSet is
 * written and read back as C writes it. A Box used as the template of {@link Frame#findFrame} and
 * as either Frame of {@link Frame#convert} stands for its current Frame (region.c {@code Match},
 * {@code Convert}, {@code ConvertX}).
 *
 * <p>After a change of Negated, Closed, MeshSize (on a Box mapped by a non-linear MathMap, whose
 * bounds come from its boundary mesh), the System of a Box in a SpecFrame, Adaptive and not, or
 * the uncertainty, the transformation, {@link Region#getRegionBounds}, {@link
 * Region#getRegionPoints} and {@link Region#getUnc} reflect the change, as C's resets of what a
 * Region keeps make them.
 *
 * <p>Each case writes the lines the probe writes, values with {@code %.17g}, and compares them
 * with C's exactly: the arithmetic of these cases involves no {@code java.lang.Math} function.
 */
class RegionFrameSetTest {

    private static final Map<String, String> PROBE = ProbeOutput.load("region_frameset_probe");

    /** The probe's output for one case, written as the probe writes it. */
    private static final class Out {
        private final List<String> lines = new ArrayList<>();

        private static String g(double v) {
            return CFormat.format("%.17g", v);
        }

        void line(String text) {
            lines.add(text);
        }

        void tran(String label, Mapping m, boolean forward, double... in) {
            int nin = forward ? m.getNin() : m.getNout();
            int nout = forward ? m.getNout() : m.getNin();
            double[][] rows = new double[nin][1];
            for (int i = 0; i < nin; i++) {
                rows[i][0] = in[i];
            }
            double[][] out = m.tranP(1, nin, rows, forward, nout);
            StringBuilder t = new StringBuilder("tran ").append(label).append(forward ? " fwd" : " inv");
            for (int i = 0; i < nin; i++) {
                t.append(' ').append(g(in[i]));
            }
            t.append(" :");
            for (int i = 0; i < nout; i++) {
                t.append(' ').append(g(out[i][0]));
            }
            line(t.toString());
        }

        void bounds(String what, Region r) {
            double[][] b = r.getRegionBounds();
            StringBuilder t = new StringBuilder(what);
            for (int i = 0; i < b[0].length; i++) {
                t.append(' ').append(g(b[0][i])).append(' ').append(g(b[1][i]));
            }
            line(t.toString());
        }

        void unc(Region r) {
            bounds("unc", r.getUnc(true));
        }

        void points(Region r) {
            double[][] p = r.getRegionPoints();
            StringBuilder t = new StringBuilder("points");
            for (int ip = 0; ip < p[0].length; ip++) {
                for (double[] axis : p) {
                    t.append(' ').append(g(axis[ip]));
                }
            }
            line(t.toString());
        }

        void frames(FrameSet fs) {
            if (fs == null) {
                line("frames null");
                return;
            }
            line("frames " + fs.getFrame(FrameSet.AST__CURRENT).astClass() + " " + fs.getNframe() + " "
                    + fs.getDomain());
        }

        AstObject reread(AstObject obj) {
            String text = ChannelText.dump(obj);
            for (String l : text.split("\n", -1)) {
                if (!l.isEmpty()) {
                    line(trimEnd("dump " + l));
                }
            }
            return ChannelText.read(text);
        }

        private static String trimEnd(String s) {
            int n = s.length();
            while (n > 0 && Character.isWhitespace(s.charAt(n - 1))) {
                n--;
            }
            return s.substring(0, n);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    private static Frame frame2(String domain) {
        Frame f = new Frame(2);
        if (domain != null) {
            f.setDomain(domain);
        }
        return f;
    }

    private static Box square(Frame f, double lo, double hi) {
        return new Box(f, 1, new double[] {lo, lo}, new double[] {hi, hi}, null);
    }

    private static void check(String name, Out out) {
        assertEquals(PROBE.get(name), out.toString(), name);
    }

    @Test
    void aBoxInAFrameSetMasksThePath() {
        Out out = new Out();
        FrameSet fs = new FrameSet(frame2("PIX"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), square(frame2("GRID"), 0.0, 10.0));
        out.tran("to_box_in", fs, true, 1.0, 1.0);
        out.tran("to_box_out", fs, true, 6.0, 6.0);
        fs.addFrame(FrameSet.AST__CURRENT, new ShiftMap(new double[] {100.0, 100.0}), frame2("SKYISH"));
        out.tran("through_box_in", fs, true, 1.0, 1.0);
        out.tran("through_box_out", fs, true, 6.0, 6.0);
        out.tran("through_box_in", fs, false, 102.0, 102.0);
        out.tran("through_box_out", fs, false, 112.0, 112.0);
        Mapping back = (Mapping) out.reread(fs);
        out.tran("reread_in", back, true, 1.0, 1.0);
        out.tran("reread_out", back, true, 6.0, 6.0);
        check("in_frameset", out);
    }

    @Test
    void aBoxAsTheTemplateOfFindFrameIsItsCurrentFrame() {
        Out out = new Out();
        FrameSet fs = new FrameSet(frame2("PIX"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 0.5), frame2("GRID"));
        fs.setCurrent(1);
        Box box = square(frame2("GRID"), 0.0, 10.0);
        FrameSet cvt = fs.findFrame(box, " ");
        out.frames(cvt);
        out.line("current " + fs.getCurrent());
        out.tran("inside", cvt, true, 4.0, 4.0);
        out.tran("outside", cvt, true, 100.0, 100.0);
        cvt = frame2("GRID").findFrame(box, " ");
        out.frames(cvt);
        out.tran("frame_outside", cvt, true, 100.0, 100.0);
        check("findframe_template", out);
    }

    @Test
    void aBoxConvertedToOrFromIsItsCurrentFrame() {
        Out out = new Out();
        Box box = square(frame2("GRID"), 0.0, 10.0);
        FrameSet cvt = frame2("GRID").convert(box, " ");
        out.frames(cvt);
        out.tran("to_box_outside", cvt, true, 100.0, 100.0);
        cvt = box.convert(frame2("GRID"), " ");
        out.frames(cvt);
        out.tran("from_box_outside", cvt, true, 100.0, 100.0);
        cvt = box.convert(square(frame2("GRID"), 50.0, 60.0), " ");
        out.frames(cvt);
        out.tran("box_to_box", cvt, true, 100.0, 100.0);
        check("convert", out);
    }

    @Test
    void negatedAfterUse() {
        Out out = new Out();
        Box box = square(new Frame(2), 0.0, 10.0);
        out.tran("inside", box, true, 5.0, 5.0);
        out.tran("outside", box, true, 20.0, 5.0);
        out.bounds("bounds", box);
        out.points(box);
        out.unc(box);
        box.setNegated(true);
        out.tran("neg_inside", box, true, 5.0, 5.0);
        out.tran("neg_outside", box, true, 20.0, 5.0);
        out.tran("neg_boundary", box, true, 10.0, 5.0);
        out.bounds("bounds", box);
        out.points(box);
        out.unc(box);
        check("negated", out);
    }

    @Test
    void closedAfterUse() {
        Out out = new Out();
        Box box = square(new Frame(2), 0.0, 10.0);
        out.tran("boundary", box, true, 10.0, 5.0);
        out.tran("near", box, true, 10.0 - 1.0e-7, 5.0);
        box.setClosed(false);
        out.tran("open_boundary", box, true, 10.0, 5.0);
        out.tran("open_near", box, true, 10.0 - 1.0e-7, 5.0);
        box.setNegated(true);
        out.tran("open_neg_boundary", box, true, 10.0, 5.0);
        box.setClosed(true);
        out.tran("closed_neg_boundary", box, true, 10.0, 5.0);
        check("closed", out);
    }

    @Test
    void meshSizeAfterUse() {
        Out out = new Out();
        Box box = new Box(new Frame(2), 1, new double[] {-1.0, 0.0}, new double[] {1.0, 1.0}, null);
        MathMap m = new MathMap(
                2, 2, new String[] {"x2 = x1", "y2 = y1 + 0.1*x1*x1"}, new String[] {"x1 = x2", "y1 = y2 - 0.1*x2*x2"});
        Region r = box.mapRegionInto(m, new Frame(2));
        out.line("meshsize " + r.getMeshSize());
        out.bounds("bounds", r);
        out.tran("inside", r, true, 0.5, 0.5);
        out.tran("outside", r, true, 0.5, 0.01);
        r.setMeshSize(20);
        out.line("meshsize " + r.getMeshSize());
        out.bounds("bounds", r);
        out.points(r);
        out.unc(r);
        r.clear("MeshSize");
        out.bounds("bounds", r);
        check("meshsize", out);
    }

    private static Box specBox() {
        SpecFrame sf = new SpecFrame();
        sf.setSystem("FREQ");
        return new Box(sf, 1, new double[] {100.0}, new double[] {110.0}, null);
    }

    @Test
    void systemAfterUseOnAnAdaptiveBox() {
        Out out = new Out();
        Box box = specBox();
        out.tran("inside", box, true, 105.0);
        out.tran("outside", box, true, 120.0);
        out.bounds("bounds", box);
        box.setSystem("WAVE");
        out.line("system " + box.getC("System") + " unit " + box.getC("Unit(1)"));
        out.tran("inside", box, true, 2.8e7);
        out.tran("outside", box, true, 2.5e7);
        out.tran("old_inside", box, true, 105.0);
        out.bounds("bounds", box);
        out.points(box);
        out.unc(box);
        check("system_adaptive", out);
    }

    @Test
    void systemAfterUseOnABoxThatIsNotAdaptive() {
        Out out = new Out();
        Box box = specBox();
        box.setAdaptive(false);
        out.tran("inside", box, true, 105.0);
        out.bounds("bounds", box);
        box.setSystem("WAVE");
        out.line("system " + box.getC("System") + " unit " + box.getC("Unit(1)"));
        out.tran("inside", box, true, 105.0);
        out.tran("outside", box, true, 2.8e7);
        out.bounds("bounds", box);
        out.points(box);
        out.unc(box);
        check("system_not_adaptive", out);
    }

    @Test
    void uncertaintySetAfterUse() {
        Out out = new Out();
        Box box = new Box(new Frame(2), 1, new double[] {0.0, 5.0}, new double[] {10.0, 5.0}, null);
        out.tran("near", box, true, 5.0, 5.0 + 2.0e-6);
        out.tran("off", box, true, 5.0, 5.3);
        out.unc(box);
        box.setUnc(square(new Frame(2), -0.5, 0.5));
        out.tran("near", box, true, 5.0, 5.0 + 2.0e-6);
        out.tran("off", box, true, 5.0, 5.3);
        out.tran("far", box, true, 5.0, 5.6);
        out.bounds("bounds", box);
        out.points(box);
        out.unc(box);
        check("setunc", out);
    }
}
