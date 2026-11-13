package uk.ac.starlink.ast.internal.pal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.erfa.Cal2jd;
import uk.ac.starlink.ast.internal.erfa.D2tf;
import uk.ac.starlink.ast.internal.erfa.Jd2cal;
import uk.ac.starlink.ast.internal.erfa.Pn;
import uk.ac.starlink.ast.internal.erfa.Tf2d;
import uk.ac.starlink.ast.testsupport.KernelInputs;

/**
 * The unit tests of Rust {@code src/pal/mod.rs} and {@code src/pal/dat.rs}.
 *
 * <p>The slice functions run the per-value functions at every point, having no vectorised kernel,
 * so the tests that compare the kernel's builds with each other are deferred as hardware SIMD, and
 * the tests that compare a slice with the per-value function where the kernel declines a point
 * check every point.
 */
class PalTest {

    private static final double BAD = -Double.MAX_VALUE;

    private static final double TOL = 1.0e-10;

    private static final double FRAC_PI_2 = Math.PI / 2.0;

    /**
     * The bits of {@code v}, every NaN giving the same bits: Java leaves the sign and payload of a
     * NaN that arithmetic produces unspecified, so comparisons through this are blind to them.
     */
    private static long bits(double v) {
        return Double.doubleToLongBits(v);
    }

    @Test
    void bitsTreatsEveryNanAlike() {
        // Java leaves the sign and payload of a NaN that arithmetic produces unspecified, and the
        // JIT can change them between two identical calls (seen on Java 25 x86-64).
        assertEquals(
                bits(Double.longBitsToDouble(0x7FF8000000000000L)), bits(Double.longBitsToDouble(0xFFF8000000000000L)));
    }

    private static void assertClose(double a, double b, double tol) {
        assertTrue(Math.abs(a - b) < tol, "expected " + a + " ~= " + b + " (diff = " + Math.abs(a - b) + ")");
    }

    private static boolean containsBad(double[] p) {
        for (double v : p) {
            if (v == BAD) {
                return true;
            }
        }
        return false;
    }

    /** Directions and special-value triples, interleaved so special values sit among ordinary ones. */
    private static List<double[]> mixedVectors() {
        double[] s = KernelInputs.specials();
        List<double[]> out = new ArrayList<>();
        List<double[]> units = KernelInputs.unitVectors(997);
        for (int i = 0; i < units.size(); i++) {
            out.add(units.get(i));
            out.add(new double[] {s[i % s.length], s[(i / 3) % s.length], s[(i / 7) % s.length]});
        }
        out.add(new double[] {-1.0, -0.0, 0.0});
        // Components whose arctangent quotients are tiny or subnormal, or whose magnitudes are near
        // the top of the range.
        double[] tinies = {1e-300, 1e-305, 1e-310, 3e-320, 4.9e-324};
        for (int i = 0; i < tinies.length; i++) {
            double tiny = tinies[i];
            for (double x : new double[] {1.0, -1.0, 0.75}) {
                out.add(units.get(i * 7));
                out.add(new double[] {x, tiny, 0.5});
                out.add(new double[] {x, 0.5, tiny});
                out.add(new double[] {x, -tiny, -tiny});
                out.add(new double[] {tiny, x, 1e-300});
            }
        }
        for (double big : new double[] {1e300, -1e302, 1.7e308}) {
            out.add(new double[] {big, 1.0, 1.0});
            out.add(new double[] {1.0, big, 0.5});
            out.add(new double[] {big, big / 3.0, 1e-10});
        }
        return out;
    }

    /** {@code dcc2sSlice} over {@code v}, one point per row, as {@code {a, b}} pairs. */
    private static double[][] sliceC2s(List<double[]> v) {
        int n = v.size();
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = v.get(i)[0];
            y[i] = v.get(i)[1];
            z[i] = v.get(i)[2];
        }
        double[] a = new double[n];
        double[] b = new double[n];
        Pal.dcc2sSlice(x, y, z, a, b);
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new double[] {a[i], b[i]};
        }
        return out;
    }

    private static long[] bitsOf(double[] v) {
        long[] out = new long[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = bits(v[i]);
        }
        return out;
    }

    @Test
    void dcc2sSliceGivesEachPointTheSameBitsAtAnyLength() {
        List<double[]> all = mixedVectors();
        double[][] whole = sliceC2s(all);
        for (int n : new int[] {1, 2, 3, 7, 1001}) {
            int start = all.size() - n;
            double[][] part = sliceC2s(all.subList(start, all.size()));
            for (int i = 0; i < n; i++) {
                assertArrayEquals(bitsOf(whole[start + i]), bitsOf(part[i]), Arrays.toString(all.get(start + i)));
            }
        }
    }

    @Test
    void dcc2sSliceIsWithinOneUlpOfDcc2s() {
        List<double[]> all = mixedVectors();
        double[][] got = sliceC2s(all);
        for (int i = 0; i < all.size(); i++) {
            double[] p = all.get(i);
            if (containsBad(p)) {
                continue;
            }
            double[] e = Pal.dcc2s(p);
            assertTrue(
                    KernelInputs.ulps(got[i][0], e[0]) <= 1 && KernelInputs.ulps(got[i][1], e[1]) <= 1,
                    Arrays.toString(p) + ": " + Arrays.toString(got[i]) + " vs " + Arrays.toString(e));
        }
    }

    /** {@code dcs2cSlice} over {@code v}, one point per row. */
    private static double[][] sliceS2c(List<double[]> v) {
        int n = v.size();
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = v.get(i)[0];
            b[i] = v.get(i)[1];
        }
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        Pal.dcs2cSlice(a, b, x, y, z);
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new double[] {x[i], y[i], z[i]};
        }
        return out;
    }

    /** Angle pairs and special-value pairs, interleaved. */
    private static List<double[]> mixedAngles() {
        double[] s = KernelInputs.specials();
        List<double[]> all = new ArrayList<>();
        List<double[]> pairs = KernelInputs.anglePairs(500);
        for (int i = 0; i < pairs.size(); i++) {
            all.add(pairs.get(i));
            all.add(new double[] {s[i % s.length], s[(i / 5) % s.length]});
        }
        return all;
    }

    @Test
    void slicesGiveBadOutputsForABadCoordinate() {
        double[] a = new double[3];
        double[] b = new double[3];
        Pal.dcc2sSlice(new double[] {1.0, BAD, 1.0}, new double[] {2.0, 0.5, 2.0}, new double[] {3.0, 0.5, BAD}, a, b);
        assertArrayEquals(new double[] {BAD, BAD, BAD, BAD}, new double[] {a[1], b[1], a[2], b[2]});
        assertArrayEquals(Pal.dcc2s(new double[] {1.0, 2.0, 3.0}), new double[] {a[0], b[0]});
        double[] x = new double[2];
        double[] y = new double[2];
        double[] z = new double[2];
        Pal.dcs2cSlice(new double[] {BAD, 0.5}, new double[] {0.25, BAD}, x, y, z);
        assertArrayEquals(new double[] {BAD, BAD}, x);
        assertArrayEquals(new double[] {BAD, BAD}, y);
        assertArrayEquals(new double[] {BAD, BAD}, z);
    }

    @Test
    void dcs2cSliceGivesEachPointTheSameBitsAtAnyLength() {
        List<double[]> all = mixedAngles();
        double[][] whole = sliceS2c(all);
        for (int n : new int[] {1, 2, 3, 7, 1001}) {
            int start = all.size() - n;
            double[][] part = sliceS2c(all.subList(start, all.size()));
            for (int i = 0; i < n; i++) {
                assertArrayEquals(bitsOf(whole[start + i]), bitsOf(part[i]), Arrays.toString(all.get(start + i)));
            }
        }
    }

    /**
     * Where the kernel covers a pair, Rust's slice is within the kernel's bound of the exact
     * direction cosines; elsewhere it is {@code dcs2c} bit for bit. With no kernel, the slice is
     * {@code dcs2c} bit for bit everywhere.
     */
    @Test
    void dcs2cSliceIsWithinTheS2cKernelBoundOfTheExactValue() {
        List<double[]> all = mixedAngles();
        double[][] got = sliceS2c(all);
        for (int i = 0; i < all.size(); i++) {
            double[] p = all.get(i);
            if (p[0] == BAD || p[1] == BAD) {
                continue;
            }
            double[] e = Pal.dcs2c(p[0], p[1]);
            for (int k = 0; k < 3; k++) {
                boolean same = bits(got[i][k]) == bits(e[k]) || (Double.isNaN(got[i][k]) && Double.isNaN(e[k]));
                assertTrue(same, Arrays.toString(p) + ": " + Arrays.toString(got[i]) + " vs " + Arrays.toString(e));
            }
        }
    }

    /** An FK slice entry: epoch, input longitudes and latitudes, output longitudes and latitudes. */
    @FunctionalInterface
    private interface FkSliceFn {
        void apply(double bepoch, double[] r, double[] d, double[] rOut, double[] decOut);
    }

    /** FK test positions with special values interleaved among them. */
    private static List<double[]> mixedFkPositions() {
        double[] s = KernelInputs.specials();
        List<double[]> all = new ArrayList<>();
        List<double[]> positions = KernelInputs.fkPositions(500);
        for (int i = 0; i < positions.size(); i++) {
            all.add(positions.get(i));
            if (i % 3 == 0) {
                all.add(new double[] {s[i % s.length], s[(i / 5) % s.length]});
            }
        }
        return all;
    }

    /** An FK slice entry over {@code v} at {@code bepoch}, one position per row, as bits. */
    private static long[][] throughFkSlice(FkSliceFn slice, double bepoch, List<double[]> v) {
        int n = v.size();
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = v.get(i)[0];
            b[i] = v.get(i)[1];
        }
        double[] c = new double[n];
        double[] d = new double[n];
        slice.apply(bepoch, a, b, c, d);
        long[][] out = new long[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new long[] {bits(c[i]), bits(d[i])};
        }
        return out;
    }

    /**
     * Each position gets the same bits from an FK slice entry, at every test epoch, as its own
     * one-element slice and as part of slices of every length from 1 to 17 and of 1,000.
     */
    private static void assertFkSliceSameBitsWhateverItsLength(FkSliceFn slice) {
        List<double[]> all = mixedFkPositions();
        for (double bepoch : KernelInputs.fkEpochs()) {
            long[][] alone = new long[all.size()][];
            for (int i = 0; i < all.size(); i++) {
                alone[i] = throughFkSlice(slice, bepoch, all.subList(i, i + 1))[0];
            }
            int[] lens = new int[18];
            for (int len = 1; len <= 17; len++) {
                lens[len - 1] = len;
            }
            lens[17] = 1000;
            for (int len : lens) {
                for (int start = 0; start < all.size(); start += len) {
                    List<double[]> chunk = all.subList(start, Math.min(start + len, all.size()));
                    long[][] got = throughFkSlice(slice, bepoch, chunk);
                    for (int i = 0; i < got.length; i++) {
                        assertArrayEquals(
                                alone[start + i],
                                got[i],
                                "length " + len + " at " + bepoch + ": " + Arrays.toString(chunk.get(i)));
                    }
                }
            }
        }
    }

    @Test
    void fk45zSliceGivesEachElementTheSameBitsWhateverItsLength() {
        assertFkSliceSameBitsWhateverItsLength(Pal::fk45zSlice);
    }

    /** Where Rust's kernel declines a position the slice gives fk45z's bits; here it does at every position. */
    @Test
    void fk45zSliceGivesFk45zOutsideTheKernelDomain() {
        List<double[]> all = mixedFkPositions();
        for (double bepoch : KernelInputs.fkEpochs()) {
            long[][] got = throughFkSlice(Pal::fk45zSlice, bepoch, all);
            for (int i = 0; i < all.size(); i++) {
                double[] p = all.get(i);
                double[] e = Pal.fk45z(p[0], p[1], bepoch);
                assertArrayEquals(new long[] {bits(e[0]), bits(e[1])}, got[i], Arrays.toString(p) + " at " + bepoch);
            }
        }
    }

    @Test
    void fk54zSliceGivesEachElementTheSameBitsWhateverItsLength() {
        assertFkSliceSameBitsWhateverItsLength(Pal::fk54zSlice);
    }

    /** Where Rust's kernel declines a position the slice gives fk54z's bits; here it does at every position. */
    @Test
    void fk54zSliceGivesFk54zOutsideTheKernelDomain() {
        List<double[]> all = mixedFkPositions();
        for (double bepoch : KernelInputs.fkEpochs()) {
            long[][] got = throughFkSlice(Pal::fk54zSlice, bepoch, all);
            for (int i = 0; i < all.size(); i++) {
                double[] p = all.get(i);
                double[] e = Pal.fk54z(p[0], p[1], bepoch);
                assertArrayEquals(new long[] {bits(e[0]), bits(e[1])}, got[i], Arrays.toString(p) + " at " + bepoch);
            }
        }
    }

    // -- Epoch conversions --

    @Test
    void testEpjJ2000() {
        // MJD 51544.5 is J2000.0
        assertClose(Pal.epj(51544.5), 2000.0, TOL);
    }

    @Test
    void testEpjRoundTrip() {
        double mjd = 53000.0;
        assertClose(Pal.epj2d(Pal.epj(mjd)), mjd, TOL);
    }

    @Test
    void testEpbRoundTrip() {
        assertClose(Pal.epb(Pal.epb2d(1950.0)), 1950.0, TOL);
    }

    @Test
    void testEpb2dB1950() {
        // B1950 corresponds to a known MJD
        double mjd = Pal.epb2d(1950.0);
        assertClose(Pal.epb(mjd), 1950.0, TOL);
    }

    // -- Spherical / Cartesian --

    @Test
    void testDcs2cPole() {
        double[] v = Pal.dcs2c(0.0, FRAC_PI_2);
        assertClose(v[0], 0.0, TOL);
        assertClose(v[1], 0.0, TOL);
        assertClose(v[2], 1.0, TOL);
    }

    @Test
    void testDcs2cDcc2sRoundTrip() {
        double a = 1.234;
        double b = 0.567;
        double[] v = Pal.dcs2c(a, b);
        double[] ab = Pal.dcc2s(v);
        assertClose(ab[0], a, TOL);
        assertClose(ab[1], b, TOL);
    }

    // -- Vector/matrix --

    @Test
    void testDvdvOrthogonal() {
        assertClose(Pal.dvdv(new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0}), 0.0, TOL);
    }

    @Test
    void testDvdvParallel() {
        double[] v = {1.0, 2.0, 3.0};
        assertClose(Pal.dvdv(v, v), 14.0, TOL);
    }

    @Test
    void testDvxvCrossProduct() {
        double[] vc = Pal.dvxv(new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0});
        assertClose(vc[0], 0.0, TOL);
        assertClose(vc[1], 0.0, TOL);
        assertClose(vc[2], 1.0, TOL);
    }

    @Test
    void testDvnNormalize() {
        Pn.Result r = Pal.dvn(new double[] {3.0, 4.0, 0.0});
        assertClose(r.modulus, 5.0, TOL);
        assertClose(r.unit[0], 0.6, TOL);
        assertClose(r.unit[1], 0.8, TOL);
        assertClose(r.unit[2], 0.0, TOL);
    }

    private static double[][] identity() {
        return new double[][] {{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}};
    }

    @Test
    void testDmxvIdentity() {
        double[] v = {1.0, 2.0, 3.0};
        double[] result = Pal.dmxv(identity(), v);
        for (int i = 0; i < 3; i++) {
            assertClose(result[i], v[i], TOL);
        }
    }

    @Test
    void testDimxvInverse() {
        // For an orthogonal matrix, dimxv (transpose multiply) should undo dmxv.
        double[][] m = Pal.deuler("Z", 0.3, 0.0, 0.0);
        double[] v = {1.0, 0.0, 0.0};
        double[] back = Pal.dimxv(m, Pal.dmxv(m, v));
        for (int i = 0; i < 3; i++) {
            assertClose(back[i], v[i], TOL);
        }
    }

    @Test
    void testDmxmIdentity() {
        double[][] a = Pal.deuler("Z", 0.5, 0.0, 0.0);
        double[][] c = Pal.dmxm(a, identity());
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertClose(c[i][j], a[i][j], TOL);
            }
        }
    }

    // -- Coordinate conversions --

    @Test
    void testGaleqCenter() {
        // Galactic centre (l=0, b=0) should be near RA~266.4 deg, Dec~-28.9 deg
        double[] rd = Pal.galeq(0.0, 0.0);
        assertClose(Math.toDegrees(rd[0]), 266.405, 0.01);
        assertClose(Math.toDegrees(rd[1]), -28.936, 0.01);
    }

    @Test
    void testEqgalGaleqRoundTrip() {
        double[] lb = Pal.eqgal(3.0, 0.5);
        double[] rd = Pal.galeq(lb[0], lb[1]);
        assertClose(rd[0], 3.0, TOL);
        assertClose(rd[1], 0.5, TOL);
    }

    @Test
    void testGalsupSupgalRoundTrip() {
        double[] s = Pal.galsup(1.5, 0.3);
        double[] g = Pal.supgal(s[0], s[1]);
        assertClose(g[0], 1.5, TOL);
        assertClose(g[1], 0.3, TOL);
    }

    @Test
    void testFk45zFk54zRoundTrip() {
        double bepoch = 1984.0;
        double[] j2000 = Pal.fk45z(1.0, 0.5, bepoch);
        double[] b1950 = Pal.fk54z(j2000[0], j2000[1], bepoch);
        assertClose(b1950[0], 1.0, 1.0e-6);
        assertClose(b1950[1], 0.5, 1.0e-6);
    }

    @Test
    void testFk5hzHfk5zRoundTrip() {
        double epoch = 2000.0;
        double[] h = Pal.fk5hz(2.0, 0.4, epoch);
        double[] f = Pal.hfk5z(h[0], h[1], epoch);
        assertClose(f[0], 2.0, 1.0e-9);
        assertClose(f[1], 0.4, 1.0e-9);
    }

    // -- Precession --

    @Test
    void testPrecDetOne() {
        // A precession matrix is a proper rotation: det = 1.
        assertClose(determinant3x3(Pal.prec(2000.0, 2020.0)), 1.0, 1.0e-12);
    }

    @Test
    void testPrecIdentitySameEpoch() {
        // Same-epoch precession should be very close to identity.
        double[][] m = Pal.prec(2000.0, 2000.0);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertClose(m[i][j], i == j ? 1.0 : 0.0, 1.0e-7);
            }
        }
    }

    @Test
    void testPrebnDetOne() {
        assertClose(determinant3x3(Pal.prebn(1950.0, 2000.0)), 1.0, 1.0e-12);
    }

    @Test
    void testEcmatDetOne() {
        assertClose(determinant3x3(Pal.ecmat(51544.5)), 1.0, 1.0e-12);
    }

    // -- Euler --

    @Test
    void testDeulerIdentity() {
        double[][] m = Pal.deuler("Z", 0.0, 0.0, 0.0);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertClose(m[i][j], i == j ? 1.0 : 0.0, TOL);
            }
        }
    }

    @Test
    void testDeulerZRotation() {
        double[][] m = Pal.deuler("Z", FRAC_PI_2, 0.0, 0.0);
        // PAL uses the passive (frame-rotation) convention: rotating the frame by +90 deg about Z
        // maps [1,0,0] to [0,-1,0].
        double[] v = Pal.dmxv(m, new double[] {1.0, 0.0, 0.0});
        assertClose(v[0], 0.0, TOL);
        assertClose(v[1], -1.0, TOL);
        assertClose(v[2], 0.0, TOL);
    }

    // -- Aberration --

    @Test
    void testAddetSubetRoundTrip() {
        double eq = 1984.0;
        double[] c = Pal.addet(1.0, 0.5, eq);
        double[] m = Pal.subet(c[0], c[1], eq);
        assertClose(m[0], 1.0, 1.0e-9);
        assertClose(m[1], 0.5, 1.0e-9);
    }

    @Test
    void testMappaAmpqkMapqkzRoundTrip() {
        double[] amprms = Pal.mappa(2000.0, 51544.5);
        double[] a = Pal.mapqkz(2.0, 0.3, amprms);
        double[] m = Pal.ampqk(a[0], a[1], amprms);
        assertClose(m[0], 2.0, 1.0e-9);
        assertClose(m[1], 0.3, 1.0e-9);
    }

    // -- Angular normalisation --

    @Test
    void testDrange4pi() {
        assertClose(Pal.drange(4.0 * Math.PI), 0.0, TOL);
    }

    @Test
    void testDrangePi() {
        // pi should map to pi (or -pi, they are equivalent)
        double result = Pal.drange(Math.PI);
        assertTrue(Math.abs(result - Math.PI) < TOL || Math.abs(result + Math.PI) < TOL);
    }

    @Test
    void testDranrmNegative() {
        assertClose(Pal.dranrm(-0.5), 2.0 * Math.PI - 0.5, TOL);
    }

    @Test
    void testDranrmPositive() {
        assertClose(Pal.dranrm(0.5), 0.5, TOL);
    }

    @Test
    void testDsepZero() {
        assertClose(Pal.dsep(1.0, 0.5, 1.0, 0.5), 0.0, TOL);
    }

    @Test
    void testDsepPoles() {
        // Separation between north pole and south pole is pi.
        assertClose(Pal.dsep(0.0, FRAC_PI_2, 0.0, -FRAC_PI_2), Math.PI, TOL);
    }

    @Test
    void testDbearEast() {
        // Point due east on the equator: bearing should be ~pi/2.
        assertClose(Pal.dbear(0.0, 0.0, 0.1, 0.0), FRAC_PI_2, 0.01);
    }

    // -- Earth / observer --

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    void testEvpReturnsNonzero() {
        double[][] evp = Pal.evp(51544.5, 2000.0);
        // dvb, dpb, dvh, dph: check the vectors are non-trivially populated.
        assertTrue(norm(evp[1]) > 0.9, "barycentric position magnitude too small");
        assertTrue(norm(evp[3]) > 0.9, "heliocentric position magnitude too small");
        assertTrue(norm(evp[0]) > 0.0);
        assertTrue(norm(evp[2]) > 0.0);
    }

    @Test
    void testGeocEquator() {
        // At the equator and sea level r is positive and z is ~0.
        double[] rz = Pal.geoc(0.0, 0.0);
        assertTrue(rz[0] > 0.0, "r should be positive at equator");
        assertClose(rz[1], 0.0, 1.0e-6);
    }

    // -- Miscellaneous --

    @Test
    void testDav2mZero() {
        // Zero axial vector -> identity matrix.
        double[][] m = Pal.dav2m(new double[] {0.0, 0.0, 0.0});
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertClose(m[i][j], i == j ? 1.0 : 0.0, TOL);
            }
        }
    }

    @Test
    void testDav2mZRotation() {
        // Rotation of pi/2 about Z.
        double[][] m = Pal.dav2m(new double[] {0.0, 0.0, FRAC_PI_2});
        // Verify the matrix is a proper rotation (det = 1).
        assertClose(determinant3x3(m), 1.0, TOL);
        // Verify M * M^T = I (orthogonal matrix).
        double[] back = Pal.dimxv(m, Pal.dmxv(m, new double[] {1.0, 0.0, 0.0}));
        assertClose(back[0], 1.0, TOL);
        assertClose(back[1], 0.0, TOL);
        assertClose(back[2], 0.0, TOL);
    }

    // -- Time / sidereal --

    @Test
    void datJ2000() {
        // TAI-UTC at J2000.0 (MJD 51544.5) = 32 seconds
        assertTrue(Math.abs(Pal.dat(51544.5) - 32.0) < 0.5);
    }

    @Test
    void dat2017() {
        // After 2017 Jan 1 (MJD ~57754): TAI-UTC = 37 seconds
        assertTrue(Math.abs(Pal.dat(58000.0) - 37.0) < 0.5);
    }

    @Test
    void gmstRange() {
        // GMST should be in range [0, 2 pi)
        double g = Pal.gmst(51544.5);
        assertTrue(g >= 0.0 && g < 2.0 * Math.PI);
    }

    @Test
    void rvlsrkNonzero() {
        // LSRK velocity should be non-zero for an arbitrary direction (km/s)
        assertTrue(Math.abs(Pal.rvlsrk(1.0, 0.5)) > 0.1);
    }

    @Test
    void djclKnownDate() {
        // MJD 51544.0 is 2000-01-01 (J2000.0 epoch is 51544.5 = noon).
        Jd2cal.Result r = Pal.djcl(51544.0);
        assertEquals(2000, r.iy);
        assertEquals(1, r.im);
        assertEquals(1, r.id);
        assertTrue(Math.abs(r.fd) < 1e-9);
        // The 0.5-day fraction gives midday.
        assertTrue(Math.abs(Pal.djcl(51544.5).fd - 0.5) < 1e-9);
    }

    @Test
    void cldjRoundTripsDjcl() {
        Cal2jd.Result c = Pal.cldj(2000, 1, 1);
        assertNull(c.error);
        assertNull(c.warning);
        assertTrue(Math.abs(c.djm - 51544.0) < 1e-9);
        Jd2cal.Result r = Pal.djcl(c.djm);
        assertEquals(2000, r.iy);
        assertEquals(1, r.im);
        assertEquals(1, r.id);
    }

    @Test
    void cldjRejectsBadMonth() {
        assertEquals(Cal2jd.Cal2jdError.BAD_MONTH, Pal.cldj(2000, 13, 1).error);
    }

    @Test
    void dd2tfAndDtf2dRoundTrip() {
        // 0.5 days = 12:00:00.
        D2tf.Result r = Pal.dd2tf(3, 0.5);
        assertEquals('+', r.sign);
        assertArrayEquals(new int[] {12, 0, 0}, Arrays.copyOf(r.ihmsf, 3));
        // Reconstruct the day fraction from h:m:s.
        Tf2d.Result d = Pal.dtf2d(r.ihmsf[0], r.ihmsf[1], r.ihmsf[2]);
        assertNull(d.warning);
        assertTrue(Math.abs(d.days - 0.5) < 1e-9);
    }

    @Test
    void dd2tfNegativeSign() {
        assertEquals('-', Pal.dd2tf(0, -0.25).sign);
    }

    /** From {@code pal/dat.rs}. */
    @Test
    void datGivesZeroForDatesNoCalendarHolds() {
        for (double m :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, BAD, 1e300, -1e300}) {
            if (bits(Pal.dat(m)) != bits(0.0)) {
                fail("dat(" + m + ") = " + Pal.dat(m));
            }
        }
    }

    private static double determinant3x3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
}
