package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.AstException;
import uk.ac.starlink.ast.internal.erfa.Anp;
import uk.ac.starlink.ast.internal.erfa.C2s;
import uk.ac.starlink.ast.internal.erfa.Cal2jd;
import uk.ac.starlink.ast.internal.erfa.D2tf;
import uk.ac.starlink.ast.internal.erfa.Ee06a;
import uk.ac.starlink.ast.internal.erfa.Eform;
import uk.ac.starlink.ast.internal.erfa.Epb;
import uk.ac.starlink.ast.internal.erfa.Epb2jd;
import uk.ac.starlink.ast.internal.erfa.Epj;
import uk.ac.starlink.ast.internal.erfa.Epj2jd;
import uk.ac.starlink.ast.internal.erfa.Erfa;
import uk.ac.starlink.ast.internal.erfa.Fk45z;
import uk.ac.starlink.ast.internal.erfa.Fk54z;
import uk.ac.starlink.ast.internal.erfa.Fk5hz;
import uk.ac.starlink.ast.internal.erfa.Gc2gd;
import uk.ac.starlink.ast.internal.erfa.Gc2gde;
import uk.ac.starlink.ast.internal.erfa.Gd2gc;
import uk.ac.starlink.ast.internal.erfa.Gmst06;
import uk.ac.starlink.ast.internal.erfa.Hfk5z;
import uk.ac.starlink.ast.internal.erfa.Jd2cal;
import uk.ac.starlink.ast.internal.erfa.Pas;
import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.Pn;
import uk.ac.starlink.ast.internal.erfa.Pxp;
import uk.ac.starlink.ast.internal.erfa.Rv2m;
import uk.ac.starlink.ast.internal.erfa.Rxp;
import uk.ac.starlink.ast.internal.erfa.Rxr;
import uk.ac.starlink.ast.internal.erfa.S2c;
import uk.ac.starlink.ast.internal.erfa.Seps;
import uk.ac.starlink.ast.internal.erfa.Tf2d;
import uk.ac.starlink.ast.internal.erfa.Trxp;

/**
 * The positional-astronomy routines AST uses, ported from PAL (Positional Astronomy Library).
 *
 * <p>SlaMap, SkyFrame, SpecMap, TimeMap and FitsChan call these functions. Each routine whose
 * callers ask for one value of its kind (an epoch conversion, a rotation matrix, a velocity) has
 * its body in a class of its own in this package, which the function here calls; the bodies depend
 * on the ERFA ports in {@code internal.erfa}, never on mappings, frames or points. Angles are in
 * radians, dates are MJD, 3-vectors are {@code double[3]} and 3x3 matrices {@code double[3][3]} by
 * rows. A status that C PAL returns as an integer is a typed result here: an error is a null result
 * or an error field, and a warning that comes with a value is a field beside it.
 *
 * <p>Ported from Rust {@code pal/mod.rs}.
 */
public final class Pal {

    private Pal() {}

    /** AST's bad value, {@code AST__BAD}. */
    private static final double BAD_VALUE = -Double.MAX_VALUE;

    /** PAL's {@code PAL__DPI} (palmac.h): pi. */
    public static final double PAL__DPI = 3.141592653589793;

    /** PAL's {@code PAL__MJD0} (palmac.h): the zero point of the Modified Julian Date, as a Julian Date. */
    public static final double PAL__MJD0 = 2400000.5;

    /** PAL's {@code PAL__D2PI} (palmac.h): 2 pi. */
    public static final double PAL__D2PI = 6.283185307179586;

    /** PAL's {@code PAL__SR} (palmac.h): the Earth's rotation rate in radians per second. */
    public static final double PAL__SR = 7.2921150e-5;

    /** PAL's {@code PAL__DAS2R} (palmac.h): arcseconds to radians. */
    public static final double PAL__DAS2R = 4.84813681109536E-6;

    /** PAL's {@code PAL__CR} (palmac.h): the light time for 1 au, in seconds. */
    public static final double PAL__CR = 499.004782;

    /** PAL's {@code PAL__SPD} (palmac.h): seconds per day. */
    public static final double PAL__SPD = 86400.0;

    /**
     * The astronomical unit in metres, the IAU 2012 value ERFA uses. PAL's routines convert metres
     * to au with it, so a caller that converts their output back must use the same value.
     */
    public static final double ERFA_DAU = Erfa.ERFA_DAU;

    // ---------------------------------------------------------------------------
    // Epoch conversions
    // ---------------------------------------------------------------------------

    /** Converts a Modified Julian Date to a Julian epoch. */
    public static double epj(double date) {
        // palEpj is eraEpj.
        return Epj.epj(PAL__MJD0, date);
    }

    /** Converts a Julian epoch to a Modified Julian Date. */
    public static double epj2d(double epj) {
        // palEpj2d is eraEpj2jd's MJD part.
        return Epj2jd.epj2jd(epj)[1];
    }

    /** Converts a Modified Julian Date to a Besselian epoch. */
    public static double epb(double date) {
        // palEpb is eraEpb.
        return Epb.epb(PAL__MJD0, date);
    }

    /** Converts a Besselian epoch to a Modified Julian Date. */
    public static double epb2d(double epb) {
        // palEpb2d is eraEpb2jd's MJD part.
        return Epb2jd.epb2jd(epb)[1];
    }

    // ---------------------------------------------------------------------------
    // Spherical <-> Cartesian
    // ---------------------------------------------------------------------------

    /** Spherical coordinates (longitude, latitude) to a direction-cosine 3-vector. */
    public static double[] dcs2c(double a, double b) {
        // palDcs2c is eraS2c.
        return S2c.s2c(a, b);
    }

    /**
     * Direction-cosine 3-vector to spherical coordinates {@code {longitude, latitude}}.
     *
     * <p>The components are canonicalised so a negative zero becomes a positive zero before {@code
     * eraC2s}'s {@code atan2(y, x)}. A point on the -x meridian arrives from the rotation pipeline
     * with {@code y == -0.0} or {@code +0.0} depending only on the summation order of the matrix
     * product; C's compressed-matrix arithmetic yields {@code +0.0} there, so {@code atan2(+0, -1) =
     * +pi}. Without this, a {@code -0.0} from another product order would give {@code -pi}: the same
     * physical point but the opposite representation from C (for example a Mercator fiducial at RA =
     * 180 degrees). {@code x + 0.0} is the identity for every value except {@code -0.0 -> +0.0}.
     */
    public static double[] dcc2s(double[] v) {
        // palDcc2s is eraC2s.
        return C2s.c2s(new double[] {v[0] + 0.0, v[1] + 0.0, v[2] + 0.0});
    }

    /**
     * {@link #dcs2c} for every (longitude, latitude) pair of {@code a} and {@code b}, into {@code
     * x}, {@code y} and {@code z}; each point gets the same bits whatever the arrays' length. An
     * AST__BAD coordinate gives AST__BAD outputs, without the cost of computing them.
     */
    // MODERN-JAVA(9): Rust runs a branchless kernel over every point, built for AVX2 and FMA where
    // the CPU has them, and gives the points outside its domain to dcs2c; that needs Math.fma and
    // the Vector API, so here every point goes through dcs2c.
    public static void dcs2cSlice(double[] a, double[] b, double[] x, double[] y, double[] z) {
        int n = a.length;
        checkLengths("dcs2cSlice", n, b, x, y, z);
        for (int i = 0; i < n; i++) {
            if (a[i] == BAD_VALUE || b[i] == BAD_VALUE) {
                x[i] = BAD_VALUE;
                y[i] = BAD_VALUE;
                z[i] = BAD_VALUE;
            } else {
                double[] v = S2c.s2c(a[i], b[i]);
                x[i] = v[0];
                y[i] = v[1];
                z[i] = v[2];
            }
        }
    }

    /**
     * {@link #dcc2s} for every vector ({@code x[i]}, {@code y[i]}, {@code z[i]}), into {@code a}
     * and {@code b}, signed-zero canonicalisation included; each point gets the same bits whatever
     * the arrays' length. An AST__BAD coordinate gives AST__BAD outputs.
     */
    // MODERN-JAVA(9): Rust runs a branchless kernel over every point, built for AVX2 and FMA where
    // the CPU has them, and gives the points outside its domain to dcc2s; that needs Math.fma and
    // the Vector API, so here every point goes through dcc2s.
    public static void dcc2sSlice(double[] x, double[] y, double[] z, double[] a, double[] b) {
        int n = x.length;
        checkLengths("dcc2sSlice", n, y, z, a, b);
        for (int i = 0; i < n; i++) {
            double[] p = {x[i] + 0.0, y[i] + 0.0, z[i] + 0.0};
            if (p[0] == BAD_VALUE || p[1] == BAD_VALUE || p[2] == BAD_VALUE) {
                a[i] = BAD_VALUE;
                b[i] = BAD_VALUE;
            } else {
                double[] ab = C2s.c2s(p);
                a[i] = ab[0];
                b[i] = ab[1];
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Vector / matrix operations
    // ---------------------------------------------------------------------------

    /** Multiplies a 3x3 matrix by a 3-vector (forward transformation). */
    public static double[] dmxv(double[][] dm, double[] va) {
        // palDmxv is eraRxp.
        return Rxp.rxp(dm, va);
    }

    /** Multiplies the inverse (transpose) of a 3x3 rotation matrix by a 3-vector. */
    public static double[] dimxv(double[][] dm, double[] va) {
        // palDimxv is eraTrxp.
        return Trxp.trxp(dm, va);
    }

    /** Multiplies two 3x3 matrices: {@code a * b}. */
    public static double[][] dmxm(double[][] a, double[][] b) {
        // palDmxm is eraRxr.
        return Rxr.rxr(a, b);
    }

    /** Cross product of two 3-vectors. */
    public static double[] dvxv(double[] va, double[] vb) {
        // palDvxv is eraPxp.
        return Pxp.pxp(va, vb);
    }

    /** Dot product of two 3-vectors. */
    public static double dvdv(double[] va, double[] vb) {
        // palDvdv is eraPdp.
        return Pdp.pdp(va, vb);
    }

    /** Normalises a 3-vector, giving its unit vector and modulus. */
    public static Pn.Result dvn(double[] v) {
        // palDvn is eraPn.
        return Pn.pn(v);
    }

    /**
     * Matrix inversion and linear-system solution, {@code palDmat}. {@code a} is an {@code n} by
     * {@code n} matrix in row order and {@code y} the right-hand side vector of length {@code n}.
     * On success (returns true) {@code a} is replaced by its inverse and {@code y} by the solution
     * of {@code a.x = y} (for the original {@code a}). Returns false when the matrix is singular (a
     * pivot magnitude below {@code palDmat}'s 1e-20); {@code a} and {@code y} then hold what {@code
     * palDmat} leaves in them, the rows it could pivot on having been reduced, which C's fitschan.c
     * {@code MakeIntWorld} uses regardless.
     */
    public static boolean dmat(int n, double[] a, double[] y) {
        return dmatDet(n, a, y) != 0.0;
    }

    /**
     * {@link #dmat}, returning {@code palDmat}'s determinant of {@code a}, which is zero exactly
     * when the matrix is singular.
     */
    public static double dmatDet(int n, double[] a, double[] y) {
        return Dmat.dmat(n, a, y);
    }

    // ---------------------------------------------------------------------------
    // Coordinate conversions
    // ---------------------------------------------------------------------------

    /** Galactic (l, b) to FK5 J2000 {@code {ra, dec}}, all in radians. */
    public static double[] galeq(double dl, double db) {
        return Galeq.galeq(dl, db);
    }

    /** FK5 J2000 (ra, dec) to galactic {@code {l, b}}, all in radians. */
    public static double[] eqgal(double dr, double dd) {
        return Eqgal.eqgal(dr, dd);
    }

    /** Galactic (l, b) to supergalactic {@code {sl, sb}}, all in radians. */
    public static double[] galsup(double dl, double db) {
        return Galsup.galsup(dl, db);
    }

    /** Supergalactic (sl, sb) to galactic {@code {l, b}}, all in radians. */
    public static double[] supgal(double dsl, double dsb) {
        return Supgal.supgal(dsl, dsb);
    }

    /**
     * FK4 B1950 (ra, dec) to FK5 J2000 {@code {ra, dec}}, assuming zero proper motion: ERFA's
     * {@code eraFk45z}, which the C library's SlaMap calls.
     */
    public static double[] fk45z(double r1950, double d1950, double bepoch) {
        return Fk45z.fk45z(r1950, d1950, bepoch);
    }

    /**
     * {@link #fk45z} for every position ({@code r1950[i]}, {@code d1950[i]}) at the Besselian epoch
     * {@code bepoch}, into {@code r2000} and {@code d2000}; each position gets the same bits
     * whatever the arrays' length.
     */
    // MODERN-JAVA(9): Rust runs a branchless kernel over every position, built for AVX2 and FMA
    // where the CPU has them, and gives the positions outside its domain to fk45z; that needs
    // Math.fma and the Vector API, so here every position goes through fk45z.
    public static void fk45zSlice(double bepoch, double[] r1950, double[] d1950, double[] r2000, double[] d2000) {
        int n = r1950.length;
        checkLengths("fk45zSlice", n, d1950, r2000, d2000);
        for (int i = 0; i < n; i++) {
            double[] rd = Fk45z.fk45z(r1950[i], d1950[i], bepoch);
            r2000[i] = rd[0];
            d2000[i] = rd[1];
        }
    }

    /**
     * FK5 J2000 (ra, dec) to FK4 B1950 {@code {ra, dec, dra, ddec}}, the last two the fictitious
     * proper motion: ERFA's {@code eraFk54z}, which the C library's SlaMap calls.
     */
    public static double[] fk54z(double r2000, double d2000, double bepoch) {
        return Fk54z.fk54z(r2000, d2000, bepoch);
    }

    /**
     * {@link #fk54z}'s (ra, dec) for every position ({@code r2000[i]}, {@code d2000[i]}) at the
     * Besselian epoch {@code bepoch}, into {@code r1950} and {@code d1950}; each position gets the
     * same bits whatever the arrays' length.
     */
    // MODERN-JAVA(9): Rust runs a branchless kernel over every position, built for AVX2 and FMA
    // where the CPU has them, and gives the positions outside its domain to fk54z; that needs
    // Math.fma and the Vector API, so here every position goes through fk54z.
    public static void fk54zSlice(double bepoch, double[] r2000, double[] d2000, double[] r1950, double[] d1950) {
        int n = r2000.length;
        checkLengths("fk54zSlice", n, d2000, r1950, d1950);
        for (int i = 0; i < n; i++) {
            double[] rd = Fk54z.fk54z(r2000[i], d2000[i], bepoch);
            r1950[i] = rd[0];
            d1950[i] = rd[1];
        }
    }

    /** FK5 J2000 to ICRS (Hipparcos frame) {@code {ra, dec}}, assuming zero proper motion. */
    public static double[] fk5hz(double r5, double d5, double epoch) {
        // palFk5hz converts the epoch to a Julian Date, then calls eraFk5hz.
        double[] d = Epj2jd.epj2jd(epoch);
        return Fk5hz.fk5hz(r5, d5, d[0], d[1]);
    }

    /**
     * ICRS (Hipparcos frame) to FK5 J2000 {@code {r5, d5, dr5, dd5}}, assuming zero proper motion.
     */
    public static double[] hfk5z(double rh, double dh, double epoch) {
        // palHfk5z converts the epoch to a Julian Date, then calls eraHfk5z.
        double[] d = Epj2jd.epj2jd(epoch);
        return Hfk5z.hfk5z(rh, dh, d[0], d[1]);
    }

    // ---------------------------------------------------------------------------
    // Precession matrices
    // ---------------------------------------------------------------------------

    /** The Julian (FK5) precession matrix from Julian epoch {@code ep0} to {@code ep1}. */
    public static double[][] prec(double ep0, double ep1) {
        return Prec.prec(ep0, ep1);
    }

    /** The Besselian (FK4) precession matrix from Besselian epoch {@code bep0} to {@code bep1}. */
    public static double[][] prebn(double bep0, double bep1) {
        return Prebn.prebn(bep0, bep1);
    }

    /** The equatorial to ecliptic rotation matrix for a TDB date (MJD). */
    public static double[][] ecmat(double date) {
        return Ecmat.ecmat(date);
    }

    // ---------------------------------------------------------------------------
    // Euler rotation
    // ---------------------------------------------------------------------------

    /**
     * A rotation matrix from Euler angles: {@code order} names the rotation axes as a 1 to 3
     * character string, such as {@code "XYZ"} or {@code "ZYZ"}, each character selecting the axis
     * about which the corresponding rotation is applied.
     */
    public static double[][] deuler(String order, double phi, double theta, double psi) {
        return Deuler.deuler(order, phi, theta, psi);
    }

    // ---------------------------------------------------------------------------
    // Aberration
    // ---------------------------------------------------------------------------

    /** Adds the E-terms of aberration, giving the apparent {@code {rc, dc}}. */
    public static double[] addet(double rm, double dm, double eq) {
        return Addet.addet(rm, dm, eq);
    }

    /** Removes the E-terms of aberration, giving the mean {@code {rm, dm}}. */
    public static double[] subet(double rc, double dc, double eq) {
        return Subet.subet(rc, dc, eq);
    }

    /**
     * The 21 star-independent mean-to-apparent parameters for an equinox and date, used by {@link
     * #ampqk} and {@link #mapqkz}.
     */
    public static double[] mappa(double eq, double date) {
        return Mappa.mappa(eq, date);
    }

    /** Quick apparent (ra, dec) to mean {@code {ra, dec}}, using parameters from {@link #mappa}. */
    public static double[] ampqk(double ra, double da, double[] amprms) {
        return Ampqk.ampqk(ra, da, amprms);
    }

    /**
     * Quick mean (ra, dec) to apparent {@code {ra, dec}}, assuming zero proper motion, using
     * parameters from {@link #mappa}.
     */
    public static double[] mapqkz(double rm, double dm, double[] amprms) {
        return Mapqkz.mapqkz(rm, dm, amprms);
    }

    // ---------------------------------------------------------------------------
    // Angular normalisation
    // ---------------------------------------------------------------------------

    /** Normalises an angle to the range -pi to pi. */
    public static double drange(double angle) {
        return Drange.drange(angle);
    }

    /** Normalises an angle to the range 0 to 2 pi. */
    public static double dranrm(double angle) {
        // palDranrm is eraAnp.
        return Anp.anp(angle);
    }

    /** Angular separation between two points on a sphere (radians). */
    public static double dsep(double al, double ap, double bl, double bp) {
        // palDsep is eraSeps.
        return Seps.seps(al, ap, bl, bp);
    }

    /** Bearing (position angle) of point B as seen from point A (radians). */
    public static double dbear(double a1, double b1, double a2, double b2) {
        // palDbear is eraPas.
        return Pas.pas(a1, b1, a2, b2);
    }

    // ---------------------------------------------------------------------------
    // Earth / observer
    // ---------------------------------------------------------------------------

    /**
     * Earth velocity and position, barycentric and heliocentric: {@code {dvb, dpb, dvh, dph}}, the
     * barycentric velocity (au/s) and position (au), then the heliocentric velocity and position.
     */
    public static double[][] evp(double date, double deqx) {
        return Evp.evp(date, deqx);
    }

    /**
     * Geodetic latitude {@code p} (radians) and height {@code h} (metres) to {@code {r, z}}, the
     * distance from the spin axis and from the equatorial plane, in au.
     */
    public static double[] geoc(double p, double h) {
        return Geoc.geoc(p, h);
    }

    // ---------------------------------------------------------------------------
    // Miscellaneous
    // ---------------------------------------------------------------------------

    /**
     * Axial vector to rotation matrix: the direction of the axial vector is the Euler axis, and its
     * magnitude is the rotation angle in radians.
     */
    public static double[][] dav2m(double[] axvec) {
        // palDav2m is eraRv2m.
        return Rv2m.rv2m(axvec);
    }

    // ---------------------------------------------------------------------------
    // Time / sidereal
    // ---------------------------------------------------------------------------

    /** TAI minus UTC, in seconds, for a UTC date (MJD). */
    public static double dat(double mjd) {
        return Dat.dat(mjd);
    }

    /** Greenwich Mean Sidereal Time (radians) for a UT1 date (MJD). */
    public static double gmst(double ut1) {
        // palGmst is eraGmst06 with UT1 standing in for TT.
        return Gmst06.gmst06(PAL__MJD0, ut1, PAL__MJD0, ut1);
    }

    /** Equation of the equinoxes (radians) for a TDB date (MJD). */
    public static double eqeqx(double date) {
        // palEqeqx is eraEe06a.
        return Ee06a.ee06a(PAL__MJD0, date);
    }

    // ---------------------------------------------------------------------------
    // Observer / velocity
    // ---------------------------------------------------------------------------

    /**
     * Geodetic position (east longitude {@code lon}, latitude {@code lat}, both in radians, and
     * {@code height} in metres above the WGS84 ellipsoid) to the geocentric Cartesian position
     * vector in metres, as {@code eraGd2gc(1, ...)} gives it; null where ERFA signals an error.
     */
    public static double[] gd2gc(double lon, double lat, double height) {
        return Gd2gc.gd2gc(Eform.Ellipsoid.WGS84, lon, lat, height);
    }

    /**
     * Geocentric Cartesian position (metres) to {@code {lon, lat, height}}, the geodetic longitude
     * and latitude in radians and the height in metres above the WGS84 ellipsoid, as {@code
     * eraGc2gd(1, ...)} gives them; null where ERFA signals an error.
     */
    public static double[] gc2gd(double[] xyz) {
        Gc2gde.Result r = Gc2gd.gc2gd(Eform.Ellipsoid.WGS84, xyz);
        return r.error == null ? new double[] {r.elong, r.phi, r.height} : null;
    }

    /**
     * Observer position and velocity as a 6-vector {@code {x, y, z, xdot, ydot, zdot}} in au and
     * au/s, from the geodetic latitude {@code lat} (radians), the altitude {@code alt} above the
     * reference spheroid (metres) and the local apparent sidereal time {@code lst} (radians).
     */
    public static double[] pvobs(double lat, double alt, double lst) {
        return Pvobs.pvobs(lat, alt, lst);
    }

    /** Component of the galactic rotation velocity towards a J2000 (ra, dec) (km/s). */
    public static double rvgalc(double ra, double dec) {
        return Rvgalc.rvgalc(ra, dec);
    }

    /** Component of the Local Group velocity towards a J2000 (ra, dec) (km/s). */
    public static double rvlg(double ra, double dec) {
        return Rvlg.rvlg(ra, dec);
    }

    /** Component of the dynamical LSR velocity towards a J2000 (ra, dec) (km/s). */
    public static double rvlsrd(double ra, double dec) {
        return Rvlsrd.rvlsrd(ra, dec);
    }

    /** Component of the kinematic LSR velocity towards a J2000 (ra, dec) (km/s). */
    public static double rvlsrk(double ra, double dec) {
        return Rvlsrk.rvlsrk(ra, dec);
    }

    // ---------------------------------------------------------------------------
    // Calendar / time-of-day conversions
    // ---------------------------------------------------------------------------

    /**
     * Modified Julian Date to Gregorian calendar fields; null when {@code djm} is outside the range
     * the conversion supports. A NaN date is accepted, as in ERFA, and gives a NaN fraction.
     */
    public static Jd2cal.Result djcl(double djm) {
        // palDjcl is eraJd2cal with the MJD zero point.
        return Jd2cal.jd2cal(PAL__MJD0, djm);
    }

    /**
     * Gregorian calendar date to Modified Julian Date, which is the result's {@code djm}, with the
     * warning {@link Cal2jd.Cal2jdWarning#BAD_DAY} when the day is not a day of that month, or the
     * error for an invalid year or month.
     */
    public static Cal2jd.Result cldj(int iy, int im, int id) {
        // palCldj is eraCal2jd's MJD part.
        return Cal2jd.cal2jd(iy, im, id);
    }

    /**
     * Day fraction to hours, minutes and seconds: {@code ndp} is the number of decimal places of
     * seconds, and the fraction is the {@code ndp}-digit fractional seconds field as an integer.
     */
    public static D2tf.Result dd2tf(int ndp, double days) {
        // palDd2tf is eraD2tf.
        return D2tf.d2tf(ndp, days);
    }

    /** Hours, minutes and seconds to a day fraction, with the warning for the first field out of range. */
    public static Tf2d.Result dtf2d(int ihour, int imin, double sec) {
        // palDtf2d is eraTf2d with a blank sign.
        return Tf2d.tf2d(' ', ihour, imin, sec);
    }

    /** Raises AST__INTER unless every array has length {@code n}, as Rust's slice functions assert. */
    private static void checkLengths(String what, int n, double[]... arrays) {
        for (double[] v : arrays) {
            if (v.length != n) {
                throw new AstException(
                        what + ": arrays of lengths " + n + " and " + v.length + " given", AstException.AST__INTER);
            }
        }
    }
}
