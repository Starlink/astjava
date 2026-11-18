package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_rebin_oracle.rs}: rebinning against C's {@code
 * astRebin<X>}, from {@code oracle/rebin.json}, which C's {@code rebin_probe.c} made. Each case
 * gives the status, an FNV-1a hash of the bit patterns of every output value and variance (as
 * doubles) and every 97th output value.
 *
 * <p>{@code rebinningMatchesC} runs every case of the types jniast has (D, F and I); the cases of
 * C's {@code B} and {@code UB} types have no jniast method. A case's flags are passed as C's, so
 * that the cases with variance arrays but without {@code AST__USEVAR}, which jniast's {@code
 * rebin} never makes, run too. jniast has no Spreader factories for {@code AST__SOMB} and {@code
 * AST__SOMBCOS}, so their Spreaders are made directly. {@code errorsMatchC} runs the probe's ten
 * calls with invalid arguments (and one valid one) and checks the status and the first two output
 * values after each, which show that C zeroes only the first output value after an error in the
 * axis counts, bounds, region or tolerance. {@code too_few_params_is_an_error} is listed in {@code
 * deferred/rust-tests.txt}: jniast's Spreader factories fix the parameters.
 */
class RebinOracleTest {

    /** The probe's Mapping for a case's Mapping name. */
    static Mapping mapping(String name) {
        switch (name) {
            case "unit2":
                return new UnitMap(2);
            case "shift_half":
                return new ShiftMap(new double[] {0.5, -0.25});
            case "zoom2":
                return new ZoomMap(2, 1.7);
            case "shrink2":
                return new ZoomMap(2, 0.45);
            case "perm":
                return new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
            case "nonlin2":
                return new MathMap(
                        2, 2, new String[] {"p=a+0.02*a*b-0.3", "q=b-0.015*a*a+0.2"}, new String[] {"a=p", "b=q"});
            case "cubic1":
                return new MathMap(1, 1, new String[] {"p=0.001*a*a*a+0.5*a"}, new String[] {"a=p"});
            case "win3":
                return new WinMap(
                        3,
                        new double[] {0.0, 0.0, 0.0},
                        new double[] {1.0, 1.0, 1.0},
                        new double[] {0.3, -0.2, 0.1},
                        new double[] {1.1, 0.7, 1.4});
            case "nonlin3":
                return new MathMap(3, 3, new String[] {"p=a+0.05*b*c", "q=b-0.03*a*a", "r=0.9*c+0.1*a"}, new String[] {
                    "a=p", "b=q", "c=r"
                });
            case "noforward":
                return new MathMap(2, 2, new String[] {"p", "q"}, new String[] {"a=p", "b=q"});
            default:
                throw new IllegalArgumentException("no Mapping " + name);
        }
    }

    /**
     * The Spreader jniast makes for the probe's scheme and parameters {@code p0} and {@code p1}.
     * jniast's factories take the number of neighbouring pixels as an integer, which each case's
     * {@code p0} is.
     */
    static Mapping.Spreader spreader(String scheme, double p0, double p1) {
        int npix = (int) p0;
        assertEquals(p0, npix, "params[0] of " + scheme);
        switch (scheme) {
            case "nearest":
                return Mapping.NEAREST_SPREADER;
            case "linear":
                return Mapping.LINEAR_SPREADER;
            case "sinc":
                return Mapping.Spreader.sinc(npix);
            case "sincsinc":
                return Mapping.Spreader.sincSinc(npix, p1);
            case "sinccos":
                return Mapping.Spreader.sincCos(npix, p1);
            case "sincgauss":
                return Mapping.Spreader.sincGauss(npix, p1);
            case "gauss":
                return Mapping.Spreader.gauss(npix, p1);
            case "somb":
                return new Mapping.Spreader(Mapping.AST__SOMB, p0, p1);
            case "sombcos":
                return new Mapping.Spreader(Mapping.AST__SOMBCOS, p0, p1);
            default:
                throw new IllegalArgumentException("no scheme " + scheme);
        }
    }

    private static int[] ints(JsonNode n) {
        int[] v = new int[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = n.get(i).asInt();
        }
        return v;
    }

    static int npix(int[] l, int[] u) {
        int n = 1;
        for (int i = 0; i < l.length; i++) {
            n *= u[i] - l[i] + 1;
        }
        return n;
    }

    /** A case's arguments, as the probe sets them up. */
    static final class Case {
        final String name;
        final String type;
        final Mapping map;
        final int[] lbndIn;
        final int[] ubndIn;
        final int[] lbndOut;
        final int[] ubndOut;
        final int[] lbnd;
        final int[] ubnd;
        final Mapping.Spreader spread;
        final boolean useBad;
        final boolean useVar;
        final boolean varArrays;
        final double tol;
        final int maxpix;
        final double wlim;

        Case(JsonNode c) {
            name = c.get(0).asText();
            map = mapping(c.get(1).asText());
            type = c.get(2).asText();
            lbndIn = ints(c.get(4));
            ubndIn = ints(c.get(5));
            lbndOut = ints(c.get(6));
            ubndOut = ints(c.get(7));
            lbnd = ints(c.get(8));
            ubnd = ints(c.get(9));
            spread = spreader(
                    c.get(10).asText(),
                    ProbeData.toDouble(c.get(14).get(0)),
                    ProbeData.toDouble(c.get(14).get(1)));
            useBad = c.get(11).asBoolean();
            useVar = c.get(12).asBoolean();
            varArrays = c.get(13).asBoolean();
            tol = ProbeData.toDouble(c.get(15));
            maxpix = c.get(16).asInt();
            wlim = ProbeData.toDouble(c.get(17));
        }

        /** The flags C is passed. */
        int flags() {
            return (useBad ? ResampleFlags.AST__USEBAD : 0) | (useVar ? Rebin.AST__USEVAR : 0);
        }

        /**
         * The probe's input data and variances as doubles in the case's type: {@code ((7 k) % 97)
         * * 0.37 - 10} and {@code ((3 k) % 11) * 0.5 + 0.25} for a floating type, {@code (7 k) %
         * 23 - 11} and {@code (3 k) % 11} for a signed integer one, with the bad value 120 at
         * {@code k % 17 == 3} (data) and {@code k % 19 == 5} (variances) when bad pixels are used.
         */
        double[][] input() {
            int n = npix(lbndIn, ubndIn);
            double[] data = new double[n];
            double[] var = new double[n];
            boolean floating = "D".equals(type) || "F".equals(type);
            for (int k = 0; k < n; k++) {
                if (floating) {
                    data[k] = (7 * k) % 97 * 0.37 - 10.0;
                    var[k] = (3 * k) % 11 * 0.5 + 0.25;
                } else {
                    data[k] = (7 * k) % 23 - 11;
                    var[k] = (3 * k) % 11;
                }
                if (useBad && k % 17 == 3) {
                    data[k] = 120.0;
                }
                if (useBad && k % 19 == 5) {
                    var[k] = 120.0;
                }
            }
            return new double[][] {data, var};
        }
    }

    /** The result of a case: status, and the output values and variances as doubles. */
    static final class Result {
        int status;
        double[] out;
        double[] outVar;
    }

    /** The case's data of type {@code type} (D, F or I) from its values as doubles. */
    private static PixelArray pixels(String type, double[] v) {
        switch (type) {
            case "D":
                return PixelArray.of(v.clone(), 120.0);
            case "F": {
                float[] a = new float[v.length];
                for (int k = 0; k < v.length; k++) {
                    a[k] = (float) v[k];
                }
                return PixelArray.of(a, 120.0f);
            }
            case "I": {
                int[] a = new int[v.length];
                for (int k = 0; k < v.length; k++) {
                    a[k] = (int) v[k];
                }
                return PixelArray.of(a, 120);
            }
            default:
                throw new IllegalArgumentException("no type " + type);
        }
    }

    private static double[] doubles(PixelArray a) {
        double[] v = new double[a.length()];
        for (int k = 0; k < v.length; k++) {
            v[k] = a.get(k);
        }
        return v;
    }

    /** Runs a case through {@code Rebin.rebin} with C's flags. */
    static Result run(Case c) {
        double[][] input = c.input();
        int nout = npix(c.lbndOut, c.ubndOut);
        double[] start = new double[nout];
        Arrays.fill(start, 77.0);
        PixelArray in = pixels(c.type, input[0]);
        PixelArray inVar = pixels(c.type, input[1]);
        PixelArray out = pixels(c.type, start);
        PixelArray outVar = pixels(c.type, start);
        Result r = new Result();
        try {
            Rebin.rebin(
                    c.map,
                    c.wlim,
                    c.lbndIn.length,
                    c.lbndIn,
                    c.ubndIn,
                    in,
                    c.varArrays ? inVar : null,
                    c.spread,
                    c.flags(),
                    c.tol,
                    c.maxpix,
                    c.lbndOut.length,
                    c.lbndOut,
                    c.ubndOut,
                    c.lbnd,
                    c.ubnd,
                    out,
                    c.varArrays ? outVar : null,
                    "astRebin" + c.type);
        } catch (AstException e) {
            r.status = e.getStatus();
        }
        r.out = doubles(out);
        r.outVar = doubles(outVar);
        return r;
    }

    /** FNV-1a over the bit patterns of every output value, and variance when the arrays are given. */
    static long hash(Result r, boolean varArrays) {
        long h = 0xcbf29ce484222325L;
        for (int k = 0; k < r.out.length; k++) {
            h = (h ^ Double.doubleToRawLongBits(r.out[k])) * 0x100000001b3L;
            if (varArrays) {
                h = (h ^ Double.doubleToRawLongBits(r.outVar[k])) * 0x100000001b3L;
            }
        }
        return h;
    }

    /**
     * Every output value and variance of the oracle's cases whose kernels call sin, cos or exp,
     * keyed by case name and type, from {@code probes/rebin_kernel_probe.out}.
     */
    private static final Map<String, String> KERNEL_VALUES = ProbeOutput.load("rebin_kernel_probe");

    /**
     * The values after {@code label} in the text of a probe case of {@code type}, up to the next
     * label or the end. The probe writes a float with nine significant digits, which reads back to
     * the float, not to the double it equals.
     */
    private static double[] probeValues(String text, String label, String type) {
        String[] lines = text.split("\n", -1);
        List<Double> v = new ArrayList<>();
        boolean in = false;
        for (String line : lines) {
            if (line.equals(label)) {
                in = true;
            } else if (in && !line.isEmpty() && Character.isLetter(line.charAt(0))) {
                break;
            } else if (in && !line.isEmpty()) {
                for (String t : line.trim().split(" +", -1)) {
                    v.add("F".equals(type) ? (double) Float.parseFloat(t) : Double.parseDouble(t));
                }
            }
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = v.get(i);
        }
        return a;
    }

    /** A block of input pixels as the rebinning spreads it: their offsets and output positions. */
    private static final class Block {
        final int npoint;
        final int[] offset;
        final double[][] coords;

        Block(int npoint, int[] offset, double[][] coords) {
            this.npoint = npoint;
            this.offset = offset;
            this.coords = coords;
        }
    }

    /** The blocks the case's rebinning spreads, in order, with their output positions. */
    private static List<Block> blocks(Case cs) {
        double[][] input = cs.input();
        int nout = npix(cs.lbndOut, cs.ubndOut);
        List<Block> blocks = new ArrayList<>();
        Rebin.rebin(
                cs.map,
                cs.wlim,
                cs.lbndIn.length,
                cs.lbndIn,
                cs.ubndIn,
                pixels(cs.type, input[0]),
                cs.varArrays ? pixels(cs.type, input[1]) : null,
                cs.spread,
                cs.flags(),
                cs.tol,
                cs.maxpix,
                cs.lbndOut.length,
                cs.lbndOut,
                cs.ubndOut,
                cs.lbnd,
                cs.ubnd,
                pixels(cs.type, new double[nout]),
                cs.varArrays ? pixels(cs.type, new double[nout]) : null,
                "astRebin" + cs.type,
                (npoint, offset, coords) -> blocks.add(new Block(npoint, offset, coords)));
        return blocks;
    }

    private static final double U = 0x1p-53;
    private static final double UF = 0x1p-24;

    /**
     * The largest relative difference between Java's and C's values of the kernel of {@code
     * setup} for the scheme {@code scheme}, worked out in {@link #bounds}.
     */
    private static double kernelDifference(int scheme, ResampleKernels.Setup setup) {
        switch (scheme) {
            case Mapping.AST__SINC:
                return 6 * U;
            case Mapping.AST__SINCCOS:
                return 12 * U;
            case Mapping.AST__SINCSINC:
                return 14 * U;
            case Mapping.AST__SOMBCOS:
                assertTrue(Math.PI * setup.neighb < 8.0, "J1's rational approximation");
                return 6 * U;
            case Mapping.AST__GAUSS:
                return gaussDifference(setup);
            case Mapping.AST__SINCGAUSS:
                return 6 * U + gaussDifference(setup) + 2 * U;
            default:
                throw new IllegalArgumentException("no kernel calling sin, cos or exp: " + scheme);
        }
    }

    /**
     * The relative difference of {@code exp(-k x x)} for the offsets {@code x}, at most {@code
     * neighb}, of the Gaussian kernel of {@code setup}; also checks that the default reach, where
     * it applies, is far from an integer.
     */
    private static double gaussDifference(ResampleKernels.Setup setup) {
        double k = setup.params[0];
        double reach = Math.sqrt(-Math.log(0.01) / k);
        assertTrue(Math.abs(reach - Math.rint(reach)) > 1.0e-6, "default reach " + reach);
        double a = k * setup.neighb * setup.neighb;
        return 10 * U * a + 4 * U;
    }

    /** One axis of a point's kernel box: its pixels, kernel values (reflected) and their sums. */
    private static final class Axis {
        long lo;
        int jlo;
        int jhi;
        /** The kernel values landing in each pixel of the box. */
        double[] f;
        /** The sum of the absolute kernel values landing in each pixel of the box. */
        double[] fa;
        /** The sum of the kernel values. */
        double s;
        /** The sum of the absolute kernel values. */
        double a;
    }

    /**
     * The kernel box of an input pixel at {@code x} along an axis of the output grid from {@code
     * lb} to {@code ub}, as {@code SpreadKernel1<X>} forms it, or null if the pixel is not spread.
     */
    private static Axis axis(double x, long lb, long ub, ResampleKernels.Setup setup) {
        long icentre = (long) Math.floor(x + 0.5);
        if (icentre < lb || icentre > ub || x == AstObject.AST__BAD) {
            return null;
        }
        int nb2 = 2 * setup.neighb;
        long ix = (long) Math.floor(x) - setup.neighb + 1;
        Axis ax = new Axis();
        ax.lo = Math.max(ix, lb);
        long hi = Math.min(ix + nb2 - 1, ub);
        if (ax.lo > hi) {
            return null;
        }
        ax.jlo = (int) (ax.lo - ix);
        ax.jhi = (int) (hi - ix);
        ax.f = new double[nb2];
        ax.fa = new double[nb2];
        int nw = 2 * (ax.jhi - ax.jlo + 1);
        boolean edge = ax.jhi - ax.jlo + 1 < nb2;
        double xx = ix - x;
        for (int j = 0; j < nb2; j++) {
            double kv = setup.kernel.value(xx, setup.params);
            int jj = j;
            if (edge) {
                jj = (j - ax.jlo) % nw + ax.jlo;
                if (jj < ax.jlo) {
                    jj += nw;
                }
                if (jj > ax.jhi) {
                    jj = 2 * ax.jhi - jj + 1;
                }
            }
            ax.f[jj] += kv;
            ax.fa[jj] += Math.abs(kv);
            ax.s += kv;
            ax.a += Math.abs(kv);
            xx += 1.0;
        }
        return ax;
    }

    /** Whether {@code t} lies within {@code d} of a half-integer, where rounding to an integer may go either way. */
    private static boolean nearHalf(double t, double d) {
        return Math.abs(Math.abs(t - Math.floor(t)) - 0.5) <= d;
    }

    /**
     * The largest differences from C's outputs allowed for the kernel case {@code cs}, for each
     * output value ([0]) and variance ([1]), worked out pixel by pixel from the contributions C's
     * {@code SpreadKernel1<X>} adds to it; zero where the output must equal C's exactly. The output
     * positions are those the case's rebinning spreads (the Mapping, fits and blocks use only
     * arithmetic, so the positions are C's on every platform); the kernel boxes, reflections, bad
     * pixels and weights are {@code SpreadKernel1<X>}'s, computed here in double.
     *
     * <p>The bound. Let u = 2^-53, and let e be the largest relative difference between Java's and
     * C's value of the kernel, worked out as for resampling (in {@code ResampleOracleTest}): sinc
     * 6u, SincCos 12u, SincSinc 14u, SombCos 6u (J1's rational approximation, plain arithmetic, for
     * offsets up to 2 pixels), and for the Gaussians, through a = k o o at most k neighb^2, exp(-a)
     * by 10u a + 4u, so Gauss 10u a + 4u and SincGauss 6u + 10u a + 6u. Positions, and so which
     * pixels each input reaches and with which reflections, are the same; only the kernel values
     * differ, and each operation on inputs differing by d relative differs by at most d + 2u
     * relative. With nb2 = 2 neighb values per axis, a pixel of the box whose kernel values (one
     * or, where reflected, several summed) along axis i are f_i, with absolute values summing to
     * fa_i, differs along that axis by at most (e + 2u nb2) fa_i, so the product F of the n axes'
     * values by E_F Fa, E_F = n (e + 2u (nb2 + 1)), Fa the product of the fa_i; the sum S of the
     * whole box's products, which is the product of the axes' sums s_i, differs by at most E_S A,
     * E_S = E_F + 2u nb2^n, A the product of the axes' sums of absolute values a_i. The weight w =
     * F / S then differs by dw = E_F Fa / |S| + E_S |F| A / S^2 + 2u (n + 2) |w|. A term w v, for
     * the input value v, differs by |v| dw + 2u |w v|, and 2s |w v| more where it is rounded to
     * float (s = 2^-24); an output value, the sum of its n_o terms, by the sum of the terms'
     * differences plus 2 s' n_o P, P the sum of the terms' absolute values and s' the unit
     * roundoff of the type's sum (u for D, s for F). A variance term var w^2 differs by |var| 2
     * |w| dw + 4u |var w^2|, and its sum likewise. For {@code int} data each term is rounded to an
     * integer and the sum is exact, so an output may differ by one for each term lying within its
     * difference of a half-integer. The bound allowed is twice this, for the second-order terms.
     * With a weight limit, the sum of an output pixel's weights differs by the sum of their dw plus
     * 2u n_o times the sum of their absolute values; that sum must lie further than twice this from
     * the limit, so that the pixel is bad in both or in neither, and a bad output must equal C's.
     * An output pixel no input reaches must equal C's.
     *
     * @throws AssertionError if a sum of weights lies within its bound of the weight limit
     */
    private static double[][] bounds(Case cs) {
        double[][] input = cs.input();
        int nout = npix(cs.lbndOut, cs.ubndOut);
        int ndim = cs.lbndOut.length;
        ResampleKernels.Setup setup = ResampleKernels.setup(cs.spread.scheme, cs.spread.params);
        int nb2 = 2 * setup.neighb;
        double e = kernelDifference(cs.spread.scheme, setup);
        double ef = ndim * (e + 2 * U * (nb2 + 1));
        double es = ef + 2 * U * Math.pow(nb2, ndim);
        boolean isFloat = "F".equals(cs.type);
        boolean isInt = "I".equals(cs.type);
        double sumUnit = isFloat ? UF : U;
        boolean usebad = cs.useBad;
        boolean usevar = cs.useVar && cs.varArrays;
        long[] stride = new long[ndim];
        long st = 1;
        for (int i = 0; i < ndim; i++) {
            stride[i] = st;
            st *= cs.ubndOut[i] - cs.lbndOut[i] + 1;
        }

        double[] dt = new double[nout];
        double[] p = new double[nout];
        double[] dtv = new double[nout];
        double[] pv = new double[nout];
        double[] flips = new double[nout];
        double[] flipsVar = new double[nout];
        double[] dw = new double[nout];
        double[] w = new double[nout];
        double[] wa = new double[nout];
        int[] n = new int[nout];
        for (Block b : blocks(cs)) {
            for (int pt = 0; pt < b.npoint; pt++) {
                int offIn = b.offset[pt];
                double v = pixels(cs.type, new double[] {input[0][offIn]}).get(0);
                double var = pixels(cs.type, new double[] {input[1][offIn]}).get(0);
                if (usebad && (v == 120.0 || (usevar && var == 120.0))) {
                    continue;
                }
                Axis[] axes = new Axis[ndim];
                boolean spreads = true;
                for (int i = 0; i < ndim && spreads; i++) {
                    axes[i] = axis(b.coords[i][pt], cs.lbndOut[i], cs.ubndOut[i], setup);
                    spreads = axes[i] != null;
                }
                if (!spreads) {
                    continue;
                }
                double s = 1.0;
                double a = 1.0;
                for (Axis ax : axes) {
                    s *= ax.s;
                    a *= ax.a;
                }
                assertNotEquals(0.0, s, cs.name + ": a kernel box sums to zero");
                // Each pixel of the box, first axis fastest.
                int[] j = new int[ndim];
                for (int i = 0; i < ndim; i++) {
                    j[i] = axes[i].jlo;
                }
                boolean done = false;
                while (!done) {
                    long off = 0;
                    double f = 1.0;
                    double fa = 1.0;
                    for (int i = 0; i < ndim; i++) {
                        off += stride[i] * (axes[i].lo + j[i] - axes[i].jlo - cs.lbndOut[i]);
                        f *= axes[i].f[j[i]];
                        fa *= axes[i].fa[j[i]];
                    }
                    int o = (int) off;
                    double wt = f / s;
                    double dwt =
                            ef * fa / Math.abs(s) + es * Math.abs(f) * a / (s * s) + 2 * U * (ndim + 2) * Math.abs(wt);
                    double t = wt * v;
                    double dterm = Math.abs(v) * dwt + 2 * U * Math.abs(t) + (isFloat ? 2 * UF * Math.abs(t) : 0.0);
                    dt[o] += dterm;
                    p[o] += Math.abs(t);
                    if (isInt && nearHalf(t, 2 * dterm + 4 * U * Math.abs(t))) {
                        flips[o]++;
                    }
                    if (usevar) {
                        double tv = var * wt * wt;
                        double dtermVar = Math.abs(var) * 2 * Math.abs(wt) * dwt + 4 * U * Math.abs(tv);
                        dtv[o] += dtermVar;
                        pv[o] += Math.abs(tv);
                        if (isInt && nearHalf(tv, 2 * dtermVar + 4 * U * Math.abs(tv))) {
                            flipsVar[o]++;
                        }
                    }
                    dw[o] += dwt;
                    w[o] += wt;
                    wa[o] += Math.abs(wt);
                    n[o]++;
                    done = true;
                    for (int i = 0; i < ndim; i++) {
                        if (j[i] < axes[i].jhi) {
                            j[i]++;
                            done = false;
                            break;
                        }
                        j[i] = axes[i].jlo;
                    }
                }
            }
        }

        double[][] bound = new double[2][nout];
        for (int o = 0; o < nout; o++) {
            if (cs.wlim > 0.0) {
                double dwork = 2 * (dw[o] + 2 * U * n[o] * wa[o]);
                assertTrue(
                        Math.abs(Math.abs(w[o]) - cs.wlim) > dwork,
                        cs.name + " " + cs.type + " " + o + ": weight " + w[o] + " within " + dwork + " of wlim");
                if (Math.abs(w[o]) < cs.wlim) {
                    continue;
                }
            }
            if (isInt) {
                bound[0][o] = flips[o];
                bound[1][o] = flipsVar[o];
            } else {
                bound[0][o] = 2 * (dt[o] + 2 * sumUnit * n[o] * p[o]);
                bound[1][o] = 2 * (dtv[o] + 2 * sumUnit * n[o] * pv[o]);
            }
        }
        return bound;
    }

    /**
     * How the outputs of {@code r} differ from the probe's by more than {@link #bounds} allows, or
     * null if none does.
     */
    private static String valueDifference(Result r, Case cs, String probe) {
        double[][] bound = bounds(cs);
        double[][] got = cs.varArrays ? new double[][] {r.out, r.outVar} : new double[][] {r.out};
        double[][] want = cs.varArrays
                ? new double[][] {probeValues(probe, "out", cs.type), probeValues(probe, "var", cs.type)}
                : new double[][] {probeValues(probe, "out", cs.type)};
        for (int a = 0; a < got.length; a++) {
            if (got[a].length != want[a].length) {
                return "probe has " + want[a].length + " values, not " + got[a].length;
            }
            for (int k = 0; k < got[a].length; k++) {
                double g = got[a][k];
                double wv = want[a][k];
                if (Double.isNaN(g - wv) || Math.abs(g - wv) > bound[a][k]) {
                    return (a == 0 ? "value " : "variance ") + k + " is " + g + ", C's " + wv + ", allowed "
                            + bound[a][k];
                }
            }
        }
        return null;
    }

    /**
     * Every case of the kernel probe is within {@link #bounds} of C's values, whether or not its
     * hash matches, so that the bound is checked on every platform.
     */
    @Test
    void kernelCasesAreWithinTheirBounds() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("rebin").get("CASES")) {
            String probe = KERNEL_VALUES.get(c.get(0).asText() + " " + c.get(2).asText());
            if (probe == null) {
                continue;
            }
            Case cs = new Case(c);
            Result r = run(cs);
            assertTrue(probe.startsWith("status " + r.status + "\n"), cs.name + " " + cs.type + " status");
            String diff = valueDifference(r, cs, probe);
            if (diff != null) {
                failures.add(cs.name + " " + cs.type + ": " + diff);
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(33, ran, "cases run");
    }

    /** The kernel probe's values reproduce the oracle's hash and status of each of its cases. */
    @Test
    void kernelProbeMatchesTheOracle() {
        int matched = 0;
        for (JsonNode c : ProbeData.load("rebin").get("CASES")) {
            String key = c.get(0).asText() + " " + c.get(2).asText();
            String probe = KERNEL_VALUES.get(key);
            if (probe == null) {
                continue;
            }
            Result r = new Result();
            r.out = probeValues(probe, "out", c.get(2).asText());
            boolean varArrays = c.get(13).asBoolean();
            r.outVar = varArrays ? probeValues(probe, "var", c.get(2).asText()) : null;
            assertEquals(c.get(19).bigIntegerValue().longValue(), hash(r, varArrays), key);
            assertTrue(probe.startsWith("status " + c.get(18).asInt() + "\n"), key);
            matched++;
        }
        assertEquals(KERNEL_VALUES.size(), matched, "probe cases in the oracle");
        assertEquals(33, matched, "probe cases");
    }

    /** How the case {@code c} differs from C, or null if it matches. */
    private static String mismatch(JsonNode c) {
        Case cs = new Case(c);
        Result r = run(cs);
        int wantStatus = c.get(18).asInt();
        long wantHash = c.get(19).bigIntegerValue().longValue();
        JsonNode sample = c.get(20);
        List<Double> got = new ArrayList<>();
        for (int k = 0; k < r.out.length; k += 97) {
            got.add(r.out[k]);
        }
        boolean sameSamples = got.size() == sample.size();
        for (int k = 0; sameSamples && k < got.size(); k++) {
            double w = ProbeData.toDouble(sample.get(k));
            sameSamples = Double.doubleToLongBits(got.get(k)) == Double.doubleToLongBits(w);
        }
        long h = hash(r, cs.varArrays);
        String values = "";
        if (r.status == wantStatus && (h != wantHash || !sameSamples)) {
            String probe = KERNEL_VALUES.get(cs.name + " " + cs.type);
            if (probe != null) {
                String diff = valueDifference(r, cs, probe);
                if (diff == null) {
                    return null;
                }
                values = "; " + diff;
            }
        }
        if (r.status != wantStatus || h != wantHash || !sameSamples) {
            return cs.name + " " + cs.type + ": status " + r.status + " want " + wantStatus + "; hash "
                    + (h == wantHash ? "same" : "differs") + "; samples " + got + " want " + sample + values;
        }
        return null;
    }

    /** Whether the case {@code c} is of a type jniast has a method for. */
    private static boolean isJniastType(JsonNode c) {
        switch (c.get(2).asText()) {
            case "D":
            case "F":
            case "I":
                return true;
            default:
                return false;
        }
    }

    @Test
    void rebinningMatchesC() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("rebin").get("CASES")) {
            if (!isJniastType(c)) {
                continue;
            }
            ran++;
            String m = mismatch(c);
            if (m != null) {
                failures.add(m);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " cases differ:\n" + String.join("\n", failures));
        assertEquals(81, ran, "cases run");
    }

    /**
     * Rebins 0 to 99 linearly through {@code map} with the given bounds and tolerance, into an
     * output of 7s, returning the status and the first two output values. Each array is whole
     * whatever the bounds say, as C's are: the output has a value per pixel of its grid, or 100
     * where the grid's bounds are inconsistent, and the input the first 100 at most of 0 to 99.
     */
    private static double[] errorCase(
            String map, int[] lbndIn, int[] ubndIn, int[] lbndOut, int[] ubndOut, int[] lbnd, int[] ubnd, double tol) {
        int nout = Math.max(0, npix(lbndOut, ubndOut));
        double[] out = new double[nout == 0 ? 100 : nout];
        Arrays.fill(out, 7.0);
        int nin = Math.max(0, npix(lbndIn, ubndIn));
        double[] in = new double[nin == 0 ? 100 : Math.min(nin, 100)];
        for (int k = 0; k < in.length; k++) {
            in[k] = k;
        }
        int status = 0;
        try {
            mapping(map)
                    .rebinD(
                            0.0,
                            lbndIn.length,
                            lbndIn,
                            ubndIn,
                            in,
                            null,
                            Mapping.LINEAR_SPREADER,
                            false,
                            tol,
                            50,
                            -1.0,
                            lbndOut.length,
                            lbndOut,
                            ubndOut,
                            lbnd,
                            ubnd,
                            out,
                            null);
        } catch (AstException e) {
            status = e.getStatus();
        }
        return new double[] {status, out[0], out[1]};
    }

    @Test
    void errorsMatchC() {
        int[] l1 = {1};
        int[] u1 = {10};
        int[] l2 = {1, 1};
        int[] u2 = {10, 10};
        int[] bad = {11, 1};
        int[] l3 = {1, 1, 1};
        int[] u3 = {10, 10, 10};
        double[][] got = {
            errorCase("unit2", l1, u1, l2, u2, l1, u1, 0.0),
            errorCase("unit2", l2, u2, l3, u3, l2, u2, 0.0),
            errorCase("unit2", bad, u2, l2, u2, bad, u2, 0.0),
            errorCase("unit2", l2, u2, bad, u2, l2, u2, 0.0),
            errorCase("unit2", l2, u2, l2, u2, new int[] {0, 1}, u2, 0.0),
            errorCase("unit2", l2, u2, l2, u2, l2, new int[] {10, 11}, 0.0),
            errorCase("unit2", l2, u2, l2, u2, new int[] {5, 1}, new int[] {4, 10}, 0.0),
            errorCase("unit2", l2, u2, l2, u2, l2, u2, -1.0),
            errorCase("noforward", l2, u2, l2, u2, l2, u2, 0.0),
            errorCase("unit2", l2, u2, l2, u2, l2, u2, 0.0),
        };
        JsonNode want = ProbeData.load("rebin").get("ERRORS");
        assertEquals(want.size(), got.length, "cases");
        for (int i = 0; i < got.length; i++) {
            JsonNode w = want.get(i);
            assertEquals(w.get(0).asInt(), (int) got[i][0], "status of case " + (i + 1));
            assertEquals(ProbeData.toDouble(w.get(1).get(0)), got[i][1], "first output value of case " + (i + 1));
            assertEquals(ProbeData.toDouble(w.get(1).get(1)), got[i][2], "second output value of case " + (i + 1));
        }
    }
}
