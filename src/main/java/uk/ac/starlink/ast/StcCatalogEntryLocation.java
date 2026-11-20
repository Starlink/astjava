package uk.ac.starlink.ast;

/**
 * Corresponds to the IVOA STCCatalogEntryLocation class. The StcCatalogEntryLocation class is a sub-class of {@link
 * Stc} used to describe the coverage of the datasets contained in some VO resource.
 *
 * <p>See http://hea-www.harvard.edu/~arots/nvometa/STC.html
 *
 * <p>Ported from C {@code stccatalogentrylocation.c}.
 */
public class StcCatalogEntryLocation extends Stc {

    /**
     * Constructs a new StcCatalogEntryLocation.
     *
     * @param region the encapsulated region; a copy is held
     * @param coords the AstroCoords elements associated with this Stc, or null for none; copies of
     *     their Regions are held
     * @throws NullPointerException if {@code region} or an element of {@code coords} is null
     * @throws AstException with status {@code AST__STCKEY} if an AstroCoords element has a number
     *     of axis names other than the number of axes of {@code region}, or a Region that cannot
     *     be converted to the coordinate system of {@code region}
     */
    public StcCatalogEntryLocation(Region region, AstroCoords[] coords) {
        this(region, astroCoordsToKeyMaps(coords));
    }

    /**
     * Constructs a new StcCatalogEntryLocation from AstroCoords elements given as KeyMaps (C's {@code
     * astStcCatalogEntryLocation}).
     *
     * @see Stc#Stc(Region, KeyMap[])
     */
    StcCatalogEntryLocation(Region region, KeyMap[] coords) {
        super(region, coords);
    }

    /** Loads an StcCatalogEntryLocation from its description (C's {@code astLoadStcCatalogEntryLocation}). */
    StcCatalogEntryLocation(NativeNode node) {
        super(node);
        node.section("StcCatalogEntryLocation");
    }

    @Override
    String dumpComment() {
        return "Resource coverage";
    }

    /**
     * Writes the StcCatalogEntryLocation class's part of a description (C's {@code Dump},
     * stccatalogentrylocation.c), which holds nothing of its own.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Stc", "An IVOA Space-Time-Coords object");
    }
}
