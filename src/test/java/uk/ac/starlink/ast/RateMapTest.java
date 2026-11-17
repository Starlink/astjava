package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_ratemap.rs} (after C's {@code ast_tester/testratemap.c}),
 * with the cases of the C probe {@code probes/ratemap_probe.c}: native dumps of RateMaps of
 * ZoomMaps (the encapsulated Mapping inverted when the RateMap is made or afterwards, the RateMap
 * inverted) and of a LutMap, a MathMap and a TranMap, each read back; simplification of a RateMap
 * alone and of RateMaps cancelling against their inverses in series; rates; equality; splits; and
 * the constructor's errors.
 *
 * <p>Rust's {@code ax1()} and {@code ax2()}, numbered from 1, are here the zero-based {@code
 * getIout()} and {@code getIin()}, C's {@code iout} and {@code iin}. The five Rust tests that call
 * {@code try_merge}, which this library does not have, simplify the series or parallel CmpMap they
 * describe instead.
 */
class RateMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("ratemap_probe");

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

    private static RateMap rate(Mapping m, int ax1, int ax2) {
        return new RateMap(m, ax1, ax2);
    }

    /** The forward transformation of the points, given one row per point. */
    private static double[][] fwd(Mapping m, double[]... rows) {
        return transformRows(m, true, rows);
    }

    private static int code(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(AstException.class, e).getStatus();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    void constructionBasic() {
        RateMap rm = rate(zoom(1, 3.0), 1, 1);
        assertEquals(1, rm.getNin());
        assertEquals(1, rm.getNout());
        assertEquals(0, rm.getIout());
        assertEquals(0, rm.getIin());
        assertFalse(rm.getInvert());
        assertTrue(rm.getTranForward());
        assertFalse(rm.getTranInverse());
    }

    @Test
    void constructionMultiAxis() {
        RateMap rm = rate(zoom(3, 2.0), 2, 3);
        assertEquals(3, rm.getNin());
        assertEquals(1, rm.getNout());
        assertEquals(1, rm.getIout());
        assertEquals(2, rm.getIin());
    }

    @Test
    void constructionInvalidAxes() {
        ZoomMap zm = zoom(2, 3.0);
        assertEquals(AstException.AST__INNCO, code(() -> rate(zm, 3, 1)));
        assertEquals(AstException.AST__INNCO, code(() -> rate(zm, 1, 3)));
        assertEquals(AstException.AST__INNCO, code(() -> rate(zm, 0, 1)));
        assertEquals(AstException.AST__INNCO, code(() -> rate(zm, 1, 0)));
    }

    // ------------------------------------------------------------------
    // Forward transformation
    // ------------------------------------------------------------------

    @Test
    void zoommapRate() {
        RateMap rm = rate(zoom(1, 3.0), 1, 1);
        double[][] out = fwd(rm, new double[] {1.0}, new double[] {2.0}, new double[] {5.0}, new double[] {-10.0});
        assertEquals(1, out.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(3.0, out[0][i], 1e-6, "point " + i);
        }
    }

    @Test
    void shiftmapRate() {
        RateMap rm = rate(new ShiftMap(new double[] {5.0, 10.0}), 1, 1);
        double[][] out = fwd(rm, new double[] {1.0, 3.0}, new double[] {2.0, 4.0});
        assertEquals(1, out.length);
        for (int i = 0; i < 2; i++) {
            assertEquals(1.0, out[0][i], 1e-6, "point " + i);
        }
    }

    @Test
    void offDiagonalZero() {
        RateMap rm = rate(zoom(2, 3.0), 1, 2);
        double[][] out = fwd(rm, new double[] {1.0, 3.0}, new double[] {2.0, 4.0});
        for (int i = 0; i < 2; i++) {
            assertEquals(0.0, out[0][i], 1e-6, "point " + i);
        }
    }

    /**
     * A RateMap asks its Mapping's rate at a bad input too, as ratemap.c Transform does, and a
     * ZoomMap's rate does not depend on the position, so C gives 3.0 there (oracle {@code rate.json},
     * {@code ratemap_zoommap_bad_input}).
     */
    @Test
    void badInputAsksTheMapping() {
        RateMap rm = rate(zoom(1, 3.0), 1, 1);
        double[][] out = fwd(rm, new double[] {1.0}, new double[] {BAD}, new double[] {3.0});
        assertEquals(3.0, out[0][0], 1e-6);
        assertEquals(3.0, out[0][1]);
        assertEquals(3.0, out[0][2], 1e-6);
    }

    /** Requesting the inverse is AST__TRNND, as C's base Transform reports. */
    @Test
    void inverseUndefined() {
        RateMap rm = rate(zoom(1, 3.0), 1, 1);
        assertFalse(rm.getTranInverse());
        assertEquals(AstException.AST__TRNND, code(() -> transformRows(rm, false, new double[] {1.0})));
    }

    @Test
    void inverted() {
        RateMap rm = inv(rate(zoom(1, 3.0), 1, 1));
        assertEquals(1, rm.getNin());
        assertEquals(1, rm.getNout());
        assertFalse(rm.getTranForward());
        assertTrue(rm.getTranInverse());
        assertTrue(rm.getInvert());
        assertEquals(AstException.AST__TRNND, code(() -> fwd(rm, new double[] {1.0})));
        assertEquals(3.0, transformRows(rm, false, new double[] {1.0})[0][0], 1e-6);
    }

    @Test
    void invertedMultiAxis() {
        RateMap rm = rate(zoom(2, 5.0), 1, 1);
        assertEquals(2, rm.getNin());
        assertEquals(1, rm.getNout());
        rm.invert();
        assertEquals(1, rm.getNin());
        assertEquals(2, rm.getNout());
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    @Test
    void equalSame() {
        assertTrue(rate(zoom(2, 3.0), 1, 2).astEqual(rate(zoom(2, 3.0), 1, 2)));
    }

    @Test
    void equalDifferentAxes() {
        assertFalse(rate(zoom(2, 3.0), 1, 1).astEqual(rate(zoom(2, 3.0), 1, 2)));
    }

    @Test
    void equalDifferentMapping() {
        assertFalse(rate(zoom(1, 3.0), 1, 1).astEqual(rate(zoom(1, 5.0), 1, 1)));
    }

    /** RateMaps whose Invert attributes differ are never equal (ratemap.c Equal). */
    @Test
    void equalDifferentInvert() {
        assertFalse(rate(zoom(1, 3.0), 1, 1).astEqual(inv(rate(zoom(1, 3.0), 1, 1))));
    }

    /** Every comparison of the probe's {@code equal} case gives C's {@code astEqual}. */
    @Test
    void equalityMatchesC() {
        RateMap a = rate(zoom(2, 2.0), 1, 2);
        RateMap b = rate(zoom(2, 2.0), 1, 2);
        RateMap c = rate(zoom(2, 2.0), 1, 1);
        RateMap d = rate(zoom(2, 3.0), 1, 2);
        RateMap e = rate(inv(zoom(2, 0.5)), 1, 2);
        RateMap ainv = inv((RateMap) a.copy());
        RateMap binv = inv((RateMap) b.copy());
        ZoomMap z = zoom(2, 2.0);
        RateMap f = rate(z, 1, 2);
        z.invert();
        String got = "same " + flag(a.astEqual(b)) + "\n"
                + "different_axes " + flag(a.astEqual(c)) + "\n"
                + "different_mappings " + flag(a.astEqual(d)) + "\n"
                + "equivalent_mappings " + flag(a.astEqual(e)) + "\n"
                + "inverted_vs_plain " + flag(ainv.astEqual(a)) + "\n"
                + "both_inverted " + flag(ainv.astEqual(binv)) + "\n"
                + "mapping_inverted_after " + flag(f.astEqual(a)) + "\n"
                + "not_a_ratemap " + flag(a.astEqual(zoom(2, 2.0))) + "\n"
                + "mapping_still_inverted " + flag(z.getInvert()) + "\n";
        assertEquals(probe("equal"), got);
    }

    private static int flag(boolean b) {
        return b ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    /** A RateMap of a series CmpMap of ZoomMaps becomes a RateMap of their product. */
    @Test
    void simplifyInterior() {
        RateMap rm = rate(new CmpMap(zoom(2, 2.0), zoom(2, 3.0), true), 1, 1);
        Mapping simp = rm.simplify();
        assertInstanceOf(RateMap.class, simp);
        assertEquals(6.0, fwd(simp, new double[] {1.0, 1.0})[0][0], 1e-6);
    }

    /** A RateMap in series with its own inverse cancels (Rust {@code inverse_cancel_merge}). */
    @Test
    void inverseCancelMerge() {
        Mapping simp = new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(zoom(1, 2.0), 1, 1)), true).simplify();
        assertInstanceOf(UnitMap.class, simp);
        assertEquals(1, simp.getNin());
        assertEquals(1, simp.getNout());
    }

    /** RateMaps with the same Invert attribute do not cancel (Rust {@code no_cancel_same_invert}). */
    @Test
    void noCancelSameInvert() {
        Mapping simp = new CmpMap(rate(zoom(1, 2.0), 1, 1), rate(zoom(1, 2.0), 1, 1), true).simplify();
        assertInstanceOf(CmpMap.class, simp);
    }

    /** RateMaps of different axes do not cancel (Rust {@code no_cancel_different_axes}). */
    @Test
    void noCancelDifferentAxes() {
        Mapping simp = new CmpMap(rate(zoom(2, 2.0), 1, 1), inv(rate(zoom(2, 2.0), 1, 2)), true).simplify();
        assertInstanceOf(CmpMap.class, simp);
    }

    /** RateMaps of different Mappings do not cancel (Rust {@code no_cancel_different_mapping}). */
    @Test
    void noCancelDifferentMapping() {
        Mapping simp = new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(zoom(1, 3.0), 1, 1)), true).simplify();
        assertInstanceOf(CmpMap.class, simp);
    }

    /** RateMaps in parallel do not cancel (Rust {@code no_cancel_parallel}). */
    @Test
    void noCancelParallel() {
        Mapping simp = new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(zoom(1, 2.0), 1, 1)), false).simplify();
        assertInstanceOf(CmpMap.class, simp);
    }

    /** The error simplifying the encapsulated Mapping is passed up, as in C (probe {@code simp_error}). */
    @Test
    void anErrorSimplifyingTheEncapsulatedMappingIsPassedUp() {
        CmpMap inner = new CmpMap(
                new PermMap(2, new int[] {0, 0}, 2, new int[] {-1, -2}, new double[] {0.1, 0.2}),
                new WcsMap(2, WcsMap.AST__COE, 1, 2),
                true);
        RateMap map = rate(inner, 1, 1);
        assertEquals(AstException.AST__WCSPA, code(map::simplify));
        assertTrue(probe("simp_error").endsWith("status " + AstException.AST__WCSPA + "\n"));
    }

    // ------------------------------------------------------------------
    // Simplification against the probe
    // ------------------------------------------------------------------

    private static String trans(Mapping m, double x) {
        StringBuilder sb = new StringBuilder();
        int nin = m.getNin();
        int nout = m.getNout();
        if (m.getTranForward()) {
            sb.append(tranLine("fwd", m, x, nin, nout, true));
        }
        if (m.getTranInverse()) {
            sb.append(tranLine("inv", m, x, nout, nin, false));
        }
        return sb.toString();
    }

    private static String tranLine(String label, Mapping m, double x, int nin, int nout, boolean forward) {
        double[] in = new double[nin];
        for (int i = 0; i < nin; i++) {
            in[i] = x + i;
        }
        double[] out = m.tranN(1, nin, in, forward, nout);
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

    private static void assertSimplifies(String name, Mapping map, double x) {
        Mapping s = map.simplify();
        String got = "class " + s.astClass() + "\n" + ChannelText.dump(s) + "-- transformations\n" + trans(map, x)
                + "-- transformations simplified\n" + trans(s, x);
        assertEquals(probe(name), got, name);
    }

    private static CmpMap zooms(int n, double f1, double f2) {
        return new CmpMap(zoom(n, f1), zoom(n, f2), true);
    }

    /**
     * A RateMap alone is replaced by a RateMap of its simplified Mapping, simplified in the
     * orientation it had when the RateMap was made; one whose Mapping does not simplify, such as a
     * UnitMap, is left as it is.
     */
    @Test
    void aRateMapAloneSimplifiesAsC() {
        assertSimplifies("simp_plain", rate(zoom(1, 2.0), 1, 1), 1.5);
        assertSimplifies("simp_unitmap", rate(new UnitMap(2), 1, 2), 1.5);
        assertSimplifies("simp_interior", rate(zooms(2, 2.0, 3.0), 1, 1), 1.5);
        assertSimplifies("simp_interior_inverted_mapping", rate(inv(zooms(1, 2.0, 4.0)), 1, 1), 1.5);
        CmpMap c = zooms(1, 2.0, 4.0);
        RateMap r = rate(c, 1, 1);
        c.invert();
        assertSimplifies("simp_interior_mapping_inverted_after", r, 1.5);
        assertSimplifies("simp_interior_ratemap_inverted", inv(rate(zooms(1, 2.0, 4.0), 1, 1)), 1.5);
        assertSimplifies(
                "simp_interior_in_series", new CmpMap(zoom(1, 5.0), rate(zooms(1, 2.0, 4.0), 1, 1), true), 1.5);
    }

    /**
     * A RateMap in series beside its inverse, a RateMap of the same axes of an equal Mapping, is
     * replaced, with it, by UnitMaps, as C does.
     */
    @Test
    void cancellationsMatchC() {
        assertSimplifies(
                "cancel_upper", new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(zoom(1, 2.0), 1, 1)), true), 1.5);
        assertSimplifies(
                "cancel_lower", new CmpMap(inv(rate(zoom(1, 2.0), 1, 1)), rate(zoom(1, 2.0), 1, 1), true), 1.5);
        assertSimplifies(
                "cancel_two_inputs_upper",
                new CmpMap(rate(zoom(2, 2.0), 1, 1), inv(rate(zoom(2, 2.0), 1, 1)), true),
                1.5);
        assertSimplifies(
                "cancel_two_inputs_lower",
                new CmpMap(inv(rate(zoom(2, 2.0), 1, 1)), rate(zoom(2, 2.0), 1, 1), true),
                1.5);
        assertSimplifies(
                "cancel_equivalent_mappings",
                new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(inv(zoom(1, 0.5)), 1, 1)), true),
                1.5);
        ZoomMap z = zoom(1, 2.0);
        RateMap r = rate(z, 1, 1);
        z.invert();
        assertSimplifies("cancel_mapping_inverted_after", new CmpMap(r, inv(rate(zoom(1, 2.0), 1, 1)), true), 1.5);
    }

    /** RateMaps that are not inverses of each other, or not in series, are left as they are. */
    @Test
    void nonCancellationsMatchC() {
        assertSimplifies(
                "no_cancel_axes", new CmpMap(rate(zoom(2, 2.0), 1, 1), inv(rate(zoom(2, 2.0), 1, 2)), true), 1.5);
        assertSimplifies(
                "no_cancel_mappings", new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(zoom(1, 3.0), 1, 1)), true), 1.5);
        assertSimplifies(
                "no_cancel_same_invert", new CmpMap(rate(zoom(1, 2.0), 1, 1), rate(zoom(1, 2.0), 1, 1), true), 1.5);
        assertSimplifies(
                "no_cancel_parallel", new CmpMap(rate(zoom(1, 2.0), 1, 1), inv(rate(zoom(1, 2.0), 1, 1)), false), 1.5);
        RateMap r = rate(zoom(1, 2.0), 1, 1);
        assertSimplifies("no_cancel_shared", new CmpMap(r, r, true), 1.5);
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

    private static final double[] TABLE = {0.0, 1.0, 4.0, 9.0, 16.0};

    private static MathMap square() {
        return new MathMap(1, 1, new String[] {"y = x * x"}, new String[] {"x = sqrt( y )"});
    }

    /** RateMaps of ZoomMaps, inverted in each way, dump and read back as in C. */
    @Test
    void nativeDumpsMatchC() {
        assertRoundTrips("dump_plain", rate(zoom(2, 3.0), 2, 1), 1.5);
        assertRoundTrips("dump_first_axes", rate(zoom(1, 2.0), 1, 1), 1.5);
        assertRoundTrips("dump_inverted_mapping", rate(inv(zoom(1, 2.0)), 1, 1), 1.5);
        ZoomMap z = zoom(1, 2.0);
        RateMap r = rate(z, 1, 1);
        z.invert();
        assertRoundTrips("dump_mapping_inverted_after", r, 1.5);
        assertRoundTrips("dump_ratemap_inverted", inv(rate(zoom(2, 3.0), 1, 2)), 1.5);
    }

    /** RateMaps of a LutMap, a MathMap and a TranMap dump and read back as in C. */
    @Test
    void nativeDumpsOfOtherMappingsMatchC() {
        assertRoundTrips("dump_lutmap", rate(new LutMap(TABLE, 1.0, 1.0), 1, 1), 2.5);
        MathMap mm = new MathMap(2, 2, new String[] {"r = x * y", "s = x + y * y"}, new String[] {"x", "y"});
        assertRoundTrips("dump_mathmap", rate(mm, 2, 2), 1.5);
        assertRoundTrips("dump_tranmap", rate(new TranMap(square(), zoom(1, 3.0)), 1, 1), 1.5);
        assertRoundTrips("dump_inverted_tranmap", rate(inv(new TranMap(zoom(1, 3.0), square())), 1, 1), 1.5);
    }

    @Test
    void serializationRoundtrip() {
        RateMap rm = rate(zoom(2, 3.0), 2, 1);
        RateMap back = (RateMap) ChannelText.read(ChannelText.dump(rm));
        assertEquals(2, back.getNin());
        assertEquals(1, back.getNout());
        assertEquals(1, back.getIout());
        assertEquals(0, back.getIin());
        assertFalse(back.getInvert());
        double[][] out = fwd(back, new double[] {1.0, 3.0}, new double[] {2.0, 4.0});
        for (int i = 0; i < 2; i++) {
            assertEquals(0.0, out[0][i], 1e-6, "point " + i);
        }
    }

    @Test
    void serializationRoundtripDiagonal() {
        Mapping back = (Mapping) ChannelText.read(ChannelText.dump(rate(zoom(2, 3.0), 1, 1)));
        double[][] out = fwd(back, new double[] {1.0, 3.0}, new double[] {2.0, 4.0});
        for (int i = 0; i < 2; i++) {
            assertEquals(3.0, out[0][i], 1e-6, "point " + i);
        }
    }

    /** IIn and IOut of zero are not written, and read back as zero. */
    @Test
    void serializationDefaultAxes() {
        String text = ChannelText.dump(rate(zoom(1, 2.0), 1, 1));
        assertFalse(text.contains("IIn"));
        assertFalse(text.contains("IOut"));
        RateMap back = (RateMap) ChannelText.read(text);
        assertEquals(0, back.getIout());
        assertEquals(0, back.getIin());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    @Test
    void readFixtureInverseCancel() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/ratemap_inverse_cancel.map"));
        assertEquals(1, m.getNin());
    }

    @Test
    void readFixtureSimplifyInterior() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/ratemap_simplify_interior.map"));
        assertEquals(2, m.getNin());
        assertEquals(1, m.getNout());
    }

    @Test
    void simplifyInverseCancelFixture() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/ratemap_inverse_cancel.map"));
        Mapping simp = m.simplify();
        assertInstanceOf(UnitMap.class, simp);
        assertEquals(1, simp.getNin());
        assertEquals(1, simp.getNout());
    }

    @Test
    void simplifyInteriorFixture() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/ratemap_simplify_interior.map"));
        Mapping simp = m.simplify();
        assertInstanceOf(RateMap.class, simp);
        assertEquals(6.0, fwd(simp, new double[] {1.0, 1.0})[0][0], 1e-6);
    }

    @Test
    void negNonRatemapNeighbour() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/neg_ratemap_nonratemap_neighbour.map"));
        assertFalse(m.simplify() instanceof UnitMap);
    }

    @Test
    void negDifferentInner() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/neg_ratemap_different_inner.map"));
        assertFalse(m.simplify() instanceof UnitMap);
    }

    @Test
    void negParallel() {
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/neg_ratemap_parallel.map"));
        assertFalse(m.simplify() instanceof UnitMap);
    }

    // ------------------------------------------------------------------
    // testratemap.c
    // ------------------------------------------------------------------

    /**
     * {@code testratemap.c}: a RateMap of each Jacobian element of the compound Mapping {@code
     * testrate.c} uses, forward and inverted, transforming (1,2,3,4) to the rate itself, with C's
     * error numbers in the messages. Only the forward direction of a RateMap exists.
     */
    @Test
    void rateMapsOfACompoundMapping() {
        double[] at = {1.0, 2.0, 3.0, 4.0};
        Mapping m = RateTest.compoundMap();
        double[][][] tables = {RateTest.COMPOUND_FORWARD, RateTest.COMPOUND_INVERSE};
        int[] firstErr = {1, 11};
        for (int t = 0; t < 2; t++) {
            double[][] table = tables[t];
            for (int k = 0; k < table.length; k++) {
                int ax1 = (int) table[k][0];
                int ax2 = (int) table[k][1];
                double want = table[k][2];
                boolean exact = table[k][3] != 0.0;
                RateMap rm = rate(m, ax1, ax2);
                if (k == 0 && t == 0) {
                    assertTrue(rm.getTranForward(), "Error 2");
                    assertFalse(rm.getTranInverse(), "Error 3");
                }
                double r = point(fwd(rm, at), 0)[0];
                int err = t == 0 && k > 0 ? 3 + k : firstErr[t] + k;
                if (exact) {
                    assertEquals(want, r, "Error " + err + ": RateMap(" + ax1 + "," + ax2 + ")");
                } else {
                    assertTrue(
                            Math.abs(r - want) <= 1.0e-6,
                            "Error " + err + ": RateMap(" + ax1 + "," + ax2 + ") = " + r + ", want " + want);
                }
            }
            m.invert();
        }
    }

    // ------------------------------------------------------------------
    // Rates, splits and errors against the probe
    // ------------------------------------------------------------------

    /** The numerical rate of a RateMap, and its outputs at a good and a bad input, as in C. */
    @Test
    void ratesMatchC() {
        MathMap cube = new MathMap(1, 1, new String[] {"y = x * x * x"}, new String[] {"x"});
        RateMap r = rate(cube, 1, 1);
        double[] at = {2.0};
        String got = "rate of ratemap " + CFormat.format("%.17g", r.rate(at, 1, 1)) + "\n"
                + "ratemap of cube at 2 " + CFormat.format("%.17g", r.tran1(1, at, true)[0]) + "\n"
                + "ratemap of cube at bad " + CFormat.format("%.17g", r.tran1(1, new double[] {BAD}, true)[0])
                + "\n";
        assertEquals(probe("rates"), got);
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

    private static ShiftMap shift3() {
        return new ShiftMap(new double[] {10.0, 20.0, 30.0});
    }

    /**
     * Splits of a RateMap: the encapsulated Mapping is split, and the split is a RateMap if it
     * keeps both the varied input and the differentiated output; an inverted RateMap is split only
     * as the base class splits it.
     */
    @Test
    void splitsMatchC() {
        assertEquals(probe("split_output_dropped"), split(rate(shift3(), 2, 1), new int[] {1}));
        assertEquals(probe("split_picks_varied_input"), split(rate(shift3(), 1, 1), new int[] {1}));
        assertEquals(probe("split_other_input"), split(rate(shift3(), 1, 1), new int[] {2}));
        assertEquals(probe("split_two_inputs"), split(rate(shift3(), 1, 1), new int[] {3, 1}));
        assertEquals(probe("split_inverted"), split(inv(rate(shift3(), 1, 1)), new int[] {1}));
        assertEquals(probe("split_inverted_mapping"), split(rate(inv(shift3()), 1, 1), new int[] {1}));
    }

    /** A split RateMap shares nothing with the RateMap it came from. */
    @Test
    void aSplitIsIndependent() {
        ShiftMap s = shift3();
        RateMap r = rate(s, 1, 1);
        MapSplit split = r.mapSplit(new int[] {0});
        assertInstanceOf(RateMap.class, split.map);
        assertNotSame(s, ((RateMap) split.map).getMap());
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

    private static void assertError(String name, org.junit.jupiter.api.function.Executable e) {
        assertEquals(errorCase(name), report(assertThrows(AstException.class, e)), name);
    }

    /** The constructor's checks, and the directions a RateMap does not define, as in C. */
    @Test
    void errorsMatchC() {
        assertError("error_no_forward", () -> rate(inv(rate(zoom(1, 2.0), 1, 1)), 1, 1));
        assertError("error_output_axis", () -> rate(zoom(2, 2.0), 3, 1));
        assertError("error_input_axis", () -> rate(zoom(2, 2.0), 1, 3));
        assertError("error_output_axis_zero", () -> rate(zoom(2, 2.0), 0, 1));
        assertError("error_input_axis_zero", () -> rate(zoom(2, 2.0), 1, 0));
        assertError("error_inverse", () -> rate(zoom(1, 2.0), 1, 1).tran1(1, new double[] {1.0}, false));
        assertError(
                "error_inverted_forward", () -> inv(rate(zoom(1, 2.0), 1, 1)).tran1(1, new double[] {1.0}, true));
    }

    // ------------------------------------------------------------------
    // Sharing and copying
    // ------------------------------------------------------------------

    /** A RateMap holds the Mapping it is given, as C clones it, and a copy holds a copy. */
    @Test
    void theMappingIsSharedAndCopied() {
        ZoomMap z = zoom(1, 2.0);
        RateMap r = rate(z, 1, 1);
        assertSame(z, r.getMap());
        RateMap c = (RateMap) r.copy();
        assertNotSame(z, c.getMap());
        assertTrue(c.getMap().astEqual(z));
    }
}
