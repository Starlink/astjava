package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_grismmap.rs} (after the GrismMap sections of C's {@code
 * ast_tester}), with the cases of the C probe {@code probes/grismmap_probe.c}: attribute values,
 * tests, clears and settings, a GrismMap held by a CmpMap, native dumps with each attribute set and
 * unset read back, transformations, a rate, equality, a split, and simplification beside ZoomMaps,
 * WinMaps, a ShiftMap and other GrismMaps.
 *
 * <p>Rust's {@code GrismMap::new}, which sets all eight attributes, is {@link #typical} and {@link
 * #grism}, which set them through the jniast setters, as the probe sets them with {@code
 * astGrismMap}; Rust's {@code default_map} is the jniast constructor. The five Rust tests that call
 * {@code try_merge}, which this library does not have, simplify the series or parallel CmpMap they
 * describe instead.
 */
class GrismMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("grismmap_probe");

    private static final String[] ATTRS = {
        "GrismNR", "GrismNRP", "GrismWaveR", "GrismAlpha", "GrismG", "GrismM", "GrismEps", "GrismTheta"
    };

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    /** Requires {@code got} to be C's text for {@code name}, its numbers within libm rounding. */
    private static void assertProbe(String name, String got) {
        String want = probe(name);
        if (!want.equals(got) && !CValues.nativeStringsWithinLibmRounding(got, want)) {
            assertEquals(want, got, name);
        }
    }

    /** A GrismMap with every attribute set, as Rust's {@code GrismMap::new} makes it. */
    private static GrismMap grism(
            double nr, double nrp, double waveR, double alpha, double g, int m, double eps, double theta) {
        GrismMap map = new GrismMap();
        map.setGrismNR(nr);
        map.setGrismNRP(nrp);
        map.setGrismWaveR(waveR);
        map.setGrismAlpha(alpha);
        map.setGrismG(g);
        map.setGrismM(m);
        map.setGrismEps(eps);
        map.setGrismTheta(theta);
        return map;
    }

    /** The probe's {@code typical}: a GrismMap with every attribute set, wavelengths in Angstrom. */
    private static GrismMap typical() {
        return grism(1.5, -1e-6, 5000.0, 0.1, 2e-4, 1, 0.02, 0.03);
    }

    private static GrismMap typicalM(int m) {
        GrismMap g = typical();
        g.setGrismM(m);
        return g;
    }

    private static <T extends Mapping> T inv(T m) {
        m.invert();
        return m;
    }

    private static ZoomMap zoom(double f) {
        return new ZoomMap(1, f);
    }

    private static WinMap win(double scale, double shift) {
        return new WinMap(
                1, new double[] {0.0}, new double[] {1.0}, new double[] {shift}, new double[] {shift + scale});
    }

    private static CmpMap series(Mapping a, Mapping b) {
        return new CmpMap(a, b, true);
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    // ------------------------------------------------------------------
    // tests/test_grismmap.rs: construction and attributes
    // ------------------------------------------------------------------

    @Test
    void defaultGrismmapAttributes() {
        GrismMap gm = new GrismMap();
        assertEquals(1, gm.getNin());
        assertEquals(1, gm.getNout());
        assertEquals(1.0, gm.getGrismNR());
        assertEquals(0.0, gm.getGrismNRP());
        assertEquals(5000.0, gm.getGrismWaveR());
        assertEquals(0.0, gm.getGrismAlpha());
        assertEquals(0.0, gm.getGrismG());
        assertEquals(0, gm.getGrismM());
        assertEquals(0.0, gm.getGrismEps());
        assertEquals(0.0, gm.getGrismTheta());
        assertFalse(gm.getInvert());
    }

    @Test
    void customGrismmapAttributes() {
        GrismMap gm = typical();
        assertEquals(1.5, gm.getGrismNR());
        assertEquals(-1e-6, gm.getGrismNRP());
        assertEquals(5000.0, gm.getGrismWaveR());
        assertEquals(0.1, gm.getGrismAlpha());
        assertEquals(2e-4, gm.getGrismG());
        assertEquals(1, gm.getGrismM());
        assertEquals(0.02, gm.getGrismEps());
        assertEquals(0.03, gm.getGrismTheta());
    }

    // ------------------------------------------------------------------
    // tests/test_grismmap.rs: transformations, with C's values (probe case transformations)
    // ------------------------------------------------------------------

    /** C's value for {@code value} in the probe's {@code transformations} line {@code prefix value ->}. */
    private static double cValue(String prefix, double value) {
        String head = prefix + " " + g(value) + " -> ";
        for (String line : probe("transformations").split("\n", -1)) {
            if (line.startsWith(head)) {
                return Double.parseDouble(line.substring(head.length()));
            }
        }
        throw new IllegalStateException("no line " + head);
    }

    private static void assertC(double want, double got, String what) {
        assertTrue(CValues.withinLibmRounding(got, want), what + ": got " + got + ", C gives " + want);
    }

    @Test
    void forwardTransformTypicalGrism() {
        double wavelength = typical().tran1(1, new double[] {0.0}, true)[0];
        assertNotEquals(BAD, wavelength);
        assertTrue(wavelength > 0.0, "wavelength should be positive");
        assertC(cValue("fwd", 0.0), wavelength, "forward of 0");
    }

    @Test
    void forwardInverseRoundtrip() {
        GrismMap gm = typical();
        double[] params = {0.0, 0.01, -0.01, 0.05};
        double[] wavelengths = gm.tran1(4, params, true);
        double[] recovered = gm.tran1(4, wavelengths, false);
        for (int i = 0; i < 4; i++) {
            assertC(cValue("fwd", params[i]), wavelengths[i], "forward of " + params[i]);
            assertNotEquals(BAD, recovered[i], "point " + i + ": inverse returned BAD");
            assertTrue(Math.abs(params[i] - recovered[i]) < 1e-12, "point " + i + ": round trip error");
        }
    }

    @Test
    void inverseForwardRoundtrip() {
        GrismMap gm = typical();
        double[] wavelengths = {4900.0, 5000.0, 5100.0, 5200.0};
        double[] params = gm.tran1(4, wavelengths, false);
        double[] recovered = gm.tran1(4, params, true);
        for (int i = 0; i < 4; i++) {
            assertC(cValue("inv", wavelengths[i]), params[i], "inverse of " + wavelengths[i]);
            assertNotEquals(BAD, recovered[i], "point " + i + ": forward returned BAD");
            assertTrue(Math.abs(wavelengths[i] - recovered[i]) < 1e-10, "point " + i + ": round trip error");
        }
    }

    @Test
    void badValuePropagation() {
        double[] out = typical().tran1(2, new double[] {BAD, 0.0}, true);
        assertEquals(BAD, out[0]);
        assertNotEquals(BAD, out[1]);
    }

    /** A GrismMap with no attribute set has {@code k2} zero, so its forward transformation is bad. */
    @Test
    void degenerateParamsProduceBad() {
        double[] out = new GrismMap().tran1(2, new double[] {0.0, 0.5}, true);
        assertEquals(BAD, out[0]);
        assertEquals(BAD, out[1]);
    }

    @Test
    void invertedGrismmapSwapsDirections() {
        GrismMap gm = typical();
        double[] input = {0.0};
        double fwd = gm.tran1(1, input, true)[0];
        gm.invert();
        assertTrue(gm.getInvert());
        double invFwd = gm.tran1(1, input, true)[0];
        double invInv = gm.tran1(1, input, false)[0];
        assertTrue(Math.abs(fwd - invInv) < 1e-15, "inverted inverse should match forward");
        double recovered = gm.tran1(1, new double[] {invFwd}, false)[0];
        assertTrue(Math.abs(input[0] - recovered) < 1e-12, "round trip through inverted map failed");
    }

    // ------------------------------------------------------------------
    // tests/test_grismmap.rs: equality
    // ------------------------------------------------------------------

    @Test
    void equalSameParams() {
        assertTrue(typical().astEqual(typical()));
    }

    @Test
    void notEqualDifferentNr() {
        GrismMap other = typical();
        other.setGrismNR(1.6);
        assertFalse(typical().astEqual(other));
    }

    @Test
    void notEqualDifferentM() {
        assertFalse(typical().astEqual(typicalM(-1)));
    }

    @Test
    void notEqualDifferentInversion() {
        assertFalse(typical().astEqual(inv(typical())));
    }

    // ------------------------------------------------------------------
    // tests/test_grismmap.rs: native serialisation
    // ------------------------------------------------------------------

    private static Mapping readBack(Mapping m) {
        return (Mapping) ChannelText.read(ChannelText.dump(m));
    }

    @Test
    void nativeSerializationRoundtrip() {
        GrismMap gm = typical();
        Mapping parsed = readBack(gm);
        assertTrue(gm.astEqual(parsed));
        double[] input = {0.0, 0.01, -0.01};
        double[] orig = gm.tran1(3, input, true);
        double[] back = parsed.tran1(3, input, true);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(orig[i] - back[i]) < 1e-15, "transform mismatch after round trip at point " + i);
        }
    }

    @Test
    void nativeSerializationRoundtripInverted() {
        GrismMap gm = inv(typical());
        assertTrue(gm.astEqual(readBack(gm)));
    }

    @Test
    void nativeSerializationRoundtripDefaults() {
        GrismMap gm = new GrismMap();
        assertTrue(gm.astEqual(readBack(gm)));
    }

    // ------------------------------------------------------------------
    // tests/test_grismmap.rs: simplification
    // ------------------------------------------------------------------

    /**
     * Rust's {@code inverse_cancellation}: a GrismMap followed by the inverse of one whose
     * attributes equal its own but for GrismM, which must differ, simplifies to a UnitMap.
     */
    @Test
    void inverseCancellation() {
        Mapping merged = series(typical(), inv(typicalM(-1))).simplify();
        assertInstanceOf(UnitMap.class, merged);
        assertEquals(1, merged.getNin());
        assertEquals(1, merged.getNout());
        assertEquals(42.0, merged.tran1(1, new double[] {42.0}, true)[0]);
    }

    @Test
    void noCancelSameDirection() {
        Mapping s = series(new GrismMap(), new GrismMap()).simplify();
        assertInstanceOf(CmpMap.class, s, "same direction should not cancel");
    }

    /** C's {@code CanMerge} cancels GrismMaps only when their GrismM attributes differ. */
    @Test
    void noCancelSameMOppositeDirection() {
        Mapping s = series(typical(), inv(typical())).simplify();
        assertInstanceOf(CmpMap.class, s, "same M opposite direction should not cancel per C library");
    }

    @Test
    void noCancelDifferentAttrs() {
        GrismMap other = typicalM(-1);
        other.setGrismNR(1.6);
        Mapping s = series(typical(), inv(other)).simplify();
        assertInstanceOf(CmpMap.class, s, "different attrs should not cancel");
    }

    @Test
    void noMergeParallel() {
        Mapping s = new CmpMap(typical(), inv(typicalM(-1)), false).simplify();
        assertInstanceOf(CmpMap.class, s, "parallel merge should not be supported");
    }

    // ------------------------------------------------------------------
    // tests/test_grismmap.rs: rate and dump
    // ------------------------------------------------------------------

    @Test
    void rateAtZero() {
        double rate = typical().rate(new double[] {0.0}, 1, 1);
        assertTrue(Double.isFinite(rate));
        assertNotEquals(0.0, rate);
    }

    /** A GrismMap made with no attribute values writes the defaults commented out, as C does. */
    @Test
    void defaultGrismmapDumpsAsCDoes() {
        String want = String.join(
                        "\n",
                        " Begin GrismMap \t# Map 1-d coordinates using a spectral disperser",
                        "    Nin = 1 \t# Number of input coordinates",
                        " IsA Mapping \t# Mapping between coordinate systems",
                        "#   GrmNR = 1 \t# Refractive index at the ref. wavelength",
                        "#   GrmNRP = 0 \t# Rate of change of refractive index",
                        "#   GrmWR = 5000 \t# Ref. wavelength",
                        "#   GrmAlp = 0 \t# Angle of incidence of incoming light",
                        "#   GrmG = 0 \t# Grating ruling density",
                        "#   GrmM = 0 \t# The interference order",
                        "#   GrmEps = 0 \t# Angle between grating normal and dispersion plane",
                        "#   GrmTh = 0 \t# Angle between detector normal and reference ray",
                        " End GrismMap")
                + "\n";
        assertEquals(want, ChannelText.dump(new GrismMap()));
    }

    // ------------------------------------------------------------------
    // Probe: attributes
    // ------------------------------------------------------------------

    /** The probe's {@code attributes}: each attribute, whether it is set, and its value. */
    private static String attributes(Mapping m) {
        StringBuilder sb = new StringBuilder();
        for (String a : ATTRS) {
            sb.append(a)
                    .append(' ')
                    .append(m.test(a) ? 1 : 0)
                    .append(' ')
                    .append(m.getC(a))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * The attributes of a GrismMap made with none set, with all set, with all set then cleared,
     * and with each set to its default, as C gives them.
     */
    @Test
    void attributesMatchC() {
        assertEquals(probe("attributes_default"), attributes(new GrismMap()));
        assertEquals(probe("attributes_set"), attributes(typical()));
        GrismMap cleared = typical();
        cleared.clear("GrismNR,GrismNRP,GrismWaveR,GrismAlpha,GrismG,GrismM,GrismEps,GrismTheta");
        assertEquals(probe("attributes_cleared"), attributes(cleared));
        GrismMap defaults = new GrismMap();
        defaults.set("GrismNR=1,GrismNRP=0,GrismWaveR=5000,GrismAlpha=0,GrismG=0,GrismM=0,GrismEps=0,GrismTheta=0");
        assertEquals(probe("attributes_set_to_defaults"), attributes(defaults));
    }

    /** GrismM is set from a double, truncated toward zero, as C's {@code SetAttrib} sets it. */
    @Test
    void aFractionalOrderIsTruncated() {
        GrismMap gm = new GrismMap();
        gm.set("GrismM=2.7");
        String m27 = gm.getC("GrismM");
        gm.set("GrismM=-2.7");
        String mMinus27 = gm.getC("GrismM");
        gm.setD("GrismM", 3.5);
        String got = "2.7 -> " + m27 + "\n-2.7 -> " + mMinus27 + "\nsetd 3.5 -> " + gm.getC("GrismM") + "\ngetd "
                + g(gm.getD("GrismM")) + "\n";
        assertEquals(probe("set_m_fraction"), got);
    }

    /** C's unset values, {@code AST__BAD} and {@code INT_MAX}, set as values, leave an attribute unset. */
    @Test
    void theUnsetValuesUnsetTheAttribute() {
        GrismMap gm = typical();
        gm.setGrismNR(BAD);
        gm.setD("GrismTheta", BAD);
        assertEquals(probe("set_bad_unsets"), attributes(gm));
        GrismMap gm2 = typical();
        gm2.setGrismM(Integer.MAX_VALUE);
        assertEquals(probe("set_m_int_max_unsets"), attributes(gm2));
    }

    private static String report(AstException e) {
        StringBuilder sb = new StringBuilder();
        for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        return sb.append("status ").append(e.getStatus()).append('\n').toString();
    }

    /** The probe case without the lines naming the C source of an error. */
    private static String withoutSource(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.contains("AST: Error in routine") && !line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void aSettingThatIsNotANumberIsRefused() {
        AstException e = assertThrows(AstException.class, () -> new GrismMap().set("GrismNR=abc"));
        assertEquals(withoutSource(probe("set_not_a_number")), report(e));
    }

    /**
     * A GrismMap held by a CmpMap cannot have an attribute set or cleared, as C's cannot once
     * cloned, but can be inverted; a copy of it can be changed.
     */
    @Test
    void aSharedGrismMapIsImmutable() {
        GrismMap gm = typical();
        CmpMap c = series(gm, zoom(2.0));
        String errors = report(assertThrows(AstException.class, () -> gm.setD("GrismNR", 2.0)))
                + report(assertThrows(AstException.class, () -> gm.clear("GrismM")));
        String nr = gm.getC("GrismNR");
        gm.invert();
        GrismMap copy = (GrismMap) gm.copy();
        copy.setD("GrismNR", 2.0);
        String got = errors + "GrismNR " + nr + "\nInvert " + gm.getI("Invert") + "\ncopy GrismNR "
                + copy.getC("GrismNR") + "\n";
        assertEquals(withoutSource(probe("shared_set")), got);
        assertTrue(gm.isShared(), "the CmpMap " + c.astClass() + " holds the GrismMap");
        assertThrows(AstException.class, () -> gm.setGrismG(1.0));
    }

    // ------------------------------------------------------------------
    // Probe: native dumps
    // ------------------------------------------------------------------

    /** The probe's {@code tran}: the forward transformation of {@code xf}, the inverse of {@code xi}. */
    private static String tran(Mapping m, double xf, double xi) {
        return tranLine("fwd", m, xf, true) + tranLine("inv", m, xi, false);
    }

    private static String tranLine(String label, Mapping m, double x, boolean forward) {
        int n = m.getNin();
        double[] in = new double[n];
        java.util.Arrays.fill(in, x);
        double[] out = m.tranN(1, n, in, forward, n);
        StringBuilder sb = new StringBuilder(label);
        for (double v : in) {
            sb.append(' ').append(g(v));
        }
        sb.append(" ->");
        for (double v : out) {
            sb.append(' ').append(g(v));
        }
        return sb.append('\n').toString();
    }

    private static void assertRoundTrips(String name, Mapping m, double xf, double xi) {
        String dump = ChannelText.dump(m);
        Mapping back = (Mapping) ChannelText.read(dump);
        String got = dump + "-- read back\n" + ChannelText.dump(back) + "-- equal " + (m.astEqual(back) ? 1 : 0) + "\n"
                + "-- transformations\n" + tran(m, xf, xi) + "-- transformations read back\n" + tran(back, xf, xi);
        assertProbe(name, got);
    }

    /**
     * Dumps of GrismMaps with no attribute set, all set, inverted, each set alone, each cleared
     * from a GrismMap with all set, a negative order, and a simplified one, each read back,
     * as C writes and reads them.
     */
    @Test
    void nativeDumpsMatchC() {
        assertRoundTrips("dump_default", new GrismMap(), 0.01, 5100.0);
        assertRoundTrips("dump_all_set", typical(), 0.01, 5100.0);
        assertRoundTrips("dump_inverted", inv(typical()), 5100.0, 0.01);
        String[] one = {
            "GrismNR=1.5",
            "GrismNRP=-1.0E-6",
            "GrismWaveR=5000",
            "GrismAlpha=0.1",
            "GrismG=2.0E-4",
            "GrismM=1",
            "GrismEps=0.02",
            "GrismTheta=0.03"
        };
        for (int i = 0; i < ATTRS.length; i++) {
            GrismMap gm = new GrismMap();
            gm.set(one[i]);
            assertRoundTrips("dump_only_" + ATTRS[i], gm, 0.01, 5100.0);
        }
        for (String a : ATTRS) {
            GrismMap gm = typical();
            gm.clear(a);
            assertRoundTrips("dump_without_" + a, gm, 0.01, 5100.0);
        }
        assertRoundTrips("dump_negative_order", typicalM(-2), 0.01, 5100.0);
        assertRoundTrips("dump_simplified", typical().simplify(), 0.01, 5100.0);
    }

    /** A GrismMap splits off whole, keeping its Invert attribute, as C's base {@code MapSplit} does. */
    @Test
    void aSplitIsTheGrismMap() {
        int[] out = new int[1];
        Mapping s = inv(typical()).mapSplit(new int[] {1}, out);
        assertEquals(probe("split"), "output " + out[0] + "\n" + ChannelText.dump(s));
    }

    // ------------------------------------------------------------------
    // Probe: transformations, rate and equality
    // ------------------------------------------------------------------

    @Test
    void transformationsMatchC() {
        StringBuilder sb = new StringBuilder();
        GrismMap gm = typical();
        double[] gp = {0.0, 0.01, -0.01, 0.05, BAD};
        double[] wl = {4900.0, 5000.0, 5100.0, 5200.0, BAD};
        lines(sb, "fwd", gp, gm.tran1(5, gp, true));
        lines(sb, "inv", wl, gm.tran1(5, wl, false));
        double[] far = {1.0e6, -1.0e6};
        lines(sb, "inv", far, gm.tran1(2, far, false));
        GrismMap d = new GrismMap();
        double[] dp = {0.0, 0.5};
        lines(sb, "default fwd", dp, d.tran1(2, dp, true));
        double[] wl2 = {4900.0, 5000.0};
        lines(sb, "default inv", wl2, d.tran1(2, wl2, false));
        GrismMap e = typical();
        e.setD("GrismEps", 1.5707963267948966);
        lines(sb, "eps 90 fwd", dp, e.tran1(2, dp, true));
        GrismMap b = typical();
        b.setD("GrismG", 1.0);
        lines(sb, "sinbeta_r over 1 fwd", dp, b.tran1(2, dp, true));
        assertProbe("transformations", sb.toString());
    }

    private static void lines(StringBuilder sb, String label, double[] in, double[] out) {
        for (int i = 0; i < in.length; i++) {
            sb.append(label)
                    .append(' ')
                    .append(g(in[i]))
                    .append(" -> ")
                    .append(g(out[i]))
                    .append('\n');
        }
    }

    /** C's GrismMap has no rate of its own: the base class's numerical estimate is used. */
    @Test
    void ratesMatchC() {
        GrismMap gm = typical();
        String got = "fwd " + g(gm.rate(new double[] {0.0}, 1, 1)) + "\n" + "inv "
                + g(inv(gm).rate(new double[] {5100.0}, 1, 1)) + "\n";
        assertProbe("rate", got);
    }

    /**
     * C compares the attributes as stored, so a GrismMap with no attribute set differs from one
     * with each set to its default; GrismMaps with different Invert attributes are never equal.
     */
    @Test
    void equalityMatchesC() {
        GrismMap a = typical();
        GrismMap b = typical();
        b.setD("GrismNR", 1.6);
        GrismMap defaults = new GrismMap();
        defaults.set("GrismNR=1,GrismNRP=0,GrismWaveR=5000,GrismAlpha=0,GrismG=0,GrismM=0,GrismEps=0,GrismTheta=0");
        String got = "same " + eq(a, typical()) + "\n"
                + "copy " + eq(a, a.copy()) + "\n"
                + "different_nr " + eq(a, b) + "\n"
                + "different_m " + eq(a, typicalM(-1)) + "\n"
                + "inverted " + eq(a, inv(typical())) + "\n"
                + "both_inverted " + eq(inv(typical()), inv(typical())) + "\n"
                + "default " + eq(new GrismMap(), new GrismMap()) + "\n"
                + "default_vs_set_to_defaults " + eq(new GrismMap(), defaults) + "\n"
                + "not_a_grismmap " + eq(a, zoom(2.0)) + "\n";
        assertEquals(probe("equal"), got);
    }

    private static int eq(AstObject a, AstObject b) {
        return a.astEqual(b) ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // Probe: simplification
    // ------------------------------------------------------------------

    private static void assertSimplifies(String name, Mapping map, double xf, double xi) {
        Mapping s = map.simplify();
        String got = "class " + s.astClass() + "\n" + ChannelText.dump(s) + "-- transformations\n" + tran(map, xf, xi)
                + "-- transformations simplified\n" + tran(s, xf, xi);
        assertProbe(name, got);
    }

    /**
     * A GrismMap applied forward followed by a ZoomMap, or a ZoomMap followed by a GrismMap
     * applied inverted, merge into a GrismMap whose GrismG, GrismNRP and GrismWaveR describe the
     * scaled wavelengths; the other orders do not merge.
     */
    @Test
    void zoomMapNeighboursMergeAsC() {
        assertSimplifies("zoom_after_forward", series(typical(), zoom(2.0)), 0.01, 10200.0);
        assertSimplifies("zoom_before_inverted", series(zoom(0.5), inv(typical())), 10200.0, 0.01);
        assertSimplifies("inverted_zoom_before_inverted", series(inv(zoom(2.0)), inv(typical())), 10200.0, 0.01);
        assertSimplifies("zoom_before_forward", series(zoom(2.0), typical()), 0.01, 5100.0);
        assertSimplifies("zoom_after_inverted", series(inv(typical()), zoom(2.0)), 5100.0, 0.01);
        assertSimplifies("zooms_both_sides", series(series(zoom(0.5), inv(typical())), zoom(3.0)), 10200.0, 0.03);
        assertSimplifies("zoom_forward_zoom", series(series(zoom(3.0), typical()), zoom(2.0)), 0.01, 10200.0);
        GrismMap gm = typical();
        CmpMap c = series(gm, zoom(2.0));
        gm.invert();
        assertSimplifies("grism_inverted_after_combining", c, 0.01, 10200.0);
        assertSimplifies("zoom_after_default", series(new GrismMap(), zoom(2.0)), 0.01, 10200.0);
        assertSimplifies("zoom_after_two_grisms", series(series(typical(), inv(typicalM(2))), zoom(2.0)), 0.01, 0.02);
    }

    /**
     * C takes the factor of a ZoomMap whose own Invert attribute is set in the direction opposite
     * to the one it is applied in, so the merged GrismMap does not transform as the pair did: a
     * ZoomMap inverted before it is combined, after a GrismMap, and a ZoomMap inverted after it is
     * combined, on either side. The port merges as C does.
     */
    @Test
    void anInvertedZoomMapMergesAsCMergesIt() {
        assertSimplifies("inverted_zoom_after_forward", series(typical(), inv(zoom(2.0))), 0.01, 2550.0);
        ZoomMap z = zoom(2.0);
        CmpMap c = series(typical(), z);
        z.invert();
        assertSimplifies("zoom_inverted_after_combining", c, 0.01, 10200.0);
        ZoomMap z2 = zoom(2.0);
        CmpMap c2 = series(z2, inv(typical()));
        z2.invert();
        assertSimplifies("zoom_inverted_after_combining_before", c2, 10200.0, 0.01);
    }

    /**
     * A GrismMap does not merge with a WinMap or a ShiftMap; a WinMap that only scales becomes a
     * ZoomMap, with which the GrismMap then merges.
     */
    @Test
    void winMapAndShiftMapNeighboursSimplifyAsC() {
        assertSimplifies("winmap_scale_after_forward", series(typical(), win(2.0, 0.0)), 0.01, 10200.0);
        assertSimplifies("winmap_after_forward", series(typical(), win(2.0, 10.0)), 0.01, 10210.0);
        assertSimplifies("winmap_scale_before_inverted", series(win(0.5, 0.0), inv(typical())), 10200.0, 0.01);
        assertSimplifies("winmap_before_inverted", series(win(0.5, -5.0), inv(typical())), 10210.0, 0.01);
        assertSimplifies("shiftmap_after_forward", series(typical(), new ShiftMap(new double[] {10.0})), 0.01, 5110.0);
    }

    /**
     * GrismMaps applied in opposite directions whose attributes are equal but for GrismM, which
     * must differ, cancel to a UnitMap, as C's {@code CanMerge} decides, though they are not
     * inverses of each other; others, and GrismMaps in parallel or alone, are left as they are.
     */
    @Test
    void grismMapNeighboursSimplifyAsC() {
        assertSimplifies("inverse_cancel", series(typical(), inv(typicalM(-1))), 0.01, 0.01);
        assertSimplifies("inverse_cancel_lower", series(inv(typicalM(-1)), typical()), 5100.0, 5100.0);
        assertSimplifies("inverse_same_order", series(typical(), inv(typical())), 0.01, 0.01);
        GrismMap other = typicalM(-1);
        other.setD("GrismNR", 1.6);
        assertSimplifies("inverse_different_nr", series(typical(), inv(other)), 0.01, 0.01);
        assertSimplifies("same_direction", series(typical(), typicalM(-1)), 0.0, 0.0);
        assertSimplifies("parallel", new CmpMap(typical(), inv(typicalM(-1)), false), 0.01, 0.01);
        assertSimplifies("parallel_zoom", new CmpMap(typical(), zoom(2.0), false), 0.01, 0.01);
        assertSimplifies("alone", typical(), 0.01, 5100.0);
        assertSimplifies("alone_inverted", inv(typical()), 5100.0, 0.01);
        GrismMap gm = typical();
        assertSimplifies("shared_pair", series(gm, inv((GrismMap) gm.copy())), 0.01, 0.01);
    }

    /** The transformations of the probe's merged pairs are those of the pairs. */
    @Test
    void aMergedGrismMapTransformsAsThePair() {
        CmpMap pair = series(typical(), zoom(2.0));
        Mapping merged = pair.simplify();
        assertInstanceOf(GrismMap.class, merged);
        double[][] in = {{0.0}, {0.02}};
        double[][] a = transformRows(pair, true, in);
        double[][] b = transformRows(merged, true, in);
        for (int i = 0; i < 2; i++) {
            assertTrue(CValues.withinLibmRounding(b[0][i], a[0][i]));
        }
    }
}
