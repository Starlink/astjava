package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * C's {@code SpectralAxes} (fitschan.c): the FITS-WCS paper III description of the spectral axes
 * of the current Frame of a FrameSet, with C's {@code GetFitsSor}.
 *
 * <p>Each axis of a SpecFrame is described, in order of preference, with the linear algorithm (the
 * pixel coordinates are linear in the SpecFrame's System), the non-linear {@code -X2P} algorithm
 * (linear in FREQ, WAVE, AWAV or VELO, tried in that order), the {@code -LOG} algorithm, the grism
 * algorithm ({@code -GRI} or {@code -GRA}, for a Mapping ending in a GrismMap), or, when TabOK is
 * positive, the {@code -TAB} algorithm; ForceTab passes over all but the last. An axis none of
 * them describes is left to {@link FitsOtherAxes#otherAxes}.
 *
 * <p>C tries the {@code -X2P} and grism algorithms by setting the System and Unit of the axis
 * through the FrameSet, which remaps the current Frame, and setting them back, so the FrameSet is
 * remapped as C remaps it. The {@code -TAB} description holds a FitsTable, which this library
 * does not have; where C makes one, an {@link UnsupportedOperationException} is thrown. C's grism
 * algorithm extracts a GrismMap from the pixel to wavelength Mapping; a FrameSet of this
 * library's classes holds none, so it finds none, as C finds none in such a FrameSet.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/spectral_axes.rs}, following fitschan.c, whose
 * structure is kept where Rust's follows from its ownership rules: the FrameSet is changed in place
 * as C changes it, and the SpecFrame read is the one in the FrameSet.
 */
final class FitsSpectralAxes {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** The speed of light in m/s, C's {@code AST__C}. */
    private static final double AST__C = 2.99792458E8;

    /** The systems to one of which every supported spectral system is linearly related. */
    private static final String[] X_SYS = {"FREQ", "WAVE", "AWAV", "VELO"};

    private FitsSpectralAxes() {}

    /**
     * C's {@code SpectralAxes}: stores CTYPE, CRVAL, CUNIT, CNAME and the other spectral keywords
     * (OBSGEO for the primary description, RESTFRQ or RESTWAV, IMAGFREQ, SPECSYS, ZSOURCE, SSYSSRC
     * and VELOSYS) for each spectral axis of the current Frame of {@code fs} that one of the
     * algorithms describes, flags it in {@code axisDone}, and returns the Mapping from the
     * current Frame to intermediate world coordinates for those axes, which passes the other axes
     * unchanged.
     *
     * <p>A spectral Unit not linearly related to the default Unit of the System over the spectral
     * range of the pixel grid is first cleared through the FrameSet, which remaps the current
     * Frame, and the reference value converted to the default Unit. The reference value is the
     * element of {@code crvals}; or, when that is bad, the middle of the spectral range when the
     * size of the one pixel axis feeding the spectral axis is known, and the value at pixel 1
     * otherwise; moved to the nearest whole pixel. For the {@code -X2P} algorithm the intermediate
     * world coordinate is the X value less its reference value, scaled to change at the rate of
     * the SpecFrame's value at the reference point.
     *
     * @param fc the FitsChan, whose TabOK and ForceTab attributes are used
     * @param fs the FrameSet, whose base Frame is pixel coordinates and current Frame world
     *     coordinates; the System and Unit of its spectral axes are changed and set back, and
     *     SourceVRF and SourceVel of its SpecFrames set, as C does
     * @param dim the number of pixels along each pixel axis, {@code AST__BAD} where unknown
     * @param wperm the zero-based FITS axis describing each current Frame axis
     * @param s the coordinate version character
     * @param store the store
     * @param crvals the reference value of each current Frame axis, {@code AST__BAD} where there
     *     is none, or null for none at all
     * @param axisDone a flag for each current Frame axis, set for each axis described
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return the Mapping from the current Frame to intermediate world coordinates
     * @throws UnsupportedOperationException where C describes an axis with the {@code -TAB}
     *     algorithm, which needs FitsTable
     */
    static Mapping spectralAxes(
            FitsChan fc,
            FrameSet fs,
            double[] dim,
            int[] wperm,
            char s,
            FitsStore store,
            double[] crvals,
            boolean[] axisDone,
            String method,
            String cls) {
        Mapping ret = null;

        Frame wcsfrm = fs.getFrame(FrameSet.AST__CURRENT);
        int npix = fs.getNin();
        int nwcs = fs.getNout();

        double[] lbndP = new double[npix];
        double[] ubndP = new double[npix];
        for (int iax = 0; iax < npix; iax++) {
            lbndP[iax] = 1.0;
            ubndP[iax] = dim[iax] != AST__BAD ? dim[iax] : 500;
        }

        int[] paxis = new int[1];
        for (int iax = 0; iax < nwcs; iax++) {
            Frame pframe = wcsfrm.primaryFrame(iax + 1, paxis);
            if (!(pframe instanceof SpecFrame)) {
                continue;
            }
            SpecFrame specfrm = (SpecFrame) pframe;
            int fitsI = wperm[iax];

            String systemAttr = "System(" + (iax + 1) + ")";
            String origSystem = wcsfrm.getC(systemAttr);
            String unitAttr = "Unit(" + (iax + 1) + ")";

            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);

            // The pixel axis that feeds the spectral axis, if only one does. The Mapping is
            // inverted so that any WCS axes with no inverse do not stop it being split.
            int pax = -1;
            MapSplit split;
            map.invert();
            try {
                split = map.mapSplit(new int[] {iax});
            } finally {
                map.invert();
            }
            if (split != null && split.map.getNout() == 1) {
                pax = split.outputs[0];
            }

            // The bounds of the spectral axis over the pixel grid.
            double[] bnds = MapBox.mapBox(map, lbndP, ubndP, true, iax, null, null);
            double lbndS = bnds[0];
            double ubndS = bnds[1];

            // FITS-WCS paper III needs CRVAL and so on in linear units: if the conversion from the
            // Unit to the default Unit is not linear, the Unit is cleared through the FrameSet, so
            // that the SpecFrame is remapped.
            SpecFrame tfrm = (SpecFrame) specfrm.copy();
            tfrm.clearUnit(1);
            FrameSet tfs = specfrm.convert(tfrm, "");
            if (tfs == null) {
                continue;
            }
            double crval = crvals != null ? crvals[iax] : AST__BAD;
            Mapping tmap1 = tfs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            if (!FitsIsMapLinear.isMapLinear(tmap1, new double[] {lbndS}, new double[] {ubndS}, 0, null)) {
                fs.clear(unitAttr);
                map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                bnds = MapBox.mapBox(map, lbndP, ubndP, true, iax, null, null);
                lbndS = bnds[0];
                ubndS = bnds[1];
                crval = tmap1.tran1(1, new double[] {crval}, true)[0];
            }

            // The linear spectral Unit now in use.
            String linUnit = specfrm.getUnit(1);

            // The default reference value: the middle of the spectral range if the size of the
            // spectral pixel axis is known, otherwise its value at pixel 1; at a whole pixel.
            if (crval == AST__BAD) {
                if (pax >= 0 && dim[pax] != AST__BAD) {
                    crval = 0.5 * (lbndS + ubndS);
                } else {
                    crval = lbndS;
                }
            }
            crval = FitsMakeIntWorld.nearestPix(map, crval, iax);

            String ctype = null;
            Mapping axmap = null;
            if (fc.getForceTab() == 0) {
                // The linear algorithm.
                if (FitsIsMapLinear.isMapLinear(map, lbndP, ubndP, iax, null)) {
                    ctype = origSystem;
                    axmap = FitsOtherAxes.addUnitMaps(new ShiftMap(new double[] {-crval}), iax, nwcs);
                }

                // The non-linear algorithm: the pixel coordinates are linear in another system
                // X, set on the FrameSet with its default Unit.
                for (int ix = 0; ix < X_SYS.length && ctype == null; ix++) {
                    fs.setC(systemAttr, X_SYS[ix]);
                    fs.clear(unitAttr);
                    Mapping tmap3 = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                    if (FitsIsMapLinear.isMapLinear(tmap3, lbndP, ubndP, iax, null)) {
                        ctype = withCode(origSystem, x2pCode(X_SYS[ix], origSystem));

                        // The Mapping giving S as a function of X; its inverse gives the X value
                        // at the reference value, and its rate there dS/dX.
                        Frame xfrm = (Frame) specfrm.copy();
                        xfrm.setC("System(1)", origSystem);
                        xfrm.setC("Unit(1)", linUnit);
                        FrameSet xfs = specfrm.convert(xfrm, "");
                        Mapping tmap5 = xfs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                        double xval = tmap5.tran1(1, new double[] {crval}, false)[0];
                        double dsbydx = tmap5.rate(new double[] {xval}, 1, 1);

                        // From S to X, less the X reference value, scaled so that S changes at
                        // unit rate with the intermediate world coordinate.
                        Mapping tmap2 = new ShiftMap(new double[] {-xval});
                        tmap5.invert();
                        Mapping tmap0 = new CmpMap(tmap5, tmap2, true);
                        Mapping tmapz = new ZoomMap(1, dsbydx);
                        axmap = FitsOtherAxes.addUnitMaps(new CmpMap(tmap0, tmapz, true), iax, nwcs);
                    }

                    fs.setC(systemAttr, origSystem);
                    fs.setC(unitAttr, linUnit);
                }

                // The log-linear algorithm.
                if (ctype == null) {
                    axmap = FitsOtherAxes.logAxis(map, iax, nwcs, lbndP, ubndP, crval);
                    if (axmap != null) {
                        ctype = withCode(origSystem, "-LOG");
                    }
                }

                // The grism algorithm, tried with the axis as vacuum and as air wavelength in m.
                // C looks for a GrismMap producing the axis in the simplified pixel to wavelength
                // Mapping (ExtractGrismMap); a FrameSet of this library's classes holds none.
                for (int ix = 0; ix < 2 && ctype == null; ix++) {
                    fs.setC(systemAttr, ix == 0 ? "WAVE" : "AWAV");
                    fs.setC(unitAttr, "m");
                    fs.setC(systemAttr, origSystem);
                    fs.setC(unitAttr, linUnit);
                }
            }

            // The -TAB algorithm, when TabOK is positive.
            int extver = fc.getTabOK();
            if (ctype == null && extver > 0) {
                Mapping tmap0 = FitsMapTab.isMapTab1D(
                        map, 1.0, null, wcsfrm, dim, iax, fitsI, new int[1], new int[1], new int[1]);
                if (tmap0 != null) {
                    // C stores the table version in the header of the FitsTable holding the
                    // table, and the column names.
                    throw new UnsupportedOperationException(
                            "SpectralAxes(FitsChan): the -TAB description of FITS-WCS axis " + (fitsI + 1)
                                    + " needs FitsTable");
                }
            }

            if (ctype != null) {
                ret = ret != null ? new CmpMap(ret, axmap, true) : axmap;

                store.ctype[fitsI] = ctype;
                // C's bad value is an item not set.
                store.crval[fitsI] = crval != AST__BAD ? crval : null;
                store.cunit[fitsI] = linUnit;

                // A set Label is the CTYPE comment and the CNAME.
                if (specfrm.testLabel(1)) {
                    String cname = specfrm.getLabel(1);
                    store.ctypeCom[fitsI] = cname;
                    store.cname[fitsI] = cname;
                }

                // The other keywords, from attributes that are set, differ from the defaults of
                // FITS-WCS paper III, and are good.
                if (specfrm.testObsLon() && specfrm.testObsLat() && s == ' ') {
                    double geolon = specfrm.getObsLonValue();
                    double geolat = specfrm.getObsLatValue();
                    double h = specfrm.getObsAlt();
                    if (geolat != AST__BAD && geolon != AST__BAD && h != AST__BAD) {
                        double[] xyz = Pal.gd2gc(geolon, geolat, h);
                        store.obsgeox = xyz[0];
                        store.obsgeoy = xyz[1];
                        store.obsgeoz = xyz[2];
                    }
                }
                if (specfrm.testRestFreq()) {
                    double restfreq = specfrm.restFreqValue();
                    if (restfreq != AST__BAD) {
                        if ("WAVE".equals(origSystem)
                                || "VOPT".equals(origSystem)
                                || "ZOPT".equals(origSystem)
                                || "AWAV".equals(origSystem)) {
                            store.restwav = AST__C / restfreq;
                        } else {
                            store.restfrq = restfreq;
                        }
                    }
                    if (specfrm instanceof DSBSpecFrame) {
                        double imagfreq = ((DSBSpecFrame) specfrm).imagFreqValue();
                        if (imagfreq != AST__BAD) {
                            store.imagfreq = imagfreq;
                        }
                    }
                }
                String cval = getFitsSor(specfrm.getC("StdOfRest"));
                if (cval != null) {
                    store.specsys = cval;
                }
                double vsource;
                if (specfrm.testSourceVel()) {
                    vsource = specfrm.sourceVelValue();
                    if (vsource != AST__BAD && Math.abs(vsource) < AST__C) {
                        store.zsource = Math.sqrt((AST__C + vsource) / (AST__C - vsource)) - 1.0;
                        cval = getFitsSor(specfrm.getC("SourceVRF"));
                        if (cval != null) {
                            store.ssyssrc = cval;
                        }
                    }
                } else {
                    vsource = AST__BAD;
                }

                // VELOSYS: the source velocity is made zero in the standard of rest (unless that
                // is the source's), and is then given in the topocentric rest frame, which is the
                // velocity of the standard of rest; the source velocity is set back if it was set.
                int sourcevrf;
                if (specfrm.stdOfRestValue() != SpecFrame.AST__SCSOR) {
                    sourcevrf = specfrm.sourceVrfValue();
                    specfrm.setSourceVrfValue(specfrm.stdOfRestValue());
                    specfrm.setSourceVelValue(0.0);
                } else {
                    vsource = AST__BAD;
                    sourcevrf = SpecFrame.AST__BADSOR;
                }
                specfrm.setSourceVrfValue(SpecFrame.AST__TPSOR);
                double velosys = specfrm.sourceVelValue();
                if (velosys != AST__BAD) {
                    store.velosys = velosys;
                }
                if (vsource != AST__BAD) {
                    specfrm.setSourceVrfValue(sourcevrf);
                    specfrm.setSourceVelValue(vsource);
                }

                axisDone[iax] = true;
            }
        }

        return ret != null ? ret.simplify() : new UnitMap(nwcs);
    }

    /**
     * The CTYPE C makes for an algorithm: {@code code} in place of everything from the fifth
     * character of the System {@code system} on, as C's {@code strcpy( ctype + 4, code )} does
     * (every spectral System has four characters).
     */
    private static String withCode(String system, String code) {
        return system.substring(0, Math.min(4, system.length())) + code;
    }

    /**
     * The code C appends to the four characters of the System for the {@code -X2P} algorithm:
     * {@code -X2P}, X naming the system {@code xsys} the pixel coordinates are linear in and P the
     * basic system to which {@code origSystem} is linearly related.
     */
    private static String x2pCode(String xsys, String origSystem) {
        String x;
        if ("FREQ".equals(xsys)) {
            x = "-F2";
        } else if ("WAVE".equals(xsys)) {
            x = "-W2";
        } else if ("AWAV".equals(xsys)) {
            x = "-A2";
        } else {
            x = "-V2";
        }
        String p;
        if ("FREQ".equals(origSystem)
                || "ENER".equals(origSystem)
                || "WAVN".equals(origSystem)
                || "VRAD".equals(origSystem)) {
            p = "F";
        } else if ("WAVE".equals(origSystem) || "VOPT".equals(origSystem) || "ZOPT".equals(origSystem)) {
            p = "W";
        } else if ("AWAV".equals(origSystem)) {
            p = "A";
        } else {
            p = "V";
        }
        return x + p;
    }

    /**
     * C's {@code GetFitsSor}: the FITS SPECSYS value of the SpecFrame standard of rest {@code
     * string}, or null if FITS has none.
     */
    static String getFitsSor(String string) {
        // MODERN-JAVA(21): switch expression
        switch (string) {
            case "Topocentric":
                return "TOPOCENT";
            case "Geocentric":
                return "GEOCENTR";
            case "Barycentric":
                return "BARYCENT";
            case "Heliocentric":
                return "HELIOCEN";
            case "LSRK":
                return "LSRK";
            case "LSRD":
                return "LSRD";
            case "Galactic":
                return "GALACTOC";
            case "Local_group":
                return "LOCALGRP";
            case "Source":
                return "SOURCE";
            default:
                // C's GetFitsSor returns NULL for any other string.
                return null;
        }
    }
}
