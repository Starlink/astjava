package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of the FluxFrame tests of Rust {@code tests/test_fluxframe.rs}, of Rust {@code
 * tests/test_flux.rs} (ported from C's {@code ast_tester/testflux.c}), of the unit tests of Rust
 * {@code frames/fluxframe.rs}, and of {@code test_class_attrs.rs::flux_frame_domain_is_cleaned}
 * and {@code test_frame_api.rs::density_follows_the_c_library}, with further tests of the
 * attributes, the System a Unit implies, conversions and dumps.
 *
 * <p>Rust makes a FluxFrame with a System argument, leaving FLXDN unset; here a FluxFrame is made
 * with {@code astFluxFrame}'s arguments, a default SpecVal and a SpecFrame, and the System and
 * other attributes set as C's options string sets them. The expected values are C's, from a probe
 * built against the pinned C library that makes the same FluxFrames with {@code astFluxFrame( specval,
 * specfrm, options )} and prints {@code astGetC}, {@code astFormat}, {@code astUnformat}, the
 * values {@code astConvert} gives with the class of the simplified Mapping, and {@code astShow}.
 * Rust tests that call {@code make_flux_mapping} call the package-private {@link
 * FluxFrame#makeFluxMapping}; the SpecFrame that Rust's {@code set_spec_frame} attaches is given to
 * the constructor.
 *
 * <p>{@code test_fluxframe_equal_same}, {@code test_fluxframe_equal_different_system} and {@code
 * test_fluxframe_equal_different_specval_is_equal} call {@link AstObject#astEqual}. The
 * SpecFluxFrame tests of {@code test_fluxframe.rs} are in {@link SpecFluxFrameTest}, and {@code
 * system_from_unit_matches_the_c_library} is in {@link FluxUnitSystemOracleTest}.
 */
class FluxFrameTest {

    /** A FluxFrame with default SpecVal {@code dsv}, no SpecFrame and the settings applied. */
    private static FluxFrame flux(double dsv, String settings) {
        return flux(dsv, null, settings);
    }

    /**
     * A FluxFrame with default SpecVal {@code dsv}, a SpecFrame made from {@code sfSettings} (none
     * if null) and the settings applied, as the probe's {@code astFluxFrame} calls make them.
     */
    private static FluxFrame flux(double dsv, String sfSettings, String settings) {
        SpecFrame sf = null;
        if (sfSettings != null) {
            sf = new SpecFrame();
            sf.set(sfSettings);
        }
        FluxFrame f = new FluxFrame(dsv, sf);
        if (!settings.isEmpty()) {
            f.set(settings);
        }
        return f;
    }

    /** The Mapping {@code astConvert} gives from {@code from} to {@code to}, simplified, or null. */
    private static Mapping conversion(Frame from, Frame to) {
        FrameSet fs = from.convert(to, "");
        return fs == null
                ? null
                : fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
    }

    private static double tran(Mapping map, double x) {
        return map.tran1(1, new double[] {x}, true)[0];
    }

    private static double zoom(Mapping map) {
        return assertInstanceOf(ZoomMap.class, map).getZoom();
    }

    // ------------------------------------------------------------------
    // Construction and Systems (test_fluxframe.rs)
    // ------------------------------------------------------------------

    @Test
    void newFluxden() {
        FluxFrame ff = new FluxFrame();
        assertEquals(1, ff.getNaxes());
        assertEquals("FLXDN", ff.getSystem());
        assertFalse(ff.testSystem());
    }

    @Test
    void newFluxdenw() {
        FluxFrame ff = flux(AstObject.AST__BAD, "System=FLXDNW");
        assertEquals(1, ff.getNaxes());
        assertEquals("FLXDNW", ff.getSystem());
        assertTrue(ff.testSystem());
    }

    @Test
    void newSbright() {
        FluxFrame ff = flux(AstObject.AST__BAD, "System=SFCBR");
        assertEquals(1, ff.getNaxes());
        assertEquals("SFCBR", ff.getSystem());
    }

    @Test
    void newSbrightw() {
        FluxFrame ff = flux(AstObject.AST__BAD, "System=SFCBRW");
        assertEquals(1, ff.getNaxes());
        assertEquals("SFCBRW", ff.getSystem());
    }

    /** Rust's {@code test_flux_system_parse}, as System settings. */
    @Test
    void systemNames() {
        assertEquals("FLXDN", flux(AstObject.AST__BAD, "System=FLXDN").getSystem());
        assertEquals("FLXDN", flux(AstObject.AST__BAD, "System=flxdn").getSystem());
        assertEquals("FLXDNW", flux(AstObject.AST__BAD, "System=FLXDNW").getSystem());
        assertEquals("SFCBR", flux(AstObject.AST__BAD, "System=SFCBR").getSystem());
        assertEquals("SFCBRW", flux(AstObject.AST__BAD, "System=SFCBRW").getSystem());
        assertEquals("SFCBRW", flux(AstObject.AST__BAD, "System=SRCBR").getSystem());
        AstException e = assertThrows(AstException.class, () -> flux(AstObject.AST__BAD, "System=NONSENSE"));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    /**
     * Rust's {@code flux_system_parse_matches_c_names_only}: C's {@code SystemCode} matches with
     * {@code astChrMatch}, which ignores case and trailing white space but not leading white
     * space, and knows no long names. A setting skips the leading white space before the name.
     */
    @Test
    void systemNamesMatchTheCNamesOnly() {
        FluxFrame ff = new FluxFrame();
        assertEquals("SFCBR", flux(AstObject.AST__BAD, "System=sfcbr ").getSystem());
        assertEquals(Frame.AST__BADSYSTEM, ff.systemCode(" SFCBR"));
        for (String name : new String[] {"FLUXDEN", "FLUXDENW", "SBRIGHT", "SBRIGHTW", "Cartesian"}) {
            AstException e = assertThrows(AstException.class, () -> ff.setSystem(name));
            assertEquals(AstException.AST__ATTIN, e.getStatus());
            assertEquals("astSetAttrib(FluxFrame): Invalid System description \"" + name + "\".", e.getMsgText());
        }
        assertFalse(ff.testSystem());
    }

    /** Rust's {@code test_flux_system_properties}: each System's density system and default units. */
    @Test
    void systemProperties() {
        String[][] want = {
            {"FLXDN", "W/m^2/Hz", "Hz"},
            {"FLXDNW", "W/m^2/Angstrom", "Angstrom"},
            {"SFCBR", "W/m^2/Hz/arcmin**2", "Hz"},
            {"SFCBRW", "W/m^2/Angstrom/arcmin**2", "Angstrom"},
        };
        for (String[] w : want) {
            FluxFrame ff = flux(AstObject.AST__BAD, "System=" + w[0]);
            assertEquals(w[1], ff.getUnit(1), w[0]);
            assertFalse(ff.testUnit(1), w[0]);
            assertEquals(w[2], ff.getDensityUnit(), w[0]);
            assertEquals("Hz".equals(w[2]) ? SpecFrame.AST__FREQ : SpecFrame.AST__WAVELEN, ff.getDensitySystem(), w[0]);
        }
    }

    /** The defaults of each System: Label, Symbol and Title. */
    @Test
    void systemDefaults() {
        FluxFrame ff = new FluxFrame();
        assertEquals("Flux density", ff.getLabel(1));
        assertEquals("Flux density", ff.getC("Label"));
        assertEquals("S_nu", ff.getSymbol(1));
        assertEquals("Flux density", ff.getTitle());
        assertEquals("FLUX", ff.getDomain());
        assertEquals("FLXDN", ff.getAlignSystem());
        assertTrue(ff.getActiveUnit());
        assertFalse(ff.testActiveUnit());
        FluxFrame w = flux(AstObject.AST__BAD, "System=FLXDNW");
        assertEquals("Flux wavelength density", w.getLabel(1));
        assertEquals("S_lambda", w.getSymbol(1));
        assertEquals("Flux wavelength density", w.getTitle());
        FluxFrame sb = flux(AstObject.AST__BAD, "System=SFCBR");
        assertEquals("Surface brightness", sb.getLabel(1));
        assertEquals("mu_nu", sb.getSymbol(1));
        FluxFrame sbw = flux(AstObject.AST__BAD, "System=SFCBRW");
        assertEquals("Surface brightness (per wavelength)", sbw.getLabel(1));
        assertEquals("mu_lambda", sbw.getSymbol(1));
        FluxFrame al = flux(AstObject.AST__BAD, "AlignSystem=SFCBR");
        assertEquals("SFCBR", al.getAlignSystem());
        assertTrue(al.testAttrib("alignsystem"));
    }

    // ------------------------------------------------------------------
    // SpecVal
    // ------------------------------------------------------------------

    /** Rust's {@code test_fluxframe_spec_val}. */
    @Test
    void specVal() {
        FluxFrame ff = new FluxFrame();
        assertEquals("<bad>", ff.getC("SpecVal"));
        AstException e = assertThrows(AstException.class, ff::getSpecVal);
        assertEquals(AstException.AST__ATGER, e.getStatus());
        ff.setSpecVal(1.4e9);
        assertEquals(1.4e9, ff.getSpecVal());
        assertEquals("1400000000", ff.getC("SpecVal"));
        assertTrue(ff.test("SpecVal"));
        ff.clear("SpecVal");
        assertEquals("<bad>", ff.getC("SpecVal"));
        assertFalse(ff.test("SpecVal"));
    }

    /** A SpecVal that is not a number is not a SpecVal setting. */
    @Test
    void specValSettingMustBeANumber() {
        FluxFrame ff = new FluxFrame();
        AstException e = assertThrows(AstException.class, () -> ff.set("SpecVal=fred"));
        assertEquals(AstException.AST__BADAT, e.getStatus());
        assertEquals("astSet: The attribute setting \"specval=fred\" is invalid for a FluxFrame.", e.getMsgText());
    }

    /** The Title gives the SpecVal in the units of its SpecFrame. */
    @Test
    void titleGivesTheSpecVal() {
        assertEquals(
                "Flux density at = 1.4E+09 Hz",
                flux(AstObject.AST__BAD, "SpecVal=1.4e9").getTitle());
        assertEquals(
                "Flux density at = 123 GHz",
                flux(123.0, "System=FREQ,Unit=GHz", "").getTitle());
        assertEquals(
                "Flux wavelength density at = 5000 Angstrom",
                flux(5000.0, "System=FLXDNW").getTitle());
        assertEquals("1400000000", flux(1.4e9, "").getC("SpecVal"));
    }

    /** Rust's {@code test_flux.rs::spec_val_defaults_to_the_constructor_value} (testflux.c Errors 1 to 6, CheckDump 1). */
    @Test
    void specValDefaultsToTheConstructorValue() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "");
        assertEquals(123.0, ff.getSpecVal(), "Error 1");
        assertFalse(ff.test("SpecVal"), "Error 2");
        ff.setSpecVal(333.3);
        assertEquals(333.3, ff.getSpecVal(), "Error 3");
        assertTrue(ff.test("SpecVal"), "Error 4");
        ff.clear("SpecVal");
        assertEquals(123.0, ff.getSpecVal(), "Error 5");
        assertFalse(ff.test("SpecVal"), "Error 6");
        FluxFrame read = assertInstanceOf(FluxFrame.class, ChannelText.read(ChannelText.dump(ff)));
        assertEquals(ff.getSpecVal(), read.getSpecVal(), "CheckDump 1");
    }

    // ------------------------------------------------------------------
    // Format, Unformat and Distance
    // ------------------------------------------------------------------

    /** Rust's {@code test_fluxframe_format}: the Frame's {@code %1.7G}, with no units. */
    @Test
    void format() {
        FluxFrame ff = new FluxFrame();
        assertEquals("1.5", ff.format(1, 1.5));
        assertEquals("0.00123", ff.format(1, 1.23e-3));
        assertEquals("1E-26", ff.format(1, 1.0e-26));
        assertEquals("1.4E+09", ff.format(1, 1.4e9));
        assertEquals("<bad>", ff.format(1, AstObject.AST__BAD));
    }

    /** Rust's {@code test_fluxframe_unformat}. */
    @Test
    void unformat() {
        double[] v = new double[1];
        assertEquals(7, new FluxFrame().unformat(1, "1.5e-3 Jy", v));
        assertEquals(0.0015, v[0]);
    }

    /** Rust's {@code test_fluxframe_unformat_base_contract}. */
    @Test
    void unformatBaseContract() {
        FluxFrame ff = new FluxFrame();
        Object[][] cases = {
            {"12.5", 12.5, 4},
            {"  12.5  ", 12.5, 8},
            {"12.5xyz", 12.5, 4},
            {"<bad>", AstObject.AST__BAD, 5},
            {" < b a d > ", AstObject.AST__BAD, 11},
            {"xyz", null, 0},
            {"", null, 0},
        };
        for (Object[] c : cases) {
            double[] v = {0.0};
            assertEquals((int) c[2], ff.unformat(1, (String) c[0], v), (String) c[0]);
            if (c[1] != null) {
                assertEquals((double) c[1], v[0], (String) c[0]);
            }
        }
    }

    /** Rust's {@code test_fluxframe_distance}. */
    @Test
    void distance() {
        assertEquals(2.0, new FluxFrame().distance(new double[] {1.0}, new double[] {3.0}));
    }

    // ------------------------------------------------------------------
    // Units and the System they imply
    // ------------------------------------------------------------------

    /** Rust's {@code test_fluxframe_unit_accessors}. */
    @Test
    void unitAccessors() {
        FluxFrame ff = new FluxFrame();
        assertFalse(ff.testUnit(1));
        assertEquals("W/m^2/Hz", ff.getUnit(1));
        ff.setUnit(1, "Jy");
        assertTrue(ff.testUnit(1));
        assertEquals("Jy", ff.getUnit(1));
        ff.clearUnit(1);
        assertFalse(ff.testUnit(1));
        assertEquals("W/m^2/Hz", ff.getUnit(1));
    }

    /** Rust's {@code test_fluxframe_system_derived_from_unit}. */
    @Test
    void systemDerivedFromUnit() {
        FluxFrame ff = new FluxFrame();
        ff.setUnit(1, "Jy");
        assertEquals("FLXDN", ff.getSystem());
        assertFalse(ff.testSystem());
        FluxFrame w = new FluxFrame();
        w.setUnit(1, "W/m^2/Angstrom");
        assertEquals("FLXDNW", w.getSystem());
        assertFalse(w.testSystem());
    }

    /** A Unit that is a function of the default units rewrites the default Label and Symbol. */
    @Test
    void logarithmicUnit() {
        FluxFrame ff = flux(AstObject.AST__BAD, "Unit=log(Jy)");
        assertEquals("FLXDN", ff.getSystem());
        assertEquals("log( Flux density )", ff.getLabel(1));
        assertEquals("log( S_nu )", ff.getSymbol(1));
    }

    /** The Unit used with each System comes back with the System; a change of System clears the Label and Title. */
    @Test
    void unitsFollowTheSystem() {
        assertEquals(
                "W/m^2/Angstrom",
                flux(AstObject.AST__BAD, "Unit=Jy,System=FLXDNW").getUnit(1));
        FluxFrame ff = flux(AstObject.AST__BAD, "Unit=Jy,System=FLXDNW,Unit=W/m^2/nm,System=FLXDN");
        assertEquals("Jy", ff.getUnit(1));
        assertTrue(ff.testUnit(1));
        ff.setSystem("FLXDNW");
        assertEquals("W/m^2/nm", ff.getUnit(1));
        assertEquals("Mine", flux(AstObject.AST__BAD, "Label(1)=Mine,Unit=Jy").getLabel(1));
        assertEquals(
                "Surface brightness",
                flux(AstObject.AST__BAD, "Label(1)=Mine,System=SFCBR").getLabel(1));
        assertEquals(
                "Surface brightness",
                flux(AstObject.AST__BAD, "Title=Mine,System=SFCBR").getTitle());
        assertEquals("Jy", flux(AstObject.AST__BAD, "Unit=Jy  ").getUnit(1));
    }

    /** A Unit that does not suit a set System clears the System, which then follows from the Unit. */
    @Test
    void unitClearsASystemItDoesNotSuit() {
        FluxFrame sb = flux(AstObject.AST__BAD, "System=SFCBR,Unit=Jy/arcsec**2");
        assertEquals("Jy/arcsec**2", sb.getUnit(1));
        assertTrue(sb.testSystem());
        FluxFrame ff = flux(AstObject.AST__BAD, "System=FLXDNW,Unit=Jy");
        assertEquals("FLXDN", ff.getSystem());
        assertFalse(ff.testSystem());
        assertEquals("Jy", ff.getUnit(1));
    }

    /** Clearing the System, and clearing the Unit, which forgets the Unit used with the System. */
    @Test
    void clearSystemAndUnit() {
        FluxFrame sb = flux(AstObject.AST__BAD, "System=SFCBR,Unit=Jy/arcsec**2,Label(1)=L");
        sb.clear("System");
        assertEquals("SFCBR", sb.getSystem());
        assertEquals("Jy/arcsec**2", sb.getUnit(1));
        assertEquals("L", sb.getLabel(1));
        assertTrue(sb.testUnit(1));
        FluxFrame ff = flux(AstObject.AST__BAD, "Unit=Jy,System=FLXDNW,System=FLXDN");
        ff.clear("Unit(1)");
        assertEquals("W/m^2/Hz", ff.getUnit(1));
        assertEquals("FLXDN", ff.getSystem());
        ff.setSystem("FLXDNW");
        ff.setSystem("FLXDN");
        assertEquals("W/m^2/Hz", ff.getUnit(1));
        FluxFrame sv = flux(123.0, "SpecVal=5");
        sv.clear("SpecVal");
        assertEquals("123", sv.getC("SpecVal"));
    }

    /** A Unit that suits no flux System is set, and then the System cannot be found. */
    @Test
    void unitThatSuitsNoSystem() {
        FluxFrame ff = new FluxFrame();
        AstException e = assertThrows(AstException.class, () -> ff.setUnit(1, "m"));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertEquals(
                "astGetSystem(FluxFrame): The current units (m) cannot be used with any of the supported flux"
                        + " systems.",
                e.getMsgText());
        assertEquals("m", ff.getUnit(1));
    }

    /** Rust's {@code test_class_attrs.rs::flux_frame_domain_is_cleaned}. */
    @Test
    void domainIsCleaned() {
        FluxFrame ff = new FluxFrame();
        ff.setC("Domain", "my flux");
        assertEquals("MYFLUX", ff.getDomain());
    }

    /**
     * Rust's {@code test_frame_api.rs::density_follows_the_c_library} ({@code
     * oracle/frame_api.json}, the {@code DENSITY} cases of {@code frame_api_probe.c}): C's {@code
     * astGetDensitySystem} and {@code astGetDensityUnit}.
     */
    @Test
    void densityFollowsTheCLibrary() {
        com.fasterxml.jackson.databind.JsonNode cases =
                uk.ac.starlink.ast.testsupport.ProbeData.load("frame_api").get("DENSITY");
        assertEquals(3, cases.size());
        for (com.fasterxml.jackson.databind.JsonNode c : cases) {
            String setting = c.get(0).asText();
            FluxFrame ff = flux(AstObject.AST__BAD, setting);
            String sys = new SpecFrame().systemString(ff.getDensitySystem());
            assertEquals(c.get(1).asText(), sys, setting);
            assertEquals(c.get(2).asText(), ff.getDensityUnit(), setting);
        }
    }

    // ------------------------------------------------------------------
    // Conversions (test_fluxframe.rs)
    // ------------------------------------------------------------------

    /** Rust's {@code test_fluxframe_convert_same_system}. */
    @Test
    void convertSameSystem() {
        Mapping map = conversion(new FluxFrame(), new FluxFrame());
        assertInstanceOf(UnitMap.class, map);
        assertEquals(3.0, tran(map, 3.0));
    }

    /** Rust's {@code test_fluxframe_make_flux_mapping_same_system_is_unit}. */
    @Test
    void makeFluxMappingSameSystemIsUnit() {
        Mapping map = new FluxFrame().makeFluxMapping(new FluxFrame());
        assertEquals(3.0, tran(map, 3.0));
    }

    /** Rust's {@code test_fluxframe_make_flux_mapping_without_specframe_returns_none}. */
    @Test
    void makeFluxMappingWithoutSpecFrameReturnsNone() {
        FluxFrame ff1 = flux(AstObject.AST__BAD, "SpecVal=1.4e9");
        FluxFrame ff2 = flux(AstObject.AST__BAD, "System=FLXDNW,SpecVal=1.4e9");
        assertNull(ff1.makeFluxMapping(ff2));
        assertNull(conversion(ff1, ff2));
    }

    /** Rust's {@code test_fluxframe_make_flux_mapping_cross_system_with_specframe}. */
    @Test
    void makeFluxMappingCrossSystemWithSpecFrame() {
        FluxFrame ff1 = flux(AstObject.AST__BAD, "System=FREQ,Unit=Hz", "SpecVal=1.4e9");
        FluxFrame ff2 = flux(AstObject.AST__BAD, "System=FREQ,Unit=Hz", "System=FLXDNW,SpecVal=1.4e9");
        assertEquals(0.6537856304137525, tran(ff1.makeFluxMapping(ff2), 1.0));
        Mapping map = conversion(ff1, ff2);
        assertEquals(0.6537856304137525, zoom(map));
        assertEquals(0.9999999999999999, map.tran1(1, new double[] {0.6537856304137525}, false)[0]);
    }

    /** Rust's {@code test_fluxframe_make_flux_mapping_surface_brightness_mismatch_none}. */
    @Test
    void makeFluxMappingSurfaceBrightnessMismatchNone() {
        FluxFrame ff1 = flux(AstObject.AST__BAD, "SpecVal=1.4e9");
        FluxFrame ff2 = flux(AstObject.AST__BAD, "System=SFCBR,SpecVal=1.4e9");
        assertNull(ff1.makeFluxMapping(ff2));
        assertNull(conversion(ff1, ff2));
    }

    /** Rust's {@code test_fluxframe_jy_to_default_units}. */
    @Test
    void jyToDefaultUnits() {
        FluxFrame jy = flux(AstObject.AST__BAD, "Unit=Jy");
        FluxFrame def = new FluxFrame();
        assertEquals(1.0000000000000002e-26, tran(jy.makeFluxMapping(def), 1.0));
        assertEquals(1.0000000000000002e-26, zoom(conversion(jy, def)));
        assertEquals(0.9999999999999999, tran(def.makeFluxMapping(jy), 1.0e-26));
        assertEquals(9.999999999999999e25, zoom(conversion(def, jy)));
    }

    /** Rust's {@code test_fluxframe_cross_system_with_units}. */
    @Test
    void crossSystemWithUnits() {
        FluxFrame jy = flux(AstObject.AST__BAD, "System=FREQ,Unit=Hz", "Unit=Jy,SpecVal=1.4e9");
        FluxFrame w = flux(AstObject.AST__BAD, "System=FREQ,Unit=Hz", "System=FLXDNW,SpecVal=1.4e9");
        assertEquals(6.537856304137527e-27, tran(jy.makeFluxMapping(w), 1.0));
        assertEquals(6.537856304137527e-27, zoom(conversion(jy, w)));
    }

    /** Rust's {@code test_fluxframe_incompatible_unit_is_badun}. */
    @Test
    void incompatibleUnitIsBadun() {
        FluxFrame bad = flux(AstObject.AST__BAD, "System=FLXDN");
        bad.setUnit(1, "m");
        FluxFrame def = flux(AstObject.AST__BAD, "System=FLXDN");
        AstException e = assertThrows(AstException.class, () -> bad.makeFluxMapping(def));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        e = assertThrows(AstException.class, () -> bad.convert(def, ""));
        assertEquals(AstException.AST__BADUN, e.getStatus());
        assertEquals(
                "astGetSystem(FluxFrame): The current units (m) cannot be used with any of the supported flux"
                        + " systems.",
                e.getMsgText());
    }

    /** The SpecVal of a per-wavelength FluxFrame with no SpecFrame is a wavelength in Angstrom. */
    @Test
    void specValInTheDensitySystem() {
        Mapping map = conversion(flux(5000.0, "System=FLXDNW"), flux(5.99584916e14, "System=FLXDN"));
        assertEquals(8.339102404752465e-12, zoom(map));
    }

    /** No conversion between densities when the SpecVal is not known. */
    @Test
    void noConversionWithoutASpecVal() {
        assertNull(conversion(
                flux(AstObject.AST__BAD, "System=FREQ,Unit=Hz", ""),
                flux(AstObject.AST__BAD, "System=FREQ,Unit=Hz", "System=FLXDNW")));
    }

    /** A SpecVal given as a radio velocity is converted to the density systems through the SpecFrames. */
    @Test
    void specValInAVelocity() {
        Mapping map = conversion(
                flux(10.0, "System=VRAD,RestFreq=100", ""),
                flux(99.99666435e9, "System=FREQ,Unit=Hz", "System=FLXDNW"));
        assertEquals(3335.4184464280897, zoom(map));
    }

    /** AlignSystem does not change a conversion. */
    @Test
    void alignSystemIsNotUsed() {
        Mapping map = conversion(new FluxFrame(), flux(AstObject.AST__BAD, "AlignSystem=FLXDNW"));
        assertInstanceOf(UnitMap.class, map);
    }

    /**
     * A logarithmic Unit is converted by the MathMap of the units conversion, after the ZoomMap
     * to the default Unit, as C's is (probe {@code mathmap_simplify_probe.c}, case {@code
     * flux_log_conversion}); the values agree with C's to within the last bits of the maths
     * library.
     */
    @Test
    void logarithmicConversionUsesAMathMap() {
        FluxFrame jy = flux(AstObject.AST__BAD, "Unit=Jy");
        FluxFrame log = flux(AstObject.AST__BAD, "Unit=log(W/m^2/Hz)");
        FrameSet fs = jy.convert(log, "");
        assertNotNull(fs);
        Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT).simplify();
        String[] want = ProbeOutput.load("mathmap_simplify_probe")
                .get("flux_log_conversion")
                .split("\n", -1);
        String dump = ChannelText.dump(map);
        int ndump = dump.split("\n", -1).length - 1;
        StringBuilder wantDump = new StringBuilder();
        for (int i = 1; i <= ndump; i++) {
            wantDump.append(want[i]).append('\n');
        }
        assertEquals(want[0], map.astClass());
        assertEquals(wantDump.toString(), dump);
        double[] vals = {1.0, 1000.0, 0.5};
        double[] fwd = fs.tran1(vals.length, vals, true);
        double[] inv = fs.tran1(vals.length, fwd, false);
        for (int i = 0; i < vals.length; i++) {
            String[] parts = want[ndump + 1 + i].split(" -> ", -1);
            assertEquals(Double.parseDouble(parts[1]), fwd[i], 1e-14 * Math.abs(fwd[i]), "forward " + vals[i]);
            assertEquals(Double.parseDouble(parts[2]), inv[i], 1e-14 * Math.abs(inv[i]), "inverse " + vals[i]);
        }
    }

    // ------------------------------------------------------------------
    // testflux.c (test_flux.rs)
    // ------------------------------------------------------------------

    /** Rust's {@code similar_spec_vals_convert_with_a_unit_map} (Errors 8, 9). */
    @Test
    void similarSpecValsConvertWithAUnitMap() {
        Mapping map = conversion(flux(123.0, "System=freq,Unit=GHz", ""), flux(123.1, "System=freq,Unit=GHz", ""));
        assertNotNull(map, "Error 8");
        assertInstanceOf(UnitMap.class, map, "Error 9");
    }

    /** Rust's {@code per_frequency_units_scale} (Errors 10 to 14). */
    @Test
    void perFrequencyUnitsScale() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/Hz");
        assertEquals("FLXDN", ff.getSystem(), "Error 10");
        FluxFrame ff2 = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/GHz");
        assertEquals("FLXDN", ff2.getSystem(), "Error 11");
        Mapping map = conversion(ff2, ff);
        assertNotNull(map, "Error 12");
        assertEquals(1.0000000000000003e-9, zoom(map), "Error 14");
    }

    /** Rust's {@code per_wavelength_units_scale} (Errors 15 to 19). */
    @Test
    void perWavelengthUnitsScale() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/m");
        assertEquals("FLXDNW", ff.getSystem(), "Error 15");
        FluxFrame ff2 = flux(123.0e9, "System=freq,Unit=Hz", "Unit=W/m^2/Angstrom");
        assertEquals("FLXDNW", ff2.getSystem(), "Error 16");
        Mapping map = conversion(ff2, ff);
        assertNotNull(map, "Error 17");
        assertEquals(1.0e10, zoom(map), "Error 19");
    }

    /** Rust's {@code per_wavelength_units_scale_across_spectral_systems} (Errors 20 to 24). */
    @Test
    void perWavelengthUnitsScaleAcrossSpectralSystems() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/m");
        assertEquals("FLXDNW", ff.getSystem(), "Error 20");
        FluxFrame ff2 = flux(2437337.06, "System=wave,Unit=nm", "Unit=W/m^2/Angstrom");
        assertEquals("FLXDNW", ff2.getSystem(), "Error 21");
        Mapping map = conversion(ff, ff2);
        assertNotNull(map, "Error 22");
        assertEquals(1.0e-10, zoom(map), "Error 24");
    }

    /** Rust's {@code frequency_density_converts_to_wavelength_density} (Errors 25, 26). */
    @Test
    void frequencyDensityConvertsToWavelengthDensity() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/Hz");
        FluxFrame ff2 = flux(2437337.06, "System=wave,Unit=nm", "Unit=W/m^2/m");
        FrameSet fs = ff.convert(ff2, " ");
        assertNotNull(fs, "Error 25");
        assertEquals(5.046491188679814, tran(fs, 1.0e-13), "Error 26");
        assertEquals(50464911886798.13, zoom(conversion(ff, ff2)));
    }

    /** Rust's {@code surface_brightness_converts} (Errors 27a, 27b, 27, 28). */
    @Test
    void surfaceBrightnessConverts() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/Hz/arcsec**2");
        assertEquals("SFCBR", ff.getSystem(), "Error 27a");
        FluxFrame ff2 = flux(2437337.06, "System=wave,Unit=nm", "Unit=W/m^2/m/deg**2");
        assertEquals("SFCBRW", ff2.getSystem(), "Error 27b");
        FrameSet fs = ff.convert(ff2, " ");
        assertNotNull(fs, "Error 27");
        assertEquals(65402525.8052904, tran(fs, 1.0e-13), "Error 28");
    }

    /** Rust's {@code surface_brightness_does_not_convert_to_flux_density} (Error 29). */
    @Test
    void surfaceBrightnessDoesNotConvertToFluxDensity() {
        FluxFrame ff = flux(123.0, "System=freq,Unit=GHz", "Unit=W/m^2/Hz/arcsec**2");
        FluxFrame ff2 = flux(2437337.06, "System=wave,Unit=nm", "Unit=W/m^2/m");
        assertNull(ff.convert(ff2, " "), "Error 29");
    }

    /** Rust's {@code per_wavelength_surface_brightness_reads_back} (Errors 30 to 32). */
    @Test
    void perWavelengthSurfaceBrightnessReadsBack() {
        FluxFrame ff = flux(AstObject.AST__BAD, "System=SFCBRW");
        assertEquals("SFCBRW", ff.getC("System"), "Error 31");
        FluxFrame copy = assertInstanceOf(FluxFrame.class, ChannelText.read(ChannelText.dump(ff)), "Error 32");
        assertEquals("SFCBRW", copy.getC("System"), "Error 32");
    }

    // ------------------------------------------------------------------
    // Match, SubFrame, Overlay and PickAxes (frames/fluxframe.rs)
    // ------------------------------------------------------------------

    /** Rust's {@code test_system_default_is_fluxden_unset}. */
    @Test
    void systemDefaultIsFluxdenUnset() {
        FluxFrame f = new FluxFrame();
        assertFalse(f.testSystem());
        f.setSystem("SFCBR");
        assertTrue(f.testSystem());
    }

    /** Rust's {@code match_template_fluxframe_self}. */
    @Test
    void matchTemplateSelf() {
        FluxFrame template = flux(AstObject.AST__BAD, "System=SFCBR");
        FluxFrame target = flux(AstObject.AST__BAD, "System=SFCBR");
        FrameFinder.MatchResult m = template.match(target, false);
        assertNotNull(m);
        assertEquals(0, m.templateAxes[0]);
        assertEquals(0, m.targetAxes[0]);
        assertEquals("SFCBR", assertInstanceOf(FluxFrame.class, m.result).getSystem());
    }

    /** Rust's {@code match_template_fluxframe_against_basicframe_fails}. */
    @Test
    void matchTemplateAgainstPlainFrameFails() {
        assertNull(flux(AstObject.AST__BAD, "System=SFCBR").match(new Frame(1), false));
    }

    /** Rust's {@code match_template_fluxframe_different_system_returns_none}. */
    @Test
    void matchTemplateDifferentSystemReturnsNone() {
        assertNull(flux(AstObject.AST__BAD, "System=SFCBR").match(flux(AstObject.AST__BAD, "System=FLXDNW"), false));
    }

    /** Rust's {@code sub_frame_fluxframe_to_fluxframe_identity}. */
    @Test
    void subFrameIdentity() {
        FluxFrame target = flux(AstObject.AST__BAD, "System=SFCBR");
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(target.subFrame(
                flux(AstObject.AST__BAD, "System=SFCBR"), 1, new int[] {0}, new int[] {0}, map, result));
        assertEquals(1, map[0].getNin());
        assertEquals(1, map[0].getNout());
        assertEquals("SFCBR", assertInstanceOf(FluxFrame.class, result[0]).getSystem());
    }

    /** Rust's {@code overlay_template_copies_set_system}. */
    @Test
    void overlayTemplateCopiesSetSystem() {
        FluxFrame result = new FluxFrame();
        flux(AstObject.AST__BAD, "System=SFCBR").overlay(new int[] {0}, result);
        assertEquals("SFCBR", result.getSystem());
    }

    /** Rust's {@code overlay_template_skips_unset_system}. */
    @Test
    void overlayTemplateSkipsUnsetSystem() {
        FluxFrame result = flux(AstObject.AST__BAD, "System=SFCBR");
        new FluxFrame().overlay(new int[] {0}, result);
        assertEquals("SFCBR", result.getSystem());
    }

    /**
     * Overlaid onto a plain Frame, a template with its System set has its System and AlignSystem
     * cleared and set again (a C quirk, followed here): its AlignSystem is left set, and when
     * clearing the System changes it, its Label and Title are cleared before they are overlaid.
     * Values from C's {@code astOverlay}.
     */
    @Test
    void overlayOntoAPlainFrameChangesTheTemplate() {
        FluxFrame t = flux(AstObject.AST__BAD, "System=SFCBR,Unit=Jy/arcsec**2,Label(1)=Mine,Title=TT");
        Frame r = new Frame(1);
        t.overlay(new int[] {0}, r);
        assertEquals("SFCBR", t.getSystem());
        assertEquals("FLXDN", t.getAlignSystem());
        assertTrue(t.testAttrib("alignsystem"));
        assertEquals("Mine", t.getLabel(1));
        assertEquals("Jy/arcsec**2", t.getUnit(1));
        assertEquals("TT", t.getTitle());
        assertEquals("Cartesian", r.getSystem());
        assertFalse(r.testSystem());
        assertEquals("Mine", r.getLabel(1));
        assertEquals("Jy/arcsec**2", r.getUnit(1));
        assertEquals("TT", r.getTitle());

        FluxFrame t2 = flux(AstObject.AST__BAD, "System=SFCBR,Label(1)=Mine,Title=TT");
        Frame r2 = new Frame(1);
        t2.overlay(new int[] {0}, r2);
        assertEquals("SFCBR", t2.getSystem());
        assertTrue(t2.testAttrib("alignsystem"));
        assertEquals("Surface brightness", t2.getLabel(1));
        assertFalse(t2.testLabel(1));
        assertEquals("W/m^2/Hz/arcmin**2", t2.getUnit(1));
        assertFalse(t2.testTitle());
        assertEquals("Axis 1", r2.getLabel(1));
        assertEquals("", r2.getUnit(1));
        assertFalse(r2.testTitle());
    }

    /** Rust's {@code pick_axes_snapshot_full_returns_fluxframe}. */
    @Test
    void pickAxesFullReturnsFluxFrame() {
        Mapping[] map = new Mapping[1];
        Frame f = flux(1.4e9, "System=SFCBR").pickAxes(1, new int[] {1}, map);
        assertEquals("SFCBR", assertInstanceOf(FluxFrame.class, f).getSystem());
        assertInstanceOf(UnitMap.class, map[0]);
    }

    /** A FluxFrame template does not find the FluxFrame of a CmpFrame, which has two axes. */
    @Test
    void templateDoesNotFindTheFluxFrameOfACmpFrame() {
        SpecFrame sf = new SpecFrame();
        sf.setSystem("FREQ");
        CmpFrame cf = new CmpFrame(sf, flux(AstObject.AST__BAD, "Unit=Jy"));
        assertNull(cf.findFrame(new FluxFrame(), ""));
    }

    // ------------------------------------------------------------------
    // Dumps
    // ------------------------------------------------------------------

    private static final String DUMP_DEFAULT = String.join(
            "\n",
            " Begin FluxFrame \t# Description of flux values",
            "#   Title = \"Flux density\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"FLUX\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Flux density\" \t# Label for axis 1",
            "#   System = \"FLXDN\" \t# Coordinate system type",
            "#   Uni1 = \"W/m^2/Hz\" \t# Units for axis 1",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            " End FluxFrame",
            "");

    private static final String DUMP_JY = String.join(
            "\n",
            " Begin FluxFrame \t# Description of flux values",
            "#   Title = \"Flux density\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"FLUX\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Flux density\" \t# Label for axis 1",
            "#   System = \"FLXDN\" \t# Coordinate system type",
            "#   Uni1 = \"Jy\" \t# Units for axis 1 (Jansky)",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "          Unit = \"Jy\" \t# Axis units (Jansky)",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            "    UFlxdn = \"Jy\" \t# Preferred units for flux density",
            " End FluxFrame",
            "");

    private static final String DUMP_SFCBRW = String.join(
            "\n",
            " Begin FluxFrame \t# Description of flux values",
            "#   Title = \"Surface brightness (per wavelength) at = 5E+14 Angstrom\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"FLUX\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Surface brightness (per wavelength)\" \t# Label for axis 1",
            "    System = \"SFCBRW\" \t# Coordinate system type",
            "#   Uni1 = \"W/m^2/Angstrom/arcmin**2\" \t# Units for axis 1",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            "    SpcVl = 500000000000000 \t# Spectral position",
            " End FluxFrame",
            "");

    private static final String DUMP_TITLE = String.join(
            "\n",
            " Begin FluxFrame \t# Description of flux values",
            "    Title = \"Test Title\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"FLUX\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Flux density\" \t# Label for axis 1",
            "#   System = \"FLXDN\" \t# Coordinate system type",
            "#   Uni1 = \"W/m^2/Hz\" \t# Units for axis 1",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            " End FluxFrame",
            "");

    private static final String DUMP_SF = String.join(
            "\n",
            " Begin FluxFrame \t# Description of flux values",
            "#   Title = \"Flux density at = 123 GHz\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"FLUX\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Flux density\" \t# Label for axis 1",
            "#   System = \"FLXDN\" \t# Coordinate system type",
            "#   Uni1 = \"W/m^2/Hz\" \t# Units for axis 1",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            "    SpcFr = \t# SpcVl coord system",
            "       Begin SpecFrame \t# Description of spectral coordinate system",
            "#         Title = \"Frequency\" \t# Title of coordinate system",
            "          Naxes = 1 \t# Number of coordinate axes",
            "#         Domain = \"SPECTRUM\" \t# Coordinate system domain",
            "#         Epoch = 2000 \t# Julian epoch of observation",
            "#         Lbl1 = \"Frequency\" \t# Label for axis 1",
            "          System = \"FREQ\" \t# Coordinate system type",
            "#         Uni1 = \"GHz\" \t# Units for axis 1",
            "          Ax1 = \t# Axis number 1",
            "             Begin Axis \t# Coordinate axis",
            "                Unit = \"GHz\" \t# Axis units",
            "             End Axis",
            "       IsA Frame \t# Coordinate system description",
            "#         SoR = \"Heliocentric\" \t# Standard of rest",
            "          UFreq = \"GHz\" \t# Preferred units for frequency",
            "       End SpecFrame",
            "    DfSpc = 123 \t# Default spectral position",
            " End FluxFrame",
            "");

    private static final String DUMP_USED = String.join(
            "\n",
            " Begin FluxFrame \t# Description of flux values",
            "#   Title = \"Flux wavelength density at = 1.4E+09 Angstrom\" \t# Title of coordinate system",
            "    Naxes = 1 \t# Number of coordinate axes",
            "#   Domain = \"FLUX\" \t# Coordinate system domain",
            "#   Epoch = 2000 \t# Julian epoch of observation",
            "#   Lbl1 = \"Flux wavelength density\" \t# Label for axis 1",
            "    System = \"FLXDNW\" \t# Coordinate system type",
            "    AlSys = \"SFCBR\" \t# Alignment coordinate system",
            "#   Uni1 = \"W/m^2/nm\" \t# Units for axis 1",
            "    Ax1 = \t# Axis number 1",
            "       Begin Axis \t# Coordinate axis",
            "          Unit = \"W/m^2/nm\" \t# Axis units",
            "       End Axis",
            " IsA Frame \t# Coordinate system description",
            "    DfSpc = 1400000000 \t# Default spectral position",
            "    UFlxdn = \"Jy\" \t# Preferred units for flux density",
            "    UFlxdnw = \"W/m^2/nm\" \t# Preferred units for flux wavelength density",
            " End FluxFrame",
            "");

    /** C's dump of {@code frame}, which reads back to a FluxFrame with the same dump. */
    private static FluxFrame assertDumpRoundTrip(String want, FluxFrame frame) {
        assertEquals(want, ChannelText.dump(frame));
        FluxFrame back = assertInstanceOf(FluxFrame.class, ChannelText.read(want));
        assertEquals(want, ChannelText.dump(back));
        assertEquals(frame, back);
        return back;
    }

    /** Rust's {@code test_fluxframe_serialisation_roundtrip_fluxden}. */
    @Test
    void dumpDefault() {
        assertDumpRoundTrip(DUMP_DEFAULT, new FluxFrame());
    }

    /** Rust's {@code test_fluxframe_unit_serialisation_round_trip}. */
    @Test
    void dumpUnit() {
        FluxFrame back = assertDumpRoundTrip(DUMP_JY, flux(AstObject.AST__BAD, "Unit=Jy"));
        assertEquals("Jy", back.getUnit(1));
    }

    /** Rust's {@code test_fluxframe_serialisation_roundtrip_sbrightw}. */
    @Test
    void dumpSbrightw() {
        FluxFrame back = assertDumpRoundTrip(DUMP_SFCBRW, flux(AstObject.AST__BAD, "System=SFCBRW,SpecVal=5e14"));
        assertEquals(5.0e14, back.getSpecVal());
        assertTrue(back.test("SpecVal"));
    }

    /** Rust's {@code test_fluxframe_serialisation_with_title}. */
    @Test
    void dumpTitle() {
        assertDumpRoundTrip(DUMP_TITLE, flux(AstObject.AST__BAD, "Title=Test Title"));
    }

    /** A FluxFrame holding a SpecFrame and a default SpecVal. */
    @Test
    void dumpSpecFrame() {
        FluxFrame back = assertDumpRoundTrip(DUMP_SF, flux(123.0, "System=FREQ,Unit=GHz", ""));
        assertEquals(123.0, back.getSpecVal());
        assertFalse(back.test("SpecVal"));
        assertEquals("Flux density at = 123 GHz", back.getTitle());
    }

    /** The Units used with each System are dumped and read back. */
    @Test
    void dumpUsedUnits() {
        FluxFrame back =
                assertDumpRoundTrip(DUMP_USED, flux(1.4e9, "Unit=Jy,System=FLXDNW,Unit=W/m^2/nm,AlignSystem=SFCBR"));
        back.setSystem("FLXDN");
        assertEquals("Jy", back.getUnit(1));
    }

    /**
     * The Unit used with a System is recorded as given, trailing spaces and all, though the Unit
     * itself is stored without them, as C's {@code SetUnit} records it.
     */
    @Test
    void usedUnitKeepsTrailingSpaces() {
        FluxFrame ff = new FluxFrame();
        ff.setC("Unit(1)", "Jy  ");
        String dump = ChannelText.dump(ff);
        assertTrue(dump.contains("          Unit = \"Jy\" \t# Axis units (Jansky)\n"), dump);
        assertTrue(dump.contains("    UFlxdn = \"Jy  \" \t# Preferred units for flux density\n"), dump);
        ff.set("Unit(1)=W/m^2/Hz  ");
        assertTrue(ChannelText.dump(ff).contains("    UFlxdn = \"W/m^2/Hz  \" \t# Preferred units for flux density\n"));
    }

    /** A copy holds its own SpecFrame and record of Units. */
    @Test
    void copyIsIndependent() {
        FluxFrame ff = flux(123.0, "System=FREQ,Unit=GHz", "Unit=Jy");
        FluxFrame copy = (FluxFrame) ff.copy();
        copy.setSystem("FLXDNW");
        copy.setUnit(1, "W/m^2/nm");
        ff.setSystem("FLXDNW");
        assertEquals("W/m^2/Angstrom", ff.getUnit(1));
        assertEquals("W/m^2/nm", copy.getUnit(1));
        assertEquals("Flux wavelength density at = 123 GHz", copy.getTitle());
    }

    /** A dump whose SpcFr is not a SpecFrame is refused. */
    @Test
    void loaderRefusesAnotherClassOfSpcFr() {
        String text = DUMP_SF.replace("Begin SpecFrame", "Begin Frame")
                .replace("End SpecFrame", "End Frame")
                .replace("#         SoR = \"Heliocentric\" \t# Standard of rest\n", "")
                .replace("          UFreq = \"GHz\" \t# Preferred units for frequency\n", "")
                .replace("          System = \"FREQ\" \t# Coordinate system type\n", "")
                .replace("       IsA Frame \t# Coordinate system description\n", "");
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__LDERR, e.getStatus());
    }

    /** jniast's accessors. */
    @Test
    void jniastAccessors() {
        SpecFrame sf = new SpecFrame();
        sf.setSystem("FREQ");
        FluxFrame ff = new FluxFrame(123.0, sf);
        sf.setUnit(1, "Hz");
        assertEquals("Flux density at = 123 GHz", ff.getTitle());
        assertEquals(123.0, ff.getSpecVal());
        ff.setSpecVal(5.0);
        assertEquals(5.0, ff.getSpecVal());
    }

    // ------------------------------------------------------------------
    // Equality (frame.c Equal: the conversion simplifies to a UnitMap)
    // ------------------------------------------------------------------

    @Test
    void testFluxframeEqualSame() {
        assertTrue(new FluxFrame().astEqual(new FluxFrame()));
    }

    @Test
    void testFluxframeEqualDifferentSystem() {
        assertFalse(new FluxFrame().astEqual(flux(AstObject.AST__BAD, "System=FLXDNW")));
    }

    @Test
    void testFluxframeEqualDifferentSpecvalIsEqual() {
        FluxFrame ff1 = new FluxFrame();
        FluxFrame ff2 = new FluxFrame();
        ff1.setSpecVal(1.0e9);
        ff2.setSpecVal(2.0e9);
        assertTrue(ff1.astEqual(ff2));
    }
}
