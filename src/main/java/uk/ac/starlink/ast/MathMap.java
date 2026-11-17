package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * Java interface to the AST MathMap class - transform coordinates using mathematical expressions.
 * A MathMap is a Mapping which allows you to specify a set of forward and/or inverse transformation
 * functions using arithmetic operations and mathematical functions similar to those available in
 * C. The MathMap interprets these functions at run-time, whenever its forward or inverse
 * transformation is required. Because the functions are not compiled in the normal sense (unlike
 * an IntraMap), they may be used to describe coordinate transformations in a transportable manner.
 * A MathMap therefore provides a flexible way of defining new types of Mapping whose descriptions
 * may be stored as part of a dataset and interpreted by other programs.
 *
 * <h2>Defining Transformation Functions</h2>
 *
 * A MathMap's transformation functions are supplied as a set of expressions in an array of
 * character strings. Normally you would supply the same number of expressions for the forward
 * transformation, via the "fwd" parameter, as there are output variables (given by the MathMap's
 * Nout attribute). For instance, if Nout is 2 you might use:
 *
 * <ul>
 *   <li>"r = sqrt( x * x + y * y )"
 *   <li>"theta = atan2( y, x )"
 * </ul>
 *
 * <p>which defines a transformation from Cartesian to polar coordinates. Here, the variables that
 * appear on the left of each expression ("r" and "theta") provide names for the output variables
 * and those that appear on the right ("x" and "y") are references to input variables.
 *
 * <p>To complement this, you must also supply expressions for the inverse transformation via the
 * "inv" parameter. In this case, the number of expressions given would normally match the number
 * of MathMap input coordinates (given by the Nin attribute). If Nin is 2, you might use:
 *
 * <ul>
 *   <li>"x = r * cos( theta )"
 *   <li>"y = r * sin( theta )"
 * </ul>
 *
 * <p>which expresses the transformation from polar to Cartesian coordinates. Note that here the
 * input variables ("x" and "y") are named on the left of each expression, and the output variables
 * ("r" and "theta") are referenced on the right.
 *
 * <p>Normally, you cannot refer to a variable on the right of an expression unless it is named on
 * the left of an expression in the complementary set of functions. Therefore both sets of
 * functions (forward and inverse) must be formulated using the same consistent set of variable
 * names. This means that if you wish to leave one of the transformations undefined, you must
 * supply dummy expressions which simply name each of the output (or input) variables. For example,
 * you might use "x" and "y" for the inverse transformation above, which serves to name the input
 * variables but without defining an inverse transformation.
 *
 * <h2>Calculating Intermediate Values</h2>
 *
 * It is sometimes useful to calculate intermediate values and then to use these in the final
 * expressions for the output (or input) variables. This may be done by supplying additional
 * expressions for the forward (or inverse) transformation functions. For instance, the following
 * array of five expressions describes 2-dimensional pin-cushion distortion:
 *
 * <ul>
 *   <li>"r = sqrt( xin * xin + yin * yin )"
 *   <li>"rout = r * ( 1 + 0.1 * r * r )"
 *   <li>"theta = atan2( yin, xin )"
 *   <li>"xout = rout * cos( theta )"
 *   <li>"yout = rout * sin( theta )"
 * </ul>
 *
 * <p>Here, we first calculate three intermediate results ("r", "rout" and "theta") and then use
 * these to calculate the final results ("xout" and "yout"). The MathMap knows that only the final
 * two results constitute values for the output variables because its Nout attribute is set to 2.
 * You may define as many intermediate variables in this way as you choose. Having defined a
 * variable, you may then refer to it on the right of any subsequent expressions. The intermediate
 * variables are private to their transformation: the inverse transformation may refer only to the
 * output variables, though it may define its own private intermediate variables.
 *
 * <h2>Expression Syntax</h2>
 *
 * The expressions closely follow the syntax of the C programming language (with some extensions
 * for compatibility with Fortran). They may contain references to variables and literal constants,
 * together with arithmetic, boolean, relational and bitwise operators, and function invocations. A
 * set of symbolic constants is also available. Parentheses may be used to over-ride the normal
 * order of evaluation. There is no built-in limit to the length of expressions and they are
 * insensitive to case or the presence of additional white space.
 *
 * <p>Variable names must begin with an alphabetic character and may contain only alphabetic
 * characters, digits, and the underscore character "_". Literal constants, such as "0", "1",
 * "0.007" or "2.505e-16", may appear in expressions, with the decimal point and exponent being
 * optional (a "D" may also be used as an exponent character). A unary minus "-" may be used as a
 * prefix. All arithmetic is floating point, performed in double precision.
 *
 * <p>Unless indicated otherwise, if any argument of a function or operator has the value {@link
 * #AST__BAD} (indicating missing data), then the result of that function or operation is also
 * {@link #AST__BAD}, so that such values are propagated automatically through all operations
 * performed by MathMap transformations. The special value is written "&lt;bad&gt;" in expressions.
 * A bad result is also produced in response to any numerical error (such as division by zero or
 * numerical overflow), or if an invalid argument value is provided to a function or operator.
 *
 * <p>The arithmetic operators are {@code +}, {@code -}, {@code *}, {@code /} and {@code **}
 * (power), with unary {@code +} and {@code -}. The boolean operators, for which zero is false,
 * other values true and {@link #AST__BAD} "unknown", are {@code &&} ({@code .and.}) and {@code ||}
 * ({@code .or.}), which use tri-state logic, {@code ^^} (exclusive or), {@code .eqv.}, {@code
 * .neqv.} and {@code .xor.}, and {@code !} ({@code .not.}). The relational operators are {@code
 * ==} ({@code .eq.}), {@code !=} ({@code .ne.}), {@code >} ({@code .gt.}), {@code >=} ({@code
 * .ge.}), {@code <} ({@code .lt.}) and {@code <=} ({@code .le.}). The bitwise operators {@code
 * >>}, {@code <<}, {@code &}, {@code |} and {@code ^} regard their operands as fixed-point binary
 * numbers with negative values in two's complement.
 *
 * <p>The functions are {@code abs} ({@code fabs}), {@code acos}, {@code acosd}, {@code acosh},
 * {@code acoth}, {@code acsch}, {@code aint} ({@code int}), {@code asech}, {@code asin}, {@code
 * asind}, {@code asinh}, {@code atan}, {@code atand}, {@code atanh}, {@code atan2}, {@code atan2d},
 * {@code ceil}, {@code cos}, {@code cosd}, {@code cosh}, {@code coth}, {@code csch}, {@code dim},
 * {@code exp}, {@code floor}, {@code fmod} ({@code mod}), {@code gauss}, {@code isbad}, {@code
 * log}, {@code log10}, {@code max}, {@code min}, {@code nint}, {@code poisson}, {@code pow},
 * {@code qif}, {@code rand}, {@code sech}, {@code sign}, {@code sin}, {@code sinc}, {@code sind},
 * {@code sinh}, {@code sqr}, {@code sqrt}, {@code tan}, {@code tand} and {@code tanh}. The symbolic
 * constants, whose enclosing "&lt;&gt;" brackets must be included, are &lt;bad&gt;, &lt;dig&gt;,
 * &lt;e&gt;, &lt;epsilon&gt;, &lt;mant_dig&gt;, &lt;max&gt;, &lt;max_10_exp&gt;, &lt;max_exp&gt;,
 * &lt;min&gt;, &lt;min_10_exp&gt;, &lt;min_exp&gt;, &lt;pi&gt;, &lt;radix&gt; and &lt;rounds&gt;.
 *
 * <h2>Evaluation Precedence and Associativity</h2>
 *
 * Items appearing in expressions are evaluated in the following order (highest precedence first):
 * constants and variables; function arguments and parenthesised expressions; function
 * invocations; {@code **}; unary {@code + - ! .not.}; {@code * /}; {@code + -}; {@code << >>};
 * {@code < .lt. <= .le. > .gt. >= .ge.}; {@code == .eq. != .ne.}; {@code &}; {@code ^}; {@code |};
 * {@code && .and.}; {@code ^^}; {@code || .or.}; {@code .eqv. .neqv. .xor.}. So {@code -x**2} is
 * {@code -(x**2)}, and {@code x**-2} is {@code x**(-2)}. All operators associate from
 * left-to-right, except for unary {@code +}, unary {@code -}, {@code !}, {@code .not.} and {@code
 * **}, which associate from right-to-left.
 *
 * <h2>Notes</h2>
 *
 * <ul>
 *   <li>The sequence of numbers produced by the random number functions available within a
 *       MathMap is normally unpredictable and different for each MathMap. However, this behaviour
 *       may be controlled by means of the MathMap's Seed attribute.
 *   <li>Normally, compound Mappings (CmpMaps) which involve MathMaps will not be subject to
 *       simplification because AST cannot know how different MathMaps will interact. However, in
 *       the special case where a MathMap occurs in series with its own inverse, then simplification
 *       may be possible. Whether simplification does, in fact, occur under these circumstances is
 *       controlled by the MathMap's SimpFI and SimpIF attributes.
 * </ul>
 *
 * <p>Ported from Rust {@code mappings/mathmap/mod.rs} and C {@code mathmap.c}. A MathMap keeps C's
 * compiled form of its functions: for each function an array of operation codes, whose first
 * element is the number that follow, and an array of the constants they consume ({@link
 * MathMapParser}), evaluated by {@link MathMapEvaluator}. Its random-number functions draw on a
 * context of its own ({@link MathMapRandom}), which a copy copies.
 *
 * @see <a href='http://star-www.rl.ac.uk/cgi-bin/htxserver/sun211.htx/?xref_MathMap'>AST MathMap</a>
 */
public class MathMap extends Mapping {

    /** The value C stores while SimpFI or SimpIF is unset, {@code -INT_MAX}. */
    private static final int UNSET = -Integer.MAX_VALUE;

    /** The cleaned forward functions (C's {@code fwdfun}): lower case, without white space. */
    private final String[] fwdfun;

    /** The cleaned inverse functions (C's {@code invfun}). */
    private final String[] invfun;

    /**
     * The compiled functions. Like the function texts, they are never changed once made, so a
     * copy shares them.
     */
    private final MathMapParser.Compiled compiled;

    /** The SimpFI attribute, {@link #UNSET} while unset. */
    private int simpFiFlag;

    /** The SimpIF attribute, {@link #UNSET} while unset. */
    private int simpIfFlag;

    /** The random-number context, which holds the Seed attribute (C's {@code rcontext}). */
    private MathMapRandom rcontext;

    /**
     * Create a MathMap. This function creates a new MathMap and optionally initialises its
     * attributes.
     *
     * <p>A MathMap is a Mapping which allows you to specify a set of forward and/or inverse
     * transformation functions using arithmetic operations and mathematical functions similar to
     * those available in C, as the class description explains.
     *
     * @param nin Number of input variables for the MathMap. This determines the value of its Nin
     *     attribute.
     * @param nout Number of output variables for the MathMap. This determines the value of its Nout
     *     attribute.
     * @param fwd an array of Strings describing the forward transformations. There should be at
     *     least {@code nout} of these, but there may be more (see the note on "Calculating
     *     intermediate values").
     * @param inv an array of Strings describing the inverse transformations. There should be at
     *     least {@code nin} of these, but there may be more (see the note on "Calculating
     *     intermediate values").
     * @throws AstException if an error occurred in the AST library
     */
    public MathMap(int nin, int nout, String[] fwd, String[] inv) {
        this(Init.of(nin, nout, fwd, inv), nin, nout);
    }

    /** C's {@code astInitMathMap}, once the counts are checked and the functions compiled. */
    private MathMap(Init init, int nin, int nout) {
        super(nin, nout, init.compiled.fwdcode != null, init.compiled.invcode != null);
        this.fwdfun = init.fwdfun;
        this.invfun = init.invfun;
        this.compiled = init.compiled;
        this.simpFiFlag = UNSET;
        this.simpIfFlag = UNSET;
        this.rcontext = new MathMapRandom();
    }

    /** The cleaned and compiled functions of a new MathMap. */
    private static final class Init {
        final String[] fwdfun;
        final String[] invfun;
        final MathMapParser.Compiled compiled;

        private Init(String[] fwdfun, String[] invfun, MathMapParser.Compiled compiled) {
            this.fwdfun = fwdfun;
            this.invfun = invfun;
            this.compiled = compiled;
        }

        /**
         * C's checks and compilation in {@code astInitMathMap}: the numbers of inputs and outputs
         * must be at least 1 (C reports a bad output count with status {@code AST__BADNI}, as it
         * does a bad input count), and there must be at least {@code nout} forward and {@code nin}
         * inverse functions; the functions are then cleaned and compiled.
         */
        static Init of(int nin, int nout, String[] fwd, String[] inv) {
            String name = "MathMap";
            if (nin < 1) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astInitMathMap(" + name + "): Bad number of input coordinates (" + nin
                                + ").\nThis number should be one or more.");
            } else if (nout < 1) {
                throw Ast.err(
                        AstException.AST__BADNI,
                        "astInitMathMap(" + name + "): Bad number of output coordinates (" + nout
                                + ").\nThis number should be one or more.");
            }
            checkCounts("astInitMathMap", name, nin, nout, fwd.length, inv.length);
            String[] fwdfun = MathMapParser.cleanFunctions(fwd);
            String[] invfun = MathMapParser.cleanFunctions(inv);
            MathMapParser.Compiled compiled =
                    MathMapParser.compileMapping("astInitMathMap", name, nin, nout, fwdfun, invfun);
            return new Init(fwdfun, invfun, compiled);
        }
    }

    /**
     * C's check in {@code astInitMathMap} that there are at least {@code nout} forward and {@code
     * nin} inverse functions.
     *
     * @throws AstException {@code AST__INNTF} if there are too few
     */
    private static void checkCounts(String method, String name, int nin, int nout, int nfwd, int ninv) {
        if (nfwd < nout) {
            throw Ast.err(
                    AstException.AST__INNTF,
                    method + "(" + name + "): Too few forward transformation functions given (" + nfwd + ").\nAt least "
                            + nout + " forward transformation functions must be supplied. ");
        } else if (ninv < nin) {
            throw Ast.err(
                    AstException.AST__INNTF,
                    method + "(" + name + "): Too few inverse transformation functions given (" + ninv + ").\nAt least "
                            + nin + " inverse transformation functions must be supplied. ");
        }
    }

    /**
     * Loads a MathMap from its description, as C's {@code astLoadMathMap} does: the numbers of
     * forward and inverse functions (defaults the numbers of outputs and inputs of the MathMap as
     * created), each function (default empty), SimpFI and SimpIF, each re-applied through its
     * setter when set, and the Seed when the {@code Seeded} item says it was set, an unpredictable
     * default otherwise. The functions are then compiled. A description with fewer functions than
     * the MathMap's outputs or inputs is refused as the constructor refuses it.
     */
    MathMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("MathMap");
        int nin = createdNin();
        int nout = createdNout();
        int nfwd = part.readInt("Nfwd", nout);
        int ninv = part.readInt("Ninv", nin);
        checkCounts("astLoadMathMap", "MathMap", nin, nout, nfwd, ninv);
        fwdfun = new String[nfwd];
        for (int ifun = 0; ifun < nfwd; ifun++) {
            fwdfun[ifun] = part.readString("Fwd" + (ifun + 1), "");
        }
        invfun = new String[ninv];
        for (int ifun = 0; ifun < ninv; ifun++) {
            invfun[ifun] = part.readString("Inv" + (ifun + 1), "");
        }
        simpFiFlag = part.readInt("SimpFI", UNSET);
        if (simpFiFlag != UNSET) {
            storeSimpFI(simpFiFlag);
        }
        simpIfFlag = part.readInt("SimpIF", UNSET);
        if (simpIfFlag != UNSET) {
            storeSimpIF(simpIfFlag);
        }
        // C stores the Seed it reads and then re-applies it through its setter, which leaves the
        // record of simplification alone because the value is unchanged.
        if (part.readInt("Seeded", 0) != 0) {
            rcontext = new MathMapRandom(true, part.readInt("Seed", 0));
        } else {
            rcontext = new MathMapRandom();
        }
        compiled = MathMapParser.compileMapping("astLoadMathMap", "MathMap", nin, nout, fwdfun, invfun);
    }

    @Override
    String dumpComment() {
        return "Transformation using mathematical functions";
    }

    /**
     * Writes the numbers of forward and inverse functions (set when they differ from the numbers
     * of outputs and inputs of the MathMap as created), each function, SimpFI and SimpIF, and the
     * Seed with a flag saying whether it is set, as C's {@code Dump} (mathmap.c) does; an
     * attribute that is unset is written as a comment.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        int nin = createdNin();
        int nout = createdNout();
        int nfwd = fwdfun.length;
        int ninv = invfun.length;
        node.writeInt("Nfwd", nfwd != nout, false, nfwd, "Number of forward transformation functions");
        for (int ifun = 0; ifun < nfwd; ifun++) {
            node.writeString("Fwd" + (ifun + 1), true, true, fwdfun[ifun], "Forward function " + (ifun + 1));
        }
        node.writeInt("Ninv", ninv != nin, false, ninv, "Number of inverse transformation functions");
        for (int ifun = 0; ifun < ninv; ifun++) {
            node.writeString("Inv" + (ifun + 1), true, true, invfun[ifun], "Inverse function " + (ifun + 1));
        }
        boolean set = testSimpFI();
        int ival = set ? simpFiFlag : (getSimpFIValue() ? 1 : 0);
        node.writeInt(
                "SimpFI",
                set,
                false,
                ival,
                ival != 0 ? "Forward-inverse pairs may simplify" : "Forward-inverse pairs do not simplify");
        set = testSimpIF();
        ival = set ? simpIfFlag : (getSimpIFValue() ? 1 : 0);
        node.writeInt(
                "SimpIF",
                set,
                false,
                ival,
                ival != 0 ? "Inverse-forward pairs may simplify" : "Inverse-forward pairs do not simplify");
        set = rcontext.testSeed();
        ival = rcontext.getSeed();
        node.writeInt(
                "Seeded",
                set,
                false,
                set ? 1 : 0,
                set ? "Explicit random number seed set" : "No random number seed set");
        node.writeInt("Seed", set, false, ival, set ? "Random number seed value" : "Default random number seed used");
    }

    /**
     * Gives the copy its own random-number context, a copy of this one, as C's copy of the
     * MathMap structure does. The functions, which are never changed, are shared.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        rcontext = rcontext.copy();
    }

    // ------------------------------------------------------------------
    // The functions, as C holds them
    // ------------------------------------------------------------------

    /** The cleaned forward functions. */
    String[] fwdFunctions() {
        return fwdfun.clone();
    }

    /** The cleaned inverse functions. */
    String[] invFunctions() {
        return invfun.clone();
    }

    /**
     * The compiled forward and inverse functions. The arrays are shared with every copy of this
     * MathMap, as the functions never change, so callers must not modify them.
     */
    MathMapParser.Compiled compiledFunctions() {
        return compiled;
    }

    // ------------------------------------------------------------------
    // Equality and simplification
    // ------------------------------------------------------------------

    /**
     * Whether {@code that} is equivalent to this MathMap, as C's {@code astEqual} decides it: the
     * same Object, or, by C's {@code Equal} (mathmap.c), a MathMap with the same numbers of inputs
     * and outputs whose compiled functions for the effective forward direction, and then for the
     * effective inverse direction, taking each one's Invert attribute into account, match this
     * MathMap's ({@link #sameFunctions}).
     */
    // object.c astEqual compares the pointers first: the same object, not an equal one.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    boolean equalTo(Mapping that) {
        if (that == this) {
            return true;
        }
        if (!(that instanceof MathMap)) {
            return false;
        }
        MathMap other = (MathMap) that;
        if (other.getNout() != getNout() || other.getNin() != getNin()) {
            return false;
        }
        boolean result = true;
        for (int pass = 0; pass < 2 && result; pass++) {
            // The first pass compares the effective forward functions, the second the inverse.
            boolean thisFwd = (pass == 0) != getInvert();
            boolean thatFwd = (pass == 0) != other.getInvert();
            result = sameFunctions(
                    thisFwd ? fwdfun.length : invfun.length,
                    thisFwd ? compiled.fwdcode : compiled.invcode,
                    thisFwd ? compiled.fwdcon : compiled.invcon,
                    thatFwd ? other.fwdfun.length : other.invfun.length,
                    thatFwd ? other.compiled.fwdcode : other.compiled.invcode,
                    thatFwd ? other.compiled.fwdcon : other.compiled.invcon);
        }
        return result;
    }

    /**
     * The comparison of two sets of compiled functions in C's {@code Equal} (mathmap.c): the
     * numbers of functions must be the same, and for each function the numbers of opcodes (none
     * for a direction that is not defined); the opcodes are then compared in turn, and the constant
     * consumed by each opcode that loads a constant or a variable or takes a variable number of
     * arguments ({@code max}, {@code min}) is compared to within {@code astEQUAL}.
     *
     * <p>As C does, the comparison runs over the elements of the opcode array from the first, which
     * holds the number of opcodes, and stops before the last opcode, so that the last opcode of a
     * function is never compared ({@code x+1} matches {@code x-1}) and a number of opcodes equal to
     * the value of a constant-consuming opcode is taken to consume a constant. Where that leads
     * past the end of the constants, which C reads regardless, a constant that neither function has
     * matches and one that only one function has does not.
     */
    private static boolean sameFunctions(
            int thisNfun, int[][] thisCode, double[][] thisCon, int thatNfun, int[][] thatCode, double[][] thatCon) {
        if (thatNfun != thisNfun) {
            return false;
        }
        for (int ifun = 0; ifun < thisNfun; ifun++) {
            int ncode = thisCode != null ? thisCode[ifun][0] : 0;
            int ncodeThat = thatCode != null ? thatCode[ifun][0] : 0;
            if (ncode != ncodeThat) {
                return false;
            }
            int icon = 0;
            for (int icode = 0; icode < ncode; icode++) {
                int code = thisCode[ifun][icode];
                if (thatCode[ifun][icode] != code) {
                    return false;
                } else if (code == MathMapToken.OP_LDCON
                        || code == MathMapToken.OP_LDVAR
                        || code == MathMapToken.OP_MAX
                        || code == MathMapToken.OP_MIN) {
                    double[] a = thisCon[ifun];
                    double[] b = thatCon[ifun];
                    boolean inA = icon < a.length;
                    boolean inB = icon < b.length;
                    if (inA != inB || (inA && !MappingHelpers.astEqual(a[icon], b[icon]))) {
                        return false;
                    }
                    icon++;
                }
            }
        }
        return true;
    }

    /**
     * mathmap.c {@code MapMerge}. In series, a MathMap cancels with the MathMap after it in the
     * list, the pair becoming a UnitMap applied forward, when each allows it and they are each
     * other's inverse as text: the first's SimpFI (its SimpIF if the list applies it inverted) and
     * the second's SimpIF (its SimpFI if applied inverted) must both be set non-zero; the first's
     * number of inputs as applied must equal the second's number of outputs as applied; and the
     * cleaned functions of the first's effective forward direction must be those of the second's
     * effective inverse direction, and the other way round. The index returned is that of the
     * first of the pair.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        boolean simplify = series;
        int imap1 = where;
        int imap2 = imap1 + 1;
        if (simplify) {
            simplify = imap2 < list.size();
        }
        if (simplify) {
            simplify = "MathMap".equals(list.map(imap1).astClass())
                    && "MathMap".equals(list.map(imap2).astClass());
        }
        if (!simplify) {
            return -1;
        }
        MathMap mathmap1 = (MathMap) list.map(imap1);
        MathMap mathmap2 = (MathMap) list.map(imap2);
        boolean invert1 = list.invert(imap1);
        boolean invert2 = list.invert(imap2);
        simplify = (invert1 ? mathmap1.getSimpIFValue() : mathmap1.getSimpFIValue())
                && (invert2 ? mathmap2.getSimpFIValue() : mathmap2.getSimpIFValue());
        int nin1 = appliedNin(mathmap1, invert1);
        if (simplify) {
            simplify = nin1 == appliedNout(mathmap2, invert2);
        }
        if (simplify) {
            simplify = Arrays.equals(
                    invert1 ? mathmap1.invfun : mathmap1.fwdfun, invert2 ? mathmap2.fwdfun : mathmap2.invfun);
        }
        if (simplify) {
            simplify = Arrays.equals(
                    invert1 ? mathmap1.fwdfun : mathmap1.invfun, invert2 ? mathmap2.invfun : mathmap2.fwdfun);
        }
        if (!simplify) {
            return -1;
        }
        list.replacePairWith(imap1, new UnitMap(nin1), false);
        return imap1;
    }

    // ------------------------------------------------------------------
    // Seed, SimpFI and SimpIF
    // ------------------------------------------------------------------

    /**
     * Get random number seed for a MathMap. This attribute, which may take any integer value,
     * determines the sequence of random numbers produced by the random number functions in MathMap
     * expressions. It is set to an unpredictable default value when a MathMap is created, so that
     * by default each MathMap uses a different set of random numbers.
     *
     * <p>If required, you may set this Seed attribute to a value of your choosing in order to
     * produce repeatable behaviour from the random number functions. You may also enquire the Seed
     * value (e.g. if an initially unpredictable value has been used) and then use it to reproduce
     * the resulting sequence of random numbers, either from the same MathMap or from another one.
     *
     * <p>Clearing the Seed attribute gives it a new unpredictable default value.
     *
     * @return this object's Seed attribute
     */
    public int getSeed() {
        return getI("Seed");
    }

    /**
     * Set random number seed for a MathMap. This attribute, which may take any integer value,
     * determines the sequence of random numbers produced by the random number functions in MathMap
     * expressions. It is set to an unpredictable default value when a MathMap is created, so that
     * by default each MathMap uses a different set of random numbers.
     *
     * <p>If required, you may set this Seed attribute to a value of your choosing in order to
     * produce repeatable behaviour from the random number functions. You may also enquire the Seed
     * value (e.g. if an initially unpredictable value has been used) and then use it to reproduce
     * the resulting sequence of random numbers, either from the same MathMap or from another one.
     *
     * <p>Clearing the Seed attribute gives it a new unpredictable default value.
     *
     * @param seed the Seed attribute of this object
     */
    public void setSeed(int seed) {
        setI("Seed", seed);
    }

    /**
     * Get forward-inverse MathMap pairs simplify. This attribute should be set to a non-zero value
     * if applying a MathMap's forward transformation, followed immediately by the matching inverse
     * transformation will always restore the original set of coordinates. It indicates that AST
     * may replace such a sequence of operations by an identity Mapping (a UnitMap) if it is
     * encountered while simplifying a compound Mapping (e.g. using astSimplify).
     *
     * <p>By default, the SimpFI attribute is zero, so that AST will not perform this simplification
     * unless you have set SimpFI to indicate that it is safe to do so.
     *
     * <h4>Notes</h4>
     *
     * <ul>
     *   <li>For simplification to occur, the two MathMaps must be in series and be identical (with
     *       textually identical transformation functions). Functional equivalence is not
     *       sufficient.
     *   <li>The consent of both MathMaps is required before simplification can take place. If
     *       either has a SimpFI value of zero, then simplification will not occur.
     *   <li>The SimpFI attribute controls simplification only in the case where a MathMap's
     *       forward transformation is followed by the matching inverse transformation. It does not
     *       apply if an inverse transformation is followed by a forward transformation. This latter
     *       case is controlled by the SimpIF attribute.
     *   <li>The "forward" and "inverse" transformations referred to are those defined when the
     *       MathMap is created (corresponding to the "fwd" and "inv" parameters of its constructor
     *       function). If the MathMap is inverted (i.e. its Invert attribute is non-zero), then the
     *       role of the SimpFI and SimpIF attributes will be interchanged.
     * </ul>
     *
     * @return this object's SimpFI attribute
     */
    public boolean getSimpFI() {
        return getB("SimpFI");
    }

    /**
     * Set forward-inverse MathMap pairs simplify. This attribute should be set to a non-zero value
     * if applying a MathMap's forward transformation, followed immediately by the matching inverse
     * transformation will always restore the original set of coordinates, as {@link #getSimpFI}
     * describes.
     *
     * @param simpFI the SimpFI attribute of this object
     */
    public void setSimpFI(boolean simpFI) {
        setB("SimpFI", simpFI);
    }

    /**
     * Get inverse-forward MathMap pairs simplify. This attribute should be set to a non-zero value
     * if applying a MathMap's inverse transformation, followed immediately by the matching forward
     * transformation will always restore the original set of coordinates. It indicates that AST
     * may replace such a sequence of operations by an identity Mapping (a UnitMap) if it is
     * encountered while simplifying a compound Mapping (e.g. using astSimplify).
     *
     * <p>By default, the SimpIF attribute is zero, so that AST will not perform this simplification
     * unless you have set SimpIF to indicate that it is safe to do so.
     *
     * <h4>Notes</h4>
     *
     * <ul>
     *   <li>For simplification to occur, the two MathMaps must be in series and be identical (with
     *       textually identical transformation functions). Functional equivalence is not
     *       sufficient.
     *   <li>The consent of both MathMaps is required before simplification can take place. If
     *       either has a SimpIF value of zero, then simplification will not occur.
     *   <li>The SimpIF attribute controls simplification only in the case where a MathMap's
     *       inverse transformation is followed by the matching forward transformation. It does not
     *       apply if a forward transformation is followed by an inverse transformation. This latter
     *       case is controlled by the SimpFI attribute.
     *   <li>The "forward" and "inverse" transformations referred to are those defined when the
     *       MathMap is created (corresponding to the "fwd" and "inv" parameters of its constructor
     *       function). If the MathMap is inverted (i.e. its Invert attribute is non-zero), then the
     *       role of the SimpFI and SimpIF attributes will be interchanged.
     * </ul>
     *
     * @return this object's SimpIF attribute
     */
    public boolean getSimpIF() {
        return getB("SimpIF");
    }

    /**
     * Set inverse-forward MathMap pairs simplify. This attribute should be set to a non-zero value
     * if applying a MathMap's inverse transformation, followed immediately by the matching forward
     * transformation will always restore the original set of coordinates, as {@link #getSimpIF}
     * describes.
     *
     * @param simpIF the SimpIF attribute of this object
     */
    public void setSimpIF(boolean simpIF) {
        setB("SimpIF", simpIF);
    }

    /** The SimpFI attribute's value, false while unset (C's private {@code GetSimpFI}). */
    boolean getSimpFIValue() {
        return simpFiFlag != UNSET && simpFiFlag != 0;
    }

    /** The SimpIF attribute's value, false while unset (C's private {@code GetSimpIF}). */
    boolean getSimpIFValue() {
        return simpIfFlag != UNSET && simpIfFlag != 0;
    }

    /** Whether SimpFI is set. */
    boolean testSimpFI() {
        return simpFiFlag != UNSET;
    }

    /** Whether SimpIF is set. */
    boolean testSimpIF() {
        return simpIfFlag != UNSET;
    }

    /**
     * C's private {@code SetSimpFI}, an {@code astMAKE_SET1} attribute: a shared MathMap refuses
     * the change; any non-zero value is stored as 1; a value different from the one held discards
     * the record that the MathMap has been simplified.
     */
    private void storeSimpFI(int value) {
        checkSet1("SimpFI");
        int v = value != 0 ? 1 : 0;
        if (v != simpFiFlag) {
            clearIsSimple();
        }
        simpFiFlag = v;
    }

    /** As {@link #storeSimpFI}, for SimpIF. */
    private void storeSimpIF(int value) {
        checkSet1("SimpIF");
        int v = value != 0 ? 1 : 0;
        if (v != simpIfFlag) {
            clearIsSimple();
        }
        simpIfFlag = v;
    }

    /** Clears SimpFI, discarding the record of simplification ({@code astMAKE_CLEAR1}). */
    private void clearSimpFI() {
        checkClear1("SimpFI");
        clearIsSimple();
        simpFiFlag = UNSET;
    }

    /** Clears SimpIF, discarding the record of simplification ({@code astMAKE_CLEAR1}). */
    private void clearSimpIF() {
        checkClear1("SimpIF");
        clearIsSimple();
        simpIfFlag = UNSET;
    }

    /**
     * C's private {@code SetSeed}, an {@code astMAKE_SET1} attribute: a shared MathMap refuses the
     * change; setting a Seed that was unset, or a different value, discards the record that the
     * MathMap has been simplified; the generator restarts from the new seed when next used.
     */
    private void storeSeed(int value) {
        checkSet1("Seed");
        if (!rcontext.testSeed() || value != rcontext.getSeed()) {
            clearIsSimple();
        }
        rcontext.setSeed(value);
    }

    /**
     * Clears the Seed, discarding the record of simplification and giving the seed a new
     * unpredictable value ({@code astMAKE_CLEAR1}).
     */
    private void clearSeed() {
        checkClear1("Seed");
        clearIsSimple();
        rcontext.clearSeed();
    }

    @Override
    String getAttrib(String attrib) {
        if ("seed".equals(attrib)) {
            return Integer.toString(rcontext.getSeed());
        } else if ("simpfi".equals(attrib)) {
            return getSimpFIValue() ? "1" : "0";
        } else if ("simpif".equals(attrib)) {
            return getSimpIFValue() ? "1" : "0";
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        if ("seed".equals(attrib) && ival != null) {
            storeSeed(ival);
        } else if ("simpfi".equals(attrib) && ival != null) {
            storeSimpFI(ival);
        } else if ("simpif".equals(attrib) && ival != null) {
            storeSimpIF(ival);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        if ("seed".equals(attrib)) {
            return rcontext.testSeed();
        } else if ("simpfi".equals(attrib)) {
            return testSimpFI();
        } else if ("simpif".equals(attrib)) {
            return testSimpIF();
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("seed".equals(attrib)) {
            clearSeed();
        } else if ("simpfi".equals(attrib)) {
            clearSimpFI();
        } else if ("simpif".equals(attrib)) {
            clearSimpIF();
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * C's {@code Transform} (mathmap.c): each function of the direction applied is evaluated in
     * turn, the intermediate ones into workspace and the last ones into the output, each having
     * the inputs and the results of the functions before it to refer to. Where C tests an input
     * for {@link #AST__BAD}, an input that is not finite is also bad, the library's rule for such
     * values.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        int ncoordIn = in.length;
        int ncoordOut = forward ? getNout() : getNin();
        int[][] code = fwd ? compiled.fwdcode : compiled.invcode;
        double[][] con = fwd ? compiled.fwdcon : compiled.invcon;
        int stacksize = fwd ? compiled.fwdstack : compiled.invstack;
        int nfun = code.length;
        double[][] data = new double[ncoordIn + nfun][];
        int ndata = 0;
        for (int idata = 0; idata < ncoordIn; idata++) {
            double[] values = new double[npoint];
            for (int point = 0; point < npoint; point++) {
                double v = in[idata][point];
                values[point] = isBad(v) ? AST__BAD : v;
            }
            data[ndata++] = values;
        }
        for (int idata = 0; idata < nfun - ncoordOut; idata++) {
            data[ndata++] = new double[npoint];
        }
        for (int idata = 0; idata < ncoordOut; idata++) {
            data[ndata++] = out[idata];
        }
        for (int ifun = 0; ifun < nfun; ifun++) {
            MathMapEvaluator.evaluate(rcontext, npoint, data, code[ifun], con[ifun], stacksize, data[ifun + ncoordIn]);
        }
    }
}
