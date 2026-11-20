package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Mapping that locates positions within one of a set of alternate Regions.
 *
 * <p>A SelectorMap is a Mapping that identifies which Region contains a given input position.
 *
 * <p>A SelectorMap encapsulates a number of Regions that all have the same number of axes and
 * represent the same coordinate Frame. The number of inputs (Nin attribute) of the SelectorMap
 * equals the number of axes spanned by one of the encapsulated Regions. All SelectorMaps have only
 * a single output. SelectorMaps do not define an inverse transformation.
 *
 * <p>For each input position, the forward transformation of a SelectorMap searches through the
 * encapsulated Regions (in the order supplied when the SelectorMap was created) until a Region is
 * found which contains the input position. The index associated with this Region is returned as the
 * SelectorMap output value (the index value is the position of the Region within the list of
 * Regions supplied when the SelectorMap was created, starting at 1 for the first Region). If an
 * input position is not contained within any Region, a value of zero is returned by the forward
 * transformation.
 *
 * <p>If a compound Mapping contains a SelectorMap in series with its own inverse, the combination
 * of the two adjacent SelectorMaps will be replaced by a UnitMap when the compound Mapping is
 * simplified using {@link #simplify}.
 *
 * <p>In practice, SelectorMaps are often used in conjunction with {@link SwitchMap}s.
 *
 * <p>A SelectorMap holds copies of the Regions it is given. As C's does, it tests a position
 * against each Region by negating the Region and reversing its Closed attribute for the duration,
 * so transforming points changes its own Regions while it runs, and afterwards leaves each with
 * the Negated and Closed attributes set (to the values they had), which its description then
 * shows. Like every Object, a SelectorMap is not safe for use by two threads at once, even to
 * transform points.
 *
 * <p>Ported from Rust {@code mappings/selectormap.rs} and C {@code selectormap.c}.
 */
public class SelectorMap extends Mapping {

    /** The Regions, copies of those given (C's {@code reg}). */
    private Region[] regs;

    /** The output value for an input position bad on any axis (C's {@code badval}). */
    private final double badval;

    /**
     * Creates a SelectorMap.
     *
     * @param regs the Regions. All the supplied Regions must relate to the same coordinate Frame.
     *     The number of axes in this coordinate Frame defines the number of inputs for the
     *     SelectorMap. The SelectorMap holds copies of them.
     * @param badval the value to be returned by the forward transformation of the SelectorMap for
     *     any input positions that have a bad ({@link #AST__BAD}) value on any axis
     * @throws NullPointerException if {@code regs} or one of its Regions is null
     * @throws AstException with status {@code AST__BDPAR} if no Regions are given, or {@code
     *     AST__BADNI} if a Region does not refer to the same coordinate Frame as the first
     */
    public SelectorMap(Region[] regs, double badval) {
        this(check(regs), regs, badval);
    }

    /**
     * C's {@code astInitSelectorMap} (selectormap.c), once {@link #check} has given the number of
     * inputs: one output, the forward transformation only, and copies of the Regions.
     */
    private SelectorMap(int nin, Region[] regs, double badval) {
        super(nin, 1, true, false);
        this.regs = new Region[regs.length];
        for (int i = 0; i < regs.length; i++) {
            this.regs[i] = (Region) regs[i].copy();
        }
        this.badval = badval;
    }

    /**
     * The number of inputs of a SelectorMap of these Regions, the first Region's, after the checks
     * C's {@code astSelectorMap} and {@code astInitSelectorMap} make, in their order: no Regions is
     * {@code AST__BDPAR}, and the first Region whose Frame ({@code astRegFrame}) is not equal to
     * the first Region's is {@code AST__BADNI}. A null array or Region is refused first.
     */
    private static int check(Region[] regs) {
        Objects.requireNonNull(regs, "regs");
        for (int i = 0; i < regs.length; i++) {
            Objects.requireNonNull(regs[i], "regs[" + i + "]");
        }
        int nreg = regs.length;
        if (nreg <= 0) {
            throw Ast.err(
                    AstException.AST__BDPAR,
                    "astSelectorMap(SelectorMap): Bad number of Regions (" + nreg + ") specified.");
        }
        Frame f0 = regs[0].regFrame();
        for (int i = 1; i < nreg; i++) {
            if (!regs[i].regFrame().astEqual(f0)) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astInitSelectorMap(SelectorMap): Region number " + (i + 1)
                                + " does not refer to the same coordinate Frame as the first Region.");
            }
        }
        return regs[0].getNin();
    }

    /**
     * Loads a SelectorMap from its description, as C's {@code astLoadSelectorMap} does: {@code
     * Reg1}, {@code Reg2} and so on until a Region is missing, then {@code BadVal} ({@link
     * #AST__BAD} if absent). A later Region written without its Frame, which reads with a stand-in
     * FrameSet ({@link Region#regDummyFS}), is given the first Region's FrameSet ({@link
     * Region#setRegFS}). As in C, the Regions' Frames are not compared.
     *
     * @throws AstException with status {@code AST__LDERR} if a component is not a Region or there
     *     is no Region; C fails on a description without Regions too, with {@code AST__OBJIN}, when
     *     it releases the first Region's FrameSet, which it never obtained
     */
    SelectorMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("SelectorMap");
        List<Region> list = new ArrayList<>();
        FrameSet fs = null;
        while (true) {
            String key = "Reg" + (list.size() + 1);
            NativeNode desc = part.readObject(key);
            if (desc == null) {
                break;
            }
            AstObject obj = ObjectRegistry.loadNested(desc, key);
            if (!(obj instanceof Region)) {
                throw Ast.err(
                        AstException.AST__LDERR,
                        "astLoadSelectorMap(SelectorMap): The component " + key + " is a " + obj.astClass()
                                + ", not a Region.");
            }
            Region reg = (Region) obj;
            if (list.isEmpty()) {
                fs = reg.getRegFS();
            } else if (reg.regDummyFS()) {
                reg.setRegFS(fs);
            }
            list.add(reg);
        }
        if (list.isEmpty()) {
            throw Ast.err(
                    AstException.AST__LDERR, "astLoadSelectorMap(SelectorMap): The SelectorMap holds no Regions.");
        }
        regs = list.toArray(new Region[0]);
        badval = part.readDouble("BadVal", AST__BAD);
    }

    @Override
    String dumpComment() {
        return "Region identification Mapping";
    }

    /**
     * Writes each Region, the second and later without their Frame, which the first Region's
     * gives them all when read back, and the bad value if it is not {@link #AST__BAD}, as C's
     * {@code Dump} (selectormap.c) does: each later Region's RegionFS is set to false while it is
     * written and cleared afterwards.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        for (int i = 0; i < regs.length; i++) {
            Region reg = regs[i];
            NativeNode desc;
            if (i > 0) {
                reg.setRegionFS(false);
                try {
                    desc = NativeNode.of(reg);
                } finally {
                    reg.clearRegionFS();
                }
            } else {
                desc = NativeNode.of(reg);
            }
            node.writeObject("Reg" + (i + 1), true, true, desc, "Region of input space");
        }
        if (badval != AST__BAD) {
            node.writeDouble("BadVal", true, true, badval, "Output value for bad input positions");
        }
    }

    /** Gives the copy copies of the Regions, as C's {@code Copy} (selectormap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        Region[] copies = new Region[regs.length];
        for (int i = 0; i < regs.length; i++) {
            copies[i] = (Region) regs[i].copy();
        }
        regs = copies;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (selectormap.c). Each output is -1 for a point bad on any input and 0
     * otherwise; then each Region in turn is negated and its Closed attribute reversed, so that its
     * transformation makes bad the points inside it, and the points it makes bad that have no index
     * yet are given its (one-based) index; its output, in which those points are bad, is what the
     * next Region transforms. Each Region's Closed attribute and negation are put back after it
     * transforms, so both are left set. Finally -1 becomes the bad value. A transformation in the
     * direction the SelectorMap does not define, which the checks refuse, writes nothing, as in C.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        if (!effectiveForward(forward)) {
            return;
        }
        int ncoord = in.length;
        double[][] ps1 = new double[ncoord][];
        for (int c = 0; c < ncoord; c++) {
            ps1[c] = Arrays.copyOf(in[c], npoint);
        }
        double[] pout = out[0];
        for (int p = 0; p < npoint; p++) {
            boolean bad = false;
            for (int c = 0; c < ncoord; c++) {
                if (isBad(ps1[c][p])) {
                    bad = true;
                    break;
                }
            }
            pout[p] = bad ? -1 : 0;
        }
        for (int ireg = 1; ireg <= regs.length; ireg++) {
            Region reg = regs[ireg - 1];
            reg.negate();
            boolean closed = reg.getClosed();
            reg.setClosedValue(!closed);
            try {
                double[][] ps2 = reg.transform(ps1, npoint, true);
                double[] p2 = ps2[0];
                for (int p = 0; p < npoint; p++) {
                    if (pout[p] == 0 && p2[p] == AST__BAD) {
                        pout[p] = ireg;
                    }
                }
                ps1 = ps2;
            } finally {
                reg.setClosedValue(closed);
                reg.negate();
            }
        }
        for (int p = 0; p < npoint; p++) {
            if (pout[p] == -1) {
                pout[p] = badval;
            }
        }
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * selectormap.c {@code Equal}: {@code that} is a SelectorMap with the same number of inputs as
     * each now has, the same number of Regions and a bad value equal to within {@code astEQUAL},
     * whose Regions are equal ({@link #astEqual}) in order. The Invert attributes are not compared,
     * so a 1-D SelectorMap is equal to its inverse.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof SelectorMap)) {
            return false;
        }
        SelectorMap other = (SelectorMap) that;
        if (other.getNin() != getNin()) {
            return false;
        }
        int nreg = regs.length;
        if (other.regs.length != nreg || !MappingHelpers.astEqual(other.badval, badval)) {
            return false;
        }
        for (int i = 0; i < nreg; i++) {
            if (!regs[i].astEqual(other.regs[i])) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * selectormap.c {@code MapMerge}. If simplifying any Region gives a different Region, the
     * SelectorMap is replaced by a new one of the simplified Regions, applied as the list applies
     * this one. Otherwise, in series, the SelectorMap after it in the list, or failing that the
     * one before it, is inverted, compared with it ({@link #astEqual}) and inverted back; if they
     * are equal the pair is replaced by a UnitMap of the lower one's number of inputs. As {@code
     * Equal} does not compare the Invert attributes, two equal 1-D SelectorMaps applied in the same
     * direction are replaced too, though their transformation is not a unit one.
     */
    // C compares the pointers: whether astSimplify returned the Region it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int nreg = regs.length;
        int result = -1;
        Region[] sreg = new Region[nreg];
        boolean simp = false;
        for (int i = 0; i < nreg; i++) {
            Mapping s = regs[i].simplify();
            if (!(s instanceof Region)) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astMapMerge(SelectorMap): Simplifying a " + regs[i].astClass() + " gave a " + s.astClass()
                                + ", not a Region (internal AST programming error).");
            }
            sreg[i] = (Region) s;
            simp = simp || sreg[i] != regs[i];
        }
        if (simp) {
            list.set(where, new SelectorMap(sreg, badval), list.invert(where));
            result = where;
        }
        if (result == -1 && series) {
            result = cancelWithNeighbour(this, list, where, SelectorMap.class);
        }
        return result;
    }
}
