package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of the tests of Rust {@code tests/test_serialisation.rs} that round-trip Objects made
 * in the test: each Object is written to native text through a default Channel, the text is checked
 * for the items it must hold, and the Object read back is checked for its attributes and its
 * transformation. {@link SerialisationCorpusTest} translates the tests over C's native fixtures
 * ({@code native_byte_exact_round_trip_*_fixtures} and {@code round_trip_exclusions_still_fail}).
 *
 * <p>Expected values are hand-derived: transformations of exact binary values, defaults the C
 * library documents, and the values set before writing. The Rust test's bounds on transformed and
 * read-back values are kept.
 *
 * <p>Deferred: the Region round trips ({@code round_trip_circle}, {@code round_trip_box_region},
 * {@code round_trip_polygon}, {@code round_trip_interval}, {@code round_trip_null_region}, {@code
 * round_trip_ellipse}, {@code round_trip_pointlist}, {@code round_trip_cmpregion}, {@code
 * round_trip_prism}, {@code round_trip_negated_circle}) need Region classes, which are out of scope;
 * the {@code any_object_*} tests, {@code round_trip_skyframe_into_frame} and {@code
 * a_keymap_is_neither_a_mapping_nor_a_frame_nor_a_region} test Rust's {@code AnyObject}, in which a
 * Frame is not a Mapping where in AST every Frame is one; {@code any_object_debug} tests Rust's
 * {@code Debug} formatting.
 */
class SerialisationTest {

    /** Writes {@code obj} through a default Channel. */
    private static String dump(AstObject obj) {
        return ChannelText.dump(obj);
    }

    /** Reads the first Object in {@code text}. */
    private static AstObject read(String text) {
        return ChannelText.read(text);
    }

    /** One point transformed by {@code map}. */
    private static double[] tran(Mapping map, boolean forward, double... in) {
        int nout = forward ? map.getNout() : map.getNin();
        return map.tranN(1, in.length, in, forward, nout);
    }

    private static void assertClose(double expected, double actual, double tol, String label) {
        assertTrue(
                Math.abs(actual - expected) < tol,
                label + ": expected " + expected + ", got " + actual + " (diff " + Math.abs(actual - expected) + ")");
    }

    /** Writes {@code obj}, reads it back, writes it again, and checks the two texts are the same. */
    private static void assertDoubleRoundTrip(AstObject obj, String name) {
        String text1 = dump(obj);
        String text2 = dump(read(text1));
        assertEquals(text1, text2, "double round-trip text mismatch for " + name);
    }

    private static FrameSet twoFrameSet(Frame base, Mapping map, Frame current) {
        FrameSet fs = new FrameSet(base);
        fs.addFrame(FrameSet.AST__BASE, map, current);
        return fs;
    }

    private static Frame frame(int naxes, String domain) {
        Frame f = new Frame(naxes);
        f.setDomain(domain);
        return f;
    }

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    // ------------------------------------------------------------------
    // UnitMap
    // ------------------------------------------------------------------

    @Test
    void roundTripUnitMap() {
        String text = dump(new UnitMap(3));
        assertTrue(text.contains("Begin UnitMap"), "missing Begin UnitMap");
        assertTrue(text.contains("End UnitMap"), "missing End UnitMap");
        assertTrue(text.contains("Nin"), "missing Nin attribute");

        Mapping mapping = (Mapping) read(text);
        assertEquals(3, mapping.getNin());
        assertEquals(3, mapping.getNout());
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, tran(mapping, true, 1.0, 2.0, 3.0));
    }

    @Test
    void roundTripUnitMapInverse() {
        Mapping mapping = (Mapping) read(dump(new UnitMap(2)));
        assertArrayEquals(new double[] {7.0, 8.0}, tran(mapping, false, 7.0, 8.0));
    }

    // ------------------------------------------------------------------
    // ZoomMap
    // ------------------------------------------------------------------

    @Test
    void roundTripZoomMap() {
        String text = dump(new ZoomMap(2, 3.5));
        assertTrue(text.contains("Begin ZoomMap"), "missing Begin ZoomMap");
        assertTrue(text.contains("End ZoomMap"), "missing End ZoomMap");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 1.0, 2.0);
        assertClose(3.5, out[0], 1e-10, "zoom x");
        assertClose(7.0, out[1], 1e-10, "zoom y");
    }

    @Test
    void roundTripZoomMapInverse() {
        Mapping mapping = (Mapping) read(dump(new ZoomMap(2, 4.0)));
        double[] out = tran(mapping, false, 8.0, 12.0);
        assertClose(2.0, out[0], 1e-10, "zoom inv x");
        assertClose(3.0, out[1], 1e-10, "zoom inv y");
    }

    @Test
    void roundTripZoomMapFractionalFactor() {
        Mapping mapping = (Mapping) read(dump(new ZoomMap(1, 0.1)));
        assertClose(5.0, tran(mapping, true, 50.0)[0], 1e-10, "zoom 0.1");
    }

    // ------------------------------------------------------------------
    // ShiftMap
    // ------------------------------------------------------------------

    @Test
    void roundTripShiftMap() {
        String text = dump(new ShiftMap(new double[] {1.5, -2.7}));
        assertTrue(text.contains("Begin ShiftMap"), "missing Begin ShiftMap");
        assertTrue(text.contains("End ShiftMap"), "missing End ShiftMap");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 10.0, 20.0);
        assertClose(11.5, out[0], 1e-10, "shift x");
        assertClose(17.3, out[1], 1e-10, "shift y");
    }

    @Test
    void roundTripShiftMapInverse() {
        Mapping mapping = (Mapping) read(dump(new ShiftMap(new double[] {1.5, -2.7})));
        double[] out = tran(mapping, false, 11.5, 17.3);
        assertClose(10.0, out[0], 1e-10, "shift inv x");
        assertClose(20.0, out[1], 1e-10, "shift inv y");
    }

    // ------------------------------------------------------------------
    // MatrixMap
    // ------------------------------------------------------------------

    @Test
    void roundTripMatrixMapRotation() {
        String text = dump(new MatrixMap(2, 2, new double[][] {{0.0, -1.0}, {1.0, 0.0}}));
        assertTrue(text.contains("Begin MatrixMap"), "missing Begin MatrixMap");
        assertTrue(text.contains("End MatrixMap"), "missing End MatrixMap");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 1.0, 0.0);
        assertClose(0.0, out[0], 1e-10, "rot x");
        assertClose(1.0, out[1], 1e-10, "rot y");
    }

    @Test
    void roundTripMatrixMapScale() {
        Mapping mapping = (Mapping) read(dump(new MatrixMap(2, 2, new double[][] {{2.0, 0.0}, {0.0, 3.0}})));
        double[] out = tran(mapping, true, 5.0, 7.0);
        assertClose(10.0, out[0], 1e-10, "scale x");
        assertClose(21.0, out[1], 1e-10, "scale y");
    }

    @Test
    void roundTripMatrixMapInverse() {
        Mapping mapping = (Mapping) read(dump(new MatrixMap(2, 2, new double[][] {{2.0, 0.0}, {0.0, 4.0}})));
        double[] out = tran(mapping, false, 10.0, 20.0);
        assertClose(5.0, out[0], 1e-10, "inv scale x");
        assertClose(5.0, out[1], 1e-10, "inv scale y");
    }

    // ------------------------------------------------------------------
    // PermMap
    // ------------------------------------------------------------------

    @Test
    void roundTripPermMapSwap() {
        String text = dump(new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null));
        assertTrue(text.contains("Begin PermMap"), "missing Begin PermMap");
        assertTrue(text.contains("End PermMap"), "missing End PermMap");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        assertArrayEquals(new double[] {7.0, 3.0}, tran(mapping, true, 3.0, 7.0));
    }

    @Test
    void roundTripPermMapWithConstant() {
        Mapping mapping =
                (Mapping) read(dump(new PermMap(1, new int[] {1}, 2, new int[] {1, -1}, new double[] {42.0})));
        assertEquals(1, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 5.0);
        assertEquals(5.0, out[0]);
        assertEquals(42.0, out[1]);
    }

    @Test
    void roundTripPermMapInverse() {
        Mapping mapping = (Mapping) read(dump(new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null)));
        assertArrayEquals(new double[] {20.0, 10.0}, tran(mapping, false, 10.0, 20.0));
    }

    // ------------------------------------------------------------------
    // CmpMap
    // ------------------------------------------------------------------

    @Test
    void roundTripCmpMapSeries() {
        CmpMap original = new CmpMap(new ZoomMap(2, 2.0), new ShiftMap(new double[] {10.0, 20.0}), true);
        String text = dump(original);
        assertTrue(text.contains("Begin CmpMap"), "missing Begin CmpMap");
        assertTrue(text.contains("End CmpMap"), "missing End CmpMap");
        assertTrue(text.contains("Begin ZoomMap"), "missing nested ZoomMap");
        assertTrue(text.contains("Begin ShiftMap"), "missing nested ShiftMap");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 1.0, 2.0);
        assertClose(12.0, out[0], 1e-10, "cmp series x");
        assertClose(24.0, out[1], 1e-10, "cmp series y");
    }

    @Test
    void roundTripCmpMapParallel() {
        CmpMap original = new CmpMap(new ZoomMap(1, 3.0), new ShiftMap(new double[] {100.0}), false);
        Mapping mapping = (Mapping) read(dump(original));
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 2.0, 5.0);
        assertClose(6.0, out[0], 1e-10, "cmp parallel x");
        assertClose(105.0, out[1], 1e-10, "cmp parallel y");
    }

    @Test
    void roundTripCmpMapSeriesInverse() {
        CmpMap original = new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true);
        Mapping mapping = (Mapping) read(dump(original));
        assertClose(5.0, tran(mapping, false, 20.0)[0], 1e-10, "cmp inv");
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------

    @Test
    void roundTripBasicFrame() {
        Frame original = new Frame(2);
        original.setTitle("Test Frame");
        original.setDomain("PIXEL");
        original.setLabel(1, "X");
        original.setLabel(2, "Y");
        original.setUnit(1, "m");
        String text = dump(original);
        assertTrue(text.contains("Begin Frame"), "missing Begin Frame");
        assertTrue(text.contains("End Frame"), "missing End Frame");
        assertTrue(text.contains("Naxes"), "missing Naxes");

        Frame bf = assertInstanceOf(Frame.class, read(text));
        assertEquals("Frame", bf.astClass());
        assertEquals(2, bf.getNaxes());
        assertEquals("Test Frame", bf.getTitle());
        assertEquals("PIXEL", bf.getDomain());
        assertEquals("X", bf.getLabel(1));
        assertEquals("Y", bf.getLabel(2));
        assertEquals("m", bf.getUnit(1));
    }

    @Test
    void roundTripBasicFrameDefaultAttributes() {
        Frame bf = (Frame) read(dump(new Frame(3)));
        assertEquals("Frame", bf.astClass());
        assertEquals(3, bf.getNaxes());
        assertFalse(bf.testTitle());
        assertEquals("3-d coordinate system", bf.getTitle());
        assertEquals("", bf.getDomain());
    }

    @Test
    void roundTripBasicFrameGeometry() {
        Frame frame = (Frame) read(dump(new Frame(2)));
        double d = frame.distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0});
        assertClose(5.0, d, 1e-12, "distance");
    }

    @Test
    void roundTripBasicFrameWithEpoch() {
        Frame original = new Frame(1);
        original.setEpoch(2000.0);
        Frame bf = (Frame) read(dump(original));
        assertEquals("Frame", bf.astClass());
        assertTrue(bf.testEpoch());
        assertEquals(2000.0, bf.getEpoch());
    }

    // ------------------------------------------------------------------
    // CmpFrame
    // ------------------------------------------------------------------

    @Test
    void roundTripCmpFrame() {
        Frame f1 = new Frame(1);
        f1.setLabel(1, "RA");
        Frame f2 = new Frame(1);
        f2.setLabel(1, "Dec");
        String text = dump(new CmpFrame(f1, f2));
        assertTrue(text.contains("Begin CmpFrame"), "missing Begin CmpFrame");
        assertTrue(text.contains("End CmpFrame"), "missing End CmpFrame");

        Frame frame = (Frame) read(text);
        assertEquals("CmpFrame", frame.astClass());
        assertEquals(2, frame.getNaxes());
    }

    @Test
    void roundTripCmpFrame3plus2() {
        Frame frame = (Frame) read(dump(new CmpFrame(new Frame(3), new Frame(2))));
        assertEquals(5, frame.getNaxes());
    }

    /** C's CmpFrame Distance is the Cartesian sum of the component distances (cmpframe.c). */
    @Test
    void roundTripCmpFrameDistance() {
        Frame frame = (Frame) read(dump(new CmpFrame(new Frame(1), new Frame(1))));
        double d = frame.distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0});
        assertClose(5.0, d, 1e-12, "cmpframe distance");
    }

    // ------------------------------------------------------------------
    // FrameSet
    // ------------------------------------------------------------------

    @Test
    void roundTripFramesetWithZoom() {
        FrameSet fs = twoFrameSet(frame(2, "PIXEL"), new ZoomMap(2, 5.0), frame(2, "SKY"));
        String text = dump(fs);
        assertTrue(text.contains("Begin FrameSet"), "missing Begin FrameSet");
        assertTrue(text.contains("End FrameSet"), "missing End FrameSet");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 2.0, 3.0);
        assertClose(10.0, out[0], 1e-10, "fs zoom x");
        assertClose(15.0, out[1], 1e-10, "fs zoom y");
    }

    @Test
    void roundTripFramesetInverse() {
        FrameSet fs = twoFrameSet(new Frame(2), new ZoomMap(2, 3.0), new Frame(2));
        Mapping mapping = (Mapping) read(dump(fs));
        double[] out = tran(mapping, false, 9.0, 12.0);
        assertClose(3.0, out[0], 1e-10, "fs inv x");
        assertClose(4.0, out[1], 1e-10, "fs inv y");
    }

    /** A FrameSet read back is a Frame as well as a Mapping. */
    @Test
    void roundTripFramesetAsFrame() {
        FrameSet fs = twoFrameSet(frame(2, "GRID"), new UnitMap(2), frame(2, "PIXEL"));
        AstObject restored = read(dump(fs));
        assertInstanceOf(Mapping.class, restored);
        Frame frame = assertInstanceOf(Frame.class, restored);
        assertEquals(2, frame.getNaxes());
    }

    @Test
    void roundTripFramesetChain() {
        FrameSet fs = new FrameSet(new Frame(2));
        fs.addFrame(FrameSet.AST__CURRENT, new ZoomMap(2, 2.0), new Frame(2));
        fs.addFrame(FrameSet.AST__CURRENT, new ShiftMap(new double[] {10.0, 20.0}), new Frame(2));
        Mapping mapping = (Mapping) read(dump(fs));
        double[] out = tran(mapping, true, 1.0, 2.0);
        assertClose(12.0, out[0], 1e-10, "chain x");
        assertClose(24.0, out[1], 1e-10, "chain y");
    }

    // ------------------------------------------------------------------
    // Double round trips
    // ------------------------------------------------------------------

    @Test
    void doubleRoundTripZoomMap() {
        assertDoubleRoundTrip(new ZoomMap(2, 7.77), "ZoomMap");
    }

    @Test
    void doubleRoundTripShiftMap() {
        assertDoubleRoundTrip(new ShiftMap(new double[] {1.5, -2.7, 0.0}), "ShiftMap");
    }

    @Test
    void doubleRoundTripBasicFrame() {
        Frame original = new Frame(2);
        original.setTitle("Round-trip");
        original.setDomain("TEST");
        original.setLabel(1, "A");
        original.setLabel(2, "B");
        assertDoubleRoundTrip(original, "Frame");
    }

    @Test
    void doubleRoundTripFrameset() {
        assertDoubleRoundTrip(twoFrameSet(new Frame(2), new ZoomMap(2, 2.5), new Frame(2)), "FrameSet");
    }

    @Test
    void doubleRoundTripCmpMap() {
        assertDoubleRoundTrip(new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true), "CmpMap");
    }

    @Test
    void doubleRoundTripPermMap() {
        assertDoubleRoundTrip(new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null), "PermMap");
    }

    @Test
    void doubleRoundTripMatrixMap() {
        assertDoubleRoundTrip(new MatrixMap(2, 2, new double[][] {{0.0, -1.0}, {1.0, 0.0}}), "MatrixMap");
    }

    @Test
    void doubleRoundTripCmpFrame() {
        assertDoubleRoundTrip(new CmpFrame(new Frame(2), new Frame(1)), "CmpFrame");
    }

    @Test
    void doubleRoundTripUnitMap() {
        assertDoubleRoundTrip(new UnitMap(4), "UnitMap");
    }

    // ------------------------------------------------------------------
    // Several points
    // ------------------------------------------------------------------

    @Test
    void roundTripZoomMapMultiPoint() {
        Mapping mapping = (Mapping) read(dump(new ZoomMap(2, 3.0)));
        double[][] out = mapping.tranP(3, 2, new double[][] {{1.0, 3.0, 5.0}, {2.0, 4.0, 6.0}}, true, 2);
        assertArrayEquals(new double[] {3.0, 9.0, 15.0}, out[0]);
        assertArrayEquals(new double[] {6.0, 12.0, 18.0}, out[1]);
    }

    @Test
    void roundTripShiftMapMultiPoint() {
        Mapping mapping = (Mapping) read(dump(new ShiftMap(new double[] {100.0, -50.0})));
        double[][] out = mapping.tranP(2, 2, new double[][] {{0.0, 1.0}, {0.0, 1.0}}, true, 2);
        assertArrayEquals(new double[] {100.0, 101.0}, out[0]);
        assertArrayEquals(new double[] {-50.0, -49.0}, out[1]);
    }

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------

    @Test
    void fromNativeStringEmptyInput() {
        assertThrows(AstException.class, () -> read(""), "empty input should fail");
    }

    @Test
    void fromNativeStringGarbageInput() {
        assertThrows(AstException.class, () -> read("this is not AST native format"), "garbage input should fail");
    }

    @Test
    void fromNativeStringTruncated() {
        String text = dump(new ZoomMap(2, 3.5));
        String truncated = text.substring(0, text.length() / 2);
        assertThrows(AstException.class, () -> read(truncated), "truncated input should fail");
    }

    // ------------------------------------------------------------------
    // Structure of the text
    // ------------------------------------------------------------------

    @Test
    void nativeTextHasIsaMarkers() {
        assertTrue(dump(new ZoomMap(2, 3.5)).contains("IsA Mapping"), "missing IsA Mapping marker in ZoomMap");
    }

    @Test
    void nativeTextFramesetHasNestedObjects() {
        String text = dump(twoFrameSet(new Frame(2), new ZoomMap(2, 2.0), new Frame(2)));
        assertTrue(text.contains("Begin Frame"), "missing nested Frame in FrameSet");
        assertTrue(text.contains("Begin ZoomMap"), "missing nested ZoomMap in FrameSet");
    }

    @Test
    void nativeTextCmpmapHasSeriesFlag() {
        String text = dump(new CmpMap(new ZoomMap(1, 2.0), new ShiftMap(new double[] {10.0}), true));
        assertTrue(text.contains("MapA"), "missing MapA key in CmpMap");
        assertTrue(text.contains("MapB"), "missing MapB key in CmpMap");
    }

    // ------------------------------------------------------------------
    // SkyFrame
    // ------------------------------------------------------------------

    /** The default Domain, SKY, is written as an unset value; a SkyFrame is a Mapping, as in C. */
    @Test
    void roundTripSkyframeDefaultFk5() {
        String text = dump(sky("FK5"));
        assertTrue(text.contains("Begin SkyFrame"), "missing Begin SkyFrame");
        assertTrue(text.contains("End SkyFrame"), "missing End SkyFrame");
        assertTrue(text.contains("Naxes = 2"), "missing Naxes");
        assertTrue(text.contains("System = \"FK5\""), "missing System");
        assertTrue(text.contains("IsA Frame"), "missing IsA Frame");
        assertTrue(text.contains("#   Domain = \"SKY\""), "default domain SKY should be written as unset");
        assertTrue(text.contains("Begin SkyAxis"), "missing SkyAxis");

        SkyFrame sf = assertInstanceOf(SkyFrame.class, read(text));
        assertEquals(2, sf.getNaxes());
        assertInstanceOf(Mapping.class, sf);
        assertEquals(SkyFrame.AST__FK5, sf.systemValue());
        assertEquals("SKY", sf.getDomain());
        assertFalse(sf.getNegLon());
        assertEquals("Ignored", sf.getSkyRefIs());
        assertFalse(sf.getAlignOffset());
    }

    @Test
    void roundTripSkyframeGalacticWithAttributes() {
        SkyFrame original = sky("GALACTIC");
        original.setTitle("Galactic Test");
        original.setEpochMjd(53000.0);
        original.setNegLon(true);
        original.setSkyTol(1.0e-7);
        original.setProjection("TAN");
        original.setAlignOffset(true);

        String text = dump(original);
        assertTrue(text.contains("GALACTIC"), "missing GALACTIC system");
        assertTrue(text.contains("Galactic Test"), "missing title");
        assertTrue(text.contains("NegLon"), "missing NegLon");
        assertTrue(text.contains("SkyTol"), "missing SkyTol");
        assertTrue(text.contains("Proj"), "missing Proj");
        assertTrue(text.contains("AlOff"), "missing AlOff");

        SkyFrame sf = assertInstanceOf(SkyFrame.class, read(text));
        assertEquals(SkyFrame.AST__GALACTIC, sf.systemValue());
        assertEquals("Galactic Test", sf.getTitle());
        assertClose(53000.0, sf.getEpochMjd(), 1e-10, "epoch");
        assertTrue(sf.getNegLon());
        assertClose(1.0e-7, sf.getSkyTol(), 1e-15, "sky_tol");
        assertEquals("TAN", sf.getProjection());
        assertTrue(sf.getAlignOffset());
    }

    @Test
    void roundTripSkyframeWithEquinox() {
        SkyFrame original = sky("FK5");
        double j2010Mjd = 55197.0;
        original.setEquinoxMjd(j2010Mjd);

        String text = dump(original);
        assertTrue(text.contains("Eqnox"), "missing Eqnox");

        SkyFrame sf = (SkyFrame) read(text);
        assertClose(j2010Mjd, sf.getEquinoxMjd(), 1e-6, "equinox MJD");
    }

    @Test
    void roundTripSkyframeWithSkyRef() {
        SkyFrame original = sky("FK5");
        original.setSkyRefIs("Origin");
        original.setSkyRef(original.getLonAxis(), 1.5);
        original.setSkyRef(original.getLatAxis(), 0.75);
        original.setSkyRefP(original.getLonAxis(), 0.3);
        original.setSkyRefP(original.getLatAxis(), 1.2);

        String text = dump(original);
        assertTrue(text.contains("SRefIs"), "missing SRefIs");
        assertTrue(text.contains("SRef1"), "missing SRef1");
        assertTrue(text.contains("SRef2"), "missing SRef2");
        assertTrue(text.contains("SRefP1"), "missing SRefP1");
        assertTrue(text.contains("SRefP2"), "missing SRefP2");

        SkyFrame sf = (SkyFrame) read(text);
        assertEquals("Origin", sf.getSkyRefIs());
        assertClose(1.5, sf.getSkyRef(sf.getLonAxis()), 1e-10, "sky_ref lon");
        assertClose(0.75, sf.getSkyRef(sf.getLatAxis()), 1e-10, "sky_ref lat");
        assertClose(0.3, sf.getSkyRefP(sf.getLonAxis()), 1e-10, "sky_ref_p lon");
        assertClose(1.2, sf.getSkyRefP(sf.getLatAxis()), 1e-10, "sky_ref_p lat");
    }

    @Test
    void roundTripSkyframeWithObserver() {
        SkyFrame original = sky("FK5");
        original.setObsLatValue(0.3);
        original.setObsLonValue(-2.1);
        original.setObsAlt(4200.0);
        original.setDtai(37.0);
        original.setDut1(0.27);

        SkyFrame sf = (SkyFrame) read(dump(original));
        assertTrue(sf.testObsLat());
        assertClose(0.3, sf.getObsLatValue(), 1e-10, "obs_lat");
        assertTrue(sf.testObsLon());
        assertClose(-2.1, sf.getObsLonValue(), 1e-10, "obs_lon");
        assertTrue(sf.testObsAlt());
        assertClose(4200.0, sf.getObsAlt(), 1e-10, "obs_alt");
        assertTrue(sf.testDtai());
        assertClose(37.0, sf.getDtai(), 1e-10, "dtai");
        assertTrue(sf.testDut1());
        assertClose(0.27, sf.getDut1(), 1e-10, "dut1");
    }

    @Test
    void roundTripSkyframeWithDomain() {
        SkyFrame original = sky("FK5");
        original.setDomain("CUSTOM_SKY");
        String text = dump(original);
        assertTrue(text.contains("CUSTOM_SKY"), "missing custom domain");
        assertEquals("CUSTOM_SKY", ((SkyFrame) read(text)).getDomain());
    }

    @Test
    void roundTripSkyframeGeometry() {
        Frame frame = (Frame) read(dump(sky("FK5")));
        double d = frame.distance(new double[] {0.0, Math.PI / 2.0}, new double[] {0.0, -Math.PI / 2.0});
        assertClose(Math.PI, d, 1e-10, "distance");
    }

    @Test
    void doubleRoundTripSkyframe() {
        SkyFrame original = sky("FK5");
        original.setTitle("Double RT");
        original.setEpochMjd(53000.0);
        original.setEquinoxMjd(51544.5);
        original.setNegLon(true);
        original.setSkyRefIs("Pole");
        original.setSkyRef(original.getLonAxis(), 0.5);
        original.setSkyRef(original.getLatAxis(), 0.25);
        original.setSkyRefP(original.getLonAxis(), 0.1);
        original.setSkyRefP(original.getLatAxis(), 1.0);
        original.setSkyTol(1.0e-8);
        original.setProjection("SIN");
        original.setAlignOffset(true);
        assertDoubleRoundTrip(original, "SkyFrame");
    }

    @Test
    void roundTripFramesetWithSkyframe() {
        FrameSet fs = twoFrameSet(frame(2, "PIXEL"), new ZoomMap(2, 0.01), sky("FK5"));
        String text = dump(fs);
        assertTrue(text.contains("Begin SkyFrame"), "missing nested SkyFrame");

        Mapping mapping = (Mapping) read(text);
        assertEquals(2, mapping.getNin());
        assertEquals(2, mapping.getNout());
        double[] out = tran(mapping, true, 100.0, 200.0);
        assertClose(1.0, out[0], 1e-10, "fs skyframe x");
        assertClose(2.0, out[1], 1e-10, "fs skyframe y");
    }

    @Test
    void roundTripSkyframeFk4BesselianEquinox() {
        SkyFrame original = sky("FK4");
        double b1950Mjd = 33281.92346;
        original.setEquinoxMjd(b1950Mjd);

        String text = dump(original);
        assertTrue(text.contains("Eqnox"), "missing Eqnox for FK4");

        SkyFrame sf = (SkyFrame) read(text);
        assertEquals(SkyFrame.AST__FK4, sf.systemValue());
        assertClose(b1950Mjd, sf.getEquinoxMjd(), 1e-3, "FK4 equinox MJD");
    }

    // ------------------------------------------------------------------
    // KeyMap
    // ------------------------------------------------------------------

    @Test
    void keymapRoundTripsThroughNativeText() {
        KeyMap inner = new KeyMap();
        inner.mapPut0D("Scale", 2.5, "a scale factor");

        KeyMap km = new KeyMap();
        km.mapPut0C("Name", "example", null);
        km.mapPut1I("Counts", new int[] {1, 2, 3}, "three counts");
        km.mapPut0A("Inner", inner, null);
        km.mapPut0A("Zoom", new ZoomMap(2, 3.0), null);

        String text = dump(km);
        assertTrue(text.contains("Begin KeyMap"));
        assertTrue(text.contains("Map of key/value pairs"));
        assertTrue(text.contains("Begin ZoomMap"));

        KeyMap back = assertInstanceOf(KeyMap.class, read(text));
        assertEquals(4, back.mapSize());
        assertEquals("example", back.mapGet0C("Name"));
        assertArrayEquals(new int[] {1, 2, 3}, back.mapGet1I("Counts"));
        assertEquals("three counts", back.mapComment("Counts"));

        KeyMap innerBack = assertInstanceOf(KeyMap.class, back.mapGet0A("Inner"));
        assertEquals(2.5, innerBack.mapGet0D("Scale"));
        assertEquals("a scale factor", innerBack.mapComment("Scale"));

        ZoomMap zm = assertInstanceOf(ZoomMap.class, back.mapGet0A("Zoom"));
        assertEquals(3.0, zm.rate(new double[] {0.0, 0.0}, 1, 1));

        assertEquals(text, dump(back));
    }
}
