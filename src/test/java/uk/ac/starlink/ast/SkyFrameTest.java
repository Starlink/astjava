package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;
import uk.ac.starlink.ast.testsupport.CValues;

/**
 * Translation of Rust {@code tests/test_skyframe.rs}, itself a subset of C's {@code
 * testskyframe.c}: construction and attributes of a SkyFrame, conversion between sky coordinate
 * systems, spherical geometry, sexagesimal formatting, FrameSets holding a SkyFrame, offset
 * coordinate systems, native serialisation and bad values.
 *
 * <p>Expected values are hand-derived identities, documented C defaults, or values the Rust test
 * records from the C library (the template conversion, the wrapped {@code Offset} longitude, the
 * {@code SkyOffsetMap} transformations from {@code tools/oracle_fixtures/sky_offset_map_probe.c}).
 * Round trips and approximate astronomical positions keep the Rust test's bounds, which are
 * properties rather than C values.
 *
 * <p>{@code skyframe_is_send} (Rust's {@code Send}), {@code debug_formatting} (Rust's {@code Debug})
 * and {@code serialise_skyframe_not_a_mapping} (a Rust {@code AnyObject} that is a Frame but not a
 * Mapping, where in AST every Frame is a Mapping) test Rust-only API and are deferred.
 */
class SkyFrameTest {

    private static final double HALF_PI = Math.PI / 2.0;

    private static final double QUARTER_PI = Math.PI / 4.0;

    private static final double TWO_PI = 2.0 * Math.PI;

    private static final double BAD = AstObject.AST__BAD;

    private static SkyFrame sky(String system) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        return f;
    }

    private static void setSkyRefLonLat(SkyFrame f, double lon, double lat) {
        f.setSkyRef(f.getLonAxis(), lon);
        f.setSkyRef(f.getLatAxis(), lat);
    }

    private static void setSkyRefPLonLat(SkyFrame f, double lon, double lat) {
        f.setSkyRefP(f.getLonAxis(), lon);
        f.setSkyRefP(f.getLatAxis(), lat);
    }

    private static double[] skyRefLonLat(SkyFrame f) {
        return new double[] {f.getSkyRef(f.getLonAxis()), f.getSkyRef(f.getLatAxis())};
    }

    private static double[] skyRefPLonLat(SkyFrame f) {
        return new double[] {f.getSkyRefP(f.getLonAxis()), f.getSkyRefP(f.getLatAxis())};
    }

    /** One point transformed by {@code map}. */
    private static double[] tran(Mapping map, double x, double y, boolean forward) {
        double[][] out = map.tran2(1, new double[] {x}, new double[] {y}, forward);
        return new double[] {out[0][0], out[1][0]};
    }

    /** The conversion FrameSet from {@code from} to {@code to}, which must exist. */
    private static FrameSet convert(Frame from, Frame to) {
        FrameSet fs = from.convert(to, "");
        assertNotNull(fs, "no conversion");
        return fs;
    }

    /** A hand-derived or C value, compared as {@link CValues#cValueEq} compares. */
    private static void assertC(double want, double got, String what) {
        assertTrue(CValues.cValueEq(got, want), what + ": expected " + want + ", got " + got);
    }

    /** An angle compared modulo 2*pi, within libm rounding of {@code want}. */
    private static void assertAngleC(double want, double got, String what) {
        double err = Math.abs(Math.IEEEremainder(got - want, TWO_PI));
        assertTrue(
                err <= CValues.LIBM_ATOL + CValues.LIBM_RTOL * Math.abs(want),
                what + ": expected " + want + ", got " + got);
    }

    /** A property bound: {@code |got - want| < tol}. */
    private static void assertClose(double want, double got, double tol, String what) {
        assertTrue(Math.abs(got - want) < tol, what + ": expected " + want + ", got " + got);
    }

    /** A FrameSet with a 2-D Frame as Base and an FK5 SkyFrame as Current, joined by a ZoomMap. */
    private static FrameSet skyFrameSet(double zoom, String baseDomain) {
        Frame base = new Frame(2);
        if (baseDomain != null) {
            base.setDomain(baseDomain);
        }
        FrameSet fs = new FrameSet(base);
        fs.addFrame(FrameSet.AST__BASE, new ZoomMap(2, zoom), sky("FK5"));
        return fs;
    }

    // ------------------------------------------------------------------
    // Construction and attributes
    // ------------------------------------------------------------------

    @Test
    void constructionFk5Defaults() {
        SkyFrame f = sky("FK5");
        assertEquals(2, f.getNaxes());
        assertEquals(SkyFrame.AST__FK5, f.systemValue());
        assertEquals("SKY", f.getDomain());
        // The default C reports (skyframe.c GetTitle).
        assertFalse(f.testTitle());
        assertEquals("FK5 equatorial coordinates; mean equinox J2000.0", f.getTitle());
        assertFalse(f.getNegLon());
        assertEquals(SkyFrame.AST__IGNORED_REF, f.getSkyRefIsValue());
        assertFalse(f.testProjection());
    }

    @Test
    void constructionEachSystem() {
        String[] names = {
            "FK4",
            "FK4-NO-E",
            "FK5",
            "GAPPT",
            "ECLIPTIC",
            "GALACTIC",
            "SUPERGALACTIC",
            "ICRS",
            "J2000",
            "HELIOECLIPTIC",
            "AZEL"
        };
        int[] codes = {
            SkyFrame.AST__FK4,
            SkyFrame.AST__FK4_NO_E,
            SkyFrame.AST__FK5,
            SkyFrame.AST__GAPPT,
            SkyFrame.AST__ECLIPTIC,
            SkyFrame.AST__GALACTIC,
            SkyFrame.AST__SUPERGALACTIC,
            SkyFrame.AST__ICRS,
            SkyFrame.AST__J2000,
            SkyFrame.AST__HELIOECLIPTIC,
            SkyFrame.AST__AZEL
        };
        for (int i = 0; i < names.length; i++) {
            SkyFrame f = sky(names[i]);
            assertEquals(codes[i], f.systemValue(), "system code for " + names[i]);
            assertEquals(names[i], f.getSystem(), "system name for " + names[i]);
            assertEquals(2, f.getNaxes(), "naxes for " + names[i]);
            assertEquals("SKY", f.getDomain(), "domain for " + names[i]);
        }
    }

    @Test
    void defaultEquinoxFk5() {
        // The FK5 default equinox is J2000.0, MJD 51544.5.
        assertC(51544.5, sky("FK5").getEquinoxMjd(), "FK5 default equinox");
    }

    @Test
    void defaultEquinoxFk4() {
        // The FK4 default equinox is B1950.0.
        SkyFrame f = sky("FK4");
        assertC(Pal.epb2d(1950.0), f.getEquinoxMjd(), "FK4 default equinox");
        assertTrue(Math.abs(f.getEquinoxMjd() - 51544.5) > 1000.0, "FK4 equinox differs from J2000");
    }

    @Test
    void setGetEpoch() {
        SkyFrame f = sky("FK5");
        f.setEpochMjd(53000.0);
        assertC(53000.0, f.getEpochMjd(), "epoch");
    }

    @Test
    void defaultEpochDependsOnSystem() {
        // skyframe.c GetEpoch: B1950.0 for FK4 and FK4-NO-E, J2000.0 otherwise.
        double j2000 = Pal.epj2d(2000.0);
        assertC(51544.5, j2000, "J2000.0");
        assertC(j2000, sky("FK5").getEpochMjd(), "FK5 default epoch");
        assertC(j2000, sky("ICRS").getEpochMjd(), "ICRS default epoch");
        double b1950 = Pal.epb2d(1950.0);
        SkyFrame fk4 = sky("FK4");
        assertC(b1950, fk4.getEpochMjd(), "FK4 default epoch");
        assertC(b1950, sky("FK4-NO-E").getEpochMjd(), "FK4-NO-E default epoch");
        assertTrue(Math.abs(fk4.getEpochMjd() - j2000) > 1000.0);
    }

    @Test
    void defaultBottomTopClampLatitude() {
        // skyframe.c GetBottom and GetTop: the latitude axis is limited to +/-pi/2, the longitude
        // axis is not.
        SkyFrame f = sky("FK5");
        assertEquals(-Double.MAX_VALUE, f.getBottom(1));
        assertEquals(Double.MAX_VALUE, f.getTop(1));
        assertC(-HALF_PI, f.getBottom(2), "lat bottom");
        assertC(HALF_PI, f.getTop(2), "lat top");
    }

    @Test
    void defaultBottomTopFollowAxisPermutation() {
        // After swapping the axes the limits follow the latitude axis.
        SkyFrame f = sky("FK5");
        f.permAxes(new int[] {2, 1});
        assertC(-HALF_PI, f.getBottom(1), "lat bottom (perm)");
        assertC(HALF_PI, f.getTop(1), "lat top (perm)");
        assertEquals(-Double.MAX_VALUE, f.getBottom(2));
        assertEquals(Double.MAX_VALUE, f.getTop(2));
    }

    @Test
    void setGetEquinox() {
        SkyFrame f = sky("FK5");
        f.setEquinoxMjd(55000.0);
        assertC(55000.0, f.getEquinoxMjd(), "equinox");
    }

    @Test
    void setGetNeglon() {
        SkyFrame f = sky("FK5");
        assertFalse(f.getNegLon());
        f.setNegLon(true);
        assertTrue(f.getNegLon());
        f.setNegLon(false);
        assertFalse(f.getNegLon());
    }

    @Test
    void setGetSkyRef() {
        SkyFrame f = sky("FK5");
        setSkyRefLonLat(f, 1.5, 0.75);
        assertC(1.5, skyRefLonLat(f)[0], "sky_ref lon");
        assertC(0.75, skyRefLonLat(f)[1], "sky_ref lat");
    }

    @Test
    void setGetSkyRefIs() {
        SkyFrame f = sky("FK5");
        assertEquals(SkyFrame.AST__IGNORED_REF, f.getSkyRefIsValue());
        assertEquals("Ignored", f.getSkyRefIs());
        f.setSkyRefIs("Origin");
        assertEquals(SkyFrame.AST__ORIGIN_REF, f.getSkyRefIsValue());
        assertEquals("Origin", f.getSkyRefIs());
        f.setSkyRefIs("Pole");
        assertEquals(SkyFrame.AST__POLE_REF, f.getSkyRefIsValue());
        assertEquals("Pole", f.getSkyRefIs());
    }

    @Test
    void setGetProjection() {
        SkyFrame f = sky("FK5");
        assertFalse(f.testProjection());
        f.setProjection("TAN");
        assertTrue(f.testProjection());
        assertEquals("TAN", f.getProjection());
    }

    @Test
    void setGetObserverAttributes() {
        SkyFrame f = sky("FK5");
        assertFalse(f.testObsLat());
        assertFalse(f.testObsLon());
        assertFalse(f.testObsAlt());
        assertFalse(f.testDtai());
        assertFalse(f.testDut1());

        f.setObsLatValue(0.35);
        f.setObsLonValue(-2.1);
        f.setObsAlt(4200.0);
        f.setDtai(37.0);
        f.setDut1(0.27);

        assertTrue(f.testObsLat());
        assertC(0.35, f.getObsLatValue(), "obs_lat");
        assertTrue(f.testObsLon());
        assertC(-2.1, f.getObsLonValue(), "obs_lon");
        assertTrue(f.testObsAlt());
        assertC(4200.0, f.getObsAlt(), "obs_alt");
        assertTrue(f.testDtai());
        assertC(37.0, f.getDtai(), "dtai");
        assertTrue(f.testDut1());
        assertC(0.27, f.getDut1(), "dut1");
    }

    // ------------------------------------------------------------------
    // Conversion between coordinate systems
    // ------------------------------------------------------------------

    @Test
    void fk5ToGalacticCentre() {
        FrameSet fs = convert(sky("FK5"), sky("GALACTIC"));
        // The galactic centre (l=0, b=0) back in FK5 J2000 is near RA 266.4, Dec -28.94 degrees.
        double[] p = tran(fs, 0.0, 0.0, false);
        double raDeg = Math.toDegrees(p[0]) % 360.0;
        if (raDeg < 0.0) {
            raDeg += 360.0;
        }
        double decDeg = Math.toDegrees(p[1]);
        assertTrue(Math.abs(raDeg - 266.4) < 0.5, "RA of galactic centre: " + raDeg);
        assertTrue(Math.abs(decDeg - -28.94) < 0.5, "Dec of galactic centre: " + decDeg);
    }

    @Test
    void fk5ToGalacticForward() {
        FrameSet fs = convert(sky("FK5"), sky("GALACTIC"));
        double[] p = tran(fs, 0.0, 0.0, true);
        assertTrue(Double.isFinite(p[0]) && p[0] != BAD, "galactic l should be finite");
        assertTrue(Double.isFinite(p[1]) && p[1] != BAD, "galactic b should be finite");
    }

    @Test
    void fk5ToIcrsNearIdentity() {
        // ICRS and FK5 J2000 differ by the frame tie, some tens of milliarcseconds.
        FrameSet fs = convert(sky("FK5"), sky("ICRS"));
        double[] p = tran(fs, 2.0, 0.3, true);
        assertTrue(Math.abs(p[0] - 2.0) < 1.0e-4, "FK5->ICRS lon diff too large: " + p[0]);
        assertTrue(Math.abs(p[1] - 0.3) < 1.0e-4, "FK5->ICRS lat diff too large: " + p[1]);
    }

    @Test
    void galacticSupergalacticRoundTrip() {
        FrameSet fs = convert(sky("GALACTIC"), sky("SUPERGALACTIC"));
        double[] sg = tran(fs, 1.5, 0.3, true);
        double[] back = tran(fs, sg[0], sg[1], false);
        assertClose(1.5, back[0], 1.0e-8, "galactic lon round-trip");
        assertClose(0.3, back[1], 1.0e-8, "galactic lat round-trip");
    }

    @Test
    void fk4ToFk5RoundTrip() {
        FrameSet fs = convert(sky("FK4"), sky("FK5"));
        double[] fk5 = tran(fs, 1.0, 0.5, true);
        double[] back = tran(fs, fk5[0], fk5[1], false);
        assertClose(1.0, back[0], 1.0e-8, "FK4->FK5 lon round-trip");
        assertClose(0.5, back[1], 1.0e-8, "FK4->FK5 lat round-trip");
    }

    @Test
    void allSystemsRoundTripViaFk5() {
        String[] systems = {
            "FK4", "FK4-NO-E", "FK5", "GAPPT", "ECLIPTIC", "GALACTIC", "SUPERGALACTIC", "ICRS", "J2000", "HELIOECLIPTIC"
        };
        SkyFrame fk5 = sky("FK5");
        for (String name : systems) {
            FrameSet fs = convert(sky(name), fk5);
            double[] p = tran(fs, 1.0, 0.4, true);
            double[] back = tran(fs, p[0], p[1], false);
            assertClose(1.0, back[0], 1.0e-8, name + " lon rt");
            assertClose(0.4, back[1], 1.0e-8, name + " lat rt");
        }
    }

    @Test
    void convertSkyframeToBlankDomainFrameIsUnitmap() {
        // A plain Frame with no Domain matches any Frame, and the SkyFrame is used as a plain Frame
        // for the alignment (testconvert.c).
        FrameSet fs = convert(sky("FK5"), new Frame(2));
        assertTrue(
                MappingTestData.isUnitChain(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)),
                "SkyFrame -> blank-domain Frame is a UnitMap");
    }

    @Test
    void convertSkyframeAndSkyDomainFrameIsUnitmap() {
        // testconvert.c errors 9-18: a plain Frame with Domain SKY aligns with a SkyFrame both ways
        // with a UnitMap; one with another Domain does not align.
        Frame bf = new Frame(2);
        bf.setDomain("SKY");
        SkyFrame f = sky("FK5");
        FrameSet fs1 = convert(bf, f);
        assertTrue(
                MappingTestData.isUnitChain(fs1.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)),
                "Frame[SKY] -> SkyFrame is a UnitMap");
        FrameSet fs2 = convert(f, bf);
        assertTrue(
                MappingTestData.isUnitChain(fs2.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT)),
                "SkyFrame -> Frame[SKY] is a UnitMap");
        Frame nf = new Frame(2);
        nf.setDomain("NOTSKY");
        assertNull(f.convert(nf, ""), "SkyFrame -> Frame[NOTSKY] does not align");
    }

    @Test
    void convertSameSystemIsIdentity() {
        FrameSet fs = convert(sky("FK5"), sky("FK5"));
        double[] p = tran(fs, 2.5, 0.8, true);
        assertC(2.5, p[0], "identity lon");
        assertC(0.8, p[1], "identity lat");
    }

    // ------------------------------------------------------------------
    // Spherical geometry
    // ------------------------------------------------------------------

    @Test
    void distancePoleToPole() {
        double d = sky("FK5").distance(new double[] {0.0, HALF_PI}, new double[] {0.0, -HALF_PI});
        assertC(Math.PI, d, "pole-to-pole distance");
    }

    @Test
    void distanceEquatorialOneRadian() {
        double d = sky("FK5").distance(new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
        assertC(1.0, d, "equatorial 1 radian");
    }

    @Test
    void distanceSamePointIsZero() {
        double d = sky("FK5").distance(new double[] {1.5, 0.3}, new double[] {1.5, 0.3});
        assertC(0.0, d, "same-point distance");
    }

    @Test
    void distanceEquatorialQuarterCircle() {
        double d = sky("FK5").distance(new double[] {0.0, 0.0}, new double[] {HALF_PI, 0.0});
        assertC(HALF_PI, d, "equatorial pi/2");
    }

    @Test
    void angleReturnsFinite() {
        double angle = sky("FK5").angle(new double[] {0.0, 0.0}, new double[] {0.5, 0.3}, new double[] {1.0, 0.0});
        assertTrue(Double.isFinite(angle) && angle != BAD, "angle should be finite");
        assertTrue(Math.abs(angle) <= Math.PI, "angle should be in [-pi, pi]: " + angle);
    }

    @Test
    void angleCollinearEquatorialPoints() {
        // The bearings from the middle of three equatorial points to the other two are opposite.
        double angle = sky("FK5").angle(new double[] {0.0, 0.0}, new double[] {0.5, 0.0}, new double[] {1.0, 0.0});
        assertC(Math.PI, Math.abs(angle), "collinear equatorial angle");
    }

    @Test
    void offsetEquatorTowardPole() {
        // Walking pi/4 from the equator towards the north pole reaches latitude pi/4.
        double[] p = sky("FK5").offset(new double[] {0.0, 0.0}, new double[] {0.0, HALF_PI}, QUARTER_PI);
        assertAngleC(0.0, p[0], "offset lon");
        assertC(QUARTER_PI, p[1], "offset lat");
    }

    /**
     * C keeps the longitude {@code Offset} returns within 0 to 2*pi, since {@code palDcc2s} does
     * not (skyframe.c {@code Offset}), so walking westward across longitude zero gives a longitude
     * just under 2*pi.
     */
    @Test
    void offsetNormalisesTheLongitudeItReturns() {
        SkyFrame f = sky("FK5");
        double[] p = f.offset(new double[] {0.1, 0.0}, new double[] {0.0, 0.0}, 0.2);
        assertTrue(p[0] >= 0.0 && p[0] < TWO_PI, "offset longitude must lie in 0..2*pi, got " + p[0]);
        // The C value.
        assertC(6.183185307179587, p[0], "offset lon wrapped");

        // The branch for coincident points normalises too. C takes it only when sin(offset) is
        // exactly zero, so for an offset of pi, whose sine is not zero, it returns bad values.
        p = f.offset(new double[] {-0.1, 0.0}, new double[] {-0.1, 0.0}, 0.0);
        assertC(6.183185307179587, p[0], "coincident offset lon wrapped");

        p = f.offset(new double[] {0.1, 0.0}, new double[] {0.1, 0.0}, Math.PI);
        assertEquals(
                Arrays.toString(new double[] {BAD, BAD}),
                Arrays.toString(p),
                "a non-zero offset between coincident points defines no great circle");
    }

    @Test
    void offsetFullDistance() {
        SkyFrame f = sky("FK5");
        double[] from = {0.0, 0.0};
        double[] to = {HALF_PI, 0.0};
        double sep = f.distance(from, to);
        double[] p = f.offset(from, to, sep);
        assertAngleC(HALF_PI, p[0], "offset full lon");
        assertC(0.0, p[1], "offset full lat");
    }

    @Test
    void normLongitudeWrapping() {
        // A longitude beyond 2*pi is brought into 0..2*pi when NegLon is false: 3*pi becomes pi.
        double[] p = {3.0 * Math.PI, 0.3};
        sky("FK5").norm(p);
        assertC(Math.PI, p[0], "norm lon wrapping");
        assertC(0.3, p[1], "norm lat unchanged");
    }

    @Test
    void normLatitudePoleFolding() {
        // A latitude beyond pi/2 folds over the north pole, and the longitude moves by pi.
        double[] p = {0.0, HALF_PI + 0.1};
        sky("FK5").norm(p);
        assertC(HALF_PI - 0.1, p[1], "norm lat folding");
        assertC(Math.PI, p[0], "norm lon shifted by pi");
    }

    @Test
    void normNeglonMode() {
        SkyFrame f = sky("FK5");
        f.setNegLon(true);
        double[] p = {4.0, 0.3};
        f.norm(p);
        assertTrue(p[0] >= -Math.PI && p[0] <= Math.PI, "neglon mode: lon=" + p[0] + " should be in [-pi, pi]");
    }

    @Test
    void normNegativeLatitude() {
        // A latitude below -pi/2 folds over the south pole.
        double[] p = {0.0, -HALF_PI - 0.1};
        sky("FK5").norm(p);
        assertC(-HALF_PI + 0.1, p[1], "norm south pole folding");
    }

    // ------------------------------------------------------------------
    // Sexagesimal formatting
    // ------------------------------------------------------------------

    @Test
    void formatLongitudeZero() {
        String s = sky("FK5").format(1, 0.0);
        assertTrue(s.contains("0:00:00"), "0 rad should format as 0:00:00, got: " + s);
    }

    @Test
    void formatLongitudeSixHours() {
        String s = sky("FK5").format(1, HALF_PI);
        assertTrue(s.contains("6:00:00"), "pi/2 rad should contain 6:00:00, got: " + s);
    }

    @Test
    void formatLatitude45Degrees() {
        // C writes no leading '+' for a positive sexagesimal value.
        String s = sky("FK5").format(2, QUARTER_PI);
        assertTrue(s.contains("45:00:00") && !s.contains("+"), "pi/4 should contain 45:00:00 with no +, got: " + s);
    }

    @Test
    void unformatHms() {
        // 12h30m is 12.5 hours, 12.5 * pi/12 radians.
        double v = sky("FK5").unformat(1, "12:30:00");
        assertC(12.5 * Math.PI / 12.0, v, "unformat 12:30:00");
    }

    @Test
    void formatUnformatRoundTripLongitude() {
        // The formatted text has a precision of a tenth of a second of time.
        SkyFrame f = sky("FK5");
        double original = 1.234;
        double restored = f.unformat(1, f.format(1, original));
        assertClose(original, restored, 1.0e-4, "format/unformat round-trip lon");
    }

    @Test
    void formatUnformatRoundTripLatitude() {
        // The formatted text has a precision of a second of arc.
        SkyFrame f = sky("FK5");
        double original = 0.5;
        double restored = f.unformat(2, f.format(2, original));
        double oneArcsec = Math.toRadians(1.0) / 3600.0;
        assertClose(original, restored, 2.0 * oneArcsec, "format/unformat rt lat");
    }

    @Test
    void formatAxisOutOfRange() {
        SkyFrame f = sky("FK5");
        assertThrows(AstException.class, () -> f.format(0, 0.0), "axis 0 should be rejected");
        assertThrows(AstException.class, () -> f.format(3, 0.0), "axis 3 should be rejected");
    }

    // ------------------------------------------------------------------
    // FrameSets holding a SkyFrame
    // ------------------------------------------------------------------

    @Test
    void framesetWithSkyframeCurrent() {
        FrameSet fs = skyFrameSet(1.0, null);
        assertEquals(2, fs.getNaxes());
        assertEquals(2, fs.getFrame(FrameSet.AST__CURRENT).getNaxes());
        assertEquals("FK5", fs.getFrame(FrameSet.AST__CURRENT).getSystem());
        assertEquals("FK5", fs.getSystem());
        // A ZoomMap with factor 1 leaves the point unchanged.
        double[] p = tran(fs, 1.0, 0.5, true);
        assertC(1.0, p[0], "frameset fwd lon");
        assertC(0.5, p[1], "frameset fwd lat");
    }

    @Test
    void framesetSkyframeFrameMutEscapeHatch() {
        FrameSet fs = skyFrameSet(1.0, null);
        double[] r1 = tran(fs, 1.0, 0.5, true);
        // The FrameSet's own current Frame is the SkyFrame; obtaining it changes nothing.
        assertInstanceOf(SkyFrame.class, fs.getFrame(FrameSet.AST__CURRENT));
        double[] r2 = tran(fs, 1.0, 0.5, true);
        assertEquals(r1[0], r2[0], "lon unchanged");
        assertEquals(r1[1], r2[1], "lat unchanged");
    }

    @Test
    void framesetSkyframeDistanceDelegation() {
        // The FrameSet measures distances in its current Frame, on the sphere.
        FrameSet fs = skyFrameSet(1.0, null);
        double d = fs.distance(new double[] {0.0, HALF_PI}, new double[] {0.0, -HALF_PI});
        assertC(Math.PI, d, "frameset skyframe distance pole-to-pole");
    }

    // ------------------------------------------------------------------
    // Offset coordinate systems
    // ------------------------------------------------------------------

    @Test
    void skyRefIsOriginOffset() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 1.0, 0.5);
        setSkyRefPLonLat(src, 1.0, 0.5 + 0.1);
        src.setSkyRefIs("Origin");
        FrameSet fs = convert(src, sky("FK5"));
        // The offset origin is the SkyRef position.
        double[] p = tran(fs, 0.0, 0.0, true);
        assertAngleC(1.0, p[0], "origin offset lon");
        assertC(0.5, p[1], "origin offset lat");
    }

    @Test
    void skyRefIsOriginRoundTrip() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 2.0, -0.3);
        setSkyRefPLonLat(src, 2.0, -0.3 + 0.1);
        src.setSkyRefIs("Origin");
        FrameSet fs = convert(src, sky("FK5"));
        double[] p = tran(fs, 0.1, 0.2, true);
        double[] back = tran(fs, p[0], p[1], false);
        assertClose(0.1, back[0], 1.0e-9, "origin rt lon");
        assertClose(0.2, back[1], 1.0e-9, "origin rt lat");
    }

    @Test
    void skyRefIsPoleOffset() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 1.5, 0.8);
        setSkyRefPLonLat(src, 0.0, 0.0);
        src.setSkyRefIs("Pole");
        FrameSet fs = convert(src, sky("FK5"));
        // The north pole of the offset system is the SkyRef position.
        double[] p = tran(fs, 0.0, HALF_PI, true);
        assertC(0.8, p[1], "pole offset lat at pole");
    }

    @Test
    void skyRefIsPoleRoundTrip() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 0.5, 1.0);
        setSkyRefPLonLat(src, 0.5, 0.0);
        src.setSkyRefIs("Pole");
        FrameSet fs = convert(src, sky("FK5"));
        double[] p = tran(fs, 0.3, 0.4, true);
        double[] back = tran(fs, p[0], p[1], false);
        assertClose(0.3, back[0], 1.0e-9, "pole rt lon");
        assertClose(0.4, back[1], 1.0e-9, "pole rt lat");
    }

    @Test
    void skyRefIsIgnoredNoEffect() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 1.0, 0.5);
        src.setSkyRefIs("Ignored");
        FrameSet fs = convert(src, sky("FK5"));
        // With SkyRefIs Ignored, FK5 to FK5 leaves the point unchanged.
        double[] p = tran(fs, 2.0, 0.3, true);
        assertC(2.0, p[0], "ignored lon");
        assertC(0.3, p[1], "ignored lat");
    }

    @Test
    void convertBetweenOffsetFrames() {
        SkyFrame src = sky("FK5");
        setSkyRefLonLat(src, 0.5, 0.3);
        setSkyRefPLonLat(src, 0.5, 0.3 + 0.1);
        src.setSkyRefIs("Origin");
        SkyFrame dst = sky("FK5");
        setSkyRefLonLat(dst, 2.0, -0.4);
        setSkyRefPLonLat(dst, 2.0, -0.4 + 0.1);
        dst.setSkyRefIs("Origin");
        FrameSet fs = convert(src, dst);
        double[] mid = tran(fs, 0.1, 0.2, true);
        double[] back = tran(fs, mid[0], mid[1], false);
        assertClose(0.1, back[0], 1.0e-9, "offset-offset rt lon");
        assertClose(0.2, back[1], 1.0e-9, "offset-offset rt lat");
    }

    // ------------------------------------------------------------------
    // Native serialisation
    // ------------------------------------------------------------------

    @Test
    void serialiseDefaultFk5() {
        String text = ChannelText.dump(sky("FK5"));
        assertTrue(text.contains("Begin SkyFrame"), "missing Begin SkyFrame");
        assertTrue(text.contains("IsA Frame"), "missing IsA Frame");
        assertTrue(text.contains("End SkyFrame"), "missing End SkyFrame");
        assertTrue(text.contains("FK5"), "missing system name");

        AstObject restored = ChannelText.read(text);
        assertInstanceOf(SkyFrame.class, restored, "expected a SkyFrame");
        SkyFrame f = (SkyFrame) restored;
        assertEquals(SkyFrame.AST__FK5, f.systemValue());
        assertEquals(2, f.getNaxes());
        assertEquals("SKY", f.getDomain());
        assertFalse(f.getNegLon());
        assertEquals(SkyFrame.AST__IGNORED_REF, f.getSkyRefIsValue());
    }

    @Test
    void serialisePreservesEquinox() {
        SkyFrame original = sky("FK5");
        double j2010Mjd = 55197.0;
        original.setEquinoxMjd(j2010Mjd);
        String text = ChannelText.dump(original);
        assertTrue(text.contains("Eqnox"), "missing Eqnox");
        SkyFrame f = (SkyFrame) ChannelText.read(text);
        // The dump holds the equinox as a Julian or Besselian year.
        assertClose(j2010Mjd, f.getEquinoxMjd(), 1.0e-6, "equinox round-trip");
    }

    @Test
    void serialisePreservesSkyRef() {
        SkyFrame original = sky("FK5");
        original.setSkyRefIs("Origin");
        setSkyRefLonLat(original, 1.5, 0.75);
        setSkyRefPLonLat(original, 0.3, 1.2);
        String text = ChannelText.dump(original);
        assertTrue(text.contains("SRefIs"), "missing SRefIs");
        assertTrue(text.contains("SRef1"), "missing SRef1");
        assertTrue(text.contains("SRef2"), "missing SRef2");

        SkyFrame f = (SkyFrame) ChannelText.read(text);
        assertEquals(SkyFrame.AST__ORIGIN_REF, f.getSkyRefIsValue());
        assertClose(1.5, skyRefLonLat(f)[0], 1.0e-10, "sky_ref lon rt");
        assertClose(0.75, skyRefLonLat(f)[1], 1.0e-10, "sky_ref lat rt");
        assertClose(0.3, skyRefPLonLat(f)[0], 1.0e-10, "sky_ref_p lon rt");
        assertClose(1.2, skyRefPLonLat(f)[1], 1.0e-10, "sky_ref_p lat rt");
    }

    @Test
    void serialisePreservesAllAttributes() {
        SkyFrame original = sky("GALACTIC");
        original.setTitle("Test Sky");
        original.setDomain("CUSTOM");
        original.setEpochMjd(53000.0);
        original.setNegLon(true);
        original.setSkyTol(1.0e-7);
        original.setProjection("SIN");
        original.setAlignOffset(true);
        original.setObsLatValue(0.5);
        original.setObsLonValue(-1.5);
        original.setObsAlt(2800.0);
        original.setDtai(37.0);
        original.setDut1(0.3);

        SkyFrame f = (SkyFrame) ChannelText.read(ChannelText.dump(original));
        assertEquals(SkyFrame.AST__GALACTIC, f.systemValue());
        assertEquals("Test Sky", f.getTitle());
        assertEquals("CUSTOM", f.getDomain());
        // The dump holds the epoch as a year.
        assertClose(53000.0, f.getEpochMjd(), 1.0e-10, "epoch rt");
        assertTrue(f.getNegLon());
        assertClose(1.0e-7, f.getSkyTol(), 1.0e-15, "sky_tol rt");
        assertEquals("SIN", f.getProjection());
        assertTrue(f.getAlignOffset());
        assertTrue(f.testObsLat());
        assertClose(0.5, f.getObsLatValue(), 1.0e-10, "obs_lat rt");
        assertTrue(f.testObsLon());
        assertClose(-1.5, f.getObsLonValue(), 1.0e-10, "obs_lon rt");
        assertTrue(f.testObsAlt());
        assertClose(2800.0, f.getObsAlt(), 1.0e-10, "obs_alt rt");
        assertTrue(f.testDtai());
        assertClose(37.0, f.getDtai(), 1.0e-10, "dtai rt");
        assertTrue(f.testDut1());
        assertClose(0.3, f.getDut1(), 1.0e-10, "dut1 rt");
    }

    @Test
    void doubleRoundTripSkyframe() {
        SkyFrame original = sky("FK5");
        original.setTitle("Double RT");
        original.setEpochMjd(53000.0);
        original.setEquinoxMjd(51544.5);
        original.setNegLon(true);
        original.setSkyRefIs("Pole");
        setSkyRefLonLat(original, 0.5, 0.25);
        setSkyRefPLonLat(original, 0.1, 1.0);
        original.setSkyTol(1.0e-8);
        original.setProjection("TAN");

        String text1 = ChannelText.dump(original);
        String text2 = ChannelText.dump(ChannelText.read(text1));
        assertEquals(text1, text2, "double round-trip text mismatch");
    }

    @Test
    void serialiseSkyframeGeometryPreserved() {
        Frame f = (Frame) ChannelText.read(ChannelText.dump(sky("FK5")));
        double d = f.distance(new double[] {0.0, HALF_PI}, new double[] {0.0, -HALF_PI});
        assertC(Math.PI, d, "serialised distance");
    }

    @Test
    void serialiseFramesetWithSkyframe() {
        FrameSet fs = skyFrameSet(0.01, "PIXEL");
        String text = ChannelText.dump(fs);
        assertTrue(text.contains("Begin SkyFrame"), "missing SkyFrame in FrameSet");
        Mapping map = (Mapping) ChannelText.read(text);
        double[] p = tran(map, 100.0, 200.0, true);
        assertC(1.0, p[0], "fs skyframe x");
        assertC(2.0, p[1], "fs skyframe y");
    }

    // ------------------------------------------------------------------
    // Bad values
    // ------------------------------------------------------------------

    @Test
    void distanceWithBadInput() {
        double d = sky("FK5").distance(new double[] {BAD, 0.0}, new double[] {1.0, 0.5});
        assertEquals(BAD, d, "distance with bad input should return AST__BAD");
    }

    @Test
    void distanceWithBadSecondPoint() {
        double d = sky("FK5").distance(new double[] {1.0, 0.5}, new double[] {0.0, BAD});
        assertEquals(BAD, d, "distance with bad input b should return AST__BAD");
    }

    @Test
    void formatBadValue() {
        SkyFrame f = sky("FK5");
        assertEquals("<bad>", f.format(1, BAD), "format axis 1 AST__BAD");
        assertEquals("<bad>", f.format(2, BAD), "format axis 2 AST__BAD");
    }

    @Test
    void angleWithBadInput() {
        double a = sky("FK5").angle(new double[] {BAD, 0.0}, new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
        assertEquals(BAD, a, "angle with bad input should return AST__BAD");
    }

    @Test
    void offsetWithBadInput() {
        double[] p = sky("FK5").offset(new double[] {BAD, 0.0}, new double[] {1.0, 0.0}, 0.5);
        assertEquals(BAD, p[0], "offset with bad from should return AST__BAD");
        assertEquals(BAD, p[1]);
    }

    @Test
    void offsetWithBadDistance() {
        double[] p = sky("FK5").offset(new double[] {0.0, 0.0}, new double[] {1.0, 0.0}, BAD);
        assertEquals(BAD, p[0], "offset with bad distance should return AST__BAD");
        assertEquals(BAD, p[1]);
    }

    @Test
    void normBadValueNoOp() {
        double[] p = {BAD, 0.5};
        sky("FK5").norm(p);
        assertEquals(BAD, p[0], "norm should not touch a bad longitude");
        assertEquals(0.5, p[1], "norm should not touch the latitude when the longitude is bad");
    }

    // ------------------------------------------------------------------
    // Other cases
    // ------------------------------------------------------------------

    @Test
    void cloneIsIndependent() {
        SkyFrame f = sky("FK5");
        f.setTitle("original");
        f.setNegLon(true);
        SkyFrame copy = (SkyFrame) f.copy();
        copy.setTitle("clone");
        copy.setNegLon(false);
        assertEquals("original", f.getTitle());
        assertTrue(f.getNegLon());
        assertEquals("clone", copy.getTitle());
        assertFalse(copy.getNegLon());
    }

    @Test
    void systemString() {
        assertEquals("GALACTIC", sky("GALACTIC").getSystem());
        assertEquals("FK5", sky("FK5").getSystem());
    }

    @Test
    void convertMultiPoint() {
        FrameSet fs = convert(sky("FK5"), sky("GALACTIC"));
        double[][] out = fs.tran2(3, new double[] {0.0, Math.PI, 0.0}, new double[] {0.0, 0.0, QUARTER_PI}, true);
        for (int i = 0; i < 3; i++) {
            assertTrue(Double.isFinite(out[0][i]) && out[0][i] != BAD, "point " + i + " lon should be finite");
            assertTrue(Double.isFinite(out[1][i]) && out[1][i] != BAD, "point " + i + " lat should be finite");
        }
    }

    @Test
    void eclipticVernalEquinox() {
        // Ecliptic (0, 0) is the vernal equinox, FK5 (0, 0), to the 1e-6 radians the Rust test
        // allows (the conversion aligns in ICRS). The longitude is compared as an angle, since C
        // brings it into 0..2*pi (palDranrm), where a value just below zero becomes nearly 2*pi.
        FrameSet fs = convert(sky("ECLIPTIC"), sky("FK5"));
        double[] p = tran(fs, 0.0, 0.0, true);
        double ra = p[0] > Math.PI ? p[0] - 2 * Math.PI : p[0];
        assertClose(0.0, ra, 1.0e-6, "vernal equinox RA");
        assertClose(0.0, p[1], 1.0e-6, "vernal equinox Dec");
    }

    @Test
    void galacticNorthPoleInFk5() {
        // The north galactic pole is near RA 192.86, Dec 27.13 degrees in FK5 J2000.
        FrameSet fs = convert(sky("GALACTIC"), sky("FK5"));
        double[] p = tran(fs, 0.0, HALF_PI, true);
        double raDeg = Math.toDegrees(p[0]) % 360.0;
        if (raDeg < 0.0) {
            raDeg += 360.0;
        }
        double decDeg = Math.toDegrees(p[1]);
        assertTrue(Math.abs(raDeg - 192.86) < 0.5, "NGP RA should be ~192.86 deg, got " + raDeg);
        assertTrue(Math.abs(decDeg - 27.13) < 0.5, "NGP Dec should be ~27.13 deg, got " + decDeg);
    }

    /**
     * A template SkyFrame with System ICRS converts a Galactic target to ICRS; one with no System
     * set keeps the target's. The values are the C library's for the same calls.
     */
    @Test
    void anIcrsTemplateConvertsAsTheCLibraryDoes() {
        SkyFrame target = sky("GALACTIC");
        SkyFrame[] templates = {sky("ICRS"), new SkyFrame()};
        String[] systems = {"ICRS", "GALACTIC"};
        double[][] want = {{4.620574201798786, 0.5714018887702035}, {1.0, 0.5}};
        for (int i = 0; i < templates.length; i++) {
            FrameSet fs = target.findFrame(templates[i], "");
            assertNotNull(fs, "template System " + systems[i]);
            assertEquals(systems[i], fs.getFrame(FrameSet.AST__CURRENT).getSystem());
            Mapping map = fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT);
            CValues.assertCValues(tran(map, 1.0, 0.5, true), want[i], "template System " + systems[i]);
        }
    }

    /**
     * testskyframe.c Errors 0-4: two FK5 SkyFrames whose epochs differ by less than SkyTol
     * (default 0.001 arcsec of resulting shift) convert with a UnitMap; a smaller SkyTol, or a
     * larger epoch difference, does not.
     */
    @Test
    void anEpochChangeWithinSkytolConvertsWithAUnitmap() {
        SkyFrame sf1 = sky("FK5");
        sf1.setEpoch(2015.0);
        SkyFrame sf2 = sky("FK5");
        sf2.setEpoch(2015.1);
        assertTrue(convertsWithUnitMap(sf1, sf2), "Error 1");
        assertEquals(0.001, sf1.getSkyTol(), "Error 2");
        sf2.setSkyTol(1.0e-6);
        assertFalse(convertsWithUnitMap(sf1, sf2), "Error 3");
        SkyFrame sf3 = sky("FK5");
        sf3.setEpoch(2016.6);
        assertFalse(convertsWithUnitMap(sf1, sf3), "Error 4");
    }

    private static boolean convertsWithUnitMap(SkyFrame a, SkyFrame b) {
        FrameSet fs = a.convert(b, "SKY");
        assertNotNull(fs, "FK5 SkyFrames convert");
        return MappingTestData.isUnitChain(fs.getMapping(FrameSet.AST__BASE, FrameSet.AST__CURRENT));
    }

    /**
     * testskyframe.c Errors 5-6: AxNorm on the longitude axis, with oper 0 (each value into 0..2*pi)
     * and oper 1 (all values into one range, here all moved down by 2*pi together).
     */
    @Test
    void axNormOnTheLongitudeAxisMatchesC() {
        SkyFrame sf1 = sky("FK5");
        sf1.setEpoch(2015.0);
        double[] vals = {6.1, 6.15, 6.2, 6.25, 6.3};
        sf1.axNorm(1, 0, vals);
        assertEquals(
                Arrays.toString(new double[] {6.1, 6.15, 6.2, 6.25, 6.3 - TWO_PI}), Arrays.toString(vals), "Error 5");
        sf1.axNorm(1, 1, vals);
        double[] x = {6.1, 6.15, 6.2, 6.25, 6.3};
        for (int i = 0; i < x.length; i++) {
            // The tolerance is testskyframe.c's.
            assertTrue(Math.abs(vals[i] - (x[i] - TWO_PI)) <= 1.0e-10, "Error 6: " + Arrays.toString(vals));
        }
    }

    // ------------------------------------------------------------------
    // SkyOffsetMap
    // ------------------------------------------------------------------

    /**
     * {@code skyOffsetMap} gives the Mapping C's {@code astSkyOffsetMap} gives: a UnitMap without
     * an offset coordinate system, and otherwise a CmpMap whose transformations match the C
     * library's. The values are from {@code tools/oracle_fixtures/sky_offset_map_probe.c}.
     */
    @Test
    void skyOffsetMapFollowsTheCLibrary() {
        double[][] input = {{1.0, 0.5}, {1.2, -0.3}, {4.0, 1.1}};
        double[][] origin = {
            {-1.1102230246251563e-16, 0.0},
            {0.2721869183958761, -0.7869827008101172},
            {1.0925418109024947, 1.4986324283930401}
        };
        double[][] originInv = {
            {2.323715150812643, 0.7050341643360052},
            {2.1069058376653653, -0.09351516272737756},
            {-1.6784670157476533, 0.694450189816252}
        };
        Object[][] cases = {
            {"ignored", new double[][] {{1, 1.0}, {2, 0.5}}, new double[0][], "Ignored", false, true, input, input},
            {"unset", new double[0][], new double[0][], "Origin", false, true, input, input},
            {"origin", new double[][] {{1, 1.0}, {2, 0.5}}, new double[0][], "Origin", false, false, origin, originInv},
            {
                "origin_refp",
                new double[][] {{1, 1.0}, {2, 0.5}},
                new double[][] {{1, 0.2}, {2, 1.2}},
                "Origin",
                false,
                false,
                new double[][] {
                    {5.5511151231257815e-17, 1.1102230246251563e-16},
                    {-0.10210991864465951, -0.8181738903649057},
                    {1.4897499824922824, 1.1484701853536217}
                },
                new double[][] {
                    {2.4249301423352168, 1.0122189985077705},
                    {2.2733863215807153, 0.19733240519296935},
                    {-1.331861624286645, 0.5067829250214343}
                }
            },
            {
                "pole",
                new double[][] {{1, 1.0}, {2, 0.5}},
                new double[0][],
                "Pole",
                false,
                false,
                new double[][] {
                    {0.0, 1.5707963267948966},
                    {-2.879757185700063, 0.7477555350298881},
                    {-0.06409062726198048, 0.033189282448934675}
                },
                new double[][] {
                    {-0.31463876459691453, 0.7022845055472241},
                    {-1.016407391025914, 0.16283461017645623},
                    {1.3556251391076843, 0.16786011926334668}
                }
            },
            {
                "origin_lat_only",
                new double[][] {{2, 0.5}},
                new double[0][],
                "Origin",
                false,
                false,
                new double[][] {
                    {0.8521116382803207, 0.19463774791986274},
                    {1.3907003322727278, -0.4393019920225063},
                    {-1.1178516922472144, 1.179074360848845}
                },
                new double[][] {
                    {1.323715150812643, 0.705034164336005},
                    {1.1069058376653655, -0.09351516272737764},
                    {-2.6784670157476533, 0.6944501898162521}
                }
            },
            // SkyRef is set before the axes are swapped, so it is the same position as "origin",
            // and the Mapping, which works in (longitude, latitude) order, is unchanged by the swap.
            {
                "permuted_origin",
                new double[][] {{1, 1.0}, {2, 0.5}},
                new double[0][],
                "Origin",
                true,
                false,
                origin,
                originInv
            },
        };
        double[] xin = new double[input.length];
        double[] yin = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            xin[i] = input[i][0];
            yin[i] = input[i][1];
        }
        for (Object[] c : cases) {
            String name = (String) c[0];
            SkyFrame f = sky("ICRS");
            for (double[] r : (double[][]) c[1]) {
                f.setSkyRef((int) r[0], r[1]);
            }
            for (double[] r : (double[][]) c[2]) {
                f.setSkyRefP((int) r[0], r[1]);
            }
            f.setSkyRefIs((String) c[3]);
            if ((Boolean) c[4]) {
                f.permAxes(new int[] {2, 1});
            }
            Mapping map = f.skyOffsetMap();
            if ((Boolean) c[5]) {
                assertInstanceOf(UnitMap.class, map, name + ": a UnitMap");
            } else {
                assertInstanceOf(CmpMap.class, map, name + ": a CmpMap");
            }
            boolean[] directions = {true, false};
            double[][][] wants = {(double[][]) c[6], (double[][]) c[7]};
            for (int d = 0; d < 2; d++) {
                double[][] out = map.tran2(input.length, xin, yin, directions[d]);
                for (int i = 0; i < input.length; i++) {
                    CValues.assertCValues(
                            new double[] {out[0][i], out[1][i]},
                            wants[d][i],
                            name + " forward=" + directions[d] + " point " + i);
                }
            }
        }

        // A SkyRefP that coincides with SkyRef defines no orientation.
        SkyFrame f = sky("ICRS");
        double[] v = {1.0, 0.5};
        for (int axis = 1; axis <= 2; axis++) {
            f.setSkyRef(axis, v[axis - 1]);
            f.setSkyRefP(axis, v[axis - 1]);
        }
        f.setSkyRefIs("Origin");
        AstException e = assertThrows(AstException.class, f::skyOffsetMap);
        assertEquals(AstException.AST__BADOC, e.getStatus());
    }

    /**
     * System and SkyRefIs names match as C's {@code astChrMatch} matches them: case is ignored, and
     * so is white space after the name, but not before it or within it. C's {@code SystemCode}
     * reads the System name, and {@code SetAttrib} (skyframe.c), which takes the SkyRefIs value
     * from the character after the '=', reads the SkyRefIs name.
     */
    @Test
    void skyNamesIgnoreTrailingWhiteSpace() {
        SkyFrame f = new SkyFrame();
        assertEquals(SkyFrame.AST__FK5, f.systemCode("FK5 "));
        assertEquals(SkyFrame.AST__GALACTIC, f.systemCode("galactic\t\n"));
        assertEquals(SkyFrame.AST__FK4_NO_E, f.systemCode("fk4-no-e\u000b\f\r"));
        assertEquals(Frame.AST__BADSYSTEM, f.systemCode(" FK5"));
        assertEquals(Frame.AST__BADSYSTEM, f.systemCode("FK5 x"));
        f.setAttrib("skyrefis", "pole  ");
        assertEquals(SkyFrame.AST__POLE_REF, f.getSkyRefIsValue());
        AstException e = assertThrows(AstException.class, () -> f.setAttrib("skyrefis", " Pole"));
        assertEquals(AstException.AST__OPT, e.getStatus());
    }
}
