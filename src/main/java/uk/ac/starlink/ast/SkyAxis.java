package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.erfa.Erfa;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * An axis of a celestial coordinate system, whose values are angles in radians. A SkyAxis formats
 * its values as sexagesimal angles or times, such as {@code 12:34:56.7}, reads them in the same
 * forms, measures distances between them as angles, and normalises them into the range zero to
 * 2&pi; or, for an axis centred on zero, &minus;&pi; to &pi;.
 *
 * <p>Besides the attributes of an {@link Axis}, a SkyAxis has AsTime (whether values are
 * formatted as times rather than angles), IsLatitude (whether the axis is a latitude rather than
 * a longitude) and CentreZero (whether values are normalised into &minus;&pi; to &pi;). Its
 * Format, which C calls the "sky format", is held apart from the Axis class's Format: a Format of
 * letters such as {@code hms.2} or {@code bdms} selects the fields, separators and decimal places
 * of the sexagesimal form, while a Format starting with {@code %} formats values as plain numbers
 * of radians, with the Axis class's own Format.
 *
 * <p>Ported from C {@code skyaxis.c}, with Rust {@code frames/skyaxis.rs}. The Plot-only methods
 * of C's SkyAxis ({@code AxisAbbrev}, {@code AxisFields}, {@code AxisGap}) are not ported.
 */
public class SkyAxis extends Axis {

    /** Radians per hour: C's {@code palDtf2r( 1, 0, 0.0, ... )}, which is ERFA's arithmetic. */
    private static final double HR2RAD = (60.0 * (60.0 * 1.0 + 0.0) + 0.0) * Erfa.ERFA_DS2R;

    /** Radians per degree: C's {@code palDaf2r( 1, 0, 0.0, ... )}. */
    private static final double DEG2RAD = (60.0 * (60.0 * 1.0 + 0.0) + 0.0) * Erfa.ERFA_DAS2R;

    /** Pi: C's {@code palDaf2r( 180, 0, 0.0, ... )}. */
    private static final double PI = (60.0 * (60.0 * 180.0 + 0.0) + 0.0) * Erfa.ERFA_DAS2R;

    /** Half of {@link #PI}. */
    private static final double PIBY2 = 0.5 * PI;

    /**
     * The length of C's {@code DHmsFormat} result buffer ({@code AST__SKYAXIS_DHMSFORMAT_BUFF_LEN},
     * skyaxis.h), which limits the number of decimal places.
     */
    private static final int DHMSFORMAT_BUFF_LEN = 70;

    /** The decimal places {@link #dhmsUnit} shows before an ellipsis. */
    private static final int MAXDP = 6;

    /** The separators {@link #dhmsUnformat} recognises (C's {@code sep_list}). */
    private static final String SEP_LIST = " :hHdDmM'sS\"";

    /** Whether each separator says the value is an angle (1) or a time (2); 0 if it does not say. */
    private static final int[] ANGLE_OR_TIME_LIST = {0, 0, 2, 2, 1, 1, 0, 0, 1, 0, 0, 1};

    /** The field (1 to 3) each separator ends when it follows it; 0 if it does not say. */
    private static final int[] FIELD_ID_LIST = {0, 0, 1, 1, 1, 1, 2, 2, 2, 3, 3, 3};

    /** The nominal value, in degrees or hours, of a unit in each field. */
    private static final double[] FIELD_VALUE = {1.0, 1.0 / 60.0, 1.0 / 3600.0};

    /** The Format of this class (C's {@code skyformat}), or null if it is not set. */
    private String skyFormat;

    /** The AsTime attribute (0 or 1), or {@link #UNSET_INT}. */
    private int asTime = UNSET_INT;

    /** The IsLatitude attribute (0 or 1), or {@link #UNSET_INT}. */
    private int isLatitude = UNSET_INT;

    /** The CentreZero attribute (0 or 1), or {@link #UNSET_INT}. */
    private int centreZero = UNSET_INT;

    /** Creates a SkyAxis with no attributes set, as C's {@code astInitSkyAxis} does. */
    protected SkyAxis() {}

    /**
     * Loads a SkyAxis from its description, as C's {@code astLoadSkyAxis} does: the Axis class's
     * part, then the SkyAxis Format, IsLat, CnZer and AsTime, each unset if absent and each number
     * read as 0 or 1.
     *
     * @param node the description
     */
    SkyAxis(NativeNode node) {
        super(node);
        NativeNode part = node.section("SkyAxis");
        skyFormat = part.getStringIfSet("Format");
        isLatitude = flag(part.getIntIfSet("IsLat"));
        centreZero = flag(part.getIntIfSet("CnZer"));
        asTime = flag(part.getIntIfSet("AsTime"));
    }

    /** A flag read by a loader: unset if absent, otherwise 0 or 1 as C's setter makes it. */
    private static int flag(Long value) {
        if (value == null) {
            return UNSET_INT;
        }
        return value != 0 ? 1 : 0;
    }

    @Override
    String dumpComment() {
        return "Celestial coordinate axis";
    }

    /**
     * Writes the SkyAxis class's part of a description, as C's {@code Dump} (skyaxis.c) does,
     * after the Axis class's part: Format, IsLat, CnZer and AsTime, each active when set and
     * otherwise the default value.
     */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Axis", "Coordinate axis");
        node.writeString("Format", skyFormat != null, false, skyAxisFormat(), "Format specifier");
        boolean ival = getAxisIsLatitude();
        node.writeInt(
                "IsLat",
                testAxisIsLatitude(),
                false,
                ival ? 1 : 0,
                ival ? "Latitude axis (not longitude)" : "Longitude axis (not latitude)");
        ival = getAxisCentreZero();
        node.writeInt(
                "CnZer",
                testAxisCentreZero(),
                false,
                ival ? 1 : 0,
                ival ? "Display axis values in range -PI -> +PI" : "Display axis values in range 0 -> 2.PI");
        ival = getAxisAsTime();
        node.writeInt(
                "AsTime",
                testAxisAsTime(),
                false,
                ival ? 1 : 0,
                ival ? "Display values as times (not angles)" : "Display values as angles (not times)");
    }

    @Override
    boolean isSkyAxis() {
        return true;
    }

    // ------------------------------------------------------------------
    // SkyAxis attributes
    // ------------------------------------------------------------------

    /**
     * AsTime: whether values are formatted as times rather than angles. Set, or true only for an
     * axis whose IsLatitude is set to false.
     */
    boolean getAxisAsTime() {
        if (asTime != UNSET_INT) {
            return asTime != 0;
        }
        return testAxisIsLatitude() && !getAxisIsLatitude();
    }

    void setAxisAsTime(boolean value) {
        asTime = value ? 1 : 0;
    }

    boolean testAxisAsTime() {
        return asTime != UNSET_INT;
    }

    void clearAxisAsTime() {
        asTime = UNSET_INT;
    }

    /** IsLatitude: whether the axis is a latitude. Set, or false. */
    boolean getAxisIsLatitude() {
        return isLatitude != UNSET_INT && isLatitude != 0;
    }

    void setAxisIsLatitude(boolean value) {
        isLatitude = value ? 1 : 0;
    }

    boolean testAxisIsLatitude() {
        return isLatitude != UNSET_INT;
    }

    void clearAxisIsLatitude() {
        isLatitude = UNSET_INT;
    }

    /**
     * CentreZero: whether values are normalised into -pi to pi rather than 0 to 2 pi. Set, or the
     * IsLatitude value.
     */
    boolean getAxisCentreZero() {
        if (centreZero != UNSET_INT) {
            return centreZero != 0;
        }
        return getAxisIsLatitude();
    }

    void setAxisCentreZero(boolean value) {
        centreZero = value ? 1 : 0;
    }

    boolean testAxisCentreZero() {
        return centreZero != UNSET_INT;
    }

    void clearAxisCentreZero() {
        centreZero = UNSET_INT;
    }

    // ------------------------------------------------------------------
    // Axis attributes with SkyAxis defaults
    // ------------------------------------------------------------------

    /**
     * The SkyAxis Format, set or default, as C's private {@code GetAxisFormat} (skyaxis.c) gives
     * it. The default depends on Digits and AsTime: for a time {@code h} (Digits up to 2), {@code
     * hm} (3, 4), {@code hms} (5, 6) or {@code hms.<Digits-6>}; for an angle {@code d} (up to 3),
     * {@code dm} (4, 5), {@code dms} (6, 7) or {@code dms.<Digits-7>}.
     */
    private String skyAxisFormat() {
        if (skyFormat != null) {
            return skyFormat;
        }
        int digits = getAxisDigits();
        if (getAxisAsTime()) {
            if (digits <= 2) {
                return "h";
            } else if (digits <= 4) {
                return "hm";
            } else if (digits <= 6) {
                return "hms";
            } else {
                return "hms." + (digits - 6);
            }
        } else {
            if (digits <= 3) {
                return "d";
            } else if (digits <= 5) {
                return "dm";
            } else if (digits <= 7) {
                return "dms";
            } else {
                return "dms." + (digits - 7);
            }
        }
    }

    /** The Format: the SkyAxis Format, set or default, not the Axis class's. */
    @Override
    String getAxisFormat() {
        return skyAxisFormat();
    }

    @Override
    void setAxisFormat(String value) {
        skyFormat = value;
    }

    @Override
    boolean testAxisFormat() {
        return skyFormat != null;
    }

    @Override
    void clearAxisFormat() {
        skyFormat = null;
    }

    /**
     * The Label: set, or a description from AsTime and IsLatitude, such as {@code "Sky
     * latitude"} or {@code "Angle on sky expressed as time"}.
     */
    @Override
    String getAxisLabel() {
        if (testAxisLabel()) {
            return super.getAxisLabel();
        }
        boolean time = getAxisAsTime();
        if (!testAxisIsLatitude()) {
            return time ? "Angle on sky expressed as time" : "Angle on sky";
        } else if (getAxisIsLatitude()) {
            return time ? "Sky latitude expressed as time" : "Sky latitude";
        } else {
            return time ? "Sky longitude expressed as time" : "Sky longitude";
        }
    }

    /**
     * The Symbol: set, or {@code "delta"} for a latitude and {@code "alpha"} for a longitude; if
     * IsLatitude is not set, an axis formatted as a time is taken to be a longitude.
     */
    @Override
    String getAxisSymbol() {
        if (testAxisSymbol()) {
            return super.getAxisSymbol();
        } else if (testAxisIsLatitude()) {
            return getAxisIsLatitude() ? "delta" : "alpha";
        } else {
            return getAxisAsTime() ? "alpha" : "delta";
        }
    }

    /**
     * The Unit: set, or {@code "rad"} for a {@code %} Format, otherwise the description of the
     * formatted values the Format gives ({@link #dhmsUnit}).
     */
    @Override
    String getAxisUnit() {
        if (testAxisUnit()) {
            return super.getAxisUnit();
        }
        String fmt = skyAxisFormat();
        if (fmt.startsWith("%")) {
            return "rad";
        }
        return dhmsUnit(fmt, getAxisDigits(), null);
    }

    /**
     * The NormUnit. For a Format other than {@code %}, a Unit that describes the axis values, such
     * as {@code "hh:mm:ss"}, is returned unchanged when the Unit is the default or is set to that
     * same description; any other Unit is a units expression, normalised as the Axis class does.
     */
    @Override
    String getAxisNormUnit() {
        String fmt = skyAxisFormat();
        if (!fmt.startsWith("%")) {
            boolean[] descr = {false};
            String descunit = dhmsUnit(fmt, getAxisDigits(), descr);
            if (descr[0] && (!testAxisUnit() || super.getAxisUnit().equals(descunit))) {
                return descunit;
            }
        }
        return super.getAxisNormUnit();
    }

    /** The InternalUnit: always {@code "rad"}. */
    @Override
    String getAxisInternalUnit() {
        return "rad";
    }

    /** Whether the InternalUnit is known: always true. */
    @Override
    boolean testAxisInternalUnit() {
        return true;
    }

    /** The Direction: set, or false (reversed) for an axis formatted as a time. */
    @Override
    boolean getAxisDirection() {
        if (testAxisDirection()) {
            return super.getAxisDirection();
        }
        return !getAxisAsTime();
    }

    /** The Top: set, or pi/2 for a latitude axis and the largest double otherwise. */
    @Override
    double getAxisTop() {
        if (testAxisTop()) {
            return super.getAxisTop();
        }
        return getAxisIsLatitude() ? PIBY2 : Double.MAX_VALUE;
    }

    /** The Bottom: set, or -pi/2 for a latitude axis and the most negative double otherwise. */
    @Override
    double getAxisBottom() {
        if (testAxisBottom()) {
            return super.getAxisBottom();
        }
        return getAxisIsLatitude() ? -PIBY2 : -Double.MAX_VALUE;
    }

    // ------------------------------------------------------------------
    // Per-class methods
    // ------------------------------------------------------------------

    /**
     * Formats a value (C's {@code AxisFormat}): with {@link #dhmsFormat} and the SkyAxis Format,
     * or, for a Format starting with {@code %}, as the Axis class formats it, with the Axis
     * class's own Format rather than the SkyAxis one.
     */
    @Override
    String axisFormat(double value) {
        String fmt = skyAxisFormat();
        if (fmt.startsWith("%")) {
            return super.axisFormat(value);
        }
        return dhmsFormat(fmt, getAxisDigits(), value);
    }

    /**
     * Reads a value (C's {@code AxisUnformat}): with {@link #dhmsUnformat} and the SkyAxis
     * Format, or, for a Format starting with {@code %}, as the Axis class reads it.
     *
     * @throws AstException with status {@code AST__UNFER} if a minutes or seconds field is 60 or
     *     more
     */
    @Override
    int axisUnformat(String string, double[] value) {
        String fmt = skyAxisFormat();
        if (fmt.startsWith("%")) {
            return super.axisUnformat(string, value);
        }
        return dhmsUnformat(string, fmt, getAxisDigits(), value);
    }

    /**
     * The increment from {@code v1} to {@code v2} (C's {@code AxisDistance}): their angular
     * difference normalised into -pi to pi, or {@link #AST__BAD} if either is bad ({@link
     * Mapping#isBad}).
     */
    @Override
    double axisDistance(double v1, double v2) {
        if (Mapping.isBad(v1) || Mapping.isBad(v2)) {
            return AST__BAD;
        }
        return Pal.drange(v2 - v1);
    }

    /**
     * The value {@code dist} from {@code v1} (C's {@code AxisOffset}): their sum, normalised as
     * {@link #axisNorm} does, or {@link #AST__BAD} if either is bad ({@link Mapping#isBad}).
     */
    @Override
    double axisOffset(double v1, double dist) {
        if (Mapping.isBad(v1) || Mapping.isBad(dist)) {
            return AST__BAD;
        }
        return normValue(v1 + dist);
    }

    /**
     * Normalises a value (C's {@code AxisNorm}): into -pi to pi if CentreZero is true, otherwise
     * into 0 to 2 pi. {@link #AST__BAD} is returned unchanged.
     */
    @Override
    double axisNorm(double value) {
        return normValue(value);
    }

    /** C's {@code AxisNorm} (skyaxis.c), which C's {@code AxisOffset} calls directly. */
    private double normValue(double value) {
        if (value == AST__BAD) {
            return value;
        }
        return getAxisCentreZero() ? Pal.drange(value) : Pal.dranrm(value);
    }

    /** Whether a value is normalised by {@link #axisNormValues} (C's {@code VAL_OK}). */
    private static boolean valOk(double v) {
        return v != AST__BAD && Math.abs(v) < 1.0E6;
    }

    /** C's {@code astMAX} macro. */
    private static double cmax(double a, double b) {
        return a > b ? a : b;
    }

    /** C's {@code astMIN} macro. */
    private static double cmin(double a, double b) {
        return a < b ? a : b;
    }

    /**
     * Normalises the values of an array (C's {@code AxisNormValues}). With {@code oper} 0, each as
     * {@link #axisNorm} does. With 1, a latitude axis is normalised into -pi to pi, and a
     * longitude axis into whichever of 0 to 2 pi and -pi to pi gives the values the smaller range.
     * Bad values, and values outside -1E6 to 1E6, are left unchanged.
     *
     * @throws AstException with status {@code AST__INTER} if {@code oper} is not 0 or 1
     */
    @Override
    void axisNormValues(int oper, double[] values) {
        if (oper == 0) {
            boolean centrezero = getAxisCentreZero();
            for (int i = 0; i < values.length; i++) {
                if (valOk(values[i])) {
                    values[i] = centrezero ? Pal.drange(values[i]) : Pal.dranrm(values[i]);
                }
            }
        } else if (oper == 1) {
            if (getAxisIsLatitude()) {
                for (int i = 0; i < values.length; i++) {
                    if (valOk(values[i])) {
                        values[i] = Pal.drange(values[i]);
                    }
                }
            } else {
                normLongitudes(values);
            }
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astAxisNormValues: Invalid oper value " + oper + " supplied (internal AST programming error).");
        }
    }

    /**
     * The longitude case of {@link #axisNormValues} with {@code oper} 1: the values are brought
     * into -pi/2 to 2 pi, and then normalised into whichever of 0 to 2 pi and -pi to pi gives the
     * smaller range.
     */
    private static void normLongitudes(double[] values) {
        double twopi = 2 * Math.PI;
        double mx1 = -Double.MAX_VALUE;
        double mn1 = Double.MAX_VALUE;
        double mx2 = -Double.MAX_VALUE;
        double mn2 = Double.MAX_VALUE;
        double mx3 = -Double.MAX_VALUE;
        double mn3 = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (valOk(values[i])) {
                while (values[i] > twopi) {
                    values[i] -= twopi;
                }
                while (values[i] < -Math.PI / 2) {
                    values[i] += twopi;
                }
                double v = values[i];
                if (v > Math.PI) {
                    mx3 = cmax(mx3, v);
                    mn3 = cmin(mn3, v);
                } else if (v > 0) {
                    mx2 = cmax(mx2, v);
                    mn2 = cmin(mn2, v);
                } else {
                    mx1 = cmax(mx1, v);
                    mn1 = cmin(mn1, v);
                }
            }
        }

        // The range if normalised into 0 to 2 pi.
        double hi;
        double lo;
        if (mx1 != -Double.MAX_VALUE) {
            hi = cmax(mx2, cmax(mx1 + twopi, mx3));
            lo = cmin(mn2, cmin(mn1 + twopi, mn3));
        } else {
            hi = cmax(mx2, mx3);
            lo = cmin(mn2, mn3);
        }
        double range1 = hi - lo;

        // The range if normalised into -pi to pi. As in C, the test compares mn3, which starts at
        // the largest double, with the most negative double, and so is always true.
        if (mn3 != -Double.MAX_VALUE) {
            hi = cmax(mx2, cmax(mx3 - twopi, mx1));
            lo = cmin(mn2, cmin(mn3 - twopi, mn1));
        } else {
            hi = cmax(mx2, mx1);
            lo = cmin(mn2, mn1);
        }
        double range2 = hi - lo;

        boolean centred = range2 < range1;
        for (int i = 0; i < values.length; i++) {
            if (valOk(values[i])) {
                values[i] = centred ? Pal.drange(values[i]) : Pal.dranrm(values[i]);
            }
        }
    }

    /**
     * Whether {@code val} lies between {@code lo} and {@code hi}, the limits included when {@code
     * closed} (C's {@code AxisIn}). The limits are not cyclic, but whole turns are added to or
     * subtracted from {@code val} to bring it into the interval if possible.
     */
    @Override
    boolean axisIn(double lo, double hi, double val, boolean closed) {
        double v = val;
        if (closed) {
            while (v > hi) {
                v -= 2 * PI;
            }
            while (v < lo) {
                v += 2 * PI;
            }
            return v <= hi;
        }
        while (v >= hi) {
            v -= 2 * PI;
        }
        while (v <= lo) {
            v += 2 * PI;
        }
        return v < hi;
    }

    /**
     * Copies the attributes set on this SkyAxis, the template, to {@code result} (C's {@code
     * AxisOverlay}): those of the Axis class, then, if {@code result} is a SkyAxis, the SkyAxis
     * Format, AsTime, IsLatitude and CentreZero.
     */
    @Override
    void axisOverlay(Axis result) {
        super.axisOverlay(result);
        if (result instanceof SkyAxis) {
            SkyAxis sky = (SkyAxis) result;
            if (skyFormat != null) {
                sky.skyFormat = skyFormat;
            }
            if (testAxisAsTime()) {
                sky.setAxisAsTime(getAxisAsTime());
            }
            if (testAxisIsLatitude()) {
                sky.setAxisIsLatitude(getAxisIsLatitude());
            }
            if (testAxisCentreZero()) {
                sky.setAxisCentreZero(getAxisCentreZero());
            }
        }
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    @Override
    String getAttrib(String attrib) {
        switch (attrib) {
            case "astime":
                return getAxisAsTime() ? "1" : "0";
            case "islatitude":
                return getAxisIsLatitude() ? "1" : "0";
            case "centrezero":
                return getAxisCentreZero() ? "1" : "0";
            default:
                return super.getAttrib(attrib);
        }
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        if ("astime".equals(attrib) && ival != null) {
            setAxisAsTime(ival != 0);
        } else if ("islatitude".equals(attrib) && ival != null) {
            setAxisIsLatitude(ival != 0);
        } else if ("centrezero".equals(attrib) && ival != null) {
            setAxisCentreZero(ival != 0);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        switch (attrib) {
            case "astime":
                return testAxisAsTime();
            case "islatitude":
                return testAxisIsLatitude();
            case "centrezero":
                return testAxisCentreZero();
            default:
                return super.testAttrib(attrib);
        }
    }

    @Override
    void clearAttrib(String attrib) {
        switch (attrib) {
            case "astime":
                clearAxisAsTime();
                break;
            case "islatitude":
                clearAxisIsLatitude();
                break;
            case "centrezero":
                clearAxisCentreZero();
                break;
            default:
                super.clearAttrib(attrib);
        }
    }

    // ------------------------------------------------------------------
    // Sexagesimal Formats
    // ------------------------------------------------------------------

    /** A SkyAxis Format, parsed: the outputs of C's {@code ParseDHmsFormat}. */
    // MODERN-JAVA(16): record
    static final class DHmsFields {
        /** The field separator: {@code ':'}, {@code ' '}, or {@code 'l'} for letters. */
        char sep = ':';

        /** Whether a positive value has a {@code +} in front. */
        boolean plus;

        /** Whether the leading field is padded with zeros. */
        boolean leadZero;

        /** Whether the value is formatted as a time (hours) rather than an angle (degrees). */
        boolean asTime;

        /** Whether a degrees or hours field is present. */
        boolean dh;

        /** Whether a minutes field is present. */
        boolean min;

        /** Whether a seconds field is present. */
        boolean sec;

        /** The number of decimal places after the final field. */
        int ndp;
    }

    /**
     * Parses a SkyAxis Format (C's {@code ParseDHmsFormat}). Each character of the Format counts
     * for itself: {@code +} a plus sign, {@code z} leading zeros, {@code i}, {@code b}, {@code l}
     * or {@code g} the separator (colons, blanks, letters, and letters again since graphical
     * escape sequences are not used), {@code t} a time unless a {@code d} says otherwise, {@code
     * h} or {@code d} a field of hours or degrees, {@code m} and {@code s} minutes and seconds, in
     * either case. The integer after the last {@code .} is the number of decimal places; {@code
     * .*} gives as many as leave {@code digs} digits of precision.
     */
    static DHmsFields parseDHmsFormat(String fmt, int digs) {
        DHmsFields f = new DHmsFields();
        int asTime = -1;
        int decpos = -1;
        int len = fmt.length();
        for (int i = 0; i < len; i++) {
            switch (fmt.charAt(i)) {
                case '+':
                    f.plus = true;
                    break;
                case 'Z':
                case 'z':
                    f.leadZero = true;
                    break;
                case 'I':
                case 'i':
                    f.sep = ':';
                    break;
                case 'B':
                case 'b':
                    f.sep = ' ';
                    break;
                case 'L':
                case 'l':
                case 'G':
                case 'g':
                    f.sep = 'l';
                    break;
                case 'T':
                case 't':
                    if (asTime == -1) {
                        asTime = 1;
                    }
                    break;
                case 'H':
                case 'h':
                    f.dh = true;
                    asTime = 1;
                    break;
                case 'D':
                case 'd':
                    f.dh = true;
                    asTime = 0;
                    break;
                case 'M':
                case 'm':
                    f.min = true;
                    break;
                case 'S':
                case 's':
                    f.sec = true;
                    break;
                case '.':
                    decpos = i;
                    break;
                default:
                    // Other characters are ignored.
                    break;
            }
        }
        f.asTime = asTime == 1;
        if (!f.min && !f.sec) {
            f.dh = true;
        }
        if (f.dh && !f.min) {
            f.sec = false;
        }
        if (decpos >= 0 && decpos < len - 1) {
            Integer ndpval = scanLeadingInt(fmt, decpos + 1);
            if (ndpval != null) {
                if (ndpval >= 0) {
                    f.ndp = ndpval;
                }
            } else if (fmt.charAt(decpos + 1) == '*') {
                int ndp;
                if (f.asTime) {
                    ndp = digs > 2 ? digs : 2;
                    if (f.dh) {
                        ndp -= 2;
                    }
                } else {
                    ndp = digs > 3 ? digs : 3;
                    if (f.dh) {
                        ndp -= 3;
                    }
                }
                if (f.min) {
                    ndp -= 2;
                }
                if (f.sec) {
                    ndp -= 2;
                }
                f.ndp = ndp < 0 ? 0 : ndp;
            }
        }
        return f;
    }

    /**
     * The integer C's {@code sscanf( s + from, "%d", &n )} reads: optional white space, an
     * optional sign and at least one digit, or null if there is none. A value beyond the range of
     * {@code long} is clamped to it and the result cast to {@code int}, as the C libraries store
     * an out-of-range {@code %d}.
     */
    private static Integer scanLeadingInt(String s, int from) {
        int i = from;
        while (i < s.length() && CScan.isCSpace(s.charAt(i))) {
            i++;
        }
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int start = i;
        long v = 0;
        boolean overflow = false;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            int d = s.charAt(i) - '0';
            if (v > (Long.MAX_VALUE - d) / 10) {
                overflow = true;
            } else {
                v = v * 10 + d;
            }
            i++;
        }
        if (i == start) {
            return null;
        }
        if (overflow) {
            v = neg ? Long.MIN_VALUE : Long.MAX_VALUE;
        } else if (neg) {
            v = -v;
        }
        return (int) v;
    }

    /**
     * The width-{@code width} zero-padded whole number C's {@code sprintf( "%0*.0f", width, v )}
     * writes.
     */
    private static String zeroPadded(int width, double v) {
        return CFormat.format("%0" + width + ".0f", v);
    }

    /**
     * Formats a value in radians with a SkyAxis Format (C's {@code DHmsFormat}), {@code digs}
     * being the Digits used by a {@code .*} precision. The value is split into the fields the
     * Format selects, the last rounded to its decimal places so that the text reads back as the
     * value, with any carry taken into the fields before it; the leading field is not reduced into
     * any range. A bad value ({@link Mapping#isBad}) gives {@code <bad>}.
     */
    static String dhmsFormat(String fmt, int digs, double value) {
        if (Mapping.isBad(value)) {
            return "<bad>";
        }
        DHmsFields f = parseDHmsFormat(fmt, digs);
        int ndp = f.ndp > DHMSFORMAT_BUFF_LEN - 11 ? DHMSFORMAT_BUFF_LEN - 11 : f.ndp;

        // A negative zero is formatted as zero.
        double val = value == 0.0 ? 0.0 : value;
        boolean positive = val >= 0.0;
        double absvalue = positive ? val : -val;
        double unit = f.asTime ? HR2RAD : DEG2RAD;

        // Split into fields, each the whole part of what remains (C's modf).
        double fract = absvalue / unit;
        double idh = 0.0;
        if (f.dh) {
            idh = Math.floor(fract);
            fract -= idh;
        }
        double imin = 0.0;
        if (f.min) {
            double v = fract * 60.0;
            imin = Math.floor(v);
            fract = v - imin;
        }
        double isec = 0.0;
        if (f.sec) {
            if (!f.min) {
                fract *= 60.0;
            }
            double v = fract * 60.0;
            isec = Math.floor(v);
            fract = v - isec;
        }

        // Truncate the final field to ndp places, then round up if the value is at least the one
        // whose text ends in 5 at the next place, compared in radians, so that the text of a value
        // formatted and read back is unchanged.
        double shift = Math.pow(10.0, ndp);
        double ifract = Math.floor(fract * shift);
        double test = (0.5 + ifract) / shift;
        if (f.sec) {
            test = (isec + test) / 60.0;
        }
        if (f.min) {
            test = (imin + test) / 60.0;
        } else if (f.sec) {
            test /= 60.0;
        }
        if (f.dh) {
            test += idh;
        }
        test *= unit;
        if (absvalue >= test) {
            ifract += 1.0;
        }

        // Carry a fraction rounded up to one into the fields that are present.
        if (ifract / shift >= 1.0) {
            ifract = 0.0;
            if (f.sec) {
                isec += 1.0;
                if (Math.floor(isec + 0.5) > 59.5 && f.min) {
                    isec = 0.0;
                    imin += 1.0;
                    if (Math.floor(imin + 0.5) > 59.5 && f.dh) {
                        imin = 0.0;
                        idh += 1.0;
                    }
                }
            } else if (f.min) {
                imin += 1.0;
                if (Math.floor(imin + 0.5) > 59.5 && f.dh) {
                    imin = 0.0;
                    idh += 1.0;
                }
            } else {
                idh += 1.0;
            }
        }

        // A value that is zero in every field has no sign.
        if (!positive
                && (!f.dh || Math.floor(idh + 0.5) < 0.5)
                && (!f.min || Math.floor(imin + 0.5) < 0.5)
                && (!f.sec || Math.floor(isec + 0.5) < 0.5)
                && Math.floor(ifract + 0.5) < 0.5) {
            positive = true;
        }

        boolean letters = f.sep == 'l';
        StringBuilder out = new StringBuilder();
        if (!positive) {
            out.append('-');
        } else if (f.plus) {
            out.append('+');
        }
        if (f.dh) {
            out.append(zeroPadded(f.leadZero ? (f.asTime ? 2 : 3) : 1, idh));
            if ((f.min || f.sec) && letters) {
                out.append(f.asTime ? 'h' : 'd');
            }
        }
        if (f.min) {
            if (!letters && f.dh) {
                out.append(f.sep);
            }
            out.append(zeroPadded(f.dh || f.leadZero ? 2 : 1, imin));
            if (f.sec && letters) {
                out.append('m');
            }
        }
        if (f.sec) {
            if (!letters && (f.dh || f.min)) {
                out.append(f.sep);
            }
            out.append(zeroPadded(f.dh || f.min || f.leadZero ? 2 : 1, isec));
        }
        if (ndp > 0) {
            out.append('.').append(zeroPadded(ndp, ifract));
        }
        if (letters) {
            out.append(f.sec ? 's' : (f.min ? 'm' : (f.asTime ? 'h' : 'd')));
        }
        return out.toString();
    }

    /**
     * The Unit a SkyAxis Format implies for the formatted values (C's {@code DHmsUnit}, with its
     * {@code output} flag set, as every caller sets it): blank for letter separators; {@code
     * "degrees"}, {@code "arcminutes"}, {@code "seconds of time"} and the like for a single field;
     * otherwise a picture such as {@code "hh:mm:ss.sss"}, with an ellipsis after six decimal
     * places.
     *
     * @param descr if not null, receives in its first element whether the result describes the
     *     axis values (a picture, or minutes or seconds of time) rather than naming a unit
     */
    static String dhmsUnit(String fmt, int digs, boolean[] descr) {
        if (descr != null) {
            descr[0] = false;
        }
        DHmsFields f = parseDHmsFormat(fmt, digs);
        if (f.sep == 'l') {
            return "";
        } else if (f.dh && !f.min && !f.sec) {
            return f.asTime ? "hours" : "degrees";
        } else if (!f.dh && f.min && !f.sec) {
            if (descr != null && f.asTime) {
                descr[0] = true;
            }
            return f.asTime ? "minutes of time" : "arcminutes";
        } else if (!f.dh && !f.min && f.sec) {
            if (descr != null && f.asTime) {
                descr[0] = true;
            }
            return f.asTime ? "seconds of time" : "arcseconds";
        }
        if (descr != null) {
            descr[0] = true;
        }
        StringBuilder out = new StringBuilder();
        char dpchar = 'd';
        if (f.dh) {
            out.append(f.asTime ? "hh" : "ddd");
            dpchar = f.asTime ? 'h' : 'd';
        }
        if (f.min) {
            if (f.dh) {
                out.append(f.sep);
            }
            out.append("mm");
            dpchar = 'm';
        }
        if (f.sec) {
            if (f.dh || f.min) {
                out.append(f.sep);
            }
            out.append("ss");
            dpchar = 's';
        }
        if (f.ndp > 0) {
            out.append('.');
            for (int dp = 0; dp < f.ndp; dp++) {
                if (dp < MAXDP) {
                    out.append(dpchar);
                } else {
                    // After MAXDP places the last three become an ellipsis.
                    int pos = out.length();
                    out.replace(pos - 3, pos, "...");
                    break;
                }
            }
        }
        return out.toString();
    }

    /** The length of the run of ASCII digits starting at {@code from} (C's {@code %*[0123456789]}). */
    private static int digitRun(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i - from;
    }

    /** The character at {@code i}, or C's terminating null after the end. */
    private static char charAt(String s, int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    /**
     * The characters of an exponent ({@code E} or {@code e}, an optional sign and digits) starting
     * at {@code from}, or zero if there is none.
     */
    private static int exponentLength(String s, int from) {
        char c = charAt(s, from);
        if (c != 'E' && c != 'e') {
            return 0;
        }
        char sign = charAt(s, from + 1);
        int start = sign == '+' || sign == '-' ? 2 : 1;
        int digs = digitRun(s, from + start);
        return digs > 0 ? start + digs : 0;
    }

    /**
     * Reads a sky coordinate from the start of {@code string}, as C's {@code AxisUnformat}
     * (skyaxis.c) does for a SkyAxis whose Format is {@code fmt}, not starting with {@code %}, and
     * whose Digits is {@code digs}.
     *
     * <p>Up to three fields are read, each a number, after an optional sign. A field with a
     * decimal point or exponent is the last. Fields are separated by white space or colons, or
     * followed by letters ({@code h}, {@code d}, {@code m}, {@code s}) or quotes ({@code '} for
     * minutes of arc, {@code "} for seconds) that say which field each is and whether the value is
     * an angle or a time; fields the separators do not identify are numbered from the fields the
     * Format says are expected. The string {@code <bad>}, in any case and with white space
     * anywhere, gives {@link #AST__BAD}.
     *
     * @param value receives the value in radians in its first element if anything is read
     * @return the number of characters read, including white space after the value, or zero
     * @throws AstException with status {@code AST__UNFER} if a field after the first is 60 or
     *     more before any decimal point
     */
    static int dhmsUnformat(String string, String fmt, int digs, double[] value) {
        DHmsFields parsed = parseDHmsFormat(fmt, digs);
        boolean asTime = parsed.asTime;

        int s = 0;
        while (CScan.isCSpace(charAt(string, s))) {
            s++;
        }
        int stringStart = s;

        // An optional sign, surrounded by optional white space (" %1[+-] %n").
        boolean positive = true;
        char c0 = charAt(string, s);
        if (c0 == '+' || c0 == '-') {
            positive = c0 == '+';
            s++;
            while (CScan.isCSpace(charAt(string, s))) {
                s++;
            }
        }

        int angleOrTime = 0;
        char lastSep = '\0';
        int nextId = 0;
        int nfield = 0;
        char sepUsed = '\0';
        boolean suffixSep = false;
        int sepLen = 0;
        double[] field = new double[3];
        int[] fieldStart = new int[3];
        int[] fieldId = new int[3];
        int nc = 0;

        for (int ifield = 0; ifield < 3; ifield++) {
            field[ifield] = 0.0;
            if (ifield > 0 && !suffixSep) {
                s += sepLen;
            }
            fieldStart[ifield] = s;

            // A sequence of digits with an optional decimal point: "12.345", "12.", ".12" or
            // "12", then an optional exponent.
            boolean decimal = true;
            boolean exponent = false;
            int d1 = digitRun(string, s);
            int n;
            if (d1 > 0 && charAt(string, s + d1) == '.' && digitRun(string, s + d1 + 1) > 0) {
                n = d1 + 1 + digitRun(string, s + d1 + 1);
            } else if (d1 > 0 && charAt(string, s + d1) == '.') {
                n = d1 + 1;
            } else if (charAt(string, s) == '.' && digitRun(string, s + 1) > 0) {
                n = 1 + digitRun(string, s + 1);
            } else {
                n = d1;
                decimal = false;
            }
            boolean match = n > 0;
            if (match) {
                int m = exponentLength(string, s + n);
                if (m > 0) {
                    n += m;
                    exponent = true;
                }
            }

            // Read the number from exactly those characters, then any white space ("%<n>lf %n").
            int nread = 0;
            if (match) {
                CScan.Num num = CScan.strtod(string.substring(s, s + n), 0);
                if (num != null) {
                    field[ifield] = num.value;
                    int k = s + num.end;
                    while (CScan.isCSpace(charAt(string, k))) {
                        k++;
                    }
                    nread = k - s;
                    s = k;
                }
            }
            nc = s;

            // The separator after the field: a space by default, which is valid if white space
            // followed the number.
            char sep = ' ';
            boolean goodSep = nread > n;
            sepLen = 0;
            char c = charAt(string, s);
            if (c != '\0' && SEP_LIST.indexOf(c) >= 0) {
                sep = c;
                goodSep = true;
                sepLen = 1;
                while (CScan.isCSpace(charAt(string, s + sepLen))) {
                    sepLen++;
                }
            }
            int sepIndex = SEP_LIST.indexOf(sep);
            int sepFieldId = FIELD_ID_LIST[sepIndex];
            int sepAngleOrTime = ANGLE_OR_TIME_LIST[sepIndex];

            // A suffix separator must name a later field than any before it; a prefix separator
            // must be the same as the first one used.
            if (goodSep) {
                if (sepFieldId != 0) {
                    goodSep = sepFieldId >= nextId && sepFieldId > ifield;
                } else {
                    goodSep = sepUsed == '\0' || sep == sepUsed;
                    if (sepUsed == '\0') {
                        sepUsed = sep;
                    }
                }
            }
            if (goodSep) {
                if (angleOrTime == 0) {
                    angleOrTime = sepAngleOrTime;
                } else {
                    goodSep = sepAngleOrTime == 0 || sepAngleOrTime == angleOrTime;
                }
            }
            if (goodSep) {
                nread += sepLen;
            }
            suffixSep = goodSep && sepFieldId != 0;
            if (suffixSep) {
                fieldId[ifield] = sepFieldId;
                nextId = sepFieldId + 1;
                s += sepLen;
                nc = s;
            } else {
                fieldId[ifield] = nextId;
                if (nextId != 0) {
                    nextId++;
                }
            }

            // Nothing read ends the fields if this is the first, or follows white space, which
            // does not delimit another field.
            boolean last = false;
            if (nread == 0 && (ifield == 0 || CScan.isCSpace(lastSep))) {
                last = true;
            } else {
                nfield++;
            }
            if (last || decimal || exponent || !goodSep || nextId > 3) {
                break;
            }
            lastSep = sep;
        }

        // Number the fields no separator identified, working back from the last, from the fields
        // the Format says are expected.
        int id = nfield + (parsed.dh ? 0 : (parsed.min ? 1 : 2));
        if (id > 3) {
            id = 3;
        }
        for (int ifield = nfield - 1; ifield >= 0; ifield--) {
            if (fieldId[ifield] != 0) {
                id = fieldId[ifield] - 1;
            } else {
                fieldId[ifield] = id--;
            }
        }

        if (nfield == 0) {
            int nbad = badToken(string);
            if (nbad > 0) {
                value[0] = AST__BAD;
            }
            return nbad;
        }

        if (angleOrTime != 0) {
            asTime = angleOrTime == 2;
        }

        // Fields after the first must be below 60, except where only their decimal places make
        // them round up to it.
        for (int ifield = 1; ifield < nfield; ifield++) {
            if (field[ifield] >= 60.0) {
                int n = digitRun(string, fieldStart[ifield]);
                if (n > 0) {
                    CScan.Num whole = CScan.strtod(string.substring(fieldStart[ifield], fieldStart[ifield] + n), 0);
                    if (whole != null && whole.value >= 60.0) {
                        int len = 0;
                        for (int i = 0; i < nc - stringStart; i++) {
                            if (!CScan.isCSpace(string.charAt(stringStart + i))) {
                                len = i + 1;
                            }
                        }
                        throw Ast.err(
                                AstException.AST__UNFER,
                                "Invalid " + (asTime ? "" : "arc") + (fieldId[ifield] == 2 ? "minutes" : "seconds")
                                        + " value in sky coordinate \""
                                        + string.substring(stringStart, stringStart + len) + "\".");
                    }
                }
            }
        }

        double unit = asTime ? HR2RAD : DEG2RAD;
        double result = 0.0;
        for (int ifield = 0; ifield < nfield; ifield++) {
            result += field[ifield] * FIELD_VALUE[fieldId[ifield] - 1] * unit;
        }
        value[0] = positive ? result : -result;
        return nc;
    }
}
