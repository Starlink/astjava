package uk.ac.starlink.ast;

/**
 * A 1-dimensional interpolation kernel supplied by the caller, for resampling with {@link
 * Mapping.Interpolator#ukern1}: C's {@code AST__UKERN1} scheme. Each input pixel within {@code
 * npix} pixels of the interpolation point along every axis is weighted by the product of the
 * kernel's values at its offsets from the point along each axis, and the output value is the
 * weighted mean. A good kernel is usually symmetric about zero, is 1 at zero, and is zero at every
 * other integer offset, so that the interpolated values pass through the data; it may be negative
 * in places.
 *
 * <p>The kernel is called as often and in the order C calls it. An exception it throws ends the
 * resampling with an {@link AstException} whose status is {@link AstException#AST__UK1ER} and
 * whose cause is that exception; an {@link Error} it throws passes through unchanged. Either way, the output pixels set before the failure keep their
 * values and the others are left as they were.
 */
@FunctionalInterface
public interface Ukern1Calculator {

    /**
     * Returns the value of the kernel at an offset from the interpolation point.
     *
     * @param offset the offset, in pixels, of an input pixel centre from the interpolation point
     * @return the kernel value; the values over all the pixels used need not be normalised
     * @throws Exception if the value cannot be found, which ends the resampling with an {@link
     *     AstException} with status {@code AST__UK1ER} and this exception as its cause
     */
    double ukern1(double offset) throws Exception;
}
