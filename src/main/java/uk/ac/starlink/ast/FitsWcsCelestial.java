package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The celestial axes of a FITS-WCS axis description: if a FitsStore holds a pair of celestial
 * longitude and latitude axes, the Mapping from their intermediate world coordinates to celestial
 * coordinates, and the SkyFrame describing them.
 *
 * <p>As C's {@code WcsCelestial} builds it, the Mapping has as many axes as the axis description
 * and leaves the non-celestial axes unchanged: a diagonal MatrixMap converting the celestial IWC
 * from degrees to radians (followed by a ShiftMap when the fiducial point is moved), an inverted
 * N-axis WcsMap (projection plane to native spherical coordinates), and C's {@code WcsNative}
 * rotation of native spherical to celestial coordinates, which embeds the 2-axis rotation in N
 * axes with a parallel UnitMap and, unless the celestial axes are the first two, a PermMap before
 * and an inverted copy of it after.
 *
 * <p>Ported from Rust {@code fitschan/wcs_celestial.rs}, following C where the two differ.
 */
final class FitsWcsCelestial {

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double AST__DD2R = 0.017453292519943295;

    /** C's {@code AST__DR2D}: radians to degrees. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__DPI}: pi. */
    private static final double AST__DPI = 3.141592653589793;

    /** C's {@code AST__DPIBY2}: pi/2. */
    private static final double AST__DPIBY2 = 1.5707963267948966;

    /** AST's bad value. */
    private static final double AST__BAD = AstObject.AST__BAD;

    /** C's {@code %.*g} with {@code AST__DBL_DIG}, as the {@code badlat} warning writes angles. */
    private static final String G15 = "%.15g";

    // C's RADESYS codes in WcsSkyFrame (fitschan.c), with NORADEC for none.
    private static final int NORADEC = 0;
    private static final int FK4 = 1;
    private static final int FK4NOE = 2;
    private static final int FK5 = 3;
    private static final int GAPPT = 4;
    private static final int ICRS = 5;

    private FitsWcsCelestial() {}

    /** The celestial axes of a FitsStore, as {@link #buildCelestialWithDefB1950} finds them. */
    static final class CelestialResult {
        /**
         * The Mapping from IWC (in degrees on the celestial axes) to world coordinates (radians on
         * the celestial axes), with as many axes as the store and the other axes unchanged.
         */
        final Mapping mapping;

        /**
         * The SkyFrame describing the celestial axes, its axes swapped when the latitude axis
         * comes first, so that they are in the order the Mapping delivers them.
         */
        final SkyFrame frame;

        /** The zero-based longitude axis. */
        final int lonAxis;

        /** The zero-based latitude axis. */
        final int latAxis;

        /**
         * The celestial reference position, {@code {longitude, latitude}} in radians, or null: C's
         * {@code reflon} and {@code reflat}, from CRVAL, overridden by {@code RFVAL<i>} and then
         * by {@code AXREF<i>}.
         */
        final double[] refPos;

        /**
         * Whether {@link #refPos} came from {@code AXREF<i>}, so that C sets SkyRef from it even
         * when the SkyFrame has one (fitschan.c:36677-36686), where otherwise it sets SkyRef only
         * when it is not set (fitschan.c:36566).
         */
        final boolean refPosForced;

        CelestialResult(
                Mapping mapping, SkyFrame frame, int lonAxis, int latAxis, double[] refPos, boolean refPosForced) {
            this.mapping = mapping;
            this.frame = frame;
            this.lonAxis = lonAxis;
            this.latAxis = latAxis;
            this.refPos = refPos;
            this.refPosForced = refPosForced;
        }
    }

    /** The celestial axes of an axis description, as C's {@code WcsCelestial} identifies them. */
    private static final class CelestialAxes {
        /** The zero-based longitude axis. */
        final int lon;

        /** The zero-based latitude axis. */
        final int lat;

        /** The projection type both axes name, a WcsMap {@code AST__} type. */
        final int prj;

        /**
         * C's {@code wcscelestial_type}: {@code "EQU"}, {@code "AZL"}, or the one or two
         * character system code the CTYPE values hold.
         */
        final String sys;

        /** Whether a CTYPE too short to name a projection made the axes linear (C's {@code carlin}). */
        final boolean forcedCarLin;

        CelestialAxes(int lon, int lat, int prj, String sys, boolean forcedCarLin) {
            this.lon = lon;
            this.lat = lat;
            this.prj = prj;
            this.sys = sys;
            this.forcedCarLin = forcedCarLin;
        }
    }

    /** One celestial axis found by {@link #findCelestialAxes}. */
    private static final class FoundAxis {
        final int axis;
        final String sys;
        final int prj;
        final String ctype;

        FoundAxis(int axis, String sys, int prj, String ctype) {
            this.axis = axis;
            this.sys = sys;
            this.prj = prj;
            this.ctype = ctype;
        }
    }

    /**
     * C's {@code astWcsPrjType} on the last four characters of a CTYPE: with every blank removed,
     * a {@code -} and a projection code, matched exactly. Returns a WcsMap {@code AST__} type, or
     * {@link WcsMap#AST__WCSBAD} (Rust's None) if there is none.
     */
    static int wcsPrjType(String last4) {
        return WcsMap.wcsPrjTypeNumber(last4);
    }

    /** C's {@code FormatKey( name, axis, -1, s )}: {@code name}, the axis number and the version. */
    private static String keyName(String name, int axis, char s) {
        return name + axis + (s == ' ' ? "" : String.valueOf(s));
    }

    /** The error C reports with {@code astError( AST__BDFTS, "%s(%s): ...", method, class )}. */
    private static AstException bdfts(String message) {
        return Ast.err(AstException.AST__BDFTS, "astRead(FitsChan): " + message);
    }

    /**
     * The system code of {@code ctype} if it names a celestial axis: {@code "EQU"} or {@code
     * "AZL"} for one of {@code whole}, alone or followed by dashes to four characters, or the
     * first one or two characters before {@code tail1} or {@code tail2}, alone or followed by a
     * dash; otherwise null.
     */
    private static String isAxis(String ctype, String[] whole, String tail1, String tail2) {
        for (String w : whole) {
            StringBuilder prefix = new StringBuilder(w);
            while (prefix.length() < 4) {
                prefix.append('-');
            }
            if (ctype.equals(w) || ctype.startsWith(prefix.toString())) {
                return "RA".equals(w) || "DEC".equals(w) ? "EQU" : "AZL";
            }
        }
        if (tailMatches(ctype, 1, tail1)) {
            return ctype.substring(0, 1);
        } else if (tailMatches(ctype, 2, tail2)) {
            return ctype.substring(0, 2);
        }
        return null;
    }

    /** Whether {@code ctype} from {@code from} is {@code tail}, alone or followed by a dash. */
    private static boolean tailMatches(String ctype, int from, String tail) {
        if (ctype.length() < from) {
            return false;
        }
        String rest = ctype.substring(from);
        return rest.equals(tail) || rest.startsWith(tail + "-");
    }

    /**
     * The projection a celestial CTYPE names, from its last four characters when it is longer
     * than four, and otherwise CAR, made linear: C's {@code WcsCelestial} (fitschan.c:36210-36220).
     * With no {@code -TAB} axes (see {@link #buildCelestialWithDefB1950}) C's {@code AST__WCSBAD}
     * case for a short CTYPE does not arise.
     */
    private static int projectionOf(int i, String ctype, char s) {
        int ctlen = ctype.length();
        if (ctlen > 4) {
            String last4 = ctype.substring(ctlen - 4);
            int prj = wcsPrjType(last4);
            if (prj == WcsMap.AST__WCSBAD) {
                throw bdfts("FITS keyword '" + keyName("CTYPE", i + 1, s) + "' refers to an unknown projection type '"
                        + last4 + "'.");
            }
            return prj;
        }
        return WcsMap.AST__CAR;
    }

    /**
     * The celestial axes of axis description {@code s} of {@code store}, found as C's {@code
     * WcsCelestial} finds them (fitschan.c:36163-36330), or null if there are none.
     *
     * <p>A longitude CTYPE is {@code RA}, {@code AZ}, {@code xLON} or {@code yzLN}, alone or
     * followed by {@code -}, and a latitude CTYPE {@code DEC}, {@code EL}, {@code xLAT} or {@code
     * yzLT}; an axis without a CTYPE raises a {@code noctype} warning. Two longitude or two
     * latitude axes, an unknown projection, axes of different systems or projections, and one
     * axis without the other are errors.
     *
     * @throws AstException with status {@code AST__BDFTS} for a malformed pair
     */
    private static CelestialAxes findCelestialAxes(FitsStore store, char s, FitsWarnings warnings) {
        FoundAxis lon = null;
        FoundAxis lat = null;
        boolean forcedCarLin = false;
        for (int i = 0; i < store.naxis; i++) {
            String raw = store.ctype[i];
            if (raw == null) {
                warnings.push(new FitsWarnings.Warning(
                        "noctype",
                        "Axis type keywords (CTYPE, etc) were not found for one or more axes in the original FITS"
                                + " header. These axes will be assumed to be linear."));
                continue;
            }
            String ctype = raw.substring(0, FitsCard.chrLen(raw));
            String system = isAxis(ctype, new String[] {"RA", "AZ"}, "LON", "LN");
            if (system != null) {
                if (lon != null) {
                    throw bdfts("FITS keywords '" + keyName("CTYPE", i + 1, s) + "' (='" + ctype + "') and '"
                            + keyName("CTYPE", lon.axis + 1, s) + "' (='" + lon.ctype
                            + "') both describe celestial longitude axes.");
                }
                int prj = projectionOf(i, ctype, s);
                forcedCarLin |= ctype.length() <= 4;
                lon = new FoundAxis(i, system, prj, ctype);
            }
            system = isAxis(ctype, new String[] {"DEC", "EL"}, "LAT", "LT");
            if (system != null) {
                if (lat != null) {
                    throw bdfts("FITS keywords '" + keyName("CTYPE", i + 1, s) + "' (='" + ctype + "') and '"
                            + keyName("CTYPE", lat.axis + 1, s) + "' (='" + lat.ctype
                            + "') both describe celestial latitude axes.");
                }
                int prj = projectionOf(i, ctype, s);
                forcedCarLin |= ctype.length() <= 4;
                lat = new FoundAxis(i, system, prj, ctype);
            }
        }
        if (lon != null && lat != null) {
            String latKey = keyName("CTYPE", lat.axis + 1, s);
            String lonKey = keyName("CTYPE", lon.axis + 1, s);
            if (!lat.sys.equals(lon.sys)) {
                throw bdfts("FITS keywords '" + latKey + "' and '" + lonKey
                        + "' indicate different celestial coordinate systems ('" + lat.ctype + "' and '" + lon.ctype
                        + "').");
            } else if (lon.prj != lat.prj) {
                throw bdfts("FITS keywords '" + latKey + "' and '" + lonKey + "' indicate different projections ('"
                        + lat.ctype + "' and '" + lon.ctype + "').");
            }
            return new CelestialAxes(lon.axis, lat.axis, lon.prj, lon.sys, forcedCarLin);
        } else if (lat != null) {
            throw bdfts("A latitude axis ('" + lat.ctype + "') was found without a corresponding longitude axis.");
        } else if (lon != null) {
            throw bdfts("A longitude axis ('" + lon.ctype + "') was found without a corresponding latitude axis.");
        }
        return null;
    }

    /**
     * C's {@code WcsCelestial} (fitschan.c:35988): if axis description {@code s} of {@code store}
     * has a pair of celestial axes, the Mapping from their IWC to celestial coordinates and the
     * SkyFrame describing them; otherwise null. Building the Frame of all the axes from the
     * SkyFrame, setting its SkyRef, and the IWC units are left to the caller.
     *
     * <p>{@code defB1950} is the FitsChan's DefB1950 attribute, used when there is neither
     * RADESYS nor EQUINOX. {@code rfval} holds the {@code RFVAL<i>} values (degrees, by
     * zero-based FITS axis, null for none), which C reads unmarked from the cards at this point
     * (fitschan.c:36554-36559). Warnings are added to {@code warnings} as C's {@code Warn} adds
     * them, so those raised before an error are kept.
     *
     * <p>Rust's {@code tab} argument (the look-up of {@code -TAB} axes) is not taken: with TabOK
     * above zero a {@code -TAB} axis needs LutMap and the read stops before this is reached, and
     * otherwise there is no look-up, so C's {@code tabmap} is always null here, and neither its
     * projectionless {@code AST__WCSBAD} axes nor its modification of the look-up arise.
     *
     * @throws AstException with status {@code AST__BDFTS} for malformed celestial axes, an
     *     unrecognised RADESYS or an ill-conditioned native to celestial conversion, or {@code
     *     AST__BADIN} for a warning with Strict in force
     */
    static CelestialResult buildCelestialWithDefB1950(
            FitsStore store, char s, boolean defB1950, Double[] rfval, FitsWarnings warnings) {
        CelestialAxes axes = findCelestialAxes(store, s, warnings);
        if (axes == null) {
            return null;
        }
        int naxes = store.naxis;
        int axlon = axes.lon;
        int axlat = axes.lat;
        int latprj = axes.prj;

        // C keeps one local carlin, from the attribute and forced on for a projectionless CTYPE;
        // it never writes the attribute back.
        boolean carlin = store.carLin || axes.forcedCarLin;

        // The MatrixMap scaling the celestial axes from degrees to radians.
        double[] mat = new double[naxes];
        for (int i = 0; i < naxes; i++) {
            mat[i] = i == axlat || i == axlon ? AST__DD2R : 1.0;
        }
        Mapping map1 = new MatrixMap(naxes, naxes, mat);

        WcsMap map2;
        Mapping map3;
        double reflon;
        double reflat;
        if (latprj == WcsMap.AST__CAR && carlin) {
            // CAR under CarLin is a linear mapping of IWC onto celestial coordinates: a WcsMap
            // with no projection, then a WinMap adding CRVAL in radians.
            map2 = new WcsMap(naxes, WcsMap.AST__WCSBAD, axlon + 1, axlat + 1);
            double[] ina = new double[naxes];
            double[] inb = new double[naxes];
            double[] outa = new double[naxes];
            double[] outb = new double[naxes];
            for (int i = 0; i < naxes; i++) {
                ina[i] = 0.0;
                inb[i] = 1.0;
                outa[i] = 0.0;
                outb[i] = 1.0;
            }
            reflon = store.crval[axlon] != null ? store.crval[axlon] * AST__DD2R : AST__BAD;
            reflat = store.crval[axlat] != null ? store.crval[axlat] * AST__DD2R : AST__BAD;
            addOrBad(outa, outb, axlon, reflon);
            addOrBad(outa, outb, axlat, reflat);
            map3 = new WinMap(naxes, ina, inb, outa, outb);
        } else {
            map2 = new WcsMap(naxes, latprj, axlon + 1, axlat + 1);
            setProjectionParameters(store, map2, axlon, axlat, warnings);
            map2.invert();
            map3 = wcsNative(store, map2, -1, -1, warnings);
            reflon = store.crval[axlon] != null ? store.crval[axlon] * AST__DD2R : AST__BAD;
            reflat = store.crval[axlat] != null ? store.crval[axlat] * AST__DD2R : AST__BAD;
        }

        // A non-zero parameter 0 on the longitude axis moves the fiducial point away from the IWC
        // origin, so the IWC are shifted onto the projection plane coordinates of the fiducial
        // point (fitschan.c:36491-36520). TPN uses the parameter for a polynomial term.
        if (map2.getWcsType() != WcsMap.AST__TPN && map2.getPV(axlon, 0) != 0.0) {
            double[] ppc = getFiducialPpc(map2);
            if (ppc[0] != AST__BAD && ppc[1] != AST__BAD) {
                double[] shifts = new double[naxes];
                shifts[axlon] = ppc[0];
                shifts[axlat] = ppc[1];
                map1 = new CmpMap(map1, new ShiftMap(shifts), true);
            }
        }
        Mapping map4 = new CmpMap(map1, map2, true);
        Mapping ret = new CmpMap(map4, map3, true);

        SkyFrame sfrm = wcsSkyFrame(store, s, latprj, axes.sys, axlon, axlat, defB1950, warnings);

        // RFVAL<i>, the CRVAL values before SpecTrans translated an old projection, override
        // CRVAL (fitschan.c:36543-36559); AXREF<i> overrides both, and is set as SkyRef whatever
        // SkyRef the SkyFrame has (fitschan.c:36677-36686).
        if (rfval != null && rfval[axlon] != null && rfval[axlat] != null) {
            reflon = rfval[axlon] * AST__DD2R;
            reflat = rfval[axlat] * AST__DD2R;
        }
        boolean refPosForced = false;
        if (store.axref[axlon] != null && store.axref[axlat] != null) {
            reflon = store.axref[axlon] * AST__DD2R;
            reflat = store.axref[axlat] * AST__DD2R;
            refPosForced = true;
        }
        double[] refPos = reflon != AST__BAD && reflat != AST__BAD ? new double[] {reflon, reflat} : null;

        // C builds the Frame of all the axes with the SkyFrame's axes in FITS order; the SkyFrame
        // returned here has its axes in the order the Mapping delivers them.
        if (axlat < axlon) {
            sfrm.permAxes(new int[] {2, 1});
        }
        return new CelestialResult(ret, sfrm, axlon, axlat, refPos, refPosForced);
    }

    /** Adds {@code val} to element {@code axis} of {@code outa} and {@code outb}, or makes both bad. */
    private static void addOrBad(double[] outa, double[] outb, int axis, double val) {
        if (val != AST__BAD) {
            outa[axis] += val;
            outb[axis] += val;
        } else {
            outa[axis] = AST__BAD;
            outb[axis] = AST__BAD;
        }
    }

    /**
     * Stores the {@code PVi_m} projection parameters of the celestial axes on {@code map2}, as
     * C's {@code WcsCelestial} does (fitschan.c:36431-36464): one ascending pass over {@code m}
     * up to the highest index stored on any axis, the latitude axis before the longitude axis at
     * each index, with a {@code badpv} warning for a parameter the projection does not use.
     */
    private static void setProjectionParameters(
            FitsStore store, WcsMap map2, int axlon, int axlat, FitsWarnings warnings) {
        int np = store.maxPvM();
        int mxparLat = map2.getPVMax(axlat);
        int mxparLon = map2.getPVMax(axlon);
        String prjName = WcsMap.wcsPrjName(map2.getWcsType());
        for (int m = 0; m <= np; m++) {
            setOrWarn(store, map2, axlat, m, mxparLat, prjName, warnings);
            setOrWarn(store, map2, axlon, m, mxparLon, prjName, warnings);
        }
    }

    /** Sets parameter {@code m} of {@code axis} from the store, or warns that it is not used. */
    private static void setOrWarn(
            FitsStore store, WcsMap map2, int axis, int m, int mxpar, String prjName, FitsWarnings warnings) {
        Double pv = m < store.pv[axis].length ? store.pv[axis][m] : null;
        if (pv == null) {
            return;
        }
        if (m <= mxpar) {
            map2.setPV(axis, m, pv);
        } else {
            warnings.push(new FitsWarnings.Warning(
                    "badpv",
                    "Projection parameter PV" + (axis + 1) + "_" + m + " found, but is not used by " + prjName
                            + " projections."));
        }
    }

    /**
     * C's {@code GetFiducialPPC} (fitschan.c): the projection plane coordinates, in radians, of
     * the fiducial point of {@code map}, an inverted WcsMap, as {@code {x0, y0}}; found by
     * transforming the native spherical coordinates of the fiducial point through the inverse of
     * {@code map}, with zero on the other axes.
     */
    private static double[] getFiducialPpc(WcsMap map) {
        int naxes = map.getNin();
        int axlon = map.getWcsAxis(1) - 1;
        int axlat = map.getWcsAxis(2) - 1;
        double[][] in = new double[naxes][1];
        double[] nsc = FitsFiducial.getFiducialNsc(map);
        in[axlon][0] = nsc[0];
        in[axlat][0] = nsc[1];
        double[][] out = map.tranP(1, naxes, in, false, naxes);
        return new double[] {out[axlon][0], out[axlat][0]};
    }

    /**
     * C's {@code WcsNative} (fitschan.c:38518): the Mapping from native spherical coordinates to
     * celestial coordinates for the inverted WcsMap {@code wcsmap}, leaving any other axes
     * unchanged.
     *
     * <p>In series: a PermMap putting the longitude and latitude axes first (unless they are
     * already), the rotation in parallel with a UnitMap for any other axes, and an inverted copy
     * of the PermMap. The rotation is a SphMap to Cartesian coordinates, a MatrixMap made by
     * three {@code astMtrRot} rotations (by -LONPOLE about Z, by the pole latitude less pi/2
     * about Y, and by pi plus the pole longitude about Z), and a SphMap back.
     *
     * <p>The fiducial latitude (CRVAL of {@code fitsIlat}, or of the WcsMap's latitude axis for
     * -1) is limited to +/- pi/2, with a {@code badlat} warning. LONPOLE and LATPOLE are parameters
     * 3 and 4 of the longitude axis when set (except for TPN), and otherwise the store's, which
     * are then set as those parameters.
     *
     * @throws AstException with status {@code AST__BDFTS} if {@link FitsNativePole#wcsNatPole}
     *     cannot place the native pole
     */
    private static Mapping wcsNative(
            FitsStore store, WcsMap wcsmap, int fitsIlon, int fitsIlat, FitsWarnings warnings) {
        int naxis = wcsmap.getNin();
        int axlon = wcsmap.getWcsAxis(1) - 1;
        int axlat = wcsmap.getWcsAxis(2) - 1;
        int ilon = fitsIlon == -1 ? axlon : fitsIlon;
        int ilat = fitsIlat == -1 ? axlat : fitsIlat;
        if (axlon == axlat || wcsmap.getWcsType() == WcsMap.AST__WCSBAD) {
            return new UnitMap(naxis);
        }

        // The forward and inverse permutations putting the longitude axis at 0 and the latitude
        // axis at 1.
        int[] outperm = new int[naxis];
        int[] inperm = new int[naxis];
        for (int i = 0; i < naxis; i++) {
            outperm[i] = i;
        }
        int t = outperm[axlon];
        outperm[axlon] = outperm[0];
        outperm[0] = t;
        int newAxlat = axlat == 0 ? axlon : axlat;
        t = outperm[newAxlat];
        outperm[newAxlat] = outperm[1];
        outperm[1] = t;
        for (int i = 0; i < naxis; i++) {
            inperm[outperm[i]] = i;
        }

        // The fiducial point in the standard system, in radians, its latitude limited to +/- pi/2.
        double delta0 = store.crval[ilat] != null ? store.crval[ilat] : 0.0;
        delta0 *= AST__DD2R;
        double alpha0 = store.crval[ilon] != null ? store.crval[ilon] : 0.0;
        alpha0 *= AST__DD2R;
        double unlimited = Pal.drange(delta0);
        delta0 = unlimited;
        if (delta0 > AST__DPIBY2) {
            delta0 = AST__DPIBY2;
        } else if (delta0 < -AST__DPIBY2) {
            delta0 = -AST__DPIBY2;
        }
        if (unlimited != delta0) {
            warnings.push(new FitsWarnings.Warning(
                    "badlat",
                    "The original FITS header specified a fiducial point with latitude "
                            + CFormat.format(G15, unlimited * AST__DR2D) + ". A value of "
                            + CFormat.format(G15, delta0 * AST__DR2D) + " is being used instead. "));
        }

        // LONPOLE and LATPOLE, from parameters 3 and 4 of the longitude axis if set, and
        // otherwise from the store, then stored as those parameters; not for TPN, whose
        // parameters are polynomial terms.
        boolean tpn = wcsmap.getWcsType() == WcsMap.AST__TPN;
        double phip;
        if (wcsmap.testPV(axlon, 3) && !tpn) {
            phip = wcsmap.getPV(axlon, 3);
        } else {
            phip = store.lonpole != null ? store.lonpole : AST__BAD;
            if (phip != AST__BAD && !tpn) {
                wcsmap.setPV(axlon, 3, phip);
            }
        }
        if (phip != AST__BAD) {
            phip *= AST__DD2R;
        }
        double latpole;
        if (wcsmap.testPV(axlon, 4) && !tpn) {
            latpole = wcsmap.getPV(axlon, 4);
        } else {
            latpole = store.latpole != null ? store.latpole : AST__BAD;
            if (latpole != AST__BAD && !tpn) {
                wcsmap.setPV(axlon, 4, latpole);
            }
        }
        if (latpole != AST__BAD) {
            latpole *= AST__DD2R;
        }

        FitsNativePole.NativePole np = FitsNativePole.wcsNatPole(
                wcsmap, alpha0, delta0, latpole != AST__BAD ? latpole : null, phip != AST__BAD ? phip : null, warnings);
        if (np.pole == null) {
            throw bdfts("Conversion from FITS WCS native coordinates to celestial coordinates is ill-conditioned.");
        }
        phip = np.phip;
        double alphap = np.pole[0];
        double deltap = np.pole[1];

        // Spherical to Cartesian, on the unit sphere, giving the native longitude of the fiducial
        // point at either pole.
        SphMap sphmap = new SphMap();
        sphmap.setUnitRadius(true);
        sphmap.invert();
        double phi0 = FitsFiducial.getFiducialNsc(wcsmap)[0];
        sphmap.setPolarLong(phi0);

        // The rotation of native into celestial Cartesian coordinates.
        MatrixMap matmap = new MatrixMap(3, 3);
        matmap = matmap.mtrRot(-phip, new double[] {0.0, 0.0, 1.0});
        matmap = matmap.mtrRot(deltap - AST__DPIBY2, new double[] {0.0, 1.0, 0.0});
        matmap = matmap.mtrRot(AST__DPI + alphap, new double[] {0.0, 0.0, 1.0});
        Mapping cmpmap = new CmpMap(sphmap, matmap, true);

        // Cartesian to spherical, giving the celestial longitude of the fiducial point at either
        // pole.
        SphMap sphmap2 = new SphMap();
        sphmap2.setUnitRadius(true);
        sphmap2.setPolarLong(alpha0);
        Mapping result = new CmpMap(cmpmap, sphmap2, true);

        int naxRem = naxis - 2;
        if (naxRem > 0) {
            result = new CmpMap(result, new UnitMap(naxRem), false);
        }

        if (axlon != 0 || axlat != 1) {
            PermMap permmap = PermMap.fromInternal(naxis, inperm, naxis, outperm, null);
            result = new CmpMap(permmap, result, true);
            // A copy is inverted, since the CmpMap shares the original.
            PermMap permmap2 = (PermMap) permmap.copy();
            permmap2.invert();
            result = new CmpMap(result, permmap2, true);
        }
        return result;
    }

    /**
     * C's {@code WcsSkyFrame} (fitschan.c:39555): the SkyFrame for the celestial axes {@code
     * axlon} and {@code axlat} (zero-based) of version {@code s}, of the system {@code sys} that
     * {@code WcsCelestial} names and projection {@code prj}.
     *
     * <p>RADESYS and EQUINOX, failing those the IAU 1984 rule, and failing both {@code defB1950},
     * decide an equatorial system and the equinox; the date of observation is {@link
     * FitsEpoch#chooseEpoch}, failing that the equinox. An unknown system is a general spherical
     * one, with axis symbols made from its code. SkyRefIs, which can only be set once the Frame is
     * in its FrameSet, is marked by a Domain of {@code SKY_POLE} or {@code SKY_ORIGIN}.
     *
     * @throws AstException with status {@code AST__BDFTS} for an unrecognised RADESYS
     */
    private static SkyFrame wcsSkyFrame(
            FitsStore store,
            char s,
            int prj,
            String sys,
            int axlon,
            int axlat,
            boolean defB1950,
            FitsWarnings warnings) {
        String ckeyval = store.radesys;
        int radesys = NORADEC;
        if (ckeyval != null) {
            if (ckeyval.startsWith("FK4 ") || "FK4".equals(ckeyval)) {
                radesys = FK4;
            } else if (ckeyval.startsWith("FK4-NO-E")) {
                radesys = FK4NOE;
            } else if (ckeyval.startsWith("FK5 ") || "FK5".equals(ckeyval)) {
                radesys = FK5;
            } else if (ckeyval.startsWith("ICRS ") || "ICRS".equals(ckeyval)) {
                radesys = ICRS;
            } else if (ckeyval.startsWith("GAPPT ") || "GAPPT".equals(ckeyval)) {
                radesys = GAPPT;
            } else {
                throw bdfts("FITS keyword '" + (s == ' ' ? "RADESYS" : "RADESYS" + s) + "' has the unrecognised value '"
                        + ckeyval + "'.");
            }
        }

        // The equinox, and whether it is a Besselian ('B') or Julian ('J') epoch.
        double equinox = store.equinox != null ? store.equinox : AST__BAD;
        char bj = 0;
        if (equinox != AST__BAD) {
            if (radesys == FK4 || radesys == FK4NOE) {
                bj = 'B';
            } else if (radesys != NORADEC) {
                bj = 'J';
            } else {
                // No RADESYS: the IAU 1984 rule.
                if (equinox < 1984.0) {
                    radesys = FK4;
                    bj = 'B';
                } else {
                    radesys = FK5;
                    bj = 'J';
                }
                if ("EQU".equals(sys)) {
                    warnings.push(new FitsWarnings.Warning(
                            "noradesys",
                            "The original FITS header did not specify the RA/DEC reference frame. A default value"
                                    + " of " + (radesys == FK4 ? "FK4" : "FK5") + " was assumed."));
                }
            }
        } else {
            if (radesys == FK4 || radesys == FK4NOE) {
                equinox = 1950.0;
                bj = 'B';
            } else if (radesys == FK5) {
                equinox = 2000.0;
                bj = 'J';
            } else if (radesys == NORADEC) {
                if (defB1950) {
                    equinox = 1950.0;
                    bj = 'B';
                    radesys = FK4;
                } else {
                    radesys = ICRS;
                }
                if ("EQU".equals(sys)) {
                    warnings.push(new FitsWarnings.Warning(
                            "noradesys",
                            "The original FITS header did not specify the RA/DEC reference frame. A default value"
                                    + " of " + (radesys == FK4 ? "FK4" : "ICRS") + " was assumed."));
                }
            }
            if (("EQU".equals(sys) && radesys != ICRS && radesys != GAPPT)
                    || ("E".equals(sys) && equinox != AST__BAD)) {
                warnings.push(new FitsWarnings.Warning(
                        "noequinox",
                        "The original FITS header did not specify the reference equinox. A default value of " + bj
                                + CFormat.format("%.8g", equinox) + " was assumed."));
            }
        }
        double eqmjd;
        if (equinox != AST__BAD) {
            eqmjd = bj == 'B' ? Pal.epb2d(equinox) : Pal.epj2d(equinox);
        } else {
            eqmjd = AST__BAD;
        }

        // The date of observation, defaulting to the equinox.
        Double chosen = FitsEpoch.chooseEpoch(store, warnings);
        double mjdobs = chosen != null ? chosen : AST__BAD;
        if (mjdobs == AST__BAD) {
            mjdobs = eqmjd;
            if (mjdobs != AST__BAD) {
                warnings.push(new FitsWarnings.Warning(
                        "nomjd-obs",
                        "The original FITS header did not specify the date of observation. A default value of " + bj
                                + CFormat.format("%.8g", equinox) + " was assumed."));
            }
        }

        SkyFrame ret = new SkyFrame();
        // MODERN-JAVA(21): switch expression over the system code
        if ("E".equals(sys)) {
            ret.setSystem("Ecliptic");
        } else if ("H".equals(sys)) {
            ret.setSystem("Helioecliptic");
        } else if ("G".equals(sys)) {
            ret.setSystem("Galactic");
        } else if ("S".equals(sys)) {
            ret.setSystem("Supergalactic");
        } else if ("AZL".equals(sys)) {
            ret.setSystem("AzEl");
        } else if ("EQU".equals(sys)) {
            // MODERN-JAVA(21): switch expression over the RADESYS code
            if (radesys == FK4) {
                ret.setSystem("FK4");
            } else if (radesys == FK4NOE) {
                ret.setSystem("FK4-NO-E");
            } else if (radesys == FK5) {
                ret.setSystem("FK5");
            } else if (radesys == ICRS) {
                ret.setSystem("ICRS");
            } else if (radesys == GAPPT) {
                ret.setSystem("GAPPT");
            } else {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astRead(FitsChan): Internal AST programming error - FITS equatorial coordinate system type "
                                + radesys + " not yet supported in WcsSkyFrame.");
            }
        } else {
            // An unknown system: a general spherical one.
            boolean report = true;
            ret.setSystem("UNKNOWN");
            if (sys.length() == 1) {
                ret.setSymbol(1, sys + "LON");
                ret.setSymbol(2, sys + "LAT");
            } else {
                ret.setSymbol(1, sys + "LN");
                ret.setSymbol(2, sys + "LT");
                // AST describes offset sky coordinates with the code "OF".
                if ("OF".equals(sys)) {
                    ret.setDomain("SKY_OFFSETS");
                    report = false;
                }
            }
            String lontype = store.ctype[axlon];
            String lattype = store.ctype[axlat];
            if (report && lontype != null && lattype != null) {
                warnings.push(
                        new FitsWarnings.Warning(
                                "badcel",
                                "This FITS header contains references to an unknown spherical co-ordinate system specified" // codespell:ignore
                                        + " in the values " + lontype + " and " + lattype
                                        + ". It may not be possible to convert to other standard co-ordinate systems.")); // codespell:ignore
            }
        }

        if (prj != WcsMap.AST__WCSBAD) {
            ret.setProjection(WcsMap.wcsPrjDesc(prj));
        }
        if (mjdobs != AST__BAD) {
            ret.setEpochMjd(mjdobs);
        }
        if (("EQU".equals(sys) || "E".equals(sys)) && equinox != AST__BAD) {
            ret.setEquinoxMjd(eqmjd);
        }
        if (store.cname[axlon] != null) {
            ret.setLabel(1, store.cname[axlon]);
        }
        if (store.cname[axlat] != null) {
            ret.setLabel(2, store.cname[axlat]);
        }

        // The observer's position, from the primary axis description's OBSGEO-X/Y/Z.
        if (store.obsgeox != null && store.obsgeoy != null && store.obsgeoz != null) {
            double[] geo = Pal.gc2gd(new double[] {store.obsgeox, store.obsgeoy, store.obsgeoz});
            if (geo != null) {
                ret.setObsLatValue(geo[1]);
                ret.setObsLonValue(geo[0]);
                ret.setObsAlt(geo[2]);
            }
        }

        if (store.skyref[axlon] != null) {
            ret.setSkyRef(1, store.skyref[axlon]);
        }
        if (store.skyref[axlat] != null) {
            ret.setSkyRef(2, store.skyref[axlat]);
        }
        if (store.skyrefp[axlon] != null) {
            ret.setSkyRefP(1, store.skyrefp[axlon]);
        }
        if (store.skyrefp[axlat] != null) {
            ret.setSkyRefP(2, store.skyrefp[axlat]);
        }

        // SkyRefIs can only be set once the Frame is in its FrameSet, so the Frame is marked by its
        // Domain, which C's TidyOffsets clears.
        if (store.skyrefis != null) {
            if ("POLE".equalsIgnoreCase(store.skyrefis)) {
                ret.setDomain("SKY_POLE");
            } else if ("ORIGIN".equalsIgnoreCase(store.skyrefis)) {
                ret.setDomain("SKY_ORIGIN");
            }
        }
        return ret;
    }
}
