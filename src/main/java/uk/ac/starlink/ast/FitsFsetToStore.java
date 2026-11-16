package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * C's {@code FsetToStore} (fitschan.c): the FITS-WCS axis descriptions of a FrameSet that a write
 * turns into cards, with the functions it calls to make each description ({@code AddVersion},
 * {@code MakeFitsFrameSet} with {@code AddEncodingFrame}, {@code FitsAxisOrder} and {@code ReadCrval}).
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

    /** C's {@code AST__DBL_DIG}: the significant digits C's {@code %.*g} gives a double. */
    private static final int AST__DBL_DIG = 17;

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
     * axes ({@link FitsSpectralAxes#spectralAxes}) and the rest ({@link FitsOtherAxes#otherAxes}) give the Mapping
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
        Mapping tmap = FitsSpectralAxes.spectralAxes(fc, fset, dim, wperm, s, store, crvals, axisDone, method, cls);
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
     * <p>A SpecFrame whose RefRA and RefDec are both set is given the celestial axes of its
     * reference position: when the world Frame has no SkyFrame and at most two axes, a SkyFrame
     * (FK5 J2000) is added after its axes, fed by the Mapping of a TAN header with the reference
     * position at pixel (0, 0), the constant 0 being given to its two pixel axes; when it has a
     * SkyFrame, the SpecFrame's RefRA and RefDec are set, through the FrameSet, to the FK5 J2000
     * position of the fiducial point of the celestial projection, if there is one.
     *
     * <p>Then {@link #addEncodingFrame} may add a Frame that suits the encoding, which replaces the
     * world Frame.
     *
     * @return the FrameSet, or null if the world Frame holds axes of two SkyFrames or of two
     *     SpecFrames, or the celestial axes of a SpecFrame's reference position cannot be found
     */
    // C compares the pointers astPrimaryFrame returns: whether two axes are in the same SkyFrame.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static FrameSet makeFitsFrameSet(
            FitsChan fc, FrameSet fset, int ipix, int iwcs, FitsChan.Encoding enc, String method, String cls) {
        Frame pixfrm = (Frame) fset.getFrame(ipix).copy();
        Frame wcsfrm = (Frame) fset.getFrame(iwcs).copy();
        Mapping map = (Mapping) fset.getMapping(ipix, iwcs).copy();
        int npix = pixfrm.getNaxes();
        int nwcs = wcsfrm.getNaxes();

        // The SpecFrame and SkyFrame of the world Frame, each of which must be its only one, and
        // the Mapping from the internal units of each other axis to its Unit.
        Mapping remap = null;
        SpecFrame specfrm = null;
        Frame skyfrm = null;
        boolean ok = true;
        int ilat = -1;
        int ilon = -1;
        int[] paxis = new int[1];
        for (int iax = 0; iax < nwcs; iax++) {
            Frame pframe = wcsfrm.primaryFrame(iax + 1, paxis);
            Mapping umap = null;
            if (pframe instanceof SpecFrame) {
                if (specfrm != null) {
                    ok = false;
                    break;
                }
                specfrm = (SpecFrame) pframe;
            } else if (FitsMakeIntWorld.isASkyFrame(pframe)) {
                if (skyfrm != null) {
                    if (pframe != skyfrm) {
                        ok = false;
                        break;
                    }
                } else {
                    skyfrm = pframe;
                }
                if (paxis[0] == 1) {
                    ilon = iax;
                } else {
                    ilat = iax;
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

        // Two SkyFrames or two SpecFrames make the FrameSet unusable. C then passes a NULL
        // FrameSet to AddEncodingFrame, which dereferences it; the FrameSet that is not made is
        // reported here as C's caller expects.
        if (!ok) {
            return null;
        }
        FrameSet ret;
        if (specfrm == null || !specfrm.testRefRA() || !specfrm.testRefDec() || (nwcs > 2 && skyfrm == null)) {
            ret = new FrameSet(pixfrm);
            ret.addFrame(FrameSet.AST__BASE, map, wcsfrm);
        } else if (skyfrm == null) {
            ret = addReferenceSkyFrame(pixfrm, wcsfrm, map, specfrm, npix);
        } else {
            ret = setReferencePosition(pixfrm, wcsfrm, map, ilon, ilat);
        }
        // C passes a NULL FrameSet to AddEncodingFrame, which dereferences it.
        if (ret == null) {
            return null;
        }

        int icurr = addEncodingFrame(ret, enc);
        if (icurr != FrameSet.AST__NOFRAME) {
            ret.removeFrame(icurr);
        }
        return ret;
    }

    /**
     * The FrameSet {@link #makeFitsFrameSet} makes for a SpecFrame with a celestial reference
     * position and a world Frame with no SkyFrame: the world Frame and an FK5 J2000 SkyFrame,
     * whose axes come from a TAN projection with the reference position at pixel (0, 0), the
     * constant 0 being given to those two pixel axes. The projection is that of a FITS-WCS header,
     * with an MJD-OBS (UTC) of the SpecFrame's Epoch, read by a FitsChan.
     *
     * @return the FrameSet, or null if the header gives none
     */
    private static FrameSet addReferenceSkyFrame(Frame pixfrm, Frame wcsfrm, Mapping map, SpecFrame specfrm, int npix) {
        FitsChan fc = new FitsChan();
        fc.putFits("CRPIX1  = 0", false);
        fc.putFits("CRPIX2  = 0", false);
        fc.putFits("CDELT1  = 0.0003", false);
        fc.putFits("CDELT2  = 0.0003", false);
        fc.putFits("CTYPE1  = 'RA---TAN'", false);
        fc.putFits("CTYPE2  = 'DEC--TAN'", false);
        fc.putFits("RADESYS = 'FK5'", false);
        fc.putFits("EQUINOX = 2000.0", false);
        fc.putFits("CRVAL1  = " + CFormat.format("%." + AST__DBL_DIG + "g", AST__DR2D * specfrm.refRaValue()), false);
        fc.putFits("CRVAL2  = " + CFormat.format("%." + AST__DBL_DIG + "g", AST__DR2D * specfrm.refDecValue()), false);
        fc.putFits(
                "MJD-OBS = "
                        + CFormat.format(
                                "%." + AST__DBL_DIG + "g",
                                FitsEpoch.tdbConv(specfrm.getEpochMjd(), TimeFrame.AST__UTC, true)),
                false);
        fc.cards().clearCard();
        AstObject obj = fc.read();
        if (!(obj instanceof FrameSet)) {
            return null;
        }
        FrameSet tfs = (FrameSet) obj;

        // The pixel to world Mapping in parallel with the pixel to sky Mapping, the two sky pixel
        // axes being given the constant 0.0, the CRPIX value of the header.
        Mapping tmap0 = tfs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping tmap1 = new CmpMap(map, tmap0, false);
        int[] perm = new int[npix + 2];
        for (int iax = 0; iax < npix; iax++) {
            perm[iax] = iax;
        }
        perm[npix] = -1;
        perm[npix + 1] = -1;
        Mapping pm = PermMap.fromInternal(npix, perm, npix + 2, perm, new double[] {0.0});
        Mapping tmap2 = new CmpMap(pm, tmap1, true);

        Frame tfrm0 = new CmpFrame(wcsfrm, tfs.getFrame(FrameSet.AST__CURRENT));
        FrameSet ret = new FrameSet(pixfrm);
        ret.addFrame(FrameSet.AST__BASE, tmap2, tfrm0);
        return ret;
    }

    /**
     * The FrameSet {@link #makeFitsFrameSet} makes for a SpecFrame with a celestial reference
     * position and a world Frame with a SkyFrame: the world Frame, with the SpecFrame's RefRA and
     * RefDec set through the FrameSet to the FK5 J2000 position of the fiducial point of the
     * celestial projection, formatted with 20 digits. The SkyFrame's System and Equinox are set to
     * FK5 and J2000 meanwhile, and set back if they were set. With no projection, the reference
     * position is kept.
     *
     * @return the FrameSet, or null if the fiducial point is not found
     */
    private static FrameSet setReferencePosition(Frame pixfrm, Frame wcsfrm, Mapping map, int ilon, int ilat) {
        FrameSet tfs = new FrameSet(pixfrm);
        tfs.addFrame(FrameSet.AST__BASE, map, wcsfrm);

        String systemAttr = "System(" + (ilon + 1) + ")";
        String skysys = tfs.test(systemAttr) ? tfs.getC(systemAttr) : null;
        tfs.setC(systemAttr, "FK5");
        String equinoxAttr = "Equinox(" + (ilon + 1) + ")";
        String eqn = tfs.test(equinoxAttr) ? tfs.getC(equinoxAttr) : null;
        tfs.setC(equinoxAttr, "J2000");

        FrameSet ret = null;
        Mapping tmap0 = tfs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        FitsCelestialAxes.Split split = FitsCelestialAxes.splitMap(tmap0, tmap0.getInvert(), ilon, ilat);
        if (split != null) {
            double[] fid = FitsFiducial.getFiducialWcs(split.map2, split.map3, ilon, ilat);
            if (fid != null) {
                // The Digits of the latitude and longitude axes are 20 while the position is
                // formatted, so that no precision is lost, and then set back or cleared.
                int[] digs = new int[2];
                for (int iax = 0; iax < 2; iax++) {
                    String digitsAttr = "Digits(" + ((iax != 0 ? ilon : ilat) + 1) + ")";
                    digs[iax] = tfs.test(digitsAttr) ? tfs.getI(digitsAttr) : -1;
                    tfs.setI(digitsAttr, 20);
                }
                tfs.setC("RefRA", tfs.format(ilon + 1, fid[0]));
                tfs.setC("RefDec", tfs.format(ilat + 1, fid[1]));
                for (int iax = 0; iax < 2; iax++) {
                    String digitsAttr = "Digits(" + ((iax != 0 ? ilon : ilat) + 1) + ")";
                    if (digs[iax] == -1) {
                        tfs.clear(digitsAttr);
                    } else {
                        tfs.setI(digitsAttr, digs[iax]);
                    }
                }
                ret = tfs;
            }
        } else {
            ret = tfs;
        }

        if (skysys != null) {
            tfs.setC(systemAttr, skysys);
        }
        if (eqn != null) {
            tfs.setC(equinoxAttr, eqn);
        }
        return ret;
    }

    /**
     * C's {@code AddEncodingFrame}: for FITS-CLASS, a Frame added to {@code fs} as its current
     * Frame, if a SpecFrame and a SkyFrame of the current Frame (the first of each) can be
     * converted to it: a CmpFrame of a copy of the SpecFrame, in FREQ with Unit Hz and the Source
     * standard of rest, and a copy of the SkyFrame, in FK4 (equinox B1950), FK5 (equinox J2000)
     * or galactic coordinates (FK5 for any other system); the Frame added is the one the
     * conversion gives.
     *
     * @return the index of the original current Frame if a Frame was added, otherwise {@link
     *     FrameSet#AST__NOFRAME}
     */
    static int addEncodingFrame(FrameSet fs, FitsChan.Encoding enc) {
        int result = FrameSet.AST__NOFRAME;
        Frame cfrm = fs.getFrame(FrameSet.AST__CURRENT);
        int naxc = cfrm.getNaxes();
        if (enc == FitsChan.Encoding.FITS_CLASS) {
            SpecFrame specfrm = null;
            SkyFrame skyfrm = null;
            int[] paxis = new int[1];
            for (int i = 0; i < naxc; i++) {
                Frame pfrm = cfrm.primaryFrame(i + 1, paxis);
                if (pfrm instanceof SpecFrame) {
                    if (specfrm == null) {
                        specfrm = (SpecFrame) pfrm.copy();
                    }
                } else if (skyfrm == null && FitsMakeIntWorld.isASkyFrame(pfrm)) {
                    skyfrm = (SkyFrame) pfrm.copy();
                }
            }

            if (specfrm != null && skyfrm != null) {
                if (specfrm.systemValue() != SpecFrame.AST__FREQ) {
                    specfrm.setSystemValue(SpecFrame.AST__FREQ);
                }
                specfrm.setUnit(1, "Hz");
                specfrm.setStdOfRestValue(SpecFrame.AST__SCSOR);

                int sys = skyfrm.systemValue();
                if (sys != SkyFrame.AST__FK4 && sys != SkyFrame.AST__FK5 && sys != SkyFrame.AST__GALACTIC) {
                    skyfrm.setSystemValue(SkyFrame.AST__FK5);
                    sys = SkyFrame.AST__FK5;
                }
                if (sys == SkyFrame.AST__FK5) {
                    skyfrm.setC("Equinox", "J2000.0");
                } else if (sys == SkyFrame.AST__FK4) {
                    skyfrm.setC("Equinox", "B1950.0");
                }

                CmpFrame cmpfrm = new CmpFrame(specfrm, skyfrm);
                FrameSet fsconv = cfrm.convert(cmpfrm, "");
                if (fsconv != null) {
                    Frame newfrm = fsconv.getFrame(FrameSet.AST__CURRENT);
                    Mapping map = fsconv.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                    result = fs.getCurrent();
                    fs.addFrame(FrameSet.AST__CURRENT, map, newfrm);
                }
            }
        }
        return result;
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
}
