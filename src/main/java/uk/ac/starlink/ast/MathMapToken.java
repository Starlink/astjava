package uk.ac.starlink.ast;

/**
 * The operation codes of a compiled MathMap function and the symbols that may appear in a
 * MathMap expression, as C's {@code Oper} enum and {@code symbol} array (mathmap.c) define them.
 *
 * <p>Ported from Rust {@code mappings/mathmap/{token,opcode}.rs} and C {@code mathmap.c}. Rust
 * reads expressions with its own tokenizer; this class keeps C's table, which {@link
 * MathMapParser} searches as C's {@code CompileExpression} does.
 */
final class MathMapToken {

    private MathMapToken() {}

    // ------------------------------------------------------------------
    // Operation codes, in the order of C's Oper enum
    // ------------------------------------------------------------------

    /** Load a constant. */
    static final int OP_LDCON = 0;

    /** Load a variable. */
    static final int OP_LDVAR = 1;

    /** Load the bad value, {@code AST__BAD}. */
    static final int OP_LDBAD = 2;

    /** Load the number of decimal digits, {@code AST__DBL_DIG}. */
    static final int OP_LDDIG = 3;

    /** Load the relative precision, {@code DBL_EPSILON}. */
    static final int OP_LDEPS = 4;

    /** Load the largest value, {@code DBL_MAX}. */
    static final int OP_LDMAX = 5;

    /** Load the largest decimal exponent, {@code DBL_MAX_10_EXP}. */
    static final int OP_LDMAX10E = 6;

    /** Load the largest exponent, {@code DBL_MAX_EXP}. */
    static final int OP_LDMAXE = 7;

    /** Load the number of mantissa digits, {@code DBL_MANT_DIG}. */
    static final int OP_LDMDIG = 8;

    /** Load the smallest normalised value, {@code DBL_MIN}. */
    static final int OP_LDMIN = 9;

    /** Load the smallest decimal exponent, {@code DBL_MIN_10_EXP}. */
    static final int OP_LDMIN10E = 10;

    /** Load the smallest exponent, {@code DBL_MIN_EXP}. */
    static final int OP_LDMINE = 11;

    /** Load the floating-point radix, {@code FLT_RADIX}. */
    static final int OP_LDRAD = 12;

    /** Load the rounding mode, {@code FLT_ROUNDS}. */
    static final int OP_LDRND = 13;

    /** Load e, the base of natural logarithms. */
    static final int OP_LDE = 14;

    /** Load pi. */
    static final int OP_LDPI = 15;

    /** Absolute value. */
    static final int OP_ABS = 16;

    /** Inverse cosine (radians). */
    static final int OP_ACOS = 17;

    /** Inverse cosine (degrees). */
    static final int OP_ACOSD = 18;

    /** Inverse hyperbolic cosine. */
    static final int OP_ACOSH = 19;

    /** Inverse hyperbolic cotangent. */
    static final int OP_ACOTH = 20;

    /** Inverse hyperbolic cosecant. */
    static final int OP_ACSCH = 21;

    /** Inverse hyperbolic secant. */
    static final int OP_ASECH = 22;

    /** Inverse sine (radians). */
    static final int OP_ASIN = 23;

    /** Inverse sine (degrees). */
    static final int OP_ASIND = 24;

    /** Inverse hyperbolic sine. */
    static final int OP_ASINH = 25;

    /** Inverse tangent (radians). */
    static final int OP_ATAN = 26;

    /** Inverse tangent (degrees). */
    static final int OP_ATAND = 27;

    /** Inverse hyperbolic tangent. */
    static final int OP_ATANH = 28;

    /** Round up. */
    static final int OP_CEIL = 29;

    /** Cosine (radians). */
    static final int OP_COS = 30;

    /** Cosine (degrees). */
    static final int OP_COSD = 31;

    /** Hyperbolic cosine. */
    static final int OP_COSH = 32;

    /** Hyperbolic cotangent. */
    static final int OP_COTH = 33;

    /** Hyperbolic cosecant. */
    static final int OP_CSCH = 34;

    /** Exponential function. */
    static final int OP_EXP = 35;

    /** Round down. */
    static final int OP_FLOOR = 36;

    /** Integer part (round towards zero). */
    static final int OP_INT = 37;

    /** Test for the bad value. */
    static final int OP_ISBAD = 38;

    /** Natural logarithm. */
    static final int OP_LOG = 39;

    /** Base 10 logarithm. */
    static final int OP_LOG10 = 40;

    /** Fortran's NINT (round to nearest). */
    static final int OP_NINT = 41;

    /** Poisson random number. */
    static final int OP_POISS = 42;

    /** Hyperbolic secant. */
    static final int OP_SECH = 43;

    /** Sine (radians). */
    static final int OP_SIN = 44;

    /** Sinc function, {@code sin(x)/x}. */
    static final int OP_SINC = 45;

    /** Sine (degrees). */
    static final int OP_SIND = 46;

    /** Hyperbolic sine. */
    static final int OP_SINH = 47;

    /** Square. */
    static final int OP_SQR = 48;

    /** Square root. */
    static final int OP_SQRT = 49;

    /** Tangent (radians). */
    static final int OP_TAN = 50;

    /** Tangent (degrees). */
    static final int OP_TAND = 51;

    /** Hyperbolic tangent. */
    static final int OP_TANH = 52;

    /** Inverse tangent of two arguments (radians). */
    static final int OP_ATAN2 = 53;

    /** Inverse tangent of two arguments (degrees). */
    static final int OP_ATAN2D = 54;

    /** Fortran's DIM (positive difference). */
    static final int OP_DIM = 55;

    /** Gaussian random number. */
    static final int OP_GAUSS = 56;

    /** Modulus function. */
    static final int OP_MOD = 57;

    /** Raise to a power. */
    static final int OP_POW = 58;

    /** Uniformly distributed random number. */
    static final int OP_RAND = 59;

    /** Transfer of sign. */
    static final int OP_SIGN = 60;

    /** C's "question mark" operator, {@code a?b:c}. */
    static final int OP_QIF = 61;

    /** Maximum of two or more values. */
    static final int OP_MAX = 62;

    /** Minimum of two or more values. */
    static final int OP_MIN = 63;

    /** Negate. */
    static final int OP_NEG = 64;

    /** Boolean NOT. */
    static final int OP_NOT = 65;

    /** Add. */
    static final int OP_ADD = 66;

    /** Divide. */
    static final int OP_DIV = 67;

    /** Multiply. */
    static final int OP_MUL = 68;

    /** Subtract. */
    static final int OP_SUB = 69;

    /** Shift bits left. */
    static final int OP_SHFTL = 70;

    /** Shift bits right. */
    static final int OP_SHFTR = 71;

    /** Relational equal. */
    static final int OP_EQ = 72;

    /** Greater than or equal. */
    static final int OP_GE = 73;

    /** Greater than. */
    static final int OP_GT = 74;

    /** Less than or equal. */
    static final int OP_LE = 75;

    /** Less than. */
    static final int OP_LT = 76;

    /** Not equal. */
    static final int OP_NE = 77;

    /** Bit-wise AND. */
    static final int OP_BITAND = 78;

    /** Bit-wise OR. */
    static final int OP_BITOR = 79;

    /** Bit-wise exclusive OR. */
    static final int OP_BITXOR = 80;

    /** Boolean AND. */
    static final int OP_AND = 81;

    /** Fortran's logical {@code .EQV.}. */
    static final int OP_EQV = 82;

    /** Boolean OR. */
    static final int OP_OR = 83;

    /** Boolean exclusive OR. */
    static final int OP_XOR = 84;

    /** Null operation. */
    static final int OP_NULL = 85;

    // ------------------------------------------------------------------
    // The symbol table
    // ------------------------------------------------------------------

    /** A symbol which may appear in an expression, C's {@code Symbol} structure. */
    static final class Symbol {

        /** The symbol's text as it appears in expressions; empty for a constant or variable. */
        final String text;

        /** Whether the symbol is an operator when seen from the left. */
        final boolean operleft;

        /** Whether the symbol is an operator when seen from the right. */
        final boolean operright;

        /** Whether the symbol may be followed by a unary {@code +} or {@code -}. */
        final boolean unarynext;

        /** Whether the symbol is a unary {@code +} or {@code -}. */
        final boolean unaryoper;

        /** The symbol's priority seen from the left. */
        final int leftpriority;

        /** The symbol's priority seen from the right. */
        final int rightpriority;

        /** The change in parenthesis level. */
        final int parincrement;

        /** The change in evaluation stack size. */
        final int stackincrement;

        /**
         * The number of function arguments: 0 for a symbol that is not a function, minus the least
         * number for a function taking a variable number.
         */
        final int nargs;

        /** The operation code the symbol gives. */
        final int opcode;

        Symbol(
                String text,
                int operleft,
                int operright,
                int unarynext,
                int unaryoper,
                int leftpriority,
                int rightpriority,
                int parincrement,
                int stackincrement,
                int nargs,
                int opcode) {
            this.text = text;
            this.operleft = operleft != 0;
            this.operright = operright != 0;
            this.unarynext = unarynext != 0;
            this.unaryoper = unaryoper != 0;
            this.leftpriority = leftpriority;
            this.rightpriority = rightpriority;
            this.parincrement = parincrement;
            this.stackincrement = stackincrement;
            this.nargs = nargs;
            this.opcode = opcode;
        }

        /** The length of the symbol's text, C's {@code size}. */
        int size() {
            return text.length();
        }
    }

    /**
     * Every symbol, as C's {@code symbol} array lists them (without its end marker): a symbol's
     * index here is its symbol number.
     */
    private static final Symbol[] SYMBOLS = {
        // User-supplied constants and variables.
        new Symbol("", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDCON),
        new Symbol("", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDVAR),

        // System constants.
        new Symbol("<bad>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDBAD),
        new Symbol("<dig>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDDIG),
        new Symbol("<epsilon>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDEPS),
        new Symbol("<mant_dig>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMDIG),
        new Symbol("<max>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMAX),
        new Symbol("<max_10_exp>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMAX10E),
        new Symbol("<max_exp>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMAXE),
        new Symbol("<min>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMIN),
        new Symbol("<min_10_exp>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMIN10E),
        new Symbol("<min_exp>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDMINE),
        new Symbol("<radix>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDRAD),
        new Symbol("<rounds>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDRND),

        // Mathematical constants.
        new Symbol("<e>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDE),
        new Symbol("<pi>", 0, 0, 0, 0, 19, 19, 0, 1, 0, OP_LDPI),

        // Functions with one argument.
        new Symbol("abs(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ABS),
        new Symbol("acos(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ACOS),
        new Symbol("acosd(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ACOSD),
        new Symbol("acosh(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ACOSH),
        new Symbol("acoth(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ACOTH),
        new Symbol("acsch(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ACSCH),
        new Symbol("aint(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_INT),
        new Symbol("asech(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ASECH),
        new Symbol("asin(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ASIN),
        new Symbol("asind(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ASIND),
        new Symbol("asinh(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ASINH),
        new Symbol("atan(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ATAN),
        new Symbol("atand(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ATAND),
        new Symbol("atanh(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ATANH),
        new Symbol("ceil(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_CEIL),
        new Symbol("cos(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_COS),
        new Symbol("cosd(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_COSD),
        new Symbol("cosh(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_COSH),
        new Symbol("coth(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_COTH),
        new Symbol("csch(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_CSCH),
        new Symbol("exp(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_EXP),
        new Symbol("fabs(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ABS),
        new Symbol("floor(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_FLOOR),
        new Symbol("int(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_INT),
        new Symbol("isbad(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_ISBAD),
        new Symbol("log(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_LOG),
        new Symbol("log10(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_LOG10),
        new Symbol("nint(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_NINT),
        new Symbol("poisson(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_POISS),
        new Symbol("sech(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SECH),
        new Symbol("sin(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SIN),
        new Symbol("sinc(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SINC),
        new Symbol("sind(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SIND),
        new Symbol("sinh(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SINH),
        new Symbol("sqr(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SQR),
        new Symbol("sqrt(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_SQRT),
        new Symbol("tan(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_TAN),
        new Symbol("tand(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_TAND),
        new Symbol("tanh(", 0, 1, 1, 0, 19, 1, 1, 0, 1, OP_TANH),

        // Functions with two arguments.
        new Symbol("atan2(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_ATAN2),
        new Symbol("atan2d(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_ATAN2D),
        new Symbol("dim(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_DIM),
        new Symbol("fmod(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_MOD),
        new Symbol("gauss(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_GAUSS),
        new Symbol("mod(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_MOD),
        new Symbol("pow(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_POW),
        new Symbol("rand(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_RAND),
        new Symbol("sign(", 0, 1, 1, 0, 19, 1, 1, -1, 2, OP_SIGN),

        // Functions with three arguments.
        new Symbol("qif(", 0, 1, 1, 0, 19, 1, 1, -2, 3, OP_QIF),

        // Functions with variable numbers of arguments.
        new Symbol("max(", 0, 1, 1, 0, 19, 1, 1, -1, -2, OP_MAX),
        new Symbol("min(", 0, 1, 1, 0, 19, 1, 1, -1, -2, OP_MIN),

        // Parenthesised expressions.
        new Symbol(")", 1, 0, 0, 0, 2, 19, -1, 0, 0, OP_NULL),
        new Symbol("(", 0, 1, 1, 0, 19, 1, 1, 0, 0, OP_NULL),

        // Unary arithmetic operators.
        new Symbol("+", 0, 1, 1, 1, 17, 16, 0, 0, 0, OP_NULL),
        new Symbol("-", 0, 1, 1, 1, 17, 16, 0, 0, 0, OP_NEG),

        // Unary boolean operators.
        new Symbol("!", 0, 1, 1, 0, 17, 16, 0, 0, 0, OP_NOT),
        new Symbol(".not.", 0, 1, 1, 0, 17, 16, 0, 0, 0, OP_NOT),

        // Binary arithmetic operators.
        new Symbol("**", 1, 1, 1, 0, 18, 15, 0, -1, 0, OP_POW),
        new Symbol("*", 1, 1, 1, 0, 14, 14, 0, -1, 0, OP_MUL),
        new Symbol("/", 1, 1, 1, 0, 14, 14, 0, -1, 0, OP_DIV),
        new Symbol("+", 1, 1, 1, 0, 13, 13, 0, -1, 0, OP_ADD),
        new Symbol("-", 1, 1, 1, 0, 13, 13, 0, -1, 0, OP_SUB),

        // Bit-shift operators.
        new Symbol("<<", 1, 1, 1, 0, 12, 12, 0, -1, 0, OP_SHFTL),
        new Symbol(">>", 1, 1, 1, 0, 12, 12, 0, -1, 0, OP_SHFTR),

        // Relational operators.
        new Symbol("<", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_LT),
        new Symbol(".lt.", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_LT),
        new Symbol("<=", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_LE),
        new Symbol(".le.", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_LE),
        new Symbol(">", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_GT),
        new Symbol(".gt.", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_GT),
        new Symbol(">=", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_GE),
        new Symbol(".ge.", 1, 1, 1, 0, 11, 11, 0, -1, 0, OP_GE),
        new Symbol("==", 1, 1, 1, 0, 10, 10, 0, -1, 0, OP_EQ),
        new Symbol(".eq.", 1, 1, 1, 0, 10, 10, 0, -1, 0, OP_EQ),
        new Symbol("!=", 1, 1, 1, 0, 10, 10, 0, -1, 0, OP_NE),
        new Symbol(".ne.", 1, 1, 1, 0, 10, 10, 0, -1, 0, OP_NE),

        // Bit-wise operators.
        new Symbol("&", 1, 1, 1, 0, 9, 9, 0, -1, 0, OP_BITAND),
        new Symbol("^", 1, 1, 1, 0, 8, 8, 0, -1, 0, OP_BITXOR),
        new Symbol("|", 1, 1, 1, 0, 7, 7, 0, -1, 0, OP_BITOR),

        // Binary boolean operators.
        new Symbol("&&", 1, 1, 1, 0, 6, 6, 0, -1, 0, OP_AND),
        new Symbol(".and.", 1, 1, 1, 0, 6, 6, 0, -1, 0, OP_AND),
        new Symbol("^^", 1, 1, 1, 0, 5, 5, 0, -1, 0, OP_XOR),
        new Symbol("||", 1, 1, 1, 0, 4, 4, 0, -1, 0, OP_OR),
        new Symbol(".or.", 1, 1, 1, 0, 4, 4, 0, -1, 0, OP_OR),
        new Symbol(".eqv.", 1, 1, 1, 0, 3, 3, 0, -1, 0, OP_EQV),
        new Symbol(".neqv.", 1, 1, 1, 0, 3, 3, 0, -1, 0, OP_XOR),
        new Symbol(".xor.", 1, 1, 1, 0, 3, 3, 0, -1, 0, OP_XOR),

        // Separators.
        new Symbol(",", 1, 1, 1, 0, 2, 2, 0, 0, 0, OP_NULL),
    };

    /** The symbol number of "load a constant", C's {@code symbol_ldcon}. */
    static final int SYMBOL_LDCON = 0;

    /** The symbol number of "load a variable", C's {@code symbol_ldvar}. */
    static final int SYMBOL_LDVAR = 1;

    /** The number of symbols. */
    static int count() {
        return SYMBOLS.length;
    }

    /** The symbol with number {@code sym}. */
    static Symbol symbol(int sym) {
        return SYMBOLS[sym];
    }
}
