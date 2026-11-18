package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Translation of Rust {@code tests/test_resample_oracle.rs}: {@code resample} against C's {@code
 * astResample<X>}, from {@code oracle/resample.json}, which C's {@code resample_probe.c} made. Each
 * case gives the status, the number of bad pixels, an FNV-1a hash of the bit patterns of every
 * output value and variance (as doubles) and every 211th output value. A case that fails part way
 * is compared on what it wrote before failing.
 *
 * <p>{@code resamplingMatchesC} runs every case of the types jniast has (D, F, L, I, S, B); the
 * cases of C's unsigned types have no jniast method. The probe's caller's kernel and interpolator
 * are {@link ResampleCallbackTest.TriKernel} and {@link ResampleCallbackTest.ProbeUinterpAll}. A
 * case whose kernel calls sin, cos or exp may differ from C in the last bits, since Java's {@code
 * Math} and C's library may; where its hash differs it is compared value by value with {@code
 * probes/resample_kernel_probe.out} within a bound worked out for each pixel ({@link #bounds}), and
 * {@code kernelCasesAreWithinTheirBounds} makes that comparison for every such case on every
 * platform.
 * {@code errorsMatchC} runs the probe's fifteen invalid-argument cases. {@code
 * too_few_params_is_an_error} is listed in {@code deferred/rust-tests.txt}: jniast's Interpolator
 * factories fix the parameters.
 */
class ResampleOracleTest {

    /** The probe's Mapping for a case's Mapping name. */
    private static Mapping mapping(String name) {
        switch (name) {
            case "unit2":
                return new UnitMap(2);
            case "shift_half":
                return new ShiftMap(new double[] {0.5, -0.25});
            case "zoom2":
                return new ZoomMap(2, 1.7);
            case "zoom1":
                return new ZoomMap(1, 1.3);
            case "perm":
                return new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
            case "nonlin2":
                return new MathMap(
                        2, 2, new String[] {"p=a", "q=b"}, new String[] {"a=p+0.02*p*q-0.3", "b=q-0.015*p*p+0.2"});
            case "cubic1":
                return new MathMap(1, 1, new String[] {"p=a"}, new String[] {"a=0.001*p*p*p+0.5*p"});
            case "win3":
                return new WinMap(
                        3,
                        new double[] {0.0, 0.0, 0.0},
                        new double[] {1.0, 1.0, 1.0},
                        new double[] {0.3, -0.2, 0.1},
                        new double[] {1.1, 0.7, 1.4});
            case "nonlin3":
                return new MathMap(3, 3, new String[] {"p=a", "q=b", "r=c"}, new String[] {
                    "a=p+0.05*q*r", "b=q-0.03*p*p", "c=0.9*r+0.1*p"
                });
            case "noinverse":
                return new MathMap(2, 2, new String[] {"p=a", "q=b"}, new String[] {"a", "b"});
            default:
                throw new IllegalArgumentException("no Mapping " + name);
        }
    }

    /**
     * The Interpolator jniast makes for the probe's scheme and parameters {@code p0} and {@code
     * p1}. jniast's factories take the number of neighbouring pixels as an integer, which each
     * case's {@code p0} is. jniast has no factory for {@code AST__GAUSS}, so its Interpolator is
     * made directly. The probe's caller's kernel and interpolator take their parameter from
     * {@code p1} and {@code p0}, which jniast's calculators cannot see, so each is made with it.
     */
    private static Mapping.Interpolator interpolator(String scheme, double p0, double p1) {
        int npix = (int) p0;
        if (!"uinterp".equals(scheme)) {
            assertEquals(p0, npix, "params[0] of " + scheme);
        }
        switch (scheme) {
            case "nearest":
                return Mapping.NEAREST_INTERPOLATOR;
            case "linear":
                return Mapping.LINEAR_INTERPOLATOR;
            case "sinc":
                return Mapping.Interpolator.sinc(npix);
            case "sincsinc":
                return Mapping.Interpolator.sincSinc(npix, p1);
            case "sinccos":
                return Mapping.Interpolator.sincCos(npix, p1);
            case "sincgauss":
                return Mapping.Interpolator.sincGauss(npix, p1);
            case "gauss":
                return new Mapping.Interpolator(Mapping.AST__GAUSS, null, null, p0, p1);
            case "somb":
                return Mapping.Interpolator.somb(npix);
            case "sombcos":
                return Mapping.Interpolator.sombCos(npix, p1);
            case "blockave":
                return Mapping.Interpolator.blockAve(npix);
            case "ukern1":
                return Mapping.Interpolator.ukern1(new ResampleCallbackTest.TriKernel(p1), npix);
            case "uinterp":
                return Mapping.Interpolator.uinterp(new ResampleCallbackTest.ProbeUinterpAll(p0));
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

    private static int npix(int[] l, int[] u) {
        int n = 1;
        for (int i = 0; i < l.length; i++) {
            n *= u[i] - l[i] + 1;
        }
        return n;
    }

    /** A case's arguments, as the probe sets them up. */
    private static final class Case {
        final String name;
        final String type;
        final Mapping map;
        final int[] lbndIn;
        final int[] ubndIn;
        final int[] lbndOut;
        final int[] ubndOut;
        final int[] lbnd;
        final int[] ubnd;
        final Mapping.Interpolator interp;
        final ResampleFlags flags = new ResampleFlags();
        final boolean useVar;
        final double tol;
        final int maxpix;

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
            interp = interpolator(
                    c.get(10).asText(),
                    ProbeData.toDouble(c.get(15).get(0)),
                    ProbeData.toDouble(c.get(15).get(1)));
            flags.setUseBad(c.get(11).asBoolean());
            flags.setNoBad(c.get(12).asBoolean());
            useVar = c.get(13).asBoolean();
            flags.setConserveFlux(c.get(14).asBoolean());
            tol = ProbeData.toDouble(c.get(16));
            maxpix = c.get(17).asInt();
        }

        /**
         * The probe's input data and variances as doubles in the case's type: {@code
         * ((7 k) % 97) * 0.37 - 10} and {@code ((3 k) % 11) * 0.5 - 1} for a floating type, {@code
         * (7 k) % 97 - 40} and {@code (3 k) % 11 - 2} for an integer one, with the bad value 120 at
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
                    var[k] = (3 * k) % 11 * 0.5 - 1.0;
                } else {
                    data[k] = (7 * k) % 97 - 40;
                    var[k] = (3 * k) % 11 - 2;
                }
                if (flags.getUseBad() && k % 17 == 3) {
                    data[k] = 120.0;
                }
                if (flags.getUseBad() && k % 19 == 5) {
                    var[k] = 120.0;
                }
            }
            return new double[][] {data, var};
        }
    }

    /** The result of a case: status, bad pixels, and the output values and variances as doubles. */
    private static final class Result {
        int status;
        int nbad;
        double[] out;
        double[] outVar;
    }

    /** Runs a case through the jniast method of its type, or returns null for a type jniast lacks. */
    private static Result run(Case c) {
        double[][] input = c.input();
        double[] d = input[0];
        double[] v = input[1];
        int nout = npix(c.lbndOut, c.ubndOut);
        Result r = new Result();
        int ndimIn = c.lbndIn.length;
        int ndimOut = c.lbndOut.length;
        try {
            switch (c.type) {
                case "D": {
                    double[] out = new double[nout];
                    double[] outVar = new double[nout];
                    Arrays.fill(out, 77.0);
                    Arrays.fill(outVar, 77.0);
                    r.out = out;
                    r.outVar = outVar;
                    r.nbad = c.map.resampleD(
                            ndimIn,
                            c.lbndIn,
                            c.ubndIn,
                            d,
                            c.useVar ? v : null,
                            c.interp,
                            c.flags,
                            c.tol,
                            c.maxpix,
                            120.0,
                            ndimOut,
                            c.lbndOut,
                            c.ubndOut,
                            c.lbnd,
                            c.ubnd,
                            out,
                            c.useVar ? outVar : null);
                    break;
                }
                case "F": {
                    float[] in = new float[d.length];
                    float[] inVar = new float[d.length];
                    for (int k = 0; k < d.length; k++) {
                        in[k] = (float) d[k];
                        inVar[k] = (float) v[k];
                    }
                    float[] out = new float[nout];
                    float[] outVar = new float[nout];
                    Arrays.fill(out, 77.0f);
                    Arrays.fill(outVar, 77.0f);
                    try {
                        r.nbad = c.map.resampleF(
                                ndimIn,
                                c.lbndIn,
                                c.ubndIn,
                                in,
                                c.useVar ? inVar : null,
                                c.interp,
                                c.flags,
                                c.tol,
                                c.maxpix,
                                120.0f,
                                ndimOut,
                                c.lbndOut,
                                c.ubndOut,
                                c.lbnd,
                                c.ubnd,
                                out,
                                c.useVar ? outVar : null);
                    } finally {
                        r.out = new double[nout];
                        r.outVar = new double[nout];
                        for (int k = 0; k < nout; k++) {
                            r.out[k] = out[k];
                            r.outVar[k] = outVar[k];
                        }
                    }
                    break;
                }
                case "L": {
                    long[] in = new long[d.length];
                    long[] inVar = new long[d.length];
                    for (int k = 0; k < d.length; k++) {
                        in[k] = (long) d[k];
                        inVar[k] = (long) v[k];
                    }
                    long[] out = new long[nout];
                    long[] outVar = new long[nout];
                    Arrays.fill(out, 77L);
                    Arrays.fill(outVar, 77L);
                    try {
                        r.nbad = c.map.resampleL(
                                ndimIn,
                                c.lbndIn,
                                c.ubndIn,
                                in,
                                c.useVar ? inVar : null,
                                c.interp,
                                c.flags,
                                c.tol,
                                c.maxpix,
                                120L,
                                ndimOut,
                                c.lbndOut,
                                c.ubndOut,
                                c.lbnd,
                                c.ubnd,
                                out,
                                c.useVar ? outVar : null);
                    } finally {
                        r.out = new double[nout];
                        r.outVar = new double[nout];
                        for (int k = 0; k < nout; k++) {
                            r.out[k] = out[k];
                            r.outVar[k] = outVar[k];
                        }
                    }
                    break;
                }
                case "I": {
                    int[] in = new int[d.length];
                    int[] inVar = new int[d.length];
                    for (int k = 0; k < d.length; k++) {
                        in[k] = (int) d[k];
                        inVar[k] = (int) v[k];
                    }
                    int[] out = new int[nout];
                    int[] outVar = new int[nout];
                    Arrays.fill(out, 77);
                    Arrays.fill(outVar, 77);
                    try {
                        r.nbad = c.map.resampleI(
                                ndimIn,
                                c.lbndIn,
                                c.ubndIn,
                                in,
                                c.useVar ? inVar : null,
                                c.interp,
                                c.flags,
                                c.tol,
                                c.maxpix,
                                120,
                                ndimOut,
                                c.lbndOut,
                                c.ubndOut,
                                c.lbnd,
                                c.ubnd,
                                out,
                                c.useVar ? outVar : null);
                    } finally {
                        r.out = new double[nout];
                        r.outVar = new double[nout];
                        for (int k = 0; k < nout; k++) {
                            r.out[k] = out[k];
                            r.outVar[k] = outVar[k];
                        }
                    }
                    break;
                }
                case "S": {
                    short[] in = new short[d.length];
                    short[] inVar = new short[d.length];
                    for (int k = 0; k < d.length; k++) {
                        in[k] = (short) d[k];
                        inVar[k] = (short) v[k];
                    }
                    short[] out = new short[nout];
                    short[] outVar = new short[nout];
                    Arrays.fill(out, (short) 77);
                    Arrays.fill(outVar, (short) 77);
                    try {
                        r.nbad = c.map.resampleS(
                                ndimIn,
                                c.lbndIn,
                                c.ubndIn,
                                in,
                                c.useVar ? inVar : null,
                                c.interp,
                                c.flags,
                                c.tol,
                                c.maxpix,
                                (short) 120,
                                ndimOut,
                                c.lbndOut,
                                c.ubndOut,
                                c.lbnd,
                                c.ubnd,
                                out,
                                c.useVar ? outVar : null);
                    } finally {
                        r.out = new double[nout];
                        r.outVar = new double[nout];
                        for (int k = 0; k < nout; k++) {
                            r.out[k] = out[k];
                            r.outVar[k] = outVar[k];
                        }
                    }
                    break;
                }
                case "B": {
                    byte[] in = new byte[d.length];
                    byte[] inVar = new byte[d.length];
                    for (int k = 0; k < d.length; k++) {
                        in[k] = (byte) d[k];
                        inVar[k] = (byte) v[k];
                    }
                    byte[] out = new byte[nout];
                    byte[] outVar = new byte[nout];
                    Arrays.fill(out, (byte) 77);
                    Arrays.fill(outVar, (byte) 77);
                    try {
                        r.nbad = c.map.resampleB(
                                ndimIn,
                                c.lbndIn,
                                c.ubndIn,
                                in,
                                c.useVar ? inVar : null,
                                c.interp,
                                c.flags,
                                c.tol,
                                c.maxpix,
                                (byte) 120,
                                ndimOut,
                                c.lbndOut,
                                c.ubndOut,
                                c.lbnd,
                                c.ubnd,
                                out,
                                c.useVar ? outVar : null);
                    } finally {
                        r.out = new double[nout];
                        r.outVar = new double[nout];
                        for (int k = 0; k < nout; k++) {
                            r.out[k] = out[k];
                            r.outVar[k] = outVar[k];
                        }
                    }
                    break;
                }
                default:
                    return null;
            }
        } catch (AstException e) {
            r.status = e.getStatus();
            r.nbad = 0;
        }
        return r;
    }

    /** FNV-1a over the bit patterns of every output value, and variance when variances are used. */
    private static long hash(Result r, boolean useVar) {
        long h = 0xcbf29ce484222325L;
        for (int k = 0; k < r.out.length; k++) {
            h = (h ^ Double.doubleToRawLongBits(r.out[k])) * 0x100000001b3L;
            if (useVar) {
                h = (h ^ Double.doubleToRawLongBits(r.outVar[k])) * 0x100000001b3L;
            }
        }
        return h;
    }

    /**
     * How the case {@code c} differs from C, or null if it matches; also null for a case of a type
     * jniast lacks, which {@link #isJniastType} tells apart.
     */
    static String mismatch(JsonNode c) {
        Case cs = new Case(c);
        Result r = run(cs);
        if (r == null) {
            return null;
        }
        int wantNbad = c.get(18).asInt();
        int wantStatus = c.get(19).asInt();
        long wantHash = c.get(20).bigIntegerValue().longValue();
        JsonNode sample = c.get(21);
        List<Double> got = new ArrayList<>();
        for (int k = 0; k < r.out.length; k += 211) {
            got.add(r.out[k]);
        }
        boolean sameSamples = got.size() == sample.size();
        for (int k = 0; sameSamples && k < got.size(); k++) {
            double w = ProbeData.toDouble(sample.get(k));
            sameSamples = Double.doubleToLongBits(got.get(k)) == Double.doubleToLongBits(w);
        }
        long h = hash(r, cs.useVar);
        String values = "";
        if (r.status == wantStatus && r.nbad == wantNbad && (h != wantHash || !sameSamples)) {
            String probe = KERNEL_VALUES.get(cs.name + " " + cs.type);
            if (probe != null) {
                String diff = valueDifference(r, cs, probe);
                if (diff == null) {
                    return null;
                }
                values = "; " + diff;
            }
        }
        if (r.status != wantStatus || r.nbad != wantNbad || h != wantHash || !sameSamples) {
            return cs.name + " " + cs.type + ": status " + r.status + " want " + wantStatus + "; bad " + r.nbad
                    + " want " + wantNbad + "; hash " + (h == wantHash ? "same" : "differs") + "; samples " + got
                    + " want " + sample + values;
        }
        return null;
    }

    /**
     * Every output value and variance of the oracle's cases whose kernels call sin, cos or exp,
     * keyed by case name and type, from {@code probes/resample_kernel_probe.out}.
     */
    private static final Map<String, String> KERNEL_VALUES = ProbeOutput.load("resample_kernel_probe");

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

    /** Whether the case {@code c} is of a type jniast has a method for. */
    static boolean isJniastType(JsonNode c) {
        switch (c.get(2).asText()) {
            case "D":
            case "F":
            case "L":
            case "I":
            case "S":
            case "B":
                return true;
            default:
                return false;
        }
    }

    @Test
    void resamplingMatchesC() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("resample").get("CASES")) {
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
        assertEquals(222, ran, "cases run");
    }

    /** Records the input position of each output pixel the resampling interpolates. */
    private static final class PositionRecorder extends UinterpCalculator {
        final double[][] position;

        PositionRecorder(int nout) {
            position = new double[nout][];
        }

        @Override
        public int uinterpD(
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                double[] in,
                double[] inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                double badval,
                double[] out,
                double[] outVar) {
            for (int point = 0; point < npoint; point++) {
                double[] x = new double[ndimIn];
                for (int i = 0; i < ndimIn; i++) {
                    x[i] = coords[i][point];
                }
                position[offset[point]] = x;
            }
            return 0;
        }
    }

    private static final double U = 0x1p-53;
    private static final double UF = 0x1p-24;

    /**
     * The largest relative difference between Java's and C's weight for a pixel, worked out in
     * {@link #bounds}.
     */
    private static final double RHO_W = 164 * U;

    /**
     * The largest differences from C's outputs allowed for the kernel case {@code cs}, for each
     * output value ([0]) and variance ([1]), worked out pixel by pixel from the weights C's {@code
     * InterpolateKernel1<X>} gives it; zero where the output must equal C's exactly. The input
     * positions are those the case's resampling gives a caller's interpolator (the Mapping, fits
     * and blocks are the same for every scheme, and use only arithmetic, so the positions are C's
     * on every platform); the blocks, bad pixels and weights are {@code InterpolateKernel1<X>}'s,
     * computed here in double.
     *
     * <p>The bound. Let u = 2^-53. Java's {@code Math.sin}, {@code cos}, {@code exp} and {@code
     * log} and C's are each within an ulp (at most 2u relative) of the exact value, so they differ
     * by at most 4u relative; two correct roundings of an operation on inputs differing by d
     * relative differ by at most d + 2u (one ulp each way); an operation on equal inputs gives
     * equal results. To first order the kernel values then differ by at most: sinc, sin(x) / x:
     * 6u; SincCos: 6u + 4u + 2u = 12u; SincSinc: 14u; Somb and SombCos: J1 uses its rational
     * approximation, plain arithmetic, for |x| below 8, and the cases' offsets are at most 2
     * pixels (|x| at most 2 pi), so 0 and 4u + 2u = 6u; the Gaussians: k = 4 log(2) / fwhm^2 differs
     * by 6u, the argument a = k o o by 10u, and exp(-a) by 10u a + 4u, where a is at most k
     * neighb^2 = 11.1 (Gauss with fwhm 1 and 2 pixels), so Gauss 115u and SincGauss (a at most
     * 4.93) 6u + 53u + 2u = 61u. The default reaches, ceil(sqrt(-log(0.01) / k)) = ceil(3.87) and
     * ceil(1.93), are far from integers, so are the same. A weight is the product of one kernel
     * value per axis, with a rounding for each product: at most 115u in 1-D (Gauss), 2 * 81u + 2u =
     * 164u in 2-D (Gauss with fwhm 1.2, a at most 7.70, 81u), and 3 * 6u + 4u = 22u in 3-D: RHO_W
     * = 164u.
     *
     * <p>With s the unit roundoff of the sum type (u, or 2^-24 for F, S and B), e the relative
     * difference of a weight as summed (RHO_W, or RHO_W + 2s where it is rounded to float), and n
     * the number of pixels summed: a term w v differs by (e + 2s)|w v|; the running sum, whose
     * partial sums each round to within an ulp of each other, by (e + 2s + 2ns) P with P =
     * sum(|w v|); the sum of weights W by (e + 2ns) A with A = sum(|w|); so the value, S / W, by
     * dV = (e + 2s + 2ns) P / |W| + |val| (e + 2ns) A / |W| + 2s |val|. A variance sum(w^2 var) /
     * W^2, whose squared weights differ by e2 = 2 RHO_W + 2u (and 2s more if rounded to float),
     * differs by (e2 + 2s + 2ns) Q / W^2 + |valVar| (2 (e + 2ns) A / |W| + 4s), Q = sum(w^2 |var|).
     * Flux conservation multiplies by the fit's factor, which is the same in both and at most 1
     * here (1 and 1 / 1.3), with one more rounding, 2s of the result. The bound allowed is twice
     * this, for the second-order terms. A value of an integer type is rounded by adding a half
     * (less a half if negative) in the sum type and truncating, which is monotonic and steps only
     * where the value lies within s (|val| + 0.5) of a half-integer; so with its bound below a half
     * it may differ by one only where it lies within its bound plus that of a half-integer, and
     * must be equal elsewhere. It must not lie within its bound of the type's range limits, where
     * C would make it bad. A pixel bad in C (outside the grid, in a bad input pixel, or with no
     * weight) is bad in Java too, and must be equal.
     *
     * @throws AssertionError if an integer value's bound is a half or more, or the value lies
     *     within its bound of a range limit, where the bound cannot decide
     */
    private static double[][] bounds(Case cs) {
        int nout = npix(cs.lbndOut, cs.ubndOut);
        PositionRecorder rec = new PositionRecorder(nout);
        double[][] input = cs.input();
        cs.map.resampleD(
                cs.lbndIn.length,
                cs.lbndIn,
                cs.ubndIn,
                input[0],
                cs.useVar ? input[1] : null,
                Mapping.Interpolator.uinterp(rec),
                cs.flags,
                cs.tol,
                cs.maxpix,
                120.0,
                cs.lbndOut.length,
                cs.lbndOut,
                cs.ubndOut,
                cs.lbnd,
                cs.ubnd,
                new double[nout],
                cs.useVar ? new double[nout] : null);
        boolean floatSum = "F".equals(cs.type) || "S".equals(cs.type) || "B".equals(cs.type);
        boolean integer = !"D".equals(cs.type) && !"F".equals(cs.type);
        double s = floatSum ? UF : U;
        double e = RHO_W + (floatSum ? 2 * s : 0.0);
        double e2 = 2 * RHO_W + 2 * U + (floatSum ? 2 * s : 0.0);
        double[] limits = integerLimits(cs.type);
        ResampleKernels.Setup setup = ResampleKernels.setup(cs.interp.scheme, cs.interp.params);
        boolean usebad = cs.flags.getUseBad();
        boolean conserve = cs.flags.getConserveFlux();
        int ndim = cs.lbndIn.length;
        long[] stride = new long[ndim];
        long st = 1;
        for (int i = 0; i < ndim; i++) {
            stride[i] = st;
            st *= cs.ubndIn[i] - cs.lbndIn[i] + 1;
        }
        double[][] bound = new double[2][nout];
        for (int pt = 0; pt < nout; pt++) {
            double[] x = rec.position[pt];
            if (x == null) {
                continue;
            }
            boolean bad = false;
            long pixel = 0;
            long[] lo = new long[ndim];
            long[] hi = new long[ndim];
            double[][] k = new double[ndim][];
            for (int i = 0; i < ndim && !bad; i++) {
                bad = x[i] < cs.lbndIn[i] - 0.5 || x[i] >= cs.ubndIn[i] + 0.5 || x[i] == AstObject.AST__BAD;
                if (!bad) {
                    pixel += stride[i] * ((long) Math.floor(x[i] + 0.5) - cs.lbndIn[i]);
                    long ix = (long) Math.floor(x[i]);
                    lo[i] = Math.max(ix - setup.neighb + 1, cs.lbndIn[i]);
                    hi[i] = Math.min(ix + setup.neighb, cs.ubndIn[i]);
                    k[i] = new double[(int) (hi[i] - lo[i] + 1)];
                    for (long j = lo[i]; j <= hi[i]; j++) {
                        k[i][(int) (j - lo[i])] = setup.kernel.value(j - x[i], setup.params);
                    }
                }
            }
            if (bad || (usebad && input[0][(int) pixel] == 120.0)) {
                continue;
            }
            double sumAbs = 0.0;
            double sum = 0.0;
            double sumV = 0.0;
            double sumAbsV = 0.0;
            double sumW2Var = 0.0;
            double sumW2AbsVar = 0.0;
            boolean badVar = false;
            int n = 0;
            long[] j = lo.clone();
            while (true) {
                int off = 0;
                double w = 1.0;
                for (int i = 0; i < ndim; i++) {
                    off += (int) (stride[i] * (j[i] - cs.lbndIn[i]));
                    w *= k[i][(int) (j[i] - lo[i])];
                }
                double v = "F".equals(cs.type) ? (float) input[0][off] : input[0][off];
                if (!usebad || v != 120.0) {
                    n++;
                    sumAbs += Math.abs(w);
                    sum += w;
                    sumV += w * v;
                    sumAbsV += Math.abs(w * v);
                    double var = "F".equals(cs.type) ? (float) input[1][off] : input[1][off];
                    badVar = badVar || (usebad && var == 120.0) || var < 0.0;
                    sumW2Var += w * w * var;
                    sumW2AbsVar += w * w * Math.abs(var);
                }
                // The next pixel of the block, first axis fastest; done when every axis returns.
                boolean done = true;
                for (int i = 0; i < ndim; i++) {
                    if (j[i] < hi[i]) {
                        j[i]++;
                        done = false;
                        break;
                    }
                    j[i] = lo[i];
                }
                if (done) {
                    break;
                }
            }
            if (sum == 0.0) {
                continue;
            }
            double val = sumV / sum;
            double ratio = sumAbs / Math.abs(sum);
            double dv = (e + 2 * s + 2 * n * s) * sumAbsV / Math.abs(sum)
                    + Math.abs(val) * (e + 2 * n * s) * ratio
                    + 2 * s * Math.abs(val)
                    + (conserve ? 2 * s * Math.abs(val) : 0.0);
            bound[0][pt] = integerBound(integer, 2 * dv, val, s, limits, cs, pt, "value");
            if (cs.useVar && !badVar) {
                double valVar = sumW2Var / (sum * sum);
                double dvar = (e2 + 2 * s + 2 * n * s) * sumW2AbsVar / (sum * sum)
                        + Math.abs(valVar) * (2 * (e + 2 * n * s) * ratio + 4 * s)
                        + (conserve ? 2 * s * Math.abs(valVar) : 0.0);
                bound[1][pt] = integerBound(integer, 2 * dvar, valVar, s, limits, cs, pt, "variance");
            }
        }
        return bound;
    }

    /** The values at which an interpolated value overflows an integer type (C's LO and HI). */
    private static double[] integerLimits(String type) {
        switch (type) {
            case "B":
                return new double[] {-128.5, 127.5};
            case "S":
                return new double[] {-32768.5, 32767.5};
            case "I":
                return new double[] {-2147483648.5, 2147483647.5};
            case "L":
            case "D":
            case "F":
                return new double[0];
            default:
                throw new IllegalArgumentException("no type " + type);
        }
    }

    /**
     * The bound {@code b} for a floating type; for an integer type, whose sum type has unit
     * roundoff {@code s}, 1 where {@code val} lies within {@code b + s (|val| + 0.5)} of a
     * half-integer, where rounding may go either way, and 0 elsewhere or where it lies beyond the
     * range limits by more than {@code b} (bad in both).
     *
     * @throws AssertionError if {@code b} is a half or more, or {@code val} lies within {@code b} of
     *     a range limit
     */
    private static double integerBound(
            boolean integer, double b, double val, double s, double[] limits, Case cs, int pt, String what) {
        if (!integer) {
            return b;
        }
        assertTrue(b < 0.5, cs.name + " " + cs.type + " " + what + " " + pt + ": bound " + b);
        for (double lim : limits) {
            assertTrue(
                    Math.abs(val - lim) > b,
                    cs.name + " " + cs.type + " " + what + " " + pt + " " + val + " within " + b + " of " + lim);
        }
        boolean overflows = limits.length > 0 && (val <= limits[0] || val >= limits[1]);
        if (overflows) {
            return 0.0;
        }
        double fromHalf = Math.abs(val - (Math.floor(val) + 0.5));
        return fromHalf <= b + s * (Math.abs(val) + 0.5) ? 1.0 : 0.0;
    }

    /**
     * How the outputs of {@code r} differ from the probe's by more than {@link #bounds} allows, or
     * null if none does.
     */
    private static String valueDifference(Result r, Case cs, String probe) {
        double[][] bound = bounds(cs);
        double[][] got = cs.useVar ? new double[][] {r.out, r.outVar} : new double[][] {r.out};
        double[][] want = cs.useVar
                ? new double[][] {probeValues(probe, "out", cs.type), probeValues(probe, "var", cs.type)}
                : new double[][] {probeValues(probe, "out", cs.type)};
        for (int a = 0; a < got.length; a++) {
            if (got[a].length != want[a].length) {
                return "probe has " + want[a].length + " values, not " + got[a].length;
            }
            for (int k = 0; k < got[a].length; k++) {
                double g = got[a][k];
                double w = want[a][k];
                if (Double.isNaN(g - w) || Math.abs(g - w) > bound[a][k]) {
                    return (a == 0 ? "value " : "variance ") + k + " is " + g + ", C's " + w + ", allowed "
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
        for (JsonNode c : ProbeData.load("resample").get("CASES")) {
            String probe = KERNEL_VALUES.get(c.get(0).asText() + " " + c.get(2).asText());
            if (probe == null) {
                continue;
            }
            Case cs = new Case(c);
            String diff = valueDifference(run(cs), cs, probe);
            if (diff != null) {
                failures.add(cs.name + " " + cs.type + ": " + diff);
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(78, ran, "cases run");
    }

    /** The kernel probe's values reproduce the oracle's hash of each of its cases. */
    @Test
    void kernelProbeMatchesTheOracle() {
        int matched = 0;
        for (JsonNode c : ProbeData.load("resample").get("CASES")) {
            String key = c.get(0).asText() + " " + c.get(2).asText();
            String probe = KERNEL_VALUES.get(key);
            if (probe == null) {
                continue;
            }
            Result r = new Result();
            r.out = probeValues(probe, "out", c.get(2).asText());
            boolean useVar = c.get(13).asBoolean();
            r.outVar = useVar ? probeValues(probe, "var", c.get(2).asText()) : null;
            assertEquals(c.get(20).bigIntegerValue().longValue(), hash(r, useVar), key);
            assertTrue(probe.startsWith("nbad " + c.get(18).asInt() + "\n"), key);
            matched++;
        }
        assertEquals(KERNEL_VALUES.size(), matched, "probe cases in the oracle");
        assertEquals(78, matched, "probe cases");
    }

    /** The status of resampling double data as one of the probe's invalid-argument cases does. */
    private static int status(
            Mapping map,
            int[] lbndIn,
            int[] ubndIn,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            ResampleFlags flags,
            double tol) {
        return status(map, lbndIn, ubndIn, lbndOut, ubndOut, lbnd, ubnd, flags, tol, Mapping.LINEAR_INTERPOLATOR);
    }

    /** The status of resampling as {@link #status} does, with {@code interp}. */
    private static int status(
            Mapping map,
            int[] lbndIn,
            int[] ubndIn,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            ResampleFlags flags,
            double tol,
            Mapping.Interpolator interp) {
        double[] input = new double[Math.min(npix(lbndIn, ubndIn), 400)];
        for (int k = 0; k < input.length; k++) {
            input[k] = k;
        }
        double[] out = new double[Math.min(npix(lbndOut, ubndOut), 400)];
        try {
            map.resampleD(
                    lbndIn.length,
                    lbndIn,
                    ubndIn,
                    input,
                    null,
                    interp,
                    flags,
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
            return 0;
        } catch (AstException e) {
            return e.getStatus();
        }
    }

    private static ResampleFlags conserve(boolean noBad) {
        ResampleFlags f = new ResampleFlags();
        f.setConserveFlux(true);
        f.setNoBad(noBad);
        return f;
    }

    @Test
    void errorsMatchC() {
        int[] l2 = {1, 1};
        int[] u2 = {10, 10};
        int[] bad = {11, 1};
        int[] rl = {0, 1};
        int[] ru = {10, 11};
        int[] l3 = {1, 1, 1};
        int[] u3 = {10, 10, 1};
        int[] u3b = {10, 10, 10};
        Mapping unit = mapping("unit2");
        ResampleFlags none = new ResampleFlags();
        int[] got = {
            status(unit, new int[] {1}, new int[] {10}, l2, u2, l2, u2, none, 0.0),
            status(unit, l2, u2, l3, u3b, l3, u3b, none, 0.0),
            status(unit, bad, u2, l2, u2, l2, u2, none, 0.0),
            status(unit, l2, u2, bad, u2, l2, u2, none, 0.0),
            status(unit, l2, u2, l2, u2, rl, u2, none, 0.0),
            status(unit, l2, u2, l2, u2, l2, ru, none, 0.0),
            status(unit, l2, u2, l2, u2, l2, u2, none, -1.0),
            status(mapping("noinverse"), l2, u2, l2, u2, l2, u2, none, 0.0),
            status(unit, l2, u2, l2, u2, l2, u2, conserve(false), 0.0),
            status(
                    new PermMap(2, new int[] {1, 2}, 3, new int[] {1, 2, -1}, new double[] {0.5, 0.5}),
                    l2,
                    u2,
                    l3,
                    u3,
                    l3,
                    u3,
                    conserve(false),
                    0.1),
            status(mapping("nonlin2"), l2, u2, l2, u2, l2, u2, conserve(false), 1.0e-12),
            status(unit, l2, u2, l2, u2, l2, u2, conserve(true), 0.1),
            status(
                    new ShiftMap(new double[] {0.5, 0.5}),
                    l2,
                    u2,
                    l2,
                    u2,
                    l2,
                    u2,
                    none,
                    0.0,
                    Mapping.Interpolator.ukern1(new ResampleCallbackTest.TriKernel(-1.0), 2)),
            status(
                    unit,
                    l2,
                    u2,
                    l2,
                    u2,
                    l2,
                    u2,
                    none,
                    0.0,
                    Mapping.Interpolator.uinterp(new ResampleCallbackTest.ProbeUinterpAll(-1.0))),
            status(
                    unit,
                    l2,
                    u2,
                    l2,
                    u2,
                    l2,
                    u2,
                    none,
                    0.0,
                    Mapping.Interpolator.ukern1(new ResampleCallbackTest.TriKernel(-1.0), 2))
        };
        JsonNode want = ProbeData.load("resample").get("ERRORS");
        assertEquals(15, want.size(), "probe error cases");
        int[] wantStatus = new int[got.length];
        for (int i = 0; i < got.length; i++) {
            wantStatus[i] = want.get(i).asInt();
        }
        // The probe's kernel and interpolator fail with AST__BADIN, which C passes on as the
        // status; jniast's callbacks fail with AST__UK1ER and AST__UINER, which C passes on alike.
        assertEquals(AstException.AST__BADIN, wantStatus[12]);
        assertEquals(AstException.AST__BADIN, wantStatus[13]);
        wantStatus[12] = AstException.AST__UK1ER;
        wantStatus[13] = AstException.AST__UINER;
        assertArrayEquals(wantStatus, got);
    }
}
