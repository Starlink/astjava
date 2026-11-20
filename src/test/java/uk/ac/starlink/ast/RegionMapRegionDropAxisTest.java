package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Mapping and masking a 2-D Region through a PermMap to one pixel axis whose inverse leaves the
 * axis it drops bad, against C ({@code probes/region_mapregion_drop_axis_probe.c}).
 *
 * <p>region.c {@code MapRegion} then picks the Region of the axis kept and uses the Mapping {@code
 * astMapSplit} splits off. C releases that Mapping twice, so that {@code astMapRegion} fails with
 * {@code AST__OBJIN}; the probe is built with the second release guarded, and its mapped Regions
 * and masks are what {@link Region#mapRegionInto} and {@link Region#maskI} give (see
 * docs/rust-c-differences.md, "Releasing the split Mapping twice in {@code MapRegion}").
 *
 * <p>Every value compared is a count, a flag, a class name or a bound that is a whole or half
 * number, so the comparisons are exact.
 */
class RegionMapRegionDropAxisTest {

    private static final Map<String, String> PROBE = ProbeOutput.load("region_mapregion_drop_axis_probe");

    /** PermMap( 2, {1,0}, 1, {1} ): the first axis, whose inverse leaves the second bad. */
    private static PermMap keepFirst() {
        return new PermMap(2, new int[] {1, 0}, 1, new int[] {1}, null);
    }

    /** PermMap( 2, {0,1}, 1, {2} ): the second axis, whose inverse leaves the first bad. */
    private static PermMap keepSecond() {
        return new PermMap(2, new int[] {0, 1}, 1, new int[] {2}, null);
    }

    private static Region box() {
        return new Box(new Frame(2), 1, new double[] {1.5, 2.5}, new double[] {3.5, 4.5}, null);
    }

    /** The points (2,3), (4,1) and (3,5). */
    private static Region pointList() {
        return new PointList(new Frame(2), 3, new double[][] {{2, 4, 3}, {3, 1, 5}}, null);
    }

    private static Frame grid() {
        Frame f = new Frame(1);
        f.setDomain("GRID");
        return f;
    }

    /** {@link Region#maskI} of a copy of {@code r0} on pixels 1 to 6, as the probe writes it. */
    private static String maskLine(Region r0, Mapping map, boolean inside) {
        Region r = (Region) r0.copy();
        int[] d = new int[6];
        int count = r.maskI(map, inside, 1, new int[] {1}, new int[] {6}, d, 1);
        StringBuilder sb = new StringBuilder("mask ").append(count);
        for (int v : d) {
            sb.append(' ').append(v);
        }
        return sb.append('\n').toString();
    }

    /** The probe's lines for a case: the mapped Region's class and bounds, then the two masks. */
    private static String caseText(Region r, Mapping map) {
        Region m = r.mapRegionInto(map, grid());
        return "mapped " + m.astClass() + "\n" + RegionProbeText.bounds(m) + maskLine(r, map, false)
                + maskLine(r, map, true);
    }

    @Test
    void boxMatchesC() {
        RegionProbeText.check(PROBE, "box_keep_first", caseText(box(), keepFirst()));
        RegionProbeText.check(PROBE, "box_keep_second", caseText(box(), keepSecond()));
    }

    @Test
    void pointListMatchesC() {
        RegionProbeText.check(PROBE, "pointlist_keep_first", caseText(pointList(), keepFirst()));
        RegionProbeText.check(PROBE, "pointlist_keep_second", caseText(pointList(), keepSecond()));
    }

    @Test
    void mappingIsLeftAsGiven() {
        PermMap map = keepFirst();
        box().mapRegionInto(map, grid());
        maskLine(box(), map, true);
        assertFalse(map.getInvert());
        assertEquals(2, map.getNin());
    }

    /**
     * The axis picked from a Circle is a Frame, not a Region. C (with the second release guarded)
     * goes on to use that Frame as a Region; Java reports {@code AST__INTER} instead, a departure
     * from C (docs/rust-c-differences.md, "Picking axes of a Region in {@code MapRegion}").
     */
    @Test
    void circleReportsInternalError() {
        Region circle = new Circle(new Frame(2), new double[] {3, 3.5}, 1.6, null);
        AstException e = assertThrows(AstException.class, () -> circle.mapRegionInto(keepFirst(), grid()));
        assertEquals(AstException.AST__INTER, e.getStatus());
        AstException m = assertThrows(AstException.class, () -> maskLine(circle, keepSecond(), true));
        assertEquals(AstException.AST__INTER, m.getStatus());
    }
}
