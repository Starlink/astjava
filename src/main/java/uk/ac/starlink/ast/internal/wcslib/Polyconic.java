package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The polyconic projections: BON and PCO.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a {@link
 * PrjPrm}, and a forward and a reverse function. The bounds tests apply unconditionally, as {@link
 * Prj} explains.
 *
 * <p>Bonne's projection with {@code PV2_1 = 0} is Sanson-Flamsteed's. {@link #bonSet} then sets up
 * SFL's constants from BON's parameters, and {@link #bonFwd} and {@link #bonRev} run SFL's bodies on
 * them, so every call, in any order, projects as SFL does. C tests {@code p[1] == 0} first in both
 * {@code astBONfwd} and {@code astBONrev}, before it looks at {@code flag}, and hands the call to
 * {@code astSFLfwd} or {@code astSFLrev} with BON's own struct. The first of those calls finds
 * {@code flag} not naming SFL and runs {@code astSFLset} on the struct, which sets {@code phi0},
 * {@code theta0}, {@code r0}, {@code w[0]} and {@code w[1]} and leaves {@code flag} naming SFL.
 * {@code astBONset} never runs for such a struct, because every BON call tests {@code p[1]} before
 * it tests {@code flag}, and {@code astSFLset} does not run again, because {@code flag} keeps naming
 * SFL. Every call in any order therefore sees the constants of SFL set up from the same parameters,
 * which is what {@link #bonSet} computes.
 *
 * <p>Ported from Rust {@code wcslib/polyconic.rs}; C {@code wcslib/proj.c}.
 */
final class Polyconic {

    private Polyconic() {}

    // -----------------------------------------------------------------------
    // BON: Bonne's equal area
    // -----------------------------------------------------------------------

    // proj.c astBONset; with p[1] == 0 the projection is SFL's, whose set-up C runs from astSFLfwd
    // or astSFLrev on BON's struct.
    static Status bonSet(PrjPrm prm) {
        if (prm.p[1] == 0.0) {
            return Pseudocyl.sflSet(prm);
        }

        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[1] = 1.0;
            prm.w[2] = prm.r0 * Trig.cosd(prm.p[1]) / Trig.sind(prm.p[1]) + prm.p[1];
        } else {
            prm.w[1] = prm.r0 * Trig.D2R;
            prm.w[2] = prm.r0 * (Trig.cosd(prm.p[1]) / Trig.sind(prm.p[1]) + prm.p[1] * Trig.D2R);
        }

        return Status.OK;
    }

    // proj.c astBONfwd.
    static Status bonFwd(PrjPrm prm, double phi, double theta, double[] out) {
        if (prm.p[1] == 0.0) {
            // Sanson-Flamsteed.
            return Pseudocyl.sflFwd(prm, phi, theta, out);
        }

        double r = prm.w[2] - theta * prm.w[1];
        double a = prm.r0 * phi * Trig.cosd(theta) / r;

        out[0] = r * Trig.sind(a);
        out[1] = prm.w[2] - r * Trig.cosd(a);
        return Status.OK;
    }

    // proj.c astBONrev.
    static Status bonRev(PrjPrm prm, double x, double y, double[] out) {
        if (prm.p[1] == 0.0) {
            // Sanson-Flamsteed.
            return Pseudocyl.sflRev(prm, x, y, out);
        }

        double r = Conic.signedRadius(prm, x, y);
        double a = Conic.coneAngle(prm, x, y, r);

        double theta = (prm.w[2] - r) / prm.w[1];
        double cthe = Trig.cosd(theta);
        double phi;
        if (cthe == 0.0) {
            phi = 0.0;
        } else {
            phi = a * (r / prm.r0) / cthe;
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }

    // -----------------------------------------------------------------------
    // PCO: polyconic
    // -----------------------------------------------------------------------

    // proj.c astPCOset.
    static Status pcoSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
            prm.w[2] = 360.0 / Math.PI;
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = 1.0 / prm.w[0];
            prm.w[2] = 2.0 * prm.r0;
        }
        prm.w[3] = Trig.D2R / prm.w[2];

        return Status.OK;
    }

    // proj.c astPCOfwd, with the small-theta formula, which the C comments as upstream 4.22.
    static Status pcoFwd(PrjPrm prm, double phi, double theta, double[] out) {
        // Below this |theta|, in degrees, cot(theta) is replaced by a series, 1.0e-4 in C.
        final double smallTheta = 1.0e-4;

        double cthe = Trig.cosd(theta);
        double sthe = Trig.sind(theta);
        double a = phi * sthe;

        double x;
        double y;
        if (sthe == 0.0) {
            x = prm.w[0] * phi;
            y = 0.0;
        } else if (Math.abs(theta) < smallTheta) {
            x = prm.w[0] * phi * cthe;
            y = (prm.w[0] + prm.w[3] * x * x) * theta;
        } else {
            double cotthe = cthe / sthe;
            x = prm.r0 * cotthe * Trig.sind(a);
            y = prm.r0 * (cotthe * (1.0 - Trig.cosd(a)) + theta * Trig.D2R);
        }

        out[0] = x;
        out[1] = y;
        return Status.OK;
    }

    // proj.c astPCOrev.
    static Status pcoRev(PrjPrm prm, double x, double y, double[] out) {
        // The tolerance on |y| at the equator and the poles, and the convergence tolerance of the
        // iteration, tol in C.
        final double tol = 1.0e-12;
        // The most iterations of C's weighted division of the interval.
        final int maxIterations = 64;
        // C's marker that fneg has not been computed yet; anything below -100 selects the equal
        // division of the interval.
        final double fnegUnset = -999.0;
        final double fnegSetBelow = -100.0;
        // The clamp on the weight of the weighted division, in C.
        final double lambdaMin = 0.1;
        final double lambdaMax = 0.9;

        double w = Math.abs(y * prm.w[1]);
        double phi;
        double theta;
        if (w < tol) {
            phi = x * prm.w[1];
            theta = 0.0;
        } else if (Math.abs(w - 90.0) < tol) {
            phi = 0.0;
            theta = Zenithal.cCopysign(90.0, y);
        } else {
            // Iterative solution using weighted division of the interval.
            double thepos = y > 0.0 ? 90.0 : -90.0;
            double theneg = 0.0;

            double xx = x * x;
            double ymthe = y - prm.w[0] * thepos;
            double fpos = xx + ymthe * ymthe;
            double fneg = fnegUnset;

            double th = 0.0;
            double tanthe = 0.0;
            for (int j = 0; j < maxIterations; j++) {
                if (fneg < fnegSetBelow) {
                    // Equal division of the interval.
                    th = (thepos + theneg) / 2.0;
                } else {
                    // Weighted division of the interval.
                    double lambda = fpos / (fpos - fneg);
                    if (lambda < lambdaMin) {
                        lambda = lambdaMin;
                    } else if (lambda > lambdaMax) {
                        lambda = lambdaMax;
                    }
                    th = thepos - lambda * (thepos - theneg);
                }

                // Compute the residue.
                ymthe = y - prm.w[0] * th;
                tanthe = Trig.tand(th);
                double f = xx + ymthe * (ymthe - prm.w[2] / tanthe);

                // Check for convergence.
                if (Math.abs(f) < tol) {
                    break;
                }
                if (Math.abs(thepos - theneg) < tol) {
                    break;
                }

                // Redefine the interval.
                if (f > 0.0) {
                    thepos = th;
                    fpos = f;
                } else {
                    theneg = th;
                    fneg = f;
                }
            }

            double xp = prm.r0 - ymthe * tanthe;
            double yp = x * tanthe;
            if (xp == 0.0 && yp == 0.0) {
                phi = 0.0;
            } else {
                phi = Trig.atan2d(yp, xp) / Trig.sind(th);
            }
            theta = th;
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }
}
