package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * The unit tests of Rust {@code src/frames/sky_convert.rs}.
 *
 * <p>Rust's {@code SkySystem::parse}/{@code as_str} and {@code SkyRefIs::parse}/{@code as_str} are
 * tested through the System and SkyRefIs attributes (C's {@code SystemCode}/{@code SystemString}
 * and {@code SetAttrib}/{@code GetAttrib}). Rust's {@code make_sky_mapping} takes the System,
 * Equinox, Epoch, observer latitude, LAST and diurnal aberration of each end as values, where C's
 * {@code MakeSkyMapping} ({@link SkyConvert#makeSkyMapping}) takes two SkyFrames and computes the
 * LAST and diurnal aberration from their Epoch and observer; the tests build SkyFrames with the
 * same System, Equinox, Epoch and observer latitude, and test properties that hold whatever LAST
 * C computes. Rust's {@code needs_equinox} and {@code needs_epoch} are tested through whether a
 * difference in Equinox or Epoch alone changes the conversion; for {@code needs_epoch} only AZEL
 * and Unknown are tested, since the epoch dependence of FK5 and Galactic (through the FK5 to ICRS
 * spin) is below any bound the Rust test documents. Rust's {@code make_offset_mapping} is tested
 * through {@link SkyFrame#skyOffsetMap}, whose forward transformation runs from standard to offset
 * coordinates, the inverse of Rust's.
 */
class SkyConvertModuleTest {

    private static final double TOL = 1.0e-8;
    private static final double BAD = AstObject.AST__BAD;
    private static final double HALF_PI = Math.PI / 2;
    private static final double J2000 = Pal.epj2d(2000.0);

    private static final String[] NAMES = {
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
        "AZEL",
        "Unknown"
    };

    private static final int[] CODES = {
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
        SkyFrame.AST__AZEL,
        SkyFrame.AST__UNKNOWN
    };

    private static void assertClose(double a, double b, double tol) {
        assertTrue(Math.abs(a - b) < tol, "expected " + a + " ~= " + b + " (diff = " + Math.abs(a - b) + ")");
    }

    private static double remEuclid(double x, double m) {
        return x - m * Math.floor(x / m);
    }

    /** The difference between two longitudes, wrapped into [0, pi]. */
    private static double lonErr(double a, double b) {
        double twoPi = 2.0 * Math.PI;
        double d = remEuclid(a - b, twoPi);
        return Math.min(d, twoPi - d);
    }

    /** A SkyFrame with the given System, Equinox and Epoch (MJD). */
    private static SkyFrame sky(String system, double equinoxMjd, double epochMjd) {
        SkyFrame f = new SkyFrame();
        f.setSystem(system);
        f.setEquinoxMjd(equinoxMjd);
        f.setEpochMjd(epochMjd);
        return f;
    }

    private static SkyFrame sky(String system) {
        return sky(system, J2000, J2000);
    }

    private static Mapping skyMapping(SkyFrame target, SkyFrame result, int alignSys) {
        Mapping map = SkyConvert.makeSkyMapping(target, result, alignSys);
        assertNotNull(map, "conversion exists");
        return map;
    }

    private static double[] fwd(Mapping m, double lon, double lat) {
        double[][] out = m.tran2(1, new double[] {lon}, new double[] {lat}, true);
        return new double[] {out[0][0], out[1][0]};
    }

    private static double[] inv(Mapping m, double lon, double lat) {
        double[][] out = m.tran2(1, new double[] {lon}, new double[] {lat}, false);
        return new double[] {out[0][0], out[1][0]};
    }

    /** An FK5 SkyFrame defining an offset coordinate system. */
    private static SkyFrame offsetFrame(double[] skyRef, double[] skyRefP, String skyRefIs) {
        SkyFrame f = new SkyFrame();
        f.setSystem("FK5");
        f.setSkyRef(f.getLonAxis(), skyRef[0]);
        f.setSkyRef(f.getLatAxis(), skyRef[1]);
        f.setSkyRefP(f.getLonAxis(), skyRefP[0]);
        f.setSkyRefP(f.getLatAxis(), skyRefP[1]);
        f.setSkyRefIs(skyRefIs);
        return f;
    }

    // System names

    @Test
    void skySystemAsStrParseRoundTrip() {
        for (int i = 0; i < NAMES.length; i++) {
            SkyFrame f = new SkyFrame();
            f.setSystemValue(CODES[i]);
            String s = f.getSystem();
            assertEquals(NAMES[i], s);
            SkyFrame g = new SkyFrame();
            g.setSystem(s);
            assertEquals(CODES[i], g.systemValue(), "round trip failed for " + s);
        }
    }

    private static int parse(String name) {
        SkyFrame f = new SkyFrame();
        f.setSystem(name);
        return f.systemValue();
    }

    private static void assertNotASystem(String name) {
        AstException e = assertThrows(AstException.class, () -> new SkyFrame().setSystem(name));
        assertEquals(AstException.AST__ATTIN, e.getStatus());
    }

    @Test
    void skySystemParseCaseInsensitive() {
        assertEquals(SkyFrame.AST__FK5, parse("fk5"));
        assertEquals(SkyFrame.AST__GALACTIC, parse("Galactic"));
        assertEquals(SkyFrame.AST__FK4_NO_E, parse("FK4-NO-E"));
        assertEquals(SkyFrame.AST__FK4_NO_E, parse("FK4_NO_E"));
        assertNotASystem("nonsense");
    }

    /**
     * C's {@code SystemCode} (skyframe.c) also accepts "Equatorial" for FK5 and "GEOCENTRIC" or
     * "APPARENT" for GAPPT, ignoring case, as whole names.
     */
    @Test
    void skySystemParseAcceptsCAlternativeNames() {
        assertEquals(SkyFrame.AST__FK5, parse("Equatorial"));
        assertEquals(SkyFrame.AST__GAPPT, parse("GEOCENTRIC"));
        assertEquals(SkyFrame.AST__GAPPT, parse("apparent"));
        assertNotASystem("Equat");
    }

    @Test
    void skySystemNeedsEquinox() {
        // A difference in Equinox alone changes the conversion for the systems that use it, and
        // leaves it the identity for those that do not.
        double eq2050 = Pal.epj2d(2050.0);
        String[] needs = {"FK4", "FK4-NO-E", "FK5", "ECLIPTIC"};
        for (String sys : needs) {
            Mapping map = skyMapping(sky(sys, J2000, J2000), sky(sys, eq2050, J2000), SkyFrame.AST__ICRS);
            double[] out = fwd(map, 1.0, 0.5);
            assertTrue(Math.abs(out[0] - 1.0) > 1.0e-6 || Math.abs(out[1] - 0.5) > 1.0e-6, sys + " uses Equinox");
        }
        String[] ignores = {"GALACTIC", "ICRS", "AZEL"};
        for (String sys : ignores) {
            Mapping map = skyMapping(sky(sys, J2000, J2000), sky(sys, eq2050, J2000), SkyFrame.AST__ICRS);
            double[] out = fwd(map, 1.0, 0.5);
            assertClose(out[0], 1.0, TOL);
            assertClose(out[1], 0.5, TOL);
        }
    }

    @Test
    void skySystemNeedsEpoch() {
        // AZEL depends on Epoch through the LAST: a quarter of a day turns the sky by about 90
        // degrees. Two Unknown systems are taken to be the same (skyframe.c MakeSkyMapping).
        double later = J2000 + 0.25;
        Mapping azel = skyMapping(sky("AZEL", J2000, J2000), sky("AZEL", J2000, later), SkyFrame.AST__ICRS);
        double[] out = fwd(azel, 1.0, 0.5);
        assertTrue(Math.abs(out[0] - 1.0) > 1.0e-6 || Math.abs(out[1] - 0.5) > 1.0e-6, "AZEL uses Epoch");
        Mapping unknown = skyMapping(sky("Unknown", J2000, J2000), sky("Unknown", J2000, later), SkyFrame.AST__FK5);
        assertInstanceOf(UnitMap.class, unknown);
    }

    @Test
    void skySystemDefaultEquinox() {
        // The FK5 default equinox is J2000.0, the FK4 default B1950.0.
        SkyFrame fk5 = new SkyFrame();
        fk5.setSystem("FK5");
        assertClose(fk5.getEquinoxMjd(), J2000, 1.0e-6);
        SkyFrame fk4 = new SkyFrame();
        fk4.setSystem("FK4");
        assertClose(fk4.getEquinoxMjd(), Pal.epb2d(1950.0), 1.0e-6);
    }

    // SkyRefIs

    @Test
    void skyRefIsRoundTrip() {
        for (String s : new String[] {"Origin", "Pole", "Ignored"}) {
            SkyFrame f = new SkyFrame();
            f.setSkyRefIs(s);
            assertEquals(s, f.getSkyRefIs(), "round trip failed for " + s);
        }
    }

    @Test
    void skyRefIsParseCaseInsensitive() {
        SkyFrame f = new SkyFrame();
        f.setSkyRefIs("origin");
        assertEquals(SkyFrame.AST__ORIGIN_REF, f.getSkyRefIsValue());
        f.setSkyRefIs("POLE");
        assertEquals(SkyFrame.AST__POLE_REF, f.getSkyRefIsValue());
        f.setSkyRefIs("Ignored");
        assertEquals(SkyFrame.AST__IGNORED_REF, f.getSkyRefIsValue());
        // skyframe.c SetAttrib reports an unknown option.
        AstException e = assertThrows(AstException.class, () -> f.setSkyRefIs("bad"));
        assertEquals(AstException.AST__OPT, e.getStatus());
    }

    // FK5 to Galactic

    @Test
    void fk5ToGalactic() {
        // The galactic centre (l=0, b=0) is at about RA 266.405 deg, Dec -28.936 deg in FK5
        // J2000.0 (the published position from the IAU 1958 definition), to 0.01 deg.
        Mapping map = skyMapping(sky("GALACTIC"), sky("FK5"), SkyFrame.AST__FK5);
        double[] p = fwd(map, 0.0, 0.0);
        assertClose(remEuclid(Math.toDegrees(p[0]), 360.0), 266.405, 0.01);
        assertClose(Math.toDegrees(p[1]), -28.936, 0.01);
    }

    @Test
    void fk5ToFk5Same() {
        Mapping map = skyMapping(sky("FK5"), sky("FK5"), SkyFrame.AST__FK5);
        assertTrue(MappingTestData.isUnitChain(map));
        double[] p = fwd(map, 1.234, 0.567);
        assertClose(p[0], 1.234, 1.0e-14);
        assertClose(p[1], 0.567, 1.0e-14);
    }

    @Test
    void fk5DifferentEquinox() {
        // Aligning in ICRS lets the equinox difference produce a precession.
        Mapping map = skyMapping(sky("FK5"), sky("FK5", Pal.epj2d(2050.0), J2000), SkyFrame.AST__ICRS);
        double[] out = fwd(map, 1.0, 0.5);
        assertTrue(Math.abs(out[0] - 1.0) > 1.0e-6, "precession should be non-trivial");
        // Less than a degree over 50 years.
        assertTrue(Math.abs(out[0] - 1.0) < 0.02);
        assertTrue(Math.abs(out[1] - 0.5) < 0.02);
        double[] back = inv(map, out[0], out[1]);
        assertClose(back[0], 1.0, TOL);
        assertClose(back[1], 0.5, TOL);
    }

    @Test
    void fk4Fk5RoundTrip() {
        double b1950 = Pal.epb2d(1950.0);
        Mapping map = skyMapping(sky("FK4", b1950, b1950), sky("FK5"), SkyFrame.AST__FK5);
        double[] fk5 = fwd(map, 1.0, 0.5);
        double[] back = inv(map, fk5[0], fk5[1]);
        assertClose(back[0], 1.0, TOL);
        assertClose(back[1], 0.5, TOL);
    }

    @Test
    void icrsToFk5() {
        // ICRS and FK5 J2000 differ by the ~20 mas frame tie, well under 1e-4 rad.
        Mapping map = skyMapping(sky("ICRS"), sky("FK5"), SkyFrame.AST__FK5);
        double[] out = fwd(map, 2.0, 0.3);
        assertTrue(Math.abs(out[0] - 2.0) < 1.0e-4);
        assertTrue(Math.abs(out[1] - 0.3) < 1.0e-4);
    }

    @Test
    void galacticSupergalacticRoundTrip() {
        Mapping map = skyMapping(sky("GALACTIC"), sky("SUPERGALACTIC"), SkyFrame.AST__FK5);
        double[] sg = fwd(map, 1.5, 0.3);
        double[] back = inv(map, sg[0], sg[1]);
        assertClose(back[0], 1.5, TOL);
        assertClose(back[1], 0.3, TOL);
    }

    @Test
    void eclipticToFk5() {
        // Ecliptic (0, 0) is the vernal equinox, which in FK5 J2000 is RA 0, Dec 0. The longitude
        // may come out just below 2pi, so RA is compared as an angle.
        Mapping map = skyMapping(sky("ECLIPTIC"), sky("FK5"), SkyFrame.AST__FK5);
        double[] p = fwd(map, 0.0, 0.0);
        assertClose(remEuclid(p[0] + Math.PI, 2.0 * Math.PI) - Math.PI, 0.0, 1.0e-6);
        assertClose(p[1], 0.0, 1.0e-6);

        double[] fk5 = fwd(map, 2.0, 0.4);
        double[] back = inv(map, fk5[0], fk5[1]);
        assertClose(back[0], 2.0, TOL);
        assertClose(back[1], 0.4, TOL);
    }

    @Test
    void allSystemsRoundTrip() {
        // Each system to itself, aligned in FK5, with the same observer at both ends.
        for (int i = 0; i < NAMES.length; i++) {
            if (CODES[i] == SkyFrame.AST__UNKNOWN) {
                // Unknown to Unknown is tested separately.
                continue;
            }
            String name = NAMES[i];
            SkyFrame a = new SkyFrame();
            a.setSystem(name);
            a.setEpochMjd(J2000);
            a.setObsLatValue(0.5);
            SkyFrame b = (SkyFrame) a.copy();
            Mapping map = skyMapping(a, b, SkyFrame.AST__FK5);

            double[] out = fwd(map, 1.0, 0.5);
            assertClose(out[0], 1.0, TOL);
            assertClose(out[1], 0.5, TOL);

            double[] back = inv(map, 1.0, 0.5);
            assertTrue(
                    Math.abs(back[0] - 1.0) < TOL,
                    name + ": inverse lon failed (diff = " + Math.abs(back[0] - 1.0) + ")");
            assertTrue(
                    Math.abs(back[1] - 0.5) < TOL,
                    name + ": inverse lat failed (diff = " + Math.abs(back[1] - 0.5) + ")");
        }
    }

    // Unknown systems

    @Test
    void unknownToUnknownIsIdentity() {
        Mapping map = skyMapping(sky("Unknown"), sky("Unknown"), SkyFrame.AST__FK5);
        assertInstanceOf(UnitMap.class, map);
    }

    @Test
    void unknownToFk5HasNoConversion() {
        assertNull(SkyConvert.makeSkyMapping(sky("Unknown"), sky("FK5"), SkyFrame.AST__FK5));
    }

    // AZEL, GAPPT and HELIOECLIPTIC

    @Test
    void azelRoundTrip() {
        SkyFrame azel = sky("AZEL");
        azel.setObsLatValue(0.5);
        Mapping map = skyMapping(azel, sky("FK5"), SkyFrame.AST__FK5);

        // A point above the horizon; the azimuth may wrap.
        double[] radec = fwd(map, 1.0, 0.5);
        double[] back = inv(map, radec[0], radec[1]);
        double azErr = lonErr(back[0], 1.0);
        assertTrue(azErr < TOL, "AZEL round trip az: expected 1.0, got " + back[0] + " (err = " + azErr + ")");
        assertClose(back[1], 0.5, TOL);
    }

    @Test
    void gapptRoundTrip() {
        Mapping map = skyMapping(sky("GAPPT"), sky("FK5"), SkyFrame.AST__FK5);
        double[] fk5 = fwd(map, 2.0, 0.3);
        double[] back = inv(map, fk5[0], fk5[1]);
        assertClose(back[0], 2.0, TOL);
        assertClose(back[1], 0.3, TOL);
    }

    @Test
    void helioeclipticRoundTrip() {
        Mapping map = skyMapping(sky("HELIOECLIPTIC"), sky("FK5"), SkyFrame.AST__FK5);
        double[] fk5 = fwd(map, 1.5, 0.2);
        double[] back = inv(map, fk5[0], fk5[1]);
        assertClose(back[0], 1.5, TOL);
        assertClose(back[1], 0.2, TOL);
    }

    // Epoch alignment

    @Test
    void fk5EpochAlignmentInGalactic() {
        // Both Frames are FK5 J2000 with different Epochs, aligned in Galactic: the conversion is
        // close to the identity.
        Mapping map = skyMapping(sky("FK5"), sky("FK5", J2000, Pal.epj2d(2020.0)), SkyFrame.AST__GALACTIC);
        double[] out = fwd(map, 1.0, 0.5);
        assertClose(out[0], 1.0, 1.0e-6);
        assertClose(out[1], 0.5, 1.0e-6);
    }

    // SlaMap H2R and R2H

    @Test
    void h2rR2hRoundTrip() {
        double last = 1.5;
        SlaMap sla = new SlaMap();
        sla.add("H2R", new double[] {last});

        double ha = 0.8;
        double dec = 0.4;
        double[] out = fwd(sla, ha, dec);
        // RA + HA = LAST, compared in [0, 2pi).
        assertClose(remEuclid(out[0] + ha, 2.0 * Math.PI), remEuclid(last, 2.0 * Math.PI), 1.0e-10);
        assertClose(out[1], dec, 1.0e-10);

        double[] back = inv(sla, out[0], out[1]);
        assertClose(back[0], ha, 1.0e-10);
        assertClose(back[1], dec, 1.0e-10);
    }

    // Offset coordinates (skyOffsetMap: standard to offset)

    @Test
    void offsetIgnoredReturnsNone() {
        // C returns a UnitMap when SkyRefIs is Ignored (skyframe.c SkyOffsetMap).
        SkyFrame f = offsetFrame(new double[] {1.0, 0.5}, new double[] {0.0, HALF_PI}, "Ignored");
        assertInstanceOf(UnitMap.class, f.skyOffsetMap());
    }

    @Test
    void offsetOriginMapsOriginToSkyref() {
        // With SkyRefIs Origin, (0,0) in offset coordinates is SkyRef in the standard system.
        double[] skyRef = {1.0, 0.5};
        Mapping map =
                offsetFrame(skyRef, new double[] {1.0, 0.5 + 0.1}, "Origin").skyOffsetMap();
        double[] p = inv(map, 0.0, 0.0);
        assertClose(p[0], skyRef[0], 1.0e-10);
        assertClose(p[1], skyRef[1], 1.0e-10);
    }

    @Test
    void offsetOriginRoundTrip() {
        Mapping map = offsetFrame(new double[] {2.0, 0.3}, new double[] {2.0, 0.3 + 0.5}, "Origin")
                .skyOffsetMap();
        double[] std = inv(map, 0.1, 0.2);
        double[] back = fwd(map, std[0], std[1]);
        assertClose(back[0], 0.1, 1.0e-10);
        assertClose(back[1], 0.2, 1.0e-10);
    }

    @Test
    void offsetPoleMapsPoleToSkyref() {
        // With SkyRefIs Pole, the north pole of the offset system is SkyRef; the longitude there
        // is degenerate, so only the latitude is compared.
        double[] skyRef = {1.5, 0.8};
        Mapping map = offsetFrame(skyRef, new double[] {0.0, 0.0}, "Pole").skyOffsetMap();
        double[] p = inv(map, 0.0, HALF_PI);
        assertClose(p[1], skyRef[1], 1.0e-10);
    }

    @Test
    void offsetPoleRoundTrip() {
        Mapping map = offsetFrame(new double[] {0.5, 1.0}, new double[] {0.5, 0.0}, "Pole")
                .skyOffsetMap();
        double[] std = inv(map, 0.3, 0.4);
        double[] back = fwd(map, std[0], std[1]);
        assertClose(back[0], 0.3, 1.0e-10);
        assertClose(back[1], 0.4, 1.0e-10);
    }

    @Test
    void offsetOriginGalacticCentre() {
        // The offset origin at the galactic centre's FK5 J2000 position, with SkyRefP north of it.
        double raGc = Math.toRadians(266.405);
        double decGc = Math.toRadians(-28.936);
        Mapping map = offsetFrame(new double[] {raGc, decGc}, new double[] {raGc, decGc + 0.01}, "Origin")
                .skyOffsetMap();

        double[] p = inv(map, 0.0, 0.0);
        double err = lonErr(p[0], raGc);
        assertTrue(err < 1.0e-10, "expected lon ~= " + raGc + ", got " + p[0] + " (err = " + err + ")");
        assertClose(p[1], decGc, 1.0e-10);

        // A small positive latitude offset goes north, by about 0.01 rad.
        double[] p2 = inv(map, 0.0, 0.01);
        assertTrue(p2[1] > decGc, "positive latitude offset should go north of reference");
        assertClose(Pal.dsep(p[0], p[1], p2[0], p2[1]), 0.01, 1.0e-4);
    }

    @Test
    void offsetBadValues() {
        Mapping map = offsetFrame(new double[] {1.0, 0.5}, new double[] {1.0, 1.0}, "Origin")
                .skyOffsetMap();
        double[] p = inv(map, BAD, 0.5);
        assertEquals(BAD, p[0]);
        assertEquals(BAD, p[1]);
        double[] q = fwd(map, BAD, 0.5);
        assertEquals(BAD, q[0]);
        assertEquals(BAD, q[1]);
    }
}
