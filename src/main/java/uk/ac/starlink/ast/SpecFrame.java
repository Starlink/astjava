package uk.ac.starlink.ast;

/**
 * A spectral coordinate system. A SpecFrame is a one-dimensional Frame describing positions in an
 * electro-magnetic spectrum, in the coordinate system its System attribute names: frequency
 * ({@code FREQ}), energy ({@code ENER}), wave-number ({@code WAVN}), wavelength ({@code WAVE}, the
 * default), wavelength in air ({@code AWAV}), radio velocity ({@code VRAD}), optical velocity
 * ({@code VOPT}), redshift ({@code ZOPT}), the beta factor ({@code BETA}) or apparent radial
 * velocity ({@code VELO}). The velocities and redshift are relative to the rest frequency given by
 * the RestFreq attribute; the default units of each System (GHz, J, 1/m, Angstrom, km/s, or none
 * for redshift and beta) may be changed through the Unit attribute, and the Unit last used with
 * each System comes back when the System does.
 *
 * <p>The StdOfRest attribute gives the standard of rest of the values, qualified as it needs by
 * the Epoch, the observer's position (ObsLon, ObsLat, ObsAlt), the source position (RefRA, RefDec)
 * and, for the source's rest frame, its velocity (SourceVel, given in the spectral system
 * SourceSys and the rest frame SourceVRF). Two SpecFrames are aligned in the System and standard
 * of rest given by their AlignSystem and AlignStdOfRest attributes. A SpecOrigin may be set,
 * making the axis values offsets from that spectral position; with AlignSpecOffset set, two
 * SpecFrames are aligned in their offset values.
 *
 * <p>The per-axis attributes Direction, Bottom, Top, Format, Label, Symbol and Unit may be named
 * without an axis number. A SpecFrame always uses the Unit of its axis when it is aligned with
 * another (its ActiveUnit flag is always true). The attributes GeoLat and GeoLon are the ObsLat
 * and ObsLon under the names an older version of the SpecFrame used.
 *
 * <p>Ported from C {@code specframe.c}, with Rust {@code frames/specframe.rs}.
 */
public class SpecFrame extends Frame {

    /** The frequency System. */
    static final int AST__FREQ = 1;

    /** The energy System. */
    static final int AST__ENERGY = 2;

    /** The wave-number System. */
    static final int AST__WAVENUM = 3;

    /** The wavelength System. */
    static final int AST__WAVELEN = 4;

    /** The wavelength in air System. */
    static final int AST__AIRWAVE = 5;

    /** The radio velocity System. */
    static final int AST__VRADIO = 6;

    /** The optical velocity System. */
    static final int AST__VOPTICAL = 7;

    /** The redshift System. */
    static final int AST__REDSHIFT = 8;

    /** The beta factor System. */
    static final int AST__BETA = 9;

    /** The apparent radial velocity System. */
    static final int AST__VREL = 10;

    /** The standard of rest of an unset attribute. */
    static final int AST__BADSOR = 0;

    /** The topocentric standard of rest. */
    static final int AST__TPSOR = 1;

    /** The geocentric standard of rest. */
    static final int AST__GESOR = 2;

    /** The barycentric standard of rest. */
    static final int AST__BYSOR = 3;

    /** The heliocentric standard of rest. */
    static final int AST__HLSOR = 4;

    /** The dynamical local standard of rest. */
    static final int AST__LDSOR = 5;

    /** The kinematical local standard of rest. */
    static final int AST__LKSOR = 6;

    /** The Local Group standard of rest. */
    static final int AST__LGSOR = 7;

    /** The Galactic standard of rest. */
    static final int AST__GLSOR = 8;

    /** The rest frame of the source. */
    static final int AST__SCSOR = 9;

    private static final int FIRST_SYSTEM = AST__FREQ;
    private static final int LAST_SYSTEM = AST__VREL;
    private static final int FIRST_SOR = AST__TPSOR;
    private static final int LAST_SOR = AST__SCSOR;

    /** The value of an unset integer attribute (C's {@code -INT_MAX}). */
    private static final int UNSET = -Integer.MAX_VALUE;

    /** The digits C writes a full-precision double with ({@code AST__DBL_DIG}). */
    private static final int DBL_DIG = 17;

    /** The names of the per-axis Frame attributes that may be given without an axis number. */
    private static final String[] ONE_AXIS_NAMES = {"direction", "bottom", "top", "format", "label", "symbol", "unit"};

    /** The AlignStdOfRest code, or {@link #AST__BADSOR} if unset. */
    private int alignStdOfRest = AST__BADSOR;

    /** The RefDec in radians (FK5 J2000), or bad if unset. */
    private double refDecRadians = AST__BAD;

    /** The RefRA in radians (FK5 J2000), or bad if unset. */
    private double refRaRadians = AST__BAD;

    /** The RestFreq in Hz, or bad if unset. */
    private double restFreq = AST__BAD;

    /** The SourceVel, in m/s for a velocity and dimensionless otherwise, or bad if unset. */
    private double sourceVel = AST__BAD;

    /** The SourceVRF code, or {@link #AST__BADSOR} if unset. */
    private int sourceVrfCode = AST__BADSOR;

    /** The SourceSys code, or {@link Frame#AST__BADSYSTEM} if unset. */
    private int sourceSys = AST__BADSYSTEM;

    /** The StdOfRest code, or {@link #AST__BADSOR} if unset. */
    private int stdOfRest = AST__BADSOR;

    /** The Unit last set with each System, indexed by System code, or null (C's {@code usedunits}). */
    private String[] usedUnits = new String[LAST_SYSTEM + 1];

    /** The SpecOrigin in the default units of the System, or bad if unset. */
    private double specOrigin = AST__BAD;

    /** The AlignSpecOffset flag, 0 or 1, or {@link #UNSET}. */
    private int alignSpecOffset = UNSET;

    /**
     * Creates a SpecFrame with every attribute at its default: wavelength in Angstrom, in the
     * heliocentric standard of rest.
     */
    public SpecFrame() {
        super(1);
    }

    /**
     * Loads a SpecFrame from its description, as C's {@code astLoadSpecFrame} does: SoR, AlSoR,
     * RefRA, RefDec, RstFrq, AlSpOf, SrcVel, SrcVRF, SrcSys, the Unit used with each System
     * ({@code U<System>}) and SpOrg, each unset if absent, and the GeoLat and GeoLon an old version
     * wrote, used as the ObsLat and ObsLon if those are not set.
     *
     * @param node the description
     * @throws AstException with status {@code AST__ATTIN} if a standard of rest or spectral system
     *     is not a known one
     */
    // C's loader passes the observer's position through the Frame class's setters.
    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    SpecFrame(NativeNode node) {
        super(node);
        NativeNode part = node.section("SpecFrame");
        String sval = part.getStringIfSet("SoR");
        if (sval != null) {
            stdOfRest = stdOfRestCode(sval);
            if (stdOfRest == AST__BADSOR) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astRead(Channel): Invalid standard of rest description \"" + sval + "\".");
            }
        }
        sval = part.getStringIfSet("AlSoR");
        if (sval != null) {
            alignStdOfRest = stdOfRestCode(sval);
            if (alignStdOfRest == AST__BADSOR) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astRead(Channel): Invalid alignment standard of rest description \"" + sval + "\".");
            }
        }
        if (!testObsLat()) {
            double obslat = part.readDouble("GeoLat", AST__BAD);
            if (obslat != AST__BAD) {
                setObsLatValue(obslat);
            }
        }
        if (!testObsLon()) {
            double obslon = part.readDouble("GeoLon", AST__BAD);
            if (obslon != AST__BAD) {
                setObsLonValue(obslon);
            }
        }
        refRaRadians = part.readDouble("RefRA", AST__BAD);
        refDecRadians = part.readDouble("RefDec", AST__BAD);
        restFreq = part.readDouble("RstFrq", AST__BAD);
        int ival = part.readInt("AlSpOf", UNSET);
        if (ival != UNSET) {
            alignSpecOffset = ival != 0 ? 1 : 0;
        }
        sourceVel = part.readDouble("SrcVel", AST__BAD);
        sval = part.getStringIfSet("SrcVRF");
        if (sval != null) {
            sourceVrfCode = stdOfRestCode(sval);
            if (sourceVrfCode == AST__BADSOR) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astRead(Channel): Invalid source velocity rest frame description \"" + sval + "\".");
            }
        }
        sval = part.getStringIfSet("SrcSys");
        if (sval != null) {
            sourceSys = systemCode(sval);
            if (sourceSys == AST__BADSYSTEM) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astRead(Channel): Invalid source velocity spectral system description \"" + sval + "\".");
            }
        }
        for (int sys = FIRST_SYSTEM; sys <= LAST_SYSTEM; sys++) {
            String unit = part.getStringIfSet(usedUnitKey(sys));
            if (unit != null) {
                usedUnits[sys] = unit;
            }
        }
        specOrigin = part.readDouble("SpOrg", AST__BAD);
    }

    @Override
    String dumpComment() {
        return "Description of spectral coordinate system";
    }

    /** The key of the dump item holding the Unit used with a System: {@code U} and its name, as {@code UFreq}. */
    private String usedUnitKey(int sys) {
        String name = systemString(sys);
        StringBuilder sb = new StringBuilder("U").append(name);
        for (int j = 2; j < sb.length(); j++) {
            sb.setCharAt(j, CScan.toLower(sb.charAt(j)));
        }
        return sb.toString();
    }

    /** Writes the SpecFrame class's part of a description, as C's {@code Dump} (specframe.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        boolean set = testStdOfRest();
        String sval = set ? corruptChecked(stdOfRest, "standard of rest identification code") : getAttrib("stdofrest");
        node.writeString("SoR", set, true, sval, "Standard of rest");
        set = testAlignStdOfRest();
        sval = set
                ? corruptChecked(alignStdOfRest, "alignment standard of rest identification code")
                : getAttrib("alignstdofrest");
        node.writeString("AlSoR", set, false, sval, "Alignment standard of rest");
        node.writeDouble("RefRA", testRefRA(), false, refRaValue(), "Reference RA (rads, FK5 J2000)");
        node.writeDouble("RefDec", testRefDec(), false, refDecValue(), "Reference Dec (rads, FK5 J2000)");
        node.writeDouble("RstFrq", testRestFreq(), false, restFreqValue(), "Rest frequency (Hz)");
        node.writeDouble("SrcVel", testSourceVel(), false, sourceVelValue(), "Source velocity (m/s)");
        set = testSourceVRF();
        sval = set
                ? corruptChecked(sourceVrfCode, "source velocity rest frame identification code")
                : getAttrib("sourcevrf");
        node.writeString("SrcVRF", set, false, sval, "Source velocity rest frame");
        set = testSourceSys();
        if (set) {
            sval = systemString(sourceSys);
            if (sval == null) {
                throw Ast.err(
                        AstException.AST__SCSIN,
                        "astWrite(Channel): Corrupt " + astClass()
                                + " contains invalid source velocity spectral system identification code ("
                                + sourceSys + ").");
            }
        } else {
            sval = getAttrib("sourcesys");
        }
        node.writeString("SrcSys", set, false, sval, "Source velocity spectral system");
        int ival = alignSpecOffsetValue() ? 1 : 0;
        node.writeInt(
                "AlSpOf",
                testAlignSpecOffset(),
                false,
                ival,
                ival != 0 ? "Align in offset coords" : "Align in system coords");
        for (int i = 0; i < usedUnits.length; i++) {
            if (usedUnits[i] != null) {
                node.writeString(usedUnitKey(i), true, false, usedUnits[i], "Preferred units for " + systemLabel(i));
            }
        }
        double dval = specOriginValue();
        if (dval != AST__BAD) {
            node.writeDouble("SpOrg", testSpecOrigin(), false, dval, "Spec offset");
        }
    }

    /** The name of a standard of rest code a dump writes, or the error C reports for a corrupt one. */
    private String corruptChecked(int sor, String what) {
        String s = stdOfRestString(sor);
        if (s == null) {
            throw Ast.err(
                    AstException.AST__SCSIN,
                    "astWrite(Channel): Corrupt " + astClass() + " contains invalid " + what + " (" + sor + ").");
        }
        return s;
    }

    /** Copies the record of the Unit used with each System, as C's {@code Copy} (specframe.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        usedUnits = usedUnits.clone();
    }

    /**
     * A deep copy of this SpecFrame, of a subclass, as an instance of the class of {@code obj} (C's
     * {@code astCastCopy} followed by {@code astCleanAttribs}): a SpecFrame with this Frame's
     * Object, Mapping, Frame and SpecFrame attributes, or a plain Frame as {@link Frame#castCopy}
     * gives it.
     */
    @Override
    Frame castCopy(Frame obj) {
        if (obj.getClass() != SpecFrame.class) {
            return super.castCopy(obj);
        }
        SpecFrame spec = new SpecFrame();
        spec.castCopyFrom(this);
        spec.cleanAttribs();
        return spec;
    }

    /** Takes the Object, Mapping, Frame and SpecFrame parts of {@code src}, as C's copy of a SpecFrame does. */
    @Override
    void castCopyFrom(AstObject src) {
        super.castCopyFrom(src);
        SpecFrame f = (SpecFrame) src;
        alignStdOfRest = f.alignStdOfRest;
        refDecRadians = f.refDecRadians;
        refRaRadians = f.refRaRadians;
        restFreq = f.restFreq;
        sourceVel = f.sourceVel;
        sourceVrfCode = f.sourceVrfCode;
        sourceSys = f.sourceSys;
        stdOfRest = f.stdOfRest;
        usedUnits = f.usedUnits.clone();
        specOrigin = f.specOrigin;
        alignSpecOffset = f.alignSpecOffset;
    }

    // ------------------------------------------------------------------
    // Systems and standards of rest
    // ------------------------------------------------------------------

    /**
     * Whether a System's values do not depend on the rest frequency (C's {@code ABS_SYSTEM}):
     * frequency, energy, wave-number and the wavelengths.
     */
    static boolean absSystem(int sys) {
        return sys == AST__ENERGY
                || sys == AST__WAVENUM
                || sys == AST__WAVELEN
                || sys == AST__AIRWAVE
                || sys == AST__FREQ;
    }

    /**
     * The standard of rest code a name gives, ignoring case and trailing white space, or {@link
     * #AST__BADSOR} (C's {@code StdOfRestCode}).
     */
    static int stdOfRestCode(String sor) {
        if (chrMatch("TOPO", sor) || chrMatch("TOPOCENT", sor) || chrMatch("TOPOCENTRIC", sor)) {
            return AST__TPSOR;
        } else if (chrMatch("GEO", sor) || chrMatch("GEOCENTR", sor) || chrMatch("GEOCENTRIC", sor)) {
            return AST__GESOR;
        } else if (chrMatch("BARY", sor) || chrMatch("BARYCENT", sor) || chrMatch("BARYCENTRIC", sor)) {
            return AST__BYSOR;
        } else if (chrMatch("HELIO", sor) || chrMatch("HELIOCEN", sor) || chrMatch("HELIOCENTRIC", sor)) {
            return AST__HLSOR;
        } else if (chrMatch("LSRK", sor) || chrMatch("LSR", sor)) {
            return AST__LKSOR;
        } else if (chrMatch("LSRD", sor)) {
            return AST__LDSOR;
        } else if (chrMatch("GAL", sor) || chrMatch("GALACTOC", sor) || chrMatch("GALACTIC", sor)) {
            return AST__GLSOR;
        } else if (chrMatch("LG", sor)
                || chrMatch("LOCALGRP", sor)
                || chrMatch("LOCAL_GROUP", sor)
                || chrMatch("LOCAL-GROUP", sor)) {
            return AST__LGSOR;
        } else if (chrMatch("SOURCE", sor) || chrMatch("SRC", sor)) {
            return AST__SCSOR;
        }
        return AST__BADSOR;
    }

    /** The name of a standard of rest code, or null (C's {@code StdOfRestString}). */
    static String stdOfRestString(int sor) {
        switch (sor) {
            case AST__TPSOR:
                return "Topocentric";
            case AST__GESOR:
                return "Geocentric";
            case AST__BYSOR:
                return "Barycentric";
            case AST__HLSOR:
                return "Heliocentric";
            case AST__LDSOR:
                return "LSRD";
            case AST__LKSOR:
                return "LSRK";
            case AST__LGSOR:
                return "Local_group";
            case AST__GLSOR:
                return "Galactic";
            case AST__SCSOR:
                return "Source";
            default:
                return null;
        }
    }

    /** The System code a name gives, ignoring case and trailing white space (C's {@code SystemCode}). */
    @Override
    int systemCode(String name) {
        if (chrMatch("FREQ", name)) {
            return AST__FREQ;
        } else if (chrMatch("ENER", name) || chrMatch("ENERGY", name)) {
            return AST__ENERGY;
        } else if (chrMatch("WAVN", name) || chrMatch("WAVENUM", name)) {
            return AST__WAVENUM;
        } else if (chrMatch("WAVE", name) || chrMatch("WAVELEN", name)) {
            return AST__WAVELEN;
        } else if (chrMatch("AWAV", name) || chrMatch("AIRWAVE", name)) {
            return AST__AIRWAVE;
        } else if (chrMatch("VRAD", name) || chrMatch("VRADIO", name)) {
            return AST__VRADIO;
        } else if (chrMatch("VOPT", name) || chrMatch("VOPTICAL", name)) {
            return AST__VOPTICAL;
        } else if (chrMatch("ZOPT", name) || chrMatch("REDSHIFT", name)) {
            return AST__REDSHIFT;
        } else if (chrMatch("BETA", name)) {
            return AST__BETA;
        } else if (chrMatch("VELO", name) || chrMatch("VREL", name)) {
            return AST__VREL;
        }
        return AST__BADSYSTEM;
    }

    /** The name of a System code, or null (C's {@code SystemString}). */
    @Override
    String systemString(int code) {
        switch (code) {
            case AST__FREQ:
                return "FREQ";
            case AST__ENERGY:
                return "ENER";
            case AST__WAVENUM:
                return "WAVN";
            case AST__WAVELEN:
                return "WAVE";
            case AST__AIRWAVE:
                return "AWAV";
            case AST__VRADIO:
                return "VRAD";
            case AST__VOPTICAL:
                return "VOPT";
            case AST__REDSHIFT:
                return "ZOPT";
            case AST__BETA:
                return "BETA";
            case AST__VREL:
                return "VELO";
            default:
                return null;
        }
    }

    /** The description of a System code, in lower case, or null (C's {@code SystemLabel}). */
    static String systemLabel(int system) {
        switch (system) {
            case AST__FREQ:
                return "frequency";
            case AST__ENERGY:
                return "energy";
            case AST__WAVENUM:
                return "wave-number";
            case AST__WAVELEN:
                return "wavelength";
            case AST__AIRWAVE:
                return "wavelength in air";
            case AST__VRADIO:
                return "radio velocity";
            case AST__VOPTICAL:
                return "optical velocity";
            case AST__REDSHIFT:
                return "redshift";
            case AST__BETA:
                return "beta factor";
            case AST__VREL:
                return "apparent radial velocity";
            default:
                return null;
        }
    }

    /** A System's description with its first letter in upper case, as C's labels and titles start. */
    private static String capitalised(int system) {
        String label = systemLabel(system);
        return CScan.toUpper(label.charAt(0)) + label.substring(1);
    }

    /**
     * Checks a System code (C's {@code ValidateSystem}).
     *
     * @throws AstException with status {@code AST__AXIIN} if it is not a spectral System
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
     * The default units of a System (C's {@code DefUnit}): {@code GHz}, {@code J}, {@code 1/m},
     * {@code Angstrom} for both wavelengths, {@code km/s} for the velocities, and none for redshift
     * and beta.
     *
     * @throws AstException with status {@code AST__SCSIN} if the System is not a spectral System
     */
    static String defUnit(int system, String method, String cls) {
        switch (system) {
            case AST__FREQ:
                return "GHz";
            case AST__ENERGY:
                return "J";
            case AST__WAVENUM:
                return "1/m";
            case AST__WAVELEN:
            case AST__AIRWAVE:
                return "Angstrom";
            case AST__VRADIO:
            case AST__VOPTICAL:
            case AST__VREL:
                return "km/s";
            case AST__REDSHIFT:
            case AST__BETA:
                return "";
            default:
                throw Ast.err(
                        AstException.AST__SCSIN,
                        method + "(" + cls + "): Corrupt " + cls + " contains illegal System identification code ("
                                + system + ").");
        }
    }

    /**
     * The units of the values a SpecMap converts in a System (C's {@code SpecMapUnit}): {@code
     * Hz}, {@code J}, {@code 1/m}, {@code m} for both wavelengths, {@code m/s} for the velocities,
     * and none for redshift and beta.
     *
     * @throws AstException with status {@code AST__SCSIN} if the System is not a spectral System
     */
    static String specMapUnit(int system, String method, String cls) {
        switch (system) {
            case AST__FREQ:
                return "Hz";
            case AST__ENERGY:
                return "J";
            case AST__WAVENUM:
                return "1/m";
            case AST__WAVELEN:
            case AST__AIRWAVE:
                return "m";
            case AST__VRADIO:
            case AST__VOPTICAL:
            case AST__VREL:
                return "m/s";
            case AST__REDSHIFT:
            case AST__BETA:
                return "";
            default:
                throw Ast.err(
                        AstException.AST__SCSIN,
                        method + "(" + cls + "): Corrupt " + cls + " contains illegal System identification code ("
                                + system + ").");
        }
    }

    @Override
    int systemValue() {
        return testSystem() ? super.systemValue() : AST__WAVELEN;
    }

    @Override
    int alignSystemValue() {
        return testAlignSystem() ? super.alignSystemValue() : AST__WAVELEN;
    }

    /**
     * Sets the System (C's {@code SetSystem}). If the System changes, the Unit last used with the
     * new System is restored, or the Unit cleared; a set SpecOrigin is re-expressed in the new
     * System; and the Label, Symbol and Title, whose defaults depend on the System, are cleared.
     */
    @Override
    void setSystemValue(int newsys) {
        int oldsys = systemValue();
        super.setSystemValue(newsys);
        if (oldsys != newsys) {
            restoreUnit(newsys);
            originSystem(oldsys, "astSetSystem");
            clearLabel(1);
            clearSymbol(1);
            clearTitle();
        }
    }

    /**
     * Clears the System (C's {@code ClearSystem}). If the System changes, the Unit last used with
     * the default System is restored, or the Unit cleared; the Label, Symbol and Title are
     * cleared; and a set SpecOrigin is re-expressed in the default System.
     */
    @Override
    void clearSystem() {
        int oldsys = systemValue();
        super.clearSystem();
        int newsys = systemValue();
        if (newsys != oldsys) {
            restoreUnit(newsys);
            clearLabel(1);
            clearSymbol(1);
            clearTitle();
            originSystem(oldsys, "astClearSystem");
        }
    }

    /** Sets the Unit to the one last used with System {@code sys}, or clears it if there is none. */
    private void restoreUnit(int sys) {
        if (sys < usedUnits.length && usedUnits[sys] != null) {
            setUnit(1, usedUnits[sys]);
        } else {
            clearUnit(1);
        }
    }

    // ------------------------------------------------------------------
    // SpecFrame attributes
    // ------------------------------------------------------------------

    /**
     * Gets the StdOfRest attribute: the standard of rest of the axis values. It is one of {@code
     * Topocentric} (the observer, on the surface of the Earth), {@code Geocentric} (the centre of
     * the Earth), {@code Barycentric} (the barycentre of the solar system), {@code Heliocentric}
     * (the centre of the Sun), {@code LSRK} (the kinematical local standard of rest), {@code LSRD}
     * (the dynamical local standard of rest), {@code Galactic} (the centre of the Milky Way),
     * {@code Local_group} (the Local Group) or {@code Source} (the source, moving at SourceVel
     * relative to SourceVRF towards RefRA, RefDec). Each is qualified as it needs by the Epoch,
     * ObsLon, ObsLat, ObsAlt, RefRA and RefDec attributes. The default is Heliocentric.
     *
     * @return the StdOfRest
     */
    public String getStdOfRest() {
        return getC("StdOfRest");
    }

    /**
     * Sets the StdOfRest attribute. A set SpecOrigin is re-expressed in the new standard of rest.
     *
     * @param stdOfRest the StdOfRest: any of the names {@link #getStdOfRest} gives, in any case, or
     *     the abbreviations {@code Topo}, {@code Topocent}, {@code Geo}, {@code Geocentr}, {@code
     *     Bary}, {@code Barycent}, {@code Helio}, {@code Heliocen}, {@code LSR}, {@code Gal}, {@code
     *     Galactoc}, {@code LG}, {@code LocalGrp}, {@code Local-group} or {@code Src}
     * @throws AstException with status {@code AST__ATTIN} if the name is not a standard of rest
     */
    public void setStdOfRest(String stdOfRest) {
        setC("StdOfRest", stdOfRest);
    }

    /** The StdOfRest code: set, or {@link #AST__HLSOR} (C's {@code GetStdOfRest}). */
    int stdOfRestValue() {
        return stdOfRest != AST__BADSOR ? stdOfRest : AST__HLSOR;
    }

    /**
     * Sets the StdOfRest code (C's {@code SetStdOfRest}), first re-expressing a set SpecOrigin in
     * it.
     *
     * @throws AstException with status {@code AST__ATTIN} if the code is not a standard of rest,
     *     or {@code AST__ATSER} if the SpecOrigin cannot be re-expressed
     */
    void setStdOfRestValue(int value) {
        if (value < FIRST_SOR || value > LAST_SOR) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetStdOfRest(" + astClass() + "): Bad value (" + value + ") given for StdOfRest attribute.");
        }
        originStdOfRest(value, "astSetStdOfRest");
        stdOfRest = value;
    }

    boolean testStdOfRest() {
        return stdOfRest != AST__BADSOR;
    }

    /**
     * Clears the StdOfRest (C's {@code ClearStdOfRest}), first re-expressing a set SpecOrigin in
     * the heliocentric standard of rest.
     */
    void clearStdOfRest() {
        originStdOfRest(AST__HLSOR, "astClearStdOfRest");
        stdOfRest = AST__BADSOR;
    }

    /**
     * Gets the AlignStdOfRest attribute: the standard of rest in which two SpecFrames are aligned
     * when one is converted to the other. The conversion found goes from the target's System to the
     * AlignSystem, then from the target's standard of rest to the AlignStdOfRest, from that to the
     * template's standard of rest, and from the AlignSystem to the template's System. It takes the
     * values {@link #getStdOfRest} describes; the default is Heliocentric.
     *
     * @return the AlignStdOfRest
     */
    public String getAlignStdOfRest() {
        return getC("AlignStdOfRest");
    }

    /**
     * Sets the AlignStdOfRest attribute.
     *
     * @param alignStdOfRest the AlignStdOfRest, as {@link #setStdOfRest} accepts it
     * @throws AstException with status {@code AST__ATTIN} if the name is not a standard of rest
     */
    public void setAlignStdOfRest(String alignStdOfRest) {
        setC("AlignStdOfRest", alignStdOfRest);
    }

    /** The AlignStdOfRest code: set, or {@link #AST__HLSOR}. */
    int alignStdOfRestValue() {
        return alignStdOfRest != AST__BADSOR ? alignStdOfRest : AST__HLSOR;
    }

    /**
     * Sets the AlignStdOfRest code.
     *
     * @throws AstException with status {@code AST__ATTIN} if the code is not a standard of rest
     */
    void setAlignStdOfRestValue(int value) {
        if (value < FIRST_SOR || value > LAST_SOR) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetAlignStdOfRest(" + astClass() + "): Bad value (" + value
                            + ") given for AlignStdOfRest attribute.");
        }
        alignStdOfRest = value;
    }

    boolean testAlignStdOfRest() {
        return alignStdOfRest != AST__BADSOR;
    }

    void clearAlignStdOfRest() {
        alignStdOfRest = AST__BADSOR;
    }

    /**
     * Gets the RefRA attribute: the right ascension (FK5 J2000) of the source, towards which the
     * Source standard of rest moves and which the other standards of rest are corrected for, as
     * hours, minutes and seconds. The default is zero. {@link #getRefPos} gives the position in
     * another celestial coordinate system.
     *
     * @return the RefRA
     */
    public String getRefRA() {
        return getC("RefRA");
    }

    /**
     * Sets the RefRA attribute.
     *
     * @param refRA the right ascension (FK5 J2000), as an FK5 J2000 SkyFrame reads it, such as
     *     {@code 10:30:00}
     * @throws AstException with status {@code AST__ATTIN} if the value cannot be read
     */
    public void setRefRA(String refRA) {
        setC("RefRA", refRA);
    }

    /** The RefRA in radians: set, or zero (C's {@code GetRefRA}). */
    double refRaValue() {
        return refRaRadians != AST__BAD ? refRaRadians : 0.0;
    }

    void setRefRaValue(double value) {
        refRaRadians = value;
    }

    boolean testRefRA() {
        return refRaRadians != AST__BAD;
    }

    void clearRefRA() {
        refRaRadians = AST__BAD;
    }

    /**
     * Gets the RefDec attribute: the declination (FK5 J2000) of the source, as degrees, minutes and
     * seconds. The default is zero.
     *
     * @return the RefDec
     */
    public String getRefDec() {
        return getC("RefDec");
    }

    /**
     * Sets the RefDec attribute.
     *
     * @param refDec the declination (FK5 J2000), as an FK5 J2000 SkyFrame reads it, such as {@code
     *     -30:00:00}
     * @throws AstException with status {@code AST__ATTIN} if the value cannot be read
     */
    public void setRefDec(String refDec) {
        setC("RefDec", refDec);
    }

    /** The RefDec in radians: set, or zero (C's {@code GetRefDec}). */
    double refDecValue() {
        return refDecRadians != AST__BAD ? refDecRadians : 0.0;
    }

    void setRefDecValue(double value) {
        refDecRadians = value;
    }

    boolean testRefDec() {
        return refDecRadians != AST__BAD;
    }

    void clearRefDec() {
        refDecRadians = AST__BAD;
    }

    /**
     * Gets the RestFreq attribute: the frequency of zero velocity, in GHz, used when converting
     * between a velocity or redshift and the other Systems. The default is 1.0E5 GHz.
     *
     * @return the RestFreq, in GHz
     */
    public double getRestFreq() {
        return getD("RestFreq");
    }

    /**
     * Sets the RestFreq attribute.
     *
     * @param restFreq the RestFreq, in GHz
     */
    public void setRestFreq(double restFreq) {
        setD("RestFreq", restFreq);
    }

    /**
     * Sets the RestFreq attribute from text: a frequency in GHz, or a value followed by its units,
     * which may be those of a frequency, of a (vacuum) wavelength or of an energy, the value then
     * being converted to a frequency. {@code 1.4E5}, {@code 1.4E14 Hz}, {@code 2.14E-6 m}, {@code
     * 21400 Angstrom}, {@code 9.28E-20 J} and {@code 0.58 eV} all give about 1.4E14 Hz.
     *
     * @param restFreq the RestFreq
     * @throws AstException with status {@code AST__ATTIN} if the units are not a frequency,
     *     wavelength or energy, or the wavelength is zero
     */
    public void setRestFreq(String restFreq) {
        set("RestFreq=" + restFreq);
    }

    /** The RestFreq in Hz: set, or 1.0E14 (C's {@code GetRestFreq}). */
    double restFreqValue() {
        return restFreq != AST__BAD ? restFreq : 1.0E14;
    }

    void setRestFreqValue(double value) {
        restFreq = value;
    }

    boolean testRestFreq() {
        return restFreq != AST__BAD;
    }

    void clearRestFreq() {
        restFreq = AST__BAD;
    }

    /**
     * Gets the SourceVel attribute: the velocity of the source, which defines the Source standard
     * of rest, in the spectral system given by SourceSys (km/s for a velocity) and the rest frame
     * given by SourceVRF. A positive value means the source moves away from the observer. The
     * default is zero.
     *
     * @return the SourceVel
     */
    public double getSourceVel() {
        return getD("SourceVel");
    }

    /**
     * Sets the SourceVel attribute, in the spectral system given by SourceSys and the rest frame
     * given by SourceVRF, which should be set first: a later change to either re-expresses the
     * SourceVel in the new one.
     *
     * @param sourceVel the SourceVel
     */
    public void setSourceVel(double sourceVel) {
        setD("SourceVel", sourceVel);
    }

    /** The SourceVel, in m/s for a velocity: set, or zero (C's {@code GetSourceVel}). */
    double sourceVelValue() {
        return sourceVel != AST__BAD ? sourceVel : 0.0;
    }

    void setSourceVelValue(double value) {
        sourceVel = value;
    }

    boolean testSourceVel() {
        return sourceVel != AST__BAD;
    }

    void clearSourceVel() {
        sourceVel = AST__BAD;
    }

    /** Whether SourceSys is a velocity, which the SourceVel attribute gives in km/s. */
    private boolean sourceVelInKms() {
        int sys = sourceSysValue();
        return sys == AST__VREL || sys == AST__VRADIO || sys == AST__VOPTICAL;
    }

    /**
     * Gets the SourceVRF attribute as a number, as jniast's accessor does. The attribute is the
     * rest frame of the SourceVel, a standard of rest as {@link #getStdOfRest} describes them
     * other than Source; its value is the name of one, so this accessor fails: {@code
     * getC("SourceVRF")} gives the name.
     *
     * @return the SourceVRF, read as a number
     * @throws AstException with status {@code AST__ATGER}, as the value is not a number
     */
    public double getSourceVRF() {
        return getD("SourceVRF");
    }

    /**
     * Sets the SourceVRF attribute from a number, as jniast's accessor does. The attribute takes
     * the name of a standard of rest, so this accessor fails: {@code setC("SourceVRF", name)} sets
     * it, re-expressing a set SourceVel in the new rest frame.
     *
     * @param sourceVRF the value, written as a number
     * @throws AstException with status {@code AST__ATTIN}, as a number is not a standard of rest
     */
    public void setSourceVRF(double sourceVRF) {
        setD("SourceVRF", sourceVRF);
    }

    /** The SourceVRF code: set, or {@link #AST__HLSOR}. */
    int sourceVrfValue() {
        return sourceVrfCode != AST__BADSOR ? sourceVrfCode : AST__HLSOR;
    }

    /**
     * Sets the SourceVRF code, first re-expressing a set SourceVel in the new rest frame.
     *
     * @throws AstException with status {@code AST__ATTIN} if the code is not a standard of rest,
     *     or is the Source rest frame
     */
    void setSourceVrfValue(int value) {
        if (value < FIRST_SOR || value > LAST_SOR || value == AST__SCSOR) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetSourceVRF(" + astClass() + "): Bad value (" + value + ") given for SourceVRF attribute.");
        }
        if (testSourceVel()) {
            setSourceVelValue(convertSourceVel(value, sourceSysValue()));
        }
        sourceVrfCode = value;
    }

    boolean testSourceVRF() {
        return sourceVrfCode != AST__BADSOR;
    }

    /** Clears the SourceVRF, first re-expressing a set SourceVel in the heliocentric rest frame. */
    void clearSourceVRF() {
        if (testSourceVel()) {
            setSourceVelValue(convertSourceVel(AST__HLSOR, sourceSysValue()));
        }
        sourceVrfCode = AST__BADSOR;
    }

    /**
     * Gets the SourceSys attribute: the spectral system of the SourceVel, one of {@code VRAD},
     * {@code VOPT}, {@code ZOPT}, {@code BETA} and {@code VELO} (the default).
     *
     * @return the SourceSys
     */
    public String getSourceSys() {
        return getC("SourceSys");
    }

    /**
     * Sets the SourceSys attribute, re-expressing a set SourceVel in the new spectral system.
     *
     * @param sourceSys the SourceSys, in any case, or the long forms {@code VRADIO}, {@code
     *     VOPTICAL}, {@code REDSHIFT} or {@code VREL}
     * @throws AstException with status {@code AST__ATTIN} if the name is not one of these
     */
    public void setSourceSys(String sourceSys) {
        setC("SourceSys", sourceSys);
    }

    /** The SourceSys code: set, or {@link #AST__VREL}. */
    int sourceSysValue() {
        return sourceSys != AST__BADSYSTEM ? sourceSys : AST__VREL;
    }

    /**
     * Sets the SourceSys code, first re-expressing a set SourceVel in the new spectral system.
     *
     * @throws AstException with status {@code AST__ATTIN} if the code is not a velocity, redshift
     *     or beta
     */
    void setSourceSysValue(int value) {
        if (value != AST__VREL
                && value != AST__BETA
                && value != AST__VRADIO
                && value != AST__REDSHIFT
                && value != AST__VOPTICAL) {
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetSourceSys(" + astClass() + "): Bad value (" + value + ") given for SourceSys attribute.");
        }
        if (testSourceVel()) {
            setSourceVelValue(convertSourceVel(sourceVrfValue(), value));
        }
        sourceSys = value;
    }

    boolean testSourceSys() {
        return sourceSys != AST__BADSYSTEM;
    }

    /** Clears the SourceSys, first re-expressing a set SourceVel as an apparent radial velocity. */
    void clearSourceSys() {
        if (testSourceVel()) {
            setSourceVelValue(convertSourceVel(sourceVrfValue(), AST__VREL));
        }
        sourceSys = AST__BADSYSTEM;
    }

    /** The SpecOrigin in the default units of the System: set, or zero (C's {@code GetSpecOrigin}). */
    double specOriginValue() {
        return specOrigin != AST__BAD ? specOrigin : 0.0;
    }

    void setSpecOriginValue(double value) {
        specOrigin = value;
    }

    boolean testSpecOrigin() {
        return specOrigin != AST__BAD;
    }

    void clearSpecOrigin() {
        specOrigin = AST__BAD;
    }

    /**
     * The SpecOrigin in the units of the axis (C's {@code GetSpecOriginCur}), which the SpecOrigin
     * attribute reports.
     *
     * @throws AstException with status {@code AST__BADUN} if the Unit cannot be converted from the
     *     System's default units
     */
    double specOriginCur() {
        double result = specOriginValue();
        if (result != 0.0 && result != AST__BAD) {
            String def = defUnit(systemValue(), "astGetSpecOrigin", "SpecFrame");
            String cur = getUnit(1);
            if (!cur.equals(def)) {
                Mapping map = Units.unitMapper(def, cur);
                if (map == null) {
                    throw Ast.err(
                            AstException.AST__BADUN,
                            "astGetSpecOrigin(" + astClass() + "): The current units (" + cur
                                    + ") are not suitable for a SpecFrame.");
                }
                result = transformValue(map, result);
            }
        }
        return result;
    }

    /** The AlignSpecOffset flag: set, or false (C's {@code GetAlignSpecOffset}). */
    boolean alignSpecOffsetValue() {
        return alignSpecOffset != UNSET && alignSpecOffset != 0;
    }

    void setAlignSpecOffsetValue(boolean value) {
        alignSpecOffset = value ? 1 : 0;
    }

    boolean testAlignSpecOffset() {
        return alignSpecOffset != UNSET;
    }

    void clearAlignSpecOffset() {
        alignSpecOffset = UNSET;
    }

    /**
     * Gets the GeoLat attribute, the latitude of the observer, which is the ObsLat attribute under
     * the name an older version of the SpecFrame used.
     *
     * @return the ObsLat
     */
    public String getGeoLat() {
        return getObsLat();
    }

    /**
     * Sets the GeoLat attribute, which sets the ObsLat attribute.
     *
     * @param geoLat the latitude, as {@code N} or {@code S} followed by an angle in degrees
     * @throws AstException with status {@code AST__ATTIN} if the value cannot be read
     */
    public void setGeoLat(String geoLat) {
        setObsLat(geoLat);
    }

    /**
     * Gets the GeoLon attribute, the longitude of the observer, which is the ObsLon attribute under
     * the name an older version of the SpecFrame used.
     *
     * @return the ObsLon
     */
    public String getGeoLon() {
        return getObsLon();
    }

    /**
     * Sets the GeoLon attribute, which sets the ObsLon attribute.
     *
     * @param geoLon the longitude, as {@code E} or {@code W} followed by an angle in degrees
     * @throws AstException with status {@code AST__ATTIN} if the value cannot be read
     */
    public void setGeoLon(String geoLon) {
        setObsLon(geoLon);
    }

    // ------------------------------------------------------------------
    // The reference position
    // ------------------------------------------------------------------

    /**
     * The FK5 J2000 SkyFrame in which C formats and reads RefRA and RefDec and stores the reference
     * position.
     */
    private static SkyFrame refSkyFrame() {
        SkyFrame sf = new SkyFrame();
        sf.set("system=FK5,equinox=J2000");
        return sf;
    }

    /**
     * Gets the reference position (the RefRA and RefDec attributes) in the celestial coordinate
     * system of a SkyFrame (C's {@code astGetRefPos}).
     *
     * @param frm the SkyFrame giving the coordinate system, or null for FK5 J2000
     * @return the longitude and latitude, in radians; both bad if there is no conversion to the
     *     SkyFrame's coordinate system
     */
    public double[] getRefPos(SkyFrame frm) {
        if (frm == null) {
            return new double[] {refRaValue(), refDecValue()};
        }
        double lon = AST__BAD;
        double lat = AST__BAD;
        FrameSet fs = refSkyFrame().findFrame(frm, "");
        if (fs != null) {
            SkyFrame fb = (SkyFrame) fs.getFrame(FrameSet.AST__BASE);
            double[] xin;
            double[] yin;
            if (fb.getLonAxis() == 1) {
                xin = new double[] {refRaValue()};
                yin = new double[] {refDecValue()};
            } else {
                yin = new double[] {refRaValue()};
                xin = new double[] {refDecValue()};
            }
            double[][] out = fs.tran2(1, xin, yin, true);
            SkyFrame fc = (SkyFrame) fs.getFrame(FrameSet.AST__CURRENT);
            if (fc.getLonAxis() == 1) {
                lon = out[0][0];
                lat = out[1][0];
            } else {
                lon = out[1][0];
                lat = out[0][0];
            }
        }
        return new double[] {lon, lat};
    }

    /**
     * Sets the reference position (the RefRA and RefDec attributes) from a position in the
     * celestial coordinate system of a SkyFrame, converted to FK5 J2000 (C's {@code
     * astSetRefPos}). Nothing is set if there is no conversion from the SkyFrame's coordinate
     * system.
     *
     * @param frm the SkyFrame giving the coordinate system, or null for FK5 J2000
     * @param lon the longitude, in radians
     * @param lat the latitude, in radians
     */
    public void setRefPos(SkyFrame frm, double lon, double lat) {
        if (frm == null) {
            setRefRaValue(lon);
            setRefDecValue(lat);
            return;
        }
        FrameSet fs = frm.findFrame(refSkyFrame(), "");
        if (fs != null) {
            SkyFrame fb = (SkyFrame) fs.getFrame(FrameSet.AST__BASE);
            double[] xin;
            double[] yin;
            if (fb.getLonAxis() == 1) {
                xin = new double[] {lon};
                yin = new double[] {lat};
            } else {
                xin = new double[] {lat};
                yin = new double[] {lon};
            }
            double[][] out = fs.tran2(1, xin, yin, true);
            SkyFrame fc = (SkyFrame) fs.getFrame(FrameSet.AST__CURRENT);
            if (fc.getLonAxis() == 1) {
                setRefRaValue(out[0][0]);
                setRefDecValue(out[1][0]);
            } else {
                setRefRaValue(out[1][0]);
                setRefDecValue(out[0][0]);
            }
        }
    }

    // ------------------------------------------------------------------
    // Frame attributes with SpecFrame defaults
    // ------------------------------------------------------------------

    /** The Domain: set, or {@code SPECTRUM}. */
    @Override
    public String getDomain() {
        return testDomain() ? super.getDomain() : "SPECTRUM";
    }

    /**
     * Gets the ActiveUnit flag, which is always true for a SpecFrame: a difference in the Unit of
     * two SpecFrames is always allowed for when one is converted to the other.
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

    /** Gets the Unit of the axis: set, or the default units of the System. */
    @Override
    public String getUnit(int axis) {
        validateAxis(axis, true, "astGetUnit");
        if (testUnit(axis)) {
            return super.getUnit(axis);
        }
        return defUnit(systemValue(), "astGetUnit", astClass());
    }

    /**
     * Sets the Unit of the axis (C's {@code SetUnit}), recording it as the Unit used with the
     * current System, which comes back when the System does. Units of another kind than the
     * System's, such as Angstrom for frequency, are accepted; a SpecOrigin cannot then be given or
     * read, and the SpecFrame cannot be converted.
     */
    @Override
    public void setUnit(int axis, String unit) {
        validateAxis(axis, true, "astSetUnit");
        int system = systemValue();
        if (usedUnits[system] == null || !usedUnits[system].equals(unit)) {
            usedUnits[system] = unit;
        }
        super.setUnit(axis, unit);
    }

    /** Clears the Unit of the axis and the record of the Unit used with the current System (C's {@code ClearUnit}). */
    @Override
    void clearUnit(int axis) {
        validateAxis(axis, true, "astClearUnit");
        int system = systemValue();
        if (system < usedUnits.length) {
            usedUnits[system] = null;
        }
        super.clearUnit(axis);
    }

    /**
     * Gets the Label of the axis (C's {@code GetLabel}): set; or the System's description, followed
     * by {@code offset from} and the formatted SpecOrigin if it is not zero, and rewritten for a set
     * Unit, as {@code log( Frequency )} for {@code log(GHz)}.
     *
     * @throws AstException with status {@code AST__BADUN} if a SpecOrigin is set and the Unit
     *     cannot be converted from the System's default units
     */
    @Override
    public String getLabel(int axis) {
        validateAxis(axis, true, "astGetLabel");
        if (testLabel(axis)) {
            return super.getLabel(axis);
        }
        int system = systemValue();
        String result = capitalised(system);
        double orig = specOriginCur();
        if (orig != 0.0) {
            result = result + " offset from " + format(1, orig);
        }
        if (testUnit(axis)) {
            Units.LabelledMapping lm = Units.relabel(defUnit(system, "astGetLabel", astClass()), getUnit(axis), result);
            if (lm != null && lm.label != null) {
                result = lm.label;
            }
        }
        return result;
    }

    /**
     * Gets the Symbol of the axis (C's {@code GetSymbol}): set, or the System's name ({@code FREQ},
     * {@code ENER}, {@code WAVN}, {@code WAVE}, {@code AWAV}, {@code VRAD}, {@code VOPT}, {@code
     * ZOPT}, {@code BETA} or {@code VELO}), rewritten for a set Unit.
     */
    @Override
    public String getSymbol(int axis) {
        validateAxis(axis, true, "astGetSymbol");
        if (testSymbol(axis)) {
            return super.getSymbol(axis);
        }
        int system = systemValue();
        String result = systemString(system);
        if (result == null) {
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
     * StdOfRest in brackets if it is set, and for a velocity, redshift or beta by the rest
     * frequency in GHz if the RestFreq is set or UseDefs is true.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        if (testTitle()) {
            return super.getTitle();
        }
        int system = systemValue();
        String sorString = stdOfRestString(stdOfRestValue());
        double rf = restFreqValue();
        StringBuilder sb = new StringBuilder(capitalised(system));
        if (testStdOfRest()) {
            sb.append(" (").append(sorString).append(')');
        }
        if (!absSystem(system) && (testRestFreq() || getUseDefs())) {
            sb.append(", rest frequency = ")
                    .append(CFormat.format("%g", rf * 1.0E-9))
                    .append(" GHz");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Conversions of attribute values
    // ------------------------------------------------------------------

    /** One value transformed by a Mapping with one input and one output. */
    private static double transformValue(Mapping map, double value) {
        return map.tran1(1, new double[] {value}, true)[0];
    }

    /**
     * A value in units {@code oldunit} in the default units of the System (C's {@code ToUnits}).
     *
     * @throws AstException with status {@code AST__BADUN} if the units cannot be converted
     */
    double toUnits(String oldunit, double oldval, String method) {
        String defunit = defUnit(systemValue(), method, "SpecFrame");
        Mapping map = Units.unitMapper(oldunit, defunit);
        if (map == null) {
            throw Ast.err(
                    AstException.AST__BADUN,
                    method + "(" + astClass() + "): Cannot convert the supplied attribute value from units of "
                            + oldunit + " to " + defunit + ".");
        }
        return transformValue(map, oldval);
    }

    /**
     * Re-expresses a set SpecOrigin in standard of rest {@code newsor}, before the StdOfRest
     * changes to it (C's {@code OriginStdOfRest}): through the conversion from this SpecFrame to a
     * copy in the new standard of rest.
     *
     * @throws AstException with status {@code AST__ATSER} if the SpecOrigin cannot be converted
     */
    private void originStdOfRest(int newsor, String method) {
        if (!testSpecOrigin() || newsor == stdOfRestValue()) {
            return;
        }
        double origin = specOriginCur();
        clearSpecOrigin();
        SpecFrame sf = (SpecFrame) copy();
        sf.setStdOfRestValue(newsor);
        FrameSet fs = convert(sf, "");
        double neworigin = AST__BAD;
        if (fs != null) {
            neworigin = transformValue(fs, origin);
        }
        if (neworigin == AST__BAD) {
            throw Ast.err(
                    AstException.AST__ATSER,
                    method + "(" + astClass() + "): Cannot convert the SpecOrigin value to a different rest frame.");
        }
        setSpecOriginValue(toUnits(getUnit(1), neworigin, method));
    }

    /**
     * Re-expresses a set SpecOrigin, in System {@code oldsys}, in the System the SpecFrame now has
     * (C's {@code OriginSystem}): through the conversion between two copies with no Unit,
     * SpecOrigin or AlignSpecOffset, one in the old System and one in the new.
     *
     * @throws AstException with status {@code AST__ATSER} if the SpecOrigin cannot be converted
     */
    private void originSystem(int oldsys, String method) {
        if (!testSpecOrigin() || oldsys == systemValue()) {
            return;
        }
        double origin = specOriginValue();
        SpecFrame sf1 = (SpecFrame) copy();
        sf1.clearUnit(1);
        sf1.clearSpecOrigin();
        sf1.clearAlignSpecOffset();
        SpecFrame sf2 = (SpecFrame) sf1.copy();
        sf2.setSystemValue(oldsys);
        FrameSet fs = sf2.convert(sf1, "");
        double neworigin = AST__BAD;
        if (fs != null) {
            neworigin = transformValue(fs, origin);
        }
        if (neworigin == AST__BAD) {
            throw Ast.err(
                    AstException.AST__ATSER,
                    method + "(" + astClass() + "): Cannot convert the SpecOrigin value to a different spectral"
                            + " system.");
        }
        setSpecOriginValue(neworigin);
    }

    /**
     * The SourceVel in rest frame {@code newsor} and spectral system {@code newsys} (C's {@code
     * ConvertSourceVel}): in m/s for a velocity, through a SpecMap from the SourceSys to apparent
     * radial velocity, to frequency, from the SourceVRF to {@code newsor}, and back.
     *
     * @throws AstException with status {@code AST__NOVAL} if UseDefs is false and the SourceVel, or
     *     for a conversion the RestFreq, is not set, or the error of the change of rest frame
     */
    double convertSourceVel(int newsor, int newsys) {
        String purp = "convert source velocity to a new standard of rest";
        double ret = sourceVelValue();
        verifyAttrs(purp, "SourceVel", "astMatch");
        int sor = sourceVrfValue();
        int sys = sourceSysValue();
        if (sor != newsor || sys != newsys) {
            verifyAttrs(purp, "RestFreq", "astMatch");
            SpecFrame from = (SpecFrame) copy();
            from.setStdOfRestValue(sor);
            SpecFrame to = (SpecFrame) copy();
            to.setStdOfRestValue(newsor);
            SpecMap specmap = new SpecMap(1, 0);
            if (sys == AST__VRADIO) {
                specmap.specAdd("VRTOVL", null);
            } else if (sys == AST__VOPTICAL) {
                specmap.specAdd("VOTOVL", null);
            } else if (sys == AST__REDSHIFT) {
                specmap.specAdd("ZOTOVL", null);
            } else if (sys == AST__BETA) {
                specmap.specAdd("BTTOVL", null);
            }
            double rf = restFreqValue();
            specmap.specAdd("VLTOFR", new double[] {rf});
            sorConvert(from, to, specmap);
            specmap.specAdd("FRTOVL", new double[] {rf});
            if (newsys == AST__VRADIO) {
                specmap.specAdd("VLTOVR", null);
            } else if (newsys == AST__VOPTICAL) {
                specmap.specAdd("VLTOVO", null);
            } else if (newsys == AST__REDSHIFT) {
                specmap.specAdd("VLTOZO", null);
            } else if (newsys == AST__BETA) {
                specmap.specAdd("VLTOBT", null);
            }
            ret = transformValue(specmap, ret);
        }
        return ret;
    }

    /**
     * Whether two SpecFrames use the same standard of rest (C's {@code EqualSor}): the same
     * StdOfRest, RefRA and RefDec, and what that standard of rest depends on: the SourceVel,
     * SourceVRF and SourceSys for the source, the Epoch for the geocentric, barycentric and
     * heliocentric ones, and the Epoch and observer's position for the topocentric one.
     */
    static boolean equalSor(SpecFrame self, SpecFrame that) {
        int sor = self.stdOfRestValue();
        if (that.stdOfRestValue() != sor) {
            return false;
        }
        if (!MappingHelpers.astEqual(self.refRaValue(), that.refRaValue())
                || !MappingHelpers.astEqual(self.refDecValue(), that.refDecValue())) {
            return false;
        }
        if (sor == AST__SCSOR) {
            return MappingHelpers.astEqual(self.sourceVelValue(), that.sourceVelValue())
                    && self.sourceVrfValue() == that.sourceVrfValue()
                    && self.sourceSysValue() == that.sourceSysValue();
        } else if (sor == AST__GESOR || sor == AST__BYSOR || sor == AST__HLSOR) {
            return MappingHelpers.astEqual(self.getEpochMjd(), that.getEpochMjd());
        } else if (sor == AST__TPSOR) {
            return MappingHelpers.astEqual(self.getEpochMjd(), that.getEpochMjd())
                    && MappingHelpers.astEqual(self.getObsAlt(), that.getObsAlt())
                    && MappingHelpers.astEqual(self.getObsLonValue(), that.getObsLonValue())
                    && MappingHelpers.astEqual(self.getObsLatValue(), that.getObsLatValue());
        } else if (sor == AST__LKSOR || sor == AST__LDSOR || sor == AST__GLSOR || sor == AST__LGSOR) {
            return true;
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "SorEqual(SpecFrame): Function SorEqual does not yet support rest frame " + sor
                            + " (AST internal programming error)");
        }
    }

    /**
     * Adds to {@code specmap} the conversion of a frequency from the standard of rest of {@code
     * self} to that of {@code that}, through the heliocentric one, unless they are the same (C's
     * {@code SorConvert}). Each side's RefRA and RefDec, and the Epoch, observer's position and
     * source velocity its standard of rest needs, are used.
     *
     * @throws AstException with status {@code AST__NOVAL} if UseDefs is false on a side and an
     *     attribute it needs is not set
     */
    static void sorConvert(SpecFrame self, SpecFrame that, SpecMap specmap) {
        if (equalSor(self, that)) {
            return;
        }
        String vmess = "convert between different standards of rest";
        int from = self.stdOfRestValue();
        double ra = self.refRaValue();
        double dec = self.refDecValue();
        double lon = self.getObsLonValue();
        double lat = self.getObsLatValue();
        double alt = self.getObsAlt();
        double epoch = self.getEpochMjd();
        self.verifyAttrs(vmess, "RefRA RefDec", "astMatch");
        if (from == AST__TPSOR) {
            self.verifyAttrs(vmess, "ObsLon ObsLat ObsAlt Epoch", "astMatch");
            specmap.specAdd("TPF2HL", new double[] {lon, lat, alt, epoch, ra, dec});
        } else if (from == AST__GESOR) {
            self.verifyAttrs(vmess, "Epoch", "astMatch");
            specmap.specAdd("GEF2HL", new double[] {epoch, ra, dec});
        } else if (from == AST__BYSOR) {
            self.verifyAttrs(vmess, "Epoch", "astMatch");
            specmap.specAdd("BYF2HL", new double[] {epoch, ra, dec});
        } else if (from == AST__LKSOR) {
            specmap.specAdd("LKF2HL", new double[] {ra, dec});
        } else if (from == AST__LDSOR) {
            specmap.specAdd("LDF2HL", new double[] {ra, dec});
        } else if (from == AST__LGSOR) {
            specmap.specAdd("LGF2HL", new double[] {ra, dec});
        } else if (from == AST__GLSOR) {
            specmap.specAdd("GLF2HL", new double[] {ra, dec});
        } else if (from == AST__SCSOR) {
            specmap.specAdd("USF2HL", new double[] {self.convertSourceVel(AST__HLSOR, AST__VREL), ra, dec});
        }
        int to = that.stdOfRestValue();
        ra = that.refRaValue();
        dec = that.refDecValue();
        lon = that.getObsLonValue();
        lat = that.getObsLatValue();
        alt = that.getObsAlt();
        epoch = that.getEpochMjd();
        that.verifyAttrs(vmess, "RefRA RefDec", "astMatch");
        if (to == AST__TPSOR) {
            that.verifyAttrs(vmess, "ObsLon ObsLat ObsAlt Epoch", "astMatch");
            specmap.specAdd("HLF2TP", new double[] {lon, lat, alt, epoch, ra, dec});
        } else if (to == AST__GESOR) {
            that.verifyAttrs(vmess, "Epoch", "astMatch");
            specmap.specAdd("HLF2GE", new double[] {epoch, ra, dec});
        } else if (to == AST__BYSOR) {
            that.verifyAttrs(vmess, "Epoch", "astMatch");
            specmap.specAdd("HLF2BY", new double[] {epoch, ra, dec});
        } else if (to == AST__LKSOR) {
            specmap.specAdd("HLF2LK", new double[] {ra, dec});
        } else if (to == AST__LDSOR) {
            specmap.specAdd("HLF2LD", new double[] {ra, dec});
        } else if (to == AST__LGSOR) {
            specmap.specAdd("HLF2LG", new double[] {ra, dec});
        } else if (to == AST__GLSOR) {
            specmap.specAdd("HLF2GL", new double[] {ra, dec});
        } else if (to == AST__SCSOR) {
            specmap.specAdd("HLF2US", new double[] {that.convertSourceVel(AST__HLSOR, AST__VREL), ra, dec});
        }
    }

    /**
     * C's {@code VerifyAttrs}: with UseDefs false, checks that each attribute named in {@code
     * attrs} (separated by white space, a name standing for every attribute it starts) is set.
     *
     * @throws AstException with status {@code AST__NOVAL} if one is not, saying that the SpecFrame
     *     cannot do {@code purp}
     */
    void verifyAttrs(String purp, String attrs, String method) {
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
                desc = "observer's latitude";
            } else if ("ObsLon".startsWith(a)) {
                set = testObsLon();
                desc = "observer's longitude";
            } else if ("ObsAlt".startsWith(a)) {
                set = testObsAlt();
                desc = "observer's altitude";
            } else if ("RefRA".startsWith(a)) {
                set = testRefRA();
                desc = "source RA";
            } else if ("RefDec".startsWith(a)) {
                set = testRefDec();
                desc = "source Dec";
            } else if ("RestFreq".startsWith(a)) {
                set = testRestFreq();
                desc = "rest frequency";
            } else if ("SourceVel".startsWith(a)) {
                set = testSourceVel();
                desc = "source velocity";
            } else if ("StdOfRest".startsWith(a)) {
                set = testStdOfRest();
                desc = "spectral standard of rest";
            } else if ("Epoch".startsWith(a)) {
                set = testEpoch();
                desc = "epoch of observation";
            } else {
                throw Ast.err(
                        AstException.AST__INTER,
                        "VerifyAttrs(SpecFrame): Unknown attribute name \"" + a
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

    /** Adds to {@code specmap} the conversion from System {@code system} to frequency. */
    private void addToFrequency(SpecMap specmap, int system, double rf, String vmess) {
        if (system == AST__ENERGY) {
            specmap.specAdd("ENTOFR", null);
        } else if (system == AST__WAVENUM) {
            specmap.specAdd("WNTOFR", null);
        } else if (system == AST__WAVELEN) {
            specmap.specAdd("WVTOFR", null);
        } else if (system == AST__AIRWAVE) {
            specmap.specAdd("AWTOFR", null);
        } else {
            if (system == AST__VRADIO) {
                specmap.specAdd("VRTOVL", null);
            } else if (system == AST__VOPTICAL) {
                specmap.specAdd("VOTOVL", null);
            } else if (system == AST__REDSHIFT) {
                specmap.specAdd("ZOTOVL", null);
            } else if (system == AST__BETA) {
                specmap.specAdd("BTTOVL", null);
            } else if (system != AST__VREL) {
                throw Ast.err(AstException.AST__INTER, "MakeSpecMapping(SpecFrame): unexpected System " + system + ".");
            }
            verifyAttrs(vmess, "RestFreq", "astMatch");
            specmap.specAdd("VLTOFR", new double[] {rf});
        }
    }

    /** Adds to {@code specmap} the conversion from frequency to System {@code system}. */
    private void addFromFrequency(SpecMap specmap, int system, double rf, String vmess) {
        if (system == AST__ENERGY) {
            specmap.specAdd("FRTOEN", null);
        } else if (system == AST__WAVENUM) {
            specmap.specAdd("FRTOWN", null);
        } else if (system == AST__WAVELEN) {
            specmap.specAdd("FRTOWV", null);
        } else if (system == AST__AIRWAVE) {
            specmap.specAdd("FRTOAW", null);
        } else {
            verifyAttrs(vmess, "RestFreq", "astMatch");
            specmap.specAdd("FRTOVL", new double[] {rf});
            if (system == AST__VRADIO) {
                specmap.specAdd("VLTOVR", null);
            } else if (system == AST__VOPTICAL) {
                specmap.specAdd("VLTOVO", null);
            } else if (system == AST__REDSHIFT) {
                specmap.specAdd("VLTOZO", null);
            } else if (system == AST__BETA) {
                specmap.specAdd("VLTOBT", null);
            } else if (system != AST__VREL) {
                throw Ast.err(AstException.AST__INTER, "MakeSpecMapping(SpecFrame): unexpected System " + system + ".");
            }
        }
    }

    /**
     * A copy of {@code alignFrm} with the properties that define the rest frames of {@code frm}:
     * its Epoch, RefRA, RefDec, SourceVRF, SourceVel and observer's position.
     */
    private static SpecFrame alignCopy(SpecFrame alignFrm, SpecFrame frm) {
        SpecFrame a = (SpecFrame) alignFrm.copy();
        a.setEpochMjd(frm.getEpochMjd());
        a.setRefRaValue(frm.refRaValue());
        a.setRefDecValue(frm.refDecValue());
        a.setSourceVrfValue(frm.sourceVrfValue());
        a.setSourceVelValue(frm.sourceVelValue());
        a.setObsLatValue(frm.getObsLatValue());
        a.setObsLonValue(frm.getObsLonValue());
        a.setObsAlt(frm.getObsAlt());
        return a;
    }

    /**
     * The Mapping from SpecFrame {@code target} to SpecFrame {@code result}, or null if there is
     * none (C's {@code MakeSpecMapping}). The values go from the target's System to frequency, from
     * the target's standard of rest to the alignment standard of rest, to the alignment System and
     * back (only for a relative alignment System and different rest frequencies), from the
     * alignment standard of rest to the result's, and from frequency to the result's System, using
     * the target's properties on the way to the alignment Frame and the result's after it. The
     * alignment System (frequency for an absolute System, apparent radial velocity for a relative
     * one) and standard of rest are the System and StdOfRest of {@code alignFrm}. Restoring a
     * FrameSet's integrity ({@code AST__INTFLAG} set on the target), the alignment rest frames are
     * defined by {@code alignFrm}'s own properties; otherwise by those of the target and of the
     * result, so that, say, two source rest frames align however their sources differ. The
     * Mapping converts between the two Frames' units, and adds the target's SpecOrigin before and
     * subtracts the result's after, or, if both Frames have AlignSpecOffset set, the target's
     * origin transformed (the result's own when that is bad).
     *
     * @param report whether a Unit of another kind than the System's is an error rather than no
     *     Mapping
     * @throws AstException with status {@code AST__NOVAL} if UseDefs is false and an attribute the
     *     conversion needs is not set, or {@code AST__BADUN} for units that cannot be converted when
     *     {@code report} is true
     */
    private static Mapping makeSpecMapping(SpecFrame target, SpecFrame result, SpecFrame alignFrm, boolean report) {
        boolean match = true;
        SpecMap specmap = new SpecMap(1, 0);
        double targetRf = target.restFreqValue();
        double resultRf = result.restFreqValue();
        int targetSystem = target.systemValue();
        int resultSystem = result.systemValue();
        int alignSystem = alignFrm.systemValue();
        String vmess = "convert between spectral systems";
        result.verifyAttrs(vmess, "StdOfRest", "astMatch");
        target.verifyAttrs(vmess, "StdOfRest", "astMatch");

        SpecFrame alignTarget;
        SpecFrame alignResult;
        if ((target.getFrameFlags() & AST__INTFLAG) != 0) {
            alignTarget = alignFrm;
            alignResult = alignFrm;
        } else {
            alignTarget = alignCopy(alignFrm, target);
            alignResult = alignCopy(alignFrm, result);
        }
        alignSystem = absSystem(alignSystem) ? AST__FREQ : AST__VREL;

        boolean step2 = true;
        boolean step3 = true;
        boolean step4 = true;
        boolean step5 = true;
        boolean step6 = true;
        boolean step7 = true;
        if (targetSystem == AST__FREQ) {
            step2 = false;
        }
        if (equalSor(target, alignTarget)) {
            step3 = false;
        }
        if (equalSor(result, alignResult)) {
            step6 = false;
        }
        if (resultSystem == AST__FREQ) {
            step7 = false;
        }
        // C compares the rest frequencies exactly.
        boolean sameRf = resultRf == targetRf;
        if (alignSystem == AST__FREQ || sameRf) {
            step4 = false;
            step5 = false;
        }
        if (!step4 && !step5 && equalSor(target, result)) {
            step3 = false;
            step6 = false;
        }
        if (!step3
                && !step4
                && !step5
                && !step6
                && equalSor(target, result)
                && targetSystem == resultSystem
                && !absSystem(targetSystem)
                && sameRf) {
            step2 = false;
            step7 = false;
        }

        if (step2 && targetSystem != AST__FREQ) {
            target.addToFrequency(specmap, targetSystem, targetRf, vmess);
        }
        if (step3) {
            sorConvert(target, alignTarget, specmap);
        }
        if (step4 && alignSystem == AST__VREL) {
            target.verifyAttrs(vmess, "RestFreq", "astMatch");
            specmap.specAdd("FRTOVL", new double[] {targetRf});
        }
        if (step5 && alignSystem == AST__VREL) {
            result.verifyAttrs(vmess, "RestFreq", "astMatch");
            specmap.specAdd("VLTOFR", new double[] {resultRf});
        }
        if (step6) {
            sorConvert(alignResult, result, specmap);
        }
        if (step7 && resultSystem != AST__FREQ) {
            result.addFromFrequency(specmap, resultSystem, resultRf, vmess);
        }

        String utarg = target.getUnit(1);
        Mapping umap1 = Units.unitMapper(utarg, specMapUnit(targetSystem, "MakeSpecMap", "SpecFrame"));
        String ures = result.getUnit(1);
        Mapping umap2 = Units.unitMapper(specMapUnit(resultSystem, "MakeSpecMap", "SpecFrame"), ures);
        Mapping map2;
        if (umap1 != null && umap2 != null) {
            map2 = new CmpMap(new CmpMap(umap1, specmap, true), umap2, true);
        } else {
            map2 = specmap.simplify();
            if (!(map2 instanceof UnitMap) || !ures.equals(utarg)) {
                match = false;
                if (report) {
                    String uerr;
                    int serr;
                    if (umap1 == null) {
                        uerr = utarg;
                        serr = target.systemValue();
                    } else {
                        uerr = ures;
                        serr = result.systemValue();
                    }
                    throw Ast.err(
                            AstException.AST__BADUN,
                            "astMatch(SpecFrame): Inappropriate units (" + uerr + ") specified for a "
                                    + systemLabel(serr) + " axis.");
                }
            }
        }

        double targetOrigin = target.specOriginCur();
        Mapping map1;
        if (targetOrigin != 0.0) {
            map1 = new CmpMap(new ShiftMap(new double[] {targetOrigin}), map2, true);
        } else {
            map1 = map2;
        }

        double resultOrigin;
        if (target.alignSpecOffsetValue() && result.alignSpecOffsetValue()) {
            resultOrigin = transformValue(map1, 0.0);
            if (resultOrigin == AST__BAD) {
                resultOrigin = result.specOriginCur();
            }
        } else {
            resultOrigin = result.specOriginCur();
        }
        if (resultOrigin != 0.0) {
            map2 = new CmpMap(map1, new ShiftMap(new double[] {-resultOrigin}), true);
        } else {
            map2 = map1;
        }
        Mapping map = map2.simplify();
        return match ? map : null;
    }

    /**
     * Matches this SpecFrame, a template, against a target (C's {@code Match}, specframe.c): the
     * target must pass the Frame tests and have an axis of a SpecFrame, the first of which is
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
            if (target.primaryFrame(iaxis + 1, pax) instanceof SpecFrame) {
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
     * Selects axes from this SpecFrame (C's {@code SubFrame}, specframe.c). Selecting the one axis
     * gives a SpecFrame, a copy with the template's attributes overlaid, and the Mapping converts
     * between the two SpecFrames, aligned in the AlignSystem and AlignStdOfRest of a copy of the
     * template (of this SpecFrame if the template is not a SpecFrame or there is none); with a
     * SpecFrame template, units that cannot be converted are an error rather than no match. Any
     * other selection gives a plain Frame, made from a copy whose Domain, Title, Label, Symbol and
     * Unit are set to their values and whose System and AlignSystem are cleared, which clears the
     * Title, Label and Symbol again if the System changes (a C quirk, followed here).
     *
     * @throws AstException with status {@code AST__NOVAL} if UseDefs is false and the StdOfRest,
     *     or an attribute the conversion needs, is not set, or {@code AST__BADUN} for units that
     *     cannot be converted
     */
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        map[0] = null;
        result[0] = null;
        boolean match;
        if (resultNaxes == 1 && targetAxes[0] == 0) {
            SpecFrame res = (SpecFrame) copy();
            result[0] = res;
            boolean report = false;
            SpecFrame alignFrm;
            if (template != null) {
                template.overlay(templateAxes, res);
                if (template instanceof SpecFrame) {
                    alignFrm = (SpecFrame) template.copy();
                    report = true;
                } else {
                    alignFrm = (SpecFrame) copy();
                }
            } else {
                verifyAttrs("convert between different spectral systems", "StdOfRest", "astMatch");
                alignFrm = (SpecFrame) copy();
            }
            alignFrm.setSystemValue(alignFrm.alignSystemValue());
            alignFrm.setStdOfRestValue(alignFrm.alignStdOfRestValue());
            Mapping m = makeSpecMapping(this, res, alignFrm, report);
            match = m != null;
            map[0] = m;
        } else {
            SpecFrame temp = (SpecFrame) copy();
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

    /** The Frame class's {@code SubFrame}, for a copy of this SpecFrame. */
    private boolean frameSubFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        return super.subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    /**
     * Overlays the attributes set on this SpecFrame, the template, onto another Frame (C's {@code
     * Overlay}, specframe.c). Onto a SpecFrame, a change of System set on the template first clears
     * the result's Title, Label and Symbol, and its Unit if the two Systems' default units differ;
     * then the Frame attributes are overlaid, and the SpecFrame attributes set on the template,
     * SourceVRF before SourceVel. Onto another class of Frame a template with a System set gives
     * the Frame attributes of a copy whose System and AlignSystem are cleared, with what that does
     * to a SpecFrame: a change of System clears the copy's Title, Label and Symbol, and its Unit
     * unless one was used with the default System, so those are not overlaid (a C quirk, followed
     * here).
     */
    @Override
    void overlay(int[] templateAxes, Frame result) {
        String method = "astOverlay";
        int oldSystem = result.systemValue();
        int newSystem = systemValue();
        SpecFrame templt = this;
        boolean specframe = result instanceof SpecFrame;
        if (specframe) {
            if (newSystem != oldSystem && testSystem()) {
                result.clearTitle();
                if (!defUnit(newSystem, method, astClass()).equals(defUnit(oldSystem, method, result.astClass()))) {
                    result.clearUnit(1);
                }
                result.clearLabel(1);
                result.clearSymbol(1);
            }
        } else if (testSystem()) {
            templt = (SpecFrame) copy();
            templt.clearSystem();
            templt.clearAlignSystem();
        }
        templt.frameOverlay(templateAxes, result);
        if (specframe) {
            SpecFrame res = (SpecFrame) result;
            if (testAlignStdOfRest()) {
                res.setAlignStdOfRestValue(alignStdOfRestValue());
            }
            if (testAlignSpecOffset()) {
                res.setAlignSpecOffsetValue(alignSpecOffsetValue());
            }
            if (testRefDec()) {
                res.setRefDecValue(refDecValue());
            }
            if (testRefRA()) {
                res.setRefRaValue(refRaValue());
            }
            if (testRestFreq()) {
                res.setRestFreqValue(restFreqValue());
            }
            if (testSourceSys()) {
                res.setSourceSysValue(sourceSysValue());
            }
            if (testSourceVRF()) {
                res.setSourceVrfValue(sourceVrfValue());
            }
            if (testSourceVel()) {
                res.setSourceVelValue(sourceVelValue());
            }
            if (testStdOfRest()) {
                res.setStdOfRestValue(stdOfRestValue());
            }
            if (testSpecOrigin()) {
                res.setSpecOriginValue(specOriginValue());
            }
        }
    }

    /** The Frame class's {@code Overlay}, for this SpecFrame or a copy of it. */
    private void frameOverlay(int[] templateAxes, Frame result) {
        super.overlay(templateAxes, result);
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

    /** The name of a standard of rest code, or the error C's {@code GetAttrib} reports for a corrupt one. */
    private String checkedSorString(int sor, String attribute) {
        String s = stdOfRestString(sor);
        if (s == null) {
            throw Ast.err(
                    AstException.AST__SCSIN,
                    "astGetAttrib(" + astClass() + "): Corrupt " + astClass() + " contains invalid " + attribute
                            + " identification code (" + sor + ").");
        }
        return s;
    }

    /** C's {@code astChrLen}: the length of a string without trailing white space. */
    private static int chrLen(String s) {
        int n = s.length();
        while (n > 0 && CScan.isCSpace(s.charAt(n - 1))) {
            n--;
        }
        return n;
    }

    @Override
    String getAttrib(String attrib) {
        if (isOneAxisName(attrib)) {
            return super.getAttrib(attrib + "(1)");
        } else if ("alignstdofrest".equals(attrib)) {
            return checkedSorString(alignStdOfRestValue(), "AlignStdOfRest");
        } else if ("alignspecoffset".equals(attrib)) {
            return alignSpecOffsetValue() ? "1" : "0";
        } else if ("geolat".equals(attrib)) {
            return getAttrib("obslat");
        } else if ("geolon".equals(attrib)) {
            return getAttrib("obslon");
        } else if ("refdec".equals(attrib)) {
            return refSkyFrame().format(2, refDecValue());
        } else if ("refra".equals(attrib)) {
            return refSkyFrame().format(1, refRaValue());
        } else if ("restfreq".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", restFreqValue() * 1.0E-9);
        } else if ("sourcevel".equals(attrib)) {
            double dval = sourceVelValue();
            if (sourceVelInKms()) {
                dval *= 1.0E-3;
            }
            return CFormat.format("%." + DBL_DIG + "g", dval);
        } else if ("specorigin".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", specOriginCur());
        } else if ("sourcevrf".equals(attrib)) {
            return checkedSorString(sourceVrfValue(), "SourceVRF");
        } else if ("sourcesys".equals(attrib)) {
            int sys = sourceSysValue();
            String s = systemString(sys);
            if (s == null) {
                throw Ast.err(
                        AstException.AST__SCSIN,
                        "astGetAttrib(" + astClass() + "): Corrupt " + astClass()
                                + " contains invalid SourceSys identification code (" + sys + ").");
            }
            return s;
        } else if ("stdofrest".equals(attrib)) {
            return checkedSorString(stdOfRestValue(), "StdOfRest");
        }
        return super.getAttrib(attrib);
    }

    /** The error C's {@code SetAttrib} reports for an unknown standard of rest. */
    private AstException badSor(String value) {
        return Ast.err(
                AstException.AST__ATTIN,
                "astSetAttrib(" + astClass() + "): Invalid standard of rest description \"" + value + "\".");
    }

    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        Integer ival = CScan.scanInt(value);
        String w = oneWord(value);
        if (isOneAxisName(attrib)) {
            super.setAttrib(attrib + "(1)", value);
        } else if ("alignstdofrest".equals(attrib) && w != null) {
            int sor = stdOfRestCode(value);
            if (sor == AST__BADSOR) {
                throw badSor(value);
            }
            setAlignStdOfRestValue(sor);
        } else if ("geolat".equals(attrib) && CScan.hasWord(value)) {
            setAttrib("obslat", value);
        } else if ("geolon".equals(attrib) && CScan.hasWord(value)) {
            setAttrib("obslon", value);
        } else if ("refdec".equals(attrib) && CScan.hasWord(value)) {
            double[] dv = {AST__BAD};
            int nc = refSkyFrame().unformat(2, value, dv);
            if (nc != chrLen(value)) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Invalid reference declination \"" + value + "\".");
            }
            setRefDecValue(dv[0]);
        } else if ("refra".equals(attrib) && CScan.hasWord(value)) {
            double[] dv = {AST__BAD};
            int nc = refSkyFrame().unformat(1, value, dv);
            if (nc != chrLen(value)) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Invalid reference right ascension \"" + value + "\".");
            }
            setRefRaValue(dv[0]);
        } else if ("alignspecoffset".equals(attrib) && ival != null) {
            setAlignSpecOffsetValue(ival != 0);
        } else if ("restfreq".equals(attrib) && dval != null) {
            setRestFreqValue(dval * 1.0E9);
        } else if ("restfreq".equals(attrib) && CScan.numberThenWord(value) != null) {
            double[] nw = CScan.numberThenWord(value);
            setRestFreqValue(restFreqFromUnits(nw[0], value.substring((int) nw[1])));
        } else if ("sourcevel".equals(attrib) && dval != null) {
            double v = dval;
            if (sourceVelInKms()) {
                v *= 1.0E3;
            }
            setSourceVelValue(v);
        } else if ("sourcevrf".equals(attrib) && w != null) {
            int sor = stdOfRestCode(value);
            if (sor == AST__BADSOR) {
                throw badSor(value);
            }
            setSourceVrfValue(sor);
        } else if ("sourcesys".equals(attrib) && w != null) {
            setSourceSysValue(systemCode(value));
        } else if ("stdofrest".equals(attrib) && w != null) {
            int sor = stdOfRestCode(value);
            if (sor == AST__BADSOR) {
                throw badSor(value);
            }
            setStdOfRestValue(sor);
        } else if ("specorigin".equals(attrib) && dval != null) {
            setSpecOriginValue(toUnits(getUnit(1), dval, "astSetSpecOrigin"));
        } else if ("specorigin".equals(attrib) && CScan.numberThenWord(value) != null) {
            double[] nw = CScan.numberThenWord(value);
            setSpecOriginValue(toUnits(value.substring((int) nw[1]), nw[0], "astSetSpecOrigin"));
        } else {
            super.setAttrib(attrib, value);
        }
    }

    /**
     * A rest frequency in Hz from a value in units {@code unit} (C's {@code SetAttrib}): a
     * frequency converted to Hz, a vacuum wavelength converted to metres and then to a frequency,
     * or an energy converted to Joules and then to a frequency.
     *
     * @throws AstException with status {@code AST__ATTIN} if the units are none of these, or the
     *     wavelength is zero or bad, or the energy bad
     */
    private double restFreqFromUnits(double dval, String unit) {
        Mapping umap = Units.unitMapper(unit, "Hz");
        if (umap != null) {
            return transformValue(umap, dval);
        }
        umap = Units.unitMapper(unit, "m");
        if (umap != null) {
            double dtemp = transformValue(umap, dval);
            if (dtemp != AST__BAD && dtemp != 0.0) {
                return SpecMap.AST__C / dtemp;
            }
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetAttrib(" + astClass() + "): Invalid rest wavelength \"" + CFormat.format("%g", dval) + " "
                            + unit + "\" supplied.");
        }
        umap = Units.unitMapper(unit, "J");
        if (umap != null) {
            double dtemp = transformValue(umap, dval);
            if (dtemp != AST__BAD) {
                return dtemp * (1.0 / SpecMap.AST__H);
            }
            throw Ast.err(
                    AstException.AST__ATTIN,
                    "astSetAttrib(" + astClass() + "): Invalid rest energy \"" + CFormat.format("%g", dval) + " " + unit
                            + "\" supplied.");
        }
        throw Ast.err(
                AstException.AST__ATTIN,
                "astSetAttrib(" + astClass() + "): Rest frequency given in an unsupported system of units \""
                        + CFormat.format("%g", dval) + " " + unit + "\".");
    }

    @Override
    boolean testAttrib(String attrib) {
        if (isOneAxisName(attrib)) {
            return super.testAttrib(attrib + "(1)");
        } else if ("alignstdofrest".equals(attrib)) {
            return testAlignStdOfRest();
        } else if ("geolat".equals(attrib)) {
            return testAttrib("obslat");
        } else if ("geolon".equals(attrib)) {
            return testAttrib("obslon");
        } else if ("refdec".equals(attrib)) {
            return testRefDec();
        } else if ("refra".equals(attrib)) {
            return testRefRA();
        } else if ("restfreq".equals(attrib)) {
            return testRestFreq();
        } else if ("sourcevel".equals(attrib)) {
            return testSourceVel();
        } else if ("sourcevrf".equals(attrib)) {
            return testSourceVRF();
        } else if ("sourcesys".equals(attrib)) {
            return testSourceSys();
        } else if ("stdofrest".equals(attrib)) {
            return testStdOfRest();
        } else if ("specorigin".equals(attrib)) {
            return testSpecOrigin();
        } else if ("alignspecoffset".equals(attrib)) {
            return testAlignSpecOffset();
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if (isOneAxisName(attrib)) {
            super.clearAttrib(attrib + "(1)");
        } else if ("alignstdofrest".equals(attrib)) {
            clearAlignStdOfRest();
        } else if ("geolat".equals(attrib)) {
            clearAttrib("obslat");
        } else if ("geolon".equals(attrib)) {
            clearAttrib("obslon");
        } else if ("refdec".equals(attrib)) {
            clearRefDec();
        } else if ("refra".equals(attrib)) {
            clearRefRA();
        } else if ("restfreq".equals(attrib)) {
            clearRestFreq();
        } else if ("sourcevel".equals(attrib)) {
            clearSourceVel();
        } else if ("specorigin".equals(attrib)) {
            clearSpecOrigin();
        } else if ("alignspecoffset".equals(attrib)) {
            clearAlignSpecOffset();
        } else if ("sourcevrf".equals(attrib)) {
            clearSourceVRF();
        } else if ("sourcesys".equals(attrib)) {
            clearSourceSys();
        } else if ("stdofrest".equals(attrib)) {
            clearStdOfRest();
        } else {
            super.clearAttrib(attrib);
        }
    }
}
