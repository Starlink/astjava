package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Removing the Regions from the containers and nestings the {@code remove_regions} oracle does not
 * cover, against C ({@code probes/remove_regions_extra_probe.c}).
 *
 * <p>A Region gives a copy of its current Frame (region.c {@code RemoveRegions}), and a Mapping
 * holding no Region gives itself (mapping.c, C's {@code astClone}). A container replaces a
 * component that comes back as a Frame with a UnitMap: a CmpMap and a TranMap every such
 * component, so also a plain Frame or a FrameSet component that was already there when the other
 * component changed (cmpmap.c, tranmap.c); a SwitchMap and a FrameSet only a component that
 * changed (switchmap.c, frameset.c). A CmpFrame or a FrameSet that changed is a copy whose
 * unchanged components are the original's own Objects (cmpframe.c, frameset.c), so that an
 * attribute set through the result is seen in the original.
 *
 * <p>Each case writes the lines the probe writes and compares them with C's exactly: the dump of
 * the Mapping ({@code in}), the dump of the result ({@code out}), whether the result is the Mapping
 * itself ({@code same}), and for a CmpFrame or a FrameSet whether each component of the result is
 * the original's own. A SelectorMap removes no Regions (selectormap.c installs no {@code
 * RemoveRegions}), so it is itself, and a SwitchMap with one as its selector removes the Regions of
 * its routes only.
 */
class RemoveRegionsTest {

    private static final Map<String, String> PROBE = ProbeOutput.load("remove_regions_extra_probe");

    private static final double[] SHIFT = {100.0, 100.0};

    /** The probe's output for one case, written as the probe writes it. */
    private static final class Out {
        private final List<String> lines = new ArrayList<>();

        void line(String text) {
            lines.add(trimEnd(text));
        }

        void dump(String prefix, AstObject obj) {
            for (String l : ChannelText.dump(obj).split("\n", -1)) {
                if (!l.isEmpty()) {
                    line(prefix + " " + l);
                }
            }
        }

        /** Writes the Mapping and what {@code removeRegions} returns, and returns that. */
        Mapping run(Mapping map) {
            dump("in", map);
            Mapping result = map.removeRegions();
            dump("out", result);
            line("same " + same(result, map));
            return result;
        }

        void components(Mapping result, Mapping map) {
            Mapping[] r = result.decompose(null, null);
            Mapping[] m = map.decompose(null, null);
            line("component 1 same " + same(r[0], m[0]));
            line("component 2 same " + same(r[1], m[1]));
        }

        void frames(FrameSet result, FrameSet map) {
            for (int i = 1; i <= map.getNframe(); i++) {
                line("frame " + i + " same " + same(result.getFrame(i), map.getFrame(i)));
            }
        }

        /** C's {@code astSame}: 1 if {@code a} and {@code b} are the same Object, otherwise 0. */
        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // astSame compares the Objects.
        private static int same(AstObject a, AstObject b) {
            return a == b ? 1 : 0;
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

    private static void check(String name, Out out) {
        assertEquals(PROBE.get(name), out.toString(), name);
    }

    private static void check(String name, Mapping map) {
        Out out = new Out();
        out.run(map);
        check(name, out);
    }

    private static Frame frame(int naxes, String domain) {
        Frame f = new Frame(naxes);
        if (domain != null) {
            f.setDomain(domain);
        }
        return f;
    }

    private static Box box2(double lo, double hi) {
        return new Box(new Frame(2), 1, new double[] {lo, lo}, new double[] {hi, hi}, null);
    }

    private static Circle circle2() {
        return new Circle(new Frame(2), new double[] {1.0, 2.0}, 1.0, null);
    }

    /** A PermMap from two axes to one, giving the first input. */
    private static PermMap pickFirst() {
        return new PermMap(2, new int[] {1, 0}, 1, new int[] {1}, null);
    }

    private static SwitchMap switch2(Mapping fs, Mapping r1, Mapping r2) {
        return new SwitchMap(fs, null, new Mapping[] {r1, r2});
    }

    private static FrameSet pixToSky() {
        FrameSet fs = new FrameSet(frame(2, "PIX"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), frame(2, "SKY"));
        return fs;
    }

    private static Out frameSetCase(FrameSet fs) {
        Out out = new Out();
        out.frames((FrameSet) out.run(fs), fs);
        return out;
    }

    private static Out cmpFrameCase(CmpFrame cf) {
        Out out = new Out();
        out.components(out.run(cf), cf);
        return out;
    }

    @Test
    void aRegionGivesACopyOfItsFrame() {
        check("region_alone", box2(0.0, 1.0));
    }

    @Test
    void aFrameIsItself() {
        check("frame_alone", frame(2, null));
    }

    @Test
    void aCmpMapWithoutRegionsIsItself() {
        check("no_regions_cmpmap", new CmpMap(new ZoomMap(2, 3.0), new ShiftMap(SHIFT), true));
    }

    @Test
    void aFrameSetWithoutRegionsIsItself() {
        check("no_regions_frameset", frameSetCase(pixToSky()));
    }

    @Test
    void aCmpMapTurnsAnUnchangedFrameIntoAUnitMap() {
        check(
                "cmpmap_frame_beside_region",
                new CmpMap(frame(1, null), new CmpMap(box2(0.0, 1.0), new ZoomMap(2, 3.0), true), false));
    }

    @Test
    void aTranMapTurnsAnUnchangedFrameIntoAUnitMap() {
        check(
                "tranmap_frame_beside_region",
                new TranMap(frame(2, null), new CmpMap(box2(0.0, 1.0), new ZoomMap(2, 3.0), true)));
    }

    /** C's {@code astIsAFrame} is true of a FrameSet, so its Mapping is lost: a C quirk, kept. */
    @Test
    void aTranMapTurnsAFrameSetIntoAUnitMap() {
        check("tranmap_frameset_beside_region", new TranMap(pixToSky(), box2(0.0, 1.0)));
    }

    @Test
    void aSwitchMapRemovesRegionsFromItsSelectorAndRoutes() {
        check(
                "switchmap_region_route",
                switch2(new CmpMap(box2(0.0, 10.0), pickFirst(), true), box2(0.0, 1.0), new ZoomMap(2, 3.0)));
    }

    @Test
    void aSwitchMapKeepsAnUnchangedFrameRoute() {
        check("switchmap_frame_route", switch2(pickFirst(), box2(0.0, 1.0), frame(2, null)));
    }

    @Test
    void aSwitchMapWithoutRegionsIsItself() {
        check("switchmap_no_regions", switch2(pickFirst(), new ZoomMap(2, 2.0), new ZoomMap(2, 3.0)));
    }

    @Test
    void aRegionAsAFrameOfAFrameSetBecomesItsFrame() {
        FrameSet fs = new FrameSet(frame(2, "PIX"));
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, 2.0), box2(0.0, 10.0));
        fs.addFrame(FrameSet.AST__CURRENT, new ShiftMap(SHIFT), frame(2, "SKY"));
        check("frameset_region_frame", frameSetCase(fs));
    }

    @Test
    void aRegionInACmpMapInAFrameSetIsRemoved() {
        FrameSet fs = new FrameSet(frame(2, "PIX"));
        fs.addFrame(FrameSet.AST__BASE, new CmpMap(new ZoomMap(2, 2.0), circle2(), true), frame(2, "SKY"));
        check("frameset_region_in_cmpmap", frameSetCase(fs));
    }

    @Test
    void aRegionAsAMappingOfAFrameSetBecomesAUnitMap() {
        FrameSet fs = new FrameSet(frame(2, "PIX"));
        fs.addFrame(FrameSet.AST__BASE, box2(0.0, 10.0), frame(2, "SKY"));
        check("frameset_region_map", frameSetCase(fs));
    }

    @Test
    void aFrameSetKeepsAnUnchangedFrameAsAMapping() {
        FrameSet fs = new FrameSet(frame(2, "PIX"));
        fs.addFrame(FrameSet.AST__BASE, frame(2, null), box2(0.0, 10.0));
        check("frameset_frame_map", frameSetCase(fs));
    }

    @Test
    void aCmpFrameOfTwoRegionsBecomesACmpFrameOfTheirFrames() {
        CmpFrame cf = new CmpFrame(
                box2(0.0, 1.0), new Interval(frame(1, null), new double[] {0.0}, new double[] {1.0}, null));
        check("cmpframe_two_regions", cmpFrameCase(cf));
    }

    @Test
    void aCmpFrameSharesAnUnchangedComponent() {
        CmpFrame cf = new CmpFrame(box2(0.0, 1.0), frame(1, null));
        Out out = new Out();
        Mapping result = out.run(cf);
        out.components(result, cf);
        ((Frame) result).setLabel(3, "Shared");
        out.dump("orig", cf);
        check("cmpframe_shared_component", out);
    }

    @Test
    void aCmpFrameWithoutRegionsIsItself() {
        check("cmpframe_no_regions", cmpFrameCase(new CmpFrame(frame(2, null), frame(1, null))));
    }

    private static SelectorMap selectorOfTwoBoxes() {
        return new SelectorMap(new Region[] {box2(0.0, 1.0), box2(2.0, 3.0)}, AstObject.AST__BAD);
    }

    @Test
    void aSelectorMapKeepsItsRegions() {
        check("selectormap_alone", selectorOfTwoBoxes());
    }

    @Test
    void aSwitchMapKeepsTheRegionsOfItsSelectorMap() {
        check("selectormap_switchmap", switch2(selectorOfTwoBoxes(), box2(0.0, 1.0), new ZoomMap(2, 3.0)));
    }

    @Test
    void probeCasesAreAllCovered() {
        assertEquals(19, PROBE.size(), PROBE.keySet().toString());
    }
}
