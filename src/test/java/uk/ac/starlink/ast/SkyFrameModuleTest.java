package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The unit tests of Rust {@code src/frames/skyframe.rs}.
 *
 * <p>Rust's {@code map_from} has no Java counterpart; the tests using it build the same Mapping
 * with {@link Frame#convert}, which is how C obtains it. Rust's {@code pick_axes_snapshot} is C's
 * {@code astPickAxes}, with axes counted from 1. Rust's {@code skyframe_is_send} tests a Rust
 * trait bound and is left out ({@code Rust-only API}).
 */
class SkyFrameModuleTest {

    private static final double TOL = 1.0e-10;
    private static final double BAD = AstObject.AST__BAD;
    private static final double HALF_PI = Math.PI / 2;

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    private static void assertClose(double a, double b, double tol) {
        assertTrue(Math.abs(a - b) < tol, "expected " + a + " ~= " + b + " (diff = " + Math.abs(a - b) + ")");
    }

    /** The difference between two longitudes, wrapped into [0, pi]. */
    private static double lonErr(double a, double b) {
        double twoPi = 2.0 * Math.PI;
        double d = a - b;
        d -= twoPi * Math.floor(d / twoPi);
        return Math.min(d, twoPi - d);
    }

    private static void setSkyRefLonLat(SkyFrame f, double lon, double lat) {
        f.setSkyRef(f.getLonAxis(), lon);
        f.setSkyRef(f.getLatAxis(), lat);
    }

    private static void setSkyRefPLonLat(SkyFrame f, double lon, double lat) {
        f.setSkyRefP(f.getLonAxis(), lon);
        f.setSkyRefP(f.getLatAxis(), lat);
    }

    private static double[] tran(Mapping m, double lon, double lat, boolean forward) {
        double[][] out = m.tran2(1, new double[] {lon}, new double[] {lat}, forward);
        return new double[] {out[0][0], out[1][0]};
    }

    private static Mapping baseToCurrent(FrameSet fs) {
        return fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
    }

    // Construction and defaults

    @Test
    void newDefaults() {
        SkyFrame f = sky("FK5");
        assertEquals(2, f.getNaxes());
        assertEquals(SkyFrame.AST__FK5, f.systemValue());
        assertEquals("SKY", f.getDomain());
        assertClose(f.getEpochMjd(), 51544.5, TOL);
        assertClose(f.getEquinoxMjd(), 51544.5, TOL);
        assertFalse(f.getNegLon());
        assertEquals(SkyFrame.AST__IGNORED_REF, f.getSkyRefIsValue());
        assertFalse(f.getAlignOffset());
        assertClose(f.getSkyRef(f.getLonAxis()), 0.0, TOL);
        assertClose(f.getSkyRef(f.getLatAxis()), 0.0, TOL);
        assertClose(f.getSkyRefP(f.getLonAxis()), 0.0, TOL);
        assertClose(f.getSkyRefP(f.getLatAxis()), HALF_PI, TOL);
        assertClose(f.getSkyTol(), 0.001, TOL);
        assertFalse(f.testProjection());
        // The default title C reports (skyframe.c GetTitle).
        assertFalse(f.testTitle());
        assertEquals("FK5 equatorial coordinates; mean equinox J2000.0", f.getTitle());
    }

    @Test
    void fk4DefaultEquinox() {
        assertClose(sky("FK4").getEquinoxMjd(), Pal.epb2d(1950.0), 1.0e-6);
    }

    // Attribute setters

    @Test
    void setSystem() {
        SkyFrame f = sky("FK5");
        f.setSystem("GALACTIC");
        assertEquals(SkyFrame.AST__GALACTIC, f.systemValue());
    }

    @Test
    void setEpoch() {
        SkyFrame f = sky("FK5");
        f.setEpochMjd(53000.0);
        assertClose(f.getEpochMjd(), 53000.0, TOL);
    }

    @Test
    void setEquinox() {
        SkyFrame f = sky("FK5");
        f.setEquinoxMjd(53000.0);
        assertClose(f.getEquinoxMjd(), 53000.0, TOL);
    }

    @Test
    void setNegLon() {
        SkyFrame f = sky("FK5");
        f.setNegLon(true);
        assertTrue(f.getNegLon());
    }

    @Test
    void setSkyRef() {
        SkyFrame f = sky("FK5");
        setSkyRefLonLat(f, 1.0, 0.5);
        assertClose(f.getSkyRef(f.getLonAxis()), 1.0, TOL);
        assertClose(f.getSkyRef(f.getLatAxis()), 0.5, TOL);
    }

    @Test
    void setSkyRefIs() {
        SkyFrame f = sky("FK5");
        f.setSkyRefIs("Origin");
        assertEquals(SkyFrame.AST__ORIGIN_REF, f.getSkyRefIsValue());
    }

    @Test
    void setSkyRefP() {
        SkyFrame f = sky("FK5");
        setSkyRefPLonLat(f, 1.0, 0.5);
        assertClose(f.getSkyRefP(f.getLonAxis()), 1.0, TOL);
        assertClose(f.getSkyRefP(f.getLatAxis()), 0.5, TOL);
    }

    @Test
    void setSkyTol() {
        SkyFrame f = sky("FK5");
        f.setSkyTol(1.0e-6);
        assertClose(f.getSkyTol(), 1.0e-6, TOL);
    }

    @Test
    void setAlignOffset() {
        SkyFrame f = sky("FK5");
        f.setAlignOffset(true);
        assertTrue(f.getAlignOffset());
    }

    @Test
    void setProjection() {
        SkyFrame f = sky("FK5");
        f.setProjection("TAN");
        assertTrue(f.testProjection());
        assertEquals("TAN", f.getProjection());
    }

    @Test
    void setObserver() {
        SkyFrame f = sky("FK5");
        f.setObsLatValue(0.5);
        f.setObsLonValue(1.0);
        f.setObsAlt(4200.0);
        f.setDtai(37.0);
        f.setDut1(0.3);
        assertTrue(f.testObsLat());
        assertClose(f.getObsLatValue(), 0.5, TOL);
        assertTrue(f.testObsLon());
        assertClose(f.getObsLonValue(), 1.0, TOL);
        assertTrue(f.testObsAlt());
        assertClose(f.getObsAlt(), 4200.0, TOL);
        assertTrue(f.testDtai());
        assertClose(f.getDtai(), 37.0, TOL);
        assertTrue(f.testDut1());
        assertClose(f.getDut1(), 0.3, TOL);
    }

    // Format and unformat

    @Test
    void formatLongitudeHms() {
        // pi/2 is 6h; skyaxis.c GetAxisFormat gives "hms.1" for 7 digits.
        assertEquals("6:00:00.0", sky("FK5").format(1, HALF_PI));
    }

    @Test
    void formatLatitudeDms() {
        // pi/4 is 45 degrees. C astFormat emits no leading '+' for positive sexagesimal values.
        assertEquals("45:00:00", sky("FK5").format(2, Math.PI / 4.0));
    }

    @Test
    void formatBadAxis() {
        SkyFrame f = sky("FK5");
        assertThrows(AstException.class, () -> f.format(0, 0.0));
        assertThrows(AstException.class, () -> f.format(3, 0.0));
    }

    @Test
    void unformatHms() {
        assertClose(sky("FK5").unformat(1, "6:00:00.0"), HALF_PI, 1.0e-6);
    }

    @Test
    void unformatDms() {
        assertClose(sky("FK5").unformat(2, "+45:00:00"), Math.PI / 4.0, 1.0e-6);
    }

    // Distance

    @Test
    void distanceSamePoint() {
        assertClose(sky("FK5").distance(new double[] {1.0, 0.5}, new double[] {1.0, 0.5}), 0.0, TOL);
    }

    @Test
    void distancePoles() {
        double d = sky("FK5").distance(new double[] {0.0, HALF_PI}, new double[] {0.0, -HALF_PI});
        assertClose(d, Math.PI, TOL);
    }

    @Test
    void distanceBadValue() {
        assertEquals(BAD, sky("FK5").distance(new double[] {BAD, 0.0}, new double[] {1.0, 0.5}));
    }

    // resolvePoints (great-circle geometry)

    @Test
    void resolvePointsAlongEquator() {
        // The basis vector runs east along the equator from (0,0) to (0.1,0). The perpendicular
        // from (0.05, 0.02) meets the equator at (0.05, 0), so the parallel component is the
        // longitude arc and the perpendicular component the latitude arc. Per skyframe.c, a
        // positive perpendicular means rotation from the basis vector to the point matches the
        // lon->lat sense, so a northward point is positive.
        double[][] res = sky("FK5")
                .resolvePoints(new double[] {0.0, 0.0}, new double[] {0.1, 0.0}, new double[][] {{0.05}, {0.02}}, 1);
        assertClose(res[0][0], 0.05, 1.0e-6);
        assertClose(res[1][0], 0.02, 1.0e-6);
    }

    @Test
    void resolvePointsDiffersFromPlanar() {
        // At high declination the great-circle perpendicular component is smaller than the planar
        // delta-latitude, because a parallel of latitude is a small circle.
        double lat = 1.2;
        double[][] res = sky("FK5")
                .resolvePoints(
                        new double[] {0.0, lat},
                        new double[] {0.002, lat},
                        new double[][] {{0.001}, {lat + 1.0e-5}},
                        1);
        assertTrue(Math.abs(res[1][0]) < 1.0e-5, "prp=" + res[1][0]);
        assertTrue(Double.isFinite(res[1][0]));
    }

    @Test
    void resolvePointsBadInput() {
        double[][] res = sky("FK5")
                .resolvePoints(new double[] {0.0, 0.0}, new double[] {0.1, 0.0}, new double[][] {{BAD}, {0.02}}, 1);
        assertEquals(BAD, res[0][0]);
        assertEquals(BAD, res[1][0]);
    }

    // Angle

    @Test
    void angleBadValue() {
        double ang = sky("FK5").angle(new double[] {BAD, 0.0}, new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
        assertEquals(BAD, ang);
    }

    @Test
    void angleIsSignedAndMatchesOracle() {
        // Values from C astAngle on an FK5 SkyFrame, given to 12 decimal places: the angle at b is
        // signed, measured from the direction of north (positive towards east).
        SkyFrame f = sky("FK5");
        double[] north = {0.0, 0.1};
        double[] b = {0.0, 0.0};
        double[] east = {0.1, 0.0};
        double[] west = {-0.1, 0.0};
        assertTrue(Math.abs(f.angle(north, b, east) - HALF_PI) < 1e-9);
        assertTrue(Math.abs(f.angle(north, b, west) + HALF_PI) < 1e-9);
        assertTrue(Math.abs(f.angle(east, b, north) + HALF_PI) < 1e-9);

        double[] p = {0.3, 0.4};
        double[] q = {0.25, -0.2};
        double[] t = {0.6, 0.1};
        double got = f.angle(p, q, t);
        assertTrue(Math.abs(got - 0.79603712773) < 1e-9, "got " + got);
    }

    @Test
    void angleCoincidentPointsReturnBad() {
        // C returns AST__BAD when a or c coincides with the vertex b.
        SkyFrame f = sky("FK5");
        double[] b = {0.0, 0.0};
        double[] east = {0.1, 0.0};
        assertEquals(BAD, f.angle(b, b, east));
        assertEquals(BAD, f.angle(east, b, b));
    }

    @Test
    void angleRespectsAxisPermutation() {
        // With the axes swapped (axis 1 is latitude) C applies a pi/2 - result correction
        // (skyframe.c Angle). Values from C via astPermAxes, given to 12 decimal places.
        SkyFrame f = sky("FK5");
        f.permAxes(new int[] {2, 1});
        double[] north = {0.1, 0.0};
        double[] b = {0.0, 0.0};
        double[] east = {0.0, 0.1};
        assertTrue(Math.abs(f.angle(north, b, east)) < 1e-9);

        double[] p = {0.4, 0.3};
        double[] q = {-0.2, 0.25};
        double[] t = {0.1, 0.6};
        double got = f.angle(p, q, t);
        assertTrue(Math.abs(got - 0.774759199065) < 1e-9, "got " + got);
    }

    // SkyTol

    @Test
    void skyTolDefaultIsOneMilliarcsec() {
        // C default: 0.001 arcsec (skyframe.c GetSkyTol).
        assertTrue(Math.abs(sky("FK5").getSkyTol() - 0.001) < 1e-15);
    }

    @Test
    void convertCollapsesChainWithinSkyTol() {
        // C: astConvert(ICRS, FK5 J2000) with SkyTol=100 on both Frames simplifies to a UnitMap
        // (the ~23 mas frame bias is within tolerance); at the default 1 mas it does not.
        SkyFrame icrs = sky("ICRS");
        icrs.setEpoch(2000.0);
        icrs.setSkyTol(100.0);
        SkyFrame fk5 = sky("FK5");
        fk5.setEpoch(2000.0);
        fk5.setSkyTol(100.0);
        FrameSet fs = icrs.convert(fk5, "");
        assertNotNull(fs);
        Mapping map = baseToCurrent(fs).simplify();
        assertTrue(MappingTestData.isUnitChain(map), "expected UnitMap within SkyTol, got " + map.astClass());

        SkyFrame icrs2 = sky("ICRS");
        icrs2.setEpoch(2000.0);
        SkyFrame fk52 = sky("FK5");
        fk52.setEpoch(2000.0);
        FrameSet fs2 = icrs2.convert(fk52, "");
        assertNotNull(fs2);
        Mapping map2 = baseToCurrent(fs2).simplify();
        assertFalse(MappingTestData.isUnitChain(map2), "the 23 mas ICRS-FK5 bias exceeds the default 1 mas SkyTol");
    }

    // Offset

    @Test
    void offsetZeroDistance() {
        double[] result = sky("FK5").offset(new double[] {1.0, 0.5}, new double[] {2.0, 0.3}, 0.0);
        assertClose(result[0], 1.0, TOL);
        assertClose(result[1], 0.5, TOL);
    }

    @Test
    void offsetFullDistance() {
        SkyFrame f = sky("FK5");
        double[] from = {0.0, 0.0};
        double[] to = {HALF_PI, 0.0};
        double sep = f.distance(from, to);
        double[] result = f.offset(from, to, sep);
        assertClose(result[0], HALF_PI, 1.0e-8);
        assertClose(result[1], 0.0, 1.0e-8);
    }

    @Test
    void offsetBadValue() {
        double[] result = sky("FK5").offset(new double[] {BAD, 0.0}, new double[] {1.0, 0.0}, 0.5);
        assertEquals(BAD, result[0]);
        assertEquals(BAD, result[1]);
    }

    // Norm

    @Test
    void normNoOp() {
        double[] coords = {1.0, 0.5};
        sky("FK5").norm(coords);
        assertClose(coords[0], 1.0, TOL);
        assertClose(coords[1], 0.5, TOL);
    }

    @Test
    void normWrapsLongitude() {
        // NegLon defaults to false, so longitude normalises to [0, 2pi].
        double[] coords = {-0.5, 0.3};
        sky("FK5").norm(coords);
        assertClose(coords[0], 2.0 * Math.PI - 0.5, 1.0e-8);
        assertClose(coords[1], 0.3, TOL);
    }

    @Test
    void normNeglonWrapsToMinusPiPi() {
        SkyFrame f = sky("FK5");
        f.setNegLon(true);
        double[] coords = {4.0, 0.3};
        f.norm(coords);
        assertClose(coords[0], Pal.drange(4.0), 1.0e-8);
    }

    @Test
    void normFoldsLatitudeThroughPole() {
        // A latitude above pi/2 folds through the north pole to pi - (pi/2 + 0.1) = pi/2 - 0.1, and
        // the longitude shifts by pi.
        double[] coords = {0.0, HALF_PI + 0.1};
        sky("FK5").norm(coords);
        assertClose(coords[1], HALF_PI - 0.1, 1.0e-8);
        assertClose(coords[0], Math.PI, 1.0e-8);
    }

    @Test
    void normBadValueNoOp() {
        double[] coords = {BAD, 0.5};
        sky("FK5").norm(coords);
        assertEquals(BAD, coords[0]);
        assertClose(coords[1], 0.5, TOL);
    }

    // Convert

    @Test
    void convertFk5ToGalactic() {
        FrameSet fs = sky("FK5").convert(sky("GALACTIC"), "");
        assertNotNull(fs, "FK5 -> Galactic conversion should succeed");
        double[] p = tran(fs, 0.0, 0.0, true);
        assertTrue(Double.isFinite(p[0]));
        assertTrue(Double.isFinite(p[1]));
    }

    @Test
    void convertSkyframeToBlankDomainFrameIsUnitmap() {
        // astConvert(SkyFrame, Frame[Domain=""]) succeeds with a UnitMap: a blank-domain plain
        // Frame matches any Frame (ast_tester/testconvert.c).
        FrameSet fs = sky("FK5").convert(new Frame(2), "");
        assertNotNull(fs, "SkyFrame -> blank-domain Frame converts");
        assertTrue(MappingTestData.isUnitChain(baseToCurrent(fs)), "SkyFrame -> blank-domain Frame is a UnitMap");
    }

    @Test
    void systemString() {
        assertEquals("GALACTIC", sky("GALACTIC").getSystem());
    }

    @Test
    void mapFromSame() {
        // Same System, Equinox and Epoch: the conversion is a UnitMap.
        SkyFrame f = sky("FK5");
        SkyFrame prev = (SkyFrame) f.copy();
        FrameSet fs = prev.convert(f, "");
        assertNotNull(fs);
        assertTrue(MappingTestData.isUnitChain(baseToCurrent(fs).simplify()));
    }

    @Test
    void cloneIsIndependent() {
        SkyFrame f = sky("FK5");
        f.setTitle("original");
        SkyFrame clone = (SkyFrame) f.copy();
        clone.setTitle("clone");
        assertEquals("original", f.getTitle());
        assertEquals("clone", clone.getTitle());
    }

    // Offset coordinates in conversions

    @Test
    void convertWithOriginOffset() {
        // The source Frame has offset coordinates; the destination is standard FK5.
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 1.0, 0.5);
        setSkyRefPLonLat(src, 1.0, 0.5 + 0.1);
        src.setSkyRefIs("Origin");
        FrameSet fs = src.convert(sky("FK5"), "");
        assertNotNull(fs);

        // (0, 0) in offset coordinates maps to SkyRef.
        double[] p = tran(fs, 0.0, 0.0, true);
        assertTrue(lonErr(p[0], 1.0) < 1.0e-10, "lon mismatch: " + lonErr(p[0], 1.0));
        assertClose(p[1], 0.5, 1.0e-10);
    }

    @Test
    void convertToOffsetFrame() {
        // The source is standard FK5; the destination has offset coordinates.
        SkyFrame dst = sky("FK5");
        setSkyRefLonLat(dst, 1.0, 0.5);
        setSkyRefPLonLat(dst, 1.0, 0.5 + 0.1);
        dst.setSkyRefIs("Origin");
        FrameSet fs = sky("FK5").convert(dst, "");
        assertNotNull(fs);

        // SkyRef maps to (0, 0) in offset coordinates; the longitude may come out as 0 or 2pi.
        double[] p = tran(fs, 1.0, 0.5, true);
        assertTrue(lonErr(p[0], 0.0) < 1.0e-10, "lon mismatch: " + lonErr(p[0], 0.0));
        assertClose(p[1], 0.0, 1.0e-10);
    }

    @Test
    void convertBetweenOffsetFramesRoundTrip() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 0.5, 0.3);
        setSkyRefPLonLat(src, 0.5, 0.3 + 0.1);
        src.setSkyRefIs("Origin");
        SkyFrame dst = sky("FK5");
        setSkyRefLonLat(dst, 2.0, -0.4);
        setSkyRefPLonLat(dst, 2.0, -0.4 + 0.1);
        dst.setSkyRefIs("Origin");
        FrameSet fs = src.convert(dst, "");
        assertNotNull(fs);

        double[] mid = tran(fs, 0.1, 0.2, true);
        double[] back = tran(fs, mid[0], mid[1], false);
        assertClose(back[0], 0.1, 1.0e-9);
        assertClose(back[1], 0.2, 1.0e-9);
    }

    @Test
    void mapFromWithOffsets() {
        // The Mapping from an offset Frame to a standard one, which C forms with astConvert.
        SkyFrame prev = sky("FK5");
        setSkyRefLonLat(prev, 1.0, 0.5);
        setSkyRefPLonLat(prev, 1.0, 0.5 + 0.1);
        prev.setSkyRefIs("Origin");
        FrameSet fs = prev.convert(sky("FK5"), "");
        assertNotNull(fs);

        // (0, 0) in prev (offset) maps to SkyRef in the standard system.
        double[] p = tran(baseToCurrent(fs), 0.0, 0.0, true);
        assertTrue(lonErr(p[0], 1.0) < 1.0e-10, "lon mismatch: " + lonErr(p[0], 1.0));
        assertClose(p[1], 0.5, 1.0e-10);
    }

    @Test
    void skyframeSetIdentRoundTrip() {
        SkyFrame f = sky("FK5");
        assertEquals("", f.getIdent());
        f.setIdent("A");
        assertEquals("A", f.getIdent());
        f.setIdent("");
        assertEquals("", f.getIdent());
    }

    // Match, SubFrame and Overlay

    @Test
    void overlayTemplateCopiesSetSystem() {
        // The template has System set to Galactic; the result starts as FK5. After the overlay the
        // result is Galactic.
        SkyFrame template = sky("GALACTIC");
        assertTrue(template.testSystem());
        Frame result = sky("FK5");
        template.overlay(new int[] {0, 1}, result);
        assertEquals(SkyFrame.AST__GALACTIC, result.systemValue());
        // A Title set on the template is copied through.
        template.setTitle("custom");
        Frame r2 = sky("FK5");
        template.overlay(new int[] {0, 1}, r2);
        assertEquals("custom", r2.getTitle());
    }

    @Test
    void overlayTemplateSkipsUnsetAttrs() {
        // A template whose System is unset does not overwrite the target's System.
        SkyFrame template = new SkyFrame();
        assertFalse(template.testSystem());
        Frame result = sky("GALACTIC");
        template.overlay(new int[] {0, 1}, result);
        assertEquals(SkyFrame.AST__GALACTIC, result.systemValue());
    }

    @Test
    void subFrameSkyframeToSkyframe() {
        // An FK5 target with a Galactic template gives a 2-axis Mapping and a Galactic result.
        SkyFrame target = sky("FK5");
        SkyFrame template = sky("GALACTIC");
        Mapping[] map = new Mapping[1];
        Frame[] result = new Frame[1];
        assertTrue(target.subFrame(template, 2, new int[] {0, 1}, new int[] {0, 1}, map, result), "should match");
        assertEquals(2, map[0].getNin());
        assertEquals(2, map[0].getNout());
        assertEquals(SkyFrame.AST__GALACTIC, result[0].systemValue());
    }

    @Test
    void matchTemplateSkyframeSelf() {
        SkyFrame target = sky("FK5");
        SkyFrame template = sky("GALACTIC");
        FrameFinder.MatchResult m = template.match(target, false);
        assertNotNull(m, "should match");
        assertEquals(2, m.templateAxes.length);
        assertEquals(2, m.targetAxes.length);
        assertInstanceOf(SkyFrame.class, m.result);
        assertEquals(SkyFrame.AST__GALACTIC, m.result.systemValue());
    }

    @Test
    void matchTemplateSkyframeSwapBlockedWhenPermuteFalse() {
        // The target has its axes swapped; with Permute false the swap test (skyframe.c Match
        // "match = !swap || astGetPermute(template)") refuses the match.
        SkyFrame target = sky("FK5");
        target.permAxes(new int[] {2, 1});
        SkyFrame template = sky("GALACTIC");
        template.setPermute(false);
        assertFalse(template.getPermute());
        assertNull(template.match(target, false));
    }

    @Test
    void matchTemplateSkyframePermuteDefaultsTrue() {
        // Permute defaults to true (frame.c), which SkyFrame does not override, so an axis-swapped
        // target matches by default.
        SkyFrame template = sky("GALACTIC");
        assertTrue(template.getPermute());
        SkyFrame target = sky("FK5");
        target.permAxes(new int[] {2, 1});
        assertNotNull(template.match(target, false), "axis-swapped SkyFrame matches with the default Permute=true");
    }

    @Test
    void matchTemplateSkyframeSwapAllowedWhenPermuteTrue() {
        SkyFrame target = sky("FK5");
        target.permAxes(new int[] {2, 1});
        SkyFrame template = sky("GALACTIC");
        template.setPermute(true);
        FrameFinder.MatchResult m = template.match(target, false);
        assertNotNull(m, "should match with Permute on");
        assertEquals(2, m.templateAxes.length);
    }

    @Test
    void pickAxesSnapshotSkyframeFull() {
        // Picking both axes in order gives a SkyFrame.
        Frame picked = sky("GALACTIC").pickAxes(2, new int[] {1, 2}, null);
        assertInstanceOf(SkyFrame.class, picked);
        assertEquals(SkyFrame.AST__GALACTIC, picked.systemValue());
    }

    @Test
    void pickAxesSnapshotSkyframePartial() {
        // Picking one axis gives a 1-axis Frame that is no longer a SkyFrame.
        Frame picked = sky("GALACTIC").pickAxes(1, new int[] {2}, null);
        assertEquals(1, picked.getNaxes());
        assertFalse(picked instanceof SkyFrame);
    }

    @Test
    void castSkyframeToBasicFrame() {
        Frame cast = sky("FK5").cast(new Frame(2));
        assertNotNull(cast);
        assertSame(Frame.class, cast.getClass());
        assertEquals(2, cast.getNaxes());
    }

    @Test
    void castSkyframePreservesPerAxisMetadata() {
        // C astCast copies each Axis into the plain Frame, so a per-axis Label and Symbol carry
        // through. The Rust test also checks the Unit the cast Frame reports; that value came from
        // the Rust port, not C, and is not checked here.
        SkyFrame f = sky("GALACTIC");
        f.setLabel(1, "MyLon");
        f.setLabel(2, "MyLat");
        f.setSymbol(1, "ML");
        f.setSymbol(2, "MB");
        Frame out = f.cast(new Frame(2));
        assertNotNull(out);
        assertSame(Frame.class, out.getClass(), "cast result should be a plain Frame");
        assertEquals(2, out.getNaxes());
        assertEquals("MyLon", out.getLabel(1));
        assertEquals("MyLat", out.getLabel(2));
        assertEquals("ML", out.getSymbol(1));
        assertEquals("MB", out.getSymbol(2));
    }
}
