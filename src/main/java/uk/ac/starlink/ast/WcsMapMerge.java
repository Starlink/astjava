package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * WcsMap's {@code MapMerge} (wcsmap.c) and the functions of wcsmap.c it uses: {@code CanMerge},
 * {@code CanSwap} and the swap {@code WcsPerm}. The permutations of a PermMap are found by {@link
 * WinMapMerge#permGet}, as wcsmap.c's {@code PermGet}, which is the same code as winmap.c's.
 *
 * <p>Each function that applies a Mapping of a list in the orientation the list gives it sets the
 * Mapping's Invert attribute to that value while it reads the Mapping, and puts the attribute back
 * afterwards, as C does.
 */
final class WcsMapMerge {

    /** C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    /** C's {@code DBL_MIN}. */
    private static final double DBL_MIN = Double.MIN_NORMAL;

    private WcsMapMerge() {}

    /**
     * wcsmap.c {@code MapMerge}. A WcsMap of type {@link WcsMap#AST__WCSBAD} becomes a UnitMap.
     * Otherwise, in series, it cancels with a neighbouring WcsMap that is its inverse ({@link
     * #canMerge}), the lower neighbour tried first. Failing that, if it can be swapped with a
     * neighbouring PermMap ({@link #canSwap}), it is swapped towards the nearer WcsMap beyond a
     * run of PermMaps that the swapped WcsMap would cancel with, or with a PermMap the swap makes
     * simpler; and failing that, each possible swap, the lower first, is tried on copies and kept
     * if simplifying either swapped Mapping changes its class.
     *
     * @param thisMap the nominated WcsMap, at {@code where} in the list
     * @return the index of the first entry changed, or -1 if nothing changed
     */
    static int wcsMapMerge(WcsMap thisMap, MergeList list, int where, boolean series) {
        int result = -1;
        int nin = list.map(where).getNin();

        if (thisMap.getWcsType() == WcsMap.AST__WCSBAD) {
            list.set(where, new UnitMap(nin), false);
            return where;
        }
        if (!series || list.size() <= 1) {
            return result;
        }
        int nmap = list.size();
        boolean hasLower = where > 0;
        boolean hasUpper = where < nmap - 1;

        boolean merge = false;
        int i1 = 0;
        if (hasLower) {
            i1 = where - 1;
            merge = canMerge(list.map(i1), list.invert(i1), list.map(where), list.invert(where));
        }
        if (!merge && hasUpper) {
            i1 = where;
            merge = canMerge(list.map(i1), list.invert(i1), list.map(i1 + 1), list.invert(i1 + 1));
        }
        if (merge) {
            list.replacePairWith(i1, new UnitMap(nin), false);
            return i1;
        }

        boolean[] do2 = new boolean[1];
        Mapping[] newwcsmap = new Mapping[1];
        boolean swaphi = false;
        if (where + 1 < nmap) {
            swaphi = canSwap(
                    list.map(where), list.map(where + 1), list.invert(where), list.invert(where + 1), do2, newwcsmap);
        }
        int nstep2 = -1;
        if (swaphi) {
            for (int i2 = where + 1; i2 < nmap; i2++) {
                if (canMerge(list.map(i2), list.invert(i2), newwcsmap[0], newwcsmap[0].getInvert())) {
                    nstep2 = i2 - where - 1;
                    break;
                }
                if (list.map(i2).getClass() != PermMap.class) {
                    break;
                }
            }
        }

        boolean[] do1 = new boolean[1];
        boolean swaplo = false;
        if (where > 0) {
            swaplo = canSwap(
                    list.map(where - 1), list.map(where), list.invert(where - 1), list.invert(where), do1, newwcsmap);
        }
        int nstep1 = -1;
        if (swaplo) {
            for (int j = where - 1; j >= 0; j--) {
                if (canMerge(list.map(j), list.invert(j), newwcsmap[0], newwcsmap[0].getInvert())) {
                    nstep1 = where - 1 - j;
                    break;
                }
                if (list.map(j).getClass() != PermMap.class) {
                    break;
                }
            }
        }

        boolean target;
        if (do1[0] || (nstep1 != -1 && (nstep2 == -1 || nstep2 > nstep1))) {
            target = true;
            i1 = where - 1;
        } else if (do2[0] || nstep2 != -1) {
            target = true;
            i1 = where;
        } else {
            target = false;
        }

        if (target) {
            wcsPerm(list, i1, where - i1);
            result = i1;
        } else if (swaphi || swaplo) {
            i1 = swaplo ? where - 1 : where;
            MergeList mc = new MergeList(
                    Arrays.asList((Mapping) list.map(i1).copy(), (Mapping)
                            list.map(i1 + 1).copy()),
                    Arrays.asList(list.invert(i1), list.invert(i1 + 1)));
            wcsPerm(mc, 0, where - i1);
            Mapping smc0 = mc.map(0).simplify();
            Mapping smc1 = mc.map(1).simplify();
            if (smc0.getClass() != mc.map(0).getClass()
                    || smc1.getClass() != mc.map(1).getClass()) {
                list.set(i1, mc.map(0), mc.invert(0));
                list.set(i1 + 1, mc.map(1), mc.invert(1));
                result = i1;
            }
        }
        return result;
    }

    /**
     * wcsmap.c {@code CanMerge}: whether two Mappings are WcsMaps that cancel: the same projection
     * type, number of axes and longitude and latitude axes, applied in opposite senses, with equal
     * projection parameters ({@link #equal}). As in C, the parameters compared are those up to the
     * number {@code map1} holds on each axis, each read with its default where it is not set
     * (wcsmap.c:914 compares {@code map1}'s count with itself, so the counts are never compared).
     */
    static boolean canMerge(Mapping map1, boolean inv1, Mapping map2, boolean inv2) {
        if (map1.getClass() != WcsMap.class || map2.getClass() != WcsMap.class) {
            return false;
        }
        WcsMap wcs1 = (WcsMap) map1;
        WcsMap wcs2 = (WcsMap) map2;
        if (wcs1.getWcsType() != wcs2.getWcsType() || wcs1.getNin() != wcs2.getNin() || inv1 == inv2) {
            return false;
        }
        if (wcs1.getWcsAxis(1) != wcs2.getWcsAxis(1) || wcs1.getWcsAxis(2) != wcs2.getWcsAxis(2)) {
            return false;
        }
        for (int i = 0; i < wcs1.getNin(); i++) {
            int np = wcs1.storedParamCount(i);
            for (int m = 0; m < np; m++) {
                if (!equal(wcs1.getPV(i, m), wcs2.getPV(i, m))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * wcsmap.c's {@code EQUAL}: whether two values are equal to within {@code 1e5} times the larger
     * of their scaled sum and {@code DBL_MIN}; two bad values are equal, and a bad value and a good
     * one are not.
     */
    private static boolean equal(double aa, double bb) {
        if (aa == AstObject.AST__BAD) {
            return bb == AstObject.AST__BAD;
        }
        return bb != AstObject.AST__BAD
                && Math.abs(aa - bb) <= 1.0e5 * Math.max((Math.abs(aa) + Math.abs(bb)) * DBL_EPSILON, DBL_MIN);
    }

    /**
     * wcsmap.c {@code CanSwap}: whether two Mappings applied in series, one of them a WcsMap, can
     * be swapped. Only a PermMap can be, and only if every link it makes between an input and an
     * output works in both directions and it passes both the longitude and latitude axes of the
     * WcsMap, or gives both a constant.
     *
     * @param simpler receives at index 0 whether the swap makes the PermMap simpler: one applied
     *     first with more outputs than inputs, or applied second with more inputs than outputs
     * @param newwcsmap receives at index 0 the Mapping the WcsMap becomes in the swap ({@link
     *     #wcsPerm}), or null if the Mappings cannot be swapped
     */
    // C compares the pointers: which of the pair is the WcsMap.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static boolean canSwap(
            Mapping map1, Mapping map2, boolean inv1, boolean inv2, boolean[] simpler, Mapping[] newwcsmap) {
        simpler[0] = false;
        newwcsmap[0] = null;
        boolean ret = false;
        int iwm;
        int invert0 = map1.swapInvert(inv1);
        int invert1 = map2.swapInvert(inv2);
        try {
            Mapping nowcs;
            Mapping wcsm;
            if (map1.getClass() == WcsMap.class) {
                iwm = 0;
                wcsm = map1;
                nowcs = map2;
            } else {
                iwm = 1;
                nowcs = map1;
                wcsm = map2;
            }
            if (nowcs.getClass() == PermMap.class) {
                // The class test above makes the other Mapping the nominated WcsMap.
                WcsMap wcs = (WcsMap) wcsm;
                int nin = nowcs.getNin();
                int nout = nowcs.getNout();
                WinMapMerge.PermParts perm = WinMapMerge.permGet((PermMap) nowcs);
                int[] outperm = perm.outperm;
                int[] inperm = perm.inperm;
                ret = true;
                for (int i = 0; i < nout; i++) {
                    int o = outperm[i];
                    if (o >= 0 && o < nin && inperm[o] != i) {
                        ret = false;
                        break;
                    }
                }
                for (int i = 0; i < nin; i++) {
                    int p = inperm[i];
                    if (p >= 0 && p < nout && outperm[p] != i) {
                        ret = false;
                        break;
                    }
                }
                if (ret) {
                    int lonax = wcs.getWcsAxis(1) - 1;
                    int latax = wcs.getWcsAxis(2) - 1;
                    if (wcsm == map1) {
                        if (inperm[lonax] < 0 && inperm[latax] < 0) {
                            ret = true;
                        } else if (inperm[lonax] < 0
                                || inperm[lonax] >= nout
                                || inperm[latax] < 0
                                || inperm[latax] >= nout) {
                            ret = false;
                        }
                    } else {
                        if (outperm[lonax] < 0 && outperm[latax] < 0) {
                            ret = true;
                        } else if (outperm[lonax] < 0
                                || outperm[lonax] >= nin
                                || outperm[latax] < 0
                                || outperm[latax] >= nin) {
                            ret = false;
                        }
                    }
                }
                if (ret) {
                    simpler[0] = nowcs == map1 ? nout > nin : nin > nout;
                }
            }
        } finally {
            map2.restoreInvert(invert1);
            map1.restoreInvert(invert0);
        }
        if (ret) {
            MergeList maps = new MergeList(Arrays.asList(map1, map2), Arrays.asList(inv1, inv2));
            wcsPerm(maps, 0, iwm);
            newwcsmap[0] = maps.map(1 - iwm);
        }
        return ret;
    }

    /**
     * wcsmap.c {@code WcsPerm}: swaps a WcsMap and a PermMap applied in series, the entries at
     * {@code base} and {@code base + 1}, the WcsMap at {@code base + iwm}, which {@link #canSwap}
     * has found can be swapped. If the PermMap gives both the longitude and the latitude axis a
     * constant, the WcsMap becomes a UnitMap and a new PermMap gives those axes the constants
     * passed through the WcsMap. Otherwise a new WcsMap with the same projection parameters takes
     * its longitude and latitude axes from where the PermMap puts them, and is applied as the old
     * one was; the PermMap is kept. Each Mapping is then applied with its own Invert attribute,
     * the kept PermMap's being the one it had before the swap.
     */
    static void wcsPerm(MergeList maps, int base, int iwm) {
        WcsMap wm = (WcsMap) maps.map(base + iwm);
        PermMap pm = (PermMap) maps.map(base + 1 - iwm);
        boolean winv = maps.invert(base + iwm);
        int oldPinv = pm.swapInvert(maps.invert(base + 1 - iwm));
        int oldWinv = wm.swapInvert(winv);
        Mapping newpm;
        Mapping newwm;
        try {
            int type = wm.getWcsType();
            int lonax = wm.getWcsAxis(1) - 1;
            int latax = wm.getWcsAxis(2) - 1;
            WinMapMerge.PermParts perm = WinMapMerge.permGet(pm);
            int[] outperm = perm.outperm;
            int[] inperm = perm.inperm;
            double[] consts = perm.consts;
            int npin = pm.getNin();
            int npout = pm.getNout();
            if (iwm == 0 && inperm[lonax] < 0 && inperm[latax] < 0) {
                transformConstants(wm, inperm, consts, false);
                newpm = PermMap.fromInternal(npin, inperm, npout, outperm, consts);
                newwm = new UnitMap(npout);
            } else if (iwm != 0 && outperm[lonax] < 0 && outperm[latax] < 0) {
                transformConstants(wm, outperm, consts, true);
                newpm = PermMap.fromInternal(npin, inperm, npout, outperm, consts);
                newwm = new UnitMap(npin);
            } else {
                WcsMap w;
                if (iwm == 0) {
                    w = new WcsMap(npout, type, inperm[lonax] + 1, inperm[latax] + 1);
                } else {
                    w = new WcsMap(npin, type, outperm[lonax] + 1, outperm[latax] + 1);
                }
                WcsMap.copyPV(wm, w);
                w.setInvert(winv);
                newwm = w;
                newpm = pm;
            }
        } finally {
            wm.restoreInvert(oldWinv);
            pm.restoreInvert(oldPinv);
        }
        maps.set(base + iwm, newpm, newpm.getInvert());
        maps.set(base + 1 - iwm, newwm, newwm.getInvert());
    }

    /**
     * The constants of a PermMap beside a WcsMap that it gives both the longitude and the latitude
     * axis, passed through the WcsMap in its current orientation, forward or inverse, as one
     * position whose other axes are bad: each axis whose entry of {@code perm} refers to a
     * constant gets the transformed value in place of the constant.
     */
    private static void transformConstants(WcsMap wm, int[] perm, double[] consts, boolean forward) {
        int n = perm.length;
        double[][] p = new double[n][1];
        for (int i = 0; i < n; i++) {
            p[i][0] = perm[i] < 0 ? consts[-perm[i] - 1] : AstObject.AST__BAD;
        }
        double[][] q = wm.transform(p, 1, forward);
        for (int i = 0; i < n; i++) {
            if (perm[i] < 0) {
                consts[-perm[i] - 1] = q[i][0];
            }
        }
    }
}
