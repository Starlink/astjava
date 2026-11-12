package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.AstException;

/**
 * The FITS-WCS projection types, with the per-projection columns of C's {@code PrjInfo} table
 * (wcsmap.c:684-716).
 *
 * <p>Each constant corresponds to a 3-letter FITS projection code recognised by wcslib. {@code NCP}
 * and {@code GLS} are deprecated aliases for {@code SIN} and {@code SFL}, handled by a WcsMap,
 * which gives the underlying projection its parameters. {@code UNKNOWN} is the blank projection
 * type, C's {@code AST__WCSBAD}.
 *
 * <p>Ported from Rust {@code wcslib/mod.rs}.
 */
public enum WcsProjection {
    /** Zenithal/azimuthal perspective. */
    AZP("AZP", "zenithal perspective"),
    /** Slant zenithal perspective. */
    SZP("SZP", "slant zenithal perspective"),
    /** Gnomonic (tangent plane). */
    TAN("TAN", "gnomonic"),
    /** Stereographic. */
    STG("STG", "stereographic"),
    /** Orthographic / synthesis. */
    SIN("SIN", "orthographic"),
    /** Zenithal/azimuthal equidistant. */
    ARC("ARC", "zenithal equidistant"),
    /** Zenithal/azimuthal polynomial. */
    ZPN("ZPN", "zenithal polynomial"),
    /** Zenithal/azimuthal equal-area. */
    ZEA("ZEA", "zenithal equal area"),
    /** Airy. */
    AIR("AIR", "Airy"),
    /** Cylindrical perspective. */
    CYP("CYP", "cylindrical perspective"),
    /** Cylindrical equal-area. */
    CEA("CEA", "cylindrical equal area"),
    /** Plate carree. */
    CAR("CAR", "Cartesian"),
    /** Mercator. */
    MER("MER", "Mercator"),
    /** Sanson-Flamsteed (global sinusoidal). */
    SFL("SFL", "Sanson-Flamsteed"),
    /** Parabolic. */
    PAR("PAR", "parabolic"),
    /** Mollweide. */
    MOL("MOL", "Mollweide"),
    /** Hammer-Aitoff. */
    AIT("AIT", "Hammer-Aitoff"),
    /** Conic perspective. */
    COP("COP", "conical perspective"),
    /** Conic equal-area. */
    COE("COE", "conical equal area"),
    /** Conic equidistant. */
    COD("COD", "conical equidistant"),
    /** Conic orthomorphic. */
    COO("COO", "conical orthomorphic"),
    /** Bonne. */
    BON("BON", "Bonne's equal area"),
    /** Polyconic. */
    PCO("PCO", "polyconic"),
    /** Tangential spherical cube. */
    TSC("TSC", "tangential spherical cube"),
    /** COBE spherical cube. */
    CSC("CSC", "cobe quadrilateralized spherical cube"),
    /** Quadrilateralised spherical cube. */
    QSC("QSC", "quadrilateralized spherical cube"),
    /** North celestial pole (deprecated; an alias for SIN). */
    NCP("NCP", "AIPS north celestial pole"),
    /** Global sinusoidal (deprecated; an alias for SFL). */
    GLS("GLS", "sinusoidal"),
    /** TAN with polynomial correction terms. */
    TPN("TPN", "gnomonic polynomial"),
    /** HEALPix. */
    HPX("HPX", "HEALPix"),
    /** HEALPix polar. */
    XPH("XPH", "polar HEALPix"),
    /** The unknown or blank projection type, which a WcsMap treats as the identity. */
    UNKNOWN("   ", "<null>");

    /**
     * The largest projection-parameter index any projection accepts: the {@code mxpar} and {@code
     * mxpar2} value of ZPN and TPN ({@code WCSLIB_MXPAR}, proj.h:59).
     */
    public static final int WCSLIB_MXPAR = 100;

    /**
     * The number of slots in a projection-parameter array: one more than the largest parameter
     * index, so that index itself is addressable. Every array here is sized for the largest
     * projection, so the same indices are legal for every projection (C allocates {@code mxpar + 1}
     * doubles per axis, wcsmap.c:5862-5863).
     */
    public static final int PRJ_PARAM_SLOTS = WCSLIB_MXPAR + 1;

    /** AST's bad value, {@code AST__BAD}. */
    private static final double BAD = -Double.MAX_VALUE;

    private final String fitsCode;
    private final String desc;

    WcsProjection(String code, String description) {
        this.fitsCode = code;
        this.desc = description;
    }

    /**
     * The 3-letter FITS projection code, in upper case; three spaces for {@link #UNKNOWN}.
     *
     * @return the code
     */
    public String code() {
        return fitsCode;
    }

    /**
     * The textual description of the projection: the {@code desc} column of C's {@code PrjInfo}
     * table (wcsmap.c:685-716), which {@code astWcsPrjDesc} returns.
     *
     * @return the description
     */
    public String description() {
        return desc;
    }

    /**
     * The largest legal {@code m} in {@code PVi_m} on the latitude axis: the {@code mxpar} column
     * of C's {@code PrjInfo} table (wcsmap.c:684-716). C sizes the parameter array {@code mxpar +
     * 1} (wcsmap.c:5862), and {@code astGetPVMax} reports that allocation less one
     * (wcsmap.c:2208-2212).
     *
     * @return the largest latitude-axis parameter index
     */
    public int pvMaxLat() {
        // MODERN-JAVA(21): switch expression over the enum
        switch (this) {
            case AZP:
            case SIN:
            case CYP:
            case COP:
            case COE:
            case COD:
            case COO:
            case NCP:
            case HPX:
                return 2;
            case SZP:
                return 3;
            case AIR:
            case CEA:
            case BON:
                return 1;
            case ZPN:
            case TPN:
                return WCSLIB_MXPAR;
            case TAN:
            case STG:
            case ARC:
            case ZEA:
            case CAR:
            case MER:
            case SFL:
            case PAR:
            case MOL:
            case AIT:
            case PCO:
            case TSC:
            case CSC:
            case QSC:
            case GLS:
            case XPH:
            case UNKNOWN:
                return 0;
            default:
                throw new AstException("unexpected projection " + this, AstException.AST__INTER);
        }
    }

    /**
     * The largest legal {@code m} in {@code PVi_m} on the longitude axis: the {@code mxpar2}
     * column of C's {@code PrjInfo} table, which is 4 for every projection except TPN, whose
     * longitude axis also carries polynomial coefficients.
     *
     * @return the largest longitude-axis parameter index
     */
    public int pvMaxLon() {
        return this == TPN ? WCSLIB_MXPAR : 4;
    }

    /**
     * Parses a 3-letter FITS projection code, in any case.
     *
     * @param s the code
     * @return the projection, or null if the code is not recognised
     */
    public static WcsProjection fromCode(String s) {
        if (s.length() != 3) {
            return null;
        }
        StringBuilder buf = new StringBuilder(3);
        for (int i = 0; i < 3; i++) {
            char c = s.charAt(i);
            buf.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        String upper = buf.toString();
        for (WcsProjection p : values()) {
            if (p != UNKNOWN && p.fitsCode.equals(upper)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The native latitude of the fiducial point, in radians: the {@code theta0} column of C's
     * {@code PrjInfo} table (wcsmap.c:703-737), which {@code GetNatLat} returns. {@code AST__BAD}
     * for the conics, whose fiducial latitude is a projection parameter.
     *
     * @return the native latitude
     */
    public double tableTheta0() {
        // MODERN-JAVA(21): switch expression over the enum
        switch (this) {
            case AZP:
            case SZP:
            case TAN:
            case STG:
            case SIN:
            case ARC:
            case ZPN:
            case ZEA:
            case AIR:
            case XPH:
            case TPN:
                return Math.PI / 2;
            case COP:
            case COE:
            case COD:
            case COO:
                return BAD;
            case CYP:
            case CEA:
            case CAR:
            case MER:
            case SFL:
            case PAR:
            case MOL:
            case AIT:
            case BON:
            case PCO:
            case TSC:
            case CSC:
            case QSC:
            case NCP:
            case GLS:
            case HPX:
            case UNKNOWN:
                return 0.0;
            default:
                throw new AstException("unexpected projection " + this, AstException.AST__INTER);
        }
    }

    /**
     * How far past a limit of the accepted range, in degrees, a native longitude or latitude from
     * the reverse projection is set to the limit rather than rejected: the {@code tol} column of
     * C's {@code PrjInfo} table (wcsmap.c:711-743), the per-projection tolerances of wcslib's
     * {@code prjbchk} calls.
     *
     * @return the tolerance in degrees
     */
    public double tableTol() {
        // MODERN-JAVA(21): switch expression over the enum
        switch (this) {
            case AZP:
            case TAN:
            case STG:
            case SIN:
            case ARC:
            case ZPN:
            case ZEA:
            case AIR:
            case CYP:
            case CEA:
            case CAR:
            case MER:
            case AIT:
            case COP:
            case COE:
            case COD:
            case COO:
            case TSC:
            case CSC:
            case QSC:
            case TPN:
                return 1.0e-13;
            case SZP:
                return 2.0e-13;
            case SFL:
            case PAR:
            case PCO:
            case GLS:
            case HPX:
            case XPH:
                return 1.0e-12;
            case MOL:
            case BON:
                return 1.0e-11;
            case NCP:
            case UNKNOWN:
                return 0.0;
            default:
                throw new AstException("unexpected projection " + this, AstException.AST__INTER);
        }
    }
}
