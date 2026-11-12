package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * The kinematic LSR velocity: {@code palRvlsrk}.
 *
 * <p>Ported from Rust {@code pal/rvlsrk.rs}, itself ported from PAL's {@code palRvlsrk} ({@code
 * pal/palRvlsrk.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Rvlsrk {

    private Rvlsrk() {}

    /** The standard solar motion (20 km/s towards RA 18h, Dec +30 degrees, 1900), as a J2000.0 equatorial vector in km/s. */
    private static final double[] VA = {-0.29000, 17.31726, -10.00141};

    /**
     * The component, in km/s, of the kinematic LSR velocity towards the J2000.0 position {@code (r2000, d2000)}.
     */
    public static double rvlsrk(double r2000, double d2000) {
        double[] vb = S2c.s2c(r2000, d2000);
        return Pdp.pdp(VA, vb);
    }
}
