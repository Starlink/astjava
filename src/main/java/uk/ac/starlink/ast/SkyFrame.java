package uk.ac.starlink.ast;

import uk.ac.starlink.ast.internal.erfa.Pn;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * A celestial coordinate system. A SkyFrame has two axes, longitude and latitude, whose values
 * are angles in radians; it describes positions in one of several celestial coordinate systems,
 * chosen by its System attribute and qualified by its Equinox and Epoch, and knows how to convert
 * between them. Values are formatted as angles (for example in hours, minutes and seconds) and
 * distances, angles and offsets are measured on the sphere.
 *
 * <p>The System attribute takes the values {@code FK4}, {@code FK4-NO-E}, {@code FK5}, {@code
 * J2000}, {@code ICRS} (the default), {@code GAPPT}, {@code AZEL}, {@code ECLIPTIC}, {@code
 * HELIOECLIPTIC}, {@code GALACTIC}, {@code SUPERGALACTIC} and {@code Unknown}. The SkyRef,
 * SkyRefP and SkyRefIs attributes describe an offset coordinate system, rotated so that the pole
 * or the origin is at a given position; AlignOffset controls whether two such SkyFrames are
 * aligned in their offset coordinates.
 *
 * <p>Ported from C {@code skyframe.c}, with Rust {@code frames/skyframe.rs}. The SkyFrame holds a
 * {@link SkyAxis} for each axis and reaches its per-axis behaviour (formatting, axis distances and
 * offsets) through {@link #getAxis}, as C does; it overrides the Frame methods C's SkyFrame
 * overrides. Axis numbers count from 1. The Plot-only methods of C's SkyFrame ({@code FrameGrid},
 * {@code Gap}, {@code LineDef} and its family, {@code NormBox}) are not ported.
 */
public class SkyFrame extends Frame {

    /** The FK4 (old Bessel-Newcomb) equatorial System. */
    static final int AST__FK4 = 1;

    /** The FK4 equatorial System with no E-terms of aberration. */
    static final int AST__FK4_NO_E = 2;

    /** The FK5 (Julian) equatorial System. */
    static final int AST__FK5 = 3;

    /** The geocentric apparent equatorial System. */
    static final int AST__GAPPT = 4;

    /** The ecliptic System. */
    static final int AST__ECLIPTIC = 5;

    /** The galactic System. */
    static final int AST__GALACTIC = 6;

    /** The supergalactic System. */
    static final int AST__SUPERGALACTIC = 7;

    /** The ICRS System. */
    static final int AST__ICRS = 8;

    /** The helio-ecliptic System. */
    static final int AST__HELIOECLIPTIC = 9;

    /** The dynamical J2000 equatorial System. */
    static final int AST__J2000 = 10;

    /** An unknown spherical coordinate System. */
    static final int AST__UNKNOWN = 11;

    /** The horizon (azimuth and elevation) System. */
    static final int AST__AZEL = 12;

    /** The SkyRefIs value of an unset attribute. */
    static final int AST__BAD_REF = 0;

    /** SkyRefIs "Pole": the offset coordinate system has its pole at SkyRef. */
    static final int AST__POLE_REF = 1;

    /** SkyRefIs "Origin": the offset coordinate system has its origin at SkyRef. */
    static final int AST__ORIGIN_REF = 2;

    /** SkyRefIs "Ignored": SkyRef is ignored and the SkyFrame is not offset. */
    static final int AST__IGNORED_REF = 3;

    private static final int FIRST_SYSTEM = AST__FK4;
    private static final int LAST_SYSTEM = AST__AZEL;

    private static final String POLE_STRING = "Pole";
    private static final String ORIGIN_STRING = "Origin";
    private static final String IGNORED_STRING = "Ignored";

    /** The longest default Title C's buffer holds ({@code AST__SKYFRAME_GETTITLE_BUFF_LEN}). */
    private static final int GETTITLE_BUFF_LEN = 200;

    /** The digits C writes a full-precision double with ({@code AST__DBL_DIG}). */
    private static final int DBL_DIG = 17;

    /** Ratio of sidereal to solar time (C's {@code SOLSID}). */
    private static final double SOLSID = 1.00273790935;

    /** The speed of light in AU per day (C's {@code C}). */
    private static final double C_AU_PER_DAY = 173.14463331;

    /** C's {@code pi}, {@code palDaf2r( 180, 0, 0.0 )}, which is exactly {@code Math.PI}. */
    private static final double PI = Math.PI;

    /** C's {@code piby2}. */
    private static final double PIBY2 = 0.5 * PI;

    /** The value of an unset integer attribute (C's {@code -INT_MAX}). */
    private static final int UNSET = Axis.UNSET_INT;

    private String projection;
    private double equinox = AST__BAD;
    private int negLon = UNSET;
    private double skytol = AST__BAD;
    private int alignOffset = UNSET;
    private int skyRefIs = AST__BAD_REF;
    private double[] skyRef = {AST__BAD, AST__BAD};
    private double[] skyRefP = {AST__BAD, AST__BAD};

    /** The LAST at Epoch {@link #eplast}, or bad (C's {@code last}). */
    private double last = AST__BAD;

    /** The Epoch (TDB MJD) of {@link #last}, or bad (C's {@code eplast}). */
    private double eplast = AST__BAD;

    /** The ratio of solar to sidereal time used to extrapolate the LAST, or bad (C's {@code klast}). */
    private double klast = AST__BAD;

    /** The magnitude of the diurnal aberration vector, or bad (C's {@code diurab}). */
    private double diurab = AST__BAD;

    /**
     * Creates a SkyFrame describing ICRS coordinates, with every attribute, the System included,
     * at its default. Its two axes are longitude and latitude, in that order.
     */
    // C's astInitSkyFrame stores the SkyAxis objects through astSetAxis.
    @SuppressWarnings({"this-escape", "PMD.ConstructorCallsOverridableMethod"})
    public SkyFrame() {
        super(2);
        setAxis(1, new SkyAxis());
        setAxis(2, new SkyAxis());
    }

    /**
     * Loads a SkyFrame from its description, as C's {@code astLoadSkyFrame} does: AlOff, SRefIs,
     * SRef1, SRef2, SRefP1, SRefP2, Proj, Eqnox (a Besselian year before 1984, Julian after),
     * NegLon and SkyTol, each unset if absent, and a System and Epoch written in the SkyFrame's own
     * part by an old version, used only if the Frame's are not set.
     *
     * @param node the description
     * @throws AstException with status {@code AST__INTER} if SRefIs is not a known value
     */
    // C's loader passes some values through the class's own setters.
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    SkyFrame(NativeNode node) {
        super(node);
        NativeNode part = node.section("SkyFrame");
        Long ival = part.getIntIfSet("AlOff");
        if (ival != null) {
            alignOffset = ival != 0 ? 1 : 0;
        }
        String sval = part.getStringIfSet("SRefIs");
        if (sval != null) {
            if (chrMatch(sval, POLE_STRING)) {
                skyRefIs = AST__POLE_REF;
            } else if (chrMatch(sval, ORIGIN_STRING)) {
                skyRefIs = AST__ORIGIN_REF;
            } else if (chrMatch(sval, IGNORED_STRING)) {
                skyRefIs = AST__IGNORED_REF;
            } else if (!chrMatch(sval, " ")) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astRead(SkyFrame): Corrupt SkyFrame contains invalid SkyRefIs attribute value (" + sval
                                + ").");
            }
        }
        skyRef[0] = part.readDouble("SRef1", AST__BAD);
        skyRef[1] = part.readDouble("SRef2", AST__BAD);
        skyRefP[0] = part.readDouble("SRefP1", AST__BAD);
        skyRefP[1] = part.readDouble("SRefP2", AST__BAD);
        if (!testSystem()) {
            sval = part.getStringIfSet("System");
            if (sval != null) {
                setAttrib("system", sval);
            }
        }
        if (!testEpoch()) {
            double dval = part.readDouble("Epoch", AST__BAD);
            if (dval != AST__BAD) {
                setAttrib("epoch", (dval < 1984.0 ? "B" : "J") + CFormat.format("%." + DBL_DIG + "g", dval));
            }
        }
        projection = part.getStringIfSet("Proj");
        double year = part.readDouble("Eqnox", AST__BAD);
        if (year != AST__BAD) {
            setEquinoxMjd(year < 1984.0 ? Pal.epb2d(year) : Pal.epj2d(year));
        }
        ival = part.getIntIfSet("NegLon");
        if (ival != null) {
            negLon = ival != 0 ? 1 : 0;
        }
        double tol = part.readDouble("SkyTol", AST__BAD);
        if (tol != AST__BAD) {
            skytol = Math.abs(tol);
        }
    }

    @Override
    String dumpComment() {
        return "Description of celestial coordinate system";
    }

    /** Writes the SkyFrame class's part of a description, as C's {@code Dump} (skyframe.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        int[] perm = getPerm();
        int[] invperm = new int[2];
        for (int axis = 0; axis < 2; axis++) {
            invperm[perm[axis]] = axis;
        }
        node.writeString("Proj", testProjection(), false, getProjection(), "Description of sky projection");
        boolean flag = getNegLon();
        node.writeInt(
                "NegLon",
                testNegLon(),
                false,
                flag ? 1 : 0,
                flag ? "Display negative longitude values" : "Display positive longitude values");
        node.writeDouble("SkyTol", testSkyTol(), true, getSkyTol(), "Smallest significant separation [arc-sec]");
        double dval = getEquinoxMjd();
        int system = systemValue();
        boolean helpful =
                system == AST__FK4 || system == AST__FK4_NO_E || system == AST__FK5 || system == AST__ECLIPTIC;
        boolean bessyr = dval < Pal.epj2d(1984.0);
        dval = bessyr ? Pal.epb(dval) : Pal.epj(dval);
        node.writeDouble(
                "Eqnox",
                testEquinox(),
                helpful,
                dval,
                bessyr ? "Besselian epoch of mean equinox" : "Julian epoch of mean equinox");
        int ref = getSkyRefIsValue();
        if (ref == AST__POLE_REF) {
            node.writeString("SRefIs", testSkyRefIs(), false, POLE_STRING, "Rotated to put pole at ref. pos.");
        } else if (ref == AST__IGNORED_REF) {
            node.writeString("SRefIs", testSkyRefIs(), false, IGNORED_STRING, "Not rotated (ref. pos. is ignored)");
        } else {
            node.writeString("SRefIs", testSkyRefIs(), false, ORIGIN_STRING, "Rotated to put origin at ref. pos.");
        }
        for (int axisP = 0; axisP < 2; axisP++) {
            int axis = invperm[axisP] + 1;
            dval = getSkyRef(axis);
            node.writeDouble(
                    "SRef" + (axisP + 1),
                    testSkyRef(axis),
                    false,
                    dval,
                    "Ref. pos. " + getSymbol(axis) + " " + format(axis, dval));
        }
        for (int axisP = 0; axisP < 2; axisP++) {
            int axis = invperm[axisP] + 1;
            dval = getSkyRefP(axis);
            node.writeDouble(
                    "SRefP" + (axisP + 1),
                    testSkyRefP(axis),
                    false,
                    dval,
                    "Ref. north " + getSymbol(axis) + " " + format(axis, dval));
        }
        flag = getAlignOffset();
        node.writeInt(
                "AlOff",
                testAlignOffset(),
                false,
                flag ? 1 : 0,
                flag ? "Align in offset coords" : "Align in system coords");
    }

    /**
     * Completes a copy as C's {@code Copy} (skyframe.c) does. C's copy starts as a byte copy of
     * the structure, so the cached LAST and diurnal aberration go with it.
     */
    @Override
    void deepCopy() {
        super.deepCopy();
        skyRef = skyRef.clone();
        skyRefP = skyRefP.clone();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The internal axis, numbered from 0 (0 longitude, 1 latitude), of an axis numbered from 1. */
    private int axisP(int axis, String method) {
        return validateAxis(axis, true, method) - 1;
    }

    /** Whether a System is equatorial (C's {@code IsEquatorial}). */
    static boolean isEquatorial(int system) {
        return system == AST__FK4
                || system == AST__FK4_NO_E
                || system == AST__ICRS
                || system == AST__FK5
                || system == AST__J2000
                || system == AST__GAPPT;
    }

    /** Whether SkyRef is set and used, so that the SkyFrame describes offset coordinates. */
    private boolean isOffset() {
        return getSkyRefIsValue() != AST__IGNORED_REF && (testSkyRef(1) || testSkyRef(2));
    }

    /** Discards the cached LAST, and the diurnal aberration too if {@code alsoDiurab}. */
    private void discardLast(boolean alsoDiurab) {
        last = AST__BAD;
        eplast = AST__BAD;
        klast = AST__BAD;
        if (alsoDiurab) {
            diurab = AST__BAD;
        }
    }

    /** C's {@code EQUAL}: two bad values are equal, and two good ones within {@code tol}. */
    static boolean equal(double aa, double bb, double tol) {
        if (aa == AST__BAD) {
            return bb == AST__BAD;
        }
        return bb != AST__BAD && Math.abs(aa - bb) <= tol;
    }

    /** The error for a corrupt System code, as C's per-attribute functions report it. */
    private AstException corruptSystem(String method, int system) {
        return Ast.err(
                AstException.AST__SCSIN,
                method + "(" + astClass() + "): Corrupt " + astClass()
                        + " contains invalid sky coordinate system identification code (" + system + ").");
    }

    // ------------------------------------------------------------------
    // Equinox
    // ------------------------------------------------------------------

    /**
     * Gets the Equinox attribute: the epoch of the mean equinox that the FK4, FK4-NO-E, FK5 and
     * ECLIPTIC systems are referred to, as a Besselian year before 1984 and a Julian year from then
     * on. The default is B1950.0 for the FK4 systems and J2000.0 for the others.
     *
     * @return the Equinox
     */
    public double getEquinox() {
        return getD("Equinox");
    }

    /**
     * Sets the Equinox attribute from a year: Besselian before 1984 and Julian from then on. It is
     * ignored for the HELIOECLIPTIC system, whose equinox is J2000.0.
     *
     * @param equinox the year
     */
    public void setEquinox(double equinox) {
        setD("Equinox", equinox);
    }

    /** The Equinox as a Modified Julian Date: set, or the default for the System. */
    double getEquinoxMjd() {
        if (equinox != AST__BAD) {
            return equinox;
        }
        int system = systemValue();
        return system == AST__FK4 || system == AST__FK4_NO_E ? Pal.epb2d(1950.0) : Pal.epj2d(2000.0);
    }

    /** Sets the Equinox as a Modified Julian Date; for HELIOECLIPTIC it is left unset. */
    void setEquinoxMjd(double mjd) {
        equinox = systemValue() != AST__HELIOECLIPTIC ? mjd : AST__BAD;
    }

    boolean testEquinox() {
        return equinox != AST__BAD;
    }

    void clearEquinox() {
        equinox = AST__BAD;
    }

    // ------------------------------------------------------------------
    // Projection
    // ------------------------------------------------------------------

    /**
     * Gets the Projection attribute: a description of the sky projection used to display the
     * coordinates, which is added to the default Title. The default is blank.
     *
     * @return the Projection
     */
    public String getProjection() {
        return projection != null ? projection : "";
    }

    /**
     * Sets the Projection attribute.
     *
     * @param projection the Projection
     */
    public void setProjection(String projection) {
        this.projection = projection;
    }

    boolean testProjection() {
        return projection != null;
    }

    void clearProjection() {
        projection = null;
    }

    // ------------------------------------------------------------------
    // NegLon
    // ------------------------------------------------------------------

    /**
     * Gets the NegLon attribute: whether longitude values are normalised into the range -pi to pi
     * rather than 0 to 2pi. The default is true for offset coordinates whose SkyRefIs is Origin,
     * and false otherwise.
     *
     * @return the NegLon
     */
    public boolean getNegLon() {
        if (negLon != UNSET) {
            return negLon != 0;
        }
        return getSkyRefIsValue() == AST__ORIGIN_REF;
    }

    /**
     * Sets the NegLon attribute.
     *
     * @param negLon the NegLon
     */
    public void setNegLon(boolean negLon) {
        this.negLon = negLon ? 1 : 0;
    }

    boolean testNegLon() {
        return negLon != UNSET;
    }

    void clearNegLon() {
        negLon = UNSET;
    }

    // ------------------------------------------------------------------
    // SkyTol
    // ------------------------------------------------------------------

    /**
     * The SkyTol attribute: the smallest significant shift in sky position, in arc-seconds; a
     * conversion between celestial systems that shifts no position by more is a UnitMap. The
     * default is 0.001.
     */
    double getSkyTol() {
        return skytol != AST__BAD ? skytol : 0.001;
    }

    /** Sets SkyTol to the absolute value given. */
    void setSkyTol(double value) {
        skytol = Math.abs(value);
    }

    boolean testSkyTol() {
        return skytol != AST__BAD;
    }

    void clearSkyTol() {
        skytol = AST__BAD;
    }

    // ------------------------------------------------------------------
    // AlignOffset
    // ------------------------------------------------------------------

    /**
     * Gets the AlignOffset attribute: whether two SkyFrames that both describe offset coordinates
     * are aligned in those offset coordinates, so that converting between them leaves offsets
     * unchanged. The default is false, aligning them in their celestial coordinates.
     *
     * @return the AlignOffset
     */
    public boolean getAlignOffset() {
        return alignOffset != UNSET && alignOffset != 0;
    }

    /**
     * Sets the AlignOffset attribute.
     *
     * @param alignOffset the AlignOffset
     */
    public void setAlignOffset(boolean alignOffset) {
        this.alignOffset = alignOffset ? 1 : 0;
    }

    boolean testAlignOffset() {
        return alignOffset != UNSET;
    }

    void clearAlignOffset() {
        alignOffset = UNSET;
    }

    // ------------------------------------------------------------------
    // SkyRefIs
    // ------------------------------------------------------------------

    /**
     * Gets the SkyRefIs attribute: how SkyRef is used. {@code Pole} gives offset coordinates with
     * their pole at SkyRef, {@code Origin} gives offset coordinates with their origin at SkyRef,
     * and {@code Ignored} (the default) gives no offset coordinates.
     *
     * @return the SkyRefIs
     */
    public String getSkyRefIs() {
        return getC("SkyRefIs");
    }

    /**
     * Sets the SkyRefIs attribute, ignoring case.
     *
     * @param skyRefIs {@code Pole}, {@code Origin} or {@code Ignored}
     * @throws AstException with status {@code AST__OPT} for any other value
     */
    public void setSkyRefIs(String skyRefIs) {
        setC("SkyRefIs", skyRefIs);
    }

    /** The SkyRefIs code: set, or {@link #AST__IGNORED_REF}. */
    int getSkyRefIsValue() {
        return skyRefIs == AST__BAD_REF ? AST__IGNORED_REF : skyRefIs;
    }

    void setSkyRefIsValue(int value) {
        skyRefIs = value;
    }

    boolean testSkyRefIs() {
        return skyRefIs != AST__BAD_REF;
    }

    void clearSkyRefIs() {
        skyRefIs = AST__BAD_REF;
    }

    // ------------------------------------------------------------------
    // SkyRef and SkyRefP
    // ------------------------------------------------------------------

    /**
     * Gets the SkyRef attribute as a formatted position: the reference position of the offset
     * coordinate system, its two axis values separated by a comma.
     *
     * @return the SkyRef
     */
    public String getSkyRef() {
        return getC("SkyRef");
    }

    /**
     * Sets the SkyRef attribute from a position: two axis values, as {@link #unformat} reads
     * them, separated by a comma.
     *
     * @param skyRef the position
     * @throws AstException with status {@code AST__BADOC} if the string cannot be read
     */
    public void setSkyRef(String skyRef) {
        setC("SkyRef", skyRef);
    }

    /**
     * Gets the SkyRef value of an axis, in radians. The default is zero.
     *
     * @param axis the axis number, from 1
     * @return the value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double getSkyRef(int axis) {
        int p = axisP(axis, "astGetSkyRef");
        return skyRef[p] != AST__BAD ? skyRef[p] : 0.0;
    }

    /**
     * Sets the SkyRef value of an axis, in radians.
     *
     * @param axis the axis number, from 1
     * @param value the value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setSkyRef(int axis, double value) {
        skyRef[axisP(axis, "astSetSkyRef")] = value;
    }

    boolean testSkyRef(int axis) {
        return skyRef[axisP(axis, "astTestSkyRef")] != AST__BAD;
    }

    void clearSkyRef(int axis) {
        skyRef[axisP(axis, "astClearSkyRef")] = AST__BAD;
    }

    /**
     * Gets the SkyRefP attribute as a formatted position: a point on the primary meridian of the
     * offset coordinate system, its two axis values separated by a comma.
     *
     * @return the SkyRefP
     */
    public String getSkyRefP() {
        return getC("SkyRefP");
    }

    /**
     * Sets the SkyRefP attribute from a position, as {@link #setSkyRef(String)} reads one.
     *
     * @param skyRefP the position
     * @throws AstException with status {@code AST__BADOC} if the string cannot be read
     */
    public void setSkyRefP(String skyRefP) {
        setC("SkyRefP", skyRefP);
    }

    /**
     * Gets the SkyRefP value of an axis, in radians. The default is the north pole, (0, pi/2),
     * unless SkyRef is at a pole, when it is (0, 0).
     *
     * @param axis the axis number, from 1
     * @return the value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public double getSkyRefP(int axis) {
        int p = axisP(axis, "astGetSkyRefP");
        if (skyRefP[p] != AST__BAD) {
            return skyRefP[p];
        } else if (p == 0) {
            return 0.0;
        } else if (Math.abs(Math.cos(skyRef[1])) > 1.0e-10) {
            return PI / 2;
        }
        return 0.0;
    }

    /**
     * Sets the SkyRefP value of an axis, in radians.
     *
     * @param axis the axis number, from 1
     * @param value the value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setSkyRefP(int axis, double value) {
        skyRefP[axisP(axis, "astSetSkyRefP")] = value;
    }

    boolean testSkyRefP(int axis) {
        return skyRefP[axisP(axis, "astTestSkyRefP")] != AST__BAD;
    }

    void clearSkyRefP(int axis) {
        skyRefP[axisP(axis, "astClearSkyRefP")] = AST__BAD;
    }

    // ------------------------------------------------------------------
    // AsTime
    // ------------------------------------------------------------------

    /**
     * Gets the AsTime attribute of an axis: whether its values are formatted as times (hours,
     * minutes and seconds) rather than angles. The default is true for the longitude axis of an
     * equatorial system and false otherwise.
     *
     * @param axis the axis number, from 1
     * @return the AsTime
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public boolean getAsTime(int axis) {
        int p = axisP(axis, "astGetAsTime");
        Axis ax = getAxis(axis);
        if (ax instanceof SkyAxis && ((SkyAxis) ax).testAxisAsTime()) {
            return ((SkyAxis) ax).getAxisAsTime();
        }
        return p == 0 && isEquatorial(systemValue());
    }

    /**
     * Sets the AsTime attribute of an axis. An axis that is not a sky axis is first replaced by
     * one with its attributes.
     *
     * @param axis the axis number, from 1
     * @param asTime the AsTime
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    public void setAsTime(int axis, boolean asTime) {
        validateAxis(axis, true, "astSetAsTime");
        Axis ax = getAxis(axis);
        SkyAxis sax;
        if (ax instanceof SkyAxis) {
            sax = (SkyAxis) ax;
        } else {
            sax = new SkyAxis();
            ax.axisOverlay(sax);
            setAxis(axis, sax);
        }
        sax.setAxisAsTime(asTime);
    }

    boolean testAsTime(int axis) {
        validateAxis(axis, true, "astTestAsTime");
        Axis ax = getAxis(axis);
        return ax instanceof SkyAxis && ((SkyAxis) ax).testAxisAsTime();
    }

    void clearAsTime(int axis) {
        validateAxis(axis, true, "astClearAsTime");
        Axis ax = getAxis(axis);
        if (ax instanceof SkyAxis) {
            ((SkyAxis) ax).clearAxisAsTime();
        }
    }

    // ------------------------------------------------------------------
    // Latitude and longitude axes
    // ------------------------------------------------------------------

    /**
     * Gets the LatAxis attribute: the number of the latitude axis.
     *
     * @return the latitude axis, from 1
     */
    public int getLatAxis() {
        return getPerm()[0] == 1 ? 1 : 2;
    }

    /**
     * Gets the LonAxis attribute: the number of the longitude axis.
     *
     * @return the longitude axis, from 1
     */
    public int getLonAxis() {
        return getPerm()[0] == 0 ? 1 : 2;
    }

    /** Whether axis {@code axis}, from 1, is the latitude axis (the IsLatAxis attribute). */
    boolean getIsLatAxis(int axis) {
        return axis == getLatAxis();
    }

    /** Whether axis {@code axis}, from 1, is the longitude axis (the IsLonAxis attribute). */
    boolean getIsLonAxis(int axis) {
        return axis == getLonAxis();
    }

    // ------------------------------------------------------------------
    // Frame attributes with SkyFrame defaults
    // ------------------------------------------------------------------

    /** The Domain: set, or {@code SKY}. */
    @Override
    public String getDomain() {
        return testDomain() ? super.getDomain() : "SKY";
    }

    /** The Epoch as an MJD: set, or B1950.0 for the FK4 systems and J2000.0 for the others. */
    @Override
    double getEpochMjd() {
        if (testEpoch()) {
            return super.getEpochMjd();
        }
        int system = systemValue();
        return system == AST__FK4 || system == AST__FK4_NO_E ? Pal.epb2d(1950.0) : Pal.epj2d(2000.0);
    }

    @Override
    int systemValue() {
        return testSystem() ? super.systemValue() : AST__ICRS;
    }

    @Override
    int alignSystemValue() {
        return testAlignSystem() ? super.alignSystemValue() : AST__ICRS;
    }

    /**
     * Sets the System code (C's {@code SetSystem}), re-expressing a set SkyRef and SkyRefP in the
     * new System so that they keep describing the same positions, or clearing them if the old and
     * new Systems cannot be converted.
     */
    @Override
    void setSystemValue(int value) {
        boolean skyrefSet = testSkyRef(1) || testSkyRef(2);
        boolean skyrefpSet = testSkyRefP(1) || testSkyRefP(2);
        SkyFrame sfrm = skyrefSet || skyrefpSet ? (SkyFrame) copy() : null;
        super.setSystemValue(value);
        if (sfrm != null) {
            boolean aloffSet = sfrm.testAlignOffset();
            boolean aloff = sfrm.getAlignOffset();
            boolean refisSet = sfrm.testSkyRefIs();
            int refis = sfrm.getSkyRefIsValue();
            double[] xin = {sfrm.getSkyRef(1), sfrm.getSkyRefP(1)};
            double[] yin = {sfrm.getSkyRef(2), sfrm.getSkyRefP(2)};
            if (skyrefSet) {
                sfrm.clearSkyRef(1);
                sfrm.clearSkyRef(2);
                clearSkyRef(1);
                clearSkyRef(2);
            }
            if (skyrefpSet) {
                sfrm.clearSkyRefP(1);
                sfrm.clearSkyRefP(2);
                clearSkyRefP(1);
                clearSkyRefP(2);
            }
            sfrm.setAlignOffset(false);
            sfrm.setSkyRefIsValue(AST__IGNORED_REF);
            FrameSet fs = sfrm.convert(this, "");
            if (fs == null) {
                if (skyrefSet) {
                    clearSkyRef(1);
                    clearSkyRef(2);
                }
                if (skyrefpSet) {
                    clearSkyRefP(1);
                    clearSkyRefP(2);
                }
            } else {
                double[][] out = fs.tran2(2, xin, yin, true);
                if (skyrefSet) {
                    setSkyRef(1, out[0][0]);
                    setSkyRef(2, out[1][0]);
                }
                if (skyrefpSet) {
                    setSkyRefP(1, out[0][1]);
                    setSkyRefP(2, out[1][1]);
                }
                if (aloffSet) {
                    setAlignOffset(aloff);
                } else {
                    clearAlignOffset();
                }
                if (refisSet) {
                    setSkyRefIsValue(refis);
                } else {
                    clearSkyRefIs();
                }
            }
        }
    }

    /**
     * Clears the System (C's {@code ClearSystem}), re-expressing a set SkyRef and SkyRefP in the
     * default System, or clearing them if the Systems cannot be converted.
     */
    @Override
    void clearSystem() {
        boolean skyrefSet = testSkyRef(1) || testSkyRef(2);
        boolean skyrefpSet = testSkyRefP(1) || testSkyRefP(2);
        SkyFrame sfrm = skyrefSet || skyrefpSet ? (SkyFrame) copy() : null;
        super.clearSystem();
        if (sfrm != null) {
            double[] xin = {sfrm.getSkyRef(1), sfrm.getSkyRefP(1)};
            double[] yin = {sfrm.getSkyRef(2), sfrm.getSkyRefP(2)};
            if (skyrefSet) {
                sfrm.clearSkyRef(1);
                sfrm.clearSkyRef(2);
                clearSkyRef(1);
                clearSkyRef(2);
            }
            FrameSet fs = sfrm.convert(this, "");
            if (fs != null) {
                double[][] out = fs.tran2(2, xin, yin, true);
                if (skyrefSet) {
                    setSkyRef(1, out[0][0]);
                    setSkyRef(2, out[1][0]);
                }
                if (skyrefpSet) {
                    setSkyRefP(1, out[0][1]);
                    setSkyRefP(2, out[1][1]);
                }
            } else {
                if (skyrefSet) {
                    clearSkyRef(1);
                    clearSkyRef(2);
                }
                if (skyrefpSet) {
                    clearSkyRefP(1);
                    clearSkyRefP(2);
                }
            }
        }
    }

    /** The System code a name gives, ignoring case and trailing white space (C's {@code SystemCode}). */
    @Override
    int systemCode(String name) {
        if (chrMatch("FK4", name)) {
            return AST__FK4;
        } else if (chrMatch("FK4_NO_E", name) || chrMatch("FK4-NO-E", name)) {
            return AST__FK4_NO_E;
        } else if (chrMatch("FK5", name) || chrMatch("Equatorial", name)) {
            return AST__FK5;
        } else if (chrMatch("J2000", name)) {
            return AST__J2000;
        } else if (chrMatch("ICRS", name)) {
            return AST__ICRS;
        } else if (chrMatch("AZEL", name)) {
            return AST__AZEL;
        } else if (chrMatch("GAPPT", name) || chrMatch("GEOCENTRIC", name) || chrMatch("APPARENT", name)) {
            return AST__GAPPT;
        } else if (chrMatch("ECLIPTIC", name)) {
            return AST__ECLIPTIC;
        } else if (chrMatch("HELIOECLIPTIC", name)) {
            return AST__HELIOECLIPTIC;
        } else if (chrMatch("GALACTIC", name)) {
            return AST__GALACTIC;
        } else if (chrMatch("SUPERGALACTIC", name)) {
            return AST__SUPERGALACTIC;
        } else if (chrMatch("UNKNOWN", name)) {
            return AST__UNKNOWN;
        }
        return AST__BADSYSTEM;
    }

    /** The name of a System code, or null (C's {@code SystemString}). */
    @Override
    String systemString(int code) {
        switch (code) {
            case AST__FK4:
                return "FK4";
            case AST__FK4_NO_E:
                return "FK4-NO-E";
            case AST__FK5:
                return "FK5";
            case AST__J2000:
                return "J2000";
            case AST__ICRS:
                return "ICRS";
            case AST__GAPPT:
                return "GAPPT";
            case AST__AZEL:
                return "AZEL";
            case AST__ECLIPTIC:
                return "ECLIPTIC";
            case AST__HELIOECLIPTIC:
                return "HELIOECLIPTIC";
            case AST__GALACTIC:
                return "GALACTIC";
            case AST__SUPERGALACTIC:
                return "SUPERGALACTIC";
            case AST__UNKNOWN:
                return "Unknown";
            default:
                return null;
        }
    }

    /**
     * Checks a System code (C's {@code ValidateSystem}).
     *
     * @throws AstException with status {@code AST__AXIIN} if it is not a celestial System
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
     * Gets the Title: set, or a description of the celestial coordinate system made from the
     * System, the Equinox and Epoch where they matter (left out when unset if UseDefs is false),
     * and the offset reference position or the Projection.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        if (testTitle()) {
            return super.getTitle();
        }
        double ep = getEpochMjd();
        double eq = getEquinoxMjd();
        String proj = getProjection();
        int system = systemValue();
        boolean offset = (testSkyRef(1) || testSkyRef(2)) && getSkyRefIsValue() != AST__IGNORED_REF;
        String word = offset ? "offsets" : "coordinates";
        boolean useDefs = getUseDefs();
        StringBuilder sb = new StringBuilder();
        switch (system) {
            case AST__FK4:
                sb.append("FK4 equatorial ").append(word);
                if (testEquinox() || useDefs) {
                    sb.append("; mean equinox B").append(DateTime.fmtDecimalYr(Pal.epb(eq), 9));
                }
                if (testEpoch() || useDefs) {
                    sb.append("; epoch B").append(DateTime.fmtDecimalYr(Pal.epb(ep), 9));
                }
                break;
            case AST__FK4_NO_E:
                sb.append("FK4 equatorial ").append(word).append("; no E-terms");
                if (testEquinox() || useDefs) {
                    sb.append("; mean equinox B").append(DateTime.fmtDecimalYr(Pal.epb(eq), 9));
                }
                if (testEpoch() || useDefs) {
                    sb.append("; epoch B").append(DateTime.fmtDecimalYr(Pal.epb(ep), 9));
                }
                break;
            case AST__FK5:
                sb.append("FK5 equatorial ").append(word);
                if (testEquinox() || useDefs) {
                    sb.append("; mean equinox J").append(DateTime.fmtDecimalYr(Pal.epj(eq), 9));
                }
                break;
            case AST__J2000:
                sb.append("J2000 equatorial ").append(word);
                break;
            case AST__ICRS:
                sb.append("ICRS ").append(word);
                break;
            case AST__AZEL:
                sb.append("Horizon (Azimuth/Elevation) ").append(word);
                break;
            case AST__GAPPT:
                // C writes two semicolons here.
                sb.append("Geocentric apparent equatorial ")
                        .append(word)
                        .append("; ; epoch J")
                        .append(DateTime.fmtDecimalYr(Pal.epj(ep), 9));
                break;
            case AST__ECLIPTIC:
                sb.append("Ecliptic ").append(word);
                if (testEquinox() || useDefs) {
                    sb.append("; mean equinox J").append(DateTime.fmtDecimalYr(Pal.epj(eq), 9));
                }
                break;
            case AST__HELIOECLIPTIC:
                sb.append("Helio-ecliptic ").append(word).append("; mean equinox J2000");
                if (testEpoch() || useDefs) {
                    sb.append("; epoch J").append(DateTime.fmtDecimalYr(Pal.epj(ep), 9));
                }
                break;
            case AST__GALACTIC:
                sb.append("IAU (1958) galactic ").append(word);
                break;
            case AST__SUPERGALACTIC:
                sb.append("De Vaucouleurs supergalactic ").append(word);
                break;
            case AST__UNKNOWN:
                sb.append("Spherical ").append(word);
                break;
            default:
                throw corruptSystem("astGetTitle", system);
        }
        String extra;
        boolean isProjection;
        if (offset) {
            String w = getSkyRefIsValue() == AST__POLE_REF ? "pole" : "origin";
            extra = w + " at " + format(1, getSkyRef(1)) + " " + format(2, getSkyRef(2));
            isProjection = false;
        } else {
            int l = proj.length();
            while (l > 0 && CScan.isCSpace(proj.charAt(l - 1))) {
                l--;
            }
            extra = proj.substring(0, l);
            isProjection = true;
        }
        if (!extra.isEmpty()) {
            appendCapped(sb, "; ");
            appendCapped(sb, extra);
            if (isProjection) {
                appendCapped(sb, " projection");
            }
        }
        return sb.toString();
    }

    /** Appends to a Title as C does, stopping at the length of its buffer. */
    private static void appendCapped(StringBuilder sb, String s) {
        int room = GETTITLE_BUFF_LEN - sb.length();
        if (room > 0) {
            sb.append(s, 0, Math.min(room, s.length()));
        }
    }

    /**
     * Gets the Label of an axis: set, or a description of the axis for the System, such as {@code
     * Right ascension}, followed by {@code offset} for offset coordinates.
     */
    @Override
    public String getLabel(int axis) {
        int p = axisP(axis, "astGetLabel");
        if (testLabel(axis)) {
            return super.getLabel(axis);
        }
        int system = systemValue();
        String result;
        if (isEquatorial(system)) {
            result = p == 0 ? "Right ascension" : "Declination";
        } else if (system == AST__ECLIPTIC) {
            result = p == 0 ? "Ecliptic longitude" : "Ecliptic latitude";
        } else if (system == AST__HELIOECLIPTIC) {
            result = p == 0 ? "Helio-ecliptic longitude" : "Helio-ecliptic latitude";
        } else if (system == AST__AZEL) {
            result = p == 0 ? "Azimuth" : "Elevation";
        } else if (system == AST__GALACTIC) {
            result = p == 0 ? "Galactic longitude" : "Galactic latitude";
        } else if (system == AST__SUPERGALACTIC) {
            result = p == 0 ? "Supergalactic longitude" : "Supergalactic latitude";
        } else if (system == AST__UNKNOWN) {
            result = p == 0 ? "Longitude" : "Latitude";
        } else {
            throw corruptSystem("astGetLabel", system);
        }
        if (isOffset()) {
            result = result + " offset";
        }
        return result;
    }

    /**
     * Gets the Symbol of an axis: set, or a short name for the axis in the System, such as {@code
     * RA}, preceded by {@code D} for offset coordinates.
     */
    @Override
    public String getSymbol(int axis) {
        int p = axisP(axis, "astGetSymbol");
        if (testSymbol(axis)) {
            return super.getSymbol(axis);
        }
        int system = systemValue();
        String result;
        if (isEquatorial(system)) {
            result = p == 0 ? "RA" : "Dec";
        } else if (system == AST__ECLIPTIC || system == AST__HELIOECLIPTIC) {
            result = p == 0 ? "Lambda" : "Beta";
        } else if (system == AST__AZEL) {
            result = p == 0 ? "Az" : "El";
        } else if (system == AST__GALACTIC) {
            result = p == 0 ? "l" : "b";
        } else if (system == AST__SUPERGALACTIC) {
            result = p == 0 ? "SGL" : "SGB";
        } else if (system == AST__UNKNOWN) {
            result = p == 0 ? "Lon" : "Lat";
        } else {
            throw corruptSystem("astGetSymbol", system);
        }
        if (isOffset()) {
            result = "D" + result;
        }
        return result;
    }

    /**
     * Gets the Format of an axis (C's {@code GetFormat}): set, or the default the axis's sky
     * Format gives for an equatorial System (sexagesimal), or decimal degrees ({@code d} or
     * {@code d.<n>}) or decimal hours for the other Systems, with the precision the Digits gives.
     */
    @Override
    public String getFormat(int axis) {
        int p = axisP(axis, "astGetFormat");
        Axis ax = getAxis(axis);
        boolean skyaxis = ax instanceof SkyAxis;
        boolean parent = !skyaxis || super.testFormat(axis);
        if (!parent) {
            parent = isEquatorial(systemValue());
        }
        if (parent) {
            if (!skyaxis) {
                return super.getFormat(axis);
            }
            SkyAxis sax = (SkyAxis) ax;
            boolean asTimeSet = testAsTime(axis);
            boolean isLatitudeSet = sax.testAxisIsLatitude();
            boolean isLatitude = sax.getAxisIsLatitude();
            if (!asTimeSet) {
                setAsTime(axis, getAsTime(axis));
            }
            sax.setAxisIsLatitude(p == 1);
            try {
                return super.getFormat(axis);
            } finally {
                if (!asTimeSet) {
                    clearAsTime(axis);
                }
                if (isLatitudeSet) {
                    sax.setAxisIsLatitude(isLatitude);
                } else {
                    sax.clearAxisIsLatitude();
                }
            }
        }
        boolean asTime = getAsTime(axis);
        int digits = ax.testAxisDigits() ? ax.getAxisDigits() : getDigits();
        if (asTime) {
            return digits <= 2 ? "h" : "h." + (digits - 2);
        }
        return digits <= 3 ? "d" : "d." + (digits - 3);
    }

    /**
     * Gets the Unit of an axis: as the axis gives it for the Format in use (C's {@code GetUnit}),
     * which describes sexagesimal values (for example {@code hh:mm:ss}) or decimal degrees or
     * hours.
     */
    @Override
    public String getUnit(int axis) {
        validateAxis(axis, true, "astGetUnit");
        boolean formatSet = super.testFormat(axis);
        if (!formatSet) {
            super.setFormat(axis, getFormat(axis));
        }
        try {
            return super.getUnit(axis);
        } finally {
            if (!formatSet) {
                super.clearFormat(axis);
            }
        }
    }

    /** Gets the NormUnit of an axis: the axis's own, for the Format in use (C's {@code GetNormUnit}). */
    @Override
    public String getNormUnit(int axis) {
        validateAxis(axis, true, "astGetNormUnit");
        boolean formatSet = super.testFormat(axis);
        if (!formatSet) {
            super.setFormat(axis, getFormat(axis));
        }
        try {
            Axis ax = getAxis(axis);
            boolean digitsSet = ax.testAxisDigits();
            if (!digitsSet) {
                ax.setAxisDigits(getDigits());
            }
            try {
                return ax.getAxisNormUnit();
            } finally {
                if (!digitsSet) {
                    ax.clearAxisDigits();
                }
            }
        } finally {
            if (!formatSet) {
                super.clearFormat(axis);
            }
        }
    }

    /** Gets the Direction of an axis: set, or false for the longitude axis and true for the latitude. */
    @Override
    public boolean getDirection(int axis) {
        int p = axisP(axis, "astGetDirection");
        if (testDirection(axis)) {
            return super.getDirection(axis);
        }
        return p != 0;
    }

    /** Gets the Top of an axis: set, or pi/2 for the latitude axis and no limit for the longitude. */
    @Override
    public double getTop(int axis) {
        int p = axisP(axis, "astGetTop");
        if (testTop(axis)) {
            return super.getTop(axis);
        }
        return p == 1 ? PIBY2 : Double.MAX_VALUE;
    }

    /** Gets the Bottom of an axis: set, or -pi/2 for the latitude axis and no limit for the longitude. */
    @Override
    public double getBottom(int axis) {
        int p = axisP(axis, "astGetBottom");
        if (testBottom(axis)) {
            return super.getBottom(axis);
        }
        return p == 1 ? -PIBY2 : -Double.MAX_VALUE;
    }

    /**
     * Gets the ActiveUnit flag, which is always false for a SkyFrame: its Unit describes how
     * values are formatted, not the values, which are always radians.
     *
     * @return false
     */
    @Override
    public boolean getActiveUnit() {
        return false;
    }

    @Override
    boolean testActiveUnit() {
        return false;
    }

    // ------------------------------------------------------------------
    // Observer and time attributes, which invalidate the cached LAST
    // ------------------------------------------------------------------

    @Override
    void setObsLatValue(double value) {
        double orig = getObsLatValue();
        super.setObsLatValue(value);
        if (Math.abs(orig - value) > 1.0e-8) {
            discardLast(true);
        }
    }

    @Override
    void clearObsLat() {
        double orig = getObsLatValue();
        super.clearObsLat();
        if (Math.abs(orig - getObsLatValue()) > 1.0e-8) {
            discardLast(true);
        }
    }

    @Override
    void setObsLonValue(double value) {
        double orig = getObsLonValue();
        super.setObsLonValue(value);
        if (Math.abs(orig - value) > 1.0e-8) {
            discardLast(false);
        }
    }

    @Override
    void clearObsLon() {
        double orig = getObsLonValue();
        super.clearObsLon();
        if (Math.abs(orig - getObsLonValue()) > 1.0e-8) {
            discardLast(false);
        }
    }

    @Override
    void setObsAlt(double value) {
        double orig = getObsAlt();
        super.setObsAlt(value);
        if (Math.abs(orig - value) > 0.001) {
            discardLast(true);
        }
    }

    @Override
    void clearObsAlt() {
        double orig = getObsAlt();
        super.clearObsAlt();
        if (Math.abs(orig - getObsAlt()) > 0.001) {
            discardLast(true);
        }
    }

    @Override
    void setDtai(double value) {
        double orig = getDtai();
        super.setDtai(value);
        if (!equal(orig, value, 1.0e-6)) {
            discardLast(false);
        }
    }

    @Override
    void clearDtai() {
        double orig = getDtai();
        super.clearDtai();
        if (!equal(orig, getDtai(), 1.0e-6)) {
            discardLast(false);
        }
    }

    /**
     * Sets the Dut1 attribute: UT1-UTC in seconds.
     *
     * @param dut1 the Dut1
     */
    @Override
    public void setDut1(double dut1) {
        double orig = getDut1();
        super.setDut1(dut1);
        if (Math.abs(orig - dut1) > 1.0e-6) {
            discardLast(false);
        }
    }

    @Override
    void clearDut1() {
        double orig = getDut1();
        super.clearDut1();
        if (Math.abs(orig - getDut1()) > 1.0e-6) {
            discardLast(false);
        }
    }

    // ------------------------------------------------------------------
    // LAST and diurnal aberration
    // ------------------------------------------------------------------

    /**
     * The Local Apparent Sidereal Time, in radians, at the Epoch and observer (C's {@code
     * GetLAST}). The SkyFrame keeps the LAST it last computed; for an Epoch within 0.4 day of that
     * one the LAST is extrapolated linearly with the ratio of solar to sidereal time, itself found
     * from the LAST 0.4 day later; otherwise it is computed afresh, possibly from the table of LAST
     * values already computed ({@link SkyLast}).
     */
    double getLast() {
        if (eplast != AST__BAD) {
            double epoch = getEpochMjd();
            double deltaEpoch = epoch - eplast;
            if (deltaEpoch == 0.0) {
                return last;
            } else if (Math.abs(deltaEpoch) < 0.4) {
                if (klast == AST__BAD) {
                    double last1 = SkyLast.calcLast(
                            eplast + 0.4, getObsLonValue(), getObsLatValue(), getObsAlt(), getDut1(), getDtai());
                    double dlast = last1 - last;
                    if (dlast < 0.0) {
                        dlast += 2 * PI;
                    }
                    klast = 2 * PI * 0.4 / dlast;
                }
                return last + 2 * PI * deltaEpoch / klast;
            }
        }
        setLast();
        return last;
    }

    /** Computes and keeps the LAST at the Epoch (C's {@code SetLast}). */
    private void setLast() {
        double epoch = getEpochMjd();
        last = SkyLast.calcLast(epoch, getObsLonValue(), getObsLatValue(), getObsAlt(), getDut1(), getDtai());
        eplast = epoch;
        klast = AST__BAD;
    }

    /**
     * The magnitude of the diurnal aberration vector at the observer, in radians, kept until the
     * latitude or altitude changes (C's {@code GetDiurab}).
     */
    double getDiurab() {
        if (diurab == AST__BAD) {
            double[] uv = Pal.geoc(getObsLatValue(), getObsAlt());
            diurab = 2 * PI * uv[0] * SOLSID / C_AU_PER_DAY;
        }
        return diurab;
    }

    // ------------------------------------------------------------------
    // Offset coordinates
    // ------------------------------------------------------------------

    /**
     * The Mapping from the SkyFrame's System to its offset coordinates (C's {@code
     * SkyOffsetMap}): a UnitMap unless SkyRef is set and SkyRefIs is not Ignored, otherwise a
     * rotation, in Cartesian coordinates, that puts the pole or the origin at SkyRef and the
     * primary meridian through SkyRefP.
     *
     * @throws AstException with status {@code AST__BADOC} if SkyRefP is at, or opposite to, SkyRef
     */
    Mapping skyOffsetMap() {
        if (getSkyRefIsValue() == AST__IGNORED_REF || (!testSkyRef(1) && !testSkyRef(2))) {
            return new UnitMap(2);
        }
        int lataxis = getLatAxis();
        int lonaxis = 3 - lataxis;
        double[] vx;
        double[] vy;
        double[] vz;
        if (getSkyRefIsValue() == AST__ORIGIN_REF) {
            vx = Pal.dcs2c(getSkyRef(lonaxis), getSkyRef(lataxis));
            double[] vp = Pal.dcs2c(getSkyRefP(lonaxis), getSkyRefP(lataxis));
            Pn.Result n = Pal.dvn(Pal.dvxv(vp, vx));
            vy = n.unit;
            if (n.modulus == 0.0) {
                throw badSkyRefP();
            }
            vz = Pal.dvxv(vx, vy);
        } else {
            vz = Pal.dcs2c(getSkyRef(lonaxis), getSkyRef(lataxis));
            double[] vp = Pal.dcs2c(getSkyRefP(lonaxis), getSkyRefP(lataxis));
            Pn.Result n = Pal.dvn(Pal.dvxv(vz, vp));
            vy = n.unit;
            if (n.modulus == 0.0) {
                throw badSkyRefP();
            }
            vx = Pal.dvxv(vy, vz);
        }
        MatrixMap map1 = new MatrixMap(3, 3, new double[][] {vx, vy, vz});
        SphMap map2 = new SphMap();
        map2.setUnitRadius(true);
        CmpMap map3 = new CmpMap(map1, map2, true);
        map2.invert();
        return new CmpMap(map2, map3, true);
    }

    private AstException badSkyRefP() {
        return Ast.err(
                AstException.AST__BADOC,
                "astConvert(" + astClass() + "): The position specified by the SkyRefP attribute is either"
                        + " coincident, with or opposite to, the position specified by the SkyRef attribute.");
    }

    // ------------------------------------------------------------------
    // Formatting and reading values
    // ------------------------------------------------------------------

    /**
     * Formats a coordinate value for an axis, as an angle or time with the axis's Format, or, if
     * none is set, the default Format for the System.
     *
     * @param axis the axis number, from 1
     * @param value the value, in radians
     * @return the formatted value
     * @throws AstException with status {@code AST__AXIIN} if the axis does not exist
     */
    @Override
    public String format(int axis, double value) {
        validateAxis(axis, true, "astFormat");
        boolean formatSet = super.testFormat(axis);
        if (!formatSet) {
            super.setFormat(axis, getFormat(axis));
        }
        try {
            return super.format(axis, value);
        } finally {
            if (!formatSet) {
                super.clearFormat(axis);
            }
        }
    }

    /**
     * Reads a coordinate value for an axis, as an angle or time in any of the forms the axis
     * accepts, with the axis's Format, or the System's default, deciding the units of an
     * ambiguous value.
     */
    @Override
    int unformat(int axis, String string, double[] value) {
        validateAxis(axis, true, "astUnformat");
        boolean formatSet = super.testFormat(axis);
        if (!formatSet) {
            super.setFormat(axis, getFormat(axis));
        }
        double[] coord = new double[1];
        int nc;
        try {
            nc = super.unformat(axis, string, coord);
        } finally {
            if (!formatSet) {
                super.clearFormat(axis);
            }
        }
        if (nc != 0) {
            value[0] = coord[0];
        }
        return nc;
    }

    // ------------------------------------------------------------------
    // Geometry on the sphere
    // ------------------------------------------------------------------

    /** The coordinates of a point in internal (longitude, latitude) order. */
    private static double[] internal(int[] perm, double[] point) {
        double[] p = new double[2];
        p[perm[0]] = point[0];
        p[perm[1]] = point[1];
        return p;
    }

    /**
     * Finds the angle at point B between the great circles to points A and C, in radians,
     * between -pi and pi, positive in the sense from the second axis to the first.
     */
    @Override
    public double angle(double[] a, double[] b, double[] c) {
        checkPoint("astAngle", a);
        checkPoint("astAngle", b);
        checkPoint("astAngle", c);
        if (isBad(a[0]) || isBad(a[1]) || isBad(b[0]) || isBad(b[1]) || isBad(c[0]) || isBad(c[1])) {
            return AST__BAD;
        }
        int[] perm = getPerm();
        double[] aa = internal(perm, a);
        double[] bb = internal(perm, b);
        double[] cc = internal(perm, c);
        if ((aa[0] != bb[0] || aa[1] != bb[1]) && (cc[0] != bb[0] || cc[1] != bb[1])) {
            double anga = Pal.dbear(bb[0], bb[1], aa[0], aa[1]);
            double angc = Pal.dbear(bb[0], bb[1], cc[0], cc[1]);
            double result = angc - anga;
            if (perm[0] != 0) {
                result = PIBY2 - result;
            }
            return Pal.drange(result);
        }
        return AST__BAD;
    }

    /** Finds the great-circle distance between two points, in radians. */
    @Override
    public double distance(double[] point1, double[] point2) {
        checkPoint("astDistance", point1);
        checkPoint("astDistance", point2);
        if (isBad(point1[0]) || isBad(point1[1]) || isBad(point2[0]) || isBad(point2[1])) {
            return AST__BAD;
        }
        int[] perm = getPerm();
        double[] p1 = internal(perm, point1);
        double[] p2 = internal(perm, point2);
        return Pal.dsep(p1[0], p1[1], p2[0], p2[1]);
    }

    /**
     * Finds the point where two great circles cross, each given by two points on it: the crossing
     * nearer the first point of the first circle.
     */
    @Override
    public double[] intersect(double[] a1, double[] a2, double[] b1, double[] b2) {
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
        int[] perm = getPerm();
        double[] aa1 = internal(perm, a1);
        double[] aa2 = internal(perm, a2);
        double[] bb1 = internal(perm, b1);
        double[] bb2 = internal(perm, b2);
        double[] va1 = Pal.dcs2c(aa1[0], aa1[1]);
        double[] va2 = Pal.dcs2c(aa2[0], aa2[1]);
        double[] vb1 = Pal.dcs2c(bb1[0], bb1[1]);
        double[] vb2 = Pal.dcs2c(bb2[0], bb2[1]);
        double[] na = Pal.dvxv(va1, va2);
        double[] nb = Pal.dvxv(vb1, vb2);
        double[] vp = Pal.dvxv(na, nb);
        Pn.Result n = Pal.dvn(vp);
        if (n.modulus != 0.0) {
            double[] vpn = n.unit.clone();
            double d1 = Pal.dvdv(vpn, va1);
            vpn[0] = -vpn[0];
            vpn[1] = -vpn[1];
            vpn[2] = -vpn[2];
            double d2 = Pal.dvdv(vpn, va1);
            if (d1 > d2) {
                vpn[0] = -vpn[0];
                vpn[1] = -vpn[1];
                vpn[2] = -vpn[2];
            }
            double[] cc = Pal.dcc2s(vpn);
            cc[0] = Pal.dranrm(cc[0]);
            cross[0] = cc[perm[0]];
            cross[1] = cc[perm[1]];
        }
        return cross;
    }

    /**
     * Normalises a point: the longitude into the range 0 to 2pi (-pi to pi if NegLon is set) and
     * the latitude into -pi/2 to pi/2, moving the longitude through the pole when the latitude
     * passes it.
     */
    @Override
    public void norm(double[] value) {
        checkPoint("astNorm", value);
        int[] perm = getPerm();
        double[] v = internal(perm, value);
        double skyLong = v[0];
        double skyLat = v[1];
        boolean badLong = isBad(skyLong);
        boolean badLat = isBad(skyLat);
        if (!badLong && !badLat) {
            skyLong = Pal.dranrm(skyLong);
            skyLat = Pal.drange(skyLat);
            if (skyLat > PI / 2.0) {
                skyLong += skyLong < PI ? PI : -PI;
                skyLat = PI - skyLat;
            } else if (skyLat < -(PI / 2.0)) {
                skyLong += skyLong < PI ? PI : -PI;
                skyLat = -PI - skyLat;
            }
        } else if (!badLong) {
            skyLong = Pal.dranrm(skyLong);
        } else if (!badLat) {
            skyLat = Pal.drange(skyLat);
            if (skyLat > PI / 2.0) {
                skyLat = PI - skyLat;
            } else if (skyLat < -(PI / 2.0)) {
                skyLat = -PI - skyLat;
            }
        }
        // DBL_EPSILON * FLT_RADIX
        if (Math.abs(skyLong - 2.0 * PI) <= 2.0 * PI * (2.0 * Math.ulp(1.0))) {
            skyLong = 0.0;
        }
        if (!isBad(skyLong) && getNegLon()) {
            skyLong = Pal.drange(skyLong);
        }
        v[0] = skyLong;
        v[1] = skyLat;
        value[0] = v[perm[0]];
        value[1] = v[perm[1]];
    }

    /**
     * Normalises a set of points as {@link #norm} does; with {@code contig}, the longitudes are
     * then moved by multiples of 2pi into the range of 2pi centred opposite the largest gap
     * between them, so that the points are contiguous (C's {@code NormalPoints}).
     */
    @Override
    double[][] normalPoints(double[][] in, int npoint, boolean contig) {
        double[][] result = super.normalPoints(in, npoint, contig);
        if (contig) {
            int[] perm = getPerm();
            double[] lon = result[perm[0]];
            long[] hist = new long[360];
            for (int point = 0; point < npoint; point++) {
                if (!isBad(lon[point])) {
                    int ihist = (int) (lon[point] * AST__DR2D);
                    if (ihist >= 0 && ihist < 360) {
                        hist[ihist]++;
                    }
                }
            }
            boolean ingap = false;
            int maxgap = 0;
            int maxgapCentre = 0;
            int gapStart = 0;
            int ifirst = -1;
            int ilast = -1;
            for (int ihist = 0; ihist < 360; ihist++) {
                if (hist[ihist] == 0) {
                    if (!ingap) {
                        ingap = true;
                        gapStart = ihist;
                    }
                } else {
                    if (ingap) {
                        ingap = false;
                        int gapEnd = ihist - 1;
                        int gapSize = gapEnd - gapStart + 1;
                        if (gapSize > maxgap) {
                            maxgap = gapSize;
                            maxgapCentre = (gapStart + gapEnd) / 2;
                        }
                    }
                    if (ifirst == -1) {
                        ifirst = ihist;
                    }
                    ilast = ihist;
                }
            }
            gapStart = ilast - 360;
            int gapEnd = ifirst;
            int gapSize = gapEnd - gapStart + 1;
            if (gapSize > maxgap) {
                maxgapCentre = (gapStart + gapEnd) / 2;
            }
            int centre = maxgapCentre > 0 ? maxgapCentre - 180 : maxgapCentre + 180;
            double lo = (centre - 180) * AST__DD2R;
            double hi = lo + 2 * PI;
            for (int point = 0; point < npoint; point++) {
                if (!isBad(lon[point])) {
                    while (lon[point] < lo) {
                        lon[point] += 2 * PI;
                    }
                    while (lon[point] > hi) {
                        lon[point] -= 2 * PI;
                    }
                }
            }
        }
        return result;
    }

    /** C's {@code AST__DR2D}: degrees per radian. */
    private static final double AST__DR2D = 57.29577951308232;

    /** C's {@code AST__DD2R}: radians per degree. */
    private static final double AST__DD2R = 0.017453292519943295;

    /**
     * Finds the point a given distance along the great circle from one point towards another.
     * The longitude found is in the range 0 to 2pi. Coincident or opposite points give a point
     * only for a distance whose sine is zero.
     */
    @Override
    public double[] offset(double[] point1, double[] point2, double offset) {
        checkPoint("astOffset", point1);
        checkPoint("astOffset", point2);
        double[] point3 = new double[2];
        if (isBad(point1[0]) || isBad(point1[1]) || isBad(point2[0]) || isBad(point2[1]) || isBad(offset)) {
            point3[0] = AST__BAD;
            point3[1] = AST__BAD;
            return point3;
        }
        int[] perm = getPerm();
        double[] p1 = internal(perm, point1);
        double[] p2 = internal(perm, point2);
        double[] v1 = Pal.dcs2c(p1[0], p1[1]);
        double[] v2 = Pal.dcs2c(p2[0], p2[1]);
        Pn.Result n = Pal.dvn(Pal.dvxv(v2, v1));
        double[] p3;
        if (n.modulus == 0.0) {
            if (Math.sin(offset) == 0.0) {
                double scale = Math.cos(offset);
                double[] v3 = {v1[0] * scale, v1[1] * scale, v1[2] * scale};
                p3 = Pal.dcc2s(v3);
                p3[0] = Pal.dranrm(p3[0]);
            } else {
                p3 = new double[] {AST__BAD, AST__BAD};
            }
        } else {
            double[] vrot = {n.unit[0] * offset, n.unit[1] * offset, n.unit[2] * offset};
            double[][] mrot = Pal.dav2m(vrot);
            double[] v3 = Pal.dmxv(mrot, v1);
            p3 = Pal.dcc2s(v3);
            p3[0] = Pal.dranrm(p3[0]);
        }
        point3[0] = p3[perm[0]];
        point3[1] = p3[perm[1]];
        return point3;
    }

    /**
     * Finds the point a given distance along the great circle leaving a point at a given
     * position angle, measured from the second axis towards the first, and returns the position
     * angle of the great circle at the point found.
     */
    @Override
    public double offset2(double[] point1, double angle, double offset, double[] point2) {
        checkPoint("astOffset2", point1);
        checkPoint("astOffset2", point2);
        if (isBad(point1[0]) || isBad(point1[1]) || isBad(angle) || isBad(offset)) {
            point2[0] = AST__BAD;
            point2[1] = AST__BAD;
            return AST__BAD;
        }
        int[] perm = getPerm();
        double[] p1 = internal(perm, point1);
        double pa = perm[0] == 0 ? angle : PIBY2 - angle;
        double[] r0 = new double[3];
        double[] r3 = new double[3];
        shcal(p1[0], p1[1], pa, r0, r3);
        double[] p2 = shapp(offset, r0, r3, p1[0]);
        // C normalises the internal coordinates with the external axis order.
        norm(p2);
        double sina1 = Math.sin(p2[0]);
        double cosa1 = Math.cos(p2[0]);
        double sinb1 = Math.sin(p2[1]);
        double cosb1 = Math.cos(p2[1]);
        double[] q1 = {-sinb1 * cosa1, -sinb1 * sina1, cosb1};
        double[] q2 = {-sina1, cosa1, 0.0};
        double cosoff = Math.cos(offset);
        double sinoff = Math.sin(offset);
        double[] q3 = {
            -sinoff * r0[0] + cosoff * r3[0], -sinoff * r0[1] + cosoff * r3[1], -sinoff * r0[2] + cosoff * r3[2]
        };
        pa = Math.atan2(Pal.dvdv(q3, q2), Pal.dvdv(q3, q1));
        double result = perm[0] == 0 ? pa : PIBY2 - pa;
        result = Pal.dranrm(result);
        point2[0] = p2[perm[0]];
        point2[1] = p2[perm[1]];
        return result;
    }

    /**
     * C's {@code Shapp}: the position {@code dist} along the great circle given by the unit
     * vectors {@code r0} (the start) and {@code r3} (pi/2 along), with longitude {@code a0} at a
     * pole.
     */
    private static double[] shapp(double dist, double[] r0, double[] r3, double a0) {
        double sindst = Math.sin(dist);
        double cosdst = Math.cos(dist);
        double[] r4 = {cosdst * r0[0] + sindst * r3[0], cosdst * r0[1] + sindst * r3[1], cosdst * r0[2] + sindst * r3[2]
        };
        double[] p4 = new double[2];
        if (r4[0] != 0.0 || r4[1] != 0.0) {
            p4[0] = Math.atan2(r4[1], r4[0]);
        } else {
            p4[0] = a0;
        }
        if (r4[2] > 1.0) {
            r4[2] = 1.0;
        } else if (r4[2] < -1.0) {
            r4[2] = -1.0;
        }
        p4[1] = Math.asin(r4[2]);
        return p4;
    }

    /**
     * C's {@code Shcal}: the unit vector {@code r0} of the position (a0, b0), and the unit vector
     * {@code r3} pi/2 away along the great circle leaving it at position angle {@code angle}.
     */
    private static void shcal(double a0, double b0, double angle, double[] r0, double[] r3) {
        double sina0 = Math.sin(a0);
        double cosa0 = Math.cos(a0);
        double sinb0 = Math.sin(b0);
        double cosb0 = Math.cos(b0);
        double sinpa = Math.sin(angle);
        double cospa = Math.cos(angle);
        r0[0] = cosb0 * cosa0;
        r0[1] = cosb0 * sina0;
        r0[2] = sinb0;
        double[] r1 = {-sinb0 * cosa0, -sinb0 * sina0, cosb0};
        double[] r2 = {-sina0, cosa0, 0.0};
        r3[0] = cospa * r1[0] + sinpa * r2[0];
        r3[1] = cospa * r1[1] + sinpa * r2[1];
        r3[2] = cospa * r1[2] + sinpa * r2[2];
    }

    /**
     * Resolves the vector from point 1 to point 3 into components along and perpendicular to the
     * great circle from point 1 to point 2, measured as arcs on the sphere.
     */
    @Override
    public double[] resolve(double[] point1, double[] point2, double[] point3, double[] point4) {
        checkPoint("astResolve", point1);
        checkPoint("astResolve", point2);
        checkPoint("astResolve", point3);
        checkPoint("astResolve", point4);
        point4[0] = AST__BAD;
        point4[1] = AST__BAD;
        double d1 = AST__BAD;
        double d2 = AST__BAD;
        if (!isBad(point1[0])
                && !isBad(point1[1])
                && !isBad(point2[0])
                && !isBad(point2[1])
                && !isBad(point3[0])
                && !isBad(point3[1])) {
            int[] perm = getPerm();
            double[] p1 = internal(perm, point1);
            double[] p2 = internal(perm, point2);
            double[] p3 = internal(perm, point3);
            double[] v1 = Pal.dcs2c(p1[0], p1[1]);
            double[] v2 = Pal.dcs2c(p2[0], p2[1]);
            double[] v3 = Pal.dcs2c(p3[0], p3[1]);
            Pn.Result r1 = Pal.dvn(Pal.dvxv(v2, v1));
            if (r1.modulus > 0.0) {
                double[] n1 = r1.unit;
                double[] v5 = Pal.dvxv(v1, n1);
                Pn.Result r2 = Pal.dvn(Pal.dvxv(v3, n1));
                if (r2.modulus > 0.0) {
                    double[] v4 = Pal.dvxv(n1, r2.unit);
                    d1 = Math.atan2(Pal.dvdv(v4, v5), Pal.dvdv(v4, v1));
                    d2 = Math.abs(Math.atan2(Pal.dvdv(v3, n1), Pal.dvdv(v3, v4)));
                    double[] p4 = Pal.dcc2s(v4);
                    p4[0] = Pal.dranrm(p4[0]);
                    point4[0] = p4[perm[0]];
                    point4[1] = p4[perm[1]];
                }
            }
        }
        return new double[] {d1, d2};
    }

    /**
     * Resolves the vectors from point 1 to each of a set of points into components along and
     * perpendicular to the great circle from point 1 to point 2 (C's {@code ResolvePoints}), the
     * perpendicular component signed by the side of the great circle.
     */
    @Override
    double[][] resolvePoints(double[] point1, double[] point2, double[][] in, int npoint) {
        checkPoint("astResolvePoints", point1);
        checkPoint("astResolvePoints", point2);
        if (in.length != 2) {
            throw Ast.err(
                    AstException.AST__NCPIN,
                    "astResolvePoints(" + astClass() + "): Bad number of coordinate values (" + in.length
                            + ") in input PointSet.\nThe " + astClass()
                            + " given requires 2 coordinate values for each input point.");
        }
        double[] d1 = new double[npoint];
        double[] d2 = new double[npoint];
        int[] perm = getPerm();
        double sign = perm[0] == 0 ? -1.0 : 1.0;
        double[] p1 = internal(perm, point1);
        double[] p2 = internal(perm, point2);
        double[] v1 = Pal.dcs2c(p1[0], p1[1]);
        double[] v2 = Pal.dcs2c(p2[0], p2[1]);
        Pn.Result r1 = Pal.dvn(Pal.dvxv(v2, v1));
        boolean ok = r1.modulus > 0.0;
        double[] n1 = r1.unit;
        double[] v5 = ok ? Pal.dvxv(v1, n1) : null;
        for (int ipoint = 0; ipoint < npoint; ipoint++) {
            d1[ipoint] = AST__BAD;
            d2[ipoint] = AST__BAD;
            if (!ok || isBad(in[0][ipoint]) || isBad(in[1][ipoint])) {
                continue;
            }
            double[] p3 = internal(perm, new double[] {in[0][ipoint], in[1][ipoint]});
            double[] v3 = Pal.dcs2c(p3[0], p3[1]);
            Pn.Result r2 = Pal.dvn(Pal.dvxv(v3, n1));
            if (r2.modulus > 0.0) {
                double[] v4 = Pal.dvxv(n1, r2.unit);
                d1[ipoint] = Math.atan2(Pal.dvdv(v4, v5), Pal.dvdv(v4, v1));
                d2[ipoint] = sign * Math.atan2(Pal.dvdv(v3, n1), Pal.dvdv(v3, v4));
            }
        }
        return new double[][] {d1, d2};
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * Matches this SkyFrame, a template, against a target (C's {@code Match}, skyframe.c): the
     * target must pass the Frame tests and have two axes that are the longitude and latitude of
     * one SkyFrame; their order is swapped to the template's if Permute allows.
     */
    @Override
    // C compares the Frame pointers astPrimaryFrame gives.
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        FrameFinder.MatchResult parent = super.match(target, matchSub);
        if (parent == null) {
            return null;
        }
        int targetNaxes = target.getNaxes();
        int[] pax = new int[1];
        boolean match = false;
        Frame frame0 = null;
        int iaxis0 = -1;
        int iaxis1 = -1;
        int targetAxis0 = -1;
        int targetAxis1 = -1;
        int iaxis;
        for (iaxis = 0; iaxis < targetNaxes; iaxis++) {
            Frame f = target.primaryFrame(iaxis + 1, pax);
            if (f instanceof SkyFrame) {
                frame0 = f;
                iaxis0 = pax[0] - 1;
                targetAxis0 = iaxis;
                match = true;
                break;
            }
        }
        if (match) {
            match = false;
            for (iaxis++; iaxis < targetNaxes; iaxis++) {
                Frame f = target.primaryFrame(iaxis + 1, pax);
                iaxis1 = pax[0] - 1;
                if (f == frame0) {
                    targetAxis1 = iaxis;
                    match = true;
                    break;
                }
            }
        }
        if (match) {
            match = (iaxis0 == 0 && iaxis1 == 1) || (iaxis1 == 0 && iaxis0 == 1);
        }
        boolean swap = false;
        if (match) {
            boolean swap1 = validateAxis(1, true, "astMatch") != 1;
            boolean swap2 = iaxis0 != 0;
            swap = swap1 != swap2;
            match = !swap || getPermute();
        }
        if (!match) {
            return null;
        }
        int[] templateAxes = parent.templateAxes;
        int[] targetAxes = parent.targetAxes;
        if (getPreserveAxes()) {
            templateAxes[0] = swap ? 1 : 0;
            templateAxes[1] = swap ? 0 : 1;
            targetAxes[0] = targetAxis0;
            targetAxes[1] = targetAxis1;
        } else {
            templateAxes[0] = 0;
            templateAxes[1] = 1;
            targetAxes[0] = swap ? targetAxis1 : targetAxis0;
            targetAxes[1] = swap ? targetAxis0 : targetAxis1;
        }
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        if (!target.subFrame(this, 2, targetAxes, templateAxes, map, result)) {
            return null;
        }
        return new FrameFinder.MatchResult(templateAxes, targetAxes, map[0], result[0]);
    }

    /**
     * Finds the axes of {@code frm1} that correspond to this SkyFrame's two axes (C's {@code
     * MatchAxesX}, skyframe.c): {@code frm1}, keeping the target's axes and matching any number
     * of them, is matched against this SkyFrame as a whole.
     */
    @Override
    int[] matchAxesX(Frame frm1) {
        int preserveAxes = frm1.testPreserveAxes() ? (frm1.getPreserveAxes() ? 1 : 0) : -1;
        frm1.setPreserveAxes(true);
        int maxAxesValue = frm1.testMaxAxes() ? frm1.getMaxAxes() : -1;
        frm1.setMaxAxes(10000);
        int minAxesValue = frm1.testMinAxes() ? frm1.getMinAxes() : -1;
        frm1.setMinAxes(1);
        int[] axes = new int[2];
        try {
            FrameFinder.MatchResult m = FrameFinder.match(frm1, this, true);
            if (m != null) {
                axes[0] = m.templateAxes[0] + 1;
                axes[1] = m.templateAxes[1] + 1;
            }
        } finally {
            if (preserveAxes == -1) {
                frm1.clearPreserveAxes();
            } else {
                frm1.setPreserveAxes(preserveAxes != 0);
            }
            if (maxAxesValue == -1) {
                frm1.clearMaxAxes();
            } else {
                frm1.setMaxAxes(maxAxesValue);
            }
            if (minAxesValue == -1) {
                frm1.clearMinAxes();
            } else {
                frm1.setMinAxes(minAxesValue);
            }
        }
        return axes;
    }

    /**
     * Selects axes from this SkyFrame (C's {@code SubFrame}, skyframe.c). Selecting both axes, in
     * either order, gives a SkyFrame, with the template's attributes overlaid, and the Mapping
     * converts between the celestial coordinate systems, aligned in the template's AlignSystem
     * (or in the offset coordinates if both SkyFrames ask for that). Any other selection gives a
     * plain Frame whose axes keep the SkyFrame's attributes, as for a plain Frame.
     */
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        map[0] = null;
        result[0] = null;
        boolean match;
        if (resultNaxes == 2
                && ((targetAxes[0] == 0 && targetAxes[1] == 1) || (targetAxes[0] == 1 && targetAxes[1] == 0))) {
            boolean setUsedefs = false;
            if ((template == null || sameObject(template)) && !getUseDefs()) {
                clearUseDefs();
                setUsedefs = true;
            }
            SkyFrame res = (SkyFrame) copy();
            res.permAxes(new int[] {targetAxes[0] + 1, targetAxes[1] + 1});
            result[0] = res;
            int alignSys;
            if (template != null) {
                template.overlay(templateAxes, res);
                alignSys = template.alignSystemValue();
            } else {
                alignSys = alignSystemValue();
            }
            Mapping m = null;
            match = false;
            if ((getFrameFlags() & AST__INTFLAG) == 0
                    && getAlignOffset()
                    && getSkyRefIsValue() != AST__IGNORED_REF
                    && template instanceof SkyFrame) {
                SkyFrame t = (SkyFrame) template;
                if (t.getAlignOffset() && t.getSkyRefIsValue() != AST__IGNORED_REF) {
                    match = true;
                    m = new UnitMap(2);
                }
            }
            if (m == null) {
                m = SkyConvert.makeSkyMapping(this, res, alignSys);
                match = m != null;
            }
            if (setUsedefs) {
                setUseDefs(false);
                res.setUseDefs(false);
            }
            if (match) {
                boolean targetSwap = validateAxis(1, true, "astSubFrame") != 1;
                boolean resultSwap = targetSwap != (targetAxes[0] != 0);
                PermMap permmap = null;
                if (targetSwap || resultSwap) {
                    int[] perm = {2, 1};
                    permmap = new PermMap(2, perm, 2, perm, null);
                }
                if (targetSwap) {
                    m = new CmpMap(permmap, m, true);
                }
                if (resultSwap) {
                    m = new CmpMap(m, permmap, true);
                }
                map[0] = m;
            }
        } else {
            SkyFrame temp = (SkyFrame) copy();
            if (!temp.testDomain()) {
                temp.setDomain(temp.getDomain());
            }
            if (!temp.testTitle()) {
                temp.setTitle(temp.getTitle());
            }
            for (int targetAxis = 1; targetAxis <= 2; targetAxis++) {
                if (!temp.testAsTime(targetAxis)) {
                    temp.setAsTime(targetAxis, temp.getAsTime(targetAxis));
                }
                if (!temp.testFormat(targetAxis)) {
                    temp.setFormat(targetAxis, temp.getFormat(targetAxis));
                }
                if (!temp.testLabel(targetAxis)) {
                    temp.setLabel(targetAxis, temp.getLabel(targetAxis));
                }
                if (!temp.testSymbol(targetAxis)) {
                    temp.setSymbol(targetAxis, temp.getSymbol(targetAxis));
                }
                if (!temp.testUnit(targetAxis)) {
                    temp.setUnit(targetAxis, temp.getUnit(targetAxis));
                }
                Axis ax = temp.getAxis(targetAxis);
                if (!(ax instanceof SkyAxis)) {
                    throw Ast.err(
                            AstException.AST__INTER,
                            "astSubFrame(" + astClass() + "): Axis " + targetAxis + " of the " + astClass() + " is a "
                                    + ax.astClass() + ", not a SkyAxis (internal programming error).");
                }
                SkyAxis sax = (SkyAxis) ax;
                if (temp.validateAxis(targetAxis, true, "astSubFrame") == 2) {
                    sax.setAxisIsLatitude(true);
                    sax.setAxisCentreZero(true);
                } else {
                    sax.setAxisIsLatitude(false);
                    sax.setAxisCentreZero(temp.getNegLon());
                }
            }
            temp.clearSystem();
            temp.clearAlignSystem();
            match = temp.frameSubFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
        }
        if (result[0] != null) {
            result[0].setActiveUnit(false);
        }
        if (!match) {
            map[0] = null;
            result[0] = null;
        }
        return match;
    }

    /** The Frame class's {@code SubFrame}, for a copy of this SkyFrame. */
    private boolean frameSubFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        return super.subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
    }

    /**
     * Overlays the attributes set on this SkyFrame, the template, onto another Frame (C's {@code
     * Overlay}, skyframe.c). Onto a SkyFrame, a change of System or SkyRef first clears the
     * result's Title and, if the change is between equatorial and other Systems or of SkyRef, its
     * per-axis AsTime, Direction, Format, Label, Symbol and Unit; then the Frame attributes and
     * the SkyFrame attributes Equinox, Projection, NegLon, SkyTol, AlignOffset, SkyRefIs, SkyRef
     * and SkyRefP are overlaid when set. Onto another class of Frame the System and AlignSystem
     * are not overlaid.
     */
    @Override
    void overlay(int[] templateAxes, Frame result) {
        boolean resetSystem = false;
        int newSystem = AST__UNKNOWN;
        int newAlignSystem = AST__UNKNOWN;
        boolean skyframe = result instanceof SkyFrame;
        if (skyframe) {
            SkyFrame res = (SkyFrame) result;
            int oldSystem = res.systemValue();
            newSystem = systemValue();
            boolean skyrefChanged = res.getSkyRef(1) != getSkyRef(1) || res.getSkyRef(2) != getSkyRef(2);
            if ((newSystem != oldSystem && testSystem()) || skyrefChanged) {
                res.clearTitle();
                if (isEquatorial(newSystem) != isEquatorial(oldSystem) || skyrefChanged) {
                    for (int axis = 1; axis <= 2; axis++) {
                        res.clearAsTime(axis);
                        res.clearDirection(axis);
                        res.clearFormat(axis);
                        res.clearLabel(axis);
                        res.clearSymbol(axis);
                        res.clearUnit(axis);
                    }
                }
            }
        } else if (testSystem()) {
            newSystem = systemValue();
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
        if (skyframe) {
            SkyFrame res = (SkyFrame) result;
            if (testEquinox()) {
                res.setEquinoxMjd(getEquinoxMjd());
            }
            if (testProjection()) {
                res.setProjection(getProjection());
            }
            if (testNegLon()) {
                res.setNegLon(getNegLon());
            }
            if (testSkyTol()) {
                res.setSkyTol(getSkyTol());
            }
            if (testAlignOffset()) {
                res.setAlignOffset(getAlignOffset());
            }
            if (testSkyRefIs()) {
                res.setSkyRefIsValue(getSkyRefIsValue());
            }
            int tax0 = templateAxes != null ? templateAxes[0] + 1 : 1;
            int tax1 = templateAxes != null ? templateAxes[1] + 1 : 2;
            if (testSkyRef(tax0)) {
                res.setSkyRef(1, getSkyRef(tax0));
            }
            if (testSkyRef(tax1)) {
                res.setSkyRef(2, getSkyRef(tax1));
            }
            if (testSkyRefP(tax0)) {
                res.setSkyRefP(1, getSkyRefP(tax0));
            }
            if (testSkyRefP(tax1)) {
                res.setSkyRefP(2, getSkyRefP(tax1));
            }
        }
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    @Override
    String getAttrib(String attrib) {
        Integer asTimeAxis = axisArg(attrib, "astime");
        Integer isLatAxis = axisArg(attrib, "islataxis");
        Integer isLonAxis = axisArg(attrib, "islonaxis");
        Integer skyRefAxis = axisArg(attrib, "skyref");
        Integer skyRefPAxis = axisArg(attrib, "skyrefp");
        if (asTimeAxis != null) {
            return getAsTime(asTimeAxis) ? "1" : "0";
        } else if ("equinox".equals(attrib)) {
            double eq = getEquinoxMjd();
            return DateTime.fmtDecimalYr(eq < Pal.epj2d(1984.0) ? Pal.epb(eq) : Pal.epj(eq), DBL_DIG);
        } else if (isLatAxis != null) {
            return getIsLatAxis(isLatAxis) ? "1" : "0";
        } else if (isLonAxis != null) {
            return getIsLonAxis(isLonAxis) ? "1" : "0";
        } else if ("lataxis".equals(attrib)) {
            return Integer.toString(getLatAxis());
        } else if ("lonaxis".equals(attrib)) {
            return Integer.toString(getLonAxis());
        } else if ("neglon".equals(attrib)) {
            return getNegLon() ? "1" : "0";
        } else if ("skytol".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", getSkyTol());
        } else if ("projection".equals(attrib)) {
            return getProjection();
        } else if ("skyref".equals(attrib)) {
            return format(1, getSkyRef(1)) + ", " + format(2, getSkyRef(2));
        } else if (skyRefAxis != null) {
            return CFormat.format("%." + DBL_DIG + "g", getSkyRef(skyRefAxis));
        } else if ("skyrefp".equals(attrib)) {
            return format(1, getSkyRefP(1)) + ", " + format(2, getSkyRefP(2));
        } else if (skyRefPAxis != null) {
            return CFormat.format("%." + DBL_DIG + "g", getSkyRefP(skyRefPAxis));
        } else if ("skyrefis".equals(attrib)) {
            int ival = getSkyRefIsValue();
            if (ival == AST__POLE_REF) {
                return POLE_STRING;
            } else if (ival == AST__IGNORED_REF) {
                return IGNORED_STRING;
            }
            return ORIGIN_STRING;
        } else if ("alignoffset".equals(attrib)) {
            return getAlignOffset() ? "1" : "0";
        }
        return super.getAttrib(attrib);
    }

    /**
     * Reads a pair of axis values separated by a comma, as C's {@code SetAttrib} reads SkyRef and
     * SkyRefP, or returns null if the whole string is not read.
     */
    private double[] readPair(String value) {
        double[] d1 = new double[1];
        double[] d2 = new double[1];
        int nc = unformat(1, value, d1);
        if (nc < value.length() && value.charAt(nc) == ',') {
            nc++;
            nc += unformat(2, value.substring(nc), d2);
            if (nc == value.length()) {
                return new double[] {d1[0], d2[0]};
            }
        }
        return null;
    }

    /** The length of a string without trailing white space (C's {@code astChrLen}). */
    private static int chrLen(String s) {
        int n = s.length();
        while (n > 0 && CScan.isCSpace(s.charAt(n - 1))) {
            n--;
        }
        return n;
    }

    @Override
    void setAttrib(String attrib, String value) {
        Integer ival = CScan.scanInt(value);
        Double dval = CScan.scanDouble(value);
        boolean hasValue = !value.isEmpty() && value.charAt(0) != '\n';
        Integer asTimeAxis = axisArg(attrib, "astime");
        Integer skyRefAxis = axisArg(attrib, "skyref");
        Integer skyRefPAxis = axisArg(attrib, "skyrefp");
        if (asTimeAxis != null && ival != null) {
            setAsTime(asTimeAxis, ival != 0);
        } else if ("equinox".equals(attrib) && hasValue) {
            double mjd;
            try {
                mjd = DateTime.readDateTime(value);
            } catch (AstException e) {
                AstException err = Ast.err(
                        AstException.AST__ATTIN,
                        e.getMsgText() + "\nastSetAttrib(" + astClass() + "): Invalid equinox value \"" + value
                                + "\" given for sky coordinate system.");
                err.initCause(e);
                throw err;
            }
            setEquinoxMjd(mjd);
        } else if ("neglon".equals(attrib) && ival != null) {
            setNegLon(ival != 0);
        } else if ("skytol".equals(attrib) && dval != null) {
            setSkyTol(dval);
        } else if ("projection".equals(attrib) && hasValue) {
            setProjection(value);
        } else if ("skyref".equals(attrib) && hasValue) {
            double[] pair = readPair(value);
            if (pair == null) {
                throw Ast.err(
                        AstException.AST__BADOC,
                        "astSetAttrib(" + astClass() + "): Invalid axis values string \""
                                + value.substring(0, chrLen(value)) + "\" given for SkyRef attribute.");
            }
            setSkyRef(1, pair[0]);
            setSkyRef(2, pair[1]);
        } else if (skyRefAxis != null && dval != null) {
            setSkyRef(skyRefAxis, dval);
        } else if ("skyrefis".equals(attrib) && hasValue) {
            if (chrMatch(value, POLE_STRING)) {
                setSkyRefIsValue(AST__POLE_REF);
            } else if (chrMatch(value, ORIGIN_STRING)) {
                setSkyRefIsValue(AST__ORIGIN_REF);
            } else if (chrMatch(value, IGNORED_STRING)) {
                setSkyRefIsValue(AST__IGNORED_REF);
            } else {
                throw Ast.err(
                        AstException.AST__OPT,
                        "astSet(" + astClass() + "): option '" + value + "' is unknown in '" + attrib + "=" + value
                                + "'.");
            }
        } else if ("skyrefp".equals(attrib) && hasValue) {
            double[] pair = readPair(value);
            if (pair == null) {
                throw Ast.err(
                        AstException.AST__BADOC,
                        "astSetAttrib(" + astClass() + "): Invalid axis values string \""
                                + value.substring(0, chrLen(value)) + "\" given for SkyRefP attribute.");
            }
            setSkyRefP(1, pair[0]);
            setSkyRefP(2, pair[1]);
        } else if (skyRefPAxis != null && dval != null) {
            setSkyRefP(skyRefPAxis, dval);
        } else if ("alignoffset".equals(attrib) && ival != null) {
            setAlignOffset(ival != 0);
        } else if (attrib.startsWith("islataxis")
                || attrib.startsWith("islonaxis")
                || (("lataxis".equals(attrib) || "lonaxis".equals(attrib)) && hasValue)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + attrib + "=" + value + "\" is invalid for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        Integer asTimeAxis = axisArg(attrib, "astime");
        Integer skyRefAxis = axisArg(attrib, "skyref");
        Integer skyRefPAxis = axisArg(attrib, "skyrefp");
        if (asTimeAxis != null) {
            return testAsTime(asTimeAxis);
        } else if ("equinox".equals(attrib)) {
            return testEquinox();
        } else if ("neglon".equals(attrib)) {
            return testNegLon();
        } else if ("skytol".equals(attrib)) {
            return testSkyTol();
        } else if ("projection".equals(attrib)) {
            return testProjection();
        } else if ("skyrefis".equals(attrib)) {
            return testSkyRefIs();
        } else if ("skyref".equals(attrib)) {
            return testSkyRef(1) || testSkyRef(2);
        } else if (skyRefAxis != null) {
            return testSkyRef(skyRefAxis);
        } else if ("skyrefp".equals(attrib)) {
            return testSkyRefP(1) || testSkyRefP(2);
        } else if (skyRefPAxis != null) {
            return testSkyRefP(skyRefPAxis);
        } else if ("alignoffset".equals(attrib)) {
            return testAlignOffset();
        } else if (attrib.startsWith("islataxis")
                || attrib.startsWith("islonaxis")
                || "lataxis".equals(attrib)
                || "lonaxis".equals(attrib)) {
            return false;
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        Integer asTimeAxis = axisArg(attrib, "astime");
        Integer skyRefAxis = axisArg(attrib, "skyref");
        Integer skyRefPAxis = axisArg(attrib, "skyrefp");
        if (asTimeAxis != null) {
            clearAsTime(asTimeAxis);
        } else if ("equinox".equals(attrib)) {
            clearEquinox();
        } else if ("neglon".equals(attrib)) {
            clearNegLon();
        } else if ("projection".equals(attrib)) {
            clearProjection();
        } else if ("skyref".equals(attrib)) {
            clearSkyRef(1);
            clearSkyRef(2);
        } else if ("skytol".equals(attrib)) {
            clearSkyTol();
        } else if (skyRefAxis != null) {
            clearSkyRef(skyRefAxis);
        } else if ("skyrefp".equals(attrib)) {
            clearSkyRefP(1);
            clearSkyRefP(2);
        } else if (skyRefPAxis != null) {
            clearSkyRefP(skyRefPAxis);
        } else if ("skyrefis".equals(attrib)) {
            clearSkyRefIs();
        } else if ("alignoffset".equals(attrib)) {
            clearAlignOffset();
        } else if (attrib.startsWith("islataxis")
                || attrib.startsWith("islonaxis")
                || "lataxis".equals(attrib)
                || "lonaxis".equals(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else {
            super.clearAttrib(attrib);
        }
    }
}
