package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The extraction of the FITS-WCS keywords of one axis description from a card list into a {@link
 * FitsStore}: the values C's {@code WcsFcRead} (fitschan.c:36696) stores.
 *
 * <p>Each keyword is looked up by name and given the value C's {@code WcsFcRead} stores for it:
 * that sweep visits the cards in order and stores each one it recognises whose value converts, so
 * of two cards for one keyword the last that converts gives the value ({@link
 * FitsCardList#getStoredFloat}). The SIP coefficients are read by a sweep, as C reads them.
 *
 * <p>Ported from Rust {@code fitschan/wcs_keywords.rs}.
 */
final class FitsWcsKeywords {

    private FitsWcsKeywords() {}

    /** The default of a FitsChan's FitsRounding attribute. */
    private static final int DEFAULT_FITS_ROUNDING = 10;

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double DD2R = 0.017453292519943295;

    /**
     * The name of a keyword of axis description {@code s}, C's {@code FormatKey} for the shapes
     * FITS-WCS uses: {@code prefix}, then {@code axis} unless it is 0, then {@code s} unless it is a
     * space (the primary description).
     *
     * @param axis the axis, from 1, or 0 for a keyword with no axis number
     */
    static String keyFor(String prefix, int axis, char s) {
        StringBuilder b = new StringBuilder(prefix);
        if (axis > 0) {
            b.append(axis);
        }
        if (s != ' ') {
            b.append(s);
        }
        return b.toString();
    }

    /** The name of a keyword with two indices, such as {@code PCi_j} or {@code CDi_j}, of axis description {@code s}. */
    static String keyFor2(String prefix, int i, int j, char s) {
        StringBuilder b = new StringBuilder(prefix).append(i).append('_').append(j);
        if (s != ' ') {
            b.append(s);
        }
        return b.toString();
    }

    /**
     * The value {@code f} gives for the keyword {@link #keyFor} names. As C's {@code GetItem}
     * (fitschan.c:13261) each axis description has its own values, with no fallback to the primary
     * description's; the defaults alternate descriptions take from the CTYPE values are applied
     * when the Frames are built.
     */
    static <T> T lookupWithFallback(
            FitsCardList cards, String prefix, int axis, char s, BiFunction<FitsCardList, String, T> f) {
        return f.apply(cards, keyFor(prefix, axis, s));
    }

    /**
     * Reads TIMESYS, and the date of observation as C's {@code ChooseEpoch} (fitschan.c:6294) takes
     * it, into {@code store}: MJD-AVG, or MJD-OBS when there is none. TIMESYS, MJD-AVG and MJD-OBS
     * have only primary branches in C's {@code WcsFcRead} (fitschan.c:37048-37055, 37243-37250,
     * 37279-37286), and {@code ChooseEpoch} falls back to the primary values, so every axis
     * description takes the primary keywords. C's {@code WcsFcRead} has no DATE-OBS branch: {@code
     * SpecTrans} translates DATE-OBS to MJD-OBS when there is no MJD-OBS (fitschan.c:32157-32184).
     * The cards are read without being consumed, as C reads them with {@code mark} 0.
     */
    static void readEpochIntoStore(FitsCardList cards, FitsStore store, int fitsrnd) {
        // TIMESYS is never consumed but is stripped as WcsFcRead strips every string it stores.
        store.timesys = FitsCardList.stripWcsStoreString(cards.getStoredString("TIMESYS", fitsrnd));
        Double mjd = cards.getStoredFloat("MJD-AVG");
        if (mjd == null) {
            mjd = cards.getStoredFloat("MJD-OBS");
        }
        store.mjdobs = mjd;
    }

    /** C's {@code round}: to the nearest integer, halfway cases away from zero. */
    private static double roundHalfAway(double x) {
        return x < 0.0 ? -Math.floor(-x + 0.5) : Math.floor(x + 0.5);
    }

    /**
     * The FITS-WCS keywords of axis description {@code s} in {@code cards}: a space for the primary
     * description, which reads keywords without a letter, or {@code 'A'} to {@code 'Z'} for an
     * alternate one, which reads keywords ending in that letter. Keywords the header does not give
     * are null. The store has the description's WCSAXES axes, or {@code npix}, the number of pixel
     * axes, as C takes the number of WCS axes (fitschan.c:38276-38281).
     *
     * <p>The keywords describing the WCS are recorded as consumed, as C's {@code WcsFcRead} marks
     * them; the observation details (the date, DUT1, DTAI and OBSGEO) are not.
     *
     * @throws AstException with status {@code AST__BDFTS} if there are no WCS axes
     */
    static FitsStore extractWcsKeywords(FitsCardList cards, char s, int npix) {
        return extractWcsKeywords(cards, s, npix, DEFAULT_FITS_ROUNDING);
    }

    /**
     * {@link #extractWcsKeywords(FitsCardList, char, int)} for a FitsChan whose FitsRounding is
     * {@code fitsrnd}, which shapes the text of a floating value stored as a string.
     */
    static FitsStore extractWcsKeywords(FitsCardList cards, char s, int npix, int fitsrnd) {
        // C reads WCSAXES as a floating value and rounds it (fitschan.c:38276-38281).
        Double wcsaxesValue = cards.getStoredFloat(keyFor("WCSAXES", 0, s));
        Integer wcsaxes = wcsaxesValue == null ? null : (int) Math.max(0.0, roundHalfAway(wcsaxesValue));
        int naxis = wcsaxes != null ? wcsaxes : npix;
        if (naxis == 0) {
            throw Ast.err(AstException.AST__BDFTS, "WCSAXES is zero");
        }
        FitsStore store = new FitsStore(naxis);
        store.wcsaxes = wcsaxes;
        if (wcsaxes != null) {
            cards.recordConsumed(keyFor("WCSAXES", 0, s));
        }

        for (int i = 0; i < naxis; i++) {
            int axisNum = i + 1;
            store.crpix[i] = lookupWithFallback(cards, "CRPIX", axisNum, s, FitsCardList::getStoredFloatMarked);
            store.crval[i] = lookupWithFallback(cards, "CRVAL", axisNum, s, FitsCardList::getStoredFloatMarked);
            store.cdelt[i] = lookupWithFallback(cards, "CDELT", axisNum, s, FitsCardList::getStoredFloatMarked);
            // NAXISn describes the array, not the WCS, and is never consumed.
            store.dims[i] = cards.getFloat("NAXIS" + axisNum);
            store.ctype[i] = FitsCardList.stripWcsStoreString(
                    lookupWithFallback(cards, "CTYPE", axisNum, s, (c, k) -> c.getStoredStringMarked(k, fitsrnd)));
            // The comment of the CTYPE card (fitschan.c:36845-36860).
            store.ctypeCom[i] = cards.getStoredComment(keyFor("CTYPE", axisNum, s));
            store.cunit[i] = FitsCardList.stripWcsStoreString(
                    lookupWithFallback(cards, "CUNIT", axisNum, s, (c, k) -> c.getStoredStringMarked(k, fitsrnd)));
            store.cname[i] = FitsCardList.stripWcsStoreString(
                    lookupWithFallback(cards, "CNAME", axisNum, s, (c, k) -> c.getStoredStringMarked(k, fitsrnd)));
            for (int j = 0; j < naxis; j++) {
                store.pc[i][j] = cards.getStoredFloatMarked(keyFor2("PC", axisNum, j + 1, s));
            }
            for (int m = 0; m < store.pv[i].length; m++) {
                store.pv[i][m] = cards.getStoredFloatMarked(keyFor2("PV", axisNum, m, s));
            }
            for (int m = 0; m < store.ps[i].length; m++) {
                store.ps[i][m] = FitsCardList.stripWcsStoreString(
                        cards.getStoredStringMarked(keyFor2("PS", axisNum, m, s), fitsrnd));
            }
        }

        store.radesys = FitsCardList.stripWcsStoreString(
                lookupWithFallback(cards, "RADESYS", 0, s, (c, k) -> c.getStoredStringMarked(k, fitsrnd)));
        store.equinox = lookupWithFallback(cards, "EQUINOX", 0, s, FitsCardList::getStoredFloatMarked);
        store.lonpole = lookupWithFallback(cards, "LONPOLE", 0, s, FitsCardList::getStoredFloatMarked);
        store.latpole = lookupWithFallback(cards, "LATPOLE", 0, s, FitsCardList::getStoredFloatMarked);
        readEpochIntoStore(cards, store, fitsrnd);
        store.specsys = FitsCardList.stripWcsStoreString(
                lookupWithFallback(cards, "SPECSYS", 0, s, (c, k) -> c.getStoredStringMarked(k, fitsrnd)));
        store.restfrq = lookupWithFallback(cards, "RESTFRQ", 0, s, FitsCardList::getStoredFloatMarked);
        store.restwav = lookupWithFallback(cards, "RESTWAV", 0, s, FitsCardList::getStoredFloatMarked);

        // DUT1 and DTAI have only primary branches in WcsFcRead (fitschan.c:37025-37046), and the
        // Frame of an alternate description takes the values stored for its own letter
        // (fitschan.c:38360-38365), so it has none. C reads DTAI only when TELESCOP names JCMT or
        // UKIRT, since other telescopes may use DTAI for other purposes; TELESCOP is read without
        // being consumed. Neither card is consumed.
        if (s == ' ') {
            String telescop = cards.getString("TELESCOP");
            boolean jcmtOrUkirt = telescop != null && (telescop.startsWith("JCMT") || telescop.startsWith("UKIRT"));
            store.dtai = jcmtOrUkirt ? cards.getStoredFloat("DTAI") : null;
            store.dut1 = cards.getStoredFloat("DUT1");
        }

        store.wcsname = FitsCardList.stripWcsStoreString(
                lookupWithFallback(cards, "WCSNAME", 0, s, (c, k) -> c.getStoredStringMarked(k, fitsrnd)));
        // OBSGEO-X/Y/Z have only primary branches, are read for the primary description whichever
        // description uses them (fitschan.c:39868-39870), and are never consumed.
        store.obsgeox = cards.getStoredFloat("OBSGEO-X");
        store.obsgeoy = cards.getStoredFloat("OBSGEO-Y");
        store.obsgeoz = cards.getStoredFloat("OBSGEO-Z");
        // SSYSSRC and ZSOURCE have secondary branches; IMAGFREQ has only a primary one
        // (fitschan.c:37171-37178). RFVAL has no WcsFcRead branch.
        store.ssyssrc = FitsCardList.stripWcsStoreString(cards.getStoredStringMarked(keyFor("SSYSSRC", 0, s), fitsrnd));
        store.zsource = cards.getStoredFloatMarked(keyFor("ZSOURCE", 0, s));
        store.imagfreq = s == ' ' ? cards.getStoredFloatMarked("IMAGFREQ") : null;
        store.skyrefis = FitsCardList.stripWcsStoreString(cards.getStoredStringMarked(keyFor("SREFIS", 0, s), fitsrnd));
        for (int i = 0; i < naxis; i++) {
            int axisNum = i + 1;
            store.skyref[i] = cards.getStoredFloatMarked(keyFor("SREF", axisNum, s));
            store.skyrefp[i] = cards.getStoredFloatMarked(keyFor("SREFP", axisNum, s));
            store.axref[i] = cards.getStoredFloatMarked(keyFor("AXREF", axisNum, s));
        }

        extractSip(cards, store, s);
        normaliseNcpToSin(store);
        return store;
    }

    /**
     * The axis descriptions of a FITS-WCS header: the number of pixel axes, the primary description
     * if C's {@code MakeFitsFrameSet} adds a Frame for it, and the alternate ones it adds, in order
     * (see {@link FitsWcsMark#wcsVersions}).
     */
    static final class WcsStores {
        /** The number of pixel axes. */
        final int npix;

        /** The primary axis description, or null. */
        FitsStore primary;

        /** The letters of the alternate axis descriptions, in the order of {@link #alts}. */
        final List<Character> altLetters;

        /** The alternate axis descriptions. */
        final List<FitsStore> alts;

        WcsStores(int npix, FitsStore primary, List<Character> altLetters, List<FitsStore> alts) {
            this.npix = npix;
            this.primary = primary;
            this.altLetters = altLetters;
            this.alts = alts;
        }
    }

    /** Every axis description of a FITS-WCS header. */
    static WcsStores extractWcsKeywordsWithAlts(FitsCardList cards) {
        return extractWcsKeywordsWithAlts(cards, DEFAULT_FITS_ROUNDING);
    }

    /**
     * {@link #extractWcsKeywordsWithAlts(FitsCardList)} for a FitsChan whose FitsRounding is
     * {@code fitsrnd}.
     */
    static WcsStores extractWcsKeywordsWithAlts(FitsCardList cards, int fitsrnd) {
        FitsWcsMark.WcsVersions versions = FitsWcsMark.wcsVersions(cards);
        int npix = versions.npix;
        FitsStore primary = versions.primary ? extractWcsKeywords(cards, ' ', npix, fitsrnd) : null;
        List<Character> letters = new ArrayList<>();
        List<FitsStore> alts = new ArrayList<>();
        for (char s : versions.alternates) {
            letters.add(s);
            alts.add(extractWcsKeywords(cards, s, npix, fitsrnd));
        }
        return new WcsStores(npix, primary, letters, alts);
    }

    /** The projection code of a CTYPE, its characters 6 to 8, if it has them. */
    private static boolean projectionIs(String ctype, String code) {
        return ctype != null && ctype.length() >= 8 && ctype.substring(5, 8).equalsIgnoreCase(code);
    }

    /**
     * Whether the SIN projection of {@code store} is written as the old {@code -NCP} form, as C's
     * writers decide (fitschan.c:3483-3505): the first SIN parameter on the latitude axis is zero or
     * unset, and the second is either at least 1.0E14 in magnitude with a reference latitude of
     * zero, or within 0.01 of the cotangent of the reference latitude. False for any other
     * projection.
     */
    static boolean sinShouldEmitAsNcp(FitsStore store) {
        int[] axes = store.celestialAxes();
        if (axes == null) {
            return false;
        }
        int latAxis = axes[1];
        if (!projectionIs(store.ctype[latAxis], "SIN")) {
            return false;
        }
        Double crvalLatDeg = store.crval[latAxis];
        if (crvalLatDeg == null) {
            return false;
        }
        Double[] pv = store.pv[latAxis];
        double p1 = pv.length > 1 && pv[1] != null ? pv[1] : 0.0;
        double p2 = pv.length > 2 && pv[2] != null ? pv[2] : 0.0;
        if (p1 != 0.0 || p2 == 0.0) {
            return false;
        }
        return (Math.abs(p2) >= 1.0e14 && crvalLatDeg == 0.0)
                || Math.abs(p2 * Math.tan(DD2R * crvalLatDeg) - 1.0) < 0.01;
    }

    /**
     * Translates a {@code -NCP} celestial CTYPE pair to {@code -SIN}, with the projection
     * parameters PV{@code lat}_1 = 0 and PV{@code lat}_2 = cot(reference latitude): C's translation
     * of the AIPS NCP projection in {@code SpecTrans} (fitschan.c:32212-32243). Nothing is changed
     * when the sine of the reference latitude is zero.
     */
    static void normaliseNcpToSin(FitsStore store) {
        int[] axes = store.celestialAxes();
        if (axes == null) {
            return;
        }
        int lonAxis = axes[0];
        int latAxis = axes[1];
        if (!projectionIs(store.ctype[latAxis], "NCP")) {
            return;
        }
        Double crvalLatDeg = store.crval[latAxis];
        if (crvalLatDeg == null) {
            return;
        }
        double sinval = Math.sin(crvalLatDeg * DD2R);
        if (sinval == 0.0) {
            return;
        }
        double cot = Math.cos(crvalLatDeg * DD2R) / sinval;
        if (store.ctype[lonAxis] != null) {
            store.ctype[lonAxis] = replaceProjection(store.ctype[lonAxis]);
        }
        store.ctype[latAxis] = replaceProjection(store.ctype[latAxis]);
        if (store.pv[latAxis].length > 2) {
            store.pv[latAxis][1] = 0.0;
            store.pv[latAxis][2] = cot;
        }
    }

    /** C's {@code strcpy( type + 4, "-SIN" )}. */
    private static String replaceProjection(String ctype) {
        return ctype.length() >= 4 ? ctype.substring(0, 4) + "-SIN" : ctype;
    }

    /**
     * The SIP coefficients of the keyword family {@code prefix} ({@code A}, {@code B}, {@code AP}
     * or {@code BP}) of axis description {@code s}, indexed by the two fields of the keyword name:
     * the {@code A_%d_%d} and {@code A_%d_%d%1c} branches of C's {@code WcsFcRead} and their
     * counterparts. As C's sweep does, cards already consumed are passed over, a card whose value
     * does not convert to a floating value is skipped (the sweep in {@link FitsWcsMark} reports
     * it), and a later card for a coefficient replaces an earlier one, since C's {@code SetItem}
     * overwrites (fitschan.c:37354-37365). An absent coefficient is {@code AST__BAD}, which is
     * what C's {@code GetItem} gives for it, and a row with no coefficients is empty where C's is
     * a NULL pointer.
     */
    private static double[][] readSipCoeffs(FitsCardList cards, String prefix, char s) {
        String pattern = s == ' ' ? prefix + "_%d_%d" : prefix + "_%d_%d%1c";
        double[][] table = new double[0][];
        for (FitsCard card : cards.cards()) {
            if (card.isConsumed()) {
                continue;
            }
            List<Integer> fields = FitsMatch.matchKeyword(card.keyword, pattern, 2);
            if (fields == null || fields.size() != 2 || fields.get(0) < 0 || fields.get(1) < 0) {
                continue;
            }
            if (s != ' ' && card.keyword.charAt(card.keyword.length() - 1) != s) {
                continue;
            }
            if (card.value.kind == FitsCard.Kind.UNDEF) {
                continue;
            }
            FitsCardValue.Got got = FitsCardValue.cnvType(card.value, FitsCardValue.Want.FLOAT, 0, card.keyword);
            if (got == null) {
                continue;
            }
            int i = fields.get(0);
            int jm = fields.get(1);
            if (table.length <= i) {
                int old = table.length;
                table = Arrays.copyOf(table, i + 1);
                for (int k = old; k <= i; k++) {
                    table[k] = new double[0];
                }
            }
            if (table[i].length <= jm) {
                int old = table[i].length;
                table[i] = Arrays.copyOf(table[i], jm + 1);
                Arrays.fill(table[i], old, jm + 1, AstObject.AST__BAD);
            }
            table[i][jm] = got.d;
        }
        return table;
    }

    /**
     * Removes a {@code -SIP} distortion code from the CTYPE of axis {@code k}, as C's {@code
     * DistortMaps} does (fitschan.c:9032-9044): the value, without trailing blanks, must end in
     * {@code -SIP}, compared as C's {@code strcmp} compares it.
     *
     * @return whether the code was there
     */
    private static boolean stripSip(FitsStore store, int k) {
        if (k >= store.ctype.length || store.ctype[k] == null) {
            return false;
        }
        String ct = store.ctype[k];
        int nc = FitsCard.chrLen(ct) - 4;
        if (nc < 0 || !"-SIP".equals(ct.substring(nc))) {
            return false;
        }
        store.ctype[k] = ct.substring(0, nc);
        return true;
    }

    /**
     * Records SIP distortion: the {@code -SIP} branch of C's {@code DistortMaps}
     * (fitschan.c:9026-9058). The distortion is present only when both of the first two CTYPE
     * values end in {@code -SIP}; the code is removed from each so that later steps see a plain
     * projection code. C tests and strips axis 1 first, and looks at axis 2 only when axis 1 had
     * the code, so a lone code on axis 1 is removed but gives no distortion. The {@code A_ORDER}
     * family is not read: C's {@code SIPMapping} takes the powers from the coefficients, and the
     * ORDER keywords are written only.
     */
    static void extractSip(FitsCardList cards, FitsStore store, char s) {
        if (!stripSip(store, 0) || !stripSip(store, 1)) {
            return;
        }
        store.sip = true;
        store.sipA = readSipCoeffs(cards, "A", s);
        store.sipB = readSipCoeffs(cards, "B", s);
        store.sipAp = readSipCoeffs(cards, "AP", s);
        store.sipBp = readSipCoeffs(cards, "BP", s);
    }
}
