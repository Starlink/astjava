package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.Fixtures;
import uk.ac.starlink.ast.testsupport.ProbeData;
import uk.ac.starlink.ast.testsupport.ProbeOutput;

/**
 * Translation of Rust {@code tests/test_equal.rs} and {@code tests/test_equal_oracle.rs} (C's
 * {@code astEqual}, {@link AstObject#astEqual}), with the cases of the C probe {@code
 * probes/equal_probe.c}, which also gives C's answer for every comparison the Rust tests make: each
 * translated test compares its results with the probe's case of the Rust test's name. The probe's
 * own cases are comparisons where C's {@code Equal} and a comparison of native text disagree, a CmpMap comparison
 * that changes the Invert attribute of a shared component, CmpMaps with Ident set, FrameSets, and
 * Objects of classes without their own {@code Equal}.
 *
 * <p>Rust's {@code chebymap_same} and {@code chebymap_different_bounds} need ChebyMap and are listed
 * in {@code deferred/rust-tests.txt}. The SelectorMap cases of {@code oracle/equal.json} are
 * skipped: they need SelectorMap.
 */
class EqualTest {

    private static final double BAD = AstObject.AST__BAD;

    private static final Map<String, String> PROBE_CASES = ProbeOutput.load("equal_probe");

    private static String probe(String name) {
        String text = PROBE_CASES.get(name);
        if (text == null) {
            throw new IllegalStateException("no probe case " + name);
        }
        return text;
    }

    /**
     * Asserts that the results of a sequence of comparisons are C's, as the probe's case {@code
     * name} records them; a translated Rust test of that name makes the same comparisons.
     */
    static void assertAsC(String name, boolean... results) {
        assertEquals(probe(name), equalLines(results), name);
    }

    /** The probe's lines for a sequence of comparisons. */
    private static String equalLines(boolean... results) {
        StringBuilder sb = new StringBuilder();
        for (boolean r : results) {
            sb.append("equal ").append(r ? 1 : 0).append('\n');
        }
        return sb.toString();
    }

    private static <T extends Mapping> T inv(T m) {
        m.invert();
        return m;
    }

    private static ZoomMap zoom(int n, double f) {
        return new ZoomMap(n, f);
    }

    private static ShiftMap shift(double... s) {
        return new ShiftMap(s);
    }

    private static PermMap perm1(int in, int out, double... con) {
        return new PermMap(1, new int[] {in}, 1, new int[] {out}, con.length == 0 ? null : con);
    }

    private static Frame frame(int naxes, String settings) {
        Frame f = new Frame(naxes);
        if (!settings.isEmpty()) {
            f.set(settings);
        }
        return f;
    }

    private static SkyFrame sky(String settings) {
        SkyFrame f = new SkyFrame();
        f.set(settings);
        return f;
    }

    // ------------------------------------------------------------------
    // ast_equal tolerance behaviour (via ZoomMap as a vehicle)
    // ------------------------------------------------------------------

    @Test
    void floatToleranceWithin() {
        assertAsC("float_tolerance_within", zoom(2, 1.0).astEqual(zoom(2, 1.0 + 1.0e-14)));
    }

    @Test
    void floatToleranceOutside() {
        assertAsC("float_tolerance_outside", zoom(2, 1.0).astEqual(zoom(2, 1.1)));
    }

    @Test
    void badValueComparison() {
        assertAsC("bad_value_comparison", perm1(-1, -1, BAD).astEqual(perm1(-1, -1, BAD)));
    }

    @Test
    void badVsNonBadSameIndexIsNotEqual() {
        assertAsC("bad_vs_non_bad_same_index_is_not_equal", perm1(-1, -1, BAD).astEqual(perm1(-1, -1, 42.0)));
    }

    @Test
    void badVsNonBadDifferentIndexIsNotEqual() {
        assertAsC(
                "bad_vs_non_bad_different_index_is_not_equal",
                perm1(-1, -1, BAD, 0.0).astEqual(perm1(-2, -2, 0.0, 42.0)));
    }

    // ------------------------------------------------------------------
    // UnitMap
    // ------------------------------------------------------------------

    @Test
    void unitmapIdentity() {
        UnitMap a = new UnitMap(3);
        assertAsC("unitmap_identity", a.astEqual(a));
    }

    @Test
    void unitmapSameNcoord() {
        assertAsC("unitmap_same_ncoord", new UnitMap(3).astEqual(new UnitMap(3)));
    }

    @Test
    void unitmapDifferentNcoord() {
        assertAsC("unitmap_different_ncoord", new UnitMap(3).astEqual(new UnitMap(2)));
    }

    // ------------------------------------------------------------------
    // ZoomMap
    // ------------------------------------------------------------------

    @Test
    void zoommapIdentity() {
        ZoomMap a = zoom(2, 3.5);
        assertAsC("zoommap_identity", a.astEqual(a));
    }

    @Test
    void zoommapSameValues() {
        assertAsC("zoommap_same_values", zoom(2, 3.5).astEqual(zoom(2, 3.5)));
    }

    @Test
    void zoommapDifferentFactor() {
        assertAsC("zoommap_different_factor", zoom(2, 3.5).astEqual(zoom(2, 4.0)));
    }

    @Test
    void zoommapDifferentNcoord() {
        assertAsC("zoommap_different_ncoord", zoom(2, 3.5).astEqual(zoom(3, 3.5)));
    }

    @Test
    void zoommapInvertAware() {
        assertAsC("zoommap_invert_aware", inv(zoom(2, 2.0)).astEqual(zoom(2, 0.5)));
    }

    @Test
    void zoommapInvertAwareMismatch() {
        assertAsC("zoommap_invert_aware_mismatch", inv(zoom(2, 3.0)).astEqual(zoom(2, 0.5)));
    }

    // ------------------------------------------------------------------
    // ShiftMap
    // ------------------------------------------------------------------

    @Test
    void shiftmapIdentity() {
        ShiftMap a = shift(1.0, 2.0, 3.0);
        assertAsC("shiftmap_identity", a.astEqual(a));
    }

    @Test
    void shiftmapSameValues() {
        assertAsC("shiftmap_same_values", shift(1.0, 2.0).astEqual(shift(1.0, 2.0)));
    }

    @Test
    void shiftmapDifferentShifts() {
        assertAsC("shiftmap_different_shifts", shift(1.0, 2.0).astEqual(shift(1.0, 3.0)));
    }

    @Test
    void shiftmapDifferentLength() {
        assertAsC("shiftmap_different_length", shift(1.0, 2.0).astEqual(shift(1.0, 2.0, 3.0)));
    }

    @Test
    void shiftmapInvertAware() {
        assertAsC("shiftmap_invert_aware", inv(shift(1.0, 2.0)).astEqual(shift(-1.0, -2.0)));
    }

    @Test
    void shiftmapInvertAwareMismatch() {
        assertAsC("shiftmap_invert_aware_mismatch", inv(shift(1.0, 2.0)).astEqual(shift(1.0, 2.0)));
    }

    // ------------------------------------------------------------------
    // MatrixMap
    // ------------------------------------------------------------------

    /** A MatrixMap of the full matrix given as rows. */
    private static MatrixMap full(double[]... rows) {
        return new MatrixMap(rows[0].length, rows.length, rows);
    }

    @Test
    void matrixmapSame() {
        assertAsC(
                "matrixmap_same",
                full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0})
                        .astEqual(full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0})));
    }

    @Test
    void matrixmapDifferentElement() {
        assertAsC(
                "matrixmap_different_element",
                full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0})
                        .astEqual(full(new double[] {2.0, 0.0}, new double[] {0.0, 4.0})));
    }

    @Test
    void matrixmapInvertedNotEqual() {
        MatrixMap a = inv(full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0}));
        assertAsC("matrixmap_inverted_not_equal", a.astEqual(full(new double[] {2.0, 0.0}, new double[] {0.0, 3.0})));
    }

    // ------------------------------------------------------------------
    // PermMap
    // ------------------------------------------------------------------

    @Test
    void permmapSame() {
        PermMap a = new PermMap(2, new int[] {1, 2}, 2, new int[] {2, 1}, null);
        PermMap b = new PermMap(2, new int[] {1, 2}, 2, new int[] {2, 1}, null);
        assertAsC("permmap_same", a.astEqual(b));
    }

    @Test
    void permmapDifferentPerm() {
        PermMap a = new PermMap(2, new int[] {1, 2}, 2, new int[] {2, 1}, null);
        PermMap b = new PermMap(2, new int[] {1, 2}, 2, new int[] {1, 2}, null);
        assertAsC("permmap_different_perm", a.astEqual(b));
    }

    @Test
    void permmapEqualCrossInversion() {
        PermMap pm1 = new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null);
        PermMap pm2 = inv(new PermMap(2, new int[] {2, 1}, 2, new int[] {2, 1}, null));
        boolean r1 = pm1.astEqual(pm2);
        PermMap pm3 = new PermMap(3, new int[] {2, 3, 1}, 3, new int[] {3, 1, 2}, null);
        PermMap pm3Inv = inv((PermMap) pm3.copy());
        PermMap pm4 = new PermMap(3, new int[] {3, 1, 2}, 3, new int[] {2, 3, 1}, null);
        boolean r2 = pm3Inv.astEqual(pm4);
        assertAsC("permmap_equal_cross_inversion", r1, r2);
    }

    // ------------------------------------------------------------------
    // WinMap
    // ------------------------------------------------------------------

    private static WinMap win(double[] shifts, double[] scales) {
        return new WinMap(shifts, scales);
    }

    @Test
    void winmapSame() {
        assertAsC(
                "winmap_same",
                win(new double[] {1.0, 2.0}, new double[] {3.0, 4.0})
                        .astEqual(win(new double[] {1.0, 2.0}, new double[] {3.0, 4.0})));
    }

    @Test
    void winmapDifferentScale() {
        assertAsC(
                "winmap_different_scale",
                win(new double[] {1.0, 2.0}, new double[] {3.0, 4.0})
                        .astEqual(win(new double[] {1.0, 2.0}, new double[] {3.0, 5.0})));
    }

    @Test
    void winmapInvertedNotEqual() {
        WinMap a = inv(win(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}));
        assertAsC("winmap_inverted_not_equal", a.astEqual(win(new double[] {1.0, 2.0}, new double[] {3.0, 4.0})));
    }

    @Test
    void winmapEqualCrossInversion() {
        WinMap wm1Inv = inv(win(new double[] {2.0}, new double[] {3.0}));
        WinMap wm2 = win(new double[] {-2.0 / 3.0}, new double[] {1.0 / 3.0});
        assertAsC("winmap_equal_cross_inversion", wm1Inv.astEqual(wm2));
    }

    // ------------------------------------------------------------------
    // CmpMap (recursive)
    // ------------------------------------------------------------------

    @Test
    void cmpmapSeriesSame() {
        CmpMap a = new CmpMap(zoom(2, 3.0), shift(1.0, 2.0), true);
        CmpMap b = new CmpMap(zoom(2, 3.0), shift(1.0, 2.0), true);
        assertAsC("cmpmap_series_same", a.astEqual(b));
    }

    @Test
    void cmpmapDifferentComponent() {
        CmpMap a = new CmpMap(zoom(2, 3.0), shift(1.0, 2.0), true);
        CmpMap b = new CmpMap(zoom(2, 4.0), shift(1.0, 2.0), true);
        assertAsC("cmpmap_different_component", a.astEqual(b));
    }

    @Test
    void cmpmapDifferentKind() {
        CmpMap a = new CmpMap(new UnitMap(2), new UnitMap(2), true);
        CmpMap b = new CmpMap(new UnitMap(2), new UnitMap(2), false);
        assertAsC("cmpmap_different_kind", a.astEqual(b));
    }

    // ------------------------------------------------------------------
    // PolyMap
    // ------------------------------------------------------------------

    @Test
    void polymapSame() {
        double[] coeffs = {2.0, 1.0, 1.0, 0.0, 3.0, 2.0, 0.0, 1.0};
        PolyMap a = new PolyMap(2, 2, 2, coeffs, 0, null);
        PolyMap b = new PolyMap(2, 2, 2, coeffs, 0, null);
        assertAsC("polymap_same", a.astEqual(b));
    }

    @Test
    void polymapDifferentCoeff() {
        PolyMap a = new PolyMap(1, 1, 1, new double[] {2.0, 1.0, 1.0}, 0, null);
        PolyMap b = new PolyMap(1, 1, 1, new double[] {3.0, 1.0, 1.0}, 0, null);
        assertAsC("polymap_different_coeff", a.astEqual(b));
    }

    // ------------------------------------------------------------------
    // LutMap
    // ------------------------------------------------------------------

    @Test
    void lutmapSame() {
        LutMap a = new LutMap(new double[] {0.0, 1.0, 4.0, 9.0}, 0.0, 1.0);
        LutMap b = new LutMap(new double[] {0.0, 1.0, 4.0, 9.0}, 0.0, 1.0);
        assertAsC("lutmap_same", a.astEqual(b));
    }

    @Test
    void lutmapDifferentValues() {
        LutMap a = new LutMap(new double[] {0.0, 1.0, 4.0, 9.0}, 0.0, 1.0);
        LutMap b = new LutMap(new double[] {0.0, 1.0, 4.0, 16.0}, 0.0, 1.0);
        assertAsC("lutmap_different_values", a.astEqual(b));
    }

    @Test
    void lutmapDifferentStart() {
        LutMap a = new LutMap(new double[] {0.0, 1.0}, 0.0, 1.0);
        LutMap b = new LutMap(new double[] {0.0, 1.0}, 1.0, 1.0);
        assertAsC("lutmap_different_start", a.astEqual(b));
    }

    // ------------------------------------------------------------------
    // WcsMap
    // ------------------------------------------------------------------

    @Test
    void wcsmapSame() {
        assertAsC("wcsmap_same", new WcsMap(2, WcsMap.AST__TAN, 1, 2).astEqual(new WcsMap(2, WcsMap.AST__TAN, 1, 2)));
    }

    @Test
    void wcsmapDifferentProj() {
        assertAsC(
                "wcsmap_different_proj",
                new WcsMap(2, WcsMap.AST__TAN, 1, 2).astEqual(new WcsMap(2, WcsMap.AST__SIN, 1, 2)));
    }

    @Test
    void wcsmapDifferentAxes() {
        assertAsC(
                "wcsmap_different_axes",
                new WcsMap(3, WcsMap.AST__TAN, 1, 2).astEqual(new WcsMap(3, WcsMap.AST__TAN, 1, 3)));
    }

    // ------------------------------------------------------------------
    // SlaMap
    // ------------------------------------------------------------------

    private static SlaMap sla(String cvt, double... args) {
        SlaMap m = new SlaMap();
        m.add(cvt, args);
        return m;
    }

    @Test
    void slamapSame() {
        assertAsC("slamap_same", sla("EQGAL").astEqual(sla("EQGAL")));
    }

    @Test
    void slamapDifferentType() {
        assertAsC("slamap_different_type", sla("EQGAL").astEqual(sla("GALEQ")));
    }

    @Test
    void slamapDifferentArgs() {
        assertAsC("slamap_different_args", sla("PREC", 50000.0, 51000.0).astEqual(sla("PREC", 50000.0, 52000.0)));
    }

    // ------------------------------------------------------------------
    // SphMap
    // ------------------------------------------------------------------

    @Test
    void sphmapSame() {
        assertAsC("sphmap_same", new SphMap().astEqual(new SphMap()));
    }

    @Test
    void sphmapDifferentPolarLong() {
        SphMap a = new SphMap();
        a.setPolarLong(1.5);
        assertAsC("sphmap_different_polar_long", a.astEqual(new SphMap()));
    }

    @Test
    void sphmapDifferentUnitRadius() {
        SphMap a = new SphMap();
        a.setUnitRadius(true);
        assertAsC("sphmap_different_unit_radius", a.astEqual(new SphMap()));
    }

    @Test
    void sphmapInverted() {
        assertAsC("sphmap_inverted", inv(new SphMap()).astEqual(new SphMap()));
    }

    @Test
    void cmpmapEqualDifferentTreeStructure() {
        ShiftMap a = shift(1.0, 2.0);
        ZoomMap b = zoom(2, 3.0);
        ShiftMap c = shift(4.0, 5.0);
        CmpMap bc = new CmpMap((Mapping) b.copy(), (Mapping) c.copy(), true);
        CmpMap abc1 = new CmpMap((Mapping) a.copy(), bc, true);
        CmpMap ab = new CmpMap((Mapping) a.copy(), (Mapping) b.copy(), true);
        CmpMap abc2 = new CmpMap(ab, (Mapping) c.copy(), true);
        assertAsC("cmpmap_equal_different_tree_structure", abc1.astEqual(abc2));
    }

    // ------------------------------------------------------------------
    // Cross-type rejection
    // ------------------------------------------------------------------

    @Test
    void crossTypeZoomVsShift() {
        assertAsC("cross_type_zoom_vs_shift", zoom(2, 1.0).astEqual(shift(0.0, 0.0)));
    }

    @Test
    void crossTypeUnitVsZoom() {
        assertAsC("cross_type_unit_vs_zoom", new UnitMap(2).astEqual(zoom(2, 1.0)));
    }

    @Test
    void crossTypeWinmapVsShiftmap() {
        assertAsC(
                "cross_type_winmap_vs_shiftmap",
                win(new double[] {0.0}, new double[] {1.0}).astEqual(shift(0.0)));
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------

    @Test
    void basicframeSame() {
        assertAsC("basicframe_same", frame(2, "Title=test,Domain=PIXEL").astEqual(frame(2, "Title=test,Domain=PIXEL")));
    }

    @Test
    void basicframeDifferentNaxes() {
        assertAsC("basicframe_different_naxes", frame(2, "").astEqual(frame(3, "")));
    }

    @Test
    void basicframeDifferentTitleIsEqual() {
        assertAsC("basicframe_different_title_is_equal", frame(2, "Title=A").astEqual(frame(2, "Title=B")));
    }

    @Test
    void basicframeDifferentDomain() {
        assertAsC("basicframe_different_domain", frame(2, "Domain=X").astEqual(frame(2, "Domain=Y")));
    }

    @Test
    void basicframeDifferentEpochIsEqual() {
        assertAsC(
                "basicframe_different_epoch_is_equal",
                frame(2, "Epoch=MJD 50000").astEqual(frame(2, "Epoch=MJD 51000")));
    }

    @Test
    void basicframeEpochSetVsUnsetIsEqual() {
        assertAsC(
                "basicframe_epoch_set_vs_unset_is_equal",
                frame(2, "Epoch=MJD 50000").astEqual(frame(2, "")));
    }

    @Test
    void basicframeDifferentLabelsIsEqual() {
        assertAsC("basicframe_different_labels_is_equal", frame(2, "Label(1)=X").astEqual(frame(2, "Label(1)=Y")));
    }

    // ------------------------------------------------------------------
    // CmpFrame
    // ------------------------------------------------------------------

    @Test
    void cmpframeSame() {
        CmpFrame a = new CmpFrame(frame(1, ""), frame(1, ""));
        CmpFrame b = new CmpFrame(frame(1, ""), frame(1, ""));
        assertAsC("cmpframe_same", a.astEqual(b));
    }

    @Test
    void cmpframeDifferentChild() {
        CmpFrame a = new CmpFrame(frame(1, "Domain=A"), frame(1, ""));
        CmpFrame b = new CmpFrame(frame(1, "Domain=B"), frame(1, ""));
        assertAsC("cmpframe_different_child", a.astEqual(b));
    }

    // ------------------------------------------------------------------
    // SkyFrame
    // ------------------------------------------------------------------

    @Test
    void skyframeSame() {
        assertAsC("skyframe_same", sky("System=FK5").astEqual(sky("System=FK5")));
    }

    @Test
    void skyframeDifferentSystem() {
        assertAsC("skyframe_different_system", sky("System=FK5").astEqual(sky("System=Galactic")));
    }

    @Test
    void skyframeDifferentEquinox() {
        assertAsC(
                "skyframe_different_equinox",
                sky("System=FK5,Equinox=MJD 50000").astEqual(sky("System=FK5,Equinox=MJD 51000")));
    }

    @Test
    void skyframeEquinoxSetVsUnsetDefaultIsEqual() {
        assertAsC(
                "skyframe_equinox_set_vs_unset_default_is_equal",
                sky("System=FK5,Equinox=MJD 51544.5").astEqual(sky("System=FK5")));
    }

    @Test
    void skyframeDifferentEpoch() {
        assertAsC(
                "skyframe_different_epoch",
                sky("System=FK5,Epoch=MJD 50000").astEqual(sky("System=FK5,Epoch=MJD 51000")));
    }

    @Test
    void skyframeSkyRefIsOriginWithoutSkyRefIsEqual() {
        assertAsC(
                "skyframe_sky_ref_is_origin_without_sky_ref_is_equal",
                sky("System=FK5,SkyRefIs=Origin").astEqual(sky("System=FK5")));
    }

    @Test
    void skyframeOffsetOriginWithSkyRefIsNotEqual() {
        SkyFrame a = sky("System=FK5,SkyRefIs=Origin");
        a.setD("SkyRef(1)", 1.0);
        a.setD("SkyRef(2)", 0.5);
        assertAsC("skyframe_offset_origin_with_sky_ref_is_not_equal", a.astEqual(sky("System=FK5")));
    }

    @Test
    void skyframeSkyRefWithoutSkyRefIsIsEqual() {
        SkyFrame a = sky("System=FK5");
        a.setD("SkyRef(1)", 1.0);
        a.setD("SkyRef(2)", 0.5);
        assertAsC("skyframe_sky_ref_without_sky_ref_is_is_equal", a.astEqual(sky("System=FK5")));
    }

    @Test
    void skyframeDifferentNeglonIsEqual() {
        assertAsC(
                "skyframe_different_neglon_is_equal", sky("System=FK5,NegLon=1").astEqual(sky("System=FK5")));
    }

    @Test
    void skyframeDifferentTitleIsEqual() {
        assertAsC("skyframe_different_title_is_equal", sky("System=FK5,Title=A").astEqual(sky("System=FK5,Title=B")));
    }

    @Test
    void crossTypeSkyframeVsBasicframe() {
        assertAsC("cross_type_skyframe_vs_basicframe", sky("System=FK5").astEqual(frame(2, "")));
    }

    @Test
    void crossTypeBasicframeVsSkyframe() {
        assertAsC("cross_type_basicframe_vs_skyframe", frame(2, "").astEqual(sky("System=FK5")));
    }

    // ------------------------------------------------------------------
    // FrameSet
    // ------------------------------------------------------------------

    private static FrameSet frameSet(double zoom, String currentSettings) {
        FrameSet fs = new FrameSet(frame(2, ""));
        fs.addFrame(FrameSet.AST__BASE, zoom(2, zoom), frame(2, currentSettings));
        return fs;
    }

    /** Rust compares the FrameSets as Frames and as Mappings; C has one {@code astEqual}. */
    @Test
    void framesetSame() {
        assertAsC("frameset_same", frameSet(2.0, "Title=current").astEqual(frameSet(2.0, "Title=current")));
    }

    @Test
    void framesetDifferentMapping() {
        assertAsC("frameset_different_mapping", frameSet(2.0, "").astEqual(frameSet(3.0, "")));
    }

    // ------------------------------------------------------------------
    // Serialisation round trip preserves equality
    // ------------------------------------------------------------------

    private static AstObject roundTrip(AstObject obj) {
        return ChannelText.read(ChannelText.dump(obj));
    }

    @Test
    void serialiseRoundTripZoommap() {
        ZoomMap original = zoom(2, 3.14159);
        assertAsC("serialise_round_trip_zoommap", original.astEqual(roundTrip(original)));
    }

    @Test
    void serialiseRoundTripShiftmap() {
        ShiftMap original = shift(1.5, -2.7, 0.001);
        assertAsC("serialise_round_trip_shiftmap", original.astEqual(roundTrip(original)));
    }

    @Test
    void serialiseRoundTripSkyframe() {
        SkyFrame original = sky("System=FK5,Epoch=MJD 52000,Equinox=MJD 51544.5,NegLon=1,Title=Test Sky");
        assertAsC("serialise_round_trip_skyframe", original.astEqual(roundTrip(original)));
    }

    // ------------------------------------------------------------------
    // MatrixMap equality with inversion and storage normalisation
    // ------------------------------------------------------------------

    @Test
    void matrixmapEqualCrossInversion() {
        MatrixMap mmInv = inv(full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0}));
        MatrixMap mmDirect = full(new double[] {-2.0, 1.0}, new double[] {1.5, -0.5});
        boolean r1 = mmInv.astEqual(mmDirect);
        MatrixMap mmFwd = full(new double[] {1.0, 2.0}, new double[] {3.0, 4.0});
        boolean r2 = mmFwd.astEqual(mmInv);
        assertAsC("matrixmap_equal_cross_inversion", r1, r2);
    }

    // ------------------------------------------------------------------
    // tests/test_equal_oracle.rs
    // ------------------------------------------------------------------

    private static WinMap winCorners(double ina, double inb, double outa, double outb) {
        return new WinMap(1, new double[] {ina}, new double[] {inb}, new double[] {outa}, new double[] {outb});
    }

    private static WcsMap sinPv(double... pv) {
        WcsMap w = new WcsMap(2, WcsMap.AST__SIN, 1, 2);
        for (int i = 0; i < pv.length; i++) {
            w.setD("PV2_" + (i + 1), pv[i]);
        }
        return w;
    }

    /** The two Mappings an oracle case compares, or null for a case that needs SelectorMap. */
    private static Mapping[] pair(String name) {
        switch (name) {
            case "winmap_self_inverse_vs_inverted_copy": {
                WinMap a = winCorners(0.0, 1.0, 0.0, -1.0);
                return new Mapping[] {a, inv((WinMap) a.copy())};
            }
            case "winmap_inverse_of_its_inverse":
                return new Mapping[] {winCorners(0.0, 1.0, 1.0, 3.0), inv(winCorners(0.0, 2.0, -0.5, 0.5))};
            case "selectormap_one_region_vs_two":
            case "selectormap_vs_inverted_copy":
            case "selectormap_1d_vs_inverted_copy":
                return null;
            case "tranmap_vs_reread_without_component_invert": {
                TranMap tm = new TranMap(inv(zoom(1, 2.0)), zoom(1, 0.5));
                String dump = ChannelText.dump(tm);
                assertTrue(dump.contains("InvA = 1"));
                StringBuilder edited = new StringBuilder();
                boolean dropped = false;
                for (String line : dump.split("\n", -1)) {
                    if (!dropped && line.contains(" Invert = 1")) {
                        dropped = true;
                        continue;
                    }
                    edited.append(line).append('\n');
                }
                return new Mapping[] {tm, (Mapping) ChannelText.read(edited.toString())};
            }
            case "wcsmap_pv_vs_none":
                return new Mapping[] {sinPv(0.5), sinPv()};
            case "wcsmap_zero_pv_vs_none":
                return new Mapping[] {sinPv(0.0), sinPv()};
            case "wcsmap_pv_vs_pv_with_one_cleared": {
                WcsMap b = sinPv(0.5, 1.0);
                b.clear("PV2_2");
                return new Mapping[] {sinPv(0.5), b};
            }
            case "wcsmap_same_pv":
                return new Mapping[] {sinPv(0.5), sinPv(0.5)};
            default:
                throw new IllegalStateException("no construction for case " + name);
        }
    }

    @Test
    void equalityMatchesTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (JsonNode c : ProbeData.load("equal").get("CASES")) {
            String name = c.get(0).asText();
            Mapping[] p = pair(name);
            if (p == null) {
                continue;
            }
            boolean got = p[0].astEqual(p[1]);
            if (got != c.get(1).asBoolean()) {
                failures.add(name + ": " + got + ", C " + c.get(1).asBoolean());
            }
            run++;
        }
        assertEquals(7, run, "the in-scope cases the C probe recorded");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** testmapping.c {@code testwcsmapequal} asserts cases 1 and 3 in both directions. */
    @Test
    void wcsmapEqualityIsSymmetricWhereCChecksIt() {
        for (String name : new String[] {"wcsmap_pv_vs_none", "wcsmap_pv_vs_pv_with_one_cleared"}) {
            boolean want = false;
            for (JsonNode c : ProbeData.load("equal").get("CASES")) {
                if (c.get(0).asText().equals(name)) {
                    want = c.get(1).asBoolean();
                }
            }
            Mapping[] p = pair(name);
            assertEquals(want, p[1].astEqual(p[0]), name + ", reversed");
        }
    }

    // ------------------------------------------------------------------
    // The C probe: where C's Equal and a comparison of text disagree
    // ------------------------------------------------------------------

    /** An inverted ZoomMap equals one of the reciprocal factor, though they are written differently. */
    @Test
    void anInvertedZoommapEqualsTheReciprocalAsC() {
        ZoomMap a = inv(zoom(1, 2.0));
        ZoomMap b = zoom(1, 0.5);
        assertNotEquals(a, b);
        assertEquals(probe("zoommap_inverted_vs_reciprocal"), equalLines(a.astEqual(b)));
    }

    /** ID and Ident play no part in a comparison. */
    @Test
    void identifiersAreIgnoredAsC() {
        ZoomMap a = zoom(1, 2.0);
        ZoomMap b = zoom(1, 2.0);
        a.setID("a");
        b.setID("b");
        assertNotEquals(a, b);
        assertEquals(probe("differing_id"), equalLines(a.astEqual(b)));
        a = zoom(1, 2.0);
        b = zoom(1, 2.0);
        a.setIdent("a");
        b.setIdent("b");
        assertNotEquals(a, b);
        assertEquals(probe("differing_ident"), equalLines(a.astEqual(b)));
    }

    private static MathMap mathmap(String f, String i) {
        return new MathMap(1, 1, new String[] {f}, new String[] {i});
    }

    /** C's MathMap comparison never compares the last opcode of a function. */
    @Test
    void mathmapsDifferingInTheirLastOpcodeAreEqualAsC() {
        assertEquals(
                probe("mathmap_last_opcode"),
                equalLines(
                        mathmap("y = x + 1", "x = y - 1").astEqual(mathmap("y = x - 1", "x = y + 1")),
                        mathmap("y = x * 2", "x = y / 2").astEqual(mathmap("y = x / 2", "x = y * 2")),
                        mathmap("y = x + 1", "x = y - 1").astEqual(mathmap("y = x + 2", "x = y - 2"))));
    }

    /**
     * Comparing two CmpMaps that share a component, recorded with different Invert values, leaves
     * the component with the Invert value of the first CmpMap's record (cmpmap.c {@code Equal}).
     */
    @Test
    void comparingCmpmapsWithASharedComponentSetsItsInvertAsC() {
        ZoomMap z = zoom(1, 2.0);
        UnitMap u = new UnitMap(1);
        CmpMap a = new CmpMap(z, u, true);
        z.invert();
        CmpMap b = new CmpMap(z, u, true);
        String before = invertLine("invert before", z);
        String first = equalLines(a.astEqual(b));
        String after = invertLine("invert after", z);
        String second = equalLines(b.astEqual(a));
        String afterReverse = invertLine("invert after reverse", z);
        assertEquals(probe("cmpmap_shared_component"), before + first + after + second + afterReverse);
    }

    private static String invertLine(String what, Mapping m) {
        return what + " " + (m.getInvert() ? 1 : 0) + " set " + (m.testInvert() ? 1 : 0) + "\n";
    }

    /**
     * Simplifying {@code simplify/brad.map} compares CmpMaps that share its WcsMap, recorded not
     * inverted, whose Invert attribute is 1; the comparison leaves the WcsMap with the recorded
     * value, so the Mapping read and its simplification both hold it not inverted, as in C (probe
     * {@code probes/simplify_input_probe.c}).
     */
    @Test
    void simplifyingBradChangesItsSharedWcsmapAsC() {
        Map<String, String> c = ProbeOutput.load("simplify_input_probe");
        Mapping m = (Mapping) ChannelText.read(Fixtures.read("simplify/brad.map"));
        assertEquals(c.get("brad/input_before"), ChannelText.dump(m));
        Mapping s = m.simplify();
        assertEquals(c.get("brad/simplified"), ChannelText.dump(s));
        assertEquals(c.get("brad/input_after"), ChannelText.dump(m));
    }

    /** CmpMaps with Ident set are compared through copies of their components. */
    @Test
    void cmpmapsWithIdentAreComparedAsC() {
        UnitMap u = new UnitMap(1);
        StringBuilder sb = new StringBuilder();
        sb.append(equalLines(
                ident(new CmpMap(zoom(1, 2.0), u, true), "a").astEqual(ident(new CmpMap(zoom(1, 2.0), u, true), "b")),
                ident(new CmpMap(zoom(1, 2.0), u, true), "a").astEqual(ident(new CmpMap(zoom(1, 3.0), u, true), "b")),
                ident(new CmpMap(zoom(1, 2.0), u, true), "a").astEqual(new CmpMap(zoom(1, 2.0), u, true))));
        ZoomMap z = zoom(1, 2.0);
        CmpMap a = ident(new CmpMap(z, u, true), "a");
        z.invert();
        CmpMap b = ident(new CmpMap(z, u, true), "b");
        sb.append(equalLines(a.astEqual(b)))
                .append(invertLine("invert after", z))
                .append(equalLines(inv(ident(new CmpMap(zoom(1, 2.0), zoom(1, 3.0), true), "a"))
                        .astEqual(ident(new CmpMap(zoom(1, 1.0 / 3.0), zoom(1, 0.5), true), "b"))));
        assertEquals(probe("cmpmap_ident"), sb.toString());
    }

    private static CmpMap ident(CmpMap m, String ident) {
        m.setIdent(ident);
        return m;
    }

    /** FrameSets compare their Frames by conversion, so Titles play no part. */
    @Test
    void framesetsWhoseFramesDifferInTitleAreEqualAsC() {
        FrameSet a = new FrameSet(frame(2, "Title=A"));
        a.addFrame(FrameSet.AST__BASE, zoom(2, 2.0), frame(2, "Title=B"));
        FrameSet b = new FrameSet(frame(2, "Title=C"));
        b.addFrame(FrameSet.AST__BASE, zoom(2, 2.0), frame(2, "Title=D"));
        String first = equalLines(a.astEqual(b));
        b.setCurrent(1);
        assertEquals(
                probe("frameset_frames_differ_in_title"),
                first
                        + equalLines(
                                a.astEqual(b),
                                a.astEqual(frame(2, "")),
                                frame(2, "").astEqual(a)));
    }

    /**
     * C compares ZoomMaps' factors as stored, zero once the Zoom attribute is cleared, so a ZoomMap
     * whose Zoom is cleared, though its factor is 1, does not equal a ZoomMap of factor 1; against
     * an inverted ZoomMap the reciprocal of zero is infinite, which {@code astEQUAL} finds equal to
     * anything.
     */
    @Test
    void zoommapsCompareTheirStoredFactorsAsC() {
        ZoomMap a = zoom(1, 2.0);
        a.clear("Zoom");
        ZoomMap b = zoom(1, 2.0);
        b.clear("Zoom");
        assertEquals(
                probe("zoommap_cleared_zoom"),
                equalLines(
                        a.astEqual(zoom(1, 1.0)),
                        zoom(1, 1.0).astEqual(a),
                        a.astEqual(inv(b)),
                        zoom(1, 3.0).astEqual(inv((ZoomMap) a.copy()))));
    }

    /** frameset.c {@code Equal} does not compare the FrameSets' Invert attributes. */
    @Test
    void anInvertedFramesetEqualsItsOriginalAsC() {
        FrameSet a = frameSet(2.0, "");
        assertEquals(probe("frameset_inverted"), equalLines(a.astEqual(inv((FrameSet) a.copy()))));
    }

    /** Objects of a class without its own {@code Equal} are equal when they are of the same class. */
    @Test
    void objectsOfTheSameClassAreEqualAsC() {
        KeyMap k1 = new KeyMap();
        KeyMap k2 = new KeyMap();
        String first = equalLines(new KeyMap().astEqual(new KeyMap()));
        k1.mapPut0I("A", 1, null);
        k2.mapPut0I("B", 2, null);
        assertEquals(probe("object_classes"), first + equalLines(k1.astEqual(k2), k1.astEqual(zoom(1, 1.0))));
    }

    /** A Mapping is not equal to a missing Object (object.c {@code astEqual_}). */
    @Test
    void nothingIsEqualToNull() {
        assertFalse(zoom(1, 1.0).astEqual(null));
    }
}
