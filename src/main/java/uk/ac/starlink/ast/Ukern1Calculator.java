package uk.ac.starlink.ast;

/**
 * A 1-dimensional interpolation kernel supplied by the caller, for resampling with {@link
 * Mapping.Interpolator#ukern1}: C's {@code AST__UKERN1} scheme, in which the kernel is applied to
 * each dimension of the input grid in turn. Resampling is not available in this version, so the
 * kernel is never called.
 */
@FunctionalInterface
public interface Ukern1Calculator {

    /**
     * Returns the value of the kernel at an offset from the interpolation point.
     *
     * @param offset the offset, in pixels, of an input pixel centre from the interpolation point
     * @return the kernel value; the values over all the pixels used need not be normalised
     * @throws Exception if the value cannot be found, which ends the resampling with an exception
     */
    double ukern1(double offset) throws Exception;
}
