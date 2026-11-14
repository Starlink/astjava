package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * MatrixMap's {@code MapMerge} (matrixmap.c) and the functions of matrixmap.c it uses: {@code
 * CanSwap}, {@code PermOK}, {@code ScalingRowCol}, the swaps {@code MatWin} and {@code
 * MatPermSwap}, and the merges {@code MatMat}, {@code MatZoom}, {@code MatPerm} and {@code
 * MatWin2}. A PermMap's permutations are found as matrixmap.c's {@code PermGet} finds them, which
 * is what winmap.c's does ({@link WinMapMerge#permGet}).
 *
 * <p>Each function that applies a Mapping of a list in the orientation the list gives it sets the
 * Mapping's Invert attribute to that value while it reads the Mapping, and puts the attribute back
 * afterwards, as C does.
 */
final class MatrixMapMerge {

    /** C's {@code DBL_EPSILON}, the limit {@link #scalingRowCol} compares elements with. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    private MatrixMapMerge() {}

    /**
     * matrixmap.c {@code MapMerge}. A square unit MatrixMap becomes a UnitMap, a square diagonal one
     * with equal diagonal elements a ZoomMap, and a square full one whose off-diagonal elements are
     * all zero a diagonal MatrixMap. Otherwise, in series, it merges with a neighbouring MatrixMap,
     * ZoomMap, UnitMap or PermMap whose two directions agree, or, if it is diagonal, a neighbouring
     * WinMap; failing that, it is swapped with a neighbouring WinMap or PermMap towards the nearer
     * Mapping it could merge with beyond a run of WinMaps and PermMaps, or towards a PermMap the swap
     * makes simpler, and the list after the swap is offered to a merge; and failing that each
     * possible swap is tried on copies and kept if either swapped Mapping then simplifies to a
     * Mapping of another class.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    static int matrixMapMerge(MergeList list, int where, boolean series) {
        MatrixMap mm = (MatrixMap) list.map(where);
        boolean invert = list.invert(where);
        int nin = invert ? mm.createdNout() : mm.createdNin();
        int nout = invert ? mm.createdNin() : mm.createdNout();

        Mapping map2 = replaceWithSimpler(mm, invert, nin, nout);
        if (map2 != null) {
            list.set(where, map2, false);
            return where;
        }

        Mapping lower = where > 0 ? list.map(where - 1) : null;
        Mapping upper = where < list.size() - 1 ? list.map(where + 1) : null;
        if (!series) {
            return -1;
        }

        Mapping nclass;
        int i1;
        int i2;
        if (lower != null && directClass(lower)) {
            nclass = lower;
            i1 = where - 1;
            i2 = where;
        } else if (upper != null && directClass(upper)) {
            nclass = upper;
            i1 = where;
            i2 = where + 1;
        } else {
            nclass = null;
            i1 = 0;
            i2 = 0;
        }
        if (nclass != null && isClass(nclass, PermMap.class) && !permOk(list.map(i1 == where ? i2 : i1))) {
            nclass = null;
        }
        if (nclass == null && mm.storageForm() == MatrixMap.DIAGONAL) {
            if (lower != null && isClass(lower, WinMap.class)) {
                nclass = lower;
                i1 = where - 1;
                i2 = where;
            } else if (upper != null && isClass(upper, WinMap.class)) {
                nclass = upper;
                i1 = where;
                i2 = where + 1;
            }
        }

        if (nclass != null) {
            return mergeDirectly(list, where, nclass, i1, i2);
        }
        return swap(list, where, lower, upper, series);
    }

    /**
     * The first step of matrixmap.c {@code MapMerge}: the Mapping a MatrixMap applied with the
     * Invert value {@code invert}, of {@code nin} inputs and {@code nout} outputs in that
     * orientation, can be replaced by without reference to its neighbours, applied forward; or null
     * if there is none. A square unit matrix gives a UnitMap. A square diagonal matrix whose stored
     * forward elements are equal to within {@code astEQUAL}, and whose first is not bad, gives a
     * ZoomMap of the first element of the matrix the list applies, unless that is zero. A square
     * full matrix whose stored forward off-diagonal elements are all exactly zero gives a diagonal
     * MatrixMap of its stored forward diagonal; as in C (matrixmap.c:1896-1925) that MatrixMap is
     * applied forward whatever {@code invert} is.
     */
    static Mapping replaceWithSimpler(MatrixMap mm, boolean invert, int nin, int nout) {
        int form = mm.storageForm();
        double[] fMatrix = mm.storedForward();
        double[] iMatrix = mm.storedInverse();
        if (form == MatrixMap.UNIT && nin == nout) {
            return new UnitMap(nin);
        } else if (form == MatrixMap.DIAGONAL
                && nin == nout
                && fMatrix != null
                && iMatrix != null
                && fMatrix[0] != AstObject.AST__BAD) {
            boolean zoom = true;
            for (int i = 1; i < nin; i++) {
                if (!MappingHelpers.astEqual(fMatrix[i], fMatrix[i - 1])) {
                    zoom = false;
                    break;
                }
            }
            if (zoom) {
                double factor = invert ? iMatrix[0] : fMatrix[0];
                if (factor != 0.0) {
                    return new ZoomMap(nin, factor);
                }
            }
            return null;
        } else if (form == MatrixMap.FULL && nin == nout && fMatrix != null) {
            double[] newMat = new double[nin];
            int b = 0;
            for (int i = 0; i < nin; i++) {
                for (int j = 0; j < nout; j++, b++) {
                    if (i == j) {
                        newMat[i] = fMatrix[b];
                    } else if (fMatrix[b] != 0.0) {
                        return null;
                    }
                }
            }
            return new MatrixMap(nin, nout, MatrixMap.DIAGONAL, newMat);
        } else {
            return null;
        }
    }

    /** Whether the class of {@code m} is exactly {@code cls}, as C's comparison of class names tests. */
    private static boolean isClass(Mapping m, Class<?> cls) {
        return m.getClass() == cls;
    }

    /** Whether a MatrixMap may merge directly with a neighbour of the class of {@code m}. */
    private static boolean directClass(Mapping m) {
        return isClass(m, MatrixMap.class)
                || isClass(m, ZoomMap.class)
                || isClass(m, PermMap.class)
                || isClass(m, UnitMap.class);
    }

    /**
     * Whether a MatrixMap moving through the list towards {@code m} would stop there to merge with
     * it, as C's searches for the nearest such Mapping test.
     */
    private static boolean mergeTarget(Mapping m) {
        return isClass(m, MatrixMap.class)
                || isClass(m, ZoomMap.class)
                || (isClass(m, PermMap.class) && permOk(m))
                || isClass(m, UnitMap.class);
    }

    /** Whether a MatrixMap may be swapped past {@code m}, as C's searches test. */
    private static boolean swappable(Mapping m) {
        return isClass(m, WinMap.class) || isClass(m, PermMap.class);
    }

    /**
     * The MatrixMap at {@code where} merged with its neighbour {@code nclass}, the pair being the
     * entries {@code i1} and {@code i2}, which the merged Mapping replaces.
     *
     * @return {@code i1}
     */
    private static int mergeDirectly(MergeList list, int where, Mapping nclass, int i1, int i2) {
        Mapping first = list.map(i1);
        Mapping second = list.map(i2);
        boolean inv1 = list.invert(i1);
        boolean inv2 = list.invert(i2);
        boolean matFirst = i1 == where;
        Mapping newmap;
        boolean invert = false;
        if (isClass(nclass, MatrixMap.class)) {
            newmap = matMat((MatrixMap) first, (MatrixMap) second, inv1, inv2);
        } else if (isClass(nclass, ZoomMap.class)) {
            newmap = matFirst
                    ? matZoom((MatrixMap) first, (ZoomMap) second, inv1, inv2)
                    : matZoom((MatrixMap) second, (ZoomMap) first, inv2, inv1);
        } else if (isClass(nclass, PermMap.class)) {
            newmap = matFirst
                    ? matPerm((MatrixMap) first, (PermMap) second, inv1, inv2, true)
                    : matPerm((MatrixMap) second, (PermMap) first, inv2, inv1, false);
        } else if (isClass(nclass, WinMap.class)) {
            newmap = matFirst
                    ? matWin2((MatrixMap) first, (WinMap) second, inv1, inv2, true)
                    : matWin2((MatrixMap) second, (WinMap) first, inv2, inv1, false);
        } else if (isClass(nclass, UnitMap.class)) {
            newmap = list.map(where);
            invert = list.invert(where);
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astMapMerge(MatrixMap): Cannot merge a MatrixMap with a " + nclass.astClass()
                            + " (internal AST programming error).");
        }
        list.replacePairWith(i1, newmap, invert);
        return i1;
    }

    /**
     * The swap part of matrixmap.c {@code MapMerge}, in series, when the MatrixMap at {@code where}
     * can merge with neither neighbour.
     *
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    private static int swap(MergeList list, int where, Mapping lower, Mapping upper, boolean series) {
        int nmap = list.size();
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
                Mapping smc0 = mc.map(0).simplify();
                Mapping smc1 = mc.map(1).simplify();
                if (smc0.getClass() != mc.map(0).getClass()
                        || smc1.getClass() != mc.map(1).getClass()) {
                    list.set(j1, mc.map(0), mc.invert(0));
                    list.set(j1 + 1, mc.map(1), mc.invert(1));
                    return j1;
                }
            }
        }
        return -1;
    }

    /**
     * Swaps the MatrixMap at {@code base + imm} with the Mapping {@code nclass} beside it, by the
     * function for that Mapping's class.
     */
    private static void swapPair(MergeList maps, int base, int imm, Mapping nclass) {
        if (isClass(nclass, WinMap.class)) {
            matWin(maps, base, imm);
        } else if (isClass(nclass, PermMap.class)) {
            matPermSwap(maps, base, imm);
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astMapMerge(MatrixMap): Cannot swap a MatrixMap with a " + nclass.astClass()
                            + " (internal AST programming error).");
        }
    }

    // ------------------------------------------------------------------
    // Swapping
    // ------------------------------------------------------------------

    /**
     * matrixmap.c {@code CanSwap}: whether two Mappings applied in series, one of them a MatrixMap,
     * can be swapped. A WinMap can be. A PermMap can be if the MatrixMap is square and has both its
     * matrices (or is a unit matrix), every link the PermMap makes between an input and an output
     * works in both directions, and each axis to which the PermMap gives a constant, on the side
     * facing the MatrixMap, is a scaling row and column of the MatrixMap ({@link #scalingRowCol}).
     *
     * @param simpler receives at index 0 whether swapping with a PermMap makes it simpler: one
     *     applied first with more outputs than inputs, or applied second with more inputs than
     *     outputs
     * @throws AstException with status {@code AST__RDERR} if the PermMap does not have as many
     *     coordinates on the side facing the MatrixMap as the MatrixMap has
     */
    // C compares the pointers: which of the two is the PermMap.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static boolean canSwap(Mapping map1, Mapping map2, boolean inv1, boolean inv2, boolean[] simpler) {
        simpler[0] = false;
        boolean ret = false;
        int invert0 = map1.swapInvert(inv1);
        int invert1 = map2.swapInvert(inv2);
        try {
            MatrixMap mat;
            Mapping nomat;
            if (isClass(map1, MatrixMap.class)) {
                mat = (MatrixMap) map1;
                nomat = map2;
            } else {
                nomat = map1;
                mat = (MatrixMap) map2;
            }
            int naxes = mat.getNin();
            int form = mat.storageForm();
            if (isClass(nomat, WinMap.class)) {
                ret = true;
            } else if (isClass(nomat, PermMap.class)
                    && naxes == mat.getNout()
                    && (form == MatrixMap.UNIT || (mat.storedInverse() != null && mat.storedForward() != null))) {
                int nin = nomat.getNin();
                int nout = nomat.getNout();
                WinMapMerge.PermParts perm = WinMapMerge.permGet((PermMap) nomat);
                int[] outperm = perm.outperm;
                int[] inperm = perm.inperm;
                ret = true;
                for (int i = 0; i < nout; i++) {
                    if (outperm[i] >= 0 && outperm[i] < nin && inperm[outperm[i]] != i) {
                        ret = false;
                        break;
                    }
                }
                for (int i = 0; i < nin; i++) {
                    if (inperm[i] >= 0 && inperm[i] < nout && outperm[inperm[i]] != i) {
                        ret = false;
                        break;
                    }
                }
                if (ret && nomat == map1) {
                    if (nout != naxes) {
                        throw Ast.err(
                                AstException.AST__RDERR,
                                "PermMap produces " + nout + " outputs, but the followingMatrixMap has " + naxes
                                        + " inputs\n");
                    }
                    for (int i = 0; i < nout; i++) {
                        if ((outperm[i] < 0 || outperm[i] >= nin) && !scalingRowCol(mat, i)) {
                            ret = false;
                            break;
                        }
                    }
                }
                if (ret && nomat == map2) {
                    if (nin != naxes) {
                        throw Ast.err(
                                AstException.AST__RDERR,
                                "Inverse PermMap produces " + nin + " inputs, but the preceding MatrixMap has " + naxes
                                        + " outputs\n");
                    }
                    for (int i = 0; i < nin; i++) {
                        if ((inperm[i] < 0 || inperm[i] >= nout) && !scalingRowCol(mat, i)) {
                            ret = false;
                            break;
                        }
                    }
                }
                if (ret) {
                    simpler[0] = nomat == map1 ? nout > nin : nin > nout;
                }
            }
        } finally {
            map2.restoreInvert(invert1);
            map1.restoreInvert(invert0);
        }
        return ret;
    }

    /**
     * matrixmap.c {@code ScalingRowCol}: whether row and column {@code axis} of a square MatrixMap
     * are zero apart from a non-zero diagonal element. A unit or diagonal MatrixMap always is; in a
     * full one the stored forward elements are compared with {@code DBL_EPSILON}.
     */
    static boolean scalingRowCol(MatrixMap map, int axis) {
        if (map.storageForm() != MatrixMap.FULL) {
            return true;
        }
        int ncol = map.getNin();
        double[] f = map.storedForward();
        int el = axis * ncol;
        for (int i = 0; i < ncol; i++, el++) {
            if (i == axis ? Math.abs(f[el]) <= DBL_EPSILON : Math.abs(f[el]) > DBL_EPSILON) {
                return false;
            }
        }
        el = axis;
        for (int i = 0; i < ncol; i++, el += ncol) {
            if (i == axis ? Math.abs(f[el]) <= DBL_EPSILON : Math.abs(f[el]) > DBL_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * matrixmap.c {@code PermOK}: whether a PermMap can merge with a MatrixMap: it has as many
     * outputs as inputs, and the positions {@code [1, 2, 3, ...]} and {@code [0, -1, -2, ...]},
     * transformed forward and back with its own Invert attribute, come back unchanged.
     */
    static boolean permOk(Mapping pm) {
        int nin = pm.getNin();
        int nout = pm.getNout();
        if (nin != nout) {
            return false;
        }
        double[][] ptr1 = new double[nin][2];
        for (int i = 0; i < nin; i++) {
            ptr1[i][0] = i + 1;
            ptr1[i][1] = -i;
        }
        double[][] ptr2 = pm.transform(ptr1, 2, true);
        ptr1 = pm.transform(ptr2, 2, false);
        for (int i = 0; i < nin; i++) {
            if (ptr1[i][0] != i + 1 || ptr1[i][1] != -i) {
                return false;
            }
        }
        return true;
    }

    /**
     * matrixmap.c {@code MatWin}: swaps a WinMap and a MatrixMap applied in series, the entries at
     * {@code base} and {@code base + 1}, the MatrixMap at {@code base + imm}. The WinMap's scales go
     * into the matrix, and its shifts, transformed by the matrix, become the shifts of a WinMap of
     * unit scales on the other side; each new Mapping is simplified, and is applied with its own
     * Invert attribute.
     */
    static void matWin(MergeList maps, int base, int imm) {
        WinMap wm = (WinMap) maps.map(base + 1 - imm);
        MatrixMap mm = (MatrixMap) maps.map(base + imm);
        int oldWinv = wm.swapInvert(maps.invert(base + 1 - imm));
        int oldMinv = mm.swapInvert(maps.invert(base + imm));
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
            if (imm == 1) {
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
        maps.set(base + imm, sw1, sw1.getInvert());
        Mapping sm2 = m2.simplify();
        maps.set(base + 1 - imm, sm2, sm2.getInvert());
    }

    /**
     * matrixmap.c {@code MatPermSwap}: swaps a PermMap and a MatrixMap applied in series, the
     * entries at {@code base} and {@code base + 1}, the MatrixMap at {@code base + imm}, which
     * {@link #canSwap} has found can be swapped. The new MatrixMap is the old one's matrix permuted
     * by the PermMap, with a unit row for each axis the PermMap gives a constant; each constant the
     * PermMap gives on the side facing the MatrixMap is divided by (MatrixMap first) or multiplied
     * by (PermMap first) the diagonal element of the old matrix for that axis. As in C, the
     * MatrixMap is expanded to full form and compressed again. Each new Mapping is simplified, and
     * is applied with its own Invert attribute.
     */
    static void matPermSwap(MergeList maps, int base, int imm) {
        PermMap pm = (PermMap) maps.map(base + 1 - imm);
        MatrixMap mm = (MatrixMap) maps.map(base + imm);
        int oldPinv = pm.swapInvert(maps.invert(base + 1 - imm));
        MatrixMap mmnew;
        PermMap pmnew;
        try {
            mm.expandMatrix();
            double[] matrix = maps.invert(base + imm) ? mm.storedInverse() : mm.storedForward();
            int nin = pm.getNin();
            int nout = pm.getNout();
            WinMapMerge.PermParts perm = WinMapMerge.permGet(pm);
            int[] outperm = perm.outperm;
            int[] inperm = perm.inperm;
            double[] consts = perm.consts;
            if (imm == 0) {
                double[] outMat = new double[nout * nout];
                for (int i = 0; i < nout; i++) {
                    int row = outperm[i];
                    int outEl = nout * i;
                    if (row >= 0 && row < nin) {
                        for (int k = 0; k < nout; k++) {
                            int col = outperm[k];
                            outMat[outEl++] = col >= 0 && col < nin ? matrix[nin * row + col] : 0.0;
                        }
                    } else {
                        for (int k = 0; k < nout; k++) {
                            outMat[outEl++] = k != i ? 0.0 : 1.0;
                        }
                    }
                }
                mmnew = new MatrixMap(nout, nout, MatrixMap.FULL, outMat);
                for (int i = 0; i < nin; i++) {
                    if (inperm[i] < 0) {
                        int k = -inperm[i] - 1;
                        if (consts[k] != AstObject.AST__BAD) {
                            double matel = matrix[i * (nin + 1)];
                            if (matel != 0.0 && matel != AstObject.AST__BAD) {
                                consts[k] /= matel;
                            } else {
                                consts[k] = AstObject.AST__BAD;
                            }
                        }
                    }
                }
            } else {
                double[] outMat = new double[nin * nin];
                for (int i = 0; i < nin; i++) {
                    int row = inperm[i];
                    int outEl = nin * i;
                    if (row >= 0 && row < nout) {
                        for (int k = 0; k < nin; k++) {
                            int col = inperm[k];
                            outMat[outEl++] = col >= 0 && col < nout ? matrix[nout * row + col] : 0.0;
                        }
                    } else {
                        for (int k = 0; k < nin; k++) {
                            outMat[outEl++] = k != i ? 0.0 : 1.0;
                        }
                    }
                }
                mmnew = new MatrixMap(nin, nin, MatrixMap.FULL, outMat);
                for (int i = 0; i < nout; i++) {
                    if (outperm[i] < 0) {
                        int k = -outperm[i] - 1;
                        if (consts[k] != AstObject.AST__BAD) {
                            double matel = matrix[i * (nout + 1)];
                            if (matel != AstObject.AST__BAD) {
                                consts[k] *= matel;
                            } else {
                                consts[k] = AstObject.AST__BAD;
                            }
                        }
                    }
                }
            }
            pmnew = PermMap.fromInternal(nin, inperm, nout, outperm, consts);
        } finally {
            mm.compressMatrix();
            pm.restoreInvert(oldPinv);
        }
        Mapping spmnew = pmnew.simplify();
        maps.set(base + imm, spmnew, spmnew.getInvert());
        Mapping smmnew = mmnew.simplify();
        maps.set(base + 1 - imm, smmnew, smmnew.getInvert());
    }

    // ------------------------------------------------------------------
    // Merging
    // ------------------------------------------------------------------

    /**
     * matrixmap.c {@code MatMat}: the MatrixMap equal to two MatrixMaps applied in series, each with
     * the Invert value given: their product ({@link MatrixMap#mtrMult}).
     */
    static MatrixMap matMat(MatrixMap map1, MatrixMap map2, boolean inv1, boolean inv2) {
        int invert0 = map1.swapInvert(inv1);
        int invert1 = map2.swapInvert(inv2);
        try {
            return map1.mtrMult(map2);
        } finally {
            map2.restoreInvert(invert1);
            map1.restoreInvert(invert0);
        }
    }

    /**
     * matrixmap.c {@code MatZoom}: the MatrixMap equal to a MatrixMap and a ZoomMap applied in
     * series, each with the Invert value given: the MatrixMap followed by a diagonal MatrixMap of
     * the effective zoom factor, which commutes with it.
     */
    static MatrixMap matZoom(MatrixMap mm, ZoomMap zm, boolean minv, boolean zinv) {
        int oldMinv = mm.swapInvert(minv);
        int oldZinv = zm.swapInvert(zinv);
        try {
            int nrow = mm.getNout();
            double zfac = zm.getZoom();
            if (zinv) {
                zfac = 1.0 / zfac;
            }
            double[] matrix = new double[nrow];
            Arrays.fill(matrix, zfac);
            MatrixMap mm2 = new MatrixMap(nrow, nrow, MatrixMap.DIAGONAL, matrix);
            return mm.mtrMult(mm2);
        } finally {
            zm.restoreInvert(oldZinv);
            mm.restoreInvert(oldMinv);
        }
    }

    /**
     * matrixmap.c {@code MatPerm}: the MatrixMap equal to a MatrixMap and a PermMap whose two
     * directions agree ({@link #permOk}) applied in series, each with the Invert value given, the
     * MatrixMap first if {@code mat1}. The PermMap is represented by the matrix whose rows are the
     * unit vectors transformed by its inverse.
     */
    static MatrixMap matPerm(MatrixMap mm, PermMap pm, boolean minv, boolean pinv, boolean mat1) {
        int oldMinv = mm.swapInvert(minv);
        int oldPinv = pm.swapInvert(pinv);
        try {
            int naxes = pm.getNin();
            double[][] ptr1 = new double[naxes][naxes];
            for (int i = 0; i < naxes; i++) {
                ptr1[i][i] = 1.0;
            }
            double[][] ptr2 = pm.transform(ptr1, naxes, false);
            double[] matrix = new double[naxes * naxes];
            int p = 0;
            for (int j = 0; j < naxes; j++) {
                for (int i = 0; i < naxes; i++) {
                    matrix[p++] = ptr2[i][j];
                }
            }
            MatrixMap mm2 = new MatrixMap(naxes, naxes, MatrixMap.FULL, matrix);
            return mat1 ? mm.mtrMult(mm2) : mm2.mtrMult(mm);
        } finally {
            pm.restoreInvert(oldPinv);
            mm.restoreInvert(oldMinv);
        }
    }

    /**
     * matrixmap.c {@code MatWin2}: the WinMap equal to a diagonal MatrixMap and a WinMap applied in
     * series, each with the Invert value given, the MatrixMap first if {@code mat1}. The WinMap's
     * scales, and if it comes first its shifts, are transformed by the MatrixMap; the result is made
     * from its corners, the unit window mapped on to the shifted and scaled one, as C makes it.
     *
     * @throws AstException with status {@code AST__INTER} if the WinMap does not have as many axes
     *     as the MatrixMap has coordinates on the side facing it
     */
    static WinMap matWin2(MatrixMap mm, WinMap wm, boolean minv, boolean winv, boolean mat1) {
        int oldMinv = mm.swapInvert(minv);
        int oldWinv = wm.swapInvert(winv);
        try {
            int ncol = mm.getNin();
            int nrow = mm.getNout();
            double[][] terms = wm.winTerms();
            double[] shifts = terms[0];
            double[] scales = terms[1];
            int nt = shifts.length;
            double[] newscales;
            double[] newshifts;
            if (mat1) {
                if (nt != nrow) {
                    throw Ast.err(
                            AstException.AST__INTER,
                            "astMapMerge(" + mm.astClass() + "): WinMap has " + nt + " axes, but MatrixMap has " + nrow
                                    + " rows (internal AST programming error).");
                }
                double[] scales2 = new double[ncol];
                System.arraycopy(scales, 0, scales2, 0, Math.min(ncol, nrow));
                newscales = tranN(mm, scales2);
                newshifts = shifts;
            } else {
                if (nt != ncol) {
                    throw Ast.err(
                            AstException.AST__INTER,
                            "astMapMerge(" + mm.astClass() + "): WinMap has " + nt + " axes, but MatrixMap has " + ncol
                                    + " columns (internal AST programming error).");
                }
                newscales = tranN(mm, scales);
                newshifts = tranN(mm, shifts);
            }
            double[] ina = new double[nt];
            double[] inb = new double[nt];
            Arrays.fill(inb, 1.0);
            double[] outa = Arrays.copyOf(newshifts, nt);
            double[] outb = new double[nt];
            for (int i = 0; i < nt; i++) {
                outb[i] = newscales[i] + newshifts[i];
            }
            return new WinMap(nt, ina, inb, outa, outb);
        } finally {
            wm.restoreInvert(oldWinv);
            mm.restoreInvert(oldMinv);
        }
    }

    /** The point {@code in} transformed forward by {@code m}, as C's {@code astTranN} gives it for one point. */
    private static double[] tranN(Mapping m, double[] in) {
        double[][] pin = new double[in.length][1];
        for (int i = 0; i < in.length; i++) {
            pin[i][0] = in[i];
        }
        double[][] pout = m.transform(pin, 1, true);
        double[] out = new double[pout.length];
        for (int i = 0; i < pout.length; i++) {
            out[i] = pout[i][0];
        }
        return out;
    }
}
