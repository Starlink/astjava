package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.List;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A sequence of conversions between standard celestial (longitude, latitude) coordinate systems.
 *
 * <p>An SlaMap has two input and two output coordinates: the first is the celestial longitude and
 * the second the celestial latitude, both in radians. When created it performs a unit (null)
 * Mapping. {@link #add} then appends coordinate conversions, selected from those provided by the
 * SLALIB Positional Astronomy Library (Starlink User Note SUN/67), so that multi-step conversions
 * between a variety of celestial coordinate systems can be assembled out of SLALIB's building
 * blocks.
 *
 * <p>For details of the individual coordinate conversions available, see {@link #add}.
 *
 * <p>Ported from Rust {@code mappings/slamap.rs} and C {@code slamap.c}.
 */
public class SlaMap extends Mapping {

    /** The astronomical unit in metres, C's {@code AST__AU}. */
    private static final double AU = 1.49597870e11;

    /** Arcseconds to radians, C's {@code AS2R}. */
    private static final double AS2R = Math.PI / 648000.0;

    /** Degrees to radians, C's {@code D2R}. */
    private static final double D2R = Math.PI / 180.0;

    /**
     * C's {@code eq_cache}, {@code ep_cache} and {@code amprms_cache} (slamap.c, thread-specific
     * globals): the arguments and result of the last {@code palMappa} call, reused while the
     * arguments repeat.
     */
    private static final ThreadLocal<MappaCache> MAPPA_CACHE = ThreadLocal.withInitial(MappaCache::new);

    /** The last {@code palMappa} arguments and result in one thread. */
    private static final class MappaCache {
        double eq = AST__BAD;
        double ep = AST__BAD;
        double[] amprms = new double[21];
    }

    /**
     * The conversions an SlaMap can apply: C's conversion codes with their values, each with its
     * string, the comment its Dump writes for it and the descriptions of its arguments (slamap.c
     * {@code CvtString}).
     */
    enum Cvt {
        ADDET(1, "ADDET", "Add E-terms of aberration", "Besselian epoch of mean equinox (FK4)"),
        SUBET(2, "SUBET", "Subtract E-terms of aberration", "Besselian epoch of mean equinox (FK4)"),
        PREBN(3, "PREBN", "Apply Bessel-Newcomb (FK4) precession", "From Besselian epoch", "To Besselian epoch"),
        PREC(4, "PREC", "Apply IAU 1975 (FK5) precession", "From Julian epoch", "To Julian epoch"),
        FK45Z(5, "FK45Z", "FK4 to FK5 J2000.0 (no PM or parallax)", "Besselian epoch of FK4 coordinates"),
        FK54Z(6, "FK54Z", "FK5 J2000.0 to FK4 (no PM or parallax)", "Besselian epoch of FK4 system"),
        AMP(
                7,
                "AMP",
                "Geocentric apparent to mean place (FK5)",
                "TDB of apparent place (as MJD)",
                "Julian epoch of mean equinox (FK5)"),
        MAP(
                8,
                "MAP",
                "Mean place (FK5) to geocentric apparent",
                "Julian epoch of mean equinox (FK5)",
                "TDB of apparent place (as MJD)"),
        ECLEQ(9, "ECLEQ", "Ecliptic (IAU 1980) to J2000.0 equatorial (FK5)", "TDB of mean ecliptic (as MJD)"),
        EQECL(10, "EQECL", "Equatorial J2000.0 (FK5) to ecliptic (IAU 1980)", "TDB of mean ecliptic (as MJD)"),
        GALEQ(11, "GALEQ", "Galactic (IAU 1958) to J2000.0 equatorial (FK5)"),
        EQGAL(12, "EQGAL", "J2000.0 equatorial (FK5) to galactic (IAU 1958)"),
        GALSUP(13, "GALSUP", "Galactic (IAU 1958) to supergalactic"),
        SUPGAL(14, "SUPGAL", "Supergalactic to galactic (IAU 1958)"),
        HPCEQ(
                15,
                "HPCEQ",
                "Helioprojective-Cartesian to J2000.0 equatorial (FK5)",
                "Modified Julian Date of observation",
                "Heliocentric-Aries-Ecliptic X value at observer",
                "Heliocentric-Aries-Ecliptic Y value at observer",
                "Heliocentric-Aries-Ecliptic Z value at observer"),
        EQHPC(
                16,
                "EQHPC",
                "J2000.0 equatorial (FK5) to Helioprojective-Cartesian",
                "Modified Julian Date of observation",
                "Heliocentric-Aries-Ecliptic X value at observer",
                "Heliocentric-Aries-Ecliptic Y value at observer",
                "Heliocentric-Aries-Ecliptic Z value at observer"),
        HPREQ(
                17,
                "HPREQ",
                "Helioprojective-Radial to J2000.0 equatorial (FK5)",
                "Modified Julian Date of observation",
                "Heliocentric-Aries-Ecliptic X value at observer",
                "Heliocentric-Aries-Ecliptic Y value at observer",
                "Heliocentric-Aries-Ecliptic Z value at observer"),
        EQHPR(
                18,
                "EQHPR",
                "J2000.0 equatorial (FK5) to Helioprojective-Radial",
                "Modified Julian Date of observation",
                "Heliocentric-Aries-Ecliptic X value at observer",
                "Heliocentric-Aries-Ecliptic Y value at observer",
                "Heliocentric-Aries-Ecliptic Z value at observer"),
        HFK5Z(19, "HFK5Z", "ICRS to J2000.0 FK5 (no PM or parallax)", "Julian epoch of FK5 coordinates"),
        FK5HZ(20, "FK5HZ", "J2000.0 FK5 to ICRS (no PM or parallax)", "Julian epoch of FK5 coordinates"),
        HEEQ(21, "HEEQ", "Helio-ecliptic to equatorial", "Modified Julian Date of observation"),
        EQHE(22, "EQHE", "Equatorial to helio-ecliptic", "Modified Julian Date of observation"),
        J2000H(23, "J2000H", "J2000 equatorial (dynamical) to ICRS"),
        HJ2000(24, "HJ2000", "ICRS to J2000 equatorial (dynamical)"),
        DH2E(
                25,
                "H2E",
                "Horizon to equatorial",
                "Geodetic latitude of observer",
                "Magnitude of diurnal aberration vector"),
        DE2H(
                26,
                "E2H",
                "Equatorial to horizon",
                "Geodetic latitude of observer",
                "Magnitude of diurnal aberration vector"),
        R2H(27, "R2H", "RA to Hour Angle", "Local apparent sidereal time (radians)"),
        H2R(28, "H2R", "Hour Angle to RA", "Local apparent sidereal time (radians)");

        /** The conversion's string, as {@code astSlaAdd} takes it and Dump writes it. */
        final String code;

        /** C's integer code for the conversion. */
        final int cCode;

        /** The comment Dump writes beside the conversion. */
        final String comment;

        /** The descriptions of the arguments, one for each. */
        // The array is private and never modified.
        @SuppressWarnings("ImmutableEnumChecker")
        private final String[] argdescs;

        Cvt(int cCode, String code, String comment, String... argdesc) {
            this.cCode = cCode;
            this.code = code;
            this.comment = comment;
            this.argdescs = argdesc;
        }

        /** The number of arguments the conversion takes. */
        int nargs() {
            return argdescs.length;
        }

        /** The description of argument {@code i}. */
        String argdesc(int i) {
            return argdescs[i];
        }

        /**
         * The conversion named {@code s}, matched as C's {@code CvtCode} matches it, with {@code
         * astChrMatch} (ignoring case and trailing white space), or null if there is none.
         */
        static Cvt fromCode(String s) {
            for (Cvt c : values()) {
                if (chrMatch(s, c.code)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** One conversion of the sequence: C's {@code cvttype}, {@code cvtargs} and {@code cvtextra}. */
    private static final class Step {
        final Cvt cvt;
        final double[] args;

        /** The {@code palMappa} parameters of a MAP or AMP conversion, once worked out. */
        double[] extra;

        Step(Cvt cvt, double[] args) {
            this.cvt = cvt;
            this.args = args;
        }

        Step copy() {
            Step s = new Step(cvt, args.clone());
            s.extra = extra == null ? null : extra.clone();
            return s;
        }
    }

    /** The conversions, in the order they were added. */
    private List<Step> steps = new ArrayList<>();

    /** Creates an SlaMap with no conversions, which performs a unit Mapping. */
    public SlaMap() {
        super(2, 2, true, true);
    }

    /**
     * Creates an SlaMap with no conversions, which performs a unit Mapping.
     *
     * @param flags reserved for future use; should be zero, and has no effect
     */
    public SlaMap(int flags) {
        this();
    }

    /**
     * Loads an SlaMap from its description, as C's {@code astLoadSlaMap} does: the number of
     * conversions, then each conversion's string and arguments, a missing argument being {@link
     * #AST__BAD}.
     *
     * @throws AstException with status {@code AST__BADIN} if a conversion's string is missing or
     *     names no conversion
     */
    SlaMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("SlaMap");
        int ncvt = Math.max(0, part.readInt("Nsla", 0));
        for (int icvt = 0; icvt < ncvt; icvt++) {
            String key = "Sla" + (icvt + 1);
            String sval = part.readString(key, null);
            if (sval == null) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astRead(Channel): An SLALIB sky coordinate conversion type is missing from the input"
                                + " SlaMap data.");
            }
            Cvt cvt = Cvt.fromCode(sval);
            if (cvt == null) {
                throw Ast.err(
                        AstException.AST__BADIN,
                        "astRead(Channel): Invalid SLALIB sky conversion type \"" + sval + "\" in SlaMap data.");
            }
            double[] args = new double[cvt.nargs()];
            for (int iarg = 0; iarg < args.length; iarg++) {
                args[iarg] = part.readDouble(key + argLetter(iarg), AST__BAD);
            }
            steps.add(new Step(cvt, args));
        }
    }

    /** The letter that follows a conversion's key for argument {@code i}, C's {@code ALPHABET}. */
    private static char argLetter(int i) {
        return (char) ('a' + i);
    }

    @Override
    void deepCopy() {
        super.deepCopy();
        List<Step> copied = new ArrayList<>(steps.size());
        for (Step s : steps) {
            copied.add(s.copy());
        }
        steps = copied;
    }

    @Override
    String dumpComment() {
        return "Conversion between sky coordinate systems";
    }

    /**
     * Writes the number of conversions, set when there are any, then each conversion's string and
     * every one of its arguments, as C's {@code Dump} (slamap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        int ncvt = steps.size();
        node.writeInt("Nsla", ncvt != 0, false, ncvt, "Number of conversion steps");
        for (int icvt = 0; icvt < ncvt; icvt++) {
            Step s = steps.get(icvt);
            String key = "Sla" + (icvt + 1);
            node.writeString(key, true, true, s.cvt.code, s.cvt.comment);
            for (int iarg = 0; iarg < s.cvt.nargs(); iarg++) {
                node.writeDouble(key + argLetter(iarg), true, true, s.args[iarg], s.cvt.argdesc(iarg));
            }
        }
    }

    // ------------------------------------------------------------------
    // Adding conversions
    // ------------------------------------------------------------------

    /**
     * Adds one of the standard celestial coordinate system conversions provided by SLALIB (SUN/67)
     * to an existing SlaMap.
     *
     * <p>When an SlaMap is first created, it simply performs a unit (null) Mapping. By using this
     * method repeatedly, a series of coordinate conversion steps may then be added. This allows
     * multi-step conversions between a variety of celestial coordinate systems to be assembled out
     * of the building blocks provided by SLALIB.
     *
     * <p>The forward transformation of the SlaMap applies the conversions in the order they were
     * added, and its inverse transformation applies the inverse of each conversion in the reverse
     * order. No check is made that the sequence of conversions is physically meaningful. When
     * assembling a multi-step conversion, it is often simplest to include every step that is
     * logically needed (converting through FK5 J2000.0 as an intermediate stage, for instance) and
     * to simplify the SlaMap afterwards, which removes the steps that turn out not to be needed.
     *
     * <p>The conversions, with the arguments each takes in the order they are given, are:
     *
     * <ul>
     *   <li>{@code "ADDET"} (EQ): add E-terms of aberration;
     *   <li>{@code "SUBET"} (EQ): subtract E-terms of aberration;
     *   <li>{@code "PREBN"} (BEP0, BEP1): apply the Bessel-Newcomb pre-IAU 1976 (FK4) precession
     *       model;
     *   <li>{@code "PREC"} (EP0, EP1): apply the IAU 1975 (FK5) precession model;
     *   <li>{@code "FK45Z"} (BEPOCH): convert FK4 to FK5 (no proper motion or parallax);
     *   <li>{@code "FK54Z"} (BEPOCH): convert FK5 to FK4 (no proper motion or parallax);
     *   <li>{@code "AMP"} (DATE, EQ): convert geocentric apparent to mean place;
     *   <li>{@code "MAP"} (EQ, DATE): convert mean place to geocentric apparent;
     *   <li>{@code "ECLEQ"} (DATE): convert ecliptic coordinates to FK5 J2000.0 equatorial;
     *   <li>{@code "EQECL"} (DATE): convert equatorial FK5 J2000.0 to ecliptic coordinates;
     *   <li>{@code "GALEQ"}: convert galactic coordinates to FK5 J2000.0 equatorial;
     *   <li>{@code "EQGAL"}: convert FK5 J2000.0 equatorial to galactic coordinates;
     *   <li>{@code "HFK5Z"} (JEPOCH): convert ICRS coordinates to FK5 J2000.0 equatorial;
     *   <li>{@code "FK5HZ"} (JEPOCH): convert FK5 J2000.0 equatorial coordinates to ICRS;
     *   <li>{@code "GALSUP"}: convert galactic to supergalactic coordinates;
     *   <li>{@code "SUPGAL"}: convert supergalactic coordinates to galactic;
     *   <li>{@code "J2000H"}: convert dynamical J2000.0 to ICRS;
     *   <li>{@code "HJ2000"}: convert ICRS to dynamical J2000.0;
     *   <li>{@code "R2H"} (LAST): convert right ascension to hour angle;
     *   <li>{@code "H2R"} (LAST): convert hour angle to right ascension;
     *   <li>{@code "H2E"} (LAT, DIURAB): convert topocentric horizon coordinates (azimuth,
     *       elevation) to apparent local equatorial coordinates (hour angle, declination);
     *   <li>{@code "E2H"} (LAT, DIURAB): convert apparent local equatorial coordinates (hour angle,
     *       declination) to topocentric horizon coordinates (azimuth, elevation);
     *   <li>{@code "HPCEQ"} (DATE, OBSX, OBSY, OBSZ): convert Helioprojective-Cartesian coordinates
     *       to J2000.0 equatorial;
     *   <li>{@code "EQHPC"} (DATE, OBSX, OBSY, OBSZ): convert J2000.0 equatorial coordinates to
     *       Helioprojective-Cartesian;
     *   <li>{@code "HPREQ"} (DATE, OBSX, OBSY, OBSZ): convert Helioprojective-Radial coordinates to
     *       J2000.0 equatorial;
     *   <li>{@code "EQHPR"} (DATE, OBSX, OBSY, OBSZ): convert J2000.0 equatorial coordinates to
     *       Helioprojective-Radial;
     *   <li>{@code "HEEQ"} (DATE): convert helio-ecliptic coordinates to J2000.0 equatorial;
     *   <li>{@code "EQHE"} (DATE): convert J2000.0 equatorial coordinates to helio-ecliptic.
     * </ul>
     *
     * <p>The arguments are: DIURAB, the magnitude of the diurnal aberration vector in units of
     * "day/(2.PI)"; DATE, the TDB of the observation as a Modified Julian Date (for the
     * helioprojective and helio-ecliptic conversions, the Modified Julian Date of the observation);
     * BEP0 and BEP1, the Besselian epochs at the start and end of the precession; EP0 and EP1, the
     * Julian epochs at the start and end of the precession; EQ, the Besselian epoch of the mean
     * equator and equinox (ADDET, SUBET) or the Julian epoch of the mean equinox (AMP, MAP); BEPOCH,
     * the Besselian epoch at which an FK4 position is given (FK45Z) or required (FK54Z); JEPOCH, the
     * Julian epoch of the FK5 coordinates; LAT, the observer's geodetic latitude in radians; LAST, the local apparent
     * sidereal time in radians; and OBSX, OBSY and OBSZ, the Heliocentric-Aries-Ecliptic Cartesian
     * coordinates of the observer, in metres. The arguments take the units, time scales and
     * calendars SUN/67 describes. Diurnal aberration is taken into account by H2E and E2H;
     * atmospheric refraction is not.
     *
     * @param cvt the conversion, as one of the strings above (case is not significant)
     * @param args its arguments, in the order given above; null or empty for a conversion that
     *     takes none
     * @throws AstException with status {@code AST__SLAIN} if {@code cvt} names no conversion, or
     *     {@code AST__TIMIN} if {@code args} does not hold exactly the conversion's number of
     *     arguments
     */
    public void add(String cvt, double[] args) {
        Cvt type = Cvt.fromCode(cvt);
        if (type == null) {
            throw Ast.err(
                    AstException.AST__SLAIN,
                    "astSlaAdd(" + astClass() + "): Invalid SLALIB sky coordinate conversion type \"" + cvt + "\".");
        }
        int narg = args == null ? 0 : args.length;
        if (narg != type.nargs()) {
            throw Ast.err(
                    AstException.AST__TIMIN,
                    "AddSlaCvt(" + astClass() + "): Invalid no. of arguments for SLALIB coordinate conversion type "
                            + type.cCode + " - " + narg + " supplied, " + type.nargs() + " required.");
        }
        steps.add(new Step(type, narg == 0 ? new double[0] : args.clone()));
    }

    /** The number of conversions. */
    int ncvt() {
        return steps.size();
    }

    /** The code of conversion {@code i}, from 0, as {@link #add} takes it. */
    String cvtCode(int i) {
        return steps.get(i).cvt.code;
    }

    /** A copy of the arguments of conversion {@code i}, from 0. */
    double[] cvtArgs(int i) {
        return steps.get(i).args.clone();
    }

    /** Whether the SlaMap has no conversions, so that it is a unit Mapping (C's {@code astSlaIsEmpty}). */
    boolean isEmpty() {
        return steps.isEmpty();
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /** A conversion of one (longitude, latitude) pair, giving the new pair. */
    @FunctionalInterface
    private interface PointConversion {
        double[] apply(double alpha, double delta);
    }

    /**
     * C's {@code TRAN_ARRAY}: applies {@code f} to each point in place, except that a point with
     * either coordinate bad becomes bad in both.
     */
    private static void tranArray(double[] alpha, double[] delta, int npoint, PointConversion f) {
        for (int i = 0; i < npoint; i++) {
            if (isBad(alpha[i]) || isBad(delta[i])) {
                alpha[i] = AST__BAD;
                delta[i] = AST__BAD;
            } else {
                double[] r = f.apply(alpha[i], delta[i]);
                alpha[i] = r[0];
                delta[i] = r[1];
            }
        }
    }

    /**
     * A conversion that rotates the direction vector by {@code m} (by its transpose if {@code
     * transpose}), optionally normalising the longitude into [0, 2 pi) with {@code palDranrm}, as
     * the precession, ecliptic and J2000H conversions of C's {@code Transform} do.
     */
    private static PointConversion rotation(double[][] m, boolean transpose, boolean normalise) {
        return (a, d) -> {
            double[] v1 = Pal.dcs2c(a, d);
            double[] v2 = transpose ? Pal.dimxv(m, v1) : Pal.dmxv(m, v1);
            double[] r = Pal.dcc2s(v2);
            if (normalise) {
                r[0] = Pal.dranrm(r[0]);
            }
            return r;
        };
    }

    /** The first two values of {@code r}, for the PAL routines that also give proper motions. */
    private static double[] firstTwo(double[] r) {
        return new double[] {r[0], r[1]};
    }

    /**
     * The {@code palMappa} parameters of a MAP or AMP conversion for the equinox {@code eq} and
     * date {@code ep}: those already worked out for the conversion or, the first time, those of the
     * thread's last {@code palMappa} call if its arguments were the same, else a new call's, kept
     * with the conversion (slamap.c {@code Transform}).
     */
    private static double[] amprms(Step s, double eq, double ep) {
        if (s.extra == null) {
            MappaCache c = MAPPA_CACHE.get();
            if (eq != c.eq || ep != c.ep) {
                c.eq = eq;
                c.ep = ep;
                c.amprms = Pal.mappa(eq, ep);
            }
            s.extra = c.amprms.clone();
        }
        return s.extra;
    }

    /**
     * Applies the conversions as C's {@code Transform} (slamap.c) does: the output starts as a copy
     * of the input, and each conversion in turn (the inverse of each in reverse order for the
     * inverse transformation) updates it in place. A point with either coordinate bad is bad in
     * both, and a conversion with a bad argument makes every point bad.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        double[] alpha = out[0];
        double[] delta = out[1];
        System.arraycopy(in[0], 0, alpha, 0, npoint);
        System.arraycopy(in[1], 0, delta, 0, npoint);
        int ncvt = steps.size();
        for (int k = 0; k < ncvt; k++) {
            Step s = steps.get(fwd ? k : ncvt - 1 - k);
            if (hasBadArgument(s)) {
                for (int i = 0; i < npoint; i++) {
                    alpha[i] = AST__BAD;
                    delta[i] = AST__BAD;
                }
                continue;
            }
            applyStep(s, fwd, alpha, delta, npoint);
        }
    }

    /**
     * Whether any of a conversion's arguments is bad, in which case it cannot be evaluated and C
     * makes every point bad rather than let PAL propagate NaN.
     */
    private static boolean hasBadArgument(Step s) {
        for (int i = 0; i < s.cvt.nargs(); i++) {
            if (isBad(s.args[i])) {
                return true;
            }
        }
        return false;
    }

    /** One arm of the conversion switch of C's {@code Transform}. */
    private static void applyStep(Step s, boolean fwd, double[] alpha, double[] delta, int npoint) {
        double[] args = s.args;
        // MODERN-JAVA(21): switch expression over the conversions
        switch (s.cvt) {
            case ADDET:
                tranArray(alpha, delta, npoint, fwd ? addet(args[0]) : subet(args[0]));
                break;
            case SUBET:
                tranArray(alpha, delta, npoint, fwd ? subet(args[0]) : addet(args[0]));
                break;
            case PREBN:
                // The inverse is the precession with the epochs exchanged.
                tranArray(
                        alpha,
                        delta,
                        npoint,
                        rotation(fwd ? Pal.prebn(args[0], args[1]) : Pal.prebn(args[1], args[0]), false, true));
                break;
            case PREC:
                tranArray(
                        alpha,
                        delta,
                        npoint,
                        rotation(fwd ? Pal.prec(args[0], args[1]) : Pal.prec(args[1], args[0]), false, true));
                break;
            case FK45Z:
                tranArray(alpha, delta, npoint, fwd ? fk45z(args[0]) : fk54z(args[0]));
                break;
            case FK54Z:
                tranArray(alpha, delta, npoint, fwd ? fk54z(args[0]) : fk45z(args[0]));
                break;
            case AMP: {
                // AMP takes (DATE, EQ), MAP (EQ, DATE).
                double[] amprms = amprms(s, args[1], args[0]);
                tranArray(alpha, delta, npoint, fwd ? ampqk(amprms) : mapqkz(amprms));
                break;
            }
            case MAP: {
                double[] amprms = amprms(s, args[0], args[1]);
                tranArray(alpha, delta, npoint, fwd ? mapqkz(amprms) : ampqk(amprms));
                break;
            }
            case ECLEQ:
                // The matrix converts equatorial J2000.0 to the ecliptic of the date; ECLEQ applies
                // its inverse.
                tranArray(alpha, delta, npoint, rotation(eclipticMatrix(args[0]), fwd, true));
                break;
            case EQECL:
                tranArray(alpha, delta, npoint, rotation(eclipticMatrix(args[0]), !fwd, true));
                break;
            case HFK5Z:
                tranArray(alpha, delta, npoint, fwd ? hfk5z(args[0]) : fk5hz(args[0]));
                break;
            case FK5HZ:
                tranArray(alpha, delta, npoint, fwd ? fk5hz(args[0]) : hfk5z(args[0]));
                break;
            case DH2E:
                tranArray(alpha, delta, npoint, fwd ? dh2e(args[0], args[1]) : de2h(args[0], args[1]));
                break;
            case DE2H:
                tranArray(alpha, delta, npoint, fwd ? de2h(args[0], args[1]) : dh2e(args[0], args[1]));
                break;
            case GALEQ:
                tranArray(alpha, delta, npoint, fwd ? Pal::galeq : Pal::eqgal);
                break;
            case EQGAL:
                tranArray(alpha, delta, npoint, fwd ? Pal::eqgal : Pal::galeq);
                break;
            case GALSUP:
                tranArray(alpha, delta, npoint, fwd ? Pal::galsup : Pal::supgal);
                break;
            case SUPGAL:
                tranArray(alpha, delta, npoint, fwd ? Pal::supgal : Pal::galsup);
                break;
            case HPCEQ:
            case HPREQ:
            case HEEQ: {
                Stp sys = stpSystem(s.cvt);
                double[] obs = stpObserver(s);
                // Points are at an infinite distance, so the origins of the systems are ignored.
                double[][] m =
                        fwd ? stpMatrix(args[0], sys, obs, Stp.HAQ, null) : stpMatrix(args[0], Stp.HAQ, null, sys, obs);
                tranArray(alpha, delta, npoint, stpRotation(m));
                break;
            }
            case EQHPC:
            case EQHPR:
            case EQHE: {
                Stp sys = stpSystem(s.cvt);
                double[] obs = stpObserver(s);
                double[][] m =
                        fwd ? stpMatrix(args[0], Stp.HAQ, null, sys, obs) : stpMatrix(args[0], sys, obs, Stp.HAQ, null);
                tranArray(alpha, delta, npoint, stpRotation(m));
                break;
            }
            case J2000H:
                tranArray(alpha, delta, npoint, rotation(j2000hMatrix(), !fwd, false));
                break;
            case HJ2000:
                tranArray(alpha, delta, npoint, rotation(j2000hMatrix(), fwd, false));
                break;
            case H2R:
            case R2H: {
                // The forward and inverse transformations are the same, and the longitude is not
                // normalised.
                double last = args[0];
                tranArray(alpha, delta, npoint, (a, d) -> new double[] {last - a, d});
                break;
            }
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "astTransform(SlaMap): Corrupt SlaMap contains invalid SLALIB sky coordinate conversion "
                                + s.cvt + " (internal AST programming error).");
        }
    }

    private static PointConversion addet(double eq) {
        return (a, d) -> Pal.addet(a, d, eq);
    }

    private static PointConversion subet(double eq) {
        return (a, d) -> Pal.subet(a, d, eq);
    }

    /** {@code eraFk45z}, as C's SlaMap calls it. */
    private static PointConversion fk45z(double bepoch) {
        return (a, d) -> Pal.fk45z(a, d, bepoch);
    }

    /** {@code eraFk54z}'s position, as C's SlaMap calls it. */
    private static PointConversion fk54z(double bepoch) {
        return (a, d) -> firstTwo(Pal.fk54z(a, d, bepoch));
    }

    private static PointConversion fk5hz(double epoch) {
        return (a, d) -> Pal.fk5hz(a, d, epoch);
    }

    private static PointConversion hfk5z(double epoch) {
        return (a, d) -> firstTwo(Pal.hfk5z(a, d, epoch));
    }

    private static PointConversion ampqk(double[] amprms) {
        return (a, d) -> Pal.ampqk(a, d, amprms);
    }

    private static PointConversion mapqkz(double[] amprms) {
        return (a, d) -> Pal.mapqkz(a, d, amprms);
    }

    private static PointConversion de2h(double phi, double diurab) {
        return (a, d) -> de2h(a, d, phi, diurab);
    }

    private static PointConversion dh2e(double phi, double diurab) {
        return (a, d) -> dh2e(a, d, phi, diurab);
    }

    /**
     * The matrix converting equatorial J2000.0 coordinates to the ecliptic of {@code date}: the
     * precession from J2000.0 to the date followed by the rotation to the ecliptic (slamap.c {@code
     * Transform}).
     */
    private static double[][] eclipticMatrix(double date) {
        double[][] precession = Pal.prec(2000.0, Pal.epj(date));
        double[][] rotate = Pal.ecmat(date);
        return Pal.dmxm(rotate, precession);
    }

    /** The frame-tie rotation from dynamical J2000.0 to ICRS (slamap.c {@code J2000H}). */
    private static double[][] j2000hMatrix() {
        return Pal.deuler("XYZ", -0.0068192 * AS2R, 0.0166172 * AS2R, 0.0146000 * AS2R);
    }

    /**
     * C's {@code De2h}: apparent (hour angle, declination) to topocentric (azimuth, elevation),
     * correcting for diurnal aberration of magnitude {@code diurab}, as SLALIB's {@code sla_AOPQK}
     * does, for an observer at geodetic latitude {@code phi}.
     */
    private static double[] de2h(double ha, double dec, double phi, double diurab) {
        double sh = Math.sin(ha);
        double ch = Math.cos(ha);
        double sd = Math.sin(dec);
        double cd = Math.cos(dec);
        double sp = Math.sin(phi);
        double cp = Math.cos(phi);
        // Components of the Cartesian (-ha, dec) vector.
        double xhd = ch * cd;
        double yhd = -sh * cd;
        double zhd = sd;
        // Diurnal aberration.
        double f = 1.0 - diurab * yhd;
        double xhdt = f * xhd;
        double yhdt = f * (yhd + diurab);
        double zhdt = f * zhd;
        // Cartesian (az, el).
        double x = -xhdt * sp + zhdt * cp;
        double y = yhdt;
        double z = xhdt * cp + zhdt * sp;
        // Spherical (az, el).
        double r = Math.sqrt(x * x + y * y);
        double a = r == 0.0 ? 0.0 : Math.atan2(y, x);
        while (a < 0.0) {
            a += 2 * Math.PI;
        }
        return new double[] {a, Math.atan2(z, r)};
    }

    /**
     * C's {@code Dh2e}: topocentric (azimuth, elevation) to apparent (hour angle, declination),
     * correcting for diurnal aberration of magnitude {@code diurab}, as SLALIB's {@code sla_OAPQK}
     * does, for an observer at geodetic latitude {@code phi}.
     */
    private static double[] dh2e(double az, double el, double phi, double diurab) {
        double sa = Math.sin(az);
        double ca = Math.cos(az);
        double se = Math.sin(el);
        double ce = Math.cos(el);
        double sp = Math.sin(phi);
        double cp = Math.cos(phi);
        // Cartesian (az, el) to Cartesian (ha, dec), +ha rather than -ha.
        double xmhda = -ca * ce * sp + se * cp;
        double ymhda = -sa * ce;
        double zmhda = ca * ce * cp + se * sp;
        // Diurnal aberration; diurab is not negated, as the vector is in +ha.
        double f = 1 - diurab * ymhda;
        double x = f * xmhda;
        double y = f * (ymhda + diurab);
        double z = f * zmhda;
        // Spherical (ha, dec).
        double r = Math.sqrt(x * x + y * y);
        double ha = r == 0.0 ? 0.0 : Math.atan2(y, x);
        return new double[] {ha, Math.atan2(z, r)};
    }

    // ------------------------------------------------------------------
    // Solar-system (STP) coordinate systems
    // ------------------------------------------------------------------

    /**
     * The spherical solar-system coordinate systems the SlaMap conversions use, each through its
     * Cartesian counterpart (C's {@code AST__HAQ}, {@code AST__HPC}, {@code AST__HPR} and {@code
     * AST__GSE} with {@code AST__HAQC}, {@code AST__HPCC}, {@code AST__HPRC} and {@code
     * AST__GSEC}).
     */
    private enum Stp {
        /** Heliocentric-Aries-Equatorial: J2000.0 equatorial. */
        HAQ,
        /** Helioprojective-Cartesian. */
        HPC,
        /** Helioprojective-Radial. */
        HPR,
        /** Geocentric-Solar-Ecliptic. */
        GSE
    }

    /** The STP system of a helioprojective or helio-ecliptic conversion. */
    private static Stp stpSystem(Cvt cvt) {
        // MODERN-JAVA(21): switch expression
        switch (cvt) {
            case HPCEQ:
            case EQHPC:
                return Stp.HPC;
            case HPREQ:
            case EQHPR:
                return Stp.HPR;
            case HEEQ:
            case EQHE:
                return Stp.GSE;
            default:
                throw Ast.err(AstException.AST__INTER, "SlaMap: " + cvt + " is not an STP conversion.");
        }
    }

    /**
     * The observer position of a helioprojective conversion, its last three arguments; null for
     * the helio-ecliptic ones, which take none.
     */
    private static double[] stpObserver(Step s) {
        return s.cvt.nargs() == 4 ? new double[] {s.args[1], s.args[2], s.args[3]} : null;
    }

    /**
     * The matrix rotating direction vectors from system {@code in} to system {@code out} at {@code
     * mjd}: C's {@code mat4} in {@code STPConv}, the product of the transpose of the output
     * system's rotation to the Heliocentric-Aries-Ecliptic Cartesian system with the input
     * system's.
     */
    private static double[][] stpMatrix(double mjd, Stp in, double[] inObs, Stp out, double[] outObs) {
        double[][] mat1 = toHaec(mjd, in, inObs);
        double[][] mat2 = toHaec(mjd, out, outObs);
        double[][] mat3 = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mat3[i][j] = mat2[j][i];
            }
        }
        return Pal.dmxm(mat3, mat1);
    }

    /**
     * The rotation of direction vectors from {@code sys} to the Heliocentric-Aries-Ecliptic
     * Cartesian system at {@code mjd}, whose columns are the system's unit vectors (slamap.c {@code
     * Haqc}, {@code Hpcc}, {@code Hprc} and {@code Gsec}).
     */
    private static double[][] toHaec(double mjd, Stp sys, double[] obs) {
        // MODERN-JAVA(21): switch expression
        switch (sys) {
            case HAQ:
                return Pal.ecmat(Pal.epj2d(2000.0));
            case HPC:
                return hpcc(mjd, obs);
            case HPR:
                return hprc(mjd, obs);
            case GSE:
                return gsec(mjd);
            default:
                throw Ast.err(AstException.AST__INTER, "SlaMap: unsupported STP system " + sys + ".");
        }
    }

    /** The matrix whose columns are {@code x}, {@code y} and {@code z}. */
    private static double[][] columns(double[] x, double[] y, double[] z) {
        double[][] mat = new double[3][3];
        for (int i = 0; i < 3; i++) {
            mat[i][0] = x[i];
            mat[i][1] = y[i];
            mat[i][2] = z[i];
        }
        return mat;
    }

    private static double[] negate(double[] v) {
        return new double[] {-v[0], -v[1], -v[2]};
    }

    /**
     * C's {@code Hpcc}: the Helioprojective-Cartesian axes for an observer at {@code obs} (the
     * Earth if null). X points from the observer to the centre of the Sun, Y is perpendicular to X
     * and the solar north pole, and Z completes a left-handed system.
     */
    private static double[][] hpcc(double mjd, double[] obs) {
        double[] o = obs == null ? earth(mjd) : obs;
        double[] xhpc = negate(Pal.dvn(o).unit);
        double[] pole = solarPole(mjd);
        double[] yhpc = Pal.dvn(Pal.dvxv(xhpc, pole)).unit;
        double[] zhpc = Pal.dvxv(yhpc, xhpc);
        return columns(xhpc, yhpc, zhpc);
    }

    /**
     * C's {@code Hprc}: the Helioprojective-Radial axes for an observer at {@code obs} (the Earth
     * if null). Z points from the observer to the centre of the Sun, Y is perpendicular to the
     * solar north pole and Z, and X completes a left-handed system.
     */
    private static double[][] hprc(double mjd, double[] obs) {
        double[] o = obs == null ? earth(mjd) : obs;
        double[] zhpr = negate(Pal.dvn(o).unit);
        double[] pole = solarPole(mjd);
        double[] yhpr = Pal.dvn(Pal.dvxv(pole, zhpr)).unit;
        double[] xhpr = Pal.dvxv(zhpr, yhpr);
        return columns(xhpr, yhpr, zhpr);
    }

    /**
     * C's {@code Gsec}: the Geocentric-Solar-Ecliptic axes. X points from the Earth to the centre
     * of the Sun, Y is perpendicular to X and the ecliptic north pole, and Z completes a
     * right-handed system.
     */
    private static double[][] gsec(double mjd) {
        double[] xgs = negate(Pal.dvn(earth(mjd)).unit);
        double[] ygs = Pal.dvn(Pal.dvxv(new double[] {0.0, 0.0, 1.0}, xgs)).unit;
        double[] zgs = Pal.dvxv(xgs, ygs);
        return columns(xgs, ygs, zgs);
    }

    /**
     * C's {@code Earth}: the heliocentric position of the Earth at {@code mjd} in the
     * Heliocentric-Aries-Ecliptic Cartesian system, in metres.
     */
    private static double[] earth(double mjd) {
        double[] dph = Pal.evp(mjd, 2000.0)[3];
        double[] e = Pal.dmxv(Pal.ecmat(Pal.epj2d(2000.0)), dph);
        return new double[] {e[0] * AU, e[1] * AU, e[2] * AU};
    }

    /**
     * C's {@code SolarPole}: a unit vector along the solar north pole at {@code mjd}, in the
     * Heliocentric-Aries-Ecliptic Cartesian system. The ecliptic longitude of the ascending node of
     * the solar equator is that of the Explanatory Supplement to the Astronomical Almanac, with
     * time in days since J2000.0, and the solar axis is inclined 7.25 degrees to the ecliptic axis.
     */
    private static double[] solarPole(double mjd) {
        double omega = 75.76 + 0.01397 * (Pal.epj(mjd) - 2000.0);
        double sproj = (omega - 90.0) * D2R;
        double inc = 7.25 * D2R;
        double t1 = Math.sin(inc);
        return new double[] {t1 * Math.cos(sproj), t1 * Math.sin(sproj), Math.cos(inc)};
    }

    /**
     * Rotates a direction by {@code mat4} as C's {@code STPConv} does: to Cartesian coordinates at
     * a radius of 1 au, rotated (the origins being ignored, with a zero offset), and back to
     * spherical coordinates with the longitude in [0, 2 pi).
     */
    private static PointConversion stpRotation(double[][] mat4) {
        return (a, d) -> {
            double[] q = Pal.dcs2c(a, d);
            double[] p = {q[0] * AU, q[1] * AU, q[2] * AU};
            double[] r = Pal.dmxv(mat4, p);
            double[] c = {r[0] + 0.0, r[1] + 0.0, r[2] + 0.0};
            double[] sph = Pal.dcc2s(Pal.dvn(c).unit);
            return new double[] {Pal.dranrm(sph[0]), sph[1]};
        };
    }
}
