package uk.ac.starlink.ast;

import java.util.Arrays;
import uk.ac.starlink.ast.internal.erfa.D2tf;
import uk.ac.starlink.ast.internal.erfa.Jd2cal;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A time coordinate system. A TimeFrame is a one-dimensional Frame describing positions in time,
 * as a Modified Julian Date ({@code MJD}, the default), a Julian Date ({@code JD}), a Julian epoch
 * ({@code JEPOCH}) or a Besselian epoch ({@code BEPOCH}), as its System attribute says. A
 * TimeOrigin may be set, making the axis values offsets from that moment rather than absolute
 * times. Although Julian and Modified Julian Dates are in days, other units may be used through
 * the Unit attribute, related by simple scalings (60 seconds to the minute, 60 minutes to the hour,
 * 24 hours to the day and 365.25 days to the year); Julian epochs may likewise be in units other
 * than years. Besselian epochs are always in tropical years.
 *
 * <p>The TimeScale attribute gives the physical process that defines the flow of time: TAI, UTC,
 * UT1, GMST, LAST, LMST, TT, TDB, TCB, TCG or LT (Local Time). A Julian or Modified Julian Date or
 * a Julian epoch may be in any of them, a Besselian epoch only in TT. The universal and sidereal
 * times are strictly angles, but are included as they are often thought of as time scales.
 * Converting between some time scales uses the observer's position (ObsLon, ObsLat, ObsAlt), the
 * difference between UT1 and UTC (Dut1), the difference between TAI and UTC (Dtai, which defaults
 * to a table of leap seconds) and the offset of Local Time from UTC (LTOffset). Two TimeFrames are
 * aligned in the time scale given by the AlignTimeScale attribute.
 *
 * <p>A value is formatted as a number with a Frame Format such as {@code %1.7G}, or as a Gregorian
 * date with a Format starting {@code iso}: {@code iso} gives the date ({@code yyyy-mm-dd}),
 * rounded to the nearest day; {@code iso.<n>} adds the time of day with {@code n} decimal places in
 * the seconds, separated from the date by a space, or by {@code T} if the Format ends with {@code
 * T}. The date is that of the absolute time, the TimeOrigin added. Dates are read in any form a
 * Frame's Epoch accepts, and a date given as a Besselian epoch is taken to be in TT.
 *
 * <p>The per-axis attributes Direction, Bottom, Top, Format, Label, Symbol and Unit may be named
 * without an axis number. A TimeFrame always uses the Unit of its axis when it is aligned with
 * another (its ActiveUnit flag is always true).
 *
 * <p>Ported from C {@code timeframe.c}, with Rust {@code frames/timeframe.rs}. The Plot-only
 * methods of C's TimeFrame ({@code Abbrev}, {@code Centre}, {@code Fields}, {@code Gap}) are not
 * ported.
 */
public class TimeFrame extends Frame {

    /** The Modified Julian Date System. */
    static final int AST__MJD = 1;

    /** The Julian Date System. */
    static final int AST__JD = 2;

    /** The Julian epoch System. */
    static final int AST__JEPOCH = 3;

    /** The Besselian epoch System. */
    static final int AST__BEPOCH = 4;

    /** The TimeScale value of an unset attribute. */
    static final int AST__BADTS = 0;

    /** International Atomic Time. */
    static final int AST__TAI = 1;

    /** Coordinated Universal Time. */
    static final int AST__UTC = 2;

    /** Universal Time. */
    static final int AST__UT1 = 3;

    /** Greenwich Mean Sidereal Time. */
    static final int AST__GMST = 4;

    /** Local Apparent Sidereal Time. */
    static final int AST__LAST = 5;

    /** Local Mean Sidereal Time. */
    static final int AST__LMST = 6;

    /** Terrestrial Time. */
    static final int AST__TT = 7;

    /** Barycentric Dynamical Time. */
    static final int AST__TDB = 8;

    /** Barycentric Coordinate Time. */
    static final int AST__TCB = 9;

    /** Geocentric Coordinate Time. */
    static final int AST__TCG = 10;

    /** Local Time: UTC with the offset LTOffset. */
    static final int AST__LT = 11;

    private static final int FIRST_SYSTEM = AST__MJD;
    private static final int LAST_SYSTEM = AST__BEPOCH;
    private static final int FIRST_TS = AST__TAI;
    private static final int LAST_TS = AST__LT;

    /** The Unix epoch, 1970-01-01 00:00:00 UTC, as an MJD (C's {@code UNIX_EPOCH}). */
    private static final double UNIX_EPOCH = 40587.0;

    /** The digits C writes a full-precision double with ({@code AST__DBL_DIG}). */
    private static final int DBL_DIG = 17;

    /** The names of the per-axis Frame attributes that may be given without an axis number. */
    private static final String[] ONE_AXIS_NAMES = {"direction", "bottom", "top", "format", "label", "symbol", "unit"};

    /** The TimeOrigin, in the default units of the System, or bad if unset. */
    private double timeOrigin = AST__BAD;

    /** The LTOffset in hours, or bad if unset. */
    private double ltOffset = AST__BAD;

    /** The TimeScale code, or {@link #AST__BADTS} if unset. */
    private int timeScale = AST__BADTS;

    /** The AlignTimeScale code, or {@link #AST__BADTS} if unset. */
    private int alignTimeScale = AST__BADTS;

    /**
     * Creates a TimeFrame with every attribute at its default: Modified Julian Dates in TAI, with
     * no TimeOrigin.
     */
    public TimeFrame() {
        super(1);
    }

    /**
     * Loads a TimeFrame from its description, as C's {@code astLoadTimeFrame} does: TmScl, ATmScl,
     * TmOrg and LTOff, each unset if absent, and the ClLat and ClLon an old version wrote, used as
     * the ObsLat and ObsLon if those are not set.
     *
     * @param node the description
     * @throws AstException with status {@code AST__ATTIN} if a time scale is not a known one
     */
    // C's loader passes the observer's position through the Frame class's setters.
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    TimeFrame(NativeNode node) {
        super(node);
        NativeNode part = node.section("TimeFrame");
        String sval = part.getStringIfSet("TmScl");
        if (sval != null) {
            timeScale = timeScaleCode(sval);
            if (timeScale == AST__BADTS) {
                throw Ast.err(
                        AstException.AST__ATTIN, "astRead(Channel): Invalid time scale description \"" + sval + "\".");
            }
        }
        sval = part.getStringIfSet("ATmScl");
        if (sval != null) {
            alignTimeScale = timeScaleCode(sval);
            if (alignTimeScale == AST__BADTS) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astRead(Channel): Invalid alignment time scale description \"" + sval + "\".");
            }
        }
        if (!testObsLat()) {
            double obslat = part.readDouble("ClLat", AST__BAD);
            if (obslat != AST__BAD) {
                setObsLatValue(obslat);
            }
        }
        if (!testObsLon()) {
            double obslon = part.readDouble("ClLon", AST__BAD);
            if (obslon != AST__BAD) {
                setObsLonValue(obslon);
            }
        }
        timeOrigin = part.readDouble("TmOrg", AST__BAD);
        ltOffset = part.readDouble("LTOff", AST__BAD);
    }

    @Override
    String dumpComment() {
        return "Description of time coordinate system";
    }

    /** Writes the TimeFrame class's part of a description, as C's {@code Dump} (timeframe.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        boolean set = testTimeScale();
        String sval = set ? corruptChecked(timeScale, "time scale") : getAttrib("timescale");
        node.writeString("TmScl", set, true, sval, "Time scale");
        set = testAlignTimeScale();
        sval = set ? corruptChecked(alignTimeScale, "alignment time scale") : getAttrib("aligntimescale");
        node.writeString("ATmScl", set, false, sval, "Alignment time scale");
        node.writeDouble("TmOrg", testTimeOrigin(), false, getTimeOriginValue(), "Time offset");
        node.writeDouble("LTOff", testLTOffset(), false, getLtOffset(), "Local Time offset from UTC");
    }

    /** The name of a time scale code a dump writes, or the error C reports for a corrupt one. */
    private String corruptChecked(int ts, String what) {
        String s = timeScaleString(ts);
        if (s == null) {
            throw Ast.err(
                    AstException.AST__SCSIN,
                    "astWrite(Channel): Corrupt " + astClass() + " contains invalid " + what + " identification code ("
                            + ts + ").");
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Time scales and Systems
    // ------------------------------------------------------------------

    /**
     * The time scale code a name gives, ignoring case and trailing white space, or {@link
     * #AST__BADTS} (C's {@code TimeScaleCode}).
     */
    static int timeScaleCode(String ts) {
        if (chrMatch("TAI", ts)) {
            return AST__TAI;
        } else if (chrMatch("UTC", ts)) {
            return AST__UTC;
        } else if (chrMatch("UT1", ts)) {
            return AST__UT1;
        } else if (chrMatch("GMST", ts)) {
            return AST__GMST;
        } else if (chrMatch("LAST", ts)) {
            return AST__LAST;
        } else if (chrMatch("LMST", ts)) {
            return AST__LMST;
        } else if (chrMatch("TT", ts)) {
            return AST__TT;
        } else if (chrMatch("TDB", ts)) {
            return AST__TDB;
        } else if (chrMatch("TCG", ts)) {
            return AST__TCG;
        } else if (chrMatch("TCB", ts)) {
            return AST__TCB;
        } else if (chrMatch("LT", ts)) {
            return AST__LT;
        }
        return AST__BADTS;
    }

    /** The name of a time scale code, or null (C's {@code TimeScaleString}). */
    static String timeScaleString(int ts) {
        switch (ts) {
            case AST__TAI:
                return "TAI";
            case AST__UTC:
                return "UTC";
            case AST__UT1:
                return "UT1";
            case AST__GMST:
                return "GMST";
            case AST__LAST:
                return "LAST";
            case AST__LMST:
                return "LMST";
            case AST__TT:
                return "TT";
            case AST__TDB:
                return "TDB";
            case AST__TCB:
                return "TCB";
            case AST__TCG:
                return "TCG";
            case AST__LT:
                return "LT";
            default:
                return null;
        }
    }

    /**
     * Whether converting to a time scale depends on the observer's position (C's {@code
     * CLOCK_SCALE}).
     */
    private static boolean clockScale(int ts) {
        return ts == AST__LMST || ts == AST__LAST || ts == AST__TDB || ts == AST__TCB;
    }

    /** Whether converting a time scale to UTC needs Dut1 (C's {@code DUT1_SCALE}). */
    private static boolean dut1Scale(int ts) {
        return ts == AST__LMST || ts == AST__LAST || ts == AST__GMST || ts == AST__UT1;
    }

    /**
     * The group of a time scale (C's {@code DTAI_SCALE}): converting between time scales of
     * different groups needs Dtai.
     */
    private static int dtaiScale(int ts) {
        if (ts == AST__LMST
                || ts == AST__LAST
                || ts == AST__GMST
                || ts == AST__UT1
                || ts == AST__UTC
                || ts == AST__LT) {
            return 1;
        }
        return ts == AST__TAI || ts == AST__TT ? 2 : 3;
    }

    /** Whether converting a time scale to UTC needs LTOffset (C's {@code LTOFFSET_SCALE}). */
    private static boolean ltOffsetScale(int ts) {
        return ts == AST__LT;
    }

    /** C's {@code EQUAL}: two bad values are equal, and two good ones within {@code tol}. */
    private static boolean equal(double aa, double bb, double tol) {
        if (aa == AST__BAD) {
            return bb == AST__BAD;
        }
        return bb != AST__BAD && Math.abs(aa - bb) <= tol;
    }

    /** The System code a name gives, ignoring case and trailing white space (C's {@code SystemCode}). */
    @Override
    int systemCode(String name) {
        if (chrMatch("MJD", name) || chrMatch("Modified Julian Date", name)) {
            return AST__MJD;
        } else if (chrMatch("JD", name) || chrMatch("Julian Date", name)) {
            return AST__JD;
        } else if (chrMatch("BEPOCH", name) || chrMatch("Besselian Epoch", name)) {
            return AST__BEPOCH;
        } else if (chrMatch("JEPOCH", name) || chrMatch("Julian Epoch", name)) {
            return AST__JEPOCH;
        }
        return AST__BADSYSTEM;
    }

    /** The name of a System code, or null (C's {@code SystemString}). */
    @Override
    String systemString(int code) {
        switch (code) {
            case AST__MJD:
                return "MJD";
            case AST__JD:
                return "JD";
            case AST__JEPOCH:
                return "JEPOCH";
            case AST__BEPOCH:
                return "BEPOCH";
            default:
                return null;
        }
    }

    /** The description of a System code, or null (C's {@code SystemLabel}). */
    private static String systemLabel(int system) {
        switch (system) {
            case AST__MJD:
                return "Modified Julian Date";
            case AST__JD:
                return "Julian Date";
            case AST__JEPOCH:
                return "Julian Epoch";
            case AST__BEPOCH:
                return "Besselian Epoch";
            default:
                return null;
        }
    }

    /**
     * Checks a System code (C's {@code ValidateSystem}).
     *
     * @throws AstException with status {@code AST__AXIIN} if it is not a time System
     */
    @Override
    int validateSystem(int code, String method) {
        if (code < FIRST_SYSTEM || code > LAST_SYSTEM) {
            throw Ast.err(
                    AstException.AST__AXIIN,
                    method + "(" + astClass() + "): Bad value (" + code + ") given for the System or AlignSystem"
                            + " attribute of a " + astClass() + ".");
        }
        return code;
    }

    /**
     * The default units of a System (C's {@code DefUnit}): {@code d} for the Julian Dates and
     * {@code yr} for the epochs.
     *
     * @throws AstException with status {@code AST__SCSIN} if the System is not a time System
     */
    private static String defUnit(int system, String method, String cls) {
        switch (system) {
            case AST__MJD:
            case AST__JD:
                return "d";
            case AST__BEPOCH:
            case AST__JEPOCH:
                return "yr";
            default:
                throw Ast.err(
                        AstException.AST__SCSIN,
                        method + "(" + cls + "): Corrupt " + cls + " contains illegal System identification code ("
                                + system + ").");
        }
    }

    @Override
    int systemValue() {
        return testSystem() ? super.systemValue() : AST__MJD;
    }

    @Override
    int alignSystemValue() {
        return testAlignSystem() ? super.alignSystemValue() : AST__MJD;
    }

    /**
     * Sets the System (C's {@code SetSystem}). A Besselian epoch first sets the Unit to {@code yr}
     * and the TimeScale to TT, the only values it allows. If the System changes, a set TimeOrigin
     * is re-expressed in the new System, and the Label, Symbol and Title, whose defaults depend on
     * the System, are cleared.
     */
    @Override
    void setSystemValue(int value) {
        if (value == AST__BEPOCH) {
            setUnit(1, "yr");
            setTimeScaleValue(AST__TT);
        }
        int oldsys = systemValue();
        super.setSystemValue(value);
        if (oldsys != value) {
            originSystem(oldsys, "astSetSystem");
            clearLabel(1);
            clearSymbol(1);
            clearTitle();
        }
    }

    /**
     * Clears the System (C's {@code ClearSystem}). If the System changes, a set TimeOrigin is
     * re-expressed in the default System, and the Label, Symbol and Title are cleared; leaving a
     * Besselian epoch also clears the Unit and TimeScale it required.
     */
    @Override
    void clearSystem() {
        int oldsys = systemValue();
        super.clearSystem();
        if (systemValue() != oldsys) {
            originSystem(oldsys, "astClearSystem");
            clearLabel(1);
            clearSymbol(1);
            clearTitle();
            if (oldsys == AST__BEPOCH) {
                clearUnit(1);
                clearTimeScale();
            }
        }
    }

    // ------------------------------------------------------------------
    // TimeFrame attributes
    // ------------------------------------------------------------------

    /**
     * Gets the TimeScale attribute: the time scale of the axis values, one of {@code TAI}, {@code
     * UTC}, {@code UT1}, {@code GMST}, {@code LAST}, {@code LMST}, {@code TT}, {@code TDB}, {@code
     * TCB}, {@code TCG} and {@code LT}. The default is TT for a Besselian epoch and TAI otherwise.
     *
     * <p>UT1, GMST, LAST and LMST are defined by the rotation of the Earth, the others by atomic
     * processes; converting between the two groups uses Dut1, the difference between UT1 and UTC.
     * A UTC time within a leap second is ambiguous, since each moment is one number, and may be in
     * error by up to a second when converted.
     *
     * @return the TimeScale
     */
    public String getTimeScale() {
        return getC("TimeScale");
    }

    /**
     * Sets the TimeScale attribute. A set TimeOrigin is re-expressed in the new time scale, so
     * that it stays the same moment.
     *
     * @param timeScale the TimeScale, in any case
     * @throws AstException with status {@code AST__ATTIN} if the name is not a time scale, or the
     *     System is a Besselian epoch and the time scale is not TT
     */
    public void setTimeScale(String timeScale) {
        setC("TimeScale", timeScale);
    }

    /** The TimeScale code: set, or TT for a Besselian epoch and TAI otherwise (C's {@code GetTimeScale}). */
    int timeScaleValue() {
        if (timeScale != AST__BADTS) {
            return timeScale;
        }
        return systemValue() == AST__BEPOCH ? AST__TT : AST__TAI;
    }

    /**
     * Sets the TimeScale code (C's {@code SetTimeScale}), re-expressing a set TimeOrigin in it.
     *
     * @throws AstException with status {@code AST__ATTIN} if the code is not a time scale, or the
     *     System is a Besselian epoch and the time scale is not TT
     */
    void setTimeScaleValue(int value) {
        if (value < FIRST_TS || value > LAST_TS) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetTimeScale(" + astClass() + "): Bad value (" + value + ") given for TimeScale attribute.");
        } else if (systemValue() == AST__BEPOCH && value != AST__TT) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetTimeScale(" + astClass() + "): Supplied TimeScale (" + timeScaleString(value)
                            + ") cannot be used because the " + astClass()
                            + " represents Besselian Epoch which is defined in terms of TT.");
        }
        originScale(value, "astSetTimeScale");
        timeScale = value;
    }

    boolean testTimeScale() {
        return timeScale != AST__BADTS;
    }

    /**
     * Clears the TimeScale (C's {@code ClearTimeScale}), first re-expressing a set TimeOrigin in
     * TAI unless the System is a Besselian epoch, whose time scale is TT whether set or not.
     */
    void clearTimeScale() {
        if (systemValue() != AST__BEPOCH) {
            originScale(AST__TAI, "astClearTimeScale");
        }
        timeScale = AST__BADTS;
    }

    /**
     * Gets the AlignTimeScale attribute: the time scale in which two TimeFrames are aligned when
     * one is converted to the other. The conversion found goes from the first System to the
     * AlignSystem, then from the first time scale to the AlignTimeScale, from that to the second
     * time scale, and from the AlignSystem to the second System; the AlignTimeScale matters only
     * when the TimeFrames differ in a property one of these steps uses (the observer's position,
     * Dut1, Dtai or LTOffset). The default is UT1 if the TimeScale is UT1, GMST, LMST or LAST, and
     * TAI otherwise.
     *
     * @return the AlignTimeScale
     */
    public String getAlignTimeScale() {
        return getC("AlignTimeScale");
    }

    /**
     * Sets the AlignTimeScale attribute.
     *
     * @param alignTimeScale the AlignTimeScale, in any case
     * @throws AstException with status {@code AST__ATTIN} if the name is not a time scale
     */
    public void setAlignTimeScale(String alignTimeScale) {
        setC("AlignTimeScale", alignTimeScale);
    }

    /** The AlignTimeScale code: set, or the default (C's {@code GetAlignTimeScale}). */
    int alignTimeScaleValue() {
        if (alignTimeScale != AST__BADTS) {
            return alignTimeScale;
        }
        int ts = timeScaleValue();
        return ts == AST__UT1 || ts == AST__LAST || ts == AST__LMST || ts == AST__GMST ? AST__UT1 : AST__TAI;
    }

    /**
     * Sets the AlignTimeScale code.
     *
     * @throws AstException with status {@code AST__ATTIN} if the code is not a time scale
     */
    void setAlignTimeScaleValue(int value) {
        if (value < FIRST_TS || value > LAST_TS) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetAlignTimeScale(" + astClass() + "): Bad value (" + value
                            + ") given for AlignTimeScale attribute.");
        }
        alignTimeScale = value;
    }

    boolean testAlignTimeScale() {
        return alignTimeScale != AST__BADTS;
    }

    void clearAlignTimeScale() {
        alignTimeScale = AST__BADTS;
    }

    /**
     * Gets the TimeOrigin attribute, in the units of the axis: the moment from which the axis
     * values are measured. The default, zero, makes the values absolute times in the System. The
     * TimeOrigin is a moment in time, so it is re-expressed when the System or TimeScale changes.
     *
     * @return the TimeOrigin
     * @throws AstException with status {@code AST__BADUN} if the Unit cannot be converted from the
     *     System's default units
     */
    public double getTimeOrigin() {
        return getTimeOriginCur();
    }

    /**
     * Sets the TimeOrigin attribute from a value in the units of the axis.
     *
     * @param timeOrigin the TimeOrigin
     * @throws AstException with status {@code AST__BADUN} if the Unit cannot be converted to the
     *     System's default units
     */
    public void setTimeOrigin(double timeOrigin) {
        setTimeOriginValue(toUnits(getUnit(1), timeOrigin, "astSetTimeOrigin"));
    }

    /**
     * Sets the TimeOrigin attribute from text: a number in the units of the axis; a number followed
     * by units, as in {@code 2000.5 yr}; or a date in any form a Frame's Epoch accepts, as in
     * {@code 2005-06-01 12:30} or {@code MJD 53000}, taken to be in the TimeFrame's time scale.
     *
     * @param timeOrigin the TimeOrigin
     * @throws AstException with status {@code AST__ATTIN} if the text cannot be read, or {@code
     *     AST__BADUN} if a number cannot be converted to the System's default units
     */
    public void setTimeOrigin(String timeOrigin) {
        setC("TimeOrigin", timeOrigin);
    }

    /** The TimeOrigin in the default units of the System: set, or zero (C's {@code GetTimeOrigin}). */
    double getTimeOriginValue() {
        return timeOrigin != AST__BAD ? timeOrigin : 0.0;
    }

    /** Sets the TimeOrigin in the default units of the System (C's {@code SetTimeOrigin}). */
    void setTimeOriginValue(double value) {
        timeOrigin = value;
    }

    boolean testTimeOrigin() {
        return timeOrigin != AST__BAD;
    }

    void clearTimeOrigin() {
        timeOrigin = AST__BAD;
    }

    /**
     * The TimeOrigin in the units of the axis (C's {@code GetTimeOriginCur}), which the TimeOrigin
     * attribute reports.
     *
     * @throws AstException with status {@code AST__BADUN} if the Unit cannot be converted from the
     *     System's default units
     */
    double getTimeOriginCur() {
        double result = getTimeOriginValue();
        if (result != 0.0 && result != AST__BAD) {
            String def = defUnit(systemValue(), "astGetTimeOrigin", "TimeFrame");
            String cur = getUnit(1);
            if (!cur.equals(def)) {
                Mapping map = Units.unitMapper(def, cur);
                if (map == null) {
                    throw Ast.err(
                            AstException.AST__BADUN,
                            "astGetTimeOrigin(" + astClass() + "): The current units (" + cur
                                    + ") are not suitable for a TimeFrame.");
                }
                result = transformValue(map, result, true);
            }
        }
        return result;
    }

    /**
     * Gets the LTOffset attribute: the offset of Local Time (the LT time scale) from UTC, in hours,
     * positive east of Greenwich, with no allowance for daylight saving. Fractions of an hour are
     * allowed. The default is zero.
     *
     * @return the LTOffset
     */
    public double getLtOffset() {
        return ltOffset != AST__BAD ? ltOffset : 0.0;
    }

    /**
     * Sets the LTOffset attribute.
     *
     * @param ltOffset the LTOffset, in hours
     */
    public void setLtOffset(double ltOffset) {
        this.ltOffset = ltOffset;
    }

    boolean testLTOffset() {
        return ltOffset != AST__BAD;
    }

    void clearLTOffset() {
        ltOffset = AST__BAD;
    }

    /**
     * Gets the ClockLat attribute, the latitude of the clock, which is the ObsLat attribute under
     * the name an older version of the TimeFrame used.
     *
     * @return the ObsLat
     */
    public String getClockLat() {
        return getC("ClockLat");
    }

    /**
     * Sets the ClockLat attribute, which sets the ObsLat attribute.
     *
     * @param clockLat the latitude, as {@code N} or {@code S} followed by an angle in degrees
     * @throws AstException with status {@code AST__ATTIN} if the value cannot be read
     */
    public void setClockLat(String clockLat) {
        setC("ClockLat", clockLat);
    }

    /**
     * Gets the ClockLon attribute, the longitude of the clock, which is the ObsLon attribute under
     * the name an older version of the TimeFrame used.
     *
     * @return the ObsLon
     */
    public String getClockLon() {
        return getC("ClockLon");
    }

    /**
     * Sets the ClockLon attribute, which sets the ObsLon attribute.
     *
     * @param clockLon the longitude, as {@code E} or {@code W} followed by an angle in degrees
     * @throws AstException with status {@code AST__ATTIN} if the value cannot be read
     */
    public void setClockLon(String clockLon) {
        setC("ClockLon", clockLon);
    }

    // ------------------------------------------------------------------
    // Frame attributes with TimeFrame defaults
    // ------------------------------------------------------------------

    /** The Domain: set, or {@code TIME}. */
    @Override
    public String getDomain() {
        return testDomain() ? super.getDomain() : "TIME";
    }

    /**
     * Gets the ActiveUnit flag, which is always true for a TimeFrame: a difference in the Unit of
     * two TimeFrames is always allowed for when one is converted to the other.
     *
     * @return true
     */
    @Override
    public boolean getActiveUnit() {
        return true;
    }

    @Override
    boolean testActiveUnit() {
        return false;
    }

    /**
     * The Epoch as a TDB Modified Julian Date (C's {@code GetEpoch}): set; or, with a TimeOrigin
     * set, the TimeOrigin as a TDB MJD; or J2000.0.
     *
     * <p>For a time scale defined by the Earth's rotation C makes the Mapping of the TimeOrigin to
     * UT1, meaning to treat that UT1 as UTC for the step to TDB, but then converts the TimeOrigin
     * as it is, taken as a UTC MJD in days, whatever the System (a C quirk, followed here).
     *
     * @throws AstException with status {@code AST__INTER} if there is no conversion to TDB
     */
    @Override
    double getEpochMjd() {
        if (testEpoch()) {
            return super.getEpochMjd();
        }
        if (!testTimeOrigin()) {
            return super.getEpochMjd();
        }
        double oldval = getTimeOriginValue();
        int ts = timeScaleValue();
        int sys = systemValue();
        String u = defUnit(sys, "astGetEpoch", "TimeFrame");
        if (ts == AST__UT1 || ts == AST__GMST || ts == AST__LAST || ts == AST__LMST) {
            Mapping map = makeMap(sys, AST__MJD, ts, AST__UT1, 0.0, 0.0, u, "d", "astGetEpoch");
            if (map == null) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astGetEpoch(" + astClass() + "): No Mapping from " + timeScaleString(ts)
                                + " to UT1 (AST internal programming error).");
            }
            ts = AST__UTC;
            sys = AST__MJD;
            u = "d";
        }
        Mapping map = makeMap(sys, AST__MJD, ts, AST__TDB, 0.0, 0.0, u, "d", "astGetEpoch");
        if (map == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astGetEpoch(" + astClass() + "): No Mapping from " + timeScaleString(ts)
                            + " to TDB (AST internal programming error).");
        }
        return transformValue(map, oldval, true);
    }

    /**
     * Gets the Unit of the axis: set, or the default units of the System ({@code d} for Julian
     * and Modified Julian Dates, {@code yr} for epochs).
     */
    @Override
    public String getUnit(int axis) {
        validateAxis(axis, true, "astGetUnit");
        if (testUnit(axis)) {
            return super.getUnit(axis);
        }
        return defUnit(systemValue(), "astGetUnit", astClass());
    }

    /**
     * Sets the Unit of the axis (C's {@code SetUnit}). A Besselian epoch allows only {@code yr}.
     *
     * @throws AstException with status {@code AST__ATTIN} if the System is a Besselian epoch and
     *     the Unit is not {@code yr}
     */
    @Override
    public void setUnit(int axis, String unit) {
        validateAxis(axis, true, "astSetUnit");
        if (systemValue() == AST__BEPOCH && !"yr".equals(unit)) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetUnit(" + astClass() + "): Supplied Unit (" + unit + ") cannot be used because the "
                            + astClass() + " represents Besselian Epoch which is defined in units of years (yr).");
        }
        super.setUnit(axis, unit);
    }

    /**
     * Formats the origin, the axis value zero, as an absolute date and time with the Format {@code
     * iso.0} set for the purpose, as C's {@code GetLabel} and {@code GetTitle} do.
     */
    private String formatOrigin() {
        String fmt = getFormat(1);
        boolean fmtSet = testFormat(1);
        setFormat(1, "iso.0");
        try {
            return format(1, 0.0);
        } finally {
            if (fmtSet) {
                setFormat(1, fmt);
            } else {
                clearFormat(1);
            }
        }
    }

    /** The " (UTC+offset)" C appends for Local Time, with the offset as {@code %g} formats it. */
    private String ltSuffix() {
        double ltoff = getLtOffset();
        return ltoff >= 0.0
                ? " (UTC+" + CFormat.format("%g", ltoff) + ")"
                : " (UTC-" + CFormat.format("%g", -ltoff) + ")";
    }

    /**
     * Gets the Label of the axis (C's {@code GetLabel}): set; or, for a date Format, {@code
     * Date/Time} or {@code Date}; or the System's description, followed by {@code offset from}
     * and the TimeOrigin as a date and time (the time left out when it is midnight) if the
     * TimeOrigin is not zero, and rewritten for a set Unit, as {@code log( Julian Epoch )} for
     * {@code log(yr)}. For Local Time the offset from UTC follows.
     */
    @Override
    public String getLabel(int axis) {
        validateAxis(axis, true, "astGetLabel");
        if (testLabel(axis)) {
            return super.getLabel(axis);
        }
        String result;
        DateFmt df = dateFormat(getFormat(1));
        if (df.isDate) {
            result = df.ndp >= 0 ? "Date/Time" : "Date";
        } else {
            int system = systemValue();
            result = systemLabel(system);
            if (getTimeOriginValue() != 0.0) {
                String withOrigin = result + " offset from " + formatOrigin();
                result =
                        withOrigin.endsWith("00:00:00") ? withOrigin.substring(0, withOrigin.length() - 8) : withOrigin;
            }
            if (testUnit(axis)) {
                Units.LabelledMapping lm =
                        Units.relabel(defUnit(system, "astGetLabel", astClass()), getUnit(axis), result);
                if (lm != null && lm.label != null) {
                    result = lm.label;
                }
            }
        }
        return timeScaleValue() == AST__LT ? result + ltSuffix() : result;
    }

    /**
     * Gets the Symbol of the axis (C's {@code GetSymbol}): set, or {@code MJD}, {@code JD}, {@code
     * JEP} or {@code BEP} for the System, rewritten for a set Unit.
     */
    @Override
    public String getSymbol(int axis) {
        validateAxis(axis, true, "astGetSymbol");
        if (testSymbol(axis)) {
            return super.getSymbol(axis);
        }
        int system = systemValue();
        String result;
        switch (system) {
            case AST__MJD:
                result = "MJD";
                break;
            case AST__JD:
                result = "JD";
                break;
            case AST__BEPOCH:
                result = "BEP";
                break;
            case AST__JEPOCH:
                result = "JEP";
                break;
            default:
                throw Ast.err(
                        AstException.AST__SCSIN,
                        "astGetSymbol(" + astClass() + "): Corrupt " + astClass()
                                + " contains invalid System identification code (" + system + ").");
        }
        if (testUnit(axis)) {
            Units.LabelledMapping lm =
                    Units.relabel(defUnit(system, "astGetSymbol", astClass()), getUnit(axis), result);
            if (lm != null && lm.label != null) {
                result = lm.label;
            }
        }
        return result;
    }

    /**
     * Gets the Title (C's {@code GetTitle}): set, or the System's description, followed by the
     * TimeScale in brackets if it is set (with the offset from UTC for Local Time) and the System
     * is not a Besselian epoch, and by {@code offset from} and the TimeOrigin as a date and time
     * if the TimeOrigin is not zero and the Format is not a date Format.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        if (testTitle()) {
            return super.getTitle();
        }
        int system = systemValue();
        double orig = getTimeOriginCur();
        int ts = timeScaleValue();
        StringBuilder sb = new StringBuilder(systemLabel(system));
        if (system != AST__BEPOCH && testTimeScale()) {
            sb.append(" [").append(timeScaleString(ts));
            if (ts == AST__LT) {
                sb.append(ltSuffix());
            }
            sb.append(']');
        }
        if (orig != 0.0 && !dateFormat(getFormat(1)).isDate) {
            sb.append(" offset from ").append(formatOrigin());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Formatting and reading values
    // ------------------------------------------------------------------

    /** What a Format says about formatting as a date (C's {@code DateFormat}). */
    private static final class DateFmt {
        /** Whether values are formatted as dates. */
        final boolean isDate;

        /** The decimal places of the seconds, or -1 for no time of day. */
        final int ndp;

        /** The character between the date and the time. */
        final char sep;

        DateFmt(boolean isDate, int ndp, char sep) {
            this.isDate = isDate;
            this.ndp = ndp;
            this.sep = sep;
        }
    }

    /**
     * C's {@code DateFormat}: a Format whose first character that is not white space starts {@code
     * iso} is a date Format; {@code iso.<n>} as C's {@code sscanf( "iso.%d%n" )} reads it adds the
     * time of day with {@code n} decimal places in the seconds, separated by {@code T} if the next
     * character is {@code T} or {@code t} and by a space otherwise.
     */
    private static DateFmt dateFormat(String fmt) {
        if (fmt == null) {
            return new DateFmt(false, -1, ' ');
        }
        int c = 0;
        while (c < fmt.length() && CScan.isCSpace(fmt.charAt(c))) {
            c++;
        }
        if (!fmt.startsWith("iso", c)) {
            return new DateFmt(false, -1, ' ');
        }
        int p = c + 3;
        if (p < fmt.length() && fmt.charAt(p) == '.') {
            p++;
            int q = p;
            while (q < fmt.length() && CScan.isCSpace(fmt.charAt(q))) {
                q++;
            }
            int digitsStart = q;
            if (q < fmt.length() && (fmt.charAt(q) == '+' || fmt.charAt(q) == '-')) {
                q++;
            }
            int signEnd = q;
            while (q < fmt.length() && fmt.charAt(q) >= '0' && fmt.charAt(q) <= '9') {
                q++;
            }
            if (q > signEnd) {
                Integer ndp = CScan.scanInt(fmt.substring(digitsStart, q));
                if (ndp != null) {
                    char sep = q < fmt.length() && (fmt.charAt(q) == 'T' || fmt.charAt(q) == 't') ? 'T' : ' ';
                    return new DateFmt(true, ndp, sep);
                }
            }
        }
        return new DateFmt(true, -1, ' ');
    }

    /**
     * Formats a value of the axis (C's {@code Format}): with a Frame Format as a number; with a
     * date Format as the date and time of the value, the TimeOrigin added and converted to an MJD
     * in days, rounded to the nearest day if no time of day is shown. The bad value gives {@code
     * <bad>}.
     *
     * @param axis the axis number, 1
     * @param value the value
     * @return the formatted value, or null for a date Format if the Unit cannot be converted to
     *     days, as C gives
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String format(int axis, double value) {
        validateAxis(axis, true, "astFormat");
        if (isBad(value)) {
            return "<bad>";
        }
        DateFmt df = dateFormat(getFormat(1));
        if (!df.isDate) {
            return super.format(axis, value);
        }
        int ts = timeScaleValue();
        int sys = systemValue();
        double off = getTimeOriginValue();
        String u = getUnit(1);
        Mapping map = makeMap(sys, AST__MJD, ts, ts, off, 0.0, u, "d", "astFormat");
        if (map == null) {
            return null;
        }
        double mjd = transformValue(map, value, true);
        if (df.ndp < 0) {
            mjd = roundHalfAway(mjd);
        }
        // An MJD palDjcl cannot convert leaves C's uninitialised fields; zeros are given here.
        Jd2cal.Result cal = Pal.djcl(mjd);
        int iy = cal != null ? cal.iy : 0;
        int im = cal != null ? cal.im : 0;
        int id = cal != null ? cal.id : 0;
        double fd = cal != null ? cal.fd : 0.0;
        StringBuilder sb = new StringBuilder();
        sb.append(CFormat.format("%4d", iy))
                .append('-')
                .append(CFormat.format("%2.2d", im))
                .append('-')
                .append(CFormat.format("%2.2d", id));
        if (df.ndp >= 0) {
            D2tf.Result hms = Pal.dd2tf(df.ndp, fd);
            int[] f = hms.ihmsf;
            sb.append(df.sep)
                    .append(CFormat.format("%2.2d", f[0]))
                    .append(':')
                    .append(CFormat.format("%2.2d", f[1]))
                    .append(':')
                    .append(CFormat.format("%2.2d", f[2]));
            if (df.ndp > 0) {
                sb.append('.').append(CFormat.format("%" + df.ndp + "." + df.ndp + "d", f[3]));
            }
        }
        return sb.toString();
    }

    /** C's {@code round}: to the nearest integer, halfway cases away from zero. */
    private static double roundHalfAway(double x) {
        double a = Math.abs(x);
        double t = Math.floor(a);
        // a - t is exact, so a value just below a half is not rounded up.
        return Math.copySign(a - t >= 0.5 ? t + 1.0 : t, x);
    }

    /** C's {@code astChrLen}: the length of a string without trailing white space. */
    private static int chrLen(String s) {
        int n = s.length();
        while (n > 0 && CScan.isCSpace(s.charAt(n - 1))) {
            n--;
        }
        return n;
    }

    /**
     * Reads a value of the axis from the start of a string (C's {@code Unformat}): a number in the
     * System and Unit of the axis, as a Frame reads it, or a date and time in any form a Frame's
     * Epoch accepts, converted to an axis value. The longest start of the string that is a date is
     * taken, and the number is preferred if it uses at least as much of the string. A date given as
     * a Besselian epoch is in TT, any other in the TimeFrame's time scale.
     *
     * @throws AstException with status {@code AST__INCTS} if a date cannot be converted to the
     *     TimeFrame's time scale
     */
    @Override
    int unformat(int axis, String string, double[] value) {
        validateAxis(axis, true, "astUnformat");
        String txt = getFormat(axis);
        boolean dateFmt = dateFormat(txt).isDate;
        if (dateFmt) {
            clearFormat(axis);
        }
        double[] val1 = {AST__BAD};
        int nc1;
        try {
            nc1 = super.unformat(axis, string, val1);
        } finally {
            if (dateFmt) {
                setFormat(axis, txt);
            }
        }
        int l = chrLen(string);
        double mjd = AST__BAD;
        int lt;
        for (lt = l; lt > 0; lt--) {
            try {
                mjd = DateTime.readDateTime(string.substring(0, lt));
            } catch (AstException e) {
                mjd = AST__BAD;
            }
            if (mjd != AST__BAD) {
                break;
            }
        }
        if (lt >= l) {
            lt = string.length();
        }
        if (mjd == AST__BAD || nc1 >= l) {
            if (nc1 != 0) {
                value[0] = val1[0];
            }
            return nc1;
        }
        int ts2 = timeScaleValue();
        int c = 0;
        while (c < string.length() && CScan.isCSpace(string.charAt(c))) {
            c++;
        }
        boolean bes = c < string.length() && (string.charAt(c) == 'B' || string.charAt(c) == 'b');
        int ts1 = bes ? AST__TT : ts2;
        Mapping map =
                makeMap(AST__MJD, systemValue(), ts1, ts2, 0.0, getTimeOriginValue(), "d", getUnit(1), "astFormat");
        if (map == null) {
            throw Ast.err(
                    AstException.AST__INCTS,
                    "astUnformat(" + astClass() + "): Cannot convert the supplied date/time string (" + string
                            + ") to the required timescale (" + timeScaleString(ts2) + ").");
        }
        value[0] = transformValue(map, mjd, true);
        return lt;
    }

    // ------------------------------------------------------------------
    // The current time
    // ------------------------------------------------------------------

    /**
     * Gets the current system time as a value of this TimeFrame's axis, to be read with its
     * System, TimeOrigin, TimeScale, LTOffset and Unit. The system clock is taken to count SI
     * seconds since 1970-01-01 00:00:00 UTC, as POSIX defines it, and is read to the second; any
     * error in the clock is in the value.
     *
     * @return the current time
     * @throws AstException with status {@code AST__INCTS} if UTC cannot be converted to the
     *     TimeFrame's time scale
     */
    public double currentTime() {
        // C reads time(), whole seconds since 1970.
        return currentTime(Math.floor(System.currentTimeMillis() / 1000.0));
    }

    /**
     * The value of this TimeFrame's axis at a clock reading of {@code systime} UTC seconds since
     * 1970 (C's {@code CurrentTime}, given the reading).
     */
    double currentTime(double systime) {
        Mapping map = makeMap(
                AST__MJD,
                systemValue(),
                AST__UTC,
                timeScaleValue(),
                UNIX_EPOCH,
                getTimeOriginValue(),
                "s",
                getUnit(1),
                "astCurrentTime");
        if (map == null) {
            throw Ast.err(
                    AstException.AST__INCTS,
                    "astCurrentTime(" + astClass() + "): Cannot convert the current system time to the required"
                            + " timescale (" + timeScaleString(timeScaleValue()) + ").");
        }
        return transformValue(map, systime, true);
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /** One value transformed by a Mapping with one input and one output. */
    private static double transformValue(Mapping map, double value, boolean forward) {
        return map.tran1(1, new double[] {value}, forward)[0];
    }

    /** The first {@code n} arguments of an argument list, as C passes {@code n} to astTimeAdd. */
    private static double[] first(double[] args, int n) {
        return Arrays.copyOf(args, n);
    }

    /** A units Mapping C uses without checking it exists; C's use of the null Mapping fails. */
    private static Mapping requireUnits(Mapping map, String unit1, String unit2, String method) {
        if (map == null) {
            // C passes the null Mapping on, which reports AST__OBJIN.
            throw Ast.err(
                    AstException.AST__OBJIN,
                    method + "(TimeFrame): No Mapping from units of " + unit1 + " to " + unit2 + ".");
        }
        return map;
    }

    /**
     * The Mapping from a value in System {@code sys1}, time scale {@code ts1}, offset {@code off1}
     * (in the default units of {@code sys1}) and units {@code unit1}, to one in {@code sys2},
     * {@code ts2}, {@code off2} and {@code unit2} (C's {@code MakeMap}); or null if the units cannot
     * be converted. Each time scale conversion uses this TimeFrame's observer position, Dut1, Dtai
     * and LTOffset.
     */
    Mapping makeMap(
            int sys1, int sys2, int ts1, int ts2, double off1, double off2, String unit1, String unit2, String method) {
        Mapping result = null;
        if (ts1 == ts2 && sys1 == sys2) {
            if (MappingHelpers.astEquals(off1, off2, 1.0e3)) {
                result = unit1.equals(unit2) ? new UnitMap(1) : Units.unitMapper(unit1, unit2);
            } else {
                double shift = off1 - off2;
                String du = defUnit(sys1, method, "TimeFrame");
                if (!du.equals(unit2) && shift != 0.0) {
                    Mapping umap = requireUnits(Units.unitMapper(du, unit2), du, unit2, method);
                    shift = transformValue(umap, shift, true);
                }
                result = new ShiftMap(new double[] {shift});
                if (!unit1.equals(unit2)) {
                    Mapping umap = requireUnits(Units.unitMapper(unit1, unit2), unit1, unit2, method);
                    result = new CmpMap(umap, result, true);
                }
            }
        }
        if (result != null) {
            return result;
        }

        Mapping umap1 = Units.unitMapper(unit1, defUnit(sys1, method, "TimeFrame"));
        TimeMap timemap = new TimeMap();
        double[] args = new double[5];
        args[0] = off1;
        args[1] = toMjd(sys1, off1);
        if (sys1 == AST__JD) {
            timemap.timeAdd("JDTOMJD", first(args, 2));
        } else if (sys1 == AST__JEPOCH) {
            timemap.timeAdd("JEPTOMJD", first(args, 2));
        } else if (sys1 == AST__BEPOCH) {
            timemap.timeAdd("BEPTOMJD", first(args, 2));
        }
        // The Frame stores longitude positive east; the TimeMap wants it positive west.
        args[0] = args[1];
        args[1] = -getObsLonValue();
        args[2] = getObsLatValue();
        args[3] = getObsAlt();
        args[4] = getDtai();
        double[] argsUt = {getDut1()};
        double[] argsLt = {getLtOffset()};
        double[] argsTai = {args[0], getDtai()};
        if (ts1 != ts2) {
            addToTai(timemap, ts1, args, argsUt, argsLt, argsTai);
            addFromTai(timemap, ts2, args, argsUt, argsLt, argsTai);
        }
        args[1] = off2;
        if (sys2 == AST__MJD) {
            if (args[0] != off2) {
                timemap.timeAdd("MJDTOMJD", first(args, 2));
            }
        } else if (sys2 == AST__JD) {
            timemap.timeAdd("MJDTOJD", first(args, 2));
        } else if (sys2 == AST__JEPOCH) {
            timemap.timeAdd("MJDTOJEP", first(args, 2));
        } else if (sys2 == AST__BEPOCH) {
            timemap.timeAdd("MJDTOBEP", first(args, 2));
        }
        Mapping umap2 = Units.unitMapper(defUnit(sys2, method, "TimeFrame"), unit2);
        if (umap1 == null || umap2 == null) {
            return null;
        }
        Mapping tmap = new CmpMap(umap1, timemap, true);
        return new CmpMap(tmap, umap2, true).simplify();
    }

    /** Adds C's {@code MakeMap} conversion from time scale {@code ts1} to TAI. */
    private static void addToTai(
            TimeMap timemap, int ts1, double[] args, double[] argsUt, double[] argsLt, double[] argsTai) {
        // MODERN-JAVA(21): switch with arrow labels
        switch (ts1) {
            case AST__TAI:
                break;
            case AST__UTC:
                timemap.timeAdd("UTCTOTAI", argsTai);
                break;
            case AST__TT:
                timemap.timeAdd("TTTOTAI", first(args, 1));
                break;
            case AST__TDB:
                timemap.timeAdd("TDBTOTT", first(args, 5));
                timemap.timeAdd("TTTOTAI", first(args, 1));
                break;
            case AST__TCG:
                timemap.timeAdd("TCGTOTT", first(args, 1));
                timemap.timeAdd("TTTOTAI", first(args, 1));
                break;
            case AST__LT:
                timemap.timeAdd("LTTOUTC", argsLt);
                timemap.timeAdd("UTCTOTAI", argsTai);
                break;
            case AST__TCB:
                timemap.timeAdd("TCBTOTDB", first(args, 1));
                timemap.timeAdd("TDBTOTT", first(args, 5));
                timemap.timeAdd("TTTOTAI", first(args, 1));
                break;
            case AST__UT1:
                timemap.timeAdd("UTTOUTC", argsUt);
                timemap.timeAdd("UTCTOTAI", argsTai);
                break;
            case AST__GMST:
                timemap.timeAdd("GMSTTOUT", first(args, 1));
                timemap.timeAdd("UTTOUTC", argsUt);
                timemap.timeAdd("UTCTOTAI", argsTai);
                break;
            case AST__LAST:
                timemap.timeAdd("LASTTOLMST", first(args, 3));
                timemap.timeAdd("LMSTTOGMST", first(args, 3));
                timemap.timeAdd("GMSTTOUT", first(args, 1));
                timemap.timeAdd("UTTOUTC", argsUt);
                timemap.timeAdd("UTCTOTAI", argsTai);
                break;
            case AST__LMST:
                timemap.timeAdd("LMSTTOGMST", first(args, 3));
                timemap.timeAdd("GMSTTOUT", first(args, 1));
                timemap.timeAdd("UTTOUTC", argsUt);
                timemap.timeAdd("UTCTOTAI", argsTai);
                break;
            default:
                // C adds nothing for a code it does not know.
                break;
        }
    }

    /** Adds C's {@code MakeMap} conversion from TAI to time scale {@code ts2}. */
    private static void addFromTai(
            TimeMap timemap, int ts2, double[] args, double[] argsUt, double[] argsLt, double[] argsTai) {
        // MODERN-JAVA(21): switch with arrow labels
        switch (ts2) {
            case AST__TAI:
                break;
            case AST__UTC:
                timemap.timeAdd("TAITOUTC", argsTai);
                break;
            case AST__TT:
                timemap.timeAdd("TAITOTT", first(args, 1));
                break;
            case AST__TDB:
                timemap.timeAdd("TAITOTT", first(args, 1));
                timemap.timeAdd("TTTOTDB", first(args, 5));
                break;
            case AST__TCG:
                timemap.timeAdd("TAITOTT", first(args, 1));
                timemap.timeAdd("TTTOTCG", first(args, 1));
                break;
            case AST__TCB:
                timemap.timeAdd("TAITOTT", first(args, 1));
                timemap.timeAdd("TTTOTDB", first(args, 5));
                timemap.timeAdd("TDBTOTCB", first(args, 1));
                break;
            case AST__UT1:
                timemap.timeAdd("TAITOUTC", argsTai);
                timemap.timeAdd("UTCTOUT", argsUt);
                break;
            case AST__GMST:
                timemap.timeAdd("TAITOUTC", argsTai);
                timemap.timeAdd("UTCTOUT", argsUt);
                timemap.timeAdd("UTTOGMST", first(args, 1));
                break;
            case AST__LAST:
                timemap.timeAdd("TAITOUTC", argsTai);
                timemap.timeAdd("UTCTOUT", argsUt);
                timemap.timeAdd("UTTOGMST", first(args, 1));
                timemap.timeAdd("GMSTTOLMST", first(args, 3));
                timemap.timeAdd("LMSTTOLAST", first(args, 3));
                break;
            case AST__LMST:
                timemap.timeAdd("TAITOUTC", argsTai);
                timemap.timeAdd("UTCTOUT", argsUt);
                timemap.timeAdd("UTTOGMST", first(args, 1));
                timemap.timeAdd("GMSTTOLMST", first(args, 3));
                break;
            case AST__LT:
                timemap.timeAdd("TAITOUTC", argsTai);
                timemap.timeAdd("UTCTOLT", argsLt);
                break;
            default:
                // C adds nothing for a code it does not know.
                break;
        }
    }

    /**
     * A value in System {@code oldsys}, with no offset, as an MJD (C's {@code ToMJD}): through a
     * TimeMap with the System's conversion to MJD.
     */
    private static double toMjd(int oldsys, double oldval) {
        if (oldsys == AST__MJD) {
            return oldval;
        }
        TimeMap timemap = new TimeMap();
        double[] args = {0.0, 0.0};
        if (oldsys == AST__JD) {
            timemap.timeAdd("JDTOMJD", args);
        } else if (oldsys == AST__JEPOCH) {
            timemap.timeAdd("JEPTOMJD", args);
        } else if (oldsys == AST__BEPOCH) {
            timemap.timeAdd("BEPTOMJD", args);
        }
        return transformValue(timemap, oldval, true);
    }

    /** An MJD, with no offset, in this TimeFrame's System (C's {@code FromMJD}). */
    private double fromMjd(double oldval) {
        int newsys = systemValue();
        if (newsys == AST__MJD) {
            return oldval;
        }
        TimeMap timemap = new TimeMap();
        double[] args = {0.0, 0.0};
        if (newsys == AST__JD) {
            timemap.timeAdd("MJDTOJD", args);
        } else if (newsys == AST__JEPOCH) {
            timemap.timeAdd("MJDTOJEP", args);
        } else if (newsys == AST__BEPOCH) {
            timemap.timeAdd("MJDTOBEP", args);
        }
        return transformValue(timemap, oldval, true);
    }

    /**
     * A value in units {@code oldunit} in the default units of the System (C's {@code ToUnits}).
     *
     * @throws AstException with status {@code AST__BADUN} if the units cannot be converted
     */
    private double toUnits(String oldunit, double oldval, String method) {
        String defunit = defUnit(systemValue(), method, "TimeFrame");
        Mapping map = Units.unitMapper(oldunit, defunit);
        if (map == null) {
            throw Ast.err(
                    AstException.AST__BADUN,
                    method + "(" + astClass() + "): Cannot convert the supplied attribute value from units of "
                            + oldunit + " to " + defunit + ".");
        }
        return transformValue(map, oldval, true);
    }

    /**
     * Re-expresses a set TimeOrigin in time scale {@code newts}, before the TimeScale changes to it
     * (C's {@code OriginScale}).
     *
     * @throws AstException with status {@code AST__INCTS} if there is no conversion
     */
    private void originScale(int newts, String method) {
        if (!testTimeOrigin()) {
            return;
        }
        int oldts = timeScaleValue();
        if (newts == oldts) {
            return;
        }
        int sys = systemValue();
        String u = defUnit(sys, method, "TimeFrame");
        Mapping map = makeMap(sys, sys, oldts, newts, 0.0, 0.0, u, u, method);
        if (map == null) {
            throw Ast.err(
                    AstException.AST__INCTS,
                    method + "(" + astClass() + "): Cannot convert the TimeOrigin value to a different timescale"
                            + " because of incompatible time scales.");
        }
        setTimeOriginValue(transformValue(map, getTimeOriginValue(), true));
    }

    /**
     * Re-expresses a set TimeOrigin, in System {@code oldsys}, in the System the TimeFrame now has
     * (C's {@code OriginSystem}).
     *
     * @throws AstException with status {@code AST__INCTS} if there is no conversion
     */
    private void originSystem(int oldsys, String method) {
        if (!testTimeOrigin()) {
            return;
        }
        int newsys = systemValue();
        if (oldsys == newsys) {
            return;
        }
        int ts = timeScaleValue();
        String oldu = defUnit(oldsys, method, "TimeFrame");
        String newu = defUnit(newsys, method, "TimeFrame");
        Mapping map = makeMap(oldsys, newsys, ts, ts, 0.0, 0.0, oldu, newu, method);
        if (map == null) {
            throw Ast.err(
                    AstException.AST__INCTS,
                    method + "(" + astClass() + "): Cannot convert the TimeOrigin value to a different System because"
                            + " of incompatible time scales.");
        }
        setTimeOriginValue(transformValue(map, getTimeOriginValue(), true));
    }

    /**
     * The Mapping from {@code target} to {@code result}, aligned in the AlignTimeScale of {@code
     * alignFrm} (C's {@code MakeTimeMapping}), or null if there is none. The alignment time scale
     * matters only if the two TimeFrames differ in a property that the conversion to or from it
     * uses (the observer's position, Dut1, Dtai or LTOffset); then the Mapping is made in two parts,
     * to an MJD in the alignment time scale with the target's properties and from it with the
     * result's. C's only caller asks for no error report when there is no Mapping.
     */
    private static Mapping makeTimeMapping(TimeFrame target, TimeFrame result, TimeFrame alignFrm) {
        int sys1 = target.systemValue();
        int ts1 = target.timeScaleValue();
        double off1 = target.getTimeOriginValue();
        String u1 = target.getUnit(1);
        double ltoff1 = target.getLtOffset();
        int sys2 = result.systemValue();
        int ts2 = result.timeScaleValue();
        double off2 = result.getTimeOriginValue();
        String u2 = result.getUnit(1);
        double ltoff2 = result.getLtOffset();
        int alignTs = alignFrm.alignTimeScaleValue();

        boolean clkdiff = target.getObsLonValue() != result.getObsLonValue()
                || target.getObsLatValue() != result.getObsLatValue()
                || target.getObsAlt() != result.getObsAlt();
        boolean taclk = clockScale(ts1) || clockScale(alignTs);
        boolean arclk = clockScale(alignTs) || clockScale(ts2);
        boolean dut1diff = target.getDut1() != result.getDut1();
        boolean tadut = dut1Scale(ts1) != dut1Scale(alignTs);
        boolean ardut = dut1Scale(alignTs) != dut1Scale(ts2);
        boolean dtaidiff = !equal(target.getDtai(), result.getDtai(), 1.0e-6);
        boolean tadtai = dtaiScale(ts1) != dtaiScale(alignTs);
        boolean ardtai = dtaiScale(alignTs) != dtaiScale(ts2);
        boolean ltodiff = ltoff1 != ltoff2;
        boolean talto = ltOffsetScale(ts1) != ltOffsetScale(alignTs);
        boolean arlto = ltOffsetScale(alignTs) != ltOffsetScale(ts2);

        if ((!clkdiff || !(taclk || arclk))
                && (!ltodiff || !(talto || arlto))
                && (!dut1diff || !(tadut || ardut))
                && (!dtaidiff || !(tadtai || ardtai))) {
            return target.makeMap(sys1, sys2, ts1, ts2, off1, off2, u1, u2, "astSubFrame");
        }
        double alignOff = toMjd(sys1, off1);
        String alignUnit = defUnit(AST__MJD, "MakeTimeMap", "TimeFrame");
        Mapping map1 = target.makeMap(sys1, AST__MJD, ts1, alignTs, off1, alignOff, u1, alignUnit, "MakeTimeMap");
        Mapping map2 = result.makeMap(AST__MJD, sys2, alignTs, ts2, alignOff, off2, alignUnit, u2, "MakeTimeMap");
        if (map1 == null || map2 == null) {
            return null;
        }
        return new CmpMap(map1, map2, true).simplify();
    }

    /**
     * C's {@code VerifyAttrs}: with UseDefs false, checks that each attribute named in {@code
     * attrs} (separated by white space, a name standing for every attribute it starts) is set.
     *
     * @throws AstException with status {@code AST__NOVAL} if one is not, saying that the TimeFrame
     *     cannot do {@code purp}
     */
    private void verifyAttrs(String purp, String attrs, String method) {
        if (getUseDefs()) {
            return;
        }
        int i = 0;
        int n = attrs.length();
        while (i < n) {
            while (i < n && CScan.isCSpace(attrs.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !CScan.isCSpace(attrs.charAt(i))) {
                i++;
            }
            if (i == start) {
                break;
            }
            String a = attrs.substring(start, i);
            boolean set;
            String desc;
            if ("ObsLat".startsWith(a)) {
                set = testObsLat();
                desc = "observer latitude";
            } else if ("ObsLon".startsWith(a)) {
                set = testObsLon();
                desc = "observer longitude";
            } else if ("ObsAlt".startsWith(a)) {
                set = testObsAlt();
                desc = "observer altitude";
            } else if ("Dtai".startsWith(a)) {
                set = testDtai();
                desc = "TAI-UTC correction";
            } else if ("Dut1".startsWith(a)) {
                set = testDut1();
                desc = "UT1-UTC correction";
            } else if ("TimeOrigin".startsWith(a)) {
                set = testTimeOrigin();
                desc = "time offset";
            } else if ("LTOffset".startsWith(a)) {
                set = testLTOffset();
                desc = "local time offset";
            } else if ("TimeScale".startsWith(a)) {
                set = testTimeScale();
                desc = "time scale";
            } else {
                throw Ast.err(
                        AstException.AST__INTER,
                        "VerifyAttrs(TimeFrame): Unknown attribute name \"" + a
                                + "\" supplied (AST internal programming error).");
            }
            if (!set) {
                throw Ast.err(
                        AstException.AST__NOVAL,
                        method + "(" + astClass() + "): Cannot " + purp + ".\nNo value has been set for the AST \"" + a
                                + "\" attribute (" + desc + ").");
            }
        }
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * Matches this TimeFrame, a template, against a target (C's {@code Match}, timeframe.c): the
     * target must pass the Frame tests and have an axis of a TimeFrame, the first of which is
     * selected.
     */
    @Override
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        FrameFinder.MatchResult parent = super.match(target, matchSub);
        if (parent == null) {
            return null;
        }
        int targetNaxes = target.getNaxes();
        int[] pax = new int[1];
        int targetAxis0 = -1;
        for (int iaxis = 0; iaxis < targetNaxes; iaxis++) {
            if (target.primaryFrame(iaxis + 1, pax) instanceof TimeFrame) {
                targetAxis0 = iaxis;
                break;
            }
        }
        if (targetAxis0 < 0) {
            return null;
        }
        int[] templateAxes = parent.templateAxes;
        int[] targetAxes = parent.targetAxes;
        templateAxes[0] = 0;
        targetAxes[0] = targetAxis0;
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        if (!target.subFrame(this, 1, targetAxes, templateAxes, map, result)) {
            return null;
        }
        return new FrameFinder.MatchResult(templateAxes, targetAxes, map[0], result[0]);
    }

    /**
     * Selects axes from this TimeFrame (C's {@code SubFrame}, timeframe.c). Selecting the one axis
     * gives a TimeFrame, a copy with the template's attributes overlaid, and the Mapping converts
     * between the time systems, scales, origins and units, aligned in the template's AlignTimeScale
     * (the target's if the template is not a TimeFrame or there is none). Any other selection gives
     * a plain Frame, made from a copy whose Domain, Title, Label, Symbol and Unit are set to their
     * values and whose System and AlignSystem are cleared, which clears the Title, Label and Symbol
     * again if the System changes (a C quirk, followed here).
     *
     * @throws AstException with status {@code AST__NOVAL} if there is no template, UseDefs is false
     *     and the TimeScale is not set
     */
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        map[0] = null;
        result[0] = null;
        boolean match;
        if (resultNaxes == 1 && targetAxes[0] == 0) {
            TimeFrame res = (TimeFrame) copy();
            result[0] = res;
            TimeFrame alignFrm;
            if (template != null) {
                template.overlay(templateAxes, res);
                alignFrm = template instanceof TimeFrame ? (TimeFrame) template : this;
            } else {
                verifyAttrs("convert between different time systems", "TimeScale", "astMatch");
                alignFrm = this;
            }
            Mapping m = makeTimeMapping(this, res, alignFrm);
            match = m != null;
            map[0] = m;
        } else {
            TimeFrame temp = (TimeFrame) copy();
            if (!temp.testDomain()) {
                temp.setDomain(temp.getDomain());
            }
            if (!temp.testTitle()) {
                temp.setTitle(temp.getTitle());
            }
            if (!temp.testLabel(1)) {
                temp.setLabel(1, temp.getLabel(1));
            }
            if (!temp.testSymbol(1)) {
                temp.setSymbol(1, temp.getSymbol(1));
            }
            if (!temp.testUnit(1)) {
                temp.setUnit(1, temp.getUnit(1));
            }
            temp.clearSystem();
            temp.clearAlignSystem();
            match = temp.frameSubFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
        }
        if (!match) {
            map[0] = null;
            result[0] = null;
        }
        return match;
    }

    /** The Frame class's {@code SubFrame}, for a copy of this TimeFrame. */
    private boolean frameSubFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        return super.subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
    }

    /**
     * Overlays the attributes set on this TimeFrame, the template, onto another Frame (C's {@code
     * Overlay}, timeframe.c). Onto a TimeFrame, a change of System set on the template first
     * clears the result's Title, Label and Symbol; then the Frame attributes, and the
     * AlignTimeScale, LTOffset, TimeOrigin and TimeScale when set, are overlaid, the TimeOrigin
     * before the TimeScale, which re-expresses it. Onto another class of Frame the System and
     * AlignSystem are not overlaid: they are cleared on the template meanwhile, with what that
     * does to a TimeFrame (a C quirk, followed here).
     */
    @Override
    void overlay(int[] templateAxes, Frame result) {
        int oldSystem = result.systemValue();
        int newSystem = systemValue();
        int newAlignSystem = AST__BADSYSTEM;
        boolean resetSystem = false;
        boolean timeframe = result instanceof TimeFrame;
        if (timeframe) {
            if (newSystem != oldSystem && testSystem()) {
                result.clearTitle();
                result.clearLabel(1);
                result.clearSymbol(1);
            }
        } else if (testSystem()) {
            clearSystem();
            newAlignSystem = alignSystemValue();
            clearAlignSystem();
            resetSystem = true;
        }
        super.overlay(templateAxes, result);
        if (resetSystem) {
            setSystemValue(newSystem);
            setAlignSystemValue(newAlignSystem);
        }
        if (timeframe) {
            TimeFrame res = (TimeFrame) result;
            if (testAlignTimeScale()) {
                res.setAlignTimeScaleValue(alignTimeScaleValue());
            }
            if (testLTOffset()) {
                res.setLtOffset(getLtOffset());
            }
            if (testTimeOrigin()) {
                res.setTimeOriginValue(getTimeOriginValue());
            }
            if (testTimeScale()) {
                res.setTimeScaleValue(timeScaleValue());
            }
        }
    }

    // ------------------------------------------------------------------
    // The conversion FitsChan uses
    // ------------------------------------------------------------------

    /**
     * Converts an MJD between TDB and another time scale, as C's {@code TDBConv} (fitschan.c)
     * does: through the conversion {@code convert} finds from an MJD TimeFrame in TDB to one in
     * {@code scale}.
     *
     * @param mjd the MJD, or {@link #AST__BAD}
     * @param scale the other time scale, an {@code AST__} time scale code
     * @param fromTdb true to convert from TDB to {@code scale}, false for the reverse
     * @return the converted MJD, or {@link #AST__BAD} if {@code mjd} is bad
     */
    static double tdbConv(double mjd, int scale, boolean fromTdb) {
        if (mjd == AST__BAD) {
            return AST__BAD;
        }
        if (scale == AST__TDB) {
            return mjd;
        }
        TimeFrame tdbframe = new TimeFrame();
        tdbframe.setSystemValue(AST__MJD);
        tdbframe.setTimeScaleValue(AST__TDB);
        TimeFrame timeframe = new TimeFrame();
        timeframe.setSystemValue(AST__MJD);
        timeframe.setTimeScaleValue(scale);
        FrameSet fs = tdbframe.convert(timeframe, "");
        return fs.tran1(1, new double[] {mjd}, fromTdb)[0];
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    /** Whether a name is one of the per-axis Frame attributes allowed without an axis number. */
    private static boolean isOneAxisName(String attrib) {
        for (String n : ONE_AXIS_NAMES) {
            if (n.equals(attrib)) {
                return true;
            }
        }
        return false;
    }

    @Override
    String getAttrib(String attrib) {
        if (isOneAxisName(attrib)) {
            return super.getAttrib(attrib + "(1)");
        } else if ("aligntimescale".equals(attrib)) {
            return checkedTimeScaleString(alignTimeScaleValue(), "AlignTimeScale");
        } else if ("clocklat".equals(attrib)) {
            return getAttrib("obslat");
        } else if ("clocklon".equals(attrib)) {
            return getAttrib("obslon");
        } else if ("timeorigin".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", getTimeOriginCur());
        } else if ("ltoffset".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", getLtOffset());
        } else if ("timescale".equals(attrib)) {
            return checkedTimeScaleString(timeScaleValue(), "TimeScale");
        }
        return super.getAttrib(attrib);
    }

    /** The name of a time scale code, or the error C's {@code GetAttrib} reports for a corrupt one. */
    private String checkedTimeScaleString(int ts, String attribute) {
        String s = timeScaleString(ts);
        if (s == null) {
            throw Ast.err(
                    AstException.AST__SCSIN,
                    "astGetAttrib(" + astClass() + "): Corrupt " + astClass() + " contains invalid " + attribute
                            + " identification code (" + ts + ").");
        }
        return s;
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

    /**
     * C's {@code sscanf( value, " %lg %n%*s %n" )} reading all of the value: a number, then one
     * word after white space. Gives the number and the index of the word, or null.
     */
    private static double[] numberThenWord(String value) {
        int i = 0;
        int n = value.length();
        while (i < n && CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        CScan.Num num = CScan.strtod(value, i);
        if (num == null) {
            return null;
        }
        i = num.end;
        while (i < n && CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        int off = i;
        while (i < n && !CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        if (i == off) {
            return null;
        }
        while (i < n && CScan.isCSpace(value.charAt(i))) {
            i++;
        }
        return i == n ? new double[] {num.value, off} : null;
    }

    /** Sets the TimeOrigin from a date, converted to the System (C's {@code SetAttrib}). */
    private void setTimeOriginDate(String value) {
        double mjd;
        try {
            mjd = DateTime.readDateTime(value);
        } catch (AstException e) {
            AstException err = Ast.err(
                    AstException.AST__ATTIN,
                    e.getMsgText() + "\nastSetAttrib(" + astClass() + "): Invalid TimeOrigin value \"" + value
                            + "\" given.");
            err.initCause(e);
            throw err;
        }
        setTimeOriginValue(fromMjd(mjd));
    }

    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        String w = oneWord(value);
        // C's "%*[^\n]" reading all of the value: one or more characters, none a newline.
        boolean line = !value.isEmpty() && value.indexOf('\n') < 0;
        if (isOneAxisName(attrib)) {
            super.setAttrib(attrib + "(1)", value);
        } else if ("aligntimescale".equals(attrib) && w != null) {
            int ts = timeScaleCode(value);
            if (ts == AST__BADTS) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Invalid time scale description \"" + value + "\".");
            }
            setAlignTimeScaleValue(ts);
        } else if ("clocklat".equals(attrib) && hasWord(value)) {
            setAttrib("obslat", value);
        } else if ("clocklon".equals(attrib) && hasWord(value)) {
            setAttrib("obslon", value);
        } else if ("ltoffset".equals(attrib) && dval != null) {
            setLtOffset(dval);
        } else if ("timeorigin".equals(attrib) && dval != null) {
            setTimeOriginValue(toUnits(getUnit(1), dval, "astSetTimeOrigin"));
        } else if ("timeorigin".equals(attrib) && numberThenWord(value) != null) {
            double[] nw = numberThenWord(value);
            double origin;
            try {
                origin = toUnits(value.substring((int) nw[1]), nw[0], "astSetTimeOrigin");
            } catch (AstException e) {
                // C defers error reporting and clears the error, in case the value is a date
                // starting with a number.
                origin = AST__BAD;
            }
            if (origin != AST__BAD) {
                setTimeOriginValue(origin);
            } else if (line) {
                setTimeOriginDate(value);
            }
        } else if ("timeorigin".equals(attrib) && line) {
            setTimeOriginDate(value);
        } else if ("timescale".equals(attrib) && w != null) {
            int ts = timeScaleCode(value);
            if (ts == AST__BADTS) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Invalid time scale description \"" + value + "\".");
            }
            setTimeScaleValue(ts);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        if (isOneAxisName(attrib)) {
            return super.testAttrib(attrib + "(1)");
        } else if ("aligntimescale".equals(attrib)) {
            return testAlignTimeScale();
        } else if ("clocklat".equals(attrib)) {
            return testAttrib("obslat");
        } else if ("clocklon".equals(attrib)) {
            return testAttrib("obslon");
        } else if ("ltoffset".equals(attrib)) {
            return testLTOffset();
        } else if ("timeorigin".equals(attrib)) {
            return testTimeOrigin();
        } else if ("timescale".equals(attrib)) {
            return testTimeScale();
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if (isOneAxisName(attrib)) {
            super.clearAttrib(attrib + "(1)");
        } else if ("aligntimescale".equals(attrib)) {
            clearAlignTimeScale();
        } else if ("clocklat".equals(attrib)) {
            clearAttrib("obslat");
        } else if ("clocklon".equals(attrib)) {
            clearAttrib("obslon");
        } else if ("ltoffset".equals(attrib)) {
            clearLTOffset();
        } else if ("timeorigin".equals(attrib)) {
            clearTimeOrigin();
        } else if ("timescale".equals(attrib)) {
            clearTimeScale();
        } else {
            super.clearAttrib(attrib);
        }
    }
}
