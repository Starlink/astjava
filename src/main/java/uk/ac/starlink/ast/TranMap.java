package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * A Mapping which combines the forward transformation of one Mapping with the inverse
 * transformation of another, ignoring the transformation of each that is not used (which need not
 * exist). The forward transformation of the TranMap is the forward transformation of the first
 * Mapping given when it was made, and its inverse transformation is the inverse transformation of
 * the second.
 *
 * <p>A TranMap does not copy its components: it holds the Mappings it is given, as C's holds
 * cloned pointers to them, and {@link #copy} copies them. Being shared, a component's attributes
 * that C makes immutable once cloned (such as a ZoomMap's Zoom) can no longer be changed. It
 * records each component's Invert attribute when it is made, and applies each component in that
 * orientation whatever is later done to the Mapping it shares; the Invert attribute of a component
 * therefore does not change the TranMap. Its other attributes do, so a component should not be
 * changed while the TranMap is in use.
 *
 * <p>Ported from Rust {@code mappings/tranmap.rs} and C {@code tranmap.c}.
 */
public class TranMap extends Mapping {

    /** The Mapping whose forward transformation is the TranMap's forward transformation. */
    private Mapping map1;

    /** The Mapping whose inverse transformation is the TranMap's inverse transformation. */
    private Mapping map2;

    /** The Invert attribute of the first component when the TranMap was made (C's {@code invert1}). */
    private final boolean invert1;

    /** The Invert attribute of the second component when the TranMap was made (C's {@code invert2}). */
    private final boolean invert2;

    /**
     * Creates a TranMap.
     *
     * <p>The two Mappings must have the same numbers of input and of output coordinates. Both
     * directions of the TranMap are defined.
     *
     * @param map1 the first component Mapping, which defines the forward transformation
     * @param map2 the second component Mapping, which defines the inverse transformation
     * @throws AstException with status {@code AST__INTRD} if {@code map1} cannot transform in the
     *     forward direction or {@code map2} cannot transform in the inverse direction, or {@code
     *     AST__INNCO} if the numbers of coordinates do not match
     */
    public TranMap(Mapping map1, Mapping map2) {
        super(count(map1, map2, true), count(map1, map2, false), true, true);
        map1.markShared();
        map2.markShared();
        this.map1 = map1;
        this.map2 = map2;
        invert1 = map1.getInvert();
        invert2 = map2.getInvert();
    }

    /**
     * The number of input coordinates of a TranMap of {@code map1} and {@code map2} if {@code in},
     * otherwise of output coordinates, after the checks C's {@code astInitTranMap} makes, in its
     * order: the first error found is the one reported.
     */
    private static int count(Mapping map1, Mapping map2, boolean in) {
        Objects.requireNonNull(map1, "map1");
        Objects.requireNonNull(map2, "map2");
        if (!map1.getTranForward()) {
            throw Ast.err(
                    AstException.AST__INTRD,
                    "astInitTranMap(TranMap): The first supplied Mapping is not able to transform coordinates in the "
                            + "forward direction.");
        }
        if (!map2.getTranInverse()) {
            throw Ast.err(
                    AstException.AST__INTRD,
                    "astInitTranMap(TranMap): The second supplied Mapping is not able to transform coordinates in "
                            + "the inverse direction.");
        }
        int nout = map1.getNout();
        if (map2.getNout() != nout) {
            throw Ast.err(
                    AstException.AST__INNCO,
                    "astInitTranMap(TranMap): The number of output coordinates per point (" + nout
                            + ") for the first Mapping supplied does not match the number of output coordinates ("
                            + map2.getNout() + ") for the second Mapping.");
        }
        int nin = map1.getNin();
        if (map2.getNin() != nin) {
            throw Ast.err(
                    AstException.AST__INNCO,
                    "astInitTranMap(TranMap): The number of input coordinates per point (" + nin
                            + ") for the first Mapping supplied does not match the number of input coordinates ("
                            + map2.getNin() + ") for the second Mapping.");
        }
        return in ? nin : nout;
    }

    /**
     * Loads a TranMap from its description, as C's {@code astLoadTranMap} does: {@code InvA} and
     * {@code InvB} (false if absent) and the components {@code MapA} and {@code MapB}.
     *
     * @throws AstException with status {@code AST__LDERR} if a component is missing or is not a
     *     Mapping
     */
    TranMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("TranMap");
        invert1 = part.readInt("InvA", 0) != 0;
        invert2 = part.readInt("InvB", 0) != 0;
        map1 = component(part, "MapA");
        map2 = component(part, "MapB");
    }

    /** The component Mapping {@code key} describes. */
    private static Mapping component(NativeNode part, String key) {
        NativeNode desc = part.readObject(key);
        if (desc == null) {
            throw Ast.err(
                    AstException.AST__LDERR, "astLoadTranMap(TranMap): The component Mapping " + key + " is missing.");
        }
        AstObject obj = ObjectRegistry.loadNested(desc, key);
        if (!(obj instanceof Mapping)) {
            throw Ast.err(
                    AstException.AST__LDERR,
                    "astLoadTranMap(TranMap): The component " + key + " is a " + obj.astClass() + ", not a Mapping.");
        }
        return (Mapping) obj;
    }

    @Override
    String dumpComment() {
        return "Compound Transformation Mapping";
    }

    /**
     * Writes {@code InvA} and {@code InvB} (set when true), the Invert values recorded when the
     * TranMap was made, and the components, each with its own Invert attribute as it now is, as
     * C's {@code Dump} (tranmap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        node.writeInt(
                "InvA",
                invert1,
                false,
                invert1 ? 1 : 0,
                invert1 ? "First Mapping used in inverse direction" : "First Mapping used in forward direction");
        node.writeInt(
                "InvB",
                invert2,
                false,
                invert2 ? 1 : 0,
                invert2 ? "Second Mapping used in inverse direction" : "Second Mapping used in forward direction");
        node.writeObject("MapA", true, true, NativeNode.of(map1), "Mapping for forward transformation");
        node.writeObject("MapB", true, true, NativeNode.of(map2), "Mapping for inverse transformation");
    }

    /** Gives the copy copies of the components, as C's {@code Copy} (tranmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        map1 = (Mapping) map1.copy();
        map2 = (Mapping) map2.copy();
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /** The first component, which gives the forward transformation, shared with the TranMap. */
    Mapping getMap1() {
        return map1;
    }

    /** The second component, which gives the inverse transformation, shared with the TranMap. */
    Mapping getMap2() {
        return map2;
    }

    /** The Invert value the TranMap applies its first component with. */
    boolean getInvert1() {
        return invert1;
    }

    /** The Invert value the TranMap applies its second component with. */
    boolean getInvert2() {
        return invert2;
    }

    /**
     * tranmap.c {@code RemoveRegions}: removes the Regions from both components. When neither
     * changed it is the TranMap itself. Otherwise each component result that is a Frame becomes a
     * UnitMap, whether or not that component changed, so a FrameSet component, a Frame in C, becomes
     * one too; two UnitMaps become one UnitMap with as many coordinates as the first, the TranMap's
     * own number; and otherwise it is a copy of the TranMap holding the new components. A component
     * that did not change is the original's own, shared, as C's copy holds a clone of it.
     */
    @Override
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"}) // C compares the pointers.
    Mapping removeRegions() {
        Mapping new1 = map1.removeRegions();
        Mapping new2 = map2.removeRegions();
        if (new1 == map1 && new2 == map2) {
            return this;
        }
        new1 = frameToUnitMap(new1);
        new2 = frameToUnitMap(new2);
        if (new1 instanceof UnitMap && new2 instanceof UnitMap) {
            return new UnitMap(new1.getNin());
        }
        TranMap result = (TranMap) copy();
        result.map1 = keptComponent(new1, map1);
        result.map2 = keptComponent(new2, map2);
        return result;
    }

    /**
     * The components, as C's {@code Decompose} (tranmap.c) gives them: an inverted TranMap gives
     * them in reverse order with their recorded Invert values reversed. C's {@code Decompose} does
     * not set the series flag, which its documentation gives as zero for a TranMap; it is false
     * here.
     */
    @Override
    Mapping[] decomposeInto(boolean[] flags) {
        flags[0] = false;
        if (getInvert()) {
            flags[1] = !invert2;
            flags[2] = !invert1;
            return new Mapping[] {map2, map1};
        }
        flags[1] = invert1;
        flags[2] = invert2;
        return new Mapping[] {map1, map2};
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        apply(in, npoint, forward, out, false);
    }

    /** NaN protocol: the component used transforms through its own {@link #transformIntoNan}. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        apply(in, npoint, forward, out, true);
    }

    /**
     * Applies the component C's {@code Transform} (tranmap.c) chooses: for the forward
     * transformation of the TranMap as created, the first component, forward, and for the inverse
     * the second, in its inverse direction, each in the orientation recorded when the TranMap was
     * made, whatever its Invert attribute now is.
     */
    private void apply(double[][] in, int npoint, boolean forward, double[][] out, boolean nan) {
        Mapping cmap;
        boolean dir;
        if (effectiveForward(forward)) {
            cmap = map1;
            dir = invert1 == map1.getInvert();
        } else {
            cmap = map2;
            dir = invert2 != map2.getInvert();
        }
        if (nan) {
            cmap.transformIntoNan(in, npoint, dir, out);
        } else {
            cmap.transformInto(in, npoint, dir, out);
        }
    }

    /**
     * The rate of the component that gives the TranMap's forward transformation as it is now
     * applied, as C's {@code Rate} (tranmap.c) asks it: the first component in its recorded
     * orientation, or, for an inverted TranMap, the second inverted from its recorded orientation.
     * As C does, the component's Invert attribute is set for the duration and then set back.
     */
    @Override
    double rateAt(double[] at, int ax1, int ax2) {
        Mapping cmap;
        boolean cinv;
        if (getInvert()) {
            cmap = map2;
            cinv = !invert2;
        } else {
            cmap = map1;
            cinv = invert1;
        }
        int oldInv = cmap.swapInvert(cinv);
        try {
            return cmap.rateAt(at, ax1, ax2);
        } finally {
            cmap.restoreInvert(oldInv);
        }
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * tranmap.c {@code Equal}: {@code that} is a TranMap with the same numbers of inputs and
     * outputs whose components, each set for the duration to the Invert value its TranMap recorded,
     * are equal ({@link #astEqual}) to this TranMap's: in order when the two TranMaps have the same
     * Invert attribute, and otherwise each inverted and in the other order. The Invert attributes
     * of the components are set back afterwards in C's order.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof TranMap)) {
            return false;
        }
        TranMap other = (TranMap) that;
        int nin = getNin();
        int nout = getNout();
        if (other.getNin() != nin || other.getNout() != nout) {
            return false;
        }
        // C reads all four Invert values before setting any of them.
        int thatInv1 = other.map1.swapInvert(other.map1.getInvert());
        int thatInv2 = other.map2.swapInvert(other.map2.getInvert());
        int thisInv1 = map1.swapInvert(map1.getInvert());
        int thisInv2 = map2.swapInvert(map2.getInvert());
        try {
            map1.setInvert(invert1);
            map2.setInvert(invert2);
            other.map1.setInvert(other.invert1);
            other.map2.setInvert(other.invert2);
            if (getInvert() == other.getInvert()) {
                return map1.astEqual(other.map1) && map2.astEqual(other.map2);
            }
            other.map1.invert();
            other.map2.invert();
            return map1.astEqual(other.map2) && map2.astEqual(other.map1);
        } finally {
            map1.restoreInvert(thisInv1);
            map2.restoreInvert(thisInv2);
            other.map1.restoreInvert(thatInv1);
            other.map2.restoreInvert(thatInv2);
        }
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * tranmap.c {@code MapMerge}. With its components set, for the duration, to the Invert values
     * recorded when it was made, the TranMap is first simplified on its own: applied inverted, it
     * is replaced by the forward TranMap of its components inverted and swapped; otherwise, if
     * simplifying either component gives a different Mapping, it is replaced by the TranMap of the
     * simplified components; otherwise, if both components are defined in both directions and the
     * first in series with the inverse of the second simplifies to a UnitMap, it is replaced by a
     * copy of the first component, the entry's Invert value unchanged. If none of those applies and
     * the list is in series, a TranMap after it in the list merges with it: the two forward
     * Mappings in series, and the two inverse Mappings in series in the same order, are each
     * simplified, and if either simplifies the pair is replaced by the TranMap of the two results,
     * applied forward.
     *
     * <p>As in C, the copy of the first component keeps the Invert value it was recorded with,
     * while the entry it replaces is applied forward, so that a TranMap whose equal components were
     * inverted when it was made is replaced by a Mapping that transforms the other way.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int result;
        int oldInv1 = map1.swapInvert(invert1);
        int oldInv2 = map2.swapInvert(invert2);
        try {
            if (list.invert(where)) {
                map1.invert();
                map2.invert();
                TranMap swapped = new TranMap(map2, map1);
                map1.invert();
                map2.invert();
                list.set(where, swapped, false);
                result = where;
            } else {
                result = simplifyAlone(list, where);
            }
            if (result == -1 && series && where < list.size() - 1 && list.map(where + 1) instanceof TranMap) {
                result = mergeWithHigher(list, where);
            }
        } finally {
            map1.restoreInvert(oldInv1);
            map2.restoreInvert(oldInv2);
        }
        return result;
    }

    /**
     * The part of {@link #mapMerge} that simplifies a TranMap the list applies forward on its own,
     * its components set to their recorded Invert values.
     *
     * @return {@code where} if the entry was replaced, or -1
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private int simplifyAlone(MergeList list, int where) {
        Mapping smapF = map1.simplify();
        Mapping smapI = map2.simplify();
        if (smapF != map1 || smapI != map2) {
            list.set(where, new TranMap(smapF, smapI), list.invert(where));
            return where;
        }
        if (map1.getTranForward() && map1.getTranInverse() && map2.getTranForward() && map2.getTranInverse()) {
            map2.invert();
            CmpMap cmap = CmpMap.transientCmpMap(map1, map2, true);
            map2.invert();
            Mapping smap = cmap.simplify();
            if (smap instanceof UnitMap) {
                Mapping copy = (Mapping) map1.copy();
                if (list.invert(where)) {
                    copy.invert();
                }
                list.set(where, copy, list.invert(where));
                return where;
            }
        }
        return -1;
    }

    /**
     * The part of {@link #mapMerge} that merges a TranMap with the TranMap after it in a series
     * list, each applied as the list gives it.
     *
     * @return {@code where} if the pair was replaced, or -1
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private int mergeWithHigher(MergeList list, int where) {
        TranMap hmap = (TranMap) list.map(where + 1);
        Mapping hmap1 = hmap.map1;
        int oldHinv1 = hmap1.swapInvert(hmap.invert1);
        Mapping hmap2 = hmap.map2;
        int oldHinv2 = hmap2.swapInvert(hmap.invert2);
        TranMap merged;
        try {
            Mapping mapF;
            Mapping mapI;
            if (list.invert(where)) {
                mapF = map2;
                mapI = map1;
                mapF.invert();
                mapI.invert();
            } else {
                mapF = map1;
                mapI = map2;
            }
            Mapping hmapF;
            Mapping hmapI;
            if (list.invert(where + 1)) {
                hmapF = hmap2;
                hmapI = hmap1;
                hmapF.invert();
                hmapI.invert();
            } else {
                hmapF = hmap1;
                hmapI = hmap2;
            }
            CmpMap cmapF = CmpMap.transientCmpMap(mapF, hmapF, true);
            Mapping smapF = cmapF.simplify();
            CmpMap cmapI = CmpMap.transientCmpMap(mapI, hmapI, true);
            Mapping smapI = cmapI.simplify();
            if (cmapF != smapF || cmapI != smapI) {
                // A CmpMap that did not simplify is kept by the new TranMap, so its components are
                // shared as C's reference counts then have them.
                keep(cmapF, smapF);
                keep(cmapI, smapI);
                merged = new TranMap(smapF, smapI);
            } else {
                merged = null;
            }
        } finally {
            hmap1.restoreInvert(oldHinv1);
            hmap2.restoreInvert(oldHinv2);
        }
        if (merged == null) {
            return -1;
        }
        list.set(where, merged, false);
        list.removeAt(where + 1);
        return where;
    }

    /** Marks the components of {@code cmap} shared if {@code kept} is {@code cmap} itself. */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static void keep(CmpMap cmap, Mapping kept) {
        if (kept == cmap) {
            cmap.getMap1().markShared();
            cmap.getMap2().markShared();
        }
    }

    // ------------------------------------------------------------------
    // Splitting
    // ------------------------------------------------------------------

    /**
     * C's {@code MapSplit} (tranmap.c): the base split ({@link MapSplit#baseSplit}) if there is
     * one; otherwise the Mapping giving the TranMap's forward transformation as it is now applied,
     * in its recorded orientation, is split for the chosen inputs, and the Mapping giving its
     * inverse, inverted, is split for the outputs that gives; if that leads back to exactly the
     * chosen inputs, in order, the split is the TranMap of the forward split and the inverse of
     * the inverse split. The components' Invert attributes are set for the duration and set back
     * afterwards in C's order.
     */
    @Override
    MapSplit splitOff(int[] in) {
        MapSplit result = MapSplit.baseSplit(this, in);
        if (result != null) {
            return result;
        }
        Mapping fmap;
        boolean finv;
        Mapping imap;
        boolean iinv;
        if (!getInvert()) {
            fmap = map1;
            finv = invert1;
            imap = map2;
            iinv = invert2;
        } else {
            imap = map1;
            iinv = !invert1;
            fmap = map2;
            finv = !invert2;
        }
        int oldFinv = fmap.swapInvert(finv);
        int oldIinv = imap.swapInvert(iinv);
        try {
            MapSplit fsplit = fmap.mapSplit(in);
            if (fsplit != null) {
                int nout = fsplit.map.getNout();
                int[] out = Arrays.copyOf(fsplit.outputs, nout);
                imap.invert();
                MapSplit isplit;
                try {
                    isplit = imap.mapSplit(out);
                } finally {
                    imap.invert();
                }
                if (isplit != null) {
                    Mapping rimap = isplit.map;
                    rimap.invert();
                    if (rimap.getNin() == in.length && sameInputs(in, isplit.outputs)) {
                        result = new MapSplit(out, new TranMap(fsplit.map, rimap));
                    }
                }
            }
        } finally {
            fmap.restoreInvert(oldFinv);
            imap.restoreInvert(oldIinv);
        }
        return result;
    }

    /** Whether the first {@code in.length} elements of {@code ires} are those of {@code in}. */
    private static boolean sameInputs(int[] in, int[] ires) {
        for (int i = 0; i < in.length; i++) {
            if (in[i] != ires[i]) {
                return false;
            }
        }
        return true;
    }
}
