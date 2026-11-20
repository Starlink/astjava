package uk.ac.starlink.ast;

/**
 * Corresponds to the IVOA ObsDataLocation class. The StcObsDataLocation class is a sub-class of
 * {@link Stc} used to describe the coordinate space occupied by a particular observational dataset.
 *
 * <p>See http://hea-www.harvard.edu/~arots/nvometa/STC.html
 *
 * <p>An STC ObsDataLocation element specifies the extent of the observation within a specified
 * coordinate system, and also specifies the observatory location within a second coordinate
 * system.
 *
 * <p>The AST StcObsDataLocation class inherits from Stc, and therefore an StcObsDataLocation can be
 * used directly as an Stc. When used in this way, the StcObsDataLocation describes the location of
 * the observation (not the observatory).
 *
 * <p>Eventually, this class will have a method for returning an Stc describing the observatory
 * location. However, AST currently does not include any classes of Frame for describing
 * terrestrial or solar system positions. Therefore, the provision for returning observatory
 * location as an Stc is not yet available. However, for terrestrial observations, the position of
 * the observatory can still be recorded using the ObsLon and ObsLat attributes of the Frame
 * encapsulated within the Stc representing the observation location (this assumes the observatory
 * is located at sea level).
 *
 * <p>An StcObsDataLocation made by its constructor holds no observatory position; one read from a
 * description holds the one written there, if any. The observatory position takes no part in
 * {@link #equals}, as C's StcObsDataLocation compares as an Stc.
 *
 * <p>Ported from C {@code stcobsdatalocation.c}.
 */
public class StcObsDataLocation extends Stc {

    /**
     * The observatory position, or null if there is none (C's {@code obs}). It is a PointList when
     * set through {@link #setObs}; a description may hold an Object of any class here, which is
     * read as it is, as C reads it.
     */
    private AstObject obs;

    /**
     * Constructs a new StcObsDataLocation.
     *
     * @param region the encapsulated region; a copy is held
     * @param coords the AstroCoords elements associated with this Stc, or null for none; copies of
     *     their Regions are held
     * @throws NullPointerException if {@code region} or an element of {@code coords} is null
     * @throws AstException with status {@code AST__STCKEY} if an AstroCoords element has a number
     *     of axis names other than the number of axes of {@code region}, or a Region that cannot
     *     be converted to the coordinate system of {@code region}
     */
    public StcObsDataLocation(Region region, AstroCoords[] coords) {
        this(region, astroCoordsToKeyMaps(coords));
    }

    /**
     * Constructs a new StcObsDataLocation, without an observatory position, from AstroCoords
     * elements given as KeyMaps (C's {@code astStcObsDataLocation}).
     *
     * @see Stc#Stc(Region, KeyMap[])
     */
    StcObsDataLocation(Region region, KeyMap[] coords) {
        super(region, coords);
    }

    /**
     * Loads an StcObsDataLocation from its description (C's {@code astLoadStcObsDataLocation}):
     * the Stc part, then the observatory position {@code ObsLoc}, if present, an Object of any
     * class.
     */
    StcObsDataLocation(NativeNode node) {
        super(node);
        NativeNode part = node.section("StcObsDataLocation");
        NativeNode desc = part.readObject("ObsLoc");
        if (desc != null) {
            obs = ObjectRegistry.loadNested(desc, "ObsLoc");
        }
    }

    /**
     * Sets the observatory position (C's protected {@code astStcSetObs}). The PointList given is
     * held itself, not a copy, as C holds a clone of it.
     *
     * @param obs the observatory position, or null to remove any
     */
    void setObs(PointList obs) {
        this.obs = obs;
    }

    @Override
    String dumpComment() {
        return "Observation coverage";
    }

    /**
     * Writes the StcObsDataLocation class's part of a description (C's {@code Dump},
     * stcobsdatalocation.c): the observatory position {@code ObsLoc}, only when there is one.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Stc", "An IVOA Space-Time-Coords object");
        if (obs != null) {
            node.writeObject("ObsLoc", true, true, NativeNode.of(obs), "Observatory position");
        }
    }

    /**
     * Completes a copy as C's {@code Copy} (stcobsdatalocation.c) does: the observatory position is
     * copied.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (obs != null) {
            obs = obs.copy();
        }
    }
}
