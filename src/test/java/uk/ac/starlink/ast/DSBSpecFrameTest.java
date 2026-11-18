package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;

/**
 * Translation of the tests of Rust {@code tests/test_dsbspecframe.rs} and {@code
 * frames/dsbspecframe.rs}: construction and defaults, the SideBand, IF, DSBCentre, AlignSideBand
 * and ImagFreq attributes and the units they are given in, the sideband Mappings, conversions
 * between DSBSpecFrames and SpecFrames, FrameSet integrity, Overlay, Match and native dumps.
 *
 * <p>The expected values are C's, from a probe run against the pinned C library ({@code
 * task-4-probes/dsb_cases.c}; each case is a list of attribute settings, applied as the probe
 * applies them), where the Rust tests compare with formulae. Rust sets the topocentric DSBCentre
 * in Hz and the IF in Hz directly; here they are given as attributes, in a topocentric frequency
 * System in Hz where the DSBCentre is that frequency, and the IF in GHz.
 *
 * <p>Rust's {@code observed_sideband_usb}, {@code observed_sideband_lsb} and {@code
 * observed_sideband_lo} test {@code observed_freq}, which C does not have; {@code lo_offset_mapping}
 * tests Rust's port of {@code ToLOMapping}, which C never calls, and is translated as the
 * conversion to the LO offset C makes with {@code ToLSBMapping}.
 */
class DSBSpecFrameTest {

    /** A topocentric frequency in Hz, the DSBCentre 220 GHz and the IF 4 GHz (the LO 224 GHz). */
    private static final String H = "System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=220e9,IF=4";

    /** As {@link #H} in GHz. */
    private static final String G = "System=FREQ,StdOfRest=TOPO,DSBCentre=220,IF=4";

    /** A DSBSpecFrame with the settings applied, as the probe's {@code make} does. */
    private static DSBSpecFrame make(String settings) {
        DSBSpecFrame f = new DSBSpecFrame();
        if (!settings.isEmpty()) {
            f.set(settings);
        }
        return f;
    }

    /** As {@link #make}, or a SpecFrame for settings starting {@code S:}. */
    private static Frame makeAny(String settings) {
        if (settings.startsWith("S:")) {
            SpecFrame f = new SpecFrame();
            f.set(settings.substring(2));
            return f;
        }
        return make(settings);
    }

    /** The result of the probe's {@code conv}: the converted value, its inverse, the Mapping class. */
    private static final class Conv {
        final double forward;
        final double inverse;
        final String mapClass;

        Conv(double forward, double inverse, String mapClass) {
            this.forward = forward;
            this.inverse = inverse;
            this.mapClass = mapClass;
        }
    }

    private static Conv conv(Frame from, Frame to, double x) {
        FrameSet fs = from.convert(to, "");
        assertNotNull(fs, "a conversion");
        double y = fs.tran1(1, new double[] {x}, true)[0];
        double z = fs.tran1(1, new double[] {y}, false)[0];
        String cls = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                .simplify()
                .astClass();
        return new Conv(y, z, cls);
    }

    private static Conv conv(String a, String b, double x) {
        return conv(makeAny(a), makeAny(b), x);
    }

    private static void assertConv(String a, String b, double x, double forward, double inverse, String cls) {
        Conv c = conv(a, b, x);
        assertTrue(CValues.cValueEq(c.forward, forward), "forward: got " + c.forward + ", C gives " + forward);
        assertTrue(CValues.cValueEq(c.inverse, inverse), "inverse: got " + c.inverse + ", C gives " + inverse);
        assertEquals(cls, c.mapClass, "simplified Mapping");
    }

    private static void assertCEq(double want, double got, String what) {
        assertTrue(CValues.cValueEq(got, want), what + ": got " + got + ", C gives " + want);
    }

    private static AstException assertFails(int status, Runnable r) {
        AstException e = assertThrows(AstException.class, r::run);
        assertEquals(status, e.getStatus(), e.getMessage());
        return e;
    }

    // ------------------------------------------------------------------
    // Construction and attributes
    // ------------------------------------------------------------------

    @Test
    void constructionDefaults() {
        DSBSpecFrame f = make("System=FREQ");
        assertEquals("FREQ", f.getC("System"));
        assertEquals("LSB", f.getSideBand());
        assertFalse(f.test("SideBand"));
        assertFalse(f.getAlignSideBand());
        assertFalse(f.test("AlignSideBand"));
        assertEquals("4", f.getC("IF"));
        assertFalse(f.test("IF"));
        assertEquals(3.0E10, f.dsbCentreValue());
        assertEquals(4.0E9, f.ifValue());
        assertEquals("30000000000", make("System=FREQ,Unit=Hz,StdOfRest=TOPO").getC("DSBCentre"));
        assertEquals(1, f.getNaxes());
    }

    /** The SideBand defaults to the observed sideband, LSB for a positive IF and USB otherwise. */
    @Test
    void sideBandDefaultFollowsTheSignOfTheIf() {
        assertEquals("LSB", make("System=FREQ,IF=4").getSideBand());
        assertEquals("USB", make("System=FREQ,IF=-4").getSideBand());
        assertEquals("USB", make("System=FREQ,IF=0").getSideBand());
        DSBSpecFrame f = make("System=FREQ");
        f.setIfValue(-4.0E9);
        assertEquals("USB", f.getSideBand());
    }

    @Test
    void explicitSideBandOverridesTheDefault() {
        DSBSpecFrame f = make("System=FREQ,IF=4,SideBand=USB");
        assertEquals("USB", f.getSideBand());
        assertTrue(f.test("SideBand"));
    }

    @Test
    void setDsbCentre() {
        DSBSpecFrame f = make("System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=1e11");
        assertEquals("100000000000", f.getC("DSBCentre"));
        assertTrue(f.test("DSBCentre"));
        assertEquals(1.0E11, f.dsbCentreValue());
        DSBSpecFrame g = make("System=FREQ");
        g.setDsbCentreValue(1.0E11);
        assertEquals(1.0E11, g.dsbCentreValue());
        assertTrue(g.testDSBCentre());
    }

    @Test
    void setAlignSideBand() {
        DSBSpecFrame f = make("System=FREQ");
        assertFalse(f.getAlignSideBand());
        f.set("AlignSideBand=1");
        assertTrue(f.getAlignSideBand());
        assertTrue(f.test("AlignSideBand"));
        assertEquals(1, make("System=FREQ,AlignSideBand=2").getI("AlignSideBand"));
    }

    /** The names of the sidebands, read ignoring case and white space as C's {@code astChrMatch} reads them. */
    @Test
    void sideBandNames() {
        String[][] cases = {
            {"System=FREQ", "SideBand=USB", "USB"},
            {"System=FREQ", "SideBand=lsb", "LSB"},
            {"System=FREQ", "SideBand=LO", "LO"},
            {"System=FREQ", "SideBand=  Lo  ", "LO"},
            {"System=FREQ,IF=4", "SideBand=observed", "LSB"},
            {"System=FREQ,IF=4", "SideBand=image", "USB"},
            {"System=FREQ,IF=-4", "SideBand=OBSERVED", "USB"},
            {"System=FREQ,IF=-4", "SideBand=Image", "LSB"},
        };
        for (String[] c : cases) {
            DSBSpecFrame f = make(c[0]);
            f.set(c[1]);
            assertEquals(c[2], f.getC("SideBand"), c[0] + " " + c[1]);
            assertTrue(f.test("SideBand"));
        }
        DSBSpecFrame f = make("System=FREQ");
        AstException e = assertFails(AstException.AST__ATTIN, () -> f.set("SideBand=bogus"));
        assertEquals(
                "astSetAttrib(DSBSpecFrame): The setting \"sideband=bogus\" is invalid for a DSBSpecFrame.",
                e.getMsgText());
        e = assertFails(AstException.AST__BADAT, () -> f.set("SideBand=u sb"));
        assertEquals("astSet: The attribute setting \"sideband=u sb\" is invalid for a DSBSpecFrame.", e.getMsgText());
        assertEquals("LSB", f.getC("SideBand"));
        assertFalse(f.test("SideBand"));
    }

    @Test
    void specFrameAttributes() {
        DSBSpecFrame f = make("System=VRAD");
        assertEquals("VRAD", f.getSystem());
        f.set("System=FREQ");
        assertEquals("FREQ", f.getSystem());
        f.set("RestFreq=1.42");
        assertEquals(1.4200000000000002, f.getRestFreq());
        assertEquals(1.42E9, f.restFreqValue());
    }

    /** The Domain defaults to DSBSPECTRUM without being set (dsbspecframe.c {@code GetDomain}). */
    @Test
    void domainDefaultIsUnset() {
        DSBSpecFrame f = make("System=FREQ");
        assertEquals("DSBSPECTRUM", f.getDomain());
        assertFalse(f.test("Domain"));
        f.setDomain("FOO");
        assertEquals("FOO", f.getDomain());
        assertTrue(f.test("Domain"));
    }

    /** A default Label ends with the SideBand (dsbspecframe.c {@code GetLabel}). */
    @Test
    void labelNamesTheSideBand() {
        assertEquals("Radio velocity (LO)", make("System=VRAD,SideBand=LO").getLabel(1));
        assertEquals("Frequency (LSB)", make("System=FREQ").getC("Label"));
        DSBSpecFrame f = make("System=VRAD,Label(1)=Speed");
        assertEquals("Speed", f.getLabel(1));
        assertTrue(f.test("Label(1)"));
    }

    /** DSBSpecFrame does not override Unformat in C; it reads as a Frame does (axis.c). */
    @Test
    void unformatBaseContract() {
        DSBSpecFrame f = make("System=FREQ");
        double[] v = {0.0};
        assertEquals(8, f.unformat(1, "  12.5  ", v));
        assertEquals(12.5, v[0]);
        assertEquals(5, f.unformat(1, "<bad>", v));
        assertEquals(AstObject.AST__BAD, v[0]);
        assertEquals(11, f.unformat(1, " < b a d > ", v));
        assertEquals(AstObject.AST__BAD, v[0]);
        assertEquals(4, f.unformat(1, "12.5xyz", v));
        assertEquals(12.5, v[0]);
        assertEquals(0, f.unformat(1, "xyz", v));
    }

    @Test
    void ifIsGivenInGhz() {
        DSBSpecFrame f = make("System=FREQ,IF=6");
        assertEquals(6.0, f.getIf());
        assertEquals(6.0E9, f.ifValue());
        assertEquals("4", make("System=FREQ,IF=4000 MHz").getC("IF"));
        DSBSpecFrame g = make("System=FREQ");
        AstException e = assertFails(AstException.AST__ATTIN, () -> g.set("IF=4 m"));
        assertEquals(
                "astSetAttrib(DSBSpecFrame): Intermediate frequency given in an inappropriate system of units \"4 m\".",
                e.getMsgText());
        assertEquals("4", g.getC("IF"));
        assertFalse(g.test("IF"));
    }

    /** In a topocentric frequency System the DSBCentre is the stored topocentric frequency. */
    @Test
    void dsbCentreRoundTripsTopocentric() {
        assertEquals(
                "30000000000",
                make("System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=3e10").getC("DSBCentre"));
        DSBSpecFrame f = make("System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=2.5e10");
        assertEquals("25000000000", f.getC("DSBCentre"));
        assertEquals(2.5E10, f.dsbCentreValue());
    }

    /** In another standard of rest the DSBCentre is converted from the stored topocentric frequency. */
    @Test
    void dsbCentreInANonTopocentricStandardOfRest() {
        DSBSpecFrame f = make("System=FREQ,StdOfRest=TOPO,DSBCentre=30,StdOfRest=LSRK,"
                + "ObsLat=17.188733853924695,ObsLon=-57.295779513082323,Epoch=2000.0");
        f.setRefPos(null, 1.2, 0.5);
        // C's dump gives DSBCen = 29999999999.999996: 30 GHz converted to Hz.
        assertEquals(2.9999999999999996E10, f.dsbCentreValue());
        assertCEq(30.002408543763334, f.getD("DSBCentre"), "DSBCentre");
    }

    @Test
    void dsbCentreGivenWithUnits() {
        assertCEq(
                229.99999999999997,
                make("System=FREQ,StdOfRest=TOPO,DSBCentre=230000 MHz").getD("DSBCentre"),
                "DSBCentre");
        DSBSpecFrame f = make("System=FREQ,StdOfRest=TOPO");
        AstException e = assertFails(AstException.AST__ATTIN, () -> f.set("DSBCentre=5 m"));
        assertEquals(
                "astSetAttrib(DSBSpecFrame): Value supplied for attribute \"DSBCentre\" (5 m) uses units which are "
                        + "inappropriate for the current spectral system (Frequency (Topocentric)).",
                e.getMsgText());
        assertEquals(30.000000000000004, f.getD("DSBCentre"));
        assertFalse(f.test("DSBCentre"));
    }

    /**
     * C finds a DSBCentre setting by searching the whole setting for {@code dsbcentre=}, so a
     * setting of another attribute whose value holds that text is refused (a C quirk, followed
     * here).
     */
    @Test
    void aSettingHoldingDsbCentreIsRefused() {
        DSBSpecFrame f = make("System=FREQ");
        AstException e = assertFails(AstException.AST__ATTIN, () -> f.set("Title=dsbcentre=3"));
        assertEquals(
                "astSetAttrib(DSBSpecFrame): The setting \"title=dsbcentre=3\" is invalid for a DSBSpecFrame.",
                e.getMsgText());
        assertEquals("Frequency", f.getTitle());
        assertFalse(f.test("Title"));
    }

    /** Rust {@code tests/test_class_attrs.rs::dsb_centre_with_no_topocentric_frequency_is_rejected}. */
    @Test
    void dsbCentreWithNoTopocentricFrequencyIsRejected() {
        DSBSpecFrame f = make("System=ZOPT");
        AstException e = assertFails(AstException.AST__ATTIN, () -> f.set("DSBCentre=-1"));
        assertEquals(
                "astSetAttrib(DSBSpecFrame): The setting \"dsbcentre=-1\" is invalid for a DSBSpecFrame.",
                e.getMsgText());
        assertFalse(f.test("DSBCentre"));
        assertEquals(3.0E10, f.dsbCentreValue());
        assertCEq(3332.007159764053, f.getD("DSBCentre"), "DSBCentre");
    }

    // ------------------------------------------------------------------
    // ImagFreq and the local oscillator
    // ------------------------------------------------------------------

    @Test
    void imagFreq() {
        DSBSpecFrame f = make(H + ",SideBand=USB,RestFreq=225,SourceVRF=TOPO,SourceVel=0");
        assertCEq(223.0, f.getD("ImagFreq"), "ImagFreq");
        assertFalse(f.test("ImagFreq"));
    }

    /** With no RestFreq set, C uses its default, 1.0E14 Hz. */
    @Test
    void imagFreqUsesTheDefaultRestFreq() {
        assertCEq(-99931.99334537596, make("").getD("ImagFreq"), "ImagFreq");
    }

    @Test
    void imagFreqIsReadOnly() {
        DSBSpecFrame f = make("System=FREQ");
        AstException e = assertFails(AstException.AST__NOWRT, () -> f.set("ImagFreq=3"));
        assertEquals(
                "astSet: The setting \"imagfreq=3\" is invalid for a DSBSpecFrame.\nThis is a read-only attribute.",
                e.getMsgText());
        e = assertFails(AstException.AST__NOWRT, () -> f.clear("ImagFreq"));
        assertEquals(
                "astClear: Invalid attempt to clear the \"imagfreq\" value for a DSBSpecFrame.\n"
                        + "This is a read-only attribute.",
                e.getMsgText());
        assertFalse(f.test("ImagFreq"));
    }

    @Test
    void loFrequency() {
        DSBSpecFrame f = make(H);
        assertEquals(224.0E9, f.loValue(null, "astGetLO"));
        assertConv(
                H + ",SideBand=USB,AlignSideBand=1",
                H + ",SideBand=LSB,AlignSideBand=1",
                224.0E9,
                224.0E9,
                224.0E9,
                "WinMap");
    }

    /**
     * The local oscillator frequency must be at least the size of the IF (dsbspecframe.c {@code
     * GetLO}); C reads the DSBCentre for its message after reporting the error, when the getter
     * gives its default.
     */
    @Test
    void loBelowTheIfIsAnError() {
        String below = "System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=5e9,IF=-4";
        String message = "astGetImagFreq(DSBSpecFrame): The local oscillator frequency (1e+09 Hz) is too low (less "
                + "than the intermediate frequency: 4e+09 Hz).\n   This could be caused by a bad value for either "
                + "the IF attribute (currently -4e+09 Hz) or the DSBCentre attribute (currently 3e+10 Hz).";
        AstException e = assertFails(AstException.AST__ATTIN, () -> make(below).getD("ImagFreq"));
        assertEquals(message, e.getMsgText());
        e = assertFails(
                AstException.AST__ATTIN,
                () -> conv(below + ",AlignSideBand=1", below + ",SideBand=LSB,AlignSideBand=1", 6.0E9));
        assertEquals(message, e.getMsgText());
        assertFails(AstException.AST__ATTIN, () -> make(below).loValue(null, "astGetImagFreq"));
        DSBSpecFrame above = make("System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=5e9,IF=-2");
        assertEquals(3.0E9, above.loValue(null, "astGetLO"));
        assertCEq(-99993.99941282728, above.getD("ImagFreq"), "ImagFreq");
    }

    /** With UseDefs false a change of sideband needs the IF and DSBCentre set (dsbspecframe.c {@code GetLO}). */
    @Test
    void useDefsRequiresTheIfAndDsbCentre() {
        String lsb = "StdOfRest=TOPO,System=FREQ,SideBand=LSB,AlignSideBand=1";
        AstException e = assertFails(
                AstException.AST__NOVAL,
                () -> conv("StdOfRest=TOPO,System=FREQ,SideBand=USB,AlignSideBand=1,UseDefs=0", lsb, 230.0));
        assertEquals(
                "astGetImagFreq(DSBSpecFrame): Cannot create a Mapping to lower sideband.\n"
                        + "No value has been set for the AST \"IF\" attribute (intermediate frequency).",
                e.getMsgText());
        e = assertFails(
                AstException.AST__NOVAL,
                () -> conv("StdOfRest=TOPO,System=FREQ,SideBand=USB,AlignSideBand=1,IF=4,UseDefs=0", lsb, 230.0));
        assertEquals(
                "astGetImagFreq(DSBSpecFrame): Cannot create a Mapping to lower sideband.\n"
                        + "No value has been set for the AST \"DSBCentre\" attribute (central position of interest).",
                e.getMsgText());
    }

    // ------------------------------------------------------------------
    // Sideband Mappings and conversions
    // ------------------------------------------------------------------

    @Test
    void sideBandFlipUsbToLsb() {
        Mapping m = make(H + ",SideBand=USB").toLsbMapping("astSubFrame");
        assertEquals("WinMap", m.astClass());
        assertCEq(223.0E9, m.tran1(1, new double[] {225.0E9}, true)[0], "LSB");
        assertConv(
                H + ",SideBand=USB,AlignSideBand=1",
                H + ",SideBand=LSB,AlignSideBand=1",
                225.0E9,
                223.0E9,
                225.0E9,
                "WinMap");
    }

    @Test
    void sideBandFlipLsbToUsb() {
        Mapping m = make(H + ",SideBand=LSB").toUsbMapping("astSubFrame");
        assertEquals("WinMap", m.astClass());
        assertCEq(229.0E9, m.tran1(1, new double[] {219.0E9}, true)[0], "USB");
        assertConv(
                H + ",SideBand=LSB,AlignSideBand=1",
                H + ",SideBand=USB,AlignSideBand=1",
                219.0E9,
                229.0E9,
                219.0E9,
                "WinMap");
    }

    /** The Mapping to the sideband a DSBSpecFrame represents is a UnitMap (dsbspecframe.c {@code ToUSBMapping}). */
    @Test
    void sideBandIdentityWhenAlreadyTheTarget() {
        Mapping m = make(G + ",SideBand=USB").toUsbMapping("astSubFrame");
        assertInstanceOf(UnitMap.class, m);
        assertEquals(225.0E9, m.tran1(1, new double[] {225.0E9}, true)[0]);
        assertInstanceOf(UnitMap.class, make(G + ",SideBand=LSB").toLsbMapping("astSubFrame"));
    }

    /** Offsets from the LO, to and from each sideband. */
    @Test
    void loOffsetMappings() {
        assertConv(
                H + ",SideBand=USB,AlignSideBand=1",
                H + ",SideBand=LO,AlignSideBand=1",
                225.0E9,
                1.0E9,
                225.0E9,
                "ShiftMap");
        assertConv(
                H + ",SideBand=LO,AlignSideBand=1",
                H + ",SideBand=USB,AlignSideBand=1",
                1.0E9,
                225.0E9,
                1.0E9,
                "ShiftMap");
        assertConv(
                H + ",SideBand=LO,AlignSideBand=1",
                H + ",SideBand=LSB,AlignSideBand=1",
                1.0E9,
                223.0E9,
                1.0E9,
                "WinMap");
    }

    @Test
    void convertDsbToDsbSameSideBand() {
        assertConv(G + ",SideBand=USB", G + ",SideBand=USB", 225.0E9, 225.0E9, 225.0E9, "UnitMap");
    }

    @Test
    void convertDsbToDsbDifferentSideBandWithAlign() {
        assertConv(
                H + ",SideBand=USB,AlignSideBand=1",
                H + ",SideBand=LSB,AlignSideBand=1",
                225.0E9,
                223.0E9,
                225.0E9,
                "WinMap");
    }

    /** Unless both have AlignSideBand set, two DSBSpecFrames align as SpecFrames. */
    @Test
    void convertDsbToDsbDifferentSideBandNoAlign() {
        assertConv(G + ",SideBand=USB", G + ",SideBand=LSB", 225.0E9, 225.0E9, 225.0E9, "UnitMap");
        assertConv(H + ",SideBand=USB,AlignSideBand=1", H + ",SideBand=LSB", 225.0E9, 225.0E9, 225.0E9, "UnitMap");
    }

    @Test
    void convertDsbToSpecFrame() {
        assertConv(G + ",SideBand=USB", "S:System=FREQ,StdOfRest=TOPO", 225.0E9, 225.0E9, 225.0E9, "UnitMap");
        assertConv("S:System=FREQ,StdOfRest=TOPO", G + ",SideBand=USB", 225.0E9, 225.0E9, 225.0E9, "UnitMap");
    }

    @Test
    void alignmentThroughAFrameSet() {
        assertConv(
                H + ",SideBand=USB,AlignSideBand=1",
                H + ",SideBand=LSB,AlignSideBand=1",
                226.0E9,
                222.0E9,
                226.0E9,
                "WinMap");
    }

    @Test
    void alignmentOfDsbSpecFramesWithDifferentLos() {
        assertConv(
                H + ",SideBand=USB,AlignSideBand=1",
                "System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=230e9,IF=5,SideBand=USB,AlignSideBand=1",
                225.0E9,
                247.0E9,
                225.0E9,
                "ShiftMap");
    }

    /**
     * Aligning in sidebands sets the SideBand of the target, which C's {@code SubFrame} reads and
     * puts back with its setter while it finds the observed sideband (a C quirk, followed here).
     */
    @Test
    void anAlignmentInSideBandsSetsTheTargetsSideBand() {
        DSBSpecFrame from = make(H + ",AlignSideBand=1");
        DSBSpecFrame to = make(H + ",SideBand=LSB,AlignSideBand=1");
        assertFalse(from.test("SideBand"));
        Conv c = conv(from, to, 225.0E9);
        assertEquals(225.0E9, c.forward);
        assertEquals("UnitMap", c.mapClass);
        assertTrue(from.test("SideBand"));
        assertTrue(to.test("SideBand"));

        DSBSpecFrame plain = make(H);
        conv(plain, make(H + ",SideBand=LSB"), 225.0E9);
        assertFalse(plain.test("SideBand"));
    }

    /** Setting a DSBSpecFrame attribute through a FrameSet aligns in sidebands (dsbspecframe.c {@code SubFrame}). */
    @Test
    void frameSetIntegrityFollowsTheSideBand() {
        String[][] cases = {
            {H + ",SideBand=USB", "SideBand=LSB", "225.0E9", "223.0E9", "WinMap"},
            {G + ",SideBand=USB", "SideBand=LSB", "225.0", "222.99999999999997", "WinMap"},
            {H + ",SideBand=USB", "IF=5", "225.0E9", "227.0E9", "ShiftMap"},
            {H + ",SideBand=USB", "DSBCentre=230e9", "225.0E9", "245.0E9", "ShiftMap"},
        };
        for (String[] c : cases) {
            DSBSpecFrame f = make(c[0]);
            FrameSet fs = new FrameSet((Frame) f.copy());
            fs.addFrame(FrameSet.AST__BASE, new UnitMap(1), f);
            fs.set(c[1]);
            double y = fs.tran1(1, new double[] {Double.parseDouble(c[2])}, true)[0];
            assertCEq(Double.parseDouble(c[3]), y, c[0] + " " + c[1]);
            assertEquals(
                    c[4],
                    fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                            .simplify()
                            .astClass(),
                    c[1]);
        }
    }

    /**
     * A DSBSpecFrame template that does not match a SpecFrame target is cast into a SpecFrame
     * and tried again, which fails as well.
     */
    @Test
    void aTemplateCastIntoASpecFrame() {
        SpecFrame target = new SpecFrame();
        target.set("System=FREQ");
        assertNull(target.findFrame(make("System=FREQ,Domain=FOO"), ""));
        SpecFrame target2 = new SpecFrame();
        target2.set("System=FREQ,Domain=FOO");
        assertNull(target2.findFrame(make("System=VRAD,Domain=FOO,MinAxes=2,MaxAxes=2"), ""));
        Frame cast = make("System=VRAD,SideBand=USB").cast(target);
        assertEquals(SpecFrame.class, cast.getClass());
        assertEquals("VRAD", cast.getC("System"));
        assertEquals("Radio velocity", cast.getLabel(1));
    }

    // ------------------------------------------------------------------
    // Overlay, Match and picking axes (frames/dsbspecframe.rs)
    // ------------------------------------------------------------------

    @Test
    void overlayTemplateCopiesSetAttributes() {
        DSBSpecFrame template = make("System=FREQ");
        template.setDsbCentreValue(2.5E10);
        template.setIfValue(3.0E9);
        template.setSideBandValue(DSBSpecFrame.USB);
        template.setAlignSideBandValue(true);
        DSBSpecFrame target = make("System=FREQ");
        template.overlay(new int[] {0}, target);
        assertEquals(2.5E10, target.dsbCentreValue());
        assertEquals(3.0E9, target.ifValue());
        assertEquals("USB", target.getSideBand());
        assertTrue(target.getAlignSideBand());
    }

    @Test
    void overlayTemplateSkipsUnsetAttributes() {
        DSBSpecFrame template = make("System=FREQ");
        DSBSpecFrame target = make("System=FREQ");
        target.setDsbCentreValue(7.7E10);
        target.setIfValue(1.5E9);
        template.overlay(new int[] {0}, target);
        assertEquals(7.7E10, target.dsbCentreValue());
        assertEquals(1.5E9, target.ifValue());
        assertFalse(target.testSideBand());
        assertFalse(target.testAlignSideBand());
    }

    /** A DSBSpecFrame template overlays the DSBSpecFrame attributes onto a DSBSpecFrame only. */
    @Test
    void overlayOntoASpecFrame() {
        DSBSpecFrame template = make("System=VRAD,SideBand=USB,IF=5");
        SpecFrame target = new SpecFrame();
        template.overlay(new int[] {0}, target);
        assertEquals("VRAD", target.getSystem());
    }

    @Test
    void matchSelf() {
        FrameFinder.MatchResult m = make("System=FREQ").match(make("System=FREQ"), false);
        assertNotNull(m);
        assertEquals(0, m.targetAxes[0]);
        assertEquals(0, m.templateAxes[0]);
        assertEquals(1, m.result.getNaxes());
        assertInstanceOf(DSBSpecFrame.class, m.result);
    }

    /** A DSBSpecFrame template matches a SpecFrame target, as a SpecFrame template does. */
    @Test
    void matchAgainstASpecFrameTarget() {
        SpecFrame target = new SpecFrame();
        target.set("System=FREQ");
        FrameFinder.MatchResult m = make("System=FREQ").match(target, true);
        assertNotNull(m);
        assertEquals(SpecFrame.class, m.result.getClass());
    }

    @Test
    void matchAgainstAPlainFrameFails() {
        assertNull(make("System=FREQ").match(new Frame(1), false));
    }

    @Test
    void pickAxesFull() {
        Frame f = make("System=FREQ,SideBand=USB").pickAxes(1, new int[] {1}, null);
        assertEquals("USB", assertInstanceOf(DSBSpecFrame.class, f).getSideBand());
        assertEquals(1, f.getNaxes());
    }

    // ------------------------------------------------------------------
    // Dumps
    // ------------------------------------------------------------------

    private static final String DUMP_ALL = String.join(
            "\n",
            " Begin DSBSpecFrame \t# Dual sideband spectral axis",
            "#   Title = \"Radio velocity, rest frequency = 230 GHz\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"DSBSPECTRUM\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Radio velocity (LO)\" \t# Label for axis 1",
            "    System = \"VRAD\" \t# Coordinate system type",
            "#   Uni1 = \"km/s\" \t# Units for axis 1",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            "#   SoR = \"Heliocentric\" \t# Standard of rest",
            "    RstFrq = 230000000000 \t# Rest frequency (Hz)",
            " IsA SpecFrame \t# Description of spectral coordinate system",
            "    DSBCen = 229969822691.80344 \t# Central frequency (Hz topo)",
            "    IF = -4000000000 \t# Intermediate frequency (Hz)",
            "    SideBn = \"LO\" \t# Represents offset from LO frequency",
            "    AlSdBn = 1 \t# Align sidebands?",
            " End DSBSpecFrame",
            "");

    private static final String DUMP_SET = String.join(
            "\n",
            " Begin DSBSpecFrame \t# Dual sideband spectral axis",
            "#   Title = \"Frequency (Topocentric)\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"DSBSPECTRUM\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Frequency (USB)\" \t# Label for axis 1",
            "    System = \"FREQ\" \t# Coordinate system type",
            "#   Uni1 = \"Hz\" \t# Units for axis 1 (Hertz)",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "          Unit = \"Hz\" \t# Axis units (Hertz)",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            "    SoR = \"Topocentric\" \t# Standard of rest",
            "    RstFrq = 220398676500 \t# Rest frequency (Hz)",
            "    UFreq = \"Hz\" \t# Preferred units for frequency",
            " IsA SpecFrame \t# Description of spectral coordinate system",
            "    DSBCen = 220463127593.87799 \t# Central frequency (Hz topo)",
            "    IF = -3999979572.89606 \t# Intermediate frequency (Hz)",
            "    SideBn = \"USB\" \t# Represents upper sideband",
            "#   AlSdBn = 0 \t# Align sidebands?",
            " End DSBSpecFrame",
            "");

    /** Every DSBSpecFrame attribute set: C's dump, read back to the same DSBSpecFrame. */
    @Test
    void dumpMatchesTheCLibraryAndReadsBack() {
        DSBSpecFrame f = make("System=VRAD,RestFreq=230,DSBCentre=10,IF=-4,SideBand=LO,AlignSideBand=1");
        assertEquals(DUMP_ALL, ChannelText.dump(f));
        DSBSpecFrame back = assertInstanceOf(DSBSpecFrame.class, ChannelText.read(DUMP_ALL));
        assertEquals(DUMP_ALL, ChannelText.dump(back));
        assertEquals(229969822691.80344, back.dsbCentreValue());
        assertEquals(-4.0E9, back.ifValue());
        assertEquals("LO", back.getSideBand());
        assertTrue(back.getAlignSideBand());
    }

    /** Rust's {@code serialization_round_trip}. */
    @Test
    void serialisationRoundTrip() {
        DSBSpecFrame f = make("System=FREQ,Unit=Hz,StdOfRest=TOPO,DSBCentre=220.463127593878e9,"
                + "IF=-3.99997957289606,SideBand=USB,RestFreq=220.3986765");
        assertEquals(DUMP_SET, ChannelText.dump(f));
        DSBSpecFrame back = assertInstanceOf(DSBSpecFrame.class, ChannelText.read(DUMP_SET));
        assertEquals(f.dsbCentreValue(), back.dsbCentreValue());
        assertEquals(f.ifValue(), back.ifValue());
        assertEquals("USB", back.getSideBand());
        assertEquals(DUMP_SET, ChannelText.dump(back));
    }

    /** Rust's {@code deserialize_fixture}: C's {@code serialisation/jan-dsb.ast}. */
    @Test
    void readsTheCFixture() {
        String text = Fixtures.read("serialisation/jan-dsb.ast");
        DSBSpecFrame f = assertInstanceOf(DSBSpecFrame.class, ChannelText.read(text));
        assertEquals("USB", f.getSideBand());
        assertEquals(220463127593.87817, f.dsbCentreValue());
        assertEquals(-3999979572.896057, f.ifValue());
        assertFalse(f.testAlignSideBand());
        assertEquals(text, ChannelText.dump(f));
    }

    /** An unknown SideBand in a dump is refused, as C's loader refuses it. */
    @Test
    void loaderRefusesAnUnknownSideBand() {
        String text = DUMP_ALL.replace("SideBn = \"LO\"", "SideBn = \"XX\"");
        AstException e = assertFails(AstException.AST__ATTIN, () -> ChannelText.read(text));
        assertTrue(e.getMsgText().contains("Invalid SideBand description \"XX\"."), e.getMsgText());
    }

    @Test
    void copiesAreIndependent() {
        DSBSpecFrame f = make("System=FREQ,IF=4,SideBand=USB");
        DSBSpecFrame g = (DSBSpecFrame) f.copy();
        g.set("SideBand=LO,IF=-3");
        assertEquals("USB", f.getSideBand());
        assertEquals(4.0, f.getIf());
    }

    // ------------------------------------------------------------------
    // jniast accessors
    // ------------------------------------------------------------------

    @Test
    void jniastAccessors() {
        DSBSpecFrame f = new DSBSpecFrame();
        f.set("System=FREQ,Unit=Hz,StdOfRest=TOPO");
        f.setDsbCentre(2.5E10);
        assertEquals(2.5E10, f.getDsbCentre());
        f.setIf(-3.0);
        assertEquals(-3.0, f.getIf());
        assertEquals("USB", f.getSideBand());
        f.setSideBand("LO");
        assertEquals("LO", f.getSideBand());
        f.setAlignSideBand(true);
        assertTrue(f.getAlignSideBand());
        f.setAlignSideBand(false);
        assertFalse(f.getAlignSideBand());
        assertTrue(f.test("AlignSideBand"));
    }
}
