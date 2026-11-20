package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Resampling with the caller's own kernel ({@link Ukern1Calculator}) and interpolator ({@link
 * UinterpCalculator}): the oracle's cases for them, and what a calculator that fails part way does,
 * against {@code probes/resample_callback_probe.out}, whose C functions fail as jniast's callbacks
 * do, by setting the status to {@code AST__UK1ER} or {@code AST__UINER}. A calculator's exception
 * becomes an {@link AstException} with that status and the exception as its cause; an {@link Error}
 * passes through unchanged. Either way the output holds what C's holds when the status is set.
 */
class ResampleCallbackTest {

    /**
     * The probe's {@code tri_kernel}: a triangle reaching two pixels either side, scaled by the
     * probe's {@code params[1]}, which is {@code scale} here, failing at an offset of exactly 1.5
     * when {@code scale} is negative.
     */
    static final class TriKernel implements Ukern1Calculator {
        private final double scale;

        TriKernel(double scale) {
            this.scale = scale;
        }

        @Override
        public double ukern1(double offset) {
            double a = offset < 0.0 ? -offset : offset;
            if (scale < 0.0 && a == 1.5) {
                throw new IllegalArgumentException("tri_kernel: offset 1.5");
            }
            return (a < 2.0 ? 1.0 - 0.5 * a : 0.0) * (scale < 0.0 ? 1.0 : scale);
        }
    }

    /**
     * The probe's {@code uinterp_<X>} for {@code double} and {@code float} data: each output value
     * is the sum over the axes of {@code scale} (the probe's {@code params[0]}) times the input
     * coordinate, converted to the type, and each variance the first input value; a point beyond
     * the input grid's upper bound on the first axis is bad. It fails when {@code scale} is
     * negative.
     */
    static class ProbeUinterp extends UinterpCalculator {
        final double scale;

        ProbeUinterp(double scale) {
            this.scale = scale;
        }

        /**
         * The sum for point {@code point}, or null if the point is bad.
         *
         * @throws IllegalArgumentException if the scale is negative
         */
        final Double sum(int ndimIn, int[] ubndIn, double[][] coords, int point) {
            if (scale < 0.0) {
                throw new IllegalArgumentException("uinterp: negative scale");
            }
            if (coords[0][point] > ubndIn[0]) {
                return null;
            }
            double sum = 0.0;
            for (int idim = 0; idim < ndimIn; idim++) {
                sum += scale * coords[idim][point];
            }
            return sum;
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
            int nbad = 0;
            for (int point = 0; point < npoint; point++) {
                Double sum = sum(ndimIn, ubndIn, coords, point);
                if (sum == null) {
                    out[offset[point]] = badval;
                    if (outVar != null) {
                        outVar[offset[point]] = badval;
                    }
                    nbad++;
                } else {
                    out[offset[point]] = sum;
                    if (outVar != null) {
                        outVar[offset[point]] = in[0];
                    }
                }
            }
            return nbad;
        }

        @Override
        public int uinterpF(
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                float[] in,
                float[] inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                float badval,
                float[] out,
                float[] outVar) {
            int nbad = 0;
            for (int point = 0; point < npoint; point++) {
                Double sum = sum(ndimIn, ubndIn, coords, point);
                if (sum == null) {
                    out[offset[point]] = badval;
                    if (outVar != null) {
                        outVar[offset[point]] = badval;
                    }
                    nbad++;
                } else {
                    out[offset[point]] = (float) (double) sum;
                    if (outVar != null) {
                        outVar[offset[point]] = in[0];
                    }
                }
            }
            return nbad;
        }
    }

    /**
     * {@link ProbeUinterp} for every jniast type, converting the sum to an integer type as C's
     * cast does on the platform the oracle was made on: {@code long} and {@code int} saturate, and
     * {@code short} and {@code byte} go through {@code int}.
     */
    static final class ProbeUinterpAll extends ProbeUinterp {

        ProbeUinterpAll(double scale) {
            super(scale);
        }

        @Override
        public int uinterpL(
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                long[] in,
                long[] inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                long badval,
                long[] out,
                long[] outVar) {
            int nbad = 0;
            for (int point = 0; point < npoint; point++) {
                Double sum = sum(ndimIn, ubndIn, coords, point);
                if (sum == null) {
                    out[offset[point]] = badval;
                    if (outVar != null) {
                        outVar[offset[point]] = badval;
                    }
                    nbad++;
                } else {
                    out[offset[point]] = (long) (double) sum;
                    if (outVar != null) {
                        outVar[offset[point]] = in[0];
                    }
                }
            }
            return nbad;
        }

        @Override
        public int uinterpI(
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                int[] in,
                int[] inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                int badval,
                int[] out,
                int[] outVar) {
            int nbad = 0;
            for (int point = 0; point < npoint; point++) {
                Double sum = sum(ndimIn, ubndIn, coords, point);
                if (sum == null) {
                    out[offset[point]] = badval;
                    if (outVar != null) {
                        outVar[offset[point]] = badval;
                    }
                    nbad++;
                } else {
                    out[offset[point]] = (int) (double) sum;
                    if (outVar != null) {
                        outVar[offset[point]] = in[0];
                    }
                }
            }
            return nbad;
        }

        @Override
        public int uinterpS(
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                short[] in,
                short[] inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                short badval,
                short[] out,
                short[] outVar) {
            int nbad = 0;
            for (int point = 0; point < npoint; point++) {
                Double sum = sum(ndimIn, ubndIn, coords, point);
                if (sum == null) {
                    out[offset[point]] = badval;
                    if (outVar != null) {
                        outVar[offset[point]] = badval;
                    }
                    nbad++;
                } else {
                    out[offset[point]] = (short) (int) (double) sum;
                    if (outVar != null) {
                        outVar[offset[point]] = in[0];
                    }
                }
            }
            return nbad;
        }

        @Override
        public int uinterpB(
                int ndimIn,
                int[] lbndIn,
                int[] ubndIn,
                byte[] in,
                byte[] inVar,
                int npoint,
                int[] offset,
                double[][] coords,
                ResampleFlags flags,
                byte badval,
                byte[] out,
                byte[] outVar) {
            int nbad = 0;
            for (int point = 0; point < npoint; point++) {
                Double sum = sum(ndimIn, ubndIn, coords, point);
                if (sum == null) {
                    out[offset[point]] = badval;
                    if (outVar != null) {
                        outVar[offset[point]] = badval;
                    }
                    nbad++;
                } else {
                    out[offset[point]] = (byte) (int) (double) sum;
                    if (outVar != null) {
                        outVar[offset[point]] = in[0];
                    }
                }
            }
            return nbad;
        }
    }

    /** The probe's failing kernel: 1 - |offset| within one pixel, throwing on call {@code failAt}. */
    private static final class FailingKernel implements Ukern1Calculator {
        private final int failAt;
        private final Throwable failure;
        private int ncall;

        FailingKernel(int failAt, Throwable failure) {
            this.failAt = failAt;
            this.failure = failure;
        }

        @Override
        public double ukern1(double offset) throws Exception {
            ncall++;
            if (ncall == failAt) {
                throwIt(failure);
            }
            double a = offset < 0.0 ? -offset : offset;
            return a < 1.0 ? 1.0 - a : 0.0;
        }
    }

    /**
     * The probe's failing interpolator for {@code double} data: each output value is its first
     * input coordinate; call {@code failAt} sets only the first half of its points, then throws.
     */
    private static final class FailingUinterp extends UinterpCalculator {
        private final int failAt;
        private final Throwable failure;
        private int ncall;

        FailingUinterp(int failAt, Throwable failure) {
            this.failAt = failAt;
            this.failure = failure;
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
                double[] outVar)
                throws Exception {
            ncall++;
            int n = ncall == failAt ? npoint / 2 : npoint;
            for (int point = 0; point < n; point++) {
                out[offset[point]] = coords[0][point];
            }
            if (ncall == failAt) {
                throwIt(failure);
            }
            return 0;
        }
    }

    private static void throwIt(Throwable t) throws Exception {
        if (t instanceof Exception) {
            throw (Exception) t;
        }
        throw (Error) t;
    }

    /** The case's text as the probe writes it. */
    private static String text(int status, int nbad, double[] out) {
        StringBuilder sb = new StringBuilder();
        sb.append("status ").append(status).append("\nnbad ").append(nbad).append("\nout");
        for (int k = 0; k < out.length; k++) {
            sb.append(k % 10 == 0 ? "\n" : " ").append(CFormat.format("%.17g", out[k]));
        }
        return sb.append('\n').toString();
    }

    /** The data of the probe's cases: pixel k holds k + 1. */
    private static double[] data(int n) {
        double[] in = new double[n];
        for (int k = 0; k < n; k++) {
            in[k] = k + 1;
        }
        return in;
    }

    private static final String KERNEL_MESSAGE = "Error signalled by user-supplied 1-d interpolation kernel.";
    private static final String UINTERP_MESSAGE = "Error signalled by user-supplied sub-pixel interpolation function.";

    /**
     * Runs {@code call}, which resamples into {@code out}, returning the result as the probe writes
     * it. With {@code expected} null the call must succeed. With an {@link Error} it must throw
     * that error unchanged, which carries no status, so the result has {@code status}, C's, for
     * comparing the output. With an exception it must throw an AstException with status {@code
     * status}, the message {@code message} and the exception as its cause.
     */
    private static String result(IntSupplier call, Throwable expected, int status, String message, double[] out) {
        if (expected == null) {
            return text(0, call.getAsInt(), out);
        }
        if (expected instanceof Error) {
            assertSame(expected, assertThrows(Error.class, call::getAsInt));
            return text(status, 0, out);
        }
        AstException e = assertThrows(AstException.class, call::getAsInt);
        assertSame(expected, e.getCause());
        assertEquals(status, e.getStatus());
        assertTrue(e.getMessage().contains(message), e.getMessage());
        return text(e.getStatus(), 0, out);
    }

    /**
     * Runs the probe's kernel case on an {@code ndim}-dimensional UnitMap grid of {@code dim}
     * pixels a side with {@code kernel}, returning the result as the probe writes it; {@code
     * expected} is the exception the call should throw, or null.
     */
    private static String kernelCase(int ndim, int dim, Ukern1Calculator kernel, Throwable expected) {
        int n = 1;
        int[] lbnd = new int[ndim];
        int[] ubnd = new int[ndim];
        for (int i = 0; i < ndim; i++) {
            n *= dim;
            lbnd[i] = 1;
            ubnd[i] = dim;
        }
        double[] in = data(n);
        double[] out = new double[n];
        Arrays.fill(out, 77.0);
        Mapping.Interpolator interp = Mapping.Interpolator.ukern1(kernel, 1);
        Mapping map = new UnitMap(ndim);
        IntSupplier call = () -> map.resampleD(
                ndim,
                lbnd,
                ubnd,
                in,
                null,
                interp,
                new ResampleFlags(),
                0.0,
                50,
                -1.0,
                ndim,
                lbnd,
                ubnd,
                lbnd,
                ubnd,
                out,
                null);
        return result(call, expected, AstException.AST__UK1ER, "astResampleD(UnitMap): " + KERNEL_MESSAGE, out);
    }

    /**
     * Runs the probe's interpolator case with {@code calc}, returning the result as the probe
     * writes it; {@code expected} is the exception the call should throw, or null.
     */
    private static String uinterpCase(UinterpCalculator calc, Throwable expected) {
        int[] lbnd = {1};
        int[] ubnd = {4100};
        double[] in = data(4100);
        double[] out = new double[4100];
        Arrays.fill(out, 77.0);
        Mapping.Interpolator interp = Mapping.Interpolator.uinterp(calc);
        ResampleFlags flags = new ResampleFlags();
        flags.setConserveFlux(true);
        Mapping map = new ZoomMap(1, 2.0);
        IntSupplier call = () -> map.resampleD(
                1, lbnd, ubnd, in, null, interp, flags, 0.1, 10000, -1.0, 1, lbnd, ubnd, lbnd, ubnd, out, null);
        return result(call, expected, AstException.AST__UINER, "astResampleD(ZoomMap): " + UINTERP_MESSAGE, out);
    }

    private static final Map<String, String> PROBE = ProbeOutput.load("resample_callback_probe");

    /** A checked exception, a runtime exception and an error, each thrown by a calculator. */
    private static Throwable[] failures() {
        return new Throwable[] {
            new IOException("cannot read the kernel"),
            new IllegalStateException("kernel not ready"),
            new AssertionError("kernel assertion")
        };
    }

    @Test
    void aFailingKernelLeavesWhatCLeaves() {
        for (Throwable t : failures()) {
            assertEquals(PROBE.get("kernel_1d"), kernelCase(1, 10, new FailingKernel(5, t), t), t.toString());
            assertEquals(PROBE.get("kernel_2d"), kernelCase(2, 4, new FailingKernel(11, t), t), t.toString());
            assertEquals(PROBE.get("kernel_3d"), kernelCase(3, 3, new FailingKernel(20, t), t), t.toString());
        }
        assertEquals(PROBE.get("kernel_none"), kernelCase(1, 10, new FailingKernel(1000, null), null));
    }

    @Test
    void aFailingInterpolatorLeavesWhatCLeaves() {
        for (Throwable t : failures()) {
            assertEquals(PROBE.get("uinterp_second"), uinterpCase(new FailingUinterp(2, t), t), t.toString());
            assertEquals(PROBE.get("uinterp_first"), uinterpCase(new FailingUinterp(1, t), t), t.toString());
        }
        assertEquals(PROBE.get("uinterp_none"), uinterpCase(new FailingUinterp(1000, null), null));
    }

    @Test
    void aCalculatorStatusIsJniasts() {
        AstException e = assertThrows(
                AstException.class, () -> kernelCase(1, 10, new FailingKernel(1, new IOException("x")), null));
        assertEquals(AstException.AST__UK1ER, e.getStatus());
        assertInstanceOf(IOException.class, e.getCause());
        e = assertThrows(AstException.class, () -> uinterpCase(new FailingUinterp(1, new IOException("x")), null));
        assertEquals(AstException.AST__UINER, e.getStatus());
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void anInterpolatorWithoutTheDataTypeFails() {
        int[] lbnd = {1};
        int[] ubnd = {10};
        int[] out = new int[10];
        AstException e = assertThrows(
                AstException.class,
                () -> new UnitMap(1)
                        .resampleI(
                                1,
                                lbnd,
                                ubnd,
                                new int[10],
                                null,
                                Mapping.Interpolator.uinterp(new ProbeUinterp(1.0)),
                                new ResampleFlags(),
                                0.0,
                                50,
                                -1,
                                1,
                                lbnd,
                                ubnd,
                                lbnd,
                                ubnd,
                                out,
                                null));
        assertEquals(AstException.AST__UINER, e.getStatus());
        assertInstanceOf(UnsupportedOperationException.class, e.getCause());
    }

    /** The oracle's cases for the caller's kernel and interpolator, of double and float data. */
    @Test
    void callersFunctionsMatchTheOracle() {
        int ran = 0;
        for (JsonNode c : ProbeData.load("resample").get("CASES")) {
            String scheme = c.get(10).asText();
            String type = c.get(2).asText();
            if (("ukern1".equals(scheme) || "uinterp".equals(scheme)) && ("D".equals(type) || "F".equals(type))) {
                assertNull(ResampleOracleTest.mismatch(c));
                ran++;
            }
        }
        assertEquals(6, ran, "cases run");
    }
}
