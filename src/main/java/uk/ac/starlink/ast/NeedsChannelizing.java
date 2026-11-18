package uk.ac.starlink.ast;

/**
 * Declares that an {@code AstObject} requires object-specific preparation before writing to and/or
 * after reading from a {@code Channel}. Any object implementing this interface will have the
 * {@code channelize} method called on it before it is written to a sink by a Channel, and the
 * {@code unChannelize} method called on it after it is read from a source by a Channel. No other
 * {@code uk.ac.starlink.ast} methods will be called on it between calls to {@code channelize} and
 * {@code unChannelize}, so {@code channelize} may leave the object in an unusable state, as long
 * as {@code unChannelize} undoes this situation.
 *
 * <p>Only the Object given to {@link Channel#write} or returned by {@link Channel#read} is treated
 * this way, not the Objects it holds.
 */
public interface NeedsChannelizing {

    /**
     * Does object-specific preparation for output to a {@code Channel}. This method is called by
     * the writing methods of a Channel prior to doing the actual write.
     *
     * <p>The actions of this method should be reversed by the {@code unChannelize} method.
     *
     * @throws Exception the implementation may cause any exception to be thrown. This will scupper
     *     the write.
     */
    void channelize() throws Exception;

    /**
     * Reverses the effects of {@code channelize}. This method is called by the reading methods of
     * a Channel after doing the actual read. It is also called by the writing methods of a Channel
     * after doing the actual write, to undo possible destructive effects of the {@code channelize}
     * call.
     *
     * @throws Exception the implementation may cause any exception to be thrown. This will scupper
     *     the read.
     */
    void unChannelize() throws Exception;
}
