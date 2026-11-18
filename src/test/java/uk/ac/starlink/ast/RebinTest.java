package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_rebin.rs}, after C's {@code ast_tester/testrebin.c}:
 * rebinning on known data, tests 1 to 9, each run for the {@code D}, {@code F} and {@code I}
 * types and the {@code SINC}, {@code NEAREST}, {@code LINEAR}, {@code SINCSINC}, {@code SINCCOS}
 * and {@code SINCGAUSS} spreading schemes, through {@code rebinD}, {@code rebinF} and {@code
 * rebinI} with bad pixels used and both variance arrays given, so that variances are rebinned (C's
 * {@code AST__USEBAD + AST__USEVAR}), a {@code maxpix} of 100 and a {@code wlim} of 0.
 *
 * <p>C's three-phase callback over void pointers becomes the {@link RebinCase} interface, whose
 * data are held as doubles and converted to the type for each call; every input element is
 * written before each call, as in C.
 */
class RebinTest {

    /** The pixel types the tests run for: C's {@code D}, {@code F} and {@code I}. */
    private enum Type {
        DOUBLE,
        FLOAT,
        INT;

        /** The largest value of the type. */
        double maxval() {
            switch (this) {
                case DOUBLE:
                    return Double.MAX_VALUE;
                case FLOAT:
                    return Float.MAX_VALUE;
                case INT:
                    return Integer.MAX_VALUE;
                default:
                    throw new IllegalStateException(name());
            }
        }

        /** The bad value passed to the rebinning and checked for in its output. */
        double bad() {
            switch (this) {
                case DOUBLE:
                    return AstObject.AST__BAD;
                case FLOAT:
                    return (float) AstObject.AST__BAD;
                case INT:
                    return Integer.MIN_VALUE;
                default:
                    throw new IllegalStateException(name());
            }
        }

        /** {@code v} converted to the type, as a double. */
        double of(double v) {
            switch (this) {
                case DOUBLE:
                    return v;
                case FLOAT:
                    return (float) v;
                case INT:
                    return (int) v;
                default:
                    throw new IllegalStateException(name());
            }
        }

        boolean floating() {
            return this != INT;
        }

        /** C's {@code equal_gen}: approximate for a floating type, exact otherwise. */
        boolean equal(double a, double b) {
            switch (this) {
                case DOUBLE:
                    return equald(a, b);
                case FLOAT:
                    return equalf((float) a, (float) b);
                case INT:
                    return a == b;
                default:
                    throw new IllegalStateException(name());
            }
        }
    }

    /** The spreading schemes every test is run with, in C's order. */
    private static final int[] SPREADS = {
        Mapping.AST__SINC,
        Mapping.AST__NEAREST,
        Mapping.AST__LINEAR,
        Mapping.AST__SINCSINC,
        Mapping.AST__SINCCOS,
        Mapping.AST__SINCGAUSS
    };

    /** C's {@code equald}: a relative tolerance of about 1e-9. */
    private static boolean equald(double a, double b) {
        if (a != 0.0 && b != 0.0) {
            return Math.abs(a - b) <= 1.0e9 * Math.abs(a + b) * Math.ulp(1.0);
        }
        return Math.abs(a + b) <= 1.0e-11;
    }

    /** C's {@code equalf}: a relative tolerance of 50 {@code FLT_EPSILON}. */
    private static boolean equalf(float a, float b) {
        if (a != 0.0f && b != 0.0f) {
            return Math.abs(a - b) <= 50.0f * Math.abs(a + b) * Math.ulp(1.0f);
        }
        return Math.abs(a + b) <= 1.0e-11f;
    }

    /** The grids, Mapping and options of one case (C's phase 0). */
    private static final class Setup {
        final int[] lbndIn;
        final int[] ubndIn;
        final int[] lbndOut;
        final int[] ubndOut;
        final int[] lbnd;
        final int[] ubnd;
        final Mapping map;
        final double[] params;
        final double tol;

        Setup(
                int[] lbndIn,
                int[] ubndIn,
                int[] lbndOut,
                int[] ubndOut,
                int[] lbnd,
                int[] ubnd,
                Mapping map,
                double[] params,
                double tol) {
            this.lbndIn = lbndIn;
            this.ubndIn = ubndIn;
            this.lbndOut = lbndOut;
            this.ubndOut = ubndOut;
            this.lbnd = lbnd;
            this.ubnd = ubnd;
            this.map = map;
            this.params = params;
            this.tol = tol;
        }
    }

    /** The input and output arrays of one case after rebinning, as doubles. */
    private static final class Data {
        final Type type;
        final double[] in;
        final double[] inVar;
        final double[] out;
        final double[] outVar;

        Data(Type type, double[] in, double[] inVar, double[] out, double[] outVar) {
            this.type = type;
            this.in = in;
            this.inVar = inVar;
            this.out = out;
            this.outVar = outVar;
        }

        boolean isBad(double v) {
            return v == type.bad();
        }
    }

    /** One of C's test functions, split into its three phases. */
    private interface RebinCase {
        /** The limit passed to {@link #kfac}. */
        double klimit();

        /** Phase 0: the grids, Mapping and options for {@code spread}. */
        Setup setup(int spread);

        /** Phase 1: fills the input data and variances. */
        void fill(Setup s, Type type, double kfac, double[] in, double[] inVar);

        /** Phase 2: validates the output, returning a failure message or null. */
        String check(Setup s, int spread, double kfac, Data d);
    }

    /** C's {@code get_kfac}: a data scale of {@code limit}, reduced for a type too small for twenty times it. */
    private static double kfac(Type type, double limit) {
        return Math.min(type.maxval() / 20.0, limit);
    }

    private static int nel(int[] lbnd, int[] ubnd, int ndim) {
        int n = 1;
        for (int i = 0; i < ndim; i++) {
            n *= ubnd[i] - lbnd[i] + 1;
        }
        return n;
    }

    /** jniast's Spreader for the scheme {@code spread} with the parameters {@code params}. */
    private static Mapping.Spreader spreader(int spread, double[] params) {
        int npix = (int) params[0];
        switch (spread) {
            case Mapping.AST__NEAREST:
                return Mapping.NEAREST_SPREADER;
            case Mapping.AST__LINEAR:
                return Mapping.LINEAR_SPREADER;
            case Mapping.AST__SINC:
                return Mapping.Spreader.sinc(npix);
            case Mapping.AST__SINCSINC:
                return Mapping.Spreader.sincSinc(npix, params[1]);
            case Mapping.AST__SINCCOS:
                return Mapping.Spreader.sincCos(npix, params[1]);
            case Mapping.AST__SINCGAUSS:
                return Mapping.Spreader.sincGauss(npix, params[1]);
            case Mapping.AST__GAUSS:
                return Mapping.Spreader.gauss(npix, params[1]);
            default:
                throw new IllegalArgumentException("no scheme " + spread);
        }
    }

    /** Rebins through the method for {@code type}, returning the output values and variances. */
    private static double[][] rebin(Type type, Setup s, int spread, double[] in, double[] inVar) {
        int ndimIn = s.lbndIn.length;
        int ndimOut = s.lbndOut.length;
        int nout = nel(s.lbndOut, s.ubndOut, ndimOut);
        Mapping.Spreader spreader = spreader(spread, s.params);
        double[] out = new double[nout];
        double[] outVar = new double[nout];
        switch (type) {
            case DOUBLE:
                s.map.rebinD(
                        0.0,
                        ndimIn,
                        s.lbndIn,
                        s.ubndIn,
                        in,
                        inVar,
                        spreader,
                        true,
                        s.tol,
                        100,
                        type.bad(),
                        ndimOut,
                        s.lbndOut,
                        s.ubndOut,
                        s.lbnd,
                        s.ubnd,
                        out,
                        outVar);
                break;
            case FLOAT: {
                float[] inF = new float[in.length];
                float[] inVarF = new float[in.length];
                for (int k = 0; k < in.length; k++) {
                    inF[k] = (float) in[k];
                    inVarF[k] = (float) inVar[k];
                }
                float[] outF = new float[nout];
                float[] outVarF = new float[nout];
                s.map.rebinF(
                        0.0,
                        ndimIn,
                        s.lbndIn,
                        s.ubndIn,
                        inF,
                        inVarF,
                        spreader,
                        true,
                        s.tol,
                        100,
                        (float) type.bad(),
                        ndimOut,
                        s.lbndOut,
                        s.ubndOut,
                        s.lbnd,
                        s.ubnd,
                        outF,
                        outVarF);
                for (int k = 0; k < nout; k++) {
                    out[k] = outF[k];
                    outVar[k] = outVarF[k];
                }
                break;
            }
            case INT: {
                int[] inI = new int[in.length];
                int[] inVarI = new int[in.length];
                for (int k = 0; k < in.length; k++) {
                    inI[k] = (int) in[k];
                    inVarI[k] = (int) inVar[k];
                }
                int[] outI = new int[nout];
                int[] outVarI = new int[nout];
                s.map.rebinI(
                        0.0,
                        ndimIn,
                        s.lbndIn,
                        s.ubndIn,
                        inI,
                        inVarI,
                        spreader,
                        true,
                        s.tol,
                        100,
                        (int) type.bad(),
                        ndimOut,
                        s.lbndOut,
                        s.ubndOut,
                        s.lbnd,
                        s.ubnd,
                        outI,
                        outVarI);
                for (int k = 0; k < nout; k++) {
                    out[k] = outI[k];
                    outVar[k] = outVarI[k];
                }
                break;
            }
            default:
                throw new IllegalStateException(type.name());
        }
        return new double[][] {out, outVar};
    }

    /**
     * C's {@code tester}: runs {@code t} for every type and spreading scheme, returning the first
     * failure, or null if there is none.
     */
    private static String tester(RebinCase t) {
        for (Type type : Type.values()) {
            for (int spread : SPREADS) {
                Setup s = t.setup(spread);
                double kfac = kfac(type, t.klimit());
                int nelIn = nel(s.lbndIn, s.ubndIn, s.lbndIn.length);
                double[] in = new double[nelIn];
                double[] inVar = new double[nelIn];
                t.fill(s, type, kfac, in, inVar);
                double[][] result = rebin(type, s, spread, in, inVar);
                String msg = t.check(s, spread, kfac, new Data(type, in, inVar, result[0], result[1]));
                if (msg != null) {
                    return msg + " (spread " + spread + ", type " + type + ")";
                }
            }
        }
        return null;
    }

    /** The sum of the good values of {@code out}. */
    private static double goodSum(Data d) {
        double sum = 0.0;
        for (double v : d.out) {
            if (!d.isBad(v)) {
                sum += v;
            }
        }
        return sum;
    }

    /** Whether {@code sum} matches {@code kt}: to {@code equald} for a floating type, within {@code itol} otherwise. */
    private static boolean sumMatches(Type type, double sum, double kt, double itol) {
        return type.floating() ? equald(sum, kt) : Math.abs(sum - kt) < itol;
    }

    /** Fills the input with {@code k * kfac} and its variance with {@code k}, for element number {@code k} from 1. */
    private static void fillRamp(Type type, double kfac, double[] in, double[] inVar) {
        for (int k = 0; k < in.length; k++) {
            double k1 = k + 1.0;
            in[k] = type.of(k1 * kfac);
            inVar[k] = type.of(k1);
        }
    }

    /** Fills the 1-D input with {@code i * kfac} and its variance with {@code i}, for pixel {@code i}. */
    private static void fillPixelRamp(Setup s, Type type, double kfac, double[] in, double[] inVar) {
        for (int i = s.lbndIn[0]; i <= s.ubndIn[0]; i++) {
            int idx = i - s.lbndIn[0];
            in[idx] = type.of(i * kfac);
            inVar[idx] = type.of(i);
        }
    }

    /** Checks that output element {@code k} and its variance are zero. */
    private static String checkZero(String test, int label, Data d, int k) {
        if (!equald(d.out[k], 0.0)) {
            return test + " " + label + ": " + d.out[k] + " != 0";
        }
        if (!equald(d.outVar[k], 0.0)) {
            return test + " " + label + ": variance " + d.outVar[k] + " != 0";
        }
        return null;
    }

    /** Checks that output element {@code k} and its variance equal input element {@code kin} and its variance. */
    private static String checkCopy(String test, int label, Data d, int k, int kin) {
        if (!d.type.equal(d.out[k], d.in[kin])) {
            return test + " " + label + ": data " + d.out[k] + " != " + d.in[kin];
        }
        if (!d.type.equal(d.outVar[k], d.inVar[kin])) {
            return test + " " + label + ": variance " + d.outVar[k] + " != " + d.inVar[kin];
        }
        return null;
    }

    /** Checks that the only nonzero good output value is a single {@code kt}; element numbers are offset by {@code label0}. */
    private static String checkSingleSpike(String test, Data d, double kt, int label0) {
        int nz = 0;
        for (int k = 0; k < d.out.length; k++) {
            double v = d.out[k];
            if (v != 0.0 && !d.isBad(v)) {
                int label = k + label0;
                if (nz == 0) {
                    nz++;
                    if (v != kt) {
                        return test + " " + label + ": " + v + " " + kt;
                    }
                } else {
                    return test + " " + label + ": " + v;
                }
            }
        }
        return null;
    }

    private static final double[] P22 = {2.0, 2.0};

    /** TEST 1: 1-D UnitMap. */
    private static final class Case1 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            // C keeps a branch for AST__GAUSS although the schemes it runs do not include it.
            double[] params = spread == Mapping.AST__GAUSS ? new double[] {2.0, 2.0} : new double[] {2.0, 0.5};
            return new Setup(
                    new int[] {10},
                    new int[] {19},
                    new int[] {12},
                    new int[] {20},
                    new int[] {11},
                    new int[] {17},
                    new UnitMap(1),
                    params,
                    0.1);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            fillPixelRamp(s, type, kfac, in, inVar);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            int lo = s.lbndOut[0];
            int ub = s.ubnd[0];
            int uo = s.ubndOut[0];
            for (int i = lo; i <= ub; i++) {
                boolean ignore = spread == Mapping.AST__GAUSS && (i <= lo + 1 || i >= ub - 1);
                if (!ignore) {
                    String m = checkCopy("TEST1", i, d, i - lo, i - s.lbndIn[0]);
                    if (m != null) {
                        return m;
                    }
                }
            }
            for (int i = ub + 1; i <= uo; i++) {
                String m = checkZero("TEST1", i, d, i - lo);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** TEST 2: 2-D UnitMap. */
    private static final class Case2 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {-1, 3},
                    new int[] {2, 6},
                    new int[] {0, 2},
                    new int[] {3, 5},
                    new int[] {-1, 3},
                    new int[] {1, 6},
                    new UnitMap(2),
                    P22,
                    0.0);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            fillRamp(type, kfac, in, inVar);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            for (int k = 0; k < d.out.length; k++) {
                int k1 = k + 1;
                String m = k1 <= 4 || k1 % 4 == 0 || k1 % 4 == 3
                        ? checkZero("TEST2", k1, d, k)
                        : checkCopy("TEST2", k1, d, k, k - 3);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** TEST 3: 3-D UnitMap. */
    private static final class Case3 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {-1, 3, -1},
                    new int[] {2, 6, 1},
                    new int[] {0, 2, 0},
                    new int[] {3, 5, 2},
                    new int[] {-1, 3, -1},
                    new int[] {1, 6, 1},
                    new UnitMap(3),
                    P22,
                    0.0);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            fillRamp(type, kfac, in, inVar);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            int nxy = nel(s.lbndOut, s.ubndOut, 2);
            for (int k = 0; k < d.out.length; k++) {
                int k1 = k + 1;
                int k2 = (k1 - 1) % 16 + 1;
                int l = s.lbndOut[2] + k / nxy;
                String m = k2 <= 4 || k2 % 4 == 0 || k2 % 4 == 3 || l == 2
                        ? checkZero("TEST3", k1, d, k)
                        : checkCopy("TEST3", k1, d, k, k + 13);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** TEST 4: 1-D ShiftMap by 3. */
    private static final class Case4 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {10},
                    new int[] {19},
                    new int[] {12},
                    new int[] {20},
                    new int[] {11},
                    new int[] {17},
                    new ShiftMap(new double[] {3.0}),
                    P22,
                    0.1);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            fillPixelRamp(s, type, kfac, in, inVar);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            int lo = s.lbndOut[0];
            for (int i = lo; i <= s.lbnd[0] + 2; i++) {
                String m = checkZero("TEST4", i, d, i - lo);
                if (m != null) {
                    return m;
                }
            }
            for (int i = s.lbnd[0] + 3; i <= s.ubndOut[0]; i++) {
                String m = checkCopy("TEST4", i, d, i - lo, i - 3 - s.lbndIn[0]);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** TEST 5: 2-D ShiftMap by (3, -1). */
    private static final class Case5 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {-1, 3},
                    new int[] {2, 6},
                    new int[] {0, 2},
                    new int[] {3, 5},
                    new int[] {-1, 3},
                    new int[] {1, 6},
                    new ShiftMap(new double[] {3.0, -1.0}),
                    P22,
                    0.0);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            fillRamp(type, kfac, in, inVar);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            for (int k = 0; k < d.out.length; k++) {
                int k1 = k + 1;
                String m = (k1 - 1) % 4 < 2 ? checkZero("TEST5", k1, d, k) : checkCopy("TEST5", k1, d, k, k - 2);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** TEST 6: 3-D ShiftMap by (3, -1, 1). */
    private static final class Case6 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {-1, 3, -1},
                    new int[] {2, 6, 1},
                    new int[] {0, 2, 0},
                    new int[] {3, 5, 2},
                    new int[] {-1, 3, -1},
                    new int[] {1, 6, 1},
                    new ShiftMap(new double[] {3.0, -1.0, 1.0}),
                    P22,
                    0.0);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            fillRamp(type, kfac, in, inVar);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            for (int k = 0; k < d.out.length; k++) {
                int k1 = k + 1;
                int k2 = (k1 - 1) % 16 + 1;
                String m = (k2 - 1) % 4 < 2 ? checkZero("TEST6", k1, d, k) : checkCopy("TEST6", k1, d, k, k - 2);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** TEST 7: 1-D ShiftMap by 1.5 of a single value. */
    private static final class Case7 implements RebinCase {
        @Override
        public double klimit() {
            return 1000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {10},
                    new int[] {19},
                    new int[] {12},
                    new int[] {20},
                    new int[] {11},
                    new int[] {17},
                    new ShiftMap(new double[] {1.5}),
                    P22,
                    0.1);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            for (int k = 0; k < in.length; k++) {
                in[k] = 0.0;
                inVar[k] = type.of(kfac);
            }
            in[14 - s.lbndIn[0]] = type.of(kfac);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            double sum = goodSum(d);
            double kt = kfac;
            if (!sumMatches(d.type, sum, kt, 3.0)) {
                return "TEST7 Data sum is " + sum + " should be " + kt;
            }
            int lo = s.lbndOut[0];
            if (spread == Mapping.AST__NEAREST) {
                return checkSingleSpike("TEST7", d, kt, lo);
            }
            // Input pixel 14 is centred at 13.5 and lands at 15.0, the boundary of output pixels
            // 15 and 16, so the output is symmetric about it.
            for (int i = 0; i <= 3; i++) {
                int idx1 = 15 - i - lo;
                int idx2 = 16 + i - lo;
                if (!d.type.equal(d.out[idx1], d.out[idx2])) {
                    return "TEST7 " + (15 - i) + " (" + d.out[idx1] + ") != " + (16 + i) + " (" + d.out[idx2] + ")";
                }
            }
            return null;
        }
    }

    /** TEST 8: 2-D ShiftMap by (0.5, -0.5) of a single value. */
    private static final class Case8 implements RebinCase {
        @Override
        public double klimit() {
            return 10000.0;
        }

        @Override
        public Setup setup(int spread) {
            return new Setup(
                    new int[] {-2, 0},
                    new int[] {3, 5},
                    new int[] {-2, 0},
                    new int[] {3, 5},
                    new int[] {-2, 0},
                    new int[] {3, 5},
                    new ShiftMap(new double[] {0.5, -0.5}),
                    P22,
                    0.0);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            for (int k = 0; k < in.length; k++) {
                in[k] = 0.0;
                inVar[k] = type.of(kfac);
            }
            in[20] = type.of(kfac);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            double sum = goodSum(d);
            double kt = kfac;
            if (!sumMatches(d.type, sum, kt, 5.0)) {
                return "TEST8 Data sum is " + sum + " should be " + kt;
            }
            if (spread == Mapping.AST__NEAREST) {
                return checkSingleSpike("TEST8", d, kt, 1);
            }
            // Input element 21 is pixel (0, 3), centred at (-0.5, 2.5), which lands at (0, 2), a
            // corner of output pixels (0..1, 2..3), so the output is point-symmetric about it.
            int[] lb = s.lbndOut;
            int[] ub = s.ubndOut;
            int nx = ub[0] - lb[0] + 1;
            int k = 0;
            for (int j = lb[1]; j <= ub[1]; j++) {
                for (int i = lb[0]; i <= ub[0]; i++) {
                    int ii = 1 - i;
                    int jj = 5 - j;
                    if (ii >= lb[0] && ii <= ub[0] && jj >= lb[1] && jj <= ub[1]) {
                        int kk = (jj - lb[1]) * nx + ii - lb[0];
                        if (!d.type.equal(d.out[kk], d.out[k])) {
                            return "TEST8 " + (kk + 1) + " (" + d.out[kk] + ") != " + (k + 1) + " (" + d.out[k] + ")";
                        }
                    }
                    k++;
                }
            }
            return null;
        }
    }

    /** TEST 9: 3-D ShiftMap of a single value. */
    private static final class Case9 implements RebinCase {
        @Override
        public double klimit() {
            return 10000.0;
        }

        @Override
        public Setup setup(int spread) {
            double[] shifts =
                    spread == Mapping.AST__NEAREST ? new double[] {1.7, 2.1, -1.2} : new double[] {0.5, 0.0, -0.5};
            int[] zero = {0, 0, 0};
            int[] six = {6, 6, 6};
            return new Setup(zero, six, zero, six, zero, six, new ShiftMap(shifts), P22, 0.0);
        }

        @Override
        public void fill(Setup s, Type type, double kfac, double[] in, double[] inVar) {
            for (int k = 0; k < in.length; k++) {
                in[k] = 0.0;
                inVar[k] = type.of(k + 1.0);
            }
            in[171] = type.of(kfac);
        }

        @Override
        public String check(Setup s, int spread, double kfac, Data d) {
            double sum = goodSum(d);
            double kt = kfac;
            if (!sumMatches(d.type, sum, kt, 5.0)) {
                return "TEST9 Data sum is " + sum + " should be " + kt;
            }
            if (spread == Mapping.AST__NEAREST) {
                // Input element 172 is pixel (3, 3, 3), centred at (2.5, 2.5, 2.5), which lands at
                // (4.2, 4.6, 1.3), inside output element 139, pixel (5, 5, 2).
                for (int k = 0; k < d.out.length; k++) {
                    double v = d.out[k];
                    if (k == 138) {
                        if (!equald(v, kt)) {
                            return "TEST9 El. 139 is " + v + " should be " + kt;
                        }
                    } else if (!equald(v, 0.0)) {
                        return "TEST9 El. " + (k + 1) + " is " + v + " should be zero";
                    }
                }
                return null;
            }
            int[] lb = s.lbndOut;
            int[] ub = s.ubndOut;
            double[] g = new double[3];
            double w = 0.0;
            int k = 0;
            for (int l = lb[2]; l <= ub[2]; l++) {
                for (int j = lb[1]; j <= ub[1]; j++) {
                    for (int i = lb[0]; i <= ub[0]; i++) {
                        double v = d.out[k];
                        g[0] += i * v;
                        g[1] += j * v;
                        g[2] += l * v;
                        w += v;
                        k++;
                    }
                }
            }
            String[] axes = {"X", "Y", "Z"};
            double[] want = {3.5, 3.0, 2.5};
            for (int a = 0; a < 3; a++) {
                double mean = g[a] / w;
                if (!equald(mean, want[a])) {
                    return "TEST9 Mean " + axes[a] + " is " + mean + " should be " + want[a];
                }
            }
            return null;
        }
    }

    @Test
    void test1UnitMap1d() {
        assertNull(tester(new Case1()));
    }

    @Test
    void test2UnitMap2d() {
        assertNull(tester(new Case2()));
    }

    @Test
    void test3UnitMap3d() {
        assertNull(tester(new Case3()));
    }

    @Test
    void test4ShiftMap1dIntegerShift() {
        assertNull(tester(new Case4()));
    }

    @Test
    void test5ShiftMap2dIntegerShift() {
        assertNull(tester(new Case5()));
    }

    @Test
    void test6ShiftMap3dIntegerShift() {
        assertNull(tester(new Case6()));
    }

    @Test
    void test7ShiftMap1dSingleValue() {
        assertNull(tester(new Case7()));
    }

    @Test
    void test8ShiftMap2dSingleValue() {
        assertNull(tester(new Case8()));
    }

    @Test
    void test9ShiftMap3dSingleValue() {
        assertNull(tester(new Case9()));
    }
}
