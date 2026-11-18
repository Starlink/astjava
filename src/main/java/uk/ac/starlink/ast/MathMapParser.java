package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * Checks and compiles the transformation functions of a MathMap, as C's {@code CleanFunctions},
 * {@code CompileMapping}, {@code ExtractVariables}, {@code ExtractExpressions}, {@code
 * CompileExpression}, {@code ValidateSymbol}, {@code ParseConstant}, {@code ParseName}, {@code
 * ParseVariable} and {@code EvaluationSort} (mathmap.c) do.
 *
 * <p>Each function compiles to C's form: an array of operation codes ({@link MathMapToken}) whose
 * first element is the number that follow, and an array of the constants the codes consume, in
 * the order they consume them. A function is evaluated on a push-down stack by {@link
 * MathMapEvaluator}.
 *
 * <p>Ported from Rust {@code mappings/mathmap/{mod,token,parser}.rs} and C {@code mathmap.c}.
 * Rust reads an expression with a tokenizer and a precedence-climbing parser; this class follows
 * C, which identifies each symbol with its table and sorts the symbols into evaluation order with
 * an operator stack. Both give the same opcodes and constants.
 */
final class MathMapParser {

    private MathMapParser() {}

    /**
     * The compiled forward and inverse functions of a MathMap. A direction whose functions have no
     * right-hand sides is undefined, and has null arrays.
     */
    static final class Compiled {

        /** The opcodes of each forward function (C's {@code fwdcode}), or null. */
        final int[][] fwdcode;

        /** The opcodes of each inverse function (C's {@code invcode}), or null. */
        final int[][] invcode;

        /** The constants of each forward function (C's {@code fwdcon}), or null. */
        final double[][] fwdcon;

        /** The constants of each inverse function (C's {@code invcon}), or null. */
        final double[][] invcon;

        /** The evaluation stack size the forward functions need (C's {@code fwdstack}). */
        final int fwdstack;

        /** The evaluation stack size the inverse functions need (C's {@code invstack}). */
        final int invstack;

        Compiled(int[][] fwdcode, int[][] invcode, double[][] fwdcon, double[][] invcon, int fwdstack, int invstack) {
            this.fwdcode = fwdcode;
            this.invcode = invcode;
            this.fwdcon = fwdcon;
            this.invcon = invcon;
            this.fwdstack = fwdstack;
            this.invstack = invstack;
        }
    }

    /** One compiled expression, C's {@code code}, {@code con} and {@code stacksize}. */
    private static final class Expression {
        final int[] code;
        final double[] con;
        final int stacksize;

        Expression(int[] code, double[] con, int stacksize) {
            this.code = code;
            this.con = con;
            this.stacksize = stacksize;
        }
    }

    // ------------------------------------------------------------------
    // Character classes of the C locale
    // ------------------------------------------------------------------

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAlnum(char c) {
        return isDigit(c) || isAlpha(c);
    }

    /** The character of {@code s} at {@code i}, or C's terminating nul beyond its end. */
    private static char at(String s, int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    // ------------------------------------------------------------------
    // CleanFunctions
    // ------------------------------------------------------------------

    /**
     * A copy of each function with its white space removed and its letters in lower case, as C's
     * {@code CleanFunctions} makes it.
     */
    static String[] cleanFunctions(String[] fun) {
        String[] clean = new String[fun.length];
        for (int ifun = 0; ifun < fun.length; ifun++) {
            String f = fun[ifun];
            StringBuilder sb = new StringBuilder(f.length());
            for (int i = 0; i < f.length(); i++) {
                char c = f.charAt(i);
                if (!CScan.isCSpace(c)) {
                    sb.append(CScan.toLower(c));
                }
            }
            clean[ifun] = sb.toString();
        }
        return clean;
    }

    // ------------------------------------------------------------------
    // CompileMapping
    // ------------------------------------------------------------------

    /**
     * Compiles the cleaned functions of a MathMap, as C's {@code CompileMapping} does. The input
     * variables of the forward functions are named by the left-hand sides of the last {@code nin}
     * inverse functions, and those of the inverse functions by the last {@code nout} forward
     * functions; each function may also use the variables the functions before it define.
     *
     * @param method the method named in error messages
     * @param cls the class named in error messages
     * @param fwdfun the cleaned forward functions, at least {@code nout}
     * @param invfun the cleaned inverse functions, at least {@code nin}
     * @throws AstException with C's status and messages if a function is invalid
     */
    static Compiled compileMapping(String method, String cls, int nin, int nout, String[] fwdfun, String[] invfun) {
        int nfwd = fwdfun.length;
        int ninv = invfun.length;

        // The forward transformation.
        String[] strings = new String[nin + nfwd];
        int nvar = 0;
        for (int ifun = ninv - nin; ifun < ninv; ifun++) {
            strings[nvar++] = invfun[ifun];
        }
        for (int ifun = 0; ifun < nfwd; ifun++) {
            strings[nvar++] = fwdfun[ifun];
        }
        String[] var = extractVariables(method, cls, strings, nin, nout, nfwd, ninv, true);
        String[] exprs = extractExpressions(method, cls, fwdfun, true);
        int[][] fwdcode = null;
        double[][] fwdcon = null;
        int fwdstack = 0;
        if (exprs != null) {
            fwdcode = new int[nfwd][];
            fwdcon = new double[nfwd][];
            for (int ifun = 0; ifun < nfwd; ifun++) {
                Expression e = compileFunction(method, cls, exprs[ifun], nin + ifun, var, "forward", ifun);
                fwdcode[ifun] = e.code;
                fwdcon[ifun] = e.con;
                fwdstack = Math.max(fwdstack, e.stacksize);
            }
        }

        // The inverse transformation.
        strings = new String[nout + ninv];
        nvar = 0;
        for (int ifun = nfwd - nout; ifun < nfwd; ifun++) {
            strings[nvar++] = fwdfun[ifun];
        }
        for (int ifun = 0; ifun < ninv; ifun++) {
            strings[nvar++] = invfun[ifun];
        }
        var = extractVariables(method, cls, strings, nin, nout, nfwd, ninv, false);
        exprs = extractExpressions(method, cls, invfun, false);
        int[][] invcode = null;
        double[][] invcon = null;
        int invstack = 0;
        if (exprs != null) {
            invcode = new int[ninv][];
            invcon = new double[ninv][];
            for (int ifun = 0; ifun < ninv; ifun++) {
                Expression e = compileFunction(method, cls, exprs[ifun], nout + ifun, var, "inverse", ifun);
                invcode[ifun] = e.code;
                invcon[ifun] = e.con;
                invstack = Math.max(invstack, e.stacksize);
            }
        }
        return new Compiled(fwdcode, invcode, fwdcon, invcon, fwdstack, invstack);
    }

    /**
     * {@link #compileExpression} of one function, whose errors gain C's context line naming the
     * direction and the function's number.
     */
    private static Expression compileFunction(
            String method, String cls, String expr, int nvar, String[] var, String dir, int ifun) {
        try {
            return compileExpression(method, cls, expr, nvar, var);
        } catch (AstException e) {
            throw Ast.withContext(e, "Error in " + dir + " transformation function " + (ifun + 1) + ".");
        }
    }

    // ------------------------------------------------------------------
    // ExtractVariables and ExtractExpressions
    // ------------------------------------------------------------------

    /**
     * The direction, as the caller knows it, of the function of entry {@code ifun} of the list
     * {@link #extractVariables} checks: the list starts with the {@code nprimary} functions of the
     * other direction that name its input variables.
     */
    private static String userDirection(int ifun, int nprimary, boolean forward) {
        return (ifun < nprimary) != forward ? "forward" : "inverse";
    }

    /**
     * The number, as the caller knows it, of the function of entry {@code ifun} of the list {@link
     * #extractVariables} checks: the {@code nprimary} functions of the other direction at its start
     * follow {@code nextra} intermediate functions there.
     */
    private static int userNumber(int ifun, int nprimary, int nextra) {
        return ifun < nprimary ? ifun + 1 + nextra : ifun + 1 - nprimary;
    }

    /**
     * The variable names on the left-hand sides of {@code fun}, as C's {@code ExtractVariables}
     * finds them: the text before each first {@code =}, or the whole function if it has none,
     * which must be a name, and no two of which may be the same.
     *
     * @param fun the {@code nprimary} functions of the other direction that name the input
     *     variables, then every function of the direction being compiled
     * @throws AstException {@code AST__MISVN}, {@code AST__VARIN} or {@code AST__DUVAR}
     */
    private static String[] extractVariables(
            String method, String cls, String[] fun, int nin, int nout, int nfwd, int ninv, boolean forward) {
        int nfun = fun.length;
        int nprimary = forward ? nin : nout;
        int nextra = forward ? ninv - nin : nfwd - nout;
        String[] var = new String[nfun];
        for (int ifun = 0; ifun < nfun; ifun++) {
            String f = fun[ifun];
            int nc = f.indexOf('=');
            if (nc < 0) {
                nc = f.length();
            }
            String message = null;
            int status = 0;
            if (nc == 0) {
                status = AstException.AST__MISVN;
                if (nc < f.length()) {
                    message = method + "(" + cls + "): No left hand side in expression: \"" + f + "\".";
                } else {
                    message = method + ": Transformation function contains no variable name.";
                }
            } else {
                var[ifun] = f.substring(0, nc);
                int iend = parseName(var[ifun], 0);
                if (iend < 0 || iend + 1 < var[ifun].length()) {
                    status = AstException.AST__VARIN;
                    message = method + "(" + cls + "): Variable name is invalid: \"" + var[ifun] + "\".";
                }
            }
            if (message != null) {
                throw Ast.err(
                        status,
                        message + "\nError in " + userDirection(ifun, nprimary, forward) + " transformation function "
                                + userNumber(ifun, nprimary, nextra) + ".");
            }
        }
        for (int i1 = 1; i1 < nfun; i1++) {
            for (int i2 = 0; i2 < i1; i2++) {
                if (var[i1].equals(var[i2])) {
                    throw Ast.err(
                            AstException.AST__DUVAR,
                            method + "(" + cls + "): Duplicate definition of variable name: \"" + var[i1] + "\".\n"
                                    + "Conflict between " + userDirection(i1, nprimary, forward) + " function "
                                    + userNumber(i1, nprimary, nextra) + " and " + userDirection(i2, nprimary, forward)
                                    + " function " + userNumber(i2, nprimary, nextra) + ".");
                }
            }
        }
        return var;
    }

    /**
     * The right-hand sides of {@code fun}, the text after each first {@code =}, as C's {@code
     * ExtractExpressions} finds them; null if no function has an {@code =}, which leaves the
     * transformation undefined.
     *
     * @throws AstException {@code AST__NORHS} if a function's {@code =} has nothing after it, or
     *     some but not all functions have an {@code =}
     */
    private static String[] extractExpressions(String method, String cls, String[] fun, boolean forward) {
        String dir = forward ? "forward" : "inverse";
        int nfun = fun.length;
        String[] exprs = new String[nfun];
        int nud = 0;
        int iud = 0;
        for (int ifun = 0; ifun < nfun; ifun++) {
            int eq = fun[ifun].indexOf('=');
            if (eq >= 0) {
                if (eq + 1 < fun[ifun].length()) {
                    exprs[ifun] = fun[ifun].substring(eq + 1);
                } else {
                    throw Ast.err(
                            AstException.AST__NORHS,
                            method + "(" + cls + "): Missing right hand side in expression: \"" + fun[ifun]
                                    + "\".\nError in " + dir + " transformation function " + (ifun + 1) + ".");
                }
            } else {
                nud++;
                if (nud == 1) {
                    iud = ifun;
                }
            }
        }
        if (nud != 0 && nud != nfun) {
            throw Ast.err(
                    AstException.AST__NORHS,
                    method + "(" + cls + "): Missing right hand side in function: \"" + fun[iud] + "\".\nError in "
                            + dir + " transformation function " + (iud + 1) + ".");
        }
        return nud != 0 ? null : exprs;
    }

    // ------------------------------------------------------------------
    // CompileExpression
    // ------------------------------------------------------------------

    /** The expression up to and including index {@code iend}, as C's {@code "%.*s"} quotes it. */
    private static String through(String exprs, int iend) {
        return exprs.substring(0, Math.min(iend + 1, exprs.length()));
    }

    /**
     * The parenthesis level, the argument count at each level and the symbol that opened each
     * level, which C's {@code ValidateSymbol} keeps for {@code CompileExpression}, together with the
     * constants found so far.
     */
    private static final class Context {
        int lpar;
        int[] argcount = new int[4];
        int[] opensym = new int[4];
        double[] con = new double[4];
        int ncon;

        void addConstant(double value) {
            if (ncon == con.length) {
                con = Arrays.copyOf(con, ncon * 2);
            }
            con[ncon++] = value;
        }
    }

    /**
     * Compiles one expression, as C's {@code CompileExpression} does: each symbol is identified
     * from the symbol table, looking for the longest text that may stand where it does (an
     * operator or an operand, according to the symbol before it, and a unary operator only after
     * a symbol that allows one), and checked against the parentheses and function arguments so
     * far; text that matches no symbol is parsed as a constant and then as a variable. The symbols
     * are then sorted into evaluation order.
     *
     * @param exprs the expression, in lower case without white space
     * @param nvar the number of variables of {@code var} the expression may use
     * @throws AstException with C's status and message if the expression is invalid
     */
    private static Expression compileExpression(String method, String cls, String exprs, int nvar, String[] var) {
        Context ctx = new Context();
        int[] symlist = new int[Math.max(1, exprs.length())];
        int nsym = 0;
        boolean opernext = false;
        boolean unarynext = true;
        int nsymbols = MathMapToken.count();
        int iend = -1;
        for (int istart = 0; istart < exprs.length(); istart = iend + 1) {
            boolean found = false;
            int size = 0;
            int sym = 0;
            for (int isym = 0; isym < nsymbols; isym++) {
                MathMapToken.Symbol s = MathMapToken.symbol(isym);
                if (s.size() != 0
                        && s.operleft == opernext
                        && (!s.unaryoper || unarynext)
                        && exprs.startsWith(s.text, istart)) {
                    found = true;
                    if (s.size() > size) {
                        sym = isym;
                        size = s.size();
                        iend = istart + size - 1;
                    }
                }
            }
            if (found) {
                validateSymbol(method, cls, exprs, iend, sym, ctx);
            } else if (opernext) {
                throw Ast.err(
                        AstException.AST__MIOPR,
                        method + "(" + cls + "): Missing or invalid operator in the expression \""
                                + through(exprs, istart) + "\".");
            } else {
                double[] value = new double[1];
                iend = parseConstant(method, cls, exprs, istart, value);
                if (iend >= istart) {
                    sym = MathMapToken.SYMBOL_LDCON;
                    ctx.addConstant(value[0]);
                } else {
                    int[] ivar = new int[1];
                    iend = parseVariable(method, cls, exprs, istart, nvar, var, ivar);
                    if (ivar[0] != -1) {
                        sym = MathMapToken.SYMBOL_LDVAR;
                        ctx.addConstant(ivar[0]);
                    } else {
                        throw Ast.err(
                                AstException.AST__MIOPA,
                                method + "(" + cls + "): Missing or invalid operand in the expression \""
                                        + through(exprs, istart) + "\".");
                    }
                }
            }
            MathMapToken.Symbol s = MathMapToken.symbol(sym);
            opernext = !s.operright;
            unarynext = s.unarynext;
            if (nsym == symlist.length) {
                symlist = Arrays.copyOf(symlist, nsym * 2);
            }
            symlist[nsym++] = sym;
        }
        if (!opernext) {
            throw Ast.err(
                    AstException.AST__MIOPA,
                    method + "(" + cls + "): Missing or invalid operand in the expression \"" + exprs + "\".");
        } else if (ctx.lpar > 0) {
            throw Ast.err(
                    AstException.AST__MRPAR,
                    method + "(" + cls + "): Missing right parenthesis in the expression \"" + exprs + "\".");
        }
        double[] con = Arrays.copyOf(ctx.con, ctx.ncon);
        int[] stacksize = new int[1];
        int[] code = evaluationSort(con, nsym, symlist, stacksize);
        return new Expression(code, con, stacksize[0]);
    }

    /**
     * Checks a standard symbol against the parentheses and function arguments so far, as C's
     * {@code ValidateSymbol} does: a comma must separate the arguments of a function, a closing
     * parenthesis needs an opening one, and a function call must close with the number of
     * arguments the function takes. Closing the call of a function that takes a variable number of
     * arguments adds the number given to the constants.
     *
     * @param iend the index in {@code exprs} of the symbol's last character
     * @throws AstException {@code AST__COMIN}, {@code AST__MLPAR} or {@code AST__WRNFA}
     */
    private static void validateSymbol(String method, String cls, String exprs, int iend, int sym, Context ctx) {
        MathMapToken.Symbol s = MathMapToken.symbol(sym);
        if (",".equals(s.text)) {
            if (ctx.lpar <= 0 || ctx.argcount[ctx.lpar - 1] == 0) {
                throw Ast.err(
                        AstException.AST__COMIN,
                        method + "(" + cls + "): Spurious comma encountered in the expression \"" + through(exprs, iend)
                                + "\".");
            }
            ctx.argcount[ctx.lpar - 1]++;
        } else if (s.parincrement > 0) {
            if (ctx.lpar == ctx.argcount.length) {
                ctx.argcount = Arrays.copyOf(ctx.argcount, ctx.lpar * 2);
                ctx.opensym = Arrays.copyOf(ctx.opensym, ctx.lpar * 2);
            }
            ctx.argcount[++ctx.lpar - 1] = s.nargs != 0 ? 1 : 0;
            ctx.opensym[ctx.lpar - 1] = sym;
        } else if (s.parincrement < 0) {
            if (ctx.lpar == 0) {
                throw Ast.err(
                        AstException.AST__MLPAR,
                        method + "(" + cls + "): Missing left parenthesis in the expression \"" + through(exprs, iend)
                                + "\".");
            }
            int nargs = MathMapToken.symbol(ctx.opensym[ctx.lpar - 1]).nargs;
            if (nargs > 0) {
                if (ctx.argcount[ctx.lpar - 1] != nargs) {
                    throw Ast.err(
                            AstException.AST__WRNFA,
                            method + "(" + cls + "): Wrong number of function arguments in the expression \""
                                    + through(exprs, iend) + "\".");
                }
                ctx.lpar--;
            } else if (nargs < 0) {
                if (ctx.argcount[ctx.lpar - 1] < -nargs) {
                    throw Ast.err(
                            AstException.AST__WRNFA,
                            method + "(" + cls + "): Insufficient function arguments in the expression \""
                                    + through(exprs, iend) + "\".");
                }
                ctx.addConstant(ctx.argcount[--ctx.lpar]);
            } else {
                ctx.lpar--;
            }
        }
    }

    /**
     * Parses the numeric constant that starts at {@code istart}, as C's {@code ParseConstant} does.
     * Text starting with a digit or a decimal point is meant as a constant; it extends while each
     * character may continue it (digits, one decimal point before any exponent, one {@code d} or
     * {@code e} exponent character after a digit, and one sign directly after it), and is invalid
     * if a decimal point, exponent character or sign cannot continue it, except that a sign after
     * a digit ends it. Every field must hold a digit.
     *
     * @param con receives the value in its first element
     * @return the index of the constant's last character, or -1 if the text does not start with a
     *     digit or a decimal point
     * @throws AstException {@code AST__CONIN} if the constant is invalid
     */
    private static int parseConstant(String method, String cls, String exprs, int istart, double[] con) {
        con[0] = 0.0;
        char c = at(exprs, istart);
        boolean numeral = isDigit(c);
        boolean dpoint = c == '.';
        if (!(numeral || dpoint)) {
            return -1;
        }
        boolean expon = false;
        boolean sign = false;
        boolean valid = true;
        int iend = istart;
        boolean iscon = true;
        while ((c = at(exprs, iend + 1)) != '\0' && iscon) {
            iscon = false;
            if (isDigit(c)) {
                numeral = true;
                iscon = true;
            } else if (c == '.') {
                if (!(dpoint || expon)) {
                    dpoint = true;
                    iscon = true;
                } else {
                    valid = false;
                }
            } else if (c == 'd' || c == 'e') {
                if (!expon && numeral) {
                    expon = true;
                    numeral = false;
                    iscon = true;
                } else {
                    valid = false;
                }
            } else if (c == '+' || c == '-') {
                if (expon && !sign && !numeral) {
                    sign = true;
                    iscon = true;
                } else if (!numeral) {
                    valid = false;
                }
            }
            if (iscon || !valid) {
                iend++;
            }
        }
        valid = valid && numeral;
        if (valid) {
            // C reads the constant with sscanf after changing 'd' to 'e'; every character must be
            // read.
            String str = exprs.substring(istart, iend + 1).replace('d', 'e');
            CScan.Num n = CScan.strtod(str, 0);
            if (n == null || n.end < str.length()) {
                valid = false;
            } else {
                con[0] = n.value;
            }
        }
        if (!valid) {
            throw Ast.err(
                    AstException.AST__CONIN,
                    method + "(" + cls + "): Invalid constant syntax in the expression \"" + through(exprs, iend)
                            + "\".");
        }
        return iend;
    }

    /**
     * The index of the last character of the name starting at {@code istart}, as C's {@code
     * ParseName} finds it: a letter followed by letters, digits and underscores; -1 if the text
     * does not start with a letter.
     */
    static int parseName(String exprs, int istart) {
        int iend = -1;
        if (isAlpha(at(exprs, istart))) {
            iend = istart;
            char c;
            while ((c = at(exprs, iend + 1)) != '\0') {
                if (!(isAlnum(c) || c == '_')) {
                    break;
                }
                iend++;
            }
        }
        return iend;
    }

    /**
     * Parses the variable name starting at {@code istart}, as C's {@code ParseVariable} does.
     *
     * @param ivar receives in its first element the index of the name in {@code var}, or -1 if
     *     the text does not start with a name
     * @return the index of the name's last character, or -1 if there is no name
     * @throws AstException {@code AST__UDVOF} if the name is not one of the first {@code nvar}
     *     names of {@code var}
     */
    private static int parseVariable(
            String method, String cls, String exprs, int istart, int nvar, String[] var, int[] ivar) {
        ivar[0] = -1;
        int iend = parseName(exprs, istart);
        if (iend >= istart) {
            String name = exprs.substring(istart, iend + 1);
            for (int i = 0; i < nvar; i++) {
                if (name.equals(var[i])) {
                    ivar[0] = i;
                    return iend;
                }
            }
            throw Ast.err(
                    AstException.AST__UDVOF,
                    method + "(" + cls + "): Undefined variable or function in the expression \"" + through(exprs, iend)
                            + "\".");
        }
        return iend;
    }

    // ------------------------------------------------------------------
    // EvaluationSort
    // ------------------------------------------------------------------

    /**
     * Sorts the symbols into evaluation order and gives their opcodes, as C's {@code
     * EvaluationSort} does: each symbol is pushed on to a sort stack when its priority seen from
     * the left is greater than the stack top's seen from the right, and otherwise the stack top is
     * sent to the output; a closing parenthesis cancels the opening parenthesis or function call
     * it meets on the stack. The sort stack occupies the part of {@code symlist} already read.
     *
     * @param con the constants, which give the argument count of each function taking a variable
     *     number of arguments
     * @param stacksize receives in its first element the evaluation stack size the opcodes need
     * @return the opcodes, the first element being the number that follow
     */
    private static int[] evaluationSort(double[] con, int nsym, int[] symlist, int[] stacksize) {
        boolean flush = false;
        int icon = 0;
        int isym = 0;
        int ncode = 0;
        int nstack = 0;
        int tos = -1;
        int[] code = new int[nsym + 1];
        stacksize[0] = 0;
        while (tos > -1 || isym < nsym) {
            boolean push;
            if (tos == -1) {
                push = true;
            } else if (isym >= nsym) {
                push = false;
            } else if (flush) {
                push = false;
                flush = false;
            } else {
                push = MathMapToken.symbol(symlist[isym]).leftpriority
                        > MathMapToken.symbol(symlist[tos]).rightpriority;
            }
            if (push) {
                int sym = symlist[isym++];
                if (MathMapToken.symbol(sym).parincrement < 0) {
                    flush = true;
                } else {
                    symlist[++tos] = sym;
                }
            } else {
                int sym = symlist[tos--];
                if (sym == MathMapToken.SYMBOL_LDVAR || sym == MathMapToken.SYMBOL_LDCON) {
                    icon++;
                }
                MathMapToken.Symbol s = MathMapToken.symbol(sym);
                if (s.opcode != MathMapToken.OP_NULL) {
                    code[++ncode] = s.opcode;
                    if (s.nargs >= 0) {
                        nstack += s.stackincrement;
                    } else {
                        nstack -= (int) Math.round(con[icon++]) - 1;
                    }
                    stacksize[0] = Math.max(nstack, stacksize[0]);
                }
            }
        }
        code[0] = ncode;
        return Arrays.copyOf(code, ncode + 1);
    }
}
