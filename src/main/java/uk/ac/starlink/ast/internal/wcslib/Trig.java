package uk.ac.starlink.ast.internal.wcslib;

/**
 * The trigonometry the projections use, with angles in degrees, evaluated as wcslib's {@code
 * wcstrig.c} does: exact results at multiples of 45 and 90 degrees, and arguments of the inverse
 * functions within {@link #WCSTRIG_TOL} beyond the ends of their range taken as the ends.
 *
 * <p>Ported from Rust {@code wcslib/trig.rs} ({@code Libm}); the elementary functions are {@link
 * Math}'s, as C's are the platform libm's.
 */
// MODERN-JAVA(9): Rust also has branchless kernel versions of sind, cosd, atand and atan2d
// (Kernel, with the predicates sincosd_covers, atan2d_covers and atand_covers saying where they
// are valid) for the projections' vectorised slice loops, built on its vmath kernels, which need
// Math.fma; with the Vector API they would replace these in WcsMap's whole-array loops.
public final class Trig {

    /** Degrees to radians: {@code PI/180.0} of {@code wcsmath.h}. */
    public static final double D2R = Math.PI / 180.0;

    /** Radians to degrees: {@code 180.0/PI} of {@code wcsmath.h}. */
    public static final double R2D = 180.0 / Math.PI;

    /**
     * How close to the end of its range an argument of {@link #acosd} or {@link #asind} may be,
     * beyond the range, and still be taken as the end ({@code WCSTRIG_TOL} of {@code wcstrig.h}).
     */
    public static final double WCSTRIG_TOL = 1e-10;

    private Trig() {}

    /**
     * The quadrant {@code abs((int)floor(x)) % 4} of C, for {@code x} already floored. C's
     * conversion to {@code int} is undefined outside the range of an {@code int}, which a multiple
     * of 90 degrees can reach. The result here does not depend on the platform: the conversion
     * saturates, as arm64's does, and {@code abs} leaves {@code INT_MIN} unchanged, so {@code x}
     * beyond 2^31 gives {@code INT_MAX % 4} = 3 and {@code x} below -2^31 gives {@code INT_MIN % 4}
     * = 0. Java's conversion and {@link Math#abs(int)} behave exactly so.
     */
    static int quadrant(double floored) {
        return Math.abs((int) floored) % 4;
    }

    /**
     * Sine of an angle in degrees (wcstrig.c {@code astSind}).
     *
     * @param angle the angle in degrees
     * @return its sine
     */
    public static double sind(double angle) {
        if (angle % 90.0 == 0.0) {
            switch (quadrant(Math.floor(angle / 90.0 - 0.5))) {
                case 0:
                    return 1.0;
                case 1:
                    return 0.0;
                case 2:
                    return -1.0;
                case 3:
                    return 0.0;
                default:
                    break;
            }
        }
        return Math.sin(angle * D2R);
    }

    /**
     * Cosine of an angle in degrees (wcstrig.c {@code astCosd}).
     *
     * @param angle the angle in degrees
     * @return its cosine
     */
    public static double cosd(double angle) {
        if (angle % 90.0 == 0.0) {
            switch (quadrant(Math.floor(angle / 90.0 + 0.5))) {
                case 0:
                    return 1.0;
                case 1:
                    return 0.0;
                case 2:
                    return -1.0;
                case 3:
                    return 0.0;
                default:
                    break;
            }
        }
        return Math.cos(angle * D2R);
    }

    /**
     * Tangent of an angle in degrees (wcstrig.c {@code astTand}).
     *
     * @param angle the angle in degrees
     * @return its tangent
     */
    public static double tand(double angle) {
        double resid = angle % 360.0;
        if (resid == 0.0 || Math.abs(resid) == 180.0) {
            return 0.0;
        } else if (resid == 45.0 || resid == 225.0) {
            return 1.0;
        } else if (resid == -135.0 || resid == -315.0) {
            return -1.0;
        }
        return Math.tan(angle * D2R);
    }

    /**
     * Arc sine in degrees (wcstrig.c {@code astASind}).
     *
     * @param v the sine
     * @return the angle in degrees
     */
    public static double asind(double v) {
        if (v <= -1.0) {
            if (v + 1.0 > -WCSTRIG_TOL) {
                return -90.0;
            }
        } else if (v == 0.0) {
            return 0.0;
        } else if (v >= 1.0 && v - 1.0 < WCSTRIG_TOL) {
            return 90.0;
        }
        return Math.asin(v) * R2D;
    }

    /**
     * Arc cosine in degrees (wcstrig.c {@code astACosd}).
     *
     * @param v the cosine
     * @return the angle in degrees
     */
    public static double acosd(double v) {
        if (v >= 1.0) {
            if (v - 1.0 < WCSTRIG_TOL) {
                return 0.0;
            }
        } else if (v == 0.0) {
            return 90.0;
        } else if (v <= -1.0 && v + 1.0 > -WCSTRIG_TOL) {
            return 180.0;
        }
        return Math.acos(v) * R2D;
    }

    /**
     * Arc tangent in degrees (wcstrig.c {@code astATand}).
     *
     * @param v the tangent
     * @return the angle in degrees
     */
    public static double atand(double v) {
        if (v == -1.0) {
            return -45.0;
        } else if (v == 0.0) {
            return 0.0;
        } else if (v == 1.0) {
            return 45.0;
        }
        return Math.atan(v) * R2D;
    }

    /**
     * Arc tangent of {@code y / x} in degrees, in the quadrant of ({@code x}, {@code y})
     * (wcstrig.c {@code astATan2d}).
     *
     * @param y the ordinate
     * @param x the abscissa
     * @return the angle in degrees
     */
    public static double atan2d(double y, double x) {
        if (y == 0.0) {
            if (x >= 0.0) {
                return 0.0;
            } else if (x < 0.0) {
                return 180.0;
            }
        } else if (x == 0.0) {
            if (y > 0.0) {
                return 90.0;
            } else if (y < 0.0) {
                return -90.0;
            }
        }
        return Math.atan2(y, x) * R2D;
    }
}
