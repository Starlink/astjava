package uk.ac.starlink.ast;

/**
 * A dual sideband spectral coordinate system. A DSBSpecFrame is a SpecFrame describing positions
 * in a spectrum from a dual sideband instrument, in which each point holds contributions from two
 * frequencies, one in the lower sideband (LSB) and one in the upper sideband (USB), an equal
 * distance on either side of the local oscillator (LO) frequency.
 *
 * <p>The SideBand attribute says whether the values are positions in the upper sideband, in the
 * lower sideband, or topocentric offsets from the LO frequency. The LO frequency is the sum of the
 * DSBCentre, a spectral position of interest (given in the System, Unit and standard of rest of
 * the DSBSpecFrame and stored as a topocentric frequency), and the intermediate frequency, the
 * signed IF attribute. The sideband holding the DSBCentre is the observed sideband, and the other
 * is the image sideband; the read-only ImagFreq attribute is the image sideband frequency of the
 * rest frequency. Two DSBSpecFrames are aligned as SpecFrames unless both have AlignSideBand set,
 * when each is first mapped to its observed sideband. The Domain defaults to {@code DSBSPECTRUM}.
 *
 * <p>Ported from C {@code dsbspecframe.c}, with Rust {@code frames/dsbspecframe.rs}.
 */
public class DSBSpecFrame extends SpecFrame {

    /** The lower sideband (C's {@code LSB}). */
    static final int LSB = -1;

    /** Offsets from the local oscillator frequency (C's {@code LO}). */
    static final int LO = 0;

    /** The upper sideband (C's {@code USB}). */
    static final int USB = 1;

    /** The value of an unset SideBand (C's {@code BADSB}). */
    private static final int BADSB = -9999;

    /** The DSBCentre of an unset attribute, a topocentric frequency in Hz. */
    private static final double DEFAULT_DSBCENTRE = 3.0E10;

    /** The IF of an unset attribute, in Hz. */
    private static final double DEFAULT_IF = 4.0E9;

    /** The digits C writes a full-precision double with ({@code AST__DBL_DIG}). */
    private static final int DBL_DIG = 17;

    /** The DSBCentre as a topocentric frequency in Hz, or bad if unset. */
    private double dsbCentre = AST__BAD;

    /** The IF in Hz, or bad if unset. */
    private double ifr = AST__BAD;

    /** The SideBand, {@link #LSB}, {@link #LO} or {@link #USB}, or {@link #BADSB} if unset. */
    private int sideBand = BADSB;

    /** The AlignSideBand flag, 0 or 1, or -1 if unset. */
    private int alignSideBand = -1;

    /**
     * Creates a DSBSpecFrame with every attribute at its default: the defaults of a SpecFrame, a
     * DSBCentre of 30 GHz (topocentric), an IF of 4 GHz and the lower sideband.
     */
    public DSBSpecFrame() {
        super();
    }

    /**
     * Loads a DSBSpecFrame from its description, as C's {@code astLoadDSBSpecFrame} does: DSBCen,
     * IF, SideBn and AlSdBn, each unset if absent.
     *
     * @param node the description
     * @throws AstException with status {@code AST__ATTIN} if the SideBand is not USB, LSB or LO
     */
    DSBSpecFrame(NativeNode node) {
        super(node);
        NativeNode part = node.section("DSBSpecFrame");
        dsbCentre = part.readDouble("DSBCen", AST__BAD);
        ifr = part.readDouble("IF", AST__BAD);
        String text = part.getStringIfSet("SideBn");
        if (text != null) {
            if ("USB".equals(text)) {
                sideBand = USB;
            } else if ("LSB".equals(text)) {
                sideBand = LSB;
            } else if ("LO".equals(text)) {
                sideBand = LO;
            } else {
                throw Ast.err(
                        AstException.AST__ATTIN, "astRead(Channel): Invalid SideBand description \"" + text + "\".");
            }
        }
        int ival = part.readInt("AlSdBn", -1);
        if (ival != -1) {
            alignSideBand = ival != 0 ? 1 : 0;
        }
    }

    @Override
    String dumpComment() {
        return "Dual sideband spectral axis";
    }

    /** Writes the DSBSpecFrame class's part of a description, as C's {@code Dump} (dsbspecframe.c) does. */
    @Override
    void dump(NativeNode node) {
        super.dump(node);
        node.writeIsA("SpecFrame", "Description of spectral coordinate system");
        node.writeDouble("DSBCen", testDSBCentre(), true, dsbCentreValue(), "Central frequency (Hz topo)");
        node.writeDouble("IF", testIF(), true, ifValue(), "Intermediate frequency (Hz)");
        int ival = sideBandValue();
        String cval;
        String comm;
        if (ival == LSB) {
            cval = "LSB";
            comm = "Represents lower sideband";
        } else if (ival == LO) {
            cval = "LO";
            comm = "Represents offset from LO frequency";
        } else {
            cval = "USB";
            comm = "Represents upper sideband";
        }
        node.writeString("SideBn", testSideBand(), true, cval, comm);
        node.writeInt("AlSdBn", testAlignSideBand(), true, alignSideBandValue() ? 1 : 0, "Align sidebands?");
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Gets the AlignSideBand attribute: whether the SideBand is taken into account when this
     * DSBSpecFrame is aligned with another by {@code findFrame} or {@code convert}. If both
     * DSBSpecFrames have AlignSideBand set, values are mapped from the target's sideband to its
     * observed sideband, then between the spectral systems of the two, and then from the result's
     * observed sideband to its own sideband; otherwise the two are aligned as SpecFrames, the
     * SideBand being ignored. The default is false.
     *
     * @return the AlignSideBand
     */
    public boolean getAlignSideBand() {
        return getB("AlignSideBand");
    }

    /**
     * Sets the AlignSideBand attribute.
     *
     * @param alignSideBand the AlignSideBand
     */
    public void setAlignSideBand(boolean alignSideBand) {
        setB("AlignSideBand", alignSideBand);
    }

    /**
     * Gets the DSBCentre attribute: the central position of interest, which with the IF gives the
     * local oscillator frequency. It is given in the System, Unit and standard of rest of this
     * DSBSpecFrame, and stored as a topocentric frequency, so it follows a change of those; the
     * attributes needed to convert it to a topocentric frequency (such as ObsLon, ObsLat, Epoch,
     * RefRA, RefDec and RestFreq) should be set first. The default is 30 GHz, topocentric.
     *
     * @return the DSBCentre
     * @throws AstException if it cannot be converted from a topocentric frequency
     */
    public double getDsbCentre() {
        return getD("DsbCentre");
    }

    /**
     * Sets the DSBCentre attribute.
     *
     * @param dsbCentre the DSBCentre, in the System and Unit of this DSBSpecFrame
     * @throws AstException with status {@code AST__ATTIN} if the value has no topocentric frequency
     */
    public void setDsbCentre(double dsbCentre) {
        setD("DsbCentre", dsbCentre);
    }

    /**
     * Gets the IF attribute: the topocentric intermediate frequency in GHz, which added to the
     * DSBCentre (as a topocentric frequency) gives the local oscillator frequency. Its sign says
     * whether the local oscillator is above or below the DSBCentre, and so which is the observed
     * sideband. A value may be given with units of frequency, such as {@code 4000 MHz}. The default
     * is 4 GHz.
     *
     * @return the IF, in GHz
     */
    public double getIf() {
        return getD("If");
    }

    /**
     * Sets the IF attribute.
     *
     * @param ifreq the IF, in GHz
     */
    public void setIf(double ifreq) {
        setD("If", ifreq);
    }

    /**
     * Gets the SideBand attribute: {@code USB} for the upper sideband, {@code LSB} for the lower
     * sideband, or {@code LO} for offsets from the local oscillator frequency. It may be set to
     * {@code observed} or {@code image} for the sideband that holds the DSBCentre or the other. The
     * default is the observed sideband: the lower if the IF is positive, the upper otherwise.
     *
     * @return the SideBand
     */
    public String getSideBand() {
        return getC("SideBand");
    }

    /**
     * Sets the SideBand attribute.
     *
     * @param sideBand {@code USB}, {@code LSB}, {@code LO}, {@code observed} or {@code image}, in
     *     any case
     * @throws AstException with status {@code AST__ATTIN} if the value is none of these
     */
    public void setSideBand(String sideBand) {
        setC("SideBand", sideBand);
    }

    /** The DSBCentre as a topocentric frequency in Hz: set, or 3.0E10 (C's {@code GetDSBCentre}). */
    double dsbCentreValue() {
        return dsbCentre != AST__BAD ? dsbCentre : DEFAULT_DSBCENTRE;
    }

    void setDsbCentreValue(double value) {
        dsbCentre = value;
    }

    boolean testDSBCentre() {
        return dsbCentre != AST__BAD;
    }

    void clearDSBCentre() {
        dsbCentre = AST__BAD;
    }

    /** The IF in Hz: set, or 4.0E9 (C's {@code GetIF}). */
    double ifValue() {
        return ifr != AST__BAD ? ifr : DEFAULT_IF;
    }

    void setIfValue(double value) {
        ifr = value;
    }

    boolean testIF() {
        return ifr != AST__BAD;
    }

    void clearIF() {
        ifr = AST__BAD;
    }

    /** The SideBand: set, or the observed sideband, {@link #LSB} for a positive IF and {@link #USB} otherwise. */
    int sideBandValue() {
        if (sideBand != BADSB) {
            return sideBand;
        }
        return ifValue() > 0 ? LSB : USB;
    }

    /** Sets the SideBand, as C's {@code SetSideBand} does: a negative value is LSB, zero LO and a positive one USB. */
    void setSideBandValue(int value) {
        sideBand = value < 0 ? LSB : (value == 0 ? LO : USB);
    }

    boolean testSideBand() {
        return sideBand != BADSB;
    }

    void clearSideBand() {
        sideBand = BADSB;
    }

    /** The AlignSideBand flag: set, or false. */
    boolean alignSideBandValue() {
        return alignSideBand == 1;
    }

    void setAlignSideBandValue(boolean value) {
        alignSideBand = value ? 1 : 0;
    }

    boolean testAlignSideBand() {
        return alignSideBand != -1;
    }

    void clearAlignSideBand() {
        alignSideBand = -1;
    }

    /**
     * The ImagFreq in Hz (C's {@code GetImagFreq}): the rest frequency, taken as a frequency in the
     * observed sideband in the source's standard of rest, transformed to the image sideband, with a
     * copy of this DSBSpecFrame set to describe it.
     *
     * @throws AstException if the Mapping between the sidebands cannot be made
     */
    double imagFreqValue() {
        DSBSpecFrame rfFrame = (DSBSpecFrame) copy();
        rfFrame.setStdOfRestValue(AST__SCSOR);
        rfFrame.setSystemValue(AST__FREQ);
        rfFrame.setUnit(1, "Hz");
        rfFrame.setC("SideBand", "observed");
        int sb = rfFrame.sideBandValue();
        Mapping map;
        if (sb == USB) {
            map = rfFrame.toLsbMapping("astGetImagFreq");
        } else if (sb == LSB) {
            map = rfFrame.toUsbMapping("astGetImagFreq");
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astGetImagFreq(" + astClass() + "): Illegal sideband value (" + sb
                            + ") encountered (internal AST programming error).");
        }
        return transformValue(map, rfFrame.restFreqValue());
    }

    /** The Domain: set, or {@code DSBSPECTRUM}. */
    @Override
    public String getDomain() {
        return testDomain() ? super.getDomain() : "DSBSPECTRUM";
    }

    /**
     * Gets the Label of the axis (C's {@code GetLabel}): set, or the SpecFrame's default followed
     * by the SideBand in brackets, as {@code Frequency (LSB)}.
     */
    @Override
    public String getLabel(int axis) {
        validateAxis(axis, true, "astGetLabel");
        String result = super.getLabel(axis);
        if (!testLabel(axis)) {
            result = result + " (" + getAttrib("sideband") + ")";
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Sideband Mappings
    // ------------------------------------------------------------------

    /** One value transformed by a Mapping with one input and one output. */
    private static double transformValue(Mapping map, double value) {
        return map.tran1(1, new double[] {value}, true)[0];
    }

    /**
     * C's {@code VerifyAttrs} (dsbspecframe.c): with UseDefs false, checks that each attribute
     * named in {@code attrs} (DSBCentre or IF, separated by white space, a name standing for every
     * attribute it starts) is set.
     *
     * @throws AstException with status {@code AST__NOVAL} if one is not, saying that the
     *     DSBSpecFrame cannot do {@code purp}
     */
    private void verifyDsbAttrs(String purp, String attrs, String method) {
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
            if ("DSBCentre".startsWith(a)) {
                set = testDSBCentre();
                desc = "central position of interest";
            } else if ("IF".startsWith(a)) {
                set = testIF();
                desc = "intermediate frequency";
            } else {
                throw Ast.err(
                        AstException.AST__INTER,
                        "VerifyAttrs(DSBSpecFrame): Unknown attribute name \"" + a
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

    /**
     * The local oscillator frequency, a topocentric frequency in Hz (C's {@code GetLO}): the
     * DSBCentre plus the IF.
     *
     * @param checkMsg if not null, the IF and DSBCentre must be set when UseDefs is false, and an
     *     error says that the DSBSpecFrame cannot do this
     * @param method the method named in an error
     * @throws AstException with status {@code AST__NOVAL} if an attribute {@code checkMsg} needs
     *     is not set, or {@code AST__ATTIN} if the frequency is less than the size of the IF
     */
    double loValue(String checkMsg, String method) {
        if (checkMsg != null) {
            verifyDsbAttrs(checkMsg, "IF DSBCentre", method);
        }
        double fIf = ifValue();
        double result = dsbCentreValue() + fIf;
        if (result < Math.abs(fIf)) {
            // C reads the DSBCentre for the second message after reporting the first, when its
            // getter gives 3.0E10 whatever the attribute holds.
            throw Ast.err(
                    AstException.AST__ATTIN,
                    method + "(" + astClass() + "): The local oscillator frequency (" + CFormat.format("%g", result)
                            + " Hz) is too low (less than the intermediate frequency: "
                            + CFormat.format("%g", Math.abs(fIf)) + " Hz).\n   This could be caused by a bad value "
                            + "for either the IF attribute (currently " + CFormat.format("%g", fIf)
                            + " Hz) or the DSBCentre attribute (currently " + CFormat.format("%g", DEFAULT_DSBCENTRE)
                            + " Hz).");
        }
        return result;
    }

    /**
     * The Mapping from the spectral system of this DSBSpecFrame to topocentric frequency in Hz, or
     * its inverse (C's {@code TopoMap}): the SpecFrame attributes of this DSBSpecFrame are overlaid
     * onto a new SpecFrame, aligned in the topocentric standard of rest, which is converted to a
     * copy of itself describing topocentric frequency in Hz.
     *
     * @param forward true for the Mapping to topocentric frequency, false for its inverse
     * @param method the method named in an error
     * @throws AstException with status {@code AST__INTER} if there is no conversion
     */
    Mapping topoMap(boolean forward, String method) {
        SpecFrame tf1 = new SpecFrame();
        super.overlay(new int[] {0}, tf1);
        tf1.setAlignStdOfRestValue(AST__TPSOR);
        SpecFrame tf2 = (SpecFrame) tf1.copy();
        tf2.setSystemValue(AST__FREQ);
        tf2.setStdOfRestValue(AST__TPSOR);
        tf2.setUnit(1, "Hz");
        FrameSet fs = tf1.convert(tf2, "");
        if (fs == null) {
            throw Ast.err(
                    AstException.AST__INTER,
                    method + "(" + astClass() + "): Cannot convert DSBCentre value from the supplied system to "
                            + "topocentric frequency (internal AST programming error).");
        }
        Mapping result = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        if (!forward) {
            result.invert();
        }
        return result;
    }

    /**
     * The Mapping from this DSBSpecFrame to a copy of it representing another sideband, as C's
     * {@code ToLSBMapping} and {@code ToUSBMapping} build it: {@code tmap}, the Mapping to
     * topocentric frequency, then a WinMap taking {@code ina} and {@code inb} to {@code outa} and
     * {@code outb}, then {@code tmap} inverted.
     */
    private static Mapping sideBandMapping(Mapping tmap, double ina, double inb, double outa, double outb) {
        Mapping fmap = new WinMap(1, new double[] {ina}, new double[] {inb}, new double[] {outa}, new double[] {outb});
        Mapping map1 = new CmpMap(tmap, fmap, true);
        // The CmpMap keeps the Invert attribute tmap has now, as C's does.
        tmap.invert();
        Mapping map2 = new CmpMap(map1, tmap, true);
        return map2.simplify();
    }

    /**
     * The Mapping from this DSBSpecFrame to a copy of it representing the lower sideband (C's
     * {@code ToLSBMapping}): a UnitMap if it represents the lower sideband already.
     *
     * @param method the method named in an error
     * @throws AstException as {@link #loValue} does
     */
    Mapping toLsbMapping(String method) {
        int sb = sideBandValue();
        if (sb == LSB) {
            return new UnitMap(1);
        }
        Mapping tmap = topoMap(true, method);
        double fLo = loValue("create a Mapping to lower sideband", "astGetImagFreq");
        if (sb == USB) {
            return sideBandMapping(tmap, 0.0, 2 * fLo, 2 * fLo, 0.0);
        }
        return sideBandMapping(tmap, 0.0, fLo, fLo, 0.0);
    }

    /**
     * The Mapping from this DSBSpecFrame to a copy of it representing the upper sideband (C's
     * {@code ToUSBMapping}): a UnitMap if it represents the upper sideband already.
     *
     * @param method the method named in an error
     * @throws AstException as {@link #loValue} does
     */
    Mapping toUsbMapping(String method) {
        int sb = sideBandValue();
        if (sb == USB) {
            return new UnitMap(1);
        }
        Mapping tmap = topoMap(true, method);
        double fLo = loValue("create a Mapping to upper sideband", "astGetImagFreq");
        if (sb == LSB) {
            return sideBandMapping(tmap, 0.0, 2 * fLo, 2 * fLo, 0.0);
        }
        return sideBandMapping(tmap, 0.0, -fLo, fLo, 0.0);
    }

    /** The Mapping to a copy of this DSBSpecFrame representing its observed sideband, {@code obsSb}. */
    private Mapping toObservedMapping(int obsSb, Frame errorFrame) {
        if (obsSb == USB) {
            return toUsbMapping("astSubFrame");
        } else if (obsSb == LSB) {
            return toLsbMapping("astSubFrame");
        } else {
            throw Ast.err(
                    AstException.AST__INTER,
                    "astGetImagFreq(" + errorFrame.astClass() + "): Illegal sideband value (" + obsSb
                            + ") encountered (internal AST programming error).");
        }
    }

    /**
     * The observed sideband, found as C's {@code SubFrame} finds it: the SideBand is set to {@code
     * observed} and then set back to the value it had, so that it is set afterwards.
     */
    private int observedSideBand() {
        int oldSb = sideBandValue();
        setC("SideBand", "observed");
        int obsSb = sideBandValue();
        setSideBandValue(oldSb);
        return obsSb;
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * Matches this DSBSpecFrame, a template, against a target (C's {@code Match}, dsbspecframe.c):
     * as a SpecFrame matches it; then, if the matched target axis belongs to a DSBSpecFrame, the
     * target's {@link #subFrame} is called again with this template, as C calls it.
     */
    @Override
    FrameFinder.MatchResult match(Frame target, boolean matchSub) {
        FrameFinder.MatchResult m = super.match(target, matchSub);
        if (m == null) {
            return null;
        }
        Frame frame0 = target.primaryFrame(m.targetAxes[0] + 1, new int[1]);
        if (!(frame0 instanceof DSBSpecFrame)) {
            return m;
        }
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        if (!target.subFrame(this, 1, m.targetAxes, m.templateAxes, map, result)) {
            return null;
        }
        return new FrameFinder.MatchResult(m.templateAxes, m.targetAxes, map[0], result[0]);
    }

    /**
     * Selects axes from this DSBSpecFrame (C's {@code SubFrame}, dsbspecframe.c): as a SpecFrame
     * does; then, if the template and the result are DSBSpecFrames and the sidebands are aligned,
     * the Mapping first maps this DSBSpecFrame's values to its observed sideband and last maps the
     * result's observed sideband to its own. The sidebands are aligned while a FrameSet's integrity
     * is restored ({@code AST__INTFLAG} set on this target), and otherwise if both this target and
     * the template have AlignSideBand set. Finding the observed sideband leaves this target's
     * SideBand set to the value it had (a C quirk, followed here).
     *
     * @throws AstException as {@link SpecFrame#subFrame} does, or as {@link #loValue} does
     */
    @Override
    boolean subFrame(
            Frame template, int resultNaxes, int[] targetAxes, int[] templateAxes, Mapping[] map, Frame[] result) {
        boolean match = super.subFrame(template, resultNaxes, targetAxes, templateAxes, map, result);
        if (match && template instanceof DSBSpecFrame && result[0] instanceof DSBSpecFrame) {
            boolean alignsb;
            if ((getFrameFlags() & AST__INTFLAG) != 0) {
                alignsb = true;
            } else {
                alignsb = alignSideBandValue() && ((DSBSpecFrame) template).alignSideBandValue();
            }
            if (alignsb) {
                Mapping map1 = toObservedMapping(observedSideBand(), this);
                DSBSpecFrame dsbresult = (DSBSpecFrame) result[0];
                Mapping map2 = dsbresult.toObservedMapping(dsbresult.observedSideBand(), this);
                map2.invert();
                Mapping map3 = new CmpMap(map1, map[0], true);
                map1 = new CmpMap(map3, map2, true);
                map[0] = map1.simplify();
            }
        }
        if (!match) {
            map[0] = null;
            result[0] = null;
        }
        return match;
    }

    /**
     * Overlays the attributes set on this DSBSpecFrame, the template, onto another Frame (C's
     * {@code Overlay}, dsbspecframe.c): those of a SpecFrame, then, onto a DSBSpecFrame, the
     * DSBCentre, IF, SideBand and AlignSideBand that are set.
     */
    @Override
    void overlay(int[] templateAxes, Frame result) {
        super.overlay(templateAxes, result);
        if (result instanceof DSBSpecFrame) {
            DSBSpecFrame res = (DSBSpecFrame) result;
            if (testDSBCentre()) {
                res.setDsbCentreValue(dsbCentreValue());
            }
            if (testIF()) {
                res.setIfValue(ifValue());
            }
            if (testSideBand()) {
                res.setSideBandValue(sideBandValue());
            }
            if (testAlignSideBand()) {
                res.setAlignSideBandValue(alignSideBandValue());
            }
        }
    }

    // ------------------------------------------------------------------
    // String-based attribute access
    // ------------------------------------------------------------------

    @Override
    String getAttrib(String attrib) {
        if ("dsbcentre".equals(attrib)) {
            double dval = dsbCentreValue();
            Mapping tmap = topoMap(false, "astGetAttrib");
            double dtemp = transformValue(tmap, dval);
            if (dtemp == AST__BAD) {
                throw Ast.err(
                        AstException.AST__INTER,
                        "astGetAttrib(" + astClass() + "): Cannot convert DSBCentre value from topocentric "
                                + "frequency to the required system.");
            }
            return CFormat.format("%." + DBL_DIG + "g", dtemp);
        } else if ("if".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", ifValue() * 1.0E-9);
        } else if ("imagfreq".equals(attrib)) {
            return CFormat.format("%." + DBL_DIG + "g", imagFreqValue() * 1.0E-9);
        } else if ("sideband".equals(attrib)) {
            int ival = sideBandValue();
            return ival == USB ? "USB" : (ival == LO ? "LO" : "LSB");
        } else if ("alignsideband".equals(attrib)) {
            return alignSideBandValue() ? "1" : "0";
        }
        return super.getAttrib(attrib);
    }

    /** The error C's {@code SetAttrib} reports for a setting it cannot use. */
    private AstException invalidSetting(String setting) {
        return Ast.err(
                AstException.AST__ATTIN,
                "astSetAttrib(" + astClass() + "): The setting \"" + setting + "\" is invalid for a " + astClass()
                        + ".");
    }

    /**
     * Sets an attribute from its value as a string, as C's {@code SetAttrib} (dsbspecframe.c)
     * does. C takes any setting holding the text {@code dsbcentre=} for a DSBCentre setting, so
     * one of another attribute whose value holds that text is refused (a C quirk, followed here).
     */
    @Override
    void setAttrib(String attrib, String value) {
        String setting = attrib + "=" + value;
        if (setting.contains("dsbcentre=")) {
            setDsbCentreAttrib(attrib, value, setting);
            return;
        }
        Double dval = CScan.scanDouble(value);
        double[] numberThenWord = CScan.numberThenWord(value);
        Integer ival = CScan.scanInt(value);
        String word = oneWord(value);
        if ("if".equals(attrib) && dval != null) {
            setIfValue(dval * 1.0E9);
        } else if ("if".equals(attrib) && numberThenWord != null) {
            String unit = value.substring((int) numberThenWord[1]);
            Mapping umap = Units.unitMapper(unit, "Hz");
            if (umap == null) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Intermediate frequency given in an inappropriate system "
                                + "of units \"" + CFormat.format("%g", numberThenWord[0]) + " " + unit + "\".");
            }
            setIfValue(transformValue(umap, numberThenWord[0]));
        } else if ("sideband".equals(attrib) && word != null) {
            if (chrMatch("usb", word)) {
                setSideBandValue(USB);
            } else if (chrMatch("lsb", word)) {
                setSideBandValue(LSB);
            } else if (chrMatch("lo", word)) {
                setSideBandValue(LO);
            } else if (chrMatch("observed", word)) {
                setSideBandValue(ifValue() > 0 ? LSB : USB);
            } else if (chrMatch("image", word)) {
                setSideBandValue(ifValue() <= 0 ? LSB : USB);
            } else {
                throw invalidSetting(setting);
            }
        } else if ("alignsideband".equals(attrib) && ival != null) {
            setAlignSideBandValue(ival != 0);
        } else if ("imagfreq".equals(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astSet: The setting \"" + setting + "\" is invalid for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else {
            super.setAttrib(attrib, value);
        }
    }

    /**
     * A DSBCentre setting (C's {@code SetAttrib}): a value in the System and Unit of this
     * DSBSpecFrame, or a value with units converted to its Unit, converted to a topocentric
     * frequency and stored.
     */
    private void setDsbCentreAttrib(String attrib, String value, String setting) {
        boolean ok = false;
        double dval = 0.0;
        boolean named = "dsbcentre".equals(attrib);
        Double plain = named ? CScan.scanDouble(value) : null;
        double[] numberThenWord = named && plain == null ? CScan.numberThenWord(value) : null;
        if (plain != null) {
            dval = plain;
            ok = true;
        } else if (numberThenWord != null) {
            Mapping umap = Units.unitMapper(value.substring((int) numberThenWord[1]), getUnit(1));
            if (umap == null) {
                throw Ast.err(
                        AstException.AST__ATTIN,
                        "astSetAttrib(" + astClass() + "): Value supplied for attribute \"DSBCentre\" (" + value
                                + ") uses units which are inappropriate for the current spectral system ("
                                + getTitle() + ").");
            }
            dval = transformValue(umap, numberThenWord[0]);
            ok = dval != AST__BAD;
        }
        if (!ok) {
            throw invalidSetting(setting);
        }
        Mapping tmap = topoMap(true, "astSetAttrib");
        double dtemp = transformValue(tmap, dval);
        if (dtemp == AST__BAD) {
            throw invalidSetting(setting);
        }
        setDsbCentreValue(dtemp);
    }

    @Override
    boolean testAttrib(String attrib) {
        if ("dsbcentre".equals(attrib)) {
            return testDSBCentre();
        } else if ("if".equals(attrib)) {
            return testIF();
        } else if ("sideband".equals(attrib)) {
            return testSideBand();
        } else if ("alignsideband".equals(attrib)) {
            return testAlignSideBand();
        } else if ("imagfreq".equals(attrib)) {
            return false;
        }
        return super.testAttrib(attrib);
    }

    @Override
    void clearAttrib(String attrib) {
        if ("dsbcentre".equals(attrib)) {
            clearDSBCentre();
        } else if ("if".equals(attrib)) {
            clearIF();
        } else if ("sideband".equals(attrib)) {
            clearSideBand();
        } else if ("alignsideband".equals(attrib)) {
            clearAlignSideBand();
        } else if ("imagfreq".equals(attrib)) {
            throw Ast.err(
                    AstException.AST__NOWRT,
                    "astClear: Invalid attempt to clear the \"" + attrib + "\" value for a " + astClass()
                            + ".\nThis is a read-only attribute.");
        } else {
            super.clearAttrib(attrib);
        }
    }
}
