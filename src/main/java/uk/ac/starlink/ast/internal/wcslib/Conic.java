package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The conic projections: COP, COE, COD and COO.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a {@link
 * PrjPrm}, and a forward and a reverse function. The bounds tests apply unconditionally, as {@link
 * Prj} explains.
 *
 * <p>Ported from Rust {@code wcslib/conic.rs}; C {@code wcslib/proj.c}.
 */
final class Conic {

    private Conic() {}

    // -----------------------------------------------------------------------
    // COP: conic perspective
    // -----------------------------------------------------------------------

    // proj.c astCOPset.
    static Status copSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = prm.p[1];

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = Trig.sind(prm.p[1]);
        if (prm.w[0] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[1] = 1.0 / prm.w[0];

        prm.w[3] = prm.r0 * Trig.cosd(prm.p[2]);
        if (prm.w[3] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[4] = 1.0 / prm.w[3];
        prm.w[5] = 1.0 / Trig.tand(prm.p[1]);

        prm.w[2] = prm.w[3] * prm.w[5];

        return Status.OK;
    }

    // proj.c astCOPfwd, with the exact values at the poles, which the C comments as upstream 4.22.
    static Status copFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double t = theta - prm.p[1];
        double s = Trig.cosd(t);
        if (s == 0.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            return Status.BAD_POINT;
        }

        double r;
        boolean bad;
        if (Math.abs(theta) == 90.0) {
            // The pole on the same side as the standard parallels is the apex of the cone, and the
            // other is on the far side.
            r = 0.0;
            bad = (theta < 0.0) != (prm.p[1] < 0.0);
        } else {
            r = prm.w[2] - prm.w[3] * Trig.sind(t) / s;
            bad = r * prm.w[0] < 0.0;
        }

        double a = prm.w[0] * phi;

        out[0] = r * Trig.sind(a);
        out[1] = prm.w[2] - r * Trig.cosd(a);

        return bad ? Status.BAD_POINT : Status.OK;
    }

    // proj.c astCOPrev.
    static Status copRev(PrjPrm prm, double x, double y, double[] out) {
        double r = signedRadius(prm, x, y);
        double a = coneAngle(prm, x, y, r);

        out[0] = a * prm.w[1];
        out[1] = prm.p[1] + Trig.atand(prm.w[5] - r * prm.w[4]);
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // COE: conic equal area
    // -----------------------------------------------------------------------

    // proj.c astCOEset.
    static Status coeSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = prm.p[1];

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        double theta1 = prm.p[1] - prm.p[2];
        double theta2 = prm.p[1] + prm.p[2];

        prm.w[0] = (Trig.sind(theta1) + Trig.sind(theta2)) / 2.0;
        if (prm.w[0] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[1] = 1.0 / prm.w[0];

        prm.w[3] = prm.r0 / prm.w[0];
        prm.w[4] = 1.0 + Trig.sind(theta1) * Trig.sind(theta2);
        prm.w[5] = 2.0 * prm.w[0];
        prm.w[6] = prm.w[3] * prm.w[3] * prm.w[4];
        prm.w[7] = 1.0 / (2.0 * prm.r0 * prm.w[3]);
        prm.w[8] = prm.w[3] * Math.sqrt(prm.w[4] + prm.w[5]);

        prm.w[2] = prm.w[3] * Math.sqrt(prm.w[4] - prm.w[5] * Trig.sind(prm.p[1]));

        return Status.OK;
    }

    // proj.c astCOEfwd.
    static Status coeFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double a = phi * prm.w[0];
        double r;
        if (theta == -90.0) {
            r = prm.w[8];
        } else {
            r = prm.w[3] * Math.sqrt(prm.w[4] - prm.w[5] * Trig.sind(theta));
        }

        out[0] = r * Trig.sind(a);
        out[1] = prm.w[2] - r * Trig.cosd(a);
        return Status.OK;
    }

    // proj.c astCOErev.
    static Status coeRev(PrjPrm prm, double x, double y, double[] out) {
        // The tolerance on r at the south pole and on |w| = 1, tol in C.
        final double tol = 1.0e-12;

        double r = signedRadius(prm, x, y);
        double a = coneAngle(prm, x, y, r);

        double phi = a * prm.w[1];
        double theta;
        if (Math.abs(r - prm.w[8]) < tol) {
            theta = -90.0;
        } else {
            double w = (prm.w[6] - r * r) * prm.w[7];
            if (Math.abs(w) > 1.0) {
                if (Math.abs(w - 1.0) < tol) {
                    theta = 90.0;
                } else if (Math.abs(w + 1.0) < tol) {
                    theta = -90.0;
                } else {
                    out[0] = phi;
                    out[1] = 0.0;
                    return Status.BAD_POINT;
                }
            } else {
                theta = Trig.asind(w);
            }
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // COD: conic equidistant
    // -----------------------------------------------------------------------

    // proj.c astCODset.
    static Status codSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = prm.p[1];

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        if (prm.p[2] == 0.0) {
            prm.w[0] = prm.r0 * Trig.sind(prm.p[1]) * Trig.D2R;
        } else {
            prm.w[0] = prm.r0 * Trig.sind(prm.p[1]) * Trig.sind(prm.p[2]) / prm.p[2];
        }

        if (prm.w[0] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[1] = 1.0 / prm.w[0];
        prm.w[2] = prm.r0 * Trig.cosd(prm.p[2]) * Trig.cosd(prm.p[1]) / prm.w[0];
        prm.w[3] = prm.w[2] + prm.p[1];

        return Status.OK;
    }

    // proj.c astCODfwd.
    static Status codFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double a = prm.w[0] * phi;
        double r = prm.w[3] - theta;

        out[0] = r * Trig.sind(a);
        out[1] = prm.w[2] - r * Trig.cosd(a);
        return Status.OK;
    }

    // proj.c astCODrev.
    static Status codRev(PrjPrm prm, double x, double y, double[] out) {
        double r = signedRadius(prm, x, y);
        double a = coneAngle(prm, x, y, r);

        out[0] = a * prm.w[1];
        out[1] = prm.w[3] - r;
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // COO: conic orthomorphic
    // -----------------------------------------------------------------------

    // proj.c astCOOset.
    static Status cooSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = prm.p[1];

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        double theta1 = prm.p[1] - prm.p[2];
        double theta2 = prm.p[1] + prm.p[2];

        double tan1 = Trig.tand((90.0 - theta1) / 2.0);
        double cos1 = Trig.cosd(theta1);

        if (theta1 == theta2) {
            prm.w[0] = Trig.sind(theta1);
        } else {
            double tan2 = Trig.tand((90.0 - theta2) / 2.0);
            double cos2 = Trig.cosd(theta2);
            prm.w[0] = Math.log(cos2 / cos1) / Math.log(tan2 / tan1);
        }
        if (prm.w[0] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[1] = 1.0 / prm.w[0];

        prm.w[3] = prm.r0 * (cos1 / prm.w[0]) / Math.pow(tan1, prm.w[0]);
        if (prm.w[3] == 0.0) {
            return Status.BAD_PARAMS;
        }
        prm.w[2] = prm.w[3] * Math.pow(Trig.tand((90.0 - prm.p[1]) / 2.0), prm.w[0]);
        prm.w[4] = 1.0 / prm.w[3];

        return Status.OK;
    }

    // proj.c astCOOfwd.
    static Status cooFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double a = prm.w[0] * phi;
        double sa = Trig.sind(a);
        double ca = Trig.cosd(a);

        double r;
        if (theta == -90.0) {
            if (prm.w[0] < 0.0) {
                r = 0.0;
            } else {
                out[0] = 0.0;
                out[1] = 0.0;
                return Status.BAD_POINT;
            }
        } else {
            r = prm.w[3] * Math.pow(Trig.tand((90.0 - theta) / 2.0), prm.w[0]);
        }

        out[0] = r * sa;
        out[1] = prm.w[2] - r * ca;
        return Status.OK;
    }

    // proj.c astCOOrev.
    static Status cooRev(PrjPrm prm, double x, double y, double[] out) {
        double r = signedRadius(prm, x, y);
        double phi = coneAngle(prm, x, y, r) * prm.w[1];

        double theta;
        if (r == 0.0) {
            if (prm.w[0] < 0.0) {
                theta = -90.0;
            } else {
                out[0] = phi;
                out[1] = 0.0;
                return Status.BAD_POINT;
            }
        } else {
            theta = 90.0 - 2.0 * Trig.atand(Math.pow(r * prm.w[4], prm.w[1]));
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }

    /**
     * The radius of (x, y) from the apex of the cone at (0, {@code w[2]}), negated when the standard
     * parallel {@code p[1]} is south of the equator, as every conic reverse projection computes it.
     */
    static double signedRadius(PrjPrm prm, double x, double y) {
        double dy = prm.w[2] - y;
        double r = Math.sqrt(x * x + dy * dy);
        if (prm.p[1] < 0.0) {
            r = -r;
        }
        return r;
    }

    /**
     * The angle about the apex of the cone of (x, y), whose signed radius is {@code r}, in degrees:
     * zero at the apex.
     */
    static double coneAngle(PrjPrm prm, double x, double y, double r) {
        if (r == 0.0) {
            return 0.0;
        }
        return Trig.atan2d(x / r, (prm.w[2] - y) / r);
    }
}
