package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_switchmap.rs} (from {@code testswitchmap.c}), with the
 * cases of the C probe {@code probes/switchmap_probe.c}: the route a selector value picks, at the
 * halfway points and beyond the range of {@code int}; transformations and rates of the SwitchMaps
 * of testswitchmap.c at full precision; native dumps with selectors and routes inverted when the
 * SwitchMap is made and afterwards, one Mapping as both selectors, LutMap, MathMap and PcdMap
 * components, a SwitchMap inside a CmpMap, a TranMap and a SwitchMap, and a dump without {@code
 * ISInv}, each read back; equality; simplification of a SwitchMap alone, inverted, with route and
 * selector Mappings that simplify, beside its own inverse and other Mappings; and the
 * constructor's errors. The Rust tests of a SwitchMap of 1-D routes run with a forward selector
 * that is a SelectorMap of Box Regions, as does testswitchmap.c section 3.
 */
class SwitchMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("switchmap_probe");

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    /** The probe case without the lines naming the C source of an error. */
    private static String probeWithoutSource(String name) {
        StringBuilder sb = new StringBuilder();
        for (String line : probe(name).split("\n", -1)) {
            if (!line.startsWith("!! ") && !line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String g(double v) {
        return CFormat.format("%.17g", v);
    }

    private static <T extends Mapping> T inv(T m) {
        m.invert();
        return m;
    }

    private static WinMap win(double x1, double x2, double l1, double l2) {
        return new WinMap(1, new double[] {x1}, new double[] {x2}, new double[] {l1}, new double[] {l2});
    }

    private static MathMap mathmap(String f, String i) {
        return new MathMap(1, 1, new String[] {f}, new String[] {i});
    }

    /** The MathMap of testswitchmap.c section 2, which picks a half by pixel or by wavelength. */
    private static MathMap halves() {
        return mathmap("y=qif(x>101.5,2,1)", "x=qif(y>1800,2,1)");
    }

    private static SwitchMap switch2(Mapping fs, Mapping is, Mapping r1, Mapping r2) {
        return new SwitchMap(fs, is, new Mapping[] {r1, r2});
    }

    /** The plain 1-D SwitchMap of the probe. */
    private static SwitchMap plain() {
        return switch2(halves(), halves(), win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
    }

    /**
     * testswitchmap.c section 1: two rows of a 2-D grid, each a spectrum; a PermMap forward
     * selector reading the row number and a MathMap inverse selector choosing a row by wavelength.
     */
    private static SwitchMap section1() {
        int[] outperm = {1};
        int[] inperm = {1, -1};
        Mapping r1 = new CmpMap(
                new PermMap(2, inperm, 1, outperm, new double[] {1.0}), win(1.0, 101.0, 1000.0, 2000.0), true);
        Mapping r2 = new CmpMap(
                new PermMap(2, inperm, 1, outperm, new double[] {2.0}), win(1.0, 101.0, 1600.0, 2700.0), true);
        PermMap fs = new PermMap(2, new int[] {0, 1}, 1, new int[] {2}, new double[] {0.0});
        return new SwitchMap(fs, mathmap("y", "x=qif(y>1800,2,1)"), new Mapping[] {r1, r2});
    }

    /** testswitchmap.c section 2: two halves of a 1-D grid, with one MathMap as both selectors. */
    private static SwitchMap section2(MathMap sm) {
        return switch2(sm, sm, win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
    }

    // ------------------------------------------------------------------
    // The probe's formatting
    // ------------------------------------------------------------------

    private static final double[] PTS1 = {1.0, 37.5, 101.0, 140.0, BAD};
    private static final double[] PTS2 = {1.0, 2.0, 2.0, 1.0, 1.0};
    private static final double[] WAVE = {1000.0, 1700.0, 1900.0, 2700.0, BAD};

    /**
     * The forward and inverse transformations of the probe's points: positions with one
     * coordinate are given the pixel values forward and the wavelengths inverse, positions with
     * two the pixel values and row numbers.
     */
    private static String tran(Mapping m) {
        StringBuilder sb = new StringBuilder();
        for (boolean dir : new boolean[] {true, false}) {
            String label = dir ? "fwd" : "inv";
            if (!(dir ? m.getTranForward() : m.getTranInverse())) {
                sb.append(label).append(" undefined\n");
                continue;
            }
            int nin = dir ? m.getNin() : m.getNout();
            int nout = dir ? m.getNout() : m.getNin();
            int npt = PTS1.length;
            double[][] in = new double[nin][npt];
            for (int i = 0; i < npt; i++) {
                if (nin == 1) {
                    in[0][i] = dir ? PTS1[i] : WAVE[i];
                } else {
                    in[0][i] = PTS1[i];
                    in[1][i] = PTS2[i];
                }
            }
            double[][] out = m.tranP(npt, nin, in, dir, nout);
            for (int i = 0; i < npt; i++) {
                sb.append(label);
                for (int k = 0; k < nin; k++) {
                    sb.append(' ').append(g(in[k][i]));
                }
                sb.append(" ->");
                for (int k = 0; k < nout; k++) {
                    sb.append(' ').append(g(out[k][i]));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String rate(String label, Mapping m, double[] at, int ax1, int ax2) {
        return label + ": " + g(m.rate(at, ax1, ax2)) + "\n";
    }

    private static String report(AstException e) {
        StringBuilder sb = new StringBuilder();
        for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static String invertState(Mapping m) {
        return "Invert shared after " + (m.getInvert() ? 1 : 0) + " test " + (m.testInvert() ? 1 : 0) + "\n";
    }

    // ------------------------------------------------------------------
    // test_switchmap.rs: a SelectorMap as the forward selector
    // ------------------------------------------------------------------

    /**
     * Two routes, [0,10] to [0,100] and [20,30] to [200,300], and a SelectorMap over the 1-D
     * intervals as the forward selector.
     */
    private static SwitchMap make1dSwitchmap() {
        Region reg1 = new Box(new Frame(1), 1, new double[] {0.0}, new double[] {10.0}, null);
        Region reg2 = new Box(new Frame(1), 1, new double[] {20.0}, new double[] {30.0}, null);
        SelectorMap sel = new SelectorMap(new Region[] {reg1, reg2}, BAD);
        return new SwitchMap(sel, null, new Mapping[] {win(0.0, 10.0, 0.0, 100.0), win(20.0, 30.0, 200.0, 300.0)});
    }

    /** The number of route Mappings of {@code sw}, from its dump. */
    private static int nroute(SwitchMap sw) {
        String text = ChannelText.dump(sw);
        int n = 0;
        while (text.contains(" RMap" + (n + 1) + " = ")) {
            n++;
        }
        return n;
    }

    @Test
    void switchmapBasicConstruction() {
        SwitchMap sw = make1dSwitchmap();
        assertEquals(1, sw.getNin());
        assertEquals(1, sw.getNout());
        assertEquals(2, nroute(sw));
        assertTrue(sw.getTranForward());
        assertFalse(sw.getTranInverse());
    }

    @Test
    void switchmapForwardRoute1() {
        assertEquals(50.0, make1dSwitchmap().tran1(1, new double[] {5.0}, true)[0], 1e-10);
    }

    @Test
    void switchmapForwardRoute2() {
        assertEquals(250.0, make1dSwitchmap().tran1(1, new double[] {25.0}, true)[0], 1e-10);
    }

    @Test
    void switchmapForwardNoRoute() {
        assertEquals(BAD, make1dSwitchmap().tran1(1, new double[] {15.0}, true)[0]);
    }

    @Test
    void switchmapForwardBadInput() {
        assertEquals(BAD, make1dSwitchmap().tran1(1, new double[] {BAD}, true)[0]);
    }

    @Test
    void switchmapForwardMultiplePoints() {
        double[] out = make1dSwitchmap().tran1(4, new double[] {5.0, 25.0, 15.0, BAD}, true);
        assertEquals(50.0, out[0], 1e-10);
        assertEquals(250.0, out[1], 1e-10);
        assertEquals(BAD, out[2]);
        assertEquals(BAD, out[3]);
    }

    @Test
    void switchmapEqual() {
        assertTrue(make1dSwitchmap().astEqual(make1dSwitchmap()));
    }

    /**
     * A SelectorMap removes no Regions (selectormap.c installs no {@code RemoveRegions}), and the
     * WinMap routes hold none, so the SwitchMap comes back itself, as C's clone.
     */
    @Test
    void switchmapRemoveRegions() {
        SwitchMap sw = make1dSwitchmap();
        Mapping cleaned = sw.removeRegions();
        assertSame(sw, cleaned);
        assertEquals(1, cleaned.getNin());
        assertEquals(1, cleaned.getNout());
    }

    @Test
    void switchmapSerializationRoundtrip() {
        SwitchMap sw = make1dSwitchmap();
        SwitchMap loaded = assertInstanceOf(SwitchMap.class, ChannelText.read(ChannelText.dump(sw)));
        assertEquals(2, nroute(loaded));
        assertEquals(1, loaded.getNin());
        assertEquals(1, loaded.getNout());
        double[] in = {5.0, 25.0, 15.0};
        assertArrayEquals(sw.tran1(3, in, true), loaded.tran1(3, in, true));
    }

    // ------------------------------------------------------------------
    // test_switchmap.rs: construction
    // ------------------------------------------------------------------

    @Test
    void switchmapNoRoutesRejected() {
        AstException e = assertThrows(AstException.class, () -> new SwitchMap(new UnitMap(1), null, new Mapping[0]));
        assertEquals(AstException.AST__BDPAR, e.getStatus());
    }

    @Test
    void switchmapNoSelectorsRejected() {
        AstException e =
                assertThrows(AstException.class, () -> new SwitchMap(null, null, new Mapping[] {new UnitMap(2)}));
        assertEquals(AstException.AST__INTRD, e.getStatus());
    }

    @Test
    void switchmapMismatchedRoutesRejected() {
        AstException e = assertThrows(
                AstException.class,
                () -> new SwitchMap(new ZoomMap(2, 1.0), null, new Mapping[] {new UnitMap(2), new UnitMap(3)}));
        assertEquals(AstException.AST__BADNI, e.getStatus());
    }

    /** The constructor's errors give C's statuses and messages, in C's order of checks. */
    @Test
    void constructorErrorsMatchC() {
        Mapping[] zoom = {new ZoomMap(1, 2.0)};
        assertError("error_no_routes", () -> new SwitchMap(halves(), halves(), new Mapping[0]));
        assertError(
                "error_route_nin",
                () -> new SwitchMap(halves(), null, new Mapping[] {new ZoomMap(1, 2.0), new ZoomMap(2, 2.0)}));
        PermMap oneToTwo = new PermMap(1, new int[] {1}, 2, new int[] {1, 1}, null);
        assertError(
                "error_route_nout", () -> new SwitchMap(halves(), null, new Mapping[] {new ZoomMap(1, 2.0), oneToTwo}));
        assertError("error_fsmap_no_forward", () -> new SwitchMap(inv(fwdOnlyPolymap()), null, zoom));
        assertError("error_ismap_no_inverse", () -> new SwitchMap(null, fwdOnlyPolymap(), zoom));
        assertError("error_fsmap_nin", () -> new SwitchMap(new ZoomMap(2, 1.0), null, zoom));
        PermMap fsOneToTwo = new PermMap(1, new int[] {1}, 2, new int[] {1, 1}, null);
        assertError("error_fsmap_nout", () -> new SwitchMap(fsOneToTwo, null, zoom));
        assertError("error_ismap_nout", () -> new SwitchMap(null, new ZoomMap(2, 1.0), zoom));
        PermMap twoToOne = new PermMap(2, new int[] {1, 1}, 1, new int[] {1}, null);
        assertError("error_ismap_nin", () -> new SwitchMap(null, twoToOne, zoom));
        assertError("error_no_selectors", () -> new SwitchMap(null, null, zoom));
    }

    private static void assertError(String name, org.junit.jupiter.api.function.Executable make) {
        AstException e = assertThrows(AstException.class, make, name);
        assertEquals(probeWithoutSource(name), report(e) + "status " + e.getStatus() + "\n", name);
    }

    private static PolyMap fwdOnlyPolymap() {
        PolyMap pm = new PolyMap(1, 1, 1, new double[] {1.0, 1.0, 1.0}, 0, null);
        pm.setIterInverse(false);
        return pm;
    }

    /** jniast's arguments: a null array is refused, null selectors are not. */
    @Test
    void jniastArguments() {
        assertThrows(NullPointerException.class, () -> new SwitchMap(halves(), null, null));
        AstException e = assertThrows(
                AstException.class, () -> new SwitchMap(halves(), null, new Mapping[] {new ZoomMap(1, 2.0), null}));
        assertEquals(AstException.AST__OBJIN, e.getStatus());
        SwitchMap fwdOnly = new SwitchMap(halves(), null, new Mapping[] {new ZoomMap(1, 2.0)});
        assertTrue(fwdOnly.getTranForward());
        assertFalse(fwdOnly.getTranInverse());
        SwitchMap invOnly = new SwitchMap(null, halves(), new Mapping[] {new ZoomMap(1, 2.0)});
        assertFalse(invOnly.getTranForward());
        assertTrue(invOnly.getTranInverse());
    }

    /** The route Mappings are shared, so C's immutable attributes cannot be changed. */
    @Test
    void routeMappingsAreShared() {
        ZoomMap z = new ZoomMap(1, 2.0);
        new SwitchMap(halves(), null, new Mapping[] {z});
        AstException e = assertThrows(AstException.class, () -> z.setZoom(3.0));
        assertEquals(AstException.AST__IMMUT, e.getStatus());
    }

    // ------------------------------------------------------------------
    // test_switchmap.rs: the selector value
    // ------------------------------------------------------------------

    /**
     * Rust's {@code a_selector_value_beyond_any_integer_selects_no_route}: C takes {@code
     * round(sel) - 1} as the route index and ignores one out of range.
     */
    @Test
    void aSelectorValueBeyondAnyIntegerSelectsNoRoute() {
        SwitchMap m = new SwitchMap(new UnitMap(1), null, new Mapping[] {new ZoomMap(1, 2.0), new ZoomMap(1, 3.0)});
        double[] inp = {-1.0e30, 0.49, 0.5, 2.25, 2.5, 1.0e30, -Double.MAX_VALUE};
        double[] want = {BAD, BAD, 1.0, 6.75, BAD, BAD, BAD};
        assertArrayEquals(want, m.tran1(inp.length, inp, true));
        assertEquals(BAD, m.rate(new double[] {-1.0e30}, 1, 1));
        assertEquals(BAD, m.rate(new double[] {1.0e30}, 1, 1));
    }

    /**
     * The route each selector value picks, in both directions and in the rate, as C picks it,
     * including values beyond the range of {@code int}, which C converts without a range check
     * and which pick no route, as C's documented contract says of an index below 1 or above the
     * number of routes, and the halfway points, which C's {@code round} takes away from zero.
     */
    @Test
    void selectorValuesPickTheRoutesCPicks() {
        SwitchMap sw =
                new SwitchMap(new UnitMap(1), new UnitMap(1), new Mapping[] {new ZoomMap(1, 2.0), new ZoomMap(1, 3.0)});
        double[] v = {
            -1.0e30,
            -2147483649.0,
            -0.5,
            0.0,
            0.49,
            0.49999999999999994,
            0.5,
            1.4999999999999998,
            1.5,
            2.25,
            2.4999999999999996,
            2.5,
            3.0,
            2147483647.0,
            2147483648.5,
            4294967297.0,
            4294967298.0,
            1.0e30,
            -Double.MAX_VALUE,
            Double.MAX_VALUE,
            BAD
        };
        double[] f = sw.tran1(v.length, v, true);
        double[] i = sw.tran1(v.length, v, false);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < v.length; k++) {
            double r = sw.rate(new double[] {v[k]}, 1, 1);
            sb.append(g(v[k]))
                    .append(" fwd ")
                    .append(g(f[k]))
                    .append(" inv ")
                    .append(g(i[k]))
                    .append(" rate ")
                    .append(g(r))
                    .append('\n');
        }
        assertEquals(probe("selector_values"), sb.toString());
    }

    /** A selector value that is not finite picks no route. */
    @Test
    void aSelectorValueThatIsNotFinitePicksNoRoute() {
        PermMap sel = new PermMap(1, new int[] {1}, 1, new int[] {1}, null);
        SwitchMap sw = new SwitchMap(sel, null, new Mapping[] {new ZoomMap(1, 2.0)});
        double[] in = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0};
        assertArrayEquals(new double[] {BAD, BAD, BAD, 2.0}, sw.tran1(in.length, in, true));
    }

    // ------------------------------------------------------------------
    // testswitchmap.c, through test_switchmap.rs
    // ------------------------------------------------------------------

    private static double[][] tranRows(Mapping m, double[][] rows, boolean forward) {
        int nin = rows[0].length;
        double[][] in = new double[nin][rows.length];
        for (int i = 0; i < rows.length; i++) {
            for (int k = 0; k < nin; k++) {
                in[k][i] = rows[i][k];
            }
        }
        int nout = forward ? m.getNout() : m.getNin();
        double[][] out = m.tranP(rows.length, nin, in, forward, nout);
        double[][] res = new double[rows.length][nout];
        for (int i = 0; i < rows.length; i++) {
            for (int k = 0; k < nout; k++) {
                res[i][k] = out[k][i];
            }
        }
        return res;
    }

    private static void assertClose(double got, double want, double tol, int err) {
        assertNotEquals(BAD, got, "Error " + err + ": BAD");
        assertTrue(Math.abs(got - want) <= tol, "Error " + err + ": " + got + ", want " + want);
    }

    /**
     * The checks C makes of every section after its transforms: simplifying the SwitchMap
     * changes nothing, an inverted one simplifies to a non-inverted one, it cancels against its
     * inverse on either side, and it survives a dump.
     */
    private static void assertSimplificationAndDump(SwitchMap swm, int[] errs, String label) {
        Mapping s = swm.simplify();
        assertSame(swm, s, label);
        assertEquals(ChannelText.dump(swm), ChannelText.dump(s), label);

        SwitchMap invm = (SwitchMap) swm.copy();
        invm.invert();
        assertFalse(invm.simplify().getInvert(), "Error " + errs[0]);

        SwitchMap swm2 = (SwitchMap) invm.copy();
        swm2.invert();
        assertInstanceOf(UnitMap.class, new CmpMap(invm, swm2, true).simplify(), "Error " + errs[1]);
        assertInstanceOf(UnitMap.class, new CmpMap(swm2, invm, true).simplify(), "Error " + errs[2]);

        String plain = ChannelText.dump(invm);
        AstObject back = ChannelText.read(plain);
        assertEquals("SwitchMap", back.astClass(), label);
        assertEquals(plain, ChannelText.dump(back), label + ": dump round trip");
    }

    /** testswitchmap.c section 1. */
    @Test
    void section1TwoRows() {
        SwitchMap swm = section1();
        swm.invert();
        double[][] input = {{1.0, 1.0}, {101.0, 2.0}, {1.0, 2.0}, {101.0, 1.0}};
        double[][] out = tranRows(swm, input, false);
        double[] wantOut = {1000.0, 2700.0, 1600.0, 2000.0};
        for (int i = 0; i < 4; i++) {
            assertClose(out[i][0], wantOut[i], 1.0e-5, 5 + i);
        }
        double[][] back = tranRows(swm, out, true);
        double[][] want = {{1.0, 1.0}, {101.0, 2.0}, {61.0, 1.0}, {37.3636364, 2.0}};
        for (int i = 0; i < 4; i++) {
            assertClose(back[i][0], want[i][0], 1.0e-5, 17 + 2 * i);
            assertClose(back[i][1], want[i][1], 1.0e-5, 18 + 2 * i);
        }

        swm.invert();
        assertSimplificationAndDump(swm, new int[] {25, 26, 27}, "ast_equal 1");

        assertClose(swm.rate(new double[] {20.0, 1.0}, 1, 1), 10.0, 1.0e-6, 28);
        assertClose(swm.rate(new double[] {20.0, 2.0}, 1, 1), 11.0, 1.0e-6, 29);
        swm.invert();
        assertClose(swm.rate(new double[] {1700.0}, 1, 1), 0.1, 1.0e-6, 30);
        assertClose(swm.rate(new double[] {1900.0}, 1, 1), 1.0 / 11.0, 1.0e-6, 31);
    }

    /** testswitchmap.c section 2; inverting the shared selector afterwards changes nothing. */
    @Test
    void section2TwoHalves() {
        MathMap sm = mathmap("y=qif(x>101.5,2,1)", "x=qif(y>1800,2,1)");
        SwitchMap swm = section2(sm);
        swm.invert();
        sm.invert();
        double[][] input = {{1.0}, {202.0}, {102.0}, {101.0}};
        double[][] out = tranRows(swm, input, false);
        double[] wantOut = {1000.0, 2700.0, 1600.0, 2000.0};
        for (int i = 0; i < 4; i++) {
            assertClose(out[i][0], wantOut[i], 1.0e-5, 105 + i);
        }
        double[][] back = tranRows(swm, out, true);
        double[] want = {1.0, 202.0, 61.0, 138.3636364};
        for (int i = 0; i < 4; i++) {
            assertClose(back[i][0], want[i], 1.0e-5, 117 + 2 * i);
        }

        swm.invert();
        assertSimplificationAndDump(swm, new int[] {125, 126, 127}, "ast_equal 2");

        assertClose(swm.rate(new double[] {20.0}, 1, 1), 10.0, 1.0e-6, 128);
        assertClose(swm.rate(new double[] {120.0}, 1, 1), 11.0, 1.0e-6, 129);
        swm.invert();
        assertClose(swm.rate(new double[] {1700.0}, 1, 1), 0.1, 1.0e-6, 130);
        assertClose(swm.rate(new double[] {1900.0}, 1, 1), 1.0 / 11.0, 1.0e-6, 131);
    }

    /** The FrameSet a TAN header with reference pixel {@code crpix1} reads as. */
    private static FrameSet tanFrameSet(double crpix1) {
        FitsChan fc = new FitsChan();
        for (String card : new String[] {
            "CRPIX1  = " + (int) crpix1,
            "CRPIX2  = 45",
            "CRVAL1  = 45",
            "CRVAL2  = 89.9",
            "CDELT1  = -0.01",
            "CDELT2  = 0.01",
            "CTYPE1  = 'RA---TAN'",
            "CTYPE2  = 'DEC--TAN'"
        }) {
            fc.putFits(card, false);
        }
        fc.clear("Card");
        return assertInstanceOf(FrameSet.class, fc.read());
    }

    /**
     * testswitchmap.c section 3: two images of one part of the sky side by side in one grid, a
     * SelectorMap of two Boxes as the forward selector and a PermMap that always picks the first
     * route as the inverse selector. Inverting the shared selector afterwards changes nothing.
     */
    @Test
    void section3TwoImages() {
        Mapping rm0 = tanFrameSet(45.0).getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        FrameSet fs1 = tanFrameSet(135.0);
        Mapping rm1 = fs1.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
        Frame grid = fs1.getFrame(FrameSet.AST__BASE);
        Region box1 = new Box(grid, 1, new double[] {10.0, 10.0}, new double[] {80.0, 80.0}, null);
        Region box2 = new Box(grid, 1, new double[] {100.0, 10.0}, new double[] {170.0, 80.0}, null);
        SelectorMap selmap = new SelectorMap(new Region[] {box1, box2}, BAD);
        PermMap isPerm = new PermMap(1, new int[] {-1}, 2, new int[] {0, 0}, new double[] {1.0});
        SwitchMap swm = new SwitchMap(selmap, isPerm, new Mapping[] {rm0, rm1});
        swm.invert();
        selmap.invert();

        double[][] input = {{5.0, 5.0}, {50.0, 50.0}, {90.0, 50.0}, {140.0, 50.0}};
        double[][] out = tranRows(swm, input, false);
        double[][] rmout = tranRows(rm0, input, true);
        assertArrayEquals(new double[] {BAD, BAD}, out[0], "Errors 132-133");
        assertArrayEquals(rmout[1], out[1], "Errors 134-135");
        assertArrayEquals(new double[] {BAD, BAD}, out[2], "Errors 136-137");
        for (int k = 0; k < 2; k++) {
            assertTrue(Math.abs(out[3][k] - rmout[1][k]) <= 1.0e-6 * Math.abs(rmout[1][k]), "Error " + (138 + k));
        }

        double[][] back = tranRows(swm, out, true);
        assertArrayEquals(new double[] {BAD, BAD}, back[0], "Errors 140-141");
        assertClose(back[1][0], 50.0, 1.0e-6, 142);
        assertClose(back[1][1], 50.0, 1.0e-6, 143);
        assertArrayEquals(new double[] {BAD, BAD}, back[2], "Errors 144-145");
        assertClose(back[3][0], 50.0, 1.0e-6, 146);
        assertClose(back[3][1], 50.0, 1.0e-6, 147);

        swm.invert();
        assertSimplificationAndDump(swm, new int[] {148, 149, 150}, "ast_equal 3");

        int[][] axes = {{1, 1}, {2, 2}, {1, 2}, {2, 1}};
        for (int k = 0; k < axes.length; k++) {
            double r1 = swm.rate(new double[] {140.0, 50.0}, axes[k][0], axes[k][1]);
            double r2 = rm0.rate(new double[] {50.0, 50.0}, axes[k][0], axes[k][1]);
            assertTrue(Math.abs(r1 - r2) <= Math.abs(1.0e-6 * r2), "Error " + (151 + k) + ": " + r1 + " vs " + r2);
        }
    }

    /** The SwitchMaps of testswitchmap.c, at full precision, against the probe. */
    @Test
    void sectionsMatchCExactly() {
        SwitchMap s1 = section1();
        StringBuilder sb = new StringBuilder(tran(s1));
        s1.invert();
        sb.append("-- inverted\n").append(tran(s1));
        s1.invert();
        sb.append(rate("rate 20 1", s1, new double[] {20.0, 1.0}, 1, 1))
                .append(rate("rate 20 2", s1, new double[] {20.0, 2.0}, 1, 1))
                .append(rate("rate 20 2 axis 2", s1, new double[] {20.0, 2.0}, 1, 2));
        s1.invert();
        sb.append(rate("rate inverted 1700", s1, new double[] {1700.0}, 1, 1));
        AstException e = assertThrows(AstException.class, () -> s1.rate(new double[] {1900.0}, 1, 2));
        sb.append(report(e))
                .append("rate inverted 1900: ")
                .append(g(BAD))
                .append("\nstatus ")
                .append(e.getStatus())
                .append('\n');
        assertEquals(probeWithoutSource("section1"), sb.toString());

        MathMap sm = halves();
        SwitchMap s2 = section2(sm);
        sm.invert();
        sb = new StringBuilder(tran(s2));
        s2.invert();
        sb.append("-- inverted\n").append(tran(s2));
        s2.invert();
        sb.append(rate("rate 20", s2, new double[] {20.0}, 1, 1))
                .append(rate("rate 120", s2, new double[] {120.0}, 1, 1))
                .append(rate("rate 300", s2, new double[] {300.0}, 1, 1));
        s2.invert();
        sb.append(rate("rate inverted 1700", s2, new double[] {1700.0}, 1, 1))
                .append(rate("rate inverted 1900", s2, new double[] {1900.0}, 1, 1));
        assertEquals(probe("section2"), sb.toString());
    }

    /** A SwitchMap with no inverse selector, and its rate when inverted, which is bad. */
    @Test
    void aForwardOnlySwitchMapMatchesC() {
        SwitchMap sw = switch2(halves(), null, win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
        StringBuilder sb = new StringBuilder();
        sb.append("TranForward ")
                .append(sw.getTranForward() ? 1 : 0)
                .append(" TranInverse ")
                .append(sw.getTranInverse() ? 1 : 0)
                .append('\n')
                .append(tran(sw))
                .append(rate("rate 20", sw, new double[] {20.0}, 1, 1));
        sw.invert();
        sb.append("inverted TranForward ")
                .append(sw.getTranForward() ? 1 : 0)
                .append(" TranInverse ")
                .append(sw.getTranInverse() ? 1 : 0)
                .append('\n')
                .append(rate("rate inverted 1700", sw, new double[] {1700.0}, 1, 1));
        assertEquals(probe("forward_only"), sb.toString());
    }

    /** Routes inverted when the SwitchMap is made are applied inverted, rates included. */
    @Test
    void invertedRoutesMatchC() {
        SwitchMap sw = switch2(
                halves(), halves(), inv(win(1000.0, 2000.0, 1.0, 101.0)), inv(win(1600.0, 2700.0, 102.0, 202.0)));
        StringBuilder sb = new StringBuilder(tran(sw));
        sb.append(rate("rate 20", sw, new double[] {20.0}, 1, 1));
        sw.invert();
        sb.append(rate("rate inverted 1900", sw, new double[] {1900.0}, 1, 1));
        assertEquals(probe("inverted_routes"), sb.toString());
    }

    /** Inverting the Mappings after the SwitchMap is made does not change it. */
    @Test
    void componentsInvertedAfterwardsChangeNothing() {
        MathMap fs = halves();
        MathMap is = halves();
        WinMap r1 = win(1.0, 101.0, 1000.0, 2000.0);
        WinMap r2 = win(102.0, 202.0, 1600.0, 2700.0);
        SwitchMap sw = switch2(fs, is, r1, r2);
        fs.invert();
        is.invert();
        r1.invert();
        String got = tran(sw) + "Invert fs " + (fs.getInvert() ? 1 : 0) + " is " + (is.getInvert() ? 1 : 0) + " r1 "
                + (r1.getInvert() ? 1 : 0) + " r2 " + (r2.getInvert() ? 1 : 0) + "\n";
        assertEquals(probe("components_inverted_after"), got);
    }

    // ------------------------------------------------------------------
    // Native dumps
    // ------------------------------------------------------------------

    private static void assertRoundTrips(String name, Mapping m) {
        String dump = ChannelText.dump(m);
        Mapping back = (Mapping) ChannelText.read(dump);
        String got = dump + "-- read back\n" + ChannelText.dump(back) + "-- equal " + (m.astEqual(back) ? 1 : 0)
                + "\n-- transformations\n" + tran(m) + "-- transformations read back\n" + tran(back);
        assertEquals(probe(name), got, name);
    }

    /** Native dumps, read back, give C's cards and C's transformations. */
    @Test
    void nativeDumpsMatchC() {
        assertRoundTrips("dump_section1", section1());
        MathMap sm = halves();
        SwitchMap s2 = section2(sm);
        sm.invert();
        assertRoundTrips("dump_section2", s2);
        assertRoundTrips("dump_inverted", inv(plain()));
        assertRoundTrips(
                "dump_forward_only",
                switch2(halves(), null, win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0)));
        assertRoundTrips(
                "dump_inverse_only",
                switch2(null, halves(), win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0)));
        assertRoundTrips(
                "dump_inverted_at_construction",
                switch2(
                        inv(halves()),
                        inv(halves()),
                        inv(win(1000.0, 2000.0, 1.0, 101.0)),
                        win(102.0, 202.0, 1600.0, 2700.0)));
        MathMap fs = halves();
        WinMap r2 = win(102.0, 202.0, 1600.0, 2700.0);
        SwitchMap after = switch2(fs, halves(), win(1.0, 101.0, 1000.0, 2000.0), r2);
        fs.invert();
        r2.invert();
        assertRoundTrips("dump_inverted_after", after);
    }

    /** Mappings of this phase inside a SwitchMap, and a SwitchMap inside other Mappings. */
    @Test
    void nestedNativeDumpsMatchC() {
        LutMap lut = new LutMap(new double[] {1000.0, 1100.0, 1300.0, 1600.0, 2000.0}, 1.0, 25.0);
        MathMap mm = mathmap("y = x * 10 + 990", "x = ( y - 990 ) / 10");
        assertRoundTrips("dump_lutmap_mathmap", switch2(halves(), halves(), lut, mm));
        PermMap fs = new PermMap(2, new int[] {0, 1}, 1, new int[] {2}, new double[] {0.0});
        PcdMap pcd = new PcdMap(1.0e-3, new double[] {1.0, 1.5});
        assertRoundTrips("dump_pcdmap", switch2(fs, null, pcd, new ZoomMap(2, 2.0)));
        assertRoundTrips("dump_in_cmpmap", new CmpMap(new ZoomMap(1, 2.0), plain(), true));
        assertRoundTrips("dump_in_tranmap", new TranMap(plain(), inv(plain())));
        assertRoundTrips("dump_in_switchmap", new SwitchMap(new UnitMap(1), null, new Mapping[] {plain()}));
    }

    /**
     * A SwitchMap whose forward selector was inverted when it was made and whose inverse selector
     * was not does not survive a native round trip, as in C: the dump writes {@code ISInv} only
     * when it is set, and the loader takes an absent {@code ISInv} to be {@code FSInv}, so the
     * SwitchMap read back applies its inverse selector inverted and its inverse transformation
     * changes.
     */
    @Test
    void aRoundTripInvertsAnInverseSelectorAsC() {
        SwitchMap sw =
                switch2(inv(halves()), halves(), win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
        String dump = ChannelText.dump(sw);
        assertFalse(dump.contains("ISInv"));
        assertRoundTrips("dump_fsinv_set_isinv_unset", sw);
        Mapping back = (Mapping) ChannelText.read(dump);
        assertFalse(sw.astEqual(back));
        double[] wave = {1000.0, 1700.0};
        assertArrayEquals(new double[] {1.0, 71.0}, sw.tran1(2, wave, false));
        assertArrayEquals(new double[] {47.45454545454545, 111.09090909090911}, back.tran1(2, wave, false));
    }

    /**
     * A copy holds copies of the Mappings, as C's {@code Copy} makes them, so inverting the
     * original's Mappings changes the original's dump and not the copy's.
     */
    @Test
    void aCopyHoldsCopies() {
        MathMap fs = halves();
        WinMap r1 = win(1.0, 101.0, 1000.0, 2000.0);
        SwitchMap sw = switch2(fs, fs, r1, win(102.0, 202.0, 1600.0, 2700.0));
        String before = ChannelText.dump(sw);
        SwitchMap copy = (SwitchMap) sw.copy();
        fs.invert();
        r1.invert();
        assertNotEquals(before, ChannelText.dump(sw));
        assertEquals(before, ChannelText.dump(copy));
        assertEquals(tran(sw), tran(copy));
    }

    // ------------------------------------------------------------------
    // Equality
    // ------------------------------------------------------------------

    private static String eq(String label, Mapping a, AstObject b) {
        return label + " " + (a.astEqual(b) ? 1 : 0) + "\n";
    }

    /** Every comparison of the probe's {@code equal} case gives C's {@code astEqual}. */
    @Test
    void equalityMatchesC() {
        SwitchMap a = plain();
        SwitchMap c = switch2(halves(), halves(), win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2800.0));
        SwitchMap d = switch2(halves(), null, win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
        SwitchMap e = switch2(halves(), null, win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
        SwitchMap f = new SwitchMap(halves(), halves(), new Mapping[] {
            win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0), win(102.0, 202.0, 1600.0, 2700.0)
        });
        SwitchMap ainv = (SwitchMap) a.copy();
        ainv.invert();
        String before = eq("same_contents", a, plain())
                + eq("different_route", a, c)
                + eq("missing_inverse_selector", a, d)
                + eq("missing_inverse_selector_reversed", d, a)
                + eq("both_forward_only", d, e)
                + eq("three_routes", a, f)
                + eq("inverted_vs_plain", ainv, a);
        Mapping n = ainv.simplify();
        SwitchMap gm =
                switch2(halves(), halves(), inv(win(1000.0, 2000.0, 1.0, 101.0)), win(102.0, 202.0, 1600.0, 2700.0));
        MathMap fs = halves();
        SwitchMap h = switch2(fs, halves(), win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2700.0));
        fs.invert();
        String after = eq("inverted_vs_normalised", ainv, n)
                + eq("normalised_vs_inverted", n, ainv)
                + eq("route_inverted_equivalent", a, gm)
                + eq("selector_inverted_after", a, h)
                + "Invert fs after " + (fs.getInvert() ? 1 : 0) + "\n";
        MathMap sm = halves();
        SwitchMap s2a = section2(sm);
        SwitchMap s2b = section2(halves());
        sm.invert();
        String got = before
                + after
                + eq("shared_selectors", s2a, s2b)
                + invertState(sm)
                + eq("not_a_switchmap", a, new ZoomMap(1, 2.0));
        assertEquals(probe("equal"), got);
    }

    // ------------------------------------------------------------------
    // Simplification
    // ------------------------------------------------------------------

    private static String simp(Mapping m) {
        Mapping s = m.simplify();
        return "class " + s.astClass() + "\n" + ChannelText.dump(s) + "-- transformations\n" + tran(m)
                + "-- transformations simplified\n" + tran(s);
    }

    private static void assertSimplifies(String name, Mapping m) {
        assertEquals(probe(name), simp(m), name);
    }

    /** A SwitchMap alone: unchanged, inverted, and with route and selector Mappings that simplify. */
    @Test
    void aSwitchMapAloneSimplifiesAsC() {
        assertSimplifies("simp_plain", plain());
        assertSimplifies("simp_inverted", inv(plain()));
        assertSimplifies(
                "simp_routes_simplify",
                switch2(
                        halves(),
                        halves(),
                        new CmpMap(new ZoomMap(1, 2.0), new ZoomMap(1, 5.0), true),
                        new CmpMap(win(102.0, 202.0, 1600.0, 2700.0), new UnitMap(1), true)));
        assertSimplifies(
                "simp_inverted_route_simplifies",
                switch2(halves(), halves(), inv(new ZoomMap(1, 0.1)), win(102.0, 202.0, 1600.0, 2700.0)));
        assertSimplifies(
                "simp_selector_simplifies",
                switch2(
                        new CmpMap(new ZoomMap(1, 2.0), new ZoomMap(1, 0.5), true),
                        null,
                        win(0.0, 1.0, 0.0, 10.0),
                        win(0.0, 1.0, 0.0, 20.0)));
        assertSimplifies(
                "simp_inverted_routes_simplify",
                inv(switch2(
                        halves(),
                        halves(),
                        new CmpMap(new ZoomMap(1, 2.0), new ZoomMap(1, 5.0), true),
                        win(102.0, 202.0, 1600.0, 2700.0))));
        assertSimplifies("simp_section1_inverted", inv(section1()));
    }

    /** A SwitchMap beside its own inverse, an equal SwitchMap, an unequal one and a ZoomMap. */
    @Test
    void aSwitchMapBesideOtherMappingsSimplifiesAsC() {
        SwitchMap sw = plain();
        SwitchMap swi = (SwitchMap) sw.copy();
        swi.invert();
        assertSimplifies("simp_beside_inverse", new CmpMap(sw, swi, true));
        assertSimplifies("simp_inverse_beside", new CmpMap(swi, sw, true));
        assertSimplifies("simp_beside_inverse_parallel", new CmpMap(sw, swi, false));
        assertSimplifies("simp_beside_inverse_inverted_cmpmap", inv(new CmpMap(sw, swi, true)));
        assertSimplifies(
                "simp_beside_inverse_between",
                new CmpMap(
                        new CmpMap(new ZoomMap(1, 2.0), sw, true), new CmpMap(swi, new ZoomMap(1, 0.5), true), true));
        assertSimplifies("simp_beside_equal", new CmpMap(sw, plain(), true));
        SwitchMap swc = switch2(halves(), halves(), win(1.0, 101.0, 1000.0, 2000.0), win(102.0, 202.0, 1600.0, 2800.0));
        swc.invert();
        assertSimplifies("simp_beside_unequal_inverse", new CmpMap(sw, swc, true));
        assertSimplifies("simp_beside_zoommap", new CmpMap(new ZoomMap(1, 2.0), sw, true));
        SwitchMap s1 = section1();
        SwitchMap s1i = (SwitchMap) s1.copy();
        s1i.invert();
        assertSimplifies("simp_section1_beside_inverse", new CmpMap(s1, s1i, true));
        assertSimplifies("simp_section1_inverse_beside", new CmpMap(s1i, s1, true));
    }

    /**
     * C sets the Invert attribute of each Mapping it holds while it simplifies, and sets them back
     * in the same order, so a Mapping held twice is left with the value recorded for its first
     * place rather than its own.
     */
    @Test
    void aMappingHeldTwiceIsLeftAsC() {
        MathMap sm = halves();
        SwitchMap sw = section2(sm);
        sm.invert();
        assertEquals(probe("simp_section2_shared_selector"), simp(sw) + "-- " + invertState(sm));
        MathMap sm2 = halves();
        SwitchMap swb = section2(sm2);
        sm2.invert();
        assertEquals(probe("simp_section2_shared_selector_inverted"), simp(inv(swb)) + "-- " + invertState(sm2));
        WinMap r = win(1.0, 101.0, 1000.0, 2000.0);
        SwitchMap shared = switch2(halves(), halves(), r, r);
        r.invert();
        assertEquals(probe("simp_shared_route"), simp(shared) + "-- " + invertState(r));
    }

    /** The C fixtures test_switchmap.rs reads. */
    @Test
    void switchmapSimplifyFixtures() {
        Mapping cancel = (Mapping) ChannelText.read(Fixtures.read("simplify/switchmap_inverse_cancel.map"));
        Mapping s = cancel.simplify();
        assertInstanceOf(UnitMap.class, s);
        assertEquals(1, s.getNin());
        assertEquals(1, s.getNout());
        for (String name : new String[] {"switchmap_internal_simplify", "switchmap_invert_normalize"}) {
            Mapping original = (Mapping) ChannelText.read(Fixtures.read("simplify/" + name + ".map"));
            int nin = original.getNin();
            int nout = original.getNout();
            Mapping simplified = original.simplify();
            assertEquals(nin, simplified.getNin(), name);
            assertEquals(nout, simplified.getNout(), name);
        }
    }
}
