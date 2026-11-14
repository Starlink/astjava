package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * The list of Mappings a simplification works on, applied in series or in parallel: C's {@code
 * map_list} and {@code invert_list}, the arrays its {@code MapMerge} methods receive, from Rust
 * {@code MergeCtx} ({@code mappings/simplify.rs}).
 *
 * <p>Each entry is a Mapping and the value of the Invert attribute to apply it with. As in C, the
 * Mapping is the object itself, usually shared with the Mapping being simplified, and its own Invert
 * attribute is left as it is: the entry's value says how it is applied, and the Mapping's
 * orientation as created ({@link Mapping#createdNin}, its parameters) is what a merge reads.
 */
final class MergeList {

    private final List<Mapping> maps;
    private final List<Boolean> inverts;

    /**
     * Whether only Mappings with the AllowSimplify flag may be nominated (C's {@code restricted},
     * cmpmap.c {@code Simplify}).
     */
    private boolean restricted;

    /** A list holding {@code maps}, each applied with the Invert value at the same index of {@code inverts}. */
    MergeList(List<Mapping> maps, List<Boolean> inverts) {
        if (maps.size() != inverts.size()) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "MergeList: " + maps.size() + " Mappings but " + inverts.size()
                            + " Invert values (internal AST programming error).");
        }
        this.maps = new ArrayList<>(maps);
        this.inverts = new ArrayList<>(inverts);
    }

    /** The number of Mappings in the list (C's {@code *nmap}). */
    int size() {
        return maps.size();
    }

    /** The Mapping at {@code index}. */
    Mapping map(int index) {
        return maps.get(index);
    }

    /** The Invert value the Mapping at {@code index} is applied with. */
    boolean invert(int index) {
        return inverts.get(index);
    }

    /** Replaces the entry at {@code index}. */
    void set(int index, Mapping map, boolean invert) {
        maps.set(index, map);
        inverts.set(index, invert);
    }

    /** Sets the Invert value the Mapping at {@code index} is applied with. */
    void setInvert(int index, boolean invert) {
        inverts.set(index, invert);
    }

    /** Inserts an entry at {@code index}, moving the entries from there up. */
    void insert(int index, Mapping map, boolean invert) {
        maps.add(index, map);
        inverts.add(index, invert);
    }

    /** Removes the entry at {@code index}, moving the entries above it down. */
    void removeAt(int index) {
        maps.remove(index);
        inverts.remove(index);
    }

    /**
     * Replaces the entries at {@code i1} and {@code i1 + 1} with {@code merged}, applied with the
     * Invert value {@code invert}, closing the gap.
     */
    void replacePairWith(int i1, Mapping merged, boolean invert) {
        set(i1, merged, invert);
        removeAt(i1 + 1);
    }

    /**
     * Replaces the entries from {@code imap1} to {@code imap2} inclusive with {@code merged}, applied
     * forward, closing the gap, as a C {@code MapMerge} condensing a run of Mappings does.
     */
    void replaceRangeWith(int imap1, int imap2, Mapping merged) {
        set(imap1, merged, false);
        for (int i = imap2; i > imap1; i--) {
            removeAt(i);
        }
    }

    /**
     * Offers the entries from {@code start} to the end of the list, as a list of their own, to the
     * merge of the Mapping at {@code subIndex} within them ({@link Simplifier#dispatchMapMerge}),
     * and puts what that leaves in their place, as C does when it passes a {@code MapMerge} the
     * arrays from an element onwards (Rust {@code MergeCtx::merge_subrange}).
     *
     * @return the index within the entries offered of the first one changed, or -1 if none was
     */
    int mergeSubrange(int start, int subIndex, boolean series) {
        List<Mapping> tailMaps = maps.subList(start, maps.size());
        List<Boolean> tailInverts = inverts.subList(start, inverts.size());
        MergeList sub = new MergeList(tailMaps, tailInverts);
        tailMaps.clear();
        tailInverts.clear();
        int modified;
        try {
            modified = Simplifier.dispatchMapMerge(sub.map(subIndex), sub, subIndex, series);
        } finally {
            maps.addAll(sub.maps);
            inverts.addAll(sub.inverts);
        }
        return modified;
    }

    /** The Mappings of the list, in order. */
    List<Mapping> asList() {
        return maps;
    }

    /** Whether only Mappings with the AllowSimplify flag may be nominated. */
    boolean isRestricted() {
        return restricted;
    }

    /** Sets whether only Mappings with the AllowSimplify flag may be nominated. */
    void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    /**
     * Whether the Mapping at {@code index} may be nominated: it is not frozen, and the
     * simplification is not restricted or the Mapping allows it (cmpmap.c {@code Simplify}).
     */
    boolean mayNominate(int index) {
        Mapping m = maps.get(index);
        return !m.getFrozen() && (!restricted || m.getAllowSimplify());
    }

    /**
     * Makes the Mappings of the list independent of each other, as C's {@code SeparateMappings}
     * (cmpmap.c) does: a Mapping appearing more than once is replaced by a copy at each later place.
     */
    // C compares the pointers: the same object, not an equal one.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    void separate() {
        int n = maps.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (maps.get(j) == maps.get(i)) {
                    maps.set(j, (Mapping) maps.get(i).copy());
                }
            }
        }
    }
}
