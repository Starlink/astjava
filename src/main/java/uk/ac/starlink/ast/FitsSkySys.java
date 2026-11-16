package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The FITS-WCS description of a celestial coordinate system, as a FitsChan write stores it: C's
 * {@code SkySys} and {@code IsSkyOff} (fitschan.c). Dates are converted between time scales by
 * C's {@code TDBConv}, {@link FitsEpoch#tdbConv}, through a TimeFrame.
 *
 * <p>Ported from Rust {@code fitschan/fset_to_store/sky_sys.rs}, following fitschan.c.
 */
final class FitsSkySys {

    private static final double AST__BAD = AstObject.AST__BAD;

    /** C's {@code SkySys} system codes. */
    private static final int RADEC = 0;

    private static final int ECLIP = 1;

    private static final int HECLIP = 2;

    private static final int GALAC = 3;

    private static final int SUPER = 4;

    private static final int AZEL = 5;

    private static final int NOCEL = 6;

    private FitsSkySys() {}

    /**
     * C's {@code IsSkyOff}: 1 if Frame {@code iframe} of {@code fset} is, or contains, a SkyFrame
     * describing offset coordinates, one whose SkyRefIs is {@code Pole} or {@code Origin};
     * otherwise 0. The SkyRefIs attribute is read from the Frame, an error from a Frame without
     * one being cleared as C clears it.
     *
     * @param iframe the index of the Frame, from 1, or {@link FrameSet#AST__CURRENT} or {@link
     *     FrameSet#AST__BASE}
     */
    static int isSkyOff(FrameSet fset, int iframe) {
        Frame frm = fset.getFrame(iframe);
        String skyrefis;
        try {
            skyrefis = frm.getC("SkyRefIs");
        } catch (AstException e) {
            skyrefis = null;
        }
        if (skyrefis != null && (ustrcmp(skyrefis, "POLE") || ustrcmp(skyrefis, "ORIGIN"))) {
            return 1;
        }
        return 0;
    }

    /**
     * C's {@code SkySys}: stores in {@code store} the CTYPE, CNAME, RADESYS, EQUINOX, MJD-OBS,
     * TIMESYS, CUNIT, WCSNAME and OBSGEO-X/Y/Z values describing the celestial axes of {@code
     * skyfrm}, at FITS axes {@code axlon} and {@code axlat}.
     *
     * <p>The date of observation is the Epoch, written in the time scale the FitsChan's TIMESYS
     * card names (used or not), and then TIMESYS too, or else in UTC; it is stored only when it
     * differs from the equinox and only for the primary description ({@code s} a space). A TIMESYS
     * value AST does not support raises a {@code badval} warning in front of the current card.
     *
     * @param fc the FitsChan, whose TIMESYS card is used and to which warnings go
     * @param skyfrm the SkyFrame; nothing is stored unless its Domain is {@code SKY}
     * @param wcstype 0 for the {@code -TAB} algorithm, 1 for a WcsMap projection
     * @param wcsproj the WcsMap projection type, used when {@code wcstype} is 1
     * @param store the store
     * @param axlon the zero-based FITS axis of longitude
     * @param axlat the zero-based FITS axis of latitude
     * @param s the coordinate version character, a space for the primary description
     * @param isoff positive to describe offset coordinates; negative to describe absolute
     *     coordinates with the SkyRefIs (-1 Pole, -2 Origin, otherwise Ignored), SkyRef and SkyRefP
     *     attributes; zero to ignore offsets
     * @param method the calling method, for warnings
     * @param cls the class of the object written, for warnings
     * @return whether the values stored are usable: false for a SkyFrame whose Domain is not
     *     {@code SKY}
     */
    static boolean skySys(
            FitsChan fc,
            SkyFrame skyfrm,
            int wcstype,
            int wcsproj,
            FitsStore store,
            int axlon,
            int axlat,
            char s,
            int isoff,
            String method,
            String cls) {
        if (!FitsMakeIntWorld.isASkyFrame(skyfrm)) {
            return false;
        }

        // The equinox, the epoch of observation in TDB, and the system.
        double eq = skyfrm.getEquinoxMjd();
        String sys = skyfrm.getC("system");
        double epTdb = skyfrm.testEpoch() ? skyfrm.getEpochMjd() : AST__BAD;
        double epUtc = FitsEpoch.tdbConv(epTdb, TimeFrame.AST__UTC, true);

        // A TIMESYS card other than UTC, used or not, gives the time scale of the date, and is
        // stored; its padding is removed first.
        String timesys = timesysValue(fc);
        double ep;
        if (timesys != null && !"UTC".equals(timesys)) {
            FitsWarnings warnings = new FitsWarnings();
            int scale = FitsEpoch.timeSysToAst(timesys, warnings);
            for (FitsWarnings.Warning w : warnings.list()) {
                fc.issueWarning(w, method);
            }
            ep = FitsEpoch.tdbConv(epTdb, scale, true);
            store.timesys = timesys;
        } else {
            ep = epUtc;
        }

        // MJD-OBS defaults to the epoch of the equinox, so need not be written when equal to it.
        boolean defdate = MappingHelpers.astEqual(epUtc, eq);

        // The equinox as a Julian or Besselian epoch, and RADESYS.
        int isys;
        if (ustrcmp(sys, "FK4")) {
            eq = Pal.epb(eq);
            isys = RADEC;
            store.radesys = "FK4";
        } else if (ustrcmp(sys, "FK4_NO_E") || ustrcmp(sys, "FK4-NO-E")) {
            eq = Pal.epb(eq);
            isys = RADEC;
            store.radesys = "FK4-NO-E";
        } else if (ustrcmp(sys, "FK5")) {
            eq = Pal.epj(eq);
            isys = RADEC;
            store.radesys = "FK5";
        } else if (ustrcmp(sys, "ICRS")) {
            eq = AST__BAD;
            isys = RADEC;
            store.radesys = "ICRS";
        } else if (ustrcmp(sys, "GAPPT") || ustrcmp(sys, "Apparent") || ustrcmp(sys, "Geocentric")) {
            eq = AST__BAD;
            isys = RADEC;
            store.radesys = "GAPPT";
        } else if (ustrcmp(sys, "Ecliptic")) {
            // With no RADESYS, WcsSkyFrame reads an EQUINOX below 1984.0 as a Besselian epoch and
            // any other as a Julian epoch.
            eq = Pal.epb(eq) < 1984.0 ? Pal.epb(eq) : Pal.epj(eq);
            isys = ECLIP;
        } else if (ustrcmp(sys, "Helioecliptic")) {
            eq = AST__BAD;
            isys = HECLIP;
        } else if (ustrcmp(sys, "Galactic")) {
            eq = AST__BAD;
            isys = GALAC;
        } else if (ustrcmp(sys, "Supergalactic")) {
            eq = AST__BAD;
            isys = SUPER;
        } else if (ustrcmp(sys, "AzEl")) {
            eq = AST__BAD;
            isys = AZEL;
        } else {
            eq = AST__BAD;
            isys = NOCEL;
        }

        // FITS has a single date of observation, which an alternate description must not change.
        store.equinox = valueOrNull(eq);
        if (!defdate && s == ' ') {
            store.mjdobs = valueOrNull(ep);
        }

        // The zero-based axes of latitude and longitude within the SkyFrame.
        int latax = skyfrm.getLatAxis() - 1;
        int lonax = 1 - latax;

        // The first four characters of CTYPE name the system, or offset coordinates.
        String lontype;
        String lattype;
        if (isoff > 0) {
            lontype = "OFLN";
            lattype = "OFLT";
        } else if (isys == RADEC) {
            lontype = "RA--";
            lattype = "DEC-";
        } else if (isys == ECLIP) {
            lontype = "ELON";
            lattype = "ELAT";
        } else if (isys == HECLIP) {
            lontype = "HLON";
            lattype = "HLAT";
        } else if (isys == GALAC) {
            lontype = "GLON";
            lattype = "GLAT";
        } else if (isys == SUPER) {
            lontype = "SLON";
            lattype = "SLAT";
        } else if (isys == AZEL) {
            lontype = "AZ--";
            lattype = "EL--";
        } else if (isys == NOCEL) {
            // The axis symbols, when they have the form FITS-WCS requires ("xxLN/xxLT" or
            // "xLON/xLAT"), otherwise "UNLN/UNLT".
            String latsym = skyfrm.getSymbol(latax + 1);
            String lonsym = skyfrm.getSymbol(lonax + 1);
            boolean ok = false;
            if (latsym.length() == 4 && lonsym.length() == 4) {
                if ("LT".equals(latsym.substring(2))
                        && "LN".equals(lonsym.substring(2))
                        && latsym.regionMatches(0, lonsym, 0, 2)) {
                    ok = true;
                } else if ("LAT".equals(latsym.substring(1))
                        && "LON".equals(lonsym.substring(1))
                        && latsym.charAt(0) == lonsym.charAt(0)) {
                    ok = true;
                }
            }
            if (!ok) {
                latsym = "UNLT";
                lonsym = "UNLN";
            }
            lontype = padWithDashes(lonsym);
            lattype = padWithDashes(latsym);
        } else {
            throw Ast.err(AstException.AST__INTER, "SkySys(FitsChan): unexpected celestial system code " + isys + ".");
        }

        // The last four characters name the projection.
        String prjName = wcstype == 0 ? "-TAB" : WcsMap.wcsPrjName(wcsproj);
        store.ctype[axlon] = lontype + prjName;
        store.ctype[axlat] = lattype + prjName;

        if (isoff > 0) {
            // The CTYPE comments of offset coordinates say what they are offsets from.
            String skyref = skyfrm.getC("SkyRef");
            String text = skyfrm.getC("Symbol(" + (lonax + 1) + ")");
            store.ctypeCom[axlon] = withoutFirst(text) + " offset from " + skyref;
            text = skyfrm.getC("Symbol(" + (latax + 1) + ")");
            store.ctypeCom[axlat] = withoutFirst(text) + " offset from " + skyref;
        } else if (isoff < 0) {
            // Absolute coordinates keep the SkyFrame's offset attributes in AST-specific keywords.
            double skyrefLon = skyfrm.getD("SkyRef(" + (lonax + 1) + ")");
            double skyrefLat = skyfrm.getD("SkyRef(" + (latax + 1) + ")");
            double skyrefpLon = skyfrm.getD("SkyRefP(" + (lonax + 1) + ")");
            double skyrefpLat = skyfrm.getD("SkyRefP(" + (latax + 1) + ")");
            store.skyrefis = isoff < -2 ? "IGNORED" : (isoff < -1 ? "ORIGIN" : "POLE");
            if (skyfrm.test("SkyRef(1)")) {
                store.skyref[axlon] = skyrefLon;
                store.skyref[axlat] = skyrefLat;
            }
            if (skyfrm.test("SkyRefP(1)")) {
                store.skyrefp[axlon] = skyrefpLon;
                store.skyrefp[axlat] = skyrefpLat;
            }
        }

        // A Label set for an axis is the CTYPE comment and CNAME.
        if (skyfrm.testLabel(latax + 1)) {
            String label = skyfrm.getLabel(latax + 1);
            store.ctypeCom[axlat] = label;
            store.cname[axlat] = label;
        }
        if (skyfrm.testLabel(lonax + 1)) {
            String label = skyfrm.getLabel(lonax + 1);
            store.ctypeCom[axlon] = label;
            store.cname[axlon] = label;
        }

        // Celestial axes are always in degrees, CUNIT's default.
        store.cunit[axlat] = null;
        store.cunit[axlon] = null;

        if (skyfrm.testDomain()) {
            store.wcsname = skyfrm.getDomain();
        }

        // The observer's position, which AzEl needs, for the primary description only.
        if (skyfrm.testObsLon() && skyfrm.testObsLat() && s == ' ') {
            double geolon = skyfrm.getObsLonValue();
            double geolat = skyfrm.getObsLatValue();
            double h = skyfrm.getObsAlt();
            if (geolat != AST__BAD && geolon != AST__BAD && h != AST__BAD) {
                double[] xyz = Pal.gd2gc(geolon, geolat, h);
                store.obsgeox = xyz[0];
                store.obsgeoy = xyz[1];
                store.obsgeoz = xyz[2];
            }
        }
        return true;
    }

    /**
     * The value of the FitsChan's TIMESYS card as C's {@code GetValue} finds it with used cards
     * included, as a string with trailing white space removed; null if there is none or its value
     * is not a string.
     */
    private static String timesysValue(FitsChan fc) {
        FitsCard card = fc.cards().searchCardAnyUse("TIMESYS");
        if (card == null) {
            return null;
        }
        FitsCardValue.Got got =
                FitsCardValue.cnvType(card.value, FitsCardValue.Want.STRING, fc.getFitsRounding(), card.cName());
        if (got == null) {
            return null;
        }
        String value = got.s;
        if (value.length() > FitsCard.FITS_CARD_LEN) {
            value = value.substring(0, FitsCard.FITS_CARD_LEN);
        }
        return FitsCard.trimEnd(value);
    }

    /** {@code text} without its first character, as C's {@code text + 1}. */
    private static String withoutFirst(String text) {
        return text.isEmpty() ? text : text.substring(1);
    }

    /** The first four characters of {@code sym}, padded with dashes to four. */
    private static String padWithDashes(String sym) {
        StringBuilder sb = new StringBuilder(sym.length() > 4 ? sym.substring(0, 4) : sym);
        while (sb.length() < 4) {
            sb.append('-');
        }
        return sb.toString();
    }

    /** A store value: null for {@code AST__BAD}, as C's {@code GetItem} reads a bad value as unset. */
    private static Double valueOrNull(double v) {
        return v == AST__BAD ? null : v;
    }

    /** C's {@code !Ustrcmp( a, b )}: whether the strings are equal, ignoring case. */
    private static boolean ustrcmp(String a, String b) {
        return a.equalsIgnoreCase(b);
    }
}
