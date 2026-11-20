package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * The flags and arrays the public {@code rebinD}, {@code rebinF} and {@code rebinI} pass to C's
 * {@code astRebin<X>}, against {@code probes/rebin_jniast_probe.c}, which calls C with every
 * combination of the flags {@code AST__USEBAD} and {@code AST__USEVAR} and of the variance arrays.
 * This test has no Rust counterpart.
 *
 * <p>jniast passed {@code AST__USEBAD} alone, and the output variance array only when the input
 * one was given, so C zeroed the output variances and never filled them. Here, with both variance
 * arrays given, a call must give C's result with {@code AST__USEVAR} added; with only the input
 * variances given, C's result without {@code AST__USEVAR} and without output variances; with only
 * the output variances given, C's result without either variance array, the output variances
 * left as they were. The probe's cases with both arrays but without {@code AST__USEVAR}, jniast's
 * call, have output variances that are all zero, or bad where the weight is below {@code wlim}.
 *
 * <p>The input is a 2-D grid of 4 by 3 pixels rebinned through a ShiftMap of (0.5, -0.25) into a
 * grid of 6 by 5 pixels, with nearest-neighbour and linear spreading and a {@code wlim} of 0 and
 * 0.6: data pixel k holds 1.5 k - 4 (D, F) or 3 k - 7 (I), except the bad value -999 at k = 5;
 * variance pixel k holds 0.5 k + 0.25 (D, F) or k + 1 (I), except -999 at k = 8. The output
 * arrays start at 55.
 */
class RebinJniastTest {

    private static final int NIN = 12;
    private static final int NOUT = 30;
    private static final int[] LBND_IN = {1, 1};
    private static final int[] UBND_IN = {4, 3};
    private static final int[] LBND_OUT = {0, 0};
    private static final int[] UBND_OUT = {5, 4};
    private static final Mapping MAP = new ShiftMap(new double[] {0.5, -0.25});

    private static final String[] SCHEMES = {"nearest", "linear"};
    private static final Mapping.Spreader[] SPREADERS = {Mapping.NEAREST_SPREADER, Mapping.LINEAR_SPREADER};
    private static final String[] WLIMS = {"0", "0.6"};
    private static final double[] WLIM_VALUES = {0.0, 0.6};

    private static double data(int k) {
        return k == 5 ? -999.0 : 1.5 * k - 4.0;
    }

    private static double var(int k) {
        return k == 8 ? -999.0 : 0.5 * k + 0.25;
    }

    private static int dataI(int k) {
        return k == 5 ? -999 : 3 * k - 7;
    }

    private static int varI(int k) {
        return k == 8 ? -999 : k + 1;
    }

    /** The case's text as the probe writes it. */
    private static String text(String[] out, String[] outVar) {
        return "out " + String.join(" ", out) + "\nvar " + String.join(" ", outVar) + "\n";
    }

    /**
     * Rebins with the type {@code type}, giving the input variances if {@code withIn} and the
     * output variances if {@code withOut}, and returns the outputs as the probe writes them.
     */
    private static String run(String type, int scheme, int wlim, boolean usebad, boolean withIn, boolean withOut) {
        String[] o = new String[NOUT];
        String[] ov = new String[NOUT];
        switch (type) {
            case "D": {
                double[] in = new double[NIN];
                double[] inVar = new double[NIN];
                for (int k = 0; k < NIN; k++) {
                    in[k] = data(k);
                    inVar[k] = var(k);
                }
                double[] out = new double[NOUT];
                double[] outVar = new double[NOUT];
                Arrays.fill(out, 55.0);
                Arrays.fill(outVar, 55.0);
                MAP.rebinD(
                        WLIM_VALUES[wlim],
                        2,
                        LBND_IN,
                        UBND_IN,
                        in,
                        withIn ? inVar : null,
                        SPREADERS[scheme],
                        usebad,
                        0.0,
                        100,
                        -999.0,
                        2,
                        LBND_OUT,
                        UBND_OUT,
                        LBND_IN,
                        UBND_IN,
                        out,
                        withOut ? outVar : null);
                for (int k = 0; k < NOUT; k++) {
                    o[k] = CFormat.format("%.17g", out[k]);
                    ov[k] = CFormat.format("%.17g", outVar[k]);
                }
                break;
            }
            case "F": {
                float[] in = new float[NIN];
                float[] inVar = new float[NIN];
                for (int k = 0; k < NIN; k++) {
                    in[k] = (float) data(k);
                    inVar[k] = (float) var(k);
                }
                float[] out = new float[NOUT];
                float[] outVar = new float[NOUT];
                Arrays.fill(out, 55.0f);
                Arrays.fill(outVar, 55.0f);
                MAP.rebinF(
                        WLIM_VALUES[wlim],
                        2,
                        LBND_IN,
                        UBND_IN,
                        in,
                        withIn ? inVar : null,
                        SPREADERS[scheme],
                        usebad,
                        0.0,
                        100,
                        -999.0f,
                        2,
                        LBND_OUT,
                        UBND_OUT,
                        LBND_IN,
                        UBND_IN,
                        out,
                        withOut ? outVar : null);
                for (int k = 0; k < NOUT; k++) {
                    o[k] = CFormat.format("%.9g", out[k]);
                    ov[k] = CFormat.format("%.9g", outVar[k]);
                }
                break;
            }
            case "I": {
                int[] in = new int[NIN];
                int[] inVar = new int[NIN];
                for (int k = 0; k < NIN; k++) {
                    in[k] = dataI(k);
                    inVar[k] = varI(k);
                }
                int[] out = new int[NOUT];
                int[] outVar = new int[NOUT];
                Arrays.fill(out, 55);
                Arrays.fill(outVar, 55);
                MAP.rebinI(
                        WLIM_VALUES[wlim],
                        2,
                        LBND_IN,
                        UBND_IN,
                        in,
                        withIn ? inVar : null,
                        SPREADERS[scheme],
                        usebad,
                        0.0,
                        100,
                        -999,
                        2,
                        LBND_OUT,
                        UBND_OUT,
                        LBND_IN,
                        UBND_IN,
                        out,
                        withOut ? outVar : null);
                for (int k = 0; k < NOUT; k++) {
                    o[k] = Integer.toString(out[k]);
                    ov[k] = Integer.toString(outVar[k]);
                }
                break;
            }
            default:
                throw new IllegalArgumentException("no type " + type);
        }
        return text(o, ov);
    }

    @Test
    void variancesAreRebinnedWhenBothArraysAreGiven() {
        Map<String, String> want = ProbeOutput.load("rebin_jniast_probe");
        int compared = 0;
        for (String type : new String[] {"D", "F", "I"}) {
            for (int s = 0; s < SCHEMES.length; s++) {
                for (int w = 0; w < WLIMS.length; w++) {
                    for (boolean usebad : new boolean[] {false, true}) {
                        String prefix = type + "_" + SCHEMES[s] + "_wlim" + WLIMS[w] + "_";
                        String flag = usebad ? "usebad" : "none";
                        String withVar = usebad ? "usebad_usevar" : "usevar";

                        String name = prefix + withVar + "_both";
                        assertEquals(want.get(name), run(type, s, w, usebad, true, true), name);
                        name = prefix + flag + "_in";
                        assertEquals(want.get(name), run(type, s, w, usebad, true, false), name);
                        name = prefix + flag + "_none";
                        assertEquals(
                                want.get(name),
                                run(type, s, w, usebad, false, true),
                                name + " (output variances only)");
                        assertEquals(want.get(name), run(type, s, w, usebad, false, false), name);
                        compared += 4;

                        // jniast's call: both arrays without AST__USEVAR zeroes the output variances,
                        // and wlim sets some bad.
                        String jniast = want.get(prefix + flag + "_both");
                        String vars =
                                jniast.substring(jniast.indexOf("\nvar ") + 5).trim();
                        for (String v : vars.split(" ", -1)) {
                            double d = Double.parseDouble(v);
                            assertTrue(d == 0.0 || d == -999.0, prefix + flag + "_both: " + vars);
                        }
                    }
                }
            }
        }
        assertEquals(96, compared, "calls compared");
        assertEquals(192, want.size(), "probe cases");
    }
}
