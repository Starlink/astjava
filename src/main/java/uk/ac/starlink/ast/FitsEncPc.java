package uk.ac.starlink.ast;

import java.util.Locale;

/**
 * The cards of a FITS-PC header for the axis descriptions of a FrameSet: C's {@code PCFromStore},
 * which writes the descriptions {@link FitsFsetToStore#fsetToStore} made, the primary description
 * and then the alternates {@code A} to the last with a CRVAL, in letter order.
 *
 * <p>FITS-PC, the encoding of the 1996 draft of the FITS-WCS papers, holds at most ten descriptions:
 * the primary one and {@code A} to {@code I}. An alternate description is written as {@code
 * C<n>ELT<i>}, {@code C<n>PIX<i>}, {@code C<n>VAL<i>}, {@code C<n>YPE<i>} and {@code C<n>NIT<i>},
 * {@code <n>} being 1 for {@code A}, and must share the primary description's PC matrix (written as
 * {@code PCiiijjj}), RADECSYS, EQUINOX, LATPOLE, LONGPOLE, MJD-OBS and projection parameters (the
 * latitude axis's, written as {@code PROJPm}). A description that cannot be written leaves no
 * cards. Before the descriptions and after each, the current card is put after the last card
 * describing WCS ({@link FitsCardList#positionWriteCursor}, C's {@code FindWcs}).
 *
 * <p>Ported from fitschan.c {@code PCFromStore}, with the Rust port's {@code fitschan/enc_pc.rs}
 * ({@code write_pc}) as a guide; both include the fix of upstream bcbd87fa (an alternate
 * description's MJD-OBS is compared with the primary one's only when it has one).
 */
final class FitsEncPc {

    /** C's {@code AST__BAD}, for the values C tests against it. */
    private static final double BAD = AstObject.AST__BAD;

    private FitsEncPc() {}

    /** The values the alternate descriptions must share with the primary one. */
    private static final class Primary {
        /** The PC matrix, row by row, unit matrix elements included. */
        double[] pc;

        /** RADECSYS, or null if the primary description has none. */
        String sys;

        double eq = BAD;
        double lt = BAD;
        double ln = BAD;
        double dt = BAD;

        /** The first ten projection parameters of the latitude axis, {@link #BAD} if unset. */
        final double[] pv = {BAD, BAD, BAD, BAD, BAD, BAD, BAD, BAD, BAD, BAD};
    }

    /**
     * C's {@code PCFromStore}: writes the descriptions in {@code store} to {@code fc} as FITS-PC
     * cards, each in front of the current card. Nothing is written if a version after {@code I}
     * has a CRVAL. A description lacking a CDELT, CRPIX, CRVAL or CTYPE on one of its axes, whose
     * CTYPE C's FITS-PC cannot express ({@code xxLT-}, {@code xxLN-}, {@code -TAB} or TPN), or
     * whose projection parameters FITS-PC cannot hold, is not written: the cards it added are
     * removed. So is an alternate description that does not share the primary one's values. An
     * error while writing an alternate description is dropped, and the description not written,
     * as C clears it. Alternate descriptions are written only if the primary one is.
     *
     * @param fc the FitsChan written to
     * @param store the descriptions
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if any description was written, which is when the primary one was
     */
    static boolean pcFromStore(FitsChan fc, FitsPlaneStores store, String method, String cls) {
        FitsCardList cards = fc.cards();
        boolean ret = false;

        // FITS-PC can encode only 10 axis descriptions, the primary one included.
        char sup = store.maxCrvalVersion();
        if (sup > 'I') {
            return false;
        }

        // The primary description, then the alternates A to sup.
        Primary prim = new Primary();
        int maxm = maxPv(store.get(' '));
        char s = ' ';
        while (s <= sup) {
            FitsStore st = store.get(s);
            if (st == null) {
                // A version with nothing stored, which C reads as unset values, as it does in
                // the store of every version.
                st = new FitsStore(0);
            }
            boolean ok;
            if (s == ' ') {
                ok = writeVersion(fc, st, s, store.naxis, maxm, prim);
            } else {
                // C clears the error status after an alternate description, which is then not
                // written, so that the next can be.
                try {
                    ok = writeVersion(fc, st, s, store.naxis, maxm, prim);
                } catch (AstException e) {
                    ok = false;
                }
            }
            if (ok) {
                ret = true;
            }

            // The cards of a description not written are removed.
            cards.fixNew(FitsCardList.NewScope.VERSION, !ok);
            cards.positionWriteCursor();

            if (s == ' ') {
                // Alternate descriptions cannot stand on their own.
                if (!ok) {
                    break;
                }
                s = 'A';
            } else {
                s++;
            }
        }
        return ret;
    }

    /** C's {@code GetMaxJM( &(store->pv), ' ' )}: the highest PV index of the primary description, or -1. */
    private static int maxPv(FitsStore primary) {
        return primary == null ? -1 : primary.maxPvM();
    }

    /**
     * The cards of one description, {@code s}, from the body of C's version loop: false, C's
     * {@code ok = 0; goto next}, if the description cannot be written. The primary description
     * records in {@code prim} the values the alternates must share.
     *
     * @param storeNaxis C's {@code store->naxis}, the number of pixel axes
     * @param maxm the highest projection parameter index of the primary description, or -1
     */
    private static boolean writeVersion(FitsChan fc, FitsStore st, char s, int storeNaxis, int maxm, Primary prim) {
        int rnd = fc.getFitsRounding();
        FitsCardList cards = fc.cards();
        boolean primary = s == ' ';
        int is = primary ? 0 : s - 'A' + 1;

        // The number of WCS axes, written as WCSAXES if the store has one.
        int naxis;
        if (st.wcsaxes != null) {
            naxis = st.wcsaxes;
            setInt(cards, primary ? "WCSAXES" : "WCSAXES" + s, naxis, "Number of WCS axes", rnd);
        } else {
            naxis = st.maxCrpixAxis() + 1;
        }

        // The PC matrix, which all descriptions share: the primary description's is written,
        // leaving out the elements equal to the unit matrix's, and each alternate must have the
        // same.
        double[] matrix = new double[naxis * naxis];
        for (int i = 0; i < naxis; i++) {
            for (int j = 0; j < naxis; j++) {
                Double v = at(st.pc, i, j);
                matrix[i * naxis + j] = v != null ? v : (i == j ? 1.0 : 0.0);
            }
        }
        if (primary) {
            prim.pc = matrix;
            for (int i = 0; i < naxis; i++) {
                for (int j = 0; j < naxis; j++) {
                    double val = matrix[i * naxis + j];
                    if (!MappingHelpers.astEqual(val, i == j ? 1.0 : 0.0)) {
                        setFloat(cards, String.format(Locale.ROOT, "PC%03d%03d", i + 1, j + 1), val, null, rnd);
                    }
                }
            }
        } else if (prim.pc != null) {
            // C compares the two matrices element by element in storage order, so matrices of
            // different sizes are compared as flat arrays; C reads past the primary matrix for an
            // alternate with more axes, where an element beyond it is taken as different here.
            for (int k = 0; k < matrix.length; k++) {
                if (k >= prim.pc.length || !MappingHelpers.astEqual(matrix[k], prim.pc[k])) {
                    return false;
                }
            }
        }

        for (int i = 0; i < naxis; i++) {
            Double val = at(st.cdelt, i);
            if (val == null) {
                return false;
            }
            setFloat(cards, key("CDELT", "ELT", is, i + 1), val, "Pixel scale on axis " + (i + 1), rnd);
        }
        for (int j = 0; j < naxis; j++) {
            Double val = at(st.crpix, j);
            if (val == null) {
                return false;
            }
            setFloat(cards, key("CRPIX", "PIX", is, j + 1), val, "Reference pixel on axis " + (j + 1), rnd);
        }
        for (int i = 0; i < naxis; i++) {
            Double val = at(st.crval, i);
            if (val == null) {
                return false;
            }
            setFloat(cards, key("CRVAL", "VAL", is, i + 1), val, "Value at ref. pixel on axis " + (i + 1), rnd);
        }

        // CTYPE: FITS-PC has no -TAB axes, no celestial axes of type xxLT or xxLN, and no TPN
        // projection (an AST code); SFL is written with the older code GLS.
        for (int i = 0; i < naxis; i++) {
            String cval = at(st.ctype, i);
            if (cval == null) {
                return false;
            }
            int nc = cval.length();
            if (nc > 4 && "-TAB".equals(cval.substring(4))) {
                return false;
            }
            String comm = at(st.ctypeCom, i);
            if (comm == null) {
                comm = "Type of co-ordinate on axis " + (i + 1); // codespell:ignore
            }
            if ((nc > 2 && cval.startsWith("LT-", 2))
                    || (nc > 2 && cval.startsWith("LN-", 2))
                    || (nc > 4 && cval.startsWith("-TAB", 4))) {
                return false;
            }
            int prj = nc > 4 ? FitsWcsCelestial.wcsPrjType(cval.substring(4)) : WcsMap.AST__WCSBAD;
            if (prj == WcsMap.AST__SFL) {
                cval = cval.substring(0, 4) + "-GLS";
            }
            if (prj == WcsMap.AST__TPN) {
                return false;
            }
            setString(cards, key("CTYPE", "YPE", is, i + 1), cval, comm, rnd);
        }

        for (int i = 0; i < naxis; i++) {
            String cval = at(st.cunit, i);
            if (cval != null) {
                setString(cards, key("CUNIT", "NIT", is, i + 1), cval, "Units for axis " + (i + 1), rnd);
            }
        }

        // RADECSYS, EQUINOX, LATPOLE and LONGPOLE: written for the primary description, and the
        // same for each alternate one. C compares an alternate RADECSYS with the primary one's
        // even when the primary description has none, reading an unset string; here they then
        // differ.
        if (st.radesys != null) {
            if (primary) {
                prim.sys = st.radesys;
                setString(cards, "RADECSYS", st.radesys, "Reference frame for RA/DEC values", rnd);
            } else if (!st.radesys.equals(prim.sys)) {
                return false;
            }
        }
        double val = value(st.equinox);
        if (primary) {
            prim.eq = val;
            if (val != BAD) {
                setFloat(cards, "EQUINOX", val, "Epoch of reference equinox", rnd);
            }
        } else if (!MappingHelpers.astEqual(val, prim.eq)) {
            return false;
        }
        val = value(st.latpole);
        if (primary) {
            prim.lt = val;
            if (val != BAD) {
                setFloat(cards, "LATPOLE", val, "Latitude of native north pole", rnd);
            }
        } else if (!FitsStore.equalAng(val, prim.lt)) {
            return false;
        }
        val = value(st.lonpole);
        if (primary) {
            prim.ln = val;
            if (val != BAD) {
                setFloat(cards, "LONGPOLE", val, "Longitude of native north pole", rnd);
            }
        } else if (!FitsStore.equalAng(val, prim.ln)) {
            return false;
        }

        // The date of observation: an alternate description with one must have the primary one's.
        val = value(st.mjdobs);
        if (primary) {
            prim.dt = val;
            if (val != BAD) {
                setFloat(cards, "MJD-OBS", val, "Modified Julian Date of observation", rnd);
                setString(cards, "DATE-OBS", FitsDates.mjdToDateStr(val), "Date of observation", rnd);
            }
        } else if (val != BAD && !MappingHelpers.astEqual(val, prim.dt)) {
            return false;
        }

        // Projection parameters: only those of the latitude axis, and only the first ten, with
        // none for TAN, which in the 1996 draft had none. An alternate description must have the
        // primary one's.
        int[] axes = st.lonLatSpecAxes(storeNaxis);
        int axlon = axes[0];
        int axlat = axes[1];
        if (axlon >= 0 && axlat >= 0) {
            String cval = at(st.ctype, axlat);
            int prj = cval != null ? FitsWcsCelestial.wcsPrjType(cval.substring(4)) : WcsMap.AST__WCSBAD;
            for (int i = 0; i < naxis; i++) {
                if (i == axlon) {
                    continue;
                }
                for (int m = 0; m <= maxm; m++) {
                    double pv = value(at(st.pv, i, m));
                    if (primary) {
                        if (pv != BAD) {
                            if (i != axlat || prj == WcsMap.AST__TAN || m >= 10) {
                                return false;
                            }
                            setFloat(cards, "PROJP" + m, pv, "Projection parameter", rnd);
                        }
                        if (i == axlat && m < 10) {
                            prim.pv[m] = pv;
                        }
                    } else if (((i != axlat || m >= 10) && pv != BAD)
                            || (i == axlat && m < 10 && !MappingHelpers.astEqual(pv, prim.pv[m]))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The keyword of axis {@code i} of description {@code is} (0 for the primary one): {@code
     * <primary><i>}, or {@code C<is><alt><i>} for an alternate one.
     */
    private static String key(String primary, String alt, int is, int i) {
        return is == 0 ? primary + i : "C" + is + alt + i;
    }

    /** {@code v}, or {@link #BAD} (C's unset item) if it is null. */
    private static double value(Double v) {
        return v != null ? v : BAD;
    }

    /** Element {@code i} of {@code a}, null (C's unset item) beyond its end. */
    private static <T> T at(T[] a, int i) {
        return i < a.length ? a[i] : null;
    }

    /** Element {@code [i][j]} of {@code a}, null beyond its ends. */
    private static <T> T at(T[][] a, int i, int j) {
        return i < a.length && j < a[i].length ? a[i][j] : null;
    }

    private static void setFloat(FitsCardList cards, String keyword, double value, String comment, int rnd) {
        cards.setValueAsWriter(keyword, FitsCard.Value.ofFloat(value), comment, rnd);
    }

    private static void setString(FitsCardList cards, String keyword, String value, String comment, int rnd) {
        cards.setValueAsWriter(keyword, FitsCard.Value.ofString(value), comment, rnd);
    }

    private static void setInt(FitsCardList cards, String keyword, int value, String comment, int rnd) {
        cards.setValueAsWriter(keyword, FitsCard.Value.ofInt(value), comment, rnd);
    }
}
