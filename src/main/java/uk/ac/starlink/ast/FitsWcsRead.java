package uk.ac.starlink.ast;

/**
 * The FITS-WCS read of a FitsChan: the FrameSet its cards describe in the FITS-WCS encoding (C's
 * {@code FitsToStore} and {@code FsetFromStore} for that encoding).
 */
final class FitsWcsRead {

    private FitsWcsRead() {}

    /**
     * The FrameSet the cards of {@code fc} describe, or null when they hold no WCS description.
     *
     * @throws UnsupportedOperationException always: reading the FITS-WCS encoding is not available
     */
    // Every call throws while the FITS-WCS read is not available; callers are kept so that the
    // read dispatch is in place.
    @SuppressWarnings("DoNotCallSuggester")
    static FrameSet read(FitsChan fc) {
        throw new UnsupportedOperationException("FitsChan: reading the FITS-WCS encoding is not available");
    }
}
