package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A coordinate system: a set of axes, each with a label, symbol, unit and format, and the
 * attributes that describe the system as a whole, such as its Title, Domain and Epoch. Methods
 * format and read coordinate values, and measure distances, angles and offsets in the
 * coordinate system; a plain Frame is Cartesian.
 *
 * <p>A Frame is also a Mapping, with as many inputs and outputs as it has axes, which copies its
 * input to its output.
 *
 * <p>The per-axis attributes are addressed with the axis number, counting from 1, as in {@code
 * Label(1)}; on a Frame with one axis the number may be left out. The order of the axes may be
 * changed with {@link #permAxes}, after which the axis numbers refer to the new order.
 *
 * <p>Ported from C {@code frame.c}, with Rust {@code frames/basic.rs}, {@code frames/mod.rs} and
 * {@code frames/frame_attrs.rs}. The methods each class of Frame provides in its own way (C's
 * per-class virtual functions) are overridable methods here; in particular each axis is reached
 * through {@link #getAxis}, which a Frame made of other Frames overrides, so that the per-axis
 * behaviour of the Axis objects is used wherever they are. Axes are numbered from 1 throughout,
 * where C's internal functions number them from 0, except in the axis selections given to {@link
 * #subFrame} and {@link #overlay}, which keep C's numbering from 0 with a negative or too large
 * number selecting no axis.
 */
public class Frame extends Mapping {

    /** The System value of no coordinate system ({@code AST__BADSYSTEM}). */
    static final int AST__BADSYSTEM = -1;

    /** The System value of a Cartesian coordinate system, the only one a plain Frame has ({@code AST__CART}). */
    static final int AST__CART = 0;

    /** The value of an unset integer attribute (C's {@code -INT_MAX}). */
    private static final int UNSET = Axis.UNSET_INT;

    /** The longest NormUnit C's buffer holds ({@code AST__FRAME_GETNORMUNIT_BUFF_LEN}). */
    private static final int NORMUNIT_BUFF_LEN = 127;

    /** The longest default Symbol C's buffer holds ({@code AST__FRAME_SYMBOL_BUFF_LEN}). */
    private static final int SYMBOL_BUFF_LEN = 50;

    /** The number of axes. */
    private int naxes;

    /** The Axis object of each axis, in internal order. */
    private Axis[] axes;

    /** The permutation: {@code perm[external] = internal}, numbered from 0. */
    private int[] perm;

    private String title;
    private String domain;
    private double epoch = AST__BAD;
    private int digits = UNSET;
    private int matchEnd = UNSET;
    private int maxAxes = UNSET;
    private int minAxes = UNSET;
    private int permute = UNSET;
    private int preserveAxes = UNSET;
    private int system = AST__BADSYSTEM;
    private int alignSystem = AST__BADSYSTEM;
    private int activeUnit = UNSET;
    private double obsLat = AST__BAD;
    private double obsLon = AST__BAD;
    private double obsAlt = AST__BAD;
    private double dtai = AST__BAD;
    private double dut1 = AST__BAD;

    /** The protected flags of C's Frame ({@code GetFrameFlags}). */
    private int flags;

    /** The FrameSet of variant Frames, or null. */
    private AstObject variants;

    /**
     * Creates a Frame with the given number of axes, each with default attributes.
     *
     * @param naxes the number of axes, which may be zero
     * @throws AstException with status {@code AST__NAXIN} if {@code naxes} is negative
     */
    // The error names the class with astClass, which is final and reads only the class.
    @SuppressWarnings("this-escape")
    public Frame(int naxes) {
        super(0, 0, true, true);
        if (naxes < 0) {
            throw Ast.err(
                    AstException.AST__NAXIN,
                    "astInitFrame(" + astClass() + "): Number of axes (" + naxes
                            + ") is invalid - this number should not be negative.");
        }
        this.naxes = naxes;
        axes = new Axis[naxes];
        perm = new int[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            axes[axis] = new Axis();
            perm[axis] = axis;
        }
    }

    /** Creates a Frame with no axes, for subclasses that describe their axes themselves. */
    protected Frame() {
        this(0);
    }

    /**
     * Loads a Frame from its description, as C's {@code astLoadFrame} does: Naxes, the Axis
     * objects {@code Ax<n>} (a default Axis where one is missing) with any per-axis items {@code
     * Lbl<n>}, {@code Sym<n>}, {@code Fmt<n>}, {@code Uni<n>}, {@code Dir<n>}, {@code Top<n>},
     * {@code Bot<n>} and {@code Dig<n>} applied to them, the permutation {@code Prm<n>}, and the
     * Frame attributes, each number passed through its setter. The Epoch is written as a year,
     * Besselian before 1984 and Julian from then on.
     *
     * @param node the description
     */
    // C's loader reads the System names through the vtab (astSystemCode), so a subclass reads its own.
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    Frame(NativeNode node) {
        super(node);
        NativeNode part = node.section("Frame");
        naxes = Math.max(0, part.readInt("Naxes", 0));
        axes = new Axis[naxes];
        perm = new int[naxes];
        for (int axis = 0; axis < naxes; axis++) {
            int n = axis + 1;
            NativeNode desc = part.readObject("Ax" + n);
            Axis ax;
            if (desc == null) {
                ax = new Axis();
            } else {
                AstObject obj = ObjectRegistry.load(desc);
                if (!(obj instanceof Axis)) {
                    throw Ast.err(
                            AstException.AST__LDERR,
                            "astLoadFrame(" + astClass() + "): Axis " + n + " is a " + obj.astClass()
                                    + ", not an Axis.");
                }
                ax = (Axis) obj;
            }
            axes[axis] = ax;
            String sval = part.getStringIfSet("Lbl" + n);
            if (sval != null) {
                ax.setAxisLabel(sval);
            }
            sval = part.getStringIfSet("Sym" + n);
            if (sval != null) {
                ax.setAxisSymbol(sval);
            }
            sval = part.getStringIfSet("Fmt" + n);
            if (sval != null) {
                ax.setAxisFormat(sval);
            }
            sval = part.getStringIfSet("Uni" + n);
            if (sval != null) {
                ax.setAxisUnit(sval);
            }
            Long ival = part.getIntIfSet("Dir" + n);
            if (ival != null) {
                ax.setAxisDirection(ival != 0);
            }
            double dval = part.readDouble("Top" + n, AST__BAD);
            if (dval != AST__BAD) {
                ax.setAxisTop(dval);
            }
            dval = part.readDouble("Bot" + n, AST__BAD);
            if (dval != AST__BAD) {
                ax.setAxisBottom(dval);
            }
            ival = part.getIntIfSet("Dig" + n);
            if (ival != null) {
                ax.setAxisDigits((int) (long) ival);
            }
            perm[axis] = part.readInt("Prm" + n, n) - 1;
        }
        // C's loader passes each value read through the Frame class's own setter.
        title = part.getStringIfSet("Title");
        domain = part.getStringIfSet("Domain");
        double year = part.readDouble("Epoch", AST__BAD);
        if (year != AST__BAD) {
            epoch = year < 1984.0 ? Pal.epb2d(year) : Pal.epj2d(year);
        }
        Long ival = part.getIntIfSet("Digits");
        if (ival != null) {
            digits = ival > 1 ? (int) (long) ival : 1;
        }
        preserveAxes = flagValue(part.getIntIfSet("Presrv"));
        permute = flagValue(part.getIntIfSet("Permut"));
        ival = part.getIntIfSet("MinAx");
        if (ival != null) {
            minAxes = Math.max((int) (long) ival, 0);
        }
        ival = part.getIntIfSet("MaxAx");
        if (ival != null) {
            // ConsistentMaxAxes reduces a larger MinAxes to the MaxAxes.
            maxAxes = Math.max((int) (long) ival, 0);
            if (minAxes != UNSET && minAxes > maxAxes) {
                minAxes = maxAxes;
            }
        }
        matchEnd = flagValue(part.getIntIfSet("MchEnd"));
        obsLat = part.readDouble("ObsLat", AST__BAD);
        obsLon = part.readDouble("ObsLon", AST__BAD);
        obsAlt = part.readDouble("ObsAlt", AST__BAD);
        dtai = part.readDouble("Dtai", AST__BAD);
        dut1 = part.readDouble("Dut1", AST__BAD);
        activeUnit = flagValue(part.getIntIfSet("ActUnt"));
        String sval = part.getStringIfSet("System");
        if (sval != null) {
            system = systemCode(sval);
            if (system == AST__BADSYSTEM) {
                throw Ast.err(
                        AstException.AST__ATTIN, "astRead(Channel): Invalid System description \"" + sval + "\".");
            }
        }
        sval = part.getStringIfSet("AlSys");
        if (sval != null) {
            alignSystem = systemCode(sval);
            if (alignSystem == AST__BADSYSTEM) {
                throw Ast.err(
                        AstException.AST__ATTIN, "astRead(Channel): Invalid AlignSystem description \"" + sval + "\".");
            }
        }
        NativeNode vdesc = part.readObject("Vrnts");
        if (vdesc != null) {
            variants = ObjectRegistry.load(vdesc);
        }
    }

    /** A flag read from a description: 0 or 1, or unset if absent. */
    private static int flagValue(Long v) {
        if (v == null) {
            return UNSET;
        }
        return v != 0 ? 1 : 0;
    }

    @Override
    String dumpComment() {
        return "Coordinate system description";
    }

    /**
     * Whether a description of this Object writes the Epoch and System without marking them as
     * worth reading when they are not set, as for a plain Frame (frame.c {@code Dump}, which
     * makes the same choice for a FrameSet whose current Frame is a plain Frame and for a
     * CmpFrame).
     */
    boolean dumpIsFrame() {
        return "Frame".equals(astClass());
    }

    /**
     * Writes the Frame class's part of a description, as C's {@code Dump} (frame.c) does. Whether
     * each Frame attribute is set is that of this Frame's own values, and the values written for
     * those not set are what the class reports. The per-axis items, in the order of the axes as
     * created, are commented out, written for information; the Axis objects follow, written with
     * Full -1 unless Full is 1.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Mapping", "Mapping between coordinate systems");
        boolean isFrame = dumpIsFrame();
        int numAxes = getNaxes();
        int[] p = getPerm();
        int[] invperm = new int[numAxes];
        for (int axis = 0; axis < numAxes; axis++) {
            invperm[p[axis]] = axis;
        }

        node.writeString(
                "Title", title != null, true, title != null ? title : getTitle(), "Title of coordinate system");
        node.writeInt("Naxes", naxes != 0, true, naxes != 0 ? naxes : numAxes, "Number of coordinate axes");
        String sval = domain != null ? domain : getDomain();
        node.writeString("Domain", domain != null, !sval.isEmpty(), sval, "Coordinate system domain");
        double dval = epoch != AST__BAD ? epoch : getEpochMjd();
        boolean bessyr = dval < Pal.epj2d(1984.0);
        dval = bessyr ? Pal.epb(dval) : Pal.epj(dval);
        node.writeDouble(
                "Epoch",
                epoch != AST__BAD,
                !isFrame,
                dval,
                bessyr ? "Besselian epoch of observation" : "Julian epoch of observation");
        for (int axis = 0; axis < numAxes; axis++) {
            node.writeString(
                    "Lbl" + (axis + 1), false, true, getLabel(invperm[axis] + 1), "Label for axis " + (axis + 1));
        }
        for (int axis = 0; axis < numAxes; axis++) {
            node.writeString(
                    "Sym" + (axis + 1), false, false, getSymbol(invperm[axis] + 1), "Symbol for axis " + (axis + 1));
        }
        sval = system != AST__BADSYSTEM
                ? checkedSystemString(system, "System", "astWrite(Channel)")
                : getAttrib("system");
        node.writeString("System", system != AST__BADSYSTEM, !isFrame, sval, "Coordinate system type");
        sval = alignSystem != AST__BADSYSTEM
                ? checkedSystemString(alignSystem, "AlignSystem", "astWrite(Channel)")
                : getAttrib("alignsystem");
        node.writeString("AlSys", alignSystem != AST__BADSYSTEM, false, sval, "Alignment coordinate system");
        for (int axis = 0; axis < numAxes; axis++) {
            sval = getUnit(invperm[axis] + 1);
            String lab = Units.unitLabel(sval);
            String comment = lab != null && !lab.equals(sval)
                    ? "Units for axis " + (axis + 1) + " (" + lab + ")"
                    : "Units for axis " + (axis + 1);
            node.writeString("Uni" + (axis + 1), false, !sval.isEmpty(), sval, comment);
        }
        boolean digitsSet = false;
        for (int axis = 0; axis < numAxes; axis++) {
            Axis ax = getAxis(invperm[axis] + 1);
            boolean set = ax.testAxisDigits();
            int ival = set ? ax.getAxisDigits() : getDigits();
            if (set) {
                digitsSet = true;
            }
            node.writeInt("Dig" + (axis + 1), false, set, ival, "Individual precision for axis " + (axis + 1));
        }
        node.writeInt(
                "Digits",
                digits != UNSET,
                digitsSet,
                digits != UNSET ? digits : getDigits(),
                "Default formatting precision");
        for (int axis = 0; axis < numAxes; axis++) {
            sval = getFormat(invperm[axis] + 1);
            boolean helpful = getAxis(invperm[axis] + 1).testAxisDigits();
            node.writeString("Fmt" + (axis + 1), false, helpful, sval, "Format specifier for axis " + (axis + 1));
        }
        for (int axis = 0; axis < numAxes; axis++) {
            boolean dir = getDirection(invperm[axis] + 1);
            node.writeInt(
                    "Dir" + (axis + 1),
                    false,
                    !dir,
                    dir ? 1 : 0,
                    dir
                            ? "Plot axis " + (axis + 1) + " in conventional direction"
                            : "Plot axis " + (axis + 1) + " in reverse direction");
        }
        for (int axis = 0; axis < numAxes; axis++) {
            dval = getBottom(invperm[axis] + 1);
            node.writeDouble("Bot" + (axis + 1), false, dval != -Double.MAX_VALUE, dval, "Lowest legal axis value");
        }
        for (int axis = 0; axis < numAxes; axis++) {
            dval = getTop(invperm[axis] + 1);
            node.writeDouble("Top" + (axis + 1), false, dval != Double.MAX_VALUE, dval, "Highest legal axis value");
        }
        boolean flag = preserveAxes != UNSET ? preserveAxes != 0 : getPreserveAxes();
        node.writeInt(
                "Presrv",
                preserveAxes != UNSET,
                false,
                flag ? 1 : 0,
                flag ? "Preserve target axes" : "Don't preserve target axes");
        flag = permute != UNSET ? permute != 0 : getPermute();
        node.writeInt(
                "Permut",
                permute != UNSET,
                false,
                flag ? 1 : 0,
                flag ? "Axes may be permuted to match" : "Axes may not be permuted match");
        node.writeInt(
                "MinAx",
                minAxes != UNSET,
                false,
                minAxes != UNSET ? minAxes : getMinAxes(),
                "Minimum number of axes to match");
        node.writeInt(
                "MaxAx",
                maxAxes != UNSET,
                false,
                maxAxes != UNSET ? maxAxes : getMaxAxes(),
                "Maximum number of axes to match");
        flag = matchEnd != UNSET ? matchEnd != 0 : getMatchEnd();
        node.writeInt(
                "MchEnd",
                matchEnd != UNSET,
                false,
                flag ? 1 : 0,
                flag ? "Match final target axes" : "Match initial target axes");
        node.writeDouble(
                "ObsLat",
                obsLat != AST__BAD,
                false,
                obsLat != AST__BAD ? obsLat : getObsLatValue(),
                "Observers geodetic latitude (rads)");
        node.writeDouble(
                "ObsLon",
                obsLon != AST__BAD,
                false,
                obsLon != AST__BAD ? obsLon : getObsLonValue(),
                "Observers geodetic longitude (rads)");
        node.writeDouble(
                "ObsAlt",
                obsAlt != AST__BAD,
                false,
                obsAlt != AST__BAD ? obsAlt : getObsAlt(),
                "Observers geodetic altitude (metres)");
        node.writeDouble("Dtai", dtai != AST__BAD, false, dtai != AST__BAD ? dtai : getDtai(), "TAI-UTC in seconds");
        node.writeDouble("Dut1", dut1 != AST__BAD, false, dut1 != AST__BAD ? dut1 : getDut1(), "UT1-UTC in seconds");
        if (testActiveUnit()) {
            flag = getActiveUnit();
            node.writeInt(
                    "ActUnt",
                    true,
                    false,
                    flag ? 1 : 0,
                    flag ? "Unit strings affects alignment" : "Unit strings do not affect alignment");
        }
        for (int axis = 0; axis < naxes; axis++) {
            boolean set = perm[axis] != axis;
            int ival = perm[axis] + 1;
            node.writeInt(
                    "Prm" + (axis + 1),
                    set,
                    false,
                    ival,
                    set
                            ? "Axis " + (axis + 1) + " permuted to use internal axis " + ival
                            : "Axis " + (axis + 1) + " not permuted");
        }
        for (int axis = 0; axis < naxes; axis++) {
            NativeNode axNode = NativeNode.of(axes[axis]);
            axNode.fullCapped = true;
            node.writeObject("Ax" + (axis + 1), true, false, axNode, "Axis number " + (axis + 1));
        }
        if (variants != null) {
            node.writeObject("Vrnts", true, false, NativeNode.of(variants), "Variant Frames");
        }
    }

    /** Completes a copy as C's {@code Copy} (frame.c) does: the Axis objects and variants are copied. */
    @Override
    void deepCopy() {
        super.deepCopy();
        Axis[] copied = new Axis[axes.length];
        for (int axis = 0; axis < axes.length; axis++) {
            copied[axis] = (Axis) axes[axis].copy();
        }
        axes = copied;
        perm = perm.clone();
        if (variants != null) {
            variants = variants.copy();
        }
    }

    // ------------------------------------------------------------------
    // The Frame as a Mapping
    // ------------------------------------------------------------------

    /**
     * Gets the number of input coordinates, which is the number of axes.
     *
     * @return the number of axes
     */
    @Override
    public int getNin() {
        return getNaxes();
    }

    /**
     * Gets the number of output coordinates, which is the number of axes.
     *
     * @return the number of axes
     */
    @Override
    public int getNout() {
        return getNaxes();
    }

    /** A Frame transforms linearly (frame.c {@code GetIsLinear}). */
    @Override
    boolean isLinear() {
        return true;
    }

    /** A Frame is never recorded as simplified (frame.c {@code GetIsSimple}). */
    @Override
    boolean getIsSimple() {
        return false;
    }

    /** A Frame with a set Ident may still be simplified (frame.c {@code DoNotSimplify}). */
    @Override
    boolean doNotSimplify() {
        return false;
    }

    /**
     * Copies the input to the output, as the UnitMap C's {@code Transform} (frame.c) applies
     * does, a NaN becoming {@link #AST__BAD}.
     */
    @Override
    void transformInto(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        for (int c = 0; c < in.length; c++) {
            double[] a = in[c];
            double[] o = out[c];
            for (int p = 0; p < npoint; p++) {
                double v = a[p];
                o[p] = Double.isNaN(v) ? AST__BAD : v;
            }
        }
    }

    /** The NaN protocol carries bad values as NaN, so this is a plain copy. */
    @Override
    void transformIntoNan(double[][] in, int npoint, boolean forward, double[][] out) {
        validateTransformInto(in, npoint, forward, out);
        for (int c = 0; c < in.length; c++) {
            System.arraycopy(in[c], 0, out[c], 0, npoint);
        }
    }

    /** Reports each value as the Frame formats it (frame.c {@code ReportPoints}). */
    @Override
    void reportPoints(boolean forward, double[][] in, double[][] out, int npoint) {
        MappingReport.write(
                MappingReport.lines(in, out, npoint, (c, v) -> format(c + 1, v), (c, v) -> format(c + 1, v)));
    }

    /** Splits off the chosen axes as a Frame of those axes (frame.c {@code MapSplit}). */
    @Override
    MapSplit splitOff(int[] in) {
        int[] axes1 = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            axes1[i] = in[i] + 1;
        }
        return new MapSplit(in.clone(), pickAxes(in.length, axes1, null));
    }

    // ------------------------------------------------------------------
    // Axes
    // ------------------------------------------------------------------

    /**
     * Gets the number of axes (the Naxes attribute).
     *
     * @return the number of axes
     */
    public int getNaxes() {
        return naxes;
    }

    /**
     * The permutation of the axes: element {@code i} is the internal axis, numbered from 0, that
     * external axis {@code i + 1} uses (C's {@code astGetPerm}).
     */
    int[] getPerm() {
        return perm;
    }

    /**
     * Checks an axis number and converts it between the external and internal order (C's {@code
     * astValidateAxis}).
     *
     * @param axis the axis number, from 1
     * @param fwd true to convert an external number to the internal one, false for the reverse
     * @param method the name of the method, for the error message
     * @return the converted axis number, from 1
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    int validateAxis(int axis, boolean fwd, String method) {
        int numAxes = getNaxes();
        if (numAxes == 0) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Invalid attempt to use an axis index (" + axis + ") for a "
                            + astClass() + " which has no axes.");
        }
        if (axis < 1 || axis > numAxes) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Axis index (" + axis + ") invalid - it should be in the range 1 to "
                            + numAxes + ".");
        }
        int[] p = getPerm();
        if (fwd) {
            return p[axis - 1] + 1;
        }
        int result = 0;
        while (result < numAxes && p[result] != axis - 1) {
            result++;
        }
        return result + 1;
    }

    /**
     * The Axis object of an axis, honouring the permutation (C's {@code astGetAxis}). It is the
     * Frame's own, not a copy. A Frame made of other Frames gives the Axis object of the Frame
     * that holds the axis.
     *
     * @param axis the axis number, from 1
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    Axis getAxis(int axis) {
        return axes[validateAxis(axis, true, "astGetAxis") - 1];
    }

    /**
     * Replaces the Axis object of an axis, honouring the permutation (C's {@code astSetAxis}).
     * The Axis object given is held, not copied.
     *
     * @param axis the axis number, from 1
     * @param ax the new Axis object
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    void setAxis(int axis, Axis ax) {
        axes[validateAxis(axis, true, "astSetAxis") - 1] = ax;
    }

    /**
     * The Frame that holds an axis, and the axis's number in it (C's {@code astPrimaryFrame}): for
     * a plain Frame, the Frame itself and the internal number of the axis.
     *
     * @param axis the axis number, from 1
     * @param primaryAxis receives the axis number in the Frame returned, from 1, in its first
     *     element
     * @return the Frame that holds the axis
     */
    Frame primaryFrame(int axis, int[] primaryAxis) {
        primaryAxis[0] = validateAxis(axis, true, "astPrimaryFrame");
        return this;
    }

    /** The Protected flags of the Frame (C's {@code astGetFrameFlags}). */
    int getFrameFlags() {
        return flags;
    }

    /** Sets the protected flags of the Frame (C's {@code astSetFrameFlags}). */
    void setFrameFlags(int value) {
        flags = value;
    }

    /** The FrameSet of variant Frames, or null (C's {@code astGetFrameVariants}). */
    AstObject getFrameVariants() {
        return variants;
    }

    /** Sets the FrameSet of variant Frames, which is held, not copied (C's {@code astSetFrameVariants}). */
    void setFrameVariants(AstObject value) {
        variants = value;
    }

    /**
     * Checks that a permutation of the axes, numbered from 0, holds each axis once (C's {@code
     * astCheckPerm}).
     *
     * @throws AstException with status {@code AST__PRMIN} if it does not
     */
    void checkPerm(int[] p, String method) {
        int numAxes = getNaxes();
        int[] there = new int[numAxes];
        boolean valid = true;
        for (int axis = 0; axis < numAxes; axis++) {
            if (p[axis] < 0 || p[axis] >= numAxes) {
                valid = false;
                break;
            }
            there[p[axis]]++;
        }
        if (valid) {
            for (int axis = 0; axis < numAxes; axis++) {
                if (there[axis] != 1) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw Ast.err(
                    AstException.AST__PRMIN,
                    method + "(" + astClass() + "): Invalid axis permutation array.\nEach axis index should lie in the"
                            + " range 1 to " + numAxes + " and should occur only once.");
        }
    }

    /**
     * Permutes the order of the axes. Element {@code i} of {@code perm} gives the axis, in the
     * current order and numbered from 1, that becomes axis {@code i + 1}. Every per-axis attribute
     * and coordinate then follows the new order.
     *
     * @param perm the permutation, one element for each axis
     * @throws AstException with status {@code AST__PRMIN} if {@code perm} does not hold each axis
     *     once, or {@code AST__DIMIN} if it is too short
     */
    public void permAxes(int[] perm) {
        int numAxes = getNaxes();
        checkDim("astPermAxes", "permutation", perm.length, numAxes);
        int[] perm0 = new int[numAxes];
        for (int axis = 0; axis < numAxes; axis++) {
            perm0[axis] = perm[axis] - 1;
        }
        checkPerm(perm0, "astPermAxes");
        int[] old = this.perm.clone();
        for (int axis = 0; axis < numAxes; axis++) {
            this.perm[axis] = old[perm0[axis]];
        }
    }

    /**
     * Checks that a selection of axes, numbered from 0, selects no axis twice (C's {@code
     * astValidateAxisSelection}). Numbers that are not axes select none and are allowed.
     *
     * @throws AstException with status {@code AST__SELIN} if an axis is selected twice
     */
    void validateAxisSelection(int nsel, int[] sel, String method) {
        if (nsel == 0) {
            return;
        }
        int numAxes = getNaxes();
        int[] count = new int[numAxes];
        for (int i = 0; i < nsel; i++) {
            int axis = sel[i];
            if (axis >= 0 && axis < numAxes) {
                count[axis]++;
            }
        }
        for (int axis = 0; axis < numAxes; axis++) {
            if (count[axis] > 1) {
                throw Ast.err(
                        AstException.AST__SELIN,
                        method + "(" + astClass() + "): Invalid axis selection - each axis may be selected only once.");
            }
        }
    }

    /**
     * Creates a new Frame from a selection of this Frame's axes, which may also be reordered. An
     * axis number that is not an axis of this Frame gives an axis with default attributes. The
     * new Frame is of the class picking the axes gives: picking every axis of a sky coordinate
     * system in order gives a Frame of the same class.
     *
     * @param naxes the number of axes to pick
     * @param axes the axes to pick, numbered from 1, at least {@code naxes} of them
     * @param map null, or an array of at least one element, which receives a Mapping from this
     *     Frame's coordinates to the new Frame's: it copies the coordinates picked and gives the
     *     bad value for an axis that is not one of this Frame's, and its inverse gives the bad
     *     value for each axis not picked
     * @return the new Frame
     * @throws AstException with status {@code AST__SELIN} if an axis is picked twice, or {@code
     *     AST__DIMIN} if an array is too short
     */
    public Frame pickAxes(int naxes, int[] axes, Mapping[] map) {
        int n = Math.max(naxes, 0);
        checkDim("astPickAxes", "axes", axes.length, n);
        if (map != null) {
            checkDim("astPickAxes", "map", map.length, 1);
        }
        int[] axes0 = new int[n];
        for (int i = 0; i < n; i++) {
            axes0[i] = axes[i] - 1;
        }
        validateAxisSelection(n, axes0, "astPickAxes");
        Mapping[] mapping = new Mapping[1];
        Frame[] frame = new Frame[1];
        subFrame(null, n, axes0, null, mapping, frame);
        if (map != null) {
            map[0] = mapping[0];
        }
        return frame[0];
    }

    /**
     * Selects axes from this Frame, the target, into a new Frame, and makes the Mapping from the
     * target to it (C's {@code astSubFrame}). The new Frame has this Frame's attributes and the
     * selected axes' Axis objects, and then the attributes set on the template, if one is given,
     * overlaid; if both the template and the new Frame use active units, the Mapping converts each
     * axis's units, and the selection fails if one cannot be converted.
     *
     * @param template the template, or null
     * @param resultNaxes the number of axes the new Frame has
     * @param targetAxes for each new axis, the target axis it is, numbered from 0, or one that is
     *     not an axis for a new axis with default attributes
     * @param templateAxes for each new axis, the template axis whose attributes it takes,
     *     numbered from 0, or null for the same as the new axis
     * @param map receives the Mapping in its first element, or null if the selection failed
     * @param result receives the new Frame in its first element, which C gives even when the
     *     units cannot be converted
     * @return whether the selection succeeded
     */
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        map[0] = null;
        result[0] = null;
        int targetNaxes = getNaxes();
        int rnax = Math.max(resultNaxes, 0);
        Frame tempframe = new Frame(rnax);
        int[] inperm = new int[targetNaxes];
        int[] outperm = new int[rnax];
        for (int targetAxis = 0; targetAxis < targetNaxes; targetAxis++) {
            inperm[targetAxis] = -1;
        }
        for (int resultAxis = 0; resultAxis < rnax; resultAxis++) {
            int targetAxis = targetAxes[resultAxis];
            if (targetAxis < 0 || targetAxis >= targetNaxes) {
                outperm[resultAxis] = -1;
            } else {
                tempframe.setAxis(resultAxis + 1, getAxis(targetAxis + 1));
                outperm[resultAxis] = targetAxis;
                inperm[targetAxis] = resultAxis;
            }
        }
        Frame res = (Frame) tempframe.copy();
        overlay(targetAxes, res);
        if (template != null) {
            template.overlay(templateAxes, res);
        }
        boolean unit = targetNaxes == rnax;
        if (unit) {
            for (int resultAxis = 0; resultAxis < rnax; resultAxis++) {
                if (outperm[resultAxis] != resultAxis) {
                    unit = false;
                    break;
                }
            }
        }
        Mapping m = unit ? new UnitMap(rnax) : PermMap.fromInternal(targetNaxes, inperm, rnax, outperm, null);
        result[0] = res;
        boolean match = true;
        if (template != null && template.getActiveUnit() && res.getActiveUnit()) {
            Mapping umap = null;
            boolean uunit = true;
            for (int resultAxis = 0; resultAxis < rnax; resultAxis++) {
                int targetAxis = unit ? resultAxis : outperm[resultAxis];
                Mapping aumap =
                        targetAxis > -1 ? Units.unitMapper(getUnit(targetAxis + 1), res.getUnit(resultAxis + 1)) : null;
                if (aumap == null) {
                    umap = null;
                    match = false;
                    break;
                }
                if (!(aumap instanceof UnitMap)) {
                    uunit = false;
                }
                umap = umap == null ? aumap : CmpMap.transientCmpMap(umap, aumap, false);
            }
            if (!uunit && umap != null) {
                m = CmpMap.transientCmpMap(m, umap, true);
            }
        }
        if (match) {
            map[0] = m;
        }
        return match;
    }

    /**
     * Overlays the attributes set on this Frame, the template, onto another Frame (C's {@code
     * astOverlay}): Dtai, Dut1, Digits, Domain, Epoch, Title, ObsLat, ObsLon and ObsAlt when set,
     * ActiveUnit always, System and AlignSystem when set and valid for {@code result}, and the
     * attributes set on each template axis onto the corresponding axis of {@code result}.
     *
     * <p>C sets System and AlignSystem only when the value {@code astValidateSystem} returns is not
     * zero, so a Cartesian System, whose code is zero, is never overlaid.
     *
     * @param templateAxes for each axis of {@code result}, the template axis, numbered from 0,
     *     whose attributes it takes, or one that is not an axis for none; or null for the axis of
     *     the same number
     * @param result the Frame to change
     */
    void overlay(int[] templateAxes, Frame result) {
        if (testDtai()) {
            result.setDtai(getDtai());
        }
        if (testDut1()) {
            result.setDut1(getDut1());
        }
        if (testDigits()) {
            result.setDigits(getDigits());
        }
        if (testDomain()) {
            result.setDomain(getDomain());
        }
        if (testEpoch()) {
            result.setEpochMjd(getEpochMjd());
        }
        if (testTitle()) {
            result.setTitle(getTitle());
        }
        if (testObsLat()) {
            result.setObsLatValue(getObsLatValue());
        }
        if (testObsLon()) {
            result.setObsLonValue(getObsLonValue());
        }
        if (testObsAlt()) {
            result.setObsAlt(getObsAlt());
        }
        result.setActiveUnit(getActiveUnit());
        if (testSystem()) {
            int sys = systemValue();
            if (result.validateSystem(sys, "astOverlay") != 0) {
                result.setSystemValue(sys);
            }
        }
        if (testAlignSystem()) {
            int sys = alignSystemValue();
            if (result.validateSystem(sys, "astOverlay") != 0) {
                result.setAlignSystemValue(sys);
            }
        }
        int templateNaxes = getNaxes();
        int resultNaxes = result.getNaxes();
        for (int resultAxis = 0; resultAxis < resultNaxes; resultAxis++) {
            int templateAxis = templateAxes != null ? templateAxes[resultAxis] : resultAxis;
            if (templateAxis >= 0 && templateAxis < templateNaxes) {
                getAxis(templateAxis + 1).axisOverlay(result.getAxis(resultAxis + 1));
            }
        }
    }

    /** Whether this Frame is equivalent to a UnitMap when used as a Mapping (C's {@code astIsUnitFrame}). */
    boolean isUnitFrame() {
        return true;
    }

    // ------------------------------------------------------------------
    // Formatting and reading values
    // ------------------------------------------------------------------

    /**
     * Lends the Frame's Digits to an Axis that has none set, as C's per-axis methods do so that
     * the Axis formats with the Frame's precision.
     *
     * @return whether Digits was lent, to be given back with {@link #returnDigits}
     */
    private boolean lendDigits(Axis ax) {
        if (ax.testAxisDigits()) {
            return false;
        }
        ax.setAxisDigits(getDigits());
        return true;
    }

    private static void returnDigits(Axis ax, boolean lent) {
        if (lent) {
            ax.clearAxisDigits();
        }
    }

    /**
     * Formats a coordinate value for an axis, as the axis's Format and Digits specify, or as the
     * Frame's Digits if the axis has none. The bad value gives {@code <bad>}.
     *
     * @param axis the axis number, from 1
     * @param value the value
     * @return the formatted value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist, or {@code
     *     AST__FMTER} if the Format cannot format the value
     */
    public String format(int axis, double value) {
        validateAxis(axis, true, "astFormat");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.axisFormat(value);
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Reads a coordinate value for an axis from the start of a string (C's {@code astUnformat}).
     * For a plain Frame axis the value is a number as C's {@code strtod} reads it; the string
     * {@code <bad>}, in any case and with white space between its characters, reads as the bad
     * value. White space around the value is read with it.
     *
     * @param axis the axis number, from 1
     * @param string the text
     * @param value receives the value in its first element if any characters are read
     * @return the number of characters read, or zero if the string does not start with a value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    int unformat(int axis, String string, double[] value) {
        validateAxis(axis, true, "astUnformat");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        double[] coord = new double[1];
        int nc;
        try {
            nc = ax.axisUnformat(string, coord);
        } catch (AstException e) {
            AstException err = Ast.err(
                    e.getStatus(),
                    e.getMsgText() + "\nastUnformat(" + astClass() + "): Unable to read \"" + getLabel(axis)
                            + "\" value.");
            err.initCause(e);
            throw err;
        } finally {
            returnDigits(ax, lent);
        }
        if (nc != 0) {
            value[0] = coord[0];
        }
        return nc;
    }

    /**
     * Reads a coordinate value for an axis from a string. The value is read as {@link #format}
     * writes it; for a plain Frame axis, a number. The string {@code <bad>} reads as the bad
     * value.
     *
     * @param axis the axis number, from 1
     * @param string the text
     * @return the value, or {@link #AST__BAD} if the string does not start with a value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double unformat(int axis, String string) {
        double[] value = {AST__BAD};
        unformat(axis, string, value);
        return value[0];
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * Checks that a point has a coordinate for each axis.
     *
     * @throws AstException with status {@code AST__DIMIN} if it does not
     */
    final void checkPoint(String method, double[] point) {
        int numAxes = getNaxes();
        if (point.length < numAxes) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    method + "(" + astClass() + "): A point has " + point.length + " coordinates but the " + astClass()
                            + " has " + numAxes + " axes.");
        }
    }

    /** Checks that the Frame has two axes, as {@code method} requires, with C's message and its misspelling. */
    private void checkTwoAxes(String method) {
        int numAxes = getNaxes();
        if (numAxes != 2) {
            throw Ast.err(
                    AstException.AST__NAXIN,
                    method + "(" + astClass() + "): Invalid number of Frame axes (" + numAxes + "). " + method
                            + " can only be used with 2 dimensonal Frames."); // codespell:ignore dimensonal
        }
    }

    /**
     * Finds the angle at point B between the lines to points A and C, in radians. In a
     * two-dimensional Frame the angle is signed, positive in the sense from the second axis to the
     * first, and between -pi and pi; with more axes it is between 0 and pi.
     *
     * @param a point A
     * @param b point B
     * @param c point C
     * @return the angle, or {@link #AST__BAD} if a coordinate is bad or a line has no length
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    public double angle(double[] a, double[] b, double[] c) {
        checkPoint("astAngle", a);
        checkPoint("astAngle", b);
        checkPoint("astAngle", c);
        int numAxes = getNaxes();
        double[] ab = new double[numAxes];
        double[] cb = new double[numAxes];
        double sla = 0.0;
        double slc = 0.0;
        for (int axis = 0; axis < numAxes; axis++) {
            if (isBad(a[axis]) || isBad(b[axis]) || isBad(c[axis])) {
                return AST__BAD;
            }
            ab[axis] = a[axis] - b[axis];
            cb[axis] = c[axis] - b[axis];
            sla += ab[axis] * ab[axis];
            slc += cb[axis] * cb[axis];
        }
        if (sla == 0 || slc == 0) {
            return AST__BAD;
        }
        if (numAxes == 2) {
            double anga = Math.atan2(ab[0], ab[1]);
            double angc = Math.atan2(cb[0], cb[1]);
            return Pal.drange(angc - anga);
        }
        double sp = 0.0;
        for (int axis = 0; axis < numAxes; axis++) {
            sp += ab[axis] * cb[axis];
        }
        double cos = sp / Math.sqrt(sla * slc);
        if (cos > 1.0) {
            cos = 1.0;
        } else if (cos < -1.0) {
            cos = -1.0;
        }
        return Math.acos(cos);
    }

    /**
     * Finds the angle, as seen from point A, from the positive direction of an axis to the line
     * from A to point B, in radians, as {@link #angle} measures it.
     *
     * @param a point A
     * @param b point B
     * @param axis the axis number, from 1
     * @return the angle, or {@link #AST__BAD} if it is not defined
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist, or {@code
     *     AST__DIMIN} if a point has too few coordinates
     */
    public double axAngle(double[] a, double[] b, int axis) {
        validateAxis(axis, true, "astAxAngle");
        checkPoint("astAxAngle", a);
        checkPoint("astAxAngle", b);
        int numAxes = getNaxes();
        double[] aa = new double[numAxes];
        double mxab = 0.0;
        for (int iaxis = 0; iaxis < numAxes; iaxis++) {
            if (isBad(a[iaxis]) || isBad(b[iaxis])) {
                return AST__BAD;
            }
            aa[iaxis] = a[iaxis];
            double ab = Math.abs(a[iaxis] - b[iaxis]);
            if (ab > mxab) {
                mxab = ab;
            }
        }
        // Nudge A along the axis by a small amount relative to the values (frame.c:1458-1469).
        int k = axis - 1;
        double eps = 10000.0 * Math.ulp(1.0);
        if (a[k] != 0.0) {
            aa[k] += eps * Math.abs(a[k]);
        } else if (b[k] != 0.0) {
            aa[k] = eps * Math.abs(b[k]);
        } else if (mxab != 0.0) {
            aa[k] = eps * mxab;
        } else {
            aa[k] = 1.0;
        }
        return angle(aa, a, b);
    }

    /**
     * Finds the increment from one value to another on an axis: for a plain axis their
     * difference.
     *
     * @param axis the axis number, from 1
     * @param v1 the first value
     * @param v2 the second value
     * @return the increment, or {@link #AST__BAD} if a value is bad
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double axDistance(int axis, double v1, double v2) {
        validateAxis(axis, true, "astAxDistance");
        return getAxis(axis).axisDistance(v1, v2);
    }

    /**
     * Adds an increment to a value on an axis: for a plain axis their sum.
     *
     * @param axis the axis number, from 1
     * @param v1 the value
     * @param dist the increment
     * @return the new value, or {@link #AST__BAD} if a value is bad
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double axOffset(int axis, double v1, double dist) {
        validateAxis(axis, true, "astAxOffset");
        return getAxis(axis).axisOffset(v1, dist);
    }

    /**
     * Normalises values on an axis (C's {@code astAxNorm}): with {@code oper} 0 each as {@link
     * #norm} would, with 1 into whichever range gives them the smallest spread. A plain axis
     * leaves them unchanged.
     *
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist, or {@code
     *     AST__OPRIN} if {@code oper} is not 0 or 1
     */
    void axNorm(int axis, int oper, double[] values) {
        validateAxis(axis, true, "astAxNorm");
        Axis ax = getAxis(axis);
        if (oper < 0 || oper > 1) {
            throw Ast.err(AstException.AST__OPRIN, "astAxNorm(" + astClass() + "): Invalid operation " + oper + ".");
        }
        ax.axisNormValues(oper, values);
    }

    /**
     * Whether {@code val} lies between {@code lo} and {@code hi} on an axis, the limits included
     * when {@code closed} (C's {@code astAxIn}).
     */
    boolean axIn(int axis, double lo, double hi, double val, boolean closed) {
        return getAxis(axis).axisIn(lo, hi, val, closed);
    }

    /**
     * Finds the distance between two points along the geodesic joining them: in a plain Frame,
     * the straight line.
     *
     * @param point1 the first point
     * @param point2 the second point
     * @return the distance, or {@link #AST__BAD} if a coordinate is bad
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    public double distance(double[] point1, double[] point2) {
        checkPoint("astDistance", point1);
        checkPoint("astDistance", point2);
        int numAxes = getNaxes();
        double result = 0.0;
        for (int axis = 0; axis < numAxes; axis++) {
            if (isBad(point1[axis]) || isBad(point2[axis])) {
                return AST__BAD;
            }
            double delta = point1[axis] - point2[axis];
            result += delta * delta;
        }
        return Math.sqrt(result);
    }

    /**
     * Finds the point a given distance along the geodesic from one point towards another: in a
     * plain Frame, the straight line.
     *
     * @param point1 the point the distance is measured from
     * @param point2 the point the line passes through
     * @param offset the distance, which may be negative to go away from {@code point2}
     * @return the point, every coordinate {@link #AST__BAD} if a coordinate is bad, or if the
     *     points coincide and {@code offset} is not zero
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    public double[] offset(double[] point1, double[] point2, double offset) {
        checkPoint("astOffset", point1);
        checkPoint("astOffset", point2);
        int numAxes = getNaxes();
        double[] point3 = new double[numAxes];
        double dist = 0.0;
        boolean bad = isBad(offset);
        for (int axis = 0; axis < numAxes && !bad; axis++) {
            if (isBad(point1[axis]) || isBad(point2[axis])) {
                bad = true;
            } else {
                double delta = point1[axis] - point2[axis];
                dist += delta * delta;
            }
        }
        if (!bad) {
            dist = Math.sqrt(dist);
        }
        if (bad || (dist == 0.0 && offset != 0.0)) {
            for (int axis = 0; axis < numAxes; axis++) {
                point3[axis] = AST__BAD;
            }
        } else {
            double fract = dist == 0.0 ? 0.0 : offset / dist;
            for (int axis = 0; axis < numAxes; axis++) {
                point3[axis] = point1[axis] + fract * (point2[axis] - point1[axis]);
            }
        }
        return point3;
    }

    /**
     * Finds the point a given distance from a point along the geodesic leaving it at a given
     * angle, in a two-dimensional Frame. The angle is measured from the positive direction of the
     * second axis, positive towards the first axis.
     *
     * @param point1 the starting point
     * @param angle the angle, in radians
     * @param offset the distance
     * @param point2 receives the point found, or bad values if an input is bad
     * @return the direction of the geodesic at {@code point2}, measured as {@code angle} is, or
     *     {@link #AST__BAD}
     * @throws AstException with status {@code AST__NAXIN} if the Frame does not have two axes, or
     *     {@code AST__DIMIN} if a point has too few coordinates
     */
    public double offset2(double[] point1, double angle, double offset, double[] point2) {
        checkTwoAxes("astOffset2");
        checkPoint("astOffset2", point1);
        checkPoint("astOffset2", point2);
        point2[0] = AST__BAD;
        point2[1] = AST__BAD;
        if (isBad(point1[0]) || isBad(point1[1]) || isBad(angle) || isBad(offset)) {
            return AST__BAD;
        }
        point2[0] = point1[0] + Math.sin(angle) * offset;
        point2[1] = point1[1] + Math.cos(angle) * offset;
        return angle;
    }

    /**
     * Finds the point where two geodesics cross, each given by two points on it, in a
     * two-dimensional Frame: in a plain Frame, two straight lines.
     *
     * @param a1 a point on the first line
     * @param a2 another point on the first line
     * @param b1 a point on the second line
     * @param b2 another point on the second line
     * @return the crossing point, bad if a coordinate is bad, the points of a line coincide or
     *     the lines do not cross
     * @throws AstException with status {@code AST__NAXIN} if the Frame does not have two axes, or
     *     {@code AST__DIMIN} if a point has too few coordinates
     */
    public double[] intersect(double[] a1, double[] a2, double[] b1, double[] b2) {
        checkTwoAxes("astIntersect");
        checkPoint("astIntersect", a1);
        checkPoint("astIntersect", a2);
        checkPoint("astIntersect", b1);
        checkPoint("astIntersect", b2);
        double[] cross = {AST__BAD, AST__BAD};
        if (isBad(a1[0])
                || isBad(a1[1])
                || isBad(a2[0])
                || isBad(a2[1])
                || isBad(b1[0])
                || isBad(b1[1])
                || isBad(b2[0])
                || isBad(b2[1])) {
            return cross;
        }
        double dxa = a2[0] - a1[0];
        double dxb = b2[0] - b1[0];
        if (dxa != 0.0) {
            double ma = (a2[1] - a1[1]) / dxa;
            double ca = a1[1] - a1[0] * ma;
            if (dxb != 0.0) {
                double mb = (b2[1] - b1[1]) / dxb;
                double cb = b1[1] - b1[0] * mb;
                if (ma != mb) {
                    cross[0] = (cb - ca) / (ma - mb);
                    cross[1] = ((ma + mb) * cross[0] + ca + cb) / 2;
                }
            } else if (b1[1] != b2[1]) {
                cross[0] = b1[0];
                cross[1] = ma * b1[0] + ca;
            }
        } else if (dxb != 0.0 && a1[1] != a2[1]) {
            double mb = (b2[1] - b1[1]) / dxb;
            double cb = b1[1] - b1[0] * mb;
            cross[0] = a1[0];
            cross[1] = mb * a1[0] + cb;
        }
        return cross;
    }

    /**
     * Normalises a point, putting each coordinate into the range used for display. A plain Frame
     * leaves the coordinates unchanged.
     *
     * @param value the point, changed in place
     * @throws AstException with status {@code AST__DIMIN} if it has too few coordinates
     */
    public void norm(double[] value) {
        checkPoint("astNorm", value);
        int numAxes = getNaxes();
        for (int axis = 0; axis < numAxes; axis++) {
            value[axis] = getAxis(axis + 1).axisNorm(value[axis]);
        }
    }

    /**
     * Normalises a set of points (C's {@code astNormPoints}), as {@link #normalPoints} does.
     *
     * @param in the points, {@code [naxes][npoint or more]}
     * @param npoint the number of points
     * @param contig whether to choose among equivalent values so that the points are contiguous
     * @return the normalised points, {@code [naxes][npoint]}
     * @throws AstException with status {@code AST__DIMIN} if the points do not have one
     *     coordinate for each axis, or a coordinate has fewer than {@code npoint} values
     */
    double[][] normPoints(double[][] in, int npoint, boolean contig) {
        int numAxes = getNaxes();
        String cls = astClass();
        if (in.length != numAxes) {
            throw Ast.err(
                    AstException.AST__DIMIN,
                    "astNormPoints(" + cls + "): The number of input axes (" + in.length
                            + ") is not the same as the number of axes in the " + cls + " (" + numAxes + ").");
        }
        for (double[] row : in) {
            if (row.length < npoint) {
                throw Ast.err(
                        AstException.AST__DIMIN,
                        "astNormPoints(" + cls + "): The input array dimension value (" + row.length
                                + ") is invalid.\nThis should not be less than the number of points being transformed ("
                                + npoint + ").");
            }
        }
        return normalPoints(in, npoint, contig);
    }

    /**
     * The per-class part of {@link #normPoints} (C's {@code astNormalPoints}): for a plain
     * Frame, each point normalised as {@link #norm} does it.
     *
     * @throws AstException with status {@code AST__NCPIN} if the points do not have one
     *     coordinate for each axis
     */
    double[][] normalPoints(double[][] in, int npoint, boolean contig) {
        int numAxes = getNaxes();
        if (in.length != numAxes) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astNormalPoints(" + astClass() + "): Bad number of coordinate values (" + in.length
                            + ") in input PointSet.\nThe " + astClass() + " given has " + numAxes + " axes.");
        }
        double[][] out = new double[numAxes][npoint];
        double[] buf = new double[numAxes];
        for (int point = 0; point < npoint; point++) {
            for (int coord = 0; coord < numAxes; coord++) {
                buf[coord] = in[coord][point];
            }
            norm(buf);
            for (int coord = 0; coord < numAxes; coord++) {
                out[coord][point] = buf[coord];
            }
        }
        return out;
    }

    /**
     * Resolves a vector into two orthogonal components. The vector from point 1 to point 2 is
     * the basis, and the vector from point 1 to point 3 is resolved into components parallel and
     * perpendicular to it.
     *
     * @param point1 the start of the basis vector and of the vector resolved
     * @param point2 the end of the basis vector
     * @param point3 the end of the vector resolved
     * @param point4 receives the point on the basis vector closest to point 3, bad if it is not
     *     defined
     * @return the length of the parallel component, positive in the direction of point 2, and the
     *     length of the perpendicular component, both {@link #AST__BAD} if a coordinate is bad or
     *     the basis vector has no length
     * @throws AstException with status {@code AST__DIMIN} if a point has too few coordinates
     */
    public double[] resolve(double[] point1, double[] point2, double[] point3, double[] point4) {
        checkPoint("astResolve", point1);
        checkPoint("astResolve", point2);
        checkPoint("astResolve", point3);
        checkPoint("astResolve", point4);
        int numAxes = getNaxes();
        double d1 = AST__BAD;
        double d2 = AST__BAD;
        boolean ok = true;
        for (int axis = 0; axis < numAxes; axis++) {
            point4[axis] = AST__BAD;
            if (isBad(point1[axis]) || isBad(point2[axis]) || isBad(point3[axis])) {
                ok = false;
            }
        }
        if (ok) {
            double dp = 0.0;
            double bv = 0.0;
            for (int axis = 0; axis < numAxes; axis++) {
                double c = point2[axis] - point1[axis];
                dp += c * (point3[axis] - point1[axis]);
                bv += c * c;
            }
            if (bv > 0.0) {
                bv = Math.sqrt(bv);
                d1 = dp / bv;
                for (int axis = 0; axis < numAxes; axis++) {
                    point4[axis] = point1[axis] + (d1 / bv) * (point2[axis] - point1[axis]);
                }
                d2 = 0.0;
                for (int axis = 0; axis < numAxes; axis++) {
                    double c = point3[axis] - point4[axis];
                    d2 += c * c;
                }
                d2 = Math.sqrt(d2);
            }
        }
        return new double[] {d1, d2};
    }

    /**
     * Resolves the vectors from point 1 to each of a set of points into components parallel and
     * perpendicular to the basis vector from point 1 to point 2 (C's {@code astResolvePoints}).
     * In two dimensions the perpendicular component is negative on the side C's sign convention
     * makes it so; with one axis it is zero and the parallel component is the axis increment.
     *
     * @param in the points, {@code [naxes][npoint or more]}
     * @return the parallel and perpendicular components, {@code [2][npoint]}, bad where not
     *     defined
     * @throws AstException with status {@code AST__NCPIN} if the points do not have one
     *     coordinate for each axis, or {@code AST__DIMIN} if point 1 or 2 has too few
     */
    double[][] resolvePoints(double[] point1, double[] point2, double[][] in, int npoint) {
        int numAxes = getNaxes();
        checkPoint("astResolvePoints", point1);
        checkPoint("astResolvePoints", point2);
        if (in.length != numAxes) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astResolvePoints(" + astClass() + "): Bad number of coordinate values (" + in.length
                            + ") in input PointSet.\nThe " + astClass() + " given requires " + numAxes
                            + " coordinate value(s) for each input point.");
        }
        double[] d1 = new double[npoint];
        double[] d2 = new double[npoint];
        if (numAxes < 2) {
            for (int ipoint = 0; ipoint < npoint; ipoint++) {
                d1[ipoint] = axDistance(1, point1[0], in[0][ipoint]);
                d2[ipoint] = 0.0;
            }
            return new double[][] {d1, d2};
        }
        double[] basisv = new double[numAxes];
        boolean ok = true;
        double bv = 0.0;
        for (int axis = 0; axis < numAxes; axis++) {
            if (isBad(point1[axis]) || isBad(point2[axis])) {
                ok = false;
                break;
            }
            basisv[axis] = point2[axis] - point1[axis];
            bv += basisv[axis] * basisv[axis];
        }
        if (ok && bv > 0.0) {
            bv = Math.sqrt(bv);
        } else {
            ok = false;
        }
        for (int ipoint = 0; ipoint < npoint; ipoint++) {
            d1[ipoint] = AST__BAD;
            d2[ipoint] = AST__BAD;
            if (!ok) {
                continue;
            }
            boolean good = true;
            double dp = 0.0;
            for (int axis = 0; axis < numAxes; axis++) {
                double d = in[axis][ipoint] - point1[axis];
                if (isBad(d)) {
                    good = false;
                    break;
                }
                dp += basisv[axis] * d;
            }
            if (!good) {
                continue;
            }
            d1[ipoint] = dp / bv;
            double c = d1[ipoint] / bv;
            if (numAxes > 2) {
                double s = 0.0;
                for (int axis = 0; axis < numAxes; axis++) {
                    double d = in[axis][ipoint] - (point1[axis] + c * basisv[axis]);
                    s += d * d;
                }
                d2[ipoint] = Math.sqrt(s);
            } else {
                double x1 = c * basisv[0];
                double y1 = c * basisv[1];
                double x2 = in[0][ipoint] - (point1[0] + x1);
                double y2 = in[1][ipoint] - (point1[1] + y1);
                d2[ipoint] = Math.sqrt(x2 * x2 + y2 * y2);
                if (x1 * y2 - x2 * y1 < 0.0) {
                    d2[ipoint] = -d2[ipoint];
                }
            }
        }
        return new double[][] {d1, d2};
    }

    // ------------------------------------------------------------------
    // Frame attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Title attribute: a description of the coordinate system. The default is {@code
     * "<n>-d coordinate system"}.
     *
     * @return the Title
     */
    public String getTitle() {
        return title != null ? title : getNaxes() + "-d coordinate system";
    }

    /**
     * Sets the Title attribute.
     *
     * @param title the Title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    boolean testTitle() {
        return title != null;
    }

    void clearTitle() {
        title = null;
    }

    /**
     * Gets the Domain attribute: the physical domain of the coordinate system, such as {@code
     * SKY} or {@code PIXEL}, which decides which Frames can be matched. The default for a plain
     * Frame is blank.
     *
     * @return the Domain
     */
    public String getDomain() {
        return domain != null ? domain : "";
    }

    /**
     * Sets the Domain attribute. White space is removed and letters are made upper case.
     *
     * @param domain the Domain
     */
    public void setDomain(String domain) {
        StringBuilder sb = new StringBuilder(domain.length());
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (!CScan.isCSpace(c)) {
                sb.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
            }
        }
        this.domain = sb.toString();
    }

    boolean testDomain() {
        return domain != null;
    }

    void clearDomain() {
        domain = null;
    }

    /**
     * The Epoch as a Modified Julian Date (TDB): set, or J2000.0. Setting it to the bad value
     * leaves it unset.
     */
    double getEpochMjd() {
        return epoch != AST__BAD ? epoch : Pal.epj2d(2000.0);
    }

    void setEpochMjd(double mjd) {
        epoch = mjd;
    }

    boolean testEpoch() {
        return epoch != AST__BAD;
    }

    void clearEpoch() {
        epoch = AST__BAD;
    }

    /**
     * Gets the Epoch attribute, the date of observation, as a year: Besselian before 1984 and
     * Julian from then on, as written to 17 significant digits. The default is 2000.0.
     *
     * @return the Epoch
     */
    public double getEpoch() {
        return getD("Epoch");
    }

    /**
     * Sets the Epoch attribute from a year: Besselian before 1984 and Julian from then on.
     *
     * @param epoch the year
     */
    public void setEpoch(double epoch) {
        setD("Epoch", epoch);
    }

    /**
     * Sets the Epoch attribute from a date, in any of the forms C's {@code astReadDateTime}
     * reads: {@code B1950}, {@code J2000}, a year, {@code MJD 54321.5}, {@code JD 2454321.5}, or a
     * calendar date and time such as {@code 1996-Oct-2 12:13:56.985}.
     *
     * @param epoch the date
     * @throws AstException with status {@code AST__ATTIN} if the date cannot be read
     */
    public void setEpoch(String epoch) {
        setC("Epoch", epoch);
    }

    /** The System code: set, or {@link #AST__CART} (C's {@code astGetSystem}). */
    int systemValue() {
        return system != AST__BADSYSTEM ? system : AST__CART;
    }

    /** Sets the System code, which must be one the class has (C's {@code astSetSystem}). */
    void setSystemValue(int value) {
        system = validateSystem(value, "astSetSystem");
    }

    boolean testSystem() {
        return system != AST__BADSYSTEM;
    }

    void clearSystem() {
        system = AST__BADSYSTEM;
    }

    /** The AlignSystem code: set, or {@link #AST__CART} (C's {@code astGetAlignSystem}). */
    int alignSystemValue() {
        return alignSystem != AST__BADSYSTEM ? alignSystem : AST__CART;
    }

    /** Sets the AlignSystem code, which must be one the class has. */
    void setAlignSystemValue(int value) {
        alignSystem = validateSystem(value, "astSetAlignSystem");
    }

    boolean testAlignSystem() {
        return alignSystem != AST__BADSYSTEM;
    }

    void clearAlignSystem() {
        alignSystem = AST__BADSYSTEM;
    }

    /**
     * The System code a name gives, ignoring case and trailing white space, or {@link
     * #AST__BADSYSTEM} (C's {@code SystemCode}): for a plain Frame only {@code Cartesian}.
     */
    int systemCode(String name) {
        return chrMatch("Cartesian", name) ? AST__CART : AST__BADSYSTEM;
    }

    /** The name of a System code, or null (C's {@code SystemString}). */
    String systemString(int code) {
        return code == AST__CART ? "Cartesian" : null;
    }

    /**
     * Checks a System code (C's {@code ValidateSystem}).
     *
     * @return the code
     * @throws AstException with status {@code AST__AXIIN} if the class has no such System
     */
    int validateSystem(int code, String method) {
        if (code != AST__CART) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Bad value (" + code + ") given for the System or AlignSystem"
                            + " attribute of a " + astClass() + ".");
        }
        return code;
    }

    /** The name of a System code, or the error C reports for a corrupt one. */
    private String checkedSystemString(int code, String attribute, String method) {
        String s = systemString(code);
        if (s == null) {
            throw Ast.err(
                    AstException.AST__SCSIN,
                    method + ": Corrupt " + astClass() + " contains invalid " + attribute + " identification code ("
                            + code + ").");
        }
        return s;
    }

    /**
     * Gets the System attribute: the coordinate system used to describe positions. A plain
     * Frame's is {@code Cartesian}.
     *
     * @return the System
     */
    public String getSystem() {
        return getC("System");
    }

    /**
     * Sets the System attribute.
     *
     * @param system the System, in any case
     * @throws AstException with status {@code AST__ATTIN} if the class has no such System
     */
    public void setSystem(String system) {
        setC("System", system);
    }

    /**
     * Gets the AlignSystem attribute: the coordinate system in which two Frames of this class are
     * aligned when one is converted to the other. A plain Frame's is {@code Cartesian}.
     *
     * @return the AlignSystem
     */
    public String getAlignSystem() {
        return getC("AlignSystem");
    }

    /**
     * Sets the AlignSystem attribute.
     *
     * @param alignSystem the AlignSystem, in any case
     * @throws AstException with status {@code AST__ATTIN} if the class has no such System
     */
    public void setAlignSystem(String alignSystem) {
        setC("AlignSystem", alignSystem);
    }

    /**
     * Gets the Digits attribute: the number of significant digits used when formatting a value
     * on an axis that has no Digits or Format of its own. The default is 7.
     *
     * @return the Digits
     */
    public int getDigits() {
        return digits != UNSET ? digits : 7;
    }

    /**
     * Sets the Digits attribute, which is at least 1.
     *
     * @param digits the Digits
     */
    public void setDigits(int digits) {
        this.digits = digits > 1 ? digits : 1;
    }

    boolean testDigits() {
        return digits != UNSET;
    }

    void clearDigits() {
        digits = UNSET;
    }

    /**
     * Gets the Digits attribute of an axis: its own if set, otherwise the Frame's.
     *
     * @param axis the axis number, from 1
     * @return the Digits
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public int getDigits(int axis) {
        validateAxis(axis, true, "astGetDigits(axis)");
        Axis ax = getAxis(axis);
        return ax.testAxisDigits() ? ax.getAxisDigits() : getDigits();
    }

    /**
     * Sets the Digits attribute of an axis, which is at least 1.
     *
     * @param axis the axis number, from 1
     * @param digits the Digits
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setDigits(int axis, int digits) {
        validateAxis(axis, true, "astSetDigits(axis)");
        getAxis(axis).setAxisDigits(digits);
    }

    boolean testDigits(int axis) {
        validateAxis(axis, true, "astTestDigits(axis)");
        return getAxis(axis).testAxisDigits();
    }

    void clearDigits(int axis) {
        validateAxis(axis, true, "astClearDigits(axis)");
        getAxis(axis).clearAxisDigits();
    }

    /**
     * Gets the MatchEnd attribute: whether a template with fewer axes than a target matches the
     * target's last axes rather than its first. The default is false.
     *
     * @return the MatchEnd
     */
    public boolean getMatchEnd() {
        return matchEnd != UNSET && matchEnd != 0;
    }

    /**
     * Sets the MatchEnd attribute.
     *
     * @param matchEnd the MatchEnd
     */
    public void setMatchEnd(boolean matchEnd) {
        this.matchEnd = matchEnd ? 1 : 0;
    }

    boolean testMatchEnd() {
        return matchEnd != UNSET;
    }

    void clearMatchEnd() {
        matchEnd = UNSET;
    }

    /**
     * Gets the MaxAxes attribute: the largest number of axes a target may have to match this
     * Frame as a template. The default is the number of axes, but no less than a set MinAxes.
     *
     * @return the MaxAxes
     */
    public int getMaxAxes() {
        if (maxAxes != UNSET) {
            return maxAxes;
        }
        int result = getNaxes();
        if (testMinAxes()) {
            result = Math.max(result, getMinAxes());
        }
        return result;
    }

    /**
     * Sets the MaxAxes attribute, at least zero. A set MinAxes larger than it is reduced to it.
     *
     * @param maxAxes the MaxAxes
     */
    public void setMaxAxes(int maxAxes) {
        int result = Math.max(maxAxes, 0);
        if (testMinAxes() && getMinAxes() > result) {
            setMinAxes(result);
        }
        this.maxAxes = result;
    }

    boolean testMaxAxes() {
        return maxAxes != UNSET;
    }

    void clearMaxAxes() {
        maxAxes = UNSET;
    }

    /**
     * Gets the MinAxes attribute: the smallest number of axes a target may have to match this
     * Frame as a template. The default is the number of axes, but no more than a set MaxAxes.
     *
     * @return the MinAxes
     */
    public int getMinAxes() {
        if (minAxes != UNSET) {
            return minAxes;
        }
        int result = getNaxes();
        if (testMaxAxes()) {
            result = Math.min(result, getMaxAxes());
        }
        return result;
    }

    /**
     * Sets the MinAxes attribute, at least zero. A set MaxAxes smaller than it is increased to it.
     *
     * @param minAxes the MinAxes
     */
    public void setMinAxes(int minAxes) {
        int result = Math.max(minAxes, 0);
        if (testMaxAxes() && getMaxAxes() < result) {
            setMaxAxes(result);
        }
        this.minAxes = result;
    }

    boolean testMinAxes() {
        return minAxes != UNSET;
    }

    void clearMinAxes() {
        minAxes = UNSET;
    }

    /**
     * Gets the Permute attribute: whether the axes of a target may be permuted to match this
     * Frame as a template. The default is true.
     *
     * @return the Permute
     */
    public boolean getPermute() {
        return permute == UNSET || permute != 0;
    }

    /**
     * Sets the Permute attribute.
     *
     * @param permute the Permute
     */
    public void setPermute(boolean permute) {
        this.permute = permute ? 1 : 0;
    }

    boolean testPermute() {
        return permute != UNSET;
    }

    void clearPermute() {
        permute = UNSET;
    }

    /**
     * Gets the PreserveAxes attribute: whether a Frame found by matching this Frame as a template
     * keeps the target's axes rather than taking the template's. The default is false.
     *
     * @return the PreserveAxes
     */
    public boolean getPreserveAxes() {
        return preserveAxes != UNSET && preserveAxes != 0;
    }

    /**
     * Sets the PreserveAxes attribute.
     *
     * @param preserveAxes the PreserveAxes
     */
    public void setPreserveAxes(boolean preserveAxes) {
        this.preserveAxes = preserveAxes ? 1 : 0;
    }

    boolean testPreserveAxes() {
        return preserveAxes != UNSET;
    }

    void clearPreserveAxes() {
        preserveAxes = UNSET;
    }

    /** The ObsLat attribute, the observer's geodetic latitude in radians: set, or zero. */
    double getObsLatValue() {
        return obsLat != AST__BAD ? obsLat : 0.0;
    }

    void setObsLatValue(double value) {
        obsLat = value;
    }

    boolean testObsLat() {
        return obsLat != AST__BAD;
    }

    void clearObsLat() {
        obsLat = AST__BAD;
    }

    /** The ObsLon attribute, the observer's geodetic longitude in radians: set, or zero. */
    double getObsLonValue() {
        return obsLon != AST__BAD ? obsLon : 0.0;
    }

    void setObsLonValue(double value) {
        obsLon = value;
    }

    boolean testObsLon() {
        return obsLon != AST__BAD;
    }

    void clearObsLon() {
        obsLon = AST__BAD;
    }

    /**
     * Gets the ObsLat attribute: the geodetic latitude of the observer, as {@code N} or {@code S}
     * followed by degrees, minutes and seconds.
     *
     * @return the ObsLat
     * @throws UnsupportedOperationException because the angle is formatted by a SkyAxis, which
     *     this version does not have
     */
    public String getObsLat() {
        return getC("ObsLat");
    }

    /**
     * Sets the ObsLat attribute, as {@code N} or {@code S} followed by an angle in degrees.
     *
     * @param obsLat the ObsLat
     * @throws UnsupportedOperationException because the angle is read by a SkyAxis, which this
     *     version does not have
     */
    public void setObsLat(String obsLat) {
        setC("ObsLat", obsLat);
    }

    /**
     * Gets the ObsLon attribute: the geodetic longitude of the observer, as {@code E} or {@code
     * W} followed by degrees, minutes and seconds.
     *
     * @return the ObsLon
     * @throws UnsupportedOperationException because the angle is formatted by a SkyAxis, which
     *     this version does not have
     */
    public String getObsLon() {
        return getC("ObsLon");
    }

    /**
     * Sets the ObsLon attribute, as {@code E} or {@code W} followed by an angle in degrees.
     *
     * @param obsLon the ObsLon
     * @throws UnsupportedOperationException because the angle is read by a SkyAxis, which this
     *     version does not have
     */
    public void setObsLon(String obsLon) {
        setC("ObsLon", obsLon);
    }

    /** The ObsAlt attribute, the observer's altitude in metres: set, or zero. */
    double getObsAlt() {
        return obsAlt != AST__BAD ? obsAlt : 0.0;
    }

    void setObsAlt(double value) {
        obsAlt = value;
    }

    boolean testObsAlt() {
        return obsAlt != AST__BAD;
    }

    void clearObsAlt() {
        obsAlt = AST__BAD;
    }

    /** The Dtai attribute, TAI-UTC in seconds: set, or {@link #AST__BAD}, meaning look it up. */
    double getDtai() {
        return dtai;
    }

    void setDtai(double value) {
        dtai = value;
    }

    boolean testDtai() {
        return dtai != AST__BAD;
    }

    void clearDtai() {
        dtai = AST__BAD;
    }

    /**
     * Gets the Dut1 attribute: UT1-UTC in seconds. The default is zero.
     *
     * @return the Dut1
     */
    public double getDut1() {
        return dut1 != AST__BAD ? dut1 : 0.0;
    }

    /**
     * Sets the Dut1 attribute.
     *
     * @param dut1 the Dut1
     */
    public void setDut1(double dut1) {
        this.dut1 = dut1;
    }

    boolean testDut1() {
        return dut1 != AST__BAD;
    }

    void clearDut1() {
        dut1 = AST__BAD;
    }

    /**
     * Gets the ActiveUnit flag: whether the Unit of each axis is taken into account when this
     * Frame is matched with another, so that, for example, a value in kilometres is converted to
     * metres. It is false, whatever was set, for a Frame with a sky axis, and false by default.
     *
     * @return the ActiveUnit flag
     */
    public boolean getActiveUnit() {
        int numAxes = getNaxes();
        for (int i = 1; i <= numAxes; i++) {
            if (getAxis(i).isSkyAxis()) {
                return false;
            }
        }
        return activeUnit != UNSET && activeUnit != 0;
    }

    /**
     * Sets the ActiveUnit flag.
     *
     * @param value the ActiveUnit flag
     */
    public void setActiveUnit(boolean value) {
        activeUnit = value ? 1 : 0;
    }

    boolean testActiveUnit() {
        return activeUnit != UNSET;
    }

    // ------------------------------------------------------------------
    // Per-axis attributes
    // ------------------------------------------------------------------

    /**
     * Gets the Label attribute of an axis: a description of the quantity on it. The default is
     * {@code "Axis <n>"}, with the axis's number before any permutation.
     *
     * @param axis the axis number, from 1
     * @return the Label
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public String getLabel(int axis) {
        int internal = validateAxis(axis, true, "astGetLabel");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.testAxisLabel() ? ax.getAxisLabel() : "Axis " + internal;
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Sets the Label attribute of an axis.
     *
     * @param axis the axis number, from 1
     * @param label the Label
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setLabel(int axis, String label) {
        validateAxis(axis, true, "astSetLabel");
        getAxis(axis).setAxisLabel(label);
    }

    boolean testLabel(int axis) {
        validateAxis(axis, true, "astTestLabel");
        return getAxis(axis).testAxisLabel();
    }

    void clearLabel(int axis) {
        validateAxis(axis, true, "astClearLabel");
        getAxis(axis).clearAxisLabel();
    }

    /**
     * Gets the Symbol attribute of an axis: a short name for its values. The default is the
     * Domain if set, otherwise {@code x}, followed by the axis's number before any permutation,
     * with spaces made underscores.
     *
     * @param axis the axis number, from 1
     * @return the Symbol
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public String getSymbol(int axis) {
        int internal = validateAxis(axis, true, "astGetSymbol");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.testAxisSymbol() ? ax.getAxisSymbol() : defaultSymbol(internal);
        } finally {
            returnDigits(ax, lent);
        }
    }

    /** C's {@code GetDefaultSymbol}: the Domain if set, else "x", then the internal axis number. */
    private String defaultSymbol(int internal) {
        String num = Integer.toString(internal);
        String prefix = testDomain() ? getDomain() : "x";
        int room = SYMBOL_BUFF_LEN - num.length();
        if (prefix.length() > room) {
            prefix = prefix.substring(0, room);
        }
        StringBuilder sb = new StringBuilder(prefix).append(num);
        for (int i = 0; i < sb.length(); i++) {
            if (CScan.isCSpace(sb.charAt(i))) {
                sb.setCharAt(i, '_');
            }
        }
        return sb.toString();
    }

    /**
     * Sets the Symbol attribute of an axis.
     *
     * @param axis the axis number, from 1
     * @param symbol the Symbol
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setSymbol(int axis, String symbol) {
        validateAxis(axis, true, "astSetSymbol");
        getAxis(axis).setAxisSymbol(symbol);
    }

    boolean testSymbol(int axis) {
        validateAxis(axis, true, "astTestSymbol");
        return getAxis(axis).testAxisSymbol();
    }

    void clearSymbol(int axis) {
        validateAxis(axis, true, "astClearSymbol");
        getAxis(axis).clearAxisSymbol();
    }

    /**
     * Gets the Unit attribute of an axis: the units of its values. The default for a plain Frame
     * is blank.
     *
     * @param axis the axis number, from 1
     * @return the Unit
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public String getUnit(int axis) {
        validateAxis(axis, true, "astGetUnit");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.getAxisUnit();
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Sets the Unit attribute of an axis. Trailing white space is removed. If the ActiveUnit flag
     * is set, a set Label and Symbol are rewritten for the new units and a set Format is cleared
     * when the units change, as C's {@code SetUnit} (frame.c) does.
     *
     * @param axis the axis number, from 1
     * @param unit the Unit
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist, or {@code
     *     AST__BADUN} if, with ActiveUnit set, a units string or the Label cannot be parsed
     */
    public void setUnit(int axis, String unit) {
        int l = unit.length();
        while (l > 0 && CScan.isCSpace(unit.charAt(l - 1))) {
            l--;
        }
        String c = unit.substring(0, l);
        validateAxis(axis, true, "astSetUnit");
        Axis ax = getAxis(axis);
        if (getActiveUnit()) {
            newUnit(ax, getUnit(axis), c);
        }
        ax.setAxisUnit(c);
    }

    boolean testUnit(int axis) {
        validateAxis(axis, true, "astTestUnit");
        return getAxis(axis).testAxisUnit();
    }

    /**
     * Clears the Unit attribute of an axis, rewriting a set Label and Symbol and clearing a set
     * Format as {@link #setUnit} does if the ActiveUnit flag is set.
     */
    void clearUnit(int axis) {
        validateAxis(axis, true, "astSetUnit");
        if (testUnit(axis)) {
            Axis ax = getAxis(axis);
            String oldUnits = ax.getAxisUnit();
            ax.clearAxisUnit();
            String units = getUnit(axis);
            if (getActiveUnit()) {
                newUnit(ax, oldUnits, units);
            }
        }
    }

    /**
     * C's {@code NewUnit} (frame.c): rewrites a set Label and Symbol of an Axis for a change of
     * units, where the old units convert to the new ones, and clears a set Format if the units
     * change.
     */
    private static void newUnit(Axis ax, String oldUnits, String newUnits) {
        if (ax.testAxisLabel()) {
            Units.LabelledMapping lm = Units.relabel(oldUnits, newUnits, ax.getAxisLabel());
            if (lm != null && lm.label != null) {
                ax.setAxisLabel(lm.label);
            }
        }
        if (ax.testAxisSymbol()) {
            Units.LabelledMapping lm = Units.relabel(oldUnits, newUnits, ax.getAxisSymbol());
            if (lm != null && lm.label != null) {
                ax.setAxisSymbol(lm.label);
            }
        }
        if (!oldUnits.equals(newUnits)) {
            ax.clearAxisFormat();
        }
    }

    /**
     * Gets the NormUnit attribute of an axis: its Unit in the standard form C's units handling
     * gives, or the Unit itself if it is not a units expression.
     *
     * @param axis the axis number, from 1
     * @return the NormUnit
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public String getNormUnit(int axis) {
        validateAxis(axis, true, "astGetNormUnit");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            if (ax.testAxisNormUnit()) {
                return ax.getAxisNormUnit();
            }
            String norm = Units.normUnit(getUnit(axis));
            if (norm.length() > NORMUNIT_BUFF_LEN) {
                throw Ast.err(
                        AstException.AST__FMTER,
                        "astGetNormUnit(" + astClass() + "): Internal buffer overflow while normalising the units "
                                + "string '" + norm + "' - result exceeds " + NORMUNIT_BUFF_LEN + " characters.");
            }
            return norm;
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * The InternalUnit attribute of an axis: the units of the values the Frame holds, which for a
     * plain Frame are its Unit.
     *
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    String getInternalUnit(int axis) {
        validateAxis(axis, true, "astGetInternalUnit");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.testAxisInternalUnit() ? ax.getAxisInternalUnit() : getUnit(axis);
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Gets the Format attribute of an axis: the C {@code printf} format used to format its
     * values. The default is {@code %1.<Digits>G}.
     *
     * @param axis the axis number, from 1
     * @return the Format
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public String getFormat(int axis) {
        validateAxis(axis, true, "astGetFormat");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.getAxisFormat();
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Sets the Format attribute of an axis.
     *
     * @param axis the axis number, from 1
     * @param format the Format
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setFormat(int axis, String format) {
        validateAxis(axis, true, "astSetFormat");
        getAxis(axis).setAxisFormat(format);
    }

    boolean testFormat(int axis) {
        validateAxis(axis, true, "astTestFormat");
        return getAxis(axis).testAxisFormat();
    }

    void clearFormat(int axis) {
        validateAxis(axis, true, "astClearFormat");
        getAxis(axis).clearAxisFormat();
    }

    /**
     * Gets the Direction attribute of an axis: whether it should be plotted in the conventional
     * direction (increasing to the right or upwards). The default is true.
     *
     * @param axis the axis number, from 1
     * @return the Direction
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public boolean getDirection(int axis) {
        validateAxis(axis, true, "astGetDirection");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.getAxisDirection();
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Sets the Direction attribute of an axis.
     *
     * @param axis the axis number, from 1
     * @param direction the Direction
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setDirection(int axis, boolean direction) {
        validateAxis(axis, true, "astSetDirection");
        getAxis(axis).setAxisDirection(direction);
    }

    boolean testDirection(int axis) {
        validateAxis(axis, true, "astTestDirection");
        return getAxis(axis).testAxisDirection();
    }

    void clearDirection(int axis) {
        validateAxis(axis, true, "astClearDirection");
        getAxis(axis).clearAxisDirection();
    }

    /**
     * Gets the Top attribute of an axis: the largest value it should show. The default is the
     * largest double.
     *
     * @param axis the axis number, from 1
     * @return the Top
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double getTop(int axis) {
        validateAxis(axis, true, "astGetTop");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.getAxisTop();
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Sets the Top attribute of an axis. The bad value leaves it unset.
     *
     * @param axis the axis number, from 1
     * @param top the Top
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setTop(int axis, double top) {
        validateAxis(axis, true, "astSetTop");
        getAxis(axis).setAxisTop(top);
    }

    boolean testTop(int axis) {
        validateAxis(axis, true, "astTestTop");
        return getAxis(axis).testAxisTop();
    }

    void clearTop(int axis) {
        validateAxis(axis, true, "astClearTop");
        getAxis(axis).clearAxisTop();
    }

    /**
     * Gets the Bottom attribute of an axis: the smallest value it should show. The default is the
     * most negative double.
     *
     * @param axis the axis number, from 1
     * @return the Bottom
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double getBottom(int axis) {
        validateAxis(axis, true, "astGetBottom");
        Axis ax = getAxis(axis);
        boolean lent = lendDigits(ax);
        try {
            return ax.getAxisBottom();
        } finally {
            returnDigits(ax, lent);
        }
    }

    /**
     * Sets the Bottom attribute of an axis. The bad value leaves it unset.
     *
     * @param axis the axis number, from 1
     * @param bottom the Bottom
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setBottom(int axis, double bottom) {
        validateAxis(axis, true, "astSetBottom");
        getAxis(axis).setAxisBottom(bottom);
    }

    boolean testBottom(int axis) {
        validateAxis(axis, true, "astTestBottom");
        return getAxis(axis).testAxisBottom();
    }

    void clearBottom(int axis) {
        validateAxis(axis, true, "astClearBottom");
        getAxis(axis).clearAxisBottom();
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    /**
     * The axis number in {@code attrib} if it is {@code name(n)}, as C's {@code sscanf(attrib,
     * "name(%d)%n")} reads it when all of it is read, or null.
     */
    private static Integer axisArg(String attrib, String name) {
        int n = name.length();
        if (attrib.length() < n + 3
                || !attrib.startsWith(name)
                || attrib.charAt(n) != '('
                || attrib.charAt(attrib.length() - 1) != ')') {
            return null;
        }
        return CScan.scanInt(attrib.substring(n + 1, attrib.length() - 1));
    }

    /**
     * An attribute name with an axis number, {@code name(n)}, as C's {@code "%*[^()]%n(%d)%n"}
     * reads it: a name of one or more characters other than parentheses, then the number in
     * parentheses.
     */
    private static final class AxisAttrib {
        final String name;
        final int axis;

        private AxisAttrib(String name, int axis) {
            this.name = name;
            this.axis = axis;
        }

        static AxisAttrib parse(String attrib) {
            int open = attrib.indexOf('(');
            if (open <= 0 || attrib.lastIndexOf(')', open) >= 0) {
                return null;
            }
            String name = attrib.substring(0, open);
            Integer axis = axisArg(attrib, name);
            return axis == null ? null : new AxisAttrib(name, axis);
        }
    }

    /** The error for ObsLat and ObsLon in text, which C formats and reads with a SkyAxis. */
    private static UnsupportedOperationException noSkyAxis(String attrib) {
        return new UnsupportedOperationException(
                attrib + " is formatted and read as an angle by a SkyAxis, which is not available in this version");
    }

    @Override
    String getAttrib(String attrib) {
        return getAttrib(attrib, null);
    }

    /**
     * C's {@code GetAttrib} (frame.c). A Frame with one axis takes a per-axis name without an
     * axis number as one for axis 1: C tries the name again with {@code (1)} added, {@code
     * original} then being the name as given, which goes to the parent class if the name with
     * the number is not one this class knows either.
     */
    private String getAttrib(String attrib, String original) {
        boolean hasAxis = attrib.indexOf('(') >= 0;
        AxisAttrib aa = AxisAttrib.parse(attrib);
        String ix = aa != null ? aa.name : "";
        int axis = aa != null ? aa.axis : 0;
        if ("digits".equals(attrib)) {
            return Integer.toString(getDigits());
        } else if ("digits".equals(ix)) {
            return Integer.toString(getDigits(axis));
        } else if ("direction".equals(ix)) {
            return getDirection(axis) ? "1" : "0";
        } else if ("epoch".equals(attrib)) {
            double e = getEpochMjd();
            return DateTime.fmtDecimalYr(e < Pal.epj2d(1984.0) ? Pal.epb(e) : Pal.epj(e), 17);
        } else if ("top".equals(ix)) {
            return CFormat.format("%.17g", getTop(axis));
        } else if ("bottom".equals(ix)) {
            return CFormat.format("%.17g", getBottom(axis));
        } else if ("domain".equals(attrib)) {
            return getDomain();
        } else if ("format".equals(ix)) {
            return getFormat(axis);
        } else if ("label".equals(ix)) {
            return getLabel(axis);
        } else if ("matchend".equals(attrib)) {
            return getMatchEnd() ? "1" : "0";
        } else if ("maxaxes".equals(attrib)) {
            return Integer.toString(getMaxAxes());
        } else if ("minaxes".equals(attrib)) {
            return Integer.toString(getMinAxes());
        } else if ("naxes".equals(attrib)) {
            return Integer.toString(getNaxes());
        } else if ("permute".equals(attrib)) {
            return getPermute() ? "1" : "0";
        } else if ("preserveaxes".equals(attrib)) {
            return getPreserveAxes() ? "1" : "0";
        } else if ("symbol".equals(ix)) {
            return getSymbol(axis);
        } else if ("alignsystem".equals(attrib)) {
            return checkedSystemString(alignSystemValue(), "AlignSystem", "astGetAttrib(" + astClass() + ")");
        } else if ("system".equals(attrib)) {
            return checkedSystemString(systemValue(), "System", "astGetAttrib(" + astClass() + ")");
        } else if ("title".equals(attrib)) {
            return getTitle();
        } else if ("unit".equals(ix)) {
            return getUnit(axis);
        } else if ("normunit".equals(ix)) {
            return getNormUnit(axis);
        } else if ("internalunit".equals(ix)) {
            return getInternalUnit(axis);
        } else if ("obslat".equals(attrib) || "obslon".equals(attrib)) {
            throw noSkyAxis("obslat".equals(attrib) ? "ObsLat" : "ObsLon");
        } else if ("obsalt".equals(attrib)) {
            return CFormat.format("%.17g", getObsAlt());
        } else if ("dtai".equals(attrib)) {
            return CFormat.format("%.17g", getDtai());
        } else if ("dut1".equals(attrib)) {
            return CFormat.format("%.17g", getDut1());
        }
        if (original == null && aa != null) {
            return getAxisAttrib(aa);
        } else if (!hasAxis && getNaxes() == 1) {
            return getAttrib(attrib + "(1)", attrib);
        }
        return super.getAttrib(original != null ? original : attrib);
    }

    /**
     * The generic per-axis branch of C's {@code GetAttrib}: the attribute of the Axis object;
     * failing that, the attribute of the Frame that holds the axis, with that Frame's axis number
     * and then without one; failing that, the Axis object's error.
     */
    // C clears each error but the last and tries the next way of using the name.
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private String getAxisAttrib(AxisAttrib aa) {
        validateAxis(aa.axis, true, "astGet");
        Axis ax = getAxis(aa.axis);
        try {
            return ax.getAttrib(aa.name);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        int[] paxis = new int[1];
        Frame pfrm = primaryFrame(aa.axis, paxis);
        if (!sameObject(pfrm)) {
            int p = pfrm.validateAxis(paxis[0], false, "astGet");
            try {
                return pfrm.getAttrib(aa.name + "(" + p + ")");
            } catch (AstException e) {
                // C clears the error and tries the name without an axis number.
            }
        }
        try {
            return pfrm.getAttrib(aa.name);
        } catch (AstException e) {
            return ax.getAttrib(aa.name);
        }
    }

    @Override
    void setAttrib(String attrib, String value) {
        setAttrib(attrib, value, null);
    }

    /**
     * The value from its first character that is not white space, if it is one word with white
     * space around it, as C's {@code " %n%*s %n"} reads all of it; otherwise null.
     */
    private static String oneWord(String value) {
        int i = 0;
        while (i < value.length() && CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < value.length() && !CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        if (i == start) {
            return null;
        }
        while (i < value.length() && CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        return i == value.length() ? value.substring(start) : null;
    }

    /** Whether a value holds a character that is not white space, as C's {@code "%*s"} needs. */
    private static boolean hasWord(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!CScan.isCSpace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** C's {@code SetAttrib} (frame.c); {@code original} as for {@link #getAttrib(String, String)}. */
    private void setAttrib(String attrib, String value, String original) {
        Integer ival = CScan.scanInt(value);
        Double dval = CScan.scanDouble(value);
        String w = oneWord(value);
        AxisAttrib aa = AxisAttrib.parse(attrib);
        String ix = aa != null ? aa.name : "";
        int axis = aa != null ? aa.axis : 0;
        if ("digits".equals(attrib) && ival != null) {
            setDigits(ival);
        } else if ("digits".equals(ix) && ival != null) {
            setDigits(axis, ival);
        } else if ("direction".equals(ix) && ival != null) {
            setDirection(axis, ival != 0);
        } else if ("epoch".equals(attrib)) {
            double mjd;
            try {
                mjd = DateTime.readDateTime(value);
            } catch (AstException e) {
                AstException err = Ast.err(
                        AstException.AST__ATTIN,
                        e.getMsgText() + "\nastSetAttrib(" + astClass() + "): Invalid epoch value \"" + value
                                + "\" given for coordinate system.");
                err.initCause(e);
                throw err;
            }
            setEpochMjd(mjd);
        } else if ("top".equals(ix) && dval != null) {
            setTop(axis, dval);
        } else if ("bottom".equals(ix) && dval != null) {
            setBottom(axis, dval);
        } else if ("domain".equals(attrib)) {
            setDomain(value);
        } else if ("format".equals(ix)) {
            setFormat(axis, value);
        } else if ("label".equals(ix)) {
            setLabel(axis, value);
        } else if ("matchend".equals(attrib) && ival != null) {
            setMatchEnd(ival != 0);
        } else if ("maxaxes".equals(attrib) && ival != null) {
            setMaxAxes(ival);
        } else if ("minaxes".equals(attrib) && ival != null) {
            setMinAxes(ival);
        } else if ("permute".equals(attrib) && ival != null) {
            setPermute(ival != 0);
        } else if ("preserveaxes".equals(attrib) && ival != null) {
            setPreserveAxes(ival != 0);
        } else if ("symbol".equals(ix)) {
            setSymbol(axis, value);
        } else if ("alignsystem".equals(attrib) && w != null) {
            int code = systemCode(w);
            if (code == AST__BADSYSTEM) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Invalid AlignSystem description \"" + w + "\".");
            }
            setAlignSystemValue(code);
        } else if ("system".equals(attrib) && w != null) {
            int code = systemCode(w);
            if (code == AST__BADSYSTEM) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Invalid System description \"" + w + "\".");
            }
            setSystemValue(code);
        } else if ("title".equals(attrib)) {
            setTitle(value);
        } else if ("unit".equals(ix)) {
            setUnit(axis, value);
        } else if (("obslat".equals(attrib) || "obslon".equals(attrib)) && hasWord(value)) {
            throw noSkyAxis("obslat".equals(attrib) ? "ObsLat" : "ObsLon");
        } else if ("obsalt".equals(attrib) && dval != null) {
            setObsAlt(dval);
        } else if ("dtai".equals(attrib) && dval != null) {
            setDtai(dval);
        } else if ("dut1".equals(attrib) && dval != null) {
            setDut1(dval);
        } else if ("naxes".equals(attrib) || attrib.startsWith("normunit") || attrib.startsWith("internalunit")) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else {
            if (original == null && aa != null) {
                setAxisAttrib(aa, value);
            } else if (original == null && attrib.indexOf('(') < 0 && getNaxes() == 1) {
                setAttrib(attrib + "(1)", value, attrib);
            } else {
                super.setAttrib(original != null ? original : attrib, value);
            }
        }
    }

    /** The generic per-axis branch of C's {@code SetAttrib}, as {@link #getAxisAttrib} for getting. */
    // C clears each error but the last and tries the next way of using the name.
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private void setAxisAttrib(AxisAttrib aa, String value) {
        validateAxis(aa.axis, true, "astSet");
        Axis ax = getAxis(aa.axis);
        try {
            ax.setAttrib(aa.name, value);
            return;
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        int[] paxis = new int[1];
        Frame pfrm = primaryFrame(aa.axis, paxis);
        if (!sameObject(pfrm)) {
            int p = pfrm.validateAxis(paxis[0], false, "astSet");
            try {
                pfrm.setAttrib(aa.name + "(" + p + ")", value);
                return;
            } catch (AstException e) {
                // C clears the error and tries the name without an axis number.
            }
        }
        try {
            pfrm.setAttrib(aa.name, value);
        } catch (AstException e) {
            ax.setAttrib(aa.name, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        return testAttrib(attrib, null);
    }

    /** C's {@code TestAttrib} (frame.c); {@code original} as for {@link #getAttrib(String, String)}. */
    private boolean testAttrib(String attrib, String original) {
        AxisAttrib aa = AxisAttrib.parse(attrib);
        String ix = aa != null ? aa.name : "";
        int axis = aa != null ? aa.axis : 0;
        if ("digits".equals(attrib)) {
            return testDigits();
        } else if ("digits".equals(ix)) {
            return testDigits(axis);
        } else if ("direction".equals(ix)) {
            return testDirection(axis);
        } else if ("epoch".equals(attrib)) {
            return testEpoch();
        } else if ("bottom".equals(ix)) {
            return testBottom(axis);
        } else if ("top".equals(ix)) {
            return testTop(axis);
        } else if ("domain".equals(attrib)) {
            return testDomain();
        } else if ("format".equals(ix)) {
            return testFormat(axis);
        } else if ("label".equals(ix)) {
            return testLabel(axis);
        } else if ("matchend".equals(attrib)) {
            return testMatchEnd();
        } else if ("maxaxes".equals(attrib)) {
            return testMaxAxes();
        } else if ("minaxes".equals(attrib)) {
            return testMinAxes();
        } else if ("permute".equals(attrib)) {
            return testPermute();
        } else if ("preserveaxes".equals(attrib)) {
            return testPreserveAxes();
        } else if ("symbol".equals(ix)) {
            return testSymbol(axis);
        } else if ("alignsystem".equals(attrib)) {
            return testAlignSystem();
        } else if ("system".equals(attrib)) {
            return testSystem();
        } else if ("title".equals(attrib)) {
            return testTitle();
        } else if ("unit".equals(ix)) {
            return testUnit(axis);
        } else if ("obslat".equals(attrib)) {
            return testObsLat();
        } else if ("obslon".equals(attrib)) {
            return testObsLon();
        } else if ("obsalt".equals(attrib)) {
            return testObsAlt();
        } else if ("dtai".equals(attrib)) {
            return testDtai();
        } else if ("dut1".equals(attrib)) {
            return testDut1();
        } else if ("naxes".equals(attrib) || attrib.startsWith("normunit") || attrib.startsWith("internalunit")) {
            return false;
        }
        if (original == null && aa != null) {
            return testAxisAttrib(aa);
        } else if (original == null && attrib.indexOf('(') < 0 && getNaxes() == 1) {
            return testAttrib(attrib + "(1)", attrib);
        }
        return super.testAttrib(original != null ? original : attrib);
    }

    /** The generic per-axis branch of C's {@code TestAttrib}, as {@link #getAxisAttrib} for getting. */
    // C clears each error but the last and tries the next way of using the name.
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private boolean testAxisAttrib(AxisAttrib aa) {
        validateAxis(aa.axis, true, "astTest");
        Axis ax = getAxis(aa.axis);
        try {
            return ax.testAttrib(aa.name);
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        int[] paxis = new int[1];
        Frame pfrm = primaryFrame(aa.axis, paxis);
        if (!sameObject(pfrm)) {
            int p = pfrm.validateAxis(paxis[0], false, "astTest");
            try {
                return pfrm.testAttrib(aa.name + "(" + p + ")");
            } catch (AstException e) {
                // C clears the error and tries the name without an axis number.
            }
        }
        try {
            return pfrm.testAttrib(aa.name);
        } catch (AstException e) {
            return ax.testAttrib(aa.name);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        clearAttrib(attrib, null);
    }

    /** C's {@code ClearAttrib} (frame.c); {@code original} as for {@link #getAttrib(String, String)}. */
    private void clearAttrib(String attrib, String original) {
        AxisAttrib aa = AxisAttrib.parse(attrib);
        String ix = aa != null ? aa.name : "";
        int axis = aa != null ? aa.axis : 0;
        if ("digits".equals(attrib)) {
            clearDigits();
        } else if ("digits".equals(ix)) {
            clearDigits(axis);
        } else if ("direction".equals(ix)) {
            clearDirection(axis);
        } else if ("epoch".equals(attrib)) {
            clearEpoch();
        } else if ("top".equals(ix)) {
            clearTop(axis);
        } else if ("bottom".equals(ix)) {
            clearBottom(axis);
        } else if ("domain".equals(attrib)) {
            clearDomain();
        } else if ("format".equals(ix)) {
            clearFormat(axis);
        } else if ("label".equals(ix)) {
            clearLabel(axis);
        } else if ("matchend".equals(attrib)) {
            clearMatchEnd();
        } else if ("maxaxes".equals(attrib)) {
            clearMaxAxes();
        } else if ("minaxes".equals(attrib)) {
            clearMinAxes();
        } else if ("permute".equals(attrib)) {
            clearPermute();
        } else if ("preserveaxes".equals(attrib)) {
            clearPreserveAxes();
        } else if ("symbol".equals(ix)) {
            clearSymbol(axis);
        } else if ("system".equals(attrib)) {
            clearSystem();
        } else if ("alignsystem".equals(attrib)) {
            clearAlignSystem();
        } else if ("title".equals(attrib)) {
            clearTitle();
        } else if ("unit".equals(ix)) {
            clearUnit(axis);
        } else if ("obslat".equals(attrib)) {
            clearObsLat();
        } else if ("obslon".equals(attrib)) {
            clearObsLon();
        } else if ("obsalt".equals(attrib)) {
            clearObsAlt();
        } else if ("dtai".equals(attrib)) {
            clearDtai();
        } else if ("dut1".equals(attrib)) {
            clearDut1();
        } else if ("naxes".equals(attrib) || attrib.startsWith("normunit") || attrib.startsWith("internalunit")) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else {
            if (original == null && aa != null) {
                clearAxisAttrib(aa);
            } else if (original == null && attrib.indexOf('(') < 0 && getNaxes() == 1) {
                clearAttrib(attrib + "(1)", attrib);
            } else {
                super.clearAttrib(original != null ? original : attrib);
            }
        }
    }

    /** The generic per-axis branch of C's {@code ClearAttrib}, as {@link #getAxisAttrib} for getting. */
    // C clears each error but the last and tries the next way of using the name.
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private void clearAxisAttrib(AxisAttrib aa) {
        validateAxis(aa.axis, true, "astClear");
        Axis ax = getAxis(aa.axis);
        try {
            ax.clearAttrib(aa.name);
            return;
        } catch (AstException e) {
            if (e.getStatus() != AstException.AST__BADAT) {
                throw e;
            }
        }
        int[] paxis = new int[1];
        Frame pfrm = primaryFrame(aa.axis, paxis);
        if (!sameObject(pfrm)) {
            int p = pfrm.validateAxis(paxis[0], false, "astClear");
            try {
                pfrm.clearAttrib(aa.name + "(" + p + ")");
                return;
            } catch (AstException e) {
                // C clears the error and tries the name without an axis number.
            }
        }
        try {
            pfrm.clearAttrib(aa.name);
        } catch (AstException e) {
            ax.clearAttrib(aa.name);
        }
    }
}
