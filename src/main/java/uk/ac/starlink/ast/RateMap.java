package uk.ac.starlink.ast;

import java.util.Objects;

/**
 * A Mapping which represents a single element of the Jacobian matrix of another Mapping, the
 * "encapsulated Mapping". The number of inputs of a RateMap is the number of inputs of the
 * encapsulated Mapping, and it has one output: the rate of change of a chosen output of the
 * encapsulated Mapping with respect to a chosen input, at the input position.
 *
 * <p>A RateMap that has not been inverted defines no inverse transformation; an inverted one
 * defines an inverse transformation but no forward transformation.
 *
 * <p>A RateMap does not copy the Mapping it is given: it holds it, as C's holds a cloned pointer
 * to it, and {@link #copy} copies it. Being shared, the Mapping's attributes that C makes
 * immutable once cloned (such as a ZoomMap's Zoom) can no longer be changed. The RateMap records
 * the Mapping's Invert attribute when it is made and uses the Mapping in that orientation whatever
 * is later done to the Mapping it shares; its other attributes do change the RateMap, so the
 * Mapping should not be changed while the RateMap is in use.
 *
 * <p>Ported from Rust {@code mappings/ratemap.rs} and C {@code ratemap.c}.
 */
public class RateMap extends Mapping {

    /** The encapsulated Mapping. */
    private Mapping map;

    /** The Invert attribute of the encapsulated Mapping when the RateMap was made (C's {@code invert}). */
    private final boolean invert;

    /** The zero-based index of the encapsulated Mapping's input that is varied (C's {@code iin}). */
    private final int iin;

    /** The zero-based index of the encapsulated Mapping's output that is differentiated (C's {@code iout}). */
    private final int iout;

    /**
     * Creates a RateMap.
     *
     * @param map the encapsulated Mapping, which must be able to transform in the forward
     *     direction
     * @param ax1 the index of the output of the encapsulated Mapping whose rate of change is
     *     wanted, the numerator of the Jacobian element; the first axis has index 1
     * @param ax2 the index of the input of the encapsulated Mapping that is varied, the
     *     denominator of the Jacobian element; the first axis has index 1
     * @throws AstException with status {@code AST__INTRD} if {@code map} cannot transform in the
     *     forward direction, or {@code AST__INNCO} if {@code ax1} is not an output of {@code map}
     *     or {@code ax2} not one of its inputs
     */
    public RateMap(Mapping map, int ax1, int ax2) {
        // astRateMapId_ (ratemap.c) gives astInitRateMap the indices from zero.
        this(map, ax1 - 1, ax2 - 1, true);
    }

    /**
     * C's {@code astRateMap_} and {@code astInitRateMap} (ratemap.c), with zero-based indices:
     * shares {@code map} and records its Invert attribute.
     *
     * @param zeroBased distinguishes this constructor from the public one
     */
    RateMap(Mapping map, int iout, int iin, boolean zeroBased) {
        super(check(map, iout, iin), 1, true, false);
        map.markShared();
        this.map = map;
        invert = map.getInvert();
        this.iout = iout;
        this.iin = iin;
    }

    /**
     * The number of inputs of a RateMap of {@code map}, after the checks C's {@code
     * astInitRateMap} makes, in its order: the first error found is the one reported.
     */
    private static int check(Mapping map, int iout, int iin) {
        Objects.requireNonNull(map, "map");
        if (!map.getTranForward()) {
            throw Ast.err(
                    AstException.AST__INTRD,
                    "astInitRateMap(RateMap): The supplied Mapping is not able to transform coordinates in the "
                            + "forward direction.");
        }
        int nin = map.getNin();
        int nout = map.getNout();
        if (iout < 0 || iout >= nout) {
            throw Ast.err(
                    AstException.AST__INNCO,
                    "astInitRateMap(RateMap): The output axis " + (iout + 1)
                            + " is out of range - it should be in the range 1 to " + nout + ".");
        }
        if (iin < 0 || iin >= nin) {
            throw Ast.err(
                    AstException.AST__INNCO,
                    "astInitRateMap(RateMap): The input axis " + (iin + 1)
                            + " is out of range - it should be in the range 1 to " + nin + ".");
        }
        return nin;
    }

    /**
     * Loads a RateMap from its description, as C's {@code astLoadRateMap} does: {@code Inv},
     * {@code IIn} and {@code IOut} (zero if absent) and the encapsulated Mapping {@code Map}.
     *
     * @throws AstException with status {@code AST__LDERR} if the Mapping is missing or is not a
     *     Mapping
     */
    RateMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("RateMap");
        invert = part.readInt("Inv", 0) != 0;
        iin = part.readInt("IIn", 0);
        iout = part.readInt("IOut", 0);
        NativeNode desc = part.readObject("Map");
        if (desc == null) {
            throw Ast.err(AstException.AST__LDERR, "astLoadRateMap(RateMap): The Mapping Map is missing.");
        }
        AstObject obj = ObjectRegistry.loadNested(desc, "Map");
        if (!(obj instanceof Mapping)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadRateMap(RateMap): The component Map is a " + obj.astClass() + ", not a Mapping.");
        }
        map = (Mapping) obj;
    }

    @Override
    String dumpComment() {
        return "Differential Mapping";
    }

    /**
     * Writes {@code IIn} and {@code IOut} (set when not zero), {@code Inv}, the Invert value
     * recorded when the RateMap was made (set when true), and the encapsulated Mapping, with its
     * own Invert attribute as it now is, as C's {@code Dump} (ratemap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeInt("IIn", iin != 0, false, iin, "Index of Mapping input");
        node.writeInt("IOut", iout != 0, false, iout, "Index of Mapping output");
        node.writeInt(
                "Inv",
                invert,
                false,
                invert ? 1 : 0,
                invert ? "Mapping used in inverse direction" : "Mapping used in forward direction");
        node.writeObject("Map", true, true, NativeNode.of(map), "Mapping to be differentiated");
    }

    /** Gives the copy a copy of the encapsulated Mapping, as C's {@code Copy} (ratemap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        map = (Mapping) map.copy();
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /** The encapsulated Mapping, shared with the RateMap. */
    Mapping getMap() {
        return map;
    }

    /** The Invert value the RateMap applies the encapsulated Mapping with. */
    boolean getRecordedInvert() {
        return invert;
    }

    /** The zero-based index of the encapsulated Mapping's input that is varied. */
    int getIin() {
        return iin;
    }

    /** The zero-based index of the encapsulated Mapping's output that is differentiated. */
    int getIout() {
        return iout;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (ratemap.c): the one output at each point is the rate of change of the
     * encapsulated Mapping's chosen output with respect to its chosen input there, found
     * by {@code astRate} with the Mapping set for the duration to the Invert value recorded when the
     * RateMap was made. Every point is passed to {@code astRate}, bad values included, as the rate
     * of some Mappings does not depend on the position.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        // validateTransformInto has refused the direction a RateMap does not define, which is C's
        // AST__INTER report in Transform.
        int ncoord = in.length;
        double[] work = new double[ncoord];
        double[] pout = out[0];
        int oldInv = map.swapInvert(invert);
        try {
            for (int p = 0; p < npoint; p++) {
                for (int c = 0; c < ncoord; c++) {
                    work[c] = in[c][p];
                }
                pout[p] = map.rate(work, iout + 1, iin + 1);
            }
        } finally {
            map.restoreInvert(oldInv);
        }
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * ratemap.c {@code Equal}: {@code that} is a RateMap with the same numbers of inputs and
     * outputs and the same Invert attribute, the same indices, and an encapsulated Mapping equal
     * ({@link #astEqual}) to this one's, each set for the duration to the Invert value its RateMap
     * recorded. RateMaps whose Invert attributes differ are never equal. The Invert attributes of
     * the Mappings are set back afterwards in C's order.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof RateMap)) {
            return false;
        }
        RateMap other = (RateMap) that;
        int nin = getNin();
        int nout = getNout();
        if (other.getNin() != nin || other.getNout() != nout || getInvert() != other.getInvert()) {
            return false;
        }
        // C reads both Invert values before setting either.
        int thisInv = map.swapInvert(map.getInvert());
        int thatInv = other.map.swapInvert(other.map.getInvert());
        try {
            map.setInvert(invert);
            other.map.setInvert(other.invert);
            return map.astEqual(other.map) && iin == other.iin && iout == other.iout;
        } finally {
            map.restoreInvert(thisInv);
            other.map.restoreInvert(thatInv);
        }
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * ratemap.c {@code MapMerge}. With the encapsulated Mapping set, for the duration, to the
     * Invert value recorded when the RateMap was made, it is simplified; if that gives a different
     * Mapping, the RateMap is replaced by a RateMap of the simplified Mapping, the entry's Invert
     * value unchanged. Otherwise, if the list is in series, a RateMap beside it (the one before it
     * first) that the list applies with the other Invert value, of the same indices, whose
     * encapsulated Mapping in its recorded orientation is equal ({@link #astEqual}) to this one's,
     * cancels with it: both are replaced by UnitMaps applied forward, of this RateMap's number of
     * outputs, as the list applies it, when the other is before it, and of its number of inputs
     * when the other is after it.
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int mapInv = swapInvert(list.invert(where));
        int oldInv = map.swapInvert(invert);
        try {
            Mapping smap = map.simplify();
            if (smap != map) {
                list.set(where, new RateMap(smap, iout, iin, true), list.invert(where));
                return where;
            }
            if (!series) {
                return -1;
            }
            int cancel = -1;
            int naxes = 0;
            if (where > 0 && cancels(list, where, where - 1)) {
                cancel = where - 1;
                naxes = getNout();
            }
            if (cancel == -1 && where + 1 < list.size() && cancels(list, where, where + 1)) {
                cancel = where + 1;
                naxes = getNin();
            }
            if (cancel == -1) {
                return -1;
            }
            list.set(where, new UnitMap(naxes), false);
            list.set(cancel, new UnitMap(naxes), false);
            return Math.min(cancel, where);
        } finally {
            map.restoreInvert(oldInv);
            restoreInvert(mapInv);
        }
    }

    /**
     * Whether the entry {@code other} of the list cancels this RateMap, the entry {@code where}:
     * a RateMap the list applies with the other Invert value, of the same indices, whose
     * encapsulated Mapping, set for the duration to the Invert value its RateMap recorded, is equal
     * to this one's in its recorded orientation, as C's {@code MapMerge} (ratemap.c) compares them.
     */
    private boolean cancels(MergeList list, int where, int other) {
        if (!(list.map(other) instanceof RateMap) || list.invert(where) == list.invert(other)) {
            return false;
        }
        RateMap rmap2 = (RateMap) list.map(other);
        if (iin != rmap2.iin || iout != rmap2.iout) {
            return false;
        }
        Mapping emap2 = rmap2.map;
        int oldWinv = rmap2.swapInvert(list.invert(other));
        int oldInv2 = emap2.swapInvert(rmap2.invert);
        try {
            return map.astEqual(emap2);
        } finally {
            emap2.restoreInvert(oldInv2);
            rmap2.restoreInvert(oldWinv);
        }
    }

    // ------------------------------------------------------------------
    // Splitting
    // ------------------------------------------------------------------

    /**
     * C's {@code MapSplit} (ratemap.c): the base split ({@link MapSplit#baseSplit}) if there is
     * one; otherwise, for a RateMap that is not inverted, the encapsulated Mapping, in its recorded
     * orientation, is split for the chosen inputs, and if the input varied is among them and the
     * output differentiated among the outputs of the split, the split is the RateMap of the split
     * Mapping for those, with the one output, output 0. The Mapping's Invert attribute is set for
     * the duration and set back afterwards.
     */
    @Override
    MapSplit splitOff(int[] in) {
        MapSplit result = MapSplit.baseSplit(this, in);
        if (result != null || getInvert()) {
            return result;
        }
        int oldInv = map.swapInvert(invert);
        try {
            MapSplit esplit = map.mapSplit(in);
            if (esplit != null) {
                int ax2 = -1;
                for (int i = 0; i < in.length; i++) {
                    if (in[i] == iin) {
                        ax2 = i;
                        break;
                    }
                }
                int ax1 = -1;
                int nout = esplit.map.getNout();
                for (int i = 0; i < nout; i++) {
                    if (esplit.outputs[i] == iout) {
                        ax1 = i;
                        break;
                    }
                }
                if (ax1 != -1 && ax2 != -1) {
                    result = new MapSplit(new int[] {0}, new RateMap(esplit.map, ax1, ax2, true));
                }
            }
        } finally {
            map.restoreInvert(oldInv);
        }
        return result;
    }
}
