package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The HEALPix projections: HPX and XPH.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a {@link
 * PrjPrm}, and a forward and a reverse function. The bounds tests apply unconditionally, as {@link
 * Prj} explains.
 *
 * <p>HPX chooses its facet by integer arithmetic: the parameters H and K are truncated toward zero
 * to {@code int} and {@code %} takes the sign of the dividend. Java's {@code (int)} and {@code %}
 * do the same for every value C defines, and saturate where C's conversion is undefined.
 *
 * <p>Ported from Rust {@code wcslib/healpix.rs}; C {@code wcslib/proj.c}.
 */
final class Healpix {

    /**
     * astHPXrev: the polar zone's outer edge, and the facet half-width, may be exceeded by this much
     * for rounding (degrees).
     */
    private static final double HPX_REV_TOL = 1.0e-12;

    /** astHPXrev: the factor that stands in for 1/sigma at the pole, where sigma is zero. */
    private static final double HPX_POLE_SCALE = 1.0e9;

    /**
     * astXPHset: the sigma below which the reverse projection uses the small angle formula for
     * theta.
     */
    private static final double XPH_SIGMA_TOL = 1e-4;

    /** astXPHrev: a point may lie outside its facet by this much for rounding. */
    private static final double XPH_REV_TOL = 1.0e-12;

    private Healpix() {}

    // ---------------------------------------------------------------------
    // HPX: HEALPix
    // ---------------------------------------------------------------------

    // proj.c astHPXset.
    static Status hpxSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        prm.n = ((int) prm.p[2]) % 2;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = Trig.R2D / prm.r0;
        }

        prm.w[2] = (prm.p[2] - 1.0) / prm.p[2];
        prm.w[3] = 90.0 * prm.p[2] / prm.p[1];
        prm.w[4] = (prm.p[2] + 1.0) / 2.0;
        prm.w[5] = 90.0 * (prm.p[2] - 1.0) / prm.p[1];
        prm.w[6] = 180.0 / prm.p[1];
        prm.w[7] = prm.p[1] / 360.0;
        prm.w[8] = prm.w[3] * prm.w[0];
        prm.w[9] = prm.w[6] * prm.w[0];

        return Status.OK;
    }

    // proj.c astHPXfwd.
    // phic is conditioned to improve the accuracy of floor for arguments just below an integer
    // (AST's change), and the phi = 180 meridian is put in the expected place (wcslib 4.7 fix in
    // the C).
    static Status hpxFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double sinthe = Trig.sind(theta);
        double abssin = Math.abs(sinthe);

        if (abssin <= prm.w[2]) {
            // Equatorial zone.
            out[0] = prm.w[0] * phi;
            out[1] = prm.w[8] * sinthe;
            return Status.OK;
        }

        // Polar zone.
        int hodd = ((int) prm.p[1]) % 2;
        if (prm.n == 0 && theta <= 0.0) {
            hodd = 1 - hodd;
        }
        double phic;
        if (hodd != 0) {
            phic = -180.0 + (2.0 * Math.floor(prm.w[7] * phi + 0.5) + prm.p[1]) * prm.w[6];
        } else {
            phic = -180.0 + (2.0 * Math.floor(prm.w[7] * phi) + prm.p[1] + 1.0) * prm.w[6];
        }

        double sigma = Math.sqrt(prm.p[2] * (1.0 - abssin));

        double x = prm.w[0] * (phic + (phi - phic) * sigma);

        double y = prm.w[9] * (prm.w[4] - sigma);
        if (theta < 0.0) {
            y = -y;
        }

        // Put the phi = 180 meridian in the expected place.
        if (180.0 < x) {
            x = 360.0 - x;
        }

        out[0] = x;
        out[1] = y;
        return Status.OK;
    }

    // proj.c astHPXrev.
    // The polar zone extends to (K+1)/2 facets of 180/H degrees (wcslib 4.7 fix in the C). xc is
    // conditioned as in astHPXfwd (AST's change). A point is on a facet when (x - xc)/sigma is
    // within a facet half-width plus a rounding margin (wcslib 4.7 and 4.20 fixes in the C), and y
    // within the margin of the pole's counts as the pole (AST's change, because a WcsMap's radian
    // round trip moves the pole's y by a few ULP).
    static Status hpxRev(PrjPrm prm, double x, double y, double[] out) {
        double yr = prm.w[1] * y;
        double absy = Math.abs(yr);

        double phi;
        double theta;
        if (absy <= prm.w[5]) {
            // Equatorial zone.
            phi = prm.w[1] * x;
            double t = yr / prm.w[3];
            if (t < -1.0 || t > 1.0) {
                return badPoint(out);
            }
            theta = Trig.asind(t);
        } else if (absy <= prm.w[4] * prm.w[6] + HPX_REV_TOL) {
            // Polar zone.
            int hodd = ((int) prm.p[1]) % 2;
            if (prm.n == 0 && yr <= 0.0) {
                hodd = 1 - hodd;
            }
            double xc;
            if (hodd != 0) {
                xc = -180.0 + (2.0 * Math.floor(prm.w[7] * x + 0.5) + prm.p[1]) * prm.w[6];
            } else {
                xc = -180.0 + (2.0 * Math.floor(prm.w[7] * x) + prm.p[1] + 1.0) * prm.w[6];
            }

            double sigma = prm.w[4] - absy / prm.w[6];

            boolean pole = Math.abs(sigma) * prm.w[6] <= HPX_REV_TOL;
            double t;
            if (pole) {
                t = (x - xc) * HPX_POLE_SCALE;
            } else {
                t = (x - xc) / sigma;
            }
            if (Math.abs(t) < prm.w[6] + HPX_REV_TOL) {
                phi = prm.w[1] * (xc + t);
            } else {
                return badPoint(out);
            }

            if (pole) {
                theta = 90.0;
            } else {
                double s = 1.0 - sigma * sigma / prm.p[2];
                if (s < -1.0 || s > 1.0) {
                    return badPoint(out);
                }
                theta = Trig.asind(s);
            }
            if (y < 0.0) {
                theta = -theta;
            }
        } else {
            return badPoint(out);
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }

    // ---------------------------------------------------------------------
    // XPH: HEALPix polar ("butterfly")
    // ---------------------------------------------------------------------

    // proj.c astXPHset.
    // The C leaves phi0 and theta0 as they were.
    static Status xphSet(PrjPrm prm) {
        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 1.0;
            prm.w[1] = 1.0;
        } else {
            prm.w[0] = prm.r0 * Trig.D2R;
            prm.w[1] = Trig.R2D / prm.r0;
        }

        prm.w[0] /= Math.sqrt(2.0);
        prm.w[1] /= Math.sqrt(2.0);
        prm.w[2] = 2.0 / 3.0;
        prm.w[3] = XPH_SIGMA_TOL;
        prm.w[4] = Math.sqrt(prm.w[2]) * Trig.R2D;
        prm.w[5] = 90.0 - prm.w[3] * prm.w[4];
        prm.w[6] = Math.sqrt(1.5) * Trig.D2R;

        return Status.OK;
    }

    // proj.c astXPHfwd.
    // The C holds phi (rounded) in its output y and psi in x until the end; they are chiPhi and
    // psi here.
    static Status xphFwd(PrjPrm prm, double phi, double theta, double[] out) {
        // Do phi dependence.
        double chi = phi;
        if (180.0 <= Math.abs(chi)) {
            chi %= 360.0;
            if (chi < -180.0) {
                chi += 360.0;
            } else if (180.0 <= chi) {
                chi -= 360.0;
            }
        }

        // phi is also recomputed from chi to avoid rounding problems.
        chi += 180.0;
        double psi = chi % 90.0;
        double chiPhi = chi - 180.0;

        // Do theta dependence.
        double sinthe = Trig.sind(theta);
        double abssin = Math.abs(sinthe);

        double xi;
        double eta;
        if (abssin <= prm.w[2]) {
            // Equatorial regime.
            xi = psi;
            eta = 67.5 * sinthe;
        } else {
            // Polar regime.
            double sigma;
            if (theta < prm.w[5]) {
                sigma = Math.sqrt(3.0 * (1.0 - abssin));
            } else {
                sigma = (90.0 - theta) * prm.w[6];
            }

            xi = 45.0 + (psi - 45.0) * sigma;
            eta = 45.0 * (2.0 - sigma);
            if (theta < 0.0) {
                eta = -eta;
            }
        }

        xi -= 45.0;
        eta -= 90.0;

        if (chiPhi < -90.0) {
            out[0] = prm.w[0] * (-xi + eta);
            out[1] = prm.w[0] * (-xi - eta);
        } else if (chiPhi < 0.0) {
            out[0] = prm.w[0] * (xi + eta);
            out[1] = prm.w[0] * (-xi + eta);
        } else if (chiPhi < 90.0) {
            out[0] = prm.w[0] * (xi - eta);
            out[1] = prm.w[0] * (xi + eta);
        } else {
            out[0] = prm.w[0] * (-xi - eta);
            out[1] = prm.w[0] * (xi - eta);
        }

        return Status.OK;
    }

    // proj.c astXPHrev.
    static Status xphRev(PrjPrm prm, double x, double y, double[] out) {
        double xr = x * prm.w[1];
        double yr = y * prm.w[1];
        double xi1;
        double eta1;
        double phi;
        if (xr <= 0.0 && 0.0 < yr) {
            xi1 = -xr - yr;
            eta1 = xr - yr;
            phi = -180.0;
        } else if (xr < 0.0 && yr <= 0.0) {
            xi1 = xr - yr;
            eta1 = xr + yr;
            phi = -90.0;
        } else if (0.0 <= xr && yr < 0.0) {
            xi1 = xr + yr;
            eta1 = -xr + yr;
            phi = 0.0;
        } else {
            xi1 = -xr + yr;
            eta1 = -xr - yr;
            phi = 90.0;
        }

        double xi = xi1 + 45.0;
        double eta = eta1 + 90.0;
        double abseta = Math.abs(eta);

        double theta;
        if (abseta <= 90.0) {
            if (abseta <= 45.0) {
                // Equatorial regime.
                phi += xi;
                theta = Trig.asind(eta / 67.5);

                // Bounds checking.
                if (45.0 + XPH_REV_TOL < Math.abs(xi1)) {
                    return badPoint(out);
                }
            } else {
                // Polar regime.
                double sigma = (90.0 - abseta) / 45.0;

                // Ensure an exact result for points on the boundary.
                if (xr == 0.0) {
                    if (yr <= 0.0) {
                        phi = 0.0;
                    } else {
                        phi = 180.0;
                    }
                } else if (yr == 0.0) {
                    if (xr < 0.0) {
                        phi = -90.0;
                    } else {
                        phi = 90.0;
                    }
                } else {
                    phi += 45.0 + xi1 / sigma;
                }

                if (sigma < prm.w[3]) {
                    theta = 90.0 - sigma * prm.w[4];
                } else {
                    theta = Trig.asind(1.0 - sigma * sigma / 3.0);
                }
                if (eta < 0.0) {
                    theta = -theta;
                }

                // Bounds checking.
                if (eta < -45.0 && eta + 90.0 + XPH_REV_TOL < Math.abs(xi1)) {
                    return badPoint(out);
                }
            }
        } else {
            // Beyond latitude range.
            return badPoint(out);
        }

        out[0] = phi;
        out[1] = theta;
        return Status.OK;
    }

    /** Writes (0, 0) to {@code out} and returns {@link Status#BAD_POINT}. */
    private static Status badPoint(double[] out) {
        out[0] = 0.0;
        out[1] = 0.0;
        return Status.BAD_POINT;
    }
}
