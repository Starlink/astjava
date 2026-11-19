package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Translation of jniast's own {@code RegionTest} ({@code
 * reference/starjava/jniast/src/testcases/uk/ac/starlink/ast/RegionTest.java}): its 14 tests, with
 * the values it expects, which jniast found with C's library. jniast's {@code noTestShowMesh},
 * which it does not run, is left out.
 *
 * <p>Where jniast's JNI layer throws {@code IllegalArgumentException} before calling C, the
 * exceptions are those the library gives instead: an array shorter than the Frame has axes is
 * {@code AST__DIMIN} (the phase 1 ruling for too-short jniast arrays), an unknown Circle form is
 * C's {@code AST__BADIN}, and a null element of a PointList's points is {@code
 * NullPointerException} naming it. A PointList's element longer than the number of points, which
 * jniast rejects, is not tried: the library accepts a longer array, as C does. jniast's {@code
 * maskL} may throw {@code UnsupportedOperationException}, which it allows; here {@code maskL} is
 * implemented and is checked. A mask with no Mapping and more grid dimensions than the Region has
 * axes, which jniast rejects for its short bound arrays, fails C's check of the dimensions first,
 * {@code AST__NGDIN}.
 */
class JniastRegionTest {

    @Test
    void testBox() {
        Frame frm = new Frame(2);
        frm.setIdent("Region test frame");
        double[] lbnds = {10, 20};
        double[] ubnds = {100, 200};
        Box box = new Box(frm, 1, lbnds, ubnds, null);
        assertTrue(box.getBounded());
        double[][] bounds = box.getRegionBounds();
        assertArrayEquals(lbnds, bounds[0]);
        assertArrayEquals(ubnds, bounds[1]);
        assertEquals(frm, box.getRegionFrame());
        assertNotSame(frm, box.getRegionFrame()); // It's a deep copy.
    }

    @Test
    void testCircle() {
        Frame frm = new Frame(2);
        Circle unit = new Circle(frm, new double[2], 1., null);
        assertTrue(unit.getBounded());
        assertBounds(unit, -1, -1, 1, 1);
        assertSameShape(unit, new Circle(frm, new double[] {0, 0}, new double[] {0, 1}, null));
        assertStatus(AstException.AST__DIMIN, () -> new Circle(frm, new double[1], 1., null));
        assertStatus(AstException.AST__BADIN, () -> new Circle(frm, 2, new double[2], new double[2], null));
    }

    @Test
    void testEllipse() {
        Ellipse blob =
                new Ellipse(new Frame(2), 0, new double[] {0, 0}, new double[] {2, 0}, new double[] {0, 1}, null);
        assertBounds(blob, -2, -1, 2, 1, 1e-3);
        assertSameShape(
                blob,
                new Ellipse(new Frame(2), 1, new double[] {0, 0}, new double[] {1, 2}, new double[] {0, 0}, null));
    }

    @Test
    void testCmpRegion() {
        Box oblong1 = box(0, 0, 4, 8);
        Box oblong2 = box(4, 0, 8, 8);
        // As jniast notes, the uncertainties make the overlap of the two OVERLAP_PARTIAL rather
        // than OVERLAP_NONE.
        CmpRegion square = new CmpRegion(oblong1, oblong2, CmpRegion.AST__OR);
        assertSameShape(square, box(0, 0, 8, 8));

        Region s1 = box(0, 0, 2, 2);
        Region s2 = box(1, 1, 3, 3);
        assertSameShape(new CmpRegion(s1, s2, CmpRegion.AST__AND), box(1, 2, 2, 2));
    }

    @Test
    void testInterval() {
        Interval pos = new Interval(
                new Frame(2), new double[] {0, 0}, new double[] {AstObject.AST__BAD, AstObject.AST__BAD}, null);
        Box box = box(-1, -1, 1, 1);
        Region intersect = new CmpRegion(box, pos, CmpRegion.AST__AND);
        assertBounds(intersect, 0, 0, 1, 1);
        assertStatus(AstException.AST__DIMIN, () -> new Interval(new Frame(3), new double[2], new double[3], null));
    }

    @Test
    void testPointList() {
        Frame frm = new Frame(2);
        double[][] points = {{1, 2, 3, 4}, {101, 102, 103, 104}};
        PointList plist = new PointList(frm, 4, points, null);
        assertEquals(4, plist.getListSize());
        assertArrayEquals(points[0], plist.getRegionPoints()[0]);
        assertArrayEquals(points[1], plist.getRegionPoints()[1]);
        assertEquals(2, plist.getRegionPoints().length);
        assertBounds(plist, 1, 101, 4, 104);
        plist = new PointList(frm, 4, points, box(.01, .01, .01, .01));
        assertEquals(4, plist.getListSize());

        assertStatus(
                AstException.AST__DIMIN, () -> new PointList(frm, 4, new double[][] {{1, 2, 3, 4}, {1, 2, 3}}, null));
        assertStatus(
                AstException.AST__DIMIN,
                () -> new PointList(new Frame(3), 4, new double[][] {{1, 2, 3, 4}, {1, 2, 3, 4}}, null));
        assertThrows(
                NullPointerException.class, () -> new PointList(frm, 4, new double[][] {{1, 2, 3, 4}, null}, null));
    }

    @Test
    void testPolygon() {
        assertSameShape(
                box(0, 0, 1, 1),
                new Polygon(new Frame(2), 4, new double[] {0, 1, 1, 0}, new double[] {0, 0, 1, 1}, null));
    }

    @Test
    void testPrism() {
        Prism oblong = new Prism(
                new Box(new Frame(1), 1, new double[] {10}, new double[] {20}, null),
                new Interval(new Frame(1), new double[] {-6}, new double[] {-3}, null));
        assertBounds(oblong, 10, -6, 20, -3);

        Region hyperSphere = new Circle(new Frame(4), new double[] {Math.PI, Math.E, 1, 0}, 23., null);
        assertEquals(6, new Prism(hyperSphere, box(100, 500, 120, 520)).getNaxes());
    }

    @Test
    void testUnc() {
        Region box = box(0, 0, 100, 100);
        assertNull(box.getUnc(false));
        assertNotNull(box.getUnc(true));

        Region blob = new Box(new Frame(2), 0, new double[2], new double[] {0.1, 0.1}, null);
        box.setUnc(blob);
        assertNotNull(box.getUnc(false));
        assertNotNull(box.getUnc(false));
        assertEquals(box.getUnc(false), box.getUnc(true));
        // As jniast notes, the uncertainty need not equal blob: it may be a translated copy.
    }

    @Test
    void testNullRegion() {
        Region unc = box(0, 0, 1e-6, 1e-6);
        NullRegion nr = new NullRegion(new Frame(2), unc);
        Box box = box(1, 2, 3, 4);
        assertEquals(Region.OVERLAP_NONE, box.overlap(nr));
        assertFalse(nr.getNegated());
        nr.negate();
        assertTrue(nr.getNegated());
        assertEquals(Region.OVERLAP_INSIDE, box.overlap(nr));
    }

    @Test
    void testMapRegion() {
        Region moved = box(1, 2, 21, 22).mapRegion(new ZoomMap(2, 2.0), new Frame(2));
        assertBounds(moved, 2, 4, 42, 44);
    }

    @Test
    void testNegate() {
        Region box = box(-50, -23, 51, 51);
        assertFalse(box.getNegated());
        box.negate();
        assertTrue(box.getNegated());
        box.negate();
        assertFalse(box.getNegated());
        box.setNegated(true);
        assertTrue(box.getNegated());
        box.setNegated(false);
        assertFalse(box.getNegated());
    }

    @Test
    void testOverlap() {
        Box unit = box(0, 0, 1, 1);
        assertEquals(Region.OVERLAP_NONE, unit.overlap(box(5, 6, 7, 8)));
        assertEquals(Region.OVERLAP_INSIDE, unit.overlap(box(-1, -1, 2, 2)));
        assertEquals(Region.OVERLAP_OUTSIDE, unit.overlap(box(.1, .1, .9, .9)));
        assertEquals(Region.OVERLAP_PARTIAL, unit.overlap(box(.5, .5, 2, 2)));
        assertEquals(Region.OVERLAP_SAME, unit.overlap((Region) unit.copy()));
        Box unUnit = (Box) unit.copy();
        unUnit.negate();
        assertEquals(Region.OVERLAP_NEGATE, unit.overlap(unUnit));
    }

    @Test
    void testMask() {
        Mapping map = new UnitMap(2);
        int[] lbnd = {10, 20};
        int[] ubnd = {11, 21};
        Region region = box(9.5, 19.5, 11.5, 20.5);

        byte[] inB = {0, 0, 0, 0};
        assertEquals(2, region.maskB(null, true, 2, lbnd, ubnd, inB, (byte) -1));
        assertArrayEquals(new byte[] {-1, -1, 0, 0}, inB);

        short[] inS = {5, 5, 5, 5};
        assertEquals(2, region.maskS(null, false, 2, lbnd, ubnd, inS, (short) 10));
        assertArrayEquals(new short[] {5, 5, 10, 10}, inS);

        int[] inI = {0, 0, 0, 0};
        assertEquals(2, region.maskI(map, true, 2, lbnd, ubnd, inI, 1));
        assertArrayEquals(new int[] {1, 1, 0, 0}, inI);

        long[] inL = {0L, 0L, 0L, 0L};
        assertEquals(2, region.maskL(map, true, 2, lbnd, ubnd, inL, 1L));
        assertArrayEquals(new long[] {1L, 1L, 0, 0}, inL);

        float[] inF = {1.f, 2.f, 3.f, 4.f};
        assertEquals(2, region.maskF(map, true, 2, lbnd, ubnd, inF, Float.NaN));
        assertArrayEquals(new float[] {Float.NaN, Float.NaN, 3.f, 4.f}, inF);

        double[] inD = {99., 99., 99., 99.};
        assertEquals(1, region.maskD(new ShiftMap(new double[] {1., 0.}), true, 2, lbnd, ubnd, inD, Math.E));
        assertArrayEquals(new double[] {99., Math.E, 99., 99.}, inD);

        // jniast rejects this for having too few elements in lbnd before calling C; here C's own
        // check comes first, that with no Mapping the grid has a dimension for each axis of the
        // Region.
        assertStatus(AstException.AST__NGDIN, () -> region.maskI(null, true, 3, lbnd, ubnd, new int[4], -1));
        // Not enough elements in the data array.
        assertStatus(AstException.AST__DIMIN, () -> region.maskD(map, false, 2, lbnd, ubnd, new double[3], -1.));
    }

    private static Box box(double x1, double y1, double x2, double y2) {
        return new Box(new Frame(2), 1, new double[] {x1, y1}, new double[] {x2, y2}, null);
    }

    private static void assertBounds(Region region, double xlo, double ylo, double xhi, double yhi, double delta) {
        double[][] bounds = region.getRegionBounds();
        assertArrayEquals(
                new double[] {xlo, ylo, xhi, yhi},
                new double[] {bounds[0][0], bounds[0][1], bounds[1][0], bounds[1][1]},
                delta);
    }

    private static void assertBounds(Region region, double xlo, double ylo, double xhi, double yhi) {
        assertBounds(region, xlo, ylo, xhi, yhi, 0.0);
    }

    private static void assertSameShape(Region region1, Region region2) {
        int ov = region1.overlap(region2);
        assertEquals(Region.OVERLAP_SAME, ov, "Regions don't match (" + ov + ")");
    }

    private static void assertStatus(int status, Executable call) {
        assertEquals(status, assertThrows(AstException.class, call).getStatus());
    }
}
