package uk.ac.starlink.ast;

/**
 * One axis of a coordinate system: the attributes C's Axis class stores for it, and the way
 * values on it are formatted, read, compared and normalised. A {@link Frame} holds one Axis
 * object for each of its axes; the Frame's per-axis attributes and methods are those of its Axis
 * objects, with defaults the Frame supplies.
 *
 * <p>The attributes of an Axis are Label, Symbol, Unit, Digits, Format, Direction, Top and
 * Bottom, and the read-only NormUnit and InternalUnit, all addressed through a Frame as, for
 * example, {@code Label(1)}. A plain Axis treats values as Cartesian: they are not normalised,
 * and the distance between two is their difference.
 *
 * <p>Ported from C {@code axis.c}, with the formatting of Rust {@code frames/axis_format.rs}. The
 * methods each class of Axis provides in its own way (C's per-class virtual functions, such as
 * {@code AxisFormat} and {@code AxisDistance}) are overridable methods here, so that a sky axis
 * can supply its own; a Frame reaches them through its {@code getAxis}.
 */
public class Axis extends AstObject {

    /** The value of an unset integer attribute (C's {@code -INT_MAX}). */
    static final int UNSET_INT = -Integer.MAX_VALUE;

    /** The Label attribute, or null if it is not set. */
    private String label;

    /** The Symbol attribute, or null if it is not set. */
    private String symbol;

    /** The Unit attribute, or null if it is not set. */
    private String unit;

    /** The Format attribute, or null if it is not set. */
    private String format;

    /** The Digits attribute, or {@link #UNSET_INT}. */
    private int digits = UNSET_INT;

    /** The Direction attribute (0 or 1), or {@link #UNSET_INT}. */
    private int direction = UNSET_INT;

    /** The Top attribute, or {@link #AST__BAD} if it is not set. */
    private double top = AST__BAD;

    /** The Bottom attribute, or {@link #AST__BAD} if it is not set. */
    private double bottom = AST__BAD;

    /** Creates an Axis with no attributes set, as C's {@code astInitAxis} does. */
    protected Axis() {}

    /**
     * Loads an Axis from its description, as C's {@code astLoadAxis} does: Label, Symbol, Unit,
     * Digits, Format, Dirn, Top and Bottom, each unset if absent; Digits is at least 1 and Dirn is
     * 0 or 1.
     *
     * @param node the description
     */
    Axis(NativeNode node) {
        super(node);
        NativeNode part = node.section("Axis");
        label = part.getStringIfSet("Label");
        symbol = part.getStringIfSet("Symbol");
        unit = part.getStringIfSet("Unit");
        // C's loader passes each number read through the class's own setter, which constrains it.
        Long d = part.getIntIfSet("Digits");
        if (d != null) {
            digits = d > 1 ? (int) (long) d : 1;
        }
        format = part.getStringIfSet("Format");
        Long dir = part.getIntIfSet("Dirn");
        if (dir != null) {
            direction = dir != 0 ? 1 : 0;
        }
        top = part.readDouble("Top", AST__BAD);
        bottom = part.readDouble("Bottom", AST__BAD);
    }

    @Override
    String dumpComment() {
        return "Coordinate axis";
    }

    /**
     * Writes the Axis class's part of a description, as C's {@code Dump} (axis.c) does: each
     * attribute active when set and otherwise the value the class gives, with Label and Symbol
     * helpful, and the Unit's comment naming the unit when its name differs from the string.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Object", "AST Object");
        node.writeString("Label", testAxisLabel(), true, getAxisLabel(), "Axis Label");
        node.writeString("Symbol", testAxisSymbol(), true, getAxisSymbol(), "Axis symbol");
        String sval = getAxisUnit();
        String lab = Units.unitLabel(sval);
        String comment = lab != null && !lab.equals(sval) ? "Axis units (" + lab + ")" : "Axis units";
        node.writeString("Unit", testAxisUnit(), false, sval, comment);
        node.writeInt("Digits", testAxisDigits(), false, getAxisDigits(), "Default formatting precision");
        // The Axis class's own Format, as C's private TestAxisFormat and GetAxisFormat give it; a
        // class that keeps its Format elsewhere supplies the unset value through getAxisFormat.
        boolean set = format != null;
        node.writeString("Format", set, false, set ? format : getAxisFormat(), "Format specifier");
        boolean dir = getAxisDirection();
        node.writeInt(
                "Dirn",
                testAxisDirection(),
                false,
                dir ? 1 : 0,
                dir ? "Plot in conventional direction (hint)" : "Plot in reverse direction (hint)");
        node.writeDouble("Top", testAxisTop(), false, getAxisTop(), "Maximum legal axis value");
        node.writeDouble("Bottom", testAxisBottom(), false, getAxisBottom(), "Minimum legal axis value");
    }

    // ------------------------------------------------------------------
    // Per-class methods
    // ------------------------------------------------------------------

    /**
     * Whether this is a sky axis, whose values are angles (C's {@code astIsASkyAxis}). A Frame
     * with a sky axis does not use active units.
     */
    boolean isSkyAxis() {
        return false;
    }

    /**
     * Formats a value for this axis (C's {@code AxisFormat}): {@code <bad>} for a bad value
     * ({@link Mapping#isBad}), otherwise the value written with the Format, rewritten as C's {@code
     * ParseAxisFormat} rewrites it. The Format is the Axis class's own ({@link #ownAxisFormat}),
     * not one a subclass that extends the Format syntax keeps.
     *
     * @throws AstException with status {@code AST__FMTER} if the Format cannot format one value
     */
    String axisFormat(double value) {
        if (Mapping.isBad(value)) {
            return "<bad>";
        }
        return AxisFormat.formatValue(astClass(), ownAxisFormat(), getAxisDigits(), value);
    }

    /**
     * Reads a value for this axis from the start of {@code string} (C's {@code AxisUnformat}),
     * as {@code sscanf("%lf %n")} reads it, or the {@code <bad>} token in any case with white
     * space between its characters.
     *
     * @param value receives the value in its first element if any characters are read
     * @return the number of characters read, including white space around the value, or zero
     */
    int axisUnformat(String string, double[] value) {
        int i = 0;
        while (i < string.length() && CScan.isCSpace(string.charAt(i))) {
            i++;
        }
        CScan.Num num = CScan.strtod(string, i);
        if (num != null) {
            int nc = num.end;
            while (nc < string.length() && CScan.isCSpace(string.charAt(nc))) {
                nc++;
            }
            value[0] = num.value;
            return nc;
        }
        int nc = badToken(string);
        if (nc > 0) {
            value[0] = AST__BAD;
        }
        return nc;
    }

    /**
     * The characters C's {@code sscanf(s, " < %*1[Bb] %*1[Aa] %*1[Dd] > %n", &nc)} reads: the
     * {@code <bad>} token with white space allowed before each of its characters and counted
     * after it, or zero if it is not there.
     */
    static int badToken(String s) {
        String[] sets = {"<", "Bb", "Aa", "Dd", ">"};
        int i = 0;
        for (String set : sets) {
            while (i < s.length() && CScan.isCSpace(s.charAt(i))) {
                i++;
            }
            if (i >= s.length() || set.indexOf(s.charAt(i)) < 0) {
                return 0;
            }
            i++;
        }
        while (i < s.length() && CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * The increment from {@code v1} to {@code v2} on this axis (C's {@code AxisDistance}): their
     * difference, or {@link #AST__BAD} if either is bad ({@link Mapping#isBad}).
     */
    double axisDistance(double v1, double v2) {
        return Mapping.isBad(v1) || Mapping.isBad(v2) ? AST__BAD : v2 - v1;
    }

    /**
     * The value {@code dist} from {@code v1} on this axis (C's {@code AxisOffset}): their sum, or
     * {@link #AST__BAD} if either is bad ({@link Mapping#isBad}).
     */
    double axisOffset(double v1, double dist) {
        return Mapping.isBad(v1) || Mapping.isBad(dist) ? AST__BAD : v1 + dist;
    }

    /**
     * Normalises a value on this axis into the range used for display (C's {@code AxisNorm}). A
     * plain axis leaves it unchanged.
     */
    double axisNorm(double value) {
        return value;
    }

    /**
     * Normalises the values of an array (C's {@code AxisNormValues}): with {@code oper} 0 each as
     * {@link #axisNorm} does, with 1 into whichever range gives them the smallest spread. A plain
     * axis leaves them unchanged.
     */
    void axisNormValues(int oper, double[] values) {
        // A Cartesian axis has no range to choose between.
    }

    /**
     * Whether {@code val} lies between {@code lo} and {@code hi} on this axis, the limits
     * included when {@code closed} (C's {@code AxisIn}).
     */
    boolean axisIn(double lo, double hi, double val, boolean closed) {
        if (closed) {
            return lo <= val && val <= hi;
        }
        return lo < val && val < hi;
    }

    /**
     * Copies the attributes set on this Axis, the template, to {@code result} (C's {@code
     * AxisOverlay}): Digits, Direction, Label, Symbol, Unit and Format. Top and Bottom are not
     * copied. The Format is the Axis class's own, copied to the Axis class's own Format of {@code
     * result}, so that a Format whose syntax a subclass extends is not given to an Axis that
     * cannot read it.
     */
    void axisOverlay(Axis result) {
        if (testAxisDigits()) {
            result.setAxisDigits(getAxisDigits());
        }
        if (testAxisDirection()) {
            result.setAxisDirection(getAxisDirection());
        }
        if (testAxisLabel()) {
            result.setAxisLabel(getAxisLabel());
        }
        if (testAxisSymbol()) {
            result.setAxisSymbol(getAxisSymbol());
        }
        if (testAxisUnit()) {
            result.setAxisUnit(getAxisUnit());
        }
        if (format != null) {
            result.format = format;
        }
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /** The Label: set, or {@code "Coordinate axis"}. */
    String getAxisLabel() {
        return label != null ? label : "Coordinate axis";
    }

    void setAxisLabel(String value) {
        label = value;
    }

    boolean testAxisLabel() {
        return label != null;
    }

    void clearAxisLabel() {
        label = null;
    }

    /** The Symbol: set, or {@code "x"}. */
    String getAxisSymbol() {
        return symbol != null ? symbol : "x";
    }

    void setAxisSymbol(String value) {
        symbol = value;
    }

    boolean testAxisSymbol() {
        return symbol != null;
    }

    void clearAxisSymbol() {
        symbol = null;
    }

    /** The Unit: set, or blank. */
    String getAxisUnit() {
        return unit != null ? unit : "";
    }

    void setAxisUnit(String value) {
        unit = value;
    }

    boolean testAxisUnit() {
        return unit != null;
    }

    void clearAxisUnit() {
        unit = null;
    }

    /** The Format: set, or the default {@code %1.<Digits>G}. */
    String getAxisFormat() {
        return ownAxisFormat();
    }

    void setAxisFormat(String value) {
        format = value;
    }

    boolean testAxisFormat() {
        return format != null;
    }

    void clearAxisFormat() {
        format = null;
    }

    /**
     * The Axis class's own Format, set or default, as C's private {@code GetAxisFormat} (axis.c)
     * gives it. A subclass that keeps its Format elsewhere overrides {@link #getAxisFormat} but
     * not this.
     */
    private String ownAxisFormat() {
        return format != null ? format : getDefaultFormat();
    }

    /** The Format used when none is set (C's {@code GetDefaultFormat}): {@code %1.<Digits>G}. */
    private String getDefaultFormat() {
        return "%1." + getAxisDigits() + "G";
    }

    /** The Digits: set, or 7. */
    int getAxisDigits() {
        return digits != UNSET_INT ? digits : 7;
    }

    /** Sets Digits, which is at least 1. */
    void setAxisDigits(int value) {
        digits = value > 1 ? value : 1;
    }

    boolean testAxisDigits() {
        return digits != UNSET_INT;
    }

    void clearAxisDigits() {
        digits = UNSET_INT;
    }

    /** The Direction: set, or true (the conventional direction). */
    boolean getAxisDirection() {
        return direction == UNSET_INT || direction != 0;
    }

    void setAxisDirection(boolean value) {
        direction = value ? 1 : 0;
    }

    boolean testAxisDirection() {
        return direction != UNSET_INT;
    }

    void clearAxisDirection() {
        direction = UNSET_INT;
    }

    /** The Top: set, or the largest double. Setting it to the bad value leaves it unset. */
    double getAxisTop() {
        return top != AST__BAD ? top : Double.MAX_VALUE;
    }

    void setAxisTop(double value) {
        top = value;
    }

    boolean testAxisTop() {
        return top != AST__BAD;
    }

    void clearAxisTop() {
        top = AST__BAD;
    }

    /** The Bottom: set, or the most negative double. Setting it to the bad value leaves it unset. */
    double getAxisBottom() {
        return bottom != AST__BAD ? bottom : -Double.MAX_VALUE;
    }

    void setAxisBottom(double value) {
        bottom = value;
    }

    boolean testAxisBottom() {
        return bottom != AST__BAD;
    }

    void clearAxisBottom() {
        bottom = AST__BAD;
    }

    /**
     * The NormUnit: the Unit in the standard form C's {@code astNormUnit} gives, or the Unit
     * itself if it is not a units expression (C's {@code GetAxisNormUnit}).
     *
     * @throws AstException with status {@code AST__FMTER} if the result exceeds the 127
     *     characters C allows
     */
    String getAxisNormUnit() {
        String result = Units.normUnit(getAxisUnit());
        if (result.length() > 127) {
            throw Ast.err(
                    AstException.AST__FMTER,
                    "astGetAxisNormUnit(" + astClass() + "): Internal buffer overflow while normalising the units "
                            + "string '" + result + "' - result exceeds 127 characters.");
        }
        return result;
    }

    /** Whether the NormUnit comes from a set value: whether the Unit is set. */
    boolean testAxisNormUnit() {
        return testAxisUnit();
    }

    /** The InternalUnit, the unit of the values the axis holds: for a plain axis, its Unit. */
    String getAxisInternalUnit() {
        return getAxisUnit();
    }

    /** Whether the InternalUnit comes from a set value: whether the Unit is set. */
    boolean testAxisInternalUnit() {
        return testAxisUnit();
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    private static boolean isReadOnly(String attrib) {
        return "normunit".equals(attrib) || "internalunit".equals(attrib);
    }

    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "digits":
                return Integer.toString(getAxisDigits());
            case "direction":
                return getAxisDirection() ? "1" : "0";
            case "top":
                return CFormat.format("%.17g", getAxisTop());
            case "bottom":
                return CFormat.format("%.17g", getAxisBottom());
            case "format":
                return getAxisFormat();
            case "label":
                return getAxisLabel();
            case "symbol":
                return getAxisSymbol();
            case "unit":
                return getAxisUnit();
            case "normunit":
                return getAxisNormUnit();
            case "internalunit":
                return getAxisInternalUnit();
            default:
                return super.getAttrib(attrib);
        }
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        Double dval = CScan.scanDouble(value);
        if ("digits".equals(attrib) && ival != null) {
            setAxisDigits(ival);
        } else if ("direction".equals(attrib) && ival != null) {
            setAxisDirection(ival != 0);
        } else if ("top".equals(attrib) && dval != null) {
            setAxisTop(dval);
        } else if ("bottom".equals(attrib) && dval != null) {
            setAxisBottom(dval);
        } else if ("format".equals(attrib)) {
            setAxisFormat(value);
        } else if ("label".equals(attrib)) {
            setAxisLabel(value);
        } else if ("symbol".equals(attrib)) {
            setAxisSymbol(value);
        } else if ("unit".equals(attrib)) {
            setAxisUnit(value);
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
            case "digits":
                return testAxisDigits();
            case "direction":
                return testAxisDirection();
            case "top":
                return testAxisTop();
            case "bottom":
                return testAxisBottom();
            case "format":
                return testAxisFormat();
            case "label":
                return testAxisLabel();
            case "symbol":
                return testAxisSymbol();
            case "unit":
                return testAxisUnit();
            case "internalunit":
                return testAxisInternalUnit();
            case "normunit":
                return testAxisNormUnit();
            default:
                return super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "digits":
                clearAxisDigits();
                break;
            case "direction":
                clearAxisDirection();
                break;
            case "format":
                clearAxisFormat();
                break;
            case "label":
                clearAxisLabel();
                break;
            case "top":
                clearAxisTop();
                break;
            case "bottom":
                clearAxisBottom();
                break;
            case "symbol":
                clearAxisSymbol();
                break;
            case "unit":
                clearAxisUnit();
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
}
