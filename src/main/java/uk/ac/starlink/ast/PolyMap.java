package uk.ac.starlink.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A Mapping which performs a general polynomial transformation. Each output coordinate is a
 * polynomial function of all the input coordinates. The coefficients are specified separately for
 * each output coordinate. The forward and inverse transformations are defined independently by
 * separate sets of coefficients. If no inverse transformation is supplied, the default is to use
 * an iterative method to evaluate the inverse based only on the forward transformation (see the
 * IterInverse attribute).
 *
 * <p>Besides those of every Mapping, a PolyMap has the attributes IterInverse (whether to provide
 * an iterative inverse transformation), NiterInverse (the maximum number of iterations of the
 * iterative inverse) and TolInverse (its target relative error), read and set through the generic
 * attribute methods such as {@link #getI} and {@link #set}.
 *
 * <p>IterInverse: if true, the inverse transformation of the PolyMap as created is evaluated by an
 * iterative Newton-Raphson process that uses only the forward transformation, transforming
 * candidate input positions until one is found whose output is close enough to the required
 * output. A position for which the inverse is undefined gives bad values (for {@code y = x*x},
 * {@code y = -1}), and where there are several solutions the one closest to the supplied position
 * is given (for {@code y = x*x}, {@code y = 4} gives 2). The iterative inverse is used in
 * preference to any inverse coefficients. "Inverse", "input" and "output" here refer to the
 * PolyMap as created, ignoring any later inversion; the other transformation cannot be made
 * iterative. The default is true if no inverse coefficients were supplied and the PolyMap has as
 * many inputs as outputs, and false otherwise; it cannot be set true for a PolyMap with unequal
 * numbers of inputs and outputs.
 *
 * <p>NiterInverse: the maximum number of iterations the iterative inverse makes for each
 * position. Default 4.
 *
 * <p>TolInverse: the target relative error in the axis values of each position the iterative
 * inverse gives; iterations stop when it is reached or after NiterInverse iterations. Default
 * 1.0e-6.
 *
 * <p>IterInverse, NiterInverse and TolInverse cannot be changed once the PolyMap is shared with a
 * holder, such as a CmpMap, that took it without copying it.
 *
 * <p>Ported from Rust {@code mappings/polymap.rs}, with the coefficient storage, attributes, Copy,
 * Dump and loader of C's {@code polymap.c}. Like C, it keeps for each transformation the
 * coefficients and powers of each output in the order they were given.
 */
public class PolyMap extends Mapping {

    /** C's unset value of IterInverse and NiterInverse ({@code -INT_MAX}). */
    private static final int UNSET = -Integer.MAX_VALUE;

    /** The fewest points {@link #polyTransform} evaluates a block at a time. */
    private static final int BLOCKED_MIN = 16;

    /** The number of points in a block of {@link #polyTransformBlocked}. */
    private static final int BLOCK = 256;

    /** Per original output, the number of forward coefficients; null when there are none. */
    private int[] ncoeffF;

    /** The forward coefficient values of each original output. */
    private double[][] coeffF;

    /** The forward powers: per original output, per coefficient, the power of each original input. */
    private int[][][] powerF;

    /** The maximum power of each original input in any forward coefficient. */
    private int[] mxpowF;

    /** Per original input, the number of inverse coefficients; null when there are none. */
    private int[] ncoeffI;

    /** The inverse coefficient values of each original input. */
    private double[][] coeffI;

    /** The inverse powers: per original input, per coefficient, the power of each original output. */
    private int[][][] powerI;

    /** The maximum power of each original output in any inverse coefficient. */
    private int[] mxpowI;

    /** The IterInverse attribute: 0, 1 or {@link #UNSET}. */
    private int iterInverse = UNSET;

    /** The NiterInverse attribute, or {@link #UNSET}. */
    private int niterInverse = UNSET;

    /** The TolInverse attribute, or {@link #AST__BAD} when unset. */
    private double tolInverse = AST__BAD;

    /**
     * C's {@code jacobian}: the PolyMaps whose forward transformations give the columns of the
     * Jacobian of the original forward transformation, made when first needed; not copied.
     */
    private PolyMap[] jacobian;

    /**
     * C's {@code lintrunc}: the linear truncation the iterative inverse takes its first guess
     * from, made when first needed and copied with the PolyMap.
     */
    private Mapping lintrunc;

    /**
     * Creates a PolyMap, as C's {@code astPolyMap} does.
     *
     * <p>Each coefficient is described by a group of {@code 2 + nin} elements of {@code coeffF}:
     * the coefficient value; the 1-based index of the output whose polynomial uses it; and the
     * power of each input coordinate (not negative; values are rounded to the nearest integer). For
     * instance, with 3 inputs and 2 outputs, the group {@code (1.2, 2.0, 1.0, 3.0, 0.0)} adds 1.2
     * times the first input times the cube of the second to output 2, and {@code (-1.0, 1.0, 0.0,
     * 0.0, 0.0)} adds the constant -1.0 to output 1. Each output is the sum of the terms naming it.
     * A coefficient of zero contributes nothing and is not kept. The inverse coefficients in {@code
     * coeffI} are described the same way with groups of {@code 2 + nout} elements, inputs and
     * outputs exchanged.
     *
     * @param nin the number of input coordinates
     * @param nout the number of output coordinates
     * @param ncoeffF the number of coefficients defining the forward transformation; if zero, the
     *     forward transformation is undefined
     * @param coeffF the forward coefficients, {@code ncoeffF * (2 + nin)} elements; may be null if
     *     {@code ncoeffF} is zero
     * @param ncoeffI the number of coefficients defining the inverse transformation; if zero, the
     *     inverse is by default the iterative one (see IterInverse)
     * @param coeffI the inverse coefficients, {@code ncoeffI * (2 + nout)} elements; may be null if
     *     {@code ncoeffI} is zero
     * @throws AstException with status {@code AST__BADCI} if a coefficient names an output that
     *     does not exist, {@code AST__BADPW} if it has a negative power, {@code AST__DIMIN} if an
     *     array is too short, or {@code AST__BADNI} or {@code AST__BADNO} if a count is negative
     */
    public PolyMap(int nin, int nout, int ncoeffF, double[] coeffF, int ncoeffI, double[] coeffI) {
        super(nin, nout, true, true);
        Coeffs f = Coeffs.of(true, nin, nout, ncoeffF, coeffF);
        Coeffs i = Coeffs.of(false, nin, nout, ncoeffI, coeffI);
        if (f != null) {
            this.ncoeffF = f.nco;
            this.coeffF = f.cof;
            powerF = f.pow;
            mxpowF = f.mxpow;
        }
        if (i != null) {
            this.ncoeffI = i.nco;
            this.coeffI = i.cof;
            powerI = i.pow;
            mxpowI = i.mxpow;
        }
    }

    /**
     * Loads a PolyMap from its description, as C's {@code astLoadPolyMap} does. A transformation
     * whose maximum powers or coefficient counts are not all present is undefined. Each
     * coefficient is read from {@code CF<n>}, numbered through all the outputs, or, if that is
     * absent, from the older {@code CF<i>_<j>}, and is bad if neither is present; each power from
     * {@code PF<n>} or, if that is zero, {@code PF<i>_<j>_<k>}; likewise {@code CI} and {@code PI}
     * for the inverse. A negative count or power is taken as zero. The iterative inverse
     * attributes are read raw and, when set, applied again through their setters.
     */
    PolyMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("PolyMap");
        int nin = createdNin();
        int nout = createdNout();
        int[] mx = readCounts(part, "MPF", nin);
        int[] nc = mx == null ? null : readCounts(part, "NCF", nout);
        if (nc != null) {
            mxpowF = mx;
            ncoeffF = nc;
            coeffF = readCoeffs(part, "CF", nc);
            powerF = readPowers(part, "PF", nc, nin);
        }
        mx = readCounts(part, "MPI", nout);
        nc = mx == null ? null : readCounts(part, "NCI", nin);
        if (nc != null) {
            mxpowI = mx;
            ncoeffI = nc;
            coeffI = readCoeffs(part, "CI", nc);
            powerI = readPowers(part, "PI", nc, nout);
        }
        iterInverse = part.readInt("IterInv", UNSET);
        if (iterInverse != UNSET) {
            storeIterInverse(iterInverse != 0);
        }
        niterInverse = part.readInt("NiterInv", UNSET);
        if (niterInverse != UNSET) {
            storeNiterInverse(niterInverse);
        }
        tolInverse = part.readDouble("TolInv", AST__BAD);
        if (tolInverse != AST__BAD) {
            storeTolInverse(tolInverse);
        }
    }

    /** The values of {@code <prefix>1} to {@code <prefix>n}, or null if any is absent. */
    private static int[] readCounts(NativeNode part, String prefix, int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            int x = part.readInt(prefix + (i + 1), Integer.MAX_VALUE);
            if (x == Integer.MAX_VALUE) {
                return null;
            }
            v[i] = x;
        }
        return v;
    }

    private static double[][] readCoeffs(NativeNode part, String prefix, int[] ncoeff) {
        double[][] c = new double[ncoeff.length][];
        int iv = 0;
        for (int i = 0; i < ncoeff.length; i++) {
            ncoeff[i] = Math.max(0, ncoeff[i]);
            c[i] = new double[ncoeff[i]];
            for (int j = 0; j < ncoeff[i]; j++) {
                iv++;
                double v = part.readDouble(prefix + iv, AST__BAD);
                if (v == AST__BAD) {
                    v = part.readDouble(prefix + (i + 1) + "_" + (j + 1), AST__BAD);
                }
                c[i][j] = v;
            }
        }
        return c;
    }

    private static int[][][] readPowers(NativeNode part, String prefix, int[] ncoeff, int nvar) {
        int[][][] p = new int[ncoeff.length][][];
        int iv = 0;
        for (int i = 0; i < ncoeff.length; i++) {
            p[i] = new int[ncoeff[i]][nvar];
            for (int j = 0; j < ncoeff[i]; j++) {
                for (int k = 0; k < nvar; k++) {
                    iv++;
                    int v = part.readInt(prefix + iv, 0);
                    if (v == 0) {
                        v = part.readInt(prefix + (i + 1) + "_" + (j + 1) + "_" + (k + 1), 0);
                    }
                    p[i][j][k] = Math.max(0, v);
                }
            }
        }
        return p;
    }

    @Override
    String dumpComment() {
        return "Polynomial transformation";
    }

    /**
     * Writes the PolyMap's part of its description, as C's {@code Dump} (polymap.c) does: for each
     * transformation with coefficients, the maximum power of each of its inputs, the number of
     * coefficients of each output, the coefficients that are not bad and the powers that are above
     * zero; then the iterative inverse attributes, each set when it is set.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        if (ncoeffF != null) {
            dumpArrays(node, createdNin(), mxpowF, ncoeffF, coeffF, powerF, true);
        }
        if (ncoeffI != null) {
            dumpArrays(node, createdNout(), mxpowI, ncoeffI, coeffI, powerI, false);
        }
        boolean iter = getIterInverse();
        node.writeInt(
                "IterInv",
                testIterInverse(),
                false,
                iter ? 1 : 0,
                iter ? "Use an iterative inverse" : "Do not use an iterative inverse");
        node.writeInt(
                "NiterInv",
                testNiterInverse(),
                false,
                getNiterInverse(),
                "Max number of iterations for iterative inverse");
        node.writeDouble(
                "TolInv", testTolInverse(), false, getTolInverse(), "Target relative error for iterative inverse");
    }

    /** One transformation's items in {@link #dump}, of {@code nvar} variables. */
    private static void dumpArrays(
            NativeNode node, int nvar, int[] mxpow, int[] ncoeff, double[][] coeff, int[][][] power, boolean forward) {
        String f = forward ? "F" : "I";
        String dir = forward ? "forward" : "inverse";
        for (int i = 0; i < nvar; i++) {
            node.writeInt(
                    "MP" + f + (i + 1),
                    true,
                    true,
                    mxpow[i],
                    "Max. power of " + (forward ? "input " : "output ") + (i + 1) + " in any " + dir + " polynomial");
        }
        for (int i = 0; i < ncoeff.length; i++) {
            node.writeInt(
                    "NC" + f + (i + 1), true, true, ncoeff[i], "No. of coeff.s for " + dir + " polynomial " + (i + 1));
        }
        int iv = 1;
        for (int i = 0; i < ncoeff.length; i++) {
            for (int j = 0; j < ncoeff[i]; j++, iv++) {
                if (coeff[i][j] != AST__BAD) {
                    node.writeDouble(
                            "C" + f + iv,
                            true,
                            true,
                            coeff[i][j],
                            "Coeff " + (j + 1) + " of " + dir + " polynomial " + (i + 1));
                }
            }
        }
        iv = 1;
        for (int i = 0; i < ncoeff.length; i++) {
            for (int j = 0; j < ncoeff[i]; j++) {
                for (int k = 0; k < nvar; k++, iv++) {
                    if (power[i][j][k] > 0) {
                        node.writeDouble(
                                "P" + f + iv,
                                true,
                                true,
                                power[i][j][k],
                                "Power of " + (forward ? "i/p " : "o/p ") + (k + 1) + " for coeff " + (j + 1) + " of "
                                        + (forward ? "fwd" : "inv") + " poly " + (i + 1));
                    }
                }
            }
        }
    }

    /**
     * Completes a copy as C's {@code Copy} (polymap.c) does: the coefficient arrays and the linear
     * truncation are copied, and the Jacobian is left to be made again.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (ncoeffF != null) {
            ncoeffF = ncoeffF.clone();
            coeffF = deepClone(coeffF);
            powerF = deepClone(powerF);
            mxpowF = mxpowF.clone();
        }
        if (ncoeffI != null) {
            ncoeffI = ncoeffI.clone();
            coeffI = deepClone(coeffI);
            powerI = deepClone(powerI);
            mxpowI = mxpowI.clone();
        }
        jacobian = null;
        if (lintrunc != null) {
            lintrunc = (Mapping) lintrunc.copy();
        }
    }

    private static double[][] deepClone(double[][] a) {
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }

    private static int[][][] deepClone(int[][][] a) {
        int[][][] c = new int[a.length][][];
        for (int i = 0; i < a.length; i++) {
            c[i] = new int[a[i].length][];
            for (int j = 0; j < a[i].length; j++) {
                c[i][j] = a[i][j].clone();
            }
        }
        return c;
    }

    // ------------------------------------------------------------------
    // Coefficients
    // ------------------------------------------------------------------

    /**
     * C's {@code StoreArrays}: replaces the coefficients of the forward ({@code forward}) or inverse
     * transformation of the PolyMap as it stands (the other original transformation if it is
     * inverted) with {@code ncoeff} coefficients in the form the constructor takes. A zero
     * coefficient is not kept (C's {@code AddCoeff}); if {@code ncoeff} is not positive the
     * transformation is left without coefficients. The Jacobian and linear truncation, if made, are
     * kept, as C keeps them.
     *
     * @throws AstException with status {@code AST__BADCI} or {@code AST__BADPW} for a coefficient
     *     naming an output that does not exist or with a negative power, or {@code AST__DIMIN} if
     *     the array is too short
     */
    final void storeArrays(boolean forward, int ncoeff, double[] coeff) {
        boolean origForward = forward != getInvert();
        Coeffs c = Coeffs.of(origForward, createdNin(), createdNout(), ncoeff, coeff);
        if (origForward) {
            setForward(c);
        } else {
            setInverse(c);
        }
    }

    private void setForward(Coeffs c) {
        ncoeffF = c == null ? null : c.nco;
        coeffF = c == null ? null : c.cof;
        powerF = c == null ? null : c.pow;
        mxpowF = c == null ? null : c.mxpow;
    }

    private void setInverse(Coeffs c) {
        ncoeffI = c == null ? null : c.nco;
        coeffI = c == null ? null : c.cof;
        powerI = c == null ? null : c.pow;
        mxpowI = c == null ? null : c.mxpow;
    }

    /** The coefficient arrays C keeps for one original transformation. */
    private static final class Coeffs {
        final int[] nco;
        final double[][] cof;
        final int[][][] pow;
        final int[] mxpow;

        private Coeffs(int npoly, int nvar) {
            nco = new int[npoly];
            cof = new double[npoly][0];
            pow = new int[npoly][0][];
            mxpow = new int[nvar];
        }

        /**
         * The arrays C's {@code StoreArrays} makes from {@code ncoeff} coefficients of the original
         * forward ({@code origForward}) or inverse transformation of a PolyMap with {@code nin}
         * inputs and {@code nout} outputs, or null if {@code ncoeff} is not positive.
         */
        static Coeffs of(boolean origForward, int nin, int nout, int ncoeff, double[] coeff) {
            if (ncoeff <= 0) {
                return null;
            }
            int nvar = origForward ? nin : nout;
            int npoly = origForward ? nout : nin;
            String array = origForward ? "coeff_f" : "coeff_i";
            int length = coeff == null ? 0 : coeff.length;
            long need = (long) ncoeff * (2 + nvar);
            if (length < need) {
                throw Ast.err(
                        AstException.AST__DIMIN,
                        "astPolyMap(PolyMap): The " + array + " array holds " + length
                                + " values.\nThis should not be less than " + need + ".");
            }
            Coeffs c = new Coeffs(npoly, nvar);
            String what = origForward ? "Forward" : "Inverse";
            int gsize = 2 + nvar;
            int[] pows = new int[nvar];
            for (int i = 0; i < ncoeff; i++) {
                int group = i * gsize;
                int iout = (int) Math.floor(coeff[group + 1] + 0.5);
                if (iout < 1 || iout > npoly) {
                    throw Ast.err(
                            AstException.AST__BADCI,
                            "astInitPolyMap(PolyMap): " + what + " coefficient " + (i + 1) + " referred to an illegal "
                                    + (origForward ? "output" : "input") + " coordinate " + iout
                                    + ".\nThis number should be in the range 1 to " + npoly + ".");
                }
                for (int j = 0; j < nvar; j++) {
                    pows[j] = (int) Math.floor(coeff[group + 2 + j] + 0.5);
                    if (pows[j] < 0) {
                        throw Ast.err(
                                AstException.AST__BADPW,
                                "astInitPolyMap(PolyMap): " + what + " coefficient " + (i + 1)
                                        + " has a negative power (" + pows[j] + ") for "
                                        + (origForward ? "input" : "output") + " coordinate " + (j + 1)
                                        + ".\nAll powers should be zero or positive.");
                    }
                }
                addCoeff(iout - 1, coeff[group], pows, c.nco, c.cof, c.pow, c.mxpow);
            }
            return c;
        }
    }

    /**
     * C's {@code AddCoeff}: appends a coefficient for output {@code iout} unless it is zero, and
     * raises the maximum powers to its powers.
     */
    // The loop takes maxima, which PMD mistakes for a copy.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private static void addCoeff(
            int iout, double c, int[] pows, int[] nco, double[][] cof, int[][][] pow, int[] mxpow) {
        if (c != 0.0) {
            int n = ++nco[iout];
            cof[iout] = Arrays.copyOf(cof[iout], n);
            cof[iout][n - 1] = c;
            pow[iout] = Arrays.copyOf(pow[iout], n);
            pow[iout][n - 1] = pows.clone();
            for (int i = 0; i < pows.length; i++) {
                if (pows[i] > mxpow[i]) {
                    mxpow[i] = pows[i];
                }
            }
        }
    }

    /**
     * Whether the original forward ({@code forward}) or inverse transformation has coefficients:
     * C's {@code ncoeff_f} or {@code ncoeff_i} being set.
     */
    final boolean hasCoeffs(boolean forward) {
        return (forward ? ncoeffF : ncoeffI) != null;
    }

    /**
     * C's {@code astPolyCoeffs}: the coefficients of the forward ({@code forward}) or inverse
     * transformation of the PolyMap as it stands, in the form the constructor takes them, grouped
     * by the output each defines, in output order, and within each output in the order they were
     * given. The forward coefficients of an inverted PolyMap are the inverse ones it was created
     * with. The array is empty when the transformation has no coefficients, such as an iterative
     * inverse.
     */
    double[] polyCoeffs(boolean forward) {
        boolean origForward = forward != getInvert();
        int[] nco = origForward ? ncoeffF : ncoeffI;
        if (nco == null) {
            return new double[0];
        }
        double[][] cof = origForward ? coeffF : coeffI;
        int[][][] pow = origForward ? powerF : powerI;
        int naxes = origForward ? createdNin() : createdNout();
        int total = 0;
        for (int n : nco) {
            total += n;
        }
        double[] out = new double[total * (2 + naxes)];
        int iel = 0;
        for (int ipoly = 0; ipoly < nco.length; ipoly++) {
            for (int icoeff = 0; icoeff < nco[ipoly]; icoeff++) {
                out[iel++] = cof[ipoly][icoeff];
                out[iel++] = ipoly + 1;
                for (int iax = 0; iax < naxes; iax++) {
                    out[iel++] = pow[ipoly][icoeff][iax];
                }
            }
        }
        return out;
    }

    /**
     * C's protected {@code astMergeShift} (polymap.c {@code MergeShift}): a new PolyMap equivalent
     * to {@code shift} and this PolyMap applied in series, the ShiftMap first if {@code before} and
     * last otherwise. If {@code force} is true the constant terms of the new PolyMap are made zero.
     * The new PolyMap is simplified, which amalgamates the coefficients of each output that refer
     * to the same powers and removes zero coefficients; it is inverted if this PolyMap is. While
     * the coefficients are merged this PolyMap's Invert attribute is cleared and {@code shift} is
     * inverted, as C does; both are restored afterwards.
     *
     * @throws AstException with status {@code AST__INTER} if {@code shift} has the wrong number of
     *     inputs, or the simplified result is not a PolyMap
     */
    PolyMap mergeShift(ShiftMap shift, boolean before, boolean force) {
        boolean inverted = getInvert();
        boolean bef = before;
        if (inverted) {
            clearInvert();
            shift.invert();
            bef = !bef;
        }
        try {
            int nin = getNin();
            int nout = getNout();
            int nshift = bef ? nin : nout;
            if (shift.getNin() != nshift) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astMergeShift(" + astClass() + "): Supplied ShiftMap has incorrect number of inputs - "
                                + nshift + " are required (internal AST programming error).");
            }
            double[] shifts = shift.getShifts();

            // A copy whose coefficients are replaced, so any record that it is simple, its linear
            // truncation and its Jacobian no longer apply.
            PolyMap result = (PolyMap) copy();
            result.clearIsSimple();
            result.lintrunc = null;
            result.jacobian = null;

            result.setForward(mergeShifts(nin, nout, force, ncoeffF, coeffF, powerF, mxpowF, shifts, bef));

            // The inverse of the series is the inverse of the PolyMap and the negated shifts, in
            // the other order.
            if (ncoeffI != null) {
                for (int iin = 0; iin < nshift; iin++) {
                    shifts[iin] = -shifts[iin];
                }
                result.setInverse(mergeShifts(nout, nin, force, ncoeffI, coeffI, powerI, mxpowI, shifts, !bef));
            }

            Mapping tmap = result.simplify();
            if (!(tmap instanceof PolyMap)) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astMergeShift(" + astClass() + "): Result is a " + tmap.astClass()
                                + " - expected a PolyMap  (internal AST programming error).");
            }
            PolyMap merged = (PolyMap) tmap;
            if (inverted) {
                merged.invert();
            }
            return merged;
        } finally {
            if (inverted) {
                setInvert(true);
                shift.invert();
            }
        }
    }

    /**
     * C's {@code MergeShifts} (polymap.c): the coefficient arrays of a polynomial transformation
     * of {@code nin} inputs and {@code nout} outputs equivalent to the one given and the shifts
     * {@code shifts} applied in series, added to the inputs first if {@code before}, else to the
     * outputs afterwards. Shifts added afterwards go into the first constant term of each output,
     * or a new constant term appended to it. Shifts added first expand each term with {@link
     * #expandTerm}, the terms of the expansion being appended in order. If {@code force} is true
     * the constant terms are then set to zero, and left in the arrays. The maximum powers start as
     * those given.
     */
    private static Coeffs mergeShifts(
            int nin,
            int nout,
            boolean force,
            int[] ncoeff,
            double[][] coeff,
            int[][][] power,
            int[] mxpow,
            double[] shifts,
            boolean before) {
        Coeffs c = new Coeffs(nout, nin);
        System.arraycopy(mxpow, 0, c.mxpow, 0, nin);
        if (before) {
            List<Double> ks = new ArrayList<>();
            List<int[]> qs = new ArrayList<>();
            for (int iout = 0; iout < nout; iout++) {
                for (int ico = 0; ico < ncoeff[iout]; ico++) {
                    ks.clear();
                    qs.clear();
                    expandTerm(nin, coeff[iout][ico], power[iout][ico].clone(), shifts, ks, qs);
                    for (int iexp = 0; iexp < ks.size(); iexp++) {
                        addCoeff(iout, ks.get(iexp), qs.get(iexp), c.nco, c.cof, c.pow, c.mxpow);
                    }
                }
            }
        } else {
            for (int iout = 0; iout < nout; iout++) {
                c.nco[iout] = ncoeff[iout];
                c.cof[iout] = coeff[iout].clone();
                c.pow[iout] = new int[ncoeff[iout]][];
                for (int ico = 0; ico < ncoeff[iout]; ico++) {
                    c.pow[iout][ico] = power[iout][ico].clone();
                }
            }
            for (int iout = 0; iout < nout; iout++) {
                boolean shiftAdded = false;
                for (int ico = 0; ico < c.nco[iout]; ico++) {
                    if (isConstant(c.pow[iout][ico])) {
                        c.cof[iout][ico] += shifts[iout];
                        shiftAdded = true;
                        break;
                    }
                }
                if (!shiftAdded) {
                    addCoeff(iout, shifts[iout], new int[nin], c.nco, c.cof, c.pow, c.mxpow);
                }
            }
        }
        if (force) {
            for (int iout = 0; iout < nout; iout++) {
                for (int ico = 0; ico < c.nco[iout]; ico++) {
                    if (isConstant(c.pow[iout][ico])) {
                        c.cof[iout][ico] = 0.0;
                    }
                }
            }
        }
        return c;
    }

    /** Whether every power of a term is zero. */
    private static boolean isConstant(int[] pows) {
        for (int pow : pows) {
            if (pow != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * C's {@code ExpandTerm} (polymap.c): appends to {@code ks} and {@code qs} the coefficients and
     * powers of the expansion of {@code c * (x0 + s0)^p0 * (x1 + s1)^p1 * ...} in powers of the
     * unshifted inputs. The first input {@code i} with a non-zero power is factored out once: each
     * term {@code k * x^q} of the expansion of the reduced term gives {@code k * x^q * xi} and then
     * {@code k * si * x^q}, in that order. Terms with the same powers are not combined. {@code p}
     * is changed while the expansion is made and restored afterwards.
     */
    private static void expandTerm(int nin, double c, int[] p, double[] s, List<Double> ks, List<int[]> qs) {
        int i = -1;
        for (int iin = 0; iin < nin; iin++) {
            if (p[iin] > 0) {
                i = iin;
                break;
            }
        }
        if (i == -1) {
            ks.add(c);
            qs.add(new int[nin]);
            return;
        }
        p[i] -= 1;
        List<Double> ks1 = new ArrayList<>();
        List<int[]> qs1 = new ArrayList<>();
        expandTerm(nin, c, p, s, ks1, qs1);
        p[i] += 1;
        for (int ico = 0; ico < ks1.size(); ico++) {
            double k = ks1.get(ico);
            int[] q = qs1.get(ico);
            q[i] += 1;
            ks.add(k);
            qs.add(q.clone());
            q[i] -= 1;
            ks.add(k * s[i]);
            qs.add(q.clone());
        }
    }

    // ------------------------------------------------------------------
    // Definedness
    // ------------------------------------------------------------------

    /**
     * Gets the TranForward attribute, as C's {@code GetTranForward} (polymap.c) does: when not
     * inverted, whether forward coefficients were supplied; when inverted, whether inverse
     * coefficients were supplied or the iterative inverse is used.
     *
     * @return true if the forward transformation is defined
     */
    @Override
    public boolean getTranForward() {
        if (!getInvert()) {
            return ncoeffF != null;
        }
        return ncoeffI != null || getIterInverse();
    }

    /**
     * Gets the TranInverse attribute, as C's {@code GetTranInverse} (polymap.c) does: when not
     * inverted, whether inverse coefficients were supplied or the iterative inverse is used; when
     * inverted, whether forward coefficients were supplied.
     *
     * @return true if the inverse transformation is defined
     */
    @Override
    public boolean getTranInverse() {
        if (!getInvert()) {
            return ncoeffI != null || getIterInverse();
        }
        return ncoeffF != null;
    }

    // ------------------------------------------------------------------
    // IterInverse, NiterInverse and TolInverse
    // ------------------------------------------------------------------

    /** The IterInverse attribute (see the class description). */
    boolean getIterInverse() {
        if (iterInverse == UNSET) {
            return ncoeffI == null && createdNin() == createdNout();
        }
        return iterInverse != 0;
    }

    /**
     * Sets the IterInverse attribute, C's {@code SetIterInverse}, an {@code astMAKE_SET1}
     * attribute (polymap.c). A value different from the one held discards the record that the
     * PolyMap has been simplified.
     *
     * @throws AstException with status {@code AST__IMMUT} if the PolyMap is shared, or {@code
     *     AST__ATTIN} if {@code value} is true and the PolyMap has unequal numbers of inputs and
     *     outputs, leaving the value unchanged
     */
    void setIterInverse(boolean value) {
        storeIterInverse(value);
    }

    /** C's {@code SetIterInverse}, an {@code astMAKE_SET1} attribute (polymap.c). */
    private void storeIterInverse(boolean value) {
        checkSet1("IterInverse");
        if (createdNin() == createdNout() || !value) {
            int v = value ? 1 : 0;
            if (v != iterInverse) {
                clearIsSimple();
            }
            iterInverse = v;
        } else {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetIterInverse(" + astClass() + "):Cannot use an iterative inverse because the " + astClass()
                            + " has unequal numbers of inputs and outputs.");
        }
    }

    /** Whether the IterInverse attribute is set. */
    boolean testIterInverse() {
        return iterInverse != UNSET;
    }

    /**
     * Clears the IterInverse attribute, which also discards the record of simplification; an
     * {@code astMAKE_CLEAR1} attribute (polymap.c).
     */
    void clearIterInverse() {
        checkClear1("IterInverse");
        clearIsSimple();
        iterInverse = UNSET;
    }

    /** The NiterInverse attribute (see the class description). */
    int getNiterInverse() {
        return niterInverse == UNSET ? 4 : niterInverse;
    }

    /**
     * Sets the NiterInverse attribute, an {@code astMAKE_SET1} attribute (polymap.c). A value
     * different from the one held discards the record that the PolyMap has been simplified.
     *
     * @throws AstException with status {@code AST__IMMUT} if the PolyMap is shared
     */
    void setNiterInverse(int value) {
        storeNiterInverse(value);
    }

    /** C's {@code SetNiterInverse}, an {@code astMAKE_SET1} attribute (polymap.c). */
    private void storeNiterInverse(int value) {
        checkSet1("NiterInverse");
        if (value != niterInverse) {
            clearIsSimple();
        }
        niterInverse = value;
    }

    /** Whether the NiterInverse attribute is set. */
    boolean testNiterInverse() {
        return niterInverse != UNSET;
    }

    /** Clears the NiterInverse attribute, and the record of simplification; {@code astMAKE_CLEAR1}. */
    void clearNiterInverse() {
        checkClear1("NiterInverse");
        clearIsSimple();
        niterInverse = UNSET;
    }

    /** The TolInverse attribute (see the class description). */
    double getTolInverse() {
        return tolInverse == AST__BAD ? 1.0e-6 : tolInverse;
    }

    /**
     * Sets the TolInverse attribute, an {@code astMAKE_SET1} attribute (polymap.c); {@link
     * #AST__BAD} leaves it unset. A value different from the one held discards the record that the
     * PolyMap has been simplified.
     *
     * @throws AstException with status {@code AST__IMMUT} if the PolyMap is shared
     */
    void setTolInverse(double value) {
        storeTolInverse(value);
    }

    /** C's {@code SetTolInverse}, an {@code astMAKE_SET1} attribute (polymap.c). */
    private void storeTolInverse(double value) {
        checkSet1("TolInverse");
        if (value != tolInverse) {
            clearIsSimple();
        }
        tolInverse = value;
    }

    /** Whether the TolInverse attribute is set. */
    boolean testTolInverse() {
        return tolInverse != AST__BAD;
    }

    /** Clears the TolInverse attribute, and the record of simplification; {@code astMAKE_CLEAR1}. */
    void clearTolInverse() {
        checkClear1("TolInverse");
        clearIsSimple();
        tolInverse = AST__BAD;
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * polymap.c {@code Equal}: {@code that} is a PolyMap with the same numbers of inputs and
     * outputs, the same Invert attribute, and, for each original transformation, either no
     * coefficients in both or the same coefficient counts, maximum powers and powers, and
     * coefficients equal to within {@code astEQUAL}.
     */
    @Override
    boolean equalTo(AstObject that) {
        if (!(that instanceof PolyMap)) {
            return false;
        }
        PolyMap other = (PolyMap) that;
        if (other.getNin() != getNin() || other.getNout() != getNout() || getInvert() != other.getInvert()) {
            return false;
        }
        int nin = createdNin();
        int nout = createdNout();
        return sameTerms(ncoeffF, other.ncoeffF, mxpowF, other.mxpowF, nout, nin)
                && sameCoeffs(coeffF, other.coeffF, powerF, other.powerF, ncoeffF, nout, nin)
                && sameTerms(ncoeffI, other.ncoeffI, mxpowI, other.mxpowI, nin, nout)
                && sameCoeffs(coeffI, other.coeffI, powerI, other.powerI, ncoeffI, nin, nout);
    }

    /**
     * Whether two transformations' coefficient counts ({@code npoly} of them) and maximum powers
     * ({@code nvar} of them) are both absent or equal, as C's {@code Equal} compares them.
     */
    private static boolean sameTerms(int[] nc1, int[] nc2, int[] mx1, int[] mx2, int npoly, int nvar) {
        if ((nc1 == null) != (nc2 == null) || (mx1 == null) != (mx2 == null)) {
            return false;
        }
        if (nc1 != null) {
            for (int i = 0; i < npoly; i++) {
                if (nc1[i] != nc2[i]) {
                    return false;
                }
            }
        }
        if (mx1 != null) {
            for (int i = 0; i < nvar; i++) {
                if (mx1[i] != mx2[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether two transformations with the same coefficient counts {@code ncoeff} have both no
     * coefficients or the same powers and coefficients equal to within {@code astEQUAL}, as C's
     * {@code Equal} compares them.
     */
    private static boolean sameCoeffs(
            double[][] c1, double[][] c2, int[][][] p1, int[][][] p2, int[] ncoeff, int npoly, int nvar) {
        if ((c1 == null) != (c2 == null) || (p1 == null) != (p2 == null)) {
            return false;
        }
        if (c1 != null) {
            for (int i = 0; i < npoly; i++) {
                for (int j = 0; j < ncoeff[i]; j++) {
                    if (!MappingHelpers.astEqual(c1[i][j], c2[i][j])) {
                        return false;
                    }
                }
            }
        }
        if (p1 != null) {
            for (int i = 0; i < npoly; i++) {
                for (int j = 0; j < ncoeff[i]; j++) {
                    for (int k = 0; k < nvar; k++) {
                        if (p1[i][j][k] != p2[i][j][k]) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * polymap.c {@code MapMerge}. First, a copy of the PolyMap whose coefficients over the same
     * powers have been added together and whose zero coefficients have been removed, in both
     * original transformations, replaces it in the list if that changed anything. Then, if the
     * original forward transformation has coefficients, as many inputs as outputs, and no term of
     * degree above one, the PolyMap is replaced by the simplified series CmpMap of the MatrixMap of
     * its linear terms and the ShiftMap of its constant terms, applied with the PolyMap's Invert
     * value from the list. Finally, in series, a neighbouring PolyMap applied in the other
     * direction that is equal to the nominated Mapping once both are applied in the nominated
     * Mapping's direction turns the pair into two UnitMaps.
     */
    @Override
    int mapMerge(MergeList list, int where, boolean series) {
        int result = -1;
        Mapping pmap0 = list.map(where);
        int nin = createdNin();
        int nout = createdNout();

        PolyMap consolidated = (PolyMap) pmap0.copy();
        boolean mod = false;
        if (consolidated.ncoeffI != null) {
            mod = consolidate(consolidated.ncoeffI, consolidated.coeffI, consolidated.powerI, nin);
        }
        if (consolidated.ncoeffF != null) {
            mod |= consolidate(consolidated.ncoeffF, consolidated.coeffF, consolidated.powerF, nout);
        }
        if (mod) {
            list.set(where, consolidated, list.invert(where));
            result = where;
            pmap0 = consolidated;
        }

        // The ChebyMap branch of polymap.c MapMerge, which folds a ChebyMap's input normalisation
        // into the matrix and offsets, is absent.
        PolyMap poly0 = (PolyMap) pmap0;
        if (poly0.ncoeffF != null && nout == nin) {
            double[] offsets = new double[nout];
            double[] matrix = new double[nout * nout];
            if (linearTerms(poly0, nin, nout, offsets, matrix)) {
                ShiftMap sm = new ShiftMap(offsets);
                MatrixMap mm = new MatrixMap(nin, nout, MatrixMap.FULL, matrix);
                Mapping simplified = new CmpMap(mm, sm, true).simplify();
                list.set(where, simplified, list.invert(where));
                pmap0 = simplified;
                result = where;
            }
        }

        if (series) {
            result = cancelWithNeighbour(list, where, pmap0, result);
        }
        return result;
    }

    /**
     * The consolidation of polymap.c {@code MapMerge} for one original transformation, of {@code
     * npoly} polynomials: each non-zero coefficient takes in the value of
     * every later non-zero coefficient over the same powers, which becomes zero; then the non-zero
     * coefficients are moved, in order, to the start of each polynomial's arrays, which are cut
     * short after them. The maximum powers are left as they are.
     *
     * @return whether any polynomial lost a coefficient
     */
    private static boolean consolidate(int[] ncoeff, double[][] coeff, int[][][] power, int npoly) {
        boolean mod = false;
        for (int iout = 0; iout < npoly; iout++) {
            int nc = ncoeff[iout];
            double[] outcof = coeff[iout];
            int[][] outpow = power[iout];
            for (int ic = 0; ic < nc - 1; ic++) {
                if (outcof[ic] != 0.0) {
                    for (int jc = ic + 1; jc < nc; jc++) {
                        if (outcof[jc] != 0.0 && Arrays.equals(outpow[ic], outpow[jc])) {
                            outcof[ic] += outcof[jc];
                            outcof[jc] = 0.0;
                        }
                    }
                }
            }
            int jc = 0;
            for (int ic = 0; ic < nc; ic++) {
                if (outcof[ic] != 0.0) {
                    if (ic > jc) {
                        outcof[jc] = outcof[ic];
                        outcof[ic] = 0.0;
                        outpow[jc] = outpow[ic];
                        outpow[ic] = null;
                    }
                    jc++;
                } else {
                    outpow[ic] = null;
                }
            }
            if (jc < nc) {
                ncoeff[iout] = jc;
                coeff[iout] = Arrays.copyOf(outcof, jc);
                power[iout] = Arrays.copyOf(outpow, jc);
                mod = true;
            }
        }
        return mod;
    }

    /**
     * The linearity test of polymap.c {@code MapMerge}: adds each non-zero constant term of the
     * original forward transformation of {@code poly} into {@code offsets}, and each non-zero term
     * of degree one into {@code matrix} (row-major, {@code nout} rows of {@code nin}), stopping at
     * the first term of higher degree.
     *
     * @return whether every term is of degree one or less
     */
    private static boolean linearTerms(PolyMap poly, int nin, int nout, double[] offsets, double[] matrix) {
        for (int iout = 0; iout < nout; iout++) {
            int nc = poly.ncoeffF[iout];
            double[] outcof = poly.coeffF[iout];
            int[][] outpow = poly.powerF[iout];
            for (int ic = 0; ic < nc; ic++) {
                if (outcof[ic] != 0.0) {
                    boolean constTerm = true;
                    boolean linearTerm = true;
                    int jin = -1;
                    for (int iin = 0; iin < nin; iin++) {
                        int pow = outpow[ic][iin];
                        if (pow > 0) {
                            constTerm = false;
                        }
                        if (pow > 1) {
                            linearTerm = false;
                        } else if (linearTerm && pow == 1) {
                            if (jin == -1) {
                                jin = iin;
                            } else {
                                linearTerm = false;
                            }
                        }
                    }
                    if (constTerm) {
                        offsets[iout] += outcof[ic];
                    } else if (linearTerm) {
                        matrix[jin + iout * nin] += outcof[ic];
                    } else {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The neighbour half of polymap.c {@code MapMerge}: with {@code pmap0}, the Mapping now at
     * {@code where}, applied as the list applies it, each neighbour in turn (the one before, then
     * the one after) that is a PolyMap applied in the other direction is applied in {@code
     * pmap0}'s direction and compared with it; the first equal one and {@code pmap0} are replaced
     * by UnitMaps applied forward, of {@code pmap0}'s output count if the neighbour comes first and
     * its input count otherwise. As in C, the neighbour's Invert attribute is restored after the
     * comparison, and {@code pmap0}'s only when the pair does not cancel: a cancelled {@code pmap0},
     * which may be shared with the Mapping being simplified, is left with the list's value
     * (docs/rust-c-differences.md, "Cancelling a PolyMap with its inverse").
     *
     * @return the index of the first entry changed: the lower of the pair if they cancel, and
     *     {@code result} otherwise
     */
    private static int cancelWithNeighbour(MergeList list, int where, Mapping pmap0, int result) {
        boolean inv0 = list.invert(where);
        int saved0 = pmap0.swapInvert(inv0);
        boolean cancelled = false;
        try {
            int nin = pmap0.getNin();
            int nout = pmap0.getNout();
            for (int i = where - 1; i <= where + 1; i += 2) {
                if (i < 0 || i >= list.size() || !(list.map(i) instanceof PolyMap) || list.invert(i) == inv0) {
                    continue;
                }
                PolyMap pmap1 = (PolyMap) list.map(i);
                int saved1 = pmap1.swapInvert(inv0);
                boolean ok;
                try {
                    ok = pmap0.astEqual(pmap1);
                } finally {
                    pmap1.restoreInvert(saved1);
                }
                if (ok) {
                    int n = i < where ? nout : nin;
                    list.set(where, new UnitMap(n), false);
                    list.set(i, new UnitMap(n), false);
                    cancelled = true;
                    return Math.min(i, where);
                }
            }
        } finally {
            // C annuls its pointer to a cancelled pmap0, which skips the restore.
            if (!cancelled) {
                pmap0.restoreInvert(saved0);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Attribute strings (polymap.c GetAttrib, SetAttrib, TestAttrib, ClearAttrib)
    // ------------------------------------------------------------------

    @Override
    String getAttrib(String attrib) {
        if ("iterinverse".equals(attrib)) {
            return Integer.toString(getIterInverse() ? 1 : 0);
        } else if ("niterinverse".equals(attrib)) {
            return Integer.toString(getNiterInverse());
        } else if ("tolinverse".equals(attrib)) {
            return CFormat.format("%.15g", getTolInverse());
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        Double dval = CScan.scanDouble(value);
        if ("iterinverse".equals(attrib) && ival != null) {
            setIterInverse(ival != 0);
        } else if ("niterinverse".equals(attrib) && ival != null) {
            setNiterInverse(ival);
        } else if ("tolinverse".equals(attrib) && dval != null) {
            setTolInverse(dval);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        if ("iterinverse".equals(attrib)) {
            return testIterInverse();
        } else if ("niterinverse".equals(attrib)) {
            return testNiterInverse();
        } else if ("tolinverse".equals(attrib)) {
            return testTolInverse();
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("iterinverse".equals(attrib)) {
            clearIterInverse();
        } else if ("niterinverse".equals(attrib)) {
            clearNiterInverse();
        } else if ("tolinverse".equals(attrib)) {
            clearTolInverse();
        } else {
            super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Fitting
    // ------------------------------------------------------------------

    /**
     * C's {@code astPolyTran}: a copy of this PolyMap in which the forward ({@code forward}) or,
     * more usually, inverse transformation has been replaced by a polynomial fitted by least
     * squares to samples of the other transformation, which must have coefficients. Only PolyMaps
     * with one input and one output, or two of each, can be used.
     *
     * <p>Writing X for the inputs and Y for the outputs, with {@code forward} false the forward
     * transformation is sampled at a regular grid of X covering the box {@code lbnd} to {@code
     * ubnd} of the input space, and the inverse polynomial X = P_i(Y) is chosen to minimise the sum
     * of the squared residuals between the sampled X and P_i(Y); with {@code forward} true the
     * inverse transformation is sampled over a box of the output space and a forward polynomial
     * fitted. The transformations are those of the PolyMap as it stands, after any inversion. The
     * fit is repeated with increasing order, starting with linear, until the RMS residual is below
     * {@code acc} or the order reaches {@code maxorder}; a maximum-order fit that misses {@code acc}
     * is accepted if its residual is below {@code maxacc}. {@code acc} and {@code maxacc} are
     * distances in the space the new transformation transforms into. {@code maxorder} is one more
     * than the maximum power of either input, so 3 gives a quadratic; cross terms with a total power
     * of {@code maxorder} or more are not included. IterInverse is cleared in the result, so that it
     * uses the new fit rather than an iterative inverse.
     *
     * @return the new PolyMap, or null if the fit fails to achieve {@code maxacc}
     * @throws AstException with status {@code AST__BADNI} if the PolyMap does not have one input
     *     and one output or two of each, {@code AST__NODEF} if the transformation to be sampled has
     *     no coefficients, a bound is null or an upper bound is not above its lower bound, {@code
     *     AST__DIMIN} if a bounds array is too short, or {@code AST__MNPCK} if the minimiser fails
     */
    PolyMap polyTran(boolean forward, double acc, double maxacc, int maxorder, double[] lbnd, double[] ubnd) {
        PolyMap result = (PolyMap) copy();
        result.clearIsSimple();
        if (!PolyTran.replaceTransformation(result, forward, acc, maxacc, maxorder, lbnd, ubnd)) {
            return null;
        }
        result.clearIterInverse();
        return result;
    }

    /**
     * C's {@code FitPoly1DInit} (polymap.c), which a subclass overrides: the values each of the
     * {@code order} coefficients of a 1-D fit multiplies at each of the {@code nsamp} samples of
     * {@code table[0]}, sample by sample; here the powers 0 to {@code order - 1}. {@code scales}
     * holds the factors that restore the table's columns, which a subclass applying its own scaling
     * may change. {@code forward} is the transformation being replaced.
     */
    double[] fitPoly1DInit(boolean forward, double[][] table, int order, int nsamp, double[] scales) {
        return powerBasis(table[0], nsamp, order);
    }

    /**
     * C's {@code FitPoly2DInit} (polymap.c), which a subclass overrides: as {@link #fitPoly1DInit}
     * for the two inputs {@code table[0]} and {@code table[1]}, giving the values for each.
     */
    double[][] fitPoly2DInit(boolean forward, double[][] table, int order, int nsamp, double[] scales) {
        return new double[][] {powerBasis(table[0], nsamp, order), powerBasis(table[1], nsamp, order)};
    }

    /** The powers 0 to {@code order - 1} of each of the {@code nsamp} values of {@code x}. */
    private static double[] powerBasis(double[] x, int nsamp, int order) {
        double[] xp = new double[nsamp * order];
        int k = 0;
        for (int i = 0; i < nsamp; i++) {
            double tv = 1.0;
            for (int w = 0; w < order; w++) {
                xp[k++] = tv;
                tv *= x[i];
            }
        }
        return xp;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Transforms as C's {@code Transform} (polymap.c) does: the original inverse transformation is
     * the iterative one when IterInverse is set, in preference to any inverse coefficients;
     * otherwise each output is the sum of its terms, a term with a bad coefficient, or using a power
     * above zero of a bad input, making its output bad. A NaN output is bad.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        if (!fwd && getIterInverse()) {
            iterativeInverse(in, npoint, out);
        } else {
            polyTransform(fwd, in, npoint, out, AST__BAD);
        }
    }

    /**
     * NaN protocol: the polynomials propagate NaN through their arithmetic, so a NaN input gives a
     * NaN in each output that uses a power of it; the iterative inverse works in bad values, so its
     * NaN inputs become bad on the way in and its bad outputs NaN on the way out.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        requireDirection(forward);
        boolean fwd = effectiveForward(forward);
        if (!fwd && getIterInverse()) {
            double[][] bad = new double[in.length][];
            for (int c = 0; c < in.length; c++) {
                bad[c] = Arrays.copyOf(in[c], npoint);
                for (int p = 0; p < npoint; p++) {
                    if (Double.isNaN(bad[c][p])) {
                        bad[c][p] = AST__BAD;
                    }
                }
            }
            iterativeInverse(bad, npoint, out);
            for (int c = 0; c < createdNin(); c++) {
                for (int p = 0; p < npoint; p++) {
                    if (out[c][p] == AST__BAD) {
                        out[c][p] = Double.NaN;
                    }
                }
            }
        } else {
            polyTransform(fwd, in, npoint, out, Double.NaN);
        }
    }

    /**
     * C's {@code PolyPowers} (polymap.c), which a subclass overrides: fills {@code work[axis][p]}
     * with the value standing for input {@code axis} raised to power {@code p}, for {@code p} up to
     * {@code maxPows[axis]}; here the plain power, with every power above zero of a bad input bad.
     * {@code forward} says which original transformation the terms belong to.
     *
     * @return whether every value is good
     */
    boolean polyPowers(double[][] work, int[] maxPows, double[] input, boolean forward) {
        boolean allGood = true;
        for (int axis = 0; axis < work.length; axis++) {
            double[] pwork = work[axis];
            pwork[0] = 1.0;
            double x = input[axis];
            int mp = maxPows[axis];
            if (x == AST__BAD) {
                Arrays.fill(pwork, 1, mp + 1, AST__BAD);
                allGood &= mp == 0;
            } else {
                for (int p = 1; p <= mp; p++) {
                    pwork[p] = pwork[p - 1] * x;
                }
            }
        }
        return allGood;
    }

    /**
     * Whether {@link #polyPowers} forms plain powers by repeated multiplication from 1, which lets
     * {@link #polyTransform} form them for a block of points at once.
     */
    boolean isMonomial() {
        return true;
    }

    /** The highest power of each variable in the original forward or inverse terms. */
    // The loop takes maxima, which PMD mistakes for a copy.
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private int[] maxPowers(boolean fwd) {
        int[][][] pow = fwd ? powerF : powerI;
        int[] mp = new int[fwd ? createdNin() : createdNout()];
        for (int[][] perOut : pow) {
            for (int[] powers : perOut) {
                for (int a = 0; a < mp.length; a++) {
                    if (powers[a] > mp[a]) {
                        mp[a] = powers[a];
                    }
                }
            }
        }
        return mp;
    }

    /**
     * Transforms {@code npoint} points through the original forward ({@code fwd}) or inverse
     * polynomials, as C's {@code Transform} (polymap.c) does, writing {@code bad} (bad or NaN) for
     * an output that is bad, as C does, or NaN, which C leaves as NaN unless the environment
     * variable {@code AST_REPLACE_NAN} is set (its {@code astTransform} then makes it bad,
     * mapping.c:24815, pointset.c:1896). An infinite output is kept. With plain powers, good coefficients and enough points, the
     * points are evaluated a block at a time ({@link #polyTransformBlocked}), which gives the same
     * values.
     */
    private void polyTransform(boolean fwd, double[][] in, int npoint, double[][] out, double bad) {
        Terms t = new Terms(fwd);
        double[][] work = new double[t.maxPows.length][];
        for (int a = 0; a < work.length; a++) {
            // C allocates at least two values per axis, since a subclass may store the first power
            // whatever the highest (polymap.c Transform).
            work[a] = new double[Math.max(1, t.maxPows[a]) + 1];
        }
        double[] inp = new double[in.length];
        if (isMonomial() && !t.badCoeff && npoint >= BLOCKED_MIN) {
            polyTransformBlocked(t, in, npoint, out, bad, work, inp);
        } else {
            for (int i = 0; i < npoint; i++) {
                evalPoint(t, in, out, work, inp, i, bad);
            }
        }
    }

    /** The coefficients of one original transformation, with what {@link #polyTransform} needs of them. */
    private final class Terms {
        final boolean fwd;
        final int[] nco;
        final double[][] cof;
        final int[][][] pow;
        final int[] maxPows;
        final boolean badCoeff;

        Terms(boolean fwd) {
            this.fwd = fwd;
            nco = fwd ? ncoeffF : ncoeffI;
            cof = fwd ? coeffF : coeffI;
            pow = fwd ? powerF : powerI;
            maxPows = maxPowers(fwd);
            boolean anyBad = false;
            for (double[] c : cof) {
                for (double v : c) {
                    anyBad |= v == AST__BAD;
                }
            }
            badCoeff = anyBad;
        }
    }

    /** One point of {@link #polyTransform}: polymap.c {@code Transform} for point {@code i}. */
    private void evalPoint(Terms t, double[][] in, double[][] out, double[][] work, double[] inp, int i, double bad) {
        for (int a = 0; a < inp.length; a++) {
            inp[a] = in[a][i];
        }
        boolean allGood = polyPowers(work, t.maxPows, inp, t.fwd);
        if (allGood && !t.badCoeff) {
            // No term can be bad, so this is the loop below without its checks. A zero power's
            // value is exactly 1, so multiplying by it changes nothing.
            for (int o = 0; o < t.nco.length; o++) {
                double outval = 0.0;
                for (int ico = 0; ico < t.nco[o]; ico++) {
                    double term = t.cof[o][ico];
                    int[] p = t.pow[o][ico];
                    for (int a = 0; a < p.length; a++) {
                        term *= work[a][p[a]];
                    }
                    outval += term;
                }
                out[o][i] = Double.isNaN(outval) ? bad : outval;
            }
            return;
        }
        for (int o = 0; o < t.nco.length; o++) {
            double outval = 0.0;
            for (int ico = 0; ico < t.nco[o] && outval != AST__BAD; ico++) {
                double term = t.cof[o][ico];
                if (term == AST__BAD) {
                    outval = AST__BAD;
                } else {
                    int[] p = t.pow[o][ico];
                    for (int a = 0; a < p.length; a++) {
                        if (p[a] > 0) {
                            double xp = work[a][p[a]];
                            if (xp == AST__BAD) {
                                outval = AST__BAD;
                                break;
                            }
                            term *= xp;
                        }
                    }
                }
                if (outval != AST__BAD) {
                    outval += term;
                }
            }
            out[o][i] = outval == AST__BAD || Double.isNaN(outval) ? bad : outval;
        }
    }

    /**
     * {@link #polyTransform} for plain powers and good coefficients, over blocks of points with the
     * point loop innermost: each input's powers for the block, then each output's terms, one term at
     * a time across the block. Each point's arithmetic is that of {@link #evalPoint}'s unchecked
     * loop in the same order, so the results are identical. A point with a bad input is then
     * evaluated again by {@link #evalPoint}, which applies C's per-term checks, so that an output
     * using no power of the bad input stays good.
     */
    // MODERN-JAVA(16): the Vector API could evaluate a block several points at a time, as Rust's
    // polymap_simd.rs does with fused multiply-adds.
    private void polyTransformBlocked(
            Terms t, double[][] in, int npoint, double[][] out, double bad, double[][] work, double[] inp) {
        int blen = Math.min(BLOCK, npoint);
        int naxes = t.maxPows.length;
        // Row first[a] + p of powers holds the block's values of axis a to the power p.
        int[] first = new int[naxes];
        int nrows = 0;
        for (int a = 0; a < naxes; a++) {
            first[a] = nrows;
            nrows += t.maxPows[a] + 1;
        }
        double[][] powers = new double[nrows][blen];
        double[] acc = new double[blen];
        double[] term = new double[blen];
        for (int lo = 0; lo < npoint; lo += BLOCK) {
            int len = Math.min(BLOCK, npoint - lo);
            for (int a = 0; a < naxes; a++) {
                double[] x = in[a];
                Arrays.fill(powers[first[a]], 0, len, 1.0);
                for (int p = 1; p <= t.maxPows[a]; p++) {
                    double[] prev = powers[first[a] + p - 1];
                    double[] row = powers[first[a] + p];
                    for (int k = 0; k < len; k++) {
                        row[k] = prev[k] * x[lo + k];
                    }
                }
            }
            for (int o = 0; o < t.nco.length; o++) {
                Arrays.fill(acc, 0, len, 0.0);
                for (int ico = 0; ico < t.nco[o]; ico++) {
                    Arrays.fill(term, 0, len, t.cof[o][ico]);
                    int[] p = t.pow[o][ico];
                    for (int a = 0; a < naxes; a++) {
                        if (p[a] > 0) {
                            double[] row = powers[first[a] + p[a]];
                            for (int k = 0; k < len; k++) {
                                term[k] *= row[k];
                            }
                        }
                    }
                    for (int k = 0; k < len; k++) {
                        acc[k] += term[k];
                    }
                }
                double[] dest = out[o];
                for (int k = 0; k < len; k++) {
                    dest[lo + k] = Double.isNaN(acc[k]) ? bad : acc[k];
                }
            }
            for (int i = lo; i < lo + len; i++) {
                for (int a = 0; a < naxes; a++) {
                    if (in[a][i] == AST__BAD) {
                        evalPoint(t, in, out, work, inp, i, bad);
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The iterative inverse
    // ------------------------------------------------------------------

    /**
     * C's {@code GetJacobian}: the PolyMaps, one per original input, whose forward transformations
     * give the derivatives of every original output with respect to that input, made once.
     */
    private PolyMap[] getJacobian() {
        if (jacobian == null) {
            int nin = createdNin();
            int nout = createdNout();
            PolyMap[] jac = new PolyMap[nin];
            int ncof = 0;
            for (int n : ncoeffF) {
                ncof += n;
            }
            double[] coeffs = new double[ncof * (2 + nin)];
            for (int icol = 0; icol < nin; icol++) {
                int total = 0;
                int pc = 0;
                for (int irow = 0; irow < nout; irow++) {
                    for (int icof = 0; icof < ncoeffF[irow]; icof++) {
                        int power = powerF[irow][icof][icol];
                        if (power > 0) {
                            total++;
                            coeffs[pc++] = coeffF[irow][icof] * power;
                            coeffs[pc++] = irow + 1;
                            for (int iin = 0; iin < nin; iin++) {
                                coeffs[pc++] = iin != icol ? powerF[irow][icof][iin] : powerF[irow][icof][iin] - 1;
                            }
                        }
                    }
                }
                jac[icol] = new PolyMap(nin, nout, total, coeffs, 0, null);
            }
            jacobian = jac;
        }
        return jacobian;
    }

    /**
     * C's {@code LinearGuess}: a linear approximation of the original forward transformation, made
     * once. The constant and linear coefficients of the forward transformation, or of the inverse
     * one (and then inverted) if there is no forward one, become a MatrixMap followed by a ShiftMap;
     * a later coefficient for the same input replaces an earlier one, as C assigns them. A row with
     * no linear coefficient gets 1 on the diagonal so that the matrix can be inverted, and a matrix
     * that still cannot be inverted is replaced by a UnitMap.
     */
    private Mapping linearGuess() {
        if (lintrunc != null) {
            return lintrunc;
        }
        boolean inverse = ncoeffF == null;
        int[] nco = inverse ? ncoeffI : ncoeffF;
        double[][] cof = inverse ? coeffI : coeffF;
        int[][][] pow = inverse ? powerI : powerF;
        int nin = inverse ? createdNout() : createdNin();
        int nout = inverse ? createdNin() : createdNout();
        double[] matrix = new double[nin * nout];
        double[] vector = new double[nout];
        for (int iout = 0; iout < nout; iout++) {
            for (int ico = 0; ico < nco[iout]; ico++) {
                // 0: constant, 1: linear in input jin, 2: anything else.
                int flag = 0;
                int jin = 0;
                for (int iin = 0; iin < nin; iin++) {
                    int p = pow[iout][ico][iin];
                    if (p != 0) {
                        if (p != 1 || flag == 1) {
                            flag = 2;
                            break;
                        }
                        flag = 1;
                        jin = iin;
                    }
                }
                if (flag == 0) {
                    vector[iout] = cof[iout][ico];
                } else if (flag == 1) {
                    matrix[jin + iout * nin] = cof[iout][ico];
                }
            }
        }
        for (int iout = 0; iout < nout; iout++) {
            boolean ok = false;
            for (int iin = 0; iin < nin; iin++) {
                if (matrix[iin + iout * nin] != 0.0) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                matrix[iout + iout * nin] = 1.0;
            }
        }
        Mapping mm = new MatrixMap(nin, nout, MatrixMap.FULL, matrix);
        if (!mm.getTranInverse()) {
            mm = new UnitMap(nin);
        }
        Mapping result = new CmpMap(mm, new ShiftMap(vector), true);
        if (inverse) {
            result.invert();
        }
        lintrunc = result;
        return result;
    }

    /**
     * Replaces each value of the first {@code npoint} points that is not finite with bad. C's
     * {@code astTransform} does this to its results only when the environment variable {@code
     * AST_REPLACE_NAN} is set (mapping.c:24815, pointset.c:1896); by default it leaves them.
     */
    private static void replaceNaN(double[][] pts, int ncoord, int npoint) {
        for (int c = 0; c < ncoord; c++) {
            double[] axis = pts[c];
            for (int p = 0; p < npoint; p++) {
                if (!Double.isFinite(axis[p])) {
                    axis[p] = AST__BAD;
                }
            }
        }
    }

    /**
     * C's {@code IterInverse}: the original inverse transformation of {@code npoint} positions in
     * {@code target} (original outputs) into {@code result} (original inputs), by Newton-Raphson
     * iteration of the original forward transformation. The first guess is the inverse of the
     * linear truncation ({@link #linearGuess}). Each iteration transforms the guesses with the
     * forward transformation and with the Jacobian's PolyMaps, and solves the Jacobian equation for
     * each position not yet converged with {@code palDmat}; a singular Jacobian makes the position
     * bad. A position has converged when its step {@code dx} satisfies {@code |dx|^2 <= tol^2
     * |x|^2}; one that has not converged after NiterInverse iterations keeps its last position. Bad
     * values are carried through the arithmetic as C carries them, so a position bad on one axis
     * can keep a value on another. Each set of values C gets from {@code astTransform}, and the
     * result, has its values that are not finite replaced by bad ones here ({@link #replaceNaN}),
     * which C's {@code astTransform} does only when {@code AST_REPLACE_NAN} is set; by default C
     * carries an infinity or a NaN that arises in the iteration on through it.
     */
    private void iterativeInverse(double[][] target, int npoint, double[][] result) {
        int ncoord = createdNin();
        if (ncoord != createdNout()) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astTransform(" + astClass() + "): Supplied " + astClass() + " has unequal numbers of inputs "
                            + "and outputs and therefore an iterative inverse cannot be used (internal AST "
                            + "Programming errpr)."); // codespell:ignore errpr
        }
        PolyMap[] jacob = getJacobian();
        double[][] res = result.length == ncoord ? result : Arrays.copyOf(result, ncoord);
        linearGuess().transformInto(target, npoint, false, res);
        replaceNaN(res, ncoord, npoint);

        int maxiter = getNiterInverse();
        double maxerr = getTolInverse();
        maxerr *= maxerr;
        double[][] work = new double[ncoord][npoint];
        double[][][] psJac = new double[ncoord][ncoord][npoint];
        boolean[] flags = new boolean[npoint];
        double[] mat = new double[ncoord * ncoord];
        double[] vec = new double[ncoord];
        int nconv = 0;
        for (int iter = 0; iter < maxiter && nconv < npoint; iter++) {
            polyTransform(true, res, npoint, work, AST__BAD);
            replaceNaN(work, ncoord, npoint);
            for (int ic = 0; ic < ncoord; ic++) {
                double[] pa = target[ic];
                double[] pb = work[ic];
                for (int ip = 0; ip < npoint; ip++) {
                    if (pa[ip] != AST__BAD && pb[ip] != AST__BAD) {
                        pb[ip] = pa[ip] - pb[ip];
                    } else {
                        pb[ip] = AST__BAD;
                    }
                }
            }
            for (int ic = 0; ic < ncoord; ic++) {
                jacob[ic].transformInto(res, npoint, true, psJac[ic]);
                replaceNaN(psJac[ic], ncoord, npoint);
            }
            for (int ip = 0; ip < npoint; ip++) {
                if (flags[ip]) {
                    continue;
                }
                for (int irow = 0; irow < ncoord; irow++) {
                    for (int icol = 0; icol < ncoord; icol++) {
                        mat[irow * ncoord + icol] = psJac[icol][irow][ip];
                    }
                    vec[irow] = work[irow][ip];
                }
                if (!Pal.dmat(ncoord, mat, vec)) {
                    for (int ic = 0; ic < ncoord; ic++) {
                        res[ic][ip] = AST__BAD;
                    }
                    flags[ip] = true;
                    nconv++;
                } else {
                    double vlensq = 0.0;
                    double xlensq = 0.0;
                    for (int ic = 0; ic < ncoord; ic++) {
                        double xx = res[ic][ip] + vec[ic];
                        res[ic][ip] = xx;
                        xlensq += xx * xx;
                        vlensq += vec[ic] * vec[ic];
                    }
                    if (vlensq <= maxerr * xlensq) {
                        flags[ip] = true;
                        nconv++;
                    }
                }
            }
        }
        replaceNaN(res, ncoord, npoint);
    }
}
