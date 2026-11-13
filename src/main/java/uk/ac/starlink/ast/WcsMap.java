package uk.ac.starlink.ast;

import java.util.Arrays;
import uk.ac.starlink.ast.internal.pal.Pal;
import uk.ac.starlink.ast.internal.wcslib.Prj;
import uk.ac.starlink.ast.internal.wcslib.WcsProjection;

/**
 * A Mapping implementing the sky projections of the FITS world coordinate system (FITS-WCS) paper
 * II, "Representations of Celestial Coordinates in FITS" by M. Calabretta and E.W. Greisen, which
 * transform longitude-latitude pairs representing spherical celestial coordinates into pairs of
 * Cartesian coordinates, and back.
 *
 * <p>A WcsMap applies one of these projections to a chosen pair of its coordinates, the others
 * being copied unchanged. Every projection of the paper is supported, plus the "TAN with
 * polynomial correction terms" projection TPN of an early draft of it. The forward transformation
 * converts "native spherical" coordinates into "projection plane" coordinates, and the inverse
 * converts back. Angles are in radians, not the degrees FITS-WCS uses. A point that cannot be
 * transformed (many projections do not cover the whole sphere) transforms to {@link #AST__BAD}.
 *
 * <p>Each of the two axes can carry projection parameters, the attributes {@code PVi_m}, which
 * give the precise form of the projection; transforming is an error ({@code AST__WCSPA}) when a
 * required parameter with no default is not set, or the parameters are unusable.
 *
 * <p>Ported from Rust {@code mappings/wcsmap.rs} and C {@code wcsmap.c}.
 */
public class WcsMap extends Mapping {

    /** Zenithal perspective projection. */
    public static final int AST__AZP = 1;

    /** Slant zenithal perspective projection. */
    public static final int AST__SZP = 2;

    /** Gnomonic projection. */
    public static final int AST__TAN = 3;

    /** Stereographic projection. */
    public static final int AST__STG = 4;

    /** Orthographic projection. */
    public static final int AST__SIN = 5;

    /** Zenithal equidistant projection. */
    public static final int AST__ARC = 6;

    /** Zenithal polynomial projection. */
    public static final int AST__ZPN = 7;

    /** Zenithal equal area projection. */
    public static final int AST__ZEA = 8;

    /** Airy projection. */
    public static final int AST__AIR = 9;

    /** Cylindrical perspective projection. */
    public static final int AST__CYP = 10;

    /** Cylindrical equal area projection. */
    public static final int AST__CEA = 11;

    /** Plate carree projection. */
    public static final int AST__CAR = 12;

    /** Mercator projection. */
    public static final int AST__MER = 13;

    /** Sanson-Flamsteed projection. */
    public static final int AST__SFL = 14;

    /** Parabolic projection. */
    public static final int AST__PAR = 15;

    /** Mollweide projection. */
    public static final int AST__MOL = 16;

    /** Hammer-Aitoff projection. */
    public static final int AST__AIT = 17;

    /** Conical perspective projection. */
    public static final int AST__COP = 18;

    /** Conical equal area projection. */
    public static final int AST__COE = 19;

    /** Conical equidistant projection. */
    public static final int AST__COD = 20;

    /** Conical orthomorphic projection. */
    public static final int AST__COO = 21;

    /** Bonne's equal area projection. */
    public static final int AST__BON = 22;

    /** Polyconic projection. */
    public static final int AST__PCO = 23;

    /** Tangential spherical cube projection. */
    public static final int AST__TSC = 24;

    /** COBE quadrilateralised spherical cube projection. */
    public static final int AST__CSC = 25;

    /** Quadrilateralised spherical cube projection. */
    public static final int AST__QSC = 26;

    /** AIPS north celestial pole projection, which has no transformation of its own. */
    public static final int AST__NCP = 27;

    /** Global sinusoidal projection, an alias for Sanson-Flamsteed. */
    public static final int AST__GLS = 28;

    /** Gnomonic projection with polynomial correction terms. */
    public static final int AST__TPN = 29;

    /** HEALPix projection. */
    public static final int AST__HPX = 30;

    /** Polar HEALPix projection. */
    public static final int AST__XPH = 31;

    /** A bad projection type: a WcsMap of this type is the identity. */
    public static final int AST__WCSBAD = 32;

    /** C's {@code AST__DR2D}: radians to degrees. */
    private static final double DR2D = 180.0 / Math.PI;

    /** C's {@code AST__DD2R}: degrees to radians. */
    private static final double DD2R = Math.PI / 180.0;

    /** The number of {@code ProjP<m>} keys an old dump can hold (wcsmap.h {@code AST__WCSMX}). */
    private static final int AST__WCSMX = 10;

    /** C's {@code -INT_MAX}, the unset value of the integer attributes. */
    private static final int UNSET = -Integer.MAX_VALUE;

    /**
     * The projections in the order of C's projection type numbers, so that type {@code t} is
     * element {@code t - 1}; {@link WcsProjection#UNKNOWN} is {@link #AST__WCSBAD}.
     */
    private static final WcsProjection[] TYPES = {
        WcsProjection.AZP, WcsProjection.SZP, WcsProjection.TAN, WcsProjection.STG, WcsProjection.SIN,
        WcsProjection.ARC, WcsProjection.ZPN, WcsProjection.ZEA, WcsProjection.AIR, WcsProjection.CYP,
        WcsProjection.CEA, WcsProjection.CAR, WcsProjection.MER, WcsProjection.SFL, WcsProjection.PAR,
        WcsProjection.MOL, WcsProjection.AIT, WcsProjection.COP, WcsProjection.COE, WcsProjection.COD,
        WcsProjection.COO, WcsProjection.BON, WcsProjection.PCO, WcsProjection.TSC, WcsProjection.CSC,
        WcsProjection.QSC, WcsProjection.NCP, WcsProjection.GLS, WcsProjection.TPN, WcsProjection.HPX,
        WcsProjection.XPH, WcsProjection.UNKNOWN
    };

    /** The projection type. */
    private final WcsProjection proj;

    /** The zero-based longitude and latitude axes (C's {@code wcsaxis}). */
    private final int[] axes = new int[2];

    /**
     * The projection parameters set, per axis (C's {@code p} and {@code np}): null when none has
     * been set on any axis, and otherwise an array per axis, null or holding the parameters up to
     * the largest index set, {@code AST__BAD} for one that is unset.
     */
    private double[][] pv;

    /** The projection set up from the parameters and TPNTan (C's {@code params}). */
    private Prj prj;

    /** The FITSProj attribute: {@link #UNSET}, 0 or 1. */
    private int fitsProj = UNSET;

    /** The LonCheck attribute: {@link #UNSET}, 0 or 1. */
    private int lonCheck = UNSET;

    /** The TPNTan attribute: {@link #UNSET}, 0 or 1. */
    private int tpnTan = UNSET;

    /**
     * Creates a WcsMap that applies a FITS-WCS projection to two axes of an {@code ncoord}
     * -dimensional space; the other coordinates are copied unchanged. Projection parameters are
     * set afterwards (attributes {@code PVi_m}); their validity is not checked until the WcsMap
     * transforms coordinates.
     *
     * @param ncoord the number of input and output coordinates, at least 2
     * @param type the projection type, one of the {@code AST__} constants of this class, such as
     *     {@link #AST__TAN}; {@link #AST__WCSBAD} gives a WcsMap that is the identity
     * @param lonax the index of the longitude axis, from 1 to {@code ncoord}
     * @param latax the index of the latitude axis, from 1 to {@code ncoord}, not {@code lonax}
     * @throws AstException with status {@code AST__WCSNC} if {@code ncoord} is less than 2, {@code
     *     AST__WCSAX} if an axis does not exist or the two are the same, or {@code AST__WCSTY} if
     *     the projection type is not defined
     */
    public WcsMap(int ncoord, int type, int lonax, int latax) {
        super(checkedNcoord(ncoord, type, lonax - 1, latax - 1), ncoord, true, true);
        proj = projection(type);
        axes[0] = lonax - 1;
        axes[1] = latax - 1;
        initPrjPrm();
    }

    /**
     * {@code ncoord}, after the checks of C's {@code astInitWcsMap}, made for a genuine projection
     * before the Mapping is initialised, with zero-based axes.
     */
    private static int checkedNcoord(int ncin, int type, int lonax, int latax) {
        if (type != AST__WCSBAD) {
            String name = "astInitWcsMap(WcsMap): ";
            if (ncin < 2) {
                throw Ast.err(
                        AstException.AST__WCSNC, name + "Too few axes (" + ncin + ") specified. Must be at least 2.");
            } else if (lonax < 0 || lonax >= ncin) {
                throw Ast.err(
                        AstException.AST__WCSAX,
                        name + "Specified longitude axis (" + (lonax + 1) + ") does not exist within a " + ncin
                                + " dimensional coordinate system. ");
            } else if (latax < 0 || latax >= ncin) {
                throw Ast.err(
                        AstException.AST__WCSAX,
                        name + "Specified latitude axis (" + (latax + 1) + ") does not exist within a " + ncin
                                + " dimensional coordinate system. ");
            } else if (lonax == latax) {
                throw Ast.err(
                        AstException.AST__WCSAX,
                        name + "The same axis (" + (lonax + 1)
                                + ") has been given for both the longitude and the latitude axis.");
            } else if (type < 1 || type >= AST__WCSBAD) {
                throw Ast.err(
                        AstException.AST__WCSTY,
                        name + "Projection type " + type + " is undefined. Projection types must be in the range 1 to "
                                + (AST__WCSBAD - 1) + ".");
            }
        }
        return ncin;
    }

    /** The projection of C's type number {@code type}, which has been checked. */
    private static WcsProjection projection(int type) {
        return type >= 1 && type <= AST__WCSBAD ? TYPES[type - 1] : WcsProjection.UNKNOWN;
    }

    /**
     * Loads a WcsMap from its description, as C's {@code astLoadWcsMap} does: FITSProj, LonCheck
     * and TPNTan, the projection type and axes, then the parameters, first from the {@code
     * ProjP<m>} keys of old dumps, then from the {@code PVi_m} keys, each up to the largest index
     * its axis takes (0 for an axis that is neither longitude nor latitude).
     */
    WcsMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("WcsMap");
        fitsProj = part.readInt("FitsPrj", UNSET);
        if (fitsProj != UNSET) {
            setFITSProj(fitsProj != 0);
        }
        lonCheck = part.readInt("LonChk", UNSET);
        if (lonCheck != UNSET) {
            setLonCheck(lonCheck != 0);
        }
        int tpn = part.readInt("TpnTan", UNSET);
        if (tpn != UNSET) {
            tpnTan = tpn != 0 ? 1 : 0;
        }
        String text = part.readString("Type", " ");
        proj = " ".equals(text) ? WcsProjection.UNKNOWN : wcsPrjType("-" + firstChars(text, 8));
        for (int axis = 0; axis < 2; axis++) {
            axes[axis] = part.readInt("WcsAx" + (axis + 1), axis + 1) - 1;
        }
        int ncoord = createdNin();
        // C uses the axes unchecked; they are checked as the constructor checks them, so that a
        // malformed description gives an error rather than an index out of range.
        checkedNcoord(ncoord, proj == WcsProjection.UNKNOWN ? AST__WCSBAD : 1, axes[0], axes[1]);
        if (axes[0] < 0 || axes[0] >= ncoord || axes[1] < 0 || axes[1] >= ncoord) {
            axes[0] = 0;
            axes[1] = 1;
        }
        initPrjPrm();
        for (int m = 0; m < AST__WCSMX; m++) {
            double val = part.readDouble("ProjP" + m, AST__BAD);
            if (val != AST__BAD) {
                setPV(axes[1], m, val);
            }
        }
        for (int i = 0; i < ncoord; i++) {
            int mxpar;
            if (i == axes[0]) {
                mxpar = proj.pvMaxLon();
            } else if (i == axes[1]) {
                mxpar = proj.pvMaxLat();
            } else {
                mxpar = 0;
            }
            for (int m = 0; m <= mxpar; m++) {
                double val = part.readDouble("PV" + (i + 1) + "_" + m, AST__BAD);
                if (val != AST__BAD) {
                    setPV(i, m, val);
                }
            }
        }
    }

    /** At most the first {@code n} characters of {@code s}, as C's {@code %.8s}. */
    private static String firstChars(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /**
     * The projection whose FITS {@code CTYPE} string, such as {@code "-TAN"}, is {@code ctype}
     * after removing every white-space character, compared exactly; {@link
     * WcsProjection#UNKNOWN} if there is none (C's {@code astWcsPrjType}).
     */
    private static WcsProjection wcsPrjType(String ctype) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < ctype.length() && buf.length() < 80; i++) {
            char c = ctype.charAt(i);
            if (!CScan.isCSpace(c)) {
                buf.append(c);
            }
        }
        String want = buf.toString();
        for (WcsProjection p : TYPES) {
            if (p != WcsProjection.UNKNOWN && want.equals("-" + p.code())) {
                return p;
            }
        }
        return WcsProjection.UNKNOWN;
    }

    @Override
    String dumpComment() {
        return "FITS-WCS sky projection";
    }

    /**
     * Writes the class's items as C's {@code Dump} (wcsmap.c) does: the projection type, always;
     * FITSProj, LonCheck and TPNTan, set when they are set; every projection parameter set, axis
     * by axis; and the two axes, set when not the defaults of 1 and 2.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        String desc = proj.description() + " projection";
        String comment = Character.toUpperCase(desc.charAt(0)) + desc.substring(1);
        node.writeString("Type", true, true, proj.code(), comment);
        boolean val = getFITSProj();
        node.writeInt(
                "FitsPrj",
                testFITSProj(),
                false,
                val ? 1 : 0,
                val ? "Defines the FITS-WCS projection" : "Does not define the FITS-WCS projection");
        val = getLonCheck();
        node.writeInt(
                "LonChk",
                testLonCheck(),
                false,
                val ? 1 : 0,
                val ? "Check returned lon values" : "Do not check returned lon values");
        val = getTPNTan();
        node.writeInt(
                "TpnTan",
                testTPNTan(),
                false,
                val ? 1 : 0,
                val ? "Include TAN projection in TPN mapping" : "Exclude TAN projection from TPN mapping");
        if (pv != null) {
            for (int i = 0; i < pv.length; i++) {
                if (pv[i] == null) {
                    continue;
                }
                for (int m = 0; m < pv[i].length; m++) {
                    if (testPV(i, m)) {
                        node.writeDouble(
                                "PV" + (i + 1) + "_" + m,
                                true,
                                false,
                                getPV(i, m),
                                "Projection parameter " + m + " for axis " + (i + 1));
                    }
                }
            }
        }
        for (int axis = 0; axis < 2; axis++) {
            int ival = axes[axis];
            node.writeInt(
                    "WcsAx" + (axis + 1),
                    axis != ival,
                    false,
                    ival + 1,
                    axis == 0 ? "Index of celestial longitude axis" : "Index of celestial latitude axis");
        }
    }

    /** Gives the copy its own projection parameters, as C's {@code Copy} (wcsmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (pv != null) {
            double[][] copy = new double[pv.length][];
            for (int i = 0; i < pv.length; i++) {
                copy[i] = pv[i] == null ? null : pv[i].clone();
            }
            pv = copy;
        }
    }

    // ------------------------------------------------------------------
    // Read-only attributes: WcsType, WcsAxis, NatLat, NatLon, PVMax
    // ------------------------------------------------------------------

    /**
     * Gets the WcsType attribute: the type of FITS-WCS projection the WcsMap performs, one of the
     * {@code AST__} constants of this class, which corresponds to the 3-character code a FITS-WCS
     * {@code CTYPEi} keyword uses to identify it; {@link #AST__TPN} represents a TAN projection
     * with polynomial correction terms as defined in an early draft of the FITS-WCS paper. It is
     * fixed when the WcsMap is created.
     *
     * @return the projection type
     */
    public int getWcsType() {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == proj) {
                return i + 1;
            }
        }
        throw Ast.err(AstException.AST__INTER, "WcsMap: unknown projection " + proj + ".");
    }

    /**
     * Gets the WcsAxis attribute: the index of the longitude axis ({@code lonlat} 1) or of the
     * latitude axis ({@code lonlat} 2) of the projection within the coordinate space of the
     * WcsMap. It is fixed when the WcsMap is created.
     *
     * @param lonlat 1 for the longitude axis, 2 for the latitude axis
     * @return the axis index, from 1 to {@code Nin}
     * @throws AstException with status {@code AST__AXIIN} if {@code lonlat} is not 1 or 2
     */
    public int getWcsAxis(int lonlat) {
        return wcsAxis(lonlat - 1) + 1;
    }

    /** C's {@code GetWcsAxis} (wcsmap.c {@code MAKE_GET}), with a zero-based index and result. */
    private int wcsAxis(int axis) {
        if (axis < 0 || axis >= 2) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    "astGetWcsAxis(" + astClass() + "): Index (" + (axis + 1)
                            + ") is invalid for attribute WcsAxis - it should be in the range 1 to 2.");
        }
        return axes[axis];
    }

    /**
     * Gets the NatLat attribute: the latitude of the reference point of the projection, in
     * radians in the native spherical coordinate system. It is fixed for most projections: pi/2
     * for every zenithal projection and 0 for the cylindrical ones; for the conics it is
     * projection parameter 1 on the latitude axis, converted to radians, or {@link #AST__BAD}
     * when that is not set. FITS-WCS paper II distinguishes the reference point from the fiducial
     * point, whose celestial coordinates the {@code CRVAL} keywords give; by default the two are
     * the same, but parameters 1 and 2 of the longitude axis can move the fiducial point.
     *
     * @return the native latitude of the reference point, as a float as jniast declares it
     */
    public float getNatLat() {
        return getF("NatLat");
    }

    /**
     * Gets the NatLon attribute: the longitude of the reference point of the projection, in
     * radians in the native spherical coordinate system, which is zero for every projection.
     *
     * @return the native longitude of the reference point, as a float as jniast declares it
     */
    public float getNatLon() {
        return getF("NatLon");
    }

    /** C's {@code GetNatLat}: the {@code theta0} of the projection table, or for the conics PV1. */
    final double natLat() {
        double ret = proj.tableTheta0();
        if (ret == AST__BAD) {
            ret = getPV(axes[1], 1);
            if (ret != AST__BAD) {
                ret *= DD2R;
            }
        }
        return ret;
    }

    /** C's {@code GetNatLon}: zero. */
    final double natLon() {
        return 0.0;
    }

    /**
     * Gets the attribute named {@code PVi_m}, as jniast's generated accessor does. No attribute
     * has that name ({@code i} and {@code m} must be numbers), so this always fails; read a
     * projection parameter with {@code getD("PV2_1")} and the like.
     *
     * @return never returns normally
     * @throws AstException with status {@code AST__BADAT}
     */
    // CHECKSTYLE.OFF: MethodName - jniast's name
    public float getPVi_m() {
        return getF("PVi_m");
    }
    // CHECKSTYLE.ON: MethodName

    /**
     * Sets the attribute named {@code PVi_m}, as jniast's generated accessor does. No attribute
     * has that name, so this always fails; set a projection parameter with {@code
     * set("PV2_1=45")} and the like.
     *
     * @param value the value
     * @throws AstException with status {@code AST__BADAT}
     */
    // CHECKSTYLE.OFF: MethodName - jniast's name
    public void setPVi_m(float value) {
        setF("PVi_m", value);
    }
    // CHECKSTYLE.ON: MethodName

    /**
     * C's {@code GetPVMax}: the largest legal parameter index on the zero-based axis {@code i},
     * from the projection table's {@code mxpar2} for the longitude axis and {@code mxpar} for the
     * latitude axis, and -1 for any other axis.
     */
    final int getPVMax(int i) {
        if (i < 0 || i >= createdNin()) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    "astGetPVMax(" + astClass() + "): Axis index (" + (i + 1) + ") is invalid in attribute PVMax("
                            + (i + 1) + ")  - it should be in the range 1 to " + createdNin() + ".");
        } else if (i == axes[0]) {
            return proj.pvMaxLon();
        } else if (i == axes[1]) {
            return proj.pvMaxLat();
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Projection parameters
    // ------------------------------------------------------------------

    /** The error for a parameter index out of range, as C's PV functions report it. */
    private AstException badParamIndex(String method, int i, int m, int mxpar) {
        return Ast.err(
                AstException.AST__AXIIN,
                method + "(" + astClass() + "): Parameter index (" + m + ") is invalid in attribute PV" + (i + 1) + "_"
                        + m + " for a \"-" + proj.code() + "\" projection - it should be in the range 0 to " + mxpar
                        + ".");
    }

    /** The error for an axis index out of range, as C's PV functions report it. */
    private AstException badAxisIndex(String method, int i, int m) {
        return Ast.err(
                AstException.AST__AXIIN,
                method + "(" + astClass() + "): Axis index (" + (i + 1) + ") is invalid in attribute PV" + (i + 1) + "_"
                        + m + "  - it should be in the range 1 to " + createdNin() + ".");
    }

    /**
     * C's {@code GetPV}: projection parameter {@code m} of the zero-based axis {@code i}. A
     * latitude-axis parameter has its default applied ({@code AST__BAD} for parameter 1 of the
     * conics and Bonne, which has none); on the longitude axis, an unset parameter 0 is 0 and 1 and
     * 2 the native longitude and latitude of the reference point in degrees, except for TPN, whose
     * parameters are all polynomial terms; any other unset parameter is {@code AST__BAD}.
     */
    final double getPV(int i, int m) {
        if (i < 0 || i >= createdNin()) {
            throw badAxisIndex("astGetPV", i, m);
        }
        int mxpar = getPVMax(i);
        if (m < 0 || m > mxpar) {
            throw badParamIndex("astGetPV", i, m, mxpar);
        }
        double ret = AST__BAD;
        if (i == axes[1]) {
            ret = prj.getParam(0, m);
        } else if (pv != null && pv[i] != null && m < pv[i].length) {
            ret = pv[i][m];
        }
        if (ret == AST__BAD && i == axes[0] && proj != WcsProjection.TPN) {
            if (m == 0) {
                ret = 0.0;
            } else if (m == 1) {
                ret = natLon() * DR2D;
            } else if (m == 2) {
                ret = natLat() * DR2D;
            }
        }
        return ret;
    }

    /**
     * C's {@code SetPV}: sets projection parameter {@code m} of the zero-based axis {@code i};
     * {@code AST__BAD} leaves it unset. The parameter is stored as C stores it, so a shared WcsMap
     * refuses the change, as an {@code astMAKE_SET1} attribute does (wcsmap.c:3924).
     */
    final void setPV(int i, int m, double val) {
        if (isShared()) {
            throw Ast.err(
                    AstException.AST__IMMUT,
                    "astSet(" + astClass() + "): Projection parameter values within the supplied " + astClass()
                            + " cannot be changed because the " + astClass() + " has been cloned (programming error).");
        }
        int naxis = createdNin();
        if (i < 0 || i >= naxis) {
            throw badAxisIndex("astSetPV", i, m);
        }
        int mxpar = getPVMax(i);
        if (m < 0 || m > mxpar) {
            throw badParamIndex("astSetPV", i, m, mxpar);
        }
        if (pv == null) {
            pv = new double[naxis][];
        }
        double[] axis = pv[i];
        if (axis == null || axis.length <= m) {
            int old = axis == null ? 0 : axis.length;
            axis = axis == null ? new double[m + 1] : Arrays.copyOf(axis, m + 1);
            Arrays.fill(axis, old, m + 1, AST__BAD);
            pv[i] = axis;
        }
        axis[m] = val;
        initPrjPrm();
    }

    /** C's {@code TestPV}: whether projection parameter {@code m} of the zero-based axis {@code i} is set. */
    final boolean testPV(int i, int m) {
        if (i < 0 || i >= createdNin()) {
            throw badAxisIndex("astTestPV", i, m);
        }
        int mxpar = getPVMax(i);
        if (m < 0 || m > mxpar) {
            return false;
        }
        return pv != null && pv[i] != null && m < pv[i].length && pv[i][m] != AST__BAD;
    }

    /**
     * C's {@code ClearPV}: clears projection parameter {@code m} of the zero-based axis {@code
     * i}, ignoring a parameter the axis does not take. A shared WcsMap refuses, as an {@code
     * astMAKE_CLEAR1} attribute does (wcsmap.c:1300).
     */
    final void clearPV(int i, int m) {
        if (isShared()) {
            throw Ast.err(
                    AstException.AST__IMMUT,
                    "astClear(" + astClass() + "): Projection parameter values within the supplied " + astClass()
                            + " cannot be cleared because the " + astClass() + " has been cloned (programming error).");
        }
        if (i < 0 || i >= createdNin()) {
            throw badAxisIndex("astClearPV", i, m);
        }
        int mxpar = getPVMax(i);
        if (m >= 0 && m <= mxpar && pv != null && pv[i] != null && m < pv[i].length) {
            pv[i][m] = AST__BAD;
        }
        initPrjPrm();
    }

    /**
     * C's {@code InitPrjPrm}: sets the projection up from the stored parameters and TPNTan, with
     * defaults applied. NCP and GLS are performed by SIN and SFL with the parameters the WcsMap
     * gives them; a WcsMap of a bad type is the identity and never consults its projection, which
     * is CAR as a placeholder.
     */
    private void initPrjPrm() {
        double[] lat = pv == null ? null : pv[axes[1]];
        double[] lon = pv == null ? null : pv[axes[0]];
        WcsProjection underlying;
        // MODERN-JAVA(21): switch expression over the enum
        switch (proj) {
            case NCP:
                underlying = WcsProjection.SIN;
                break;
            case GLS:
                underlying = WcsProjection.SFL;
                break;
            case UNKNOWN:
                underlying = WcsProjection.CAR;
                break;
            default:
                underlying = proj;
                break;
        }
        prj = Prj.create(underlying, lat, lon, getTPNTan());
    }

    // ------------------------------------------------------------------
    // Protected attributes: FITSProj, LonCheck, TPNTan
    // ------------------------------------------------------------------

    /**
     * The FITSProj attribute: whether this WcsMap defines the FITS-WCS projection when a compound
     * Mapping containing it is written through a FitsChan. A WcsMap that only supplies a
     * distortion correction has it false. Default true.
     */
    final boolean getFITSProj() {
        return fitsProj == UNSET || fitsProj != 0;
    }

    /** Sets FITSProj; a different value discards the record of simplification. */
    final void setFITSProj(boolean value) {
        int v = value ? 1 : 0;
        if (v != fitsProj) {
            clearIsSimple();
        }
        fitsProj = v;
    }

    final boolean testFITSProj() {
        return fitsProj != UNSET;
    }

    /** Clears FITSProj, discarding the record of simplification. */
    final void clearFITSProj() {
        clearIsSimple();
        fitsProj = UNSET;
    }

    /**
     * The LonCheck attribute: whether the inverse transformation sets bad a longitude outside the
     * projection's primary range. It has no effect for a cyclic projection. Default true.
     */
    final boolean getLonCheck() {
        return lonCheck == UNSET || lonCheck != 0;
    }

    /** Sets LonCheck; a different value discards the record of simplification. */
    final void setLonCheck(boolean value) {
        int v = value ? 1 : 0;
        if (v != lonCheck) {
            clearIsSimple();
        }
        lonCheck = v;
    }

    final boolean testLonCheck() {
        return lonCheck != UNSET;
    }

    /** Clears LonCheck, discarding the record of simplification. */
    final void clearLonCheck() {
        clearIsSimple();
        lonCheck = UNSET;
    }

    /**
     * The TPNTan attribute: whether a TPN projection includes the TAN projection as well as its
     * polynomial correction. When false, the WcsMap applies only the polynomial, and its inputs
     * and outputs are in degrees rather than radians. Default true.
     */
    final boolean getTPNTan() {
        return tpnTan == UNSET || tpnTan != 0;
    }

    /** Sets TPNTan and sets the projection up again. */
    final void setTPNTan(boolean value) {
        tpnTan = value ? 1 : 0;
        initPrjPrm();
    }

    final boolean testTPNTan() {
        return tpnTan != UNSET;
    }

    /** Clears TPNTan and sets the projection up again. */
    final void clearTPNTan() {
        tpnTan = UNSET;
        initPrjPrm();
    }

    // ------------------------------------------------------------------
    // Attribute strings (wcsmap.c GetAttrib, SetAttrib, TestAttrib, ClearAttrib)
    // ------------------------------------------------------------------

    /**
     * The integers of {@code attrib} parsed as {@code prefix%d<sep>%d...}, C's {@code sscanf}
     * with every character matched: one integer per element of {@code seps}, each followed by that
     * separator ({@code ""} for none). Null if {@code attrib} does not match.
     */
    private static int[] scanIndices(String attrib, String prefix, String... seps) {
        if (!attrib.startsWith(prefix)) {
            return null;
        }
        int at = prefix.length();
        int[] result = new int[seps.length];
        for (int k = 0; k < seps.length; k++) {
            int start = at;
            if (at < attrib.length() && (attrib.charAt(at) == '+' || attrib.charAt(at) == '-')) {
                at++;
            }
            int digits = at;
            while (at < attrib.length() && attrib.charAt(at) >= '0' && attrib.charAt(at) <= '9') {
                at++;
            }
            if (at == digits || at - digits > 9) {
                return null;
            }
            result[k] = Integer.parseInt(attrib.substring(attrib.charAt(start) == '+' ? start + 1 : start, at));
            if (!attrib.startsWith(seps[k], at)) {
                return null;
            }
            at += seps[k].length();
        }
        return at == attrib.length() ? result : null;
    }

    /** Whether {@code attrib} names one of the read-only attributes. */
    private static boolean isReadOnlyWcs(String attrib) {
        return "wcstype".equals(attrib)
                || "natlat".equals(attrib)
                || "natlon".equals(attrib)
                || scanIndices(attrib, "wcsaxis(", ")") != null;
    }

    @Override
    String getAttrib(String attrib) {
        int[] projp = scanIndices(attrib, "projp(", ")");
        int[] pvim = scanIndices(attrib, "pv", "_", "");
        int[] pvmax = scanIndices(attrib, "pvmax(", ")");
        int[] axis = scanIndices(attrib, "wcsaxis(", ")");
        if (projp != null) {
            return CFormat.format("%.15g", getPV(axes[1], projp[0]));
        } else if (pvim != null) {
            return CFormat.format("%.15g", getPV(pvim[0] - 1, pvim[1]));
        } else if ("wcstype".equals(attrib)) {
            return Integer.toString(getWcsType());
        } else if (pvmax != null) {
            return Integer.toString(getPVMax(pvmax[0] - 1));
        } else if ("natlat".equals(attrib)) {
            return CFormat.format("%.15g", natLat());
        } else if ("natlon".equals(attrib)) {
            return CFormat.format("%.15g", natLon());
        } else if (axis != null) {
            return Integer.toString(wcsAxis(axis[0] - 1) + 1);
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        int[] projp = scanIndices(attrib, "projp(", ")");
        int[] pvim = scanIndices(attrib, "pv", "_", "");
        if (dval != null && projp != null) {
            setPV(axes[1], projp[0], dval);
        } else if (dval != null && pvim != null) {
            setPV(pvim[0] - 1, pvim[1], dval);
        } else if (!value.isEmpty() && isReadOnlyWcs(attrib)) {
            // C matches a read-only name followed by "=" and at least one character.
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ". This is a read-only attribute.");
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        int[] projp = scanIndices(attrib, "projp(", ")");
        int[] pvim = scanIndices(attrib, "pv", "_", "");
        if (projp != null) {
            return testPV(axes[1], projp[0]);
        } else if (pvim != null) {
            return testPV(pvim[0] - 1, pvim[1]);
        } else if (isReadOnlyWcs(attrib)) {
            return false;
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        // C's ClearAttrib looks for "prpjp(%d)", not "projp(%d)" (wcsmap.c:1228), so clearing a
        // ProjP attribute falls through to the parent class, which does not know it.
        int[] projp = scanIndices(attrib, "prpjp(", ")");
        int[] pvim = scanIndices(attrib, "pv", "_", "");
        if (projp != null) {
            clearPV(axes[1], projp[0]);
        } else if (pvim != null) {
            clearPV(pvim[0] - 1, pvim[1]);
        } else if (isReadOnlyWcs(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                            + ". This is a read-only attribute.");
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Raises the errors C's {@code Map} returns before it projects any point, which {@code
     * Transform} reports: a projection with no functions (NCP) and a latitude parameter that has
     * no default and is not set.
     */
    private void checkTransformable() {
        if (proj == WcsProjection.NCP) {
            throw Ast.err(
                    AstException.AST__WCSTY,
                    "astTransform(" + astClass() + "): The " + astClass() + " specifies an illegal projection type ('"
                            + proj.description() + "').");
        }
        int plen = proj.pvMaxLat() + 1;
        for (int i = 0; i < plen; i++) {
            if (prj.getParam(0, i) == AST__BAD) {
                throw Ast.err(
                        AstException.AST__WCSPA,
                        "astTransform(" + astClass() + "): Required projection parameter PV" + (axes[1] + 1) + "_" + i
                                + " was not supplied for a " + proj.description() + " projection.");
            }
        }
    }

    /** The error {@code Transform} reports for unusable projection parameters. */
    private AstException unusable() {
        return Ast.err(
                AstException.AST__WCSPA,
                "astTransform(" + astClass() + "): The " + proj.description() + " projection parameter values in this "
                        + astClass() + " are unusable.");
    }

    /**
     * Transforms as C's {@code Transform} and {@code Map} (wcsmap.c) do: every axis is copied,
     * then the longitude and latitude axes are projected in place, unless the projection type is
     * bad, when the WcsMap is the identity.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        for (int i = 0; i < in.length; i++) {
            System.arraycopy(in[i], 0, out[i], 0, npoint);
        }
        if (proj == WcsProjection.UNKNOWN) {
            return;
        }
        checkTransformable();
        project(out[axes[0]], out[axes[1]], npoint, effectiveForward(forward));
    }

    /**
     * Projects the longitude and latitude arrays {@code lon} and {@code lat} in place, forward
     * from native spherical to projection-plane coordinates, or inverse; a bad input, a point the
     * projection cannot project and an inverse solution outside the projection's domain give
     * {@code AST__BAD} in both (wcsmap.c {@code Map}).
     */
    private void project(double[] lon, double[] lat, int npoint, boolean fwd) {
        // The WcsMap's inputs and outputs are in radians, or in degrees if TPNTan has been reset;
        // wcslib works in degrees. factor scales the WcsMap input into radians.
        double factor = getTPNTan() ? 1.0 : DD2R;
        double toOutput = DD2R / factor;
        boolean ok;
        if (fwd) {
            ok = prj.fwdSlice(
                    lon,
                    lat,
                    npoint,
                    (in0, in1, o) -> {
                        if (isBad(in0) || isBad(in1)) {
                            o[0] = AST__BAD;
                            o[1] = AST__BAD;
                        } else {
                            forwardPrep(in0, in1, factor, o);
                        }
                    },
                    (x, y, o) -> {
                        if (x == AST__BAD && y == AST__BAD) {
                            o[0] = AST__BAD;
                            o[1] = AST__BAD;
                        } else {
                            o[0] = x * toOutput;
                            o[1] = y * toOutput;
                        }
                    });
        } else {
            LonRange range = lonRange();
            double toDegrees = DR2D * factor;
            ok = prj.revSlice(
                    lon,
                    lat,
                    npoint,
                    (x, y, o) -> {
                        if (isBad(x) || isBad(y)) {
                            o[0] = AST__BAD;
                            o[1] = AST__BAD;
                        } else {
                            o[0] = x * toDegrees;
                            o[1] = y * toDegrees;
                        }
                    },
                    (longitude, latitude, o) -> range.finish(toOutput, longitude, latitude, o));
        }
        if (!ok) {
            throw unusable();
        }
    }

    /**
     * Converts native spherical coordinates in the WcsMap's units into the degrees a forward
     * projection takes, with the longitude in [-180, 180] and the latitude in [-90, 90]; a point
     * whose latitude is outside [-90, 90] goes to the equivalent point on the complementary
     * meridian (wcsmap.c {@code Map}).
     */
    private static void forwardPrep(double in0, double in1, double factor, double[] out) {
        double latitude = DR2D * Pal.drange(factor * in1);
        if (latitude > 90.0) {
            out[0] = DR2D * Pal.drange(Math.PI + factor * in0);
            out[1] = 180.0 - latitude;
        } else if (latitude < -90.0) {
            out[0] = DR2D * Pal.drange(Math.PI + factor * in0);
            out[1] = -180.0 - latitude;
        } else {
            out[0] = DR2D * Pal.drange(factor * in0);
            out[1] = latitude;
        }
    }

    /**
     * How the inverse accepts the longitudes its projection gives, worked out before projecting
     * any point: whether it tests them, from LonCheck and whether the projection is cyclic, and
     * the range it accepts (wcsmap.c {@code Map} and {@code LongRange}).
     */
    LonRange lonRange() {
        // LongRange: the reverse projections of points near the origin; a longitude beyond 180
        // degrees means the range is [0, 360) rather than [-180, 180). C reads the outputs whatever
        // the status, and bb carries the latitude of the last point tried into the cyclic test.
        double[] xx = {-1.0e-6, 0.0, 1.0e-6, 0.0};
        double[] yy = {0.0, 1.0e-6, 0.0, -1.0e-6};
        double hi = 180.0;
        double lo = -180.0;
        double[] o = new double[2];
        double bb = 0.0;
        for (int point = 0; point < 4; point++) {
            Prj.Status status = prj.revRaw(xx[point], yy[point], o);
            bb = o[1];
            if (status == Prj.Status.OK && o[0] > 180.0) {
                hi = 360.0;
                lo = 0.0;
                break;
            }
        }
        // The projection is cyclic if [90, bb] and [450, bb] project to the same place.
        double[] o1 = new double[2];
        prj.fwdRaw(90.0, bb, o);
        prj.fwdRaw(450.0, bb, o1);
        boolean cyclic = Math.abs(o[0] - o1[0]) < 1.0e-10 && Math.abs(o[1] - o1[1]) < 1.0e-10;
        return new LonRange(getLonCheck() && !cyclic, lo, hi, proj.tableTol());
    }

    /**
     * How a WcsMap's inverse accepts the native longitude its projection gives: where {@code
     * tested}, a longitude is accepted in [{@code lo}, {@code hi}), and one below {@code lo} by
     * at most {@code tol}, the projection's tolerance, is set to {@code lo}; any other is
     * rejected. Elsewhere every longitude is accepted.
     */
    static final class LonRange {
        final boolean tested;
        final double lo;
        final double hi;
        final double tol;

        LonRange(boolean tested, double lo, double hi, double tol) {
            this.tested = tested;
            this.lo = lo;
            this.hi = hi;
            this.tol = tol;
        }

        /**
         * The pair the inverse stores for the native ({@code longitude}, {@code latitude}) in
         * degrees: scaled by {@code toOutput}, or bad for a bad point or one outside the
         * projection's domain. A latitude a little past either pole, by at most the projection's
         * tolerance, is set to the pole first, as wcslib's bounds check {@code prjbchk} does, and
         * so is a longitude a little below the lower limit; the accepted longitude range is
         * half-open, so a value at or above the upper limit, the wrap of the lower one, is
         * rejected (wcsmap.c {@code Map}).
         */
        void finish(double toOutput, double longitude, double latitude, double[] out) {
            if (longitude == AST__BAD && latitude == AST__BAD) {
                out[0] = AST__BAD;
                out[1] = AST__BAD;
                return;
            }
            double lat = latitude;
            double abslat = Math.abs(lat);
            if (abslat > 90.0 && abslat - 90.0 <= tol) {
                lat = lat < 0.0 ? -90.0 : 90.0;
                abslat = 90.0;
            }
            double lon = longitude;
            if (tested && lon < lo && lo - lon <= tol) {
                lon = lo;
            }
            if ((!tested || (lon < hi && lon >= lo)) && abslat <= 90.0) {
                out[0] = toOutput * lon;
                out[1] = toOutput * lat;
            } else {
                out[0] = AST__BAD;
                out[1] = AST__BAD;
            }
        }
    }

    // ------------------------------------------------------------------
    // Splitting
    // ------------------------------------------------------------------

    /**
     * Splits as C's {@code MapSplit} (wcsmap.c) does, after the base class's split, which needs
     * every input: inputs including both celestial axes give a smaller WcsMap with the same
     * projection, parameters and Invert attribute; inputs including neither give a UnitMap; and
     * inputs including only one give a PermMap that drops it, since it cannot be transformed
     * alone, unless it is the only input.
     */
    @Override
    MapSplit splitOff(int[] in) {
        MapSplit base = MapSplit.baseSplit(this, in);
        if (base != null) {
            return base;
        }
        int nin = in.length;
        if (nin == 0) {
            return null;
        }
        int lonax = axes[0];
        int latax = axes[1];
        int ilat = -1;
        int ilon = -1;
        int ilatlon = -1;
        int[] result = new int[nin];
        for (int i = 0; i < nin; i++) {
            int iin = in[i];
            if (iin == lonax) {
                ilon = i;
                ilatlon = i;
            } else if (iin == latax) {
                ilat = i;
                ilatlon = i;
            }
            result[i] = iin;
        }
        if (ilat != -1 && ilon != -1) {
            WcsMap newwcs = new WcsMap(nin, getWcsType(), ilon + 1, ilat + 1);
            copyPV(this, newwcs);
            newwcs.setInvert(getInvert());
            return new MapSplit(result, newwcs);
        } else if (ilat == -1 && ilon == -1) {
            return new MapSplit(result, new UnitMap(nin));
        } else if (nin > 1) {
            int[] outputs = new int[nin - 1];
            int j = 0;
            for (int i = 0; i < nin; i++) {
                if (i != ilatlon) {
                    outputs[j] = result[i];
                    j++;
                }
            }
            int[] inperm = new int[nin];
            int[] outperm = new int[nin - 1];
            for (int i = 0; i < ilatlon; i++) {
                inperm[i] = i;
                outperm[i] = i;
            }
            // An input that feeds no output: its inverse is bad.
            inperm[ilatlon] = Integer.MAX_VALUE;
            for (int i = ilatlon + 1; i < nin; i++) {
                inperm[i] = i - 1;
                outperm[i - 1] = i;
            }
            return new MapSplit(outputs, PermMap.fromInternal(nin, inperm, nin - 1, outperm, null));
        }
        return null;
    }

    /**
     * C's {@code CopyPV}: gives {@code out} the projection parameters of {@code in}'s longitude
     * and latitude axes on its own longitude and latitude axes, and sets it up again.
     */
    private static void copyPV(WcsMap in, WcsMap out) {
        out.pv = null;
        if (in.pv != null) {
            out.pv = new double[out.createdNin()][];
            double[] lon = in.pv[in.axes[0]];
            double[] lat = in.pv[in.axes[1]];
            out.pv[out.axes[0]] = lon == null ? null : lon.clone();
            out.pv[out.axes[1]] = lat == null ? null : lat.clone();
        }
        out.initPrjPrm();
    }

    /**
     * C's {@code astWcsPrjType}: the type number of the projection whose FITS {@code CTYPE}
     * suffix, such as {@code "-TAN"}, is {@code ctype} without its white space, or {@link
     * #AST__WCSBAD} if there is none.
     */
    static int wcsPrjTypeNumber(String ctype) {
        WcsProjection p = wcsPrjType(ctype);
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == p) {
                return i + 1;
            }
        }
        return AST__WCSBAD;
    }

    /**
     * C's {@code astWcsPrjName}: the FITS {@code CTYPE} suffix, such as {@code "-TAN"}, of
     * projection type {@code type}, four blanks for an unknown type.
     */
    static String wcsPrjName(int type) {
        WcsProjection p = projection(type);
        return p == WcsProjection.UNKNOWN ? "    " : "-" + p.code();
    }

    /** C's {@code astWcsPrjDesc}: the description of projection type {@code type}. */
    static String wcsPrjDesc(int type) {
        return projection(type).description();
    }
}
