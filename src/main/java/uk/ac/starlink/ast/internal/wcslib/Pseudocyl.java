package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The pseudo-cylindrical projections: SFL, PAR, MOL and AIT.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a {@link
 * PrjPrm}, and a forward and a reverse function. GLS is SFL with parameters and a WcsMap converts it
 * before it gets here. The bounds tests apply unconditionally, as {@link Prj} explains.
 *
 * <p>Ported from Rust {@code wcslib/pseudocyl.rs}; C {@code wcslib/proj.c}.
 */
final class Pseudocyl {

    /** C's {@code SQRT2} literal, the double nearest the square root of 2. */
    private static final double SQRT2 = 1.4142135623730951;

    private Pseudocyl() {}

    // -----------------------------------------------------------------------
    // SFL: Sanson-Flamsteed
    // -----------------------------------------------------------------------

    // proj.c astSFLset.
    static Status sflSet(PrjPrm prm) {
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

    // proj.c astSFLfwd.
    static Status sflFwd(PrjPrm prm, double phi, double theta, double[] out) {
        out[0] = prm.w[0] * phi * Trig.cosd(theta);
        out[1] = prm.w[0] * theta;
        return Status.OK;
    }

    // proj.c astSFLrev.
    static Status sflRev(PrjPrm prm, double x, double y, double[] out) {
        double w = Math.cos(y / prm.r0);
        double phi;
        if (w == 0.0) {
            phi = 0.0;
        } else {
            phi = x * prm.w[1] / Math.cos(y / prm.r0);
        }
        out[0] = phi;
        out[1] = y * prm.w[1];
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // PAR: parabolic
    // -----------------------------------------------------------------------

    // proj.c astPARset.
    static Status parSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
            prm.w[2] = 180.0;
            prm.w[3] = 1.0 / prm.w[2];
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = 1.0 / prm.w[0];
            prm.w[2] = Math.PI * prm.r0;
            prm.w[3] = 1.0 / prm.w[2];
        }

        return Status.OK;
    }

    // proj.c astPARfwd.
    static Status parFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double s = Trig.sind(theta / 3.0);
        out[0] = prm.w[0] * phi * (1.0 - 4.0 * s * s);
        out[1] = prm.w[2] * s;
        return Status.OK;
    }

    // proj.c astPARrev.
    static Status parRev(PrjPrm prm, double x, double y, double[] out) {
        double s = y * prm.w[3];
        if (s > 1.0 || s < -1.0) {
            return badPoint(out, 0.0);
        }

        double t = 1.0 - 4.0 * s * s;
        double phi;
        if (t == 0.0) {
            if (x == 0.0) {
                phi = 0.0;
            } else {
                return badPoint(out, 0.0);
            }
        } else {
            phi = prm.w[1] * x / t;
        }

        out[0] = phi;
        out[1] = 3.0 * Trig.asind(s);
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // MOL: Mollweide
    // -----------------------------------------------------------------------

    // proj.c astMOLset.
    static Status molSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = SQRT2 * prm.r0;
        prm.w[1] = prm.w[0] / 90.0;
        prm.w[2] = 1.0 / prm.w[0];
        prm.w[3] = 90.0 / prm.r0;
        prm.w[4] = 2.0 / Math.PI;

        return Status.OK;
    }

    // proj.c astMOLfwd.
    static Status molFwd(PrjPrm prm, double phi, double theta, double[] out) {
        // The tolerance on the residual of the iteration, tol in C.
        final double tol = 1.0e-13;
        // The most iterations of the bisection in C.
        final int maxIterations = 100;

        if (Math.abs(theta) == 90.0) {
            out[0] = 0.0;
            out[1] = Zenithal.cCopysign(prm.w[0], theta);
        } else if (theta == 0.0) {
            out[0] = prm.w[1] * phi;
            out[1] = 0.0;
        } else {
            double u = Math.PI * Trig.sind(theta);
            double v0 = -Math.PI;
            double v1 = Math.PI;
            double v = u;
            for (int j = 0; j < maxIterations; j++) {
                double resid = (v - u) + Math.sin(v);
                if (resid < 0.0) {
                    if (resid > -tol) {
                        break;
                    }
                    v0 = v;
                } else {
                    if (resid < tol) {
                        break;
                    }
                    v1 = v;
                }
                v = (v0 + v1) / 2.0;
            }

            double gamma = v / 2.0;
            out[0] = prm.w[1] * phi * Math.cos(gamma);
            out[1] = prm.w[0] * Math.sin(gamma);
        }

        return Status.OK;
    }

    // proj.c astMOLrev.
    static Status molRev(PrjPrm prm, double x, double y, double[] out) {
        // The tolerance on the squared radius and on |z| = 1, tol in C.
        final double tol = 1.0e-12;

        double y0 = y / prm.r0;
        double s = 2.0 - y0 * y0;
        double phi;
        if (s <= tol) {
            if (s < -tol) {
                return badPoint(out, 0.0);
            }
            s = 0.0;

            if (Math.abs(x) > tol) {
                return badPoint(out, 0.0);
            }
            phi = 0.0;
        } else {
            s = Math.sqrt(s);
            phi = prm.w[3] * x / s;
        }

        double z = y * prm.w[2];
        if (Math.abs(z) > 1.0) {
            if (Math.abs(z) > 1.0 + tol) {
                return badPoint(out, phi);
            }
            z = Zenithal.cCopysign(1.0, z) + y0 * s / Math.PI;
        } else {
            z = Math.asin(z) * prm.w[4] + y0 * s / Math.PI;
        }

        if (Math.abs(z) > 1.0) {
            if (Math.abs(z) > 1.0 + tol) {
                return badPoint(out, phi);
            }
            z = Zenithal.cCopysign(1.0, z);
        }

        out[0] = phi;
        out[1] = Trig.asind(z);
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // AIT: Hammer-Aitoff
    // -----------------------------------------------------------------------

    // proj.c astAITset.
    static Status aitSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = 2.0 * prm.r0 * prm.r0;
        prm.w[1] = 1.0 / (2.0 * prm.w[0]);
        prm.w[2] = prm.w[1] / 4.0;
        prm.w[3] = 1.0 / (2.0 * prm.r0);

        return Status.OK;
    }

    // proj.c astAITfwd.
    static Status aitFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double cthe = Trig.cosd(theta);
        double w = Math.sqrt(prm.w[0] / (1.0 + cthe * Trig.cosd(phi / 2.0)));
        out[0] = 2.0 * w * cthe * Trig.sind(phi / 2.0);
        out[1] = w * Trig.sind(theta);
        return Status.OK;
    }

    // proj.c astAITrev.
    static Status aitRev(PrjPrm prm, double x, double y, double[] out) {
        // The tolerance on u = 1/2 and on |s| = 1, tol in C.
        final double tol = 1.0e-13;

        double u = 1.0 - x * x * prm.w[2] - y * y * prm.w[1];
        if (u < 0.5) {
            if (u < 0.5 - tol) {
                return badPoint(out, 0.0);
            }

            u = 0.5;
        }

        double z = Math.sqrt(u);
        double s = z * y / prm.r0;
        if (Math.abs(s) > 1.0) {
            if (Math.abs(s) > 1.0 + tol) {
                return badPoint(out, 0.0);
            }
            s = Zenithal.cCopysign(1.0, s);
        }

        double xp = 2.0 * z * z - 1.0;
        double yp = z * x * prm.w[3];
        double phi;
        if (xp == 0.0 && yp == 0.0) {
            phi = 0.0;
        } else {
            phi = 2.0 * Trig.atan2d(yp, xp);
        }

        out[0] = phi;
        out[1] = Trig.asind(s);
        return Status.OK;
    }

    /** Writes ({@code first}, 0) to {@code out} and returns {@link Status#BAD_POINT}. */
    private static Status badPoint(double[] out, double first) {
        out[0] = first;
        out[1] = 0.0;
        return Status.BAD_POINT;
    }
}
