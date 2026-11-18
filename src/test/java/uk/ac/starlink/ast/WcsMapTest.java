package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.ac.starlink.ast.MappingTestData.point;
import static uk.ac.starlink.ast.MappingTestData.transformRows;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.KernelInputs;

/**
 * The tests of Rust {@code tests/test_wcsmap.rs}: construction, round trips, bad values, the
 * LonCheck attribute, projection parameters, native round trips and errors for unusable
 * parameters.
 *
 * <p>Deferred: {@code wcsmap_equal_*} ({@code astEqual}, out of scope). Rust's {@code try_merge}
 * of two WcsMaps is the simplification of their series CmpMap, which wcsmap.c {@code MapMerge}
 * merges, and the comparison with C's simplified fixture made with Rust's {@code equal} is made
 * with the dumps.
 */
class WcsMapTest {

    private static final double BAD = AstObject.AST__BAD;

    private static void assertClose(double actual, double expected, double tol, String label) {
        assertTrue(Math.abs(actual - expected) < tol, label + ": expected " + expected + ", got " + actual);
    }

    /** A WcsMap with longitude axis {@code lonax} and latitude axis {@code latax}. */
    private static WcsMap wcs(int ncoord, int type, int lonax, int latax) {
        return new WcsMap(ncoord, type, lonax, latax);
    }

    private static double[] tran(Mapping map, boolean forward, double... row) {
        return point(transformRows(map, forward, row), 0);
    }

    /** The round trip of (lon, lat) through {@code map}. */
    private static void assertRoundTrip(WcsMap map, double lon, double lat, double tol, String label) {
        double[] out = tran(map, true, lon, lat);
        double[] back = tran(map, false, out);
        assertClose(back[0], lon, tol, label + " lon");
        assertClose(back[1], lat, tol, label + " lat");
    }

    @Test
    void newBasic() {
        WcsMap w = wcs(2, WcsMap.AST__TAN, 1, 2);
        assertEquals(2, w.getNin());
        assertEquals(2, w.getNout());
        assertEquals(1, w.getWcsAxis(1));
        assertEquals(2, w.getWcsAxis(2));
        assertEquals(WcsMap.AST__TAN, w.getWcsType());
        assertFalse(w.getInvert());
    }

    @Test
    void newRejectsNcoordLessThan2() {
        assertEquals(
                AstException.AST__WCSNC,
                assertThrows(AstException.class, () -> wcs(1, WcsMap.AST__TAN, 1, 1))
                        .getStatus());
    }

    @Test
    void newRejectsEqualAxes() {
        assertEquals(
                AstException.AST__WCSAX,
                assertThrows(AstException.class, () -> wcs(2, WcsMap.AST__TAN, 1, 1))
                        .getStatus());
    }

    @Test
    void newRejectsAxisOutOfRange() {
        assertThrows(AstException.class, () -> wcs(2, WcsMap.AST__TAN, 0, 2));
        assertThrows(AstException.class, () -> wcs(2, WcsMap.AST__TAN, 3, 2));
        assertThrows(AstException.class, () -> wcs(2, WcsMap.AST__TAN, 1, 3));
    }

    @Test
    void tanForwardInverseRoundTrip() {
        assertRoundTrip(wcs(2, WcsMap.AST__TAN, 1, 2), 0.1, 1.4, 1e-10, "TAN");
    }

    @Test
    void carRoundTrip() {
        assertRoundTrip(wcs(2, WcsMap.AST__CAR, 1, 2), 0.5, 0.3, 1e-10, "CAR");
    }

    @Test
    void aitRoundTrip() {
        assertRoundTrip(wcs(2, WcsMap.AST__AIT, 1, 2), 0.4, 0.6, 1e-10, "AIT");
    }

    @Test
    void multiAxisPassthrough() {
        WcsMap w = wcs(4, WcsMap.AST__TAN, 2, 3);
        assertEquals(4, w.getNin());
        assertEquals(4, w.getNout());
        assertEquals(2, w.getWcsAxis(1));
        assertEquals(3, w.getWcsAxis(2));
        double[] out = tran(w, true, 100.0, 0.1, 1.3, 200.0);
        assertEquals(100.0, out[0]);
        assertEquals(200.0, out[3]);
        double[] back = tran(w, false, out);
        assertEquals(100.0, back[0]);
        assertEquals(200.0, back[3]);
        assertClose(back[1], 0.1, 1e-10, "lon inverse");
        assertClose(back[2], 1.3, 1e-10, "lat inverse");
        assertTrue(Math.abs(out[1] - 0.1) > 1e-6 || Math.abs(out[2] - 1.3) > 1e-6);
    }

    @Test
    void badValueOnLon() {
        assertArrayEquals(new double[] {BAD, BAD}, tran(wcs(2, WcsMap.AST__TAN, 1, 2), true, BAD, 1.0));
    }

    @Test
    void badValueOnLat() {
        assertArrayEquals(new double[] {BAD, BAD}, tran(wcs(2, WcsMap.AST__TAN, 1, 2), true, 0.1, BAD));
    }

    @Test
    void nanTreatedAsBad() {
        assertArrayEquals(new double[] {BAD, BAD}, tran(wcs(2, WcsMap.AST__TAN, 1, 2), true, Double.NaN, 1.0));
    }

    // AIT at (3, 0) radians, 171.887 degrees: AIT's domain test gives u = 1 - x*x*w[2] = 0.4375,
    // below 0.5 - tol, so astAITrev returns status 2 and the point is bad whatever LonCheck is.
    @Test
    void aitInverseOutOfRangeClippedWhenLoncheckOn() {
        assertArrayEquals(new double[] {BAD, BAD}, tran(wcs(2, WcsMap.AST__AIT, 1, 2), false, 3.0, 0.0));
    }

    @Test
    void aitInverseOutOfRangeBadRegardlessOfLoncheckOff() {
        WcsMap w = wcs(2, WcsMap.AST__AIT, 1, 2);
        w.setLonCheck(false);
        assertArrayEquals(new double[] {BAD, BAD}, tran(w, false, 3.0, 0.0));
    }

    // CAR at (3.5, 0) radians: the longitude, 200.5 degrees, is outside [-180, 180); bad with
    // LonCheck on, passed through with it off.
    @Test
    void carInverseOutOfRangeClippedWhenLoncheckOn() {
        assertArrayEquals(new double[] {BAD, BAD}, tran(wcs(2, WcsMap.AST__CAR, 1, 2), false, 3.5, 0.0));
    }

    @Test
    void carInverseOutOfRangeKeptWhenLoncheckOff() {
        WcsMap w = wcs(2, WcsMap.AST__CAR, 1, 2);
        w.setLonCheck(false);
        double[] out = tran(w, false, 3.5, 0.0);
        assertClose(out[0], 3.5, 1e-12, "car lon (loncheck off)");
        assertClose(out[1], 0.0, 1e-12, "car lat (loncheck off)");
    }

    @Test
    void zeaInverseOffDiscBadRegardlessOfLoncheck() {
        for (boolean loncheck : new boolean[] {true, false}) {
            WcsMap w = wcs(2, WcsMap.AST__ZEA, 1, 2);
            w.setLonCheck(loncheck);
            assertArrayEquals(new double[] {BAD, BAD}, tran(w, false, 3.0, 0.0), "loncheck=" + loncheck);
        }
    }

    @Test
    void aitInverseInDomainUnaffectedByLoncheck() {
        double[] out = tran(wcs(2, WcsMap.AST__AIT, 1, 2), false, 0.0, 0.0);
        assertClose(out[0], 0.0, 1e-12, "ait origin lon");
        assertClose(out[1], 0.0, 1e-12, "ait origin lat");
    }

    @Test
    void invertedFlag() {
        WcsMap w = wcs(2, WcsMap.AST__TAN, 1, 2);
        assertFalse(w.getInvert());
        w.invert();
        assertTrue(w.getInvert());
        double[] fwd = tran(wcs(2, WcsMap.AST__TAN, 1, 2), true, 0.1, 1.4);
        double[] invFwd = tran(w, true, fwd);
        assertClose(invFwd[0], 0.1, 1e-10, "inverted forward lon");
        assertClose(invFwd[1], 1.4, 1e-10, "inverted forward lat");
    }

    @Test
    void setPvParameter() {
        WcsMap w = wcs(2, WcsMap.AST__AZP, 1, 2);
        w.set("PV2_1=45.0");
        assertRoundTrip(w, 0.1, 1.0, 1e-10, "AZP+PV");
    }

    @Test
    void setPvWrongAxisRejected() {
        WcsMap w = wcs(4, WcsMap.AST__TAN, 2, 3);
        for (String setting : new String[] {"PV1_0=1", "PV4_0=1", "PV0_0=1", "PV5_0=1"}) {
            assertEquals(
                    AstException.AST__AXIIN,
                    assertThrows(AstException.class, () -> w.set(setting)).getStatus(),
                    setting);
        }
    }

    @Test
    void cloneIndependence() {
        WcsMap w = wcs(2, WcsMap.AST__TAN, 1, 2);
        WcsMap w2 = (WcsMap) w.copy();
        assertArrayEquals(tran(w, true, 0.1, 1.4), tran(w2, true, 0.1, 1.4));
    }

    @Test
    void simplifiedProducesWorkingClone() {
        Mapping s = wcs(2, WcsMap.AST__TAN, 1, 2).simplify();
        double[] out = tran(s, true, 0.1, 1.4);
        double[] back = tran(s, false, out);
        assertClose(back[0], 0.1, 1e-10, "simplified lon");
        assertClose(back[1], 1.4, 1e-10, "simplified lat");
    }

    // ------------------------------------------------------------------
    // Merging (wcsmap.c MapMerge)
    // ------------------------------------------------------------------

    /** A ZPN WcsMap with parameter PV2_1 set to {@code pv21}. */
    private static WcsMap zpn(double pv21) {
        WcsMap w = wcs(2, WcsMap.AST__ZPN, 1, 2);
        w.set("PV2_1=" + pv21);
        return w;
    }

    @Test
    void wcsmapNoCancelDifferentParams() {
        WcsMap w2 = zpn(0.7);
        w2.invert();
        assertFalse(new CmpMap(zpn(0.5), w2, true).simplify() instanceof UnitMap);
    }

    @Test
    void wcsmapCancelSameParams() {
        WcsMap w1 = zpn(0.5);
        w1.invert();
        assertInstanceOf(UnitMap.class, new CmpMap(w1, zpn(0.5), true).simplify());
    }

    /** The simplification of the Mapping of a simplify fixture, and C's, as dumps. */
    private static String[] simplifiedFixture(String name) {
        Mapping map = (Mapping) ChannelText.read(Fixtures.read("simplify/" + name + ".map"));
        String want = SerialisationCorpusTest.stripLeadingComments(Fixtures.read("simplify/" + name + ".simp"));
        return new String[] {ChannelText.dump(map.simplify()), want};
    }

    /**
     * A forward TAN WcsMap, a PermMap swapping the axes and the inverse of a TAN WcsMap with the
     * axes swapped: the WcsMap is swapped past the PermMap (wcsmap.c {@code WcsPerm}) and cancels,
     * leaving C's PermMap.
     */
    @Test
    void wcsmapPermSwapCancelSimplify() {
        String[] got = simplifiedFixture("wcsmap_perm_swap_cancel");
        assertTrue(got[0].startsWith(" Begin PermMap"), got[0]);
        assertTrue(CValues.nativeStringsEquivalent(got[0], got[1], 4), got[0]);
    }

    /**
     * A forward TAN WcsMap of three axes followed by a PermMap that keeps only the third and gives
     * its longitude and latitude constants: the swap gives a UnitMap and a PermMap whose constants
     * have been through the inverse projection, C's PermMap.
     */
    @Test
    void wcsmapPermSwapSimplifyConstants() {
        String[] got = simplifiedFixture("wcsmap_perm_swap_simplify");
        assertTrue(got[0].startsWith(" Begin PermMap"), got[0]);
        assertTrue(CValues.nativeStringsEquivalent(got[0], got[1], 4), got[0]);
    }

    @Test
    void wcsmapAbsorbsAdjacentPermmap() {
        WcsMap wcsFwd = wcs(2, WcsMap.AST__TAN, 1, 2);
        PermMap pm = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        WcsMap wcsInv = wcs(2, WcsMap.AST__TAN, 2, 1);
        wcsInv.invert();
        CmpMap full = new CmpMap(wcsFwd, new CmpMap(pm, wcsInv, true), true);
        Mapping simplified = full.simplify();
        assertInstanceOf(PermMap.class, simplified);
        double[] out = tran(simplified, true, 0.1, 1.2);
        assertClose(out[0], 1.2, 1e-10, "swap axis 0");
        assertClose(out[1], 0.1, 1e-10, "swap axis 1");
    }

    /** An AZP WcsMap, with PV2_1 set to 0.5 if {@code withParam}. */
    private static WcsMap azp(boolean withParam) {
        WcsMap w = wcs(2, WcsMap.AST__AZP, 1, 2);
        if (withParam) {
            w.set("PV2_1=0.5");
        }
        return w;
    }

    /**
     * wcsmap.c {@code CanMerge} compares the parameters up to the number the first WcsMap holds
     * (it compares that WcsMap's count with itself, wcsmap.c:914), so a WcsMap with none cancels
     * with the inverse of one with PV2_1 = 0.5, changing what the Mapping computes, while the
     * other order compares PV2_1 and does not cancel. C (pinned build, cfuzz wcsprobe 1 and 2): a
     * UnitMap, with (0.1, 1.2) going to (0.1, 1.1914111091742374) before simplification; and an
     * unsimplified CmpMap.
     */
    @Test
    void aWcsMapCancelsWithAnInverseHavingParametersItLacks() {
        WcsMap second = azp(true);
        second.invert();
        CmpMap c = new CmpMap(azp(false), second, true);
        assertClose(tran(c, true, 0.1, 1.2)[1], 1.1914111091742374, 1e-12, "before");
        assertInstanceOf(UnitMap.class, c.simplify());

        WcsMap first = azp(false);
        first.invert();
        assertInstanceOf(CmpMap.class, new CmpMap(azp(true), first, true).simplify());
    }

    /**
     * wcsmap.c {@code WcsPerm} keeps the PermMap it swaps past and gives it its own Invert
     * attribute (wcsmap.c:4726, 4744), not the value the list applies it with. A PermMap of two
     * inputs and three outputs before a WcsMap of three axes, in a CmpMap that is then inverted,
     * is applied inverted in the list; the swap makes it forward, so the simplified Mapping has
     * two inputs and three outputs where the CmpMap has three and two. C (pinned build, cfuzz
     * wcsprobe 3) gives this dump.
     */
    @Test
    void aPermMapSwappedPastAWcsMapTakesItsOwnInvert() {
        PermMap p = new PermMap(2, new int[] {1, 2}, 3, new int[] {1, 2, -1}, new double[] {5.0});
        CmpMap c = new CmpMap(p, wcs(3, WcsMap.AST__TAN, 1, 2), true);
        c.invert();
        Mapping s = c.simplify();
        String want = String.join(
                "\n",
                " Begin CmpMap \t# Compound Mapping",
                "    Nin = 2 \t# Number of input coordinates",
                "    Nout = 3 \t# Number of output coordinates",
                "    IsSimp = 1 \t# Mapping has been simplified",
                " IsA Mapping \t# Mapping between coordinate systems",
                "    InvA = 1 \t# First Mapping used in inverse direction",
                "    MapA = \t# First component Mapping",
                "       Begin WcsMap \t# FITS-WCS sky projection",
                "          Nin = 2 \t# Number of input coordinates",
                "          Invert = 1 \t# Mapping inverted",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          Type = \"TAN\" \t# Gnomonic projection",
                "       End WcsMap",
                "    MapB = \t# Second component Mapping",
                "       Begin PermMap \t# Coordinate permutation",
                "          Nin = 2 \t# Number of input coordinates",
                "          Nout = 3 \t# Number of output coordinates",
                "       IsA Mapping \t# Mapping between coordinate systems",
                "          Out1 = 1 \t# Output coordinate 1 = input coordinate 1",
                "          Out2 = 2 \t# Output coordinate 2 = input coordinate 2",
                "          Out3 = -1 \t# Output coordinate 3 = constant no. 1",
                "          In1 = 1 \t# Input coordinate 1 = output coordinate 1",
                "          In2 = 2 \t# Input coordinate 2 = output coordinate 2",
                "          Nconst = 1 \t# Number of constants",
                "          Con1 = 5 \t# Constant number 1",
                "       End PermMap",
                " End CmpMap",
                "");
        String got = ChannelText.dump(s);
        assertTrue(CValues.nativeStringsEquivalent(got, want, 4), got);
        assertEquals(3, c.getNin());
        assertEquals(2, s.getNin());
    }

    @Test
    void rateNumeric() {
        double r = wcs(2, WcsMap.AST__TAN, 1, 2).rate(new double[] {0.0, 1.4}, 2, 2);
        assertTrue(Double.isFinite(r), "rate should be finite, got " + r);
    }

    @Test
    void serialisationRoundTripTan() {
        String text = ChannelText.dump(wcs(2, WcsMap.AST__TAN, 1, 2));
        assertTrue(text.contains("Begin WcsMap"));
        assertTrue(text.contains("End WcsMap"));
        assertTrue(text.contains("Type"));
        assertTrue(text.contains("TAN"));
        Mapping m = (Mapping) ChannelText.read(text);
        assertEquals(2, m.getNin());
        assertEquals(2, m.getNout());
        double[] out = tran(m, true, 0.1, 1.4);
        double[] back = tran(m, false, out);
        assertClose(back[0], 0.1, 1e-10, "restored lon");
        assertClose(back[1], 1.4, 1e-10, "restored lat");
    }

    @Test
    void serialisationRoundTripNonDefaultAxes() {
        String text = ChannelText.dump(wcs(4, WcsMap.AST__CAR, 3, 4));
        assertTrue(text.contains("WcsAx1"));
        assertTrue(text.contains("WcsAx2"));
        assertTrue(text.contains("CAR"));
        WcsMap w = (WcsMap) ChannelText.read(text);
        assertEquals(3, w.getWcsAxis(1));
        assertEquals(4, w.getWcsAxis(2));
        assertEquals(WcsMap.AST__CAR, w.getWcsType());
    }

    @Test
    void serialisationRoundTripWithPv() {
        WcsMap w = wcs(2, WcsMap.AST__AZP, 1, 2);
        w.set("PV2_1=45");
        String text = ChannelText.dump(w);
        assertTrue(text.contains("PV2_1"));
        assertRoundTrip((WcsMap) ChannelText.read(text), 0.1, 1.0, 1e-10, "PV restored");
    }

    @Test
    void serialisationInverted() {
        WcsMap w = wcs(2, WcsMap.AST__TAN, 1, 2);
        w.invert();
        String text = ChannelText.dump(w);
        assertTrue(text.contains("Invert"));
        assertTrue(((Mapping) ChannelText.read(text)).getInvert());
    }

    @Test
    void multipleProjectionsRoundTrip() {
        int[] types = {
            WcsMap.AST__TAN,
            WcsMap.AST__CAR,
            WcsMap.AST__AIT,
            WcsMap.AST__ARC,
            WcsMap.AST__STG,
            WcsMap.AST__MER,
            WcsMap.AST__ZEA,
            WcsMap.AST__MOL,
            WcsMap.AST__SIN,
            WcsMap.AST__SFL
        };
        for (int type : types) {
            assertRoundTrip(wcs(2, type, 1, 2), 0.05, 0.8, 1e-9, "type " + type);
        }
    }

    /** C has no projection functions for NCP: wcsmap.c Map returns 1, which Transform reports. */
    @Test
    void ncpProjection() {
        WcsMap w = wcs(2, WcsMap.AST__NCP, 1, 2);
        assertEquals(WcsMap.AST__NCP, w.getWcsType());
        for (boolean forward : new boolean[] {true, false}) {
            AstException e = assertThrows(AstException.class, () -> tran(w, forward, 0.05, 1.2));
            assertEquals(AstException.AST__WCSTY, e.getStatus(), "forward=" + forward);
        }
    }

    @Test
    void glsProjection() {
        WcsMap w = wcs(2, WcsMap.AST__GLS, 1, 2);
        assertEquals(WcsMap.AST__GLS, w.getWcsType());
        assertRoundTrip(w, 0.3, 0.5, 1e-10, "GLS");
    }

    @Test
    void wcsmapNatlatNatlon() {
        WcsMap tan = wcs(2, WcsMap.AST__TAN, 1, 2);
        assertClose(tan.natLat(), Math.PI / 2, 1e-10, "TAN NatLat");
        assertClose(tan.natLon(), 0.0, 1e-10, "TAN NatLon");
        WcsMap car = wcs(2, WcsMap.AST__CAR, 1, 2);
        assertClose(car.natLat(), 0.0, 1e-10, "CAR NatLat");
        assertClose(car.natLon(), 0.0, 1e-10, "CAR NatLon");
        assertClose(wcs(2, WcsMap.AST__ARC, 1, 2).natLat(), Math.PI / 2, 1e-10, "ARC NatLat");
    }

    /**
     * A WcsMap of type AST__WCSBAD is the identity: C's Transform copies every axis
     * (wcsmap.c:4247), and its NatLon and NatLat are 0.
     */
    @Test
    void unknownProjectionIsIdentity() {
        WcsMap wm = wcs(2, WcsMap.AST__WCSBAD, 1, 2);
        double[][] pts = {{0.3, 0.7}, {-1.2, 0.45}};
        for (boolean forward : new boolean[] {true, false}) {
            double[][] out = transformRows(wm, forward, pts);
            for (int i = 0; i < 2; i++) {
                assertArrayEquals(pts[i], point(out, i));
            }
        }
        assertEquals(0.0, wm.natLon());
        assertEquals(0.0, wm.natLat());
        // wcsmap.c:3140-3150: an AST__WCSBAD WcsMap is replaced by a UnitMap.
        assertInstanceOf(UnitMap.class, wm.simplify());
    }

    /**
     * astGetPVMax reports the longitude axis's limit from the PrjInfo table's mxpar2 column and
     * the latitude axis's from mxpar (wcsmap.c:2205-2210), -1 for any other axis, and an error for
     * an axis out of range; NCP keeps its own limits.
     */
    @Test
    void pvMaxIsPerAxis() {
        WcsMap map = wcs(2, WcsMap.AST__TAN, 1, 2);
        assertEquals(4, map.getI("PVMax(1)"));
        assertEquals(0, map.getI("PVMax(2)"));
        WcsMap map3 = wcs(3, WcsMap.AST__TAN, 1, 2);
        assertEquals(-1, map3.getI("PVMax(3)"));
        assertEquals(
                AstException.AST__AXIIN,
                assertThrows(AstException.class, () -> map3.getI("PVMax(0)")).getStatus());
        assertEquals(2, wcs(2, WcsMap.AST__NCP, 1, 2).getI("PVMax(2)"));
    }

    /** C's SetPV raises AST__AXIIN for a parameter index the projection cannot use (wcsmap.c:3884-3890). */
    @Test
    void setPvRejectsAnIndexTheProjectionCannotUse() {
        WcsMap map = wcs(2, WcsMap.AST__TAN, 1, 2);
        map.set("PV2_0=0");
        assertEquals(
                AstException.AST__AXIIN,
                assertThrows(AstException.class, () -> map.set("PV2_1=0")).getStatus());
        map.set("PV1_4=0");
        assertThrows(AstException.class, () -> map.set("PV1_5=0"));
        assertFalse(map.test("PV2_1"));
        WcsMap zpn = wcs(2, WcsMap.AST__ZPN, 1, 2);
        zpn.set("PV2_99=0");
        zpn.set("PV2_100=0");
        assertThrows(AstException.class, () -> zpn.set("PV2_101=0"));
    }

    private static WcsMap withLatPv(int type, double... pairs) {
        WcsMap m = wcs(2, type, 1, 2);
        for (int i = 0; i < pairs.length; i += 2) {
            m.setPV(1, (int) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /**
     * C refuses to transform when PV2_1 of a conic or Bonne projection is missing, and when
     * wcslib reports the parameters unusable (status 1); the expectations are C's, from astTran2
     * on the same WcsMaps.
     */
    @Test
    void unusableProjectionParametersAreErrors() {
        for (WcsMap map : new WcsMap[] {withLatPv(WcsMap.AST__COP), withLatPv(WcsMap.AST__BON)}) {
            for (boolean forward : new boolean[] {true, false}) {
                AstException e = assertThrows(AstException.class, () -> tran(map, forward, 0.1, 0.2));
                assertEquals(AstException.AST__WCSPA, e.getStatus());
            }
        }
        WcsMap azp = withLatPv(WcsMap.AST__AZP, 1, -1.0);
        assertEquals(
                AstException.AST__WCSPA,
                assertThrows(AstException.class, () -> tran(azp, true, 0.1, 0.2))
                        .getStatus());
        WcsMap cyp = withLatPv(WcsMap.AST__CYP, 1, -1.0, 2, 1.0);
        assertEquals(
                AstException.AST__WCSPA,
                assertThrows(AstException.class, () -> tran(cyp, true, 0.1, 0.2))
                        .getStatus());
        tran(withLatPv(WcsMap.AST__COP, 1, 45.0), true, 0.1, 0.2);
    }

    /** wcsmap.c GetNatLat: a conic's native latitude is its PV2_1, bad while that is unset. */
    @Test
    void conicNativeLatitudeIsItsPv() {
        assertEquals(BAD, wcs(2, WcsMap.AST__COP, 1, 2).natLat());
        assertEquals(Math.PI / 4, withLatPv(WcsMap.AST__COP, 1, 45.0).natLat());
        assertEquals(Math.PI / 2, wcs(2, WcsMap.AST__TAN, 1, 2).natLat());
    }

    private static boolean sameValue(double a, double b) {
        return (Double.isNaN(a) && Double.isNaN(b)) || Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    /** The TPN parameters of the Rust fixture {@code wcsconv/inputs/dss.ast}. */
    private static WcsMap dssTpn() {
        WcsMap m = wcs(2, WcsMap.AST__TPN, 1, 2);
        double[][] lon = {
            {0, 0.00037777813768480556},
            {1, 0.018675372165510556},
            {2, 1.4659181119170556e-05},
            {4, -5.654183449024166e-09},
            {5, -1.6598619578175834e-10},
            {6, 3.324645548432778e-09},
            {7, 6.80293751629639e-10},
            {8, -1.0315391309210556e-11},
            {9, 6.577018409631667e-10},
            {10, 4.6843790588691666e-11},
            {17, 0.0},
            {19, 0.0},
            {21, 0.0}
        };
        double[][] lat = {
            {0, 0.000207343956905325},
            {1, 0.01867508980654278},
            {2, -1.6578391725152224e-05},
            {4, -5.137876793798055e-09},
            {5, -1.7623932712259446e-09},
            {6, 2.716154731325139e-10},
            {7, 7.088907407099166e-10},
            {8, 1.8432618513145277e-11},
            {9, 6.849106198956944e-10},
            {10, 7.332585963470833e-13},
            {17, 0.0},
            {19, 0.0},
            {21, 0.0}
        };
        for (double[] p : lon) {
            m.setPV(0, (int) p[0], p[1]);
        }
        for (double[] p : lat) {
            m.setPV(1, (int) p[0], p[1]);
        }
        return m;
    }

    private static void assertBatchMatchesSinglePoints(
            WcsMap map, double[][] rows, boolean forward, double[][] batch, String label) {
        for (int i = 0; i < rows.length; i++) {
            double[] single = tran(map, forward, rows[i]);
            for (int axis = 0; axis < 2; axis++) {
                assertTrue(
                        sameValue(batch[axis][i], single[axis]),
                        label + ": point " + i + " axis " + axis + ": batch " + batch[axis][i] + ", alone "
                                + single[axis]);
            }
        }
    }

    /**
     * Every point of a batch transforms as it does alone, whatever the length of the batch and
     * for both the BAD and NaN conventions, through zenithal, polynomial, TPN, HEALPix and conic
     * projections.
     */
    @Test
    void bulkAndSinglePointGiveIdenticalBits() {
        WcsMap[] maps = {
            wcs(2, WcsMap.AST__TAN, 1, 2),
            wcs(2, WcsMap.AST__SIN, 1, 2),
            withLatPv(WcsMap.AST__ZPN, 1, 1.0, 3, 0.05),
            dssTpn(),
            wcs(2, WcsMap.AST__HPX, 1, 2),
            withLatPv(WcsMap.AST__COD, 1, 45.0, 2, 10.0)
        };
        KernelInputs.SplitMix64 rng = new KernelInputs.SplitMix64(0x5763734d6170L);
        double halfPi = Math.PI / 2;
        for (WcsMap map : maps) {
            for (boolean forward : new boolean[] {true, false}) {
                for (int n : new int[] {1, 2, 3, 7, 1001}) {
                    double[][] rows = new double[n][];
                    for (int i = 0; i < n; i++) {
                        rows[i] = forward
                                ? new double[] {uniform(rng, -Math.PI, Math.PI), uniform(rng, -halfPi, halfPi)}
                                : new double[] {uniform(rng, -1.5, 1.5), uniform(rng, -1.5, 1.5)};
                    }
                    String label = map.getWcsType() + " forward=" + forward + " n=" + n;
                    assertBatchMatchesSinglePoints(map, rows, forward, transformRows(map, forward, rows), label);
                    double[][] viaNan = map.transformNanProtocol(MappingTestData.fromRows(rows), n, forward);
                    assertBatchMatchesSinglePoints(map, rows, forward, viaNan, label + " (NaN protocol)");
                }
            }
        }
    }

    private static double uniform(KernelInputs.SplitMix64 rng, double lo, double hi) {
        return lo + (hi - lo) * rng.nextF64();
    }

    /** A bad point and a NaN point in a batch are bad, and the points around them transform as they do alone. */
    @Test
    void oneBadAndOneNanPointLeaveNeighboursAlone() {
        WcsMap map = wcs(2, WcsMap.AST__TAN, 1, 2);
        for (boolean forward : new boolean[] {true, false}) {
            double[][] rows = new double[7][];
            for (int i = 0; i < 7; i++) {
                double t = i;
                rows[i] = forward
                        ? new double[] {0.3 * t - 1.0, 1.0 + 0.05 * t}
                        : new double[] {0.1 * t - 0.3, 0.2 - 0.07 * t};
            }
            rows[2][1] = BAD;
            rows[5][0] = Double.NaN;
            double[][] out = transformRows(map, forward, rows);
            for (int i : new int[] {2, 5}) {
                assertArrayEquals(new double[] {BAD, BAD}, point(out, i), "point " + i);
            }
            double[][] nanIn = MappingTestData.fromRows(rows);
            nanIn[1][2] = Double.NaN;
            double[][] nanOut = new double[2][7];
            map.transformIntoNan(nanIn, 7, forward, nanOut);
            for (int i : new int[] {2, 5}) {
                assertTrue(Double.isNaN(nanOut[0][i]) && Double.isNaN(nanOut[1][i]), "point " + i);
            }
            double[][] protocol = map.transformNanProtocol(MappingTestData.fromRows(rows), 7, forward);
            for (int i : new int[] {2, 5}) {
                assertArrayEquals(new double[] {BAD, BAD}, point(protocol, i), "point " + i);
            }
            for (int i : new int[] {0, 1, 3, 4, 6}) {
                double[] single = tran(map, forward, rows[i]);
                for (int axis = 0; axis < 2; axis++) {
                    assertNotEquals(BAD, single[axis]);
                    assertTrue(sameValue(out[axis][i], single[axis]));
                    assertTrue(sameValue(nanOut[axis][i], single[axis]));
                    assertTrue(sameValue(protocol[axis][i], single[axis]));
                }
            }
        }
    }

    /**
     * Unusable projection parameters are reported for a point that reaches the projection; a
     * batch of only bad points never does. CYP with PV2_2 = 0 fails its set-up; ZPN with every
     * coefficient zero has no polynomial to invert.
     */
    @Test
    void unusableParametersAreAnErrorOnlyForGoodPoints() {
        WcsMap cyp = withLatPv(WcsMap.AST__CYP, 2, 0.0);
        WcsMap zpn = wcs(2, WcsMap.AST__ZPN, 1, 2);
        assertEquals(
                AstException.AST__WCSPA,
                assertThrows(AstException.class, () -> tran(cyp, true, 0.1, 0.2))
                        .getStatus());
        assertEquals(
                AstException.AST__WCSPA,
                assertThrows(AstException.class, () -> tran(zpn, false, 0.1, 0.2))
                        .getStatus());
        assertArrayEquals(new double[] {BAD, BAD}, tran(cyp, true, BAD, BAD));
        assertArrayEquals(new double[] {BAD, BAD}, tran(zpn, false, BAD, BAD));
    }

    /**
     * Unusable projection parameters are reported by every transformation, not only the first, as
     * the Rust port does; C reports them only on the first (docs/issues/c-library-quirks.md, "A
     * WcsMap reports unusable parameters only on its first transformation").
     */
    @Test
    void unusableParametersAreAnErrorOnEveryTransform() {
        double[][] pvs = {{2, 0.0}, {1, -1.0, 2, 1.0}};
        for (double[] pv : pvs) {
            for (boolean[] order : new boolean[][] {{true, true}, {true, false}, {false, true}, {false, false}}) {
                WcsMap map = withLatPv(WcsMap.AST__CYP, pv);
                for (boolean forward : order) {
                    assertEquals(
                            AstException.AST__WCSPA,
                            assertThrows(AstException.class, () -> tran(map, forward, 0.1, 0.2))
                                    .getStatus());
                }
            }
        }
        WcsMap azp = withLatPv(WcsMap.AST__AZP, 1, -1.0);
        for (boolean forward : new boolean[] {true, false, true}) {
            assertEquals(
                    AstException.AST__WCSPA,
                    assertThrows(AstException.class, () -> tran(azp, forward, 0.1, 0.2))
                            .getStatus());
        }
    }

    /** jniast's accessors read the attributes; getPVi_m names no attribute, so it fails as C would. */
    @Test
    void jniastAccessors() {
        WcsMap w = wcs(3, WcsMap.AST__COE, 3, 1);
        w.set("PV1_1=30");
        assertEquals((float) (Math.PI / 6), w.getNatLat());
        assertEquals(0.0f, w.getNatLon());
        assertEquals(3, w.getWcsAxis(1));
        assertEquals(1, w.getWcsAxis(2));
        assertEquals(
                AstException.AST__AXIIN,
                assertThrows(AstException.class, () -> w.getWcsAxis(3)).getStatus());
        assertEquals(WcsMap.AST__COE, w.getWcsType());
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, w::getPVi_m).getStatus());
        assertEquals(
                AstException.AST__BADAT,
                assertThrows(AstException.class, () -> w.setPVi_m(1.0f)).getStatus());
        assertSame(WcsMap.class, w.getClass());
    }

    // ------------------------------------------------------------------
    // Equality (wcsmap.c Equal compares the projection parameters)
    // ------------------------------------------------------------------

    @Test
    void wcsmapEqualDifferentParams() {
        WcsMap w1 = wcs(2, WcsMap.AST__ZPN, 1, 2);
        WcsMap w2 = wcs(2, WcsMap.AST__ZPN, 1, 2);
        assertTrue(w1.astEqual(w2));
        w1.setD("PV2_1", 0.5);
        w2.setD("PV2_1", 0.7);
        assertFalse(w1.astEqual(w2));
    }

    @Test
    void wcsmapEqualSameParams() {
        WcsMap w1 = wcs(2, WcsMap.AST__ZPN, 1, 2);
        WcsMap w2 = wcs(2, WcsMap.AST__ZPN, 1, 2);
        w1.setD("PV2_1", 0.5);
        w2.setD("PV2_1", 0.5);
        assertTrue(w1.astEqual(w2));
    }
}
