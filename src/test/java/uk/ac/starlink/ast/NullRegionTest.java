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
 * Translation of Rust {@code tests/test_nullregion.rs}, and the NullRegion cases of {@code
 * probes/region_null_interval_pointlist_probe.c}: membership, Bounded, bounds, mesh, masking,
 * overlap, the constructor's uncertainty checks and the dump, against C.
 *
 * <p>The default uncertainty, a Circle of zero radius at the origin of the base Frame
 * (nullregion.c {@code GetDefUnc}), is checked against {@code
 * probes/region_circle_ellipse_probe.c}.
 */
class NullRegionTest {

    private static final double BAD = AstObject.AST__BAD;

    private static NullRegion null2() {
        return new NullRegion(new Frame(2), null);
    }

    private static NullRegion negated2() {
        NullRegion r = null2();
        r.negate();
        return r;
    }

    private static Box box2(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static Box box3(double lo, double hi) {
        return new Box(new Frame(3), 1, new double[] {lo, lo, lo}, new double[] {hi, hi, hi}, null);
    }

    private static Interval halfPlane() {
        return new Interval(new Frame(2), new double[] {0.0, BAD}, new double[] {BAD, BAD}, null);
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

    // ---- Translation of test_nullregion.rs ----

    @Test
    void constructBasic() {
        NullRegion nr = null2();
        assertEquals(2, nr.getNaxes());
        assertFalse(nr.getNegated());
        assertTrue(nr.getBounded());
        assertFalse(negated2().getBounded());
    }

    @Test
    void construct3d() {
        assertEquals(3, new NullRegion(new Frame(3), null).getNaxes());
    }

    @Test
    void nothingInsideUnnegated() {
        NullRegion nr = null2();
        assertFalse(nr.pointInRegion(new double[] {0.0, 0.0}));
        assertFalse(nr.pointInRegion(new double[] {100.0, -50.0}));
        assertFalse(nr.pointInRegion(new double[] {1e30, 1e30}));
    }

    @Test
    void everythingInsideNegated() {
        NullRegion nr = negated2();
        assertTrue(nr.getNegated());
        assertTrue(nr.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(nr.pointInRegion(new double[] {100.0, -50.0}));
        assertTrue(nr.pointInRegion(new double[] {1e30, 1e30}));
    }

    @Test
    void negatedCopyIsUniversal() {
        Region negated = null2().getNegation();
        assertTrue(negated.getNegated());
        assertTrue(negated.pointInRegion(new double[] {42.0, 7.0}));
    }

    @Test
    void forwardTransformSetsAllBad() {
        double[][] out =
                null2().transform(rows(new double[] {1, 2}, new double[] {3, 4}, new double[] {5, 6}), 3, true);
        for (double[] axis : out) {
            assertArrayEquals(new double[] {BAD, BAD, BAD}, axis);
        }
    }

    @Test
    void inverseTransformMasksAsForward() {
        NullRegion nr = null2();
        double[][] in = rows(new double[] {1, 2}, new double[] {3, 4}, new double[] {5, 6});
        double[][] inverse = nr.transform(in, 3, false);
        double[][] forward = nr.transform(in, 3, true);
        assertArrayEquals(forward, inverse);
        assertEquals(BAD, inverse[0][0]);
    }

    @Test
    void negatedForwardTransformPassesThrough() {
        double[][] in = rows(new double[] {1, 2}, new double[] {3, 4}, new double[] {5, 6});
        assertArrayEquals(in, negated2().transform(in, 3, true));
    }

    @Test
    void boundsReportAnEmptyExtent() {
        NullRegion nr = null2();
        double[][] b = nr.getRegionBounds();
        assertArrayEquals(new double[] {1.0, 1.0}, b[0]);
        assertArrayEquals(new double[] {-1.0, -1.0}, b[1]);
        double[] lb = new double[2];
        double[] ub = new double[2];
        nr.regBaseBox(lb, ub);
        assertArrayEquals(new double[] {1.0, 1.0}, lb);
        assertArrayEquals(new double[] {-1.0, -1.0}, ub);
    }

    @Test
    void meshIsSingleBadPoint() {
        double[][] mesh = null2().baseMesh();
        assertEquals(1, mesh[0].length);
        assertEquals(BAD, mesh[0][0]);
        assertEquals(BAD, mesh[1][0]);
    }

    @Test
    void frameDistance() {
        assertEquals(5.0, null2().distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}), 1e-10);
    }

    @Test
    void mapRegionPreservesNull() {
        Region mapped = null2().mapRegion(new ZoomMap(2, 2.0), new Frame(2));
        assertFalse(mapped.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(mapped.getBounded());
    }

    @Test
    void mapNegatedRegionPreservesNegation() {
        Region mapped = negated2().mapRegion(new ZoomMap(2, 2.0), new Frame(2));
        assertTrue(mapped.getNegated());
        assertTrue(mapped.pointInRegion(new double[] {0.0, 0.0}));
    }

    @Test
    void badInputReturnsFalse() {
        assertFalse(null2().pointInRegion(new double[] {BAD, 0.0}));
    }

    // ---- C's values from region_null_interval_pointlist_probe ----

    @Test
    void constructorRejectsAnUncertaintyOfOtherAxes() {
        check("null_unc_dims", error(() -> new NullRegion(new Frame(2), box3(-0.1, 0.1))));
    }

    @Test
    void constructorRejectsAnUncertaintyThatCannotBeRecentred() {
        check("null_unc_shape", error(() -> new NullRegion(new Frame(2), halfPlane())));
    }

    @Test
    void constructorNegatesAnUnboundedUncertainty() {
        Box u = box2(-0.1, -0.1, 0.1, 0.1);
        u.negate();
        check("null_unc_negated", unc(new NullRegion(new Frame(2), u), false));
    }

    @Test
    void constructorNullFrameIsNullPointerException() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> new NullRegion(null, null));
        assertEquals("frame", e.getMessage());
    }

    @Test
    void plainNullRegionMatchesC() {
        Region r = null2();
        double[] test = {0.0, 0.0, BAD, 1.0, 1.0e30, 2.0};
        check("null_plain", tran(r, 2, test) + bounded(r) + bounds(r) + points(r) + unc(r, false));
        r = negated2();
        check("null_negated", tran(r, 2, test) + bounded(r) + bounds(r));
        r = negated2().mapRegionInto(new ZoomMap(2, 2.0), new Frame(2));
        check("null_mapped", tran(r, 2, test) + bounded(r) + bounds(r));
    }

    @Test
    void mappedToFewerAxesMatchesC() {
        // The public mapRegion simplifies the result (nullregion.c Simplify): the Mapping cannot
        // be split, so the NullRegion keeps both axes in its base Frame until it is made again
        // in the 1-D current Frame.
        double c = Math.cos(0.3);
        double s = Math.sin(0.3);
        Mapping m = new CmpMap(
                new MatrixMap(2, 2, new double[][] {{c, -s}, {s, c}}),
                new PermMap(2, new int[] {1, -1}, 1, new int[] {1}, new double[] {0.0}),
                true);
        Region r = null2().mapRegion(m, new Frame(1));
        check(
                "null_mapped_fewer_axes",
                "class " + r.astClass() + " naxes " + r.getNaxes() + " base "
                        + r.regionFrameSet().getNin() + "\n" + bounds(r));
    }

    @Test
    void boundsWithMoreBaseThanCurrentFrameAxesAreAnError() {
        // nullregion.c GetRegionBounds writes a bound for each of the two base Frame axes into
        // arrays with one element; mapRegionInto, unlike the public mapRegion, does not simplify.
        Mapping pm = new PermMap(2, new int[] {1, -1}, 1, new int[] {1}, new double[] {0.0});
        Region r = null2().mapRegionInto(pm, new Frame(1));
        assertEquals(2, r.regionFrameSet().getNin());
        AstException e = assertThrows(AstException.class, r::getRegionBounds);
        assertEquals(AstException.AST__INTER, e.getStatus());
    }

    @Test
    void simplifyMakesTheNullRegionInItsCurrentFrame() {
        NullRegion nr = negated2();
        nr.setMeshSize(50);
        Region mapped = nr.mapRegionInto(new ZoomMap(2, 2.0), new Frame(2));
        Region simple = assertInstanceOf(NullRegion.class, mapped.simplify());
        assertTrue(isUnitMap(simple));
        assertTrue(simple.getNegated());
        assertEquals(50, simple.getMeshSize());
        assertFalse(simple.testUnc());
    }

    private static boolean isUnitMap(Region r) {
        return Region.isUnitMap(r.regionFrameSet()
                .getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                .simplify());
    }

    @Test
    void badInputMatchesC() {
        double[] pts = {BAD, 1.0, 1.0e30, 2.0};
        check("null_bad_input", tranValues(null2(), pts) + tranValues(negated2(), pts));
    }

    @Test
    void nullRegionHasNoMesh() {
        check("null_mesh", error(() -> null2().showMesh(false, null)));
    }

    @Test
    void masksMatchC() {
        int[] lb = {1, 1};
        int[] ub = {3, 3};
        check("null_inside", mask(null2(), null, true, lb, ub));
        check("null_outside", mask(null2(), null, false, lb, ub));
        check("negated_null_inside", mask(negated2(), null, true, lb, ub));
        check("negated_null_outside", mask(negated2(), null, false, lb, ub));
    }

    @Test
    void overlapsMatchC() {
        check("null_null", overlap(null2(), null2()));
        check("null_negated_null", overlap(null2(), negated2()));
        check("negated_null_negated_null", overlap(negated2(), negated2()));
        check("negated_null_box", overlap(negated2(), box2(0, 0, 1, 1)));
        check("null_interval", overlap(null2(), halfPlane()));
        check("negated_null_interval", overlap(negated2(), halfPlane()));
        check("null_other_axes", overlap(null2(), new NullRegion(new Frame(3), null)));
    }

    @Test
    void dumpsMatchC() {
        check("null_dump", ChannelText.dump(null2()));
        check("negated_null_dump", ChannelText.dump(negated2()));
        Region back = assertInstanceOf(NullRegion.class, ChannelText.read(ChannelText.dump(negated2())));
        assertTrue(back.getNegated());
        assertTrue(back.pointInRegion(new double[] {3.0, 4.0}));
    }

    @Test
    void defaultUncertaintyIsBuiltInTheBaseFrame() {
        Mapping pm = new PermMap(2, new int[] {1, 2}, 3, new int[] {1, 2, -1}, new double[] {5.0});
        Region mapped = null2().mapRegionInto(pm, new Frame(3));
        assertEquals(3, mapped.getUncFrm(FrameSet.AST__CURRENT).getNaxes());
        assertEquals(2, mapped.getUncFrm(FrameSet.AST__BASE).getNaxes());
    }

    @Test
    void defaultUncertaintiesMatchC() {
        check(CircleTest.PROBE, "null_default_unc", unc(null2(), true) + unc(null2(), false));
        check(CircleTest.PROBE, "null_sky_default_unc", unc(new NullRegion(new SkyFrame(), null), true));
        Mapping pm = new PermMap(2, new int[] {1, 2}, 3, new int[] {1, 2, -1}, new double[] {5.0});
        Region mapped = null2().mapRegionInto(pm, new Frame(3));
        Region u = mapped.getUnc(true);
        Region base = mapped.getUncFrm(FrameSet.AST__BASE);
        check(
                CircleTest.PROBE,
                "null_mapped_default_unc",
                "unc " + u.astClass() + " " + u.getNaxes() + "\nbase unc " + base.astClass() + " " + base.getNaxes()
                        + "\n");
    }

    @Test
    void negatedNullRegionKeepsBadAxesAsGiven() {
        // nullregion.c Transform: a negated NullRegion copies every point, bad axes and all.
        double[][] out = negated2().transform(rows(new double[] {BAD, 3.0}), 1, true);
        assertEquals(BAD, out[0][0]);
        assertEquals(3.0, out[1][0]);
    }
}
