package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether WCS axes of a pixel to WCS Mapping can be described by the {@code -TAB} algorithm of
 * FITS-WCS paper III: C's {@code IsMapTab1D}, {@code IsMapTab2D}, {@code AdaptLut} and {@code
 * ChangePermSplit} (fitschan.c).
 *
 * <p>C decides with the Mapping alone whether an axis can be tabulated, and then writes the table
 * into a FitsTable. This library has no FitsTable, so the decision is ported as C makes it, and
 * where C would create or use a FitsTable an {@link UnsupportedOperationException} is thrown
 * instead.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/map_tab.rs}, following fitschan.c.
 */
final class FitsMapTab {

    private static final double AST__BAD = AstObject.AST__BAD;

    private FitsMapTab() {}

    /**
     * C's {@code IsMapTab1D}: whether output {@code iax} of the pixel to WCS Mapping {@code map}
     * can be described by a look-up table for FITS-WCS axis {@code iwcs}, and if so the Mapping
     * from that WCS axis to the one grid axis that feeds it.
     *
     * <p>The grid axis is found by splitting off the output from the inverse of {@code map};
     * failing that, by splitting off each input of {@code map} in turn until one feeds the output;
     * and failing that, by splitting the inverse again with every PermMap using its alternative
     * split. The output can be tabulated if exactly one grid axis feeds it and either the Mapping
     * from the grid axis holds a LutMap applied forward in series, whose table C uses, or the
     * number of pixels on the grid axis is known, when C samples the Mapping; either way C stores
     * the table in a FitsTable.
     *
     * <p>When the inverse split gives more than one grid axis, C returns the split Mapping as it
     * is, with no table columns; that is kept.
     *
     * @param map the pixel to WCS Mapping; its Invert attribute is changed while it is split, and
     *     restored
     * @param scale the factor from AST units to FITS units of the tabulated values
     * @param unit the unit of the table column, or null for the Unit of axis {@code iax} of
     *     {@code wcsfrm}
     * @param wcsfrm the WCS Frame
     * @param dim the number of pixels along each grid axis, {@code AST__BAD} where unknown
     * @param iax the zero-based output of {@code map}
     * @param iwcs the zero-based FITS-WCS axis, which names the table columns
     * @param icolmain receives -1 in its first element: the main column C adds, which there is none of
     * @param icolindex receives -1 in its first element: the index column C adds, which there is
     *     none of
     * @param interp receives in its first element the LutInterp attribute of the LutMap whose table
     *     C uses, or 0
     * @return C's Mapping, or null where C returns NULL (the output cannot be tabulated)
     * @throws UnsupportedOperationException where C would create a FitsTable to hold the table,
     *     which needs FitsTable
     */
    static Mapping isMapTab1D(
            Mapping map,
            double scale,
            String unit,
            Frame wcsfrm,
            double[] dim,
            int iax,
            int iwcs,
            int[] icolmain,
            int[] icolindex,
            int[] interp) {
        icolmain[0] = -1;
        icolindex[0] = -1;
        interp[0] = 0;
        String colunit = unit != null ? unit : wcsfrm.getUnit(iax + 1);

        // The grid inputs feeding the output and the Mapping from the output to them: split off
        // the output from the inverse Mapping, since astMapSplit splits off inputs.
        MapSplit split = splitInverse(map, iax);
        int[] ins = split != null ? split.outputs : null;
        Mapping ret = split != null ? split.map : null;

        // Failing that, each input in turn, to see if it alone feeds the output.
        if (ins == null) {
            int nin = map.getNin();
            for (int iin = 0; iin < nin && ins == null; iin++) {
                MapSplit s = map.mapSplit(new int[] {iin});
                if (s != null) {
                    int[] outs = s.outputs;
                    int nout = s.map.getNout();
                    int iout = 0;
                    while (iout < nout && outs[iout] != iax) {
                        iout++;
                    }
                    if (iout < nout) {
                        // The 1-input split followed by a PermMap selecting the output, inverted
                        // to map the WCS axis to the grid axis.
                        int[] inperm = new int[nout];
                        Arrays.fill(inperm, -1);
                        inperm[iout] = 0;
                        PermMap pm = PermMap.fromInternal(nout, inperm, 1, new int[] {iout}, null);
                        ret = new CmpMap(s.map, pm, true);
                        ret.invert();
                        ins = new int[] {iin};
                    }
                }
            }
        }

        // Failing that, the inverse again, with every PermMap using its alternative split.
        if (ins == null) {
            map.invert();
            MapSplit s;
            try {
                Mapping tmap = (Mapping) map.copy();
                changePermSplit(tmap);
                s = tmap.mapSplit(new int[] {iax});
            } finally {
                map.invert();
            }
            ins = s != null ? s.outputs : null;
            ret = s != null ? s.map : null;
        }

        if (ins != null && ret != null && ret.getNout() == 1) {
            // From the grid axis to the WCS axis.
            ret.invert();

            // C searches the Mappings in series for a LutMap applied forward, whose table it
            // uses, transformed by the Mappings after it, with the grid positions of its entries
            // given by the inverse of the Mappings before it.
            List<Mapping> maps = new ArrayList<>();
            List<Boolean> inverts = new ArrayList<>();
            ret.mapList(true, ret.getInvert(), maps, inverts);
            int ilut = -1;
            for (int imap = 0; imap < maps.size(); imap++) {
                if (maps.get(imap) instanceof LutMap && !inverts.get(imap)) {
                    ilut = imap;
                    break;
                }
            }
            if (ilut >= 0) {
                interp[0] = ((LutMap) maps.get(ilut)).getLutInterp();
                // C combines the Mappings either side of the LutMap and takes its table, which are
                // used only to fill the FitsTable it makes next.
                throw new UnsupportedOperationException("IsMapTab1D(FitsChan): the look-up table for FITS-WCS axis "
                        + (iwcs + 1) + " (unit '" + colunit + "') needs FitsTable");
            } else if (dim[ins[0]] != AST__BAD) {
                // Without a LutMap, C samples the Mapping, the number of pixels along the grid
                // axis being known.
                double[] x = {1.0, dim[ins[0]]};
                double[] v = ret.tran1(2, x, true);
                double[][] work = {{x[0]}, {v[0]}};
                int[] nlut = {1};
                adaptLut(ret, 3, 0.25, x[0], x[1], v[0], v[1], work, nlut);
                throw new UnsupportedOperationException("IsMapTab1D(FitsChan): the look-up table for FITS-WCS axis "
                        + (iwcs + 1) + " (unit '" + colunit + "') needs FitsTable");
            }
            ret = null;
        }
        return ret;
    }

    /** The split of output {@code iax} from the inverse of {@code map}, which is left as it was. */
    private static MapSplit splitInverse(Mapping map, int iax) {
        map.invert();
        try {
            return map.mapSplit(new int[] {iax});
        } finally {
            map.invert();
        }
    }

    /**
     * C's {@code IsMapTab2D}: whether outputs {@code iax1} and {@code iax2} of {@code map} can be
     * described by look-up tables, for FITS-WCS axes {@code iwcs1} and {@code iwcs2}. Each must be
     * separable ({@link #isMapTab1D}); C then gives the Mapping from the two WCS axes to their grid
     * axes, split from the inverse of {@code map} where it can be and otherwise the two 1-D
     * Mappings in parallel. When only one is separable, C removes its columns from the FitsTable.
     * Non-separable outputs are not tabulated (C has no 2-D table yet).
     *
     * @param max1 receives 1 in its first element when both outputs are separable: each axis is
     *     the first of its coordinate array
     * @param max2 as {@code max1}, for the second output
     * @return C's Mapping, or null where C returns NULL
     * @throws UnsupportedOperationException where C would create a FitsTable, or remove the
     *     columns of a separable output from one, which needs FitsTable
     */
    static Mapping isMapTab2D(
            Mapping map,
            double scale,
            String unit,
            Frame wcsfrm,
            double[] dim,
            int iax1,
            int iax2,
            int iwcs1,
            int iwcs2,
            int[] icolmain1,
            int[] icolmain2,
            int[] icolindex1,
            int[] icolindex2,
            int[] max1,
            int[] max2,
            int[] interp1,
            int[] interp2) {
        Mapping ret = null;
        Mapping ret1 = isMapTab1D(map, scale, unit, wcsfrm, dim, iax1, iwcs1, icolmain1, icolindex1, interp1);
        Mapping ret2 = isMapTab1D(map, scale, unit, wcsfrm, dim, iax2, iwcs2, icolmain2, icolindex2, interp2);

        if (ret1 != null && ret2 != null) {
            max1[0] = 1;
            max2[0] = 1;

            // The Mapping from the two WCS axes to their grid axes, split from the inverse.
            map.invert();
            MapSplit split;
            try {
                split = map.mapSplit(new int[] {iax1, iax2});
            } finally {
                map.invert();
            }
            if (split != null) {
                ret = split.map;
                if (ret.getNout() > 2) {
                    ret = null;
                } else if (ret.getNout() == 1) {
                    // Both WCS axes fed by one grid axis: a PermMap duplicates it.
                    PermMap pm = PermMap.fromInternal(1, new int[] {0}, 2, new int[] {0, 0}, null);
                    ret = new CmpMap(ret, pm, true);
                }
            }
            if (ret == null) {
                ret = new CmpMap(ret1, ret2, false);
            }
        } else if (ret1 != null || ret2 != null) {
            throw new UnsupportedOperationException(
                    "IsMapTab2D(FitsChan): removing the columns of a separable axis from the look-up table needs"
                            + " FitsTable");
        }
        return ret;
    }

    /**
     * C's {@code AdaptLut}: appends to the samples {@code work[0]} (grid positions) and {@code
     * work[1]} (values), of which {@code nsamp[0]} are used, samples of the 1-D Mapping {@code map}
     * over the interval from {@code x0} (value {@code v0}) to {@code x1} (value {@code v1}),
     * excluding {@code x0}: {@code npos} evenly spaced interior samples and {@code x1}, each
     * sub-interval being sampled again in the same way wherever a sample departs from the straight
     * line between the ends by more than {@code eps} in input (or is bad).
     */
    static void adaptLut(
            Mapping map,
            int npos,
            double eps,
            double x0,
            double x1,
            double v0,
            double v1,
            double[][] work,
            int[] nsamp) {
        double dx = (x1 - x0) / (npos + 1);
        double xx0 = x0 + dx;
        double[] xx = new double[npos];
        for (int ipos = 0; ipos < npos; ipos++) {
            xx[ipos] = xx0 + ipos * dx;
        }
        double[] vv = map.tran1(npos, xx, true);

        boolean subdivide = false;
        if (v0 == v1) {
            for (int ipos = 0; ipos < npos; ipos++) {
                if (vv[ipos] != v0) {
                    subdivide = true;
                    break;
                }
            }
        } else {
            double rg = (x1 - x0) / (v1 - v0);
            for (int ipos = 0; ipos < npos; ipos++) {
                if (vv[ipos] == AST__BAD || Math.abs(rg * (vv[ipos] - v0) - (xx[ipos] - x0)) > eps) {
                    subdivide = true;
                    break;
                }
            }
        }

        if (subdivide) {
            for (int ipos = 0; ipos < npos; ipos++) {
                adaptLut(map, npos, eps, x0, xx[ipos], v0, vv[ipos], work, nsamp);
                x0 = xx[ipos];
                v0 = vv[ipos];
            }
            adaptLut(map, npos, eps, x0, x1, v0, v1, work, nsamp);
        } else {
            int isamp = nsamp[0];
            nsamp[0] += npos + 1;
            if (work[0].length < nsamp[0]) {
                work[0] = Arrays.copyOf(work[0], Math.max(nsamp[0], 2 * work[0].length));
                work[1] = Arrays.copyOf(work[1], work[0].length);
            }
            for (int ipos = 0; ipos < npos; ipos++, isamp++) {
                work[0][isamp] = xx[ipos];
                work[1][isamp] = vv[ipos];
            }
            work[0][isamp] = x1;
            work[1][isamp] = v1;
        }
    }

    /**
     * C's {@code ChangePermSplit}: sets PermSplit on every PermMap in {@code map}, looking inside
     * the Mappings C's {@code astDecompose} gives two components for.
     */
    static void changePermSplit(Mapping map) {
        if (map instanceof PermMap) {
            ((PermMap) map).setPermSplit(true);
        } else {
            Mapping[] parts = map.decomposeInto(new boolean[3]);
            if (parts.length == 2) {
                changePermSplit(parts[0]);
                changePermSplit(parts[1]);
            }
        }
    }
}
