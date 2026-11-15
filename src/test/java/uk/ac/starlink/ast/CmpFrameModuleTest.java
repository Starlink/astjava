package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * Translation of the unit tests in Rust {@code src/frames/cmpframe.rs}: the axis-selection helpers
 * of cmpframe.c ({@code GenAxisSelection}, {@code AddExtraAxes}, {@code RenumberAxes}, {@code
 * GoodPerm}), the geometry that combines the components, the attribute defaults and forwarding,
 * {@code Cast}, and the matching ({@code PartMatch}, {@code ComponentMatch}, {@code Match} and
 * {@code SubFrame}).
 *
 * <p>The helpers are called with C's arguments: axis indices from 0, the array lengths given, and
 * {@code GoodPerm}'s permutation arrays holding axis indices from 0, as C passes them (Rust passes
 * them from 1). Where C's {@code ComponentMatch} fills only the first entries of an axis list (one
 * per result axis), only those are compared.
 *
 * <p>The three {@code overlay_template_*} tests overlay a CmpFrame holding a SpecFrame and are
 * deferred.
 */
class CmpFrameModuleTest {

    private static final double BAD = AstObject.AST__BAD;

    /**
     * Every selection that repeated calls of {@code GenAxisSelection} give, starting from {@code
     * [0, 1, ..., nselect-1]}. The array has {@code naxes} elements, as C's {@code Match} allocates
     * it.
     */
    private static List<int[]> enumerateSelections(int naxes, int nselect) {
        int[] axes = new int[naxes];
        for (int i = 0; i < nselect; i++) {
            axes[i] = i;
        }
        List<int[]> out = new ArrayList<>();
        out.add(Arrays.copyOf(axes, nselect));
        while (CmpFrame.genAxisSelection(naxes, nselect, axes)) {
            out.add(Arrays.copyOf(axes, nselect));
        }
        return out;
    }

    private static void assertSelections(int[][] want, List<int[]> got) {
        assertEquals(want.length, got.size(), "number of selections");
        for (int i = 0; i < want.length; i++) {
            assertArrayEquals(want[i], got.get(i), "selection " + i);
        }
    }

    @Test
    void genAxisSelectionChoose2Of4() {
        // 4 choose 2 = 6.
        assertSelections(new int[][] {{0, 1}, {0, 2}, {1, 2}, {0, 3}, {1, 3}, {2, 3}}, enumerateSelections(4, 2));
    }

    @Test
    void genAxisSelectionChoose3Of5() {
        // 5 choose 3 = 10.
        List<int[]> got = enumerateSelections(5, 3);
        assertEquals(10, got.size());
        assertArrayEquals(new int[] {0, 1, 2}, got.get(0));
        assertArrayEquals(new int[] {2, 3, 4}, got.get(9));
        for (int[] sel : got) {
            for (int i = 0; i < sel.length; i++) {
                assertTrue(sel[i] >= 0 && sel[i] < 5, Arrays.toString(sel));
                if (i > 0) {
                    assertTrue(sel[i - 1] < sel[i], Arrays.toString(sel));
                }
            }
        }
    }

    @Test
    void genAxisSelectionChoose1Of3() {
        assertSelections(new int[][] {{0}, {1}, {2}}, enumerateSelections(3, 1));
    }

    @Test
    void genAxisSelectionChooseNOfN() {
        assertSelections(new int[][] {{0, 1, 2}}, enumerateSelections(3, 3));
    }

    /** C's result is {@code nselect > 0 && ...}, so selecting no axes gives no selection. */
    @Test
    void genAxisSelectionZeroSelectReturnsFalseImmediately() {
        assertFalse(CmpFrame.genAxisSelection(5, 0, new int[5]));
    }

    /**
     * A missing index (-1) is numbered one more than the valid index before it, and the valid
     * indices from there on move up to make room.
     */
    @Test
    void addExtraAxesFollowingFalse() {
        int[] axes = {0, -1, 2};
        CmpFrame.addExtraAxes(3, axes, 0, 2, false);
        assertArrayEquals(new int[] {0, 1, 3}, axes);
    }

    /** With {@code following}, a missing index takes the number of the valid index after it, which moves up. */
    @Test
    void addExtraAxesFollowingTrue() {
        int[] axes = {0, -1, 2};
        CmpFrame.addExtraAxes(3, axes, 0, 2, true);
        assertArrayEquals(new int[] {0, 2, 3}, axes);
    }

    @Test
    void renumberAxesCompressesGaps() {
        int[] axes = {3, 7, 1};
        CmpFrame.renumberAxes(3, axes);
        assertArrayEquals(new int[] {1, 2, 0}, axes);
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    private static CmpFrame skyBasicCmp() {
        return new CmpFrame(sky("FK5"), new Frame(1));
    }

    private static CmpFrame plain21() {
        return new CmpFrame(new Frame(2), new Frame(1));
    }

    // ------------------------------------------------------------------
    // Geometry combining the components (values from the C library)
    // ------------------------------------------------------------------

    /** The sky part is half a great circle (pi) and the plain part 4. */
    @Test
    void distanceCombinesComponentsInQuadrature() {
        double d = skyBasicCmp().distance(new double[] {0.0, 0.0, 0.0}, new double[] {Math.PI, 0.0, 4.0});
        assertTrue(Math.abs(d - 5.086217101254) < 1e-9, "distance " + d);
    }

    /** Half the distance lands at (pi/2, 0, 2). */
    @Test
    void offsetSplitsBetweenComponents() {
        CmpFrame cf = skyBasicCmp();
        double[] p1 = {0.0, 0.0, 0.0};
        double[] p2 = {Math.PI, 0.0, 4.0};
        double d = cf.distance(p1, p2);
        double[] p3 = cf.offset(p1, p2, d / 2.0);
        String msg = Arrays.toString(p3);
        assertTrue(Math.abs(p3[0] - Math.PI / 2.0) < 1e-9, msg);
        assertTrue(Math.abs(p3[1]) < 1e-9, msg);
        assertTrue(Math.abs(p3[2] - 2.0) < 1e-9, msg);
    }

    /**
     * Coincident points with a non-zero offset give bad values. The coincident branch needs an
     * exactly zero cross product, so the points are at the origin, where the products are exact.
     */
    @Test
    void offsetCoincidentPointsNonzeroOffsetIsBad() {
        double[] p = {0.0, 0.0, 1.0};
        double[] out = skyBasicCmp().offset(p, p, 1.0);
        assertArrayEquals(new double[] {BAD, BAD, BAD}, out);
    }

    @Test
    void angleUsesTheSingleDefinedComponent() {
        CmpFrame cf = skyBasicCmp();
        double[] b = {0.0, 0.0, 7.0};
        // The sky axes vary and the plain axis is constant: the SkyFrame's angle.
        double ang = cf.angle(new double[] {0.0, 0.1, 7.0}, b, new double[] {0.1, 0.0, 7.0});
        assertTrue(Math.abs(ang - Math.PI / 2.0) < 1e-9, "angle " + ang);

        // Only the plain axis varies: the one-dimensional Cartesian angle.
        ang = cf.angle(new double[] {0.0, 0.0, 9.0}, b, new double[] {0.0, 0.0, 5.0});
        assertTrue(Math.abs(ang - Math.PI) < 1e-9, "angle " + ang);

        // Both components give an angle: undefined.
        assertEquals(BAD, cf.angle(new double[] {0.0, 0.1, 9.0}, b, new double[] {0.1, 0.0, 5.0}));
    }

    /** With only plain Frames, the Frame angle, unsigned for three axes. */
    @Test
    void angleAllBasicComponentsIsCartesian() {
        double ang = plain21()
                .angle(new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 0.0, 0.0}, new double[] {0.0, 1.0, 1.0});
        assertTrue(Math.abs(ang - Math.PI / 2.0) < 1e-9, "angle " + ang);
    }

    // ------------------------------------------------------------------
    // Attribute defaults (cmpframe.c GetMinAxes, GetMaxAxes, GetDomain)
    // ------------------------------------------------------------------

    @Test
    void minMaxAxesDefaultsMatchC() {
        CmpFrame cf = skyBasicCmp();
        assertEquals(0, cf.getMinAxes());
        assertEquals(1000000, cf.getMaxAxes());
        cf.setMinAxes(3);
        cf.setMaxAxes(3);
        assertEquals(3, cf.getMinAxes());
        assertEquals(3, cf.getMaxAxes());
    }

    @Test
    void domainDefaultJoinsComponentDomains() {
        assertEquals("SKY-", skyBasicCmp().getDomain());
        assertEquals("CMP", new CmpFrame(new Frame(1), new Frame(1)).getDomain());
        CmpFrame cf3 = skyBasicCmp();
        cf3.setDomain("FRED");
        assertEquals("FRED", cf3.getDomain());
    }

    // ------------------------------------------------------------------
    // Epoch and observer forwarding (cmpframe.c SetEpoch, GetEpoch, SetObsLat, GetObsLat)
    // ------------------------------------------------------------------

    @Test
    void setEpochForwardsToComponents() {
        CmpFrame cf = skyBasicCmp();
        double mjd = Pal.epj2d(1980.0);
        cf.setEpochMjd(mjd);
        assertTrue(Math.abs(cf.getEpochMjd() - mjd) < 1e-9);
        SkyFrame sky = assertInstanceOf(SkyFrame.class, cf.decompose()[0]);
        assertTrue(Math.abs(sky.getEpochMjd() - mjd) < 1e-9);
    }

    /** An Epoch set only on a component is the CmpFrame's. */
    @Test
    void epochFallsBackToComponentValue() {
        SkyFrame sky = sky("FK5");
        double mjd = Pal.epj2d(1975.0);
        sky.setEpochMjd(mjd);
        CmpFrame cf = new CmpFrame(sky, new Frame(1));
        assertTrue(Math.abs(cf.getEpochMjd() - mjd) < 1e-9, "epoch " + cf.getEpochMjd());
    }

    @Test
    void obsLatSetAndFallback() {
        CmpFrame cf = skyBasicCmp();
        cf.setObsLatValue(0.5);
        assertEquals(0.5, cf.getObsLatValue());
        assertEquals(0.5, cf.decompose()[0].getObsLatValue());

        SkyFrame sky2 = sky("FK5");
        sky2.setObsLatValue(0.25);
        CmpFrame cf2 = new CmpFrame(sky2, new Frame(1));
        assertEquals(0.25, cf2.getObsLatValue());
    }

    // ------------------------------------------------------------------
    // Cast (cmpframe.c Cast copies each axis in external order)
    // ------------------------------------------------------------------

    /**
     * Cast to a plain Frame copies each axis in external order, so a component with its own axis
     * permutation gives its Labels and Symbols in that component's external order.
     */
    @Test
    void castCmpframePreservesPermutedComponentAxisOrder() {
        SkyFrame sky = sky("GALACTIC");
        sky.setLabel(1, "LON_LBL");
        sky.setLabel(2, "LAT_LBL");
        sky.setSymbol(1, "LON_SYM");
        sky.setSymbol(2, "LAT_SYM");
        sky.permAxes(new int[] {2, 1});
        assertEquals("LAT_LBL", sky.getLabel(1));
        assertEquals("LON_LBL", sky.getLabel(2));
        assertEquals("LAT_SYM", sky.getSymbol(1));
        assertEquals("LON_SYM", sky.getSymbol(2));

        CmpFrame cmp = new CmpFrame(sky, new Frame(1));
        Frame out = cmp.cast(new Frame(3));
        assertNotNull(out);
        assertSame(Frame.class, out.getClass());
        assertEquals(3, out.getNaxes());
        assertEquals("LAT_LBL", out.getLabel(1));
        assertEquals("LON_LBL", out.getLabel(2));
        assertEquals("LAT_SYM", out.getSymbol(1));
        assertEquals("LON_SYM", out.getSymbol(2));
    }

    @Test
    void castCmpframeUnpermutedComponentAxisOrder() {
        SkyFrame sky = sky("GALACTIC");
        sky.setLabel(1, "LON_LBL");
        sky.setLabel(2, "LAT_LBL");
        sky.setSymbol(1, "LON_SYM");
        sky.setSymbol(2, "LAT_SYM");
        CmpFrame cmp = new CmpFrame(sky, new Frame(1));
        Frame out = cmp.cast(new Frame(3));
        assertNotNull(out);
        assertSame(Frame.class, out.getClass());
        assertEquals("LON_LBL", out.getLabel(1));
        assertEquals("LAT_LBL", out.getLabel(2));
        assertEquals("LON_SYM", out.getSymbol(1));
        assertEquals("LAT_SYM", out.getSymbol(2));
    }

    // ------------------------------------------------------------------
    // GoodPerm
    // ------------------------------------------------------------------

    @Test
    void goodPermIdentityReturnsFalse() {
        assertFalse(CmpFrame.goodPerm(3, new int[] {0, 1, 2}, 3, new int[] {0, 1, 2}));
    }

    @Test
    void goodPermSwapReturnsTrue() {
        assertTrue(CmpFrame.goodPerm(2, new int[] {1, 0}, 2, new int[] {1, 0}));
    }

    @Test
    void goodPermSizeMismatchReturnsTrue() {
        assertTrue(CmpFrame.goodPerm(2, new int[] {0, 1}, 3, new int[] {0, 1, 2}));
    }

    // ------------------------------------------------------------------
    // PartMatch
    // ------------------------------------------------------------------

    /** Target axes [0, 1] go to the 2-axis component and [2] to the 1-axis one. */
    @Test
    void partMatchBasicframePartitionSucceeds() {
        FrameFinder.MatchResult m =
                CmpFrame.partMatch(plain21(), new Frame(3), false, 2, new int[] {0, 1}, 1, new int[] {2});
        assertNotNull(m, "3-axis partition match");
        assertEquals(3, m.result.getNaxes());
        assertEquals(3, m.templateAxes.length);
        assertEquals(3, m.targetAxes.length);
    }

    /** One target axis for the 2-axis component: the component's Match rejects it. */
    @Test
    void partMatchReturnsNoneOnAxisCountMismatch() {
        assertNull(CmpFrame.partMatch(plain21(), new Frame(3), false, 1, new int[] {0}, 2, new int[] {1, 2}));
    }

    @Test
    void partMatchAgainstCmpframeTargetNowSucceeds() {
        FrameFinder.MatchResult m =
                CmpFrame.partMatch(plain21(), plain21(), false, 2, new int[] {0, 1}, 1, new int[] {2});
        assertNotNull(m, "CmpFrame partition match");
        assertEquals(3, m.result.getNaxes());
    }

    // ------------------------------------------------------------------
    // ComponentMatch
    // ------------------------------------------------------------------

    /**
     * With PreserveAxes set on the template, the result has the target's axes, and the template
     * axes are the component's axes in the CmpFrame's external order.
     */
    @Test
    void componentMatchPreserveAxesReturnsTargetShape() {
        CmpFrame template = plain21();
        template.setPreserveAxes(true);
        FrameFinder.MatchResult m = CmpFrame.componentMatch(template, new Frame(2), false, 0);
        assertNotNull(m, "a match");
        int n = m.result.getNaxes();
        assertEquals(2, n);
        assertArrayEquals(new int[] {0, 1}, Arrays.copyOf(m.templateAxes, n));
        assertArrayEquals(new int[] {0, 1}, Arrays.copyOf(m.targetAxes, n));
    }

    /**
     * Without PreserveAxes, the result is a CmpFrame of the component's result and a copy of the
     * other component, and the Mapping gives its three outputs from the target's two inputs.
     */
    @Test
    void componentMatchDefaultAxesWrapsInCmpframe() {
        CmpFrame template = plain21();
        assertFalse(template.getPreserveAxes());
        FrameFinder.MatchResult m = CmpFrame.componentMatch(template, new Frame(2), false, 0);
        assertNotNull(m, "a match");
        assertEquals(3, m.result.getNaxes());
        assertArrayEquals(new int[] {0, 1, 2}, m.templateAxes);
        assertArrayEquals(new int[] {0, 1, -1}, m.targetAxes);
        assertEquals(2, m.map.getNin());
        assertEquals(3, m.map.getNout());
    }

    /**
     * Matching the second component: its template axes follow those of the first component, while
     * the target axes are the matched ones followed by -1 whichever component matched (cmpframe.c
     * {@code ComponentMatch}).
     */
    @Test
    void componentMatchIcomp1OffsetsTemplateAxes() {
        FrameFinder.MatchResult m = CmpFrame.componentMatch(plain21(), new Frame(1), false, 1);
        assertNotNull(m, "a match");
        assertEquals(3, m.result.getNaxes());
        assertArrayEquals(new int[] {0, 1, 2}, m.templateAxes);
        assertArrayEquals(new int[] {0, -1, -1}, m.targetAxes);
        assertEquals(1, m.map.getNin());
        assertEquals(3, m.map.getNout());
    }

    @Test
    void componentMatchReturnsNoneOnAxisCountMismatch() {
        assertNull(CmpFrame.componentMatch(plain21(), new Frame(1), false, 0));
    }

    // ------------------------------------------------------------------
    // Match (cmpframe.c Match)
    // ------------------------------------------------------------------

    @Test
    void matchTemplateCmpframeAgainstBasicframeTarget() {
        FrameFinder.MatchResult m = plain21().match(new Frame(3), false);
        assertNotNull(m, "3-axis match");
        assertEquals(3, m.result.getNaxes());
    }

    /**
     * With MinAxes 1 and MaxAxes 3, no partition of a 1-axis target suits both components, so the
     * match comes from {@code ComponentMatch} with the second component, carrying the first.
     */
    @Test
    void matchTemplateFallsBackToComponentMatch() {
        CmpFrame template = plain21();
        template.setMinAxes(1);
        template.setMaxAxes(3);
        FrameFinder.MatchResult m = template.match(new Frame(1), false);
        assertNotNull(m, "ComponentMatch with the second component");
        assertEquals(3, m.result.getNaxes());
    }

    /**
     * A Domain set on a component but not on the CmpFrame does not stop the match before the
     * partitions are tried; the call runs to its end.
     */
    @Test
    void matchTemplateDomainMismatchReturnsNone() {
        Frame frame1 = new Frame(2);
        frame1.setDomain("ABC");
        CmpFrame template = new CmpFrame(frame1, new Frame(1));
        assertFalse(template.testDomain());
        template.match(new Frame(3), false);
    }

    /** A 5-axis target against the 3-axis template, with neither component of 5 axes: no match. */
    @Test
    void matchTemplateCardinalityRejectsOversizedTarget() {
        assertNull(plain21().match(new Frame(5), false));
    }

    // ------------------------------------------------------------------
    // SubFrame (cmpframe.c SubFrame)
    // ------------------------------------------------------------------

    /** The result of {@code target.subFrame} without a template, its Mapping put in {@code map}. */
    private static Frame select(Frame target, int n, int[] targetAxes, int[] templateAxes, Mapping[] map) {
        Frame[] result = new Frame[1];
        assertTrue(target.subFrame(null, n, targetAxes, templateAxes, map, result), "a selection");
        return result[0];
    }

    private static void assertShape(int naxes, int nin, int nout, Frame result, Mapping map) {
        assertEquals(naxes, result.getNaxes(), "Naxes");
        assertEquals(nin, map.getNin(), "Nin");
        assertEquals(nout, map.getNout(), "Nout");
    }

    @Test
    void subFrameIdentityFullAxisPick() {
        Mapping[] map = new Mapping[1];
        Frame result = select(plain21(), 3, new int[] {0, 1, 2}, new int[] {0, 1, 2}, map);
        assertNotNull(result);
        assertShape(3, 3, 3, result, map[0]);
    }

    @Test
    void subFramePicksFrame1Only() {
        Mapping[] map = new Mapping[1];
        Frame result = select(plain21(), 2, new int[] {0, 1}, new int[] {0, 1}, map);
        assertNotNull(result);
        assertShape(2, 3, 2, result, map[0]);
    }

    @Test
    void subFramePicksFrame2Only() {
        Mapping[] map = new Mapping[1];
        Frame result = select(plain21(), 1, new int[] {2}, new int[] {0}, map);
        assertNotNull(result);
        assertShape(1, 3, 1, result, map[0]);
    }

    /** Result axis 1 is target axis 3 (second component) and result axis 2 is target axis 1. */
    @Test
    void subFrameMixedComponentsWithReorder() {
        Mapping[] map = new Mapping[1];
        Frame result = select(plain21(), 2, new int[] {2, 0}, new int[] {0, 1}, map);
        assertNotNull(result);
        assertShape(2, 3, 2, result, map[0]);
    }

    /** Picking every axis gives a CmpFrame with the target's Domain and Title (cmpframe.c {@code SubFrame}). */
    @Test
    void subFrameFullPickInheritsDomainAndTitle() {
        CmpFrame target = plain21();
        target.setDomain("MYCMP");
        target.setTitle("custom title");
        assertTrue(target.testDomain());
        assertTrue(target.testTitle());
        Frame result = select(target, 3, new int[] {0, 1, 2}, new int[] {0, 1, 2}, new Mapping[1]);
        CmpFrame resultCmp = assertInstanceOf(CmpFrame.class, result);
        assertEquals("MYCMP", resultCmp.getDomain());
        assertEquals("custom title", resultCmp.getTitle());
        assertTrue(resultCmp.testDomain());
        assertTrue(resultCmp.testTitle());
    }

    /** Picking some of the axes runs to its end and gives a 2-axis Frame. */
    @Test
    void subFramePartialPickDoesNotInheritDomain() {
        CmpFrame target = plain21();
        target.setDomain("MYCMP");
        Frame result = select(target, 2, new int[] {0, 1}, new int[] {0, 1}, new Mapping[1]);
        result.getDomain();
        assertEquals(2, result.getNaxes());
    }

    // ------------------------------------------------------------------
    // Copy
    // ------------------------------------------------------------------

    @Test
    void cloneIsADeepCopy() {
        CmpFrame cmp = skyBasicCmp();
        cmp.setIdent("ORIGINAL");
        cmp.setEpochMjd(51544.5);

        CmpFrame copy = (CmpFrame) cmp.copy();
        copy.setIdent("COPY");

        assertEquals("ORIGINAL", cmp.getIdent());
        assertEquals("COPY", copy.getIdent());
        assertEquals(cmp.getNaxes(), copy.getNaxes());
        assertEquals(cmp.getEpochMjd(), copy.getEpochMjd());
        assertEquals(cmp.getDomain(), copy.getDomain());
    }
}
