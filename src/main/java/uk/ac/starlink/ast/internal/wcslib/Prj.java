package uk.ac.starlink.ast.internal.wcslib;

import java.util.Arrays;
import uk.ac.starlink.ast.AstException;

/**
 * A projection with its parameters, set up: the native projection state and its dispatch to the
 * projection functions of the family classes.
 *
 * <p>wcslib keeps a projection's parameters, its derived constants and a {@code flag} that says
 * whether the constants are current in one struct, and every {@code fwd} and {@code rev} function
 * calls the projection's set-up function first whenever {@code flag} does not name the projection.
 * AST clears {@code flag} whenever a parameter changes and never sets it to a negative value, so
 * after set-up {@code flag} is always positive and every bounds test that the projection bodies
 * make only when {@code flag} is positive is made. Here {@link #create} runs the set-up once,
 * eagerly, and the bodies apply those tests unconditionally. A set-up that fails leaves its status
 * here, and every call returns it, so the error surfaces at the first point that reaches the
 * projection as it does in C. (wcslib's set-up functions write {@code flag} before they can fail,
 * so after a failed set-up C runs the projection bodies on half-initialised constants at the second
 * and later calls; this class keeps returning the set-up status instead.)
 *
 * <p>The per-value entries, {@link #fwdRaw} and {@link #revRaw}, run the projection bodies and give
 * wcslib's results; the slice entries, {@link #fwdSlice} and {@link #revSlice}, do the same for
 * every pair of two arrays in one pass.
 *
 * <p>Instances are immutable, so they may be shared.
 *
 * <p>Ported from Rust {@code wcslib/prj.rs}.
 */
// MODERN-JAVA(9): Rust's slice entries run the trigonometry of many projections through
// branchless kernels over whole arrays (its KernelBody bodies and kernel_slice loops, with an
// AVX2 and FMA build at wcslib/prj.rs:1422), redoing per point those the kernels do not cover;
// they rely on fused multiply-adds (Math.fma), and the Vector API would express them. Here every
// slice runs the per-value body for each pair, the loop Rust runs for the projections that do not
// opt in.
public final class Prj {

    /** AST's bad value, {@code AST__BAD}. */
    static final double BAD = -Double.MAX_VALUE;

    /** The status wcslib's projection functions return. */
    public enum Status {
        /** The point was projected. */
        OK(0),
        /** The projection parameters are unusable. */
        BAD_PARAMS(1),
        /** The point cannot be projected. */
        BAD_POINT(2);

        private final int value;

        Status(int value) {
            this.value = value;
        }

        /**
         * The integer wcslib returns for this status.
         *
         * @return 0, 1 or 2
         */
        public int code() {
            return value;
        }
    }

    /**
     * The parameters of a projection and the constants derived from them, without wcslib's
     * bookkeeping (wcslib's {@code AstPrjPrm}).
     */
    static final class PrjPrm {
        /** Native longitude of the fiducial point, in degrees. */
        double phi0;

        /** Native latitude of the fiducial point, in degrees. */
        double theta0;

        /** The radius of the generating sphere; the set-up resets 0 to 180/pi. */
        double r0;

        /** The latitude-axis projection parameters. */
        final double[] p = new double[WcsProjection.PRJ_PARAM_SLOTS];

        /** The longitude-axis projection parameters (TPN only). */
        final double[] p2 = new double[WcsProjection.PRJ_PARAM_SLOTS];

        /** Constants the set-up derives from the parameters. */
        final double[] w = new double[20];

        /**
         * The projection's integer constant: ZPN's polynomial degree, HPX's {@code n}, TPN's
         * "include the TAN projection" flag.
         */
        int n;
    }

    /**
     * A function of a pair of values that gives a pair of values, used to prepare the inputs and
     * finish the outputs of a slice entry.
     */
    @FunctionalInterface
    public interface PairFunction {
        /**
         * Computes the pair for ({@code u}, {@code v}).
         *
         * @param u the first value
         * @param v the second value
         * @param out receives the two results
         */
        void apply(double u, double v, double[] out);
    }

    private final WcsProjection proj;
    private final PrjPrm prm;

    /** The status of the set-up, returned by every call when it failed. */
    private final Status setup;

    private Prj(WcsProjection proj, PrjPrm prm, Status setup) {
        this.proj = proj;
        this.prm = prm;
        this.setup = setup;
    }

    /** The parameters and the constants derived from them, which tests read. */
    PrjPrm params() {
        return prm;
    }

    /** The projection performed. */
    WcsProjection projection() {
        return proj;
    }

    /**
     * A projection of the given type with the parameters {@code params} and defaults for those not
     * supplied, set up. A failed set-up is not an error here: its status is returned by the first
     * call that reaches the projection.
     *
     * @param proj the projection; not NCP, GLS or UNKNOWN, which a WcsMap converts to another
     *     projection before it gets here
     * @param lat the latitude-axis parameters supplied, {@code AST__BAD} for one not supplied;
     *     those beyond the array are not supplied
     * @param lon the longitude-axis parameters, likewise
     * @param tpnTan for TPN, whether the projection includes the TAN projection; ignored otherwise
     * @return the projection
     * @throws AstException with status {@code AST__WCSTY} for NCP, GLS and UNKNOWN
     */
    public static Prj create(WcsProjection proj, double[] lat, double[] lon, boolean tpnTan) {
        if (proj == WcsProjection.NCP || proj == WcsProjection.GLS || proj == WcsProjection.UNKNOWN) {
            throw new AstException(
                    "Projection " + proj.code()
                            + " cannot be initialised directly and must be handled at the WcsMap level",
                    AstException.AST__WCSTY);
        }
        PrjPrm prm = new PrjPrm();
        initParams(proj, prm, lat, lon, tpnTan);
        Status setup = runSetup(proj, prm);
        return new Prj(proj, prm, setup);
    }

    /**
     * Reads a projection parameter, with its default applied.
     *
     * @param axis 0 for the latitude axis and 1 for the longitude axis
     * @param m the parameter index
     * @return the parameter, or null if either index is out of range
     */
    public Double getParam(int axis, int m) {
        if (m < 0 || m >= WcsProjection.PRJ_PARAM_SLOTS) {
            return null;
        }
        switch (axis) {
            case 0:
                return prm.p[m];
            case 1:
                return prm.p2[m];
            default:
                return null;
        }
    }

    /**
     * Forward projection of native ({@code phi}, {@code theta}) in degrees. The outputs are
     * whatever the projection wrote before returning, which callers such as the longitude-range
     * probe read even for {@link Status#BAD_POINT}; a failed set-up gives zeros.
     *
     * @param phi the native longitude in degrees
     * @param theta the native latitude in degrees
     * @param out receives the projected (x, y)
     * @return the status
     */
    public Status fwdRaw(double phi, double theta, double[] out) {
        if (setup != Status.OK) {
            out[0] = 0.0;
            out[1] = 0.0;
            return setup;
        }
        return fwd(proj, prm, phi, theta, out);
    }

    /**
     * Reverse projection of ({@code x}, {@code y}). The outputs are whatever the projection wrote
     * before returning, as for {@link #fwdRaw}.
     *
     * @param x the projected x
     * @param y the projected y
     * @param out receives the native (phi, theta) in degrees
     * @return the status
     */
    public Status revRaw(double x, double y, double[] out) {
        if (setup != Status.OK) {
            out[0] = 0.0;
            out[1] = 0.0;
            return setup;
        }
        return rev(proj, prm, x, y, out);
    }

    /**
     * {@link #fwdRaw} in place for each of the first {@code n} pairs ({@code a[i]}, {@code b[i]}),
     * in one pass over the arrays: {@code prep} gives the native (phi, theta) in degrees to project
     * from the pair on entry, and {@code finish} the pair to store from the projected (x, y). A pair
     * that {@code prep} gives as {@code AST__BAD} or NaN, and a point the projection cannot project,
     * give {@code finish(AST__BAD, AST__BAD)}.
     *
     * @param a the first values
     * @param b the second values
     * @param n the number of pairs
     * @param prep prepares the inputs
     * @param finish gives the outputs
     * @return true, or false if the projection parameters are unusable for a pair that is not bad,
     *     in which case the arrays hold partial results, whose contents are unspecified
     */
    public boolean fwdSlice(double[] a, double[] b, int n, PairFunction prep, PairFunction finish) {
        return sliceApply(a, b, n, prep, true, finish);
    }

    /**
     * {@link #revRaw} in place for each of the first {@code n} pairs ({@code a[i]}, {@code b[i]}),
     * in one pass: {@code prep} gives the (x, y) to project from the pair on entry, and {@code
     * finish} the pair to store from the native (phi, theta) in degrees; bad pairs and errors as
     * for {@link #fwdSlice}.
     *
     * @param a the first values
     * @param b the second values
     * @param n the number of pairs
     * @param prep prepares the inputs
     * @param finish gives the outputs
     * @return true, or false if the projection parameters are unusable for a pair that is not bad
     */
    public boolean revSlice(double[] a, double[] b, int n, PairFunction prep, PairFunction finish) {
        return sliceApply(a, b, n, prep, false, finish);
    }

    /**
     * Applies the projection in direction {@code forward} in place to each pair ({@code a[i]},
     * {@code b[i]}), in one pass: {@code prep} gives the pair to project, a pair holding {@code
     * AST__BAD} or NaN and a point the projection cannot project give ({@code AST__BAD}, {@code
     * AST__BAD}), and {@code finish} gives the pair stored from the result.
     */
    private boolean sliceApply(double[] a, double[] b, int n, PairFunction prep, boolean forward, PairFunction finish) {
        double[] uv = new double[2];
        double[] pq = new double[2];
        double[] res = new double[2];
        for (int i = 0; i < n; i++) {
            prep.apply(a[i], b[i], uv);
            double u = uv[0];
            double v = uv[1];
            if (u == BAD || v == BAD || Double.isNaN(u) || Double.isNaN(v)) {
                pq[0] = BAD;
                pq[1] = BAD;
            } else {
                Status status = forward ? fwdRaw(u, v, res) : revRaw(u, v, res);
                // MODERN-JAVA(21): switch expression over the enum
                switch (status) {
                    case OK:
                        pq[0] = res[0];
                        pq[1] = res[1];
                        break;
                    case BAD_POINT:
                        pq[0] = BAD;
                        pq[1] = BAD;
                        break;
                    case BAD_PARAMS:
                        return false;
                    default:
                        throw new AstException("unexpected projection status " + status, AstException.AST__INTER);
                }
            }
            finish.apply(pq[0], pq[1], uv);
            a[i] = uv[0];
            b[i] = uv[1];
        }
        return true;
    }

    /** The forward projection function of {@code proj}. */
    private static Status fwd(WcsProjection proj, PrjPrm prm, double phi, double theta, double[] out) {
        // MODERN-JAVA(21): switch expression over the enum
        switch (proj) {
            case AZP:
                return Zenithal.azpFwd(prm, phi, theta, out);
            case SZP:
                return Zenithal.szpFwd(prm, phi, theta, out);
            case TAN:
                return Zenithal.tanFwd(prm, phi, theta, out);
            case STG:
                return Zenithal.stgFwd(prm, phi, theta, out);
            case SIN:
                return Zenithal.sinFwd(prm, phi, theta, out);
            case ARC:
                return Zenithal.arcFwd(prm, phi, theta, out);
            case ZPN:
                return Zenithal.zpnFwd(prm, phi, theta, out);
            case ZEA:
                return Zenithal.zeaFwd(prm, phi, theta, out);
            case AIR:
                return Zenithal.airFwd(prm, phi, theta, out);
            case TPN:
                return Tpn.tpnFwd(prm, phi, theta, out);
            case CYP:
                return Cylindrical.cypFwd(prm, phi, theta, out);
            case CEA:
                return Cylindrical.ceaFwd(prm, phi, theta, out);
            case CAR:
                return Cylindrical.carFwd(prm, phi, theta, out);
            case MER:
                return Cylindrical.merFwd(prm, phi, theta, out);
            case SFL:
                return Pseudocyl.sflFwd(prm, phi, theta, out);
            case PAR:
                return Pseudocyl.parFwd(prm, phi, theta, out);
            case MOL:
                return Pseudocyl.molFwd(prm, phi, theta, out);
            case AIT:
                return Pseudocyl.aitFwd(prm, phi, theta, out);
            case COP:
                return Conic.copFwd(prm, phi, theta, out);
            case COE:
                return Conic.coeFwd(prm, phi, theta, out);
            case COD:
                return Conic.codFwd(prm, phi, theta, out);
            case COO:
                return Conic.cooFwd(prm, phi, theta, out);
            case BON:
                return Polyconic.bonFwd(prm, phi, theta, out);
            case PCO:
                return Polyconic.pcoFwd(prm, phi, theta, out);
            case TSC:
                return Quadcube.tscFwd(prm, phi, theta, out);
            case CSC:
                return Quadcube.cscFwd(prm, phi, theta, out);
            case QSC:
                return Quadcube.qscFwd(prm, phi, theta, out);
            case HPX:
                return Healpix.hpxFwd(prm, phi, theta, out);
            case XPH:
                return Healpix.xphFwd(prm, phi, theta, out);
            default:
                throw new AstException(proj.code() + " is rejected by Prj.create", AstException.AST__INTER);
        }
    }

    /** The reverse projection function of {@code proj}. */
    private static Status rev(WcsProjection proj, PrjPrm prm, double x, double y, double[] out) {
        // MODERN-JAVA(21): switch expression over the enum
        switch (proj) {
            case AZP:
                return Zenithal.azpRev(prm, x, y, out);
            case SZP:
                return Zenithal.szpRev(prm, x, y, out);
            case TAN:
                return Zenithal.tanRev(prm, x, y, out);
            case STG:
                return Zenithal.stgRev(prm, x, y, out);
            case SIN:
                return Zenithal.sinRev(prm, x, y, out);
            case ARC:
                return Zenithal.arcRev(prm, x, y, out);
            case ZPN:
                return Zenithal.zpnRev(prm, x, y, out);
            case ZEA:
                return Zenithal.zeaRev(prm, x, y, out);
            case AIR:
                return Zenithal.airRev(prm, x, y, out);
            case TPN:
                return Tpn.tpnRev(prm, x, y, out);
            case CYP:
                return Cylindrical.cypRev(prm, x, y, out);
            case CEA:
                return Cylindrical.ceaRev(prm, x, y, out);
            case CAR:
                return Cylindrical.carRev(prm, x, y, out);
            case MER:
                return Cylindrical.merRev(prm, x, y, out);
            case SFL:
                return Pseudocyl.sflRev(prm, x, y, out);
            case PAR:
                return Pseudocyl.parRev(prm, x, y, out);
            case MOL:
                return Pseudocyl.molRev(prm, x, y, out);
            case AIT:
                return Pseudocyl.aitRev(prm, x, y, out);
            case COP:
                return Conic.copRev(prm, x, y, out);
            case COE:
                return Conic.coeRev(prm, x, y, out);
            case COD:
                return Conic.codRev(prm, x, y, out);
            case COO:
                return Conic.cooRev(prm, x, y, out);
            case BON:
                return Polyconic.bonRev(prm, x, y, out);
            case PCO:
                return Polyconic.pcoRev(prm, x, y, out);
            case TSC:
                return Quadcube.tscRev(prm, x, y, out);
            case CSC:
                return Quadcube.cscRev(prm, x, y, out);
            case QSC:
                return Quadcube.qscRev(prm, x, y, out);
            case HPX:
                return Healpix.hpxRev(prm, x, y, out);
            case XPH:
                return Healpix.xphRev(prm, x, y, out);
            default:
                throw new AstException(proj.code() + " is rejected by Prj.create", AstException.AST__INTER);
        }
    }

    /** Runs the set-up function of a projection. */
    private static Status runSetup(WcsProjection proj, PrjPrm prm) {
        // MODERN-JAVA(21): switch expression over the enum
        switch (proj) {
            case AZP:
                return Zenithal.azpSet(prm);
            case SZP:
                return Zenithal.szpSet(prm);
            case TAN:
                return Zenithal.tanSet(prm);
            case STG:
                return Zenithal.stgSet(prm);
            case SIN:
                return Zenithal.sinSet(prm);
            case ARC:
                return Zenithal.arcSet(prm);
            case ZPN:
                return Zenithal.zpnSet(prm);
            case ZEA:
                return Zenithal.zeaSet(prm);
            case AIR:
                return Zenithal.airSet(prm);
            case TPN:
                return Tpn.tpnSet(prm);
            case CYP:
                return Cylindrical.cypSet(prm);
            case CEA:
                return Cylindrical.ceaSet(prm);
            case CAR:
                return Cylindrical.carSet(prm);
            case MER:
                return Cylindrical.merSet(prm);
            case SFL:
                return Pseudocyl.sflSet(prm);
            case PAR:
                return Pseudocyl.parSet(prm);
            case MOL:
                return Pseudocyl.molSet(prm);
            case AIT:
                return Pseudocyl.aitSet(prm);
            case COP:
                return Conic.copSet(prm);
            case COE:
                return Conic.coeSet(prm);
            case COD:
                return Conic.codSet(prm);
            case COO:
                return Conic.cooSet(prm);
            case BON:
                return Polyconic.bonSet(prm);
            case PCO:
                return Polyconic.pcoSet(prm);
            case TSC:
                return Quadcube.tscSet(prm);
            case CSC:
                return Quadcube.cscSet(prm);
            case QSC:
                return Quadcube.qscSet(prm);
            case HPX:
                return Healpix.hpxSet(prm);
            case XPH:
                return Healpix.xphSet(prm);
            default:
                throw new AstException(proj.code() + " is rejected by Prj.create", AstException.AST__INTER);
        }
    }

    /**
     * Fills the parameter arrays from the supplied parameters, giving each parameter that was not
     * supplied its projection's default or zero (C's {@code InitPrjPrm}, wcsmap.c).
     */
    private static void initParams(WcsProjection proj, PrjPrm prm, double[] lat, double[] lon, boolean tpnTan) {
        // Make the set-up compute its constants afresh.
        prm.r0 = 0.0;

        // TPN's n says whether the transformation includes the TAN projection or only the
        // polynomial correction; for the other projections the set-up fills it.
        if (proj == WcsProjection.TPN) {
            prm.n = tpnTan ? 1 : 0;
        }

        // Start with every parameter unset, then copy those supplied.
        double[] p = prm.p;
        double[] p2 = prm.p2;
        Arrays.fill(p, BAD);
        Arrays.fill(p2, BAD);
        if (lat != null) {
            System.arraycopy(lat, 0, p, 0, Math.min(lat.length, p.length));
        }
        if (lon != null) {
            System.arraycopy(lon, 0, p2, 0, Math.min(lon.length, p2.length));
        }

        // Defaults for the parameters that do not default to zero, one arm per projection as in C.
        // MODERN-JAVA(21): switch expression over the enum
        switch (proj) {
            case SZP:
                if (p[3] == BAD) {
                    p[3] = 90.0;
                }
                break;
            case AIR:
                if (p[1] == BAD) {
                    p[1] = 90.0;
                }
                break;
            case CYP:
                if (p[1] == BAD) {
                    p[1] = 1.0;
                }
                if (p[2] == BAD) {
                    p[2] = 1.0;
                }
                break;
            case CEA:
                if (p[1] == BAD) {
                    p[1] = 1.0;
                }
                break;
            case TPN:
                if (p[1] == BAD) {
                    p[1] = 1.0;
                }
                if (p2[1] == BAD) {
                    p2[1] = 1.0;
                }
                break;
            case HPX:
                if (p[1] == BAD) {
                    p[1] = 4.0;
                }
                if (p[2] == BAD) {
                    p[2] = 3.0;
                }
                break;
            default:
                break;
        }

        // Zero for the rest, except parameter 1 of Bonne and the conics, which has no default.
        boolean undefaultable = proj == WcsProjection.BON
                || proj == WcsProjection.COP
                || proj == WcsProjection.COE
                || proj == WcsProjection.COD
                || proj == WcsProjection.COO;
        for (int i = 0; i < p.length; i++) {
            if (!(undefaultable && i == 1) && p[i] == BAD) {
                p[i] = 0.0;
            }
        }
        for (int i = 0; i < p2.length; i++) {
            if (!(undefaultable && i == 1) && p2[i] == BAD) {
                p2[i] = 0.0;
            }
        }
    }
}
