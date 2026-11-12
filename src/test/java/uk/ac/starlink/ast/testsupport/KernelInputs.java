package uk.ac.starlink.ast.testsupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Inputs for testing the numerical kernels, ported from Rust {@code src/test_inputs.rs}:
 * deterministic random values, realistic directions and angles, and the special values every
 * kernel must handle as the C it replaces does.
 */
public final class KernelInputs {

    /** AST's bad value, {@code -Double.MAX_VALUE}. */
    private static final double BAD = -Double.MAX_VALUE;

    private KernelInputs() {}

    /**
     * A small deterministic generator (SplitMix64), so test inputs are the same on every run and
     * platform.
     */
    public static final class SplitMix64 {
        private long state;

        /** A generator starting from {@code seed}. */
        public SplitMix64(long seed) {
            this.state = seed;
        }

        /** The next 64 random bits. */
        public long nextU64() {
            state += 0x9e3779b97f4a7c15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }

        /** Uniform in [0, 1). */
        public double nextF64() {
            return (nextU64() >>> 11) * 0x1.0p-53;
        }
    }

    /**
     * Values every kernel must treat as the C does: signed zeros, the extremes, subnormals,
     * AST__BAD, infinities, NaN, and the angles at quadrant boundaries.
     */
    public static double[] specials() {
        return new double[] {
            0.0,
            -0.0,
            1.0,
            -1.0,
            Double.MIN_NORMAL,
            -Double.MIN_NORMAL,
            Double.MIN_NORMAL / 1024.0,
            -Double.MIN_NORMAL / 1024.0,
            Double.MAX_VALUE,
            -Double.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Math.PI,
            -Math.PI,
            Math.PI / 2.0,
            -Math.PI / 2.0,
            BAD,
            Double.NaN
        };
    }

    /**
     * {@code n} random directions, unnormalised and at several magnitudes, then the six axis
     * directions with signed zeros in every combination.
     */
    public static List<double[]> unitVectors(int n) {
        SplitMix64 rng = new SplitMix64(0x5eed0c25L);
        List<double[]> out = new ArrayList<>(n + 64);
        double[] scales = {1.0, 1e-3, 1e3, 7.25};
        for (int i = 0; i < n; i++) {
            double lon = rng.nextF64() * 2.0 * Math.PI;
            double lat = (rng.nextF64() - 0.5) * Math.PI;
            double scale = scales[i % 4];
            out.add(new double[] {
                scale * Math.cos(lon) * Math.cos(lat), scale * Math.sin(lon) * Math.cos(lat), scale * Math.sin(lat)
            });
        }
        for (int axis = 0; axis < 3; axis++) {
            for (double one : new double[] {1.0, -1.0}) {
                for (int zeros = 0; zeros < 4; zeros++) {
                    double[] v = new double[3];
                    v[(axis + 1) % 3] = (zeros & 1) == 0 ? 0.0 : -0.0;
                    v[(axis + 2) % 3] = (zeros & 2) == 0 ? 0.0 : -0.0;
                    v[axis] = one;
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * {@code n} random (longitude, latitude) pairs in radians, then pairs whose magnitudes span
     * 2^-40 to 2^40 in every sign combination.
     */
    public static List<double[]> anglePairs(int n) {
        SplitMix64 rng = new SplitMix64(0x5eed0a2cL);
        List<double[]> out = new ArrayList<>(2 * n);
        for (int i = 0; i < n; i++) {
            double lon = rng.nextF64() * 2.0 * Math.PI;
            out.add(new double[] {lon, (rng.nextF64() - 0.5) * Math.PI});
        }
        for (int i = 0; i < n; i++) {
            double a = Math.pow(2.0, rng.nextF64() * 80.0 - 40.0);
            double b = Math.pow(2.0, rng.nextF64() * 80.0 - 40.0);
            double sa = i % 4 == 1 || i % 4 == 3 ? -1.0 : 1.0;
            double sb = i % 4 >= 2 ? -1.0 : 1.0;
            out.add(new double[] {sa * a, sb * b});
        }
        return out;
    }

    /**
     * The distance between two doubles in units in the last place, or {@code Long.MAX_VALUE} when
     * their signs differ or either is NaN.
     */
    public static long ulps(double a, double b) {
        long ab = Double.doubleToRawLongBits(a);
        long bb = Double.doubleToRawLongBits(b);
        if (ab == bb) {
            return 0;
        }
        if (Double.isNaN(a) || Double.isNaN(b) || (ab < 0) != (bb < 0)) {
            return Long.MAX_VALUE;
        }
        return Math.abs(ab - bb);
    }

    /**
     * Positions for the FK4 and FK5 conversions: {@link #anglePairs}, then both poles and the
     * latitudes one 1e-15 radian inside them, at several longitudes.
     */
    public static List<double[]> fkPositions(int n) {
        List<double[]> out = anglePairs(n);
        double halfPi = Math.PI / 2.0;
        for (double d : new double[] {halfPi, -halfPi, halfPi - 1e-15, -(halfPi - 1e-15)}) {
            for (double r : new double[] {0.0, 1.0, 3.0, 6.0}) {
                out.add(new double[] {r, d});
            }
        }
        return out;
    }

    /**
     * Besselian epochs (years) for the FK4 and FK5 conversions: far from J2000 and at the extremes,
     * AST__BAD and NaN, then random epochs from 1800 to 2200.
     */
    public static double[] fkEpochs() {
        SplitMix64 rng = new SplitMix64(0xf45e90L);
        double[] out = {1000.0, 1900.0, 1950.0, 2000.0, 2100.0, 3000.0, BAD, Double.NaN, 0, 0, 0, 0};
        for (int i = 8; i < 12; i++) {
            out[i] = 1800.0 + 400.0 * rng.nextF64();
        }
        return out;
    }
}
