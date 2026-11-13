package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The cylindrical projections: CYP, CEA, CAR and MER.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a {@link
 * PrjPrm}, and a forward and a reverse function. The bounds tests apply unconditionally, as {@link
 * Prj} explains.
 *
 * <p>Ported from Rust {@code wcslib/cylindrical.rs}; C {@code wcslib/proj.c}.
 */
final class Cylindrical {

    /** The tolerance on |a| = 1 in CYP and |s| = 1 in CEA, {@code tol} in C. */
    private static final double TOL = 1.0e-13;

    private Cylindrical() {}

    // ---------------------------------------------------------------------
    // CYP: cylindrical perspective
    // ---------------------------------------------------------------------

    // proj.c astCYPset.
    static Status cypSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;

            prm.w[0] = prm.p[2];
            if (prm.w[0] == 0.0) {
                return Status.BAD_PARAMS;
            }

            prm.w[1] = 1.0 / prm.w[0];

            prm.w[2] = Trig.R2D * (prm.p[1] + prm.p[2]);
            if (prm.w[2] == 0.0) {
                return Status.BAD_PARAMS;
            }

            prm.w[3] = 1.0 / prm.w[2];
        } else {
            prm.w[0] = prm.r0 * prm.p[2] * Trig.D2R;
            if (prm.w[0] == 0.0) {
                return Status.BAD_PARAMS;
            }

            prm.w[1] = 1.0 / prm.w[0];

            prm.w[2] = prm.r0 * (prm.p[1] + prm.p[2]);
            if (prm.w[2] == 0.0) {
                return Status.BAD_PARAMS;
            }

            prm.w[3] = 1.0 / prm.w[2];
        }

        return Status.OK;
    }

    // proj.c astCYPfwd.
    static Status cypFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double s = prm.p[1] + Trig.cosd(theta);
        if (s == 0.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            return Status.BAD_POINT;
        }

        out[0] = prm.w[0] * phi;
        out[1] = prm.w[2] * Trig.sind(theta) / s;
        return Status.OK;
    }

    // proj.c astCYPrev.
    static Status cypRev(PrjPrm prm, double x, double y, double[] out) {
        double phi = x * prm.w[1];
        double eta = y * prm.w[3];

        double a = eta * prm.p[1] / Math.sqrt(eta * eta + 1.0);
        double theta;
        if (Math.abs(a) < 1.0) {
            theta = Trig.atan2d(eta, 1.0) + Trig.asind(a);
        } else if (Math.abs(a) < 1.0 + TOL) {
            if (a > 0.0) {
                theta = Trig.atan2d(eta, 1.0) + 90.0;
            } else {
                theta = Trig.atan2d(eta, 1.0) - 90.0;
            }
        } else {
            out[0] = phi;
            out[1] = 0.0;
            return Status.BAD_POINT;
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }

    // ---------------------------------------------------------------------
    // CEA: cylindrical equal area
    // ---------------------------------------------------------------------

    // proj.c astCEAset.
    static Status ceaSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
            if (prm.p[1] <= 0.0 || prm.p[1] > 1.0) {
                return Status.BAD_PARAMS;
            }
            prm.w[2] = prm.r0 / prm.p[1];
            prm.w[3] = prm.p[1] / prm.r0;
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = Trig.R2D / prm.r0;
            if (prm.p[1] <= 0.0 || prm.p[1] > 1.0) {
                return Status.BAD_PARAMS;
            }
            prm.w[2] = prm.r0 / prm.p[1];
            prm.w[3] = prm.p[1] / prm.r0;
        }

        return Status.OK;
    }

    // proj.c astCEAfwd.
    static Status ceaFwd(PrjPrm prm, double phi, double theta, double[] out) {
        out[0] = prm.w[0] * phi;
        out[1] = prm.w[2] * Trig.sind(theta);
        return Status.OK;
    }

    // proj.c astCEArev.
    static Status ceaRev(PrjPrm prm, double x, double y, double[] out) {
        double s = y * prm.w[3];
        if (Math.abs(s) > 1.0) {
            if (Math.abs(s) > 1.0 + TOL) {
                out[0] = 0.0;
                out[1] = 0.0;
                return Status.BAD_POINT;
            }
            s = Zenithal.cCopysign(1.0, s);
        }

        out[0] = x * prm.w[1];
        out[1] = Trig.asind(s);
        return Status.OK;
    }

    // ---------------------------------------------------------------------
    // CAR: Cartesian
    // ---------------------------------------------------------------------

    // proj.c astCARset.
    static Status carSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = 1.0 / prm.w[0];
        }

        return Status.OK;
    }

    // proj.c astCARfwd.
    static Status carFwd(PrjPrm prm, double phi, double theta, double[] out) {
        out[0] = prm.w[0] * phi;
        out[1] = prm.w[0] * theta;
        return Status.OK;
    }

    // proj.c astCARrev.
    static Status carRev(PrjPrm prm, double x, double y, double[] out) {
        out[0] = prm.w[1] * x;
        out[1] = prm.w[1] * y;
        return Status.OK;
    }

    // ---------------------------------------------------------------------
    // MER: Mercator
    // ---------------------------------------------------------------------

    // proj.c astMERset.
    static Status merSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = 1.0 / prm.w[0];
        }

        return Status.OK;
    }

    // proj.c astMERfwd.
    static Status merFwd(PrjPrm prm, double phi, double theta, double[] out) {
        if (theta <= -90.0 || theta >= 90.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            return Status.BAD_POINT;
        }

        out[0] = prm.w[0] * phi;
        out[1] = prm.r0 * Math.log(Trig.tand((90.0 + theta) / 2.0));
        return Status.OK;
    }

    // proj.c astMERrev.
    static Status merRev(PrjPrm prm, double x, double y, double[] out) {
        out[0] = x * prm.w[1];
        out[1] = 2.0 * Trig.atand(Math.exp(y / prm.r0)) - 90.0;
        return Status.OK;
    }
}
