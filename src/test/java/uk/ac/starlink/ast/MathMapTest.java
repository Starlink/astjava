package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of the Rust tests {@code tests/test_mathmap.rs} that need no simplification, and
 * tests against the C probe {@code probes/mathmap_probe.c}.
 *
 * <p>The probe pins what no fixture or oracle holds: the value of every operator, function and
 * constant of C's symbol table at a set of arguments, bad and out-of-domain ones included; the
 * status and messages of each malformed function; the random-number functions for a given Seed;
 * the attribute strings; and the dumps through a Channel and a FitsChan.
 *
 * <p>Two kinds of probe value differ from Java's by ruling. C traps the errors of {@code exp},
 * {@code cosh}, {@code sinh} and {@code pow} through {@code errno}, which the macOS library the
 * probe was built with does not set, so the probe gives an infinity or a NaN where a library that
 * sets {@code errno} gives the bad value, as Java does ({@link #ERRNO_TRAPPED}). And C keeps the
 * second value of each Gaussian pair in variables shared by every MathMap, where Java keeps it in
 * each MathMap's random-number context, so that a copy, or another MathMap, does not take it.
 */
class MathMapTest {

    private static final double BAD = Mapping.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("mathmap_probe");

    /** The arguments of the probe's "evaluate" case. */
    private static final double[] XS = {-2.5, -1.0, -0.5, 0.0, 0.25, 1.0, 2.0, 1000.0, 1.0e300, -1.0e300, BAD};

    /**
     * The expressions whose maths library errors C traps through {@code errno}: where the probe,
     * built with a library that does not set it, gives an infinity or a NaN, Java gives the bad
     * value.
     */
    private static final Set<String> ERRNO_TRAPPED = new HashSet<>(Arrays.asList(
            "cosh(x)",
            "exp(x)",
            "sinh(x)",
            "pow(x,0.5)",
            "pow(x,2)",
            "pow(x,-1)",
            "pow(2,x)",
            "x**2",
            "x**0.5",
            "x**-1",
            "-x**2"));

    /**
     * The values, by "expression @ argument" as the probe prints them, that depend on an error C
     * traps through {@code errno} in a part of the expression: the value a library that sets
     * {@code errno} gives, which Java gives.
     */
    private static final Map<String, Double> ERRNO_CASES = new LinkedHashMap<>();

    static {
        // sinh overflows, so csch is 0; the probe's library gives 1/-infinity, -0.
        ERRNO_CASES.put("csch(x) @ -1.0000000000000001e+300", 0.0);
        // x**2 overflows and is bad, which ! and the outer ** keep; the probe's library gives an
        // infinity, which they turn into 0.
        ERRNO_CASES.put("!x**2 @ 1.0000000000000001e+300", BAD);
        ERRNO_CASES.put("!x**2 @ -1.0000000000000001e+300", BAD);
        ERRNO_CASES.put("2**-x**2 @ 1.0000000000000001e+300", BAD);
        ERRNO_CASES.put("2**-x**2 @ -1.0000000000000001e+300", BAD);
    }

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    /** The parts of a probe case, each starting with a line {@code -- <name>}, by name. */
    private static Map<String, String> parts(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String name = null;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("-- ")) {
                if (name != null) {
                    out.put(name, sb.toString());
                }
                name = line.substring(3);
                sb.setLength(0);
            } else if (name != null && !line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        if (name != null) {
            out.put(name, sb.toString());
        }
        return out;
    }

    /**
     * The probe's text with the lines naming the C source line of an error removed, which leaves
     * C's messages, each starting with {@code "!  "}, and the {@code status} lines.
     */
    private static String withoutErrorOrigin(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.startsWith("!! ") && !line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** The error report as the probe prints it, without the line naming the C source. */
    private static String report(AstException e) {
        StringBuilder sb = new StringBuilder();
        for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        return sb.append("status ").append(e.getStatus()).append('\n').toString();
    }

    private static MathMap map1(String expr) {
        return new MathMap(1, 1, new String[] {"y = " + expr}, new String[] {"x"});
    }

    private static double at(Mapping map, double x, boolean forward) {
        return map.tran1(1, new double[] {x}, forward)[0];
    }

    private static double evalAt(String expr, double x) {
        return at(map1(expr), x, true);
    }

    private static double[] at2(Mapping map, double x1, double x2, boolean forward) {
        double[][] out = map.tran2(1, new double[] {x1}, new double[] {x2}, forward);
        return new double[] {out[0][0], out[1][0]};
    }

    // ------------------------------------------------------------------
    // test_mathmap.rs
    // ------------------------------------------------------------------

    @Test
    void basicArithmetic() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x + 1"}, new String[] {"x = y - 1"});
        assertEquals(1, map.getNin());
        assertEquals(1, map.getNout());
        assertEquals(6.0, at(map, 5.0, true), 1e-10);
        assertEquals(5.0, at(map, 6.0, false), 1e-10);
    }

    @Test
    void multiplication() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x * 3"}, new String[] {"x = y / 3"});
        assertEquals(12.0, at(map, 4.0, true), 1e-10);
        assertEquals(4.0, at(map, 12.0, false), 1e-10);
    }

    @Test
    void powerOperator() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x ** 2"}, new String[] {"x = sqrt(y)"});
        assertEquals(25.0, at(map, 5.0, true), 1e-10);
        assertEquals(5.0, at(map, 25.0, false), 1e-10);
    }

    @Test
    void trigFunctions() {
        MathMap map = new MathMap(1, 1, new String[] {"y = sin(x)"}, new String[] {"x = asin(y)"});
        assertEquals(Math.sin(0.5), at(map, 0.5, true), 1e-10);
        assertEquals(0.5, at(map, Math.sin(0.5), false), 1e-10);
    }

    @Test
    void trigDegrees() {
        MathMap map = new MathMap(1, 1, new String[] {"y = sind(x)"}, new String[] {"x = asind(y)"});
        assertEquals(0.5, at(map, 30.0, true), 1e-10);
        assertEquals(30.0, at(map, 0.5, false), 1e-10);
    }

    @Test
    void systemConstants() {
        MathMap map = new MathMap(1, 1, new String[] {"y = <pi>"}, new String[] {"x = <pi>"});
        assertEquals(Math.PI, at(map, 0.0, true), 1e-14);
    }

    @Test
    void badValuePropagation() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x + 1"}, new String[] {"x = y - 1"});
        assertEquals(BAD, at(map, BAD, true));
    }

    @Test
    void isbadFunction() {
        MathMap map = new MathMap(1, 1, new String[] {"y = isbad(x)"}, new String[] {"x = y"});
        assertEquals(0.0, at(map, 42.0, true), 1e-10);
        assertEquals(1.0, at(map, BAD, true), 1e-10);
    }

    @Test
    void twoDimensional() {
        MathMap map = new MathMap(
                2, 2, new String[] {"yout1 = xin2", "yout2 = xin1"}, new String[] {"xin1 = yout2", "xin2 = yout1"});
        assertArrayEquals(new double[] {7.0, 3.0}, at2(map, 3.0, 7.0, true), 1e-10);
        assertArrayEquals(new double[] {3.0, 7.0}, at2(map, 7.0, 3.0, false), 1e-10);
    }

    @Test
    void intermediateVariables() {
        MathMap map = new MathMap(
                2,
                2,
                new String[] {
                    "r = sqrt(xin1*xin1 + xin2*xin2)", "theta = atan2(xin2, xin1)", "yout1 = r", "yout2 = theta"
                },
                new String[] {"xin1 = yout1 * cos(yout2)", "xin2 = yout1 * sin(yout2)"});
        double[] out = at2(map, 3.0, 4.0, true);
        assertEquals(5.0, out[0], 1e-10);
        assertEquals(Math.atan2(4.0, 3.0), out[1], 1e-10);
    }

    @Test
    void comparisonOperators() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x > 0"}, new String[] {"x = y"});
        assertEquals(1.0, at(map, 5.0, true), 1e-10);
        assertEquals(0.0, at(map, -5.0, true), 1e-10);
    }

    @Test
    void maxMinFunctions() {
        MathMap map = new MathMap(1, 1, new String[] {"y = max(x, 0, 10)"}, new String[] {"x = y"});
        assertEquals(10.0, at(map, -5.0, true), 1e-10);
        assertEquals(10.0, at(map, 5.0, true), 1e-10);
        assertEquals(15.0, at(map, 15.0, true), 1e-10);
    }

    @Test
    void expLog() {
        MathMap map = new MathMap(1, 1, new String[] {"y = exp(x)"}, new String[] {"x = log(y)"});
        assertEquals(Math.E, at(map, 1.0, true), 1e-10);
        assertEquals(1.0, at(map, Math.E, false), 1e-10);
    }

    @Test
    void absFunction() {
        MathMap map = new MathMap(1, 1, new String[] {"y = abs(x)"}, new String[] {"x = y"});
        assertEquals(3.0, at(map, -3.0, true), 1e-10);
        assertEquals(3.0, at(map, 3.0, true), 1e-10);
    }

    @Test
    void qifTernary() {
        MathMap map = new MathMap(1, 1, new String[] {"y = qif(x > 0, x, -x)"}, new String[] {"x = y"});
        assertEquals(5.0, at(map, 5.0, true), 1e-10);
        assertEquals(5.0, at(map, -5.0, true), 1e-10);
    }

    @Test
    void invert() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x + 10"}, new String[] {"x = y - 10"});
        assertEquals(15.0, at(map, 5.0, true), 1e-10);
        map.invert();
        assertEquals(5.0, at(map, 15.0, true), 1e-10);
    }

    @Test
    void multipointTransform() {
        MathMap map = new MathMap(1, 1, new String[] {"y = x * x"}, new String[] {"x = sqrt(y)"});
        double[] out = map.tran1(5, new double[] {1.0, 2.0, 3.0, 4.0, 5.0}, true);
        assertArrayEquals(new double[] {1.0, 4.0, 9.0, 16.0, 25.0}, out, 1e-10);
    }

    @Test
    void divisionByZero() {
        MathMap map = new MathMap(1, 1, new String[] {"y = 1.0 / x"}, new String[] {"x = 1.0 / y"});
        assertEquals(BAD, at(map, 0.0, true));
    }

    @Test
    void sqrtNegative() {
        MathMap map = new MathMap(1, 1, new String[] {"y = sqrt(x)"}, new String[] {"x = y * y"});
        assertEquals(BAD, at(map, -1.0, true));
    }

    @Test
    void modFunction() {
        MathMap map = new MathMap(1, 1, new String[] {"y = mod(x, 3)"}, new String[] {"x = y"});
        assertEquals(1.0, at(map, 7.0, true), 1e-10);
        assertEquals(-1.0, at(map, -7.0, true), 1e-10);
    }

    @Test
    void floorCeilNint() {
        assertEquals(2.0, evalAt("floor(x)", 2.7), 1e-10);
        assertEquals(-3.0, evalAt("floor(x)", -2.7), 1e-10);
        assertEquals(3.0, evalAt("ceil(x)", 2.3), 1e-10);
        assertEquals(-2.0, evalAt("ceil(x)", -2.3), 1e-10);
        assertEquals(2.0, evalAt("nint(x)", 2.3), 1e-10);
        assertEquals(3.0, evalAt("nint(x)", 2.7), 1e-10);
    }

    @Test
    void booleanAndTristate() {
        assertEquals(0.0, evalAt("x && 0", BAD), 1e-10);
        assertEquals(1.0, evalAt("x || 1", BAD), 1e-10);
    }

    /**
     * The binary operators bind with the priorities of C's operator table: {@code .neqv.} and
     * {@code .xor.} compute what {@code ^^} computes but bind as loosely as {@code .eqv.}. Expected
     * values are from the C library at x = 2 and x = 1.
     */
    @Test
    void binaryOperatorPrioritiesMatchC() {
        Object[][] cases = {
            {"x .or. x .xor. x", 0.0, 0.0},
            {"x .or. x .neqv. x", 0.0, 0.0},
            {"x || x ^^ x", 1.0, 1.0},
            {"x ^^ x .eqv. x", 0.0, 0.0},
            {"x .and. x .xor. x", 0.0, 0.0},
            {"x .xor. x .eqv. 0", 1.0, 1.0},
            {"x < 3 == 1", 1.0, 1.0},
            {"x << 1 < 3", 0.0, 1.0},
            {"-x * 3", -6.0, -3.0},
        };
        for (Object[] c : cases) {
            String expr = (String) c[0];
            assertEquals((double) c[1], evalAt(expr, 2.0), expr + " at x = 2");
            assertEquals((double) c[2], evalAt(expr, 1.0), expr + " at x = 1");
        }
    }

    /**
     * A prefix operator binds less tightly than a {@code **} on its right, as in C's operator
     * table: {@code -x**2} is {@code -(x**2)}. Expected values are from the C library.
     */
    @Test
    void prefixOperatorsBindBelowPower() {
        Object[][] cases = {
            {"-x**2", -4.0, -1.0},
            {"+x**2", 4.0, 1.0},
            {".not. x**2", 0.0, 0.0},
            {"!x**2", 0.0, 0.0},
            {"-x**2**2", -16.0, -1.0},
            {"x**-2", 0.25, 1.0},
            {"2**-x**2", 0.0625, 0.5},
            {"!x == 0", 1.0, 1.0},
            {"-x < -1", 1.0, 0.0},
        };
        for (Object[] c : cases) {
            String expr = (String) c[0];
            assertEquals((double) c[1], evalAt(expr, 2.0), expr + " at x = 2");
            assertEquals((double) c[2], evalAt(expr, 1.0), expr + " at x = 1");
        }
    }

    private static int expressionError(String expr) {
        try {
            map1(expr);
        } catch (AstException e) {
            return e.getStatus();
        }
        throw new AssertionError("\"" + expr + "\" compiled");
    }

    /**
     * Each symbol is read as an operator or as an operand according to what precedes it, as in C.
     * Expected codes are from the C library.
     */
    @Test
    void malformedExpressionsReportCErrorCodes() {
        Object[][] cases = {
            {"1.or.0", AstException.AST__MIOPR},
            {"1 .and. 1", AstException.AST__MIOPR},
            {"x .not. 1", AstException.AST__MIOPR},
            {"2x", AstException.AST__MIOPR},
            {"x(1)", AstException.AST__MIOPR},
            {"x!", AstException.AST__MIOPR},
            {"(x)(x)", AstException.AST__MIOPR},
            {"2.eq.2", AstException.AST__CONIN},
            {".eq.x", AstException.AST__CONIN},
            {"1.e", AstException.AST__CONIN},
            {"1.0.5", AstException.AST__CONIN},
            {"x<bad>", AstException.AST__UDVOF},
            {"sin", AstException.AST__UDVOF},
            {"q", AstException.AST__UDVOF},
            {"*x", AstException.AST__MIOPA},
            {"max(1,)", AstException.AST__MIOPA},
            {"x·2", AstException.AST__MIOPR},
        };
        for (Object[] c : cases) {
            assertEquals((int) c[1], expressionError((String) c[0]), (String) c[0]);
        }
        assertEquals(1.0, evalAt("x.or.1", 0.0));
        assertEquals(45.05, evalAt("1.e1 + 1.d1 + .5e-1 + 2.5d+1", 0.0));
    }

    /**
     * Parentheses and commas are checked as each is read, as in C. Expected codes are from the C
     * library.
     */
    @Test
    void parenthesesAndArgumentsReportCErrorCodes() {
        Object[][] cases = {
            {"x,1", AstException.AST__COMIN},
            {"(x,1)", AstException.AST__COMIN},
            {"sin((x,1))", AstException.AST__COMIN},
            {"max(x,(1,2))", AstException.AST__COMIN},
            {"x,1)+(", AstException.AST__COMIN},
            {"x)", AstException.AST__MLPAR},
            {"x)+(", AstException.AST__MLPAR},
            {"sin(x,1)", AstException.AST__WRNFA},
            {"atan2(x)", AstException.AST__WRNFA},
            {"atan2(x,1,2)", AstException.AST__WRNFA},
            {"qif(1,2)", AstException.AST__WRNFA},
            {"max(x)", AstException.AST__WRNFA},
            {"max(x)+(", AstException.AST__WRNFA},
            {"min(x,)", AstException.AST__MIOPA},
            {"x+", AstException.AST__MIOPA},
            {"x+)", AstException.AST__MIOPA},
            {"(,x)", AstException.AST__MIOPA},
            {")", AstException.AST__MIOPA},
            {"(x", AstException.AST__MRPAR},
            {"sin(x", AstException.AST__MRPAR},
        };
        for (Object[] c : cases) {
            assertEquals((int) c[1], expressionError((String) c[0]), (String) c[0]);
        }
        assertEquals(3.0, evalAt("max(1,2,3)", 0.0));
    }

    /**
     * The bit-wise operators read each value as a fixed-point binary number with negative values
     * in two's complement, as C does. Expected values are from the C library.
     */
    @Test
    void bitwiseOperatorsActOnFixedPointValues() {
        Object[][] cases = {
            {"2^3", 1.0},
            {"2|1", 3.0},
            {"1&3", 1.0},
            {"-1&3", 3.0},
            {"-2|1", -1.0},
            {"-1^-1", 0.0},
            {"0.5|0.25", 0.75},
            {"1.5^0.75", 1.25},
            {"-0.5&1", 1.0},
            {"3&-1e300", 0.0},
            {"<max>|0", Double.MAX_VALUE},
            {"1e-310|1", 1.0},
            {"1e-320|1e-310", Double.parseDouble("1.00000000002213e-310")},
            {"1e308&1e308", 1e308},
            {"-<max>|1", -Double.MAX_VALUE},
            {"0&0", 0.0},
            {"-0^1", 1.0},
            {"3.75&-2.5", 1.5},
            {"1e20|1", 1e20},
            {"1e-20^-1", -1.0},
            {"<min>|-<min>", -Double.MIN_NORMAL},
            {"x|2", 2.0},
            {"-1&-1", -1.0},
        };
        for (Object[] c : cases) {
            assertEquals((double) c[1], evalAt((String) c[0], 0.0), (String) c[0]);
        }
    }

    /**
     * The shift operators scale by a power of two with a single rounding. Expected values are
     * from the C library; an overflowing result is bad, as C gives it where {@code ldexp} sets
     * {@code errno}.
     */
    @Test
    void shiftOperatorsScaleExactly() {
        Object[][] cases = {
            {"1e-310<<1", 2e-310},
            {"<max>>>1<<1", Double.MAX_VALUE},
            {"1<<-1074", Double.MIN_VALUE},
            {"1<<-1075", 0.0},
            {"1.5<<-1074", 2 * Double.MIN_VALUE},
            {"<min>>>3", Double.parseDouble("2.781342323134e-309")},
            {"0.75<<1024", 1.348269851146737e308},
            {"<max><<1", BAD},
            {"1<<2000", BAD},
        };
        for (Object[] c : cases) {
            assertEquals((double) c[1], evalAt((String) c[0], 0.0), (String) c[0]);
        }
    }

    private static int constructionError(String[] fwd, String[] inv) {
        try {
            new MathMap(1, 1, fwd, inv);
        } catch (AstException e) {
            return e.getStatus();
        }
        throw new AssertionError(Arrays.toString(fwd) + " / " + Arrays.toString(inv) + " compiled");
    }

    /**
     * The left-hand side of every function, or the whole of one with no {@code =}, must be a
     * variable name, no name may be defined twice, and every function of a direction must have a
     * right-hand side, or none may. Expected codes are from the C library.
     */
    @Test
    void variableNamesAreValidated() {
        Object[][] cases = {
            {new String[] {"y = x1 + 1"}, new String[] {"x + 1"}, AstException.AST__VARIN},
            {new String[] {"y = x"}, new String[] {"x <= y"}, AstException.AST__VARIN},
            {new String[] {"y = x"}, new String[] {"x + 1 = y"}, AstException.AST__VARIN},
            {new String[] {"y = x"}, new String[] {"_x"}, AstException.AST__VARIN},
            {new String[] {"y = x"}, new String[] {"1x"}, AstException.AST__VARIN},
            {new String[] {"y = x + 1"}, new String[] {"x = y - 1", "x = 2"}, AstException.AST__DUVAR},
            {new String[] {"y = x"}, new String[] {"y"}, AstException.AST__DUVAR},
            {new String[] {"y = x + 1"}, new String[] {"= y - 1"}, AstException.AST__MISVN},
            {new String[] {"y = x + 1"}, new String[] {""}, AstException.AST__MISVN},
            {new String[] {"y = x + 1"}, new String[] {"   "}, AstException.AST__MISVN},
            {new String[] {"y =="}, new String[] {"x"}, AstException.AST__MIOPA},
            {new String[] {"y = = x"}, new String[] {"x"}, AstException.AST__MIOPA},
            {new String[] {"y ="}, new String[] {"x"}, AstException.AST__NORHS},
            {new String[] {"t", "y = x"}, new String[] {"x"}, AstException.AST__NORHS},
        };
        for (Object[] c : cases) {
            String[] fwd = (String[]) c[0];
            String[] inv = (String[]) c[1];
            assertEquals((int) c[2], constructionError(fwd, inv), Arrays.toString(fwd) + " / " + Arrays.toString(inv));
        }
        MathMap map = new MathMap(1, 1, new String[] {"t = x * 2", "y = t + 1"}, new String[] {"x"});
        assertEquals(1.0, at(map, 0.0, true));
        map = new MathMap(1, 1, new String[] {"y = 1"}, new String[] {"a = y", "x = a"});
        assertEquals(1.0, at(map, 0.0, true));
        assertEquals(3.0, at(map, 3.0, false));
    }

    // ------------------------------------------------------------------
    // The probe: every operator, function and constant
    // ------------------------------------------------------------------

    private static double parseC(String s) {
        switch (s) {
            case "inf":
                return Double.POSITIVE_INFINITY;
            case "-inf":
                return Double.NEGATIVE_INFINITY;
            case "nan":
            case "-nan":
                return Double.NaN;
            default:
                return Double.parseDouble(s);
        }
    }

    /** The number a token of probe text holds, or null if it is not a number. */
    private static Double number(String token) {
        try {
            return parseC(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Asserts that two texts are the same but for the last bits of their numbers, which may
     * differ where C's maths library and Java's round differently: every token but the numbers is
     * the same, and each number is within four units in the last place of C's, a bad value or a
     * value that is not finite being the same.
     */
    private static void assertSameNumbers(String expected, String actual, String what) {
        String[] el = expected.split("\n", -1);
        String[] al = actual.split("\n", -1);
        assertEquals(el.length, al.length, what + ": lines\n" + actual);
        for (int i = 0; i < el.length; i++) {
            String[] et = el[i].split(" ", -1);
            String[] at = al[i].split(" ", -1);
            assertEquals(et.length, at.length, what + ": " + al[i]);
            for (int j = 0; j < et.length; j++) {
                Double e = number(et[j]);
                Double a = number(at[j]);
                if (e == null || a == null || e == BAD || a == BAD || !Double.isFinite(e)) {
                    assertEquals(et[j], at[j], what + ": " + al[i]);
                } else {
                    assertEquals(e, a, 4 * Math.ulp(e), what + ": " + al[i]);
                }
            }
        }
    }

    /**
     * Each expression of the probe's "evaluate" case at each argument gives C's value. Values of
     * the maths library functions may differ from C's in the last bits, as the two libraries
     * round differently; every other value, and every bad value, is C's exactly.
     */
    @Test
    void everySymbolGivesCsValue() {
        Map<String, double[]> results = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String line : probe("evaluate").split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int atSign = line.indexOf(" @ ");
            int eq = line.lastIndexOf(" = ");
            String expr = line.substring(0, atSign);
            String key = line.substring(0, eq);
            double x = parseC(line.substring(atSign + 3, eq));
            double expected = parseC(line.substring(eq + 3));
            double[] out = results.computeIfAbsent(expr, e -> map1(e).tran1(XS.length, XS, true));
            double got = out[indexOf(x)];
            boolean ok;
            if (ERRNO_CASES.containsKey(key)) {
                ok = Double.compare(ERRNO_CASES.get(key), got) == 0;
            } else if (!Double.isFinite(expected) && ERRNO_TRAPPED.contains(expr)) {
                ok = got == BAD;
            } else if (expected == BAD || got == BAD || !Double.isFinite(expected) || expected == 0.0) {
                ok = Double.compare(expected, got) == 0;
            } else {
                ok = Math.abs(expected - got) <= 2 * Math.ulp(expected);
            }
            if (!ok) {
                failures.add(key + " = " + CFormat.format("%.17g", got) + " (C: " + line.substring(eq + 3) + ")");
            }
            checked++;
        }
        assertEquals(Collections.emptyList(), failures);
        assertEquals(XS.length * results.size(), checked);
        assertEquals(138, results.size());
    }

    private static int indexOf(double x) {
        for (int i = 0; i < XS.length; i++) {
            if (Double.compare(XS[i], x) == 0) {
                return i;
            }
        }
        throw new IllegalStateException("no argument " + x);
    }

    /** The constants of C's symbol table, exactly. */
    @Test
    void symbolicConstantsAreCs() {
        assertEquals(17.0, evalAt("<dig>", 0.0));
        assertEquals(Math.ulp(1.0), evalAt("<epsilon>", 0.0));
        assertEquals(53.0, evalAt("<mant_dig>", 0.0));
        assertEquals(Double.MAX_VALUE, evalAt("<max>", 0.0));
        assertEquals(308.0, evalAt("<max_10_exp>", 0.0));
        assertEquals(1024.0, evalAt("<max_exp>", 0.0));
        assertEquals(Double.MIN_NORMAL, evalAt("<min>", 0.0));
        assertEquals(-307.0, evalAt("<min_10_exp>", 0.0));
        assertEquals(-1021.0, evalAt("<min_exp>", 0.0));
        assertEquals(2.0, evalAt("<radix>", 0.0));
        assertEquals(1.0, evalAt("<rounds>", 0.0));
        // C's exp(1.0), which a correctly rounded library gives as Math.E.
        assertEquals(Math.E, evalAt("<e>", 0.0));
        assertEquals(Math.PI, evalAt("<pi>", 0.0));
        assertEquals(BAD, evalAt("<bad>", 0.0));
    }

    /** An input that is not finite is bad, the library's rule for such values. */
    @Test
    void inputsThatAreNotFiniteAreBad() {
        assertEquals(1.0, evalAt("isbad(x)", Double.NaN));
        assertEquals(1.0, evalAt("isbad(x)", Double.POSITIVE_INFINITY));
        assertEquals(BAD, evalAt("x + 1", Double.NEGATIVE_INFINITY));
        assertEquals(1.0, evalAt("x || 1", Double.NaN));
    }

    // ------------------------------------------------------------------
    // The probe: errors
    // ------------------------------------------------------------------

    /** The probe's text for a MathMap made with these functions: "made", or the error. */
    private static String made(int nin, int nout, String[] fwd, String[] inv) {
        try {
            new MathMap(nin, nout, fwd, inv);
            return "made\n";
        } catch (AstException e) {
            return report(e);
        }
    }

    /** Each malformed expression gives C's status and messages. */
    @Test
    void expressionErrorsGiveCsMessages() {
        Map<String, String> parts = parts(probe("expression_errors"));
        assertEquals(50, parts.size());
        for (Map.Entry<String, String> p : parts.entrySet()) {
            String expr = p.getKey();
            String got = made(1, 1, new String[] {"y = " + expr}, new String[] {"x"});
            assertEquals(withoutErrorOrigin(p.getValue()), got, expr);
        }
    }

    /** A unary operator may follow another operator. */
    @Test
    void unarySequencesGiveCsValues() {
        Map<String, String> parts = parts(probe("unary_sequences"));
        assertEquals(10, parts.size());
        for (Map.Entry<String, String> p : parts.entrySet()) {
            double y = evalAt(p.getKey(), 2.0);
            assertEquals(p.getValue(), CFormat.format("%.17g", y) + "\n", p.getKey());
        }
    }

    /** Errors in the functions as a whole give C's status and messages, naming the function. */
    @Test
    void functionErrorsGiveCsMessages() {
        Map<String, String> parts = parts(probe("function_errors"));
        Map<String, String[][]> cases = new LinkedHashMap<>();
        String[] y = {"y = x"};
        String[] yp1 = {"y = x + 1"};
        String[] x = {"x"};
        cases.put("varin_expression", new String[][] {{"y = x1 + 1"}, {"x + 1"}});
        cases.put("varin_relational", new String[][] {y, {"x <= y"}});
        cases.put("varin_lhs", new String[][] {y, {"x + 1 = y"}});
        cases.put("varin_underscore", new String[][] {y, {"_x"}});
        cases.put("varin_digit", new String[][] {y, {"1x"}});
        cases.put("duvar_inverse", new String[][] {yp1, {"x = y - 1", "x = 2"}});
        cases.put("duvar_input_output", new String[][] {y, {"y"}});
        cases.put("misvn_no_lhs", new String[][] {yp1, {"= y - 1"}});
        cases.put("misvn_empty", new String[][] {yp1, {""}});
        cases.put("misvn_blank", new String[][] {yp1, {"   "}});
        cases.put("miopa_double_equals", new String[][] {{"y =="}, x});
        cases.put("miopa_equals_equals", new String[][] {{"y = = x"}, x});
        cases.put("norhs_empty", new String[][] {{"y ="}, x});
        cases.put("norhs_mixed", new String[][] {{"t", "y = x"}, x});
        cases.put("udvof_third_function", new String[][] {{"a = x", "b = a", "y = b + c"}, x});
        cases.put("inverse_second_function", new String[][] {y, {"t = y", "x = t + <bad"}});
        cases.put("duvar_numbering", new String[][] {{"a = x", "b = a", "y = b"}, {"c = y", "a = c", "x = a"}});
        cases.put("extra_forward_ok", new String[][] {{"p = 1", "q = 2", "y = x"}, {"x = 1"}});
        cases.put("extra_forward_varin", new String[][] {{"p = 1", "y-2 = x"}, {"x = 1"}});
        cases.put("extra_inverse_varin", new String[][] {{"p = 1", "y = 2", "q = x"}, {"p q = 1", "x = y"}});
        cases.put("misvn_primary", new String[][] {y, {"c = y", "=1"}});
        assertEquals(parts.keySet(), cases.keySet());
        for (Map.Entry<String, String[][]> c : cases.entrySet()) {
            String got = made(1, 1, c.getValue()[0], c.getValue()[1]);
            assertEquals(withoutErrorOrigin(parts.get(c.getKey())), got, c.getKey());
        }
    }

    /**
     * The numbers of inputs, outputs and functions are checked as C checks them; a bad number of
     * outputs is reported with C's status for a bad number of inputs, AST__BADNI.
     */
    @Test
    void countErrorsGiveCsMessages() {
        Map<String, String> parts = parts(probe("count_errors"));
        String[] f = {"y = x"};
        String[] i = {"x = y"};
        assertEquals(withoutErrorOrigin(parts.get("nin_zero")), made(0, 1, f, i));
        assertEquals(withoutErrorOrigin(parts.get("nout_zero")), made(1, 0, f, i));
        assertEquals(withoutErrorOrigin(parts.get("nin_negative")), made(-1, 1, f, i));
        assertEquals(withoutErrorOrigin(parts.get("too_few_forward")), made(1, 2, f, new String[] {"x = y", "w = y"}));
        assertEquals(withoutErrorOrigin(parts.get("too_few_inverse")), made(2, 1, new String[] {"y = x", "z = x"}, i));
    }

    // ------------------------------------------------------------------
    // The probe: transformations
    // ------------------------------------------------------------------

    private static void tran2Line(StringBuilder sb, Mapping m, double x, double y, boolean forward) {
        try {
            double[] out = at2(m, x, y, forward);
            sb.append(forward ? "fwd " : "inv ")
                    .append(CFormat.format("%.17g", x))
                    .append(' ')
                    .append(CFormat.format("%.17g", y))
                    .append(" -> ")
                    .append(CFormat.format("%.17g", out[0]))
                    .append(' ')
                    .append(CFormat.format("%.17g", out[1]))
                    .append('\n');
        } catch (AstException e) {
            sb.append(report(e));
        }
    }

    private static String tranFlags(Mapping m) {
        return "TranForward " + (m.getTranForward() ? 1 : 0) + " TranInverse " + (m.getTranInverse() ? 1 : 0) + "\n";
    }

    /** Intermediate variables, an undefined direction and inversion, as C transforms them. */
    @Test
    void transformationsAsC() {
        StringBuilder sb = new StringBuilder();
        MathMap m = new MathMap(
                2,
                2,
                new String[] {
                    "r = sqrt( x * x + y * y )",
                    "rout = r * ( 1 + 0.1 * r * r )",
                    "theta = atan2( y, x )",
                    "xout = rout * cos( theta )",
                    "yout = rout * sin( theta )"
                },
                new String[] {"x", "y"});
        sb.append("made\n").append(tranFlags(m));
        tran2Line(sb, m, 3.0, 4.0, true);
        tran2Line(sb, m, -1.0, 0.5, true);
        tran2Line(sb, m, BAD, 0.5, true);
        tran2Line(sb, m, 3.0, 4.0, false);
        m.invert();
        sb.append(tranFlags(m));
        tran2Line(sb, m, 3.0, 4.0, false);
        MathMap p =
                new MathMap(2, 2, new String[] {"r = sqrt( x * x + y * y )", "theta = atan2( y, x )"}, new String[] {
                    "x = r * cos( theta )", "y = r * sin( theta )"
                });
        sb.append("made\n");
        tran2Line(sb, p, 3.0, 4.0, true);
        tran2Line(sb, p, 5.0, 0.9272952180016122, false);
        p.invert();
        tran2Line(sb, p, 5.0, 0.9272952180016122, true);
        MathMap u = new MathMap(1, 1, new String[] {"y"}, new String[] {"x = 2 * y"});
        sb.append("made\n").append(tranFlags(u));
        try {
            at(u, 3.0, true);
        } catch (AstException e) {
            sb.append(report(e));
        }
        sb.append("inv ").append(CFormat.format("%.17g", at(u, 3.0, false))).append('\n');
        assertSameNumbers(withoutErrorOrigin(probe("transformations")), sb.toString(), "transformations");
    }

    // ------------------------------------------------------------------
    // The probe: random numbers
    // ------------------------------------------------------------------

    private static MathMap random1(String expr, int seed) {
        MathMap m = map1(expr);
        m.setSeed(seed);
        return m;
    }

    private static String draws(Mapping m, int npoint, double x) {
        double[] in = new double[npoint];
        Arrays.fill(in, x);
        double[] out = m.tran1(npoint, in, true);
        StringBuilder sb = new StringBuilder();
        for (double v : out) {
            sb.append(CFormat.format("%.17g", v)).append('\n');
        }
        return sb.toString();
    }

    /**
     * The values of {@code rand}, {@code gauss} and {@code poisson} for a given Seed are C's, a
     * Seed below 1 included, for which C's generator gives values outside the range 0 to 1.
     */
    @Test
    void randomFunctionsGiveCsSequences() {
        Map<String, String> parts = parts(probe("random_seeds"));
        int[] seeds = {1, 12345, -7, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int seed : seeds) {
            assertEquals(parts.get("rand seed " + seed), draws(random1("rand(0,1)", seed), 6, 0.0), "rand " + seed);
        }
        for (int is = 0; is < 3; is++) {
            assertEquals(
                    parts.get("gauss seed " + seeds[is]),
                    draws(random1("gauss(x,1)", seeds[is]), 7, 0.0),
                    "gauss " + seeds[is]);
        }
        assertEquals(parts.get("rand range"), draws(random1("rand(x,-3)", 99), 5, 10.0));
        assertEquals(parts.get("gauss mean sd"), draws(random1("gauss(x,-2)", 99), 5, 10.0));
        assertEquals(parts.get("poisson small"), draws(random1("poisson(x)", 3), 10, 3.5));
        assertEquals(parts.get("poisson large"), draws(random1("poisson(x)", 3), 10, 20.0));
        assertEquals(parts.get("poisson huge mean"), draws(random1("poisson(x)", 3), 2, 1.0e40));
        assertEquals(parts.get("poisson zero"), draws(random1("poisson(x)", 3), 2, 0.0));
        assertEquals(parts.get("poisson negative"), draws(random1("poisson(x)", 3), 2, -1.0));
        assertEquals(parts.get("poisson bad"), draws(random1("poisson(x)", 3), 2, BAD));
        assertEquals(parts.get("rand bad"), draws(random1("rand(x,1)", 3), 2, BAD));
        assertEquals(parts.get("gauss pair"), draws(random1("gauss(0,1)", 1), 2, 0.0));
        assertSameNumbers(parts.get("mixed"), draws(random1("rand(0,1)+gauss(0,1)*poisson(5)", 8), 6, 0.0), "mixed");
    }

    /**
     * A copy continues its original's sequence, independently of it, and setting the Seed again
     * restarts it. C keeps a Gaussian pair's second value in variables shared by every MathMap,
     * so in C the original takes it and the copy draws a new pair; Java keeps it in the context a
     * copy copies, so the copy gives the original's sequence.
     */
    @Test
    void copiesContinueTheSequence() {
        Map<String, String> parts = parts(probe("random_copies"));
        MathMap m = random1("rand(0,1)", 42);
        assertEquals(parts.get("first"), draws(m, 3, 0.0));
        MathMap c = (MathMap) m.copy();
        assertEquals(parts.get("copy"), draws(c, 3, 0.0));
        assertEquals(parts.get("original"), draws(m, 3, 0.0));
        m.setSeed(42);
        assertEquals(parts.get("reseeded"), draws(m, 3, 0.0));

        MathMap g = random1("gauss(0,1)", 42);
        assertEquals(parts.get("gauss first"), draws(g, 1, 0.0));
        MathMap gc = (MathMap) g.copy();
        String original = parts.get("gauss original");
        assertEquals(original, draws(g, 3, 0.0));
        assertEquals(original, draws(gc, 3, 0.0));
    }

    /**
     * Two MathMaps used in turn each give the sequence they give alone. In C each takes the
     * Gaussian value the other kept: the probe's second value for "a" is the second value "b"
     * gives alone.
     */
    @Test
    void gaussianPairsBelongToTheirMathMap() {
        String[] lines = probe("gauss_two_mathmaps").split("\n", -1);
        assertEquals("-- a", lines[0]);
        assertEquals("-- a alone", lines[8]);
        assertEquals("-- b alone", lines[11]);
        String a1 = lines[9];
        String a2 = lines[10];
        String b1 = lines[12];
        String b2 = lines[13];
        assertEquals(Arrays.asList(a1, b1, b2), Arrays.asList(lines[1], lines[3], lines[5]));
        MathMap a = random1("gauss(0,1)", 5);
        MathMap b = random1("gauss(0,1)", 6);
        assertEquals(a1 + "\n", draws(a, 1, 0.0));
        assertEquals(b1 + "\n", draws(b, 1, 0.0));
        assertEquals(a2 + "\n", draws(a, 1, 0.0));
        assertEquals(b2 + "\n", draws(b, 1, 0.0));
    }

    /** Two threads transforming two copies of a seeded MathMap at once give the same values. */
    @Test
    void twoThreadsTransformingCopiesAgree() throws InterruptedException {
        MathMap m = random1("rand(0,1) + gauss(x, 2) * poisson(4.5) + poisson(30)", 2024);
        int npoint = 20000;
        double[] in = new double[npoint];
        for (int i = 0; i < npoint; i++) {
            in[i] = i * 0.001;
        }
        double[] alone = ((MathMap) m.copy()).tran1(npoint, in, true);
        MathMap c1 = (MathMap) m.copy();
        MathMap c2 = (MathMap) m.copy();
        double[][] results = new double[2][];
        CountDownLatch start = new CountDownLatch(1);
        Thread t1 = new Thread(() -> results[0] = transformAfter(start, c1, in));
        Thread t2 = new Thread(() -> results[1] = transformAfter(start, c2, in));
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();
        assertArrayEquals(alone, results[0]);
        assertArrayEquals(alone, results[1]);
        assertArrayEquals(alone, m.tran1(npoint, in, true));
    }

    private static double[] transformAfter(CountDownLatch start, MathMap m, double[] in) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new double[0];
        }
        return m.tran1(in.length, in, true);
    }

    /**
     * An unset Seed has an unpredictable default, different for each MathMap, which clearing the
     * Seed replaces; a default Seed, read and set on another MathMap, reproduces its sequence.
     */
    @Test
    void defaultSeedsDiffer() {
        MathMap a = map1("rand(0,1)");
        MathMap b = map1("rand(0,1)");
        assertFalse(a.test("Seed"));
        assertNotEquals(a.getSeed(), b.getSeed());
        int before = a.getSeed();
        a.clear("Seed");
        assertFalse(a.test("Seed"));
        assertNotEquals(before, a.getSeed());
        b.setSeed(a.getSeed());
        assertTrue(b.test("Seed"));
        assertEquals(draws(a, 4, 0.0), draws(b, 4, 0.0));
    }

    // ------------------------------------------------------------------
    // The probe: attributes and dumps
    // ------------------------------------------------------------------

    private static String attrs(MathMap m) {
        String s = "SimpFI " + m.getC("SimpFI") + " test " + (m.test("SimpFI") ? 1 : 0) + "\n" + "SimpIF "
                + m.getC("SimpIF") + " test " + (m.test("SimpIF") ? 1 : 0) + "\n";
        if (m.test("Seed")) {
            return s + "Seed " + m.getC("Seed") + " test 1\n";
        }
        return s + "Seed test 0\n";
    }

    /** The attribute strings are C's, and a shared MathMap refuses changes to them. */
    @Test
    void attributesAsC() {
        StringBuilder sb = new StringBuilder();
        MathMap m = new MathMap(1, 1, new String[] {"y = x"}, new String[] {"x = y"});
        sb.append(attrs(m));
        m.set("SimpFI=5, SimpIF=0, Seed=-12");
        sb.append(attrs(m));
        m.set("SimpFI=0, SimpIF=-3");
        sb.append(attrs(m));
        m.clear("SimpFI");
        m.clear("SimpIF");
        m.clear("Seed");
        sb.append(attrs(m));
        CmpMap cm = new CmpMap(m, new ZoomMap(1, 2.0), true);
        assertEquals(1, cm.getNin());
        sb.append("-- shared\n");
        try {
            m.setI("Seed", 3);
            fail("Seed set on a shared MathMap");
        } catch (AstException e) {
            sb.append(report(e));
        }
        try {
            m.setI("SimpFI", 1);
            fail("SimpFI set on a shared MathMap");
        } catch (AstException e) {
            sb.append(report(e));
        }
        try {
            m.clear("SimpIF");
            fail("SimpIF cleared on a shared MathMap");
        } catch (AstException e) {
            sb.append(report(e));
        }
        assertEquals(withoutErrorOrigin(probe("attributes")), sb.toString());
    }

    /** The public accessors read and write the attributes. */
    @Test
    void publicAccessors() {
        MathMap m = map1("x");
        assertFalse(m.getSimpFI());
        assertFalse(m.getSimpIF());
        m.setSimpFI(true);
        m.setSimpIF(true);
        assertTrue(m.getSimpFI());
        assertTrue(m.getSimpIF());
        m.setSeed(-99);
        assertEquals(-99, m.getSeed());
        assertThrows(AstException.class, () -> m.set("Seed=x"));
    }

    /**
     * The dump through a Channel, the cards of a native FitsChan write, and the dump of the Object
     * read back from them, as the probe's {@code dumps} gives them.
     */
    private static void assertDumps(String name, AstObject obj) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChannelText.dump(obj));
        FitsChan fc = new FitsChan();
        fc.setC("Encoding", "NATIVE");
        fc.write(obj);
        sb.append("-- fits\n");
        fc.clear("Card");
        String card;
        while ((card = fc.findFits("%f", true)) != null) {
            assertEquals(80, card.length(), card);
            sb.append(ProbeOutput.trimLines(card)).append('\n');
        }
        fc.clear("Card");
        AstObject back = fc.read();
        sb.append("-- read back from fits\n").append(ChannelText.dump(back));
        assertEquals(probe(name), sb.toString(), name);
    }

    /**
     * A MathMap with intermediate variables and random functions, with each attribute set and
     * unset, inverted, without a forward transformation and inside a CmpMap, dumps and reads back
     * as C's does.
     */
    @Test
    void dumpsAsC() {
        MathMap m = new MathMap(
                2,
                2,
                new String[] {
                    "R = sqrt( X*X + Y*Y )",
                    "Theta = atan2( Y, X )",
                    "Rout = R * ( 1 + 0.1 * R*R ) + gauss( 0, 0.001 )",
                    "Tout = Theta + rand( -1.0E-6, 1.0E-6 )"
                },
                new String[] {"X = Rout * cos( Tout )", "Y = Rout * sin( Tout )"});
        assertDumps("dump_default", m);
        m.set("SimpFI=1");
        assertDumps("dump_simpfi", m);
        m.set("SimpIF=0, Seed=1234");
        assertDumps("dump_all_set", m);
        m.clear("SimpFI");
        m.clear("SimpIF");
        m.clear("Seed");
        assertDumps("dump_cleared", m);
        MathMap mi = (MathMap) m.copy();
        mi.set("Seed=-5, SimpIF=1");
        mi.invert();
        assertDumps("dump_inverted", mi);
        MathMap u = new MathMap(1, 1, new String[] {"y"}, new String[] {"t = y * 2", "x = t + 1"});
        assertDumps("dump_no_forward", u);
        CmpMap cm = new CmpMap(new ZoomMap(2, 3.0), mi, true);
        assertDumps("dump_in_cmpmap", cm);
    }

    /** A MathMap read back with its Seed gives the original's sequence. */
    @Test
    void seedSurvivesReadBack() {
        MathMap m = random1("rand(0,1)", 77);
        FitsChan fc = new FitsChan();
        fc.setC("Encoding", "NATIVE");
        fc.write(m);
        fc.clear("Card");
        Mapping back = (Mapping) fc.read();
        String got = "Seed " + back.getC("Seed") + " test " + (back.test("Seed") ? 1 : 0) + "\n" + draws(back, 3, 0.0)
                + draws(m, 3, 0.0);
        assertEquals(probe("seed_read_back"), got);
    }

    /**
     * Simplifying a CmpMap clears the flag allowing a restricted simplification on the components
     * it joins into a new CmpMap, which it shares with the input, as C's does.
     */
    @Test
    void simplifyClearsAllowSimplifyOnSharedComponents() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/cap_restricted_component_flags.map"));
        m.simplify();
        assertEquals(probe("simplify_input_after"), ChannelText.dump(m));
    }

    /** A dump through a Channel reads back as an equivalent MathMap, through text. */
    @Test
    void channelRoundTripTransformsTheSame() {
        MathMap m = new MathMap(
                2,
                2,
                new String[] {"r = sqrt(x*x + y*y)", "t = atan2(y, x)", "u = r * 2", "v = t + rand(0, 1)"},
                new String[] {"a = u / 2", "x = a * cos(v)", "y = a * sin(v)"});
        m.setSeed(11);
        String text = ChannelText.dump(m);
        MathMap back = (MathMap) ChannelText.read(text);
        assertEquals(text, ChannelText.dump(back));
        assertArrayEquals(at2(m, 3.0, 4.0, true), at2(back, 3.0, 4.0, true));
        assertArrayEquals(at2(m, 5.0, 0.5, false), at2(back, 5.0, 0.5, false));
    }

    /** A loaded description with fewer functions than outputs is refused. */
    @Test
    void loadingTooFewFunctionsIsRefused() {
        String text = " Begin MathMap\n    Nin = 1\n IsA Mapping\n    Nfwd = 0\n    Inv1 = \"x\"\n End MathMap\n";
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__INNTF, e.getStatus());
    }
}
