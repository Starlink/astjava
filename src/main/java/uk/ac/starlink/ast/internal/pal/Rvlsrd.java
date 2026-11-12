package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * The dynamical LSR velocity: {@code palRvlsrd}.
 *
 * <p>Ported from Rust {@code pal/rvlsrd.rs}, itself ported from PAL's {@code palRvlsrd} ({@code
 * pal/palRvlsrd.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Rvlsrd {

    private Rvlsrd() {}

    /** The peculiar solar motion of Delhaye (1965), (+9, +12, +7) km/s in Galactic Cartesian coordinates, as a J2000.0 equatorial vector in km/s. */
    private static final double[] VA = {0.63823, 14.58542, -7.80116};

    /**
     * The component, in km/s, of the dynamical LSR velocity towards the J2000.0 position {@code (r2000, d2000)}.
     */
    public static double rvlsrd(double r2000, double d2000) {
        double[] vb = S2c.s2c(r2000, d2000);
        return Pdp.pdp(VA, vb);
    }
}
