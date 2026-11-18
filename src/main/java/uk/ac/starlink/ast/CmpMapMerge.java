package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CmpMap's {@code MapMerge} (cmpmap.c) and the helper it uses to combine two Mappings ({@code
 * CombineMaps}).
 *
 * <p>The nominated CmpMap is first simplified on its own, applied as the list applies it. If that
 * changes nothing, a CmpMap combining its components as the list does is replaced in the list by its
 * two components; otherwise it is merged with a neighbouring CmpMap (series CmpMaps side by side in
 * a parallel list become two parallel CmpMaps in series, and parallel CmpMaps one after the other in
 * a series list are regrouped into pairs that feed each other), and failing that a parallel CmpMap
 * is swapped with a PermMap before it whose outputs feed the CmpMap's two components from two
 * contiguous blocks of its inputs.
 */
final class CmpMapMerge {

    private CmpMapMerge() {}

    /**
     * cmpmap.c {@code MapMerge} for the CmpMap at {@code where} in {@code list}.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static int mapMerge(CmpMap cmpmap, MergeList list, int where, boolean series) {
        int result = -1;
        Mapping map = list.map(where);

        // Simplify the CmpMap on its own, applied as the list applies it, so that the
        // simplification can absorb an Invert value of true.
        boolean listInvert = list.invert(where);
        boolean invert = map.getInvert();
        Mapping simplified;
        boolean newInvert;
        int saved = invert != listInvert ? map.swapInvert(listInvert) : 0;
        try {
            simplified = map.simplify();
            newInvert = simplified.getInvert();
        } finally {
            if (invert != listInvert) {
                map.restoreInvert(saved);
            }
        }

        boolean simpler = simplified != map || newInvert != listInvert;
        if (simpler) {
            list.set(where, simplified, newInvert);
            return where;
        }

        // A CmpMap combining its components as the list does is replaced by its two components,
        // each with the Invert value the CmpMap recorded, whatever the list applies the CmpMap with.
        if (series == cmpmap.isSeries()) {
            list.set(where, cmpmap.getMap1(), cmpmap.getInvert1());
            list.insert(where + 1, cmpmap.getMap2(), cmpmap.getInvert2());
            result = where;
            simpler = true;
        }

        if (!simpler && list.size() > 1) {
            result = mergeNeighbour(list, where, series);
        }

        if (result == -1 && where > 0) {
            result = swapPermMap(list, where);
        }
        return result;
    }

    /**
     * The section of cmpmap.c {@code MapMerge} that merges the nominated CmpMap with a neighbouring
     * CmpMap: the one before it, or the one after it if it is first in the list.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    private static int mergeNeighbour(MergeList list, int where, boolean series) {
        int imap1 = where == 0 ? 0 : where - 1;
        int imap2 = where == 0 ? 1 : where;
        Mapping neighbour = list.map(where > 0 ? where - 1 : 1);
        if (neighbour.getClass() != CmpMap.class) {
            return -1;
        }
        CmpMap cmpmap1 = (CmpMap) list.map(imap1);
        CmpMap cmpmap2 = (CmpMap) list.map(imap2);
        boolean invert1 = list.invert(imap1);
        boolean invert2 = list.invert(imap2);
        boolean invert1a = cmpmap1.getInvert1() != invert1;
        boolean invert1b = cmpmap1.getInvert2() != invert1;
        boolean invert2a = cmpmap2.getInvert1() != invert2;
        boolean invert2b = cmpmap2.getInvert2() != invert2;

        Mapping merged = null;
        if (!series && cmpmap1.isSeries() && cmpmap2.isSeries()) {
            merged = seriesInParallel(cmpmap1, invert1, invert1a, invert1b, cmpmap2, invert2, invert2a, invert2b);
        } else if (series && !cmpmap1.isSeries() && !cmpmap2.isSeries()) {
            merged = parallelInSeries(cmpmap1, invert1, cmpmap2, invert2);
        }
        if (merged == null) {
            return -1;
        }
        list.replacePairWith(imap1, merged, false);
        return imap1;
    }

    /**
     * Series CmpMaps side by side in a parallel list: the two series are regrouped as two parallel
     * CmpMaps applied in series, each of which is simplified.
     *
     * @return the series CmpMap of the two simplified parallel CmpMaps, or null if neither simplified
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static Mapping seriesInParallel(
            CmpMap cmpmap1,
            boolean invert1,
            boolean invert1a,
            boolean invert1b,
            CmpMap cmpmap2,
            boolean invert2,
            boolean invert2a,
            boolean invert2b) {
        Mapping new1 = combineMaps(
                invert1 ? cmpmap1.getMap2() : cmpmap1.getMap1(),
                invert1 ? invert1b : invert1a,
                invert2 ? cmpmap2.getMap2() : cmpmap2.getMap1(),
                invert2 ? invert2b : invert2a,
                false);
        Mapping new2 = combineMaps(
                invert1 ? cmpmap1.getMap1() : cmpmap1.getMap2(),
                invert1 ? invert1a : invert1b,
                invert2 ? cmpmap2.getMap1() : cmpmap2.getMap2(),
                invert2 ? invert2a : invert2b,
                false);
        Mapping simp1 = new1.simplify();
        Mapping simp2 = new2.simplify();
        boolean simpler = simp1 != new1 || simp2 != new2 || simp1.getInvert() || simp2.getInvert();
        return simpler ? new CmpMap(simp1, simp2, true) : null;
    }

    /**
     * Parallel CmpMaps one after the other in a series list: each is decomposed into the Mappings it
     * applies in parallel, the two lists are divided into runs whose outputs and inputs match in
     * number, each run being combined unsimplified into a parallel CmpMap, and each pair of runs is
     * combined in series and simplified.
     *
     * @return the parallel CmpMap of the simplified pairs, or null if no pair simplified
     */
    // C compares the pointers: whether astSimplify returned the Mapping it was given.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static Mapping parallelInSeries(CmpMap cmpmap1, boolean invert1, CmpMap cmpmap2, boolean invert2) {
        MergeList list1 = parallelList(cmpmap1, invert1);
        MergeList list2 = parallelList(cmpmap2, invert2);
        int nmap1 = list1.size();
        int nmap2 = list2.size();
        // C assumes the first CmpMap has as many outputs as the second has inputs; a list a C defect
        // elsewhere has left with Mappings that do not fit would make it read past the end of a
        // list, so the two are not merged.
        int nout1 = 0;
        for (int i = 0; i < nmap1; i++) {
            nout1 += Mapping.appliedNout(list1.map(i), list1.invert(i));
        }
        int nin2 = 0;
        for (int i = 0; i < nmap2; i++) {
            nin2 += Mapping.appliedNin(list2.map(i), list2.invert(i));
        }
        if (nout1 != nin2) {
            return null;
        }

        boolean simpler = false;
        int jmap1 = 0;
        int jmap2 = 0;
        Mapping merged = null;
        Mapping submap1 = null;
        Mapping submap2 = null;
        boolean subinv1 = false;
        boolean subinv2 = false;
        while (jmap1 <= nmap1 && jmap2 <= nmap2) {
            int subout1 = appliedCount(submap1, subinv1, false);
            int subin2 = appliedCount(submap2, subinv2, true);
            if (subout1 < subin2) {
                submap1 = combineMaps(submap1, subinv1, list1.map(jmap1), list1.invert(jmap1), false);
                subinv1 = false;
                jmap1++;
            } else if (subin2 < subout1) {
                submap2 = combineMaps(submap2, subinv2, list2.map(jmap2), list2.invert(jmap2), false);
                subinv2 = false;
                jmap2++;
            } else {
                if (submap1 != null && submap2 != null) {
                    Mapping tmap = combineMaps(submap1, subinv1, submap2, subinv2, true);
                    Mapping tmap2 = tmap.simplify();
                    if (tmap != tmap2 || tmap.getInvert() != tmap2.getInvert()) {
                        simpler = true;
                    }
                    merged = merged == null ? tmap2 : new CmpMap(merged, tmap2, false);
                }
                if (jmap1 < nmap1) {
                    submap1 = list1.map(jmap1);
                    subinv1 = list1.invert(jmap1);
                    jmap1++;
                } else {
                    break;
                }
                if (jmap2 < nmap2) {
                    submap2 = list2.map(jmap2);
                    subinv2 = list2.invert(jmap2);
                    jmap2++;
                } else {
                    break;
                }
            }
        }
        return simpler ? merged : null;
    }

    /**
     * The Mappings a CmpMap applied with the Invert value {@code invert} applies in parallel ({@code
     * astMapList}), made independent of each other (C's {@code SeparateMappings}).
     */
    private static MergeList parallelList(CmpMap cmpmap, boolean invert) {
        List<Mapping> maps = new ArrayList<>();
        List<Boolean> inverts = new ArrayList<>();
        cmpmap.mapList(false, invert, maps, inverts);
        MergeList list = new MergeList(maps, inverts);
        list.separate();
        return list;
    }

    /**
     * The number of inputs (if {@code in}) or outputs of {@code map} applied with the Invert value
     * {@code invert}, or zero if there is no Mapping.
     */
    private static int appliedCount(Mapping map, boolean invert, boolean in) {
        if (map == null) {
            return 0;
        }
        boolean asIs = invert == map.getInvert();
        return asIs == in ? map.getNin() : map.getNout();
    }

    /**
     * The section of cmpmap.c {@code MapMerge} that swaps a parallel CmpMap at {@code where} with a
     * PermMap before it, when the PermMap's outputs that feed the CmpMap's first component come from
     * the last block of its inputs, or are all constant, and those that feed the second component
     * come from the first block of its inputs, or are all constant. The CmpMap, with its components
     * in the other order (a constant block's component being replaced by a UnitMap, or dropped),
     * then comes first, and a new PermMap after it puts the outputs in order, holding as constants
     * what a constant block's component made of the PermMap's constants.
     *
     * <p>As C does, the PermMap's permutations are read as it stores them ({@code astGetInPerm},
     * {@code astGetOutPerm}), whichever way the list applies it, and the component shapes as the
     * components now are. When both blocks are constant, C replaces only the first component, so
     * the second component's inverse is applied to the constants the PermMap's inverse gives: the
     * forward transformation is kept, and the inverse one changes, as in C.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    private static int swapPermMap(MergeList list, int where) {
        int imap1 = where - 1;
        int imap2 = where;
        CmpMap cmpmap2 = (CmpMap) list.map(imap2);
        Mapping lower = list.map(imap1);
        if (lower.getClass() != PermMap.class || cmpmap2.isSeries()) {
            return -1;
        }
        PermMap permmap1 = (PermMap) lower;
        Mapping newPm = null;
        Mapping newCm = null;
        boolean canswap;
        int invert1 = permmap1.swapInvert(list.invert(imap1));
        int invert2 = cmpmap2.swapInvert(list.invert(imap2));
        try {
            int npout = permmap1.getNout();
            int npin = permmap1.getNin();
            Mapping map2a = cmpmap2.getMap1();
            Mapping map2b = cmpmap2.getMap2();
            int nin2a = map2a.getNin();
            int nin2b = map2b.getNin();
            int nout2a = map2a.getNout();
            int nout2b = map2b.getNout();
            int[] inperm = permmap1.storedInPerm();
            int[] outperm = permmap1.storedOutPerm();

            // C runs both checks even after the PermMap is found not square or the first check
            // fails; their reads can then only fail the swap again, so they are skipped here.
            canswap = npin == npout;
            boolean aconstants = false;
            boolean bconstants = false;
            if (canswap) {
                aconstants = outperm[0] < 0;
                for (int i = 0; i < nin2a; i++) {
                    int j = npin - nin2a + i;
                    if (aconstants) {
                        if (outperm[i] >= 0) {
                            canswap = false;
                            break;
                        }
                    } else if (outperm[i] != j || inperm[j] != i) {
                        canswap = false;
                        break;
                    }
                }
            }
            if (canswap) {
                bconstants = outperm[nin2a] < 0;
                for (int i = 0; i < nin2b; i++) {
                    int j = npout - nin2b + i;
                    if (bconstants) {
                        if (outperm[j] >= 0) {
                            canswap = false;
                            break;
                        }
                    } else if (inperm[i] != j || outperm[j] != i) {
                        canswap = false;
                        break;
                    }
                }
            }

            if (canswap) {
                int invert2a = map2a.swapInvert(cmpmap2.getInvert1());
                int invert2b = map2b.swapInvert(cmpmap2.getInvert2());
                try {
                    Mapping[] built = swappedPair(
                            permmap1,
                            map2a,
                            map2b,
                            inperm,
                            aconstants,
                            bconstants,
                            npin,
                            npout,
                            nin2a,
                            nin2b,
                            nout2a,
                            nout2b);
                    newPm = built[0];
                    newCm = built[1];
                } finally {
                    map2b.restoreInvert(invert2b);
                    map2a.restoreInvert(invert2a);
                }
            }
        } finally {
            cmpmap2.restoreInvert(invert2);
            permmap1.restoreInvert(invert1);
        }
        if (!canswap) {
            return -1;
        }
        list.set(imap2, newPm, newPm.getInvert());
        list.set(imap1, newCm, newCm.getInvert());
        return imap1;
    }

    /**
     * The new PermMap and the new CmpMap of {@link #swapPermMap}, with the PermMap's Invert
     * attribute set as the list applies it and the components' set as the CmpMap applies them.
     *
     * @return the PermMap and the CmpMap
     */
    private static Mapping[] swappedPair(
            PermMap permmap1,
            Mapping map2a,
            Mapping map2b,
            int[] inperm,
            boolean aconstants,
            boolean bconstants,
            int npin,
            int npout,
            int nin2a,
            int nin2b,
            int nout2a,
            int nout2b) {
        // The components' outputs for the PermMap's constants.
        double[] qa = null;
        double[] qb = null;
        if (aconstants || bconstants) {
            double[] p = new double[npin];
            Arrays.fill(p, AstObject.AST__BAD);
            double[] q = permmap1.tranN(1, npin, p, true, npout);
            qa = map2a.tranN(1, nin2a, Arrays.copyOfRange(q, 0, nin2a), true, nout2a);
            qb = map2b.tranN(1, nin2b, Arrays.copyOfRange(q, nin2a, nin2a + nin2b), true, nout2b);
        }

        int nunit;
        int npinNew;
        if (aconstants) {
            nunit = npin - nin2b;
            npinNew = nout2b + nunit;
        } else if (bconstants) {
            nunit = npin - nin2a;
            npinNew = nout2a + nunit;
        } else {
            nunit = 0;
            npinNew = nout2a + nout2b;
        }
        UnitMap unit = nunit != 0 ? new UnitMap(nunit) : null;

        int npoutNew = nout2a + nout2b;
        int[] outpermNew = new int[npoutNew];
        int[] inpermNew = new int[npinNew];
        // When both blocks are constant, C's two branches below write overlapping parts of
        // inpermNew, leaving the elements from nunit to nout2b uninitialised if nunit is the
        // smaller (zero here), and, if nunit is the larger, store more constants than the
        // npoutNew + npinNew C allocates (cmpmap.c:2176), writing past the end (cmpmap.c:2243);
        // the nunit more here hold every constant the two blocks can store.
        double[] constNew = new double[npoutNew + npinNew + nunit];
        int oconid = 0;

        if (!bconstants) {
            for (int i = 0; i < nout2b; i++) {
                int j = npoutNew - nout2b + i;
                inpermNew[i] = j;
                outpermNew[j] = i;
            }
        } else {
            for (int i = 0; i < nunit; i++) {
                int iconid = inperm[i];
                if (iconid >= npout) {
                    inpermNew[i] = npoutNew;
                } else if (iconid >= 0) {
                    throw notConstant();
                } else {
                    inpermNew[i] = --oconid;
                    constNew[-(oconid + 1)] = permmap1.constantFor(iconid);
                }
            }
            for (int i = 0; i < nout2b; i++) {
                int j = npoutNew - nout2b + i;
                outpermNew[j] = --oconid;
                constNew[-(oconid + 1)] = qb[i];
            }
        }

        if (!aconstants) {
            for (int i = 0; i < nout2a; i++) {
                int j = npinNew - nout2a + i;
                inpermNew[j] = i;
                outpermNew[i] = j;
            }
        } else {
            for (int i = nout2b; i < npinNew; i++) {
                int iconid = inperm[i - nout2b + nin2b];
                if (iconid >= npout) {
                    inpermNew[i] = npoutNew;
                } else if (iconid >= 0) {
                    throw notConstant();
                } else {
                    inpermNew[i] = --oconid;
                    constNew[-(oconid + 1)] = permmap1.constantFor(iconid);
                }
            }
            for (int i = 0; i < nout2a; i++) {
                outpermNew[i] = --oconid;
                constNew[-(oconid + 1)] = qa[i];
            }
        }

        Mapping newPm = PermMap.fromInternal(npinNew, inpermNew, npoutNew, outpermNew, constNew);
        Mapping newCm;
        if (aconstants) {
            newCm = unit != null ? new CmpMap(map2b, unit, false) : (Mapping) map2b.copy();
        } else if (bconstants) {
            newCm = unit != null ? new CmpMap(unit, map2a, false) : (Mapping) map2a.copy();
        } else {
            newCm = new CmpMap(map2b, map2a, false);
        }
        return new Mapping[] {newPm, newCm};
    }

    /** The error C reports when a PermMap input it takes for constant is not. */
    private static AstException notConstant() {
        return Ast.err(
                AstException.AST__INTER,
                "astMapMerge(CmpMap): Swapped PermMap input is not constant (internal AST programming error).");
    }

    /**
     * cmpmap.c {@code CombineMaps}: a CmpMap of {@code mapping1} and {@code mapping2}, in series if
     * {@code series}, made with their Invert attributes set to {@code invert1} and {@code invert2}
     * and put back afterwards. If the two are the same Mapping but are wanted with different Invert
     * values, the second is a copy.
     */
    // C compares the pointers: whether the two Mappings are one object.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static Mapping combineMaps(Mapping mapping1, boolean invert1, Mapping mapping2, boolean invert2, boolean series) {
        boolean inv1 = mapping1.getInvert();
        boolean inv2 = mapping2.getInvert();
        boolean copy = mapping1 == mapping2 && invert1 != invert2;
        Mapping map1 = mapping1;
        Mapping map2 = copy ? (Mapping) mapping2.copy() : mapping2;
        int saved1 = invert1 != inv1 ? map1.swapInvert(invert1) : 0;
        int saved2 = invert2 != inv2 ? map2.swapInvert(invert2) : 0;
        try {
            return new CmpMap(map1, map2, series);
        } finally {
            if (invert2 != inv2 && !copy) {
                map2.restoreInvert(saved2);
            }
            if (invert1 != inv1) {
                map1.restoreInvert(saved1);
            }
        }
    }
}
