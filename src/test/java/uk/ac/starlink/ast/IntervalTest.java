package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static uk.ac.starlink.ast.RegionProbeText.unc;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_interval.rs}, and the Interval cases of {@code
 * probes/region_null_interval_pointlist_probe.c}: membership of bounded, unbounded and excluded
 * intervals, open and negated, Bounded, bounds, the default uncertainty, masking (including C's
 * mask of an Interval unbounded above, which sets no pixel inside it), overlap between Intervals
 * (interval.c {@code Overlap}), the constructor's uncertainty checks and the dump, against C.
 *
 * <p>{@code construct_mismatched_axes_error} gives an lbnd array longer than the Frame has axes,
 * which jniast accepts; here it is shorter, which is {@code AST__DIMIN}. {@code
 * interval_centre_is_given_in_the_frame_asked_for} asks the protected {@code astRegCentre}.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: {@code centre_of_bounded_interval} ({@code
 * astGetRegionDisc}, not in jniast), {@code mesh_of_an_unbounded_interval_names_the_class}
 * ({@code astGetRegionMesh}, not in jniast).
 */
class IntervalTest {

    private static final double BAD = AstObject.AST__BAD;

    private static Interval interval(Frame f, double[] lbnd, double[] ubnd) {
        return new Interval(f, lbnd, ubnd, null);
    }

    private static Interval interval2(double x1, double y1, double x2, double y2) {
        return interval(new Frame(2), new double[] {x1, y1}, new double[] {x2, y2});
    }

    private static Interval interval1(double x1, double x2) {
        return interval(new Frame(1), new double[] {x1}, new double[] {x2});
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

    private static double[] point(double[][] p, int i) {
        double[] out = new double[p.length];
        for (int j = 0; j < p.length; j++) {
            out[j] = p[j][i];
        }
        return out;
    }

    // ---- Translation of test_interval.rs ----

    @Test
    void constructBounded2d() {
        Interval iv = interval2(1.0, 2.0, 5.0, 8.0);
        assertEquals(2, iv.getNaxes());
        assertTrue(iv.getBounded());
        assertFalse(iv.getNegated());
        assertTrue(iv.getClosed());
    }

    @Test
    void constructUnboundedAxis() {
        assertFalse(interval2(BAD, 2.0, 5.0, 8.0).getBounded());
    }

    @Test
    void constructMismatchedAxesError() {
        AstException e = assertThrows(
                AstException.class, () -> interval(new Frame(2), new double[] {1.0}, new double[] {5.0, 8.0}));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals(
                "astInterval(Interval): The lbnd array holds 1 values.\nThis should not be less than 2.",
                e.getMsgText());
    }

    @Test
    void constructMismatchedUbndError() {
        AstException e = assertThrows(
                AstException.class, () -> interval(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0}));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
        assertEquals(
                "astInterval(Interval): The ubnd array holds 1 values.\nThis should not be less than 2.",
                e.getMsgText());
    }

    @Test
    void pointInsideBounded() {
        Interval iv = interval2(1.0, 2.0, 5.0, 8.0);
        assertTrue(iv.pointInRegion(new double[] {3.0, 5.0}));
        assertTrue(iv.pointInRegion(new double[] {1.0, 2.0}));
        assertTrue(iv.pointInRegion(new double[] {5.0, 8.0}));
        assertTrue(iv.pointInRegion(new double[] {1.0, 8.0}));
        assertTrue(iv.pointInRegion(new double[] {5.0, 2.0}));
    }

    @Test
    void pointOutsideBounded() {
        Interval iv = interval2(1.0, 2.0, 5.0, 8.0);
        assertFalse(iv.pointInRegion(new double[] {0.5, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {5.5, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {3.0, 1.5}));
        assertFalse(iv.pointInRegion(new double[] {3.0, 8.5}));
        assertFalse(iv.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void unboundedLowerAcceptsAnyBelow() {
        Interval iv = interval2(BAD, 0.0, 10.0, 10.0);
        assertTrue(iv.pointInRegion(new double[] {-1e20, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {11.0, 5.0}));
    }

    @Test
    void unboundedUpperAcceptsAnyAbove() {
        Interval iv = interval2(0.0, 0.0, BAD, 10.0);
        assertTrue(iv.pointInRegion(new double[] {1e20, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {-1.0, 5.0}));
    }

    @Test
    void fullyUnboundedAxis() {
        Interval iv = interval2(BAD, 0.0, BAD, 10.0);
        assertTrue(iv.pointInRegion(new double[] {-1e30, 5.0}));
        assertTrue(iv.pointInRegion(new double[] {1e30, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {0.0, 11.0}));
    }

    @Test
    void openBoundaryExcludesEdges() {
        Interval iv = interval2(1.0, 2.0, 5.0, 8.0);
        iv.setClosed(false);
        assertFalse(iv.pointInRegion(new double[] {1.0, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {3.0, 8.0}));
        assertFalse(iv.pointInRegion(new double[] {5.0, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {3.0, 2.0}));
        assertTrue(iv.pointInRegion(new double[] {3.0, 5.0}));
    }

    @Test
    void excludedIntervalWrapsAround() {
        Interval iv = interval1(8.0, 2.0);
        assertTrue(iv.pointInRegion(new double[] {1.0}));
        assertTrue(iv.pointInRegion(new double[] {9.0}));
        assertTrue(iv.pointInRegion(new double[] {2.0}));
        assertTrue(iv.pointInRegion(new double[] {8.0}));
        assertFalse(iv.pointInRegion(new double[] {5.0}));
        assertFalse(iv.pointInRegion(new double[] {3.0}));
        assertFalse(iv.pointInRegion(new double[] {7.0}));
    }

    @Test
    void negatedInvertsMembership() {
        Interval iv = interval2(0.0, 0.0, 10.0, 10.0);
        iv.negate();
        assertFalse(iv.pointInRegion(new double[] {5.0, 5.0}));
        assertTrue(iv.pointInRegion(new double[] {15.0, 5.0}));
        assertTrue(iv.pointInRegion(new double[] {-1.0, 5.0}));
    }

    @Test
    void forwardTransformMasksOutside() {
        Interval iv = interval2(0.0, 0.0, 10.0, 10.0);
        double[][] out = iv.transform(
                rows(new double[] {5, 5}, new double[] {15, 5}, new double[] {5, -1}, new double[] {0, 10}), 4, true);
        assertArrayEquals(new double[] {5.0, 5.0}, point(out, 0));
        assertArrayEquals(new double[] {BAD, BAD}, point(out, 1));
        assertArrayEquals(new double[] {BAD, BAD}, point(out, 2));
        assertArrayEquals(new double[] {0.0, 10.0}, point(out, 3));
    }

    @Test
    void inverseTransformMasksAsForward() {
        Interval iv = interval2(0.0, 0.0, 10.0, 10.0);
        double[][] in = rows(new double[] {15, 5}, new double[] {-1, -1});
        assertArrayEquals(iv.transform(in, 2, true), iv.transform(in, 2, false));
        assertArrayEquals(new double[] {BAD, BAD}, point(iv.transform(in, 2, false), 0));
    }

    @Test
    void badInputGivesOutside() {
        Interval iv = interval2(0.0, 0.0, 10.0, 10.0);
        assertFalse(iv.pointInRegion(new double[] {BAD, 5.0}));
        assertFalse(iv.pointInRegion(new double[] {5.0, BAD}));
    }

    @Test
    void badInputInBatchTransform() {
        double[][] out =
                interval2(0.0, 0.0, 10.0, 10.0).transform(rows(new double[] {5, 5}, new double[] {BAD, 5}), 2, true);
        assertEquals(5.0, out[0][0]);
        assertArrayEquals(new double[] {BAD, BAD}, point(out, 1));
    }

    @Test
    void boundsOfBoundedInterval() {
        double[][] b = interval2(1.0, 2.0, 5.0, 8.0).getRegionBounds();
        assertArrayEquals(new double[] {1.0, 2.0}, b[0]);
        assertArrayEquals(new double[] {5.0, 8.0}, b[1]);
    }

    @Test
    void boundsOfUnboundedInterval() {
        double[][] b = interval2(BAD, 2.0, 5.0, BAD).getRegionBounds();
        assertArrayEquals(new double[] {-Double.MAX_VALUE, 2.0}, b[0]);
        assertArrayEquals(new double[] {5.0, Double.MAX_VALUE}, b[1]);
    }

    @Test
    void meshOfBounded2dIsTheBoxMesh() {
        assertEquals(200, interval2(0.0, 0.0, 10.0, 10.0).baseMesh()[0].length);
    }

    @Test
    void meshOfUnboundedIsAnError() {
        AstException e = assertThrows(
                AstException.class, () -> interval2(BAD, 0.0, 10.0, 10.0).baseMesh());
        assertEquals(AstException.AST__INTER, e.getStatus());
    }

    @Test
    void mapRegionScalesBounds() {
        Region mapped = interval2(1.0, 2.0, 5.0, 8.0).mapRegion(new ZoomMap(2, 2.0), new Frame(2));
        assertTrue(mapped.pointInRegion(new double[] {6.0, 10.0}));
        assertFalse(mapped.pointInRegion(new double[] {1.0, 3.0}));
    }

    @Test
    void frameDistanceDelegation() {
        assertEquals(5.0, interval2(0, 0, 10, 10).distance(new double[] {0, 0}, new double[] {3, 4}), 1e-10);
    }

    @Test
    void oneDimensionalInterval() {
        Interval iv = interval1(2.0, 8.0);
        assertTrue(iv.pointInRegion(new double[] {5.0}));
        assertTrue(iv.pointInRegion(new double[] {2.0}));
        assertTrue(iv.pointInRegion(new double[] {8.0}));
        assertFalse(iv.pointInRegion(new double[] {1.0}));
        assertFalse(iv.pointInRegion(new double[] {9.0}));
    }

    @Test
    void batch10kPoints() {
        Interval iv = interval2(0.0, 0.0, 100.0, 100.0);
        int n = 10_000;
        double[][] in = new double[2][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = i % 2 == 0 ? 50.0 : 150.0;
            in[1][i] = 50.0;
        }
        double[][] out = iv.transform(in, n, true);
        for (int i = 0; i < n; i++) {
            assertEquals(i % 2 == 0 ? 50.0 : BAD, out[0][i], "point " + i);
        }
    }

    @Test
    void builderSerialisedPointSetUsesCLayout() {
        String s = ChannelText.dump(interval2(3.0, 4.0, 7.0, 8.0));
        assertTrue(s.contains("X1 = 3"), s);
        assertTrue(s.contains("X2 = 4"), s);
        assertTrue(s.contains("X3 = 7"), s);
        assertTrue(s.contains("X4 = 8"), s);
    }

    @Test
    void parsedCFormatPointSetPreservesBounds() {
        String s = " Begin Interval\n    Naxes = 2\n IsA Frame\n    Frm =\n       Begin Frame\n          Naxes = 2\n"
                + "          Ax1 = Begin Axis End Axis\n          Ax2 = Begin Axis End Axis\n       End Frame\n"
                + "    Points =\n       Begin PointSet\n          Npoint = 2\n          Ncoord = 2\n"
                + "          X1 = 3\n          X2 = 4\n          X3 = 7\n          X4 = 8\n       End PointSet\n"
                + " IsA Region\n End Interval\n";
        Interval iv = assertInstanceOf(Interval.class, ChannelText.read(s));
        double[][] out = iv.transform(rows(new double[] {3, 4}, new double[] {7, 8}, new double[] {5, 6}), 3, true);
        for (int k = 0; k < 3; k++) {
            assertNotEquals(BAD, out[0][k], "point " + k);
        }
    }

    @Test
    void regPinsBoundedIntervalDelegatesToBox() {
        Interval iv = interval2(0.0, 0.0, 10.0, 10.0);
        assertTrue(iv.regPins(rows(new double[] {0.0, 5.0}), null, null));
        assertTrue(iv.regPins(rows(new double[] {10.0, 10.0}), null, null));
        assertFalse(iv.regPins(rows(new double[] {5.0, 5.0}), null, null));
        assertFalse(iv.regPins(rows(new double[] {20.0, 20.0}), null, null));
    }

    @Test
    void regPinsSemiInfiniteIntervalBoundary() {
        Interval iv = interval2(BAD, 0.0, 10.0, 10.0);
        assertTrue(iv.regPins(rows(new double[] {10.0, 5.0}), null, null));
        assertFalse(iv.regPins(rows(new double[] {-100.0, 5.0}), null, null));
    }

    @Test
    void regPinsSemiInfiniteBoundaryMeshWhenBoundedAxis() {
        Interval iv = interval2(BAD, 0.0, 10.0, 10.0);
        assertTrue(iv.regPins(rows(new double[] {5.0, 0.0}), null, null));
        assertFalse(iv.regPins(rows(new double[] {10.0, 5.0}, new double[] {-100.0, 5.0}), null, null));
    }

    @Test
    void intervalCentreIsGivenInTheFrameAskedFor() {
        Region mapped = interval1(1.0, 3.0).mapRegionInto(new ZoomMap(1, 2.0), new Frame(1));
        assertArrayEquals(new double[] {2.0}, mapped.regCentre(null, null, 0, FrameSet.AST__BASE));
        assertArrayEquals(new double[] {4.0}, mapped.regCentre(null, null, 0, FrameSet.AST__CURRENT));
    }

    @Test
    void intervalTransformFollowsChangesAfterFirstUse() {
        Interval iv = interval2(0.0, 0.0, 1.0, 1.0);
        double[][] pts = rows(new double[] {0.5, 0.5}, new double[] {2.0, 2.0});
        double[][] before = iv.transform(pts, 2, true);
        assertArrayEquals(new double[] {0.5, 0.5}, point(before, 0));
        assertArrayEquals(new double[] {BAD, BAD}, point(before, 1));
        iv.setNegated(true);
        double[][] after = iv.transform(pts, 2, true);
        assertArrayEquals(new double[] {BAD, BAD}, point(after, 0));
        assertArrayEquals(new double[] {2.0, 2.0}, point(after, 1));
        iv.setClosed(false);
        iv.setNegated(false);
        assertArrayEquals(new double[] {BAD, BAD}, point(iv.transform(rows(new double[] {0.0, 0.5}), 1, true), 0));
    }

    @Test
    void intervalNegateAfterFirstUse() {
        Interval iv = interval2(0.0, 0.0, 1.0, 1.0);
        double[][] pts = rows(new double[] {0.5, 0.5}, new double[] {2.0, 2.0}, new double[] {1.0, 0.5});
        iv.transform(pts, 3, true);
        Region copy = iv.getNegation();
        iv.negate();
        for (Mapping region : new Mapping[] {iv.simplify(), copy.simplify()}) {
            double[][] out = region.transform(pts, 3, true);
            assertArrayEquals(new double[] {BAD, BAD}, point(out, 0));
            assertArrayEquals(new double[] {2.0, 2.0}, point(out, 1));
        }
        double[][] out = iv.transform(pts, 3, true);
        assertArrayEquals(new double[] {BAD, BAD}, point(out, 0));
        assertArrayEquals(new double[] {2.0, 2.0}, point(out, 1));
        assertArrayEquals(new double[] {1.0, 0.5}, point(out, 2));
    }

    // ---- C's values from region_null_interval_pointlist_probe ----

    @Test
    void constructorUncertaintyChecks() {
        check(
                "interval_unc_dims",
                error(() -> new Interval(new Frame(2), new double[] {0, 0}, new double[] {1, 1}, box3(-0.1, 0.1))));
        PointList pl = new PointList(new Frame(2), 3, new double[][] {{1, 3, 5}, {2, 4, 6}}, null);
        check(
                "interval_unc_shape",
                error(() -> new Interval(new Frame(2), new double[] {0, 0}, new double[] {1, 1}, pl)));
        Box u = box2(-0.1, -0.1, 0.1, 0.1);
        u.negate();
        check(
                "interval_unc_negated",
                unc(new Interval(new Frame(2), new double[] {0, 0}, new double[] {1, 1}, u), false));
    }

    @Test
    void anIntervalUncertaintyKeepsTheBoundsItHadBeforeItWasMoved() {
        // interval.c RegCentre moves the equivalent Box and the limits, but leaves the bounds
        // the Interval keeps, which RegBaseBox reports, where they were.
        Box b = box2(0.0, 0.0, 10.0, 10.0);
        b.setUnc(interval2(-0.1, -0.1, 0.1, 0.1));
        check("interval_unc_bounded_interval", unc(b, false));
    }

    @Test
    void constructorNullFrameIsNullPointerException() {
        NullPointerException e = assertThrows(
                NullPointerException.class, () -> new Interval(null, new double[] {0}, new double[] {1}, null));
        assertEquals("frame", e.getMessage());
    }

    @Test
    void anIntervalWithNoLimitsHoldsEveryPoint() {
        Interval r = interval2(BAD, BAD, BAD, BAD);
        double[] test = {0.0, 0.0, 1.0e30, -1.0e30, BAD, 0.0, 0.5, BAD, -5.0, 7.0};
        check("interval_no_limits", tran(r, 2, test) + bounded(r) + bounds(r) + points(r));
    }

    @Test
    void nonFiniteLimitsAreBad() {
        double nan = Double.NaN;
        double inf = Double.POSITIVE_INFINITY;
        Interval r = interval2(nan, -inf, inf, nan);
        double[] test = {0.0, 0.0, 1.0e30, -1.0e30, BAD, 0.0, 0.5, BAD, -5.0, 7.0};
        check("interval_no_limits", tran(r, 2, test) + bounded(r) + bounds(r) + points(r));
    }

    @Test
    void membershipMatchesC() {
        double[] zy = {1.5, 5.0, 1.5, 5.000001, 0.5, 5.0, 1.5, 4.0};
        Interval r = interval2(1.0, 5.0, 2.0, 5.0);
        check("interval_slice_unc", tran(r, 2, zy) + bounded(r) + unc(r, true));
        r = interval2(1.0, 5.0, BAD, 5.0);
        check("interval_slice_unbounded", tran(r, 2, zy) + bounded(r) + bounds(r) + unc(r, true) + points(r));

        double[] x1 = {1.0, 2.0, 2.5, 5.0, 8.0, 9.0, BAD};
        r = interval1(8.0, 2.0);
        check("interval_excluded", tran(r, 1, x1) + bounded(r) + bounds(r));
        r.setClosed(false);
        check("interval_excluded_open", tran(r, 1, x1));
        r.setClosed(true);
        r.negate();
        check("interval_excluded_negated", tran(r, 1, x1) + bounded(r));

        double[] xh = {1.0, -1.0e30, 0.5, 0.0, 2.0, 1.0e30, BAD, 1.0, 4.0, BAD};
        r = interval2(1.0, BAD, BAD, BAD);
        r.negate();
        check("interval_half_negated", tran(r, 2, xh) + bounded(r) + bounds(r));

        r = interval2(0.0, 0.0, 1.0, 1.0);
        r.negate();
        check("interval_negated_bounded", bounded(r) + bounds(r));
    }

    @Test
    void mappedIntervalsMatchC() {
        Region r = interval1(1.0, 3.0).mapRegionInto(new ZoomMap(1, 2.0), new Frame(1));
        check("interval_mapped", tran(r, 1, 1.0, 3.0, 4.0, 7.0) + bounded(r) + bounds(r) + points(r) + unc(r, true));
    }

    @Test
    void mappedUnboundedIntervalMatchesC() {
        // The bounds come from the corners of the base Frame box, the largest double on the
        // unbounded axes, through the ZoomMap: twice the largest double, which C gives as
        // infinite (and NaN once the width is taken) and the Mapping here as bad, making every
        // corner bad. The rest of the case is compared with C.
        double[] xh = {1.0, -1.0e30, 0.5, 0.0, 2.0, 1.0e30, BAD, 1.0, 4.0, BAD};
        Region r = interval2(1.0, BAD, BAD, BAD).mapRegionInto(new ZoomMap(2, 2.0), new Frame(2));
        String want = RegionProbeText.PROBE.get("interval_unbounded_mapped");
        String[] lines = want.split("\n", -1);
        assertEquals("bounds 2 inf inf nan", lines[2]);
        assertEquals(lines[0] + "\n" + lines[1] + "\n", tran(r, 2, xh) + bounded(r));
        assertEquals(lines[3] + "\n" + lines[4] + "\n", unc(r, true));
        double[][] b = r.getRegionBounds();
        assertArrayEquals(new double[] {BAD, BAD}, b[0]);
        assertArrayEquals(new double[] {BAD, BAD}, b[1]);
    }

    @Test
    void unboundedIntervalHasNoMesh() {
        check("interval_unbounded_mesh", error(() -> interval1(BAD, 1.0).showMesh(false, null)));
    }

    @Test
    void masksMatchC() {
        int[] lb = {1};
        int[] ub = {10};
        // An Interval unbounded above has an upper bound of the largest double, which C's
        // Mask<X> turns into an empty grid box (region.c Mask<X>, C bug): no pixel is inside.
        check("interval_above_inside", mask(interval1(3.0, BAD), null, true, lb, ub));
        check("interval_above_outside", mask(interval1(3.0, BAD), null, false, lb, ub));
        check("interval_below_inside", mask(interval1(BAD, 7.0), null, true, lb, ub));
        check("interval_below_outside", mask(interval1(BAD, 7.0), null, false, lb, ub));
        check("interval_above_bounds", bounds(interval1(3.0, BAD)));
    }

    @Test
    void overlapsMatchC() {
        check("interval_same_half", overlap(interval2(0, BAD, BAD, BAD), interval2(0, BAD, BAD, BAD)));
        check("interval_nested_half", overlap(interval2(0, BAD, BAD, BAD), interval2(1, BAD, BAD, BAD)));
        check("interval_disjoint_half", overlap(interval2(BAD, BAD, 0, BAD), interval2(1, BAD, BAD, BAD)));
        check("interval_crossed_half", overlap(interval2(0, BAD, BAD, BAD), interval2(BAD, 0, BAD, BAD)));
        Interval a = interval2(0, BAD, BAD, BAD);
        a.negate();
        check("interval_negated_half", overlap(a, interval2(0, BAD, BAD, BAD)));
        check("interval_excluded_inside", overlap(interval1(2.0, 1.0), interval1(3.0, 4.0)));
        check("interval_excluded_across", overlap(interval1(2.0, 1.0), interval1(1.5, 4.0)));
        check("interval_excluded_same", overlap(interval1(2.0, 1.0), interval1(2.0, 1.0)));
        check("interval_excluded_negation", overlap(interval1(2.0, 1.0), interval1(1.0, 2.0)));
        check("interval_bounded_same", overlap(interval2(0, 0, 1, 1), interval2(0, 0, 1, 1)));
        check("interval_bounded_inside", overlap(interval2(0, 0, 1, 1), interval2(0.25, 0.25, 0.5, 0.5)));
        check("interval_box_inside", overlap(interval2(0, 0, 1, 1), box2(0.25, 0.25, 0.5, 0.5)));
        check(
                "interval_zoomed",
                overlap(
                        interval2(2, BAD, BAD, BAD),
                        interval2(1, BAD, BAD, BAD).mapRegionInto(new ZoomMap(2, 2.0), new Frame(2))));
        double c = Math.cos(0.5);
        double s = Math.sin(0.5);
        Mapping rot = new MatrixMap(2, 2, new double[][] {{c, -s}, {s, c}});
        Region rotated = interval2(0, BAD, BAD, BAD).mapRegionInto(rot, new Frame(2));
        check("interval_rotated", error(() -> overlap(interval2(0, BAD, BAD, BAD), rotated)));
    }

    @Test
    void limitsEachOthersNegationOnOneOfTwoAxesAreAnError() {
        // interval.c Overlap combines the axes' codes through a 5 by 5 table, which code 6 indexes
        // beyond; the pinned build gives 6 though these Intervals are disjoint (probe cases
        // interval_negation_on_first_of_two_axes and interval_negation_on_second_of_two_axes).
        Interval[][] pairs = {
            {interval2(2, 0, 1, 1), interval2(1, 0, 2, 1)}, {interval2(0, 2, 1, 1), interval2(0, 1, 1, 2)}
        };
        for (Interval[] pair : pairs) {
            for (int i = 0; i < 2; i++) {
                Interval a = pair[i];
                Interval b = pair[1 - i];
                AstException e = assertThrows(AstException.class, () -> a.overlap(b));
                assertEquals(AstException.AST__INTER, e.getStatus());
            }
        }
    }

    @Test
    void aSplitWithTwoOutputsIsNotOneToOne() {
        // interval.c OneToOne gives up when the first input feeds two outputs, so the overlap is
        // region.c's, which cannot compare two unbounded Intervals.
        Mapping pm = new PermMap(2, new int[] {1, 2}, 2, new int[] {1, 1}, null);
        MapSplit split = pm.mapSplit(new int[] {0});
        check("interval_split_two_outputs", "split " + (split == null ? -1 : split.map.getNout()) + "\n");
        assertNull(Interval.oneToOne(pm));
        Region mapped = interval2(0, BAD, BAD, BAD).mapRegionInto(pm, new Frame(2));
        check("interval_split_two_outputs_overlap", error(() -> overlap(interval2(0, BAD, BAD, BAD), mapped)));
    }

    @Test
    void dumpMatchesC() {
        Interval iv = interval2(1.0, BAD, BAD, 2.0);
        check("interval_dump", ChannelText.dump(iv));
        Interval back = assertInstanceOf(Interval.class, ChannelText.read(ChannelText.dump(iv)));
        assertTrue(back.pointInRegion(new double[] {5.0, -100.0}));
        assertFalse(back.pointInRegion(new double[] {0.0, 0.0}));
    }

    /**
     * Absorbing a PointList into an Interval attaches one constant per Interval axis to every
     * point, which is only equivalent when the Interval is a point (interval.c {@code
     * MergeInterval}). An Interval spanning 0 to 10 on axis 1 and unbounded above on axis 2 is not a
     * point, so the simplified Prism holds every (x, y, z) with x in 0 to 10, y at least 0 and z 3
     * or 4.
     */
    @Test
    void wideIntervalDoesNotMergeIntoAPointlist() {
        PointList pl = new PointList(new Frame(1), 2, new double[][] {{3.0, 4.0}}, null);
        Interval iv = interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {10.0, BAD});
        Region simp = (Region) new Prism(iv, pl).simplify();
        assertFalse(simp instanceof PointList, "a wide Interval must not merge into a PointList");
        assertTrue(simp.pointInRegion(new double[] {2.0, 7.0, 3.0}), "(2,7,3) is inside the Prism");
        assertFalse(simp.pointInRegion(new double[] {20.0, 1.0, 3.0}), "(20,1,3) is outside the Prism");
    }

    /**
     * An Interval of zero width on every axis is a point, so the Prism is the PointList with the
     * point's axis values attached to every member.
     */
    @Test
    void pointIntervalMergesIntoAPointlist() {
        PointList pl = new PointList(new Frame(1), 2, new double[][] {{3.0, 4.0}}, null);
        Interval iv = interval(new Frame(2), new double[] {5.0, 0.0}, new double[] {5.0, 0.0});
        PointList merged = assertInstanceOf(PointList.class, new Prism(iv, pl).simplify());
        double[][] pts = merged.getPoints();
        assertEquals(2, pts[0].length);
        assertArrayEquals(new double[] {5.0, 5.0}, pts[0]);
        assertArrayEquals(new double[] {0.0, 0.0}, pts[1]);
        assertArrayEquals(new double[] {3.0, 4.0}, pts[2]);
    }
}
