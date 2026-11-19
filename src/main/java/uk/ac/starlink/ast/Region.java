package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * An area within a coordinate system: the abstract class of every Region shape, such as a {@link
 * Box}. A Region is a Frame, the Frame its area is described in, and also a Mapping: it transforms
 * a position inside the area into itself and a position outside it into bad values, in either
 * direction.
 *
 * <p>A Region holds a FrameSet whose base Frame is the Frame the Region was defined in and whose
 * current Frame is the Frame it now represents. As a Frame, a Region is its current Frame: its axes
 * and their attributes are those of the current Frame. Setting a Frame attribute that changes the
 * coordinate system remaps the current Frame when the Adaptive attribute is set, so that the
 * Region keeps covering the same area; otherwise the change is made to both Frames, so that the
 * Region keeps its coordinates in the changed system.
 *
 * <p>The attributes Negated, Closed, MeshSize, FillFactor, Adaptive and Bounded are the Region's
 * own. A change of Negated, Closed, MeshSize or the uncertainty, or of a Frame attribute of a
 * Region that is not Adaptive, discards what the Region has worked out about its boundary.
 *
 * <p>A Region works out its boundary when first asked and keeps it, so it may be used from any
 * thread, but not from two at once; give each thread its own {@link #copy}.
 *
 * <p>Ported from C {@code region.c}, with Rust {@code regions/mod.rs} and {@code regions/data.rs}.
 */
public abstract class Region extends Frame {

    /** The overlap of two Regions cannot be determined ({@link #overlap}). */
    public static final int OVERLAP_UNKNOWN = 0;

    /** Two Regions do not overlap ({@link #overlap}). */
    public static final int OVERLAP_NONE = 1;

    /** This Region is inside the other ({@link #overlap}). */
    public static final int OVERLAP_INSIDE = 2;

    /** The other Region is inside this one ({@link #overlap}). */
    public static final int OVERLAP_OUTSIDE = 3;

    /** Two Regions overlap partly ({@link #overlap}). */
    public static final int OVERLAP_PARTIAL = 4;

    /** Two Regions are the same ({@link #overlap}). */
    public static final int OVERLAP_SAME = 5;

    /** One Region is the negation of the other ({@link #overlap}). */
    public static final int OVERLAP_NEGATE = 6;

    /** The value of an unset integer attribute (C's {@code -INT_MAX}). */
    static final int UNSET = -Integer.MAX_VALUE;

    /** The Ident of the FrameSet a Region read without a Frame holds (C's {@code DUMMY_FS}). */
    static final String DUMMY_FS = "ASTREGION-DUMMY";

    /** The attribute names that are read-only on a Region. */
    private static final String[] READ_ONLY = {
        "class", "nin", "nobject", "bounded", "nout", "refcount", "tranforward", "traninverse"
    };

    /** The encapsulated FrameSet: base Frame the Frame of definition, current Frame the Frame represented. */
    private FrameSet frameset;

    /** The points defining the Region in the base Frame, {@code [ncoord][npoint]}, or null. */
    private double[][] points;

    /** The uncertainty Region set explicitly, in the base Frame, or null. */
    private Region unc;

    /** The default uncertainty Region, built when first needed (C's {@code defunc}), or null. */
    private Region defunc;

    /** The boundary mesh in the base Frame, kept once made (C's {@code basemesh}), or null. */
    private double[][] keptMesh;

    /** The grid over the Region in the base Frame, kept once made (C's {@code basegrid}), or null. */
    private double[][] keptGrid;

    /** A negated copy of the Region, kept once made (C's {@code negation}), or null. */
    private Region negation;

    /** The Negated attribute: 0, 1 or {@link #UNSET}. */
    private int negated = UNSET;

    /** The Closed attribute: 0, 1 or {@link #UNSET}. */
    private int closed = UNSET;

    /** The MeshSize attribute, or {@link #UNSET}. */
    private int meshSize = UNSET;

    /** The Adaptive attribute: 0, 1 or {@link #UNSET}. */
    private int adaptive = UNSET;

    /** The RegionFS attribute, whether a description includes the Frame: 0, 1 or {@link #UNSET}. */
    private int regionFs = UNSET;

    /** The FillFactor attribute, or {@link #AST__BAD} if it is not set. */
    private double fillFactor = AST__BAD;

    /** Whether the base and current Frames are taken to be the same (C's {@code nomap}). */
    private boolean nomap;

    /**
     * Creates a Region, as C's {@code astInitRegion} does: the Frame used is the current Frame of
     * {@code frame} if it is a Region or a FrameSet, and otherwise {@code frame}; the encapsulated
     * FrameSet holds two copies of it joined by a UnitMap; and {@code unc}, if given, is mapped
     * into it and held as the uncertainty.
     *
     * @param frame the Frame the Region is defined in
     * @param points the defining points, {@code [ncoord][npoint]}, which the Region holds, or null
     * @param unc the uncertainty, or null
     * @throws AstException with status {@code AST__NCPIN} if the points do not have a coordinate
     *     for each axis of the Frame, or the error of {@link #setUnc}
     */
    Region(Frame frame, double[][] points, Region unc) {
        super(0);
        int numAxes = frame.getNaxes();
        if (points != null && points.length != numAxes) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astInitRegion(" + astClass() + "): Bad number of coordinate values per point (" + points.length
                            + ").\nThe " + frame.astClass() + " given requires " + numAxes
                            + " coordinate value(s) for each point.");
        }
        Frame f0;
        if (frame instanceof Region) {
            f0 = ((Region) frame).frameset.getFrame(FrameSet.AST__CURRENT);
        } else if (frame instanceof FrameSet) {
            f0 = ((FrameSet) frame).getFrame(FrameSet.AST__CURRENT);
        } else {
            f0 = frame;
        }
        this.points = points;
        storeRegFS(f0);
        storeUnc(unc);
    }

    /**
     * Loads a Region from its description, as C's {@code astLoadRegion} does: the Frame part, then
     * Negate, Fill, MeshSz, Closed and Adapt, each passed through its setter; the defining points
     * ({@code Points}, a PointSet) or the number of axes ({@code RegAxes}); the uncertainty
     * ({@code Unc}); and the Frame ({@code Frm}), from which the FrameSet is made, or the FrameSet
     * ({@code FrmSet}) with RegFS. A description with neither gets a FrameSet of a plain Frame
     * marked as a stand-in, which the Region holding this one replaces.
     *
     * @throws AstException with status {@code AST__REGIN} if the points are not a PointSet, the
     *     uncertainty is not a Region, the Frame or FrameSet is not one, or the points do not have
     *     a coordinate for each axis; or {@code AST__ATSER} for a FillFactor outside 0 to 1
     */
    Region(NativeNode node) {
        super(node);
        NativeNode part = node.section("Region");
        int ival = part.readInt("Negate", UNSET);
        if (ival != UNSET) {
            negated = ival != 0 ? 1 : 0;
        }
        double dval = part.readDouble("Fill", AST__BAD);
        if (dval != AST__BAD) {
            fillFactor = checkedFillFactor(dval);
        }
        ival = part.readInt("MeshSz", UNSET);
        if (ival != UNSET) {
            meshSize = ival > 5 ? ival : 5;
        }
        ival = part.readInt("Closed", UNSET);
        if (ival != UNSET) {
            closed = ival != 0 ? 1 : 0;
        }
        ival = part.readInt("Adapt", UNSET);
        if (ival != UNSET) {
            adaptive = ival != 0 ? 1 : 0;
        }
        int naxpt;
        NativeNode desc = part.readObject("Points");
        if (desc != null) {
            AstObject obj = ObjectRegistry.loadNested(desc, "Points");
            if (!(obj instanceof PointSet)) {
                throw Ast.err(
                        AstException.AST__REGIN,
                        "astLoadRegion(" + astClass() + "): Corrupt " + astClass() + " specifies points using a "
                                + obj.astClass() + " (should be a PointSet).");
            }
            PointSet ps = (PointSet) obj;
            naxpt = ps.getNcoord();
            points = ps.points();
        } else {
            naxpt = part.readInt("RegAxes", 0);
        }
        desc = part.readObject("Unc");
        if (desc != null) {
            AstObject obj = ObjectRegistry.loadNested(desc, "Unc");
            if (!(obj instanceof Region)) {
                throw Ast.err(
                        AstException.AST__REGIN,
                        "astLoadRegion(" + astClass() + "): Corrupt " + astClass()
                                + " specifies its uncertainty using a " + obj.astClass() + " (should be a Region).");
            }
            unc = (Region) obj;
        }
        int numAxes;
        desc = part.readObject("Frm");
        if (desc != null) {
            AstObject obj = ObjectRegistry.loadNested(desc, "Frm");
            if (!(obj instanceof Frame)) {
                throw Ast.err(
                        AstException.AST__REGIN,
                        "astLoadRegion(" + astClass() + "): Corrupt " + astClass() + " specifies its Frame using a "
                                + obj.astClass() + " (should be a Frame).");
            }
            regionFs = 1;
            numAxes = ((Frame) obj).getNaxes();
            storeRegFS((Frame) obj);
        } else {
            desc = part.readObject("FrmSet");
            if (desc != null) {
                AstObject obj = ObjectRegistry.loadNested(desc, "FrmSet");
                if (!(obj instanceof FrameSet)) {
                    throw Ast.err(
                            AstException.AST__REGIN,
                            "astLoadRegion(" + astClass() + "): Corrupt " + astClass()
                                    + " specifies its FrameSet using a " + obj.astClass() + " (should be a FrameSet).");
                }
                frameset = (FrameSet) obj;
                numAxes = frameset.getNin();
                regionFs = part.readInt("RegFS", 1) != 0 ? 1 : 0;
                if (unc != null && !unc.getRegionFS()) {
                    unc.setRegFS(frameset.getFrame(FrameSet.AST__BASE));
                }
            } else {
                numAxes = 0;
            }
        }
        if (frameset == null) {
            numAxes = naxpt != 0 ? naxpt : 1;
            frameset = new FrameSet(new Frame(numAxes));
            frameset.setIdent(DUMMY_FS);
            regionFs = 0;
        }
        if (points != null && naxpt != numAxes) {
            throw Ast.err(
                    AstException.AST__REGIN,
                    "astLoadRegion(" + astClass() + "): Corrupt " + astClass()
                            + " contains  incorrect number of coordinate values per point (" + naxpt + ").\nThe "
                            + astClass() + " requires " + numAxes + " coordinate value(s) for each point.");
        }
    }

    @Override
    String dumpComment() {
        return "An area within a coordinate system";
    }

    /**
     * Writes the Region class's part of a description, as C's {@code Dump} (region.c) does, after
     * the Frame part, which describes the current Frame: Negate, Fill, MeshSz, Closed and Adapt;
     * the current Frame ({@code Frm}) if the Mapping from the base Frame is a UnitMap, unless
     * RegionFS is zero, and otherwise the FrameSet ({@code FrmSet}); RegFS; the defining points or
     * the number of axes; and the uncertainty, if one is set.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        boolean set;
        int ival = getNegated() ? 1 : 0;
        node.writeInt("Negate", ival != 0, false, ival, ival != 0 ? "Region negated" : "Region not negated");
        set = testFillFactor();
        node.writeDouble("Fill", set, false, getFillFactor(), "Region fill factor");
        set = testMeshSize();
        node.writeInt("MeshSz", set, false, getMeshSize(), "No. of points used to represent boundary");
        set = testClosed();
        ival = getClosed() ? 1 : 0;
        node.writeInt("Closed", set, false, ival, ival != 0 ? "Boundary is inside" : "Boundary is outside");
        set = testAdaptive();
        ival = getAdaptive() ? 1 : 0;
        node.writeInt(
                "Adapt",
                ival != 0 || set,
                false,
                ival,
                ival != 0 ? "Region adapts to coord sys changes" : "Region does not adapt to coord sys changes");
        ival = getRegionFS() ? 1 : 0;
        if (isUnitMap(regMapping())) {
            set = false;
            if (ival != 0) {
                node.writeObject(
                        "Frm",
                        true,
                        true,
                        NativeNode.of(frameset.getFrame(FrameSet.AST__CURRENT)),
                        "Coordinate system");
            }
        } else {
            set = ival == 0;
            node.writeObject("FrmSet", true, true, NativeNode.of(frameset), "Original & current coordinate systems");
        }
        node.writeInt("RegFS", set, false, ival, ival != 0 ? "Include Frame in dump" : "Do not include Frame in dump");
        if (points != null) {
            node.writeObject("Points", true, true, NativeNode.of(new PointSet(points)), "Points defining the shape");
        } else {
            node.writeInt("RegAxes", true, true, getNaxes(), "Number of axes spanned by the Region");
        }
        if (testUnc()) {
            node.writeObject(
                    "Unc",
                    true,
                    true,
                    NativeNode.of(getUncFrm(FrameSet.AST__BASE)),
                    "Region defining positional uncertainties.");
        }
    }

    /**
     * Completes a copy as C's {@code Copy} (region.c) does: the FrameSet, points, kept boundary
     * and grid, uncertainties and negation are copied, and RegionFS is cleared in the copy.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        frameset = (FrameSet) frameset.copy();
        points = copyPoints(points);
        keptMesh = copyPoints(keptMesh);
        keptGrid = copyPoints(keptGrid);
        if (unc != null) {
            unc = (Region) unc.copy();
        }
        if (negation != null) {
            negation = (Region) negation.copy();
        }
        if (defunc != null) {
            defunc = (Region) defunc.copy();
        }
        regionFs = UNSET;
    }

    /** A deep copy of {@code [ncoord][npoint]} values, or null. */
    static double[][] copyPoints(double[][] p) {
        if (p == null) {
            return null;
        }
        double[][] out = new double[p.length][];
        for (int i = 0; i < p.length; i++) {
            out[i] = p[i].clone();
        }
        return out;
    }

    /** Whether {@code map} is a UnitMap, as C's {@code astIsAUnitMap} tests. */
    static boolean isUnitMap(Mapping map) {
        return "UnitMap".equals(map.astClass());
    }

    // ------------------------------------------------------------------
    // Region attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Negated attribute: whether the Region has been negated, so that it covers the area
     * outside its boundary. The default is false.
     *
     * @return the Negated attribute
     */
    public boolean getNegated() {
        return negated != UNSET && negated != 0;
    }

    /**
     * Sets the Negated attribute. A change discards what the Region has worked out about its
     * boundary.
     *
     * @param negated whether the Region is negated
     */
    public void setNegated(boolean negated) {
        setB("Negated", negated);
    }

    /** Sets Negated (C's protected {@code astSetNegated}), discarding the kept boundary. */
    void setNegatedValue(boolean value) {
        resetCache();
        negated = value ? 1 : 0;
    }

    /** Whether Negated is set (C's {@code astTestNegated}). */
    boolean testNegated() {
        return negated != UNSET;
    }

    /** Clears Negated (C's {@code astClearNegated}), discarding the kept boundary. */
    void clearNegated() {
        resetCache();
        negated = UNSET;
    }

    /**
     * Gets the Closed attribute: whether the boundary of the Region is inside it. The default is
     * true.
     *
     * @return the Closed attribute
     */
    public boolean getClosed() {
        return closed == UNSET || closed != 0;
    }

    /**
     * Sets the Closed attribute. A change discards what the Region has worked out about its
     * boundary.
     *
     * @param closed whether the boundary is inside the Region
     */
    public void setClosed(boolean closed) {
        setB("Closed", closed);
    }

    /** Sets Closed (C's protected {@code astSetClosed}), discarding the kept boundary. */
    void setClosedValue(boolean value) {
        resetCache();
        closed = value ? 1 : 0;
    }

    /** Whether Closed is set (C's {@code astTestClosed}). */
    boolean testClosed() {
        return closed != UNSET;
    }

    /** Clears Closed (C's {@code astClearClosed}), discarding the kept boundary. */
    void clearClosed() {
        resetCache();
        closed = UNSET;
    }

    /**
     * Gets the MeshSize attribute: the number of points used to describe the boundary of the
     * Region. The default is 2 for a Region of one axis, 200 for two axes and 2000 for more.
     *
     * @return the MeshSize attribute
     */
    public int getMeshSize() {
        if (meshSize != UNSET) {
            return meshSize;
        }
        int numAxes = getNaxes();
        return numAxes == 1 ? 2 : (numAxes == 2 ? 200 : 2000);
    }

    /**
     * Sets the MeshSize attribute; a value below 5 is taken as 5. A change discards what the Region
     * has worked out about its boundary.
     *
     * @param meshSize the number of points on the boundary
     */
    public void setMeshSize(int meshSize) {
        setI("MeshSize", meshSize);
    }

    /** Sets MeshSize (C's protected {@code astSetMeshSize}), at least 5, discarding the kept boundary. */
    void setMeshSizeValue(int value) {
        resetCache();
        meshSize = value > 5 ? value : 5;
    }

    /** Whether MeshSize is set (C's {@code astTestMeshSize}). */
    boolean testMeshSize() {
        return meshSize != UNSET;
    }

    /** Clears MeshSize (C's {@code astClearMeshSize}), discarding the kept boundary. */
    void clearMeshSize() {
        resetCache();
        meshSize = UNSET;
    }

    /**
     * Gets the FillFactor attribute: the fraction of the Region's bounding box the Region fills.
     * The default is 1.
     *
     * @return the FillFactor attribute
     */
    public double getFillFactor() {
        return fillFactor == AST__BAD ? 1.0 : fillFactor;
    }

    /**
     * Sets the FillFactor attribute.
     *
     * @param fillFactor the fraction, from 0 to 1
     * @throws AstException with status {@code AST__ATSER} if the value is outside 0 to 1
     */
    public void setFillFactor(double fillFactor) {
        setD("FillFactor", fillFactor);
    }

    /** Sets FillFactor (C's protected {@code astSetFillFactor}). */
    void setFillFactorValue(double value) {
        fillFactor = checkedFillFactor(value);
    }

    /** {@code value} if it is a valid FillFactor, from 0 to 1. */
    private double checkedFillFactor(double value) {
        if (value < 0.0 || value > 1.0) {
            throw Ast.err(
                    AstException.AST__ATSER,
                    "astSetFillFactor(" + astClass() + "): Invalid value (" + CFormat.format("%g", value)
                            + ") supplied for attribute FillFactor.\n"
                            + "FillFactor values should be in the range 0.0 to 1.0");
        }
        return value;
    }

    /** Whether FillFactor is set (C's {@code astTestFillFactor}). */
    boolean testFillFactor() {
        return fillFactor != AST__BAD;
    }

    /** Clears FillFactor (C's {@code astClearFillFactor}). */
    void clearFillFactor() {
        fillFactor = AST__BAD;
    }

    /**
     * Gets the Adaptive attribute: whether the Region adapts to a change of the coordinate system
     * of its Frame, so that it keeps covering the same area. The default is true.
     *
     * @return the Adaptive attribute
     */
    public boolean getAdaptive() {
        return adaptive == UNSET || adaptive != 0;
    }

    /**
     * Sets the Adaptive attribute.
     *
     * @param adaptive whether the Region adapts to a change of coordinate system
     */
    public void setAdaptive(boolean adaptive) {
        setB("Adaptive", adaptive);
    }

    /** Sets Adaptive (C's protected {@code astSetAdaptive}). */
    void setAdaptiveValue(boolean value) {
        adaptive = value ? 1 : 0;
    }

    /** Whether Adaptive is set (C's {@code astTestAdaptive}). */
    boolean testAdaptive() {
        return adaptive != UNSET;
    }

    /** Clears Adaptive (C's {@code astClearAdaptive}). */
    void clearAdaptive() {
        adaptive = UNSET;
    }

    /** The RegionFS attribute: whether a description includes the Frame; true if not set. */
    boolean getRegionFS() {
        return regionFs == UNSET || regionFs != 0;
    }

    /** Sets RegionFS (C's {@code astSetRegionFS}). */
    void setRegionFS(boolean value) {
        regionFs = value ? 1 : 0;
    }

    /** Whether RegionFS is set (C's {@code astTestRegionFS}). */
    boolean testRegionFS() {
        return regionFs != UNSET;
    }

    /** Clears RegionFS (C's {@code astClearRegionFS}). */
    void clearRegionFS() {
        regionFs = UNSET;
    }

    /**
     * Gets the Bounded attribute: whether the Region is bounded, so that it has a finite boundary
     * enclosing it.
     *
     * @return the Bounded attribute
     */
    public boolean getBounded() {
        return isBoundedInner();
    }

    /**
     * Whether the Region is bounded (C's {@code GetBounded}): this version, region.c's for a
     * Region made of closed curves, is bounded unless negated, and always bounded in a SkyFrame,
     * where a finite area has a finite negation.
     */
    boolean isBoundedInner() {
        return frameset.getFrame(FrameSet.AST__BASE) instanceof SkyFrame || !getNegated();
    }

    // ------------------------------------------------------------------
    // Attributes by name
    // ------------------------------------------------------------------

    private static boolean isReadOnly(String attrib) {
        for (String name : READ_ONLY) {
            if (name.equals(attrib)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code attrib} is a FrameSet attribute that a Region does not pass on. */
    private static boolean isFrameSetAttrib(String attrib) {
        return "base".equals(attrib) || "current".equals(attrib) || "nframe".equals(attrib);
    }

    /**
     * C's {@code GetAttrib} (region.c): the Region's own attributes, and those of an Object and a
     * Mapping it keeps; Base, Current and Nframe have no value; any other name is the encapsulated
     * FrameSet's, so its current Frame's.
     */
    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "negated":
                return getNegated() ? "1" : "0";
            case "closed":
                return getClosed() ? "1" : "0";
            case "adaptive":
                return getAdaptive() ? "1" : "0";
            case "fillfactor":
                return CFormat.format("%.15g", getFillFactor());
            case "meshsize":
                return Integer.toString(getMeshSize());
            case "bounded":
                return getBounded() ? "1" : "0";
            case "class":
                return astClass();
            case "id":
                return getID();
            case "ident":
                return getIdent();
            case "invert":
                return getInvert() ? "1" : "0";
            case "nin":
                return Integer.toString(getNin());
            case "nobject":
                return Integer.toString(getNobject());
            case "nout":
                return Integer.toString(getNout());
            case "refcount":
                return Integer.toString(getRefCount());
            case "report":
                return getReport() ? "1" : "0";
            case "tranforward":
                return getTranForward() ? "1" : "0";
            case "traninverse":
                return getTranInverse() ? "1" : "0";
            default:
                if (isFrameSetAttrib(attrib)) {
                    return null;
                }
                return frameset.getAttrib(attrib);
        }
    }

    /**
     * C's {@code SetAttrib} (region.c): the Region's own attributes, and those of an Object and a
     * Mapping it keeps. Settings of Base, Current and Nframe are ignored. Any other setting is made
     * to the encapsulated FrameSet, which remaps its current Frame where the change requires it,
     * if the Region is Adaptive, and otherwise to the current and base Frames alike ({@link
     * #regSetAttrib}).
     */
    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        Double dval = CScan.scanDouble(value);
        if ("negated".equals(attrib) && ival != null) {
            setNegatedValue(ival != 0);
        } else if ("closed".equals(attrib) && ival != null) {
            setClosedValue(ival != 0);
        } else if ("fillfactor".equals(attrib) && dval != null) {
            setFillFactorValue(dval);
        } else if ("meshsize".equals(attrib) && ival != null) {
            setMeshSizeValue(ival);
        } else if ("adaptive".equals(attrib) && ival != null) {
            setAdaptiveValue(ival != 0);
        } else if ("id".equals(attrib)) {
            setID(value);
        } else if ("ident".equals(attrib)) {
            setIdent(value);
        } else if ("invert".equals(attrib) && ival != null) {
            setInvert(ival != 0);
        } else if ("report".equals(attrib) && ival != null) {
            setReport(ival != 0);
        } else if (isReadOnly(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else if (!isFrameSetAttrib(attrib)) {
            if (getAdaptive()) {
                frameset.set(attrib + "=" + value);
            } else {
                regSetAttrib(attrib, value);
            }
        }
    }

    /**
     * C's {@code TestAttrib} (region.c): the Region's own attributes, and those of an Object and a
     * Mapping it keeps; a read-only attribute, Base, Current and Nframe are never set; any other
     * name is tested on the encapsulated FrameSet.
     */
    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "negated":
                return testNegated();
            case "closed":
                return testClosed();
            case "fillfactor":
                return testFillFactor();
            case "meshsize":
                return testMeshSize();
            case "adaptive":
                return testAdaptive();
            case "id":
            case "ident":
                return super.testAttrib(attrib);
            case "invert":
                return testInvert();
            case "report":
                return testReport();
            default:
                if (isReadOnly(attrib) || isFrameSetAttrib(attrib)) {
                    return false;
                }
                return frameset.testAttrib(attrib);
        }
    }

    /**
     * C's {@code ClearAttrib} (region.c): the Region's own attributes, and those of an Object and
     * a Mapping it keeps. Base, Current and Nframe are ignored. Any other attribute is cleared on
     * the encapsulated FrameSet, which remaps its current Frame where the change requires it, if
     * the Region is Adaptive, and otherwise on the current and base Frames alike ({@link
     * #regClearAttrib}).
     */
    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "negated":
                clearNegated();
                break;
            case "closed":
                clearClosed();
                break;
            case "fillfactor":
                clearFillFactor();
                break;
            case "meshsize":
                clearMeshSize();
                break;
            case "adaptive":
                clearAdaptive();
                break;
            case "id":
            case "ident":
                super.clearAttrib(attrib);
                break;
            case "invert":
                clearInvert();
                break;
            case "report":
                clearReport();
                break;
            default:
                if (isReadOnly(attrib)) {
                    throw Ast.err(
                            AstException.AST__NOWRT,
                            "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                                    + ".\nThis is a read-only attribute.");
                }
                if (!isFrameSetAttrib(attrib)) {
                    if (getAdaptive()) {
                        frameset.clear(attrib);
                    } else {
                        regClearAttrib(attrib);
                    }
                }
        }
    }

    /** The Region's UseDefs if set, otherwise its current Frame's (C's {@code GetUseDefs}). */
    @Override
    boolean getUseDefs() {
        if (super.testAttrib("usedefs")) {
            return super.getUseDefs();
        }
        return frameset.getFrame(FrameSet.AST__CURRENT).getUseDefs();
    }

    /**
     * The axis number in {@code attrib} if it is {@code name(n)}, or null, as C's {@code
     * "%[^(](%d)"} reads it in {@code RegSetAttrib} and {@code RegClearAttrib}.
     */
    private static Integer attribAxis(String attrib) {
        int open = attrib.indexOf('(');
        if (open <= 0) {
            return null;
        }
        return axisArg(attrib, attrib.substring(0, open));
    }

    /**
     * The base Frame axis, from 1, that current Frame axis {@code axis} feeds alone, found by
     * splitting the Mapping from the current Frame to the base Frame, as {@code RegSetAttrib} and
     * {@code RegClearAttrib} (region.c) find it.
     *
     * @throws AstException with status {@code AST__INTER} if there is no single such axis
     */
    private int baseAxis(int axis, String what) {
        Mapping map = frameset.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
        MapSplit split = map.mapSplit(new int[] {axis - 1});
        if (split != null && split.map.getNout() == 1) {
            return split.outputs[0] + 1;
        }
        throw Ast.err(
                AstException.AST__INTER,
                what + "\nThere is no base Frame axis corresponding to current Frame axis " + axis + "\n");
    }

    /**
     * Sets a Frame attribute on the current and base Frames of the encapsulated FrameSet without
     * remapping either (C's {@code RegSetAttrib}), and on the uncertainty, whose current Frame is
     * the base Frame. An attribute of a current Frame axis is set on the base Frame axis that axis
     * alone feeds. An attribute the base Frame or the uncertainty does not have is ignored there.
     * What the Region has worked out about its boundary is discarded.
     *
     * <p>As C reads the setting, the axis is changed for the base Frame only when the value is a
     * single word: a value holding white space is set on the base Frame axis of the same number.
     *
     * @param attrib the attribute name, in lower case without white space
     * @param value the value
     * @return the attribute name as set on the base Frame
     * @throws AstException with status {@code AST__INTER} if no single base Frame axis corresponds
     *     to the axis of the attribute
     */
    String regSetAttrib(String attrib, String value) {
        frameset.getFrame(FrameSet.AST__CURRENT).setAttrib(attrib, value);
        String battrib = attrib;
        Integer axis = attribAxis(attrib);
        if (axis != null && isOneWord(value)) {
            int baxis = baseAxis(
                    axis,
                    "astRegSetAttrib(" + astClass() + "): Unable to apply attribute setting \"" + attrib + "=" + value
                            + "\" to the base Frame in the " + astClass());
            if (baxis != axis) {
                battrib = attrib.substring(0, attrib.indexOf('(')) + "(" + baxis + ")";
            }
        }
        try {
            frameset.getFrame(FrameSet.AST__BASE).setAttrib(battrib, value);
            if (testUnc()) {
                getUncFrm(FrameSet.AST__BASE).regSetAttrib(battrib, value);
            }
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        resetCache();
        return battrib;
    }

    /**
     * Whether a value is a single word, with nothing else but white space around it, as C's {@code
     * "= %n%*s %n"} reads it.
     */
    private static boolean isOneWord(String value) {
        String t = value.trim();
        if (t.isEmpty()) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (CScan.isCSpace(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Clears a Frame attribute on the current and base Frames of the encapsulated FrameSet without
     * remapping either (C's {@code RegClearAttrib}), and on the uncertainty, as {@link
     * #regSetAttrib} sets one. What the Region has worked out about its boundary is discarded.
     *
     * @param attrib the attribute name, in lower case without white space
     * @return the attribute name as cleared on the base Frame
     * @throws AstException with status {@code AST__INTER} if no single base Frame axis corresponds
     *     to the axis of the attribute
     */
    String regClearAttrib(String attrib) {
        frameset.getFrame(FrameSet.AST__CURRENT).clearAttrib(attrib);
        String battrib = attrib;
        Integer axis = attribAxis(attrib);
        if (axis != null) {
            int baxis = baseAxis(
                    axis,
                    "astRegClearAttrib(" + astClass() + "): Unable to clear attribute \"" + attrib
                            + "\" in the base Frame of the " + astClass());
            if (baxis != axis) {
                battrib = attrib.substring(0, attrib.indexOf('(')) + "(" + baxis + ")";
            }
        }
        try {
            frameset.getFrame(FrameSet.AST__BASE).clearAttrib(battrib);
            if (testUnc()) {
                getUncFrm(FrameSet.AST__BASE).regClearAttrib(battrib);
            }
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        resetCache();
        return battrib;
    }

    // ------------------------------------------------------------------
    // The encapsulated FrameSet
    // ------------------------------------------------------------------

    /**
     * Gets a copy of the Frame the Region represents (C's {@code astGetRegionFrame}): changing it
     * does not change the Region.
     *
     * @return a copy of the current Frame of the Region
     */
    public Frame getRegionFrame() {
        return (Frame) frameset.getFrame(FrameSet.AST__CURRENT).copy();
    }

    /** A copy of the encapsulated FrameSet (C's {@code astGetRegionFrameSet}). */
    FrameSet regionFrameSet() {
        return (FrameSet) frameset.copy();
    }

    /** The encapsulated FrameSet itself, not a copy (C's {@code astGetRegFS}). */
    FrameSet getRegFS() {
        return frameset;
    }

    /** The current Frame of the encapsulated FrameSet, the FrameSet's own (C's {@code astRegFrame}). */
    Frame regFrame() {
        return frameset.getFrame(FrameSet.AST__CURRENT);
    }

    /** The base Frame of the encapsulated FrameSet, the FrameSet's own. */
    Frame baseFrame() {
        return frameset.getFrame(FrameSet.AST__BASE);
    }

    /**
     * The simplified Mapping from the base Frame to the current Frame, or a UnitMap while the two
     * are taken to be the same (C's {@code astRegMapping}).
     */
    Mapping regMapping() {
        if (nomap) {
            return new UnitMap(frameset.getNin());
        }
        return frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
    }

    /** Whether the FrameSet is the stand-in a description without a Frame gives (C's {@code astRegDummyFS}). */
    boolean regDummyFS() {
        return DUMMY_FS.equals(frameset.getIdent());
    }

    /**
     * Gives the Region a FrameSet holding two copies of {@code frm} joined by a UnitMap (C's
     * {@code astSetRegFS}); an uncertainty read without a Frame gets {@code frm} too.
     */
    void setRegFS(Frame frm) {
        storeRegFS(frm);
    }

    /** region.c's own {@code SetRegFS}, which the constructor and loader use. */
    private void storeRegFS(Frame frm) {
        Frame f1 = (Frame) frm.copy();
        Frame f2 = (Frame) f1.copy();
        FrameSet fs = new FrameSet(f1);
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(f1.getNaxes()), f2);
        frameset = fs;
        if (unc != null && !unc.getRegionFS()) {
            unc.setRegFS(f1);
        }
    }

    /** The defining points in the base Frame, the Region's own, or null. */
    double[][] getPoints() {
        return points;
    }

    /**
     * Transforms points between the base and current Frames of the encapsulated FrameSet (C's
     * {@code astRegTransform}): base to current if {@code forward}. The result is {@code in} itself
     * when the Mapping is a UnitMap.
     *
     * @param in the points, {@code [ncoord][npoint]}, or null for the defining points
     */
    double[][] regTransform(double[][] in, boolean forward) {
        double[][] pts = in;
        if (pts == null) {
            if (points == null) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astRegTransform(" + astClass() + "): No PointSet supplied and the supplied " + astClass()
                                + " has no PointSet (internal AST programming error)");
            }
            pts = points;
        }
        Mapping smap = regMapping();
        if (isUnitMap(smap)) {
            return pts;
        }
        return smap.transform(pts, pts.length == 0 ? 0 : pts[0].length, forward);
    }

    /**
     * Transforms one point between the base and current Frames of the encapsulated FrameSet (C's
     * {@code astRegTranPoint}): base to current if {@code forward}.
     */
    double[] regTranPoint(double[] in, boolean forward) {
        Mapping map = forward
                ? frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                : frameset.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
        int naxin = map.getNin();
        double[][] pin = new double[naxin][1];
        for (int ic = 0; ic < naxin; ic++) {
            pin[ic][0] = in[ic];
        }
        double[][] pout = map.transform(pin, 1, true);
        double[] out = new double[pout.length];
        for (int ic = 0; ic < pout.length; ic++) {
            out[ic] = pout[ic][0];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Uncertainty
    // ------------------------------------------------------------------

    /**
     * Sets the uncertainty of positions in the Region: a Region, such as a Box, Circle or Ellipse,
     * centred on a typical position, that each position may lie anywhere within. A copy of it is
     * held, mapped into the Frame the Region was defined in, re-centred on the Region's first
     * defining point, and negated if it is not bounded. Any uncertainty already set is discarded
     * first, even if the new one is rejected, and what the Region has worked out about its boundary
     * is discarded.
     *
     * @param unc the uncertainty
     * @throws AstException with status {@code AST__NCPIN} if the uncertainty cannot be re-centred,
     *     or cannot be converted into the Frame of the Region
     */
    public void setUnc(Region unc) {
        Objects.requireNonNull(unc, "unc");
        storeUnc(unc);
        resetCache();
    }

    /**
     * region.c's own {@code SetUnc}: the uncertainty given is mapped into the base Frame and held,
     * or, if null, the uncertainty is cleared; what the Region has worked out about its boundary is
     * discarded if the uncertainty changes. The constructor uses it, as C's initialiser uses the
     * private function rather than a class's own.
     */
    private void storeUnc(Region given) {
        boolean changed = false;
        if (unc != null) {
            unc = null;
            changed = true;
        }
        double[] cen0 = given != null ? given.regCentre(null, null, 0, FrameSet.AST__CURRENT) : null;
        try {
            if (cen0 != null) {
                FrameSet fs = frameset;
                fs.invert();
                FrameSet fs2;
                try {
                    fs2 = conv(given.frameset, fs);
                } finally {
                    fs.invert();
                }
                if (fs2 != null) {
                    Mapping map = fs2.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                    Frame frm = fs2.getFrame(FrameSet.AST__CURRENT);
                    unc = given.mapRegionInto(map, frm);
                    if (!unc.getBounded()) {
                        unc.negate();
                    }
                    Mapping map2 = unc.frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                    if (isUnitMap(map2.simplify())) {
                        unc.setRegionFS(false);
                    }
                    if (points != null) {
                        unc.regCentre(null, points, 0, FrameSet.AST__CURRENT);
                    }
                    changed = true;
                } else {
                    throw Ast.err(
                            AstException.AST__NCPIN,
                            "astSetUnc(" + astClass() + "): Bad " + given.getNaxes()
                                    + " dimensional uncertainty Frame ("
                                    + given.getDomain() + " " + given.getTitle() + ") supplied.\n"
                                    + "Cannot convert it to the Frame of the new " + astClass() + ".");
                }
            } else if (given != null) {
                throw Ast.err(
                        AstException.AST__NCPIN,
                        "astSetUnc(" + astClass() + "): Bad uncertainty shape (" + given.astClass() + ") supplied.\n"
                                + "The uncertainty Region must be an instance of a centro-symetric subclass of Region "
                                + "(e.g. Box, Circle, Ellipse, etc).");
            }
        } finally {
            if (changed) {
                resetRegionCache();
            }
        }
    }

    /**
     * The conversion between two FrameSets (C's {@code Conv}, region.c): {@code from} converted to
     * {@code to} in the Domain of {@code to}, their Base attributes restored afterwards.
     */
    private static FrameSet conv(FrameSet from, FrameSet to) {
        int toBase = to.getBase();
        int fromBase = from.getBase();
        String dom = to.getDomain();
        FrameSet result = from.convert(to, dom);
        to.setBase(toBase);
        from.setBase(fromBase);
        return result;
    }

    /**
     * Gets the uncertainty of positions in the Region, in the Frame the Region represents (C's
     * {@code astGetUnc}). The Region returned is a copy, which includes its Frame when written.
     *
     * @param def whether to return the default uncertainty when none has been set; if false and
     *     none has been set, null is returned
     * @return the uncertainty, or null
     */
    public Region getUnc(boolean def) {
        if (def || testUnc()) {
            Region result = (Region) getUncFrm(FrameSet.AST__CURRENT).copy();
            result.clearRegionFS();
            return result;
        }
        return null;
    }

    /**
     * The uncertainty Region, set or default, in the base Frame ({@code AST__BASE}) or the current
     * Frame ({@code AST__CURRENT}) (C's {@code astGetUncFrm}). In the base Frame, or when the base
     * and current Frames are joined by a UnitMap, it is the Region's own, so that a change made to
     * it changes the Region; otherwise it is mapped into the current Frame.
     */
    Region getUncFrm(int ifrm) {
        Region u;
        if (unc != null) {
            u = unc;
        } else {
            if (defunc == null) {
                defunc = getDefUnc();
            }
            u = defunc;
        }
        if (ifrm == FrameSet.AST__BASE) {
            return u;
        }
        Mapping map = frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        if (isUnitMap(map)) {
            return u;
        }
        return u.mapRegionInto(map, frameset.getFrame(FrameSet.AST__CURRENT));
    }

    /**
     * The default uncertainty, in the base Frame (C's {@code GetDefUnc}): a Box centred on the
     * centre of the Region's bounding box, one millionth of its width on each axis. An axis on
     * which the box has no width gets one millionth of the axis value, and an axis at zero
     * 1.0E-12; an unbounded axis gets no width, at zero.
     */
    Region getDefUnc() {
        Frame bfrm = frameset.getFrame(FrameSet.AST__BASE);
        int numAxes = bfrm.getNaxes();
        double[] lbnd = new double[numAxes];
        double[] ubnd = new double[numAxes];
        regBaseBox2(lbnd, ubnd);
        for (int i = 0; i < numAxes; i++) {
            if (ubnd[i] != Double.MAX_VALUE && lbnd[i] != -Double.MAX_VALUE) {
                double hw = Math.abs(0.5E-6 * (ubnd[i] - lbnd[i]));
                double c = 0.5 * (ubnd[i] + lbnd[i]);
                if (hw == 0.0) {
                    hw = c * 0.5E-6;
                }
                if (hw == 0.0) {
                    hw = 0.5E-12;
                }
                ubnd[i] = c + hw;
                lbnd[i] = c - hw;
            } else {
                ubnd[i] = 0.0;
                lbnd[i] = 0.0;
            }
        }
        return new Box(bfrm, 1, lbnd, ubnd, null);
    }

    /** Whether an uncertainty has been set (C's {@code astTestUnc}). */
    final boolean testUnc() {
        return unc != null;
    }

    /** Clears the uncertainty (C's {@code astClearUnc}), discarding the kept boundary if one was set. */
    void clearUnc() {
        if (unc != null) {
            unc = null;
            resetCache();
        }
        defunc = null;
    }

    /** The uncertainty set, the Region's own, or null. */
    Region explicitUnc() {
        return unc;
    }

    /** The default uncertainty built so far, the Region's own, or null. */
    Region defaultUncCache() {
        return defunc;
    }

    // ------------------------------------------------------------------
    // What the Region keeps
    // ------------------------------------------------------------------

    /**
     * Discards what the Region has worked out about its boundary (C's {@code astResetCache}): the
     * boundary mesh, the grid and the negation. The default uncertainty is kept, as C keeps it. A
     * class that keeps more overrides this, calling it.
     */
    void resetCache() {
        resetRegionCache();
    }

    /** region.c's own {@code ResetCache}. */
    private void resetRegionCache() {
        keptMesh = null;
        keptGrid = null;
        negation = null;
    }

    /** Discards the kept boundary mesh and grid, as a class does when it moves the Region. */
    final void discardMeshAndGrid() {
        keptMesh = null;
        keptGrid = null;
    }

    // ------------------------------------------------------------------
    // The hooks each class provides
    // ------------------------------------------------------------------

    /**
     * Which base Frame points lie inside the Region as if it were not negated (Rust's {@code
     * reg_base_inside}): a point on the boundary is inside if {@link #closedForBaseInside} is true.
     * A point with a bad coordinate may be given either answer; {@link #regBaseMask} makes it
     * outside.
     *
     * @param base the points in the base Frame, {@code [ncoord][npoint or more]}
     * @param npoint the number of points
     * @return for each point, whether it is inside
     */
    abstract boolean[] regBaseInside(double[][] base, int npoint);

    /**
     * The boundary strictness {@link #regBaseInside} uses, given that {@link #regBaseMask} inverts
     * its answer when the Region is negated: a point on the boundary of a Region is in it if Closed
     * is set, negated or not, so the un-negated test must take the boundary as inside when Closed
     * and Negated differ.
     */
    final boolean closedForBaseInside() {
        return getClosed() != getNegated();
    }

    /**
     * Which base Frame points lie in the Region, allowing for Negated: what the class's {@code
     * Transform} (C) decides once the points are in the base Frame. This version is the test of
     * every class whose {@code Transform} decides so: inside the un-negated Region unless negated,
     * and outside wherever a coordinate is bad. A class whose C {@code Transform} decides otherwise
     * overrides it.
     *
     * @param base the points in the base Frame, {@code [ncoord][npoint or more]}
     * @param npoint the number of points
     * @return for each point, whether it is in the Region
     */
    boolean[] regBaseMask(double[][] base, int npoint) {
        boolean neg = getNegated();
        boolean[] mask = regBaseInside(base, npoint);
        for (int i = 0; i < npoint; i++) {
            mask[i] = mask[i] != neg;
        }
        for (double[] axis : base) {
            for (int i = 0; i < npoint; i++) {
                if (isBad(axis[i])) {
                    mask[i] = false;
                }
            }
        }
        return mask;
    }

    /**
     * The bounding box of the un-negated Region in the base Frame (C's {@code astRegBaseBox}).
     *
     * @param lbnd receives the lower bound on each base Frame axis
     * @param ubnd receives the upper bound on each base Frame axis
     */
    abstract void regBaseBox(double[] lbnd, double[] ubnd);

    /**
     * The bounding box of the Region in the base Frame, with any Regions it holds taken as not
     * negated (C's {@code astRegBaseBox2}). This version is {@link #regBaseBox}; a class holding
     * other Regions overrides it.
     */
    void regBaseBox2(double[] lbnd, double[] ubnd) {
        regBaseBox(lbnd, ubnd);
    }

    /**
     * A mesh of points spread evenly over the boundary of the Region in the base Frame, {@code
     * [ncoord][npoint]} (C's {@code astRegBaseMesh}). The class keeps it, through {@link
     * #keptBaseMesh} and {@link #keepBaseMesh}, until the Region changes; the array returned is that
     * kept, which the caller must not change.
     */
    abstract double[][] regBaseMesh();

    /** The boundary mesh kept by {@link #keepBaseMesh}, or null. */
    final double[][] keptBaseMesh() {
        return keptMesh;
    }

    /** Keeps a boundary mesh until the Region changes. */
    final void keepBaseMesh(double[][] mesh) {
        keptMesh = mesh;
    }

    /**
     * A grid of points spread over the inside of the Region in the base Frame, {@code
     * [ncoord][npoint]} (C's {@code astRegBaseGrid}), kept until the Region changes; the array
     * returned is that kept, which the caller must not change. This version, region.c's, makes a
     * grid over the Region's base Frame bounding box with a Box, keeps the points inside the
     * Region, and adjusts the Box's grid size by the ratio by which the count missed MeshSize,
     * until the count is within 5% of MeshSize or three grids have been made. Box has its own.
     *
     * @return the grid, or null if the Region is not bounded
     * @throws AstException with status {@code AST__NPTIN} if no grid point lies in the Region
     */
    double[][] regBaseGrid() {
        if (keptGrid != null) {
            return keptGrid;
        }
        if (!getBounded()) {
            return null;
        }
        int meshsize = getMeshSize();
        int naxb = frameset.getNin();
        double[] lbnd = new double[naxb];
        double[] ubnd = new double[naxb];
        regBaseBox(lbnd, ubnd);
        Box box = new Box(frameset.getFrame(FrameSet.AST__BASE), 1, lbnd, ubnd, null);
        int ipr = 0;
        int np = meshsize;
        int ntry = 0;
        int npnt2 = 0;
        double[][] result = null;
        while (ntry++ < 3) {
            box.setMeshSizeValue(np);
            double[][] ps1 = box.regBaseGrid();
            npnt2 = ps1[0].length;
            double[][] ps2 = bTransform(ps1, npnt2);
            result = new double[naxb][npnt2];
            ipr = 0;
            for (int ip = 0; ip < npnt2; ip++) {
                boolean good = true;
                for (int ic = 0; ic < naxb; ic++) {
                    if (ps2[ic][ip] == AST__BAD) {
                        good = false;
                        break;
                    }
                    result[ic][ipr] = ps2[ic][ip];
                }
                if (good) {
                    ipr++;
                }
            }
            if (Math.abs(ipr - meshsize) / (double) meshsize < 0.05) {
                break;
            }
            if (ntry < 3) {
                if (ipr == 0) {
                    np *= 10;
                } else {
                    np = (int) (np * ((double) meshsize / ipr));
                }
            }
        }
        if (ipr < 1) {
            throw Ast.err(
                    AstException.AST__NPTIN,
                    "astSetNpoint(PointSet): Number of points (" + ipr + ") is not valid.\nShould be in the range 1 to "
                            + npnt2 + ".");
        }
        double[][] trimmed = new double[naxb][];
        for (int ic = 0; ic < naxb; ic++) {
            trimmed[ic] = Arrays.copyOf(result[ic], ipr);
        }
        keptGrid = trimmed;
        return keptGrid;
    }

    /** The grid kept by the class's {@link #regBaseGrid}, or null. */
    final double[][] keptBaseGrid() {
        return keptGrid;
    }

    /** Keeps a grid until the Region changes. */
    final void keepBaseGrid(double[][] grid) {
        keptGrid = grid;
    }

    /**
     * The centre of the Region, or a change of it (C's {@code astRegCentre}). With {@code cen} and
     * {@code ptr} both null, the centre in the base Frame ({@code ifrm} {@code AST__BASE}) or the
     * current Frame ({@code AST__CURRENT}) is returned; otherwise the Region is moved so that its
     * centre is {@code cen}, or point {@code index} of {@code ptr}, in that Frame, and null is
     * returned. This version, region.c's, cannot move a Region, and returns null for its centre.
     *
     * @throws AstException with status {@code AST__NOIMP} if asked to move the Region
     */
    double[] regCentre(double[] cen, double[][] ptr, int index, int ifrm) {
        if (ptr != null || cen != null) {
            throw Ast.err(
                    AstException.AST__NOIMP,
                    "astRegCentre(" + astClass() + "): The " + astClass()
                            + " class does not implement the astRegCentre "
                            + "method inherited from the Region class (internal AST programming error).");
        }
        return null;
    }

    /**
     * Whether every point of {@code pset}, in the base Frame, is on the boundary of the Region,
     * allowing for the uncertainty of the Region and that of the points, {@code unc}, in the base
     * Frame (C's {@code astRegPins}). This version, region.c's, is for a class that cannot tell.
     *
     * @param pset the points, {@code [ncoord][npoint]}
     * @param pointUnc the uncertainty of the points, or null
     * @param mask null, or an array of at least one element per point, which receives whether each
     *     point is on the boundary
     * @throws AstException with status {@code AST__NOIMP}
     */
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        throw Ast.err(
                AstException.AST__NOIMP,
                "astRegPins(" + astClass() + "): The " + astClass() + " class does not implement the astRegPins "
                        + "method inherited from the Region class (internal AST programming error).");
    }

    /**
     * A Region of the base Frame axes {@code axes}, numbered from 0, or null if none can be made
     * (C's {@code astRegBasePick}). This version, region.c's, makes one only when the axes are all
     * the base Frame's: a copy of the Region in its base Frame, its axes permuted as {@code axes}
     * gives them.
     */
    Region regBasePick(int[] axes) {
        Frame fr = frameset.getFrame(FrameSet.AST__BASE);
        int numAxes = fr.getNaxes();
        if (numAxes != axes.length) {
            return null;
        }
        boolean unit = true;
        for (int i = 0; i < numAxes; i++) {
            boolean found = false;
            for (int j = 0; j < numAxes; j++) {
                if (axes[j] == i) {
                    if (found) {
                        return null;
                    }
                    found = true;
                    if (i != j) {
                        unit = false;
                    }
                }
            }
            if (!found) {
                return null;
            }
        }
        Region result = (Region) copy();
        result.setRegFS(fr);
        if (!unit) {
            int[] perm = new int[numAxes];
            for (int i = 0; i < numAxes; i++) {
                perm[i] = axes[i] + 1;
            }
            result.permAxes(perm);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Boundary, bounds and points
    // ------------------------------------------------------------------

    /** The boundary mesh in the base Frame, kept by the Region, which the caller must not change. */
    final double[][] baseMesh() {
        return regBaseMesh();
    }

    /**
     * The boundary mesh in the current Frame (C's {@code astRegMesh}); the base Frame mesh itself
     * when the Mapping between the Frames is a UnitMap.
     */
    double[][] regMesh() {
        double[][] bmesh = regBaseMesh();
        Mapping map = regMapping();
        if (isUnitMap(map)) {
            return bmesh;
        }
        return map.transform(bmesh, bmesh[0].length, true);
    }

    /**
     * The grid over the Region in the current Frame (C's {@code astRegGrid}); the base Frame grid
     * itself when the Mapping between the Frames is a UnitMap.
     */
    final double[][] regGrid() {
        double[][] grid = keptGrid != null ? keptGrid : regBaseGrid();
        if (grid == null) {
            throw Ast.err(
                    AstException.AST__MBBNF,
                    "astRegGrid(" + astClass() + "): The " + astClass()
                            + " is unbounded so no grid can be created to cover it.");
        }
        Mapping map = regMapping();
        if (isUnitMap(map)) {
            return grid;
        }
        return map.transform(grid, grid[0].length, true);
    }

    /**
     * Gets the bounds of a box that just encloses the Region, in the Frame it represents (C's
     * {@code astGetRegionBounds}). Negated is ignored. On an axis without a limit, the bound is
     * the largest negative or positive double.
     *
     * @return two arrays, the lower bound and the upper bound on each axis
     */
    public double[][] getRegionBounds() {
        int ncur = getNaxes();
        double[] lbnd = new double[ncur];
        double[] ubnd = new double[ncur];
        regionBounds(lbnd, ubnd);
        return new double[][] {lbnd, ubnd};
    }

    /**
     * C's {@code GetRegionBounds}, into the arrays given, which have an element for each axis of
     * the current Frame. This version is region.c's.
     */
    void regionBounds(double[] lbnd, double[] ubnd) {
        Mapping smap = regMapping();
        if (isUnitMap(smap)) {
            regBaseBox(lbnd, ubnd);
            return;
        }
        double[][] bmesh;
        if (getBounded()) {
            bmesh = regBaseMesh();
        } else {
            int nbase = smap.getNin();
            double[] blbnd = new double[nbase];
            double[] bubnd = new double[nbase];
            regBaseBox(blbnd, bubnd);
            int npos = 1 << nbase;
            bmesh = new double[nbase][npos];
            int jmax = 1;
            for (int i = 0; i < nbase; i++) {
                double[] p = bmesh[i];
                boolean lo = true;
                int j = 0;
                for (int ip = 0; ip < npos; ip++, j++) {
                    if (j == jmax) {
                        lo = !lo;
                        j = 0;
                    }
                    p[ip] = lo ? blbnd[i] : bubnd[i];
                }
                jmax *= 2;
            }
        }
        double[][] cmesh = smap.transform(bmesh, bmesh[0].length, true);
        Frame frm = frameset.getFrame(FrameSet.AST__CURRENT);
        int ncur = frm.getNaxes();
        int npos = cmesh[0].length;
        for (int i = 0; i < ncur; i++) {
            double[] vals = Arrays.copyOf(cmesh[i], npos);
            frm.axNorm(i + 1, 1, vals);
            cmesh[i] = vals;
        }
        bndPoints(cmesh, npos, lbnd, ubnd);
        for (int i = 0; i < ncur; i++) {
            double width = frm.axDistance(i + 1, lbnd[i], ubnd[i]);
            if (width != AST__BAD) {
                ubnd[i] = lbnd[i] + width;
            } else {
                ubnd[i] = AST__BAD;
                lbnd[i] = AST__BAD;
            }
        }
    }

    /** The bounds of the good values on each axis, bad where there are none (C's {@code astBndPoints}). */
    static void bndPoints(double[][] ptr, int np, double[] lbnd, double[] ubnd) {
        for (int ic = 0; ic < ptr.length; ic++) {
            double lb = AST__BAD;
            double ub = AST__BAD;
            double[] p = ptr[ic];
            int ip = 0;
            for (; ip < np; ip++) {
                if (p[ip] != AST__BAD) {
                    lb = p[ip];
                    ub = p[ip];
                    break;
                }
            }
            for (; ip < np; ip++) {
                if (p[ip] != AST__BAD) {
                    if (p[ip] < lb) {
                        lb = p[ip];
                    } else if (p[ip] > ub) {
                        ub = p[ip];
                    }
                }
            }
            lbnd[ic] = lb;
            ubnd[ic] = ub;
        }
    }

    /**
     * The bounds of the Region in the current Frame, with any Regions it holds taken as not
     * negated (C's {@code astGetRegionBounds2}): the base Frame box of {@link #regBaseBox2}, mapped
     * with {@code astMapBox} unless the Mapping between the Frames is a UnitMap.
     */
    final void regionBounds2(double[] lbnd, double[] ubnd) {
        int nbase = frameset.getNin();
        int ncur = frameset.getNout();
        double[] lbndb = new double[nbase];
        double[] ubndb = new double[nbase];
        regBaseBox2(lbndb, ubndb);
        Mapping smap = regMapping();
        if (isUnitMap(smap)) {
            for (int i = 0; i < ncur; i++) {
                lbnd[i] = lbndb[i];
                ubnd[i] = ubndb[i];
            }
        } else {
            for (int i = 0; i < ncur; i++) {
                double[] b = MapBox.mapBox(smap, lbndb, ubndb, true, i, null, null);
                lbnd[i] = b[0];
                ubnd[i] = b[1];
            }
        }
    }

    /**
     * Gets the points that define the Region, in the Frame it represents (C's {@code
     * astGetRegionPoints}). What they are depends on the class; for a {@link Box}, the centre and
     * a corner.
     *
     * @return the points, {@code [naxes][npoint]}
     */
    public double[][] getRegionPoints() {
        int naxes = getNaxes();
        if (points == null || points[0].length == 0) {
            return new double[naxes][0];
        }
        return frameset.transform(points, points[0].length, true);
    }

    /**
     * Whether a point, in the Frame the Region represents, is in the Region (C's {@code
     * astPointInRegion}): whether its first good coordinate is left good by the Region.
     */
    final boolean pointInRegion(double[] point) {
        int naxes = getNaxes();
        double[][] in = new double[naxes][1];
        for (int i = 0; i < naxes; i++) {
            in[i][0] = point[i];
        }
        double[][] out = transform(in, 1, true);
        for (int i = 0; i < naxes; i++) {
            if (point[i] != AST__BAD) {
                return out[i][0] != AST__BAD;
            }
        }
        return false;
    }

    /**
     * Negates the Region, so that the points inside it are outside and those outside are inside,
     * by toggling Negated.
     */
    public void negate() {
        setNegatedValue(!getNegated());
    }

    /** A negated copy of the Region, kept until the Region changes (C's {@code astGetNegation}). */
    final Region getNegation() {
        if (negation == null) {
            negation = (Region) copy();
            negation.negate();
        }
        return negation;
    }

    /**
     * Gives this Region the Negated, Closed, RegionFS and Adaptive values of {@code that}, and its
     * MeshSize and FillFactor if set and the two have the same number of axes (cleared otherwise),
     * and clears the uncertainty if {@code clearUnc} and {@code that} has none (C's {@code
     * astRegOverlay}).
     */
    void regOverlay(Region that, boolean clearUnc) {
        negated = that.negated;
        closed = that.closed;
        regionFs = that.regionFs;
        adaptive = that.adaptive;
        if (getNaxes() == that.getNaxes()) {
            if (that.testMeshSize()) {
                setMeshSizeValue(that.getMeshSize());
            }
            if (that.testFillFactor()) {
                setFillFactorValue(that.getFillFactor());
            }
        } else {
            clearMeshSize();
            clearFillFactor();
        }
        if (clearUnc && !that.testUnc()) {
            clearUnc();
        }
    }

    // ------------------------------------------------------------------
    // Mapping a Region into another Frame
    // ------------------------------------------------------------------

    /**
     * Maps the Region into another Frame (C's {@code astMapRegion}): the result covers the area
     * of {@code frame} that the Mapping takes the Region to, simplified. Its class may differ from
     * this Region's. The uncertainty is mapped too.
     *
     * @param map a Mapping from the Frame the Region represents to {@code frame}, with both
     *     transformations, which should be the inverse of each other; for a FrameSet, its Mapping
     *     from base to current Frame
     * @param frame the Frame of the new Region; for a FrameSet, its current Frame
     * @return the new Region
     * @throws AstException with status {@code AST__NODEF} if the Mapping lacks a transformation,
     *     or makes a defining point bad
     */
    public Region mapRegion(Mapping map, Frame frame) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(frame, "frame");
        Mapping simple = mapRegionInto(map, frame).simplify();
        return (Region) simple;
    }

    /**
     * C's protected {@code astMapRegion} (region.c {@code MapRegion}): a copy of the Region whose
     * FrameSet gains {@code frame0}, reached by {@code map0} from the current Frame, as its new
     * current Frame in place of the old, without simplification. When {@code frame0} has fewer
     * axes than the Region, base Frame axes the Mapping's inverse does not need are dropped, unless
     * the defining points give them good values.
     */
    Region mapRegionInto(Mapping map0, Frame frame0) {
        Mapping map = map0 instanceof FrameSet
                ? ((FrameSet) map0).getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                : map0;
        Frame frame = frame0 instanceof FrameSet ? ((FrameSet) frame0).getFrame(FrameSet.AST__CURRENT) : frame0;
        if (!map.getTranInverse()) {
            throw Ast.err(
                    AstException.AST__NODEF,
                    "astMapRegion(" + astClass() + "): The supplied " + map.astClass()
                            + " does not define an inverse transformation.");
        } else if (!map.getTranForward()) {
            throw Ast.err(
                    AstException.AST__NODEF,
                    "astMapRegion(" + astClass() + "): The supplied " + map.astClass()
                            + " does not define a forward transformation.");
        }
        int nold = getNaxes();
        int nnew = frame.getNaxes();
        boolean[] axflags = null;
        if (points != null) {
            int nax1 = points.length;
            int np = points[0].length;
            boolean ok = true;
            for (int i = 0; i < nax1 && ok; i++) {
                for (int j = 0; j < np; j++) {
                    if (points[i][j] == AST__BAD) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                double[][] pst = regTransform(points, true);
                double[][] ps2 = map.transform(pst, np, true);
                for (int i = 0; i < nnew && ok; i++) {
                    for (int j = 0; j < np; j++) {
                        if (ps2[i][j] == AST__BAD) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (!ok) {
                    throw Ast.err(
                            AstException.AST__NODEF,
                            "astMapRegion(" + astClass() + "): The region which results from using the supplied "
                                    + map.astClass() + " to transform the supplied " + astClass() + " is undefined.");
                }
                double[][] back = map.transform(ps2, np, false);
                axflags = new boolean[nold];
                for (int i = 0; i < nold; i++) {
                    for (int j = 0; j < np; j++) {
                        if (back[i][j] != AST__BAD) {
                            axflags[i] = true;
                            break;
                        }
                    }
                }
            }
        }
        Region usethis = this;
        Mapping usemap = map;
        if (nnew < nold) {
            Mapping inv = (Mapping) map.copy();
            inv.invert();
            int[] inax = new int[nnew];
            for (int i = 0; i < nnew; i++) {
                inax[i] = i;
            }
            MapSplit split = inv.mapSplit(inax);
            if (split != null) {
                int nkept = split.map.getNout();
                int[] outax = split.outputs;
                int ntotal = 0;
                int[] keep = null;
                if (axflags != null) {
                    keep = new int[nold];
                    for (int i = 0; i < nold; i++) {
                        boolean kept = false;
                        for (int j = 0; j < nkept; j++) {
                            if (outax[j] == i) {
                                keep[ntotal++] = i;
                                kept = true;
                                break;
                            }
                        }
                        if (!kept && axflags[i]) {
                            keep[ntotal++] = i;
                        }
                    }
                }
                if (ntotal == nkept) {
                    usethis = pickedRegion(nkept, outax);
                    usemap = split.map;
                    usemap.invert();
                } else if (ntotal > nkept) {
                    MapSplit split2 = map.mapSplit(Arrays.copyOf(keep, ntotal));
                    if (split2 != null) {
                        usethis = pickedRegion(ntotal, keep);
                        usemap = split2.map;
                    }
                }
            }
        }
        Region result = (Region) usethis.copy();
        FrameSet fs = result.frameset;
        int icurr = fs.getCurrent();
        fs.addFrame(FrameSet.AST__CURRENT, usemap, frame);
        fs.removeFrame(icurr);
        result.setRegionFS(true);
        result.resetCache();
        return result;
    }

    /**
     * The Region of the first {@code n} of {@code axes}, numbered from 0, that {@link #pickAxes}
     * makes.
     *
     * @throws AstException with status {@code AST__INTER} if it makes a Frame that is not a
     *     Region, which C would go on to use as a Region
     */
    private Region pickedRegion(int n, int[] axes) {
        int[] axes1 = new int[n];
        for (int i = 0; i < n; i++) {
            axes1[i] = axes[i] + 1;
        }
        Frame picked = pickAxes(n, axes1, null);
        if (!(picked instanceof Region)) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astMapRegion(" + astClass() + "): The axes of the " + astClass() + " picked are a "
                            + picked.astClass() + ", not a Region (internal AST programming error).");
        }
        return (Region) picked;
    }

    // ------------------------------------------------------------------
    // The Region as a Mapping
    // ------------------------------------------------------------------

    /**
     * region.c {@code Equal}: {@code that} is of the same class, with equal defining points
     * (pointset.c {@code Equal}), equal base and current Frames, an equal Mapping between them, and
     * the same Negated and Closed values.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!astClass().equals(that.astClass())) {
            return false;
        }
        Region other = (Region) that;
        if ((points == null) != (other.points == null)) {
            return false;
        }
        if (points != null && !new PointSet(points).equalTo(new PointSet(other.points))) {
            return false;
        }
        if (!frameset.getFrame(FrameSet.AST__BASE).equalTo(other.frameset.getFrame(FrameSet.AST__BASE))) {
            return false;
        }
        if (!frameset.getFrame(FrameSet.AST__CURRENT).equalTo(other.frameset.getFrame(FrameSet.AST__CURRENT))) {
            return false;
        }
        Mapping m1 = frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping m2 = other.frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        return m1.equalTo(m2) && getNegated() == other.getNegated() && getClosed() == other.getClosed();
    }

    /**
     * Copies the input to the output, then makes bad every point outside the Region (the
     * class's {@code Transform}, C): the points are taken into the base Frame and tested by {@link
     * #regBaseMask}. The forward and inverse transformations are the same.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        int ncoord = in.length;
        for (int c = 0; c < ncoord; c++) {
            double[] a = in[c];
            double[] o = out[c];
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = Double.isNaN(v) ? AST__BAD : v;
            }
        }
        double[][] base = baseTransform(in, npoint);
        boolean[] mask = regBaseMask(base, npoint);
        for (int p = 0; p < npoint; p++) {
            if (!mask[p]) {
                for (int c = 0; c < ncoord; c++) {
                    out[c][p] = AST__BAD;
                }
            }
        }
    }

    /** The first {@code npoint} points of {@code in}, in the current Frame, taken into the base Frame. */
    private double[][] baseTransform(double[][] in, int npoint) {
        Mapping smap = regMapping();
        if (isUnitMap(smap)) {
            return in;
        }
        return smap.transform(in, npoint, false);
    }

    /**
     * Transforms under the NaN protocol as a Mapping without its own does: {@link #transformInto},
     * then each bad value written made NaN.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        transformInto(in, npoint, forward, out);
        for (int c = 0; c < out.length && c < in.length; c++) {
            double[] axis = out[c];
            for (int p = 0; p < npoint; p++) {
                if (axis[p] == AST__BAD) {
                    axis[p] = Double.NaN;
                }
            }
        }
    }

    /**
     * Transforms base Frame points as if the base and current Frames were the same (C's {@code
     * astBTransform}).
     */
    // regMapping reads nomap while the transformation runs.
    @SuppressWarnings("PMD.UnusedAssignment")
    final double[][] bTransform(double[][] in, int npoint) {
        boolean old = nomap;
        nomap = true;
        try {
            return transform(in, npoint, true);
        } finally {
            nomap = old;
        }
    }

    /** Reports each value as the current Frame formats it (C's {@code ReportPoints}, region.c). */
    @Override
    void reportPoints(boolean forward, double[][] in, double[][] out, int npoint) {
        frameset.getFrame(FrameSet.AST__CURRENT).reportPoints(forward, in, out, npoint);
    }

    /**
     * Simplifies the Region (C's {@code Simplify}, region.c): a copy whose FrameSet is simplified,
     * whose default uncertainty is dropped, and whose uncertainty is replaced by its simplification
     * when that does not change size across the Region. The Region itself is returned if nothing
     * simplifies.
     */
    @Override
    Mapping simplifyStep() {
        return parentSimplify();
    }

    /** region.c's {@code Simplify}, which each class's own starts from (Rust's {@code parent_region_simplify}). */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    final Region parentSimplify() {
        Region nw = (Region) copy();
        Mapping fs = nw.frameset.simplify();
        boolean simpler = fs != nw.frameset;
        if (simpler) {
            nw.frameset = (FrameSet) fs;
        }
        nw.defunc = null;
        if (nw.testUnc()) {
            Region u = nw.getUncFrm(FrameSet.AST__BASE);
            Mapping map = frameset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            int nin = map.getNin();
            int nout = map.getNout();
            if (nin != nout) {
                double[] cen = u.regCentre(null, null, 0, FrameSet.AST__CURRENT);
                double[][] ptr1 = new double[nin][1];
                for (int ic = 0; ic < nin; ic++) {
                    ptr1[ic][0] = cen[ic];
                }
                double[][] pset2 = map.transform(ptr1, 1, true);
                ptr1 = map.transform(pset2, 1, false);
                u.regCentre(null, ptr1, 0, FrameSet.AST__CURRENT);
            }
            Mapping sunc = u.simplify();
            if (sunc != u) {
                Region su = (Region) sunc;
                double[] origCen = su.regCentre(null, null, 0, FrameSet.AST__BASE);
                int naxb = frameset.getNin();
                double[] lbnd = new double[naxb];
                double[] ubnd = new double[naxb];
                if (getBounded()) {
                    regBaseBox(lbnd, ubnd);
                } else {
                    su.regionBounds(lbnd, ubnd);
                    for (int ic = 0; ic < naxb; ic++) {
                        double delta = 0.5 * Math.abs(ubnd[ic] - lbnd[ic]);
                        lbnd[ic] = origCen[ic] - delta;
                        ubnd[ic] = origCen[ic] + delta;
                    }
                }
                su.regCentre(lbnd, null, 0, FrameSet.AST__CURRENT);
                double[] s1Lbnd = new double[naxb];
                double[] s1Ubnd = new double[naxb];
                su.regionBounds(s1Lbnd, s1Ubnd);
                su.regCentre(ubnd, null, 0, FrameSet.AST__CURRENT);
                double[] s2Lbnd = new double[naxb];
                double[] s2Ubnd = new double[naxb];
                su.regionBounds(s2Lbnd, s2Ubnd);
                boolean ok = true;
                for (int ic = 0; ic < naxb; ic++) {
                    double w1 = s1Ubnd[ic] - s1Lbnd[ic];
                    double w2 = s2Ubnd[ic] - s2Lbnd[ic];
                    if (Math.abs(w1 - w2) > 0.005 * (Math.abs(w1) + Math.abs(w2))) {
                        ok = false;
                        break;
                    }
                }
                su.regCentre(origCen, null, 0, FrameSet.AST__BASE);
                if (ok) {
                    simpler = true;
                    nw.setUnc(su);
                }
            }
        }
        return simpler ? nw : this;
    }

    /**
     * This Region cast into the class of another Frame (C's {@code Cast}, region.c): a copy if
     * {@code obj} is a Region of this class, null for a Region of another class, and otherwise the
     * current Frame cast into the class of {@code obj}.
     */
    @Override
    Frame cast(Frame obj) {
        if (obj instanceof Region) {
            return obj.getClass() == getClass() ? (Frame) copy() : null;
        }
        return frameset.getFrame(FrameSet.AST__CURRENT).cast(obj);
    }

    // ------------------------------------------------------------------
    // Operations between Regions, and masking
    // ------------------------------------------------------------------

    /**
     * Tests how this Region and another overlap (C's {@code astOverlap}). The other Region is
     * converted into the coordinate system of this one; if that cannot be done, the overlap cannot
     * be determined. The Closed attribute is not considered in deciding that two Regions are the
     * same or each other's negation.
     *
     * <p>Unless the two are equal, this Region is negated in place and back again to compare its
     * negation with the other, as C does, so that its Negated attribute is set afterwards.
     *
     * @param that the other Region
     * @return {@link #OVERLAP_UNKNOWN} if the other Region cannot be converted into the coordinate
     *     system of this one, {@link #OVERLAP_NONE} if they do not overlap, {@link
     *     #OVERLAP_INSIDE} if this Region is inside the other, {@link #OVERLAP_OUTSIDE} if the other
     *     is inside this one, {@link #OVERLAP_PARTIAL} if they overlap partly, {@link
     *     #OVERLAP_SAME} if they are the same to within their uncertainties, or {@link
     *     #OVERLAP_NEGATE} if the other is the negation of this one to within their uncertainties
     * @throws AstException with status {@code AST__INTER} if neither Region has a finite boundary
     * @throws NullPointerException if {@code that} is null
     */
    public int overlap(Region that) {
        Objects.requireNonNull(that, "that");
        return overlapWith(that);
    }

    /**
     * C's {@code Overlap}, chosen by the class of this Region: how this Region and {@code that}
     * overlap. This version, region.c's, is {@code that}'s {@link #overlapX}, chosen by the class of
     * {@code that}.
     */
    int overlapWith(Region that) {
        return that.overlapX(this);
    }

    /**
     * C's {@code OverlapX( that, this )}, with this Region as {@code that}, chosen by the class of
     * this Region: how {@code thisRegion} and this Region overlap, as {@code thisRegion}'s {@link
     * #overlap} returns it. This version is region.c's ({@link RegionOverlap#overlap}).
     */
    int overlapX(Region thisRegion) {
        return RegionOverlap.overlap(thisRegion, this);
    }

    /**
     * C's {@code astBndBaseMesh}: the boundary mesh, in the base Frame, of the part of the Region
     * inside the box from {@code lbnd} to {@code ubnd} in the current Frame, or one point with every
     * coordinate bad if the boundary does not cross the box. This version is region.c's ({@link
     * RegionOverlap#bndBaseMesh}).
     */
    double[][] bndBaseMesh(double[] lbnd, double[] ubnd) {
        return RegionOverlap.bndBaseMesh(this, lbnd, ubnd);
    }

    /**
     * Masks the pixels of a grid of data inside or outside the Region (C's {@code astMask<X>}),
     * calling the method of {@code in}'s type: {@link #maskB}, {@link #maskS}, {@link #maskI},
     * {@link #maskL}, {@link #maskF} or {@link #maskD}.
     *
     * @param map the Mapping from the Frame the Region represents to grid coordinates, with an
     *     input for each axis of the Region and an output for each grid dimension; null if the
     *     Region is in grid coordinates already, as a UnitMap would make it
     * @param inside whether to set the pixels whose centres are inside the Region, rather than
     *     those whose centres are not inside it; which pixels are inside allows for Negated, so the
     *     inside of a Region is the outside of its negation
     * @param ndim the number of grid dimensions, at least one
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension; each pixel
     *     has unit extent, and the grid {@code ubnd[j] - lbnd[j] + 1} pixels on dimension {@code j}
     * @param in the data, an array of {@code byte}, {@code short}, {@code int}, {@code long}, {@code
     *     float} or {@code double} with an element for each pixel, the index of the first dimension
     *     varying fastest; the pixels masked are set to {@code val}, and the others left alone
     * @param val the value to set, of the {@code Number} class of {@code in}'s type
     * @return the number of pixels set to {@code val}
     * @throws IllegalArgumentException if {@code in} is not an array of one of those types, or
     *     {@code val} is not of the matching class
     * @throws AstException as for {@link #maskD}
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int mask(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, Object in, Number val) {
        // jniast casts the arguments to the type of in and reports any mismatch; the types are
        // checked here before the call so that an exception from within it is not mistaken for one.
        // As in jniast, null data is a NullPointerException.
        Objects.requireNonNull(in, "in");
        if (in instanceof byte[] && val instanceof Byte) {
            return maskB(map, inside, ndim, lbnd, ubnd, (byte[]) in, (Byte) val);
        } else if (in instanceof short[] && val instanceof Short) {
            return maskS(map, inside, ndim, lbnd, ubnd, (short[]) in, (Short) val);
        } else if (in instanceof int[] && val instanceof Integer) {
            return maskI(map, inside, ndim, lbnd, ubnd, (int[]) in, (Integer) val);
        } else if (in instanceof long[] && val instanceof Long) {
            return maskL(map, inside, ndim, lbnd, ubnd, (long[]) in, (Long) val);
        } else if (in instanceof float[] && val instanceof Float) {
            return maskF(map, inside, ndim, lbnd, ubnd, (float[]) in, (Float) val);
        } else if (in instanceof double[] && val instanceof Double) {
            return maskD(map, inside, ndim, lbnd, ubnd, (double[]) in, (Double) val);
        } else {
            throw new IllegalArgumentException("Bad class " + in.getClass() + " for map 'in' param");
        }
    }

    /**
     * Masks a grid of {@code byte} data (C's {@code astMaskB}), as {@link #mask} describes.
     *
     * @param map the Mapping from the Region's Frame to grid coordinates, or null
     * @param inside whether to set the pixels inside the Region rather than those not inside it
     * @param ndim the number of grid dimensions
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension
     * @param in the data, an element for each pixel
     * @param val the value to set
     * @return the number of pixels set to {@code val}
     * @throws AstException as for {@link #maskD}
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int maskB(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, byte[] in, byte val) {
        requireBounds(lbnd, ubnd);
        return maskData(
                map, inside, ndim, lbnd, ubnd, PixelArray.of(Objects.requireNonNull(in, "in"), val), "astMaskB");
    }

    /**
     * Masks a grid of {@code short} data (C's {@code astMaskS}), as {@link #mask} describes.
     *
     * @param map the Mapping from the Region's Frame to grid coordinates, or null
     * @param inside whether to set the pixels inside the Region rather than those not inside it
     * @param ndim the number of grid dimensions
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension
     * @param in the data, an element for each pixel
     * @param val the value to set
     * @return the number of pixels set to {@code val}
     * @throws AstException as for {@link #maskD}
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int maskS(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, short[] in, short val) {
        requireBounds(lbnd, ubnd);
        return maskData(
                map, inside, ndim, lbnd, ubnd, PixelArray.of(Objects.requireNonNull(in, "in"), val), "astMaskS");
    }

    /**
     * Masks a grid of {@code int} data (C's {@code astMaskI}), as {@link #mask} describes.
     *
     * @param map the Mapping from the Region's Frame to grid coordinates, or null
     * @param inside whether to set the pixels inside the Region rather than those not inside it
     * @param ndim the number of grid dimensions
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension
     * @param in the data, an element for each pixel
     * @param val the value to set
     * @return the number of pixels set to {@code val}
     * @throws AstException as for {@link #maskD}
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int maskI(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, int[] in, int val) {
        requireBounds(lbnd, ubnd);
        return maskData(
                map, inside, ndim, lbnd, ubnd, PixelArray.of(Objects.requireNonNull(in, "in"), val), "astMaskI");
    }

    /**
     * Masks a grid of {@code long} data (C's {@code astMaskL}), as {@link #mask} describes.
     *
     * @param map the Mapping from the Region's Frame to grid coordinates, or null
     * @param inside whether to set the pixels inside the Region rather than those not inside it
     * @param ndim the number of grid dimensions
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension
     * @param in the data, an element for each pixel
     * @param val the value to set
     * @return the number of pixels set to {@code val}
     * @throws AstException as for {@link #maskD}
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int maskL(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, long[] in, long val) {
        requireBounds(lbnd, ubnd);
        return maskData(
                map, inside, ndim, lbnd, ubnd, PixelArray.of(Objects.requireNonNull(in, "in"), val), "astMaskL");
    }

    /**
     * Masks a grid of {@code float} data (C's {@code astMaskF}), as {@link #mask} describes.
     *
     * @param map the Mapping from the Region's Frame to grid coordinates, or null
     * @param inside whether to set the pixels inside the Region rather than those not inside it
     * @param ndim the number of grid dimensions
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension
     * @param in the data, an element for each pixel
     * @param val the value to set
     * @return the number of pixels set to {@code val}
     * @throws AstException as for {@link #maskD}
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int maskF(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, float[] in, float val) {
        requireBounds(lbnd, ubnd);
        return maskData(
                map, inside, ndim, lbnd, ubnd, PixelArray.of(Objects.requireNonNull(in, "in"), val), "astMaskF");
    }

    /**
     * Masks a grid of {@code double} data (C's {@code astMaskD}), as {@link #mask} describes.
     *
     * <p>A pixel is inside the Region when its centre is. For a Region with no volume, such as a
     * PointList, a pixel is inside when any part of the Region falls in it. With no Mapping and
     * {@code inside} true, the Region is negated in place and back again while the data are masked,
     * as C does, so that its Negated attribute is set afterwards.
     *
     * @param map the Mapping from the Region's Frame to grid coordinates, or null
     * @param inside whether to set the pixels inside the Region rather than those not inside it
     * @param ndim the number of grid dimensions
     * @param lbnd the coordinate of the centre of the first pixel on each grid dimension
     * @param ubnd the coordinate of the centre of the last pixel on each grid dimension
     * @param in the data, an element for each pixel
     * @param val the value to set
     * @return the number of pixels set to {@code val}
     * @throws AstException with status {@code AST__NGDIN} if {@code map} does not have an input for
     *     each axis of the Region and an output for each grid dimension, or, with no {@code map},
     *     {@code ndim} is not the number of axes of the Region; {@code AST__DIMIN} if a bound array
     *     has fewer than {@code ndim} values or {@code in} fewer elements than the grid has pixels;
     *     or {@code AST__GBDIN} if a lower bound is above its upper bound
     * @throws NullPointerException if {@code lbnd}, {@code ubnd} or {@code in} is null, leaving
     *     the data unchanged
     */
    public int maskD(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, double[] in, double val) {
        requireBounds(lbnd, ubnd);
        return maskData(
                map, inside, ndim, lbnd, ubnd, PixelArray.of(Objects.requireNonNull(in, "in"), val), "astMaskD");
    }

    /** The null checks of the bound arrays of the {@code mask<X>} methods. */
    private static void requireBounds(int[] lbnd, int[] ubnd) {
        Objects.requireNonNull(lbnd, "lbnd");
        Objects.requireNonNull(ubnd, "ubnd");
    }

    /**
     * C's {@code Mask<X>}, chosen by the class of the Region: sets to the bad value of {@code in}
     * the pixels inside (or not inside) the Region, returning how many. This version is region.c's
     * ({@link RegionMask#mask}).
     *
     * @param method C's name of the function, such as {@code astMaskD}, for error messages
     */
    int maskData(Mapping map, boolean inside, int ndim, int[] lbnd, int[] ubnd, PixelArray in, String method) {
        return RegionMask.mask(this, map, inside, ndim, lbnd, ubnd, in, method);
    }

    /**
     * Writes a mesh of points on the boundary of the Region to standard output (C's {@code
     * astShowMesh}): the title, if given, between blank lines, then a line for each point holding
     * its value on each axis of the Frame the Region represents, each followed by a tab, then a line
     * {@code ENDMESH} and a blank line. The number of points is set by MeshSize.
     *
     * @param format whether to format the values with the Format of each axis, rather than as C's
     *     {@code %g} writes them
     * @param ttl a title to write first, or null for none
     * @throws AstException if the Region has no finite boundary to mesh
     */
    // C's astShowMesh writes the table to standard output with printf.
    @SuppressWarnings("PMD.SystemPrintln")
    public void showMesh(boolean format, String ttl) {
        String text = meshText(format, ttl);
        System.out.print(text);
        System.out.flush();
    }

    /**
     * The text {@link #showMesh} writes (region.c {@code ShowMesh}): the current Frame mesh (C's
     * {@code astRegMesh}), normalised, with a bad value written as {@code <bad>}.
     */
    String meshText(boolean format, String ttl) {
        double[][] mesh = regMesh();
        int np = mesh[0].length;
        double[][] ptr = normalPoints(mesh, np, true);
        int numAxes = ptr.length;
        StringBuilder text = new StringBuilder();
        if (ttl != null) {
            text.append('\n').append(ttl).append("\n\n");
        }
        for (int j = 0; j < np; j++) {
            for (int i = 0; i < numAxes; i++) {
                double v = ptr[i][j];
                if (v == AST__BAD) {
                    text.append("<bad>");
                } else if (format) {
                    text.append(format(i + 1, v));
                } else {
                    text.append(CFormat.format("%g", v));
                }
                text.append('\t');
            }
            text.append('\n');
        }
        text.append("ENDMESH\n\n");
        return text.toString();
    }

    // ------------------------------------------------------------------
    // The Region as a Frame: its current Frame
    // ------------------------------------------------------------------

    /**
     * Gets the number of axes of the Frame the Region represents.
     *
     * @return the number of axes
     */
    @Override
    public int getNaxes() {
        if (frameset == null) {
            // Only while the Frame part of a description is loaded, before the FrameSet is read, when
            // that part holds a value whose loading asks the current Frame. C's loader then reads
            // the FrameSet before it exists; this check has no counterpart in C.
            throw Ast.err(
                    AstException.AST__BADIN,
                    "astLoadRegion(" + astClass() + "): The Frame part of the description holds a value that needs "
                            + "the current Frame, which has not yet been read.");
        }
        return frameset.getNaxes();
    }

    @Override
    int[] getPerm() {
        return frameset.getPerm();
    }

    /** C's {@code ValidateAxis} (region.c): the axis is checked here, then by the current Frame. */
    @Override
    int validateAxis(int axis, boolean fwd, String method) {
        int naxes = getNaxes();
        if (naxes == 0) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Invalid attempt to use an axis index (" + axis + ") for a "
                            + astClass() + " which has no axes.");
        }
        if (axis < 1 || axis > naxes) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Axis index (" + axis + ") invalid - it should be in the range 1 to "
                            + naxes + ".");
        }
        return frameset.getFrame(FrameSet.AST__CURRENT).validateAxis(axis, fwd, "astValidateAxis");
    }

    @Override
    Axis getAxis(int axis) {
        validateAxis(axis, true, "astGetAxis");
        return frameset.getAxis(axis);
    }

    @Override
    void setAxis(int axis, Axis ax) {
        validateAxis(axis, true, "astSetAxis");
        frameset.getFrame(FrameSet.AST__CURRENT).setAxis(axis, ax);
    }

    @Override
    void checkPerm(int[] p, String method) {
        frameset.getFrame(FrameSet.AST__CURRENT).checkPerm(p, method);
    }

    /**
     * Permutes the order of the axes of the Frame the Region represents, remapping it so that the
     * Region stays where it is (C's {@code PermAxes}, region.c).
     *
     * @param perm the permutation, one element for each axis, numbered from 1
     * @throws AstException with status {@code AST__PRMIN} if {@code perm} does not hold each axis
     *     once, or {@code AST__DIMIN} if it is too short
     */
    @Override
    public void permAxes(int[] perm) {
        frameset.permAxes(perm);
    }

    @Override
    void validateAxisSelection(int nsel, int[] sel, String method) {
        frameset.getFrame(FrameSet.AST__CURRENT).validateAxisSelection(nsel, sel, method);
    }

    /**
     * Creates a new Frame from a selection of the axes of the Frame the Region represents (C's
     * {@code PickAxes}, region.c). When the axes chosen correspond to a set of base Frame axes from
     * which the class can make a Region, the result is that Region, mapped into the chosen axes;
     * otherwise it is a Frame of those axes.
     *
     * @param naxes the number of axes to pick
     * @param axes the axes to pick, numbered from 1; 0 selects a new default axis
     * @param map null, or an array of at least one element, which receives the Mapping from the
     *     Region's coordinates to the new Frame's
     * @return the new Region or Frame
     */
    @Override
    public Frame pickAxes(int naxes, int[] axes, Mapping[] map) {
        checkDim("astPickAxes", "axes", axes.length, naxes);
        if (map != null) {
            checkDim("astPickAxes", "map", map.length, 1);
        }
        int[] axes0 = new int[naxes];
        for (int i = 0; i < naxes; i++) {
            axes0[i] = axes[i] - 1;
        }
        validateAxisSelection(naxes, axes0, "astPickAxes");
        Frame cfrm = frameset.getFrame(FrameSet.AST__CURRENT);
        Frame frame = cfrm.pickAxes(naxes, axes, map);
        boolean def = false;
        for (int i = 0; i < naxes; i++) {
            if (axes0[i] < 0) {
                def = true;
            }
        }
        if (!def) {
            Mapping cbmap = frameset.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
            MapSplit split = cbmap.mapSplit(axes0);
            if (split != null) {
                int nbase = split.map.getNout();
                Region breg = regBasePick(Arrays.copyOf(split.outputs, nbase));
                if (breg != null) {
                    Mapping fsmap = split.map;
                    fsmap.invert();
                    Region creg = breg.mapRegionInto(fsmap, frame);
                    creg.regOverlay(this, false);
                    frame = creg;
                }
            }
        }
        return frame;
    }

    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        return frameset.getFrame(FrameSet.AST__CURRENT)
                .subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
    }

    @Override
    void overlay(int[] templateAxes, Frame result) {
        frameset.getFrame(FrameSet.AST__CURRENT).overlay(templateAxes, result);
    }

    /** A Region is never equivalent to a UnitMap (C's {@code IsUnitFrame}, region.c). */
    @Override
    boolean isUnitFrame() {
        return false;
    }

    /**
     * Finds a coordinate system with given characteristics in the Frame the Region represents (C's
     * {@code FindFrame}, region.c): the current Frame's own search.
     *
     * @param template the template, an instance of the kind of Frame wanted
     * @param domainlist a comma-separated list of Domains in order of priority
     * @return the conversion to the system found, or null if none is found
     */
    @Override
    public FrameSet findFrame(Frame template, String domainlist) {
        return frameset.getFrame(FrameSet.AST__CURRENT).findFrame(template, domainlist);
    }

    /** The current Frame of {@code f} if it is a Region, otherwise {@code f}. */
    private static Frame unwrap(Frame f) {
        return f instanceof Region ? ((Region) f).frameset.getFrame(FrameSet.AST__CURRENT) : f;
    }

    /** The conversion between the current Frames of the Regions given (C's {@code Convert}, region.c). */
    @Override
    FrameSet convertFrames(Frame to, String domainlist) {
        return frameset.getFrame(FrameSet.AST__CURRENT).convertFrames(unwrap(to), domainlist);
    }

    /** The conversion between the current Frames of the Regions given (C's {@code ConvertX}, region.c). */
    @Override
    FrameSet convertX(Frame from, String domainlist) {
        return frameset.getFrame(FrameSet.AST__CURRENT).convertX(unwrap(from), domainlist);
    }

    /** Matches the current Frame, as a template, against a target (C's {@code Match}, region.c). */
    @Override
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        return FrameFinder.match(frameset.getFrame(FrameSet.AST__CURRENT), target, matchSub);
    }

    /** The axes of the current Frame that correspond to those of {@code frm2} (C's {@code MatchAxes}, region.c). */
    @Override
    int[] matchAxes(Frame frm2) {
        return frm2.matchAxesX(frameset.getFrame(FrameSet.AST__CURRENT));
    }

    /** The axes of {@code frm1} that correspond to those of the current Frame (C's {@code MatchAxesX}, region.c). */
    @Override
    int[] matchAxesX(Frame frm1) {
        return frameset.getFrame(FrameSet.AST__CURRENT).matchAxesX(frm1);
    }

    /**
     * Formats a coordinate value for an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param value the value
     * @return the formatted value
     */
    @Override
    public String format(int axis, double value) {
        validateAxis(axis, true, "astFormat");
        return frameset.format(axis, value);
    }

    @Override
    int unformat(int axis, String string, double[] value) {
        validateAxis(axis, true, "astUnformat");
        return frameset.unformat(axis, string, value);
    }

    /**
     * Finds the angle at point B between the lines to points A and C, in the Frame the Region
     * represents.
     *
     * @param a point A
     * @param b point B
     * @param c point C
     * @return the angle in radians, or {@link #AST__BAD}
     */
    @Override
    public double angle(double[] a, double[] b, double[] c) {
        return frameset.angle(a, b, c);
    }

    /**
     * Finds the angle from an axis of the Frame the Region represents to the line between two
     * points.
     *
     * @param a the first point
     * @param b the second point
     * @param axis the axis number, from 1
     * @return the angle in radians, or {@link #AST__BAD}
     */
    @Override
    public double axAngle(double[] a, double[] b, int axis) {
        validateAxis(axis, true, "astAxAngle");
        return frameset.axAngle(a, b, axis);
    }

    /**
     * Finds the distance between two values on an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param v1 the first value
     * @param v2 the second value
     * @return the distance, or {@link #AST__BAD}
     */
    @Override
    public double axDistance(int axis, double v1, double v2) {
        validateAxis(axis, true, "astAxDistance");
        return frameset.axDistance(axis, v1, v2);
    }

    /**
     * Adds a distance to a value on an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param v1 the value
     * @param dist the distance
     * @return the new value, or {@link #AST__BAD}
     */
    @Override
    public double axOffset(int axis, double v1, double dist) {
        validateAxis(axis, true, "astAxOffset");
        return frameset.axOffset(axis, v1, dist);
    }

    @Override
    void axNorm(int axis, int oper, double[] values) {
        validateAxis(axis, true, "astAxNorm");
        frameset.axNorm(axis, oper, values);
    }

    /**
     * Finds the distance between two points in the Frame the Region represents.
     *
     * @param point1 the first point
     * @param point2 the second point
     * @return the distance, or {@link #AST__BAD}
     */
    @Override
    public double distance(double[] point1, double[] point2) {
        return frameset.distance(point1, point2);
    }

    /**
     * Finds the point a given distance along the line between two points in the Frame the Region
     * represents.
     *
     * @param point1 the first point
     * @param point2 the second point
     * @param offset the distance from the first point
     * @return the point
     */
    @Override
    public double[] offset(double[] point1, double[] point2, double offset) {
        return frameset.offset(point1, point2, offset);
    }

    /**
     * Finds the point a given distance from another in a given direction in the two-dimensional
     * Frame the Region represents.
     *
     * @param point1 the start point
     * @param angle the direction
     * @param offset the distance
     * @param point2 receives the point found
     * @return the direction at the point found
     */
    @Override
    public double offset2(double[] point1, double angle, double offset, double[] point2) {
        return frameset.offset2(point1, angle, offset, point2);
    }

    /**
     * Finds the point where two lines cross in the two-dimensional Frame the Region represents.
     *
     * @param a1 the start of the first line
     * @param a2 the end of the first line
     * @param b1 the start of the second line
     * @param b2 the end of the second line
     * @return the point where the lines cross
     */
    @Override
    public double[] intersect(double[] a1, double[] a2, double[] b1, double[] b2) {
        return frameset.intersect(a1, a2, b1, b2);
    }

    /**
     * Normalises a point in the Frame the Region represents.
     *
     * @param value the point, normalised in place
     */
    @Override
    public void norm(double[] value) {
        frameset.norm(value);
    }

    @Override
    double[][] normalPoints(double[][] in, int npoint, boolean contig) {
        return frameset.getFrame(FrameSet.AST__CURRENT).normalPoints(in, npoint, contig);
    }

    /**
     * Resolves a vector into components parallel and perpendicular to another in the Frame the
     * Region represents.
     *
     * @param point1 the start of the basis vector
     * @param point2 the end of the basis vector
     * @param point3 the end of the vector to resolve
     * @param point4 receives the point at the foot of the perpendicular
     * @return the parallel and perpendicular components
     */
    @Override
    public double[] resolve(double[] point1, double[] point2, double[] point3, double[] point4) {
        return frameset.resolve(point1, point2, point3, point4);
    }

    @Override
    double[][] resolvePoints(double[] point1, double[] point2, double[][] in, int npoint) {
        return frameset.resolvePoints(point1, point2, in, npoint);
    }

    @Override
    LineDef lineDef(double[] start, double[] end) {
        return frameset.lineDef(start, end);
    }

    @Override
    boolean lineCrossing(LineDef l1, LineDef l2, double[] cross, double[] dist) {
        return frameset.lineCrossing(l1, l2, cross, dist);
    }

    @Override
    boolean lineContains(LineDef l, int def, double[] point) {
        return frameset.lineContains(l, def, point);
    }

    @Override
    void lineOffset(LineDef line, double par, double prp, double[] point) {
        frameset.lineOffset(line, par, prp, point);
    }

    @Override
    void normBox(double[] lbnd, double[] ubnd, Mapping reg) {
        frameset.getFrame(FrameSet.AST__CURRENT).normBox(lbnd, ubnd, reg);
    }

    @Override
    int systemCode(String name) {
        if (frameset == null) {
            getNaxes();
        }
        return frameset.systemCode(name);
    }

    @Override
    String systemString(int code) {
        return frameset.systemString(code);
    }

    @Override
    int validateSystem(int code, String method) {
        return frameset.validateSystem(code, method);
    }

    // ------------------------------------------------------------------
    // Frame attributes, those of the current Frame
    // ------------------------------------------------------------------

    /**
     * Gets the Title of the Frame the Region represents.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        return frameset.getTitle();
    }

    /**
     * Sets the Title of the Frame the Region represents.
     *
     * @param title the Title
     */
    @Override
    public void setTitle(String title) {
        setC("Title", title);
    }

    @Override
    boolean testTitle() {
        return frameset.testTitle();
    }

    @Override
    void clearTitle() {
        frameset.clear("Title");
    }

    /**
     * Gets the Domain of the Frame the Region represents.
     *
     * @return the Domain
     */
    @Override
    public String getDomain() {
        return frameset.getDomain();
    }

    /**
     * Sets the Domain of the Frame the Region represents.
     *
     * @param domain the Domain
     */
    @Override
    public void setDomain(String domain) {
        setC("Domain", domain);
    }

    @Override
    boolean testDomain() {
        return frameset.testDomain();
    }

    @Override
    void clearDomain() {
        frameset.clear("Domain");
    }

    @Override
    double getEpochMjd() {
        return frameset.getEpochMjd();
    }

    @Override
    void setEpochMjd(double mjd) {
        frameset.setC("Epoch", "MJD " + CFormat.format("%.17g", mjd));
    }

    @Override
    boolean testEpoch() {
        return frameset.testEpoch();
    }

    @Override
    void clearEpoch() {
        frameset.clear("Epoch");
    }

    @Override
    int systemValue() {
        return frameset.systemValue();
    }

    @Override
    void setSystemValue(int value) {
        frameset.setC("System", frameset.systemString(value));
    }

    @Override
    boolean testSystem() {
        return frameset.testSystem();
    }

    @Override
    void clearSystem() {
        frameset.clear("System");
    }

    @Override
    int alignSystemValue() {
        return frameset.alignSystemValue();
    }

    @Override
    void setAlignSystemValue(int value) {
        frameset.setC("AlignSystem", frameset.systemString(value));
    }

    @Override
    boolean testAlignSystem() {
        return frameset.testAlignSystem();
    }

    @Override
    void clearAlignSystem() {
        frameset.clear("AlignSystem");
    }

    /**
     * Gets the Digits of the Frame the Region represents.
     *
     * @return the Digits
     */
    @Override
    public int getDigits() {
        return frameset.getDigits();
    }

    /**
     * Sets the Digits of the Frame the Region represents.
     *
     * @param digits the Digits
     */
    @Override
    public void setDigits(int digits) {
        setI("Digits", digits);
    }

    @Override
    boolean testDigits() {
        return frameset.testDigits();
    }

    @Override
    void clearDigits() {
        frameset.clear("Digits");
    }

    /**
     * Sets the Digits of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param digits the Digits
     */
    @Override
    public void setDigits(int axis, int digits) {
        setI("Digits(" + axis + ")", digits);
    }

    /**
     * Gets the MatchEnd of the Frame the Region represents.
     *
     * @return the MatchEnd
     */
    @Override
    public boolean getMatchEnd() {
        return frameset.getMatchEnd();
    }

    /**
     * Sets the MatchEnd of the Frame the Region represents.
     *
     * @param matchEnd the MatchEnd
     */
    @Override
    public void setMatchEnd(boolean matchEnd) {
        setB("MatchEnd", matchEnd);
    }

    @Override
    boolean testMatchEnd() {
        return frameset.testMatchEnd();
    }

    @Override
    void clearMatchEnd() {
        frameset.clear("MatchEnd");
    }

    /**
     * Gets the MaxAxes of the Frame the Region represents.
     *
     * @return the MaxAxes
     */
    @Override
    public int getMaxAxes() {
        return frameset.getMaxAxes();
    }

    /**
     * Sets the MaxAxes of the Frame the Region represents.
     *
     * @param maxAxes the MaxAxes
     */
    @Override
    public void setMaxAxes(int maxAxes) {
        setI("MaxAxes", maxAxes);
    }

    @Override
    boolean testMaxAxes() {
        return frameset.testMaxAxes();
    }

    @Override
    void clearMaxAxes() {
        frameset.clear("MaxAxes");
    }

    /**
     * Gets the MinAxes of the Frame the Region represents.
     *
     * @return the MinAxes
     */
    @Override
    public int getMinAxes() {
        return frameset.getMinAxes();
    }

    /**
     * Sets the MinAxes of the Frame the Region represents.
     *
     * @param minAxes the MinAxes
     */
    @Override
    public void setMinAxes(int minAxes) {
        setI("MinAxes", minAxes);
    }

    @Override
    boolean testMinAxes() {
        return frameset.testMinAxes();
    }

    @Override
    void clearMinAxes() {
        frameset.clear("MinAxes");
    }

    /**
     * Gets the Permute of the Frame the Region represents.
     *
     * @return the Permute
     */
    @Override
    public boolean getPermute() {
        return frameset.getPermute();
    }

    /**
     * Sets the Permute of the Frame the Region represents.
     *
     * @param permute the Permute
     */
    @Override
    public void setPermute(boolean permute) {
        setB("Permute", permute);
    }

    @Override
    boolean testPermute() {
        return frameset.testPermute();
    }

    @Override
    void clearPermute() {
        frameset.clear("Permute");
    }

    /**
     * Gets the PreserveAxes of the Frame the Region represents.
     *
     * @return the PreserveAxes
     */
    @Override
    public boolean getPreserveAxes() {
        return frameset.getPreserveAxes();
    }

    /**
     * Sets the PreserveAxes of the Frame the Region represents.
     *
     * @param preserveAxes the PreserveAxes
     */
    @Override
    public void setPreserveAxes(boolean preserveAxes) {
        setB("PreserveAxes", preserveAxes);
    }

    @Override
    boolean testPreserveAxes() {
        return frameset.testPreserveAxes();
    }

    @Override
    void clearPreserveAxes() {
        frameset.clear("PreserveAxes");
    }

    @Override
    double getObsLatValue() {
        return frameset.getObsLatValue();
    }

    @Override
    void setObsLatValue(double value) {
        frameset.setObsLatValue(value);
    }

    @Override
    boolean testObsLat() {
        return frameset.testObsLat();
    }

    @Override
    void clearObsLat() {
        frameset.clear("ObsLat");
    }

    @Override
    double getObsLonValue() {
        return frameset.getObsLonValue();
    }

    @Override
    void setObsLonValue(double value) {
        frameset.setObsLonValue(value);
    }

    @Override
    boolean testObsLon() {
        return frameset.testObsLon();
    }

    @Override
    void clearObsLon() {
        frameset.clear("ObsLon");
    }

    @Override
    double getObsAlt() {
        return frameset.getObsAlt();
    }

    @Override
    void setObsAlt(double value) {
        frameset.setObsAlt(value);
    }

    @Override
    boolean testObsAlt() {
        return frameset.testObsAlt();
    }

    @Override
    void clearObsAlt() {
        frameset.clear("ObsAlt");
    }

    /**
     * Gets the Dut1 of the Frame the Region represents.
     *
     * @return the Dut1
     */
    @Override
    public double getDut1() {
        return frameset.getDut1();
    }

    /**
     * Sets the Dut1 of the Frame the Region represents.
     *
     * @param dut1 the Dut1
     */
    @Override
    public void setDut1(double dut1) {
        setD("Dut1", dut1);
    }

    /**
     * Gets the ActiveUnit flag of the Frame the Region represents.
     *
     * @return the ActiveUnit flag
     */
    @Override
    public boolean getActiveUnit() {
        return frameset.getActiveUnit();
    }

    /**
     * Sets the ActiveUnit flag of the Frame the Region represents.
     *
     * @param value the ActiveUnit flag
     */
    @Override
    public void setActiveUnit(boolean value) {
        setB("ActiveUnit", value);
    }

    @Override
    boolean testActiveUnit() {
        return frameset.testActiveUnit();
    }

    /**
     * Gets the Label of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Label
     */
    @Override
    public String getLabel(int axis) {
        validateAxis(axis, true, "astGetLabel");
        return frameset.getLabel(axis);
    }

    /**
     * Sets the Label of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param label the Label
     */
    @Override
    public void setLabel(int axis, String label) {
        validateAxis(axis, true, "astSetLabel");
        setC("Label(" + axis + ")", label);
    }

    @Override
    boolean testLabel(int axis) {
        validateAxis(axis, true, "astTestLabel");
        return frameset.testLabel(axis);
    }

    @Override
    void clearLabel(int axis) {
        validateAxis(axis, true, "astClearLabel");
        frameset.clear("Label(" + axis + ")");
    }

    /**
     * Gets the Symbol of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Symbol
     */
    @Override
    public String getSymbol(int axis) {
        validateAxis(axis, true, "astGetSymbol");
        return frameset.getSymbol(axis);
    }

    /**
     * Sets the Symbol of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param symbol the Symbol
     */
    @Override
    public void setSymbol(int axis, String symbol) {
        validateAxis(axis, true, "astSetSymbol");
        setC("Symbol(" + axis + ")", symbol);
    }

    @Override
    boolean testSymbol(int axis) {
        validateAxis(axis, true, "astTestSymbol");
        return frameset.testSymbol(axis);
    }

    @Override
    void clearSymbol(int axis) {
        validateAxis(axis, true, "astClearSymbol");
        frameset.clear("Symbol(" + axis + ")");
    }

    /**
     * Gets the Unit of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Unit
     */
    @Override
    public String getUnit(int axis) {
        validateAxis(axis, true, "astGetUnit");
        return frameset.getUnit(axis);
    }

    /**
     * Sets the Unit of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param unit the Unit
     */
    @Override
    public void setUnit(int axis, String unit) {
        validateAxis(axis, true, "astSetUnit");
        setC("Unit(" + axis + ")", unit);
    }

    @Override
    boolean testUnit(int axis) {
        validateAxis(axis, true, "astTestUnit");
        return frameset.testUnit(axis);
    }

    @Override
    void clearUnit(int axis) {
        validateAxis(axis, true, "astClearUnit");
        frameset.clear("Unit(" + axis + ")");
    }

    /**
     * Gets the Format of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Format
     */
    @Override
    public String getFormat(int axis) {
        validateAxis(axis, true, "astGetFormat");
        return frameset.getFormat(axis);
    }

    /**
     * Sets the Format of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param format the Format
     */
    @Override
    public void setFormat(int axis, String format) {
        validateAxis(axis, true, "astSetFormat");
        setC("Format(" + axis + ")", format);
    }

    @Override
    boolean testFormat(int axis) {
        validateAxis(axis, true, "astTestFormat");
        return frameset.testFormat(axis);
    }

    @Override
    void clearFormat(int axis) {
        validateAxis(axis, true, "astClearFormat");
        frameset.clear("Format(" + axis + ")");
    }

    /**
     * Gets the Direction of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Direction
     */
    @Override
    public boolean getDirection(int axis) {
        validateAxis(axis, true, "astGetDirection");
        return frameset.getDirection(axis);
    }

    /**
     * Sets the Direction of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param direction the Direction
     */
    @Override
    public void setDirection(int axis, boolean direction) {
        validateAxis(axis, true, "astSetDirection");
        setB("Direction(" + axis + ")", direction);
    }

    @Override
    boolean testDirection(int axis) {
        validateAxis(axis, true, "astTestDirection");
        return frameset.testDirection(axis);
    }

    @Override
    void clearDirection(int axis) {
        validateAxis(axis, true, "astClearDirection");
        frameset.clear("Direction(" + axis + ")");
    }

    /**
     * Gets the Top of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Top
     */
    @Override
    public double getTop(int axis) {
        validateAxis(axis, true, "astGetTop");
        return frameset.getTop(axis);
    }

    /**
     * Sets the Top of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param top the Top
     */
    @Override
    public void setTop(int axis, double top) {
        validateAxis(axis, true, "astSetTop");
        setD("Top(" + axis + ")", top);
    }

    @Override
    boolean testTop(int axis) {
        validateAxis(axis, true, "astTestTop");
        return frameset.testTop(axis);
    }

    @Override
    void clearTop(int axis) {
        validateAxis(axis, true, "astClearTop");
        frameset.clear("Top(" + axis + ")");
    }

    /**
     * Gets the Bottom of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @return the Bottom
     */
    @Override
    public double getBottom(int axis) {
        validateAxis(axis, true, "astGetBottom");
        return frameset.getBottom(axis);
    }

    /**
     * Sets the Bottom of an axis of the Frame the Region represents.
     *
     * @param axis the axis number, from 1
     * @param bottom the Bottom
     */
    @Override
    public void setBottom(int axis, double bottom) {
        validateAxis(axis, true, "astSetBottom");
        setD("Bottom(" + axis + ")", bottom);
    }

    @Override
    boolean testBottom(int axis) {
        validateAxis(axis, true, "astTestBottom");
        return frameset.testBottom(axis);
    }

    @Override
    void clearBottom(int axis) {
        validateAxis(axis, true, "astClearBottom");
        frameset.clear("Bottom(" + axis + ")");
    }
}
