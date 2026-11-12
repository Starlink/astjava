package uk.ac.starlink.ast.internal.wcslib;

import uk.ac.starlink.ast.AstException;
import uk.ac.starlink.ast.internal.wcslib.Prj.PrjPrm;
import uk.ac.starlink.ast.internal.wcslib.Prj.Status;

/**
 * The quad-cube projections: TSC, CSC and QSC.
 *
 * <p>Each projection is a set-up function that derives constants from the parameters in a {@link
 * PrjPrm}, and a forward and a reverse function. The bounds tests apply unconditionally, as {@link
 * Prj} explains.
 *
 * <p>CSC works in single precision, as the C does. Its working variables and coefficients are
 * {@code float}, and an expression of {@code float} operands is evaluated in single precision.
 * Where a {@code float} meets a {@code double} value (in C, often a {@code double} literal such as
 * {@code 1.0}), the expression is evaluated in double precision and rounded to {@code float} once,
 * on assignment. A coefficient is converted from its {@code double} value rather than written as a
 * {@code float} literal, so it rounds twice, as C's initialisation of a {@code float} from a {@code
 * double} literal does. The C reference is built without floating-point contraction, so no
 * multiply-add is fused.
 *
 * <p>Ported from Rust {@code wcslib/quadcube.rs}; C {@code wcslib/proj.c}.
 */
final class Quadcube {

    /** 1/sqrt(2), {@code SQRT2INV} of {@code wcsmath.h}. */
    private static final double SQRT2INV = 1.0 / Math.sqrt(2.0);

    /** astTSCfwd: a coordinate may exceed 1 by this much for rounding. */
    private static final double TSC_FWD_TOL = 1.0e-12;

    /** astCSCfwd: a coordinate may exceed 1 by this much for rounding (a {@code float} in C). */
    private static final float CSC_FWD_TOL = (float) 1.0e-7;

    /**
     * astCSCfwd: the squares below this are taken as zero, "to avoid floating underflows" in the
     * C.
     */
    private static final double CSC_FWD_UNDERFLOW = 1.0e-16;

    // astCSCfwd: the coefficients of the polynomial that maps the tangent plane onto the cube
    // face, float in C.
    private static final float CSC_GSTAR = (float) 1.37484847732;
    private static final float CSC_MM = (float) 0.004869491981;
    private static final float CSC_GAMMA = (float) -0.13161671474;
    private static final float CSC_OMEGA1 = (float) -0.159596235474;
    private static final float CSC_D0 = (float) 0.0759196200467;
    private static final float CSC_D1 = (float) -0.0217762490699;
    private static final float CSC_C00 = (float) 0.141189631152;
    private static final float CSC_C10 = (float) 0.0809701286525;
    private static final float CSC_C01 = (float) -0.281528535557;
    private static final float CSC_C11 = (float) 0.15384112876;
    private static final float CSC_C20 = (float) -0.178251207466;
    private static final float CSC_C02 = (float) 0.106959469314;

    // astCSCrev: the coefficients of the polynomial that maps the cube face onto the tangent
    // plane, float in C.
    private static final float CSC_P00 = (float) -0.27292696;
    private static final float CSC_P10 = (float) -0.07629969;
    private static final float CSC_P20 = (float) -0.22797056;
    private static final float CSC_P30 = (float) 0.54852384;
    private static final float CSC_P40 = (float) -0.62930065;
    private static final float CSC_P50 = (float) 0.25795794;
    private static final float CSC_P60 = (float) 0.02584375;
    private static final float CSC_P01 = (float) -0.02819452;
    private static final float CSC_P11 = (float) -0.01471565;
    private static final float CSC_P21 = (float) 0.48051509;
    private static final float CSC_P31 = (float) -1.74114454;
    private static final float CSC_P41 = (float) 1.71547508;
    private static final float CSC_P51 = (float) -0.53022337;
    private static final float CSC_P02 = (float) 0.2705816;
    private static final float CSC_P12 = (float) -0.56800938;
    private static final float CSC_P22 = (float) 0.30803317;
    private static final float CSC_P32 = (float) 0.98938102;
    private static final float CSC_P42 = (float) -0.83180469;
    private static final float CSC_P03 = (float) -0.6044156;
    private static final float CSC_P13 = (float) 1.50880086;
    private static final float CSC_P23 = (float) -0.93678576;
    private static final float CSC_P33 = (float) 0.08693841;
    private static final float CSC_P04 = (float) 0.93412077;
    private static final float CSC_P14 = (float) -1.4160192;
    private static final float CSC_P24 = (float) 0.33887446;
    private static final float CSC_P05 = (float) -0.63915306;
    private static final float CSC_P15 = (float) 0.52032238;
    private static final float CSC_P06 = (float) 0.14381585;

    /** astQSCfwd: a coordinate may exceed 1 by this much for rounding. */
    private static final double QSC_FWD_TOL = 1.0e-12;

    /**
     * astQSCfwd: below this {@code 1 - rho} is computed from the angles instead, "small angle
     * formula" in the C.
     */
    private static final double QSC_SMALL_ANGLE = 1.0e-8;

    /** astQSCrev: {@code rho} may fall below -1 by this much for rounding. */
    private static final double QSC_REV_TOL = 1.0e-12;

    /** The value {@link #revFace} returns for a point outside the projection's bounds. */
    private static final int OUT_OF_BOUNDS = -1;

    private Quadcube() {}

    /** The constants the three set-up functions derive: wcslib writes the same body three times. */
    private static void quadcubeSet(PrjPrm prm) {
        prm.phi0 = 0.0;
        prm.theta0 = 0.0;

        if (prm.r0 == 0.0) {
            prm.r0 = Trig.R2D;
            prm.w[0] = 45.0;
            prm.w[1] = 1.0 / 45.0;
        } else {
            prm.w[0] = prm.r0 * Math.PI / 4.0;
            prm.w[1] = 1.0 / prm.w[0];
        }
    }

    /**
     * The cube face of the direction cosines (l, m, n): the index of the first of the largest of
     * {@code n}, {@code l}, {@code m}, {@code -l}, {@code -m}, {@code -n}. CSC passes its {@code
     * float} values widened, which compare and negate as they do in single precision.
     */
    private static int cubeFace(double l, double m, double n) {
        int face = 0;
        double rho = n;
        if (l > rho) {
            face = 1;
            rho = l;
        }
        if (m > rho) {
            face = 2;
            rho = m;
        }
        if (-l > rho) {
            face = 3;
            rho = -l;
        }
        if (-m > rho) {
            face = 4;
            rho = -m;
        }
        if (-n > rho) {
            face = 5;
        }
        return face;
    }

    /** The largest of the candidates {@link #cubeFace} compares, the one of face {@code face}. */
    private static double cubeRho(int face, double l, double m, double n) {
        switch (face) {
            case 0:
                return n;
            case 1:
                return l;
            case 2:
                return m;
            case 3:
                return -l;
            case 4:
                return -m;
            case 5:
                return -n;
            default:
                throw new AstException("Unexpected cube face " + face, AstException.AST__INTER);
        }
    }

    /**
     * Rounds {@code v} to single precision when {@code single} is set, as C's assignment to a
     * {@code float} does.
     */
    private static double narrow(double v, boolean single) {
        return single ? (double) (float) v : v;
    }

    /**
     * The face of the reverse projection's scaled (x, y) in {@code f[0]} and {@code f[1]}, which
     * are replaced by the position within the face, or {@link #OUT_OF_BOUNDS} for a point outside
     * the projection's bounds. The code the three reverse functions share verbatim, up to the type
     * of {@code xf} and {@code yf}: {@code single} is set where they are {@code float}, and each
     * value assigned is then rounded to single precision. The bounds tests and the face tests are
     * C's.
     */
    private static int revFace(double[] f, boolean single) {
        double xf = f[0];
        double yf = f[1];

        // Check bounds.
        if (Math.abs(xf) <= 1.0) {
            if (Math.abs(yf) > 3.0) {
                return OUT_OF_BOUNDS;
            }
        } else if (Math.abs(xf) > 7.0 || Math.abs(yf) > 1.0) {
            return OUT_OF_BOUNDS;
        }

        // Map negative faces to the other side.
        if (xf < -1.0) {
            xf = narrow(xf + 8.0, single);
        }

        // Determine the face.
        int face;
        if (xf > 5.0) {
            face = 4;
            xf = narrow(xf - 6.0, single);
        } else if (xf > 3.0) {
            face = 3;
            xf = narrow(xf - 4.0, single);
        } else if (xf > 1.0) {
            face = 2;
            xf = narrow(xf - 2.0, single);
        } else if (yf > 1.0) {
            face = 0;
            yf = narrow(yf - 2.0, single);
        } else if (yf < -1.0) {
            face = 5;
            yf = narrow(yf + 2.0, single);
        } else {
            face = 1;
        }

        f[0] = xf;
        f[1] = yf;
        return face;
    }

    /** Writes (0, 0) to {@code out} and returns {@link Status#BAD_POINT}. */
    private static Status badPoint(double[] out) {
        out[0] = 0.0;
        out[1] = 0.0;
        return Status.BAD_POINT;
    }

    // ---------------------------------------------------------------------
    // TSC: tangential spherical cube
    // ---------------------------------------------------------------------

    // proj.c astTSCset.
    static Status tscSet(PrjPrm prm) {
        quadcubeSet(prm);
        return Status.OK;
    }

    // proj.c astTSCfwd.
    static Status tscFwd(PrjPrm prm, double phi, double theta, double[] out) {
        double cthe = Trig.cosd(theta);
        double l = cthe * Trig.cosd(phi);
        double m = cthe * Trig.sind(phi);
        double n = Trig.sind(theta);

        int face = cubeFace(l, m, n);
        double rho = cubeRho(face, l, m, n);

        double xf;
        double yf;
        double x0;
        double y0;
        if (face == 0) {
            xf = m / rho;
            yf = -l / rho;
            x0 = 0.0;
            y0 = 2.0;
        } else if (face == 1) {
            xf = m / rho;
            yf = n / rho;
            x0 = 0.0;
            y0 = 0.0;
        } else if (face == 2) {
            xf = -l / rho;
            yf = n / rho;
            x0 = 2.0;
            y0 = 0.0;
        } else if (face == 3) {
            xf = -m / rho;
            yf = n / rho;
            x0 = 4.0;
            y0 = 0.0;
        } else if (face == 4) {
            xf = l / rho;
            yf = n / rho;
            x0 = 6.0;
            y0 = 0.0;
        } else {
            xf = m / rho;
            yf = l / rho;
            x0 = 0.0;
            y0 = -2.0;
        }

        if (Math.abs(xf) > 1.0) {
            if (Math.abs(xf) > 1.0 + TSC_FWD_TOL) {
                return badPoint(out);
            }
            xf = Zenithal.cCopysign(1.0, xf);
        }
        if (Math.abs(yf) > 1.0) {
            if (Math.abs(yf) > 1.0 + TSC_FWD_TOL) {
                return badPoint(out);
            }
            yf = Zenithal.cCopysign(1.0, yf);
        }

        out[0] = prm.w[0] * (xf + x0);
        out[1] = prm.w[0] * (yf + y0);
        return Status.OK;
    }

    // proj.c astTSCrev.
    static Status tscRev(PrjPrm prm, double x, double y, double[] out) {
        double[] f = {x * prm.w[1], y * prm.w[1]};
        int face = revFace(f, false);
        if (face == OUT_OF_BOUNDS) {
            return badPoint(out);
        }
        double xf = f[0];
        double yf = f[1];

        double l;
        double m;
        double n;
        if (face == 4) {
            m = -1.0 / Math.sqrt(1.0 + xf * xf + yf * yf);
            l = -m * xf;
            n = -m * yf;
        } else if (face == 3) {
            l = -1.0 / Math.sqrt(1.0 + xf * xf + yf * yf);
            m = l * xf;
            n = -l * yf;
        } else if (face == 2) {
            m = 1.0 / Math.sqrt(1.0 + xf * xf + yf * yf);
            l = -m * xf;
            n = m * yf;
        } else if (face == 0) {
            n = 1.0 / Math.sqrt(1.0 + xf * xf + yf * yf);
            l = -n * yf;
            m = n * xf;
        } else if (face == 5) {
            n = -1.0 / Math.sqrt(1.0 + xf * xf + yf * yf);
            l = -n * yf;
            m = -n * xf;
        } else {
            l = 1.0 / Math.sqrt(1.0 + xf * xf + yf * yf);
            m = l * xf;
            n = l * yf;
        }

        if (l == 0.0 && m == 0.0) {
            out[0] = 0.0;
        } else {
            out[0] = Trig.atan2d(m, l);
        }
        out[1] = Trig.asind(n);
        return Status.OK;
    }

    // ---------------------------------------------------------------------
    // CSC: COBE quadrilateralized spherical cube
    // ---------------------------------------------------------------------

    // proj.c astCSCset.
    static Status cscSet(PrjPrm prm) {
        quadcubeSet(prm);
        return Status.OK;
    }

    // proj.c astCSCfwd.
    // The C works in float throughout; see the class documentation.
    static Status cscFwd(PrjPrm prm, double phi, double theta, double[] out) {
        float cthe = (float) Trig.cosd(theta);
        float l = (float) (cthe * Trig.cosd(phi));
        float m = (float) (cthe * Trig.sind(phi));
        float n = (float) Trig.sind(theta);

        int face = cubeFace(l, m, n);
        float rho = (float) cubeRho(face, l, m, n);

        float xi;
        float eta;
        float x0;
        float y0;
        if (face == 0) {
            xi = m;
            eta = -l;
            x0 = 0.0f;
            y0 = 2.0f;
        } else if (face == 1) {
            xi = m;
            eta = n;
            x0 = 0.0f;
            y0 = 0.0f;
        } else if (face == 2) {
            xi = -l;
            eta = n;
            x0 = 2.0f;
            y0 = 0.0f;
        } else if (face == 3) {
            xi = -m;
            eta = n;
            x0 = 4.0f;
            y0 = 0.0f;
        } else if (face == 4) {
            xi = l;
            eta = n;
            x0 = 6.0f;
            y0 = 0.0f;
        } else {
            xi = m;
            eta = l;
            x0 = 0.0f;
            y0 = -2.0f;
        }

        float a = xi / rho;
        float b = eta / rho;

        float a2 = a * a;
        float b2 = b * b;
        float ca2 = (float) (1.0 - a2);
        float cb2 = (float) (1.0 - b2);

        // Avoid floating underflows.
        float ab = (float) Math.abs((double) (a * b));
        float a4 = a2 > CSC_FWD_UNDERFLOW ? a2 * a2 : 0.0f;
        float b4 = b2 > CSC_FWD_UNDERFLOW ? b2 * b2 : 0.0f;
        float a2b2 = ab > CSC_FWD_UNDERFLOW ? a2 * b2 : 0.0f;

        float xf = a
                * (a2
                        + ca2
                                * (CSC_GSTAR
                                        + b2
                                                * (CSC_GAMMA * ca2
                                                        + CSC_MM * a2
                                                        + cb2
                                                                * (CSC_C00
                                                                        + CSC_C10 * a2
                                                                        + CSC_C01 * b2
                                                                        + CSC_C11 * a2b2
                                                                        + CSC_C20 * a4
                                                                        + CSC_C02 * b4))
                                        + a2 * (CSC_OMEGA1 - ca2 * (CSC_D0 + CSC_D1 * a2))));
        float yf = b
                * (b2
                        + cb2
                                * (CSC_GSTAR
                                        + a2
                                                * (CSC_GAMMA * cb2
                                                        + CSC_MM * b2
                                                        + ca2
                                                                * (CSC_C00
                                                                        + CSC_C10 * b2
                                                                        + CSC_C01 * a2
                                                                        + CSC_C11 * a2b2
                                                                        + CSC_C20 * b4
                                                                        + CSC_C02 * a4))
                                        + b2 * (CSC_OMEGA1 - cb2 * (CSC_D0 + CSC_D1 * b2))));

        if (Math.abs((double) xf) > 1.0) {
            if (Math.abs((double) xf) > 1.0 + CSC_FWD_TOL) {
                return badPoint(out);
            }
            xf = (float) Zenithal.cCopysign(1.0, xf);
        }
        if (Math.abs((double) yf) > 1.0) {
            if (Math.abs((double) yf) > 1.0 + CSC_FWD_TOL) {
                return badPoint(out);
            }
            yf = (float) Zenithal.cCopysign(1.0, yf);
        }

        // x0 + xf and y0 + yf are single precision; the product with w[0] is double.
        out[0] = prm.w[0] * (x0 + xf);
        out[1] = prm.w[0] * (y0 + yf);
        return Status.OK;
    }

    /**
     * CSC's reverse polynomial in {@code u = s*s}, {@code v = t*t}, which the reverse projection
     * evaluates twice, once with the roles of {@code xx} and {@code yy} exchanged (astCSCrev writes
     * the series out twice).
     */
    private static float cscSeries(float u, float v) {
        float z0 =
                CSC_P00 + u * (CSC_P10 + u * (CSC_P20 + u * (CSC_P30 + u * (CSC_P40 + u * (CSC_P50 + u * CSC_P60)))));
        float z1 = CSC_P01 + u * (CSC_P11 + u * (CSC_P21 + u * (CSC_P31 + u * (CSC_P41 + u * CSC_P51))));
        float z2 = CSC_P02 + u * (CSC_P12 + u * (CSC_P22 + u * (CSC_P32 + u * CSC_P42)));
        float z3 = CSC_P03 + u * (CSC_P13 + u * (CSC_P23 + u * CSC_P33));
        float z4 = CSC_P04 + u * (CSC_P14 + u * CSC_P24);
        float z5 = CSC_P05 + u * CSC_P15;
        float z6 = CSC_P06;

        return z0 + v * (z1 + v * (z2 + v * (z3 + v * (z4 + v * (z5 + v * z6)))));
    }

    // proj.c astCSCrev.
    // The C works in float throughout; see the class documentation.
    static Status cscRev(PrjPrm prm, double x, double y, double[] out) {
        double[] f = {(float) (x * prm.w[1]), (float) (y * prm.w[1])};
        int face = revFace(f, true);
        if (face == OUT_OF_BOUNDS) {
            return badPoint(out);
        }
        float xf = (float) f[0];
        float yf = (float) f[1];

        float xx = xf * xf;
        float yy = yf * yf;

        // xf + xf*(1.0 - xx)*a mixes in double literals: it is evaluated in double precision and
        // rounded once.
        float a = cscSeries(xx, yy);
        a = (float) (xf + xf * (1.0 - xx) * a);

        float b = cscSeries(yy, xx);
        b = (float) (yf + yf * (1.0 - yy) * b);

        // a*a + b*b is single precision; adding 1.0 makes the rest double.
        double root = Math.sqrt((a * a + b * b) + 1.0);
        float l = 0.0f;
        float m = 0.0f;
        float n = 0.0f;
        if (face == 0) {
            n = (float) (1.0 / root);
            l = -b * n;
            m = a * n;
        } else if (face == 1) {
            l = (float) (1.0 / root);
            m = a * l;
            n = b * l;
        } else if (face == 2) {
            m = (float) (1.0 / root);
            l = -a * m;
            n = b * m;
        } else if (face == 3) {
            l = (float) (-1.0 / root);
            m = a * l;
            n = -b * l;
        } else if (face == 4) {
            m = (float) (-1.0 / root);
            l = -a * m;
            n = -b * m;
        } else if (face == 5) {
            n = (float) (-1.0 / root);
            l = -b * n;
            m = -a * n;
        }

        if (l == 0.0f && m == 0.0f) {
            out[0] = 0.0;
        } else {
            out[0] = Trig.atan2d(m, l);
        }
        out[1] = Trig.asind(n);
        return Status.OK;
    }

    // ---------------------------------------------------------------------
    // QSC: quadrilateralized spherical cube
    // ---------------------------------------------------------------------

    // proj.c astQSCset.
    static Status qscSet(PrjPrm prm) {
        quadcubeSet(prm);
        return Status.OK;
    }

    // proj.c astQSCfwd.
    static Status qscFwd(PrjPrm prm, double phi, double theta, double[] out) {
        if (Math.abs(theta) == 90.0) {
            out[0] = 0.0;
            out[1] = Zenithal.cCopysign(2.0 * prm.w[0], theta);
            return Status.OK;
        }

        double cthe = Trig.cosd(theta);
        double l = cthe * Trig.cosd(phi);
        double m = cthe * Trig.sind(phi);
        double n = Trig.sind(theta);

        int face = cubeFace(l, m, n);
        double rho = cubeRho(face, l, m, n);

        double rhu = 1.0 - rho;

        double xi;
        double eta;
        double x0;
        double y0;
        if (face == 0) {
            if (rhu < QSC_SMALL_ANGLE) {
                // Small angle formula.
                double t = (90.0 - theta) * Trig.D2R;
                rhu = t * t / 2.0;
            }
            xi = m;
            eta = -l;
            x0 = 0.0;
            y0 = 2.0;
        } else if (face == 1) {
            if (rhu < QSC_SMALL_ANGLE) {
                // Small angle formula.
                double t = theta * Trig.D2R;
                double p = phi % 360.0;
                if (p < -180.0) {
                    p += 360.0;
                }
                if (p > 180.0) {
                    p -= 360.0;
                }
                p *= Trig.D2R;
                rhu = (p * p + t * t) / 2.0;
            }
            xi = m;
            eta = n;
            x0 = 0.0;
            y0 = 0.0;
        } else if (face == 2) {
            if (rhu < QSC_SMALL_ANGLE) {
                // Small angle formula.
                double t = theta * Trig.D2R;
                double p = phi % 360.0;
                if (p < -180.0) {
                    p += 360.0;
                }
                p = (90.0 - p) * Trig.D2R;
                rhu = (p * p + t * t) / 2.0;
            }
            xi = -l;
            eta = n;
            x0 = 2.0;
            y0 = 0.0;
        } else if (face == 3) {
            if (rhu < QSC_SMALL_ANGLE) {
                // Small angle formula.
                double t = theta * Trig.D2R;
                double p = phi % 360.0;
                if (p < 0.0) {
                    p += 360.0;
                }
                p = (180.0 - p) * Trig.D2R;
                rhu = (p * p + t * t) / 2.0;
            }
            xi = -m;
            eta = n;
            x0 = 4.0;
            y0 = 0.0;
        } else if (face == 4) {
            if (rhu < QSC_SMALL_ANGLE) {
                // Small angle formula.
                double t = theta * Trig.D2R;
                double p = phi % 360.0;
                if (p > 180.0) {
                    p -= 360.0;
                }
                p = (90.0 + p) * Trig.D2R;
                rhu = (p * p + t * t) / 2.0;
            }
            xi = l;
            eta = n;
            x0 = 6.0;
            y0 = 0.0;
        } else {
            if (rhu < QSC_SMALL_ANGLE) {
                // Small angle formula.
                double t = (90.0 + theta) * Trig.D2R;
                rhu = t * t / 2.0;
            }
            xi = m;
            eta = l;
            x0 = 0.0;
            y0 = -2.0;
        }

        double xf = 0.0;
        double yf = 0.0;
        if (xi == 0.0 && eta == 0.0) {
            xf = 0.0;
            yf = 0.0;
        } else if (-xi >= Math.abs(eta)) {
            double omega = eta / xi;
            double tau = 1.0 + omega * omega;
            xf = -Math.sqrt(rhu / (1.0 - 1.0 / Math.sqrt(1.0 + tau)));
            yf = (xf / 15.0) * (Trig.atand(omega) - Trig.asind(omega / Math.sqrt(tau + tau)));
        } else if (xi >= Math.abs(eta)) {
            double omega = eta / xi;
            double tau = 1.0 + omega * omega;
            xf = Math.sqrt(rhu / (1.0 - 1.0 / Math.sqrt(1.0 + tau)));
            yf = (xf / 15.0) * (Trig.atand(omega) - Trig.asind(omega / Math.sqrt(tau + tau)));
        } else if (-eta > Math.abs(xi)) {
            double omega = xi / eta;
            double tau = 1.0 + omega * omega;
            yf = -Math.sqrt(rhu / (1.0 - 1.0 / Math.sqrt(1.0 + tau)));
            xf = (yf / 15.0) * (Trig.atand(omega) - Trig.asind(omega / Math.sqrt(tau + tau)));
        } else if (eta > Math.abs(xi)) {
            double omega = xi / eta;
            double tau = 1.0 + omega * omega;
            yf = Math.sqrt(rhu / (1.0 - 1.0 / Math.sqrt(1.0 + tau)));
            xf = (yf / 15.0) * (Trig.atand(omega) - Trig.asind(omega / Math.sqrt(tau + tau)));
        }

        if (Math.abs(xf) > 1.0) {
            if (Math.abs(xf) > 1.0 + QSC_FWD_TOL) {
                return badPoint(out);
            }
            xf = Zenithal.cCopysign(1.0, xf);
        }
        if (Math.abs(yf) > 1.0) {
            if (Math.abs(yf) > 1.0 + QSC_FWD_TOL) {
                return badPoint(out);
            }
            yf = Zenithal.cCopysign(1.0, yf);
        }

        out[0] = prm.w[0] * (xf + x0);
        out[1] = prm.w[0] * (yf + y0);
        return Status.OK;
    }

    // proj.c astQSCrev.
    static Status qscRev(PrjPrm prm, double x, double y, double[] out) {
        double[] f = {x * prm.w[1], y * prm.w[1]};
        int face = revFace(f, false);
        if (face == OUT_OF_BOUNDS) {
            return badPoint(out);
        }
        double xf = f[0];
        double yf = f[1];

        boolean direct = Math.abs(xf) > Math.abs(yf);
        double omega;
        double tau;
        double rho;
        double rhu;
        if (direct) {
            if (xf == 0.0) {
                omega = 0.0;
                tau = 1.0;
                rho = 1.0;
                rhu = 0.0;
            } else {
                double w = 15.0 * yf / xf;
                omega = Trig.sind(w) / (Trig.cosd(w) - SQRT2INV);
                tau = 1.0 + omega * omega;
                rhu = xf * xf * (1.0 - 1.0 / Math.sqrt(1.0 + tau));
                rho = 1.0 - rhu;
            }
        } else if (yf == 0.0) {
            omega = 0.0;
            tau = 1.0;
            rho = 1.0;
            rhu = 0.0;
        } else {
            double w = 15.0 * xf / yf;
            omega = Trig.sind(w) / (Trig.cosd(w) - SQRT2INV);
            tau = 1.0 + omega * omega;
            rhu = yf * yf * (1.0 - 1.0 / Math.sqrt(1.0 + tau));
            rho = 1.0 - rhu;
        }

        double w;
        if (rho < -1.0) {
            if (rho < -1.0 - QSC_REV_TOL) {
                return badPoint(out);
            }

            // C also sets rhu to 2.0 here; nothing reads it afterwards.
            rho = -1.0;
            w = 0.0;
        } else {
            w = Math.sqrt(rhu * (2.0 - rhu) / tau);
        }

        double l = 0.0;
        double m = 0.0;
        double n = 0.0;
        if (face == 0) {
            n = rho;
            if (direct) {
                m = w;
                if (xf < 0.0) {
                    m = -m;
                }
                l = -m * omega;
            } else {
                l = w;
                if (yf > 0.0) {
                    l = -l;
                }
                m = -l * omega;
            }
        } else if (face == 1) {
            l = rho;
            if (direct) {
                m = w;
                if (xf < 0.0) {
                    m = -m;
                }
                n = m * omega;
            } else {
                n = w;
                if (yf < 0.0) {
                    n = -n;
                }
                m = n * omega;
            }
        } else if (face == 2) {
            m = rho;
            if (direct) {
                l = w;
                if (xf > 0.0) {
                    l = -l;
                }
                n = -l * omega;
            } else {
                n = w;
                if (yf < 0.0) {
                    n = -n;
                }
                l = -n * omega;
            }
        } else if (face == 3) {
            l = -rho;
            if (direct) {
                m = w;
                if (xf > 0.0) {
                    m = -m;
                }
                n = -m * omega;
            } else {
                n = w;
                if (yf < 0.0) {
                    n = -n;
                }
                m = -n * omega;
            }
        } else if (face == 4) {
            m = -rho;
            if (direct) {
                l = w;
                if (xf < 0.0) {
                    l = -l;
                }
                n = l * omega;
            } else {
                n = w;
                if (yf < 0.0) {
                    n = -n;
                }
                l = n * omega;
            }
        } else if (face == 5) {
            n = -rho;
            if (direct) {
                m = w;
                if (xf < 0.0) {
                    m = -m;
                }
                l = m * omega;
            } else {
                l = w;
                if (yf < 0.0) {
                    l = -l;
                }
                m = l * omega;
            }
        }

        if (l == 0.0 && m == 0.0) {
            out[0] = 0.0;
        } else {
            out[0] = Trig.atan2d(m, l);
        }
        out[1] = Trig.asind(n);
        return Status.OK;
    }
}
