package uk.ac.starlink.ast;

/**
 * Defines a custom one-dimensional coordinate transformation function. This abstract subclass of
 * {@code Transformer} is provided for convenience when only a 1-d transformation is required.
 * Subclasses need to implement the {@code tran1} method, which is somewhat more convenient than
 * {@code Transformer}'s {@code tranP} method.
 */
public abstract class Transformer1 extends Transformer {

    /** Constructs a Transformer1. */
    public Transformer1() {}

    /**
     * Indicates whether this {@code Transformer} is able to transform between coordinate spaces
     * with the given dimensionalities.
     *
     * @param nin the number of coordinates of an input point
     * @param nout the number of coordinates of an output point
     * @return true only if both {@code nin} and {@code nout} are equal to 1
     */
    @Override
    public final boolean canTransformCoords(int nin, int nout) {
        return nin == 1 && nout == 1;
    }

    /**
     * Implements {@code Transformer}'s {@code tranP} method in terms of this class's {@code tran1}
     * method.
     *
     * @param npoint the number of points to be transformed
     * @param ncoordIn the number of coordinates of each input point (1)
     * @param in a one-element array holding the array of {@code npoint} input coordinates
     * @param forward {@code true} for the forward transformation, {@code false} for the inverse
     * @param ncoordOut the number of coordinates of each output point (1)
     * @return a one-element array holding the result of {@code tran1}
     * @throws Exception if {@code tran1} throws it
     */
    @Override
    public final double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut)
            throws Exception {
        return new double[][] {tran1(npoint, in[0], forward)};
    }

    /**
     * Transforms points from 1-d input coordinates to 1-d output coordinates. This method does the
     * work of the {@code Transformer1}, taking an input coordinate array and returning an output
     * coordinate array.
     *
     * <p>This method will only be called with {@code forward} set true if a prior call of the
     * {@code hasForward} method has returned true, and will only be called with {@code forward}
     * set false if a prior call of the {@code hasInverse} method has returned true.
     *
     * @param npoint the number of points to be transformed
     * @param xin an array of {@code npoint} elements representing the coordinates to be
     *     transformed
     * @param forward {@code true} if the forward transformation is to be used, {@code false} if
     *     its inverse transformation is to be used
     * @return an array of {@code npoint} elements giving the transformed coordinates
     * @throws Exception if an error occurs during the transformation, in which case the
     *     transformation is aborted
     */
    public abstract double[] tran1(int npoint, double[] xin, boolean forward) throws Exception;
}
