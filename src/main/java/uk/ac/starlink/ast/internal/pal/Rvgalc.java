package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * The LSR velocity due to Galactic rotation: {@code palRvgalc}.
 *
 * <p>Ported from Rust {@code pal/rvgalc.rs}, itself ported from PAL's {@code palRvgalc} ({@code
 * pal/palRvgalc.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Rvgalc {

    private Rvgalc() {}

    /** The LSR velocity due to Galactic rotation (220 km/s towards l = 90 degrees, b = 0), as a J2000.0 equatorial vector in km/s. */
    private static final double[] VA = {-108.70408, 97.86251, -164.33610};

    /**
     * The component, in km/s, of the LSR velocity due to Galactic rotation towards the J2000.0 position {@code (r2000, d2000)}.
     */
    public static double rvgalc(double r2000, double d2000) {
        double[] vb = S2c.s2c(r2000, d2000);
        return Pdp.pdp(VA, vb);
    }
}
