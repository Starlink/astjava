package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Overlap and masking of Boxes, and the negated copy a Region keeps, against C ({@code
 * probes/region_overlap_mask_probe.c}).
 *
 * <p>Overlap: Boxes beside, inside, across, touching (closed and open), equal, equal to within
 * their uncertainties, negated, in Frames joined by a unit change or a rotation, and in Frames with
 * no conversion, each pair in both orders. region.c {@code OverlapX} negates the first Region in
 * place to compare it with the second, so that after a comparison that gets that far, Negated is
 * set on both Regions, as in C.
 *
 * <p>Masking: {@link Region#maskD} and the other types, inside and outside, negated or not, with
 * no Mapping and through a ZoomMap, partly and wholly off the grid, in one and three dimensions,
 * and C's errors. region.c {@code Mask<X>} negates the Region it masks with in place when it masks
 * the inside, and with no Mapping that is the Region given, so that Negated is set on it
 * afterwards, as in C.
 *
 * <p>The negated copy a Region keeps (region.c {@code GetNegation}) is not discarded when a Frame
 * attribute of an Adaptive Region changes, as in C, so it describes the Region in its old
 * coordinate system; on a Region that is not Adaptive the change discards it.
 *
 * <p>Every value compared is a count, a code, a small whole number, a flag or a message, so the
 * comparisons are exact.
 */
class RegionOverlapMaskProbeTest {

    private static final Map<String, String> PROBE = ProbeOutput.load("region_overlap_mask_probe");

    private static final int[] G1 = {1, 1};
    private static final int[] G6 = {6, 6};

    private static Region box2(Frame f, double x1, double y1, double x2, double y2) {
        return new Box(f, 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static Region unit2(double x1, double y1, double x2, double y2) {
        return box2(new Frame(2), x1, y1, x2, y2);
    }

    private static Region negated(Region r) {
        r.negate();
        return r;
    }

    private static int flag(Region r) {
        return r.test("Negated") ? 1 : 0;
    }

    private static String overlapLine(Region a, Region b) {
        int ab = a.overlap(b);
        int ba = b.overlap(a);
        return "overlap " + ab + " " + ba + " " + flag(a) + " " + flag(b) + "\n";
    }

    private static void checkOverlap(String name, Region a, Region b) {
        assertEquals(PROBE.get(name), overlapLine(a, b), name);
    }

    private static Frame unitFrame(String unit) {
        Frame f = new Frame(2);
        f.setUnit(1, unit);
        f.setUnit(2, unit);
        f.setActiveUnit(true);
        return f;
    }

    /** The probe's Box [-1, 1]^2 rotated by 0.5 radians, by the protected astMapRegion. */
    private static Region rotated() {
        double c = Math.cos(0.5);
        double s = Math.sin(0.5);
        Mapping rot = new MatrixMap(2, 2, new double[][] {{c, -s}, {s, c}});
        return unit2(-1, -1, 1, 1).mapRegionInto(rot, new Frame(2));
    }

    @Test
    void overlapCodesMatchC() {
        checkOverlap("beside", unit2(0, 0, 1, 1), unit2(2, 0, 3, 1));
        checkOverlap("inside", unit2(0, 0, 1, 1), unit2(0.25, 0.25, 0.75, 0.75));
        checkOverlap("across", unit2(0, 0, 1, 1), unit2(0.5, 0.5, 1.5, 1.5));
        checkOverlap("touching_closed", unit2(0, 0, 1, 1), unit2(1, 0, 2, 1));
        Region a = unit2(0, 0, 1, 1);
        Region b = unit2(1, 0, 2, 1);
        a.setClosed(false);
        b.setClosed(false);
        checkOverlap("touching_open", a, b);
        checkOverlap("same", unit2(0, 0, 1, 1), unit2(0, 0, 1, 1));
        checkOverlap("same_within_uncertainty", unit2(0, 0, 1, 1), unit2(0, 0, 1.0 + 1.0e-8, 1));
        checkOverlap("beyond_uncertainty", unit2(0, 0, 1, 1), unit2(0, 0, 1.0 + 1.0e-4, 1));
        checkOverlap("negated", negated(unit2(0, 0, 1, 1)), unit2(0, 0, 1, 1));
        checkOverlap("negated_within_uncertainty", negated(unit2(0, 0, 1, 1)), unit2(0, 0, 1.0 + 1.0e-8, 1));
        checkOverlap("negated_around_inside", negated(unit2(0, 0, 1, 1)), unit2(0.25, 0.25, 0.75, 0.75));
        checkOverlap("negated_around_beside", negated(unit2(0, 0, 1, 1)), unit2(2, 0, 3, 1));
        checkOverlap("negated_around_across", negated(unit2(0, 0, 1, 1)), unit2(0.5, 0.5, 1.5, 1.5));
        checkOverlap("both_negated", negated(unit2(0, 0, 1, 1)), negated(unit2(0.25, 0.25, 0.75, 0.75)));
    }

    @Test
    void overlapAcrossFramesMatchesC() {
        Region metres = box2(unitFrame("m"), 0, 0, 1000, 1000);
        checkOverlap("units_inside", metres, box2(unitFrame("km"), 0.25, 0.25, 0.75, 0.75));
        checkOverlap("units_same", metres, box2(unitFrame("km"), 0, 0, 1, 1));
        checkOverlap("rotated_inside", rotated(), unit2(-0.5, -0.5, 0.5, 0.5));
        checkOverlap("rotated_across", rotated(), unit2(0.9, -0.2, 2, 0.2));
        checkOverlap("rotated_around", rotated(), unit2(-3, -3, 3, 3));
        checkOverlap("rotated_same", rotated(), rotated());
        checkOverlap(
                "three_d_across",
                new Box(new Frame(3), 1, new double[] {0, 0, 0}, new double[] {1, 1, 1}, null),
                new Box(new Frame(3), 1, new double[] {0.5, 0.5, -1}, new double[] {2, 2, 0.5}, null));
        Frame aaa = new Frame(2);
        aaa.setDomain("AAA");
        Frame bbb = new Frame(2);
        bbb.setDomain("BBB");
        checkOverlap("domains", box2(aaa, 0, 0, 1, 1), box2(bbb, 0, 0, 1, 1));
    }

    /** What the probe writes for a mask: the count, the values and the Region's Negated flag. */
    private static String maskLine(int count, double[] d, Region r) {
        StringBuilder t = new StringBuilder("mask ").append(count);
        for (double v : d) {
            t.append(' ').append(CFormat.format("%.17g", v));
        }
        return t.append(' ').append(flag(r)).append('\n').toString();
    }

    private static int npixel(int ndim, int[] lbnd, int[] ubnd) {
        int n = 1;
        for (int i = 0; i < ndim; i++) {
            n *= Math.abs(ubnd[i] - lbnd[i]) + 1;
        }
        return n;
    }

    private static double[] prefilled(int n) {
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = i % 3;
        }
        return d;
    }

    /** {@link Region#maskD} of a copy of {@code r0} with value 9, as the probe's {@code maskd}. */
    private static void checkMaskD(String name, Region r0, Mapping map, boolean inside, int ndim, int[] lb, int[] ub) {
        Region r = (Region) r0.copy();
        double[] d = prefilled(npixel(ndim, lb, ub));
        int count = r.maskD(map, inside, ndim, lb, ub, d, 9.0);
        assertEquals(PROBE.get(name), maskLine(count, d, r), name);
    }

    /** The status and messages C reports for a failed case. */
    private static void checkError(String name, AstException e) {
        List<String> messages = new ArrayList<>();
        String status = null;
        for (String line : PROBE.get(name).split("\n", -1)) {
            if (line.startsWith("!  ")) {
                messages.add(line.substring(3));
            } else if (line.startsWith("status ")) {
                status = line.substring(7);
            }
        }
        assertEquals(status, Integer.toString(e.getStatus()), name + ": status");
        assertEquals(String.join("\n", messages), e.getMsgText(), name + ": message");
    }

    @Test
    void masksMatchC() {
        Region a = unit2(2, 2, 4, 4);
        checkMaskD("box_inside", a, null, true, 2, G1, G6);
        checkMaskD("box_outside", a, null, false, 2, G1, G6);
        Region b = negated((Region) a.copy());
        checkMaskD("negated_box_inside", b, null, true, 2, G1, G6);
        checkMaskD("negated_box_outside", b, null, false, 2, G1, G6);
        Mapping zoom = new ZoomMap(2, 2.0);
        checkMaskD("zoomed_inside", unit2(1, 1, 2, 2), zoom, true, 2, G1, G6);
        checkMaskD("zoomed_outside", unit2(1, 1, 2, 2), zoom, false, 2, G1, G6);
        checkMaskD("off_edge_inside", unit2(4, -3, 9, 2), null, true, 2, G1, G6);
        checkMaskD("off_edge_outside", unit2(4, -3, 9, 2), null, false, 2, G1, G6);
        checkMaskD("off_grid_inside", unit2(20, 20, 30, 30), null, true, 2, G1, G6);
        checkMaskD("off_grid_outside", unit2(20, 20, 30, 30), null, false, 2, G1, G6);
        Region off = negated(unit2(20, 20, 30, 30));
        checkMaskD("negated_off_grid_inside", off, null, true, 2, G1, G6);
        checkMaskD("negated_off_grid_outside", off, null, false, 2, G1, G6);
        checkMaskD("grid_inside_box", unit2(-10, -10, 10, 10), null, true, 2, G1, G6);
        checkMaskD("half_pixel_edges", unit2(1.5, 2.5, 3.5, 4.5), null, true, 2, G1, G6);
        Region oneD = new Box(new Frame(1), 1, new double[] {3.0}, new double[] {7.0}, null);
        checkMaskD("one_d", oneD, null, true, 1, new int[] {1}, new int[] {10});
        Region threeD = new Box(new Frame(3), 1, new double[] {0, 0, 0}, new double[] {1, 1, 1}, null);
        checkMaskD("three_d", threeD, null, false, 3, new int[] {1, 1, 1}, new int[] {4, 4, 4});
        checkMaskD("single_row", a, null, true, 2, new int[] {1, 2}, new int[] {1, 2});
    }

    /** The probe's line for a mask of {@code data}, an array of a primitive type, as doubles. */
    private static String maskLine(int count, Object data, Region r) {
        double[] d = new double[Array.getLength(data)];
        for (int i = 0; i < d.length; i++) {
            d[i] = Array.getDouble(data, i);
        }
        return maskLine(count, d, r);
    }

    @Test
    void gridBoundsBeyondTheIntRangeMatchC() {
        // The ZoomMap takes the Box's bounds to +/-1e12 in grid coordinates, beyond the int range,
        // and C's Mask<X> then finds an empty grid box (region.c Mask<X>, C bug): every pixel is
        // inside the Box, but masking its outside sets all 16.
        checkMaskD("huge_zoom_outside", unit2(-1, -1, 1, 1), new ZoomMap(2, 1.0e12), false, 2, G1, new int[] {4, 4});
    }

    @Test
    void masksOfEachTypeMatchC() {
        Region a = unit2(2, 2, 4, 4);
        int n = 36;

        Region r = (Region) a.copy();
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            f[i] = i % 3;
        }
        int count = r.maskF(null, true, 2, G1, G6, f, 9.0f);
        assertEquals(PROBE.get("float_inside"), maskLine(count, f, r), "float_inside");

        r = (Region) a.copy();
        long[] l = new long[n];
        for (int i = 0; i < n; i++) {
            l[i] = i % 3;
        }
        count = r.maskL(null, false, 2, G1, G6, l, 9L);
        assertEquals(PROBE.get("long_outside"), maskLine(count, l, r), "long_outside");

        r = (Region) a.copy();
        int[] ii = new int[n];
        for (int i = 0; i < n; i++) {
            ii[i] = i % 3;
        }
        count = r.maskI(null, true, 2, G1, G6, ii, 9);
        assertEquals(PROBE.get("int_inside"), maskLine(count, ii, r), "int_inside");

        r = (Region) a.copy();
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) (i % 3);
        }
        count = r.maskS(null, false, 2, G1, G6, s, (short) 9);
        assertEquals(PROBE.get("short_outside"), maskLine(count, s, r), "short_outside");

        r = (Region) a.copy();
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 3);
        }
        count = r.maskB(null, true, 2, G1, G6, b, (byte) 9);
        assertEquals(PROBE.get("byte_inside"), maskLine(count, b, r), "byte_inside");
    }

    @Test
    void maskErrorsMatchC() {
        Region a = unit2(2, 2, 4, 4);
        double[] d = new double[36];
        checkError(
                "map_wrong_inputs",
                assertThrows(AstException.class, () -> a.maskD(new UnitMap(3), true, 2, G1, G6, d, 9.0)));
        Mapping perm = new PermMap(2, null, 3, null, null);
        checkError("map_wrong_outputs", assertThrows(AstException.class, () -> a.maskD(perm, true, 2, G1, G6, d, 9.0)));
        checkError(
                "wrong_ndim",
                assertThrows(AstException.class, () -> a.maskD(null, true, 1, new int[] {1}, new int[] {10}, d, 9.0)));
        Region oneD = new Box(new Frame(1), 1, new double[] {3.0}, new double[] {7.0}, null);
        checkError(
                "zero_ndim",
                assertThrows(
                        AstException.class, () -> oneD.maskD(null, true, 0, new int[] {1}, new int[] {10}, d, 9.0)));
        checkError(
                "lower_above_upper",
                assertThrows(
                        AstException.class, () -> a.maskD(null, true, 2, new int[] {4, 1}, new int[] {3, 6}, d, 9.0)));
    }

    /** The probe's line for the negated copy {@code box} keeps, compared with {@code kept}. */
    // The probe compares the objects themselves (astSame), not their contents.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static String negationLine(Region box, Region kept) {
        Region neg = box.getNegation();
        double[] out = neg.tran1(1, new double[] {5.0}, true);
        return "negation " + (neg == kept ? 1 : 0) + " " + neg.getC("System") + " " + neg.getI("Negated") + " "
                + (out[0] != AstObject.AST__BAD ? 1 : 0) + "\n";
    }

    private static String stale(boolean adaptive) {
        SpecFrame f = new SpecFrame();
        f.setSystem("FREQ");
        f.setUnit(1, "GHz");
        Region box = new Box(f, 1, new double[] {4.0}, new double[] {6.0}, null);
        box.setAdaptive(adaptive);
        Region before = box.getNegation();
        String text = negationLine(box, null);
        box.setSystem("WAVE");
        return text + negationLine(box, before);
    }

    @Test
    void keptNegationMatchesC() {
        assertEquals(PROBE.get("negation_adaptive"), stale(true));
        assertEquals(PROBE.get("negation_not_adaptive"), stale(false));
    }
}
