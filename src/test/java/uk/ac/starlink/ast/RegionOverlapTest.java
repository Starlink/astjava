package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.CompoundProbe.BAD;
import static uk.ac.starlink.ast.CompoundProbe.boxc;
import static uk.ac.starlink.ast.CompoundProbe.check;
import static uk.ac.starlink.ast.CompoundProbe.circle2;
import static uk.ac.starlink.ast.CompoundProbe.negatedCopy;
import static uk.ac.starlink.ast.CompoundProbe.overlap;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_overlap.rs}: {@link Region#overlap} of Circles, Boxes and
 * NullRegions, separate, nested, partly overlapping, identical and negated, each pair in both
 * orders, with C's codes from {@code probes/region_compound_probe.c}; and an Interval with no
 * boundary mesh against Circles, for which region.c {@code OverlapX} meshes the part of the
 * Interval's boundary inside a box with a CmpRegion (region.c {@code BndBaseMesh}).
 *
 * <p>The Circles' meshes come from {@code java.lang.Math}'s {@code sin} and {@code cos}, which may
 * differ from C's libm in the last place. Where a code says one Region is inside, outside or apart
 * from the other, every mesh point is at least 0.5 from the other's boundary; where it says they
 * overlap partly, many mesh points lie well inside and many well outside, so a point that rounding
 * could move across a boundary does not change the code.
 */
class RegionOverlapTest {

    private static final double[] ZERO2 = {0.0, 0.0};

    private static NullRegion nullRegion() {
        return new NullRegion(new Frame(2), null);
    }

    // ---- Translation of test_overlap.rs ----

    @Test
    void identicalCirclesSame() {
        assertEquals(Region.OVERLAP_SAME, circle2(0.0, 0.0, 5.0).overlap(circle2(0.0, 0.0, 5.0)));
    }

    @Test
    void separatedCirclesNone() {
        assertEquals(Region.OVERLAP_NONE, circle2(0.0, 0.0, 2.0).overlap(circle2(10.0, 0.0, 2.0)));
    }

    @Test
    void smallCircleInsideLarge() {
        assertEquals(Region.OVERLAP_INSIDE, circle2(0.0, 0.0, 2.0).overlap(circle2(0.0, 0.0, 10.0)));
    }

    @Test
    void largeCircleContainsSmall() {
        assertEquals(Region.OVERLAP_OUTSIDE, circle2(0.0, 0.0, 10.0).overlap(circle2(0.0, 0.0, 2.0)));
    }

    @Test
    void overlappingCirclesPartial() {
        assertEquals(Region.OVERLAP_PARTIAL, circle2(0.0, 0.0, 5.0).overlap(circle2(6.0, 0.0, 5.0)));
    }

    @Test
    void offCentreSmallInsideLarge() {
        assertEquals(Region.OVERLAP_INSIDE, circle2(3.0, 0.0, 2.0).overlap(circle2(0.0, 0.0, 10.0)));
    }

    @Test
    void circleVsNullRegionNone() {
        assertEquals(Region.OVERLAP_NONE, circle2(0.0, 0.0, 5.0).overlap(nullRegion()));
    }

    @Test
    void circleVsNegatedNullRegionFirstInside() {
        assertEquals(Region.OVERLAP_INSIDE, circle2(0.0, 0.0, 5.0).overlap(negatedCopy(nullRegion())));
    }

    @Test
    void negatedNullRegionVsCircleSecondInside() {
        assertEquals(Region.OVERLAP_OUTSIDE, negatedCopy(nullRegion()).overlap(circle2(0.0, 0.0, 5.0)));
    }

    @Test
    void twoNullRegionsSame() {
        assertEquals(Region.OVERLAP_SAME, nullRegion().overlap(nullRegion()));
    }

    @Test
    void twoNegatedNullRegionsSame() {
        assertEquals(Region.OVERLAP_SAME, negatedCopy(nullRegion()).overlap(negatedCopy(nullRegion())));
    }

    @Test
    void circleInsideBox() {
        assertEquals(Region.OVERLAP_INSIDE, circle2(0.0, 0.0, 2.0).overlap(boxc(ZERO2, new double[] {10.0, 10.0})));
    }

    @Test
    void boxInsideCircle() {
        assertEquals(Region.OVERLAP_INSIDE, boxc(ZERO2, new double[] {1.0, 1.0}).overlap(circle2(0.0, 0.0, 100.0)));
    }

    @Test
    void circleAndBoxSeparated() {
        assertEquals(
                Region.OVERLAP_NONE,
                circle2(0.0, 0.0, 2.0).overlap(boxc(new double[] {100.0, 100.0}, new double[] {101.0, 101.0})));
    }

    @Test
    void circleAndBoxPartial() {
        assertEquals(Region.OVERLAP_PARTIAL, circle2(0.0, 0.0, 5.0).overlap(boxc(new double[] {5.5, 0.0}, new double[] {
            2.5, 2.0
        })));
    }

    @Test
    void negatedCircleVsSmallCircle() {
        assertEquals(Region.OVERLAP_NONE, negatedCopy(circle2(0.0, 0.0, 10.0)).overlap(circle2(0.0, 0.0, 2.0)));
    }

    @Test
    void negatedCircleVsFarCircle() {
        assertEquals(Region.OVERLAP_OUTSIDE, negatedCopy(circle2(0.0, 0.0, 2.0)).overlap(circle2(50.0, 0.0, 3.0)));
    }

    @Test
    void overlapSymmetry() {
        Circle c1 = circle2(0.0, 0.0, 5.0);
        Circle c2 = circle2(6.0, 0.0, 5.0);
        assertEquals(Region.OVERLAP_PARTIAL, c1.overlap(c2));
        assertEquals(Region.OVERLAP_PARTIAL, c2.overlap(c1));
    }

    @Test
    void containmentSwapsWithArgumentOrder() {
        Circle small = circle2(0.0, 0.0, 2.0);
        Circle large = circle2(0.0, 0.0, 10.0);
        assertEquals(Region.OVERLAP_INSIDE, small.overlap(large));
        assertEquals(Region.OVERLAP_OUTSIDE, large.overlap(small));
    }

    @Test
    void boundingBoxQuickReject() {
        assertEquals(Region.OVERLAP_NONE, circle2(0.0, 0.0, 1.0).overlap(circle2(1000.0, 1000.0, 1.0)));
    }

    // ---- Against C (probes/region_compound_probe.c) ----

    @Test
    void codesInBothOrdersMatchC() {
        check("ov_identical_circles", overlap(circle2(0.0, 0.0, 5.0), circle2(0.0, 0.0, 5.0)));
        check("ov_separated_circles", overlap(circle2(0.0, 0.0, 2.0), circle2(10.0, 0.0, 2.0)));
        check("ov_small_in_large", overlap(circle2(0.0, 0.0, 2.0), circle2(0.0, 0.0, 10.0)));
        check("ov_partial_circles", overlap(circle2(0.0, 0.0, 5.0), circle2(6.0, 0.0, 5.0)));
        check("ov_off_centre", overlap(circle2(3.0, 0.0, 2.0), circle2(0.0, 0.0, 10.0)));
        NullRegion a = nullRegion();
        check("ov_circle_null", overlap(circle2(0.0, 0.0, 5.0), a));
        check("ov_circle_negated_null", overlap(circle2(0.0, 0.0, 5.0), negatedCopy(a)));
        check("ov_two_nulls", overlap(a, (Region) a.copy()));
        check("ov_two_negated_nulls", overlap(negatedCopy(a), negatedCopy(a)));
        check("ov_circle_in_box", overlap(circle2(0.0, 0.0, 2.0), boxc(ZERO2, new double[] {10.0, 10.0})));
        check("ov_box_in_circle", overlap(boxc(ZERO2, new double[] {1.0, 1.0}), circle2(0.0, 0.0, 100.0)));
        check("ov_circle_box_apart", overlap(circle2(0.0, 0.0, 2.0), boxc(new double[] {100.0, 100.0}, new double[] {
            101.0, 101.0
        })));
        check("ov_circle_box_partial", overlap(circle2(0.0, 0.0, 5.0), boxc(new double[] {5.5, 0.0}, new double[] {
            2.5, 2.0
        })));
        check("ov_negated_vs_small", overlap(negatedCopy(circle2(0.0, 0.0, 10.0)), circle2(0.0, 0.0, 2.0)));
        check("ov_negated_vs_far", overlap(negatedCopy(circle2(0.0, 0.0, 2.0)), circle2(50.0, 0.0, 3.0)));
        check("ov_far_apart", overlap(circle2(0.0, 0.0, 1.0), circle2(1000.0, 1000.0, 1.0)));
    }

    @Test
    void regionWithNoBoundaryMeshMatchesC() {
        Interval a = new Interval(new Frame(2), new double[] {0.0, BAD}, new double[] {BAD, BAD}, null);
        check("ov_half_plane_circle_across", overlap(a, circle2(0.0, 0.0, 1.0)));
        check("ov_half_plane_circle_inside", overlap(a, circle2(5.0, 0.0, 1.0)));
        check("ov_half_plane_circle_outside", overlap(a, circle2(-5.0, 0.0, 1.0)));
        check(
                "ov_negated_circle_circle",
                overlap(negatedCopy(circle2(0.0, 0.0, 1.0)), negatedCopy(circle2(0.5, 0.0, 1.0))));
    }

    // ---- A Region with no mesh inside a box that needs its own mesh inside that box ----

    /** The Prism of the Interval x &gt;= 0 and the Box 0 &lt;= y &lt;= 1, which has an open end. */
    private static Prism openEndedPrism() {
        Interval open = new Interval(new Frame(1), new double[] {0.0}, new double[] {BAD}, null);
        return new Prism(open, new Box(new Frame(1), 1, new double[] {0.0}, new double[] {1.0}, null));
    }

    /** The Prism of the negated Box 0 &lt;= x &lt;= 1 and the Box 0 &lt;= y &lt;= 1. */
    private static Prism negatedComponentPrism() {
        Box side = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {1.0}, null);
        return new Prism(negatedCopy(side), (Region) side.copy());
    }

    /**
     * Asserts that {@code a.overlap(b)} reports region.c {@code OverlapX}'s error for Regions whose
     * overlap it cannot determine, naming the classes in the order C's message does, and leaves
     * both Regions as they were.
     */
    private static void assertOverlapUndetermined(Region a, Region b) {
        Region a0 = (Region) a.copy();
        Region b0 = (Region) b.copy();
        AstException ex = assertThrows(AstException.class, () -> a.overlap(b));
        assertEquals(AstException.AST__INTER, ex.getStatus());
        assertTrue(
                ex.getMsgText().contains("cannot determine the overlap between the supplied Regions"), ex.getMsgText());
        assertTrue(a.astEqual(a0));
        assertTrue(b.astEqual(b0));
        // Nothing is left behind that changes a second attempt.
        AstException again = assertThrows(AstException.class, () -> a.overlap(b));
        assertEquals(ex.getMsgText(), again.getMsgText());
    }

    @Test
    void prismWithAnOpenEndAgainstACircleIsAnErrorInBothOrders() {
        // prism.c Overlap takes the Prism first whichever order the Regions are given in.
        String message = "astOverlap(Prism): The current implementation of astOverlap cannot determine the overlap"
                + " between the supplied Regions (a Prism and a Circle).";
        assertEquals(
                message,
                assertThrows(AstException.class, () -> circle2(5.0, 0.5, 0.25).overlap(openEndedPrism()))
                        .getMsgText());
        assertEquals(
                message,
                assertThrows(AstException.class, () -> openEndedPrism().overlap(circle2(5.0, 0.5, 0.25)))
                        .getMsgText());
        assertOverlapUndetermined(circle2(5.0, 0.5, 0.25), openEndedPrism());
        assertOverlapUndetermined(openEndedPrism(), circle2(5.0, 0.5, 0.25));
    }

    @Test
    void prismWithANegatedComponentAgainstACircleIsAnErrorInBothOrders() {
        assertOverlapUndetermined(circle2(5.0, 0.5, 0.25), negatedComponentPrism());
        assertOverlapUndetermined(negatedComponentPrism(), circle2(5.0, 0.5, 0.25));
    }

    @Test
    void prismWithNoMeshAgainstAnEllipsePolygonOrAndIsAnErrorInBothOrders() {
        Region[] others = {
            new Ellipse(new Frame(2), 1, new double[] {5.0, 0.5}, new double[] {0.3, 0.2}, new double[] {0.3}, null),
            new Polygon(new Frame(2), 3, new double[] {4.8, 5.2, 5.0}, new double[] {0.2, 0.2, 0.8}, null),
            new CmpRegion(circle2(5.0, 0.5, 0.25), circle2(5.1, 0.5, 0.25), CmpRegion.AST__AND)
        };
        for (Region other : others) {
            assertOverlapUndetermined(other, openEndedPrism());
            assertOverlapUndetermined(openEndedPrism(), other);
            assertOverlapUndetermined(other, negatedComponentPrism());
            assertOverlapUndetermined(negatedComponentPrism(), other);
        }
    }

    /**
     * region.c {@code OverlapX} ignores an error from a mesh it can do without, so an AND holding
     * a Prism with no mesh can still be compared: with the AND first C gives 4, its code from
     * {@code probes/region_and_open_prism_probe.c}; with the Circle first C recurses until its
     * stack overflows, and this library gives 4 too.
     */
    @Test
    void anAndHoldingAPrismWithNoMeshCanStillOverlap() {
        CmpRegion and = new CmpRegion(openEndedPrism(), circle2(5.0, 0.5, 3.0), CmpRegion.AST__AND);
        String want = ProbeOutput.load("region_and_open_prism_probe").get("and_of_open_prism_and_circle");
        assertEquals(want, "overlap " + and.overlap(circle2(0.0, 0.5, 3.0)) + "\n");
        assertEquals(Region.OVERLAP_PARTIAL, circle2(0.0, 0.5, 3.0).overlap(and));
    }

    @Test
    void meshOfTheAndOfAPrismWithNoMeshIsAnError() {
        // cmpregion.c RegBaseMesh meshes the Prism inside the bounds of the Circle, which cross the
        // open end of the Prism, with region.c BndBaseMesh.
        CmpRegion c = new CmpRegion(openEndedPrism(), circle2(0.0, 0.5, 0.25), CmpRegion.AST__AND);
        AstException ex = assertThrows(AstException.class, c::regMesh);
        assertEquals(AstException.AST__INTER, ex.getStatus());
        assertEquals(
                "astOverlap(Prism): The current implementation of astOverlap cannot determine the overlap"
                        + " between the supplied Regions (a Prism and a Box).",
                ex.getMsgText());
    }
}
