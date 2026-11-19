package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * WinMap's {@code MapMerge} (winmap.c) and the functions of winmap.c it uses: {@code CanSwap},
 * {@code PermGet}, the swaps {@code WinMat}, {@code WinPerm} and {@code WinWcs}, and the merges
 * {@code WinWin}, {@code WinZoom}, {@code WinShift}, {@code WinUnit} and {@code WinDiagMat}.
 *
 * <p>Each function that applies a Mapping of a list in the orientation the list gives it sets the
 * Mapping's Invert attribute to that value while it reads the Mapping, and puts the attribute back
 * afterwards, as C does.
 */
final class WinMapMerge {

    private WinMapMerge() {}

    /**
     * winmap.c {@code MapMerge}. A WinMap whose shifts are all zero becomes a diagonal MatrixMap.
     * Otherwise it merges with a neighbouring WinMap, ZoomMap, ShiftMap, UnitMap or diagonal
     * MatrixMap; in series, failing that, it is distributed into a neighbouring parallel CmpMap if
     * that simplifies either half, or else it is swapped with a neighbouring MatrixMap, PermMap or
     * WcsMap, towards the nearest Mapping it could merge with or where the swap simplifies
     * something. If nothing changed and its scales are all one, it becomes a ShiftMap.
     *
     * @param thisMap the nominated WinMap, at {@code where} in the list
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    static int winMapMerge(WinMap thisMap, MergeList list, int where, boolean series) {
        int result = -1;
        int nin = list.map(where).getNin();
        WinMap oldwm = thisMap;

        WinMap newwm = (WinMap) list.map(where);
        double[] storedShifts = newwm.storedShifts();
        boolean diag = true;
        for (int i = 0; i < nin; i++) {
            if (!MappingHelpers.astEqual(storedShifts[i], 0.0)) {
                diag = false;
                break;
            }
        }

        if (diag) {
            double[] b;
            int saved = newwm.swapInvert(list.invert(where));
            try {
                b = newwm.winTerms()[1];
            } finally {
                newwm.restoreInvert(saved);
            }
            list.set(where, new MatrixMap(nin, nin, MatrixMap.DIAGONAL, b), false);
            result = where;
            // A WinMap whose scales are all one is turned into a ShiftMap only at the end, when
            // nothing else changed: ShiftMap's merge turns a ShiftMap straight back into a WinMap.
        } else {
            Mapping lower = where > 0 ? list.map(where - 1) : null;
            Mapping upper = where < list.size() - 1 ? list.map(where + 1) : null;
            boolean diag1 = lower != null && lower.getClass() == MatrixMap.class && ((MatrixMap) lower).isDiagonal();
            boolean diag2 = upper != null && upper.getClass() == MatrixMap.class && ((MatrixMap) upper).isDiagonal();

            int i1;
            int i2;
            if (lower != null && directlyMergeable(lower, diag1)) {
                i1 = where - 1;
                i2 = where;
            } else if (upper != null && directlyMergeable(upper, diag2)) {
                i1 = where;
                i2 = where + 1;
            } else {
                i1 = -1;
                i2 = -1;
            }

            if (i1 >= 0) {
                result = mergeDirectly(list, where, i1, i2, series);
            } else if (series && (isCmpMap(lower) || isCmpMap(upper))) {
                int[] ninHolder = {nin};
                result = distribute(oldwm, list, where, ninHolder);
                // C reuses its axis count for the width of the CmpMap's first component, and the
                // final step below reads it.
                nin = ninHolder[0];
            } else if (series) {
                result = swap(list, where, lower, upper, series);
            }
        }

        if (result == -1) {
            double[] storedScales = oldwm.storedScales();
            boolean isshift = true;
            for (int i = 0; i < nin; i++) {
                if (!MappingHelpers.astEqual(storedScales[i], 1.0)) {
                    isshift = false;
                    break;
                }
            }
            if (isshift) {
                double[] a;
                int saved = oldwm.swapInvert(list.invert(where));
                try {
                    a = oldwm.winTerms()[0];
                } finally {
                    oldwm.restoreInvert(saved);
                }
                // A shift zero to within the tolerance the scales were just tested against is
                // zero, so that rounding left by an earlier merge does not decide whether the
                // ShiftMap's dump has the item.
                for (int i = 0; i < nin; i++) {
                    if (MappingHelpers.astEqual(a[i], 0.0)) {
                        a[i] = 0.0;
                    }
                }
                list.set(where, new ShiftMap(Arrays.copyOf(a, nin)), false);
                result = where;
            }
        }
        return result;
    }

    /** Whether the class of {@code m} is exactly {@code cls}, as C's comparison of class names tests. */
    private static boolean isClass(Mapping m, Class<?> cls) {
        return m.getClass() == cls;
    }

    private static boolean isCmpMap(Mapping m) {
        return m != null && isClass(m, CmpMap.class);
    }

    /**
     * Whether a WinMap can merge directly with {@code m}: a WinMap, ZoomMap, ShiftMap or UnitMap,
     * or a MatrixMap found diagonal ({@code diag}).
     */
    private static boolean directlyMergeable(Mapping m, boolean diag) {
        return isClass(m, WinMap.class)
                || isClass(m, ZoomMap.class)
                || isClass(m, ShiftMap.class)
                || isClass(m, UnitMap.class)
                || diag;
    }

    /**
     * Whether a WinMap moving through the list towards {@code m} would stop there to merge with
     * it, as C's searches for the nearest such Mapping test.
     */
    private static boolean mergeTarget(Mapping m) {
        return isClass(m, WinMap.class)
                || isClass(m, ZoomMap.class)
                || isClass(m, ShiftMap.class)
                || isClass(m, UnitMap.class)
                || (isClass(m, MatrixMap.class) && ((MatrixMap) m).isDiagonal());
    }

    /** Whether a WinMap may be swapped past {@code m}, as C's searches test. */
    private static boolean swappable(Mapping m) {
        return isClass(m, MatrixMap.class) || isClass(m, WcsMap.class) || isClass(m, PermMap.class);
    }

    /**
     * The WinMap at {@code where} merged with its neighbour, the pair being the entries {@code i1}
     * and {@code i2}, which the merged Mapping replaces.
     *
     * @return {@code i1}
     */
    private static int mergeDirectly(MergeList list, int where, int i1, int i2, boolean series) {
        // C's WinWin, WinZoom, WinShift and WinDiagMat assume a neighbour with as many outputs as
        // inputs and, in series, as wide as the WinMap. A diagonal MatrixMap that is not square, or a
        // list a C defect elsewhere has left with Mappings that do not fit (a unit MatrixMap beside a
        // WinMap), would make them read past their arrays, so such a pair is not merged.
        int other = i1 == where ? i2 : i1;
        int otherNin = Mapping.appliedNin(list.map(other), list.invert(other));
        int otherNout = Mapping.appliedNout(list.map(other), list.invert(other));
        if (otherNin != otherNout || (series && otherNin != list.map(where).createdNin())) {
            return -1;
        }
        Mapping first = list.map(i1);
        Mapping second = list.map(i2);
        boolean inv1 = list.invert(i1);
        boolean inv2 = list.invert(i2);
        boolean winFirst = i1 == where;
        Mapping neighbour = winFirst ? second : first;
        Mapping merged;
        boolean invert = false;
        if (isClass(neighbour, WinMap.class)) {
            merged = winWin(first, second, inv1, inv2, series);
        } else if (isClass(neighbour, ZoomMap.class)) {
            merged = winFirst
                    ? winZoom((WinMap) first, (ZoomMap) second, inv1, inv2, true, series)
                    : winZoom((WinMap) second, (ZoomMap) first, inv2, inv1, false, series);
        } else if (isClass(neighbour, ShiftMap.class)) {
            merged = winFirst
                    ? winShift((WinMap) first, (ShiftMap) second, inv1, inv2, true, series)
                    : winShift((WinMap) second, (ShiftMap) first, inv2, inv1, false, series);
        } else if (isClass(neighbour, MatrixMap.class)) {
            merged = winFirst
                    ? winDiagMat((WinMap) first, (MatrixMap) second, inv1, inv2, true, series)
                    : winDiagMat((WinMap) second, (MatrixMap) first, inv2, inv1, false, series);
        } else if (isClass(neighbour, UnitMap.class)) {
            if (series) {
                merged = list.map(where);
                invert = list.invert(where);
            } else {
                merged = winFirst
                        ? winUnit((WinMap) first, (UnitMap) second, inv1, true)
                        : winUnit((WinMap) second, (UnitMap) first, inv2, false);
            }
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astMapMerge(WinMap): Cannot merge a WinMap with a " + neighbour.astClass()
                            + " (internal AST programming error).");
        }
        list.replacePairWith(i1, merged, invert);
        return i1;
    }

    /**
     * The WinMap at {@code where}, next to a CmpMap in a series list, made into a parallel CmpMap of
     * two WinMaps, one for each component of the CmpMap if that is parallel, each of which is
     * joined in series with its component; if either of those series CmpMaps simplifies, the
     * parallel CmpMap of the two, simplified, replaces the pair.
     *
     * @param nin receives at index 0, if the CmpMap is parallel, the number of axes of the WinMap
     *     made for its first component
     * @return the index of the first entry of the pair, or -1 if nothing changed
     */
    private static int distribute(WinMap oldwm, MergeList list, int where, int[] nin) {
        int i1;
        int i2;
        CmpMap cm;
        boolean cmlow;
        if (isCmpMap(where > 0 ? list.map(where - 1) : null)) {
            i1 = where - 1;
            i2 = where;
            cm = (CmpMap) list.map(where - 1);
            cmlow = true;
        } else {
            i1 = where;
            i2 = where + 1;
            cm = (CmpMap) list.map(where + 1);
            cmlow = false;
        }
        Mapping map2 = null;
        Mapping first = list.map(i1);
        Mapping second = list.map(i2);
        int inc0 = first.swapInvert(list.invert(i1));
        int inc1 = second.swapInvert(list.invert(i2));
        try {
            double[][] terms = oldwm.winTerms();
            double[] a = terms[0];
            double[] b = terms[1];
            boolean[] flags = new boolean[3];
            Mapping[] mc = cm.decomposeInto(flags);
            if (!flags[0]) {
                int inc2 = mc[0].swapInvert(flags[1]);
                int inc3 = mc[1].swapInvert(flags[2]);
                try {
                    int n1 = cmlow ? mc[0].getNout() : mc[0].getNin();
                    int n2 = cmlow ? mc[1].getNout() : mc[1].getNin();
                    // C assumes the WinMap has as many axes as the CmpMap's components together; a
                    // list a C defect elsewhere has left with Mappings that do not fit would make it
                    // read past the WinMap's terms, so the WinMap is not distributed.
                    if (n1 + n2 != a.length) {
                        return -1;
                    }
                    nin[0] = n1;
                    WinMap w1 = new WinMap(Arrays.copyOfRange(a, 0, n1), Arrays.copyOfRange(b, 0, n1));
                    WinMap w2 = new WinMap(Arrays.copyOfRange(a, n1, n1 + n2), Arrays.copyOfRange(b, n1, n1 + n2));
                    CmpMap nc0;
                    CmpMap nc1;
                    if (cmlow) {
                        nc0 = new CmpMap(mc[0], w1, true);
                        nc1 = new CmpMap(mc[1], w2, true);
                    } else {
                        nc0 = new CmpMap(w1, mc[0], true);
                        nc1 = new CmpMap(w2, mc[1], true);
                    }
                    // Unless either half simplifies, the parallel CmpMap would only be turned back
                    // into the series one by CmpMap's merge, and back again here.
                    Mapping simp1 = nc0.simplify();
                    Mapping simp2 = nc1.simplify();
                    if (changed(simp1, nc0) || changed(simp2, nc1)) {
                        map2 = new CmpMap(simp1, simp2, false);
                    }
                } finally {
                    mc[1].restoreInvert(inc3);
                    mc[0].restoreInvert(inc2);
                }
            }
        } finally {
            second.restoreInvert(inc1);
            first.restoreInvert(inc0);
        }
        if (map2 == null) {
            return -1;
        }
        list.replacePairWith(i1, map2, false);
        return i1;
    }

    /** Whether simplifying {@code before} gave another Mapping. */
    // C compares the pointers: astSimplify returns the same object when nothing changed.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static boolean changed(Mapping after, Mapping before) {
        return after != before;
    }

    /**
     * The swap part of winmap.c {@code MapMerge}, in series, when the WinMap at {@code where} can
     * merge with neither neighbour. If it can be swapped with a neighbour, the WinMap is moved
     * towards the nearer Mapping it could merge with beyond a run of Mappings it can be swapped
     * past, or towards a PermMap the swap makes simpler, and the list after the swap is offered
     * to a merge; otherwise each possible swap is tried on copies and kept if it lets the
     * neighbours merge or simplifies either swapped Mapping.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    private static int swap(MergeList list, int where, Mapping lower, Mapping upper, boolean series) {
        int nmap = list.size();
        // C's swaps (WinMat, WinPerm, WinWcs) assume the WinMap's neighbours have as many outputs
        // and inputs as the WinMap has axes; a list a C defect elsewhere has left with Mappings that
        // do not fit would make them read past their arrays, so the WinMap is not swapped.
        int width = list.map(where).createdNin();
        if ((where > 0 && Mapping.appliedNout(list.map(where - 1), list.invert(where - 1)) != width)
                || (where + 1 < nmap && Mapping.appliedNin(list.map(where + 1), list.invert(where + 1)) != width)) {
            return -1;
        }
        boolean[] do2 = new boolean[1];
        boolean swaphi = false;
        if (where + 1 < nmap) {
            swaphi = canSwap(list.map(where), list.map(where + 1), list.invert(where), list.invert(where + 1), do2);
        }
        int nstep2 = -1;
        if (swaphi) {
            for (int i2 = where + 1; i2 < nmap; i2++) {
                Mapping m = list.map(i2);
                if (mergeTarget(m)) {
                    nstep2 = i2 - where - 1;
                    break;
                }
                if (!swappable(m)) {
                    break;
                }
            }
        }

        boolean[] do1 = new boolean[1];
        boolean swaplo = false;
        if (where > 0) {
            swaplo = canSwap(list.map(where - 1), list.map(where), list.invert(where - 1), list.invert(where), do1);
        }
        int nstep1 = -1;
        if (swaplo) {
            for (int i1 = where - 1; i1 >= 0; i1--) {
                Mapping m = list.map(i1);
                if (mergeTarget(m)) {
                    nstep1 = where - 1 - i1;
                    break;
                }
                if (!swappable(m)) {
                    break;
                }
            }
        }

        Mapping nclass;
        int i1;
        if (do1[0] || (nstep1 != -1 && (nstep2 == -1 || nstep2 > nstep1))) {
            nclass = lower;
            i1 = where - 1;
        } else if (do2[0] || nstep2 != -1) {
            nclass = upper;
            i1 = where;
        } else {
            nclass = null;
            i1 = -1;
        }

        if (nclass != null) {
            swapPair(list, i1, where - i1, nclass);
            if (where == i1 && where + 1 < list.size()) {
                list.mergeSubrange(where + 1, 0, series);
            } else if (where - 2 >= 0) {
                list.mergeSubrange(where - 2, 0, series);
            }
            return i1;
        }

        if (swaphi || swaplo) {
            for (int i = 0; i < 2; i++) {
                Mapping neighbour;
                int j1;
                if (i == 0 && swaplo) {
                    neighbour = lower;
                    j1 = where - 1;
                } else if (i == 1 && swaphi) {
                    neighbour = upper;
                    j1 = where;
                } else {
                    continue;
                }
                MergeList mc = new MergeList(
                        Arrays.asList((Mapping) list.map(j1).copy(), (Mapping)
                                list.map(j1 + 1).copy()),
                        Arrays.asList(list.invert(j1), list.invert(j1 + 1)));
                swapPair(mc, 0, where - j1, neighbour);

                Mapping nc0 = null;
                Mapping nc1 = null;
                boolean inc0 = false;
                boolean inc1 = false;
                if (i == 0 && where != list.size() - 1) {
                    nc0 = mc.map(1);
                    nc1 = list.map(where + 1);
                    inc0 = mc.invert(1);
                    inc1 = list.invert(where + 1);
                } else if (i == 1 && where > 0) {
                    nc0 = list.map(where - 1);
                    nc1 = mc.map(0);
                    inc0 = list.invert(where - 1);
                    inc1 = mc.invert(0);
                }

                boolean doSwap = false;
                if (nc0 != null) {
                    MergeList pair = new MergeList(Arrays.asList(nc0, nc1), Arrays.asList(inc0, inc1));
                    int merged = Simplifier.dispatchMapMerge(nc0, pair, 0, series);
                    if (merged == -1) {
                        Simplifier.dispatchMapMerge(pair.map(1), pair, 1, series);
                    }
                    doSwap = pair.size() < 2;
                }
                if (!doSwap) {
                    Mapping smc0 = mc.map(0).simplify();
                    if (changed(smc0, mc.map(0))) {
                        doSwap = true;
                    } else {
                        Mapping smc1 = mc.map(1).simplify();
                        doSwap = changed(smc1, mc.map(1));
                    }
                }
                if (doSwap) {
                    list.set(j1, mc.map(0), mc.invert(0));
                    list.set(j1 + 1, mc.map(1), mc.invert(1));
                    return j1;
                }
            }
        }
        return -1;
    }

    /**
     * Swaps the WinMap at {@code base + iwm} with the Mapping {@code nclass} beside it, by the
     * function for that Mapping's class.
     */
    private static void swapPair(MergeList maps, int base, int iwm, Mapping nclass) {
        if (isClass(nclass, MatrixMap.class)) {
            winMat(maps, base, iwm);
        } else if (isClass(nclass, PermMap.class)) {
            winPerm(maps, base, iwm);
        } else if (isClass(nclass, WcsMap.class)) {
            winWcs(maps, base, iwm);
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astMapMerge(WinMap): Cannot swap a WinMap with a " + nclass.astClass()
                            + " (internal AST programming error).");
        }
    }

    // ------------------------------------------------------------------
    // Swapping
    // ------------------------------------------------------------------

    /**
     * winmap.c {@code CanSwap}: whether two Mappings applied in series, one of them a WinMap, can
     * be swapped. A MatrixMap can be; a WcsMap can be if the WinMap has a zero shift and a unit
     * scale, as stored, on both its celestial axes; and a PermMap can be if every link it makes
     * between an input and an output works in both directions.
     *
     * @param simpler receives at index 0 whether swapping with a PermMap makes it simpler: one
     *     applied first with more outputs than inputs, or applied second with more inputs than
     *     outputs
     */
    static boolean canSwap(Mapping map1, Mapping map2, boolean inv1, boolean inv2, boolean[] simpler) {
        simpler[0] = false;
        boolean ret = false;
        int invert0 = map1.swapInvert(inv1);
        int invert1 = map2.swapInvert(inv2);
        try {
            Mapping nowin;
            WinMap win;
            boolean nowinFirst;
            if (isClass(map1, WinMap.class)) {
                nowin = map2;
                win = (WinMap) map1;
                nowinFirst = false;
            } else {
                nowin = map1;
                win = (WinMap) map2;
                nowinFirst = true;
            }
            if (isClass(nowin, MatrixMap.class)) {
                ret = true;
            } else if (isClass(nowin, WcsMap.class)) {
                WcsMap wcs = (WcsMap) nowin;
                int axlat = wcs.getWcsAxis(2) - 1;
                int axlon = wcs.getWcsAxis(1) - 1;
                double[] a = win.storedShifts();
                double[] b = win.storedScales();
                ret = a[axlon] == 0.0 && b[axlon] == 1.0 && a[axlat] == 0.0 && b[axlat] == 1.0;
            } else if (isClass(nowin, PermMap.class)) {
                int nin = nowin.getNin();
                int nout = nowin.getNout();
                PermParts perm = permGet((PermMap) nowin);
                ret = true;
                for (int i = 0; i < nout; i++) {
                    int o = perm.outperm[i];
                    if (o >= 0 && o < nin && perm.inperm[o] != i) {
                        ret = false;
                        break;
                    }
                }
                for (int i = 0; i < nin; i++) {
                    int p = perm.inperm[i];
                    if (p >= 0 && p < nout && perm.outperm[p] != i) {
                        ret = false;
                        break;
                    }
                }
                if (ret) {
                    simpler[0] = nowinFirst ? nout > nin : nin > nout;
                }
            }
        } finally {
            map2.restoreInvert(invert1);
            map1.restoreInvert(invert0);
        }
        return ret;
    }

    /** The permutations and constants of a PermMap, as winmap.c {@code PermGet} finds them. */
    static final class PermParts {
        /** For each output, the input it copies, or {@code -(k + 1)} for constant {@code k}. */
        final int[] outperm;

        /** For each input, the output it copies in the inverse, or {@code -(k + 1)} for constant {@code k}. */
        final int[] inperm;

        /** The constants, one for each axis that takes one. */
        final double[] consts;

        PermParts(int[] outperm, int[] inperm, double[] consts) {
            this.outperm = outperm;
            this.inperm = inperm;
            this.consts = consts;
        }
    }

    /**
     * winmap.c {@code PermGet}: the permutations and constants of a PermMap in its current
     * orientation, found by transforming the positions {@code [0, 1, 2, ...]} and {@code [-1, -1,
     * ...]} each way: an axis whose two values are the same takes a constant, which gets a slot of
     * its own, and any other copies the axis whose index its first value is.
     *
     * <p>C takes that index with {@code round}. A PermMap copies a value, gives a constant or gives
     * a bad value, the same at both positions, so an index that is not a constant is one of the
     * whole numbers given, and no input reaches the values (just below a half, or odd above 2^52)
     * where {@code round} and {@code floor(x + 0.5)} differ.
     */
    static PermParts permGet(PermMap map) {
        int nin = map.getNin();
        int nout = map.getNout();
        int[] outprm = new int[nout];
        int[] inprm = new int[nin];
        double[] cnst = new double[nout + nin];
        int nc = 0;

        double[][] ptr1 = new double[nin][2];
        for (int i = 0; i < nin; i++) {
            ptr1[i][0] = i;
            ptr1[i][1] = -1.0;
        }
        double[][] ptr2 = map.transform(ptr1, 2, true);
        for (int i = 0; i < nout; i++) {
            double op = ptr2[i][0];
            double cn = ptr2[i][1];
            if (op == cn) {
                cnst[nc] = cn;
                outprm[i] = -(nc + 1);
                nc++;
            } else {
                outprm[i] = (int) CMath.roundHalfAway(op);
            }
        }

        double[][] back = new double[nout][2];
        for (int i = 0; i < nout; i++) {
            back[i][0] = i;
            back[i][1] = -1.0;
        }
        ptr1 = map.transform(back, 2, false);
        for (int i = 0; i < nin; i++) {
            double ip = ptr1[i][0];
            double cn = ptr1[i][1];
            if (ip == cn) {
                cnst[nc] = cn;
                inprm[i] = -(nc + 1);
                nc++;
            } else {
                inprm[i] = (int) CMath.roundHalfAway(ip);
            }
        }
        return new PermParts(outprm, inprm, cnst);
    }

    /**
     * winmap.c {@code WinMat}: swaps a WinMap and a MatrixMap applied in series, the entries at
     * {@code base} and {@code base + 1}, the WinMap at {@code base + iwm}. The WinMap's scales go
     * into the matrix, and its shifts, transformed by the matrix, become the shifts of a WinMap
     * of unit scales on the other side; each new Mapping is simplified, and is applied with its own
     * Invert attribute.
     */
    static void winMat(MergeList maps, int base, int iwm) {
        WinMap wm = (WinMap) maps.map(base + iwm);
        MatrixMap mm = (MatrixMap) maps.map(base + 1 - iwm);
        int oldWinv = wm.swapInvert(maps.invert(base + iwm));
        int oldMinv = mm.swapInvert(maps.invert(base + 1 - iwm));
        MatrixMap m2;
        double[][] pset2;
        try {
            double[][] terms = wm.winTerms();
            double[] a = terms[0];
            double[] b = terms[1];
            int nin = a.length;
            MatrixMap m1 = new MatrixMap(nin, nin, MatrixMap.DIAGONAL, b);
            double[][] pset1 = new double[nin][1];
            for (int i = 0; i < nin; i++) {
                pset1[i][0] = a[i];
            }
            if (iwm == 0) {
                m2 = m1.mtrMult(mm);
                pset2 = mm.transform(pset1, 1, true);
            } else {
                m2 = mm.mtrMult(m1);
                pset2 = m2.transform(pset1, 1, false);
            }
        } finally {
            mm.restoreInvert(oldMinv);
            wm.restoreInvert(oldWinv);
        }
        int nout = pset2.length;
        double[] aa = new double[nout];
        double[] bb = new double[nout];
        for (int i = 0; i < nout; i++) {
            bb[i] = 1.0;
            aa[i] = pset2[i][0];
        }
        Mapping sw1 = new WinMap(aa, bb).simplify();
        maps.set(base + 1 - iwm, sw1, sw1.getInvert());
        Mapping sm2 = m2.simplify();
        maps.set(base + iwm, sm2, sm2.getInvert());
    }

    /**
     * winmap.c {@code WinPerm}: swaps a WinMap and a PermMap applied in series, the entries at
     * {@code base} and {@code base + 1}, the WinMap at {@code base + iwm}. The new WinMap gives each
     * axis the terms of the axis the PermMap takes it from, and a zero shift and unit scale to an
     * axis that takes a constant; the constants of the new PermMap are those the old one gave,
     * passed through the WinMap they no longer meet. Each new Mapping is simplified; the PermMap
     * is applied forward and the WinMap with its own Invert attribute.
     */
    static void winPerm(MergeList maps, int base, int iwm) {
        WinMap wm = (WinMap) maps.map(base + iwm);
        PermMap pm = (PermMap) maps.map(base + 1 - iwm);
        int oldWinv = wm.swapInvert(maps.invert(base + iwm));
        int oldPinv = pm.swapInvert(maps.invert(base + 1 - iwm));
        WinMap w1;
        PermMap p1;
        try {
            double[][] terms = wm.winTerms();
            double[] a = terms[0];
            double[] b = terms[1];
            int nin = a.length;
            PermParts perm = permGet(pm);
            int[] outperm = perm.outperm;
            int[] inperm = perm.inperm;
            double[] consts = perm.consts;
            int npin = pm.getNin();
            int npout = pm.getNout();
            if (iwm == 0) {
                double[] aa = new double[npout];
                double[] bb = new double[npout];
                for (int i = 0; i < npout; i++) {
                    int j = outperm[i];
                    if (j >= 0 && j < nin) {
                        aa[i] = a[j];
                        bb[i] = b[j];
                    } else {
                        aa[i] = 0.0;
                        bb[i] = 1.0;
                    }
                }
                for (int i = 0; i < npin; i++) {
                    if (inperm[i] < 0) {
                        int k = -inperm[i] - 1;
                        double c = consts[k];
                        if (c != AstObject.AST__BAD) {
                            if (a[i] != AstObject.AST__BAD && b[i] != AstObject.AST__BAD && b[i] != 0.0) {
                                consts[k] = (c - a[i]) / b[i];
                            } else {
                                consts[k] = AstObject.AST__BAD;
                            }
                        }
                    }
                }
                w1 = new WinMap(aa, bb);
            } else {
                double[] aa = new double[npin];
                double[] bb = new double[npin];
                for (int i = 0; i < npin; i++) {
                    int j = inperm[i];
                    if (j >= 0 && j < nin) {
                        aa[i] = a[j];
                        bb[i] = b[j];
                    } else {
                        aa[i] = 0.0;
                        bb[i] = 1.0;
                    }
                }
                for (int i = 0; i < npout; i++) {
                    if (outperm[i] < 0) {
                        int k = -outperm[i] - 1;
                        double c = consts[k];
                        if (c != AstObject.AST__BAD) {
                            if (a[i] != AstObject.AST__BAD && b[i] != AstObject.AST__BAD) {
                                consts[k] = a[i] + c * b[i];
                            } else {
                                consts[k] = AstObject.AST__BAD;
                            }
                        }
                    }
                }
                w1 = new WinMap(aa, bb);
            }
            p1 = PermMap.fromInternal(npin, inperm, npout, outperm, consts);
        } finally {
            pm.restoreInvert(oldPinv);
            wm.restoreInvert(oldWinv);
        }
        Mapping sp1 = p1.simplify();
        Mapping sw1 = w1.simplify();
        maps.set(base + iwm, sp1, false);
        maps.set(base + 1 - iwm, sw1, sw1.getInvert());
    }

    /**
     * winmap.c {@code WinWcs}: swaps a WinMap and a WcsMap applied in series, the entries at
     * {@code base} and {@code base + 1}, which {@link #canSwap} has found commute: they change
     * places as they are.
     *
     * @param iwm the index within the pair of the WinMap, which the swap does not need
     */
    static void winWcs(MergeList maps, int base, int iwm) {
        Mapping m1 = maps.map(base);
        boolean inv = maps.invert(base);
        maps.set(base, maps.map(base + 1), maps.invert(base + 1));
        maps.set(base + 1, m1, inv);
    }

    // ------------------------------------------------------------------
    // Merging
    // ------------------------------------------------------------------

    /**
     * winmap.c {@code WinWin}: the WinMap equal to two WinMaps applied in series or in parallel,
     * each with the Invert value given. In series, two WinMaps applied in opposite senses whose
     * stored terms are equal to within {@code astEQUAL} cancel exactly; otherwise the terms are
     * composed, a shift negligible against the geometric mean of the two shifts becoming zero and
     * a scale within 1e-15 of one becoming one, and a bad term making the axis bad.
     */
    static WinMap winWin(Mapping map1, Mapping map2, boolean inv1, boolean inv2, boolean series) {
        WinMap wm1 = (WinMap) map1;
        WinMap wm2 = (WinMap) map2;
        int invert0 = wm1.swapInvert(inv1);
        int invert1 = wm2.swapInvert(inv2);
        double[][] t0;
        double[][] t1;
        try {
            t0 = wm1.winTerms();
            t1 = wm2.winTerms();
        } finally {
            wm2.restoreInvert(invert1);
            wm1.restoreInvert(invert0);
        }
        double[] a0 = t0[0];
        double[] b0 = t0[1];
        double[] a1 = t1[0];
        double[] b1 = t1[1];
        int nin0 = a0.length;
        if (series) {
            boolean cancel = false;
            if (inv1 != inv2) {
                double[] sa1 = wm1.storedShifts();
                double[] sb1 = wm1.storedScales();
                double[] sa2 = wm2.storedShifts();
                double[] sb2 = wm2.storedScales();
                cancel = true;
                for (int i = 0; i < nin0; i++) {
                    if (!MappingHelpers.astEqual(sa1[i], sa2[i]) || !MappingHelpers.astEqual(sb1[i], sb2[i])) {
                        cancel = false;
                        break;
                    }
                }
            }
            if (cancel) {
                Arrays.fill(a0, 0.0);
                Arrays.fill(b0, 1.0);
            } else {
                for (int i = 0; i < nin0; i++) {
                    if (a0[i] != AstObject.AST__BAD
                            && b0[i] != AstObject.AST__BAD
                            && a1[i] != AstObject.AST__BAD
                            && b1[i] != AstObject.AST__BAD) {
                        double amean = Math.sqrt(Math.abs(a0[i] * a1[i]));
                        a0[i] *= b1[i];
                        a0[i] += a1[i];
                        b0[i] *= b1[i];
                        if (Math.abs(a0[i]) < amean * 1e-15) {
                            a0[i] = 0.0;
                        }
                        if (Math.abs(b0[i] - 1.0) < 1e-15) {
                            b0[i] = 1.0;
                        }
                    } else {
                        a0[i] = AstObject.AST__BAD;
                        b0[i] = AstObject.AST__BAD;
                        a1[i] = AstObject.AST__BAD;
                        b1[i] = AstObject.AST__BAD;
                    }
                }
            }
            return new WinMap(a0, b0);
        }
        return new WinMap(concat(a0, a1), concat(b0, b1));
    }

    /**
     * winmap.c {@code WinZoom}: the WinMap equal to a WinMap and a ZoomMap applied in series or in
     * parallel, each with the Invert value given, the WinMap first if {@code win1}. In series the
     * WinMap's scales, and if it comes first its shifts, are multiplied by the effective zoom
     * factor; in parallel the ZoomMap's axes get a zero shift and that factor as scale.
     */
    static WinMap winZoom(WinMap wm, ZoomMap zm, boolean winv, boolean zinv, boolean win1, boolean series) {
        int oldWinv = wm.swapInvert(winv);
        int oldZinv = zm.swapInvert(zinv);
        double zfac;
        double[][] terms;
        int ninz;
        try {
            zfac = zm.getZoom();
            if (zinv) {
                zfac = 1.0 / zfac;
            }
            terms = wm.winTerms();
            ninz = zm.getNin();
        } finally {
            zm.restoreInvert(oldZinv);
            wm.restoreInvert(oldWinv);
        }
        double[] a = terms[0];
        double[] b = terms[1];
        if (series) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] != AstObject.AST__BAD && b[i] != AstObject.AST__BAD && zfac != AstObject.AST__BAD) {
                    b[i] *= zfac;
                    if (win1) {
                        a[i] *= zfac;
                    }
                } else {
                    b[i] = AstObject.AST__BAD;
                    a[i] = AstObject.AST__BAD;
                }
            }
            return new WinMap(a, b);
        }
        double[] za = new double[ninz];
        double[] zb = new double[ninz];
        Arrays.fill(zb, zfac);
        return win1 ? new WinMap(concat(a, za), concat(b, zb)) : new WinMap(concat(za, a), concat(zb, b));
    }

    /**
     * winmap.c {@code WinShift}: the WinMap equal to a WinMap and a ShiftMap applied in series or
     * in parallel, each with the Invert value given, the WinMap first if {@code win1}. In series
     * the effective shifts are added to the WinMap's shifts, after the WinMap's scales if it comes
     * second; in parallel the ShiftMap's axes get its shifts and a unit scale.
     */
    static WinMap winShift(WinMap wm, ShiftMap sm, boolean winv, boolean sinv, boolean win1, boolean series) {
        int oldWinv = wm.swapInvert(winv);
        int oldSinv = sm.swapInvert(sinv);
        double[] shifts;
        double[][] terms;
        int nins;
        try {
            shifts = sm.getShifts();
            terms = wm.winTerms();
            nins = sm.getNin();
        } finally {
            sm.restoreInvert(oldSinv);
            wm.restoreInvert(oldWinv);
        }
        double[] a = terms[0];
        double[] b = terms[1];
        if (series) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] != AstObject.AST__BAD && b[i] != AstObject.AST__BAD && shifts[i] != AstObject.AST__BAD) {
                    a[i] += win1 ? shifts[i] : b[i] * shifts[i];
                } else {
                    b[i] = AstObject.AST__BAD;
                    a[i] = AstObject.AST__BAD;
                }
            }
            return new WinMap(a, b);
        }
        double[] sb = new double[nins];
        Arrays.fill(sb, 1.0);
        double[] sa = Arrays.copyOf(shifts, nins);
        return win1 ? new WinMap(concat(a, sa), concat(b, sb)) : new WinMap(concat(sa, a), concat(sb, b));
    }

    /**
     * winmap.c {@code WinUnit}: the WinMap equal to a WinMap, applied with the Invert value given,
     * and a UnitMap in parallel, the WinMap first if {@code win1}; the UnitMap's axes get a zero
     * shift and a unit scale.
     */
    static WinMap winUnit(WinMap wm, UnitMap um, boolean winv, boolean win1) {
        int oldWinv = wm.swapInvert(winv);
        double[][] terms;
        try {
            terms = wm.winTerms();
        } finally {
            wm.restoreInvert(oldWinv);
        }
        int ninu = um.getNin();
        double[] ua = new double[ninu];
        double[] ub = new double[ninu];
        Arrays.fill(ub, 1.0);
        double[] a = terms[0];
        double[] b = terms[1];
        return win1 ? new WinMap(concat(a, ua), concat(b, ub)) : new WinMap(concat(ua, a), concat(ub, b));
    }

    /**
     * winmap.c {@code WinDiagMat}: the Mapping equal to a WinMap and a diagonal MatrixMap applied
     * in series or in parallel, each with the Invert value given, the WinMap first if {@code win1}.
     * A unit MatrixMap gives the WinMap itself. Otherwise, in series the WinMap's scales, and if it
     * comes first its shifts, are multiplied by the diagonal elements, a bad term making the axis
     * bad; in parallel the MatrixMap's axes get a zero shift and their diagonal element as scale.
     *
     * @throws AstException with status {@code AST__INTER} if the MatrixMap is not diagonal
     */
    static WinMap winDiagMat(WinMap wm, MatrixMap mm, boolean winv, boolean minv, boolean win1, boolean series) {
        int oldWinv = wm.swapInvert(winv);
        int oldMinv = mm.swapInvert(minv);
        int[] form = new int[1];
        double[] diag;
        double[][] terms = null;
        int ninm;
        try {
            diag = mm.mtrGet(true, false, form);
            if (form[0] == MatrixMap.FULL) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astSimplify(WinMap): supplied MatrixMap is not diagonal (internal AST programming error).");
            }
            if (form[0] != MatrixMap.UNIT) {
                terms = wm.winTerms();
            }
            ninm = mm.getNin();
        } finally {
            mm.restoreInvert(oldMinv);
            wm.restoreInvert(oldWinv);
        }
        if (terms == null) {
            return wm;
        }
        double[] a = terms[0];
        double[] b = terms[1];
        if (series) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] != AstObject.AST__BAD && b[i] != AstObject.AST__BAD && diag[i] != AstObject.AST__BAD) {
                    b[i] *= diag[i];
                    if (win1) {
                        a[i] *= diag[i];
                    }
                } else {
                    b[i] = AstObject.AST__BAD;
                    a[i] = AstObject.AST__BAD;
                }
            }
            return new WinMap(a, b);
        }
        double[] ma = new double[ninm];
        double[] mb = Arrays.copyOf(diag, ninm);
        return win1 ? new WinMap(concat(a, ma), concat(b, mb)) : new WinMap(concat(ma, a), concat(mb, b));
    }

    /** The values of {@code x} followed by those of {@code y}. */
    private static double[] concat(double[] x, double[] y) {
        double[] out = Arrays.copyOf(x, x.length + y.length);
        System.arraycopy(y, 0, out, x.length, y.length);
        return out;
    }
}
