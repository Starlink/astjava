package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The spectral axes of a FITS-WCS axis description: C's {@code WcsSpectral}, which describes each
 * axis whose CTYPE names a spectral system of FITS-WCS paper III with a SpecFrame (a DSBSpecFrame
 * when the header gives IMAGFREQ) and makes the Mapping from its intermediate world coordinate to
 * that system, leaving the other axes unchanged.
 *
 * <p>The SpecFrame takes its System from the CTYPE, its reference position from the celestial
 * axes, its Unit from CUNIT, its Epoch from the date of observation, its rest frequency from
 * RESTFRQ or RESTWAV, the observer's position from OBSGEO-X/Y/Z, the source velocity from SSYSSRC
 * and ZSOURCE, its standard of rest from SPECSYS and its Label from CNAME, in C's order. The
 * Mapping depends on the algorithm code: a linear axis adds CRVAL, a {@code -X2P} axis (C's {@code
 * NonLinSpecWcs}) is linear in the system X and converted to the axis's own system, a {@code
 * -LOG} axis is exponential in the intermediate world coordinate (C's {@code LogWcs}), and the
 * {@code -GRI}/{@code -GRA} axes need GrismMap, which is not available.
 *
 * <p>Ported from Rust {@code fitschan/wcs_spectral.rs}, following C's {@code WcsSpectral}, {@code
 * NonLinSpecWcs}, {@code GrismSpecWcs}, {@code LogWcs} and {@code DSBSetUp} (fitschan.c) where
 * the two differ.
 */
final class FitsWcsSpectral {

    /** The method C's read reports errors from. */
    private static final String METHOD = "astRead";

    /** The class C's read reports errors for. */
    private static final String CLASS = "FitsChan";

    /** {@code AST__DBL_DIG}, the significant digits with which C writes a constant into a function. */
    private static final int DBL_DIG = 17;

    private FitsWcsSpectral() {}

    /** What {@link #wcsSpectral} makes: the Mapping over every axis, and the Frame describing them. */
    static final class Result {
        /** The Mapping from intermediate world coordinates, one Mapping per axis in parallel. */
        final Mapping map;

        /** The Frame, with a SpecFrame for each spectral axis. */
        final Frame frame;

        Result(Mapping map, Frame frame) {
            this.map = map;
            this.frame = frame;
        }
    }

    /**
     * Whether each axis of {@code store} is one {@link #wcsSpectral} describes: its CTYPE names a
     * spectral system, as C's {@code IsSpectral} decides.
     */
    static boolean[] spectralAxes(FitsStore store) {
        boolean[] spectral = new boolean[store.naxis];
        for (int i = 0; i < store.naxis; i++) {
            spectral[i] = store.ctype[i] != null && FitsStore.isSpectral(store.ctype[i]) != null;
        }
        return spectral;
    }

    /**
     * C's {@code WcsSpectral} (fitschan.c:39913) for axis description {@code s} of {@code store}.
     * For each spectral axis in turn, the SpecFrame describing it replaces that axis of {@code
     * frm}: the other axes are picked from the Frame, joined with the SpecFrame in a CmpFrame, and
     * permuted to put the spectral axis back in place (the SpecFrame alone replaces a Frame with no
     * other axes). Each spectral axis's element of {@code iwcUnits}, the units of the IWC Frame, is
     * set to its unit.
     *
     * @param frm the Frame describing every axis, as {@code WcsCelestial} leaves it
     * @param celestial the celestial axes, whose SkyFrame and reference position give the
     *     SpecFrames their reference position, or null when there are none
     * @param warnings receives the warnings raised
     * @return the Mapping, one Mapping per axis in parallel, a UnitMap for each axis that is not
     *     spectral, and the Frame
     * @throws AstException with status {@code AST__BDFTS} for an algorithm code that cannot be
     *     implemented, as C reports it, or as the attribute settings report a bad keyword value
     * @throws UnsupportedOperationException for a {@code -GRI} or {@code -GRA} axis, whose Mapping
     *     needs GrismMap
     */
    static Result wcsSpectral(
            FitsStore store,
            char s,
            Frame frm,
            String[] iwcUnits,
            FitsWcsCelestial.CelestialResult celestial,
            FitsWarnings warnings) {
        int naxes = frm.getNaxes();
        int[] axes = new int[naxes];
        Mapping ret = null;
        Frame frame = frm;
        for (int i = 0; i < naxes; i++) {
            Mapping map1 = null;
            String ctype = store.ctype[i];
            String defunit = ctype != null ? FitsStore.isSpectral(ctype) : null;
            if (defunit != null) {
                SpecFrame specfrm = specFrame(store, i, ctype, defunit, iwcUnits, celestial, warnings);
                String algcode = algorithmCode(ctype);
                if (algcode.isEmpty()) {
                    map1 = linearWcs(store, i);
                } else if ("-LOG".equals(algcode)) {
                    map1 = logWcs(store, i);
                } else if (algcode.charAt(0) == '-' && algcode.length() > 2 && algcode.charAt(2) == '2') {
                    map1 = nonLinSpecWcs(algcode, store, i, specfrm, warnings);
                } else if ("-GRI".equals(algcode) || "-GRA".equals(algcode)) {
                    map1 = grismSpecWcs(algcode, i, specfrm, ctype);
                } else {
                    // An algorithm code C does not know, which it reports below.
                    map1 = null;
                }
                if (map1 == null) {
                    throw Ast.err(
                            AstException.AST__BDFTS,
                            METHOD + "(" + CLASS + "): Cannot implement spectral algorithm code '"
                                    + ctype.substring(4) + "' specified in FITS keyword '"
                                    + FitsWcsKeywords.keyFor("CTYPE", i + 1, s) + "'.\n" + METHOD + "(" + CLASS
                                    + "): Unknown algorithm code or unusable parameter values.");
                }
                frame = replaceAxis(frame, i, specfrm, axes);
            }
            if (map1 == null) {
                map1 = new UnitMap(1);
            }
            ret = ret == null ? map1 : new CmpMap(ret, map1, false);
        }
        return new Result(ret, frame);
    }

    /**
     * The Frame {@code frm} with axis {@code i} (from 0) replaced by {@code specfrm}: the other
     * axes picked into a Frame and joined with the SpecFrame in a CmpFrame, or the SpecFrame alone
     * when there are no other axes, then permuted to put the spectral axis back in place
     * (fitschan.c:40171-40206). {@code axes} is workspace with an element for each axis.
     */
    private static Frame replaceAxis(Frame frm, int i, SpecFrame specfrm, int[] axes) {
        int naxes = frm.getNaxes();
        int j = 0;
        for (int k = 0; k < naxes; k++) {
            if (k != i) {
                axes[j++] = k + 1;
            }
        }
        Frame result;
        if (j == 0) {
            result = specfrm;
        } else {
            Frame ofrm = frm.pickAxes(j, axes, null);
            result = new CmpFrame(ofrm, specfrm);
        }
        j = 0;
        for (int kk = 0; kk < naxes; kk++) {
            axes[kk] = kk == i ? naxes : ++j;
        }
        result.permAxes(axes);
        return result;
    }

    /**
     * The SpecFrame (or DSBSpecFrame) of spectral axis {@code i} (from 0), set up as C's {@code
     * WcsSpectral} sets it up before choosing the Mapping (fitschan.c:40043-40136), in C's order.
     * The axis's element of {@code iwcUnits} is set to its unit.
     */
    private static SpecFrame specFrame(
            FitsStore store,
            int i,
            String ctype,
            String defunit,
            String[] iwcUnits,
            FitsWcsCelestial.CelestialResult celestial,
            FitsWarnings warnings) {
        // A SpecFrame, or a DSBSpecFrame when there is an IMAGFREQ. C sets the System from the
        // first four characters of the CTYPE, without trailing spaces, which are also legal
        // System values.
        SpecFrame specfrm = store.imagfreq == null ? new SpecFrame() : new DSBSpecFrame();
        specfrm.setC("System", systemType(ctype));

        // The reference position, if there are celestial axes.
        if (celestial != null) {
            specfrm.setRefPos(celestial.frame, celestial.refLon, celestial.refLat);
        }

        // The Unit: CUNIT, or the default unit of the system; also the Unit of the IWC axis.
        String cunit = store.cunit[i] != null ? store.cunit[i] : defunit;
        specfrm.setUnit(1, cunit);
        iwcUnits[i] = cunit;

        // The date of observation.
        Double mjd = FitsEpoch.chooseEpoch(store, warnings);
        if (mjd != null && mjd != AstObject.AST__BAD) {
            specfrm.setEpochMjd(mjd);
        }

        // The rest frequency, from RESTFRQ (Hz) or else RESTWAV (m); C sets the bad value when there
        // is neither, which leaves the RestFreq unset.
        double restfrq;
        if (store.restfrq != null) {
            restfrq = store.restfrq;
        } else if (store.restwav != null) {
            restfrq = SpecMap.AST__C / store.restwav;
        } else {
            restfrq = AstObject.AST__BAD;
        }
        specfrm.setRestFreqValue(restfrq);

        // The observer's position, from the primary axis description's OBSGEO-X/Y/Z.
        if (store.obsgeox != null && store.obsgeoy != null && store.obsgeoz != null) {
            double[] geo = Pal.gc2gd(new double[] {store.obsgeox, store.obsgeoy, store.obsgeoz});
            if (geo != null) {
                specfrm.setObsLatValue(geo[1]);
                specfrm.setObsLonValue(geo[0]);
                specfrm.setObsAlt(geo[2]);
            }
        }

        // The rest frame of the source velocity, and the source velocity from the redshift
        // ZSOURCE (relativistic).
        if (store.ssyssrc != null) {
            specfrm.setC("SourceVRF", store.ssyssrc);
        }
        if (store.zsource != null) {
            double vsource = store.zsource + 1.0;
            vsource *= vsource;
            vsource = SpecMap.AST__C * (vsource - 1.0) / (vsource + 1.0);
            specfrm.setSourceVelValue(vsource);
        }

        // The standard of rest; the FITS codes are also legal StdOfRest values.
        if (store.specsys != null) {
            specfrm.setC("StdOfRest", store.specsys);
        }

        // The axis Label, from CNAME.
        if (store.cname[i] != null) {
            specfrm.setLabel(1, store.cname[i]);
        }

        // The observation centre: AXREF, which a write gives a -TAB axis with no inverse, in
        // preference to CRVAL.
        Double obscentre = store.axref[i] != null ? store.axref[i] : store.crval[i];
        if (specfrm instanceof DSBSpecFrame) {
            dsbSetUp(store, (DSBSpecFrame) specfrm, obscentre != null ? obscentre : AstObject.AST__BAD);
        }
        return specfrm;
    }

    /**
     * The spectral system code of a CTYPE, its first four characters without trailing spaces, as
     * C's {@code IsSpectral} extracts it.
     */
    private static String systemType(String ctype) {
        String stype = ctype.substring(0, 4);
        int n = stype.length();
        while (n > 0 && stype.charAt(n - 1) == ' ') {
            n--;
        }
        return stype.substring(0, n);
    }

    /**
     * The algorithm code of a CTYPE, as C's {@code IsSpectral} extracts it: at most four
     * characters after the first four, without trailing spaces.
     */
    static String algorithmCode(String ctype) {
        if (ctype.length() <= 4) {
            return "";
        }
        String rest = ctype.substring(4);
        if (rest.length() > 4) {
            rest = rest.substring(0, 4);
        }
        int n = rest.length();
        while (n > 0 && rest.charAt(n - 1) == ' ') {
            n--;
        }
        return rest.substring(0, n);
    }

    /**
     * C's {@code DSBSetUp} (fitschan.c:9177): sets the DSBCentre of {@code dsb} to {@code crval},
     * its IF to the difference between the topocentric local oscillator frequency, half way
     * between the topocentric rest and image frequencies, and the topocentric DSBCentre, and its
     * SideBand to the observed sideband. The RestFreq and IMAGFREQ are frequencies in the source's
     * rest frame. Nothing is set when there is no conversion from that rest frame to the
     * topocentric one, or it gives a bad frequency.
     */
    private static void dsbSetUp(FitsStore store, DSBSpecFrame dsb, double crval) {
        DSBSpecFrame dsbSrc = (DSBSpecFrame) dsb.copy();
        dsbSrc.setStdOfRestValue(SpecFrame.AST__SCSOR);
        dsbSrc.setSystemValue(SpecFrame.AST__FREQ);
        dsbSrc.setUnit(1, "Hz");
        DSBSpecFrame dsbTopo = (DSBSpecFrame) dsbSrc.copy();
        dsbTopo.setStdOfRestValue(SpecFrame.AST__TPSOR);
        FrameSet fs = dsbSrc.convert(dsbTopo, "");
        if (fs == null) {
            return;
        }
        double imagfreq = store.imagfreq != null ? store.imagfreq : AstObject.AST__BAD;
        double[] out = fs.tran1(2, new double[] {dsb.restFreqValue(), imagfreq}, true);
        if (out[0] != AstObject.AST__BAD && out[1] != AstObject.AST__BAD) {
            dsb.setD("DSBCentre", crval);
            dsbTopo = (DSBSpecFrame) dsb.copy();
            dsbTopo.setStdOfRestValue(SpecFrame.AST__TPSOR);
            dsbTopo.setSystemValue(SpecFrame.AST__FREQ);
            dsbTopo.setUnit(1, "Hz");
            double dsbcentre = dsbTopo.getD("DSBCentre");
            double lo = 0.5 * (out[1] + out[0]);
            dsb.setIfValue(lo - dsbcentre);
            dsb.setC("SideBand", "observed");
        }
    }

    /**
     * C's {@code LinearWcs} (fitschan.c:20713): a ShiftMap adding CRVAL, or a UnitMap when CRVAL
     * is zero or absent.
     */
    private static Mapping linearWcs(FitsStore store, int i) {
        double crv = store.crval[i] != null ? store.crval[i] : 0.0;
        return crv != 0.0 ? new ShiftMap(new double[] {crv}) : new UnitMap(1);
    }

    /**
     * C's {@code LogWcs} (fitschan.c): the Mapping from the intermediate world coordinate {@code w}
     * of axis {@code i} to the logarithmic axis {@code S = Sr*exp(w/Sr)}, with {@code Sr} the
     * CRVAL; a MathMap with the functions {@code s=Sr*exp(w/Sr)} and {@code w=Sr*log(s/Sr)},
     * {@code Sr} written with C's {@code %.17g} ({@code AST__DBL_DIG}), and SimpFI and SimpIF set.
     *
     * @return the MathMap, or null when CRVAL is zero or absent, which has no logarithmic form
     */
    static Mapping logWcs(FitsStore store, int i) {
        double crv = store.crval[i] != null ? store.crval[i] : 0.0;
        if (crv == 0.0) {
            return null;
        }
        String sr = CFormat.format("%." + DBL_DIG + "g", crv);
        MathMap ret = new MathMap(
                1, 1, new String[] {"s=" + sr + "*exp(w/" + sr + ")"}, new String[] {"w=" + sr + "*log(s/" + sr + ")"});
        ret.set("simpfi=1,simpif=1");
        return ret;
    }

    /**
     * C's {@code NonLinSpecWcs} (fitschan.c:23867): the Mapping of a {@code -X2P} axis, sampled
     * linearly in the system X the second character of the code names (F, W, V or A), from the
     * intermediate world coordinate {@code w} to the axis's system S. FITS-WCS paper III makes
     * {@code dS/dw} one at the reference point, so {@code w} maps to X through a WinMap (a ZoomMap
     * when the reference value in X is zero) scaled by {@code dX/dS} there, and X to S through the
     * conversion between two copies of the SpecFrame. A {@code badctype} warning is raised when
     * the fourth character of the code is not the P system that S implies.
     *
     * @return the Mapping, or null when X is not one of those, there is no conversion from S to
     *     X, or {@code dS/dX} at the reference point is bad or zero
     */
    private static Mapping nonLinSpecWcs(
            String algcode, FitsStore store, int i, SpecFrame specfrm, FitsWarnings warnings) {
        SpecFrame xfrm = (SpecFrame) specfrm.copy();
        boolean ok = true;
        char x = algcode.charAt(1);
        if (x == 'F') {
            xfrm.setSystemValue(SpecFrame.AST__FREQ);
            xfrm.setUnit(1, "Hz");
        } else if (x == 'W') {
            xfrm.setSystemValue(SpecFrame.AST__WAVELEN);
            xfrm.setUnit(1, "m");
        } else if (x == 'V') {
            xfrm.setSystemValue(SpecFrame.AST__VREL);
            xfrm.setUnit(1, "m/s");
        } else if (x == 'A') {
            xfrm.setSystemValue(SpecFrame.AST__AIRWAVE);
            xfrm.setUnit(1, "m");
        } else {
            ok = false;
        }
        Mapping map1 = null;
        if (ok) {
            ok = false;
            FrameSet fs = specfrm.convert(xfrm, "");
            if (fs != null) {
                map1 = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
                ok = true;
            }

            // A warning when the P system is not the one S implies; AST interprets the illegal P
            // system correctly, so the read goes on.
            char pc = requiredP(specfrm);
            char p = algcode.length() > 3 ? algcode.charAt(3) : '\0';
            if (p != pc) {
                String text = specfrm.getC("System");
                warnings.push(new FitsWarnings.Warning(
                        "badctype",
                        "The spectral CTYPE value " + text + algcode + " is not legal - using " + text
                                + algcode.substring(0, Math.min(3, algcode.length())) + pc + " instead."));
            }
        }
        if (!ok) {
            return null;
        }
        double crv = store.crval[i] != null ? store.crval[i] : 0.0;
        crv = map1.tran1(1, new double[] {crv}, true)[0];
        map1.invert();
        double ds = map1.rate(new double[] {crv}, 1, 1);
        if (ds == AstObject.AST__BAD || ds == 0.0) {
            return null;
        }
        Mapping map2;
        if (crv != 0.0) {
            map2 = new WinMap(
                    1, new double[] {0.0}, new double[] {crv * ds}, new double[] {crv}, new double[] {2.0 * crv});
        } else {
            map2 = new ZoomMap(1, 1.0 / ds);
        }
        return new CmpMap(map2, map1, true);
    }

    /**
     * The P character of a {@code -X2P} code that the System of {@code specfrm} implies
     * (fitschan.c:23995-24012): F for frequency, energy, wavenumber and radio velocity, W for
     * wavelength, optical velocity and redshift, A for air wavelength, V for beta and apparent
     * radial velocity.
     */
    private static char requiredP(SpecFrame specfrm) {
        int sys = specfrm.systemValue();
        if (sys == SpecFrame.AST__FREQ
                || sys == SpecFrame.AST__ENERGY
                || sys == SpecFrame.AST__WAVENUM
                || sys == SpecFrame.AST__VRADIO) {
            return 'F';
        } else if (sys == SpecFrame.AST__WAVELEN || sys == SpecFrame.AST__VOPTICAL || sys == SpecFrame.AST__REDSHIFT) {
            return 'W';
        } else if (sys == SpecFrame.AST__AIRWAVE) {
            return 'A';
        } else if (sys == SpecFrame.AST__BETA || sys == SpecFrame.AST__VREL) {
            return 'V';
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    METHOD + ": Function NonLinSpecWcs does not yet support spectral axes of type "
                            + specfrm.getC("System") + " (internal AST programming error).");
        }
    }

    /**
     * C's {@code GrismSpecWcs} (fitschan.c:14424) for a {@code -GRI} (vacuum) or {@code -GRA} (air
     * wavelength) axis: the Mapping is built from the conversion of the SpecFrame to wavelength in
     * metres and a GrismMap.
     *
     * @return null when there is no conversion from the SpecFrame to wavelength
     * @throws UnsupportedOperationException when there is one, since the GrismMap is not
     *     available
     */
    private static Mapping grismSpecWcs(String algcode, int i, SpecFrame specfrm, String ctype) {
        SpecFrame wfrm = (SpecFrame) specfrm.copy();
        wfrm.setSystemValue("-GRI".equals(algcode) ? SpecFrame.AST__WAVELEN : SpecFrame.AST__AIRWAVE);
        wfrm.setUnit(1, "m");
        FrameSet fs = specfrm.convert(wfrm, "");
        if (fs == null) {
            return null;
        }
        throw new UnsupportedOperationException(
                "FitsChan: the " + algcode + " spectral axis " + (i + 1) + " (CTYPE '" + ctype + "') needs GrismMap");
    }
}
