package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The zenithal projections: AZP, SZP, TAN, STG, SIN, ARC, ZPN, ZEA and AIR.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a
 * {@link PrjPrm}, and a forward and a reverse function. The bounds tests that C makes only when
 * {@code prj->flag} is positive apply unconditionally, as {@link Prj} explains.
 *
 * <p>Ported from Rust {@code wcslib/zenithal.rs}; C {@code wcslib/proj.c}.
 */
final class Zenithal {

    /** How far beyond 1 AZP's reverse {@code |t|} may be and still be taken as 1; {@code tol} in C. */
    private static final double AZP_REV_TOL = 1.0e-13;

    /** How close beyond +-1 sin(theta) may be and still be taken as +-1; {@code tol} in C. */
    private static final double SZP_REV_TOL = 1.0e-13;

    /** Below this r^2 SZP's reverse uses the small angle formula, the literal in C. */
    private static final double SZP_REV_SMALL_R2 = 1.0e-10;

    /** Below this angle from the pole, in radians, SIN's forward uses the small angle formulae, the literal in C. */
    private static final double SIN_SMALL_T = 1.0e-5;

    /** How close beyond +-1 sin(theta) may be and still be taken as +-1; {@code tol} in C. */
    private static final double SIN_REV_TOL = 1.0e-13;

    /** Below this r^2 SIN's reverse uses the small angle formula, the literal in C. */
    private static final double SIN_REV_SMALL_R2 = 1.0e-10;

    /**
     * How close to zero the derivative must be, in ZPN's search for the point of inflection, to be
     * taken as zero; {@code tol} in C.
     */
    private static final double ZPN_SET_TOL = 1.0e-13;

    /** The number of degrees searched for the point where ZPN's derivative first goes negative. */
    private static final int ZPN_SET_MAX_DEGREES = 180;

    /** The number of iterations that find where ZPN's derivative is zero, the literal in C. */
    private static final int ZPN_SET_MAX_ITER = 10;

    /**
     * How far beyond a limit a ZPN root may be and still be taken as the limit, and how close a
     * polynomial value must be to the target to end the search; {@code tol} in C.
     */
    private static final double ZPN_REV_TOL = 1.0e-13;

    /** The most interval divisions ZPN's iterative solution makes, the literal in C. */
    private static final int ZPN_REV_MAX_ITER = 100;

    /** How close r may be to the edge of the ZEA map, in the units of r, and be taken as the south pole. */
    private static final double ZEA_REV_TOL = 1.0e-12;

    /** Below this xi, in radians, AIR's forward projection uses its small angle form; {@code tol} in C. */
    private static final double AIR_SET_TOL = 1.0e-4;

    /** How close AIR's trial radius must be to the target to end the search; {@code tol} in C. */
    private static final double AIR_REV_TOL = 1.0e-12;

    /** The most halvings of AIR's bracketing interval, the literal in C. */
    private static final int AIR_REV_MAX_BRACKET = 30;

    /** The most interval divisions of AIR's solution, the literal in C. */
    private static final int AIR_REV_MAX_ITER = 100;

    /** The lower bound on the weight of an interval division, the literal in C. */
    private static final double LAMBDA_MIN = 0.1;

    /** The upper bound on the weight of an interval division, the literal in C. */
    private static final double LAMBDA_MAX = 0.9;

    private Zenithal() {}

    /** C's {@code (a > b) ? a : b}, which differs from {@link Math#max} when either is NaN. */
    static double cMax(double a, double b) {
        return a > b ? a : b;
    }

    /** C's {@code (a < b) ? a : b}, which differs from {@link Math#min} when either is NaN. */
    static double cMin(double a, double b) {
        return a < b ? a : b;
    }

    /**
     * {@code x} with the sign of {@code y}, taking {@code -0.0} and NaN as non-negative, which
     * differs from {@link Math#copySign} for both (wcslib's {@code copysign} macro in {@code proj.c}).
     */
    static double cCopysign(double x, double y) {
        return y < 0.0 ? -Math.abs(x) : Math.abs(x);
    }

    private static Status put(Status status, double a, double b, double[] out) {
        out[0] = a;
        out[1] = b;
        return status;
    }

    // ---------------------------------------------------------------------------
    // AZP: zenithal/azimuthal perspective
    // ---------------------------------------------------------------------------

    // proj.c astAZPset.
    static Status azpSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = prm.r0 * (prm.p[1] + 1.0);
        if (prm.w[0] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[3] = Trig.cosd(prm.p[2]);
        if (prm.w[3] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[2] = 1.0 / prm.w[3];
        prm.w[4] = Trig.sind(prm.p[2]);
        prm.w[1] = prm.w[4] / prm.w[3];

        if (Math.abs(prm.p[1]) > 1.0) {
            prm.w[5] = Trig.asind(-1.0 / prm.p[1]);
        } else {
            prm.w[5] = -90.0;
        }

        prm.w[6] = prm.p[1] * prm.w[3];
        prm.w[7] = Math.abs(prm.w[6]) < 1.0 ? 1.0 : 0.0;

        return Status.OK;
    }

    // proj.c astAZPfwd.
    // C returns at t == 0 before it computes x and y, and the outputs it returns are then (0, 0).
    static Status azpFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double cphi = Trig.cosd(phi);
        double cthe = Trig.cosd(theta);
        double s = prm.w[1] * cphi;
        double t = (prm.p[1] + Trig.sind(theta)) + cthe * s;

        double r = prm.w[0] * cthe / t;
        double x = r * Trig.sind(phi);
        double y = -r * cphi * prm.w[2];

        // The zero denominator, then bounds checking: the overlap.
        if (t == 0.0) {
            return put(Status.BAD_POINT, 0.0, 0.0, out);
        }
        if (theta < prm.w[5]) {
            return put(Status.BAD_POINT, x, y, out);
        }

        // Divergence.
        if (prm.w[7] > 0.0) {
            double t2 = prm.p[1] / Math.sqrt(1.0 + s * s);

            if (Math.abs(t2) <= 1.0) {
                double s3 = Trig.atand(-s);
                double t3 = Trig.asind(t2);
                double a = s3 - t3;
                double b = s3 + t3 + 180.0;

                if (a > 90.0) {
                    a -= 360.0;
                }
                if (b > 90.0) {
                    b -= 360.0;
                }

                if (theta < cMax(a, b)) {
                    return put(Status.BAD_POINT, x, y, out);
                }
            }
        }

        return put(Status.OK, x, y, out);
    }

    // proj.c astAZPrev.
    static Status azpRev(PrjPrm prm, double x, double y, double[] out) {
        double ycosg = y * prm.w[3];

        double r = Math.sqrt(x * x + ycosg * ycosg);
        double phi;
        double theta;
        if (r == 0.0) {
            phi = 0.0;
            theta = 90.0;
        } else {
            phi = Trig.atan2d(x, -ycosg);

            double s = r / (prm.w[0] + y * prm.w[4]);
            double t = s * prm.p[1] / Math.sqrt(s * s + 1.0);

            s = Trig.atan2d(1.0, s);

            if (Math.abs(t) > 1.0) {
                t = cCopysign(90.0, t);
                if (Math.abs(t) > 1.0 + AZP_REV_TOL) {
                    return put(Status.BAD_POINT, phi, 0.0, out);
                }
            } else {
                t = Trig.asind(t);
            }

            double a = s - t;
            double b = s + t + 180.0;

            if (a > 90.0) {
                a -= 360.0;
            }
            if (b > 90.0) {
                b -= 360.0;
            }

            theta = cMax(a, b);
        }

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // SZP: slant zenithal perspective
    // ---------------------------------------------------------------------------

    // proj.c astSZPset.
    static Status szpSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = 1.0 / prm.r0;

        prm.w[3] = prm.p[1] * Trig.sind(prm.p[3]) + 1.0;
        if (prm.w[3] == 0.0) {
            return Status.BAD_PARAMS;
        }

        prm.w[1] = -prm.p[1] * Trig.cosd(prm.p[3]) * Trig.sind(prm.p[2]);
        prm.w[2] = prm.p[1] * Trig.cosd(prm.p[3]) * Trig.cosd(prm.p[2]);
        prm.w[4] = prm.r0 * prm.w[1];
        prm.w[5] = prm.r0 * prm.w[2];
        prm.w[6] = prm.r0 * prm.w[3];
        prm.w[7] = (prm.w[3] - 1.0) * prm.w[3] - 1.0;

        if (Math.abs(prm.w[3] - 1.0) < 1.0) {
            prm.w[8] = Trig.asind(1.0 - prm.w[3]);
        } else {
            prm.w[8] = -90.0;
        }

        return Status.OK;
    }

    // proj.c astSZPfwd.
    // C returns at t == 0 before it computes x and y, and the outputs it returns are then (0, 0).
    static Status szpFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double cphi = Trig.cosd(phi);
        double sphi = Trig.sind(phi);
        double cthe = Trig.cosd(theta);
        double s = 1.0 - Trig.sind(theta);

        double t = prm.w[3] - s;

        double x = (prm.w[6] * cthe * sphi - prm.w[4] * s) / t;
        double y = -(prm.w[6] * cthe * cphi + prm.w[5] * s) / t;

        // The zero denominator, then bounds checking: the divergence.
        if (t == 0.0) {
            return put(Status.BAD_POINT, 0.0, 0.0, out);
        }
        if (theta < prm.w[8]) {
            return put(Status.BAD_POINT, x, y, out);
        }

        // Overlap.
        if (Math.abs(prm.p[1]) > 1.0) {
            double s2 = prm.w[1] * sphi - prm.w[2] * cphi;
            double t2 = 1.0 / Math.sqrt(prm.w[7] + s2 * s2);

            if (Math.abs(t2) <= 1.0) {
                double s3 = Trig.atan2d(s2, prm.w[3] - 1.0);
                double t3 = Trig.asind(t2);
                double a = s3 - t3;
                double b = s3 + t3 + 180.0;

                if (a > 90.0) {
                    a -= 360.0;
                }
                if (b > 90.0) {
                    b -= 360.0;
                }

                if (theta < cMax(a, b)) {
                    return put(Status.BAD_POINT, x, y, out);
                }
            }
        }

        return put(Status.OK, x, y, out);
    }

    // proj.c astSZPrev.
    // C returns at a bad point before it computes either output, and the outputs it returns are
    // then (0, 0).
    static Status szpRev(PrjPrm prm, double x, double y, double[] out) {
        double xp = x * prm.w[0];
        double yp = y * prm.w[0];
        double r2 = xp * xp + yp * yp;

        double x1 = (xp - prm.w[1]) / prm.w[3];
        double y1 = (yp - prm.w[2]) / prm.w[3];
        double sxy = xp * x1 + yp * y1;

        double z;
        double theta;
        if (r2 < SZP_REV_SMALL_R2) {
            // Use small angle formula.
            z = r2 / 2.0;
            theta = 90.0 - Trig.R2D * Math.sqrt(r2 / (1.0 + sxy));
        } else {
            double t = x1 * x1 + y1 * y1;
            double a = t + 1.0;
            double b = sxy - t;
            double c = r2 - sxy - sxy + t - 1.0;
            double d = b * b - a * c;

            // Check for a solution.
            if (d < 0.0) {
                return put(Status.BAD_POINT, 0.0, 0.0, out);
            }
            d = Math.sqrt(d);

            // Choose solution closest to pole.
            double sth1 = (-b + d) / a;
            double sth2 = (-b - d) / a;
            double sthe = cMax(sth1, sth2);
            if (sthe > 1.0) {
                if (sthe - 1.0 < SZP_REV_TOL) {
                    sthe = 1.0;
                } else {
                    sthe = cMin(sth1, sth2);
                }
            }

            if (sthe < -1.0 && sthe + 1.0 > -SZP_REV_TOL) {
                sthe = -1.0;
            }

            if (sthe > 1.0 || sthe < -1.0) {
                return put(Status.BAD_POINT, 0.0, 0.0, out);
            }

            theta = Trig.asind(sthe);
            z = 1.0 - sthe;
        }

        double phi = Trig.atan2d(xp - x1 * z, -(yp - y1 * z));

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // TAN: gnomonic
    // ---------------------------------------------------------------------------

    // proj.c astTANset.
    static Status tanSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        return Status.OK;
    }

    // proj.c astTANfwd.
    static Status tanFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double s = Trig.sind(theta);

        double r = prm.r0 * Trig.cosd(theta) / s;
        double x = r * Trig.sind(phi);
        double y = -r * Trig.cosd(phi);

        // C returns for s == 0 before computing x and y, and for s < 0 after; the outputs of a
        // bad point are not used.
        if (s <= 0.0) {
            return put(Status.BAD_POINT, x, y, out);
        }

        return put(Status.OK, x, y, out);
    }

    // proj.c astTANrev.
    static Status tanRev(PrjPrm prm, double x, double y, double[] out) {
        double r = Math.sqrt(x * x + y * y);
        double phi = r == 0.0 ? 0.0 : Trig.atan2d(x, -y);
        double theta = Trig.atan2d(prm.r0, r);

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // STG: stereographic
    // ---------------------------------------------------------------------------

    // proj.c astSTGset.
    static Status stgSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 360.0 / Math.PI;
            prm.w[1] = Math.PI / 360.0;
        } else {
            prm.w[0] = 2.0 * prm.r0;
            prm.w[1] = 1.0 / prm.w[0];
        }

        return Status.OK;
    }

    // proj.c astSTGfwd.
    static Status stgFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double s = 1.0 + Trig.sind(theta);

        double r = prm.w[0] * Trig.cosd(theta) / s;
        double x = r * Trig.sind(phi);
        double y = -r * Trig.cosd(phi);

        if (s == 0.0) {
            return put(Status.BAD_POINT, x, y, out);
        }

        return put(Status.OK, x, y, out);
    }

    // proj.c astSTGrev.
    static Status stgRev(PrjPrm prm, double x, double y, double[] out) {
        double r = Math.sqrt(x * x + y * y);
        double phi = r == 0.0 ? 0.0 : Trig.atan2d(x, -y);
        double theta = 90.0 - 2.0 * Trig.atand(r * prm.w[1]);

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // SIN: orthographic/synthesis
    // ---------------------------------------------------------------------------

    // proj.c astSINset.
    static Status sinSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = 1.0 / prm.r0;
        prm.w[1] = prm.p[1] * prm.p[1] + prm.p[2] * prm.p[2];
        prm.w[2] = prm.w[1] + 1.0;
        prm.w[3] = prm.w[1] - 1.0;

        return Status.OK;
    }

    // proj.c astSINfwd.
    static Status sinFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double t = (90.0 - Math.abs(theta)) * Trig.D2R;
        double z;
        double cthe;
        if (t < SIN_SMALL_T) {
            if (theta > 0.0) {
                z = t * t / 2.0;
            } else {
                z = 2.0 - t * t / 2.0;
            }
            cthe = t;
        } else {
            z = 1.0 - Trig.sind(theta);
            cthe = Trig.cosd(theta);
        }

        double cphi = Trig.cosd(phi);
        double sphi = Trig.sind(phi);
        double x = prm.r0 * (cthe * sphi + prm.p[1] * z);
        double y = -prm.r0 * (cthe * cphi - prm.p[2] * z);

        // Validate this solution: the orthographic projection is bad beyond the equator, and the
        // synthesis projection beyond the native latitude where the projection plane is tangent
        // to the sphere.
        boolean bad;
        if (prm.w[1] == 0.0) {
            bad = theta < 0.0;
        } else {
            bad = theta < -Trig.atand(prm.p[1] * sphi - prm.p[2] * cphi);
        }

        return put(bad ? Status.BAD_POINT : Status.OK, x, y, out);
    }

    // proj.c astSINrev.
    static Status sinRev(PrjPrm prm, double x, double y, double[] out) {
        // Compute intermediaries.
        double x0 = x * prm.w[0];
        double y0 = y * prm.w[0];
        double r2 = x0 * x0 + y0 * y0;

        double phi;
        double theta;
        if (prm.w[1] == 0.0) {
            // Orthographic projection.
            phi = r2 != 0.0 ? Trig.atan2d(x0, -y0) : 0.0;

            if (r2 < 0.5) {
                theta = Trig.acosd(Math.sqrt(r2));
            } else if (r2 <= 1.0) {
                theta = Trig.asind(Math.sqrt(1.0 - r2));
            } else {
                return put(Status.BAD_POINT, phi, 0.0, out);
            }
        } else {
            // "Synthesis" projection.
            double x1 = prm.p[1];
            double y1 = prm.p[2];
            double sxy = x0 * x1 + y0 * y1;

            double z;
            if (r2 < SIN_REV_SMALL_R2) {
                // Use small angle formula.
                z = r2 / 2.0;
                theta = 90.0 - Trig.R2D * Math.sqrt(r2 / (1.0 + sxy));
            } else {
                double a = prm.w[2];
                double b = sxy - prm.w[1];
                double c = r2 - sxy - sxy + prm.w[3];
                double d = b * b - a * c;

                // Check for a solution.
                if (d < 0.0) {
                    return put(Status.BAD_POINT, 0.0, 0.0, out);
                }
                d = Math.sqrt(d);

                // Choose solution closest to pole.
                double sth1 = (-b + d) / a;
                double sth2 = (-b - d) / a;
                double sthe = cMax(sth1, sth2);
                if (sthe > 1.0) {
                    if (sthe - 1.0 < SIN_REV_TOL) {
                        sthe = 1.0;
                    } else {
                        sthe = cMin(sth1, sth2);
                    }
                }

                if (sthe < -1.0 && sthe + 1.0 > -SIN_REV_TOL) {
                    sthe = -1.0;
                }

                if (sthe > 1.0 || sthe < -1.0) {
                    return put(Status.BAD_POINT, 0.0, 0.0, out);
                }

                theta = Trig.asind(sthe);
                z = 1.0 - sthe;
            }

            double xp = -y0 + prm.p[2] * z;
            double yp = x0 - prm.p[1] * z;
            if (xp == 0.0 && yp == 0.0) {
                phi = 0.0;
            } else {
                phi = Trig.atan2d(yp, xp);
            }
        }

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // ARC: zenithal/azimuthal equidistant
    // ---------------------------------------------------------------------------

    // proj.c astARCset.
    static Status arcSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

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

    // proj.c astARCfwd.
    static Status arcFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double r = prm.w[0] * (90.0 - theta);
        double x = r * Trig.sind(phi);
        double y = -r * Trig.cosd(phi);

        return put(Status.OK, x, y, out);
    }

    // proj.c astARCrev.
    static Status arcRev(PrjPrm prm, double x, double y, double[] out) {
        double r = Math.sqrt(x * x + y * y);
        double phi = r == 0.0 ? 0.0 : Trig.atan2d(x, -y);
        double theta = 90.0 - r * prm.w[1];

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // ZPN: zenithal/azimuthal polynomial
    // ---------------------------------------------------------------------------

    // proj.c astZPNset.
    // The search for the highest non-zero coefficient covers every slot of the parameter array,
    // C's astSizeOf(prj->p)/sizeof(double).
    static Status zpnSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        // Find the highest non-zero coefficient.
        int k = WcsProjection.PRJ_PARAM_SLOTS - 1;
        while (k >= 0 && prm.p[k] == 0.0) {
            k--;
        }
        if (k < 0) {
            return Status.BAD_PARAMS;
        }

        prm.n = k;

        if (k < 2) {
            // No point of inflection for polynomials of degree < 2.
            prm.w[0] = Math.PI;
            prm.w[1] = 0.0;
        } else {
            // Find the point of inflection closest to the pole.
            double zd1 = 0.0;
            double d1 = prm.p[1];
            if (d1 <= 0.0) {
                return Status.BAD_PARAMS;
            }

            // Find the point where the derivative first goes negative.
            double zd2 = 0.0;
            double d2 = 0.0;
            int i = 0;
            while (i < ZPN_SET_MAX_DEGREES) {
                zd2 = i * Trig.D2R;
                d2 = 0.0;
                for (int j = k; j > 0; j--) {
                    d2 = d2 * zd2 + j * prm.p[j];
                }

                if (d2 <= 0.0) {
                    break;
                }
                zd1 = zd2;
                d1 = d2;
                i++;
            }

            double zd;
            if (i == ZPN_SET_MAX_DEGREES) {
                // No negative derivative -> no point of inflection.
                zd = Math.PI;
            } else {
                // Find where the derivative is zero.
                zd = 0.0;
                for (int iter = 1; iter <= ZPN_SET_MAX_ITER; iter++) {
                    zd = zd1 - d1 * (zd2 - zd1) / (d2 - d1);

                    double d = 0.0;
                    for (int j = k; j > 0; j--) {
                        d = d * zd + j * prm.p[j];
                    }

                    if (Math.abs(d) < ZPN_SET_TOL) {
                        break;
                    }

                    if (d < 0.0) {
                        zd2 = zd;
                        d2 = d;
                    } else {
                        zd1 = zd;
                        d1 = d;
                    }
                }
            }

            double r = 0.0;
            for (int j = k; j >= 0; j--) {
                r = r * zd + prm.p[j];
            }
            prm.w[0] = zd;
            prm.w[1] = r;
        }

        return Status.OK;
    }

    // proj.c astZPNfwd.
    // The loop runs from the degree n down, as AST's modification of wcslib has it, not from the
    // largest degree a parameter array holds.
    static Status zpnFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double s = (90.0 - theta) * Trig.D2R;

        double r = 0.0;
        for (int j = prm.n; j >= 0; j--) {
            r = r * s + prm.p[j];
        }
        r *= prm.r0;

        double x = r * Trig.sind(phi);
        double y = -r * Trig.cosd(phi);

        if (s > prm.w[0]) {
            return put(Status.BAD_POINT, x, y, out);
        }

        return put(Status.OK, x, y, out);
    }

    // proj.c astZPNrev.
    static Status zpnRev(PrjPrm prm, double x, double y, double[] out) {
        int k = prm.n;

        double r = Math.sqrt(x * x + y * y) / prm.r0;

        double zd;
        if (k < 1) {
            // Constant - no solution.
            return put(Status.BAD_PARAMS, 0.0, 0.0, out);
        } else if (k == 1) {
            // Linear.
            zd = (r - prm.p[0]) / prm.p[1];
        } else if (k == 2) {
            // Quadratic.
            double a = prm.p[2];
            double b = prm.p[1];
            double c = prm.p[0] - r;

            double d = b * b - 4.0 * a * c;
            if (d < 0.0) {
                return put(Status.BAD_POINT, 0.0, 0.0, out);
            }
            d = Math.sqrt(d);

            // Choose solution closest to pole.
            double zd1 = (-b + d) / (2.0 * a);
            double zd2 = (-b - d) / (2.0 * a);
            double z = cMin(zd1, zd2);
            if (z < -ZPN_REV_TOL) {
                z = cMax(zd1, zd2);
            }
            if (z < 0.0) {
                if (z < -ZPN_REV_TOL) {
                    return put(Status.BAD_POINT, 0.0, 0.0, out);
                }
                z = 0.0;
            } else if (z > Math.PI) {
                if (z > Math.PI + ZPN_REV_TOL) {
                    return put(Status.BAD_POINT, 0.0, 0.0, out);
                }
                z = Math.PI;
            }
            zd = z;
        } else {
            // Higher order - solve iteratively.
            double zd1 = 0.0;
            double r1 = prm.p[0];
            double zd2 = prm.w[0];
            double r2 = prm.w[1];

            double z = 0.0;
            if (r < r1) {
                if (r < r1 - ZPN_REV_TOL) {
                    return put(Status.BAD_POINT, 0.0, 0.0, out);
                }
                z = zd1;
            } else if (r > r2) {
                if (r > r2 + ZPN_REV_TOL) {
                    return put(Status.BAD_POINT, 0.0, 0.0, out);
                }
                z = zd2;
            } else {
                // Dissect the interval.
                for (int j = 0; j < ZPN_REV_MAX_ITER; j++) {
                    double lambda = (r2 - r) / (r2 - r1);
                    if (lambda < LAMBDA_MIN) {
                        lambda = LAMBDA_MIN;
                    } else if (lambda > LAMBDA_MAX) {
                        lambda = LAMBDA_MAX;
                    }

                    z = zd2 - lambda * (zd2 - zd1);

                    double rt = 0.0;
                    for (int i = k; i >= 0; i--) {
                        rt = (rt * z) + prm.p[i];
                    }

                    if (rt < r) {
                        if (r - rt < ZPN_REV_TOL) {
                            break;
                        }
                        r1 = rt;
                        zd1 = z;
                    } else {
                        if (rt - r < ZPN_REV_TOL) {
                            break;
                        }
                        r2 = rt;
                        zd2 = z;
                    }

                    if (Math.abs(zd2 - zd1) < ZPN_REV_TOL) {
                        break;
                    }
                }
            }
            zd = z;
        }

        double phi = r == 0.0 ? 0.0 : Trig.atan2d(x, -y);
        double theta = 90.0 - zd * Trig.R2D;

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // ZEA: zenithal/azimuthal equal area
    // ---------------------------------------------------------------------------

    // proj.c astZEAset.
    static Status zeaSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 360.0 / Math.PI;
            prm.w[1] = Math.PI / 360.0;
        } else {
            prm.w[0] = 2.0 * prm.r0;
            prm.w[1] = 1.0 / prm.w[0];
        }

        return Status.OK;
    }

    // proj.c astZEAfwd.
    static Status zeaFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double r = prm.w[0] * Trig.sind((90.0 - theta) / 2.0);
        double x = r * Trig.sind(phi);
        double y = -r * Trig.cosd(phi);

        return put(Status.OK, x, y, out);
    }

    // proj.c astZEArev.
    static Status zeaRev(PrjPrm prm, double x, double y, double[] out) {
        double r = Math.sqrt(x * x + y * y);
        double phi = r == 0.0 ? 0.0 : Trig.atan2d(x, -y);

        double s = r * prm.w[1];
        double theta;
        if (Math.abs(s) > 1.0) {
            if (Math.abs(r - prm.w[0]) < ZEA_REV_TOL) {
                theta = -90.0;
            } else {
                return put(Status.BAD_POINT, phi, 0.0, out);
            }
        } else {
            theta = 90.0 - 2.0 * Trig.asind(s);
        }

        return put(Status.OK, phi, theta, out);
    }

    // ---------------------------------------------------------------------------
    // AIR: Airy
    // ---------------------------------------------------------------------------

    // proj.c astAIRset.
    static Status airSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 90.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
        }

        prm.w[0] = 2.0 * prm.r0;
        if (prm.p[1] == 90.0) {
            prm.w[1] = -0.5;
            prm.w[2] = 1.0;
        } else if (prm.p[1] > -90.0) {
            double cxi = Trig.cosd((90.0 - prm.p[1]) / 2.0);
            prm.w[1] = Math.log(cxi) * (cxi * cxi) / (1.0 - cxi * cxi);
            prm.w[2] = 0.5 - prm.w[1];
        } else {
            return Status.BAD_PARAMS;
        }

        prm.w[3] = prm.w[0] * prm.w[2];
        prm.w[4] = AIR_SET_TOL;
        prm.w[5] = prm.w[2] * AIR_SET_TOL;
        prm.w[6] = Trig.R2D / prm.w[2];

        return Status.OK;
    }

    // proj.c astAIRfwd.
    // C returns for theta at or below -90 before it computes x and y, and the outputs it returns
    // are then (0, 0).
    static Status airFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double r;
        if (theta == 90.0) {
            r = 0.0;
        } else if (theta > -90.0) {
            double xi = Trig.D2R * (90.0 - theta) / 2.0;
            if (xi < prm.w[4]) {
                r = xi * prm.w[3];
            } else {
                double cxi = Trig.cosd((90.0 - theta) / 2.0);
                double txi = Math.sqrt(1.0 - cxi * cxi) / cxi;
                r = -prm.w[0] * (Math.log(cxi) / txi + prm.w[1] * txi);
            }
        } else {
            return put(Status.BAD_POINT, 0.0, 0.0, out);
        }

        double x = r * Trig.sind(phi);
        double y = -r * Trig.cosd(phi);

        return put(Status.OK, x, y, out);
    }

    // proj.c astAIRrev.
    static Status airRev(PrjPrm prm, double x, double y, double[] out) {
        double r = Math.sqrt(x * x + y * y) / prm.w[0];

        double xi;
        if (r == 0.0) {
            xi = 0.0;
        } else if (r < prm.w[5]) {
            xi = r * prm.w[6];
        } else {
            // Find a solution interval.
            double x1 = 1.0;
            double r1 = 0.0;
            double x2 = 0.0;
            double r2 = 0.0;
            int j = 0;
            while (j < AIR_REV_MAX_BRACKET) {
                x2 = x1 / 2.0;
                double txi = Math.sqrt(1.0 - x2 * x2) / x2;
                r2 = -(Math.log(x2) / txi + prm.w[1] * txi);

                if (r2 >= r) {
                    break;
                }
                x1 = x2;
                r1 = r2;
                j++;
            }
            if (j == AIR_REV_MAX_BRACKET) {
                return put(Status.BAD_POINT, 0.0, 0.0, out);
            }

            double cxi = 0.0;
            j = 0;
            while (j < AIR_REV_MAX_ITER) {
                // Weighted division of the interval.
                double lambda = (r2 - r) / (r2 - r1);
                if (lambda < LAMBDA_MIN) {
                    lambda = LAMBDA_MIN;
                } else if (lambda > LAMBDA_MAX) {
                    lambda = LAMBDA_MAX;
                }
                cxi = x2 - lambda * (x2 - x1);

                double txi = Math.sqrt(1.0 - cxi * cxi) / cxi;
                double rt = -(Math.log(cxi) / txi + prm.w[1] * txi);

                if (rt < r) {
                    if (r - rt < AIR_REV_TOL) {
                        break;
                    }
                    r1 = rt;
                    x1 = cxi;
                } else {
                    if (rt - r < AIR_REV_TOL) {
                        break;
                    }
                    r2 = rt;
                    x2 = cxi;
                }
                j++;
            }
            if (j == AIR_REV_MAX_ITER) {
                return put(Status.BAD_POINT, 0.0, 0.0, out);
            }

            xi = Trig.acosd(cxi);
        }

        double phi = r == 0.0 ? 0.0 : Trig.atan2d(x, -y);
        double theta = 90.0 - 2.0 * xi;

        return put(Status.OK, phi, theta, out);
    }
}
