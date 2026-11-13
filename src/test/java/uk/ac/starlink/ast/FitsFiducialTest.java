package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/fiducial.rs} that test
 * {@code get_fiducial_nsc} (C {@code GetFiducialNSC}, fitschan.c:12902). The tests of {@code
 * get_fiducial_wcs} belong to the FITS-WCS writer.
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
}
