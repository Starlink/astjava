package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_tranmap.rs}, with the cases of the C probe {@code
 * probes/tranmap_probe.c}: native dumps with each component inverted, when the TranMap is made and
 * afterwards, and with a LutMap and a MathMap inside, each read back; simplification of a TranMap
 * alone and beside other Mappings; rates; equality; decomposition; splits; and the constructor's
 * errors.
 *
 * <p>Rust's {@code no_merge_parallel_tranmaps} and {@code no_merge_with_non_tranmap} call {@code
 * try_merge}, which this library does not have; here they simplify the parallel CmpMap and the
 * series CmpMap they describe, against C. Rust's {@code clone_produces_independent_copy} uses
 * {@link AstObject#copy}.
 */
class TranMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("tranmap_probe");

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    private static ZoomMap zoom(int n, double f) {
        return new ZoomMap(n, f);
    }

    private static <T extends Mapping> T inv(T m) {
        m.invert();
        return m;
    }

    private static TranMap tz(double f1, double f2) {
        return new TranMap(zoom(1, f1), zoom(1, f2));
    }

    private static double at(Mapping m, double x, boolean forward) {
        return m.tran1(1, new double[] {x}, forward)[0];
    }

    private static final double[] TABLE = {0.0, 1.0, 4.0, 9.0, 16.0};

    private static LutMap lut() {
        return new LutMap(TABLE, 1.0, 1.0);
    }

    private static MathMap square(boolean simp) {
        MathMap m = new MathMap(1, 1, new String[] {"y = x * x"}, new String[] {"x = sqrt( y )"});
        if (simp) {
            m.setSimpFI(true);
            m.setSimpIF(true);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Construction and basic properties
    // ------------------------------------------------------------------

    @Test
    void basicConstruction() {
        TranMap tm = tz(2.0, 3.0);
        assertEquals(1, tm.getNin());
        assertEquals(1, tm.getNout());
        assertFalse(tm.getInvert());
    }

    @Test
    void constructionDimensionMismatchNin() {
        AstException e = assertThrows(AstException.class, () -> new TranMap(zoom(1, 2.0), zoom(2, 3.0)));
        assertEquals(AstException.AST__INNCO, e.getStatus());
    }

    @Test
    void constructionDimensionMismatchNout() {
        AstException e = assertThrows(AstException.class, () -> new TranMap(zoom(1, 2.0), zoom(2, 3.0)));
        assertEquals(AstException.AST__INNCO, e.getStatus());
    }

    // ------------------------------------------------------------------
    // Forward and inverse transforms
    // ------------------------------------------------------------------

    @Test
    void forwardUsesMap1() {
        assertEquals(10.0, at(tz(2.0, 3.0), 5.0, true), 1e-14);
    }

    @Test
    void inverseUsesMap2() {
        assertEquals(3.0, at(tz(2.0, 3.0), 9.0, false), 1e-14);
    }

    @Test
    void forwardAndInverseAreDifferent() {
        TranMap tm = tz(2.0, 3.0);
        double fwd = at(tm, 5.0, true);
        assertEquals(10.0 / 3.0, at(tm, fwd, false), 1e-14);
    }

    @Test
    void inversionSwapsTransforms() {
        TranMap tm = tz(2.0, 3.0);
        tm.invert();
        assertTrue(tm.getInvert());
        assertEquals(3.0, at(tm, 9.0, true), 1e-14);
        assertEquals(10.0, at(tm, 5.0, false), 1e-14);
    }

    @Test
    void badInputForward() {
        assertEquals(BAD, at(tz(2.0, 3.0), BAD, true));
    }

    @Test
    void badInputInverse() {
        assertEquals(BAD, at(tz(2.0, 3.0), BAD, false));
    }

    @Test
    void multidimTransform() {
        TranMap tm = new TranMap(zoom(2, 2.0), zoom(2, 5.0));
        assertEquals(2, tm.getNin());
        assertEquals(2, tm.getNout());
        double[][] fwd = tm.tran2(1, new double[] {3.0}, new double[] {4.0}, true);
        assertEquals(6.0, fwd[0][0], 1e-14);
        assertEquals(8.0, fwd[1][0], 1e-14);
        double[][] inv = tm.tran2(1, new double[] {3.0}, new double[] {4.0}, false);
        assertEquals(0.6, inv[0][0], 1e-14);
        assertEquals(0.8, inv[1][0], 1e-14);
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    @Test
    void equalSameComponents() {
        assertTrue(tz(2.0, 3.0).equalTo(tz(2.0, 3.0)));
    }

    @Test
    void equalDifferentComponents() {
        assertFalse(tz(2.0, 3.0).equalTo(tz(2.0, 4.0)));
    }

    @Test
    void equalInvertedVsSwapped() {
        TranMap tm1 = inv(tz(2.0, 3.0));
        TranMap tm2 = new TranMap(inv(zoom(1, 3.0)), inv(zoom(1, 2.0)));
        assertTrue(tm1.equalTo(tm2));
    }

    @Test
    void notEqualToNonTranmap() {
        assertFalse(tz(2.0, 3.0).equalTo(zoom(1, 2.0)));
    }

    /** Every comparison of the probe's {@code equal} case gives C's {@code astEqual}. */
    @Test
    void equalityMatchesC() {
        TranMap a = tz(2.0, 3.0);
        TranMap b = tz(2.0, 3.0);
        TranMap c = tz(2.0, 4.0);
        TranMap d = new TranMap(inv(zoom(1, 3.0)), inv(zoom(1, 2.0)));
        TranMap ainv = inv((TranMap) a.copy());
        ZoomMap z2 = zoom(1, 2.0);
        TranMap e = new TranMap(z2, zoom(1, 3.0));
        z2.invert();
        String got = "same_components " + flag(a.equalTo(b)) + "\n"
                + "different_components " + flag(a.equalTo(c)) + "\n"
                + "inverted_vs_swapped " + flag(ainv.equalTo(d)) + "\n"
                + "swapped_vs_inverted " + flag(d.equalTo(ainv)) + "\n"
                + "not_a_tranmap " + flag(a.equalTo(zoom(1, 2.0))) + "\n"
                + "inverted_vs_plain " + flag(ainv.equalTo(a)) + "\n"
                + "component_inverted_after " + flag(e.equalTo(a)) + "\n";
        assertEquals(probe("equal"), got);
        // The comparison sets the components' Invert attributes back.
        assertTrue(z2.getInvert());
        assertTrue(d.getMap1().getInvert());
    }

    private static int flag(boolean b) {
        return b ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    @Test
    void simplifyEqualComponents() {
        Mapping simp = tz(2.0, 2.0).simplify();
        assertInstanceOf(ZoomMap.class, simp);
        assertEquals(10.0, at(simp, 5.0, true), 1e-14);
        assertEquals(2.5, at(simp, 5.0, false), 1e-14);
    }

    @Test
    void simplifyInvertNormalize() {
        TranMap tm = inv(new TranMap(zoom(1, 2.0), zoom(1, 0.5)));
        Mapping simp = tm.simplify();
        assertInstanceOf(TranMap.class, simp);
        assertFalse(simp.getInvert());
        assertEquals(10.0, at(simp, 5.0, true), 1e-14);
    }

    @Test
    void simplifyComponentSimplification() {
        CmpMap cmp1 = new CmpMap(zoom(1, 2.0), zoom(1, 3.0), true);
        CmpMap cmp2 = new CmpMap(zoom(1, 0.5), zoom(1, 2.0), true);
        Mapping simp = new TranMap(cmp1, cmp2).simplify();
        assertEquals(30.0, at(simp, 5.0, true), 1e-14);
    }

    @Test
    void mergeAdjacentTranmaps() {
        TranMap tm1 = new TranMap(zoom(1, 2.0), zoom(1, 0.5));
        TranMap tm2 = new TranMap(zoom(1, 3.0), zoom(1, 1.0 / 3.0));
        Mapping simp = new CmpMap(tm1, tm2, true).simplify();
        assertEquals(30.0, at(simp, 5.0, true), 1e-14);
        assertEquals(30.0, at(simp, 5.0, false), 1e-12);
    }

    /** Two TranMaps in parallel do not merge (Rust {@code no_merge_parallel_tranmaps}). */
    @Test
    void noMergeParallelTranmaps() {
        assertSimplifies("simp_pair_parallel", new CmpMap(tz(2.0, 3.0), tz(5.0, 7.0), false), 6.0);
    }

    /** A TranMap does not merge with a ZoomMap (Rust {@code no_merge_with_non_tranmap}). */
    @Test
    void noMergeWithNonTranmap() {
        assertSimplifies("simp_beside_zoommap", new CmpMap(tz(2.0, 3.0), zoom(1, 4.0), true), 6.0);
    }

    @Test
    void cloneProducesIndependentCopy() {
        TranMap tm1 = tz(2.0, 3.0);
        TranMap tm2 = (TranMap) tm1.copy();
        tm2.invert();
        assertFalse(tm1.getInvert());
        assertTrue(tm2.getInvert());
    }

    /** The simplified {@code tranmap_invert_normalize} fixture is dumped as C's {@code .simp}. */
    @Test
    void simplifyInvertNormalizeFixtureRoundtrip() {
        String input = Fixtures.read("simplify/tranmap_invert_normalize.map");
        String ref = Fixtures.read("simplify/tranmap_invert_normalize.simp");
        Mapping simplified = ((Mapping) ChannelText.read(input)).simplify();
        assertTrue(((TranMap) simplified).equalTo((Mapping) ChannelText.read(ref)));
        assertEquals(ref, ChannelText.dump(simplified));
    }

    /** The error simplifying an encapsulated Mapping is passed up, as in C. */
    @Test
    void anErrorSimplifyingAComponentIsPassedUp() {
        TranMap map = new TranMap(failingComponent(), new UnitMap(2));
        AstException e = assertThrows(AstException.class, map::simplify);
        assertEquals(AstException.AST__WCSPA, e.getStatus());
    }

    /** Two TranMaps in series merge; an error doing so is passed up, as in C. */
    @Test
    void anErrorMergingTwoTranmapsIsPassedUp() {
        TranMap constants = new TranMap(
                new PermMap(2, new int[] {0, 0}, 2, new int[] {-1, -2}, new double[] {0.1, 0.2}), new UnitMap(2));
        TranMap conic = new TranMap(new WcsMap(2, WcsMap.AST__COE, 1, 2), new UnitMap(2));
        AstException e = assertThrows(AstException.class, () -> new CmpMap(constants, conic, true).simplify());
        assertEquals(AstException.AST__WCSPA, e.getStatus());
    }

    /**
     * A PermMap supplying constants to a conic WcsMap that lacks its PV2_1 parameter: simplifying
     * it raises an error that is not an undefined transformation.
     */
    private static CmpMap failingComponent() {
        return new CmpMap(
                new PermMap(2, new int[] {0, 0}, 2, new int[] {-1, -2}, new double[] {0.1, 0.2}),
                new WcsMap(2, WcsMap.AST__COE, 1, 2),
                true);
    }

    /** A merged TranMap's inverse applies the higher TranMap's inverse first, as the series does. */
    @Test
    void aMergedTranmapKeepsTheOrderOfTheInverses() {
        TranMap lower = new TranMap(new UnitMap(2), new MatrixMap(2, 2, new double[][] {{1.0, 2.0}, {0.0, 1.0}}));
        TranMap higher = new TranMap(new UnitMap(2), new MatrixMap(2, 2, new double[][] {{1.0, 0.0}, {3.0, 1.0}}));
        CmpMap map = new CmpMap(lower, higher, true);
        Mapping simplified = map.simplify();
        assertInstanceOf(TranMap.class, simplified);
        double[][] expected = map.tran2(1, new double[] {1.0}, new double[] {2.0}, false);
        double[][] got = simplified.tran2(1, new double[] {1.0}, new double[] {2.0}, false);
        assertEquals(expected[0][0], got[0][0], 1e-12);
        assertEquals(expected[1][0], got[1][0], 1e-12);
    }

    /** TranMaps of 2-to-3 then 3-to-4 Mappings merge, their inverse Mappings combining lower first. */
    @Test
    void tranmapsOfDifferingDimensionsMerge() {
        TranMap lower = new TranMap(
                new PermMap(2, new int[] {1, 2}, 3, new int[] {1, 2, -1}, new double[] {5.0}),
                new PermMap(2, new int[] {2, 1}, 3, new int[] {2, 1, -1}, new double[] {6.0}));
        TranMap higher = new TranMap(
                new PermMap(3, new int[] {1, 2, 3}, 4, new int[] {1, 2, 3, -1}, new double[] {5.0}),
                new PermMap(3, new int[] {3, 2, 1}, 4, new int[] {3, 2, 1, -1}, new double[] {6.0}));
        assertInstanceOf(TranMap.class, new CmpMap(lower, higher, true).simplify());
    }

    // ------------------------------------------------------------------
    // Simplification against the probe
    // ------------------------------------------------------------------

    private static void assertSimplifies(String name, Mapping map, double x) {
        Mapping s = map.simplify();
        String got = "class " + s.astClass() + "\n" + ChannelText.dump(s) + "-- transformations\n" + trans(map, x)
                + "-- transformations simplified\n" + trans(s, x);
        assertEquals(probe(name), got, name);
    }

    private static String trans(Mapping m, double x) {
        if (m.getNin() != 1) {
            return "";
        }
        return "fwd " + CFormat.format("%.17g", x) + " -> " + CFormat.format("%.17g", at(m, x, true)) + "\n" + "inv "
                + CFormat.format("%.17g", x) + " -> " + CFormat.format("%.17g", at(m, x, false)) + "\n";
    }

    /** A TranMap alone simplifies its components, and is left as it is when they do not change. */
    @Test
    void aTranMapAloneSimplifiesAsC() {
        assertSimplifies("simp_plain", tz(2.0, 3.0), 6.0);
        assertSimplifies("simp_inverted", inv(tz(2.0, 3.0)), 6.0);
        assertSimplifies("simp_unequal_mathmaps", new TranMap(square(false), square(false)), 2.5);
    }

    /**
     * A TranMap whose components are equal becomes one of them, unless they are the same object,
     * which C inverts as the second component before combining it with itself as the first.
     */
    @Test
    void equalComponentsCollapseAsC() {
        assertSimplifies("simp_equal", tz(2.0, 2.0), 6.0);
        assertSimplifies("simp_equal_inverted_tranmap", inv(tz(2.0, 2.0)), 6.0);
        assertSimplifies("simp_equal_inverted_components", new TranMap(inv(zoom(1, 2.0)), inv(zoom(1, 2.0))), 6.0);
        ZoomMap z = zoom(1, 2.0);
        assertSimplifies("simp_same_object", new TranMap(z, z), 6.0);
        assertSimplifies("simp_equal_lutmaps", new TranMap(lut(), lut()), 2.5);
        assertSimplifies("simp_equal_mathmaps", new TranMap(square(true), square(true)), 2.5);
    }

    /**
     * C replaces a TranMap of equal components that were inverted when it was made with a copy of
     * the first, applied forward, so the simplified Mapping transforms the other way; Java does as
     * C does (docs/rust-c-differences.md).
     */
    @Test
    void equalInvertedComponentsCollapseToTheForwardComponentAsC() {
        assertSimplifies("simp_equal_inverted_mathmaps", new TranMap(inv(square(true)), inv(square(true))), 2.5);
        assertSimplifies("simp_equal_inverted_lutmaps", new TranMap(inv(lut()), inv(lut())), 2.5);
    }

    /** A TranMap beside the Mappings C merges, and beside other TranMaps. */
    @Test
    void aTranMapBesideOtherMappingsSimplifiesAsC() {
        assertSimplifies(
                "simp_lutmap_beside_its_inverse", new CmpMap(new TranMap(lut(), zoom(1, 3.0)), inv(lut()), true), 2.5);
        assertSimplifies("simp_pair_second_inverted", new CmpMap(tz(2.0, 3.0), inv(tz(5.0, 7.0)), true), 6.0);
        assertSimplifies("simp_pair_inverted_series", inv(new CmpMap(tz(2.0, 3.0), tz(5.0, 7.0), true)), 6.0);
        TranMap t = new TranMap(zoom(1, 2.0), zoom(1, 3.0));
        assertSimplifies("simp_pair_shared_tranmap", new CmpMap(t, t, true), 6.0);
    }

    // ------------------------------------------------------------------
    // Native dumps
    // ------------------------------------------------------------------

    private static void assertRoundTrips(String name, Mapping m, double x) {
        String dump = ChannelText.dump(m);
        Mapping back = (Mapping) ChannelText.read(dump);
        String got = dump + "-- read back\n" + ChannelText.dump(back) + "-- transformations\n" + trans(m, x)
                + "-- transformations read back\n" + trans(back, x);
        assertEquals(probe(name), got, name);
    }

    @Test
    void serializationRoundtrip() {
        TranMap tm = tz(2.0, 3.0);
        String text = ChannelText.dump(tm);
        assertTrue(text.contains("TranMap"));
        assertTrue(text.contains("MapA"));
        assertTrue(text.contains("MapB"));
        Mapping restored = (Mapping) ChannelText.read(text);
        assertEquals(at(tm, 5.0, true), at(restored, 5.0, true), 1e-14);
        assertEquals(at(tm, 5.0, false), at(restored, 5.0, false), 1e-14);
    }

    @Test
    void serializationRoundtripInverted() {
        TranMap tm = inv(tz(2.0, 3.0));
        String text = ChannelText.dump(tm);
        assertTrue(text.contains("Invert"));
        Mapping restored = (Mapping) ChannelText.read(text);
        assertEquals(at(tm, 5.0, true), at(restored, 5.0, true), 1e-14);
    }

    @Test
    void serializationWithInvAFlag() {
        TranMap tm = new TranMap(inv(zoom(1, 2.0)), zoom(1, 3.0));
        String text = ChannelText.dump(tm);
        assertTrue(text.contains("InvA"));
        Mapping restored = (Mapping) ChannelText.read(text);
        assertEquals(at(tm, 10.0, true), at(restored, 10.0, true), 1e-14);
        assertEquals(5.0, at(tm, 10.0, true), 1e-14);
    }

    /** Each component inverted, when the TranMap is made or afterwards, dumps and reads back as in C. */
    @Test
    void nativeDumpsWithInvertedComponentsMatchC() {
        assertRoundTrips("dump_plain", tz(2.0, 3.0), 6.0);
        assertRoundTrips("dump_inva", new TranMap(inv(zoom(1, 2.0)), zoom(1, 3.0)), 6.0);
        assertRoundTrips("dump_invb", new TranMap(zoom(1, 2.0), inv(zoom(1, 3.0))), 6.0);
        assertRoundTrips("dump_both_inverted", new TranMap(inv(zoom(1, 2.0)), inv(zoom(1, 3.0))), 6.0);
        assertRoundTrips("dump_tranmap_inverted", inv(tz(2.0, 3.0)), 6.0);
        ZoomMap a = zoom(1, 2.0);
        ZoomMap b = zoom(1, 3.0);
        TranMap t = new TranMap(a, b);
        a.invert();
        b.invert();
        assertRoundTrips("dump_components_inverted_after", t, 6.0);
    }

    /** A LutMap and a MathMap inside a TranMap dump and read back as in C. */
    @Test
    void nativeDumpsOfLutMapAndMathMapComponentsMatchC() {
        assertRoundTrips("dump_lutmap_mathmap", new TranMap(lut(), square(false)), 2.5);
        assertRoundTrips("dump_lutmap_mathmap_inverted", new TranMap(inv(square(false)), inv(lut())), 2.5);
    }

    // ------------------------------------------------------------------
    // Rates, decomposition, splits and errors
    // ------------------------------------------------------------------

    /** The rate is the forward component's, in the orientation the TranMap gives it, as in C. */
    @Test
    void ratesMatchC() {
        WinMap w = new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {1.0, 3.0}, new double[] {3.0, 5.0});
        WinMap wi = inv((WinMap) w.copy());
        TranMap t = new TranMap(w, wi);
        double[] pos = {1.0, 2.0};
        StringBuilder sb = new StringBuilder();
        for (String dir : new String[] {"fwd", "inv"}) {
            for (int i = 1; i <= 2; i++) {
                for (int j = 1; j <= 2; j++) {
                    sb.append(dir)
                            .append(' ')
                            .append(i)
                            .append(' ')
                            .append(j)
                            .append(' ')
                            .append(CFormat.format("%.17g", t.rate(pos, i, j)))
                            .append('\n');
                }
            }
            t.invert();
        }
        TranMap z = tz(2.0, 3.0);
        sb.append(rateLine("zoom fwd", z.rate(new double[] {1.0}, 1, 1)));
        z.invert();
        sb.append(rateLine("zoom inv", z.rate(new double[] {1.0}, 1, 1)));
        TranMap m = new TranMap(square(false), zoom(1, 3.0));
        sb.append(rateLine("mathmap fwd", m.rate(new double[] {3.0}, 1, 1)));
        assertEquals(probe("rates"), sb.toString());
    }

    private static String rateLine(String label, double rate) {
        return label + " " + CFormat.format("%.17g", rate) + "\n";
    }

    /**
     * The components and the Invert values to use with them, swapped for an inverted TranMap, as
     * in the probe's {@code decompose} case; C leaves the series flag as it was, and it is false
     * here.
     */
    @Test
    void decomposeGivesComponentsAndInvertValuesLikeC() {
        ShiftMap shift = inv(new ShiftMap(new double[] {1.0}));
        TranMap map = new TranMap(zoom(1, 2.0), shift);
        boolean[] series = {true};
        boolean[] inverts = new boolean[2];
        Mapping[] d = map.decompose(series, inverts);
        assertInstanceOf(ZoomMap.class, d[0]);
        assertSame(shift, d[1]);
        assertFalse(series[0]);
        assertFalse(inverts[0]);
        assertTrue(inverts[1]);
        map.invert();
        d = map.decompose(series, inverts);
        assertSame(shift, d[0]);
        assertInstanceOf(ZoomMap.class, d[1]);
        assertFalse(inverts[0]);
        assertTrue(inverts[1]);
        assertTrue(probe("decompose").contains("forward ZoomMap ShiftMap series 7 inverts 0 1\n"));
        assertTrue(probe("decompose").contains("inverted ShiftMap ZoomMap series 7 inverts 0 1\n"));
    }

    /** The Invert values recorded in a loaded TranMap, not its components' own. */
    @Test
    void decomposeGivesRecordedInvertValuesNotComponentFlags() {
        String text = " Begin TranMap \t# Compound Transformation Mapping\n"
                + "    Nin = 1 \t# Number of input coordinates\n"
                + " IsA Mapping \t# Mapping between coordinate systems\n"
                + "    InvA = 1 \t# First Mapping used in inverse direction\n"
                + "    MapA = \t# Mapping for forward transformation\n"
                + "       Begin ZoomMap \t# Zoom about the origin\n"
                + "          Nin = 1 \t# Number of input coordinates\n"
                + "       IsA Mapping \t# Mapping between coordinate systems\n"
                + "          Zoom = 2 \t# Zoom factor\n"
                + "       End ZoomMap\n"
                + "    MapB = \t# Mapping for inverse transformation\n"
                + "       Begin ZoomMap \t# Zoom about the origin\n"
                + "          Nin = 1 \t# Number of input coordinates\n"
                + "       IsA Mapping \t# Mapping between coordinate systems\n"
                + "          Zoom = 0.5 \t# Zoom factor\n"
                + "       End ZoomMap\n"
                + " End TranMap\n";
        TranMap map = (TranMap) ChannelText.read(text);
        boolean[] inverts = new boolean[2];
        Mapping[] d = map.decompose(null, inverts);
        assertFalse(d[0].getInvert());
        assertTrue(inverts[0]);
        assertFalse(inverts[1]);
    }

    private static String split(Mapping m, int[] in) {
        int[] out = new int[m.getNout()];
        Mapping s = m.mapSplit(in, out);
        if (s == null) {
            return "split none\n";
        }
        StringBuilder sb = new StringBuilder("outputs");
        for (int i = 0; i < s.getNout(); i++) {
            sb.append(' ').append(out[i]);
        }
        return sb.append('\n').append(ChannelText.dump(s)).toString();
    }

    /** Splits of a TranMap, inverted or not, and one whose inverse cannot follow, as in C. */
    @Test
    void splitsMatchC() {
        ShiftMap f = new ShiftMap(new double[] {10.0, 20.0, 30.0});
        ShiftMap i = inv(new ShiftMap(new double[] {-10.0, -20.0, -30.0}));
        TranMap t = new TranMap(f, i);
        assertEquals(probe("split_shiftmaps"), split(t, new int[] {2}));
        t.invert();
        assertEquals(probe("split_inverted"), split(t, new int[] {3, 1}));
        int[] perm = {2, 1, 3};
        TranMap tp = new TranMap(f, new PermMap(3, perm, 3, perm, null));
        assertEquals(probe("split_mismatched"), split(tp, new int[] {1}));
    }

    private static String report(AstException e) {
        StringBuilder sb = new StringBuilder();
        for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        return sb.append("status ").append(e.getStatus()).append('\n').toString();
    }

    /** The probe case without the line naming the C source of the error. */
    private static String errorCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (String line : probe(name).split("\n", -1)) {
            if (!line.startsWith("!! ") && !line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static PolyMap fwdOnlyPolymap() {
        PolyMap pm = new PolyMap(1, 1, 1, new double[] {1.0, 1.0, 1.0}, 0, null);
        pm.setIterInverse(false);
        return pm;
    }

    /** The constructor refuses Mappings without the directions it needs, or that do not fit. */
    @Test
    void constructorErrorsMatchC() {
        assertEquals(
                errorCase("error_no_forward"),
                report(assertThrows(AstException.class, () -> new TranMap(inv(fwdOnlyPolymap()), zoom(1, 2.0)))));
        assertEquals(
                errorCase("error_no_inverse"),
                report(assertThrows(AstException.class, () -> new TranMap(zoom(1, 2.0), fwdOnlyPolymap()))));
        assertEquals(
                errorCase("error_nin"),
                report(assertThrows(AstException.class, () -> new TranMap(zoom(1, 2.0), zoom(2, 3.0)))));
        PermMap p = new PermMap(1, new int[] {1}, 2, new int[] {1, 1}, null);
        assertEquals(
                errorCase("error_nout"), report(assertThrows(AstException.class, () -> new TranMap(zoom(1, 2.0), p))));
    }
}
