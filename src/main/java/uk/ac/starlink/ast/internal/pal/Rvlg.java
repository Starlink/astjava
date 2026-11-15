package uk.ac.starlink.ast.internal.pal;

import uk.ac.starlink.ast.internal.erfa.Pdp;
import uk.ac.starlink.ast.internal.erfa.S2c;

/**
 * The solar velocity due to Galactic rotation and translation: {@code palRvlg}.
 *
 * <p>Ported from Rust {@code pal/rvlg.rs}, itself ported from PAL's {@code palRvlg} ({@code
 * pal/palRvlg.c}, PAL v0.9.10-9-g75017db as vendored with AST), derived from SLALIB with
 * permission: Copyright Science and Technology Facilities Council, Rutherford Appleton Laboratory,
 * Tim Jenness and Patrick T. Wallace; GNU Lesser General Public License version 3 or later.
 */
public final class Rvlg {

    private Rvlg() {}

    /** The solar velocity due to Galactic rotation and translation (300 km/s towards l = 90 degrees, b = 0), as a J2000.0 equatorial vector in km/s. */
    private static final double[] VA = {-148.23284, 133.44888, -224.09467};

    /**
     * The component, in km/s, of the solar velocity due to Galactic rotation and translation towards the J2000.0 position {@code (r2000, d2000)}.
     */
    public static double rvlg(double r2000, double d2000) {
        double[] vb = S2c.s2c(r2000, d2000);
        return Pdp.pdp(VA, vb);
    }
}
