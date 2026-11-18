package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * {@code resampleF} and {@code resampleD} of data and variances holding NaN, with a bad value
 * that is NaN (as SPLAT and astgui pass it) and one that is a number, with and without {@code
 * setUseBad(true)}, against C's {@code astResampleF} and {@code astResampleD} as {@code
 * probes/resample_nan_probe.c} calls them. C recognises a bad input pixel by comparing it with the
 * bad value in the data type, so NaN data is never bad, and NaN reaches the output through the
 * arithmetic. This test has no Rust counterpart.
 *
 * <p>The input is a 1-D grid of ten pixels resampled through a ShiftMap of half a pixel into a
 * grid of the same bounds: data pixel k holds k + 1, except NaN at 3 and 7 and -999 at 5;
 * variance pixel k holds 0.5 k + 0.25, except NaN at 1 and -999 at 8; the output starts at 55.
 * Each output value and variance must equal C's bit for bit, or both be NaN.
 */
class ResampleNanTest {

    private static final int N = 10;

    private static double data(int k) {
        if (k == 3 || k == 7) {
            return Double.NaN;
        }
        if (k == 5) {
            return -999.0;
        }
        return k + 1.0;
    }

    private static double var(int k) {
        if (k == 1) {
            return Double.NaN;
        }
        if (k == 8) {
            return -999.0;
        }
        return 0.5 * k + 0.25;
    }

    /** The case's text as the probe writes it, from the bad pixel count and outputs. */
    private static String text(int nbad, double[] out, double[] outVar, boolean isFloat) {
        StringBuilder sb = new StringBuilder();
        sb.append("nbad ").append(nbad).append("\nout");
        for (double v : out) {
            sb.append(' ').append(format(v, isFloat));
        }
        sb.append("\nvar");
        for (double v : outVar) {
            sb.append(' ').append(format(v, isFloat));
        }
        return sb.append('\n').toString();
    }

    private static String format(double v, boolean isFloat) {
        return Double.isNaN(v) ? "nan" : CFormat.format(isFloat ? "%.9g" : "%.17g", v);
    }

    @Test
    void resamplingMatchesC() {
        Map<String, String> want = ProbeOutput.load("resample_nan_probe");
        Mapping map = new ShiftMap(new double[] {0.5});
        int[] lbnd = {1};
        int[] ubnd = {N};
        String[] schemes = {"nearest", "linear"};
        Mapping.Interpolator[] interps = {Mapping.NEAREST_INTERPOLATOR, Mapping.LINEAR_INTERPOLATOR};
        String[] bads = {"nan", "m999"};
        double[] badvals = {Double.NaN, -999.0};
        int ran = 0;
        for (int s = 0; s < 2; s++) {
            for (int b = 0; b < 2; b++) {
                for (int u = 0; u < 2; u++) {
                    ResampleFlags flags = new ResampleFlags();
                    flags.setUseBad(u == 1);
                    String suffix = schemes[s] + "_" + bads[b] + "_" + (u == 1 ? "usebad" : "nousebad");

                    float[] inF = new float[N];
                    float[] inVarF = new float[N];
                    float[] outF = new float[N];
                    float[] outVarF = new float[N];
                    double[] inD = new double[N];
                    double[] inVarD = new double[N];
                    double[] outD = new double[N];
                    double[] outVarD = new double[N];
                    for (int k = 0; k < N; k++) {
                        inF[k] = (float) data(k);
                        inVarF[k] = (float) var(k);
                        inD[k] = data(k);
                        inVarD[k] = var(k);
                    }
                    Arrays.fill(outF, 55.0f);
                    Arrays.fill(outVarF, 55.0f);
                    Arrays.fill(outD, 55.0);
                    Arrays.fill(outVarD, 55.0);

                    int nbad = map.resampleF(
                            1,
                            lbnd,
                            ubnd,
                            inF,
                            inVarF,
                            interps[s],
                            flags,
                            0.0,
                            100,
                            (float) badvals[b],
                            1,
                            lbnd,
                            ubnd,
                            lbnd,
                            ubnd,
                            outF,
                            outVarF);
                    double[] o = new double[N];
                    double[] ov = new double[N];
                    for (int k = 0; k < N; k++) {
                        o[k] = outF[k];
                        ov[k] = outVarF[k];
                    }
                    assertEquals(want.get("F_" + suffix), text(nbad, o, ov, true), "F_" + suffix);

                    nbad = map.resampleD(
                            1,
                            lbnd,
                            ubnd,
                            inD,
                            inVarD,
                            interps[s],
                            flags,
                            0.0,
                            100,
                            badvals[b],
                            1,
                            lbnd,
                            ubnd,
                            lbnd,
                            ubnd,
                            outD,
                            outVarD);
                    assertEquals(want.get("D_" + suffix), text(nbad, outD, outVarD, false), "D_" + suffix);
                    ran += 2;
                }
            }
        }
        assertEquals(want.size(), ran, "cases run");
    }
}
