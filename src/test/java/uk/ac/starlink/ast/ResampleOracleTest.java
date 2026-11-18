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
 * are {@link ResampleCallbackTest.TriKernel} and {@link ResampleCallbackTest.ProbeUinterpAll}.
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
     * The largest difference from C's value allowed for a case of {@code type} whose kernel calls
     * sin, cos or exp, where the hash differs. Java's {@code Math.sin}, {@code cos} and {@code exp}
     * and C's are each within an ulp of the exact value, so they differ by at most 4u relative (u =
     * 2^-53). A kernel value, at most 1 in size and made from at most two such calls with a
     * quotient and a product, then differs by at most 16u; a pixel's weight w, the product of at
     * most three kernel values, by at most 64u. An output value is sum(w v) / sum(w) over at most
     * 64 pixels (the probe's largest block, 4 by 4 by 4), with |v| at most 56 (the probe's data)
     * and sum(|w|) / |sum(w)| at most 20 for these kernels with at most a few pixels of a block
     * left out as bad. Its difference is then at most 64u * 20 * 2 * 56 from the weights, and the
     * same again from the roundings of the 64 terms of each sum taking different paths: together
     * 3.2e-11, and a variance, sum(w^2 var) / sum(w)^2 with |var| at most 4, no more.
     *
     * <ul>
     *   <li>D: 1e-10, the bound above.
     *   <li>F: the sums are in float, so a weight rounded to float may differ by an ulp of float
     *       (2^-23 relative), and each of the 64 float roundings of each sum may then differ by
     *       2^-24 of the sum: at most 2^-23 * 20 * 112 + 2 * 64 * 2^-24 * 20 * 56, under 1e-2.
     *   <li>L, I, S, B: an integer whose value before rounding differs by less than a half, by the
     *       bounds above, can differ only by one, where that value is near a half.
     * </ul>
     *
     * <p>A bad or untouched pixel (120 or 77) must be bad or untouched in both.
     */
    private static double tolerance(String type) {
        switch (type) {
            case "D":
                return 1.0e-10;
            case "F":
                return 1.0e-2;
            case "L":
            case "I":
            case "S":
            case "B":
                return 1.0;
            default:
                throw new IllegalArgumentException("no type " + type);
        }
    }

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

    /**
     * How the outputs of {@code r} differ from the probe's beyond {@link #tolerance}, or null if
     * none does.
     */
    private static String valueDifference(Result r, Case cs, String probe) {
        double tol = tolerance(cs.type);
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
                boolean fixed = w == 120.0 || w == 77.0 || g == 120.0 || g == 77.0;
                if (fixed ? g != w : Double.isNaN(g - w) || Math.abs(g - w) > tol) {
                    return (a == 0 ? "value " : "variance ") + k + " is " + g + ", C's " + w;
                }
            }
        }
        return null;
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
