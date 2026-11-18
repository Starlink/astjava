package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_specflux.rs} (ported from C's {@code
 * ast_tester/testspecflux.c}), of the SpecFluxFrame tests of Rust {@code tests/test_fluxframe.rs}
 * and of the unit tests of Rust {@code frames/specfluxframe.rs}, with further tests of the
 * defaults, conversions, matching and dumps.
 *
 * <p>A conversion between two SpecFluxFrames whose SpecFrames and FluxFrames are not each equal
 * (C's {@code astEqual}) is built by C's {@code MakeSFMapping} with a RateMap, MathMaps and a
 * TranMap ({@code MakeMapF} and {@code MakeMapI}, specfluxframe.c). Each such conversion is
 * checked against the C probe {@code probes/specflux_probe.c}: the class and dump of the
 * simplified Mapping {@code astConvert} gives, and the transformation of a point forwards and of
 * the result back. Equal components convert through a UnitMap, and SpecFluxFrames whose
 * SpecFrames do not convert do not convert at all.
 *
 * <p>The other expected values are C's: testspecflux.c's, and those of a probe built against the
 * pinned C library that makes SpecFluxFrames with {@code astSpecFluxFrame( astSpecFrame( ... ),
 * astFluxFrame( specval, NULL, ... ), "" )} and prints {@code astGetC}, {@code astFormat}, {@code
 * astUnformat}, {@code astDistance}, the class of the simplified Mapping {@code astConvert}, {@code
 * astFindFrame} or {@code astPickAxes} gives with the values it transforms, and {@code astShow}.
 * Where Rust compares SpecFluxFrames for equality the test checks the conversion C's {@code Equal}
 * (frame.c) makes, which must simplify to a UnitMap, or calls {@link AstObject#astEqual}.
 * Rust tests that call {@code match_template} and {@code sub_frame} call the package-private
 * {@link Frame#match} and {@link Frame#subFrame}; Rust's {@code pick_axes_snapshot} is {@link
 * Frame#pickAxes} here, whose classes are C's.
 */
class SpecFluxFrameTest {

    /** The SpecFrame of testspecflux.c, frequency in GHz. */
    private static SpecFrame ghz() {
        SpecFrame sf = new SpecFrame();
        sf.set("system=freq,unit=GHz");
        return sf;
    }

    /** {@code astFluxFrame( specval, specfrm, settings )}. */
    private static FluxFrame flux(double specval, SpecFrame specfrm, String settings) {
        FluxFrame ff = new FluxFrame(specval, specfrm);
        if (!settings.isEmpty()) {
            ff.set(settings);
        }
        return ff;
    }

    /** The probe's SpecFluxFrame: a new SpecFrame with {@code sfSettings} and a FluxFrame. */
    private static SpecFluxFrame sff(String sfSettings, double dsv, String ffSettings) {
        SpecFrame sf = new SpecFrame();
        sf.set(sfSettings);
        return new SpecFluxFrame(sf, flux(dsv, null, ffSettings));
    }

    /** A SpecFluxFrame of a frequency SpecFrame and a FLXDN FluxFrame, all defaults. */
    private static SpecFluxFrame plain() {
        return sff("System=FREQ", AstObject.AST__BAD, "System=FLXDN");
    }

    /** The simplified Mapping {@code astConvert} gives from {@code from} to {@code to}, or null. */
    private static Mapping conversion(Frame from, Frame to) {
        FrameSet fs = from.convert(to, "");
        return fs == null
                ? null
                : fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
    }

    /** One point transformed by {@code map} (a FrameSet's base-to-current Mapping, for a FrameSet). */
    private static double[] tran2(Mapping map, double x, double y, boolean forward) {
        double[][] out = map.tranP(1, 2, new double[][] {{x}, {y}}, forward, 2);
        return new double[] {out[0][0], out[1][0]};
    }

    private static final Map<String, String> PROBE = ProbeOutput.load("specflux_probe");

    /**
     * What the probe prints for the conversion from {@code from} to {@code to}, searching {@code
     * domains}: the class and dump of the simplified Mapping, then the transformation of the point
     * (x, y), or (x) for a one-dimensional {@code from}, forwards by the FrameSet {@code convert}
     * gives, and of the result back.
     */
    private static String converted(Frame from, Frame to, String domains, double x, double y) {
        FrameSet fs = from.convert(to, domains);
        if (fs == null) {
            return "no conversion\n";
        }
        Mapping s = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
        int nin = fs.getNin();
        int nout = fs.getNout();
        double[] in = Arrays.copyOf(new double[] {x, y}, nin);
        double[] out = fs.tranN(1, nin, in, true, nout);
        double[] back = fs.tranN(1, nout, out, false, nin);
        return "class " + s.astClass() + "\n" + ChannelText.dump(s) + "-- transformations\n" + tranLine("fwd", in, out)
                + tranLine("inv", out, back);
    }

    private static String tranLine(String label, double[] in, double[] out) {
        StringBuilder sb = new StringBuilder(label);
        for (double v : in) {
            sb.append(' ').append(CFormat.format("%.17g", v));
        }
        sb.append(" ->");
        for (double v : out) {
            sb.append(' ').append(CFormat.format("%.17g", v));
        }
        return sb.append('\n').toString();
    }

    /** Asserts that the conversion from {@code from} to {@code to} is the probe's case {@code name}. */
    private static void assertConverts(String name, Frame from, Frame to, double x, double y) {
        assertConverts(name, from, to, "", x, y);
    }

    private static void assertConverts(String name, Frame from, Frame to, String domains, double x, double y) {
        String want = PROBE.get(name);
        assertNotNull(want, "no probe case " + name);
        assertEquals(want, converted(from, to, domains, x, y), name);
    }

    // ------------------------------------------------------------------
    // testspecflux.c (test_specflux.rs)
    // ------------------------------------------------------------------

    /** The SpecFluxFrame most of testspecflux.c's checks start from. */
    private static SpecFluxFrame jansky(SpecFrame sf) {
        return new SpecFluxFrame(sf, flux(123.0, sf, "Unit=Jy"));
    }

    /** testspecflux.c Errors 0 to 6 and CheckDump 1 ({@code spec_val_defaults_to_the_flux_frame_value}). */
    @Test
    void specValDefaultsToTheFluxFrameValue() {
        SpecFluxFrame sff = jansky(ghz());
        assertEquals("SpecFluxFrame", sff.getC("Class"), "Error 0");
        assertEquals(123.0, sff.getD("specval"), "Error 1");
        assertFalse(sff.test("specval"), "Error 2");
        sff.setD("specval", 333.3);
        assertEquals(333.3, sff.getD("specval"), "Error 3");
        assertTrue(sff.test("specval"), "Error 4");
        sff.clear("specval");
        assertEquals(123.0, sff.getD("specval"), "Error 5");
        assertFalse(sff.test("specval"), "Error 6");

        AstObject read = ChannelText.read(ChannelText.dump(sff));
        assertInstanceOf(SpecFluxFrame.class, read, "CheckDump 1");
        assertEquals(sff.getD("specval"), read.getD("specval"), "CheckDump 1: Object has changed");
    }

    /**
     * testspecflux.c Errors 6B to 10 ({@code flux_density_converts_to_per_wavelength}): (2, 1)
     * converts to (2, 1.33425638e-26).
     */
    @Test
    void fluxDensityConvertsToPerWavelength() {
        SpecFrame sf = ghz();
        SpecFluxFrame sff = jansky(sf);
        FluxFrame ff2 = flux(123.1, sf, "System=flxdnw");
        assertEquals("W/m^2/Angstrom", ff2.getC("Unit"), "Error 6B");
        assertEquals("FLXDNW", ff2.getC("System"), "Error 6C");
        SpecFluxFrame sff2 = new SpecFluxFrame(sf, ff2);
        assertEquals("SpecFluxFrame", sff2.getC("Class"), "Error 7");
        assertConverts("error8", sff, sff2, 2.0, 1.0);
    }

    /**
     * testspecflux.c Errors 11 to 16 ({@code conversion_follows_permuted_axes}): the flux is on the
     * first result axis, and then on the first axis of both.
     */
    @Test
    void conversionFollowsPermutedAxes() {
        SpecFrame sf = ghz();
        SpecFluxFrame sff = jansky(sf);
        SpecFluxFrame sff2 = new SpecFluxFrame(sf, flux(123.1, sf, "System=flxdnw"));
        sff2.permAxes(new int[] {2, 1});
        assertConverts("error11", sff, sff2, 2.0, 1.0);
        sff.permAxes(new int[] {2, 1});
        assertConverts("error14", sff, sff2, 1.0, 2.0);
    }

    /**
     * testspecflux.c Errors 17 to 22 ({@code logarithmic_flux_units_convert}): (2, 1) converts to
     * (2, -24.8747607) and back.
     */
    @Test
    void logarithmicFluxUnitsConvert() {
        SpecFrame sf = ghz();
        SpecFluxFrame csff = (SpecFluxFrame) jansky(sf).copy();
        FluxFrame ff2 = flux(AstObject.AST__BAD, null, "Unit=log(W/m2/nm)");
        assertEquals("FLXDNW", ff2.getC("System"), "Error 17");
        SpecFluxFrame sff2 = new SpecFluxFrame(sf, ff2);
        assertConverts("error18", csff, sff2, 2.0, 1.0);
    }

    /**
     * testspecflux.c Errors 23 to 29 ({@code surface_brightness_converts}): (2, 1) converts to (2,
     * -21.3585154) and back.
     */
    @Test
    void surfaceBrightnessConverts() {
        FluxFrame ff2 = flux(AstObject.AST__BAD, null, "Unit=log(W/m2/nm/sr)");
        assertEquals("SFCBRW", ff2.getC("System"), "Error 23");
        SpecFluxFrame sff2 = new SpecFluxFrame(ghz(), ff2);
        SpecFrame sf = ghz();
        FluxFrame ff = flux(123.0, sf, "Unit=Jy/deg**2");
        assertEquals("SFCBR", ff.getC("System"), "Error 24");
        SpecFluxFrame sff = new SpecFluxFrame(sf, ff);
        assertConverts("error25", sff, sff2, 2.0, 1.0);
    }

    /**
     * testspecflux.c Errors 30 to 32 ({@code equal_when_and_only_when_the_components_are}): C's
     * {@code Equal} converts the second Frame to the first. SpecFluxFrames sharing equal
     * components, and a copy, convert through a UnitMap ({@code astEqual} 1); with a FLXDNW
     * FluxFrame the conversion is a CmpMap ({@code astEqual} 0).
     */
    @Test
    void equalWhenAndOnlyWhenTheComponentsAre() {
        SpecFrame esf = new SpecFrame();
        esf.set("system=freq");
        FluxFrame eff = flux(AstObject.AST__BAD, null, "system=flxdn");
        FluxFrame eff2 = flux(AstObject.AST__BAD, null, "system=flxdnw");
        SpecFluxFrame ea = new SpecFluxFrame(esf, eff);
        SpecFluxFrame eb = new SpecFluxFrame(esf, eff);
        SpecFluxFrame ec = (SpecFluxFrame) ea.copy();
        SpecFluxFrame ed = new SpecFluxFrame(esf, eff2);
        assertInstanceOf(UnitMap.class, conversion(eb, ea), "Error 30");
        assertInstanceOf(UnitMap.class, conversion(ec, ea), "Error 31");
        String equal =
                (ea.astEqual(eb) ? 1 : 0) + " " + (ea.astEqual(ec) ? 1 : 0) + " " + (ea.astEqual(ed) ? 1 : 0) + "\n";
        assertEquals(PROBE.get("equal"), equal, "Errors 30 to 32");
        assertConverts("error32", ed, ea, 1.4e9, 1.0e-26);
    }

    // ------------------------------------------------------------------
    // test_fluxframe.rs
    // ------------------------------------------------------------------

    /** Rust's {@code test_specfluxframe_new}. */
    @Test
    void newHasTwoAxes() {
        assertEquals(2, plain().getNaxes());
    }

    /** Rust's {@code test_specfluxframe_unformat_base_contract}: each axis is read by its component. */
    @Test
    void unformatBaseContract() {
        SpecFluxFrame s = plain();
        Object[][] cases = {
            {"  12.5  ", 12.5, 8},
            {"<bad>", AstObject.AST__BAD, 5},
            {" < b a d > ", AstObject.AST__BAD, 11},
            {"12.5xyz", 12.5, 4},
            {"xyz", 0.0, 0},
        };
        for (int axis = 1; axis <= 2; axis++) {
            for (Object[] c : cases) {
                double[] v = {0.0};
                assertEquals((int) c[2], s.unformat(axis, (String) c[0], v), axis + " " + c[0]);
                assertEquals((double) c[1], v[0], axis + " " + c[0]);
            }
        }
    }

    /** Rust's {@code test_specfluxframe_components}. */
    @Test
    void components() {
        SpecFrame sf = new SpecFrame();
        sf.set("System=WAVE");
        FluxFrame ff = flux(AstObject.AST__BAD, null, "System=SFCBR");
        SpecFluxFrame s = new SpecFluxFrame(sf, ff);
        Frame[] parts = s.decompose();
        assertSame(sf, parts[0]);
        assertSame(ff, parts[1]);
        assertEquals("WAVE", s.getC("System(1)"));
        assertEquals("SFCBR", s.getC("System(2)"));
    }

    /**
     * Rust's {@code test_specfluxframe_component_mutation}: the SpecFluxFrame holds the Frames it
     * is given, as C's {@code astSpecFluxFrame} clones them, so a change to one is a change to it.
     */
    @Test
    void componentMutation() {
        SpecFrame sf = new SpecFrame();
        sf.set("System=FREQ");
        FluxFrame ff = flux(AstObject.AST__BAD, null, "System=FLXDN");
        SpecFluxFrame s = new SpecFluxFrame(sf, ff);
        ff.setSystem("SFCBR");
        assertEquals("SFCBR", s.getC("System(2)"));
        sf.setSystem("WAVE");
        assertEquals("WAVE", s.getC("System(1)"));
        assertEquals("Surface brightness versus Wavelength", s.getTitle());
    }

    /** A copy holds copies of the components, as C's CmpFrame {@code Copy} makes them. */
    @Test
    void copyIsIndependent() {
        SpecFrame sf = new SpecFrame();
        sf.set("System=FREQ");
        FluxFrame ff = flux(AstObject.AST__BAD, null, "System=FLXDN");
        SpecFluxFrame s = new SpecFluxFrame(sf, ff);
        SpecFluxFrame copy = (SpecFluxFrame) s.copy();
        assertInstanceOf(SpecFrame.class, copy.decompose()[0]);
        assertInstanceOf(FluxFrame.class, copy.decompose()[1]);
        ff.setSystem("SFCBR");
        assertEquals("FLXDN", copy.getC("System(2)"));
        copy.setC("System(1)", "WAVE");
        assertEquals("FREQ", sf.getSystem());
    }

    /** Rust's {@code test_specfluxframe_format_axes}. */
    @Test
    void formatAxes() {
        SpecFluxFrame s = plain();
        assertEquals("1.4E+09", s.format(1, 1.4e9));
        assertEquals("1E-26", s.format(2, 1.0e-26));
        AstException e = assertThrows(AstException.class, () -> s.format(3, 1.0));
        assertEquals(AstException.AST__AXIIN, e.getStatus());
        assertEquals(
                "astFormat(SpecFluxFrame): Axis index (3) invalid - it should be in the range 1 to 2.", e.getMsgText());
    }

    /** Rust's {@code test_specfluxframe_distance}. */
    @Test
    void distance() {
        assertEquals(5.0, plain().distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}));
    }

    /** Rust's {@code test_specfluxframe_equal}: equal components convert through a UnitMap. */
    @Test
    void equalComponentsConvertThroughAUnitMap() {
        assertInstanceOf(UnitMap.class, conversion(plain(), plain()));
    }

    /**
     * Rust's {@code test_specfluxframe_not_equal_different_spec} and {@code
     * test_specfluxframe_convert_different_spec_system_converts}: a frequency SpecFluxFrame
     * converts to a wavelength one through a CmpMap, (1.4e9, 1e-26) to (2.1413747000000005, 1e-26).
     */
    @Test
    void differentSpectralSystemsConvert() {
        SpecFluxFrame wave = sff("System=WAVE", AstObject.AST__BAD, "System=FLXDN");
        assertFalse(plain().astEqual(wave));
        assertConverts("freq_to_wave", plain(), wave, 1.4e9, 1.0e-26);
    }

    /** Rust's {@code test_specfluxframe_convert_same}. */
    @Test
    void convertSame() {
        SpecFluxFrame a = plain();
        Mapping map = conversion(a, plain());
        assertInstanceOf(UnitMap.class, map);
        assertArrayEquals(new double[] {1.4e9, 1.0e-26}, tran2(map, 1.4e9, 1.0e-26, true));
        assertInstanceOf(UnitMap.class, conversion(a, (Frame) a.copy()));
    }

    /** Rust's {@code test_specfluxframe_serialisation_roundtrip}. */
    @Test
    void serialisationRoundTrip() {
        SpecFluxFrame s = plain();
        String text = ChannelText.dump(s);
        assertTrue(text.contains("SpecFluxFrame"));
        assertTrue(text.contains("SpecFrame"));
        assertTrue(text.contains("FluxFrame"));
        SpecFluxFrame back = assertInstanceOf(SpecFluxFrame.class, ChannelText.read(text));
        assertEquals(text, ChannelText.dump(back));
        assertEquals(s, back);
        assertInstanceOf(UnitMap.class, conversion(back, s));
    }

    private static final String DUMP_ATTRS = ChannelText.text(
            " Begin SpecFluxFrame \t# Compound spectral/flux coordinate system description",
            "#   Title = \"Surface brightness (per wavelength) versus Wavelength\" \t# Title of coordinate system",
            "#   Naxes = 2 \t# Number of coordinate axes",
            "#   Domain = \"SPECTRUM-FLUX\" \t# Coordinate system domain",
            "#   Lbl1 = \"Wavelength\" \t# Label for axis 1",
            "#   Lbl2 = \"Surface brightness (per wavelength)\" \t# Label for axis 2",
            "#   Uni1 = \"Angstrom\" \t# Units for axis 1",
            "#   Uni2 = \"W/m^2/Angstrom/arcmin**2\" \t# Units for axis 2",
            " IsA Frame \t# Coordinate system description",
            "    FrameA = \t# First component Frame",
            "       Begin SpecFrame \t# Description of spectral coordinate system",
            "          Naxes = 1 \t# Number of coordinate axes",
            "          System = \"WAVE\" \t# Coordinate system type",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "             End Axis",
            "       IsA Frame \t# Coordinate system description",
            "          RstFrq = 4.5700000000000001e+23 \t# Rest frequency (Hz)",
            "       End SpecFrame",
            "    FrameB = \t# Second component Frame",
            "       Begin FluxFrame \t# Description of flux values",
            "          Naxes = 1 \t# Number of coordinate axes",
            "          System = \"SFCBRW\" \t# Coordinate system type",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "             End Axis",
            "       IsA Frame \t# Coordinate system description",
            "          SpcVl = 457000000000000 \t# Spectral position",
            "       End FluxFrame",
            " IsA CmpFrame \t# Compound coordinate system description",
            " End SpecFluxFrame");

    /**
     * Rust's {@code test_specfluxframe_serialisation_with_attributes}, with the RestFreq set as C's
     * attribute string sets it (in GHz).
     */
    @Test
    void serialisationWithAttributes() {
        SpecFluxFrame s = sff("System=WAVE,RestFreq=4.57e14", AstObject.AST__BAD, "System=SFCBRW,SpecVal=4.57e14");
        assertEquals(DUMP_ATTRS, ChannelText.dump(s));
        SpecFluxFrame back = assertInstanceOf(SpecFluxFrame.class, ChannelText.read(DUMP_ATTRS));
        assertEquals(DUMP_ATTRS, ChannelText.dump(back));
        assertEquals(s, back);
        assertInstanceOf(UnitMap.class, conversion(back, s));
    }

    /**
     * Rust's {@code test_specflux_jy_to_fluxdenw} and {@code test_specflux_inverse}: as
     * testspecflux.c Errors 8 to 10, with the SpecVal set rather than defaulted.
     */
    @Test
    void jyToFluxDenW() {
        SpecFrame sf = ghz();
        FluxFrame ff = flux(AstObject.AST__BAD, sf, "System=FLXDN,Unit=Jy,SpecVal=123.0");
        SpecFluxFrame sff = new SpecFluxFrame(sf, ff);
        FluxFrame ff2 = flux(AstObject.AST__BAD, sf, "System=FLXDNW,SpecVal=123.1");
        SpecFluxFrame sff2 = new SpecFluxFrame(sf, ff2);
        assertConverts("jy_to_fluxdenw", sff, sff2, 2.0, 1.0);
    }

    /** Rust's {@code test_specflux_log_units}: as testspecflux.c Errors 18 to 20. */
    @Test
    void logUnits() {
        SpecFrame sf = ghz();
        SpecFluxFrame sff = new SpecFluxFrame(sf, flux(AstObject.AST__BAD, sf, "System=FLXDN,Unit=Jy,SpecVal=123.0"));
        SpecFluxFrame sff2 = new SpecFluxFrame(sf, flux(AstObject.AST__BAD, sf, "System=FLXDNW,Unit=log(W/m2/nm)"));
        assertConverts("log_units", sff, sff2, 2.0, 1.0);
    }

    /** Rust's {@code test_specflux_surface_brightness}: as testspecflux.c Errors 25 to 27. */
    @Test
    void surfaceBrightnessLogUnits() {
        SpecFrame sf = ghz();
        SpecFluxFrame sff =
                new SpecFluxFrame(sf, flux(AstObject.AST__BAD, sf, "System=SFCBR,Unit=Jy/deg**2,SpecVal=123.0"));
        SpecFluxFrame sff2 = new SpecFluxFrame(sf, flux(AstObject.AST__BAD, sf, "System=SFCBRW,Unit=log(W/m2/nm/sr)"));
        assertConverts("surface_brightness", sff, sff2, 2.0, 1.0);
    }

    // ------------------------------------------------------------------
    // frames/specfluxframe.rs unit tests
    // ------------------------------------------------------------------

    /** Rust's {@code match_template_specflux_self}. */
    @Test
    void matchSelf() {
        FrameFinder.MatchResult m = plain().match(plain(), false);
        assertNotNull(m);
        assertArrayEquals(new int[] {0, 1}, m.templateAxes);
        assertArrayEquals(new int[] {0, 1}, m.targetAxes);
        assertEquals(2, m.result.getNaxes());
        assertInstanceOf(SpecFluxFrame.class, m.result);
        assertInstanceOf(UnitMap.class, m.map.simplify());
    }

    /** Rust's {@code match_template_specflux_against_basicframe_fails}: CmpFrame's Match finds none. */
    @Test
    void matchAgainstAPlainFrameFails() {
        assertNull(plain().match(new Frame(2), false));
        assertNull(new Frame(2).findFrame(plain(), ""));
    }

    /** Rust's {@code match_template_specflux_swap_blocked_when_permute_false}. */
    @Test
    void swapBlockedWhenPermuteFalse() {
        SpecFluxFrame template = plain();
        template.setPermute(false);
        SpecFluxFrame target = plain();
        target.permAxes(new int[] {2, 1});
        assertNull(template.match(target, false));
        assertNull(target.findFrame(template, ""));
    }

    /**
     * Rust's {@code match_template_specflux_swap_allowed_when_permute_true}: the result takes the
     * template's axis order, so C's Mapping is a PermMap and (1.4, 1e-26) becomes (1e-26, 1.4).
     */
    @Test
    void swapAllowedWhenPermuteTrue() {
        SpecFluxFrame template = plain();
        template.setPermute(true);
        SpecFluxFrame target = plain();
        target.permAxes(new int[] {2, 1});
        FrameFinder.MatchResult m = template.match(target, false);
        assertNotNull(m);
        assertArrayEquals(new int[] {0, 1}, m.templateAxes);
        assertArrayEquals(new int[] {1, 0}, m.targetAxes);
        FrameSet fs = target.findFrame(template, "");
        assertNotNull(fs);
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(SpecFluxFrame.class, cur);
        assertInstanceOf(
                PermMap.class,
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify());
        assertArrayEquals(new double[] {1.0e-26, 1.4}, tran2(fs, 1.4, 1.0e-26, true));
        assertEquals("Frequency", cur.getLabel(1));
    }

    /** With PreserveAxes set, the result keeps the target's axis order and the Mapping is a UnitMap. */
    @Test
    void preserveAxesKeepsTheTargetOrder() {
        SpecFluxFrame template = plain();
        template.setPreserveAxes(true);
        SpecFluxFrame target = plain();
        target.permAxes(new int[] {2, 1});
        FrameSet fs = target.findFrame(template, "");
        assertNotNull(fs);
        Frame cur = fs.getFrame(FrameSet.AST__CURRENT);
        assertInstanceOf(SpecFluxFrame.class, cur);
        assertEquals("Flux density", cur.getLabel(1));
        assertInstanceOf(
                UnitMap.class,
                fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify());
        assertArrayEquals(new double[] {1.4, 1.0e-26}, tran2(fs, 1.4, 1.0e-26, true));
    }

    /** Rust's {@code sub_frame_specflux_to_specflux_identity}. */
    @Test
    void subFrameIdentity() {
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(plain().subFrame(plain(), 2, new int[] {0, 1}, new int[] {0, 1}, map, result));
        assertEquals(2, map[0].getNin());
        assertEquals(2, map[0].getNout());
        SpecFluxFrame r = assertInstanceOf(SpecFluxFrame.class, result[0]);
        assertArrayEquals(new int[] {0, 1}, r.getPerm());
    }

    /** Rust's {@code sub_frame_specflux_axis_swap}. */
    @Test
    void subFrameAxisSwap() {
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(plain().subFrame(plain(), 2, new int[] {1, 0}, new int[] {0, 1}, map, result));
        SpecFluxFrame r = assertInstanceOf(SpecFluxFrame.class, result[0]);
        assertArrayEquals(new int[] {1, 0}, r.getPerm());
        assertInstanceOf(PermMap.class, map[0].simplify());
    }

    /**
     * Rust's {@code pick_axes_snapshot_full_returns_specflux} and {@code
     * pick_axes_snapshot_partial_returns_basicframe}: C's {@code astPickAxes} picks through
     * CmpFrame's {@code SubFrame}, giving a CmpFrame for both axes, in either order, and the
     * component's class for one.
     */
    @Test
    void pickAxesGivesCsClasses() {
        SpecFluxFrame s = sff("System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN");
        Mapping[] map = new Mapping[1];
        Frame f = s.pickAxes(2, new int[] {1, 2}, map);
        assertEquals("CmpFrame", f.astClass());
        assertInstanceOf(UnitMap.class, map[0].simplify());
        f = s.pickAxes(2, new int[] {2, 1}, map);
        assertEquals("CmpFrame", f.astClass());
        assertInstanceOf(PermMap.class, map[0].simplify());
        f = s.pickAxes(1, new int[] {1}, map);
        assertEquals("SpecFrame", f.astClass());
        assertInstanceOf(PermMap.class, map[0].simplify());
    }

    // ------------------------------------------------------------------
    // Defaults, Title and dumps
    // ------------------------------------------------------------------

    private static final String DUMP_DEFAULT = ChannelText.text(
            " Begin SpecFluxFrame \t# Compound spectral/flux coordinate system description",
            "#   Title = \"Flux density versus Frequency\" \t# Title of coordinate system",
            "#   Naxes = 2 \t# Number of coordinate axes",
            "#   Domain = \"SPECTRUM-FLUX\" \t# Coordinate system domain",
            "#   Lbl1 = \"Frequency\" \t# Label for axis 1",
            "#   Lbl2 = \"Flux density\" \t# Label for axis 2",
            "#   Uni1 = \"GHz\" \t# Units for axis 1",
            "#   Uni2 = \"W/m^2/Hz\" \t# Units for axis 2",
            " IsA Frame \t# Coordinate system description",
            "    FrameA = \t# First component Frame",
            "       Begin SpecFrame \t# Description of spectral coordinate system",
            "          Naxes = 1 \t# Number of coordinate axes",
            "          System = \"FREQ\" \t# Coordinate system type",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "                Unit = \"GHz\" \t# Axis units",
            "             End Axis",
            "       IsA Frame \t# Coordinate system description",
            "          UFreq = \"GHz\" \t# Preferred units for frequency",
            "       End SpecFrame",
            "    FrameB = \t# Second component Frame",
            "       Begin FluxFrame \t# Description of flux values",
            "          Naxes = 1 \t# Number of coordinate axes",
            "          System = \"FLXDN\" \t# Coordinate system type",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "             End Axis",
            "       IsA Frame \t# Coordinate system description",
            "          DfSpc = 1400000000 \t# Default spectral position",
            "       End FluxFrame",
            " IsA CmpFrame \t# Compound coordinate system description",
            " End SpecFluxFrame");

    /** The defaults C gives, and C's dump, which reads back to the same SpecFluxFrame. */
    @Test
    void defaultsAndDump() {
        SpecFluxFrame s = sff("System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN");
        assertEquals("Flux density versus Frequency", s.getTitle());
        assertEquals("SPECTRUM-FLUX", s.getDomain());
        assertEquals("Compound", s.getC("System"));
        assertEquals("FREQ", s.getC("System(1)"));
        assertEquals("FLXDN", s.getC("System(2)"));
        assertEquals("Frequency", s.getC("Label(1)"));
        assertEquals("Flux density", s.getC("Label(2)"));
        assertEquals("GHz", s.getC("Unit(1)"));
        assertEquals("W/m^2/Hz", s.getC("Unit(2)"));
        assertEquals("1400000000", s.getC("SpecVal"));
        assertEquals(DUMP_DEFAULT, ChannelText.dump(s));
        SpecFluxFrame back = assertInstanceOf(SpecFluxFrame.class, ChannelText.read(DUMP_DEFAULT));
        assertEquals(DUMP_DEFAULT, ChannelText.dump(back));
        assertEquals(s, back);
    }

    /**
     * The default Title is the FluxFrame's Label, its first letter made upper case, then {@code
     * versus} and the SpecFrame's Label, whatever the axis order (specfluxframe.c {@code GetTitle}).
     */
    @Test
    void titleFromTheLabels() {
        SpecFluxFrame s =
                sff("System=WAVE,Label=wavelength in vacuo", AstObject.AST__BAD, "System=SFCBRW,Label=brightness");
        assertEquals("Brightness versus wavelength in vacuo", s.getTitle());
        s.setTitle("My title");
        assertEquals("My title", s.getTitle());
        s.clear("Title");
        s.permAxes(new int[] {2, 1});
        assertEquals("Brightness versus wavelength in vacuo", s.getTitle());
    }

    /** A dump whose FrameA is not a SpecFrame, or whose FrameB is not a FluxFrame, is refused. */
    @Test
    void loaderRefusesOtherComponentClasses() {
        String text = DUMP_DEFAULT
                .replace("    FrameA = \t# First component Frame", "    FrameX = \t# First")
                .replace("    FrameB = \t# Second component Frame", "    FrameA = \t# First component Frame")
                .replace("    FrameX = \t# First", "    FrameB = \t# Second component Frame");
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__LDERR, e.getStatus());
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /**
     * Equal components in a different axis order convert through a PermMap (specfluxframe.c
     * {@code SubFrame} around {@code MakeSFMapping}'s UnitMap), and through a UnitMap when both are
     * swapped.
     */
    @Test
    void swappedEqualComponents() {
        SpecFluxFrame s = plain();
        SpecFluxFrame u = (SpecFluxFrame) plain().copy();
        u.permAxes(new int[] {2, 1});
        Mapping map = conversion(s, u);
        assertInstanceOf(PermMap.class, map);
        assertArrayEquals(new double[] {1.0e-26, 1.4e9}, tran2(map, 1.4e9, 1.0e-26, true));
        map = conversion(u, s);
        assertInstanceOf(PermMap.class, map);
        assertArrayEquals(new double[] {1.4e9, 1.0e-26}, tran2(map, 1.0e-26, 1.4e9, true));
        map = conversion(u, (Frame) u.copy());
        assertInstanceOf(UnitMap.class, map);
    }

    /**
     * SpecFluxFrames whose SpecFrames have different Domains do not convert: C's {@code MakeMapF}
     * finds no conversion between the standardised SpecFrames.
     */
    @Test
    void spectralDomainsThatDifferDoNotConvert() {
        SpecFluxFrame s = sff("System=FREQ,Domain=A", AstObject.AST__BAD, "System=FLXDN");
        SpecFluxFrame t = sff("System=FREQ,Domain=B", AstObject.AST__BAD, "System=FLXDN");
        assertNull(s.convert(t, ""));
        assertEquals("A-FLUX", s.getDomain());
        assertEquals("B-FLUX", t.getDomain());
    }

    /**
     * Every other change goes through C's {@code MakeMapF} and {@code MakeMapI}, at one spectral
     * system: Jy to mJy gives (2, 1000.0000000000001), GHz to MHz (1999.9999999999998, 1), and
     * FluxFrames in different Domains (1.4e9, 1e-26), each through a CmpMap.
     */
    @Test
    void otherChangesConvert() {
        SpecFluxFrame s = sff("System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=Jy");
        assertConverts("jy_to_mjy", s, sff("System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=mJy"), 2.0, 1.0);
        assertConverts("ghz_to_mhz", s, sff("System=FREQ,Unit=MHz", 1.4e9, "System=FLXDN,Unit=Jy"), 2.0, 1.0);
        assertConverts(
                "flux_domains",
                sff("System=FREQ", AstObject.AST__BAD, "System=FLXDN,Domain=A"),
                sff("System=FREQ", AstObject.AST__BAD, "System=FLXDN,Domain=B"),
                1.4e9,
                1.0e-26);
    }

    /** Flux values in logarithmic units: (2, 1) Jy converts to (2, 0) {@code log(Jy)}, and back. */
    @Test
    void logarithmicUnitsConvert() {
        SpecFluxFrame s = sff("System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=Jy");
        SpecFluxFrame t = sff("System=FREQ,Unit=GHz", 1.4e9, "System=FLXDN,Unit=log(Jy)");
        assertConverts("jy_to_log_jy", s, t, 2.0, 1.0);
        assertConverts("log_jy_to_jy", t, s, 2.0, 0.0);
    }

    /** A spectrum as SPLAT holds it: a FrameSet of a GRID Frame and a SpecFluxFrame. */
    private static FrameSet spectrum(SpecFluxFrame s) {
        Frame grid = new Frame(2);
        grid.setDomain("GRID");
        FrameSet fs = new FrameSet(grid);
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(2), s);
        return fs;
    }

    /**
     * Spectra whose SpecFluxFrames differ in both components convert in both directions, between
     * frequency in GHz with flux density in Jy, wavelength in nm with flux density in mJy, radio
     * velocity with flux density in W/m^2/Hz, and wavelength in nm with flux density per unit
     * wavelength in W/m^2/nm, searching the SPECTRUM-FLUX Domain (the GRID Frames would otherwise
     * match first).
     */
    @Test
    void spectraConvertBothWays() {
        String settings = ",RestFreq=1.4GHz";
        FrameSet a = spectrum(sff("System=FREQ,Unit=GHz" + settings, AstObject.AST__BAD, "System=FLXDN,Unit=Jy"));
        FrameSet b = spectrum(sff("System=WAVE,Unit=nm" + settings, AstObject.AST__BAD, "System=FLXDN,Unit=mJy"));
        FrameSet c = spectrum(sff("System=VRAD" + settings, AstObject.AST__BAD, "System=FLXDN,Unit=W/m^2/Hz"));
        FrameSet d = spectrum(sff("System=WAVE,Unit=nm" + settings, AstObject.AST__BAD, "System=FLXDNW,Unit=W/m^2/nm"));
        String domain = "SPECTRUM-FLUX";
        assertConverts("splat_freq_jy_to_wave_mjy", a, b, domain, 1.3, 2.0);
        assertConverts("splat_wave_mjy_to_freq_jy", b, a, domain, 2.3e8, 2000.0);
        assertConverts("splat_freq_jy_to_vrad", a, c, domain, 1.3, 2.0);
        assertConverts("splat_vrad_to_freq_jy", c, a, domain, 1500.0, 2.0e-26);
        assertConverts("splat_freq_jy_to_wave_flxdnw", a, d, domain, 1.3, 2.0);
        assertConverts("splat_wave_flxdnw_to_freq_jy", d, a, domain, 2.3e8, 1.0e-30);
    }

    /** jniast's constructor. */
    @Test
    void jniastConstructor() {
        SpecFrame sf = new SpecFrame();
        FluxFrame ff = new FluxFrame();
        SpecFluxFrame s = new SpecFluxFrame(sf, ff);
        assertEquals("SpecFluxFrame", s.getC("Class"));
        CmpFrame cmp = s;
        assertEquals(2, cmp.getNaxes());
    }

    // ------------------------------------------------------------------
    // Casting between CmpFrames and SpecFluxFrames (cmpframe.c Cast)
    // ------------------------------------------------------------------

    /**
     * cmpframe.c {@code Cast} makes a copy of a CmpFrame as an instance of any class of the
     * CmpFrame family ({@code astCastCopy}): a SpecFluxFrame cast into a CmpFrame is a plain
     * CmpFrame holding copies of its components, and a CmpFrame cast into a SpecFluxFrame is a
     * SpecFluxFrame holding copies of the CmpFrame's, whatever their classes.
     */
    @Test
    void castWithinTheCmpFrameFamilyCopiesTheComponents() {
        SpecFrame sf = new SpecFrame();
        FluxFrame ff = new FluxFrame();
        SpecFluxFrame s = new SpecFluxFrame(sf, ff);
        s.setTitle("A title");
        Frame asCmp = s.cast(new CmpFrame(new Frame(1), new Frame(1)));
        assertEquals(CmpFrame.class, asCmp.getClass());
        assertEquals("A title", asCmp.getTitle());
        Frame[] parts = ((CmpFrame) asCmp).decompose();
        assertInstanceOf(SpecFrame.class, parts[0]);
        assertInstanceOf(FluxFrame.class, parts[1]);
        assertNotSame(sf, parts[0]);
        assertNotSame(ff, parts[1]);

        Frame a = new Frame(1);
        Frame b = new Frame(1);
        CmpFrame cmp = new CmpFrame(a, b);
        cmp.permAxes(new int[] {2, 1});
        Frame asSff = cmp.cast(s);
        assertEquals(SpecFluxFrame.class, asSff.getClass());
        Frame[] sffParts = ((CmpFrame) asSff).decompose();
        assertEquals(Frame.class, sffParts[0].getClass());
        assertNotSame(a, sffParts[0]);
        assertNotSame(b, sffParts[1]);
        assertEquals(2, asSff.validateAxis(1, true, "test"));
    }

    /**
     * A SpecFluxFrame and a CmpFrame that do not match: C casts the SpecFluxFrame template into a
     * plain CmpFrame, which matches no better, and finds no conversion either way.
     */
    @Test
    void unmatchedCmpFramesDoNotConvert() {
        SpecFluxFrame s = new SpecFluxFrame(new SpecFrame(), new FluxFrame());
        CmpFrame cube = new CmpFrame(new SkyFrame(), new SpecFrame());
        assertNull(cube.convert(s, ""), "cube to SpecFluxFrame");
        assertNull(s.convert(cube, ""), "SpecFluxFrame to cube");
        CmpFrame sky1 = new CmpFrame(new SkyFrame(), new Frame(1));
        assertNull(s.convert(sky1, ""), "SpecFluxFrame to sky1");
        assertNull(sky1.convert(s, ""), "sky1 to SpecFluxFrame");
        FrameSet fs = new FrameSet(new Frame(3));
        fs.addFrame(FrameSet.AST__BASE, new UnitMap(3), cube);
        assertNull(fs.findFrame(s, ""), "findFrame in a cube");
    }

    /**
     * A FluxFrame whose System differs from a SpecFluxFrame's: the CmpFrame C's {@code PartMatch}
     * gives is cast into a SpecFluxFrame, whose conversion {@code MakeSFMapping} builds. It
     * converts both ways; from the SpecFluxFrame, (5000, 2) gives 239833965686.78632, and from the
     * FluxFrame, whose spectral position is unknown, bad values.
     */
    @Test
    void fluxFrameAndSpecFluxFrameConvert() {
        FluxFrame fw = flux(AstObject.AST__BAD, new SpecFrame(), "System=FLXDNW");
        SpecFluxFrame s = new SpecFluxFrame(new SpecFrame(), flux(AstObject.AST__BAD, new SpecFrame(), ""));
        assertConverts("fluxframe_to_specfluxframe", fw, s, 2.0, 0.0);
        assertConverts("specfluxframe_to_fluxframe", s, fw, 5000.0, 2.0);
    }

    /**
     * A SpecFrame in another Domain and a SpecFluxFrame whose SpecFrame has SpecOrigin 0: the cast
     * reaches {@code MakeSFMapping}, whose standardised SpecFrame ({@code GetSpecFrame}) cannot
     * re-express the origin in frequency, and C reports AST__ATSER both ways. With SpecOrigin
     * unset C finds no conversion.
     */
    @Test
    void specFrameToSpecFluxFrameReachesMakeSFMapping() {
        SpecFrame foo = new SpecFrame();
        foo.setDomain("FOO");
        SpecFrame origin = new SpecFrame();
        origin.set("SpecOrigin=0");
        SpecFluxFrame s = new SpecFluxFrame(origin, flux(AstObject.AST__BAD, new SpecFrame(), ""));
        AstException e = assertThrows(AstException.class, () -> foo.convert(s, ""));
        assertEquals(AstException.AST__ATSER, e.getStatus());
        e = assertThrows(AstException.class, () -> s.convert(foo, ""));
        assertEquals(AstException.AST__ATSER, e.getStatus());

        SpecFluxFrame plain = new SpecFluxFrame(new SpecFrame(), flux(AstObject.AST__BAD, new SpecFrame(), ""));
        assertNull(foo.convert(plain, ""));
        assertNull(plain.convert(foo, ""));
    }
}
