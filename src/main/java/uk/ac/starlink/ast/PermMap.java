package uk.ac.starlink.ast;

import java.util.Arrays;

/**
 * A Mapping which permutes the order of coordinates, and possibly also changes the number of
 * coordinates, between its input and output. A PermMap may also assign constant values to
 * coordinates, which is useful when the number of coordinates is increased, as it allows fixed
 * values to be assigned to the new ones.
 *
 * <p>No attempt is made to make the forward and inverse transformations consistent with each
 * other, so the permutations may be chosen to give whatever effect is wanted.
 *
 * <p>As in C, a permutation is held with zero-based coordinate numbers: a value from zero to one
 * less than the number of coordinates on the other side selects that coordinate, a negative value
 * {@code -j} selects constant {@code j}, and any other value gives a bad coordinate. A
 * permutation that is not given is held as null, and selects the coordinate with the same number.
 *
 * <p>Ported from Rust {@code mappings/perm.rs} and C {@code permmap.c}.
 */
public class PermMap extends Mapping {

    /**
     * For each input coordinate, the output coordinate it takes in the inverse transformation (C's
     * {@code inperm}), or null for the null permutation.
     */
    private int[] inperm;

    /**
     * For each output coordinate, the input coordinate it takes in the forward transformation (C's
     * {@code outperm}), or null for the null permutation.
     */
    private int[] outperm;

    /** The constants the permutations may select (C's {@code constant}), or null if there are none. */
    private double[] constant;

    /** The PermSplit attribute: 0, 1, or {@link #UNSET_FLAG}. */
    private int permSplit = UNSET_FLAG;

    /**
     * Creates a PermMap.
     *
     * @param nin the number of input coordinates
     * @param inperm for each input coordinate, the number of the output coordinate whose value it
     *     takes, coordinates being numbered from 1, so that this array defines the inverse
     *     transformation; or null, so that each input coordinate takes the output coordinate with
     *     the same number, or the bad value if there is none. A negative value {@code -j} takes
     *     element {@code j} of {@code constant}, and zero gives the bad value.
     * @param nout the number of output coordinates
     * @param outperm for each output coordinate, the number of the input coordinate whose value it
     *     takes, so that this array defines the forward transformation; or null, so that each
     *     output coordinate takes the input coordinate with the same number, or the bad value if
     *     there is none. Negative values and zero are as for {@code inperm}.
     * @param constant the values a negative entry in {@code inperm} or {@code outperm} selects, -1
     *     the first, -2 the second, and so on; or null, which acts as an array of bad values. An
     *     entry selecting an element beyond the array gives the bad value.
     * @throws AstException with status {@code AST__BADNI} if {@code inperm} has fewer than {@code
     *     nin} elements or {@code nin} is negative, or {@code AST__BADNO} if {@code outperm} has
     *     fewer than {@code nout} elements or {@code nout} is negative
     */
    public PermMap(int nin, int[] inperm, int nout, int[] outperm, double[] constant) {
        super(checkedNin(nin, inperm, nout, outperm), nout, true, true);
        this.inperm = internal(inperm, nin, nout);
        this.outperm = internal(outperm, nout, nin);
        if (constant != null) {
            int neg = 0;
            neg = Math.min(neg, mostNegative(inperm, nin));
            neg = Math.min(neg, mostNegative(outperm, nout));
            int nconst = Math.min(constant.length, -neg);
            this.constant = nconst > 0 ? Arrays.copyOf(constant, nconst) : null;
        }
    }

    /** {@code nin}, after checking that each permutation given has an element for each coordinate. */
    private static int checkedNin(int nin, int[] inperm, int nout, int[] outperm) {
        if (inperm != null && nin >= 0 && inperm.length < nin) {
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astPermMap(PermMap): " + inperm.length + " input permutation values supplied; the PermMap has "
                            + nin + " inputs.");
        }
        if (outperm != null && nout >= 0 && outperm.length < nout) {
            throw Ast.err(
                    AstException.AST__BADNO,
                    "astPermMap(PermMap): " + outperm.length + " output permutation values supplied; the PermMap has "
                            + nout + " outputs.");
        }
        return nin;
    }

    /**
     * The first {@code n} elements of a permutation given with coordinates numbered from 1, in
     * C's internal form (permmap.c {@code astPermMapId_}): zero-based, with zero, which gives the
     * bad value, as {@code nother}, a coordinate that does not exist. Null for no permutation, or
     * none of any length, as C's {@code astStore} gives for no elements.
     */
    private static int[] internal(int[] perm, int n, int nother) {
        if (perm == null || n <= 0) {
            return null;
        }
        int[] out = new int[n];
        for (int coord = 0; coord < n; coord++) {
            int p = perm[coord];
            out[coord] = p < 0 ? p : p == 0 ? nother : p - 1;
        }
        return out;
    }

    /** The most negative of the first {@code n} elements of {@code perm}, or zero. */
    private static int mostNegative(int[] perm, int n) {
        int neg = 0;
        if (perm != null) {
            for (int i = 0; i < n; i++) {
                neg = Math.min(neg, perm[i]);
            }
        }
        return neg;
    }

    /**
     * Loads a PermMap from its description, as C's {@code astLoadPermMap} does: {@code pmsplt},
     * the permutations from {@code In<i>} and {@code Out<i>} (zero, the bad value, if absent)
     * unless {@code InCpy} or {@code OutCpy} says a permutation is null, and {@code Nconst}
     * constants from {@code Con<i>}, bad if absent.
     */
    PermMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("PermMap");
        int nin = createdNin();
        int nout = createdNout();
        Long split = part.getIntIfSet("pmsplt");
        if (split != null) {
            permSplit = split != 0 ? 1 : 0;
        }
        boolean inCpy = part.readInt("InCpy", 0) != 0;
        boolean outCpy = part.readInt("OutCpy", 0) != 0;
        if (!inCpy && nin > 0) {
            inperm = readPerm(part, "In", nin, nout);
        }
        if (!outCpy && nout > 0) {
            outperm = readPerm(part, "Out", nout, nin);
        }
        int nconst = Math.max(0, part.readInt("Nconst", 0));
        if (nconst > 0) {
            constant = new double[nconst];
            for (int iconst = 0; iconst < nconst; iconst++) {
                constant[iconst] = part.readDouble("Con" + (iconst + 1), AST__BAD);
            }
        }
    }

    /** The permutation {@code <prefix><i>} of {@code n} coordinates, in C's internal form. */
    private static int[] readPerm(NativeNode part, String prefix, int n, int nother) {
        int[] perm = new int[n];
        for (int coord = 0; coord < n; coord++) {
            int ival = part.readInt(prefix + (coord + 1), 0);
            perm[coord] = ival == 0 ? nother : ival > 0 ? ival - 1 : ival;
        }
        return perm;
    }

    @Override
    String dumpComment() {
        return "Coordinate permutation";
    }

    /**
     * Writes the PermMap's items as C's {@code Dump} (permmap.c) does: {@code pmsplt}; {@code
     * OutCpy} or the forward permutation and {@code InCpy} or the inverse one, numbered from 1
     * with zero for a coordinate that does not exist; and the constants up to the highest the
     * permutations select, a bad one written unset so that it reads back as bad.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        int nin = createdNin();
        int nout = createdNout();
        boolean split = getPermSplit();
        node.writeInt(
                "pmsplt",
                testPermSplit(),
                false,
                split ? 1 : 0,
                split ? "Use alternative astMapSplit implementation" : "Use normal astMapSplit implementation");
        int nconst = writePerm(node, outperm, "Out", "Output", "input", nout, nin, 0);
        nconst = writePerm(node, inperm, "In", "Input", "output", nin, nout, nconst);
        if (constant == null) {
            nconst = 0;
        }
        node.writeInt("Nconst", nconst != 0, false, nconst, "Number of constants");
        for (int iconst = 0; iconst < nconst && iconst < constant.length; iconst++) {
            String key = "Con" + (iconst + 1);
            String comment = "Constant number " + (iconst + 1);
            if (constant[iconst] != AST__BAD) {
                node.writeDouble(key, true, true, constant[iconst], comment);
            } else {
                node.writeString(key, false, true, "<bad>", comment);
            }
        }
    }

    /**
     * Writes one permutation, or the {@code <prefix>Cpy} item saying it is null, and returns the
     * highest constant number it or the permutations before it select ({@code nconst}).
     */
    private int writePerm(
            NativeNode node, int[] perm, String prefix, String side, String other, int n, int nother, int nconst) {
        boolean cpy = perm == null;
        node.writeInt(
                prefix + "Cpy",
                cpy,
                false,
                cpy ? 1 : 0,
                cpy ? side + " coordinates = " + other + " coordinates" : side + " coordinates specified individually");
        if (cpy) {
            return nconst;
        }
        int top = nconst;
        for (int coord = 0; coord < n; coord++) {
            int ival = perm[coord];
            String comment;
            if (ival >= nother) {
                ival = 0;
                comment = side + " coordinate " + (coord + 1) + " is \"bad\"";
            } else if (ival >= 0) {
                ival++;
                comment = side + " coordinate " + (coord + 1) + " = " + other + " coordinate " + ival;
            } else {
                comment = constant != null
                        ? side + " coordinate " + (coord + 1) + " = constant no. " + -ival
                        : side + " coordinate " + (coord + 1) + " is \"bad\"";
                top = Math.max(top, -ival);
            }
            node.writeInt(prefix + (coord + 1), true, true, ival, comment);
        }
        return top;
    }

    /** Gives the copy its own permutations and constants, as C's {@code Copy} (permmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (inperm != null) {
            inperm = inperm.clone();
        }
        if (outperm != null) {
            outperm = outperm.clone();
        }
        if (constant != null) {
            constant = constant.clone();
        }
    }

    // ------------------------------------------------------------------
    // PermSplit
    // ------------------------------------------------------------------

    /**
     * The PermSplit attribute (protected in C): which method {@code mapSplit} uses to split the
     * PermMap. When false (the default) it splits on the forward transformation; when true it
     * ignores the forward transformation and takes the inverse one to be its exact inverse.
     */
    boolean getPermSplit() {
        return permSplit != UNSET_FLAG && permSplit != 0;
    }

    /**
     * Sets the PermSplit attribute. A value different from the one held, as any value is from an
     * unset one, discards the record that the PermMap has been simplified (permmap.c).
     */
    void setPermSplit(boolean split) {
        int value = split ? 1 : 0;
        if (value != permSplit) {
            clearIsSimple();
        }
        permSplit = value;
    }

    /** Whether the PermSplit attribute is set. */
    boolean testPermSplit() {
        return permSplit != UNSET_FLAG;
    }

    /** Clears the PermSplit attribute, which also discards the record of simplification. */
    void clearPermSplit() {
        clearIsSimple();
        permSplit = UNSET_FLAG;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * The permutation the transformation in direction {@code forward} applies: the inverse one
     * when {@code forward} equals the Invert attribute (permmap.c {@code Transform}).
     */
    private int[] permFor(boolean forward) {
        return getInvert() == forward ? inperm : outperm;
    }

    /**
     * The coordinate (or, if negative, constant) that output {@code coord} of the transformation
     * in direction {@code forward} takes, as C's {@code PERMVAL} macro (permmap.c) finds it. A
     * null permutation gives {@code coord} itself if it is below {@code maxperm}, and otherwise
     * -1, which selects the first constant: {@code maxperm} is the number of outputs of the
     * transformation when it applies the inverse permutation, so this arises only for a null
     * forward permutation with more outputs than inputs, whose surplus outputs C gives the first
     * constant, or the bad value if there are no constants.
     */
    private static int permVal(int[] perm, int coord, int maxperm) {
        if (perm != null) {
            return perm[coord];
        }
        return coord < maxperm ? coord : -1;
    }

    /** C's {@code maxperm} (permmap.c {@code Transform}) for the transformation in direction {@code forward}. */
    private int maxPerm(boolean forward, int ncoordIn, int ncoordOut) {
        return getInvert() == forward ? ncoordOut : ncoordIn;
    }

    /** Constant {@code -p}, or the bad value if there is no such constant. */
    private double constantFor(int p) {
        int i = -p - 1;
        return constant != null && i < constant.length ? constant[i] : AST__BAD;
    }

    /**
     * Permutes the coordinates as C's {@code Transform} (permmap.c) does: each output takes the
     * input the permutation selects, with a bad input giving a bad output, or the constant it
     * selects, or the bad value. A null permutation selects the input with the same number (see
     * {@link #permVal}).
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        int[] perm = permFor(forward);
        int ncoordIn = in.length;
        int ncoordOut = forward ? getNout() : getNin();
        int maxperm = maxPerm(forward, ncoordIn, ncoordOut);
        for (int coord = 0; coord < ncoordOut; coord++) {
            int p = permVal(perm, coord, maxperm);
            double[] o = out[coord];
            if (p >= 0 && p < ncoordIn) {
                double[] a = in[p];
                for (int i = 0; i < npoint; i++) {
                    double v = a[i];
                    o[i] = isBad(v) ? AST__BAD : v;
                }
            } else if (p < 0) {
                Arrays.fill(o, 0, npoint, constantFor(p));
            } else {
                Arrays.fill(o, 0, npoint, AST__BAD);
            }
        }
    }

    /**
     * NaN protocol: inputs are copied as they are, and a bad constant, or a coordinate given the
     * bad value, is NaN.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        int[] perm = permFor(forward);
        int ncoordIn = in.length;
        int ncoordOut = forward ? getNout() : getNin();
        int maxperm = maxPerm(forward, ncoordIn, ncoordOut);
        for (int coord = 0; coord < ncoordOut; coord++) {
            int p = permVal(perm, coord, maxperm);
            double[] o = out[coord];
            if (p >= 0 && p < ncoordIn) {
                System.arraycopy(in[p], 0, o, 0, npoint);
            } else {
                double c = p < 0 ? constantFor(p) : AST__BAD;
                Arrays.fill(o, 0, npoint, isBad(c) ? Double.NaN : c);
            }
        }
    }
}
