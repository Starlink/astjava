package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * C's {@code SIPIntWorld} (fitschan.c): the CRPIX and CD values of celestial axes whose Mapping
 * from grid to intermediate world coordinates includes a polynomial distortion the SIP convention
 * can describe, and the SIP coefficients describing it.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/sip_int_world.rs}, following fitschan.c.
 */
final class FitsSipIntWorld {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** The largest departure from a unit scale, zero rotation or zero shift C accepts. */
    private static final double LINEAR_TOL = 1.0E-7;

    private FitsSipIntWorld() {}

    /**
     * Finds CRPIX and CD values for the celestial axes of {@code map}, and stores in {@code store}
     * the SIP coefficients of the distortion it holds, as C's {@code SIPIntWorld} does.
     *
     * <p>The Mapping from the grid axes to the two celestial outputs is split off, simplified and
     * searched for a PolyMap. The Mappings before the PolyMap must be linear over the image with
     * no rotation; any scaling is moved into the PolyMap. The PolyMap is then analysed into a
     * shift, a SIP polynomial and a linear transformation where it can be, and the Mappings after
     * the polynomial must be linear over the region they are used in, with no shift of origin.
     * Their linear fit gives the CD values, and the grid position of the origin of intermediate
     * world coordinates gives CRPIX. The coefficients of each transformation of the polynomial are
     * stored, less one for the unit linear terms, as the A and B (forward) and AP and BP (inverse)
     * coefficients, and {@code -SIP} is written into the CTYPE values of the celestial axes from
     * their ninth character.
     *
     * <p>{@code map} is inverted while it is split, and inverted back.
     *
     * @param map the Mapping from grid to intermediate world coordinates
     * @param tol the tolerance, in pixels, of the linearity tests
     * @param lonax the zero-based output of {@code map} giving celestial longitude
     * @param latax the zero-based output of {@code map} giving celestial latitude
     * @param s the axis description character: a space for the primary description, else {@code
     *     'A'} to {@code 'Z'}
     * @param store the store which receives the SIP coefficients and CTYPE values
     * @param dim the image dimensions in pixels, {@code AST__BAD} where unknown, which then counts
     *     as 1000
     * @param inaxes receives the zero-based inputs of {@code map} which {@code crpix} and {@code
     *     cd} refer to, once the celestial Mapping is split off
     * @param crpix receives the CRPIX values of the inputs {@code inaxes} once the SIP coefficients
     *     are stored
     * @param cd receives the CD values (CDlonax_j1, CDlonax_j2, CDlatax_j1, CDlatax_j2), where
     *     {@code j1} and {@code j2} are the inputs {@code inaxes}, once the SIP coefficients are
     *     stored
     * @param method the calling method, for messages
     * @param cls the class of the object being written, for messages
     * @return a Mapping equivalent to {@code map} without the polynomial distortion, or null if
     *     the celestial axes cannot be described with SIP coefficients, when nothing is stored
     */
    static Mapping sipIntWorld(
            Mapping map,
            double tol,
            int lonax,
            int latax,
            char s,
            FitsStore store,
            double[] dim,
            int[] inaxes,
            double[] crpix,
            double[] cd,
            String method,
            String cls) {
        int nin = map.getNin();
        int nout = map.getNout();
        if (!map.getTranForward() || !map.getTranInverse()) {
            return null;
        }

        // The Mapping from grid coordinates to the celestial outputs, split off the inverse of
        // map, since a split names the inputs it keeps.
        MapSplit split1 = splitOutputs(map, new int[] {lonax, latax});
        if (split1 == null || split1.map.getNout() != 2) {
            return null;
        }
        int[] inax1 = split1.outputs;
        Mapping tmap1 = split1.map;
        tmap1.invert();
        inaxes[0] = inax1[0];
        inaxes[1] = inax1[1];

        // The first PolyMap of the simplified Mapping as a list in series.
        Mapping smap = tmap1.simplify();
        List<Mapping> mapList = new ArrayList<>();
        List<Boolean> invertList = new ArrayList<>();
        smap.mapList(true, smap.getInvert(), mapList, invertList);
        int nmap = mapList.size();
        int imapPm = -1;
        PolyMap polymap = null;
        for (int imap = 0; imap < nmap; imap++) {
            if (mapList.get(imap) instanceof PolyMap) {
                imapPm = imap;
                polymap = (PolyMap) mapList.get(imap).copy();
                polymap.setInvert(invertList.get(imap));
                break;
            }
        }
        if (polymap == null || polymap.getNin() != 2 || polymap.getNout() != 2) {
            return null;
        }

        // The Mappings before and after the PolyMap, each joined into one, or a UnitMap if there
        // are none.
        Mapping mapLower = accumulate(mapList, invertList, 0, imapPm);
        Mapping mapUpper = accumulate(mapList, invertList, imapPm + 1, nmap);
        if (mapLower == null) {
            mapLower = new UnitMap(2);
        }
        if (mapUpper == null) {
            mapUpper = new UnitMap(2);
        }
        boolean ok =
                mapLower.getNin() == 2 && mapLower.getNout() == 2 && mapUpper.getNin() == 2 && mapUpper.getNout() == 2;

        // The lower Mapping must be linear over the image, and may scale but not rotate.
        double[] lbnd = new double[2];
        double[] ubnd = new double[2];
        double[] fit = null;
        boolean scaling = false;
        boolean rotation = false;
        if (ok) {
            lbnd[0] = 0.0;
            lbnd[1] = 0.0;
            ubnd[0] = dim[0];
            ubnd[1] = dim[1];
            if (ubnd[0] == AST__BAD) {
                ubnd[0] = 1000.0;
            }
            if (ubnd[1] == AST__BAD) {
                ubnd[1] = 1000.0;
            }
            fit = mapLower.linearApprox(lbnd, ubnd, tol);
            ok = fit != null;
            if (ok) {
                if (Math.abs(fit[2] - 1.0) > LINEAR_TOL || Math.abs(fit[5] - 1.0) > LINEAR_TOL) {
                    scaling = true;
                }
                if (Math.abs(fit[3]) > LINEAR_TOL || Math.abs(fit[4]) > LINEAR_TOL) {
                    rotation = true;
                }
            }
        }
        if (rotation) {
            ok = false;
        }

        // A scaling is moved into the PolyMap, leaving the shift of origin, which the fit applies
        // after the scaling, as a shift applied before it.
        if (scaling && ok) {
            double[] shift = {fit[0] / fit[2], fit[1] / fit[5]};
            mapLower = new ShiftMap(shift);
            polymap = ScalePolyInputs.scalePolyInputs(polymap, new double[] {fit[2], fit[5]});
        }

        // The SIP polynomial has no constant or linear terms (beyond a unit copy of each input),
        // so any shift in the PolyMap goes into the lower Mapping and any scaling or rotation into
        // the upper.
        Mapping polyStage = polymap;
        if (ok) {
            Mapping[] parts = new Mapping[3];
            if (AnalysePoly.analysePoly(polymap, parts)) {
                mapLower = new CmpMap(mapLower, parts[0], true);
                mapUpper = new CmpMap(parts[2], mapUpper, true);
                polyStage = parts[1];
            }
        }

        // The upper Mapping must be linear, with no shift of origin, over the region it is used
        // in: the bounding box of a 3x3 grid of positions spanning the image, taken through the
        // lower Mapping and the polynomial. Two positions a pixel from the image centre along each
        // grid axis express the tolerance, in pixels, in the output space of the upper Mapping.
        double utol = 0.0;
        if (ok) {
            double[] upx = new double[11];
            double[] upy = new double[11];
            int isamp = 0;
            for (int ix = 0; ix < 3; ix++) {
                for (int iy = 0; iy < 3; iy++) {
                    upx[isamp] = 0.5 * ix * ubnd[0];
                    upy[isamp] = 0.5 * iy * ubnd[1];
                    isamp++;
                }
            }
            upx[9] = 0.5 * ubnd[0] + 1.0;
            upy[9] = 0.5 * ubnd[1];
            upx[10] = 0.5 * ubnd[0];
            upy[10] = 0.5 * ubnd[1] + 1.0;

            double[][] uw = mapLower.tran2(11, upx, upy, true);
            double[][] up = polyStage.tran2(11, uw[0], uw[1], true);
            upx = up[0];
            upy = up[1];

            for (isamp = 0; isamp < 9 && ok; isamp++) {
                if (upx[isamp] == AST__BAD || upy[isamp] == AST__BAD) {
                    ok = false;
                } else if (isamp == 0) {
                    lbnd[0] = upx[0];
                    ubnd[0] = upx[0];
                    lbnd[1] = upy[0];
                    ubnd[1] = upy[0];
                } else {
                    if (upx[isamp] < lbnd[0]) {
                        lbnd[0] = upx[isamp];
                    }
                    if (upx[isamp] > ubnd[0]) {
                        ubnd[0] = upx[isamp];
                    }
                    if (upy[isamp] < lbnd[1]) {
                        lbnd[1] = upy[isamp];
                    }
                    if (upy[isamp] > ubnd[1]) {
                        ubnd[1] = upy[isamp];
                    }
                }
            }

            if (ok) {
                double[][] st = mapUpper.tran2(
                        3, new double[] {upx[4], upx[9], upx[10]}, new double[] {upy[4], upy[9], upy[10]}, true);
                double[] sx = st[0];
                double[] sy = st[1];
                double uscale;
                if (sx[0] != AST__BAD
                        && sy[0] != AST__BAD
                        && sx[1] != AST__BAD
                        && sy[1] != AST__BAD
                        && sx[2] != AST__BAD
                        && sy[2] != AST__BAD) {
                    double d1 = Math.sqrt((sx[1] - sx[0]) * (sx[1] - sx[0]) + (sy[1] - sy[0]) * (sy[1] - sy[0]));
                    double d2 = Math.sqrt((sx[2] - sx[0]) * (sx[2] - sx[0]) + (sy[2] - sy[0]) * (sy[2] - sy[0]));
                    // C's astMAX.
                    uscale = d1 > d2 ? d1 : d2;
                } else {
                    uscale = 0.0;
                }
                if (uscale > 0.0) {
                    utol = tol * uscale;
                } else {
                    ok = false;
                }
            }

            if (ok) {
                fit = mapUpper.linearApprox(lbnd, ubnd, utol);
                ok = fit != null && Math.abs(fit[0]) <= LINEAR_TOL && Math.abs(fit[1]) <= LINEAR_TOL;
            }
        }

        // The Mapping giving any other outputs, split off the inverse of map in the same way.
        int[] outrem = null;
        int noutrem = 0;
        int[] inax2 = null;
        Mapping tmap2 = null;
        if (nout > 2 && ok) {
            noutrem = nout - 2;
            outrem = new int[noutrem];
            int ioutrem = 0;
            for (int iout = 0; iout < nout; iout++) {
                if (iout != lonax && iout != latax) {
                    outrem[ioutrem++] = iout;
                }
            }
            MapSplit split2 = splitOutputs(map, outrem);
            if (split2 != null) {
                inax2 = split2.outputs;
                tmap2 = split2.map;
                tmap2.invert();
            } else {
                ok = false;
            }
        } else if (nout != 2 || nin != 2) {
            ok = false;
        }

        // CRPIX: the grid position of the origin of intermediate world coordinates. The inverse
        // may not be defined there, when SIP cannot describe the celestial axes.
        if (ok) {
            double[][] ref = smap.tran2(1, new double[] {0.0}, new double[] {0.0}, false);
            crpix[0] = ref[0][0];
            crpix[1] = ref[1][0];
            if (crpix[0] == AST__BAD || crpix[1] == AST__BAD) {
                ok = false;
            }
        }
        if (!ok) {
            return null;
        }

        // The linear fit to the upper Mapping gives the CD matrix.
        cd[0] = fit[2];
        cd[1] = fit[3];
        cd[2] = fit[4];
        cd[3] = fit[5];

        storeCoefficients(polyStage, inaxes, store);

        store.ctype[latax] = sipCtype(store.ctype[latax]);
        store.ctype[lonax] = sipCtype(store.ctype[lonax]);

        // The Mapping without the polynomial: the lower and upper Mappings in series, in parallel
        // with the Mapping of any other axes, between PermMaps restoring the order of the inputs
        // and outputs that the splits changed.
        Mapping result = new CmpMap(mapLower, mapUpper, true);
        if (tmap2 != null) {
            result = new CmpMap(result, tmap2, false);
        }

        int[] inperm1 = new int[nout];
        int[] outperm1 = new int[nout];
        inperm1[0] = lonax;
        inperm1[1] = latax;
        outperm1[lonax] = 0;
        outperm1[latax] = 1;
        if (tmap2 != null) {
            System.arraycopy(outrem, 0, inperm1, 2, noutrem);
            for (int iout = 0; iout < noutrem; iout++) {
                outperm1[outrem[iout]] = iout + 2;
            }
        }
        result = new CmpMap(result, PermMap.fromInternal(nout, inperm1, nout, outperm1, null), true);

        int[] inperm2 = new int[nin];
        int[] outperm2 = new int[nin];
        outperm2[0] = inax1[0];
        outperm2[1] = inax1[1];
        inperm2[inax1[0]] = 0;
        inperm2[inax1[1]] = 1;
        if (tmap2 != null) {
            if (inax2.length < nin - 2) {
                // C reads past the end of its array of inputs here.
                throw Ast.err(
                        AstException.AST__INTER,
                        method + "(" + cls + "): the axes other than the celestial axes are fed by " + inax2.length
                                + " grid axes, not " + (nin - 2) + " (internal AST programming error).");
            }
            System.arraycopy(inax2, 0, outperm2, 2, nin - 2);
            for (int iin = 0; iin < nin - 2; iin++) {
                inperm2[inax2[iin]] = iin + 2;
            }
        }
        return new CmpMap(PermMap.fromInternal(nin, inperm2, nin, outperm2, null), result, true);
    }

    /**
     * C's {@code astInvert}, {@code astMapSplit} and {@code astInvert} on {@code map}: the split
     * of the inverse of {@code map} that keeps the zero-based outputs {@code outax}, whose own
     * outputs are the inputs of {@code map} feeding them; null if there is none.
     */
    private static MapSplit splitOutputs(Mapping map, int[] outax) {
        map.invert();
        try {
            return map.mapSplit(outax);
        } finally {
            map.invert();
        }
    }

    /**
     * The Mappings of the list from {@code from} to before {@code to}, each applied with the
     * Invert value the list gives it, joined in series: a copy of the first, which C copies, and
     * each later one added in a CmpMap, which C makes from shared Mappings; null if there are
     * none.
     */
    private static Mapping accumulate(List<Mapping> mapList, List<Boolean> invertList, int from, int to) {
        Mapping acc = null;
        for (int imap = from; imap < to; imap++) {
            Mapping m = mapList.get(imap);
            int saved = m.swapInvert(invertList.get(imap));
            try {
                if (acc != null) {
                    acc = new CmpMap(acc, m, true);
                } else {
                    acc = (Mapping) m.copy();
                }
            } finally {
                m.restoreInvert(saved);
            }
        }
        return acc;
    }

    /**
     * Stores the SIP coefficients of the defined transformations of {@code polymap}, forward (A
     * and B) then inverse (AP and BP), as C does. A coefficient for celestial output 1 is an A (or
     * AP) coefficient if the first of {@code inaxes} is the lower, and a B (or BP) coefficient
     * otherwise, the powers {@code i} and {@code j} being those of the first and second input in
     * the first case and exchanged in the second. Every power up to the largest used is first
     * stored as {@code AST__BAD}; the unit linear terms are reduced by one, and the coefficients
     * that are then neither zero nor bad stored over them.
     */
    private static void storeCoefficients(Mapping polyStage, int[] inaxes, FitsStore store) {
        for (int fwd = 1; fwd >= 0; fwd--) {
            boolean forward = fwd == 1;
            if (!(forward ? polyStage.getTranForward() : polyStage.getTranInverse())) {
                continue;
            }
            double[] coeffs = polyCoeffs(polyStage, forward);
            int ncoeff = coeffs.length / 4;

            // The largest power used on each input.
            int aimax = 0;
            int ajmmax = 0;
            int bimax = 0;
            int bjmmax = 0;
            for (int icoeff = 0; icoeff < ncoeff; icoeff++) {
                int pc = 4 * icoeff;
                if (inaxes[0] < inaxes[1]) {
                    int i = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 2]);
                    int jm = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 3]);
                    if (coeffs[pc + 1] == 1) {
                        aimax = Math.max(aimax, i);
                        ajmmax = Math.max(ajmmax, jm);
                    } else {
                        bimax = Math.max(bimax, i);
                        bjmmax = Math.max(bjmmax, jm);
                    }
                } else {
                    int i = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 3]);
                    int jm = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 2]);
                    if (coeffs[pc + 1] == 1) {
                        bimax = Math.max(bimax, i);
                        bjmmax = Math.max(bjmmax, jm);
                    } else {
                        aimax = Math.max(aimax, i);
                        ajmmax = Math.max(ajmmax, jm);
                    }
                }
            }

            double[][] a = forward ? store.sipA : store.sipAp;
            double[][] b = forward ? store.sipB : store.sipBp;
            for (int i = 0; i <= aimax; i++) {
                for (int jm = 0; jm <= ajmmax; jm++) {
                    a = FitsStore.setItem(a, i, jm, AST__BAD);
                }
            }
            for (int i = 0; i <= bimax; i++) {
                for (int jm = 0; jm <= bjmmax; jm++) {
                    b = FitsStore.setItem(b, i, jm, AST__BAD);
                }
            }

            for (int icoeff = 0; icoeff < ncoeff; icoeff++) {
                int pc = 4 * icoeff;
                boolean toA;
                int i;
                int jm;
                if (inaxes[0] < inaxes[1]) {
                    toA = coeffs[pc + 1] == 1;
                    i = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 2]);
                    jm = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 3]);
                } else {
                    toA = coeffs[pc + 1] != 1;
                    i = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 3]);
                    jm = (int) FitsMakeIntWorld.roundHalfAway(coeffs[pc + 2]);
                }
                double val = coeffs[pc];
                if ((coeffs[pc + 1] == 1 && i == 1 && jm == 0) || (coeffs[pc + 1] == 2 && i == 0 && jm == 1)) {
                    val -= 1.0;
                }
                if (val != 0.0 && val != AST__BAD) {
                    if (toA) {
                        a = FitsStore.setItem(a, i, jm, val);
                    } else {
                        b = FitsStore.setItem(b, i, jm, val);
                    }
                }
            }

            if (forward) {
                store.sipA = a;
                store.sipB = b;
            } else {
                store.sipAp = a;
                store.sipBp = b;
            }
        }
    }

    /**
     * C's {@code astPolyCoeffs} on the middle Mapping, which C takes to be a PolyMap.
     *
     * @throws AstException with status {@code AST__OBJIN} if it is not a PolyMap, as C's {@code
     *     astCheckPolyMap} reports
     */
    private static double[] polyCoeffs(Mapping polyStage, boolean forward) {
        if (polyStage instanceof PolyMap) {
            return ((PolyMap) polyStage).polyCoeffs(forward);
        }
        throw Ast.err(
                AstException.AST__OBJIN,
                "Pointer to PolyMap required, but pointer to " + polyStage.astClass() + " given.");
    }

    /**
     * The CTYPE value C stores for a SIP axis: it copies the value into a buffer and writes
     * {@code -SIP} from the ninth character. A value of fewer than eight characters keeps the
     * null that ends it, so the buffer still holds the value; a longer one is cut to eight
     * characters. An unset value stays unset.
     */
    private static String sipCtype(String cval) {
        if (cval == null || cval.length() < 8) {
            return cval;
        }
        return cval.substring(0, 8) + "-SIP";
    }
}
