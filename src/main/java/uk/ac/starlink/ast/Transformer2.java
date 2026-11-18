package uk.ac.starlink.ast;

/**
 * Defines a custom two-dimensional coordinate transformation function. This abstract subclass of
 * {@code Transformer} is provided for convenience when only a 2-d transformation is required.
 * Subclasses need to implement the {@code tran2} method, which is somewhat more convenient than
 * {@code Transformer}'s {@code tranP} method.
 */
public abstract class Transformer2 extends Transformer {

    /** Constructs a Transformer2. */
    public Transformer2() {}

    /**
     * Indicates whether this {@code Transformer} is able to transform between coordinate spaces
     * with the given dimensionalities.
     *
     * @param nin the number of coordinates of an input point
     * @param nout the number of coordinates of an output point
     * @return true only if both {@code nin} and {@code nout} are equal to 2
     */
    @Override
    public final boolean canTransformCoords(int nin, int nout) {
        return nin == 2 && nout == 2;
    }

    /**
     * Implements {@code Transformer}'s {@code tranP} method in terms of this class's {@code tran2}
     * method.
     *
     * @param npoint the number of points to be transformed
     * @param ncoordIn the number of coordinates of each input point (2)
     * @param in a two-element array holding the arrays of {@code npoint} X and Y input coordinates
     * @param forward {@code true} for the forward transformation, {@code false} for the inverse
     * @param ncoordOut the number of coordinates of each output point (2)
     * @return the result of {@code tran2}
     * @throws Exception if {@code tran2} throws it
     */
    @Override
    public final double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut)
            throws Exception {
        return tran2(npoint, in[0], in[1], forward);
    }

    /**
     * Transforms points from 2-d input coordinates to 2-d output coordinates. This method does the
     * work of the {@code Transformer2}, taking input x and y coordinate arrays and returning a
     * two-element array consisting of the x and y output coordinate arrays.
     *
     * <p>This method will only be called with {@code forward} set true if a prior call of the
     * {@code hasForward} method has returned true, and will only be called with {@code forward}
     * set false if a prior call of the {@code hasInverse} method has returned true.
     *
     * @param npoint the number of points to be transformed
     * @param xin an array of {@code npoint} elements representing the X coordinates to be
     *     transformed
     * @param yin an array of {@code npoint} elements representing the Y coordinates to be
     *     transformed
     * @param forward {@code true} if the forward transformation is to be used, {@code false} if
     *     its inverse transformation is to be used
     * @return a two-element array; the first element is an {@code npoint}-element array of the
     *     output X coordinates, and the second element is an {@code npoint}-element array of the
     *     output Y coordinates
     * @throws Exception if an error occurs during the transformation, in which case the
     *     transformation is aborted
     */
    public abstract double[][] tran2(int npoint, double[] xin, double[] yin, boolean forward) throws Exception;
}
