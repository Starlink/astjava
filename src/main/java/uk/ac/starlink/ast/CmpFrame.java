package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * A compound coordinate system: two component Frames, of any class, merged into one. The axes of
 * the first Frame come first, followed by those of the second. Since a CmpFrame is itself a
 * Frame, it may be a component of another CmpFrame, so Frames of any complexity may be built from
 * simple ones.
 *
 * <p>Used as a Mapping, a CmpFrame is equivalent to a UnitMap.
 *
 * <p>The CmpFrame class defines no attributes of its own, but the attributes of its component
 * Frames can be used as if they were the CmpFrame's: a CmpFrame holding a SkyFrame recognises the
 * SkyFrame's {@code Equinox}, for example, and passes it on. An axis number may be added to any
 * attribute name, as in {@code Equinox(1)}, to address the Frame that holds that axis. A name with
 * an axis number is read from that Frame directly, while it is set, tested and cleared as the
 * CmpFrame's own if the CmpFrame knows it: so the default {@code Label(2)} of a CmpFrame of two
 * plain one-axis Frames is that of the second Frame's only axis, {@code Axis 1}, while {@link
 * #getLabel getLabel(2)} gives {@code Axis 2}. The typed per-axis getters are the CmpFrame's own,
 * except {@link #getNormUnit}, which reads the attribute by name.
 *
 * <p>The Epoch, ObsLat, ObsLon, ObsAlt, Dtai and Dut1 set on or cleared from a CmpFrame are set on
 * or cleared from both components as well; when not set on the CmpFrame they are those of the
 * first component that has them set, or else the first component's defaults. ActiveUnit is set on
 * both components too, and by default is true if either component uses active units; clearing
 * AlignSystem clears it from both components. The per-axis attributes Direction, Format, Label,
 * Symbol and Unit belong to the component holding the axis, which formats and reads the axis
 * values.
 *
 * <p>Ported from C {@code cmpframe.c}, with Rust {@code frames/cmpframe.rs}. The Plot methods of
 * C's CmpFrame ({@code Abbrev}, {@code Centre}, {@code Fields}, {@code FrameGrid}, {@code Gap},
 * {@code NormBox}) and its Region handling ({@code RemoveRegions}) are not ported.
 */
public class CmpFrame extends Frame {

    /** The System value of a CmpFrame, its only one ({@code AST__COMP}). */
    static final int AST__COMP = 0;

    /** The MaxAxes of a CmpFrame's component when the CmpFrame's own is set (C's sentinel). */
    private static final int ANY_MAX_AXES = 100000;

    /** The first component Frame. */
    private Frame frame1;

    /** The second component Frame. */
    private Frame frame2;

    /** The permutation: {@code perm[external] = internal}, numbered from 0. */
    private int[] perm;

    /**
     * Creates a CmpFrame from two component Frames, which are held, not copied: later changes to
     * them change the CmpFrame. The CmpFrame's axes are those of {@code frame1} followed by those of
     * {@code frame2}.
     *
     * @param frame1 the first component Frame
     * @param frame2 the second component Frame
     */
    public CmpFrame(Frame frame1, Frame frame2) {
        this(frame1, frame2, true);
    }

    /** Creates a CmpFrame, marking its components shared if {@code share} (C's {@code astInitCmpFrame}). */
    private CmpFrame(Frame frame1, Frame frame2, boolean share) {
        super();
        this.frame1 = Objects.requireNonNull(frame1, "frame1");
        this.frame2 = Objects.requireNonNull(frame2, "frame2");
        if (share) {
            frame1.markShared();
            frame2.markShared();
        }
        int naxes = frame1.getNaxes() + frame2.getNaxes();
        perm = new int[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            perm[axis] = axis;
        }
    }

    /**
     * A CmpFrame made within a computation whose components only it refers to, as C's are once the
     * caller annuls its own pointers: the components are not marked shared.
     */
    static CmpFrame transientCmpFrame(Frame frame1, Frame frame2) {
        return new CmpFrame(frame1, frame2, false);
    }

    /**
     * Loads a CmpFrame from its description, as C's {@code astLoadCmpFrame} does: the component
     * Frames {@code FrameA} and {@code FrameB} (a one-axis Frame where one is missing) and the
     * permutation {@code Axp<n>}.
     *
     * @param node the description
     * @throws AstException with status {@code AST__LDERR} if a component is not a Frame
     */
    CmpFrame(NativeNode node) {
        super(node);
        NativeNode part = node.section("CmpFrame");
        frame1 = loadComponent(part, "FrameA");
        frame2 = loadComponent(part, "FrameB");
        int naxes = frame1.getNaxes() + frame2.getNaxes();
        perm = new int[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            perm[axis] = part.readInt("Axp" + (axis + 1), axis + 1) - 1;
        }
    }

    /** The Frame item {@code key} of a CmpFrame's description holds, or a one-axis Frame if it is missing. */
    private static Frame loadComponent(NativeNode part, String key) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            return new Frame(1);
        }
        AstObject obj = ObjectRegistry.load(desc);
        if (!(obj instanceof Frame)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadCmpFrame(CmpFrame): The object " + key + " is a " + obj.astClass() + ", not a Frame.");
        }
        return (Frame) obj;
    }

    @Override
    String dumpComment() {
        return "Compound coordinate system description";
    }

    /** A CmpFrame's description is written as a plain Frame's (frame.c {@code Dump}). */
    @Override
    boolean dumpIsFrame() {
        return true;
    }

    /**
     * Writes the CmpFrame class's part of a description, as C's {@code Dump} (cmpframe.c) does: the
     * permutation, each element as the internal axis numbered from 1, and the component Frames,
     * written with Full -1 unless Full is 1.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        int naxes = getNaxes();
        for (int axis = 0; axis < naxes; axis++) {
            boolean set = perm[axis] != axis;
            int ival = perm[axis] + 1;
            node.writeInt(
                    "Axp" + (axis + 1),
                    set,
                    false,
                    ival,
                    set
                            ? "Axis " + (axis + 1) + " permuted to use internal axis " + ival
                            : "Axis " + (axis + 1) + " not permuted");
        }
        NativeNode a = NativeNode.of(frame1);
        a.fullCapped = true;
        node.writeObject("FrameA", true, true, a, "First component Frame");
        NativeNode b = NativeNode.of(frame2);
        b.fullCapped = true;
        node.writeObject("FrameB", true, true, b, "Second component Frame");
    }

    /** Completes a copy as C's {@code Copy} (cmpframe.c) does: both components are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        frame1 = (Frame) frame1.copy();
        frame2 = (Frame) frame2.copy();
        perm = perm.clone();
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /**
     * Gets the two component Frames. They are the CmpFrame's own, not copies, so changes made to
     * them change the CmpFrame.
     *
     * @return the first and second component Frames
     */
    public Frame[] decompose() {
        Mapping[] frms = decompose(null, null);
        return new Frame[] {(Frame) frms[0], (Frame) frms[1]};
    }

    /**
     * The components, as C's {@code Decompose} (cmpframe.c) gives them: in parallel and in their
     * original order whether or not the CmpFrame is inverted, with their Invert values reversed
     * if it is.
     */
    @Override
    Mapping[] decomposeInto(boolean[] flags) {
        boolean inv = getInvert();
        flags[0] = false;
        flags[1] = inv != frame1.getInvert();
        flags[2] = inv != frame2.getInvert();
        return new Mapping[] {frame1, frame2};
    }

    /** The component Frame holding internal axis {@code internal}, numbered from 1. */
    private Frame component(int internal) {
        return internal <= frame1.getNaxes() ? frame1 : frame2;
    }

    /** The number, in its component Frame, of internal axis {@code internal}, both numbered from 1. */
    private int componentAxis(int internal) {
        int naxes1 = frame1.getNaxes();
        return internal <= naxes1 ? internal : internal - naxes1;
    }

    /** The coordinates of a point in internal order: {@code result[perm[axis]] = point[axis]}. */
    private double[] toInternal(double[] point) {
        int naxes = getNaxes();
        double[] p = new double[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            p[perm[axis]] = point[axis];
        }
        return p;
    }

    /** The coordinates of the second component, which follow those of the first in internal order. */
    private double[] second(double[] internal) {
        return Arrays.copyOfRange(internal, frame1.getNaxes(), internal.length);
    }

    // ------------------------------------------------------------------
    // The CmpFrame as a Mapping
    // ------------------------------------------------------------------

    /**
     * Transforms points as C's {@code Transform} (cmpframe.c) does, through the parallel CmpMap of
     * the two components, between PermMaps that put the coordinates into internal order and back.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        Mapping map = CmpMap.transientCmpMap(frame1, frame2, false);
        int naxes = getNaxes();
        boolean permuted = false;
        for (int i = 0; i < naxes; i++) {
            if (perm[i] != i) {
                permuted = true;
                break;
            }
        }
        if (permuted) {
            int[] outperm = new int[naxes];
            for (int i = 0; i < naxes; i++) {
                outperm[perm[i]] = i;
            }
            PermMap pmap = PermMap.fromInternal(naxes, perm, naxes, outperm, null);
            Mapping map2 = CmpMap.transientCmpMap(pmap, map, true);
            PermMap pinv = PermMap.fromInternal(naxes, perm, naxes, outperm, null);
            pinv.invert();
            map = CmpMap.transientCmpMap(map2, pinv, true);
        }
        map.transformInto(in, npoint, forward, out);
    }

    /**
     * Simplifies the components (C's {@code Simplify}, cmpframe.c): a copy holding the simplified
     * components if either changed and both are still Frames, otherwise this CmpFrame.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        Mapping map1 = frame1.simplify();
        Mapping map2 = frame2.simplify();
        if (map1 instanceof Frame && map2 instanceof Frame && (map1 != frame1 || map2 != frame2)) {
            CmpFrame result = (CmpFrame) copy();
            result.frame1 = (Frame) map1;
            result.frame2 = (Frame) map2;
            return result;
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Axes
    // ------------------------------------------------------------------

    /**
     * Gets the number of axes: the sum of those of the components.
     *
     * @return the number of axes
     */
    @Override
    public int getNaxes() {
        return frame1.getNaxes() + frame2.getNaxes();
    }

    @Override
    int[] getPerm() {
        return perm;
    }

    /** The Axis object of the component Frame holding the axis (C's {@code GetAxis}, cmpframe.c). */
    @Override
    Axis getAxis(int axis) {
        int internal = validateAxis(axis, true, "astGetAxis");
        return component(internal).getAxis(componentAxis(internal));
    }

    /** Replaces the Axis object in the component Frame holding the axis (C's {@code SetAxis}, cmpframe.c). */
    @Override
    void setAxis(int axis, Axis ax) {
        int internal = validateAxis(axis, true, "astSetAxis");
        component(internal).setAxis(componentAxis(internal), ax);
    }

    /**
     * The primary Frame holding an axis, found in the component holding it (C's {@code
     * PrimaryFrame}, cmpframe.c).
     */
    @Override
    Frame primaryFrame(int axis, int[] primaryAxis) {
        int internal = validateAxis(axis, true, "astPrimaryFrame");
        return component(internal).primaryFrame(componentAxis(internal), primaryAxis);
    }

    /**
     * Permutes the order of the axes. Element {@code i} of {@code perm} gives the axis, in the
     * current order and numbered from 1, that becomes axis {@code i + 1}. The component Frames are
     * not changed.
     *
     * @param perm the permutation, one element for each axis
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
        permAxes0(perm0);
    }

    /** C's {@code PermAxes} (cmpframe.c), given the permutation numbered from 0. */
    private void permAxes0(int[] perm0) {
        checkPerm(perm0, "astPermAxes");
        int naxes = getNaxes();
        int[] old = perm.clone();
        for (int axis = 0; axis < naxes; axis++) {
            perm[axis] = old[perm0[axis]];
        }
    }

    /** A CmpFrame is a unit Frame when both components are (C's {@code IsUnitFrame}, cmpframe.c). */
    @Override
    boolean isUnitFrame() {
        return frame1.isUnitFrame() && frame2.isUnitFrame();
    }

    // ------------------------------------------------------------------
    // Formatting and reading values
    // ------------------------------------------------------------------

    /**
     * Lends the CmpFrame's Digits to a component that has none set, as C's per-axis methods do
     * while the component works on the CmpFrame's behalf.
     *
     * @return whether Digits was lent, to be given back with {@link #returnDigits}
     */
    private boolean lendDigits(Frame frame) {
        if (frame.testDigits()) {
            return false;
        }
        frame.setDigits(getDigits());
        return true;
    }

    private static void returnDigits(Frame frame, boolean lent) {
        if (lent) {
            frame.clearDigits();
        }
    }

    /**
     * Formats a coordinate value for an axis, as the component Frame holding the axis formats it,
     * with the CmpFrame's Digits if the component has none set.
     *
     * @param axis the axis number, from 1
     * @param value the value
     * @return the formatted value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String format(int axis, double value) {
        int internal = validateAxis(axis, true, "astFormat");
        Frame frame = component(internal);
        boolean lent = lendDigits(frame);
        try {
            return frame.format(componentAxis(internal), value);
        } finally {
            returnDigits(frame, lent);
        }
    }

    /** Reads a value for an axis as the component Frame holding it does (C's {@code Unformat}, cmpframe.c). */
    @Override
    int unformat(int axis, String string, double[] value) {
        int internal = validateAxis(axis, true, "astUnformat");
        Frame frame = component(internal);
        boolean lent = lendDigits(frame);
        double[] coord = new double[1];
        int nc;
        try {
            nc = frame.unformat(componentAxis(internal), string, coord);
        } finally {
            returnDigits(frame, lent);
        }
        if (nc != 0) {
            value[0] = coord[0];
        }
        return nc;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * Finds the angle at point B between the lines from B to A and from B to C. A CmpFrame whose
     * axes are all those of plain Frames measures it as a plain Frame does; otherwise each
     * component measures the angle between its parts of the points, and the result is that of the
     * component in which it is defined, or the bad value if it is defined in both or neither.
     *
     * @param a the first point
     * @param b the second point, at which the angle is measured
     * @param c the third point
     * @return the angle in radians, or the bad value
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    @Override
    public double angle(double[] a, double[] b, double[] c) {
        checkPoint("astAngle", a);
        checkPoint("astAngle", b);
        checkPoint("astAngle", c);
        int naxes = getNaxes();
        boolean iscart = true;
        int[] paxis = new int[1];
        for (int axis = 1; axis <= naxes; axis++) {
            if (!"Frame".equals(primaryFrame(axis, paxis).astClass())) {
                iscart = false;
                break;
            }
        }
        if (iscart) {
            return super.angle(a, b, c);
        }
        double[] pa = toInternal(a);
        double[] pb = toInternal(b);
        double[] pc = toInternal(c);
        double ang1 = frame1.angle(pa, pb, pc);
        double ang2 = frame2.angle(second(pa), second(pb), second(pc));
        if (ang1 == AST__BAD) {
            return ang2;
        } else if (ang2 == AST__BAD) {
            return ang1;
        }
        return AST__BAD;
    }

    /**
     * Finds the distance between two points: the components' distances between their parts of the
     * points, combined in quadrature.
     *
     * @param point1 the first point
     * @param point2 the second point
     * @return the distance, or the bad value if either point has a bad coordinate
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    @Override
    public double distance(double[] point1, double[] point2) {
        checkPoint("astDistance", point1);
        checkPoint("astDistance", point2);
        int naxes = getNaxes();
        for (int axis = 0; axis < naxes; axis++) {
            if (isBad(point1[axis]) || isBad(point2[axis])) {
                return AST__BAD;
            }
        }
        double[] p1 = toInternal(point1);
        double[] p2 = toInternal(point2);
        double dist1 = frame1.distance(p1, p2);
        double dist2 = frame2.distance(second(p1), second(p2));
        if (dist1 != AST__BAD && dist2 != AST__BAD) {
            return Math.sqrt(dist1 * dist1 + dist2 * dist2);
        }
        return AST__BAD;
    }

    /**
     * Finds the point a given distance along the geodesic from one point towards another: each
     * component moves its part of the first point towards its part of the second by its share of
     * the offset, in proportion to its share of the distance between the points.
     *
     * @param point1 the start point
     * @param point2 the point towards which to move
     * @param offset the distance to move
     * @return the point reached, all bad if a coordinate is bad or the points coincide and the
     *     offset is not zero
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    @Override
    public double[] offset(double[] point1, double[] point2, double offset) {
        checkPoint("astOffset", point1);
        checkPoint("astOffset", point2);
        int naxes = getNaxes();
        double[] point3 = new double[naxes];
        boolean bad = false;
        for (int axis = 0; axis < naxes; axis++) {
            if (isBad(point1[axis]) || isBad(point2[axis])) {
                bad = true;
                break;
            }
        }
        double[] p1 = null;
        double[] p2 = null;
        double dist1 = 0.0;
        double dist2 = 0.0;
        if (!bad) {
            p1 = toInternal(point1);
            p2 = toInternal(point2);
            dist1 = frame1.distance(p1, p2);
            dist2 = frame2.distance(second(p1), second(p2));
            bad = dist1 == AST__BAD || dist2 == AST__BAD;
        }
        double offset1 = 0.0;
        double offset2 = 0.0;
        if (!bad) {
            double dist = Math.sqrt(dist1 * dist1 + dist2 * dist2);
            if (dist == 0.0) {
                if (offset != 0.0) {
                    bad = true;
                }
            } else {
                offset1 = offset * dist1 / dist;
                offset2 = offset * dist2 / dist;
            }
        }
        if (!bad) {
            double[] p3a = frame1.offset(p1, p2, offset1);
            double[] p3b = frame2.offset(second(p1), second(p2), offset2);
            int naxes1 = frame1.getNaxes();
            double[] p3 = new double[naxes];
            System.arraycopy(p3a, 0, p3, 0, naxes1);
            System.arraycopy(p3b, 0, p3, naxes1, naxes - naxes1);
            for (int axis = 0; axis < naxes; axis++) {
                point3[axis] = p3[perm[axis]];
                if (point3[axis] == AST__BAD) {
                    bad = true;
                    break;
                }
            }
        }
        if (bad) {
            Arrays.fill(point3, AST__BAD);
        }
        return point3;
    }

    /**
     * Normalises a point: each component normalises its part of it.
     *
     * @param value the point, normalised in place
     * @throws AstException with status {@code AST__DIMIN} if the point has too few coordinates
     */
    @Override
    public void norm(double[] value) {
        checkPoint("astNorm", value);
        int naxes = getNaxes();
        int naxes1 = frame1.getNaxes();
        double[] v = toInternal(value);
        frame1.norm(v);
        double[] v2 = second(v);
        frame2.norm(v2);
        System.arraycopy(v2, 0, v, naxes1, naxes - naxes1);
        for (int axis = 0; axis < naxes; axis++) {
            value[axis] = v[perm[axis]];
        }
    }

    /** Each component normalises its part of the points (C's {@code NormalPoints}, cmpframe.c). */
    @Override
    double[][] normalPoints(double[][] in, int npoint, boolean contig) {
        int naxes1 = frame1.getNaxes();
        int naxesAll = getNaxes();
        if (in.length != naxesAll) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astNormalPoints(" + astClass() + "): Bad number of coordinate values (" + in.length
                            + ") in input PointSet.\nThe " + astClass() + " given requires " + naxesAll
                            + " coordinate value(s) for each input point.");
        }
        double[][] internal = new double[naxesAll][];
        for (int axis = 0; axis < naxesAll; axis++) {
            internal[perm[axis]] = in[axis];
        }
        double[][] out1 = frame1.normalPoints(Arrays.copyOfRange(internal, 0, naxes1), npoint, contig);
        double[][] out2 = frame2.normalPoints(Arrays.copyOfRange(internal, naxes1, naxesAll), npoint, contig);
        double[][] result = new double[naxesAll][];
        for (int axis = 0; axis < naxesAll; axis++) {
            int i = perm[axis];
            result[axis] = i < naxes1 ? out1[i] : out2[i - naxes1];
        }
        return result;
    }

    /**
     * Resolves the vector from point1 to point3 into components parallel and perpendicular to the
     * geodesic from point1 to point2. Each component resolves its part of the vector; the parallel
     * component is the components' parallel parts weighted by their shares of the distance from
     * point1 to point2, and the perpendicular component is the distance from point3 to the point
     * that far from point1 towards point2.
     *
     * @param point1 the start of the basis vector and of the vector to resolve
     * @param point2 the end of the basis vector
     * @param point3 the end of the vector to resolve
     * @param point4 receives the point on the geodesic closest to point3, all bad if the result is
     *     undefined
     * @return the parallel and perpendicular components, bad if undefined
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    @Override
    public double[] resolve(double[] point1, double[] point2, double[] point3, double[] point4) {
        checkPoint("astResolve", point1);
        checkPoint("astResolve", point2);
        checkPoint("astResolve", point3);
        checkPoint("astResolve", point4);
        int naxes = getNaxes();
        double d1 = AST__BAD;
        double d2 = AST__BAD;
        boolean bad = false;
        for (int axis = 0; axis < naxes; axis++) {
            if (isBad(point1[axis]) || isBad(point2[axis]) || isBad(point3[axis])) {
                bad = true;
                break;
            }
        }
        double da = 0.0;
        double db = 0.0;
        double d1a = AST__BAD;
        double d1b = AST__BAD;
        if (!bad) {
            double[] p1 = toInternal(point1);
            double[] p2 = toInternal(point2);
            double[] p3 = toInternal(point3);
            double[] p4a = new double[frame1.getNaxes()];
            double[] p4b = new double[naxes - frame1.getNaxes()];
            d1a = frame1.resolve(p1, p2, p3, p4a)[0];
            d1b = frame2.resolve(second(p1), second(p2), second(p3), p4b)[0];
            da = frame1.distance(p1, p2);
            db = frame2.distance(second(p1), second(p2));
            bad = da == AST__BAD || db == AST__BAD;
            if (d1a == AST__BAD && da == 0.0) {
                d1a = 0.0;
            }
            if (d1b == AST__BAD && db == 0.0) {
                d1b = 0.0;
            }
            bad = bad || d1a == AST__BAD || d1b == AST__BAD;
        }
        if (!bad) {
            double d = Math.sqrt(da * da + db * db);
            if (d == 0.0) {
                bad = true;
            } else {
                d1 = (da * d1a + db * d1b) / d;
                double[] p4 = offset(point1, point2, d1);
                System.arraycopy(p4, 0, point4, 0, naxes);
                d2 = distance(point4, point3);
            }
        }
        if (bad) {
            d1 = AST__BAD;
            d2 = AST__BAD;
            for (int axis = 0; axis < naxes; axis++) {
                point4[axis] = AST__BAD;
            }
        }
        return new double[] {d1, d2};
    }

    /**
     * Resolves many vectors, as {@link #resolve} does for one (C's {@code ResolvePoints},
     * cmpframe.c).
     */
    @Override
    double[][] resolvePoints(double[] point1, double[] point2, double[][] in, int npoint) {
        int naxes1 = frame1.getNaxes();
        int naxesAll = getNaxes();
        if (in.length != naxesAll) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astResolvePoints(" + astClass() + "): Bad number of coordinate values (" + in.length
                            + ") in input PointSet.\nThe " + astClass() + " given requires " + naxesAll
                            + " coordinate value(s) for each input point.");
        }
        checkPoint("astResolvePoints", point1);
        checkPoint("astResolvePoints", point2);
        double[] d1 = new double[npoint];
        double[] d2 = new double[npoint];
        double[][] internal = new double[naxesAll][];
        for (int axis = 0; axis < naxesAll; axis++) {
            internal[perm[axis]] = in[axis];
        }
        double[] p1 = toInternal(point1);
        double[] p2 = toInternal(point2);
        double b1 = frame1.distance(p1, p2);
        double b2 = frame2.distance(second(p1), second(p2));
        if (b1 == AST__BAD || b2 == AST__BAD || (b1 == 0.0 && b2 == 0.0)) {
            Arrays.fill(d1, AST__BAD);
            Arrays.fill(d2, AST__BAD);
            return new double[][] {d1, d2};
        }
        double b = Math.sqrt(b1 * b1 + b2 * b2);
        double[][] out1 = frame1.resolvePoints(p1, p2, Arrays.copyOfRange(internal, 0, naxes1), npoint);
        double[][] out2 =
                frame2.resolvePoints(second(p1), second(p2), Arrays.copyOfRange(internal, naxes1, naxesAll), npoint);
        double[] p3 = new double[naxesAll];
        for (int ipoint = 0; ipoint < npoint; ipoint++) {
            double d11 = out1[0][ipoint];
            double d12 = out2[0][ipoint];
            if (d11 == AST__BAD && b1 == 0.0) {
                d11 = 0.0;
            }
            if (d12 == AST__BAD && b2 == 0.0) {
                d12 = 0.0;
            }
            if (d11 != AST__BAD && d12 != AST__BAD) {
                d1[ipoint] = (b1 * d11 + b2 * d12) / b;
                double[] p4 = offset(point1, point2, d1[ipoint]);
                for (int axis = 0; axis < naxesAll; axis++) {
                    p3[axis] = in[axis][ipoint];
                }
                d2[ipoint] = distance(p4, p3);
            } else {
                d1[ipoint] = AST__BAD;
                d2[ipoint] = AST__BAD;
            }
        }
        return new double[][] {d1, d2};
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * This CmpFrame cast into the class of another (C's {@code Cast}, cmpframe.c): for a CmpFrame
     * class, as {@link Frame#cast} does; for any other, a plain Frame with this CmpFrame's axes and
     * the attributes set on its components overlaid, itself cast into that class.
     */
    @Override
    Frame cast(Frame obj) {
        if (obj instanceof CmpFrame) {
            return super.cast(obj);
        }
        int naxes = getNaxes();
        Frame cfrm = new Frame(naxes);
        for (int i = 1; i <= naxes; i++) {
            cfrm.setAxis(i, getAxis(i));
        }
        overlay(null, cfrm);
        return cfrm.cast(obj);
    }

    /**
     * Overlays the attributes set on the components onto another Frame (C's {@code Overlay},
     * cmpframe.c). If the result is a CmpFrame whose two components each take their axes from a
     * different one of this CmpFrame's components, each of these components is overlaid onto the
     * corresponding component of the result; otherwise each of them is overlaid onto the whole
     * result, with the axes that come from it. The CmpFrame's own Frame attributes are not overlaid.
     *
     * <p>C reads beyond its permutation array for a result axis with no template axis; such an
     * axis here takes no attributes, and prevents the component-by-component overlay.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    void overlay(int[] templateAxes, Frame result) {
        int nc1 = frame1.getNaxes();
        int ntemplate = getNaxes();
        boolean done = false;
        if (result instanceof CmpFrame) {
            CmpFrame res = (CmpFrame) result;
            int nres1 = res.frame1.getNaxes();
            int nres2 = res.frame2.getNaxes();
            int nres = nres1 + nres2;
            int[] rperm = res.getPerm();
            int[] axes1 = new int[nres1];
            int[] axes2 = new int[nres2];
            done = true;
            Frame sub1 = overlayPart(templateAxes, ntemplate, nc1, rperm, nres, 0, nres1, axes1);
            Frame sub2 =
                    sub1 == null ? null : overlayPart(templateAxes, ntemplate, nc1, rperm, nres, nres1, nres2, axes2);
            if (sub1 == null || sub2 == null || sub1 == sub2) {
                done = false;
            }
            if (done) {
                sub1.overlay(axes1, res.frame1);
                sub2.overlay(axes2, res.frame2);
            }
        }
        if (!done) {
            int nres = result.getNaxes();
            int[] axes1 = new int[nres];
            int[] axes2 = new int[nres];
            for (int i = 0; i < nres; i++) {
                int t = templateAxes != null ? templateAxes[i] : i;
                axes1[i] = -1;
                axes2[i] = -1;
                if (t >= 0 && t < ntemplate) {
                    int icmp = perm[t];
                    if (icmp < nc1) {
                        axes1[i] = icmp;
                    } else {
                        axes2[i] = icmp - nc1;
                    }
                }
            }
            frame1.overlay(axes1, result);
            frame2.overlay(axes2, result);
        }
    }

    /**
     * For the {@code n} internal axes of a result CmpFrame starting at {@code first}, finds the
     * template component they all come from and fills {@code axes} with the axis of that component
     * each comes from, numbered from 0 (one loop of C's {@code Overlay}).
     *
     * @return the template component (the second one if {@code n} is zero), or null if the axes
     *     come from both components or one has no template axis
     */
    private Frame overlayPart(
            int[] templateAxes, int ntemplate, int nc1, int[] rperm, int nres, int first, int n, int[] axes) {
        boolean isfirst = false;
        boolean issecond = false;
        for (int i = 0; i < n; i++) {
            int j = 0;
            while (j < nres && rperm[j] != i + first) {
                j++;
            }
            int t = templateAxes != null ? templateAxes[j] : j;
            if (t < 0 || t >= ntemplate) {
                return null;
            }
            int icmp = perm[t];
            if (icmp < nc1) {
                if (issecond) {
                    return null;
                }
                isfirst = true;
                axes[i] = icmp;
            } else {
                if (isfirst) {
                    return null;
                }
                issecond = true;
                axes[i] = icmp - nc1;
            }
        }
        return isfirst ? frame1 : frame2;
    }

    /**
     * Matches this CmpFrame, a template, against a target (C's {@code Match}, cmpframe.c). The
     * target's axes are divided between the two components in every possible way, starting with
     * the most axes for the first component, and each component is matched against a Frame of its
     * share; the first division that matches both, keeping the axis order unless Permute is set,
     * gives the result. If none does, each component in turn is matched against the whole target.
     */
    @Override
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        int maxax;
        int maxax1;
        int maxax2;
        if (testMaxAxes()) {
            maxax = getMaxAxes();
            maxax1 = ANY_MAX_AXES;
            maxax2 = ANY_MAX_AXES;
        } else {
            maxax1 = frame1.getMaxAxes();
            maxax2 = frame2.getMaxAxes();
            maxax = maxax1 + maxax2;
        }
        int minax;
        int minax1;
        int minax2;
        if (testMinAxes()) {
            minax = getMinAxes();
            minax1 = 1;
            minax2 = 1;
        } else {
            minax1 = frame1.getMinAxes();
            minax2 = frame2.getMinAxes();
            minax = minax1 + minax2;
        }
        int naxes = target.getNaxes();
        boolean match = naxes >= minax && naxes <= maxax;
        if (naxes == 0 || maxax1 == 0 || maxax2 == 0) {
            match = false;
        }
        if (match && testDomain()) {
            match = getDomain().equals(target.getDomain());
        }
        int naxesMin = 0;
        int naxesMax = 0;
        if (match) {
            naxesMin = Math.max(minax1, naxes - maxax2);
            naxesMax = Math.min(maxax1, naxes - minax2);
            if (naxesMin > naxesMax) {
                match = false;
            }
        }
        FrameFinder.MatchResult result = null;
        if (match) {
            int[] axes1 = new int[naxes];
            int[] axes2 = new int[naxes];
            boolean[] used = new boolean[naxes];
            boolean permute = getPermute();
            for (int naxes1 = naxesMax; naxes1 >= naxesMin && result == null; naxes1--) {
                int naxes2 = naxes - naxes1;
                for (int axis = 0; axis < naxes1; axis++) {
                    axes1[axis] = axis;
                }
                while (true) {
                    Arrays.fill(used, false);
                    for (int axis = 0; axis < naxes1; axis++) {
                        used[axes1[axis]] = true;
                    }
                    int axis2 = 0;
                    for (int axis = 0; axis < naxes; axis++) {
                        if (!used[axis]) {
                            axes2[axis2++] = axis;
                        }
                    }
                    FrameFinder.MatchResult m = partMatch(this, target, matchSub, naxes1, axes1, naxes2, axes2);
                    if (m != null && !permute && !inOrder(m)) {
                        m = null;
                    }
                    if (m != null) {
                        result = m;
                        break;
                    }
                    if (!genAxisSelection(naxes, naxes1, axes1)) {
                        break;
                    }
                }
            }
        }
        if (result == null) {
            result = componentMatch(this, target, matchSub, 0);
        }
        if (result == null) {
            result = componentMatch(this, target, matchSub, 1);
        }
        return result;
    }

    /** Whether the template and target axes a match associates each increase along the result axes. */
    private static boolean inOrder(FrameFinder.MatchResult m) {
        int lastTemplate = -1;
        int lastTarget = -1;
        int resultNaxes = m.result.getNaxes();
        for (int axis = 0; axis < resultNaxes; axis++) {
            int t = m.templateAxes[axis];
            if (t != -1) {
                if (t <= lastTemplate) {
                    return false;
                }
                lastTemplate = t;
            }
            int g = m.targetAxes[axis];
            if (g != -1) {
                if (g <= lastTarget) {
                    return false;
                }
                lastTarget = g;
            }
        }
        return true;
    }

    /**
     * Matches one component of a CmpFrame template against the whole target (C's {@code
     * ComponentMatch}, cmpframe.c), with the CmpFrame's PreserveAxes. With PreserveAxes set the
     * result is the component's match; otherwise it is a CmpFrame of the component's result and a
     * copy of the other component, in the template's order, the other component's axes having no
     * target axis.
     *
     * @param template the CmpFrame template
     * @param target the target
     * @param matchSub whether a component of a subclass may be cast into the target's class
     * @param icomp 0 to match the first component, 1 the second
     * @return the match, or null if there is none
     */
    static FrameFinder.MatchResult componentMatch(CmpFrame template, Frame target, boolean matchSub, int icomp) {
        Frame ctemplate = icomp != 0 ? template.frame2 : template.frame1;
        int praxo = ctemplate.testPreserveAxes() ? (ctemplate.getPreserveAxes() ? 1 : 0) : -1;
        boolean prax = template.getPreserveAxes();
        ctemplate.setPreserveAxes(prax);
        FrameFinder.MatchResult m;
        try {
            m = FrameFinder.match(ctemplate, target, matchSub);
        } finally {
            if (praxo == -1) {
                ctemplate.clearPreserveAxes();
            } else {
                ctemplate.setPreserveAxes(praxo != 0);
            }
        }
        if (m == null) {
            return null;
        }
        int nax1 = template.frame1.getNaxes();
        int nax2 = template.frame2.getNaxes();
        int templateNaxes = nax1 + nax2;
        int[] p = template.getPerm();
        int[] operm = new int[templateNaxes];
        for (int axis = 0; axis < templateNaxes; axis++) {
            operm[p[axis]] = axis;
        }
        int[] ftemplateAxes = m.templateAxes.clone();
        if (prax) {
            int resultNaxes = m.result.getNaxes();
            if (icomp != 0) {
                for (int axis = 0; axis < resultNaxes; axis++) {
                    ftemplateAxes[axis] += nax1;
                }
            }
            int[] templateAxes = new int[templateNaxes];
            for (int axis = 0; axis < resultNaxes; axis++) {
                templateAxes[axis] = operm[ftemplateAxes[axis]];
            }
            return new FrameFinder.MatchResult(templateAxes, m.targetAxes, m.map, m.result);
        }
        int naxr = m.result.getNaxes();
        int resultNaxes = (icomp != 0 ? nax1 : nax2) + naxr;
        Frame result;
        int[] inperm = null;
        int[] outperm = null;
        if (icomp != 0) {
            result = transientCmpFrame((Frame) template.frame1.copy(), m.result);
            inperm = new int[naxr];
            outperm = new int[resultNaxes];
            for (int axis = 0; axis < nax1; axis++) {
                outperm[axis] = -1;
            }
            for (int axis = 0; axis < naxr; axis++) {
                outperm[axis + nax1] = axis;
                inperm[axis] = axis + nax1;
            }
        } else {
            result = transientCmpFrame(m.result, (Frame) template.frame2.copy());
        }
        PermMap pm = PermMap.fromInternal(naxr, inperm, resultNaxes, outperm, null);
        Mapping map = CmpMap.transientCmpMap(m.map, pm, true);
        int[] templateAxes = new int[resultNaxes];
        int[] targetAxes = new int[resultNaxes];
        if (icomp != 0) {
            System.arraycopy(operm, 0, templateAxes, 0, nax1);
            for (int axis = nax1; axis < resultNaxes; axis++) {
                templateAxes[axis] = operm[nax1 + ftemplateAxes[axis - nax1]];
            }
        } else {
            for (int axis = 0; axis < nax1; axis++) {
                templateAxes[axis] = operm[ftemplateAxes[axis]];
            }
            System.arraycopy(operm, nax1, templateAxes, nax1, resultNaxes - nax1);
        }
        System.arraycopy(m.targetAxes, 0, targetAxes, 0, naxr);
        Arrays.fill(targetAxes, naxr, resultNaxes, -1);
        return new FrameFinder.MatchResult(templateAxes, targetAxes, map, result);
    }

    /**
     * Matches the components of a CmpFrame template against two parts of a target (C's {@code
     * PartMatch}, cmpframe.c): the first component against a Frame of the target axes {@code
     * axes1}, the second against one of {@code axes2}, each with the template's MatchEnd and
     * PreserveAxes where it has none of its own and with Permute set. The result is a CmpFrame of
     * the two results, its axes put into the order of the template's axes (or the target's, with
     * PreserveAxes set), then given the template's number of axes (or the target's).
     *
     * @param template the CmpFrame template
     * @param target the target
     * @param matchSub whether a component of a subclass may be cast into the target's class
     * @param naxes1 the number of target axes for the first component
     * @param axes1 the target axes for the first component, numbered from 0
     * @param naxes2 the number of target axes for the second component
     * @param axes2 the target axes for the second component, numbered from 0
     * @return the match, or null if either component does not match
     */
    static FrameFinder.MatchResult partMatch(
            CmpFrame template, Frame target, boolean matchSub, int naxes1, int[] axes1, int naxes2, int[] axes2) {
        Mapping[] junk = new Mapping[1];
        Frame[] sub = new Frame[1];
        target.subFrame(null, naxes1, axes1, null, junk, sub);
        Frame frame1 = sub[0];
        target.subFrame(null, naxes2, axes2, null, junk, sub);
        Frame frame2 = sub[0];
        FrameFinder.MatchResult m1 = componentPartMatch(template, template.frame1, frame1, matchSub);
        FrameFinder.MatchResult m2 = componentPartMatch(template, template.frame2, frame2, matchSub);
        if (m1 == null || m2 == null) {
            return null;
        }
        int targetNaxes = target.getNaxes();
        int resultNaxes1 = m1.result.getNaxes();
        int resultNaxes2 = m2.result.getNaxes();
        int templateNaxes1 = template.frame1.getNaxes();
        int templateNaxes = template.getNaxes();
        boolean matchEnd1 = template.frame1.getMatchEnd();
        boolean matchEnd2 = template.frame2.getMatchEnd();
        boolean matchEnd = template.getMatchEnd();
        int[] p = template.getPerm();
        int[] invperm = new int[templateNaxes];
        for (int templateAxis = 0; templateAxis < templateNaxes; templateAxis++) {
            invperm[p[templateAxis]] = templateAxis;
        }
        int resultNaxes = resultNaxes1 + resultNaxes2;
        int[] templateAssoc = new int[resultNaxes];
        int[] targetAssoc = new int[resultNaxes];
        for (int resultAxis = 0; resultAxis < resultNaxes; resultAxis++) {
            boolean first = resultAxis < resultNaxes1;
            int partResultAxis = first ? resultAxis : resultAxis - resultNaxes1;
            int partTemplateAxis = first ? m1.templateAxes[partResultAxis] : m2.templateAxes[partResultAxis];
            int partTargetAxis = first ? m1.targetAxes[partResultAxis] : m2.targetAxes[partResultAxis];
            templateAssoc[resultAxis] =
                    partTemplateAxis != -1 ? invperm[first ? partTemplateAxis : partTemplateAxis + templateNaxes1] : -1;
            targetAssoc[resultAxis] =
                    partTargetAxis != -1 ? (first ? axes1[partTargetAxis] : axes2[partTargetAxis]) : -1;
        }

        CmpFrame cmp = transientCmpFrame(m1.result, m2.result);
        boolean preserveAxes = template.getPreserveAxes();
        int refNaxes = preserveAxes ? targetNaxes : templateNaxes;
        int[] resultOrder = (preserveAxes ? targetAssoc : templateAssoc).clone();
        addExtraAxes(resultNaxes, resultOrder, 0, resultNaxes1 - 1, matchEnd1);
        addExtraAxes(resultNaxes, resultOrder, resultNaxes1, resultNaxes - 1, matchEnd2);
        renumberAxes(resultNaxes, resultOrder);
        int[] resultPerm = new int[resultNaxes];
        for (int resultAxis = 0; resultAxis < resultNaxes; resultAxis++) {
            resultPerm[resultOrder[resultAxis]] = resultAxis;
        }
        cmp.permAxes0(resultPerm);
        Frame result = cmp;
        if (refNaxes != resultNaxes) {
            int[] pick = new int[refNaxes];
            for (int outputAxis = 0; outputAxis < refNaxes; outputAxis++) {
                int fullAxis = matchEnd ? outputAxis + (resultNaxes - refNaxes) : outputAxis;
                pick[outputAxis] = fullAxis >= 0 && fullAxis < resultNaxes ? fullAxis : -1;
            }
            cmp.validateAxisSelection(refNaxes, pick, "astPickAxes");
            Mapping[] pickMap = new Mapping[1];
            Frame[] picked = new Frame[1];
            cmp.subFrame(null, refNaxes, pick, null, pickMap, picked);
            result = picked[0];
        }
        int[] outTargetAxes = new int[refNaxes];
        int[] outTemplateAxes = new int[refNaxes];
        for (int outputAxis = 0; outputAxis < refNaxes; outputAxis++) {
            int fullAxis = matchEnd ? outputAxis + (resultNaxes - refNaxes) : outputAxis;
            if (fullAxis >= 0 && fullAxis < resultNaxes) {
                int resultAxis = resultPerm[fullAxis];
                outTemplateAxes[outputAxis] = templateAssoc[resultAxis];
                outTargetAxes[outputAxis] = targetAssoc[resultAxis];
            } else {
                outTemplateAxes[outputAxis] = -1;
                outTargetAxes[outputAxis] = -1;
            }
        }

        Mapping map = CmpMap.transientCmpMap(m1.map, m2.map, false);
        int[] inperm = new int[targetNaxes];
        int[] outperm = new int[targetNaxes];
        for (int targetAxis = 0; targetAxis < targetNaxes; targetAxis++) {
            boolean first = targetAxis < naxes1;
            int partTargetAxis = first ? targetAxis : targetAxis - naxes1;
            outperm[targetAxis] = first ? axes1[partTargetAxis] : axes2[partTargetAxis];
            inperm[outperm[targetAxis]] = targetAxis;
        }
        if (goodPerm(targetNaxes, inperm, targetNaxes, outperm)) {
            PermMap permmap = PermMap.fromInternal(targetNaxes, inperm, targetNaxes, outperm, null);
            map = CmpMap.transientCmpMap(permmap, map, true);
        }
        inperm = new int[resultNaxes];
        outperm = new int[refNaxes];
        Arrays.fill(inperm, -1);
        for (int outputAxis = 0; outputAxis < refNaxes; outputAxis++) {
            int fullAxis = matchEnd ? outputAxis + (resultNaxes - refNaxes) : outputAxis;
            if (fullAxis >= 0 && fullAxis < resultNaxes) {
                outperm[outputAxis] = resultPerm[fullAxis];
                inperm[outperm[outputAxis]] = outputAxis;
            } else {
                outperm[outputAxis] = -1;
            }
        }
        // C tests these arrays as if each held targetNaxes elements; an element C would read from
        // beyond the end of one counts here as not the identity.
        if (goodPermPrefix(targetNaxes, inperm, outperm)) {
            PermMap permmap = PermMap.fromInternal(resultNaxes, inperm, refNaxes, outperm, null);
            map = CmpMap.transientCmpMap(map, permmap, true);
        }
        return new FrameFinder.MatchResult(outTemplateAxes, outTargetAxes, map, result);
    }

    /**
     * Matches one component of a CmpFrame template against a Frame of part of the target, as C's
     * {@code PartMatch} does: with the template's MatchEnd and PreserveAxes where the component
     * has none of its own, and with Permute set, these being put back afterwards.
     */
    private static FrameFinder.MatchResult componentPartMatch(
            CmpFrame template, Frame component, Frame part, boolean matchSub) {
        boolean matchEndSet = component.testMatchEnd();
        if (!matchEndSet) {
            component.setMatchEnd(template.getMatchEnd());
        }
        boolean preserveAxesSet = component.testPreserveAxes();
        if (!preserveAxesSet) {
            component.setPreserveAxes(template.getPreserveAxes());
        }
        boolean permuteSet = component.testPermute();
        boolean permuteValue = permuteSet && component.getPermute();
        component.setPermute(true);
        try {
            return FrameFinder.match(component, part, matchSub);
        } finally {
            if (!matchEndSet) {
                component.clearMatchEnd();
            }
            if (!preserveAxesSet) {
                component.clearPreserveAxes();
            }
            if (permuteSet) {
                component.setPermute(permuteValue);
            } else {
                component.clearPermute();
            }
        }
    }

    /**
     * Selects axes from this CmpFrame, the target, into a new Frame (C's {@code SubFrame},
     * cmpframe.c). The selected axes are divided between the components, an axis that is not one
     * of the CmpFrame's going with the component of the nearest selected axis before it (after it,
     * with MatchEnd set). If they all come from one component, that component selects them;
     * otherwise each component selects its share and the result is a CmpFrame of the two, with its
     * axes in the order selected, which has the target's Domain and Title, where set, if every axis
     * was selected.
     */
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        map[0] = null;
        result[0] = null;
        int naxes = getNaxes();
        int naxes1 = frame1.getNaxes();
        int naxes2 = frame2.getNaxes();
        int rnax = Math.max(resultNaxes, 0);
        boolean following = getMatchEnd();
        int[] frameChoice = new int[rnax];
        partitionSelection(rnax, targetAxes, perm, naxes1, naxes2, frameChoice, following);
        int[] targetAxes1 = new int[rnax];
        int[] targetAxes2 = new int[rnax];
        int[] templateAxes1 = template != null ? new int[rnax] : null;
        int[] templateAxes2 = template != null ? new int[rnax] : null;
        int n1 = 0;
        int n2 = 0;
        for (int resultAxis = 0; resultAxis < rnax; resultAxis++) {
            int targetAxis = targetAxes[resultAxis];
            int axisP = targetAxis >= 0 && targetAxis < naxes ? perm[targetAxis] : -1;
            int templateAxis = templateAxes != null ? templateAxes[resultAxis] : resultAxis;
            if (frameChoice[resultAxis] == 1) {
                targetAxes1[n1] = axisP;
                if (template != null) {
                    templateAxes1[n1] = templateAxis;
                }
                n1++;
            } else {
                targetAxes2[n2] = axisP == -1 ? -1 : axisP - naxes1;
                if (template != null) {
                    templateAxes2[n2] = templateAxis;
                }
                n2++;
            }
        }
        if (n1 != 0 && n2 == 0) {
            Mapping[] subMap = new Mapping[1];
            boolean match = frame1.subFrame(template, n1, targetAxes1, templateAxes1, subMap, result);
            if (match) {
                map[0] = prefixMap(naxes, naxes1, true, subMap[0]);
            }
            return match;
        } else if (n2 != 0 && n1 == 0) {
            Mapping[] subMap = new Mapping[1];
            boolean match = frame2.subFrame(template, n2, targetAxes2, templateAxes2, subMap, result);
            if (match) {
                map[0] = prefixMap(naxes, naxes2, false, subMap[0]);
            }
            return match;
        }
        Mapping[] subMap1 = new Mapping[1];
        Mapping[] subMap2 = new Mapping[1];
        Frame[] subResult1 = new Frame[1];
        Frame[] subResult2 = new Frame[1];
        boolean match = frame1.subFrame(template, n1, targetAxes1, templateAxes1, subMap1, subResult1);
        if (match) {
            match = frame2.subFrame(template, n2, targetAxes2, templateAxes2, subMap2, subResult2);
        }
        if (match) {
            int[] inpermPref = new int[naxes];
            int[] outpermPref = new int[naxes];
            for (int targetAxis = 0; targetAxis < naxes; targetAxis++) {
                int axisP = perm[targetAxis];
                inpermPref[targetAxis] = axisP;
                outpermPref[axisP] = targetAxis;
            }
            int[] inpermSuff = new int[rnax];
            int[] outpermSuff = new int[rnax];
            int i1 = 0;
            int i2 = 0;
            for (int resultAxis = 0; resultAxis < rnax; resultAxis++) {
                if (frameChoice[resultAxis] == 1) {
                    inpermSuff[i1] = resultAxis;
                    outpermSuff[resultAxis] = i1;
                    i1++;
                } else {
                    inpermSuff[n1 + i2] = resultAxis;
                    outpermSuff[resultAxis] = n1 + i2;
                    i2++;
                }
            }
            Mapping subMap = CmpMap.transientCmpMap(subMap1[0], subMap2[0], false);
            PermMap permmapPref = PermMap.fromInternal(naxes, inpermPref, naxes, outpermPref, null);
            PermMap permmapSuff = PermMap.fromInternal(rnax, inpermSuff, rnax, outpermSuff, null);
            Mapping tmpMap = CmpMap.transientCmpMap(permmapPref, subMap, true);
            map[0] = CmpMap.transientCmpMap(tmpMap, permmapSuff, true);
            CmpFrame res = transientCmpFrame(subResult1[0], subResult2[0]);
            res.permAxes0(outpermSuff);
            if (rnax == naxes) {
                if (testDomain()) {
                    res.setDomain(getDomain());
                }
                if (testTitle()) {
                    res.setTitle(getTitle());
                }
            }
            result[0] = res;
        }
        return match;
    }

    /**
     * The Mapping from this CmpFrame's coordinates to those of a selection from one component: a
     * PermMap picking that component's axes, in internal order, followed by {@code subMap} (C's
     * {@code SubFrame}, cmpframe.c).
     *
     * @param naxes the number of axes of the CmpFrame
     * @param ncomp the number of axes of the component
     * @param first whether the component is the first
     * @param subMap the Mapping the component's selection gives
     */
    private Mapping prefixMap(int naxes, int ncomp, boolean first, Mapping subMap) {
        int naxes1 = frame1.getNaxes();
        int[] inpermPref = new int[naxes];
        int[] outpermPref = new int[ncomp];
        for (int targetAxis = 0; targetAxis < naxes; targetAxis++) {
            int axisP = perm[targetAxis];
            boolean inComp = first ? axisP < naxes1 : axisP >= naxes1;
            if (inComp) {
                int a = first ? axisP : axisP - naxes1;
                inpermPref[targetAxis] = a;
                outpermPref[a] = targetAxis;
            } else {
                inpermPref[targetAxis] = -1;
            }
        }
        PermMap permmapPref = PermMap.fromInternal(naxes, inpermPref, ncomp, outpermPref, null);
        return CmpMap.transientCmpMap(permmapPref, subMap, true);
    }

    /**
     * The axes of {@code frm1} that correspond to this CmpFrame's (C's {@code MatchAxesX},
     * cmpframe.c): each component finds those for its own axes, which are then put into the
     * CmpFrame's axis order.
     */
    @Override
    int[] matchAxesX(Frame frm1) {
        int nax1 = frame1.getNaxes();
        int naxesAll = nax1 + frame2.getNaxes();
        int[] work = new int[naxesAll];
        int[] w1 = frm1.matchAxes(frame1);
        int[] w2 = frm1.matchAxes(frame2);
        System.arraycopy(w1, 0, work, 0, nax1);
        System.arraycopy(w2, 0, work, nax1, naxesAll - nax1);
        int[] axes = new int[naxesAll];
        for (int i = 0; i < naxesAll; i++) {
            axes[i] = work[perm[i]];
        }
        return axes;
    }

    // ------------------------------------------------------------------
    // Axis selection helpers (cmpframe.c)
    // ------------------------------------------------------------------

    /**
     * Advances a selection of {@code nselect} distinct axes, numbered from 0 and in increasing
     * order, to the next one from {@code naxes} axes (C's {@code GenAxisSelection}). Starting from
     * {@code 0, 1, ..., nselect - 1}, repeated calls give every selection.
     *
     * @return whether {@code axes} holds a new selection; false once every one has been given
     */
    static boolean genAxisSelection(int naxes, int nselect, int[] axes) {
        int iselect = 0;
        while (iselect < nselect) {
            if (iselect == nselect - 1 || axes[iselect + 1] > axes[iselect] + 1) {
                axes[iselect]++;
                for (int i = 0; i < iselect; i++) {
                    axes[i] = i;
                }
                break;
            } else if (++iselect >= nselect) {
                break;
            }
        }
        return nselect > 0 && axes[nselect - 1] < naxes;
    }

    /**
     * Replaces the {@code -1} elements among {@code axes[i1..i2]} with new axis numbers, those
     * after (or, if {@code following}, before) the nearest axis given in that range, or in the
     * rest of the array, renumbering the other axes to make room (C's {@code AddExtraAxes}).
     */
    static void addExtraAxes(int naxes, int[] axes, int i1, int i2, boolean following) {
        int extra = 0;
        int start = following ? i2 : i1;
        int end = following ? -1 : naxes;
        int inc = following ? -1 : 1;
        boolean found = false;
        for (int i = start; i != end; i += inc) {
            if (axes[i] != -1) {
                found = true;
                extra = axes[i] + (following ? 1 : 0);
                break;
            }
        }
        if (!found) {
            start = !following ? i2 : i1;
            end = !following ? -1 : naxes;
            inc = !following ? -1 : 1;
            for (int i = start; i != end; i += inc) {
                if (axes[i] != -1) {
                    extra = axes[i] + (!following ? 1 : 0);
                    break;
                }
            }
        }
        start = following ? i2 : i1;
        end = following ? i1 - 1 : i2 + 1;
        inc = following ? -1 : 1;
        for (int i = start; i != end; i += inc) {
            if (axes[i] == -1) {
                for (int j = 0; j < naxes; j++) {
                    if (axes[j] != -1 && axes[j] >= extra) {
                        axes[j]++;
                    }
                }
                axes[i] = extra;
                extra += following ? 0 : 1;
            } else {
                extra = axes[i] + (following ? 0 : 1);
            }
        }
    }

    /**
     * Replaces the axis numbers in {@code axes} with their ranks, so that they run from 0 to
     * {@code naxes - 1} in the same order (C's {@code RenumberAxes}, which sorts with {@code
     * qsort}; the numbers are distinct, so the sort's stability does not matter).
     */
    static void renumberAxes(int naxes, int[] axes) {
        Integer[] work = new Integer[naxes];
        for (int i = 0; i < naxes; i++) {
            work[i] = i;
        }
        Arrays.sort(work, (a, b) -> Integer.compare(axes[a], axes[b]));
        for (int i = 0; i < naxes; i++) {
            axes[work[i]] = i;
        }
    }

    /**
     * Whether a pair of permutation arrays, numbered from 0, would make a PermMap that is not a
     * UnitMap (C's {@code GoodPerm}): the numbers of coordinates differ, or an element is not its
     * own index.
     */
    static boolean goodPerm(int ncoordIn, int[] inperm, int ncoordOut, int[] outperm) {
        boolean result = ncoordIn != ncoordOut;
        if (!result) {
            for (int axis = 0; axis < ncoordIn; axis++) {
                if (inperm[axis] != axis) {
                    result = true;
                    break;
                }
            }
        }
        if (!result) {
            for (int axis = 0; axis < ncoordOut; axis++) {
                if (outperm[axis] != axis) {
                    result = true;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * {@link #goodPerm} applied as C's {@code PartMatch} applies it, to the first {@code n}
     * elements of arrays that may hold fewer: an element beyond the end of an array counts as not
     * its own index.
     */
    private static boolean goodPermPrefix(int n, int[] inperm, int[] outperm) {
        for (int axis = 0; axis < n; axis++) {
            if (axis >= inperm.length || inperm[axis] != axis) {
                return true;
            }
        }
        for (int axis = 0; axis < n; axis++) {
            if (axis >= outperm.length || outperm[axis] != axis) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records in {@code iframe} which component, 1 or 2, each selected axis comes from (C's
     * {@code PartitionSelection}). A selection that is not an axis goes with the nearest selected
     * axis before it, or after it if {@code following}, or failing that the first component (the
     * second if {@code following}).
     *
     * @param nselect the number of selected axes
     * @param select the selected axes, numbered from 0
     * @param perm the CmpFrame's permutation
     * @param naxes1 the number of axes of the first component
     * @param naxes2 the number of axes of the second component
     * @param iframe receives the component of each selected axis
     * @param following whether a selection that is not an axis goes with the one after it
     */
    static void partitionSelection(
            int nselect, int[] select, int[] perm, int naxes1, int naxes2, int[] iframe, boolean following) {
        int naxes = naxes1 + naxes2;
        for (int iselect = 0; iselect < nselect; iselect++) {
            if (select[iselect] >= 0 && select[iselect] < naxes) {
                iframe[iselect] = perm[select[iselect]] >= naxes1 ? 2 : 1;
            }
        }
        int start = following ? nselect - 1 : 0;
        int end = following ? -1 : nselect;
        int inc = following ? -1 : 1;
        int ifr = following ? 2 : 1;
        for (int iselect = start; iselect != end; iselect += inc) {
            if (select[iselect] >= 0 && select[iselect] < naxes) {
                ifr = iframe[iselect];
                break;
            }
        }
        for (int iselect = start; iselect != end; iselect += inc) {
            if (select[iselect] >= 0 && select[iselect] < naxes) {
                ifr = iframe[iselect];
            } else {
                iframe[iselect] = ifr;
            }
        }
    }

    // ------------------------------------------------------------------
    // Frame attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Title: the one set, or by default {@code n-d compound coordinate system}.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        if (testTitle()) {
            return super.getTitle();
        }
        return getNaxes() + "-d compound coordinate system";
    }

    /**
     * Gets the Domain: the one set, or by default the components' Domains joined by a hyphen, or
     * {@code CMP} if both are empty.
     *
     * @return the Domain
     */
    @Override
    public String getDomain() {
        if (testDomain()) {
            return super.getDomain();
        }
        String dom1 = frame1.getDomain();
        String dom2 = frame2.getDomain();
        if (!dom1.isEmpty() || !dom2.isEmpty()) {
            return dom1 + "-" + dom2;
        }
        return "CMP";
    }

    /**
     * Gets the MaxAxes: the one set, or by default 1000000.
     *
     * @return the MaxAxes
     */
    @Override
    public int getMaxAxes() {
        return testMaxAxes() ? super.getMaxAxes() : 1000000;
    }

    /**
     * Gets the MinAxes: the one set, or by default 0.
     *
     * @return the MinAxes
     */
    @Override
    public int getMinAxes() {
        return testMinAxes() ? super.getMinAxes() : 0;
    }

    /** An unset UseDefs is the first component's own Object-class value (C's {@code GetUseDefs}, cmpframe.c). */
    @Override
    boolean getUseDefs() {
        if (testUseDefs()) {
            return super.getUseDefs();
        }
        return frame1.objectUseDefs();
    }

    @Override
    int systemValue() {
        return testSystem() ? super.systemValue() : AST__COMP;
    }

    @Override
    int alignSystemValue() {
        return testAlignSystem() ? super.alignSystemValue() : AST__COMP;
    }

    /** Clears the AlignSystem of the CmpFrame and of both components (C's {@code ClearAlignSystem}). */
    @Override
    void clearAlignSystem() {
        super.clearAlignSystem();
        frame1.clearAlignSystem();
        frame2.clearAlignSystem();
    }

    @Override
    int systemCode(String name) {
        return chrMatch("Compound", name) ? AST__COMP : AST__BADSYSTEM;
    }

    @Override
    String systemString(int code) {
        return code == AST__COMP ? "Compound" : null;
    }

    @Override
    int validateSystem(int code, String method) {
        if (code != AST__COMP) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Bad value (" + code + ") given for the System or AlignSystem"
                            + " attribute of a " + astClass() + ".");
        }
        return code;
    }

    /**
     * Gets the ActiveUnit flag: the one set, or by default true if either component uses active
     * units.
     *
     * @return the ActiveUnit flag
     */
    @Override
    public boolean getActiveUnit() {
        if (testActiveUnit()) {
            return super.getActiveUnit();
        }
        return frame1.getActiveUnit() || frame2.getActiveUnit();
    }

    /**
     * Sets the ActiveUnit flag of the CmpFrame and of both components.
     *
     * @param value the ActiveUnit flag
     */
    @Override
    public void setActiveUnit(boolean value) {
        super.setActiveUnit(value);
        frame1.setActiveUnit(value);
        frame2.setActiveUnit(value);
    }

    /** Sets the flags of the CmpFrame and of both components (C's {@code SetFrameFlags}, cmpframe.c). */
    @Override
    void setFrameFlags(int value) {
        super.setFrameFlags(value);
        frame1.setFrameFlags(value);
        frame2.setFrameFlags(value);
    }

    @Override
    double getEpochMjd() {
        if (super.testEpoch()) {
            return super.getEpochMjd();
        } else if (frame1.testEpoch()) {
            return frame1.getEpochMjd();
        } else if (frame2.testEpoch()) {
            return frame2.getEpochMjd();
        }
        return frame1.getEpochMjd();
    }

    @Override
    void setEpochMjd(double mjd) {
        super.setEpochMjd(mjd);
        frame1.setEpochMjd(mjd);
        frame2.setEpochMjd(mjd);
    }

    @Override
    void clearEpoch() {
        super.clearEpoch();
        frame1.clearEpoch();
        frame2.clearEpoch();
    }

    @Override
    double getDtai() {
        if (super.testDtai()) {
            return super.getDtai();
        } else if (frame1.testDtai()) {
            return frame1.getDtai();
        } else if (frame2.testDtai()) {
            return frame2.getDtai();
        }
        return frame1.getDtai();
    }

    @Override
    void setDtai(double value) {
        super.setDtai(value);
        frame1.setDtai(value);
        frame2.setDtai(value);
    }

    @Override
    void clearDtai() {
        super.clearDtai();
        frame1.clearDtai();
        frame2.clearDtai();
    }

    /**
     * Gets Dut1: the one set, or that of the first component that has one set, or the first
     * component's default.
     *
     * @return Dut1 in seconds
     */
    @Override
    public double getDut1() {
        if (super.testDut1()) {
            return super.getDut1();
        } else if (frame1.testDut1()) {
            return frame1.getDut1();
        } else if (frame2.testDut1()) {
            return frame2.getDut1();
        }
        return frame1.getDut1();
    }

    /**
     * Sets Dut1 on the CmpFrame and on both components.
     *
     * @param dut1 UT1-UTC in seconds
     */
    @Override
    public void setDut1(double dut1) {
        super.setDut1(dut1);
        frame1.setDut1(dut1);
        frame2.setDut1(dut1);
    }

    @Override
    void clearDut1() {
        super.clearDut1();
        frame1.clearDut1();
        frame2.clearDut1();
    }

    @Override
    double getObsAlt() {
        if (super.testObsAlt()) {
            return super.getObsAlt();
        } else if (frame1.testObsAlt()) {
            return frame1.getObsAlt();
        } else if (frame2.testObsAlt()) {
            return frame2.getObsAlt();
        }
        return frame1.getObsAlt();
    }

    @Override
    void setObsAlt(double value) {
        super.setObsAlt(value);
        frame1.setObsAlt(value);
        frame2.setObsAlt(value);
    }

    @Override
    void clearObsAlt() {
        super.clearObsAlt();
        frame1.clearObsAlt();
        frame2.clearObsAlt();
    }

    @Override
    double getObsLatValue() {
        if (super.testObsLat()) {
            return super.getObsLatValue();
        } else if (frame1.testObsLat()) {
            return frame1.getObsLatValue();
        } else if (frame2.testObsLat()) {
            return frame2.getObsLatValue();
        }
        return frame1.getObsLatValue();
    }

    @Override
    void setObsLatValue(double value) {
        super.setObsLatValue(value);
        frame1.setObsLatValue(value);
        frame2.setObsLatValue(value);
    }

    @Override
    void clearObsLat() {
        super.clearObsLat();
        frame1.clearObsLat();
        frame2.clearObsLat();
    }

    @Override
    double getObsLonValue() {
        if (super.testObsLon()) {
            return super.getObsLonValue();
        } else if (frame1.testObsLon()) {
            return frame1.getObsLonValue();
        } else if (frame2.testObsLon()) {
            return frame2.getObsLonValue();
        }
        return frame1.getObsLonValue();
    }

    @Override
    void setObsLonValue(double value) {
        super.setObsLonValue(value);
        frame1.setObsLonValue(value);
        frame2.setObsLonValue(value);
    }

    @Override
    void clearObsLon() {
        super.clearObsLon();
        frame1.clearObsLon();
        frame2.clearObsLon();
    }

    // ------------------------------------------------------------------
    // Per-axis attributes, held by the component Frames (cmpframe.c MAKE_GET and friends)
    // ------------------------------------------------------------------

    /**
     * Gets the Label of an axis: the component's, except that a plain Frame component with no
     * Label set gives {@code Axis n}, numbered as the CmpFrame's axis.
     *
     * @param axis the axis number, from 1
     * @return the Label
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String getLabel(int axis) {
        int internal = validateAxis(axis, true, "astGetLabel");
        Frame frame = component(internal);
        int a = componentAxis(internal);
        boolean lent = lendDigits(frame);
        try {
            if (!"Frame".equals(frame.astClass()) || frame.testLabel(a)) {
                return frame.getLabel(a);
            }
            return "Axis " + axis;
        } finally {
            returnDigits(frame, lent);
        }
    }

    /**
     * Gets the NormUnit of an axis, as the NormUnit attribute named with the axis number reads it:
     * from the primary Frame holding the axis. So a SkyFrame axis whose Unit describes its values,
     * such as {@code hh mm ss}, keeps that Unit.
     *
     * @param axis the axis number, from 1
     * @return the NormUnit
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String getNormUnit(int axis) {
        validateAxis(axis, true, "astGetNormUnit");
        return getC("NormUnit(" + axis + ")");
    }

    /**
     * Sets the Label of an axis on the component holding it.
     *
     * @param axis the axis number, from 1
     * @param label the Label
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public void setLabel(int axis, String label) {
        int internal = validateAxis(axis, true, "astSetLabel");
        component(internal).setLabel(componentAxis(internal), label);
    }

    @Override
    boolean testLabel(int axis) {
        int internal = validateAxis(axis, true, "astSetLabel");
        return component(internal).testLabel(componentAxis(internal));
    }

    @Override
    void clearLabel(int axis) {
        int internal = validateAxis(axis, true, "astSetLabel");
        component(internal).clearLabel(componentAxis(internal));
    }

    /**
     * Gets the Symbol of an axis: the component's, except that a plain Frame component with no
     * Symbol set gives {@code xn}, numbered as the CmpFrame's axis.
     *
     * @param axis the axis number, from 1
     * @return the Symbol
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String getSymbol(int axis) {
        int internal = validateAxis(axis, true, "astGetSymbol");
        Frame frame = component(internal);
        int a = componentAxis(internal);
        boolean lent = lendDigits(frame);
        try {
            if (!"Frame".equals(frame.astClass()) || frame.testSymbol(a)) {
                return frame.getSymbol(a);
            }
            return "x" + axis;
        } finally {
            returnDigits(frame, lent);
        }
    }

    /**
     * Sets the Symbol of an axis on the component holding it.
     *
     * @param axis the axis number, from 1
     * @param symbol the Symbol
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public void setSymbol(int axis, String symbol) {
        int internal = validateAxis(axis, true, "astSetSymbol");
        component(internal).setSymbol(componentAxis(internal), symbol);
    }

    @Override
    boolean testSymbol(int axis) {
        int internal = validateAxis(axis, true, "astSetSymbol");
        return component(internal).testSymbol(componentAxis(internal));
    }

    @Override
    void clearSymbol(int axis) {
        int internal = validateAxis(axis, true, "astSetSymbol");
        component(internal).clearSymbol(componentAxis(internal));
    }

    /**
     * Gets the Unit of an axis from the component holding it.
     *
     * @param axis the axis number, from 1
     * @return the Unit
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String getUnit(int axis) {
        int internal = validateAxis(axis, true, "astGetUnit");
        Frame frame = component(internal);
        boolean lent = lendDigits(frame);
        try {
            return frame.getUnit(componentAxis(internal));
        } finally {
            returnDigits(frame, lent);
        }
    }

    /**
     * Sets the Unit of an axis on the component holding it.
     *
     * @param axis the axis number, from 1
     * @param unit the Unit
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public void setUnit(int axis, String unit) {
        int internal = validateAxis(axis, true, "astSetUnit");
        component(internal).setUnit(componentAxis(internal), unit);
    }

    @Override
    boolean testUnit(int axis) {
        int internal = validateAxis(axis, true, "astSetUnit");
        return component(internal).testUnit(componentAxis(internal));
    }

    @Override
    void clearUnit(int axis) {
        int internal = validateAxis(axis, true, "astSetUnit");
        component(internal).clearUnit(componentAxis(internal));
    }

    /**
     * Gets the Format of an axis from the component holding it, with the CmpFrame's Digits if the
     * component has none set.
     *
     * @param axis the axis number, from 1
     * @return the Format
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String getFormat(int axis) {
        int internal = validateAxis(axis, true, "astGetFormat");
        Frame frame = component(internal);
        boolean lent = lendDigits(frame);
        try {
            return frame.getFormat(componentAxis(internal));
        } finally {
            returnDigits(frame, lent);
        }
    }

    /**
     * Sets the Format of an axis on the component holding it.
     *
     * @param axis the axis number, from 1
     * @param format the Format
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public void setFormat(int axis, String format) {
        int internal = validateAxis(axis, true, "astSetFormat");
        component(internal).setFormat(componentAxis(internal), format);
    }

    @Override
    boolean testFormat(int axis) {
        int internal = validateAxis(axis, true, "astSetFormat");
        return component(internal).testFormat(componentAxis(internal));
    }

    @Override
    void clearFormat(int axis) {
        int internal = validateAxis(axis, true, "astSetFormat");
        component(internal).clearFormat(componentAxis(internal));
    }

    /**
     * Gets the Direction of an axis from the component holding it.
     *
     * @param axis the axis number, from 1
     * @return the Direction
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public boolean getDirection(int axis) {
        int internal = validateAxis(axis, true, "astGetDirection");
        Frame frame = component(internal);
        boolean lent = lendDigits(frame);
        try {
            return frame.getDirection(componentAxis(internal));
        } finally {
            returnDigits(frame, lent);
        }
    }

    /**
     * Sets the Direction of an axis on the component holding it.
     *
     * @param axis the axis number, from 1
     * @param direction the Direction
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public void setDirection(int axis, boolean direction) {
        int internal = validateAxis(axis, true, "astSetDirection");
        component(internal).setDirection(componentAxis(internal), direction);
    }

    @Override
    boolean testDirection(int axis) {
        int internal = validateAxis(axis, true, "astSetDirection");
        return component(internal).testDirection(componentAxis(internal));
    }

    @Override
    void clearDirection(int axis) {
        int internal = validateAxis(axis, true, "astSetDirection");
        component(internal).clearDirection(componentAxis(internal));
    }

    // ------------------------------------------------------------------
    // String-based attribute access (cmpframe.c GetAttrib, SetAttrib, TestAttrib, ClearAttrib)
    // ------------------------------------------------------------------

    /** An attribute name with an axis number, {@code name(n)}. */
    private static final class AxisName {
        final String name;
        final int axis;

        AxisName(String name, int axis) {
            this.name = name;
            this.axis = axis;
        }
    }

    /**
     * The name and axis number of {@code attrib} if it is {@code name(n)}, as C's {@code
     * sscanf(attrib, "%[^(](%d)%n")} reads it when it reads all of it: a name of one or more
     * characters other than {@code (}, then a decimal integer in parentheses. Otherwise null.
     */
    private static AxisName axisName(String attrib) {
        int open = attrib.indexOf('(');
        if (open <= 0) {
            return null;
        }
        int n = attrib.length();
        int i = open + 1;
        while (i < n && CScan.isCSpace(attrib.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < n && (attrib.charAt(i) == '+' || attrib.charAt(i) == '-')) {
            i++;
        }
        int digits = i;
        while (i < n && attrib.charAt(i) >= '0' && attrib.charAt(i) <= '9') {
            i++;
        }
        if (i == digits || i != n - 1 || attrib.charAt(i) != ')') {
            return null;
        }
        Integer axis = CScan.scanInt(attrib.substring(start, i));
        return axis == null ? null : new AxisName(attrib.substring(0, open), axis);
    }

    /**
     * The primary Frame holding an axis of this CmpFrame, and the external number of the axis in
     * it, from 1, in {@code paxis[0]} (C's {@code astPrimaryFrame} followed by {@code
     * astValidateAxis} to undo the primary Frame's permutation).
     */
    private Frame primaryExternal(int axis, int[] paxis, String method) {
        Frame pfrm = primaryFrame(axis, paxis);
        paxis[0] = pfrm.validateAxis(paxis[0], false, method);
        return pfrm;
    }

    /**
     * C's {@code GetAttrib} (cmpframe.c): a name with an axis number is read from the primary
     * Frame holding that axis, with the axis number it has there and failing that without one;
     * another name is read as a Frame attribute, failing that from the primary Frame of each axis
     * in turn.
     */
    @Override
    String getAttrib(String attrib) {
        AxisName an = axisName(attrib);
        String result;
        if (an != null) {
            int[] paxis = new int[1];
            Frame pfrm = primaryExternal(an.axis, paxis, "astGet");
            result = tryGetAttrib(pfrm, an.name + "(" + paxis[0] + ")");
            if (result == null) {
                result = tryGetAttrib(pfrm, an.name);
            }
        } else {
            result = tryParentGetAttrib(attrib);
            int naxes = getNaxes();
            int[] paxis = new int[1];
            for (int axis = 1; axis <= naxes && result == null; axis++) {
                result = tryGetAttrib(primaryFrame(axis, paxis), attrib);
            }
        }
        if (result == null) {
            throw noAttribute("astGet", attrib);
        }
        return result;
    }

    /** The Frame class's value of an attribute, or null if it has none (C clears the error). */
    private String tryParentGetAttrib(String attrib) {
        try {
            return super.getAttrib(attrib);
        } catch (AstException e) {
            return null;
        }
    }

    /** The value of an attribute of {@code frame}, or null if it has none (C clears the error). */
    private static String tryGetAttrib(Frame frame, String attrib) {
        try {
            return frame.getAttrib(attrib);
        } catch (AstException e) {
            return null;
        }
    }

    /**
     * C's {@code TestAttrib} (cmpframe.c): the name is tested as a Frame attribute, failing that,
     * if it has an axis number, on the primary Frame holding that axis, with the axis number it
     * has there and failing that without one, and otherwise on the primary Frame of each axis in
     * turn.
     */
    @Override
    boolean testAttrib(String attrib) {
        Boolean result = tryParentTestAttrib(attrib);
        if (result == null) {
            AxisName an = axisName(attrib);
            if (an != null) {
                int[] paxis = new int[1];
                Frame pfrm = primaryExternal(an.axis, paxis, "astTest");
                result = tryTestAttrib(pfrm, an.name + "(" + paxis[0] + ")");
                if (result == null) {
                    result = tryTestAttrib(pfrm, an.name);
                }
            } else {
                int naxes = getNaxes();
                int[] paxis = new int[1];
                for (int axis = 1; axis <= naxes && result == null; axis++) {
                    result = tryTestAttrib(primaryFrame(axis, paxis), attrib);
                }
            }
        }
        if (result == null) {
            throw noAttribute("astTest", attrib);
        }
        return result;
    }

    /** Whether the Frame class's attribute is set, or null if it has none (C clears the error). */
    // MODERN-JAVA(9): Optional<Boolean> could replace the nullable Boolean.
    private Boolean tryParentTestAttrib(String attrib) {
        try {
            return super.testAttrib(attrib);
        } catch (AstException e) {
            return null;
        }
    }

    /** Whether an attribute of {@code frame} is set, or null if it has none (C clears the error). */
    private static Boolean tryTestAttrib(Frame frame, String attrib) {
        try {
            return frame.testAttrib(attrib);
        } catch (AstException e) {
            return null;
        }
    }

    /**
     * C's {@code ClearAttrib} (cmpframe.c): the name is cleared as a Frame attribute, failing that,
     * if it has an axis number, on the primary Frame holding that axis, with the axis number it
     * has there and failing that without one, and otherwise on the primary Frame of every axis
     * that has it.
     */
    @Override
    void clearAttrib(String attrib) {
        boolean ok = apply(this, f -> clearAttribAsFrame(attrib));
        if (!ok) {
            AxisName an = axisName(attrib);
            if (an != null) {
                int[] paxis = new int[1];
                Frame pfrm = primaryExternal(an.axis, paxis, "astClear");
                ok = apply(pfrm, f -> f.clearAttrib(an.name + "(" + paxis[0] + ")"))
                        || apply(pfrm, f -> f.clearAttrib(an.name));
            } else {
                ok = forEachPrimary(f -> f.clearAttrib(attrib));
            }
        }
        if (!ok) {
            throw noAttribute("astClear", attrib);
        }
    }

    /**
     * C's {@code SetAttrib} (cmpframe.c): the setting is applied as a Frame attribute, failing that,
     * if the name has an axis number and the value is a single word, on the primary Frame holding
     * that axis, with the axis number it has there and failing that without one, and otherwise on
     * the primary Frame of every axis that accepts it.
     */
    @Override
    void setAttrib(String attrib, String value) {
        boolean ok = apply(this, f -> setAttribAsFrame(attrib, value));
        if (!ok) {
            AxisName an = axisName(attrib);
            int vstart = 0;
            while (vstart < value.length() && CScan.isCSpace(value.charAt(vstart))) {
                vstart++;
            }
            String word = value.substring(vstart);
            if (an != null && isOneWord(word)) {
                int[] paxis = new int[1];
                Frame pfrm = primaryExternal(an.axis, paxis, "astSet");
                ok = apply(pfrm, f -> f.setAttrib(an.name + "(" + paxis[0] + ")", word))
                        || apply(pfrm, f -> f.setAttrib(an.name, word));
            } else {
                ok = forEachPrimary(f -> f.setAttrib(attrib, value));
            }
        }
        if (!ok) {
            throw Ast.err(
                    AstException.AST__BADAT,
                    "astSet: The attribute setting \"" + attrib + "=" + value + "\" is invalid for the given "
                            + astClass() + ".");
        }
    }

    /** The Frame class's {@code SetAttrib}, which C's CmpFrame tries first. */
    private void setAttribAsFrame(String attrib, String value) {
        super.setAttrib(attrib, value);
    }

    /** The Frame class's {@code ClearAttrib}, which C's CmpFrame tries first. */
    private void clearAttribAsFrame(String attrib) {
        super.clearAttrib(attrib);
    }

    /**
     * Whether {@code s} is what C's {@code "%*s %n"} reads all of: one or more characters that are
     * not white space, then only white space.
     */
    private static boolean isOneWord(String s) {
        int n = s.length();
        int i = 0;
        while (i < n && !CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return false;
        }
        while (i < n && CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        return i == n;
    }

    /** An action on a Frame, which may fail with an AstException. */
    @FunctionalInterface
    private interface FrameAction {
        void apply(Frame frame);
    }

    /**
     * Applies an action to a Frame, as C does with error reporting off.
     *
     * @return whether it succeeded; C clears the error if not
     */
    private static boolean apply(Frame frame, FrameAction action) {
        try {
            action.apply(frame);
            return true;
        } catch (AstException e) {
            return false;
        }
    }

    /**
     * Applies an action to the primary Frame of every axis in turn, as C's {@code SetAttrib} and
     * {@code ClearAttrib} do, whether or not it succeeds for an earlier one.
     *
     * @return whether it succeeded for at least one axis
     */
    private boolean forEachPrimary(FrameAction action) {
        boolean ok = false;
        int naxes = getNaxes();
        int[] paxis = new int[1];
        for (int axis = 1; axis <= naxes; axis++) {
            if (apply(primaryFrame(axis, paxis), action)) {
                ok = true;
            }
        }
        return ok;
    }

    /** C's error when no part of the CmpFrame has an attribute. */
    private AstException noAttribute(String method, String attrib) {
        return Ast.err(
                AstException.AST__BADAT,
                method + ": The " + astClass() + " given does not have an attribute called \"" + attrib + "\".");
    }
}
