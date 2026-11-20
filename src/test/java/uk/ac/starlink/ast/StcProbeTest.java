package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import uk.ac.starlink.ast.testsupport.CValues;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * StcResourceProfile behaviour against C, from {@code probes/stc_probe.c}: an Stc transformed or
 * asked whether it is bounded, then dumped, copied and compared, which leaves Negated set on the
 * encapsulated Region and so reading as set on the Stc (stc.c {@code Transform} and {@code
 * GetBounded}); AstroCoords elements without keys, with one Region, and with Regions in another
 * Frame, as {@code astGetStcCoord} gives them, its index errors, and the constructor's errors for
 * bad AstroCoords; Frame attributes, Negated, Closed, MeshSize and FillFactor set on and cleared
 * from an Stc, and the read-only RegionClass; testregions.c's {@code checkStcNegated}; and dumps
 * edited by hand: {@code Begin Stc}, which C reads as an Stc of the abstract class and Java refuses,
 * and {@code Ncoord} below zero, which both read as no AstroCoords elements; testregions.c's {@code
 * checkStcObsDump}, an StcObsDataLocation's observatory position, set as C's protected {@code
 * astStcSetObs} sets it (holding the PointList given), dumped, copied, compared and removed, and
 * one of another class read from a dump; and Stcs of different classes compared, and aligned with
 * {@code findFrame} and {@code convert}.
 *
 * <p>Casting an Stc into the class of another Region ({@link Region#cast}) gives a copy for a Region
 * of its own class and null for any other. No call of {@code astFindFrame} or {@code astConvert}
 * between two Stcs reaches region.c's {@code Cast} in C: {@code astFindFrame} matches with {@code
 * matchsub} 0, and region.c's {@code ConvertX} aligns the Regions' current Frames. A CmpFrame whose
 * component Region fails to match does reach it ({@link #aCmpFrameOfStcsInOtherDomainsDoesNotConvert}):
 * C then crashes unless a description of the class Region has been read earlier in the process
 * (region.c {@code Cast} compares the class of its own vtab, which only that read initialises), so
 * the probe reads one first, in its last case, and C then finds no conversion, as Java does.
 *
 * <p>An Stc dump without its {@code Region}, or with fewer {@code Coord<n>} items than {@code
 * Ncoord}, is refused here with {@code AST__LDERR}: C's {@code astLoadStc} stores a null for the
 * missing item, and then crashes, in the load ({@code astRegDummyFS} of the null Region) or in
 * {@code astGetStcCoord} ({@code astCopy} of the null KeyMap), so the probe holds no such case.
 *
 * <p>Each case writes the lines the probe writes and compares them with C's exactly, except that
 * the numbers of {@code attribs_sky}, whose Circles' second points come from {@code
 * SkyFrame.offset2}, are compared within {@link #SKY_TOL} for the reason {@code
 * RegionStcOracleTest} gives.
 */
class StcProbeTest {

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("stc_probe");

    private static final double SKY_TOL = 1.0e-12;

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

    private static int flag(boolean b) {
        return b ? 1 : 0;
    }

    /** The dump of {@code obj}, as the probe's {@code astShow} writes it. */
    private static String show(AstObject obj) {
        StringBuilder sb = new StringBuilder();
        for (String l : ChannelText.dump(obj).split("\n", -1)) {
            if (!l.isEmpty()) {
                sb.append(ProbeOutput.trimLines(l)).append('\n');
            }
        }
        return sb.toString();
    }

    /** The error {@code run} raises, as the probe reports it, less the line naming the C source. */
    private static String err(Executable run) {
        AstException e = assertThrows(AstException.class, run);
        StringBuilder sb = new StringBuilder();
        for (String line : ProbeOutput.trimLines(e.getMsgText()).split("\n", -1)) {
            sb.append("!  ").append(line).append('\n');
        }
        return sb + "status " + e.getStatus() + "\n";
    }

    /** The two test points of a 2-D Stc transformed, one line each, as the probe's {@code tran2}. */
    private static String tran2(Region stc, double x0, double y0, double x1, double y1) {
        double[] xin = {x0, x1};
        double[] yin = {y0, y1};
        double[][] out = stc.transform(new double[][] {xin.clone(), yin.clone()}, 2, true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            sb.append("tran ")
                    .append(g(xin[i]))
                    .append(' ')
                    .append(g(yin[i]))
                    .append(" -> ")
                    .append(g(out[0][i]))
                    .append(' ')
                    .append(g(out[1][i]))
                    .append('\n');
        }
        return sb.toString();
    }

    private static Circle circle2(double x, double y, double r) {
        return new Circle(new Frame(2), 1, new double[] {x, y}, new double[] {r}, null);
    }

    private static StcResourceProfile plain() {
        return new StcResourceProfile(circle2(0.0, 0.0, 1.0), (KeyMap[]) null);
    }

    private static StcResourceProfile withCoords(Region reg, KeyMap... coords) {
        return new StcResourceProfile(reg, coords);
    }

    /** An Interval from {@code lo} to {@code hi} in a SpecFrame of frequency in {@code unit}. */
    private static Interval specInterval(String unit, double lo, double hi) {
        SpecFrame sf = new SpecFrame();
        sf.set("System=FREQ");
        sf.set("Unit=" + unit);
        return new Interval(sf, new double[] {lo}, new double[] {hi}, null);
    }

    /** {@code astGetStcCoord( stc, i )} as a dump. */
    private static String showCoord(Stc stc, int i) {
        return "-- coord " + i + "\n" + show(stc.stcCoord(i));
    }

    /** The Negated, Closed, MeshSize and FillFactor values and whether each is set. */
    private static String showAttrs(String what, Region obj) {
        return what + " Negated " + obj.getI("Negated") + " " + flag(obj.test("Negated")) + " Closed "
                + obj.getI("Closed") + " " + flag(obj.test("Closed")) + " MeshSize " + obj.getI("MeshSize") + " "
                + flag(obj.test("MeshSize")) + " FillFactor " + g(obj.getD("FillFactor")) + " "
                + flag(obj.test("FillFactor")) + "\n";
    }

    // ------------------------------------------------------------------
    // Review Focus 2: an Stc used, then dumped, copied and compared
    // ------------------------------------------------------------------

    @Test
    void transformingLeavesNegatedSet() {
        StcResourceProfile stc = plain();
        String before = "test Negated before " + flag(stc.test("Negated")) + "\n";
        String tran = tran2(stc, 0.0, 0.0, 5.0, 0.0);
        String after = "test Negated after " + flag(stc.test("Negated")) + "\n" + show(stc);
        Region copy = (Region) stc.copy();
        String got = before + tran + after + "copy test Negated " + flag(copy.test("Negated")) + "\nequal copy "
                + flag(stc.astEqual(copy)) + "\nequal fresh " + flag(stc.astEqual(plain()))
                + "\nequal fresh reversed " + flag(plain().astEqual(stc)) + "\n";
        assertEquals(probe("used_plain"), got);
    }

    @Test
    void aNegatedStcUsed() {
        Region stc = (Region) plain().copy();
        stc.negate();
        String tran = tran2(stc, 0.0, 0.0, 5.0, 0.0);
        String after = "test Negated after " + flag(stc.test("Negated")) + "\n" + show(stc);
        StcResourceProfile stc2 = plain();
        stc2.negate();
        String got = tran + after + "equal copy " + flag(stc.astEqual(stc.copy())) + "\nequal fresh negated "
                + flag(stc.astEqual(stc2)) + "\nequal plain " + flag(stc.astEqual(plain())) + "\n";
        assertEquals(probe("used_negated"), got);
    }

    @Test
    void askingBoundedLeavesNegatedSet() {
        StcResourceProfile stc = plain();
        String got = "Bounded " + stc.getI("Bounded") + "\ntest Negated after " + flag(stc.test("Negated")) + "\n"
                + show(stc);
        assertEquals(probe("used_bounded"), got);
    }

    // ------------------------------------------------------------------
    // Review Focus 4: AstroCoords elements
    // ------------------------------------------------------------------

    @Test
    void anEmptyAstroCoordsElement() {
        StcResourceProfile stc = withCoords(circle2(0.0, 0.0, 1.0), new KeyMap());
        String got = "ncoord " + stc.getStcNCoord() + "\n" + show(stc) + showCoord(stc, 1);
        assertEquals(probe("coords_empty"), got);
    }

    @Test
    void astroCoordsElementsWithRegions() {
        KeyMap km = new KeyMap();
        km.mapPut0A(Stc.AST__STCVALUE, circle2(0.5, 0.0, 0.25), null);
        KeyMap km2 = new KeyMap();
        km2.mapPut1C(Stc.AST__STCNAME, new String[] {"X", "Y"}, "axis names");
        km2.mapPut0A(Stc.AST__STCRES, circle2(0.0, 0.0, 0.01), "resolution");
        StcResourceProfile stc = withCoords(circle2(0.0, 0.0, 1.0), km, km2);
        String got = "ncoord " + stc.getStcNCoord() + "\n" + show(stc) + showCoord(stc, 1) + showCoord(stc, 2)
                + "-- given KeyMap after\n" + show(km);
        assertEquals(probe("coords_value_only"), got);
    }

    @Test
    void astroCoordsRegionsInAnotherFrame() {
        KeyMap km = new KeyMap();
        km.mapPut1C(Stc.AST__STCNAME, new String[] {"Frequency"}, null);
        km.mapPut0A(Stc.AST__STCVALUE, specInterval("MHz", 1500.0, 1600.0), null);
        km.mapPut0A(Stc.AST__STCSIZE, specInterval("GHz", 0.0, 0.25), null);
        StcResourceProfile stc = withCoords(specInterval("GHz", 1.0, 2.0), km);
        String given = show(stc) + showCoord(stc, 1);
        stc.set("Unit=MHz");
        String got = given + "-- Unit=MHz, Adaptive\n" + show(stc) + showCoord(stc, 1) + "-- region\n"
                + show(stc.getStcRegion());
        assertEquals(probe("coords_other_frame"), got);
    }

    @Test
    void astroCoordsIndexErrors() {
        StcResourceProfile none = plain();
        StcResourceProfile one = withCoords(circle2(0.0, 0.0, 1.0), new KeyMap());
        StcResourceProfile two = withCoords(circle2(0.0, 0.0, 1.0), new KeyMap(), new KeyMap());
        String got = err(() -> none.stcCoord(0))
                + err(() -> none.stcCoord(1))
                + err(() -> one.stcCoord(0))
                + err(() -> one.stcCoord(2))
                + err(() -> two.stcCoord(3))
                + err(() -> two.stcCoord(-1));
        assertEquals(probeWithoutSource("coords_index"), got);
        // jniast's getStcCoord reports the same.
        AstException e = assertThrows(AstException.class, () -> one.getStcCoord(2));
        assertEquals(AstException.AST__STCIND, e.getStatus());
    }

    @Test
    void badAstroCoordsKeyMaps() {
        Circle reg = circle2(0.0, 0.0, 1.0);
        KeyMap colour = new KeyMap();
        colour.mapPut0A("Colour", circle2(0.0, 0.0, 1.0), null);
        KeyMap three = new KeyMap();
        three.mapPut1C(Stc.AST__STCNAME, new String[] {"X", "Y", "Z"}, null);
        KeyMap ints = new KeyMap();
        ints.mapPut1I(Stc.AST__STCNAME, new int[] {1, 2}, null);
        KeyMap twoValues = new KeyMap();
        twoValues.mapPut1A(Stc.AST__STCVALUE, new AstObject[] {circle2(0.0, 0.0, 1.0), circle2(0.0, 0.0, 2.0)}, null);
        KeyMap dbl = new KeyMap();
        dbl.mapPut0D(Stc.AST__STCERROR, 1.0, null);
        KeyMap frame = new KeyMap();
        frame.mapPut0A(Stc.AST__STCSIZE, new Frame(2), null);
        KeyMap box3 = new KeyMap();
        box3.mapPut0A(
                Stc.AST__STCPIXSZ,
                new Box(new Frame(3), 1, new double[] {0.0, 0.0, 0.0}, new double[] {1.0, 1.0, 1.0}, null),
                null);
        String got = "-- unknown key\n" + err(() -> withCoords(reg, colour))
                + "-- three names\n" + err(() -> withCoords(reg, three))
                + "-- names not strings\n" + err(() -> withCoords(reg, ints))
                + "-- two values\n" + err(() -> withCoords(reg, twoValues))
                + "-- value not an Object\n" + err(() -> withCoords(reg, dbl))
                + "-- value a Frame\n" + err(() -> withCoords(reg, frame))
                + "-- value in a Frame of three axes\n" + err(() -> withCoords(reg, box3));
        assertEquals(probeWithoutSource("coords_bad_keys"), got);
    }

    /** A KeyMap holding null under a Region key, which C's public interface cannot store, gives C's message. */
    @Test
    void aNullAstroCoordsRegionIsStckey() {
        KeyMap km = new KeyMap();
        km.mapPut0A(Stc.AST__STCERROR, null, null);
        AstException e = assertThrows(AstException.class, () -> withCoords(circle2(0.0, 0.0, 1.0), km));
        assertEquals(AstException.AST__STCKEY, e.getStatus());
        assertEquals(
                "astInitStc(StcResourceProfile): The \"Error\" value supplied in an AstroCoords list is a NULL "
                        + "pointer. ",
                e.getMsgText());
    }

    /**
     * jniast's arguments: a null Region or AstroCoords element is a NullPointerException; a null
     * array, an empty array, an AstroCoords with a null or empty name array and null Regions give an
     * Stc with no AstroCoords elements or with empty ones.
     */
    @Test
    void jniastArguments() {
        assertThrows(NullPointerException.class, () -> new StcResourceProfile(null, new AstroCoords[0]));
        assertThrows(
                NullPointerException.class,
                () -> new StcResourceProfile(circle2(0.0, 0.0, 1.0), new AstroCoords[] {null}));
        assertEquals(0, new StcResourceProfile(circle2(0.0, 0.0, 1.0), (AstroCoords[]) null).getStcNCoord());
        assertEquals(0, new StcResourceProfile(circle2(0.0, 0.0, 1.0), new AstroCoords[0]).getStcNCoord());
        AstroCoords nullNames = new AstroCoords(null);
        AstroCoords emptyNames = new AstroCoords(new String[0]);
        StcResourceProfile stc =
                new StcResourceProfile(circle2(0.0, 0.0, 1.0), new AstroCoords[] {nullNames, emptyNames});
        assertEquals(2, stc.getStcNCoord());
        for (int i = 1; i <= 2; i++) {
            assertEquals(probe("coords_empty").split("-- coord 1\n", -1)[1], show(stc.stcCoord(i)));
            AstroCoords ac = stc.getStcCoord(i);
            assertNull(ac.getName());
            assertNull(ac.getValue());
            assertNull(ac.getError());
        }
    }

    /**
     * jniast's AstroCoords: the Stc holds copies of its Regions, mapped into its Frame, and {@link
     * Stc#getStcCoord} gives them back, shared with the KeyMap it reads.
     */
    @Test
    void jniastAstroCoordsRoundTrip() {
        AstroCoords ac = new AstroCoords(new String[] {"Frequency"});
        Interval value = specInterval("MHz", 1500.0, 1600.0);
        ac.setValue(value);
        ac.setSize(specInterval("GHz", 0.0, 0.25));
        StcResourceProfile stc = new StcResourceProfile(specInterval("GHz", 1.0, 2.0), new AstroCoords[] {ac});
        AstroCoords back = stc.getStcCoord(1);
        assertArrayEquals(new String[] {"Frequency"}, back.getName());
        assertEquals("Box", back.getValue().astClass());
        assertEquals("GHz", back.getValue().getC("Unit(1)"));
        assertEquals(Region.OVERLAP_SAME, back.getValue().overlap(value));
        assertNull(back.getError());
        assertSame(ac.getValue(), value);
    }

    // ------------------------------------------------------------------
    // Review Focus 5: attributes set on an Stc
    // ------------------------------------------------------------------

    @Test
    void frameAttributesForwardAsCDoes() {
        SkyFrame sky = new SkyFrame();
        KeyMap km = new KeyMap();
        km.mapPut1C(Stc.AST__STCNAME, new String[] {"RA", "Dec"}, null);
        km.mapPut0A(Stc.AST__STCERROR, new Circle(sky, 1, new double[] {1.0, 0.5}, new double[] {0.0001}, null), null);
        StcResourceProfile stc = withCoords(new Circle(sky, 1, new double[] {1.0, 0.5}, new double[] {0.01}, null), km);
        String readOnly = "RegionClass " + stc.getC("RegionClass") + "\ntest RegionClass "
                + flag(stc.test("RegionClass")) + "\n" + err(() -> stc.set("RegionClass=Box"))
                + err(() -> stc.clear("RegionClass"));
        stc.set("Adaptive=0");
        stc.set("System=Galactic");
        String set = "-- Adaptive=0, System=Galactic\n" + show(stc) + showCoord(stc, 1) + "-- region\n"
                + show(stc.getStcRegion());
        stc.clear("System");
        String got = readOnly + set + "-- clear System\n" + show(stc) + showCoord(stc, 1);
        String want = probeWithoutSource("attribs_sky");
        assertTrue(CValues.nativeStringsWithin(got, want, SKY_TOL), "--- got\n" + got + "--- C\n" + want);
    }

    @Test
    void regionAttributesForwardAsCDoes() {
        StcResourceProfile stc = plain();
        StringBuilder sb = new StringBuilder(showAttrs("stc", stc));
        stc.set("Negated=1");
        sb.append(showAttrs("stc", stc))
                .append(showAttrs("region", stc.getStcRegion()))
                .append(show(stc));
        stc.set("Closed=0,MeshSize=40,FillFactor=0.25");
        sb.append(showAttrs("stc", stc))
                .append(showAttrs("region", stc.getStcRegion()))
                .append(show(stc));
        stc.clear("Negated,Closed,MeshSize,FillFactor");
        sb.append("-- clear\n")
                .append(showAttrs("stc", stc))
                .append(showAttrs("region", stc.getStcRegion()))
                .append(show(stc));
        Circle reg = circle2(0.0, 0.0, 1.0);
        reg.set("Negated=1,Closed=0,MeshSize=30,FillFactor=0.5");
        StcResourceProfile fromSet = new StcResourceProfile(reg, (KeyMap[]) null);
        sb.append("-- set on the region given\n")
                .append(showAttrs("stc", fromSet))
                .append(show(fromSet));
        assertEquals(probe("attribs_region"), sb.toString());
    }

    /** testregions.c {@code checkStcNegated}. */
    @Test
    void checkStcNegated() {
        StcResourceProfile stc = plain();
        String got = tran2(stc, 0.0, 0.0, 5.0, 0.0);
        stc.negate();
        got += tran2(stc, 0.0, 0.0, 5.0, 0.0) + "Bounded " + stc.getI("Bounded") + "\n";
        assertEquals(probe("check_stc_negated"), got);
    }

    // ------------------------------------------------------------------
    // StcObsDataLocation's observatory position
    // ------------------------------------------------------------------

    /** The probe's {@code read_back}: the dump of {@code obj}, then that dump read back. */
    private static AstObject readBack(AstObject obj, StringBuilder sb) {
        String text = show(obj);
        AstObject back = ChannelText.read(text);
        sb.append("-- text\n")
                .append(text)
                .append("-- end text\n-- read ")
                .append(back.astClass())
                .append('\n');
        return back;
    }

    private static StcObsDataLocation obsData() {
        return new StcObsDataLocation(circle2(0.0, 0.0, 1.0), (KeyMap[]) null);
    }

    /** testregions.c {@code checkStcObsDump}. */
    @Test
    void checkStcObsDump() {
        StcObsDataLocation noobs = obsData();
        StringBuilder sb = new StringBuilder();
        AstObject back = readBack(noobs, sb);
        sb.append("equal ").append(flag(back.astEqual(noobs))).append('\n').append(show(back));
        assertEquals(probe("check_stc_obs_dump"), sb.toString());
    }

    /**
     * The observatory position: written as {@code ObsLoc}, held as given (so that a change to the
     * PointList shows in the next dump) and copied in a copy, not compared, and removed.
     */
    @Test
    void anObservatoryPosition() {
        StcObsDataLocation noobs = obsData();
        StcObsDataLocation obsdata = obsData();
        Frame geo = new Frame(3);
        geo.setDomain("GEO");
        PointList obs = new PointList(geo, 1, new double[][] {{1.0}, {2.0}, {3.0}}, null);
        obsdata.setObs(obs);
        StringBuilder sb = new StringBuilder("set obs status 0\n").append(show(obsdata));
        AstObject obscopy = obsdata.copy();
        obs.setIdent("given");
        sb.append("-- Ident=given set on the PointList given\n")
                .append(show(obsdata))
                .append("-- the copy\n")
                .append(show(obscopy))
                .append("equal without obs ")
                .append(flag(obsdata.astEqual(noobs)))
                .append("\nequal without obs reversed ")
                .append(flag(noobs.astEqual(obsdata)))
                .append("\nequal copy ")
                .append(flag(obsdata.astEqual(obscopy)))
                .append("\n-- removed\nset obs status 0\n");
        obsdata.setObs(null);
        sb.append(show(obsdata)).append("-- the copy read back\n");
        AstObject back = readBack(obscopy, sb);
        sb.append("equal ").append(flag(back.astEqual(obscopy))).append('\n').append(show(back));
        assertEquals(probe("obs_loc"), sb.toString());
    }

    /** An {@code ObsLoc} of another class than PointList, a ZoomMap, is read as it is, as C reads it. */
    @Test
    void anObservatoryPositionOfAnyClassIsRead() {
        String text = textRead("obs_loc_any_class");
        AstObject back = ChannelText.read(text);
        String got = "set obs status 0\n-- text\n" + text + "-- end text\n-- read " + back.astClass() + "\n"
                + show(back) + "-- copy\n" + show(back.copy());
        assertEquals(probe("obs_loc_any_class"), got);
    }

    // ------------------------------------------------------------------
    // Stcs of different classes
    // ------------------------------------------------------------------

    /** The probe's {@code show_fs}. */
    private static String showFs(String what, FrameSet fs) {
        if (fs == null) {
            return what + " 0\n";
        }
        return what + " 1 " + fs.getFrame(FrameSet.AST__BASE).astClass() + " "
                + fs.getFrame(FrameSet.AST__CURRENT).astClass() + " "
                + fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)
                        .simplify()
                        .astClass() + "\n";
    }

    /** The probe's {@code find_and_convert}. */
    private static String findAndConvert(String what, Region a, Region b) {
        return "-- " + what + "\n" + showFs("findframe", a.findFrame(b, "")) + showFs("convert", a.convert(b, ""));
    }

    private static Circle circleIn(String domain, double r) {
        Frame f = new Frame(2);
        f.setDomain(domain);
        return new Circle(f, 1, new double[] {0.0, 0.0}, new double[] {r}, null);
    }

    /** A CmpFrame of {@code reg} and a 1-D Frame in the Domain C. */
    private static CmpFrame cmpFrameC(Region reg) {
        Frame c = new Frame(1);
        c.setDomain("C");
        return new CmpFrame(reg, c);
    }

    @Test
    void stcsOfDifferentClasses() {
        StcSearchLocation search = new StcSearchLocation(circle2(0.0, 0.0, 1.0), (KeyMap[]) null);
        StcCatalogEntryLocation entry = new StcCatalogEntryLocation(circle2(0.0, 0.0, 1.0), (KeyMap[]) null);
        StcObsDataLocation obsdata = obsData();
        StcResourceProfile profile = plain();
        StcSearchLocation search2 = new StcSearchLocation(circle2(0.0, 0.0, 1.0), (KeyMap[]) null);
        StcSearchLocation searcha = new StcSearchLocation(circleIn("A", 1.0), (KeyMap[]) null);
        StcSearchLocation searchb = new StcSearchLocation(circleIn("B", 1.0), (KeyMap[]) null);
        StcObsDataLocation obsb = new StcObsDataLocation(circleIn("B", 1.0), (KeyMap[]) null);
        StcCatalogEntryLocation entryb = new StcCatalogEntryLocation(circleIn("B", 1.0), (KeyMap[]) null);
        String got = "equal search search " + flag(search.astEqual(search2)) + "\nequal search entry "
                + flag(search.astEqual(entry)) + "\nequal search obsdata " + flag(search.astEqual(obsdata))
                + "\nequal obsdata profile " + flag(obsdata.astEqual(profile)) + "\n"
                + findAndConvert("search, search", search, search2)
                + findAndConvert("search, obsdata", search, obsdata)
                + findAndConvert("obsdata, search", obsdata, search)
                + findAndConvert("entry, profile", entry, profile)
                + findAndConvert("search A, search B", searcha, searchb)
                + findAndConvert("search A, obsdata B", searcha, obsb)
                + findAndConvert("obsdata B, search A", obsb, searcha)
                + findAndConvert("search A, entry B", searcha, entryb)
                + "-- CmpFrames of search B and search A\n"
                + showFs("findframe", cmpFrameC(searchb).findFrame(cmpFrameC(searcha), ""));
        assertEquals(probe("stc_classes"), got);
    }

    /**
     * {@link Region#cast}: an Stc cast into the class of a Region is a copy if the Stc is an
     * instance of that class, and null otherwise, so that two Stc classes, which are siblings, do not
     * cast into each other; cast into the class of a plain Frame, it is its current Frame cast.
     */
    @Test
    void castWithinTheStcClasses() {
        StcSearchLocation search = new StcSearchLocation(circle2(0.0, 0.0, 1.0), (KeyMap[]) null);
        StcSearchLocation search2 = new StcSearchLocation(circle2(1.0, 0.0, 2.0), (KeyMap[]) null);
        StcObsDataLocation obsdata = obsData();
        Frame same = search.cast(search2);
        assertNotSame(search, same);
        assertSame(StcSearchLocation.class, same.getClass());
        assertTrue(search.astEqual(same));
        assertNull(search.cast(obsdata));
        assertNull(obsdata.cast(search));
        assertNull(search.cast(plain()));
        assertNull(search.cast(circle2(0.0, 0.0, 1.0)));
        assertNull(circle2(0.0, 0.0, 1.0).cast(search));
        assertSame(Frame.class, search.cast(new Frame(2)).getClass());
    }

    /**
     * Two CmpFrames, each of an Stc and a Frame, whose Stcs are in different Domains, do not convert.
     * The CmpFrame's match casts the template's Stc into the class of the target's component (frame.c
     * {@code astMatch}); C's region.c {@code Cast} crashes there unless a description of the class
     * Region has been read earlier in the process, which initialises the vtab whose class it
     * compares, so the probe's last case reads one first.
     */
    @Test
    void aCmpFrameOfStcsInOtherDomainsDoesNotConvert() {
        CmpFrame ca = cmpFrameC(new StcSearchLocation(circleIn("A", 1.0), (KeyMap[]) null));
        CmpFrame cb = cmpFrameC(new StcSearchLocation(circleIn("B", 1.0), (KeyMap[]) null));
        String c = probe("cast_after_region_read");
        assertEquals(
                "-- CmpFrames of search B and search A\n" + showFs("convert", cb.convert(ca, "")),
                c.substring(c.indexOf("-- CmpFrames")));
    }

    // ------------------------------------------------------------------
    // Dumps edited by hand
    // ------------------------------------------------------------------

    /** The text a case of the probe read, between {@code -- text} and {@code -- end text}. */
    private static String textRead(String name) {
        String c = probe(name);
        int start = c.indexOf("-- text\n") + "-- text\n".length();
        return c.substring(start, c.indexOf("-- end text\n"));
    }

    /**
     * C reads a {@code Begin Stc}, an Stc of the abstract class; no Java class is the abstract Stc
     * alone, so the read fails here with {@code AST__LDERR}.
     */
    @Test
    void anStcOfTheAbstractClassIsNotRead() {
        assertTrue(probe("begin_stc").contains("-- read Stc\nncoord 0\n"));
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(textRead("begin_stc")));
        assertEquals(AstException.AST__LDERR, e.getStatus());
        assertTrue(e.getMsgText().contains("Stc"), e.getMsgText());
    }

    @Test
    void ncoordBelowZeroReadsAsNone() {
        String text = textRead("negative_ncoord");
        Stc stc = (Stc) ChannelText.read(text);
        String got = "-- text\n" + text + "-- end text\n-- read " + stc.astClass() + "\nncoord " + stc.getStcNCoord()
                + "\n" + show(stc);
        assertEquals(probe("negative_ncoord"), got);
    }

    /**
     * The plain Stc's dump without the lines from the first starting with {@code startsWith} to the
     * next starting with {@code endsWith}.
     */
    private static String plainDumpWithout(String startsWith, String endsWith) {
        StringBuilder sb = new StringBuilder();
        boolean skipping = false;
        for (String l : CValues.lines(show(plain()))) {
            if (l.startsWith(startsWith)) {
                skipping = true;
            }
            if (!skipping) {
                sb.append(l).append('\n');
            }
            if (skipping && l.startsWith(endsWith)) {
                skipping = false;
            }
        }
        return sb.toString();
    }

    @Test
    void anStcWithoutItsRegionIsNotRead() {
        String text = plainDumpWithout("    Region =", "       End Circle");
        assertFalse(text.contains("Circle"), text);
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__LDERR, e.getStatus());
    }

    @Test
    void anStcMissingAnAstroCoordsElementIsNotRead() {
        String text = show(plain()).replace(" IsA Stc", "    Ncoord = 1\n IsA Stc");
        AstException e = assertThrows(AstException.class, () -> ChannelText.read(text));
        assertEquals(AstException.AST__LDERR, e.getStatus());
    }
}
