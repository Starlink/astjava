package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of Rust {@code tests/test_specmap.rs}, itself from C's {@code testspecmap.c}: the
 * spectral conversions, alone and chained, in both directions, the changes of rest frame, bad
 * values, the rate and native round trips.
 *
 * <p>{@code code_roundtrip} and {@code inverse_pairs} are translated as the behaviour C gives the
 * codes: each code {@code astSpecAdd} accepts is written back by name, and simplifying an inverted
 * SpecMap gives the inverse conversion. {@code specmap_always_merges_adjacent} becomes a
 * simplification of the two SpecMaps in a series CmpMap. Deferred: {@code equality} ({@code
 * astEqual}, an out-of-scope method).
 */
class SpecMapTest {

    private static final double C_LIGHT = 2.99792458e8;
    private static final double H_PLANCK = 6.6260755e-34;
    private static final double BAD = AstObject.AST__BAD;

    /** Every conversion C's {@code CvtCode} recognises, with arguments of the number it takes. */
    private static final Map<String, double[]> CONVERSIONS = allConversions();

    private static Map<String, double[]> allConversions() {
        Map<String, double[]> m = new LinkedHashMap<>();
        double[] none = new double[0];
        m.put("FRTOVL", new double[] {1.42e9});
        m.put("VLTOFR", new double[] {1.42e9});
        for (String c : new String[] {
            "ENTOFR", "FRTOEN", "WNTOFR", "FRTOWN", "WVTOFR", "FRTOWV", "AWTOFR", "FRTOAW", "VRTOVL", "VLTOVR",
            "VOTOVL", "VLTOVO", "ZOTOVL", "VLTOZO", "BTTOVL", "VLTOBT"
        }) {
            m.put(c, none);
        }
        m.put("USF2HL", new double[] {30000.0, 1.0, 0.5});
        m.put("HLF2US", new double[] {30000.0, 1.0, 0.5});
        m.put("TPF2HL", new double[] {-0.1, 0.9, 100.0, 53000.0, 1.0, 0.5});
        m.put("HLF2TP", new double[] {-0.1, 0.9, 100.0, 53000.0, 1.0, 0.5});
        for (String c : new String[] {"GEF2HL", "HLF2GE", "BYF2HL", "HLF2BY"}) {
            m.put(c, new double[] {53000.0, 1.5, 0.3});
        }
        for (String c : new String[] {"LKF2HL", "HLF2LK", "LDF2HL", "HLF2LD", "LGF2HL", "HLF2LG", "GLF2HL", "HLF2GL"}) {
            m.put(c, new double[] {1.0, 0.5});
        }
        return m;
    }

    /** The name of the conversion that inverts {@code code}: "AATOBB" for "BBTOAA", "HLF2XX" for "XXF2HL". */
    private static String inverseName(String code) {
        String sep = code.substring(2, 4);
        return code.substring(4) + sep + code.substring(0, 2);
    }

    private static SpecMap spec(String cvt, double... args) {
        SpecMap m = new SpecMap(1, 0);
        m.specAdd(cvt, args);
        return m;
    }

    private static double transformOne(Mapping m, double val, boolean forward) {
        return m.transform(new double[][] {{val}}, 1, forward)[0][0];
    }

    private static double fwd(Mapping m, double val) {
        return transformOne(m, val, true);
    }

    private static void assertRel(double got, double want, double rtol, String label) {
        assertTrue(Math.abs(got - want) / Math.abs(want) < rtol, label + ": got " + got + ", expected " + want);
    }

    // ------------------------------------------------------------------
    // Changes of system
    // ------------------------------------------------------------------

    @Test
    void freqToWavelength() {
        double wv = fwd(spec("FRTOWV"), 5.0e14);
        assertTrue(Math.abs(wv - C_LIGHT / 5.0e14) < 1e-20, "FrToWv: got " + wv);
    }

    @Test
    void freqToEnergy() {
        assertRel(fwd(spec("FRTOEN"), 5.0e14), H_PLANCK * 5.0e14, 1e-10, "FrToEn");
    }

    @Test
    void energyToFreq() {
        assertRel(fwd(spec("ENTOFR"), 3.313e-19), 3.313e-19 / H_PLANCK, 1e-10, "EnToFr");
    }

    @Test
    void roundtripFreqWavelength() {
        SpecMap m = spec("FRTOWV");
        m.specAdd("WVTOFR", null);
        assertRel(fwd(m, 4.567e14), 4.567e14, 1e-12, "freq->wv->freq");
    }

    @Test
    void roundtripFreqVelocity() {
        SpecMap m = spec("FRTOVL", 1.42e9);
        m.specAdd("VLTOFR", new double[] {1.42e9});
        assertRel(fwd(m, 1.41e9), 1.41e9, 1e-12, "freq->vel->freq");
    }

    @Test
    void freqToWavenum() {
        assertRel(fwd(spec("FRTOWN"), 3.0e14), 3.0e14 / C_LIGHT, 1e-12, "FrToWn");
    }

    @Test
    void wavenumToFreq() {
        assertRel(fwd(spec("WNTOFR"), 1.0e6), 1.0e6 * C_LIGHT, 1e-12, "WnToFr");
    }

    @Test
    void betaToVelocity() {
        double vel = fwd(spec("BTTOVL"), 0.5);
        assertTrue(Math.abs(vel - 0.5 * C_LIGHT) < 1.0, "BtToVl: got " + vel);
    }

    @Test
    void velocityToBeta() {
        double beta = fwd(spec("VLTOBT"), 1.5e8);
        assertTrue(Math.abs(beta - 1.5e8 / C_LIGHT) < 1e-12, "VlToBt: got " + beta);
    }

    @Test
    void roundtripRedshiftVelocity() {
        SpecMap m = spec("ZOTOVL");
        m.specAdd("VLTOZO", new double[0]);
        double z = fwd(m, 0.1);
        assertTrue(Math.abs(z - 0.1) < 1e-12, "z->v->z: got " + z);
    }

    @Test
    void roundtripRadioVelocity() {
        SpecMap m = spec("VRTOVL");
        m.specAdd("VLTOVR", new double[0]);
        assertRel(fwd(m, 1.0e7), 1.0e7, 1e-12, "vr->vl->vr");
    }

    @Test
    void roundtripOpticalVelocity() {
        SpecMap m = spec("VOTOVL");
        m.specAdd("VLTOVO", new double[0]);
        assertRel(fwd(m, 5.0e7), 5.0e7, 1e-12, "vo->vl->vo");
    }

    @Test
    void roundtripAirWavelength() {
        SpecMap m = spec("FRTOAW");
        m.specAdd("AWTOFR", new double[0]);
        assertRel(fwd(m, 5.0e14), 5.0e14, 1e-10, "freq->air wv->freq");
    }

    @Test
    void freqToVelocity() {
        SpecMap m = spec("FRTOVL", 1.42e9);
        double vel = fwd(m, 1.42e9);
        assertTrue(Math.abs(vel) < 1.0e-5, "FrToVl at the rest frequency: got " + vel);
        // v = c * (rf^2 - f^2) / (rf^2 + f^2) = c * 0.6 at half the rest frequency.
        assertRel(fwd(m, 1.42e9 / 2.0), C_LIGHT * 0.6, 1e-10, "FrToVl at rf/2");
    }

    @Test
    void velocityToFreq() {
        assertRel(fwd(spec("VLTOFR", 1.42e9), 0.0), 1.42e9, 1e-12, "VlToFr at v=0");
    }

    @Test
    void multistepChain() {
        double air = fwd(spec("FRTOAW"), 5.0e14);
        double vac = fwd(spec("FRTOWV"), 5.0e14);
        assertTrue(air < vac, "air wavelength " + air + " should be less than vacuum " + vac);
        assertTrue(Math.abs(vac / air - 1.0) < 0.001, "refractive index " + vac / air);
    }

    // ------------------------------------------------------------------
    // The rate
    // ------------------------------------------------------------------

    /** specmap.c {@code Rate}: a lone wavelength to frequency step has the rate -c/x^2. */
    @Test
    void rateWvtofrIsAnalytic() {
        double wave = 0.21106199521261618;
        double rate = spec("WVTOFR").rate(new double[] {wave}, 1, 1);
        double want = -C_LIGHT / (wave * wave);
        assertTrue(Math.abs(rate - want) <= 1e-12 * Math.abs(want), "WvToFr rate " + rate);
    }

    /** specmap.c {@code Rate} reads the first conversion's code, whatever the Invert attribute. */
    @Test
    void rateFrtowvIsAnalytic() {
        double freq = 1.4204e9;
        double rate = spec("FRTOWV").rate(new double[] {freq}, 1, 1);
        double want = -C_LIGHT / (freq * freq);
        assertTrue(Math.abs(rate - want) <= 1e-12 * Math.abs(want), "FrToWv rate " + rate);
    }

    /** More than one step: the rate is estimated as the Mapping class estimates it. */
    @Test
    void rateMultistepFallsBackToNumeric() {
        SpecMap m = spec("FRTOWV");
        m.specAdd("WVTOFR", new double[0]);
        double rate = m.rate(new double[] {1.4204e9}, 1, 1);
        assertTrue(Math.abs(rate - 1.0) <= 1e-6, "multi-step rate " + rate);
    }

    // ------------------------------------------------------------------
    // Changes of rest frame
    // ------------------------------------------------------------------

    @Test
    void lsrkCorrection() {
        double freq = 1.42e9;
        double shifted = fwd(spec("LKF2HL", Math.PI / 4.0, Math.PI / 6.0), freq);
        assertTrue(Math.abs(shifted - freq) > 1.0, "LSRK shift " + Math.abs(shifted - freq));
        assertTrue(Math.abs(shifted - freq) / freq < 0.001, "LSRK shift " + Math.abs(shifted - freq) / freq);
    }

    private static void assertRoundTrip(String x2hl, String hl2x, double freq, double rtol, double... args) {
        SpecMap m = spec(x2hl, args);
        m.specAdd(hl2x, args);
        assertRel(fwd(m, freq), freq, rtol, x2hl + " then " + hl2x);
    }

    @Test
    void lsrkRoundtrip() {
        assertRoundTrip("LKF2HL", "HLF2LK", 1.42e9, 1e-12, 1.0, 0.5);
    }

    @Test
    void lsrdRoundtrip() {
        assertRoundTrip("LDF2HL", "HLF2LD", 5.0e14, 1e-12, 2.0, -0.3);
    }

    @Test
    void lgRoundtrip() {
        assertRoundTrip("LGF2HL", "HLF2LG", 1.0e10, 1e-12, 0.5, 0.8);
    }

    @Test
    void galacticRoundtrip() {
        assertRoundTrip("GLF2HL", "HLF2GL", 2.0e9, 1e-12, 3.0, -1.0);
    }

    @Test
    void geocentricRoundtrip() {
        assertRoundTrip("GEF2HL", "HLF2GE", 1.42e9, 1e-12, 53000.0, 1.5, 0.3);
    }

    @Test
    void barycentricRoundtrip() {
        assertRoundTrip("BYF2HL", "HLF2BY", 1.42e9, 1e-12, 53000.0, 2.5, -0.5);
    }

    @Test
    void topocentricRoundtrip() {
        assertRoundTrip("TPF2HL", "HLF2TP", 1.42e9, 1e-10, -0.1, 0.9, 100.0, 53000.0, 1.0, 0.5);
    }

    @Test
    void userDefinedRoundtrip() {
        assertRoundTrip("USF2HL", "HLF2US", 1.42e9, 1e-12, 30000.0, 1.0, 0.5);
    }

    /**
     * testspecmap.c {@code check_topocentric_frequency}, with its wanted value. The observer's
     * Earth-rotation velocity comes from PAL in au/s and is converted back to km/s with the au PAL
     * used, ERFA's {@code ERFA_DAU}.
     */
    @Test
    void topocentricToHeliocentricFrequency() {
        // OBSLON, OBSLAT, OBSALT, EPOCH, RA, DEC; the RA is the C test's, close to pi/2 but not it.
        double out = fwd(spec("TPF2HL", -2.71, 0.34, 4200.0, 60000.0, 1.570796326794896, 0.1), 1.0e9);
        double want = 1000085270.7870382;
        assertTrue(Math.abs(out - want) <= 2.0e-6, "TPF2HL gives " + out + " Hz, expected " + want);
    }

    // ------------------------------------------------------------------
    // Inversion, bad values and simplification
    // ------------------------------------------------------------------

    @Test
    void inversion() {
        SpecMap m = spec("FRTOVL", 1.42e9);
        double freq = 1.41e9;
        double vel = fwd(m, freq);
        assertRel(transformOne(m, vel, false), freq, 1e-12, "inverse");
        Mapping m2 = (Mapping) m.copy();
        m2.invert();
        assertRel(fwd(m2, vel), freq, 1e-12, "inverted forward");
    }

    @Test
    void badValuePropagation() {
        assertEquals(BAD, fwd(spec("FRTOWV"), BAD));
    }

    /**
     * specmap.c {@code SystemChange}: ENTOFR does not test its result, so an overflow gives an
     * infinity, while FRTOWV makes a result that is not finite bad.
     */
    @Test
    void anOverflowIsKeptWhereCKeepsIt() {
        assertEquals(Double.POSITIVE_INFINITY, fwd(spec("ENTOFR"), 1.0e308));
        assertEquals(BAD, fwd(spec("FRTOWV"), Double.MIN_VALUE));
    }

    /**
     * specmap.c {@code MapMerge}: SpecMaps in series always merge into one, whether or not any of
     * their conversions cancel.
     */
    @Test
    void specmapAlwaysMergesAdjacent() {
        Mapping s = new CmpMap(spec("FRTOWV"), spec("VRTOVL"), true).simplify();
        assertInstanceOf(SpecMap.class, s);
        String dump = ChannelText.dump(s);
        assertTrue(dump.contains("Spec1 = \"FRTOWV\"") && dump.contains("Spec2 = \"VRTOVL\""), dump);
    }

    /**
     * specmap.c {@code CvtCode} and {@code CvtString}: every code is accepted, in any case and
     * with trailing spaces, and written back by its name.
     */
    @Test
    void codeRoundtrip() {
        for (Map.Entry<String, double[]> e : CONVERSIONS.entrySet()) {
            String code = e.getKey();
            for (String given : new String[] {code, code.toLowerCase(Locale.ROOT) + "  "}) {
                String dump = ChannelText.dump(spec(given, e.getValue()));
                assertTrue(dump.contains("Spec1 = \"" + code + "\""), given + ":\n" + dump);
            }
        }
        assertEquals(34, CONVERSIONS.size());
    }

    /**
     * specmap.c {@code MapMerge}: simplifying an inverted SpecMap gives a SpecMap applying the
     * inverse conversion forward, and doing that twice gives the original conversion.
     */
    @Test
    void inversePairs() {
        for (Map.Entry<String, double[]> e : CONVERSIONS.entrySet()) {
            String code = e.getKey();
            String inverse = inverseName(code);
            assertTrue(CONVERSIONS.containsKey(inverse), code);
            SpecMap m = spec(code, e.getValue());
            m.invert();
            Mapping once = m.simplify();
            assertInstanceOf(SpecMap.class, once, code);
            assertFalse(once.getInvert(), code);
            assertTrue(ChannelText.dump(once).contains("Spec1 = \"" + inverse + "\""), code);
            Mapping twice = (Mapping) once.copy();
            twice.invert();
            twice = twice.simplify();
            assertTrue(ChannelText.dump(twice).contains("Spec1 = \"" + code + "\""), code);
        }
    }

    // ------------------------------------------------------------------
    // Arguments and errors
    // ------------------------------------------------------------------

    /** specmap.c {@code SpecAdd}: an unknown code is AST__SPCIN. */
    @Test
    void anUnknownCodeIsRefused() {
        SpecMap m = new SpecMap(1, 0);
        AstException e = assertThrows(AstException.class, () -> m.specAdd("FRTOXX", new double[0]));
        assertEquals(AstException.AST__SPCIN, e.getStatus());
    }

    /** specmap.c {@code AddSpecCvt}: each conversion takes exactly its number of arguments. */
    @Test
    void eachConversionTakesExactlyItsArguments() {
        for (Map.Entry<String, double[]> e : CONVERSIONS.entrySet()) {
            int n = e.getValue().length;
            SpecMap m = new SpecMap(1, 0);
            AstException ex =
                    assertThrows(AstException.class, () -> m.specAdd(e.getKey(), new double[n + 1]), e.getKey());
            assertEquals(AstException.AST__TIMIN, ex.getStatus(), e.getKey());
            m.specAdd(e.getKey(), e.getValue());
        }
    }

    // ------------------------------------------------------------------
    // Native round trips
    // ------------------------------------------------------------------

    @Test
    void serialisationRoundtrip() {
        SpecMap m = spec("FRTOWV");
        m.specAdd("WVTOFR", new double[0]);
        m.specAdd("FRTOVL", new double[] {1.42e9});
        m.specAdd("LKF2HL", new double[] {1.0, 0.5});
        AstObject back = ChannelText.read(ChannelText.dump(m));
        assertInstanceOf(SpecMap.class, back);
        assertTrue(Math.abs(fwd(m, 1.42e9) - fwd((Mapping) back, 1.42e9)) < 1e-6);
    }

    /**
     * A SpecMap holding every conversion is written with C's comments, reads back as the same
     * SpecMap, and transforms as the original does.
     */
    @Test
    void everyConversionSurvivesANativeRoundTrip() {
        for (int nin : new int[] {1, 3}) {
            SpecMap m = new SpecMap(nin, 0);
            for (Map.Entry<String, double[]> e : CONVERSIONS.entrySet()) {
                m.specAdd(e.getKey(), e.getValue());
            }
            String dump = ChannelText.dump(m);
            assertTrue(dump.contains("Nspec = 34 \t# Number of conversion steps"), dump);
            assertTrue(dump.contains("Spec22d = 53000 \t# UT1 epoch of observaton (Modified Julian Date)"), dump);
            AstObject back = ChannelText.read(dump);
            assertInstanceOf(SpecMap.class, back);
            assertEquals(dump, ChannelText.dump(back));
            double[][] in = nin == 1
                    ? new double[][] {{1.0e9, 1.42e9, BAD}}
                    : new double[][] {{1.0e9, 1.42e9, BAD}, {1.0, 2.0, 3.0}, {0.5, -0.5, 0.1}};
            for (boolean forward : new boolean[] {true, false}) {
                double[][] want = m.transform(in, 3, forward);
                double[][] got = ((Mapping) back).transform(in, 3, forward);
                for (int c = 0; c < nin; c++) {
                    assertArrayEquals(want[c], got[c], "nin " + nin + " forward " + forward);
                }
            }
        }
    }

    /**
     * specmap.c {@code FrameChange} keeps the frequency correction factor of a 1-dimensional
     * SpecMap's change of rest frame in the conversion's arguments once it has found it, so a
     * SpecMap written after it has transformed points carries the factor. The arguments and
     * factors are those of the SpecMaps of C fixture {@code simplify/cap_specmap_06.map}.
     */
    @Test
    void theFrequencyCorrectionFactorIsKeptAndWritten() {
        SpecMap m = spec("USF2HL", 70700.96444689816, -1.3776320118304248, -0.06981317007977327);
        m.specAdd("HLF2TP", new double[] {
            -2.71363307300091, 0.346026069000144, 0, 54299.24556318331, -1.3776320118304248, -0.06981317007977327
        });
        String before = ChannelText.dump(m);
        assertFalse(before.contains("Frequency correction factor"), before);
        transformOne(m, 1.0e11, true);
        String after = ChannelText.dump(m);
        String want = before.replace(
                        "    Spec2 = ",
                        "    Spec1d = 0.99976419476969891 \t# Frequency correction factor\n    Spec2 = ")
                .replace(
                        " End SpecMap",
                        "    Spec2g = 0.99997927106231288 \t# Frequency correction factor\n End SpecMap");
        assertTrue(CValues.nativeStringsWithinLibmRounding(after, want), after + "\nC gives:\n" + want);
    }

    /** specmap.c {@code astLoadSpecMap}: a missing or unknown conversion is AST__BADIN. */
    @Test
    void aLoadNeedsKnownConversions() {
        String head = ChannelText.text(
                " Begin SpecMap", "    Nin = 1", " IsA Mapping", "    Nspec = 2", "    Spec1 = \"FRTOWV\"");
        AstException e =
                assertThrows(AstException.class, () -> ChannelText.read(head + ChannelText.text(" End SpecMap")));
        assertEquals(AstException.AST__BADIN, e.getStatus());
        e = assertThrows(
                AstException.class,
                () -> ChannelText.read(head + ChannelText.text("    Spec2 = \"XXTOYY\"", " End SpecMap")));
        assertEquals(AstException.AST__BADIN, e.getStatus());
    }

    /** specmap.c {@code Equal} compares the conversions and their arguments. */
    @Test
    void equality() {
        SpecMap spec1 = spec("FRTOWV");
        spec1.specAdd("LKF2HL", new double[] {1.0, 0.5});
        SpecMap spec2 = spec("FRTOWV");
        spec2.specAdd("LKF2HL", new double[] {1.0, 0.5});
        assertTrue(spec1.astEqual(spec2));
        SpecMap spec3 = spec("FRTOWV");
        spec3.specAdd("LKF2HL", new double[] {2.0, 0.5});
        assertFalse(spec1.astEqual(spec3));
    }
}
