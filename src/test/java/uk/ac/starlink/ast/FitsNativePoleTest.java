package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Translation of the unit tests of Rust {@code fitschan/fset_to_store/sky_pole.rs} that test
 * {@code wcs_nat_pole} (C {@code WcsNatPole}, fitschan.c:38906). The tests of {@code sky_pole}
 * belong to the FITS-WCS writer.
 */
class FitsNativePoleTest {

    /**
     * For TAN the fiducial point is the native north pole, so the native pole has the celestial
     * coordinates of the fiducial point (fitschan.c:39037-39039), and the default LONPOLE, pi as
     * delta0 is below theta0 (fitschan.c:39018-39023), is warned of.
     */
    @Test
    void wcsNatPoleFiducialAtNorthPole() {
        WcsMap wm = new WcsMap(2, WcsMap.AST__TAN, 1, 2);
        double alpha0 = 1.5;
        double delta0 = 0.7;
        FitsWarnings warnings = new FitsWarnings();
        FitsNativePole.NativePole result = FitsNativePole.wcsNatPole(wm, alpha0, delta0, null, null, warnings);
        assertNotNull(result.pole);
        assertEquals(alpha0, result.pole[0]);
        assertEquals(delta0, result.pole[1]);
        assertEquals(Math.PI, result.phip);
        assertEquals(1, warnings.size());
        assertEquals("nolonpole", warnings.list().get(0).condition);
    }
}
