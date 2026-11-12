package uk.ac.starlink.ast;

/**
 * An interpolation function supplied by the caller, for resampling with {@link
 * Mapping.Interpolator#uinterp}: C's {@code AST__UINTERP} scheme. A subclass overrides the method
 * for each data type it handles; the others throw {@link UnsupportedOperationException}.
 * Resampling is not available in this version, so the methods are never called.
 *
 * <p>Each method is given the input grid and the positions at which to interpolate, and returns
 * the number of output pixels it set to the bad value. Its parameters are:
 *
 * <ul>
 *   <li>{@code ndimIn}: the number of dimensions of the input grid.
 *   <li>{@code lbndIn}, {@code ubndIn}: the pixel index bounds of the input grid in each
 *       dimension.
 *   <li>{@code in}, {@code inVar}: the input data and their variances (or null), with the first
 *       dimension varying fastest.
 *   <li>{@code npoint}: the number of points at which to interpolate.
 *   <li>{@code offset}: for each point, the index in {@code out} at which to store its value.
 *   <li>{@code coords}: {@code ndimIn} arrays of {@code npoint} input-grid coordinates of the
 *       points, bad where a point's position is unknown.
 *   <li>{@code flags}: the resampling flags.
 *   <li>{@code badval}: the bad value of the data.
 *   <li>{@code out}, {@code outVar}: where the interpolated values and their variances (if
 *       {@code outVar} is not null) are stored.
 * </ul>
 *
 * <p>A method may throw any exception, which ends the resampling with an exception.
 */
// Abstract, as jniast's is, so that only a subclass that handles some data type is made.
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class UinterpCalculator {

    /** Constructs an interpolation function. */
    public UinterpCalculator() {}

    private static UnsupportedOperationException unsupported(String type) {
        return new UnsupportedOperationException("This interpolation function does not handle " + type + " data");
    }

    /**
     * Interpolates {@code byte} data. This implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @return the number of output pixels set to the bad value
     * @throws Exception if the interpolation fails
     */
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
            byte[] outVar)
            throws Exception {
        throw unsupported("byte");
    }

    /**
     * Interpolates {@code short} data. This implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @return the number of output pixels set to the bad value
     * @throws Exception if the interpolation fails
     */
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
            short[] outVar)
            throws Exception {
        throw unsupported("short");
    }

    /**
     * Interpolates {@code int} data. This implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @return the number of output pixels set to the bad value
     * @throws Exception if the interpolation fails
     */
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
            int[] outVar)
            throws Exception {
        throw unsupported("int");
    }

    /**
     * Interpolates {@code long} data. This implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @return the number of output pixels set to the bad value
     * @throws Exception if the interpolation fails
     */
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
            long[] outVar)
            throws Exception {
        throw unsupported("long");
    }

    /**
     * Interpolates {@code float} data. This implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @return the number of output pixels set to the bad value
     * @throws Exception if the interpolation fails
     */
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
            float[] outVar)
            throws Exception {
        throw unsupported("float");
    }

    /**
     * Interpolates {@code double} data. This implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @return the number of output pixels set to the bad value
     * @throws Exception if the interpolation fails
     */
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
        throw unsupported("double");
    }
}
