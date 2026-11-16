package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/fiducial.rs} (C {@code
 * GetFiducialNSC} and {@code GetFiducialWCS}, fitschan.c). Rust's {@code invalid_axes_return_none}
 * is not translated: C reads past its array for an output that does not exist.
 */
class FitsFiducialTest {

    /** C's {@code AST__DD2R}. */
    private static final double DD2R = 0.017453292519943295;

    /** Without parameter 0 on the longitude axis, the reference point of the projection is used. */
    @Test
    void nscDefaultTanIsZeroZero() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        double[] nsc = FitsFiducial.getFiducialNsc(wm);
        assertEquals(wm.natLon(), nsc[0]);
        assertEquals(wm.natLat(), nsc[1]);
        // wcsmap.c PrjInfo: TAN's reference point is the native pole.
        assertEquals(0.0, nsc[0]);
        assertEquals(Math.PI / 2, nsc[1]);
    }

    /** A non-zero parameter 0 makes parameters 1 and 2, in degrees, the fiducial point. */
    @Test
    void nscPvOverridesNativeWhenPv0Set() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.setPV(0, 0, 1.0);
        wm.setPV(0, 1, 30.0);
        wm.setPV(0, 2, 60.0);
        double[] nsc = FitsFiducial.getFiducialNsc(wm);
        // fitschan.c:12949-12950: AST__DD2R times the parameter.
        assertEquals(DD2R * 30.0, nsc[0]);
        assertEquals(DD2R * 60.0, nsc[1]);
    }

    /** TPN holds polynomial terms in its parameters, so its reference point is always used. */
    @Test
    void nscTpnAlwaysUsesNative() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TPN, 1, 2);
        wm.setPV(0, 0, 1.0);
        wm.setPV(0, 1, 30.0);
        wm.setPV(0, 2, 60.0);
        double[] nsc = FitsFiducial.getFiducialNsc(wm);
        assertEquals(wm.natLon(), nsc[0]);
        assertEquals(wm.natLat(), nsc[1]);
    }

    /** Through a UnitMap the celestial fiducial point is the native one. */
    @Test
    void wcsWithUnitMap2ReturnsNativeFiducial() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        double[] nsc = FitsFiducial.getFiducialNsc(wm);
        double[] r = FitsFiducial.getFiducialWcs(wm, new UnitMap(2), 0, 1);
        assertEquals(nsc[0], r[0]);
        assertEquals(nsc[1], r[1]);
    }

    @Test
    void wcsWithPvOverridesPassesThroughToCelestial() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.setPV(0, 0, 1.0);
        wm.setPV(0, 1, 45.0);
        wm.setPV(0, 2, 30.0);
        double[] r = FitsFiducial.getFiducialWcs(wm, new UnitMap(2), 0, 1);
        assertEquals(DD2R * 45.0, r[0]);
        assertEquals(DD2R * 30.0, r[1]);
    }

    /** A bad celestial coordinate means the fiducial point cannot be found (C returns 0). */
    @Test
    void wcsWithABadValueReturnsNull() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        PermMap pm = new PermMap(2, new int[] {1, 2}, 2, new int[] {1, 0}, null);
        assertNull(FitsFiducial.getFiducialWcs(wm, pm, 0, 1));
    }

    /**
     * The projection plane coordinates of the fiducial point: the origin for a default TAN
     * projection, whose fiducial point is its reference point, and the projection of the native
     * point that parameters 1 and 2 of the longitude axis give otherwise.
     */
    @Test
    void ppcOfTheFiducialPoint() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        wm.invert();
        double[] ppc = FitsFiducial.getFiducialPpc(wm);
        assertEquals(0.0, ppc[0], 1e-15);
        assertEquals(0.0, ppc[1], 1e-15);

        WcsMap car = new WcsMap(2, WcsMap.AST__CAR, 1, 2);
        car.setPV(0, 0, 1.0);
        car.setPV(0, 1, 10.0);
        car.setPV(0, 2, 20.0);
        car.invert();
        ppc = FitsFiducial.getFiducialPpc(car);
        // CAR: x = phi, y = theta.
        assertEquals(DD2R * 10.0, ppc[0], 1e-15);
        assertEquals(DD2R * 20.0, ppc[1], 1e-15);
    }
}
