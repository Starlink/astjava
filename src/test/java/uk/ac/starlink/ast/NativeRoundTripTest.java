package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_native_roundtrip_all.rs}: an Object of each class, with
 * attributes away from their defaults, is written, read back and written again, and the two texts
 * must be the same, so that a dump cannot silently drop a setting the C fixtures happen not to use.
 * {@code SerialisationCorpusTest} checks the dumps against C's.
 *
 * <p>Rust's file leaves the classes {@code test_serialisation.rs} covers (UnitMap, ShiftMap,
 * ZoomMap, MatrixMap, PermMap, CmpMap) to that file; here every class the library loads has a
 * case, each with non-default attributes. Later tasks add theirs: PolyMap ({@code round_trip_polymap_with_iter_inverse}) in Task 12, SlaMap and TimeMap ({@code
 * round_trip_slamap}, {@code round_trip_timemap}) in Task 13, and a case for each Frame and
 * FrameSet class as it is ported.
 */
class NativeRoundTripTest {

    /**
     * Writes {@code obj}, reads the text back and writes the result, and checks the two texts are
     * the same; gives what was read.
     */
    private static AstObject assertDoubleRoundTrip(AstObject obj, String name) {
        String text1 = ChannelText.dump(obj);
        AstObject restored = ChannelText.read(text1);
        String text2 = ChannelText.dump(restored);
        assertEquals(text1, text2, name + ": double round-trip text mismatch");
        assertEquals(obj.astClass(), restored.astClass(), name);
        return restored;
    }

    @Test
    void roundTripWinmap() {
        WinMap m = new WinMap(
                2, new double[] {0.0, 0.0}, new double[] {1.0, 1.0}, new double[] {0.5, -0.25}, new double[] {1.5, 0.75
                });
        Mapping r = (Mapping) assertDoubleRoundTrip(m, "WinMap");
        assertArrayEquals(
                m.tranN(1, 2, new double[] {0.0, 1.0}, true, 2), r.tranN(1, 2, new double[] {0.0, 1.0}, true, 2));
    }

    @Test
    void roundTripInvertedWinmapWithIdent() {
        WinMap m = new WinMap(1, new double[] {-1.0}, new double[] {3.0}, new double[] {2.0}, new double[] {10.0});
        m.setInvert(true);
        m.setIdent("window");
        m.setID("w1");
        Mapping r = (Mapping) assertDoubleRoundTrip(m, "WinMap inverted");
        assertTrue(r.getInvert());
        assertEquals("window", r.getIdent());
        assertEquals("w1", r.getID());
    }

    @Test
    void roundTripUnitmap() {
        UnitMap m = new UnitMap(3);
        m.setInvert(true);
        m.setReport(true);
        Mapping r = (Mapping) assertDoubleRoundTrip(m, "UnitMap");
        assertEquals(3, r.getNin());
        assertTrue(r.getInvert());
        assertTrue(r.getReport());
    }

    @Test
    void roundTripShiftmap() {
        ShiftMap m = new ShiftMap(new double[] {1.5, -2.25, 1.0e-300});
        m.setInvert(true);
        Mapping r = (Mapping) assertDoubleRoundTrip(m, "ShiftMap");
        assertTrue(r.getInvert());
        assertArrayEquals(
                m.tranN(1, 3, new double[] {1, 2, 3}, true, 3), r.tranN(1, 3, new double[] {1, 2, 3}, true, 3));
    }

    @Test
    void roundTripZoommap() {
        ZoomMap m = new ZoomMap(2, 0.1);
        m.setInvert(true);
        m.setIdent("zoom");
        ZoomMap r = (ZoomMap) assertDoubleRoundTrip(m, "ZoomMap");
        assertEquals(0.1, r.getZoom());
        assertTrue(r.getInvert());
    }

    @Test
    void roundTripMatrixmapFull() {
        MatrixMap m = new MatrixMap(2, 3, new double[][] {{1.0, 2.0}, {3.0, 4.0}, {5.0, 6.5}});
        assertDoubleRoundTrip(m, "MatrixMap full, no inverse");
        MatrixMap sq = new MatrixMap(2, 2, new double[][] {{0.0, -1.0}, {1.0, 0.0}});
        sq.setInvert(true);
        Mapping r = (Mapping) assertDoubleRoundTrip(sq, "MatrixMap full, inverted");
        assertArrayEquals(sq.tranN(1, 2, new double[] {1, 2}, true, 2), r.tranN(1, 2, new double[] {1, 2}, true, 2));
    }

    @Test
    void roundTripMatrixmapDiagonalAndUnit() {
        assertDoubleRoundTrip(new MatrixMap(3, 3, new double[] {2.0, -0.5, 4.0}), "MatrixMap diagonal");
        assertDoubleRoundTrip(new MatrixMap(2, 3), "MatrixMap unit");
    }

    @Test
    void roundTripPermmap() {
        PermMap m = new PermMap(3, new int[] {2, -1, 1}, 2, new int[] {3, 1}, new double[] {7.5});
        m.setInvert(true);
        Mapping r = (Mapping) assertDoubleRoundTrip(m, "PermMap with constant");
        assertTrue(r.getInvert());
        assertArrayEquals(m.tranN(1, 2, new double[] {1, 2}, true, 3), r.tranN(1, 2, new double[] {1, 2}, true, 3));
        assertDoubleRoundTrip(new PermMap(2, null, 2, null, null), "PermMap unit");
    }

    @Test
    void roundTripCmpmap() {
        ZoomMap z = new ZoomMap(2, 2.0);
        z.setInvert(true);
        ShiftMap s = new ShiftMap(new double[] {0.5, 0.5});
        CmpMap series = new CmpMap(z, s, true);
        series.setInvert(true);
        Mapping r = (Mapping) assertDoubleRoundTrip(series, "CmpMap series");
        assertArrayEquals(
                series.tranN(1, 2, new double[] {1, 2}, true, 2), r.tranN(1, 2, new double[] {1, 2}, true, 2));
        CmpMap parallel =
                new CmpMap(new UnitMap(1), new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null), false);
        assertDoubleRoundTrip(parallel, "CmpMap parallel");
        assertDoubleRoundTrip(new CmpMap(series, parallel, false), "CmpMap nested");
    }

    @Test
    void roundTripKeymap() {
        KeyMap inner = new KeyMap();
        inner.mapPut0I("n", 3, "a count");
        KeyMap km = new KeyMap();
        km.setC("SortBy", "KeyDown");
        km.setB("KeyCase", false);
        km.setB("KeyError", true);
        km.setI("SizeGuess", 40);
        km.mapPut0D("pi", Math.PI, "a double");
        km.mapPut0I("i", -7, null);
        km.mapPut0C("s", "text with \"quotes\"", "a string");
        km.mapPut0A("zoom", new ZoomMap(1, 3.0), null);
        km.mapPut0A("inner", inner, null);
        km.mapPut1D("vd", new double[] {1.0, -2.5, 1.0e-10}, null);
        km.mapPut1I("vi", new int[] {1, 2, 3}, null);
        km.mapPut1C("vc", new String[] {"a", "", "c"}, null);
        km.mapPut1A("va", new AstObject[] {new UnitMap(1), new ShiftMap(new double[] {1.0})}, null);
        km.setB("MapLocked", true);
        KeyMap r = (KeyMap) assertDoubleRoundTrip(km, "KeyMap");
        assertEquals("KeyDown", r.getC("SortBy"));
        assertTrue(r.getB("MapLocked"));
        assertTrue(r.getB("KeyError"));
        assertEquals(40, r.getI("SizeGuess"));
        assertEquals(9, r.mapSize());
    }

    @Test
    void roundTripChannel() {
        Channel c = new Channel(null, null);
        c.setComment(false);
        c.setFull(1);
        c.setI("Indent", 5);
        c.setReportLevel(3);
        c.setSkip(true);
        c.setStrict(true);
        Channel r = (Channel) assertDoubleRoundTrip(c, "Channel");
        assertTrue(r.getSkip());
        assertTrue(r.getStrict());
        assertEquals(3, r.getReportLevel());
        assertEquals(5, r.getI("Indent"));
    }

    @Test
    void roundTripSphmap() {
        assertDoubleRoundTrip(new SphMap(), "SphMap");
        SphMap m = new SphMap();
        m.setUnitRadius(true);
        m.setPolarLong(0.25);
        m.invert();
        SphMap r = (SphMap) assertDoubleRoundTrip(m, "SphMap with attributes");
        assertTrue(r.getUnitRadius());
        assertEquals(0.25, r.getPolarLong());
        assertTrue(r.getInvert());
    }

    @Test
    void roundTripWcsmapTan() {
        assertDoubleRoundTrip(new WcsMap(2, WcsMap.AST__TAN, 1, 2), "WcsMap TAN");
        WcsMap m = new WcsMap(4, WcsMap.AST__TAN, 4, 2);
        m.set("PV4_1=10, PV4_2=80");
        m.setFITSProj(false);
        m.setLonCheck(false);
        WcsMap r = (WcsMap) assertDoubleRoundTrip(m, "WcsMap TAN with attributes");
        assertEquals(4, r.getWcsAxis(1));
        assertEquals(2, r.getWcsAxis(2));
        assertEquals(80.0, r.getD("PV4_2"));
        assertFalse(r.getFITSProj());
        assertFalse(r.getLonCheck());
    }

    @Test
    void roundTripWcsmapZpn() {
        WcsMap m = new WcsMap(2, WcsMap.AST__ZPN, 1, 2);
        m.set("PV2_1=1, PV2_3=0.05");
        WcsMap r = (WcsMap) assertDoubleRoundTrip(m, "WcsMap ZPN with PV");
        assertEquals(0.05, r.getD("PV2_3"));
        assertFalse(r.test("PV2_2"));
    }
}
