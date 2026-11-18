package uk.ac.starlink.ast;

/**
 * The DSS encoding: the plate solutions of the Digitized Sky Survey. C's {@code DSSToStore} turns
 * the plate solution in a header into an axis description that FITS-WCS can hold, and {@code
 * DSSFromStore} writes such a description as DSS cards.
 *
 * <p>The description is two-dimensional: FK5 right ascension and declination at equinox J2000,
 * with LONPOLE 180, in the TPN projection (TAN with polynomial corrections), whose projection
 * parameters hold the plate solution's polynomial (the AMDXi and AMDYi coefficients divided by
 * 3600), with a CDELT of 1 and the pixel sizes, in millimetres, in a diagonal PC matrix (plate
 * coordinates in millimetres, "lambda = 1" in the draft of Calabretta and Greisen C cites). A
 * plain TAN projection is written as the TPN projection whose only polynomial term is the unit
 * one. A description with a rotation, or whose polynomial DSS cannot hold, is not written.
 *
 * <p>No DssMap is made: C's FitsChan no longer uses that class, so a DSS read gives a FrameSet
 * whose Mapping holds a TPN WcsMap (Rust c-library-quirks.md, "The DssMap documentation says a
 * DSS FitsChan read creates a DssMap").
 *
 * <p>Ported from fitschan.c {@code DSSToStore} and {@code DSSFromStore}, with Rust {@code
 * fitschan/enc_dss.rs}.
 */
final class FitsEncDss {

    /** C's {@code AST__BAD}, for the coefficients C marks as absent with it. */
    private static final double BAD = AstObject.AST__BAD;

    private FitsEncDss() {}

    /**
     * C's {@code DSSToStore}: the axis description of the DSS plate solution the cards of {@code
     * fc} hold. Each keyword is found as C's {@code GetValue} finds it, from the current card,
     * and each card whose value is read is marked as provisionally used: the plate solution's
     * cards, and PLTSCALE, PPO1, PPO2, PPO4 and PPO5, which are read only so that they are not
     * copied to another header. CNPIX1 and CNPIX2 default to zero, and an absent AMDXi or AMDYi
     * leaves the projection parameters it gives unset.
     *
     * @param method the calling method, for messages
     * @param cls the class of the FitsChan, for messages
     * @throws AstException with status {@code AST__BDFTS} if PPO3, PPO6, XPIXELSZ, YPIXELSZ,
     *     PLTRAH, PLTRAM, PLTRAS, PLTDECD, PLTDECM, PLTDECS or PLTDECSN is missing, or XPIXELSZ or
     *     YPIXELSZ is zero; {@code AST__FTCNV} if one of them does not convert; {@code AST__FUNDEF}
     *     if a card read has no value
     */
    static FitsStore dssToStore(FitsChan fc, String method, String cls) {
        FitsCardList cards = fc.cards();
        int rnd = fc.getFitsRounding();

        // The optional keywords, with their defaults.
        double cnpix1 = value(getFloat(cards, "CNPIX1", false, rnd, method, cls), 0.0);
        double cnpix2 = value(getFloat(cards, "CNPIX2", false, rnd, method, cls), 0.0);

        // The required keywords.
        double ppo3 = getFloat(cards, "PPO3", true, rnd, method, cls);
        double ppo6 = getFloat(cards, "PPO6", true, rnd, method, cls);
        double xpixelsz = getFloat(cards, "XPIXELSZ", true, rnd, method, cls);
        double ypixelsz = getFloat(cards, "YPIXELSZ", true, rnd, method, cls);
        double pltrah = getFloat(cards, "PLTRAH", true, rnd, method, cls);
        double pltram = getFloat(cards, "PLTRAM", true, rnd, method, cls);
        double pltras = getFloat(cards, "PLTRAS", true, rnd, method, cls);
        double pltdecd = getFloat(cards, "PLTDECD", true, rnd, method, cls);
        double pltdecm = getFloat(cards, "PLTDECM", true, rnd, method, cls);
        double pltdecs = getFloat(cards, "PLTDECS", true, rnd, method, cls);

        // The first word of PLTDECSN, up to 10 characters. C copies them with strncpy into a
        // buffer of 11, leaving it unterminated for a longer word; any such word differs from the
        // "-" it is compared with.
        String text = cards.getValue("PLTDECSN", FitsCardValue.Want.STRING, true, true, rnd, method, cls).s;
        int start = 0;
        while (start < text.length() && text.charAt(start) == ' ') {
            start++;
        }
        int end = text.indexOf(' ', start);
        String word = text.substring(start, end < 0 ? text.length() : end);
        String pltdecsn = word.length() > 10 ? word.substring(0, 10) : word;

        // Related keywords, read only so that they are marked as used.
        for (String key : new String[] {"PLTSCALE", "PPO1", "PPO2", "PPO4", "PPO5"}) {
            getFloat(cards, key, false, rnd, method, cls);
        }

        // The polynomial coefficients, BAD where absent.
        double[] amdx = new double[20];
        double[] amdy = new double[20];
        for (int i = 0; i < 20; i++) {
            amdx[i] = value(getFloat(cards, "AMDX" + (i + 1), false, rnd, method, cls), BAD);
            amdy[i] = value(getFloat(cards, "AMDY" + (i + 1), false, rnd, method, cls), BAD);
        }

        FitsStore store = new FitsStore(2);

        // The projection parameters equivalent to the coefficients.
        pvFromAmd(store, 0, amdx);
        pvFromAmd(store, 1, amdy);

        // The reference pixel.
        if (xpixelsz == 0.0) {
            throw Ast.err(
                    AstException.AST__BDFTS, method + "(" + cls + "): FITS keyword XPIXELSZ has illegal value 0.0");
        }
        store.crpix[0] = (ppo3 / xpixelsz) - cnpix1 + 0.5;
        if (ypixelsz == 0.0) {
            throw Ast.err(
                    AstException.AST__BDFTS, method + "(" + cls + "): FITS keyword YPIXELSZ has illegal value 0.0");
        }
        store.crpix[1] = (ppo6 / ypixelsz) - cnpix2 + 0.5;

        // The reference point.
        store.crval[0] = 15.0 * (pltrah + pltram / 60.0 + pltras / 3600.0);
        double crval2 = pltdecd + pltdecm / 60.0 + pltdecs / 3600.0;
        if ("-".equals(pltdecsn)) {
            crval2 = -crval2;
        }
        store.crval[1] = crval2;

        // The pixel sizes as the PC matrix, with CDELT 1.
        store.pc[0][0] = -0.001 * xpixelsz;
        store.pc[1][1] = 0.001 * ypixelsz;
        store.cdelt[0] = 1.0;
        store.cdelt[1] = 1.0;

        store.lonpole = 180.0;
        store.equinox = 2000.0;
        store.radesys = "FK5";
        store.wcsaxes = 2;
        store.ctype[0] = "RA---TPN";
        store.ctype[1] = "DEC--TPN";
        return store;
    }

    /**
     * Sets the projection parameters of axis {@code axis} from the coefficients {@code amd} of
     * that axis, each divided by 3600, as C's {@code DSSToStore} does for each axis; a parameter
     * whose coefficients are not all present is left unset.
     */
    private static void pvFromAmd(FitsStore store, int axis, double[] amd) {
        Double[] pv = store.pv[axis];
        if (amd[2] != BAD) {
            pv[0] = amd[2] / 3600.0;
        }
        if (amd[0] != BAD) {
            pv[1] = amd[0] / 3600.0;
        }
        if (amd[1] != BAD) {
            pv[2] = amd[1] / 3600.0;
        }
        if (amd[3] != BAD && amd[6] != BAD) {
            pv[4] = (amd[3] + amd[6]) / 3600.0;
        }
        if (amd[4] != BAD) {
            pv[5] = amd[4] / 3600.0;
        }
        if (amd[5] != BAD && amd[6] != BAD) {
            pv[6] = (amd[5] + amd[6]) / 3600.0;
        }
        if (amd[7] != BAD && amd[11] != BAD) {
            pv[7] = (amd[7] + amd[11]) / 3600.0;
        }
        if (amd[8] != BAD) {
            pv[8] = amd[8] / 3600.0;
        }
        if (amd[9] != BAD && amd[11] != BAD) {
            pv[9] = (amd[9] + amd[11]) / 3600.0;
        }
        if (amd[10] != BAD) {
            pv[10] = amd[10] / 3600.0;
        }
        if (amd[12] != BAD) {
            double p = amd[12] / 3600.0;
            pv[17] = p;
            pv[19] = 2 * p;
            pv[21] = p;
        }
    }

    /**
     * C's {@code DSSFromStore}: writes the primary description in {@code store} to {@code fc} as
     * DSS cards, each in front of the current card or over a card with its keyword, if DSS can
     * hold it: two pixel axes, RA and DEC in the TAN or TPN projection, LONPOLE unset or 180,
     * FK5 at equinox J2000 (or no equinox), non-zero pixel sizes, no rotation, and projection
     * parameters (TPN only) of the polynomial DSS describes. The corner pixel CNPIX is written as
     * (1, 1), with the plate centre PPO3, PPO6 to match; AMDX7, AMDY7, AMDX12 and AMDY12, which
     * duplicate other coefficients, are written as zero.
     *
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if the description was written, false (with nothing written) if DSS cannot
     *     hold it
     */
    static boolean dssFromStore(FitsChan fc, FitsPlaneStores store, String method, String cls) {
        FitsStore st = store.get(' ');
        if (st == null) {
            st = new FitsStore(0);
        }

        // Two-dimensional.
        if (st.maxCrpixAxis() != 1) {
            return false;
        }

        // RA and DEC, both TAN or both TPN.
        String cval = at(st.ctype, 0);
        if (cval == null) {
            return false;
        }
        boolean gottpn = "RA---TPN".equals(cval);
        if (!"RA---TAN".equals(cval) && !gottpn) {
            return false;
        }
        cval = at(st.ctype, 1);
        if (cval == null) {
            return false;
        }
        if (gottpn ? !"DEC--TPN".equals(cval) : !"DEC--TAN".equals(cval)) {
            return false;
        }

        // LONPOLE unset or 180, FK5, and the equinox unset or 2000.
        if (st.lonpole != null && st.lonpole != 180.0) {
            return false;
        }
        if (!"FK5".equals(st.radesys)) {
            return false;
        }
        if (st.equinox != null && st.equinox != 2000.0) {
            return false;
        }

        // The pixel sizes, from CDELT and the diagonal of the PC matrix; both must be non-zero.
        Double cdelt = at(st.cdelt, 0);
        if (cdelt == null) {
            return false;
        }
        double xpixelsz = cdelt * value(at(st.pc, 0, 0), 1.0);
        cdelt = at(st.cdelt, 1);
        if (cdelt == null) {
            return false;
        }
        double ypixelsz = cdelt * value(at(st.pc, 1, 1), 1.0);
        if (xpixelsz == 0.0 || ypixelsz == 0.0) {
            return false;
        }
        xpixelsz *= -1000.0;
        ypixelsz *= 1000.0;

        // No rotation.
        Double val = at(st.pc, 0, 1);
        if (val != null && val != 0.0) {
            return false;
        }
        val = at(st.pc, 1, 0);
        if (val != null && val != 0.0) {
            return false;
        }

        // The projection parameters, those of the unit polynomial for a TAN projection.
        double[] pvx = new double[22];
        double[] pvy = new double[22];
        for (int m = 0; m < 22; m++) {
            Double px = at(st.pv, 0, m);
            pvx[m] = px == null || !gottpn ? (m == 1 ? 1.0 : 0.0) : px;
            Double py = at(st.pv, 1, m);
            pvy[m] = py == null || !gottpn ? (m == 1 ? 1.0 : 0.0) : py;
        }

        // No other parameters, those DSS has no coefficient for zero, and the related ones
        // related.
        if (st.maxPvM() > 21) {
            return false;
        }
        if (pvx[3] != 0.0 || pvy[3] != 0.0) {
            return false;
        }
        for (int m = 11; m < 17; m++) {
            if (pvx[m] != 0.0 || pvy[m] != 0.0) {
                return false;
            }
        }
        if (pvx[18] != 0.0 || pvy[18] != 0.0) {
            return false;
        }
        if (pvx[20] != 0.0 || pvy[20] != 0.0) {
            return false;
        }
        if (!MappingHelpers.astEqual(2 * pvx[17], pvx[19])
                || !MappingHelpers.astEqual(pvx[17], pvx[21])
                || !MappingHelpers.astEqual(2 * pvy[17], pvy[19])
                || !MappingHelpers.astEqual(pvy[17], pvy[21])) {
            return false;
        }

        // The polynomial coefficients.
        double[] amdx = amdFromPv(pvx);
        double[] amdy = amdFromPv(pvy);

        // The plate scale is the mean of the first X and Y coefficients.
        double pltscl = 0.5 * (amdx[0] + amdy[0]);

        // The corner pixel (1, 1) and the plate centre that goes with it.
        double cnpix1 = 1.0;
        double cnpix2 = 1.0;
        double ppo1 = 0.0;
        double ppo2 = 0.0;
        val = at(st.crpix, 0);
        if (val == null) {
            return false;
        }
        double ppo3 = xpixelsz * (val + cnpix1 - 0.5);
        double ppo4 = 0.0;
        double ppo5 = 0.0;
        val = at(st.crpix, 1);
        if (val == null) {
            return false;
        }
        double ppo6 = ypixelsz * (val + cnpix2 - 0.5);

        // The reference RA in hours, from 0 to 24, as hours, minutes and seconds.
        val = at(st.crval, 0);
        if (val == null) {
            return false;
        }
        double h = val / 15.0;
        while (h < 0) {
            h += 24.0;
        }
        while (h >= 24.0) {
            h -= 24.0;
        }
        double pltrah = (int) h;
        h = 60.0 * (h - pltrah);
        double pltram = (int) h;
        double pltras = 60.0 * (h - pltram);

        // The reference DEC in degrees, from -180 to 180, as a sign, degrees, minutes and
        // seconds.
        val = at(st.crval, 1);
        if (val == null) {
            return false;
        }
        double d = val;
        while (d < -180.0) {
            d += 360.0;
        }
        while (d >= 180.0) {
            d -= 360.0;
        }
        String pltdecsn;
        if (d > 0.0) {
            pltdecsn = "+";
        } else {
            pltdecsn = "-";
            d = -d;
        }
        double pltdecd = (int) d;
        d = 60.0 * (d - pltdecd);
        double pltdecm = (int) d;
        double pltdecs = 60.0 * (d - pltdecm);

        FitsCardList cards = fc.cards();
        int rnd = fc.getFitsRounding();
        setFloat(cards, "CNPIX1", cnpix1, "X corner (pixels)", rnd);
        setFloat(cards, "CNPIX2", cnpix2, "Y corner (pixels)", rnd);
        setFloat(cards, "PPO1", ppo1, "Orientation co-efficients", rnd);
        setFloat(cards, "PPO2", ppo2, "", rnd);
        setFloat(cards, "PPO3", ppo3, "", rnd);
        setFloat(cards, "PPO4", ppo4, "", rnd);
        setFloat(cards, "PPO5", ppo5, "", rnd);
        setFloat(cards, "PPO6", ppo6, "", rnd);
        setFloat(cards, "XPIXELSZ", xpixelsz, "X pixel size (microns)", rnd);
        setFloat(cards, "YPIXELSZ", ypixelsz, "Y pixel size (microns)", rnd);
        setFloat(cards, "PLTRAH", pltrah, "RA at plate centre", rnd);
        setFloat(cards, "PLTRAM", pltram, "", rnd);
        setFloat(cards, "PLTRAS", pltras, "", rnd);
        setFloat(cards, "PLTDECD", pltdecd, "DEC at plate centre", rnd);
        setFloat(cards, "PLTDECM", pltdecm, "", rnd);
        setFloat(cards, "PLTDECS", pltdecs, "", rnd);
        cards.setValueAsWriter("PLTDECSN", FitsCard.Value.ofString(pltdecsn), "", rnd);
        setFloat(cards, "PLTSCALE", pltscl, "Plate scale (arcsec/mm)", rnd);
        String comm = "Plate solution x co-efficients";
        for (int i = 0; i < 20; i++) {
            setFloat(cards, "AMDX" + (i + 1), amdx[i], comm, rnd);
            comm = null;
        }
        comm = "Plate solution y co-efficients";
        for (int i = 0; i < 20; i++) {
            setFloat(cards, "AMDY" + (i + 1), amdy[i], comm, rnd);
            comm = null;
        }
        return true;
    }

    /**
     * The 20 coefficients of one axis of the polynomial that the projection parameters {@code pv}
     * of that axis give, each times 3600, as C's {@code DSSFromStore} sets them; AMD7 and AMD12,
     * which duplicate other coefficients, are zero.
     */
    private static double[] amdFromPv(double[] pv) {
        double[] amd = new double[20];
        amd[0] = 3600.0 * pv[1];
        amd[1] = 3600.0 * pv[2];
        amd[2] = 3600.0 * pv[0];
        amd[3] = 3600.0 * pv[4];
        amd[4] = 3600.0 * pv[5];
        amd[5] = 3600.0 * pv[6];
        amd[7] = 3600.0 * pv[7];
        amd[8] = 3600.0 * pv[8];
        amd[9] = 3600.0 * pv[9];
        amd[10] = 3600.0 * pv[10];
        amd[12] = 3600.0 * pv[17];
        return amd;
    }

    /** C's {@code GetValue} of a floating value, marking the card read. */
    private static Double getFloat(
            FitsCardList cards, String keyword, boolean report, int rnd, String method, String cls) {
        FitsCardValue.Got g = cards.getValue(keyword, FitsCardValue.Want.FLOAT, report, true, rnd, method, cls);
        return g == null ? null : g.d;
    }

    /** {@code v}, or {@code unset} (in place of C's unset item) if it is null. */
    private static double value(Double v, double unset) {
        return v != null ? v : unset;
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
}
