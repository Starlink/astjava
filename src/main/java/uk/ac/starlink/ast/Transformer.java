package uk.ac.starlink.ast;

/**
 * Defines a custom general coordinate transformation function. Objects subclassing this abstract
 * class can be used to construct a {@code Mapping} (an {@link IntraMap}) with a user-defined
 * transformation function.
 *
 * <p>An {@code IntraMap} is written to a {@link Channel} with the name of its transformation
 * function, not the {@code Transformer}, and is read back with the {@code Transformer} that name
 * stands for (see {@link IntraMap}).
 *
 * <p>The {@code tranP} method may be called from several threads at once, since copies of an
 * {@code IntraMap} share its {@code Transformer} and no lock is held while it is called: a
 * {@code Transformer} must be thread-safe.
 *
 * <p>This is implemented as an abstract class (rather than an interface) so that additional flags
 * along the lines of the {@code simpFI} method can be introduced in the future without breaking
 * existing code.
 */
public abstract class Transformer {

    /** Constructs a Transformer. */
    public Transformer() {}

    /**
     * Indicates whether this {@code Transformer} is able to transform between coordinate spaces
     * with the given dimensionalities.
     *
     * @param nin the number of coordinates of an input point
     * @param nout the number of coordinates of an output point
     * @return the method should return {@code true} if this {@code Transformer}'s {@code tranP}
     *     method will be able to transform points with {@code nin} coordinates to points with
     *     {@code nout} coordinates, and {@code false} otherwise
     */
    public abstract boolean canTransformCoords(int nin, int nout);

    /**
     * Indicates whether this {@code Transformer} can perform a forward transformation (whether the
     * transformation method may be invoked with {@code forward} set true).
     *
     * <p>The default implementation returns {@code true}; subclasses should override this if the
     * forward transformation is not available.
     *
     * @return whether the forward transformation is available
     */
    public boolean hasForward() {
        return true;
    }

    /**
     * Indicates whether this {@code Transformer} can perform an inverse transformation (whether the
     * transformation method may be invoked with {@code forward} set false).
     *
     * <p>The default implementation returns {@code true}; subclasses should override this if the
     * inverse transformation is not available.
     *
     * @return whether the inverse transformation is available
     */
    public boolean hasInverse() {
        return true;
    }

    /**
     * Indicates whether a forward transformation followed by an inverse transformation may always
     * be considered to restore the original coordinates. It is not necessary that both forward
     * and inverse transformations have been implemented.
     *
     * <p>The default implementation returns {@code false}; subclasses should override this if such
     * simplifications are always valid.
     *
     * @return whether forward followed by inverse is a unit transformation
     */
    public boolean simpFI() {
        return false;
    }

    /**
     * Indicates whether an inverse transformation followed by a forward transformation may always
     * be considered to restore the original coordinates. It is not necessary that both forward
     * and inverse transformations have been implemented.
     *
     * <p>The default implementation returns {@code false}; subclasses should override this if such
     * simplifications are always valid.
     *
     * @return whether inverse followed by forward is a unit transformation
     */
    public boolean simpIF() {
        return false;
    }

    /**
     * Transforms points from input coordinates to output coordinates. This method does the work of
     * the {@code Transformer}, taking an array of input coordinate arrays and returning an array
     * of output coordinate arrays.
     *
     * <p>This method will only be called with {@code forward} set true if a prior call of the
     * {@code hasForward} method has returned true, and will only be called with {@code forward}
     * set false if a prior call of the {@code hasInverse} method has returned true.
     *
     * <p>If {@code forward} is true, then this method will only be called with values of {@code
     * ncoordIn}, {@code ncoordOut} for which a prior call of {@code
     * canTransformCoords(ncoordIn, ncoordOut)} has returned true. If {@code forward} is false,
     * then this method will only be called with values of {@code ncoordIn}, {@code ncoordOut} for
     * which a prior call of {@code canTransformCoords(ncoordOut, ncoordIn)} has returned true.
     *
     * @param npoint the number of points to be transformed
     * @param ncoordIn the number of coordinates being supplied for each input point (the
     *     dimensionality of the input space)
     * @param in an array of {@code ncoordIn} arrays, each containing {@code npoint} elements.
     *     These give the coordinates of each point to transform.
     * @param forward {@code true} if the forward transformation is to be used, {@code false} if
     *     its inverse transformation is to be used
     * @param ncoordOut the number of coordinates being generated by the transformation for each
     *     output point (the dimensionality of the output space). This need not be equal to {@code
     *     ncoordIn}.
     * @return an array of {@code ncoordOut} arrays, each containing {@code npoint} elements. These
     *     give the coordinates of the transformed points.
     * @throws Exception if an error occurs during the transformation, in which case the
     *     transformation is aborted
     */
    public abstract double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut)
            throws Exception;

    /**
     * Gives a brief indication of the purpose of this {@code Transformer}.
     *
     * @return a short (one-line) string describing the purpose of this transformation function
     */
    public abstract String getPurpose();

    /**
     * Names the author of this {@code Transformer}.
     *
     * @return a string giving the name of the author of this transformation function
     */
    public abstract String getAuthor();

    /**
     * Gives contact details for the author of this {@code Transformer}.
     *
     * @return a string giving contact details, for instance an e-mail or WWW address, for the
     *     author
     */
    public abstract String getContact();
}
