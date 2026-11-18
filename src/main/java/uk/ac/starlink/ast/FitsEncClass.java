package uk.ac.starlink.ast;

/**
 * The cards of a FITS-CLASS header for the primary axis description of a FrameSet: C's {@code
 * CLASSFromStore}, which writes the description {@link FitsFsetToStore#fsetToStore} made.
 *
 * <p>FITS-CLASS, the encoding of the GILDAS CLASS package, describes a cube whose first three WCS
 * axes are a frequency axis and two celestial axes (RA/DEC or galactic, in a projection CLASS
 * knows, with no rotation), with the frequency given as an offset from the rest frequency and in
 * the source's rest frame, and the reference point's radio velocity in the LSRK (VELO-LSR) and its
 * azimuth and elevation. C checks the description first, and writes it only if FITS-CLASS can hold
 * it; it finds the velocities with a SpecFrame matched in the current Frame of the FrameSet, and
 * the azimuth and elevation with SkyFrames matched there.
 *
 * <p>Ported from fitschan.c {@code CLASSFromStore}, which includes the fixes of upstream aea3090b
 * (a LONPOLE of +180 or -180 degrees is allowed) and 17416d33 (the spectral CDELT is converted to
 * Hz once, for the card and for the velocities). The Rust port's {@code fitschan/enc_class.rs} is
 * organised differently.
 */
final class FitsEncClass {

    /** C's {@code AST__DD2R}: radians per degree. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__BAD}, for the values C tests against it. */
    private static final double BAD = AstObject.AST__BAD;

    private FitsEncClass() {}

    /**
     * C's {@code CLASSFromStore}: writes the primary description in {@code store} to {@code fc} as
     * FITS-CLASS cards, each in front of the current card, if FITS-CLASS can hold it. The spectral
     * and celestial axes must be among the first three; the projection TAN, ARC or STG, AIT or SFL
     * with the reference point at the origin (written with the older codes ATF and GLS), or SIN
     * with zero parameters; the longitude RA or galactic; the spectral axis FREQ in the source's
     * rest frame (SPECSYS SOURCE), in Hz, kHz, MHz or GHz; no rotation unless both celestial
     * pixel axes span one pixel; an FK4 or FK5 system (when there is one) whose equinox the IAU
     * 1984 rule allows; a rest frequency; and, unless both celestial pixel axes span one pixel, no
     * LATPOLE and no LONPOLE but +180 or -180 degrees.
     *
     * <p>When the image has three or more pixel axes and the celestial pixel axes span one pixel,
     * C first puts the reference point at pixel (1, 1, 1), transforming that pixel with {@code
     * fs}, which must then have three inputs and three outputs; it looks up the sizes of the
     * celestial pixel axes in {@code dim} at the indices of the celestial WCS axes. A description
     * of one pixel axis, to which celestial axes were added, is written as an image of three with
     * NAXIS2 and NAXIS3 of 1.
     *
     * <p>The cards are the first three axes' CRPIX, CRVAL (the spectral one less the rest
     * frequency, in Hz), CTYPE and CDELT (the spectral one in Hz), EQUINOX (that of the system
     * when the description has none), DATE-OBS (and MJD-OBS over a card the header has), RESTFREQ,
     * IMAGFREQ, OBJECT and LINE (blank unless the header has them), and, where the current Frame of
     * {@code fs} gives them, VELO-LSR, VLSR and DELTAV, the radio velocity in the LSRK of the
     * reference channel and the difference to the next channel, and AZIMUTH and ELEVATIO, the
     * reference point's. A description lacking a CRPIX, CRVAL or CTYPE on one of the first three
     * axes, or with a {@code -TAB} axis, stops those cards part way, and the write gives false
     * after writing the others, as C does; {@link FitsChan#write} then removes the cards written.
     *
     * @param fc the FitsChan written to, whose NAXISi cards give the size of the celestial pixel
     *     axes
     * @param store the descriptions
     * @param fs the FrameSet the descriptions were made from
     * @param dim the size of each pixel axis, or {@code AST__BAD} where unknown
     * @param method the calling method, for messages
     * @param cls the class of the object written, for messages
     * @return true if the description was written
     * @throws AstException as {@code fs} reports, with status {@code AST__NCPIN}, a transformation
     *     of pixel (1, 1, 1) by a FrameSet without three inputs and outputs
     */
    static boolean classFromStore(
            FitsChan fc, FitsPlaneStores store, FrameSet fs, double[] dim, String method, String cls) {
        FitsStore st = store.get(' ');
        if (st == null) {
            st = new FitsStore(0);
        }
        FitsCardList cards = fc.cards();
        int rnd = fc.getFitsRounding();
        double specfactor = 1.0;
        boolean ok = false;
        String lontype = null;
        String lattype = null;
        String spectype = null;

        // The primary celestial and spectral axes.
        int[] axes = st.lonLatSpecAxes(store.naxis);
        int axlon = axes[0];
        int axlat = axes[1];
        int axspec = axes[2];
        Frame curfrm = fs.getFrame(FrameSet.AST__CURRENT);

        // They must be among WCS axes 1, 2 and 3.
        if (axspec >= 0 && axspec < 3 && axlon >= 0 && axlon < 3 && axlat >= 0 && axlat < 3) {
            // When the celestial pixel axes span one pixel, the reference point becomes the centre
            // of that pixel, pixel (1, 1, 1).
            if (store.naxis >= 3 && dim[axlon] == 1.0 && dim[axlat] == 1.0) {
                double[] xout = fs.tranN(1, 3, new double[] {1.0, 1.0, 1.0}, true, 3);
                if (xout[axlon] != BAD && xout[axlat] != BAD) {
                    // The celestial axes of the current Frame may not be the FITS axes of those
                    // indices, so the values are taken from its longitude and latitude axes.
                    double lonval = 0.0;
                    double latval = 0.0;
                    for (int i = 0; i < 3; i++) {
                        Boolean isLon = isLonAxis(curfrm, i + 1);
                        if (isLon != null) {
                            if (isLon) {
                                lonval = xout[i];
                            } else {
                                latval = xout[i];
                            }
                        }
                    }
                    st.crval[axlon] = lonval * AST__DR2D;
                    st.crval[axlat] = latval * AST__DR2D;
                    st.crpix[axlon] = 1.0;
                    st.crpix[axlat] = 1.0;
                }
            }

            // The reference point, and the CTYPE values, the projection from the latitude one. C
            // sets ok here, and clears it for a missing CRVAL or CTYPE of either celestial axis,
            // but the test of the projection sets it again whatever it was; a missing CRVAL is BAD, which fails the
            // test of AIT and SFL. The CTYPE values are never missing, as they name the celestial
            // axes.
            double latval = value(at(st.crval, axlat));
            double lonval = value(at(st.crval, axlon));
            lontype = at(st.ctype, axlon);
            lattype = at(st.ctype, axlat);
            int prj = lattype == null ? WcsMap.AST__WCSBAD : FitsWcsCelestial.wcsPrjType(lattype.substring(4));
            if (prj == WcsMap.AST__WCSBAD) {
                ok = false;
            } else if (prj != WcsMap.AST__SIN) {
                ok = prj == WcsMap.AST__TAN
                        || prj == WcsMap.AST__ARC
                        || prj == WcsMap.AST__STG
                        || prj == WcsMap.AST__AIT
                        || prj == WcsMap.AST__SFL;
                // AIT and SFL need the reference point at the origin of the celestial coordinates,
                // and are written with the older codes ATF and GLS.
                if (ok && (prj == WcsMap.AST__AIT || prj == WcsMap.AST__SFL)) {
                    if (latval != 0.0 || lonval != 0.0) {
                        ok = false;
                    } else if (prj == WcsMap.AST__SFL) {
                        lontype = withCode(lontype, "-GLS");
                        lattype = withCode(lattype, "-GLS");
                    } else {
                        lontype = withCode(lontype, "-ATF");
                        lattype = withCode(lattype, "-ATF");
                    }
                }
            } else {
                // A SIN projection whose parameters are both zero.
                double p1 = value(at(st.pv, axlat, 1));
                double p2 = value(at(st.pv, axlat, 2));
                ok = (p1 == BAD ? 0.0 : p1) == 0.0 && (p2 == BAD ? 0.0 : p2) == 0.0;
            }

            // RA or galactic longitude.
            if (ok && !lontype.startsWith("RA--") && !lontype.startsWith("GLON")) {
                ok = false;
            }

            // A FREQ spectral axis: the CTYPE must be a leading part of "FREQ".
            String cval = at(st.ctype, axspec);
            if (cval == null) {
                ok = false;
            } else if ("FREQ".startsWith(cval.substring(0, FitsCard.chrLen(cval)))) {
                spectype = "FREQ";
            } else {
                ok = false;
            }

            // The source's rest frame: SPECSYS a leading part of "SOURCE".
            cval = st.specsys;
            if (cval == null) {
                ok = false;
            } else if (ok && !"SOURCE".startsWith(cval.substring(0, FitsCard.chrLen(cval)))) {
                ok = false;
            }

            // Units of frequency, and the factor converting them to Hz.
            cval = at(st.cunit, axspec);
            if (cval == null) {
                ok = false;
            } else if (ok) {
                specfactor = hertzPerUnit(cval);
                if (specfactor == 0.0) {
                    ok = false;
                }
            }
        }

        // The number of WCS axes, at most three.
        int naxis = Math.min(st.maxCrpixAxis() + 1, 3);
        double[] cdelt = ok ? new double[naxis] : null;

        // The diagonal of the CD matrix, and no rotation unless both celestial pixel axes span one
        // pixel; the sizes of those axes are 0 where unknown.
        int naxis2 = naxisValue(cards, axlon, rnd, method, cls);
        int naxis3 = naxisValue(cards, axlat, rnd, method, cls);
        for (int i = 0; i < naxis && ok; i++) {
            for (int j = 0; j < naxis && ok; j++) {
                double val = st.cdElement(i, j);
                if (i == j) {
                    cdelt[i] = val;
                } else if (val != 0.0 && (naxis2 != 1 || naxis3 != 1)) {
                    ok = false;
                }
            }
        }

        // FK4 or FK5, and the equinox: that the IAU 1984 rule gives them, or their default.
        String radesys = st.radesys;
        double equ = value(st.equinox);
        if (radesys != null) {
            if (!"FK4".equals(radesys) && !"FK5".equals(radesys)) {
                ok = false;
            }
            if (equ == BAD) {
                if ("FK4".equals(radesys)) {
                    equ = 1950.0;
                } else if ("FK5".equals(radesys)) {
                    equ = 2000.0;
                } else {
                    ok = false;
                }
            } else if ("FK4".equals(radesys)) {
                if (equ >= 1984.0) {
                    ok = false;
                }
            } else if ("FK5".equals(radesys)) {
                if (equ < 1984.0) {
                    ok = false;
                }
            } else {
                ok = false;
            }
        }

        // A rest frequency.
        double rf = value(st.restfrq);
        if (rf == BAD) {
            ok = false;
        }

        // No LATPOLE, and a LONPOLE of +180 or -180 degrees, unless the celestial pixel axes span
        // one pixel.
        if (ok && (naxis2 != 1 || naxis3 != 1)) {
            if (st.lonpole != null && st.lonpole != -180.0 && st.lonpole != 180.0) {
                ok = false;
            }
            if (st.latpole != null) {
                ok = false;
            }
        }
        if (!ok) {
            return false;
        }

        // A description of one pixel axis, to which celestial axes were added, is written as an
        // image of three, since FITS-CLASS has no WCSAXES keyword: NAXIS is set to 3 (a card put
        // first if there is none), and NAXIS2 and NAXIS3 of 1 are put after NAXIS1, or at the end
        // if there is no NAXIS1.
        if (store.naxis == 1) {
            cards.clearCard();
            cards.setValueAsWriter("NAXIS", FitsCard.Value.ofInt(3), null, rnd);
            if (cards.findKeyCard("NAXIS1", method)) {
                cards.moveCard(1);
            }
            cards.setValueAsWriter("NAXIS2", FitsCard.Value.ofInt(1), null, rnd);
            cards.setValueAsWriter("NAXIS3", FitsCard.Value.ofInt(1), null, rnd);
        }
        cards.positionWriteCursor();

        // CRPIX, CRVAL and CTYPE, which are required, so a missing one stops each loop and the
        // write. The spectral CRVAL is written in Hz as an offset from the rest frequency, and the
        // celestial CTYPE values with the projection codes chosen above. C's crval values for
        // axes not reached are not set; here they are 0.
        double[] crval = new double[3];
        for (int j = 0; j < naxis && ok; j++) {
            Double val = at(st.crpix, j);
            if (val == null) {
                ok = false;
            } else {
                setFloat(cards, "CRPIX" + (j + 1), val, "Reference pixel on axis " + (j + 1), rnd);
            }
        }
        for (int i = 0; i < naxis && ok; i++) {
            Double val = at(st.crval, i);
            if (val == null) {
                ok = false;
            } else {
                crval[i] = val;
                double v = val;
                if (i == axspec) {
                    v *= specfactor;
                    v -= rf;
                }
                setFloat(cards, "CRVAL" + (i + 1), v, "Value at ref. pixel on axis " + (i + 1), rnd);
            }
        }
        for (int i = 0; i < naxis && ok; i++) {
            String cval;
            if (i == axlat) {
                cval = lattype;
            } else if (i == axlon) {
                cval = lontype;
            } else if (i == axspec) {
                cval = spectype;
            } else {
                cval = at(st.ctype, i);
            }
            if (cval != null && (cval.length() < 5 || !"-TAB".equals(cval.substring(4)))) {
                String comm = at(st.ctypeCom, i);
                if (comm == null) {
                    comm = "Type of co-ordinate on axis " + (i + 1); // codespell:ignore
                }
                setString(cards, "CTYPE" + (i + 1), cval, comm, rnd);
            } else {
                ok = false;
            }
        }

        // CDELT, the spectral one in Hz. C's cdelt array has one element per axis written and it
        // writes past the end of it here, when the spectral axis is not one of them; no FrameSet
        // gives that, since every WCS axis has a CRPIX.
        if (axspec >= naxis) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "CLASSFromStore: the spectral axis " + (axspec + 1) + " is beyond the " + naxis
                            + " axes written, where C writes past the end of its CDELT array.");
        }
        cdelt[axspec] *= specfactor;
        for (int i = 0; i < naxis; i++) {
            setFloat(cards, "CDELT" + (i + 1), cdelt[i], "Pixel size", rnd);
        }

        if (equ != BAD) {
            setFloat(cards, "EQUINOX", equ, "Epoch of reference equinox", rnd);
        }

        // The date of observation as DATE-OBS. A read leaves any MJD-OBS card in the header, since
        // other software may use it, so such a card is set to the same date.
        if (st.mjdobs != null) {
            double val = st.mjdobs;
            setString(cards, "DATE-OBS", FitsDates.mjdToDateStr(val), "Date of observation", rnd);
            if (cards.hasCard("MJD-OBS")) {
                setFloat(cards, "MJD-OBS", val, "Modified Julian Date of observation", rnd);
            }
        }

        setFloat(cards, "RESTFREQ", rf, "[Hz] Rest frequency", rnd);

        // The image frequency of the rest frequency, for double sideband data.
        if (st.imagfreq != null) {
            setFloat(cards, "IMAGFREQ", st.imagfreq, "[Hz] Image frequency", rnd);
        }

        // OBJECT and LINE, blank unless the header has them.
        if (!cards.hasCard("OBJECT")) {
            setString(cards, "OBJECT", " ", null, rnd);
        }
        if (!cards.hasCard("LINE")) {
            setString(cards, "LINE", " ", null, rnd);
        }

        velocities(cards, curfrm, crval[axspec] * specfactor, cdelt[axspec], rnd);
        azimuthElevation(cards, curfrm, crval[axlon], crval[axlat], rnd);
        return ok;
    }

    /**
     * VELO-LSR and VLSR, the radio velocity in the LSRK of the reference channel, whose frequency
     * in the source's rest frame is {@code freq} Hz, and DELTAV, the difference of that of the
     * channel {@code delta} Hz above it. CLASS takes VELO-LSR to be the velocity of the reference
     * channel, not of the source. C finds the spectral axis of {@code curfrm} with a template
     * SpecFrame of radio velocity in the LSRK in m/s, whose other attributes come from that axis,
     * and converts to it from a copy set to frequency in the source's rest frame in Hz; nothing is
     * written when either is not found.
     */
    private static void velocities(FitsCardList cards, Frame curfrm, double freq, double delta, int rnd) {
        SpecFrame template = new SpecFrame();
        template.set("System=vrad,StdOfRest=lsrk,Unit=m/s,MinAxes=1,MaxAxes=3");
        FrameSet found = curfrm.findFrame(template, "");
        if (found == null) {
            return;
        }
        Frame velofrm = found.getFrame(FrameSet.AST__CURRENT);
        Frame freqfrm = (Frame) velofrm.copy();
        freqfrm.set("System=freq,StdOfRest=Source,Unit=Hz");
        FrameSet conv = freqfrm.convert(velofrm, "");
        if (conv == null) {
            return;
        }
        double[] aval = conv.tran1(2, new double[] {freq, freq + delta}, true);
        setFloat(cards, "VELO-LSR", aval[0], "[m/s] Reference velocity", rnd);
        setFloat(cards, "VLSR", aval[0], "[m/s] Reference velocity", rnd);
        setFloat(cards, "DELTAV", aval[1] - aval[0], "[m/s] Velocity resolution", rnd);
    }

    /**
     * AZIMUTH and ELEVATIO, the azimuth and elevation of the reference point ({@code lon}, {@code
     * lat}) in degrees in the celestial system of the CTYPE values. C finds a template SkyFrame of
     * that system and one of AZEL in {@code curfrm}, whose other attributes (the observatory, the
     * epoch) come from it, and transforms from the one to the other through {@code curfrm},
     * normalising the result with the AZEL template; nothing is written when either is not found
     * or the result is bad.
     */
    private static void azimuthElevation(FitsCardList cards, Frame curfrm, double lon, double lat, int rnd) {
        SkyFrame radecfrm = new SkyFrame();
        radecfrm.set("Permute=0,MinAxes=3,MaxAxes=3");
        FrameSet fsconv1 = curfrm.findFrame(radecfrm, "");
        SkyFrame azelfrm = new SkyFrame();
        azelfrm.set("System=AZEL,Permute=0,MinAxes=3,MaxAxes=3");
        FrameSet fsconv2 = curfrm.findFrame(azelfrm, "");
        if (fsconv1 == null || fsconv2 == null) {
            return;
        }
        Mapping map1 = fsconv1.getMapping(FrameSet.AST__CURRENT, FrameSet.AST__BASE);
        Mapping map2 = fsconv2.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Mapping map3 = new CmpMap(map1, map2, true);
        double[] azel = map3.tranN(1, 2, new double[] {lon * AST__DD2R, lat * AST__DD2R}, true, 2);
        if (azel[0] != BAD && azel[1] != BAD) {
            azelfrm.norm(azel);
            setFloat(cards, "AZIMUTH", azel[0] * AST__DR2D, "[Deg] Telescope azimuth", rnd);
            setFloat(cards, "ELEVATIO", azel[1] * AST__DR2D, "[Deg] Telescope elevation", rnd);
        }
    }

    /**
     * The factor converting the spectral unit {@code unit} to Hz, C's {@code specfactor}: 1, 1e3,
     * 1e6 or 1e9 for Hz, kHz, MHz or GHz, and 0 for any other unit, which FITS-CLASS cannot hold.
     */
    private static double hertzPerUnit(String unit) {
        // MODERN-JAVA(21): switch expression
        switch (unit) {
            case "Hz":
                return 1.0;
            case "kHz":
                return 1.0e3;
            case "MHz":
                return 1.0e6;
            case "GHz":
                return 1.0e9;
            default:
                return 0.0;
        }
    }

    /** C's {@code strcpy( type + 4, code )}: the first four characters of {@code ctype} and {@code code}. */
    private static String withCode(String ctype, String code) {
        return ctype.substring(0, 4) + code;
    }

    /**
     * C's {@code astHasAttribute( frame, "IsLonAxis(axis)" )} and {@code astGetI}: whether axis
     * {@code axis} (from 1) of {@code frame} is a longitude axis, or null if the Frame has no such
     * attribute for that axis (it is not an axis of a SkyFrame).
     */
    private static Boolean isLonAxis(Frame frame, int axis) {
        try {
            return frame.getI("IsLonAxis(" + axis + ")") != 0;
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
            return null;
        }
    }

    /**
     * The integer value of keyword NAXIS{@code axis + 1}, read as C's {@code GetValue} reads it,
     * unmarked and with no error for a card that is absent or does not convert, which give 0.
     */
    private static int naxisValue(FitsCardList cards, int axis, int rnd, String method, String cls) {
        FitsCardValue.Got g =
                cards.getValue("NAXIS" + (axis + 1), FitsCardValue.Want.INT, false, false, rnd, method, cls);
        return g == null ? 0 : (int) g.i;
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
}
