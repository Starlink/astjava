package uk.ac.starlink.ast;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The base class of all Mappings: relationships between two coordinate systems.
 *
 * <p>A Mapping transforms a set of coordinates representing "input" points into a new set
 * representing "output" points. It describes how two coordinate systems are related and drives the
 * operations that use that relationship, such as transforming coordinates. The Mapping class has
 * no constructor of its own: each subclass, such as {@link ZoomMap}, implements a particular kind
 * of transformation.
 *
 * <p>The attributes of a Mapping, besides those of every {@link AstObject}, are:
 *
 * <ul>
 *   <li>{@code Invert}: whether the Mapping is inverted (see {@link #getInvert}).
 *   <li>{@code IsLinear} (read-only): whether the Mapping is linear.
 *   <li>{@code IsSimple} (read-only): whether the Mapping has been simplified.
 *   <li>{@code Nin} (read-only) and {@code Nout} (read-only): the numbers of input and output
 *       coordinates.
 *   <li>{@code Report}: whether transformed coordinates are reported (see {@link #getReport}).
 *   <li>{@code TranForward} (read-only) and {@code TranInverse} (read-only): whether the forward
 *       and inverse transformations are defined.
 * </ul>
 *
 * <p>Coordinates are held as {@code double[ncoord][npoint]}: element {@code [coord][point]} is
 * coordinate {@code coord} of point {@code point}. A coordinate that is missing or undefined has
 * the value {@link #AST__BAD}; a NaN or infinite input coordinate is taken to be bad too, and no
 * transformation gives a NaN output.
 *
 * <p>Inverting a Mapping exchanges its forward and inverse transformations. As in C, the
 * inversion is a property of each Mapping object: its {@code Invert} attribute, held here, which
 * {@link #invert} toggles. Each class's transformation reads it, through {@link
 * #effectiveForward}, every time it transforms, so that the class's own data always describe the
 * Mapping as it was created.
 *
 * <p>Resampling and rebinning ({@code resample*}, {@code rebin*}) are not available in this
 * version, and throw {@link UnsupportedOperationException}.
 *
 * <p>Ported from Rust {@code mappings/mod.rs} and C {@code mapping.c}.
 */
public abstract class Mapping extends AstObject {

    /** An Interpolator for nearest-neighbour resampling. */
    public static final Interpolator NEAREST_INTERPOLATOR = Interpolator.nearest();

    /** An Interpolator for linear resampling. */
    public static final Interpolator LINEAR_INTERPOLATOR = Interpolator.linear();

    /** A Spreader for nearest-neighbour rebinning. */
    public static final Spreader NEAREST_SPREADER = Spreader.nearest();

    /** A Spreader for linear rebinning. */
    public static final Spreader LINEAR_SPREADER = Spreader.linear();

    /** C's resampling scheme identifiers (mapping.h). */
    static final int AST__UKERN1 = 1;

    static final int AST__UINTERP = 3;
    static final int AST__NEAREST = 4;
    static final int AST__LINEAR = 5;
    static final int AST__SINC = 6;
    static final int AST__SINCSINC = 7;
    static final int AST__SINCCOS = 8;
    static final int AST__SINCGAUSS = 9;
    static final int AST__BLOCKAVE = 10;
    static final int AST__GAUSS = 11;
    static final int AST__SOMB = 12;
    static final int AST__SOMBCOS = 13;

    /** The number of input coordinates of the Mapping as created (C's {@code nin}). */
    private int nin;

    /** The number of output coordinates of the Mapping as created (C's {@code nout}). */
    private int nout;

    /** Whether the forward transformation of the Mapping as created is defined. */
    private boolean tranForward = true;

    /** Whether the inverse transformation of the Mapping as created is defined. */
    private boolean tranInverse = true;

    /** The Invert attribute: 0, 1, or {@link #UNSET_FLAG}. */
    private int invertValue = UNSET_FLAG;

    /** The Report attribute: 0, 1, or {@link #UNSET_FLAG}. */
    private int reportValue = UNSET_FLAG;

    /** Whether the Mapping has been simplified when not inverted (C's AST__ISSIMPLE_FLAG). */
    private boolean simpleForward;

    /** Whether the Mapping has been simplified when inverted (C's AST__ISSIMPLEINV_FLAG). */
    private boolean simpleInverse;

    /** C's AST__RESTRICTED_SIMPLIFY_FLAG: simplification may nominate only allowed Mappings. */
    private boolean restrictedSimplify;

    /** C's AST__ALLOW_SIMPLIFY_FLAG: a restricted simplification may nominate this Mapping. */
    private boolean allowSimplify;

    /**
     * Constructs a Mapping with no inputs or outputs, defined in both directions. Each subclass
     * that describes its own inputs and outputs overrides {@link #getNin} and {@link #getNout}.
     */
    protected Mapping() {}

    /**
     * Constructs a Mapping, as C's {@code astInitMapping} does.
     *
     * @param nin the number of input coordinates
     * @param nout the number of output coordinates
     * @param tranForward whether the forward transformation is defined
     * @param tranInverse whether the inverse transformation is defined
     * @throws AstException with status {@code AST__BADNI} or {@code AST__BADNO} if a count is
     *     negative
     */
    Mapping(int nin, int nout, boolean tranForward, boolean tranInverse) {
        if (nin < 0) {
            throw Ast.err(
                    AstException.AST__BADNI,
                    "astInitMapping(" + astClass() + "): Bad number of input coordinates (" + nin
                            + ").\nThis number should be zero or more.");
        } else if (nout < 0) {
            throw Ast.err(
                    AstException.AST__BADNO,
                    "astInitMapping(" + astClass() + "): Bad number of output coordinates (" + nout
                            + ").\nThis number should be zero or more.");
        }
        this.nin = nin;
        this.nout = nout;
        this.tranForward = tranForward;
        this.tranInverse = tranInverse;
    }

    /**
     * Constructs a Mapping from its description, reading the Mapping class's part as C's {@code
     * astLoadMapping} does: Nin, Nout, Invert, IsSimp, ReSimp, AlSimp, Fwd, Inv and Report. The
     * IsSimp item describes the Mapping in the orientation it was written in, which is the one
     * its Invert item sets up.
     *
     * @param node the description
     */
    Mapping(NativeNode node) {
        super(node);
        NativeNode part = node.section("Mapping");
        nin = Math.max(0, part.readInt("Nin", 0));
        nout = Math.max(0, part.readInt("Nout", nin));
        Long inv = part.getIntIfSet("Invert");
        invertValue = inv == null ? UNSET_FLAG : (inv != 0 ? 1 : 0);
        if (part.readInt("IsSimp", 0) != 0) {
            setIsSimple();
        }
        restrictedSimplify = part.readInt("ReSimp", 0) != 0;
        allowSimplify = part.readInt("AlSimp", 0) != 0;
        tranForward = part.readInt("Fwd", 1) != 0;
        tranInverse = part.readInt("Inv", 1) != 0;
        Long rep = part.getIntIfSet("Report");
        reportValue = rep == null ? UNSET_FLAG : (rep != 0 ? 1 : 0);
    }

    /**
     * Writes the Mapping class's part of a description, as C's {@code Dump} (mapping.c) does.
     * Nin, Nout, Fwd and Inv are those of the Mapping as created, before any inversion; an item
     * that holds its default is written as a comment with the value the Mapping reports.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Object", "AST Object");
        boolean inv = getInvert();
        boolean set = nin != 0;
        int ival = set ? nin : (!inv ? getNin() : getNout());
        node.writeInt("Nin", set, false, ival, "Number of input coordinates");
        set = nout != nin;
        ival = set ? nout : (!inv ? getNout() : getNin());
        node.writeInt("Nout", set, false, ival, "Number of output coordinates");
        boolean flag = getIsSimple();
        node.writeInt(
                "IsSimp",
                flag,
                false,
                flag ? 1 : 0,
                flag ? "Mapping has been simplified" : "Mapping has not been simplified");
        flag = restrictedSimplify;
        node.writeInt(
                "ReSimp",
                flag,
                false,
                flag ? 1 : 0,
                flag ? "Simplify restricted to allowed Mappings" : "Simplify uses all Mappings");
        flag = allowSimplify;
        node.writeInt(
                "AlSimp",
                flag,
                false,
                flag ? 1 : 0,
                flag ? "Will be used by restricted Simplify" : "Will not be used by restricted Simplify");
        flag = inv;
        node.writeInt("Invert", flag, false, flag ? 1 : 0, flag ? "Mapping inverted" : "Mapping not inverted");
        set = !tranForward;
        flag = set ? tranForward : (!inv ? getTranForward() : getTranInverse());
        node.writeInt(
                "Fwd",
                set,
                false,
                flag ? 1 : 0,
                flag ? "Forward transformation defined" : "Forward transformation not defined");
        set = !tranInverse;
        flag = set ? tranInverse : (!inv ? getTranInverse() : getTranForward());
        node.writeInt(
                "Inv",
                set,
                false,
                flag ? 1 : 0,
                flag ? "Inverse transformation defined" : "Inverse transformation not defined");
        flag = getReport();
        node.writeInt(
                "Report",
                reportValue != UNSET_FLAG,
                false,
                flag ? 1 : 0,
                flag ? "Report coordinate transformations" : "Don't report coordinate transformations");
    }

    /** Completes a copy as C's {@code Copy} (mapping.c) does: the copy's Report is cleared. */
    @Override
    void deepCopy() {
        super.deepCopy();
        reportValue = UNSET_FLAG;
    }

    // ------------------------------------------------------------------
    // Counts, inversion and definedness
    // ------------------------------------------------------------------

    /**
     * Gets the Nin attribute: the number of coordinate values needed to specify an input point,
     * the number of dimensions of the space the Mapping's input points lie in. Inverting the
     * Mapping exchanges it with Nout.
     *
     * @return the number of input coordinates
     */
    public int getNin() {
        return getInvert() ? nout : nin;
    }

    /**
     * Gets the Nout attribute: the number of coordinate values generated for each output point,
     * the number of dimensions of the space the Mapping's output points lie in. Inverting the
     * Mapping exchanges it with Nin.
     *
     * @return the number of output coordinates
     */
    public int getNout() {
        return getInvert() ? nin : nout;
    }

    /**
     * Gets the TranForward attribute: whether the Mapping defines a forward transformation, from
     * input coordinates to output coordinates. Transforming in an undefined direction is an
     * {@code AST__TRNND} error. Inverting the Mapping exchanges it with TranInverse.
     *
     * @return true if the forward transformation is defined
     */
    public boolean getTranForward() {
        return getInvert() ? tranInverse : tranForward;
    }

    /**
     * Gets the TranInverse attribute: whether the Mapping defines an inverse transformation, from
     * output coordinates back to input coordinates. Inverting the Mapping exchanges it with
     * TranForward.
     *
     * @return true if the inverse transformation is defined
     */
    public boolean getTranInverse() {
        return getInvert() ? tranForward : tranInverse;
    }

    /**
     * Gets the Invert attribute: whether the Mapping is inverted. When false (the default), the
     * Mapping transforms coordinates in the way it was created to; when true, its input and output
     * coordinates are exchanged, so that it behaves as the inverse of the original: Nin and Nout
     * are exchanged, as are TranForward and TranInverse. Inverting a UnitMap has no effect on how
     * it behaves.
     *
     * @return true if the Mapping is inverted
     */
    public boolean getInvert() {
        return invertValue != UNSET_FLAG && invertValue != 0;
    }

    /**
     * Sets the Invert attribute.
     *
     * @param invert true to invert the Mapping
     */
    public void setInvert(boolean invert) {
        this.invertValue = invert ? 1 : 0;
    }

    /**
     * The number of input coordinates of the Mapping as created, which inverting it does not
     * change.
     */
    final int createdNin() {
        return nin;
    }

    /**
     * The number of output coordinates of the Mapping as created, which inverting it does not
     * change.
     */
    final int createdNout() {
        return nout;
    }

    /** Whether the Invert attribute has been set, even to false. */
    boolean testInvert() {
        return invertValue != UNSET_FLAG;
    }

    /** Clears the Invert attribute, returning the Mapping to its original orientation. */
    void clearInvert() {
        invertValue = UNSET_FLAG;
    }

    /**
     * Inverts the Mapping by reversing the sense of its Invert attribute, as C's {@code astInvert}
     * does: the attribute is cleared, and set again only if its default is not the value wanted.
     */
    public void invert() {
        boolean inv = !getInvert();
        clearInvert();
        if (getInvert() != inv) {
            setInvert(inv);
        }
    }

    /**
     * Whether a transformation in direction {@code forward}, as the caller asks for it, uses the
     * transformation the Mapping was created with as its forward one: true when {@code forward}
     * differs from the Invert attribute.
     */
    final boolean effectiveForward(boolean forward) {
        return forward != getInvert();
    }

    /**
     * Whether the Mapping is linear: true for a class whose transformation is always linear (the
     * IsLinear attribute). A class that can represent both linear and non-linear transformations
     * reports false.
     */
    boolean isLinear() {
        return false;
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    /**
     * Gets the Report attribute: whether {@link #tran1}, {@link #tran2}, {@link #tranN} and {@link
     * #tranP} report the coordinates of each point before and after transformation, one line per
     * point, on standard output. This is an aid to debugging. Only the Report attribute of the
     * Mapping transformed is used, not that of any Mapping it holds. A copy does not inherit it.
     *
     * @return true if transformations are reported
     */
    public boolean getReport() {
        return reportValue != UNSET_FLAG && reportValue != 0;
    }

    /**
     * Sets the Report attribute.
     *
     * @param report true to report transformations
     */
    public void setReport(boolean report) {
        this.reportValue = report ? 1 : 0;
    }

    /** Whether the Report attribute has been set. */
    boolean testReport() {
        return reportValue != UNSET_FLAG;
    }

    /** Clears the Report attribute. */
    void clearReport() {
        reportValue = UNSET_FLAG;
    }

    /**
     * Writes the effect of a transformation in direction {@code forward}, the first {@code npoint}
     * points of {@code in} mapped to those of {@code out}, to the thread's report stream, as C's
     * per-class {@code ReportPoints} does. A Mapping writes each value to 17 significant digits,
     * or as {@code <bad>}.
     */
    void reportPoints(boolean forward, double[][] in, double[][] out, int npoint) {
        MappingReport.write(MappingReport.lines(in, out, npoint, MappingReport::plainValue, MappingReport::plainValue));
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /**
     * Whether the Mapping has been simplified in its current orientation (the IsSimple attribute),
     * so that simplifying it again would gain nothing. Each orientation has its own record, chosen
     * by the Invert attribute; inverting the Mapping does not discard either.
     */
    boolean getIsSimple() {
        return invertValue == 1 ? simpleInverse : simpleForward;
    }

    /** Records that the Mapping has been simplified in its current orientation (C's {@code astSetIsSimple}). */
    final void setIsSimple() {
        if (invertValue == 1) {
            simpleInverse = true;
        } else {
            simpleForward = true;
        }
    }

    /**
     * Discards the record of simplification in both orientations (C's {@code astClearIsSimple}),
     * which a class does when something changes that feeds its own simplification.
     */
    final void clearIsSimple() {
        simpleForward = false;
        simpleInverse = false;
    }

    /** Whether simplification of this Mapping is restricted to Mappings that allow it. */
    final boolean getRestrictedSimplify() {
        return restrictedSimplify;
    }

    /** Sets whether simplification of this Mapping is restricted. */
    final void setRestrictedSimplify(boolean flag) {
        restrictedSimplify = flag;
    }

    /** Whether a restricted simplification may nominate this Mapping. */
    final boolean getAllowSimplify() {
        return allowSimplify;
    }

    /** Sets whether a restricted simplification may nominate this Mapping. */
    final void setAllowSimplify(boolean flag) {
        allowSimplify = flag;
    }

    /**
     * Whether this Mapping must be left intact by simplification (C's {@code astDoNotSimplify}):
     * true when its Ident attribute is set, so that a Mapping a user has named keeps its identity.
     */
    boolean doNotSimplify() {
        return testAttrib("ident");
    }

    /**
     * The per-class step of simplification (C's {@code Simplify} method), called by {@link
     * #simplify} only. In this version no Mapping is restructured, so the result is this Mapping,
     * as C's returns when nothing can be simplified.
     */
    Mapping simplifyStep() {
        return this;
    }

    /**
     * Simplifies a Mapping, eliminating redundant computational steps or merging separate steps
     * that can be performed more efficiently in a single operation, as C's {@code astSimplify}
     * does. The result transforms coordinates as this Mapping does.
     *
     * <p>In this version no Mapping is restructured: the result is this Mapping, which is recorded
     * as simplified, as C records a Mapping it cannot simplify. The Mapping returned may therefore
     * not be independent of the original; use {@link #copy} if an independent one is needed.
     *
     * @return the simplified Mapping
     */
    public Mapping simplify() {
        if (getIsSimple() || doNotSimplify()) {
            return this;
        }
        Mapping result;
        try {
            result = simplifyStep();
        } catch (AstException e) {
            // C clears these two errors, which come from a simplification that tried to use an
            // undefined transformation, and returns the Mapping unchanged (mapping.c astSimplify_).
            if (e.getStatus() == AstException.AST__NODEF || e.getStatus() == AstException.AST__TRNND) {
                return this;
            }
            throw e;
        }
        if (restrictedSimplify) {
            result.restrictedSimplify = false;
        } else {
            result.setIsSimple();
        }
        result.allowSimplify = false;
        return result;
    }

    // ------------------------------------------------------------------
    // Transformation
    // ------------------------------------------------------------------

    /**
     * Whether a coordinate value is bad: {@link #AST__BAD} or any value that is not finite.
     *
     * @param v the value
     */
    static boolean isBad(double v) {
        return v == AST__BAD || !Double.isFinite(v);
    }

    /**
     * Transforms the first {@code npoint} points of {@code in} into {@code out}, both {@code
     * [ncoord][npoint or more]}, applying the forward transformation if {@code forward} is true
     * and the inverse otherwise. {@code forward} is the direction the caller asks for; each class
     * applies {@link #effectiveForward} to it. Only the first {@code npoint} points of each output
     * coordinate the transformation produces are written. Each class checks its arguments first,
     * with {@link #validateTransformInto}.
     */
    abstract void transformInto(double[][] in, int npoint, boolean forward, double[][] out);

    /**
     * {@link #transformInto} under the NaN protocol: bad input values arrive as NaN, and bad output
     * values may be left as NaN. The caller has converted {@link #AST__BAD} inputs to NaN once at
     * the start of a chain of transformations ({@link #transformNanProtocol}), so a class whose
     * arithmetic propagates NaN may skip its per-point checks. This default runs {@link
     * #transformInto}, which treats a NaN input as bad, and turns the {@link #AST__BAD} values it
     * writes into NaN, so that a later stage that does not check cannot take them for numbers.
     */
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        transformInto(in, npoint, forward, out);
        int ncoordOut = forward ? getNout() : getNin();
        for (int c = 0; c < ncoordOut; c++) {
            double[] axis = out[c];
            for (int p = 0; p < npoint; p++) {
                if (axis[p] == AST__BAD) {
                    axis[p] = Double.NaN;
                }
            }
        }
    }

    /**
     * Transforms {@code npoint} points under the NaN protocol: bad input values (and any that are
     * not finite) are turned into NaN once, the transformation runs through {@link
     * #transformIntoNan}, and NaN and infinite outputs are turned into {@link #AST__BAD} once at
     * the end. The result is that of {@link #transform}.
     *
     * @return the transformed points, {@code [ncoord][npoint]}
     */
    final double[][] transformNanProtocol(double[][] in, int npoint, boolean forward) {
        validateTransformInto(in, npoint, forward, null);
        double[][] nan = new double[in.length][npoint];
        for (int c = 0; c < in.length; c++) {
            for (int p = 0; p < npoint; p++) {
                double v = in[c][p];
                nan[c][p] = isBad(v) ? Double.NaN : v;
            }
        }
        double[][] out = new double[forward ? getNout() : getNin()][npoint];
        transformIntoNan(nan, npoint, forward, out);
        for (double[] axis : out) {
            for (int p = 0; p < npoint; p++) {
                if (!Double.isFinite(axis[p])) {
                    axis[p] = AST__BAD;
                }
            }
        }
        return out;
    }

    /**
     * Transforms {@code npoint} points, applying the forward transformation if {@code forward} is
     * true and the inverse otherwise. Each of the transformation's input coordinates is a row of
     * {@code in}.
     *
     * @return the transformed points, {@code [ncoord][npoint]}, with one row per output coordinate
     *     of the transformation
     */
    final double[][] transform(double[][] in, int npoint, boolean forward) {
        validateTransformInto(in, npoint, forward, null);
        double[][] out = new double[forward ? getNout() : getNin()][npoint];
        transformInto(in, npoint, forward, out);
        return out;
    }

    /**
     * The checks C's base-class {@code Transform} (mapping.c) makes before any class transforms a
     * point: the transformation must be defined ({@code AST__TRNND}), the input must have one row
     * per input coordinate ({@code AST__NCPIN}) of at least {@code npoint} values ({@code
     * AST__DIMIN}), and an output, if given, must hold every point ({@code AST__NOPTS}) of every
     * output coordinate ({@code AST__NOCTS}). A negative {@code npoint} is {@code AST__NPTIN}.
     *
     * @param out the output array, or null if it is yet to be made
     */
    final void validateTransformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        String cls = astClass();
        if (!(forward ? getTranForward() : getTranInverse())) {
            throw Ast.err(
                    AstException.AST__TRNND,
                    "astTransform(" + cls + "): " + (forward ? "A forward" : "An inverse")
                            + " coordinate transformation is not defined by the " + cls + " supplied.");
        }
        int ncoordIn = forward ? getNin() : getNout();
        int ncoordOut = forward ? getNout() : getNin();
        if (in.length != ncoordIn) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astTransform(" + cls + "): Bad number of coordinate values (" + in.length
                            + ") in input.\nThe " + cls + " given requires " + ncoordIn
                            + " coordinate value(s) for each input point.");
        }
        if (npoint < 0) {
            throw Ast.err(
                    AstException.AST__NPTIN,
                    "astTransform(" + cls + "): Number of points to be transformed (" + npoint + ") is invalid.");
        }
        for (double[] axis : in) {
            if (axis.length < npoint) {
                throw Ast.err(
                        AstException.AST__DIMIN,
                        "astTransform(" + cls + "): An input coordinate array holds " + axis.length
                                + " values.\nThis should not be less than the number of points being transformed ("
                                + npoint + ").");
            }
        }
        if (out == null) {
            return;
        }
        if (out.length < ncoordOut) {
            throw Ast.err(
                    AstException.AST__NOCTS,
                    "astTransform(" + cls + "): Too few coordinate values per point (" + out.length
                            + ") in output.\nThe " + cls + " supplied needs space to store " + ncoordOut
                            + " coordinate value(s) per transformed point.");
        }
        for (int c = 0; c < ncoordOut; c++) {
            if (out[c].length < npoint) {
                throw Ast.err(
                        AstException.AST__NOPTS,
                        "astTransform(" + cls + "): Too few points (" + out[c].length + ") in output.\nThe " + cls
                                + " needs space to hold " + npoint + " transformed point(s).");
            }
        }
    }

    /**
     * The checks C's {@code ValidateMapping} (mapping.c) makes for {@code astTran1}, {@code
     * astTran2}, {@code astTranN} and {@code astTranP}: the transformation must be defined ({@code
     * AST__TRNND}), and must take {@code ncoordIn} and give {@code ncoordOut} coordinates ({@code
     * AST__NCPIN}). At least one point must be given ({@code AST__NPTIN}), as C's PointSet requires
     * (pointset.c {@code astInitPointSet}).
     */
    final void validateMapping(boolean forward, int npoint, int ncoordIn, int ncoordOut, String method) {
        String cls = astClass();
        if (!(forward ? getTranForward() : getTranInverse())) {
            throw Ast.err(
                    AstException.AST__TRNND,
                    method + "(" + cls + "): " + (forward ? "A forward" : "An inverse")
                            + " coordinate transformation is not defined by the " + cls + " supplied.");
        }
        int mapIn = forward ? getNin() : getNout();
        int mapOut = forward ? getNout() : getNin();
        if (ncoordIn != mapIn) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    method + "(" + cls + "): Bad number of input coordinate values (" + ncoordIn + ").\nThe "
                            + cls + " given requires " + mapIn + " coordinate value" + (mapIn == 1 ? "" : "s")
                            + " for each input point.");
        }
        if (ncoordOut != mapOut) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    method + "(" + cls + "): Bad number of output coordinate values (" + ncoordOut + ").\nThe "
                            + cls + " given generates " + (mapOut < ncoordOut ? "only " : "") + mapOut
                            + " coordinate value" + (mapOut == 1 ? "" : "s") + " for each output point.");
        }
        if (npoint < 1) {
            throw Ast.err(
                    AstException.AST__NPTIN,
                    method + "(" + cls + "): Number of points to be transformed (" + npoint + ") is invalid.");
        }
    }

    /** An {@code AST__DIMIN} error if {@code length}, the size of an array, is below {@code need}. */
    final void checkDim(String method, String what, int length, long need) {
        if (length < need) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    method + "(" + astClass() + "): The " + what + " array holds " + length
                            + " values.\nThis should not be less than " + need + ".");
        }
    }

    /** Transforms as the public transform methods do, reporting the points if Report is set. */
    private double[][] tran(double[][] in, int npoint, boolean forward) {
        double[][] out = transform(in, npoint, forward);
        if (getReport()) {
            reportPoints(forward, in, out, npoint);
        }
        return out;
    }

    /**
     * Transforms a set of points in one dimension, applying the forward transformation if {@code
     * forward} is true and the inverse otherwise. The Mapping must have one input and one output
     * coordinate. A bad (or NaN) input value gives a bad output value.
     *
     * @param npoint the number of points to transform
     * @param xin the coordinates of the points, of which the first {@code npoint} are transformed
     * @param forward true for the forward transformation, false for the inverse
     * @return the {@code npoint} transformed coordinates
     * @throws AstException with status {@code AST__TRNND} if the transformation is not defined,
     *     {@code AST__NCPIN} if the Mapping does not have one input and one output, {@code
     *     AST__NPTIN} if {@code npoint} is less than 1, or {@code AST__DIMIN} if {@code xin} holds
     *     fewer than {@code npoint} values
     */
    public double[] tran1(int npoint, double[] xin, boolean forward) {
        validateMapping(forward, npoint, 1, 1, "astTran1");
        checkDim("astTran1", "xin", xin.length, npoint);
        return tran(new double[][] {xin}, npoint, forward)[0];
    }

    /**
     * Transforms a set of points in two dimensions, applying the forward transformation if {@code
     * forward} is true and the inverse otherwise. The Mapping must have two input and two output
     * coordinates. Point {@code i} is ({@code xin[i]}, {@code yin[i]}).
     *
     * @param npoint the number of points to transform
     * @param xin the first coordinate of each point
     * @param yin the second coordinate of each point
     * @param forward true for the forward transformation, false for the inverse
     * @return a two-element array: the {@code npoint} transformed first coordinates, then the
     *     second
     * @throws AstException with status {@code AST__TRNND} if the transformation is not defined,
     *     {@code AST__NCPIN} if the Mapping does not have two inputs and two outputs, {@code
     *     AST__NPTIN} if {@code npoint} is less than 1, or {@code AST__DIMIN} if an array holds
     *     fewer than {@code npoint} values
     */
    public double[][] tran2(int npoint, double[] xin, double[] yin, boolean forward) {
        validateMapping(forward, npoint, 2, 2, "astTran2");
        checkDim("astTran2", "xin", xin.length, npoint);
        checkDim("astTran2", "yin", yin.length, npoint);
        return tran(new double[][] {xin, yin}, npoint, forward);
    }

    /**
     * Transforms a set of points in any number of dimensions, held in one array, applying the
     * forward transformation if {@code forward} is true and the inverse otherwise. Coordinate
     * <i>c</i> of point <i>p</i> is element <code>in[npoint * <i>c</i> + <i>p</i>]</code>: all the
     * values of the first coordinate come first, then all those of the second, and so on. The
     * result has the same layout.
     *
     * @param npoint the number of points to transform
     * @param ncoordIn the number of coordinates of each input point: the Mapping's Nin for the
     *     forward transformation, its Nout for the inverse
     * @param in the input coordinates, at least {@code ncoordIn * npoint} values
     * @param forward true for the forward transformation, false for the inverse
     * @param ncoordOut the number of coordinates of each output point: the Mapping's Nout for the
     *     forward transformation, its Nin for the inverse
     * @return the {@code ncoordOut * npoint} transformed coordinates
     * @throws AstException with status {@code AST__TRNND} if the transformation is not defined,
     *     {@code AST__NCPIN} if the coordinate counts are not the Mapping's, {@code AST__NPTIN} if
     *     {@code npoint} is less than 1, or {@code AST__DIMIN} if {@code in} is too short
     */
    public double[] tranN(int npoint, int ncoordIn, double[] in, boolean forward, int ncoordOut) {
        validateMapping(forward, npoint, ncoordIn, ncoordOut, "astTranN");
        checkDim("astTranN", "input", in.length, (long) ncoordIn * npoint);
        double[][] rows = new double[ncoordIn][];
        for (int c = 0; c < ncoordIn; c++) {
            rows[c] = Arrays.copyOfRange(in, c * npoint, (c + 1) * npoint);
        }
        double[][] out = tran(rows, npoint, forward);
        double[] flat = new double[ncoordOut * npoint];
        for (int c = 0; c < ncoordOut; c++) {
            System.arraycopy(out[c], 0, flat, c * npoint, npoint);
        }
        return flat;
    }

    /**
     * Transforms a set of points in any number of dimensions, held in one array per coordinate,
     * applying the forward transformation if {@code forward} is true and the inverse otherwise.
     * Coordinate <i>c</i> of point <i>p</i> is element <code>in[<i>c</i>][<i>p</i>]</code>, and the
     * result has the same layout.
     *
     * @param npoint the number of points to transform
     * @param ncoordIn the number of coordinates of each input point: the Mapping's Nin for the
     *     forward transformation, its Nout for the inverse
     * @param in the input coordinates, {@code ncoordIn} arrays of at least {@code npoint} values
     * @param forward true for the forward transformation, false for the inverse
     * @param ncoordOut the number of coordinates of each output point: the Mapping's Nout for the
     *     forward transformation, its Nin for the inverse
     * @return {@code ncoordOut} arrays of {@code npoint} transformed coordinates
     * @throws AstException with status {@code AST__TRNND} if the transformation is not defined,
     *     {@code AST__NCPIN} if the coordinate counts are not the Mapping's or {@code in} has too
     *     few arrays, {@code AST__NPTIN} if {@code npoint} is less than 1, or {@code AST__DIMIN} if
     *     an array of {@code in} is too short
     */
    public double[][] tranP(int npoint, int ncoordIn, double[][] in, boolean forward, int ncoordOut) {
        validateMapping(forward, npoint, ncoordIn, ncoordOut, "astTranP");
        if (in.length < ncoordIn) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astTranP(" + astClass() + "): Bad number of input coordinate arrays (" + in.length + ").\nThe "
                            + astClass() + " given requires " + ncoordIn + ".");
        }
        double[][] rows = Arrays.copyOf(in, ncoordIn);
        for (int c = 0; c < ncoordIn; c++) {
            checkDim("astTranP", "input coordinate", rows[c].length, npoint);
        }
        return tran(rows, npoint, forward);
    }

    // ------------------------------------------------------------------
    // Rates, fits, grids, bounds, splitting and decomposition
    // ------------------------------------------------------------------

    /**
     * Finds the rate of change of one output of the Mapping with respect to one input, at a given
     * input position: the derivative of output {@code ax1} with respect to input {@code ax2} at
     * {@code at}. Classes whose rates are known exactly give them; otherwise the rate is estimated
     * by evaluating the Mapping at points spaced over intervals of several sizes about {@code at}
     * and choosing the interval over which the gradient is most consistent. This is accurate but
     * may evaluate the Mapping 100 or more times.
     *
     * @param at the input position, one value per input; only the first {@code Nin} are used
     * @param ax1 the output whose rate of change is wanted, numbered from 1
     * @param ax2 the input that is varied, numbered from 1
     * @return the rate of change, or {@link #AST__BAD} if it cannot be found, for instance because
     *     {@code at} holds a bad value
     * @throws AstException with status {@code AST__AXIIN} if {@code ax1} is not an output or {@code
     *     ax2} not an input, {@code AST__NCPIN} if {@code at} holds fewer than {@code Nin} values, or
     *     {@code AST__NODEF} if the rate must be estimated and the forward transformation is not
     *     defined
     */
    public double rate(double[] at, int ax1, int ax2) {
        String cls = astClass();
        int nout = getNout();
        int nin = getNin();
        // The checks of astRate_ (mapping.c), given the indices astRateId_ makes zero-based.
        if (ax1 < 1 || ax1 > nout) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    "astRate(" + cls + "): Invalid output index (" + ax1 + ") specified - should be in the range 1 to "
                            + nout + ".");
        }
        if (ax2 < 1 || ax2 > nin) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    "astRate(" + cls + "): Invalid input index (" + ax2 + ") specified - should be in the range 1 to "
                            + nin + ".");
        }
        if (at.length < nin) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astRate(" + cls + "): " + at.length + " input values supplied; the " + cls + " has " + nin
                            + " inputs.");
        }
        return rateAt(at, ax1 - 1, ax2 - 1);
    }

    /**
     * The rate of change of output {@code ax1} with respect to input {@code ax2} at {@code at},
     * both indices zero-based and already checked: C's per-class {@code Rate} method. This base
     * version estimates it numerically ({@link Rate#numericRate}); a class whose rates are known
     * exactly overrides it, as its C class does.
     */
    double rateAt(double[] at, int ax1, int ax2) {
        return Rate.numericRate(this, at, ax1, ax2);
    }

    /**
     * Finds a linear approximation to the forward transformation over a box of input coordinates,
     * if the transformation is linear enough there: the fit must reproduce the Mapping, at a set
     * of test points spread over the box, to within {@code tol}, a Cartesian distance in the
     * output space. To fit the inverse transformation, invert the Mapping first.
     *
     * <p>The fit is returned as {@code Nout} constant terms followed by the {@code Nout} by {@code
     * Nin} matrix of gradients, row by row: for two inputs and three outputs, output {@code X =
     * fit[0] + fit[3]*x + fit[4]*y}, {@code Y = fit[1] + fit[5]*x + fit[6]*y} and {@code Z = fit[2]
     * + fit[7]*x + fit[8]*y}. An output that is bad at any of the points tested has its
     * coefficients set to {@link #AST__BAD}, without stopping the other outputs being fitted, so
     * check the coefficients before using them.
     *
     * @param lbnd the lower bound of the box on each input, {@code Nin} values
     * @param ubnd the upper bound of the box on each input, {@code Nin} values
     * @param tol the largest acceptable deviation of the fit from the Mapping
     * @return the {@code (Nin + 1) * Nout} coefficients of the fit, or null if the Mapping is not
     *     linear enough over the box, or is bad on every output
     * @throws AstException with status {@code AST__NCPIN} if a bound holds fewer than {@code Nin}
     *     values, or any error transforming the test points, such as {@code AST__TRNND} if the
     *     forward transformation is not defined
     */
    public double[] linearApprox(double[] lbnd, double[] ubnd, double tol) {
        return LinearApprox.linearApprox(this, lbnd, ubnd, tol);
    }

    /**
     * Transforms every position of a regular grid, applying the forward transformation if {@code
     * forward} is true and the inverse otherwise. The grid holds the integer positions from {@code
     * lbnd} to {@code ubnd} inclusive on each axis, each the centre of a pixel of unit extent.
     *
     * <p>When {@code tol} is positive, the Mapping is replaced by linear fits over sections of the
     * grid wherever they are accurate to within {@code tol}, a distance in the output space, which
     * is much faster for large grids. A fit is first tried over the whole grid; where it is not
     * accurate enough, the section is divided in two along its longest axis and the fit tried
     * again, until a fit is found or the section is too small to be worth fitting, when the
     * Mapping is used directly. Before any fit is tried, the grid is divided until no section is
     * longer than {@code maxpix} positions on any axis. A {@code tol} of zero transforms every
     * position through the Mapping.
     *
     * @param ncoordIn the number of coordinates of each input position: the Mapping's Nin for the
     *     forward transformation, its Nout for the inverse
     * @param lbnd the lowest grid index on each axis, {@code ncoordIn} values
     * @param ubnd the highest grid index on each axis, {@code ncoordIn} values
     * @param tol the largest acceptable error of the linear fits, or zero for none
     * @param maxpix the largest number of positions along any axis over which a fit is tried
     * @param forward true for the forward transformation, false for the inverse
     * @param ncoordOut the number of coordinates of each output position: the Mapping's Nout for
     *     the forward transformation, its Nin for the inverse
     * @return {@code ncoordOut} arrays, each with one value per grid position, the first axis
     *     varying fastest
     * @throws AstException with status {@code AST__DIMIN} if a bound array is too short, {@code
     *     AST__GBDIN} if a lower bound exceeds its upper bound, {@code AST__EXSPIX} if the grid has
     *     too many positions, {@code AST__TRNND} if the transformation is not defined, {@code
     *     AST__NCPIN} if the coordinate counts are not the Mapping's, {@code AST__PATIN} if {@code
     *     tol} is negative, or {@code AST__SSPIN} if {@code maxpix} is negative
     */
    public double[][] tranGrid(
            int ncoordIn, int[] lbnd, int[] ubnd, double tol, int maxpix, boolean forward, int ncoordOut) {
        return TranGrid.tranGrid(this, ncoordIn, lbnd, ubnd, tol, maxpix, forward, ncoordOut);
    }

    /**
     * Finds the bounds of one output coordinate over a box of input coordinates: the lowest and
     * highest values it takes as the input ranges over the box, and an input position (not
     * necessarily unique) where each is reached. Calling this once per output gives the bounding
     * box of the transformed input box, for instance the extent of an image after it has been
     * transformed. With {@code forward} false the inverse transformation is used, and the inputs
     * and outputs exchange roles. Input positions that give a bad value on any output are ignored.
     *
     * <p>Many positions are tested, using a global search for the extremes, so bounds are found
     * unless good positions are confined to a very small part of the box.
     *
     * @param lbndIn the lower bound of the box on each input of the transformation used; a bound
     *     above its partner is exchanged with it
     * @param ubndIn the upper bound of the box on each input of the transformation used
     * @param forward true for the forward transformation, false for the inverse
     * @param coordOut the output whose bounds are wanted, numbered from 1
     * @param xl null, or an array of at least one value per input, which receives a position where
     *     the lower bound is reached
     * @param xu null, or an array of at least one value per input, which receives a position where
     *     the upper bound is reached
     * @return the lower and upper bounds of output {@code coordOut}
     * @throws AstException with status {@code AST__BADCI} if {@code coordOut} is not an output,
     *     {@code AST__DIMIN} if an array is too short, or {@code AST__MBBNF} if no bounds are found,
     *     typically because every position tested gave a bad value
     */
    public double[] mapBox(double[] lbndIn, double[] ubndIn, boolean forward, int coordOut, double[] xl, double[] xu) {
        return MapBox.mapBox(this, lbndIn, ubndIn, forward, coordOut - 1, xl, xu);
    }

    /**
     * Splits off the part of the Mapping that connects a chosen set of inputs to the outputs they
     * feed. The result is a new Mapping whose inputs are the chosen ones, in the order given, and
     * whose outputs are the outputs of this Mapping that depend only on them, in the order {@code
     * out} gives. A split exists only if some subset of the outputs depends on the chosen inputs
     * alone, and no other output depends on any of them. The new Mapping is a deep copy of the
     * parts of this one it uses.
     *
     * @param in the inputs to pick, numbered from 1, in any order
     * @param out an array of at least {@code Nout} elements, which receives the number (from 1)
     *     of the output of this Mapping that each output of the returned Mapping corresponds to;
     *     the elements after those are unchanged, as are all of them when there is no split
     * @return the split Mapping, or null if there is no split
     * @throws AstException with status {@code AST__DIMIN} if {@code out} is too short, or {@code
     *     AST__AXIIN} for an input that does not exist, from the classes that check
     */
    public Mapping mapSplit(int[] in, int[] out) {
        Objects.requireNonNull(in, "in");
        checkDim("astMapSplit", "out", out.length, getNout());
        // astMapSplitId_ (mapping.c): the protected method takes zero-based indices.
        int[] inZero = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            inZero[i] = in[i] - 1;
        }
        MapSplit split = mapSplit(inZero);
        if (split == null) {
            return null;
        }
        int nout = split.map.getNout();
        for (int i = 0; i < nout; i++) {
            out[i] = split.outputs[i] + 1;
        }
        return split.map;
    }

    /**
     * C's protected {@code astMapSplit} (mapping.c {@code astMapSplit_}): the class's {@link
     * #splitOff}, whose Mapping is then copied, so that the result is independent of this Mapping.
     *
     * @param in the inputs to pick, numbered from zero
     * @return the split, with outputs numbered from zero, or null if there is none
     */
    final MapSplit mapSplit(int[] in) {
        MapSplit split = splitOff(in);
        if (split == null) {
            return null;
        }
        return new MapSplit(split.outputs, (Mapping) split.map.copy());
    }

    /**
     * C's per-class {@code MapSplit} method, with zero-based indices: the split of the inputs
     * {@code in}, which the caller copies. This base version, C's, splits only a selection of
     * every input, each once ({@link MapSplit#baseSplit}); classes that can split part of their
     * inputs override it, as their C classes do.
     *
     * @throws AstException with status {@code AST__AXIIN} for an input that does not exist
     */
    MapSplit splitOff(int[] in) {
        return MapSplit.baseSplit(this, in);
    }

    /**
     * Decomposes the Mapping into two component Mappings which, applied in series or in parallel,
     * are equivalent to it. A CmpMap gives the two Mappings it was made from; any other Mapping
     * gives itself alone. The Mappings returned are the components themselves, not copies, so
     * that a change made to one is reflected in this Mapping; being shared, they are no longer
     * allowed the changes C forbids to an Object that has been cloned.
     *
     * <p>The Invert value returned for each component should be used in preference to the
     * component's own Invert attribute, which may have changed since the Mappings were combined.
     *
     * @param series null, or an array of at least one element, whose first element is set true if
     *     the components are applied in series (the first, then the second) and false if in
     *     parallel (the first to the lower-numbered coordinates, the second to the others)
     * @param inverts null, or an array of at least two elements, which receive the Invert values
     *     to use with the first and second components
     * @return the first component, and the second if there is one
     * @throws AstException with status {@code AST__DIMIN} if an array is too short
     */
    public Mapping[] decompose(boolean[] series, boolean[] inverts) {
        if (series != null) {
            checkDim("astDecompose", "series", series.length, 1);
        }
        if (inverts != null) {
            checkDim("astDecompose", "inverts", inverts.length, 2);
        }
        boolean[] flags = new boolean[3];
        Mapping[] parts = decomposeInto(flags);
        if (series != null) {
            series[0] = flags[0];
        }
        if (inverts != null) {
            inverts[0] = flags[1];
            inverts[1] = flags[2];
        }
        for (Mapping m : parts) {
            m.markShared();
        }
        return parts;
    }

    /**
     * C's per-class {@code Decompose} method: the components, which the caller shares, with
     * {@code flags} receiving whether they are applied in series and the Invert values to use with
     * each. This base version, C's, gives the Mapping itself, in series, with its own Invert value.
     *
     * @param flags receives the series flag and the two Invert values
     * @return one or two components
     */
    Mapping[] decomposeInto(boolean[] flags) {
        flags[0] = true;
        flags[1] = getInvert();
        flags[2] = false;
        return new Mapping[] {this};
    }

    /**
     * C's per-class {@code MapList} method: appends to {@code maps} the Mappings which, applied in
     * series if {@code series} and in parallel otherwise, are equivalent to this Mapping applied
     * with the Invert value {@code invert}, and to {@code inverts} the Invert value to apply each
     * with. This base version, C's, appends the Mapping itself.
     *
     * @return whether an inverted compound Mapping was decomposed
     */
    boolean mapList(boolean series, boolean invert, List<Mapping> maps, List<Boolean> inverts) {
        inverts.add(invert);
        maps.add(this);
        return false;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /** Whether {@code name} is one of the Mapping class's read-only attributes. */
    private static boolean isReadOnly(String name) {
        switch (name) {
            case "nin":
            case "nout":
            case "issimple":
            case "islinear":
            case "tranforward":
            case "traninverse":
                return true;
            default:
                return false;
        }
    }

    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "invert":
                return getInvert() ? "1" : "0";
            case "islinear":
                return isLinear() ? "1" : "0";
            case "issimple":
                return getIsSimple() ? "1" : "0";
            case "nin":
                return Integer.toString(getNin());
            case "nout":
                return Integer.toString(getNout());
            case "report":
                return getReport() ? "1" : "0";
            case "tranforward":
                return getTranForward() ? "1" : "0";
            case "traninverse":
                return getTranInverse() ? "1" : "0";
            default:
                return super.getAttrib(attrib);
        }
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        if ("invert".equals(attrib) && ival != null) {
            setInvert(ival != 0);
        } else if ("report".equals(attrib) && ival != null) {
            setReport(ival != 0);
        } else if (isReadOnly(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ". This is a read-only attribute.");
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "invert":
                return testInvert();
            case "report":
                return testReport();
            default:
                return !isReadOnly(attrib) && super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "invert":
                clearInvert();
                break;
            case "report":
                clearReport();
                break;
            default:
                if (isReadOnly(attrib)) {
                    throw Ast.err(
                            AstException.AST__NOWRT,
                            "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                                    + ". This is a read-only attribute.");
                }
                super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Resampling and rebinning (not available)
    // ------------------------------------------------------------------

    private static UnsupportedOperationException noResampling() {
        return new UnsupportedOperationException("Resampling and rebinning are not available in this version");
    }

    /**
     * Resamples a grid of data of any numeric type, chosen by the class of {@code in}. Not
     * available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resample(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            Object in,
            Object inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            Number badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            Object out,
            Object outVar) {
        throw noResampling();
    }

    /**
     * Resamples a grid of {@code byte} data. Not available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resampleB(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            byte[] in,
            byte[] inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            byte badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            byte[] out,
            byte[] outVar) {
        throw noResampling();
    }

    /**
     * Resamples a grid of {@code short} data. Not available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resampleS(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            short[] in,
            short[] inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            short badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            short[] out,
            short[] outVar) {
        throw noResampling();
    }

    /**
     * Resamples a grid of {@code int} data. Not available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resampleI(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            int[] in,
            int[] inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            int badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            int[] out,
            int[] outVar) {
        throw noResampling();
    }

    /**
     * Resamples a grid of {@code long} data. Not available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resampleL(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            long[] in,
            long[] inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            long badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            long[] out,
            long[] outVar) {
        throw noResampling();
    }

    /**
     * Resamples a grid of {@code float} data. Not available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resampleF(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            float[] in,
            float[] inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            float badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            float[] out,
            float[] outVar) {
        throw noResampling();
    }

    /**
     * Resamples a grid of {@code double} data. Not available in this version.
     *
     * @return never
     * @throws UnsupportedOperationException always
     */
    public int resampleD(
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            double[] in,
            double[] inVar,
            Interpolator interp,
            ResampleFlags flags,
            double tol,
            int maxpix,
            double badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            double[] out,
            double[] outVar) {
        throw noResampling();
    }

    /**
     * Rebins a grid of data of any numeric type, chosen by the class of {@code in}. Not available
     * in this version.
     *
     * @throws UnsupportedOperationException always
     */
    public void rebin(
            double wlim,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            Object in,
            Object inVar,
            Spreader spread,
            boolean usebad,
            double tol,
            int maxpix,
            Number badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            Object out,
            Object outVar) {
        throw noResampling();
    }

    /**
     * Rebins a grid of {@code int} data. Not available in this version.
     *
     * @throws UnsupportedOperationException always
     */
    public void rebinI(
            double wlim,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            int[] in,
            int[] inVar,
            Spreader spread,
            boolean usebad,
            double tol,
            int maxpix,
            int badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            int[] out,
            int[] outVar) {
        throw noResampling();
    }

    /**
     * Rebins a grid of {@code float} data. Not available in this version.
     *
     * @throws UnsupportedOperationException always
     */
    public void rebinF(
            double wlim,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            float[] in,
            float[] inVar,
            Spreader spread,
            boolean usebad,
            double tol,
            int maxpix,
            float badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            float[] out,
            float[] outVar) {
        throw noResampling();
    }

    /**
     * Rebins a grid of {@code double} data. Not available in this version.
     *
     * @throws UnsupportedOperationException always
     */
    public void rebinD(
            double wlim,
            int ndimIn,
            int[] lbndIn,
            int[] ubndIn,
            double[] in,
            double[] inVar,
            Spreader spread,
            boolean usebad,
            double tol,
            int maxpix,
            double badval,
            int ndimOut,
            int[] lbndOut,
            int[] ubndOut,
            int[] lbnd,
            int[] ubnd,
            double[] out,
            double[] outVar) {
        throw noResampling();
    }

    /**
     * A sub-pixel interpolation scheme for resampling: one of C's {@code AST__} schemes with its
     * parameters. The schemes taking {@code npix} use that many pixels on each side of the
     * interpolation point, or choose a number themselves if it is zero or less; {@code width} is
     * the number of pixels at which the second function of a product kernel first falls to zero,
     * and {@code fwhm} the full width at half maximum of a Gaussian, in pixels.
     */
    public static final class Interpolator {

        /** C's scheme identifier. */
        final int scheme;

        /** The 1-dimensional kernel, for {@code AST__UKERN1}. */
        final Ukern1Calculator ukern1er;

        /** The interpolation function, for {@code AST__UINTERP}. */
        final UinterpCalculator uinterper;

        /** The parameters C's {@code params} array holds. */
        final double[] params;

        private Interpolator(int scheme, Ukern1Calculator ukern1er, UinterpCalculator uinterper, double... params) {
            this.scheme = scheme;
            this.ukern1er = ukern1er;
            this.uinterper = uinterper;
            this.params = params.clone();
        }

        /**
         * Returns an Interpolator that takes the value of the input pixel nearest the
         * interpolation point ({@code AST__NEAREST}).
         *
         * @return the Interpolator
         */
        public static Interpolator nearest() {
            return new Interpolator(AST__NEAREST, null, null);
        }

        /**
         * Returns an Interpolator that interpolates linearly between the nearest input pixels
         * ({@code AST__LINEAR}).
         *
         * @return the Interpolator
         */
        public static Interpolator linear() {
            return new Interpolator(AST__LINEAR, null, null);
        }

        /**
         * Returns an Interpolator using the kernel sinc(pi*x) ({@code AST__SINC}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @return the Interpolator
         */
        public static Interpolator sinc(int npix) {
            return new Interpolator(AST__SINC, null, null, npix);
        }

        /**
         * Returns an Interpolator using the kernel sinc(pi*x)*sinc(k*pi*x) ({@code
         * AST__SINCSINC}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @param width the number of pixels at which the second sinc first falls to zero
         * @return the Interpolator
         */
        public static Interpolator sincSinc(int npix, double width) {
            return new Interpolator(AST__SINCSINC, null, null, npix, width);
        }

        /**
         * Returns an Interpolator using the kernel sinc(pi*x)*cos(k*pi*x) ({@code AST__SINCCOS}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @param width the number of pixels at which the cosine first falls to zero
         * @return the Interpolator
         */
        public static Interpolator sincCos(int npix, double width) {
            return new Interpolator(AST__SINCCOS, null, null, npix, width);
        }

        /**
         * Returns an Interpolator using the kernel somb(pi*x), a Bessel-function analogue of sinc
         * ({@code AST__SOMB}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @return the Interpolator
         */
        public static Interpolator somb(int npix) {
            return new Interpolator(AST__SOMB, null, null, npix);
        }

        /**
         * Returns an Interpolator using the kernel somb(pi*x)*cos(k*pi*x) ({@code AST__SOMBCOS}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @param width the number of pixels at which the cosine first falls to zero
         * @return the Interpolator
         */
        public static Interpolator sombCos(int npix, double width) {
            return new Interpolator(AST__SOMBCOS, null, null, npix, width);
        }

        /**
         * Returns an Interpolator using the kernel sinc(pi*x)*exp(-k*x*x) ({@code
         * AST__SINCGAUSS}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @param fwhm the full width at half maximum of the Gaussian, in pixels
         * @return the Interpolator
         */
        public static Interpolator sincGauss(int npix, double fwhm) {
            return new Interpolator(AST__SINCGAUSS, null, null, npix, fwhm);
        }

        /**
         * Returns an Interpolator that averages the input pixels in a block about the
         * interpolation point ({@code AST__BLOCKAVE}).
         *
         * @param npix the number of pixels on each side of the interpolation point
         * @return the Interpolator
         */
        public static Interpolator blockAve(int npix) {
            return new Interpolator(AST__BLOCKAVE, null, null, npix);
        }

        /**
         * Returns an Interpolator using a 1-dimensional kernel supplied by the caller, applied to
         * each dimension in turn ({@code AST__UKERN1}).
         *
         * @param ukern1er the kernel
         * @param npix the number of pixels on each side of the interpolation point
         * @return the Interpolator
         */
        public static Interpolator ukern1(Ukern1Calculator ukern1er, int npix) {
            return new Interpolator(AST__UKERN1, ukern1er, null, npix);
        }

        /**
         * Returns an Interpolator using an interpolation function supplied by the caller ({@code
         * AST__UINTERP}).
         *
         * @param uinterper the interpolation function
         * @return the Interpolator
         */
        public static Interpolator uinterp(UinterpCalculator uinterper) {
            return new Interpolator(AST__UINTERP, null, uinterper);
        }
    }

    /**
     * A scheme for spreading each input pixel's value over the output pixels when rebinning: one
     * of C's {@code AST__} schemes with its parameters, which are as for {@link Interpolator}.
     */
    public static final class Spreader {

        /** C's scheme identifier. */
        final int scheme;

        /** The parameters C's {@code params} array holds. */
        final double[] params;

        private Spreader(int scheme, double... params) {
            this.scheme = scheme;
            this.params = params.clone();
        }

        /**
         * Returns a Spreader that gives each input value to the nearest output pixel ({@code
         * AST__NEAREST}).
         *
         * @return the Spreader
         */
        public static Spreader nearest() {
            return new Spreader(AST__NEAREST);
        }

        /**
         * Returns a Spreader that divides each input value between the nearest output pixels in
         * proportion to their distance ({@code AST__LINEAR}).
         *
         * @return the Spreader
         */
        public static Spreader linear() {
            return new Spreader(AST__LINEAR);
        }

        /**
         * Returns a Spreader using the kernel sinc(pi*x) ({@code AST__SINC}).
         *
         * @param npix the number of pixels on each side of the input position
         * @return the Spreader
         */
        public static Spreader sinc(int npix) {
            return new Spreader(AST__SINC, npix);
        }

        /**
         * Returns a Spreader using the kernel sinc(pi*x)*sinc(k*pi*x) ({@code AST__SINCSINC}).
         *
         * @param npix the number of pixels on each side of the input position
         * @param width the number of pixels at which the second sinc first falls to zero
         * @return the Spreader
         */
        public static Spreader sincSinc(int npix, double width) {
            return new Spreader(AST__SINCSINC, npix, width);
        }

        /**
         * Returns a Spreader using the kernel sinc(pi*x)*cos(k*pi*x) ({@code AST__SINCCOS}).
         *
         * @param npix the number of pixels on each side of the input position
         * @param width the number of pixels at which the cosine first falls to zero
         * @return the Spreader
         */
        public static Spreader sincCos(int npix, double width) {
            return new Spreader(AST__SINCCOS, npix, width);
        }

        /**
         * Returns a Spreader using the kernel sinc(pi*x)*exp(-k*x*x) ({@code AST__SINCGAUSS}).
         *
         * @param npix the number of pixels on each side of the input position
         * @param fwhm the full width at half maximum of the Gaussian, in pixels
         * @return the Spreader
         */
        public static Spreader sincGauss(int npix, double fwhm) {
            return new Spreader(AST__SINCGAUSS, npix, fwhm);
        }

        /**
         * Returns a Spreader using the kernel exp(-k*x*x) ({@code AST__GAUSS}).
         *
         * @param npix the number of pixels on each side of the input position
         * @param fwhm the full width at half maximum of the Gaussian, in pixels
         * @return the Spreader
         */
        public static Spreader gauss(int npix, double fwhm) {
            return new Spreader(AST__GAUSS, npix, fwhm);
        }
    }
}
