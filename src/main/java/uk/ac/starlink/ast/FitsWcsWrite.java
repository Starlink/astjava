package uk.ac.starlink.ast;

/**
 * The cards of a FITS-WCS header for the axis descriptions of a FrameSet: C's {@code
 * WcsFromStore}, which writes the descriptions {@link FitsFsetToStore#fsetToStore} made, the
 * primary description and then the alternates {@code A} to {@code Z} in letter order.
 *
 * <p>Each description's cards are written in C's order: WCSAXES, WCSNAME, CRPIX, CRVAL, CTYPE,
 * CNAME, the CD matrix or CDELT and the PC matrix, CUNIT, the AST extensions AXREF, SREFIS, SREF
 * and SREFP, the date of observation (primary description only), the projection parameters, the
 * celestial and spectral keywords, OBSGEO (primary only), and the SIP coefficients with their
 * orders. Before each description and after it the current card is put after the last card
 * describing WCS ({@link FitsCardList#positionWriteCursor}, C's {@code FindWcs}); the SIP
 * coefficients are not such cards, so the next description's cards go in front of them.
 *
 * <p>Ported from fitschan.c {@code WcsFromStore}, the Rust port's {@code wcs_write/mod.rs} ({@code
 * write_store_to_cards}) being organised differently.
 */
final class FitsWcsWrite {

    private FitsWcsWrite() {}

    /**
     * C's {@code WcsFromStore}: writes the descriptions in {@code store} to {@code fc} as FITS-WCS
     * cards, each in front of the current card, which is first put after the last card describing
     * WCS. A description without CRPIX1 and CRVAL1, or lacking a CRPIX, CRVAL, CTYPE or (without
     * CDMatrix) CDELT on one of its axes, is not written: the cards it added are removed. An error
     * while writing an alternate description is dropped, and the description not written, as C
     * clears it. Alternate descriptions are written only if the primary one is.
     *
     * @param fc the FitsChan written to
     * @param store the descriptions
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if any description was written, which is when the primary one was
     */
    static boolean wcsFromStore(FitsChan fc, FitsPlaneStores store, String method, String cls) {
        FitsCardList cards = fc.cards();
        boolean ret = false;

        // The NAXIS value, or -1 if there is none.
        Integer naxisCard = fc.getFitsI("NAXIS");
        int naxis = naxisCard != null ? naxisCard : -1;

        cards.positionWriteCursor();

        // The primary description, then the alternates A to the last with a CRVAL.
        char sup = store.maxCrvalVersion();
        char s = ' ';
        while (s <= sup) {
            boolean ok;
            if (s == ' ') {
                ok = writeVersion(fc, store.get(s), s, naxis);
            } else {
                // C clears the error status after an alternate description, which is then not
                // written, so that the next can be.
                try {
                    ok = writeVersion(fc, store.get(s), s, naxis);
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

    /**
     * The cards of one description, {@code s}, from the body of C's version loop: false, C's
     * {@code ok = 0; goto next}, if the description is incomplete.
     */
    private static boolean writeVersion(FitsChan fc, FitsStore st, char s, int naxis) {
        // A description without CRPIX1 or CRVAL1 is not written.
        if (st == null || at(st.crpix, 0) == null || at(st.crval, 0) == null) {
            return false;
        }
        int rnd = fc.getFitsRounding();
        FitsCardList cards = fc.cards();

        // WCSAXES, if the store has one or the number of axes differs from NAXIS.
        int nwcs;
        boolean writeWcsaxes;
        if (st.wcsaxes != null) {
            nwcs = st.wcsaxes;
            writeWcsaxes = true;
        } else {
            nwcs = st.maxCrpixAxis() + 1;
            writeWcsaxes = nwcs != 0 && nwcs != naxis;
        }
        if (writeWcsaxes) {
            setInt(cards, key("WCSAXES", s), nwcs, "Number of WCS axes", rnd);
        }

        // WCSNAME, not needed; for one axis, left out when C's Similar finds it like CTYPE1.
        String cval = st.wcsname;
        if (cval != null && nwcs == 1) {
            String ctype = at(st.ctype, 0);
            if (ctype != null && FitsCardList.similar(ctype, cval)) {
                cval = null;
            }
        }
        if (cval != null) {
            setString(cards, key("WCSNAME", s), cval, "Reference name for the coord. frame", rnd);
        }

        // The prefix of the numerical projection parameters, QV once an axis uses TPN.
        String parprefix = "PV";

        for (int i = 0; i < nwcs; i++) {
            Double val = at(st.crpix, i);
            if (val == null) {
                return false;
            }
            setFloat(cards, axisKey("CRPIX", i + 1, s), val, "Reference pixel on axis " + (i + 1), rnd);
        }
        for (int i = 0; i < nwcs; i++) {
            Double val = at(st.crval, i);
            if (val == null) {
                return false;
            }
            setFloat(cards, axisKey("CRVAL", i + 1, s), val, "Value at ref. pixel on axis " + (i + 1), rnd);
        }

        // CTYPE: a TPN projection (an AST code) is written as TAN with its parameters as QV.
        boolean[] tabaxis = new boolean[nwcs];
        for (int i = 0; i < nwcs; i++) {
            String ctype = at(st.ctype, i);
            if (ctype == null) {
                return false;
            }
            String comm = at(st.ctypeCom, i);
            if (comm == null) {
                comm = "Type of co-ordinate on axis " + (i + 1); // codespell:ignore
            }
            int nc = ctype.length();
            int prj = nc > 4 ? FitsWcsCelestial.wcsPrjType(ctype.substring(nc - 4)) : WcsMap.AST__WCSBAD;
            if (prj == WcsMap.AST__TPN) {
                parprefix = "QV";
                ctype = ctype.substring(0, nc - 4) + "-TAN";
            }
            tabaxis[i] = prj == WcsMap.AST__WCSBAD && ctype.length() >= 8 && ctype.startsWith("-TAB", 4);
            setString(cards, axisKey("CTYPE", i + 1, s), ctype, comm, rnd);
        }

        // CNAME, unless it is the CTYPE value or its comment.
        for (int i = 0; i < nwcs; i++) {
            String cname = at(st.cname, i);
            if (cname != null) {
                String comm = at(st.ctype, i);
                if (comm == null || !comm.equals(cname)) {
                    comm = at(st.ctypeCom, i);
                    if (comm == null || !comm.equals(cname)) {
                        setString(cards, axisKey("CNAME", i + 1, s), cname, "Description of axis " + (i + 1), rnd);
                    }
                }
            }
        }

        if (fc.getCDMatrix()) {
            // The CD matrix: each row of the PC matrix times its CDELT; zero elements left out.
            for (int i = 0; i < nwcs; i++) {
                Double c = at(st.cdelt, i);
                double cdl = c != null ? c : 1.0;
                for (int j = 0; j < nwcs; j++) {
                    Double p = at(st.pc, i, j);
                    double val = p != null ? p : (i == j ? 1.0 : 0.0);
                    val *= cdl;
                    if (val != 0.0) {
                        setFloat(cards, matrixKey("CD", i + 1, j + 1, s), val, "Transformation matrix element", rnd);
                    }
                }
            }
        } else {
            for (int i = 0; i < nwcs; i++) {
                Double val = at(st.cdelt, i);
                if (val == null) {
                    return false;
                }
                setFloat(cards, axisKey("CDELT", i + 1, s), val, "Pixel size on axis " + (i + 1), rnd);
            }
            // The PC matrix, leaving out elements equal to the unit matrix's.
            for (int i = 0; i < nwcs; i++) {
                for (int j = 0; j < nwcs; j++) {
                    Double val = at(st.pc, i, j);
                    if (val != null && !MappingHelpers.astEqual(val, i == j ? 1.0 : 0.0)) {
                        setFloat(cards, matrixKey("PC", i + 1, j + 1, s), val, "Transformation matrix element", rnd);
                    }
                }
            }
        }

        for (int i = 0; i < nwcs; i++) {
            String cunit = at(st.cunit, i);
            if (cunit != null) {
                setString(cards, axisKey("CUNIT", i + 1, s), cunit, "Units for axis " + (i + 1), rnd);
            }
        }

        // AXREF, SREFIS, SREF and SREFP: AST extensions, the reference position of a -TAB axis
        // with no inverse, and the SkyRefIs, SkyRef and SkyRefP of an offset SkyFrame.
        for (int i = 0; i < nwcs; i++) {
            Double val = at(st.axref, i);
            if (val != null) {
                setFloat(cards, axisKey("AXREF", i + 1, s), val, "Reference WCS value on axis " + (i + 1), rnd);
            }
        }
        if (st.skyrefis != null) {
            setString(cards, key("SREFIS", s), st.skyrefis, "Is SkyRef used as pole or origin?", rnd);
        }
        for (int i = 0; i < nwcs; i++) {
            Double val = at(st.skyref, i);
            if (val != null) {
                setFloat(cards, axisKey("SREF", i + 1, s), val, "Sky reference position on axis " + (i + 1), rnd);
            }
        }
        for (int i = 0; i < nwcs; i++) {
            Double val = at(st.skyrefp, i);
            if (val != null) {
                setFloat(
                        cards,
                        axisKey("SREFP", i + 1, s),
                        val,
                        "Sky primary meridian position on axis " + (i + 1),
                        rnd);
            }
        }

        // The date of observation and its timescale, for the primary description only. No write
        // stores an MJD-AVG, which C would write after DATE-OBS.
        if (s == ' ') {
            if (st.mjdobs != null) {
                setFloat(cards, "MJD-OBS", st.mjdobs, "Modified Julian Date of observation", rnd);
                setString(cards, "DATE-OBS", FitsDates.mjdToDateStr(st.mjdobs), "Date of observation", rnd);
            }
            if (st.timesys != null) {
                setString(cards, "TIMESYS", st.timesys, "Timescale for MJD-OBS/MJD-AVG values", rnd);
            }
        }

        // Numerical projection parameters. The interpolation method of a -TAB axis, an AST
        // extension in PVi_4, is written as QVi_4 when it is not zero, its default.
        int maxm = st.maxPvM();
        for (int i = 0; i < nwcs; i++) {
            for (int m = 0; m <= maxm; m++) {
                Double val = at(st.pv, i, m);
                if (val == null) {
                    continue;
                }
                if (m == 4 && tabaxis[i]) {
                    if (val != 0.0) {
                        setFloat(cards, matrixKey("QV", i + 1, m, s), val, "Use nearest neighbour interpolation", rnd);
                    }
                } else {
                    setFloat(cards, matrixKey(parprefix, i + 1, m, s), val, "Projection parameter", rnd);
                }
            }
        }

        // String projection parameters.
        int maxms = maxPsM(st);
        for (int i = 0; i < nwcs; i++) {
            for (int m = 0; m <= maxms; m++) {
                String val = at(st.ps, i, m);
                if (val != null) {
                    setString(cards, matrixKey("PS", i + 1, m, s), val, "Projection parameter", rnd);
                }
            }
        }

        // Celestial axes.
        if (st.radesys != null) {
            setString(cards, key("RADESYS", s), st.radesys, "Reference frame for RA/DEC values", rnd);
        }
        if (st.equinox != null) {
            setFloat(cards, key("EQUINOX", s), st.equinox, "[yr] Epoch of reference equinox", rnd);
        }
        if (st.latpole != null) {
            setFloat(cards, key("LATPOLE", s), st.latpole, "[deg] Latitude of native north pole", rnd);
        }
        if (st.lonpole != null) {
            setFloat(cards, key("LONPOLE", s), st.lonpole, "[deg] Longitude of native north pole", rnd);
        }

        // Spectral axes.
        if (st.specsys != null) {
            setString(cards, key("SPECSYS", s), st.specsys, "Standard of rest for spectral axis", rnd);
        }
        if (st.ssyssrc != null) {
            setString(cards, key("SSYSSRC", s), st.ssyssrc, "Standard of rest for source redshift", rnd);
        }
        if (st.zsource != null) {
            setFloat(cards, key("ZSOURCE", s), st.zsource, "[] Redshift of source", rnd);
        }
        if (st.velosys != null) {
            setFloat(cards, key("VELOSYS", s), st.velosys, "[m/s] Topo. apparent velocity of rest frame", rnd);
        }
        if (st.restfrq != null) {
            setFloat(cards, key("RESTFRQ", s), st.restfrq, "[Hz] Rest frequency", rnd);
        }
        if (st.restwav != null) {
            setFloat(cards, key("RESTWAV", s), st.restwav, "[m] Rest wavelength", rnd);
        }
        // IMAGFREQ, an extension for double sideband data, has no version letter.
        if (st.imagfreq != null) {
            setFloat(cards, "IMAGFREQ", st.imagfreq, "[Hz] Image frequency", rnd);
        }

        // The observer's position, for the primary description only.
        if (s == ' ') {
            if (st.obsgeox != null) {
                setFloat(cards, "OBSGEO-X", st.obsgeox, "[m] Observatory geocentric X", rnd);
            }
            if (st.obsgeoy != null) {
                setFloat(cards, "OBSGEO-Y", st.obsgeoy, "[m] Observatory geocentric Y", rnd);
            }
            if (st.obsgeoz != null) {
                setFloat(cards, "OBSGEO-Z", st.obsgeoz, "[m] Observatory geocentric Z", rnd);
            }
        }

        // The SIP coefficients, forward then inverse, each table followed by its order. The
        // coefficients carry the version letter but the order keywords do not (C-library quirk,
        // kept).
        writeSip(cards, st.sipA, "A_", "A_ORDER", s, "SIP forward distortion coeff", "SIP max order", rnd);
        writeSip(cards, st.sipB, "B_", "B_ORDER", s, "SIP forward distortion coeff", "SIP max order", rnd);
        writeSip(cards, st.sipAp, "AP_", "AP_ORDER", s, "SIP inverse distortion coeff", "SIP inverse max order", rnd);
        writeSip(cards, st.sipBp, "BP_", "BP_ORDER", s, "SIP inverse distortion coeff", "SIP inverse max order", rnd);
        return true;
    }

    /**
     * One table of SIP coefficients: a card {@code <prefix><p>_<q>} for each coefficient set, in
     * order of the power {@code p} and then {@code q}, and, if the highest {@code p + q} is above
     * zero, the order keyword with it.
     */
    private static void writeSip(
            FitsCardList cards,
            double[][] item,
            String prefix,
            String orderKwd,
            char s,
            String comment,
            String orderComment,
            int rnd) {
        int order = 0;
        for (int p = 0; p < item.length; p++) {
            for (int q = 0; q < item[p].length; q++) {
                double val = item[p][q];
                if (val != AstObject.AST__BAD) {
                    setFloat(cards, matrixKey(prefix, p, q, s), val, comment, rnd);
                    if (p + q > order) {
                        order = p + q;
                    }
                }
            }
        }
        if (order > 0) {
            setInt(cards, orderKwd, order, orderComment, rnd);
        }
    }

    /** C's {@code GetMaxJMC( &(store->ps) )}: the highest PS parameter index stored, or -1. */
    private static int maxPsM(FitsStore st) {
        int max = -1;
        for (String[] axis : st.ps) {
            for (int m = axis.length - 1; m > max; m--) {
                if (axis[m] != null) {
                    max = m;
                    break;
                }
            }
        }
        return max;
    }

    /** C's {@code FormatKey( name, -1, -1, s )}: {@code name} and the version letter. */
    private static String key(String name, char s) {
        return s == ' ' ? name : name + s;
    }

    /** C's {@code FormatKey( name, i, -1, s )}. */
    private static String axisKey(String name, int i, char s) {
        return key(name + i, s);
    }

    /** C's {@code FormatKey( name, i, j, s )}. */
    private static String matrixKey(String name, int i, int j, char s) {
        return key(name + i + "_" + j, s);
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
