package uk.ac.starlink.ast.internal.wcslib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.AstException;
import uk.ac.starlink.ast.testsupport.KernelInputs;

/**
 * The tests of Rust {@code src/wcslib/prj.rs} ({@code mod tests}).
 *
 * <p>Rust's slice entries run many projections through branchless kernels and redo per point the
 * points those kernels do not cover; here every slice runs the per-value body for each point. So
 * the Rust tests that a slice keeps exact points exact, gives the per-value entry's bits at the
 * points the kernels do not cover, or agrees with the per-value entry on bad and good points
 * assert here that the slice gives the per-value entry's bits, and its bad points, at the Rust
 * test's inputs, at every point rather than only those a kernel predicate selects. Their
 * assertions that do not depend on the kernels are kept; the bounds on kernel drift are not, since
 * there is none.
 *
 * <p>Deferred as {@code hardware SIMD}, since they test the kernels' drift from the per-value body,
 * the margins within which a slice redoes a point, or the agreement of the builds with and without
 * fused multiply-adds: every {@code *_slices_are_within_kernel_drift_of_the_per_value_body}, every
 * {@code *_margin_covers_the_kernels_drift_and_a_zero_margin_does_not} and {@code
 * cop_margins_cover_the_kernels_drift_and_a_zero_margin_does_not}, and every {@code
 * *_slice_builds_agree}.
 *
 * <p>{@code tpn_cap_points_agree_on_bad_or_good} keeps its check of which points the per-value
 * entry accepts and its slices; its checks of the iteration at which each point converges call
 * Rust's {@code tpn_fwd_solve} with an iteration limit, and its checks of a front end nudged by 2
 * ULP stand in for the kernels, so neither is translated.
 */
class PrjTest {

    private static final double BAD = -Double.MAX_VALUE;

    private static final double[] NONE = {};

    /** The pair as given: the {@code prep} and {@code finish} of a slice entry that stores the projection's own inputs and outputs. */
    private static final Prj.PairFunction UNCHANGED = (u, v, out) -> {
        out[0] = u;
        out[1] = v;
    };

    /** A projection with no parameters supplied. */
    private static Prj prj(WcsProjection proj) {
        return Prj.create(proj, NONE, NONE, true);
    }

    /** {@code proj} with the latitude-axis parameters {@code p}, from {@code p[0]}. */
    private static Prj withParams(WcsProjection proj, double... p) {
        return Prj.create(proj, p, NONE, true);
    }

    /** The projection's parameters and derived constants, Rust's {@code p.prm}. */
    private static Prj.PrjPrm prm(Prj p) {
        return p.params();
    }

    /** The projection, Rust's {@code p.proj}. */
    private static WcsProjection proj(Prj p) {
        return p.projection();
    }

    private static double[] pair(double u, double v) {
        return new double[] {u, v};
    }

    @Test
    void tanReferencePoint() {
        // TAN reference point is (0, 90) degrees: it projects to (0, 0).
        double[] out = new double[2];
        assertEquals(Prj.Status.OK, prj(WcsProjection.TAN).fwdRaw(0.0, 90.0, out));
        assertTrue(Math.abs(out[0]) < 1e-10, "x = " + out[0]);
        assertTrue(Math.abs(out[1]) < 1e-10, "y = " + out[1]);
    }

    @Test
    void roundTrips() {
        Object[][] cases = {
            {WcsProjection.TAN, 10.0, 80.0},
            {WcsProjection.CAR, 45.0, 30.0},
            {WcsProjection.SIN, 5.0, 85.0},
            {WcsProjection.AIT, 30.0, 45.0},
            {WcsProjection.ARC, 15.0, 60.0},
            {WcsProjection.STG, 20.0, 70.0},
            {WcsProjection.MER, 25.0, 40.0},
            {WcsProjection.ZEA, 12.0, 55.0},
            {WcsProjection.MOL, 35.0, 50.0},
        };
        for (Object[] c : cases) {
            WcsProjection proj = (WcsProjection) c[0];
            double phi0 = (Double) c[1];
            double theta0 = (Double) c[2];
            Prj p = prj(proj);
            double[] xy = new double[2];
            assertEquals(Prj.Status.OK, p.fwdRaw(phi0, theta0, xy), proj.code() + " forward");
            double[] pt = new double[2];
            assertEquals(Prj.Status.OK, p.revRaw(xy[0], xy[1], pt), proj.code() + " reverse");
            assertTrue(Math.abs(pt[0] - phi0) < 1e-10, proj.code() + " phi = " + pt[0]);
            assertTrue(Math.abs(pt[1] - theta0) < 1e-10, proj.code() + " theta = " + pt[1]);
        }
    }

    @Test
    void ncpGlsAndUnknownAreRejected() {
        // These must be mapped to another projection at the WcsMap level.
        for (WcsProjection proj : new WcsProjection[] {WcsProjection.NCP, WcsProjection.GLS, WcsProjection.UNKNOWN}) {
            assertThrows(AstException.class, () -> Prj.create(proj, NONE, NONE, true), proj.code());
        }
    }

    @Test
    void zpnSetUpFailsUntilACoefficientIsSupplied() {
        // ZPN's set-up fails while its coefficients are all zero, and every call returns that
        // status.
        double[] out = new double[2];
        Prj p = prj(WcsProjection.ZPN);
        assertNotEquals(Prj.Status.OK, p.fwdRaw(0.0, 80.0, out));
        assertNotEquals(Prj.Status.OK, p.revRaw(0.0, 0.1, out));
        // The linear coefficient makes the polynomial well defined.
        p = Prj.create(WcsProjection.ZPN, new double[] {BAD, 1.0}, NONE, true);
        assertEquals(Prj.Status.OK, p.fwdRaw(0.0, 80.0, out));
    }

    @Test
    void getParamOutOfRange() {
        Prj p = prj(WcsProjection.TAN);
        assertNull(p.getParam(0, WcsProjection.PRJ_PARAM_SLOTS));
        assertNull(p.getParam(2, 0));
    }

    @Test
    void suppliedParametersAreCopied() {
        double[] lat = new double[6];
        Arrays.fill(lat, BAD);
        lat[5] = 42.0;
        double[] lon = new double[11];
        Arrays.fill(lon, BAD);
        lon[10] = -3.14;
        Prj p = Prj.create(WcsProjection.TAN, lat, lon, true);
        assertEquals(42.0, (double) p.getParam(0, 5));
        assertEquals(-3.14, (double) p.getParam(1, 10));
        assertEquals(0.0, (double) p.getParam(0, 4));
    }

    /**
     * {@code tpnTan} selects whether the transformation includes the TAN projection as well as the
     * polynomial. With the default unit coefficients the polynomial is the identity, so clearing it
     * makes the whole projection an identity.
     */
    @Test
    void tpnTanSelectsPolynomialOnlyForm() {
        Prj withTan = Prj.create(WcsProjection.TPN, NONE, NONE, true);
        Prj polyOnly = Prj.create(WcsProjection.TPN, NONE, NONE, false);
        double[] tan = new double[2];
        double[] poly = new double[2];
        withTan.fwdRaw(0.0, 60.0, tan);
        polyOnly.fwdRaw(0.0, 60.0, poly);
        assertTrue(
                Math.abs(poly[0]) < 1e-10 && Math.abs(poly[1] - 60.0) < 1e-10,
                "polynomial-only TPN should pass its inputs through: (" + poly[0] + ", " + poly[1] + ")");
        assertTrue(
                Math.abs(tan[1] - poly[1]) > 1.0, "TPNTan should change the projection: " + tan[1] + " vs " + poly[1]);
    }

    /** Bit equality, with every NaN equal to every other. */
    private static boolean sameBitsOrBothNan(double a, double b) {
        return (Double.isNaN(a) && Double.isNaN(b)) || Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    /** The pairs through {@code fwdSlice} (or {@code revSlice} when {@code !fwd}) in one slice. */
    private static double[][] throughSlice(Prj p, List<double[]> pairs, boolean fwd) {
        int n = pairs.size();
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = pairs.get(i)[0];
            b[i] = pairs.get(i)[1];
        }
        boolean ok = fwd ? p.fwdSlice(a, b, n, UNCHANGED, UNCHANGED) : p.revSlice(a, b, n, UNCHANGED, UNCHANGED);
        assertTrue(ok, "the slice found the parameters unusable");
        return new double[][] {a, b};
    }

    /**
     * Each output of the slice entry is the per-value entry's, bit for bit (NaN equal to NaN),
     * where it projects the point; a bad input or a point the per-value entry cannot project is
     * bad in the slice.
     */
    private static void assertSliceMatchesPerValue(Prj p, List<double[]> pairs, boolean fwd) {
        double[][] got = throughSlice(p, pairs, fwd);
        double[] w = new double[2];
        for (int i = 0; i < pairs.size(); i++) {
            double u = pairs.get(i)[0];
            double v = pairs.get(i)[1];
            boolean badIn = u == BAD || v == BAD || Double.isNaN(u) || Double.isNaN(v);
            Prj.Status status = fwd ? p.fwdRaw(u, v, w) : p.revRaw(u, v, w);
            double wx;
            double wy;
            if (badIn || status == Prj.Status.BAD_POINT) {
                wx = BAD;
                wy = BAD;
            } else {
                assertEquals(Prj.Status.OK, status, "(" + u + ", " + v + ")");
                wx = w[0];
                wy = w[1];
            }
            double x = got[0][i];
            double y = got[1][i];
            if (!sameBitsOrBothNan(x, wx) || !sameBitsOrBothNan(y, wy)) {
                fail("fwd " + fwd + ", (" + u + ", " + v + "): slice gives (" + x + ", " + y + "), per-value entry ("
                        + wx + ", " + wy + ")");
            }
        }
    }

    /** {@code v} moved {@code n} ULP away from zero (towards it for negative {@code n}). */
    private static double ulpsAway(double v, long n) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(v) + n);
    }

    /** The specials and the multiples of {@code step} degrees from -8 to 8 steps. */
    private static double[] specialsAnd(double step) {
        double[] sp = KernelInputs.specials();
        double[] edges = Arrays.copyOf(sp, sp.length + 17);
        for (int k = -8; k <= 8; k++) {
            edges[sp.length + k + 8] = step * k;
        }
        return edges;
    }

    /** Inputs with specials and multiples of 90 degrees interleaved among random angles. */
    private static List<double[]> interleavedInputs() {
        double[] d = KernelInputs.degrees(3000);
        double[] edges = specialsAnd(90.0);
        List<double[]> out = new ArrayList<>(d.length);
        for (int i = 0; i < d.length; i++) {
            double u = d[i];
            double v = d[(i * 7 + 3) % d.length];
            switch (i % 5) {
                case 0:
                    out.add(pair(edges[i % edges.length], v));
                    break;
                case 1:
                    out.add(pair(u, edges[(i / 5) % edges.length]));
                    break;
                default:
                    out.add(pair(u, v));
                    break;
            }
        }
        return out;
    }

    /** Each pair gets the same bits through either slice entry of {@code p} whatever the length of the slice it is in. */
    private static void assertSameBitsAtAnyLength(Prj p, List<double[]> pairs) {
        for (boolean fwd : new boolean[] {true, false}) {
            double[][] whole = throughSlice(p, pairs, fwd);
            for (int len : new int[] {1, 2, 3, 4, 5, 7, 1001}) {
                for (int start = 0; start < pairs.size(); start += len) {
                    List<double[]> chunk = pairs.subList(start, Math.min(start + len, pairs.size()));
                    double[][] got = throughSlice(p, chunk, fwd);
                    for (int i = 0; i < chunk.size(); i++) {
                        double gx = got[0][i];
                        double gy = got[1][i];
                        double wx = whole[0][start + i];
                        double wy = whole[1][start + i];
                        if (!sameBitsOrBothNan(gx, wx) || !sameBitsOrBothNan(gy, wy)) {
                            fail("fwd " + fwd + ", length " + len + ": " + Arrays.toString(chunk.get(i)) + " gives ("
                                    + gx + ", " + gy + ") alone, (" + wx + ", " + wy + ") in the whole");
                        }
                    }
                }
            }
        }
    }

    @Test
    void tanSlicesGiveEachPointTheSameBitsAtAnyLength() {
        assertSameBitsAtAnyLength(prj(WcsProjection.TAN), interleavedInputs());
    }

    @Test
    void tanExactPointsStayExactInASlice() {
        Prj p = prj(WcsProjection.TAN);
        double[] phi = {10.0, 20.0, 30.0, 0.0, 40.0, 50.0, 60.0};
        double[] theta = {80.0, 70.0, 60.0, 90.0, 50.0, 40.0, 30.0};
        assertTrue(p.fwdSlice(phi, theta, phi.length, UNCHANGED, UNCHANGED));
        // Exactly zero, with the signs the per-value entry gives.
        double[] w = new double[2];
        p.fwdRaw(0.0, 90.0, w);
        assertTrue(phi[3] == 0.0 && theta[3] == 0.0, "(" + phi[3] + ", " + theta[3] + ")");
        assertEquals(Double.doubleToRawLongBits(w[0]), Double.doubleToRawLongBits(phi[3]));
        assertEquals(Double.doubleToRawLongBits(w[1]), Double.doubleToRawLongBits(theta[3]));
        double[] x = {1.0, 2.0, 3.0, 0.0, 4.0, 5.0, 6.0};
        double[] y = {-1.0, -2.0, -3.0, 0.0, -4.0, -5.0, -6.0};
        assertTrue(p.revSlice(x, y, x.length, UNCHANGED, UNCHANGED));
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(x[3]));
        assertEquals(Double.doubleToRawLongBits(90.0), Double.doubleToRawLongBits(y[3]));
    }

    /**
     * SIN with the latitude-axis parameters {@code p1} and {@code p2}: the orthographic projection
     * when both are zero, otherwise the synthesis projection.
     */
    private static Prj sinPrj(double p1, double p2) {
        return Prj.create(WcsProjection.SIN, new double[] {BAD, p1, p2}, NONE, true);
    }

    /** The SIN parameter sets the tests use: orthographic; NCP's, for a field at declination 30 degrees; and a general synthesis projection. */
    private static Prj[] sinPrjs() {
        return new Prj[] {sinPrj(0.0, 0.0), sinPrj(0.0, 1.0 / Math.tan(30.0 * (Math.PI / 180.0))), sinPrj(0.125, -0.2)};
    }

    @Test
    void sinSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : sinPrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /** The pole projects to exactly the per-value entry's bits in a slice. */
    @Test
    void sinExactPointsStayExactInASlice() {
        for (Prj p : sinPrjs()) {
            double[] phi = {10.0, 20.0, 30.0, 0.0, 40.0, 50.0, 60.0};
            double[] theta = {80.0, 70.0, 60.0, 90.0, 50.0, 40.0, 30.0};
            assertTrue(p.fwdSlice(phi, theta, phi.length, UNCHANGED, UNCHANGED));
            double[] w = new double[2];
            p.fwdRaw(0.0, 90.0, w);
            assertEquals(Double.doubleToRawLongBits(w[0]), Double.doubleToRawLongBits(phi[3]));
            assertEquals(Double.doubleToRawLongBits(w[1]), Double.doubleToRawLongBits(theta[3]));
        }
    }

    /**
     * Points at, a few ULP either side of, and a few multiples of 2^-46 and 1e-13 degrees either
     * side of a projection's lowest valid latitude {@code limit}, as the per-value entry computes
     * it, at longitude {@code phi}.
     */
    private static void addLimitPoints(List<double[]> pairs, double phi, double limit) {
        for (int n = -8; n <= 8; n++) {
            pairs.add(pair(phi, ulpsAway(limit, n)));
            pairs.add(pair(phi, limit + n * 0x1.0p-46));
            pairs.add(pair(phi, limit + n * 1e-13));
        }
    }

    /** How many of the pairs the per-value forward entry does not project. */
    private static int countBad(Prj p, List<double[]> pairs) {
        double[] w = new double[2];
        int bad = 0;
        for (double[] q : pairs) {
            if (p.fwdRaw(q[0], q[1], w) != Prj.Status.OK) {
                bad++;
            }
        }
        return bad;
    }

    /**
     * Points at and a few ULP either side of the synthesis projection's lowest valid latitude, as
     * the per-value entry computes it, are bad in the forward slice exactly when they are bad in the
     * per-value entry.
     */
    @Test
    void sinSynthesisLimitPointsAgreeOnBadOrGood() {
        Prj[] prjs = sinPrjs();
        for (Prj p : new Prj[] {prjs[1], prjs[2]}) {
            Prj.PrjPrm prm = prm(p);
            List<double[]> pairs = new ArrayList<>();
            for (double phi : KernelInputs.degrees(20_000)) {
                double limit = -Trig.atand(prm.p[1] * Trig.sind(phi) - prm.p[2] * Trig.cosd(phi));
                addLimitPoints(pairs, phi, limit);
            }
            int bad = countBad(p, pairs);
            assertTrue(bad > 0 && bad < pairs.size(), bad + " of " + pairs.size() + " bad");
            assertSliceMatchesPerValue(p, pairs, true);
        }
    }

    /** ZPN with the latitude-axis parameters {@code p}, from {@code p[0]}. */
    private static Prj zpnPrj(double... p) {
        return withParams(WcsProjection.ZPN, p);
    }

    /**
     * The ZPN parameter sets the tests use, one per branch of the reverse projection's root search:
     * linear; quadratic, bad beyond 2.5 radians from the pole; the cubic of the benchmarks, with no
     * point of inflection; and a quintic with a point of inflection.
     */
    private static Prj[] zpnPrjs() {
        return new Prj[] {
            zpnPrj(0.001, 1.0),
            zpnPrj(0.0, 1.0, -0.1),
            zpnPrj(0.0, 1.0, 0.0, 0.05),
            zpnPrj(0.0, 1.0, 0.0, -0.08, 0.0, 0.002)
        };
    }

    @Test
    void zpnSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : zpnPrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /** The pole, and longitudes at multiples of 90 degrees, get the per-value entry's bits in a slice. */
    @Test
    void zpnExactPointsStayExactInASlice() {
        for (Prj p : zpnPrjs()) {
            List<double[]> pairs = new ArrayList<>();
            pairs.add(pair(0.0, 90.0));
            pairs.add(pair(-0.0, 90.0));
            pairs.add(pair(10.0, 80.0));
            for (int k = -4; k <= 4; k++) {
                double a = 90.0 * k;
                pairs.add(pair(a, 80.0));
                pairs.add(pair(a, 90.0));
                pairs.add(pair(a, -10.0));
            }
            double[][] got = throughSlice(p, pairs, true);
            double[] w = new double[2];
            for (int i = 0; i < pairs.size(); i++) {
                double u = pairs.get(i)[0];
                double v = pairs.get(i)[1];
                Prj.Status status = p.fwdRaw(u, v, w);
                double wx = status == Prj.Status.OK ? w[0] : BAD;
                double wy = status == Prj.Status.OK ? w[1] : BAD;
                assertEquals(
                        Double.doubleToRawLongBits(wx),
                        Double.doubleToRawLongBits(got[0][i]),
                        "(" + u + ", " + v + ")");
                assertEquals(
                        Double.doubleToRawLongBits(wy),
                        Double.doubleToRawLongBits(got[1][i]),
                        "(" + u + ", " + v + ")");
            }
        }
    }

    /** One axis's parameters of the DSS plate solution: the values at their indices, and 0 for indices 17, 19 and 21. */
    private static double[] dssAxis(double[][] pv) {
        double[] out = new double[22];
        Arrays.fill(out, BAD);
        for (double[] mv : pv) {
            out[(int) mv[0]] = mv[1];
        }
        for (int m : new int[] {17, 19, 21}) {
            out[m] = 0.0;
        }
        return out;
    }

    /**
     * The TPN projection of the DSS plate solution in {@code tests/fixtures/wcsconv/inputs/dss.ast}:
     * its {@code PV1_m}, of the longitude axis, and {@code PV2_m}, of the latitude axis.
     */
    private static Prj dssPrj(boolean tpnTan) {
        double[][] pv1 = {
            {0, 0.00037777813768480556},
            {1, 0.018675372165510556},
            {2, 1.4659181119170556e-05},
            {4, -5.654183449024166e-09},
            {5, -1.6598619578175834e-10},
            {6, 3.324645548432778e-09},
            {7, 6.80293751629639e-10},
            {8, -1.0315391309210556e-11},
            {9, 6.577018409631667e-10},
            {10, 4.6843790588691666e-11},
        };
        double[][] pv2 = {
            {0, 0.000207343956905325},
            {1, 0.01867508980654278},
            {2, -1.6578391725152224e-05},
            {4, -5.137876793798055e-09},
            {5, -1.7623932712259446e-09},
            {6, 2.716154731325139e-10},
            {7, 7.088907407099166e-10},
            {8, 1.8432618513145277e-11},
            {9, 6.849106198956944e-10},
            {10, 7.332585963470833e-13},
        };
        return Prj.create(WcsProjection.TPN, dssAxis(pv2), dssAxis(pv1), tpnTan);
    }

    /**
     * The native (phi, theta) of the points (0, c) and (c, 0) of the projection plane, for each
     * {@code c}: points whose forward projection has a coordinate at zero, below the rounding floor
     * of the Newton iteration's convergence test.
     */
    private static List<double[]> tpnAxisImages(Prj p, double[] coords) {
        List<double[]> out = new ArrayList<>(2 * coords.length);
        double[] w = new double[2];
        for (double c : coords) {
            for (double[] xy : new double[][] {{0.0, c}, {c, 0.0}}) {
                assertEquals(Prj.Status.OK, p.revRaw(xy[0], xy[1], w));
                out.add(pair(w[0], w[1]));
            }
        }
        return out;
    }

    /** Points on the axes of the projection plane get the per-value entry's bits in a slice, bad or good. */
    @Test
    void tpnAxisPointsMatchThePerValueEntryInASlice() {
        Prj p = dssPrj(true);
        List<double[]> pairs = tpnAxisImages(p, KernelInputs.degrees(20_000));
        double[][] got = throughSlice(p, pairs, true);
        double[] w = new double[2];
        int bad = 0;
        for (int i = 0; i < pairs.size(); i++) {
            double u = pairs.get(i)[0];
            double v = pairs.get(i)[1];
            double wx;
            double wy;
            if (p.fwdRaw(u, v, w) == Prj.Status.OK) {
                wx = w[0];
                wy = w[1];
            } else {
                bad++;
                wx = BAD;
                wy = BAD;
            }
            if (Double.doubleToRawLongBits(got[0][i]) != Double.doubleToRawLongBits(wx)
                    || Double.doubleToRawLongBits(got[1][i]) != Double.doubleToRawLongBits(wy)) {
                fail("(" + u + ", " + v + "): slice gives (" + got[0][i] + ", " + got[1][i] + "), per-value entry ("
                        + wx + ", " + wy + ")");
            }
        }
        // Some of these points are bad in the per-value entry, its iteration stepping about a
        // coordinate that is rounding noise.
        assertTrue(bad > 0, "no axis point is bad; is the class exercised?");
    }

    @Test
    void tpnSlicesGiveEachPointTheSameBitsAtAnyLength() {
        assertSameBitsAtAnyLength(dssPrj(true), interleavedInputs());
    }

    /**
     * Points just off an axis of the projection plane, from 1e-16 to 1e-6 of a plane unit from it,
     * near the floor below which the convergence test is decided by rounding: bad in the slice
     * exactly when bad in the per-value entry, and otherwise the per-value entry's bits.
     */
    @Test
    void tpnPointsNearTheFloorAgreeAndStayWithinTheBound() {
        Prj p = dssPrj(true);
        double[] c = KernelInputs.degrees(4000);
        List<double[]> pairs = new ArrayList<>();
        double[] w = new double[2];
        for (int i = 0; i < c.length; i++) {
            double t = (i % 101) / 100.0;
            double sign = i % 2 == 0 ? 1.0 : -1.0;
            double delta = sign * Math.pow(10.0, -6.0 - 10.0 * t);
            for (double[] xy : new double[][] {{delta, c[i]}, {c[i], delta}}) {
                assertEquals(Prj.Status.OK, p.revRaw(xy[0], xy[1], w));
                pairs.add(pair(w[0], w[1]));
            }
        }
        assertSliceMatchesPerValue(p, pairs, true);
    }

    /**
     * Points whose per-value forward projection with {@link #dssPrj}'s parameters converges at the
     * given Newton iteration, found by searching random points near the equator for high iteration
     * counts. Those converging at the 51st are bad: the per-value entry stops at the 50th.
     */
    private static final double[][] TPN_CAP_POINTS = {
        {49, 28.458582279307073, 4.709692032133452e-11},
        {49, -158.55330477812421, 3.7952685308572407e-11},
        {49, -134.31793310169147, 6.35517181385545e-11},
        {50, 55.45920210984241, 2.801028035332779e-11},
        {50, 90.91227084307462, 3.30257334268539e-11},
        {50, -21.350194372456656, 3.669788568433079e-11},
        {51, -113.96782653838751, 1.1827860341910004e-11},
        {51, -160.2045647649468, 1.5607410389929512e-11},
        {51, -154.86670272665023, 1.827352429797668e-11},
    };

    /**
     * A point whose iteration converges at or next to the per-value entry's limit is bad in the
     * forward slice exactly when it is bad in the per-value entry.
     */
    @Test
    void tpnCapPointsAgreeOnBadOrGood() {
        Prj p = dssPrj(true);
        double[] w = new double[2];
        List<double[]> cap = new ArrayList<>();
        for (double[] kpt : TPN_CAP_POINTS) {
            int k = (int) kpt[0];
            double phi = kpt[1];
            double theta = kpt[2];
            assertEquals(
                    k <= Tpn.NEWTON_MAX_ITERATIONS,
                    p.fwdRaw(phi, theta, w) == Prj.Status.OK,
                    "(" + phi + ", " + theta + ")");
            cap.add(pair(phi, theta));
        }
        // Alone, together, and among other points and points on the axes of the projection plane.
        List<double[]> mixed = interleavedInputs();
        mixed.addAll(tpnAxisImages(p, KernelInputs.degrees(2000)));
        for (int i = 0; i < cap.size(); i++) {
            mixed.add(i * 97 + 5, cap.get(i));
        }
        for (double[] q : cap) {
            assertSliceMatchesPerValue(p, Collections.singletonList(q), true);
        }
        assertSliceMatchesPerValue(p, cap, true);
        assertSliceMatchesPerValue(p, mixed, true);
    }

    /** A point with an angle at a multiple of 90 degrees gets the per-value entry's bits in a slice. */
    @Test
    void tpnPointsTheKernelsDoNotCoverAreExactInASlice() {
        Prj p = dssPrj(true);
        List<double[]> pairs = new ArrayList<>();
        for (int k = -4; k <= 4; k++) {
            double a = 90.0 * k;
            pairs.add(pair(a, 89.5));
            pairs.add(pair(10.0, a));
            pairs.add(pair(a, a));
            pairs.add(pair(-170.0, 89.0 + k / 8.0));
        }
        assertSliceMatchesPerValue(p, pairs, true);
    }

    /** Pairs with a coordinate at a multiple of 90 degrees, which Rust's kernels do not cover, among others they do. */
    private static List<double[]> rightAnglePairs() {
        List<double[]> pairs = new ArrayList<>();
        pairs.add(pair(0.0, 90.0));
        pairs.add(pair(-0.0, 90.0));
        pairs.add(pair(10.0, 80.0));
        for (int k = -8; k <= 8; k++) {
            double a = 90.0 * k;
            pairs.add(pair(a, 80.0));
            pairs.add(pair(a, 89.5));
            pairs.add(pair(a, -10.0));
            pairs.add(pair(10.0, a));
            pairs.add(pair(a, a));
            pairs.add(pair(-170.0, 89.0 + k / 8.0));
        }
        return pairs;
    }

    /**
     * The AZP parameter sets the tests use: the default; a perspective from beyond the sphere,
     * tilted, without the divergence test; one from within it, tilted, with it; and an untilted one
     * at the sphere's centre whose divergence limit is where the denominator is zero.
     */
    private static Prj[] azpPrjs() {
        return new Prj[] {
            prj(WcsProjection.AZP),
            withParams(WcsProjection.AZP, 0.0, 2.0, 30.0),
            withParams(WcsProjection.AZP, 0.0, 0.5, 20.0),
            withParams(WcsProjection.AZP, 0.0, 0.0, 30.0)
        };
    }

    /**
     * Points at and a few ULP either side of AZP's divergence limit, as the per-value entry computes
     * it, where its denominator is near zero for the untilted perspective from the centre: bad in
     * the forward slice exactly when bad in the per-value entry.
     */
    @Test
    void azpLimitPointsAgreeOnBadOrGood() {
        for (Prj p : azpPrjs()) {
            Prj.PrjPrm prm = prm(p);
            boolean divergenceTest = prm.w[7] > 0.0;
            if (!divergenceTest) {
                continue;
            }
            List<double[]> pairs = new ArrayList<>();
            for (double phi : KernelInputs.degrees(20_000)) {
                double s = prm.w[1] * Trig.cosd(phi);
                double t = prm.p[1] / Math.sqrt(1.0 + s * s);
                s = Trig.atand(-s);
                t = Trig.asind(t);
                double a = s - t;
                double b = s + t + 180.0;
                if (a > 90.0) {
                    a -= 360.0;
                }
                if (b > 90.0) {
                    b -= 360.0;
                }
                addLimitPoints(pairs, phi, a > b ? a : b);
            }
            int bad = countBad(p, pairs);
            assertTrue(bad > 0 && bad < pairs.size(), bad + " of " + pairs.size() + " bad");
            assertSliceMatchesPerValue(p, pairs, true);
        }
    }

    /**
     * The SZP parameter sets the tests use: the default, a perspective from beyond the sphere with
     * the overlap test, and one from within it whose divergence limit is where the denominator is
     * zero.
     */
    private static Prj[] szpPrjs() {
        return new Prj[] {
            prj(WcsProjection.SZP),
            withParams(WcsProjection.SZP, 0.0, 2.0, 30.0, 60.0),
            withParams(WcsProjection.SZP, 0.0, 0.5, 0.0, -30.0)
        };
    }

    /**
     * Points at and a few ULP either side of SZP's divergence limit, where its denominator is near
     * zero for the perspective from within the sphere: bad in the forward slice exactly when bad in
     * the per-value entry.
     */
    @Test
    void szpLimitPointsAgreeOnBadOrGood() {
        Prj p = szpPrjs()[2];
        double limit = prm(p).w[8];
        List<double[]> pairs = new ArrayList<>();
        for (double phi : KernelInputs.degrees(20_000)) {
            addLimitPoints(pairs, phi, limit);
        }
        int bad = countBad(p, pairs);
        assertTrue(bad > 0 && bad < pairs.size(), bad + " of " + pairs.size() + " bad");
        assertSliceMatchesPerValue(p, pairs, true);
    }

    /** The zenithal projections whose forward slices run Rust's kernels, with their parameter sets, and SZP, whose reverse slice does too. */
    private static List<Prj> zenithalKernelPrjs() {
        List<Prj> out = new ArrayList<>();
        out.add(prj(WcsProjection.ARC));
        out.add(prj(WcsProjection.ZEA));
        out.add(prj(WcsProjection.STG));
        out.add(prj(WcsProjection.AIR));
        out.add(withParams(WcsProjection.AIR, 0.0, 45.0));
        out.addAll(Arrays.asList(azpPrjs()));
        out.addAll(Arrays.asList(szpPrjs()));
        return out;
    }

    @Test
    void zenithalSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : zenithalKernelPrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /** The pole, and coordinates at multiples of 90 degrees, get the per-value entry's bits in a slice. */
    @Test
    void zenithalPointsTheKernelsDoNotCoverAreExactInASlice() {
        List<double[]> sky = rightAnglePairs();
        List<double[]> plane = new ArrayList<>();
        for (double[] q : sky) {
            plane.add(pair(q[0], q[1]));
            plane.add(pair(0.0, q[1]));
            plane.add(pair(q[0], 0.0));
            plane.add(pair(0.0, 0.0));
        }
        for (Prj p : zenithalKernelPrjs()) {
            assertSliceMatchesPerValue(p, sky, true);
            if (proj(p) == WcsProjection.SZP) {
                assertSliceMatchesPerValue(p, plane, false);
            }
        }
    }

    /**
     * The CYP parameter sets the tests use: the default, Gall's, and one whose denominator is zero
     * at a latitude of 120 degrees.
     */
    private static Prj[] cypPrjs() {
        return new Prj[] {
            prj(WcsProjection.CYP),
            // Rust's FRAC_1_SQRT_2.
            withParams(WcsProjection.CYP, 0.0, 1.0, 0.7071067811865476),
            withParams(WcsProjection.CYP, 0.0, 0.5, 0.8)
        };
    }

    /**
     * Points at and a few ULP either side of the latitudes where CYP's denominator is zero, as the
     * per-value entry computes them: bad in the forward slice exactly when bad in the per-value
     * entry.
     */
    @Test
    void cypZeroDenominatorPointsAgreeOnBadOrGood() {
        for (Prj p : cypPrjs()) {
            double zeroAt = Trig.acosd(-prm(p).p[1]);
            List<double[]> pairs = new ArrayList<>();
            for (double phi : KernelInputs.degrees(2000)) {
                for (double centre : new double[] {zeroAt, -zeroAt}) {
                    for (int n = -64; n <= 64; n++) {
                        pairs.add(pair(phi, ulpsAway(centre, n)));
                        pairs.add(pair(phi, centre + n * 1e-9));
                    }
                }
            }
            assertSliceMatchesPerValue(p, pairs, true);
        }
    }

    @Test
    void cypSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : cypPrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /** Latitudes at multiples of 90 degrees get the per-value entry's bits in a slice. */
    @Test
    void cypPointsTheKernelsDoNotCoverAreExactInASlice() {
        for (Prj p : cypPrjs()) {
            assertSliceMatchesPerValue(p, rightAnglePairs(), true);
        }
    }

    @Test
    void aitSlicesGiveEachPointTheSameBitsAtAnyLength() {
        assertSameBitsAtAnyLength(prj(WcsProjection.AIT), interleavedInputs());
    }

    /**
     * Coordinates at multiples of 90 degrees, and longitudes at multiples of 180 whose halves are,
     * get the per-value entry's bits in a slice.
     */
    @Test
    void aitPointsTheKernelsDoNotCoverAreExactInASlice() {
        List<double[]> pairs = rightAnglePairs();
        for (int k = -4; k <= 4; k++) {
            pairs.add(pair(180.0 * k, 30.0));
        }
        assertSliceMatchesPerValue(prj(WcsProjection.AIT), pairs, true);
    }

    /**
     * The conic projections with the parameter sets the tests use: the benchmarks' (45, 10) and a
     * cone below the equator, (-30, 15), for each, and for COE standard parallels at 70 and 90
     * degrees too, where the argument of its square root is zero at the north pole.
     */
    private static List<Prj> conicPrjs() {
        List<Prj> out = new ArrayList<>();
        for (WcsProjection proj :
                new WcsProjection[] {WcsProjection.COP, WcsProjection.COE, WcsProjection.COD, WcsProjection.COO}) {
            out.add(withParams(proj, 0.0, 45.0, 10.0));
            out.add(withParams(proj, 0.0, -30.0, 15.0));
        }
        out.add(withParams(WcsProjection.COE, 0.0, 80.0, 10.0));
        return out;
    }

    @Test
    void conicSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : conicPrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /**
     * Coordinates at multiples of 90 degrees and longitudes whose product with {@code w0} is get
     * the per-value entry's bits in a slice; so do COO's reverse points on the axis {@code x = 0},
     * whose arc tangent's argument is zero.
     */
    @Test
    void conicPointsTheKernelsDoNotCoverAreExactInASlice() {
        for (Prj p : conicPrjs()) {
            Prj.PrjPrm prm = prm(p);
            List<double[]> sky = rightAnglePairs();
            for (int k = -4; k <= 4; k++) {
                sky.add(pair(90.0 * k / prm.w[0], 30.0));
            }
            assertSliceMatchesPerValue(p, sky, true);
            if (proj(p) == WcsProjection.COO) {
                List<double[]> plane = new ArrayList<>();
                for (double[] q : sky) {
                    plane.add(pair(0.0, q[1]));
                    plane.add(pair(q[0], q[1]));
                    plane.add(pair(0.0, prm.w[2]));
                }
                assertSliceMatchesPerValue(p, plane, false);
            }
        }
    }

    /** BON with the parameter sets the tests use: standard parallels at 45 degrees, the benchmarks', and at -30. */
    private static Prj[] bonPrjs() {
        return new Prj[] {withParams(WcsProjection.BON, 0.0, 45.0), withParams(WcsProjection.BON, 0.0, -30.0)};
    }

    @Test
    void polyconicSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : bonPrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /**
     * Coordinates at multiples of 90 degrees, and BON's points where {@code r} is zero, whose
     * argument {@code a} is not finite, get the per-value entry's bits in a slice; so do BON's
     * reverse points on the axis {@code x = 0}, whose arc tangent's argument is zero, and those at
     * the poles, whose cosine's argument is a multiple of 90.
     */
    @Test
    void polyconicPointsTheKernelsDoNotCoverAreExactInASlice() {
        for (Prj p : bonPrjs()) {
            Prj.PrjPrm prm = prm(p);
            List<double[]> sky = rightAnglePairs();
            double apex = prm.w[2] / prm.w[1];
            sky.add(pair(10.0, apex));
            sky.add(pair(-30.0, apex));
            sky.add(pair(0.0, apex));
            assertSliceMatchesPerValue(p, sky, true);
            List<double[]> plane = new ArrayList<>();
            for (double[] q : sky) {
                plane.add(pair(0.0, q[1]));
            }
            for (double pole : new double[] {90.0, -90.0}) {
                plane.add(pair(0.0, prm.w[2] - (prm.w[2] - pole * prm.w[1])));
                plane.add(pair(1.0, prm.w[2] - (prm.w[2] - pole * prm.w[1])));
            }
            assertSliceMatchesPerValue(p, plane, false);
        }
    }

    /** The quad-cube projections, which have no parameters. */
    private static Prj[] quadcubePrjs() {
        return new Prj[] {prj(WcsProjection.TSC), prj(WcsProjection.CSC), prj(WcsProjection.QSC)};
    }

    @Test
    void quadcubeSlicesGiveEachPointTheSameBitsAtAnyLength() {
        for (Prj p : quadcubePrjs()) {
            assertSameBitsAtAnyLength(p, interleavedInputs());
        }
    }

    /** Coordinates at multiples of 90 degrees get TSC's and CSC's per-value entry's bits in a slice. */
    @Test
    void quadcubePointsTheKernelsDoNotCoverAreExactInASlice() {
        List<double[]> pairs = rightAnglePairs();
        assertSliceMatchesPerValue(prj(WcsProjection.TSC), pairs, true);
        assertSliceMatchesPerValue(prj(WcsProjection.CSC), pairs, true);
    }
}
