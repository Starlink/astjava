package uk.ac.starlink.ast;

/**
 * The 1-dimensional kernels of resampling and rebinning ({@code Sinc}, {@code SincCos}, {@code
 * SincGauss}, {@code SincSinc}, {@code Somb}, {@code SombCos} and {@code Gauss}), the Bessel
 * function {@code Somb} and {@code SombCos} use, and the set-up {@code ResampleSection} and {@code
 * RebinSection} share: the kernel for a scheme, the number of neighbouring pixels it reaches on
 * each side, and the parameters it is given.
 *
 * <p>Each kernel takes the offset of a contributing pixel from the interpolation point and the
 * parameters {@link #setup} derives. C computes pi as {@code acos(-1.0)} and half pi as {@code 0.5
 * * pi}, which are {@link Math#PI} and {@code Math.PI / 2} exactly.
 *
 * <p>Ported from Rust {@code mappings/resample/kernels.rs} and C {@code mapping.c}.
 */
final class ResampleKernels {

    private static final double HALF_PI = 0.5 * Math.PI;

    private ResampleKernels() {}

    /** A 1-dimensional kernel: its value at an offset, given the parameters {@link #setup} derives. */
    @FunctionalInterface
    interface Kernel {

        /**
         * The kernel's value.
         *
         * @param offset the offset of a pixel from the interpolation point, in pixels
         * @param params the parameters {@link #setup} derives for the kernel
         */
        double value(double offset, double[] params);
    }

    /** A kernel scheme as set up: the kernel, its reach and its parameters. */
    static final class Setup {

        /** The kernel. */
        final Kernel kernel;

        /** The number of neighbouring pixels on each side of the point that contribute. */
        final int neighb;

        /** The parameters the kernel is given. */
        final double[] params;

        Setup(Kernel kernel, int neighb, double[] params) {
            this.kernel = kernel;
            this.neighb = neighb;
            this.params = params;
        }
    }

    /** C's {@code MaxD}. */
    private static double maxD(double a, double b) {
        return a > b ? a : b;
    }

    /** C's {@code (int) floor(v + 0.5)}. */
    private static int nearestInt(double v) {
        return (int) Math.floor(v + 0.5);
    }

    /**
     * The set-up of {@code ResampleSection} and {@code RebinSection} for the kernel scheme {@code
     * scheme} (one of {@code AST__SINC}, {@code AST__SINCSINC}, {@code AST__SINCCOS}, {@code
     * AST__SINCGAUSS}, {@code AST__GAUSS}, {@code AST__SOMB} and {@code AST__SOMBCOS}) from the
     * caller's {@code params}. {@code params[0]}, rounded, is the number of neighbouring pixels;
     * where it is zero or less a default applies.
     *
     * @throws AstException with status {@code AST__INTER} for any other scheme
     */
    static Setup setup(int scheme, double[] params) {
        switch (scheme) {
            case Mapping.AST__SINC:
                return new Setup(ResampleKernels::sinc, defaultTwo(params), new double[0]);
            case Mapping.AST__SOMB:
                return new Setup(ResampleKernels::somb, defaultTwo(params), new double[0]);
            case Mapping.AST__SINCCOS:
                return new Setup(ResampleKernels::sincCos, toZero(params), kZero(params));
            case Mapping.AST__SINCSINC:
                return new Setup(ResampleKernels::sincSinc, toZero(params), kZero(params));
            case Mapping.AST__SOMBCOS:
                return new Setup(ResampleKernels::sombCos, toZero(params), kZero(params));
            case Mapping.AST__SINCGAUSS:
                return gaussian(ResampleKernels::sincGauss, params);
            case Mapping.AST__GAUSS:
                return gaussian(ResampleKernels::gauss, params);
            default:
                throw Ast.err(AstException.AST__INTER, "Unexpected 1-d kernel scheme (" + scheme + ").");
        }
    }

    /** The reach of {@code Sinc} and {@code Somb}: {@code params[0]}, rounded, or two by default. */
    private static int defaultTwo(double[] params) {
        int n = nearestInt(params[0]);
        return n <= 0 ? 2 : Math.max(1, n);
    }

    /**
     * The reach of a kernel that cuts off at the first zero of a factor whose offset is {@code
     * params[1]}: at most that many pixels, rounded up, and that many by default.
     */
    private static int toZero(double[] params) {
        int n = nearestInt(params[0]);
        if (n <= 0) {
            n = Integer.MAX_VALUE;
        }
        return Math.min(n, (int) Math.ceil(maxD(1.0, params[1])));
    }

    /** The factor {@code k} of a kernel that cuts off at the first zero of its second factor. */
    private static double[] kZero(double[] params) {
        return new double[] {0.5 / maxD(1.0, params[1])};
    }

    /**
     * The set-up of the Gaussian kernels, whose full width at half maximum, at least 0.1, is
     * {@code params[1]}: they reach out to 1% of the peak by default.
     */
    private static Setup gaussian(Kernel kernel, double[] params) {
        double fwhm = maxD(0.1, params[1]);
        double k = 4.0 * Math.log(2.0) / (fwhm * fwhm);
        int n = nearestInt(params[0]);
        if (n <= 0) {
            n = (int) Math.ceil(Math.sqrt(-Math.log(0.01) / k));
        }
        return new Setup(kernel, n, new double[] {k});
    }

    /** C's {@code Sinc}: {@code sinc(pi * offset)}. */
    static double sinc(double offset, double[] params) {
        double x = offset * Math.PI;
        return x != 0.0 ? Math.sin(x) / x : 1.0;
    }

    /**
     * C's {@code SincCos}: {@code sinc(pi * offset) * cos(k * pi * offset)}, out to the first zero
     * of the cosine, with {@code k} in {@code params[0]}.
     */
    static double sincCos(double offset, double[] params) {
        double x = Math.PI * Math.abs(offset);
        double xk = x * params[0];
        if (xk < HALF_PI) {
            return (x != 0.0 ? Math.sin(x) / x : 1.0) * Math.cos(xk);
        }
        return 0.0;
    }

    /**
     * C's {@code SincGauss}: {@code sinc(pi * offset) * exp(-k * offset * offset)}, with {@code k}
     * in {@code params[0]}.
     */
    static double sincGauss(double offset, double[] params) {
        double x = Math.PI * offset;
        return (x != 0.0 ? Math.sin(x) / x : 1.0) * Math.exp(-params[0] * offset * offset);
    }

    /**
     * C's {@code SincSinc}: {@code sinc(pi * offset) * sinc(k * pi * offset)}, out to the first
     * zero of the second factor, with {@code k} in {@code params[0]}.
     */
    static double sincSinc(double offset, double[] params) {
        double x = Math.PI * Math.abs(offset);
        double xk = x * params[0];
        if (xk < HALF_PI) {
            return (x != 0.0 ? Math.sin(x) / x : 1.0) * (xk != 0.0 ? Math.sin(xk) / xk : 1.0);
        }
        return 0.0;
    }

    /** C's {@code Somb}: {@code somb(pi * offset)}, that is {@code 2 * J1(x) / x}. */
    static double somb(double offset, double[] params) {
        double x = offset * Math.PI;
        return x != 0.0 ? 2.0 * j1Bessel(x) / x : 1.0;
    }

    /**
     * C's {@code SombCos}: {@code somb(pi * offset) * cos(k * pi * offset)}, out to the first zero
     * of the cosine, with {@code k} in {@code params[0]}. C forms the first factor as {@code J1(x)
     * / x}, without the factor of 2 {@code Somb} has, but still takes it as 1 at zero offset,
     * where {@code J1(x) / x} tends to a half; this kernel does the same.
     */
    static double sombCos(double offset, double[] params) {
        double x = Math.PI * Math.abs(offset);
        double xk = x * params[0];
        if (xk < HALF_PI) {
            return (x != 0.0 ? j1Bessel(x) / x : 1.0) * Math.cos(xk);
        }
        return 0.0;
    }

    /** C's {@code Gauss}: {@code exp(-k * offset * offset)}, with {@code k} in {@code params[0]}. */
    static double gauss(double offset, double[] params) {
        return Math.exp(-params[0] * offset * offset);
    }

    /**
     * C's {@code J1Bessel}: the Bessel function of the first kind of order one, by C's rational and
     * asymptotic approximations, whose truncated constants (such as 2/pi and 3pi/4) are part of
     * the approximation.
     */
    static double j1Bessel(double x) {
        final double p1 = 1.0;
        final double p2 = 0.183105E-2;
        final double p3 = -0.3516396496E-4;
        final double p4 = 0.2457520174E-5;
        final double p5 = -0.240337019E-6;

        final double q1 = 0.04687499995;
        final double q2 = -0.2002690873E-3;
        final double q3 = 0.8449199096E-5;
        final double q4 = -0.88228987E-6;
        final double q5 = 0.105787412E-6;

        final double r1 = 72362614232.0;
        final double r2 = -7895059235.0;
        final double r3 = 242396853.1;
        final double r4 = -2972611.439;
        final double r5 = 15704.48260;
        final double r6 = -30.16036606;

        final double s1 = 144725228442.0;
        final double s2 = 2300535178.0;
        final double s3 = 18583304.74;
        final double s4 = 99447.43394;
        final double s5 = 376.9991397;
        final double s6 = 1.0;

        double ax = Math.abs(x);
        if (ax < 8.0) {
            double y = x * x;
            return x
                    * (r1 + y * (r2 + y * (r3 + y * (r4 + y * (r5 + y * r6)))))
                    / (s1 + y * (s2 + y * (s3 + y * (s4 + y * (s5 + y * s6)))));
        }
        int s = x >= 0.0 ? 1 : -1;
        double z = 8.0 / ax;
        double y = z * z;
        double xx = ax - 2.356194491;
        return Math.sqrt(0.636619772 / ax)
                * (Math.cos(xx) * (p1 + y * (p2 + y * (p3 + y * (p4 + y * p5))))
                        - z * Math.sin(xx) * (q1 + y * (q2 + y * (q3 + y * (q4 + y * q5)))))
                * s;
    }
}
