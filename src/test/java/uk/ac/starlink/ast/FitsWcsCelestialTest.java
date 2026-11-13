package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.internal.pal.Pal;

/**
 * Translation of the unit tests of Rust {@code fitschan/wcs_celestial.rs} (C {@code
 * WcsCelestial}, fitschan.c:35988, {@code WcsNative}, fitschan.c:38518, and {@code WcsSkyFrame},
 * fitschan.c:39555). Rust's {@code mtr_rot} and {@code compress_matrix} tests test the rotation
 * C builds with {@code astMtrRot}, which here is {@link MatrixMap#mtrRot}. The tests after them
 * check where the port follows C rather than Rust.
 */
class FitsWcsCelestialTest {

    /** C's {@code AST__DD2R}. */
    private static final double DD2R = 0.017453292519943295;

    /** Rust's test helper {@code build_celestial}: the primary axes, DefB1950 false, no RFVAL. */
    private static FitsWcsCelestial.CelestialResult buildCelestial(FitsStore store) {
        return FitsWcsCelestial.buildCelestialWithDefB1950(store, ' ', false, null, new FitsWarnings());
    }

    /** A 2-axis store with the given CTYPE and CRVAL values. */
    private static FitsStore store2(String lon, String lat, double crval1, double crval2) {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = lon;
        store.ctype[1] = lat;
        store.crval[0] = crval1;
        store.crval[1] = crval2;
        return store;
    }

    /** The point {@code in} transformed forward by {@code map}. */
    private static double[] forward(Mapping map, double... in) {
        double[][] cols = new double[in.length][];
        for (int i = 0; i < in.length; i++) {
            cols[i] = new double[] {in[i]};
        }
        double[][] out = map.tranP(1, in.length, cols, true, map.getNout());
        double[] point = new double[out.length];
        for (int i = 0; i < out.length; i++) {
            point[i] = out[i][0];
        }
        return point;
    }

    @Test
    void detectsCelestialAxesTan() {
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store2("RA---TAN", "DEC--TAN", 180.0, 45.0));
        assertNotNull(cel);
        assertEquals(0, cel.lonAxis);
        assertEquals(1, cel.latAxis);
        // fitschan.c:39724-39730: with neither RADESYS nor EQUINOX and DefB1950 false, ICRS.
        assertEquals(SkyFrame.AST__ICRS, cel.frame.systemValue());
        assertEquals(2, cel.mapping.getNin());
        assertEquals(2, cel.mapping.getNout());
    }

    @Test
    void radesysIcrs() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 0.0, 0.0);
        store.radesys = "ICRS";
        assertEquals(SkyFrame.AST__ICRS, buildCelestial(store).frame.systemValue());
    }

    @Test
    void radesysFk5() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 0.0, 0.0);
        store.radesys = "FK5";
        store.equinox = 2000.0;
        assertEquals(SkyFrame.AST__FK5, buildCelestial(store).frame.systemValue());
    }

    @Test
    void noCelestialReturnsNone() {
        FitsStore store = new FitsStore(2);
        store.ctype[0] = "FREQ";
        store.ctype[1] = "STOKES";
        assertNull(buildCelestial(store));
    }

    @Test
    void galacticFromCtype() {
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store2("GLON-CAR", "GLAT-CAR", 0.0, 0.0));
        assertEquals(SkyFrame.AST__GALACTIC, cel.frame.systemValue());
    }

    /** The IWC origin is the fiducial point, whose celestial coordinates CRVAL gives. */
    @Test
    void referencePixelMapsToCrval() {
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store2("RA---TAN", "DEC--TAN", 180.0, 45.0));
        double[] point = forward(cel.mapping, 0.0, 0.0);
        assertEquals(Pal.dranrm(180.0 * DD2R), Pal.dranrm(point[0]), 1e-12);
        assertEquals(45.0 * DD2R, point[1], 1e-12);
    }

    @Test
    void equatorialNoRadesysNoEquinoxDefaultsToIcrs() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 180.0, 45.0);
        store.crpix[0] = 50.5;
        store.crpix[1] = 50.5;
        store.cdelt[0] = -0.01;
        store.cdelt[1] = 0.01;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        assertEquals(SkyFrame.AST__ICRS, cel.frame.systemValue());
        // fitschan.c:39729: ICRS leaves EQUINOX bad, so no Equinox is set and the SkyFrame's
        // default, J2000 (skyframe.c GetEquinox), applies; nor is an Epoch set.
        assertFalse(cel.frame.testEquinox());
        assertEquals(Pal.epj2d(2000.0), cel.frame.getEquinoxMjd());
        assertFalse(cel.frame.testEpoch());
    }

    @Test
    void naxisWcsmapBuiltWithFullRank() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.ctype[2] = "FREQ";
        store.crval[0] = 180.0;
        store.crval[1] = 45.0;
        store.crval[2] = 1.4e9;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        assertEquals(0, cel.lonAxis);
        assertEquals(1, cel.latAxis);
        assertEquals(3, cel.mapping.getNin());
        assertEquals(3, cel.mapping.getNout());
    }

    @Test
    void naxisCelestialPassthroughAxesAreIdentity() {
        FitsStore store = new FitsStore(3);
        store.ctype[0] = "RA---TAN";
        store.ctype[1] = "DEC--TAN";
        store.ctype[2] = "FREQ";
        store.crval[0] = 180.0;
        store.crval[1] = 45.0;
        store.crval[2] = 1.4e9;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        double[] p = forward(cel.mapping, 0.0, 0.0, 12345.0);
        assertEquals(Pal.dranrm(180.0 * DD2R), Pal.dranrm(p[0]), 1e-12);
        assertEquals(45.0 * DD2R, p[1], 1e-12);
        assertEquals(12345.0, p[2]);
    }

    @Test
    void galacticNoRadesysNoEquinoxDoesNotFallBack() {
        FitsStore store = store2("GLON-TAN", "GLAT-TAN", 101.255, 16.177);
        store.crpix[0] = 50.5;
        store.crpix[1] = 50.5;
        store.cdelt[0] = -0.01;
        store.cdelt[1] = 0.01;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        // fitschan.c:39855-39857: the Equinox is set only for equatorial and ecliptic systems.
        assertFalse(cel.frame.testEquinox());
        assertEquals(Pal.epj2d(2000.0), cel.frame.getEquinoxMjd());
        assertFalse(cel.frame.testEpoch());
    }

    /** AXREF overrides the reference position from CRVAL and forces it (fitschan.c:36677-36686). */
    @Test
    void axrefOverridesCrvalReferencePosition() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 120.0, 30.0);
        store.crpix[0] = 50.5;
        store.crpix[1] = 50.5;
        store.cdelt[0] = -0.01;
        store.cdelt[1] = 0.01;
        store.axref[0] = 119.5;
        store.axref[1] = 30.5;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        assertArrayEquals(new double[] {119.5 * DD2R, 30.5 * DD2R}, cel.refPos);
        assertTrue(cel.refPosForced);
    }

    /** Without AXREF the reference position is CRVAL, set only if SkyRef is not (fitschan.c:36566). */
    @Test
    void crvalReferencePositionIsNotForced() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 120.0, 30.0);
        store.crpix[0] = 50.5;
        store.crpix[1] = 50.5;
        store.cdelt[0] = -0.01;
        store.cdelt[1] = 0.01;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        assertEquals(120.0 * DD2R, cel.refPos[0]);
        assertFalse(cel.refPosForced);
    }

    /**
     * A rotation by -pi about Z leaves sin(-pi) off the diagonal, which C's {@code
     * CompressMatrix} (matrixmap.c), run by {@code astMtrRot}, sets to zero, leaving exactly
     * diag(-1, -1, 1).
     */
    @Test
    void compressMatrixZerosNearZeroOffDiagonals() {
        MatrixMap m = new MatrixMap(3, 3).mtrRot(-Math.PI, new double[] {0.0, 0.0, 1.0});
        assertArrayEquals(new double[] {-1.0, 0.0, 0.0}, forward(m, 1.0, 0.0, 0.0), 0.0);
        assertArrayEquals(new double[] {0.0, -1.0, 0.0}, forward(m, 0.0, 1.0, 0.0), 0.0);
        assertArrayEquals(new double[] {0.0, 0.0, 1.0}, forward(m, 0.0, 0.0, 1.0), 0.0);
    }

    /** An angle whose cosine is one leaves the matrix unchanged (matrixmap.c {@code MtrRot}). */
    @Test
    void mtrRotIdentityIsNoop() {
        double[][] full = {{0.5, -0.5, 0.1}, {0.5, 0.5, 0.2}, {0.0, 0.0, 1.0}};
        MatrixMap before = new MatrixMap(3, 3, full);
        MatrixMap after = before.mtrRot(0.0, new double[] {0.0, 0.0, 1.0});
        for (double[] in : new double[][] {{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}, {0.3, -2.0, 7.0}}) {
            assertArrayEquals(forward(before, in), forward(after, in));
        }
    }

    // ------------------------------------------------------------------
    // Where the port follows C
    // ------------------------------------------------------------------

    /**
     * C's {@code WcsNative} takes LONPOLE from parameter 3 of the longitude axis when it is set
     * (fitschan.c:38746-38748), so a header with {@code PV1_3} and no LONPOLE has no default
     * LONPOLE and no {@code nolonpole} warning.
     */
    @Test
    void lonpoleFromPv3() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 120.0, 30.0);
        store.pv[0][3] = 180.0;
        FitsWarnings warnings = new FitsWarnings();
        FitsWcsCelestial.buildCelestialWithDefB1950(store, ' ', false, null, warnings);
        assertTrue(warnings.list().stream().noneMatch(w -> "nolonpole".equals(w.condition)));
        FitsStore bare = store2("RA---TAN", "DEC--TAN", 120.0, 30.0);
        FitsWarnings bareWarnings = new FitsWarnings();
        FitsWcsCelestial.buildCelestialWithDefB1950(bare, ' ', false, null, bareWarnings);
        assertTrue(bareWarnings.list().stream().anyMatch(w -> "nolonpole".equals(w.condition)));
    }

    /**
     * With parameter 0 of the longitude axis non-zero, C shifts the IWC origin onto the fiducial
     * point given by parameters 1 and 2 (fitschan.c:36491-36520), so the IWC origin still maps to
     * CRVAL.
     */
    @Test
    void fiducialOffsetShiftsIwcOrigin() {
        FitsStore store = store2("RA---TAN", "DEC--TAN", 30.0, 40.0);
        store.pv[0][0] = 1.0;
        store.pv[0][1] = 0.0;
        store.pv[0][2] = 80.0;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        double[] p = forward(cel.mapping, 0.0, 0.0);
        assertEquals(30.0 * DD2R, Pal.dranrm(p[0]), 1e-12);
        assertEquals(40.0 * DD2R, p[1], 1e-12);
    }

    /** C's errors for malformed celestial CTYPE values (fitschan.c:36212-36330). */
    @Test
    void malformedAxesAreBdfts() {
        AstException e =
                assertThrows(AstException.class, () -> buildCelestial(store2("RA---XYZ", "DEC--XYZ", 0.0, 0.0)));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(e.getMessage().startsWith("astRead(FitsChan): FITS keyword 'CTYPE1' refers to an unknown"));
        e = assertThrows(AstException.class, () -> buildCelestial(store2("RA---TAN", "DEC--SIN", 0.0, 0.0)));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        e = assertThrows(AstException.class, () -> buildCelestial(store2("RA---TAN", "FREQ", 0.0, 0.0)));
        assertEquals(AstException.AST__BDFTS, e.getStatus());
        assertTrue(e.getMessage().contains("without a corresponding latitude axis"));
    }

    /** CarLin makes a CAR projection a plain shift by CRVAL in radians (fitschan.c:36371-36420). */
    @Test
    void carLinIsLinear() {
        FitsStore store = store2("RA---CAR", "DEC--CAR", 10.0, 20.0);
        store.carLin = true;
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store);
        double[] p = forward(cel.mapping, 1.0, 2.0);
        assertEquals(1.0 * DD2R + 10.0 * DD2R, p[0], 1e-15);
        assertEquals(2.0 * DD2R + 20.0 * DD2R, p[1], 1e-15);
        assertArrayEquals(new double[] {10.0 * DD2R, 20.0 * DD2R}, cel.refPos);
    }

    /** With latitude first, the SkyFrame's axes are swapped to match the Mapping's outputs. */
    @Test
    void latitudeFirstPermutesSkyFrame() {
        FitsWcsCelestial.CelestialResult cel = buildCelestial(store2("DEC--TAN", "RA---TAN", 45.0, 180.0));
        assertEquals(1, cel.lonAxis);
        assertEquals(0, cel.latAxis);
        double[] p = forward(cel.mapping, 0.0, 0.0);
        assertEquals(45.0 * DD2R, p[0], 1e-12);
        assertEquals(Pal.dranrm(180.0 * DD2R), Pal.dranrm(p[1]), 1e-12);
        assertEquals("Declination", cel.frame.getLabel(1));
    }
}
