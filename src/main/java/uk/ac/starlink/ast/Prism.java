package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Region made by extruding one Region into the dimensions of another: a point is in the Prism
 * when its values on the axes of the first Region are in the first Region and its values on the
 * axes of the second are in the second. For instance, a Prism of a Circle and an Interval is a
 * cylinder. The Frame of a Prism is a CmpFrame of the Frames of the two Regions, the axes of the
 * first followed by those of the second.
 *
 * <p>The Prism holds copies of the Regions given, so that a later change to them does not change
 * it. Setting Closed or MeshSize on a Prism sets it on both its components too, and clearing it
 * clears it on them. Its default FillFactor is the product of its components'.
 *
 * <p>Ported from C {@code prism.c}, with Rust {@code regions/prism.rs}.
 */
public class Prism extends Region {

    /**
     * The table combining the overlap of the two Prisms' first components (the row) with that of
     * their second components (the column) into the overlap of the Prisms (prism.c {@code
     * Overlap}), the codes being those of {@link Region#overlap}.
     */
    private static final int[][] RTABLE = {
        {0, 0, 0, 0, 0, 0, 0},
        {0, 1, 1, 1, 1, 1, 1},
        {0, 1, 2, 4, 4, 2, 1},
        {0, 1, 4, 3, 4, 3, 1},
        {0, 1, 4, 4, 4, 4, 1},
        {0, 1, 2, 3, 4, 5, 1},
        {0, 1, 1, 1, 1, 1, 6}
    };

    /** The first component, the Region extruded, in the first axes of the base Frame. */
    private Region region1;

    /** The second component, the Region of the axes of extrusion, in the remaining base Frame axes. */
    private Region region2;

    /** The components the constructor takes, with the CmpFrame of their Frames. */
    private static final class Parts {
        Region reg1;
        Region reg2;
        Frame frm;
    }

    /**
     * Creates a Prism (C's {@code astPrism}).
     *
     * @param region1 the Region to extrude; a copy is held
     * @param region2 the Region over whose axes the first is extruded, often an Interval; a copy
     *     is held
     * @throws NullPointerException if a Region is null
     */
    public Prism(Region region1, Region region2) {
        this(parts(region1, region2));
    }

    /** C's {@code astInitPrism}, after the work {@link #parts} does. */
    private Prism(Parts p) {
        super(p.frm, null, null);
        region1 = p.reg1;
        region2 = p.reg2;
        if (isUnitMap(region1.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT))) {
            region1.setRegionFS(false);
        }
        if (isUnitMap(region2.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT))) {
            region2.setRegionFS(false);
        }
    }

    /** The first part of C's {@code astInitPrism}: the Regions copied and their Frames combined. */
    private static Parts parts(Region region1, Region region2) {
        Objects.requireNonNull(region1, "region1");
        Objects.requireNonNull(region2, "region2");
        Parts p = new Parts();
        p.reg1 = (Region) region1.copy();
        p.reg2 = (Region) region2.copy();
        // C annuls this CmpFrame once the Region part has copied it, so its components, the
        // Regions' own Frames, are not left shared.
        p.frm = new CmpFrame(p.reg1.regFrame(), p.reg2.regFrame(), false);
        return p;
    }

    /**
     * Loads a Prism from its description, as C's {@code astLoadPrism} does: the Region part, then
     * {@code RegionA} and {@code RegionB}. A component read without its Frame is given the axes of
     * the base Frame of the Prism it spans, unless the Prism was read without its own, when the
     * Region that holds it gives the components theirs.
     *
     * @throws AstException with status {@code AST__LDERR} if a component is missing or is not a
     *     Region
     */
    Prism(NativeNode node) {
        super(node);
        NativeNode part = node.section("Prism");
        region1 = component(part, "RegionA");
        region2 = component(part, "RegionB");
        if (!regDummyFS()) {
            Frame f1 = baseFrame();
            int nax1 = region1.getNaxes();
            if (region1.regDummyFS()) {
                region1.setRegFS(f1.pickAxes(nax1, axisRange(0, nax1), null));
            }
            if (region2.regDummyFS()) {
                int nax2 = region2.getNaxes();
                region2.setRegFS(f1.pickAxes(nax2, axisRange(nax1, nax2), null));
            }
        }
    }

    /** The axes {@code first + 1} to {@code first + n}, numbered from 1. */
    private static int[] axisRange(int first, int n) {
        int[] axes = new int[n];
        for (int i = 0; i < n; i++) {
            axes[i] = first + i + 1;
        }
        return axes;
    }

    /** The indices {@code first} to {@code first + n - 1}. */
    private static int[] indexRange(int first, int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = first + i;
        }
        return idx;
    }

    /** A component Region read from a description. */
    private static Region component(NativeNode part, String key) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            throw Ast.err(AstException.AST__LDERR, "astLoadPrism(Prism): The component Region " + key + " is missing.");
        }
        AstObject obj = ObjectRegistry.loadNested(desc, key);
        if (!(obj instanceof Region)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadPrism(Prism): The component " + key + " is a " + obj.astClass() + ", not a Region.");
        }
        return (Region) obj;
    }

    @Override
    String dumpComment() {
        return "Region extrusion into higher dimensions";
    }

    /** Writes the Prism class's part of a description (C's {@code Dump}, prism.c): {@code RegionA} and {@code RegionB}. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
        node.writeObject("RegionA", true, true, NativeNode.of(region1), "First component Region");
        node.writeObject("RegionB", true, true, NativeNode.of(region2), "Second component Region");
    }

    /** Completes a copy as C's {@code Copy} (prism.c) does: the components are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        region1 = (Region) region1.copy();
        region2 = (Region) region2.copy();
    }

    // ------------------------------------------------------------------
    // The components
    // ------------------------------------------------------------------

    /** The first component, the Region extruded, the Prism's own. */
    Region getRegion1() {
        return region1;
    }

    /** The second component, the Region of the axes of extrusion, the Prism's own. */
    Region getRegion2() {
        return region2;
    }

    /**
     * The two components, in parallel, as C's {@code Decompose} (prism.c) gives them, with their
     * Invert values reversed if the Prism is inverted.
     */
    @Override
    Mapping[] decomposeInto(boolean[] flags) {
        flags[0] = false;
        if (getInvert()) {
            flags[1] = !region1.getInvert();
            flags[2] = !region2.getInvert();
        } else {
            flags[1] = region1.getInvert();
            flags[2] = region2.getInvert();
        }
        return new Mapping[] {region1, region2};
    }

    /**
     * prism.c {@code MapList}: for a parallel decomposition, the lists of the two components, each
     * with Invert false, which a Region ignores; for a series one, the Prism itself.
     */
    @Override
    boolean mapList(boolean series, boolean invert, List<Mapping> maps, List<Boolean> inverts) {
        if (!series) {
            region1.mapList(false, false, maps, inverts);
            region2.mapList(false, false, maps, inverts);
        } else {
            super.mapList(series, invert, maps, inverts);
        }
        return false;
    }

    /** prism.c {@code Equal}: region.c's tests, then equal components. */
    @Override
    boolean equalTo(AstObject that) {
        if (!super.equalTo(that)) {
            return false;
        }
        Prism other = (Prism) that;
        return region1.astEqual(other.region1) && region2.astEqual(other.region2);
    }

    // ------------------------------------------------------------------
    // Attributes shared with the components
    // ------------------------------------------------------------------

    /** Sets MeshSize on the Prism and both its components (prism.c {@code SetMeshSize}). */
    @Override
    void setMeshSizeValue(int value) {
        super.setMeshSizeValue(value);
        region1.setMeshSizeValue(value);
        region2.setMeshSizeValue(value);
    }

    /** Clears MeshSize on the Prism and both its components (prism.c {@code ClearMeshSize}). */
    @Override
    void clearMeshSize() {
        super.clearMeshSize();
        region1.clearMeshSize();
        region2.clearMeshSize();
    }

    /** Sets Closed on the Prism and both its components (prism.c {@code SetClosed}). */
    @Override
    void setClosedValue(boolean value) {
        super.setClosedValue(value);
        region1.setClosedValue(value);
        region2.setClosedValue(value);
    }

    /** Clears Closed on the Prism and both its components (prism.c {@code ClearClosed}). */
    @Override
    void clearClosed() {
        super.clearClosed();
        region1.clearClosed();
        region2.clearClosed();
    }

    /**
     * Gets the FillFactor attribute: if not set, the product of the components' (prism.c {@code
     * GetFillFactor}).
     *
     * @return the FillFactor attribute
     */
    @Override
    public double getFillFactor() {
        if (testFillFactor()) {
            return super.getFillFactor();
        }
        double f1 = region1.getFillFactor();
        double f2 = region2.getFillFactor();
        return f1 != AST__BAD && f2 != AST__BAD ? f1 * f2 : AST__BAD;
    }

    /**
     * Sets a Frame attribute without remapping (prism.c {@code RegSetAttrib}): region.c's, then
     * the base Frame setting on the component holding the axis it names, with the axis number of
     * that component, if it names one and its value is a single word as C reads it, and otherwise
     * on both components. An attribute a component does not have is ignored, and then the second
     * component is not tried, as in C.
     */
    @Override
    String regSetAttrib(String attrib, String value) {
        String battrib = super.regSetAttrib(attrib, value);
        Integer axis = attribAxis(battrib);
        try {
            if (axis != null && isOneWord(value)) {
                Region creg = componentOf(axis - 1);
                creg.regSetAttrib(componentAttrib(battrib, axis - 1), value);
            } else {
                region1.regSetAttrib(battrib, value);
                region2.regSetAttrib(battrib, value);
            }
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        return battrib;
    }

    /**
     * Clears a Frame attribute without remapping (prism.c {@code RegClearAttrib}), as {@link
     * #regSetAttrib} sets one.
     */
    @Override
    String regClearAttrib(String attrib) {
        String battrib = super.regClearAttrib(attrib);
        Integer axis = attribAxis(battrib);
        try {
            if (axis != null) {
                componentOf(axis - 1).regClearAttrib(componentAttrib(battrib, axis - 1));
            } else {
                region1.regClearAttrib(battrib);
                region2.regClearAttrib(battrib);
            }
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        return battrib;
    }

    /** The component holding base Frame axis {@code axis}, numbered from 0. */
    private Region componentOf(int axis) {
        return axis < region1.getNaxes() ? region1 : region2;
    }

    /** {@code battrib}, naming base Frame axis {@code axis} (from 0), renamed for the component holding it. */
    private String componentAttrib(String battrib, int axis) {
        int nax1 = region1.getNaxes();
        int caxis = axis < nax1 ? axis : axis - nax1;
        return battrib.substring(0, battrib.indexOf('(')) + "(" + (caxis + 1) + ")";
    }

    /**
     * Gives the Prism a FrameSet of {@code frm} (prism.c {@code SetRegFS}), and each component read
     * without its Frame the axes of {@code frm} it spans.
     */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        int nax1 = 0;
        if (region1 != null) {
            nax1 = region1.getNaxes();
            if (!region1.getRegionFS()) {
                region1.setRegFS(frm.pickAxes(nax1, axisRange(0, nax1), null));
            }
        }
        if (region2 != null && !region2.getRegionFS()) {
            int nax2 = region2.getNaxes();
            region2.setRegFS(frm.pickAxes(nax2, axisRange(nax1, nax2), null));
        }
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Which base Frame points are in the Prism (prism.c {@code Transform}): the two components, in
     * parallel, transform the points, and a point is in the un-negated Prism if no coordinate is
     * made bad.
     */
    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        return inside(base, npoint, getNegated());
    }

    /** Which base Frame points are in the Prism as if it were not negated. */
    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        return inside(base, npoint, false);
    }

    private boolean[] inside(double[][] base, int npoint, boolean neg) {
        Mapping map = CmpMap.transientCmpMap(region1, region2, false);
        double[][] ptrb = map.transform(base, npoint, true);
        int ncoord = base.length;
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            boolean good = true;
            for (int coord = 0; coord < ncoord; coord++) {
                if (ptrb[coord][point] == AST__BAD) {
                    good = false;
                    break;
                }
            }
            mask[point] = good != neg;
        }
        return mask;
    }

    /**
     * Whether the Prism is bounded (prism.c {@code GetBounded}): whether both components are,
     * negated in place and back again for the while if the Prism is negated.
     */
    @Override
    boolean isBoundedInner() {
        boolean neg = getNegated();
        if (neg) {
            region1.negate();
            region2.negate();
        }
        try {
            return region1.getBounded() && region2.getBounded();
        } finally {
            if (neg) {
                region1.negate();
                region2.negate();
            }
        }
    }

    // ------------------------------------------------------------------
    // Bounds and boundary
    // ------------------------------------------------------------------

    /**
     * The bounding box of the un-negated Prism in the base Frame (prism.c {@code RegBaseBox}): the
     * bounds of the first component followed by those of the second.
     */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        double[][] b1 = region1.getRegionBounds();
        double[][] b2 = region2.getRegionBounds();
        int numAxes = b1[0].length;
        System.arraycopy(b1[0], 0, lbnd, 0, numAxes);
        System.arraycopy(b1[1], 0, ubnd, 0, numAxes);
        System.arraycopy(b2[0], 0, lbnd, numAxes, b2[0].length);
        System.arraycopy(b2[1], 0, ubnd, numAxes, b2[1].length);
    }

    /**
     * The bounds of the Prism in its current Frame (prism.c {@code GetRegionBounds}): if the axes
     * of each component can be split off the Mapping from the base Frame, each component is mapped
     * into the current Frame axes it gives and its bounds are put on those axes; otherwise
     * region.c's.
     */
    @Override
    void regionBounds(double[] lbnd, double[] ubnd) {
        int nax1 = region1.getNaxes();
        int nax2 = region2.getNaxes();
        FrameSet fs = getRegFS();
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Frame cfrm = fs.getFrame(FrameSet.AST__CURRENT);
        MapSplit split1 = map.mapSplit(indexRange(0, nax1));
        MapSplit split2 = map.mapSplit(indexRange(nax1, nax2));
        if (split1 == null || split2 == null) {
            super.regionBounds(lbnd, ubnd);
            return;
        }
        boundsOnAxes(region1, split1, cfrm, lbnd, ubnd);
        boundsOnAxes(region2, split2, cfrm, lbnd, ubnd);
    }

    /** The bounds of {@code reg} mapped by {@code split} into its axes of {@code cfrm}, put on those axes. */
    private static void boundsOnAxes(Region reg, MapSplit split, Frame cfrm, double[] lbnd, double[] ubnd) {
        int naxOut = split.map.getNout();
        int[] axes1 = new int[naxOut];
        for (int i = 0; i < naxOut; i++) {
            axes1[i] = split.outputs[i] + 1;
        }
        Frame frm = cfrm.pickAxes(naxOut, axes1, null);
        Region mapped = reg.mapRegionInto(split.map, frm);
        double[] tl = new double[naxOut];
        double[] tu = new double[naxOut];
        mapped.regionBounds(tl, tu);
        for (int i = 0; i < naxOut; i++) {
            lbnd[split.outputs[i]] = tl[i];
            ubnd[split.outputs[i]] = tu[i];
        }
    }

    /**
     * The boundary mesh in the base Frame (prism.c {@code RegBaseMesh}), kept once made: the grid
     * over the inside of the first component at each point of the second component's boundary
     * mesh, then the grid over the inside of the second at each point of the first's mesh. The mesh
     * and grid sizes are chosen so that the total is near the Prism's MeshSize, half on the ends
     * and half on the sides; each is set on the component for the while and its own value put
     * back.
     *
     * @throws AstException with status {@code AST__INTER} if a component is unbounded whether
     *     negated or not, or if the Prism has a number of axes other than its base Frame's, where C
     *     writes or reads beyond its arrays
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        if (!hasMesh(region1) || !hasMesh(region2)) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegBaseMesh(" + astClass() + "): No mesh can be produced for the " + astClass()
                            + " bacause one of its component Regions is unbounded."); // codespell:ignore bacause
        }
        int mszp = getMeshSize();
        // C's sizes are ints assigned from doubles, which truncates them.
        int msz1 = region1.getNaxes() == 1 ? 2 : (int) Math.sqrt(0.5 * mszp);
        int gsz2 = (int) (0.5 * mszp / msz1);
        int msz2 = region2.getNaxes() == 1 ? 2 : (int) Math.sqrt(0.5 * mszp);
        int gsz1 = (int) (0.5 * mszp / msz2);
        double[][] mesh2;
        double[][] grid2;
        int msz = region2.testMeshSize() ? region2.getMeshSize() : -1;
        try {
            region2.setMeshSizeValue(msz2);
            mesh2 = region2.regMesh();
            region2.setMeshSizeValue(gsz2);
            grid2 = region2.regGrid();
        } finally {
            restoreMeshSize(region2, msz);
        }
        double[][] mesh1;
        double[][] grid1;
        msz = region1.testMeshSize() ? region1.getMeshSize() : -1;
        try {
            region1.setMeshSizeValue(msz1);
            mesh1 = region1.regMesh();
            region1.setMeshSizeValue(gsz1);
            grid1 = region1.regGrid();
        } finally {
            restoreMeshSize(region1, msz);
        }
        int nax1 = mesh1.length;
        int numAxes = getNaxes();
        if (numAxes != nax1 + mesh2.length) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegBaseMesh(" + astClass() + "): The " + astClass() + " has " + numAxes
                            + " axes but its components " + "have " + (nax1 + mesh2.length)
                            + " (internal AST programming error).");
        }
        int nm1 = mesh1[0].length;
        int ng1 = grid1[0].length;
        int nm2 = mesh2[0].length;
        int ng2 = grid2[0].length;
        int np = ng1 * nm2 + nm1 * ng2;
        double[][] ptr = new double[numAxes][np];
        int ii = 0;
        for (int i = 0; i < nm2; i++) {
            for (int j = 0; j < ng1; j++, ii++) {
                for (int jc = 0; jc < nax1; jc++) {
                    ptr[jc][ii] = grid1[jc][j];
                }
                for (int jc = nax1; jc < numAxes; jc++) {
                    ptr[jc][ii] = mesh2[jc - nax1][i];
                }
            }
        }
        for (int i = 0; i < nm1; i++) {
            for (int j = 0; j < ng2; j++, ii++) {
                for (int jc = 0; jc < nax1; jc++) {
                    ptr[jc][ii] = mesh1[jc][i];
                }
                for (int jc = nax1; jc < numAxes; jc++) {
                    ptr[jc][ii] = grid2[jc - nax1][j];
                }
            }
        }
        keepBaseMesh(ptr);
        return ptr;
    }

    /** Puts back a component's MeshSize: {@code msz}, or cleared if it is -1. */
    private static void restoreMeshSize(Region reg, int msz) {
        if (msz == -1) {
            reg.clearMeshSize();
        } else {
            reg.setMeshSizeValue(msz);
        }
    }

    /** Whether a component has a mesh: whether it is bounded, negated or not. */
    private static boolean hasMesh(Region reg) {
        if (reg.getBounded()) {
            return true;
        }
        reg.negate();
        try {
            return reg.getBounded();
        } finally {
            reg.negate();
        }
    }

    /**
     * The default uncertainty (prism.c {@code GetDefUnc}): a Prism of the components'
     * uncertainties.
     */
    @Override
    Region getDefUnc() {
        return new Prism(region1.getUncFrm(FrameSet.AST__CURRENT), region2.getUncFrm(FrameSet.AST__CURRENT));
    }

    /**
     * The centre of the Prism, or a change of it (prism.c {@code RegCentre}): the components'
     * centres joined, if both have one, and in the current Frame if asked; or each component moved
     * to its part of the new centre, given in the base or the current Frame. As in C, what the
     * Prism keeps about its boundary is not discarded when it moves.
     */
    @Override
    double[] regCentre(double[] cen, double[][] ptr, int index, int ifrm) {
        int nax1 = region1.getNaxes();
        int nax2 = region2.getNaxes();
        int ncb = nax1 + nax2;
        if (ptr == null && cen == null) {
            double[] cen1 = region1.regCentre(null, null, 0, FrameSet.AST__CURRENT);
            double[] cen2 = region2.regCentre(null, null, 0, FrameSet.AST__CURRENT);
            if (cen1 == null || cen2 == null) {
                return null;
            }
            double[] total = new double[ncb];
            System.arraycopy(cen1, 0, total, 0, nax1);
            System.arraycopy(cen2, 0, total, nax1, nax2);
            return ifrm == FrameSet.AST__CURRENT ? regTranPoint(total, true) : total;
        }
        double[] bc;
        if (ifrm == FrameSet.AST__CURRENT) {
            double[] c;
            if (cen != null) {
                c = cen;
            } else {
                int ncc = getNaxes();
                c = new double[ncc];
                for (int i = 0; i < ncc; i++) {
                    c[i] = ptr[i][index];
                }
            }
            bc = regTranPoint(c, false);
        } else if (cen != null) {
            bc = cen;
        } else {
            bc = new double[ncb];
            for (int i = 0; i < ncb; i++) {
                bc[i] = ptr[i][index];
            }
        }
        region1.regCentre(Arrays.copyOf(bc, nax1), null, 0, FrameSet.AST__CURRENT);
        region2.regCentre(Arrays.copyOfRange(bc, nax1, nax1 + nax2), null, 0, FrameSet.AST__CURRENT);
        return null;
    }

    /**
     * A Region of the chosen base Frame axes (prism.c {@code RegBasePick}): the pick of the first
     * component's axes alone, or the second's alone, or a Prism of the two picks, if the picks
     * needed are Regions; null otherwise.
     */
    @Override
    Region regBasePick(int[] axes) {
        int nax1 = region1.getNaxes();
        int nax2 = region2.getNaxes();
        int[] axes1 = new int[nax1];
        int[] axes2 = new int[nax2];
        int naxes1 = 0;
        int naxes2 = 0;
        for (int j : axes) {
            if (j < nax1) {
                axes1[naxes1++] = j + 1;
            } else {
                axes2[naxes2++] = j - nax1 + 1;
            }
        }
        Region frm1 = null;
        if (naxes1 > 0) {
            Frame f = region1.pickAxes(naxes1, Arrays.copyOf(axes1, naxes1), null);
            frm1 = f instanceof Region ? (Region) f : null;
        }
        Region frm2 = null;
        if (naxes2 > 0) {
            Frame f = region2.pickAxes(naxes2, Arrays.copyOf(axes2, naxes2), null);
            frm2 = f instanceof Region ? (Region) f : null;
        }
        if (naxes1 > 0 && naxes2 == 0 && frm1 != null) {
            return frm1;
        } else if (naxes1 == 0 && naxes2 > 0 && frm2 != null) {
            return frm2;
        } else if (naxes1 > 0 && naxes2 > 0 && frm1 != null && frm2 != null) {
            return new Prism(frm1, frm2);
        }
        return null;
    }

    /**
     * Whether every point of {@code pset}, in the base Frame, is on the boundary of the Prism
     * (prism.c {@code RegPins}): on the boundary of one component and on or inside the other. For
     * the while each component's Closed is set to its own value, which keeps one set false open,
     * and to true where it is not set. Each component tests its own part of the points with its
     * own uncertainty; {@code pointUnc} is not used, as in C.
     */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        int cl1 = region1.testClosed() ? (region1.getClosed() ? 1 : 0) : Integer.MAX_VALUE;
        int cl2 = region2.testClosed() ? (region2.getClosed() ? 1 : 0) : Integer.MAX_VALUE;
        // C sets each to its own value as an int, so INT_MAX, for one not set, sets it true.
        region1.setClosedValue(cl1 != 0);
        region2.setClosedValue(cl2 != 0);
        try {
            return pins(pset, mask);
        } finally {
            restoreClosed(region1, cl1);
            restoreClosed(region2, cl2);
        }
    }

    private static void restoreClosed(Region reg, int cl) {
        if (cl == Integer.MAX_VALUE) {
            reg.clearClosed();
        } else {
            reg.setClosedValue(cl != 0);
        }
    }

    /** The tests of {@link #regPins}, with the components' Closed set as it sets them. */
    private boolean pins(double[][] pset, boolean[] mask) {
        int np = pset[0].length;
        int nax1 = region1.getNaxes();
        int nax2 = region2.getNaxes();
        double[][] ps1 = new double[nax1][];
        for (int i = 0; i < nax1; i++) {
            ps1[i] = Arrays.copyOf(pset[i], np);
        }
        double[][] ps1b = region1.regTransform(ps1, false);
        boolean[] mask1 = new boolean[np];
        region1.regPins(ps1b, region1.getUncFrm(FrameSet.AST__BASE), mask1);
        double[][] ps1b2 = region1.transform(ps1b, np, true);
        double[][] ps2 = new double[nax2][];
        for (int i = 0; i < nax2; i++) {
            ps2[i] = Arrays.copyOf(pset[nax1 + i], np);
        }
        double[][] ps2b = region2.regTransform(ps2, false);
        boolean[] mask2 = new boolean[np];
        region2.regPins(ps2b, region2.getUncFrm(FrameSet.AST__BASE), mask2);
        double[][] ps2b2 = region2.transform(ps2b, np, true);
        boolean result = true;
        for (int i = 0; i < np; i++) {
            boolean on = true;
            if (mask1[i]) {
                if (!mask2[i]) {
                    on = allGood(ps2b2, nax2, i);
                }
            } else if (mask2[i]) {
                on = allGood(ps1b2, nax1, i);
            } else {
                on = false;
            }
            if (mask != null) {
                mask[i] = on;
            }
            if (!on) {
                result = false;
            }
        }
        return result;
    }

    /** Whether point {@code i} of {@code ptr} has no bad value on its first {@code numAxes} axes. */
    private static boolean allGood(double[][] ptr, int numAxes, int i) {
        for (int j = 0; j < numAxes; j++) {
            if (ptr[j][i] == AST__BAD) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Overlap
    // ------------------------------------------------------------------

    /** C's {@code Overlap} (prism.c): {@link #prismOverlap}. */
    @Override
    int overlapWith(Region that) {
        return prismOverlap(this, that);
    }

    /**
     * C's {@code OverlapX} (prism.c), with this Prism as {@code that}: {@link #prismOverlap} with
     * the Regions swapped, its codes for one inside the other swapped back.
     */
    @Override
    int overlapX(Region thisRegion) {
        int result = prismOverlap(this, thisRegion);
        if (result == OVERLAP_INSIDE) {
            result = OVERLAP_OUTSIDE;
        } else if (result == OVERLAP_OUTSIDE) {
            result = OVERLAP_INSIDE;
        }
        return result;
    }

    /**
     * C's {@code Overlap} (prism.c): how {@code thisPrism} and {@code that} overlap. If the axes of
     * each component of the Prism correspond to a set of axes of {@code that} from which a Region
     * can be picked, each pair is compared and the two codes combined by {@link #RTABLE}, which
     * then allows for the negation of the Prism and of {@code that}; otherwise region.c's {@code
     * OverlapX}. The Regions picked from {@code that} have its Negated value (region.c {@code
     * PickAxes}), so a negated {@code that} is allowed for twice, as in C.
     */
    private static int prismOverlap(Prism thisPrism, Region that) {
        Region thisReg1 = thisPrism.region1;
        Region thisReg2 = thisPrism.region2;
        boolean thisNeg = thisPrism.getNegated();
        int nbase = thisReg1.getNaxes();
        int next = thisReg2.getNaxes();
        int result = OVERLAP_UNKNOWN;
        FrameSet fs = thisPrism.convert(that, "");
        if (fs != null) {
            Mapping map2 = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Mapping map1 = thisPrism.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            Mapping smap = CmpMap.transientCmpMap(map1, map2, true).simplify();
            MapSplit esplit = smap.mapSplit(indexRange(nbase, next));
            if (esplit != null && esplit.map.getNout() == next) {
                Frame thatExt = that.pickAxes(next, plusOne(esplit.outputs, next), null);
                if (thatExt instanceof Region) {
                    MapSplit bsplit = smap.mapSplit(indexRange(0, nbase));
                    if (bsplit != null && bsplit.map.getNout() == nbase) {
                        Frame thatBase = that.pickAxes(nbase, plusOne(bsplit.outputs, nbase), null);
                        if (thatBase instanceof Region) {
                            Mapping emap = esplit.map;
                            emap.invert();
                            Region thatExt2 = ((Region) thatExt).mapRegionInto(emap, thisReg2.regFrame());
                            Mapping bmap = bsplit.map;
                            bmap.invert();
                            Region thatBase2 = ((Region) thatBase).mapRegionInto(bmap, thisReg1.regFrame());
                            int rbase = thisReg1.overlap(thatBase2);
                            int rext = thisReg2.overlap(thatExt2);
                            result = negationAdjusted(RTABLE[rbase][rext], thisNeg, that.getNegated());
                        }
                    }
                }
            }
        }
        if (result == OVERLAP_UNKNOWN) {
            result = RegionOverlap.overlap(thisPrism, that);
        }
        return result;
    }

    /** The first {@code n} of {@code idx}, each plus one. */
    private static int[] plusOne(int[] idx, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = idx[i] + 1;
        }
        return out;
    }

    /** The code of the overlap of un-negated Prisms adjusted for the negation of either (prism.c {@code Overlap}). */
    private static int negationAdjusted(int result, boolean thisNeg, boolean thatNeg) {
        if (thisNeg) {
            if (thatNeg) {
                if (result == OVERLAP_NONE) {
                    return OVERLAP_PARTIAL;
                } else if (result == OVERLAP_INSIDE) {
                    return OVERLAP_OUTSIDE;
                } else if (result == OVERLAP_OUTSIDE) {
                    return OVERLAP_INSIDE;
                }
                return result;
            }
            if (result == OVERLAP_NONE) {
                return OVERLAP_OUTSIDE;
            } else if (result == OVERLAP_INSIDE) {
                return OVERLAP_PARTIAL;
            } else if (result == OVERLAP_OUTSIDE) {
                return OVERLAP_NONE;
            } else if (result == OVERLAP_SAME) {
                return OVERLAP_NEGATE;
            } else if (result == OVERLAP_NEGATE) {
                return OVERLAP_SAME;
            }
            return result;
        } else if (thatNeg) {
            if (result == OVERLAP_NONE) {
                return OVERLAP_INSIDE;
            } else if (result == OVERLAP_INSIDE) {
                return OVERLAP_NONE;
            } else if (result == OVERLAP_OUTSIDE) {
                return OVERLAP_PARTIAL;
            } else if (result == OVERLAP_SAME) {
                return OVERLAP_NEGATE;
            } else if (result == OVERLAP_NEGATE) {
                return OVERLAP_SAME;
            }
            return result;
        }
        return result;
    }
    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * Simplifies the Prism (C's {@code Simplify}, prism.c). The two components in parallel are
     * simplified, which merges neighbouring Regions ({@link RegionMerge}); a Region, or a pair of
     * Regions other than the components, that results is mapped into the current Frame and
     * simplified. Failing that, a component whose axes the Mapping from the base to the current
     * Frame keeps apart is mapped into its current Frame axes and simplified: the Prism is that
     * Region, negated if the Prism is and with its axes permuted into their place, if the other
     * component has no current Frame axes; and otherwise, if either component simplifies, the
     * Prism of the two, so negated and permuted. An uncertainty set on the Prism is given to the
     * result, which then has the Region-generic simplification and takes this Prism's Negated,
     * Closed, MeshSize, FillFactor and Adaptive values.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping simplifyStep() {
        Region reg1 = region1;
        Region reg2 = region2;
        boolean neg = getNegated();
        FrameSet fs = getRegFS();
        Frame cfrm = fs.getFrame(FrameSet.AST__CURRENT);
        Mapping bcmap = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping smap = CmpMap.transientCmpMap(reg1, reg2, false).simplify();
        Region nw = null;
        if (smap instanceof Region) {
            nw = (Region) smap;
        } else if (smap instanceof CmpMap) {
            boolean[] flags = new boolean[3];
            Mapping[] parts = smap.decomposeInto(flags);
            if (!flags[0]
                    && parts[0] instanceof Region
                    && parts[1] instanceof Region
                    && (parts[0] != reg1 || parts[1] != reg2)) {
                nw = new Prism((Region) parts[0], (Region) parts[1]);
            }
        }
        if (nw != null) {
            nw = (Region) nw.mapRegionInto(bcmap, cfrm).simplify();
        } else {
            int nax1 = reg1.getNaxes();
            int nax2 = reg2.getNaxes();
            MapSplit split1 = bcmap.mapSplit(indexRange(0, nax1));
            MapSplit split2 = bcmap.mapSplit(indexRange(nax1, nax2));
            nw = this;
            int naxout1 = split1 != null ? split1.map.getNout() : 0;
            int naxout2 = split2 != null ? split2.map.getNout() : 0;
            int naxt = bcmap.getNout();
            if (naxout1 == naxt && naxout2 == 0) {
                nw = onAxes(reg1, split1, cfrm, neg, naxout1);
            } else if (naxout1 == 0 && naxout2 == naxt) {
                nw = onAxes(reg2, split2, cfrm, neg, naxout2);
            } else if (split1 != null && split2 != null) {
                Frame newfrm1 = cfrm.pickAxes(naxout1, plusOne(split1.outputs, naxout1), null);
                Region newreg1 = reg1.mapRegionInto(split1.map, newfrm1);
                Mapping snewreg1 = newreg1.simplify();
                Frame newfrm2 = cfrm.pickAxes(naxout2, plusOne(split2.outputs, naxout2), null);
                Region newreg2 = reg2.mapRegionInto(split2.map, newfrm2);
                Mapping snewreg2 = newreg2.simplify();
                if (snewreg1 != newreg1 || snewreg2 != newreg2) {
                    nw = new Prism((Region) snewreg1, (Region) snewreg2);
                    if (neg) {
                        nw.negate();
                    }
                    int[] perm = new int[naxout1 + naxout2];
                    for (int i = 0; i < naxout1; i++) {
                        perm[i] = split1.outputs[i] + 1;
                    }
                    for (int i = naxout1; i < naxout1 + naxout2; i++) {
                        perm[i] = split2.outputs[i - naxout1] + 1;
                    }
                    nw.permAxes(perm);
                }
            }
        }
        if (testUnc()) {
            nw.setUnc(getUnc(false));
        }
        Region result = nw.parentSimplify();
        if (result != this) {
            result.regOverlay(this, false);
        }
        return result;
    }

    /**
     * A component of a Prism alone, as prism.c {@code Simplify} makes it when the other component
     * has no current Frame axes: mapped by its split of the Mapping into the current Frame axes it
     * has, simplified, negated if the Prism is, and with its axes permuted into their place.
     */
    private static Region onAxes(Region reg, MapSplit split, Frame cfrm, boolean neg, int naxout) {
        Frame newfrm = cfrm.pickAxes(naxout, plusOne(split.outputs, naxout), null);
        Region newreg = reg.mapRegionInto(split.map, newfrm);
        Region nw = (Region) newreg.simplify();
        if (neg) {
            nw.negate();
        }
        nw.permAxes(plusOne(split.outputs, naxout));
        return nw;
    }

    /**
     * A Region split into a Prism of two Regions of separate current Frame axes, if one of them
     * simplifies (C's {@code astConvertToPrism}, prism.c): each subset of the current Frame axes is
     * tried in turn, in the order the bits of a counter give, until both it and the remaining axes
     * can be picked from the Region as Regions ({@link Region#pickAxes}) and one of the two
     * simplifies. The Prism of the two simplified Regions, its axes permuted into the Region's
     * order and given the Region's current Frame, is then simplified. If no subset will do, the
     * Region itself is returned.
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    static Region convertToPrism(Region reg) {
        Mapping map = reg.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        int nout = map.getNout();
        int[] ax = new int[nout];
        Region result = null;
        int topmask = 1 << nout;
        for (int bitmask = 1; bitmask < topmask && result == null; bitmask++) {
            int nsel = 0;
            int mask = 1;
            for (int axis = 0; axis < nout; axis++) {
                if ((bitmask & mask) != 0) {
                    ax[nsel++] = axis;
                }
                mask <<= 1;
            }
            Frame pickfrm1 = reg.pickAxes(nsel, plusOne(ax, nsel), new Mapping[1]);
            if (pickfrm1 instanceof Region) {
                nsel = 0;
                mask = 1;
                for (int axis = 0; axis < nout; axis++) {
                    if ((bitmask & mask) == 0) {
                        ax[nsel++] = axis;
                    }
                    mask <<= 1;
                }
                Frame pickfrm2 = reg.pickAxes(nsel, plusOne(ax, nsel), new Mapping[1]);
                if (pickfrm2 instanceof Region) {
                    Mapping sp1 = pickfrm1.simplify();
                    Mapping sp2 = pickfrm2.simplify();
                    if (sp1 != pickfrm1 || sp2 != pickfrm2) {
                        Prism prism = new Prism((Region) sp1, (Region) sp2);
                        int[] perm = new int[nout];
                        Arrays.fill(perm, -1);
                        for (int i = 0; i < nsel; i++) {
                            perm[ax[i]] = i + (nout - nsel);
                        }
                        nsel = 0;
                        for (int i = 0; i < nout; i++) {
                            if (perm[i] == -1) {
                                perm[i] = nsel++;
                            }
                        }
                        prism.permAxes(plusOne(perm, nout));
                        Region newprism = prism.mapRegionInto(new UnitMap(nout), reg.regFrame());
                        result = (Region) newprism.simplify();
                    }
                }
            }
        }
        return result == null ? reg : result;
    }
}
