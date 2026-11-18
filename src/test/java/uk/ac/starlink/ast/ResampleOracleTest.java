package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_resample_oracle.rs}: {@code resample} against C's {@code
 * astResample<X>}, from {@code oracle/resample.json}, which C's {@code resample_probe.c} made. Each
 * case gives the status, the number of bad pixels, an FNV-1a hash of the bit patterns of every
 * output value and variance (as doubles) and every 211th output value. A case that fails part way
 * is compared on what it wrote before failing.
 *
 * <p>{@code resamplingMatchesC} runs the cases of the nearest-neighbour and linear schemes for the
 * types jniast has (D, F, L, I, S, B); the cases of C's unsigned types have no jniast method. The
 * Mappings these cases use do only arithmetic, which Java and C carry out alike, so every value
 * must equal C's on every platform, and the hash is compared everywhere. {@code errorsMatchC} runs
 * the first twelve of the probe's fifteen invalid-argument cases; the last three use a caller's
 * kernel or interpolator, which this test does not cover. {@code too_few_params_is_an_error} is
 * listed in {@code deferred/rust-tests.txt}: jniast's Interpolator factories fix the parameters.
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
            interp = "nearest".equals(c.get(10).asText()) ? Mapping.NEAREST_INTERPOLATOR : Mapping.LINEAR_INTERPOLATOR;
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

    @Test
    void resamplingMatchesC() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("resample").get("CASES")) {
            String scheme = c.get(10).asText();
            if (!"nearest".equals(scheme) && !"linear".equals(scheme)) {
                continue;
            }
            Case cs = new Case(c);
            Result r = run(cs);
            if (r == null) {
                continue;
            }
            ran++;
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
            if (r.status != wantStatus || r.nbad != wantNbad || h != wantHash || !sameSamples) {
                failures.add(cs.name + " " + cs.type + ": status " + r.status + " want " + wantStatus + "; bad "
                        + r.nbad + " want " + wantNbad + "; hash " + (h == wantHash ? "same" : "differs")
                        + "; samples " + got + " want " + sample);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " cases differ:\n" + String.join("\n", failures));
        assertEquals(90, ran, "cases run");
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
                    Mapping.LINEAR_INTERPOLATOR,
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
            status(unit, l2, u2, l2, u2, l2, u2, conserve(true), 0.1)
        };
        JsonNode want = ProbeData.load("resample").get("ERRORS");
        assertEquals(15, want.size(), "probe error cases");
        int[] wantFirst = new int[got.length];
        for (int i = 0; i < got.length; i++) {
            wantFirst[i] = want.get(i).asInt();
        }
        assertArrayEquals(wantFirst, got);
    }
}
