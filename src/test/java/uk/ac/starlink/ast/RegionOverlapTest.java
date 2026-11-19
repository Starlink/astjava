package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.ac.starlink.ast.CompoundProbe.BAD;
import static uk.ac.starlink.ast.CompoundProbe.boxc;
import static uk.ac.starlink.ast.CompoundProbe.check;
import static uk.ac.starlink.ast.CompoundProbe.circle2;
import static uk.ac.starlink.ast.CompoundProbe.negatedCopy;
import static uk.ac.starlink.ast.CompoundProbe.overlap;

import org.junit.jupiter.api.Test;

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
}
