package uk.ac.starlink.ast;

import static uk.ac.starlink.ast.MathMapToken.OP_ABS;
import static uk.ac.starlink.ast.MathMapToken.OP_ACOS;
import static uk.ac.starlink.ast.MathMapToken.OP_ACOSD;
import static uk.ac.starlink.ast.MathMapToken.OP_ACOSH;
import static uk.ac.starlink.ast.MathMapToken.OP_ACOTH;
import static uk.ac.starlink.ast.MathMapToken.OP_ACSCH;
import static uk.ac.starlink.ast.MathMapToken.OP_ADD;
import static uk.ac.starlink.ast.MathMapToken.OP_AND;
import static uk.ac.starlink.ast.MathMapToken.OP_ASECH;
import static uk.ac.starlink.ast.MathMapToken.OP_ASIN;
import static uk.ac.starlink.ast.MathMapToken.OP_ASIND;
import static uk.ac.starlink.ast.MathMapToken.OP_ASINH;
import static uk.ac.starlink.ast.MathMapToken.OP_ATAN;
import static uk.ac.starlink.ast.MathMapToken.OP_ATAN2;
import static uk.ac.starlink.ast.MathMapToken.OP_ATAN2D;
import static uk.ac.starlink.ast.MathMapToken.OP_ATAND;
import static uk.ac.starlink.ast.MathMapToken.OP_ATANH;
import static uk.ac.starlink.ast.MathMapToken.OP_BITAND;
import static uk.ac.starlink.ast.MathMapToken.OP_BITOR;
import static uk.ac.starlink.ast.MathMapToken.OP_BITXOR;
import static uk.ac.starlink.ast.MathMapToken.OP_CEIL;
import static uk.ac.starlink.ast.MathMapToken.OP_COS;
import static uk.ac.starlink.ast.MathMapToken.OP_COSD;
import static uk.ac.starlink.ast.MathMapToken.OP_COSH;
import static uk.ac.starlink.ast.MathMapToken.OP_COTH;
import static uk.ac.starlink.ast.MathMapToken.OP_CSCH;
import static uk.ac.starlink.ast.MathMapToken.OP_DIM;
import static uk.ac.starlink.ast.MathMapToken.OP_DIV;
import static uk.ac.starlink.ast.MathMapToken.OP_EQ;
import static uk.ac.starlink.ast.MathMapToken.OP_EQV;
import static uk.ac.starlink.ast.MathMapToken.OP_EXP;
import static uk.ac.starlink.ast.MathMapToken.OP_FLOOR;
import static uk.ac.starlink.ast.MathMapToken.OP_GAUSS;
import static uk.ac.starlink.ast.MathMapToken.OP_GE;
import static uk.ac.starlink.ast.MathMapToken.OP_GT;
import static uk.ac.starlink.ast.MathMapToken.OP_INT;
import static uk.ac.starlink.ast.MathMapToken.OP_ISBAD;
import static uk.ac.starlink.ast.MathMapToken.OP_LDBAD;
import static uk.ac.starlink.ast.MathMapToken.OP_LDCON;
import static uk.ac.starlink.ast.MathMapToken.OP_LDDIG;
import static uk.ac.starlink.ast.MathMapToken.OP_LDE;
import static uk.ac.starlink.ast.MathMapToken.OP_LDEPS;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMAX;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMAX10E;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMAXE;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMDIG;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMIN;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMIN10E;
import static uk.ac.starlink.ast.MathMapToken.OP_LDMINE;
import static uk.ac.starlink.ast.MathMapToken.OP_LDPI;
import static uk.ac.starlink.ast.MathMapToken.OP_LDRAD;
import static uk.ac.starlink.ast.MathMapToken.OP_LDRND;
import static uk.ac.starlink.ast.MathMapToken.OP_LDVAR;
import static uk.ac.starlink.ast.MathMapToken.OP_LE;
import static uk.ac.starlink.ast.MathMapToken.OP_LOG;
import static uk.ac.starlink.ast.MathMapToken.OP_LOG10;
import static uk.ac.starlink.ast.MathMapToken.OP_LT;
import static uk.ac.starlink.ast.MathMapToken.OP_MAX;
import static uk.ac.starlink.ast.MathMapToken.OP_MIN;
import static uk.ac.starlink.ast.MathMapToken.OP_MOD;
import static uk.ac.starlink.ast.MathMapToken.OP_MUL;
import static uk.ac.starlink.ast.MathMapToken.OP_NE;
import static uk.ac.starlink.ast.MathMapToken.OP_NEG;
import static uk.ac.starlink.ast.MathMapToken.OP_NINT;
import static uk.ac.starlink.ast.MathMapToken.OP_NOT;
import static uk.ac.starlink.ast.MathMapToken.OP_NULL;
import static uk.ac.starlink.ast.MathMapToken.OP_OR;
import static uk.ac.starlink.ast.MathMapToken.OP_POISS;
import static uk.ac.starlink.ast.MathMapToken.OP_POW;
import static uk.ac.starlink.ast.MathMapToken.OP_QIF;
import static uk.ac.starlink.ast.MathMapToken.OP_RAND;
import static uk.ac.starlink.ast.MathMapToken.OP_SECH;
import static uk.ac.starlink.ast.MathMapToken.OP_SHFTL;
import static uk.ac.starlink.ast.MathMapToken.OP_SHFTR;
import static uk.ac.starlink.ast.MathMapToken.OP_SIGN;
import static uk.ac.starlink.ast.MathMapToken.OP_SIN;
import static uk.ac.starlink.ast.MathMapToken.OP_SINC;
import static uk.ac.starlink.ast.MathMapToken.OP_SIND;
import static uk.ac.starlink.ast.MathMapToken.OP_SINH;
import static uk.ac.starlink.ast.MathMapToken.OP_SQR;
import static uk.ac.starlink.ast.MathMapToken.OP_SQRT;
import static uk.ac.starlink.ast.MathMapToken.OP_SUB;
import static uk.ac.starlink.ast.MathMapToken.OP_TAN;
import static uk.ac.starlink.ast.MathMapToken.OP_TAND;
import static uk.ac.starlink.ast.MathMapToken.OP_TANH;
import static uk.ac.starlink.ast.MathMapToken.OP_XOR;

/**
 * The virtual machine that evaluates a compiled MathMap function, C's {@code EvaluateFunction}
 * (mathmap.c): each operation code applies a vector operation to a push-down stack of vectors, one
 * element per point, and the result is left in the bottom element, which is the output.
 *
 * <p>Unless an operation says otherwise, a bad argument gives a bad result, and so do numerical
 * errors, such as division by zero and overflow, and arguments outside a function's domain. C
 * traps the errors of some maths library functions ({@code exp}, {@code pow}, {@code ldexp} and
 * others) through {@code errno}; here a finite argument whose result is not finite stands for the
 * error C's library reports, as a library that sets {@code errno} (glibc) reports it. The macOS
 * library does not set {@code errno}, so C built there gives the infinity or NaN instead.
 *
 * <p>Ported from Rust {@code mappings/mathmap/evaluator.rs} and C {@code mathmap.c}.
 */
final class MathMapEvaluator {

    private MathMapEvaluator() {}

    private static final double BAD = Mapping.AST__BAD;

    /** C's {@code DBL_EPSILON}. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    /** C's {@code AST__DBL_DIG}, the significant digits of a full-precision double. */
    private static final int AST_DBL_DIG = 17;

    /** The number of bits in C's {@code unsigned long}, and in a Java {@code long}. */
    private static final int BITS = 64;

    /** The number of blocks of bits a double is processed in, C's {@code nblock}. */
    private static final int NBLOCK = (8 + 8 - 1) / 8;

    /** The smallest number that may be subtracted from 2.0, C's {@code eps}. */
    private static final double EPS = 2.0 * DBL_EPSILON;

    /** 2 to the power {@link #BITS}, C's {@code scale}. */
    private static final double SCALE = Math.scalb(1.0, BITS);

    /** 2 to the power {@code BITS - 1}, C's {@code scale1}. */
    private static final double SCALE1 = SCALE * 0.5;

    private static final double RSCALE = 1.0 / SCALE;
    private static final double RSCALE1 = 1.0 / SCALE1;

    /** The sign bit of a block, C's {@code signbit}. */
    private static final long SIGNBIT = 1L << (BITS - 1);

    /** C's {@code pi}, from {@code acos(-1.0)}. */
    private static final double PI = Math.acos(-1.0);

    private static final double R2D = 180.0 / PI;
    private static final double D2R = PI / 180.0;

    /** The natural logarithm of 2. */
    private static final double LOG2 = Math.log(2.0);

    /**
     * A value that may be squared without overflow, yet large enough that adding or subtracting 1
     * from its square makes no difference.
     */
    private static final double SAFE_SQ = 0.9 * Math.sqrt(Double.MAX_VALUE);

    private static final double RSAFE_SQ = 1.0 / SAFE_SQ;

    /**
     * Evaluates a compiled function for {@code npoint} points.
     *
     * @param rcontext the random-number context of the MathMap
     * @param ptrIn the data the function's variables refer to, {@code [variable][point]}
     * @param code the opcodes, the first element being the number that follow
     * @param con the constants the opcodes consume
     * @param stacksize the size of evaluation stack the opcodes need
     * @param out receives the result for each point
     */
    static void evaluate(
            MathMapRandom rcontext,
            int npoint,
            double[][] ptrIn,
            int[] code,
            double[] con,
            int stacksize,
            double[] out) {
        double[][] stack = new double[Math.max(stacksize, 1)][];
        stack[0] = out;
        for (int istk = 1; istk < stacksize; istk++) {
            stack[istk] = new double[npoint];
        }
        int tos = -1;
        int icon = 0;
        int ncode = code[0];
        for (int icode = 1; icode <= ncode; icode++) {
            int op = code[icode];
            switch (op) {
                case OP_NULL:
                    break;

                // Constants and variables.
                case OP_LDCON: {
                    double value = con[icon++];
                    fill(stack[++tos], npoint, value);
                    break;
                }
                case OP_LDVAR: {
                    int ivar = (int) Math.round(con[icon++]);
                    System.arraycopy(ptrIn[ivar], 0, stack[++tos], 0, npoint);
                    break;
                }
                case OP_LDBAD:
                case OP_LDDIG:
                case OP_LDEPS:
                case OP_LDMAX:
                case OP_LDMAX10E:
                case OP_LDMAXE:
                case OP_LDMDIG:
                case OP_LDMIN:
                case OP_LDMIN10E:
                case OP_LDMINE:
                case OP_LDRAD:
                case OP_LDRND:
                case OP_LDE:
                case OP_LDPI:
                    fill(stack[++tos], npoint, constant(op));
                    break;

                // Functions of one argument, which a bad argument leaves bad.
                case OP_ABS:
                case OP_ACOS:
                case OP_ACOSD:
                case OP_ACOSH:
                case OP_ACOTH:
                case OP_ACSCH:
                case OP_ASECH:
                case OP_ASIN:
                case OP_ASIND:
                case OP_ASINH:
                case OP_ATAN:
                case OP_ATAND:
                case OP_ATANH:
                case OP_CEIL:
                case OP_COS:
                case OP_COSD:
                case OP_COSH:
                case OP_COTH:
                case OP_CSCH:
                case OP_EXP:
                case OP_FLOOR:
                case OP_INT:
                case OP_LOG:
                case OP_LOG10:
                case OP_NINT:
                case OP_POISS:
                case OP_SECH:
                case OP_SIN:
                case OP_SINC:
                case OP_SIND:
                case OP_SINH:
                case OP_SQR:
                case OP_SQRT:
                case OP_TAN:
                case OP_TAND:
                case OP_TANH:
                case OP_NEG:
                case OP_NOT: {
                    double[] xv = stack[tos];
                    for (int point = 0; point < npoint; point++) {
                        double x = xv[point];
                        if (x != BAD) {
                            xv[point] = function1(op, x, rcontext);
                        }
                    }
                    break;
                }

                // The bad-value test, which takes a bad argument.
                case OP_ISBAD: {
                    double[] xv = stack[tos];
                    for (int point = 0; point < npoint; point++) {
                        xv[point] = xv[point] == BAD ? 1.0 : 0.0;
                    }
                    break;
                }

                // Functions and operators of two arguments, which a bad argument makes bad.
                case OP_ATAN2:
                case OP_ATAN2D:
                case OP_DIM:
                case OP_GAUSS:
                case OP_MOD:
                case OP_POW:
                case OP_RAND:
                case OP_SIGN:
                case OP_ADD:
                case OP_SUB:
                case OP_MUL:
                case OP_DIV:
                case OP_SHFTL:
                case OP_SHFTR:
                case OP_EQ:
                case OP_GE:
                case OP_GT:
                case OP_LE:
                case OP_LT:
                case OP_NE:
                case OP_BITOR:
                case OP_BITXOR:
                case OP_BITAND:
                case OP_EQV:
                case OP_XOR: {
                    double[] xv2 = stack[tos--];
                    double[] xv1 = stack[tos];
                    for (int point = 0; point < npoint; point++) {
                        double x1 = xv1[point];
                        if (x1 != BAD) {
                            double x2 = xv2[point];
                            xv1[point] = x2 != BAD ? function2(op, x1, x2, rcontext) : BAD;
                        }
                    }
                    break;
                }

                // The tri-state boolean operators, which take bad arguments.
                case OP_AND:
                case OP_OR: {
                    double[] xv2 = stack[tos--];
                    double[] xv1 = stack[tos];
                    for (int point = 0; point < npoint; point++) {
                        xv1[point] =
                                op == OP_AND ? tristateAnd(xv1[point], xv2[point]) : tristateOr(xv1[point], xv2[point]);
                    }
                    break;
                }

                // C's "a?b:c", which takes bad arguments.
                case OP_QIF: {
                    double[] xv3 = stack[tos--];
                    double[] xv2 = stack[tos--];
                    double[] xv1 = stack[tos];
                    for (int point = 0; point < npoint; point++) {
                        xv1[point] = xv1[point] != 0.0 ? xv2[point] : xv3[point];
                    }
                    break;
                }

                // Functions of a variable number of arguments, the number being a constant.
                case OP_MAX:
                case OP_MIN: {
                    int narg = (int) Math.round(con[icon++]);
                    for (int iarg = 0; iarg < narg - 1; iarg++) {
                        double[] xv2 = stack[tos--];
                        double[] xv1 = stack[tos];
                        for (int point = 0; point < npoint; point++) {
                            double x1 = xv1[point];
                            if (x1 != BAD) {
                                double x2 = xv2[point];
                                if (x2 == BAD) {
                                    xv1[point] = BAD;
                                } else if (op == OP_MAX) {
                                    xv1[point] = x1 >= x2 ? x1 : x2;
                                } else {
                                    xv1[point] = x1 <= x2 ? x1 : x2;
                                }
                            }
                        }
                    }
                    break;
                }
                default:
                    throw Ast.err(AstException.AST__INTER, "EvaluateFunction(MathMap): unknown opcode " + op + ".");
            }
        }
    }

    private static void fill(double[] y, int npoint, double value) {
        for (int point = 0; point < npoint; point++) {
            y[point] = value;
        }
    }

    /** The value a constant-loading opcode loads. */
    private static double constant(int op) {
        switch (op) {
            case OP_LDBAD:
                return BAD;
            case OP_LDDIG:
                return AST_DBL_DIG;
            case OP_LDEPS:
                return DBL_EPSILON;
            case OP_LDMAX:
                return Double.MAX_VALUE;
            case OP_LDMAX10E:
                return 308.0;
            case OP_LDMAXE:
                return Double.MAX_EXPONENT + 1;
            case OP_LDMDIG:
                return 53.0;
            case OP_LDMIN:
                return Double.MIN_NORMAL;
            case OP_LDMIN10E:
                return -307.0;
            case OP_LDMINE:
                return Double.MIN_EXPONENT + 1;
            case OP_LDRAD:
                return 2.0;
            // FLT_ROUNDS: rounding to nearest.
            case OP_LDRND:
                return 1.0;
            case OP_LDE:
                // C's exp(1.0), which C's library rounds correctly to Math.E; Java's Math.exp(1.0)
                // is one unit in the last place above it.
                return Math.E;
            case OP_LDPI:
                return PI;
            default:
                throw Ast.err(
                        AstException.AST__INTER, "EvaluateFunction(MathMap): unknown constant opcode " + op + ".");
        }
    }

    // ------------------------------------------------------------------
    // Functions of one argument
    // ------------------------------------------------------------------

    /** C's {@code ABS} macro, which keeps the sign of a negative zero. */
    private static double abs(double x) {
        return x >= 0.0 ? x : -x;
    }

    /** C's {@code INT} macro: rounding towards zero. */
    private static double trunc(double x) {
        return x >= 0.0 ? Math.floor(x) : Math.ceil(x);
    }

    /**
     * C's {@code CATCH_MATHS_OVERFLOW}: a result that overflows from finite arguments is bad, as
     * the {@code ERANGE} error a library that sets {@code errno} reports for it.
     */
    private static double catchOverflow(double result, boolean finiteArgs) {
        return finiteArgs && Double.isInfinite(result) ? BAD : result;
    }

    /**
     * C's {@code CATCH_MATHS_ERROR}: as {@link #catchOverflow}, and a result that is not a number
     * from finite arguments is bad, as the {@code EDOM} error such a library reports for it.
     */
    private static double catchError(double result, boolean finiteArgs) {
        return finiteArgs && !Double.isFinite(result) ? BAD : result;
    }

    /** The value of a one-argument function or unary operator at a good argument {@code x}. */
    private static double function1(int op, double x, MathMapRandom rcontext) {
        boolean finite = Double.isFinite(x);
        switch (op) {
            case OP_ABS:
                return abs(x);
            case OP_ACOS:
                return abs(x) <= 1.0 ? Math.acos(x) : BAD;
            case OP_ACOSD:
                return abs(x) <= 1.0 ? Math.acos(x) * R2D : BAD;
            case OP_ACOSH:
                if (x < 1.0) {
                    return BAD;
                }
                return x > SAFE_SQ ? Math.log(x) + LOG2 : Math.log(x + Math.sqrt(x * x - 1.0));
            case OP_ACOTH:
                return abs(x) <= 1.0 ? BAD : 0.5 * Math.log((x + 1.0) / (x - 1.0));
            case OP_ACSCH: {
                if (x == 0.0) {
                    return BAD;
                }
                boolean sign = x >= 0.0;
                double ax = abs(x);
                double v;
                if (ax < RSAFE_SQ) {
                    v = LOG2 - Math.log(ax);
                } else {
                    double r = 1.0 / ax;
                    v = Math.log(r + Math.sqrt(r * r + 1.0));
                }
                return (sign ? 1.0 : -1.0) * v;
            }
            case OP_ASECH: {
                if (x <= 0 || x > 1.0) {
                    return BAD;
                }
                if (x < RSAFE_SQ) {
                    return LOG2 - Math.log(x);
                }
                double r = 1.0 / x;
                return Math.log(r + Math.sqrt(r * r - 1.0));
            }
            case OP_ASIN:
                return abs(x) <= 1.0 ? Math.asin(x) : BAD;
            case OP_ASIND:
                return abs(x) <= 1.0 ? Math.asin(x) * R2D : BAD;
            case OP_ASINH: {
                boolean sign = x >= 0.0;
                double ax = abs(x);
                double v = ax > SAFE_SQ ? Math.log(ax) + LOG2 : Math.log(ax + Math.sqrt(ax * ax + 1.0));
                return (sign ? 1.0 : -1.0) * v;
            }
            case OP_ATAN:
                return Math.atan(x);
            case OP_ATAND:
                return Math.atan(x) * R2D;
            case OP_ATANH:
                return abs(x) >= 1.0 ? BAD : 0.5 * Math.log((1.0 + x) / (1.0 - x));
            case OP_CEIL:
                return Math.ceil(x);
            case OP_COS:
                return Math.cos(x);
            case OP_COSD:
                return Math.cos(x * D2R);
            case OP_COSH:
                return catchOverflow(Math.cosh(x), finite);
            case OP_COTH:
                return safeDiv(1.0, Math.tanh(x));
            case OP_CSCH: {
                double s = catchOverflow(Math.sinh(x), finite);
                return s == BAD ? 0.0 : safeDiv(1.0, s);
            }
            case OP_EXP:
                return catchOverflow(Math.exp(x), finite);
            case OP_FLOOR:
                return Math.floor(x);
            case OP_INT:
                return trunc(x);
            case OP_LOG:
                return x > 0.0 ? Math.log(x) : BAD;
            case OP_LOG10:
                return x > 0.0 ? Math.log10(x) : BAD;
            case OP_NINT:
                return x >= 0 ? Math.floor(x + 0.5) : Math.ceil(x - 0.5);
            case OP_POISS:
                return rcontext.poisson(x);
            case OP_SECH: {
                double c = catchOverflow(Math.cosh(x), finite);
                return c == BAD ? 0.0 : 1.0 / c;
            }
            case OP_SIN:
                return Math.sin(x);
            case OP_SINC:
                return x == 0.0 ? 1.0 : Math.sin(x) / x;
            case OP_SIND:
                return Math.sin(x * D2R);
            case OP_SINH:
                return catchOverflow(Math.sinh(x), finite);
            case OP_SQR:
                return safeMul(x, x);
            case OP_SQRT:
                return x >= 0.0 ? Math.sqrt(x) : BAD;
            case OP_TAN:
                return catchOverflow(Math.tan(x), finite);
            case OP_TAND:
                return Math.tan(x * D2R);
            case OP_TANH:
                return Math.tanh(x);
            case OP_NEG:
                return -x;
            case OP_NOT:
                return x == 0.0 ? 1.0 : 0.0;
            default:
                throw Ast.err(
                        AstException.AST__INTER, "EvaluateFunction(MathMap): unknown function opcode " + op + ".");
        }
    }

    // ------------------------------------------------------------------
    // Functions of two arguments
    // ------------------------------------------------------------------

    /** The value of a two-argument function or binary operator at good arguments. */
    private static double function2(int op, double x1, double x2, MathMapRandom rcontext) {
        switch (op) {
            case OP_ATAN2:
                return Math.atan2(x1, x2);
            case OP_ATAN2D:
                return Math.atan2(x1, x2) * R2D;
            case OP_DIM:
                return x1 > x2 ? x1 - x2 : 0.0;
            case OP_GAUSS:
                return gauss(x1, x2, rcontext);
            case OP_MOD:
                // Java's remainder of doubles is C's fmod.
                return x2 != 0.0 ? x1 % x2 : BAD;
            case OP_POW:
                return catchError(Math.pow(x1, x2), Double.isFinite(x1) && Double.isFinite(x2));
            case OP_RAND: {
                double ran = rcontext.rand();
                return x1 * ran + x2 * (1.0 - ran);
            }
            case OP_SIGN:
                return (x1 >= 0.0) == (x2 >= 0.0) ? x1 : -x1;
            case OP_ADD:
                return safeAdd(x1, x2);
            case OP_SUB:
                return safeSub(x1, x2);
            case OP_MUL:
                return safeMul(x1, x2);
            case OP_DIV:
                return safeDiv(x1, x2);
            case OP_SHFTL:
                return shiftBits(x1, x2);
            case OP_SHFTR:
                return shiftBits(x1, -x2);
            case OP_EQ:
                return x1 == x2 ? 1.0 : 0.0;
            case OP_GE:
                return x1 >= x2 ? 1.0 : 0.0;
            case OP_GT:
                return x1 > x2 ? 1.0 : 0.0;
            case OP_LE:
                return x1 <= x2 ? 1.0 : 0.0;
            case OP_LT:
                return x1 < x2 ? 1.0 : 0.0;
            case OP_NE:
                return x1 != x2 ? 1.0 : 0.0;
            case OP_BITOR:
            case OP_BITXOR:
            case OP_BITAND:
                return bitOper(op, x1, x2);
            case OP_EQV:
                return (x1 != 0.0) == (x2 != 0.0) ? 1.0 : 0.0;
            case OP_XOR:
                return (x1 != 0.0) != (x2 != 0.0) ? 1.0 : 0.0;
            default:
                throw Ast.err(
                        AstException.AST__INTER, "EvaluateFunction(MathMap): unknown function opcode " + op + ".");
        }
    }

    /** C's {@code TRISTATE_AND}: false if either argument is false, even when the other is bad. */
    private static double tristateAnd(double x1, double x2) {
        if (x1 == BAD) {
            return x2 != 0.0 ? BAD : 0.0;
        } else if (x2 == BAD) {
            return x1 != 0.0 ? BAD : 0.0;
        }
        return x1 != 0.0 && x2 != 0.0 ? 1.0 : 0.0;
    }

    /** C's {@code TRISTATE_OR}: true if either argument is true, even when the other is bad. */
    private static double tristateOr(double x1, double x2) {
        if (x1 == BAD) {
            return x2 == 0.0 || x2 == BAD ? BAD : 1.0;
        } else if (x2 == BAD) {
            return x1 == 0.0 ? BAD : 1.0;
        }
        return x1 != 0.0 || x2 != 0.0 ? 1.0 : 0.0;
    }

    /** C's {@code SAFE_ADD}: addition, bad where it would overflow. */
    private static double safeAdd(double x1, double x2) {
        if (x1 >= 0.0) {
            return x2 <= 0.0 || (Double.MAX_VALUE - x1) >= x2 ? x1 + x2 : BAD;
        }
        return x2 >= 0.0 || (Double.MAX_VALUE + x1) >= -x2 ? x1 + x2 : BAD;
    }

    /** C's {@code SAFE_SUB}: subtraction, bad where it would overflow. */
    private static double safeSub(double x1, double x2) {
        if (x1 >= 0.0) {
            return x2 >= 0.0 || (Double.MAX_VALUE - x1) >= -x2 ? x1 - x2 : BAD;
        }
        return x2 <= 0.0 || (Double.MAX_VALUE + x1) >= x2 ? x1 - x2 : BAD;
    }

    /** C's {@code SAFE_MUL}: multiplication, bad where it would overflow. */
    private static double safeMul(double x1, double x2) {
        double abs1 = abs(x1);
        if (abs1 <= 1.0) {
            return x1 * x2;
        }
        double abs2 = abs(x2);
        return abs2 <= 1.0 || (Double.MAX_VALUE / abs1) >= abs2 ? x1 * x2 : BAD;
    }

    /** C's {@code SAFE_DIV}: division, bad for a zero divisor and where it would overflow. */
    private static double safeDiv(double x1, double x2) {
        if (x2 == 0.0) {
            return BAD;
        }
        double abs2 = abs(x2);
        return abs2 >= 1.0 || (Double.MAX_VALUE * abs2) >= abs(x1) ? x1 / x2 : BAD;
    }

    /**
     * C's {@code GAUSS}: a value from a Gaussian distribution with mean {@code x1} and standard
     * deviation {@code |x2|}, drawn again until it does not overflow.
     */
    private static double gauss(double x1, double x2, MathMapRandom rcontext) {
        double result;
        do {
            double ran = rcontext.gauss();
            result = abs(x2);
            result = safeMul(ran, result);
            if (result != BAD) {
                result = safeAdd(result, x1);
            }
        } while (result == BAD);
        return result;
    }

    /**
     * C's {@code SHIFT_BITS}: {@code x1} times 2 to the power of the integer part of {@code x2},
     * zero where the power is too negative to hold in an {@code int}, and bad where it is too
     * large or the result overflows.
     */
    private static double shiftBits(double x1, double x2) {
        double frac = frexpFraction(x1);
        int expon = frexpExponent(x1);
        double newexp = trunc(x2) + expon;
        if (newexp < -Integer.MAX_VALUE) {
            return 0.0;
        } else if (newexp > Integer.MAX_VALUE) {
            return frac == 0.0 ? 0.0 : BAD;
        }
        return catchOverflow(ldexp(frac, (int) newexp), Double.isFinite(frac));
    }

    /**
     * C's {@code BIT_OPER}: a bit-wise operation on two values, each read as a fixed-point binary
     * number with negative values in two's complement, so that integer values give the result of
     * the integer operation and fraction bits take part too. Both values are brought to the same
     * power of two as fractions below 1; a negative value's fraction is subtracted from 2 (or the
     * largest number below 2, if it has underflowed), and the bits are combined a block at a time.
     */
    private static double bitOper(int op, double x1, double x2) {
        double frac1 = frexpFraction(abs(x1));
        int expon1 = frexpExponent(abs(x1));
        double frac2 = frexpFraction(abs(x2));
        int expon2 = frexpExponent(abs(x2));
        int expon = expon1;
        if (expon2 > expon1) {
            expon = expon2;
            frac1 = ldexp(frac1, expon1 - expon);
        } else if (expon1 > expon2) {
            frac2 = ldexp(frac2, expon2 - expon);
        }
        if (x1 < 0.0) {
            frac1 = 2.0 - (frac1 > EPS ? frac1 : EPS);
        }
        if (x2 < 0.0) {
            frac2 = 2.0 - (frac2 > EPS ? frac2 : EPS);
        }
        frac1 *= SCALE1;
        frac2 *= SCALE1;
        long b1 = toUnsignedLong(frac1);
        long b2 = toUnsignedLong(frac2);
        long b = combine(op, b1, b2);
        long neg = b & SIGNBIT;
        double unscale = RSCALE1;
        double result = unsignedToDouble(b) * unscale;
        for (int iblock = 1; iblock < NBLOCK; iblock++) {
            frac1 = (frac1 - unsignedToDouble(b1)) * SCALE;
            frac2 = (frac2 - unsignedToDouble(b2)) * SCALE;
            b1 = toUnsignedLong(frac1);
            b2 = toUnsignedLong(frac2);
            b = combine(op, b1, b2);
            unscale *= RSCALE;
            result += unsignedToDouble(b) * unscale;
        }
        if (neg != 0L) {
            result -= 2.0;
        }
        return ldexp(result, expon);
    }

    private static long combine(int op, long b1, long b2) {
        switch (op) {
            case OP_BITOR:
                return b1 | b2;
            case OP_BITXOR:
                return b1 ^ b2;
            case OP_BITAND:
                return b1 & b2;
            default:
                throw Ast.err(
                        AstException.AST__INTER, "EvaluateFunction(MathMap): unknown bit-wise opcode " + op + ".");
        }
    }

    // ------------------------------------------------------------------
    // C's conversions and frexp/ldexp
    // ------------------------------------------------------------------

    /**
     * C's conversion of a double in {@code [0, 2^64)} to {@code unsigned long}, which drops the
     * fraction, as the bits of a Java {@code long}.
     */
    static long toUnsignedLong(double d) {
        if (d < SCALE1) {
            return (long) d;
        }
        return (long) (d - SCALE1) ^ SIGNBIT;
    }

    /** C's conversion of an {@code unsigned long}, held in a Java {@code long}, to a double. */
    static double unsignedToDouble(long b) {
        if (b >= 0L) {
            return b;
        }
        // Halve with the lowest bit kept as a sticky bit, so that the one rounding is correct.
        return ((b >>> 1) | (b & 1L)) * 2.0;
    }

    /**
     * The fraction C's {@code frexp} gives: {@code x} as a fraction of magnitude in {@code [0.5,
     * 1)} with the sign of {@code x}; zero, infinities and NaN unchanged.
     */
    static double frexpFraction(double x) {
        if (x == 0.0 || !Double.isFinite(x)) {
            return x;
        }
        return Math.scalb(x, -frexpExponent(x));
    }

    /**
     * The power of two C's {@code frexp} gives, such that {@code x} is its fraction times 2 to
     * this power; zero for zero, infinities and NaN.
     */
    static int frexpExponent(double x) {
        if (x == 0.0 || !Double.isFinite(x)) {
            return 0;
        }
        int e = Math.getExponent(x);
        if (e == Double.MIN_EXPONENT - 1) {
            // A subnormal value: scale it into the normal range first.
            return Math.getExponent(x * 0x1.0p64) - 64 + 1;
        }
        return e + 1;
    }

    /**
     * {@code x} times 2 to the power {@code n}, rounded once, as C's {@code ldexp} gives it: the
     * result overflows to an infinity or underflows through the subnormal numbers to zero only
     * where the exact product does.
     */
    static double ldexp(double x, int n) {
        double y = x;
        if (n > 1023) {
            y *= 0x1.0p1023;
            n -= 1023;
            if (n > 1023) {
                y *= 0x1.0p1023;
                n -= 1023;
                n = Math.min(n, 1023);
            }
        } else if (n < -1022) {
            // A step down by 2^-969 keeps 53 bits clear of the subnormal range, so that only the
            // final multiplication rounds.
            y *= 0x1.0p-969;
            n += 1022 - 53;
            if (n < -1022) {
                y *= 0x1.0p-969;
                n += 1022 - 53;
                n = Math.max(n, -1022);
            }
        }
        return y * Double.longBitsToDouble((long) (0x3ff + n) << 52);
    }
}
