package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.fromRows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_pcdmap.rs}, with the cases of the C probe {@code
 * probes/pcdmap_probe.c}: attribute values, tests, clears and settings, an axis out of range, a
 * PcdMap held by a CmpMap, native dumps with each attribute set and unset read back,
 * transformations, rates, a split, equality, and simplification of a PcdMap alone, beside a
 * UnitMap, its own inverse, ZoomMaps and PermMaps (C's {@code PcdZoom} and {@code PcdPerm}), and
 * in parallel.
 *
 * <p>Rust's {@code PcdMap::new} is the jniast constructor. The four Rust tests that call {@code
 * try_merge}, which this library does not have, simplify the series CmpMap they describe instead.
 */
class PcdMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("pcdmap_probe");

    private static final String[] ATTRS = {"Disco", "PcdCen", "PcdCen(1)", "PcdCen(2)"};

    /** The points the probe transforms. */
    private static final double[] XS = {0.1, 0.35, -0.25, 1.5, 0.3, BAD};

    private static final double[] YS = {0.2, -0.45, 0.05, -2.0, -0.2, 0.7};

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    /**
     * Requires {@code got} to be C's text for {@code name}, exactly: a PcdMap's arithmetic, and
     * that of the Mappings beside it here, uses no elementary function whose last bit could differ
     * from C's.
     */
    private static void assertProbe(String name, String got) {
        assertEquals(probe(name), got, name);
    }

    private static PcdMap pcd(double disco, double x, double y) {
        return new PcdMap(disco, new double[] {x, y});
    }

    /** The probe's {@code typical}: the PcdMap most cases use. */
    private static PcdMap typical() {
        return pcd(0.01, 0.3, -0.2);
    }

    private static <T extends Mapping> T inv(T m) {
        m.invert();
        return m;
    }

    private static ZoomMap zoom(double f) {
        return new ZoomMap(2, f);
    }

    /** A PermMap exchanging the two axes. */
    private static PermMap swap() {
        return new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
    }

    private static CmpMap series(Mapping a, Mapping b) {
        return new CmpMap(a, b, true);
    }

    private static CmpMap series3(Mapping a, Mapping b, Mapping c) {
        return series(series(a, b), c);
    }

    private static CmpMap parallel(Mapping a, Mapping b) {
        return new CmpMap(a, b, false);
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    // ------------------------------------------------------------------
    // tests/test_pcdmap.rs: construction
    // ------------------------------------------------------------------

    @Test
    void pcdmapNewBasic() {
        PcdMap pm = pcd(0.001, 100.0, 200.0);
        assertEquals(2, pm.getNin());
        assertEquals(2, pm.getNout());
        assertFalse(pm.getInvert());
        assertTrue(Math.abs(pm.getDisco() - 0.001) < 1e-15);
        assertTrue(Math.abs(pm.getPcdCen(1) - 100.0) < 1e-15);
        assertTrue(Math.abs(pm.getPcdCen(2) - 200.0) < 1e-15);
    }

    @Test
    void pcdmapZeroDisc() {
        assertEquals(0.0, pcd(0.0, 0.0, 0.0).getDisco());
    }

    // ------------------------------------------------------------------
    // tests/test_pcdmap.rs: transformations
    // ------------------------------------------------------------------

    private static double[] fwd(Mapping m, double x, double y) {
        double[][] out = m.tran2(1, new double[] {x}, new double[] {y}, true);
        return new double[] {out[0][0], out[1][0]};
    }

    private static double[] invTran(Mapping m, double x, double y) {
        double[][] out = m.tran2(1, new double[] {x}, new double[] {y}, false);
        return new double[] {out[0][0], out[1][0]};
    }

    @Test
    void pcdmapForwardAtCentre() {
        double[] out = fwd(pcd(0.5, 10.0, 20.0), 10.0, 20.0);
        assertTrue(Math.abs(out[0] - 10.0) < 1e-12);
        assertTrue(Math.abs(out[1] - 20.0) < 1e-12);
    }

    @Test
    void pcdmapForwardKnownPoint() {
        // dx = 10, dy = 0, r^2 = 100, f = 1 + 0.001 * 100 = 1.1.
        double[] out = fwd(pcd(0.001, 0.0, 0.0), 10.0, 0.0);
        assertTrue(Math.abs(out[0] - 11.0) < 1e-12);
        assertTrue(Math.abs(out[1]) < 1e-12);
    }

    @Test
    void pcdmapForwardWithCentre() {
        // dx = 3, dy = 0, r^2 = 9, f = 1 + 0.01 * 9 = 1.09.
        double[] out = fwd(pcd(0.01, 5.0, 5.0), 8.0, 5.0);
        assertTrue(Math.abs(out[0] - 8.27) < 1e-12);
        assertTrue(Math.abs(out[1] - 5.0) < 1e-12);
    }

    @Test
    void pcdmapForwardBadValue() {
        double[] out = fwd(pcd(0.001, 0.0, 0.0), BAD, 5.0);
        assertEquals(BAD, out[0]);
        assertEquals(BAD, out[1]);
    }

    @Test
    void pcdmapInverseRoundtrip() {
        PcdMap pm = pcd(0.001, 1.0, -2.0);
        double[] xs = {5.0, 10.0, 0.5};
        double[] ys = {3.0, -7.0, 0.5};
        double[][] f = pm.tran2(3, xs, ys, true);
        double[][] back = pm.tran2(3, f[0], f[1], false);
        for (int i = 0; i < 3; i++) {
            // The SLA_UNPCD approximation is not exact: for this coefficient and points up to 12
            // from the centre the error is up to about 1e-5.
            assertTrue(Math.abs(back[0][i] - xs[i]) < 1e-4, "axis 0, point " + i);
            assertTrue(Math.abs(back[1][i] - ys[i]) < 1e-4, "axis 1, point " + i);
        }
    }

    @Test
    void pcdmapInverseBadValue() {
        double[] out = invTran(pcd(0.001, 0.0, 0.0), 5.0, BAD);
        assertEquals(BAD, out[0]);
        assertEquals(BAD, out[1]);
    }

    @Test
    void pcdmapInvertSwapsDirection() {
        PcdMap pm = pcd(0.001, 0.0, 0.0);
        assertTrue(Math.abs(fwd(pm, 10.0, 0.0)[0] - 11.0) < 1e-12);
        pm.invert();
        assertTrue(pm.getInvert());
        assertTrue(Math.abs(fwd(pm, 10.0, 0.0)[0] - 11.0) > 1e-4);
    }

    @Test
    void pcdmapMultiplePoints() {
        PcdMap pm = pcd(0.001, 0.0, 0.0);
        double[][] out = pm.tran2(4, new double[] {0.0, 10.0, 0.0, 10.0}, new double[] {0.0, 0.0, 10.0, 10.0}, true);
        double[][] want = {{0.0, 11.0, 0.0, 12.0}, {0.0, 0.0, 11.0, 12.0}};
        for (int i = 0; i < 4; i++) {
            assertTrue(Math.abs(out[0][i] - want[0][i]) < 1e-12, "point " + i);
            assertTrue(Math.abs(out[1][i] - want[1][i]) < 1e-12, "point " + i);
        }
    }

    // ------------------------------------------------------------------
    // tests/test_pcdmap.rs: equality
    // ------------------------------------------------------------------

    @Test
    void pcdmapEqualSame() {
        assertTrue(pcd(0.001, 1.0, 2.0).astEqual(pcd(0.001, 1.0, 2.0)));
    }

    @Test
    void pcdmapEqualDifferentDisc() {
        assertFalse(pcd(0.001, 1.0, 2.0).astEqual(pcd(0.002, 1.0, 2.0)));
    }

    @Test
    void pcdmapEqualDifferentCentre() {
        assertFalse(pcd(0.001, 1.0, 2.0).astEqual(pcd(0.001, 1.0, 3.0)));
    }

    @Test
    void pcdmapEqualDifferentInversion() {
        assertFalse(pcd(0.001, 1.0, 2.0).astEqual(inv(pcd(0.001, 1.0, 2.0))));
    }

    @Test
    void pcdmapNotEqualToUnitmap() {
        assertFalse(pcd(0.001, 0.0, 0.0).astEqual(new UnitMap(2)));
    }

    // ------------------------------------------------------------------
    // tests/test_pcdmap.rs: simplification
    // ------------------------------------------------------------------

    @Test
    void pcdmapDiscZeroSimplifiesToUnitmap() {
        Mapping simp = pcd(0.0, 5.0, 3.0).simplify();
        assertInstanceOf(UnitMap.class, simp);
        assertEquals(2, simp.getNin());
    }

    @Test
    void pcdmapNonzeroDiscStaysPcdmap() {
        assertInstanceOf(PcdMap.class, pcd(0.001, 0.0, 0.0).simplify());
    }

    @Test
    void pcdmapMergeWithInverseCancels() {
        PcdMap pm = pcd(0.001, 1.25, -0.75);
        assertInstanceOf(UnitMap.class, series(pm, inv((PcdMap) pm.copy())).simplify());
    }

    @Test
    void pcdmapMergeSameDirectionNoCancel() {
        PcdMap pm = pcd(0.001, 1.0, 2.0);
        assertInstanceOf(CmpMap.class, series(pm, (Mapping) pm.copy()).simplify());
    }

    @Test
    void pcdmapMergeDifferentDiscNoCancel() {
        assertInstanceOf(
                CmpMap.class,
                series(pcd(0.001, 0.0, 0.0), inv(pcd(0.002, 0.0, 0.0))).simplify());
    }

    @Test
    void pcdmapMergeWithUnitmap() {
        Mapping merged = series(pcd(0.001, 100.0, 100.0), new UnitMap(2)).simplify();
        PcdMap pm = assertInstanceOf(PcdMap.class, merged);
        assertTrue(Math.abs(pm.getDisco() - 0.001) < 1e-15);
    }

    // ------------------------------------------------------------------
    // tests/test_pcdmap.rs: serialisation
    // ------------------------------------------------------------------

    private static PcdMap readBack(PcdMap pm) {
        return assertInstanceOf(PcdMap.class, ChannelText.read(ChannelText.dump(pm)));
    }

    @Test
    void pcdmapSerializationRoundtrip() {
        PcdMap pm = pcd(0.001, 1.25, -0.75);
        assertTrue(pm.astEqual(readBack(pm)));
    }

    @Test
    void pcdmapSerializationInverted() {
        PcdMap pm = inv(pcd(0.005, 10.0, 20.0));
        PcdMap back = readBack(pm);
        assertTrue(pm.astEqual(back));
        assertTrue(back.getInvert());
    }

    @Test
    void pcdmapSerializationZeroDisc() {
        PcdMap pm = pcd(0.0, 0.0, 0.0);
        assertTrue(pm.astEqual(readBack(pm)));
    }

    @Test
    void pcdmapDeserializeFixture() {
        PcdMap pm = assertInstanceOf(PcdMap.class, ChannelText.read(Fixtures.read("simplify/pcd_zero_to_unit.map")));
        assertTrue(Math.abs(pm.getDisco()) < 1e-15);
        assertTrue(Math.abs(pm.getPcdCen(1)) < 1e-15);
        assertTrue(Math.abs(pm.getPcdCen(2)) < 1e-15);
    }

    /**
     * Rust {@code pcdmap_swap_past_an_inverted_zoommap_preserves_the_transformation}, from C's
     * {@code testpcdzoominverted} ({@code ast_tester/testmapping.c}): a PcdMap swapped past a
     * ZoomMap applied in either direction, towards an inverted PcdMap it merges with, leaves the
     * transformation as it was.
     */
    @Test
    void pcdmapSwapPastAnInvertedZoommapPreservesTheTransformation() {
        double[] xs = {0.1, -0.25};
        double[] ys = {0.2, 0.05};
        for (double z : new double[] {2.0, 0.5}) {
            for (boolean inverted : new boolean[] {false, true}) {
                PcdMap pm1 = pcd(0.01, 0.3, -0.2);
                PcdMap pm2 = inv(pcd(0.01, 0.3, -0.2));
                ZoomMap zm = zoom(z);
                if (inverted) {
                    zm.invert();
                }
                CmpMap outer = series(pm1, series(zm, pm2));
                Mapping simp = outer.simplify();
                double[][] before = outer.tran2(2, xs, ys, true);
                double[][] after = simp.tran2(2, xs, ys, true);
                for (int i = 0; i < 2; i++) {
                    double mag = Math.abs(before[0][i]) + Math.abs(before[1][i]);
                    double dev = Math.abs(after[0][i] - before[0][i]) + Math.abs(after[1][i] - before[1][i]);
                    assertTrue(
                            mag == 0.0 || dev <= 1.0e-9 * mag,
                            "zoom " + z + (inverted ? " inverted" : " forward") + ": point " + i + " gave ("
                                    + before[0][i] + ", " + before[1][i] + ") before simplification and ("
                                    + after[0][i] + ", " + after[1][i] + ") after");
                }
            }
        }
    }

    /**
     * Rust {@code tests/test_mapping.rs} {@code a_simplified_mapping_set_to_unit_simplifies_again}
     * (C's {@code ast_tester/testmapping.c} selfsimp-2): a PcdMap simplified from a series with a
     * UnitMap, its Disco then set to zero, simplifies again, to a UnitMap.
     */
    @Test
    void aSimplifiedPcdMapSetToZeroDiscoSimplifiesAgain() {
        PcdMap pm = assertInstanceOf(
                PcdMap.class, series(pcd(0.01, 0.0, 0.0), new UnitMap(2)).simplify());
        assertTrue(pm.getIsSimple());
        pm.setDisco(0.0);
        assertInstanceOf(UnitMap.class, pm.simplify());
    }

    // ------------------------------------------------------------------
    // jniast API
    // ------------------------------------------------------------------

    /** jniast checks the axis before it reaches C, and the centre array's length. */
    @Test
    void jniastChecksTheAxisAndTheCentreArray() {
        PcdMap pm = typical();
        assertThrows(IndexOutOfBoundsException.class, () -> pm.getPcdCen(3));
        assertThrows(IndexOutOfBoundsException.class, () -> pm.getPcdCen(0));
        assertThrows(IndexOutOfBoundsException.class, () -> pm.setPcdCen(3, 1.0));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new PcdMap(0.01, new double[] {1.0}));
        assertEquals("Supplied array has only 1 elements (needs 2)", e.getMessage());
        assertThrows(NullPointerException.class, () -> new PcdMap(0.01, null));
        pm.setDisco(-2.5e-3);
        pm.setPcdCen(2, 4.0);
        assertEquals(-2.5e-3, pm.getDisco());
        assertEquals(0.3, pm.getPcdCen(1));
        assertEquals(4.0, pm.getPcdCen(2));
    }

    // ------------------------------------------------------------------
    // Probe: attributes
    // ------------------------------------------------------------------

    /** Each attribute: whether it is set and its value as {@code getC} gives it. */
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

    @Test
    void attributesMatchC() {
        assertEquals(probe("attributes_constructed"), attributes(typical()));
        assertEquals(probe("attributes_constructed_bad"), attributes(pcd(BAD, BAD, 5.0)));
        PcdMap cleared = typical();
        cleared.clear("Disco,PcdCen");
        assertEquals(probe("attributes_cleared"), attributes(cleared));
        PcdMap cleared2 = typical();
        cleared2.clear("PcdCen(2)");
        assertEquals(probe("attributes_cleared_axis_2"), attributes(cleared2));
        PcdMap set = pcd(BAD, BAD, BAD);
        set.set("Disco=-2.5E-3,PcdCen(1)=1.25,PcdCen(2)= -7 ");
        assertEquals(probe("attributes_set"), attributes(set));
        PcdMap both = typical();
        both.set("PcdCen=3.5");
        assertEquals(probe("attributes_set_both_axes"), attributes(both));
        PcdMap bad = typical();
        bad.setD("Disco", BAD);
        bad.setD("PcdCen(1)", BAD);
        assertEquals(probe("set_bad_unsets"), attributes(bad));
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

    /** An axis of PcdCen other than 1 or 2 is refused by every attribute method, as in C. */
    @Test
    void anAxisOutOfRangeIsRefused() {
        PcdMap pm = typical();
        String got = "get 3\n"
                + report(assertThrows(AstException.class, () -> pm.getD("PcdCen(3)")))
                + "get 0\n"
                + report(assertThrows(AstException.class, () -> pm.getD("PcdCen(0)")))
                + "set 3\n"
                + report(assertThrows(AstException.class, () -> pm.setD("PcdCen(3)", 1.0)))
                + "set 0\n"
                + report(assertThrows(AstException.class, () -> pm.set("PcdCen(0)=1.0")))
                + "test 3\n"
                + report(assertThrows(AstException.class, () -> pm.test("PcdCen(3)")))
                + "clear 3\n"
                + report(assertThrows(AstException.class, () -> pm.clear("PcdCen(3)")))
                + attributes(pm);
        assertEquals(withoutSource(probe("axis_out_of_range")), got);
    }

    @Test
    void aSettingThatIsNotANumberIsRefused() {
        PcdMap pm = typical();
        String got = report(assertThrows(AstException.class, () -> pm.set("Disco=abc")))
                + report(assertThrows(AstException.class, () -> pm.set("PcdCen(1)=abc")))
                + attributes(pm);
        assertEquals(withoutSource(probe("set_not_a_number")), got);
    }

    /**
     * A PcdMap held by a CmpMap cannot have an attribute set or cleared, as C's cannot once
     * cloned (an axis out of range is reported first), but can be inverted; a copy of it can be
     * changed.
     */
    @Test
    void aSharedPcdMapIsImmutable() {
        PcdMap pm = typical();
        CmpMap c = series(pm, zoom(2.0));
        String errors = report(assertThrows(AstException.class, () -> pm.setD("Disco", 2.0)))
                + report(assertThrows(AstException.class, () -> pm.setD("PcdCen(2)", 2.0)))
                + report(assertThrows(AstException.class, () -> pm.set("PcdCen=2.0")))
                + report(assertThrows(AstException.class, () -> pm.clear("Disco")))
                + report(assertThrows(AstException.class, () -> pm.clear("PcdCen(1)")))
                + report(assertThrows(AstException.class, () -> pm.clear("PcdCen")))
                + "set 3\n"
                + report(assertThrows(AstException.class, () -> pm.setD("PcdCen(3)", 2.0)));
        String attrs = attributes(pm);
        pm.invert();
        PcdMap copy = (PcdMap) pm.copy();
        copy.setD("Disco", 2.0);
        copy.clear("PcdCen(1)");
        String got = errors + attrs + "Invert " + pm.getI("Invert") + "\n-- copy\n" + attributes(copy);
        assertEquals(withoutSource(probe("shared_set")), got);
        assertTrue(pm.isShared(), "the CmpMap " + c.astClass() + " holds the PcdMap");
        assertThrows(AstException.class, () -> pm.setDisco(1.0));
        assertThrows(AstException.class, () -> pm.setPcdCen(1, 1.0));
    }

    // ------------------------------------------------------------------
    // Probe: native dumps, transformations, rates, split, equality
    // ------------------------------------------------------------------

    /**
     * The probe's {@code tran}: the forward and inverse transformations of the points, given to
     * every pair of coordinates.
     */
    private static String tran(Mapping m) {
        int n = m.getNin();
        int np = XS.length;
        double[] in = new double[n * np];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < np; i++) {
                in[k * np + i] = k % 2 == 1 ? YS[i] : XS[i];
            }
        }
        StringBuilder sb = new StringBuilder();
        for (boolean forward : new boolean[] {true, false}) {
            double[] out = m.tranN(np, n, in, forward, n);
            for (int i = 0; i < np; i++) {
                sb.append(forward ? "fwd" : "inv");
                for (int k = 0; k < n; k++) {
                    sb.append(' ').append(g(in[k * np + i]));
                }
                sb.append(" ->");
                for (int k = 0; k < n; k++) {
                    sb.append(' ').append(g(out[k * np + i]));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void assertRoundTrips(String name, Mapping m) {
        String dump = ChannelText.dump(m);
        Mapping back = (Mapping) ChannelText.read(dump);
        String got = dump + "-- read back\n" + ChannelText.dump(back) + "-- equal " + (m.astEqual(back) ? 1 : 0) + "\n"
                + "-- transformations\n" + tran(m) + "-- transformations read back\n" + tran(back);
        assertProbe(name, got);
    }

    /**
     * Dumps of PcdMaps with every attribute set, inverted, none set, set to zero, each cleared in
     * turn, and simplified, each read back, as C writes and reads them.
     */
    @Test
    void nativeDumpsMatchC() {
        assertRoundTrips("dump_set", typical());
        assertRoundTrips("dump_inverted", inv(typical()));
        assertRoundTrips("dump_unset", pcd(BAD, BAD, BAD));
        assertRoundTrips("dump_zero", pcd(0.0, 0.0, 0.0));
        String[] clr = {"Disco", "PcdCen(1)", "PcdCen(2)"};
        String[] names = {"dump_without_Disco", "dump_without_PcdCen1", "dump_without_PcdCen2"};
        for (int i = 0; i < clr.length; i++) {
            PcdMap pm = typical();
            pm.clear(clr[i]);
            assertRoundTrips(names[i], pm);
        }
        assertRoundTrips("dump_simplified", typical().simplify());
    }

    @Test
    void transformationsMatchC() {
        assertProbe("transformations", tran(typical()));
        assertProbe("transformations_inverted", tran(inv(typical())));
        assertProbe("transformations_barrel", tran(pcd(-0.3, 0.1, 0.1)));
        PcdMap pm = pcd(5.0, 0.0, 0.0);
        double[] x = {1.0, 10.0, 100.0};
        double[] y = {0.0, 10.0, -100.0};
        double[][] out = pm.tran2(3, x, y, true);
        double[][] back = pm.tran2(3, out[0], out[1], false);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(g(x[i]))
                    .append(' ')
                    .append(g(y[i]))
                    .append(" -> ")
                    .append(g(out[0][i]))
                    .append(' ')
                    .append(g(out[1][i]))
                    .append(" -> ")
                    .append(g(back[0][i]))
                    .append(' ')
                    .append(g(back[1][i]))
                    .append('\n');
        }
        assertProbe("transformations_large", sb.toString());
    }

    /** C's PcdMap has no rate of its own: the base class's numerical estimate is used. */
    @Test
    void ratesMatchC() {
        PcdMap pm = typical();
        double[] at = {0.1, 0.2};
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2; i++) {
            for (int j = 1; j <= 2; j++) {
                sb.append("fwd ")
                        .append(i)
                        .append(' ')
                        .append(j)
                        .append(' ')
                        .append(g(pm.rate(at, i, j)))
                        .append('\n');
            }
        }
        pm.invert();
        for (int i = 1; i <= 2; i++) {
            for (int j = 1; j <= 2; j++) {
                sb.append("inv ")
                        .append(i)
                        .append(' ')
                        .append(j)
                        .append(' ')
                        .append(g(pm.rate(at, i, j)))
                        .append('\n');
            }
        }
        assertProbe("rate", sb.toString());
    }

    /**
     * C's PcdMap has no {@code MapSplit} of its own: one input of the two does not split off, and
     * both, in either order, give the PcdMap behind a PermMap, as the base class's does.
     */
    @Test
    void aSplitIsTheWholePcdMap() {
        int[] out = new int[2];
        Mapping one = inv(typical()).mapSplit(new int[] {1}, out);
        Mapping two = inv(typical()).mapSplit(new int[] {2, 1}, out);
        String got = "one input " + (one == null ? "null" : one.astClass()) + "\n" + "outputs " + out[0] + " " + out[1]
                + "\n" + ChannelText.dump(two);
        assertEquals(probe("split"), got);
    }

    private static int eq(AstObject a, AstObject b) {
        return a.astEqual(b) ? 1 : 0;
    }

    /**
     * C compares the attributes as stored, so a centre set to zero differs from an unset one; a
     * coefficient of 1e-13 equals zero to within {@code astEQUAL}, though not an unset one.
     */
    @Test
    void equalityMatchesC() {
        PcdMap a = typical();
        PcdMap cleared = pcd(0.01, 0.0, 0.0);
        cleared.clear("PcdCen");
        String got = "same " + eq(a, typical()) + "\n"
                + "copy " + eq(a, a.copy()) + "\n"
                + "different_disco " + eq(a, pcd(0.02, 0.3, -0.2)) + "\n"
                + "different_centre " + eq(a, pcd(0.01, 0.3, -0.3)) + "\n"
                + "near_disco " + eq(a, pcd(0.01 * (1.0 + 1.0e-12), 0.3, -0.2)) + "\n"
                + "inverted " + eq(a, inv(typical())) + "\n"
                + "both_inverted " + eq(inv(typical()), inv(typical())) + "\n"
                + "zero_vs_unset " + eq(pcd(0.01, 0.0, 0.0), cleared) + "\n"
                + "small_vs_unset_disco " + eq(pcd(1.0e-13, 0.0, 0.0), pcd(BAD, 0.0, 0.0)) + "\n"
                + "small_vs_zero_disco " + eq(pcd(1.0e-13, 0.0, 0.0), pcd(0.0, 0.0, 0.0)) + "\n"
                + "not_a_pcdmap " + eq(a, zoom(2.0)) + "\n";
        assertEquals(probe("equal"), got);
    }

    // ------------------------------------------------------------------
    // Probe: simplification
    // ------------------------------------------------------------------

    private static void assertSimplifies(String name, Mapping map) {
        Mapping s = map.simplify();
        String got = "class " + s.astClass() + "\n" + ChannelText.dump(s) + "-- transformations\n" + tran(map)
                + "-- transformations simplified\n" + tran(s);
        assertProbe(name, got);
    }

    /**
     * A PcdMap whose coefficient is zero by pcdmap.c's own {@code EQUAL}, whose floor is {@code
     * DBL_MIN}, becomes a UnitMap, alone, inverted, in series or in parallel; a coefficient of
     * 1e-13 is not zero by that test.
     */
    @Test
    void aPcdMapWithoutDistortionBecomesAUnitMap() {
        assertSimplifies("alone", typical());
        assertSimplifies("alone_inverted", inv(typical()));
        assertSimplifies("zero_disco", pcd(0.0, 0.3, -0.2));
        assertSimplifies("unset_disco", pcd(BAD, 0.3, -0.2));
        assertSimplifies("zero_disco_inverted", inv(pcd(0.0, 0.3, -0.2)));
        assertSimplifies("small_disco", pcd(1.0e-13, 0.3, -0.2));
        assertSimplifies("tiny_disco", pcd(1.0e-310, 0.3, -0.2));
        assertSimplifies("zero_disco_parallel", parallel(pcd(0.0, 0.3, -0.2), zoom(2.0)));
        assertSimplifies("zero_disco_series", series3(zoom(2.0), pcd(0.0, 0.3, -0.2), zoom(3.0)));
    }

    /**
     * A PcdMap merges with a UnitMap, and with a PcdMap applied the other way whose coefficient
     * and centre, as their getters give them, are equal by pcdmap.c's {@code EQUAL}; not with
     * others, nor in parallel.
     */
    @Test
    void unitMapAndInverseNeighboursMergeAsC() {
        assertSimplifies("unit_after", series(typical(), new UnitMap(2)));
        assertSimplifies("unit_before_inverted", series(new UnitMap(2), inv(typical())));
        assertSimplifies("inverse_after", series(typical(), inv(typical())));
        assertSimplifies("inverse_before", series(inv(typical()), typical()));
        PcdMap unsetCentre = pcd(0.01, 0.0, 0.0);
        unsetCentre.clear("PcdCen");
        assertSimplifies("inverse_unset_centre", series(pcd(0.01, 0.0, 0.0), inv(unsetCentre)));
        assertSimplifies("inverse_near_disco", series(typical(), inv(pcd(0.01 * (1.0 + 1.0e-12), 0.3, -0.2))));
        assertSimplifies("inverse_different_disco", series(typical(), inv(pcd(0.01 * (1.0 + 1.0e-9), 0.3, -0.2))));
        assertSimplifies("inverse_different_centre", series(typical(), inv(pcd(0.01, 0.3, -0.3))));
        assertSimplifies("inverse_small_centre_offset", series(pcd(0.01, 0.0, 0.0), inv(pcd(0.01, 1.0e-13, 0.0))));
        assertSimplifies("same_direction", series(typical(), typical()));
        PcdMap p = typical();
        assertSimplifies("shared_pair", series(p, inv((PcdMap) p.copy())));
        assertSimplifies("parallel_inverse", parallel(typical(), inv(typical())));
    }

    /**
     * C's {@code PcdZoom}: a PcdMap swaps past a ZoomMap, applied in either direction, towards a
     * PcdMap it then merges with, its centre and coefficient scaled by the factor the list applies.
     * Inverting a component after the CmpMap is made does not change the CmpMap.
     */
    @Test
    void zoomMapSwapsMatchC() {
        assertSimplifies("zoom_then_inverse", series3(typical(), zoom(2.0), inv(pcd(0.0025, 0.6, -0.4))));
        assertSimplifies("inverted_zoom_then_inverse", series3(typical(), inv(zoom(2.0)), inv(pcd(0.04, 0.15, -0.1))));
        assertSimplifies("inverted_pcd_zoom_then_pcd", series3(inv(typical()), zoom(2.0), pcd(0.0025, 0.6, -0.4)));
        assertSimplifies(
                "inverted_pcd_inverted_zoom_then_pcd", series3(inv(typical()), inv(zoom(0.5)), pcd(0.0025, 0.6, -0.4)));
        assertSimplifies("zoom_then_inverse_not_matching", series3(typical(), zoom(2.0), inv(typical())));
        ZoomMap z = zoom(2.0);
        CmpMap c = series3(typical(), z, inv(pcd(0.04, 0.15, -0.1)));
        z.invert();
        assertSimplifies("zoom_inverted_after_combining", c);
        PcdMap p = typical();
        CmpMap c2 = series3(p, zoom(2.0), pcd(0.0025, 0.6, -0.4));
        p.invert();
        assertSimplifies("pcd_inverted_after_combining", c2);
        assertSimplifies(
                "two_zooms_then_inverse",
                series(series3(typical(), zoom(2.0), zoom(3.0)), inv(pcd(0.01 / 36.0, 1.8, -1.2))));
        assertSimplifies(
                "zoom_inverse_both_sides",
                series(
                        series3(inv(pcd(0.04, 0.15, -0.1)), zoom(2.0), typical()),
                        series(zoom(2.0), inv(pcd(0.0025, 0.6, -0.4)))));
    }

    /**
     * C's {@code PcdPerm}: a PcdMap swaps past a PermMap that exchanges the two axes, applied in
     * either direction, its centre exchanged; not past a PermMap that does anything else.
     */
    @Test
    void permMapSwapsMatchC() {
        assertSimplifies("perm_then_inverse", series3(typical(), swap(), inv(pcd(0.01, -0.2, 0.3))));
        assertSimplifies("inverted_pcd_perm_then_pcd", series3(inv(typical()), swap(), pcd(0.01, -0.2, 0.3)));
        assertSimplifies("inverted_perm_then_inverse", series3(typical(), inv(swap()), inv(pcd(0.01, -0.2, 0.3))));
        assertSimplifies(
                "perm_zoom_then_inverse", series(series3(typical(), swap(), zoom(2.0)), inv(pcd(0.0025, -0.4, 0.6))));
        PermMap notSwap = new PermMap(2, new int[] {1, 2}, 2, new int[] {1, -1}, new double[] {4.0});
        assertSimplifies("perm_not_a_swap", series3(typical(), notSwap, inv(typical())));
        assertSimplifies("perm_alone_after", series(typical(), swap()));
    }

    /**
     * With no PcdMap to merge with, a swap is kept only when one of the swapped Mappings then
     * simplifies: past a ZoomMap of factor 1, not past one of factor 2; a ShiftMap is not swapped.
     */
    @Test
    void swapsWithoutATargetMatchC() {
        assertSimplifies("zoom_after_no_target", series(typical(), zoom(2.0)));
        assertSimplifies("zoom_before_no_target", series(zoom(2.0), typical()));
        assertSimplifies("unit_zoom_after", series(typical(), zoom(1.0)));
        assertSimplifies("shift_between", series3(typical(), new ShiftMap(new double[] {0.3, -0.2}), inv(typical())));
    }

    // ------------------------------------------------------------------
    // NaN protocol
    // ------------------------------------------------------------------

    /** The NaN protocol gives what the transformation gives, bad where it is bad. */
    @Test
    void nanProtocolMatchesTheTransformation() {
        double[][] rows = new double[32][];
        for (int i = 0; i < 32; i++) {
            rows[i] = new double[] {i * 0.1 - 1.5, 0.7 - i * 0.05};
        }
        rows[5][0] = BAD;
        rows[12][1] = Double.NaN;
        double[][] pts = fromRows(rows);
        for (Mapping m : new Mapping[] {typical(), inv(typical())}) {
            for (boolean forward : new boolean[] {true, false}) {
                double[][] regular = m.transform(pts, 32, forward);
                double[][] viaNan = m.transformNanProtocol(pts, 32, forward);
                for (int axis = 0; axis < 2; axis++) {
                    for (int i = 0; i < 32; i++) {
                        double r = regular[axis][i];
                        double n = viaNan[axis][i];
                        if (r == BAD || Double.isNaN(r) || n == BAD || Double.isNaN(n)) {
                            assertTrue(
                                    (r == BAD || Double.isNaN(r)) && (n == BAD || Double.isNaN(n)),
                                    "point " + i + " axis " + axis + ": " + r + " and " + n);
                        } else {
                            assertEquals(r, n, "point " + i + " axis " + axis);
                        }
                    }
                }
            }
        }
    }
}
