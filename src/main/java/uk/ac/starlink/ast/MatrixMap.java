package uk.ac.starlink.ast;

import java.util.Arrays;
import uk.ac.starlink.ast.internal.pal.Dmat;

/**
 * A Mapping which performs a general linear transformation. Each set of input coordinates,
 * regarded as a column vector, is pre-multiplied by a matrix, whose elements are given when the
 * MatrixMap is created, to give a new column vector holding the output coordinates. If
 * appropriate, the inverse transformation may also be performed.
 *
 * <p>The forward transformation is normally always available, but the inverse transformation
 * only when the matrix is square and non-singular. As an exception, the inverse transformation is
 * always available when a unit or diagonal matrix is given; if such a matrix is not square, the
 * input coordinates that cannot be recovered from the output coordinates are set to zero.
 *
 * <p>As in C, the matrix is stored in the most compact of three forms: in full, as its diagonal
 * elements only, or, for a unit matrix, as nothing at all. A full matrix whose off-diagonal
 * elements are all zero, or negligible beside the other elements of their row and column, is
 * stored in diagonal form, and a diagonal matrix whose elements are all one in unit form.
 *
 * <p>Ported from Rust {@code mappings/matrix.rs} and C {@code matrixmap.c}.
 */
public class MatrixMap extends Mapping {

    /** C's storage form of a matrix with every element stored, in row order. */
    static final int FULL = 0;

    /** C's storage form of a matrix with only its diagonal elements stored. */
    static final int DIAGONAL = 1;

    /** C's storage form of a unit matrix, which stores no elements. */
    static final int UNIT = 2;

    /** The names of the storage forms in a dump, indexed by form. */
    private static final String[] FORM_NAMES = {"Full", "Diagonal", "Unit"};

    /** C's {@code sqrt(DBL_EPSILON)}: the significance limit, relative to a row or column. */
    private static final double SQRT_EPSILON = Math.sqrt(Math.ulp(1.0));

    /** The storage form, {@link #FULL}, {@link #DIAGONAL} or {@link #UNIT}. */
    private int form;

    /**
     * The forward matrix (C's {@code f_matrix}): {@code nout} rows of {@code nin} elements in row
     * order, or the {@code min(nin, nout)} diagonal elements; null in unit form or when undefined.
     */
    private double[] fMatrix;

    /**
     * The inverse matrix (C's {@code i_matrix}): {@code nin} rows of {@code nout} elements, or the
     * diagonal elements; null in unit form or when the inverse is undefined.
     */
    private double[] iMatrix;

    /**
     * Creates a MatrixMap whose matrix is a unit matrix: its diagonal elements are one and all
     * others zero.
     *
     * @param nin the number of input coordinates, which is the number of columns in the matrix
     * @param nout the number of output coordinates, which is the number of rows in the matrix
     * @throws AstException with status {@code AST__BADNI} or {@code AST__BADNO} if a count is
     *     negative
     */
    public MatrixMap(int nin, int nout) {
        this(nin, nout, UNIT, null);
    }

    /**
     * Creates a MatrixMap whose matrix is diagonal: only its diagonal elements are given, and all
     * others are zero.
     *
     * @param nin the number of input coordinates, which is the number of columns in the matrix
     * @param nout the number of output coordinates, which is the number of rows in the matrix
     * @param diag the {@code min(nin, nout)} diagonal elements
     * @throws AstException with status {@code AST__MTRMT} if {@code diag} is null or holds only
     *     bad values, {@code AST__DIMIN} if it is too short, or {@code AST__BADNI} or {@code
     *     AST__BADNO} if a count is negative
     */
    public MatrixMap(int nin, int nout, double[] diag) {
        this(nin, nout, DIAGONAL, checkedDiagonal(nin, nout, diag));
    }

    /**
     * Creates a MatrixMap from a full matrix.
     *
     * @param nin the number of input coordinates, which is the number of columns in the matrix
     * @param nout the number of output coordinates, which is the number of rows in the matrix
     * @param fullmatrix the matrix: {@code nout} rows, each of {@code nin} elements
     * @throws AstException with status {@code AST__MTRMT} if {@code fullmatrix} is null or holds
     *     only bad values, {@code AST__DIMIN} if it is not {@code nout} by {@code nin}, or {@code
     *     AST__BADNI} or {@code AST__BADNO} if a count is negative
     */
    public MatrixMap(int nin, int nout, double[][] fullmatrix) {
        this(nin, nout, FULL, flattened(nin, nout, fullmatrix));
    }

    /**
     * Creates a MatrixMap as C's {@code astInitMatrixMap} does: the given elements, in the given
     * form, are stored with any NaN or infinite value replaced by {@link #AST__BAD}, the inverse is
     * found where it exists, and the matrix is compressed into the most compact form.
     *
     * @param form {@link #FULL}, {@link #DIAGONAL} or {@link #UNIT}
     * @param matrix the elements, as {@code form} stores them; not used for a unit matrix
     */
    MatrixMap(int nin, int nout, int form, double[] matrix) {
        super(checkedNin(nin, form, matrix), nout, true, true);
        int usedForm;
        int nel;
        if (form > DIAGONAL) {
            usedForm = UNIT;
            nel = 0;
        } else if (form == DIAGONAL) {
            usedForm = DIAGONAL;
            nel = Math.min(nin, nout);
        } else {
            usedForm = FULL;
            nel = nin * nout;
        }
        double[] fmat = nel > 0 ? Arrays.copyOf(matrix, nel) : null;
        if (fmat != null) {
            int nuse = 0;
            for (int i = 0; i < nel; i++) {
                if (!Double.isFinite(fmat[i])) {
                    fmat[i] = AST__BAD;
                } else if (fmat[i] != AST__BAD) {
                    nuse++;
                }
            }
            if (nuse == 0) {
                throw Ast.err(
                        AstException.AST__MTRMT,
                        "astInitMatrixMap(MatrixMap): Supplied matrix contains only bad values.");
            }
        }
        this.form = usedForm;
        fMatrix = fmat;
        iMatrix = invertMatrix(usedForm, nout, nin, fmat);
        compressMatrix(nin, nout);
    }

    /** {@code nin}, after C's check that a matrix is given unless the form is unit. */
    private static int checkedNin(int nin, int form, double[] matrix) {
        if (form < UNIT && matrix == null) {
            throw Ast.err(AstException.AST__MTRMT, "astInitMatrixMap(MatrixMap): NULL matrix supplied.");
        }
        return nin;
    }

    /** {@code diag}, checked to hold an element for each diagonal position. */
    private static double[] checkedDiagonal(int nin, int nout, double[] diag) {
        int ndiag = Math.min(nin, nout);
        if (diag != null && ndiag > 0 && diag.length < ndiag) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astMatrixMap(MatrixMap): The diagonal array holds " + diag.length
                            + " values.\nThis should not be less than " + ndiag + ".");
        }
        return diag;
    }

    /** The elements of {@code fullmatrix} in row order, checked to be {@code nout} by {@code nin}. */
    private static double[] flattened(int nin, int nout, double[][] fullmatrix) {
        if (fullmatrix == null || nin < 0 || nout < 0) {
            return null;
        }
        if (fullmatrix.length != nout) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astMatrixMap(MatrixMap): The matrix has " + fullmatrix.length + " rows.\nThis should be " + nout
                            + ", the number of output coordinates.");
        }
        double[] flat = new double[nin * nout];
        for (int row = 0; row < nout; row++) {
            if (fullmatrix[row].length != nin) {
                throw Ast.err(
                        AstException.AST__DIMIN,
                        "astMatrixMap(MatrixMap): Row " + (row + 1) + " of the matrix holds " + fullmatrix[row].length
                                + " values.\nThis should be " + nin + ", the number of input coordinates.");
            }
            System.arraycopy(fullmatrix[row], 0, flat, row * nin, nin);
        }
        return flat;
    }

    /**
     * Loads a MatrixMap from its description, as C's {@code astLoadMatrixMap} does: the storage
     * form from {@code Form} (full if absent), and the elements of each matrix from {@code M<i>}
     * and {@code IM<i>}, bad if absent. A matrix with no element given is undefined, except that
     * an inverse with no element given is found from the forward matrix. The matrix is not
     * compressed.
     */
    MatrixMap(NativeNode node) {
        super(node);
        NativeNode part = node.section("MatrixMap");
        form = findForm(part.readString("Form", FORM_NAMES[FULL]));
        // C reads astGetNin and astGetNout, which an Invert item may have exchanged; neither the
        // element count nor the inverse depends on which is which.
        int nin = createdNin();
        int nout = createdNout();
        int nel = elementCount(form, nin, nout);
        fMatrix = readMatrix(part, "M", nel);
        iMatrix = readMatrix(part, "IM", nel);
        if (iMatrix == null && fMatrix != null) {
            iMatrix = invertMatrix(form, nout, nin, fMatrix);
        }
    }

    /** The {@code nel} elements {@code <prefix><i>}, or null if none is given or not bad. */
    private static double[] readMatrix(NativeNode part, String prefix, int nel) {
        if (nel == 0) {
            return null;
        }
        double[] m = new double[nel];
        boolean def = false;
        for (int el = 0; el < nel; el++) {
            m[el] = part.readDouble(prefix + el, AST__BAD);
            def |= m[el] != AST__BAD;
        }
        return def ? m : null;
    }

    /**
     * The storage form called {@code name}, compared ignoring case as C's {@code FindString}
     * (matrixmap.c) does.
     *
     * @throws AstException with status {@code AST__RDERR} if there is no such form
     */
    private static int findForm(String name) {
        for (int f = 0; f < FORM_NAMES.length; f++) {
            String want = FORM_NAMES[f];
            boolean same = want.length() == name.length();
            for (int i = 0; same && i < want.length(); i++) {
                same = CScan.toLower(want.charAt(i)) == CScan.toLower(name.charAt(i));
            }
            if (same) {
                return f;
            }
        }
        throw Ast.err(
                AstException.AST__RDERR,
                "astRead(Channel): Illegal value '" + name + "' supplied for the MatrixMap component 'Form'.");
    }

    /** The number of elements stored for each matrix of a MatrixMap of the given form and size. */
    private static int elementCount(int form, int nin, int nout) {
        switch (form) {
            case FULL:
                return nin * nout;
            case DIAGONAL:
                return Math.min(nin, nout);
            case UNIT:
                return 0;
            default:
                throw Ast.err(AstException.AST__INTER, "MatrixMap: unknown storage form " + form + ".");
        }
    }

    @Override
    String dumpComment() {
        return "Matrix transformation";
    }

    /**
     * Writes the elements of each matrix that are not bad, as {@code M<i>} and {@code IM<i>}, and
     * the storage form, as C's {@code Dump} (matrixmap.c) does.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        int nel = elementCount(form, createdNin(), createdNout());
        writeMatrix(node, fMatrix, "M", nel, "Forward matrix value");
        writeMatrix(node, iMatrix, "IM", nel, "Inverse matrix value");
        node.writeString("Form", true, true, FORM_NAMES[form], "Matrix storage form");
    }

    private static void writeMatrix(NativeNode node, double[] m, String prefix, int nel, String comment) {
        if (m == null) {
            return;
        }
        for (int el = 0; el < nel; el++) {
            if (m[el] != AST__BAD) {
                node.writeDouble(prefix + el, true, true, m[el], comment);
            }
        }
    }

    /** Gives the copy its own matrices, as C's {@code Copy} (matrixmap.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        if (fMatrix != null) {
            fMatrix = fMatrix.clone();
        }
        if (iMatrix != null) {
            iMatrix = iMatrix.clone();
        }
    }

    @Override
    boolean isLinear() {
        return true;
    }

    /**
     * Gets the TranForward attribute. A unit MatrixMap is defined in both directions; otherwise the
     * matrix the forward transformation applies must exist (matrixmap.c {@code GetTranForward}).
     *
     * @return true if the forward transformation is defined
     */
    @Override
    public boolean getTranForward() {
        if (form == UNIT) {
            return true;
        }
        return getInvert() ? iMatrix != null : fMatrix != null;
    }

    /**
     * Gets the TranInverse attribute. A unit MatrixMap is defined in both directions; otherwise the
     * matrix the inverse transformation applies must exist (matrixmap.c {@code GetTranInverse}).
     *
     * @return true if the inverse transformation is defined
     */
    @Override
    public boolean getTranInverse() {
        if (form == UNIT) {
            return true;
        }
        return getInvert() ? fMatrix != null : iMatrix != null;
    }

    // ------------------------------------------------------------------
    // Storage forms
    // ------------------------------------------------------------------

    /**
     * The inverse of a matrix, as C's {@code InvertMatrix} (matrixmap.c) forms it: null for no
     * matrix or a unit matrix; for a diagonal matrix the reciprocal of each element, bad for a zero
     * or bad one, whatever its shape; for a full matrix, which must be square and free of bad
     * elements, its inverse through {@code palDmat}, or null if it is singular.
     *
     * @param form the storage form of {@code matrix}
     * @param nrow the number of rows of the matrix
     * @param ncol the number of columns of the matrix
     * @param matrix the stored elements, or null
     * @return the stored elements of the inverse, or null if there is none
     */
    static double[] invertMatrix(int form, int nrow, int ncol, double[] matrix) {
        if (matrix == null || form == UNIT) {
            return null;
        }
        if (form == DIAGONAL) {
            int ndiag = Math.min(nrow, ncol);
            double[] out = new double[ndiag];
            for (int i = 0; i < ndiag; i++) {
                double mval = matrix[i];
                out[i] = mval != 0.0 && mval != AST__BAD ? 1.0 / mval : AST__BAD;
            }
            return out;
        }
        if (nrow != ncol) {
            return null;
        }
        int nel = nrow * ncol;
        for (int i = 0; i < nel; i++) {
            if (matrix[i] == AST__BAD) {
                return null;
            }
        }
        double[] out = Arrays.copyOf(matrix, nel);
        double det = Dmat.dmat(nrow, out, new double[nrow]);
        return det == 0.0 ? null : out;
    }

    /**
     * Stores the matrix in the most compact form, as C's {@code CompressMatrix} (matrixmap.c)
     * does. A diagonal matrix whose elements all equal one to within {@code astEQUAL} becomes a
     * unit matrix. In a full matrix, an off-diagonal element no larger than {@code
     * sqrt(DBL_EPSILON)} times the largest element of both its row and its column is set to zero;
     * the matrix then becomes a unit matrix if its diagonal elements are exactly one and all others
     * zero, or a diagonal one if the others are all zero, and otherwise stays full, its inverse
     * found again if an element was set to zero.
     */
    private void compressMatrix() {
        compressMatrix(createdNin(), createdNout());
    }

    /** {@link #compressMatrix()} for a matrix of {@code ncol} columns and {@code nrow} rows. */
    private void compressMatrix(int ncol, int nrow) {
        int ndiag = Math.min(ncol, nrow);
        if (form == UNIT) {
            return;
        }
        if (form == DIAGONAL) {
            boolean unit = true;
            for (int i = 0; i < ndiag; i++) {
                if (!MappingHelpers.astEqual(fMatrix[i], 1.0)) {
                    unit = false;
                }
            }
            if (unit) {
                fMatrix = null;
                iMatrix = null;
                form = UNIT;
            }
            return;
        }
        double[] colmax = new double[ncol];
        double[] rowmax = new double[nrow];
        for (int k = 0; k < nrow; k++) {
            for (int j = 0; j < ncol; j++) {
                double mval = fMatrix[k * ncol + j];
                if (mval != AST__BAD) {
                    mval = Math.abs(mval);
                    if (mval > colmax[j]) {
                        colmax[j] = mval;
                    }
                    if (mval > rowmax[k]) {
                        rowmax[k] = mval;
                    }
                }
            }
        }
        for (int j = 0; j < ncol; j++) {
            colmax[j] *= SQRT_EPSILON;
        }
        for (int k = 0; k < nrow; k++) {
            rowmax[k] *= SQRT_EPSILON;
        }
        boolean newInv = false;
        int newForm = UNIT;
        for (int k = 0; k < nrow; k++) {
            for (int j = 0; j < ncol; j++) {
                int i = k * ncol + j;
                double a = fMatrix[i];
                if (a == AST__BAD) {
                    newForm = FULL;
                } else if (j == k) {
                    if (a != 1.0 && newForm == UNIT) {
                        newForm = DIAGONAL;
                    }
                } else {
                    double mval = Math.abs(a);
                    if (mval <= rowmax[k] && mval <= colmax[j]) {
                        if (a != 0.0) {
                            newInv = true;
                        }
                        fMatrix[i] = 0.0;
                    } else {
                        newForm = FULL;
                    }
                }
            }
        }
        if (newForm == UNIT) {
            fMatrix = null;
            iMatrix = null;
            form = UNIT;
        } else if (newForm == DIAGONAL) {
            double[] fmat = new double[ndiag];
            for (int i = 0; i < ndiag; i++) {
                fmat[i] = fMatrix[i * (ncol + 1)];
            }
            fMatrix = fmat;
            iMatrix = invertMatrix(DIAGONAL, nrow, ncol, fmat);
            form = DIAGONAL;
        } else if (newInv) {
            iMatrix = invertMatrix(FULL, nrow, ncol, fMatrix);
        }
    }

    /**
     * Stores the matrix in full form, with every element, as C's {@code ExpandMatrix}
     * (matrixmap.c) does.
     */
    private void expandMatrix() {
        if (form == FULL) {
            return;
        }
        int ncol = createdNin();
        int nrow = createdNout();
        int ndiag = Math.min(nrow, ncol);
        boolean unit = form == UNIT;
        fMatrix = expanded(unit, fMatrix, nrow, ncol, ndiag);
        iMatrix = expanded(unit, iMatrix, ncol, nrow, ndiag);
        form = FULL;
    }

    /**
     * The full {@code nrow} by {@code ncol} matrix with the given diagonal, or ones on the
     * diagonal if {@code unit}; null if a diagonal matrix has no elements.
     */
    private static double[] expanded(boolean unit, double[] diag, int nrow, int ncol, int ndiag) {
        if (!unit && diag == null) {
            return null;
        }
        double[] full = new double[nrow * ncol];
        for (int i = 0; i < ndiag; i++) {
            full[i * (ncol + 1)] = unit ? 1.0 : diag[i];
        }
        return full;
    }

    // ------------------------------------------------------------------
    // Products and rotations
    // ------------------------------------------------------------------

    /**
     * The product of {@code a} and this MatrixMap, as C's {@code astMtrMult} forms it: a MatrixMap
     * applying this one and then {@code a}, using the matrix each applies forward in its current
     * orientation. A bad element in either gives a bad element in the product. As in C, this
     * MatrixMap is expanded to full form and compressed again, which may zero negligible elements
     * of a full matrix read from a dump.
     *
     * @throws AstException with status {@code AST__MTRML} if either MatrixMap has no forward
     *     transformation, or the number of columns of {@code a} is not the number of rows of this
     */
    MatrixMap mtrMult(MatrixMap a) {
        if (!getTranForward()) {
            throw Ast.err(
                    AstException.AST__MTRML,
                    "astMtrMult(" + astClass() + "): Cannot find the product of 2 MatrixMaps- the first MatrixMap"
                            + " has no forward transformation.");
        }
        if (!a.getTranForward()) {
            throw Ast.err(
                    AstException.AST__MTRML,
                    "astMtrMult(" + astClass() + "): Cannot find the product of 2 MatrixMaps- the second MatrixMap"
                            + " has no forward transformation.");
        }
        int nrowA = a.getNout();
        int ncolA = a.getNin();
        int nrowThis = getNout();
        int ncolThis = getNin();
        if (ncolA != nrowThis) {
            throw Ast.err(
                    AstException.AST__MTRML,
                    "astMtrMult(" + astClass() + "): Number of rows in the first MatrixMap (" + nrowThis
                            + ") does not equal number of columns in the second MatrixMap (" + ncolA + ").");
        }
        int minrow = Math.min(nrowA, nrowThis);
        expandMatrix();
        double[] thisMatrix = getInvert() ? iMatrix : fMatrix;
        double[] aMatrix = a.getInvert() ? a.iMatrix : a.fMatrix;
        double[] newMatrix = new double[nrowA * ncolThis];
        if (a.form == UNIT) {
            System.arraycopy(thisMatrix, 0, newMatrix, 0, minrow * ncolThis);
        } else if (a.form == DIAGONAL) {
            int i = 0;
            for (int row = 0; row < minrow; row++) {
                double factor = aMatrix[row];
                for (int col = 0; col < ncolThis; col++) {
                    double thisVal = thisMatrix[i];
                    newMatrix[i++] = factor != AST__BAD && thisVal != AST__BAD ? thisVal * factor : AST__BAD;
                }
            }
        } else {
            int k = 0;
            for (int row = 0; row < nrowA; row++) {
                int aRow = ncolA * row;
                for (int col = 0; col < ncolThis; col++) {
                    double sum = 0.0;
                    for (int i = 0; i < ncolA; i++) {
                        double aVal = aMatrix[aRow + i];
                        double thisVal = thisMatrix[col + i * ncolThis];
                        if (aVal != AST__BAD && thisVal != AST__BAD) {
                            sum += aVal * thisVal;
                        } else {
                            sum = AST__BAD;
                            break;
                        }
                    }
                    newMatrix[k++] = sum;
                }
            }
        }
        MatrixMap product = new MatrixMap(ncolThis, nrowA, FULL, newMatrix);
        product.compressMatrix();
        compressMatrix();
        return product;
    }

    /**
     * A copy of this MatrixMap rotated by {@code theta} radians, as C's {@code astMtrRot} makes
     * it. The matrix this MatrixMap applies forward is post-multiplied by the rotation matrix, and
     * the one it applies in reverse pre-multiplied by the inverse rotation. A MatrixMap with two
     * outputs is rotated clockwise about the origin, from the second axis towards the first, and
     * {@code axis} is not used; one with three outputs is rotated clockwise about {@code axis}, a
     * vector through the origin, looking along it. An angle whose cosine is one gives an unchanged
     * copy.
     *
     * @param theta the angle of rotation, in radians
     * @param axis the 3-vector of the axis of rotation, for a MatrixMap with three outputs
     * @throws AstException with status {@code AST__MTRAX} if {@code axis} is null, short or of zero
     *     length for a MatrixMap with three outputs, or {@code AST__MTR23} if the MatrixMap has
     *     neither two nor three outputs
     */
    MatrixMap mtrRot(double theta, double[] axis) {
        MatrixMap result = (MatrixMap) copy();
        double costh = Math.cos(theta);
        double sinth = Math.sin(theta);
        if (costh == 1.0) {
            return result;
        }
        int nrow = result.getNout();
        int ncol = result.getNin();
        boolean inv = result.getInvert();
        if (nrow == 2) {
            result.expandMatrix();
            double[] rotmat = {costh, sinth, -sinth, costh};
            smtrMult(true, 2, ncol, rotmat, inv ? result.iMatrix : result.fMatrix);
            rotmat[1] = -sinth;
            rotmat[2] = sinth;
            smtrMult(false, ncol, 2, rotmat, inv ? result.fMatrix : result.iMatrix);
            result.compressMatrix();
        } else if (nrow == 3) {
            double axlen2 =
                    axis != null && axis.length >= 3 ? axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2] : 0.0;
            if (axlen2 <= 0.0) {
                throw Ast.err(
                        AstException.AST__MTRAX,
                        "astMtrRot(" + astClass() + "): NULL or zero length axis vector supplied.");
            }
            double axlen = Math.sqrt(axlen2);
            result.expandMatrix();
            double as = sinth / axlen;
            double a = (1.0 - costh) / axlen2;
            double b = a * axis[0] * axis[1];
            double c = as * axis[2];
            double d = a * axis[0] * axis[2];
            double e = as * axis[1];
            double f = a * axis[1] * axis[2];
            double g = as * axis[0];
            double[] rotmat = {
                a * axis[0] * axis[0] + costh,
                b - c,
                d + e,
                b + c,
                a * axis[1] * axis[1] + costh,
                f - g,
                d - e,
                f + g,
                a * axis[2] * axis[2] + costh
            };
            smtrMult(true, 3, ncol, rotmat, inv ? result.iMatrix : result.fMatrix);
            rotmat[1] = b + c;
            rotmat[2] = d - e;
            rotmat[3] = b - c;
            rotmat[5] = f + g;
            rotmat[6] = d + e;
            rotmat[7] = f - g;
            smtrMult(false, ncol, 3, rotmat, inv ? result.fMatrix : result.iMatrix);
            result.compressMatrix();
        } else {
            throw Ast.err(
                    AstException.AST__MTR23,
                    "astMtrRot(" + astClass() + "): Cannot rotate a " + nrow + "x" + ncol + " MatrixMap.");
        }
        return result;
    }

    /**
     * Multiplies {@code mat2} in place by the square matrix {@code mat1}, as C's {@code SMtrMult}
     * (matrixmap.c) does: if {@code post}, {@code mat2} ({@code m} by {@code n}) becomes {@code
     * mat1 * mat2} with {@code mat1} {@code m} by {@code m}; otherwise {@code mat2} ({@code m} by
     * {@code n}) becomes {@code mat2 * mat1} with {@code mat1} {@code n} by {@code n}. A bad
     * element in a dot product makes it bad. Nothing is done if {@code mat2} is null.
     */
    private static void smtrMult(boolean post, int m, int n, double[] mat1, double[] mat2) {
        if (mat2 == null) {
            return;
        }
        if (post) {
            double[] work = new double[m];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < m; i++) {
                    double dot = 0.0;
                    for (int k = 0; k < m; k++) {
                        double rel = mat1[i * m + k];
                        double cel = mat2[j + k * n];
                        if (rel != AST__BAD && cel != AST__BAD) {
                            dot += rel * cel;
                        } else {
                            dot = AST__BAD;
                            break;
                        }
                    }
                    work[i] = dot;
                }
                for (int i = 0; i < m; i++) {
                    mat2[j + i * n] = work[i];
                }
            }
        } else {
            double[] work = new double[n];
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) {
                    double dot = 0.0;
                    for (int k = 0; k < n; k++) {
                        double rel = mat2[i * n + k];
                        double cel = mat1[j + k * n];
                        if (rel != AST__BAD && cel != AST__BAD) {
                            dot += rel * cel;
                        } else {
                            dot = AST__BAD;
                            break;
                        }
                    }
                    work[j] = dot;
                }
                System.arraycopy(work, 0, mat2, i * n, n);
            }
        }
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Applies the matrix, as C's {@code Transform} (matrixmap.c) does. For a full matrix, each
     * output is the sum, over the inputs in order, of element times value; a bad element makes its
     * output bad, and a bad input makes bad the outputs whose element for it is not zero, so that
     * a bad input does not reach an output that does not depend on it. For a unit or diagonal
     * matrix, each output is its input times the diagonal element, bad for a bad input or a bad
     * element, and any output beyond the number of inputs is zero.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        int ncoordIn = forward ? getNin() : getNout();
        int ncoordOut = forward ? getNout() : getNin();
        double[] matrix = fwd ? fMatrix : iMatrix;
        if (form == FULL) {
            for (int row = 0; row < ncoordOut; row++) {
                double[] o = out[row];
                int base = row * ncoordIn;
                boolean badRow = false;
                for (int k = 0; k < ncoordIn; k++) {
                    badRow |= isBad(matrix[base + k]);
                }
                if (badRow) {
                    Arrays.fill(o, 0, npoint, AST__BAD);
                    continue;
                }
                for (int p = 0; p < npoint; p++) {
                    double sum = 0.0;
                    for (int k = 0; k < ncoordIn; k++) {
                        double v = in[k][p];
                        double c = matrix[base + k];
                        if (!isBad(v)) {
                            sum += c * v;
                        } else if (c != 0.0) {
                            sum = AST__BAD;
                            break;
                        }
                    }
                    o[p] = sum;
                }
            }
            return;
        }
        int naxes = Math.min(ncoordIn, ncoordOut);
        for (int j = 0; j < naxes; j++) {
            double[] a = in[j];
            double[] o = out[j];
            if (form == UNIT) {
                for (int p = 0; p < npoint; p++) {
                    double v = a[p];
                    o[p] = isBad(v) ? AST__BAD : v;
                }
                continue;
            }
            double diag = matrix[j];
            if (isBad(diag)) {
                Arrays.fill(o, 0, npoint, AST__BAD);
                continue;
            }
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = isBad(v) ? AST__BAD : diag * v;
            }
        }
        for (int j = naxes; j < ncoordOut; j++) {
            Arrays.fill(out[j], 0, npoint, 0.0);
        }
    }

    /**
     * NaN protocol: the arithmetic of {@link #transformInto} without per-point checks. A NaN input
     * propagates through a non-zero element and is left out of a sum by a zero one, as a bad input
     * is; a bad element makes its output NaN. The checks of the base class are still made, so an
     * undefined direction is an error even with no points.
     */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        boolean fwd = effectiveForward(forward);
        int ncoordIn = forward ? getNin() : getNout();
        int ncoordOut = forward ? getNout() : getNin();
        double[] matrix = fwd ? fMatrix : iMatrix;
        if (form == FULL) {
            for (int row = 0; row < ncoordOut; row++) {
                double[] sum = out[row];
                int base = row * ncoordIn;
                boolean badRow = false;
                for (int k = 0; k < ncoordIn; k++) {
                    badRow |= matrix[base + k] == AST__BAD;
                }
                if (badRow) {
                    Arrays.fill(sum, 0, npoint, Double.NaN);
                    continue;
                }
                Arrays.fill(sum, 0, npoint, 0.0);
                for (int k = 0; k < ncoordIn; k++) {
                    double c = matrix[base + k];
                    if (c == 0.0) {
                        continue;
                    }
                    double[] a = in[k];
                    for (int p = 0; p < npoint; p++) {
                        sum[p] += c * a[p];
                    }
                }
            }
            return;
        }
        int naxes = Math.min(ncoordIn, ncoordOut);
        for (int j = 0; j < naxes; j++) {
            double[] a = in[j];
            double[] o = out[j];
            if (form == UNIT) {
                System.arraycopy(a, 0, o, 0, npoint);
                continue;
            }
            double diag = matrix[j];
            if (diag == AST__BAD) {
                Arrays.fill(o, 0, npoint, Double.NaN);
                continue;
            }
            for (int p = 0; p < npoint; p++) {
                o[p] = diag * a[p];
            }
        }
        for (int j = naxes; j < ncoordOut; j++) {
            Arrays.fill(out[j], 0, npoint, 0.0);
        }
    }
}
