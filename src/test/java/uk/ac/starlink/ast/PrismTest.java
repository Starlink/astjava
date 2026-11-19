package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.CompoundProbe.BAD;
import static uk.ac.starlink.ast.CompoundProbe.bounded;
import static uk.ac.starlink.ast.CompoundProbe.bounds;
import static uk.ac.starlink.ast.CompoundProbe.box2;
import static uk.ac.starlink.ast.CompoundProbe.box3;
import static uk.ac.starlink.ast.CompoundProbe.check;
import static uk.ac.starlink.ast.CompoundProbe.checkClose;
import static uk.ac.starlink.ast.CompoundProbe.circle2;
import static uk.ac.starlink.ast.CompoundProbe.cylinder;
import static uk.ac.starlink.ast.CompoundProbe.decompose;
import static uk.ac.starlink.ast.CompoundProbe.fill;
import static uk.ac.starlink.ast.CompoundProbe.interval1;
import static uk.ac.starlink.ast.CompoundProbe.maplist;
import static uk.ac.starlink.ast.CompoundProbe.mesh;
import static uk.ac.starlink.ast.CompoundProbe.naxes;
import static uk.ac.starlink.ast.CompoundProbe.negatedCopy;
import static uk.ac.starlink.ast.CompoundProbe.overlap;
import static uk.ac.starlink.ast.CompoundProbe.pick;
import static uk.ac.starlink.ast.CompoundProbe.points;
import static uk.ac.starlink.ast.CompoundProbe.tran;
import static uk.ac.starlink.ast.CompoundProbe.tranValues;
import static uk.ac.starlink.ast.CompoundProbe.unc;
import static uk.ac.starlink.ast.RegionProbeText.error;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_prism.rs}, and the Prism cases of {@code
 * probes/region_compound_probe.c}: membership, negation, Bounded, bounds (including a Prism whose
 * axes have been permuted or scaled), the boundary mesh and the grid (including C's error when the
 * grid over a component holds no point of it), the default uncertainty, a Prism as an uncertainty,
 * picking axes, decomposition, overlap (prism.c {@code Overlap}, with its treatment of negation)
 * and the dump, against C.
 *
 * <p>The boundary mesh of a Prism of a Circle holds the Circle's mesh, {@code c + r cos(a)} and
 * {@code c + r sin(a)} with r = 5 about the origin, which {@code java.lang.Math} may give one unit in
 * the last place of {@code cos} and {@code sin} away from C's libm: at most {@code 5 * 1.1e-16 +
 * ulp(5) = 1.4e-15}; it is compared to {@code 1e-13}. Every other value comes from arithmetic on
 * Boxes, Intervals and PointLists and is compared exactly.
 */
class PrismTest {

    private static final double MESH_TOL = 1.0e-13;

    private static Prism makePrism() {
        return cylinder();
    }

    // ---- Translation of test_prism.rs ----

    @Test
    void constructCylinderPrism() {
        assertEquals(3, makePrism().getNaxes());
    }

    @Test
    void naxesIsSum() {
        Circle c3 = new Circle(new Frame(3), 1, new double[3], new double[] {1.0}, null);
        Interval i2 = new Interval(new Frame(2), new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
        assertEquals(5, new Prism(c3, i2).getNaxes());
    }

    @Test
    void pointInsideBothRegions() {
        assertTrue(makePrism().pointInRegion(new double[] {1.0, 1.0, 5.0}));
    }

    @Test
    void pointInsideCircleOutsideInterval() {
        assertFalse(makePrism().pointInRegion(new double[] {1.0, 1.0, 10.0}));
    }

    @Test
    void pointOutsideCircleInsideInterval() {
        assertFalse(makePrism().pointInRegion(new double[] {10.0, 10.0, 5.0}));
    }

    @Test
    void pointOutsideBothRegions() {
        assertFalse(makePrism().pointInRegion(new double[] {10.0, 10.0, 10.0}));
    }

    @Test
    void pointOnCircleBoundaryInside() {
        assertTrue(makePrism().pointInRegion(new double[] {5.0, 0.0, 5.0}));
    }

    @Test
    void badCoordinateMeansOutside() {
        Prism p = makePrism();
        assertFalse(p.pointInRegion(new double[] {BAD, 0.0, 5.0}));
        assertFalse(p.pointInRegion(new double[] {0.0, BAD, 5.0}));
        assertFalse(p.pointInRegion(new double[] {0.0, 0.0, BAD}));
    }

    @Test
    void negationInvertsMembership() {
        Prism p = makePrism();
        Prism n = negatedCopy(p);
        assertTrue(p.pointInRegion(new double[] {1.0, 1.0, 5.0}));
        assertFalse(n.pointInRegion(new double[] {1.0, 1.0, 5.0}));
        assertFalse(p.pointInRegion(new double[] {10.0, 10.0, 10.0}));
        assertTrue(n.pointInRegion(new double[] {10.0, 10.0, 10.0}));
    }

    @Test
    void negateInPlace() {
        Prism p = makePrism();
        assertFalse(p.getNegated());
        assertTrue(p.pointInRegion(new double[] {1.0, 1.0, 5.0}));
        p.negate();
        assertTrue(p.getNegated());
        assertFalse(p.pointInRegion(new double[] {1.0, 1.0, 5.0}));
    }

    @Test
    void boundingBoxIsConcatenation() {
        double[][] b = makePrism().getRegionBounds();
        assertEquals(3, b[0].length);
        assertEquals(-5.0, b[0][0], 1e-10);
        assertEquals(-5.0, b[0][1], 1e-10);
        assertEquals(5.0, b[1][0], 1e-10);
        assertEquals(5.0, b[1][1], 1e-10);
        assertEquals(2.0, b[0][2], 1e-10);
        assertEquals(8.0, b[1][2], 1e-10);
    }

    @Test
    void boundedWhenBothBounded() {
        assertTrue(makePrism().getBounded());
    }

    @Test
    void unboundedWhenIntervalUnbounded() {
        Interval iv = new Interval(new Frame(1), new double[] {2.0}, new double[] {BAD}, null);
        assertFalse(new Prism(circle2(0.0, 0.0, 5.0), iv).getBounded());
    }

    @Test
    void forwardTransformFiltersPoints() {
        double[][] in = {{1.0, 10.0, 1.0}, {1.0, 10.0, 1.0}, {5.0, 5.0, 10.0}};
        double[][] out = makePrism().transform(in, 3, true);
        assertEquals(1.0, out[0][0], 1e-10);
        assertEquals(1.0, out[1][0], 1e-10);
        assertEquals(5.0, out[2][0], 1e-10);
        for (int i = 1; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(BAD, out[j][i]);
            }
        }
    }

    @Test
    void inverseTransformMasksAsForward() {
        double[][] in = {{10.0}, {10.0}, {10.0}};
        Prism p = makePrism();
        double[][] inv = p.transform(in, 1, false);
        double[][] fwd = p.transform(in, 1, true);
        for (int j = 0; j < 3; j++) {
            assertEquals(fwd[j][0], inv[j][0]);
            assertEquals(BAD, inv[j][0]);
        }
    }

    @Test
    void accessSubRegions() {
        Prism p = makePrism();
        assertEquals(2, p.getRegion1().getNaxes());
        assertEquals(1, p.getRegion2().getNaxes());
    }

    @Test
    void frameNaxesMatchesCombined() {
        Frame frame = makePrism();
        assertEquals(3, frame.getNaxes());
    }

    @Test
    void mappingNinNoutMatch() {
        Prism p = makePrism();
        assertEquals(3, p.getNin());
        assertEquals(3, p.getNout());
    }

    @Test
    void meshIsNonemptyAndCorrectDimensionality() {
        double[][] mesh = makePrism().regBaseMesh();
        assertEquals(3, mesh.length);
        assertTrue(mesh[0].length > 0);
        for (int i = 0; i < mesh[0].length; i++) {
            for (int j = 0; j < 3; j++) {
                assertNotEquals(BAD, mesh[j][i]);
            }
        }
    }

    @Test
    void meshPointsAreOnPrismBoundary() {
        double[][] mesh = makePrism().regBaseMesh();
        for (int i = 0; i < mesh[0].length; i++) {
            double x = mesh[0][i];
            double y = mesh[1][i];
            double z = mesh[2][i];
            double d = Math.sqrt(x * x + y * y);
            boolean onCircle = Math.abs(d - 5.0) < 1e-6;
            boolean inCircle = x * x + y * y <= 25.0 + 1e-6;
            boolean onInterval = Math.abs(z - 2.0) < 1e-6 || Math.abs(z - 8.0) < 1e-6;
            boolean inInterval = z >= 2.0 - 1e-6 && z <= 8.0 + 1e-6;
            assertTrue((onCircle && inInterval) || (inCircle && onInterval), "point " + i);
        }
    }

    @Test
    void meshHasBothFaceTypes() {
        double[][] mesh = makePrism().regBaseMesh();
        boolean side = false;
        boolean cap = false;
        for (int i = 0; i < mesh[0].length; i++) {
            double d = Math.hypot(mesh[0][i], mesh[1][i]);
            side |= Math.abs(d - 5.0) < 1e-6;
            cap |= d <= 5.0 + 1e-6 && (Math.abs(mesh[2][i] - 2.0) < 1e-6 || Math.abs(mesh[2][i] - 8.0) < 1e-6);
        }
        assertTrue(side);
        assertTrue(cap);
    }

    // ---- Against C (probes/region_compound_probe.c) ----

    @Test
    void prismOfSkyAndSpectralRegionsMatchesC() {
        SkyFrame sky = new SkyFrame();
        Circle sc = new Circle(sky, 1, new double[] {1.0, 0.5}, new double[] {0.1}, null);
        SpecFrame spec = new SpecFrame();
        spec.set("System=FREQ,Unit=Hz");
        Interval si = new Interval(spec, new double[] {5.0e14}, new double[] {6.0e14}, null);
        Prism r = new Prism(sc, si);
        // The sky bounds come from the Circle's mesh on the sphere (SkyFrame.offset2), whose error
        // RegionEllipseOracleTest bounds by 4e-14; 1e-12 as there. The spectral bounds are exact.
        checkClose("prism_sky_spec", naxes(r) + "domain " + r.getDomain() + "\n" + bounds(r), 1.0e-12);
    }

    @Test
    void cylinderMatchesC() {
        double[] pts = {
            1.0, 1.0, 5.0, 1.0, 1.0, 10.0, 10.0, 10.0, 5.0, 10.0, 10.0, 10.0, 5.0, 0.0, 5.0, BAD, 0.0, 5.0, 0.0, BAD,
            5.0, 0.0, 0.0, BAD, 0.0, 0.0, 2.0
        };
        double[] vpts = {1.0, 1.0, 5.0, 10.0, 10.0, 5.0, 1.0, 1.0, 10.0};
        Prism r = cylinder();
        check(
                "prism",
                naxes(r)
                        + tran(r, 3, pts)
                        + tranValues(r, 3, true, vpts)
                        + tranValues(r, 3, false, vpts)
                        + bounded(r)
                        + bounds(r)
                        + fill(r)
                        + points(r)
                        + unc(r, true)
                        + "domain " + r.getDomain() + "\n");
        r.negate();
        check("prism_negated", tran(r, 3, pts) + bounded(r) + bounds(r));
        Prism m = cylinder();
        m.setMeshSize(60);
        checkClose("prism_mesh", mesh(m, true), MESH_TOL);
    }

    @Test
    void unboundedPrismMatchesC() {
        Interval iv = new Interval(new Frame(1), new double[] {2.0}, new double[] {BAD}, null);
        Prism r = new Prism(circle2(0.0, 0.0, 5.0), iv);
        // The last line of the case is astGetRegionMesh's own check that the Region is bounded,
        // which jniast does not have; the boundary mesh of such a Prism is prism.c's error.
        String want = CompoundProbe.PROBE.get("prism_unbounded");
        String wantStart = want.substring(0, want.indexOf("!!"));
        assertEquals(wantStart, bounded(r) + bounds(r));
        AstException e = assertThrows(AstException.class, r::regBaseMesh);
        assertEquals(AstException.AST__INTER, e.getStatus());
        assertEquals(
                "astRegBaseMesh(Prism): No mesh can be produced for the Prism bacause one of its component Regions is " // codespell:ignore bacause
                        + "unbounded.",
                e.getMsgText());
    }

    @Test
    void remappedPrismsMatchC() {
        int[] perm = {3, 2, 1};
        Region r = cylinder().mapRegionInto(new PermMap(3, perm, 3, perm, null), new Frame(3));
        check("prism_permuted", tran(r, 3, 5.0, 1.0, 1.0, 1.0, 1.0, 5.0, 5.0, 0.0, 4.0) + bounds(r) + bounded(r));
        Region z = cylinder().mapRegionInto(new ZoomMap(3, 2.0), new Frame(3));
        check("prism_zoomed", bounds(z));
    }

    @Test
    void pickAndDecomposeMatchC() {
        Prism r = cylinder();
        check("prism_pick", pick(r, 1, 2) + pick(r, 1, 3) + pick(r, 3));
        check("prism_decompose", decompose(r) + maplist(r));
    }

    @Test
    void decomposeGivesTheComponentsThemselves() {
        Prism r = cylinder();
        boolean[] series = {true};
        Mapping[] parts = r.decompose(series, null);
        assertFalse(series[0]);
        assertSame(r.getRegion1(), parts[0]);
        assertSame(r.getRegion2(), parts[1]);
    }

    /** prism.c {@code MapList}: a parallel decomposition lists the components, a series one the Prism. */
    @Test
    void mapListDecomposesInParallelOnly() {
        Prism r = cylinder();
        List<Mapping> maps = new ArrayList<>();
        List<Boolean> inverts = new ArrayList<>();
        assertFalse(r.mapList(false, true, maps, inverts));
        assertEquals(2, maps.size());
        assertSame(r.getRegion1(), maps.get(0));
        assertSame(r.getRegion2(), maps.get(1));
        assertEquals(false, inverts.get(0));
        assertEquals(false, inverts.get(1));
        maps.clear();
        inverts.clear();
        r.mapList(true, false, maps, inverts);
        assertEquals(1, maps.size());
        assertSame(r, maps.get(0));
    }

    /**
     * A parallel CmpMap holding a Prism decomposes it into its components when simplified, as C's
     * cmpmap.c does through prism.c {@code MapList}.
     */
    @Test
    void parallelCmpMapListsThePrismComponents() {
        Prism r = cylinder();
        CmpMap m = new CmpMap(r, new UnitMap(1), false);
        List<Mapping> maps = new ArrayList<>();
        m.mapList(false, false, maps, new ArrayList<>());
        assertEquals(3, maps.size());
        assertSame(r.getRegion1(), maps.get(0));
        assertSame(r.getRegion2(), maps.get(1));
        assertEquals("UnitMap", maps.get(2).astClass());
    }

    @Test
    void prismAsAnUncertaintyMatchesC() {
        Prism u = new Prism(box2(-0.1, -0.1, 0.1, 0.1), interval1(-0.2, 0.2));
        Box r = new Box(new Frame(3), 1, new double[] {0.0, 0.0, 0.0}, new double[] {10.0, 10.0, 10.0}, u);
        check("prism_as_uncertainty", unc(r, false));
    }

    @Test
    void overlapsMatchC() {
        Box big = box3(-10.0, -10.0, 0.0, 10.0, 10.0, 10.0);
        check("ov_prism_in_box", overlap(cylinder(), big));
        check("ov_prism_across_box", overlap(cylinder(), box3(0.0, -10.0, 0.0, 10.0, 10.0, 10.0)));
        check("ov_prism_apart_box", overlap(cylinder(), box3(20.0, 20.0, 0.0, 30.0, 30.0, 10.0)));
        check("ov_negated_prism_box", overlap(negatedCopy(cylinder()), big));
        check("ov_prism_negated_box", overlap(cylinder(), negatedCopy(big)));
        check("ov_negated_prism_negated_box", overlap(negatedCopy(cylinder()), negatedCopy(big)));
        check("ov_prism_prism", overlap(cylinder(), cylinder()));
        check("ov_prism_negated_prism", overlap(cylinder(), negatedCopy(cylinder())));
        Circle a = new Circle(new Frame(2), 0, new double[] {0.0, 0.0}, new double[] {5.0, 0.0}, null);
        check("ov_prism_prism_other_form", overlap(cylinder(), new Prism(a, interval1(2.0, 8.0))));
    }

    @Test
    void pointListPrismMatchesC() {
        PointList pl = new PointList(new Frame(2), 2, new double[][] {{1.0, 2.0}, {1.0, 2.0}}, null);
        Prism r = new Prism(pl, interval1(0.0, 1.0));
        check("prism_pointlist", bounded(r) + bounds(r) + mesh(r, true) + mesh(r, false));
    }

    /**
     * C's error when no point of the grid over a Region's bounding box lies in it (region.c {@code
     * RegBaseGrid}, {@code astSetNpoint} of 0): a PointList none of whose points is at a corner of
     * its bounding box, and a Prism of it, whose boundary mesh is made from its components' grids.
     * The grids are evenly spaced over the bounding box, 0 to 1 on each axis, with at most 223
     * points on an axis; each of the PointList's values other than 0 and 1 is at least 2.5e-6 from
     * every fraction with a denominator up to 300, which the test checks, so no grid point comes
     * within the PointList's default uncertainty, 5e-7 either side, whatever the rounding.
     */
    @Test
    void gridWithNoPointInsideIsCError() {
        for (double v : new double[] {0.3141, 0.2718, 0.577, 0.7071}) {
            for (int m = 1; m <= 300; m++) {
                assertTrue(Math.abs(v - Math.rint(v * m) / m) > 2.5e-6, v + " near a fraction of " + m);
            }
        }
        PointList pl = new PointList(
                new Frame(2), 4, new double[][] {{0.0, 0.2718, 1.0, 0.7071}, {0.3141, 0.0, 0.577, 1.0}}, null);
        check("pointlist_grid", bounds(pl) + error(() -> mesh(pl, false)));
        Prism r = new Prism(pl, interval1(0.0, 1.0));
        check("prism_pointlist_grid", error(() -> mesh(r, true)) + error(() -> mesh(r, false)));
    }

    @Test
    void maskMatchesC() {
        Prism r = new Prism(circle2(3.0, 3.0, 1.0), interval1(2.0, 4.0));
        check(
                "mask_prism",
                CompoundProbe.maskcount(r, new UnitMap(3), true, new int[] {1, 1, 1}, new int[] {5, 5, 5}, -1.0));
    }

    @Test
    void nullArgumentsAreRejected() {
        Box b = box2(0.0, 0.0, 1.0, 1.0);
        assertEquals(
                "region1",
                assertThrows(NullPointerException.class, () -> new Prism(null, b))
                        .getMessage());
        assertEquals(
                "region2",
                assertThrows(NullPointerException.class, () -> new Prism(b, null))
                        .getMessage());
    }

    @Test
    void componentsAreCopies() {
        Box b = box2(0.0, 0.0, 1.0, 1.0);
        Prism r = new Prism(b, interval1(0.0, 1.0));
        b.negate();
        assertFalse(r.getRegion1().getNegated());
        Prism copy = (Prism) r.copy();
        copy.getRegion1().negate();
        assertFalse(r.getRegion1().getNegated());
    }

    @Test
    void dumpsMatchC() {
        check("show_prism", ChannelText.dump(cylinder()));
        Prism c = new Prism(new Prism(box2(0.0, 0.0, 1.0, 1.0), interval1(0.0, 1.0)), interval1(2.0, 3.0));
        check("show_prism_of_prism", ChannelText.dump(c));
        check(
                "prism_of_prism",
                naxes(c) + bounds(c) + tran(c, 4, 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 4.0, 0.5));
        Prism r = cylinder();
        r.set("Adaptive=0,Label(3)=Height,Label(1)=X");
        check("show_prism_labels", ChannelText.dump(r));
        r.clear("Label(3)");
        check("show_prism_cleared", ChannelText.dump(r));
    }

    /** A Prism read back from its description is written as C writes the one it reads back. */
    @Test
    void dumpsReadBackAsC() {
        check("reload_prism", ChannelText.dump(ChannelText.read(ChannelText.dump(cylinder()))));
        Prism c = new Prism(new Prism(box2(0.0, 0.0, 1.0, 1.0), interval1(0.0, 1.0)), interval1(2.0, 3.0));
        Prism back = (Prism) ChannelText.read(ChannelText.dump(c));
        check("reload_prism_of_prism", ChannelText.dump(back));
        check(
                "prism_of_prism",
                naxes(back) + bounds(back) + tran(back, 4, 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 4.0, 0.5));
        Prism r = cylinder();
        r.set("Adaptive=0,Label(3)=Height,Label(1)=X");
        r.clear("Label(3)");
        check("reload_prism_cleared", ChannelText.dump(ChannelText.read(ChannelText.dump(r))));
    }
}
