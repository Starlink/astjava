package uk.ac.starlink.ast;

/**
 * A description of flux values. A FluxFrame is a one-dimensional Frame describing the signal
 * level in an observation, in the system its System attribute names: flux density per unit
 * frequency ({@code FLXDN}, the default, in W/m^2/Hz), flux density per unit wavelength ({@code
 * FLXDNW}, in W/m^2/Angstrom), surface brightness per unit frequency ({@code SFCBR}, in
 * W/m^2/Hz/arcmin**2) or surface brightness per unit wavelength ({@code SFCBRW}, in
 * W/m^2/Angstrom/arcmin**2; {@code SRCBR} is accepted for it too). The Unit attribute may give
 * other units of the same kind; with a Unit set and no System, the System is the first of these
 * whose default units can be converted to the Unit. The Unit last used with each System comes back
 * when the System does.
 *
 * <p>All flux values are taken to be measured at one spectral position, the SpecVal attribute,
 * given in the spectral system of the SpecFrame supplied when the FluxFrame is made (or, with no
 * SpecFrame, as a frequency in Hz for a per-frequency System and a wavelength in Angstrom for a
 * per-wavelength one). A FluxFrame is therefore more suited to images than to spectra. Two
 * FluxFrames convert between flux densities per unit frequency and per unit wavelength only when
 * their SpecVals give the same spectral position, and never between a flux density and a surface
 * brightness. A FluxFrame always uses the Unit of its axis when it is aligned with another (its
 * ActiveUnit flag is always true).
 *
 * <p>Ported from C {@code fluxframe.c}, with Rust {@code frames/fluxframe.rs}.
 */
public class FluxFrame extends Frame {

    /** The System of flux density per unit frequency. */
    static final int AST__FLUXDEN = 1;

    /** The System of flux density per unit wavelength. */
    static final int AST__FLUXDENW = 2;

    /** The System of surface brightness per unit frequency. */
    static final int AST__SBRIGHT = 3;

    /** The System of surface brightness per unit wavelength. */
    static final int AST__SBRIGHTW = 4;

    private static final int FIRST_SYSTEM = AST__FLUXDEN;
    private static final int LAST_SYSTEM = AST__SBRIGHTW;

    /** The digits C writes a full-precision double with ({@code AST__DBL_DIG}). */
    private static final int DBL_DIG = 17;

    /** The SpecVal, or bad if unset. */
    private double specValue = AST__BAD;

    /** The SpecVal used when none is set, given when the FluxFrame is made, or bad (C's {@code defspecval}). */
    private double defSpecVal;

    /** The SpecFrame giving the spectral system of the SpecVal, or null for the default one. */
    private SpecFrame specFrame;

    /** The Unit last set with each System, indexed by System code, or null (C's {@code usedunits}). */
    private String[] usedUnits = new String[LAST_SYSTEM + 1];

    /**
     * Creates a FluxFrame for flux values measured at a spectral position.
     *
     * @param specval the spectral position at which the flux values are measured, in the spectral
     *     system and units of {@code specfrm}: the default SpecVal. It may be {@link #AST__BAD} if
     *     the position is not known, but then no conversion to another FluxFrame may be found.
     * @param specfrm the spectral system of {@code specval}, of which a deep copy is kept; or null
     *     (allowed if {@code specval} is bad), for a frequency in Hz with a per-frequency System and
     *     a wavelength in Angstrom with a per-wavelength one
     */
    // C's astFluxFrame checks that the Unit suits the System once its options string is applied;
    // these constructors take no options, and the default Unit and System always agree.
    public FluxFrame(double specval, SpecFrame specfrm) {
        super(1);
        defSpecVal = specval;
        specFrame = specfrm == null ? null : (SpecFrame) specfrm.copy();
    }

    /** Creates a FluxFrame with every attribute at its default and no spectral position. */
    public FluxFrame() {
        this(AST__BAD, null);
    }

    /**
     * Loads a FluxFrame from its description, as C's {@code astLoadFluxFrame} does: DfSpc, the
     * SpecFrame SpcFr, SpcVl and the Unit used with each System ({@code U<System>}), each unset if
     * absent.
     *
     * @param node the description
     * @throws AstException with status {@code AST__LDERR} if SpcFr is not a SpecFrame
     */
    FluxFrame(NativeNode node) {
        super(node);
        NativeNode part = node.section("FluxFrame");
        defSpecVal = part.readDouble("DfSpc", AST__BAD);
        NativeNode desc = part.readObject("SpcFr");
        if (desc != null) {
            AstObject obj = ObjectRegistry.loadNested(desc, "SpcFr");
            // C stores whatever Object it reads; a FluxFrame here holds only a SpecFrame.
            if (!(obj instanceof SpecFrame)) {
                throw Ast.err(
                        AstException.AST__LDERR,
                        "astLoadFluxFrame(FluxFrame): The object SpcFr is a " + obj.astClass() + ", not a SpecFrame.");
            }
            specFrame = (SpecFrame) obj;
        }
        specValue = part.readDouble("SpcVl", AST__BAD);
        for (int sys = FIRST_SYSTEM; sys <= LAST_SYSTEM; sys++) {
            String unit = part.getStringIfSet(usedUnitKey(sys));
            if (unit != null) {
                usedUnits[sys] = unit;
            }
        }
    }

    @Override
    String dumpComment() {
        return "Description of flux values";
    }

    /** The key of the dump item holding the Unit used with a System: {@code U} and its name, as {@code UFlxdn}. */
    private static String usedUnitKey(int sys) {
        String name = fluxSystemString(sys);
        StringBuilder sb = new StringBuilder("U").append(name);
        for (int j = 2; j < sb.length(); j++) {
            sb.setCharAt(j, CScan.toLower(sb.charAt(j)));
        }
        return sb.toString();
    }

    /** Writes the FluxFrame class's part of a description, as C's {@code Dump} (fluxframe.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("Frame", "Coordinate system description");
        boolean set = testSpecVal();
        double dval = specValValue();
        if (dval != AST__BAD) {
            node.writeDouble("SpcVl", set, false, dval, "Spectral position");
        }
        if (specFrame != null) {
            node.writeObject("SpcFr", true, false, NativeNode.of(specFrame), "SpcVl coord system");
        }
        if (defSpecVal != AST__BAD) {
            node.writeDouble("DfSpc", true, false, defSpecVal, "Default spectral position");
        }
        for (int i = 0; i < usedUnits.length; i++) {
            if (usedUnits[i] != null) {
                node.writeString(usedUnitKey(i), true, false, usedUnits[i], "Preferred units for " + systemLabel(i));
            }
        }
    }

    /** Copies the record of the Unit used with each System and the SpecFrame, as C's {@code Copy} (fluxframe.c) does. */
    @Override
    void deepCopy() {
        super.deepCopy();
        usedUnits = usedUnits.clone();
        if (specFrame != null) {
            specFrame = (SpecFrame) specFrame.copy();
        }
    }

    // ------------------------------------------------------------------
    // Systems
    // ------------------------------------------------------------------

    /** The System code a name gives, ignoring case and trailing white space (C's {@code SystemCode}). */
    @Override
    int systemCode(String name) {
        if (chrMatch("FLXDN", name)) {
            return AST__FLUXDEN;
        } else if (chrMatch("FLXDNW", name)) {
            return AST__FLUXDENW;
        } else if (chrMatch("SFCBR", name)) {
            return AST__SBRIGHT;
        } else if (chrMatch("SFCBRW", name) || chrMatch("SRCBR", name)) {
            return AST__SBRIGHTW;
        }
        return AST__BADSYSTEM;
    }

    /** The name of a System code, or null (C's {@code SystemString}). */
    @Override
    String systemString(int code) {
        return fluxSystemString(code);
    }

    /** The name of a System code, or null (C's {@code FluxSystemString}). */
    private static String fluxSystemString(int code) {
        switch (code) {
            case AST__FLUXDEN:
                return "FLXDN";
            case AST__FLUXDENW:
                return "FLXDNW";
            case AST__SBRIGHT:
                return "SFCBR";
            case AST__SBRIGHTW:
                return "SFCBRW";
            default:
                return null;
        }
    }

    /** The description of a System code, in lower case, or null (C's {@code SystemLabel}). */
    static String systemLabel(int system) {
        switch (system) {
            case AST__FLUXDEN:
                return "flux density";
            case AST__FLUXDENW:
                return "flux wavelength density";
            case AST__SBRIGHT:
                return "surface brightness";
            case AST__SBRIGHTW:
                return "surface brightness (per wavelength)";
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
     * @throws AstException with status {@code AST__AXIIN} if it is not a flux System
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
     * The default units of a System (C's {@code DefUnit}).
     *
     * @throws AstException with status {@code AST__SCSIN} if the System is not a flux System
     */
    static String defUnit(int system, String method, String cls) {
        switch (system) {
            case AST__FLUXDEN:
                return "W/m^2/Hz";
            case AST__FLUXDENW:
                return "W/m^2/Angstrom";
            case AST__SBRIGHT:
                return "W/m^2/Hz/arcmin**2";
            case AST__SBRIGHTW:
                return "W/m^2/Angstrom/arcmin**2";
            default:
                throw Ast.err(
                        AstException.AST__SCSIN,
                        method + "(" + cls + "): Corrupt " + cls + " contains illegal System identification code ("
                                + system + ").");
        }
    }

    /**
     * The spectral System a flux System is a density in (C's {@code DensitySystem}): frequency for
     * a per-frequency System, wavelength for a per-wavelength one.
     *
     * @throws AstException with status {@code AST__INTER} if the System is not a flux System
     */
    private static int densitySystem(int sys) {
        switch (sys) {
            case AST__FLUXDEN:
            case AST__SBRIGHT:
                return SpecFrame.AST__FREQ;
            case AST__FLUXDENW:
            case AST__SBRIGHTW:
                return SpecFrame.AST__WAVELEN;
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "DensitySystem(FluxFrame): The DensitySystem method does not yet support FluxFrame system "
                                + sys + " (AST internal programming error).");
        }
    }

    /**
     * The default units of the spectral System a flux System is a density in (C's {@code
     * DensityUnit}): {@code Hz} or {@code Angstrom}.
     *
     * @throws AstException with status {@code AST__INTER} if the System is not a flux System
     */
    private static String densityUnit(int sys) {
        switch (sys) {
            case AST__FLUXDEN:
            case AST__SBRIGHT:
                return "Hz";
            case AST__FLUXDENW:
            case AST__SBRIGHTW:
                return "Angstrom";
            default:
                throw Ast.err(
                        AstException.AST__INTER,
                        "DensityUnit(FluxFrame): The DensityUnit method does not yet support FluxFrame system " + sys
                                + " (AST internal programming error).");
        }
    }

    /** The spectral System this FluxFrame's values are a density in (C's {@code astGetDensitySystem}). */
    int getDensitySystem() {
        return densitySystem(systemValue());
    }

    /** The default units of the spectral System this FluxFrame's values are a density in (C's {@code astGetDensityUnit}). */
    String getDensityUnit() {
        return densityUnit(systemValue());
    }

    /**
     * Whether values in units {@code from} can be converted to units {@code to}, as C finds by
     * asking {@code astUnitMapper} for the Mapping.
     */
    private static boolean unitsConvert(String from, String to) {
        return Units.unitMapper(from, to) != null;
    }

    /**
     * The System (C's {@code GetSystem}): set; or, with a Unit set, the first System whose default
     * units can be converted from the Unit; or {@link #AST__FLUXDEN}.
     *
     * @throws AstException with status {@code AST__BADUN} if the System is not set and the Unit
     *     suits no flux System
     */
    @Override
    int systemValue() {
        boolean unitSet = testUnit(1);
        if (testSystem()) {
            return super.systemValue();
        } else if (unitSet) {
            String units = getUnit(1);
            for (int i = FIRST_SYSTEM; i <= LAST_SYSTEM; i++) {
                if (unitsConvert(units, defUnit(i, "astGetSystem", astClass()))) {
                    return i;
                }
            }
            throw Ast.err(
                    AstException.AST__BADUN,
                    "astGetSystem(" + astClass() + "): The current units (" + getUnit(1)
                            + ") cannot be used with any of the supported flux systems.");
        }
        return AST__FLUXDEN;
    }

    /** The AlignSystem: set, or {@link #AST__FLUXDEN} (C's {@code GetAlignSystem}). */
    @Override
    int alignSystemValue() {
        return testAlignSystem() ? super.alignSystemValue() : AST__FLUXDEN;
    }

    /**
     * Sets the System (C's {@code SetSystem}). If the System changes, the Unit last used with the
     * new System is restored, or the Unit cleared, and the Label, Symbol and Title, whose defaults
     * depend on the System, are cleared.
     */
    @Override
    void setSystemValue(int newsys) {
        int oldsys = systemValue();
        super.setSystemValue(newsys);
        if (oldsys != newsys) {
            restoreUnit(newsys);
            clearLabel(1);
            clearSymbol(1);
            clearTitle();
        }
    }

    /**
     * Clears the System (C's {@code ClearSystem}). If that changes the System, the Unit last used
     * with the new System is restored, or the Unit cleared, and the Label, Symbol and Title are
     * cleared.
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
        }
    }

    /**
     * Sets the Unit to the one last used with System {@code sys}, or clears it if there is none,
     * through the Frame class's methods, which do not change the record of the Units used.
     */
    private void restoreUnit(int sys) {
        if (sys < usedUnits.length && usedUnits[sys] != null) {
            super.setUnit(1, usedUnits[sys]);
        } else {
            super.clearUnit(1);
        }
    }

    // ------------------------------------------------------------------
    // SpecVal
    // ------------------------------------------------------------------

    /**
     * Gets the SpecVal attribute: the spectral position (frequency, wavelength, etc.) at which
     * the flux values are measured, in the spectral system of the SpecFrame the FluxFrame was made
     * with. It is used when finding the Mapping between two FluxFrames. The default is the value
     * the FluxFrame was made with.
     *
     * @return the SpecVal
     * @throws AstException with status {@code AST__ATGER} if it is bad, which it is if neither set
     *     nor given when the FluxFrame was made
     */
    public double getSpecVal() {
        return getD("SpecVal");
    }

    /**
     * Sets the SpecVal attribute; see {@link #getSpecVal}.
     *
     * @param specVal the SpecVal
     */
    public void setSpecVal(double specVal) {
        setD("SpecVal", specVal);
    }

    /** The SpecVal: set, or the default given when the FluxFrame was made, either of which may be bad. */
    double specValValue() {
        return specValue != AST__BAD ? specValue : defSpecVal;
    }

    void setSpecValValue(double value) {
        specValue = value;
    }

    boolean testSpecVal() {
        return specValue != AST__BAD;
    }

    void clearSpecVal() {
        specValue = AST__BAD;
    }

    /**
     * The SpecFrame describing the spectral system of the SpecVal (C's {@code GetSpecFrame}): the
     * one the FluxFrame holds, or a new one in the frequency (Hz) or wavelength (Angstrom) its
     * System is a density in.
     */
    private SpecFrame getSpecFrame() {
        if (specFrame != null) {
            return specFrame;
        }
        SpecFrame result = new SpecFrame();
        result.setSystemValue(getDensitySystem());
        result.setUnit(1, getDensityUnit());
        return result;
    }

    // ------------------------------------------------------------------
    // Frame attributes with FluxFrame defaults
    // ------------------------------------------------------------------

    /** The Domain: set, or {@code FLUX}. */
    @Override
    public String getDomain() {
        return testDomain() ? super.getDomain() : "FLUX";
    }

    /**
     * Gets the ActiveUnit flag, which is always true for a FluxFrame: a difference in the Unit of
     * two FluxFrames is always allowed for when one is converted to the other.
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
     * Sets the Unit of the axis (C's {@code SetUnit}). A Unit that can be converted from the
     * default units of the System is recorded as the Unit used with that System, which comes back
     * when the System does; any other Unit clears the System, which then follows from the Unit.
     *
     * @throws AstException with status {@code AST__BADUN} if the System is not set and the Unit
     *     suits no flux System (the Unit is set nonetheless)
     */
    @Override
    public void setUnit(int axis, String unit) {
        super.setUnit(axis, unit);
        validateAxis(axis, true, "astSetUnit");
        int system = systemValue();
        if (unitsConvert(defUnit(system, "astSetUnit", astClass()), unit)) {
            if (usedUnits[system] == null || !usedUnits[system].equals(unit)) {
                usedUnits[system] = unit;
            }
        } else {
            super.clearSystem();
        }
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
     * Gets the Label of the axis (C's {@code GetLabel}): set, or the System's description,
     * rewritten for a set Unit, as {@code log( Flux density )} for {@code log(Jy)}.
     */
    @Override
    public String getLabel(int axis) {
        validateAxis(axis, true, "astGetLabel");
        if (testLabel(axis)) {
            return super.getLabel(axis);
        }
        int system = systemValue();
        String result = capitalised(system);
        if (testUnit(axis)) {
            Units.LabelledMapping lm =
                    Units.unitMapperLabel(defUnit(system, "astGetLabel", astClass()), getUnit(axis), result);
            if (lm != null && lm.label != null) {
                result = lm.label;
            }
        }
        return result;
    }

    /**
     * Gets the Symbol of the axis (C's {@code GetSymbol}): set, or {@code S_nu}, {@code S_lambda},
     * {@code mu_nu} or {@code mu_lambda} for the System, rewritten for a set Unit.
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
            case AST__FLUXDEN:
                result = "S_nu";
                break;
            case AST__FLUXDENW:
                result = "S_lambda";
                break;
            case AST__SBRIGHT:
                result = "mu_nu";
                break;
            case AST__SBRIGHTW:
                result = "mu_lambda";
                break;
            default:
                throw Ast.err(
                        AstException.AST__SCSIN,
                        "astGetSymbol(" + astClass() + "): Corrupt " + astClass()
                                + " contains invalid System identification code (" + system + ").");
        }
        if (testUnit(axis)) {
            Units.LabelledMapping lm =
                    Units.unitMapperLabel(defUnit(system, "astGetSymbol", astClass()), getUnit(axis), result);
            if (lm != null && lm.label != null) {
                result = lm.label;
            }
        }
        return result;
    }

    /**
     * Gets the Title (C's {@code GetTitle}): set, or the System's description followed, if the
     * SpecVal is known, by {@code " at = "}, the SpecVal formatted by its SpecFrame, and that
     * SpecFrame's Unit.
     *
     * @return the Title
     */
    @Override
    public String getTitle() {
        if (testTitle()) {
            return super.getTitle();
        }
        StringBuilder sb = new StringBuilder(capitalised(systemValue()));
        double specval = specValValue();
        SpecFrame sf = getSpecFrame();
        if (specval != AST__BAD) {
            sb.append(" at = ").append(sf.format(1, specval)).append(' ').append(sf.getUnit(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Conversion
    // ------------------------------------------------------------------

    /**
     * The Mapping from flux values in this FluxFrame, the target, to those in {@code result} (C's
     * {@code MakeFluxMapping}): from the target's Unit to the default units of its System, a
     * ZoomMap by the rate of change of the result's density spectral system with the target's at
     * the SpecVal, and from the default units of the result's System to its Unit, simplified.
     * A surface brightness is converted only to a surface brightness, as a flux density per unit
     * area. Between a per-frequency and a per-wavelength System the two SpecVals must give the
     * same spectral position.
     *
     * @return the Mapping, or null if there is none
     * @throws AstException with status {@code AST__BADUN} if either System follows from a Unit
     *     that suits no flux System
     */
    Mapping makeFluxMapping(FluxFrame result) {
        int rsysIn = systemValue();
        int rsysOut = result.systemValue();
        Mapping map1 = Units.unitMapper(getUnit(1), defUnit(rsysIn, "MakeFluxMapping", "FluxFrame"));
        int sysIn;
        boolean sbIn;
        if (rsysIn == AST__SBRIGHT) {
            sysIn = AST__FLUXDEN;
            sbIn = true;
        } else if (rsysIn == AST__SBRIGHTW) {
            sysIn = AST__FLUXDENW;
            sbIn = true;
        } else {
            sysIn = rsysIn;
            sbIn = false;
        }
        int sysOut;
        boolean sbOut;
        if (rsysOut == AST__SBRIGHT) {
            sysOut = AST__FLUXDEN;
            sbOut = true;
        } else if (rsysOut == AST__SBRIGHTW) {
            sysOut = AST__FLUXDENW;
            sbOut = true;
        } else {
            sysOut = rsysOut;
            sbOut = false;
        }
        double zoom;
        if (sbIn != sbOut) {
            zoom = AST__BAD;
        } else if (sysIn == sysOut) {
            zoom = 1.0;
        } else {
            zoom = densityZoom(result, sysIn, sysOut);
        }
        Mapping map2 = zoom != AST__BAD ? new ZoomMap(1, Math.abs(zoom)) : null;
        Mapping map3 = Units.unitMapper(defUnit(rsysOut, "MakeFluxMapping", "FluxFrame"), result.getUnit(1));
        if (map1 == null || map2 == null || map3 == null) {
            return null;
        }
        Mapping map4 = new CmpMap(map1, map2, true);
        Mapping map5 = new CmpMap(map4, map3, true);
        return map5.simplify();
    }

    /**
     * The rate of change of the density spectral system of {@code result}'s flux System {@code
     * sysOut} with that of this FluxFrame's {@code sysIn}, at the SpecVal, or bad if the two
     * SpecVals are not the same spectral position or either is bad (part of C's {@code
     * MakeFluxMapping}).
     */
    private double densityZoom(FluxFrame result, int sysIn, int sysOut) {
        SpecFrame sfin1 = getSpecFrame();
        SpecFrame sfin2 = (SpecFrame) sfin1.copy();
        sfin2.setSystemValue(densitySystem(sysIn));
        sfin2.setUnit(1, densityUnit(sysIn));
        SpecFrame sfout1 = result.getSpecFrame();
        SpecFrame sfout2 = (SpecFrame) sfout1.copy();
        sfout2.setSystemValue(densitySystem(sysOut));
        sfout2.setUnit(1, densityUnit(sysOut));
        double zoom = AST__BAD;
        FrameSet fs = sfout2.convert(sfin2, "");
        if (fs != null) {
            Mapping smap =
                    fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
            fs = sfin1.convert(sfin2, "");
            Mapping smapIn = fs != null ? fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT) : null;
            fs = sfout1.convert(sfout2, "");
            Mapping smapOut = fs != null ? fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT) : null;
            if (smapIn == null || smapOut == null) {
                // C passes the missing Mapping to astTran1, which reports the null pointer.
                throw Ast.err(
                        AstException.AST__INTER,
                        "MakeFluxMapping(FluxFrame): No Mapping from the SpecFrame of a FluxFrame to its density"
                                + " system (internal programming error).");
            }
            double specval2 = smapIn.tran1(1, new double[] {specValValue()}, true)[0];
            double specvalIn = smap.tran1(1, new double[] {specval2}, false)[0];
            double specvalOut = smapOut.tran1(1, new double[] {result.specValValue()}, true)[0];
            if (MappingHelpers.astEquals(specvalIn, specvalOut, 1.0E8) && specvalIn != AST__BAD) {
                if (smap instanceof UnitMap) {
                    zoom = 1.0;
                } else if (smap instanceof ZoomMap) {
                    zoom = ((ZoomMap) smap).getZoom();
                } else {
                    zoom = smap.rate(new double[] {0.5 * (specvalIn + specvalOut)}, 1, 1);
                }
            }
        }
        return zoom;
    }

    // ------------------------------------------------------------------
    // Match, SubFrame and Overlay
    // ------------------------------------------------------------------

    /**
     * Matches this FluxFrame, a template, against a target (C's {@code Match}, fluxframe.c): the
     * target must pass the Frame tests and have an axis of a FluxFrame, the first of which is
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
            if (target.primaryFrame(iaxis + 1, pax) instanceof FluxFrame) {
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
     * Selects axes from this FluxFrame (C's {@code SubFrame}, fluxframe.c). Selecting the one
     * axis gives a FluxFrame, a copy with the template's attributes overlaid, and the Mapping
     * converts between the two FluxFrames ({@link #makeFluxMapping}). Any other selection gives a
     * plain Frame, made from a copy whose Domain, Title, Label, Symbol and Unit are set to their
     * values and whose System and AlignSystem are cleared.
     */
    // C works out an alignment System for MakeFluxMapping, which does not use it, so it is not
    // found here.
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        map[0] = null;
        result[0] = null;
        boolean match;
        if (resultNaxes == 1 && targetAxes[0] == 0) {
            FluxFrame res = (FluxFrame) copy();
            result[0] = res;
            if (template != null) {
                template.overlay(templateAxes, res);
            }
            Mapping m = makeFluxMapping(res);
            match = m != null;
            map[0] = m;
        } else {
            FluxFrame temp = (FluxFrame) copy();
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

    /** The Frame class's {@code SubFrame}, for a copy of this FluxFrame. */
    private boolean frameSubFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        return super.subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
    }

    /**
     * Overlays the attributes set on this FluxFrame, the template, onto another Frame (C's {@code
     * Overlay}, fluxframe.c). Onto a FluxFrame, a change of System set on the template first
     * clears the result's Title, Label and Symbol, and its Unit if the two Systems' default units
     * differ; the result takes the template's default SpecVal and a copy of its SpecFrame; then
     * the Frame attributes are overlaid, and the SpecVal if set on the template. Onto another class
     * of Frame, a System set on this template is cleared, with its AlignSystem, while the Frame
     * attributes are overlaid, and then the System and the AlignSystem's value are set again: the
     * template's AlignSystem is left set, and the change of System clears its Title, Label and
     * Symbol and may change its Unit (a C quirk, followed here).
     */
    @Override
    void overlay(int[] templateAxes, Frame result) {
        String method = "astOverlay";
        int oldSystem = result.systemValue();
        int newSystem = systemValue();
        int newAlignSystem = AST__BADSYSTEM;
        boolean resetSystem = false;
        boolean fluxframe = result instanceof FluxFrame;
        if (fluxframe) {
            if (newSystem != oldSystem && testSystem()) {
                result.clearTitle();
                if (!defUnit(newSystem, method, astClass()).equals(defUnit(oldSystem, method, result.astClass()))) {
                    result.clearUnit(1);
                }
                result.clearLabel(1);
                result.clearSymbol(1);
            }
            FluxFrame resff = (FluxFrame) result;
            resff.defSpecVal = defSpecVal;
            resff.specFrame = specFrame != null ? (SpecFrame) specFrame.copy() : null;
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
        if (fluxframe && testSpecVal()) {
            ((FluxFrame) result).setSpecValValue(specValValue());
        }
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    @Override
    String getAttrib(String attrib) {
        if ("specval".equals(attrib)) {
            double dval = specValValue();
            return dval != AST__BAD ? CFormat.format("%." + DBL_DIG + "g", dval) : "<bad>";
        }
        return super.getAttrib(attrib);
    }

    @Override
    void setAttrib(String attrib, String value) {
        Double dval = CScan.scanDouble(value);
        if ("specval".equals(attrib) && dval != null) {
            setSpecValValue(dval);
        } else {
            super.setAttrib(attrib, value);
        }
    }

    @Override
    boolean testAttrib(String attrib) {
        if ("specval".equals(attrib)) {
            return testSpecVal();
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("specval".equals(attrib)) {
            clearSpecVal();
        } else {
            super.clearAttrib(attrib);
        }
    }
}
