package uk.ac.starlink.ast;

import java.util.Locale;

/**
 * The FITS-WCS keyword values of one axis description, extracted from a header in a structured
 * form for the read pipeline, as C's {@code FitsStore} structure holds them.
 *
 * <p>Axis-indexed arrays are indexed from 0 (axis 1 is at index 0); {@link #naxis} is the number
 * of WCS axes. A null element is a keyword the header does not give.
 *
 * <p>Ported from Rust {@code fitschan/fits_store.rs}.
 */
final class FitsStore {

    /** The number of PV (and PS) parameters held for each axis. */
    static final int MAX_PV_PARAMS = 40;

    /** The number of WCS axes. */
    int naxis;

    /** CRPIXi: the reference pixel of each axis. */
    Double[] crpix;

    /** CRVALi: the reference value of each axis. */
    Double[] crval;

    /** CDELTi: the pixel scale of each axis. */
    Double[] cdelt;

    /**
     * NAXISi: the image dimension of each axis, when known; the {@code dim} array C passes through
     * {@code WcsMapFrm} and {@code SIPMapping} for the sampling bounds of {@code astPolyTran}.
     */
    Double[] dims;

    /** The SipReplace attribute of the reading FitsChan (C default 1). */
    boolean sipReplace = true;

    /**
     * The CarLin attribute of the reading FitsChan (C default 0): read {@code -CAR} projections as
     * a linear pixel to sky Mapping.
     */
    boolean carLin;

    /** PCi_j: the rotation matrix, indexed [row][column]. */
    Double[][] pc;

    /** CDi_j: the CD matrix, indexed [row][column]. */
    Double[][] cd;

    /** CTYPEi: the axis type of each axis, with any distortion code removed. */
    String[] ctype;

    /**
     * The comment for the CTYPE of each axis; when null a writer uses C's default comment, as C's {@code store->ctype_com}.
     */
    String[] ctypeCom;

    /** CUNITi: the units of each axis. */
    String[] cunit;

    /** CNAMEi: the name of each axis. */
    String[] cname;

    /** PVi_m: the projection parameters of each axis, indexed [axis][parameter]. */
    Double[][] pv;

    /**
     * PSi_m: the string parameters of each axis, indexed [axis][parameter]; for a {@code -TAB}
     * axis, the extension name and the columns of its look-up table.
     */
    String[][] ps;

    /** RADESYS: the reference frame of celestial coordinates. */
    String radesys;

    /** EQUINOX: the equinox of the celestial coordinate system. */
    Double equinox;

    /** LONPOLE: the native longitude of the celestial pole. */
    Double lonpole;

    /** LATPOLE: the native latitude of the celestial pole. */
    Double latpole;

    /**
     * The Modified Julian Date of the observation: MJD-AVG, or MJD-OBS when there is none, or the
     * date DATE-OBS gives when there is neither.
     */
    Double mjdobs;

    /**
     * TIMESYS: the time scale of {@link #mjdobs} and {@link #dateobs}, as the upper-case value of
     * the keyword; null when absent, which FITS takes to be UTC.
     */
    String timesys;

    /** DATE-OBS: the formatted date of the observation. */
    String dateobs;

    /** SPECSYS: the spectral reference frame. */
    String specsys;

    /** RESTFRQ: the rest frequency in Hz. */
    Double restfrq;

    /** RESTWAV: the rest wavelength in m. */
    Double restwav;

    /** VELOSYS: the topocentric apparent velocity of the rest frame, in m/s. */
    Double velosys;

    /** WCSAXES: the number of WCS axes the header declares. */
    Integer wcsaxes;

    /**
     * Whether both of the first two CTYPE values carried a {@code -SIP} distortion code. C tests
     * the CTYPE values in {@code DistortMaps} (fitschan.c:8919-8942) before truncating them; this
     * keeps the answer for the later step, which sees only the truncated values.
     */
    boolean sip;

    /** A_ORDER (write side only; a read takes the powers from the coefficients, as C does). */
    Integer sipAOrder;

    /** B_ORDER (write side only). */
    Integer sipBOrder;

    /** SIP A coefficients, indexed [i][j]; an absent element holds {@code AST__BAD}. */
    double[][] sipA = new double[0][];

    /** SIP B coefficients, indexed [i][j]. */
    double[][] sipB = new double[0][];

    /** AP_ORDER (write side only). */
    Integer sipApOrder;

    /** BP_ORDER (write side only). */
    Integer sipBpOrder;

    /** SIP AP coefficients (inverse), indexed [i][j]. */
    double[][] sipAp = new double[0][];

    /** SIP BP coefficients (inverse), indexed [i][j]. */
    double[][] sipBp = new double[0][];

    /** The SkyRefIs value of an offset SkyFrame: {@code POLE}, {@code ORIGIN} or {@code IGNORED}. */
    String skyrefis;

    /** SkyRef: the reference point of each axis, in radians, in FITS axis order. */
    Double[] skyref;

    /** SkyRefP: the reference pole of each axis. */
    Double[] skyrefp;

    /**
     * AXREFi: the reference position, in degrees, of each axis; AST uses it on axes described by
     * {@code -TAB} that have no inverse, and on read it overrides the reference position taken
     * from CRVAL and forces SkyRef to be set (fitschan.c:36338-36352).
     */
    Double[] axref;

    /** WCSNAME: the name of the axis description. */
    String wcsname;

    /** OBSGEO-X: the geocentric X position of the observer, in m. */
    Double obsgeox;

    /** OBSGEO-Y: the geocentric Y position of the observer, in m. */
    Double obsgeoy;

    /** OBSGEO-Z: the geocentric Z position of the observer, in m. */
    Double obsgeoz;

    /** ZSOURCE: the redshift of the source. */
    Double zsource;

    /** SSYSSRC: the rest frame in which the source velocity is given. */
    String ssyssrc;

    /** IMAGFREQ: the image sideband frequency of a double sideband spectral axis. */
    Double imagfreq;

    /** DTAI: TAI - UTC, in seconds. */
    Double dtai;

    /** DUT1: UT1 - UTC, in days. */
    Double dut1;

    /** A store for {@code naxis} axes with no keyword values. */
    FitsStore(int naxis) {
        this.naxis = naxis;
        crpix = new Double[naxis];
        crval = new Double[naxis];
        cdelt = new Double[naxis];
        dims = new Double[naxis];
        pc = new Double[naxis][naxis];
        cd = new Double[naxis][naxis];
        ctype = new String[naxis];
        ctypeCom = new String[naxis];
        cunit = new String[naxis];
        cname = new String[naxis];
        pv = new Double[naxis][MAX_PV_PARAMS];
        ps = new String[naxis][MAX_PV_PARAMS];
        skyref = new Double[naxis];
        skyrefp = new Double[naxis];
        axref = new Double[naxis];
    }

    /** A copy of this store, sharing no arrays with it. */
    FitsStore copy() {
        FitsStore c = new FitsStore(naxis);
        c.crpix = crpix.clone();
        c.crval = crval.clone();
        c.cdelt = cdelt.clone();
        c.dims = dims.clone();
        c.sipReplace = sipReplace;
        c.carLin = carLin;
        c.pc = copyOf(pc);
        c.cd = copyOf(cd);
        c.ctype = ctype.clone();
        c.ctypeCom = ctypeCom.clone();
        c.cunit = cunit.clone();
        c.cname = cname.clone();
        c.pv = copyOf(pv);
        c.ps = copyOf(ps);
        c.radesys = radesys;
        c.equinox = equinox;
        c.lonpole = lonpole;
        c.latpole = latpole;
        c.mjdobs = mjdobs;
        c.timesys = timesys;
        c.dateobs = dateobs;
        c.specsys = specsys;
        c.restfrq = restfrq;
        c.restwav = restwav;
        c.velosys = velosys;
        c.wcsaxes = wcsaxes;
        c.sip = sip;
        c.sipAOrder = sipAOrder;
        c.sipBOrder = sipBOrder;
        c.sipA = copyOf(sipA);
        c.sipB = copyOf(sipB);
        c.sipApOrder = sipApOrder;
        c.sipBpOrder = sipBpOrder;
        c.sipAp = copyOf(sipAp);
        c.sipBp = copyOf(sipBp);
        c.skyrefis = skyrefis;
        c.skyref = skyref.clone();
        c.skyrefp = skyrefp.clone();
        c.axref = axref.clone();
        c.wcsname = wcsname;
        c.obsgeox = obsgeox;
        c.obsgeoy = obsgeoy;
        c.obsgeoz = obsgeoz;
        c.zsource = zsource;
        c.ssyssrc = ssyssrc;
        c.imagfreq = imagfreq;
        c.dtai = dtai;
        c.dut1 = dut1;
        return c;
    }

    private static Double[][] copyOf(Double[][] a) {
        Double[][] c = new Double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }

    private static String[][] copyOf(String[][] a) {
        String[][] c = new String[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }

    private static double[][] copyOf(double[][] a) {
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }

    /** Whether any PC matrix element is set. */
    boolean hasPcMatrix() {
        for (Double[] row : pc) {
            for (Double v : row) {
                if (v != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the header declared {@code -SIP} distortion on both of the first two axes, whether
     * or not it gave any coefficients: C's {@code DistortMaps} calls {@code SIPMapping} on the
     * strength of the CTYPE codes alone (fitschan.c:8931), and {@code SIPMapping} gives a UnitMap
     * when the store holds no coefficients.
     */
    boolean hasSip() {
        return sip;
    }

    /**
     * The longitude and latitude axes, numbered from 0, that the CTYPE values name, the last of
     * each kind; null unless both are found.
     */
    int[] celestialAxes() {
        int lonAxis = -1;
        int latAxis = -1;
        for (int i = 0; i < ctype.length; i++) {
            if (ctype[i] != null) {
                String upper = ctype[i].toUpperCase(Locale.ROOT);
                if (isLongitudeCtype(upper)) {
                    lonAxis = i;
                } else if (isLatitudeCtype(upper)) {
                    latAxis = i;
                }
            }
        }
        return lonAxis >= 0 && latAxis >= 0 ? new int[] {lonAxis, latAxis} : null;
    }

    /** C's {@code GetMaxJM( &(store->crpix), ... )}: the highest axis with a CRPIX, or -1. */
    int maxCrpixAxis() {
        for (int i = crpix.length - 1; i >= 0; i--) {
            if (crpix[i] != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * C's {@code GetMaxJM( &(store->pv), ... )}: the highest PV parameter index stored for any
     * axis, or -1.
     */
    int maxPvM() {
        int max = -1;
        for (Double[] axis : pv) {
            for (int m = axis.length - 1; m > max; m--) {
                if (axis[m] != null) {
                    max = m;
                    break;
                }
            }
        }
        return max;
    }

    /**
     * The CD matrix element (i, j) as C's writers form it, {@code PCi_j} times {@code CDELTi}, with
     * the unit matrix and a CDELT of 1 for unset values.
     */
    double cdElement(int i, int j) {
        double cdel = i < cdelt.length && cdelt[i] != null ? cdelt[i] : 1.0;
        Double p = i < pc.length && j < pc[i].length ? pc[i][j] : null;
        double pcv = p != null ? p : (i == j ? 1.0 : 0.0);
        return pcv * cdel;
    }

    /**
     * C's {@code FindLonLatSpecAxes} (fitschan.c:10822): the longitude, latitude and spectral axes,
     * numbered from 0 (-1 for none), among the first WCSAXES (or NAXIS) CTYPE values. A spectral
     * axis is one {@code IsSpectral} or {@code IsAIPSSpectral} recognises; a celestial axis has
     * {@code -} as its fifth character, and is a longitude axis for {@code RA--}, {@code AZ--},
     * {@code xLON} and {@code xyLN}, and a latitude axis for {@code DEC-}, {@code EL--}, {@code
     * xLAT} and {@code xyLT}. The last axis of each kind is the one given.
     */
    int[] lonLatSpecAxes() {
        int axlon = -1;
        int axlat = -1;
        int axspec = -1;
        int n = wcsaxes != null ? wcsaxes : naxis;
        for (int i = 0; i < n; i++) {
            String ct = i < ctype.length ? ctype[i] : null;
            if (ct == null) {
                continue;
            }
            if (isSpectral(ct) != null || FitsGuessEncoding.isAipsSpectral(ct) != null) {
                axspec = i;
            } else if (ct.length() > 4 && ct.charAt(4) == '-') {
                if (ct.startsWith("RA--")
                        || ct.startsWith("AZ--")
                        || "LON".equals(ct.substring(1, 4))
                        || "LN".equals(ct.substring(2, 4))) {
                    axlon = i;
                } else if (ct.startsWith("DEC-")
                        || ct.startsWith("EL--")
                        || "LAT".equals(ct.substring(1, 4))
                        || "LT".equals(ct.substring(2, 4))) {
                    axlat = i;
                }
            }
        }
        return new int[] {axlon, axlat, axspec};
    }

    /** The first axis, numbered from 0, whose CTYPE {@link #isSpectralCtype} accepts, or -1. */
    int spectralAxis() {
        for (int i = 0; i < ctype.length; i++) {
            if (ctype[i] != null && isSpectralCtype(ctype[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * C's {@code EQUALANG} (fitschan.c:1596): two angles both bad, or within the larger of 1.0E5
     * times their summed magnitudes times the machine precision and 1.0E-9.
     */
    static boolean equalAng(double a, double b) {
        if (a == AstObject.AST__BAD || b == AstObject.AST__BAD) {
            return a == AstObject.AST__BAD && b == AstObject.AST__BAD;
        }
        return Math.abs(a - b) <= Math.max(1.0e5 * (Math.abs(a) + Math.abs(b)) * Math.ulp(1.0), 1.0e-9);
    }

    /**
     * C's {@code !strncmp( cval, target, astChrLen( cval ) )}: whether {@code cval}, without its
     * trailing blanks, is the start of {@code target}.
     */
    static boolean namesStartOf(String cval, String target) {
        int n = cval.length();
        while (n > 0 && cval.charAt(n - 1) == ' ') {
            n--;
        }
        return n <= target.length() && target.regionMatches(0, cval, 0, n);
    }

    /**
     * C's {@code IsSpectral} (fitschan.c:20576): the default unit of the spectral system a CTYPE
     * names in its first four characters (trailing spaces ignored), when any algorithm code after
     * them (at most four characters, trailing spaces ignored) is blank or starts with {@code -};
     * otherwise null.
     */
    static String isSpectral(String ctype) {
        if (ctype.length() < 4) {
            return null;
        }
        String stype = trimTrailingSpaces(ctype.substring(0, 4));
        String rest = ctype.substring(4);
        String algcode = trimTrailingSpaces(rest.length() > 4 ? rest.substring(0, 4) : rest);
        String unit;
        // MODERN-JAVA(21): switch expression
        switch (stype) {
            case "FREQ":
                unit = "Hz";
                break;
            case "ENER":
                unit = "J";
                break;
            case "WAVN":
                unit = "/m";
                break;
            case "VRAD":
            case "VOPT":
            case "VELO":
                unit = "m/s";
                break;
            case "WAVE":
            case "AWAV":
                unit = "m";
                break;
            case "ZOPT":
            case "BETA":
                unit = "";
                break;
            default:
                return null;
        }
        return algcode.isEmpty() || algcode.charAt(0) == '-' ? unit : null;
    }

    private static String trimTrailingSpaces(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == ' ') {
            n--;
        }
        return s.substring(0, n);
    }

    /**
     * C's {@code GetSkyAxes} test for a longitude axis (fitschan.c:10809-10819): {@code -} as the
     * fifth character, and {@code RA--}, {@code AZ--}, {@code xLON} or {@code xyLN}.
     */
    private static boolean isLongitudeCtype(String ctype) {
        String ct = FitsCard.trim(ctype);
        if (ct.length() <= 4 || ct.charAt(4) != '-') {
            return false;
        }
        return ct.startsWith("RA--")
                || ct.startsWith("AZ--")
                || "LON".equals(ct.substring(1, 4))
                || "LN".equals(ct.substring(2, 4));
    }

    /**
     * C's {@code GetSkyAxes} test for a latitude axis (fitschan.c:10821-10825): {@code -} as the
     * fifth character, and {@code DEC-}, {@code EL--}, {@code xLAT} or {@code xyLT}.
     */
    private static boolean isLatitudeCtype(String ctype) {
        String ct = FitsCard.trim(ctype);
        if (ct.length() <= 4 || ct.charAt(4) != '-') {
            return false;
        }
        return ct.startsWith("DEC-")
                || ct.startsWith("EL--")
                || "LAT".equals(ct.substring(1, 4))
                || "LT".equals(ct.substring(2, 4));
    }

    /**
     * Whether a CTYPE names a spectral axis in its first four characters: {@code FREQ}, {@code
     * ENER}, {@code WAVN}, {@code VOPT}, {@code ZOPT}, {@code VELO}, {@code VRAD}, {@code WAVE},
     * {@code AWAV} or {@code BETA}, in any case.
     */
    static boolean isSpectralCtype(String ctype) {
        String ct = FitsCard.trim(ctype).toUpperCase(Locale.ROOT);
        String prefix = ct.length() >= 4 ? ct.substring(0, 4) : ct;
        // MODERN-JAVA(21): switch expression
        switch (prefix) {
            case "FREQ":
            case "ENER":
            case "WAVN":
            case "VOPT":
            case "ZOPT":
            case "VELO":
            case "VRAD":
            case "WAVE":
            case "AWAV":
            case "BETA":
                return true;
            default:
                return false;
        }
    }
}
