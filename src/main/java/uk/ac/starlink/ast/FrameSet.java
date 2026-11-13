package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * A set of inter-related coordinate systems: Frames connected by Mappings, so that coordinates
 * can be converted between any two of them.
 *
 * <p>The Frames are numbered from 1 in the order they are added ({@link #addFrame}). One of
 * them is the base Frame and one the current Frame, which may be the same; by default the first
 * Frame added is the base Frame and the last the current Frame. The base Frame describes the
 * "native" coordinates of whatever the FrameSet calibrates, such as the pixels of an image, and
 * the current Frame the "apparent" coordinates in which it is viewed; the other Frames are
 * alternatives, any of which may be made current.
 *
 * <p>As a Frame, a FrameSet is its current Frame: its axes, their attributes and the attributes
 * of the coordinate system are those of the current Frame, and setting or clearing one sets or
 * clears it on the current Frame. A change that alters the coordinate system the current Frame
 * describes then remaps the current Frame, so that the other Frames still describe the same
 * positions; this is the FrameSet's integrity. The attributes Base, Current, Nframe, Variant and
 * AllVariants, and those of an Object and a Mapping such as ID, Ident and Invert, are the
 * FrameSet's own.
 *
 * <p>As a Mapping, a FrameSet converts from its base Frame to its current Frame. Inverting it
 * exchanges the base and current Frames.
 *
 * <p>The FrameSet holds the Frame it is made with, and the Frames {@link #getFrame} returns are
 * its own. {@link #addFrame} adds copies of the Frame and Mapping it is given, and {@link
 * #remapFrame} holds the Mapping it is given.
 *
 * <p>A FrameSet may be used from any thread, but not from two at once; give each thread its own
 * {@link #copy}.
 *
 * <p>Ported from C {@code frameset.c}, whose arrays of Frames, nodes and Mappings it keeps, with
 * Rust {@code frames/frameset.rs}.
 */
public class FrameSet extends Frame {

    /** The Frame index that selects the base Frame ({@code AST__BASE}). */
    public static final int AST__BASE = 0;

    /** The Frame index that selects the current Frame ({@code AST__CURRENT}). */
    public static final int AST__CURRENT = -1;

    /** A Frame index that selects no Frame ({@code AST__NOFRAME}). */
    public static final int AST__NOFRAME = -99;

    /**
     * The Frame index given to {@link #addFrame} to append the axes of a Frame to every Frame
     * ({@code AST__ALLFRAMES}).
     */
    static final int AST__ALLFRAMES = -199;

    /** The value of the Base or Current attribute when it is not set (C's {@code -INT_MAX}). */
    private static final int UNSET = -Integer.MAX_VALUE;

    /** The longest list of variant names C's buffer holds ({@code GETALLVARIANTS_BUFF_LEN}). */
    private static final int GETALLVARIANTS_BUFF_LEN = 200;

    /** The attribute names that are read-only on a FrameSet. */
    private static final String[] READ_ONLY = {
        "allvariants", "class", "nframe", "nin", "nobject", "nout", "refcount", "tranforward", "traninverse"
    };

    /** The number of Frames. */
    private int nframe;

    /** The Frames, by index from 0. */
    private Frame[] frames;

    /** The node each Frame is at, numbered from 0. */
    private int[] nodes;

    /** The Frame, numbered from 1, whose variant Mappings each Frame mirrors, or 0. */
    private int[] varfrms;

    /** The number of nodes. */
    private int nnode;

    /**
     * For each node but the first (element {@code i} for node {@code i + 1}), the Mapping from the
     * node it is derived from to it.
     */
    private Mapping[] maps;

    /** For each node but the first, the node it is derived from, numbered from 0. */
    private int[] links;

    /**
     * For each node but the first, the Invert value its Mapping is used with to convert from the
     * node it is derived from: 1 or 0.
     */
    private int[] inverts;

    /** The Base attribute as stored, which inversion exchanges with Current, or {@link #UNSET}. */
    private int base = UNSET;

    /** The Current attribute as stored, or {@link #UNSET}. */
    private int current = UNSET;

    /** A copy of the current Frame made before an attribute is changed, or null. */
    private Frame integrityFrame;

    /** Whether the current Frame has been changed since {@link #integrityFrame} was made. */
    private boolean integrityLost;

    /** The method changing attributes, for the error message when integrity is lost. */
    private String integrityMethod = "";

    /**
     * Creates a FrameSet holding one Frame, which is both its base and its current Frame. The
     * FrameSet holds the Frame given, not a copy. Given a FrameSet, the new FrameSet holds the
     * same Frames and Mappings, with its Base, Current and Invert attributes, but none of its
     * other attributes.
     *
     * @param frame the first Frame
     */
    // The Invert attribute is set through Mapping's setter, which no class overrides.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    public FrameSet(Frame frame) {
        super(0);
        Objects.requireNonNull(frame, "frame");
        if (frame instanceof FrameSet) {
            FrameSet old = (FrameSet) frame;
            nframe = old.nframe;
            nnode = old.nnode;
            frames = old.frames.clone();
            nodes = old.nodes.clone();
            varfrms = old.varfrms.clone();
            maps = old.maps.clone();
            links = old.links.clone();
            inverts = old.inverts.clone();
            for (Frame f : frames) {
                f.markShared();
            }
            for (Mapping m : maps) {
                m.markShared();
            }
            base = old.base;
            current = old.current;
            if (old.testInvert()) {
                setInvert(old.getInvert());
            }
        } else {
            frame.markShared();
            nframe = 1;
            nnode = 1;
            frames = new Frame[] {frame};
            nodes = new int[] {0};
            varfrms = new int[] {0};
            maps = new Mapping[0];
            links = new int[0];
            inverts = new int[0];
        }
    }

    /** Creates a FrameSet holding one Frame with no axes, for subclasses that add their own Frames. */
    protected FrameSet() {
        this(new Frame(0));
    }

    /**
     * Loads a FrameSet from its description, as C's {@code astLoadFrameSet} does: Nframe (1 if
     * absent or negative), Nnode (Nframe if absent, at least 1), and for each Frame {@code
     * Frm<i>}, {@code Nod<i>} and {@code VFr<i>}, for each node but the first {@code Lnk<n>},
     * {@code Inv<n>} and {@code Map<n>}, and Base and Current, each unset when below 1.
     *
     * @param node the description
     * @throws AstException with status {@code AST__LDERR} if a Frame or Mapping is missing or of
     *     the wrong class
     */
    FrameSet(NativeNode node) {
        super(node);
        NativeNode part = node.section("FrameSet");
        nframe = part.readInt("Nframe", 1);
        if (nframe < 0) {
            nframe = 1;
        }
        nnode = part.readInt("Nnode", nframe);
        if (nnode < 1) {
            nnode = 1;
        }
        frames = new Frame[nframe];
        nodes = new int[nframe];
        varfrms = new int[nframe];
        maps = new Mapping[nnode - 1];
        links = new int[nnode - 1];
        inverts = new int[nnode - 1];
        for (int ifr = 1; ifr <= nframe; ifr++) {
            frames[ifr - 1] = (Frame) loadPart(part, "Frm" + ifr, Frame.class);
            nodes[ifr - 1] = part.readInt("Nod" + ifr, ifr) - 1;
            varfrms[ifr - 1] = part.readInt("VFr" + ifr, 0);
        }
        for (int inode = 1; inode < nnode; inode++) {
            links[inode - 1] = part.readInt("Lnk" + (inode + 1), 0) - 1;
            inverts[inode - 1] = part.readInt("Inv" + (inode + 1), 0);
            maps[inode - 1] = (Mapping) loadPart(part, "Map" + (inode + 1), Mapping.class);
        }
        base = part.readInt("Base", UNSET);
        if (base < 1) {
            base = UNSET;
        }
        current = part.readInt("Currnt", UNSET); // codespell:ignore currnt
        if (current < 1) {
            current = UNSET;
        }
    }

    /** The Object of class {@code cls} that item {@code key} of a FrameSet's description holds. */
    private static AstObject loadPart(NativeNode part, String key, Class<?> cls) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            throw Ast.err(AstException.AST__LDERR, "astLoadFrameSet(FrameSet): The object " + key + " is missing.");
        }
        AstObject obj = ObjectRegistry.load(desc);
        if (!cls.isInstance(obj)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadFrameSet(FrameSet): The object " + key + " is a " + obj.astClass() + ", not a "
                            + cls.getSimpleName() + ".");
        }
        return obj;
    }

    @Override
    String dumpComment() {
        return "Set of inter-related coordinate systems";
    }

    /** A FrameSet's description is written as a plain Frame's when its current Frame is a plain Frame. */
    @Override
    boolean dumpIsFrame() {
        return "Frame".equals(currentFrame().astClass());
    }

    /**
     * Writes the FrameSet class's part of a description, as C's {@code Dump} (frameset.c) does:
     * the stored Base and Current, unaffected by inversion, the nodes, links, Invert values,
     * Frames and Mappings.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        boolean inv = getInvert();
        node.writeInt("Nframe", nframe != 0, true, nframe, "Number of Frames in FrameSet");
        boolean set = base != UNSET;
        int ival = set ? base : (!inv ? getBase() : getCurrent());
        node.writeInt("Base", set, true, ival, "Index of base Frame");
        set = current != UNSET;
        ival = set ? current : (!inv ? getCurrent() : getBase());
        node.writeInt("Currnt", set, true, ival, "Index of current Frame"); // codespell:ignore currnt
        node.writeInt("Nnode", nnode != nframe, false, nnode, "Number of nodes in FrameSet");
        for (int ifr = 1; ifr <= nframe; ifr++) {
            ival = nodes[ifr - 1] + 1;
            node.writeInt("Nod" + ifr, ival != ifr, false, ival, "Frame " + ifr + " is associated with node " + ival);
        }
        for (int ifr = 1; ifr <= nframe; ifr++) {
            ival = varfrms[ifr - 1];
            node.writeInt("VFr" + ifr, ival > 0, false, ival, "Frame " + ifr + " inherits variants from Frame " + ival);
        }
        for (int inode = 1; inode < nnode; inode++) {
            ival = links[inode - 1] + 1;
            node.writeInt(
                    "Lnk" + (inode + 1), true, false, ival, "Node " + (inode + 1) + " is derived from node " + ival);
            ival = inverts[inode - 1];
            node.writeInt(
                    "Inv" + (inode + 1),
                    ival != 0,
                    false,
                    ival,
                    ival != 0 ? "The inverse mapping is used" : "The forward mapping is used");
        }
        for (int ifr = 1; ifr <= nframe; ifr++) {
            node.writeObject("Frm" + ifr, true, true, NativeNode.of(frames[ifr - 1]), "Frame number " + ifr);
        }
        for (int inode = 1; inode < nnode; inode++) {
            node.writeObject(
                    "Map" + (inode + 1),
                    true,
                    true,
                    NativeNode.of(maps[inode - 1]),
                    "Mapping between nodes " + (links[inode - 1] + 1) + " and " + (inode + 1));
        }
    }

    /** Completes a copy as C's {@code Copy} (frameset.c) does: each Frame and Mapping is copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        frames = frames.clone();
        for (int i = 0; i < nframe; i++) {
            frames[i] = (Frame) frames[i].copy();
        }
        nodes = nodes.clone();
        varfrms = varfrms.clone();
        maps = maps.clone();
        for (int i = 0; i < nnode - 1; i++) {
            maps[i] = (Mapping) maps[i].copy();
        }
        links = links.clone();
        inverts = inverts.clone();
        integrityFrame = null;
        integrityLost = false;
        integrityMethod = "";
    }

    // ------------------------------------------------------------------
    // Frames, Base and Current
    // ------------------------------------------------------------------

    /**
     * Gets the Nframe attribute: the number of Frames in the FrameSet, which is at least 1.
     *
     * @return the number of Frames
     */
    public int getNframe() {
        return nframe;
    }

    /**
     * Gets the Base attribute: the index of the base Frame. The default is 1, the first Frame
     * added, or the last Frame while the FrameSet is inverted, since inverting it exchanges its
     * base and current Frames.
     *
     * @return the index of the base Frame
     */
    public int getBase() {
        if (!getInvert()) {
            return base != UNSET ? base : 1;
        }
        return current != UNSET ? current : nframe;
    }

    /**
     * Sets the Base attribute.
     *
     * @param iframe the index of the new base Frame, or {@link #AST__BASE} or {@link
     *     #AST__CURRENT}
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame
     */
    public void setBase(int iframe) {
        int i = validateFrameIndex(iframe, "astSetBase");
        if (getInvert()) {
            current = i;
        } else {
            base = i;
        }
    }

    /** Whether the Base attribute is set. */
    boolean testBase() {
        return !getInvert() ? base != UNSET : current != UNSET;
    }

    /** Clears the Base attribute. */
    void clearBase() {
        if (getInvert()) {
            current = UNSET;
        } else {
            base = UNSET;
        }
    }

    /**
     * Gets the Current attribute: the index of the current Frame. The default is the number of
     * Frames, the last Frame added, or 1 while the FrameSet is inverted.
     *
     * @return the index of the current Frame
     */
    public int getCurrent() {
        if (!getInvert()) {
            return current != UNSET ? current : nframe;
        }
        return base != UNSET ? base : 1;
    }

    /**
     * Sets the Current attribute.
     *
     * @param iframe the index of the new current Frame, or {@link #AST__BASE} or {@link
     *     #AST__CURRENT}
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame
     */
    public void setCurrent(int iframe) {
        int i = validateFrameIndex(iframe, "astSetCurrent");
        if (getInvert()) {
            base = i;
        } else {
            current = i;
        }
    }

    /** Whether the Current attribute is set. */
    boolean testCurrent() {
        return !getInvert() ? current != UNSET : base != UNSET;
    }

    /** Clears the Current attribute. */
    void clearCurrent() {
        if (getInvert()) {
            base = UNSET;
        } else {
            current = UNSET;
        }
    }

    /**
     * Checks a Frame index and turns {@link #AST__BASE} and {@link #AST__CURRENT} into the index
     * they select (C's {@code astValidateFrameIndex}).
     *
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame
     */
    int validateFrameIndex(int iframe, String method) {
        if (iframe == AST__BASE) {
            return getBase();
        } else if (iframe == AST__CURRENT) {
            return getCurrent();
        }
        if (iframe < 1 || iframe > nframe) {
            throw Ast.err(
                    AstException.AST__FRMIN,
                    method + "(" + astClass() + "): Invalid Frame index (" + iframe + ") given.\n"
                            + "This value should be in the range 1 to " + nframe + " (or AST__CURRENT or AST__BASE).");
        }
        return iframe;
    }

    /** The current Frame, not marked as shared. */
    private Frame currentFrame() {
        return frames[getCurrent() - 1];
    }

    /**
     * Gets a Frame of the FrameSet. The Frame is the FrameSet's own, not a copy, so a change made
     * to it changes the FrameSet, without the remapping that keeps the FrameSet's integrity.
     *
     * @param iframe the index of the Frame, or {@link #AST__BASE} or {@link #AST__CURRENT}
     * @return the Frame
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame
     */
    public Frame getFrame(int iframe) {
        Frame f = frames[validateFrameIndex(iframe, "astGetFrame") - 1];
        f.markShared();
        return f;
    }

    // ------------------------------------------------------------------
    // Mappings between Frames
    // ------------------------------------------------------------------

    /**
     * Gets a Mapping that converts coordinates from one Frame of the FrameSet to another, with
     * its forward transformation, and back with its inverse. It is made of copies of the
     * FrameSet's Mappings, so a change made to it does not change the FrameSet. For the same
     * Frame at both ends it is a UnitMap.
     *
     * @param iframe1 the index of the Frame to convert from, or {@link #AST__BASE} or {@link
     *     #AST__CURRENT}
     * @param iframe2 the index of the Frame to convert to, or {@link #AST__BASE} or {@link
     *     #AST__CURRENT}
     * @return the Mapping
     * @throws AstException with status {@code AST__FRMIN} if an index is not that of a Frame
     */
    public Mapping getMapping(int iframe1, int iframe2) {
        int i1 = validateFrameIndex(iframe1, "astGetMapping");
        int i2 = validateFrameIndex(iframe2, "astGetMapping");
        Mapping[] path = new Mapping[nnode - 1 + nframe];
        boolean[] forward = new boolean[nnode - 1 + nframe];
        Frame[] byNode = new Frame[nnode];
        for (int ifr = 0; ifr < nframe; ifr++) {
            byNode[nodes[ifr]] = frames[ifr];
        }
        int npath = span(byNode, nodes[i1 - 1], nodes[i2 - 1], -1, path, forward, 0) - 1;
        if (npath < 0) {
            throw Ast.err(
                    AstException.AST__FRSIN,
                    "astGetMapping(" + astClass() + "): Invalid or corrupt " + astClass()
                            + " - could not find conversion path between Frames " + i1 + " and " + i2 + ".");
        }
        if (npath == 0) {
            Frame fr = frames[i1 - 1];
            if (fr.isUnitFrame()) {
                return new UnitMap(fr.getNaxes());
            }
            fr.markShared();
            return fr;
        }
        Mapping result = (Mapping) path[0].copy();
        if (!forward[0]) {
            result.invert();
        }
        for (int ipath = 1; ipath < npath; ipath++) {
            Mapping copy = (Mapping) path[ipath].copy();
            if (!forward[ipath]) {
                copy.invert();
            }
            // The components are copies that only this CmpMap refers to, so, as in C, where its
            // clones are their only references once the local pointers are annulled, they are not
            // marked shared.
            result = CmpMap.transientCmpMap(result, copy, true);
        }
        return result;
    }

    /**
     * Finds the path from node {@code inode1} to node {@code inode2} without passing through
     * node {@code avoid} (C's {@code Span}), storing from element {@code off} of {@code map} and
     * {@code forward} each Mapping on it, with whether its forward transformation is used. A
     * Frame at a node on the path that is not equivalent to a UnitMap is included as a Mapping.
     *
     * @param byNode the Frame at each node, or null
     * @return one more than the number of Mappings stored, or 0 if there is no path
     */
    private int span(Frame[] byNode, int inode1, int inode2, int avoid, Mapping[] map, boolean[] forward, int off) {
        if (inode1 == inode2) {
            int result = 1;
            Frame frame = byNode[inode1];
            if (frame != null && !frame.isUnitFrame()) {
                result++;
                map[off] = frame;
                forward[off] = true;
            }
            return result;
        }
        int result = 0;
        for (int inode = 0; inode < nnode; inode++) {
            if (inode == avoid) {
                continue;
            }
            boolean fwd = inode > 0 && links[inode - 1] == inode1;
            boolean inv = inode1 > 0 && links[inode1 - 1] == inode;
            if (fwd || inv) {
                int nextra = 1;
                Frame frame = byNode[inode1];
                if (frame != null && !frame.isUnitFrame()) {
                    nextra = 2;
                }
                result = span(byNode, inode, inode2, inode1, map, forward, off + nextra);
                if (result != 0) {
                    result++;
                    nextra--;
                    int k = (fwd ? inode : inode1) - 1;
                    map[off + nextra] = maps[k];
                    forward[off + nextra] = fwd;
                    // A Mapping inverted since it was stored is used the other way.
                    if ((inverts[k] != 0) != maps[k].getInvert()) {
                        forward[off + nextra] = !forward[off + nextra];
                    }
                    if (nextra != 0) {
                        result++;
                        map[off] = frame;
                        forward[off] = true;
                    }
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Adds a Frame to the FrameSet to define a new coordinate system, derived from the one the
     * Frame {@code iframe} describes. The new Frame becomes the current Frame. Copies of the
     * Frame and Mapping given are stored, so changes made to them afterwards do not affect the
     * FrameSet.
     *
     * <p>Given a FrameSet as {@code frame}, the two FrameSets are merged: its Frames are appended,
     * in order and with the Mappings between them, and {@code map} converts from Frame {@code
     * iframe} to its current Frame, which becomes the current Frame. A FrameSet given as {@code
     * map} is used as the Mapping from its base Frame to its current Frame.
     *
     * @param iframe the index of the Frame the new one is derived from, or {@link #AST__BASE} or
     *     {@link #AST__CURRENT}
     * @param map the Mapping whose forward transformation converts coordinates from Frame {@code
     *     iframe} to the new Frame, and whose inverse transformation converts back
     * @param frame the new Frame
     * @throws AstException with status {@code AST__FRMIN} if {@code iframe} is not the index of a
     *     Frame, or {@code AST__NCPIN} if the Mapping's Nin is not the number of axes of Frame
     *     {@code iframe}, or its Nout not that of the new Frame
     */
    public void addFrame(int iframe, Mapping map, Frame frame) {
        if (iframe == AST__ALLFRAMES) {
            appendAxes(frame);
            return;
        }
        Mapping m = map instanceof FrameSet ? ((FrameSet) map).getMapping(AST__BASE, AST__CURRENT) : map;
        int ifr0 = validateFrameIndex(iframe, "astAddFrame");
        int naxes = frames[ifr0 - 1].getNaxes();
        int ncoord = m.getNin();
        if (naxes != ncoord) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astAddFrame(" + astClass() + "): Bad number of " + m.astClass() + " input coordinate values ("
                            + ncoord + ").\nThe " + m.astClass() + " given should accept " + naxes
                            + " coordinate value" + (naxes == 1 ? "" : "s") + " for each input point.");
        }
        ncoord = m.getNout();
        naxes = frame.getNaxes();
        if (ncoord != naxes) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astAddFrame(" + astClass() + "): Bad number of " + m.astClass() + " output coordinate values ("
                            + ncoord + ").\nThe " + m.astClass() + " given should generate " + naxes
                            + " coordinate value" + (naxes == 1 ? "" : "s") + " for each output point.");
        }
        if (!(frame instanceof FrameSet)) {
            growFrames(nframe + 1);
            growNodes(nnode + 1);
            frames[nframe] = (Frame) frame.copy();
            maps[nnode - 1] = (Mapping) m.copy();
            varfrms[nframe] = 0;
            nodes[nframe] = nnode;
            links[nnode - 1] = nodes[ifr0 - 1];
            inverts[nnode - 1] = m.getInvert() ? 1 : 0;
            nframe++;
            nnode++;
            setCurrent(nframe);
        } else {
            mergeFrameSet(ifr0, m, (FrameSet) frame);
        }
    }

    /**
     * Merges the Frames and nodes of {@code frameset} into this FrameSet, linking the current
     * Frame of {@code frameset} to Frame {@code ifr0} through {@code map} (the FrameSet branch of
     * C's {@code AddFrame}).
     */
    private void mergeFrameSet(int ifr0, Mapping map, FrameSet frameset) {
        int newNframe = nframe + frameset.nframe;
        int newNnode = nnode + frameset.nnode;
        growFrames(newNframe);
        growNodes(newNnode);
        for (int ifr = 1; ifr <= frameset.nframe; ifr++) {
            frames[nframe + ifr - 1] = (Frame) frameset.frames[ifr - 1].copy();
            nodes[nframe + ifr - 1] = frameset.nodes[ifr - 1] + nnode;
            int vf = frameset.varfrms[ifr - 1];
            varfrms[nframe + ifr - 1] = vf > 0 ? vf + nframe : 0;
        }
        for (int inode = 1; inode < frameset.nnode; inode++) {
            maps[nnode + inode - 1] = (Mapping) frameset.maps[inode - 1].copy();
            links[nnode + inode - 1] = frameset.links[inode - 1] + nnode;
            inverts[nnode + inode - 1] = frameset.inverts[inode - 1];
        }
        // The element for the merged FrameSet's node zero, which has no Mapping yet.
        maps[nnode - 1] = null;
        links[nnode - 1] = -1;
        inverts[nnode - 1] = -1;
        int newCurrent = frameset.getCurrent() + nframe;

        // The node of the current Frame of the merged FrameSet already has a link, and its node
        // zero has none. Reverse each link on the path from the current Frame's node back to node
        // zero, moving each Mapping one step along the path and reversing its sense, which leaves
        // the current Frame's node free for the new link.
        int currentNode = nodes[newCurrent - 1];
        int nodeZero = nnode;
        Mapping inodeMap = null;
        int inodeInvert = 0;
        int next = 0;
        if (currentNode != nodeZero) {
            inodeMap = maps[currentNode - 1];
            int inodeLink = links[currentNode - 1];
            inodeInvert = inverts[currentNode - 1];
            maps[currentNode - 1] = null;
            links[currentNode - 1] = -1;
            inverts[currentNode - 1] = -1;
            next = inodeLink;
        }
        int inode = currentNode;
        while (inode != nodeZero) {
            Mapping nextMap = null;
            int nextLink = 0;
            int nextInvert = 0;
            if (next != nodeZero) {
                nextMap = maps[next - 1];
                nextLink = links[next - 1];
                nextInvert = inverts[next - 1];
            }
            maps[next - 1] = inodeMap;
            links[next - 1] = inode;
            inverts[next - 1] = inodeInvert == 0 ? 1 : 0;
            inode = next;
            if (inode != nodeZero) {
                inodeMap = nextMap;
                inodeInvert = nextInvert;
                next = nextLink;
            }
        }
        maps[currentNode - 1] = (Mapping) map.copy();
        links[currentNode - 1] = nodes[ifr0 - 1];
        inverts[currentNode - 1] = map.getInvert() ? 1 : 0;
        nframe = newNframe;
        nnode = newNnode;
        setCurrent(newCurrent);
    }

    /**
     * Appends the axes of {@code frame} to every Frame (C's {@code AppendAxes}): each Frame is
     * replaced by a CmpFrame of it and {@code frame}, which they all hold, and each Mapping, used
     * with the Invert value recorded for it, by a parallel CmpMap of it and a UnitMap for the new
     * axes, which they all share.
     */
    private void appendAxes(Frame frame) {
        for (int iframe = 0; iframe < nframe; iframe++) {
            frames[iframe] = CmpFrame.transientCmpFrame(frames[iframe], frame);
        }
        frame.markShared();
        UnitMap umap = new UnitMap(frame.getNaxes());
        if (nnode - 1 > 1) {
            umap.markShared();
        }
        for (int imap = 0; imap < nnode - 1; imap++) {
            boolean invOrig = maps[imap].getInvert();
            maps[imap].setInvert(inverts[imap] != 0);
            Mapping map = CmpMap.transientCmpMap(maps[imap], umap, false);
            maps[imap].setInvert(invOrig);
            maps[imap] = map.simplify();
            inverts[imap] = maps[imap].getInvert() ? 1 : 0;
        }
    }

    /** Makes the arrays of Frame information hold {@code n} Frames. */
    private void growFrames(int n) {
        frames = Arrays.copyOf(frames, n);
        nodes = Arrays.copyOf(nodes, n);
        varfrms = Arrays.copyOf(varfrms, n);
    }

    /** Makes the arrays of node information hold {@code n} nodes. */
    private void growNodes(int n) {
        maps = Arrays.copyOf(maps, n - 1);
        links = Arrays.copyOf(links, n - 1);
        inverts = Arrays.copyOf(inverts, n - 1);
    }

    /**
     * Removes a Frame from the FrameSet. The other Frames are renumbered from 1 if necessary, and
     * the relationships between them are kept, including those that went through the Frame
     * removed. Removing the base or current Frame clears the Base or Current attribute, so that
     * another Frame takes its role by default.
     *
     * @param iframe the index of the Frame, or {@link #AST__BASE} or {@link #AST__CURRENT}
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame, or
     *     {@code AST__REMIN} if the Frame is the only one
     */
    public void removeFrame(int iframe) {
        int ifr0 = validateFrameIndex(iframe, "astRemoveFrame");
        if (nframe == 1) {
            throw Ast.err(
                    AstException.AST__REMIN,
                    "astRemoveFrame(" + astClass() + "): Invalid attempt to remove the only Frame in a " + astClass()
                            + ".");
        }
        removeMirrors(ifr0);
        for (int ifr = 1; ifr <= nframe; ifr++) {
            if (varfrms[ifr - 1] > ifr0) {
                varfrms[ifr - 1]--;
            }
        }
        for (int ifr = ifr0; ifr < nframe; ifr++) {
            frames[ifr - 1] = frames[ifr];
            nodes[ifr - 1] = nodes[ifr];
            varfrms[ifr - 1] = varfrms[ifr];
        }
        nframe--;
        growFrames(nframe);
        tidyNodes();
        if (testBase()) {
            int ii = getBase();
            if (ifr0 == ii) {
                clearBase();
            } else if (ifr0 < ii) {
                setBase(ii - 1);
            }
        }
        if (testCurrent()) {
            int ii = getCurrent();
            if (ifr0 == ii) {
                clearCurrent();
            } else if (ifr0 < ii) {
                setCurrent(ii - 1);
            }
        }
    }

    /**
     * Modifies the relationship of a Frame to the other Frames: {@code map} converts from the
     * Frame's old coordinate system to its new one with its forward transformation, and back with
     * its inverse. The relationships between the other Frames are unchanged. The FrameSet holds
     * the Mapping given, not a copy. The Frame's variant Mappings, other than the one in use, are
     * removed.
     *
     * @param iframe the index of the Frame, or {@link #AST__BASE} or {@link #AST__CURRENT}
     * @param map the Mapping, whose Nin and Nout must both be the number of axes of the Frame
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame, or
     *     {@code AST__NCPIN} if the Mapping's Nin or Nout is not the Frame's number of axes
     */
    public void remapFrame(int iframe, Mapping map) {
        int ifr0 = validateFrameIndex(iframe, "astRemapFrame");
        // Variant Mappings are held by the Frame for its node, which the Frame leaves, so all but
        // the one in use are removed, unless the Frame only mirrors another's.
        int icur = getCurrent();
        setCurrent(ifr0);
        int varfrm = varfrms[ifr0 - 1];
        varfrms[ifr0 - 1] = 0;
        clearVariant();
        varfrms[ifr0 - 1] = varfrm;
        setCurrent(icur);
        remapNode(ifr0, map, "astRemapFrame");
    }

    /**
     * Moves Frame {@code ifr0} to a new node linked to its old one by {@code map}, which the
     * FrameSet holds, and tidies the nodes (the second part of C's {@code RemapFrame}).
     */
    private void remapNode(int ifr0, Mapping map, String method) {
        int nin = map.getNin();
        int nout = map.getNout();
        int naxes = frames[ifr0 - 1].getNaxes();
        if (nin != naxes) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    method + "(" + astClass() + "): Bad number of " + map.astClass() + " input coordinate values ("
                            + nin + ").\nThe " + map.astClass() + " given should accept " + naxes + " coordinate value"
                            + (naxes == 1 ? "" : "s") + " for each input point.");
        } else if (nout != naxes) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    method + "(" + astClass() + "): Bad number of " + map.astClass() + " output coordinate values ("
                            + nout + ").\nThe " + map.astClass() + " given should generate " + naxes
                            + " coordinate value" + (naxes == 1 ? "" : "s") + " for each output point.");
        }
        if (nframe > 1) {
            growNodes(nnode + 1);
            map.markShared();
            maps[nnode - 1] = map;
            links[nnode - 1] = nodes[ifr0 - 1];
            inverts[nnode - 1] = map.getInvert() ? 1 : 0;
            nnode++;
            nodes[ifr0 - 1] = nnode - 1;
            tidyNodes();
        }
    }

    /**
     * Removes the nodes that are no longer needed (C's {@code TidyNodes}): a node with no Frame
     * that is a dead end, or that only joins two Mappings, which are then combined into one and
     * simplified. Node zero, from which no Mapping leads, is needed with no Frame only if three
     * or more nodes are derived from it.
     */
    private void tidyNodes() {
        boolean needed = false;
        while (!needed) {
            for (int suspect = 0; suspect < nnode; suspect++) {
                int linkRef = 0;
                int[] lastLink = new int[2];
                needed = false;
                for (int ifr = 1; ifr <= nframe; ifr++) {
                    needed = nodes[ifr - 1] == suspect;
                    if (needed) {
                        break;
                    }
                }
                int minRef = suspect != 0 ? 1 : 2;
                if (!needed) {
                    for (int inode = 1; inode < nnode; inode++) {
                        if (links[inode - 1] == suspect) {
                            needed = linkRef >= minRef;
                            if (needed) {
                                break;
                            }
                            lastLink[linkRef++] = inode;
                        }
                    }
                }
                if (!needed) {
                    int remove = suspect != 0 ? suspect : lastLink[0];
                    if (linkRef < minRef) {
                        maps[remove - 1] = null;
                    } else {
                        // For node zero, from which no Mapping leads, the first Mapping is used
                        // the other way.
                        int next = suspect != 0 ? lastLink[0] : lastLink[1];
                        Mapping tmpmap = combineMaps(
                                maps[remove - 1],
                                (inverts[remove - 1] != 0) != (suspect == 0),
                                maps[next - 1],
                                inverts[next - 1] != 0);
                        Mapping newmap = tmpmap.simplify();
                        maps[next - 1] = newmap;
                        inverts[next - 1] = newmap.getInvert() ? 1 : 0;
                        links[next - 1] = links[remove - 1];
                    }
                    for (int inode = remove; inode < nnode - 1; inode++) {
                        maps[inode - 1] = maps[inode];
                        links[inode - 1] = links[inode];
                        inverts[inode - 1] = inverts[inode];
                    }
                    nnode--;
                    growNodes(nnode);
                    for (int ifr = 1; ifr <= nframe; ifr++) {
                        if (nodes[ifr - 1] > remove) {
                            nodes[ifr - 1]--;
                        } else if (nodes[ifr - 1] == remove) {
                            nodes[ifr - 1] = 0;
                        }
                    }
                    for (int inode = 1; inode < nnode; inode++) {
                        if (links[inode - 1] > remove) {
                            links[inode - 1]--;
                        } else if (links[inode - 1] == remove) {
                            links[inode - 1] = 0;
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * A series CmpMap of two Mappings, each used with the Invert value given (C's {@code
     * CombineMaps}): each Mapping's Invert attribute is set as needed while the CmpMap records it,
     * and then restored. The same Mapping needed with two Invert values is copied.
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    private static Mapping combineMaps(Mapping mapping1, boolean invert1, Mapping mapping2, boolean invert2) {
        boolean inv1 = mapping1.getInvert();
        boolean inv2 = mapping2.getInvert();
        boolean set1 = mapping1.testInvert();
        boolean set2 = mapping2.testInvert();
        boolean copy = mapping1 == mapping2 && invert1 != invert2;
        Mapping map1 = mapping1;
        Mapping map2 = copy ? (Mapping) mapping2.copy() : mapping2;
        setInvertValue(map1, invert1, inv1);
        setInvertValue(map2, invert2, inv2);
        Mapping result = new CmpMap(map1, map2, true);
        if (invert1 != inv1) {
            restoreInvert(map1, set1, inv1);
        }
        if (invert2 != inv2 && !copy) {
            restoreInvert(map2, set2, inv2);
        }
        return result;
    }

    /** Gives a Mapping the Invert value {@code want}, set when true and cleared when false, if it differs from {@code now}. */
    private static void setInvertValue(Mapping m, boolean want, boolean now) {
        if (want != now) {
            if (want) {
                m.setInvert(true);
            } else {
                m.clearInvert();
            }
        }
    }

    /** Restores a Mapping's Invert attribute to {@code value}, set only if it was set. */
    private static void restoreInvert(Mapping m, boolean set, boolean value) {
        if (set) {
            m.setInvert(value);
        } else {
            m.clearInvert();
        }
    }

    /**
     * Makes a copy of the Frame {@code iframe} if the same Frame is also held under another index,
     * so that it can be changed independently (C's {@code ForceCopy}).
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    private void forceCopy(int iframe) {
        int ifr0 = validateFrameIndex(iframe, integrityMethod);
        Frame frame = frames[ifr0 - 1];
        for (int ifr = 1; ifr <= nframe; ifr++) {
            if (ifr != ifr0 && frames[ifr - 1] == frame) {
                frames[ifr0 - 1] = (Frame) frame.copy();
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Integrity
    // ------------------------------------------------------------------

    /** Records a copy of the current Frame, to restore integrity from (C's {@code RecordIntegrity}). */
    private void recordIntegrity() {
        integrityFrame = null;
        integrityLost = false;
        integrityFrame = (Frame) currentFrame().copy();
    }

    /**
     * Restores the FrameSet's integrity after its current Frame has changed (C's {@code
     * RestoreIntegrity}): the conversion from the copy recorded before the change to the current
     * Frame, with their Domains made the same, remaps the current Frame unless it is a UnitMap.
     * The record is then discarded.
     *
     * @throws AstException with status {@code AST__ILOST} if there is no such conversion
     */
    private void restoreIntegrity() {
        Frame recorded = integrityFrame;
        boolean lost = integrityLost;
        integrityFrame = null;
        integrityLost = false;
        if (recorded == null || !lost || nframe <= 1) {
            return;
        }
        Frame cur = currentFrame();
        if (cur.testDomain()) {
            recorded.setDomain(cur.getDomain());
        } else {
            recorded.clearDomain();
        }
        recorded.setFrameFlags(recorded.getFrameFlags() | AST__INTFLAG);
        int flags = cur.getFrameFlags();
        cur.setFrameFlags(flags | AST__INTFLAG);
        FrameSet cvt;
        try {
            cvt = recorded.convertFrames(cur, "");
        } finally {
            cur.setFrameFlags(flags);
        }
        if (cvt == null) {
            throw Ast.err(
                    AstException.AST__ILOST,
                    integrityMethod + "(" + astClass() + "): Cannot maintain " + astClass() + " integrity.");
        }
        Mapping map = cvt.getMapping(AST__BASE, AST__CURRENT);
        if (!"UnitMap".equals(map.astClass())) {
            remapFrame(AST__CURRENT, map);
        }
    }

    /**
     * C's {@code VSet} (frameset.c): the settings are made with the FrameSet's integrity recorded
     * before and restored after, so that a change to the coordinate system of the current Frame
     * remaps it.
     */
    @Override
    void vset(String head, String value) {
        if (head.isEmpty() && value.isEmpty()) {
            return;
        }
        Frame saveFrame = integrityFrame;
        boolean saveLost = integrityLost;
        String saveMethod = integrityMethod;
        integrityMethod = "astSet";
        try {
            recordIntegrity();
            super.vset(head, value);
            try {
                restoreIntegrity();
            } catch (AstException e) {
                AstException err = Ast.withContext(
                        e,
                        "Unable to accommodate the attribute setting \"" + (head + value).replace('\r', ',') + "\".");
                err.initCause(e);
                throw err;
            }
        } finally {
            integrityFrame = saveFrame;
            integrityLost = saveLost;
            integrityMethod = saveMethod;
        }
    }

    /**
     * Clears attribute values, restoring their defaults, with the FrameSet's integrity recorded
     * before and restored after, as C's {@code Clear} (frameset.c) does, so that a change to the
     * coordinate system of the current Frame remaps it.
     *
     * @param attrib the attribute names, separated by commas
     * @throws AstException if an attribute does not exist or is read-only, or the integrity of the
     *     FrameSet cannot be kept
     */
    @Override
    public void clear(String attrib) {
        Frame saveFrame = integrityFrame;
        boolean saveLost = integrityLost;
        String saveMethod = integrityMethod;
        integrityMethod = "astClear";
        try {
            recordIntegrity();
            super.clear(attrib);
            try {
                restoreIntegrity();
            } catch (AstException e) {
                AstException err =
                        Ast.withContext(e, "Unable to accommodate clearing the \"" + attrib + "\" attribute(s).");
                err.initCause(e);
                throw err;
            }
        } finally {
            integrityFrame = saveFrame;
            integrityLost = saveLost;
            integrityMethod = saveMethod;
        }
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    private static boolean isReadOnly(String attrib) {
        for (String name : READ_ONLY) {
            if (name.equals(attrib)) {
                return true;
            }
        }
        return false;
    }

    /**
     * C's {@code GetAttrib} (frameset.c): the FrameSet's own attributes, and those of an Object
     * and a Mapping it keeps, and for any other name the current Frame's.
     */
    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "allvariants":
                return getAllVariants();
            case "base":
                return Integer.toString(getBase());
            case "class":
                return astClass();
            case "current":
                return Integer.toString(getCurrent());
            case "id":
                return getID();
            case "ident":
                return getIdent();
            case "invert":
                return getInvert() ? "1" : "0";
            case "nframe":
                return Integer.toString(getNframe());
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
            case "variant":
                return getVariant();
            default:
                return currentFrame().getAttrib(attrib);
        }
    }

    /**
     * C's {@code SetAttrib} (frameset.c). Base and Current may also be given as {@code
     * AST__BASE}, {@code Base}, {@code AST__CURRENT} or {@code Current}, or as the Domain of a
     * Frame, the first with that Domain being chosen. Changing Current or Invert restores the
     * integrity of the FrameSet before and records it after. Any other setting is made on the
     * current Frame, which is then taken to have changed.
     */
    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        String word = oneWord(value);
        if ("base".equals(attrib) && ival != null) {
            setBase(ival);
        } else if ("base".equals(attrib) && word != null) {
            if (chrMatch("AST__CURRENT", word) || chrMatch("Current", word)) {
                setBase(AST__CURRENT);
            } else if (!chrMatch("AST__BASE", word) && !chrMatch("Base", word)) {
                setBase(domainIndex(word, "Base"));
            }
        } else if ("current".equals(attrib) && ival != null) {
            restoreIntegrity();
            setCurrent(ival);
            recordIntegrity();
        } else if ("current".equals(attrib) && word != null) {
            if (chrMatch("AST__BASE", word) || chrMatch("Base", word)) {
                restoreIntegrity();
                setCurrent(AST__BASE);
                recordIntegrity();
            } else if (!chrMatch("AST__CURRENT", word) && !chrMatch("Current", word)) {
                int iframe = domainIndex(word, "Current");
                restoreIntegrity();
                setCurrent(iframe);
                recordIntegrity();
            }
        } else if ("id".equals(attrib)) {
            setID(value);
        } else if ("ident".equals(attrib)) {
            setIdent(value);
        } else if ("invert".equals(attrib) && ival != null) {
            restoreIntegrity();
            setInvert(ival != 0);
            recordIntegrity();
        } else if ("report".equals(attrib) && ival != null) {
            setReport(ival != 0);
        } else if ("variant".equals(attrib)) {
            setVariant(value);
        } else if (isReadOnly(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ". This is a read-only attribute.");
        } else {
            forceCopy(AST__CURRENT);
            currentFrame().setAttrib(attrib, value);
            integrityLost = true;
        }
    }

    /**
     * The index of the first Frame whose Domain matches {@code word}, ignoring case and trailing
     * white space.
     *
     * @throws AstException with status {@code AST__ATTIN} if there is none
     */
    private int domainIndex(String word, String which) {
        for (int ifr = 1; ifr <= nframe; ifr++) {
            if (chrMatch(frames[ifr - 1].getDomain(), word)) {
                return ifr;
            }
        }
        throw Ast.err(
                AstException.AST__ATTIN,
                "astSetAttrib(" + astClass() + "): Invalid index value for " + which + " Frame \"" + word + "\".");
    }

    /** C's {@code TestAttrib} (frameset.c). */
    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "base":
                return testBase();
            case "current":
                return testCurrent();
            case "id":
            case "ident":
                return super.testAttrib(attrib);
            case "invert":
                return testInvert();
            case "report":
                return testReport();
            case "variant":
                return testVariant();
            default:
                return !isReadOnly(attrib) && currentFrame().testAttrib(attrib);
        }
    }

    /**
     * C's {@code ClearAttrib} (frameset.c). Clearing Current or Invert restores the integrity of
     * the FrameSet before and records it after. Any other attribute is cleared on the current
     * Frame, which is then taken to have changed.
     */
    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "base":
                clearBase();
                break;
            case "current":
                restoreIntegrity();
                clearCurrent();
                recordIntegrity();
                break;
            case "id":
            case "ident":
                super.clearAttrib(attrib);
                break;
            case "invert":
                restoreIntegrity();
                clearInvert();
                recordIntegrity();
                break;
            case "report":
                clearReport();
                break;
            case "variant":
                clearVariant();
                break;
            default:
                if (isReadOnly(attrib)) {
                    throw Ast.err(
                            AstException.AST__NOWRT,
                            "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                                    + ". This is a read-only attribute.");
                }
                forceCopy(AST__CURRENT);
                currentFrame().clearAttrib(attrib);
                integrityLost = true;
        }
    }

    /** The FrameSet's UseDefs if set, otherwise its current Frame's (C's {@code GetUseDefs}). */
    @Override
    boolean getUseDefs() {
        if (super.testAttrib("usedefs")) {
            return super.getUseDefs();
        }
        return currentFrame().getUseDefs();
    }

    // ------------------------------------------------------------------
    // Variant Mappings
    // ------------------------------------------------------------------

    /**
     * The Frame, numbered from 1, whose variant Mappings Frame {@code iframe} uses, following the
     * chain of Frames that mirror others (C's {@code GetVarFrm}).
     *
     * @throws AstException with status {@code AST__INTER} if a Frame mirrors itself
     */
    private int getVarFrm(int iframe) {
        int result = iframe;
        while (varfrms[result - 1] > 0) {
            if (varfrms[result - 1] == result) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "GetVarFrm(FrameSet): FrameSet is corrupt (internal programming error).");
            }
            result = varfrms[result - 1];
        }
        return result;
    }

    /**
     * The Variant attribute: the name of the variant Mapping the current Frame uses, the Domain
     * of the current Frame of its variants FrameSet, or, with no variants, the Domain of the
     * current Frame.
     */
    String getVariant() {
        FrameSet vfs = frames[getVarFrm(getCurrent()) - 1].getFrameVariants();
        return vfs == null ? getDomain() : vfs.getDomain();
    }

    /** Whether the current Frame, or the Frame it mirrors, has variant Mappings (C's {@code TestVariant}). */
    boolean testVariant() {
        return frames[getVarFrm(getCurrent()) - 1].getFrameVariants() != null;
    }

    /**
     * Removes the variant Mappings of the current Frame, or of the Frame it mirrors, leaving the
     * one in use (C's {@code ClearVariant}).
     */
    void clearVariant() {
        frames[getVarFrm(getCurrent()) - 1].setFrameVariants(null);
    }

    /**
     * The AllVariants attribute: the names of the variant Mappings of the current Frame,
     * separated by spaces, or the Domain of the current Frame if it has none (C's {@code
     * GetAllVariants}).
     *
     * @throws AstException with status {@code AST__INTER} if the names are longer than C's buffer
     */
    String getAllVariants() {
        FrameSet vfs = frames[getVarFrm(getCurrent()) - 1].getFrameVariants();
        if (vfs == null) {
            return getDomain();
        }
        StringBuilder sb = new StringBuilder();
        for (int ifrm = 1; ifrm <= vfs.nframe; ifrm++) {
            String dom = vfs.frames[ifrm - 1].getDomain();
            if (sb.length() + dom.length() + 1 < GETALLVARIANTS_BUFF_LEN) {
                sb.append(dom).append(' ');
            } else {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astGetAllVariants(" + astClass() + "): Buffer overflow - too many variants.");
            }
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Makes the current Frame use the variant Mappings of Frame {@code iframe} (C's {@code
     * astMirrorVariants}). Giving the current Frame, or {@link #AST__NOFRAME}, ends any mirroring.
     *
     * @throws AstException with status {@code AST__FRMIN} if the index is not that of a Frame
     */
    void mirrorVariants(int iframe) {
        int icur = getCurrent();
        if (iframe == AST__NOFRAME) {
            varfrms[icur - 1] = 0;
            return;
        }
        int ifr0 = validateFrameIndex(iframe, "astMirrorVariants");
        varfrms[icur - 1] = ifr0 == icur ? 0 : ifr0;
    }

    /** Ends the mirroring of Frame {@code iframe}'s variant Mappings by any Frame (C's {@code RemoveMirrors}). */
    private void removeMirrors(int iframe) {
        int[] list = new int[nframe];
        int n = 0;
        for (int ifr = 1; ifr <= nframe; ifr++) {
            if (getVarFrm(ifr) == iframe) {
                list[n++] = ifr;
            }
        }
        for (int i = 0; i < n; i++) {
            varfrms[list[i] - 1] = 0;
        }
    }

    /** A name with leading and trailing white space removed and letters in upper case, as C cleans a variant name. */
    private static String cleanName(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && CScan.isCSpace(name.charAt(start))) {
            start++;
        }
        while (end > start && CScan.isCSpace(name.charAt(end - 1))) {
            end--;
        }
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = name.charAt(i);
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /**
     * The conversion from {@code from} to {@code to}, with the Domain of {@code to} given for the
     * search the Domain of {@code from}, as C's variant functions find it.
     */
    private static Mapping convertMatchingDomain(Frame from, Frame to, String method, String cls) {
        boolean domSet = to.testDomain();
        String dom = to.getDomain();
        to.setDomain(from.getDomain());
        FrameSet tfs;
        try {
            tfs = from.convertFrames(to, "");
        } finally {
            if (domSet) {
                to.setDomain(dom);
            } else {
                to.clearDomain();
            }
        }
        if (tfs == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    method + "(" + cls + "): Cannot convert from a " + from.astClass() + " with Domain '"
                            + from.getDomain() + "' to a " + to.astClass() + " with Domain '" + to.getDomain()
                            + "' (internal programming error).");
        }
        return tfs.getMapping(AST__BASE, AST__CURRENT);
    }

    /**
     * Selects the variant Mapping the current Frame uses (C's {@code SetVariant}): the Mapping
     * that generates the node of the Frame holding the variants is followed by the change from
     * the variant in use to the one named. With no variants, the only name allowed is the
     * current Frame's Domain.
     *
     * <p>C replaces the Mapping from the parent of that node, which the node of the first Frame
     * added does not have; there, C reads outside its arrays. Here such a Frame is moved to a new
     * node, linked to its old one by the change of variant, as Rust's {@code set_variant} does.
     *
     * @throws AstException with status {@code AST__ATTIN} if there is no variant of that name
     */
    void setVariant(String variant) {
        String myvar = cleanName(variant);
        int icur = getVarFrm(getCurrent());
        Frame frm = frames[icur - 1];
        FrameSet vfs = frm.getFrameVariants();
        if (vfs == null) {
            if (!myvar.equals(getDomain())) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetVariant(" + astClass() + "): Unknown Frame variant '" + myvar + "' requested.");
            }
            return;
        }
        int nfrm = vfs.nframe;
        int ifrm = 0;
        while (ifrm < nfrm && !myvar.equals(vfs.frames[ifrm].getDomain())) {
            ifrm++;
        }
        if (ifrm == nfrm) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetVariant(" + astClass() + "): Unknown Frame variant '" + myvar
                            + "' requested - available variants are '" + getAllVariants() + "'.");
        }
        if (ifrm + 1 == vfs.getCurrent()) {
            return;
        }
        Frame vfrm = vfs.currentFrame();
        Mapping map1 = convertMatchingDomain(frm, vfrm, "astSetVariant", astClass());
        Mapping map2 = vfs.getMapping(AST__CURRENT, ifrm + 1);
        Mapping map3 = convertMatchingDomain(vfs.frames[ifrm], frm, "astSetVariant", astClass());
        Mapping map5 = new CmpMap(new CmpMap(map1, map2, true), map3, true);
        int inode = nodes[icur - 1];
        if (inode == 0) {
            remapNode(icur, map5.simplify(), "astSetVariant");
        } else {
            Mapping map0 = maps[inode - 1];
            boolean inv0 = inverts[inode - 1] != 0;
            boolean set = map0.testInvert();
            boolean inv = map0.getInvert();
            map0.setInvert(inv0);
            Mapping map6 = new CmpMap(map0, map5, true);
            Mapping simplified = map6.simplify();
            maps[inode - 1] = simplified;
            inverts[inode - 1] = simplified.getInvert() ? 1 : 0;
            restoreInvert(map0, set, inv);
        }
        vfs.setCurrent(ifrm + 1);
    }

    /**
     * Adds a variant Mapping to the current Frame, or renames the variant in use (C's {@code
     * astAddVariant}). {@code map} converts from the current Frame to the new variant, which
     * becomes the one in use; with no Mapping the variant in use is renamed {@code name}.
     *
     * @throws AstException with status {@code AST__MIRRO} if the current Frame mirrors another's
     *     variants, or {@code AST__BDVNM} if it already has a variant called {@code name}
     */
    void addVariant(Mapping map, String name) {
        int icur = getCurrent();
        if (varfrms[icur - 1] > 0) {
            throw Ast.err(
                    AstException.AST__MIRRO,
                    "astAddVariant(" + astClass()
                            + "): Illegal attempt to add a variant Mapping to a mirror Frame (programming error).");
        }
        String myname = cleanName(name);
        Frame frm = frames[icur - 1];
        FrameSet vfs = frm.getFrameVariants();
        boolean isNew = vfs == null;
        if (isNew) {
            vfs = new FrameSet((Frame) frm.copy());
        }
        for (int ifrm = 0; ifrm < vfs.nframe; ifrm++) {
            if (vfs.frames[ifrm].getDomain().equals(myname)) {
                throw Ast.err(
                        AstException.AST__BDVNM,
                        "astAddVariant(" + astClass() + "): Cannot add a variant " + frm.getDomain()
                                + " Frame with name '" + myname + "' because one already exists in the " + astClass()
                                + " (programming error).");
            }
        }
        if (map == null) {
            vfs.currentFrame().setDomain(name);
        } else {
            Mapping map1 = convertMatchingDomain(vfs.currentFrame(), frm, "astAddVariant", astClass());
            Mapping map3 = new CmpMap(map1, map, true).simplify();
            Frame vfrm = (Frame) frm.copy();
            vfrm.setFrameVariants(null);
            vfrm.setDomain(name);
            int vcur = vfs.getCurrent();
            vfs.addFrame(AST__CURRENT, map3, vfrm);
            vfs.setCurrent(vcur);
        }
        if (isNew) {
            frm.setFrameVariants(vfs);
        }
        if (map != null) {
            setVariant(name);
        }
    }

    // ------------------------------------------------------------------
    // The FrameSet as a Mapping
    // ------------------------------------------------------------------

    /**
     * Gets the number of input coordinates: the number of axes of the base Frame.
     *
     * @return the number of input coordinates
     */
    @Override
    public int getNin() {
        return frames[getBase() - 1].getNaxes();
    }

    /**
     * Gets the number of output coordinates: the number of axes of the current Frame.
     *
     * @return the number of output coordinates
     */
    @Override
    public int getNout() {
        return getNaxes();
    }

    /**
     * Gets the TranForward attribute: whether the Mapping from the base Frame to the current Frame
     * has a forward transformation.
     *
     * @return true if the forward transformation is defined
     */
    @Override
    public boolean getTranForward() {
        return getMapping(AST__BASE, AST__CURRENT).getTranForward();
    }

    /**
     * Gets the TranInverse attribute: whether the Mapping from the base Frame to the current Frame
     * has an inverse transformation.
     *
     * @return true if the inverse transformation is defined
     */
    @Override
    public boolean getTranInverse() {
        return getMapping(AST__BASE, AST__CURRENT).getTranInverse();
    }

    /** Whether the Mapping from the base Frame to the current Frame is linear (C's {@code GetIsLinear}). */
    @Override
    boolean isLinear() {
        return getMapping(AST__BASE, AST__CURRENT).isLinear();
    }

    /** Transforms through the Mapping from the base Frame to the current Frame (C's {@code Transform}). */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        getMapping(AST__BASE, AST__CURRENT).transformInto(in, npoint, forward, out);
    }

    /** The NaN protocol: {@link #transformInto}, with the bad values it writes turned into NaN. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        transformInto(in, npoint, forward, out);
        int ncoordOut = forward ? getNout() : getNin();
        for (int c = 0; c < ncoordOut; c++) {
            double[] axis = out[c];
            for (int p = 0; p < npoint; p++) {
                if (axis[p] == AST__BAD) {
                    axis[p] = Double.NaN;
                }
            }
        }
    }

    /**
     * Reports each value as the Frame it is in formats it: the input values by the base Frame and
     * the output values by the current Frame, the other way round for the inverse (C's {@code
     * ReportPoints}).
     */
    @Override
    void reportPoints(boolean forward, double[][] in, double[][] out, int npoint) {
        Frame baseFrame = frames[getBase() - 1];
        Frame currentFrame = currentFrame();
        Frame fin = forward ? baseFrame : currentFrame;
        Frame fout = forward ? currentFrame : baseFrame;
        MappingReport.write(
                MappingReport.lines(in, out, npoint, (c, v) -> fin.format(c + 1, v), (c, v) -> fout.format(c + 1, v)));
    }

    /** The rate of change of the Mapping from the base Frame to the current Frame (C's {@code Rate}). */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        return getMapping(AST__BASE, AST__CURRENT).rateAt(at, ax1, ax2);
    }

    /** Splits the Mapping from the base Frame to the current Frame (C's {@code MapSplit}). */
    @Override
    MapSplit splitOff(int[] in) {
        return getMapping(AST__BASE, AST__CURRENT).mapSplit(in);
    }

    /**
     * Simplifies each of the FrameSet's Mappings, used with its Invert value, in a copy of the
     * FrameSet (C's {@code Simplify}): the copy if any Mapping was simplified, otherwise this
     * FrameSet.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        FrameSet copy = (FrameSet) copy();
        boolean simpler = false;
        for (int inode = 1; inode < copy.nnode; inode++) {
            Mapping map = copy.maps[inode - 1];
            boolean invert = copy.inverts[inode - 1] != 0;
            boolean set = map.testInvert();
            boolean inv = map.getInvert();
            if (inv != invert) {
                map.setInvert(invert);
            }
            Mapping tmp = map.simplify();
            if (inv != invert) {
                restoreInvert(map, set, inv);
            }
            if (tmp != map) {
                copy.maps[inode - 1] = tmp;
                copy.inverts[inode - 1] = tmp.getInvert() ? 1 : 0;
                simpler = true;
            }
        }
        return simpler ? copy : this;
    }

    // ------------------------------------------------------------------
    // The FrameSet as a Frame: the current Frame's axes and methods
    // ------------------------------------------------------------------

    /**
     * Gets the number of axes: that of the current Frame.
     *
     * @return the number of axes
     */
    @Override
    public int getNaxes() {
        return currentFrame().getNaxes();
    }

    @Override
    int[] getPerm() {
        return currentFrame().getPerm();
    }

    /** C's {@code ValidateAxis} (frameset.c): the axis is checked here, then by the current Frame. */
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
        return currentFrame().validateAxis(axis, fwd, "astValidateAxis");
    }

    /** The current Frame, after checking an axis number as {@code method}. */
    private Frame axisFrame(int axis, String method) {
        validateAxis(axis, true, method);
        return currentFrame();
    }

    @Override
    Axis getAxis(int axis) {
        return axisFrame(axis, "astGetAxis").getAxis(axis);
    }

    @Override
    void setAxis(int axis, Axis ax) {
        axisFrame(axis, "astSetAxis").setAxis(axis, ax);
    }

    @Override
    Frame primaryFrame(int axis, int[] primaryAxis) {
        return axisFrame(axis, "astPrimaryFrame").primaryFrame(axis, primaryAxis);
    }

    @Override
    void checkPerm(int[] p, String method) {
        currentFrame().checkPerm(p, method);
    }

    /**
     * Permutes the order of the current Frame's axes, and remaps it so that each coordinate value
     * stays with its axis (C's {@code PermAxes} in frameset.c). The current Frame's variant
     * Mappings other than the one in use are removed, as {@link #remapFrame} removes them.
     *
     * @param perm the permutation, one element for each axis, numbered from 1
     * @throws AstException with status {@code AST__PRMIN} if {@code perm} does not hold each axis
     *     once, or {@code AST__DIMIN} if it is too short
     */
    @Override
    public void permAxes(int[] perm) {
        int naxes = getNaxes();
        checkDim("astPermAxes", "permutation", perm.length, naxes);
        int[] perm0 = new int[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            perm0[axis] = perm[axis] - 1;
        }
        checkPerm(perm0, "astPermAxes");
        currentFrame().permAxes(perm);
        int[] invperm = new int[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            invperm[perm0[axis]] = axis;
        }
        remapFrame(AST__CURRENT, PermMap.fromInternal(naxes, invperm, naxes, perm0, null));
    }

    @Override
    void validateAxisSelection(int nsel, int[] sel, String method) {
        currentFrame().validateAxisSelection(nsel, sel, method);
    }

    /**
     * Creates a new Frame from a selection of the current Frame's axes, as the current Frame's
     * own {@code pickAxes} does.
     *
     * @param naxes the number of axes to pick
     * @param axes the axes to pick, numbered from 1
     * @param map null, or an array of at least one element, which receives the Mapping from the
     *     current Frame's coordinates to the new Frame's
     * @return the new Frame
     */
    @Override
    public Frame pickAxes(int naxes, int[] axes, Mapping[] map) {
        return currentFrame().pickAxes(naxes, axes, map);
    }

    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        return currentFrame().subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
    }

    @Override
    void overlay(int[] templateAxes, Frame result) {
        currentFrame().overlay(templateAxes, result);
    }

    @Override
    boolean isUnitFrame() {
        return currentFrame().isUnitFrame();
    }

    // ------------------------------------------------------------------
    // Finding and converting between coordinate systems
    // ------------------------------------------------------------------

    /**
     * A Frame of the FrameSet, the FrameSet's own and not marked as shared, for use while a search
     * runs (C's {@code astGetFrame}, whose clone is annulled before the search returns).
     */
    private Frame frameAt(int iframe) {
        return frames[validateFrameIndex(iframe, "astGetFrame") - 1];
    }

    /**
     * Finds a coordinate system with given characteristics among the FrameSet's Frames (C's {@code
     * FindFrame}, frameset.c). Each field of the domain list is taken in turn, and within it each
     * Frame in the order current, base, then the others in ascending order, until a Frame's own
     * search with that field finds a match. The result runs from the base Frame, through the
     * Mapping to the Frame matched, to the system found, and the Frame matched becomes the current
     * Frame; nothing is changed if there is no match.
     *
     * @param template the template, an instance of the kind of Frame wanted
     * @param domainlist a comma-separated list of Domains in order of priority; an empty field
     *     accepts any Domain, and case and white space are ignored
     * @return a FrameSet whose base Frame is this FrameSet's base Frame and whose current Frame is
     *     the system found, or null if no system is found
     */
    @Override
    public FrameSet findFrame(Frame template, String domainlist) {
        int targetNframe = nframe;
        int targetCurrent = getCurrent();
        int targetBase = getBase();
        int[] targetOrder = new int[targetNframe];
        int n = 0;
        targetOrder[n++] = targetCurrent;
        if (targetBase != targetCurrent) {
            targetOrder[n++] = targetBase;
        }
        for (int targetIndex = 1; targetIndex <= targetNframe; targetIndex++) {
            if (targetIndex != targetCurrent && targetIndex != targetBase) {
                targetOrder[n++] = targetIndex;
            }
        }
        FrameSet result = null;
        int matched = 0;
        for (String domain : FrameFinder.domainFields(domainlist)) {
            if (result != null) {
                break;
            }
            for (int targetNumber = 0; result == null && targetNumber < targetNframe; targetNumber++) {
                int targetIndex = targetOrder[targetNumber];
                FrameSet found = frameAt(targetIndex).findFrame(template, domain);
                if (found != null) {
                    matched = targetIndex;
                    Mapping map = found.getMapping(AST__BASE, AST__CURRENT);
                    Frame frame = found.frameAt(AST__CURRENT);
                    Mapping prefix = getMapping(AST__BASE, targetIndex);
                    map = CmpMap.transientCmpMap(prefix, map, true).simplify();
                    result = new FrameSet(frameAt(AST__BASE));
                    result.addFrame(AST__BASE, map, frame);
                }
            }
        }
        if (result != null) {
            setCurrent(matched);
        }
        return result;
    }

    /** The conversion from this FrameSet to {@code to}, by frameset.c's search ({@code Convert}). */
    @Override
    FrameSet convertFrames(Frame to, String domainlist) {
        return convertX(to, this, domainlist);
    }

    /** The conversion from {@code from} to this FrameSet, by frameset.c's search ({@code ConvertX}). */
    @Override
    FrameSet convertX(Frame from, String domainlist) {
        return convertX(this, from, domainlist);
    }

    /** The indices of a FrameSet's Frames in the order a conversion tries them (frameset.c {@code ConvertX}). */
    private static int[] searchOrder(FrameSet fs) {
        int nfr = fs.getNframe();
        int base = fs.getBase();
        int current = fs.getCurrent();
        int[] order = new int[nfr];
        int n = 0;
        order[n++] = base;
        if (!fs.getInvert()) {
            for (int index = 1; index <= nfr; index++) {
                if (index != base && index != current) {
                    order[n++] = index;
                }
            }
        } else {
            for (int index = nfr; index >= 1; index--) {
                if (index != base && index != current) {
                    order[n++] = index;
                }
            }
        }
        if (current != base) {
            order[n] = current;
        }
        return order;
    }

    /**
     * The conversion between two Frames either of which is a FrameSet (C's {@code ConvertX},
     * frameset.c). Each field of the domain list is taken in turn; within it each Frame of {@code
     * to} is tried against each Frame of {@code from}, in the order base Frame, the others in
     * ascending order (descending while the FrameSet is inverted), then current Frame, through
     * {@code convert} between the two Frames. The Mapping from the current Frame of {@code from}
     * through the two Frames to the current Frame of {@code to} is simplified and scored, 2 for a
     * forward transformation and 1 for an inverse, and the best is kept; a score of 3 ends the
     * search. When a conversion is found, the base Frame of each FrameSet is set to the Frame it was
     * found through.
     *
     * @return a FrameSet whose base Frame is the current Frame of {@code from} and whose current
     *     Frame is that of {@code to}, or null if there is no conversion
     */
    private static FrameSet convertX(Frame to, Frame from, String domainlist) {
        FrameSet fromSet = from instanceof FrameSet ? (FrameSet) from : null;
        FrameSet toSet = to instanceof FrameSet ? (FrameSet) to : null;
        int fromNframe = fromSet == null ? 1 : fromSet.getNframe();
        int fromCurrent = fromSet == null ? 1 : fromSet.getCurrent();
        int toNframe = toSet == null ? 1 : toSet.getNframe();
        int toCurrent = toSet == null ? 1 : toSet.getCurrent();
        int[] fromOrder = fromSet == null ? new int[] {1} : searchOrder(fromSet);
        int[] toOrder = toSet == null ? new int[] {1} : searchOrder(toSet);

        Mapping resultMap = null;
        int iframeFrom = 0;
        int iframeTo = 0;
        boolean match = false;
        for (String domain : FrameFinder.domainFields(domainlist)) {
            if (match) {
                break;
            }
            int bestScore = -1;
            boolean perfect = false;
            for (int toNumber = 0; !perfect && toNumber < toNframe; toNumber++) {
                int toIndex = toOrder[toNumber];
                Frame toFrame = toSet == null ? to : toSet.frameAt(toIndex);
                for (int fromNumber = 0; !perfect && fromNumber < fromNframe; fromNumber++) {
                    int fromIndex = fromOrder[fromNumber];
                    Frame fromFrame = fromSet == null ? from : fromSet.frameAt(fromIndex);
                    FrameSet cvt = fromFrame.convertFrames(toFrame, domain);
                    if (cvt == null) {
                        continue;
                    }
                    Mapping map = cvt.getMapping(AST__BASE, AST__CURRENT);
                    if (fromIndex != fromCurrent) {
                        Mapping fromMap = fromSet.getMapping(AST__CURRENT, fromIndex);
                        map = CmpMap.transientCmpMap(fromMap, map, true);
                    }
                    if (toIndex != toCurrent) {
                        Mapping toMap = toSet.getMapping(toIndex, AST__CURRENT);
                        map = CmpMap.transientCmpMap(map, toMap, true);
                    }
                    map = map.simplify();
                    int score = (map.getTranForward() ? 2 : 0) + (map.getTranInverse() ? 1 : 0);
                    if (score > bestScore) {
                        match = true;
                        bestScore = score;
                        perfect = bestScore >= 3;
                        resultMap = map;
                        iframeFrom = fromIndex;
                        iframeTo = toIndex;
                    }
                }
            }
        }
        if (resultMap == null) {
            return null;
        }
        FrameSet result = new FrameSet(fromSet == null ? from : fromSet.frameAt(AST__CURRENT));
        result.addFrame(AST__BASE, resultMap, toSet == null ? to : toSet.frameAt(AST__CURRENT));
        if (fromSet != null) {
            fromSet.setBase(iframeFrom);
        }
        if (toSet != null) {
            toSet.setBase(iframeTo);
        }
        return result;
    }

    /** Matches the current Frame, as a template, against a target (C's {@code Match}, frameset.c). */
    @Override
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        return FrameFinder.match(currentFrame(), target, matchSub);
    }

    /**
     * This FrameSet cast into the class of another Frame (C's {@code Cast}, frameset.c): a copy
     * when cast into a FrameSet, otherwise its current Frame cast into that class.
     */
    @Override
    Frame cast(Frame obj) {
        if (obj instanceof FrameSet) {
            return (Frame) copy();
        }
        return currentFrame().cast(obj);
    }

    /** The axes of the current Frame that correspond to those of {@code frm2} (C's {@code MatchAxes}, frameset.c). */
    @Override
    int[] matchAxes(Frame frm2) {
        return frm2.matchAxesX(currentFrame());
    }

    /** The axes of {@code frm1} that correspond to those of the current Frame (C's {@code MatchAxesX}, frameset.c). */
    @Override
    int[] matchAxesX(Frame frm1) {
        return currentFrame().matchAxesX(frm1);
    }

    /**
     * Formats a coordinate value for an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param value the value
     * @return the formatted value
     */
    @Override
    public String format(int axis, double value) {
        return axisFrame(axis, "astFormat").format(axis, value);
    }

    @Override
    int unformat(int axis, String string, double[] value) {
        return axisFrame(axis, "astUnformat").unformat(axis, string, value);
    }

    /**
     * Finds the angle at point B between the lines to points A and C, in the current Frame.
     *
     * @param a point A
     * @param b point B
     * @param c point C
     * @return the angle
     */
    @Override
    public double angle(double[] a, double[] b, double[] c) {
        return currentFrame().angle(a, b, c);
    }

    /**
     * Finds the angle from an axis of the current Frame to the line between two points.
     *
     * @param a the first point
     * @param b the second point
     * @param axis the axis number, from 1
     * @return the angle
     */
    @Override
    public double axAngle(double[] a, double[] b, int axis) {
        return axisFrame(axis, "astAxAngle").axAngle(a, b, axis);
    }

    /**
     * Finds the distance between two values on an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param v1 the first value
     * @param v2 the second value
     * @return the distance
     */
    @Override
    public double axDistance(int axis, double v1, double v2) {
        return axisFrame(axis, "astAxDistance").axDistance(axis, v1, v2);
    }

    /**
     * Adds an increment to a value on an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param v1 the value
     * @param dist the increment
     * @return the result
     */
    @Override
    public double axOffset(int axis, double v1, double dist) {
        return axisFrame(axis, "astAxOffset").axOffset(axis, v1, dist);
    }

    @Override
    void axNorm(int axis, int oper, double[] values) {
        axisFrame(axis, "astAxNorm").axNorm(axis, oper, values);
    }

    /**
     * Finds the distance between two points in the current Frame.
     *
     * @param point1 the first point
     * @param point2 the second point
     * @return the distance
     */
    @Override
    public double distance(double[] point1, double[] point2) {
        return currentFrame().distance(point1, point2);
    }

    /**
     * Finds the point a distance along the geodesic from one point to another in the current
     * Frame.
     *
     * @param point1 the start
     * @param point2 the point that gives the direction
     * @param offset the distance
     * @return the point
     */
    @Override
    public double[] offset(double[] point1, double[] point2, double offset) {
        return currentFrame().offset(point1, point2, offset);
    }

    /**
     * Finds the point a distance in a given direction from another point in the current Frame.
     *
     * @param point1 the start
     * @param angle the direction
     * @param offset the distance
     * @param point2 receives the point
     * @return the direction of the geodesic at the point found
     */
    @Override
    public double offset2(double[] point1, double angle, double offset, double[] point2) {
        return currentFrame().offset2(point1, angle, offset, point2);
    }

    /**
     * Finds the point where two geodesics cross in the current Frame.
     *
     * @param a1 a point on the first geodesic
     * @param a2 another point on the first geodesic
     * @param b1 a point on the second geodesic
     * @param b2 another point on the second geodesic
     * @return the crossing point
     */
    @Override
    public double[] intersect(double[] a1, double[] a2, double[] b1, double[] b2) {
        return currentFrame().intersect(a1, a2, b1, b2);
    }

    /**
     * Normalises a point in the current Frame.
     *
     * @param value the point, changed in place
     */
    @Override
    public void norm(double[] value) {
        currentFrame().norm(value);
    }

    @Override
    double[][] normalPoints(double[][] in, int npoint, boolean contig) {
        return currentFrame().normalPoints(in, npoint, contig);
    }

    /**
     * Resolves a vector into components parallel and perpendicular to another in the current
     * Frame.
     *
     * @param point1 the start of the basis vector
     * @param point2 the end of the basis vector
     * @param point3 the end of the vector to resolve
     * @param point4 receives the point of closest approach on the basis vector
     * @return the parallel and perpendicular components
     */
    @Override
    public double[] resolve(double[] point1, double[] point2, double[] point3, double[] point4) {
        return currentFrame().resolve(point1, point2, point3, point4);
    }

    @Override
    double[][] resolvePoints(double[] point1, double[] point2, double[][] in, int npoint) {
        return currentFrame().resolvePoints(point1, point2, in, npoint);
    }

    @Override
    int systemCode(String name) {
        return currentFrame().systemCode(name);
    }

    @Override
    String systemString(int code) {
        return currentFrame().systemString(code);
    }

    @Override
    int validateSystem(int code, String method) {
        return currentFrame().validateSystem(code, method);
    }

    // ------------------------------------------------------------------
    // The FrameSet as a Frame: the current Frame's attributes. The public setters go through
    // the attribute strings, so that integrity is kept.
    // ------------------------------------------------------------------

    /**
     * Gets the Title of the current Frame.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        return currentFrame().getTitle();
    }

    /**
     * Sets the Title of the current Frame.
     *
     * @param title the Title
     */
    @Override
    public void setTitle(String title) {
        setC("Title", title);
    }

    @Override
    boolean testTitle() {
        return currentFrame().testTitle();
    }

    @Override
    void clearTitle() {
        currentFrame().clearTitle();
    }

    /**
     * Gets the Domain of the current Frame.
     *
     * @return the Domain
     */
    @Override
    public String getDomain() {
        return currentFrame().getDomain();
    }

    /**
     * Sets the Domain of the current Frame.
     *
     * @param domain the Domain
     */
    @Override
    public void setDomain(String domain) {
        setC("Domain", domain);
    }

    @Override
    boolean testDomain() {
        return currentFrame().testDomain();
    }

    @Override
    void clearDomain() {
        currentFrame().clearDomain();
    }

    @Override
    double getEpochMjd() {
        return currentFrame().getEpochMjd();
    }

    @Override
    void setEpochMjd(double mjd) {
        currentFrame().setEpochMjd(mjd);
    }

    @Override
    boolean testEpoch() {
        return currentFrame().testEpoch();
    }

    @Override
    void clearEpoch() {
        currentFrame().clearEpoch();
    }

    @Override
    int systemValue() {
        return currentFrame().systemValue();
    }

    @Override
    void setSystemValue(int value) {
        currentFrame().setSystemValue(value);
    }

    @Override
    boolean testSystem() {
        return currentFrame().testSystem();
    }

    @Override
    void clearSystem() {
        currentFrame().clearSystem();
    }

    @Override
    int alignSystemValue() {
        return currentFrame().alignSystemValue();
    }

    @Override
    void setAlignSystemValue(int value) {
        currentFrame().setAlignSystemValue(value);
    }

    @Override
    boolean testAlignSystem() {
        return currentFrame().testAlignSystem();
    }

    @Override
    void clearAlignSystem() {
        currentFrame().clearAlignSystem();
    }

    /**
     * Gets the Digits of the current Frame.
     *
     * @return the Digits
     */
    @Override
    public int getDigits() {
        return currentFrame().getDigits();
    }

    /**
     * Sets the Digits of the current Frame.
     *
     * @param digits the Digits
     */
    @Override
    public void setDigits(int digits) {
        setI("Digits", digits);
    }

    @Override
    boolean testDigits() {
        return currentFrame().testDigits();
    }

    @Override
    void clearDigits() {
        currentFrame().clearDigits();
    }

    /**
     * Sets the Digits of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param digits the Digits
     */
    @Override
    public void setDigits(int axis, int digits) {
        setI("Digits(" + axis + ")", digits);
    }

    /**
     * Gets the MatchEnd of the current Frame.
     *
     * @return the MatchEnd
     */
    @Override
    public boolean getMatchEnd() {
        return currentFrame().getMatchEnd();
    }

    /**
     * Sets the MatchEnd of the current Frame.
     *
     * @param matchEnd the MatchEnd
     */
    @Override
    public void setMatchEnd(boolean matchEnd) {
        setB("MatchEnd", matchEnd);
    }

    @Override
    boolean testMatchEnd() {
        return currentFrame().testMatchEnd();
    }

    @Override
    void clearMatchEnd() {
        currentFrame().clearMatchEnd();
    }

    /**
     * Gets the MaxAxes of the current Frame.
     *
     * @return the MaxAxes
     */
    @Override
    public int getMaxAxes() {
        return currentFrame().getMaxAxes();
    }

    /**
     * Sets the MaxAxes of the current Frame.
     *
     * @param maxAxes the MaxAxes
     */
    @Override
    public void setMaxAxes(int maxAxes) {
        setI("MaxAxes", maxAxes);
    }

    @Override
    boolean testMaxAxes() {
        return currentFrame().testMaxAxes();
    }

    @Override
    void clearMaxAxes() {
        currentFrame().clearMaxAxes();
    }

    /**
     * Gets the MinAxes of the current Frame.
     *
     * @return the MinAxes
     */
    @Override
    public int getMinAxes() {
        return currentFrame().getMinAxes();
    }

    /**
     * Sets the MinAxes of the current Frame.
     *
     * @param minAxes the MinAxes
     */
    @Override
    public void setMinAxes(int minAxes) {
        setI("MinAxes", minAxes);
    }

    @Override
    boolean testMinAxes() {
        return currentFrame().testMinAxes();
    }

    @Override
    void clearMinAxes() {
        currentFrame().clearMinAxes();
    }

    /**
     * Gets the Permute of the current Frame.
     *
     * @return the Permute
     */
    @Override
    public boolean getPermute() {
        return currentFrame().getPermute();
    }

    /**
     * Sets the Permute of the current Frame.
     *
     * @param permute the Permute
     */
    @Override
    public void setPermute(boolean permute) {
        setB("Permute", permute);
    }

    @Override
    boolean testPermute() {
        return currentFrame().testPermute();
    }

    @Override
    void clearPermute() {
        currentFrame().clearPermute();
    }

    /**
     * Gets the PreserveAxes of the current Frame.
     *
     * @return the PreserveAxes
     */
    @Override
    public boolean getPreserveAxes() {
        return currentFrame().getPreserveAxes();
    }

    /**
     * Sets the PreserveAxes of the current Frame.
     *
     * @param preserveAxes the PreserveAxes
     */
    @Override
    public void setPreserveAxes(boolean preserveAxes) {
        setB("PreserveAxes", preserveAxes);
    }

    @Override
    boolean testPreserveAxes() {
        return currentFrame().testPreserveAxes();
    }

    @Override
    void clearPreserveAxes() {
        currentFrame().clearPreserveAxes();
    }

    @Override
    double getObsLatValue() {
        return currentFrame().getObsLatValue();
    }

    @Override
    void setObsLatValue(double value) {
        currentFrame().setObsLatValue(value);
    }

    @Override
    boolean testObsLat() {
        return currentFrame().testObsLat();
    }

    @Override
    void clearObsLat() {
        currentFrame().clearObsLat();
    }

    @Override
    double getObsLonValue() {
        return currentFrame().getObsLonValue();
    }

    @Override
    void setObsLonValue(double value) {
        currentFrame().setObsLonValue(value);
    }

    @Override
    boolean testObsLon() {
        return currentFrame().testObsLon();
    }

    @Override
    void clearObsLon() {
        currentFrame().clearObsLon();
    }

    @Override
    double getObsAlt() {
        return currentFrame().getObsAlt();
    }

    @Override
    void setObsAlt(double value) {
        currentFrame().setObsAlt(value);
    }

    @Override
    boolean testObsAlt() {
        return currentFrame().testObsAlt();
    }

    @Override
    void clearObsAlt() {
        currentFrame().clearObsAlt();
    }

    @Override
    double getDtai() {
        return currentFrame().getDtai();
    }

    @Override
    void setDtai(double value) {
        currentFrame().setDtai(value);
    }

    @Override
    boolean testDtai() {
        return currentFrame().testDtai();
    }

    @Override
    void clearDtai() {
        currentFrame().clearDtai();
    }

    /**
     * Gets the Dut1 of the current Frame.
     *
     * @return the Dut1
     */
    @Override
    public double getDut1() {
        return currentFrame().getDut1();
    }

    /**
     * Sets the Dut1 of the current Frame.
     *
     * @param dut1 the Dut1
     */
    @Override
    public void setDut1(double dut1) {
        setD("Dut1", dut1);
    }

    @Override
    boolean testDut1() {
        return currentFrame().testDut1();
    }

    @Override
    void clearDut1() {
        currentFrame().clearDut1();
    }

    /**
     * Gets the ActiveUnit flag of the current Frame.
     *
     * @return the ActiveUnit flag
     */
    @Override
    public boolean getActiveUnit() {
        return currentFrame().getActiveUnit();
    }

    /**
     * Sets the ActiveUnit flag of the current Frame.
     *
     * @param value the ActiveUnit flag
     */
    @Override
    public void setActiveUnit(boolean value) {
        setB("ActiveUnit", value);
    }

    @Override
    boolean testActiveUnit() {
        return currentFrame().testActiveUnit();
    }

    /**
     * Gets the Label of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Label
     */
    @Override
    public String getLabel(int axis) {
        return axisFrame(axis, "astGetLabel").getLabel(axis);
    }

    /**
     * Sets the Label of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param label the Label
     */
    @Override
    public void setLabel(int axis, String label) {
        setC("Label(" + axis + ")", label);
    }

    @Override
    boolean testLabel(int axis) {
        return axisFrame(axis, "astTestLabel").testLabel(axis);
    }

    @Override
    void clearLabel(int axis) {
        axisFrame(axis, "astClearLabel").clearLabel(axis);
    }

    /**
     * Gets the Symbol of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Symbol
     */
    @Override
    public String getSymbol(int axis) {
        return axisFrame(axis, "astGetSymbol").getSymbol(axis);
    }

    /**
     * Sets the Symbol of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param symbol the Symbol
     */
    @Override
    public void setSymbol(int axis, String symbol) {
        setC("Symbol(" + axis + ")", symbol);
    }

    @Override
    boolean testSymbol(int axis) {
        return axisFrame(axis, "astTestSymbol").testSymbol(axis);
    }

    @Override
    void clearSymbol(int axis) {
        axisFrame(axis, "astClearSymbol").clearSymbol(axis);
    }

    /**
     * Gets the Unit of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Unit
     */
    @Override
    public String getUnit(int axis) {
        return axisFrame(axis, "astGetUnit").getUnit(axis);
    }

    /**
     * Sets the Unit of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param unit the Unit
     */
    @Override
    public void setUnit(int axis, String unit) {
        setC("Unit(" + axis + ")", unit);
    }

    @Override
    boolean testUnit(int axis) {
        return axisFrame(axis, "astTestUnit").testUnit(axis);
    }

    @Override
    void clearUnit(int axis) {
        axisFrame(axis, "astClearUnit").clearUnit(axis);
    }

    /**
     * Gets the Format of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Format
     */
    @Override
    public String getFormat(int axis) {
        return axisFrame(axis, "astGetFormat").getFormat(axis);
    }

    /**
     * Sets the Format of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param format the Format
     */
    @Override
    public void setFormat(int axis, String format) {
        setC("Format(" + axis + ")", format);
    }

    @Override
    boolean testFormat(int axis) {
        return axisFrame(axis, "astTestFormat").testFormat(axis);
    }

    @Override
    void clearFormat(int axis) {
        axisFrame(axis, "astClearFormat").clearFormat(axis);
    }

    /**
     * Gets the Direction of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Direction
     */
    @Override
    public boolean getDirection(int axis) {
        return axisFrame(axis, "astGetDirection").getDirection(axis);
    }

    /**
     * Sets the Direction of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param direction the Direction
     */
    @Override
    public void setDirection(int axis, boolean direction) {
        setB("Direction(" + axis + ")", direction);
    }

    @Override
    boolean testDirection(int axis) {
        return axisFrame(axis, "astTestDirection").testDirection(axis);
    }

    @Override
    void clearDirection(int axis) {
        axisFrame(axis, "astClearDirection").clearDirection(axis);
    }

    /**
     * Gets the Top of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Top
     */
    @Override
    public double getTop(int axis) {
        return axisFrame(axis, "astGetTop").getTop(axis);
    }

    /**
     * Sets the Top of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param top the Top
     */
    @Override
    public void setTop(int axis, double top) {
        setD("Top(" + axis + ")", top);
    }

    @Override
    boolean testTop(int axis) {
        return axisFrame(axis, "astTestTop").testTop(axis);
    }

    @Override
    void clearTop(int axis) {
        axisFrame(axis, "astClearTop").clearTop(axis);
    }

    /**
     * Gets the Bottom of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @return the Bottom
     */
    @Override
    public double getBottom(int axis) {
        return axisFrame(axis, "astGetBottom").getBottom(axis);
    }

    /**
     * Sets the Bottom of an axis of the current Frame.
     *
     * @param axis the axis number, from 1
     * @param bottom the Bottom
     */
    @Override
    public void setBottom(int axis, double bottom) {
        setD("Bottom(" + axis + ")", bottom);
    }

    @Override
    boolean testBottom(int axis) {
        return axisFrame(axis, "astTestBottom").testBottom(axis);
    }

    @Override
    void clearBottom(int axis) {
        axisFrame(axis, "astClearBottom").clearBottom(axis);
    }
}
