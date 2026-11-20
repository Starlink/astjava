package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_region_masking.rs}, from C's {@code
 * ast_tester/testregionmasking.c} ({@code checkCurvature}): masking with a sky Polygon whose
 * great-circle edges are curved in the pixel grid.
 *
 * <p>The ground truth classifies each pixel centre by transforming it to the sky and asking the
 * Polygon directly; the masks are compared with it. {@link Region#maskI} resamples through the
 * Region, which simplifies it when the grid has more than 1024 pixels, and with the default
 * SimpVertices that rebuilds the Polygon from its vertices in the grid, with straight edges. For
 * CAR the straight edges miss the curved boundary; with SimpVertices false the simplification
 * keeps the curved Polygon. For TAN, great circles are straight lines, so both masks are exact.
 * The counts are those C's test program prints, built from the pinned C library.
 *
 * <p>Each pixel is classified through {@code sin}, {@code cos}, {@code atan2} and the like, which
 * may differ in the last bit between {@code java.lang.Math} and the C library, so that a sky
 * position may move by some {@code 1e-15} radians and a grid position by some {@code 1e-12}
 * pixels. {@link #pixelsAreClearOfEveryBoundary} shows that no decision lies near its threshold:
 * every pixel centre is more than {@code 1e-9} radians on the sky, and {@code 1e-6} pixels in the
 * grid, from the distance from each edge within which the edge counts as containing it, and from
 * the line from the Polygon's point inside through each vertex, where the count of edges crossed
 * changes (the least are {@code 2.5e-7} radians and {@code 1.1e-3} pixels). The bounds of the
 * mapped Polygon, which the pixel box is taken from, are more than {@code 1e-6} from a whole
 * number.
 */
class RegionMaskingTest {

    private static final String CAR_WCS = String.join(
            "\n",
            " Begin FrameSet",
            " Nframe = 2",
            " Base = 1",
            " Currnt = 2", // codespell:ignore currnt
            " Lnk2 = 1",
            " Frm1 =",
            " Begin Frame",
            " Title = \"Pixel Coordinates\"",
            " Naxes = 2",
            " Domain = \"GRID\"",
            " Ax1 =",
            " Begin Axis",
            " Label = \"Pixel axis 1\"",
            " End Axis",
            " Ax2 =",
            " Begin Axis",
            " Label = \"Pixel axis 2\"",
            " End Axis",
            " End Frame",
            " Frm2 =",
            " Begin SkyFrame",
            " Ident = \" \"",
            " IsA Object",
            " Naxes = 2",
            " System = \"ICRS\"",
            " Ax1 =",
            " Begin SkyAxis",
            " End SkyAxis",
            " Ax2 =",
            " Begin SkyAxis",
            " End SkyAxis",
            " IsA Frame",
            " Proj = \"Cartesian\"",
            " SRefIs = \"Ignored\"",
            " SRef1 = 3.1415926535897931",
            " SRef2 = 1.0471975511965976",
            " End SkyFrame",
            " Map2 =",
            " Begin CmpMap",
            " Nin = 2",
            " IsSimp = 1",
            " IsA Mapping",
            " MapA =",
            " Begin WinMap",
            " Nin = 2",
            " IsA Mapping",
            " Sft1 = 0.6108652381980153",
            " Scl1 = -0.0017453292519943296",
            " Sft2 = -0.43633231299858238",
            " Scl2 = 0.0017453292519943296",
            " End WinMap",
            " MapB =",
            " Begin CmpMap",
            " Nin = 2",
            " IsA Mapping",
            " InvA = 1",
            " MapA =",
            " Begin WcsMap",
            " Nin = 2",
            " Invert = 1",
            " IsA Mapping",
            " Type = \"CAR\"",
            " End WcsMap",
            " MapB =",
            " Begin CmpMap",
            " Nin = 2",
            " IsA Mapping",
            " InvA = 1",
            " MapA =",
            " Begin SphMap",
            " Nin = 3",
            " Nout = 2",
            " Invert = 1",
            " IsA Mapping",
            " UntRd = 1",
            " PlrLg = 0",
            " End SphMap",
            " MapB =",
            " Begin CmpMap",
            " Nin = 3",
            " Nout = 2",
            " IsA Mapping",
            " MapA =",
            " Begin MatrixMap",
            " Nin = 3",
            " IsA Mapping",
            " M0 = -0.50000000000000011",
            " M1 = 0",
            " M2 = 0.8660254037844386",
            " M3 = 0",
            " M4 = -1",
            " M5 = 0",
            " M6 = 0.8660254037844386",
            " M7 = 0",
            " M8 = 0.50000000000000011",
            " IM0 = -0.50000000000000011",
            " IM1 = 0",
            " IM2 = 0.8660254037844386",
            " IM3 = 0",
            " IM4 = -1",
            " IM5 = 0",
            " IM6 = 0.86602540378443849",
            " IM7 = 0",
            " IM8 = 0.50000000000000011",
            " Form = \"Full\"",
            " End MatrixMap",
            " MapB =",
            " Begin SphMap",
            " Nin = 3",
            " Nout = 2",
            " IsA Mapping",
            " UntRd = 1",
            " PlrLg = 3.1415926535897931",
            " End SphMap",
            " End CmpMap",
            " End CmpMap",
            " End CmpMap",
            " End CmpMap",
            " End FrameSet",
            "");

    private static final String TAN_WCS = String.join(
            "\n",
            " Begin FrameSet",
            " Nframe = 2",
            " Base = 1",
            " Currnt = 2", // codespell:ignore currnt
            " Lnk2 = 1",
            " Frm1 =",
            " Begin Frame",
            " Title = \"Pixel Coordinates\"",
            " Naxes = 2",
            " Domain = \"GRID\"",
            " Ax1 =",
            " Begin Axis",
            " Label = \"Pixel axis 1\"",
            " End Axis",
            " Ax2 =",
            " Begin Axis",
            " Label = \"Pixel axis 2\"",
            " End Axis",
            " End Frame",
            " Frm2 =",
            " Begin SkyFrame",
            " Ident = \" \"",
            " IsA Object",
            " Naxes = 2",
            " System = \"ICRS\"",
            " Ax1 =",
            " Begin SkyAxis",
            " End SkyAxis",
            " Ax2 =",
            " Begin SkyAxis",
            " End SkyAxis",
            " IsA Frame",
            " Proj = \"gnomonic\"",
            " SRefIs = \"Ignored\"",
            " SRef1 = 3.1415926535897931",
            " SRef2 = 1.0471975511965976",
            " End SkyFrame",
            " Map2 =",
            " Begin CmpMap",
            " Nin = 2",
            " IsSimp = 1",
            " IsA Mapping",
            " MapA =",
            " Begin WinMap",
            " Nin = 2",
            " IsA Mapping",
            " Sft1 = 0.6108652381980153",
            " Scl1 = -0.0017453292519943296",
            " Sft2 = -0.43633231299858238",
            " Scl2 = 0.0017453292519943296",
            " End WinMap",
            " MapB =",
            " Begin CmpMap",
            " Nin = 2",
            " IsA Mapping",
            " InvA = 1",
            " MapA =",
            " Begin WcsMap",
            " Nin = 2",
            " Invert = 1",
            " IsA Mapping",
            " Type = \"TAN\"",
            " End WcsMap",
            " MapB =",
            " Begin CmpMap",
            " Nin = 2",
            " IsA Mapping",
            " InvA = 1",
            " MapA =",
            " Begin SphMap",
            " Nin = 3",
            " Nout = 2",
            " Invert = 1",
            " IsA Mapping",
            " UntRd = 1",
            " PlrLg = 0",
            " End SphMap",
            " MapB =",
            " Begin CmpMap",
            " Nin = 3",
            " Nout = 2",
            " IsA Mapping",
            " MapA =",
            " Begin MatrixMap",
            " Nin = 3",
            " IsA Mapping",
            " M0 = -0.8660254037844386",
            " M1 = 0",
            " M2 = -0.5",
            " M3 = 0",
            " M4 = -1",
            " M5 = 0",
            " M6 = -0.5",
            " M7 = 0",
            " M8 = 0.8660254037844386",
            " IM0 = -0.8660254037844386",
            " IM1 = 0",
            " IM2 = -0.5",
            " IM3 = 0",
            " IM4 = -1",
            " IM5 = 0",
            " IM6 = -0.5",
            " IM7 = 0",
            " IM8 = 0.8660254037844386",
            " Form = \"Full\"",
            " End MatrixMap",
            " MapB =",
            " Begin SphMap",
            " Nin = 3",
            " Nout = 2",
            " IsA Mapping",
            " UntRd = 1",
            " PlrLg = 3.1415926535897931",
            " End SphMap",
            " End CmpMap",
            " End CmpMap",
            " End CmpMap",
            " End CmpMap",
            " End FrameSet",
            "");

    private static final double D2R = Math.PI / 180.0;

    private static final double[] LONS = {160.0 * D2R, 200.0 * D2R, 200.0 * D2R, 160.0 * D2R};

    private static final double[] LATS = {50.0 * D2R, 50.0 * D2R, 70.0 * D2R, 70.0 * D2R};

    /** The Polygon, the Mappings and the pixel box of one projection, as checkCurvature makes them. */
    private static final class Setup {
        final Frame grid;
        final Mapping sky2grid;
        final Mapping grid2sky;
        final Polygon poly;
        final Polygon polc;
        final double[][] bounds;
        final int[] lbnd;
        final int[] ubnd;
        final int npix;
        final double[][] centres;

        Setup(String wcs) {
            FrameSet fs = (FrameSet) ChannelText.read(wcs);
            SkyFrame sky = (SkyFrame) fs.getFrame(FrameSet.AST__CURRENT);
            grid = fs.getFrame(FrameSet.AST__BASE);
            sky2grid = fs.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
            grid2sky = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Polygon p = new Polygon(sky, 4, LONS, LATS, null);
            double[][] centre = p.transform(new double[][] {{180.0 * D2R}, {60.0 * D2R}}, 1, true);
            if (centre[0][0] == AstObject.AST__BAD) {
                p.negate();
            }
            poly = p;
            polc = (Polygon) poly.copy();
            polc.set("SimpVertices=0");
            bounds = polc.mapRegion(sky2grid, grid).getRegionBounds();
            lbnd = new int[] {(int) Math.floor(bounds[0][0]) - 3, (int) Math.floor(bounds[0][1]) - 3};
            ubnd = new int[] {(int) Math.ceil(bounds[1][0]) + 3, (int) Math.ceil(bounds[1][1]) + 3};
            int nx = ubnd[0] - lbnd[0] + 1;
            int ny = ubnd[1] - lbnd[1] + 1;
            npix = nx * ny;
            centres = new double[2][npix];
            int k = 0;
            for (int iy = lbnd[1]; iy <= ubnd[1]; iy++) {
                for (int ix = lbnd[0]; ix <= ubnd[0]; ix++) {
                    centres[0][k] = ix;
                    centres[1][k] = iy;
                    k++;
                }
            }
        }

        int[] mask(Polygon region) {
            int[] data = new int[npix];
            region.maskI(sky2grid, true, 2, lbnd, ubnd, data, 1);
            return data;
        }
    }

    private static void checkCurvature(String wcs, int inside, int defaultWrong, int sv0Wrong) {
        Setup s = new Setup(wcs);
        double[][] onSky = s.grid2sky.transform(s.centres, s.npix, true);
        double[][] tested = s.poly.transform(onSky, s.npix, true);
        boolean[] truth = new boolean[s.npix];
        int nin = 0;
        for (int i = 0; i < s.npix; i++) {
            truth[i] = tested[0][i] != AstObject.AST__BAD;
            if (truth[i]) {
                nin++;
            }
        }
        int[] mdef = s.mask(s.poly);
        int[] msv0 = s.mask(s.polc);
        int wrongDef = 0;
        int wrongSv0 = 0;
        for (int i = 0; i < s.npix; i++) {
            if ((mdef[i] != 0) != truth[i]) {
                wrongDef++;
            }
            if ((msv0[i] != 0) != truth[i]) {
                wrongSv0++;
            }
        }
        assertEquals(inside, nin, "pixels inside");
        assertEquals(defaultWrong, wrongDef, "default mask");
        assertEquals(sv0Wrong, wrongSv0, "SimpVertices=0 mask");
    }

    /** CAR: the default mask linearises the curved edges; the SimpVertices-false mask matches the ground truth. */
    @Test
    void carDefaultMaskLinearisesCurvedEdges() {
        checkCurvature(CAR_WCS, 38036, 467, 0);
    }

    /** TAN: great circles are straight in the grid, so both masks are exact. */
    @Test
    void tanMasksMatchTheGroundTruth() {
        checkCurvature(TAN_WCS, 39080, 0, 0);
    }

    // ---- Clearance of the decisions from their thresholds ----

    private static double[] unit(double lon, double lat) {
        return new double[] {Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalise(double[] a) {
        double n = Math.sqrt(dot(a, a));
        return new double[] {a[0] / n, a[1] / n, a[2] / n};
    }

    private static double arc(double[] a, double[] b) {
        return Math.atan2(Math.sqrt(dot(cross(a, b), cross(a, b))), dot(a, b));
    }

    /** The angular distance of {@code p} from the great-circle arc from {@code a} to {@code b}. */
    private static double arcDistance(double[] p, double[] a, double[] b) {
        double[] n = normalise(cross(a, b));
        double d = dot(p, n);
        double[] foot = normalise(new double[] {p[0] - d * n[0], p[1] - d * n[1], p[2] - d * n[2]});
        if (Math.abs(arc(a, foot) + arc(foot, b) - arc(a, b)) < 1e-9) {
            return Math.abs(Math.asin(d));
        }
        return Math.min(arc(p, a), arc(p, b));
    }

    /** The distance of {@code p} from the segment from {@code a} to {@code b} in the plane. */
    private static double segmentDistance(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
    }

    /**
     * How far each point is from the nearest decision a Polygon makes about it, in its base Frame
     * (on the sky in radians, in a plain Frame in its units): the least, over the edges, of the
     * difference between the point's distance from the edge and the distance within which the
     * edge counts as containing it ({@code 1e-7} of its length, and at least {@code 1e-10} on the
     * sky); and the least distance of the point from the line from the Polygon's point inside
     * through a vertex, wherever that line reaches as far as the point, where the count of edges
     * the line to the point crosses changes.
     */
    static double clearance(Polygon p, double[][] pts, int npix, boolean sky) {
        double[][] v = p.getRegionPoints();
        int nv = v[0].length;
        double[] in = p.insidePoint();
        double least = Double.MAX_VALUE;
        for (int i = 0; i < npix; i++) {
            double[] q = {pts[0][i], pts[1][i]};
            for (int k = 0; k < nv; k++) {
                int j = (k + nv - 1) % nv;
                double[] a = {v[0][j], v[1][j]};
                double[] b = {v[0][k], v[1][k]};
                double edge;
                double ray;
                if (sky) {
                    double[] pq = unit(q[0], q[1]);
                    double[] pa = unit(a[0], a[1]);
                    double[] pb = unit(b[0], b[1]);
                    double band = Math.max(1e-7 * arc(pa, pb), 1e-10);
                    edge = Math.abs(arcDistance(pq, pa, pb) - band);
                    double[] pin = unit(in[0], in[1]);
                    ray = arc(pin, pq) >= arc(pin, pb) - 1e-3
                            ? Math.abs(Math.asin(dot(pq, normalise(cross(pin, pb)))))
                            : Double.MAX_VALUE;
                } else {
                    double band = 1e-7 * Math.hypot(b[0] - a[0], b[1] - a[1]);
                    edge = Math.abs(segmentDistance(q, a, b) - band);
                    double far = Math.hypot(b[0] - in[0], b[1] - in[1]);
                    double reach = Math.hypot(q[0] - in[0], q[1] - in[1]);
                    ray = reach >= far - 1.0
                            ? Math.abs((b[0] - in[0]) * (q[1] - in[1]) - (b[1] - in[1]) * (q[0] - in[0])) / far
                            : Double.MAX_VALUE;
                }
                least = Math.min(least, Math.min(edge, ray));
            }
        }
        return least;
    }

    private static void checkClear(String wcs) {
        Setup s = new Setup(wcs);
        double[][] onSky = s.grid2sky.transform(s.centres, s.npix, true);
        double sky = clearance(s.poly, onSky, s.npix, true);
        assertTrue(sky > 1e-9, "a pixel centre is " + sky + " radians from a decision on the sky");
        Polygon flat = (Polygon) s.poly.mapRegion(s.sky2grid, s.grid);
        double grid = clearance(flat, s.centres, s.npix, false);
        assertTrue(grid > 1e-6, "a pixel centre is " + grid + " pixels from a decision in the grid");
        for (double[] b : s.bounds) {
            for (double x : b) {
                assertTrue(Math.abs(x - Math.rint(x)) > 1e-6, "bound " + x);
            }
        }
    }

    @Test
    void pixelsAreClearOfEveryBoundary() {
        checkClear(CAR_WCS);
        checkClear(TAN_WCS);
    }
}
