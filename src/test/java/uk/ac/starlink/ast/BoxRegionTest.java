package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_box_region.rs} (ancestor: C {@code
 * ast_tester/testbox.c}): construction from a centre and corner and from two corners,
 * membership, the open boundary, negation, bounds, a 3-D Box, the transformation, Frame
 * behaviour, the Region attributes, the boundary mesh, copies, a change of attribute that leaves
 * the Box in place, and {@code astRegPins}.
 *
 * <p>{@code default_uncertainty_follows_a_moved_box} pins a departure of the Rust port from C:
 * Rust discards the default uncertainty when the Box moves, while C keeps it (region.c {@code
 * ResetCache} leaves {@code defunc}). Its expected values here are C's, from {@code
 * probes/region_defunc_probe.c}, which also records C's default uncertainty after the Box is
 * mapped and after a change of Unit.
 *
 * <p>{@code construct_requires_matching_axes} gives two points of different lengths; jniast
 * accepts an array longer than the Frame has axes, so here the shorter one is shorter than the
 * Frame, which is {@code AST__DIMIN}. {@code with_integrity_preserves_points} makes a change
 * through the FrameSet's integrity that leaves the coordinate system alone, a new Title.
 *
 * <p>Listed in {@code deferred/rust-tests.txt}: the three {@code simplify_permmap_slice_*} tests
 * that expect a NullRegion, which needs the Box's own simplification.
 */
class BoxRegionTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final double TOL = 1e-10;

    private static Box fromBounds(Frame f, double[] lbnd, double[] ubnd) {
        return new Box(f, 1, lbnd, ubnd, null);
    }

    private static Box unit10() {
        return fromBounds(new Frame(2), new double[] {0.0, 0.0}, new double[] {10.0, 10.0});
    }

    private static void assertBounds(Region r, double[] lbnd, double[] ubnd) {
        double[][] b = r.getRegionBounds();
        assertArrayEquals(lbnd, b[0], TOL);
        assertArrayEquals(ubnd, b[1], TOL);
    }

    @Test
    void constructCentreCorner() {
        Box b = new Box(new Frame(2), 0, new double[] {5.0, 10.0}, new double[] {8.0, 14.0}, null);
        assertEquals(2, b.getNaxes());
        assertBounds(b, new double[] {2.0, 6.0}, new double[] {8.0, 14.0});
    }

    @Test
    void constructCentreCornerSymmetric() {
        Box b = new Box(new Frame(2), 0, new double[] {5.0, 10.0}, new double[] {2.0, 6.0}, null);
        assertBounds(b, new double[] {2.0, 6.0}, new double[] {8.0, 14.0});
    }

    @Test
    void constructFromBounds() {
        Box b = fromBounds(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        assertEquals(2, b.getNaxes());
        assertBounds(b, new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
    }

    @Test
    void constructRequiresMatchingAxes() {
        AstException e = assertThrows(
                AstException.class,
                () -> new Box(new Frame(3), 0, new double[] {1.0, 2.0, 3.0}, new double[] {4.0, 5.0}, null));
        assertEquals(AstException.AST__DIMIN, e.getStatus());
    }

    @Test
    void pointInside() {
        Box b = unit10();
        assertTrue(b.pointInRegion(new double[] {5.0, 5.0}));
        assertTrue(b.pointInRegion(new double[] {1.0, 1.0}));
        assertTrue(b.pointInRegion(new double[] {9.0, 9.0}));
        assertTrue(b.pointInRegion(new double[] {0.5, 5.0}));
    }

    @Test
    void pointOutside() {
        Box b = unit10();
        assertFalse(b.pointInRegion(new double[] {-1.0, 5.0}));
        assertFalse(b.pointInRegion(new double[] {5.0, 11.0}));
        assertFalse(b.pointInRegion(new double[] {11.0, 5.0}));
        assertFalse(b.pointInRegion(new double[] {5.0, -0.1}));
    }

    @Test
    void pointOnBoundaryClosed() {
        Box b = unit10();
        assertTrue(b.getClosed());
        assertTrue(b.pointInRegion(new double[] {0.0, 5.0}));
        assertTrue(b.pointInRegion(new double[] {10.0, 5.0}));
        assertTrue(b.pointInRegion(new double[] {5.0, 0.0}));
        assertTrue(b.pointInRegion(new double[] {5.0, 10.0}));
        assertTrue(b.pointInRegion(new double[] {0.0, 0.0}));
        assertTrue(b.pointInRegion(new double[] {10.0, 10.0}));
    }

    @Test
    void pointOnBoundaryOpen() {
        Box b = unit10();
        b.setClosed(false);
        assertFalse(b.getClosed());
        assertFalse(b.pointInRegion(new double[] {0.0, 5.0}));
        assertFalse(b.pointInRegion(new double[] {10.0, 5.0}));
        assertFalse(b.pointInRegion(new double[] {5.0, 0.0}));
        assertFalse(b.pointInRegion(new double[] {5.0, 10.0}));
        assertTrue(b.pointInRegion(new double[] {5.0, 5.0}));
        assertTrue(b.pointInRegion(new double[] {0.01, 0.01}));
    }

    @Test
    void pointWithBadValue() {
        Box b = unit10();
        assertFalse(b.pointInRegion(new double[] {BAD, 5.0}));
        assertFalse(b.pointInRegion(new double[] {5.0, BAD}));
        assertFalse(b.pointInRegion(new double[] {BAD, BAD}));
    }

    @Test
    void negationFlipsMembership() {
        Box b = unit10();
        assertTrue(b.pointInRegion(new double[] {5.0, 5.0}));
        assertFalse(b.pointInRegion(new double[] {15.0, 5.0}));
        b.negate();
        assertTrue(b.getNegated());
        assertFalse(b.pointInRegion(new double[] {5.0, 5.0}));
        assertTrue(b.pointInRegion(new double[] {15.0, 5.0}));
    }

    @Test
    void negatedCopy() {
        Box b = unit10();
        Region neg = b.getNegation();
        assertFalse(b.getNegated());
        assertTrue(neg.getNegated());
        assertTrue(b.pointInRegion(new double[] {5.0, 5.0}));
        assertFalse(neg.pointInRegion(new double[] {5.0, 5.0}));
    }

    @Test
    void boundsFromOffsetCentre() {
        Box b = new Box(new Frame(2), 0, new double[] {3.0, 7.0}, new double[] {5.0, 10.0}, null);
        assertBounds(b, new double[] {1.0, 4.0}, new double[] {5.0, 10.0});
    }

    @Test
    void box3d() {
        Box b = fromBounds(new Frame(3), new double[] {1.0, 2.0, 3.0}, new double[] {4.0, 6.0, 9.0});
        assertEquals(3, b.getNaxes());
        assertTrue(b.getBounded());
        double[][] bounds = b.getRegionBounds();
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, bounds[0]);
        assertArrayEquals(new double[] {4.0, 6.0, 9.0}, bounds[1]);
        assertTrue(b.pointInRegion(new double[] {2.5, 4.0, 6.0}));
        assertTrue(b.pointInRegion(new double[] {1.0, 2.0, 3.0}));
        assertFalse(b.pointInRegion(new double[] {0.0, 4.0, 6.0}));
        assertFalse(b.pointInRegion(new double[] {2.5, 7.0, 6.0}));
        assertFalse(b.pointInRegion(new double[] {2.5, 4.0, 10.0}));
    }

    @Test
    void transformPassesInterior() {
        double[][] out = unit10().tran2(2, new double[] {5.0, 1.0}, new double[] {5.0, 1.0}, true);
        assertArrayEquals(new double[] {5.0, 1.0}, out[0], TOL);
        assertArrayEquals(new double[] {5.0, 1.0}, out[1], TOL);
    }

    @Test
    void transformMasksExterior() {
        double[][] out = unit10().tran2(2, new double[] {-1.0, 5.0}, new double[] {5.0, 11.0}, true);
        assertArrayEquals(new double[] {BAD, BAD}, out[0]);
        assertArrayEquals(new double[] {BAD, BAD}, out[1]);
    }

    @Test
    void batchTransform10kPoints() {
        Box b = fromBounds(new Frame(2), new double[] {-1.0, -1.0}, new double[] {1.0, 1.0});
        int n = 100;
        int total = n * n;
        double[] x = new double[total];
        double[] y = new double[total];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int idx = i * n + j;
                x[idx] = -2.0 + 4.0 * i / (n - 1);
                y[idx] = -2.0 + 4.0 * j / (n - 1);
            }
        }
        double[][] out = b.tran2(total, x, y, true);
        int inside = 0;
        int outside = 0;
        for (int i = 0; i < total; i++) {
            boolean inBox = x[i] >= -1.0 && x[i] <= 1.0 && y[i] >= -1.0 && y[i] <= 1.0;
            if (inBox) {
                assertEquals(x[i], out[0][i], "point " + i + " should pass through");
                assertEquals(y[i], out[1][i]);
                inside++;
            } else {
                assertEquals(BAD, out[0][i], "point " + i + " should be bad");
                assertEquals(BAD, out[1][i]);
                outside++;
            }
        }
        assertTrue(inside > 2000, "expected >2000 inside, got " + inside);
        assertTrue(outside > 7000, "expected >7000 outside, got " + outside);
    }

    @Test
    void frameNaxes() {
        Box b = fromBounds(new Frame(3), new double[] {0.0, 0.0, 0.0}, new double[] {1.0, 1.0, 1.0});
        assertEquals(3, b.getNaxes());
        assertEquals(3, b.getNin());
        assertEquals(3, b.getNout());
    }

    @Test
    void frameDistanceDelegation() {
        assertEquals(5.0, unit10().distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}), TOL);
    }

    @Test
    void regionAttributes() {
        Box b = unit10();
        assertTrue(b.getClosed());
        assertFalse(b.getNegated());
        assertTrue(b.getBounded());
        assertTrue(b.getAdaptive());
        assertEquals(200, b.getMeshSize());
        assertEquals(1.0, b.getFillFactor(), TOL);
        b.setClosed(false);
        assertFalse(b.getClosed());
        b.setMeshSize(500);
        assertEquals(500, b.getMeshSize());
        b.setFillFactor(0.5);
        assertEquals(0.5, b.getFillFactor(), TOL);
        b.setAdaptive(false);
        assertFalse(b.getAdaptive());
    }

    @Test
    void mesh2dPointsOnBoundary() {
        Box b = fromBounds(new Frame(2), new double[] {0.0, 0.0}, new double[] {4.0, 3.0});
        b.setMeshSize(100);
        double[][] mesh = b.baseMesh();
        for (int i = 0; i < mesh[0].length; i++) {
            double px = mesh[0][i];
            double py = mesh[1][i];
            boolean onXEdge = Math.abs(px) < TOL || Math.abs(px - 4.0) < TOL;
            boolean onYEdge = Math.abs(py) < TOL || Math.abs(py - 3.0) < TOL;
            boolean inX = px >= -TOL && px <= 4.0 + TOL;
            boolean inY = py >= -TOL && py <= 3.0 + TOL;
            assertTrue(
                    (onXEdge && inY) || (onYEdge && inX),
                    "mesh point " + i + " at (" + px + ", " + py + ") is not on the box boundary");
        }
    }

    @Test
    void mesh1d() {
        Box b = fromBounds(new Frame(1), new double[] {3.0}, new double[] {7.0});
        double[][] mesh = b.baseMesh();
        assertEquals(1, mesh.length);
        assertEquals(2, mesh[0].length);
        double lo = Math.min(mesh[0][0], mesh[0][1]);
        double hi = Math.max(mesh[0][0], mesh[0][1]);
        assertEquals(3.0, lo, TOL);
        assertEquals(7.0, hi, TOL);
    }

    @Test
    void cloneIsIndependent() {
        Box b = unit10();
        Box copy = (Box) b.copy();
        copy.negate();
        assertFalse(b.getNegated());
        assertTrue(copy.getNegated());
        assertTrue(b.pointInRegion(new double[] {5.0, 5.0}));
        assertFalse(copy.pointInRegion(new double[] {5.0, 5.0}));
    }

    @Test
    void withIntegrityPreservesPoints() {
        Box b = fromBounds(new Frame(2), new double[] {1.0, 2.0}, new double[] {5.0, 8.0});
        double[][] orig = b.getRegionBounds();
        b.setTitle("Same place");
        assertBounds(b, orig[0], orig[1]);
    }

    private static double[][] points(double[]... rows) {
        double[][] out = new double[rows[0].length][rows.length];
        for (int i = 0; i < rows.length; i++) {
            for (int c = 0; c < rows[i].length; c++) {
                out[c][i] = rows[i][c];
            }
        }
        return out;
    }

    @Test
    void regPinsBoundaryPointIsOnBoundary() {
        Box b = unit10();
        assertTrue(b.regPins(points(new double[] {0.0, 5.0}), null, null));
        assertTrue(b.regPins(points(new double[] {10.0, 10.0}), null, null));
    }

    @Test
    void regPinsInteriorPointIsNotOnBoundary() {
        assertFalse(unit10().regPins(points(new double[] {5.0, 5.0}), null, null));
    }

    @Test
    void regPinsExteriorPointIsNotOnBoundary() {
        assertFalse(unit10().regPins(points(new double[] {20.0, 20.0}), null, null));
    }

    @Test
    void regPinsFullBoundaryMeshAllOnBoundary() {
        Box b = unit10();
        assertTrue(b.regPins(b.baseMesh(), null, null));
    }

    @Test
    void regPinsMixedSetReportsFalse() {
        assertFalse(unit10().regPins(points(new double[] {0.0, 5.0}, new double[] {5.0, 5.0}), null, null));
    }

    /**
     * Map a 3-D Box onto two current Frame axes through a PermMap whose base axes 2 and 3 are each
     * fed a constant, then simplify. Both constants are inside the Box, so the slice is a Box of the
     * two current Frame axes.
     */
    @Test
    void simplifyPermmapSliceBothConstantsInsideKeepsBox() {
        Frame base = new Frame(3);
        base.setDomain("PIXEL");
        Frame curr = new Frame(2);
        curr.setDomain("SLICE");
        PermMap pm = new PermMap(3, new int[] {1, -1, -2}, 2, new int[] {1, 2}, new double[] {5.0, 15.0});
        Box b = fromBounds(base, new double[] {0.0, 0.0, 0.0}, new double[] {10.0, 20.0, 30.0});
        Mapping r = b.mapRegion(pm, curr);
        assertSame(Box.class, r.getClass());
        assertEquals(2, ((Box) r).getNaxes());
    }

    /**
     * A Region's forward and inverse transformations are the same (box.c {@code Transform}): an
     * exterior point is bad in either direction. The values are C's for astTran2 on astBox(frame,
     * 1, [0,0], [1,1]).
     */
    @Test
    void inverseTransformMasksAsForwardDoes() {
        Box b = fromBounds(new Frame(2), new double[] {0.0, 0.0}, new double[] {1.0, 1.0});
        for (boolean forward : new boolean[] {true, false}) {
            double[][] out = b.tran2(2, new double[] {0.5, 2.0}, new double[] {0.5, 2.0}, forward);
            assertArrayEquals(new double[] {0.5, BAD}, out[0], "forward=" + forward);
            assertArrayEquals(new double[] {0.5, BAD}, out[1], "forward=" + forward);
        }
    }

    // ------------------------------------------------------------------
    // The default uncertainty, against probes/region_defunc_probe.c
    // ------------------------------------------------------------------

    private static final Map<String, String> DEFUNC = ProbeOutput.load("region_defunc_probe");

    /** The probe's lines for one case, written as the probe writes them. */
    private static final class Out {
        private final List<String> lines = new ArrayList<>();

        private static String g(double v) {
            return CFormat.format("%.17g", v);
        }

        void tran(String label, Mapping m, double x, double y) {
            double[][] o = m.tran2(1, new double[] {x}, new double[] {y}, true);
            lines.add("tran " + label + " " + g(x) + " " + g(y) + " : " + g(o[0][0]) + " " + g(o[1][0]));
        }

        void unc(String label, Region r) {
            double[][] b = r.getUnc(true).getRegionBounds();
            lines.add("unc " + label + " " + g(b[0][0]) + " " + g(b[1][0]) + " " + g(b[0][1]) + " " + g(b[1][1]));
        }

        void points(Region r) {
            double[][] p = r.getRegionPoints();
            lines.add("points " + g(p[0][0]) + " " + g(p[1][0]) + " " + g(p[0][1]) + " " + g(p[1][1]));
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    private static Box flat(Frame f) {
        return fromBounds(f, new double[] {0.0, 5.0}, new double[] {10.0, 5.0});
    }

    private static Frame metres() {
        Frame f = new Frame(2);
        f.setUnit(1, "m");
        f.setUnit(2, "m");
        f.setActiveUnit(true);
        return f;
    }

    /**
     * A Box of zero width is widened by its default uncertainty, which scales with the axis value
     * (region.c {@code GetDefUnc}). C keeps the default uncertainty once built, so after the Box is
     * moved to 1000 a point 2e-4 off the centre is outside, as the uncertainty is still that made
     * at 5, 5e-6 wide.
     */
    @Test
    void defaultUncertaintyFollowsAMovedBox() {
        Out out = new Out();
        Box b = flat(new Frame(2));
        out.tran("near", b, 5.0, 5.0 + 2.0e-6);
        out.tran("off", b, 5.0, 5.0 + 3.0e-6);
        out.unc("before", b);
        b.regCentre(new double[] {5.0, 1000.0}, null, 0, FrameSet.AST__BASE);
        out.points(b);
        out.tran("moved_near", b, 5.0, 1000.0 + 2.0e-6);
        out.tran("moved_far", b, 5.0, 1000.0 + 2.0e-4);
        out.unc("after", b);
        assertEquals(DEFUNC.get("regcentre"), out.text());
    }

    /** The default uncertainty of a Box mapped by the protected astMapRegion through a ShiftMap. */
    @Test
    void defaultUncertaintyOfAMappedBox() {
        Out out = new Out();
        Box b = flat(new Frame(2));
        out.tran("near", b, 5.0, 5.0 + 2.0e-6);
        Region r = b.mapRegionInto(new ShiftMap(new double[] {0.0, 995.0}), new Frame(2));
        out.points(r);
        out.tran("moved_near", r, 5.0, 1000.0 + 2.0e-6);
        out.tran("moved_off", r, 5.0, 1000.0 + 3.0e-6);
        out.tran("moved_far", r, 5.0, 1000.0 + 2.0e-4);
        out.unc("after", r);
        assertEquals(DEFUNC.get("mapregion"), out.text());
    }

    /** The default uncertainty after an adaptive change of Unit, which remaps the Box into km. */
    @Test
    void defaultUncertaintyAfterAnAdaptiveChangeOfUnit() {
        Out out = new Out();
        Box b = flat(metres());
        out.tran("near", b, 5.0, 5.0 + 2.0e-6);
        out.unc("before", b);
        b.setUnit(2, "km");
        out.points(b);
        out.tran("moved_near", b, 5.0, 0.005 + 2.0e-9);
        out.tran("moved_off", b, 5.0, 0.005 + 3.0e-9);
        out.tran("moved_far", b, 5.0, 0.005 + 2.0e-6);
        out.unc("after", b);
        assertEquals(DEFUNC.get("unit_adaptive"), out.text());
    }

    /** The default uncertainty after a change of Unit on a Box that is not Adaptive. */
    @Test
    void defaultUncertaintyAfterAChangeOfUnitThatDoesNotAdapt() {
        Out out = new Out();
        Box b = flat(metres());
        b.setAdaptive(false);
        out.tran("near", b, 5.0, 5.0 + 2.0e-6);
        out.unc("before", b);
        b.setUnit(2, "km");
        out.points(b);
        out.tran("moved_near", b, 5.0, 5.0 + 2.0e-6);
        out.tran("moved_off", b, 5.0, 5.0 + 3.0e-6);
        out.unc("after", b);
        assertEquals(DEFUNC.get("unit_not_adaptive"), out.text());
    }
}
