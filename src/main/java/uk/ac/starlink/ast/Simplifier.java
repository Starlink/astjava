package uk.ac.starlink.ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The simplification engine: C's base {@code Simplify} (mapping.c), CmpMap's {@code Simplify} with
 * its nominate loop (cmpmap.c), and the {@code astMapMerge} dispatcher (mapping.c), from Rust {@code
 * mappings/simplify.rs}.
 *
 * <p>A Mapping is simplified by decomposing it into a list of Mappings applied in series or in
 * parallel ({@link Mapping#mapList}), offering each Mapping of the list in turn the chance to merge
 * with its neighbours ({@link Mapping#mapMerge}), until a pass through the list changes nothing, and
 * building the result from what is left.
 */
final class Simplifier {

    /**
     * The Mappings this thread is simplifying further up its call stack, each with its description
     * once one has been needed (C's {@code simplify_stackmaps}, cmpmap.c).
     */
    private static final ThreadLocal<Deque<StackEntry>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /** The shortest history in which {@link #patternCheck} looks for a pattern (C's {@code PatternCheck}). */
    private static final int PATTERN_MIN_HISTORY = 29;

    /** The longest wavelength {@link #patternCheck} looks for. */
    private static final int PATTERN_MAX_WAVE = 50;

    /** The repeats of a pattern of wavelength 1 that {@link #patternCheck} needs. */
    private static final int PATTERN_REPEATS_WAVE1 = 30;

    /** The repeats of a longer pattern that {@link #patternCheck} needs. */
    private static final int PATTERN_REPEATS = 3;

    private Simplifier() {}

    /** A Mapping on the recursion stack, with the description it is compared by. */
    private static final class StackEntry {
        final Mapping map;
        private String description;

        StackEntry(Mapping map) {
            this.map = map;
        }

        String key() {
            if (description == null) {
                description = guardKey(map);
            }
            return description;
        }
    }

    /**
     * The description by which the recursion guard compares two Mappings: the native text without
     * the records of simplification, which change as a simplification goes on. C compares with
     * {@code astEqual}, which this library does not have; equal descriptions give equal Mappings.
     */
    private static String guardKey(Mapping m) {
        StringBuilder sb = new StringBuilder();
        for (String line : Channel.text(m, false, 0).split("\n", -1)) {
            if (!line.trim().startsWith("IsSimp =")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Whether a Mapping equal to {@code m} is being simplified further up this thread's stack (C's
     * check at the start of cmpmap.c {@code Simplify}).
     */
    // C compares the pointers: the same object, not an equal one.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static boolean onStack(Mapping m) {
        Deque<StackEntry> stack = STACK.get();
        if (stack.isEmpty()) {
            return false;
        }
        String key = null;
        for (StackEntry e : stack) {
            if (e.map == m) {
                return true;
            }
            if (key == null) {
                key = guardKey(m);
            }
            if (key.equals(e.key())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Decomposition and reconstruction
    // ------------------------------------------------------------------

    /**
     * The list of Mappings {@code m} decomposes into, applied in series if {@code series} and in
     * parallel otherwise (C's {@code astMapList} with the Mapping's own Invert value).
     *
     * @param simpler if not null, receives at index 0 whether the decomposition met an inverted
     *     CmpMap, which rebuilding the Mapping from the list then simplifies
     */
    static MergeList flatten(Mapping m, boolean series, boolean[] simpler) {
        List<Mapping> maps = new ArrayList<>();
        List<Boolean> inverts = new ArrayList<>();
        boolean s = m.mapList(series, m.getInvert(), maps, inverts);
        if (simpler != null) {
            simpler[0] = s;
        }
        return new MergeList(maps, inverts);
    }

    /**
     * The Mapping a list of more than one Mapping gives, as cmpmap.c {@code Simplify} builds it:
     * from the end of the list, each Mapping joined to what follows it in a CmpMap, with its Invert
     * attribute set to the value the list applies it with while the CmpMap is made and restored
     * afterwards; the AllowSimplify flag of each is cleared. A list of one Mapping gives a copy of
     * it with its Invert attribute set as the list applies it.
     */
    static Mapping reconstruct(MergeList list, boolean series) {
        int nmap = list.size();
        if (nmap == 1) {
            Mapping result = (Mapping) list.map(0).copy();
            if (list.invert(0)) {
                result.setInvert(true);
            } else {
                result.clearInvert();
            }
            return result;
        }
        Mapping last = list.map(nmap - 1);
        boolean setN = last.testInvert();
        boolean invertN = last.getInvert();
        last.setInvert(list.invert(nmap - 1));
        Mapping result = null;
        try {
            for (int i = nmap - 1; i >= 0; i--) {
                Mapping m = list.map(i);
                m.setAllowSimplify(false);
                if (result == null) {
                    result = m;
                } else {
                    boolean set = m.testInvert();
                    boolean invert = m.getInvert();
                    m.setInvert(list.invert(i));
                    try {
                        result = new CmpMap(m, result, series);
                    } finally {
                        restoreInvert(m, set, invert);
                    }
                }
            }
        } finally {
            restoreInvert(last, setN, invertN);
        }
        return result;
    }

    /** Puts back an Invert attribute that was set or cleared. */
    private static void restoreInvert(Mapping m, boolean set, boolean invert) {
        if (set) {
            m.setInvert(invert);
        } else {
            m.clearInvert();
        }
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * C's base {@code Simplify} (mapping.c): the Mapping alone in a list, offered repeatedly to its
     * own {@code MapMerge} until that changes nothing or makes more than one Mapping. The result is
     * the Mapping itself if nothing changed, and otherwise the one Mapping left, shared if the list
     * applies it with its own Invert value and copied with the list's value if not.
     */
    static Mapping baseSimplify(Mapping m) {
        if (m.getRestrictedSimplify() && !m.getAllowSimplify()) {
            return m;
        }
        MergeList list = flatten(m, true, null);
        list.map(0).clearFrozen();
        boolean simpler = false;
        while (!list.map(0).getFrozen()) {
            Mapping map = list.map(0);
            int modified = dispatchMapMerge(map, list, 0, true);
            if (list.size() > 1 || modified < 0) {
                break;
            }
            simpler = true;
        }
        if (!simpler || list.size() > 1) {
            return m;
        }
        Mapping only = list.map(0);
        if (list.invert(0) == only.getInvert()) {
            return only;
        }
        Mapping result = (Mapping) only.copy();
        if (list.invert(0)) {
            result.setInvert(true);
        } else {
            result.clearInvert();
        }
        return result;
    }

    /**
     * cmpmap.c {@code Simplify}: the CmpMap decomposed into the list of Mappings it applies in its
     * own kind of combination, the list driven through the nominate loop, and the result rebuilt
     * from the list if anything changed or the decomposition met an inverted CmpMap. A CmpMap equal
     * to one this thread is already simplifying is returned as it is.
     */
    static Mapping simplifyCmpMap(CmpMap cmp) {
        if (onStack(cmp)) {
            return cmp;
        }
        Deque<StackEntry> stack = STACK.get();
        stack.push(new StackEntry(cmp));
        try {
            boolean series = cmp.isSeries();
            boolean[] decomposed = new boolean[1];
            MergeList list = flatten(cmp, series, decomposed);
            boolean restricted = cmp.getRestrictedSimplify();
            // Under a restricted simplification it is not yet known whether an inverted CmpMap the
            // decomposition met may be simplified, so meeting one does not count.
            boolean simpler = !restricted && decomposed[0];
            list.setRestricted(restricted);
            list.separate();
            for (Mapping m : list.asList()) {
                m.clearFrozen();
            }
            if (nominateLoop(list, series)) {
                simpler = true;
            }
            if (!simpler) {
                return cmp;
            }
            return reconstruct(list, series);
        } finally {
            stack.pop();
        }
    }

    /**
     * The nominate loop of cmpmap.c {@code Simplify}: each Mapping of the list that is not frozen
     * (and, under a restricted simplification, allows it) is offered the chance to merge with its
     * neighbours; after a change the loop goes back to the first Mapping changed, and it ends when
     * a pass through the list changes nothing. A Mapping whose changes start to repeat is frozen,
     * which ends an argument between neighbours that undo each other's changes.
     *
     * @return whether the list changed
     */
    private static boolean nominateLoop(MergeList list, boolean series) {
        List<Long> mlist = new ArrayList<>();
        List<Long> nlist = new ArrayList<>();
        boolean any = false;
        boolean improved = true;
        while (improved) {
            improved = false;
            int nominated = 0;
            while (nominated < list.size()) {
                if (!list.mayNominate(nominated)) {
                    nominated++;
                    continue;
                }
                Mapping map = list.map(nominated);
                int modified = dispatchMapMerge(map, list, nominated, series);
                nominated++;
                if (modified < 0) {
                    continue;
                }
                // A pattern in the indices changed counts only if the list's length also repeats,
                // with a wavelength related to it by a whole factor.
                long wlen1 = patternCheck(modified, 1, mlist);
                long wlen2 = patternCheck(list.size(), wlen1, nlist);
                if (wlen1 != 0 && wlen2 != 0) {
                    if (wlen1 > wlen2) {
                        long t = wlen1;
                        wlen1 = wlen2;
                        wlen2 = t;
                    }
                    if (wlen2 % wlen1 != 0) {
                        wlen1 = 0;
                    }
                }
                if (wlen1 > 0) {
                    list.map(modified).setFrozen();
                } else {
                    improved = true;
                    any = true;
                    if (modified < nominated) {
                        nominated = modified;
                    }
                }
            }
        }
        return any;
    }

    /**
     * Appends {@code val} to {@code history} and gives the wavelength of a pattern repeating at its
     * end, or 0 if there is none: C's {@code PatternCheck} (cmpmap.c). This is C's heuristic for
     * recognising neighbours that undo each other's changes, kept with C's numbers: no pattern is
     * looked for until the history is longer than 29 entries, wavelengths up to a third of the
     * history and at most 50 are tried, and a pattern needs 3 repeats, or 30 for a wavelength of 1.
     *
     * @param check zero to append without looking for a pattern
     */
    static long patternCheck(long val, long check, List<Long> history) {
        long result = 0;
        int iat = history.size();
        history.add(val);
        if (check != 0 && history.size() > PATTERN_MIN_HISTORY) {
            int mxwave = Math.min(iat / 3, PATTERN_MAX_WAVE);
            int jlo = iat - 3 * mxwave;
            for (int jat = iat - 1; jat >= jlo; jat--) {
                if (history.get(jat) != val) {
                    continue;
                }
                int wavelen = iat - jat;
                int nwave = wavelen == 1 ? Math.min(PATTERN_REPEATS_WAVE1, iat) : PATTERN_REPEATS;
                if (nwave * wavelen <= history.size()) {
                    result = wavelen;
                    int len = history.size();
                    check:
                    for (int k = 0; k < wavelen; k++) {
                        int base0 = len - wavelen + k;
                        for (int iwave = 1; iwave < nwave; iwave++) {
                            if (!history.get(base0 - iwave * wavelen).equals(history.get(base0))) {
                                result = 0;
                                break check;
                            }
                        }
                    }
                }
                if (result != 0) {
                    break;
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Merging
    // ------------------------------------------------------------------

    /**
     * C's {@code astMapMerge}: offers the list to the nominated Mapping's own {@link
     * Mapping#mapMerge}, unless the Mapping must keep its identity ({@link
     * Mapping#doNotSimplify}). When the nominated Mapping has the AllowSimplify flag, the Mappings
     * the merge creates get the flag if every Mapping it removed from the list had it.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    static int dispatchMapMerge(Mapping nominee, MergeList list, int where, boolean series) {
        if (nominee.doNotSimplify()) {
            return -1;
        }
        if (!nominee.getAllowSimplify()) {
            return nominee.mapMerge(list, where, series);
        }
        List<Mapping> before = new ArrayList<>(list.asList());
        int result = nominee.mapMerge(list, where, series);
        if (result >= 0) {
            boolean allset = true;
            for (Mapping in : before) {
                if (!containsIdentical(list.asList(), in) && !in.getAllowSimplify()) {
                    allset = false;
                    break;
                }
            }
            if (allset) {
                for (Mapping out : list.asList()) {
                    if (!containsIdentical(before, out)) {
                        out.setAllowSimplify(true);
                    }
                }
            }
        }
        return result;
    }

    /** Whether {@code maps} holds {@code m} itself. */
    // C compares the pointers: the same object, not an equal one.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static boolean containsIdentical(List<Mapping> maps, Mapping m) {
        for (Mapping x : maps) {
            if (x == m) {
                return true;
            }
        }
        return false;
    }

    /**
     * The merge a class inherits (Rust's {@code default_map_merge}): the nominated Mapping merged
     * with the next one by {@link Mapping#tryMerge}, or, in series, the next one merged with it; the
     * merged Mapping replaces the pair, decomposed into the list if it is a CmpMap of the list's
     * kind.
     *
     * @return {@code where} if the pair was merged, otherwise -1
     */
    static int defaultMapMerge(MergeList list, int where, boolean series) {
        int i = where;
        if (i + 1 >= list.size()) {
            return -1;
        }
        Mapping m1 = list.map(i);
        Mapping m2 = list.map(i + 1);
        boolean inv1 = list.invert(i);
        boolean inv2 = list.invert(i + 1);
        int m1Nin = inv1 ? m1.createdNout() : m1.createdNin();
        Mapping merged = m1.tryMerge(m2, series, inv1, inv2);
        // In series a UnitMap is not merged into the next Mapping here: UnitMap's own merge removes
        // it from the list instead.
        if (merged == null && series && m1.getClass() != UnitMap.class) {
            Mapping reverse = m2.tryMerge(m1, series, inv2, inv1);
            if (reverse != null && reverse.getNin() == m1Nin) {
                merged = reverse;
            }
        }
        if (merged == null) {
            return -1;
        }
        if (merged instanceof CmpMap && ((CmpMap) merged).isSeries() == series) {
            MergeList expanded = flatten(merged, series, null);
            list.removeAt(i + 1);
            list.removeAt(i);
            for (int k = 0; k < expanded.size(); k++) {
                list.insert(i + k, expanded.map(k), expanded.invert(k));
            }
            return i;
        }
        list.replacePairWith(i, merged, merged.getInvert());
        return i;
    }
}
