package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * C's {@code FsetToStore} (fitschan.c): the FITS-WCS axis descriptions of a FrameSet that a write
 * turns into cards, with the functions it calls to make each description ({@code AddVersion},
 * {@code MakeFitsFrameSet}, {@code FitsAxisOrder}, {@code ReadCrval} and {@code SpectralAxes}).
 *
 * <p>The base Frame of the FrameSet is the pixel Frame and its current Frame gives the primary
 * description; each other Frame (but those whose Domain begins {@code IWC}) gives an alternate
 * description, and an offset SkyFrame also one of the absolute coordinates it is offset in.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/mod.rs} and {@code wcs_write/plane_stores.rs},
 * following fitschan.c, whose structure is kept where Rust's follows from its ownership rules: the
 * descriptions are made from the caller's FrameSet by Frame index, the SkyRefIs attribute of an
 * offset SkyFrame is cleared and restored on the caller's FrameSet, and the store is indexed by
 * version letter.
 */
final class FitsFsetToStore {

    /** Seconds per day, C's {@code SPD}. */
    private static final double SPD = 86400.0;

    /** Radians to degrees, C's {@code AST__DR2D}. */
    private static final double AST__DR2D = 57.29577951308232;

    /** Machine precision, C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    private FitsFsetToStore() {}

    /**
     * C's {@code FsetToStore}: the axis descriptions of {@code fset} for a FITS write. The primary
     * description is made from the current Frame; then, unless AltAxes is NONE, a description of
     * absolute coordinates if the current Frame is an offset SkyFrame, and for each other Frame in
     * turn that is given a version letter ({@link FitsPlaneStores#versionLetters}) its description
     * and, if it is an offset SkyFrame, one of absolute coordinates. Each description of absolute
     * coordinates takes the letter after the last one taken, which C does not check against the
     * letters Frames take from their Ident.
     *
     * <p>The SkyRefIs attribute of an offset SkyFrame is cleared through the FrameSet while its
     * absolute description is made, and set back, so the FrameSet is remapped twice.
     *
     * @param fc the FitsChan written to, whose attributes control the descriptions, from whose
     *     cards {@link #readCrval} reads reference values, and to which warnings go
     * @param fset the FrameSet
     * @param naxis the number of axes of the base Frame
     * @param dim the number of pixels along each pixel axis, {@code AST__BAD} where unknown
     * @param enc the encoding written
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return the descriptions, or null if the primary description cannot be made
     * @throws UnsupportedOperationException where a description needs a class this library lacks
     */
    static FitsPlaneStores fsetToStore(
            FitsChan fc, FrameSet fset, int naxis, double[] dim, FitsChan.Encoding enc, String method, String cls) {
        FitsPlaneStores ret = new FitsPlaneStores(naxis);
        int ibase = fset.getBase();
        int icurr = fset.getCurrent();
        int isoff = FitsSkySys.isSkyOff(fset, icurr);

        boolean primok = addVersion(fc, fset, ibase, icurr, ret, dim, ' ', enc, isoff, method, cls);

        int altaxes = fc.getAltAxes();
        if (primok && altaxes != 2) {
            FitsPlaneStores.Letters letters = FitsPlaneStores.versionLetters(fset, ibase, icurr, altaxes);
            char[] sid = letters.sid;
            char s = letters.last;

            if (isoff != 0 && ++s <= 'Z') {
                addVersion(fc, fset, ibase, icurr, ret, dim, s, enc, -1, method, cls);
            }

            for (int ifrm = 1; ifrm <= fset.getNframe(); ifrm++) {
                char s0 = sid[ifrm];
                if (s0 != 0 && s0 != 1) {
                    isoff = FitsSkySys.isSkyOff(fset, ifrm);
                    addVersion(fc, fset, ibase, ifrm, ret, dim, s0, enc, isoff, method, cls);
                    if (isoff != 0 && ++s <= 'Z') {
                        addVersion(fc, fset, ibase, ifrm, ret, dim, s, enc, -1, method, cls);
                    }
                }
            }
        }
        return primok ? ret : null;
    }

    /**
     * C's {@code AddVersion}: stores in version {@code s} of {@code stores} the description of
     * Frame {@code iwcs} of {@code fs} relative to the pixel Frame {@code ipix}, and whether it
     * could be made: the celestial axes ({@link FitsCelestialAxes#celestialAxes}), the spectral
     * axes ({@link #spectralAxes}) and the rest ({@link FitsOtherAxes#otherAxes}) give the Mapping
     * from world to intermediate world coordinates, and {@link FitsMakeIntWorld#makeIntWorld} the
     * CRPIX, PC and CDELT values that make the pixel to intermediate world Mapping. For a
     * description it makes, WCSNAME is the Frame's Domain if that is set, the primary description
     * stores DUT1 (in days) and DTAI if they are set, a CRVAL smaller than {@code
     * sqrt(DBL_EPSILON)} times the pixel size becomes zero, and each CRPIX is rounded to a
     * millionth of a pixel.
     *
     * <p>The description cannot be made if the Frames cannot ({@link #makeFitsFrameSet}), if the
     * Mapping has no inverse and TabOK is not positive, if no FITS axis order is found, or if the
     * pixel to intermediate world Mapping is not linear enough.
     *
     * @param isoff positive to describe the offset coordinates of an offset SkyFrame; negative to
     *     describe the absolute coordinates it is offset in, its SkyRefIs being cleared meanwhile;
     *     zero for a Frame that is no offset SkyFrame
     * @return whether the description was made
     */
    static boolean addVersion(
            FitsChan fc,
            FrameSet fs,
            int ipix,
            int iwcs,
            FitsPlaneStores stores,
            double[] dim,
            char s,
            FitsChan.Encoding enc,
            int isoff,
            String method,
            String cls) {
        // For the absolute coordinates of an offset SkyFrame, clear its SkyRefIs through the
        // FrameSet, so that it is remapped, noting the original value as a negative isoff.
        String oldSkyrefis = null;
        int icurr = 0;
        int jsoff = isoff;
        if (isoff < 0) {
            icurr = fs.getCurrent();
            fs.setCurrent(iwcs);
            oldSkyrefis = fs.getC("SkyRefIs");
            if ("POLE".equalsIgnoreCase(oldSkyrefis)) {
                jsoff = -1;
            } else if ("ORIGIN".equalsIgnoreCase(oldSkyrefis)) {
                jsoff = -2;
            } else {
                jsoff = -3;
            }
            fs.clear("SkyRefIs");
            fs.setCurrent(icurr);
        }

        FrameSet fset;
        try {
            fset = makeFitsFrameSet(fc, fs, ipix, iwcs, enc, method, cls);
        } finally {
            if (oldSkyrefis != null) {
                fs.setCurrent(iwcs);
                fs.setC("SkyRefIs", oldSkyrefis);
                fs.setCurrent(icurr);
            }
        }
        if (fset == null) {
            return false;
        }

        Mapping mapping = fset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Frame wcsfrm = fset.getFrame(FrameSet.AST__CURRENT);
        if (!mapping.getTranInverse() && fc.getTabOK() <= 0) {
            return false;
        }

        int nwcs = mapping.getNout();
        int[] wperm = new int[nwcs];
        if (!fitsAxisOrder(fc, nwcs, wcsfrm, wperm) && !FitsWorldAxes.worldAxes(fc, mapping, dim, wperm)) {
            return false;
        }

        boolean[] axisDone = new boolean[nwcs];
        double[] crvals = readCrval(fc, wcsfrm, s, method, cls);

        FitsStore store = stores.version(s, Math.max(fset.getNin(), nwcs));

        Mapping iwcmap = FitsCelestialAxes.celestialAxes(fc, fset, dim, wperm, s, store, axisDone, jsoff, method, cls);
        Mapping tmap = spectralAxes(fset);
        iwcmap = new CmpMap(iwcmap, tmap, true);
        tmap = FitsOtherAxes.otherAxes(fc, fset, dim, wperm, s, store, crvals, axisDone, method, cls);
        iwcmap = new CmpMap(iwcmap, tmap, true);

        // The pixel to world Mapping is taken again, as C takes it again in case SpectralAxes
        // remapped the world Frame.
        mapping = fset.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping pixiwcmap = new CmpMap(mapping, iwcmap, true);

        double fitstol = fc.getFitsTol();
        // Only FITS-WCS writes SIP coefficients.
        boolean sipok = enc == FitsChan.Encoding.FITS_WCS && fc.getSipOK() != 0;

        boolean ret =
                FitsMakeIntWorld.makeIntWorld(pixiwcmap, wcsfrm, wperm, s, store, dim, fitstol, sipok, method, cls);

        if (ret) {
            if (wcsfrm.testDomain()) {
                store.wcsname = wcsfrm.getDomain();
            }
            if (wcsfrm.testDut1() && s == ' ') {
                store.dut1 = wcsfrm.getDut1() / SPD;
            }
            if (wcsfrm.testDtai() && s == ' ') {
                store.dtai = wcsfrm.getDtai();
            }

            // CRVAL values very small compared with the pixel size become zero.
            for (int iax = 0; iax < nwcs; iax++) {
                int fitsI = wperm[iax];
                Double crval = store.crval[fitsI];
                if (crval != null) {
                    double cdelt2 = 0.0;
                    for (int fitsJ = 0; fitsJ < nwcs; fitsJ++) {
                        Double p = store.pc[fitsI][fitsJ];
                        double pc = p != null ? p : (fitsI == fitsJ ? 1.0 : 0.0);
                        cdelt2 += pc * pc;
                    }
                    double cdelt = store.cdelt[fitsI] != null ? store.cdelt[fitsI] : 1.0;
                    cdelt2 *= cdelt * cdelt;
                    if (Math.abs(crval) < Math.sqrt(DBL_EPSILON * cdelt2)) {
                        store.crval[fitsI] = 0.0;
                    }
                }
            }

            // CRPIX values are rounded to the nearest millionth of a pixel.
            for (int iax = 0; iax < nwcs; iax++) {
                Double crpix = store.crpix[iax];
                if (crpix != null) {
                    store.crpix[iax] = Math.floor(crpix * 1.0e6 + 0.5) * 1.0e-6;
                }
            }
        }
        return ret;
    }

    /**
     * C's {@code MakeFitsFrameSet}: a new FrameSet whose base Frame is a copy of Frame {@code ipix}
     * of {@code fset} and whose current Frame is a copy of Frame {@code iwcs}, joined by a copy of
     * the Mapping between them, made to fit the FITS model. Each axis of the world Frame that is
     * neither a SkyFrame axis (of a SkyFrame whose Domain is {@code SKY}) nor a spectral axis is
     * changed from its internal units to its Unit: the Mapping gains the conversion, or where
     * there is none, the Unit is set to the internal units (to degrees for internal radians,
     * converted by a ZoomMap).
     *
     * <p>C's other changes are for a SpecFrame: one with a celestial reference point gains
     * celestial axes, or has the reference point made that of the celestial axes; and for
     * FITS-CLASS {@code AddEncodingFrame} adds a Frame for the SpecFrame and SkyFrame. Spectral
     * axes are not yet written, so a world Frame with a SpecFrame axis is refused.
     *
     * @return the FrameSet, or null if the world Frame holds axes of two SkyFrames
     * @throws UnsupportedOperationException if the world Frame has a SpecFrame axis
     */
    // C compares the pointers astPrimaryFrame returns: whether two axes are in the same SkyFrame.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static FrameSet makeFitsFrameSet(
            FitsChan fc, FrameSet fset, int ipix, int iwcs, FitsChan.Encoding enc, String method, String cls) {
        Frame pixfrm = (Frame) fset.getFrame(ipix).copy();
        Frame wcsfrm = (Frame) fset.getFrame(iwcs).copy();
        Mapping map = (Mapping) fset.getMapping(ipix, iwcs).copy();
        int nwcs = wcsfrm.getNaxes();
        for (int iax = 0; iax < nwcs; iax++) {
            if (wcsfrm.primaryFrame(iax + 1, new int[1]) instanceof SpecFrame) {
                throw new UnsupportedOperationException(method + "(" + cls + "): the spectral axis " + (iax + 1)
                        + " of the Frame written needs SpecFrame");
            }
        }

        // The SkyFrame of the world Frame, which must be its only one, and the Mapping from the
        // internal units of each axis to its Unit.
        Mapping remap = null;
        Frame skyfrm = null;
        boolean ok = true;
        int[] paxis = new int[1];
        for (int iax = 0; iax < nwcs; iax++) {
            Frame pframe = wcsfrm.primaryFrame(iax + 1, paxis);
            Mapping umap = null;
            if (FitsMakeIntWorld.isASkyFrame(pframe)) {
                if (skyfrm != null) {
                    if (pframe != skyfrm) {
                        ok = false;
                        break;
                    }
                } else {
                    skyfrm = pframe;
                }
            } else {
                String extunit = pframe.getUnit(paxis[0]);
                String intunit = pframe.getInternalUnit(paxis[0]);
                if (!extunit.equals(intunit)) {
                    // An error from the unit mapper is ignored, as C ignores it.
                    try {
                        umap = Units.unitMapper(intunit, extunit);
                    } catch (AstException e) {
                        umap = null;
                    }
                    if (umap == null) {
                        if ("rad".equals(intunit)) {
                            umap = new ZoomMap(1, AST__DR2D);
                            extunit = "deg";
                        } else {
                            extunit = intunit;
                        }
                        wcsfrm.setUnit(iax + 1, extunit);
                    }
                }
            }
            if (umap == null) {
                umap = new UnitMap(1);
            }
            remap = remap != null ? new CmpMap(remap, umap, false) : umap;
        }

        if (remap != null && !(remap.simplify() instanceof UnitMap)) {
            map = new CmpMap(map, remap, true);
        }

        // Two SkyFrames make the FrameSet unusable. C then passes a NULL FrameSet to
        // AddEncodingFrame, which dereferences it; the FrameSet that is not made is reported here
        // as C's caller expects.
        if (!ok) {
            return null;
        }
        FrameSet ret = new FrameSet(pixfrm);
        ret.addFrame(FrameSet.AST__BASE, map, wcsfrm);
        return ret;
    }

    /**
     * C's {@code FitsAxisOrder}: sets {@code perm} to the zero-based FITS axis of each axis of
     * {@code wcsfrm} that the FitsChan's FitsAxisOrder gives, and returns whether it gives them
     * (it does unless it is {@code <auto>}). {@code <copy>} keeps the Frame's order; a list of
     * axis Symbols, matched ignoring case and with words naming no axis passed over, gives the
     * axes their places in the list.
     *
     * @throws AstException with status {@code AST__ATTIN} if the list names an axis twice or
     *     leaves one out
     */
    static boolean fitsAxisOrder(FitsChan fc, int nwcs, Frame wcsfrm, int[] perm) {
        for (int i = 0; i < nwcs; i++) {
            perm[i] = i;
        }
        String attr = fc.getFitsAxisOrder();
        boolean result = !AstObject.chrMatch(attr, "<auto>");
        if (result && !AstObject.chrMatch(attr, "<copy>")) {
            // C's KeyMap with KeyCase=0: the axis index of each Symbol, ignoring case.
            KeyMap km = new KeyMap();
            km.setKeyCase(false);
            for (int i = 0; i < nwcs; i++) {
                km.mapPut0I(wcsfrm.getC("Symbol(" + (i + 1) + ")"), i, null);
            }
            int k = 0;
            for (String word : chrSplit(attr)) {
                Integer j = km.mapGet0I(word);
                if (j != null) {
                    if (j < 0) {
                        throw Ast.err(
                                AstException.AST__ATTIN,
                                "astWrite(fitschan): attribute FitsAxisOrder (" + attr + ") refers to axis " + word
                                        + " more than once.");
                    }
                    perm[j] = k++;
                    km.mapPut0I(word, -1, null);
                }
            }
            for (int i = 0; i < nwcs; i++) {
                String sym = wcsfrm.getC("Symbol(" + (i + 1) + ")");
                Integer j = km.mapGet0I(sym);
                if (j != null && j >= 0) {
                    throw Ast.err(
                            AstException.AST__ATTIN,
                            "astWrite(fitschan): attribute FitsAxisOrder (" + attr
                                    + ") does not specify a position for WCS axis '" + sym + "'.");
                }
            }
        }
        return result;
    }

    /** C's {@code astChrSplit}: the words of {@code str}, separated by white space. */
    private static List<String> chrSplit(String str) {
        List<String> words = new ArrayList<>();
        int i = 0;
        int n = str.length();
        while (i < n) {
            while (i < n && CScan.isCSpace(str.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !CScan.isCSpace(str.charAt(i))) {
                i++;
            }
            if (i > start) {
                words.add(str.substring(start, i));
            }
        }
        return words;
    }

    /**
     * C's {@code ReadCrval}: the reference point of axis description {@code s} in the cards of
     * {@code fc}, in the coordinates of {@code wcsfrm}, or null if there is none. The cards are
     * read afresh, as if none had been read: in a copy of the FitsChan with its Encoding cleared
     * and every card marked unused, the CRVAL values of the description are read (all of them up
     * to the highest axis number, or none), a FrameSet is read, and the CRVAL position is
     * transformed from its Frame whose Ident is {@code s} to {@code wcsfrm}, if that Frame has as
     * many axes as there are CRVAL values and can be converted to {@code wcsfrm} with the Domain
     * of {@code wcsfrm}. An error in reading the FrameSet gives null, as C clears it.
     *
     * @return the reference point, with an element for each axis of {@code wcsfrm}, or null
     * @throws UnsupportedOperationException if reading the FrameSet needs a class or encoding this
     *     library lacks
     */
    static double[] readCrval(FitsChan fc0, Frame wcsfrm, char s, String method, String cls) {
        FitsChan fc = (FitsChan) fc0.copy();
        fc.clear("Encoding");
        fc.cards().resetUsed();

        String template = s == ' ' ? "CRVAL%d" : "CRVAL%d" + s;
        FitsCardList.KeyFields kf = fc.cards().keyFields(template, 1);
        if (kf.n <= 0) {
            return null;
        }
        int hii = kf.hi[0];
        double[] crval = new double[hii];
        boolean ok = true;
        for (int iax = 0; iax < hii && ok; iax++) {
            Double v = fc.cards().getFloat(FitsWcsKeywords.keyFor("CRVAL", iax + 1, s));
            if (v == null) {
                ok = false;
            } else {
                crval[iax] = v;
            }
        }
        if (!ok) {
            return null;
        }

        double[] ret = null;
        fc.cards().clearCard();
        FrameSet fs;
        try {
            AstObject obj = fc.read();
            fs = obj instanceof FrameSet ? (FrameSet) obj : null;
        } catch (AstException e) {
            fs = null;
        }
        if (fs != null) {
            int nfr = fs.getNframe();
            int ifr;
            for (ifr = 1; ifr <= nfr; ifr++) {
                fs.setCurrent(ifr);
                Frame tfrm = fs.getFrame(ifr);
                String id = tfrm.test("Ident") ? tfrm.getIdent() : null;
                if (id != null && id.length() == 1 && id.charAt(0) == s) {
                    break;
                }
            }
            if (ifr <= nfr && fs.getNaxes() == hii) {
                FrameSet tfs = fs.convert(wcsfrm, wcsfrm.getDomain());
                if (tfs != null) {
                    ret = tfs.tranN(1, hii, crval, true, wcsfrm.getNaxes());
                }
            }
        }
        return ret;
    }

    /**
     * C's {@code SpectralAxes} for a FrameSet with no SpecFrame axis ({@link #makeFitsFrameSet}
     * refuses one): no axis is spectral, nothing is stored, and the Mapping from world to
     * intermediate world coordinates is a UnitMap passing every axis of the current Frame.
     */
    static Mapping spectralAxes(FrameSet fs) {
        return new UnitMap(fs.getNout());
    }
}
