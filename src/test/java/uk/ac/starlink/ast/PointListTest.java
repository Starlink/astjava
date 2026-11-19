package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.RegionProbeText.bounded;
import static uk.ac.starlink.ast.RegionProbeText.bounds;
import static uk.ac.starlink.ast.RegionProbeText.check;
import static uk.ac.starlink.ast.RegionProbeText.error;
import static uk.ac.starlink.ast.RegionProbeText.mask;
import static uk.ac.starlink.ast.RegionProbeText.overlap;
import static uk.ac.starlink.ast.RegionProbeText.points;
import static uk.ac.starlink.ast.RegionProbeText.tran;
import static uk.ac.starlink.ast.RegionProbeText.tranValues;
import static uk.ac.starlink.ast.RegionProbeText.unc;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_pointlist.rs} (ancestor: C {@code
 * ast_tester/testpointlist.c}), and the PointList cases of {@code
 * probes/region_null_interval_pointlist_probe.c}: membership through the uncertainty, Bounded,
 * bounds, the Closed and ListSize attributes, masking (pointlist.c {@code Mask<X>}), overlap, the
 * constructor's checks and the dump, against C.
 *
 * <p>The Rust tests that call {@code reg_pins} with no uncertainty give one here: pointlist.c
 * {@code RegPins} makes a PointList of the points given in the Frame of that uncertainty. Its
 * second test, that each of this PointList's points is near a point given, looks at as many of
 * this PointList's points as there are points given (C), so {@code
 * reg_pins_incomplete_cover_reports_false}, whose three points are tested against two, reports
 * true as C does. {@code with_integrity_preserves_points} sets a Title through the FrameSet's
 * integrity.
 *
 * <p>{@code point_with_bad_value_not_inside} expects a point with a bad value to be outside; C
 * keeps such a point unless its first value is good and outside the uncertainty of every point
 * (pointlist.c {@code Transform}, which looks at the first axis of the masked points alone), and
 * its {@code astPointInRegion} finds (bad, 2) and (1, bad) inside the PointList (1, 2), (3, 4).
 * The test is translated with C's values, from the probe.
 */
class PointListTest {

    private static final double BAD = AstObject.AST__BAD;

    /** A PointList of points given point by point. */
    private static PointList pointList(int naxes, Region unc, double[]... pts) {
        double[][] axes = new double[naxes][pts.length];
        for (int i = 0; i < pts.length; i++) {
            for (int j = 0; j < naxes; j++) {
                axes[j][i] = pts[i][j];
            }
        }
        return new PointList(new Frame(naxes), pts.length, axes, unc);
    }

    private static PointList pl12() {
        return pointList(2, null, new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
    }

    private static Box box2(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static Box box3(double lo, double hi) {
        return new Box(new Frame(3), 1, new double[] {lo, lo, lo}, new double[] {hi, hi, hi}, null);
    }

    private static double[][] rows(double[]... pts) {
        int naxes = pts[0].length;
        double[][] out = new double[naxes][pts.length];
        for (int i = 0; i < pts.length; i++) {
            for (int j = 0; j < naxes; j++) {
                out[j][i] = pts[i][j];
            }
        }
        return out;
    }

    /** A point uncertainty: a Box of half-width {@code w} about the origin. */
    private static Box pointUnc(double w) {
        return box2(-w, -w, w, w);
    }

    // ---- Translation of test_pointlist.rs ----

    @Test
    void constructSinglePoint() {
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        assertEquals(2, pl.getNaxes());
        assertEquals(1, pl.getListSize());
    }

    @Test
    void constructMultiplePoints() {
        PointList pl = pointList(3, null, new double[] {1, 2, 3}, new double[] {4, 5, 6}, new double[] {7, 8, 9});
        assertEquals(3, pl.getNaxes());
        assertEquals(3, pl.getListSize());
    }

    @Test
    void constructRequiresAtLeastOnePoint() {
        check("pointlist_no_points", error(() -> new PointList(new Frame(2), 0, new double[][] {{}, {}}, null)));
    }

    @Test
    void constructRequiresMatchingAxes() {
        AstException e = assertThrows(
                AstException.class, () -> new PointList(new Frame(3), 1, new double[][] {{1.0}, {2.0}}, null));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals(
                "astPointList(PointList): The points array holds values for 2 axes.\nThis should not be less than 3.",
                e.getMsgText());
    }

    @Test
    void constructRequiresEnoughValuesOnEachAxis() {
        AstException e = assertThrows(
                AstException.class, () -> new PointList(new Frame(2), 2, new double[][] {{1.0, 2.0}, {3.0}}, null));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals(
                "astPointList(PointList): The points[1] array holds 1 values.\nThis should not be less than 2.",
                e.getMsgText());
    }

    @Test
    void constructNullArgumentsAreNullPointerExceptions() {
        assertEquals(
                "frame",
                assertThrows(NullPointerException.class, () -> new PointList(null, 1, new double[][] {{1}}, null))
                        .getMessage());
        assertEquals(
                "points",
                assertThrows(NullPointerException.class, () -> new PointList(new Frame(1), 1, null, null))
                        .getMessage());
        assertEquals(
                "points[1]",
                assertThrows(
                                NullPointerException.class,
                                () -> new PointList(new Frame(2), 1, new double[][] {{1}, null}, null))
                        .getMessage());
    }

    @Test
    void longerArraysAreAccepted() {
        PointList pl = new PointList(new Frame(2), 1, new double[][] {{1.0, 9.0}, {2.0, 9.0}, {7.0}}, null);
        assertEquals(1, pl.getListSize());
        assertArrayEquals(new double[][] {{1.0}, {2.0}}, pl.getRegionPoints());
    }

    @Test
    void getRegionPointsReturnsDefiningPoints() {
        double[][] pts = pointList(2, null, new double[] {1, 2}, new double[] {3, 4}, new double[] {5, 6})
                .getRegionPoints();
        assertArrayEquals(new double[][] {{1, 3, 5}, {2, 4, 6}}, pts);
    }

    @Test
    void pointMatchesDefiningPointExact() {
        PointList pl = pl12();
        assertTrue(pl.pointInRegion(new double[] {1.0, 2.0}));
        assertTrue(pl.pointInRegion(new double[] {3.0, 4.0}));
    }

    @Test
    void pointDoesNotMatchEvenSlightlyOff() {
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        assertFalse(pl.pointInRegion(new double[] {1.1, 2.0}));
        assertFalse(pl.pointInRegion(new double[] {1.0, 2.1}));
        assertFalse(pl.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(pl.pointInRegion(new double[] {1.001, 2.001}));
    }

    @Test
    void pointWithBadValueIsAsCLeavesIt() {
        // pointlist.c Transform tests the first axis of the masked points alone, so a point
        // whose first value is bad keeps its values, and astPointInRegion, which looks at the
        // first good value, finds it inside.
        PointList r = pl12();
        double[] badin = {BAD, 2.0, 1.0, BAD, 7.0, BAD};
        String text = tranValues(r, badin);
        r.negate();
        check("pointlist_bad_input", text + tranValues(r, badin));
    }

    @Test
    void negationFlipsMembership() {
        PointList pl = pl12();
        assertTrue(pl.pointInRegion(new double[] {1.0, 2.0}));
        assertFalse(pl.pointInRegion(new double[] {0.0, 0.0}));
        pl.negate();
        assertTrue(pl.getNegated());
        assertFalse(pl.pointInRegion(new double[] {1.0, 2.0}));
        assertTrue(pl.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void negatedCopyIndependent() {
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        Region neg = pl.getNegation();
        assertFalse(pl.getNegated());
        assertTrue(neg.getNegated());
        assertTrue(pl.pointInRegion(new double[] {1.0, 2.0}));
        assertFalse(neg.pointInRegion(new double[] {1.0, 2.0}));
    }

    @Test
    void multiplePointsAllMatch() {
        double[][] pts = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {0.5, 0.5}};
        PointList pl = pointList(2, null, pts);
        for (double[] p : pts) {
            assertTrue(pl.pointInRegion(p));
        }
        assertFalse(pl.pointInRegion(new double[] {0.25, 0.25}));
        assertFalse(pl.pointInRegion(new double[] {2.0, 2.0}));
    }

    @Test
    void boundingBoxSinglePoint() {
        double[][] b = pointList(2, null, new double[] {3.0, 7.0}).getRegionBounds();
        assertArrayEquals(new double[] {3.0, 7.0}, b[0]);
        assertArrayEquals(new double[] {3.0, 7.0}, b[1]);
    }

    @Test
    void boundingBoxMultiplePoints() {
        double[][] b = pointList(
                        2, null, new double[] {1, 5}, new double[] {3, 2}, new double[] {7, 8}, new double[] {4, 1})
                .getRegionBounds();
        assertArrayEquals(new double[] {1.0, 1.0}, b[0]);
        assertArrayEquals(new double[] {7.0, 8.0}, b[1]);
    }

    @Test
    void boundingBox3d() {
        double[][] b = pointList(3, null, new double[] {1, 2, 3}, new double[] {4, 5, 6}, new double[] {-1, 0, 10})
                .getRegionBounds();
        assertArrayEquals(new double[] {-1.0, 0.0, 3.0}, b[0]);
        assertArrayEquals(new double[] {4.0, 5.0, 10.0}, b[1]);
    }

    @Test
    void isBoundedAlwaysTrue() {
        assertTrue(
                pointList(2, null, new double[] {1, 2}, new double[] {100, 200}).getBounded());
    }

    @Test
    void transformPassesMatchingPoints() {
        double[][] out = pl12().transform(rows(new double[] {1, 2}, new double[] {3, 4}, new double[] {5, 6}), 3, true);
        assertArrayEquals(new double[][] {{1.0, 3.0, BAD}, {2.0, 4.0, BAD}}, out);
    }

    @Test
    void transformInverseMasksAsForward() {
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        double[][] in = rows(new double[] {99, 88}, new double[] {1, 2});
        assertArrayEquals(pl.transform(in, 2, true), pl.transform(in, 2, false));
        assertEquals(BAD, pl.transform(in, 2, false)[0][0]);
    }

    @Test
    void transformNegatedInvertsMask() {
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        pl.negate();
        double[][] out = pl.transform(rows(new double[] {1, 2}, new double[] {5, 6}), 2, true);
        assertArrayEquals(new double[][] {{BAD, 5.0}, {BAD, 6.0}}, out);
    }

    @Test
    void mappingNinNout() {
        PointList pl = pointList(3, null, new double[] {1, 2, 3});
        assertEquals(3, pl.getNin());
        assertEquals(3, pl.getNout());
    }

    @Test
    void regionAttributes() {
        PointList pl = pointList(2, null, new double[] {0.0, 0.0});
        assertTrue(pl.getClosed());
        assertFalse(pl.getNegated());
        assertTrue(pl.getBounded());
        assertTrue(pl.getAdaptive());
        assertEquals(200, pl.getMeshSize());
        assertEquals(1.0, pl.getFillFactor());
        pl.setClosed(false);
        assertTrue(pl.getClosed());
        pl.negate();
        assertFalse(pl.getClosed());
        pl.negate();
        assertTrue(pl.getClosed());
        pl.setMeshSize(500);
        assertEquals(500, pl.getMeshSize());
        pl.setFillFactor(0.5);
        assertEquals(0.5, pl.getFillFactor());
        pl.setAdaptive(false);
        assertFalse(pl.getAdaptive());
    }

    @Test
    void frameDistanceDelegation() {
        assertEquals(
                5.0, pointList(2, null, new double[] {0, 0}).distance(new double[] {0, 0}, new double[] {3, 4}), 1e-10);
    }

    @Test
    void cloneIsIndependent() {
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        PointList copy = (PointList) pl.copy();
        copy.negate();
        assertFalse(pl.getNegated());
        assertTrue(copy.getNegated());
        assertTrue(pl.pointInRegion(new double[] {1.0, 2.0}));
        assertFalse(copy.pointInRegion(new double[] {1.0, 2.0}));
    }

    @Test
    void meshIsDefiningPoints() {
        double[][] pts = {{1, 3, 5}, {2, 4, 6}};
        assertArrayEquals(pts, new PointList(new Frame(2), 3, pts, null).baseMesh());
    }

    @Test
    void withIntegrityPreservesPoints() {
        PointList pl = pl12();
        pl.setTitle("A new title");
        assertTrue(pl.pointInRegion(new double[] {1.0, 2.0}));
        assertTrue(pl.pointInRegion(new double[] {3.0, 4.0}));
        assertFalse(pl.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void regPinsDefiningPointsOnBoundary() {
        assertTrue(pl12().regPins(rows(new double[] {1, 2}, new double[] {3, 4}), pointUnc(1e-6), null));
    }

    @Test
    void regPinsPointOffBoundaryReportsFalse() {
        assertFalse(pl12().regPins(rows(new double[] {1, 2}, new double[] {10, 10}), pointUnc(1e-6), null));
    }

    @Test
    void regPinsLooksAtAsManyOfItsPointsAsAreGiven() {
        PointList pl = pointList(2, null, new double[] {1, 2}, new double[] {3, 4}, new double[] {5, 6});
        assertTrue(pl.regPins(rows(new double[] {1, 2}, new double[] {3, 4}), pointUnc(1e-6), null));
        assertFalse(pl.regPins(rows(new double[] {1, 2}, new double[] {5, 6}), pointUnc(1e-6), null));
    }

    @Test
    void regPinsMask() {
        boolean[] mask = new boolean[2];
        assertFalse(pl12().regPins(rows(new double[] {1, 2}, new double[] {10, 10}), pointUnc(1e-6), mask));
        assertArrayEquals(new boolean[] {true, false}, mask);
    }

    @Test
    void membershipUsesTheNegatedUncertaintyBoundaryRule() {
        Box unc = new Box(new Frame(1), 1, new double[] {-1.0}, new double[] {1.0}, null);
        PointList pl = new PointList(new Frame(1), 1, new double[][] {{0.0}}, unc);
        assertFalse(pl.pointInRegion(new double[] {1.0}));
        assertTrue(pl.pointInRegion(new double[] {0.5}));
        Box zero = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {0.0}, null);
        PointList pl2 = new PointList(new Frame(1), 2, new double[][] {{0.0, 5.0}}, zero);
        assertFalse(pl2.pointInRegion(new double[] {0.0}));
        assertFalse(pl2.pointInRegion(new double[] {5.0}));
    }

    // ---- C's values from region_null_interval_pointlist_probe ----

    @Test
    void constructorUncertaintyChecks() {
        double[][] xy = {{1, 3, 5}, {2, 4, 6}};
        check("pointlist_unc_dims", error(() -> new PointList(new Frame(2), 3, xy, box3(-0.1, 0.1))));
        check(
                "pointlist_unc_shape",
                error(() -> new PointList(new Frame(2), 3, xy, new NullRegion(new Frame(2), null))));
        Box u = box2(-0.1, -0.1, 0.1, 0.1);
        u.negate();
        check("pointlist_unc_negated", unc(new PointList(new Frame(2), 3, xy, u), false));
    }

    @Test
    void aBadPointMatchesC() {
        PointList r = pointList(2, null, new double[] {1, 2}, new double[] {BAD, 3}, new double[] {5, 6});
        double[] bt = {1.0, 2.0, 5.0, 6.0, 1.0, 3.0, BAD, 3.0, 0.0, 0.0, 3.0, 3.0};
        check("pointlist_bad_point", tran(r, 2, bt) + bounded(r) + bounds(r) + points(r));
        // A value that is not finite is bad.
        r = pointList(2, null, new double[] {1, 2}, new double[] {Double.NaN, 3}, new double[] {5, 6});
        check("pointlist_bad_point", tran(r, 2, bt) + bounded(r) + bounds(r) + points(r));
    }

    @Test
    void membershipMatchesC() {
        PointList r = pl12();
        double[] near = {1.0, 2.0, 1.0000001, 2.0, 1.00001, 2.0, 3.0, 4.0, 2.0, 3.0, BAD, 2.0, 1.0, BAD, 3.0, 3.9999999
        };
        check(
                "pointlist_default_unc",
                tran(r, 2, near) + bounded(r) + bounds(r) + unc(r, true) + points(r) + "listsize " + r.getListSize()
                        + "\n");
        double[] halfw = {1.4, 2.0, 1.5, 2.0, 1.6, 2.0, 0.5, 2.0, 3.0, 4.5};
        r = new PointList(new Frame(2), 2, new double[][] {{1, 3}, {2, 4}}, pointUnc(0.5));
        check("pointlist_explicit_unc", tran(r, 2, halfw) + unc(r, false));
        r.negate();
        check("pointlist_negated", tran(r, 2, halfw) + bounded(r) + unc(r, false));
        Region m = pl12().mapRegionInto(new ZoomMap(2, 2.0), new Frame(2));
        check(
                "pointlist_mapped",
                tran(m, 2, 2.0, 4.0, 6.0, 8.0, 1.0, 2.0, 3.0, 4.0) + bounds(m) + points(m) + unc(m, true));
    }

    private static String closedLine(Region r) {
        return "closed " + r.getI("Closed") + " " + (r.test("Closed") ? 1 : 0) + "\n";
    }

    @Test
    void closedIsTheOppositeOfNegated() {
        PointList r = pl12();
        r.setClosed(false);
        String set = closedLine(r);
        r.negate();
        String negated = closedLine(r);
        r.clear("Closed");
        check("pointlist_closed", set + negated + closedLine(r));
        assertFalse(r.getClosed());
    }

    @Test
    void listSizeIsReadOnly() {
        PointList r = pl12();
        check("pointlist_listsize", "listsize " + r.getC("ListSize") + " " + (r.test("ListSize") ? 1 : 0) + "\n");
        check("pointlist_set_listsize", error(() -> r.set("ListSize=3")));
        check("pointlist_clear_listsize", error(() -> r.clear("ListSize")));
    }

    @Test
    void masksMatchC() {
        int[] lb = {1, 1};
        int[] ub = {5, 5};
        PointList dup = pointList(2, null, new double[] {2, 2}, new double[] {2, 2}, new double[] {2, BAD});
        check("pointlist_duplicates_inside", mask(dup, null, true, lb, ub));
        check("pointlist_duplicates_outside", mask(dup, null, false, lb, ub));
        PointList neg = pl12();
        neg.negate();
        check("pointlist_negated_inside", mask(neg, null, true, lb, ub));
        check("pointlist_zoomed_inside", mask(pl12(), new ZoomMap(2, 0.5), true, lb, ub));
    }

    @Test
    void overlapsMatchC() {
        check("pointlist_same", overlap(pl12(), pl12()));
        check("pointlist_subset", overlap(pl12(), pointList(2, null, new double[] {1.0, 2.0})));
        check("pointlist_null", overlap(pl12(), new NullRegion(new Frame(2), null)));
        check("pointlist_in_box", overlap(pl12(), box2(0, 0, 5, 5)));
        check("pointlist_across_box", overlap(pl12(), box2(0, 0, 2, 3)));
        check("pointlist_beside_box", overlap(pl12(), box2(10, 10, 11, 11)));
    }

    @Test
    void regPinsWithMorePointsThanThePointListHasChecksEachOfItsPoints() {
        // pointlist.c RegPins reads as many of this PointList's points as there are points
        // given, beyond its own when there are more; this library checks each of its own.
        PointList pl = pointList(2, null, new double[] {1.0, 2.0});
        assertTrue(pl.regPins(rows(new double[] {1, 2}, new double[] {1, 2}), pointUnc(1e-6), null));
    }

    @Test
    void anErrorFromRegPinsLeavesThePointListAsItWas() {
        // With no uncertainty for the points given, RegPins cannot make its second test, which C
        // would make with a null Frame.
        PointList p = pl12();
        p.negate();
        int icurr = p.getRegFS().getCurrent();
        double[][] pts = rows(new double[] {1, 2}, new double[] {0, 0});
        double[][] before = p.transform(pts, 2, true);
        AstException e = assertThrows(
                AstException.class, () -> p.regPins(rows(new double[] {1, 2}, new double[] {3, 4}), null, null));
        assertEquals(AstException.AST__INTER, e.getStatus());
        assertTrue(p.getNegated());
        assertEquals(icurr, p.getRegFS().getCurrent());
        assertArrayEquals(before, p.transform(pts, 2, true));
    }

    @Test
    void dumpMatchesC() {
        check("pointlist_dump", ChannelText.dump(pl12()));
        PointList back = assertInstanceOf(PointList.class, ChannelText.read(ChannelText.dump(pl12())));
        assertEquals(2, back.getListSize());
        assertTrue(back.pointInRegion(new double[] {3.0, 4.0}));
        assertFalse(back.pointInRegion(new double[] {0.0, 0.0}));
    }
}
