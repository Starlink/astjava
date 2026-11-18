package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of the unit tests of Rust {@code mappings/dssmap.rs}, with expected values from the
 * C probe {@code probes/dssmap_probe.c}. C has no public DssMap constructor, so every DssMap here
 * is read from the C fixture {@code serialisation/c_dssmap.ast} (the plate solution of Rust's
 * {@code make_test_dssmap}), some with one item removed or its value replaced.
 *
 * <p>The plate solution uses {@code sin}, {@code cos}, {@code tan}, {@code atan} and {@code
 * atan2}, whose last bit can differ from C's maths library ({@code Math.tan} of the plate
 * declination does here), so transformed values are compared with C's to within libm rounding
 * ({@link CValues#withinLibmRounding}), each given the inputs C was given; every other part of the
 * text must match exactly.
 */
class DssMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("dssmap_probe");

    /** The pixel positions the probe transforms forward. */
    private static final double[] XS = {1000.0, 1.0, 500.0, 4000.0, 2000.0, BAD, 100.0};

    private static final double[] YS = {2000.0, 1.0, 500.0, 4000.0, 3000.0, 100.0, BAD};

    /** The sky positions (radians) the probe transforms in the inverse direction. */
    private static final double[] RAS = {2.83, 2.8, 5.97, 1.25, BAD, 2.85};

    private static final double[] DECS = {-1.05, -1.0, 1.05, 0.0, -1.0, BAD};

    private static final String FIXTURE = Fixtures.read("serialisation/c_dssmap.ast");

    /**
     * The size of the plate pixel coordinates of the image pixels here (CNPix of the fixture), to
     * which {@link #pixelEq} applies {@link CValues#LIBM_RTOL}.
     */
    private static final double PLATE_PIXELS = 4201.0;

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    /** Whether the fixture line {@code line} holds the item {@code key}, as the probe's {@code has_key}. */
    private static boolean hasKey(String line, String key) {
        return line.trim().startsWith(key + " =");
    }

    /**
     * The probe's {@code dss}: the DssMap the fixture describes, with the item {@code drop} left
     * out and the value of item {@code key} replaced by {@code value} (either may be null).
     */
    static Mapping dss(String drop, String key, String value) {
        StringBuilder sb = new StringBuilder();
        for (String line : FIXTURE.split("\n", -1)) {
            if (line.isEmpty() || (drop != null && hasKey(line, drop))) {
                continue;
            }
            if (key != null && hasKey(line, key)) {
                sb.append("    ")
                        .append(key)
                        .append(" = ")
                        .append(value)
                        .append(" \t")
                        .append(line.substring(line.indexOf('#')))
                        .append('\n');
            } else {
                sb.append(line).append('\n');
            }
        }
        return (Mapping) ChannelText.read(sb.toString());
    }

    static Mapping dss() {
        return dss(null, null, null);
    }

    /** The fixture with both CNPix items 4200, so that the probe's WinMaps give whole CNPix values. */
    private static Mapping dss4200() {
        String text = FIXTURE.replace("CNPix1 = 4201", "CNPix1 = 4200").replace("CNPix2 = 4201", "CNPix2 = 4200");
        return (Mapping) ChannelText.read(text);
    }

    private static <T extends Mapping> T inv(T m) {
        m.invert();
        return m;
    }

    private static CmpMap series(Mapping a, Mapping b) {
        return new CmpMap(a, b, true);
    }

    /** The WinMap x -> 2x - 0.5 on both axes. */
    private static WinMap win() {
        return new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {-0.5, -0.5}, new double[] {1.5, 1.5
                });
    }

    /** The WinMap x -> 2x on both axes. */
    private static WinMap winNoShift() {
        return new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {0.0, 0.0}, new double[] {2.0, 2.0});
    }

    /**
     * Whether the image pixel coordinate {@code got} matches C's {@code want}. {@code platepix}
     * gives the plate pixel coordinate, about 4200 here, less the corner offset (CNPix), so the
     * image pixel coordinate is compared to within libm rounding of the plate pixel coordinate: the
     * tolerance is {@link CValues#LIBM_RTOL} applied to the plate coordinate, about 4e-10 pixels.
     * That is too coarse to see an error the size of the one a wrong value of two pi makes, so
     * {@link #rightAscensionWrapsAsInC} checks the wrapped right ascensions to two units in the last
     * place.
     */
    private static boolean pixelEq(double got, double want) {
        if (got == BAD || want == BAD) {
            return got == want;
        }
        return Math.abs(got - want) <= CValues.LIBM_ATOL + CValues.LIBM_RTOL * (Math.abs(want) + PLATE_PIXELS);
    }

    /**
     * Requires each line {@code <dir> x y -> a b} of the probe case {@code name} to be what {@code
     * m} gives: ({@code x}, {@code y}) transformed forward ({@code fwd}) or in the inverse
     * direction ({@code inv}) is ({@code a}, {@code b}), sky coordinates to within libm rounding
     * and pixel coordinates as {@link #pixelEq} compares them.
     */
    private static void assertTransformsAsC(String name, Mapping m) {
        StringBuilder failures = new StringBuilder();
        int n = 0;
        for (String line : probe(name).split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(" ", -1);
            assertEquals(6, f.length, line);
            assertEquals("->", f[3], line);
            boolean forward = "fwd".equals(f[0]);
            double[][] out = m.tran2(
                    1, new double[] {Double.parseDouble(f[1])}, new double[] {Double.parseDouble(f[2])}, forward);
            double want0 = Double.parseDouble(f[4]);
            double want1 = Double.parseDouble(f[5]);
            boolean ok = forward != m.getInvert()
                    ? CValues.cValueEq(out[0][0], want0) && CValues.cValueEq(out[1][0], want1)
                    : pixelEq(out[0][0], want0) && pixelEq(out[1][0], want1);
            if (!ok) {
                failures.append(line)
                        .append(": got ")
                        .append(g(out[0][0]))
                        .append(' ')
                        .append(g(out[1][0]))
                        .append('\n');
            }
            n++;
        }
        assertEquals(XS.length * 2 + RAS.length, n, name);
        assertEquals("", failures.toString(), name);
    }

    /** Requires {@code got} to be the probe case {@code name}, numbers to within libm rounding. */
    private static void assertProbe(String name, String got) {
        String want = probe(name);
        assertTrue(CValues.nativeStringsWithinLibmRounding(got, want), name + ": got\n" + got + "C gives\n" + want);
    }

    /**
     * The probe's {@code simp}: the class of the simplified Mapping, its dump, and, for a Mapping
     * of two inputs, the transformations of the pixel positions in direction {@code fwd} by the
     * Mapping and by the simplified Mapping.
     */
    private static String simp(Mapping m, boolean fwd) {
        Mapping s = m.simplify();
        StringBuilder sb = new StringBuilder();
        sb.append("class ").append(s.astClass()).append('\n').append(ChannelText.dump(s));
        if (m.getNin() != 2) {
            return sb.toString();
        }
        String[] heads = {"-- transformations\n", "-- transformations simplified\n"};
        Mapping[] maps = {m, s};
        for (int k = 0; k < 2; k++) {
            sb.append(heads[k]);
            double[][] out = maps[k].tran2(XS.length, XS, YS, fwd);
            for (int i = 0; i < XS.length; i++) {
                sb.append(g(out[0][i])).append(' ').append(g(out[1][i])).append('\n');
            }
        }
        return sb.toString();
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

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /** dssmap_basic_properties: two inputs and outputs, not inverted, the plate centre as read. */
    @Test
    void dssmapBasicProperties() {
        Mapping dm = dss();
        assertEquals("DssMap", dm.astClass());
        assertEquals(2, dm.getNin());
        assertEquals(2, dm.getNout());
        assertFalse(dm.getInvert());
        assertTrue(dm.getTranForward());
        assertTrue(dm.getTranInverse());
        String dump = ChannelText.dump(dm);
        assertTrue(dump.contains("PltRA = 2.8272374655684112 \t"), dump);
        assertTrue(dump.contains("PltDec = -1.0518122540502668 \t"), dump);
        assertTrue(dump.contains("PltScl = 67.200000000000003 \t"), dump);
    }

    // ------------------------------------------------------------------
    // Transformations
    // ------------------------------------------------------------------

    /**
     * dssmap_forward_plate_centre, dssmap_forward_bad_propagation, dssmap_inverse_bad_propagation
     * and dssmap_reads_c_written_native_dump: pixel positions to the sky and back, and sky
     * positions to pixels, as C gives them; a bad coordinate gives bad values on both axes.
     */
    @Test
    void transformationsMatchC() {
        assertTransformsAsC("tran", dss());
        assertTransformsAsC("tran_cnpix_4200", dss(null, "CNPix1", "4200"));
    }

    /** dssmap_roundtrip_inverted: an inverted DssMap exchanges the two directions. */
    @Test
    void invertedTransformationsMatchC() {
        assertTransformsAsC("tran_inverted", inv(dss()));
    }

    /**
     * A plate centre just east of RA zero: pixel positions to its west have a negative right
     * ascension, to which C's {@code platepos} adds its own value of two pi, 6.28318530717959, a
     * little more than the double nearest to two pi: about four units in the last place of the
     * result. So the wrapped right ascensions, which C gives here to within libm rounding, must also
     * be within two units in the last place of C's (one for the {@code atan2} of the plate offset
     * and one for the rounding of the conversions).
     */
    @Test
    void rightAscensionWrapsAsInC() {
        Mapping wrapped = dss(null, "PltRA", "0.0001");
        assertTransformsAsC("tran_pltra_small", wrapped);
        int n = 0;
        for (String line : probe("tran_pltra_small").split("\n", -1)) {
            String[] f = line.split(" ", -1);
            if (!line.startsWith("fwd") || Double.parseDouble(f[1]) == BAD || Double.parseDouble(f[2]) == BAD) {
                continue;
            }
            double want = Double.parseDouble(f[4]);
            double[][] out = wrapped.tran2(
                    1, new double[] {Double.parseDouble(f[1])}, new double[] {Double.parseDouble(f[2])}, true);
            assertTrue(want > 6.2 && want < 2.0 * Math.PI, line);
            assertTrue(CValues.ulpClose(out[0][0], want, 2), line + ": got " + g(out[0][0]));
            n++;
        }
        assertEquals(5, n);
    }

    /**
     * Plate solution coefficients 14 to 19 are read and dumped but take no part in the
     * transformations: C's {@code platepos} and {@code platepix} leave out the magnitude and colour
     * terms.
     */
    @Test
    void magnitudeTermsAreIgnored() {
        assertTransformsAsC("load_amdx14_nonzero", dss(null, "AMDX14", "5"));
    }

    /** dssmap_roundtrip: pixel positions come back from the sky to well within a pixel. */
    @Test
    void dssmapRoundtrip() {
        Mapping dm = dss();
        double[] x = {500.0, 1000.0, 2000.0, 4000.0};
        double[] y = {500.0, 1000.0, 3000.0, 4000.0};
        double[][] sky = dm.tran2(4, x, y, true);
        double[][] back = dm.tran2(4, sky[0], sky[1], false);
        for (int i = 0; i < 4; i++) {
            assertTrue(Math.abs(x[i] - back[0][i]) < 1e-6, "x " + i);
            assertTrue(Math.abs(y[i] - back[1][i]) < 1e-6, "y " + i);
        }
    }

    /** dssmap_roundtrip_inverted. */
    @Test
    void dssmapRoundtripInverted() {
        Mapping dm = inv(dss());
        double[] x = {1500.0, 3000.0};
        double[] y = {2500.0, 3500.0};
        double[][] sky = dm.tran2(2, x, y, false);
        double[][] back = dm.tran2(2, sky[0], sky[1], true);
        for (int i = 0; i < 2; i++) {
            assertTrue(Math.abs(x[i] - back[0][i]) < 1e-6, "x " + i);
            assertTrue(Math.abs(y[i] - back[1][i]) < 1e-6, "y " + i);
        }
    }

    /** The numerical rates of the forward transformation, as mapping.c {@code Rate} gives them. */
    @Test
    void ratesMatchC() {
        Mapping dm = dss();
        StringBuilder sb = new StringBuilder();
        for (int ax1 = 1; ax1 <= 2; ax1++) {
            for (int ax2 = 1; ax2 <= 2; ax2++) {
                sb.append("rate ")
                        .append(ax1)
                        .append(' ')
                        .append(ax2)
                        .append(' ')
                        .append(g(dm.rate(new double[] {1000.0, 2000.0}, ax1, ax2)))
                        .append('\n');
            }
        }
        assertProbe("rate", sb.toString());
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    /**
     * dssmap_equal_same, dssmap_equal_different_ppo and dssmap_equal_different_invert: C's {@code
     * Equal} (dssmap.c) compares, with {@code astEQUAL}, the plate centre, the pixel offsets and
     * sizes, PPO3, PPO6 and the first 13 coefficients on each axis, and requires the same Invert
     * attribute; the plate scale and the other coefficients are not compared.
     */
    @Test
    void equalityMatchesC() {
        Mapping m = dss();
        String[] keys = {
            "PltRA", "PltRA", "PltDec", "PltScl", "CNPix1", "CNPix2", "XPixSz", "YPixSz", "PPO1", "PPO2", "PPO3",
            "PPO4", "PPO5", "PPO6", "AMDX1", "AMDX13", "AMDX14", "AMDX19", "AMDY1", "AMDY13", "AMDY14", "AMDY19"
        };
        String[] values = {
            "2.8272374655684120",
            "2.8272374655685",
            "-1.05",
            "60",
            "4200",
            "4200",
            "25.3",
            "25.3",
            "1",
            "1",
            "105990",
            "1",
            "1",
            "105990",
            "67.15",
            "1.5e-12",
            "1",
            "1",
            "67.15",
            "1.4e-12",
            "1",
            "1"
        };
        StringBuilder sb = new StringBuilder()
                .append("same ")
                .append(m.astEqual(dss()) ? 1 : 0)
                .append("\ncopy ")
                .append(m.astEqual(m.copy()) ? 1 : 0)
                .append("\ninverted ")
                .append(m.astEqual(inv(dss())) ? 1 : 0)
                .append("\nboth_inverted ")
                .append(inv((Mapping) m.copy()).astEqual(inv(dss())) ? 1 : 0)
                .append("\nwinmap ")
                .append(m.astEqual(win()) ? 1 : 0)
                .append('\n');
        for (int i = 0; i < keys.length; i++) {
            boolean eq = m.astEqual(dss(null, keys[i], values[i]));
            sb.append(keys[i])
                    .append(' ')
                    .append(values[i])
                    .append(' ')
                    .append(eq ? 1 : 0)
                    .append('\n');
        }
        assertEquals(probe("equal"), sb.toString());
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    /**
     * dssmap_serialization_roundtrip, dssmap_serialization_inverted and
     * dssmap_serialization_transform_preserved: a DssMap read back from its dump is equal to it,
     * with the same dump and transformations.
     */
    @Test
    void dssmapSerializationRoundtrip() {
        for (boolean inverted : new boolean[] {false, true}) {
            Mapping dm = dss();
            if (inverted) {
                dm.invert();
            }
            String text = ChannelText.dump(dm);
            Mapping back = (Mapping) ChannelText.read(text);
            assertEquals("DssMap", back.astClass());
            assertEquals(inverted, back.getInvert());
            assertTrue(dm.astEqual(back));
            assertEquals(text, ChannelText.dump(back));
            for (boolean forward : new boolean[] {true, false}) {
                double[][] want = dm.tran2(RAS.length, RAS, DECS, forward);
                double[][] got = back.tran2(RAS.length, RAS, DECS, forward);
                assertTrue(Arrays.deepEquals(want, got), "inverted " + inverted + " forward " + forward);
            }
        }
    }

    /** C's dump of a DssMap is written back byte for byte, as C writes it. */
    @Test
    void cDumpIsWrittenBackAsC() {
        assertEquals(FIXTURE, ChannelText.dump(dss()));
    }

    /**
     * A copy holds its own plate solution: it is equal to the original, and the simplification of
     * a CmpMap holding the original leaves the original as it was.
     */
    @Test
    void aCopyIsEqual() {
        Mapping dm = dss4200();
        Mapping copy = (Mapping) dm.copy();
        assertTrue(copy.astEqual(dm));
        series(win(), dm).simplify();
        assertEquals(ChannelText.dump(copy), ChannelText.dump(dm));
    }

    /**
     * Rust {@code tests/test_mapping_load.rs} {@code a_dssmap_dump_needs_its_plate_solution}, with
     * C's messages: a dump missing a plate parameter, PPO3, PPO6 or one of the first 13
     * coefficients on an axis is AST__RDERR (dssmap.c {@code astLoadDssMap}); the other items
     * default to zero.
     */
    @Test
    void loadsWithAnItemMissingMatchC() {
        String[] keys = {
            "PltRA", "PltDec", "PltScl", "CNPix1", "CNPix2", "XPixSz", "YPixSz", "PPO1", "PPO2", "PPO3", "PPO4",
            "PPO5", "PPO6", "AMDX1", "AMDX2", "AMDX13", "AMDX14", "AMDX19", "AMDY1", "AMDY12", "AMDY13", "AMDY14",
            "AMDY19"
        };
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String result;
            try {
                result = "class " + dss(key, null, null).astClass() + "\n";
            } catch (AstException e) {
                result = report(e);
            }
            sb.append("-- ").append(key).append('\n').append(result);
        }
        assertEquals(withoutSource(probe("load_missing")), sb.toString());
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /** dssmap_simplified_returns_clone: a DssMap alone simplifies to itself. */
    @Test
    void dssmapSimplifiedAlone() {
        Mapping dm = dss();
        assertProbe("alone", simp(dm, true));
    }

    /**
     * dssmap_absorbs_preceding_winmap: a WinMap before a DssMap folds into its pixel offsets and
     * sizes when the new offsets are whole to within 0.01 of a pixel; also with the WinMap
     * inverted.
     */
    @Test
    void dssmapAbsorbsPrecedingWinmap() {
        assertProbe("winmap_then_dssmap_4200", simp(series(win(), dss4200()), true));
        assertProbe("inverted_winmap_then_dssmap_4200", simp(series(inv(win()), dss4200()), true));
    }

    /**
     * dssmap_inverted_absorbs_following_winmap: an inverted DssMap absorbs a WinMap after it, and
     * stays inverted.
     */
    @Test
    void dssmapInvertedAbsorbsFollowingWinmap() {
        assertProbe("inverted_dssmap_then_winmap", simp(series(inv(dss4200()), win()), false));
    }

    /**
     * dssmap_fractional_cnpix_not_merged: no merge when either new pixel offset is not whole to
     * within 0.01 of a pixel.
     */
    @Test
    void dssmapFractionalCnpixNotMerged() {
        assertProbe("winmap_then_dssmap_fractional", simp(series(win(), dss()), true));
        assertProbe("winmap_then_dssmap_fractional_cnpix2", simp(series(win(), dss(null, "CNPix1", "4200")), true));
        Mapping dm = dss();
        MergeList list = new MergeList(Arrays.asList(winNoShift(), dm), Arrays.asList(false, false));
        assertEquals(-1, dm.mapMerge(list, 1, true));
    }

    /**
     * dssmap_non_winmap_neighbour_not_merged: a ZoomMap, which a WinMap of zero shifts simplifies
     * to, is not absorbed; nor is a WinMap on the side the DssMap's own direction does not take.
     */
    @Test
    void dssmapNonWinmapNeighbourNotMerged() {
        assertProbe("winmap_no_shift_then_dssmap_4200", simp(series(winNoShift(), dss4200()), true));
        assertProbe("zoommap_then_dssmap", simp(series(new ZoomMap(2, 2.0), dss4200()), true));
        assertProbe("dssmap_then_winmap_4200", simp(series(dss4200(), win()), true));
        assertProbe("winmap_then_inverted_dssmap", simp(series(win(), inv(dss4200())), false));
        Mapping dm = dss4200();
        MergeList list = new MergeList(Arrays.asList(new ZoomMap(2, 2.0), dm), Arrays.asList(false, false));
        assertEquals(-1, dm.mapMerge(list, 1, true));
    }

    /** dssmap_parallel_not_merged: nothing merges in parallel. */
    @Test
    void dssmapParallelNotMerged() {
        assertProbe("parallel", simp(new CmpMap(win(), dss4200(), false), true));
        Mapping dm = dss4200();
        MergeList list = new MergeList(Arrays.asList(win(), dm), Arrays.asList(false, false));
        assertEquals(-1, dm.mapMerge(list, 1, false));
    }

    /**
     * dssmap_zero_scale_winmap_not_merged: a WinMap with a zero scale is not absorbed (C's {@code
     * MapMerge} requires both scales to be non-zero and neither term bad).
     */
    @Test
    void dssmapZeroScaleWinmapNotMerged() {
        Mapping dm = dss4200();
        WinMap zero = new WinMap(new double[] {-0.5, -0.5}, new double[] {0.0, 2.0});
        MergeList list = new MergeList(Arrays.asList(zero, dm), Arrays.asList(false, false));
        assertEquals(-1, dm.mapMerge(list, 1, true));
        assertEquals(2, list.size());
    }

    /**
     * The inverse of a series CmpMap of an inverted DssMap and an inverted WinMap applies the WinMap
     * forward and then the DssMap forward, so the two merge as C merges them. As in C, the WinMap,
     * which the CmpMap shares, is left with Invert 0, the value the list applied it with
     * (dssmap.c {@code MapMerge} annuls its pointer before the restore; docs/rust-c-differences.md,
     * "A DssMap merge leaves the WinMap's Invert attribute changed").
     */
    @Test
    void aMergeLeavesTheWinmapWithTheListsInvertValue() {
        WinMap w = inv(win());
        CmpMap c = series(inv(dss4200()), w);
        c.invert();
        int before = w.getI("Invert");
        Mapping s = c.simplify();
        String got = "before " + before + "\nclass " + s.astClass() + "\nafter " + w.getI("Invert") + "\n";
        assertEquals(probe("invert_attr_both_inverted"), got);
        assertProbe("inverted_dssmap_then_winmap_both_inverted", simp(c, true));
    }

    /** When the merge fails C restores the WinMap's Invert attribute, as this library does. */
    @Test
    void aFailedMergeRestoresTheWinmapInvertAttribute() {
        WinMap w = inv(win());
        CmpMap c = series(inv(dss()), w);
        c.invert();
        int before = w.getI("Invert");
        Mapping s = c.simplify();
        String got = "before " + before + "\nclass " + s.astClass() + "\nafter " + w.getI("Invert") + "\n";
        assertEquals(probe("invert_attr_no_merge"), got);
    }

    /** A DssMap has no constructor but the loader and the one its merge uses. */
    @Test
    void noPublicConstructor() {
        assertEquals(0, dss().getClass().getConstructors().length);
        assertThrows(AstException.class, () -> dss("PltRA", null, null));
    }
}
