package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_regions_c.rs}: functions of C's {@code
 * ast_tester/testregions.c} that the class tests do not cover, one test per function, each
 * following its C function's sequence with C's error text in its messages and C's own literals
 * and tolerances as the expected values.
 *
 * <p>{@code checkEllipse} includes Errors 15 to 17, which Rust excludes ({@code
 * REGIONS_C_GAP_EXCLUDED}): an Ellipse with a Box uncertainty mapped through a full MatrixMap and
 * back gives C's result, an Ellipse with no FrameSet each way that overlaps the original as the
 * same Region. Rust's {@code region_gaps_still_differ}, which checks that Rust's gap persists, is
 * listed in {@code deferred/rust-tests.txt}, as is {@code check_remove_regions} ({@code
 * astRemoveRegions}, which is not in this phase).
 */
class RegionsCTest {

    private static final double BAD = AstObject.AST__BAD;

    private static Frame frame2() {
        return new Frame(2);
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    private static Box boxBounds(Frame f, double[] lbnd, double[] ubnd) {
        return new Box(f, 1, lbnd, ubnd, null);
    }

    private static double[] tranPoint(Mapping m, double x, double y) {
        double[][] out = m.tran2(1, new double[] {x}, new double[] {y}, true);
        return new double[] {out[0][0], out[1][0]};
    }

    /** The points {@code pts}, each {x, y}, transformed by {@code m}, each {x, y}. */
    private static double[][] tran2(Mapping m, double[][] pts, boolean forward) {
        int n = pts.length;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = pts[i][0];
            y[i] = pts[i][1];
        }
        double[][] out = m.tran2(n, x, y, forward);
        double[][] result = new double[n][];
        for (int i = 0; i < n; i++) {
            result[i] = new double[] {out[0][i], out[1][i]};
        }
        return result;
    }

    /**
     * C's {@code hasframeset}: whether the Region's dump holds a FrameSet before its uncertainty,
     * that is, whether its own Frame needs one.
     */
    static boolean hasFrameSet(AstObject reg) {
        for (String line : ChannelText.dump(reg).split("\n", -1)) {
            if (line.contains("Unc =")) {
                return false;
            }
            if (line.contains("FrameSet")) {
                return true;
            }
        }
        return false;
    }

    /** C's {@code checkdump}: the dump reads back to a Region that dumps the same, or failing that is the same (overlap 5). */
    static void checkDump(Region reg, String text) {
        String s1 = ChannelText.dump(reg);
        AstObject back = ChannelText.read(s1);
        String s2 = ChannelText.dump(back);
        if (!s1.equals(s2)) {
            assertEquals(Region.OVERLAP_SAME, reg.overlap(assertInstanceOf(Region.class, back, text)), text);
        }
    }

    /**
     * {@code checkBoxTransformAfterBounds}: a zero-width Box contains a point just off its
     * degenerate axis whether its bounds are asked for before or after the transformation, and the
     * bounds stay at the degenerate value.
     */
    @Test
    void checkBoxTransformAfterBounds() {
        double[] l = {1.0, 5.0};
        double[] u = {2.0, 5.0};
        Box a = boxBounds(frame2(), l, u);
        assertNotEquals(BAD, tranPoint(a, 1.5, 5.0 + 1.0e-6)[0], "Box transform after bounds 1");
        double[][] bounds = a.getRegionBounds();
        assertTrue(bounds[0][1] == 5.0 && bounds[1][1] == 5.0, "Box transform after bounds 2");
        Box b = boxBounds(frame2(), l, u);
        b.getRegionBounds();
        assertNotEquals(BAD, tranPoint(b, 1.5, 5.0 + 1.0e-6)[0], "Box transform after bounds 3");
    }

    /**
     * {@code checkPolygonMaskLargeLobe}: an ICRS Polygon wound so that its inside is the large lobe
     * masks every pixel of a grid far from the small square its vertices outline.
     */
    @Test
    void checkPolygonMaskLargeLobe() {
        double ra0 = 0.5;
        double dec0 = 0.0;
        double half = 0.001;
        double fra = ra0 + 0.1;
        double fdec = dec0;
        int g = 20;
        double span = 0.002;
        Polygon poly = new Polygon(
                sky("ICRS"),
                4,
                new double[] {ra0 - half, ra0 + half, ra0 + half, ra0 - half},
                new double[] {dec0 - half, dec0 - half, dec0 + half, dec0 + half},
                null);
        assertEquals(BAD, tranPoint(poly, ra0, dec0)[0], "setup: square centre is not outside the region");
        assertNotEquals(BAD, tranPoint(poly, fra, fdec)[0], "setup: far point is not inside the large lobe");
        WinMap sky2pix = new WinMap(
                2,
                new double[] {fra - span, fdec - span},
                new double[] {fra + span, fdec + span},
                new double[] {0.0, 0.0},
                new double[] {g, g});
        int npix = (g + 1) * (g + 1);
        int[] data = new int[npix];
        poly.maskI(sky2pix, true, 2, new int[] {0, 0}, new int[] {g, g}, data, 1);
        int nmasked = 0;
        for (int v : data) {
            if (v != 0) {
                nmasked++;
            }
        }
        assertEquals(npix, nmasked, "astMaskI masked " + nmasked + " of " + npix + " in-region pixels");
    }

    /**
     * {@code checkNullRegion}: a NullRegion over a 4-axis CmpFrame and a Circle in it; their
     * overlaps before and after negation; simplifying through a change of sky system leaves no
     * FrameSet; and masking with a negated and a plain NullRegion.
     */
    @Test
    void checkNullRegion() {
        Frame f2 = frame2();
        CmpFrame f3 = new CmpFrame(sky("ICRS"), f2);
        NullRegion nr = new NullRegion(f3, null);
        checkDump(nr, "checkdump NullRegion:nr");
        Circle cir = new Circle(nr, 0, new double[] {1.0, 1.0, 3.0, 3.0}, new double[] {1.01, 1.02, 3.01, 3.01}, null);
        checkDump(cir, "checkdump NullRegion:cir");
        assertEquals(1, cir.overlap(nr), "NullRegion: Error 1");
        assertEquals(1, nr.overlap(cir), "NullRegion: Error 2");
        assertEquals(5, nr.overlap(nr), "NullRegion: Error 3");
        nr.negate();
        assertEquals(2, cir.overlap(nr), "NullRegion: Error 4");
        assertEquals(3, nr.overlap(cir), "NullRegion: Error 5");
        assertEquals(5, nr.overlap(nr), "NullRegion: Error 6");
        nr.set("system(1)=FK4");
        Region nr2 = (Region) nr.simplify();
        nr2.set("system(1)=ICRS");
        Region nr3 = (Region) nr2.simplify();
        assertFalse(hasFrameSet(nr3), "NullRegion: error 7");

        int[] lbnd = {1, 1};
        int[] ubnd = {5, 5};
        double[] data = new double[25];
        Arrays.fill(data, 1.0);
        NullRegion nr4 = new NullRegion(f2, null);
        nr4.negate();
        assertEquals(0, nr4.maskD(null, false, 2, lbnd, ubnd, data, BAD), "res should be 0");
        for (double v : data) {
            assertEquals(1.0, v, "Above value should be 1.0");
        }
        nr4.negate();
        assertEquals(25, nr4.maskD(null, false, 2, lbnd, ubnd, data, BAD), "res should be 25");
        for (double v : data) {
            assertEquals(BAD, v, "Above value should be BAD");
        }
    }

    /**
     * {@code checkPointList}: a PointList of two spectral positions with a Box uncertainty overlaps
     * itself (5) and its negation (6), contains only its points, maps through y = x squared to a
     * Region holding 1.21 but not 1.5, and masks two pixels through a WinMap.
     */
    @Test
    void checkPointList() {
        SpecFrame frm = new SpecFrame();
        frm.setSystem("WAVELEN");
        Box unc = new Box(frm, 0, new double[] {0.0}, new double[] {1.0e-5}, null);
        PointList reg = new PointList(frm, 2, new double[][] {{1.0, 1.1}}, unc);
        checkDump(reg, "checkdump reg");
        assertEquals(5, reg.overlap(reg), "PointList: self is not identical with self");
        PointList reg2 = (PointList) reg.copy();
        reg2.negate();
        checkDump(reg2, "checkdump reg2");
        assertEquals(6, reg.overlap(reg2), "PointList: overlap with self-exclusion");
        double[] xs = {1.0, 1.05, 1.1};
        assertArrayEquals(new double[] {1.0, BAD, 1.1}, reg.tran1(3, xs, true), "PointList: Errors 1-3");
        assertArrayEquals(new double[] {BAD, 1.05, BAD}, reg2.tran1(3, xs, true), "PointList: Errors 4-6");

        MathMap mm = new MathMap(1, 1, new String[] {"y=x**2"}, new String[] {"x=y**0.5"});
        Region reg3 = reg.mapRegion(mm, new Frame(1));
        Region reg4 = (Region) reg3.simplify();
        checkDump(reg4, "checkdump reg4");
        assertArrayEquals(
                new double[] {1.21, BAD}, reg4.tran1(2, new double[] {1.21, 1.5}, true), "PointList: Errors 7-8");

        WinMap map = new WinMap(1, new double[] {1.01}, new double[] {1.11}, new double[] {2.0}, new double[] {7.0});
        int[] lbnd = {-1};
        int[] ubnd = {15};
        int[] mdata = new int[17];
        assertEquals(2, reg.maskI(map, true, 1, lbnd, ubnd, mdata, 2), "nbad");
        int[][] wants = {{1, 0}, {2, 2}, {3, 0}, {6, 0}, {7, 2}, {8, 0}};
        for (int[] pw : wants) {
            assertEquals(pw[1], mdata[pw[0] - lbnd[0]], "mdata(" + pw[0] + ")");
        }
    }

    /**
     * {@code checkPrism}: an FK5 Box extruded through a wavelength Interval. It simplifies to a Box
     * with no FrameSet; its overlaps with other Prisms, a negated copy, open Prisms and
     * half-unbounded Intervals in other systems; and the bounds of a Prism over the south pole.
     */
    @Test
    void checkPrism() {
        SpecFrame f2 = angstrom(null);
        Box r1 = skyBox(0.0, 0.0, 1.0e-4, 1.0e-4);
        Prism r3 = new Prism(r1, interval1(f2, 5000.0, 6000.0));
        checkDump(r3, "checkdump Prism 1");
        assertEquals(5, r3.overlap(r3), "Prism 1");
        Mapping r4m = r3.simplify();
        assertInstanceOf(Box.class, r4m, "Prism 1b");
        assertFalse(hasFrameSet(r4m), "Prism 1c");
        assertEquals(5, r3.overlap((Region) r4m), "Prism 1d");

        Prism r4 = new Prism(r1, interval1(f2, 5500.0, 5800.0));
        assertEquals(3, r3.overlap(r4), "Prism 2");
        assertEquals(2, r4.overlap(r3), "Prism 3");
        r4 = new Prism(r1, interval1(f2, 5500.0, 6500.0));
        assertEquals(4, r3.overlap(r4), "Prism 4");
        assertEquals(4, r4.overlap(r3), "Prism 5");
        r4 = new Prism(r1, interval1(f2, 6500.0, 7500.0));
        assertEquals(1, r3.overlap(r4), "Prism 6");
        assertEquals(1, r4.overlap(r3), "Prism 7");
        r4 = (Prism) r3.copy();
        r4.negate();
        assertEquals(6, r4.overlap(r3), "Prism 8");

        Box r1b = skyBox(2.0e-4, 2.0e-4, 1.1e-4, 1.0e-4);
        r4 = new Prism(r1b, interval1(f2, 5000.0, 6000.0));
        assertEquals(1, r3.overlap(r4), "Prism 9");
        Box r1c = skyBox(2.0e-4, 2.0e-4, 1.0e-4, 1.0e-4);
        r4 = new Prism(r1c, interval1(f2, 5000.0, 6000.0));
        assertEquals(4, r3.overlap(r4), "Prism 10");
        Prism r3o = (Prism) r3.copy();
        r3o.setClosed(false);
        r4.setClosed(false);
        assertEquals(1, r3o.overlap(r4), "Prism 11");

        f2 = angstrom("WAVELEN");
        r1 = skyBox(0.0, 0.0, 1.0e-4, 1.0e-4);
        r3 = new Prism(r1, interval1(f2, 5000.0, BAD));
        r4 = new Prism(r1, interval1(f2, 6000.0, BAD));
        r3.set("system(1)=galactic");
        assertEquals(3, r3.overlap(r4), "Prism 12");
        r4 = new Prism(r1, interval1(f2, BAD, 6000.0));
        assertEquals(4, r3.overlap(r4), "Prism 13");
        r4 = new Prism(r1, interval1(f2, BAD, 5000.0));
        r4.set("system(3)=freq");
        assertEquals(4, r3.overlap(r4), "Prism 14");
        r4.setClosed(false);
        assertEquals(1, r3.overlap(r4), "Prism 15");

        r1 = skyBox(0.0, -1.57, 0.8, -1.5);
        r3 = new Prism(r1, interval1(angstrom(null), 5000.0, 6000.0));
        double[][] b = ((Region) r3.simplify()).getRegionBounds();
        assertEquals(-0.8, b[0][0], 1e-6, "Prism 16");
        assertEquals(-1.64, b[0][1], 1e-6, "Prism 17");
        assertEquals(0.8, b[1][0], 1e-6, "Prism 18");
        assertEquals(-1.5, b[1][1], 1e-6, "Prism 19");
        assertEquals(5000.0, b[0][2], 1e-10, "Prism 20");
        assertEquals(6000.0, b[1][2], 1e-6, "Prism 21");
    }

    /** A SpecFrame in Angstrom, with System {@code system} if not null. */
    private static SpecFrame angstrom(String system) {
        SpecFrame f = new SpecFrame();
        if (system != null) {
            f.setSystem(system);
        }
        f.setUnit(1, "Angstrom");
        return f;
    }

    /** A Box in FK5 of centre (cx, cy) and corner (kx, ky). */
    private static Box skyBox(double cx, double cy, double kx, double ky) {
        return new Box(sky("FK5"), 0, new double[] {cx, cy}, new double[] {kx, ky}, null);
    }

    private static Interval interval1(Frame f, double lo, double hi) {
        return new Interval(f, new double[] {lo}, new double[] {hi}, null);
    }

    /** An Ellipse of form 0: centre {@code c}, end of an axis {@code p}, a point {@code q} on it. */
    private static Ellipse form0(Frame f, double[] c, double[] p, double[] q, Region unc) {
        return new Ellipse(f, 0, c, p, q, unc);
    }

    /** An Ellipse of form 1: centre {@code c}, half-lengths {@code axes}, angle {@code angle}. */
    private static Ellipse form1(Frame f, double[] c, double[] axes, double angle) {
        return new Ellipse(f, 1, c, axes, new double[] {angle}, null);
    }

    /** A Box from (-1e-7, -1e-7) to (1e-7, 1e-7) in {@code f}, C's uncertainty in checkEllipse. */
    private static Box uncBox(Frame f) {
        return new Box(f, 0, new double[] {0.0, 0.0}, new double[] {1.0e-7, 1.0e-7}, null);
    }

    /**
     * {@code checkEllipse}: an Ellipse in a permuted CmpFrame; Ellipses near the pole of a TAN
     * header's SkyFrame (bounds, membership, overlaps with a copy in another system and with copies
     * shifted in pixel coordinates); mapping through a MatrixMap and back; a circular Ellipse
     * simplifying to a Circle; and overlaps across RA = 0.
     */
    @Test
    void checkEllipse() {
        Frame picked = sky("FK4").pickAxes(1, new int[] {1}, null);
        SpecFrame spec = new SpecFrame();
        spec.setSystem("WAVELEN");
        spec.setUnit(1, "um");
        CmpFrame f3 = new CmpFrame(picked, spec);
        f3.permAxes(new int[] {2, 1});
        Ellipse ell1 =
                form0(f3, new double[] {0.0, 0.0}, new double[] {0.001, 0.001}, new double[] {-0.001, 0.001}, null);
        double[][] pts = {{0.0, 0.00141421}, {0.0, 0.00141422}, {-0.000999, 0.0009999}, {-0.001001, 0.001001}};
        double[][] out = tran2(ell1, pts, true);
        assertArrayEquals(pts[0], out[0], "Ellipse: Cmp 1-2");
        assertArrayEquals(new double[] {BAD, BAD}, out[1], "Ellipse: Cmp 3-4");
        assertArrayEquals(pts[2], out[2], "Ellipse: Cmp 5-6");
        assertArrayEquals(new double[] {BAD, BAD}, out[3], "Ellipse: Cmp 7-8");
        checkDump(ell1, "checkdump ell1 cmp");
        Region ell2 = (Region) ell1.simplify();
        checkDump(ell2, "checkdump ell2 cmp");
        assertEquals(5, ell1.overlap(ell2), "ellipse: Error 5 cmp");

        FitsChan fc = new FitsChan();
        for (String card : new String[] {
            "NAXIS1  = 300",
            "NAXIS2  = 300",
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
            "CRPIX1  = 100",
            "CRPIX2  = 100",
            "CRVAL1  = 0.0",
            "CRVAL2  = 90.0",
            "CDELT1  = 0.6",
            "CDELT2  = 0.6"
        }) {
            fc.putFits(card, false);
        }
        fc.setCard(1);
        FrameSet fs = (FrameSet) fc.read();
        Frame skyf = fs.getFrame(FrameSet.AST__CURRENT);
        double[] p1 = {1.2217305, 1.570796};
        double[] p2 = {0.9, 1.470796};
        double[] p3 = {2.9217305, 1.370796};
        ell1 = form0(skyf, p1, p2, p3, null);
        checkDump(ell1, "checkdump ell1");
        double[][] b = ell1.getRegionBounds();
        assertTrue(Math.abs(b[0][0]) <= 1e-10, "Error b1");
        assertEquals(1.19059777, b[0][1], 1e-6, "Error b2");
        assertEquals(6.28318531, b[1][0], 1e-6, "Error b3");
        assertEquals(1.57079633, b[1][1], 1e-6, "Error b4");
        double[][] towards = {p2, p3};
        String[] labels = {"Errors 1-4", "Errors 1c-4c"};
        for (int it = 0; it < 2; it++) {
            double[] toward = towards[it];
            String label = labels[it];
            double rad = ell1.distance(p1, toward);
            for (double sign : new double[] {1.0, -1.0}) {
                String suffix = sign > 0 ? "" : " (opposite)";
                double[] inside = skyf.offset(p1, toward, sign * rad * 0.999);
                double[] outside = skyf.offset(p1, toward, sign * rad * 1.001);
                double[][] o = tran2(ell1, new double[][] {inside, outside}, true);
                assertArrayEquals(inside, o[0], "Ellipse: " + label + suffix + " inside");
                assertArrayEquals(new double[] {BAD, BAD}, o[1], "Ellipse: " + label + suffix + " outside");
            }
        }
        Ellipse ell2e = (Ellipse) ell1.copy();
        assertEquals(5, ell1.overlap(ell2e), "ellipse: Error 5");
        assertEquals(5, ell2e.overlap(ell1), "ellipse: Error 6");
        ell2e.setSystem("GALACTIC");
        assertEquals(5, ell1.overlap(ell2e), "ellipse: Error 7");
        assertEquals(5, ell2e.overlap(ell1), "ellipse: Error 8");

        // Copies shifted in pixel coordinates by 1.95 and 2.05 times the primary semi-axis. C
        // measures the 2.05 shift with its Frame already the current (sky) Frame, so that offset
        // is taken in the SkyFrame.
        double[][] q = tran2(fs, new double[][] {p1, p2, p3}, false);
        Frame grid = fs.getFrame(FrameSet.AST__BASE);
        double rad = grid.distance(q[0], q[1]);
        Object[][] shifts = {{1.95, grid, 4, 9}, {2.05, skyf, 1, 11}};
        for (Object[] sh : shifts) {
            double factor = (Double) sh[0];
            Frame offsetFrame = (Frame) sh[1];
            int want = (Integer) sh[2];
            int err = (Integer) sh[3];
            double[] q1b = offsetFrame.offset(q[0], q[1], rad * factor);
            double[] d = {q1b[0] - q[0][0], q1b[1] - q[0][1]};
            double[][] shifted = {q1b, {q[1][0] + d[0], q[1][1] + d[1]}, {q[2][0] + d[0], q[2][1] + d[1]}};
            double[][] pb = tran2(fs, shifted, true);
            Ellipse e2 = form0(skyf, pb[0], pb[1], pb[2], uncBox(skyf));
            assertEquals(want, e2.overlap(ell1), "ellipse: Error " + err);
            assertEquals(want, ell1.overlap(e2), "ellipse: Error " + (err + 1));
        }
        double[] p2b = {p2[0], 0.9 * p2[1] + 0.1 * p1[1]};
        double[] p3b = {p3[0], 0.9 * p3[1] + 0.1 * p1[1]};
        Ellipse e2 = form0(skyf, p1, p2b, p3b, uncBox(skyf));
        assertEquals(2, e2.overlap(ell1), "ellipse: Error 13");
        assertEquals(3, ell1.overlap(e2), "ellipse: Error 14");

        // Through a full MatrixMap and back.
        Frame f = frame2();
        Ellipse ellm = form0(f, new double[] {0.0, 0.0}, new double[] {1.0, 0.0}, new double[] {0.0, 0.5}, uncBox(f));
        MatrixMap mm = new MatrixMap(2, 2, new double[][] {{1.73, 0.5003}, {-1.0006, 0.866}});
        Region ellm2 = ellm.mapRegion(mm, f);
        checkDump(ellm2, "checkdump ell2");
        assertFalse(hasFrameSet(ellm2), "Ellipse: error 15");
        mm.invert();
        Region ellm3 = ellm2.mapRegion(mm, f);
        assertFalse(hasFrameSet(ellm3), "Ellipse: error 16");
        assertEquals(5, ellm.overlap(ellm3), "ellipse: Error 17");

        // A circular Ellipse simplifies to a Circle.
        Ellipse ellc = form0(f, new double[] {0.0, 0.0}, new double[] {1.0, 0.0}, new double[] {0.0, 1.0}, uncBox(f));
        Mapping reg = ellc.simplify();
        assertInstanceOf(Circle.class, reg, "ellipse: Error 18");
        Circle cir = new Circle(f, new double[] {0.0, 0.0}, new double[] {1.0, 0.0}, null);
        assertEquals(5, ((Region) reg).overlap(cir), "Ellipse: Error 19");

        // Across RA = 0.
        SkyFrame sf = sky("ICRS");
        Ellipse a = form1(sf, new double[] {0.0, 0.0}, new double[] {0.01, 0.01}, 0.0);
        Ellipse c = form1(sf, new double[] {-0.015, 0.0}, new double[] {0.01, 0.01}, 0.0);
        assertEquals(4, a.overlap(c), "Ellipse: Error 20");
        c = form1(sf, new double[] {6.2681853, 0.0}, new double[] {0.01, 0.01}, 0.0);
        assertEquals(4, a.overlap(c), "Ellipse: Error 21");
        a = form1(sf, new double[] {-0.015, 0.0}, new double[] {0.01, 0.01}, 0.0);
        assertEquals(5, a.overlap(c), "Ellipse: Error 22");
    }
    /** Checks that each of four points passes unchanged if {@code inside} says so, and is bad otherwise. */
    private static void expect(String label, Mapping m, double[] xs, double[] ys, boolean[] inside) {
        double[][] out = m.tran2(4, xs, ys, true);
        for (int i = 0; i < 4; i++) {
            double[] want = inside[i] ? new double[] {xs[i], ys[i]} : new double[] {BAD, BAD};
            assertArrayEquals(want, new double[] {out[0][i], out[1][i]}, "CmpRegion: " + label + " Error " + (i + 1));
        }
    }

    /**
     * {@code checkCmpRegion}: an FK5 Box combined with a Galactic Circle under AND and OR, with each
     * negated in turn; converting and mapping one CmpRegion onto its negation (overlap 6); A OR not
     * A and A AND not A simplifying to a negated and a plain NullRegion; and a Box with two Circles
     * cut out of it. Each CmpRegion is built from its components in the state C's have at that
     * point. C's check of the OR's bounding disc ({@code astGetRegionDisc}, not in jniast) is left
     * out.
     */
    @Test
    void checkCmpRegion() {
        Box r1 = new Box(sky("FK5"), 0, new double[] {0.0, 0.0}, new double[] {1.0e-4, 1.0e-4}, null);
        Circle r2 = new Circle(
                sky("GALACTIC"),
                0,
                new double[] {1.68166715892457, -1.050436507472},
                new double[] {1.68140254777194, -1.05048840003467},
                null);
        assertEquals(4, r1.overlap(r2), "CmpRegion: Error 0");
        CmpRegion cr = new CmpRegion(r1, r2, CmpRegion.AST__AND);
        assertEquals(5, cr.overlap(cr), "CmpRegion: Error 1");
        double[] xin = {0.5e-4, 1.5e-4, -0.5e-4, 1.1e-4};
        double[] yin = {0.5e-4, 1.5e-4, -0.5e-4, -1.1e-4};
        expect("AND", cr, xin, yin, new boolean[] {true, false, false, false});
        cr = new CmpRegion(r1, r2, CmpRegion.AST__OR);
        expect("OR", cr, xin, yin, new boolean[] {true, true, true, false});

        r2.negate();
        cr = new CmpRegion(r1, r2, CmpRegion.AST__AND);
        expect("AND, Circle negated", cr, xin, yin, new boolean[] {false, false, true, false});
        r1.negate();
        cr = new CmpRegion(r1, r2, CmpRegion.AST__AND);
        expect("AND, both negated", cr, xin, yin, new boolean[] {false, false, false, true});
        cr = new CmpRegion(r1, r2, CmpRegion.AST__AND);
        cr.negate();
        expect("negated AND", cr, xin, yin, new boolean[] {true, true, true, false});

        CmpRegion cr2 = new CmpRegion(r2, r1, CmpRegion.AST__AND);
        FrameSet fs = cr.convert(cr2, "");
        assertNotNull(fs, "CmpRegion: Error 5");
        Region cr3 = cr.mapRegion(
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), fs.getFrame(FrameSet.AST__CURRENT));
        assertEquals(6, cr3.overlap(cr2), "CmpRegion: Error 6");
        checkDump(cr, "checkdump CmpRegion: cr");

        cr2 = (CmpRegion) cr.copy();
        cr2.negate();
        Mapping simp = new CmpRegion(cr2, cr, CmpRegion.AST__OR).simplify();
        assertTrue(
                assertInstanceOf(NullRegion.class, simp, "CmpRegion: Error 7").getNegated(), "CmpRegion: Error 8");
        simp = new CmpRegion(cr2, cr, CmpRegion.AST__AND).simplify();
        assertFalse(
                assertInstanceOf(NullRegion.class, simp, "CmpRegion: Error 9").getNegated(), "CmpRegion: Error 10");

        Frame f1 = frame2();
        Box b1 = new Box(f1, 0, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, null);
        Circle c1 = new Circle(f1, 0, new double[] {-1.0, 0.0}, new double[] {0.0, 0.0}, null);
        Circle c2 = new Circle(f1, 0, new double[] {1.0, 0.0}, new double[] {0.0, 0.0}, null);
        CmpRegion cc = new CmpRegion(c1, c2, CmpRegion.AST__OR);
        checkDump(cc, "checkdump CmpRegion: cr");
        cc.negate();
        CmpRegion cr4 = new CmpRegion(cc, b1, CmpRegion.AST__AND);
        checkDump(cr4, "checkdump CmpRegion: cr2");
        Region s4 = (Region) cr4.simplify();
        double[] x2 = {0.0, 0.2, 0.5, -0.5};
        double[] y2 = {0.5, 1.5, 0.5, 0.5};
        expect("Errors 11-18", s4, x2, y2, new boolean[] {true, false, false, false});
        Region neg = (Region) s4.copy();
        neg.negate();
        expect("Errors 19-26", neg.simplify(), x2, y2, new boolean[] {false, true, true, true});
    }

    /** Checks that a point just inside {@code m} along the direction to {@code toward} passes and one just outside does not. */
    private static void checkProbe(Mapping m, Frame f, double[] c, double[] toward, double rad, String label) {
        double[] in = f.offset(c, toward, rad * 0.999);
        double[] out = f.offset(c, toward, rad * 1.001);
        int n = c.length;
        double[][] pts = new double[n][2];
        for (int i = 0; i < n; i++) {
            pts[i][0] = in[i];
            pts[i][1] = out[i];
        }
        double[][] res = m.transform(pts, 2, true);
        for (int i = 0; i < n; i++) {
            assertEquals(in[i], res[i][0], "Circle: " + label + " inside");
            assertEquals(BAD, res[i][1], "Circle: " + label + " outside");
        }
    }

    private static Circle edgeCircle(Frame f, double[] c, double[] e, Region unc) {
        return new Circle(f, 0, c, e, unc);
    }

    /**
     * {@code checkCircle}: Circles in the SkyFrame of a TAN header (bounds, boundary and interior
     * meshes, membership near the edge, a change of system and its FrameSet, overlaps, pole
     * bounds), in a Frame of another Domain (overlap 0), in a 4-axis CmpFrame, and 3-D spheres.
     * C's meshes come from {@code astGetRegionMesh}, which is not in jniast; the protected {@code
     * astRegMesh} and {@code astRegGrid} it calls give them here. C's checks of the bounding disc
     * ({@code astGetRegionDisc}, not in jniast) are left out.
     */
    @Test
    void checkCircle() {
        FitsChan fc = new FitsChan();
        for (String card : new String[] {
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
            "CRPIX1  = 100",
            "CRPIX2  = 100",
            "CRVAL1  = 70.0",
            "CRVAL2  = 80.0",
            "CDELT1  = 0.6",
            "CDELT2  = 0.6"
        }) {
            fc.putFits(card, false);
        }
        fc.setCard(1);
        FrameSet fs = (FrameSet) fc.read();
        Frame frm1 = fs.getFrame(FrameSet.AST__CURRENT);

        Circle cir1 = new Circle(frm1, new double[] {0.0, 1.0}, 0.01, null);
        double[][] b = cir1.getRegionBounds();
        assertEquals(-0.01850666061475259, b[0][0], 1e-6, "Circle: Error AA1");
        assertEquals(0.9900000002235173, b[0][1], 1e-6, "Circle: Error AA2");
        assertEquals(0.01850666061475276, b[1][0], 1e-6, "Circle: Error AA3");
        assertEquals(1.009994987166073, b[1][1], 1e-6, "Circle: Error AA4");

        double[] p1 = {0.0, 1.57};
        cir1 = new Circle(frm1, p1, 0.01, null);
        b = cir1.getRegionBounds();
        assertEquals(0.0, b[0][0], 1e-6, "Circle: Error AA5");
        assertEquals(1.560000052675599, b[0][1], 1e-6, "Circle: Error AA6");
        assertEquals(6.283185307179586, b[1][0], 1e-6, "Circle: Error AA7");
        assertEquals(1.5707963267948966, b[1][1], 1e-6, "Circle: Error AA8");
        double[][] mesh = cir1.regMesh();
        assertEquals(200, mesh[0].length, "Circle: Error mesh 1");
        for (int i = 0; i < mesh[0].length; i++) {
            double d = frm1.distance(p1, new double[] {mesh[0][i], mesh[1][i]});
            assertEquals(0.01, d, 1e-6, "Circle: Error mesh 2");
        }
        double[][] grid = cir1.regGrid();
        assertEquals(201, grid[0].length, "Circle: Error mesh 3");
        for (int i = 0; i < grid[0].length; i++) {
            assertTrue(frm1.distance(p1, new double[] {grid[0][i], grid[1][i]}) <= 0.01, "Circle: Error mesh 4");
        }

        p1 = new double[] {1.2217305, 1.3962634};
        double[] e8 = {0.8, 0.8};
        cir1 = edgeCircle(frm1, p1, e8, null);
        checkDump(cir1, "checkdump cir1");
        double rad = cir1.distance(p1, e8);
        checkProbe(cir1, frm1, p1, e8, rad, "Errors 1-4");
        assertArrayEquals(new double[] {0.0, 1.5707963}, tranPoint(cir1, 0.0, 1.5707963), "Circle: Error 1b-2b");
        checkProbe(cir1, frm1, p1, new double[] {0.0, 0.0}, rad, "Errors 5-8");
        cir1.setSystem("GALACTIC");
        assertTrue(hasFrameSet(cir1.simplify()), "Circle: error 9");

        Box unc = new Box(frm1, 0, new double[] {0.0, 0.0}, new double[] {1.0e-6, 1.0e-6}, null);
        double[] e = {1.2218, 1.3963};
        cir1 = edgeCircle(frm1, p1, e, unc);
        rad = cir1.distance(p1, e);
        checkProbe(cir1, frm1, p1, e, rad, "Errors 1b-4b");
        checkProbe(cir1, frm1, p1, new double[] {0.0, 0.0}, rad, "Errors 5b-8b");
        Circle cir2c = (Circle) cir1.copy();
        cir2c.setSystem("GALACTIC");
        checkDump(cir2c, "checkdump cir2");
        Region cir2 = (Region) cir2c.simplify();
        assertFalse(hasFrameSet(cir2), "Circle: error 9b");
        assertEquals(5, cir1.overlap(cir2), "Circle: Error 10");
        assertEquals(5, cir2.overlap(cir1), "Circle: Error 11");

        cir2 = edgeCircle(frm1, new double[] {1.2217305, 1.3964}, e, unc);
        assertEquals(4, cir1.overlap(cir2), "Circle: Error 12");
        assertEquals(4, cir2.overlap(cir1), "Circle: Error 13");
        cir2 = edgeCircle(frm1, p1, new double[] {1.221731, 1.396268}, unc);
        assertEquals(3, cir1.overlap(cir2), "Circle: Error 14");
        assertEquals(2, cir2.overlap(cir1), "Circle: Error 15");
        cir2 = edgeCircle(frm1, new double[] {0.8, 1.0}, new double[] {0.88, 1.05}, unc);
        assertEquals(1, cir1.overlap(cir2), "Circle: Error 16");
        cir2 = new Circle(frm1, new double[] {0.8, 1.5707963}, 0.1, unc);
        b = cir2.getRegionBounds();
        assertEquals(0.0, b[0][0], "Circle: Error 16a");
        assertEquals(1.47079625, b[0][1], 1e-6, "Circle: Error 16b");
        assertEquals(6.28318531, b[1][0], 1e-6, "Circle: Error 16c");
        assertEquals(1.57079633, b[1][1], 1e-6, "Circle: Error 16d");

        Frame aa = frame2();
        aa.setDomain("aa");
        Box uncAa = new Box(aa, 0, new double[] {0.0, 0.0}, new double[] {1.0e-6, 1.0e-6}, null);
        cir2 = edgeCircle(aa, p1, e, uncAa);
        assertEquals(0, cir1.overlap(cir2), "Circle: Error 17");
        assertEquals(0, cir2.overlap(cir1), "Circle: Error 18");
        CmpFrame f3 = new CmpFrame(sky("ICRS"), frame2());
        cir2 = edgeCircle(f3, new double[] {1.0, 1.0, 3.0, 3.0}, new double[] {1.01, 1.02, 3.01, 3.01}, null);
        assertEquals(5, cir2.overlap(cir2), "Circle: Error 18b");

        // 3-D spheres.
        Frame f = new Frame(3);
        Box unc3 = new Box(f, 0, new double[3], new double[] {1.0e-6, 2.0e-6, 2.0e-6}, null);
        double[] c = {1.0, 2.0, 3.0};
        double[] edge = {0.0, -1.0, -2.0};
        Circle sph1 = edgeCircle(f, c, edge, unc3);
        checkDump(sph1, "checkdump sph1");
        rad = sph1.distance(c, edge);
        checkProbe(sph1, f, c, edge, rad, "Sphere: Errors 1-4z");
        checkProbe(sph1, f, c, new double[3], rad, "Sphere: Errors 5-8z");
        assertEquals(5, sph1.overlap(sph1), "Sphere: Error 10");
        Circle sph2 = edgeCircle(f, c, new double[] {0.5, 0.0, -1.0}, unc3);
        checkDump(sph2, "checkdump sph2");
        assertEquals(2, sph2.overlap(sph1), "Sphere: Error 11");
        assertEquals(3, sph1.overlap(sph2), "Sphere: Error 12");
        sph2 = edgeCircle(f, new double[] {1.0, 0.0, 3.0}, edge, unc3);
        assertEquals(4, sph1.overlap(sph2), "Sphere: Error 13");
        sph2 = edgeCircle(f, new double[] {1.0, 102.0, 3.0}, new double[] {0.0, 99.0, -2.0}, unc3);
        assertEquals(1, sph1.overlap(sph2), "Sphere: Error 14");
        sph1 = edgeCircle(f, new double[3], new double[] {0.0, 0.0, 1.0}, unc3);
        sph2 = edgeCircle(f, new double[] {2.0000001, 0.0, 0.0}, new double[] {2.000001, 1.0, 0.0}, unc3);
        assertEquals(4, sph1.overlap(sph2), "Sphere: Error 15");
        sph2 = edgeCircle(f, new double[] {2.000001, 0.0, 0.0}, new double[] {2.000001, 1.0, 0.0}, unc3);
        assertEquals(4, sph1.overlap(sph2), "Sphere: Error 16");
        sph1.setClosed(false);
        sph2.setClosed(false);
        assertEquals(1, sph1.overlap(sph2), "Sphere: Error 17");
        sph1.clearClosed();
        sph2 = edgeCircle(f, new double[] {2.000004, 0.0, 0.0}, new double[] {2.000004, 1.0, 0.0}, unc3);
        assertEquals(1, sph1.overlap(sph2), "Sphere: Error 18");
    }
    /** Checks each point of {@code pts} ({@code [point][axis]}) passes if {@code inside} is 1, and is bad otherwise. */
    private static void expectPoints(Mapping m, double[][] pts, int[] inside, String label) {
        int n = pts.length;
        int nc = pts[0].length;
        double[][] in = new double[nc][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < nc; j++) {
                in[j][i] = pts[i][j];
            }
        }
        double[][] out = m.transform(in, n, true);
        for (int i = 0; i < inside.length; i++) {
            for (int j = 0; j < nc; j++) {
                double want = inside[i] == 1 ? pts[i][j] : BAD;
                assertEquals(want, out[j][i], label + ": point " + (i + 1) + (inside[i] == 1 ? " kept" : " BAD"));
            }
        }
    }

    private static Polygon skyPolygon(double[][] v) {
        double[] xs = new double[v.length];
        double[] ys = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            xs[i] = v[i][0];
            ys[i] = v[i][1];
        }
        Circle unc = new Circle(new SkyFrame(), new double[] {0.0, 0.5 * Math.PI}, 0.01, null);
        return new Polygon(new SkyFrame(), v.length, xs, ys, unc);
    }

    /**
     * {@code checkPolygon}: an open sky Polygon, then closed, then negated and open, against the
     * same five points; whether it needs a FrameSet before and after a change of system and after
     * simplification; and a Polygon round the pole (containing it), its bounds, and a Prism made
     * from it. C's check of the bounding disc ({@code astGetRegionDisc}, not in jniast) is left
     * out.
     */
    @Test
    void checkPolygon() {
        double pi = Math.PI;
        Polygon pol1 = skyPolygon(new double[][] {
            {0.0, 0.0}, {1.0, 0.5 * pi}, {0.5 * pi, 0.25 * pi}, {0.25 * pi, 0.0}, {0.25 * pi, 0.25 * pi}
        });
        pol1.setClosed(false);
        checkDump(pol1, "checkdump pol1");
        double[][] p = {
            {0.0, 0.0}, {1.0, 0.5 * pi + 0.1}, {0.5 * pi - 0.1, 0.25 * pi}, {0.0, 0.1}, {0.25 * pi, 0.25 * pi + 0.1}
        };
        expectPoints(pol1, p, new int[] {0, 0, 1, 0, 1}, "open: Poly 1-5");
        pol1.setClosed(true);
        expectPoints(pol1, p, new int[] {1, 0, 1, 1, 1}, "closed: Poly 6-10");
        pol1.setNegated(true);
        expectPoints(pol1, p, new int[] {1, 1, 0, 1, 0}, "negated: Poly 11-15");
        pol1.setClosed(false);
        expectPoints(pol1, p, new int[] {0, 1, 0, 0, 0}, "negated open: Poly 16-20");

        assertFalse(hasFrameSet(pol1), "pol1 has FrameSet");
        pol1.setSystem("FK5");
        checkDump(pol1, "checkdump pol2");
        assertTrue(hasFrameSet(pol1), "pol1 does not have FrameSet");
        pol1.setMeshSize(30);
        assertFalse(hasFrameSet(pol1.simplify()), "pol2 has FrameSet");

        // Round the pole, which the Polygon contains.
        Polygon pole = skyPolygon(
                new double[][] {{1.5 * pi, 0.4 * pi}, {pi, 0.4 * pi}, {0.5 * pi, 0.4 * pi}, {0.0, 0.4 * pi}});
        assertArrayEquals(new double[] {0.0, 0.5 * pi}, tranPoint(pole, 0.0, 0.5 * pi), "Poly 21-22");
        checkPoleBounds(pole.getRegionBounds(), 23);

        SpecFrame f2 = new SpecFrame();
        f2.setUnit(1, "Angstrom");
        Prism r3 = new Prism(pole, interval1(f2, 5000.0, 6000.0));
        double[][] b = ((Region) r3.simplify()).getRegionBounds();
        checkPoleBounds(b, 27);
        assertEquals(5000.0, b[0][2], 1e-10, "Poly 31");
        assertEquals(6000.0, b[1][2], 1e-6, "Poly 32");
    }

    /** The bounds of a Polygon round the north pole, C's checks numbered from {@code first}. */
    private static void checkPoleBounds(double[][] b, int first) {
        assertTrue(Math.abs(b[0][0]) <= 1e-10, "Poly " + first);
        assertEquals(1.25663708, b[0][1], 1e-6, "Poly " + (first + 1));
        assertEquals(6.28318531, b[1][0], 1e-6, "Poly " + (first + 2));
        assertEquals(1.57079633, b[1][1], 1e-6, "Poly " + (first + 3));
    }

    /** Sets Closed and Negated on {@code reg} through C's four states and checks each pattern. */
    private static void fourStates(Interval reg, double[][] pts, int[][] patterns, String label, boolean startNegated) {
        boolean[][] states = {{true, false}, {true, true}, {false, false}, {false, true}};
        for (int k = 0; k < 4; k++) {
            reg.setClosed(states[k][0]);
            reg.setNegated(states[k][1] != startNegated);
            expectPoints(reg, pts, patterns[k], label + " " + (1 + 5 * k));
        }
    }

    /** Negates {@code a} or {@code b} as each step says ('a', 'b' or '-') and checks their overlap. */
    private static void overlapSequence(Interval a, Interval b, char[] which, int[] want, int first) {
        for (int k = 0; k < which.length; k++) {
            if (which[k] == 'a') {
                a.negate();
            } else if (which[k] == 'b') {
                b.negate();
            }
            assertEquals(want[k], a.overlap(b), "Interval overlap " + (first + k));
        }
    }

    /** The Region {@code reg} mapped by {@code pm} into {@code f}, checked to be an Interval with no FrameSet. */
    private static Region mappedInterval(Region reg, PermMap pm, Frame f, int first) {
        Region r = reg.mapRegion(pm, f);
        assertInstanceOf(Interval.class, r, "Int: perm check " + first);
        assertFalse(hasFrameSet(r), "Int: perm check " + (first + 1));
        assertEquals(f.getNaxes(), r.getNaxes(), "Int: perm check " + (first + 2));
        return r;
    }

    private static double[] filled(int n, double v) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    /**
     * {@code checkInterval}: InternalUnit of a SkyFrame, a SpecFrame and their CmpFrame; 3-D
     * Intervals with bad limits (one-sided, reversed so that they exclude a range, and with a
     * single finite axis), each closed, negated and open; overlaps of half-unbounded Intervals; and
     * mapping Intervals through PermMaps that add or drop axes, giving Intervals or NullRegions.
     */
    @Test
    void checkInterval() {
        SkyFrame frm1 = new SkyFrame();
        SpecFrame frm2 = new SpecFrame();
        frm2.setUnit(1, "Angstrom");
        CmpFrame frm3 = new CmpFrame(frm1, frm2);
        assertEquals("rad", frm1.getInternalUnit(1), "InternalUnit 1");
        assertEquals("rad", frm1.getInternalUnit(2), "InternalUnit 2");
        assertEquals("Angstrom", frm2.getInternalUnit(1), "InternalUnit 3");
        assertEquals("rad", frm3.getInternalUnit(1), "InternalUnit 4");
        assertEquals("rad", frm3.getInternalUnit(2), "InternalUnit 5");
        assertEquals("Angstrom", frm3.getInternalUnit(3), "InternalUnit 6");

        double[][] p1 = {
            {0.0, 0.0, 5000.0}, {2.0, -1.0, 6000.0}, {-2.0, 1.0, 5999.0}, {2.0, -2.0, 6010.0}, {1.0, -1.0, 4910.0}
        };
        int[][] pats = {{1, 1, 1, 0, 0}, {1, 1, 0, 1, 1}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 1}};
        Interval int1 = new Interval(frm3, new double[] {BAD, BAD, 5000.0}, new double[] {BAD, BAD, 6000.0}, null);
        checkDump(int1, "checkdump int1");
        expectPoints(int1, p1, pats[0], "Interval 1");
        assertTrue(int1.pointInRegion(p1[0]), "Interval 5d");
        assertFalse(int1.pointInRegion(p1[4]), "Interval 5e");
        fourStates(int1, p1, pats, "Interval", false);

        // Reversed limits: the Interval excludes 5000 to 6000, so it is negated first to give the
        // same patterns.
        Interval int2 = new Interval(frm3, new double[] {BAD, BAD, 6000.0}, new double[] {BAD, BAD, 5000.0}, null);
        checkDump(int2, "checkdump int2");
        fourStates(int2, p1, pats, "Interval B", true);

        double[][] p3 = {
            {0.0, 0.0, 5000.0}, {0.5, -1.0, 6000.0}, {-2.0, 0.4, 5999.0}, {2.0, -2.0, 6010.0}, {0.0, -3.0, 4910.0}
        };
        Interval int3 = new Interval(frm3, new double[] {BAD, BAD, 5000.0}, new double[] {0.5, BAD, BAD}, null);
        checkDump(int3, "checkdump int3");
        fourStates(int3, p3, pats, "Interval C", false);

        // Overlaps of half-unbounded Intervals.
        int3.setClosed(true);
        int3.setNegated(false);
        Interval int4 = new Interval(frm3, new double[] {BAD, 0.0, BAD}, new double[] {BAD, -1.0, 6000.0}, null);
        overlapSequence(int3, int4, new char[] {'-', 'a', 'b', 'a'}, new int[] {4, 4, 4, 4}, 1);
        int4 = new Interval(frm3, new double[] {0.6, 0.0, BAD}, new double[] {BAD, -1.0, 6000.0}, null);
        overlapSequence(int3, int4, new char[] {'-', 'a', 'b', 'a'}, new int[] {1, 3, 4, 2}, 5);
        int4 = (Interval) int3.copy();
        overlapSequence(int3, int4, new char[] {'-', 'b'}, new int[] {5, 6}, 9);

        // Through PermMaps that add an axis.
        Frame fa = frame2();
        fa.setDomain("A");
        Box unc = new Box(fa, 0, new double[] {0.0, 0.0}, new double[] {0.01, 0.01}, null);
        Interval ia = new Interval(fa, new double[] {-2.0, 0.5}, new double[] {0.0, BAD}, unc);
        Frame fb = new Frame(3);
        fb.setDomain("B");
        PermMap pm0 = new PermMap(2, new int[] {3, 1}, 3, new int[] {2, -1, 1}, filled(6, 0.0));
        Region reg = mappedInterval(ia, pm0, fb, 1);
        expectPoints(
                reg,
                new double[][] {{0.0, 0.0, -0.5}, {20.0, 0.0, -0.5}, {20.0, -10.0, 0.5}, {20.0, 0.0, -2.0}},
                new int[] {0, 1, 0, 1},
                "Int: pc 1-12");
        PermMap pm15 = new PermMap(2, new int[] {3, 1}, 3, new int[] {2, -1, 1}, filled(6, 1.5));
        reg = mappedInterval(ia, pm15, fb, 4);
        double[][] pts = {{20.0, 0.0, -0.5}, {20.0, 1.5, -0.5}, {20.0, 1.6, -0.5}, {0.5, 1.5, 0.0}};
        expectPoints(reg, pts, new int[] {0, 1, 0, 1}, "Int: pc 13-24");
        ia.negate();
        ia.setClosed(false);
        reg = mappedInterval(ia, pm15, fb, 7);
        expectPoints(reg, pts, new int[] {1, 0, 1, 0}, "Int: pc 25-36");

        // Through PermMaps that drop an axis.
        Frame fa3 = new Frame(3);
        fa3.setDomain("A");
        Box unc3 = new Box(fa3, 0, new double[3], filled(3, 0.01), null);
        Interval ib = new Interval(fa3, new double[] {0.5, -1.0, -2.0}, new double[] {BAD, BAD, 0.0}, unc3);
        Frame fb2 = frame2();
        fb2.setDomain("B");
        PermMap pm = new PermMap(3, new int[] {1, -1, 2}, 2, new int[] {1, 3}, filled(6, 1.0));
        reg = mappedInterval(ib, pm, fb2, 10);
        expectPoints(
                reg,
                new double[][] {{0.4, -1.0}, {1.0, 0.1}, {1.0, -2.1}, {0.5, -1.0}, {10.0, -0.1}, {0.55, -2.0}},
                new int[] {0, 0, 0, 1, 1, 1},
                "Int: pc 37-48");
        pm = new PermMap(3, new int[] {1, -1, 2}, 2, new int[] {1, 3}, filled(6, -2.0));
        for (int first : new int[] {13, 17}) {
            boolean negate = first == 17;
            if (negate) {
                ib.negate();
            }
            Region r = ib.mapRegion(pm, fb2);
            NullRegion nr = assertInstanceOf(NullRegion.class, r, "Int: perm check " + first);
            assertFalse(hasFrameSet(nr), "Int: perm check " + (first + 1));
            assertEquals(2, nr.getNaxes(), "Int: perm check " + (first + 2));
            assertEquals(negate, nr.getNegated(), "Int: perm check " + (first + 3));
        }
    }
    /** The cards of the header {@code checkBox} reads its first sky Frame from, with C's blank card. */
    private static final String[] CHECK_BOX_CARDS = {
        "CTYPE1  = 'RA---TAN'",
        "CTYPE2  = 'DEC--TAN'",
        "CRPIX1  = 100",
        "CRPIX2  = 100",
        "CRVAL1  = 71.619724",
        "CRVAL2  = 42.971835",
        "  ",
        "CDELT1  = 0.6",
        "CDELT2  = 0.6"
    };

    private static FrameSet readFitsFrameSet(String[] cards) {
        FitsChan fc = new FitsChan();
        for (String card : cards) {
            fc.putFits(card, false);
        }
        fc.setCard(1);
        return (FrameSet) fc.read();
    }

    /**
     * Checks points ({@code [point][axis]}) transformed by {@code m}: kept unchanged (1), bad on
     * every axis (0), or bad on none (2).
     */
    private static void expectStates(Mapping m, double[][] pts, boolean forward, int[] want, String label) {
        int n = pts.length;
        int nc = pts[0].length;
        double[][] in = new double[nc][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < nc; j++) {
                in[j][i] = pts[i][j];
            }
        }
        double[][] out = m.transform(in, n, forward);
        for (int i = 0; i < want.length; i++) {
            for (int j = 0; j < nc; j++) {
                if (want[i] == 1) {
                    assertEquals(pts[i][j], out[j][i], label + ": point " + (i + 1) + " kept");
                } else if (want[i] == 0) {
                    assertEquals(BAD, out[j][i], label + ": point " + (i + 1) + " BAD");
                } else {
                    assertNotEquals(BAD, out[j][i], label + ": point " + (i + 1) + " not BAD");
                }
            }
        }
    }

    /**
     * {@code checkBox}, first part: a Box on the sky Frame of a FITS header, its transformations
     * through changes of sky system and axis order, with Adaptive off, and its formatting;
     * membership of nine points closed, negated and open; the Box added to a FrameSet as a Frame,
     * clipping its Mappings, and unchanged by the FrameSet's Negated; and the Box as one component
     * of a permuted CmpFrame, which transforms points as C's does.
     */
    @Test
    void checkBoxSky() {
        FrameSet fs = readFitsFrameSet(CHECK_BOX_CARDS);
        Frame frm1 = fs.getFrame(FrameSet.AST__CURRENT);
        frm1.setDigits(1, 12);
        Box box1 = new Box(frm1, 0, new double[] {1.25, 0.75}, new double[] {1.5, 0.5}, null);
        checkDump(box1, "checkdump box1");
        assertEquals("ICRS", box1.getSystem(), "box1 system is not ICRS");
        box1.setSystem("GALACTIC");
        box1.permAxes(new int[] {2, 1});
        Box box3 = (Box) box1.copy();

        // C's inputs, rounded to single precision as C's casts round them, in (b, l) order.
        double[][] pts = {
            {(float) -0.0269096590283195, (float) 2.82175432250852}, {(float) 0.2467384819891, (float) 2.70798275154741}
        };
        checkGalactic(box1, pts, 1.0e-7, 1.0e-6, "3", "4");
        assertTrue(box3.getAdaptive(), "error 4a");
        box3.setAdaptive(false);
        box3.setSystem("ICRS");
        checkGalactic(box3, pts, 1.0e-8, 1.0e-8, "4b", "4c");
        box3.clear("System");
        checkGalactic(box3, pts, 1.0e-8, 1.0e-8, "4d", "4e");
        box1.simplify();
        box1.setSystem("ICRS");
        box1.permAxes(new int[] {2, 1});

        String t2 = box1.format(1, 0.25);
        assertEquals(frm1.format(1, 0.25), t2, "ast_format is different for frm1 and box1");
        assertEquals(0.25, box1.unformat(1, t2), 1.0e-10, "ast_unformat failed for box1");
        assertEquals("ICRS", box1.getSystem(), "Box1(b) system is not ICRS");
        assertEquals(2000.0, ((SkyFrame) box1.getRegionFrame()).getEquinox(), "Box1 equinox is not 2000.0");
        assertTrue(box1.getClosed(), "Box1 closed is not 1");

        double[][] p = {
            {1.25, 0.75},
            {1.0, 1.0},
            {1.0, 0.5},
            {1.5, 0.5},
            {1.5, 1.0},
            {1.0, 1.2},
            {0.8, 0.5},
            {1.5, 0.45},
            {1.501, 1.0}
        };
        int[] inside = {1, 1, 1, 1, 1, 0, 0, 0, 0};
        expectStates(box1, p, true, inside, "error A");
        expectStates(box1, p, false, inside, "error B");
        box1.negate();
        expectStates(box1, p, true, new int[] {0, 1, 1, 1, 1, 1, 1, 1, 1}, "error C");
        box1.setClosed(false);
        box1.negate();
        expectStates(box1, p, true, new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0}, "error D");
        box1.setNegated(true);
        expectStates(box1, p, true, new int[] {0, 0, 0, 0, 0, 1, 1, 1, 1}, "error E");
        box1.clear("Negated");
        box1.clear("Closed");

        fs.addFrame(FrameSet.AST__CURRENT, new UnitMap(2), box1);
        assertInstanceOf(
                Region.class, fs.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__CURRENT), "map1 is not a Region");
        fs.set("Negated=1");
        assertFalse(box1.getNegated(), "FrameSet Negated attribute reflected in box1");
        fs.clear("Negated");
        Mapping map1 = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        expectStates(map1, p, false, new int[] {2, 2, 2, 2, 2, 0, 0, 0, 0}, "error F");
        double[][] in = new double[2][9];
        for (int i = 0; i < 9; i++) {
            in[0][i] = p[i][0];
            in[1][i] = p[i][1];
        }
        double[][] back = map1.transform(map1.transform(in, 9, false), 9, true);
        assertEquals(1.25, back[0][0], 1.0e-7, "error G1");
        assertEquals(0.75, back[1][0], 1.0e-7, "error G2");
        for (int i = 5; i < 9; i++) {
            assertEquals(BAD, back[0][i], "error G" + (2 * i + 1));
            assertEquals(BAD, back[1][i], "error G" + (2 * i + 2));
        }

        SpecFrame frm2 = new SpecFrame();
        frm2.setUnit(1, "Angstrom");
        Box box2 = new Box(frm2, 0, new double[] {1000.0}, new double[] {1100.0}, null);
        CmpFrame frm3 = new CmpFrame(box1, box2);
        frm3.permAxes(new int[] {2, 3, 1});
        frm3.set("system(1)=galactic");
        frm3.set("system(2)=Freq");
        double b1 = -0.0269096590283195;
        double l1 = 2.82175432250852;
        double b2 = 0.2467384819891;
        double l2 = 2.70798275154741;
        double[][] cin = {{b1, b2, b1, b2}, {2997924.58, 2997924.58, 4000000.0, 4000000.0}, {l1, l2, l1, l2}};
        double[][] o = frm3.transform(cin, 4, true);
        assertEquals(-0.0269096587, o[0][0], 1.0e-8, "error H1");
        assertEquals(2997924.5, o[1][0], 1.0e-1, "error H2");
        assertEquals(2.82175422, o[2][0], 1.0e-6, "error H3");
        assertEquals(BAD, o[0][1], "error H4");
        assertEquals(2997924.5, o[1][1], 1.0e-1, "error H5");
        assertEquals(BAD, o[2][1], "error H6");
        assertEquals(-0.0269096587, o[0][2], 1.0e-8, "error H7");
        assertEquals(BAD, o[1][2], "error H8");
        assertEquals(2.82175422, o[2][2], 1.0e-6, "error H9");
        assertEquals(BAD, o[0][3], "error H10");
        assertEquals(BAD, o[1][3], "error H11");
        assertEquals(BAD, o[2][3], "error H12");
        assertEquals(1, frm3.getI("closed(1)"), "compound frame region is not closed");
    }

    /** C's checks of a Box in Galactic (b, l) order: the first point kept, within tolerances, and the second bad. */
    private static void checkGalactic(Mapping m, double[][] pts, double xtol, double ytol, String e3, String e4) {
        double[][] out = tran2(m, pts, true);
        assertEquals(2.82175422, out[0][1], ytol, "error 1");
        assertEquals(-0.0269096587, out[0][0], xtol, "error 2");
        assertEquals(BAD, out[1][1], "error " + e3);
        assertEquals(BAD, out[1][0], "error " + e4);
    }

    private static Frame domainFrame(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
    }

    /**
     * {@code checkBox}, MapRegion part: a Box mapped by a diagonal and a sheared MatrixMap, a 1-D
     * Box by a ZoomMap, and a sky Box by the conversion to an offset sky Frame, each into the new
     * Frame with no FrameSet; the offset Box clips as the original did, across zero longitude.
     */
    @Test
    void checkBoxMapRegion() {
        Box box1 = new Box(
                domainFrame(3, "A"), 0, new double[] {100.0, 200.0, 300.0}, new double[] {0.0, 400.0, 250.0}, null);
        Frame frm2 = domainFrame(3, "B");
        double[][][] matrices = {
            {{2.0, 0.0, 0.0}, {0.0, 4.0, 0.0}, {0.0, 0.0, 6.0}}, {{2.0, 0.1, 0.0}, {0.0, 4.0, 0.0}, {0.0, 0.0, 6.0}}
        };
        int first = 1;
        for (double[][] matrix : matrices) {
            Region box2 = box1.mapRegion(new MatrixMap(3, 3, matrix), frm2);
            assertEquals("B", box2.getDomain(), "ast_mapregion" + first + ": Box2 domain is not B");
            assertFalse(hasFrameSet(box2), "ast_mapregion" + (first + 1) + ": Box2 has FrameSet");
            if (first == 3) {
                checkDump(box2, "checkdump box2");
            }
            first += 2;
        }

        Box box1d = new Box(domainFrame(1, "A"), 0, new double[] {100.0}, new double[] {0.0}, null);
        Region box2 = box1d.mapRegion(new ZoomMap(1, 2.0), domainFrame(1, "B"));
        assertEquals("B", box2.getDomain(), "ast_mapregion5: Box2 domain is not B");
        assertFalse(hasFrameSet(box2), "ast_mapregion6: Box2 has FrameSet (B)");

        SkyFrame sf1 = new SkyFrame();
        Box sbox = new Box(sf1, 0, new double[] {0.0, 0.0}, new double[] {0.001, 0.001}, null);
        SkyFrame sf2 = (SkyFrame) sf1.copy();
        sf2.setSkyRef(1, 0.0005);
        sf2.setSkyRefIs("Origin");
        FrameSet fs = sf1.convert(sf2, "");
        Region sbox2 = sbox.mapRegion(fs, sf2);
        assertFalse(hasFrameSet(sbox2), "ast_mapregion7: Box2 has FrameSet (C)");
        double[][] p = {
            {0.00049, 0.0009}, {0.00051, 0.0009}, {-0.0016, 0.0}, {-0.0014, 0.0}, {6.2815853, 0.0}, {6.2817853, 0.0}
        };
        double[][] out = tran2(sbox2, p, true);
        int[][] kept = {{0, 1}, {3, 9}, {5, 11}};
        for (int[] k : kept) {
            for (int ax = 0; ax < 2; ax++) {
                assertEquals(p[k[0]][ax], out[k[0]][ax], 1.0e-10, "error I" + (k[1] + ax));
            }
        }
        int[][] bad = {{1, 3}, {4, 5}, {2, 7}};
        for (int[] k : bad) {
            for (int ax = 0; ax < 2; ax++) {
                assertEquals(BAD, out[k[0]][ax], "error I" + (k[1] + ax));
            }
        }
        sbox2.set("SkyRefIs=Pole");
        assertFalse(hasFrameSet(sbox2.simplify()), "ast_mapregion8: Box2 has FrameSet (B)");
    }

    /**
     * {@code checkBox}, Overlap part: Boxes in different Domains cannot be compared; each Box is the
     * same as itself, open or closed; and Boxes that enclose, miss and cross each other.
     */
    @Test
    void checkBoxOverlap() {
        double[] p1 = {100.0, 200.0, 300.0};
        double[] p2 = {0.0, 400.0, 250.0};
        Box box1 = new Box(domainFrame(3, "A"), 0, p1, p2, null);
        box1.setClosed(true);
        Frame frm2 = domainFrame(3, "B");
        Box box2 = new Box(frm2, 0, p1, p2, null);
        box2.setClosed(false);
        assertEquals(0, box1.overlap(box2), "ast_overlap A: result should be zero");
        assertEquals(5, box1.overlap(box1), "ast_overlap B: result should be 5");
        assertEquals(5, box2.overlap(box2), "ast_overlap C: result should be 5");
        frm2.setDomain("A");
        Object[][] cases = {
            {new double[] {100.0, 200.0, 300.0}, new double[] {-100.0, 600.0, 400.0}, 2, 3, "D", "E"},
            {new double[] {300.0, 200.0, 300.0}, new double[] {201.0, 400.0, 250.0}, 1, 1, "F", "G"},
            {new double[] {150.0, 200.0, 300.0}, new double[] {50.0, 400.0, 250.0}, 4, 4, "H", "I"}
        };
        for (Object[] c : cases) {
            Box b2 = new Box(frm2, 0, (double[]) c[0], (double[]) c[1], null);
            assertEquals((int) (Integer) c[2], box1.overlap(b2), "ast_overlap " + c[4] + ": result should be " + c[2]);
            assertEquals((int) (Integer) c[3], b2.overlap(box1), "ast_overlap " + c[5] + ": result should be " + c[3]);
        }
    }

    /**
     * {@code checkBox}, pixel mask part: masking outside a Box on a 5x5 grid, and outside a negated
     * Box on the sky through a FITS FrameSet, inverted so that it maps the sky onto the pixel grid.
     * The bad value is the Fortran test's {@code VAL__BADR}, the most negative {@code float}.
     */
    @Test
    void checkBoxMask() {
        float valBadr = -Float.MAX_VALUE;
        Box box1 = new Box(domainFrame(2, "A"), 0, new double[] {1.0, 1.0}, new double[] {3.1, 4.1}, null);
        float[] data = new float[25];
        Arrays.fill(data, 1.0f);
        assertEquals(
                13, box1.maskF(null, false, 2, new int[] {1, 1}, new int[] {5, 5}, data, valBadr), "res should be 13");
        for (int i = 1; i <= 5; i++) {
            for (int j = 1; j <= 5; j++) {
                float v = data[(i - 1) * 5 + (j - 1)];
                if (j <= 3 && i <= 4) {
                    assertEquals(1.0f, v, "pixel (" + j + "," + i + ") should be 1.0");
                } else {
                    assertEquals(valBadr, v, "pixel (" + j + "," + i + ") should be VAL__BADR");
                }
            }
        }

        String[] cards = CHECK_BOX_CARDS.clone();
        cards[2] = "CRPIX1  = 20";
        cards[3] = "CRPIX2  = 20";
        cards[4] = "CRVAL1  = 0.0";
        cards[5] = "CRVAL2  = 0.0";
        cards[6] = "   ";
        cards[7] = "CDELT1  = 1.6";
        cards[8] = "CDELT2  = 1.6";
        FrameSet fs = readFitsFrameSet(cards);
        Box sbox = new Box(fs, 0, new double[] {0.13089969, 0.17453293}, new double[] {-0.13089971, -0.17453293}, null);
        float[] image = new float[50 * 50];
        Arrays.fill(image, 1.0f);
        sbox.negate();
        fs.invert();
        assertEquals(
                522,
                sbox.maskF(fs, false, 2, new int[] {1, 1}, new int[] {50, 50}, image, valBadr),
                "res should be 522");
        assertEquals(valBadr, image[(42 - 1) * 50 + (34 - 1)], "image(34,42) should be VAL__BADR");
        assertEquals(1.0f, image[(42 - 1) * 50 + (33 - 1)], "image(33,42) should be 1.0");
        assertEquals(valBadr, image[(14 - 1) * 50 + (16 - 1)], "image(16,14) should be VAL__BADR");
        assertEquals(1.0f, image[(13 - 1) * 50 + (15 - 1)], "image(15,13) should be 1.0");
    }

    /** {@code reg} mapped by {@code pm} into {@code f}: a NullRegion if {@code isNull}, else a Box, with no FrameSet. */
    private static Region mappedBox(Region reg, PermMap pm, Frame f, int first, boolean isNull) {
        Region r = reg.mapRegion(pm, f);
        Class<? extends Region> cls = isNull ? NullRegion.class : Box.class;
        assertInstanceOf(cls, r, "Box: perm check " + first);
        assertFalse(hasFrameSet(r), "Box: perm check " + (first + 1));
        assertEquals(f.getNaxes(), r.getNaxes(), "Box: perm check " + (first + 2));
        return r;
    }

    /**
     * {@code checkBox}, axis-count part: a Box mapped by PermMaps that add an axis (a constant
     * inside or outside it) or drop one, giving a Box with no FrameSet, or a NullRegion when the
     * dropped axis's constant is outside.
     */
    @Test
    @SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // mappedBox and expectStates assert.
    void checkBoxPerm() {
        Frame frm1 = domainFrame(2, "A");
        Circle unc = new Circle(frm1, new double[] {0.0, 0.0}, 0.01, null);
        Box box1 = new Box(frm1, 0, new double[] {-1.0, 1.0}, new double[] {-2.0, 1.5}, unc);

        Frame frm2 = domainFrame(3, "B");
        PermMap pm = new PermMap(2, new int[] {3, 1}, 3, new int[] {2, -1, 1}, filled(6, 0.0));
        Region reg = mappedBox(box1, pm, frm2, 1, false);
        expectStates(
                reg,
                new double[][] {{0.0, 0.0, 0.0}, {0.7, 0.0, -0.5}, {2.0, 0.0, -1.0}, {1.5, 0.0, 0.0}},
                true,
                new int[] {0, 1, 0, 1},
                "box: pc 1-12");
        pm = new PermMap(2, new int[] {3, 1}, 3, new int[] {2, -1, 1}, filled(6, 1.0));
        reg = mappedBox(box1, pm, frm2, 4, false);
        expectStates(
                reg,
                new double[][] {{0.0, 0.0, 0.0}, {0.7, 1.0, -0.5}, {0.7, 1.1, -0.5}, {0.7, 0.9, -0.5}},
                true,
                new int[] {0, 1, 0, 0},
                "box: pc 11-19");

        frm2 = domainFrame(1, "B");
        double[][] x = {{-2.5}, {-1.9}, {0.0}, {0.5}};
        pm = new PermMap(2, new int[] {1, -1}, 1, new int[] {1}, filled(6, 1.4));
        reg = mappedBox(box1, pm, frm2, 7, false);
        expectStates(reg, x, true, new int[] {0, 1, 1, 0}, "box: pc 20-23");
        pm = new PermMap(2, new int[] {1, -1}, 1, new int[] {1}, filled(6, 1.6));
        reg = mappedBox(box1, pm, frm2, 10, true);
        expectStates(reg, x, true, new int[] {0, 0, 0, 0}, "box: pc 24-27");

        Box box3 = new Box(domainFrame(3, "A"), 1, new double[] {0.5, -1.0, -2.0}, new double[] {30.0, 5.0, 0.0}, null);
        pm = new PermMap(3, new int[] {1, -1, 2}, 2, new int[] {1, 3}, filled(6, 1.0));
        reg = mappedBox(box3, pm, domainFrame(2, "B"), 13, false);
        expectStates(
                reg,
                new double[][] {{0.4, -1.0}, {1.0, 0.1}, {1.0, -2.1}, {0.5, -1.0}, {10.0, -0.1}, {0.55, -2.0}},
                true,
                new int[] {0, 0, 0, 1, 1, 1},
                "Box: pc 37-48");
    }

    /**
     * {@code checkBox}, mesh part: the grid and boundary mesh of a Box in the pixel Frame of a
     * rotated TAN header, their point counts and positions, and the Box mapped onto the sky
     * becoming a Polygon with no FrameSet. C's meshes come from {@code astGetRegionMesh}, which is
     * not in jniast; the protected {@code astRegGrid} and {@code astRegMesh} it calls give them
     * here.
     */
    @Test
    void checkBoxMesh() {
        FrameSet fs = readFitsFrameSet(new String[] {
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'",
            "CRPIX1  = 20",
            "CRPIX2  = 20",
            "CRVAL1  = 0.0",
            "CRVAL2  = 0.0",
            "CROTA1  = 30.0",
            "CDELT1  = -0.00001",
            "CDELT2  = 0.00001"
        });
        Box box1 = new Box(
                fs.getFrame(FrameSet.AST__BASE), 0, new double[] {100.0, 150.0}, new double[] {150.0, 170.0}, null);
        double[][] grid = box1.regGrid();
        assertEquals(176, grid[0].length, "Box: Error mesh 3");
        for (int i = 0; i < grid[0].length; i++) {
            assertTrue(Math.abs(grid[0][i] - 100.0) <= 50.0, "Box: Error mesh 1");
            assertTrue(Math.abs(grid[1][i] - 150.0) <= 20.0, "Box: Error mesh 2");
        }
        double[][] mesh = box1.regMesh();
        assertEquals(198, mesh[0].length, "Box: Error mesh 4");
        for (int i = 0; i < mesh[0].length; i++) {
            double px = mesh[0][i];
            double py = mesh[1][i];
            assertTrue(px == 50.0 || px == 150.0 || py == 130.0 || py == 170.0, "Box: Error mesh 5");
        }
        Region reg1 = box1.mapRegion(
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), fs.getFrame(FrameSet.AST__CURRENT));
        assertFalse(hasFrameSet(reg1), "Box: poly simp 1");
        assertInstanceOf(Polygon.class, reg1, "Box: poly simp 2");
    }
}
