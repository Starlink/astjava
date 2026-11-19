package uk.ac.starlink.ast;

import java.util.List;
import java.util.Objects;

/**
 * A Region combining two Regions with a Boolean operator. With {@link #AST__AND}, a point is in
 * the CmpRegion when it is in both component Regions; with {@link #AST__OR}, when it is in either.
 * C's XOR operator, 3, is accepted too: a point is then in the CmpRegion when it is in one
 * component but not both. Other combinations can be made by negating a component first.
 *
 * <p>The two Regions need not be in the same Frame, but a conversion from the Frame of the second
 * to that of the first must exist: the second is mapped into the Frame of the first, which is the
 * Frame the CmpRegion represents. The CmpRegion holds copies of the Regions given, so that a later
 * change to them does not change it. If either Region has an uncertainty set, that of the first
 * such is used by default for the CmpRegion.
 *
 * <p>Setting Closed or MeshSize on a CmpRegion sets it on both its components too, and clearing it
 * clears it on them; the CmpRegion takes the first component's MeshSize and Closed when they are
 * set. Its default FillFactor is the first component's.
 *
 * <p>Ported from C {@code cmpregion.c}, with Rust {@code regions/cmpregion.rs}. C's {@code
 * RegTrace}, {@code RegSplit} and the boundary breaks they use are not ported (they serve Plot).
 */
public class CmpRegion extends Region {

    /** The Boolean AND operator: a point is in the CmpRegion when it is in both components. */
    public static final int AST__AND = 1;

    /** The Boolean OR operator: a point is in the CmpRegion when it is in either component. */
    public static final int AST__OR = 2;

    /** C's Boolean XOR operator, which jniast does not name. */
    static final int AST__XOR = 3;

    /** The first component, in the base Frame of the CmpRegion, the CmpRegion's own. */
    private Region region1;

    /** The second component, in the base Frame of the CmpRegion, the CmpRegion's own. */
    private Region region2;

    /** The operator combining the components: {@link #AST__AND} or {@link #AST__OR}. */
    private int oper;

    /** The first Region XORed, when the CmpRegion is an XOR (C's {@code xor1}), or null. */
    private Region xor1;

    /** The second Region XORed, when the CmpRegion is an XOR (C's {@code xor2}), or null. */
    private Region xor2;

    /** Whether the CmpRegion is bounded, 0 or 1, kept once found, or {@link #UNSET}. */
    private int bounded = UNSET;

    /** What {@code astInitCmpRegion} works out before the Region part is made. */
    private static final class Parts {
        Frame frm;
        Region reg1;
        Region reg2;
        Region xor1;
        Region xor2;
        int oper;
    }

    /**
     * Creates a CmpRegion (C's {@code astCmpRegion}).
     *
     * @param region1 the first Region; a copy is held
     * @param region2 the second Region, which is mapped into the Frame of the first; a copy is held
     * @param oper the Boolean operator: {@link #AST__AND}, {@link #AST__OR}, or C's XOR, 3
     * @throws AstException with status {@code AST__INTRD} if {@code oper} is not one of these, or
     *     there is no conversion from the Frame of the second Region to that of the first
     * @throws NullPointerException if a Region is null
     */
    public CmpRegion(Region region1, Region region2, int oper) {
        this(parts(region1, region2, oper));
    }

    /** C's {@code astInitCmpRegion}, after the work {@link #parts} does. */
    // C's astInitCmpRegion sets MeshSize and Closed through the class's own setters once the
    // components are in place, so that the components take them too.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    private CmpRegion(Parts p) {
        super(p.frm, null, null);
        region1 = p.reg1;
        region2 = p.reg2;
        oper = p.oper;
        xor1 = p.xor1;
        xor2 = p.xor2;
        if (isUnitMap(region1.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT))) {
            region1.setRegionFS(false);
        }
        if (isUnitMap(region2.getRegFS().getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT))) {
            region2.setRegionFS(false);
        }
        if (region1.testMeshSize()) {
            setMeshSizeValue(region1.getMeshSize());
        }
        if (region1.testClosed()) {
            setClosedValue(region1.getClosed());
        }
    }

    /**
     * The first part of C's {@code astInitCmpRegion}: the operator checked, the Regions copied,
     * the second mapped into the Frame of the first unless the conversion is a UnitMap, and, for
     * XOR, the two ANDs made whose OR is the XOR.
     */
    private static Parts parts(Region region1, Region region2, int oper) {
        Objects.requireNonNull(region1, "region1");
        Objects.requireNonNull(region2, "region2");
        if (oper != AST__AND && oper != AST__OR && oper != AST__XOR) {
            throw Ast.err(
                    AstException.AST__INTRD,
                    "astInitCmpRegion(CmpRegion): Illegal boolean operator value (" + oper + ") supplied.");
        }
        Region reg1 = (Region) region1.copy();
        Region reg2 = (Region) region2.copy();
        FrameSet fs = reg2.convert(reg1, "");
        if (fs == null) {
            throw Ast.err(
                    AstException.AST__INTRD,
                    "astInitCmpRegion(CmpRegion): No Mapping can be found between the two supplied Regions.");
        }
        Parts p = new Parts();
        p.frm = fs.getFrame(FrameSet.AST__CURRENT);
        Mapping smap = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
        if (!isUnitMap(smap)) {
            reg2 = reg2.mapRegionInto(smap, p.frm);
        }
        if (oper == AST__XOR) {
            p.reg1 = andOf(reg1, true, reg2, false);
            p.reg2 = andOf(reg1, false, reg2, true);
            p.xor1 = reg1;
            p.xor2 = reg2;
            p.oper = AST__OR;
        } else {
            p.reg1 = reg1;
            p.reg2 = reg2;
            p.oper = oper;
        }
        return p;
    }

    /**
     * The AND of {@code a} and {@code b}, each negated for the while if asked, as C's XOR is made
     * of them; both are left as they were.
     */
    private static Region andOf(Region a, boolean nega, Region b, boolean negb) {
        if (nega) {
            a.negate();
        }
        if (negb) {
            b.negate();
        }
        try {
            return new CmpRegion(a, b, AST__AND);
        } finally {
            if (nega) {
                a.negate();
            }
            if (negb) {
                b.negate();
            }
        }
    }

    /**
     * Loads a CmpRegion from its description, as C's {@code astLoadCmpRegion} does: the Region
     * part, then {@code Operator} (AND if absent), {@code RegionA} and {@code RegionB}; an XOR is
     * made again from the two Regions XORed. A component read without its Frame is given the base
     * Frame of the CmpRegion, unless the CmpRegion was read without its own, when the Region that
     * holds it gives the components theirs.
     *
     * @throws AstException with status {@code AST__LDERR} if a component is missing or is not a
     *     Region
     */
    CmpRegion(NativeNode node) {
        super(node);
        NativeNode part = node.section("CmpRegion");
        int op = part.readInt("Operator", AST__AND);
        Region reg1 = component(part, "RegionA");
        Region reg2 = component(part, "RegionB");
        if (op == AST__XOR) {
            region1 = andOf(reg1, true, reg2, false);
            region2 = andOf(reg1, false, reg2, true);
            xor1 = reg1;
            xor2 = reg2;
            oper = AST__OR;
        } else {
            region1 = reg1;
            region2 = reg2;
            oper = op;
        }
        if (!regDummyFS()) {
            Frame f1 = baseFrame();
            if (region1.regDummyFS()) {
                region1.setRegFS(f1);
            }
            if (region2.regDummyFS()) {
                region2.setRegFS(f1);
            }
        }
    }

    /** A component Region read from a description. */
    private static Region component(NativeNode part, String key) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadCmpRegion(CmpRegion): The component Region " + key + " is missing.");
        }
        AstObject obj = ObjectRegistry.loadNested(desc, key);
        if (!(obj instanceof Region)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadCmpRegion(CmpRegion): The component " + key + " is a " + obj.astClass()
                            + ", not a Region.");
        }
        return (Region) obj;
    }

    @Override
    String dumpComment() {
        return "Combination of two Regions";
    }

    /**
     * Writes the CmpRegion class's part of a description (C's {@code Dump}, cmpregion.c): {@code
     * Operator}, {@code RegionA} and {@code RegionB}. A CmpRegion equivalent to an XOR ({@link
     * #xorCheck}) is written as the XOR of the two Regions XORed.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Region", "An area within a coordinate system");
        xorCheck();
        int op;
        Region reg1;
        Region reg2;
        if (xor1 != null) {
            op = AST__XOR;
            reg1 = xor1;
            reg2 = xor2;
        } else {
            op = oper;
            reg1 = region1;
            reg2 = region2;
        }
        String comment;
        if (op == AST__AND) {
            comment = "Regions combined using Boolean AND";
        } else if (op == AST__OR) {
            comment = "Regions combined using Boolean OR";
        } else if (op == AST__XOR) {
            comment = "Regions combined using Boolean XOR";
        } else {
            comment = "Regions combined using unknown operator";
        }
        node.writeInt("Operator", true, false, op, comment);
        node.writeObject("RegionA", true, true, NativeNode.of(reg1), "First component Region");
        node.writeObject("RegionB", true, true, NativeNode.of(reg2), "Second component Region");
    }

    /** Completes a copy as C's {@code Copy} (cmpregion.c) does: the components are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        region1 = (Region) region1.copy();
        region2 = (Region) region2.copy();
        if (xor1 != null) {
            xor1 = (Region) xor1.copy();
        }
        if (xor2 != null) {
            xor2 = (Region) xor2.copy();
        }
    }

    // ------------------------------------------------------------------
    // The components
    // ------------------------------------------------------------------

    /** The first component, the CmpRegion's own. */
    Region getRegion1() {
        return region1;
    }

    /** The second component, the CmpRegion's own. */
    Region getRegion2() {
        return region2;
    }

    /** The operator combining the components as the CmpRegion holds them: AND or OR. */
    int getOperator() {
        return oper;
    }

    /**
     * The components and operator that are equivalent to the CmpRegion with Negated {@code neg}
     * (C's {@code GetRegions}, cmpregion.c), each component replaced by its negation ({@link
     * #getNegation}) where it must be negated: a negated AND is the OR of the negated components,
     * and a negated OR their AND.
     */
    private Region[] regions(boolean neg, int[] op) {
        boolean neg1 = region1.getNegated();
        boolean neg2 = region2.getNegated();
        op[0] = oper;
        if (neg) {
            if (oper == AST__AND) {
                op[0] = AST__OR;
            } else if (oper == AST__OR) {
                op[0] = AST__AND;
            } else {
                throw unknownOperator("GetRegions");
            }
            neg1 = !neg1;
            neg2 = !neg2;
        }
        Region reg1 = neg1 != region1.getNegated() ? region1.getNegation() : region1;
        Region reg2 = neg2 != region2.getNegated() ? region2.getNegation() : region2;
        return new Region[] {reg1, reg2};
    }

    private AstException unknownOperator(String method) {
        return Ast.err(
                AstException.AST__INTER,
                method + "(" + astClass() + "): The " + astClass() + " refers to an unknown boolean operator with "
                        + "identifier " + oper + " (internal AST programming error).");
    }

    /**
     * The components and operator of the CmpRegion, flattened (C's protected {@code
     * astCmpRegionList}): a component that is a CmpRegion with the same operator, and not an XOR,
     * gives its own list; otherwise the component is listed. A CmpRegion equivalent to an XOR
     * lists the two Regions XORed.
     *
     * @param list receives the Regions, the CmpRegions' own, appended
     * @return the operator combining them: {@link #AST__AND}, {@link #AST__OR} or C's XOR
     */
    int cmpRegionList(List<Region> list) {
        xorCheck();
        if (xor1 != null) {
            list.add(xor1);
            list.add(xor2);
            return AST__XOR;
        }
        addToList(region1, list);
        addToList(region2, list);
        return oper;
    }

    private void addToList(Region reg, List<Region> list) {
        if (reg instanceof CmpRegion) {
            CmpRegion cmpreg = (CmpRegion) reg;
            cmpreg.xorCheck();
            if (cmpreg.oper == oper && cmpreg.xor1 == null) {
                cmpreg.cmpRegionList(list);
                return;
            }
        }
        list.add(reg);
    }

    /**
     * Notes whether the CmpRegion is equivalent to an XOR (C's {@code XORCheck}): an OR of two
     * ANDs, the first of A and B, the second of not-A and not-B in either order. If it is, copies
     * of A and B are kept, one negated so that neither is.
     */
    void xorCheck() {
        if (xor1 != null) {
            return;
        }
        if (oper == AST__OR && region1 instanceof CmpRegion && region2 instanceof CmpRegion) {
            CmpRegion c1 = (CmpRegion) region1;
            CmpRegion c2 = (CmpRegion) region2;
            if (c1.oper == AST__AND && c2.oper == AST__AND) {
                boolean xor = false;
                c1.region1.negate();
                try {
                    if (c1.region1.astEqual(c2.region1)) {
                        xor = negatedEqual(c1.region2, c2.region2);
                    } else if (c1.region1.astEqual(c2.region2)) {
                        xor = negatedEqual(c1.region2, c2.region1);
                    }
                } finally {
                    c1.region1.negate();
                }
                if (xor) {
                    xor1 = (Region) c1.region1.copy();
                    xor2 = (Region) c1.region2.copy();
                    if (xor1.getNegated()) {
                        xor1.negate();
                    } else {
                        xor2.negate();
                    }
                }
            }
        }
    }

    /** Whether {@code a}, negated for the while, equals {@code b}. */
    private static boolean negatedEqual(Region a, Region b) {
        a.negate();
        try {
            return a.astEqual(b);
        } finally {
            a.negate();
        }
    }

    /**
     * The two components, in series, as C's {@code Decompose} (cmpregion.c) gives them, with
     * their Invert values reversed if the CmpRegion is inverted.
     */
    @Override
    Mapping[] decomposeInto(boolean[] flags) {
        flags[0] = true;
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
     * cmpregion.c {@code Equal}: region.c's tests, then equal components and the same operator.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!super.equalTo(that)) {
            return false;
        }
        CmpRegion other = (CmpRegion) that;
        return region1.astEqual(other.region1) && region2.astEqual(other.region2) && oper == other.oper;
    }

    // ------------------------------------------------------------------
    // Attributes shared with the components
    // ------------------------------------------------------------------

    /** Sets MeshSize on the CmpRegion and both its components (cmpregion.c {@code SetMeshSize}). */
    @Override
    void setMeshSizeValue(int value) {
        super.setMeshSizeValue(value);
        region1.setMeshSizeValue(value);
        region2.setMeshSizeValue(value);
    }

    /** Clears MeshSize on the CmpRegion and both its components (cmpregion.c {@code ClearMeshSize}). */
    @Override
    void clearMeshSize() {
        super.clearMeshSize();
        region1.clearMeshSize();
        region2.clearMeshSize();
    }

    /** Sets Closed on the CmpRegion and both its components (cmpregion.c {@code SetClosed}). */
    @Override
    void setClosedValue(boolean value) {
        super.setClosedValue(value);
        region1.setClosedValue(value);
        region2.setClosedValue(value);
    }

    /** Clears Closed on the CmpRegion and both its components (cmpregion.c {@code ClearClosed}). */
    @Override
    void clearClosed() {
        super.clearClosed();
        region1.clearClosed();
        region2.clearClosed();
    }

    /**
     * Gets the FillFactor attribute: if not set, the first component's (cmpregion.c {@code
     * GetFillFactor}).
     *
     * @return the FillFactor attribute
     */
    @Override
    public double getFillFactor() {
        return testFillFactor() ? super.getFillFactor() : region1.getFillFactor();
    }

    /**
     * Sets a Frame attribute without remapping (cmpregion.c {@code RegSetAttrib}): region.c's, then
     * the base Frame setting on both components, whose current Frame is the base Frame of the
     * CmpRegion; an attribute a component does not have is ignored, and then the second
     * component is not tried, as in C.
     */
    @Override
    String regSetAttrib(String attrib, String value) {
        String battrib = super.regSetAttrib(attrib, value);
        try {
            region1.regSetAttrib(battrib, value);
            region2.regSetAttrib(battrib, value);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        return battrib;
    }

    /**
     * Clears a Frame attribute without remapping (cmpregion.c {@code RegClearAttrib}), as {@link
     * #regSetAttrib} sets one.
     */
    @Override
    String regClearAttrib(String attrib) {
        String battrib = super.regClearAttrib(attrib);
        try {
            region1.regClearAttrib(battrib);
            region2.regClearAttrib(battrib);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        return battrib;
    }

    /**
     * Discards what the CmpRegion has worked out (cmpregion.c {@code ResetCache}): whether it is
     * bounded, and what its components and the Region part keep.
     */
    @Override
    void resetCache() {
        bounded = UNSET;
        if (region1 != null) {
            region1.resetCache();
        }
        if (region2 != null) {
            region2.resetCache();
        }
        super.resetCache();
    }

    /**
     * Gives the CmpRegion a FrameSet of {@code frm} (cmpregion.c {@code SetRegFS}), and each
     * component read without its Frame the same.
     */
    @Override
    void setRegFS(Frame frm) {
        super.setRegFS(frm);
        if (region1 != null && !region1.getRegionFS()) {
            region1.setRegFS(frm);
        }
        if (region2 != null && !region2.getRegionFS()) {
            region2.setRegFS(frm);
        }
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /**
     * Which base Frame points are in the CmpRegion (cmpregion.c {@code Transform}): each component,
     * negated as {@link #regions} gives it, transforms the points, and a point is in an AND if both
     * leave some one coordinate good, and in an OR if either does.
     */
    @Override
    boolean[] regBaseMask(double[][] base, int npoint) {
        return inside(base, npoint, getNegated());
    }

    /** Which base Frame points are in the CmpRegion as if it were not negated. */
    @Override
    boolean[] regBaseInside(double[][] base, int npoint) {
        return inside(base, npoint, false);
    }

    private boolean[] inside(double[][] base, int npoint, boolean neg) {
        int[] op = new int[1];
        Region[] regs = regions(neg, op);
        double[][] ps1 = regs[0].transform(base, npoint, false);
        double[][] ps2 = regs[1].transform(base, npoint, false);
        int ncoord = base.length;
        boolean[] mask = new boolean[npoint];
        for (int point = 0; point < npoint; point++) {
            boolean good = false;
            for (int coord = 0; coord < ncoord; coord++) {
                boolean g1 = ps1[coord][point] != AST__BAD;
                boolean g2 = ps2[coord][point] != AST__BAD;
                boolean g;
                if (op[0] == AST__AND) {
                    g = g1 && g2;
                } else if (op[0] == AST__OR) {
                    g = g1 || g2;
                } else {
                    throw unknownOperator("astTransform");
                }
                if (g) {
                    good = true;
                    break;
                }
            }
            mask[point] = good;
        }
        return mask;
    }

    /**
     * Whether the CmpRegion is bounded (cmpregion.c {@code GetBounded}), kept once found: an AND
     * is bounded if either component, negated as {@link #regions} gives it, is bounded, and, if
     * neither is, unless the components do not overlap, overlap partly, or are each other's
     * negation; an OR is bounded if both components are. An AND of two unbounded Regions one inside
     * the other is reported bounded, as C reports it, though it is the inner Region.
     */
    @Override
    boolean isBoundedInner() {
        if (bounded == UNSET) {
            int[] op = new int[1];
            Region[] regs = regions(getNegated(), op);
            boolean reg1b = regs[0].getBounded();
            boolean reg2b = regs[1].getBounded();
            boolean result;
            if (op[0] == AST__AND) {
                if (reg1b || reg2b) {
                    result = true;
                } else {
                    int overlap = regs[0].overlap(regs[1]);
                    result = overlap != OVERLAP_NONE && overlap != OVERLAP_PARTIAL && overlap != OVERLAP_NEGATE;
                }
            } else {
                result = reg1b && reg2b;
            }
            bounded = result ? 1 : 0;
        }
        return bounded != 0;
    }

    // ------------------------------------------------------------------
    // Bounds and boundary
    // ------------------------------------------------------------------

    /**
     * The bounding box of the un-negated CmpRegion in the base Frame (cmpregion.c {@code
     * RegBaseBox}): the extent of its boundary mesh if it is bounded; otherwise axis by axis from
     * the components' bounds, where the bounds of a negated component that are both finite exclude
     * an interval, which on more than one axis is taken as no limit.
     */
    @Override
    void regBaseBox(double[] lbnd, double[] ubnd) {
        if (getBounded()) {
            double[][] ptr = regBaseMesh();
            int npoint = ptr[0].length;
            for (int icoord = 0; icoord < ptr.length; icoord++) {
                lbnd[icoord] = Double.MAX_VALUE;
                ubnd[icoord] = -Double.MAX_VALUE;
                for (int ipoint = 0; ipoint < npoint; ipoint++) {
                    double p = ptr[icoord][ipoint];
                    if (p != AST__BAD) {
                        if (p < lbnd[icoord]) {
                            lbnd[icoord] = p;
                        }
                        if (p > ubnd[icoord]) {
                            ubnd[icoord] = p;
                        }
                    }
                }
            }
            return;
        }
        boolean neg1 = region1.getNegated();
        boolean neg2 = region2.getNegated();
        int numAxes = region1.getNaxes();
        double[][] b1 = region1.getRegionBounds();
        double[][] b2 = region2.getRegionBounds();
        double[] clbnd1 = b1[0];
        double[] cubnd1 = b1[1];
        double[] clbnd2 = b2[0];
        double[] cubnd2 = b2[1];
        for (int i = 0; i < numAxes; i++) {
            boolean inc1 = included(neg1, numAxes, clbnd1, cubnd1, i);
            boolean inc2 = included(neg2, numAxes, clbnd2, cubnd2, i);
            if (oper == AST__AND) {
                if (inc1) {
                    if (inc2) {
                        lbnd[i] = max(clbnd1[i], clbnd2[i]);
                        ubnd[i] = min(cubnd1[i], cubnd2[i]);
                    } else {
                        lbnd[i] = clbnd1[i] < clbnd2[i] ? clbnd1[i] : cubnd2[i];
                        ubnd[i] = cubnd1[i] > cubnd2[i] ? cubnd1[i] : clbnd2[i];
                    }
                } else if (inc2) {
                    lbnd[i] = clbnd2[i] < clbnd1[i] ? clbnd2[i] : cubnd1[i];
                    ubnd[i] = cubnd2[i] > cubnd1[i] ? cubnd2[i] : clbnd1[i];
                } else {
                    lbnd[i] = clbnd1[i] < clbnd2[i] ? clbnd1[i] : cubnd2[i];
                    ubnd[i] = cubnd1[i] > cubnd2[i] ? cubnd1[i] : clbnd2[i];
                }
            } else if (inc1 && inc2) {
                lbnd[i] = min(clbnd1[i], clbnd2[i]);
                ubnd[i] = max(cubnd1[i], cubnd2[i]);
            } else {
                lbnd[i] = -Double.MAX_VALUE;
                ubnd[i] = Double.MAX_VALUE;
            }
        }
    }

    /**
     * Whether the bounds of a component on axis {@code i} are an included interval, turning the
     * bounds of a negated component into one where they can be (part of cmpregion.c {@code
     * RegBaseBox}).
     */
    private static boolean included(boolean neg, int numAxes, double[] clbnd, double[] cubnd, int i) {
        if (!neg) {
            return true;
        }
        double lb = clbnd[i];
        double ub = cubnd[i];
        if (lb == -Double.MAX_VALUE) {
            clbnd[i] = ub;
        }
        if (ub == Double.MAX_VALUE) {
            cubnd[i] = lb;
        }
        if (lb != -Double.MAX_VALUE && ub != Double.MAX_VALUE) {
            if (numAxes == 1) {
                return false;
            }
            clbnd[i] = -Double.MAX_VALUE;
            cubnd[i] = Double.MAX_VALUE;
        }
        return true;
    }

    /** C's {@code astMAX}. */
    private static double max(double a, double b) {
        return a > b ? a : b;
    }

    /** C's {@code astMIN}. */
    private static double min(double a, double b) {
        return a < b ? a : b;
    }

    /**
     * The bounding box in the base Frame with the components taken as not negated (cmpregion.c
     * {@code RegBaseBox2}): the overlap of the components' boxes for an AND, their union otherwise.
     */
    @Override
    void regBaseBox2(double[] lbnd, double[] ubnd) {
        int numAxes = region1.getNaxes();
        double[] clbnd1 = new double[numAxes];
        double[] cubnd1 = new double[numAxes];
        double[] clbnd2 = new double[numAxes];
        double[] cubnd2 = new double[numAxes];
        region1.regionBounds2(clbnd1, cubnd1);
        region2.regionBounds2(clbnd2, cubnd2);
        for (int i = 0; i < numAxes; i++) {
            if (oper == AST__AND) {
                lbnd[i] = max(clbnd1[i], clbnd2[i]);
                ubnd[i] = min(cubnd1[i], cubnd2[i]);
            } else {
                lbnd[i] = min(clbnd1[i], clbnd2[i]);
                ubnd[i] = max(cubnd1[i], cubnd2[i]);
            }
        }
    }

    /**
     * The boundary mesh in the base Frame (cmpregion.c {@code RegBaseMesh}), kept once made: the
     * points of each component's mesh inside the other component for an AND, and outside it for an
     * OR, the components being negated in place and back again for that. A component with no mesh
     * of its own, unbounded whether negated or not, is meshed inside the bounding box of the other
     * ({@link #bndMesh}), which an OR does not allow. With no point kept, the mesh is one point with
     * every coordinate bad.
     *
     * @throws AstException with status {@code AST__INTER} if neither component has a mesh, or one
     *     has none and the operator is OR
     */
    @Override
    double[][] regBaseMesh() {
        double[][] kept = keptBaseMesh();
        if (kept != null) {
            return kept;
        }
        boolean hasMesh1 = hasMesh(region1);
        boolean hasMesh2 = hasMesh(region2);
        if (!hasMesh1 && !hasMesh2) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegBaseMesh(" + astClass() + "): No mesh can be produced for the " + astClass()
                            + " bacause neither of its component Regions has a mesh (internal AST programming error)."); // codespell:ignore bacause
        } else if ((!hasMesh1 || !hasMesh2) && oper == AST__OR) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astRegBaseMesh(" + astClass() + "): No mesh can be produced for the " + astClass()
                            + " bacause one its component Regions has no mesh and the union of the Regions is required " // codespell:ignore bacause
                            + "(internal AST programming error).");
        }
        double[][] mesh1;
        double[][] mesh2;
        if (hasMesh1) {
            mesh1 = region1.regMesh();
            if (hasMesh2) {
                mesh2 = region2.regMesh();
            } else {
                double[][] b = region1.getRegionBounds();
                mesh2 = region2.bndMesh(b[0], b[1]);
            }
        } else {
            mesh2 = region2.regMesh();
            double[][] b = region2.getRegionBounds();
            mesh1 = region1.bndMesh(b[0], b[1]);
        }
        boolean or = oper == AST__OR;
        double[][] mesh1b;
        double[][] mesh2b;
        if (or) {
            region1.negate();
            region2.negate();
        }
        try {
            mesh1b = region2.transform(mesh1, mesh1[0].length, true);
            mesh2b = region1.transform(mesh2, mesh2[0].length, true);
        } finally {
            if (or) {
                region1.negate();
                region2.negate();
            }
        }
        int nc = mesh1b.length;
        int np1 = mesh1b[0].length;
        int np2 = mesh2b[0].length;
        double[][] ptr = new double[nc][np1 + np2];
        int jp = goodPoints(mesh1b, np1, ptr, 0);
        jp = goodPoints(mesh2b, np2, ptr, jp);
        double[][] result = new double[nc][];
        if (jp == 0) {
            for (int ic = 0; ic < nc; ic++) {
                result[ic] = new double[] {AST__BAD};
            }
        } else {
            for (int ic = 0; ic < nc; ic++) {
                result[ic] = java.util.Arrays.copyOf(ptr[ic], jp);
            }
        }
        keepBaseMesh(result);
        return result;
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
     * Copies the points of {@code in} with no bad coordinate to {@code out} from point {@code jp},
     * returning the index after the last point copied.
     */
    private static int goodPoints(double[][] in, int np, double[][] out, int jp) {
        int nc = in.length;
        int j = jp;
        for (int ip = 0; ip < np; ip++) {
            boolean ok = true;
            for (int ic = 0; ic < nc; ic++) {
                if (in[ic][ip] == AST__BAD) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                for (int ic = 0; ic < nc; ic++) {
                    out[ic][j] = in[ic][ip];
                }
                j++;
            }
        }
        return j;
    }

    /**
     * The default uncertainty (cmpregion.c {@code GetDefUnc}): the first component's uncertainty
     * if one is set, otherwise the second's if set, otherwise region.c's default.
     */
    @Override
    Region getDefUnc() {
        if (region1.testUnc()) {
            return region1.getUncFrm(FrameSet.AST__CURRENT);
        } else if (region2.testUnc()) {
            return region2.getUncFrm(FrameSet.AST__CURRENT);
        }
        return super.getDefUnc();
    }

    /**
     * A CmpRegion of the chosen base Frame axes (cmpregion.c {@code RegBasePick}): the same pick
     * of each component, combined with the same operator, if both picks are Regions; null
     * otherwise.
     */
    @Override
    Region regBasePick(int[] axes) {
        int naxes = axes.length;
        int[] axes1 = new int[naxes];
        for (int i = 0; i < naxes; i++) {
            axes1[i] = axes[i] + 1;
        }
        Frame frm1 = region1.pickAxes(naxes, axes1, null);
        if (frm1 instanceof Region) {
            Frame frm2 = region2.pickAxes(naxes, axes1, null);
            if (frm2 instanceof Region) {
                return new CmpRegion((Region) frm1, (Region) frm2, oper);
            }
        }
        return null;
    }

    /**
     * Whether every point of {@code pset}, in the base Frame, is on the boundary of the CmpRegion
     * (cmpregion.c {@code RegPins}): for an AND, on the boundary of one component and inside the
     * other; for an OR, on the boundary of one and outside the other. Each component tests its own
     * boundary with {@code pointUnc} mapped into its base Frame.
     *
     * @throws AstException with status {@code AST__INTER} if {@code pointUnc} cannot be mapped into
     *     the Frame of a component
     */
    @Override
    boolean regPins(double[][] pset, Region pointUnc, boolean[] mask) {
        int np = pset[0].length;
        double[][] psetb1 = region1.regTransform(pset, false);
        Region unc1 = matchRegion(region1, pointUnc, "astRegPins");
        boolean[] mask1 = new boolean[np];
        region1.regPins(psetb1, unc1, mask1);
        double[][] psetb2 = region2.regTransform(pset, false);
        Region unc2 = matchRegion(region2, pointUnc, "astRegPins");
        boolean[] mask2 = new boolean[np];
        region2.regPins(psetb2, unc2, mask2);
        boolean or = oper == AST__OR;
        double[][] pset1;
        double[][] pset2;
        if (or) {
            region1.negate();
            region2.negate();
        }
        try {
            pset1 = region1.transform(pset, np, true);
            pset2 = region2.transform(pset, np, true);
        } finally {
            if (or) {
                region1.negate();
                region2.negate();
            }
        }
        double[] p1 = pset1[0];
        double[] p2 = pset2[0];
        boolean result = true;
        if (mask != null) {
            for (int ip = 0; ip < np; ip++) {
                if ((mask1[ip] && p2[ip] != AST__BAD) || (mask2[ip] && p1[ip] != AST__BAD)) {
                    mask[ip] = true;
                } else {
                    mask[ip] = false;
                    result = false;
                }
            }
        } else {
            for (int ip = 0; ip < np; ip++) {
                if ((!mask1[ip] || p2[ip] == AST__BAD) && (!mask2[ip] || p1[ip] == AST__BAD)) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * {@code that} mapped into the base Frame of {@code into} (cmpregion.c {@code MatchRegion} with
     * {@code AST__BASE}), or null if {@code that} is null. As C does, {@code into} is inverted for
     * the while, which changes only its Invert attribute: the conversion is found between the
     * current Frames of the two Regions.
     *
     * @throws AstException with status {@code AST__INTER} if there is no conversion
     */
    private static Region matchRegion(Region into, Region that, String method) {
        if (that == null) {
            return null;
        }
        FrameSet fs;
        into.invert();
        try {
            fs = that.convert(into, "");
        } finally {
            into.invert();
        }
        if (fs == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    method + "(" + into.astClass() + "): MatchRegion cannot convert between the two supplied "
                            + "coordinate Frames (internal AST programming error).");
        }
        return that.mapRegionInto(
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT), fs.getFrame(FrameSet.AST__CURRENT));
    }
}
