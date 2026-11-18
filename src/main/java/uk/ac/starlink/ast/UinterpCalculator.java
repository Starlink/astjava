package uk.ac.starlink.ast;

/**
 * An interpolation function supplied by the caller, for resampling with {@link
 * Mapping.Interpolator#uinterp}: C's {@code AST__UINTERP} scheme. A subclass overrides the method
 * for each data type it handles; the others throw {@link UnsupportedOperationException}. The
 * method for the type of the data is called once for each block of output pixels.
 *
 * <p>Each method is given the input grid and the positions at which to interpolate, and returns
 * the number of output pixels it set to the bad value. Its parameters are:
 *
 * <ul>
 *   <li>{@code ndimIn}: the number of dimensions of the input grid.
 *   <li>{@code lbndIn}, {@code ubndIn}: the pixel index bounds of the input grid in each
 *       dimension.
 *   <li>{@code in}, {@code inVar}: the input data and their variances, the arrays given to the
 *       resampling method, with the first dimension varying fastest; {@code inVar} is null
 *       unless both input and output variances were given.
 *   <li>{@code npoint}: the number of points at which to interpolate.
 *   <li>{@code offset}: for each point, the index in {@code out} at which to store its value.
 *   <li>{@code coords}: {@code ndimIn} arrays of {@code npoint} input-grid coordinates of the
 *       points, bad where a point's position is unknown.
 *   <li>{@code flags}: the flags object given to the resampling method.
 *   <li>{@code badval}: the bad value of the data.
 *   <li>{@code out}, {@code outVar}: the output arrays given to the resampling method, where the
 *       interpolated values and their variances (if {@code outVar} is not null) are stored; an
 *       element no point names should be left as it is.
 * </ul>
 *
 * <p>A point whose position is bad should be given {@code badval}, or left as it is when the NoBad
 * flag is set, and an input pixel equal to {@code badval} treated as bad only when the UseBad flag
 * is set. The number returned is of the points given no value.
 *
 * <p>An exception a method throws ends the resampling with an {@link AstException} whose status is
 * {@link AstException#AST__UINER} and whose cause is that exception; an {@link Error} it throws
 * passes through unchanged. Either way, the output holds what the earlier blocks and the failing
 * call left in it.
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
     * @throws Exception if the interpolation fails, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UINER} and this exception as its cause
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
     * @throws Exception if the interpolation fails, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UINER} and this exception as its cause
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
     * @throws Exception if the interpolation fails, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UINER} and this exception as its cause
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
     * @throws Exception if the interpolation fails, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UINER} and this exception as its cause
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
     * @throws Exception if the interpolation fails, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UINER} and this exception as its cause
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
     * @throws Exception if the interpolation fails, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UINER} and this exception as its cause
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
