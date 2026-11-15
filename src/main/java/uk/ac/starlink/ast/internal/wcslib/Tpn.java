package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * TPN: the gnomonic projection with polynomial correction terms.
 *
 * <p>The projection is AST's own addition to its fork of wcslib, which uses it to implement the IRAF TNX
 * projection and the DSS plate solution encoding. It is no longer part of the FITS-WCS standard. {@code prm.p}
 * holds the coefficients of the latitude-axis polynomial and {@code prm.p2} those of the longitude axis;
 * {@code prm.n} says whether the TAN projection is applied as well as the polynomial.
 *
 * <p>The bounds tests apply unconditionally: the {@code prj->flag > 0} tests of the C bodies are taken as true.
 *
 * <p>Ported from Rust {@code wcslib/tpn.rs}; C {@code wcslib/tpn.c}.
 */
final class Tpn {

    /**
     * Newton's method in the forward projection stops when both corrections are within this fraction of the new
     * coordinate (the {@code tol} of {@code tpn.c}).
     */
    static final double NEWTON_RELATIVE_TOLERANCE = 1.0e-5;

    /** The most iterations the forward projection makes before it gives up (the loop bound of {@code tpn.c}). */
    static final int NEWTON_MAX_ITERATIONS = 50;

    private Tpn() {}

    /** The powers and products of a point (x, y) the polynomial terms use, in the names and arithmetic of C. */
    private static final class Terms {
        final double x;
        final double y;
        final double x2;
        final double xy;
        final double y2;
        final double r;
        final double x3;
        final double x2y;
        final double xy2;
        final double y3;
        final double r3;
        final double x4;
        final double x3y;
        final double x2y2;
        final double xy3;
        final double y4;
        final double x5;
        final double x4y;
        final double x3y2;
        final double x2y3;
        final double xy4;
        final double y5;
        final double r5;
        final double x6;
        final double x5y;
        final double x4y2;
        final double x3y3;
        final double x2y4;
        final double xy5;
        final double y6;
        final double x7;
        final double x6y;
        final double x5y2;
        final double x4y3;
        final double x3y4;
        final double x2y5;
        final double xy6;
        final double y7;
        final double r7;

        Terms(double x, double y) {
            this.x = x;
            this.y = y;
            x2 = x * x;
            xy = x * y;
            y2 = y * y;
            double r2 = x2 + y2;
            r = Math.sqrt(r2);
            x3 = x2 * x;
            x2y = x2 * y;
            xy2 = x * y2;
            y3 = y * y2;
            r3 = r * r2;
            x4 = x3 * x;
            x3y = x3 * y;
            x2y2 = x2 * y2;
            xy3 = x * y3;
            y4 = y * y3;
            x5 = x4 * x;
            x4y = x4 * y;
            x3y2 = x3 * y2;
            x2y3 = x2 * y3;
            xy4 = x * y4;
            y5 = y * y4;
            r5 = r3 * r2;
            x6 = x5 * x;
            x5y = x5 * y;
            x4y2 = x4 * y2;
            x3y3 = x3 * y3;
            x2y4 = x2 * y4;
            xy5 = x * y5;
            y6 = y * y5;
            x7 = x6 * x;
            x6y = x6 * y;
            x5y2 = x5 * y2;
            x4y3 = x4 * y3;
            x3y4 = x3 * y4;
            x2y5 = x2 * y5;
            xy6 = x * y6;
            y7 = y * y6;
            r7 = r5 * r2;
        }

        /** The longitude-axis polynomial in {@code a} (C's {@code f} and {@code xi}). */
        double xi(double[] a) {
            return a[0]
                    + a[1] * x
                    + a[2] * y
                    + a[3] * r
                    + a[4] * x2
                    + a[5] * xy
                    + a[6] * y2
                    + a[7] * x3
                    + a[8] * x2y
                    + a[9] * xy2
                    + a[10] * y3
                    + a[11] * r3
                    + a[12] * x4
                    + a[13] * x3y
                    + a[14] * x2y2
                    + a[15] * xy3
                    + a[16] * y4
                    + a[17] * x5
                    + a[18] * x4y
                    + a[19] * x3y2
                    + a[20] * x2y3
                    + a[21] * xy4
                    + a[22] * y5
                    + a[23] * r5
                    + a[24] * x6
                    + a[25] * x5y
                    + a[26] * x4y2
                    + a[27] * x3y3
                    + a[28] * x2y4
                    + a[29] * xy5
                    + a[30] * y6
                    + a[31] * x7
                    + a[32] * x6y
                    + a[33] * x5y2
                    + a[34] * x4y3
                    + a[35] * x3y4
                    + a[36] * x2y5
                    + a[37] * xy6
                    + a[38] * y7
                    + a[39] * r7;
        }

        /** The latitude-axis polynomial in {@code b} (C's {@code g} and {@code eta}). */
        double eta(double[] b) {
            return b[0]
                    + b[1] * y
                    + b[2] * x
                    + b[3] * r
                    + b[4] * y2
                    + b[5] * xy
                    + b[6] * x2
                    + b[7] * y3
                    + b[8] * xy2
                    + b[9] * x2y
                    + b[10] * x3
                    + b[11] * r3
                    + b[12] * y4
                    + b[13] * xy3
                    + b[14] * x2y2
                    + b[15] * x3y
                    + b[16] * x4
                    + b[17] * y5
                    + b[18] * xy4
                    + b[19] * x2y3
                    + b[20] * x3y2
                    + b[21] * x4y
                    + b[22] * x5
                    + b[23] * r5
                    + b[24] * y6
                    + b[25] * xy5
                    + b[26] * x2y4
                    + b[27] * x3y3
                    + b[28] * x4y2
                    + b[29] * x5y
                    + b[30] * x6
                    + b[31] * y7
                    + b[32] * xy6
                    + b[33] * x2y5
                    + b[34] * x3y4
                    + b[35] * x4y3
                    + b[36] * x5y2
                    + b[37] * x6y
                    + b[38] * x7
                    + b[39] * r7;
        }
    }

    // tpn.c astTPNset.
    static Status tpnSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        // If all coefficients have their "unit" values, the polynomial correction is not needed.
        prm.w[0] = 0.0;

        if (prm.p[0] != 0.0 || prm.p2[0] != 0.0) {
            prm.w[0] = 1.0;
        } else if (prm.p[1] != 1.0 || prm.p2[1] != 1.0) {
            prm.w[0] = 1.0;
        } else {
            for (int m = 2; m < WcsProjection.WCSLIB_MXPAR; m++) {
                if (prm.p[m] != 0.0 || prm.p2[m] != 0.0) {
                    prm.w[0] = 1.0;
                    break;
                }
            }
        }

        return Status.OK;
    }

    // tpn.c astTPNfwd.
    static Status tpnFwd(PrjPrm prm, double phi, double theta, double[] out) {
        // The gnomonic projection of (phi, theta) to (xi, eta), or (phi, theta) unchanged when the projection
        // excludes the TAN term; a point beyond the equator is bad.
        double xi;
        double eta;
        if (prm.n != 0) {
            double s = Trig.sind(theta);
            if (s < 0.0) {
                out[0] = 0.0;
                out[1] = 0.0;
                return Status.BAD_POINT;
            }
            double r = prm.r0 * Trig.cosd(theta) / s;
            xi = r * Trig.sind(phi);
            eta = -r * Trig.cosd(phi);
        } else {
            xi = phi;
            eta = theta;
        }

        // Simple tan.
        if (prm.w[0] == 0.0) {
            out[0] = xi;
            out[1] = eta;
            return Status.OK;
        }

        // Tan with polynomial corrections: iterate using Newton's method to get the (x, y) corresponding to the
        // above (xi, eta).
        double[] a = prm.p2;
        double[] b = prm.p;

        // Initial guess: linear solution assuming a3,... and b3,... are zero.
        double x;
        double y;
        double denom = a[1] * b[1] - a[2] * b[2];
        if (denom != 0.0) {
            x = (xi * b[1] - eta * a[2] - a[0] * b[1] + b[0] * a[2]) / denom;
            y = -(xi * b[2] - eta * a[1] - a[0] * b[2] + b[0] * a[1]) / denom;
        } else {
            if (a[1] != 0.0) {
                x = (xi - a[0]) / a[1];
            } else {
                x = a[0];
            }

            if (b[1] != 0.0) {
                y = (eta - b[0]) / b[1];
            } else {
                y = b[0];
            }
        }

        boolean ok = false;
        for (int i = 0; i < NEWTON_MAX_ITERATIONS; i++) {
            Terms t = new Terms(x, y);
            double r = t.r;

            // The xi and eta models corresponding to the current x and y.
            double f = t.xi(a);
            double g = t.eta(b);

            double xr = r != 0.0 ? x / r : 0.0;
            double yr = r != 0.0 ? y / r : 0.0;

            // Partial derivative of xi wrt x...
            double fx = a[1]
                    + a[3] * xr
                    + 2.0 * a[4] * x
                    + a[5] * y
                    + 3.0 * a[7] * t.x2
                    + 2.0 * a[8] * t.xy
                    + a[9] * t.y2
                    + 3.0 * a[11] * r * x
                    + 4.0 * a[12] * t.x3
                    + 3.0 * a[13] * t.x2y
                    + 2.0 * a[14] * t.xy2
                    + a[15] * t.y3
                    + 5.0 * a[17] * t.x4
                    + 4.0 * a[18] * t.x3y
                    + 3.0 * a[19] * t.x2y2
                    + 2.0 * a[20] * t.xy3
                    + a[21] * t.y4
                    + 5.0 * a[23] * t.r3 * x
                    + 6.0 * a[24] * t.x5
                    + 5.0 * a[25] * t.x4y
                    + 4.0 * a[26] * t.x3y2
                    + 3.0 * a[27] * t.x2y3
                    + 2.0 * a[28] * t.xy4
                    + a[29] * t.y5
                    + 7.0 * a[31] * t.x6
                    + 6.0 * a[32] * t.x5y
                    + 5.0 * a[33] * t.x4y2
                    + 4.0 * a[34] * t.x3y3
                    + 3.0 * a[35] * t.x2y4
                    + 2.0 * a[36] * t.xy5
                    + a[37] * t.y6
                    + 7.0 * a[39] * t.r5 * x;
            // Partial derivative of xi wrt y...
            double fy = a[2]
                    + a[3] * yr
                    + a[5] * x
                    + 2.0 * a[6] * y
                    + a[8] * t.x2
                    + 2.0 * a[9] * t.xy
                    + 3.0 * a[10] * t.y2
                    + 3.0 * a[11] * r * y
                    + a[13] * t.x3
                    + 2.0 * a[14] * t.x2y
                    + 3.0 * a[15] * t.xy2
                    + 4.0 * a[16] * t.y3
                    + a[18] * t.x4
                    + 2.0 * a[19] * t.x3y
                    + 3.0 * a[20] * t.x2y2
                    + 4.0 * a[21] * t.xy3
                    + 5.0 * a[22] * t.y4
                    + 5.0 * a[23] * t.r3 * y
                    + a[25] * t.x5
                    + 2.0 * a[26] * t.x4y
                    + 3.0 * a[27] * t.x3y2
                    + 4.0 * a[28] * t.x2y3
                    + 5.0 * a[29] * t.xy4
                    + 6.0 * a[30] * t.y5
                    + a[32] * t.x6
                    + 2.0 * a[33] * t.x5y
                    + 3.0 * a[34] * t.x4y2
                    + 4.0 * a[35] * t.x3y3
                    + 5.0 * a[36] * t.x2y4
                    + 6.0 * a[37] * t.xy5
                    + 7.0 * a[38] * t.y6
                    + 7.0 * a[39] * t.r5 * y;
            // Partial derivative of eta wrt x...
            double gx = b[2]
                    + b[3] * xr
                    + b[5] * y
                    + 2.0 * b[6] * x
                    + b[8] * t.y2
                    + 2.0 * b[9] * t.xy
                    + 3.0 * b[10] * t.x2
                    + 3.0 * b[11] * r * x
                    + b[13] * t.y3
                    + 2.0 * b[14] * t.xy2
                    + 3.0 * b[15] * t.x2y
                    + 4.0 * b[16] * t.x3
                    + b[18] * t.y4
                    + 2.0 * b[19] * t.xy3
                    + 3.0 * b[20] * t.x2y2
                    + 4.0 * b[21] * t.x3y
                    + 5.0 * b[22] * t.x4
                    + 5.0 * b[23] * t.r3 * x
                    + b[25] * t.y5
                    + 2.0 * b[26] * t.xy4
                    + 3.0 * b[27] * t.x2y3
                    + 4.0 * b[28] * t.x3y2
                    + 5.0 * b[29] * t.x4y
                    + 6.0 * b[30] * t.x5
                    + b[32] * t.y6
                    + 2.0 * b[33] * t.xy5
                    + 3.0 * b[34] * t.x2y4
                    + 4.0 * b[35] * t.x3y3
                    + 5.0 * b[36] * t.x4y2
                    + 6.0 * b[37] * t.x5y
                    + 7.0 * b[38] * t.x6
                    + 7.0 * b[39] * t.r5 * x;
            // Partial derivative of eta wrt y...
            double gy = b[1]
                    + b[3] * yr
                    + 2.0 * b[4] * y
                    + b[5] * x
                    + 3.0 * b[7] * t.y2
                    + 2.0 * b[8] * t.xy
                    + b[9] * t.x2
                    + 3.0 * b[11] * r * y
                    + 4.0 * b[12] * t.y3
                    + 3.0 * b[13] * t.xy2
                    + 2.0 * b[14] * t.x2y
                    + b[15] * t.x3
                    + 5.0 * b[17] * t.y4
                    + 4.0 * b[18] * t.xy3
                    + 3.0 * b[19] * t.x2y2
                    + 2.0 * b[20] * t.x3y
                    + b[21] * t.x4
                    + 5.0 * b[23] * t.r3 * y
                    + 6.0 * b[24] * t.y5
                    + 5.0 * b[25] * t.xy4
                    + 4.0 * b[26] * t.x2y3
                    + 3.0 * b[27] * t.x3y2
                    + 2.0 * b[28] * t.x4y
                    + b[29] * t.x5
                    + 7.0 * b[31] * t.y6
                    + 6.0 * b[32] * t.xy5
                    + 5.0 * b[33] * t.x2y4
                    + 4.0 * b[34] * t.x3y3
                    + 3.0 * b[35] * t.x4y2
                    + 2.0 * b[36] * t.x5y
                    + b[37] * t.x6
                    + 7.0 * b[39] * t.r5 * y;

            // Calculate new x and y values.
            f = f - xi;
            g = g - eta;
            double dx = ((-f * gy) + (g * fy)) / ((fx * gy) - (fy * gx));
            double dy = ((-g * fx) + (f * gx)) / ((fx * gy) - (fy * gx));
            x += dx;
            y += dy;

            // Check if convergence has been achieved.
            if (Math.abs(dx) <= NEWTON_RELATIVE_TOLERANCE * Math.abs(x)
                    && Math.abs(dy) <= NEWTON_RELATIVE_TOLERANCE * Math.abs(y)) {
                ok = true;
                break;
            }
        }

        // The last iterate is returned whether or not the iteration converged.
        out[0] = x;
        out[1] = y;
        return ok ? Status.OK : Status.BAD_POINT;
    }

    // tpn.c astTPNrev.
    static Status tpnRev(PrjPrm prm, double x, double y, double[] out) {
        double xi;
        double eta;

        // Simple tan.
        if (prm.w[0] == 0.0) {
            xi = x;
            eta = y;
        } else {
            // Tan with polynomial corrections.
            Terms t = new Terms(x, y);
            xi = t.xi(prm.p2);
            eta = t.eta(prm.p);
        }

        // Now do the tan projection.
        if (prm.n != 0) {
            double r = Math.sqrt(xi * xi + eta * eta);
            out[0] = r == 0.0 ? 0.0 : Trig.atan2d(xi, -eta);
            out[1] = Trig.atan2d(prm.r0, r);
        } else {
            out[0] = xi;
            out[1] = eta;
        }

        return Status.OK;
    }
}
