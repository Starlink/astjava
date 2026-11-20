package uk.ac.starlink.ast;

/**
 * Corresponds to the IVOA SearchLocation class. The StcSearchLocation class is a sub-class of {@link
 * Stc} used to describe the coverage of a query.
 *
 * <p>See http://hea-www.harvard.edu/~arots/nvometa/STC.html
 *
 * <p>Ported from C {@code stcsearchlocation.c}.
 */
public class StcSearchLocation extends Stc {

    /**
     * Constructs a new StcSearchLocation.
     *
     * @param region the encapsulated region; a copy is held
     * @param coords the AstroCoords elements associated with this Stc, or null for none; copies of
     *     their Regions are held
     * @throws NullPointerException if {@code region} or an element of {@code coords} is null
     * @throws AstException with status {@code AST__STCKEY} if an AstroCoords element has a number
     *     of axis names other than the number of axes of {@code region}, or a Region that cannot
     *     be converted to the coordinate system of {@code region}
     */
    public StcSearchLocation(Region region, AstroCoords[] coords) {
        this(region, astroCoordsToKeyMaps(coords));
    }

    /**
     * Constructs a new StcSearchLocation from AstroCoords elements given as KeyMaps (C's {@code
     * astStcSearchLocation}).
     *
     * @see Stc#Stc(Region, KeyMap[])
     */
    StcSearchLocation(Region region, KeyMap[] coords) {
        super(region, coords);
    }

    /** Loads an StcSearchLocation from its description (C's {@code astLoadStcSearchLocation}). */
    StcSearchLocation(NativeNode node) {
        super(node);
        node.section("StcSearchLocation");
    }

    @Override
    String dumpComment() {
        return "Query coverage";
    }

    /**
     * Writes the StcSearchLocation class's part of a description (C's {@code Dump},
     * stcsearchlocation.c), which holds nothing of its own.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Stc", "An IVOA Space-Time-Coords object");
    }
}
