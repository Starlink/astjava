package uk.ac.starlink.ast;

/**
 * Corresponds to the IVOA STCResourceProfile class. The StcResourceProfile class is a sub-class of
 * {@link Stc} used to describe the coverage of the datasets contained in some VO resource.
 *
 * <p>See http://hea-www.harvard.edu/~arots/nvometa/STC.html
 *
 * <p>Ported from C {@code stcresourceprofile.c}.
 */
public class StcResourceProfile extends Stc {

    /**
     * Constructs a new StcResourceProfile.
     *
     * @param region the encapsulated region; a copy is held
     * @param coords the AstroCoords elements associated with this Stc, or null for none; copies of
     *     their Regions are held
     * @throws NullPointerException if {@code region} or an element of {@code coords} is null
     * @throws AstException with status {@code AST__STCKEY} if an AstroCoords element has a number
     *     of axis names other than the number of axes of {@code region}, or a Region that cannot
     *     be converted to the coordinate system of {@code region}
     */
    public StcResourceProfile(Region region, AstroCoords[] coords) {
        this(region, astroCoordsToKeyMaps(coords));
    }

    /**
     * Constructs a new StcResourceProfile from AstroCoords elements given as KeyMaps (C's {@code
     * astStcResourceProfile}).
     *
     * @see Stc#Stc(Region, KeyMap[])
     */
    StcResourceProfile(Region region, KeyMap[] coords) {
        super(region, coords);
    }

    /** Loads an StcResourceProfile from its description (C's {@code astLoadStcResourceProfile}). */
    StcResourceProfile(NativeNode node) {
        super(node);
        node.section("StcResourceProfile");
    }

    @Override
    String dumpComment() {
        return "Resource coverage";
    }

    /**
     * Writes the StcResourceProfile class's part of a description (C's {@code Dump},
     * stcresourceprofile.c), which holds nothing of its own.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Stc", "An IVOA Space-Time-Coords object");
    }
}
