package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * Represents an instance of the IVOA STC class. The Stc class is an implementation of the IVOA STC
 * class which forms part of the IVOA Space-Time Coordinate Metadata system. See:
 *
 * <p>http://hea-www.harvard.edu/~arots/nvometa/STC.html
 *
 * <p>The Stc class does not have a constructor function of its own, as it is simply a container
 * class for a family of specialised sub-classes including {@link StcResourceProfile}.
 *
 * <p>An Stc encapsulates a copy of the Region it is made from, and covers exactly the points that
 * Region covers; a Region that is itself an Stc is replaced by its own encapsulated Region, mapped
 * into the coordinate system it represents. The Negated, Closed, MeshSize and FillFactor
 * attributes are read from the encapsulated Region, and setting or clearing one on the Stc sets or
 * clears it on both. Transforming points with an Stc, or asking whether it is bounded, sets the
 * encapsulated Region's Negated attribute to the value it already has, so that Negated then reads
 * as set on the Stc, as it does in C. The read-only RegionClass attribute is the class of the
 * encapsulated Region.
 *
 * <p>An Stc also holds a list of AstroCoords elements, each a copy of the description given, with
 * each Region it holds mapped into the coordinate system of the encapsulated Region. A Frame
 * attribute set through an Stc that is not Adaptive is set on the encapsulated Region and on each
 * of these Regions too; one cleared is cleared on the encapsulated Region only.
 *
 * <p>Ported from C {@code stc.c}, with Rust {@code regions/stc.rs}.
 */
public abstract class Stc extends Region {

    /** The AstroCoords key for the axis names, a vector of strings (C's {@code AST__STCNAME}). */
    static final String AST__STCNAME = "Name";

    /** The AstroCoords key for the value, a Region (C's {@code AST__STCVALUE}). */
    static final String AST__STCVALUE = "Value";

    /** The AstroCoords key for the error, a Region (C's {@code AST__STCERROR}). */
    static final String AST__STCERROR = "Error";

    /** The AstroCoords key for the resolution, a Region (C's {@code AST__STCRES}). */
    static final String AST__STCRES = "Resolution";

    /** The AstroCoords key for the size, a Region (C's {@code AST__STCSIZE}). */
    static final String AST__STCSIZE = "Size";

    /** The AstroCoords key for the pixel size, a Region (C's {@code AST__STCPIXSZ}). */
    static final String AST__STCPIXSZ = "PixSize";

    /** The AstroCoords keys that may hold a Region, in C's order (stc.c {@code regkey}). */
    private static final String[] REG_KEYS = {AST__STCERROR, AST__STCRES, AST__STCSIZE, AST__STCPIXSZ, AST__STCVALUE};

    /** The comment stored with each of {@link #REG_KEYS} (stc.c {@code regcom}). */
    private static final String[] REG_COMMENTS = {
        "AstroCoords error region",
        "AstroCoords resolution region",
        "AstroCoords size region",
        "AstroCoords pixel size region",
        "AstroCoords value region"
    };

    /**
     * The encapsulated Region, the Stc's own, describing an area in the Stc's base Frame, which is
     * its current Frame (C's {@code region}).
     */
    private Region region;

    /** The AstroCoords elements, the Stc's own, each Region in the base Frame (C's {@code coord}). */
    private KeyMap[] coords;

    /** The Region an Stc encapsulates, which {@code astInitStc} makes before the Region part. */
    private static final class Encapsulated {
        final Region reg;

        Encapsulated(Region reg) {
            this.reg = reg;
        }
    }

    /**
     * Creates an Stc (C's {@code astInitStc}).
     *
     * @param region the Region to encapsulate: a copy of it is held, or, if it is an Stc, its
     *     encapsulated Region mapped into the Frame it represents
     * @param coords the AstroCoords elements, each a KeyMap with the keys {@link #AST__STCNAME} (a
     *     name for each axis) and those of {@link #REG_KEYS} (one Region each), or null for none
     * @throws NullPointerException if {@code region} or an element of {@code coords} is null
     * @throws AstException with status {@code AST__STCKEY} if an AstroCoords element has another
     *     key, a number of names other than the number of axes, names that are not strings, more
     *     than one value or a value that is not a Region under another key, or a Region that cannot
     *     be converted to the coordinate system of the encapsulated Region
     */
    Stc(Region region, KeyMap[] coords) {
        this(encapsulate(region), coords);
    }

    /** The second part of C's {@code astInitStc}, once the Region to encapsulate is made. */
    // C's astInitStc copies the attributes through the class's own setters (astRegOverlay), so
    // that the encapsulated Region takes MeshSize and FillFactor too.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    private Stc(Encapsulated e, KeyMap[] coords) {
        super(e.reg, null, null);
        Region reg = e.reg;
        this.region = reg;
        this.coords = new KeyMap[0];
        regOverlay(reg, true);
        if (reg.testAttrib("ident")) {
            setIdent(reg.getIdent());
        }
        reg.setRegionFS(false);
        if (coords != null && coords.length > 0) {
            KeyMap[] made = new KeyMap[coords.length];
            for (int i = 0; i < coords.length; i++) {
                Objects.requireNonNull(coords[i], "coords[" + i + "]");
                made[i] = makeAstroCoordsKeyMap(reg, coords[i], astClass());
            }
            this.coords = made;
        }
    }

    /**
     * The first part of C's {@code astInitStc}: a copy of {@code region}, or, if it is an Stc, its
     * encapsulated Region mapped into the Frame it represents (not simplified).
     */
    private static Encapsulated encapsulate(Region region) {
        Objects.requireNonNull(region, "region");
        if (region instanceof Stc) {
            FrameSet fs = region.getRegFS();
            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Frame frm = fs.getFrame(FrameSet.AST__CURRENT);
            return new Encapsulated(((Stc) region).region.mapRegionInto(map, frm));
        }
        return new Encapsulated((Region) region.copy());
    }

    /**
     * A copy of the AstroCoords KeyMap {@code coord}, checked, with each Region it holds mapped
     * into the Frame of {@code reg} and simplified where the two Frames differ, and marked as not
     * needing its Frame in a description (stc.c {@code MakeAstroCoordsKeyMap}).
     */
    private static KeyMap makeAstroCoordsKeyMap(Region reg, KeyMap coord, String cls) {
        KeyMap result = (KeyMap) coord.copy();
        int naxes = reg.getNaxes();
        int nkey = result.mapSize();
        String pre = "astInitStc(" + cls + "): ";
        for (int j = 0; j < nkey; j++) {
            String key = result.mapKey(j);
            int nv = result.mapLength(key);
            int type = result.mapType(key);
            if (!isAstroCoordsKey(key)) {
                throw Ast.err(
                        AstException.AST__STCKEY, pre + "Unknown key \"" + key + "\" supplied in an AstroCoords list.");
            } else if (AST__STCNAME.equals(key)) {
                if (nv != naxes) {
                    throw Ast.err(
                            AstException.AST__STCKEY,
                            pre + nv + " \"" + key + "\" values supplied in an AstroCoords list, but the Stc has "
                                    + naxes + " axes. ");
                } else if (type != KeyMap.AST__STRINGTYPE) {
                    throw Ast.err(
                            AstException.AST__STCKEY,
                            pre + "The \"" + key + "\" values supplied in an AstroCoords list are not character "
                                    + "strings. ");
                }
            } else if (nv != 1) {
                throw Ast.err(
                        AstException.AST__STCKEY,
                        pre + nv + " \"" + key + "\" values supplied in an AstroCoords list, but only one is "
                                + "allowed. ");
            } else if (type != KeyMap.AST__OBJECTTYPE) {
                throw Ast.err(
                        AstException.AST__STCKEY,
                        pre + "The \"" + key + "\" value supplied in an AstroCoords list is not an AST Object "
                                + "pointer. ");
            } else {
                AstObject obj = result.mapGet0A(key);
                if (obj == null) {
                    throw Ast.err(
                            AstException.AST__STCKEY,
                            pre + "The \"" + key + "\" value supplied in an AstroCoords list is a NULL pointer. ");
                } else if (!(obj instanceof Region)) {
                    throw Ast.err(
                            AstException.AST__STCKEY,
                            pre + "The \"" + key + "\" value supplied in an AstroCoords list is a " + obj.astClass()
                                    + ", not a Region. ");
                }
                Region areg = (Region) obj;
                FrameSet fs = areg.convert(reg, "");
                if (fs == null) {
                    throw Ast.err(
                            AstException.AST__STCKEY,
                            pre + "The \"" + key + "\" value supplied in an AstroCoords list cannot be converted to "
                                    + "the coordinate system of its parent Stc object.");
                }
                Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                if (!isUnitMap(map)) {
                    Frame frm = fs.getFrame(FrameSet.AST__CURRENT);
                    Region sareg = (Region) areg.mapRegionInto(map, frm).simplify();
                    sareg.setRegionFS(false);
                    result.mapPut0A(key, sareg, null);
                } else {
                    areg.setRegionFS(false);
                }
            }
        }
        return result;
    }

    /** Whether {@code key} is one of the six AstroCoords keys. */
    private static boolean isAstroCoordsKey(String key) {
        if (AST__STCNAME.equals(key)) {
            return true;
        }
        for (String k : REG_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads an Stc from its description, as C's {@code astLoadStc} does: the Region part, then
     * {@code Region}, the encapsulated Region, {@code Ncoord} (0 if absent or below 0) and {@code
     * Coord1} to {@code Coord<Ncoord>}, the AstroCoords elements. A Region read without its Frame,
     * the encapsulated Region or one in an AstroCoords element, is given the base Frame of the
     * Stc, and one in an AstroCoords element is stored again with its comment, unless the Stc was
     * read without its own Frame, when the Region that holds it gives the encapsulated Region its
     * Frame.
     *
     * @throws AstException with status {@code AST__LDERR} if the encapsulated Region or an
     *     AstroCoords element is missing or of the wrong class, or an AstroCoords element holds
     *     something other than a Region under a key that may hold one; C stores a null for a
     *     missing item, and its load or later use then fails
     */
    Stc(NativeNode node) {
        super(node);
        NativeNode part = node.section("Stc");
        String pre = "astLoadStc(" + astClass() + "): ";
        NativeNode desc = part.readObject("Region");
        if (desc == null) {
            throw Ast.err(AstException.AST__LDERR, pre + "The encapsulated Region is missing.");
        }
        AstObject obj = ObjectRegistry.loadNested(desc, "Region");
        if (!(obj instanceof Region)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    pre + "The encapsulated Region is a " + obj.astClass() + ", not a Region.");
        }
        region = (Region) obj;
        Frame f1 = baseFrame();
        boolean dummy = regDummyFS();
        if (!dummy && region.regDummyFS()) {
            region.setRegFS(f1);
        }
        int ncoord = Math.max(part.readInt("Ncoord", 0), 0);
        coords = new KeyMap[ncoord];
        for (int ico = 1; ico <= ncoord; ico++) {
            String key = "Coord" + ico;
            desc = part.readObject(key);
            if (desc == null) {
                throw Ast.err(AstException.AST__LDERR, pre + "The AstroCoords element " + key + " is missing.");
            }
            obj = ObjectRegistry.loadNested(desc, key);
            if (!(obj instanceof KeyMap)) {
                throw Ast.err(
                        AstException.AST__LDERR,
                        pre + "The AstroCoords element " + key + " is a " + obj.astClass() + ", not a KeyMap.");
            }
            KeyMap km = (KeyMap) obj;
            coords[ico - 1] = km;
            if (!dummy) {
                for (int ikey = 0; ikey < REG_KEYS.length; ikey++) {
                    Region creg = coordRegion(km, REG_KEYS[ikey]);
                    if (creg != null && creg.regDummyFS()) {
                        creg.setRegFS(f1);
                        km.mapPut0A(REG_KEYS[ikey], creg, REG_COMMENTS[ikey]);
                    }
                }
            }
        }
    }

    /**
     * The Region {@code km} holds under {@code key}, the KeyMap's own, or null if it holds none.
     *
     * @throws AstException with status {@code AST__LDERR} if it holds an Object that is not a
     *     Region, which C would go on to use as one
     */
    private static Region coordRegion(KeyMap km, String key) {
        AstObject[] out = new AstObject[1];
        if (!km.mapGet0A(key, out) || out[0] == null) {
            return null;
        }
        if (!(out[0] instanceof Region)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadStc: The AstroCoords \"" + key + "\" value is a " + out[0].astClass() + ", not a Region.");
        }
        return (Region) out[0];
    }

    /**
     * Writes the Stc class's part of a description (C's {@code Dump}, stc.c): {@code Region}, the
     * encapsulated Region, {@code Ncoord}, set when not zero, and each AstroCoords element.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
        node.writeObject("Region", true, true, NativeNode.of(region), "STC Region");
        int ncoord = coords.length;
        node.writeInt("Ncoord", ncoord != 0, false, ncoord, "Number of AstroCoords elements");
        for (int ico = 1; ico <= ncoord; ico++) {
            node.writeObject("Coord" + ico, true, true, NativeNode.of(coords[ico - 1]), "AstroCoords number " + ico);
        }
    }

    /**
     * Completes a copy as C's {@code Copy} (stc.c) does: the encapsulated Region and each
     * AstroCoords element are copied.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        region = (Region) region.copy();
        KeyMap[] copies = new KeyMap[coords.length];
        for (int i = 0; i < coords.length; i++) {
            copies[i] = (KeyMap) coords[i].copy();
        }
        coords = copies;
    }

    // ------------------------------------------------------------------
    // jniast's public methods
    // ------------------------------------------------------------------

    /**
     * Returns the number of AstroCoords elements stored within this {@code Stc} when it was
     * constructed.
     *
     * @return number of coords stored
     */
    public int getStcNCoord() {
        return coords.length;
    }

    /**
     * Returns one of the AstroCoords elements stored within this {@code Stc} when it was
     * constructed. If the coordinate system represented by this {@code Stc} has been changed since
     * it was created (for instance, by changing its System attribute), then the sizes and positions
     * in the returned {@code AstroCoords} object will reflect the change in coordinate system.
     *
     * @param i index of coords to retrieve. The first index is 1.
     * @return {@code i}'th coord stored
     * @throws AstException with status {@code AST__STCIND} if {@code i} is not from 1 to {@link
     *     #getStcNCoord}
     */
    public AstroCoords getStcCoord(int i) {
        return keyMapToAstroCoords(stcCoord(i));
    }

    /**
     * Obtain a copy of the encapsulated Region within a Stc. This function returns a deep copy of
     * the Region supplied when the Stc was created.
     *
     * @return A deep copy of the Region encapsulated within the supplied Stc.
     */
    public Region getStcRegion() {
        return (Region) region.copy();
    }

    /**
     * A copy of AstroCoords element {@code icoord}, counting from 1 (C's {@code astGetStcCoord}).
     * When the Mapping from the base to the current Frame does not simplify to a UnitMap, each
     * Region in it is mapped into the current Frame and simplified, and the axis names are
     * removed; each Region in it includes its Frame when written.
     *
     * @throws AstException with status {@code AST__STCIND} if {@code icoord} is not from 1 to
     *     {@link #getStcNCoord}
     */
    KeyMap stcCoord(int icoord) {
        int nc = coords.length;
        String cls = astClass();
        if (icoord < 1 || icoord > nc) {
            String more;
            if (icoord < 1) {
                more = "The index of the first AstroCoord element is one, not zero.";
            } else if (nc == 0) {
                more = "There are no AstroCoords elements in the supplied " + cls + ".";
            } else if (nc == 1) {
                more = "There is 1 AstroCoords element in the supplied " + cls + ".";
            } else {
                more = "There are " + nc + " AstroCoords elements in the supplied " + cls + ".";
            }
            throw Ast.err(
                    AstException.AST__STCIND,
                    "astGetStcCoord(" + cls + "): Supplied AstroCoords index (" + icoord + ") is invalid.\n" + more);
        }
        KeyMap result = (KeyMap) coords[icoord - 1].copy();
        FrameSet fs = getRegFS();
        Mapping smap = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
        Frame frm = fs.getFrame(FrameSet.AST__CURRENT);
        boolean unit = isUnitMap(smap);
        if (!unit) {
            result.mapRemove(AST__STCNAME);
        }
        for (String key : REG_KEYS) {
            AstObject[] out = new AstObject[1];
            if (result.mapGet0A(key, out) && out[0] instanceof Region) {
                Region reg = (Region) out[0];
                reg.setRegionFS(true);
                Region srereg =
                        unit ? reg : (Region) reg.mapRegionInto(smap, frm).simplify();
                result.mapPut0A(key, srereg, null);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // jniast's conversions between AstroCoords and KeyMaps
    // ------------------------------------------------------------------

    /**
     * Converts an AstroCoords object to an equivalent KeyMap object, which holds the AstroCoords
     * object's Regions, not copies: the axis names if there are any, and each Region that is not
     * null.
     *
     * @param astroCoords AstroCoords object
     * @return KeyMap object
     * @throws NullPointerException if {@code astroCoords} is null
     */
    static KeyMap astroCoordsToKeyMap(AstroCoords astroCoords) {
        KeyMap kmap = new KeyMap();
        String[] axisNames = astroCoords.getName();
        if (axisNames != null && axisNames.length > 0) {
            kmap.mapPut1C(AST__STCNAME, axisNames, "STC axis names");
        }
        Region value = astroCoords.getValue();
        if (value != null) {
            kmap.mapPut0A(AST__STCVALUE, value, "STC value");
        }
        Region error = astroCoords.getError();
        if (error != null) {
            kmap.mapPut0A(AST__STCERROR, error, "STC error");
        }
        Region res = astroCoords.getResolution();
        if (res != null) {
            kmap.mapPut0A(AST__STCRES, res, "STC resolution");
        }
        Region size = astroCoords.getSize();
        if (size != null) {
            kmap.mapPut0A(AST__STCSIZE, size, "STC size");
        }
        Region pixSize = astroCoords.getPixSize();
        if (pixSize != null) {
            kmap.mapPut0A(AST__STCPIXSZ, pixSize, "STC pixel size");
        }
        return kmap;
    }

    /**
     * Converts a KeyMap object to an equivalent AstroCoords object, which holds the KeyMap's
     * Regions, not copies: the axis names if they are strings, and each Region.
     *
     * @param keyMap KeyMap object
     * @return AstroCoords object
     */
    static AstroCoords keyMapToAstroCoords(KeyMap keyMap) {
        AstroCoords ac = new AstroCoords();
        if (keyMap.mapType(AST__STCNAME) == KeyMap.AST__STRINGTYPE) {
            ac.setName(keyMap.mapGet1C(AST__STCNAME, 100));
        }
        ac.setValue(keyMapRegion(keyMap, AST__STCVALUE));
        ac.setError(keyMapRegion(keyMap, AST__STCERROR));
        ac.setResolution(keyMapRegion(keyMap, AST__STCRES));
        ac.setSize(keyMapRegion(keyMap, AST__STCSIZE));
        ac.setPixSize(keyMapRegion(keyMap, AST__STCPIXSZ));
        return ac;
    }

    /** The Region {@code keyMap} holds under {@code key}, or null, as jniast reads it. */
    private static Region keyMapRegion(KeyMap keyMap, String key) {
        if (keyMap.mapType(key) == KeyMap.AST__OBJECTTYPE) {
            AstObject obj = keyMap.mapGet0A(key);
            if (obj instanceof Region) {
                return (Region) obj;
            }
        }
        return null;
    }

    /**
     * Converts an array of AstroCoords objects to an equivalent array of KeyMap objects.
     *
     * @param coords array of AstroCoords objects
     * @return array of KeyMap objects, or null if {@code coords} is null
     * @throws NullPointerException if an element of {@code coords} is null
     */
    static KeyMap[] astroCoordsToKeyMaps(AstroCoords[] coords) {
        if (coords == null) {
            return null;
        }
        KeyMap[] kmaps = new KeyMap[coords.length];
        for (int i = 0; i < coords.length; i++) {
            kmaps[i] = astroCoordsToKeyMap(Objects.requireNonNull(coords[i], "coords[" + i + "]"));
        }
        return kmaps;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /** C's {@code GetAttrib} (stc.c): RegionClass, then the Region's. */
    @Override
    String getAttrib(String attrib) {
        if ("regionclass".equals(attrib)) {
            return region.astClass();
        }
        return super.getAttrib(attrib);
    }

    /** C's {@code SetAttrib} (stc.c): RegionClass is read-only; anything else is the Region's. */
    @Override
    void setAttrib(String attrib, String value) {
        if ("regionclass".equals(attrib) && !value.isEmpty()) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        }
        super.setAttrib(attrib, value);
    }

    /** C's {@code TestAttrib} (stc.c): RegionClass is never set; anything else is the Region's. */
    @Override
    boolean testAttrib(String attrib) {
        return !"regionclass".equals(attrib) && super.testAttrib(attrib);
    }

    /** C's {@code ClearAttrib} (stc.c): RegionClass is read-only; anything else is the Region's. */
    @Override
    void clearAttrib(String attrib) {
        if ("regionclass".equals(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        }
        super.clearAttrib(attrib);
    }

    /**
     * Gets the Negated attribute of the encapsulated Region (stc.c {@code GetNegated}).
     *
     * @return the Negated attribute
     */
    @Override
    public boolean getNegated() {
        return region.getNegated();
    }

    /** Sets Negated on the Stc and on the encapsulated Region (stc.c {@code SetNegated}). */
    @Override
    void setNegatedValue(boolean value) {
        super.setNegatedValue(value);
        region.setNegatedValue(value);
    }

    /** Whether Negated is set on the encapsulated Region (stc.c {@code TestNegated}). */
    @Override
    boolean testNegated() {
        return region.testNegated();
    }

    /** Clears Negated on the Stc and on the encapsulated Region (stc.c {@code ClearNegated}). */
    @Override
    void clearNegated() {
        super.clearNegated();
        region.clearNegated();
    }

    /**
     * Gets the Closed attribute of the encapsulated Region (stc.c {@code GetClosed}).
     *
     * @return the Closed attribute
     */
    @Override
    public boolean getClosed() {
        return region.getClosed();
    }

    /** Sets Closed on the Stc and on the encapsulated Region (stc.c {@code SetClosed}). */
    @Override
    void setClosedValue(boolean value) {
        super.setClosedValue(value);
        region.setClosedValue(value);
    }

    /** Whether Closed is set on the encapsulated Region (stc.c {@code TestClosed}). */
    @Override
    boolean testClosed() {
        return region.testClosed();
    }

    /** Clears Closed on the Stc and on the encapsulated Region (stc.c {@code ClearClosed}). */
    @Override
    void clearClosed() {
        super.clearClosed();
        region.clearClosed();
    }

    /**
     * Gets the MeshSize attribute of the encapsulated Region (stc.c {@code GetMeshSize}).
     *
     * @return the MeshSize attribute
     */
    @Override
    public int getMeshSize() {
        return region.getMeshSize();
    }

    /** Sets MeshSize on the Stc and on the encapsulated Region (stc.c {@code SetMeshSize}). */
    @Override
    void setMeshSizeValue(int value) {
        super.setMeshSizeValue(value);
        region.setMeshSizeValue(value);
    }

    /** Whether MeshSize is set on the encapsulated Region (stc.c {@code TestMeshSize}). */
    @Override
    boolean testMeshSize() {
        return region.testMeshSize();
    }

    /** Clears MeshSize on the Stc and on the encapsulated Region (stc.c {@code ClearMeshSize}). */
    @Override
    void clearMeshSize() {
        super.clearMeshSize();
        region.clearMeshSize();
    }

    /**
     * Gets the FillFactor attribute of the encapsulated Region (stc.c {@code GetFillFactor}).
     *
     * @return the FillFactor attribute
     */
    @Override
    public double getFillFactor() {
        return region.getFillFactor();
    }

    /** Sets FillFactor on the Stc and on the encapsulated Region (stc.c {@code SetFillFactor}). */
    @Override
    void setFillFactorValue(double value) {
        super.setFillFactorValue(value);
        region.setFillFactorValue(value);
    }

    /** Whether FillFactor is set on the encapsulated Region (stc.c {@code TestFillFactor}). */
    @Override
    boolean testFillFactor() {
        return region.testFillFactor();
    }

    /** Clears FillFactor on the Stc and on the encapsulated Region (stc.c {@code ClearFillFactor}). */
    @Override
    void clearFillFactor() {
        super.clearFillFactor();
        region.clearFillFactor();
    }

    /** The Stc's UseDefs if set, otherwise the encapsulated Region's (stc.c {@code GetUseDefs}). */
    @Override
    boolean getUseDefs() {
        if (testUseDefs() || region == null) {
            return super.getUseDefs();
        }
        return region.getUseDefs();
    }

    /**
     * Sets a Frame attribute without remapping (stc.c {@code RegSetAttrib}): region.c's, then the
     * base Frame setting on the encapsulated Region, whose current Frame is the base Frame of the
     * Stc, and on each Region in an AstroCoords element; a Region without the attribute is passed
     * over.
     */
    @Override
    String regSetAttrib(String attrib, String value) {
        String battrib = super.regSetAttrib(attrib, value);
        regSetIgnoringBadAttrib(region, battrib, value);
        for (KeyMap km : coords) {
            for (String key : REG_KEYS) {
                AstObject[] out = new AstObject[1];
                if (km.mapGet0A(key, out) && out[0] instanceof Region) {
                    regSetIgnoringBadAttrib((Region) out[0], battrib, value);
                }
            }
        }
        return battrib;
    }

    /** {@code reg.regSetAttrib}, an {@code AST__BADAT} error ignored, as C annuls it. */
    private static void regSetIgnoringBadAttrib(Region reg, String battrib, String value) {
        try {
            reg.regSetAttrib(battrib, value);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
    }

    /**
     * Clears a Frame attribute without remapping (stc.c {@code RegClearAttrib}): region.c's, then
     * the base Frame attribute on the encapsulated Region, an {@code AST__BADAT} error ignored. The
     * Regions in the AstroCoords elements keep it.
     */
    @Override
    String regClearAttrib(String attrib) {
        String battrib = super.regClearAttrib(attrib);
        try {
            region.regClearAttrib(battrib);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        return battrib;
    }

    /**
     * Gives the Stc a FrameSet of {@code frm} (stc.c {@code SetRegFS}), and the encapsulated Region
     * too if it is written without its Frame.
     */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        if (region != null && !region.getRegionFS()) {
            region.setRegFS(frm);
        }
    }

    // ------------------------------------------------------------------
    // Membership, bounds and boundary
    // ------------------------------------------------------------------

    /**
     * Which base Frame points are in the Stc (stc.c {@code Transform}): those the encapsulated
     * Region, with its own Negated value, leaves good on some axis. As C does, Negated is set on
     * the encapsulated Region to the value it has for the duration, and so is left set.
     */
    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        boolean neg = region.getNegated();
        boolean negOld = region.getNegated();
        region.setNegatedValue(neg);
        try {
            return goodPoints(region.transform(base, npoint, false), base.length, npoint);
        } finally {
            region.setNegatedValue(negOld);
        }
    }

    /**
     * Not used: {@link Region#regBaseMask} is this hook's only caller, and Stc replaces it with C's
     * {@code Transform} ({@link #regBaseMask}), as stc.c installs no other membership test.
     *
     * @throws AstException with status {@code AST__INTER} always
     */
    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        throw Ast.err(
                AstException.AST__INTER,
                "astRegBaseInside(" + astClass() + "): An Stc decides membership through its encapsulated Region "
                        + "(internal AST programming error).");
    }

    /** For each point, whether some one of its first {@code ncoord} coordinates is good. */
    private static boolean[] goodPoints(double[][] ps, int ncoord, int npoint) {
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            for (int coord = 0; coord < ncoord; coord++) {
                if (ps[coord][point] != AST__BAD) {
                    mask[point] = true;
                    break;
                }
            }
        }
        return mask;
    }

    /**
     * Whether the encapsulated Region is bounded (stc.c {@code GetBounded}). As C does, Negated is
     * set on the encapsulated Region to the value it has for the duration, and so is left set.
     */
    @Override
    boolean isBoundedInner() {
        boolean neg = region.getNegated();
        boolean negOld = region.getNegated();
        region.setNegatedValue(neg);
        try {
            return region.getBounded();
        } finally {
            region.setNegatedValue(negOld);
        }
    }

    /** The encapsulated Region's bounding box in its base Frame (stc.c {@code RegBaseBox}). */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        region.regBaseBox(lbnd, ubnd);
    }

    /**
     * The encapsulated Region's boundary mesh in its current Frame, which is the base Frame of the
     * Stc (stc.c {@code RegBaseMesh}).
     */
    @Override
    double[][] regBaseMesh() {
        return region.regMesh();
    }

    /** The encapsulated Region's pick of base Frame axes (stc.c {@code RegBasePick}). */
    @Override
    Region regBasePick(int[] axes) {
        return region.regBasePick(axes);
    }

    /** Whether the points are on the encapsulated Region's boundary (stc.c {@code RegPins}). */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        return region.regPins(pset, pointUnc, mask);
    }

    /**
     * The default uncertainty (stc.c {@code GetDefUnc}): the encapsulated Region's uncertainty in
     * its current Frame if one is set, otherwise region.c's default.
     */
    @Override
    Region getDefUnc() {
        if (region.testUnc()) {
            return region.getUncFrm(FrameSet.AST__CURRENT);
        }
        return super.getDefUnc();
    }

    /** How the encapsulated Region and {@code that} overlap (stc.c {@code Overlap}). */
    @Override
    int overlapWith(Region that) {
        return region.overlap(that);
    }

    /**
     * How {@code thisRegion} and this Stc overlap (stc.c {@code OverlapX}): how the encapsulated
     * Region and {@code thisRegion} overlap, with inside and outside exchanged.
     */
    @Override
    int overlapX(Region thisRegion) {
        int result = region.overlap(thisRegion);
        if (result == OVERLAP_INSIDE) {
            result = OVERLAP_OUTSIDE;
        } else if (result == OVERLAP_OUTSIDE) {
            result = OVERLAP_INSIDE;
        }
        return result;
    }

    /**
     * stc.c {@code Equal}: region.c's tests, then equal encapsulated Regions. The AstroCoords
     * elements are not compared.
     */
    @Override
    boolean equalTo(AstObject that) {
        return super.equalTo(that) && region.astEqual(((Stc) that).region);
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * Simplifies the Stc (C's {@code Simplify}, stc.c). After the Region-generic simplification, a
     * negated Stc has the negation moved onto the encapsulated Region; the encapsulated Region,
     * mapped into the current Frame if the Mapping from the base Frame is not a UnitMap, is
     * simplified, and if that gives a different Region it is stored, the Stc's FrameSet becoming
     * one of the current Frame; and each Region in an AstroCoords element is treated alike. The Stc
     * is copied before the first change, and returned itself if nothing changes.
     */
    // C compares the pointers; the loop over the AstroCoords elements indexes them, since the Stc
    // whose element is changed may become a copy part way through.
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals", "PMD.ForLoopCanBeForeach"})
    Mapping simplifyStep() {
        Stc stc = (Stc) parentSimplify();
        if (stc.getNegated()) {
            if (stc == this) {
                stc = (Stc) copy();
            }
            stc.negate();
            stc.region.negate();
        }
        FrameSet fs = stc.getRegFS();
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Frame frm = fs.getFrame(FrameSet.AST__CURRENT);
        boolean unit = isUnitMap(map);
        Region newreg;
        if (!unit) {
            newreg = (Region) stc.region.mapRegionInto(map, frm).simplify();
        } else {
            newreg = (Region) stc.region.simplify();
        }
        if (newreg != stc.region) {
            if (stc == this) {
                stc = (Stc) copy();
            }
            stc.region = newreg;
            if (!unit) {
                stc.setRegFS(frm);
            }
        }
        for (int i = 0; i < stc.coords.length; i++) {
            KeyMap keymap = stc.coords[i];
            for (int ikey = 0; ikey < REG_KEYS.length; ikey++) {
                AstObject[] out = new AstObject[1];
                if (!keymap.mapGet0A(REG_KEYS[ikey], out) || !(out[0] instanceof Region)) {
                    continue;
                }
                Region reg = (Region) out[0];
                Region treg = unit ? reg : reg.mapRegionInto(map, frm);
                Region sreg = (Region) treg.simplify();
                if (sreg != reg) {
                    if (stc == this) {
                        stc = (Stc) copy();
                        keymap = stc.coords[i];
                    }
                    keymap.mapPut0A(REG_KEYS[ikey], sreg, REG_COMMENTS[ikey]);
                }
            }
        }
        return stc;
    }
}
