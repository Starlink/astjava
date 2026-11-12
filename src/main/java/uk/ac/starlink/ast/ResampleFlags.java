package uk.ac.starlink.ast;

/**
 * The flags that control resampling, which C combines into one {@code int} by OR-ing the values
 * of {@code AST__NOBAD}, {@code AST__USEBAD} and {@code AST__CONSERVEFLUX}. Each flag is false
 * unless set. Resampling is not available in this version.
 */
public class ResampleFlags {

    /** C's {@code AST__USEBAD} (mapping.h). */
    static final int AST__USEBAD = 32;

    /** C's {@code AST__CONSERVEFLUX} (mapping.h). */
    static final int AST__CONSERVEFLUX = 64;

    /** C's {@code AST__NOBAD} (mapping.h). */
    static final int AST__NOBAD = 2048;

    private boolean noBad;
    private boolean useBad;
    private boolean conserveFlux;

    /** Constructs a set of flags, all false. */
    public ResampleFlags() {}

    /**
     * Sets the NoBad flag: whether output pixels that cannot be given a value are left unchanged
     * rather than set to the bad value.
     *
     * @param noBad the flag
     */
    public void setNoBad(boolean noBad) {
        this.noBad = noBad;
    }

    /**
     * Gets the NoBad flag.
     *
     * @return the flag
     */
    public boolean getNoBad() {
        return noBad;
    }

    /**
     * Sets the UseBad flag: whether input pixels holding the bad value are recognised and left
     * out of the interpolation.
     *
     * @param useBad the flag
     */
    public void setUseBad(boolean useBad) {
        this.useBad = useBad;
    }

    /**
     * Gets the UseBad flag.
     *
     * @return the flag
     */
    public boolean getUseBad() {
        return useBad;
    }

    /**
     * Sets the ConserveFlux flag: whether the output values are scaled so that the total flux is
     * preserved.
     *
     * @param conserveFlux the flag
     */
    public void setConserveFlux(boolean conserveFlux) {
        this.conserveFlux = conserveFlux;
    }

    /**
     * Gets the ConserveFlux flag.
     *
     * @return the flag
     */
    public boolean getConserveFlux() {
        return conserveFlux;
    }

    /**
     * Returns the flags as C combines them: the OR of the values of the flags that are set.
     *
     * @return the combined flags
     */
    public int getFlagsInt() {
        int flags = 0;
        if (noBad) {
            flags |= AST__NOBAD;
        }
        if (useBad) {
            flags |= AST__USEBAD;
        }
        if (conserveFlux) {
            flags |= AST__CONSERVEFLUX;
        }
        return flags;
    }
}
